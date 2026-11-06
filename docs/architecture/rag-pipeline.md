# RAG 管线：阶段、插件与装配

管线不是一条写死的 `for` 循环，而是 **事件 → 插件链**：

- `PipelineEventType` 是 14 个值的 enum（编译期注册表）；
- `EventManager` 按 `eventType` 维护 `List<Plugin>` 与闭包链；
- `QaWiring` 负责注册；
- `PipelineBuilder.presets()` 决定某条业务路径按什么顺序触发哪些阶段。

## 执行语义

`Plugin.onEvent(eventType, chatManage, next)` 返回 `PluginError`：

- `null` = 成功；
- 非 `null` = 该阶段失败（`PluginError.SEARCH_NOTHING` 是"没搜到"的哨兵，不是异常）；
- `next.invoke()` 调用链的下一环。

`EventManager.buildHandler` 从**后往前**包闭包，因此**注册顺序就是执行顺序**：先注册的在外层，先执行。

## 14 个阶段

注册位置：`domains/.../session/assembly/QaWiring.java`。

| 阶段 | 执行的插件（按序） | 说明 |
|---|---|---|
| `LOAD_HISTORY` | `PluginLoadHistory` | 载入会话历史；`rag_stream` 第一站 |
| `MEMORY_RECALL` | `PluginMemoryRecall` | 长期记忆召回（条件阶段） |
| `QUERY_UNDERSTAND` | `PluginQueryUnderstand` → `PluginExtractEntity` | 查询改写/意图理解，再做实体抽取 |
| `CHUNK_SEARCH` | `PluginSearch` | 普通 `rag` 预设的分块检索 |
| `CHUNK_SEARCH_PARALLEL` | `PluginSearchParallel` | 主问答默认；内部复用已注册的 `PluginSearch` + `PluginSearchEntity` 同一批实例（构造注入） |
| `ENTITY_SEARCH` | `PluginSearchEntity` | 实体/图谱检索（条件阶段） |
| `CHUNK_RERANK` | `PluginRerank` → `PluginWikiBoost` → `PluginMemoryAffinity` | 三插件按注册序执行 |
| `WEB_FETCH` | `PluginWebFetch` | 网页抓取（条件阶段） |
| `CHUNK_MERGE` | `PluginMerge` | 合并/去重 |
| `FILTER_TOP_K` | `PluginFilterTopK` | TopK 过滤 |
| `DATA_ANALYSIS` | `PluginDataAnalysis` | 表格/数据分析（条件阶段） |
| `INTO_CHAT_MESSAGE` | `PluginIntoChatMessage` | 检索结果转消息与引用 |
| `CHAT_COMPLETION` | `PluginChatCompletion` | 非流式生成 |
| `CHAT_COMPLETION_STREAM` | `PluginChatCompletionStream` | 流式生成（主路径） |

## 预设管线

`PipelineBuilder.presets()`：

| 预设 | 阶段序列 |
|---|---|
| `chat` | `CHAT_COMPLETION` |
| `chat_stream` | `CHAT_COMPLETION_STREAM` |
| `chat_history_stream` | `LOAD_HISTORY` → `CHAT_COMPLETION_STREAM` |
| `rag` | `CHUNK_SEARCH` → `CHUNK_RERANK` → `CHUNK_MERGE` → `INTO_CHAT_MESSAGE` → `CHAT_COMPLETION` |
| `rag_stream` | `LOAD_HISTORY` → `QUERY_UNDERSTAND` → `CHUNK_SEARCH_PARALLEL` → `CHUNK_RERANK` → `CHUNK_MERGE` → `FILTER_TOP_K` → `DATA_ANALYSIS` → `INTO_CHAT_MESSAGE` → `CHAT_COMPLETION_STREAM` |

条件阶段（记忆召回、网页抓取、实体检索）由业务配置追加，或由 `PluginSearchParallel` 在内部执行；不在默认 `rag_stream` 序列里。

## `PluginSearchParallel` 与两个检索插件的关系

它把"分块检索"和"实体检索"并行 fan-out，再合并结果。两个内部插件由 `QaWiring` 构造传入，且是**已注册的同一批实例**：`PluginSearch` 同时是 `CHUNK_SEARCH` 的执行体、`PluginSearchEntity` 同时是 `ENTITY_SEARCH` 的执行体；在并行分支里它们再次以内部执行体身份运行（见 `QaWiring` 的 `mgr.register(searchPlugin)` … `new PluginSearchParallel(searchPlugin, searchEntityPlugin)`）。

两点要知道：

- 并行分支与主链共享同一份 `ChatManage` 可变状态，两个执行序都可能写它，读检索结果时留意；
- 给 `CHUNK_SEARCH` / `ENTITY_SEARCH` 换实现时，并行分支会一起变——同一实例，不是两套。

## 加一个新阶段

1. 在 `PipelineEventType` 加枚举值；
2. 实现 `Plugin`，`activationEvents()` 返回新常量；
3. 在 `QaWiring` 注册——**顺序即执行顺序**；
4. 如果默认路径要跑，加入 `PipelineBuilder.presets()` 的对应预设；
5. 跑 `mvn -B -pl boot test -Dtest='*Pipeline*'`——`PipelineRegistryCompletenessTest` 在 boot 测试树，会要求该阶段至少注册一个插件；录制测试与全量口径见 `mvn -B verify`；
6. 不要重排已有阶段：SSE 帧顺序与金片都锁着。

## 相关文件

- `domains/.../session/rag/PipelineEventType.java`
- `domains/.../session/rag/EventManager.java`
- `domains/.../session/rag/PipelineBuilder.java`
- `domains/.../session/rag/plugin/`
- `domains/.../session/assembly/QaWiring.java`
