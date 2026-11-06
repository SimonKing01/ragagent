package com.ragagent.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.contract.tenant.StorageEngineConfig.CosEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.Ks3EngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.LocalEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.MinioEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.ObsEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.OssEngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.S3EngineConfig;
import com.ragagent.contract.tenant.StorageEngineConfig.TosEngineConfig;

/**
 * 租户配置族的 Jackson mixin（契约面纯 JDK 的配套）。
 *
 * <p>承载的线格式：{@code @JsonInclude(NON_NULL)}（provider 子结构为 null 时省略键）与
 * {@code @JsonIgnoreProperties}；子结构（Local/Minio/Cos/Tos/S3/Oss/Ks3/Obs）**无注解**，
 * 不需要 mixin。golden {@code ct-kv-storage-*} 钉住其输出形态。</p>
 */
public final class ContractJsonMixinsTenant {

    private ContractJsonMixinsTenant() {
    }

    /** APIPrincipalConfig 的 mixin（注解逐条对位）。 */
    public abstract static class APIPrincipalConfigMixin {
    }

    /** ChatHistoryConfig 的 mixin（注解逐条对位）。 */
    public abstract static class ChatHistoryConfigMixin {
    }

    /** RetrievalConfig 的 mixin（注解逐条对位）。 */
    public abstract static class RetrievalConfigMixin {
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private int rrfK;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private double rrfVectorWeight;

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        private double rrfKeywordWeight;

    }

    /** StorageEngineConfig 的 mixin（注解逐条对位）。 */
    public abstract static class StorageEngineConfigMixin {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private LocalEngineConfig local;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private MinioEngineConfig minio;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private CosEngineConfig cos;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private TosEngineConfig tos;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private S3EngineConfig s3;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private OssEngineConfig oss;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Ks3EngineConfig ks3;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private ObsEngineConfig obs;

    }

}
