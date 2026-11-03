import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

/**
 * 源码扫描守卫：widget 对外暴露的全局对象名。
 *
 * 背景：演示页与接入说明写的是「全局 `RagAgent` 提供 init/open/…」，而脚本实际导出的
 * 仍是上游沿用的 `window.WeKnora` ⇒ 集成方照文档写 `RagAgent.init()` 会直接 TypeError。
 * widget 是独立发布物（纯 JS，不在 vue-tsc / 单测构建面内），这类漂移只能靠扫描源码钉住。
 *
 * 注意范围：这里只钉「对外全局名」。localStorage 键（WeKnora_theme 等）、postMessage
 * 协议常量（weknora-host / weknora-embed）、宿主骨架 CSS 类仍沿用旧前缀，不在本守卫内。
 */
const widgetSource = readFileSync(
  new URL('../../../public/ragagent-widget.js', import.meta.url),
  'utf8',
)

test('widget 对外暴露全局名 RagAgent', () => {
  assert.match(widgetSource, /global\.RagAgent = api;/)
})

test('widget 不再挂 WeKnora 旧全局名（改名不保留别名）', () => {
  assert.doesNotMatch(widgetSource, /\bWeKnora\b/)
})
