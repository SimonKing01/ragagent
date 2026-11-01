import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { resetSteerTurnForReplay } from '../utils/steerStreamFork.ts'

const source = readFileSync(new URL('./useChatStreamHandler.ts', import.meta.url), 'utf8')

test('command output updates only its pending tool and cannot replace a final result', () => {
  const start = source.indexOf("case 'commandOutput': {")
  const block = source.slice(start, source.indexOf("case 'toolResult':", start))
  const command = { type: 'toolCall', toolName: 'shell_exec', toolCallId: 'a', pending: true }
  const other = { type: 'toolCall', toolName: 'shell_exec', toolCallId: 'b', pending: true }
  const message = { agentEventStream: [command, other] }
  const process = vm.runInNewContext(ts.transpile(`(dataPayload) => { switch ('commandOutput') { ${block} } }`), { message })
  process({ toolCallId: 'a', output: 'Reading CSV', done: false })
  assert.equal(command.commandOutput.output, 'Reading CSV')
  assert.equal(command.pending, true)
  assert.equal(other.commandOutput, undefined)
  process({ toolCallId: 'missing', output: 'unmatched' })
  assert.equal(message.agentEventStream.length, 2)
  process({ toolCallId: 'a', output: 'Finished', done: true })
  process({ toolCallId: 'a', output: 'late chunk', done: false })
  assert.equal(command.commandOutput.output, 'Finished')
  command.pending = false
  command.output = 'Final tool result'
  process({ toolCallId: 'a', output: 'more late output', done: false })
  assert.equal(command.output, 'Final tool result')
})

test('replaying agentQuery binds the first segment and preserves distinct row IDs', () => {
  const messagesList = [
    { id: 'a', role: 'assistant', requestId: 'r', steerForked: true, completed: true },
    { id: 'u', role: 'user', requestId: 'r' },
    { id: 'a:steer:1', assistantMessageId: 'a', role: 'assistant', requestId: 'r', completed: false },
  ]
  const start = source.indexOf("if (data.responseType === 'agentQuery')")
  const block = source.slice(start, source.indexOf('const isAgentOnlyResponse', start))
  const replaySegments = new Map()
  const process = vm.runInNewContext(ts.transpile(`(data) => { ${block} }`), {
    messagesList, replaySegments, resetSteerTurnForReplay,
    currentAssistantMessageId: { value: 'a' },
    getTrailingIncompleteAssistant: () => [...messagesList].reverse().find(m => m.role === 'assistant' && !m.completed),
    findLastMessage: fn => [...messagesList].reverse().find(fn),
    log() {}, ensureAgentMessageShell() {}, bindServerTurnTimestamps() {}, onAgentQuery() {},
    mergeOptimisticUserDuplicate() {},
  })
  process({ responseType: 'agentQuery', id: 'r', assistantMessageId: 'a' })
  assert.deepEqual(messagesList.map(m => m.id), ['a', 'u', 'a:steer:1'])
  assert.equal(replaySegments.get('r'), messagesList[0])
})

test('历史回灌的同一轮用户消息与本地乐观那条合并（不重复显示）', () => {
  const start = source.indexOf('const mergeOptimisticUserDuplicate =')
  const block = source.slice(start, source.indexOf('\n  const processStreamChunk', start))
  const merge = vm.runInNewContext(ts.transpile(`${block}\nmergeOptimisticUserDuplicate`), {})

  // 历史里已有本轮服务端行（带 id/requestId）+ 本地乐观那条（无 id）：只留服务端行
  const list = [
    { role: 'user', content: '同一个问题', id: 'u1', requestId: 'r1' },
    { role: 'assistant', content: '', requestId: 'r1', completed: false },
    { role: 'user', content: '同一个问题' },
  ]
  merge(list, 'r1')
  assert.equal(list.filter((m) => m.role === 'user').length, 1)
  assert.equal(list[0].id, 'u1')

  // 内容不同的消息不会被合并（本轮的乐观消息尚未回灌时保持原样）
  const other = [
    { role: 'user', content: '旧问题', id: 'u2', requestId: 'r2' },
    { role: 'user', content: '新问题' },
    { role: 'user', content: '新问题' },
  ]
  merge(other, 'r2')
  assert.equal(other.length, 3)
})

