import assert from 'node:assert/strict'
import test from 'node:test'

import { getKnowledgeChunksSummaryHtml } from './knowledgeChunksDisplay.ts'

const t = (key, params) => {
  if (key === 'agentStream.knowledgeChunksList.chunkRange') {
    return `read ${params?.from}-${params?.to} of ${params?.total}`
  }
  if (key === 'agentStream.knowledgeChunksList.chunkSingle') {
    return `chunk ${params?.index} of ${params?.total}`
  }
  return key
}

test('summary shows the exact block range read this time', () => {
  const html = getKnowledgeChunksSummaryHtml(t, {
    displayType: 'knowledge_chunks_list',
    fetchedChunks: 10,
    totalChunks: 67,
    startChunkIndex: 26,
  })
  assert.equal(html, 'read <strong>27</strong>-<strong>36</strong> of <strong>67</strong>')
})

test('summary uses single-block wording when one block is read', () => {
  const html = getKnowledgeChunksSummaryHtml(t, {
    displayType: 'knowledge_chunks_list',
    fetchedChunks: 1,
    totalChunks: 67,
    startChunkIndex: 24,
  })
  assert.equal(html, 'chunk <strong>25</strong> of <strong>67</strong>')
})

test('summary falls back to page/pageSize for old payloads', () => {
  const html = getKnowledgeChunksSummaryHtml(t, {
    displayType: 'knowledge_chunks_list',
    fetchedChunks: 20,
    totalChunks: 282,
    page: 3,
    pageSize: 20,
  })
  assert.match(html, /read <strong>41<\/strong>-<strong>60<\/strong>/)
})
