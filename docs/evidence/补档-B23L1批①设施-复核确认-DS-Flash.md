# 补档 · B23L1 批① 设施 复核确认（DS-Flash，2026-10-21）

## 0 自证行（wire 实测）

- 会话 `session_081d49e8-17a5-451b-befe-c0422c23a69b`；`grep -n 'profile.bind' …/agents/agent-1305/wire.jsonl` 命中**第 5 行**（首行为 metadata）：
  `{"type":"profile.bind","agentId":"agent-1305","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...`
- 与施工方（agent-1301）互盲：只读其落库的施工报告 §6 与工作树，未读其 wire。

## 1 只读纪律与新锚（SHA-256）

零 commit/push/stash、零服务启停（harness **仍未运行**）；取数 = 15 个未跟踪新件逐个 `git add -N` → `git diff` → `git reset -q`；还原后 `git status` = 9 `M` + 18 `??`（新增 1 个 `M` 与 1 个 `??` 均为收口轮产物），index 空。唯一新增文件 = 本确认件。

| 取数面 | 行数 | SHA-256（收口轮） | 对照：首轮锚 |
|---|---|---|---|
| 全量（生产 6 新 + 4 改、测试 5 新 + 3 替身 + 2 改、脚本 1 新、文档 3 改） | 3923 | `704ece14a8908ba35b58d834f012fe2e2f41e767f82826f328fb808c9f294647` | `2b3ecc9b…`（3270 行） |
| 生产-only（`backend/src/main`） | 1685 | `ed4a4eb78b99a36220146f3e4ccea3a2510ec7be39f8daad8cb86d7e109a7416` | `bc01e447…`（1527 行） |

净增 653 行（生产 +158）：与 C/I/M 收口面（显式 ID + 自修复 + 顺序调整 + boolean 订阅 + 替身重建）量级吻合。

## 2 结论：**READY-WITH-NITS**（0C / 0I / 1M / 3N；新 M 不阻断，随复核收口或并入 L-2）

C-1 / I-1 / I-2 / I-3 **四条全部关闭且修法正确**，M-1/M-3/M-4/M-5/M-6/N-2~N-7 按申报落实，M-2/M-7 的挂账（L-4/L-5）措辞诚实、无虚标。收口轮**新引入 1 个窗口边界的读取方向问题**（§3.2-M′，1 行可修，>6000 帧长轮全量回放才会命中，二次刷新即自愈），以及 3 处文档/取证精度项；无 C/I 遗留。

## 3 三点核查

### 3.1 C/I 修法正确性

