/**
 * AI 会话 store（状态层）。
 *
 * 形态来源：kimi-k3 `stores/ai.js`（`initSessionId` / 会话镜像 / `loadHistory` 后端对账，
 * 原 .js:25-52 与 248-301），TS 化并按本仓后端契约重写：
 * - 会话 id 存 **sessionStorage**（页签级：同页签刷新保持，新页签是新会话，DC-06③）；
 * - 刷新恢复走"镜像即时恢复 + `GET /api/ai/history/{sessionId}` 对账"两步；
 * - 409 SESSION_BUSY 不当作普通失败：记下进行中的 runId 并允许一键 reattach（ADR-5）。
 *
 * 本棒（a 棒）只落状态层：面板 UI 与真实对话交互由 b/c 棒接。
 */

import { defineStore } from 'pinia'
import { ApiError } from '@/api/http'
import { aiApi } from '@/api/ai'
import type { SseFrameHandlers, SseStreamOptions } from '@/api/sse'
import { useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool, parseToolArgs } from '@/utils/frontend-tools'
import { ErrorCode, type SessionBusyConflict, type SuspendPoolSaturated } from '@/types/api'
import { isTerminalFrame } from '@/types/sse'
import type {
  AiHealth,
  AiHistory,
  AiSessionMirror,
  ChatMessage,
  PendingToolCall,
  ToolRun
} from '@/types/ai'
import type { SseConfirmRequestFrame, SseFrame } from '@/types/sse'

/** sessionStorage 键：会话 id 与镜像前缀。 */
export const SESSION_ID_KEY = 'ai-session-id'
export const MIRROR_PREFIX = 'ai-mirror:'

/** 当前轮的 AbortController（模块级：不进响应式状态）。 */
let currentAbort: AbortController | null = null
/** 会话镜像是否已启动（防止重复 $subscribe）。 */
let mirrorStarted = false
let mirrorTimer: ReturnType<typeof setTimeout> | null = null

/** 生成页签级会话 id（crypto.randomUUID 在非安全上下文不可用，故留回退）。 */
export function createSessionId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    // 落回退实现
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** 取/建页签会话 id。 */
function initSessionId(): string {
  try {
    const stored = sessionStorage.getItem(SESSION_ID_KEY)
    if (stored) {
      return stored
    }
    const created = createSessionId()
    sessionStorage.setItem(SESSION_ID_KEY, created)
    return created
  } catch {
    return createSessionId()
  }
}

function readMirror(sessionId: string): AiSessionMirror | null {
  try {
    const raw = sessionStorage.getItem(MIRROR_PREFIX + sessionId)
    if (!raw) {
      return null
    }
    return JSON.parse(raw) as AiSessionMirror
  } catch {
    return null
  }
}

function writeMirror(sessionId: string, messages: ChatMessage[], lastRunId: string | null): void {
  try {
    const mirror: AiSessionMirror = {
      savedAt: Date.now(),
      lastRunId,
      // 镜像只留展示所需字段（pendingCall 内嵌其中，刷新后可重建确认卡）
      messages: messages.map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
        reasoning: message.reasoning,
        toolRuns: message.toolRuns,
        pendingCall: message.pendingCall
      }))
    }
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify(mirror))
  } catch {
    // 配额/隐私模式等场景忽略：镜像是刷新体验优化，不是正确性依赖
  }
}

/** 历史消息 type → 展示角色。 */
function roleOf(type: string): ChatMessage['role'] {
  const normalized = (type || '').toUpperCase()
  if (normalized === 'USER') {
    return 'user'
  }
  if (normalized === 'SYSTEM') {
    return 'system'
  }
  return 'assistant'
}

