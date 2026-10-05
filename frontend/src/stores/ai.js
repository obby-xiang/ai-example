import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { aiApi } from '@/api'
import { postSSE } from '@/api/sse'
import { useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool } from '@/utils/frontend-tools'

function initSessionId() {
  try {
    const stored = sessionStorage.getItem('ai-session-id')
    if (stored) return stored
    const id = uuidv4()
    sessionStorage.setItem('ai-session-id', id)
    return id
  } catch (e) {
    return uuidv4()
  }
}

let currentAbort = null
const autoExecutedCallIds = new Set()

/**
 * AI 对话 store —— SSE 流式会话。
 * 消息结构：{ id, role, content, reasoning, toolRuns:[{name,status}],
 *            toolCall:{callId,name,arguments,needConfirm,status}|null, pending, stopped, done }
 * toolCall.status: pending / running / succeeded / failed / rejected
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    sessionId: initSessionId(),
    expanded: true,
    messages: [],
    loading: false
  }),
  getters: {
    pendingToolCall: (state) => {
      for (let i = state.messages.length - 1; i >= 0; i--) {
        const m = state.messages[i]
        if (m.role === 'assistant' && m.toolCall && m.toolCall.status === 'pending') {
          return m.toolCall
        }
        if (m.role === 'assistant' && m.toolCall) return null
      }
      return null
    }
  },
  actions: {
    toggleExpand() {
      this.expanded = !this.expanded
    },

    _newAssistantMsg() {
      const msg = {
        id: uuidv4(),
        role: 'assistant',
        content: '',
        reasoning: '',
        toolRuns: [],
        toolCall: null,
        pending: true,
        stopped: false,
        done: false
      }
      this.messages.push(msg)
      return msg
    },

    async _consumeStream(url, body, msg) {
      currentAbort = new AbortController()
      this.loading = true
      try {
        await postSSE(url, body, {
          signal: currentAbort.signal,
          onEvent: (event, data) => {
            if (event === 'token') {
              msg.content += data.text || ''
            } else if (event === 'reasoning') {
              msg.reasoning += data.text || ''
            } else if (event === 'tool_run') {
              msg.toolRuns.push({ name: data.name, status: data.status })
            } else if (event === 'tool_call') {
              msg.toolCall = {
                callId: data.callId,
                name: data.name,
                arguments: data.arguments || {},
                needConfirm: !!data.needConfirm,
                status: 'pending'
              }
            } else if (event === 'done') {
              msg.pending = false
              msg.done = true
              if (data.status === 'cancelled') {
                msg.content += (msg.content ? '\n' : '') + '（已取消）'
              } else if (data.status === 'max_rounds') {
                msg.content += (msg.content ? '\n' : '') + '（已达到最大执行轮次，请拆分任务后重试）'
              }
            } else if (event === 'error') {
              msg.pending = false
              msg.done = true
              msg.content += (msg.content ? '\n' : '') + '【错误】' + (data.message || '未知错误')
            }
          }
        })
      } catch (e) {
        if (e?.name === 'AbortError') {
          msg.pending = false
          msg.done = true
          msg.stopped = true
          msg.content += (msg.content ? '\n' : '') + '（已停止，已生成的内容保留）'
        } else {
          msg.pending = false
          msg.done = true
          msg.content += (msg.content ? '\n' : '') + '【请求失败】' + (e?.message || '网络错误')
        }
      } finally {
        currentAbort = null
        this.loading = false
      }

      // needConfirm=false 的前端工具自动执行并回灌续流
      const tc = msg.toolCall
      if (msg.done && tc && tc.status === 'pending' && !tc.needConfirm && !autoExecutedCallIds.has(tc.callId)) {
        autoExecutedCallIds.add(tc.callId)
        await this.executeToolCall(msg, tc)
      }
    },

    /**
     * 执行前端工具并把结果回灌后端恢复 Agent Loop。
     * @param msg  携带 toolCall 的 assistant 消息
     * @param tc   toolCall 对象
     * @param rejected 用户点击"拒绝"时为 true，不执行工具直接回灌拒绝
     */
    async executeToolCall(msg, tc, rejected = false) {
      tc.status = 'running'
      let result
      if (rejected) {
        result = { success: false, error: '用户拒绝执行' }
        tc.status = 'rejected'
      } else {
        try {
          result = await executeFrontendTool(tc.name, tc.arguments)
          tc.status = result && result.success === false ? 'failed' : 'succeeded'
        } catch (e) {
          result = { success: false, error: e?.message || String(e) }
          tc.status = 'failed'
        }
      }
      const ws = useWorkspaceStore()
      const nextMsg = this._newAssistantMsg()
      await this._consumeStream('/api/ai/tool-result', {
        sessionId: this.sessionId,
        callId: tc.callId,
        result,
        context: ws.buildContext()
      }, nextMsg)
    },

    async sendChat(text) {
      const message = (text || '').trim()
      if (!message || this.loading) return
      this.messages.push({ id: uuidv4(), role: 'user', content: message })
      const ws = useWorkspaceStore()
      const msg = this._newAssistantMsg()
      await this._consumeStream('/api/ai/chat', {
        sessionId: this.sessionId,
        message,
        context: ws.buildContext()
      }, msg)
    },

    async stop() {
      try { currentAbort && currentAbort.abort() } catch (e) { /* ignore */ }
      try { await aiApi.cancel(this.sessionId) } catch (e) { /* 后端不可达也继续本地停止 */ }
      this.loading = false
    },

    /** 进入页面时恢复历史消息与未完成的前端工具调用 */
    async loadHistory() {
      let data
      try {
        data = await aiApi.history(this.sessionId)
      } catch (e) {
        return
      }
      const list = (data && data.messages) || []
      this.messages = list.map((m) => {
        const msg = {
          id: uuidv4(),
          role: m.role,
          content: m.content || '',
          reasoning: '',
          toolRuns: m.toolName ? [{ name: m.toolName, status: 'ok' }] : [],
          toolCall: null,
          pending: false,
          stopped: false,
          done: true
        }
        return msg
      })
      const pc = data && data.pendingCall
      if (pc && pc.callId) {
        const msg = this._newAssistantMsg()
        msg.content = '（页面已刷新，恢复待处理的工具调用）'
        msg.pending = false
        msg.done = true
        msg.toolCall = {
          callId: pc.callId,
          name: pc.name,
          arguments: pc.arguments || {},
          needConfirm: !!pc.needConfirm,
          status: 'pending'
        }
      }
    }
  }
})
