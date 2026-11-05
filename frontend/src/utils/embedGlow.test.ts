import { test } from 'node:test'
import assert from 'node:assert/strict'

import { glowAlphaFor, glowVars, relativeLuminance } from './embedGlow'

test('深色品牌色压到最淡浓度', () => {
  for (const color of ['#000000', '#000', '#1a1a1a', '#333333']) {
    assert.equal(glowAlphaFor(color), 12, `${color} 应该走深色档`)
    assert.deepEqual(glowVars(color), { '--embed-glow-alpha': '12%' })
  }
})

test('中间色用 18%，浅色用 24%，且都不加弧线', () => {
  assert.equal(glowAlphaFor('#28b9bf'), 18, '品牌青属于中间档')
  assert.equal(glowAlphaFor('#ffffff'), 24)
  assert.equal(glowAlphaFor('#f5f5f5'), 24)
  assert.deepEqual(glowVars('#ffffff'), { '--embed-glow-alpha': '24%' })
})

test('非法或缺失颜色回落到中间档，不产出变量', () => {
  assert.equal(relativeLuminance('teal'), null)
  assert.equal(glowAlphaFor('teal'), 18)
  assert.equal(glowAlphaFor(''), 18)
  assert.equal(glowAlphaFor(undefined), 18)
  assert.deepEqual(glowVars(undefined), {})
})

test('三位十六进制与六位等价', () => {
  assert.equal(glowAlphaFor('#000'), glowAlphaFor('#000000'))
  assert.equal(glowAlphaFor('#fff'), glowAlphaFor('#ffffff'))
})
