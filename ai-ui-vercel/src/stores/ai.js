import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { streamText, stepCountIs } from 'ai'
import { deepseek } from '@/ai/provider'
import { buildSystemPrompt } from '@/ai/system-prompt'
import { getToolsForStep } from '@/tools'
import { useTaskStore } from '@/stores/task'
import { clearInteraction, cancelInteraction, recoverInteraction, withVersionHint, pendingInteraction } from '@/tools/runtime'
import { toolTitle, toolImpact, getToolMeta } from '@/tools/registry'
import { getDataVersion } from '@/utils/workspace-version'

/** 改造 F1：当前在途 streamText 的 AbortController（停止按钮中断；loading 互斥保证同一时刻只有一个 run） */
let currentAbort = null

/**
 * AI 对话 store —— 前端 AI runtime(Vercel AI SDK streamText 驱动)。
 *
 * 与旧 ai-ui ai store 的本质差异:
 *   - agent loop 在前端跑(streamText + maxSteps),不在后端
 *   - 工具 execute 在浏览器内执行,暂停发生在 execute 的 Promise 内(不再用 resumeToken)
 *   - 任务/配置数据本地化(localStorage + mock),无后端 REST
 *   - 消息结构: { id, role, content, toolCards, done, pending }
 *     toolCards: [{ toolCallId, toolName, args, status, result, title, impact, suspendedDataVersion }]
 *
 * 流式消费:
 *   for await (part of fullStream):
 *     text-delta  -> 增量拼到当前 assistant 文本(打字机效果)
 *     tool-call   -> 追加工具卡片(状态 running)
 *     tool-result -> 更新卡片状态 succeeded/failed + 回填结果
 *   确认/表单工具的 execute 会设置 pendingInteraction(runtime.js)暂停,
 *   AiPanel 据此渲染交互卡片,用户操作后 resolve,execute 返回,loop 续行。
 *
 * ====== 改造 A v1.1 持久化顺序（三阶段提交模式，防「有问无答」污染上下文） ======
 *   1. 用户消息先落 sessionStorage 再发起 LLM 调用（chat 入口 push 后立即 _persistMessages）
 *   2. AI 响应完整（loop 结束）后才标记该轮 resolved（done=true）并再次持久化
 *   3. 刷新恢复时以「最后一条 resolved 轮次」为一致点：未 resolved 的尾部消息
 *      由 recoverPendingInteraction 按 AG-UI 重连模式重建交互卡片或标记中断（改造 B3）
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
      if (message) {
        this.pushMessage({ role: 'user', content: message })
        // 改造 A v1.1 三阶段提交①：用户消息先落库(sessionStorage)再发起 LLM 调用
        this._persistMessages()
      }
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
      await this._runLoop(assistantMsg, workspaceState, extra)
      return assistantMsg
    },

    /**
     * agent loop 主循环(chat 与 B4 恢复路径共享)。
     * 改造 H1：maxRetries=3 —— AI SDK 内置指数退避并尊重 Retry-After 头
     *   （可重试: 429/5xx/网络超时; 401/400/上下文超限不重试直接抛）。
     * 改造 F1：abortSignal 支持停止按钮协作式中断（轮次边界停止+保留现场）。
     */
    async _runLoop(assistantMsg, workspaceState = {}, extra = {}) {
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

      currentAbort = new AbortController()
      try {
        const result = streamText({
          model: deepseek,
          system,
          messages: history,
          tools,
          stopWhen: stepCountIs(10),
          // 改造 H1：错误三分类中的「可重试」分支(429/5xx/超时)由 SDK 指数退避处理,上限 3 次
          maxRetries: 3,
          // 改造 F1：停止按钮中断流式生成；SDK 会把 signal 透传给工具 execute(F3 步骤边界检查)
          abortSignal: currentAbort.signal
        })

        for await (const part of result.fullStream) {
          this._handleStreamPart(part, assistantMsg)
        }
        assistantMsg.done = true
        assistantMsg.pending = false
      } catch (e) {
        const userStopped = currentAbort.signal.aborted || e?.name === 'AbortError' && currentAbort.signal.aborted
        if (userStopped) {
          // 改造 F1：用户主动停止——保留现场,不追加错误信息
          assistantMsg.content += (assistantMsg.content ? '\n' : '') + '（已停止，已完成的内容保留）'
        } else if (this._isDisconnectError(e)) {
          // ====== 改造 H3：断连安全默认 ======
          // RUN 中途断连(网络错误/180s 超时中止)不自动重跑(防重复副作用),提示可重新发送
          assistantMsg.content += (assistantMsg.content ? '\n' : '')
            + '【连接中断】请检查网络后重新发送。已确认/已执行的操作结果保留，不会重复执行。'
        } else {
          assistantMsg.content += (assistantMsg.content ? '\n' : '') + '【AI 调用失败】' + (e?.message || String(e))
        }
        assistantMsg.done = true
        assistantMsg.pending = false
        console.error('[aiStore] streamText 失败', e)
      } finally {
        currentAbort = null
        this.loading = false
        clearInteraction()
      }
      // 改造 A v1.1 三阶段提交②：loop 结束(该轮 resolved)后持久化最终状态
      this._persistMessages()
    },

    /** H3 断连判定：fetch 网络层 TypeError / AbortSignal.timeout 触发的 AbortError / SDK 网络错误 */
    _isDisconnectError(e) {
      if (!e) return false
      if (e.name === 'AbortError' || e.name === 'TimeoutError') return true
      const msg = String(e.message || e)
      return e.name === 'TypeError' || /failed to fetch|network|ECONN|ETIMEDOUT|terminated|socket/i.test(msg)
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
              impact: toolImpact(part.toolName, part.args || {}),
              // 改造 D3d/B3：记录挂起时工作区版本,随消息持久化,刷新恢复后仍可对比版本
              suspendedDataVersion: getDataVersion()
            })
            // 改造 B3：卡片元数据落 sessionStorage,刷新后可据此重建 pendingInteraction
            this._persistMessages()
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
            // 工具结果落定即持久化(刷新恢复以最后 resolved 状态为一致点)
            this._persistMessages()
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

    /**
     * ====== 改造 B3：pending 刷新恢复（AG-UI 重连模式） ======
     * 检测「尾部 assistant 有 toolCall 但无 result」→ 重建 pendingInteraction 交互卡片,
     * 不续跑已断的 Promise；非交互类中断卡片标记失败(不自动重跑,防重复副作用);
     * 无任何卡片的纯生成中断 → 按 H3 提示「连接中断,请重新发送」。
     * App.vue onMounted 在 loadHistory 之后调用(卡片元数据随 messages 持久化在 sessionStorage,天然存活刷新)。
     * @returns {boolean} 是否重建了 pending 交互
     */
    recoverPendingInteraction() {
      const last = this.messages[this.messages.length - 1]
      if (!last || last.role !== 'assistant') return false
      if (!last.pending && last.done !== false) return false
      // 尾部消息封口(该轮不再 resolved,转为「中断/待恢复」终态)
      last.pending = false
      last.done = true
      let recovered = false
      for (const card of last.toolCards || []) {
        if (card.status !== 'running') continue
        const meta = getToolMeta(card.toolName)
        const interactive = !!(meta && (meta.needConfirm || meta.inputMode))
        if (interactive && !recovered) {
          // 串行执行:最多一个待交互卡片;重建交互卡片(CONFIRM→确认按钮,INPUT→表单)
          recoverInteraction({
            toolCallId: card.toolCallId,
            type: meta.inputMode ? 'form' : 'confirm',
            toolName: card.toolName,
            args: card.args,
            impact: card.impact,
            suspendedDataVersion: card.suspendedDataVersion ?? null
          })
          recovered = true
        } else {
          // 自动类工具中断:标记失败,不自动重跑(H3 防重复副作用)
          card.status = 'failed'
          card.result = { ok: false, message: '页面刷新中断,未执行完成' }
        }
      }
      if (!recovered) {
        last.content += (last.content ? '\n' : '') + '（连接中断，请重新发送）'
      }
      this._persistMessages()
      return recovered
    },

    /**
     * ====== 改造 B4：恢复态交互提交 —— 不续跑旧 Promise,重开新一轮 loop ======
     * 刷新后旧 execute Promise 已销毁,无法 resolve;改为:
     *   1. 回填卡片最终态(D3d 版本对比提示仍生效)
     *   2. append 工具结果消息 + 重新 streamText 开新一轮 loop,模型基于结果继续推理
     * 新 assistant 消息天然绕过按 toolCallId 去重的逻辑(plan 风险表记录的去重坑)。
     * @returns {boolean} 是否消费了恢复态交互
     */
    async submitRecoveredInteraction(result, workspaceState = {}) {
      const p = pendingInteraction.value
      if (!p || !p.recovered) return false
      const finalResult = withVersionHint(result, p.suspendedDataVersion)
      clearInteraction()
      // 1. 回填卡片最终态
      const last = this.messages[this.messages.length - 1]
      const card = last?.toolCards?.find(c => c.toolCallId === p.toolCallId)
      if (card) {
        card.result = finalResult
        card.status = finalResult && finalResult.ok === false
          ? (['user_cancelled', 'interaction_expired'].includes(finalResult.message) ? 'cancelled' : 'failed')
          : 'succeeded'
      }
      // 2. append 工具结果消息(模型可见),重开新一轮 loop
      const resultText = '[系统] 工具「' + (card?.title || p.toolName) + '」的用户交互已完成,结果: '
        + JSON.stringify(finalResult)
        + '。请基于此结果继续当前流程(如需最新工作区状态,先调 get_workspace_state 现查)。'
      this.pushMessage({ role: 'user', content: resultText })
      // 三阶段提交①：先落库再调 LLM
      this._persistMessages()
      const assistantMsg = {
        id: uuidv4(),
        role: 'assistant',
        content: '',
        toolCards: [],
        done: false,
        pending: true
      }
      this.messages.push(assistantMsg)
      await this._runLoop(assistantMsg, workspaceState, {})
      return true
    },

    /** B3/C4 配套：恢复态交互超时(runtime 定时器调用),本地标记卡片过期 */
    expireRecoveredInteraction(toolCallId) {
      const last = this.messages[this.messages.length - 1]
      const card = last?.toolCards?.find(c => c.toolCallId === toolCallId)
      if (card && card.status === 'running') {
        card.status = 'cancelled'
        card.result = { ok: false, message: 'interaction_expired', reason: '交互超时(10分钟未响应),已自动取消' }
        this._persistMessages()
      }
    },

    /**
     * ====== 改造 F1：协作式停止 ======
     * 业界共识:打断=轮次边界停止+保留现场,不删除已生成内容、不回滚已完成操作。
     * ① AbortController 中断 streamText 流(并透传给工具 execute 做 F3 步骤边界检查)
     * ② 取消 pending 交互(reject 使 execute 终止)
     * ③ 尾部消息标记「已停止」
     */
    stopRun() {
      try { currentAbort?.abort() } catch (e) { /* ignore */ }
      cancelInteraction()
      const last = this.messages[this.messages.length - 1]
      if (last && last.role === 'assistant' && (last.pending || last.done === false)) {
        last.pending = false
        last.done = true
        last.content = (last.content ? last.content + '\n' : '') + '（已停止，已完成的内容保留）'
        for (const c of last.toolCards || []) {
          if (c.status === 'running') {
            c.status = 'cancelled'
            c.result = { ok: false, message: 'user_stopped', reason: '用户停止了执行' }
          }
        }
      }
      this.loading = false
      this._persistMessages()
    },

    setLastEvent(ev) { this.lastEvent = ev },
    toggleExpand() { this.expanded = !this.expanded },
    setExpand(v) { this.expanded = v },

    /** 加载历史(sessionStorage 持久化,按 taskId 隔离；会话随窗口:刷新存活、关窗自毁、多标签隔离) */
    loadHistory(taskId) {
      try {
        const raw = sessionStorage.getItem('ai-vercel-chat-' + taskId)
        if (!raw) { this.messages = []; return }
        const arr = JSON.parse(raw)
        this.messages = Array.isArray(arr) ? arr : []
      } catch (e) { this.messages = [] }
    },

    /**
     * 持久化消息到 sessionStorage。
     * ====== 改造 A v1.1 三阶段提交顺序约束（固化于此,不得调整调用顺序）======:
     *   ① 用户消息 push 后立即调本方法落库,再发起 LLM 调用
     *   ② tool-call/tool-result 分片到达时增量落库(刷新恢复依赖卡片元数据)
     *   ③ loop 完整结束(该轮 resolved)后落库最终状态
     * 刷新恢复以「最后一条 resolved 轮次」为一致点,防网络中断导致「有问无答」污染上下文。
     */
    _persistMessages() {
      const taskStore = useTaskStore()
      if (!taskStore.currentTaskId) return
      try {
        sessionStorage.setItem('ai-vercel-chat-' + taskStore.currentTaskId, JSON.stringify(this.messages))
      } catch (e) { /* ignore */ }
    },

    clear() {
      this.messages = []
      this.mode = 'PLAN'
      clearInteraction()
      const taskStore = useTaskStore()
      if (taskStore.currentTaskId) {
        try { sessionStorage.removeItem('ai-vercel-chat-' + taskStore.currentTaskId) } catch (e) {}
      }
    }
  }
})
