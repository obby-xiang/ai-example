# AI 会话 / Redis 性能验证工具（scripts/perf/redis）

本工具用于在**另一台隔离设备**上，对同一套真实项目代码分别连接 **standalone Redis** 与 **Redis Cluster**，生成符合项目键形/JSON/Stream 形状的数据，并通过**真实 HTTP API** 测量会话删除、读取与最终清理。

- Python 只负责**造数据**和**调用接口**，不重写、不模拟任何删除逻辑。
- 删除一律走 `DELETE /api/ai/session/{sessionId}`，由后端 `AiSessionPurgeService` 真正执行。
- 结果同时给出 HTTP 耗时、`complete/deferred/errors`、按源码推导的最终键面核对，以及清理完整性。
- 只允许操作本工具命名空间（`PERF_NAMESPACE`）与它生成的会话/文件；**没有** `FLUSHDB`、`FLUSHALL`、`KEYS`、`DEBUG`、`CONFIG`，扫描只用 `SCAN`。

> 验收原则（贯穿全文）：**响应提前返回 ≠ 最终清理完成**。`200 + complete=true` 只代表本次删除主体完成；`pending/guard/gen/ver/xack/xtrim/lock` 是有意保留，最终是否干净必须看 `verify` 与 `cleanup` 的实测状态。

---

## 1. 源码依据（为什么要这样造数据）

关键事实均从生产源码读取，未按摘要猜测：

| 事实 | 源码 |
| --- | --- |
| 删除入口：`DELETE /api/ai/session/{sessionId}`，响应是裸对象 | `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java` |
| 身份头：`X-Tenant-Id` / `X-User-Id`；目录仅 `1/101, 1/102, 2/201, 2/202` | `ai/tenant/TenantIdentityFilter.java`、`ai/tenant/TenantUserDirectory.java` |
| 两趟全键空间 SCAN：staging 前缀、legacy archive 前缀；Cluster 逐 master 串行 | `ai/session/AiSessionPurgeService.java`（`stagingKeys`、`scanArchives`）、`ai/session/RedisClusterScanSupport.java` |
| 归档发现 = SCAN ∪ `ctx.turnNo` 推导（上限 2000） | `AiSessionPurgeService#archiveKeys/archivesFromTurnNo` |
| run 13 键同槽 DEL | `ai/run/RunStore.java#deleteRunKeys` |
| run 级键 tag：`{<tenantId>:<userId>:run:<runId>}` | `ai/tenant/RunKeyScope.java`、`RunStore#runTag` |
| 快照 JSON 字段与状态：RUNNING/SUSPENDED/DONE/FAILED/CANCELLED | `ai/run/RunSnapshot.java` |
| 待决 HASH：field=toolCallId，value=PendingToolCall JSON | `ai/run/RunStore.java#saveSuspended/readPendings`、`ai/run/PendingToolCall.java` |
| 记忆键：`chat:mem:<t>:<u>:{<escapedId>}` + `:ver` / `:gen`；staging：`chat:mem:staging:<t>:<u>:{<escapedId>}:<uuid>` | `ai/memory/RedisChatMemoryRepository.java` |
| transcript：`ai:tx:<t>:<u>:{<escapedId>}` + `:bytes/:ready/:truncated` | `ai/history/ConversationTranscriptStore.java` |
| 帧 Stream：`ai:xevents:{tag}`，ID=`<seq>-0`，字段 `seq` + `frame` JSON；`ai:seq`、`ai:xack:<token>:<tag>`（120s）、`ai:xtrim:<tag>` | `ai/run/RedisFrameStore.java` |
| 终帧探测预算 = 6000 + 256 帧，起点 = `lastSeq - 6256` | `ai/run/SseChatEmitter.java#terminalScanStart/scanArchive` |
| guard / pending / due：`ai:purge:guard:*`、`ai:purge:pending:*`（无 TTL）、`ai:purge:retry:due` | `ai/session/SessionPurgeGuard.java`、`SessionPurgePendingStore.java` |
| 60 秒记忆复核（complete 后保留 pending） | `AiSessionPurgeService#MEMORY_VERIFY_DELAY_MS/verifyMemoryOnly` |
| 附件四族键与真实上传口 | `ai/form/RedisFormAttachmentStore.java`、`GenerativeFormAttachmentController.java` |
| 真实读取接口：history / context / runs/current / runs/{id} / events?probe=1 / cancel / pending / kb | `ai/web/AiController.java`、`ai/kb/AiKbController.java` |

