# Redis 候选协议验证 harness（backend test 源集）

本目录只放轻量启动脚本、配置模板和说明。真正的 Java 代码在：

```
backend/src/test/java/com/example/configmgr/perf/redis/
```

它直接复用 backend 的 Java 21、Spring Boot 3.5.14、Spring Data Redis/Lettuce、Jackson 与
JUnit/Maven 依赖，**不进入生产包**，也不新增依赖或修改 `backend/pom.xml`。

> 范围：文档 `21-redis-change-preflight-plan.md` 的 **A（删除候选协议性能/完整性）** 与
> **B（run/frame/decision 原子协议）**。不实现 C（history/recovery/KB 读模型原型）。
>
> 不启动项目服务、不调用任何 HTTP API、不驱动真实模型；只直连显式配置的隔离 Redis。

---

## 1. 为什么放在 backend test 源集

- 与生产代码使用同一套 Java/Lettuce/Jackson 版本和 `application*.yml` 的连接口径；
- surefire 默认不会把 harness 当生产代码打包；
- 默认单点测试不连接 Redis：只有显式 `REDIS_PROTOCOL_EXECUTE=true` 才会打开连接。

`RedisProtocolHarnessTest` 是 Maven 入口；默认跑全仓单测时它会被 `Assumptions` 跳过。

---

## 2. 构建与启动

要求：JDK 21、Maven 3.9+、能访问目标 Redis。**不要**在共享实例上开启写操作。

### 2.1 推荐：只用本包编译（不跑全仓测试）

Windows Git Bash / Linux：

```bash
cd <repo>/scripts/perf/redis-protocol
export MVN=/path/to/apache-maven/bin/mvn      # 若 mvn 不在 PATH
export PERF_ISOLATED_ENV=true
export PERF_NAMESPACE=rp-sa-$(date +%Y%m%d%H%M)
export PERF_OUTPUT_DIR=./out-sa
./run.sh doctor
```

Windows CMD：

```bat
cd <repo>\scripts\perf\redis-protocol
set MVN=<Maven的mvn.cmd绝对路径>
set PERF_ISOLATED_ENV=true
set PERF_NAMESPACE=rp-sa-20261104
set PERF_OUTPUT_DIR=.\out-sa
run.cmd doctor
```

脚本只做四件事：`mvn -DskipTests compile` → `dependency:build-classpath` →
`javac` 本包 → `java HarnessMain`。它**不会**编译或执行其他测试文件，也不会跑全仓测试。
脚本默认带 `-o`（离线，符合本仓 `mvn -o` 习惯）；新设备首次拉依赖失败时，把脚本里的
`-o` 去掉（或在本地先完成一次在线依赖解析），之后仍可离线。

### 2.2 直接 Maven（基线测试源码可编译时）

```bash
cd <repo>/backend
REDIS_PROTOCOL_EXECUTE=true REDIS_PROTOCOL_COMMAND="doctor" \
  mvn -Dtest=RedisProtocolHarnessTest test
```

把 `REDIS_PROTOCOL_COMMAND` 换成 `"generate --execute"`、`"measure --execute"` 等。
如果仓库基线存在**与本任务无关**的测试源码编译错误，`mvn -Dtest=... test` 会在编译阶段失败；
此时用 `run.sh` / `run.cmd`，它只编译 `com/example/configmgr/perf/redis` 包。

### 2.3 单元测试（不连接 Redis）

纯逻辑单测覆盖键形/slot、预算、统计/门禁、状态判定：

```bash
cd <repo>/backend
# 若全仓测试源码可编译：
mvn -Dtest='RedisSlotsTest,KeyShapesTest,DeadlineTest,LatencyStatsGateTest,StreamPagerTest,RunStateMachineTest' test
```

本机基线测试源集有无关编译错误时，用 `run.sh` 的编译步骤后直接跑 JUnit Launcher（见交付说明），
或逐个类运行。测试本身不需要 Redis。

