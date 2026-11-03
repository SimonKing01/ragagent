import { loadHighlight } from '@/utils/highlightRuntime'
import { ensureMermaidInitialized } from '@/utils/mermaidShared'

type NetworkInformationLike = {
  saveData?: boolean
  effectiveType?: string
}

/** 省流模式或明显弱网时放弃预热：把带宽留给首屏与正文。 */
function canPrefetch(): boolean {
  const nav = navigator as Navigator & { connection?: NetworkInformationLike }
  const conn = nav.connection
  if (conn?.saveData) return false
  if (conn?.effectiveType && /(^|-)2g$/.test(conn.effectiveType)) return false
  return true
}

/**
 * 预取「回答渲染」才用到的重资源：mermaid（770 KB gzip）与 highlight.js（351 KB gzip）。
 *
 * 时机由调用方决定——嵌入面板在**打开后空闲**时调用一次：不抢首屏带宽，
 * 而答案里真的出现图表/代码块时通常已经就绪；未就绪也有降级渲染兜底
 * （代码块纯文本、图表占位后替换）。
 */
function prefetchAnswerRendering(): void {
  if (!canPrefetch()) return
  void loadHighlight().catch(() => {
    /* 预取失败不影响主流程，用到时会重试 */
  })
  ensureMermaidInitialized()
}

/** 面板打开后调度一次空闲预热（无 requestIdleCallback 时退化为 1.5s 定时）。 */
export function scheduleAnswerRenderPrefetch(): void {
  const idle = (window as Window & { requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number })
    .requestIdleCallback
  if (typeof idle === 'function') {
    idle(() => prefetchAnswerRendering(), { timeout: 4000 })
    return
  }
  window.setTimeout(() => prefetchAnswerRendering(), 1500)
}
