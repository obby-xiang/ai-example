/**
 * E2E **身份面**（Task 5 Playwright，`frontend/e2e` 内唯一事实源）。
 *
 * <h2>为什么需要它</h2>
 * Task 4 起 AI 对话数据面的每个请求都必须带 `X-Tenant-Id` / `X-User-Id`（后端
 * `TenantIdentityFilter`：缺头/非正数/目录外组合一律 400），会话号只由 `POST /api/ai/session`
 * 发放，浏览器键（会话指针、镜像、草稿）都带 `tenantId:userId` 指纹。E2E 里"绕过页面直连后端"
 * 的辅助（history 取证、直打 chat、读镜像……）若不带身份头就会被 400；若继续读写裸键（
 * `ai-session-id` / `ai-mirror:<sid>`）则会读到 `null` 或造出跨身份的假现场。
 *
 * <h2>口径（与后端逐条同构）</h2>
 * <ul>
 * <li>{@link isAiConversationPath}：`/api/ai` 与 `/api/ai/` 下**除**精确排除路径
 *   （`health` / `tools` / `tenants`）与**模型配置子树**（`providers` / `models` / `settings`）
 *   之外全部受保护 —— **包括** `startup-resume` 的读取与 `POST startup-resume/scan`：两者都只处理
 *   **当前身份**的轮次，所以和前端 `utils/ai-identity.ts`、后端 `TenantIdentityFilter.EXCLUDED_EXACT`
 *   一样把它们留在边界内（漏带头 = 400，扫别人的轮在边界上就不成立）。业务数据（`/api/tasks` 等）
 *   与模型配置**不加**身份头。</li>
 * <li>{@link newAiSession}：先 `POST /api/ai/session` 取号，**只认 `sessionIdText`**（原始响应
 *   文本正则），禁止读 JSON `sessionId` number（`long` 可能超过 2^53，double 会静默变值）。
 *   号值由后端 `IdGenerator` 发放（当前实现 = 毫秒时间戳），**不按位数**判定合法性；撞号（同毫秒）
 *   由后端回 503 `SESSION_ID_UNAVAILABLE`，这里做有界重试。</li>
 * <li>页面身份 = `sessionStorage['ai-identity']`；直连身份必须与它一致（{@link expectSameIdentity}），
 *   否则取证会跨身份（读别人的历史 / 把镜像写到别的身份下）。</li>
 * </ul>
 */
import { type Page } from '@playwright/test'

/** AI 身份（默认 = 目录首项：租户一 / 用户 101，与前端 `loadTenantDirectory` 的落点一致）。 */
export interface AiIdentity {
  tenantId: number
  userId: number
}

export const AI_IDENTITY: AiIdentity = {
  tenantId: Number(process.env.E2E_TENANT_ID ?? '1'),
  userId: Number(process.env.E2E_USER_ID ?? '101')
}

/** 身份指纹：键形里的唯一定位段（`1:101`）。 */
export function identityFingerprint(identity: AiIdentity = AI_IDENTITY): string {
  return `${identity.tenantId}:${identity.userId}`
}

/** 身份请求头（只给对话数据面用；业务/模型配置不加）。 */
export function aiIdentityHeaders(identity: AiIdentity = AI_IDENTITY): Record<string, string> {
  return {
    'X-Tenant-Id': String(identity.tenantId),
    'X-User-Id': String(identity.userId)
  }
}

/**
 * 后端 `TenantIdentityFilter.EXCLUDED_EXACT` 的**逐字同集**（只有三条；子路径**不**排除）。
 *
 * <p>`startup-resume` 与其 `POST /scan` 刻意**不在**这里：HTTP 边界收紧后两者都要求身份头、
 * 且只处理当前身份的轮次 —— 把它们当"公开面"会让 E2E 直连的 scan 变成 400，也会掩盖
 * "越权扫别人的轮"这一整类回归。
 */
const EXCLUDED_EXACT = new Set(['/api/ai/health', '/api/ai/tools', '/api/ai/tenants'])

/** 模型配置命名空间（整个子树不做租户隔离）。 */
const CONFIG_SUBTREES = ['/api/ai/providers', '/api/ai/models', '/api/ai/settings']

