<template>
  <div class="embed-chat">
    <div ref="scrollContainer" class="embed-chat__scroll" @scroll="handleScroll">
      <div class="embed-chat__messages">
        <!-- 历史会话加载中：与首屏/推荐问题骨架同一套块，交接时不跳 -->
        <div v-if="historyLoading && messagesList.length === 0" class="msg-skeleton-list">
          <span class="eb-skel eb-bubble" />
          <span class="eb-skel eb-bubble eb-bubble--user" />
          <span class="eb-skel eb-row" />
        </div>

        <div
          v-if="showGreeting || showSuggestedBlock"
          class="embed-suggested"
        >
          <!-- 问候语只有一行：渠道配了欢迎语就显示欢迎语，否则显示默认问候语 -->
          <div v-if="showGreeting" class="embed-suggested__head">
            <p class="embed-suggested__title">
              {{ hasWelcomeText ? welcomeText : t('chat.suggestedQuestions') }}
            </p>
            <button
              v-if="starterQuestionsEnabled"
              type="button"
              class="embed-refresh-btn"
              :title="t('common.refresh')"
              :aria-label="t('common.refresh')"
              :disabled="suggestedLoading"
              @click="refreshSuggestedQuestions"
            >
              <t-icon name="refresh" size="14px" :class="{ 'is-spinning': suggestedLoading }" />
            </button>
          </div>
          <!-- 骨架与首屏骨架同形（欢迎气泡 + 用户气泡 + 卡片），交接时不跳 -->
          <div v-if="suggestedLoading && suggestedQuestions.length === 0"
            class="embed-suggested__grid embed-suggested__grid--skeleton">
            <span class="eb-skel eb-bubble" />
            <span class="eb-skel eb-bubble eb-bubble--user" />
            <span class="eb-skel eb-row" />
          </div>
          <div v-else-if="suggestedQuestions.length > 0" class="embed-suggested__grid">
            <button
              v-for="item in suggestedQuestions"
              :key="item.question"
              type="button"
              class="embed-suggested__card"
              @click="handleSuggestedClick(item.question)"
            >
              <span class="embed-suggested__text">{{ item.question }}</span>
            </button>
          </div>
        </div>

        <div
          v-for="(session, index) in messagesList"
          :key="(session.id as string) || `${session.role}-${session.createdAt}-${index}`"
          class="msg-item-wrapper"
        >
          <MessageTimestamp
            v-if="shouldShowConversationTimestamp(messagesList, index)"
            :value="session.createdAt"
          />
          <div v-if="session.role === 'user'" class="message-row">
            <EmbedUserMessage
              :content="String(session.content || '')"
              :mentionedItems="asUnknownArray(session.mentionedItems)"
              :images="asEmbedImages(session.images)"
              :attachments="asEmbedAttachments(session.attachments)"
              :embeddedMode="true"
              :embed-channel-id="channelId"
              :embed-token="token"
            />
          </div>
          <div v-if="session.role === 'assistant' && shouldRenderAssistantMessage(session)" class="message-row">
            <EmbedBotMessage
              :content="String(session.content || '')"
              :session="session"
              :session-id="sessionId"
              :user-query="getUserQuery(index)"
              :show-thinking="showThinking"
              :embedded-mode="true"
              :embed-channel-id="channelId"
              :embed-token="token"
              :embed-session-sig="sessionSig"
              :embed-visitor-id="visitorId"
            />
            <FollowUpSuggestions v-if="!session.suggestionsDismissed"
              :suggestion-set="session.suggestionSet as any"
              :loading="Boolean(session.suggestionLoading)"
              :allow-regenerate="Boolean((session.suggestionSet as any)?.allowRegenerate)"
              @select="(item) => handleFollowUpSelect(session, item)"
              @regenerate="loadFollowUpSuggestions(session, true, true)"
              @impression="(set) => recordFollowUpEvent(set, 'impression')"
              @dismiss="(set) => dismissFollowUps(session, set)" />
          </div>
        </div>

        <div v-if="showGlobalTypingIndicator" class="embed-chat__typing" role="status"
          :aria-label="t('chat.thinkingAlt')">
          <span class="embed-chat__typing-spinner" aria-hidden="true"></span>
        </div>
      </div>
    </div>

    <transition name="scroll-btn-fade">
      <div v-show="userHasScrolledUp" class="scroll-to-bottom-btn" @click="onClickScrollToBottom" aria-label="scroll to bottom">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>
    </transition>

    <div class="embed-chat__input">
      <EmbedInputField
        :isReplying="isReplying"
        :show-web-search-toggle="showWebSearchToggle"
        v-model:web-search-enabled="webSearchEnabled"
        :show-file-upload-toggle="showFileUploadToggle"
        @send-msg="onSendMsg"
        @stop-generation="handleStopGeneration"
      />
    </div>
    <ChatReferencesDrawer embedded-mode />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ensureEmbedMessageSuggestions,
  getEmbedMessageSuggestions,
  getEmbedSuggestedQuestions,
  onEmbedHostOpenWithQuery,
  recordEmbedMessageSuggestionEvent,
  type SuggestedQuestion,
} from '@/api/embed'
import EmbedInputField from '@/components/EmbedInputField.vue'
import EmbedBotMessage from '@/views/embed/EmbedBotMessage.vue'
import EmbedUserMessage from '@/views/embed/EmbedUserMessage.vue'
import ChatReferencesDrawer from '@/components/ChatReferencesDrawer.vue'
import { provideChatReferencesDrawer } from '@/composables/useChatReferencesDrawer'
import { useEmbedChatSession } from '@/composables/useEmbedChatSession'
import FollowUpSuggestions from '@/components/chat/FollowUpSuggestions.vue'
import MessageTimestamp from '@/components/chat/MessageTimestamp.vue'
import { shouldShowConversationTimestamp } from '@/utils/messageTimestamp'
import type { MessageSuggestionItem, MessageSuggestionSet } from '@/api/message-suggestion'

