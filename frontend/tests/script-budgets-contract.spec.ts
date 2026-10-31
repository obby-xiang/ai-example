/**
 * 脚本侧三条时间预算的**静态自证**（只读脚本事实源，不启动桩 / 不跑 E2E / 不起浏览器）。
 *
 * 为什么要有这层：这三处都是"数值本身不报错、错了只会假绿"的口径 ——
 *   ① 桩的 `stall-frames` 缺省若小于产品的帧间看门狗阈值，缺省值永远触发不了看门狗（假绿）；
 *   ② verify-e2e 的"无新帧"观察窗若短于 2 个心跳周期，抖动一次就漏帧（假绿）；
 *   ③ m2t2a 取消收敛轮询若短于取消重试节奏的末次重试，会把"还没收敛"判成"没收敛"（假红）。
 * 本文件只把三条不等式连同**事实源单点**一起锁住（stub-routes.json ↔ stub-upstream.mjs 两处同值），
 * 不做全量脚本回归 —— 需要真实运行的部分仍由各自的 E2E / 冒烟脚本承担。
 */
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const REPO = path.resolve(process.cwd(), '..')

function readSource(relPath: string): string {
  return readFileSync(path.join(REPO, relPath), 'utf8')
}

describe('scripts/ci · 桩缺省 stall-frames 必须大于产品帧间看门狗阈值', () => {
  it('stub-routes.json 与 stub-upstream.mjs 同值 95000，且 95000 > inter-event-timeout 90s', () => {
    const routes = JSON.parse(readSource('scripts/ci/stub-routes.json')) as {
      faults: { switches: Record<string, { default: number }> }
    }
    expect(routes.faults.switches['stall-frames']!.default).toBe(95_000)

    const stub = readSource('scripts/ci/stub-upstream.mjs')
    expect(stub).toMatch(/'stall-frames':\s*95000\b/)

    const product = readSource('backend/src/main/resources/application.yml')
    const interEvent = /inter-event-timeout:\s*(\d+)s/.exec(product)
    expect(interEvent, 'application.yml 里读不到 inter-event-timeout（产品口径已变，需同步本断言）').not.toBeNull()
    expect(95_000).toBeGreaterThan(Number(interEvent![1]) * 1000)
  })
})

describe('scripts/verify-e2e.ps1 · S-12 迟到帧闸门的观察窗 = 5000ms', () => {
  it('S-12 的 Read-Sse-Brief 窗口取 5000（≥ 2 个 2s 心跳周期 + 抖动余量）', () => {
    const src = readSource('scripts/verify-e2e.ps1')
    const start = src.indexOf("Add-AiCase 'S-12'")
    expect(start, 'verify-e2e.ps1 里找不到 S-12 的 Add-AiCase').toBeGreaterThan(-1)
    const next = src.indexOf("Add-AiCase '", start + 1)
    const block = src.slice(start, next < 0 ? src.length : next)

    const window = /Read-Sse-Brief\s+"[^"]*lastSeq=\$max"\s+(\d+)/.exec(block)
    expect(window, 'S-12 里找不到对归档末号的 Read-Sse-Brief 调用').not.toBeNull()
    expect(Number(window![1])).toBe(5000)
  })
})

describe('scripts/m2t2a-smoke.mjs · 取消收敛轮询上界 = 20s', () => {
  it('CONVERGE_POLLS × CONVERGE_POLL_INTERVAL_MS = 20000，且覆盖取消重试的末次（14s）', () => {
    const src = readSource('scripts/m2t2a-smoke.mjs')
    const polls = /const CONVERGE_POLLS = (\d+)/.exec(src)
    const interval = /const CONVERGE_POLL_INTERVAL_MS = (\d+)/.exec(src)
    expect(polls, 'm2t2a-smoke.mjs 里找不到 CONVERGE_POLLS').not.toBeNull()
    expect(interval, 'm2t2a-smoke.mjs 里找不到 CONVERGE_POLL_INTERVAL_MS').not.toBeNull()

    const windowMs = Number(polls![1]) * Number(interval![1])
    expect(windowMs).toBe(20_000)
    // 取消重试节奏 2s / 6s / 14s：观察窗必须越过末次重试，否则"未收敛"与"太早判"分不开
    expect(windowMs).toBeGreaterThan(14_000)
  })
})
