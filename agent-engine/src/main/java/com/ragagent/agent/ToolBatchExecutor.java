package com.ragagent.agent;

import com.ragagent.agent.tool.ExecutionPolicy;
import com.ragagent.contract.agent.AgentToolCall;
import com.ragagent.contract.error.BizException;
import com.ragagent.contract.model.ToolCall;
import com.ragagent.contract.tool.ToolResult;
import com.ragagent.platform.access.TenantContext;
import com.ragagent.platform.access.TenantContextSnapshot;
import com.ragagent.platform.access.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具批次执行器：读并行、写屏障、并发上限 {@value #MAX_CONCURRENCY}；中断 = 取消 + 等齐。
 *
 * <p><b>调用序不变量</b>：结果按调用下标回填到对应槽位——乱序完成也稳定回填（收集与事件
 * 发射由调用方按序做）。中断后不再启动剩余调用，槽位补失败占位（槽不许留 null）。</p>
 *
 * <p>为什么单独成类：这段并发+中断语义与大文件里的业务编排无关；`ActPhase` 已超 600 行
 * 棘轮，拆在这里也让"读并行 / 写屏障 / 取消等齐"三件事集中可读。</p>
 */
final class ToolBatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolBatchExecutor.class);

    /** 单批并发上限。 */
    static final int MAX_CONCURRENCY = 8;

    private ToolBatchExecutor() {
    }

    /** 单次工具调用的执行回调。 */
    /** 输入是 LLM 侧的工具调用（{@code com.ragagent.contract.model.ToolCall}），产出是 agent 域的结果。 */
    interface CallRunner {
        /**
         * @param inWorker true = 运行在并发子线程（身份已 replay，按子线程上下文执行）；
         *                 false = 运行在引擎线程（执行屏障）
         */
        AgentToolCall run(int index, ToolCall call, boolean inWorker);
    }

    /**
     * 并发执行一批工具调用，返回**按调用序**排列的结果。
     *
     * @param tenant 身份快照（子线程自行 replay/clear；null = 在当前线程上下文里执行）
     */
    static List<AgentToolCall> execute(List<ToolCall> calls, int round,
            TenantContextSnapshot tenant, CallRunner runner) {
        int n = calls.size();
        log.info("[Agent][Round-{}] Parallel execution of {} tool calls", round, n);
        AgentToolCall[] results = new AgentToolCall[n];

        int i = 0;
        while (i < n) {
            if (!ExecutionPolicy.canRunConcurrently(calls.get(i).getFunction().getName())) {
                // 突变/有状态工具是屏障：先等之前的读全部落地，屏障完成后再开后面的读。
                results[i] = runner.run(i, calls.get(i), false);
                i++;
                continue;
            }
            int batchStart = i;
            while (i < n && ExecutionPolicy.canRunConcurrently(calls.get(i).getFunction().getName())) {
                i++;
            }
            int batchSize = i - batchStart;
            Semaphore permits = new Semaphore(Math.min(MAX_CONCURRENCY, batchSize));
            List<Thread> threads = new ArrayList<>(batchSize);
            for (int k = batchStart; k < i; k++) {
                final int idx = k;
                Runnable work = () -> {
                    boolean acquired = false;
                    try {
                        permits.acquire();
                        acquired = true;
                        results[idx] = runner.run(idx, calls.get(idx), true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[Agent][Round-{}] tool call interrupted", round);
                        // 结果槽不允许留 null：留空会让收集循环 emitToolOutcome(null) NPE
                        results[idx] = crashed(calls.get(idx), "tool call interrupted");
                    } catch (Throwable fatal) {
                        // 外围（modelContext 解码/langfuse span/eventBus emit）抛错会让线程死亡、
                        // results[idx] 保持 null，收集循环直接 NPE 炸掉整轮。兜底落失败结果。
                        log.warn("[Agent][Round-{}] tool call crashed", round, fatal);
                        results[idx] = crashed(calls.get(idx), BizException.wireText(fatal));
                    } finally {
                        // acquire 被中断时并没有持票——此时 release 会让许可数虚增，
                        // 后续批次的并发上限被悄悄放宽。
                        if (acquired) {
                            permits.release();
                        }
                    }
                };
                Thread t = Thread.ofVirtual().unstarted(() -> {
                    if (tenant != null) {
                        TenantScope.run(tenant, work);
                    } else {
                        try {
                            work.run();
                        } finally {
                            TenantContext.clear();
                        }
                    }
                });
                threads.add(t);
                t.start();
            }
            if (joinAllCancellingOnInterrupt(threads)) {
                // 中断按取消收束：剩余调用不再启动（结果槽不许留 null，补失败占位）。
                for (int j = i; j < n; j++) {
                    results[j] = crashed(calls.get(j), "tool call canceled");
                }
                break;
            }
        }

        List<AgentToolCall> ordered = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            ordered.add(results[k] == null
                    ? crashed(calls.get(k), "tool call not executed") : results[k]);
        }
        return ordered;
    }

    /**
     * 等齐全部子线程；父线程被中断时**先取消子线程，再继续等齐**，最后恢复中断标志。
     *
     * <p>中断语义：join 一被打断就跳过、继续收集结果是不行的——子线程还在跑，
     * "本轮收集 / 下一批启动 / 下一轮工具写"会与它们并发（跨越写屏障）。中断在这里
     * 必须等价于"取消 + 等齐"。</p>
     *
     * @return true = 期间发生过中断（调用方据此按取消收束本批）
     */
    static boolean joinAllCancellingOnInterrupt(List<Thread> threads) {
        boolean interrupted = false;
        for (Thread t : threads) {
            boolean joined = false;
            while (!joined) {
                try {
                    t.join();
                    joined = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                    // 取消全部子线程（含尚未 join 到的）——然后继续等它们真正退出。
                    for (Thread other : threads) {
                        other.interrupt();
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return interrupted;
    }

    /** 崩溃/中断/未执行的工具调用的失败占位结果（保证结果槽非 null）。 */
    private static AgentToolCall crashed(ToolCall tc, String error) {
        AgentToolCall crashed = new AgentToolCall();
        crashed.setId(tc.getId());
        crashed.setName(tc.getToolName());
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        crashed.setResult(r);
        return crashed;
    }
}
