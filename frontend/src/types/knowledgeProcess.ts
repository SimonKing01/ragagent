/**
 * 上传/重解析的逐文档处理覆盖（multipart 字段 `processConfig`）。
 *
 * 键名统一 **camelCase**：这是本仓自有的 JSON 面（后端只把它存进
 * `knowledge.metadata.processOverrides`，再由解析管线按需读取），
 * 不适用 Go 期遗留的 snake 约定；守卫 `check-json-key-case.py --strict`
 * 也禁止在自有 JSON 面上新增 snake 键。
 *
 * 兼容性：早期上传落库的旧数据是 snake 键，读侧（处理时间线 / 回填）保留
 * snake 兜底，写入一律用 camel。
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

export interface VLMConfigOverride {
  enabled?: boolean
  modelId?: string
  descriptionLanguage?: string
  customInstructions?: string
}

export interface ASRConfigOverride {
  enabled?: boolean
  modelId?: string
  language?: string
}

export interface QuestionGenerationConfigOverride {
  enabled?: boolean
  questionCount?: number
  customInstructions?: string
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

export interface ExtractConfigOverride {
  enabled?: boolean
  text?: string
  tags?: string[]
  nodes?: GraphNodeOverride[]
  relations?: GraphRelationOverride[]
  customInstructions?: string
}

export interface KnowledgeProcessOverrides {
  parserEngineRules?: ParserEngineRule[]
  chunkingConfig?: ChunkingConfigOverride
  /** 逐文档图片处理覆盖：解析管线消费 ocrEnabled（图片 OCR 开关） */
  imageProcessingConfig?: { ocrEnabled?: boolean }
  enableMultimodel?: boolean
  vlmConfig?: VLMConfigOverride
  asrConfig?: ASRConfigOverride
  /** 逐文档推荐问题覆盖：解析管线消费 enabled / questionCount */
  questionGenerationConfig?: QuestionGenerationConfigOverride
  graphEnabled?: boolean
  extractConfig?: ExtractConfigOverride
  parserEngineOverrides?: Record<string, string>
}
