import {
  ref,
  reactive,
  watch,
  nextTick,
  onMounted,
  onUnmounted,
  type Ref,
} from 'vue'
import { useStream } from '@/api/chat/streame'
import {
  getEmbedMessageList,
  postEmbedMessageSent,
  postEmbedMessageReceived,
  relayEmbedWebhookEvent,
  stopEmbedSession,
} from '@/api/embed'
import { embedToast } from '@/utils/embedToast'
import { buildQueryWithHostContext } from '@/utils/embedContext'
import { fileToDataURI } from '@/utils/embedFile'
import { useI18n } from 'vue-i18n'
import { useChatStreamHandler } from '@/composables/useChatStreamHandler'
import { useStickyBottomOnResize } from '@/composables/useStickyBottomOnResize'

export function useEmbedChatSession(options: {
  sessionId: Ref<string>
  sessionSig: Ref<string>
  visitorId: Ref<string>
  channelId: string
  token: string
  agentId: string
  kbIds: string[]
  allowWebSearch?: boolean
  allowFileUpload?: boolean
  hostContext?: Ref<Record<string, unknown>>
  onMessagesChange?: (has: boolean) => void
  onSessionTitle?: (title: string) => void
  onTurnComplete?: (message: Record<string, unknown>) => void
  onMessagesLoaded?: (messages: Record<string, unknown>[]) => void
  /** 懒创建：发送前确保会话已存在（访客会话按需创建）。 */
  ensureSession?: () => Promise<boolean>
  /** 流式回答状态变化：父级据此决定切换会话前是否需要确认。 */
  onStreamingChange?: (streaming: boolean) => void
}) {
  const { t } = useI18n()
  const { onChunk, error, startStream, stopStream } = useStream()

  const isAgentStreamSession = () =>
    !!(options.agentId && options.agentId !== 'builtin-quick-answer')

  const limit = ref(20)
  const messagesList = reactive<Record<string, unknown>[]>([])
  watch(
    () => messagesList.length,
    (len) => options.onMessagesChange?.(len > 0),
    { immediate: true },
  )

  const isReplying = ref(false)
  const currentAssistantMessageId = ref('')
  const isFirstEnter = ref(true)
  const loading = ref(false)
  const historyLoading = ref(true)
  const historyLoadingMore = ref(false)
  const hasMoreHistory = ref(true)
  const created_at = ref('')
  const fullContent = ref('')
  let pendingSuggestionAttribution: { suggestionSetId: string; questionId: string } | null = null
  // 发送期间让路：首条消息建会话时 sessionId 从空变成新 id，会触发下面的
  // resetAndLoad（stopStream + 清空列表 + 拉历史），与正在跑的这一轮竞争。
  let sending = false
  let sendingSessionId = ''
  const scrollContainer = ref<HTMLElement | null>(null)
  const userHasScrolledUp = ref(false)
  const SCROLL_BOTTOM_THRESHOLD = 80

  const isNearBottom = () => {
    if (!scrollContainer.value) return true
    const { scrollTop, scrollHeight, clientHeight } = scrollContainer.value
    return scrollHeight - scrollTop - clientHeight < SCROLL_BOTTOM_THRESHOLD
  }

  const getUserQuery = (index: number) => {
    if (index <= 0) return ''
    const previous = messagesList[index - 1]
    if (previous && previous.role === 'user') {
      return String(previous.content || '')
    }
    return ''
  }

  const scrollToBottom = (force = false) => {
    if (!force && userHasScrolledUp.value) return
    nextTick(() => {
      if (scrollContainer.value) {
        scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
      }
    })
  }

  const onClickScrollToBottom = () => {
    userHasScrolledUp.value = false
    scrollToBottom(true)
  }

  useStickyBottomOnResize(scrollContainer, userHasScrolledUp)

  const debounce = <T extends (...args: never[]) => void>(fn: T, delay: number) => {
    let timer: ReturnType<typeof setTimeout>
    return (...args: Parameters<T>) => {
      clearTimeout(timer)
      timer = setTimeout(() => fn(...args), delay)
    }
  }

  const notifyEmbedReceived = (content: string) => {
    if (!content?.trim()) return
    postEmbedMessageReceived(options.channelId, options.sessionId.value, content)
    relayEmbedWebhookEvent(
      options.channelId,
      options.token,
      options.sessionId.value,
      options.sessionSig.value,
      { type: 'message_received', content },
    )
  }

  const {
    shouldRenderAssistantMessage,
    shouldShowGlobalTypingIndicator,
    handleMsgList,
    processStreamChunk,
    prepareForNewOutgoingMessage,
    markInFlightAssistantStopped,
  } = useChatStreamHandler({
    messagesList,
    loading,
    isReplying,
    currentAssistantMessageId,
    fullContent,
    isAgentStreamSession,
    scrollToBottom,
    onReplyComplete: notifyEmbedReceived,
    onTurnComplete: options.onTurnComplete,
    onAfterMsgList: () => options.onMessagesLoaded?.(messagesList),
    onError: embedToast,
    isFirstEnter,
    scrollContainer,
  })

  const onChatScrollTop = () => {
    if (historyLoadingMore.value || !hasMoreHistory.value) return
    if (!scrollContainer.value) return
    const { scrollTop, scrollHeight } = scrollContainer.value
    isFirstEnter.value = false
    if (scrollTop <= 0) {
      getmsgList(
        {
          sessionId: options.sessionId.value,
          created_at: created_at.value,
          limit: limit.value,
        },
        true,
        scrollHeight,
      )
    }
  }

  const debouncedScrollTop = debounce(onChatScrollTop, 500)

  let lastScrollTop = 0
  const handleScroll = () => {
    const el = scrollContainer.value
    if (el) {
      const currentTop = el.scrollTop
      // Only an actual upward scroll detaches from the live edge. Content that
      // grows after a chunk (images, diagrams) keeps scrollTop fixed and would
      // otherwise fire a stale scroll event that falsely marks the user as
      // scrolled up, killing the auto-follow during streaming.
      if (currentTop < lastScrollTop - 1) {
        userHasScrolledUp.value = !isNearBottom()
      } else if (isNearBottom()) {
        userHasScrolledUp.value = false
      }
      lastScrollTop = currentTop
    }
    debouncedScrollTop()
  }

  const getmsgList = (
    data: { sessionId: string; created_at?: string; limit: number },
    isScrollType = false,
    scrollHeight?: number,
  ) => {
    // 懒创建：会话尚未落库（新建对话后的空白态）时没有历史可拉，别打出 /messages//load
    if (!data.sessionId) return
    if (isScrollType) {
      if (historyLoadingMore.value || !hasMoreHistory.value) return
      historyLoadingMore.value = true
    }

    getEmbedMessageList(
      options.channelId,
      options.token,
      data.sessionId,
      data.limit,
      data.created_at || undefined,
      options.sessionSig.value,
    )
      .then(async (res) => {
        // 裸数组：embed 的 load 委托 MessageController，响应体即消息列表（S2 起不再有信封）
        const batch = (Array.isArray(res) ? res : undefined) as Record<string, unknown>[] | undefined
        if (!batch?.length) {
          // No (more) server history. Crucially this also covers the initial
          // load of a brand-new session: leaving hasMoreHistory true here would
          // let a later scroll-to-top re-fetch with an empty cursor (= "latest"),
          // pulling back the just-sent messages and duplicating them.
          hasMoreHistory.value = false
          return
        }
        const nextCursor = String(batch[0].createdAt)
        if (isScrollType && created_at.value && nextCursor === created_at.value) {
          hasMoreHistory.value = false
          return
        }
        if (batch.length < limit.value) hasMoreHistory.value = false
        created_at.value = nextCursor
        await handleMsgList(batch, isScrollType, scrollHeight)
      })
      .catch((err) => {
        console.error('Failed to load messages:', err)
        if (isScrollType) hasMoreHistory.value = false
      })
      .finally(() => {
        historyLoading.value = false
        historyLoadingMore.value = false
      })
  }

  const handleStopGeneration = () => {
    stopStream()
    markInFlightAssistantStopped(currentAssistantMessageId.value)
    const messageId = currentAssistantMessageId.value
    if (messageId) {
      stopEmbedSession(
        options.channelId,
        options.token,
        options.sessionId.value,
        messageId,
        options.sessionSig.value,
      ).catch((err) => console.error('Failed to stop embed generation:', err))
    }
    loading.value = false
    isReplying.value = false
  }

  /**
   * 发送入口：发送期间屏蔽 sessionId 变化触发的历史重置。
   * 首条消息建会话时 sessionId 从空变成新 id，resetAndLoad 会停止当前流、清空列表
   * 并拉历史；那次重置与这一轮发送竞争——历史里若已包含刚落库的用户消息，界面会
   * 显示两个问题，占位行还会顶掉流式回答。
   */
  const sendMsg = async (
    value: string,
    opts: { webSearchEnabled?: boolean; imageFiles?: File[]; attachmentFiles?: File[] } = {},
  ) => {
    sending = true
    sendingSessionId = options.sessionId.value
    try {
      await submitMessage(value, opts)
    } finally {
      sending = false
      sendingSessionId = ''
    }
  }

  const submitMessage = async (
    value: string,
    opts: { webSearchEnabled?: boolean; imageFiles?: File[]; attachmentFiles?: File[] } = {},
  ) => {
    // 懒创建：首条消息发送前才把会话落库；失败时不产生本地消息半成品。
    if (options.ensureSession) {
      const ready = await options.ensureSession()
      if (!ready) {
        embedToast(t('embedPublish.sessionFailed'))
        return
      }
      // 新建成功后目标会话才确定，后续同 id 的 sessionId 变化一律让路。
      sendingSessionId = options.sessionId.value
    }
    stopStream()
    prepareForNewOutgoingMessage()
    const outboundQuery = buildQueryWithHostContext(value, options.hostContext?.value)
    const visitorWebSearchEnabled = opts.webSearchEnabled ?? false
    const imageFiles = (options.allowFileUpload ? opts.imageFiles : undefined) || []
    const attachmentFiles = (options.allowFileUpload ? opts.attachmentFiles : undefined) || []
    isReplying.value = true
    loading.value = true

    const imageAttachments: Array<{ data: string }> = []
    const displayImages: Array<{ url: string }> = []
    // 上传载荷（QA 请求体的 attachmentUploads，§14.9l S4 后 camelCase）与本地消息上的
    // displayAttachments 同形——两者都与 REST 返回的 MessageAttachment 一致。
    const attachmentUploads: Array<{ data: string; fileName: string; fileSize: number }> = []
    const displayAttachments: Array<{ fileName: string; fileSize: number }> = []
    try {
      for (const file of imageFiles) {
        const dataURI = String(await fileToDataURI(file))
        imageAttachments.push({ data: dataURI })
        displayImages.push({ url: dataURI })
      }
      for (const file of attachmentFiles) {
        const dataURI = String(await fileToDataURI(file))
        attachmentUploads.push({ data: dataURI, fileName: file.name, fileSize: file.size })
        displayAttachments.push({ fileName: file.name, fileSize: file.size })
      }
    } catch (err) {
      console.error('Failed to read attachment:', err)
      embedToast(t('chat.imageReadFailed'))
      isReplying.value = false
      loading.value = false
      return
    }

    messagesList.push({
      content: value,
      role: 'user',
      mentionedItems: [],
      images: displayImages,
      attachments: displayAttachments,
      channel: 'embed',
      createdAt: new Date().toISOString(),
    })
    postEmbedMessageSent(options.channelId, options.sessionId.value, value)
    relayEmbedWebhookEvent(
      options.channelId,
      options.token,
      options.sessionId.value,
      options.sessionSig.value,
      { type: 'message_sent', query: value },
    )
    userHasScrolledUp.value = false
    scrollToBottom(true)

    const agentEnabled = isAgentStreamSession()
    const endpoint = agentEnabled
      ? `/api/v1/embed/${options.channelId}/agent-chat`
      : `/api/v1/embed/${options.channelId}/knowledge-chat`

    const suggestionAttribution = pendingSuggestionAttribution
    pendingSuggestionAttribution = null
    await startStream({
      sessionId: options.sessionId.value,
      knowledgeBaseIds: options.kbIds,
      knowledgeIds: [],
      agentEnabled: agentEnabled,
      agentId: options.agentId,
      webSearchEnabled: (options.allowWebSearch ?? false) && visitorWebSearchEnabled,
      summaryModelId: '',
      mcpServiceIds: [],
      mentionedItems: [],
      images: imageAttachments.length > 0 ? imageAttachments : undefined,
      attachmentUploads: attachmentUploads.length > 0 ? attachmentUploads : undefined,
      query: outboundQuery,
      suggestionAttribution: suggestionAttribution || undefined,
      method: 'POST',
      url: endpoint,
      embed_token: options.token,
      embed_session_sig: options.sessionSig.value,
      embed_visitor_id: options.visitorId.value,
    })
  }

  watch(isReplying, (streaming) => options.onStreamingChange?.(streaming))

  watch(error, (newError) => {
    if (newError) {
      embedToast(newError)
      isReplying.value = false
      loading.value = false
      currentAssistantMessageId.value = ''
    }
  })

  onChunk((data) => {
    if (data.responseType === 'sessionTitle') {
      const title = String(data.content || (data.data as { title?: string })?.title || '').trim()
      if (title) {
        options.onSessionTitle?.(title)
      }
      return
    }
    processStreamChunk(data)
  })

  const resetAndLoad = (sid: string) => {
    // 正在发送的这一轮：本地乐观消息与在跑的回答都在这份列表里，重置会把它们
    // 清掉；历史里若已包含刚落库的用户消息，还会把同一句话显示成两遍。
    if (sending && (sendingSessionId === '' || sid === sendingSessionId)) {
      sendingSessionId = sid
      // 刚建的会话没有更早的历史；留着 true 会让"滚到顶"用空游标再拉一次
      // 最新消息（即屏幕上这些），把同一句话又插一遍。
      hasMoreHistory.value = false
      return
    }
    // 切换会话先断掉旧流：否则上一会话的 chunk 会写进新会话的消息列表。
    stopStream()
    messagesList.splice(0)
    historyLoading.value = true
    historyLoadingMore.value = false
    hasMoreHistory.value = true
    created_at.value = ''
    loading.value = false
    isReplying.value = false
    currentAssistantMessageId.value = ''
    userHasScrolledUp.value = false
    isFirstEnter.value = true
    fullContent.value = ''
    if (!sid) {
      historyLoading.value = false
      return
    }
    getmsgList({ sessionId: sid, created_at: '', limit: limit.value })
  }

  watch(
    () => options.sessionId.value,
    (sid) => resetAndLoad(sid),
    { immediate: true },
  )

  onMounted(() => {
    loading.value = false
    isReplying.value = false
  })

  onUnmounted(() => {
    stopStream()
    fullContent.value = ''
  })

  return {
    messagesList,
    loading,
    isReplying,
    historyLoading,
    scrollContainer,
    userHasScrolledUp,
    isFirstEnter,
    shouldRenderAssistantMessage,
    shouldShowGlobalTypingIndicator,
    getUserQuery,
    handleScroll,
    scrollToBottom,
    onClickScrollToBottom,
    sendMsg,
    handleStopGeneration,
    setSuggestionAttribution: (suggestionSetId: string, questionId: string) => {
      pendingSuggestionAttribution = { suggestionSetId, questionId }
    },
  }
}