/** 该 URL / 路径是否属于 AI **对话数据面**（= 必须带身份头）。 */
export function isAiConversationPath(urlOrPath: string): boolean {
  let path = urlOrPath
  if (/^https?:\/\//i.test(path)) {
    try {
      path = new URL(path).pathname
    } catch {
      return false
    }
  }
  // 后端 `getRequestURI()` 不含 query：分类只看路径段（`/api/ai/tools?page=x` 仍是排除面）。
  const queryAt = path.indexOf('?')
  if (queryAt >= 0) {
    path = path.slice(0, queryAt)
  }
  if (!path.startsWith('/api/ai')) {
    return false
  }
  if (path !== '/api/ai' && !path.startsWith('/api/ai/')) {
    return false
  }
  const normalized = path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
  if (EXCLUDED_EXACT.has(normalized)) {
    return false
  }
  for (const subtree of CONFIG_SUBTREES) {
    if (normalized === subtree || normalized.startsWith(`${subtree}/`)) {
      return false
    }
  }
  return true
}

/** 该请求该带哪些身份头（排除面返回 `{}`，调用方原样合并即可）。 */
export function aiHeaders(urlOrPath: string, identity: AiIdentity = AI_IDENTITY): Record<string, string> {
  return isAiConversationPath(urlOrPath) ? aiIdentityHeaders(identity) : {}
}

/**
 * `fetch` 封装：AI 对话数据面自动带身份头；其余请求**原样**（不无脑加头，业务/模型配置不加）。
 */
export function aiFetch(url: string, init: RequestInit = {}, identity: AiIdentity = AI_IDENTITY): Promise<Response> {
  const merged: Record<string, string> = {
    ...((init.headers as Record<string, string> | undefined) ?? {}),
    ...aiHeaders(url, identity)
  }
  return fetch(url, { ...init, headers: merged })
}

/**
 * Playwright `APIRequestContext` 的选项封装：返回合并了身份头的 `{ headers }`。
 * 用法：`request.post(url, { ...aiRequestOptions(url), data })`。
 */
export function aiRequestOptions<T extends object>(
  urlOrPath: string,
  options: T = {} as T,
  identity: AiIdentity = AI_IDENTITY
): T & { headers: Record<string, string> } {
  const merged: Record<string, string> = {
    ...((options as { headers?: Record<string, string> }).headers ?? {}),
    ...aiHeaders(urlOrPath, identity)
  }
  return { ...options, headers: merged }
}

/** `java.lang.Long` 的十进制上界（后端发号是 `long`；超过它不可能是本端点发的号）。 */
const LONG_MAX = 9223372036854775807n

/**
 * 后端发号：`POST /api/ai/session` → **只认 `sessionIdText`**。
 *
 * <p>为什么不 `res.json()` 取号：号是 `long`，可能超过 `2^53`，JSON number 在 JS 里会被静默解析成
 * 另一个数且不报错；`sessionIdText` 是后端 `Long.toString` 的**权威十进制字符串**。
 *
 * <p><b>不按位数判定</b>：发号实现是 `TimestampIdGenerator`（`System.currentTimeMillis()`，当前 13 位），
 * 位数字随实现变化（毫秒时间戳 / 雪花 / 序列号），拿固定位数当"精度丢失"判据必然漂移。
 * 这里只校验三件事：十进制正整数（无前导零、非 0）、不超过 `long` 上界、与同一响应里的
 * `sessionId` number 自洽（仅在该 number 可安全表示时逐字比对）。
 */
export function parseSessionIdText(text: string): string {
  const matched = /"sessionIdText"\s*:\s*"([1-9]\d*)"/.exec(text)
  if (matched === null) {
    throw new Error(`POST /api/ai/session 未返回 sessionIdText（禁止读 JSON number）：${text.slice(0, 400)}`)
  }
  const sessionId = matched[1]
  if (BigInt(sessionId) > LONG_MAX) {
    throw new Error(`sessionIdText 超出 long 上界（不是本端点发的号）：${sessionId}`)
  }
  // 与响应自洽：number 能安全表示时必须与文本逐字相等；超过 2^53 的 number 已失真，不回读比对。
  const numberText = /"sessionId"\s*:\s*(-?\d+)/.exec(text)?.[1]
  if (numberText !== undefined) {
    const asNumber = Number(numberText)
    if (Number.isSafeInteger(asNumber) && String(asNumber) !== sessionId) {
      throw new Error(`sessionIdText 与响应 sessionId 不一致：${sessionId} vs ${numberText}`)
    }
  }
  return sessionId
}

