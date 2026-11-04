import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  fromOverrideParserRules,
  normalizeKbParserRules,
  toOverrideParserRules,
} from './parserEngineRules.ts'

// 解析引擎规则的「两面」互转。
// KB 配置面是 camel（键名统一 camel + V2 迁移；后端 ParserEngineRuleView(fileTypes, …)），
// 覆盖/智能体面是 snake（后端运行时 ParserEngineRules.resolve 读 file_types）。
// 旧实现按 snake 直读 KB 配置（camel）→ `undefined.some` 把整页打崩；写入侧也会因
// 键名不对而被后端丢弃（库里出现 fileTypes: []）。

test('归一架：camel 与 legacy snake 都吃，一律产出 camel', () => {
  const camel = normalizeKbParserRules([
    { engine: 'builtin', fileTypes: ['pdf'], xlsxFirstRowAsHeader: false },
  ])
  assert.deepEqual(camel, [{ engine: 'builtin', fileTypes: ['pdf'], xlsxFirstRowAsHeader: false }])

  const legacy = normalizeKbParserRules([
    { engine: 'simple', file_types: ['csv', 'xlsx'], xlsx_first_row_as_header: true },
  ])
  assert.deepEqual(legacy, [{ engine: 'simple', fileTypes: ['csv', 'xlsx'], xlsxFirstRowAsHeader: true }])
})

test('归一架：畸形项丢弃、缺 fileTypes 给空数组、非字符串元素滤掉（不抛错）', () => {
  assert.deepEqual(normalizeKbParserRules(null), [])
  assert.deepEqual(normalizeKbParserRules('nope'), [])
  assert.deepEqual(normalizeKbParserRules([1, null, 'x']), [])

  // 缺 engine → 丢弃（无法应用）
  assert.deepEqual(normalizeKbParserRules([{ fileTypes: ['pdf'] }]), [])
  // 缺 fileTypes → 空数组（规则不匹配任何类型，但保留数据）
  assert.deepEqual(normalizeKbParserRules([{ engine: 'builtin' }]), [
    { engine: 'builtin', fileTypes: [] },
  ])
  // 元素非字符串 → 滤掉
  assert.deepEqual(normalizeKbParserRules([{ engine: 'e', fileTypes: ['pdf', 42, null] }]), [
    { engine: 'e', fileTypes: ['pdf'] },
  ])
  // engine 前后空格 → trim
  assert.deepEqual(normalizeKbParserRules([{ engine: '  builtin  ', fileTypes: [] }]), [
    { engine: 'builtin', fileTypes: [] },
  ])
})

test('互转：KB 配置（camel）→ 上传覆盖（camel）→ 回 KB 配置，round-trip 不丢字段', () => {
  const kb = [
    { engine: 'builtin', fileTypes: ['pdf'], xlsxFirstRowAsHeader: false },
    { engine: 'markitdown', fileTypes: ['pptx', 'ppt'] },
  ]
  const override = toOverrideParserRules(kb)
  assert.deepEqual(override, [
    { engine: 'builtin', fileTypes: ['pdf'], xlsxFirstRowAsHeader: false },
    { engine: 'markitdown', fileTypes: ['pptx', 'ppt'] },
  ])
  // 未设置 xlsxFirstRowAsHeader 时不得凭空造键
  assert.equal('xlsxFirstRowAsHeader' in override[1], false)

  assert.deepEqual(fromOverrideParserRules(override), kb)
  // 覆盖入参不被原地修改
  assert.deepEqual(kb[0].fileTypes, ['pdf'])
  // 空值安全
  assert.deepEqual(toOverrideParserRules(null), [])
  assert.deepEqual(fromOverrideParserRules(undefined), [])
})
