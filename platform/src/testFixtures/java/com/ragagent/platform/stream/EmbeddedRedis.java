package com.ragagent.platform.stream;

import io.lettuce.core.ClientOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 测试用的一次性 Redis。
 *
 * <h2>为什么不用 Spring 的自动配置</h2>
 * {@code RedisStreamManager} 的语义有一半在 <b>Lua 脚本与真实 TTL</b> 里：steer 的去重/CAS、
 * live-run 标记的续期，都不是能用内存假实现替代的东西（本项目其他 Redis 依赖点靠
 * {@code Fake*} 替身测，是因为那些接口的语义在 Java 侧；这里不是）。所以起一个真 redis-server。
 *
 * <h2>取二进制的方式</h2>
 * 依次尝试：
 * <ol>
 *   <li>环境变量 {@code REDIS_TEST_ADDR}（{@code host:port}）—— 指向已有实例，不启动进程；</li>
 *   <li>{@code redis-server} 在 {@code PATH} 上（Homebrew / apt 装的都算）；</li>
 *   <li>都没有 → {@link org.junit.jupiter.api.Assumptions#assumeTrue} 跳过，测试不会红。</li>
 * </ol>
 * 只绑 {@code 127.0.0.1}、只走回环，不依赖外部网络。
 *
 * <h2>端口分配：文件锁互斥，不做探测</h2>
 * 早先用 {@code new ServerSocket(0)} 探空闲端口，在 {@code maxParallelForks} 下会撞：
 * 两个 fork 可能拿到同一端口，先 bind 成功者的实例被后到者"借用"，前者跑完关进程时
 * 后者的连接全断（表现为 {@code Unable to connect to Redis}）。
 *
 * <p>现在改为固定端口池 + 跨进程文件锁：拿到 {@code port-<n>.lock} 的排他锁才用该端口，
 * 锁持有到 {@link #close()}；进程异常退出时操作系统自动释放。这样端口分配是确定互斥的，
 * 不依赖概率。</p>
 *
 * <p>跨包共享的测试基建：本包（StreamManager 的 Lua/TTL）与 IM 域 Redis 面
 * （{@code im.runtime.ImRedisStore} 的闸门 CAS / marker / inflight）同用。</p>
 */
public final class EmbeddedRedis implements AutoCloseable {

    /** 端口池起点。选在高位段，避开常见服务端口。 */
    private static final int PORT_POOL_START = 16390;

    /** 端口池容量：远大于并行 fork 数，留足重试空间。 */
    private static final int PORT_POOL_SIZE = 64;

    /** 锁文件目录（按临时目录隔离，跨 fork 共享）。 */
    private static final Path LOCK_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "ragagent-redis-ports");

    private final Process process;
    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate template;
    private final FileChannel portLockChannel;
    private final FileLock portLock;
    /** redis-server 的输出（崩溃/启动失败时写进跳过原因，CI 才看得到真因）。 */
    private final Path logFile;

    private EmbeddedRedis(Process process, String host, int port,
            FileChannel portLockChannel, FileLock portLock, Path logFile) {
        this.process = process;
        this.portLockChannel = portLockChannel;
        this.portLock = portLock;
        this.logFile = logFile;
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        // 命令超时压到 5s：CI 上实例假死时快速失败，而不是每个用例干等 60s（8 个 = 8 分钟）
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(5))
                .clientOptions(ClientOptions.builder()
                        .autoReconnect(true)
                        .build())
                .build();
        this.connectionFactory = new LettuceConnectionFactory(config, clientConfig);
        this.connectionFactory.afterPropertiesSet();
        this.template = new StringRedisTemplate(connectionFactory);
        this.template.afterPropertiesSet();
    }

    /**
     * 启动实例；不可用时返回 {@code null}（调用方据此 assume 跳过）。
     *
     * <p>进程启动失败不抛异常——测试环境没有 redis-server 是常态，该跳过而不是让整个构建红掉。</p>
     */
    public static EmbeddedRedis tryStart() {
        String external = System.getenv("REDIS_TEST_ADDR");
        if (external != null && !external.isEmpty()) {
            int colon = external.lastIndexOf(':');
            String h = external.substring(0, colon);
            int p = Integer.parseInt(external.substring(colon + 1));
            return new EmbeddedRedis(null, h, p, null, null, null);
        }

        Path binary = findRedisServer();
        if (binary == null) {
            return null;
        }
        try {
            Files.createDirectories(LOCK_DIR);
        } catch (IOException e) {
            return null;
        }

        for (int offset = 0; offset < PORT_POOL_SIZE; offset++) {
            int port = PORT_POOL_START + offset;
            FileChannel channel = null;
            FileLock lock = null;
            try {
                channel = FileChannel.open(LOCK_DIR.resolve("port-" + port + ".lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同 JVM 内已有线程持有该锁（跨 JVM 由 tryLock 返回 null 表达）
                releaseQuietly(channel, null);
                continue;
            } catch (IOException e) {
                releaseQuietly(channel, lock);
                continue;
            }
            if (lock == null) {
                // 该端口已被另一个 fork 持有
                releaseQuietly(channel, null);
                continue;
            }

            Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "ragagent-redis-" + port + ".log");
            Process process = start(binary, port, logFile);
            if (process != null && awaitReady(process, "127.0.0.1", port, Duration.ofSeconds(10))) {
                EmbeddedRedis instance = new EmbeddedRedis(process, "127.0.0.1", port, channel, lock,
                        logFile);
                if (instance.ping()) {
                    return instance;
                }
                // 端口通了但 Lettuce 真连不上（实例已在退出）——杀掉换下一个端口
                instance.close();
                continue;
            }
            if (process != null) {
                process.destroyForcibly();
            }
            releaseQuietly(channel, lock);
        }
        return null;
    }

    private static Process start(Path binary, int port, Path logFile) {
        List<String> cmd = new ArrayList<>(List.of(
                binary.toString(),
                "--port", Integer.toString(port),
                "--bind", "127.0.0.1",
                "--save", "",
                "--appendonly", "no",
                "--dir", System.getProperty("java.io.tmpdir"),
                "--daemonize", "no"));
        try {
            return new ProcessBuilder(cmd)
                    .redirectOutput(logFile.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return null;
        }
    }

    /** 真连一次，确认实例不仅能握手、还能执行命令。 */
    private boolean ping() {
        try {
            var connection = connectionFactory.getConnection();
            try {
                return "PONG".equalsIgnoreCase(connection.ping());
            } finally {
                connection.close();
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 实例此刻还能执行命令吗？
     *
     * <p>CI 上出现过「启动时 PING 通、随后所有命令超时」的环境问题（实例假死）。
     * 用例前先探一次，失败就带原因跳过，而不是 8 个用例各等 60 秒把 job 拖红。</p>
     */
    public boolean isResponsive() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (ping()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 跳过原因用的现场信息：进程是否活着 + redis-server 最近输出。 */
    public String diagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("alive=").append(process != null && process.isAlive());
        if (logFile != null) {
            try {
                String text = Files.exists(logFile) ? Files.readString(logFile) : "";
                if (!text.isBlank()) {
                    String tail = text.length() > 400 ? text.substring(text.length() - 400) : text;
                    b.append("; log=").append(tail.strip().replace('\n', ' '));
                }
            } catch (IOException ignored) {
                // 诊断读不到就只报 alive
            }
        }
        return b.toString();
    }

    public StringRedisTemplate template() {
        return template;
    }

    /** 清空本实例全部键——测试之间互不干扰。 */
    public void flushAll() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Override
    public void close() {
        // 先杀进程再关连接池：避免杀进程瞬间连接池在后台重连
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        try {
            connectionFactory.destroy();
        } catch (RuntimeException ignored) {
            // 关连接失败不影响测试结论
        }
        releaseQuietly(portLockChannel, portLock);
    }

    private static void releaseQuietly(FileChannel channel, FileLock lock) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 进程退出时由操作系统兜底
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    private static Path findRedisServer() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, "redis-server");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 等到端口可连、能回 {@code +PONG}、且自己起的进程仍然存活。
     *
     * <p>只测端口可连不够：CI 上出现过“启动瞬间端口可连、随后进程退出”的情况，
     * {@code tryStart} 会误判成功，测试真正发命令时才报
     * {@code Unable to connect to Redis}。这里用最小 RESP 命令验证实例真能服务，
     * 失败则换下一个端口；全部失败返回 {@code null}，回到既有“跳过”契约。</p>
     */
    private static boolean awaitReady(Process process, String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 200);
                socket.setSoTimeout(300);
                OutputStream out = socket.getOutputStream();
                out.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                if ("+PONG".equals(reader.readLine())) {
                    return true;
                }
            } catch (IOException ignored) {
                // 还没就绪，继续等
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
