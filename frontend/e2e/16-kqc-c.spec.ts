/**
 * 批③c 真机 E2E · C-08 / C-10 / C-14（压缩谱系需要**小分母**才可触达）
 *
 * 为什么单独一份：L1 的触发条件是「存在超过 keep-tool-pairs(5) 对的旧工具结果」**且**占比 ≥ l1-percent；
 * 默认分母 128000 下，桩一次工具结果 ~1–2k token，要凑到 70% 需要几十轮真实工具往返。
 * 本棒用专用后端参数把分母压到 1.5k（并同步压 reserve / chunk，满足启动期校验），
 * 于是「第 7 轮出现第 6 对工具结果」这一刻就会真的触发 L1 —— 走的是**同一段判定代码**，
 * 只是分母换成小值（不是改语义）。
 *
 * 文件级闸门：只有 `E2E_KQC_COMPACT=1` 才跑，常规套跑自动跳过。
 *
 * 运行（专用一棒）：
 *   E2E_KQC_COMPACT=1 \
 *   E2E_EXTRA_BACKEND_ARGS="--app.ai.context.limit-tokens=1500 --app.ai.context.reserve-tokens=500 --app.ai.context.chunk-tokens=200" \
 *   E2E_BACKEND_PORT=19730 ... E2E_REDIS_DB=14 E2E_RUN_DIR=test-results/.e2e-run-kqc-compact \
 *   E2E_STUB_SCRIPT="<仓库根>/frontend/e2e/support/kqc-upstream.mjs" \
 *   npx playwright test e2e/16-kqc-c.spec.ts
 */
import { expect, test } from '@playwright/test'
import {
  chatRound,
  contextLedger,
  contextSnapshot,
  fingerprint,
  maxStubIndex,
  newSessionId,
  redisExec,
  redisTtl,
  sleep,
  stubRequest,
  toolTextsOf,
  waitStubRequestsAfter
} from './support/helpers-kqc'

test.skip(
  process.env.E2E_KQC_COMPACT !== '1',
  'C-08 / C-10 / C-14 需要专用小分母参数（limit-tokens=1500 / reserve=500 / chunk=200），见文件头运行说明'
)

/** 本轮的上游请求里"带刚执行完的工具结果"的那一条（L2 的摘要请求会插在它之前，故不能按序号取）。 */
async function toolRound(sessionId: string, caseId: string, extra = '') {
  const before = maxStubIndex()
  const message = `压缩用例 ${fingerprint(caseId)} ${extra} tool=kb_lookup_term&args={"term":"标准费率"}`
  const round = await chatRound(sessionId, message)
  await waitStubRequestsAfter(before, 1)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    for (let index = maxStubIndex(); index > before; index -= 1) {
      const record = stubRequest(index)
      if (record === null) continue
      const last = record.messages[record.messages.length - 1]
      if (last?.role === 'tool') return { round, continuation: record }
    }
    await sleep(150)
  }
  throw new Error(`第 ${before} 条之后没有找到带工具结果的续轮请求`)
}

const PLACEHOLDER = /\[工具结果已清除：[A-Za-z_]+ · ≈\d+ tokens · toolCallId=[^\]]+\]/

test('@stub E2E-C08 L1 工具结果清除：占位符格式 + 幂等 + 不暴露台账键', async () => {
  const sid = newSessionId('C08')
  let placeholdersAt7 = 0
  let placeholdersAt8 = 0
  for (let turn = 1; turn <= 8; turn += 1) {
    const { round, continuation } = await toolRound(sid, `C08-${turn}`)
    expect(round.done).toBeTruthy()
    expect(round.errors).toHaveLength(0)
    const allText = toolTextsOf(continuation).join('\n')
    // 运维面（台账键名）不得泄漏进模型可见文本
    expect(allText).not.toContain('ai:ledger:')
    const placeholders = allText.match(new RegExp(PLACEHOLDER.source, 'g')) ?? []
    if (turn === 7) placeholdersAt7 = placeholders.length
    if (turn === 8) {
      placeholdersAt8 = placeholders.length
      // 幂等：占位符不嵌套（已清除的条目不会被再清一次，也不会被重复计费）
      for (const placeholder of placeholders) {
        expect(placeholder).not.toContain('[工具结果已清除：[工具结果已清除')
      }
    }
  }
  // 第 7 轮起真的出现了清除占位符，且后续轮次单调不减（旧结果只被清一次）
  expect(placeholdersAt7).toBeGreaterThan(0)
  expect(placeholdersAt8).toBeGreaterThanOrEqual(placeholdersAt7)

  const snapshot = await contextSnapshot(sid)
  expect(snapshot.compaction?.level).toBe('L1')
  expect(Number(snapshot.compaction?.lostTokens ?? 0)).toBe(0)
})

