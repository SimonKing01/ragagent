<template>
  <div class="embed-page" :style="[pageStyle, glowStyle]">
    <div v-if="loadError" class="embed-error">
      <img class="embed-error__icon" :src="errorIcon" alt="" />
      <p class="embed-error__text">{{ loadError }}</p>
      <t-button theme="primary" @click="handleReload">
        {{ $t('embedPublish.refresh') }}
      </t-button>
    </div>
    <template v-else-if="config">
      <!-- 右上角主题色光晕：只在新对话态出现，进入会话后淡出 -->
      <Transition name="embed-glow">
        <span v-if="chatBooted && !chatHasMessages" class="embed-page__glow" aria-hidden="true" />
      </Transition>

      <header class="embed-header" :class="{ 'embed-header--hero': !chatHasMessages }">
        <span class="embed-header__badge" :style="badgeStyle">
          <img v-if="headerAvatarImage" class="embed-header__avatar-img" :src="headerAvatarImage" alt="" />
          <span v-else-if="config.agentAvatar" class="embed-header__avatar">{{ config.agentAvatar }}</span>
          <t-icon v-else :name="headerIcon" size="18px" />
        </span>
        <div class="embed-header__text">
          <h1 class="embed-header__title">{{ headerTitle }}</h1>
        </div>
        <t-button
          v-if="chatBooted"
          variant="text"
          shape="square"
          size="medium"
          class="embed-header__action"
          :title="$t('embedPublish.historyTitle')"
          :aria-label="$t('embedPublish.historyTitle')"
          @click="openHistory"
        >
          <template #icon><t-icon name="history" size="18px"/></template>
        </t-button>
        <t-button
          v-if="chatHasMessages"
          variant="text"
          shape="square"
          size="medium"
          class="embed-header__action"
          :title="$t('embedPublish.newChat')"
          :aria-label="$t('embedPublish.newChat')"
          @click="handleNewChat"
        >
          <template #icon><t-icon name="add" size="18px"/></template>
        </t-button>
      </header>

      <!-- 聊天主体是异步分块：加载期间继续用首屏骨架占位，避免头部以下整片空白 -->
      <Suspense>
        <EmbedChatView
          :session-id="sessionId"
          :session-sig="sessionSig"
          :visitor-id="visitorId"
          :channel-id="channelId"
          :token="token"
          :agent-id="config.agentId"
          :kb-ids="kbIds"
          :welcome-message="config.welcomeMessage"
          :hero-avatar-image="headerAvatarImage"
          :hero-avatar-text="config.agentAvatar || ''"
          :hero-title="channelDisplayTitle"
          :show-suggested-questions="config.showSuggestedQuestions !== false"
          :agent-starter-questions-enabled="config.agentStarterQuestionsEnabled === true"
          :agent-follow-up-questions-enabled="config.agentFollowUpQuestionsEnabled === true"
          :show-thinking="config.showThinking === true"
          :allow-web-search="config.allowWebSearch === true"
          :agent-web-search-enabled="config.agentWebSearchEnabled === true"
          :allow-file-upload="config.allowFileUpload === true"
          :agent-image-upload-enabled="config.agentImageUploadEnabled === true"
          :use-session-header-title="useSessionHeaderTitle"
          :host-context="hostContext"
          :ensure-session="ensureSession"
          :session-restoring="restoringSession"
          @session-title="sessionTitle = $event"
          @messages-state="chatHasMessages = $event"
          @boot-ready="chatBooted = true"
          @streaming-state="streaming = $event"
        />
        <template #fallback>
          <div class="embed-chat-fallback">
            <EmbedBootSkeleton
              :variant="restoringSession ? 'conversation' : 'welcome'"
              :show-header="false"
            />
          </div>
        </template>
      </Suspense>
    </template>
    <div v-else class="embed-boot-fallback">
      <EmbedBootSpinner />
    </div>

    <EmbedHistoryDrawer
      :open="historyOpen"
      :loading="historyLoading"
      :sessions="historySessions"
      :current-session-id="sessionId"
      @close="historyOpen = false"
      @select="handleSelectSession"
      @delete-request="requestDeleteSession"
    />

    <t-dialog
      class="embed-confirm-dialog"
      v-model:visible="deleteConfirmVisible"
      :header="$t('embedPublish.historyDelete')"
      :body="$t('embedPublish.historyDeleteConfirm')"
      :confirm-btn="{ content: $t('common.confirm'), theme: 'danger' }"
      :cancel-btn="$t('common.cancel')"
      :z-index="3500"
      width="min(320px, 86vw)"
      @confirm="confirmDeleteSession"
      @close="cancelDeleteSession"
    />

    <t-dialog
      class="embed-confirm-dialog"
      v-model:visible="switchConfirmVisible"
      :header="$t('embedPublish.historySwitchTitle')"
      :body="$t('embedPublish.historySwitchBody')"
      :confirm-btn="$t('embedPublish.historySwitchOk')"
      :cancel-btn="$t('common.cancel')"
      :z-index="3500"
      width="min(320px, 86vw)"
      @confirm="confirmSwitchSession"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import EmbedBootSkeleton from '@/views/embed/EmbedBootSkeleton.vue'
