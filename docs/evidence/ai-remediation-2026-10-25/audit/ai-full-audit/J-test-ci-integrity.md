> 来源=<仓外证据>/ai-full-audit/J-test-ci-integrity.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# J｜测试与 CI 可信度审计（AI 全链路只读验收）

- 审计对象（固定快照）：`<仓外证据>/ai-full-audit/tree` = `ae883fb` + 审计开始时未提交补丁（`working.patch`，SHA256 `6c88b47d…dab955`）。
- 审计方式：只读。未修改任何产品源码/测试/配置/工作流/历史；未启动服务、未跑真实拓扑、未触 8081/5202、未读写密钥、未派子 agent。
- 报告位置：本文件（仓外）。命令均为只读：`gh run view/list`、`git show/log`、文件读取。
- 结论口径：只给「可证明正确（带条件）/ 缺陷 / 风险 / 未验证」四档，不做绝对保证。

---

## 0. 必须先讲清的两个边界（否则后面的所有"绿"都会被误读）

### 0.1 证据只到 `ae883fb`，补丁零验证

快照 = `ae883fb`（提交）+ `working.patch`（未提交）。但本项目所有 CI/集群证据都产生于 `ae883fb` **不含补丁**的树上：

- 集群网 `40174291244` 的 `headSha = ae883fb`，跑的是提交树；
- 提交门禁 `40174289141` 的 `headSha = ae883fb`，同上；
- 真机 E2E `40171918873` 的 `headSha = 20c56bb`（更早）。

`working.patch` 改的正是 `ConfirmGate`、`SpToolCallingManager`、`SseChatEmitter`、`JobProgressService/JobService`、`FileController`、`ReadinessService`（新增 `ShutdownDrainGate`）、`parts.ts`，并新增 6 个测试文件（5 个后端 + 1 个前端：`ParkToolStartOrderConformanceTest`、`SseChatEmitterWakeFloorTest`、`FileDownloadByteIdentityTest`、`ReadinessDrainWindowTest`、`JobWatchdogItemConvergenceTest`、`frontend/tests/park-request-tool-start-order.spec.ts`）。

**判定：补丁修复的内容在本次审计中属于「未验证」**——没有一次 CI/集群/真机运行覆盖它。补丁的注释里引用的 run（如 `SseChatEmitter:875` 引「三实例 streaming-kill 实测 lastSeq=2 先收到 seq=1」、`ReadinessDrainWindowTest` 引 `40173316364`）都发生在补丁之前，属"证据先行、补丁后置"。

### 0.2 "35 用例" 是历史口径，实际规模是 160+

`ci.yml:225` 作业名写「E2E 全流程（35 用例 · 桩上游 + redis）」，但 `verify-e2e.ps1` 实际执行 TC1–TC23 + GF1–GF6 + E2E-CTX-1..4 + 批① 130 条（`verify-e2e.ps1:5507` 的 MANIFEST 断言 `130`，`5517-5530`）。名称只是标签，但会让读面板的人低估覆盖面与耗时（见 F-01）。

---

## 1. 覆盖清单

### 1.1 本域文件 × 执行入口 × 最近证据

| 文件 | 行数 | 触发方式 | 能否真跑 ≥3 JVM | 最近一次证据 | 结论 |
|---|---:|---|---|---|---|
| `.github/workflows/ci.yml` | 584 | push/PR → main | 否（单 JVM） | `40174289141` @ae883fb：**cancelled**（e2e 超时）→ summary 红 | 门禁在 HEAD 红；130 条 AI 用例只跑了 17 条 |
| `.github/workflows/cluster-3node.yml` | 109 | **仅 workflow_dispatch** | 是（A/B/C + H2 TCP + HAProxy + 1 个 Redis） | `40174291244` @ae883fb：9 PASS / 7 FAIL | 真实拓扑跑得起来，但**从未绿过**（4/4 全红） |
| `.github/workflows/e2e-real.yml` | 261 | 仅 workflow_dispatch | 单 JVM（MDO 档为双 JVM） | `40171918873` @20c56bb：real 8 passed / 1 failed；stub 作业 **skipped** | UI 桩全量最近一次绿是 `40011022921`@5b468f3（10-23），HEAD 无证据 |
| `.github/workflows/nightly.yml` | 418 | schedule + dispatch | 否 | 最近 `40131155913`@d66ab7c **failure**（10-24 20:44Z）；上一次 `40026235999` success | 全量兜底；testmap 棘轮阈值 40；HEAD 未跑（每日一次 cron） |
| `.github/workflows/redis-5-smoke.yml` | 66 | 仅 workflow_dispatch | **否（不起 JVM）** | 仅环境级 | 只证 Redis 5.0.14 语义，不证产品 |
| `scripts/ci/cluster3-run.sh` | 531 | 由 cluster-3node 调用 | 是 | 同上 | HAProxy `roundrobin`、无 stick-table，静态自证 53 项 |
| `scripts/ci/cluster3-probe.mjs` | 3978 | 同上 | 是 | 同上 | 16 条用例全部直接打产品端点，无 SKIP，断言到 seq/台账/字节级 |
| `scripts/ci/stub-upstream.mjs` + `stub-routes.json` | 2122 / 24 路由 | ci.yml / cluster3 / e2e-real | — | `--self-check` 退出码（CI 步骤） | 桩，非模型；文件头已如实声明边界 |
| `scripts/verify-e2e.ps1` | 5725 | ci.yml / nightly | 否 | `40174289141` | API 级断言强（帧序/台账/字节），但无 per-case 超时 |
| `scripts/redis5-cluster-smoke.sh` | 1257 | redis-5-smoke | 否 | — | 纯 redis-cli + 源码 grep；产品 `available/503` 自认未验 |
| `scripts/ci/forbid-redis-cluster-unsafe.mjs` | ~200 | ci.yml（无条件） | — | 在 `965e6ef` **真实抓到违规**（redis-guard 红），`ae883fb` 转绿 | 守卫有效（非空转） |
| `scripts/ci/changed-files.mjs` | ~300 | ci.yml changes job | — | `40174289141` 输出 backend/frontend/e2e=true | 规则表有自检；frontend 改动**不触发 e2e 面** |
| 后端 AI 测试 | 327 个文件 | `mvn test` | 否 | `40174289141` backend 绿（4 分钟） | 16 个 `@SpringBootTest`，153 个含 Mockito、42 个用 Fake Redis |
| 前端 vitest | 139 文件 / 1511 例 | `yarn test` | 否 | `40174289141` frontend 绿 | jsdom；AI 面板只在 store/util 层 |
| 前端 Playwright | 278 个 `test()`（9 套 config） | 仅 dispatch | 部分（MDO 双 JVM） | 见上 | 真浏览器断言 DOM；但不在任何提交门禁里 |

### 1.2 明确未覆盖 / 未执行（不许静默略过）

