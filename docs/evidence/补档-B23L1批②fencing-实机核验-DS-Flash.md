# 补档 · B23L1 批② fencing 实机核验（双实例 harness · DS-Flash，2026-10-21）

- 核验范围：设计稿 `docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md` §5-2（双实例 PoC harness 五条，本批 = 其中三条）+ §5-4（P95 帧延迟，L-5 取证）
- 形态：`scripts/b23l1-dual-instance.ps1` —— 两个实例（8081/8082）共享同一 Redis（`127.0.0.1:6379`）+ 桩上游（18399，`AI_API_KEY=b23l1-stub-placeholder`），脚本自起自等自收自停
- 证据目录（仓内 `target/` 不入库，本件只摘录关键行）：
  - `backend/target/b23l1-artifacts-final`（PASS=6/6 的批②三用例证据：`dec-*` / `kill-*` / `wake-*`）
  - `backend/target/b23l1-artifacts-final2`（`lease-scripts` 证据：`lease-A.sse.txt` / `lease-B.sse.txt` / `lease-B-full.sse.txt`）
- 自证行（wire `profile.bind` 单行）：`agentId=agent-1319`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`

## 0. 运行史（七跑：五轮修 + 一轮基线 + 终验）

| 跑 | 结果 | 现象/根因 | 修法 |
| --- | --- | --- | --- |
| 一跑 | 立即失败 | `$PSScriptRoot` 为空（脚本被 `pwsh -File` 之外的形态加载）⇒ 仓根解析不出来 | 显式 `-RepoRoot` 参数绕过（脚本保留自动推断作为默认） |
| 二跑 | 桩路由不触发 | 挂起类用例直接 chat，桩按路由表要走"生成式表单/工具轮"，但没有任务与披露上下文 ⇒ 退化成文本轮；**附带发现** `Kill($true)` 重载在目标进程上不存在（静默失败，A 没死） | ①挂起类用例**先建真实任务 + `select-defs` + chat 带 `context`**（预检要求任务存在、`start_export` 才在披露清单里）；②停子进程改 `taskkill /T /F` + `StartTime` 比对（防误杀同 PID 复用） |
| 三跑 | B 侧订阅未就绪 | 决策已经落库、B 侧却还没订阅（`ai:wake:` 的订阅在连接建立后才发生）⇒ 唤醒没人收 | `Read-Sse` 加**空响应防御**；建任务响应加防御（区分"桩没给任务 id"与"任务建失败"） |
| 四跑 | 哨兵未写 | `Start-Job` 的 CWD 与主线程不同，`ArtifactDir` 又传的是**相对路径** ⇒ 哨兵写到作业自己的目录里，主线程永远轮询不到 | ①`ArtifactDir` 一律**绝对路径**；②`Wait-WakeReady` 轮询哨兵文件，并在作业 `State` 异常时**上报真实错误**（不再只报"超时"） |
| 五跑 | **PASS=3** | 批①三条基线（`reattach-full-replay` / `reattach-diff` / `suspend-reattach-wake`）在批②改动下**不回归** | — |
| 六跑 | **PASS=6**（批② jar 前） | 换批② jar 之前的三条批②用例已全绿 | — |
| 七跑（终验） | **PASS=6/6** + `lease-scripts` **PASS** | 见 §2 / §3 | created 修复后复跑 |

> "批② jar 前"的含义：六跑用的是批②a 存储层 + 部分门闸的中间 jar；换 jar 后（终验）**六条全绿 + 租约冒烟 PASS**，证明门闸接线没有把既有用例打红。

## 1. 六条用例面（终验）

| # | 用例 | 断言 | 结果 |
| --- | --- | --- | --- |
| 1 | `reattach-full-replay`（批①） | B 侧从 0 回放得到逐帧同序同号 | PASS |
| 2 | `reattach-diff`（批①） | B 侧带 `?lastSeq` 只拿差量，不重不漏 | PASS |
| 3 | `suspend-reattach-wake`（批①） | A 挂起 → B 重挂 → A 批准确认 → 续跑帧经 `ai:wake:` 扇出到 B 连接 | PASS |
| 4 | `suspend-decision`（批②） | 决策打 B：`accepted=true` 且 `resumeOutcome=LEASE_HELD_ELSEWHERE`（A 的等待方持租）；`tool_start`/`tool_result` 各恰 1 帧；A、B 双侧重挂都收到终帧 | PASS |
| 5 | `instance-kill`（批②） | 杀真 A ⇒ B 的 `/runs/current` 重建挂起轮；决策落 B 后租约过期由延迟重试接管（预算 90s）；`tool_start` 恰 1 帧、终帧到达、台账 `executedBy=B` 且 `ledgerCounts.start_export=1` | PASS |
| 6 | `suspend-decision-concurrent`（批② 可选） | 同一 `runId/toolCallId` 双路并发 POST：恰一个 200（accepted）+ 一个 409（`DUPLICATE_TOOL_CALL_ID`）；`confirm_decision`/`tool_start` 各恰 1 帧 | PASS |

### 1.1 延迟取证（L-5）

`suspend-reattach-wake` 摘要打印 `帧延迟（A confirm 完成 → B 首续跑帧）：P50 / P95`：

| 样本 | P50 | P95 | 口径 |
| --- | --- | --- | --- |
| 样本 1（批①运行批） | 94 ms | 94 ms | **单样本 ⇒ P50 = P95** |
| 样本 2（本批终验） | 107 ms | 107 ms | **单样本 ⇒ P50 = P95** |

- **L-5 销记依据 = 上述两个样本**（94ms / 107ms）。样本量少（每次运行每用例只采一个 confirm→首帧延迟），**不据此给性能结论**；设计稿 §5-4 的"±10% 不回退"口径仍需多轮样本，本件只声明"已取得两样本"。
- 与批① 的差别：批② 在 emit 路径上没有新增同步往返（租约只落在"谁驱动"这一层，不在每帧），延迟量级与批① 同档。

## 2. `lease-scripts`（真 Redis 租约冒烟，终止性用例）

| 段 | 断言 | 实测（原文摘录） |
| --- | --- | --- |
| ACQUIRE | 挂起期租约键存在，`/health.leaseOk=true` | 键在；`leaseOk=true` |
| RENEW | 等待 12s 后 `PTTL` 回升（续租真的发生） | PTTL 回升 |
| FENCED-SET | fence 键存在且快照 `runEpoch>0`、台账 `epoch>0` | **`fence=14, runEpoch=14, ledgerEpoch=14`**（三者相等 = 同一次写回把 fence、快照、台账推到同一任） |
| RELEASE | 终态后租约键消失 | 键消失 |
| 并发双 POST `/runs/{runId}/resume` | 恰一个 202、一个 409 | 202 + 409 |

关键帧（`lease-B.sse.txt`，逐字摘录，仅取字段）：

```
data:{"type":"confirm_decision",...,"decision":"approve","approved":true,"status":"APPROVED","reason":"b23l1 lease-scripts","waitedMs":0,"preflightHash":"47ea7bd8",...,"seq":5}