provideChatReferencesDrawer()

type EmbedImage = { url?: string; data?: string }
type EmbedAttachment = { fileName: string; fileSize?: number }

const props = defineProps<{
  sessionId: string
  sessionSig: string
  visitorId: string
  channelId: string
  token: string
  agentId: string
  kbIds: string[]
  welcomeMessage?: string
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
}>()

const { t } = useI18n()
const sessionIdRef = toRef(props, 'sessionId')
const sessionSigRef = toRef(props, 'sessionSig')
const visitorIdRef = toRef(props, 'visitorId')
const suggestedQuestions = ref<SuggestedQuestion[]>([])
const suggestedLoading = ref(false)
const hostContextRef = ref<Record<string, unknown>>(props.hostContext || {})

/** 渠道「推荐问题」是总闸；开场/追问各自再看智能体开关（与联网/上传同款 AND）。 */
const starterQuestionsEnabled = computed(
  () => props.showSuggestedQuestions === true && props.agentStarterQuestionsEnabled === true,
)
const followUpQuestionsEnabled = computed(
  () => props.showSuggestedQuestions === true && props.agentFollowUpQuestionsEnabled === true,
)

const loadFollowUpSuggestions = async (
  message: Record<string, unknown>,
  ensure = false,
  regenerate = false,
) => {
  const messageId = String(message.id || message.assistantMessageId || '')
  const targetSessionId = props.sessionId
  if (!followUpQuestionsEnabled.value || !messageId || !targetSessionId || message.suggestionsDismissed) return
  message.suggestionLoading = true
  try {
    let response = ensure
      ? await ensureEmbedMessageSuggestions(
        props.channelId, props.token, targetSessionId, messageId, props.sessionSig, props.visitorId, regenerate,
      )
      : await getEmbedMessageSuggestions(
        props.channelId, props.token, targetSessionId, messageId, props.sessionSig, props.visitorId,
      )
    let set = response
    for (let attempt = 0; set?.status === 'generating' && attempt < 120; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      if (props.sessionId !== targetSessionId || message.suggestionsDismissed) return
      response = await getEmbedMessageSuggestions(
        props.channelId, props.token, targetSessionId, messageId, props.sessionSig, props.visitorId,
      )
      set = response
    }
    message.suggestionSet = set?.status === 'ready' ? set : null
  } catch {
    message.suggestionSet = null
  } finally {
    message.suggestionLoading = false
  }
}

const loadPersistedFollowUps = (messages: Record<string, unknown>[]) => {
  for (const message of messages) {
    if (message.role === 'assistant' && message.completed && message.suggestionSet === undefined) {
      void loadFollowUpSuggestions(message, false)
    }
  }
}

function asUnknownArray(value: unknown): unknown[] | undefined {
  return Array.isArray(value) ? value : undefined
}

function asEmbedImages(value: unknown): EmbedImage[] | undefined {
  return Array.isArray(value) ? value as EmbedImage[] : undefined
}

function asEmbedAttachments(value: unknown): EmbedAttachment[] | undefined {
  return Array.isArray(value) ? value as EmbedAttachment[] : undefined
}