export const useAiStore = defineStore('ai', {
  state: () => ({
    /** 页签级会话 id（记忆窗口 CONVERSATION_ID） */
    sessionId: initSessionId(),
    /** AI 面板是否展开（裁剪性：收起后业务照常） */
    expanded: true,
    messages: [] as ChatMessage[],
    /** 是否有轮次在进行 */
    loading: false,
    /** 当前轮 runId（来自 start 帧） */
    activeRunId: null as string | null,
    /** 是否处于挂起等待（suspended / confirm_request 帧口径，N6 裁决） */
    suspended: false,
    /** 连接/降级提示（断流、重连、池饱和、无 key 等） */
    notice: null as string | null,
    /** 409 SESSION_BUSY 的现场（可一键 reattach） */
    busyConflict: null as SessionBusyConflict | null,
    /** 503 挂起池饱和现场 */
    poolSaturated: null as SuspendPoolSaturated | null,
    /** AI 可用性 */
    health: null as AiHealth | null,
    healthError: null as string | null,
    /** 历史对账是否完成（UI 可用它做骨架屏） */
    historyLoaded: false,
    /** 本轮是否已被用户停止 */
    stopped: false
  }),

  getters: {
    /** 当前待处理（待确认 / 待前端执行）的工具调用。 */
    pendingToolCall(state): PendingToolCall | null {
      for (let i = state.messages.length - 1; i >= 0; i -= 1) {
        const message = state.messages[i]
        if (!message) {
          continue
        }
        if (message.role === 'assistant' && message.pendingCall && message.pendingCall.status === 'pending') {
          return message.pendingCall
        }
        if (message.role === 'assistant' && message.pendingCall) {
          return null
        }
      }
      return null
    },
    /** 需要人工放行的确认门（DANGER 工具）。 */
    pendingConfirm(): PendingToolCall | null {
      const pending = this.pendingToolCall as PendingToolCall | null
      return pending && pending.kind === 'CONFIRM' ? pending : null
    },
    /** AI 侧是否可用（无 key / 无 Redis 时降级提示）。 */
    available(state): boolean {
      return state.health?.available ?? false
    },
    /** 降级原因文案（不可用时）。 */
    unavailableReason(state): string | null {
      if (state.health?.available) {
        return null
      }
      if (state.healthError) {
        return state.healthError
      }
      return state.health?.message ?? null
    }
  },

  actions: {
    toggleExpand(): void {
      this.expanded = !this.expanded
    },

    /** 启动会话镜像：messages 任何变化（含流式 token）防抖写入 sessionStorage。 */
    startMirror(): void {
      if (mirrorStarted) {
        return
      }
      mirrorStarted = true
      this.$subscribe(() => {
        if (mirrorTimer !== null) {
          clearTimeout(mirrorTimer)
        }
        mirrorTimer = setTimeout(() => writeMirror(this.sessionId, this.messages, this.activeRunId), 300)
      }, { detached: true, deep: true })
    },

    /** 探测 AI 可用性（不可用时后端 503 + 同一个体，属正常语义）。 */
    async probeHealth(): Promise<void> {
      try {
        this.health = await aiApi.health()
        this.healthError = null
      } catch (error) {
        this.health = null
        this.healthError = error instanceof Error ? error.message : 'AI 可用性探测失败'
      }
    },

    /**
     * 进入页面时恢复历史（刷新不丢）：
     * 1) 先按 sessionStorage 镜像即时恢复展示；
     * 2) 再与 `GET /api/ai/history/{sessionId}` 对账 —— 后端有内容以后端为准，
     *    后端为空（重启/TTL 过期）则保留镜像，但把挂起的工具调用降级为 expired
     *    （旧会话已无法回灌续跑，用户发新消息即自动按新会话重建）。
     */
    async loadHistory(): Promise<void> {
      this.startMirror()
      const mirror = readMirror(this.sessionId)
      if (mirror && Array.isArray(mirror.messages) && mirror.messages.length > 0) {
        this.messages = mirror.messages.map((message) => ({
          ...message,
          streaming: false,
          done: true
        }))
        this.activeRunId = mirror.lastRunId ?? null
      }

      let history: AiHistory
      try {
        history = await aiApi.history(this.sessionId)
      } catch {
        this.historyLoaded = true
        return // 后端不可达：保留镜像展示
      }

      if (!history.messages || history.messages.length === 0) {
        for (const message of this.messages) {
          if (message.pendingCall && message.pendingCall.status === 'pending') {
            message.pendingCall = { ...message.pendingCall, status: 'expired' }
          }
        }
        this.historyLoaded = true
        return
      }

      this.messages = history.messages.map((item, index) => {
        const role = roleOf(item.type)
        const toolRuns: ToolRun[] = (item.toolCalls ?? []).map((call) => ({
          toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
          name: call.name ?? '未知工具',
          kind: 'UNKNOWN',
          status: 'succeeded',
          args: call.arguments,
          result: (item.toolResponses ?? []).find((response) => response.id === call.id)?.responseData ?? null
        }))
        return {
          id: `history-${index}`,
          role,
          content: item.text ?? '',
          reasoning: '',
          toolRuns,
          pendingCall: null,
          streaming: false,
          done: true
        }
      })
      // 历史里若存在未决挂起，补一张待处理卡片（前端工具/确认门都可能出现在这里）
      this.historyLoaded = true
    },

    /** 发一轮消息（含渐进披露上下文注入）。 */
    async send(text: string): Promise<void> {
      const content = text.trim()
      if (!content || this.loading) {
        return
      }
      const workspace = useWorkspaceStore()
      this.recordUserMessage(content)
      const assistant = this.appendAssistantMessage()
      this.loading = true
      this.stopped = false
      this.notice = null
      this.busyConflict = null
      this.poolSaturated = null
      currentAbort = new AbortController()

      const handlers: SseFrameHandlers = {
        onFrame: (frame) => this.handleFrame(frame, assistant.id),
        onReconnect: (attempt, delayMs) => {
          this.notice = `连接中断，${Math.round(delayMs / 1000)} 秒后重挂（第 ${attempt} 次）`
        },
        onClose: (info) => {
          if (!info.terminal) {
            this.notice = `本轮未收到完成帧（${info.reason ?? '断流'}）。历史已保留，可重新发送或重挂该轮。`
          }
        }
      }

      const options: SseStreamOptions = { signal: currentAbort.signal }
      try {
        await aiApi.chat(
          { sessionId: this.sessionId, message: content, context: workspace.buildContext() },
          handlers,
          options
        )
      } catch (error) {
        this.handleChatError(error, assistant.id)
      } finally {
        this.loading = false
        this.suspended = false
        currentAbort = null
        const target = this.messages.find((message) => message.id === assistant.id)
        if (target && !target.done) {
          target.streaming = false
          target.done = true
        }
      }
    },

    /** 处理 409/503 等开流前失败（按错误码给不同文案与动作）。 */
    handleChatError(error: unknown, assistantId?: string): void {
      if (assistantId) {
        const message = this.messages.find((item) => item.id === assistantId)
        if (message) {
          message.streaming = false
          message.done = true
        }
      }
      if (!(error instanceof ApiError)) {
        this.notice = error instanceof Error ? error.message : '对话请求失败'
        return
      }
      switch (error.code) {
        case ErrorCode.SESSION_BUSY: {
          const body = error.bodyAs<SessionBusyConflict>()
          this.busyConflict = body
          this.notice = body
            ? `该会话已有一轮进行中（runId=${body.runId}），可一键重挂继续接收。`
            : '该会话已有一轮进行中，可尝试重挂。'
          break
        }
        case ErrorCode.SUSPEND_POOL_SATURATED: {
          const body = error.bodyAs<SuspendPoolSaturated>()
          this.poolSaturated = body
          this.notice = body
            ? `挂起池已饱和（容量 ${body.poolSize}），请稍后重试。`
            : '挂起池已饱和，请稍后重试。'
          break
        }
        case ErrorCode.AI_UNAVAILABLE:
          this.notice = `AI 未启用：${error.message}`
          break
        case ErrorCode.AI_REDIS_UNAVAILABLE:
          this.notice = `AI 运行时依赖的 Redis 不可用：${error.message}`
          break
        default:
          this.notice = error.message
      }
    },

    /**
     * 一键 reattach：重挂进行中那一轮（ADR-5）。
     * 409 的响应体已带 reattach 路径，这里只取 runId（路径由 sse.ts 统一拼）。
     */
    async reattachActive(runId?: string): Promise<void> {
      const targetRunId = runId ?? this.busyConflict?.runId ?? this.activeRunId
      if (!targetRunId) {
        this.notice = '没有可重挂的轮次（缺少 runId）'
        return
      }
      const assistant = this.appendAssistantMessage('（正在重挂进行中的轮次…）')
      this.loading = true
      currentAbort = new AbortController()
      try {
        await aiApi.reattachRun(
          targetRunId,
          {
            onFrame: (frame) => this.handleFrame(frame, assistant.id),
            onReconnect: (attempt, delayMs) => {
              this.notice = `重挂中断，${Math.round(delayMs / 1000)} 秒后第 ${attempt} 次重试`
            },
            onClose: (info) => {
              if (!info.terminal) {
                this.notice = `重挂未能收到完成帧（${info.reason ?? '断流'}）`
              }
            }
          },
          { signal: currentAbort.signal }
        )
        this.busyConflict = null
      } catch (error) {
        this.notice = error instanceof Error ? error.message : '重挂失败'
      } finally {
        this.loading = false
        this.suspended = false
        currentAbort = null
      }
    },

    /** HITL 确认/拒绝（双路径：approved=true 放行，false 拒绝并回填原因）。 */
    async confirm(approved: boolean, reason?: string): Promise<void> {
      const pending = this.pendingToolCall
      const runId = this.activeRunId
      if (!pending || !runId) {
        this.notice = '没有待处理的确认请求'
        return
      }
      pending.status = approved ? 'running' : 'rejected'
      try {
        await aiApi.confirm({ runId, toolCallId: pending.toolCallId, approved, reason: reason ?? null })
        this.suspended = false
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          this.notice = '该确认已提交过（幂等拒绝），无需重复操作'
          pending.status = 'approved'
          return
        }
        pending.status = 'pending'
        this.notice = error instanceof Error ? error.message : '确认提交失败'
      }
    },

    /** 前端工具结果回灌（幂等：重复 → 409，未知 → 409）。 */
    async submitFrontendToolResult(toolCallId: string, result: string, source = 'http-post'): Promise<void> {
      const runId = this.activeRunId
      if (!runId) {
        this.notice = '缺少 runId，无法回灌前端工具结果'
        return
      }
      try {
        await aiApi.frontendToolResult({ runId, toolCallId, result, source })
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          this.notice = '该工具结果已回灌过（幂等拒绝）'
          return
        }
        this.notice = error instanceof Error ? error.message : '前端工具结果回灌失败'
      }
    },

    /** 停止本轮：先断本地连接，再通知后端取消（后端不可达也不影响本地停止）。 */
    async stop(): Promise<void> {
      this.stopped = true
      try {
        currentAbort?.abort()
      } catch {
        // 忽略：中止失败不影响后续取消请求
      }
      const runId = this.activeRunId
      this.loading = false
      if (!runId) {
        return
      }
      try {
        await aiApi.cancel(runId)
        this.notice = '已请求取消本轮对话'
      } catch (error) {
        this.notice = error instanceof Error ? `取消请求失败：${error.message}` : '取消请求失败'
      }
    },

    /** 重置会话（新页签语义：清 sessionId 与镜像）。 */
    resetSession(): void {
      try {
        sessionStorage.removeItem(MIRROR_PREFIX + this.sessionId)
        sessionStorage.setItem(SESSION_ID_KEY, createSessionId())
      } catch {
        // 隐私模式下忽略
      }
      this.messages = []
      this.activeRunId = null
      this.notice = null
      this.busyConflict = null
      this.poolSaturated = null
      this.suspended = false
    },

    // ── 内部：消息与帧处理 ─────────────────────────────────────────────────

    recordUserMessage(content: string): ChatMessage {
      const message: ChatMessage = {
        id: `u-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        role: 'user',
        content,
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: false,
        done: true
      }
      this.messages.push(message)
      return message
    },

    appendAssistantMessage(initial = ''): ChatMessage {
      const message: ChatMessage = {
        id: `a-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        role: 'assistant',
        content: initial,
        reasoning: '',
        toolRuns: [],
        pendingCall: null,
        streaming: true,
        done: false
      }
      this.messages.push(message)
      return message
    },

    /** 当前正在流式输出的助手消息（没有则新建）。 */
    currentAssistantMessage(): ChatMessage {
      for (let i = this.messages.length - 1; i >= 0; i -= 1) {
        const message = this.messages[i]
        if (!message) {
          continue
        }
        if (message.role === 'assistant' && !message.done) {
          return message
        }
      }
      return this.appendAssistantMessage()
    },

    /** SSE 帧 → store（判别联合 switch，覆盖后端全部帧类型）。 */
    handleFrame(frame: SseFrame, assistantId?: string): void {
      const message = assistantId
        ? this.messages.find((item) => item.id === assistantId) ?? this.currentAssistantMessage()
        : this.currentAssistantMessage()

      switch (frame.type) {
        case 'start':
          this.activeRunId = frame.runId
          this.notice = null
          break
        case 'delta':
          message.content += frame.text
          message.streaming = true
          break
        case 'tool_start': {
          message.toolRuns.push({
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: frame.kind,
            status: 'running',
            args: frame.args
          })
          break
        }
        case 'tool_result': {
          this.upsertToolRun(message, {
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: frame.kind,
            status: frame.ok ? 'succeeded' : 'failed',
            result: frame.result ?? null
          })
          break
        }
        case 'confirm_request': {
          this.suspended = true
          message.pendingCall = this.toPendingCall(frame, 'CONFIRM')
          break
        }
        case 'confirm_decision': {
          const pending = message.pendingCall
          if (pending && pending.toolCallId === frame.toolCallId) {
            pending.status = frame.approved ? 'approved' : 'rejected'
          }
          if (!frame.approved) {
            this.upsertToolRun(message, {
              toolCallId: frame.toolCallId,
              name: frame.name,
              kind: 'CONFIRM',
              status: 'rejected',
              reason: frame.reason ?? '用户拒绝'
            })
          }
          this.suspended = false
          break
        }
        case 'frontend_tool_request': {
          this.suspended = true
          message.pendingCall = this.toPendingCall(frame, 'FRONTEND')
          // 前端工具没有 HITL：立即在浏览器执行并回灌结果
          void this.runFrontendTool(message, frame)
          break
        }
        case 'frontend_tool_result': {
          this.upsertToolRun(message, {
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: 'FRONTEND',
            status: frame.ok ? 'succeeded' : 'failed',
            result: frame.result ?? null
          })
          if (message.pendingCall?.toolCallId === frame.toolCallId) {
            message.pendingCall = null
          }
          this.suspended = false
          break
        }
        case 'suspended': {
          this.suspended = true
          this.notice = `本轮已挂起外置（可重挂：runId=${frame.runId}）`
          const first = frame.toolCalls[0]
          if (first) {
            message.pendingCall = {
              toolCallId: first.toolCallId,
              name: first.name,
              kind: first.kind === 'FRONTEND' ? 'FRONTEND' : 'CONFIRM',
              args: parseToolArgs(first.arguments),
              rawArgs: first.arguments,
              status: 'pending'
            }
          }
          break
        }
        case 'heartbeat':
          // 心跳只用于保活与倒计时，不产生展示内容
          break
        case 'retry':
          this.notice = '上游连接中断，正在重试（本轮尚未产出内容）'
          break
        case 'done': {
          message.streaming = false
          message.done = true
          message.pendingCall = message.pendingCall?.status === 'pending' ? null : message.pendingCall
          this.suspended = false
          if (frame.cancelled) {
            this.notice = '本轮已取消'
          }
          break
        }
        case 'error': {
          message.streaming = false
          message.done = true
          this.notice = `本轮出错（${frame.code}）：${frame.message}`
          break
        }
        default:
          // 联合类型已穷尽；运行时多出的未知帧（后端加帧）在此静默忽略
          break
      }

      if (isTerminalFrame(frame)) {
        this.loading = false
      }
    },

    /** 把挂起请求帧转成展示态挂起调用。 */
    toPendingCall(
      frame: SseConfirmRequestFrame | Extract<SseFrame, { type: 'frontend_tool_request' }>,
      kind: 'CONFIRM' | 'FRONTEND'
    ): PendingToolCall {
      return {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind,
        args: parseToolArgs(frame.args),
        rawArgs: frame.args,
        timeoutSeconds: frame.timeoutSeconds,
        status: 'pending'
      }
    },

    /** 同一 toolCallId 的工具卡就地更新，避免重复卡片。 */
    upsertToolRun(message: ChatMessage, run: ToolRun): void {
      const index = message.toolRuns.findIndex((item) => item.toolCallId === run.toolCallId)
      if (index >= 0) {
        const existing = message.toolRuns[index]
        if (existing) {
          message.toolRuns.splice(index, 1, { ...existing, ...run })
        }
        return
      }
      message.toolRuns.push(run)
    },

    /** 执行前端工具并把结果回灌（三级可用性兜底在 utils/frontend-tools.ts）。 */
    async runFrontendTool(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): Promise<void> {
      const execution = await executeFrontendTool({
        name: frame.name,
        toolCallId: frame.toolCallId,
        args: parseToolArgs(frame.args)
      })
      this.upsertToolRun(message, {
        toolCallId: frame.toolCallId,
        name: frame.name,
        kind: 'FRONTEND',
        status: execution.ok ? 'succeeded' : 'failed',
        result: execution.result
      })
      if (execution.availability === 'degraded' || execution.availability === 'unavailable') {
        this.notice = execution.result
      }
      await this.submitFrontendToolResult(frame.toolCallId, execution.result, 'frontend-executor')
    }
  }
})