data:{"type":"tool_result",...,"ok":false,"executed":true,"executedBy":"instance-…","reused":false,"status":"APPROVED","outcome":"failed","code":"RESOURCE_NOT_FOUND",...,"seq":6}
```

## 3. 首跑 FAIL 的红态（created 修复的实证来源）

- **现象**：`lease-scripts` 首跑 FAIL —— 并发同主双抢，**败方**走"我还租"把**胜方**的租约删掉。
- **红态（真 Redis 实证）**：`ai:fence:run:<runId>` = `nil`；快照 `runEpoch=0`；台账 `epoch=0`（本应是 14）。
- **单测复现**：`ResumeServiceLeaseTest:302` 租约键被删成 `nil`。
- **修法**：所有权判据改 Lua 返回的 `|1`/`|0`（"谁创建谁释放，重入不释放"）+ 进程内清理改条件删除。
- **复跑**：§2 的四段全部命中，fence/runEpoch/ledgerEpoch 三者相等 = 14。

## 4. 附带观察（登记挂账 L-12）

- **现象**：`instance-kill` 里 B 接管后 `start_export` 报 `outcome=failed / code=RESOURCE_NOT_FOUND`（"任务不存在"），但 `executed=true`、`executedBy` = **B 的实例标识**、台账 `start_export` 计数 = 1。
- **根因（harness 边界，不是产品缺陷）**：两个实例各用**独立 H2 文件库**（`instance-a/` / `instance-b/` 各自的工作目录），任务只在 A 的库里；B 接管后查不到该任务。
- **对证据的影响**：**不影响租约证据** —— 台账仍证"B 执行了"，`tool_start`/`tool_result` 各恰一帧、终帧到达；`RESOURCE_NOT_FOUND` 是 H2 按实例隔离的必然结果，恰好也证明"执行确实换了主人"。
- **去向**：登记 **L-12**（harness 跨实例共享库形态，若将来要做"跨实例数据一致性"证据，需要换共享库或改桩路由）。

## 5. 未覆盖（诚实口径）

| 项 | 说明 |
| --- | --- |
| 真跨进程的"接管瞬间"时序 | 单测（`ResumeServiceLeaseTest`）只锁"入口判定"；"旧主人还在驱动、新主人已抢到"的微秒级交错由 harness 的 `instance-kill` 近似覆盖（kill → 租约过期 → 延迟重试接管），不是精确时序实验 |
| 双实例数据一致性 | 见 L-12（H2 隔离） |
| 资源互斥 / 幂等 | 批③（`resource-lock` / `idempotency` 仍为 SKIP 占位） |
| P95 统计显著性 | 见 §1.1（两样本） |
