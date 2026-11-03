import { applyFinalArtifactContent } from '@/utils/finalArtifactContent'
import { normalizeKnowledgeReference } from '@/utils/referenceSources'
import { markRaw, nextTick, onScopeDispose, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ensureRagPipelineHistoryStream } from '@/utils/rag-pipeline-history'
import { applyMessageCreatedAt, bindServerTurnTimestamps, ensureMessageCreatedAt } from '@/utils/messageTimestamp'
import { expandSteerForksInHistory, forkAfterInjectedUser, steerStepEvents, resetSteerTurnForReplay } from '@/utils/steerStreamFork'
import type { BackendStreamEvent, ChatMessage } from '@/types/chatStream'
import { normalizeStreamEvent } from '@/types/chatStream'
import { preambleRetractDelayMs } from '@/utils/preambleRetract'

export interface UseChatStreamHandlerOptions {
  messagesList: ChatMessage[]
  loading: Ref<boolean>
  isReplying: Ref<boolean>
  currentAssistantMessageId: Ref<string>
  fullContent: Ref<string>
  isAgentStreamSession: () => boolean
  scrollToBottom: (force?: boolean) => void
  onReplyComplete?: (content: string) => void
  onTurnComplete?: (message: ChatMessage) => void
  onError?: (message: string) => void
  /** Main chat: keep the last incomplete message reactive for continue-stream. */
  preserveIncompleteStreamReactive?: boolean
  isFirstEnter?: Ref<boolean>
  scrollContainer?: Ref<HTMLElement | null>
  onAfterMsgList?: () => void | Promise<void>
  onAgentQuery?: (
    data: ChatMessage,
    existingMessage: ChatMessage | undefined,
    created: boolean,
  ) => void
  onMessageCreated?: (message: ChatMessage) => void
  onMessageUpdated?: (message: ChatMessage, payload?: ChatMessage) => void
  onAgentAnswerDone?: (message: ChatMessage) => void
  onAgentChunkBound?: (message: ChatMessage, created: boolean) => void
  onUserMessageInjected?: (steerId: string) => void
  /** Remote or local stop: the composer overlay must drop server-discarded items. */
  onGenerationStopped?: () => void
  debug?: boolean
}

function mergeToolCallArguments(previous: unknown, incoming: unknown): Record<string, unknown> {
  const prev =
    previous && typeof previous === 'object' && !Array.isArray(previous)
      ? (previous as Record<string, unknown>)
      : {}
  const next =
    incoming && typeof incoming === 'object' && !Array.isArray(incoming)
      ? (incoming as Record<string, unknown>)
      : { value: incoming }
  return { ...prev, ...next }
}

