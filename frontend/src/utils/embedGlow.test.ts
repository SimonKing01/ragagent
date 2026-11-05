import { test } from 'node:test'
import assert from 'node:assert/strict'

import { glowAlphaFor, glowNeedsArc, glowVars, relativeLuminance } from './embedGlow'

test('深色品牌色压到最淡浓度并打开细弧线', () => {
  for (const color of ['#000000', '#000', '#1a1a1a', '#333333']) {
    assert.equal(glowAlphaFor(color), 7, `${color} 应该走深色档`)
    assert.equal(glowNeedsArc(color), true, `${color} 需要细弧线补偿`)
    assert.deepEqual(glowVars(color), {
      '--embed-glow-alpha': '7%',
      '--embed-glow-arc': '1',
    })
  }
})

test('中间色用 13%，浅色用 18%，且都不加弧线', () => {
  assert.equal(glowAlphaFor('#28b9bf'), 13, '品牌青属于中间档')
  assert.equal(glowAlphaFor('#ffffff'), 18)
  assert.equal(glowAlphaFor('#f5f5f5'), 18)
  assert.equal(glowNeedsArc('#28b9bf'), false)
  assert.deepEqual(glowVars('#ffffff'), {
    '--embed-glow-alpha': '18%',
    '--embed-glow-arc': '0',
  })
})

test('非法或缺失颜色回落到中间档，不产出变量', () => {
  assert.equal(relativeLuminance('teal'), null)
  assert.equal(glowAlphaFor('teal'), 13)
  assert.equal(glowAlphaFor(''), 13)
  assert.equal(glowAlphaFor(undefined), 13)
  assert.equal(glowNeedsArc(null), false)
  assert.deepEqual(glowVars(undefined), {})
})

test('三位十六进制与六位等价', () => {
  assert.equal(glowAlphaFor('#000'), glowAlphaFor('#000000'))
  assert.equal(glowAlphaFor('#fff'), glowAlphaFor('#ffffff'))
})
