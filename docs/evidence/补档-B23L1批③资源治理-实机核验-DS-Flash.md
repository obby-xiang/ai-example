# 补档 · B23L1 批③ 资源治理实机核验（双实例 harness · DS-Flash，2026-10-21）

- 核验范围：设计稿 `docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md` §5-2（双实例 PoC harness）+ §5-4（P95 帧延迟）+ §2.3/§2.4（L1 与 UNKNOWN 首跳的实机面）
- 形态：`scripts/b23l1-dual-instance.ps1` —— 两个实例（8081/8082）共享同一 Redis（`127.0.0.1:6379`）+ 桩上游（18399，`AI_API_KEY=b23l1-stub-placeholder`），脚本自起自等自收自停；批③新增 `-SharedDb`（两实例同一 H2 文件、**绝对路径** + `AUTO_SERVER=TRUE`）
- 证据目录（仓内 `target/` 不入库，本件只摘录关键行）：
  - `backend/target/b23l1-artifacts-b3`（批③ 首跑，PASS=6/7）
  - `backend/target/b23l1-artifacts-b3r2`（批③ 二跑，PASS=7/7）
  - `backend/target/b23l1-artifacts-b3final`（终验，PASS=7/7）
  - `backend/target/b23l1-artifacts-b3shared{,2,3}`（`-SharedDb` 三跑）
- 自证：核验方自证行以实机核验链原始报告为准；本补档由提交官转录（`agentId=agent-1336`），不代签

## 0. 运行史

### 0.1 默认用例面（七条，七跑史）

| 跑 | 结果 | 现象/根因 | 修法 |
| --- | --- | --- | --- |
| 一跑（`b23l1-artifacts-b3`） | **PASS=6/7** | 唯一红 = `instance-kill`：断言仍按批② 写"B 接管后台账 1 条 `executedBy=B`"，而批③ 的执行链在目标校验处**先拦**（B 的 H2 库里没有该任务 ⇒ `blocked/TARGET_INVALID`）——这是**行为变迁**，不是缺陷 | 断言改"**两形态皆可**"并打印实际形态：甲（共享库）= 台账 1 条 `executedBy=B`；乙（默认 H2 隔离，L-12）= 全量回放该 `toolCallId` 有 `TARGET_INVALID / blocked / executed=false` |
| 二跑（`b23l1-artifacts-b3r2`） | **PASS=7/7** | — | — |
| 终验（`b23l1-artifacts-b3final`） | **PASS=7/7** | 见 §1/§2 | 收口后复跑（M-1/M-3 已落） |

> 首跑红态原文（`b3final` 同形证据）：`kill-B-full.sse.txt` 中 `"code":"TARGET_INVALID"`、`"status":"BLOCKED"`、`"executed":false`。这正是 L-12（跨实例 H2 隔离边界）在批③ 之后的表现形态 —— 批② 时它表现为 `RESOURCE_NOT_FOUND`。

### 0.2 `-SharedDb` 用例面（两条，三跑史）

| 跑 | 结果 | 现象/根因 | 修法 |
| --- | --- | --- | --- |
| 一跑（`b3shared`） | 红 | **桩指纹冲突**：跨实例变体同一 taskId 的两条消息文本相同 ⇒ 桩按"会话指纹 = 首条 user 消息文本"判成同一会话，后到者因 `bucket.used` 已占 TC16-EXPORT 退化为文本轮、不弹确认卡 | 两条消息带**互异后缀**（同 taskId + 不同文本） |
| 二跑（`b3shared2`） | 红 | **终帧窗口**：后端 `finish()` 先 `save(DONE)` 再 `out.done`，且终态回放不挂订阅 ⇒ 恰在该窗口重挂会拿到"到 `message_end` 为止"的回放并直接收流 | 全量回放遇"无终帧"**短延迟重读**（最多 4 次，`<tag>-a1..a4.sse.txt` 留证）；4 次仍无才 FAIL（不掩盖真缺失） |
| 三跑（`b3shared3`） | **PASS=2/2** | 竞态**第 1 轮即命中** | 见 §3 |