---

## 3. 环境变量

全部从进程环境读取，**不自动读 `.env`**；模板见 [.env.example](.env.example)。
密码只进连接，不打印、不落盘。

### 3.1 standalone

```bash
export REDIS_MODE=standalone
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_DB=0
export PERF_ISOLATED_ENV=true
export PERF_NAMESPACE=rp-sa-1
export PERF_OUTPUT_DIR=./out-sa
export PERF_PROFILE=smoke
```

非 0 库还需 `PERF_CONFIRM_NON_DEFAULT_DB=true`；非 loopback 主机还需 `PERF_CONFIRM_REMOTE=true`。

### 3.2 cluster

```bash
export REDIS_MODE=cluster
export REDIS_CLUSTER_NODES=10.0.0.11:6379,10.0.0.12:6379,10.0.0.13:6379
export REDIS_DB=0
export PERF_CONFIRM_REMOTE=true
export PERF_ISOLATED_ENV=true
export PERF_NAMESPACE=rp-cl-1
export PERF_OUTPUT_DIR=./out-cl
```

Cluster 只支持 db 0。standalone 与 cluster 使用**同一套命令、Lua、场景和键形**；唯一差别是连接配置。

### 3.3 关键变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `REDIS_MODE` | `standalone` | `standalone` / `cluster` |
| `REDIS_HOST` `REDIS_PORT` `REDIS_DB` | `127.0.0.1` `6379` `0` | standalone |
| `REDIS_CLUSTER_NODES` | 空 | cluster 种子 |
| `REDIS_USERNAME` `REDIS_PASSWORD` | 空 | ACL；不打印、不落盘 |
| `REDIS_TLS` `REDIS_TLS_INSECURE` | `false` | TLS |
| `REDIS_COMMAND_TIMEOUT_MS` `REDIS_CONNECT_TIMEOUT_MS` | `5000` | Lettuce 超时 |
| `PERF_ISOLATED_ENV` | `false` | **任何连接（含 doctor/verify）必须 true**；写操作额外受 `--execute` 约束 |
| `PERF_CONFIRM_REMOTE` | `false` | 远程目标必须 true |
| `PERF_CONFIRM_NON_DEFAULT_DB` | `false` | standalone 非 0 库 |
| `PERF_ALLOW_LARGE_SCALE` | `false` | 2000 archive / 10k bg / 50 runs |
| `PERF_ALLOW_BACKGROUND_100K` | `false` | 10 万背景键 |
| `PERF_EXISTING_BG_SCALE` | `0` | 已有大键空间规模（不重新生成） |
| `PERF_NAMESPACE` | 自动 | 字母/数字/`_.-`，2..41 |
| `PERF_OUTPUT_DIR` | `target/redis-protocol-out` | 输出目录 |
| `PERF_PROFILE` | `smoke` | `smoke` / `full` / `stress` |
| `PERF_SCENARIOS` | 空 | 逗号分隔，显式点名优先 |
| `PERF_REPEAT` `PERF_WARMUP` | `2` `1` | 每场景独立 copy |
| `PERF_BATCH_SIZE` | `100` | 有界 pipeline 批次 |
| `PERF_STREAM_PAGE_SIZE` `PERF_STREAM_MAX_ENTRIES` | `256` `10000` | Stream 物理 ID 分页 |
| `PERF_SCENARIO_TIMEOUT_SECONDS` `PERF_TOTAL_TIMEOUT_SECONDS` | `60` `600` | 单调预算；`0` 关闭 |
| `PERF_KEY_TTL_SECONDS` | `43200` | 所有生成键兜底 TTL |
| `PERF_LEASE_TTL_MS` | `3000` | purge/run 租约 TTL |
| `PERF_FINALIZE` | `true` | 无终帧时是否允许补写终帧 |
| `PERF_GATE_EMPTY_P95_MS` | `250` | empty/minimal 门槛 |
| `PERF_GATE_RUNS50_P95_MS` | `2000` | 50 terminal runs 门槛 |
| `PERF_GATE_ARCHIVE2000_P95_MS` | `3000` | 2000 archives 门槛 |
| `PERF_GATE_MAX_SCAN_PAGES` | `0` | 在线删除 SCAN 页数上限 |
| `PERF_GATE_MAX_BG_COMMAND_GROWTH` | `0` | 背景放大后命令数允许增长 |