1. **`application-cluster.yml`（原生 Redis Cluster profile）无任何运行时验证**。只有 `ClusterProfileConfigTest`（`@ApplicationContextRunner` 做属性绑定，`ClusterProfileConfigTest.java:44`），不建立连接、不跑槽重定向。
2. **`application-postgresql.yml` 在 CI 里零覆盖**：`.github/workflows` 与 `scripts/` 中 `grep -rn postgres` 无命中。生产库形态（共享 DB）从未在 CI 跑过。
3. **Redis Cluster 真实拓扑 × 产品链路 = 0**：`redis5-cluster-smoke.sh` 不起 JVM；集群网与 E2E 全用单机 Redis（见 F-08）。
4. **3 JVM 网的 `cold-start-3` / `suspend-cross-instance` / `shared-file-download` / `suspend-kill-a` / `streaming-kill-a` / `job-kill-a` / `readiness-removal` 在 HEAD 是红的**（`40174291244`），且补丁未验证。
5. **`verify-e2e.ps1` 的 130 条 AI 用例在 HEAD 只执行了 17 条**（H-01 起停顿，见 F-01）。
6. **前端 Playwright 9 套 config 在 HEAD 无任何运行**；且由 `changed-files.mjs:75-79` 的 `frontend` 规则可知，纯 `frontend/**` 改动**不会触发 e2e 面**。
7. **多用户隔离不可测**：产品无认证/无用户概念（`docs/02-需求设计文档-v2.2-冻结版.md:33-40` DC-10），AI 端点无主体判定（见 F-14）。
8. **`GET /api/ai/runs`（全量轮次列表）、`GET /api/ai/pending/{runId}`** 无专属测试（测试源码 grep 无命中）。
9. **永久 skip**：`frontend/e2e/17-unlock.spec.ts:677`（E2E-R18）、`frontend/e2e/16-kqc.spec.ts:61`、`16-kqc-b:41`、`16-kqc-c:68`、`10-srp:164`（条件 skip）。
10. **`scripts/b23l1-dual-instance.ps1`、`scripts/chat-panel-smoke.mjs`、`scripts/m2t2a-smoke.mjs`** 是人工 harness，不进任何 workflow（本次不以其为证据）。
11. **39/130 条 AI 用例标 P2，P2 红不阻塞退出码**（`verify-e2e.ps1:5723-5725`）——这 39 条即使真的坏了，门禁仍可能绿。

---

## 2. 逐链分析

口径：后端一律以「Redis Cluster + 共享 DB/文件 + ≥3 JVM + 同一对话请求可无粘滞落不同实例」为前提评估；前端以「多页签并行 + 多用户隔离」为前提评估。**凡与该前提不符的验证，一律标"前提不成立"而不是"通过"**。

### 链 1｜对话主链 `POST /api/ai/chat` → SSE

- 入口：`AiController.java:299`。
- 身份/session/run：请求体只带 `sessionId`（客户端自生成），**无用户身份**；`SessionGate` 以 `sessionId` 做跨实例单值门（`SessionGate.java:19-33`）。
- 共享状态/外部调用：Redis（run 快照/事件归档/租约/取消标志）+ 上游模型（桩或真模型）+ 工具执行（DB/文件）。
- 正常分支：`verify-e2e.ps1` S-01（1 start / ≥1 delta / 1 done / done 后零帧）在桩下为**真实链路**（真 JVM + 真 Redis + HTTP SSE），断言强度合格。
- 异常分支：S-03（响应头挂起）、S-04（帧间静默）、S-06（上游断流）、S-07（5xx 退避）、S-13（64KB 帧）——均为桩注入的**确定性故障**，属真实行为，但故障注入由被测方以外的脚本提供（可接受）。
- 重连/取消/恢复：S-05（lastSeq 差量）、P-14（停止后台账一致）、M-08（退避有界）。
- 用户可见结果：正文与帧序有断言；**不验证模型内容正确性**（桩回复是固定文本/表单）。
- 判定：**可证明正确（有条件）**——在「桩上游 + 单 JVM + 单机 Redis」条件下，产品对帧协议与失败姿态的处理被真实执行过。**不证明**多实例、不证明真模型服从率。
- 该链在 HEAD 的门禁状态：**未验证**（H-01 停顿导致后续未跑；见 F-01）。

### 链 2｜重挂/补帧 `GET /api/ai/events/{runId}?lastSeq=`

- 入口：`AiController.java:740`；park 收流判据 `AiController.java:770-779`（`stillWaitingForInput`）。
- 关键契约（产品侧明文）：**仍有 PENDING 条目 ⇒ 回放完即收流**；条目已结算 ⇒ 保持连接等终帧。
- 单实例：S-05/M-09/W-36/W-38 覆盖「不重不漏、heartbeat 不重放、孤儿过滤」——真实。
- 多实例：`cluster3-probe.mjs` 的 `reattach-any-instance`（B 全量/B 差量逐 seq 相等）、`seq-cross-instance` 通过；`streaming-kill-a` 失败于「接管补帧起始 seq 必须 > kill 前 lastSeq：期望 ≥3，实得 1」（`40174291244`、`40173316364` 两次同签名）⇒ **击杀后重挂从 seq=1 全量重放**，客户端会看到重复正文。
- 补丁状态：`working.patch` 在 `SseChatEmitter` 增 `sub.wakeFloor = max(..., lastSeq)`（patch 内 `@@ -867,6 +875,13`），声称修复该现象；**未验证**。
- 判定：**缺陷（已证实于 ae883fb，补丁未验证）**。
- 用例设计缺陷：`suspend-cross-instance` 在**决策之前**打开 B/C 的 `lastSeq` 连接，再要求同一连接收到 `done`（`cluster3-probe.mjs:2157-2175`）。这与上面的 park 收流契约直接冲突（打开时条目仍 PENDING ⇒ 回放后即收流）。该条 FAIL 是**假红**（见 F-10）。

### 链 3｜决策 `POST /api/ai/confirm`、`POST /api/ai/frontend-tool-result`

- 入口：`AiController.java:524`、`:590`。
- 身份：只校验 `runId + toolCallId`，**无主体**。任何拿到这两个 id 的客户端都能批准一次写操作。
- 共享状态：幂等索引 `ai:idem:{run:<runId>}`（探针直接 `HGET` 校验，`cluster3-probe.mjs:2235-2238`）、决策原子写、台账。
- 正常：单实例 H-01/H-02/H-03/H-04 断言到「台账 outcome=ok + executedBy 落名 + 作业真创建」——这是**真实执行**，不是 mock。
- 并发/幂等：跨实例「重复决策 409 + 并发双决策恰一胜」在 `idempotency-cross-instance` 中通过（`40174291244` PASS），且有 Redis 侧幂等索引证据。
- 异常：H-06（预检失败不进闸门）、G-04（复核拒绝 400 + issues）、G-10/G-11（条件必填/闭集）。
- 多实例接管：`suspend-kill-a` FAIL（「接管后 tool_start 恰一次：期望 1，实得 0」，`40174291244`；`40173316364` 为「流结束但未命中等待条件，已收=confirm_decision」）⇒ **接管连接确实收到了 `done`（终帧），但随后到 C 的全量回放里该 toolCallId 的 `tool_start` 为 0 帧**；用例在 `probe:2717` 处中止，因此**台账/作业断言未执行**（不能据此说"执行成功"）。用户侧表现是卡片可能不显示/不可提交。
- 判定：**缺陷（已证实）+ 补丁未验证**（`working.patch` 的 `ConfirmGate/SpToolCallingManager` 改动与新增 `ParkToolStartOrderConformanceTest` 指向同一根因：park 批把 `frontend_tool_request` 提前到 `tool_start` 之前）。

### 链 4｜取消 `POST /api/ai/cancel/{runId}`

- 入口：`AiController.java:855`（另有 `GET :886` 读状态）。
- 身份：无。任何知道 runId 的人可取消别人的轮次。
- 覆盖：P-14（runs/pending/turn_end 同向 CANCELLED）、W-29/W-33/W-34（挂起中停止）、M-08（退避）、`CancelLedgerConformanceTest`。
- 多实例：取消权威态写 `ai:cancel:<runId>`（Redis），跨实例生效；集群网用 SIGSTOP 冻结 A 来构造「取消处理不了的窗口」，验证 B 侧 deferred（`session-purge-cross-instance` PASS）。
- 判定：**可证明正确（单机/双实例，条件：Redis 可用）**；`W-29` 在 `965e6ef` 曾红（20s 内未进 CANCELLED，现值 DONE），`ae883fb` 已绿。


### 链 5｜历史 `GET /api/ai/history/{sessionId}`、`/messages?afterSeq|beforeSeq`