test('failed tool results keep stdout/output instead of replacing it with the short error', () => {
  assert.match(source, /toolCallEvent\.output = dataPayload\.output \|\| data\.content/)
  assert.doesNotMatch(
    source,
    /toolCallEvent\.output = success\s*\?[\s\S]*dataPayload\.error/,
  )
})

test('later toolCall events merge arguments onto the same pending card', () => {
  assert.match(source, /function mergeToolCallArguments/)
  assert.match(source, /toolCallEvent\.arguments = mergeToolCallArguments\(toolCallEvent\.arguments, incomingArguments\)/)
})

test('agent chunks bind only to the in-flight assistant after a queued user message', () => {
  const chunkStart = source.indexOf('const handleAgentChunk = ')
  const chunkEnd = source.indexOf('const processStreamChunk = ', chunkStart)
  const chunk = source.slice(chunkStart, chunkEnd)
  assert.notEqual(chunkStart, -1)
  assert.notEqual(chunkEnd, -1)
  assert.match(chunk, /resolveActiveAssistantMessage\(data\)/)
  assert.doesNotMatch(chunk, /item\.requestId === dataId \|\| item\.id === dataId/)
})

test('incomplete assistant is found even when a later user message is the list tail', () => {
  const fnStart = source.indexOf('const getTrailingIncompleteAssistant = ')
  const fnEnd = source.indexOf('const markAssistantStopped = ', fnStart)
  const fn = source.slice(fnStart, fnEnd)
  assert.notEqual(fnStart, -1)
  assert.notEqual(fnEnd, -1)
  assert.match(fn, /item\?\.role === 'assistant'/)
  assert.doesNotMatch(fn, /const last = messagesList\[messagesList\.length - 1\]/)
})