---

## 4. 分阶段命令

```bash
# 0) 只打印矩阵，不连接 Redis
./run.sh scenarios

# 1) 只读体检：PING、版本/mode、DBSIZE、本地/服务端 slot
./run.sh doctor

# 2) 生成数据集（默认 dry-run；--execute 才写）
./run.sh generate --execute

# 3) 测量（删除性；必须 --execute）
./run.sh measure --execute

# 4) 只读核对最终键面/延迟/tombstone/deferred
./run.sh verify

# 5) 清理（默认 dry-run；--execute 才删）
./run.sh cleanup
./run.sh cleanup --execute

# 6) 一键：doctor → 逐场景 generate/measure/verify/cleanup
./run.sh all --execute
./run.sh all --execute --keep          # 保留数据
```

也可只跑一个场景：

```bash
PERF_SCENARIOS=b_frame_fence ./run.sh generate --execute
PERF_SCENARIOS=b_frame_fence ./run.sh measure --execute
PERF_SCENARIOS=b_frame_fence ./run.sh cleanup --execute
```

> `bg_10k_runs_1` / `bg_100k_runs_1` 必须**各自独立** generate → measure → cleanup；
> 同一命名空间只允许一个背景规模存在。`all` 会按场景逐个清理，避免相互比较。
> 已有大键空间 Cluster 用 `PERF_EXISTING_BG_SCALE=<规模> --scenario bg_existing_runs_1`，
> 不重新生成百万键。

---

## 5. 场景与断言

### A. 删除候选协议

- `empty` / `minimal`：空会话、最小会话；P95 ≤ 250ms。
- `runs_1_terminal` / `runs_10_terminal` / `runs_50_terminal`：终态 run 的 13 键 + xterminal；
  50 run 默认 P95 ≤ 2s。
- `mixed_active`：终态 + 已有终帧 RUNNING + 无终帧可收尾 + 挂起。
- `staging_5` / `staging_500`：精确 staging 索引 + 同槽批量删除。
- `archives_10` / `archives_2000`：legacy archive 精确索引 + 单键 `TYPE+DEL` 有界 pipeline；
  2000 archive 默认 P95 ≤ 3s。`TYPE_DEL` 的 ARGV[1] 是期望类型（archive 必须是 `string`）；
  类型不符返回 `-1`、不删除、计入 errors，整个 purge 不得报 complete。
  已删除或本来就不存在（stale）的成员从索引摘除，already-absent 是幂等清理、不算错误；
  类型不符的键和索引成员保留；只有索引确实为空（且无类型不符）才删除索引。
- `frames_terminal_tail/head/middle/missing/gap_large`：终帧首/中/尾、无终帧、大跳号。
- `duplicate_delete`：同会话重复删除幂等。
- `dual_executor`：两个执行体并发 acquire，只有一个 lease winner。
- `lease_takeover`：租约过期后新 owner epoch +1；旧 owner 的阶段写被 `STALE_EPOCH` 拒绝。
- `active_unfinalizable`：场景级 `allowFinalize=false`（不是改全局默认），无终帧 ⇒ `deferred`，
  **删除前中止**，所有已生成键保留；只有 `defer` 写成功且计划键完整才算合法的 deferred。
