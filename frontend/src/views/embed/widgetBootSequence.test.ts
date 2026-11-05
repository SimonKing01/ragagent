import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/**
 * 首屏加载顺序守卫：
 *   P0 未知（embed.html 静态段 / 等配置 / 等分块）→ 只有居中转圈，不画骨架；
 *   P1 形态已定 → 历史走气泡骨架、新建走迎宾骨架（推荐问题未回时）；
 *   P2 就绪 → 真实欢迎屏 + 光晕 + 头部图标。
 *
 * 关键约束：形态判定之前不能出现任何骨架，否则会出现
 * 「骨架 A → 转圈 → 骨架 B」的闪跳。
 */
const page = readFileSync(new URL('./EmbedPage.vue', import.meta.url), 'utf8')
const core = readFileSync(new URL('./EmbedChatCore.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('./EmbedChatView.vue', import.meta.url), 'utf8')
const spinner = readFileSync(new URL('./EmbedBootSpinner.vue', import.meta.url), 'utf8')
const html = readFileSync(new URL('../../../embed.html', import.meta.url), 'utf8')
const bootCss = readFileSync(new URL('../../../public/embed-boot.css', import.meta.url), 'utf8')

test('embed.html 静态首帧就是转圈，没有骨架', () => {
  assert.match(html, /<div class="eb-boot-spinner" aria-hidden="true">/)
  assert.doesNotMatch(html, /eb-skel/, '静态段不得再画骨架（会先闪一下骨架）')
  assert.doesNotMatch(html, /data-boot-variant/, '静态段不得按 localStorage 猜形态')
})

test('Vue 的未知阶段也只有转圈（配置 / 分块都没就绪）', () => {
  assert.match(page, /<div v-else class="embed-boot-fallback">\s*<EmbedBootSpinner \/>/)
  assert.match(page, /class="embed-chat-fallback">\s*<EmbedBootSpinner \/>/,
    'Suspense fallback 也必须是转圈，不能按 restoringSession 猜骨架')
  assert.doesNotMatch(page, /EmbedBootSkeleton/, '骨架组件已并入聊天核心，页面不应再引用')
  assert.doesNotMatch(spinner, /\{\{/, '转圈不带文案')
})

test('P1 骨架按已定形态渲染：迎宾骨架含头像与名称占位', () => {
  assert.match(core, /v-if="introBooting" class="eb-intro-body"/)
  assert.match(core, /eb-skel eb-intro-avatar/)
  assert.match(core, /eb-skel eb-intro-name/)
  assert.match(bootCss, /\.eb-intro-avatar \{[\s\S]*?width: 64px;[\s\S]*?height: 64px;/,
    '骨架头像尺寸要等于 .embed-hero 的 64px')
  // 骨架与真实欢迎屏互斥，避免同屏两套
  assert.match(core, /<Transition v-else name="embed-intro" appear>/)
})

test('骨架期不显示光晕与头部图标', () => {
  assert.match(page, /v-if="chatBooted && !chatHasMessages" class="embed-page__glow"/)
  assert.match(page, /v-if="chatBooted"[\s\S]{0,160}embed-header__action/)
  assert.match(core, /bootPending = computed\(\(\) =>\s*historyLoading\.value \|\| Boolean\(props\.sessionRestoring\) \|\| introBooting\.value/,
    '骨架期（含迎宾骨架）都要算作未就绪')
})

test('就绪信号从聊天核心一路上抛到外框（布尔，骨架期可回退）', () => {
  assert.match(core, /emit\('boot-ready', !pending\)/)
  assert.match(view, /@boot-ready="\(ready: boolean\) => emit\('boot-ready', ready\)"/)
  assert.match(page, /@boot-ready="chatBooted = \$event"/)
})
