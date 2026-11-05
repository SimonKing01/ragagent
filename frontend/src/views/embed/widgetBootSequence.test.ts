import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/**
 * 首屏加载顺序守卫：
 *   P0 未知（配置/分块未就绪）→ 居中转圈，不猜骨架、不显示光晕与头部图标；
 *   P1 已知形态 → 历史走气泡骨架、新建走「头像 + 名称」迎宾骨架；
 *   P2 就绪 → 真实欢迎屏 + 光晕 + 头部图标。
 */
const page = readFileSync(new URL('./EmbedPage.vue', import.meta.url), 'utf8')
const core = readFileSync(new URL('./EmbedChatCore.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('./EmbedChatView.vue', import.meta.url), 'utf8')
const skeleton = readFileSync(new URL('./EmbedBootSkeleton.vue', import.meta.url), 'utf8')
const spinner = readFileSync(new URL('./EmbedBootSpinner.vue', import.meta.url), 'utf8')
const bootCss = readFileSync(new URL('../../../public/embed-boot.css', import.meta.url), 'utf8')

test('P0 未知阶段用转圈，不用骨架', () => {
  assert.match(page, /<div v-else class="embed-boot-fallback">\s*<EmbedBootSpinner \/>/,
    '配置未就绪时必须是居中转圈')
  assert.doesNotMatch(spinner, /\{\{/, '转圈不带文案')
  assert.match(page, /\.embed-boot-fallback \{[\s\S]*?align-items: center[\s\S]*?justify-content: center/,
    '转圈要垂直水平居中')
})

test('P1 骨架形态由外部决定，不再读 localStorage 猜', () => {
  assert.match(page, /:variant="restoringSession \? 'conversation' : 'welcome'"/)
  assert.match(skeleton, /variant\?: 'conversation' \| 'welcome'/)
  assert.doesNotMatch(skeleton, /localStorage/, '骨架组件里不应再猜会话形态')
})

test('迎宾骨架与真实首屏同构（头像 + 名称）', () => {
  assert.match(skeleton, /eb-skel eb-intro-avatar/)
  assert.match(skeleton, /eb-skel eb-intro-name/)
  assert.match(bootCss, /\.eb-intro-avatar \{[\s\S]*?width: 64px;[\s\S]*?height: 64px;/,
    '骨架头像尺寸要等于 .embed-hero 的 64px')
})

test('骨架/未知阶段不显示光晕与头部图标', () => {
  assert.match(page, /v-if="chatBooted && !chatHasMessages" class="embed-page__glow"/,
    '光晕必须等首屏就绪')
  assert.match(page, /v-if="chatBooted"[\s\S]{0,160}embed-header__action/,
    '头部图标必须等首屏就绪')
})

test('就绪信号从聊天核心一路上抛到外框', () => {
  assert.match(core, /if \(!pending\) emit\('boot-ready'\)/)
  assert.match(view, /@boot-ready="emit\('boot-ready'\)"/)
  assert.match(page, /@boot-ready="chatBooted = true"/)
})
