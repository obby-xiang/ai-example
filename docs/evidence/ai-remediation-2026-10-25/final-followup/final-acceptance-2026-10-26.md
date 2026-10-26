# 最终验收事实（同一 SHA `61de820` 的五工作流实跑，2026-10-26）

> **性质**：本件是**证据件**，只登记「在同一个提交上，哪些工作流实际跑过、跑了几次、计数是多少、哪些没跑或不作数」。**不写施工方案、不改任何历史结论**。
> **判定基线**：`HEAD == origin/main == 61de820`（提交 `61de820`「test(frontend): 固定英文面板用例的Pinia实例」）；本件所有 run 的 `headSha` 均为 `61de82079d9cd759af732915facded010af962bf`（已用 `gh run list --json headSha` 逐条核对）。旧 SHA 的绿（`34fd8f4` / `d373ae6` / `636dd78` / `152b3a9` / `4942398`）**只作归因，不作本件通过证据**。
> **取数方式**：`gh run list` / `gh run view <id> --json jobs,attempt,conclusion` 取结论与 job 级结果；`gh run view <id> --log` 取 job 日志里的计数行（后端 `Tests run:` 汇总行、前端 `Test Files`/`Tests` 行、Playwright `N passed (…)` / `N skipped` 行、冒烟网 `结果: PASS …` 行）。**输入参数（`suite` / `sample`）无法从 run 元数据回查**，只能由 job 实际执行情况与 job 日志留证（见 §3）。
> **结论口径**：**4/5 满足「同一最终 SHA 三次独立成功」；主 CI 未满足，且最新一次尝试为红** —— 因此**不得写「五工作流全绿」或「全项目验收完成」**。

## 1. 总表

| 工作流 | 三次 run（同 SHA） | run 级结论 | 关键计数（job 日志实测） | 三次口径 |
|---|---|---|---|---|
| `nightly.yml`（夜间全量） | `40256321755` / `40257551220` / `40258703173` | 3/3 `success`（attempt 均 1） | 后端 `Tests run: 2889, Failures: 0, Errors: 0, Skipped: 0`（**三次同数**，默认 locale + en-US 各一遍）；前端 `Test Files 145 passed` / `Tests 1672 passed`；E2E job 绿；倒排表重建与校验 job 绿 | **满足** |
| `cluster-3node.yml`（真实三实例） | `40256321742` / `40256956378` / `40257579571` | 3/3 `success`（attempt 均 1） | 末次 `结论：PASS（PASS 16 / FAIL 0；exit=0）`；同 run 另有脚本自证 `自证结果：PASS 53 / FAIL 0` | **满足** |
| `redis-5-smoke.yml`（5.0.14 环境级） | `40256321631` / `40256402773` / `40256512660` | 3/3 `success`（attempt 均 1） | 末次 `结果: PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3` | **满足** |
| `e2e-real.yml`（真机 · 桩 + 真模型） | `40256960524` / `40258550325` / `40260513930` | 3/3 `success`（attempt 均 1）；**每次两个 job 都实际执行**（job 列表逐 run 核对） | 桩 job：**三次全段求和均为 268 passed**；三次均见 `2 skipped`。真模型 job：三次均见 `9 passed`。**本批复审已用 `gh` 把三次 `--log` 全部取全，三次均可逐段核对**（不再有“只核末次”的限制） | **满足（§3 的输入参数反推限制仍在）** |
| `ci.yml`（主 CI 门禁） | `40255993044`（仅 **2 次尝试**） | attempt 1 `success` → attempt 2 `failure`，**run 级 conclusion = failure** | attempt 1 `结果：PASS=166 FAIL=0`；attempt 2 `结果：PASS=165 FAIL=1`，唯一失败 `AIE2E-Q-08`；attempt 2 中 `E2E 全流程` 与 `门禁汇总` 两个 job 红，其余 6 个 job 绿 | **不满足（2/3 次尝试，且最新一次红）** |

## 2. 逐项取证要点

### 2.1 nightly 三次同数

