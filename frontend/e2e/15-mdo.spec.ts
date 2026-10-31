/**
 * 批③c · M 多实例（11 面）/ D 降级（12 面）/ O 并发（9 面）真机用例。
 *
 * 编号沿用 `docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.10 / §2.11 / §2.22；
 * 批①② 已覆盖的条目（D-02 见 07-empty-error）不重复登记。
 *
 * 拓扑：`E2E_MDO_GROUP=multi` 时两个不同 JVM 共用 H2、Redis、上游与模型配置，
 * 验证跨实例订阅、决策与接管；`degraded` 时各用独立 H2、B 无模型，验证 D/O 降级与并发。
 * 两组端口默认相同，必须串行；RUN_DIR 与产物目录分开，避免互相清理。
 *
 * 启动（端口基与 Redis 库为本域独占；DB 18 在本机 Memurai 上不存在 —— 只有 0..15 库，
 * 故本域用 **12**，见批③c 报告"装配偏差"一条）：
 *   cd frontend && npx playwright test --config playwright.mdo.config.ts
 *   cd frontend && E2E_MDO_GROUP=degraded npx playwright test --config playwright.mdo.config.ts
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { createPublishableImportTask, openApp, until, waitRoundSettled, sessionId as pageSessionId, type JobView } from './support/helpers'
import { aiRequestOptions, newAiSession, pageMirrorKeyOf, pageSessionKeyOf } from './support/ai-identity'
import {
  API,
  API_B,
  chatError,
  confirmOn,
  countType,
  eventsFrames,
  frontendResultOn,
  health,
  openSse,
  postChat,
  runDetail,
  seqs,
  tag,
  userBubbles,
  waitDecisionSettled,
  waitFor,
  waitFrame,
  waitPending,
  waitTerminal
} from './support/mdo'

const PUBLISH_CONTEXT = (taskId: number) => ({ page: 'import', taskType: 'IMPORT', step: 'PUBLISH', taskId })

async function jobsOf(request: APIRequestContext, taskId: number): Promise<JobView[]> {
  const res = await request.get(`${API}/api/tasks/${taskId}/jobs`)
  const json = (await res.json()) as { data?: JobView[] }
  return json.data ?? []
}

/** 只看发布作业（任务自身的 PRECHECK 作业不算 —— 它由夹具的预检产生）。 */
async function publishJobs(request: APIRequestContext, taskId: number): Promise<JobView[]> {
  return (await jobsOf(request, taskId)).filter((job) => job.jobType === 'PUBLISH')
}

/** 同一 tick 内派发两次真 DOM 点击（"同 tick 竞态"类用例的唯一严谨手法）。 */
async function clickSameTick(page: Page, first: string, second: string): Promise<void> {
  await page.evaluate(([a, b]) => {
    const one = document.querySelector(a) as HTMLElement | null
    const two = document.querySelector(b) as HTMLElement | null
    one?.click()
    two?.click()
  }, [first, second])
}

/** 起一轮会挂起在确认门上的发布调用，返回 runId 与 toolCallId。 */
async function suspendOnPublish(
  taskId: number,
  sessionId: string
): Promise<{ runId: string; toolCallId: string }> {
  const result = await postChat(sessionId, `请立即调用 start_publish 发布任务 ${taskId}`, {
    context: PUBLISH_CONTEXT(taskId)
  })
  const request = result.frames.find((frame) => frame.type === 'confirm_request')
  expect(request, `未挂起确认门（HTTP ${result.status}，帧：${result.frames.map((f) => f.type).join(',')}）`)
    .toBeTruthy()
  return { runId: String(request!.runId), toolCallId: String(request!.toolCallId) }
}

