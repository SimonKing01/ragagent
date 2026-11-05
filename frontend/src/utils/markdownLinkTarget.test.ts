import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/**
 * 回复里的链接必须在**新窗口**打开：widget 跑在 iframe 里，同页跳转会把聊天界面顶掉。
 *
 * 这段链路有两个坑，都靠静态守卫钉住：
 *   1. 净化钩子给所有 <a> 设 target=_blank / rel=noopener noreferrer；
 *   2. markdownDomPurifyConfig 开了 USE_PROFILES —— 它会让 DOMPurify 忽略
 *      ALLOWED_ATTR，进而把 target 剥掉，所以必须显式 ADD_ATTR。
 */
const source = readFileSync(new URL('./markdownDomPurify.ts', import.meta.url), 'utf8')
const chatMarkdown = readFileSync(new URL('./chatMarkdownRenderer.ts', import.meta.url), 'utf8')
const embedBotMessage = readFileSync(new URL('../views/embed/EmbedBotMessage.vue', import.meta.url), 'utf8')

test('markdown 净化配置用 ADD_ATTR 保住 target（USE_PROFILES 会忽略 ALLOWED_ATTR）', () => {
  assert.match(source, /USE_PROFILES: \{ html: true, svg: true, mathMl: true \}/)
  assert.match(source, /ADD_ATTR: \['target'\]/, 'target 必须通过 ADD_ATTR 保留')
})

test('净化钩子给链接补 target=_blank 与 noopener', () => {
  assert.match(source, /element\.setAttribute\('rel', 'noopener noreferrer'\)/)
  assert.match(source, /element\.setAttribute\('target', '_blank'\)/)
  // 下载类与受保护资源卡片仍留在当前页
  assert.match(source, /protected-resource-card/)
  assert.match(source, /element\.hasAttribute\('download'\)/)
})

test('embed 回复气泡走的是这套净化（不是裸 marked 输出）', () => {
  assert.match(chatMarkdown, /export function renderChatMarkdown/)
  assert.match(embedBotMessage, /sanitizeHtml: sanitizeMarkdownHTML/)
})