`gh run view <id> --log | grep -oE "Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: 0$"` 在三次 run 中均取到 `Tests run: 2889`（每次两行 = 默认 locale 与 en-US 两跑）。前端两行 `Test Files 145 passed (145)` / `Tests 1672 passed (1672)` 取自末次 `40258703173`；前两次的前端/E2E/testmap job 结论同为 `success`，未逐次抄写计数。

### 2.2 cluster 三次的覆盖面（不作外推）

16 条为「真实三 JVM + 共享 Redis/H2-TCP/文件 + 无粘滞代理」下的用例集，含 `suspend-cross-instance`、`idempotency-cross-instance`、kill 接管等。**它不是** `L-150` 三条安全反例（`UNKNOWN` 不得执行 / 已 `CANCELLED` 不得误认领 / claim 哈希-only 不得伪未执行）的证明 —— 三条不在该 16 例内（见 §4）。

### 2.3 redis-5 是环境级，不是产品级

`redis-5-smoke` 在 Ubuntu + Docker `redis:5.0.14` 临时 3 主 3 从内跑语义与故障冒烟，**网内不启动后端**：`SKIP 3` 中有 1 条明确写「未提供 `SMOKE_BACKEND_LOG`（本网不启动后端；修复验收时传入后端日志再扫）」，另 1 条为「节点 docker logs 仅落盘备查（不设门禁）」。`KNOWN-LIMIT 1` 为已知限制项。**因此它不证明**产品 `available` / 503 语义，也不证明迁移工具在真集群上执行。

### 2.4 e2e-real 的 268 / 9 与 2 个 skip

- 桩 job 由 11 个 Playwright 段组成：主栈切片 `106 passed`、VL `28`、NFEZUI `48`、GF2 `1`、MDXY `31`、MDO multi `7`、MDO degraded `15`、KQC 主档 `23`、KQC 降级 `2`、KQC 压缩 `4`、解锁缩阈值 `3` ⇒ 合计 **268 passed**。
- **skip 数为 2，不是 0**：`e2e/10-srp.spec.ts:176` 的 `E2E-S04`（帧间静默：到点判死 → 重试 → 显式错误收敛）与 `e2e/17-unlock.spec.ts:677` 的 `E2E-R18`（重挂横幅生命周期（缺稳定抢发面，见报告））。三次 run 均为 `2 skipped`，属**已登记缺面的既有 skip**，不是本次新增。
- 真模型 job：`9 passed`，用例为 `E2E-RM01`、`E2E-RM02`（工具轮 10 次采样 ≥7）、`E2E-S15`、`E2E-G01`（FILTER 回灌续跑，N=10 ≥7）、`E2E-X07`（提示词定界，采样 10 次 ≥9）、`E2E-K10`、`E2E-C13`、`E2E-Q02r`、`E2E-V14r`。

## 3. `suite=both` 与 `sample=10` 的可证程度（如实降级）

| 项 | 可证事实 | 不能证的事 |
|---|---|---|
| `suite=both` | 三个 run 的 job 列表里 `UI 层 E2E（桩上游 · Chromium）` 与 `真模型 E2E 冒烟（真 key · Chromium）` **都出现且都 `success`**；真模型 job 日志含真实模型用例执行与耗时（如 `9 passed (7.2m)`） | 读不到 `workflow_dispatch` 的输入原文：`gh run list --json displayTitle,event` 只回来 `display_title = 真机 E2E（手工触发 · Playwright + 真 key）` 与 `event = workflow_dispatch`，**输入值不入 run 元数据** ⇒ 「两个 job 都实际执行」是**可证**，「输入里写的就是 `both`」属**反推** |
| `sample=10` | 真模型 job 的环境块逐行打印 `E2E_REAL_SAMPLING: 10`（多次出现），且用例名自带采样口径（`采样 10 次 ≥9`、`N=10 ≥7`） | 采样是**概率口径**：`≥9/10`、`≥7/10` 通过**不等于**每次都会通过；单次绿不构成服从率结论 |