- 入口：`AiController.java:1251`、`:1284`。
- 身份：**仅 sessionId**，无归属校验 ⇒ 知道 sessionId 即可读全部对话内容。
- 单实例：`history-pagination` 用 21 轮 × 2 条 = 42 条真实消息，断言 `(seq, runId, messageId)` 三元组逐条同源、afterSeq 跨 B/C 翻页无重不漏、beforeSeq 逆序回卷、同传 400、旧 `/history` 仍 40 窗口（`cluster3-probe.mjs:1967-2101`）。**这是本域最强的断言之一**，且是跨实例真读。
- 会话不串：`session-purge-cross-instance` 断言「删目标会话后另一会话历史完整、业务 task 不受影响」；TC18 断言「会话记忆隔离」。
- 缺口：**没有 N 个会话并发的交叉污染测试**（多个 sessionId 同时跑、断言彼此不混）。现有隔离只证明「单删不影响另一个」。
- 判定：**可证明正确（跨实例读历史，条件：H2 共享库 + 单机 Redis）**；并发隔离为**未验证**。

### 链 6｜会话销毁 `DELETE /api/ai/session/{sessionId}`

- 入口：`AiSessionPurgeController.java:78`。
- 覆盖：跨实例「B 删 → C 验无残留（旧 history / 完整 history / runs/current 均为空）」+「在途 deferred + 自动重试清 pending + 旧 sessionId 再发 410 SESSION_PURGED」+「业务 task 保留」（`cluster3-probe.mjs:2275-2418`，PASS）。
- 实现面：`RedisClusterScanSupport`（patch 内新增）逐 master SCAN，`RedisClusterScanSupportTest` 用它证「单连接 scan 会漏键」。**但测试用 `PurgeFakeRedis`，不是真集群**（`RedisClusterScanSupportTest.java:18-27`）。
- 判定：**可证明正确（单机 Redis）**；**Cluster 拓扑下未验证**（多 master 逐节点扫描只在 fake 上验证过）。

### 链 7｜配置热更新（`/api/ai/settings/defaults`、`/api/ai/models`、providers CRUD）

- 入口：`AiConfigController.java:75-206`。
- 跨实例：`config-hot-reload` 断言 A 写入 → B/C 在 20s 内看到 `version` 递增 + `temperature/providerId/modelId` 三元组一致，且**进程 pid 不变**（非重启生效），并校验 Redis `ai:cfg:ver` 递增（PASS）。
- 身份：`PUT /api/ai/settings/defaults` 与 `POST/PUT/DELETE /api/ai/providers/**` **无任何鉴权**；单测 `AiConfigWriteEndpointTest` 也只测校验规则。
- 判定：**可证明正确（热更新语义，单机 Redis）**；写入口的访问控制为**风险**（见 F-14）。

### 链 8｜共享文件上传/下载

- 入口：`FileController.java:285`（`/files/download?codes=`）。
- 跨实例：`shared-file-download` 在 A 上传 → B/C/代理下载并要求 sha256 相等。**FAIL**：`40174291244` 期望 `f03f5b…` 实得 `24577e…`；`40173316364` 期望 `4172fd…` 实得 `2207a8…`。
- 根因（代码可证）：快照树里该方法已改为「单编码回原字节、多编码才 zip」（`FileController.java:285-305`），而 `ae883fb` 上「一律 buildZip」⇒ 单编码下载是**再包一层的 zip**，响应头却仍写 xlsx ⇒ 用户下载到的 xlsx 打不开。
- 判定：**缺陷（已证实于 ae883fb，补丁已改但未验证）**；新增 `FileDownloadByteIdentityTest` 为真实 H2 + 真存储 + 真 multipart，属正确补证。

### 链 9｜AI 工具的作业副作用 + 看门狗

- 入口：`JobController`（`/tasks/{id}/jobs`、`/jobs/{id}`）+ `JobWatchdog`。
- 多实例：`job-kill-a` 期望「作业中 kill A → 幸存实例看门狗 130s 内判 FAILED → 不自动重排队 → 重启清理死 runner 键 → B 重试 COMPLETED」。
- 实测：`40174291244` / `40173316364` 均 FAIL 于「被中断作业残留 RUNNING/PENDING 条目数：期望 0，实得 1（`CURRENCY/RUNNING/processed=1/total=5`）」。
- 用户可见：作业详情长期显示「1/5 进行中」而作业已是 FAILED（自相矛盾）。
- 补丁：`JobProgressService/JobService` 改动 + `JobWatchdogItemConvergenceTest`（真 H2、真 Bean、两条真实判据驱动 `watchdog.scan()`）——补证方式正确；**未验证**。
- 判定：**缺陷（已证实）**。

### 链 10｜readiness 摘流 / 停机 / Redis 故障恢复

- 入口：`GET /health/ready` + HAProxy `option httpchk GET /health/ready`（`cluster3-run.sh:114-115`）。
- 无 key 不摘流：`readiness-removal` 第①段通过（`/api/ai/health` 503 但 `/health/ready` 200，A 仍在轮询里）——**这是正确的分层**，可证明。
- SIGTERM draining：**FAIL**（`40174291244` 与 `40173316364` 同签名）：SIGTERM 后 15s 内捕获不到 `ready=false/draining=true`，观测全是 `ECONNREFUSED`。即「先摘流、后停服」只存在于日志里。
- 用户可见：滚动发布/缩容时在途 SSE 被硬切，用户看到答案中断且无重连窗口。
- 补丁：`ShutdownDrainGate` + `ReadinessDrainWindowTest`（真实 Spring 事件总线 + `LOWEST_PRECEDENCE` 监听器代表连接器关闭；测试自述「不绑真实端口」）——**未验证**。
- Redis 故障：`redis-outage-recovery` PASS（暂停单机 Redis → `/api/ai/health` 200 但 `redis.available=false`、`leaseOk=false`、`/health/ready` 仍 200、`checks.redis=DOWN`、`/chat` 与 `/events` 503 `AI_REDIS_UNAVAILABLE`、业务 `/api/definitions` 直连与代理均 200、恢复后经代理对话完成）。
- 判定：readiness/draining 为**缺陷（已证实）**；Redis 降级为**可证明正确（条件：单机 Redis + `docker pause`，不是主从切换）**。

### 链 11｜前端面板：帧渲染、多页签、刷新恢复

- 入口：SSE 帧 → `frontend/src/utils/parts.ts` → 面板渲染；页签所有权在 `frontend/src/stores/ai.ts`（Web Locks + BroadcastChannel + sessionStorage）。
- 单测（vitest/jsdom）：`parts.ts` 帧序防御（`frontend/tests/park-request-tool-start-order.spec.ts`，新增、未提交）、页签所有权生命周期（`tab-ownership-lifecycle.spec.ts`，用 `FakeLockManager`/`FakeBroadcastChannel` 桩）、`concurrency-matrix.spec.ts`（双击发送/双连接接管，全部 `vi.mock('@/api/ai')`）。
- 真机（Playwright）：多页签只在 NFEZUI 域真跑过（`13-nfezui.spec.ts:155-235` N07/N08/N09：复制页签换新会话、不双跑、原页签不受影响），以及 `10-srp.spec.ts:807`/`:1007` 的「关页签重开 + 同 sessionId」。
- 但：Playwright 只在 `workflow_dispatch` 下跑（`e2e-real.yml:13-14`），且 `changed-files.mjs:75-79` 的 `frontend` 规则**只开 frontend 面**，纯前端改动不会跑任何 Playwright ⇒ `parts.ts` 这类 UI 状态缺陷在提交门禁里只有 vitest 兜底。
- 判定：多页签**可证明正确（条件：jsdom 桩 + NFEZUI 域真机；最近一次真机绿是 5b468f3）**；HEAD 上**未验证**。

### 链 12｜真模型服从率 / 生成式表单回灌