### 1.1 有意保留（不得误报为失败）

| 键 | 语义 | 工具判定 |
| --- | --- | --- |
| `ai:purge:guard:<t>:<u>:<sid>` | 防写回守卫，TTL=session-ttl（默认 24h） | `complete=true` 时必须存在且 TTL>0 |
| `ai:purge:pending:<t>:<u>:<sid>` | durable 台账，无 TTL；complete 后保留到 60s 记忆复核 | 存在时 TTL 必须为 -1，包含 `verifyAtMs`，且在 `ai:purge:retry:due` |
| `chat:mem:...:{sid}:gen` / `:ver` | 防复活 tombstone，清空脚本会 INCR 并续 TTL | 存在且 TTL>0；**不是残留** |
| `ai:xack:<token>:<tag>` | 出帧幂等标记，120s | 存在时 TTL ∈ (0,130] |
| `ai:xtrim:<tag>` | 真实裁剪低水位，不在 13 键内 | 存在且 TTL>0，仅报告 |
| `ai:session:lock:<t>:<u>:<sid>` | 协调锁，源码明确不删 | 存在且 TTL>0，仅报告 |
| `ai:lock:task:*` / `ai:lock:job:*` | 资源锁，按 taskId 记，与 run 生死无关 | 不在删除面，仅提示 |

### 1.2 源码已证实的删除顺序（用于判读结果）

`枚举 → 登记 pending → 装 guard → 删 H2 反馈 → 取消活跃 run → 等终态 → 删记忆/transcript/run 13 键/上下文/会话/附件 →（仅无 errors/deferred）删发现键 → guard 收尾 + 排 60s 记忆复核`

因此：

- **枚举/guard/DB 中止**：Redis 一个键都没删（`aborted=true` + `phase`）。
- **活跃 run 未在窗口内终态**：`deferred`，该 run 的 **13 键全部保留**，发现键/pending/guard 保留。
- **主体有 errors**：`complete=false`，pending/guard 保留，发现键不删。
- **complete=true**：本会话业务键已删；guard/pending/gen/ver 仍会有意存在。

---

## 2. 安装（另一台设备）

要求：Python **3.9+**，能访问目标 Redis 与应用 HTTP 端口。依赖仅 `redis-py` 与 `httpx`。

```bash
# Linux / macOS
cd <repo>/scripts/perf/redis
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -U pip
python -m pip install -r requirements.txt
```

```powershell
# Windows PowerShell
cd <repo>\scripts\perf\redis
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -U pip
python -m pip install -r requirements.txt
```

所有命令都在 `scripts/perf/redis` 目录下执行（模块入口 `perf_redis`）。仓库其他代码不需要改。

---

## 3. 配置（全部走环境变量）

模板见 [.env.example](.env.example)。工具**不会自动读取 `.env`**；请把变量导出到当前 shell（或由 CI/密钥管理注入）。模板无密钥、无本机绝对路径。

### 3.1 最小 standalone 配置

```bash
export REDIS_MODE=standalone
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_DB=0
export APP_BASE_URL=http://127.0.0.1:8081
export APP_TENANT_ID=1
export APP_USER_ID=101
export PERF_ISOLATED_ENV=true
export PERF_NAMESPACE=perf-$(date +%Y%m%d)-a
export PERF_PROFILE=smoke
```

### 3.2 Cluster 配置

```bash
export REDIS_MODE=cluster
export REDIS_CLUSTER_NODES=10.0.0.11:6379,10.0.0.12:6379,10.0.0.13:6379
export REDIS_DB=0                      # Cluster 只支持 0
export APP_BASE_URL=http://10.0.0.21:8081
export PERF_ISOLATED_ENV=true
export PERF_CONFIRM_REMOTE=true        # 非 loopback 必须显式确认
export PERF_NAMESPACE=perf-cluster-a
```

应用侧必须用同一拓扑启动（项目 `application-cluster.yml`）：

```bash
export SPRING_PROFILES_ACTIVE=cluster
export AI_REDIS_CLUSTER_NODES=10.0.0.11:6379,10.0.0.12:6379,10.0.0.13:6379
# Cluster profile 同时按"多实例"校验：文件存储必须是共享绝对路径
export APP_FILE_STORAGE_PATH=/mnt/shared/config-mgr/files
```