const embedWebSearchStorageKey = () => `ragagent-embed-web-search:${props.channelId}`

const readStoredWebSearchEnabled = () => {
  if (typeof localStorage === 'undefined') return false
  return localStorage.getItem(embedWebSearchStorageKey()) === '1'
}

const webSearchEnabled = ref(readStoredWebSearchEnabled())

const showWebSearchToggle = computed(
  () => props.allowWebSearch === true && props.agentWebSearchEnabled === true,
)
const showFileUploadToggle = computed(
  () => props.allowFileUpload === true && props.agentImageUploadEnabled === true,
)

watch(webSearchEnabled, (enabled) => {
  if (!showWebSearchToggle.value) return
  if (typeof localStorage === 'undefined') return
  localStorage.setItem(embedWebSearchStorageKey(), enabled ? '1' : '0')
})

watch(showWebSearchToggle, (visible) => {
  if (!visible) {
    webSearchEnabled.value = false
  }
})

watch(() => props.hostContext, (ctx) => {
  hostContextRef.value = ctx || {}
}, { deep: true })

const {
  messagesList,
  loading,
  isReplying,
  historyLoading,
  scrollContainer,
  userHasScrolledUp,
  shouldRenderAssistantMessage,
  shouldShowGlobalTypingIndicator,
  getUserQuery,
  handleScroll,
  scrollToBottom,
  onClickScrollToBottom,
  sendMsg,
  handleStopGeneration,
  setSuggestionAttribution,
} = useEmbedChatSession({
  sessionId: sessionIdRef,
  sessionSig: sessionSigRef,
  visitorId: visitorIdRef,
  channelId: props.channelId,
  token: props.token,
  agentId: props.agentId,
  kbIds: props.kbIds,
  allowWebSearch: props.allowWebSearch,
  allowFileUpload: props.allowFileUpload,
  hostContext: hostContextRef,
  onMessagesChange: (has) => emit('messages-state', has),
  onSessionTitle: (title) => {
    if (props.useSessionHeaderTitle) {
      emit('session-title', title)
    }
  },
  onTurnComplete: (message) => { void loadFollowUpSuggestions(message, true) },
  onMessagesLoaded: loadPersistedFollowUps,
  ensureSession: props.ensureSession,
  onStreamingChange: (streaming) => emit('streaming-state', streaming),
})

const welcomeText = computed(() => props.welcomeMessage?.trim() || '')
const hasWelcomeText = computed(() => welcomeText.value.length > 0)

const hasUserMessage = computed(() =>
  messagesList.some((m) => m.role === 'user'))

const showGlobalTypingIndicator = computed(() =>
  shouldShowGlobalTypingIndicator(messagesList, loading.value),
)

/**
 * 问候语行：配了欢迎语就用它，否则用默认问候语；访客发言后隐藏。
 * 开场问题关掉时也要显示欢迎语，所以不挂在 showSuggestedBlock 上。
 */
const showGreeting = computed(() =>
  !hasUserMessage.value
  && !loading.value
  && !historyLoading.value
  && (hasWelcomeText.value || starterQuestionsEnabled.value))

const showSuggestedBlock = computed(() =>
  starterQuestionsEnabled.value
  && !hasUserMessage.value
  && !loading.value
  && !historyLoading.value
  && (suggestedLoading.value || suggestedQuestions.value.length > 0))

const fetchSuggestedQuestions = async () => {
  if (!starterQuestionsEnabled.value || !props.channelId || !props.token) {
    suggestedQuestions.value = []
    return
  }
  suggestedLoading.value = true
  try {
    const res = await getEmbedSuggestedQuestions(props.channelId, props.token)
    suggestedQuestions.value = res?.questions || []
  } catch {
    suggestedQuestions.value = []
  } finally {
    suggestedLoading.value = false
  }
}

/** 问候语旁的刷新按钮：重新拉一批推荐问题（失败时保持空态，不打断对话）。 */
const refreshSuggestedQuestions = () => {
  if (suggestedLoading.value) return
  void fetchSuggestedQuestions()
}

const onSendMsg = (query: string, imageFiles: File[] = [], attachmentFiles: File[] = []) => {
  void sendMsg(query, {
    webSearchEnabled: webSearchEnabled.value,
    imageFiles,
    attachmentFiles,
  })
}

const handleSuggestedClick = (question: string) => {
  const text = question.trim()
  if (!text || isReplying.value) return
  void sendMsg(text, { webSearchEnabled: webSearchEnabled.value })
}

