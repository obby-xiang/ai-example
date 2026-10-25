> 来源=<仓外证据>/STATUS.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 现场状态整理（供主会话防漏）—— 本棒最终状态

- 生成时间：2026-10-24 22:22 +0800（仓外核对版；上一版 22:15 的 HEAD/CI 表述已按实测修正）
- 主仓：`<主仓>`
- 本文件性质：仓外状态快照。**本次只改本文件**：未改仓内文件、未再制造提交、未起停 8081/5202、未跑 `mvn clean`。
- 口径纪律：**「已验」只写跑过的证据**；「未闭环」单列；计数以日志/CI 原文为准，不猜。

---

## 0. 现场实测（事实钉住）

| 项 | 实测值 | 依据 |
|---|---|---|
| HEAD | **`d66ab7c`**（2026-10-24 22:08:05，`docs: 完成阶段验收并登记后续风险`） | `git log -1` |
| 与远端 | `origin/main...HEAD` = **0 / 0**（已同步） | `git rev-list --left-right --count` |
| 工作树 | **6 项未跟踪运行产物**：`_b3e-*.log` ×4、`data/`、`test-results/`（无已跟踪改动） | `git status --porcelain` |
| 最近提交 | `d66ab7c` → `784f0ce` → `904cd20` → `a5af053` → `1916449` → `74ef046` → `c2f8b2f` → `03dbb84` → `b38a95a` → `882e6c5` | `git log -10` |
| 最新 CI | run **`40101151224`**（headSha `d66ab7c`）**completed / success**；8 个 job 全绿：文档一致性门禁（check-docs）｜阻断、全历史脱敏扫描（报告制）、Redis Cluster 安全守卫、变更集计算、前端类型检查与构建、后端编译与单测、E2E 全流程（35 用例 · 桩上游 + redis）、门禁汇总 | `gh run view 40101151224 --json status,conclusion,headSha,jobs` |
| 上一跑 CI | run **`40099230125`**（headSha `784f0ce`）completed / success | `gh run view 40099230125` |
| 环境级 Cluster | run **`40097120796`**（headSha `c2f8b2f`）**completed / success**：**PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3** | `gh run view 40097120796 --log` |

> 两次绿跑都不覆盖 §3 的间歇竞态（见该节限定）。

---

## 1. 本棒提交清单（全部已推 `origin/main`）

| 面 | commit | 内容 |
| --- | --- | --- |
| 文档（最终） | **`d66ab7c`** | 完成阶段验收并登记后续风险（双看板 + `03` + 总纲 + `scripts/README`：CI/E2E/Cluster 最终结论、**L-143 P1 竞态**、边界与计数） |
| 性能（githooks） | `904cd20` / `784f0ce` | 历史 blob 并行扫描 + `printf` 传 NUL；**扫描面未缩小**，修复后 scan-history **4m26s** |
| 测试（E2E） | `a5af053` | KQC C12 断言对齐 R-08 用户面安全文案（KQC 由 22P/1F → **23 passed**） |
| 文档（前一笔） | `1916449` | 回写机制规格与阶段验收证据（总纲 §3.2b/§13.5 + 03 勘误 + 双看板 + scripts/README） |
| 前端 | `74ef046` | 完善 AI 交互契约与发布确认（含错误码前端镜像，修掉中间提交的镜像缺码红） |
| 后端 | `c2f8b2f` | 收口 Redis Cluster 与任务作业竞态（`beginRunner` 创建事务内硬闸、Job CAS、TTL 派生、多实例恢复、V6 子表 FK、UNKNOWN 对账、degraded 回读、4B 语言等） |
| 脚本（前置） | `882e6c5` / `b38a95a` / `03dbb84` | 迁移工具 + 5.0 冒烟网 + Cluster grep 守卫 + CI 接线；冒烟网改用容器 IP、等 3 主 3 从落定 |

---

## 2. 验证证据（只列跑过的）