import EmbedBootSpinner from '@/views/embed/EmbedBootSpinner.vue'
import { glowVars } from '@/utils/embedGlow'
import EmbedChatView from '@/views/embed/EmbedChatView.vue'
import EmbedHistoryDrawer from '@/views/embed/EmbedHistoryDrawer.vue'
import errorIcon from '@/assets/img/error.svg'
import {
  deleteEmbedVisitorSession,
  listEmbedVisitorSessions,
  type EmbedVisitorSessionSummary,
} from '@/api/embed'
import { useEmbedBridge } from '@/composables/useEmbedBridge'
import { setDefaultProtectedFileAccess } from '@/utils/protectedFileAccess'
import { embedToast } from '@/utils/embedToast'

const { t } = useI18n()
const route = useRoute()
const channelId = ref(String(route.params.channelId || ''))
const sessionTitle = ref('')
const chatHasMessages = ref(false)
/** 首屏形态已定（历史/新建确定、骨架撤下）——光晕与头部图标此后才出现。 */
const chatBooted = ref(false)

const {
  token,
  config,
  sessionId,
  sessionSig,
  visitorId,
  loadError,
  awaitingToken,
  bootstrapping,
  // 模板里的 :session-restoring 要用它：存量会话校验期间隐藏问候屏、保持骨架，
  // 漏解构会报「ComponentPublicInstance 上不存在属性 restoringSession」
  restoringSession,
  hostContext,
  ensureSession,
  selectSession,
  startNewSession,
} = useEmbedBridge(channelId)

// An embed visitor has no Bearer/tenant credentials, so every protected file in
// this document must go through the channel-scoped proxy. Registering the plane
// once here keeps deeply nested renderers (agent stream, references, wiki
// drawer) from having to thread the channel/token down as props.
watchEffect(() => {
  setDefaultProtectedFileAccess(
    channelId.value && token.value
      ? { mode: 'embed', channelId: channelId.value, token: token.value }
      : null,
  )
})

onUnmounted(() => setDefaultProtectedFileAccess(null))

// 错误态下的一键重试：整页重载，重新走宿主握手与配置拉取（widget iframe 内
// 宿主会再次 provideToken）。
const handleReload = () => {
  window.location.reload()
}

const handleNewChat = () => {
  // 懒创建：无消息时按钮本就禁用；有消息才回到"未创建"空白态（首条消息才落库）。
  if (!chatHasMessages.value) return
  sessionTitle.value = ''
  startNewSession()
}

// ── 历史会话抽屉 ──────────────────────────────────────────────

const streaming = ref(false)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historySessions = ref<EmbedVisitorSessionSummary[]>([])
const switchConfirmVisible = ref(false)
let pendingSession: EmbedVisitorSessionSummary | null = null

const loadHistorySessions = async () => {
  if (!channelId.value || !token.value || !visitorId.value) return
  historyLoading.value = true
  try {
    historySessions.value = await listEmbedVisitorSessions(
      channelId.value,
      token.value,
      visitorId.value,
    )
  } catch (e) {
    console.error('Failed to load embed session history:', e)
    historySessions.value = []
    embedToast(t('embedPublish.historyLoadFailed'))
  } finally {
    historyLoading.value = false
  }
}

const openHistory = () => {
  historyOpen.value = true
  void loadHistorySessions()
}