## 1. 默认七条用例面（终验）

| # | 用例 | 断言 | 结果 |
| --- | --- | --- | --- |
| 1 | `reattach-full-replay`（批①） | B 侧从 0 回放逐帧同序同号 | PASS |
| 2 | `reattach-diff`（批①） | B 侧带 `?lastSeq` 只拿差量，不重不漏 | PASS |
| 3 | `suspend-reattach-wake`（批①） | A 挂起 → B 重挂 → A 批准确认 → 续跑帧经 `ai:wake:` 扇出到 B | PASS |
| 4 | `suspend-decision`（批②） | 决策打 B：`accepted=true` + `resumeOutcome=LEASE_HELD_ELSEWHERE`；`tool_start`/`tool_result` 各恰 1 帧 | PASS |
| 5 | `resource-lock`（批③，**H3 预置锁变体**） | 预置 `SET ai:lock:job:<taskId>:EXPORT 1\|other-instance PX 60000` → 批准后**必须**得到 `RESOURCE_LOCKED / BLOCKED / blocked / executed=false / retryable=true`；`DEL` 后同任务重跑必须 `ok`；终态后 `ai:lock:job:<taskId>:EXPORT` 不存在 | PASS |
| 6 | `idempotency`（批③） | 首轮执行后用同一 `runId+toolCallId` 打 B → **409 `DUPLICATE_TOOL_CALL_ID`**；台账仍 1 条、全量回放 `tool_start` 仍 1 帧、作业数仍 1；真 Redis `HGET ai:idem:<runId> <runId>:<toolCallId>` 非空 | PASS |
| 7 | `instance-kill`（批②，断言已适配批③） | 杀真 A ⇒ B 重建挂起轮；决策落 B 后租约过期由延迟重试接管（预算 90s）；`tool_start` 恰 1 帧、终帧到达；中间证据**按实际形态**校验（甲/乙） | PASS |

### 1.1 关键帧（`b3final`，逐字摘录，仅取字段）

`lock-A-blocked.sse.txt`（H3 预置锁 ⇒ 被挡）：

```
"type":"tool_result","toolCallId":"call-stub-7","name":"start_export","kind":"CONFIRM","ok":false,
"executed":false,"executedBy":null,"reused":false,"status":"BLOCKED","outcome":"blocked",
"code":"RESOURCE_LOCKED","retryable":true
```

`lock-A-ok-done.sse.txt`（`DEL` 解锁后重跑 ⇒ 执行）：

```
"type":"tool_result","toolCallId":"call-stub-9","name":"start_export","ok":true,"executed":true,
"reused":false,"status":"APPROVED","outcome":"ok","code":null,"retryable":false
```

> 这两帧是"锁真的在起作用"的最短证明：**同一条执行链、同一个工具**，唯一差别是 Redis 上有没有那把锁。

## 2. 延迟取证（L-5 延续）

`suspend-reattach-wake` 摘要打印 `帧延迟（A confirm 完成 → B 首续跑帧）：P50 / P95`：

| 样本 | P50 | P95 | 口径 |
| --- | --- | --- | --- |
| 批③ 一跑 | 139 ms | 139 ms | **单样本 ⇒ P50 = P95** |
| 批③ 二跑 | 108 ms | 108 ms | 同上 |
| 批③ 终验 | 106 ms | 106 ms | 同上 |

- 三样本 **139 / 108 / 106 ms**，与批①（94ms）/ 批②（107ms）同档 ⇒ **批③ 未在 emit 路径上新增同步往返**（资源锁、目标校验、幂等索引都发生在**执行前**，不在每帧上）。
- **不据此给性能结论**：每次运行每用例只采一个 confirm→首帧延迟，样本量小；设计稿 §5-4 的"±10% 不回退"仍需多轮样本。

## 3. `-SharedDb` 跨实例资源锁（`b3shared3`，竞态第 1 轮命中）