/** 撞号（同毫秒取到已占用的号）的有界重试：次数与间隔写死，不无限重试。 */
const SESSION_ISSUE_ATTEMPTS = 3
const SESSION_ISSUE_RETRY_MS = 5

export async function newAiSession(base: string, identity: AiIdentity = AI_IDENTITY): Promise<string> {
  const url = `${base}/api/ai/session`
  let lastBody = ''
  for (let attempt = 1; attempt <= SESSION_ISSUE_ATTEMPTS; attempt += 1) {
    const res = await fetch(url, { method: 'POST', headers: aiIdentityHeaders(identity) })
    const text = await res.text()
    if (res.ok) {
      return parseSessionIdText(text)
    }
    // 撞号：毫秒时间戳发号，同一毫秒（含跨实例）可能撞上已有会话 —— 后端保旧会话并回 503。
    // 其它失败（400/500/身份头缺失）立即上抛，不重试。
    if (res.status !== 503 || !text.includes('SESSION_ID_UNAVAILABLE')) {
      throw new Error(`POST /api/ai/session => HTTP ${res.status} ${text.slice(0, 400)}`)
    }
    lastBody = text
    if (attempt === SESSION_ISSUE_ATTEMPTS) {
      break
    }
    // 隔一拍再取（跨过同一毫秒），次数有界。
    await new Promise((done) => setTimeout(done, SESSION_ISSUE_RETRY_MS))
  }
  throw new Error(
    `POST /api/ai/session => 连续 ${SESSION_ISSUE_ATTEMPTS} 次撞号（SESSION_ID_UNAVAILABLE）：${lastBody.slice(0, 400)}`
  )
}

/**
 * 在**新页签**里预置"同一身份 + 指定会话号"的现场（复制页签 / 关掉再开页签用）。
 *
 * <p>必须同时写 `ai-identity` 与身份会话键 `ai-session-id:<t>:<u>`：只写裸键的话页面起不来身份，
 * 会话指针也读不到（Task 4 后裸键不存在）。
 */
export function seedSessionStorage(page: Page, sessionId: string, identity: AiIdentity = AI_IDENTITY): Promise<unknown> {
  return page.addInitScript(({ sid, id }) => {
    try {
      sessionStorage.setItem('ai-identity', JSON.stringify(id))
      sessionStorage.setItem(`ai-session-id:${id.tenantId}:${id.userId}`, sid)
    } catch {
      /* 隐私模式/配额：忽略（后续断言会响亮失败） */
    }
  }, { sid: sessionId, id: identity })
}

// ── 浏览器键形（身份指纹） ───────────────────────────────────────────────────

/** 会话指针键：`ai-session-id:<t>:<u>`。 */
export function pageSessionKey(identity: AiIdentity = AI_IDENTITY): string {
  return `ai-session-id:${identityFingerprint(identity)}`
}

/** 镜像键：`ai-mirror:<t>:<u>:<sid>`。 */
export function pageMirrorKey(sessionId: string, identity: AiIdentity = AI_IDENTITY): string {
  return `ai-mirror:${identityFingerprint(identity)}:${sessionId}`
}

/** 草稿键前缀：`cm.ai.form.draft.<t>:<u>.`。 */
export function pageDraftPrefix(identity: AiIdentity = AI_IDENTITY): string {
  return `cm.ai.form.draft.${identityFingerprint(identity)}.`
}

// ── 后端 Redis 键形（身份段，与 `TenantKeyScope` 逐字同构） ─────────────────────

/**
 * 会话记录键：`ai:session:<t>:<u>:<sid>`（后端 `AiSessionService.keyFor`，SET NX 创建、带会话 TTL）。
 *
 * <p>没有身份段就落到 **legacy 命名空间** `ai:session:<sid>`：带身份的读写永不触及它，
 * 所以取证必须用带身份段的键 —— 否则会把"会话确实在"读成"键不存在"。
 */
export function sessionRecordKey(sessionId: string, identity: AiIdentity = AI_IDENTITY): string {
  return `ai:session:${identityFingerprint(identity)}:${sessionId}`
}

/**
 * 会话记忆键：`chat:mem:<t>:<u>:{<sid>}`（后端 `RedisChatMemoryRepository.key`）。
 *
 * <p>`{sid}` 是 hash tag（Cluster 同槽）：身份段在 tag **之前**，tag 内只有转义后的会话号。
 */
export function sessionMemoryKey(sessionId: string, identity: AiIdentity = AI_IDENTITY): string {
  return `chat:mem:${identityFingerprint(identity)}:{${sessionId}}`
}

