/**
 * 生成式表单的**浏览器草稿镜像**（GF 优化批 G-B）。
 *
 * <h2>口径（用户逐条裁定，不得偏离）</h2>
 * <ul>
 * <li>只存 <b>sessionStorage</b>：按 {@code toolCallId} 一键一份，刷新后能在同一页签恢复；</li>
 * <li><b>永不进模型上下文</b>：草稿只在本地读写，回灌（{@code POST /api/ai/frontend-tool-result}）
 * 只发生在用户点"提交"那一次；</li>
 * <li><b>永不写服务器</b>：没有任何上传/持久化接口；</li>
 * <li>提交 / 取消 / 终态即清（{@link clearFormDraft}），不留残留；换会话再按
 * {@link pruneFormDrafts} 的前缀 + 在场白名单清孤儿键（绝不整份 clear）；</li>
 * <li><b>不逐击键同步写</b>（F-1）：键入期间按 {@link scheduleFormDraft} 合并，约
 * {@link FORM_DRAFT_DEBOUNCE_MS} 一次落盘；提交 / 卸载 / pagehide / 隐藏页等出口用
 * {@link flushFormDraft} / {@link flushAllFormDrafts} 把**最终值**写实，草稿值本身不截断、
 * 不做任何长度裁剪。</li>
 * </ul>
 *
 * <p>为什么不用 localStorage：草稿是"这一次填写"的临时态，跨页签/跨天恢复会带来
 * "打开表单看到上次的脏值"的困惑，也扩大了敏感输入在本机的驻留面。
 */

import { AI_FORM_DRAFT_PREFIX, formDraftPrefix, formDraftStorageKey } from '@/utils/ai-identity'
import { logCaughtError } from '@/utils/log-caught-error'

export const FORM_DRAFT_PREFIX = AI_FORM_DRAFT_PREFIX

/**
 * 草稿镜像键（按 toolCallId 隔离；同会话多张表单互不覆盖）。
 *
 * <p>Task 4：键随**当前身份**加 `tenantId:userId` 指纹（`cm.ai.form.draft.<t>:<u>.<toolCallId>`），
 * 不同身份既看不到也清不掉彼此的草稿；尚无身份时保持旧键形（未接租户目录的部署/单测）。
 */
export function formDraftKey(toolCallId: string): string {
  return formDraftStorageKey(toolCallId)
}

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch (error) {
      logCaughtError(error, 'utils/form-draft.ts:40')
    // 隐私模式/被禁用：草稿能力静默降级（表单照常可填，只是刷新不恢复）
    return null
  }
}

/**
 * 键入期间的合并窗口（毫秒）。
 *
 * <p>250ms：低于此值的连续键入（中文输入法的组字、长文粘贴后的补字）合并成一次落盘；
 * 用户停手 250ms 内一定写实，刷新恢复不会看到旧值。
 */
export const FORM_DRAFT_DEBOUNCE_MS = 250

interface PendingDraftWrite {
  toolCallId: string
  /** 最新一次的值（合并只保留最后一笔，**原样保存、不截断**）。 */
  values: Record<string, unknown>
  timer: ReturnType<typeof setTimeout> | null
}

/** 尚未落盘的最新值：键 = 存储键（不同身份的同一 toolCallId 互不覆盖）。 */
const pendingWrites = new Map<string, PendingDraftWrite>()
/** 每个键上一次**同步**落盘的时刻（节流基准）。 */
const lastWriteAt = new Map<string, number>()

/**
 * 落一笔（唯一写口）：返回**是否真的写进 storage**。
 *
 * <p>容量/序列化异常按存储键去重记录日志且不打断填写，但调用方必须据此决定是否推进
 * {@link lastWriteAt} —— 写失败还留下"刚写过"的假时间，会让 250ms 节流把下一次重试一并吞掉，
 * 草稿就一直停在内存里直到出口 flush（甚至出口也救不回）。
 */
function canWriteDraftKey(key: string, values: Record<string, unknown>): boolean {
  const target = storage()
  if (!target) {
    return false
  }
  try {
    target.setItem(key, JSON.stringify(values))
    return true
  } catch (error) {
    // QuotaExceeded 等：草稿只是体验增强，绝不因此打断填写
    logCaughtError(error, `form-draft:${key}`)
    return false
  }
}

function flushKey(key: string): void {
  const entry = pendingWrites.get(key)
  if (!entry) {
    return
  }
  pendingWrites.delete(key)
  if (entry.timer !== null) {
    clearTimeout(entry.timer)
  }
  if (canWriteDraftKey(key, entry.values)) {
    lastWriteAt.set(key, Date.now())
  } else {
    // 写失败：删掉节流账，下一次键入会立即重试（而不是被窗口误判成"刚写过"）
    lastWriteAt.delete(key)
  }
}

