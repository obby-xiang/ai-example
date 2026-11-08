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

依赖解析模式由 `PERF_MVN_MODE` 控制，**不需要改脚本**：

| 值 | 行为 | 适用 |
| --- | --- | --- |
| `online`（默认） | 不带 `-o`；缺失依赖会从远端仓库下载 | 新设备、首次拉依赖 |
| `offline` | 两次 Maven 调用都带 `-o`；只用本地仓库 | 既有离线习惯、内网/无网、依赖已就绪 |

```bash
# 新设备首次：默认就是 online，直接跑即可
./run.sh doctor

# 依赖已就绪、想沿用本仓 mvn -o 习惯：
PERF_MVN_MODE=offline ./run.sh doctor
```

```bat
rem Windows CMD
set PERF_MVN_MODE=offline
run.cmd doctor
```

离线模式下缺依赖会构建失败，并提示改回 `PERF_MVN_MODE=online`；脚本不会自动改写文件，
也不会静默降级。`PERF_MVN_MODE` 取 `online`/`offline` 以外的值会在运行前以退出码 2 拒绝。

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
真实 Cluster 的 RED 报告必须回到 Cluster 上复测，不能用 standalone 结果替代。

拓扑兼容基线是 **Redis 5.0+**：Stream 只用 5.0 已有的 `XRANGE` 语法（见 §5 `b_stream_gap`），
内部排他游标在发包前换算成物理 ID 后继的闭区间，线上不出现 Redis 6.2 才引入的 `(lastId`。
standalone 与 cluster 必须跑同一份 HEAD，不得为某个拓扑单独改 Lua、场景或阈值。

### 3.3 关键变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PERF_MVN_MODE` | `online` | **构建期**（`run.sh`/`run.cmd`）Maven 依赖解析：`online` / `offline`（`-o`）；不进入 Java 配置 |
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
| `PERF_BATCH_SIZE` | `500` | 有界 pipeline 批次（1..1000）；候选默认 500（原 100），减少 Cluster 串行往返，不改变断言与门禁 |
| `PERF_STREAM_PAGE_SIZE` `PERF_STREAM_MAX_ENTRIES` | `256` `10000` | Stream 物理 ID 分页；排他游标换算为后继闭区间，兼容 Redis 5.0 的 XRANGE |
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
  批量删除按 `PERF_LEASE_TTL_MS/3` 续租（重写 lease 值里的到期时间并复核 pending 的
  owner/epoch/purgeId），大批次不会把活着的 owner 误判为过期；旧 owner 也不能靠续租推进。
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
- **当前 Java harness 实际契约**（`Scripts.java` / `HarnessMain.java`，本批不修改普通测试）：`durable pending` 的 SET 不带 TTL，包含 `purgeId/owner/epoch/stage/verifyAtMs/deferred`；完成/延后保留，`ai:purge:retry:due` 记录到期项。这里是候选 harness 的现行行为，不是生产 TTL 目标，也不得被文档改写成另一种断言。
- **生产目标，非当前 Java harness 验收口径**：所有 Redis 键都必须有与生命周期匹配的 TTL，7 天只是绝对上限而不是默认值；对话数据目标为最后真实用户活动起 24h。pending/due 与共享全局/身份恢复、列表索引也不能永久；写入/续期只维护本工具 member 和索引 TTL，清理只 `ZREM` 本工具 member，不 `DEL` 仍有其他 member 的索引键，不 `PERSIST`。真实用户 history 通过 `ai:session:user-activity:<t>:<u>:<sid>` 做 60s 跨实例 Redis 节流并续期全部现存关联键；后台读取不续期，完成后的 purge guard 跳过续期，transcript `dataKey` 必须续期成功，失败必须释放窗口。锁和 run lease 等协调键保持各自更短 TTL。
- 守卫写回验证：完成前调用 `PURGE_GUARDED_WRITE`，守卫/pending 存在时必须返回 0。
- 终态收敛：已有终帧（`ai:xterminal` 或 Stream 内 `done/error`）时条件写快照；
  `done` → DONE、**普通业务/模型 `error`** → FAILED、`cancelled:true` → CANCELLED；未知/解析失败不得伪收敛，
  按 deferred/unknown 处理。无终帧且场景允许收尾时补写终帧；否则 deferred。
  `transcriptDegraded=true` 的 typed transcript error 不属于上述普通 `error` 映射，按 §5 C 分类。
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
- `b_stream_gap`：Stream 按物理 entry ID 分页。内部游标是排他语义 `(lastId`，发包前换算成
  **物理 ID 后继**的闭区间（sequence+1；sequence 到 2^64-1 时进位到“下一毫秒-0”），线上只发
  Redis 5.0 支持的 `XRANGE key <start> + COUNT n`，**不出现** 6.2+ 的 `(lastId`（5.0 会报
  `ERR invalid stream ID`）。最大 ID 没有后继时直接收尾，不重复最后一页；预算按真实条目数；
  大跳号下终帧/tool_start 不漏；预算耗尽标 `truncated`，未扫到的 tool_start 是 unknown。
