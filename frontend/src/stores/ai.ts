/**
 * AI 会话 store（状态层）。
 *
 * 形态来源：kimi-k3 `stores/ai.js`（`initSessionId` / 会话镜像 / `loadHistory` 后端对账，
 * 原 .js:25-52 与 248-301），TS 化并按本仓后端契约重写：
 * - 会话 id 存 **sessionStorage**（页签级：同页签刷新保持，新页签是新会话，DC-06③）；
 * - 刷新恢复走"镜像即时恢复 + `GET /api/ai/history/{sessionId}` 对账"两步；
 * - 409 SESSION_BUSY 不当作普通失败：记下进行中的 runId 并允许一键 reattach（ADR-5）；
 * - 断流（缺 done/error 终帧）按 ADR-6 置 `streamInterrupted` 提示"结果可能不完整"；
 * - 契约联动（S4.4c）：订阅工作区 `workspace_changed` 事件，把用户手点的动作同步进
 *   下一轮请求上下文（`buildContext().extra.recentActions`）。
 */

import { defineStore } from 'pinia'
import { ApiError } from '@/api/http'
import { aiApi } from '@/api/ai'
import type { SseFrameHandlers, SseStreamOptions } from '@/api/sse'
import { onWorkspaceEvent, useWorkspaceStore } from '@/stores/workspace'
import { executeFrontendTool, parseToolArgs } from '@/utils/frontend-tools'
import { ErrorCode, type SessionBusyConflict, type SuspendPoolSaturated } from '@/types/api'
import { frameSeq, isTerminalFrame } from '@/types/sse'
import type {
  ActiveGenerativeForm,
  AiHealth,
  AiHistory,
  AiSessionMirror,
  ChatMessage,
  PendingToolCall,
  ToolRun
} from '@/types/ai'
import { parseFormSchema, checkFormValues } from '@/types/form-schema'
import type { SseConfirmRequestFrame, SseFrame } from '@/types/sse'
import type { WorkspaceActionRecord } from '@/types/tools'

/** sessionStorage 键：会话 id 与镜像前缀。 */
export const SESSION_ID_KEY = 'ai-session-id'
export const MIRROR_PREFIX = 'ai-mirror:'

/** 当前轮的 AbortController（模块级：不进响应式状态）。 */
let currentAbort: AbortController | null = null
/** 生成式表单本地超时计时器（模块级：activeForm 被结局帧/新轮清掉时随之中和）。 */
let formExpiryTimer: ReturnType<typeof setTimeout> | null = null

function disarmFormExpiry(): void {
  if (formExpiryTimer !== null) {
    clearTimeout(formExpiryTimer)
    formExpiryTimer = null
  }
}
/** 会话镜像是否已启动（防止重复 $subscribe）。 */
let mirrorStarted = false
let mirrorTimer: ReturnType<typeof setTimeout> | null = null
/** 工作区契约事件订阅是否已启动 + 反订阅句柄。 */
let workspaceSyncStarted = false
let workspaceSyncStops: Array<() => void> = []

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

/** AI 栏展开态（页签级记忆：用户收起后刷新仍保持收起，裁剪性不被刷新打回）。 */
const EXPANDED_KEY = 'ai-panel-expanded'

function initExpanded(): boolean {
  try {
    return sessionStorage.getItem(EXPANDED_KEY) !== '0'
  } catch {
    return true
  }
}

function persistExpanded(expanded: boolean): void {
  try {
    sessionStorage.setItem(EXPANDED_KEY, expanded ? '1' : '0')
  } catch {
    // 隐私模式忽略：展开态只是展示偏好
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

/** 契约事件载荷 → 工作区动作记录（形态不符则忽略，不抛错）。 */
function readActionRecord(payload: unknown): WorkspaceActionRecord | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }
  const record = payload as Partial<WorkspaceActionRecord>
  if (typeof record.summary !== 'string' || typeof record.version !== 'number') {
    return null
  }
  return {
    version: record.version,
    summary: record.summary,
    at: typeof record.at === 'number' ? record.at : Date.now(),
    source: record.source === 'AI' ? 'AI' : '界面'
  }
}

