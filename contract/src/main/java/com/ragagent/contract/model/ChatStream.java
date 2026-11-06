package com.ragagent.contract.model;

import java.util.concurrent.BlockingQueue;

/**
 * 流式响应句柄：队列语义不变（{@code take}/{@code poll} 与既有消费者一致），
 * 额外提供**显式关闭**。
 *
 * <p>为什么需要句柄：只给 {@link BlockingQueue} 时，"停止消费"不等于"取消模型"——
 * 用户取消后上游仍在读 SSE、并发 permit 仍被占用（只能等放弃阈值 120s 才靠猜释放）。
 * {@link #close()} 的契约是：</p>
 * <ol>
 *   <li><b>终止上游</b>：关闭 provider 连接 / 让读取循环退出（模型调用不再继续跑）；</li>
 *   <li><b>释放挂载资源</b>：并发 permit、langfuse 转发线程与观测收尾；</li>
 *   <li><b>解阻塞</b>：队列里补一个终态帧，仍在等待的消费者/转发线程能立刻收尾（不再靠超时）；</li>
 *   <li><b>幂等</b>：重复调用是 no-op；已自然收尾的流再关也是 no-op。</li>
 * </ol>
 *
 * <p>消费者纪律：轮次因取消 / 超时 / 异常而**提前**离开消费循环时，必须
 * {@code close()}（正常读到 done 收尾可以不关，实现里已释放）。</p>
 */
public interface ChatStream extends BlockingQueue<StreamResponse> {

    /** 见接口注释的四条契约；幂等。 */
    void close();

    /** 是否已被显式关闭（用于收尾判断，不表示"流已排干"）。 */
    boolean isClosed();
}
