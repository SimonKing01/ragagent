# Port 对照表

> 由 `scripts/gen-port-map.py` 从代码生成，**不要手改**；`--check` 守卫防漂移。
> 读代码时先查这张表：接口 → 实现在哪个类 → 谁在调用。

## 统计

- 域间（解耦）：**22**
- 复合面（随复合端口）：**7**
- 跨模块（必要）：**12**

## 明细

| Port | 实现类 | 实现模块 | 调用方域 | 类型 |
|---|---|---|---|---|
| `AuditWritePort` | `AuditWriteAdapter` | domains | agentmanagement、auth、datasource、knowledge、platform、system | 跨模块（必要） |
| `ChunkFaqReadPort` | — | ? | — | 复合面（随复合端口） |
| `DocReaderAdminPort` | `DocReaderAdminAdapter` | domains | knowledge、system | 域间（解耦） |
| `DocReaderParsePort` | `DocReaderParseAdapter` | domains | im、initialization、session | 域间（解耦） |
| `DocumentChunkingPort` | `DocumentChunkingAdapter` | domains | session | 域间（解耦） |
| `EmbedSessionPort` | `EmbedSessionAdapter` | domains | embedchannel | 域间（解耦） |
| `EmbeddingModelPort` | `EmbeddingModelAdapter` | domains | wiki | 域间（解耦） |
| `EmbeddingPort` | `ModelRuntimeEmbeddingAdapter` | model-runtime | knowledge、memory、retrieval-engine | 跨模块（必要） |
| `EntityExtractionPort` | `EntityExtractionAdapter` | model-runtime | initialization、knowledge、session | 跨模块（必要） |
| `FileUrlPort` | `DefaultFileUrlPort` | domains | knowledge、platform、session、storage | 跨模块（必要） |
| `GlobalFileServicePort` | `GlobalFileServiceAdapter` | domains | session | 域间（解耦） |
| `HybridSearchPort` | `HybridSearchService` | retrieval-engine | knowledge、session | 跨模块（必要） |
| `KnowledgeBaseCatalogPort` | `KnowledgeBaseCatalogAdapter` | domains | agentmanagement | 域间（解耦） |
| `KnowledgeBaseReadPort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeBaseWritePort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeCatalogPort` | `KnowledgeCatalogAdapter` | domains | datasource、embedchannel、im、initialization、knowledge、session | 域间（解耦） |
| `KnowledgeContentWritePort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeDeletePort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeEntryReadPort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeIngestLookupPort` | — | ? | — | 复合面（随复合端口） |
| `KnowledgeMaintenancePort` | `KnowledgeMaintenanceAdapter` | domains | websearch | 域间（解耦） |
| `KnowledgeSpanPort` | `KnowledgeSpanAdapter` | domains | wiki | 域间（解耦） |
| `McpToolRegistrationPort` | `McpToolRegistrationAdapter` | domains | agent-engine | 跨模块（必要） |
| `MessageTranscriptPort` | `MessageTranscriptAdapter` | domains | memory | 域间（解耦） |
| `ModelCatalogPort` | `ModelCatalogAdapter` | domains | initialization、knowledge、mcp、memory、model-runtime、session、wiki | 跨模块（必要） |
| `ModelFactsPort` | `ModelService` | domains | retrieval-engine | 跨模块（必要） |
| `OllamaAdminPort` | `OllamaService` | model-runtime | model | 跨模块（必要） |
| `ParserEngineCatalogPort` | `ParserEngineCatalog` | domains | system | 域间（解耦） |
| `ProviderFileStorePort` | `ProviderFileStoreAdapter` | domains | knowledge | 域间（解耦） |
| `ResourceCatalogPort` | `ResourceCatalogAdapter` | domains | knowledge、session | 域间（解耦） |
| `SessionQaPort` | `SessionQaAdapter` | domains | im | 域间（解耦） |
| `SkillCatalogPort` | `SkillCatalogService` | agent-engine | agent-engine、agentmanagement | 跨模块（必要） |
| `StorageBackendPort` | `StorageBackendPortAdapter` | domains | initialization、knowledge、storage | 域间（解耦） |
| `StorageConnectivityPort` | `StorageConnectivityService` | domains | storage、system | 域间（解耦） |
| `TenantStorageQuotaPort` | `TenantStorageQuotaAdapter` | domains | knowledge、system | 域间（解耦） |
| `ToolRegistryPort` | `ToolRegistryPortImpl` | agent-engine | agent-engine、mcp | 跨模块（必要） |
| `UserLookupPort` | `UserLookupAdapter` | domains | agentmanagement | 域间（解耦） |
| `VectorStoreEngineCatalogPort` | `VectorStoreEngineCatalog` | retrieval-engine | vectorstore | 跨模块（必要） |
| `WebSearchProviderPort` | `WebSearchSupportAdapter` | domains | session | 域间（解耦） |
| `WebSearchTempStatePort` | `WebSearchSupportAdapter` | domains | session | 域间（解耦） |
| `WikiPageOpsPort` | `WikiPageOpsAdapter` | domains | session | 域间（解耦） |
