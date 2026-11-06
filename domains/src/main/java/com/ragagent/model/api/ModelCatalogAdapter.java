package com.ragagent.model.api;

import com.ragagent.contract.model.ModelCatalogPort;
import com.ragagent.contract.model.ModelView;
import com.ragagent.model.domain.Model;
import com.ragagent.model.mapper.ModelMapper;
import com.ragagent.model.service.ModelRowViews;
import com.ragagent.model.service.ModelService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link ModelCatalogPort} 的 model 侧实现：行读取与视图成形都发生在属主域。
 *
 * <p>实体 → 载荷视图的转换见 {@link ModelRowViews}（service 包，保持 api → service 单向）。</p>
 */
@Component
public class ModelCatalogAdapter implements ModelCatalogPort {

    private final ModelService modelService;
    private final ModelMapper modelMapper;

    public ModelCatalogAdapter(ModelService modelService, ModelMapper modelMapper) {
        this.modelService = modelService;
        this.modelMapper = modelMapper;
    }

    @Override
    public List<ModelRowView> listModels() {
        List<Model> rows = modelService.listModels();
        List<ModelRowView> out = new ArrayList<>();
        if (rows != null) {
            for (Model m : rows) {
                out.add(ModelRowViews.of(m));
            }
        }
        return out;
    }

    @Override
    public ModelRowView findModel(String modelId) {
        return ModelRowViews.of(modelService.getModelById(modelId));
    }

    @Override
    public java.util.Optional<ModelRowView> findModelOrEmpty(String modelId) {
        try {
            return java.util.Optional.ofNullable(ModelRowViews.of(modelService.getModelById(modelId)));
        } catch (ModelService.ModelNotFoundException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public ModelRowView findModelVisible(long tenantId, String modelId) {
        return ModelRowViews.of(modelService.getByIdVisible(tenantId, modelId));
    }

    @Override
    public ModelView getModelRow(String id) {
        return toView(modelService.getModelById(id));
    }

    @Override
    public ModelView getModelRowVisible(long tenantId, String id) {
        return toView(modelService.getByIdVisible(tenantId, id));
    }

    @Override
    public ModelView createModel(ModelView model) {
        Model saved = modelService.createModel(toEntity(model));
        // 域服务就地把 id/createdAt/status 写回**调用方那个对象** ⇒ 这里回填传入视图
        //（调用方随后依赖该对象的 id，例如初始化流程的 embeddingModelId 归属）。
        copyInto(model, saved);
        return toView(saved);
    }

    @Override
    public ModelView updateModel(ModelView model) {
        Model saved = modelService.updateModel(toEntity(model));
        copyInto(model, saved);
        return toView(saved);
    }

    /** 实体 → 契约视图（域内投影；字段逐一搬运，null 透传；parameters 走实体 getter 的空归一）。 */
    static ModelView toView(Model m) {
        if (m == null) {
            return null;
        }
        ModelView v = new ModelView();
        v.setId(m.getId());
        v.setTenantId(m.getTenantId());
        v.setName(m.getName());
        v.setDisplayName(m.getDisplayName());
        v.setType(m.getType());
        v.setSource(m.getSource());
        v.setDescription(m.getDescription());
        v.setParameters(m.getParameters());
        v.setIsDefault(m.isIsDefault());
        v.setIsBuiltin(m.isIsBuiltin());
        v.setManagedBy(m.getManagedBy());
        v.setStatus(m.getStatus());
        v.setCreatedAt(m.getCreatedAt());
        v.setUpdatedAt(m.getUpdatedAt());
        v.setDeletedAt(m.getDeletedAt());
        return v;
    }

    /** 把落库后的实体字段回填到传入视图（保持"同对象被填充"语义，调用方依赖；null 安全）。 */
    static void copyInto(ModelView v, Model m) {
        if (v == null || m == null) {
            return;
        }
        ModelView fresh = toView(m);
        v.setId(fresh.getId());
        v.setTenantId(fresh.getTenantId());
        v.setName(fresh.getName());
        v.setDisplayName(fresh.getDisplayName());
        v.setType(fresh.getType());
        v.setSource(fresh.getSource());
        v.setDescription(fresh.getDescription());
        v.setParameters(fresh.getParameters());
        v.setIsDefault(fresh.isIsDefault());
        v.setIsBuiltin(fresh.isIsBuiltin());
        v.setManagedBy(fresh.getManagedBy());
        v.setStatus(fresh.getStatus());
        v.setCreatedAt(fresh.getCreatedAt());
        v.setUpdatedAt(fresh.getUpdatedAt());
        v.setDeletedAt(fresh.getDeletedAt());
    }

    /** 契约视图 → 实体（写面用；字段逐一搬运，null 透传）。 */
    static Model toEntity(ModelView v) {
        if (v == null) {
            return null;
        }
        Model m = new Model();
        m.setId(v.getId());
        m.setTenantId(v.getTenantId());
        m.setName(v.getName());
        m.setDisplayName(v.getDisplayName());
        m.setType(v.getType());
        m.setSource(v.getSource());
        m.setDescription(v.getDescription());
        m.setParameters(v.getParameters());
        m.setIsDefault(v.isIsDefault());
        m.setIsBuiltin(v.isIsBuiltin());
        m.setManagedBy(v.getManagedBy());
        m.setStatus(v.getStatus());
        m.setCreatedAt(v.getCreatedAt());
        m.setUpdatedAt(v.getUpdatedAt());
        m.setDeletedAt(v.getDeletedAt());
        return m;
    }

    @Override
    public ModelRowView findModelRow(long tenantId, String modelId) {
        Model row = modelMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Model>()
                .eq(Model::getId, modelId)
                .eq(Model::getTenantId, tenantId)
                .last("LIMIT 1"));
        return ModelRowViews.of(row);
    }

}