- 入口：`frontend/e2e/11b-hgta.spec.ts:487`（G-01，N=10、阈值 ≥7）。
- `40171918873` 结果：**8 passed / 1 failed**；失败项 `[G-01] N=10 触发=0 阈值≥7`，耗时 15.1 分钟。
- 归因问题：该用例的「触发」判据是「页面上出现**带 `gf-submit` 的活卡**」（`11b-hgta.spec.ts:510-528`）。若后端把 `frontend_tool_request` 发在 `tool_start` 之前，`parts.ts` 的 `tool_start` 分支会把已建立的等动作卡复位成只读（这正是补丁 `parts.ts` 与新增单测要修的），于是 `gf-submit` 不存在 ⇒ **被计成「模型没触发」**。
- 也就是说：这条指标把「UI/帧序缺陷」与「模型不服从」混为一谈，本次 run 的「模型 0/10 触发」更可能是产品侧卡片被复位（补丁作者自己也这么归因）。这是**归因错误**，不是"模型太差"。
- 另外：真模型 run 只跑 9 条（`--grep @real`，`e2e-real.yml:250`），一次 dispatch、一次采样，`retries: 0`（`playwright.config.ts:57`）——没有复跑，样本极薄。
- 判定：真模型面**未验证**（证据不足）；G-01 的**缺陷（已证实）**是「park 帧序 + 前端复位」，不是模型问题。

---

## 3. 测试可信度：各层各自能证明什么

### 3.1 分层表

| 层 | 代表 | 真实执行的部分 | 不能证明的部分 | 本次判定 |
|---|---|---|---|---|
| 后端单测（Mockito） | 153 个文件（`mock(` 且无 `@SpringBootTest`） | 类内逻辑（帧序、闸门、租约状态机） | 装配、Redis 语义、跨进程、真实 socket | 只作**回归锁**，不作生产验证 |
| 后端 Spring 上下文 | 16 个 `@SpringBootTest`（含新增 4 个） | 真 H2 + 真 Bean + 真迁移 | 真 Redis / 真 PG / 多 JVM | **有效集成证据（单进程）** |
| 后端 Fake Redis | 42 个文件（`FakeRedis`/`FakeFrameRedis`/`FakeLeaseRedis`/`PurgeFakeRedis`） | 脚本语义、键族、Lua 前置条件 | Cluster 槽/重定向/多 master 拓扑 | 只证「代码以为自己在做什么」 |
| API 级 E2E（`verify-e2e.ps1`） | 真 JVM + 真 Redis(单机) + 桩上游 | HTTP/SSE 全链路、台账、作业、历史、字节 | 模型服从率、多实例、UI 渲染 | **本域最强证据**，但只覆盖单实例；HEAD 未跑完 |
| 3 JVM 集群网 | `cluster3-probe.mjs` | 3 JVM + H2 TCP + HAProxy + Redis(单机) + 真 kill/pause | Redis Cluster、PostgreSQL、真实模型 | **证据有效但结论是红的**（9 PASS/7 FAIL） |
| 真机 UI（Playwright） | 278 `test()`，9 套 config | Chromium 真 DOM、真 SSE、真多页签 | 只在 dispatch；HEAD 未跑 | 历史绿（5b468f3）不能外推到 HEAD |
| 真模型 | `@real` 14 条 | 真模型 + 真 UI | 样本 10、单次、无复跑 | 1 次 8/9 通过 + 1 次归因错误 |

### 3.2 是否存在「只断言 mock / 只取 200 / 测试自己造答案」

- **不是只取 200**：`verify-e2e.ps1` 的 H/G/T/W 域断言到「台账 `outcome=ok`+`executedBy` 落名」「作业行真产生」「pending 六态逐项可读」「issues[].key 点亮」「附件一次性消费」；集群网断言到 `(seq, runId, messageId)` 三元组、`sha256`、Redis `HGET` 幂等索引、HAProxy 日志同端口三节点。这一层的断言质量是合格的。
- **有一处「测试自己造答案」**：`Gf-Assert-Echo`（`verify-e2e.ps1:1537`）比对的是「脚本刚 POST 上去的 JSON」与「产品回显」，闭环在产品内部，只能证明「存回来的就是提交的」，不能证明语义正确。属可接受的窄断言，但不能当"表单正确性"证据。
- **有一处桩自证**：`stub-upstream.mjs --self-check` 校验路由表与 `GenerativeFormRules` 白名单对账（`cluster3-probe.mjs:3834-3843`）。它证明「桩的 schema 合法」，不证明「产品能处理任意合法 schema」。文件头已如实写明（`stub-upstream.mjs:13-16`）。
- **假绿面（P2 不阻塞）**：130 条里 39 条 P2，红不退出（`verify-e2e.ps1:5723-5725`）。这 39 条对门禁是"观察项"，不是"保证项"。
- **假绿面（job 级 skip）**：`ci.yml:98/162/237` 的三面 `if` 会让 backend/frontend/e2e 报 `success`（GitHub 语义），仅靠 `summary` 补说明；`summary` 自身已加「输出为空即红」防线（`ci.yml:570-576`），这一点设计是对的。

### 3.3 时序/重试水分（逐条给"能不能掩盖真实缺陷"）

| 位置 | 重试/容差 | 可掩盖什么 | 本次是否已掩盖 |
|---|---|---|---|
| `cluster3-probe.mjs:1542-1553` `fullReplayToTerminal` | 4 次 × 500ms | 「快照已终态、done 未落档」的窄窗（4 次内补上就算过） | 否（`streaming-kill-a` 仍红），但存在 |
| `cluster3-probe.mjs:2757-2772` `forceZombieResume` | 5 次 × 5s（25s） | 僵尸扫描延迟（>25s 才算失败） | 否 |
| `cluster3-probe.mjs:3303-3318` Redis 恢复后对话 | 3 次 × 2s | 恢复初期的瞬时 503 | 否 |
| `cluster3-probe.mjs:1811-1814` 代理分布 | 每节点 8–12 / 30 次 | 轻微偏斜（不区分 roundrobin 与 leastconn） | 是（不证顺序） |
| `cluster3-probe.mjs:1850-1852` 同连接 6 次 | 每节点 ≥2 | 6 次里若顺序是 a,b,c,a,b,c 或 a,a,b,b,c,c 都过 | 是（**不证 A→B→C 顺序**） |
| `verify-e2e.ps1:5723-5725` | P2 红不阻塞 | 39 条 P2 回归 | 是（门禁可绿） |
| `verify-e2e.ps1:117` `HttpTimeoutMs` | 360s/次读，无 per-case 上限 | 单条用例可吃掉整个 job 预算 | **是（已发生，见 F-01）** |

### 3.4 静默跳过

- `cluster3-probe.mjs` 结果集合被自证钉死为 `['PASS','FAIL']`（`cluster3-probe.mjs:79`、`:3757-3758`），**无 SKIP 通道** —— 这是好设计。
- `verify-e2e.ps1` 的 GF 域有 SKIP 通道，但桩模式下「SKIP≥1 即整棒 FAIL」（`:5720`），且批①130 条把 `SKIP:*` 强制改判 FAIL（`:5488-5492`）—— 也是好设计。
- 真机 Playwright 有 5 处 `test.skip`（其中 `17-unlock:677` 是永久 skip，理由是"缺稳定抢发面"）。属**已知缺口**，不是隐藏。

---

## 4. 发现清单

> 确定性分档：**已证实缺陷**（有运行/代码直接证据）/ **具体风险**（代码或证据可推出，但未在该路径复现）/ **仅缺测试**（无证据表明坏，只是没有验证）。

### F-01｜[Critical] 提交门禁的 E2E 在 HEAD 无法完成，130 条 AI 用例只跑了 17 条

