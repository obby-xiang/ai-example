/**
 * AI 对话域的**租户/用户身份**（Task 4）。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>身份只作用于 AI 对话域：会话、运行态、浏览器镜像、表单草稿、页签所有权与所有
 *   AI 请求头（{@code X-Tenant-Id} / {@code X-User-Id}）。业务数据与模型配置不纳入。</li>
 * <li>身份存 {@code sessionStorage}（页签级，与其它页签状态同生命周期），键 {@link AI_IDENTITY_KEY}。</li>
 * <li>一旦选定身份，浏览器存储键一律带 {@code tenantId:userId} 指纹，不同身份**不共享**
 *   镜像、草稿、会话 id 与页签锁；无身份（尚未接入目录）时退回旧的单用户键形，避免
 *   "未接租户"的部署出现空身份请求。</li>
 * <li>会话 id 只由后端发号；本模块只负责把 id 落到**当前身份**的键下，不做任何生成。</li>
 * </ul>
 */

/** 当前页签的 AI 身份。 */
export interface AiIdentity {
  tenantId: number
  userId: number
}

/** 身份（页签级）：`{"tenantId":1,"userId":2}`。 */
export const AI_IDENTITY_KEY = 'ai-identity'
/** 会话 id 的**基础**键；带身份时追加 `:<tenantId>:<userId>`。 */
export const AI_SESSION_ID_KEY = 'ai-session-id'
/** 会话镜像键前缀；带身份时形如 `ai-mirror:<tenantId>:<userId>:<sessionId>`。 */
export const AI_MIRROR_PREFIX = 'ai-mirror:'
/** 页签会话锁名前缀；带身份时形如 `ai-session:<tenantId>:<userId>:<sessionId>`。 */
export const AI_SESSION_LOCK_PREFIX = 'ai-session-'
/** 表单草稿键前缀；带身份时形如 `cm.ai.form.draft.<tenantId>:<userId>.<toolCallId>`。 */
export const AI_FORM_DRAFT_PREFIX = 'cm.ai.form.draft.'

/** 请求头名（后端 HTTP Filter 读取）。 */
export const TENANT_HEADER = 'X-Tenant-Id'
export const USER_HEADER = 'X-User-Id'

/** 目录里的一行（`GET /api/ai/tenant-users`；`tenantName` / `userName` 为兼容后端的别名）。 */
export interface TenantUsersEntry {
  tenantId: number
  tenantName?: string
  users: Array<{ userId: number; displayName: string }>
}

/** 正整数 id（目录/会话号/身份字段的统一收窄）。 */
export function isPositiveId(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0
}

/** 合法身份（缺字段/非正整数/负数一律不收）。 */
export function normalizeIdentity(raw: unknown): AiIdentity | null {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const candidate = raw as { tenantId?: unknown; userId?: unknown }
  if (!isPositiveId(candidate.tenantId) || !isPositiveId(candidate.userId)) {
    return null
  }
  return { tenantId: candidate.tenantId, userId: candidate.userId }
}

/** 身份指纹：键与锁的唯一定位段。 */
export function identityFingerprint(identity: AiIdentity): string {
  return `${identity.tenantId}:${identity.userId}`
}

/** 同一身份（缺省双方为 null 时也算相同：无身份）。 */
export function isSameIdentity(
  left: AiIdentity | null | undefined,
  right: AiIdentity | null | undefined
): boolean {
  if (!left || !right) {
    return !left && !right
  }
  return left.tenantId === right.tenantId && left.userId === right.userId
}

/** 读页签身份（畸形/缺失 ⇒ null）。 */
export function readAiIdentity(): AiIdentity | null {
  try {
    const raw = sessionStorage.getItem(AI_IDENTITY_KEY)
    if (!raw) {
      return null
    }
    return normalizeIdentity(JSON.parse(raw))
  } catch {
    return null
  }
}

