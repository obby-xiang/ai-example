/**
 * B5：所有 `synthetic=true` 条目（含旧后端缺 `syntheticKind`、含 compaction）都不进展示历史；
 * 真实压缩节点只由 B3 已提交事件恢复。同时锁 `/history.truncated` 落到 store 的中性标记字段。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAiStore } from '@/stores/ai'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  history: vi.fn(),
  runsCurrent: vi.fn(),
  getContext: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  for (const fn of Object.values(api)) {
    fn.mockReset()
  }
  api.runsCurrent.mockResolvedValue({
    found: false,
    runId: null,
    snapshotStatus: null,
    pendingEntries: [],
    unresolvedExternal: [],
    awaitingExternal: false,
    archiveMaxSeq: 0
  })
  api.getContext.mockResolvedValue({ source: 'none' })
  installIdentityFixture()
})

describe('B5 syntheticKind / truncated', () => {
  it('所有 synthetic 条目（含 compaction 与旧后端缺 kind）都不进展示历史', async () => {
    api.history.mockResolvedValue({
      sessionId: 'sess-syn-kind',
      count: 4,
      messages: [
        { type: 'USER', text: '原话' },
        { type: 'USER', text: '已达到本轮工具调用步数上限', synthetic: true, syntheticKind: 'step_limit' },
        { type: 'USER', text: '压缩摘要正文', synthetic: true, syntheticKind: 'compaction' },
        { type: 'USER', text: '[上下文压缩摘要]', synthetic: true }
      ]
    })
    const store = useAiStore()
    store.sessionId = 'sess-syn-kind'

    await store.loadHistory()

    expect(store.messages.map((message) => message.role)).toEqual(['user'])
    expect(store.messages).toHaveLength(1)
    expect(store.messages.some((message) => message.content.includes('已达到本轮工具调用步数上限'))).toBe(false)
    expect(store.messages.some((message) => message.content.includes('压缩摘要正文'))).toBe(false)
  })

  it('history.truncated=true 不再落 store 字段（容量裁剪由分页窗口表达）', async () => {
    api.history.mockResolvedValue({
      sessionId: 'sess-trunc',
      count: 1,
      truncated: true,
      messages: [{ type: 'USER', text: 'q' }]
    })
    const store = useAiStore()
    store.sessionId = 'sess-trunc'

    await store.loadHistory()

    expect(store.messages).toHaveLength(1)
    expect(store).not.toHaveProperty('historyTruncated')
  })
})