- `b_slot_consistency`：所有多键同槽组本地 CRC16 一致；cluster 下再用 `CLUSTER KEYSLOT` 复核。

### C. 终态错误分类与端到端证据边界（不新增 harness 覆盖）

本 harness 不调用 HTTP，也不生成 API/SSE 终帧；以下是真实端到端验收必须遵守的分类口径，
不表示候选协议场景已经覆盖这些行为：

| 类别 | 必须保留的事实 | 禁止的归并 |
| --- | --- | --- |
| 普通业务/模型 `error` | 按真实失败记 `runStatus=FAILED`，保留实际业务/模型错误码 | 不得借 transcript 降级名义改写为健康完成 |
| typed transcript error | 保留实际 `runStatus`（可以是 `DONE`）、帧内具体 `code`（包括 `AI_TRANSCRIPT_INCOMPLETE` 或 `AI_TRANSCRIPT_WRITE_FAILED`）、`transcriptDegraded=true`、`historyComplete=false` | 不得强行映射为普通 `FAILED`，也不得只看 `DONE` 就记为健康完成或历史完整 |

正常健康 `done/error` 验收一旦出现 `transcriptDegraded=true`、`historyComplete=false` 或上述两个
transcript 错误码，一律判 FAIL；只有专用故障场景可以把 typed transcript error 记为预期降级证据，
且不得混入健康 PASS。`DONE` 只表示 run 的权威状态，不能抵消 transcript 降级。

候选 Lua/键协议、纯逻辑单测和本 harness 的 PASS 只证明各自实际覆盖的 Redis 删除、原子协议、
状态判定与性能门禁。它们**不能外推证明**新的 assistant reasoning 捕获、API history 完整性
（同 run 的最终 assistant、reasoning/工具/表单 parts、顺序和最终结局）或 `DONE + transcriptDegraded`
契约。上述三项必须取得真实 HTTP/API/SSE、真实 Redis 键与 TTL、以及真实业务数据库中既有业务结果的
对照证据；AI 会话及运行数据仍不得落库，不能用静态审查或本 harness 结果替代这些运行证据。

---

## 6. 指标、门禁与退出码

每次运行输出：

| 文件 | 内容 |
| --- | --- |
| `doctor.json` | 只读体检：`redisVersion`/`redisMode`/`modeMatch`/`dbSize`/slot |
| `manifest.json` | 命名空间、每场景/copy 的精确键面、背景规模、状态 |
| `results.json` | 配置脱敏摘要、逐阶段指标、汇总、协议断言、gate |
| `requests.csv` | 每阶段：时长、命令数、pipeline 批次、SCAN 页数、slot 检查、字节（估算） |
| `summary.csv` | 每场景：P50/P95/P99/max、命令数、批次数、SCAN 页数、slot 检查、字节（估算） |
| `protocols.json` | 协议断言逐条 pass/fail（`b_*`、`dual_executor`、`lease_takeover`）；`b_*` 失败 `detail` 含异常全名、`rootCause` 和完整堆栈 |
| `verify.json` / `verify.csv` | 最终键面/状态核对 |
| `cleanup.json` | 清理清单与执行结果 |
| `verify-<scenario>.json` / `cleanup-<scenario>.json` | `all` 每场景证据（后续场景不会覆盖） |

门禁默认值（先冻结，不能看到结果后放宽）：

- `empty` / `minimal`：P95 ≤ 250ms，且无 30s 级长尾；
- `runs_50_terminal`：P95 ≤ 2s；
- `archives_2000`：P95 ≤ 3s；
- 在线删除 SCAN 页数 = 0；
- 背景规模放大后，相同目标的命令数/SCAN 页数不得增长（默认增长上限 0）。

