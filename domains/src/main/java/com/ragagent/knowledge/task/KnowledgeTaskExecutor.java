package com.ragagent.knowledge.task;

import com.ragagent.knowledge.support.KnowledgeTaskRunner;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * 知识库域的后台任务执行器。
 *
 * <p>域内后台任务统一从这里起：每个任务一条虚拟线程（fire-and-forget 的轻量任务），
 * 可带任务名便于线程转储定位，容器关闭时统一停止接收新任务——本类是命名、
 * 关停与观测的统一入口。</p>
 *
 * <p>注意：任务内部仍遵循本仓约定——跨虚拟线程显式拷贝租户/用户上下文
 * （{@code TenantContext} 不共享 ThreadLocal）。</p>
 */
@Component
public class KnowledgeTaskExecutor implements KnowledgeTaskRunner {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 提交一个后台任务（无命名）。 */
    @Override
    public void submit(Runnable task) {
        executor.submit(task);
    }

    /**
     * 提交一个带名字的后台任务。
     *
     * @param taskName 任务名（写入线程名，便于线程转储/诊断时区分）
     */
    @Override
    public void submit(String taskName, Runnable task) {
        executor.submit(() -> {
            Thread.currentThread().setName(taskName);
            task.run();
        });
    }

    /** 容器关闭：停止接收新任务（已提交的虚拟线程任务自然跑完）。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
