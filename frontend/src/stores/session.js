import { defineStore } from 'pinia'

/**
 * 会话 store：sessionId（sessionStorage，页签唯一）+ SSE 事件通道 + 刷新恢复。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    sessionId: '',
    activeTaskId: null,
    sseSource: null,
    connected: false
  }),
  actions: {
    ensureSession() {
      if (!this.sessionId) {
        let sid = sessionStorage.getItem('qk.sessionId')
        if (!sid) {
          sid = 'sess-' + crypto.randomUUID().replace(/-/g, '').slice(0, 16)
          sessionStorage.setItem('qk.sessionId', sid)
        }
        this.sessionId = sid
      }
      return this.sessionId
    },
    connectSSE(handlers) {
      this.ensureSession()
      this.disconnectSSE()
      const es = new EventSource(`/api/sse?sessionId=${encodeURIComponent(this.sessionId)}`)
      this.sseSource = es
      es.onopen = () => { this.connected = true }
      es.onerror = () => { this.connected = false }
      for (const [event, handler] of Object.entries(handlers || {})) {
        es.addEventListener(event, (e) => {
          let data = {}
          try { data = JSON.parse(e.data) } catch { /* ignore */ }
          handler(data)
        })
      }
    },
    disconnectSSE() {
      if (this.sseSource) {
        this.sseSource.close()
        this.sseSource = null
        this.connected = false
      }
    }
  }
})