const applySelectSession = (item: EmbedVisitorSessionSummary) => {
  historyOpen.value = false
  switchConfirmVisible.value = false
  pendingSession = null
  selectSession(item.id, item.sig)
}

const handleSelectSession = (item: EmbedVisitorSessionSummary) => {
  if (item.id === sessionId.value) {
    // 点当前会话：只收起抽屉，不重载。
    historyOpen.value = false
    return
  }
  if (streaming.value) {
    // 回答中切换会中断本次回答：先确认。
    pendingSession = item
    switchConfirmVisible.value = true
    return
  }
  applySelectSession(item)
}

const confirmSwitchSession = () => {
  if (pendingSession) applySelectSession(pendingSession)
}

const deleteConfirmVisible = ref(false)
let pendingDeleteSession: EmbedVisitorSessionSummary | null = null

const requestDeleteSession = (item: EmbedVisitorSessionSummary) => {
  pendingDeleteSession = item
  deleteConfirmVisible.value = true
}

const cancelDeleteSession = () => {
  deleteConfirmVisible.value = false
  pendingDeleteSession = null
}

const confirmDeleteSession = () => {
  const item = pendingDeleteSession
  cancelDeleteSession()
  if (item) void handleDeleteSession(item)
}

const handleDeleteSession = async (item: EmbedVisitorSessionSummary) => {
  if (!channelId.value || !token.value || !visitorId.value) return
  try {
    await deleteEmbedVisitorSession(channelId.value, item.id, token.value, visitorId.value)
  } catch (e) {
    console.error('Failed to delete embed session:', e)
    embedToast(t('embedPublish.historyDeleteFailed'))
    return
  }
  historySessions.value = historySessions.value.filter((session) => session.id !== item.id)
  embedToast(t('embedPublish.historyDeleted'))
  if (item.id === sessionId.value) {
    // 删掉的是当前会话：回到新会话空白态，避免引用已删除的会话
    historyOpen.value = false
    startNewSession()
  }
}

const kbIds = computed(() => config.value?.knowledgeBaseIds ?? [])

const pageStyle = computed(() => {
  const color = config.value?.primaryColor
  if (!color) return {}
  return {
    '--embed-primary': color,
    '--td-brand-color': color,
    '--td-brand-color-hover': color,
    '--td-brand-color-active': color,
  } as Record<string, string>
})

/**
 * 右上角光晕的浓度变量：品牌色越深，浓度越低（黑色给到 7% 否则是灰雾），
 * 深色同时打开细弧线补偿。
 */
const glowStyle = computed(() => glowVars(config.value?.primaryColor))

const badgeStyle = computed(() => {
  const color = config.value?.primaryColor
  if (!color) return {}
  return {
    background: `color-mix(in srgb, ${color} 12%, transparent)`,
    color,
  } as Record<string, string>
})

const channelDisplayTitle = computed(() => {
  const cfg = config.value
  if (!cfg) return ''
  return (
    cfg.displayTitle?.trim()
    || cfg.pageTitle?.trim()
    || cfg.name?.trim()
    || cfg.agentName?.trim()
    || t('embedPublish.defaultChatTitle')
  )
})

const useSessionHeaderTitle = computed(
  () => config.value?.headerTitleMode === 'session',
)

const headerTitle = computed(() => {
  if (useSessionHeaderTitle.value && sessionTitle.value.trim()) {
    return sessionTitle.value.trim()
  }
  return channelDisplayTitle.value
})

const headerIcon = computed(() => {
  const agentId = config.value?.agentId || ''
  return agentId && agentId !== 'builtin-quick-answer' ? 'control-platform' : 'chat'
})

/**
 * 头部徽标优先显示"渠道里配置的图片"（launcher_icon）；若 agent_avatar 本身就是图片地址
 * （data URL / http(s) / 站内路径）也用它。都没有时退回 emoji 文本或通用图标。
 */
const headerAvatarImage = computed(() => {
  const cfg = config.value
  const icon = typeof cfg?.launcherIcon === 'string' ? cfg.launcherIcon.trim() : ''
  if (icon) return icon
  const avatar = typeof cfg?.agentAvatar === 'string' ? cfg.agentAvatar.trim() : ''
  return /^(data:image\/|https?:\/\/|\/)/i.test(avatar) ? avatar : ''
})