> 工具直连 Redis；应用通过自己的 Spring 配置连接。两边必须是同一 Redis 拓扑/同一逻辑库，否则工具造的数据应用看不到。Standalone 应用覆盖连接用 `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT`（应用默认 db=0）。

### 3.3 关键变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `REDIS_MODE` | `standalone` | `standalone` / `cluster` |
| `REDIS_HOST` `REDIS_PORT` `REDIS_DB` | `127.0.0.1` `6379` `0` | standalone；非 0 库需 `PERF_CONFIRM_NON_DEFAULT_DB=true` |
| `REDIS_CLUSTER_NODES` | 空 | cluster 种子，`host:port,...` |
| `REDIS_USERNAME` `REDIS_PASSWORD` | 空 | ACL；不打印、不落结果 |
| `REDIS_TLS` `REDIS_TLS_INSECURE` | `false` | TLS；自签集群才用 insecure |
| `APP_BASE_URL` | `http://127.0.0.1:8081` | 应用地址 |
| `APP_TENANT_ID` `APP_USER_ID` | `1` `101` | 必须在 `/api/ai/tenants` 目录内 |
| `APP_HTTP_TIMEOUT_SECONDS` | `300` | DELETE 可能远超浏览器 60s |
| `PERF_ISOLATED_ENV` | `false` | **写操作必须 true** |
| `PERF_CONFIRM_REMOTE` | `false` | 远程 Redis 必须 true |
| `PERF_CONFIRM_NON_DEFAULT_DB` | `false` | standalone 非 0 库必须 true |
| `PERF_NAMESPACE` | 自动生成 | 字母/数字/`_.-`，2..41；自动值持久化在输出目录 `.namespace` |
| `PERF_PROFILE` | `smoke` | `smoke` / `full` / `stress` |
| `PERF_SCENARIOS` | 空 | 逗号分隔；显式点名被阻止的场景会直接失败 |
| `PERF_REPEAT` `PERF_WARMUP` `PERF_CONCURRENCY` | `2` `1` `1` | 每个破坏性场景每轮一个独立 copy；并发只作用于首个 DELETE；`concurrent_delete` 至少 2 路并发 |
| `PERF_OUTPUT_DIR` | `./perf-redis-out` | 相对当前目录；跨设备可任意 |
| `PERF_KEY_TTL_SECONDS` | `43200` | 所有生成键的兜底 TTL（12h） |
| `PERF_BATCH_SIZE` `PERF_SCAN_COUNT` | `500` `1000` | pipeline 批量 / SCAN COUNT |
| `PERF_VERIFY_SETTLE_SECONDS` | `0` | verify 前等待（例如等 60s 记忆复核） |
| `PERF_KEEP_GUARD` | `false` | cleanup 时保留 guard/pending（有真实写者时用） |
| `PERF_ENABLE_ATTACHMENTS` / `PERF_ENABLE_KB` | `true` | 关闭后对应场景被跳过 |
| `PERF_ENABLE_KB_RELOAD` | `false` | 是否允许 `POST /api/ai/kb/reload` |
| `PERF_APP_FILE_STORAGE_PATH` | 空 | 应用 `app.file.storage-path` 的绝对路径，用于文件核对/删除 |
| `PERF_ALLOW_FILE_DELETE` | `false` | true 才真正删生成的文件 |
| `PERF_WRITE_GLOBAL_RUN_INDEX` | `false` | 是否把合成 run 写入全局 `ai:runs`。默认 false：应用的 startup-resume 会扫该索引，可能用真实模型驱动合成 run |
| `PERF_ALLOW_LARGE_SCALE` | `false` | 大规模场景开关 |
| `PERF_ALLOW_BACKGROUND_MILLION` | `false` | 100 万背景键开关（还需 large） |

尺寸变量：`PERF_BG_KEYS_{10K,100K,1M}`、`PERF_RUNS_{SMALL,MED,LARGE}`、`PERF_STAGING_{SMALL,LARGE}`、`PERF_ARCHIVE_{SMALL,LARGE}`、`PERF_FRAMES_{SMALL,LARGE}`、`PERF_TRANSCRIPT_{SMALL,LARGE}`、`PERF_MEMORY_{SMALL,LARGE}`、`PERF_ATTACHMENT_MAX`、`PERF_FEEDBACK_ROWS`。

---

## 4. 启动项目（被测端）

依据仓库根 `README.md`：

```bash
cd <repo>/backend
mvn clean package -DskipTests -s maven-settings.xml
java -jar target/config-mgr.jar
```