- 确定性：**已证实缺陷**（门禁可信度缺陷，非产品缺陷）。
- path:line：`.github/workflows/ci.yml:225-237`（`timeout-minutes: 25`）、`scripts/verify-e2e.ps1:117`（单次读窗口 360s）、`scripts/verify-e2e.ps1:5481-5506`（用例循环无 per-case 超时）。
- 触发步骤：push `ae883fb` → `ci.yml` → e2e job。
- 证据：run `40174289141`。E2E step 07:56:11 起跑；最后一条用例输出是 07:59:23 `[PASS] AIE2E-M-09`；此后**20 分钟无任何输出**；08:19:30 `##[error]The operation was canceled.`，job 记 `cancelled`，`summary` 如实报红（`E2E_RESULT: cancelled`）。按注册顺序，M-09 的下一条是 `H-01`（`verify-e2e.ps1:3126`，单卡批准→真实执行）。
- 对照：同一脚本在上一提交 `965e6ef`（run `40173314741`）**5 分钟跑完 130 条**，`H-01` 1.8s 通过。⇒ 停顿是 `ae883fb` 引入的回归（现象已证实；根因未定位，因为脚本没有 per-case 日志）。
- 用户影响：门禁红 ≠ "AI 用例失败"，而是"用例没跑完"；后续 ~110 条（H 全域、G/T/K/Q/C/D/X/W 全域）在 HEAD **没有任何执行证据**。另外单条用例最多可吃掉 `maxSuspend+1` × 360s：全套健康时约 13 分钟（`40131155913`@d66ab7c），**25 分钟预算本身够用**——真问题是「单用例失控没有上限」，一次就把预算吃光，且失败形态与"用例失败"混在同一个 `cancelled` 里。
- 对应测试/缺失：缺失 = per-case 超时/看门狗 + 用例耗时预算 + "e2e job 超时视为基础设施红"的独立信号（现在与用例失败混在同一个 `cancelled` 里）。

### F-02｜[Critical] 三实例真实拓扑从未绿过；HEAD 上 9 PASS / 7 FAIL，其中 6 条可复现

- 确定性：**已证实缺陷**（harness 可用 + 产品红）。
- path:line：`.github/workflows/cluster-3node.yml:43-109`；`scripts/ci/cluster3-run.sh:454-502`；`scripts/ci/cluster3-probe.mjs:1615-1632`（16 条用例）。
- 触发步骤：`gh workflow run cluster-3node.yml`（4 次：`2ed8f95`、`20c56bb`、`965e6ef`、`ae883fb`）。
- 证据：4/4 全 `failure`。`40174291244`（ae883fb）= PASS 9 / FAIL 7；`40173316364`（965e6ef）= PASS 10 / FAIL 6。**6 条 FAIL 在两次 run 同签名**（shared-file-download、suspend-kill-a、streaming-kill-a、job-kill-a、readiness-removal、suspend-cross-instance）⇒ 不是偶发。
- 用户影响：多实例部署的 5 条核心承诺（共享文件、接管执行、接管补帧、作业收敛、摘流）在 HEAD 无一条被证明成立。
- 说明：这套 harness 本身设计是可信的（真 3 JVM / 真 HAProxy / 真 kill / 无 SKIP / 逐用例证据落盘），所以"红"是产品结论，不是测试噪声。

### F-03｜[Critical] 共享文件「单编码下载」返回再包一层的 zip（字节不符）

- 确定性：**已证实缺陷**（run 证据 + 代码路径）。
- path:line：`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:285`（快照树已修为单文件回原字节；`ae883fb` 为 `buildZip` 一律打包）。
- 触发步骤：`/api/tasks/{id}/files/upload` 上传 CURRENCY.xlsx → `GET /api/tasks/{id}/files/download?codes=CURRENCY`（B 实例）。
- 证据：`40174291244` 期望 `f03f5b14…` 实得 `24577ee7…`；`40173316364` 期望 `4172fd12…` 实得 `2207a8f8…`。响应头仍声明 `application/vnd.openxmlformats…sheet`。
- 用户影响：多实例下任意实例下载到的 xlsx 都是坏文件（外层是 zip），按字节校验/按类型解析都会错。
- 对应测试：`cluster3-probe.mjs:2587-2642`（真上传/真下载/sha256）— 有效；补丁新增 `FileDownloadByteIdentityTest`（真 H2 + 真存储）— 补证正确但**未跑过**。

### F-04｜[Critical] 看门狗判死作业后 `job_items` 残留 RUNNING

- 确定性：**已证实缺陷**。
- path:line：`scripts/ci/cluster3-probe.mjs:2866-2946`（用例）；产品侧 `JobProgressService#startItem`（REQUIRES_NEW 独立提交）+ `JobWatchdog` 只收敛作业行。
- 触发步骤：A 起 EXPORT 作业 → 等 `RUNNING + progress>=1` → `SIGKILL A` → 等 B 侧看门狗判 FAILED → 读 `/api/jobs/{id}`。
- 证据：两次 run 均「残留 RUNNING/PENDING 条目数 期望 0 实得 1：`{"id":5,"jobId":5,"defCode":"CURRENCY","status":"RUNNING","processed":1,"total":5}`」。
- 用户影响：任务中心长期显示「1/5 进行中」，与 FAILED 终态自相矛盾；用户会以为作业还能继续。
- 对应测试：补丁 `JobWatchdogItemConvergenceTest`（真 H2、真 Bean，两条真实判据驱动 `watchdog.scan()`）— 方向正确，**未跑过**。

### F-05｜[Critical] SIGTERM 后没有可观测的 draining 窗口（连接被直接掐断）

- 确定性：**已证实缺陷**。
- path:line：`scripts/ci/cluster3-probe.mjs:2980-3195`（用例）；产品侧 `ReadinessService` + Spring graceful shutdown。
- 触发步骤：A 上起在途慢流 → `SIGTERM A` → 用预热 keep-alive 连接 50ms 轮询 `/health/ready`。
- 证据：两次 run 均「15s 内未捕获 `ready=false/draining=true`」，观测尾部全是 `connect ECONNREFUSED 127.0.0.1:23181`（14.6s 起）。
- 用户影响：滚动发布/缩容时在途 AI 回答被硬切，LB 没有窗口把实例摘掉 ⇒ 用户看到"回答突然消失"。
- 对应测试：补丁 `ShutdownDrainGate` + `ReadinessDrainWindowTest`（真实 Spring 事件总线，但**不绑真实端口**，自述"Tomcat 真实 socket 关闭时刻由三实例 Action 覆盖"）— 需要集群网复跑才能闭环，**当前未验证**。

### F-06｜[Critical] 跨实例接管后重挂从 `seq=1` 全量重放（补帧回卷）

- 确定性：**已证实缺陷**。
- path:line：`scripts/ci/cluster3-probe.mjs:2809-2827`（断言 `seqs[0] >= lastSeq+1`）；产品侧 `SseChatEmitter#stageReplay`。
- 触发步骤：A 上流式跑 → 拿 `lastSeq=2` → `SIGKILL A` → 等僵尸扫描接管 → `GET /events/{runId}?lastSeq=2`。
- 证据：两次 run 均「接管补帧起始 seq 必须 > kill 前 lastSeq：期望 ≥3，实得 1」。
- 用户影响：击杀/接管后，客户端重挂会再收到已看过的 1..2 帧 ⇒ 正文重复、卡片重复；与"seq 无洞"的承诺相反（是多、不是少）。
- 对应测试：补丁 `SseChatEmitter` 的 `wakeFloor = max(maxReadSeq, lastSeq)`（patch `@@ -867,6 +875,13`）+ `SseChatEmitterWakeFloorTest`（新增、纯单测）— **未验证**。

### F-07｜[Critical] 三 JVM 并发冷启动存在 Flyway baseline 竞态（2/3 次失败）

- 确定性：**已证实缺陷（间歇，可复现 2/3）**。
- path:line：`backend/src/main/resources/application.yml:64-67`（`flyway.enabled=true` + `baseline-on-migrate=true`）；用例 `scripts/ci/cluster3-probe.mjs:1728-1765`。
- 触发步骤：清空共享 H2 → 三实例**同时**启动（`startAllConcurrently`）。
- 证据：`40174291244`「实例 c 启动失败（exit=1）：Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty」；`40171916232`（20c56bb）更严重——并发失败后**顺序基线也起不来**，退出码 2，16 条用例只跑了 1 条；`40173316364`（965e6ef）该用例 PASS（24s）。
- 用户影响：同时扩容/同时重启时，至少一个实例启动失败；若顺序启动也撞上，整批不可用。
- 对应测试：**无**。补丁也未触及 Flyway（`working.patch` 内 `grep flyway` 无命中）。属"已证实缺陷 + 无修复 + 无测试"。