- `bg_0_runs_1` / `bg_10k_runs_1` / `bg_100k_runs_1` / `bg_existing_runs_1`：
  相同目标规模放入不同背景键空间；比较命令数与 SCAN 页数，不比较裸延迟下结论。
  `bg_existing_runs_1` 的 `declaredBackground` 只是声明规模，`background=0`、
  `externalBackground=true`；生成、verify、cleanup、`Manifest.allKeys` 都不得展开/遍历/删除
  既有背景键。

候选删除协议要点：

- 枚举只用精确索引：`ai:session:runs:*`、`chat:mem:staging:idx:*`、`ai:ctx:archive:idx:*`；
  **在线删除 SCAN=0**，没有 `KEYS/FLUSHDB/FLUSHALL`。
- run 删除：14 个同槽键（生产 13 键 + `ai:xterminal`）一条 Lua，pipeline 分批。
- staging：索引与 staging 键同 `{sessionId}` tag，同槽 Lua 批量删。
- archive：键本身无 tag，**不假设同槽**；每个键一条单键 `TYPE+DEL` Lua，pipeline 分批。
- purge lease/pending/guard 三键同 `{sessionId}` tag；acquire/stage/complete 都是同槽 Lua。
- `durable pending` 无 TTL，包含 `purgeId/owner/epoch/stage/verifyAtMs/deferred`；
  完成/延后都保留，`ai:purge:retry:due` 记录到期项。
- 守卫写回验证：完成前调用 `PURGE_GUARDED_WRITE`，守卫/pending 存在时必须返回 0。
- 终态收敛：已有终帧（`ai:xterminal` 或 Stream 内 `done/error`）时条件写快照；
  `done` → DONE、`error` → FAILED、`cancelled:true` → CANCELLED；未知/解析失败不得伪收敛，
  按 deferred/unknown 处理。无终帧且场景允许收尾时补写终帧；否则 deferred。
- **complete 判定**：只有没有 errors/deferred、`PURGE_COMPLETE` 写成功、`ai:purge:retry:due`
  写入成功才把 dataset 标成 `purged`；有 errors 时绝不写 `COMPLETE`、绝不 zadd due，而是用
  带 owner/epoch 校验的 `PURGE_FAIL` 写 `FAILED_RETRYABLE`，保留 pending/guard 与必要的
  archive 索引/键作为重试发现面。`defer` 写失败、zadd 失败、类型不符、守卫未拦住写回
  都必须是 failed/unknown，不能落成 purged。
- **frontend result**：`FRONTEND_RESULT` 必须有 claim；缺 claim 返回 `MISSING_CLAIM`，
  不得写 pending/decided。claim 存在但 owner 不匹配返回 `CONFLICT_CLAIMED`。
- **并发**：双执行体线程 `join` 有 10s 上限并检查线程异常；超时/异常立即失败，
  `all` 跳过 cleanup 并保留产物，绝不让仍在写的线程进入清理。
- **预算**：`measure` 和 `all` 共用一个 `Budget`；`PERF_TOTAL_TIMEOUT_SECONDS` 是整次运行的
  总上限，不是每个场景各自重新起算。takeover 的 finalize/delete/tombstone/complete 也逐阶段
  `deadline.check`。

### B. run/frame/decision 原子协议

- `b_frame_fence`：fence + expected epoch 纳入同槽 `FRAME_APPEND` Lua；旧 epoch 在取号、
  归档、扇出前被拒；terminal 帧与 `ai:xterminal` 同脚本写。
- `b_decision_atomic`：`decided` + 完整 `pending` 载荷 + TTL 在同一 Lua；
  另有“旧实现先写 decided、崩溃在 pending 之前”的半状态对照。
- `b_claim_cancel`：两个执行体并发 claim 只有一个 WON；cancel 对已认领/已决策条目返回冲突；
  前端回执只能由认领者写，已认领副作用不被写回未执行。
- `b_stream_gap`：Stream 按物理 entry ID（`XRANGE ... (lastId)`）分页，预算按真实条目数；
  大跳号下终帧/tool_start 不漏；预算耗尽标 `truncated`，未扫到的 tool_start 是 unknown。
