/**
 * 统一**身份夹具**（Task 4 返修 I-3 / 旧测试桩迁移）。
 *
 * <h2>为什么需要它</h2>
 * Task 4 起会话号只由后端 `POST /api/ai/session` 发放，前端本地造号分支已删除；所有浏览器键
 * （`ai-session-id` / `ai-mirror:<sid>` / `ai-session-<sid>` / 草稿前缀）都带 `tenantId:userId`
 * 指纹。旧测试隐含"前端自己造 sessionId + 无身份裸键"，在改动后拿不到身份也拿不到号 ——
 * `send()` 恒 `not-sent`、`loadHistory()` 不发请求。本夹具把"一个**真实**身份装进现场"，
 * 让这些用例继续走生产路径，而不是把断言删掉或退回本地造号。
 *
 * <h2>口径（不是 mock 被测物）</h2>
 * <ol>
 * <li>`writeAiIdentity()` + 身份会话指针 —— store 初始化即"有身份、已发号"，与刷新恢复同一条
 *   `initSessionId()` / `storedSessionPointer()` 读取路径；</li>
 * <li>`tenantUsers` / `createSession` 桩 —— 目录与发号仍走生产调用点
 *   （`loadTenantDirectory()` / `ensureSession()`），用例可以断言"号是后端给的"；</li>
 * <li><b>同一身份重复取号稳定</b>（指针还在 ⇒ 同一号），<b>切换身份递增</b>（下一个号）；
 *   换会话（新建对话 / 410 / 复制页签）时指针已被 store 抹掉 ⇒ 自动发**新**号，
 *   用例要钉死具体号时用 {@link queueNextSessionId}（或夹具上的 `queueSession`）；</li>
 * <li>每个用例装一次即可（缺省每个 install 换一个新的会话号，避免上一例的镜像尾写污染下一例）。</li>
 * </ol>
 *
 * <h2>负控制不能被它抹掉</h2>
 * "无身份不得发请求"的用例（目录不可用 / 尚未选身份）**不要**调用本夹具 —— 它们必须留在
 * 裸现场，用 `sessionStorage.clear()` + `resetAiIdentityCache()` 显式表达"没有身份"。
 */
import { vi } from 'vitest'
import { aiApi } from '@/api/ai'
import {
  type AiIdentity,
  type TenantUsersEntry,
  identityFingerprint,
  isPositiveId,
  mirrorStorageKey,
  normalizeSessionId,
  readAiIdentity,
  resetAiIdentityCache,
  sessionIdStorageKey,
  sessionLockName,
  writeAiIdentity
} from '@/utils/ai-identity'

/** 缺省身份：与身份套件（`ai-tenant-identity-*`）同一组预置租户/用户，便于对照。 */
export const FIXTURE_TENANT_ID = 7
export const FIXTURE_USER_ID = 71

/** 缺省会话号基数（每个 install 递增，避免跨用例的镜像键撞车）。 */
const SESSION_ID_BASE = 7001

export interface IdentityFixtureOptions {
  /** 租户 id（缺省 {@link FIXTURE_TENANT_ID}）。 */
  tenantId?: number
  /** 用户 id（缺省 {@link FIXTURE_USER_ID}）。 */
  userId?: number
  /**
   * 该身份**已有**的会话号（后端已发号）；省略 = 自动分配。
   * 传 `''` / `0` = 该身份尚无会话（store 起来后由 `ensureSession()` 发号）。
   */
  sessionId?: number | string
  /**
   * 目录内容（`GET /api/ai/tenants` 的返回值）。缺省只含当前身份一项。
   * 需要"切换身份 / 重载目录后身份仍在"的用例把目标身份一并放进目录。
   */
  directory?: TenantUsersEntry[]
  /** 关掉 `tenantUsers` / `createSession` 桩（用例自己完全控制目录/发号时）。 */
  stubApi?: boolean
}