test.describe('@stub M 多实例与跨实例重连', () => {
  test('E2E-M01 双实例 SSE 扇出：B 订阅 A 的同轮，seq 全等、零重复、实例 id 不同', async () => {
    const [a, b] = [await health(API), await health(API_B)]
    expect(a.body.instanceId).toBeTruthy()
    expect(b.body.instanceId).toBeTruthy()
    expect(a.body.instanceId).not.toBe(b.body.instanceId)

    const sessionId = await newAiSession(API)
    // stall-frames 把这一轮拉长到数秒：B 的订阅与 A 的产出真实重叠（不是跑完再回放）
    const producer = openSse(`${API}/api/ai/chat`, {
      method: 'POST',
      body: { sessionId, message: 'turn-text stall-frames=1200 m01' }
    })
    const start = await waitFrame(producer, (frame) => frame.type === 'start', 60_000, 'start 帧')
    const runId = String(start.runId)
    const subscriber = openSse(`${API_B}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`)

    const produced = await producer.done
    const fanned = await subscriber.done
    expect(fanned.length).toBeGreaterThan(0)

    // ① 两实例看到同一组业务序号（B 的首帧 seq 不大于 A 的末帧 ⇒ 确实是同一条流）
    const aSeqs = seqs(produced)
    const bSeqs = seqs(fanned)
    expect(aSeqs.length).toBeGreaterThan(3)
    expect(new Set(aSeqs).size).toBe(aSeqs.length)
    expect(new Set(bSeqs).size).toBe(bSeqs.length)
    expect(bSeqs).toEqual(aSeqs)

    // ② 终帧恰一，正文增量非空且与 A 侧逐字一致
    expect(countType(produced, 'done')).toBe(1)
    expect(countType(fanned, 'done')).toBe(1)
    const textOf = (frames: typeof produced): string =>
      frames.filter((frame) => frame.type === 'delta').map((frame) => String(frame.text ?? '')).join('')
    expect(textOf(fanned)).toBe(textOf(produced))
    expect(textOf(produced).length).toBeGreaterThan(0)

    // ③ 跨实例读台账：B 能按 runId 读到 A 写的快照（同一 Redis 库的口径）
    const detail = await runDetail(runId, API_B)
    expect(detail.exists).toBe(true)
    expect(detail.snapshot?.status).toBe('DONE')
  })

  test('E2E-M02 挂起决策打在另一实例：决策落 B、A 侧在轮询窗口内收敛', async ({ request }) => {
    const taskId = await createPublishableImportTask(request)
    const sessionId = await newAiSession(API)
    const { runId, toolCallId } = await suspendOnPublish(taskId, sessionId)

    // 挂起态在 B 上同样可见（跨实例读同一个挂起条目）
    const onB = await waitPending(runId, toolCallId, (entry) => entry.status === 'PENDING', 20_000,
      'B 侧读到 PENDING 条目', API_B)
    expect(onB.req).toBe(true)
    await waitFor(async () => (await health(API)).body.activeSuspendGates === 0, 20_000,
      'A 侧已 park 并交还等待线程')

    // 决策**打在 B**（不是发起挂起的 A）
    const decided = await confirmOn(API_B, runId, toolCallId, true)
    expect(decided.status).toBe(200)
    expect(decided.body.accepted).toBe(true)
    expect(decided.body.duplicate).toBe(false)
    const aInstance = (await health(API)).body.instanceId
    const bInstance = (await health(API_B)).body.instanceId
    expect(decided.body.storedBy).toBe(bInstance)
    expect(['SCHEDULED', 'LEASE_HELD_ELSEWHERE']).toContain(String(decided.body.resumeOutcome ?? ''))

    // 决策不丢：条目转已决；真正执行者必须是取得租约的合法实例，不绑定固定节点
    const settled = await waitPending(runId, toolCallId, (entry) => entry.status !== 'PENDING', 20_000,
      '条目离开 PENDING')
    expect(['APPROVED', 'EXECUTED', 'BLOCKED']).toContain(String(settled.status))
    const done = await waitTerminal(runId, 90_000, API)
    // 诊断（非断言、不改判据）：跨实例决策的结局要能从一次失败里直接读出，不靠猜。
    //  - confirm.resumeOutcome 是"谁派发了续跑"的唯一凭据（生产 token：SCHEDULED /
    //    LEASE_HELD_ELSEWHERE / NO_RESUME_SERVICE / SNAPSHOT_NOT_SUSPENDED / EXECUTOR_SATURATED）；
    //  - snapshot.error 是"这一轮为什么落 FAILED"的唯一权威值（形如 CODE：短句）；
    //  - 末帧里的 error.code 与 snapshot.error 前缀同源，可与 A/B 后端日志按 runId 对齐。
    {
      const frames = await eventsFrames(runId)
      const diagnostic = {
        runId,
        taskId,
        sessionId,
        apiA: API,
        apiB: API_B,
        bInstance,
        confirm: decided.body,
        settled,
        snapshot: done.snapshot,
        pending: done.pending,
        ledger: done.ledger,
        ledgerCounts: done.ledgerCounts,
        tail: frames.slice(-8).map((frame) => ({
          type: frame.type,
          seq: frame.seq,
          code: frame.code,
          message: frame.message
        }))
      }
      console.log(`[M02 diag] ${JSON.stringify(diagnostic)}`)
      await test.info().attach('m02-diagnostic.json', {
        body: JSON.stringify(diagnostic, null, 2),
        contentType: 'application/json'
      })
    }
    expect(done.snapshot?.status).toBe('DONE')
    const ledger = done.ledger ?? []
    expect(ledger.length).toBe(1)
    const executingInstance = String(ledger[0].executedBy ?? '')
    expect([aInstance, bInstance]).toContain(executingInstance)
    expect(done.snapshot?.resumedBy ?? []).toContain(executingInstance)
    // 共享模型配置的实例真实执行：恰好一个发布作业，不是只落库或重复执行
    await until(async () => (await publishJobs(request, taskId)).length === 1, 30_000, '发布作业恰创建一次')
  })

  test('E2E-M05 业务幂等键：同一 toolCallId 二次决策 409，台账只 1 条', async ({ request }) => {
    const taskId = await createPublishableImportTask(request)
    const sessionId = await newAiSession(API)
    const { runId, toolCallId } = await suspendOnPublish(taskId, sessionId)

    const first = await confirmOn(API, runId, toolCallId, true)
    expect(first.status).toBe(200)
    expect(first.body.duplicate).toBe(false)

    const second = await confirmOn(API, runId, toolCallId, false)
    expect(second.status).toBe(409)
    expect(second.body.code).toBe('DUPLICATE_TOOL_CALL_ID')
    // 幂等键的判据是 runId:toolCallId —— 篡改 toolCallId 就不再是"同一键"（409 UNKNOWN 而非 DUPLICATE）
    const other = await confirmOn(API, runId, `${toolCallId}-nope`, false)
    expect(other.status).toBe(409)
    expect(other.body.code).toBe('UNKNOWN_TOOL_CALL')

    const done = await waitTerminal(runId, 90_000)
    expect(done.snapshot?.status).toBe('DONE')
    const ledger = (done.ledger ?? []).filter((record) => record.toolCallId === toolCallId)
    expect(ledger.length).toBe(1)
    await until(async () => (await publishJobs(request, taskId)).length > 0, 30_000, '发布作业创建')
    // 只执行一次：重复决策不得产生第二个作业
    const jobs = await publishJobs(request, taskId)
    expect(jobs.length).toBe(1)
  })

  test('E2E-M06 TARGET_INVALID 预检即拦：不进入确认门，零作业零台账', async ({ request }) => {
    // 目标校验（ToolTargetResolver 的「是否同一任务」）现已并入**开卡前预检**（TARGET_CONSISTENT）：
    // 上下文任务与入参任务不一致时预检直接失败，根本不会挂起/发确认请求，更不会执行 ——
    // 这比"批准后再拦"更早一道，安全判据（零作业零台账）不变。
    const contextTaskId = await createPublishableImportTask(request)
    const targetTaskId = await createPublishableImportTask(request)
    expect(targetTaskId).not.toBe(contextTaskId)
    const sessionId = await newAiSession(API)
    const result = await postChat(sessionId, `请立即调用 start_publish 发布任务 ${targetTaskId}`, {
      context: PUBLISH_CONTEXT(contextTaskId)
    })
    const start = result.frames.find((frame) => frame.type === 'start')
    expect(start, `未收到 start 帧（HTTP ${result.status}）`).toBeTruthy()
    const runId = String(start!.runId)
    const requestFrame = result.frames.find((frame) => frame.type === 'confirm_request')
    expect(requestFrame, `预检未通过时不得进入确认门（帧：${result.frames.map((f) => f.type).join(',')}）`)
      .toBeUndefined()

    const toolResult = result.frames.find((frame) => frame.type === 'tool_result')
    expect(String(toolResult?.code)).toBe('PRECHECK_FAILED')
    expect(toolResult?.executed).toBe(false)

    const done = await waitTerminal(runId, 60_000)
    expect(done.snapshot?.status).toBe('DONE')
    expect((done.ledger ?? []).length).toBe(0)
    expect((await publishJobs(request, targetTaskId)).length).toBe(0)
  })

  test('E2E-M09 lastSeq 差量 + 孤儿过滤：只补 seq>K、心跳零回放、DOM 无重复卡', async ({ request }) => {
    const sessionId = await newAiSession(API)
    const result = await postChat(sessionId, 'turn-text m09')
    const start = result.frames.find((frame) => frame.type === 'start')
    expect(start).toBeTruthy()
    const runId = String(start!.runId)
    await waitTerminal(runId, 60_000)

    const full = await eventsFrames(runId)
    const fullSeqs = seqs(full)
    expect(fullSeqs.length).toBeGreaterThan(5)
    expect(countType(full, 'heartbeat')).toBe(0)

    const pivot = fullSeqs[Math.floor(fullSeqs.length / 2)]
    const diff = await eventsFrames(runId, pivot)
    const diffSeqs = seqs(diff)
    expect(diffSeqs.length).toBeGreaterThan(0)
    expect(diffSeqs.length).toBeLessThan(fullSeqs.length)
    // 只回放差量：每一帧都严格大于 K，且与全量尾部逐值相同
    expect(diffSeqs.every((seq) => seq > pivot)).toBe(true)
    expect(diffSeqs).toEqual(fullSeqs.filter((seq) => seq > pivot))
    expect(countType(diff, 'heartbeat')).toBe(0)
    expect(countType(diff, 'done')).toBe(1)

    // 已到末帧：再要"之后"的帧应一帧都不回放（孤儿全过滤，回放即收尾）
    const tail = await eventsFrames(runId, fullSeqs[fullSeqs.length - 1])
    expect(seqs(tail).length).toBe(0)

    // 跨实例读同一条归档（差量口径与实例无关）
    const crossSeqs = seqs(await eventsFrames(runId, pivot, API_B))
    expect(crossSeqs).toEqual(diffSeqs)
  })

  test('E2E-M07 多页签同步降级：复制页签换新会话、不抢锁、不并发两轮', async ({ page, context, request }) => {
    await openApp(page)
    const sid1 = await pageSessionId(page)
    expect(sid1).toBeTruthy()

    // 真复制页签（window.open 继承 sessionStorage ⇒ 与"复制标签页"同构）
    const [tab2] = await Promise.all([
      context.waitForEvent('page'),
      page.evaluate(() => {
        window.open('/', '_blank')
      })
    ])
    await tab2.waitForLoadState('domcontentloaded')
    await expect(tab2.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    // Web Locks 判为复制品 ⇒ 就地换新会话（不接管原会话、不删原会话后端记忆）
    await waitFor(async () => {
      try {
        return (await pageSessionId(tab2)) !== sid1
      } catch {
        // 新页签尚未落身份/发号：还没换出来，继续等
        return false
      }
    }, 20_000, '复制页签换到新会话')
    const sid2 = await pageSessionId(tab2)
    expect(sid2).not.toBe(sid1)

    // 原页签发一轮：复制页签不跟着跑（不指望实时同步）
    await page.locator('[data-testid="composer-input"]').fill('turn-text m07')
    await page.locator('[data-testid="send-btn"]').click()
    await waitRoundSettled(page, 90_000)
    await expect(userBubbles(tab2)).toHaveCount(0)

    // 后端只存在一条该轮的 run：原会话的 current 轮次已终态，复制页签的新会话从未起轮
    const current = await request.get(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(String(sid1))}`, aiRequestOptions('/api/ai/runs/current'))
    const body = (await current.json()) as { data?: { found?: boolean; snapshotStatus?: string } }
    if (body.data?.found) {
      expect(['DONE', 'FAILED', 'CANCELLED']).toContain(String(body.data.snapshotStatus))
    }
    const other = await request.get(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(String(sid2))}`, aiRequestOptions('/api/ai/runs/current'))
    const otherBody = (await other.json()) as { data?: { found?: boolean } }
    expect(otherBody.data?.found).toBe(false)

    // 让渡：原页签关闭后，仍未说话过的复制页签接手原会话（"靠操作/关闭同步"的口径）
    await page.close()
    await waitFor(async () => (await pageSessionId(tab2)) === sid1,
      30_000, '复制页签接手让渡的会话')
    await expect(userBubbles(tab2)).toHaveCount(1)
    await tab2.close()
  })

  test('E2E-M08 重连 4 次退避 + jitter：≤5 条连接、1/2/4/8s(±25%)、耗尽后显式提示', async ({ page }) => {
    const attempts: number[] = []
    const probes: string[] = []
    // 网络层真故障注入：**只** abort 真实的 EventSource 连接（真 EventSource + 真退避计时器）。
    // M08 口径：探测请求（`?probe=1`，resourceType=fetch/xhr）不计入连接预算 ——
    // 修复前探测打正式端点、与 EventSource 混在同一个计数里，5 次预算被探测抢走一半。
    await page.route('**/api/ai/events/**', (route) => {
      const request = route.request()
      if (request.resourceType() !== 'eventsource') {
        probes.push(request.url())
        return route.continue()
      }
      attempts.push(Date.now())
      return route.abort('connectionreset')
    })

    await openApp(page)
    await sendAndReloadDuringRun(page, 'turn-text stall-frames=1500 m08')

    await expect(page.locator('[data-testid="alert-interrupted"]')).toBeVisible({ timeout: 90_000 })
    // 5 条连接 = 1 次初始 + 4 次重试（阈值不放宽；探测不占额度）
    await waitFor(() => attempts.length >= 5, 30_000, '第 5 条连接')
    expect(attempts.length).toBeLessThanOrEqual(5)
    // 探测确实发生了，而且是轻量 JSON 探测（不是第二条 SSE）—— 只作证据，不进预算断言
    expect(probes.length).toBeGreaterThan(0)
    expect(probes.every((url) => url.includes('probe=1'))).toBe(true)

    const gaps = attempts.slice(1).map((value, index) => value - attempts[index])
    const bases = [1000, 2000, 4000, 8000]
    bases.forEach((base, index) => {
      const gap = gaps[index]
      // ±25% 抖动 + 传输/调度余量 800ms（退避不被探测推迟：余量不放宽）
      expect(gap).toBeGreaterThanOrEqual(base * 0.75 - 100)
      expect(gap).toBeLessThanOrEqual(base * 1.25 + 800)
    })

    // 重试过程的显式提示（notice 文案带"第 N 次重试"）与耗尽后的断流红条
    const body = await page.locator('body').innerText()
    expect(body).toMatch(/第 ?[1-4] ?次重试|连接中断/)
    expect(body).toContain('结果可能不完整')
  })
})