| # | 修法 | 判定 | 核对要点（实测行号） |
|---|---|---|---|
| **C-1** | 健康判据改扁平体 | **PASS** | `scripts/b23l1-dual-instance.ps1:151-171`：`$body.available` + `$body.redis.available`；**503 也照解析**（判据与状态码解耦）、空体/非 JSON 有守卫。我按同款最小复演独立复跑（读真实 `/health` 附件 + 两份降级体）：`ok: old=False new=True` / `ai-down: new=False` / `redis-down: new=False`，与施工方自报一致。`scripts/README.md:135` 已同步为扁平体口径。**附带**：`Assert-PortFree`（`:81-96`）只在 `-NoStart=false` 分支调用（`:359-361`），`-NoStart`（端口本就应被占）不会误杀。 |
| **I-1** | 先挂订阅再读回放 | **PASS（窗口真消）** | `replayAndAttach:512-536`：`attachSubscription(520)` 前移到 `stageReplay` 之前，两段同持 `emitLock`。①`onWake` 也取该锁 ⇒ 不可能插进"读后、订阅前"；②订阅后迟到的唤醒在锁外排队，届时 `from` 已被回放推进 ⇒ 只补窗口内那一帧、且幂等；③FIFO 未变（回放入队与实时入队同锁，回放先入）。用例 `wakeSubscriptionIsEstablishedBeforeTheReplayRead:156` 两段都钉住（回读**当刻**订阅已建立 + 窗口内落档帧经唤醒补一次）。 |
| **I-2** | 显式 entry ID `<seq>-0` + `XRANGE (afterSeq-0` + 自动 ID 兜底 | **PASS（主诉）** | 读侧 `streamFramesAbove:278-306`：下限 `exclusive(afterSeq + "-0")`、`COUNT=窗口`、升序；落档 `xadd:222-243` 走原始 `XADD key MAXLEN ~ n <id> seq <n> frame <json>`；显式 ID 被拒（ID ≤ 表尾）⇒ 自动 ID 兜底 + WARN 一次（`:196-208`）。**混合态不漏不重**的推导：①自动 ID 取 `9e12+n`（替身同口径），恒大于任何 `<seq>-0` ⇒ 低号帧仍在 `(afterSeq-0` 区间内，按 `seq` 字段过滤后可读（用例 `lateLowSeqFrameStaysReadableAfterPhysicalReorder:134` 用真 store + 真替身证到 4/5/6 全可读）；②不会重：`mergeBySeq` 以 `seq` 为键、`override=Stream`，同号两条记录折叠为一条；③不同号不互相遮蔽。**残留协议语义**（报告 §6.6 已如实登记）：已越过该号的读者不再补发低号帧（补发会破坏 seq 单调），仅对 `lastSeq < 该号` 的读者可见 —— 与 `onWake:1018-1021` 的注记一致，非缺陷。 |
| **I-3** | 订阅失败回退 + 重试 | **PASS（无重复订阅泄漏）** | `FrameWakeBus.subscribe` 返 boolean；`FrameWakeListener.register:65-104` 锁内只碰目标集、**锁外**做 subscribe（顺带关掉 M-6 的全局锁 I/O），失败时撤登记并返 false；`SseChatEmitter:474-503` 见 false 即 `wakeRegistered.set(false)`（`:492`）。**无泄漏**的关键在订阅后的"空集复查"（`FrameWakeListener:96-102`）：若 subscribe 期间最后一个目标恰好注销，则订阅后补一次 `unsubscribe` 并返 false —— 我按 attach/detach 四种交错推演，终态恒为"有订阅者 ⇔ 有订阅"，无孤儿监听器。`RedisFrameWakeBus.subscribe` 对重复订阅用 `listeners.put` + 移除旧监听器，重试不会叠加。用例 `subscribeFailureIsRolledBackAndRetriedOnTheNextAttach:187` 证到"失败→零目标→再次 attach 成功"。**残留（N）**：重试是**事件驱动**（下一次 attach/close），无定时/退避；单订阅者连接首次失败后，跨实例唤醒要到下一次连接变动才恢复（已在该方法 Javadoc 声明"下一次 attach 重试"，口径诚实）。 |

### 3.2 收口轮**新引入**的问题（本轮唯一实质发现）

**M′（窗口边界：读取方向反了）** —— `streamFramesAbove:278-306` 用 `XRANGE … COUNT 窗口`（**升序、从下限往前**）。旧实现是 `XREVRANGE … COUNT`（取**尾部**窗口）。`MAXLEN ~ 6000` 是**近似**裁剪，Redis 会保留到 ~窗口 + 宏节点条数（默认 ≤ +99），因此流内实际可持有 **6000 ~ 6099** 条。此时：

- `afterSeq` 落后于窗口头（最典型：`?lastSeq` 缺省的**全量回放**，`afterSeq=0` ⇒ `Range.unbounded()`）⇒ 返回的是**最旧的 6000 条**，被丢掉的恰好是**最新的 ≤99 条**（可能含 `done`）。
- 后果：>6000 帧的长轮，一次全量回放会缺尾段；终态轮没有后续唤醒，客户端看到"连接关闭而无终帧"（前端提示结果可能不完整）。**数据未丢**：再刷新一次（带上次的 `lastSeq`，此时 `尾−afterSeq < 窗口`）即可读全 —— 属可自愈，且只在"单轮帧数 > 6000"时命中。
- 设计稿 §6 登记的"窗口风险"指的是**窗口外**补不回；本条是**窗口内却读不到**，不在该风险覆盖范围，故按新发现登记。
- 修法（1 行）：把下限夹到窗口头，例如 `long from = Math.max(afterSeq, tailSeq - streamWindow)`（`tailSeq` 用已有的 `streamTailSeq`），或当 `tail − afterSeq ≥ 窗口` 时改走"取尾再翻转"。
- **用例覆盖不到**：`FakeFrameRedis` 按命令实参 `MAXLEN` **精确**裁剪（`handleXadd:179-233`），永不出现"保留 > 窗口"，`~` 的近似性无法建模；建议替身补一个 `retainExtra` 开关，或至少把该面写进 L-2 措辞。

