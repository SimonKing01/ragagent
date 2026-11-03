package com.ragagent.contract.model;

import java.util.List;

/**
 * 模型目录读面（契约面）：跨域消费方按 id / 全量取"模型行"，不 import model 域实现。
 *
 * <p><b>为什么是端口</b>：mcp 的模型选用与运行时配置组装此前直连 model 域的
 * {@code ModelService}（域服务 ✗）并把 {@code Model} 实体当跨域视图传递（实体出域 ✗）。
 * 本端口把读路径收回属主域实现（{@code model/api/ModelCatalogAdapter}），
 * 调用方只 import 本端口与 {@code ModelRowView}。</p>
 *
 * <p>运行时的"模型行 → 各能力层配置"映射（{@code modelruntime.config.ModelRuntimeConfigs}）
 * 收 {@code ModelRowView} 而非实体——它属 L2，不得反向依赖模型域。</p>
 */
public interface ModelCatalogPort {

    /** 活跃状态值（与 model 域 {@code ModelService.STATUS_ACTIVE} 一致）。 */
    String ACTIVE_STATUS = "active";

    /** 全部模型行（顺序与迁移前 {@code ModelService.listModels()} 一致）。 */
    List<ModelRowView> listModels();

    /**
     * 按 id 取模型行；**失败语义与模型域 {@code ModelService.getModelById} 一致**
     * （id 空 / 不可见 / 状态异常 ⇒ 抛运行时异常）——调用方按既有"取模型失败即降级"处理。
     */
    ModelRowView findModel(String modelId);

    /** 按 id 取模型行；**不存在 ⇒ 空**（供"未找到即降级 / 转 404"的调用方，免于捕获域内异常）。 */
    java.util.Optional<ModelRowView> findModelOrEmpty(String modelId);

    /** 租户可见模型行（租户自有 或 内置）；不可见 ⇒ null。 */
    ModelRowView findModelVisible(long tenantId, String modelId);

    /** 严格按（id + 租户）取行（不含内置回落、不看删除标记）；无 ⇒ null。 */
    ModelRowView findModelRow(long tenantId, String modelId);

    // ── 管理写面（初始化向导 / 调试端点用） ──

    /** 按 id 取模型行（**全字段视图**，管理/初始化面用）；失败语义同 {@code #findModel}。 */
    ModelView getModelRow(String id);

    /** 租户可见模型行（**全字段视图**）；不可见 ⇒ null。 */
    ModelView getModelRowVisible(long tenantId, String id);

    /** 建模型行（返回落库后的行）。 */
    ModelView createModel(ModelView model);

    /** 更新模型行（返回更新后的行）。 */
    ModelView updateModel(ModelView model);

    /**
     * 模型行视图：挑模型（id/name/type）与造运行时配置（source/parameters）所需的最小字段集。
     *
     * @param defaultModel 是否为默认模型（实体 {@code isDefault}）
     * @param parameters   行上的参数（非 null；实体侧 getter 已做空归一）
     */
    record ModelRowView(String id, String name, String type, String source, String status,
                        boolean defaultModel, ModelParameters parameters) {
    }
}