function discardPendingKey(key: string): void {
  const entry = pendingWrites.get(key)
  if (!entry) {
    return
  }
  pendingWrites.delete(key)
  lastWriteAt.delete(key)
  if (entry.timer !== null) {
    clearTimeout(entry.timer)
  }
}

function discardPendingFor(toolCallId: string): void {
  for (const [key, entry] of [...pendingWrites]) {
    if (entry.toolCallId === toolCallId) {
      discardPendingKey(key)
    }
  }
}

/**
 * 出口兜底（pagehide / 页面转隐藏）：把在合并窗口里的最终值写实。
 *
 * <p>为什么必须有：防抖意味着"最后一次键入"可能还悬在内存里；刷新/关页/切后台若不等这
 * 250ms，草稿就会停在上一笔 —— 恢复出来少几个字，正是"不截断用户草稿"要排除的情形。
 * 监听只装一次，且只在真的有待写值时才装。
 */
let exitFlushBound = false
function bindExitFlush(): void {
  if (exitFlushBound) {
    return
  }
  const host = globalThis as unknown as {
    addEventListener?: (type: string, listener: () => void) => void
    document?: { visibilityState?: string }
  }
  if (typeof host.addEventListener !== 'function') {
    return
  }
  exitFlushBound = true
  host.addEventListener('pagehide', () => flushAllFormDrafts())
  host.addEventListener('visibilitychange', () => {
    if (host.document?.visibilityState === 'hidden') {
      flushAllFormDrafts()
    }
  })
}

/**
 * 写草稿（**键入路径**）：合并窗口内只保留最后一笔，窗口外的第一次改动立即落盘。
 *
 * <p>为什么第一次要同步：读回语义必须"写进去的下一次读就在"（`loadFormDraft` / 恢复路径
 * 依赖它）；随后 250ms 内的连续键入只更新内存快照，等停手或出口再写。整笔值原样保存，
 * 不做任何截断 —— 截断会让"恢复出来少几个字"变成新的数据损坏。
 */
export function scheduleFormDraft(toolCallId: string, values: Record<string, unknown>): void {
  if (!toolCallId) {
    return
  }
  const key = formDraftKey(toolCallId)
  const now = Date.now()
  const last = lastWriteAt.get(key)
  if (!pendingWrites.has(key) && (last === undefined || now - last >= FORM_DRAFT_DEBOUNCE_MS)) {
    if (canWriteDraftKey(key, values)) {
      lastWriteAt.set(key, now)
      return
    }
    // 写失败（配额/存储不可用）：不推进节流时间，落到下面的合并窗口，出口 flush 再试一次
  }
  const existing = pendingWrites.get(key)
  if (existing) {
    existing.values = values
    if (existing.timer !== null) {
      clearTimeout(existing.timer)
    }
    existing.timer = setTimeout(() => flushKey(key), FORM_DRAFT_DEBOUNCE_MS)
    return
  }
  pendingWrites.set(key, {
    toolCallId,
    values,
    timer: setTimeout(() => flushKey(key), FORM_DRAFT_DEBOUNCE_MS)
  })
  bindExitFlush()
}

/**
 * 出口 flush：把该 `toolCallId` 在合并窗口里的最终值写实（幂等；没有待写值即 no-op）。
 *
 * <p>调用点：提交前、卡卸载、`loadFormDraft` 读回前、`prune` 保留项检查点。跨身份也安全 ——
 * 每一笔都在调度时固定了自己的存储键，晚到的 flush 不会把旧身份的值写进新身份的键。
 */
export function flushFormDraft(toolCallId?: string): void {
  if (toolCallId === undefined) {
    flushAllFormDrafts()
    return
  }
  for (const [key, entry] of [...pendingWrites]) {
    if (entry.toolCallId === toolCallId) {
      flushKey(key)
    }
  }
}

/** 全部待写值立即落盘（pagehide / 卸载兜底）。 */
export function flushAllFormDrafts(): void {
  for (const key of [...pendingWrites.keys()]) {
    flushKey(key)
  }
}

/** 读草稿：不存在 / 非法 JSON / 非对象一律 null（不把畸形值塞进受控控件）。 */
export function loadFormDraft(toolCallId: string): Record<string, unknown> | null {
  if (!toolCallId) {
    return null
  }
  const target = storage()
  if (!target) {
    return null
  }
  // 读回前先写实待写值：刷新恢复读到的必须是用户**最后**的输入，而不是 250ms 前的样子
  flushFormDraft(toolCallId)
  try {
    const raw = target.getItem(formDraftKey(toolCallId))
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null
    }
    return { ...(parsed as Record<string, unknown>) }
  } catch (error) {
    logCaughtError(error, `form-draft:${formDraftKey(toolCallId)}`)
    return null
  }
}