test('global typing indicator stays hidden while an in-flight agent assistant exists', () => {
  const fnStart = source.indexOf('const shouldShowGlobalTypingIndicator = ')
  const fnEnd = source.indexOf('const restoreQuickAnswerFlags = ', fnStart)
  const fn = source.slice(fnStart, fnEnd)
  assert.notEqual(fnStart, -1)
  assert.notEqual(fnEnd, -1)
  assert.match(fn, /messages\.some\(/)
  assert.match(fn, /role === 'assistant'/)
  assert.match(fn, /isAgentMode/)
  assert.match(fn, /!m\.completed/)
})

// Splitting a turn around an injected message can leave one segment holding
// the answer text and no timeline events. Hiding that row loses the reply.
test('completed agent messages with content but no events still render', () => {
  const fnStart = source.indexOf('const shouldRenderAssistantMessage = ')
  const fnEnd = source.indexOf('const shouldShowGlobalTypingIndicator = ', fnStart)
  const fn = source.slice(fnStart, fnEnd)
  assert.notEqual(fnStart, -1)
  assert.notEqual(fnEnd, -1)
  assert.match(fn, /session\.content/)
})

test('injected user messages fork a continuation assistant below the bubble', () => {
  const chunkStart = source.indexOf("case 'userMessageInjected'")
  const chunkEnd = source.indexOf("case 'complete'", chunkStart)
  const chunk = source.slice(chunkStart, chunkEnd)
  assert.notEqual(chunkStart, -1)
  assert.notEqual(chunkEnd, -1)
  assert.match(chunk, /forkAfterInjectedUser/)
  assert.match(chunk, /onUserMessageInjected/)
  assert.match(source, /expandSteerForksInHistory\(processed\)/)
  assert.match(source, /expandSteerForksInHistory\(\[\.\.\.messagesList\]\)/)
})

test('agent answer.done does not mark the session idle', () => {
  const chunkStart = source.indexOf("case 'answer':")
  const chunkEnd = source.indexOf("case 'userMessageInjected'", chunkStart)
  assert.notEqual(chunkStart, -1)
  assert.notEqual(chunkEnd, -1)
  const chunk = source.slice(chunkStart, chunkEnd)
  assert.match(chunk, /if \(!isAgentStreamSession\(\)\)/)
  assert.ok(
    chunk.indexOf('isAgentStreamSession') < chunk.indexOf('isReplying.value = false'),
    'agent turns must wait for complete before clearing isReplying',
  )
})

// continue-stream replays the event log from the start, so after a refresh
// this event arrives for a message history has already loaded. Synthesizing a
// bubble unconditionally puts the same message on screen twice.
test('a replayed injection reuses the persisted row instead of duplicating it', () => {
  const chunkStart = source.indexOf("case 'userMessageInjected'")
  const chunkEnd = source.indexOf("case 'complete'", chunkStart)
  const chunk = source.slice(chunkStart, chunkEnd)
  assert.notEqual(chunkStart, -1)
  assert.notEqual(chunkEnd, -1)

  assert.match(chunk, /messagesList\.find\(/)
  assert.match(chunk, /item\.id === injectedId/)
  assert.match(chunk, /alreadyInList/)
  assert.match(chunk, /if \(!alreadyInList\) emitMessageCreated\(injectedUser\)/)
})

// Remote stop used to mark the assistant done and leave the overlay chips
// in place. The parent owns steerQueue, so the stream handler has to say
// the generation stopped.
test('agent and non-agent stop notify onGenerationStopped', () => {
  assert.match(source, /onGenerationStopped\?: \(\) => void/)

  const agent = source.indexOf("log('[Stop Event] Generation stopped')")
  assert.notEqual(agent, -1)
  assert.match(source.slice(agent, agent + 500), /onGenerationStopped\?\.\(\)/)

  const nonAgent = source.indexOf("log('[Stop Event] Non-agent generation stopped')")
  assert.notEqual(nonAgent, -1)
  assert.match(source.slice(nonAgent, nonAgent + 500), /onGenerationStopped\?\.\(\)/)
})

const streamSource = readFileSync(new URL('../api/chat/streame.ts', import.meta.url), 'utf8')
const chatViewSource = readFileSync(new URL('../views/chat/index.vue', import.meta.url), 'utf8')

test('流意外结束时通知上层兜底（主动停止不通知）', () => {
  assert.match(
    streamSource,
    /onclose: \(\) => \{[\s\S]{0,240}notifyClosed\('closed'\)/,
    'onclose 必须把“连接结束”交给上层，否则消息会永远停在未完成',
  )
  assert.match(streamSource, /onerror: \(err\) => \{[\s\S]{0,240}notifyClosed\('error'\)/)
  assert.match(streamSource, /const stopStream = \(\) => \{[\s\S]{0,120}intentionalStop = true/)
  assert.match(streamSource, /if \(intentionalStop\) return/, '用户主动停止不得触发兜底')
})

test('聊天页把断流接到恢复流程，并在拿不到完成态时收束消息', () => {
  assert.match(chatViewSource, /onClosed: \(\) => \{\s*void recoverInterruptedTurn\(\)/)
  assert.match(chatViewSource, /runStreamRecovery\(\{/)
  assert.match(chatViewSource, /\.error = t\('chat\.connectionLost'\)/)
  assert.match(chatViewSource, /applySnapshot: \(pending, row\) => applyCompletedSnapshot\(/)
  assert.match(chatViewSource, /if \(!isReplying\.value && !loading\.value\) return/, '没有在飞的回合时不得触发补拉')
})

test('工具失败帧（done=false）不得断流：只有终态错误才收束', () => {
  assert.match(
    streamSource,
    /if \(shouldStopStreamOnErrorFrame\(parsed\)\) \{\s*stopStream\(\)/,
    '错误帧必须先经 shouldStopStreamOnErrorFrame 判定',
  )
  assert.doesNotMatch(
    streamSource,
    /if \(parsed\?\.responseType === 'error'\) \{\s*stopStream\(\)/,
    '不能见 error 就断流——工具级失败后智能体还要继续重试并收尾',
  )
  assert.match(streamSource, /import \{ shouldStopStreamOnErrorFrame \} from '@\/utils\/streamErrorFrame'/)
})
