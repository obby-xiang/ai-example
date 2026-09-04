import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'
import { aiApi } from '@/api/ai'
import { adaptLegacyActionToToolCall } from '@/utils/frontend-tool-registry'

/**
 * AI对话 store - 独立于路由,保持跨步骤的状态
 *
 * ====== 方案二：后端 Runtime + Agent Loop + 前端工具暂停-恢复 ======
 * 消息结构:
 *   { id, role, content, toolCalls, actions, pending, resumeToken, done, toolStatus,
 *     plan, mode, currentScenario, currentStep }
 *
 *   toolCalls    - 后端下发的结构化工具调用（FRONTEND kind）
 *   resumeToken  - 后端 agent loop 的恢复令牌；toolCalls 存在时必有
 *   done         - true 表示对话结束，false 表示 loop 暂停等工具回灌
 *   toolStatus   - { [callId]: 'pending' | 'running' | 'succeeded' | 'cancelled' | 'failed' }
 *
 * ====== Phase 2 新增字段 ======
 *   plan             - Plan-and-Execute 模式的计划步骤列表（业界主流 Plan 卡片数据源）
 *   mode             - 当前模式：PLAN(规划中)/EXECUTE(执行中)/AUTO(自动执行)/CONFIRM(待用户确认)/DONE(完成)
 *   currentScenario  - 当前场景 ID（EXPORT/IMPORT/ADD/MODIFY），用于工具动态发现
 *   currentStep      - 当前步骤 ID（SELECT_SCENARIO/SELECT_DEFS/...），用于工具动态发现
 *
 * 工具执行流程（闭环）：
 *   后端返回 done=false + toolCalls + resumeToken
 *     → AiPanel 渲染工具卡片
 *       · autoExec=true 的工具(AUTO 模式) → 自动执行 + 自动回灌
 *       · autoExec=false 的工具(CONFIRM 模式) → 渲染卡片等用户点击
 *       · requireDoubleConfirm=true → 触发 ElMessageBox 二次确认
 *     → 调用 executeFrontendTool(tc) 实际执行
 *     → 调用 submitToolResult(resumeToken, callId, result)
 *     → 后端恢复 loop，可能返回下一轮 toolCalls 或 done=true 的最终回复
 *     → 更新消息
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    // 改造 A：会话随窗口——sessionId 持久化到 sessionStorage(刷新存活、关窗自毁、多标签天然隔离)
    // 修复现状 bug：此前仅内存 uuidv4()，刷新后重新生成导致历史断连
    sessionId: initSessionId(),
    expanded: true,
    messages: [],
    loading: false,
    pendingConfirmations: [],
    lastEvent: null,
    /** 当前 Plan-and-Execute 计划（Planner 生成后保存，前端渲染 Plan 卡片） */
    plan: [],
    /** 当前模式：PLAN / EXECUTE / AUTO / CONFIRM / DONE */
    mode: 'PLAN',
    /** 当前场景 ID（工具动态发现上下文） */
    currentScenario: null,
    /** 当前步骤 ID（工具动态发现上下文） */
    currentStep: null,
    /** Planner 计划摘要（人类可读，展示在 Plan 卡片头部） */
    planSummary: '',
    /** 当前 Plan 是否为兜底计划（Planner 调用失败时后端用状态图生成） */
    planFallback: false
  }),
  getters: {
    /** 当前是否有未完成的工具回灌等待中 */
    hasPendingToolCall: (state) => {
      const last = state.messages[state.messages.length - 1]
      return last && last.role === 'assistant' && last.done === false && last.resumeToken
    },
    /** 当前是否有 Plan 待用户确认 */
    hasPendingPlan: (state) => state.plan && state.plan.length > 0 && state.mode === 'PLAN'
  },
  actions: {
    pushMessage(msg) {
      this.messages.push({
        id: msg.id || uuidv4(),
        role: msg.role,
        content: msg.content || '',
        toolCalls: msg.toolCalls || [],
        actions: msg.actions || [],
        pending: !!msg.pending,
        resumeToken: msg.resumeToken || null,
        done: msg.done !== undefined ? !!msg.done : true,
        toolStatus: msg.toolStatus || {},
        // 改造 C3：pending 过期时间(ISO 字符串)，AiPanel 倒计时展示
        pendingExpiresAt: msg.pendingExpiresAt || null,
        // 改造 B2：刷新恢复重建的消息打标(用于区分展示/排障)
        recovered: !!msg.recovered,
        // Phase 2: 消息级 plan/mode 快照（消息内渲染 Plan 卡片时用）
        plan: msg.plan || null,
        mode: msg.mode || null,
        currentScenario: msg.currentScenario || null,
        currentStep: msg.currentStep || null
      })
    },

    /**
     * 用后端响应更新最后一条 pending assistant 消息。
     * 同时处理 toolCalls/resumeToken/done 三个字段 + Phase 2 元数据。
     */
    updateLastMsg(reply, resp = {}) {
      const idx = this.messages.findLastIndex(m => m.pending && m.role === 'assistant')
      if (idx < 0) return
      const msg = this.messages[idx]
      msg.content = reply || ''

      // 优先消费后端下发的结构化 toolCalls（主协议）
      let tc = (resp.toolCalls && Array.isArray(resp.toolCalls) && resp.toolCalls.length)
        ? resp.toolCalls.slice() : []
      // 降级：actions adapt 成 toolCalls
      if (!tc.length && resp.actions && resp.actions.length) {
        tc = resp.actions.map(a => adaptLegacyActionToToolCall(a)).filter(Boolean)
      }
      msg.toolCalls = tc
      msg.actions = resp.actions || []
      msg.resumeToken = resp.resumeToken || null
      msg.done = resp.done !== undefined ? !!resp.done : true
      // 初始化 toolStatus：所有 toolCalls 都是 pending
      msg.toolStatus = {}
      for (const t of msg.toolCalls) {
        msg.toolStatus[t.callId] = 'pending'
      }
      msg.pending = false

      // Phase 2: 同步元数据到消息（供 AiPanel 渲染）
      msg.mode = resp.mode || null
      msg.currentScenario = resp.currentScenario || null
      msg.currentStep = resp.currentStep || null

      // 同步到 store 顶层状态（用于工作区联动 / 工具动态发现）
      if (resp.mode) this.mode = resp.mode
      if (resp.currentScenario) this.currentScenario = resp.currentScenario
      if (resp.currentStep) this.currentStep = resp.currentStep
    },

    /**
     * 更新某个 toolCall 的执行状态。
     * 状态: pending / running / succeeded / cancelled / failed
     */
    setToolCallStatus(callId, status) {
      const last = this.messages[this.messages.length - 1]
      if (last && last.role === 'assistant' && last.toolStatus) {
        last.toolStatus[callId] = status
      }
    },

    /**
     * Phase 2: 设置当前 Plan-and-Execute 计划（由 AiPanel.generatePlan 调用）。
     * @param plan     Planner 生成的步骤列表
     * @param summary  计划摘要
     * @param isFallback 是否兜底计划
     */
    setPlan(plan, summary, isFallback = false) {
      this.plan = Array.isArray(plan) ? plan : []
      this.planSummary = summary || ''
      this.planFallback = !!isFallback
      this.mode = this.plan.length ? 'PLAN' : 'DONE'
    },

    /** 清空当前 Plan（用户确认/取消后） */
    clearPlan() {
      this.plan = []
      this.planSummary = ''
      this.planFallback = false
    },

    /**
     * 生成执行计划 —— 业界 Plan-and-Execute 模式 Phase 1。
     * 调用后端 /api/ai/plan，Planner 生成完整 plan（不执行）。
     * AiPanel 据此渲染 Plan 卡片，用户一次确认整个计划。
     */
    async generatePlan(message, workspaceState, extra = {}) {
      this.loading = true
      try {
        const payload = {
          taskId: extra.taskId || null,
          sessionId: this.sessionId,
          message: message || '',
          workspaceState: workspaceState || {},
          currentScenario: extra.currentScenario || this.currentScenario || null,
          currentStep: extra.currentStep || this.currentStep || null
        }
        const resp = await aiApi.plan(payload)
        this.setPlan(resp.plan, resp.summary, resp.isFallback)
        if (resp.currentScenario && resp.currentScenario !== 'ALL') {
          this.currentScenario = resp.currentScenario
        }
        if (resp.currentStep && resp.currentStep !== 'ALL') {
          this.currentStep = resp.currentStep
        }
        return resp
      } catch (e) {
        this.setPlan([], 'AI 规划失败：' + (e?.message || '未知错误'), true)
        return null
      } finally {
        this.loading = false
      }
    },

    async chat(message, workspaceState, extra = {}) {
      if (!message && !extra.autoPrompt) return
      // 改造 F3：新一轮对话开始，复位流程取消标志（配合后端 F2 中断标志清除）
      resetFlowCancel()
      if (message) this.pushMessage({ role: 'user', content: message })
      this.pushMessage({ role: 'assistant', content: '', pending: true })
      this.loading = true
      // 改造 F1：在途请求可被停止按钮中断
      currentAbort = new AbortController()
      try {
        const payload = {
          taskId: extra.taskId || null,
          sessionId: this.sessionId,
          message: message || '',
          workspaceState: workspaceState || {},
          autoPrompt: !!extra.autoPrompt,
          triggerEvent: extra.triggerEvent || null,
          // Phase 2: 附带 scenario+step 让后端动态过滤工具集（progressive disclosure）
          currentScenario: extra.currentScenario || this.currentScenario || null,
          currentStep: extra.currentStep || this.currentStep || null
        }
        const resp = extra.autoPrompt
          ? await aiApi.autoPrompt(payload, currentAbort.signal)
          : await aiApi.chat(payload, currentAbort.signal)
        this.updateLastMsg(resp.reply, resp)
        return resp
      } catch (e) {
        // 改造 F1：用户主动停止（stopRun 已把消息标记为「已停止」，此处不再覆盖）
        if (e?.code === 'ERR_CANCELED') return null
        // ====== 改造 H3：断连安全默认 ======
        // RUN 中途断连不自动重跑(防重复副作用)。无 HTTP 响应=网络层断连,提示可重新发送;
        // 有响应但业务失败(如 PENDING_EXPIRED)则原样展示后端错误。
        const disconnected = !e?.response
        const hint = disconnected
          ? '连接中断，请检查网络后重新发送（已产生的交互状态可靠 /pending 接口查询恢复）'
          : (e?.message || '未知错误')
        this.updateLastMsg('抱歉，AI 服务调用失败：' + hint, {})
        return null
      } finally {
        currentAbort = null
        this.loading = false
      }
    },

    /**
     * 工具执行结果回灌 —— 恢复后端 agent loop。
     * @param resumeToken 来自最后一条 assistant 消息的 resumeToken
     * @param callId      对应 FrontendToolCall.callId
     * @param result      { ok, message, data? } 工具执行结果
     * @param workspaceState 改造 D3a/D4：回灌携带的最新工作区快照(含 dataVersion)，
     *                       后端与挂起时版本对比，不一致则强制 AI 现查
     */
    async submitToolResult(resumeToken, callId, result, workspaceState = null) {
      if (!resumeToken || !callId) {
        console.warn('[aiStore] submitToolResult 参数缺失:', { resumeToken, callId })
        return null
      }
      // 标记状态（取消 user_cancelled 保留 cancelled，避免误显示为"失败"）
      let _finalStatus
      if (result?.ok === false && result?.message === 'user_cancelled') _finalStatus = 'cancelled'
      else if (result?.ok === false) _finalStatus = 'failed'
      else _finalStatus = 'succeeded'
      this.setToolCallStatus(callId, _finalStatus)
      this.loading = true
      try {
        const resp = await aiApi.toolResult({ resumeToken, callId, result, workspaceState: workspaceState || undefined })
        // 同步 Phase 2 元数据到 store 顶层（新一轮可能切换 mode/scenario/step）
        if (resp.mode) this.mode = resp.mode
        if (resp.currentScenario) this.currentScenario = resp.currentScenario
        if (resp.currentStep) this.currentStep = resp.currentStep
        // 处理恢复后的响应：
        //   - done=true: loop 结束，把最终回复作为新 assistant 消息追加
        //   - done=false: 下一轮 toolCalls，追加新 assistant 消息（含工具卡片）
        if (resp.done !== false) {
          // loop 结束，最终回复作为新消息追加
          this.pushMessage({
            role: 'assistant',
            content: resp.reply || '',
            toolCalls: resp.toolCalls || [],
            actions: resp.actions || [],
            done: true,
            pending: false,
            mode: resp.mode || null,
            currentScenario: resp.currentScenario || null,
            currentStep: resp.currentStep || null
          })
        } else {
          // 下一轮工具调用，追加新消息
          this.pushMessage({
            role: 'assistant',
            content: resp.reply || '',
            toolCalls: resp.toolCalls || [],
            actions: resp.actions || [],
            resumeToken: resp.resumeToken,
            done: false,
            pending: false,
            pendingExpiresAt: resp.pendingExpiresAt || null,
            mode: resp.mode || null,
            currentScenario: resp.currentScenario || null,
            currentStep: resp.currentStep || null
          })
        }
        return resp
      } catch (e) {
        // 回灌失败：把失败信息追加为系统消息
        this.pushMessage({
          role: 'assistant',
          content: '【工具回灌失败】' + (e?.message || '未知错误'),
          done: true
        })
        return null
      } finally {
        this.loading = false
      }
    },

    async loadHistory(taskId) {
      try {
        const list = await aiApi.history(taskId, this.sessionId)
        const merged = []
        for (const m of list) {
          let toolCalls = []
          let legacyActions = []
          if (m.toolCall) {
            const parsed = safeJson(m.toolCall, null)
            if (Array.isArray(parsed) && parsed.length) {
              if (parsed[0] && typeof parsed[0].toolName === 'string') {
                toolCalls = parsed
              } else {
                legacyActions = parsed
                toolCalls = legacyActions.map(a => adaptLegacyActionToToolCall(a)).filter(Boolean)
              }
            }
          }
          merged.push({
            id: m.id || uuidv4(),
            role: m.role,
            content: m.content || '',
            toolCalls,
            actions: legacyActions,
            resumeToken: null,
            done: true,
            toolStatus: {},
            pending: false
          })
        }
        this.messages = merged
      } catch (e) { /* ignore */ }
    },

    /**
     * ====== 改造 B2：pending 刷新恢复 ======
     * 页面刷新后调用（AiPanel onMounted）：查后端 /api/ai/pending，
     * 有未过期的 WAITING_TOOL state 则重建工具交互卡片（含 resumeToken + 过期时间），
     * 用户可直接在新页面上继续确认/填表，回灌后 loop 续跑。
     * @returns {boolean} 是否恢复了 pending 交互
     */
    async recoverPending() {
      try {
        const data = await aiApi.pending(this.sessionId)
        if (!data || !data.resumeToken) return false
        // 幂等：已有相同 resumeToken 的未完成消息则跳过（防止重复挂载）
        const dup = this.messages.find(m => m.resumeToken === data.resumeToken && m.done === false)
        if (dup) return false
        const toolCalls = data.pendingToolCalls || []
        const msg = {
          role: 'assistant',
          content: data.interimContent || '（页面已刷新，恢复待处理的交互）',
          toolCalls,
          resumeToken: data.resumeToken,
          done: false,
          pending: false,
          recovered: true,
          pendingExpiresAt: data.expiresAt || null,
          mode: data.mode || null,
          currentScenario: data.currentScenario || null,
          currentStep: data.currentStep || null
        }
        this.pushMessage(msg)
        // 初始化工具状态为 pending
        const last = this.messages[this.messages.length - 1]
        for (const t of toolCalls) last.toolStatus[t.callId] = 'pending'
        if (data.mode) this.mode = data.mode
        if (data.currentScenario) this.currentScenario = data.currentScenario
        if (data.currentStep) this.currentStep = data.currentStep
        return true
      } catch (e) {
        console.warn('[aiStore] recoverPending 失败', e)
        return false
      }
    },

    /**
     * ====== 改造 F1/F2：协作式停止 ======
     * 业界共识：打断=轮次边界停止+保留现场，不删除已生成的内容、不回滚已完成的操作。
     * 实现：① AbortController 断开在途 HTTP（F1）② 调后端 /cancel 置位中断标志+作废活跃
     * WAITING_TOOL state（F2，后端 loop 下一轮边界停）③ 本地 pending 消息标记「已停止」。
     * 多步流程工具（run_flow）经 flow-cancel 标志在步骤边界提前返回（F3）。
     */
    async stopRun() {
      // ① 断开在途请求（axios 抛 ERR_CANCELED，chat/submitToolResult 已按「已停止」处理）
      try { currentAbort?.abort() } catch (e) { /* ignore */ }
      // ③ 多步流程协作式取消标志（run_flow 步骤间检查）
      requestFlowCancel()
      // ② 后端协作式取消（不可达也继续本地停止）
      try { await aiApi.cancelRun(this.sessionId) } catch (e) { /* 后端不可达也继续本地停止 */ }
      const last = this.messages[this.messages.length - 1]
      if (last && last.role === 'assistant' && (last.pending || last.done === false)) {
        last.pending = false
        last.done = true
        last.content = (last.content ? last.content + '\n' : '') + '（已停止，已完成的内容保留）'
      }
      this.loading = false
    },
    toggleExpand() {
      this.expanded = !this.expanded
    },
    setExpand(v) {
      this.expanded = v
    },
    setLastEvent(ev) {
      this.lastEvent = ev
    },
    clear() {
      this.messages = []
      // Phase 2: 清空 plan / mode / 上下文
      this.plan = []
      this.planSummary = ''
      this.planFallback = false
      this.mode = 'PLAN'
      this.currentScenario = null
      this.currentStep = null
    }
  }
})

function safeJson(str, dft) {
  try { return JSON.parse(str) } catch (e) { return dft }
}

/**
 * 会话随窗口（改造 A）：sessionId 存 sessionStorage。
 * 刷新存活（同标签页 sessionStorage 保留）、关窗自毁、多标签各自独立会话。
 */
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