- `b_slot_consistency`：所有多键同槽组本地 CRC16 一致；cluster 下再用 `CLUSTER KEYSLOT` 复核。

---

## 6. 指标、门禁与退出码

每次运行输出：

| 文件 | 内容 |
| --- | --- |
| `doctor.json` | 只读体检 |
| `manifest.json` | 命名空间、每场景/copy 的精确键面、背景规模、状态 |
| `results.json` | 配置脱敏摘要、逐阶段指标、汇总、协议断言、gate |
| `requests.csv` | 每阶段：时长、命令数、pipeline 批次、SCAN 页数、slot 检查、字节（估算） |
| `summary.csv` | 每场景：P50/P95/P99/max、命令数、批次数、SCAN 页数、slot 检查、字节（估算） |
| `protocols.json` | B 场景断言逐条 pass/fail |
| `verify.json` / `verify.csv` | 最终键面/状态核对 |
| `cleanup.json` | 清理清单与执行结果 |
| `verify-<scenario>.json` / `cleanup-<scenario>.json` | `all` 每场景证据（后续场景不会覆盖） |

门禁默认值（先冻结，不能看到结果后放宽）：

- `empty` / `minimal`：P95 ≤ 250ms，且无 30s 级长尾；
- `runs_50_terminal`：P95 ≤ 2s；
- `archives_2000`：P95 ≤ 3s；
- 在线删除 SCAN 页数 = 0；
- 背景规模放大后，相同目标的命令数/SCAN 页数不得增长（默认增长上限 0）。

`P50/P95/P99` 使用 nearest-rank；只统计非 warmup 轮次。字节数是 RESP 帧 + **Lua 脚本正文 +
KEYS + ARGV** 长度的估算，输出统一 `bytesEstimated=true`；不是网卡计数。

退出码：`0` 通过；`1` 运行/门禁/协议失败或预算耗尽留下 partial；`2` 配置/安全门禁/unknown。

预算耗尽时保留已完成阶段与结果，场景标 `partial`，**不伪报通过**；命令超时/连接失败记 `failed`
或 `unknown`，`measure`/`all` 返回非 0。`all` 还会累计每个场景 `verify` 与 `cleanup` 的返回码：
verify fail/unknown 后仍尽量执行 cleanup，但任一项失败最终都非 0；并发线程未收口时跳过 cleanup
并保留证据。

---

## 7. verify / cleanup 判定

- `state=purged`：manifest 里所有计划键必须不存在；只允许保留：
  - `chat:mem:*:gen` / `:ver`（防复活 tombstone）
  - `ai:purge:guard:*` / `ai:purge:pending:*`（durable 台账/守卫）
  - `perf:bg:*` 与背景 marker（背景不属于本会话）
- `state=deferred`：`ai:purge:pending` 存在且 TTL=-1，run 快照仍存在，计划键无缺失
  （证明没有半删）。
- `state=protocol`：`protocols.json` 全 pass，且关键最终键存在（xterminal/decided/claim）。
- `state=generated`：未测量，`unknown`，不算通过。
- `state=failed` / `residue`：unknown，不算通过；`FAILED_RETRYABLE` 的 pending 与 archive
  索引/类型不符键保留，供重试；`state=cleaned` 才跳过键面检查。

`all` 的最终退出码：所有选中场景都进入 `GateEvaluator`；任一场景 `failed`/`deferred`
（除非场景声明为 expected deferred，如 `active_unfinalizable`）/`partial`/`skipped` 都会让最终结果
非 0。每场景的 `verify` 和 `cleanup` 返回码也会累计进 `all`；verify 失败仍继续尽量 cleanup，
但 cleanup 的结果同样决定退出码；并发线程未收口时跳过 cleanup 并保留证据。

