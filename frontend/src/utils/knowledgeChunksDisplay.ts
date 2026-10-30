import type { ComposerTranslation } from 'vue-i18n'

import type { KnowledgeChunksListData } from '@/types/tool-results'

/**
 * 工具卡片摘要：本次读取的块区间（不是页码，也不是累计）。
 * startChunkIndex 由后端下推；旧数据没有该字段时按 page/pageSize 反推。
 */
export function getKnowledgeChunksSummaryHtml(
  t: ComposerTranslation,
  toolData: KnowledgeChunksListData | null | undefined,
): string {
  if (!toolData || toolData.fetchedChunks === undefined) {
    return ''
  }

  const fetched = Number(toolData.fetchedChunks ?? 0)
  const total = `<strong>${toolData.totalChunks ?? '?'}</strong>`
  // startChunkIndex 是后端下推的 0-based 下标；展示给用户要转成 1-based 块号
  const first = resolveStartChunkIndex(toolData) + 1

  if (fetched <= 1) {
    return t('agentStream.knowledgeChunksList.chunkSingle', {
      index: `<strong>${first}</strong>`,
      total,
    })
  }

  return t('agentStream.knowledgeChunksList.chunkRange', {
    from: `<strong>${first}</strong>`,
    to: `<strong>${first + fetched - 1}</strong>`,
    total,
  })
}

function resolveStartChunkIndex(toolData: KnowledgeChunksListData): number {
  if (toolData.startChunkIndex !== undefined) {
    return Number(toolData.startChunkIndex)
  }
  const page = Number(toolData.page ?? 1)
  const pageSize = Number(toolData.pageSize ?? 0)
  return page > 1 && pageSize > 0 ? (page - 1) * pageSize : 0
}