### F-08｜[Critical] 「Redis Cluster 真实拓扑」在产品链路上从未被验证

- 确定性：**已证实覆盖缺口**（不是缺陷，但推翻大量"已验"表述）。
- path:line：
  - `backend/src/main/resources/application-cluster.yml:29`（`nodes: ${AI_REDIS_CLUSTER_NODES}`）——只有 `ClusterProfileConfigTest.java:44` 的属性绑定测试；
  - `scripts/ci/cluster3-run.sh:355-374`（起的是**单机** `redis:7.2.16`，`-p 23179:6379`，无 `--cluster-enabled`）；
  - `scripts/ci/cluster3-probe.mjs:932`（profile = `h2tcp`）、`:937-938`（`spring.data.redis.host/port`，单节点）；
  - `.github/workflows/ci.yml:240-249`、`nightly.yml:152-161`、`e2e-real.yml:37-45`（`services.redis` 均为单机）；
  - `frontend/e2e/support/harness.ts:357`（`--spring.data.redis.database=${redisDb}`）、`:222`（`FLUSHDB`）——**这两项在 Cluster 下都是非法操作**；
  - `scripts/redis5-cluster-smoke.sh`（3 主 3 从）**不启动 JVM**，只做 redis-cli 语义 + 源码 grep；文件头自认「产品 RedisAvailability.available / 503 口径未验」。
- 触发步骤：无——因为没有任何 workflow 会同时具备「真 Cluster」+「产品 JVM」。
- 用户影响：若生产是 3 主 3 从 Cluster（`application-cluster.yml` 注释明确这么写），本报告里所有"跨实例/会话销毁/配置热更新/限流"的绿都只对单机 Redis 成立；`SCAN` 跨 master、hash tag 同槽、MOVED/ASK、主从切换均未在产品链路上执行过。
- 对应测试：`RedisClusterScanSupportTest`（fake）、`ClusterSlotTest`、`RunMigrationFamilyContractTest`、`ClusterProfileConfigTest` 全部是**离线/属性级**。缺失 = 一条「Redis Cluster（至少 3 主）+ 产品 JVM」的 workflow。

### F-09｜[Important] 「双 JVM 同库」的 MDO 域其实不共享 DB / 文件，且依赖 Redis 库号
- 确定性：**已证实（配置事实）**。
- path:line：`frontend/playwright.mdo.config.ts:19-24`（两个实例同 `E2E_REDIS_DB=12`）；`frontend/e2e/support/harness.ts:53-54`（`data` / `data-degraded` **两个 H2 目录**）。
- 触发步骤：`npx playwright test --config playwright.mdo.config.ts`（`e2e-real.yml:136-139`）。
- 用户影响：该域能证明「跨 JVM 读 Redis 侧轮次台账/扇出/决策」，**不能**证明「共享 DB 的事务/行锁/唯一约束/文件可见性」；而后者恰恰是 3 JVM 网里 `cold-start`/`job-kill` 的失败面。
- 对应测试：15-mdo 的 M 域 11 条 — 结论只能写成"Redis 共享面通过"。

### F-10｜[Important] `suspend-cross-instance` 的期望与产品 park 收流契约冲突（假红）
- 确定性：**可证明测试期望错误**。
- path:line：`scripts/ci/cluster3-probe.mjs:2157-2175`（决策**前**打开 B/C 连接，之后要求同一连接收 `done`）vs `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:770-779`（SUSPENDED 且仍有 PENDING ⇒ 回放完即 `completeSubscriptions()`）；配套测试 `AiControllerEventsParkCloseTest.java:46-56` 已把该契约写成"本批口径"。
- 触发步骤：任一集群网 run 的 `suspend-cross-instance`。
- 证据：两次 run 同签名「等待终帧：流已结束（已收=tool_start）」。
- 用户影响：这条 FAIL 会被当成产品缺陷派工，实际是 harness 时序错误——**审计/派工噪声**，并掩盖了该用例真正想证的「决策后两条连接都能收到续跑终帧」（正确时序应是「先 confirm 200，再 GET /events」）。
- 对应测试：应改为「confirm 之后重挂」，或断言"决策前连接按 park 收流"。

### F-11｜[Important] 真模型 G-01 把「卡片被复位成只读」计成「模型未触发」（归因错误）
- 确定性：**具体风险（高置信，代码 + 日志双证）**。
- path:line：`frontend/e2e/11b-hgta.spec.ts:487-556`，判据在 `:510-528`（只认带 `gf-submit` 的活卡）。
- 触发步骤：`e2e-real.yml` suite=real → `npx playwright test --grep @real`。
- 证据：`40171918873` 日志 `[G-01] N=10 触发=0 阈值≥7`；同 run 其余 8 条真模型用例通过；补丁的 `ParkToolStartOrderConformanceTest` 与 `frontend/tests/park-request-tool-start-order.spec.ts` 明确写「请求帧先于 tool_start ⇒ 卡被复位成只读、永远提交不了」。
- 用户影响：真模型覆盖被误判为"模型不听话"，掩盖产品帧序缺陷；反过来修好帧序后，这条指标也不能区分"模型不愿出表单"。
- 对应测试：G-01 需把「已收到 `frontend_tool_request` 帧」与「卡片可提交」拆成两个指标。

### F-12｜[Important] 前端 UI 层没有任何提交门禁（纯前端改动不触发 e2e 面）
- 确定性：**已证实（配置事实）**。
- path:line：`scripts/ci/changed-files.mjs:75-79`（`frontend` 规则只置 `frontend: true`）；`ci.yml:162`；`e2e-real.yml:13-14`。
- 触发步骤：只改 `frontend/src/utils/parts.ts` → push。
- 证据：`ci.yml:186-189` 只有 `yarn typecheck / test / build`；本次补丁的 `parts.ts` 修复唯一防线是新增的 vitest 用例；最近一次 UI 桩全绿是 `40011022921`@`5b468f3`（10-23）。
- 用户影响：帧序复位、多页签接管、滚动跟随这类**只在浏览器暴露**的缺陷在提交门禁里没有真浏览器验证。

### F-13｜[Important] 集群网的多处"宽松窗口"可掩盖缺陷（并已掩盖一项）
- 确定性：**具体风险**（部分已发生）。
- path:line：见 3.3 表；关键两处 `cluster3-probe.mjs:1811-1814`（8–12 容差）、`:1850-1852`（每节点 ≥2，不断顺序）。
- 触发步骤：任何代理偏斜/顺序错乱的实现。
- 用户影响：`balance leastconn` 或"部分粘滞"也可能通过"无粘滞"用例；`A→B→C` 的**顺序**（题目要求"强制 A→B→C"）没有被断言。
- 对应测试：应断言 6 次请求的节点序列等于 `[a,b,c,a,b,c]`。

### F-14｜[Critical] 多用户隔离在产品里不存在，且 AI 写/读端点全部匿名
- 确定性：**已证实缺陷（架构事实）**；影响取决于部署假设。
- path:line：
  - 无 Spring Security 依赖（`backend/pom.xml` 无 `spring-security`），`backend/src/main/java/com/example/configmgr/config/WebConfig.java:13-36` 只注册 CORS + 安全响应头，无鉴权过滤器；
  - `AiController.java:524`（confirm）、`:740`（events）、`:855`（cancel）、`:894/:918`（startup-resume / scan）、`:934`（runs 全量列表）、`:1072`（runs/{id}/resume）、`:1251/:1284`（history）、`:1550`（context）、`:1917`（pending）、`:1933/:1984`（feedback）——均无主体校验；
  - `AiConfigController.java:104-206`（provider/model/settings 写）——同上；
  - `AiRateLimiter.java:31-34` 自述「sessionId 由客户端自生，故它只拦同一个页签被脚本连发」。