- 默认端口 `8081`；`/api/ai/health` 无模型时可能 `503 AI_UNAVAILABLE`，本工具不需要模型。
- Redis 不可用时 AI 端点返回 `503 AI_REDIS_UNAVAILABLE`；先 `doctor` 确认。
- H2 文件库默认 `./data/config_mgr_db`；本工具只通过真实反馈接口写/删自己会话的行，不直接改库。

---

## 5. 命令与完整步骤

```bash
# 0) 打印矩阵（不写任何数据）
python -m perf_redis scenarios

# 1) 只读体检：配置、Redis 拓扑/版本/模式、命名空间、应用身份、真实读接口
python -m perf_redis doctor

# 2) 深度预检：写探测（本命名空间一个临时键）、SCAN 权限、输出目录、依赖、集群槽位
python -m perf_redis preflight

# 3) 造数据（按当前 profile/场景；可分批，键全部带 TTL）
python -m perf_redis generate

# 4) 预热 + 多轮 + 并发测量真实 HTTP API
python -m perf_redis measure

# 5) 按源码语义核对最终键面/DB/文件/有意保留
python -m perf_redis verify

# 6) 先看清单（默认 dry-run），确认后执行
python -m perf_redis cleanup
python -m perf_redis cleanup --execute

# 7) 一键（preflight→generate→measure→verify→cleanup 执行）
python -m perf_redis all
python -m perf_redis all --keep              # 保留数据
python -m perf_redis all --cleanup-dry-run   # 只打印清理清单
```

建议顺序与产出：

> `doctor` 对 Redis/应用只读；它会创建 `PERF_OUTPUT_DIR` 并在其中写 `.namespace`（工具状态文件，不是 Redis 数据）。`preflight` 会额外写一个带 TTL 的 `perf:doctor:<ns>:probe` 键并立即删除。

| 步骤 | 产物 | 失败退出码 |
| --- | --- | --- |
| `doctor` | `doctor.json` | 2（fail/unknown） |
| `preflight` | `preflight.json` | 2 |
| `generate` | `manifest.json`（每 copy 增量落盘） | 1 生成失败；3 场景 unsupported |
| `measure` | `results.json` `requests.csv` `summary.csv` | 1（传输失败，或 health 之外的非 2xx） |
| `verify` | `verify.json` `verify.csv`（并回写 results） | 1 fail；2 unknown |
| `cleanup` | `cleanup.json` `cleanup.csv` | 1（执行后仍有残留） |
| `all` | 以上全部 | 1/2/3；preflight 有 fail 或 unknown 即停止 |

### 5.1 跨设备/双拓扑做法

同一份代码、同一套场景，跑两遍（输出目录分开）：

```bash
# A: standalone
export REDIS_MODE=standalone REDIS_HOST=... PERF_NAMESPACE=perf-sa-1 PERF_OUTPUT_DIR=./out-sa
python -m perf_redis all

# B: cluster
export REDIS_MODE=cluster REDIS_CLUSTER_NODES=... PERF_CONFIRM_REMOTE=true \
       PERF_NAMESPACE=perf-cl-1 PERF_OUTPUT_DIR=./out-cl
python -m perf_redis all
```

对比 `summary.csv` 的 `p50/p95/p99`，再看 `verify.csv` 是否存在 fail/unknown。**不要**用一次请求的耗时下结论。

### 5.2 中断后恢复

- 所有生成键都有 TTL（默认 12h）；即使机器断电也会自清。
- `manifest.json` 在**每个 copy 生成前**先写占位（含 `session_id`/场景/计划），中断后 `cleanup` 仍能按计划推导 runId 与键。
- 直接再跑 `cleanup`（先 dry-run）即可；不要手动 `FLUSHDB`。
- `generate` 对已生成场景会跳过；要**重新完整测量**，换 `PERF_NAMESPACE`（或换 `PERF_OUTPUT_DIR`）后重新 generate。

---

## 6. 场景矩阵

`PERF_PROFILE=smoke`（默认）只跑小规模；`full` 增加 10 万背景、50 run、大 staging/archive/transcript/frames、附件、反馈等；`stress` 再增加 100 万背景。大规模/百万均需显式开关。