### 3.3 断言同步与取证可信度

- **§6 与实际改动一致**：C-1/I-1/I-2/I-3 与 M-1/M-3/M-4/M-5/M-6、N-2/N-3/N-4/N-6/N-7 逐条对上代码；`AiControllerRunsCurrentTest` 现 12 条（+1），五类新增用例合计 6+4+1 = **11 条**，与"614 → 625"吻合；我复跑全量为 **625/625 · 98 类**（日志 100 条含 2 个 `@Nested`），与 §6.5 一致。
- **行号精度（N）**：`RedisFrameStore` 系整体偏低 3–5 行 —— `nextSeq` 报 115 实 **118**、`repairRollback` 报 147 实 **152**、`xadd` 报 217 实 **222**、`legacyFramesAbove` 报 261 实 **266**、`streamFramesAbove` 报 273 实 **278**（`FrameWakeListener`/`SseChatEmitter`/测试类的行号准确）。
- **红测 5 轮（抽查）**：第 1/2/3/5 轮的"变异 → 红点"与当前代码可推演一致（撤回 attach 顺序 ⇒ `subscribedDuringRead` 为 false；退回 `XREVRANGE` 估条数 ⇒ `readFrom(3)` 只剩 5/6；去掉回退 ⇒ 第二次 attach 不再订阅；忽略 FrameStore 分支 ⇒ 77 变 42）。**第 4 轮自报"3 红"含 `streamIsTrimmedToWindowWhileSeqKeepsAdvancing` 不可由"去掉 `repairRollback`"导出** —— 该用例只依赖"纯 `INCR` 从 1 起 + MAXLEN 裁剪"，在无锚定/无自修复的实现下 1..12 与 `next=13` 仍然绿（N）。
- **挂账 L-4~L-8（N）**：L-4（兼容期后删 LIST 读路径）、L-5（P95 随 harness 运行批取证，并明确"本报告不给任何性能结论"）、L-6（`WAKE_BATCH_LIMIT` 无自续读）、L-7（harness 就绪信号/信号清理/事件注销/HttpClient）、L-8（空洞观测 + `lastSeq` 低估）—— 落点与措辞均正确可执行。补一句：**L-8 宜同时容纳 M′（读方向）**，否则窗口边界两面分处两处。
- **§6.6 措辞（N）**：正文写"物理序恒等于 seq 序"，在自动 ID 兜底态下不成立（同节下文与 `docs/03-技术方案文档-v2.1.md:707` 的②已列例外）；建议这句加"（兜底态除外）"。

## 4 门禁实测（本人复跑）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 定向（卡指定） | `mvn.cmd -o test -Dtest='RedisFrameStoreTest,SseChatEmitterCrossInstanceWakeTest,FrameWakeListenerTest'` | **30/30 绿**（16 / 9 / 5），`BUILD SUCCESS` |
| 后端全量（超额） | `mvn.cmd -o test` | **625/625 绿**（0F/0E/0S），**98 类**，`BUILD SUCCESS` |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（exit 0，命名纪律扫 1327 路径） |
| 前端 | 未跑（按卡免） | `git status` 无 `frontend/` 条目 ⇒ 零改动成立；首轮 512/512 仍适用 |

## 5 遗留清单（本确认轮）

| 级别 | 条目 |
|---|---|
| **M′** | `XRANGE … COUNT 窗口` 为升序读取：流内保留 > 窗口（`MAXLEN ~` 近似裁剪）时，落后读者拿到**最旧**窗口、丢掉**最新**若干条（>6000 帧长轮全量回放可缺尾段）；1 行夹下限可修，替身需 `retainExtra` 才能测 |
| **N-1′** | §6 中 `RedisFrameStore` 系行号偏低 3–5 行 |
| **N-2′** | 第 4 轮红测"3 红"中第三条不可由所述变异导出，建议按实测改口径 |
| **N-3′** | §6.6"物理序恒等于 seq 序"缺"兜底态除外"限定；M′ 建议并入 L-8/L-2 措辞 |
| 观察 | I-3 的重试为事件驱动（下一次 attach/close），无退避定时器；单订阅者首败后需一次连接变动才恢复（已文档化，不计缺陷） |

**准予入库**：0C/0I，M′ 与 3N 不改变批① 设施的交付边界（生产主线语义未回归，单实例路径零影响），建议随下一轮（或 harness 运行批）一并收口。