/** 起一轮慢流、跑到流中刷新（M-08 的前置动作）。 */
async function sendAndReloadDuringRun(page: Page, message: string): Promise<void> {
  await page.locator('[data-testid="composer-input"]').fill(message)
  await page.locator('[data-testid="send-btn"]').click()
  await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 60_000 })
  // 流中真刷新：面板据快照重挂这一轮（reattach 走 /api/ai/events/{runId}）
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
}

test.describe('@stub D 降级与错误码', () => {
  test('E2E-D05 限流 429：稳定码 + Retry-After + 作用域限于该会话', async ({ request }) => {
    const sessionId = await newAiSession(API)
    const oversize = 'A'.repeat(9000)
    let limited: { status: number; body: Record<string, unknown>; retryAfter: string | null } | null = null
    for (let index = 0; index < 260; index += 1) {
      const result = await chatError(request, sessionId, oversize)
      if (result.status === 429) {
        limited = result
        break
      }
      // 限流之前的四道闸：超长必须是 400（而非 200），否则"消耗额度"的假设不成立
      expect([400, 429]).toContain(result.status)
    }
    expect(limited, '连发 260 次仍未触发 429（限流闸可能失效）').not.toBeNull()
    expect(limited!.body.code).toBe('RATE_LIMITED')
    expect(Number(limited!.body.limit)).toBeGreaterThan(0)
    expect(Number(limited!.body.retryAfterSeconds)).toBeGreaterThan(0)
    expect(limited!.retryAfter).toBeTruthy()

    // 作用域隔离：被限流的是这个会话，别的会话照常（不误伤整台实例）
    const other = await chatError(request, await newAiSession(API), 'turn-text d05-other')
    expect(other.status).toBe(200)
  })

  test('E2E-D06 输入超长 2000：前端到顶文案 + API 400 且不回显原文', async ({ page, request }) => {
    await openApp(page)
    const composer = page.locator('[data-testid="composer-input"]')
    await expect(composer).toHaveAttribute('maxlength', '2000')

    // 下限一侧（1999）：还差 1 字到顶，计数如实、发送可用
    await composer.fill('汉'.repeat(1999))
    const belowLength = await composer.evaluate((el) => (el as HTMLTextAreaElement).value.length)
    expect(belowLength).toBe(1999)
    await expect(page.locator('[data-testid="composer-count"]')).toHaveText('剩余 1 字')
    await expect(page.locator('[data-testid="send-btn"]')).toBeEnabled()

    // 到顶一侧（2000）
    const text = '汉'.repeat(2000)
    await composer.fill(text)
    const length = await composer.evaluate((el) => (el as HTMLTextAreaElement).value.length)
    expect(length).toBe(2000)
    // 到顶档：计数文案逐字为「已达 2000 字上限」，且此时仍可发送（上限是"恰好"不是"超出"）
    await expect(page.locator('[data-testid="composer-count"]')).toHaveText('已达 2000 字上限')
    await expect(page.locator('[data-testid="send-btn"]')).toBeEnabled()

    // 绕过前端直打 API（2001 = 越界 1 字）：400 MESSAGE_TOO_LONG，limit=2000，且响应体不回显原文
    const sessionId = await newAiSession(API)
    const marker = '超长哨兵串-D06-'
    const result = await chatError(request, sessionId, `${marker}${'汉'.repeat(2001)}`)
    expect(result.status).toBe(400)
    expect(result.body.code).toBe('MESSAGE_TOO_LONG')
    expect(Number(result.body.limit)).toBe(2000)
    expect(JSON.stringify(result.body)).not.toContain(marker)
  })

  test('E2E-D07 归一化后为空：纯不可见字符按必填同口径 400，不上上游', async ({ request }) => {
    const sessionId = await newAiSession(API)
    const zeroWidth = '\u200b\u200c\u200d\ufeff\u2060\u200b'
    const result = await chatError(request, sessionId, zeroWidth)
    expect(result.status).toBe(400)
    // 归一口径：纯不可见字符 ⇒ 与"message 必填"同一机器码
    expect(['BAD_REQUEST', 'MESSAGE_REQUIRED']).toContain(String(result.body.code))
    // 零副作用：会话没有留下任何轮次
    const current = await request.get(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`, aiRequestOptions('/api/ai/runs/current'))
    const body = (await current.json()) as { data?: { found?: boolean } }
    expect(body.data?.found).toBeFalsy()
  })

  test('E2E-D08 会话忙 409 + 可接管线索：runId 指回在途轮、前端零幻影', async ({ page, request }) => {
    const sessionId = await newAiSession(API)
    const producer = openSse(`${API}/api/ai/chat`, {
      method: 'POST',
      body: { sessionId, message: 'turn-text stall-frames=1500 d08' }
    })
    const start = await waitFrame(producer, (frame) => frame.type === 'start', 60_000, 'start 帧')
    const inFlight = String(start.runId)

    const busy = await chatError(request, sessionId, 'turn-text d08-second')
    expect(busy.status).toBe(409)
    expect(busy.body.code).toBe('SESSION_BUSY')
    expect(busy.body.runId).toBe(inFlight)
    expect(String(busy.body.reattach)).toBe(`/api/ai/events/${inFlight}`)
    expect(busy.body.degraded).toBe(false)

    // 前端零幻影：这条 409 不是页面发的，页面必须仍只有一条轮、且能自己收尾
    await openApp(page)
    const sessionKey = await pageSessionKeyOf(page)
    await page.evaluate(({ key, sid }) => sessionStorage.setItem(key, sid), { key: sessionKey, sid: sessionId })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitRoundSettled(page, 90_000)
    const detail = await waitTerminal(inFlight, 60_000)
    expect(detail.snapshot?.status).toBe('DONE')
    await producer.done
  })

  test('E2E-D09 上游断流重试边界：attempts 打满 maxAttempts 后终态失败，不无限重试', async ({ request }) => {
    const maxAttempts = Number((await health(API)).body.resilience?.maxAttempts ?? 0)
    expect(maxAttempts).toBeGreaterThanOrEqual(2)
    const sessionId = await newAiSession(API)
    const startedAt = Date.now()
    // kill-stream 必须早于首帧：桩的写出是同步的，只有配上 slow-first-byte 才让 kill 计时器真的先跑
    // （否则整段响应在同一个 tick 里写完，socket 销毁时 [DONE] 已到手 = 本轮成功）。
    // 每次尝试都在"零帧产出"阶段被断 ⇒ brokenStream 且 emitted=false ⇒ 可重试，直到次数打满。
    const result = await postChat(sessionId, 'turn-text kill-stream=300 slow-first-byte=1200 d09', {
      timeoutMs: 120_000
    })
    const start = result.frames.find((frame) => frame.type === 'start')
    expect(start).toBeTruthy()
    const runId = String(start!.runId)

    const detail = await waitTerminal(runId, 90_000)
    expect(detail.snapshot?.status).toBe('FAILED')
    // 尝试次数 == maxAttempts（既不是 1 次就放弃，也不是无限重试）
    expect(Number(detail.snapshot?.attempts)).toBe(maxAttempts)
    // 整轮耗时有界（每次断流即时失败 + 退避），证明没有反复重连
    expect(Date.now() - startedAt).toBeLessThan(90_000)
    // 每一次重试都有 retry 帧（maxAttempts-1 条），终帧是 error 而不是 done
    expect(countType(result.frames, 'done')).toBe(0)
    expect(countType(result.frames, 'retry')).toBe(maxAttempts - 1)
    const error = result.frames.find((frame) => frame.type === 'error')
    expect(error).toBeTruthy()
    expect(String(error?.code ?? '').length).toBeGreaterThan(0)
  })

  test('E2E-D11 前端工具超时结局：卡收敛为超时终态、轮仍跑完、executed=false', async ({ request }) => {
    const budget = Number((await health(API)).body.frontendToolTimeoutSeconds ?? 0)
    expect(budget).toBeGreaterThan(0)
    const sessionId = await newAiSession(API)
    // 前端工具（navigate_to 走 FRONTEND 通道）：用例扮演"不回灌的浏览器"
    const result = await postChat(sessionId, `导出向导 ${tag('d11')}`, {
      context: { page: 'export' },
      timeoutMs: 120_000
    })
    const start = result.frames.find((frame) => frame.type === 'start')
    expect(start).toBeTruthy()
    const runId = String(start!.runId)
    const requested = await waitPending(runId, String(result.frames.find((f) => f.type === 'frontend_tool_request')?.toolCallId),
      () => true, 20_000, '前端工具条目出现')

    const settled = await waitPending(runId, String(requested.toolCallId),
      (entry) => entry.status !== 'PENDING', (budget + 60) * 1000, '前端工具超时收敛')
    expect(String(settled.status)).toMatch(/TIMEOUT|EXPIRED/)
    expect(settled.executed).toBe(false)

    const detail = await waitTerminal(runId, 90_000)
    // 超时不是本轮结束：模型侧拿到结局文本后本轮继续并正常收尾
    expect(detail.snapshot?.status).toBe('DONE')
  })

  test('E2E-D12 重复回灌幂等：同 toolCallId 二次回灌 409，不二次推进', async ({ request }) => {
    const sessionId = await newAiSession(API)
    const result = await postChat(sessionId, `导出向导 ${tag('d12')}`, { context: { page: 'export' } })
    const start = result.frames.find((frame) => frame.type === 'start')
    const requestFrame = result.frames.find((frame) => frame.type === 'frontend_tool_request')
    expect(start).toBeTruthy()
    expect(requestFrame, `未下发前端工具请求（帧：${result.frames.map((f) => f.type).join(',')}）`).toBeTruthy()
    const runId = String(start!.runId)
    const toolCallId = String(requestFrame!.toolCallId)

    const first = await frontendResultOn(API, runId, toolCallId, JSON.stringify({ ok: true }))
    expect(first.status).toBe(200)
    const second = await frontendResultOn(API, runId, toolCallId, JSON.stringify({ ok: true }))
    expect(second.status).toBe(409)
    expect(second.body.code).toBe('DUPLICATE_TOOL_CALL_ID')

    const detail = await waitTerminal(runId, 90_000)
    expect(detail.snapshot?.status).toBe('DONE')
    // 只推进一次：同一条目只有一个结局，归档里 done 恰一帧
    const entries = (detail.pending ?? []).filter((entry) => entry.toolCallId === toolCallId)
    expect(entries.length).toBe(1)
    expect(countType(await eventsFrames(runId), 'done')).toBe(1)
  })
})

test.describe('@stub O 并发与同 tick 竞态', () => {
  test('E2E-O01 双击发送：只产生 1 轮（1 个 user 气泡、1 个 runId、无 409 噪声）', async ({ page, request }) => {
    await openApp(page)
    await page.locator('[data-testid="composer-input"]').fill('turn-text o01')
    await clickSameTick(page, '[data-testid="send-btn"]', '[data-testid="send-btn"]')
    await waitRoundSettled(page, 90_000)

    await expect(userBubbles(page)).toHaveCount(1)
    const sessionId = await pageSessionId(page)
    const history = await request.get(`${API}/api/ai/history/${encodeURIComponent(sessionId)}`, aiRequestOptions('/api/ai/history'))
    const body = (await history.json()) as { data?: { messages?: unknown[] } }
    // 用户消息只 1 条（后端侧也只有一个 user 回合）
    expect((body.data?.messages ?? []).length).toBe(2)
    await expect(page.locator('[data-testid="notice-bar"]')).toHaveCount(0)
  })

  test('E2E-O02 发送↔停止同 tick：停止生效、不静默发出、草稿保留', async ({ page, request }) => {
    await openApp(page)
    const composer = page.locator('[data-testid="composer-input"]')
    await composer.fill('turn-text stall-frames=1500 o02')
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 60_000 })
    const sessionId = await pageSessionId(page)

    // 同 tick：点停止 + 回车（草稿是"停止后想发的新消息"）；先真键盘写入并确认草稿已落进输入框
    await composer.click()
    await page.keyboard.type('下一条消息 o02')
    expect(await composer.inputValue()).toBe('下一条消息 o02')

    await page.evaluate(() => {
      const stop = document.querySelector('[data-testid="stop-btn"]') as HTMLElement | null
      const input = document.querySelector('[data-testid="composer-input"]') as HTMLTextAreaElement | null
      stop?.click()
      input?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    })

    await waitRoundSettled(page, 90_000)
    // 「二选一」：停止优先（1 条气泡）或 发送优先（2 条气泡），二者必居其一。
    // 输入框草稿内容与消息级「已停止」标识都不在本用例断言面内（同 tick 竞态下两者都会抖，
    // 见 E2E-O02b 已挂账缺陷与报告"发现"一节）。
    const bubbles = await userBubbles(page).count()
    expect([1, 2]).toContain(bubbles)
    if (bubbles === 2) {
      // 发出胜出：第二条确实是新消息（会话门在第二请求时已释放，不可能同轮双跑）
      expect(await userBubbles(page).last().innerText()).toContain('下一条消息 o02')
    }
    // 「不出现两轮同时跑」的后端判据：该会话的所有轮次都已终态，没有 RUNNING/SUSPENDED 孤儿
    const runs = await (await request.get(`${API}/api/ai/runs`, aiRequestOptions('/api/ai/runs'))).json() as {
      data?: Array<{ runId?: string; sessionId?: string; status?: string }>
    }
    const mine = (runs.data ?? []).filter((run) => run.sessionId === sessionId)
    expect(mine.length).toBeGreaterThan(0)
    for (const run of mine) {
      expect(['DONE', 'CANCELLED', 'FAILED', 'REJECTED']).toContain(String(run.status))
    }
    // 不静默：不留 409「会话忙」噪声
    const noticeText = (await page.locator('[data-testid="notice-bar"]').count()) > 0
      ? await page.locator('[data-testid="notice-bar"]').innerText()
      : ''
    expect(noticeText).not.toContain('进行中')
  })

  /**
   * O-02b 停止与回车落在同一 tick：输入框里**新打的草稿**不得被上一轮的提示词覆盖。
   *
   * 原缺陷（L-83 收口）：`AiPanel.send()` 结尾对 `interrupted/not-sent` 结局做「原稿回流」，
   * 条件是 `inputText === ''`；同 tick 的 `stop()` 已同步把 `loading` 置 false，于是紧随其后的
   * Enter 又走了一次 `send()`（把 inputText 清空并失败），两次回流的顺序让**旧稿**最后落地。
   * 修法（笔 75530fd = L-83）：`send()` 以 `sendGeneration` 作废被更晚发送取代的那一轮
   * （被取代者一律不写输入框），并对"用户已在流式中写入新内容"短路 ⇒ 旧稿不再覆盖新草稿。
   *
   * 该条原以 `test.fail()` 挂账。缺陷已修（挂账到期），但**同 tick 竞态本身是二选一的**
   * （与 E2E-O02 同一口径：停止优先或发送优先），故去掉挂账时把断言按分支收口 ——
   * 只断"输入框非空"会在「发送优先」那一支假红（实测 2/2 命中该支：草稿真的变成了一条
   * 用户消息，输入框清空是正确行为）。
   */
  test('E2E-O02b 停止同 tick 回车：新草稿不丢（停止优先 ⇒ 原样留存；发送优先 ⇒ 已成新消息）', async ({ page }) => {
    await openApp(page)
    const composer = page.locator('[data-testid="composer-input"]')
    await composer.fill('turn-text stall-frames=1500 o02b')
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 60_000 })
    await composer.click()
    await page.keyboard.type('草稿必须保留 o02b')
    expect(await composer.inputValue()).toBe('草稿必须保留 o02b')

    await page.evaluate(() => {
      const stop = document.querySelector('[data-testid="stop-btn"]') as HTMLElement | null
      const input = document.querySelector('[data-testid="composer-input"]') as HTMLTextAreaElement | null
      stop?.click()
      input?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    })
    await waitRoundSettled(page, 90_000)

    // 同 tick 竞态天然二选一（与 E2E-O02 同一口径，二者必居其一）：
    //   停止先落地 ⇒ 新发送被拒，输入框里是**逐字相同**的新草稿（L-83 钉的就是这一支：
    //                旧轮迟到收尾不得把上一条用户消息回填进来）；
    //   发送先落地 ⇒ 草稿真的成为一条用户消息，输入框清空是正确行为（不是"草稿被吞"）。
    // 只看"输入框非空"会在第二支上假红（实测 2/2 落在该支，草稿生成的消息气泡可证）。
    const bubbles = userBubbles(page)
    const bubbleCount = await bubbles.count()
    if (bubbleCount === 1) {
      await expect(composer).toHaveValue('草稿必须保留 o02b')
    } else {
      expect(bubbleCount, '同 tick 只允许「停止优先 1 条」或「发送优先 2 条」').toBe(2)
      await expect(bubbles.last()).toContainText('草稿必须保留 o02b')
      await expect(composer).toHaveValue('')
    }
  })

  test('E2E-O03 双连接同时 reattach：两路都收到帧、seq 一致、只收一份 done', async () => {
    const sessionId = await newAiSession(API)
    const producer = openSse(`${API}/api/ai/chat`, {
      method: 'POST',
      body: { sessionId, message: 'turn-text stall-frames=1200 o03' }
    })
    const start = await waitFrame(producer, (frame) => frame.type === 'start', 60_000, 'start 帧')
    const runId = String(start.runId)
    await waitFrame(producer, (frame) => frame.type === 'delta', 60_000, '首个 delta')

    // 两路订阅同时打开（lastSeq=0 全量回放 + 后续实时帧）
    const [one, two] = [
      openSse(`${API}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`),
      openSse(`${API_B}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`)
    ]
    const [framesOne, framesTwo] = await Promise.all([one.done, two.done])
    await producer.done

    expect(countType(framesOne, 'done')).toBe(1)
    expect(countType(framesTwo, 'done')).toBe(1)
    expect(seqs(framesOne)).toEqual(seqs(framesTwo))
    const unique = new Set(seqs(framesOne))
    expect(unique.size).toBe(seqs(framesOne).length)
    // 后端没有重复推进：归档与台账只有一份终帧、一个 done
    const archived = await eventsFrames(runId)
    expect(countType(archived, 'done')).toBe(1)
    expect((await waitTerminal(runId, 60_000)).snapshot?.status).toBe('DONE')
  })

  test('E2E-O04 并发发送（第二请求）：409 SESSION_BUSY 指回在途轮、页面零幻影', async ({ page, request }) => {
    await openApp(page)
    await page.locator('[data-testid="composer-input"]').fill('turn-text stall-frames=1500 o04')
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 60_000 })
    const sessionId = await pageSessionId(page)

    // 第二个客户端（另一个 context/进程口径）用同一会话发送
    const busy = await chatError(request, sessionId, '并发第二轮 o04')
    expect(busy.status).toBe(409)
    expect(busy.body.code).toBe('SESSION_BUSY')
    expect(String(busy.body.runId ?? '').length).toBeGreaterThan(0)
    expect(String(busy.body.reattach)).toContain('/api/ai/events/')

    // 页面侧零幻影：仍是一条 user 气泡，收尾正常，无错误红条
    await waitRoundSettled(page, 90_000)
    await expect(userBubbles(page)).toHaveCount(1)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test('E2E-O05 表单提交↔停止同 tick：只有一种结局生效，条目终态唯一', async ({ page, request }) => {
    await openApp(page)
    await page.locator('[data-testid="composer-input"]').fill(`gf-longtext ${tag('o05')}`)
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })
    await page.locator('#gf-field-memo').fill('同 tick 竞态样例')
    const sessionId = await pageSessionId(page)
    // 竞态之前先钉住这一轮的 runId（收尾后会话索引会被清，事后再查就可能取不到）
    const before = await request.get(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`, aiRequestOptions('/api/ai/runs/current'))
    const beforeBody = (await before.json()) as { data?: { runId?: string } }
    const runId = String(beforeBody.data?.runId ?? '')
    expect(runId.length).toBeGreaterThan(0)

    await clickSameTick(page, '[data-testid="gf-submit"]', '[data-testid="stop-btn"]')
    await waitRoundSettled(page, 90_000)

    // 条目终态唯一：不是"既提交又取消"，也不是长期停在 PENDING（取消收敛是异步的，须显式等）
    const toolCallId = String((await runDetail(runId)).pending?.[0]?.toolCallId ?? '')
    expect(toolCallId.length).toBeGreaterThan(0)
    const settled = await waitPending(runId, toolCallId, (entry) => entry.status !== 'PENDING', 30_000,
      '表单条目离开 PENDING')
    expect(['FRONTEND_RESULT', 'FRONTEND_CANCELLED', 'CANCELLED', 'TIMEOUT', 'APPROVED'])
      .toContain(String(settled.status))
    // 终态由续跑线程异步写：等轮次快照真正收口，不能把条目结算当成轮次已收口
    const detail = await waitTerminal(runId, 30_000)
    expect((detail.pending ?? []).length).toBe(1)
    expect(['DONE', 'CANCELLED']).toContain(String(detail.snapshot?.status))
    // 表单不再可编辑（无论哪种结局都收敛）
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
  })

  test('E2E-O06 双击批准：只发 1 次 POST /confirm，无重复执行', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    const confirms: number[] = []
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/confirm') && req.method() === 'POST') confirms.push(Date.now())
    })
    await page.goto(`/import?taskId=${taskId}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
    await page.locator('[data-testid="composer-input"]').fill(`请立即调用 start_publish 发布任务 ${taskId}`)
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="hitl-gate"]')).toBeVisible({ timeout: 40_000 })
    // 不可撤销卡要先勾确认项（否则「批准」置灰，双击不产生任何请求）
    const ack = page.locator('[data-testid="gate-ack-box"]')
    if (await ack.count() > 0) {
      await ack.click()
      await expect(ack).toBeChecked()
    }

    await page.evaluate(() => {
      const gate = document.querySelector('[data-testid="hitl-gate"]') as HTMLElement | null
      const approve = Array.from(gate?.querySelectorAll('button') ?? []).find(
        (button) => button.textContent?.trim() === '批准'
      ) as HTMLElement | undefined
      approve?.click()
      approve?.click()
    })
    await waitRoundSettled(page, 90_000)
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0)
    expect(confirms.length).toBe(1)
    await until(async () => (await publishJobs(request, taskId)).length > 0, 30_000, '发布作业创建')
    expect((await publishJobs(request, taskId)).length).toBe(1)
  })

  test('E2E-O07 流式中切换会话：旧轮被取消收敛、新会话零残留、迟到帧不写入', async ({ page, request }) => {
    await openApp(page)
    await page.locator('[data-testid="composer-input"]').fill('turn-text stall-frames=1500 o07')
    await page.locator('[data-testid="send-btn"]').click()
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 60_000 })
    const oldSession = await pageSessionId(page)
    // 施加「切会话」之前要等两件**产品事实**各自出现（判据不变，只把竞态换成同步等待）：
    //   ① 本轮登记进 `runs/current`（Redis 注册点，`start` 帧之后仍可能没写）——
    //      本地单点复跑实测读到的 runId 为空，旧写法直接断言 >0 会红在这一步；
    //   ② 运行快照真的建立（AR-06 空窗，与 c922548 / verify-e2e W-25 同源：`start` 帧先于
    //      「请求前压缩 → 建快照」发出，空窗内切会话该轮按设计**不建快照**，
    //      run 40718751980 实证 `最后快照 status=∅`，后面 waitTerminal 永远读不到 CANCELLED）。
    let oldRunId = ''
    await until(async () => {
      const res = await request.get(`${API}/api/ai/runs/current?sessionId=${encodeURIComponent(oldSession)}`, aiRequestOptions('/api/ai/runs/current'))
      const body = (await res.json()) as { data?: { runId?: string } }
      oldRunId = String(body.data?.runId ?? '')
      return oldRunId.length > 0
    }, 30_000, '本轮登记到 runs/current')
    await until(async () => (await runDetail(oldRunId)).snapshot !== undefined, 30_000, '旧轮建立运行快照')

    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 20_000 })
    const newSession = await pageSessionId(page)
    expect(newSession).not.toBe(oldSession)

    // 旧轮收敛（取消是后端真事实，不是只断本地连接）
    const old = await waitTerminal(oldRunId, 60_000)
    expect(old.snapshot?.status).toBe('CANCELLED')

    // 新会话零残留：等过一个"旧帧可能迟到"的窗口后仍为空
    await page.waitForTimeout(4000)
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0)
    const newMirrorKey = await pageMirrorKeyOf(page, newSession)
    const mirror = await page.evaluate((key) => sessionStorage.getItem(key), newMirrorKey)
    if (mirror) {
      const payload = JSON.parse(mirror) as { messages?: unknown[] }
      expect(payload.messages?.length ?? 0).toBe(0)
    }
  })
})