## 4. 主 CI 为什么不作数（attempt 1 绿 ≠ run 绿）

- run `40255993044` 在 `61de820` 上被重跑过：**attempt 1 全绿（E2E `PASS=166 FAIL=0`，`门禁汇总` 判定「绿」）**，**attempt 2 红（`PASS=165 FAIL=1`）**。GitHub 的 run 级 `conclusion` 取最后一次尝试 ⇒ **`failure`**。
- attempt 2 的唯一失败：`[FAIL] AIE2E-Q-08 [P1] 点即发与 409 的交互：历史条数不变（无幻影气泡） => 并发点发 HTTP 200，期望 409`。
- **不满足三轮**：该 run 只有 2 次尝试，且最新一次为红；**attempt 1 的 166/166 不能当 run 级通过**，也不能计入「三次独立成功」。
- **Q-08 未修**：`scripts/verify-e2e.ps1` 与 `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` 在 `636dd78` 之后**没有针对 Q-08 的改动**（`git log -1 -- <两路径>` 均停在 `636dd78`；`152b3a9` / `61de820` 未触达），归因与两种处置方向见 `q08-premise-timing-analysis.md`，**待裁决，不改测试也不改产品**。

## 5. 未验边界（不得因本件存在而视为已验）

1. **H2 ≠ 生产 PostgreSQL**：三次 nightly、cluster、E2E 全部跑在 H2（含 H2-TCP 共享形态）上；真实 PG 上的迁移、锁、`jobs.task_id` 无 FK 等面**未验**。
2. **Redis 5.0.14 环境级 ≠ 生产验证**：冒烟网只覆盖语义/故障/槽拓扑，不启动产品后端；产品 `available` / 503 / 迁移工具真集群执行 / 连接池与超时行为**未验**。
3. **真机采样是概率口径**：`@real` 9 条用例的通过是「N 次采样 ≥ 阈值」的结果，不构成模型服从率、也不构成多次运行的稳定性承诺；真 key 由 Environment 提供，成本与配额无硬上限。
4. **真实 Lettuce / Redis 中断形态未验**：租约健康分类的判据来自用例复刻的 cause 链；容器 `docker stop`/kill 属环境级故障，与生产网络分区、主从切换期间的客户端行为**不同构**。
5. **真实 PG 迁移未验**：迁移只有离线用例（52 例）与 H2 实跑，**没有**在生产同构 PG 上的执行记录。
6. **多实例墓碑 / 1s 探针 / 取消风暴下的池饱和**：仍为未验 —— 三次 `cluster-3node` 的 16 例覆盖通用链路，**不含**墓碑回收与探针窗口的确定性用例，也不覆盖 `aiResumeTrigger` 池饱和；三条安全反例另见 **`L-150`**（16/16 不能反推三格已验）。
7. **`L-143` 间歇性 SSE 帧交付竞态未闭环**：本次三次绿**不能**作为「竞态已消失」的证据（间歇竞态未被确定性覆盖）；Q-08 的「200 vs 409」正是同类时序面的新形态。
8. **九册正文零启动**：`docs/delivery/` 不存在，`01`~`09` 未建；启动前置含「五工作流验收完成」，当前**未满足**，且用户已明确「等指令、不自动写正文」。

## 6. 复核命令（只读）

```bash
git rev-parse HEAD                      # 61de820…
gh run list --limit 25 --json databaseId,workflowName,headSha,conclusion,attempt
gh run view 40255993044 --json attempt,conclusion,jobs
gh run view 40255993044 --attempt 1 --log | grep -E "结果：PASS="
gh run view 40255993044 --attempt 2 --log | grep -E "\[FAIL\]|结果：PASS="
gh run view 40258703173 --log | grep -E "Tests run: 2889|Test Files 145|Tests 1672"
gh run view 40257579571 --log | grep -E "结论：PASS|自证结果"
gh run view 40256512660 --log | grep -E "结果: PASS"
gh run view 40260513930 --log | grep -E "passed \(|skipped|E2E_REAL_SAMPLING"
```
