package com.ragagent.retrieval.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.vectorstore.ConnectionConfig;
import com.ragagent.contract.vectorstore.VectorStoreAddressField;
import com.ragagent.contract.vectorstore.VectorStoreEngineCatalogPort;
import com.ragagent.contract.vectorstore.VectorStoreEngineRules;
import com.ragagent.contract.vectorstore.VectorStoreRequiredField;
import com.ragagent.platform.security.SsrfGuard;
import com.ragagent.platform.web.JsonMappers;
import com.ragagent.retrieval.engine.doris.DorisRetrieveRepository;
import com.ragagent.retrieval.engine.milvus.MilvusRetrieveRepository;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;
import com.ragagent.retrieval.engine.qdrant.QdrantRetrieveRepository;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRestClient;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRetrieveRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 向量店引擎目录：控制面规则（必填 / SSRF 地址）+ 连通性探测。
 *
 * <p>规则与运行时共用 {@link EngineFactory} 的引擎注册表（同一份引擎知识）；
 * 探测的引擎覆盖与文案逐字沿用原
 * {@code domains.vectorstore.service.VectorStoreConfigService#testConnection} 的实现。</p>
 *
 * <p><b>TestConnection 的引擎覆盖（实现边界，原样保留）</b>：elasticsearch 走裸 HTTP GET
 * （含 basic auth、不跟随重定向、版本解析）；milvus 走 REST {@code collections/list} 探针
 * （版本恒 ""）；postgres 用 JDBC 探测；weaviate/opensearch 走 HTTP 探测；
 * qdrant/tencent/doris 以驱动探针替代——连接被拒的**错误文案逐字一致**，
 * 成功路径的 version 探测弱化为 ""。sqlite 为文件型引擎，恒 ""。</p>
 */
@Component
public class VectorStoreEngineCatalog implements VectorStoreEngineCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreEngineCatalog.class);
    private static final ObjectMapper MAPPER = JsonMappers.lenient();
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private final SsrfGuard ssrfGuard;

    public VectorStoreEngineCatalog(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public VectorStoreEngineRules rulesFor(String engineType) {
        EngineFactory.EngineSpec spec = EngineFactory.findSpec(engineType);
        if (spec == null) {
            return null;
        }
        List<VectorStoreRequiredField> required = spec.requiredFields().stream()
                .map(f -> new VectorStoreRequiredField(f.message(), f.satisfied()))
                .toList();
        List<VectorStoreAddressField> addresses = spec.controlAddressFields().stream()
                .map(f -> new VectorStoreAddressField(f.label(), f.value()))
                .toList();
        return new VectorStoreEngineRules(engineType == null ? "" : engineType, required, addresses);
    }

    /**
     * 连通性测试：成功返回探测版本（可空），
     * 失败抛 AppError（各分支均为 code 1000，
     * handler 以**双前缀**形态输出——golden vs-test-byid-connrefused 钉住）。
     */
    @Override
    public String probe(String engineType, ConnectionConfig config) {
        try {
            return switch (engineType == null ? "" : engineType) {
                case "elasticsearch" -> testElasticsearch(config);
                case "postgres" -> testPostgres(config);
                case "qdrant" -> testQdrant(config);
                case "milvus" -> testMilvus(config);
                case "tencent_vectordb" -> testTencentVectorDB(config);
                case "weaviate" -> testWeaviate(config);
                case "doris" -> testDoris(config);
                case "opensearch" -> testOpenSearch(config);
                case "sqlite" -> "";
                default -> throw BizException.badRequest(
                        "connection test not supported for engine type: " + engineType);
            };
        } catch (ConnectorFailure e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    /** 各探测器的失败载体（统一落 code 1000 AppError） */
    private static class ConnectorFailure extends RuntimeException {
        ConnectorFailure(String message) {
            super(message);
        }
    }

    // ── 各引擎探测（错误文案逐字固定，是契约） ──────────────────────────

    private String testElasticsearch(ConnectionConfig config) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TEST_TIMEOUT).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(TEST_TIMEOUT).GET();
        try {
            builder.uri(URI.create(config.addr));
        } catch (RuntimeException e) {
            throw new ConnectorFailure("failed to create elasticsearch request: invalid address");
        }
        if (config.username != null && !config.username.isEmpty()) {
            String token = Base64.getEncoder().encodeToString(
                    (config.username + ":" + (config.password == null ? "" : config.password))
                            .getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        HttpResponse<String> resp;
        try {
            resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ConnectorFailure("failed to connect to elasticsearch: connection refused or authentication failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorFailure("failed to connect to elasticsearch: connection refused or authentication failed");
        }
        if (resp.statusCode() != 200) {
            throw new ConnectorFailure("failed to connect to elasticsearch: authentication failed or server error");
        }
        try {
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode number = root.path("version").path("number");
            return number.isMissingNode() ? "" : number.asText("");
        } catch (Exception e) {
            return ""; // connected but version unparseable
        }
    }

    private String testPostgres(ConnectionConfig config) {
        if (config.useDefaultConnection) {
            return ""; // 默认连接恒可达；无 handle 查版本
        }
        String addr = config.addr == null ? "" : config.addr;
        String jdbcUrl = addr.startsWith("jdbc:")
                ? addr
                : "jdbc:postgresql://" + addr.replaceFirst("^postgres(ql)?://", "");
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl,
                config.username == null ? "" : config.username,
                config.password == null ? "" : config.password)) {
            var rs = conn.createStatement().executeQuery("SHOW server_version");
            return rs.next() ? rs.getString(1) : "";
        } catch (Exception e) {
            throw new ConnectorFailure("failed to connect to postgres: connection refused or authentication failed");
        }
    }

    /**
     * qdrant 探测：走驱动的健康探针
     * （gRPC HealthCheck → REST {@code GET /}），返回 {@code version}；失败折叠成通用文案。
     */
    private String testQdrant(ConnectionConfig config) {
        int port = config.port == 0 ? 6334 : config.port;
        try {
            return QdrantRetrieveRepository.testConnection(
                    config.host, port, config.apiKey, config.useTls, ssrfGuard);
        } catch (RuntimeException e) {
            log.warn("Qdrant connection test failed", e);
            throw new ConnectorFailure(
                    "failed to connect to qdrant: connection refused or authentication failed");
        }
    }

    /**
     * milvus 探测：走 REST v2 客户端 {@code collections/list} 探针
     * （连通性 + 认证都验到），仍返回 ""（Milvus 无版本端点）。
     */
    private String testMilvus(ConnectionConfig config) {
        try {
            return MilvusRetrieveRepository.testConnection(
                    config.addr, config.username, config.password, config.database, ssrfGuard);
        } catch (RuntimeException e) {
            log.warn("Milvus connection test failed", e);
            throw new ConnectorFailure(
                    "failed to connect to milvus: connection refused or server unreachable");
        }
    }

    /**
     * tencent_vectordb 探测：{@code ListDatabase}
     * 探针——客户端构造失败（地址/用户名/密钥不合法）→ "connection refused or authentication
     * failed"；调用失败 → "authentication failed or server error"。版本恒 ""。
     */
    private String testTencentVectorDB(ConnectionConfig config) {
        try {
            return TencentVectorDbRetrieveRepository
                    .testConnection(config.addr, config.username, config.apiKey, ssrfGuard);
        } catch (TencentVectorDbRestClient
                .TencentVectorDbApiException e) {
            log.warn("Tencent VectorDB list database failed", e);
            throw new ConnectorFailure(
                    "failed to connect to tencent vectordb: authentication failed or server error");
        } catch (RuntimeException e) {
            log.warn("Tencent VectorDB connection test failed", e);
            throw new ConnectorFailure(
                    "failed to connect to tencent vectordb: connection refused or authentication failed");
        }
    }

    private String testWeaviate(ConnectionConfig config) {
        String host = empty(config.host) ? "weaviate:8080" : config.host;
        String scheme = empty(config.scheme) ? "http" : config.scheme;
        HttpClient client = HttpClient.newBuilder().connectTimeout(TEST_TIMEOUT).build();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(scheme + "://" + host + "/v1/.well-known/ready"))
                    .timeout(TEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ConnectorFailure("failed to connect to weaviate: server not ready or authentication failed");
            }
        } catch (IOException e) {
            throw new ConnectorFailure("failed to connect to weaviate: server not ready or authentication failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorFailure("failed to connect to weaviate: server not ready or authentication failed");
        }
        try {
            HttpRequest meta = HttpRequest.newBuilder(URI.create(scheme + "://" + host + "/v1/meta"))
                    .timeout(TEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = client.send(meta, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode version = MAPPER.readTree(resp.body()).path("version");
                return version.isMissingNode() ? "" : version.asText("");
            }
        } catch (Exception e) {
            return ""; // connected but version unknown
        }
        return "";
    }

    /**
     * doris 探测：走 MySQL 协议驱动
     * 的完整探针——连接（Ping 语义）+ {@code SELECT @@version}（失败只 WARN，返回空版本）；
     * 版本串剥 {@code "Doris-"} 前缀（{@code "5.7.99 Doris-4.1.0"} → {@code "4.1.0"}）。
     * database 不强制：缺省用 information_schema。
     */
    private String testDoris(ConnectionConfig config) {
        if (empty(config.addr)) {
            throw new ConnectorFailure("failed to create doris connection: addr is required");
        }
        try {
            return DorisRetrieveRepository.testConnection(
                    config.addr, config.database, config.username, config.password);
        } catch (java.sql.SQLException e) {
            log.warn("Doris connection test failed", e);
            throw new ConnectorFailure(
                    "failed to connect to doris: connection refused or authentication failed");
        }
    }

    /**
     * opensearch 探测：走驱动的
     * 连通性探针（版本 + 每节点 k-NN 插件，TestConnection→构造期探针复用）；失败折叠
     * 成通用文案（不向 API 调用方暴露集群内部细节）。版本在探针内解析但不在此暴露
     * （lazy index 首用再校验）→ 恒 ""。
     */
    private String testOpenSearch(ConnectionConfig config) {
        String generic = "failed to connect to opensearch: check address, credentials, version (>= 2.4), "
                + "and that the k-NN plugin is installed";
        if (empty(config.addr)) {
            throw new ConnectorFailure("failed to create opensearch connection: addr is required");
        }
        try {
            OpenSearchRetrieveRepository.testConnection(
                    config.addr, config.username, config.password, config.insecureSkipVerify,
                    ssrfGuard);
        } catch (RuntimeException e) {
            log.warn("OpenSearch connection test failed", e);
            throw new ConnectorFailure(generic);
        }
        return "";
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
