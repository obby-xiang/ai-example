import { defineStore } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { api, sseRequest, downloadFile } from '../api'
import { workspaceVersion, bumpWorkspace } from '../utils/workspace'
import { useExportStore } from './exportTask'
import { useImportStore } from './importTask'
import { useDefsStore } from './defs'

/** 会话 ID：浏览器页签内唯一（sessionStorage 特性），不持久化、不看历史。 */
function getSessionId() {
  let id = sessionStorage.getItem('ai_session_id')
  if (!id) {
    id = (crypto.randomUUID && crypto.randomUUID()) || `s-${Date.now()}-${Math.random().toString(36).slice(2)}`
    sessionStorage.setItem('ai_session_id', id)
  }
  return id
}

let seq = 0

/** AI 对话状态：流式消息 + 工具卡片 + HITL 确认 + ui_event 分发（AI→业务状态同步）。 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    sessionId: getSessionId(),
    messages: [],          // {id, role, content, reasoning, tools:[], confirm, done}
    streaming: false,
    controller: null,
    sending: false
  }),
  actions: {
    /** 业务 → AI：构建上下文快照（摘要级，避免 token 浪费）。 */
    buildContext() {
      const route = useRoute()
      const page = String(route.name || 'export')
      const ctx = { page, workspaceVersion: workspaceVersion() }
      const exportStore = useExportStore()
      const importStore = useImportStore()
      ctx.export = {
        step: exportStore.step,
        selectedDefs: exportStore.selectedDefs,
        conditions: exportStore.conditions,
        taskId: exportStore.task ? exportStore.task.id : null
      }
      ctx.import = {
        step: importStore.step,
        selectedDefs: importStore.selectedDefs,
        batchId: importStore.batch ? importStore.batch.id : null
      }
      return ctx
    },

    async send(text) {
      const content = (text || '').trim()
      if (!content || this.streaming) return
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

    /** 确认/拒绝破坏性工具，续跑 Agent 循环。 */
    async confirmTool(approved) {
      const last = this.messages[this.messages.length - 1]
      if (!last || !last.confirm || this.streaming) return
      this.streaming = true
      this.controller = new AbortController()
      try {
        await this.streamChat('/api/ai/confirm', {
          sessionId: this.sessionId,
          approved
        }, last, this.controller.signal)
        last.confirm = null
      } catch (e) {
        if (e.name !== 'AbortError') last.content += `\n[错误] ${e.message}`
        last.done = true
        last.confirm = null
      } finally {
        this.streaming = false
        this.controller = null
      }
    },

    stop() {
      if (this.controller) this.controller.abort()
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

    /** AI → 业务：ui_event 统一分发（与用户点击同一 store action，保证状态一致）。 */
    async applyUiEvent(ev) {
      const type = ev.type
      const router = useRouter()
      try {
        const exportStore = useExportStore()
        const importStore = useImportStore()
        const defsStore = useDefsStore()

        switch (type) {
          case 'open_page': {
            const page = ev.page
            const route = { export: '/export', import: '/import', defs: '/defs', data: '/data' }[page]
            if (route) await router.push(route)
            break
          }
          case 'goto_step': {
            if (router.currentRoute.value.name === 'export') exportStore.goStep(ev.step)
            else if (router.currentRoute.value.name === 'import') importStore.goStep(ev.step)
            break
          }
          case 'select_defs': {
            if (ev.task === 'export') exportStore.selectDefs(ev.defCodes || [])
            else importStore.selectDefs(ev.defCodes || [])
            break
          }
          case 'set_conditions':
            exportStore.setConditions(ev.defCode, ev.conditions || {})
            break
          case 'export_started': {
            exportStore.task = ev.snapshot || { id: ev.taskId }
            exportStore.goStep(3)
            exportStore.subscribe(ev.taskId)
            break
          }
          case 'import_batch': {
            importStore.batch = { id: ev.batchId, detail: { files: [] } }
            importStore.goStep(1)
            importStore.reloadEntries()
            break
          }
          case 'import_action': {
            // 后端工具已实际启动任务；前端只同步步骤并订阅进度（避免重复触发）
            if (ev.action === 'check') importStore.goStep(2)
            else if (ev.action === 'import') importStore.goStep(3)
            else if (ev.action === 'publish') importStore.goStep(4)
            if (ev.batchId) {
              importStore.batch = importStore.batch || { id: ev.batchId, detail: {} }
              importStore.subscribe(ev.batchId)
            }
            break
          }
          case 'download':
            downloadFile(ev.url, ev.filename)
            break
          case 'refresh_defs':
            await defsStore.load()
            break
          case 'refresh_data':
            window.dispatchEvent(new CustomEvent('refresh-data', { detail: { defCode: ev.defCode } }))
            break
          case 'confirm_tool': {
            const last = this.messages[this.messages.length - 1]
            if (last && last.role === 'assistant') {
              last.confirm = {
                toolCallId: ev.toolCallId,
                toolName: ev.toolName,
                summary: ev.summary
              }
            }
            break
          }
          default:
            break
        }
        bumpWorkspace()
      } catch (e) {
        console.warn('ui_event 处理失败', type, e)
      }
    }
  }
})