/** 落页签身份（隐私模式/配额失败只影响刷新后的恢复，不影响本次切换）。 */
export function writeAiIdentity(identity: AiIdentity): void {
  try {
    sessionStorage.setItem(AI_IDENTITY_KEY, JSON.stringify(identity))
  } catch {
    // 忽略：本页签内存态照常生效
  }
}

/** 统一身份请求头；无身份时返回空对象（调用方据此不发"空身份"请求）。 */
export function identityHeaders(identity: AiIdentity | null = readAiIdentity()): Record<string, string> {
  if (!identity) {
    return {}
  }
  return {
    [TENANT_HEADER]: String(identity.tenantId),
    [USER_HEADER]: String(identity.userId)
  }
}

/** 会话 id 存储键：带身份即在基础键后追加指纹。 */
export function sessionIdStorageKey(identity: AiIdentity | null = readAiIdentity()): string {
  return identity ? `${AI_SESSION_ID_KEY}:${identityFingerprint(identity)}` : AI_SESSION_ID_KEY
}

/** 会话镜像键：带身份即带指纹（不同身份不得复用镜像）。 */
export function mirrorStorageKey(
  sessionId: string,
  identity: AiIdentity | null = readAiIdentity()
): string {
  return identity
    ? `${AI_MIRROR_PREFIX}${identityFingerprint(identity)}:${sessionId}`
    : `${AI_MIRROR_PREFIX}${sessionId}`
}

/** 表单草稿前缀：带身份即带指纹（不同身份不得复用草稿）。 */
export function formDraftPrefix(identity: AiIdentity | null = readAiIdentity()): string {
  return identity
    ? `${AI_FORM_DRAFT_PREFIX}${identityFingerprint(identity)}.`
    : AI_FORM_DRAFT_PREFIX
}

/** 表单草稿键（toolCallId 在身份段之后，孤儿清理按前缀扫描即可）。 */
export function formDraftStorageKey(
  toolCallId: string,
  identity: AiIdentity | null = readAiIdentity()
): string {
  return `${formDraftPrefix(identity)}${toolCallId}`
}

/** 页签会话锁名：带身份即带指纹（不同身份不得互相探活/接管）。 */
export function sessionLockName(
  sessionId: string,
  identity: AiIdentity | null = readAiIdentity()
): string {
  return identity
    ? `${AI_SESSION_LOCK_PREFIX}${identityFingerprint(identity)}:${sessionId}`
    : `${AI_SESSION_LOCK_PREFIX}${sessionId}`
}

/**
 * 会话号收窄：契约是 `{sessionId:number(long)}`，内部统一转成十进制字符串
 * （镜像键、锁名、路径参数都按字符串拼；空/非法一律 ''，调用方不得发送）。
 */
export function normalizeSessionId(raw: unknown): string {
  if (typeof raw === 'number' && Number.isFinite(raw) && Number.isInteger(raw) && raw > 0) {
    return String(raw)
  }
  if (typeof raw === 'string' && /^[1-9]\d*$/.test(raw)) {
    return raw
  }
  return ''
}

/**
 * 该 AI 端点是否属于**对话域**（需要身份头）。
 *
 * <p>排除：模型配置 / provider / 健康探针 / 工具清单 / 身份目录 —— 它们是"业务与模型配置"面，
 * 不参与租户隔离（健康探针与目录还要在选身份之前可用）。与后端 `TenantIdentityFilter` 的
 * 排除前缀保持一致。
 */
export function isAiConversationPath(url: string): boolean {
  const path = url.startsWith('/api/') ? url.slice(4) : url
  if (!path.startsWith('/ai/')) {
    return false
  }
  const excluded = [
    '/ai/providers',
    '/ai/models',
    '/ai/settings',
    '/ai/health',
    '/ai/tools',
    '/ai/tenants',
    '/ai/tenant-users'
  ]
  return !excluded.some((prefix) => path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`))
}