批次大小只影响命令组织，不是放宽门禁的理由：`PERF_BATCH_SIZE` 从 100 调到 500 后，
`PERF_GATE_*`、场景集合和协议断言必须保持原值，看到结果后不得调高。

**协议失败诊断**：`protocols.json` 每条断言记录 `scenario`、`name`、`pass`、`detail`、
`durationNanos`。`b_*` 场景失败时 `detail` 不是一句 `Error in execution`，而是 **异常全名 +
message + `rootCause=` 根因 + 完整堆栈（含全部 `Caused by`）**，外层异常和根因（如
`ERR invalid stream ID`、超时、槽重定向）都能直接看到；不得吞异常或截断。`lease_takeover` 等由
`HarnessMain` 直接记录的断言至少给出 `complete`/`epoch`/`errors` 原文。`results.json.protocols`、
`summary.csv` 的场景状态、`state=protocol` 的 verify 结论与退出码必须一致反映该失败。
远端报告失败时原样附上 `scenario` / `name` / `detail` 以及所在阶段和轮次，只说“协议失败”
按未复测处理。

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
  - `chat:mem:*:gen` / `:ver`（防复活 tombstone，TTL ∈ (0,86400]）
  - `ai:purge:guard:*` / `ai:purge:pending:*`（按当前 harness 的台账/守卫保留规则；当前 `durable pending` 无 TTL）
  - `perf:bg:*` 与背景 marker（背景不属于本会话，仍有兜底 TTL）
- `state=deferred`：当前 Java harness 要求 `ai:purge:pending` 存在且 **TTL=-1**，run 快照仍存在，计划键无缺失
  （证明没有半删）；锁/租约继续核对各自短 TTL。生产目标的 pending/due 有界 TTL 见 §5，不能据此把当前 HarnessMain 的 `pendingTtl == -1` 断言改成生产目标。
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
不会删除其他命名空间、其他会话、业务作业/任务/配置、KB 缓存、共享索引键本身或其他 member；共享索引只按 manifest 精确 `ZREM` 本工具 member，不能为了清理而 `PERSIST` 或整键删除。

---

## 8. 本轮远端复测（定向优先，可直接复制）

本轮真实 Cluster 报告是 **RED 证据**：修复是否有效必须在同一 Cluster 拓扑复测，standalone 通过
不能替代 Cluster。standalone 与 Cluster 必须使用同一 HEAD、同一命令、同一 Lua、同一场景和键形。

### 8.1 本轮要验的四点

| 修复 | 通过证据 |
| --- | --- |
| 候选默认 `PERF_BATCH_SIZE=500` | 未显式覆盖时 `results.json.config.batch_size=500`；`summary.csv` 的 pipeline 批次数下降，场景集合、断言和门禁不变 |
| Redis 5.0 兼容 XRANGE | Redis 5.0 上 `b_stream_gap` 通过，无 `ERR invalid stream ID` / 语法错误；终帧与 tool_start 不漏 |
| 协议异常完整诊断 | `b_*` 失败时 `protocols.json` 的 `detail` 含异常全名、message、`rootCause=` 和完整堆栈；`lease_takeover` 含 `complete`/`epoch`/`errors`；`scenario` / `name` 可直接定位 |
| doctor INFO 修复 | 原生 `INFO` bulk 解析 + 结构化回退（合并 cluster 的 `host:port.` 前缀键）；`doctor.json` 的 `redisVersion` / `redisMode` / `modeMatch` 与真实拓扑一致；Redis 5.0 Cluster 必须 `status=ok`、`modeMatch=true`。Cluster 下原生与结构化 INFO 都不可用会得到 `redisVersion/redisMode=unknown`、`modeMatch=false` 并以退出码 2 失败，不能静默误判为 standalone |

### 8.2 定向提示词（可直接复制）

