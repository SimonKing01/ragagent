import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/**
 * 新对话态布局守卫：头像 + 名称下沉到欢迎屏、右上角主题色光晕、
 * 以及「launcherIcon → agentAvatar，都没有就不显示」的口径。
 *
 * 这些都是模板/样式级约束，用静态断言守住，避免后续重构把结构改回去。
 */
const page = readFileSync(new URL('./EmbedPage.vue', import.meta.url), 'utf8')
const core = readFileSync(new URL('./EmbedChatCore.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('./EmbedChatView.vue', import.meta.url), 'utf8')

test('新对话态头部退成悬浮图标，会话态恢复完整头部', () => {
  assert.match(page, /'embed-header--hero': !chatHasMessages/,
    '头部形态必须跟随「是否已有消息」切换')
  assert.match(page, /&--hero\s*\{[\s\S]*?\.embed-header__badge,[\s\S]*?display: none/,
    'hero 态要隐藏头像与标题，让它们下沉到欢迎屏')
})

test('右上角主题色光晕只在新对话态出现', () => {
  assert.match(page, /v-if="!chatHasMessages" class="embed-page__glow"/)
  assert.match(page, /radial-gradient\([\s\S]*?var\(--td-brand-color/, '光晕颜色跟随渠道主题色')
  assert.match(page, /animation: embed-glow-drift/, '光晕需要缓慢动效')
  assert.match(page, /prefers-reduced-motion: reduce[\s\S]*?embed-page__glow[\s\S]*?animation: none/,
    '减少动效偏好下必须停掉漂移')
})

test('无限动画要在淡出期间停掉，否则 Transition 等不到 animationend', () => {
  assert.match(page, /\.embed-glow-leave-active\s*\{[\s\S]*?animation: none/)
})

test('欢迎屏头像：launcherIcon → agentAvatar，都没有则不渲染', () => {
  assert.match(core, /v-if="heroAvatarImage \|\| heroAvatarText" class="embed-hero"/,
    '两者都为空时整个 hero 不渲染（不回落默认图标）')
  assert.match(core, /v-if="heroAvatarImage" class="embed-hero__avatar"/)
  assert.match(core, /v-else class="embed-hero__avatar embed-hero__avatar--text">\{\{ heroAvatarText \}\}/)
  assert.doesNotMatch(core, /embed-hero__avatar[\s\S]{0,120}<t-icon/,
    'hero 里不出现默认图标兜底')
})

test('头像与名称由外框按渠道配置下发', () => {
  assert.match(view, /:hero-avatar-image="heroAvatarImage"/)
  assert.match(page, /:hero-avatar-image="headerAvatarImage"/, '图片优先取 launcherIcon（headerAvatarImage 已含该优先级）')
  assert.match(page, /:hero-avatar-text="config.agentAvatar \|\| ''"/)
  assert.match(page, /:hero-title="channelDisplayTitle"/)
})

test('推荐问题单列全宽', () => {
  assert.match(core, /&__grid\s*\{[\s\S]*?flex-direction: column/)
  assert.match(core, /&__card\s*\{[\s\S]*?width: 100%/)
})