| 面 | 结果 | 证据 |
| --- | --- | --- |
| CI（最新） | run `40101151224`（`d66ab7c`）**success**，8 job 全绿（含 backend 单测 / 前端类型+构建 / E2E 全流程 / scan-history） | `gh run view 40101151224 --json jobs` |
| CI（上一跑） | run `40099230125`（`784f0ce`）success | `_final-close/ci-784f0ce.log` |
| 后端全量 | **2036/2036 BUILD SUCCESS** | `_final-close/backend-tests-final.log`（21:03） |
| 后端最终定向 | 16/16、49/49、25/25、关键 5/5；最终类编译 **BUILD SUCCESS** | 主会话核验 + `final-e2e/compile.log` |
| 前端 | **124 文件 · 1497/1497**；`npm run build` 成功；`vue-tsc` 0 | `frontend-tests-final2.log`、`frontend-build-final.log` |
| ps1 全流程（r2） | 摘要行 **`PASS=166 / FAIL=0`**，`exit 0`；批① 130/130、GFSKIP=0（日志中 `[PASS]` 行 168 条 = 主用例 166 + Q8 两项单列） | `final-e2e/ps1-r2.log:240`、`ps1-r2.exit`＝`0` |
| Playwright 合计 | **268 passed / 0 failed / 2 skipped** = 主栈 106+2skip、VL 28、GF2 1、unlock 3、NFEZUI 48、MDXY 31、MDO 22、KQC 23、special 2、compact 4 | `final-e2e/lane*/pw.log` |
| MDXY 说明 | 前两棒红 = 测试环境 **`AI_SECRET_KEK` 未透传**（config 启动即报缺 key），r3 用确定性测试 KEK **31/31** ⇒ **不是产品缺陷** | `final-e2e/laneB-mdxy/pw.log` / `laneB-mdxy-r3/pw.log` |
| 迁移工具 | 离线用例 **52/52**（**未做真集群执行**） | `migration-tests-final.log` |
| Cluster 守卫 | `--self-check` 通过（12 正向样例） | `redis-guard-self-check-final.log` |
| 环境级 Cluster | **PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3**（Ubuntu + Docker `redis:5.0.14` 临时 3 主 3 从） | run `40097120796` |
| 文档门禁 | `check-docs` **6/6**；`git diff --check` 干净 | 提交前后各一次 |

**Cluster 结果的范围（必须连带说清）**：不是生产连接验证；不是产品 `RedisAvailability.available` / 503 的证明（网内该条 SKIP，且网内不启动后端）；不是迁移工具的真集群执行证明。

---

## 3. 未闭环 P1：间歇性 SSE 帧交付（证据口径已按日志校正）

**登记**：`docs/任务看板.md` **L-143**（状态 = **待施工 / 待排查**，非待用户拍板）。

**两次早期 ps1 的实际数字（日志原文，不猜）**

| 日志 | 摘要行 | `[FAIL]` 行数 | exit | 结论 |
| --- | --- | --- | --- | --- |
| `ps1-full.log:240` | `PASS=124 / FAIL=42` | 42 | （无 exit 文件） | **不作为竞态证据**：42 红以**脚本/harness 未就绪**为主 —— 8 条 `You cannot call a method on a null-valued expression`、`/api/tasks//files/...` 空 taskId、`Could not find file ...`（TC17/18/23/19）、`任务列表为空` / `前置行数 0` / `PROJECT_ENV 无种子数据` |
| `ps1-alone.log:240` | `PASS=155 / FAIL=11` | 11 | `1` | **有效红集**：`AIE2E-S-07`、`G-04`、`G-11`、`G-12`、`G-15`、`G-16`、`G-17`、`W-01`、`W-11`、`W-27`、`TC19` |
| `ps1-r2.log:240` | `PASS=166 / FAIL=0` | 0 | `0` | 全绿，**不构成"缺陷已消失"证据**（间歇、无确定性覆盖） |

> 计数口径：三条日志的 `[PASS]` 行分别 126 / 157 / 168，比摘要行各多 2 —— 摘要行是**主用例计数**，Q8 两项单列（日志内自述）。

**形态（来自 E2E 执行记录，非归因结论）**