| 场景 | 证明什么 | smoke | full | stress |
| --- | --- | --- | --- | --- |
| `empty_session` | 不存在会话的幂等删除；仍会留下 guard/pending/记忆 tombstone | ✓ | ✓ | ✓ |
| `minimal_session` | 最小会话（仅 `ai:session` 记录） | ✓ | ✓ | ✓ |
| `runs_0` | run 数 0 | ✓ | ✓ | ✓ |
| `runs_1_terminal` | 1 个终态 run 的 13 键 + pending/decision/索引 | ✓ | ✓ | ✓ |
| `runs_10_terminal` | 10 个终态 run | ✓ | ✓ | ✓ |
| `runs_50_terminal` | 50 个终态 run（串行逐 run 成本） |  | ✓ | ✓ |
| `runs_mixed` | 终态/取消/挂起/运行中混合；活跃 run 不得被半删 |  | ✓ | ✓ |
| `runs_gate_busy` | 会话门被另一轮持有；活跃 run 结果允许 deferred 或完成，但不得半删 |  | ✓ | ✓ |
| `runs_with_frames` | 终态 run + 多 Stream 归档 |  | ✓ | ✓ |
| `staging_small` / `staging_large` | 记忆 staging 全键空间 SCAN | ✓ /  | ✓ | ✓ |
| `archive_turnno` | `ctx.turnNo` 推导 ∪ SCAN 并存 | ✓ | ✓ | ✓ |
| `archive_scan_only` | 无 turnNo，只能靠 SCAN |  | ✓ | ✓ |
| `archive_large` | 2000 个 legacy archive |  | ✓ | ✓ |
| `archive_over_turnno` | turnNo > 2000，推导关闭，只能 SCAN |  | ✓ | ✓ |
| `transcript_small` / `transcript_large` | 用户展示历史大小 |  | ✓ | ✓ |
| `memory_large` | 记忆窗口 + transcript |  | ✓ | ✓ |
| `frames_terminal_tail` | 活跃 run，终帧在尾部 | ✓ | ✓ | ✓ |
| `frames_terminal_head` | 终帧在首（小窗口内） |  | ✓ | ✓ |
| `frames_terminal_middle` | 终帧在中部 |  | ✓ | ✓ |
| `frames_terminal_missing` | 无终帧，取消收尾需补写 |  | ✓ | ✓ |
| `frames_terminal_error` | `error` 终帧同判 |  | ✓ | ✓ |
| `frames_gap_large` | 9000 帧 + 大 seq 跳号 + 终帧在首；源码 6256 预算按 seq 差可能漏帧 |  | ✓ | ✓ |
| `suspended_pending` | 挂起 run + 待决条目 | ✓ | ✓ | ✓ |
| `attachments_1` / `attachments_3` | 真实上传附件、文件/登记/索引清理 |  | ✓ | ✓ |
| `feedback_rows` | H2 `ai_feedback` 行经真实接口写入/删除 |  | ✓ | ✓ |
| `repeat_delete` | 同会话连续两次 DELETE 的幂等 | ✓ | ✓ | ✓ |
| `concurrent_delete` | 同会话并发 DELETE（源码无记录级单飞） | ✓ | ✓ | ✓ |
| `reads_suspended` | history/context/runs-current/runs 列表/runs/{id}/events-probe/cancel/pending/startup-resume | ✓ | ✓ | ✓ |
| `health_read` | `GET /api/ai/health` | ✓ | ✓ | ✓ |
| `kb_status_read` | KB 状态只读（KB 缓存不属于会话销毁面） | ✓ | ✓ | ✓ |
| `kb_reload` | 可选：本地 Lucene 重建（需显式开启） |  | ✓ | ✓ |
| `bg_10k` | 1 万背景键的全库 SCAN 固定成本 |  | ✓ | ✓ |
| `bg_100k` | 10 万背景键 |  | ✓ | ✓ |
| `bg_1m` | 100 万背景键（显式允许） |  |  | ✓ |

背景键位于 `perf:bg:<namespace>:<bucket>:<n>`，**不属于任何会话**；删除后必须仍存在（verify 会抽查）。

### 6.1 数据形状