export const useAiStore = defineStore('ai', {
  state: () => ({
    /** 页签级会话 id（记忆窗口 CONVERSATION_ID） */
    sessionId: initSessionId(),
    /** AI 面板是否展开（裁剪性：收起后业务照常；页签级记忆，刷新后保持用户的选择） */
    expanded: initExpanded(),
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
    stopped: false,
    /**
     * 断流标志（ADR-6）：本轮结束时没收到 done/error 终帧 → 提示"连接中断，结果可能不完整"。
     * 与 notice 分开，便于面板用不同级别的 Alert 呈现（notice 覆盖更多降级场景）。
     */
    streamInterrupted: false,
    /** 最近一次工作区契约事件（面板"工作区已同步"chip 的数据源） */
    contextSync: null as WorkspaceActionRecord | null,
    /** 最近一次 ui_event 分发结果（AI 驱动工作区的回执展示） */
    lastUiEventResult: null as { event: string; handled: boolean; message: string } | null,
    /**
     * GF-B（DC-15）：当前待填写的生成式表单（generative_form 特判挂起，等用户填写）。
     * 不进镜像：刷新后由 pendingCall 的 expired 降级与后端超时收敛。
     */
    activeForm: null as ActiveGenerativeForm | null,
    /**
     * 已收到的最大帧序号（DC-14 T6）：reattach 时回传，服务端只补增量。
     * 按 runId 记录 —— 换轮次（新 runId）即从零开始，不跨轮混用序号。
     */
    runSeq: {} as Record<string, number>
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
    },
    /**
     * 运行状态文案（N6 裁决：按帧口径区分）。
     * - 生成中：本轮进行中且没有挂起等待；
     * - 挂起等待：收到 suspended / confirm_request / frontend_tool_request 后；
     * - 空闲：没有进行中的轮次。
     */
    runStateLabel(state): string {
      if (!state.loading) {
        return '空闲'
      }
      return state.suspended ? '挂起等待中…（待人工确认或待前端执行）' : '生成中…'
    },
    /** 输入区是否可用（执行中/挂起等待中一律禁发）。 */
    canSend(state): boolean {
      return !state.loading
    }
  },

  actions: {
    toggleExpand(): void {
      this.expanded = !this.expanded
      persistExpanded(this.expanded)
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

    /**
     * 启动工作区契约订阅（S4.4c 契约联动）。
     *
     * 订阅两个主题：
     * - `workspace_changed`：用户在业务页面上的动作（选配置/切步骤/改条件/编辑表格…）
     *   → 面板显示"工作区已同步"chip；动作本身已由 workspace store 记入 recentActions，
     *   随下一轮 `buildContext()` 注入给模型（AI 面板不自己拼上下文，仍走窄接口）；
     * - `ui_event_result`：AI 驱动工作区动作的回执（AI→业务方向）。
     */
    startWorkspaceSync(): void {
      if (workspaceSyncStarted) {
        return
      }
      workspaceSyncStarted = true
      workspaceSyncStops.push(
        onWorkspaceEvent('workspace_changed', (payload) => {
          this.contextSync = readActionRecord(payload)
        }),
        onWorkspaceEvent('ui_event_result', (payload) => {
          const record = payload as { event?: unknown; handled?: unknown; message?: unknown } | null
          if (!record || typeof record.event !== 'string') {
            return
          }
          this.lastUiEventResult = {
            event: record.event,
            handled: record.handled === true,
            message: typeof record.message === 'string' ? record.message : ''
          }
        })
      )
    },

    /** 退订工作区事件（面板卸载时调用；测试/热更场景亦可用）。 */
    stopWorkspaceSync(): void {
      workspaceSyncStops.forEach((stop) => stop())
      workspaceSyncStops = []
      workspaceSyncStarted = false
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

    /** 发一轮消息（含渐进披露上下文注入 + 工作区动作同步）。 */
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
      this.streamInterrupted = false
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
            // ADR-6：缺 done/error 终帧 = 断流，必须显式提示"结果可能不完整"
            this.streamInterrupted = true
            this.notice = `连接中断，结果可能不完整（${info.reason ?? '未收到完成帧'}）。已生成的内容保留，可重新发送或一键重挂该轮。`
            if (this.activeForm !== null) {
              // 红队问题 2：断流是第五个收尾出口 —— 与 done/error/stop/resetSession 对齐，
              // 收表单态与本地超时计时器（刷新后由后端权威超时收敛）
              disarmFormExpiry()
              this.activeForm = null
            }
          }
        }
      }

      const options: SseStreamOptions = { signal: currentAbort.signal }
      // 契约联动：上下文里带上"用户刚手点了什么"（buildContext 的 extra.recentActions）；
      // 动作只注入这一轮，构造完即清空，避免下一轮重复携带。
      const context = workspace.buildContext()
      workspace.clearRecentActions()
      try {
        await aiApi.chat(
          { sessionId: this.sessionId, message: content, context },
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
          // 本轮压根没开始：给占位助手消息一个可读的收尾，避免留空气泡
          if (error instanceof ApiError && error.code === ErrorCode.SESSION_BUSY) {
            message.content = message.content || '（本轮未开始：该会话已有一轮正在进行，可一键重挂接收它）'
          } else if (message.content === '' && error instanceof Error) {
            message.content = `（本轮未开始：${error.message}）`
          }
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
          void this.probeHealth()
          break
        case ErrorCode.AI_REDIS_UNAVAILABLE:
          this.notice = `AI 运行时依赖的 Redis 不可用：${error.message}`
          void this.probeHealth()
          break
        default:
          this.notice = error.message
      }
    },

    /**
     * 一键 reattach：重挂进行中那一轮（ADR-5）。
     * 409 的响应体已带 reattach 路径，这里只取 runId（路径由 sse.ts 统一拼）。
     *
     * `runId` 参数做**类型防御**：UI 侧若把点击事件当参数传进来（形如 `[object PointerEvent]`），
     * 会把 reattach 打到 404 上（S4.4c E6 实测发现的缺陷），故非字符串一律忽略、落回现场 runId。
     *
     * DC-14 T6：带上该 runId 已收到的最大帧序号 —— 服务端只补发 `seq > lastSeq` 的增量
     * （本地已有的帧不重发），回放与实时续接之间不会重复渲染。序号未知（首挂/旧格式帧）则不传，
     * 服务端全量回放（行为与 S4.4c 一致）。
     */
    async reattachActive(runId?: string): Promise<void> {
      const explicit = typeof runId === 'string' && runId.trim() !== '' ? runId : null
      const targetRunId = explicit ?? this.busyConflict?.runId ?? this.activeRunId
      if (!targetRunId) {
        this.notice = '没有可重挂的轮次（缺少 runId）'
        return
      }
      const lastSeq = this.runSeq[targetRunId]
      const assistant = this.appendAssistantMessage('（正在重挂进行中的轮次…）')
      this.loading = true
      this.streamInterrupted = false
      this.notice = lastSeq === undefined
        ? '正在重挂该轮（先回放已产出的帧，再续收实时帧）…'
        : `正在重挂该轮（只补发第 ${lastSeq} 帧之后的增量）…`
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
                this.streamInterrupted = true
                this.notice = `连接中断，结果可能不完整（重挂未能收到完成帧：${info.reason ?? '断流'}）`
                if (this.activeForm !== null) {
                  // 与红队问题 2 同构：重挂断流同样是收尾出口，不能只置通知而漏收表单态
                  // 与本地超时计时器（刷新后由后端权威超时收敛）
                  disarmFormExpiry()
                  this.activeForm = null
                }
              }
            }
          },
          { signal: currentAbort.signal, lastSeq }
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

    /**
     * 前端工具结果回灌（幂等：重复 → 409，未知 → 409）。
     * GF-B：cancelled=true 为取消路径（GFa 裁决 #2，可选字段，与提交共用同一入口），
     * 用户放弃 / 渲染器不可用时回灌让挂起立刻落 FRONTEND_CANCELLED，不必等超时。
     *
     * 返回结构化结果（供生成式表单区分 400 FORM_RESULT_REJECTED「挂起仍在等待，可重试」
     * 与真失败）；通用执行器路径（runFrontendTool）忽略返回值，行为不变。
     */
    async submitFrontendToolResult(
      toolCallId: string,
      result: string,
      source = 'http-post',
      cancelled = false,
      runId?: string
    ): Promise<{ ok: boolean; duplicate?: boolean; rejected?: string; message?: string }> {
      // GF-B 修复（红队问题 1）：表单回灌优先用表单创建时捕获的 runId，
      // 缺省才回退当前轮的 activeRunId（避免旧表单错配到新 run）
      const targetRunId = runId ?? this.activeRunId
      if (!targetRunId) {
        this.notice = '缺少 runId，无法回灌前端工具结果'
        return { ok: false, message: '缺少 runId' }
      }
      try {
        await aiApi.frontendToolResult(
          cancelled
            ? { runId: targetRunId, toolCallId, cancelled: true, source }
            : { runId: targetRunId, toolCallId, result, source }
        )
        return { ok: true }
      } catch (error) {
        if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
          this.notice = cancelled ? '该工具调用已是终态（幂等拒绝）' : '该工具结果已回灌过（幂等拒绝）'
          return { ok: false, duplicate: true }
        }
        if (error instanceof ApiError && error.code === ErrorCode.FORM_RESULT_REJECTED) {
          // 400 + status:"PENDING"：挂起仍在等待 —— 不置 notice 打断填写，由表单卡展示拒绝原因，
          // 用户改值后用同一 toolCallId 重发，或改发取消
          const reasons = error.bodyAs<{ reasons?: unknown }>()?.reasons
          const detail = Array.isArray(reasons) && reasons.length > 0
            ? reasons.map((item) => String(item)).join('；')
            : error.message
          return { ok: false, rejected: detail }
        }
        const message = error instanceof Error ? error.message : '前端工具结果回灌失败'
        this.notice = message
        return { ok: false, message }
      }
    },

    /**
     * GF-B：generative_form 帧到达 → 防御性解析 schema。
     * 合法：挂到会话态进"等待用户填写"（AiPanel 渲染 FormRenderer），并按帧带时限武装本地超时；
     * 非法 / 缺 runId（渲染器不可用的任何异常路径）：带 cancelled:true 回灌，挂起立刻收敛
     * （GFa §8.3 / 裁决 #3），绝不让通用执行器回灌说明文本。
     */
    openGenerativeForm(
      message: ChatMessage,
      frame: Extract<SseFrame, { type: 'frontend_tool_request' }>
    ): void {
      disarmFormExpiry()
      const args = message.pendingCall?.args ?? parseToolArgs(frame.args)
      const spec = parseFormSchema(args?.form)
      const runId = frame.runId ?? this.activeRunId
      if (!spec || !runId) {
        this.upsertToolRun(message, {
          toolCallId: frame.toolCallId,
          name: frame.name,
          kind: 'FRONTEND',
          status: 'failed',
          result: '表单 schema 解析失败（或缺少 runId），前端无法渲染，已带 cancelled:true 回灌收敛（GFa §8.3）'
        })
        void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true, runId ?? undefined)
        return
      }
      const deadline = typeof frame.expiresAt === 'number' && Number.isFinite(frame.expiresAt)
        ? frame.expiresAt
        : frame.timeoutSeconds
          ? Date.now() + frame.timeoutSeconds * 1000
          : null
      this.activeForm = {
        toolCallId: frame.toolCallId,
        runId,
        form: spec,
        status: 'filling',
        error: null,
        deadline
      }
      if (deadline !== null) {
        formExpiryTimer = setTimeout(() => {
          formExpiryTimer = null
          // 本地判过期：收起表单并带 cancelled:true 回灌（409 幂等拒绝按现有口径忽略）
          void this.cancelGenerativeForm(true)
        }, Math.max(0, deadline - Date.now()))
      }
    },

    /** GF-B：提交表单值 —— result 为字段 key→值的 JSON 字符串；客户端复核不过就地提示不发出。 */
    async submitGenerativeForm(values: Record<string, unknown>): Promise<void> {
      const form = this.activeForm
      if (!form || form.status !== 'filling') {
        return
      }
      const problems = checkFormValues(form.form, values)
      if (problems.length > 0) {
        form.error = problems.join('；')
        return
      }
      form.status = 'submitting'
      form.error = null
      const outcome = await this.submitFrontendToolResult(form.toolCallId, JSON.stringify(values), 'http-post', false, form.runId)
      if (this.activeForm?.toolCallId !== form.toolCallId) {
        // 结局帧/新表单已收敛：不写回过期状态（红队问题 6，防瞬时覆写）
        return
      }
      if (outcome.ok || outcome.duplicate) {
        form.status = 'submitted'
        return
      }
      if (outcome.rejected) {
        // 400 FORM_RESULT_REJECTED：挂起仍在等待 —— 回填写态，同一 toolCallId 可改值重发或取消
        form.status = 'filling'
        form.error = `提交被后端拒绝（挂起仍在等待）：${outcome.rejected}`
        return
      }
      form.status = 'filling'
      // 红队问题 4：泛化文案并入后端具体原因，表单卡内可见真实失败点
      form.error = outcome.message
        ? `提交失败：${outcome.message}（可重试或点「取消」放弃）`
        : '提交失败，请重试或点「取消」放弃'
    },

    /** GF-B：显式取消（或本地超时自动取消）—— 带 cancelled:true 回灌，挂起立刻收敛。 */
    async cancelGenerativeForm(auto = false): Promise<void> {
      const form = this.activeForm
      if (!form || form.status === 'cancelled' || form.status === 'submitted') {
        return
      }
      form.status = 'cancelled'
      disarmFormExpiry()
      const outcome = await this.submitFrontendToolResult(form.toolCallId, '', 'http-post', true, form.runId)
      if (this.activeForm?.toolCallId !== form.toolCallId) {
        // 结局帧/新表单已收敛：错误提示不写进过期表单（红队问题 6）
        return
      }
      if (!outcome.ok && !outcome.duplicate) {
        form.error = `${auto ? '表单已超时，' : ''}取消回灌未送达（${outcome.message ?? '网络异常'}），后端将在超时后自动收尾`
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
      if (this.activeForm !== null) {
        // 用户停止本轮：表单随轮收起（后端取消会让挂起走 TIMEOUT/取消收尾）
        disarmFormExpiry()
        this.activeForm = null
      }
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
      disarmFormExpiry()
      this.activeForm = null
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

      // T6：按 runId 记录最大帧序号（reattach 时回传，服务端只补增量）
      const seq = frameSeq(frame)
      if (seq !== null) {
        const runId = frame.runId ?? this.activeRunId
        if (runId) {
          this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, seq)
        }
      }

      switch (frame.type) {
        case 'start':
          this.activeRunId = frame.runId
          this.notice = null
          break
        case 'message_start':
          // T5 三段式的 start：本条消息的边界开始了（正文/思考段共用同一套帧）
          message.messageId = frame.messageId
          message.streaming = true
          break
        case 'message_end':
          // T5 三段式的 end：本段内容到此为止（段末不关整条消息 —— 工具调用后可能还有下一段）
          message.messageId = frame.messageId
          break
        case 'delta':
          message.content += frame.text
          message.streaming = true
          break
        case 'reasoning':
          // 裁决⑦：有 reasoning 帧才渲染思考链（无帧则 message.reasoning 恒空 → 面板不显示折叠块）
          message.reasoning += frame.text
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
          // 补丁③：终态区分显示 —— 被人工拒绝（REJECTED）保持「已拒绝」（warning 色），
          // 不让紧随其后的 tool_result(ok=false) 把它覆盖成「执行失败」（danger 色）；
          // 真失败（后端执行异常）才落 failed。
          const existing = message.toolRuns.find((run) => run.toolCallId === frame.toolCallId)
          const rejected = existing?.status === 'rejected'
          // DC-14 T1：防线③拦截（BLOCKED）也是"未执行"，展示口径与"被拒绝"同类（warning 色 +
          // 说明原因），不与后端执行失败（danger 色）混用 —— 三者语义不同，用户要能一眼分清。
          const blocked = frame.status === 'BLOCKED' || existing?.status === 'blocked'
          this.upsertToolRun(message, {
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: frame.kind,
            status: blocked ? 'blocked' : rejected ? 'rejected' : frame.ok ? 'succeeded' : 'failed',
            messageId: frame.messageId,
            result: frame.result ?? null,
            reason: blocked
              ? '该工具不在当前工作区上下文的披露范围内，未执行'
              : rejected ? (existing?.reason ?? '用户拒绝执行') : null
          })
          break
        }
        case 'confirm_request': {
          this.suspended = true
          message.pendingCall = this.toPendingCall(frame, 'CONFIRM')
          // 工具卡四态之"等待确认"：确认门未决期间，该工具卡显示为等待确认
          this.upsertToolRun(message, {
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: 'CONFIRM',
            status: 'pending',
            args: frame.args
          })
          break
        }
        case 'confirm_decision': {
          const pending = message.pendingCall
          if (pending && pending.toolCallId === frame.toolCallId) {
            pending.status = frame.approved ? 'approved' : 'rejected'
          }
          if (frame.approved) {
            // 放行后工具立刻进入执行（工具卡转"调用中"，结果帧随后落定）
            this.upsertToolRun(message, {
              toolCallId: frame.toolCallId,
              name: frame.name,
              kind: 'CONFIRM',
              status: 'running'
            })
          } else {
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
          if (frame.name === 'generative_form') {
            // GF-B：表单挂起走渲染器通道等用户填写 —— 绝不能让通用执行器兜底
            // （那段说明文本不是值 JSON，会被闸门 400 拒、挂起拖到超时，GFa §8.3）
            this.openGenerativeForm(message, frame)
            break
          }
          // 前端工具没有 HITL：立即在浏览器执行并回灌结果
          void this.runFrontendTool(message, frame)
          break
        }
        case 'frontend_tool_result': {
          // 前端工具的真执行者就是浏览器：后端这帧只是"前端回灌已收到"的回执
          // （ok 恒由后端按"收到回灌"置 true），因此**不能**用它把前端自己判定的失败
          // 改写成"已成功"（补丁③配套：本地已失败/已拒绝则保持原终态）。
          const existing = message.toolRuns.find((run) => run.toolCallId === frame.toolCallId)
          const localTerminal = existing?.status === 'failed' || existing?.status === 'rejected'
            || existing?.status === 'blocked'
          this.upsertToolRun(message, {
            toolCallId: frame.toolCallId,
            name: frame.name,
            kind: 'FRONTEND',
            status: localTerminal ? (existing?.status ?? 'failed') : frame.ok ? 'succeeded' : 'failed',
            messageId: frame.messageId,
            result: frame.result ?? null
          })
          if (message.pendingCall?.toolCallId === frame.toolCallId) {
            message.pendingCall = null
          }
          if (this.activeForm?.toolCallId === frame.toolCallId) {
            // 结局帧已到：表单收敛（提交/取消/超时皆自此收尾）
            disarmFormExpiry()
            this.activeForm = null
          }
          this.suspended = false
          break
        }
        case 'suspended': {
          // 后端对**每一批工具调用**都会先发 suspended（把挂起态外置，见 SpToolCallingManager），
          // 其中只有 CONFIRM/FRONTEND 两类需要人工或浏览器介入：BACKEND 工具随即执行完毕，
          // 不该被判成"挂起等待"（否则运行状态与输入区提示会误报）。
          const waiting = frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
          if (waiting) {
            this.suspended = true
            message.pendingCall = {
              toolCallId: waiting.toolCallId,
              name: waiting.name,
              kind: waiting.kind === 'FRONTEND' ? 'FRONTEND' : 'CONFIRM',
              args: parseToolArgs(waiting.arguments),
              rawArgs: waiting.arguments,
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
          if (this.activeForm !== null) {
            // 轮次收尾时表单未决（如后端侧超时/放弃）：本地一并收起，计时器随之中和
            disarmFormExpiry()
            this.activeForm = null
          }
          this.suspended = false
          if (frame.cancelled) {
            this.notice = '本轮已取消'
          }
          break
        }
        case 'error': {
          message.streaming = false
          message.done = true
          if (this.activeForm !== null) {
            disarmFormExpiry()
            this.activeForm = null
          }
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
        // T4：绝对到期时刻（服务端给）；缺字段时由倒计时组件回落到 timeoutSeconds
        expiresAt: frame.expiresAt,
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
