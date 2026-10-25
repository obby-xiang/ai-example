> 来源=<仓外证据>/cluster3-34fd8f4-analysis.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
I've completed the trace. Note: I'm a read-only exploration subagent (no file-editing tools), so I could not create `<仓外证据>/cluster3-34fd8f4-analysis.md` myself — the exact report content is below, ready to save verbatim to that path.

---

# 34fd8f4 · 三实例集群 run 40182796512 失败归因

- 仓库/提交：`<主仓>` @ `34fd8f405d0a7c57cb2b7bae387d04249fce5ba1`（candidate，未改动）
- 运行：GitHub Actions `40182796512`，job `121137223927`（8m46s），窗口 `09:58:50Z → 10:06:32Z`
- 证据：`<仓外证据>/cluster3-34fd8f4-failed.log`；产物已下载到 `<仓外证据>/cluster3-34fd8f4-evidence/cluster3-evidence-40182796512/`（未打印任何密钥）
- **结论：确定性产品缺陷。不是编排错误、不是更强断言的误杀、不是环境极限。** 唯一失败用例 `suspend-cross-instance` 的根因是「种子写路径不发布配置版本」→ B/C 的配置快照永久停在空 provider 列表 → 续跑被误判 `PROVIDER_GONE`；再叠加「重挂自愈派发被拒后不收敛」使客户端 120s 无帧超时。

---

## 1 计数与失败清单

| 项 | 值 |
|---|---|
| 用例 | 16 |
| PASS | **15** |
| FAIL | **1** |
| exit | 1 |
| 自证（`cluster3-probe --self-check`） | PASS 27 / FAIL 0 |
| 自证（`cluster3-run.sh --self-check`） | PASS 53 / FAIL 0 |

失败用例（`results.json` / `summary.md` / `results.md` 三处一致）：

| # | 用例 | 结果 | 耗时 | 报文 |
|---:|---|---|---:|---|
| 7 | suspend-cross-instance | **FAIL** | 120375ms | `SSE 请求超时（120000ms）：http://127.0.0.1:23182/api/ai/events/f59e429d-782d-45cb-a76b-5d74f5140e09?lastSeq=4` |

其余 15 例全绿：cold-start-3 / proxy-no-sticky / same-tcp-across-abc / reattach-any-instance / history-pagination / seq-cross-instance / idempotency-cross-instance / session-purge-cross-instance / config-hot-reload / shared-file-download / suspend-kill-a / streaming-kill-a / job-kill-a / readiness-removal / redis-outage-recovery。

**失败断言位置**：`scripts/ci/cluster3-probe.mjs:2806-2825`（步骤④「决策后 B 差量重挂必须收到 `done`」），超时抛在 `scripts/ci/cluster3-probe.mjs:1130-1142`（SSE 请求级 `timeout`）。

---

## 2 失败现场（证据时间线，均为实测）

| 时间 (UTC) | 事实 | 证据 |
|---|---|---|
| 09:58:50.6 | 三实例并发冷启动（a=2863 / b=2865 / c=2866），共享 H2-TCP 23190 / Redis 23179 | `run.log`, `instances/*.log` |
| 09:59:17.6 | B `Started`（Tomcat 23182 起于 17.555） | `instances/instance-b.log` |
| **09:59:18.470** | **仅 A** 落下 AI 配置种子（provider `deepseek` + model `ci-stub`） | `instances/instance-a.log:87` |
| 09:59:34.773→.880 | A 收流、`approval_park`、`turn_parked`，帧 seq1..4（start/suspended/tool_start/confirm_request） | `cases/suspend-cross-instance/A-parked.jsonl` |
| 09:59:34.916 | B 决策前重挂 `lastSeq=4` → **0 帧 + 服务端正常收流**（park 屏障成立） | `B-predecision-reattach.jsonl`（1 字节）、`route-log.jsonl:185` |
| 09:59:34.945 | C 决策前重挂 `lastSeq=0` → 回放 seq1..4 **后收流** | `C-predecision-reattach.jsonl`、`route-log.jsonl:186` |
| 09:59:34.995 | `POST /api/ai/confirm` → C(23183) 200，`accepted:true, executed:false, resumeTriggered:true, resumeOutcome:SCHEDULED, storedBy=C` | `confirm.json`、`route-log.jsonl:188` |
| 09:59:34.998 | C 死卡兜底 → `续跑拒绝：快照的 provider/模型已不可用 providerId=1 model=ci-stub` → `outcome=PROVIDER_GONE` | `instances/instance-c.log:132-135` |
| **09:59:35.028** | **B 决策后重挂 `lastSeq=4` 打开**，只收到 seq5 `confirm_decision`，随后无任何帧 | `B-reattach.jsonl`、`route-log.jsonl:189` |
| 09:59:35.025→.033 | B 由**重挂自愈分支**派发续跑（线程 `io-23182-exec-3`）→ 同样 `PROVIDER_GONE` | `instances/instance-b.log:93-95` |
| 10:01:35.028 | 客户端 120s 超时 → 用例 FAIL | `cases/suspend-cross-instance/failure.txt` |
| 10:02:03.4 | 第 10 例取证：三实例配置 `version=1`；**A 的 `providers=[{id:1, synthetic:false}]`，B/C 的 `providers=[{id:null, synthetic:true}]`**；`ai:cfg:ver` 仍为空串 | `cases/config-hot-reload/config-reload.json`（`before`/`healthBefore`/`redisVersionBefore`） |
| 10:02:04.9 | 首次**真实**配置写入：`version 1→2` + `INCR ai:cfg:ver`，B/C 在 1310ms 内看到新值 | 同上 + `instances/instance-a.log:305` |
| 10:02:22.5 | B 续跑另一轮 `296806df` **ACCEPTED**，`AI provider 运行态已装配 … configVersion=3` | `instances/instance-b.log:111` |
| 10:02:36.6 | A(pid4341) 启动续跑受理 `f59e429d`：`Starting job 4 type=EXPORT taskId=2`（即 `call-stub-24` 真的被执行），`turn_end status=done runTotalMs=182032` | `instances/instance-a.log:409-411` |

