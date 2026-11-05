import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

/**
 * 首屏加载守卫（方案 B：不转圈）：
 *   首帧就按「该渠道有没有存量会话」画对应骨架 —— 有 → 对话骨架，无 → 迎宾骨架；
 *   会话被服务端判定失效时回落迎宾骨架；骨架期不显示光晕与头部图标。
 */
const page = readFileSync(new URL('./EmbedPage.vue', import.meta.url), 'utf8')
const core = readFileSync(new URL('./EmbedChatCore.vue', import.meta.url), 'utf8')
const view = readFileSync(new URL('./EmbedChatView.vue', import.meta.url), 'utf8')
const skeleton = readFileSync(new URL('./EmbedBootSkeleton.vue', import.meta.url), 'utf8')
const html = readFileSync(new URL('../../../embed.html', import.meta.url), 'utf8')
const api = readFileSync(new URL('../../api/embed/index.ts', import.meta.url), 'utf8')
const bootCss = readFileSync(new URL('../../../public/embed-boot.css', import.meta.url), 'utf8')
const widget = readFileSync(new URL('../../../public/ragagent-widget.js', import.meta.url), 'utf8')
const headerSkeleton = readFileSync(new URL('./EmbedHeaderSkeleton.vue', import.meta.url), 'utf8')

test('首帧不再转圈：静态段直接按 localStorage 画骨架', () => {
  assert.doesNotMatch(html, /eb-boot-spinner/, '静态段不得再有转圈')
  assert.match(html, /id="embed-boot-skeleton"/)
  assert.match(html, /ragagent-embed-session:/, '静态脚本要同步读该渠道的会话键')
  assert.match(html, /boot-has-session/, '有存量会话时先给 <html> 打标记，首帧即对话形态')
  assert.match(html, /eb-boot-welcome[\s\S]*eb-intro-avatar/, '无会话时画迎宾骨架（含头像占位）')
})

test('宿主遮罩只画转圈，并在静态骨架就绪时立即交接', () => {
  assert.match(html, /type: 'shell_ready'/, '嵌入页解析出静态骨架后要立刻通知宿主')
  assert.match(widget, /case 'shell_ready':[\s\S]{0,80}hideBootShield\(\)/)
  assert.match(widget, /weknora-shield-spinner/, '遮罩内容是一个转圈')
  assert.doesNotMatch(widget, /weknora-shield-skel|embed-boot__header/, '宿主不再画假头部/骨架块')
})

