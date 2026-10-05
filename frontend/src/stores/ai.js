import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { aiApi } from '@/api'
import { postSSE } from '@/api/sse'
// 联动不耦合：AI 侧只使用 workspace store 的公开契约（buildContext），
// 不 import 任何业务视图 / 组件内部状态
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

// ===================== 会话镜像（页签刷新不丢，任务 3） =====================
// messages/pendingCall 变化时（防抖 300ms）镜像写入 sessionStorage，key 含 sessionId。
// 新页签 sessionStorage 无记录 → 全新会话（保持现状）。
const MIRROR_PREFIX = 'ai-mirror:'
let mirrorStarted = false
let mirrorTimer = null

function readMirror(sessionId) {
  try {
    const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

function writeMirror(sessionId, messages) {
  try {
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify({
      savedAt: Date.now(),
      // 镜像只保留展示所需字段（含 toolCall，用于重建确认卡片；pendingCall 已内嵌其中）
      messages: messages.map((m) => ({
        role: m.role,
        content: m.content || '',
        reasoning: m.reasoning || '',
        toolRuns: m.toolRuns || [],
        toolCall: m.toolCall || null
      }))
    }))
  } catch (e) { /* 配额不足等场景忽略，镜像只是刷新体验优化 */ }
}

let currentAbort = null
const autoExecutedCallIds = new Set()

/**
 * AI 对话 store —— SSE 流式会话。
 * 工作区缺省时（如直接打开 /tasks）本栏依然可用：context 取 workspace 默认值
 * （page=TASKS），后端工具照常执行，前端工具由执行器按页面可用性兜底。
 * 消息结构：{ id, role, content, reasoning, toolRuns:[{name,status}],
 *            toolCall:{callId,name,arguments,needConfirm,status}|null, pending, stopped, done }
 * toolCall.status: pending / running / succeeded / failed / rejected / expired（会话已失效）
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

    /** 启动会话镜像：messages 任何变化（含流式 token）防抖写入 sessionStorage */
    startMirror() {
      if (mirrorStarted) return
      mirrorStarted = true
      this.$subscribe(() => {
        clearTimeout(mirrorTimer)
        mirrorTimer = setTimeout(() => writeMirror(this.sessionId, this.messages), 300)
      }, { detached: true, deep: true })
    },

    /** 镜像 → 展示态消息（全部为已完成状态） */
    _restoreMirrorMessages(list) {
      this.messages = list.map((m) => ({
        id: uuidv4(),
        role: m.role,
        content: m.content || '',
        reasoning: m.reasoning || '',
        toolRuns: m.toolRuns || [],
        toolCall: m.toolCall ? { ...m.toolCall } : null,
        pending: false,
        stopped: false,
        done: true
      }))
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

    /**
     * 进入页面时恢复历史消息与未完成的前端工具调用（刷新不丢）：
     * 1) 先从 sessionStorage 镜像即时恢复展示；
     * 2) 再调 GET /api/ai/history 与后端对账——后端非空以后端为准
     *    （它持有可续跑的模型上下文与暂停现场）；后端为空（如后端重启、TTL 过期）
     *    则保留镜像展示，但旧会话已无法续跑：挂起的前端工具降级为 expired，
     *    用户发新消息时后端 getOrCreate 自动按新会话重建，无需特殊处理。
     */
    async loadHistory() {
      this.startMirror()
      // 1) 镜像即时恢复
      const mirror = readMirror(this.sessionId)
      if (mirror && Array.isArray(mirror.messages) && mirror.messages.length) {
        this._restoreMirrorMessages(mirror.messages)
      }
      // 2) 后端对账
      let data
      try {
        data = await aiApi.history(this.sessionId)
      } catch (e) {
        return // 后端不可达：保留镜像展示
      }
      const list = (data && data.messages) || []
      if (!list.length) {
        // 后端为空：镜像保留展示；挂起的工具调用已无法回灌续跑，降级展示
        for (const m of this.messages) {
          if (m.toolCall && m.toolCall.status === 'pending') {
            m.toolCall = { ...m.toolCall, status: 'expired' }
          }
        }
        return
      }
      // 后端非空：以后端为准重建
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
