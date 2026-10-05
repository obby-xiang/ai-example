import { defineStore } from 'pinia'
import { api } from '@/api'
import { postSSE } from '@/api/sse'
import { useSessionStore } from './session'

let currentAbort = null

/**
 * AI 对话 store：消息列表、流式接收、活动卡片（SSE activity 事件）。
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    messages: [],          // {id, role: user|assistant, content, streaming, error}
    activities: [],        // AI 工具活动卡片
    sending: false,
    restored: false
  }),
  actions: {
    async restore() {
      if (this.restored) return
      const session = useSessionStore()
      const sid = session.ensureSession()
      try {
        const history = await api.aiHistory(sid)
        this.messages = (history || []).map((m, i) => ({
          id: `hist-${i}`,
          role: m.role,
          content: m.content,
          streaming: false
        }))
      } catch { /* 忽略恢复失败 */ }
      this.restored = true
    },
    pushActivity(text) {
      this.activities.push({ id: Date.now() + Math.random(), text, ts: new Date().toLocaleTimeString('zh-CN') })
      if (this.activities.length > 30) {
        this.activities.shift()
      }
    },
    async send(text) {
      const session = useSessionStore()
      const sid = session.ensureSession()
      const content = (text || '').trim()
      if (!content || this.sending) return
      this.messages.push({ id: 'u-' + Date.now(), role: 'user', content, streaming: false })
      const assistantMsg = { id: 'a-' + Date.now(), role: 'assistant', content: '', streaming: true }
      this.messages.push(assistantMsg)
      this.sending = true
      currentAbort = new AbortController()
      try {
        await postSSE('/api/ai/chat', { sessionId: sid, message: content }, {
          signal: currentAbort.signal,
          onEvent: (event, data) => {
            if (event === 'token' && data.text) {
              assistantMsg.content += data.text
            } else if (event === 'error') {
              assistantMsg.content += (assistantMsg.content ? '\n\n' : '') + `⚠ ${data.message || 'AI 服务异常'}`
              assistantMsg.error = true
            } else if (event === 'done') {
              assistantMsg.streaming = false
            }
          }
        })
      } catch (e) {
        if (e.name !== 'AbortError') {
          assistantMsg.content += (assistantMsg.content ? '\n\n' : '') + `⚠ ${e.message}`
          assistantMsg.error = true
        }
      } finally {
        assistantMsg.streaming = false
        this.sending = false
        currentAbort = null
      }
    },
    stop() {
      if (currentAbort) {
        currentAbort.abort()
      }
    },
    async clear() {
      const session = useSessionStore()
      await api.aiClear(session.ensureSession())
      this.messages = []
      this.activities = []
    }
  }
})
