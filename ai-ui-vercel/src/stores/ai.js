import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { streamText, stepCountIs } from 'ai'
import { deepseek } from '@/ai/provider'
import { buildSystemPrompt } from '@/ai/system-prompt'
import { getToolsForStep } from '@/tools'
import { useTaskStore } from '@/stores/task'
import { clearInteraction } from '@/tools/runtime'
import { toolTitle, toolImpact } from '@/tools/registry'

/**
 * AI 对话 store —— 前端 AI runtime(Vercel AI SDK streamText 驱动)。
 *
 * 与旧 ai-ui ai store 的本质差异:
 *   - agent loop 在前端跑(streamText + maxSteps),不在后端
 *   - 工具 execute 在浏览器内执行,暂停发生在 execute 的 Promise 内(不再用 resumeToken)
 *   - 任务/配置数据本地化(localStorage + mock),无后端 REST
 *   - 消息结构: { id, role, content, toolCards, done, pending }
 *     toolCards: [{ toolCallId, toolName, args, status, result, title, impact }]
 *
 * 流式消费:
 *   for await (part of fullStream):
 *     text-delta  -> 增量拼到当前 assistant 文本(打字机效果)
 *     tool-call   -> 追加工具卡片(状态 running)
 *     tool-result -> 更新卡片状态 succeeded/failed + 回填结果
 *   确认/表单工具的 execute 会设置 pendingInteraction(runtime.js)暂停,
 *   AiPanel 据此渲染交互卡片,用户操作后 resolve,execute 返回,loop 续行。
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    sessionId: uuidv4(),
    expanded: true,
    messages: [],
    loading: false,
    lastEvent: null,
    mode: 'PLAN'
  }),
  getters: {
    hasPendingInteraction: () => false // 由 runtime.pendingInteraction 驱动,AiPanel 直接读
  },
  actions: {
    pushMessage(msg) {
      this.messages.push({
        id: msg.id || uuidv4(),
        role: msg.role,
        content: msg.content || '',
        toolCards: msg.toolCards || [],
        done: msg.done !== undefined ? !!msg.done : true,
        pending: !!msg.pending
      })
    },

    /**
     * 发送消息并跑 agent loop。
     * @param message  用户文本
     * @param workspaceState  工作区状态快照(注入 system prompt)
     * @param extra  { autoPrompt?, triggerEvent? }
     */
    async chat(message, workspaceState = {}, extra = {}) {
      if (!message && !extra.autoPrompt) return
      if (message) this.pushMessage({ role: 'user', content: message })
      // 推入 pending assistant 占位(流式增量填充)
      const assistantMsg = {
        id: uuidv4(),
        role: 'assistant',
        content: '',
        toolCards: [],
        done: false,
        pending: true
      }
      this.messages.push(assistantMsg)
      this.loading = true

      const system = buildSystemPrompt(workspaceState, {
        autoPrompt: !!extra.autoPrompt,
        triggerEvent: extra.triggerEvent || null
      })

      // 构造 SDK messages:文本历史(最近 20 条,对齐后端 listHistory 截断)
      let history = this.messages
        .filter(m => !m.pending && m.content && (m.role === 'user' || m.role === 'assistant'))
        .slice(-20)
        .map(m => ({ role: m.role, content: m.content }))

      // autoPrompt 无用户文本时,history 可能为空。
      // Vercel AI SDK streamText 要求 messages 至少 1 条,否则抛
      // AI_InvalidPromptError: Invalid prompt: messages must not be empty。
      // 兜底:注入一条合成 user 消息(不显示到 UI,仅喂给 SDK)。
      if (history.length === 0) {
        history = [{ role: 'user', content: '(系统自动触发,请根据当前工作区状态给出引导)' }]
      }

      // 工具动态发现:按当前 scenario+step 过滤(progressive disclosure)
      const taskStore = useTaskStore()
      const scenario = taskStore.scenario
      const step = taskStore.currentStep
      const tools = getToolsForStep(scenario, step)

      try {
        const result = streamText({
          model: deepseek,
          system,
          messages: history,
          tools,
          stopWhen: stepCountIs(10)
        })

        for await (const part of result.fullStream) {
          this._handleStreamPart(part, assistantMsg)
        }
        assistantMsg.done = true
        assistantMsg.pending = false
      } catch (e) {
        assistantMsg.content += (assistantMsg.content ? '\n' : '') + '【AI 调用失败】' + (e?.message || String(e))
        assistantMsg.done = true
        assistantMsg.pending = false
        console.error('[aiStore] streamText 失败', e)
      } finally {
        this.loading = false
        clearInteraction()
      }
      this._persistMessages()
      return assistantMsg
    },

    /**
     * 处理 fullStream 分片,增量更新 assistantMsg。
     */
    _handleStreamPart(part, msg) {
      switch (part.type) {
        case 'text-delta': {
          const delta = part.textDelta || part.text || ''
          if (delta) msg.content += delta
          break
        }
        case 'tool-call': {
          // 模型调用了工具:追加卡片(SDK 会自动调 execute)
          const toolCallId = part.toolCallId
          // 去重:同一 toolCallId 只加一张卡
          if (!msg.toolCards.find(c => c.toolCallId === toolCallId)) {
            msg.toolCards.push({
              toolCallId,
              toolName: part.toolName,
              args: part.args || {},
              status: 'running',
              result: null,
              title: toolTitle(part.toolName),
              impact: toolImpact(part.toolName, part.args || {})
            })
          }
          break
        }
        case 'tool-result': {
          const card = msg.toolCards.find(c => c.toolCallId === part.toolCallId)
          if (card) {
            const r = part.result
            card.result = r
            if (r && r.ok === false) {
              card.status = (r.message === 'user_cancelled') ? 'cancelled' : 'failed'
            } else {
              card.status = 'succeeded'
            }
          }
          break
        }
        case 'error': {
          const err = part.error
          msg.content += (msg.content ? '\n' : '') + '【流错误】' + (err?.message || JSON.stringify(err))
          break
        }
        // step-start / step-finish / finish 等无需特殊处理
      }
    },

    setLastEvent(ev) { this.lastEvent = ev },
    toggleExpand() { this.expanded = !this.expanded },
    setExpand(v) { this.expanded = v },

    /** 加载历史(localStorage 持久化,按 taskId 隔离) */
    loadHistory(taskId) {
      try {
        const raw = localStorage.getItem('ai-vercel-chat-' + taskId)
        if (!raw) { this.messages = []; return }
        const arr = JSON.parse(raw)
        this.messages = Array.isArray(arr) ? arr : []
      } catch (e) { this.messages = [] }
    },

    _persistMessages() {
      const taskStore = useTaskStore()
      if (!taskStore.currentTaskId) return
      try {
        localStorage.setItem('ai-vercel-chat-' + taskStore.currentTaskId, JSON.stringify(this.messages))
      } catch (e) { /* ignore */ }
    },

    clear() {
      this.messages = []
      this.mode = 'PLAN'
      clearInteraction()
      const taskStore = useTaskStore()
      if (taskStore.currentTaskId) {
        try { localStorage.removeItem('ai-vercel-chat-' + taskStore.currentTaskId) } catch (e) {}
      }
    }
  }
})