/** 会话上下文真值键：`ai:ctx:<t>:<u>:<sid>`（后端 `RunStore.contextKey`，HASH + 会话 TTL）。 */
export function sessionContextKey(sessionId: string, identity: AiIdentity = AI_IDENTITY): string {
  return `ai:ctx:${identityFingerprint(identity)}:${sessionId}`
}

/** 会话 run 索引键：`ai:session:runs:<t>:<u>:<sid>`（后端 `RunStore` 的 SESSION_RUNS，SET）。 */
export function sessionRunsIndexKey(sessionId: string, identity: AiIdentity = AI_IDENTITY): string {
  return `ai:session:runs:${identityFingerprint(identity)}:${sessionId}`
}

/** 页面当前身份（`ai-identity`；未落身份 = null）。 */
export async function pageIdentity(page: Page): Promise<AiIdentity | null> {
  return page.evaluate(() => {
    const raw = sessionStorage.getItem('ai-identity')
    if (!raw) return null
    try {
      const value = JSON.parse(raw) as { tenantId?: unknown; userId?: unknown }
      if (typeof value.tenantId === 'number' && typeof value.userId === 'number') {
        return { tenantId: value.tenantId, userId: value.userId }
      }
      return null
    } catch {
      return null
    }
  })
}

/**
 * 页面会话号：读**身份键** `ai-session-id:<t>:<u>`。
 *
 * <p>不退回裸键 `ai-session-id`：身份模式下裸键永远不存在，退回只会把"页面还没发号"伪装成
 * "读到了旧号"。页面身份取 `ai-identity`（与 {@link AI_IDENTITY} 不同即报错 —— 直连取证不能跨身份）。
 */
export async function pageSessionId(page: Page, identity: AiIdentity = AI_IDENTITY): Promise<string> {
  const actual = await pageIdentity(page)
  if (actual === null) throw new Error('页面尚未落 AI 身份（sessionStorage 无 ai-identity）')
  if (actual.tenantId !== identity.tenantId || actual.userId !== identity.userId) {
    throw new Error(
      `页面身份 ${identityFingerprint(actual)} 与直连取证身份 ${identityFingerprint(identity)} 不一致：`
      + '跨身份取证会读到别人的会话/历史（直连身份须与页面同身份）'
    )
  }
  return readSessionKey(page, actual)
}

async function readSessionKey(page: Page, identity: AiIdentity): Promise<string> {
  const key = pageSessionKey(identity)
  const id = await page.evaluate((storageKey) => sessionStorage.getItem(storageKey), key)
  if (!id) throw new Error(`sessionStorage 中没有身份会话键 ${key}（页面尚未发号）`)
  return id
}

/** 断言页面身份 == 直连取证身份（口径：直连必须与页面同身份，不得跨身份取证）。 */
export async function expectSameIdentity(page: Page, identity: AiIdentity = AI_IDENTITY): Promise<void> {
  const actual = await pageIdentity(page)
  if (actual === null || actual.tenantId !== identity.tenantId || actual.userId !== identity.userId) {
    throw new Error(
      `页面身份 ${JSON.stringify(actual)} 与直连身份 ${JSON.stringify(identity)} 不一致：跨身份取证会读到别人的会话/历史`
    )
  }
}

/** 页面侧会话指针键（在浏览器上下文按 `ai-identity` 求值；无身份即报错）。 */
export async function pageSessionKeyOf(page: Page): Promise<string> {
  return page.evaluate(() => {
    const raw = sessionStorage.getItem('ai-identity')
    if (!raw) throw new Error('页面尚未落 AI 身份（sessionStorage 无 ai-identity）')
    const value = JSON.parse(raw) as { tenantId: number; userId: number }
    return `ai-session-id:${value.tenantId}:${value.userId}`
  })
}

/** 页面侧镜像键（在浏览器上下文按 `ai-identity` 求值）。 */
export async function pageMirrorKeyOf(page: Page, sessionId: string): Promise<string> {
  return page.evaluate((sid) => {
    const raw = sessionStorage.getItem('ai-identity')
    if (!raw) throw new Error('页面尚未落 AI 身份（sessionStorage 无 ai-identity）')
    const value = JSON.parse(raw) as { tenantId: number; userId: number }
    return `ai-mirror:${value.tenantId}:${value.userId}:${sid}`
  }, sessionId)
}
