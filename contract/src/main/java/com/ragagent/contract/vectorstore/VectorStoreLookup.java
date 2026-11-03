package com.ragagent.contract.vectorstore;


/**
 * 向量库定义**只读端口**：L2 {@code retrieval} 的引擎注册表与归属检查需要
 * 「按 (租户, 库 id) 取一个向量库」，但不该依赖 {@code vectorstore} 域的实体与 mapper。
 *
 * <p>实现留在 {@code vectorstore} 侧（{@code VectorStoreRepository#getById}），
 * 过滤条件与迁移前逐字一致。</p>
 */
public interface VectorStoreLookup {

    /**
     * 按（租户, 库 id）取向量库；不存在返回 {@code null}。
     *
     * <p>调用方注意两处既有语义（与迁移前一致）：① 查询抛异常时由<b>调用方</b>决定姿态
     * （{@code EngineRegistry} 会转成"暂时不可用"，而不是"不存在"）；② 返回 {@code null}
     * 同时表示"不存在"与"不属于该租户"——归属检查依赖后者。</p>
     */
    VectorStoreView byId(long tenantId, String storeId);
}
