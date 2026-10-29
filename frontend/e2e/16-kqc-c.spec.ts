/**
 * 批③c 真机 E2E · C-08 / C-10 / C-13 / C-14（压缩谱系）
 *
 * 为什么单独一份：压缩触发判的是「下一次请求的预估」占「分母 − reserve-tokens」的比例；
 * 默认分母（模型行 `max_context` = 128000）下，桩的一次工具结果只有几百 token，
 * 要凑到 70% 需要几十轮真实工具往返。本棒把**分母**换成一行小 `max_context` 的模型行，
 * 于是「第 7 轮出现第 6 对工具结果」那一刻就会真的触发 L1 —— 走的是**同一段判定代码**，
 * 只是分母换成小值（不是改语义）。
 *
 * <h2>分母怎么变小（L-93 的结论）</h2>
 * 自 L-89 起上限的<b>唯一真源</b>是 `ai_model.max_context`（`ContextLimitResolver`）；
 * `--app.ai.context.limit-tokens` 只在"模型行没声明 max_context"时作启动期回退 ⇒
 * 用 env 压分母**结构性无效**（L-93 实测：默认与 limit-tokens=20000 两次配置下
 * `compaction.level` 恒 none、压缩台账 0 条，C-13 的"单调不减、每次至多一格"只是平凡成立）。
 * 本文件因此改走**模型配置写接口**（`PUT /api/ai/providers/{id}/models/{modelId}`）：
 * 每个用例自带窗口、跑完恢复，窗口生效以 `/health.contextLimitTokens` 为准
 * （`support/helpers-kqc.ts#withModelWindow`）。运行命令里仍留 `--app.ai.context.limit-tokens=20000`：
 * 它必须大于 `reserve-tokens`（13000，否则启动期自检拦下），同时与任何一个用例窗口都不同 ——
 * 于是"分母 = 模型行"这件事有断言盯着（`limitTokens === 窗口 && !== 20000`）。
 *
 * <h2>取值纪律（P3 实测；C13 起预留按窗口收口）</h2>
 * 旧口径下 `limit − reserve` 在"预留接近窗口"时会被钳成个位数的分母：任何轮次都判满、
 * 逐轮直冲 L3（退化分母）。C13 起 `ContextTrigger#effectiveReserve` 把有效预留收口到
 * **窗口的一半** ⇒ 小窗口下分母恒为 `窗口 / 2`。于是本文件的两档按"先定分母再定窗口"取：
 *   - {@link L1_WINDOW} = 12000（分母 6000，`nextEst/分母 ≈ 0.74` ⇒ 目标 L1）；
 *   - {@link LINEAGE_WINDOW} = 9000（分母 4500，`nextEst/分母 ≈ 0.98` ⇒ 目标 L3，
 *     但"每次至多升一格"⇒ 逐轮 L1 → L2 → L3）。
 * 窗口同时是**硬上限**（`nextEst >= limit` ⇒ 轮内判越窗）；C13 之后越窗不再直接失败，
 * 而是先收窄最新未保护的工具结果、再压缩固定历史（只调模型、不重跑工具）。
 *
 * <h2>usage 形状</h2>
 * 压缩判据读「上一轮 usage 真值 + 本轮输入估算」，桩的 `last`（prompt=1200）在小分母下
 * 够不到 70% ⇒ 本文件统一带 `usage-shape=repeat`（prompt=4321 / completion=77）。
 *
 * <h2>工具面</h2>
 * 本文件用 `get_config_def`（恒常披露的只读业务工具）：压缩是上下文机制，与工具语义无关；
 * 而 KB 三工具在 L-87 合并后因启动期循环依赖从注册表消失（B-KB-1，见施工报告），
 * 压缩判据不该绑在那条缺陷上。
 *
 * 文件级闸门：只有 `E2E_KQC_COMPACT=1` 才跑，常规套跑自动跳过。
 *
 * 运行（专用一棒；端口段 / Redis DB 与别域隔离；**跑前先清 `E2E_RUN_DIR`**）：
 *   桩的请求落档序号（`req-NNN`）每棒从 0 重来，而 harness 只清 H2 / Redis、不清 stub 目录；
 *   残留的上一棒文件会让 `maxStubIndex()` 读到旧的大序号 ⇒ `waitStubRequestsAfter` 永远等不到新请求。
 *   E2E_KQC_COMPACT=1 \
 *   E2E_EXTRA_BACKEND_ARGS="--app.ai.context.limit-tokens=20000" \   # 弃用回退键：留 = 启动自检约束 + 分母断言的对照值
 *   E2E_BACKEND_PORT=22930 E2E_DEGRADED_BACKEND_PORT=22931 E2E_STUB_PORT=22999 \
 *   E2E_APP_PORT=22901 E2E_DEGRADED_APP_PORT=22902 E2E_REDIS_DB=6 E2E_REDIS_DB_DEGRADED=6 \
 *   E2E_RUN_DIR=test-results/.e2e-run-kqc-c \
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
  waitStubRequestsAfter,
  withModelWindow,
  type Frame
} from './support/helpers-kqc'

test.skip(
  process.env.E2E_KQC_COMPACT !== '1',
  'C-08 / C-10 / C-13 / C-14 需要用「小 max_context 模型行」压分母（模型配置写接口），见文件头运行说明'
)

/** 分母两档（`ai_model.max_context`；有效预留 = 窗口的一半 ⇒ 分母 = 窗口 / 2，见文件头取值纪律）。 */
const L1_WINDOW = 12000
const LINEAGE_WINDOW = 9000
/**
 * 启动参数里的弃用回退键 `app.ai.context.limit-tokens`（必须 > reserve-tokens 13000）。
 * 与上面两档都不同 ⇒ "分母取的是模型行"这件事有断言盯着，而不是靠"两个值恰好相等"。
 */
