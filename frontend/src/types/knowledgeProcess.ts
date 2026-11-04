/**
 * 上传/重解析的逐文档处理覆盖（multipart 字段 `processConfig`）。
 *
 * 键名统一 **camelCase**：这是本仓自有的 JSON 面（后端只把它存进
 * `knowledge.metadata.processOverrides`，再由解析管线按需读取），
 * 不适用 Go 期遗留的 snake 约定；守卫 `check-json-key-case.py --strict`
 * 也禁止在自有 JSON 面上新增 snake 键。
 *
 * 只保留**后端解析管线真正消费**的字段（见下），其余项已删除：
 * - imageProcessingConfig.ocrEnabled → KnowledgeImageOcr
 * - questionGenerationConfig.enabled / questionCount → QuestionGenerationPolicy
 * - chunkingConfig.* → KnowledgeChunkWriter
 * - parserEngineRules → KnowledgeTextExtractor
 * - graphEnabled → KnowledgeFinalizingStage
 *
 * 兼容性：早期上传落库的旧数据是 snake 键且可能带已废弃字段，读侧保留兜底，
 * 写入一律用下面的 camel 字段。
 */

export interface ParserEngineRule {
  fileTypes: string[]
  engine: string
  xlsxFirstRowAsHeader?: boolean
}

export interface ChunkingConfigOverride {
  chunkSize?: number
  chunkOverlap?: number
  separators?: string[]
  parserEngineRules?: ParserEngineRule[]
  enableParentChild?: boolean
  parentChunkSize?: number
  childChunkSize?: number
  strategy?: string
  tokenLimit?: number
  languages?: string[]
  tableMetadataInstructions?: string
}

export interface GraphNodeOverride {
  name: string
  attributes?: string[]
}

export interface GraphRelationOverride {
  node1: string
  node2: string
  type: string
}

/** 图谱抽取配置：节点/关系示例、标签与自定义指令都会进抽取提示词 */
export interface ExtractConfigOverride {
  enabled?: boolean
  text?: string
  tags?: string[]
  nodes?: GraphNodeOverride[]
  relations?: GraphRelationOverride[]
  customInstructions?: string
}

export interface QuestionGenerationConfigOverride {
  enabled?: boolean
  questionCount?: number
}

export interface KnowledgeProcessOverrides {
  parserEngineRules?: ParserEngineRule[]
  chunkingConfig?: ChunkingConfigOverride
  /** 逐文档图片处理覆盖：解析管线消费 ocrEnabled（图片 OCR 开关） */
  imageProcessingConfig?: { ocrEnabled?: boolean }
  /** 逐文档推荐问题覆盖：解析管线消费 enabled / questionCount */
  questionGenerationConfig?: QuestionGenerationConfigOverride
  /** 图谱抽取开关：KB 打开时可按文档关闭 */
  graphEnabled?: boolean
  /** 逐文档图谱抽取配置：解析管线消费（节点/关系/标签/指令进提示词） */
  extractConfig?: ExtractConfigOverride
}