- 会话号：`p-<namespace>-<scenario>-<copy>`；runId：`<sessionId>-r<i>`。两者都是不透明字符串，删除服务按精确键/转义 glob 处理，支持非数字 id。
- run 快照：`ai:run:{<t>:<u>:run:<id>}`，JSON 字段与 `RunSnapshot` 一致（`runId/sessionId/tenantId/userId/status/messageJson/...`）。
- 待决：`ai:pending:{...}` HASH，field=`toolCallId`，value=`PendingToolCall` JSON；`ai:req`/`ai:decided`/`ai:claim` 为 HASH。
- Stream：`ai:xevents:{...}`，`XADD <seq>-0 seq <n> frame <json>`；终帧 `{"type":"done"|"error","seq":n,...}`。
- 附件：**只通过真实上传口** `POST /api/ai/form/attachments` 生成（需要先有 SUSPENDED run + `generative_form` PENDING）。工具随后从登记键读取 `storagePath` 存档。
- 反馈：**只通过真实** `POST /api/ai/feedback` 生成。
- 其它：`ai:ctx:*`、`ai:tx:*`、`chat:mem:*`、`ai:ctdraft:*`、`ai:creq:*`、`ai:session:*`、`ai:beat/activity/cancel/observed`。

---

## 7. 结果判读

### 7.1 文件

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | 命名空间、配置摘要、尺寸、每个场景/copy 的 id、run 列表、附件/反馈、槽位检查、生成错误 |
| `results.json` | 环境（Redis 版本/模式/拓扑、应用 health）、git 只读摘要、脱敏配置、全部请求、统计、verify、cleanup、验收字段 |
| `requests.csv` | 每次请求：场景/copy/轮次/是否预热/操作/HTTP 状态/`accepted`/`failure_kind`/耗时/complete/found/aborted/phase/deferred/error/传输错误 |
| `summary.csv` | 每场景×操作：count、mean、P50、P95、P99、min、max、HTTP 状态分布 |
| `verify.csv/json` | 每条核对：pass/fail/unknown/info/skip + 说明 |
| `cleanup.csv/json` | 清理清单、执行结果、剩余键、文件、反馈行 |
| `doctor.json` / `preflight.json` | 环境体检与预检 |

`results.json` 不含密码；只记录 `username_present/password_present` 与 `config_digest`。

### 7.2 响应字段

- `complete=true`：本次删除主体完成；**不**表示 guard/pending/gen/ver 立刻消失，也不表示 60s 记忆复核已执行。
- `complete=false + deferred=[...]`：活跃 run 未终态；13 键与发现面保留，可重试。
- `complete=false + errors=[...]`：有键/DB/文件失败；pending/guard 保留。
- `aborted=true + phase`：`pending`/`enumerate`/`guard`/`database` 阶段中止；Redis 未删或只删了 H2（`database` 阶段 H2 删除失败时 Redis 未动）。
- `503`：Redis 不可用；不等于删除完成。唯一例外是 `GET /api/ai/health` 的 503：它是“AI 不可用”的正常表达，记为 `allowed_http`/`accepted`，不计入失败。

### 7.3 失败口径（transport / http / allowed_http）

- `transport`：连接/超时/无 HTTP 状态；`results.json` 的 `transport_failures`。
- `http`：拿到状态但不在 2xx，且不是 `health` 的 503；`http_failures`。
- `allowed_http`：仅 `GET /api/ai/health` 的 503；`allowed_http_responses`。
- `failed_requests = transport_failures + http_failures`；`measure` / `all` 在 `failed_requests > 0` 时失败。
- `requests.csv` 保留每行 `accepted` / `failure_kind`；`summary.csv` 保留逐场景 HTTP 分布（含 `status_503` 列）。

### 7.4 耗时

- `mean/P50/P95/P99/min/max` 只统计**非预热**请求；并发场景每次调用各占一行。
- 破坏性场景每轮使用独立 copy，避免“第二轮删空会话”污染数据。
- 对比重点：
  1. `bg_10k / bg_100k / bg_1m` 与 `runs_1_terminal` 的差值 → 全库 SCAN 固定成本；
  2. `runs_1/10/50` → 串行逐 run 成本；
  3. `staging_*`、`archive_*`、`transcript_*` → 各自枚举/逐键成本；
  4. `frames_terminal_* / frames_gap_large` → 活跃 run 的取消收尾/终帧探测；
  5. standalone vs cluster 同场景 → 逐 master 串行 SCAN 与 RTT 差异。

---

## 8. verify 的判定逻辑（不是只测 HTTP 时间）

按响应分支：