```text
你在另一台隔离设备上复测 Redis 候选协议 harness。先读：
  docs/research/审查-运行时Redis与接口性能-2026-11-04/21-redis-change-preflight-plan.md
  scripts/perf/redis-protocol/README.md

硬性边界：
- 先拉本轮新 HEAD，不要沿用旧工作副本：
  git fetch --prune origin && git pull --ff-only
  git rev-parse HEAD && git rev-parse --short HEAD && git log -1 --stat
  记录完整/短提交号；确认 scripts/perf/redis-protocol 和
  backend/src/test/java/com/example/configmgr/perf/redis 下 git status --porcelain 为空。
  只读抽查本 HEAD 已包含（不要改）：
  H=backend/src/test/java/com/example/configmgr/perf/redis
  git show HEAD:$H/HarnessConfig.java | grep PERF_BATCH_SIZE   → 默认 500；
  git show HEAD:$H/CountingRedis.java | grep -n xrangeStart    → 5.0 后继闭区间换算；
  git show HEAD:$H/RedisHarness.java | grep -n parseInfo       → INFO bulk 解析；
  git show HEAD:$H/RunProtocol.java | grep -n diagnostic       → 完整异常链。
  四项缺一不可；缺任何一项说明没拉到新 HEAD，停止并报告提交号。
- 不启动项目服务，不调用任何 HTTP API，不驱动真实模型；不跑全仓测试。
- 不改 backend/src/main、frontend、backend/pom.xml；不手工增删 Maven 的 -o 参数，
  在线/离线只由 PERF_MVN_MODE 控制（默认 online）。
- 只用专用隔离 Redis；任何连接（含 doctor/verify）必须 PERF_ISOLATED_ENV=true；远程/集群必须
  PERF_CONFIRM_REMOTE=true；cluster 只允许 db 0；不执行 FLUSHDB/FLUSHALL/KEYS/DEBUG/CONFIG。
- 失败或预算耗尽时保留输出目录，不删证据，不伪报通过。
- 禁止为了让结果变绿而调大 PERF_GATE_*、删场景、放宽断言，或用 standalone 结果替代 Cluster。

执行顺序（standalone、cluster 分开命名空间和输出目录）：
0) 依赖解析：默认 PERF_MVN_MODE=online；依赖就绪后如要离线，再显式 export PERF_MVN_MODE=offline。
1) ./run.sh scenarios        (Windows CMD: run.cmd scenarios)
2) ./run.sh doctor
   检查 doctor.json：redisVersion、redisMode、modeMatch 与目标拓扑一致；
   Redis 5.0 Cluster 必须 status=ok、modeMatch=true。doctor 失败先报告，不要继续。
3) 只跑定向五场景，不要加其他场景：
   export PERF_PROFILE=full
   export PERF_SCENARIOS=b_stream_gap,empty,minimal,archives_2000,lease_takeover
   export PERF_ALLOW_LARGE_SCALE=true
   export PERF_NAMESPACE=rp-target-sa-$(date +%Y%m%d%H%M)
   export PERF_OUTPUT_DIR=./out-target-sa
   ./run.sh all --execute
   Cluster 用同一场景和参数，只把 REDIS_MODE、REDIS_CLUSTER_NODES、PERF_NAMESPACE、
   PERF_OUTPUT_DIR 换成 cluster 的值，输出到 ./out-target-cl。
4) 定向验收（必须全部满足；任一失败立即停止并保留输出）：
   - 退出码 0；五个场景均有结果，无 skipped/partial/failed/deferred；
   - protocols.json 全 pass，尤其 b_stream_gap 的终帧/tool_start 断言与 lease_takeover 的旧 owner 拒绝；
   - Redis 5.0 下无 XRANGE 语法错误；未显式覆盖时 results.json 的 config.batch_size=500；
   - summary.csv：empty/minimal P95 ≤ 250ms，archives_2000 P95 ≤ 3000ms；
   - verify.csv 无 fail/unknown；cleanup.json complete=true。
   失败报告必须给出：提交号、拓扑、场景、断言 name、detail 全文（b_* 含异常全名+message+cause
   与堆栈；lease_takeover 含 complete/epoch/errors）、阶段/轮次、requests.csv 对应行、
   doctor.json。不要放宽门禁后重跑。
5) 定向在 standalone 与 cluster 都通过后，才允许 smoke：
   新命名空间 + 新输出目录，PERF_PROFILE=smoke ./run.sh all --execute
6) smoke 通过后再 full：
   新命名空间 + 新输出目录，PERF_PROFILE=full PERF_ALLOW_LARGE_SCALE=true ./run.sh all --execute
7) 如需 10 万背景：单独 PERF_SCENARIOS=bg_100k_runs_1 逐场景 generate/measure/cleanup，
   不要和 bg_10k 同时生成；大键空间 Cluster 用 bg_existing_runs_1 + PERF_EXISTING_BG_SCALE。
8) 每个输出目录最后 cleanup --execute，确认 cleanup.json complete=true。

报告：新 HEAD 完整提交号、拓扑、Redis 版本/mode、PERF_MVN_MODE、命令、退出码、batch_size、
summary.csv 的 P50/P95/P99、命令数、pipeline 批次数、SCAN 页数、slot 检查、protocols.json 失败项
（scenario/name/detail 原文）、verify 的 fail/unknown、cleanup 残留。任何 unknown 说明原因。
```