- 触发步骤：拿到任意 `sessionId` 或 `runId` → 直接 `GET /api/ai/history/{sessionId}` / `POST /api/ai/confirm` / `POST /api/ai/startup-resume/scan`。
- 用户影响：若同一实例对内网多人开放，任一用户可读他人对话、批准他人待执行的写操作（start_publish 等）、取消他人轮次、改写全局模型配置。`docs/02-需求设计文档-v2.2-冻结版.md:33-40` 已声明 demo 无认证、限内网受信环境——**这是已声明的取舍**；但"多用户隔离"作为验收项则**不成立**，任何"多用户已覆盖"的表述都是错的。
- 对应测试：**无，且不可能有**（不存在用户维度）。

### F-15｜[Important] 多页签真机覆盖只在一个域、且不在门禁里
- 确定性：**仅缺测试/覆盖不足**。
- path:line：`frontend/e2e/13-nfezui.spec.ts:155-235`（N07/N08/N09 真机双页签）；`frontend/tests/tab-ownership-lifecycle.spec.ts` + `tab-ownership-fixtures.ts:10-60`（`FakeLockManager`/`FakeBroadcastChannel`）；`frontend/tests/concurrency-matrix.spec.ts`（全部 `vi.mock('@/api/ai')`）。
- 触发步骤：无（NFEZUI 域只在 `e2e-real.yml` stub 档第 3 步跑，最近一次真机绿为 5b468f3）。
- 用户影响：真实浏览器的 Web Locks `ifAvailable`、bfcache `pageshow.persisted`、跨页签 `BroadcastChannel` 时序只在 NFEZUI 域验证过一次；主栈的"刷新/关页签重开"（`10-srp` R11/R13）是单页签重开，不是并行双页签。
- 对应测试：缺失 = 主栈"双页签并行 + 同会话 + 一方停止/一方重挂"真机用例。

### F-16｜[Important] 补丁（含 6 个新测试）零 CI 验证，而它覆盖了本轮 6 条红中的 5 条
- 确定性：**已证实（证据边界）**。
- path:line：`<仓外证据>/ai-full-audit/tree-status.txt`（`M` 9 个产品文件、`??` 6 个测试文件）、`<仓外证据>/ai-full-audit/working.patch`。
- 触发步骤：无。
- 用户影响：所有"已修复"结论只停留在代码与单测意图层面；集群网最近一次运行（`40174291244`）跑的仍是**未修复**的 ae883fb。补丁提交并至少重跑一次 cluster-3node 之前，"多实例已收敛"不可写。
- 对应测试：新增 5 个后端测试 + 1 个前端测试尚未进入任何 run。

### F-17｜[Minor] `verify-e2e.ps1` 的"从产品键派生超时"实际是硬编码 300
- 确定性：**已证实（代码与注释不一致）**。
- path:line：`scripts/verify-e2e.ps1:112-118`（注释说"根源键 = `app.ai.resilience.total-budget`"，代码 `$script:TurnBudgetSeconds = 300`）。
- 触发步骤：修改 `application.yml` 的 `total-budget`。
- 用户影响：产品预算调大后脚本仍按 360s 判超时 ⇒ 新假红。

### F-18｜[Minor] 作业名/脚本头「35 用例」与实际 160+ 不一致
- 确定性：**已证实（文档口径）**。
- path:line：`ci.yml:225`、`scripts/verify-e2e.ps1:2570-2572`、`:5507`。
- 用户影响：按"35 条"设的 25 分钟预算正是 F-01 的诱因之一。

### F-19｜[Minor] 运维端点无测试：`GET /api/ai/runs`、`GET /api/ai/pending/{runId}`
- 确定性：**仅缺测试**。
- path:line：`AiController.java:934`、`:1917`；测试源码 grep 无命中。
- 用户影响：排障面无回归锁，且两端点匿名可读（见 F-14）。

### F-20｜[Minor] 永久/条件 skip 未登记为门禁缺口
- 确定性：**仅缺测试**。
- path:line：`frontend/e2e/17-unlock.spec.ts:677`、`16-kqc.spec.ts:61`、`16-kqc-b.spec.ts:41`、`16-kqc-c.spec.ts:68`、`10-srp.spec.ts:164`。
- 用户影响：解锁域"重挂横幅生命周期"等场景永久无自动化，且不进任何计数。

---

## 5. 三个 run 的证据边界（逐条划死）

### 5.1 `40174291244` —— cluster-3node @ `ae883fb`（workflow_dispatch）

| 项 | 内容 |
|---|---|
| 结论 | **failure**；探针自报 `PASS 9 / FAIL 7`，exit=1 |
| 真跑的东西 | 3 个 JVM（`--spring.profiles.active=h2tcp`）、1 个 H2 TCP 进程（共享库）、1 个**单机** Redis 7.2.16、1 个 HAProxy 2.9（host 网络、roundrobin、无 stick-table）、桩上游 18399/23199 |
| 真做过的事 | 真 SIGKILL A、真 SIGSTOP/SIGCONT、真 SIGTERM、真 `docker pause` Redis、真 multipart 上传/下载、真 HAProxy stats/log、21 轮历史分页 |
| **不能证明** | ① Redis Cluster 拓扑（单机）② PostgreSQL（H2）③ 真模型（桩）④ 补丁有效性（树是 ae883fb）⑤ 多用户（无身份）⑥ 集群主从切换（只 pause，不 kill）⑦ 文件共享的跨机语义（同一 runner 本地目录） |
| 额外边界 | 自证 53 项 PASS 只证脚本/配置形状；`preflight` 通过不代表用例通过 |
| 可以引用的事实 | `cold-start-3` 红（Flyway 竞态）、`shared-file-download` 红（字节不符）、`suspend-kill-a` 红（tool_start 缺失）、`streaming-kill-a` 红（seq 回卷）、`job-kill-a` 红（item 残留）、`readiness-removal` 红（无 draining）、`suspend-cross-instance` 红（**harness 期望错误**，见 F-10） |

### 5.2 `40174289141` —— ci.yml @ `ae883fb`（push）

| job | 结果 | 说明 |
|---|---|---|
| changes | success | 14 文件；`backend,scripts` 命中三面全 true |
| backend | success | `mvn test` 全量（4 分钟） |
| frontend | success | typecheck + vitest + build（3 分钟） |
| **e2e** | **cancelled** | 07:56:11 起、07:59:23 后无输出、08:19:30 被取消（25 分钟超时）；只跑到 `M-09`（AIE2E 累计 17 条），下一条 `H-01` 停住 |
| docs / scan-history / redis-guard | success | 与 AI 链路无关（redis-guard 有间接价值：它挡住了 Cluster 非法命令） |
| summary | **failure** | 如实把 `cancelled` 记红 |

- **边界**：这次 run 不能作为"AI 主链路绿"的证据；它能证明的是「TC1–TC23、GF1–GF6、CTX-1..4、AIE2E-S/P/M 共 17 条在单实例桩环境下通过」。
- 同一分支上一提交 `965e6ef`（`40173314741`）e2e **5 分钟跑完 130 条**，但有 **33 条红**（29 条 AIE2E + GF4/GF5/GF6/TC23；其中 W 域 21 条集中报「done 帧 0 个」，与 `AiController` 的 park 收流缺陷同源，`ae883fb` 的 `AiControllerEventsParkCloseTest` 正是它的回归位）；`ae883fb` 把这些修绿了，却在 `H-01` 停住。两次对照说明"红/绿"的形态在提交间剧烈变化，而门禁没有 per-case 诊断能力（F-01）。