1. `complete=true`
   - 本会话所有生成键（含 13 键、anchors、ctx/archive、transcript、memory/staging、会话/发现键、ctdraft/creq、附件四族）必须不存在；
   - guard 必须存在且 TTL>0；
   - pending 若存在：无 TTL、`verifyAtMs` 非空、在 `ai:purge:retry:due`；
   - `gen/ver` 必须存在且 TTL>0（有意 tombstone）；
   - `xack` 若存在 TTL ∈ (0,130]；`xtrim`/`session lock` 仅报告；
   - `ai:runs` 全局成员必须已 SREM；
   - 附件登记/索引/文件按响应 `attachments.registrationsDeleted` 与磁盘核对；
   - 反馈行通过 `GET /api/ai/feedback` 按 messageId 核对（列表上限 100；达到上限记 `info`，不伪报 pass）。
2. `complete=false`
   - 每个 `deferred` run 的 **13 键必须全部保留**（部分缺失 = fail）；
   - 响应 `deleted=true` 的 run 的 13 键必须不存在；
   - pending 必须存在且无 TTL；guard 必须存在且 TTL>0；
   - 仍有 deferred run 时，current/runs/active 至少一个存在；无 deferred 时仅提示（空集合会被 Redis 自动删除）。
3. `aborted=true`
   - 会话记录/run 快照/台账/ctx/transcript/memory 仍在（“一个键都没删”）。
4. 只读场景
   - 每个读接口 HTTP 2xx（health 允许 503）；
   - 读后数据仍完整（不会被读接口删除）；
   - `runs/current found=true`（有 SUSPENDED 时）、`runs/{id} exists=true`、history 非空。

状态含义：`pass` 通过；`fail` 明确违反；`unknown` 读/接口不可达，不能当通过；`info` 有意保留或不可判定；`skip` 场景未生成。

### 8.1 已知源码边界（工具如实记录，不伪造通过）

- 终帧探测从 `lastSeq - 6256` 起扫且预算按“帧数”当“seq 差”用；当 `frames_gap_large` 的终帧在 `seq=1`、末号约 90 万时，探测**预期漏帧**。工具记录该场景的耗时与结果；由于 run 13 键（含 Stream）随后被删，无法在删除后复查“是否漏帧”，因此这是**观测项而非断言**。
- 活跃 run 的最终结果取决于当拍取消收尾能否完成：`frames_terminal_missing`、`runs_mixed`、`runs_gate_busy` 允许 `deleted` 或 `deferred`，但 verify 强制“不得半删”。

---

## 9. 安全与清理

- 写操作前置：`PERF_ISOLATED_ENV=true`；远程目标需 `PERF_CONFIRM_REMOTE=true`；standalone 非 0 库需 `PERF_CONFIRM_NON_DEFAULT_DB=true`。
- 禁止命令：`FLUSHDB`、`FLUSHALL`、`KEYS`、`DEBUG`、`CONFIG`；扫描只用 `SCAN`，Cluster 逐 primary。
- 清理范围：
  - manifest 推导的本命名空间会话/run/上下文/记忆/transcript/附件登记；
  - 本命名空间 `perf:bg:*`（分批 + 多趟 SCAN，直到无残留）；
  - 本会话的 guard/pending（可 `PERF_KEEP_GUARD=true` 保留）、`ai:purge:retry:due` 成员、`ai:runs` 成员；
  - 附件文件仅在 `PERF_ALLOW_FILE_DELETE=true` 且路径**严格位于** `PERF_APP_FILE_STORAGE_PATH` 下的普通文件时删除；存储根目录本身、越界路径、目录/符号链接一律拒绝，不进入删除。
  - 反馈行通过真实 `POST /api/ai/feedback`（rating=0）删除。
- `cleanup` 默认 dry-run，输出精确键数、样本、背景采样、文件、反馈清单；`--execute` 才删除。
- 附件场景若未配置 `PERF_APP_FILE_STORAGE_PATH` / `PERF_ALLOW_FILE_DELETE=true`，`verify` 会把文件存在性记为 `unknown`，`cleanup` 会因“文件不可确认/仍存在”报 `complete=false`；这是有意为之，不伪报干净。
- 不会删除：`ai:runs` / `ai:purge:retry:due` 键本身、其他会话、业务作业/任务/配置、KB 缓存、`ai:lock:task:*`。

---

## 10. 故障排查

