/**
 * 复制页签 / 所有权（RC-4）测试桩：从 `r166b-fixes.spec.ts` 的 FakeLockManager / FakeBroadcastChannel
 * 原样抽出，供本批新增的页签生命周期用例复用（jsdom 无 Web Locks / BroadcastChannel 实现）。
 */
import { MIRROR_PREFIX, SESSION_ID_KEY } from '@/stores/ai'

/**
 * Web Locks 桩：`held` = **别的页签**已持有的锁名（原页签存活时就该在里面）；
 * `mine` = 本桩模拟的"本页签文档持有"的锁。
 * `ifAvailable` 命中占用 → 以 `null` 回调（规范语义）；空闲 → 持有到回调返回的 promise 落地。
 */
export class FakeLockManager {
  private readonly held = new Set<string>()
  private readonly mine = new Set<string>()

  /** 模拟"别人占着这把锁"（原页签存活）。 */
  hold(name: string): void {
    this.held.add(name)
  }

  /** 模拟"别人关掉页签"：锁随之释放。 */
  release(name: string): void {
    this.held.delete(name)
  }

  /**
   * 模拟"浏览器在文档冻结期间丢掉了本页签的锁"（bfcache 语义不确定的那一面）：
   * 不触发 release 回调，只是让后续探活看到"已不归我"。
   */
  dropMine(name: string): void {
    this.mine.delete(name)
  }

  /** 该锁当前是否被任何一侧持有（本桩自己的或"别人"的）。 */
  isHeld(name: string): boolean {
    return this.held.has(name) || this.mine.has(name)
  }

  request(
    name: string,
    options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown
  ): Promise<unknown> {
    if (this.held.has(name) || this.mine.has(name)) {
      return options?.ifAvailable === true ? Promise.resolve(callback(null)) : new Promise(() => {})
    }
    this.mine.add(name)
    const held = Promise.resolve(callback({ name }))
    return held.then(
      () => this.mine.delete(name),
      () => this.mine.delete(name)
    )
  }
}

/** 同页签组的 BroadcastChannel 桩（同进程同步投递，便于断言）。 */
export const channels = new Set<FakeBroadcastChannel>()

export class FakeBroadcastChannel {
  onmessage: ((event: { data: unknown }) => void) | null = null
  readonly name: string

  constructor(name: string) {
    this.name = name
    channels.add(this)
  }

  postMessage(data: unknown): void {
    for (const channel of [...channels]) {
      if (channel !== this && channel.name === this.name) {
        channel.onmessage?.({ data })
      }
    }
  }

  close(): void {
    channels.delete(this)
  }
}

/** 预置一个"已有存量会话"的页签现场（sessionStorage 被复制页签继承的原样）。 */
export function seedTab(sessionId: string, withMirror = true): void {
  sessionStorage.setItem(SESSION_ID_KEY, sessionId)
  if (withMirror) {
    sessionStorage.setItem(MIRROR_PREFIX + sessionId, JSON.stringify({ v: 2, savedAt: 1, messages: [] }))
  }
}

/** 等待真实毫秒（本仓测试桩不引入 fake timers，避免与 `flushPromises` 的微任务口径打架）。 */
export function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 造一个带 `persisted` 的生命周期事件（jsdom 的 PageTransitionEvent 可用性不稳，直接补属性）。 */
export function pageEvent(type: 'pagehide' | 'pageshow', persisted: boolean): Event {
  const event = new Event(type)
  Object.defineProperty(event, 'persisted', { value: persisted })
  return event
}
