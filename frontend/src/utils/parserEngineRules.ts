/**
 * 解析引擎规则的**两面**与显式互转。
 *
 * - **KB 配置面**（`knowledge_bases.chunking_config.parserEngineRules`）：**camelCase**
 *   —— KB 配置 jsonb 键名已统一 camelCase（V2 存量迁移），后端视图
 *   `ChunkingConfigView.ParserEngineRuleView(fileTypes, engine, xlsxFirstRowAsHeader)`。
 * - **上传覆盖面**（`processConfig.parserEngineRules`）：**camelCase** —— 与 KB 配置面
 *   一致；该载荷由本仓前后端共同约束（后端只落 metadata，未接管线前不会被消费）。
 * - **智能体面**（`chatParserEngineRules`）：条目内仍是 **snake**（`rule.file_types`）——
 *   后端运行时 `ParserEngineRules.resolve` 按 snake 读，这条不改。
 *
 * 两面在 UI 里会互相搬运（上传确认对话框把 KB 配置搬进覆盖、再搬回 KB 配置），
 * 所以转换必须**显式**——直接透传会让「读侧按 snake 拿 camel 数据」崩掉
 * （实锤案例：KBParserSettings 对 camel 数据读 `rule.file_types.some` → 整页白屏）。
 */

export interface KbParserEngineRule {
  fileTypes: string[]
  engine: string
  xlsxFirstRowAsHeader?: boolean
}

export interface OverrideParserEngineRule {
  fileTypes: string[]
  engine: string
  xlsxFirstRowAsHeader?: boolean
}

function pickFileTypes(raw: Record<string, unknown>): string[] {
  const value = raw.fileTypes ?? raw.file_types
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
}

/**
 * 宽容归一化：接受 camel 或 snake（历史数据 / 混合来源），一律产出 **camel**。
 * 畸形项直接丢弃而不抛错——设置页不该被一条脏数据整页打崩。
 */
export function normalizeKbParserRules(raw: unknown): KbParserEngineRule[] {
  if (!Array.isArray(raw)) return []
  const out: KbParserEngineRule[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const rule = item as Record<string, unknown>
    const engine = typeof rule.engine === 'string' ? rule.engine.trim() : ''
    if (!engine) continue
    const xlsx = rule.xlsxFirstRowAsHeader ?? rule.xlsx_first_row_as_header
    out.push({
      fileTypes: pickFileTypes(rule),
      engine,
      ...(typeof xlsx === 'boolean' ? { xlsxFirstRowAsHeader: xlsx } : {}),
    })
  }
  return out
}

/** 覆盖规则（camel，含历史 snake）→ KB 配置规则（camel）。 */
export function fromOverrideParserRules(raw: unknown): KbParserEngineRule[] {
  return normalizeKbParserRules(raw)
}

/** KB 配置规则（camel）→ 上传覆盖规则（camel）。 */
export function toOverrideParserRules(rules: KbParserEngineRule[] | null | undefined): OverrideParserEngineRule[] {
  return (rules ?? []).map((rule) => ({
    fileTypes: [...(rule.fileTypes ?? [])],
    engine: rule.engine,
    ...(rule.xlsxFirstRowAsHeader !== undefined
      ? { xlsxFirstRowAsHeader: rule.xlsxFirstRowAsHeader }
      : {}),
  }))
}