`cleanup` 默认 dry-run；`--execute` 只按 manifest 精确键面删除，并额外删除本会话
guard/pending/lease、`ai:purge:retry:due` 成员、背景键公式与 marker。删除后用 `EXISTS`
复核（manifest 计划键与背景键公式全量复核），残留则 `complete=false`。

**禁止**：`FLUSHDB`、`FLUSHALL`、`KEYS`、`DEBUG`、`CONFIG`、无界 `SCAN`。
不会删除其他命名空间、其他会话、业务作业/任务/配置、KB 缓存、`ai:runs` 全局索引本身。

---

## 8. 另一台设备的 AI 执行提示词（可直接复制）

```text
你在另一台隔离设备上执行 Redis 候选协议验证。先读：
  docs/research/审查-运行时Redis与接口性能-2026-11-04/21-redis-change-preflight-plan.md
  scripts/perf/redis-protocol/README.md

硬性边界：
- 不启动项目服务，不调用任何 HTTP API，不驱动真实模型。
- 不改 backend/src/main、frontend、backend/pom.xml、现有 scripts/perf/redis。
- 只用专用隔离 Redis；任何连接（含 doctor/verify）必须 PERF_ISOLATED_ENV=true；远程/集群必须
  PERF_CONFIRM_REMOTE=true；cluster 只允许 db 0。
- 不跑全仓测试；不跑旧协议全量 Cluster SCAN；不执行 FLUSHDB/FLUSHALL/KEYS。
- 失败或预算耗尽时保留输出目录，不删证据，不伪报通过。

执行顺序（每个拓扑分开输出目录）：
1) ./run.sh scenarios
2) ./run.sh doctor
3) 先 smoke：PERF_PROFILE=smoke ./run.sh all --execute
4) smoke 通过后再 full：PERF_PROFILE=full PERF_ALLOW_LARGE_SCALE=true ./run.sh all --execute
5) 如需 10 万背景：单独 PERF_SCENARIOS=bg_100k_runs_1 逐场景 generate/measure/cleanup，
   不要和 bg_10k 同时生成；大键空间 Cluster 用 bg_existing_runs_1 + PERF_EXISTING_BG_SCALE。
6) standalone 与 cluster 各跑一次，输出目录分开；比较 summary.csv、protocols.json、verify.csv。
7) 最后对每个输出目录执行 cleanup --execute，确认 cleanup.json complete=true。

报告：命令、退出码、summary.csv 的 P50/P95/P99、命令数、SCAN 页数、slot 检查、
protocols.json 的失败项、verify 的 fail/unknown、cleanup 残留。任何 unknown 都要说明原因。
```

---

## 9. 已知边界 / 未实测

- **本机基线测试源集**有与本任务无关的编译错误（例如 `AiRateLimiter`、`ClientIpResolver`
  在 test 源集被引用但 main 源集不存在）。因此本机用 `run.sh` 的“只编译本包”路径验证；
  `mvn -Dtest=...+ test` 需要基线测试源集可编译。
- **未连接本机 Redis**（按任务约束）。本交付只验证了编译、纯逻辑单测与 CLI dry-run；
  真实 standalone/cluster 的 SQL/Lua/拓扑行为需在另一台设备按 README 执行。
- 字节数是估算，不是网卡计数；pipeline 的“1 个批次”不等于 1 条命令。
- `bg_100k` 与 `archives_2000` 的生成/清理耗时随键数线性；预算耗尽会保留 partial。
- `bg_existing_runs_1` 不生成也不删除既有背景键，只记录 `declaredBackground`；`background=0` 且
  `externalBackground=true`，所有展开/遍历/删除路径都不会触达既有键。
- 终帧探测预算默认 10000 条；超过预算且未找到终帧时按 `truncated` 处理为 deferred/unknown，
  不伪报“无终帧”。
- `dual_executor` 用两个线程共享同一 Redis 连接工厂；真实多实例的网络竞争仍需服务级验收。
- 生产落地后仍必须用真实接口做少量端到端验收；harness 通过不等于生产集成已验证。