最后一行是**反证**：同一个 `providerId=1 / ci-stub` 在 3 分钟后被 A 正常续跑并执行成功 → `PROVIDER_GONE` 是**误判**，provider 从未不可用。

---

## 3 根因 A（主因，决定性）：种子写路径不发布配置版本，跨实例快照永不收敛

`AiConfigSeeder.seed()` 直接写 repository 并**只 reload 本实例**：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:98-101` — `transactionTemplate.executeWithoutResult(writeSeed)` + `this.configService.reload()`（仅本进程）
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java:130` / `:143` — `providerRepository.saveAndFlush` / `modelRepository.saveAndFlush`
- 对照正确写路径 `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:686-698`（`bumpVersion()`：`ai_setting.config.version + 1`）+ `:711-723`（提交后 `INCR ai:cfg:ver` + reload + `evictAll`）

读侧只按版本比对重载，于是其它实例没有第二把钥匙：

- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:218-229`（`checkReload`：Redis 版本存在则比 Redis，否则比 DB `config.version`）
- `AiConfigService.java:155-172`（`current()` 只在版本变化时 `load()`）
- `AiConfigService.java:244-255`（`readDbVersion` 回退到快照自身版本）
- 初始版本来自迁移常量 `backend/src/main/resources/db/migration/V4__ai_provider_config.sql:56`（`config.version=1`）

链条闭合的实测证据：种子后 DB 版本仍是 1、Redis 键不存在（`redisVersionBefore=''`），因此 B/C 的 `version=1` 永远等于"当前版本" ⇒ **一次也不会重载**；直到 10:02:03 的真实写入才把版本推到 2/3，B/C 才看到 `id=1` 的行。B/C 此前能对外"看起来正常"，是因为：

- `AiConfigService.java:440-445`（`effectiveProviders`：库为空时退回 env 合成行 `ai_provider id=null`）+ `:916+`（`availability()` 走 `resolveDefault`）→ `/api/ai/health` 仍回 `AI_AVAILABLE`；
- 冷启动用例只断言 `available===true`（`scripts/ci/cluster3-probe.mjs:2270`），**被 env 兜底掩盖**。

到了 park 续跑，判据换成**精确绑定**，空快照立刻现形：

- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:740-747` — `resolveChatTarget(snapshot.providerId=1, "ci-stub")` 为空 ⇒ `providerGone(...)`
- `ResumeService.java:1155-1166` — 只构造 409 语义 body，不改快照、不写终帧

**为什么可以排除"key 解不开/模型行缺失/行被停用"**：`writeSeed` 的 provider+model 在**同一事务**提交（H2 read-committed，不可能只看到一半）；`envSeedProvider()` 固定 `enabled=true`、`keyRef=ENV`；env key 三实例同源（B 的 `resolveKey` 在 10:02:22 就对同一行成功过）。唯一自洽状态就是"快照里根本没有这行"，也就是实测的 `synthetic:true`。

**这是冷启动期竞态触发、但结果确定**：并发冷启动时，非播种实例（B/C）的第一次 `current()`（readiness 探针 / HAProxy `http_check` / `/api/ai/health`）必然可能早于播种提交；一旦早于，且此后没有任何真实配置写入，就永久停在空快照。本次 B 与 C **同时**中招，与"两实例并发冷启动"完全吻合。