test('@stub E2E-C14 压缩归档可回溯：压缩前原文进 ai:ctx:archive:*，TTL 随会话（≤ 24h）', async () => {
  const sid = newSessionId('C14')
  for (let turn = 1; turn <= 7; turn += 1) {
    const { round } = await toolRound(sid, `C14-${turn}`)
    expect(round.done).toBeTruthy()
  }
  const snapshot = await contextSnapshot(sid)
  expect(snapshot.compaction?.level).toBe('L1')

  // 归档键 = `ai:ctx:archive:<sid>:<压缩前 turnNo>`；逐个 turn 号探活（不依赖 KEYS 的顺序）
  const archived: string[] = []
  for (let turnNo = 0; turnNo <= 12; turnNo += 1) {
    const key = `ai:ctx:archive:${sid}:${turnNo}`
    const exists = await redisExec(['EXISTS', key])
    if (exists.value === '1') archived.push(key)
  }
  expect(archived.length).toBeGreaterThan(0)
  for (const key of archived) {
    const ttl = await redisTtl(key)
    // TTL 必须存在（-1 = 永不过期是错的：归档必须随会话共亡）
    expect(ttl).toBeGreaterThan(0)
    expect(ttl).toBeLessThanOrEqual(24 * 3600)
  }
})

test('@stub E2E-C10 摘要失败不静默：上游 500 ⇒ 历史逐字不变 + 熔断 +1 + reason 如实', async () => {
  const sid = newSessionId('C10')
  // 8 轮热场：先落到 L1（第 7 轮），再多攒一轮可摘要的旧轮（keep-recent-turns=6）
  for (let turn = 1; turn <= 8; turn += 1) {
    const { round } = await toolRound(sid, `C10-${turn}`)
    expect(round.done).toBeTruthy()
  }
  const beforeState = await contextSnapshot(sid)
  expect(beforeState.compaction?.level).toBe('L1')
  const fuseBefore = Number(beforeState.compaction?.fuseFails ?? 0)

  // 第 9 轮进 L2 摘要，摘要请求被桩 500 掉（一次性 arm）
  const { round, continuation } = await toolRound(sid, 'C10-arm', 'summary-500')
  expect(round.done).toBeTruthy()
  expect(round.errors).toHaveLength(0)

  // 熔断计数可能比本轮收尾晚一拍：显式轮询（不 sleep 断言）
  let fuseAfter = fuseBefore
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    fuseAfter = Number((await contextSnapshot(sid)).compaction?.fuseFails ?? 0)
    if (fuseAfter > fuseBefore) break
    await sleep(200)
  }
  // 同一轮可能因重试再失败一次：断言单调递增（至少 +1），不在重试次数上做脆断言
  expect(fuseAfter).toBeGreaterThanOrEqual(fuseBefore + 1)
  // 失败要如实留痕：台账里有 level=none + reason 指向摘要失败的事件（"没压成"也是事实）
  const ledger = await contextLedger(sid)
  const failures = ledger.filter((event) => String(event.reason ?? '').includes('summary'))
  expect(failures.length).toBeGreaterThan(0)
  expect(String(failures[0].level)).toBe('none')
  // 历史逐字不变：上一轮的工具结果仍在上下文里（失败路径不落地"部分压缩"）
  const texts = toolTextsOf(continuation)
  expect(texts.length).toBeGreaterThan(0)
  // 失败路径不改写历史：命中答案里的 base_rate 仍在原文里（这条结果没被清成占位符）
  expect(texts.join('\n')).toContain('base_rate')
})
