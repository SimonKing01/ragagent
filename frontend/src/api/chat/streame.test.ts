import assert from 'node:assert/strict'
import { StorageKeys } from '../../utils/storageKeys'
import { after, before, mock, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer, type ViteDevServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

let server: ViteDevServer
let useStream: typeof import('./streame').useStream
let transport: { requests: Array<{ url: string; options: any }> }
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
before(async () => {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => key === StorageKeys.token ? 'test-token' : null,
  } })
  const mocks: Record<string, string> = {
    // 建立连接后立刻回调 onopen（真实 fetchEventSource 也是拿到响应头就回调），
    // 这样测试才能把 options 里的回调当"服务端"来驱动。
    '@microsoft/fetch-event-source': `export const requests = [];
      export async function fetchEventSource(url, options) {
        requests.push({ url, options });
        options.onopen?.({ ok: true, status: 200 });
      }`,
    '@/utils/index': `export const generateRandomString = () => 'test-request';`,
    '@/utils/activeI18n': `export const tText = key => key; export const activeLocale = () => 'zh-CN';`,
  }
  server = await createServer({
    configFile: false,
    plugins: [{ name: 'stream-request-test', enforce: 'pre',
      resolveId(id) { if (id.startsWith('\0mock:')) return id },
      load(id) { if (id.startsWith('\0mock:')) return mocks[id.slice(6)] },
    }],
    optimizeDeps: { noDiscovery: true, entries: [] },
    resolve: { alias: [
      ...Object.keys(mocks).map(find => ({ find, replacement: '\0mock:' + find })),
      { find: '@', replacement: fileURLToPath(new URL('../../', import.meta.url)) },
    ] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
  })
  ;({ useStream } = await server.ssrLoadModule('/src/api/chat/streame.ts'))
  transport = await server.ssrLoadModule('\0mock:@microsoft/fetch-event-source') as typeof transport
})
after(async () => {
  await server?.close()
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
})

test('SSE HTTP body preserves web search selection alongside other tools', async () => {
  let stream!: ReturnType<typeof useStream>
  await renderToString(createSSRApp({ setup() { stream = useStream(); return () => null } }))
  try {
    await stream.startStream({
      sessionId: 'new-session', query: '查一下腾讯股价', method: 'POST', url: '/api/v1/agent-chat',
      agentEnabled: true, webSearchEnabled: true,
      mcpServiceIds: ['mcp-1'], skillNames: ['report'],
    })
    assert.equal(stream.error.value, null)
    const request = transport.requests.at(-1)!
    assert.equal(request.url, '/api/v1/agent-chat/new-session')
    const body = JSON.parse(request.options.body)
    assert.equal(Object.hasOwn(body, 'local_browser_enabled'), false)
    // 请求体键＝服务端字段名（§14.9l S4），旧 snake 键不再出现
    assert.equal(body.webSearchEnabled, true)
    assert.equal(Object.hasOwn(body, 'web_search_enabled'), false)
    assert.deepEqual(body.mcpServiceIds, ['mcp-1'])
    assert.deepEqual(body.skillNames, ['report'])
    assert.equal(stream.lastStreamRequest.value?.body?.webSearchEnabled, true)
  } finally { stream.stopStream() }
})

test('终态 error 帧（done=true）让流主动收束', async () => {
  let stream!: ReturnType<typeof useStream>
  await renderToString(createSSRApp({ setup() { stream = useStream(); return () => null } }))
  const chunks: unknown[] = []
  stream.onChunk(chunk => chunks.push(chunk))
  try {
    await stream.startStream({ sessionId: 's1', query: 'q', method: 'POST', url: '/api/v1/chat' })
    const request = transport.requests.at(-1)!

    request.options.onmessage({ data: JSON.stringify({ responseType: 'error', done: true, content: '模型调用失败' }) })

    assert.equal(chunks.length, 1, '错误帧仍要交给上层渲染失败态')
    assert.equal(stream.isStreaming.value, false, '收束后不应再显示"正在生成"')
    assert.equal(request.options.signal.aborted, true, '终态错误必须中止连接')
  } finally { stream.stopStream() }
})

test('工具失败帧（done=false）不得断流，后续帧继续处理', async () => {
  let stream!: ReturnType<typeof useStream>
  await renderToString(createSSRApp({ setup() { stream = useStream(); return () => null } }))
  const chunks: any[] = []
  stream.onChunk(chunk => chunks.push(chunk))
  try {
    await stream.startStream({ sessionId: 's3', query: 'q', method: 'POST', url: '/api/v1/chat' })
    const request = transport.requests.at(-1)!

    // 服务端把工具调用失败也编码成 responseType=error，但 done=false、带 toolName
    request.options.onmessage({ data: JSON.stringify({
      responseType: 'error',
      done: false,
      content: 'MCP tool is no longer available or enabled',
      data: { toolName: 'call_mcp_tool', toolCallId: 'c1', success: false },
    }) })
    assert.equal(stream.isStreaming.value, true, '工具级失败后智能体还要继续跑，不能收束')
    assert.equal(request.options.signal.aborted, false, '不得中止连接')

    // 后续步骤与最终回答必须还能收到
    request.options.onmessage({ data: JSON.stringify({ responseType: 'answer', content: '最终回答' }) })
    assert.equal(chunks.length, 2)
    assert.equal(chunks[1].content, '最终回答')
  } finally { stream.stopStream() }
})

test('长时间没有帧：超时收束并给出可读原因', async () => {
  let stream!: ReturnType<typeof useStream>
  await renderToString(createSSRApp({ setup() { stream = useStream(); return () => null } }))
  mock.timers.enable({ apis: ['setTimeout'] })
  try {
    await stream.startStream({ sessionId: 's2', query: 'q', method: 'POST', url: '/api/v1/chat' })
    const request = transport.requests.at(-1)!

    mock.timers.tick(120_001)

    assert.equal(stream.error.value, 'error.streamTimeout')
    assert.equal(stream.isStreaming.value, false)
    assert.equal(request.options.signal.aborted, true)
  } finally {
    mock.timers.reset()
    stream.stopStream()
  }
})