---

## 4 根因 B（暴露面，独立产品缺陷）：重挂自愈派发被拒后连接不收敛

`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:766-792`：

- `:767-769` 仍有 PENDING ⇒ `out.completeSubscriptions()` 收流（正确，决策前重挂已实测通过）；
- `:770-791` 条目已全决但快照仍 SUSPENDED ⇒ 走自愈 `triggerResumeForParked(runId)`，**只 `log.debug` 结局（:784-785），随即 `return ResponseEntity.ok(emitter)`（:793）**；
- 异步侧 `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:1908-1917` 对 `PROVIDER_GONE` **只有一行 INFO**，不改快照、不写终帧；
- 服务端 emitter 用 `NO_TRANSPORT_DEADLINE`（`AiController.java:757`），因此**连接可以无限挂着**。

结果就是本轮观测到的形态：`SUSPENDED` + 条目已决 + 无驱动方 = `ProviderGoneException` 文档明确要避免的"半死轮"（`backend/src/main/java/com/example/configmgr/ai/run/ProviderGoneException.java:11-15`：必须落成 `FAILED` 终态 + 机器可读码，而不是停在非终态），只是这次是从**预检分支**进去的，没人负责收尾。客户端要么等产品自己收敛（本例等到 A 重启，182s），要么像探针一样在 120s 处超时。

---

## 5 分类判定

| 假设 | 判定 | 依据 |
|---|---|---|
| 确定性产品缺陷 | **是（A 为主，B 为暴露面）** | 种子写无版本发布（`AiConfigSeeder.java:98-101` vs `AiConfigWriter.java:711-723`）→ 空快照（`config-reload.json` 的 `healthBefore.b/c.providers[0].synthetic=true`）→ `PROVIDER_GONE`（`instance-b.log:93-95`） |
| 测试编排错误 | **否** | 用例严格按产品自述时序（`cluster3-probe.mjs:2743-2757, 2806-2841`）；决策前重挂/收流/seq 连续等前置断言全过；A 侧同 run 后续成功续跑证明期望值正确 |
| 更强断言误杀 | **否** | 断言"决策后重挂必须收到 `done` + 差量 seq 连续"是产品口径（`AiController.java:761-765` 注释即写明该标准时序）；实测缺失是真缺失，不是断言过严 |
| 真实环境极限 | **否** | 09:59 无 Redis/GC/端口异常（Redis 故障发生在 10:05:34 之后，属第 16 例且该例 PASS）；provider 全程可用；120s 远超本拓扑健康续跑耗时（同例 park 收流 <1s，`suspend-kill-a` 17.7s） |

---

## 6 早前修复，哪些守住了

| 早前修复 | 结论 | 证据 |
|---|---|---|
| 并发冷启动（种子串号/单飞） | **断言层守住，语义层没守住** | `cold-start-summary.json`：三实例 id 互异、定义数 15/15/15 一致；但 B/C 的 provider 快照是合成行（`synthetic:true`），同一份"冷启动成功"里已经埋着本轮的失败 |
| 共享文件字节一致 | 守住 | `shared-file-download` PASS 277ms，sha256 一致 |
| 挂起接管（早杀） | 守住 | `suspend-kill-a` PASS：`executedBy=B`、17722ms 收到 `done`、台账 outcome=ok |
| 流式 kill 接管补帧 | 守住 | `streaming-kill-a` PASS 70661ms |
| 作业 kill 收敛 + 重启重试 | 守住 | `job-kill-a` PASS 55645ms |
| readiness 摘除/接管/重新纳入 | 守住 | `readiness-removal` PASS 63443ms |
| 无粘滞代理 | 守住 | `proxy-no-sticky`（30 请求 a=…/b=…/c=…）+ `same-tcp-across-abc`（同客户端端口命中 3 节点）PASS |
| park 收流屏障 | 守住 | 决策前 B/C 重挂均"回放后收流、无终帧、状态仍 SUSPENDED" |
| 启动自动续跑收敛 | 守住但太晚 | `instance-a.log:409-411`：182s 后受理并真的执行了 `call-stub-24`（job 4/task 2），`status=done` |

**重要保留意见（顺序依赖）**：第 12–14 例（挂起/流式/作业接管）之所以全绿，是因为第 10 例 `config-hot-reload`（10:02:03）在它们**之前** bump 了版本、把 B/C 的快照救活了（`instance-b.log:111` `configVersion=3`）。用例顺序一旦调换，这些"接管类"用例会同样因空快照而红。

---

## 7 最小修复 / 测试动作

