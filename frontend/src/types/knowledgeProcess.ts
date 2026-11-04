/** Matches backend types.KnowledgeProcessOverrides (snake_case JSON). */

export interface ParserEngineRule {
  file_types: string[]
  engine: string
  xlsx_first_row_as_header?: boolean
}

export interface ChunkingConfigOverride {
  chunk_size?: number
  chunk_overlap?: number
  separators?: string[]
  parser_engine_rules?: ParserEngineRule[]
  enable_parent_child?: boolean
  parent_chunk_size?: number
  child_chunk_size?: number
  strategy?: string
  token_limit?: number
  languages?: string[]
  table_metadata_instructions?: string
}

export interface VLMConfigOverride {
  enabled?: boolean
  modelId?: string
  description_language?: string
  custom_instructions?: string
}

export interface ASRConfigOverride {
  enabled?: boolean
  modelId?: string
  language?: string
}

export interface QuestionGenerationConfigOverride {
  enabled?: boolean
  question_count?: number
  custom_instructions?: string
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
  custom_instructions?: string
}

export interface KnowledgeProcessOverrides {
  parser_engine_rules?: ParserEngineRule[]
  chunking_config?: ChunkingConfigOverride
  /** 逐文档图片处理覆盖：目前只消费 ocrEnabled（图片 OCR 开关） */
  imageProcessingConfig?: { ocrEnabled?: boolean }
  enable_multimodel?: boolean
  vlm_config?: VLMConfigOverride
  asr_config?: ASRConfigOverride
  question_generation_config?: QuestionGenerationConfigOverride
  graph_enabled?: boolean
  extract_config?: ExtractConfigOverride
  parser_engine_overrides?: Record<string, string>
}