- 日志可核形态：`AIE2E-W-01` 失败行为「**未收到 `frontend_tool_request`**（`ui_snapshot` 未命中披露清单）」（`ps1-alone.log:188`）；`W-11`/`W-27` 亦为挂起/停止面的帧或状态未达。
- **待验证假设（尚未被日志固化，不作为定论）**：①`approval_suspend` 与订阅者完成关闭落在**同一毫秒**、请求帧未及交付；②`GF1`/`GF3` 在 `turn_end` 的 `done` 后**缺 `done` 帧**。两条需在下一棒用**可控时钟/阻塞投递**复现并留证。
- **不得**：把 r2 166/166 或 CI 绿当作"已修复"；也**不得**在无证据时断言"与本批无关"。

**下一步**：查挂起/终态帧的**交付与连接关闭顺序**（`SseChatEmitter` 出帧→落档→投递→关闭；`ConfirmGate` 挂起帧；`done` 在订阅者关闭后的补发），补**确定性回归**，再据复现结果决定是否收窄/销记 L-143。

---

## 4. 其它未闭环边界（保留，不得当完成）

1. 真实产品 `RedisAvailability.available` / 503 降级行为（网内 SKIP）。
2. **真集群上的迁移工具执行**（迁移只有离线 52/52）。
3. **双 JVM 下的 runner 删除 / 恢复**（`b23l1-dual-instance.ps1` 面未在本棒复跑）。
4. **assistant 文本截断的历史持久化**：帧面（实时 + 归档）可挂截断标记且同源；但记忆里 assistant 文本仍全量、`/history` 以记忆窗口为权威源 ⇒ 历史面无截断语义（工具结果截断元数据是另一条链，已完成）。
5. **物理文件回收**：删除任务在事务提交后删盘，「写盘成功、落行失败」仍可能残留，无扫描回收器。
6. **`jobs.task_id` 无外键**：`db/migration/V6__Task_child_fk.java` 明确不加（历史库 1 条 `task_id=99999` 的 FAILED 孤儿，按「不得清理现存数据」不先删）；五个子表 FK 已加 + 孤儿预检 + 可重入。
7. **`@real` 真模型面未跑**（本棒只有桩面）。
8. **历史挂账未清零**：任务看板挂账 **209 行**（展开 184 + 折叠 25，真 open 171）。

---

## 5. 看板与文档状态（`d66ab7c` 后）

| 文件 | 状态 |
| --- | --- |
| `docs/任务看板.md` | 批次笔行 **152 = ✅144 + 📤0 + 📋6 + 🔧1 + ❌1**；新增 **L-143**（P1）；挂账 **209**（184+25） |
| `docs/决策看板.md` | **184 = ✅145 + 🏛️25 + 📌7 + ⏳1 + ⏸️3 + ❌2 + 🔧1**；头部重复 `16:03` 已修 |
| `docs/03-技术方案文档-v2.1.md` | 集群/版本注 → 最终成功 + 范围限定；同批边界 ⑤ 竞态 / ⑥ 未验证 / ⑦ 验收口径 |
| `docs/research/规划-核心技术文档体系与编写大纲-2026-10-24.md` | §3.2b 状态口径；§13.5 基准当前写 `784f0ce`（**下一次小回写刷 `d66ab7c`**，不为改自身状态单独起笔） |
| `scripts/README.md` | §4 scan-history 性能（4m26s、扫描面未缩小）；§5 集群/CI 最终结论 + 范围限定 |

---

## 6. 待主会话事项

1. **L-143**：按 §3 的证据边界做根因验证（同毫秒关闭 / 缺 done 属**待验证假设**）+ 确定性回归（下一棒优先）。
2. 第 4 节 1~3、7 四类**环境级验证**（产品 503 / 真集群迁移 / 双 JVM / @real）。
3. 下一次文档小回写时把总纲 §13.5 的 `baseline_commit` 与 CI run 号刷到 `d66ab7c` / `40101151224`。
4. 历史挂账 209 行未清零，按批次逐条销，不做一次性宣称。