const ENV_FALLBACK_LIMIT = 20000

/** 工具面：恒常披露的只读业务工具（KB 三工具见文件头 B-KB-1 注）。 */
const TOOL_CALL = 'tool=get_config_def&args={"code":"CURRENCY"}'
/** 压缩判据读上一轮 usage 真值 ⇒ 桩必须给一个够小分母越线的真值（repeat = 4321/77）。 */
const USAGE = 'usage-shape=repeat'
/** 档位顺序（`none < L1 < L2 < L3`）。 */
const LEVEL_ORDER = ['none', 'L1', 'L2', 'L3']

function levelOf(level: unknown): string {
  return level === undefined || level === null ? 'none' : String(level)
}

function levelIndex(level: unknown): number {
  const index = LEVEL_ORDER.indexOf(levelOf(level))
  if (index < 0) throw new Error(`未知压缩档位：${String(level)}`)
  return index
}

/** 本轮 SSE 里的 `context` 帧（压缩真的发生时才发；未压缩不发）。 */
function contextFrameOf(round: { frames: Frame[] }): Frame | undefined {
  return round.frames.find((frame) => frame.type === 'context')
}

/**
 * 台账按**时间正序**（`ContextStateStore#contextLedger` 的契约是"新→旧"，运维面直接给人看；
 * 谱系断言要的是"从最早到最晚"，故显式按 `atMs` 排序，不依赖存储顺序）。
 */
function chronological(ledger: Array<Record<string, unknown>>): Array<Record<string, unknown>> {
  return [...ledger].sort((left, right) => Number(left.atMs ?? 0) - Number(right.atMs ?? 0))
}

/**
 * 轮内上下文预算的收尾指令（`SpToolCallingManager.TURN_BUDGET_WRAP_UP`）。
 *
 * 本文件把分母压到 4500-6000（窗口 17500/19000 − reserve 13000），这个尺度下工具批之后的
 * **轮内**判定会先撞上 95% 收尾线：产品在工具结果之后追加一条"请收尾"的 user 消息（同一轮、
 * 同一段历史，不是新一轮对话）⇒ 续轮请求仍然存在，只是末条不再是 `role=tool`。
 */
const TURN_WRAP_UP_PREFIX = '本轮上下文已接近模型窗口上限'