export function useChatStreamHandler(options: UseChatStreamHandlerOptions) {
  const { t } = useI18n()
  const {
    messagesList,
    loading,
    isReplying,
    currentAssistantMessageId,
    fullContent,
    isAgentStreamSession,
    scrollToBottom,
    onReplyComplete,
    onTurnComplete,
    onError,
    preserveIncompleteStreamReactive = false,
    isFirstEnter,
    scrollContainer,
    onAfterMsgList,
    onAgentQuery,
    onMessageCreated,
    onMessageUpdated,
    onAgentAnswerDone,
    onAgentChunkBound,
    onUserMessageInjected,
    onGenerationStopped,
    debug = false,
  } = options

  const emitMessageCreated = (message: ChatMessage) => {
    ensureMessageCreatedAt(message)
    onMessageCreated?.(message)
  }

  const emitMessageUpdated = (message: ChatMessage, payload?: ChatMessage) => {
    if (payload) applyMessageCreatedAt(message, payload.createdAt)
    onMessageUpdated?.(message, payload)
  }

  const log = (...args: unknown[]) => {
    if (debug) console.log(...args)
  }

  const findLastMessage = (predicate: (item: ChatMessage) => boolean) => {
    for (let i = messagesList.length - 1; i >= 0; i--) {
      const item = messagesList[i]
      if (predicate(item)) return item
    }
    return undefined
  }

  /** Incomplete assistant for the current turn, even if a later user row is the tail. */
  const getTrailingIncompleteAssistant = () => {
    for (let i = messagesList.length - 1; i >= 0; i--) {
      const item = messagesList[i]
      if (item?.role === 'assistant' && !item.completed) return item
    }
    return undefined
  }

  const markAssistantStopped = (message: ChatMessage) => {
    if (!message || message.completed) return
    message.completed = true
    if (message.isAgentMode) {
      if (!message.agentEventStream) message.agentEventStream = []
      const stream = message.agentEventStream as ChatMessage[]
      if (!stream.some((e) => e.type === 'stop')) {
        stream.push({
          type: 'stop',
          timestamp: Date.now(),
          reason: 'user_requested',
        })
      }
    }
  }

  /** Finalize any in-flight assistant rows before a new user query is sent. */
  const prepareForNewOutgoingMessage = () => {
    for (const msg of messagesList) {
      if (msg.role === 'assistant' && !msg.completed) {
        markAssistantStopped(msg)
      }
    }
    fullContent.value = ''
    currentAssistantMessageId.value = ''
  }

  /** Mark the assistant row being stopped without clearing its id (stop API still needs it). */
  const markInFlightAssistantStopped = (messageId?: string) => {
    let target: ChatMessage | undefined
    if (messageId) {
      target = messagesList.find(
        (m) =>
          m.role === 'assistant' &&
          !m.completed &&
          (m.id === messageId ||
            m.assistantMessageId === messageId ||
            m.requestId === messageId),
      )
    }
    if (!target) target = getTrailingIncompleteAssistant()
    if (target) markAssistantStopped(target)
    fullContent.value = ''
  }

  const extractKnowledgeReferences = (data: ChatMessage) => {
    const dataPayload = data.data as ChatMessage | undefined
    const refs =
      data.knowledgeReferences ||
      dataPayload?.references ||
      dataPayload?.knowledgeReferences ||
      []
    // 同一数组可能两种来源/拼写（重建段 camelCase、直通段与历史库存 snake）→ 统一归一
    return Array.isArray(refs) ? refs.map((r) => normalizeKnowledgeReference(r)) : []
  }

  const replaySegments = new Map<string, ChatMessage>()

  /** Match the in-flight assistant row by request id or assistant message id. */
  const resolveActiveAssistantMessage = (data: ChatMessage) => {
    const dataId = data.id as string | undefined
    const assistantId =
      (data.assistantMessageId as string | undefined) ||
      currentAssistantMessageId.value ||
      undefined

    if (dataId && replaySegments.has(dataId)) return replaySegments.get(dataId)

    const matched = findLastMessage((item) => {
      if (item.role !== 'assistant') return false
      if (dataId && (item.requestId === dataId || item.id === dataId)) return true
      if (assistantId && (item.id === assistantId || item.requestId === assistantId)) return true
      return false
    })
    if (matched) return matched

    return getTrailingIncompleteAssistant()
  }

  const applyKnowledgeReferences = (data: ChatMessage) => {
    const refs = extractKnowledgeReferences(data)
    if (!refs.length) return undefined

    let message = resolveActiveAssistantMessage(data)
    const created = !message
    if (!message) {
      const rowId = (data.id as string | undefined) || currentAssistantMessageId.value
      message = {
        id: rowId,
        requestId: rowId,
        role: 'assistant',
        content: '',
        showThink: false,
        thinkContent: '',
        thinking: false,
        completed: false,
        knowledgeReferences: [],
      }
      ensureAgentMessageShell(message, data.id as string | undefined)
      messagesList.push(message)
      emitMessageCreated(message)
      loading.value = false
    } else {
      ensureAgentMessageShell(message, data.id as string | undefined)
    }

    message.knowledgeReferences = refs.slice()
    if (created) onAgentChunkBound?.(message, true)
    emitMessageUpdated(message, data)
    log('[References] Saved to message, count:', refs.length)
    return message
  }

  // Records which long-term memories the answer saw. Unlike references this
  // never creates a message shell: memory arrives before the first token, and
  // an empty bubble that only says "3 memories" would be worse than waiting
  // for the answer's own placeholder.
  const applyUsedMemories = (data: ChatMessage) => {
    const payload = (data.data ?? {}) as Record<string, unknown>
    const memories = (payload.memories ?? data.memories) as unknown
    if (!Array.isArray(memories) || memories.length === 0) return undefined

    const message = resolveActiveAssistantMessage(data)
    if (!message) {
      log('[Memory] No assistant message to attach memories to')
      return undefined
    }
    message.usedMemories = memories.slice()
    emitMessageUpdated(message, data)
    log('[Memory] Saved to message, count:', memories.length)
    return message
  }

  const ensureAgentMessageShell = (message: ChatMessage, requestId?: string) => {
    if (message.role === 'user') return
    message.isAgentMode = true
    if (!isAgentStreamSession()) {
      message.isRagMode = true
    }
    if (!message.agentEventStream) message.agentEventStream = []
    if (!message._eventMap) message._eventMap = new Map()
    if (!message._pendingToolCalls) message._pendingToolCalls = new Map()
    if (requestId) {
      if (!message.id) message.id = requestId
      if (!message.requestId) message.requestId = requestId
    }
  }

  const shouldRenderAssistantMessage = (session: ChatMessage) => {
    if (!session?.isAgentMode) return true
    if (!session.completed) return true
    const stream = session.agentEventStream
    if (Array.isArray(stream) && stream.length > 0) return true
    if (Array.isArray(session.knowledgeReferences) && session.knowledgeReferences.length > 0) {
      return true
    }
    // A turn can carry its answer as plain content with no timeline events —
    // most visibly after a steer fork, where the events are split across
    // segments and one segment can end up holding only the answer text.
    // Hiding that row loses the reply entirely on reload.
    if (typeof session.content === 'string' && session.content.trim()) return true
    return false
  }

  const shouldShowGlobalTypingIndicator = (
    messages: ChatMessage[],
    isLoading: boolean,
    isRecovering = false,
  ) => {
    if (!isLoading && !isRecovering) return false
    if (messages.some((m) => m.role === 'assistant' && m.isAgentMode && !m.completed)) {
      return false
    }
    return true
  }

  /** Quick-answer sessions: restore flags lost after history reload. */
  const restoreQuickAnswerFlags = (item: ChatMessage) => {
    if (isAgentStreamSession() || item.role !== 'assistant') return
    item.isRagMode = true
    if (
      item.agentSteps &&
      Array.isArray(item.agentSteps) &&
      item.agentSteps.length > 0
    ) {
      item.isAgentMode = true
      item.hideContent = true
    }
    ensureRagPipelineHistoryStream(item as Parameters<typeof ensureRagPipelineHistoryStream>[0])
    if (item.isRagMode && item.agentEventStream) {
      item.agentEventStream = markRaw(item.agentEventStream)
    }
  }

  const recomposeAgentAnswer = (message: ChatMessage) => {
    const stream = message.agentEventStream as Array<{
      type?: string
      superseded?: boolean
      content?: string
    }> | undefined
    if (!stream) return ''
    let out = ''
    for (const e of stream) {
      if (e.type === 'answer' && !e.superseded && e.content) {
        out += e.content
      }
    }
    return out
  }

  // 工具调用会把刚流出的「前言」段落撤回（标记 superseded，折进思考卡片）。
  // 立即撤回时内容往往刚上屏一瞬，观感是「话没打完就没了」；这里按前言字数让出打字机
  // 需要的时间，再撤回（preambleRetractDelayMs）。新答案一到则立即撤回，避免两段内容
  // 同框把气泡撑高再缩回。
  const pendingRetractions = new Map<
    ChatMessage,
    { timer: ReturnType<typeof setTimeout>; targets: ChatMessage[] }
  >()

  const retractAnswerPreamble = (message: ChatMessage, targets: ChatMessage[]) => {
    let retracted = false
    for (const ev of targets) {
      if (ev.type === 'answer' && !ev.superseded && ev.content && String(ev.content).trim()) {
        ev.superseded = true
        ev.done = true
        retracted = true
      }
    }
    if (!retracted) return
    message.content = recomposeAgentAnswer(message)
    fullContent.value = String(message.content || '')
  }

  const cancelPendingRetraction = (message: ChatMessage) => {
    const entry = pendingRetractions.get(message)
    if (!entry) return
    clearTimeout(entry.timer)
    pendingRetractions.delete(message)
  }

  /** 立即执行待撤回（新答案到来时调用），与计时器互为兜底。 */
  const flushPendingRetraction = (message: ChatMessage) => {
    const entry = pendingRetractions.get(message)
    if (!entry) return
    pendingRetractions.delete(message)
    clearTimeout(entry.timer)
    retractAnswerPreamble(message, entry.targets)
  }

  onScopeDispose(() => {
    for (const entry of pendingRetractions.values()) clearTimeout(entry.timer)
    pendingRetractions.clear()
  })

  const reconstructEventStreamFromSteps = (
    agentSteps: unknown[],
    messageContent: string,
    isCompleted = false,
    isFallback = false,
    agentDurationMs = 0,
    usage?: unknown,
  ) => {
    const events: ChatMessage[] = []

    if (agentSteps && Array.isArray(agentSteps) && agentSteps.length > 0) {
      agentSteps.forEach((rawStep) => {
        const step = rawStep as ChatMessage
        events.push(...steerStepEvents(step))
        const stepTimestamp = step.timestamp ? new Date(String(step.timestamp)).getTime() : 0
        const toolCalls = step.toolCalls
        const hasToolCalls = toolCalls && Array.isArray(toolCalls) && toolCalls.length > 0

        const reasoningText =
          step.reasoningContent && String(step.reasoningContent).trim()
            ? String(step.reasoningContent)
            : ''
        if (reasoningText) {
          events.push({
            type: 'thinking',
            eventId: `step-${step.iteration}-thought`,
            content: reasoningText,
            done: true,
            thinking: false,
            timestamp: stepTimestamp || undefined,
            durationMs: step.duration || undefined,
          })
        }
        const preambleText = step.thought && String(step.thought).trim() ? String(step.thought) : ''
        if (preambleText && step.intermediateAnswer) {
          events.push({ type: 'answer', eventId: `step-${step.iteration}-answer`,
            content: preambleText, done: true, intermediate_answer: true, timestamp: stepTimestamp || undefined })
        }
        if (preambleText && hasToolCalls) {
          events.push({
            type: 'answer',
            eventId: `step-${step.iteration}-preamble`,
            content: preambleText,
            done: true,
            superseded: true,
            timestamp: stepTimestamp || undefined,
          })
        }

        if (toolCalls && Array.isArray(toolCalls)) {
          toolCalls.forEach((toolCall: ChatMessage) => {
            if (toolCall.name === 'finalAnswer') return
            const result = toolCall.result as ChatMessage | undefined
            const resultData = result?.data as ChatMessage | undefined
            const target = toolCall.target as ChatMessage | undefined
            events.push({
              type: 'toolCall',
              toolCallId: toolCall.id,
              toolName: target?.name || toolCall.name,
              arguments: target?.args || toolCall.args,
              pending: false,
              success: result?.success !== false,
              output: result?.output || '',
              error: result?.error || undefined,
              timestamp: stepTimestamp || undefined,
              duration: toolCall.duration,
              durationMs: toolCall.duration,
              displayType: resultData?.displayType,
              tool_data: result?.data as Record<string, unknown> | undefined,
            })
          })
        }
      })
    }

    if (agentDurationMs > 0 || usage) {
      events.push({
        type: 'agentComplete',
        totalDurationMs: agentDurationMs,
        usage,
      })
    }

    if (messageContent && messageContent.trim()) {
      const answerEvent: ChatMessage = {
        type: 'answer',
        content: messageContent,
        done: true,
      }
      if (isFallback) answerEvent.isFallback = true
      events.push(answerEvent)
    } else if (isCompleted) {
      events.push({
        type: 'stop',
        timestamp: Date.now(),
        reason: 'user_requested',
      })
    }

    return events
  }

  /**
   * 断流兜底：用服务端已完成的历史行覆盖内存中的同一条消息。
   * 与 handleMsgList 对「已完成行」的处理保持一致（agent 步骤 → 重建事件流），
   * 否则消息会永远停在未完成，UI 一直显示“正在思考”。
   */
  const applyCompletedSnapshot = (message: ChatMessage | undefined, row: ChatMessage | undefined): boolean => {
    if (!message || !row || !row.completed) return false

    message.content = String(row.content || '')
    message.completed = true
    message.error = undefined
    message.thinking = false
    message.thinkContent = ''
    message.showThink = false
    if (row.agentDurationMs !== undefined) message.agentDurationMs = row.agentDurationMs
    if (row.usage !== undefined) message.usage = row.usage
    if (Array.isArray(row.knowledgeReferences)) {
      message.knowledgeReferences = row.knowledgeReferences.map((item) =>
        normalizeKnowledgeReference(item as ChatMessage),
      )
    }

    const steps = Array.isArray(row.agentSteps) ? (row.agentSteps as unknown[]) : []
    if (steps.length > 0) {
      message.agentSteps = markRaw(steps as object)
      message.isAgentMode = true
      message.hideContent = true
      message.agentEventStream = markRaw(
        reconstructEventStreamFromSteps(
          steps,
          String(row.content || ''),
          true,
          Boolean(row.fallback),
          Number(row.agentDurationMs) || 0,
          row.usage,
        ),
      )
    } else if (message.isAgentMode) {
      // 无步骤但有正文：补一条 answer 事件，避免时间线停在最后一步。
      const stream = (message.agentEventStream as ChatMessage[] | undefined) || []
      if (!stream.some((event) => event?.type === 'answer' && event.done)) {
        stream.push({ type: 'answer', content: String(row.content || ''), done: true })
      }
      message.agentEventStream = markRaw(stream)
    }

    if (row.suggestionSet !== undefined) message.suggestionSet = row.suggestionSet
    return true
  }

  const handleMsgList = async (
    data: ChatMessage[],
    isScrollType = false,
    newScrollHeight?: number,
  ) => {
    const chatlist = [...data]
    const existingIds = new Set(messagesList.map((m) => m.id).filter(Boolean))
    const processed: ChatMessage[] = []

    for (const raw of chatlist) {
      const item = preserveIncompleteStreamReactive ? raw : { ...raw }
      if (item.id && existingIds.has(item.id)) continue
      if (item.id) existingIds.add(item.id)

      item.isAgentMode = false
      const willContinueStream = preserveIncompleteStreamReactive && !item.completed
      if (willContinueStream) {
        item.agentEventStream = item.agentEventStream || []
        item._eventMap = new Map()
        item._pendingToolCalls = new Map()
      } else {
        item.agentSteps = item.agentSteps ? markRaw(item.agentSteps as object) : item.agentSteps
        item.agentEventStream = markRaw(item.agentEventStream || [])
        item._eventMap = markRaw(new Map())
        item._pendingToolCalls = markRaw(new Map())
      }

      if (item.agentSteps && Array.isArray(item.agentSteps) && item.agentSteps.length > 0) {
        item.isAgentMode = true
        item.agentEventStream = markRaw(
          reconstructEventStreamFromSteps(
            item.agentSteps as unknown[],
            String(item.content || ''),
            Boolean(item.completed),
            Boolean(item.fallback),
            Number(item.agentDurationMs) || 0,
            item.usage,
          ),
        )
        item.hideContent = true
      }

      restoreQuickAnswerFlags(item)

      if (item.content) {
        const content = String(item.content)
        const thinkCloseTag = '</think>'
        if (!content.includes('<think>') && !content.includes(thinkCloseTag)) {
          item.thinkContent = ''
          item.showThink = false
          item.thinking = false
        } else if (content.includes(thinkCloseTag)) {
          item.showThink = true
          item.thinking = false
          const index = content.trim().lastIndexOf(thinkCloseTag)
          item.thinkContent = content.trim().substring(0, index).replace('<think>', '').trim()
          item.content = content.trim().substring(index + thinkCloseTag.length)
        } else if (content.includes('<think>')) {
          item.showThink = true
          item.thinking = true
          item.thinkContent = content.replace('<think>', '').trim()
          item.content = ''
        }
      }

      processed.push(item)
    }

    if (processed.length > 0) {
      if (isScrollType) {
        for (let i = processed.length - 1; i >= 0; i--) {
          messagesList.unshift(processed[i])
        }
        const expanded = expandSteerForksInHistory([...messagesList])
        messagesList.splice(0, messagesList.length, ...expanded)
      } else {
        messagesList.push(...expandSteerForksInHistory(processed))
      }
    }

    if (isFirstEnter?.value) {
      scrollToBottom(true)
    } else if (isScrollType && scrollContainer?.value && typeof newScrollHeight === 'number') {
      nextTick(() => {
        if (!scrollContainer.value) return
        const { scrollHeight } = scrollContainer.value
        scrollContainer.value.scrollTop = scrollHeight - newScrollHeight
      })
    }

    if (onAfterMsgList) {
      await onAfterMsgList()
    }
  }

  const updateAssistantSession = (payload: ChatMessage) => {
    const message = findLastMessage((item) => {
      if (item.role !== 'assistant') return false
      if (item.requestId === payload.id) return true
      return item.id === payload.id
    })
    if (message) {
      if (payload.id && !message.requestId) message.requestId = payload.id
      message.content = payload.content
      message.thinking = payload.thinking
      message.thinkContent = payload.thinkContent
      message.showThink = payload.showThink
      if (!message.knowledgeReferences) {
        message.knowledgeReferences = Array.isArray(payload.knowledgeReferences)
          ? payload.knowledgeReferences.map((r) => normalizeKnowledgeReference(r))
          : payload.knowledgeReferences
      }
      if (payload.fallback) message.fallback = true
      if (payload.completed) message.completed = true
      emitMessageUpdated(message, payload)
    } else {
      const entry = { ...payload }
      if (entry.id && !entry.requestId) entry.requestId = entry.id
      messagesList.push(entry)
      emitMessageCreated(entry)
      emitMessageUpdated(entry, payload)
    }
    scrollToBottom()
  }

  const reportError = (errorMsg: string) => {
    if (onError) {
      onError(errorMsg)
    }
  }

  const handleAgentChunk = (data: BackendStreamEvent) => {
    const dataId = data.id as string | undefined
    let message = resolveActiveAssistantMessage(data)
    let created = false

    if (message?.role === 'user') {
      message = undefined
    }

    if (!message) {
      const newMsg: ChatMessage = {
        id: dataId,
        requestId: dataId,
        role: 'assistant',
        content: '',
        isAgentMode: true,
        isRagMode: !isAgentStreamSession(),
        agentEventStream: [],
        _eventMap: new Map(),
        knowledgeReferences: [],
      }
      messagesList.push(newMsg)
      emitMessageCreated(newMsg)
      loading.value = false
      scrollToBottom(true)
      message = newMsg
      created = true
    } else {
      onAgentChunkBound?.(message, false)
    }

    if (created) {
      onAgentChunkBound?.(message, true)
    }

    ensureAgentMessageShell(message, dataId)

    if (
      loading.value &&
      (data.responseType === 'thinking' ||
        data.responseType === 'answer' ||
        data.responseType === 'toolCall' ||
        data.responseType === 'toolApprovalRequired')
    ) {
      log('[Agent Chunk] Closing loading for continued stream')
      loading.value = false
    }

    const responseType = data.responseType as string
    const dataPayload = data.data as ChatMessage | undefined

    switch (responseType) {
      case 'thinking': {
        const eventId = dataPayload?.eventId as string | undefined
        log('[Thinking Event]', {
          eventId: eventId,
          done: data.done,
          contentLength: (data.content as string | undefined)?.length || 0,
        })
        if (!message.agentEventStream) message.agentEventStream = []
        if (!message._eventMap) message._eventMap = new Map()
        const eventMap = message._eventMap as Map<string, ChatMessage>
        const stream = message.agentEventStream as ChatMessage[]

        if (!data.done) {
          let thinkingEvent = eventMap.get(eventId || '')
          if (!thinkingEvent) {
            log('[Thinking] Creating new thinking event, eventId:', eventId)
            thinkingEvent = {
              type: 'thinking',
              eventId: eventId,
              content: '',
              done: false,
              startTime: Date.now(),
              thinking: true,
            }
            stream.push(thinkingEvent)
            if (eventId) eventMap.set(eventId, thinkingEvent)
          }
          if (data.content) {
            thinkingEvent.content = String(thinkingEvent.content || '') + String(data.content)
            log('[Thinking] Event', eventId, 'accumulated:', String(thinkingEvent.content).length, 'chars')
          }
        } else {
          const thinkingEvent = eventMap.get(eventId || '')
          if (thinkingEvent) {
            thinkingEvent.done = true
            thinkingEvent.thinking = false
            thinkingEvent.durationMs =
              dataPayload?.durationMs || Date.now() - Number(thinkingEvent.startTime || Date.now())
            thinkingEvent.completedAt = dataPayload?.completedAt || Date.now()
            log('[Thinking] Event completed, duration:', thinkingEvent.durationMs, 'ms')
          } else {
            console.warn('[Thinking] Received done for unknown eventId:', eventId)
          }
        }
        break
      }
      case 'contextCompacted': {
        // Shown in the timeline rather than swallowed: after a compaction the
        // agent no longer sees the earlier rounds, and without a marker that
        // reads as the model ignoring what it was told.
        if (!message.agentEventStream) message.agentEventStream = []
        const d = dataPayload || {}
        ;(message.agentEventStream as ChatMessage[]).push({
          type: 'contextCompacted',
          eventId: data.id || `compaction-${Date.now()}`,
          reason: d.reason,
          round: d.round,
          tokensBefore: d.tokensBefore,
          tokensAfter: d.tokensAfter,
          messagesBefore: d.messagesBefore,
          messagesAfter: d.messagesAfter,
          summary: d.summary,
          degraded: d.degraded,
          splitTurn: d.splitTurn,
        })
        break
      }
      case 'toolApprovalRequired': {
        if (!message.agentEventStream) message.agentEventStream = []
        const d = dataPayload || {}
        ;(message.agentEventStream as ChatMessage[]).push({
          type: 'toolApprovalRequired',
          pendingId: d.pendingId,
          serviceName: d.serviceName,
          mcpToolName: d.mcpToolName,
          description: d.description,
          argsJson: d.argsJson,
          timeoutSeconds: d.timeoutSeconds,
          requestedAtUnix: d.requestedAtUnix,
          toolCallId: d.toolCallId,
          resolved: false,
        })
        break
      }
      case 'toolApprovalResolved': {
        const d = dataPayload || {}
        const pid = d.pendingId
        const ev = (message.agentEventStream as ChatMessage[] | undefined)?.find(
          (e) => e.type === 'toolApprovalRequired' && e.pendingId === pid,
        )
        if (ev) {
          ev.resolved = true
          ev.approved = d.approved
          ev.resolve_reason = d.reason
          ev.timedOut = d.timedOut
          ev.canceled = d.canceled
        }
        break
      }
      case 'mcpOauthRequired': {
        if (!message.agentEventStream) message.agentEventStream = []
        const d = dataPayload || {}
        ;(message.agentEventStream as ChatMessage[]).push({
          type: 'mcpOauthRequired',
          pendingId: d.pendingId,
          serviceId: d.serviceId,
          serviceName: d.serviceName,
          mcpToolName: d.mcpToolName,
          timeoutSeconds: d.timeoutSeconds,
          requestedAtUnix: d.requestedAtUnix,
          toolCallId: d.toolCallId,
          resolved: false,
        })
        break
      }
      case 'mcpOauthResolved': {
        const d = dataPayload || {}
        const pid = d.pendingId
        const sid = d.serviceId
        const list = message.agentEventStream as ChatMessage[] | undefined
        // Resolve the matching card; also clear any other still-pending cards
        // for the same service (parallel tool calls dedup to a single auth).
        list?.forEach((e) => {
          if (e.type !== 'mcpOauthRequired' || e.resolved) return
          if (e.pendingId === pid || (sid && e.serviceId === sid && d.authorized)) {
            e.resolved = true
            e.authorized = d.authorized
            e.resolve_reason = d.reason
            e.timedOut = d.timedOut
            e.canceled = d.canceled
          }
        })
        break
      }
      case 'toolCall': {
        if (dataPayload?.toolName === 'finalAnswer') break
        if (message.agentEventStream && !pendingRetractions.has(message)) {
          // 只撤本次工具调用前已上屏的前言；延后触发，先让打字机把话说完
          const targets = (message.agentEventStream as ChatMessage[]).filter(
            (ev) => ev.type === 'answer' && !ev.superseded && ev.content && String(ev.content).trim(),
          )
          if (targets.length) {
            const pendingChars = targets.reduce(
              (sum, ev) => sum + String(ev.content || '').length,
              0,
            )
            const timer = setTimeout(() => {
              const entry = pendingRetractions.get(message)
              pendingRetractions.delete(message)
              if (!entry || message.completed) return
              retractAnswerPreamble(message, entry.targets)
            }, preambleRetractDelayMs(pendingChars))
            pendingRetractions.set(message, { timer, targets })
          }
        }
        if (dataPayload && (dataPayload.toolName || dataPayload.toolCallId)) {
          if (!message.agentEventStream) message.agentEventStream = []
          if (!message._pendingToolCalls) message._pendingToolCalls = new Map()
          const pending = message._pendingToolCalls as Map<string, ChatMessage>
          const stream = message.agentEventStream as ChatMessage[]
          const incomingToolName = dataPayload.toolName as string | undefined
          const incomingArguments = dataPayload.arguments
          const toolCallId =
            (dataPayload.toolCallId as string) ||
            (incomingToolName ? `${incomingToolName}_${Date.now()}` : null)
          if (!toolCallId) {
            console.warn('[Tool Call] Received event without identifiable toolCallId:', dataPayload)
            break
          }

          log('[Tool Call]', {
            toolCallId: toolCallId,
            toolName: incomingToolName,
            has_arguments: Boolean(incomingArguments),
          })

          let toolCallEvent = pending.get(toolCallId)
          if (!toolCallEvent) {
            toolCallEvent = stream.find(
              (event) => event.type === 'toolCall' && event.toolCallId === toolCallId,
            )
          }
          if (toolCallEvent) {
            const resolvedMcpTarget =
              toolCallEvent.toolName === 'call_mcp_tool' && incomingToolName?.startsWith('mcp_')
            if (incomingToolName) toolCallEvent.toolName = incomingToolName
            if (incomingArguments) {
              if (resolvedMcpTarget) {
                // The executor now supplies the target's arguments; discard
                // the streamed proxy envelope instead of mixing the two.
                toolCallEvent.arguments = incomingArguments
              } else {
                toolCallEvent.arguments = mergeToolCallArguments(toolCallEvent.arguments, incomingArguments)
              }
            }
            toolCallEvent.pending = true
            if (!toolCallEvent.timestamp) toolCallEvent.timestamp = Date.now()
            pending.set(toolCallId, toolCallEvent)
          } else {
            const newToolCallEvent = {
              type: 'toolCall',
              toolCallId: toolCallId,
              toolName: incomingToolName,
              arguments: incomingArguments,
              timestamp: Date.now(),
              pending: true,
            }
            stream.push(newToolCallEvent)
            pending.set(toolCallId, newToolCallEvent)
          }
        }
        break
      }
      case 'commandOutput': {
        const toolCallId = dataPayload?.toolCallId as string | undefined
        if (!toolCallId) break
        const tool = (message.agentEventStream as ChatMessage[] | undefined)?.find(
          event => event.type === 'toolCall' && event.toolCallId === toolCallId,
        )
        // Late progress must not resurrect a completed command or attach to
        // another concurrent call just because it uses the same tool name.
        if (tool?.pending && tool.toolName === 'shell_exec' && !(tool.commandOutput as ChatMessage | undefined)?.done) {
          tool.commandOutput = dataPayload
        }
        break
      }
      case 'toolResult':
      case 'error': {
        if (dataPayload) {
          const toolCallId = dataPayload.toolCallId as string | undefined
          const toolName = dataPayload.toolName as string | undefined
          const success = responseType !== 'error' && dataPayload.success !== false
          log('[Tool Result]', {
            toolCallId: toolCallId,
            toolName: toolName,
            success,
          })
          let toolCallEvent: ChatMessage | undefined
          const pending = message._pendingToolCalls as Map<string, ChatMessage> | undefined
          if (pending) {
            if (toolCallId && pending.has(toolCallId)) {
              toolCallEvent = pending.get(toolCallId)
              pending.delete(toolCallId)
            } else {
              Array.from(pending.entries()).some(([key, value]) => {
                if (value.toolName === toolName) {
                  toolCallEvent = value
                  pending.delete(key)
                  return true
                }
                return false
              })
            }
          }
          if (toolCallEvent) {
            toolCallEvent.pending = false
            toolCallEvent.success = success
            // Keep stdout/markdown on failure. The error field is often just
            // "exited with code 1" plus a retry hint; the streams live on
            // output / tool_data and are what the terminal card should show.
            toolCallEvent.output = dataPayload.output || data.content
            toolCallEvent.error = !success ? dataPayload.error || data.content : undefined
            const duration =
              dataPayload.durationMs !== undefined ? dataPayload.durationMs : dataPayload.duration
            toolCallEvent.duration = duration
            toolCallEvent.durationMs = duration
            toolCallEvent.displayType = dataPayload.displayType
            toolCallEvent.tool_data = dataPayload
            log('[Tool Result] Updated event in stream')
          } else {
            console.warn('[Tool Result] No pending tool call found for', toolCallId || toolName)
          }
          if (responseType === 'error' && !toolName) {
            const errorMsg = String(data.content || t('chat.processError'))
            message.content = errorMsg
            // 消息级错误标记：气泡（botmsg 的 error-wrapper）据此渲染失败原因——
            // 只弹 toast 的话，用户回头/刷新就看不到任何提示。
            message.error = errorMsg
            message.completed = true
            isReplying.value = false
            loading.value = false
            fullContent.value = ''
            currentAssistantMessageId.value = ''
            reportError(errorMsg)
            console.error('[Chat Error]', errorMsg)
          }
        } else if (responseType === 'error') {
          const errorMsg = String(data.content || t('chat.processError'))
          message.content = errorMsg
          message.error = errorMsg
          message.completed = true
          isReplying.value = false
          loading.value = false
          fullContent.value = ''
          currentAssistantMessageId.value = ''
          reportError(errorMsg)
          console.error('[Chat Error]', errorMsg)
        }
        break
      }
      case 'answer': {
        message.thinking = false
        // 新答案开始：先把待撤回的前言清掉，别让「前言 + 新答案」同框把气泡撑高
        flushPendingRetraction(message)
        const eventId = dataPayload?.eventId as string | undefined
        if (!message.agentEventStream) message.agentEventStream = []
        if (!message._eventMap) message._eventMap = new Map()
        const eventMap = message._eventMap as Map<string, ChatMessage>
        const stream = message.agentEventStream as ChatMessage[]

        let answerEvent = eventId
          ? eventMap.get(eventId)
          : stream.find((e) => e.type === 'answer' && !e.eventId)
        if (!answerEvent) {
          answerEvent = { type: 'answer', eventId: eventId, content: '', done: false }
          stream.push(answerEvent)
          if (eventId) eventMap.set(eventId, answerEvent)
        }
        if (!answerEvent.content && message.content && String(message.content).trim()) {
          answerEvent.content = message.content
        }
        if (data.content) {
          answerEvent.content = String(answerEvent.content || '') + String(data.content)
          message.content = recomposeAgentAnswer(message)
          fullContent.value = String(message.content || '')
        }
        if (dataPayload?.isFallback) {
          answerEvent.isFallback = true
          message.fallback = true
        }
        if (data.done && !answerEvent.done) {
          answerEvent.done = true
          onAgentAnswerDone?.(message)
          // Agent turns may continue after a finishing-round inject. Closing
          // isReplying here lets the composer start a second AgentQA. Wait
          // for `complete` (or a non-agent answer) to mark the session idle.
          if (!isAgentStreamSession()) {
            loading.value = false
            isReplying.value = false
            fullContent.value = ''
            currentAssistantMessageId.value = ''
          }
        }
        break
      }
      case 'userMessageInjected': {
        // A message the user queued mid-run was accepted into the running
        // turn. Place it under the work so far, then fork a continuation
        // assistant so later thinking/tools/answer render below it.
        const steerId = dataPayload?.steerId as string | undefined
        log('[Agent] User message injected, steerId:', steerId)
        let injectedUser: ChatMessage | undefined
        let alreadyInList = false
        if (steerId) {
          const injectedId = dataPayload?.userMessageId as string | undefined
          // Resuming a turn replays this event from the start of the log, by
          // which point history has already loaded the persisted row. Reuse
          // it — synthesizing here would show the same message twice.
          injectedUser = messagesList.find(
            (item) =>
              item.role === 'user' &&
              ((!!injectedId && item.id === injectedId) || item.steerId === steerId),
          )
          alreadyInList = Boolean(injectedUser)
          if (!injectedUser) {
            // A restored event or promoted follow-up may arrive before its preview.
            injectedUser = {
              id: injectedId || steerId,
              role: 'user',
              content: String(dataPayload?.content || ''),
              steerId: steerId,
              channel: 'web',
              completed: true,
            }
          }
          if (injectedId) injectedUser.id = injectedId
        }
        if (injectedUser) {
          if (dataId && !injectedUser.requestId) injectedUser.requestId = dataId
          const continuation = forkAfterInjectedUser(
            messagesList,
            message,
            injectedUser,
            steerId,
          )
          if (dataId && replaySegments.has(dataId)) replaySegments.set(dataId, continuation)
          if (!alreadyInList) emitMessageCreated(injectedUser)
          if (continuation !== message) {
            emitMessageCreated(continuation)
            onAgentChunkBound?.(continuation, true)
          }
          if (steerId) onUserMessageInjected?.(steerId)
        }
        break
      }
      case 'complete': {
        if (dataId) replaySegments.delete(dataId)
        cancelPendingRetraction(message)
        log('[Agent] Complete event received')
        applyFinalArtifactContent(message, (dataPayload as any)?.finalContent)
        loading.value = false
        isReplying.value = false
        message.completed = true
        onReplyComplete?.(String(message.content || ''))
        onTurnComplete?.(message)
        fullContent.value = ''
        currentAssistantMessageId.value = ''
        // Hydrate skill-generated artifacts as soon as the SSE completion
        // event arrives — without this the download button only appears
        // after a page refresh (the assistant message row is fetched via
        // getMessageList which does include the artifacts JSON column).
        // botmsg.vue / AgentStreamDisplay.vue read `message.artifacts`
        // reactively to decide whether to render the download button.
        const streamedArtifacts = (dataPayload as any)?.artifacts
        if (Array.isArray(streamedArtifacts) && streamedArtifacts.length) {
          message.artifacts = streamedArtifacts
        }
        const usage = (dataPayload as any)?.usage || (data as any).usage
        if (usage) {
          message.usage = usage
        }
        if (message.agentEventStream) {
          ;(message.agentEventStream as ChatMessage[]).push({
            type: 'agentComplete',
            totalDurationMs: dataPayload?.totalDurationMs || 0,
            totalSteps: dataPayload?.totalSteps || 0,
            usage,
          })
        }
        break
      }
      case 'stop': {
        log('[Agent] Stop event received')
        if (!message.agentEventStream) message.agentEventStream = []
        ;(message.agentEventStream as ChatMessage[]).push({
          type: 'stop',
          timestamp: Date.now(),
          reason: dataPayload?.reason || 'user_requested',
        })
        message.completed = true
        loading.value = false
        isReplying.value = false
        fullContent.value = ''
        currentAssistantMessageId.value = ''
        break
      }
    }

    scrollToBottom()
  }

  /**
   * 把本地乐观追加的用户消息并进服务端行。
   *
   * 乐观那条没有 id，历史加载按 id 去重认不出它，同一个问题会显示两遍。
   * 只有在本轮 requestId 已知、且服务端行与本条内容一致时才合并——requestId
   * 每轮唯一，不会把用户重复问的同一句话误删。
   */
  const mergeOptimisticUserDuplicate = (list: ChatMessage[], requestId: string) => {
    const serverRow = list.find(
      (item) => item.role === 'user' && (item.requestId === requestId || item.id === requestId),
    )
    if (!serverRow) return
    const content = String(serverRow.content || '')
    for (let i = list.length - 1; i >= 0; i--) {
      const item = list[i]
      if (item === serverRow) continue
      if (item.role === 'user' && !item.id && String(item.content || '') === content) {
        list.splice(i, 1)
      }
    }
  }

  const processStreamChunk = (raw: unknown) => {
    const data = normalizeStreamEvent(raw)
    if (!data) {
      log('[Agent Event] drop malformed event', raw)
      return
    }
    log('[Agent Event Received]', {
      responseType: data.responseType,
      id: data.id,
      done: data.done,
      contentLength: (data.content as string | undefined)?.length || 0,
      content_preview: data.content ? String(data.content).substring(0, 50) : '',
      data: data.data,
      sessionId: data.sessionId,
      assistantMessageId: data.assistantMessageId,
    })

    if (data.responseType === 'agentQuery') {
      if (data.id) replaySegments.delete(String(data.id))
      const replay = resetSteerTurnForReplay(messagesList, String(data.id || ''))
      if (replay) replaySegments.set(String(data.id), replay)
      if (data.id) {
        const earlyMsg = getTrailingIncompleteAssistant()
        if (earlyMsg) earlyMsg.requestId = data.id
        mergeOptimisticUserDuplicate(messagesList, String(data.id))
      }
      if (data.assistantMessageId) {
        currentAssistantMessageId.value = data.assistantMessageId as string
        log('[Agent Query] Saved assistant message ID:', data.assistantMessageId)
      }
      // 日志里读的是 SSE 载荷（冻结的线协议，键名仍是下划线），别换成消息对象的键名。
      log('[Agent Query Event]', {
        sessionId: data.sessionId || (data.data as ChatMessage | undefined)?.sessionId,
        assistantMessageId: data.assistantMessageId,
        query: (data.data as ChatMessage | undefined)?.query,
        requestId: (data.data as ChatMessage | undefined)?.requestId,
      })

      let existingMessage = replay || findLastMessage(
        (item) =>
          item.role === 'assistant' &&
          (item.id === data.id || item.requestId === data.id),
      )
      const created = !existingMessage
      if (!existingMessage) {
        const assistantId = data.assistantMessageId as string | undefined
        existingMessage = {
          id: assistantId || data.id,
          assistantMessageId: assistantId,
          requestId: data.id,
          role: 'assistant',
          content: '',
          isAgentMode: true,
          isRagMode: !isAgentStreamSession(),
          completed: false,
          agentEventStream: [],
          _eventMap: new Map(),
          _pendingToolCalls: new Map(),
          knowledgeReferences: [],
        }
        messagesList.push(existingMessage)
        emitMessageCreated(existingMessage)
        loading.value = false
        scrollToBottom(true)
        log('[Agent Query] Created agent placeholder message')
      } else {
        ensureAgentMessageShell(existingMessage, data.id as string | undefined)
        if (data.assistantMessageId) {
          existingMessage.id = data.assistantMessageId as string
          existingMessage.assistantMessageId = data.assistantMessageId
        }
        log('[Agent Query] Continuing stream for existing message')
      }
      bindServerTurnTimestamps(
        messagesList,
        (data.data as Record<string, unknown> | undefined) || data,
        existingMessage,
      )
      onAgentQuery?.(data, existingMessage, created)
      return
    }

    const isAgentOnlyResponse =
      data.responseType === 'thinking' ||
      data.responseType === 'toolCall' ||
      data.responseType === 'toolResult' ||
      data.responseType === 'commandOutput' ||
      data.responseType === 'reflection' ||
      data.responseType === 'contextCompacted' ||
      data.responseType === 'userMessageInjected'

    const activeAssistant = getTrailingIncompleteAssistant()
    const isCurrentlyAgentMode = activeAssistant?.isAgentMode === true
    const targetsActiveAgentRequest =
      isAgentStreamSession() &&
      !!data.id &&
      (data.id === currentAssistantMessageId.value ||
        activeAssistant?.requestId === data.id ||
        activeAssistant?.id === data.id)
    const isAgentAnswerChunk =
      data.responseType === 'answer' && (isAgentStreamSession() || targetsActiveAgentRequest)
    const isAgentCompleteChunk =
      data.responseType === 'complete' && (isAgentStreamSession() || targetsActiveAgentRequest)

    const shouldHandleAsAgent =
      isAgentOnlyResponse ||
      isCurrentlyAgentMode ||
      isAgentAnswerChunk ||
      isAgentCompleteChunk

    if (data.responseType === 'references') {
      applyKnowledgeReferences(data)
      scrollToBottom()
      return
    }

    if (data.responseType === 'memoryRecalled') {
      applyUsedMemories(data)
      return
    }

    if (shouldHandleAsAgent) {
      handleAgentChunk(data)
      if (data.responseType === 'stop') {
        log('[Stop Event] Generation stopped')
        const stoppedMessage = resolveActiveAssistantMessage(data)
        if (stoppedMessage) markAssistantStopped(stoppedMessage)
        if (data.id) replaySegments.delete(String(data.id))
        loading.value = false
        isReplying.value = false
        currentAssistantMessageId.value = ''
        onGenerationStopped?.()
      }
      return
    }

    if (data.responseType === 'stop') {
      log('[Stop Event] Non-agent generation stopped')
      const stoppedMessage = findLastMessage((item) => {
        if (item.role !== 'assistant') return false
        if (item.requestId === data.id) return true
        return item.id === data.id
      })
      if (stoppedMessage) stoppedMessage.completed = true
      loading.value = false
      isReplying.value = false
      fullContent.value = ''
      currentAssistantMessageId.value = ''
      onGenerationStopped?.()
      return
    }

    // 业务错误帧：绝不能当正文渲染（此前 content 会直接进 fullContent，用户看到的是
    // 一段"像回答的错误文案"，既没有失败态也没有原因提示）。
    if (data.responseType === 'error') {
      const errorMsg = String(data.content || t('chat.processError'))
      const failedMessage = findLastMessage((item) => {
        if (item.role !== 'assistant') return false
        if (item.requestId === data.id) return true
        return item.id === data.id
      })
      if (failedMessage) {
        failedMessage.content = ''
        failedMessage.error = errorMsg
        failedMessage.completed = true
        failedMessage.thinking = false
      }
      loading.value = false
      isReplying.value = false
      fullContent.value = ''
      currentAssistantMessageId.value = ''
      reportError(errorMsg)
      console.error('[Chat Error]', errorMsg)
      return
    }

    const existingMessage = findLastMessage((item) => {
      if (item.role !== 'assistant') return false
      if (item.requestId === data.id) return true
      return item.id === data.id
    })
    if (existingMessage?.completed && data.done && !data.content) {
      log('[Non-Agent] Ignoring duplicate completion event for completed message')
      return
    }

    fullContent.value += (data.content as string) || ''
    const obj: ChatMessage = {
      ...data,
      content: '',
      role: 'assistant',
      showThink: false,
      completed: false,
    }

    if ((data.data as ChatMessage | undefined)?.isFallback) obj.fallback = true

    const thinkCloseTag = '</think>'
    if (fullContent.value.includes('<think>') && !fullContent.value.includes(thinkCloseTag)) {
      obj.thinking = true
      obj.showThink = true
      obj.content = ''
      obj.thinkContent = fullContent.value.replace('<think>', '').trim()
    } else if (fullContent.value.includes('<think>') && fullContent.value.includes(thinkCloseTag)) {
      obj.thinking = false
      obj.showThink = true
      const index = fullContent.value.lastIndexOf(thinkCloseTag)
      obj.thinkContent = fullContent.value.substring(0, index).replace('<think>', '').trim()
      obj.content = fullContent.value.substring(index + thinkCloseTag.length).trim()
    } else {
      obj.content = fullContent.value
    }

    if (!existingMessage) loading.value = false

    if (data.done) {
      obj.completed = true
      onReplyComplete?.(String(obj.content || ''))
      isReplying.value = false
      fullContent.value = ''
      currentAssistantMessageId.value = ''
    }
    updateAssistantSession(obj)
    if (data.done) {
      const finishedMessage = resolveActiveAssistantMessage(data) || obj
      onTurnComplete?.(finishedMessage)
    }
  }

  return {
    findLastMessage,
    shouldRenderAssistantMessage,
    shouldShowGlobalTypingIndicator,
    applyCompletedSnapshot,
    handleMsgList,
    processStreamChunk,
    prepareForNewOutgoingMessage,
    markInFlightAssistantStopped,
  }
}
