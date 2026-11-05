<template>
  <EmbedChatCore
    :session-id="sessionId"
    :session-sig="sessionSig"
    :visitor-id="visitorId"
    :channel-id="channelId"
    :token="token"
    :agent-id="agentId"
    :kb-ids="kbIds"
    :welcome-message="welcomeMessage"
    :hero-avatar-image="heroAvatarImage"
    :hero-avatar-text="heroAvatarText"
    :hero-title="heroTitle"
    :show-suggested-questions="showSuggestedQuestions"
    :agent-starter-questions-enabled="agentStarterQuestionsEnabled"
    :agent-follow-up-questions-enabled="agentFollowUpQuestionsEnabled"
    :show-thinking="showThinking"
    :allow-web-search="allowWebSearch"
    :agent-web-search-enabled="agentWebSearchEnabled"
    :allow-file-upload="allowFileUpload"
    :agent-image-upload-enabled="agentImageUploadEnabled"
    :use-session-header-title="useSessionHeaderTitle"
    :host-context="hostContext"
    :ensure-session="ensureSession"
    @session-title="(title: string) => emit('session-title', title)"
    @messages-state="(has: boolean) => emit('messages-state', has)"
    @streaming-state="(streaming: boolean) => emit('streaming-state', streaming)"
    @history-loading="(loading: boolean) => emit('history-loading', loading)"
    @boot-ready="(ready: boolean) => emit('boot-ready', ready)"
  />
</template>

<script setup lang="ts">
import { defineAsyncComponent } from 'vue'

const EmbedChatCore = defineAsyncComponent(() => import('@/views/embed/EmbedChatCore.vue'))

defineProps<{
  sessionId: string
  sessionSig: string
  visitorId: string
  channelId: string
  token: string
  agentId: string
  kbIds: string[]
  welcomeMessage?: string
  /** 新对话态英雄区（头像图 / emoji 文本 / 名称），由外框按渠道配置传入 */
  heroAvatarImage?: string
  heroAvatarText?: string
  heroTitle?: string
  showSuggestedQuestions?: boolean
  agentStarterQuestionsEnabled?: boolean
  agentFollowUpQuestionsEnabled?: boolean
  showThinking?: boolean
  allowWebSearch?: boolean
  agentWebSearchEnabled?: boolean
  allowFileUpload?: boolean
  agentImageUploadEnabled?: boolean
  useSessionHeaderTitle?: boolean
  hostContext?: Record<string, unknown>
  /** 懒创建：发送前确保会话已存在（来自 useEmbedBridge）。 */
  ensureSession?: () => Promise<boolean>
}>()

const emit = defineEmits<{
  (e: 'session-title', title: string): void
  (e: 'messages-state', hasMessages: boolean): void
  (e: 'streaming-state', streaming: boolean): void
  /** 历史消息加载中：外框据此隐藏光晕 */
  (e: 'history-loading', loading: boolean): void
  /** 首屏是否就绪（true = 形态已定且骨架撤下）。 */
  (e: 'boot-ready', ready: boolean): void
}>()
</script>