### 8.3 定向通过后的 smoke / full

定向五场景是回归闸门：**先 standalone、再 cluster 各跑一遍**，两边都 `0` 且证据齐全，才进入
§8.2 第 5、6 步。smoke/full 必须使用新命名空间和新输出目录，不能复用定向运行的命名空间；
不得因为 full 里某个场景慢就回头调高 `PERF_GATE_*` 或从 `PERF_SCENARIOS` 里删场景。

---

## 9. 已知边界 / 未实测

- 本轮 JSON Long 双形态、pending/due 与共享索引 TTL、24h 滑动续期及 60s 跨实例节流文档同步仅为静态修改，测试、构建、真实 HTTP/Redis、standalone/Cluster 均为 `NOT_RUN`；上述规则的真实键/TTL 验收由 `scripts/perf/redis` 的显式 `session_activity*` 场景执行，不能用本段文字替代运行证据。
- 新设备首次运行默认 `PERF_MVN_MODE=online`，需要能访问 Maven 仓库（或已配置镜像）；内网无网设备
  可先在线预热一次 `~/.m2`，之后用 `PERF_MVN_MODE=offline`。脚本不再要求手工增删 `-o`。
- **本机基线测试源集**有与本任务无关的编译错误（例如 `AiRateLimiter`、`ClientIpResolver`
  在 test 源集被引用但 main 源集不存在）。因此本机用 `run.sh` 的“只编译本包”路径验证；
  `mvn -Dtest=...+ test` 需要基线测试源集可编译。
- **未连接本机 Redis**（按任务约束）。本交付只验证了编译、纯逻辑单测与 CLI dry-run；
  真实 standalone/cluster 的 SQL/Lua/拓扑行为需在另一台设备按 README 执行。
- **Redis 5.0 的 XRANGE**：不能用 6.2+ 的 `XRANGE ... (lastId` 排他区间；候选实现把
  `(lastId` 换算成后继 ID（sequence+1，溢出进位到下一毫秒）的闭区间起点，线上不出现 `(`。
  Redis 5.0 报 `ERR invalid stream ID` 说明仍在跑旧 HEAD。
- `PERF_BATCH_SIZE=500` 只改变 pipeline 的命令组织，不改变单命令延迟、断言或门禁；p95 变化必须
  结合命令数/批次数解释，不能用更大的 batch 掩盖 RED。大批次下租约按 `PERF_LEASE_TTL_MS/3`
  续租，避免长 pipeline 被误判为过期。
- doctor 先用原生命令读完整 `INFO` bulk 并解析 `redis_version`/`redis_mode`/`cluster_enabled`，
  失败才回退结构化 `info()`（cluster 会带 `host:port.` 前缀，按后缀合并）；两者都不可用时字段为
  `unknown`，不会伪造版本或拓扑；Cluster 下 `modeMatch` 为 false 并以退出码 2 失败，
  standalone 下只保留 unknown 并在报告里标明。复测报告必须附 `doctor.json`。
- 真实 Cluster 的 RED 证据只能由 Cluster 复测关闭；standalone 全绿不算关闭。
- 字节数是估算，不是网卡计数；pipeline 的“1 个批次”不等于 1 条命令。
- `bg_100k` 与 `archives_2000` 的生成/清理耗时随键数线性；预算耗尽会保留 partial。
- `bg_existing_runs_1` 不生成也不删除既有背景键，只记录 `declaredBackground`；`background=0` 且
  `externalBackground=true`，所有展开/遍历/删除路径都不会触达既有键。
- 终帧探测预算默认 10000 条；超过预算且未找到终帧时按 `truncated` 处理为 deferred/unknown，
  不伪报“无终帧”。
- `dual_executor` 用两个线程共享同一 Redis 连接工厂；真实多实例的网络竞争仍需服务级验收。
- 生产落地后仍必须用真实接口做少量端到端验收；harness 通过不等于生产集成已验证。