export interface InstalledIdentityFixture {
  /** 装进去的身份。 */
  identity: AiIdentity
  /** 该身份的会话号（'' = 尚未发号）。 */
  sessionId: string
  /** 会话号的存储键（`ai-session-id:<t>:<u>`）。 */
  sessionIdKey: string
  /** 身份镜像键（`ai-mirror:<t>:<u>:<sid>`）。 */
  mirrorKey: (sessionId?: string) => string
  /** 身份页签锁名（`ai-session-<t>:<u>:<sid>`）。 */
  lockName: (sessionId?: string) => string
  /** 指定**下一次**发号返回的号（一次性）：换会话 / 410 恢复 / 复制页签换新号。 */
  queueSession: (sessionId: number | string) => void
  /** 某身份在夹具里被分配到的号（缺省当前身份）；用于断言"切换后是新号"。 */
  issuedSessionId: (identity?: AiIdentity) => string
  /** 按身份键写一份镜像（形状与 store 的 `writeMirror` 同源：v2 + messages）。 */
  writeMirror: (sessionId: string, messages: unknown[], extra?: Record<string, unknown>) => void
  /** 读身份镜像（断言"写没写、写了什么"）。 */
  readMirror: (sessionId: string) => Record<string, unknown> | null
}

/** 每个 install 会把号推进一格（同一文件内不撞键）。 */
let installSeq = 0
/** 身份指纹 → 会话号（一次 install 内稳定：同一身份重复取号返回同一号）。 */
let issuedByFingerprint = new Map<string, string>()
/** 下一次新身份可用的号。 */
let nextNumber = SESSION_ID_BASE
/** 一次性发号覆盖（`queueSession`）。 */
let queuedSessionId: string | null = null
/** 本 install 的身份（发号时 storage 里读不到身份才回退到它）。 */
let fixtureIdentity: AiIdentity = { tenantId: FIXTURE_TENANT_ID, userId: FIXTURE_USER_ID }

function toSessionId(raw: number | string | undefined): string {
  if (raw === undefined) {
    return ''
  }
  return normalizeSessionId(raw)
}

/** 新号：同一 install 内全局递增（切换身份 / 换会话都拿下一个号）。 */
function nextSessionNumber(identity: AiIdentity): string {
  const value = String(nextNumber)
  nextNumber += 1
  issuedByFingerprint.set(identityFingerprint(identity), value)
  return value
}

/** 发号口认的身份：storage（`enterIdentity` 已落）> 内存单例 > 本次 install 的身份。 */
function identityAtIssue(): AiIdentity {
  const stored = readAiIdentity()
  if (stored !== null) {
    return stored
  }
  return fixtureIdentity
}

/**
 * 把 `tenantUsers` / `createSession` 装到被测的 `@/api/ai` 桩上。
 *
 * <p>只补**缺的**：用例自己已经给了 `tenantUsers` / `createSession` 桩（身份套件）时不覆盖 ——
 * 那些用例要自己控制"目录失败 / 发号失败"，覆盖会把它们的负控制抹平。
 */
function patchApi(options: IdentityFixtureOptions): void {
  if (options.stubApi === false) {
    return
  }
  const api = aiApi as unknown as Record<string, unknown>
  if (api === null || typeof api !== 'object') {
    return
  }
  const directory = options.directory ?? []
  if (typeof api.tenantUsers !== 'function') {
    api.tenantUsers = vi.fn(async () => directory.map((entry) => ({
      tenantId: entry.tenantId,
      users: entry.users.map((user) => ({ userId: user.userId, displayName: user.displayName }))
    })))
  }
  if (typeof api.createSession !== 'function') {
    api.createSession = vi.fn(async () => {
      const identity = identityAtIssue()
      const fingerprint = identityFingerprint(identity)
      let value = ''
      if (queuedSessionId !== null) {
        value = queuedSessionId
        queuedSessionId = null
        issuedByFingerprint.set(fingerprint, value)
      } else {
        const known = issuedByFingerprint.get(fingerprint)
        // 指针还在 ⇒ 这是"同一会话的重复取号"，返回同一号（稳定）；
        // 指针已被抹掉（新建对话 / 410 / 复制页签换会话）⇒ 旧会话已销毁，发**新**号（递增）。
        const pointer = sessionStorage.getItem(sessionIdStorageKey(identity))
        value = known !== undefined && pointer === known ? known : nextSessionNumber(identity)
      }
      return value
    })
  }
}

