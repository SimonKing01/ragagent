import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { EMBED_MESSAGES, SUPPORTED_LOCALES } from '../../i18n/embed'

// 回归守卫：embed 历史会话（懒创建 + 抽屉）。
// 两条实锤约束：
//   ① 会话必须**懒创建**——打开即建空会话会让 sessions 表随访客数线性膨胀；
//      发送首条消息前才落库（useEmbedBridge.ensureSession 由 sendMsg 触发）。
//   ② 抽屉分区复用首页同一套纯函数（sessionGrouping），且五个语言的文案齐全
//      （embed 是独立 i18n 平面，缺键会静默回落到英文）。

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', '..')

test('embed 会话懒创建：不再打开即建、发送前确保、切换不串流', () => {
  const bridge = readFileSync(join(SRC, 'composables/useEmbedBridge.ts'), 'utf8')
  assert.doesNotMatch(
    bridge,
    /} else \{\s*const sessionRes = await createEmbedSession/,
    'bootstrap 不得在无存量会话时自动创建——空会话会随访客数膨胀（懒创建是硬约束）',
  )
  assert.match(bridge, /const ensureSession = async \(\): Promise<boolean>/, '必须导出 ensureSession')
  assert.match(bridge, /ensureSession,\s*\n\s*selectSession,/, '桥接层必须导出 ensureSession 与 selectSession')
  assert.match(
    bridge,
    /createEmbedSession\(id, apiToken, visitorId\.value\)/,
    '懒创建必须带着访客 id 落库（历史列表按访客聚合的前提）',
  )

  const session = readFileSync(join(SRC, 'composables/useEmbedChatSession.ts'), 'utf8')
  const sendMsgIndex = session.indexOf('const sendMsg = async')
  const ensureIndex = session.indexOf('await options.ensureSession()', sendMsgIndex)
  const pushIndex = session.indexOf('messagesList.push', sendMsgIndex)
  assert.ok(sendMsgIndex > -1 && ensureIndex > -1, 'sendMsg 必须调用 ensureSession（懒创建入口）')
  assert.ok(
    ensureIndex < pushIndex,
    'ensureSession 必须在写入本地用户消息之前——创建失败时不留下半成品消息',
  )
  const resetIndex = session.indexOf('const resetAndLoad = (sid: string)')
  const resetBody = session.slice(resetIndex, session.indexOf('getmsgList({ sessionId: sid', resetIndex))
  assert.match(
    resetBody,
    /stopStream\(\)/,
    '切换会话（resetAndLoad）必须先停旧流，否则上一会话的 chunk 会写进新会话列表',
  )
  assert.match(
    resetBody,
    /if \(sending && \(sendingSessionId === '' \|\| sid === sendingSessionId\)\)/,
    '首条消息建会话时 sessionId 变化不得触发重置：那次 stopStream + 清空列表会与正在跑的这一轮竞争',
  )
  assert.match(
    resetBody,
    /if \(sending[\s\S]{0,240}hasMoreHistory\.value = false/,
    '让路时要把 hasMoreHistory 关掉：新会话没有更早历史，否则滚到顶会再拉一次最新消息造成重复',
  )
  assert.match(
    session,
    /const sendMsg = async[\s\S]*?await submitMessage\(value, opts\)/,
    '发送入口必须包住 submitMessage（发送期间屏蔽历史重置）',
  )
  const handler = readFileSync(join(SRC, 'composables/useChatStreamHandler.ts'), 'utf8')
  assert.match(
    handler,
    /mergeOptimisticUserDuplicate\(messagesList, String\(data\.id\)\)/,
    'agentQuery 到达时必须合并历史回灌的同一轮用户消息（否则同一句话显示两遍）',
  )

  const page = readFileSync(join(SRC, 'views/embed/EmbedPage.vue'), 'utf8')
  assert.doesNotMatch(
    page,
    /<header v-if="sessionId"/,
    '页头（含历史入口）不得依赖 sessionId——懒创建下未发言时也要能打开历史抽屉',
  )
  assert.match(page, /EmbedHistoryDrawer/, '页面必须挂载历史会话抽屉')
})

test('embed 历史文案：五个语言键齐全且非空', () => {
  const flatKeys = [
    'historyTitle',
    'historyEmpty',
    'historyCurrent',
    'historySwitchTitle',
    'historySwitchBody',
    'historySwitchOk',
    'historyLoadFailed',
  ]
  const timeKeys = ['today', 'yesterday', 'last7Days', 'last30Days', 'lastYear', 'earlier']

  for (const locale of SUPPORTED_LOCALES) {
    const bag = (EMBED_MESSAGES[locale] as { embedPublish?: Record<string, unknown> }).embedPublish
    assert.ok(bag, `${locale}: 缺 embedPublish`)
    for (const key of flatKeys) {
      const value: unknown = bag[key]
      assert.equal(typeof value, 'string', `${locale}: embedPublish.${key} 缺失`)
      assert.notEqual(String(value).trim(), '', `${locale}: embedPublish.${key} 不得为空`)
    }
    const time = bag.historyTime as Record<string, unknown> | undefined
    assert.ok(time, `${locale}: 缺 embedPublish.historyTime`)
    for (const key of timeKeys) {
      assert.equal(typeof time[key], 'string', `${locale}: embedPublish.historyTime.${key} 缺失`)
      assert.notEqual(String(time[key]).trim(), '', `${locale}: embedPublish.historyTime.${key} 不得为空`)
    }
  }
})

test('embed 抽屉分区复用首页纯函数', () => {
  const drawer = readFileSync(join(SRC, 'views/embed/EmbedHistoryDrawer.vue'), 'utf8')
  assert.match(
    drawer,
    /from '@\/components\/sessionGrouping'/,
    '抽屉必须复用首页 sessionGrouping（不重建分区逻辑）',
  )
  assert.match(drawer, /groupSessionsByDate\(/, '抽屉要用 groupSessionsByDate 按时间分区')
  assert.match(drawer, /classifyDateBucket\(item\.updatedAt\)/, '分区键取最后活动时间')
})