**第三个数据点（夜间兜底）**：`40131155913`@d66ab7c（10-24 20:44Z）把同一套 165 条（35 + 130）**在 13 分钟内跑完**，结果 `PASS=165 / FAIL=1`，唯一红是 `AIE2E-W-21`（同样报「done 帧 0 个」）。三点连起来看：① 健康时全套约 13 分钟，25 分钟预算**本来是够的**；② 「done 缺失」是一族反复出现的系统性缺陷（d66ab7c 1 条 → 965e6ef 33 条 → ae883fb 修掉但换成 H-01 停顿）；③ 夜间兜底（唯一无变更集跳过的全量载体）在最近一次运行里**也是红的**。

### 5.3 `40171918873` —— e2e-real @ `20c56bb`（workflow_dispatch，suite=real）

| 项 | 内容 |
|---|---|
| 结论 | **failure**；real 作业 `8 passed / 1 failed`（20.7 分钟） |
| 真跑的东西 | 真模型（Environment `e2e-real` 注入）+ 真后端 + 真 Chromium + 真 Redis（单机） |
| 覆盖的 9 条 | RM01、RM02（工具轮采样 ≥7/10）、S15、**G01（失败）**、X07、K10、C13、Q02r、V14r |
| **不能证明** | ① UI 桩全量（stub 作业 **skipped**）② 多页签/VL/NFEZUI/MDO/KQC 域 ③ 多用户 ④ 集群/多实例 ⑤ 补丁有效性（20c56bb 早于 ae883fb）⑥ 稳定性（单次、无复跑） |
| 可引用的事实 | 生成式表单「回灌续跑」在真模型 + 真 UI 下 **10/10 采样未出现可提交的卡**；其余 8 条真模型用例通过 |

- 对照：`40011022921`（10-23，`5b468f3`）是最近一次 **UI 桩 9 域全绿 + real 全绿**；`40006599835`/`40000263888` 的 stub 域红。也就是说 UI 域上一次全绿距今已跨两个功能提交。

---

## 6. 本域"可证明正确（带条件）"的清单（避免只报坏消息）

以下结论有真实执行证据，但**条件必须随结论一起写**：

1. **帧协议与失败姿态**（单 JVM + 桩上游 + 单机 Redis）：`start/delta/done` 唯一性与顺序、`done` 后零帧、64KB 帧、上游断流/5xx 退避、首包看门狗、lastSeq 差量不重不漏、heartbeat 不重放。证据：`verify-e2e.ps1` S/P/M 域 + `40173314741`（该 run 上 S-09、S-11 红，已在 `ae883fb` 转绿）。
2. **HITL 决策语义**（单 JVM）：批准真执行（台账 `outcome=ok` + `executedBy` + 作业行）、拒绝零执行、重复决策 409、多卡逐卡收敛、预检失败不进闸门。证据：H-01..H-11，**在 `965e6ef`（run `40173314741`）全绿**；HEAD（`ae883fb`）上 H-01 未跑完，故该结论**不可外推到 HEAD**。
3. **生成式表单 schema/回灌**（桩）：七型/联动/optionsFrom/defaultFrom/长文本/附件一次性/结构化 issues。证据：GF1–GF6 + G 域。
4. **跨实例读历史与翻页**（3 JVM + H2 + 单机 Redis）：42 条消息三元组同源、双向游标无重不漏、旧 40 窗口不变。证据：`history-pagination` PASS（两次 run）。
5. **跨实例幂等**（3 JVM）：重复决策 409 + 并发双决策恰一胜 + Redis 幂等索引存在 + 副作用恰一次。证据：`idempotency-cross-instance` PASS。
6. **跨实例配置热更新**（3 JVM）：版本推进、三元组一致、pid 不变、Redis `ai:cfg:ver` 递增。证据：`config-hot-reload` PASS。
7. **Redis 降级不摘流**（3 JVM）：`/health/ready` 仍 200、`checks.redis=DOWN`、AI 端点 503 `AI_REDIS_UNAVAILABLE`、业务面直连与代理均 200、恢复后经代理完成一轮。证据：`redis-outage-recovery` PASS。
8. **无 key 不摘流**（能力面与就绪面分层）：`/api/ai/health` 503 但 `/health/ready` 200、代理不摘 A。证据：`readiness-removal` 第①段 PASS。
9. **会话销毁跨实例**（3 JVM）：无残留、在途 deferred、自动重试清 pending、旧 session 410、业务 task 保留。证据：`session-purge-cross-instance` PASS。
10. **多页签所有权**（jsdom 桩 + NFEZUI 真机 @5b468f3）：复制页签换新会话、不双跑、不删原会话。

---

## 7. 结论摘要（给指挥官的 6 句话）

1. **测试"量"不是问题，"可执行性"是问题**：HEAD 上提交门禁的 E2E 在 `H-01` 停 20 分钟被 25 分钟超时取消，130 条 AI 用例只跑了 17 条（F-01）；门禁红的原因是"没跑完"，不是"跑失败"（上一提交是另一种极端：跑完了但 33 条红）。
2. **唯一能真跑 ≥3 JVM 的网（cluster-3node）从未绿过（4/4 红）**，HEAD 上 9 PASS/7 FAIL = **6 条产品缺陷 + 1 条 harness 假红**：产品侧为文件双 zip、job item 残留、无 draining、接管 seq 回卷、接管缺 tool_start（这 5 条在两次 run 同签名）+ 三 JVM 并发冷启动的 Flyway 竞态（间歇，2/3 次失败）；假红为 `suspend-cross-instance`（harness 期望与产品 park 契约冲突）（F-02～F-07、F-10）。
3. **"Redis Cluster + 共享 DB + 无粘滞"这三个前提里，只有"无粘滞"在单机 Redis 上被真验过**：没有任何 workflow 让产品跑在真 Cluster 上；共享 DB 用的是 H2（生产 PostgreSQL 零 CI 覆盖）；E2E harness 自身还依赖 `spring.data.redis.database` + `FLUSHDB`（Cluster 下非法）（F-08/F-09）。
4. **补丁零验证**：`working.patch` 修了本轮 6 条红中的 5 条，但所有证据（含最近一次集群网）都跑在未打补丁的 `ae883fb` 上；补丁自带 6 个新测试从未进入任何 run（F-16）。
5. **多用户隔离不是"缺测试"，是产品没有这个维度**：无认证、无用户、无归属校验，`sessionId`/`runId` 即能力；`confirm`/`history`/`cancel`/`settings`/`providers` 全匿名（F-14）。多页签则只有 NFEZUI 域一次真机绿 + jsdom 桩，且都不在提交门禁（F-15/F-12）。
6. **真模型证据最薄且有一条归因错误**：一次 dispatch、9 条、无复跑；G-01 的「10/10 未触发」实际上是卡片被 `tool_start` 复位成只读（产品缺陷），被计成"模型不服从"（F-11）。

### 若要补到"能对外声称已验"的最小集合（具体动作，不泛泛）

1. **先修门禁可执行性**：给 `verify-e2e.ps1` 的每条用例加 per-case 超时（建议 ≤ 2×`total-budget` 且整棒预算写入 job 前），把 `ci.yml:227` 的 25 分钟按实测重设；`H-01` 停顿作为独立缺陷单（它会让整条 AI 主链路无 CI 证据）。
2. **提交补丁后立刻重跑 cluster-3node**，并把 `suspend-cross-instance` 改成「confirm 之后再重挂」（否则它会一直假红）。
3. **新增一条 workflow：3 主 3 从 Redis Cluster + 3 JVM + 共享 PostgreSQL**（可用 compose 或 service 容器），只跑 AI 关键链（chat/events/confirm/purge/config/limit），作为 Cluster 面的唯一真证据源。
4. **把 UI 层 e2e 接进提交门禁的"前端面"**（至少主栈切片 + 双页签），或在 `changed-files.mjs` 里让 `frontend/**` 触发 e2e 面。
5. **G-01 指标拆分**：`frontend_tool_request` 到达率（产品）与「卡可提交」（产品）分开计数，模型"未调用工具"只由"无工具帧"判定。