/**
 * 写草稿（**直写路径**，同步）：只有显式整体覆盖的调用方用它（如恢复夹具、诊断）。
 *
 * <p>先丢掉该 `toolCallId` 的待写值，再写这一笔 —— 否则旧的合并窗口到点会把新值盖回去。
 * 丢弃待写值放在 storage 检查**之前**：直写语义是"这一笔覆盖之前所有排队值"，即使当前
 * storage 不可用（隐私模式等），旧定时器也不得在之后把过期值写回。
 *
 * <p>写失败不推进 {@link lastWriteAt}：否则下一次键入会被节流窗口当成"刚写过"而跳过落盘。
 */
export function saveFormDraft(toolCallId: string, values: Record<string, unknown>): void {
  if (!toolCallId) {
    return
  }
  discardPendingFor(toolCallId)
  const target = storage()
  if (!target) {
    return
  }
  const key = formDraftKey(toolCallId)
  if (canWriteDraftKey(key, values)) {
    lastWriteAt.set(key, Date.now())
  } else {
    lastWriteAt.delete(key)
  }
}

/**
 * 清草稿（提交成功 / 取消 / 终态；幂等）。
 *
 * <p>同时**取消**该 `toolCallId` 的待写值：清掉之后若还让合并窗口到点写回，取消/提交成功的
 * 表单会凭空"复活"一份草稿（跨身份也按 toolCallId 一并取消，不依赖键形）。
 */
export function clearFormDraft(toolCallId: string): void {
  if (!toolCallId) {
    return
  }
  discardPendingFor(toolCallId)
  lastWriteAt.delete(formDraftKey(toolCallId))
  const target = storage()
  if (!target) {
    return
  }
  try {
    target.removeItem(formDraftKey(toolCallId))
  } catch (error) {
      logCaughtError(error, 'utils/form-draft.ts:296')
    // 同上：清理失败不影响主流程
  }
}

/**
 * 批次6B（N10）：按前缀清**孤儿**草稿 —— 只保留 `present` 里的 `toolCallId`。
 *
 * <p>为什么需要：`clientCleanup.draftToolCallIds` 只枚举服务端还认识的条目，已决卡 / 换会话后
 * 不再被引用的草稿键会长期留在 sessionStorage（无人可见，但不该留）。这里以
 * {@link FORM_DRAFT_PREFIX} **+ 当前身份前缀**为唯一扫描面（不同身份的草稿不互相清理），
 * **绝不** `sessionStorage.clear()` —— 同一页签里还有业务键、镜像键与其它页签状态。
 *
 * @param present 在场白名单（活动表单 / 排队表单 / 仍待动作的工具段引用的 toolCallId）
 * @returns 实际删除的键数（测试与诊断用）
 */
export function pruneFormDrafts(present: Iterable<string>): number {
  const prefix = formDraftPrefix()
  const keep = new Set(present)
  // 待写值同样按「在场白名单」收口（F-1）：保留项先写实（不让一次换会话/停止丢掉最后几个字），
  // 其余一律取消 —— 否则合并窗口到点会在清理之后凭空写回一个孤儿键。
  for (const [key, entry] of [...pendingWrites]) {
    if (key.startsWith(prefix) && keep.has(entry.toolCallId)) {
      flushKey(key)
    } else {
      discardPendingKey(key)
    }
  }
  const target = storage()
  if (!target) {
    return 0
  }
  const doomed: string[] = []
  try {
    for (let i = 0; i < target.length; i += 1) {
      const key = target.key(i)
      if (!key || !key.startsWith(prefix)) {
        continue
      }
      const toolCallId = key.slice(prefix.length)
      if (!keep.has(toolCallId)) {
        doomed.push(key)
      }
    }
    doomed.forEach((key) => {
      target.removeItem(key)
      // 节流账与键同步清：键都删了还留着 lastWriteAt，下一笔同名键会被误判"刚写过"
      // 而跳过 leading 写（读回语义要求写进去的下次读就在）。
      lastWriteAt.delete(key)
    })
  } catch (error) {
      logCaughtError(error, 'utils/form-draft.ts:346')
    // 隐私模式/配额：清理失败不影响主流程（孤儿键只是卫生问题）
    return 0
  }
  return doomed.length
}