| 现象 | 排查 |
| --- | --- |
| `redis_mode_matches_env` fail | 工具连到了另一种拓扑：检查 `REDIS_MODE` / `REDIS_CLUSTER_NODES`；Cluster 必须 db 0 |
| `cluster_primary_connectivity` fail / SCAN 失败 | 至少一个 primary 不可达或拓扑读不出；工具拒绝用部分节点结果继续 verify/cleanup，先修节点/通告地址 |
| `app_identity_registered` fail | 身份不在 `/api/ai/tenants`；改用 `1/101` 等目录内组合 |
| `app_identity_headers` 400 | 请求头名/值被代理改写；检查 `APP_TENANT_HEADER`/`APP_USER_HEADER` |
| DELETE 503 | 应用 Redis 不可用；看 `/api/ai/health`、应用日志 |
| `unsupported`（附件/反馈） | 应用不可达或上传被拒；先 `doctor`，再用 `PERF_SCENARIOS` 只选该场景单独排查 |
| 附件上传 400 | 需要 SUSPENDED run + `generative_form` PENDING + 表单含 attachment 字段；工具已按此生成，若仍拒绝看应用日志 |
| 删除耗时远超预期 | 先看 `bg_*` 与 `staging_*`/`archive_*`；两趟全库 SCAN 是固定成本，Cluster 逐 master 串行 |
| `complete=false` | 看 `deferred`/`errors`：活跃 run、Redis/DB/文件失败；修好后再次 DELETE 或等 retry runner |
| verify `unknown` | Redis/HTTP 读失败；先恢复依赖再重跑 verify，不能当通过 |
| cleanup 仍有 exact 残留 | 读写竞争（retry runner/真实写者）；加 `PERF_VERIFY_SETTLE_SECONDS` 或停写后重跑；必要时 `PERF_KEEP_GUARD` 先保留 guard |
| Windows 终端中文乱码 | 用 UTF-8 终端（`chcp 65001`）或重定向到文件；输出文件本身是 UTF-8 |
| CROSSSLOT 类错误 | 工具所有多键脚本/DEL 都按同 tag；若出现，检查 runId 是否被改成含 `{`/`}` |

---

## 11. 已知限制 / unsupported

| 项 | 状态 | 原因 |
| --- | --- | --- |
| `POST /api/ai/chat`、`/confirm`、`/frontend-tool-result` | **unsupported** | 需要模型/桩，且会执行真实业务工具；本工具不驱动模型 |
| `POST /api/ai/runs/{runId}/resume` | **unsupported** | 会驱动模型续跑；只测只读的 `GET /runs/{id}` 与 `events?probe=1` |
| `POST /api/ai/cancel/{runId}` | 不作为测量项 | 会改写 run 取消事实；只测 `GET /cancel/{runId}` 状态 |
| KB reload | 可选 | 本地 Lucene 重建，不涉及模型；需 `PERF_ENABLE_KB_RELOAD=true`；不测 Redis 会话面 |
| KB 缓存键 | 不在删除面 | 全局共享，不属于会话销毁 |
| H2 反馈行 | 仅经真实接口 | 不直连数据库；列表上限 100 时无法证明“不存在”，verify 记 info |
| 附件 | 需要真实上传 | 依赖 SUSPENDED + generative_form；文件路径核对需要 `PERF_APP_FILE_STORAGE_PATH` |
| 100 万背景键 | 显式开启 | 会显著改变 keyspace，必须专用实例；清理为多趟 SCAN，耗时可观 |
| Cluster 节点发现 | 依赖 `CLUSTER NODES` 通告地址 | NAT/容器/证书主机名不匹配时需 `REDIS_TLS_INSECURE` 或修正通告 |
| 终帧探测漏帧 | 记录不判定 | 源码 6256 预算 + seq 跳号；删除后 Stream 已不在，无法事后复查 |
| 多实例并发 DELETE 单飞 | 无 | 源码没有记录级单飞；`concurrent_delete` 正是测量这一点 |
| 运行环境 | Python 3.9+ | 无第三方 CLI/数据库驱动依赖 |

---

## 12. 最小验收清单

1. `doctor` / `preflight` 无 fail、无 unknown。
2. `generate` 无 `failed`；HTTP 依赖场景不是 `unsupported`。
3. `measure` 的 `failed_requests=0`（传输失败与 health 之外的非 2xx 都为 0；health 503 计 `allowed_http`）。
4. `verify` 无 fail、无 unknown；`complete=true` 的场景键面无残留；`complete=false` 的场景 13 键完整保留。
5. `cleanup --execute` 后 `complete=true`；没有本命名空间残留。
6. 同一矩阵在 standalone 与 cluster 各跑一次，输出目录分开，比较 `summary.csv` 与 `verify.csv`。
