/**
 * highlight.js 的按需加载入口。
 *
 * 它原先在 `markdownEnhancements` / `mermaidShared` 里被静态 import，于是跟着聊天分块
 * 一起进首屏（生产 351 KB gzip），而代码高亮其实只在这轮回答里出现 ``` 围栏时才需要。
 *
 * 现在改成动态 import：未就绪时代码块按纯文本渲染（可读、可复制），加载完成后再调用
 * `highlightCodeBlocksInContainer` 就地补色，不触发重排。
 */

type Hljs = typeof import('highlight.js').default

let hljs: Hljs | null = null
let loading: Promise<void> | null = null

/** 已加载的实例；未加载时返回 null（调用方据此降级渲染）。 */
export function getHljs(): Hljs | null {
  return hljs
}

/** 加载 highlight.js（幂等）；失败时清空 promise 以便下次重试。 */
export function loadHighlight(): Promise<void> {
  if (hljs) return Promise.resolve()
  if (!loading) {
    loading = import('highlight.js')
      .then((mod) => {
        hljs = mod.default
        // mermaid 源码块按 plaintext 高亮；注册必须在按需加载完成之后，不得回到模块顶层
        hljs.registerAliases('mermaid', { languageName: 'plaintext' })
      })
      .catch((err) => {
        loading = null
        throw err
      })
  }
  return loading
}