watch(headerTitle, (title) => {
  if (title) document.title = title
}, { immediate: true })
</script>

<style scoped lang="less">
.embed-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--td-bg-color-container, #fff);
  overflow: hidden;
  position: relative;

  /* 渠道主题色由 pageStyle 内联写入 --td-brand-color*；未配置时继承全局主题色。 */

  :deep(.embed-input-box:focus-within) {
    border-color: var(--embed-primary, var(--td-brand-color));
  }

  :deep(.embed-send-btn:not(.disabled)) {
    background: var(--embed-primary, var(--td-brand-color));
  }

  /* 右上角主题色光晕（装饰层，不吃事件）：斜向椭圆，浓度由品牌色亮度决定 */
  &__glow {
    position: absolute;
    top: -130px;
    right: -120px;
    width: 440px;
    height: 320px;
    pointer-events: none;
    z-index: 0;
    border-radius: 50%;
    transform: rotate(-18deg);
    // 不用 filter: blur —— 它会把下面那圈 1px 细弧一起糊掉；
    // 靠更长的渐变尾巴（85%）自己软下来
    background: radial-gradient(closest-side,
      color-mix(in srgb, var(--td-brand-color, #0052d9) var(--embed-glow-alpha, 18%), transparent),
      transparent 85%);
    animation: embed-glow-drift 14s ease-in-out infinite alternate;
  }
}

/* 光晕缓慢漂移：幅度很小，只做「活着」的感觉 */
@keyframes embed-glow-drift {
  from {
    transform: rotate(-18deg) translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: rotate(-18deg) translate3d(-12px, 10px, 0) scale(1.1);
  }
}

.embed-glow-enter-active,
.embed-glow-leave-active {
  transition: opacity 0.24s ease;
  /* 常驻的漂移动画是 infinite，Vue 会据此等 animationend 而永远不结束，
     淡出期间必须停掉它，让过渡按 transitionend 收尾 */
  animation: none;
}

.embed-glow-enter-from,
.embed-glow-leave-to {
  opacity: 0;
}

/* 系统要求减少动效时停掉漂移（保留静态光晕） */
@media (prefers-reduced-motion: reduce) {
  .embed-page__glow {
    animation: none;
  }

  .embed-intro-leave-active,
  .embed-intro-enter-active,
  .embed-header {
    transition: none;
  }
}

/* 内容层压在光晕之上 */
.embed-header,
.embed-chat,
.embed-chat-fallback {
  position: relative;
  z-index: 1;
}

/* 未知阶段：转圈占满头部以下区域并居中 */
.embed-boot-fallback {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.embed-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: .5px solid var(--td-component-stroke);
  background: var(--td-bg-color-container);
  flex-shrink: 0;
  // 新对话态：头部退成右上角悬浮图标，头像与名称下沉到欢迎屏
  transition: background-color 0.24s ease, border-color 0.24s ease;

  &--hero {
    justify-content: flex-end;
    background: transparent;
    border-bottom-color: transparent;

    .embed-header__badge,
    .embed-header__text {
      display: none;
    }
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 50%;
    flex-shrink: 0;
    background: color-mix(in srgb, var(--td-brand-color) 10%, transparent);
    color: var(--td-brand-color);
  }

  &__avatar {
    font-size: 20px;
    line-height: 1;
  }

  &__avatar-img {
    width: 100%;
    height: 100%;
    border-radius: 50%;
    object-fit: cover;
    display: block;
  }

  &__text {
    min-width: 0;
    flex: 1;
  }

  &__action {
    flex-shrink: 0;
    color: var(--td-text-color-secondary);

    &:hover {
      color: var(--td-brand-color);
    }
  }

  &__title {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.35;
    color: var(--td-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__subtitle {
    margin: 2px 0 0;
    font-size: 12px;
    line-height: 1.4;
    color: var(--td-text-color-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.embed-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px;
  text-align: center;

  &__icon {
    width: 160px;
    max-width: 60%;
    height: auto;
  }

  &__text {
    margin: 0;
    font-size: 14px;
    line-height: 1.6;
    color: var(--td-text-color-secondary);
    word-break: break-word;
  }
}

</style>