test('setup 瞬间的 watch 不能把会话骨架改成欢迎骨架', () => {
  assert.match(page, /watch\(restoringSession, \(restoring, previous\) =>/)
  assert.match(page, /if \(previous && !restoring && !sessionId\.value\)/)
  assert.doesNotMatch(page, /watch\(restoringSession,[\s\S]{0,120}\{ immediate: true \}/,
    'immediate 会在 restoring=false 时误判"没有会话"')
})

test('骨架头部与真实头部同构：按钮贴右、欢迎态只有一个图标', () => {
  assert.match(bootCss, /\.embed-boot__header \{[\s\S]{0,200}justify-content: flex-end/)
  // .eb-skel{display:block} 在后面，隐藏/显示规则必须提高特异性
  assert.match(bootCss, /\.embed-boot \.eb-boot-header-action--new \{[\s\S]{0,40}display: none/)
  assert.match(bootCss, /\.boot-has-session \.eb-boot-header-action--new[\s\S]{0,40}display: block/)
  // 骨架标题容器要吸收剩余空间，否则按钮从右侧跳到左边
  assert.match(readFileSync(new URL('./EmbedHeaderSkeleton.vue', import.meta.url), 'utf8'),
    /\.embed-header__text \{[\s\S]{0,60}flex: 1/)
})

test('骨架形态容器撑满宽度（否则卡片被压窄）', () => {
  assert.match(bootCss, /\.eb-boot-welcome \{[\s\S]{0,120}width: 100%/)
  assert.match(bootCss, /\.eb-boot-conversation \{[\s\S]{0,140}width: 100%/)
})

test('Vue 侧形态来自同步判断，而不是等服务端校验', () => {
  assert.match(api, /export function hasStoredEmbedSession/)
  assert.match(page, /const bootVariant = ref<'conversation' \| 'welcome'>\(\s*hasStoredEmbedSession\(channelId\.value\)/)
  assert.match(page, /<EmbedBootSkeleton[\s\S]{0,80}:variant="bootVariant"/, '等配置阶段也画骨架，不再转圈')
  assert.match(page, /class="embed-boot-overlay"[\s\S]{0,80}:variant="bootVariant"/,
    '骨架做成覆盖层，就绪后淡出，避免卸载/挂载之间白屏')
  assert.doesNotMatch(page, /EmbedBootSpinner/)
})

test('存量会话失效时回落迎宾骨架', () => {
  assert.match(page, /watch\(restoringSession,[\s\S]{0,160}bootVariant\.value = 'welcome'/)
})

test('宿主遮罩转圈用渠道主色，切会话骨架期不显示光晕', () => {
  assert.match(widget, /function buildBootShield\(color\)/)
  assert.match(widget, /style="border-top-color:'[\s\S]{0,80}color \|\| '#0052d9'/)
  assert.match(widget, /buildBootShield\(primaryColor\)/)
  assert.doesNotMatch(widget, /border-top-color:#0052d9/, '颜色不能再写死在样式表里')

  assert.match(page, /v-if="chatBooted && !chatHasMessages && !chatLoadingHistory" class="embed-page__glow"/)
  assert.match(core, /emit\('history-loading', loading\)/)
  assert.match(view, /@history-loading="\(loading: boolean\) => emit\('history-loading', loading\)"/)
  assert.match(page, /@history-loading="chatLoadingHistory = \$event"/)
  // 点击切换的瞬间就要收起光晕，不等 core 回报
  assert.match(page, /chatLoadingHistory\.value = true\s*\n\s*selectSession\(item\.id, item\.sig\)/)
})

test('欢迎骨架的头部不画头像/名称（判断在组件内，不依赖父级隐藏）', () => {
  assert.match(headerSkeleton, /<span v-if="variant === 'conversation'" class="embed-header__badge">/)
  assert.match(headerSkeleton, /<div v-if="variant === 'conversation'" class="embed-header__text">/)
  // 历史按钮始终保留，新建按钮只在会话形态出现
  assert.match(headerSkeleton, /<span class="eb-skel eb-skel--action" \/>/)
  assert.match(headerSkeleton, /<span v-if="variant === 'conversation'" class="eb-skel eb-skel--action" \/>/)
})

test('骨架期：光晕不出现，头部用同尺寸骨架占位（高度恒定，避免抖动）', () => {
  assert.match(page, /v-if="chatBooted && !chatHasMessages && !chatLoadingHistory" class="embed-page__glow"/)
  // 头部在未就绪时渲染骨架（头像 36 / 昵称 / 按钮 32），与真实头部同高 60px
  // 首屏与「切换会话」两种情形都用骨架：形态分别是 bootVariant / conversation
  assert.match(page, /<EmbedHeaderSkeleton[\s\S]{0,120}v-if="!chatBooted \|\| chatLoadingHistory"/)
  assert.match(page, /:variant="chatLoadingHistory \? 'conversation' : bootVariant"/)
  assert.match(page, /min-height: 60px/, 'header 高度必须固定，否则骨架与真实头部之间会跳')
  assert.match(core, /bootPending = computed\(\(\) =>\s*historyLoading\.value \|\| Boolean\(props\.sessionRestoring\) \|\| introBooting\.value/)
  assert.match(core, /emit\('boot-ready', !pending\)/)
  assert.match(view, /@boot-ready="\(ready: boolean\) => emit\('boot-ready', ready\)"/)
  assert.match(page, /@boot-ready="markBooted\(\$event\)"/, '骨架只撤一次，避免反复淡入淡出')
  assert.match(page, /const markBooted = \(ready: boolean\) => \{\s*if \(ready\) chatBooted\.value = true/)
})

test('骨架与真实首屏同构：头像 64、卡片 41', () => {
  assert.match(skeleton, /eb-skel eb-intro-avatar/)
  assert.match(bootCss, /\.eb-intro-avatar \{[\s\S]*?width: 64px;[\s\S]*?height: 64px;/)
  assert.match(bootCss, /\.eb-row \{[\s\S]*?height: 41px;/)
})