/** 本轮的上游请求里"带刚执行完的工具结果"的那一条（L2 的摘要请求会插在它之前，故不能按序号取）。 */
async function toolRound(sessionId: string, caseId: string, extra = '') {
  const before = maxStubIndex()
  const message = `压缩用例 ${fingerprint(caseId)} ${extra} ${USAGE} ${TOOL_CALL}`
  const round = await chatRound(sessionId, message)
  await waitStubRequestsAfter(before, 1)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    for (let index = maxStubIndex(); index > before; index -= 1) {
      const record = stubRequest(index)
      if (record === null) continue
      // 收尾指令是工具结果**之后**追加的同一跳（判据仍看"末条工具结果"，不看指令）
      const tail = record.messages.slice()
      const last = tail[tail.length - 1]
      if (last?.role === 'user' && String(last.content ?? '').startsWith(TURN_WRAP_UP_PREFIX)) tail.pop()
      if (tail[tail.length - 1]?.role === 'tool') return { round, continuation: record }
    }
    await sleep(150)
  }
  throw new Error(`第 ${before} 条之后没有找到带工具结果的续轮请求`)
}

const PLACEHOLDER = /\[工具结果已清除：[A-Za-z_]+ · ≈\d+ tokens · toolCallId=[^\]]+\]/