| # | 目标 | 最小动作 | 必做测试（红证） |
|---|---|---|---|
| A1 | 种子写也要发布版本（**必需**） | 在 `AiConfigSeeder.writeSeed` 的同一事务里 bump `ai_setting.config.version`，提交后 `INCR ai:cfg:ver`（等价于复用 `AiConfigWriter.java:686-698` + `:711-723` 的语义；参考 `AiConfigSeeder.java:98-101`）。1s reload 间隔（`AiProperties.java:116`、`application.yml:135`）即可让 B/C 收敛 | `AiConfigSeederTest` 新增：另一个"空快照 + 空 `ai:cfg:ver`"的 `AiConfigService` 实例，在 seed 后一个 reload 周期内必须 `resolveChatTarget(1,"ci-stub")` 非空；删掉版本发布 ⇒ 空（红） |
| B1 | 重挂自愈不得无界挂起（**必需**） | `AiController.java:782-791` 使用 `triggerResumeForParked` 的结局：未派发 / 终态拒绝（`PROVIDER_GONE`、`LEASE_UNAVAILABLE`、`STATE_UNKNOWN`）⇒ 写终帧并把快照落 `FAILED`（按 `ProviderGoneException.java:11-15` 口径），或至少 `completeSubscriptions()` + 状态可查；`ConfirmGate.java:1908-1917` 同步落终态 | 新增用例/断言："provider 真被删的 SUSPENDED 轮，决策后重挂必须在 N 秒内收尾（`error`/`done` 或正常关闭）"；现状必红（120s 挂死） |
| B2 | （建议，防御纵深）硬否定前强制刷新 | `ResumeService.java:740-747` 判 `PROVIDER_GONE` 前先 `configService.reload()` 一次（一次 DB 读，把"硬否定"从缓存里救出来）；判定后仍为空才拒 | `ResumeProviderGoneTest` 增"库里有行、缓存为空 ⇒ 不得判 GONE"；去掉强制 reload ⇒ 红 |
| T1 | 让失败前移到第 1 例（**建议**） | `cluster3-probe.mjs:2265-2288` 的 `verifyColdState` 增加：每实例 `/api/ai/health` 的 `body.providers` 必须含**非 synthetic** 行，且三实例 id 一致（现在只断言 `available===true`，`AiConfigService.java:440-445` 的 env 兜底会骗过它） | 现状即红（`healthBefore.b/c.providers[0].synthetic=true`），失败点从"第 7 例 120s 超时"提前到"第 1 例秒级" |

> 不需要改探针超时、不需要重排用例、不需要动拓扑。A1 单独即可让本轮用例转绿；B1 是同一份证据已经暴露出来的第二个缺陷（否则真删 provider 时会以"客户端永久挂起"呈现）；B2/T1 是回归护栏。

---

## 8 仍未证明的部分（明确列出）

1. **B/C 空快照的"加载瞬间"没有日志**（`AiConfigService` 的 load 只有 debug），只有**状态**是硬证据（`healthBefore.b/c` = 合成行）、**机制**是硬证据（版本 1 从未变、`ai:cfg:ver` 为空）。具体是哪一次 readiness/HAProxy 探测触发的首次加载，无法从本次产物判定。
2. **"真删 provider"路径未实测**：缺陷 B 的机制由代码 + 本次"只收 seq5、120s 无帧"的空帧证据支撑，但没有专门构造"provider 确实被删除"的运行。
3. **版本 bump 成功而 `INCR ai:cfg:ver` 失败**（Redis 抖动）时 B/C 的收敛时延未验证（`AiConfigWriter.java:711-723` 有 DB 兜底路径，但本轮 Redis 健康）。
4. **C 侧步骤④/⑤断言未执行**（用例在 B 处 fail-fast）：C 与 B 同为合成行快照，且 C 在 09:59:34.998 给出同样 `PROVIDER_GONE`，因此 C 必然同型失败，但严格说 C 的帧册断言未被实际跑到。
5. 第 12–14 例的"绿灯"依赖执行顺序（依赖第 10 例的版本 bump），这一点是推论自时间线，未做顺序调换的对照运行。

---

### 一句话给主 agent

唯一失败是 `suspend-cross-instance`（15 PASS / 1 FAIL）：**`AiConfigSeeder` 播种后只 reload 自己、不发布 `config.version` / `ai:cfg:ver`，导致 B/C 的配置快照永远停在 env 合成行**，于是跨实例续跑被判 `PROVIDER_GONE`；再叠加 **`AiController.events()` 自愈派发被拒后既不写终帧也不收流**，客户端 120s 无帧超时。修 `AiConfigSeeder`（发布版本）即可转绿，`AiController`/`ConfirmGate` 的收敛是同一证据暴露出的第二个必修项；冷启动断言建议加"provider 非 synthetic 且三实例一致"，否则同类缺陷下次仍会被 env 兜底掩盖。