| 段 | 断言 | 实测 |
| --- | --- | --- |
| 同刻发令 | 两个新 run 同时 `start_export` **同一 taskId** | 两实例同刻发令（同一 H2 文件、`AUTO_SERVER=TRUE`） |
| 互斥 | **恰一个** `RESOURCE_LOCKED / blocked / executed=false`、**恰一个**真实执行 | 命中：`B` 侧 `code=RESOURCE_LOCKED, status=BLOCKED, executed=false`；`A` 侧 `outcome=ok, executed=true`（两帧相隔 **17ms**） |
| 台账 / 作业 | 台账 `start_export` **合计 1**、作业数 **1**、`locked=1 / executed=1 / ledgerSum=1 / jobs=1` | 全中 |
| 重试口径 | 3 轮上限，任一命中即 PASS | **第 1 轮**即命中（未用满 3 轮） |

关键帧（`lock-shared-{A,B}-r1-a1.sse.txt`，逐字摘录）：

```
A: "name":"start_export","ok":true,"executed":true,"status":"APPROVED","outcome":"ok","code":null,"target":{"taskId":1
B: "name":"start_export","ok":false,"executed":false,"status":"BLOCKED","outcome":"blocked","code":"RESOURCE_LOCKED","retryable":true,"target":{"taskId":1
```

> 口径（**不夸大**）：跨实例资源锁的**确定性**证据是 §1 的 H3 预置锁变体；本节是**竞态命中**的一次真实样本（第 1 轮命中），3 轮未命中时按设计判 SKIP（L-17：SKIP 与真故障同形，必须标注为"不完整证据"）。

## 4. `idempotency`（`b3final`）

| 段 | 断言 | 实测 |
| --- | --- | --- |
| 首轮 | 执行一次，台账 1 条、`tool_start` 1 帧 | `idem-A.sse.txt`：`tool_result ... ok=true, executed=true` |
| 重放 | 同一 `runId+toolCallId` 再打 B | **409 `DUPLICATE_TOOL_CALL_ID`**（HTTP 层，`ai:decided:`/pending 只读、不触任务库 ⇒ 不需要 `-SharedDb`） |
| 不变式 | 台账仍 1 条、全量回放 `tool_start` 仍 1 帧、作业数仍 1 | 全中 |
| 索引 | 真 Redis `HGET ai:idem:<runId> <runId>:<toolCallId>` 非空 | 非空 |

## 5. 附带观察（L-12 → 批③ 的新形态）

- **现象**：`instance-kill` 中 B 接管后 `start_export` 报 `blocked / TARGET_INVALID / executed=false`（默认 H2 隔离形态）。
- **根因（harness 边界，不是产品缺陷）**：两实例各用独立 H2 文件库，任务只在 A 的库里；B 接管后 `ToolTargetResolver` 在**执行前**就拦下（批③ 之前是执行到一半 `RESOURCE_NOT_FOUND`）。
- **对证据的影响**：**不影响租约/互斥证据** —— `tool_start`/`tool_result` 各恰一帧、终帧到达、租约接管路径（延迟重试）照常走通；`TARGET_INVALID` 恰好证明"执行确实换了主人、且新主人按设计拦在了执行前"。
- **去向**：L-12 措辞已由本批更新（`-SharedDb` 已提供共享库验证路径＝形态甲；默认 H2 隔离仍是边界）＋ **L-17**（SKIP/不完整证据口径）。

## 6. 未覆盖（诚实口径）

| 项 | 说明 |
| --- | --- |
| 跨实例资源锁的**精确时序** | 只有 H3 确定性 + 竞态至多一次命中，不是微秒级交错实验 |
| 跨实例数据一致性 | 见 L-12（默认 H2 隔离；`-SharedDb` 已是同一文件，但仍是"同机同文件"形态） |
| 三实例以上 | harness 是双实例形态；`ai:lock:*` 的 N 实例面靠"键 + TTL + owner"结构保证，未做三实例实跑 |
| UNKNOWN 首跳的实机面 | 首跳由单测覆盖（三跳各一例 + 前端不通道路径）；实机只验证了"台账 1 条 ⇒ 重放不重复执行"（`idempotency` 用例） |
| P95 统计显著性 | 见 §2（三样本，单样本 ⇒ P50=P95） |