const recordFollowUpEvent = (
  set: MessageSuggestionSet,
  eventType: 'impression' | 'click' | 'dismiss',
  questionId = '',
) => {
  if (!set?.id) return
  void recordEmbedMessageSuggestionEvent(
    props.channelId,
    props.token,
    props.sessionId,
    props.sessionSig,
    props.visitorId,
    set.id,
    eventType,
    questionId,
  ).catch(() => undefined)
}

const handleFollowUpSelect = (message: Record<string, unknown>, item: MessageSuggestionItem) => {
  const set = message.suggestionSet as MessageSuggestionSet | undefined
  if (set) {
    recordFollowUpEvent(set, 'click', item.id)
    setSuggestionAttribution(set.id, item.id)
  }
  if (!isReplying.value) void sendMsg(item.text, { webSearchEnabled: webSearchEnabled.value })
}

const dismissFollowUps = (message: Record<string, unknown>, set: MessageSuggestionSet) => {
  message.suggestionsDismissed = true
  recordFollowUpEvent(set, 'dismiss')
}

let removeOpenQueryListener: (() => void) | null = null

onMounted(() => {
  fetchSuggestedQuestions()
  removeOpenQueryListener = onEmbedHostOpenWithQuery((query) => {
    if (isReplying.value) return
    void sendMsg(query, { webSearchEnabled: webSearchEnabled.value })
  })
})

onUnmounted(() => {
  removeOpenQueryListener?.()
})

watch(
  () => [starterQuestionsEnabled.value, props.channelId, props.token] as const,
  () => { fetchSuggestedQuestions() },
)
</script>

<style scoped lang="less">
.embed-chat {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  position: relative;
}

.embed-chat__scroll {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow-y: auto;
}

.embed-chat__messages {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
  padding: 12px 16px 0;
  box-sizing: border-box;
}

.message-row {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.embed-suggested {
  width: 100%;

  &__title {
    margin: 20px 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--td-text-color-secondary);
    // 欢迎语由渠道配置，可能自带换行
    white-space: pre-wrap;
  }

  &__grid {
    display: flex;
    flex-direction: column;
    gap: 8px;

    // 骨架态用首屏骨架的间距（16px），与 .embed-boot__body 一致
    &--skeleton {
      gap: 16px;
    }
  }

  &__card {
    display: block;
    width: 100%;
    // 真实卡片是 button（默认 border-box）；显式声明，避免换成 div 时宽度算法变掉
    box-sizing: border-box;
    padding: 10px 12px;
    border: 1px solid var(--td-component-stroke);
    border-radius: 10px;
    background: var(--td-bg-color-container);
    text-align: left;
    cursor: pointer;
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
    transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;

    &:hover {
      border-color: color-mix(in srgb, var(--td-text-color-primary) 10%, var(--td-component-stroke));
      background: color-mix(in srgb, var(--td-text-color-primary) 4%, var(--td-bg-color-container));
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
    }

  }

  &__text {
    font-size: 13px;
    line-height: 1.45;
    color: var(--td-text-color-primary);
  }
}

// 推荐问题刷新按钮（问候语行右侧）
.embed-refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--td-text-color-placeholder);
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;

  &:hover:not(:disabled) {
    color: var(--td-brand-color, #0052d9);
    background: var(--td-bg-color-container-hover, rgba(0, 0, 0, 0.04));
  }

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }

  .is-spinning {
    animation: embed-refresh-spin 0.9s linear infinite;
  }
}

.embed-suggested__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

@keyframes embed-refresh-spin {
  to {
    transform: rotate(360deg);
  }
}

.embed-chat__typing {
  min-height: 28px;
  display: flex;
  align-items: center;
  padding-left: 4px;
}

.embed-chat__typing-spinner {
  width: 12px;
  height: 12px;
  box-sizing: border-box;
  border: 1.5px solid var(--td-component-stroke);
  border-top-color: var(--td-text-color-secondary);
  border-radius: 50%;
  animation: embedChatTypingSpin 0.8s linear infinite;
}

@keyframes embedChatTypingSpin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .embed-chat__typing-spinner {
    animation: none;
  }
}

.embed-chat__input {
  flex-shrink: 0;
  padding: 8px 16px 16px;
  box-sizing: border-box;
}

/* 块本身的尺寸/动画来自共享的 .eb-skel/.eb-bubble/.eb-row，这里只管排布 */
.msg-skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.scroll-to-bottom-btn {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: 100px;
  z-index: 10;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-stroke);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--td-text-color-secondary);
}

.scroll-btn-fade-enter-active,
.scroll-btn-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.scroll-btn-fade-enter-from,
.scroll-btn-fade-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px);
}
</style>
