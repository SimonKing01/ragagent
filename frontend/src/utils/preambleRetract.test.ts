import assert from 'node:assert/strict'
import test from 'node:test'

import { preambleRetractDelayMs } from './preambleRetract.ts'

test('preambleRetractDelayMs: 延迟随前言字数增长，且不低于下限', () => {
  // 极短前言也停留一会儿（下限 800ms）
  assert.equal(preambleRetractDelayMs(0), 800)
  assert.equal(preambleRetractDelayMs(5), 800)
  // 排水窗口内（<= 100 字）：0.42s + 250ms 余量，仍取 800 下限
  assert.equal(preambleRetractDelayMs(100), 800)
  // 超出排水窗口：按 240 字/秒推算（300 字 ≈ 1.25s + 0.25s）
  assert.equal(preambleRetractDelayMs(300), 1500)
  assert.equal(preambleRetractDelayMs(720), 3250)
  // 非法输入按 0 处理
  assert.equal(preambleRetractDelayMs(-10), 800)
  assert.equal(preambleRetractDelayMs(Number.NaN), 800)
})

test('preambleRetractDelayMs: 单调不减（字数越多停留越久）', () => {
  let previous = 0
  for (const chars of [0, 50, 120, 240, 480, 960]) {
    const delay = preambleRetractDelayMs(chars)
    assert.ok(delay >= previous, `${chars} 字的延迟 (${delay}) 不应小于更短前言 (${previous})`)
    previous = delay
  }
})