/**
 * 装身份夹具（每个用例一次，放在 `sessionStorage.clear()` 之后、创建 store 之前）。
 *
 * <p>install 之后 store 起来就是"有身份 + 已发号"，`send()` / `loadHistory()` 走生产路径；
 * 目录里没有当前身份时（传了 `directory` 却不含它）`loadTenantDirectory()` 会落到目录第一项 ——
 * 这与生产一致，用例若要"重载目录后仍是同一身份"，把该身份写进 `directory`。
 */
export function installIdentityFixture(options: IdentityFixtureOptions = {}): InstalledIdentityFixture {
  const identity: AiIdentity = {
    tenantId: isPositiveId(options.tenantId) ? options.tenantId : FIXTURE_TENANT_ID,
    userId: isPositiveId(options.userId) ? options.userId : FIXTURE_USER_ID
  }
  const noSession = options.sessionId === '' || options.sessionId === 0
  const explicit = noSession ? '' : toSessionId(options.sessionId)
  const allocated = noSession
    ? ''
    : (explicit !== '' ? explicit : String(SESSION_ID_BASE + installSeq))

  installSeq += 1
  issuedByFingerprint = allocated === '' ? new Map() : new Map([[identityFingerprint(identity), allocated]])
  nextNumber = allocated === '' ? SESSION_ID_BASE + installSeq : Number(allocated) + 1
  queuedSessionId = null
  fixtureIdentity = identity

  resetAiIdentityCache()
  writeAiIdentity(identity)
  if (allocated !== '') {
    sessionStorage.setItem(sessionIdStorageKey(identity), allocated)
  } else {
    sessionStorage.removeItem(sessionIdStorageKey(identity))
  }

  const directory: TenantUsersEntry[] = options.directory ?? [
    { tenantId: identity.tenantId, users: [{ userId: identity.userId, displayName: '测试用户' }] }
  ]
  patchApi({ ...options, directory })

  return {
    identity,
    sessionId: allocated,
    sessionIdKey: sessionIdStorageKey(identity),
    mirrorKey: (sessionId = allocated) => mirrorStorageKey(sessionId, identity),
    lockName: (sessionId = allocated) => sessionLockName(sessionId, identity),
    queueSession: (sessionId: number | string) => {
      queuedSessionId = normalizeSessionId(sessionId)
    },
    issuedSessionId: (target = identity) => issuedByFingerprint.get(identityFingerprint(target)) ?? '',
    writeMirror: (sessionId: string, messages: unknown[], extra: Record<string, unknown> = {}) => {
      sessionStorage.setItem(mirrorStorageKey(sessionId, identity), JSON.stringify({
        v: 2,
        savedAt: 1,
        lastRunId: null,
        inFlight: false,
        ...extra,
        messages
      }))
    },
    readMirror: (sessionId: string) => {
      const raw = sessionStorage.getItem(mirrorStorageKey(sessionId, identity))
      return raw ? JSON.parse(raw) as Record<string, unknown> : null
    }
  }
}

/**
 * 指定**下一次**发号返回的号（一次性）。
 *
 * <p>用于"换会话必须拿新号"的用例（新建对话 / 410 恢复 / 复制页签换新号）：夹具默认对同一身份
 * **重复取号稳定**（同一号），这类用例要显式声明"下一次是另一个号"，不能靠删断言。
 */
export function queueNextSessionId(sessionId: number | string): void {
  queuedSessionId = normalizeSessionId(sessionId)
}

/**
 * 身份键形的只读小工具：用例断言"某个键存在/不存在"时用它拼，避免手写裸键
 * （裸键在身份模式下等于"另一个键"，断言会静默永假）。
 */
export function identityKeys(identity: AiIdentity): {
  sessionIdKey: string
  mirrorKey: (sessionId: string) => string
  lockName: (sessionId: string) => string
} {
  return {
    sessionIdKey: sessionIdStorageKey(identity),
    mirrorKey: (sessionId: string) => mirrorStorageKey(sessionId, identity),
    lockName: (sessionId: string) => sessionLockName(sessionId, identity)
  }
}
