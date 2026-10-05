import { defineStore } from 'pinia'
import { api, sseRequest } from '../api'
import { workspaceVersion } from '../utils/workspace'
import { on, emit, getContextSnapshot } from '../utils/workspaceBus'

/** 会话 ID：浏览器页签内唯一（sessionStorage 特性）。新页签=新会话（无历史）；刷新同页签历史保留。 */
function getSessionId() {
  let id = sessionStorage.getItem('ai_session_id')
  if (!id) {
    id = (crypto.randomUUID && crypto.randomUUID()) || `s-${Date.now()}-${Math.random().toString(36).slice(2)}`
    sessionStorage.setItem('ai_session_id', id)
  }
  return id
}

let seq = 0

/**
 * AI 对话状态：流式消息 + 工具卡片 + HITL 确认。
 * 与业务工作区完全解耦：只通过 workspaceBus 收发事件与上下文快照，
 * 不 import 任何工作区 store / 路由（工作区可独立运行；AI 也可独立完成全部操作）。
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    sessionId: getSessionId(),
    messages: [],          // {id, role, content, reasoning, tools:[], confirm, done}
    streaming: false,
    controller: null,
    sending: false,
    workspaceDirty: false, // 用户在工作区手动操作后置位（总线通知），发送消息时复位
    busReady: false
  }),
  actions: {
    /** 订阅工作区变更事件（解耦联动：仅总线通知）。 */
    initBus() {
      if (this.busReady) return
      this.busReady = true
      on('workspace_changed', () => {
        this.workspaceDirty = true
      })
    },

    /** 业务 → AI：经总线聚合上下文快照（工作区不存在时仅含 page 与版本号）。 */
    buildContext() {
      const ctx = { page: 'export', workspaceVersion: workspaceVersion() }
      Object.assign(ctx, getContextSnapshot())
      return ctx
    },

    async send(text) {
      const content = (text || '').trim()
      if (!content || this.streaming) return
      this.workspaceDirty = false
      this.messages.push({ id: ++seq, role: 'user', content, tools: [] })
      const assistantMsg = { id: ++seq, role: 'assistant', content: '', reasoning: '', tools: [], confirm: null, done: false }
      this.messages.push(assistantMsg)
      this.streaming = true
      this.sending = true
      this.controller = new AbortController()
      try {
        await this.streamChat('/api/ai/chat', {
          sessionId: this.sessionId,
          message: content,
          context: this.buildContext()
        }, assistantMsg, this.controller.signal)
      } catch (e) {
        if (e.name !== 'AbortError') {
          assistantMsg.content += `\n[错误] ${e.message}`
        }
        assistantMsg.done = true
      } finally {
        this.streaming = false
        this.sending = false
        this.controller = null
      }
    },

    /**
     * 确认/取消破坏性工具：仅向后端释放 HITL 确认门，
     * 后续事件（工具结果/增量回复）继续从原 chat SSE 流推进同一助手消息。
     */
    async confirmTool(approved) {
      const last = this.messages[this.messages.length - 1]
      if (!last || !last.confirm) return
      // 先清掉当前确认卡；续跑流中若出现新的确认卡由 ui_event 处理器重新挂上
      last.confirm = null
      try {
        await api.post('/api/ai/confirm', { sessionId: this.sessionId, approved })
      } catch (e) {
        last.content += `\n[错误] ${e.message}`
        last.done = true
      }
    },

    stop() {
      if (this.controller) this.controller.abort()
      api.post('/api/ai/stop', { sessionId: this.sessionId }).catch(() => {})
      this.streaming = false
      this.sending = false
    },

    async clear() {
      this.stop()
      try {
        await api.post('/api/ai/clear', { sessionId: this.sessionId })
      } catch (e) {
        /* 忽略 */
      }
      this.messages = []
    },

    /**
     * 刷新恢复：按页签会话（sessionStorage 中的 sessionId）从后端恢复对话历史。
     * 会话已持久化于 H2：刷新页面或后端重启后均完整恢复（含工具卡片）；
     * 会话不存在（新页签/超时清理）时返回空列表，前端保持新会话。
     */
    async restoreHistory() {
      if (this.messages.length) return
      try {
        const history = await api.get(`/api/ai/history?sessionId=${this.sessionId}`)
        if (!history || !history.length) return
        this.messages = history.map(h => ({
          id: ++seq,
          role: h.role,
          content: h.content || '',
          reasoning: '',
          tools: (h.tools || []).map(t => ({
            name: t.name,
            args: t.args || {},
            status: t.status || 'ok',
            summary: t.summary || ''
          })),
          confirm: null,
          done: true
        }))
      } catch (e) {
        /* 后端不可用或会话已过期：保持新会话 */
      }
    },

    /** 解析后端 SSE 事件流并更新当前 assistant 消息。 */
    async streamChat(url, body, assistantMsg, signal) {
      await sseRequest(url, {
        method: 'POST',
        body,
        signal,
        onEvent: (ev, data) => {
          switch (ev) {
            case 'reasoning':
              assistantMsg.reasoning += data.content || ''
              break
            case 'delta':
              assistantMsg.content += data.content || ''
              break
            case 'tool_start': {
              assistantMsg.tools.push({
                name: data.name,
                args: data.args,
                status: 'running',
                summary: ''
              })
              break
            }
            case 'tool_result': {
              const tool = assistantMsg.tools.filter(t => t.status === 'running').pop()
                || assistantMsg.tools[assistantMsg.tools.length - 1]
              if (tool) {
                tool.status = data.ok ? 'ok' : 'fail'
                tool.summary = data.summary || ''
              }
              break
            }
            case 'ui_event':
              this.applyUiEvent(data)
              break
            case 'done':
              if (data.content && assistantMsg.content.indexOf(data.content) === -1) {
                assistantMsg.content += data.content
              }
              assistantMsg.done = true
              break
            case 'error':
              assistantMsg.content += `\n[错误] ${data.message || '未知错误'}`
              assistantMsg.done = true
              break
            case 'usage':
              assistantMsg.usage = data.usage
              break
            default:
              break
          }
        }
      })
      if (!assistantMsg.done) assistantMsg.done = true
    },

    /** AI → 业务：ui_event 经总线转发（契约层执行与用户点击相同的 store action）。
     *  AI 面板自身只处理确认卡；工作区不存在时事件无人订阅，不影响对话。 */
    applyUiEvent(ev) {
      if (ev.type === 'confirm_tool') {
        const last = this.messages[this.messages.length - 1]
        if (last && last.role === 'assistant') {
          last.confirm = {
            toolCallId: ev.toolCallId,
            toolName: ev.toolName,
            summary: ev.summary
          }
        }
        return
      }
      emit('ui_event', ev)
    }
  }
})