test('@stub E2E-C08 L1 工具结果清除：占位符格式 + 幂等 + 不暴露台账键', async () => {
  await withModelWindow(L1_WINDOW, async () => {
    const sid = newSessionId('C08')
    let placeholdersAt7 = 0
    let placeholdersAt8 = 0
    let ledgerAt7 = 0
    for (let turn = 1; turn <= 8; turn += 1) {
      const { round, continuation } = await toolRound(sid, `C08-${turn}`)
      expect(round.done).toBeTruthy()
      expect(round.errors).toHaveLength(0)
      const allText = toolTextsOf(continuation).join('\n')
      // 运维面（台账键名）不得泄漏进模型可见文本
      expect(allText).not.toContain('ai:ledger:')
      const placeholders = allText.match(new RegExp(PLACEHOLDER.source, 'g')) ?? []
      if (turn === 7) {
        placeholdersAt7 = placeholders.length
        // 清除 = 压缩真的发生了：台账要真有 L1 一条（在此之前全是 0 条）
        const ledger7 = await contextLedger(sid)
        ledgerAt7 = ledger7.length
        expect(ledgerAt7, '第 7 轮起压缩台账必须非空').toBeGreaterThan(0)
        // 台账读回是"新→旧"，最新一条就是本轮这次压缩
        expect(String(ledger7[0].level)).toBe('L1')
      }
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
    // 分母 = 模型行（19000），既不是弃用键 limit-tokens=20000、也不是种子行的 128000
    expect(snapshot.limitTokens).toBe(L1_WINDOW)
    expect(snapshot.limitTokens).not.toBe(ENV_FALLBACK_LIMIT)
    expect(snapshot.compaction?.level).toBe('L1')
    // L1 只清工具结果、不折叠轮 ⇒ turns / lostTokens 恒 0（口径钉死，不是"没压"）
    expect(Number(snapshot.compaction?.lostTokens ?? 0)).toBe(0)
    expect(Number(snapshot.compaction?.compactedTurns ?? 0)).toBe(0)
  })
})

test('@stub E2E-C14 压缩归档可回溯：压缩前原文进 ai:ctx:archive:*，TTL 随会话（≤ 24h）', async () => {
  await withModelWindow(L1_WINDOW, async () => {
    const sid = newSessionId('C14')
    for (let turn = 1; turn <= 7; turn += 1) {
      const { round } = await toolRound(sid, `C14-${turn}`)
      expect(round.done).toBeTruthy()
    }
    const snapshot = await contextSnapshot(sid)
    expect(snapshot.limitTokens).toBe(L1_WINDOW)
    expect(snapshot.limitTokens).not.toBe(ENV_FALLBACK_LIMIT)
    expect(snapshot.compaction?.level).toBe('L1')
    // 归档不可能是"零压缩"的副产物：台账里要有那一条 L1（读回新→旧，最新一条即最近一次压缩）
    const ledger = await contextLedger(sid)
    expect(ledger.length).toBeGreaterThan(0)
    expect(String(ledger[0].level)).toBe('L1')

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
})

test('@stub E2E-C10 摘要失败不静默：上游 500 ⇒ 历史逐字不变 + 熔断 +1 + reason 如实', async () => {
  await withModelWindow(LINEAGE_WINDOW, async () => {
    const sid = newSessionId('C10')
    // 6 轮平推 + 第 7 轮（第 6 对工具结果 ⇒ L1）。`summary-500` 必须在**目标轮的前一轮**打：
    // 压缩发生在轮起点（模型请求还没发出），同一轮带的标记到不了上游、摘要请求已经发出去了。
    for (let turn = 1; turn <= 6; turn += 1) {
      const { round } = await toolRound(sid, `C10-${turn}`)
      expect(round.done).toBeTruthy()
    }
    const { round: l1Round } = await toolRound(sid, 'C10-l1', 'summary-500')
    expect(l1Round.done).toBeTruthy()
    expect(levelOf(contextFrameOf(l1Round)?.level)).toBe('L1')

    const beforeState = await contextSnapshot(sid)
    expect(beforeState.limitTokens).toBe(LINEAGE_WINDOW)
    expect(beforeState.limitTokens).not.toBe(ENV_FALLBACK_LIMIT)
    // 第 8 轮：目标 L3 ⇒ "每次至多升一格" ⇒ 进 L2 摘要，摘要请求被桩 500 掉（arm 窗口内一律 500，
    // 覆盖 Spring AI 的 5xx 重试；见 kqc-upstream 的 summaryFault）
    expect(beforeState.compaction?.level).toBe('L1')
    const fuseBefore = Number(beforeState.compaction?.fuseFails ?? 0)

    const { round, continuation } = await toolRound(sid, 'C10-arm')
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
    // 失败不推进档位（本轮目标 L3、最多进 L2，L2 失败 ⇒ 停在 L1）
    const afterState = await contextSnapshot(sid)
    expect(levelOf(afterState.compaction?.level)).toBe('L1')
    // 历史逐字不变：上一轮的工具结果仍在上下文里（失败路径不落地"部分压缩"）
    const texts = toolTextsOf(continuation)
    expect(texts.length).toBeGreaterThan(0)
    // 失败路径不改写历史：本轮工具结果的原文仍在（没被清成占位符，也没被摘要折叠）
    expect(texts.join('\n')).toContain('CURRENCY')
    // 失败也不"部分生效"：本轮新增的那条工具结果必须还是原文（不是清除占位符）
    expect(PLACEHOLDER.test(texts[texts.length - 1])).toBe(false)
  })
})

/**
 * E2E-C13（L-93 修点）：压缩谱系**真验**。
 *
 * 旧判据（`@real` 那条）只断言"档位单调不减、每次至多一格"——压缩从未触发时这个命题
 * **平凡成立**（全 none 也满足），于是用例恒绿而无信息量。本条把判据换到「真的压过」的
 * 三张证据面上，并要求三面同源：
 *   ① 压缩台账（`GET /context/{sid}?ledger=1`）：条数 > 0、首条 L1、逐条只升一格；
 *   ② 快照（`compaction.compactedTurns / lostTokens`）：L1 恒 0，L2/L3 > 0，且与台账末条逐值相等；
 *   ③ 占用：每条台账事件 `after < before`（真省了），末条的 after 低于首条的 before；
 * 另加"触发与展示同分母"（L-89 的收口）：每轮 `done.context.percent == used/limitTokens`。
 *
 * 分母由**小 max_context 的模型行**提供（写接口改、跑完恢复）；对照组用默认行跑同样轮次，
 * 证明"不压"确实来自窗口而不是别的偶然。
 */
test('@stub E2E-C13 压缩谱系真验：小 max_context 模型行 ⇒ 台账 / 档位 / 占用三面同源', async () => {
  test.setTimeout(300_000)

  // ── 对照组：默认窗口（种子行 max_context=128000）下 3 轮工具往返不压缩 ─────────────
  const controlSid = newSessionId('C13-ctl')
  for (let turn = 1; turn <= 3; turn += 1) {
    const { round } = await toolRound(controlSid, `C13-ctl-${turn}`)
    expect(round.done).toBeTruthy()
  }
  const controlSnapshot = await contextSnapshot(controlSid)
  const bigWindow = controlSnapshot.limitTokens ?? 0
  // 大分母来自**模型行**（128000），不是启动参数里的 limit-tokens=20000
  expect(bigWindow, '对照组必须跑在大分母上（种子行 128000）').toBeGreaterThan(100_000)
  expect(bigWindow).not.toBe(ENV_FALLBACK_LIMIT)
  expect(levelOf(controlSnapshot.compaction?.level), '大分母下不该压缩').toBe('none')
  expect(await contextLedger(controlSid), '大分母下台账必须为空').toHaveLength(0)

  // ── 处置组：小窗口（> reserve 13000）⇒ 逐轮 L1 → L2 → L3 ───────────────────────────
  await withModelWindow(LINEAGE_WINDOW, async () => {
    const sid = newSessionId('C13')
    const rounds: Array<{
      turn: number
      level: string
      percent: number | null
      frame: Frame | undefined
      summarySeen: boolean
    }> = []

    for (let turn = 1; turn <= 10; turn += 1) {
      const { round, continuation } = await toolRound(sid, `C13-${turn}`)
      expect(round.done, `第 ${turn} 轮未收敛`).toBeTruthy()
      expect(round.errors).toHaveLength(0)
      const snapshot = await contextSnapshot(sid)
      // 分母 = 模型行（写接口写进去的那个值）；`--app.ai.context.limit-tokens=20000` 不参与
      expect(snapshot.limitTokens, `第 ${turn} 轮分母必须是模型行`).toBe(LINEAGE_WINDOW)
      expect(snapshot.limitTokens).not.toBe(ENV_FALLBACK_LIMIT)
      const telemetry = (round.done?.context ?? {}) as { usedTokens?: number; percent?: number }
      const used = Number(telemetry.usedTokens ?? snapshot.usedTokens)
      const percent = telemetry.percent === undefined ? snapshot.percent : Number(telemetry.percent)
      if (percent !== null && Number.isFinite(percent)) {
        // 触发与展示同分母（L-89）：帧里的百分比就是 used / limitTokens
        expect(Math.abs(percent - (used * 100) / LINEAGE_WINDOW), `第 ${turn} 轮展示百分比与分母不同源`).toBeLessThanOrEqual(1)
      }
      rounds.push({
        turn,
        level: levelOf(snapshot.compaction?.level),
        percent,
        frame: contextFrameOf(round),
        summarySeen: continuation.messages.some((message) =>
          String(message.content ?? '').includes('[上下文压缩摘要]'))
      })
    }

    const levels = rounds.map((row) => row.level)
    // eslint-disable-next-line no-console
    console.log(
      `[C-13] 窗口=${LINEAGE_WINDOW} 档位=${levels.join(' → ')} ` +
        `占用=${rounds.map((row) => (row.percent === null ? '-' : `${row.percent.toFixed(1)}%`)).join(' → ')}`
    )

    // ① 每轮至多升一格、不回头（真谱系：目标 L3，但一次只准上一格）
    for (let index = 1; index < levels.length; index += 1) {
      const step = levelIndex(levels[index]) - levelIndex(levels[index - 1])
      expect(step, `第 ${rounds[index].turn} 轮档位跳变：${levels.join(' → ')}`).toBeLessThanOrEqual(1)
      expect(step, `第 ${rounds[index].turn} 轮档位回退：${levels.join(' → ')}`).toBeGreaterThanOrEqual(0)
    }
    // 真的压过（不是"全 none 也单调"那种平凡成立）
    expect(levelIndex(levels[levels.length - 1]), `谱系未爬到 L2：${levels.join(' → ')}`).toBeGreaterThanOrEqual(
      levelIndex('L2')
    )

    // ② 台账：条数 > 0、首条 L1、逐条只升一格、每条都真的省了 token
    const ledger = await contextLedger(sid)
    const changed = chronological(ledger).filter((event) => levelOf(event.level) !== 'none')
    expect(changed.length, '压缩台账必须非空').toBeGreaterThanOrEqual(2)
    expect(levelOf(changed[0].level), '谱系必须从 L1 起步').toBe('L1')
    for (let index = 1; index < changed.length; index += 1) {
      const step = levelIndex(changed[index].level) - levelIndex(changed[index - 1].level)
      const trail = changed.map((event) => levelOf(event.level)).join(' → ')
      expect(step, `台账档位跳变：${trail}`).toBeLessThanOrEqual(1)
      expect(step, `台账档位回退：${trail}`).toBeGreaterThanOrEqual(0)
    }
    for (const event of changed) {
      const before = Number(event.before ?? 0)
      const after = Number(event.after ?? 0)
      expect(after, `压缩未降低占用：level=${levelOf(event.level)} before=${before} after=${after}`).toBeLessThan(before)
    }
    // L1 口径：只清结果、不折叠轮 ⇒ turns / lostTokens 恒 0
    expect(Number(changed[0].turns ?? -1)).toBe(0)
    expect(Number(changed[0].lostTokens ?? -1)).toBe(0)
    expect(String(changed[0].reason ?? '')).toContain('tool_result_clearing')
    // L2 口径：折叠整轮 ⇒ turns > 0 且 lostTokens > 0（"丢了什么"必须如实记账）
    const summarized = changed.filter((event) => levelOf(event.level) === 'L2')
    expect(summarized.length, '必须至少有一次 L2 摘要（否则只验证了 L1 清除）').toBeGreaterThan(0)
    for (const event of summarized) {
      expect(Number(event.turns ?? 0)).toBeGreaterThan(0)
      expect(Number(event.lostTokens ?? 0)).toBeGreaterThan(0)
    }
    // 占用真的降下来了：末条的 after 低于首条的 before
    expect(Number(changed[changed.length - 1].after ?? 0)).toBeLessThan(Number(changed[0].before ?? 0))

    // ③ 快照 ≡ 台账末条（端点与记账同一份事实，不允许两套口径）
    const lastEvent = changed[changed.length - 1]
    const finalSnapshot = await contextSnapshot(sid)
    expect(levelOf(finalSnapshot.compaction?.level)).toBe(levelOf(lastEvent.level))
    expect(Number(finalSnapshot.compaction?.compactedTurns ?? 0)).toBe(Number(lastEvent.turns ?? 0))
    expect(Number(finalSnapshot.compaction?.lostTokens ?? 0)).toBe(Number(lastEvent.lostTokens ?? 0))

    // ④ SSE `context` 帧 ≡ 台账（同一组 (level, before, after, turns, lostTokens)；"即时可见"面）
    const framed = rounds.filter((row) => row.frame !== undefined)
    expect(framed.length, '压缩轮必须带 context 帧').toBeGreaterThanOrEqual(2)
    for (const row of framed) {
      const frame = row.frame as Frame
      expect(levelOf(frame.level)).toBe(row.level)
      expect(Number(frame.before ?? 0)).toBeGreaterThan(Number(frame.after ?? 0))
      expect(Number(frame.savedTokens ?? 0)).toBe(Number(frame.before ?? 0) - Number(frame.after ?? 0))
    }
    // 摘要落地后模型看到的是摘要占位（"真的改写了下一次请求"的模型可见证据）
    expect(rounds.some((row) => row.summarySeen), 'L2 之后上游请求里必须出现 [上下文压缩摘要]').toBe(true)

    // eslint-disable-next-line no-console
    console.log(
      `[C-13] 台账=${changed.length} 条（${changed.map((event) => `${levelOf(event.level)}:${event.before}→${event.after}`).join(' / ')}）` +
        ` 末条 turns=${String(lastEvent.turns)} lostTokens=${String(lastEvent.lostTokens)}`
    )
  })
})
