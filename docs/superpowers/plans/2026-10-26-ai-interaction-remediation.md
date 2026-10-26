# AI Interaction Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按代码复核后的事实修复 AI 数据真相、恢复、压缩、确认与工作区交互问题，不以放宽安全闸门或堆砌机制换取表面通过。

**Architecture:** 保留 park、会话门、工具台账和终态 CAS；将“轮终态”“内容/恢复视图状态”“人工等待状态”分开。先修无争议的工程正确性，再恢复现场与可见性，最后处理需裁决的产品语义和性能；不引入新框架。

**Tech Stack:** Java 21、Spring Boot 3.5.14、Spring AI 1.1.7、Vue 3.5、TypeScript、Pinia、Vitest 4.1、Playwright、既有 Redis/H2 测试设施。

**Spec:** `docs/research/合并-AI设计与交互审查-代码复核与方案-2026-10-26.md`

**规格裁决（口径唯一来源）:** `docs/research/裁决-AI交互整改实施口径-2026-10-26.md`（`AR-01`…`AR-15`，2026-10-26 由主控按用户授权裁定并逐项记录理由 / 代价 / 影响任务）。**本计划中所有“按裁决 / 待裁 / 未裁不做 / 另按 Task 0 裁决”的字样一律指向上件的 `AR-xx`，不再是待定项**；若本计划与 `AR-xx` 冲突，以 `AR-xx` 为准并先改本计划再施工。

## Global Constraints

- 本计划是施工步骤，不是开工授权；用户须逐批明确批准，九册正文不在范围内。
- Maven 只用占位符 `<IDEA_MAVEN>`；其含义为“用户本机 IDEA 自带 Maven 3 的 `mvn.cmd`；执行时从本地环境解析，不在库内保存绝对路径”。执行方式固定在 shell=Git Bash 中**先设置环境变量、再调用**：`export IDEA_MAVEN="<本机 IDEA 自带 Maven 3 的 mvn.cmd 绝对路径>"`，随后 `"$IDEA_MAVEN" -o test`。禁止 `clean`，优先 `-o`；不改用系统 `mvn`、不换 Maven、不拼 `mvn` 短名；变量引用用双引号，不用反斜杠转义。
- 提交只按明确文件清单；禁止 `git add -A`、`git add .`、绕过 pre-commit；是否 commit/push 由用户另行授权。
- 密钥只走环境变量，不入库、不回显；运行日志、临时脚本、过程副本不入库。
- 不把静态确认写成实机复现，不把单测绿写成生产 PG/Redis/L-150 已验，不写“五工作流全绿”直至实际达成。
- 每个任务先写定向红测，再最小实现，再跑同名定向测试；批量全量只在用户批准验收时执行。
- 不放开已输出/有副作用后的整轮重试，不乐观发 done，不把未知异常整桶改可重试，不默认关闭 park 或引入虚拟列表。

## Review Focus

- 取消与 DONE/FAILED 竞争：权威取消终态不得被任何恢复、重放或“未确认”视图状态覆盖；由 Task 4 的取消优先测试锁定。
- 条件空值与显式清空：畸形输入必须拒绝，显式全量必须可理解且不会由压平意外触发；由 Task 1 测试锁定。
- 工具副作用：仅模型步重跑不得重执行已结算工具、追加用户消息或更换模型；由 Task 4 测试锁定。
- 批量文件：未载入、真实 0 行、读取失败三者必须可区分，不能都变成合法空文件；由 Task 2 测试锁定。
- park 与恢复：挂起不是运行中，也不是失败；恢复失败不得永久 loading 或伪造终态；由 Task 3、5 测试锁定。

## 追溯矩阵

Task 0 Step 3 要求的两张对照表在此落地，改计划时同步改本表。编号沿用 `docs/research/排查-AI全量设计与交互审视-2026-10-26.md` §5“最小处理方向”与 `docs/evidence/ai-interaction-review-2026-10-26/reports/review-07-业务页面与交互.md`。

> **2026-10-26 口径落地（本表“阻塞/裁决”列取值以本段为准）**：下表原标的裁决门**已全部裁定**，出处 = `docs/research/裁决-AI交互整改实施口径-2026-10-26.md`。映射 = 行 1（历史范围/保留期/查看入口）→ **AR-01**（查看入口仍属新入口，本裁不做）·行 2（持久化敏感面）→ **AR-01**（不扩大 DB 范围、不新增字段）·行 6（失败现场 DB 面）→ **AR-01**（先走 Redis 快照 + 浏览器镜像）·行 7（24h 到期）→ **AR-10** ·行 8（轮内预算 / armed 跨 park）→ **AR-10** ·行 9（summary-model 哑键）→ **AR-11** ·行 12（知情同意三行）→ **AR-02**（supersede 只在三字段）·行 14（第二张表单）→ **AR-07**（排队）·行 19（任务中心刷新）→ **AR-08** ·行 20（虚拟化）→ **AR-15** ·行 23（复制页签）→ **AR-14** ·行 24（regenerate / 编辑提示）→ **AR-09 + AR-17**（三动作语义分开；普通 regenerate 本批不做、入口隐藏）·行 1 与 6 之外的重跑粒度 → **AR-05** ·`create_task`（Task 9）→ **AR-03** ·Q-08（Task 12）→ **AR-04** ·首轮压缩首帧（Task 5）→ **AR-06** ·历史思考（Task 10）→ **AR-12** ·通知队列（Task 3）→ **AR-13**。**未列入本段的事项仍按各自挂账未裁**（见裁决件 §4）。

### 25 条处理方向 → Task

| # | 处理方向（简） | 归属 | 阻塞/裁决 |
| --- | --- | --- | --- |
| 1 | 历史正文落库范围、保留期、清理、查看入口 | Task 4 Step 4；查看入口本批不做 | **AR-01**：不扩大 DB 范围；**Redis 24h 与 DB 无 TTL 两侧分别维持原状**；查看入口未立项 |
| 2 | 持久化敏感面（密钥形状标量、工具响应原文、思考） | Task 4 Step 4 | **AR-01**：不新增字段、不恢复 DLP |
| 3 | 向导硬闸拒绝空值 / `IN []` / 重复字段 | Task 1 Step 1-4 | 无 |
| 4 | 工作区复位与前端动作串行、成功才报已同步 | Task 8 Step 1-3 | 无 |
| 5 | 压缩摘要不冒充用户原话 | Task 4 Step 4（复用 synthetic 判据） | 无 |
| 6 | 失败也留可解释现场 | Task 4 Step 3（镜像/展示）+ Step 4（DB 不扩） | **AR-01**：先走 Redis 快照 + 浏览器镜像 |
| 7 | 24h 到期可见化 / 续期 | Task 6 Step 6 + Task 13 Step 2 | **AR-10**：只可见化、不加续期 |
| 8 | 轮内预算入规格、95% 行为、armed 是否跨 park | Task 4 Step 6 + Task 13 Step 2 | **AR-10**：armed 跨 park 持久化 |
| 9 | summary-model 哑键 | Task 5 Step 5 + Task 13 Step 2 | **AR-11**：删键 + 退役告警，不恢复分支 |
| 10 | 看板与决策登记回写 | Task 0 Step 1-2 | 无 |
| 11 | 恢复失败可见出口 + 409 回到进行中回答 | Task 3 Step 1-3 | 无 |
| 12 | 知情同意三行（影响面/条件摘要/发布模式） | Task 6 Step 4（无裁决部分）+ Step 5 | **AR-02**：supersede 只在这三字段 |
| 13 | 结果完整性（批量下载/服务端字节回落） | Task 2 Step 1-3 | 无 |
| 14 | 单活动表单不覆盖/无死卡（实时与恢复同构） | Task 7 Step 1-3 | **AR-07**：第二张排队 |
| 15 | 预检前移、覆盖面补两项、阻断态文案 | Task 6 Step 1-4 | 无 |
| 16 | 派发/起作业后的页面同步 | Task 8 Step 3 | 无 |
| 17 | 死轮兜底（受租约与僵尸年龄约束的低频扫描） | Task 12 Step 3 | 无 |
| 18 | 只读 fail-closed | Task 2 Step 1/3 | 无 |
| 19 | 任务中心自动刷新复审 | Task 8 Step 4 | **AR-08**：事件后 + 仅在途 + 可见时（对 10-12 的有限改判） |
| 20 | 渲染三件：围栏 key 只由渲染器生成、>1 万字符一次性提示、15 语言文档 | Task 11 Step 1/3/5 | **AR-15**：虚拟化只量化、不在本条 |
| 21 | 披露不变量：空 scope 同判、回调表 ⊆ 元数据表、scope 下限 26→37 | Task 6 Step 4 | 无 |
| 22 | 过期文档口径统一（6h/24h/去超时/重连） | Task 13 Step 2 | 无 |
| 23 | 术语与残留清理（skipped 卡二分、i18n 残留、复制页签、park 文案、空态保存） | Task 3 Step 4 + Task 6 Step 3 | **AR-14**：复制页签只告知、不接回（新入口本批不做） |
| 24 | 编辑重跑后果告知、继续 vs 重试本轮、错误卡两层文案 | Task 4 Step 5 + Task 3 Step 4 | **AR-09 + AR-17**：继续 / 重试本轮本批交付；普通 regenerate 本批不做（入口隐藏，A7 保留缺项）+ 编辑后果提示 |
| 25 | 可访问性硬项（log 名、aria-busy、aria-expanded） | Task 10 Step 3 | 无 |

### review-07 P1-1…8 → Task

| 项 | 内容（简） | 归属 |
| --- | --- | --- |
| P1-1 | 导入勾选/上传/换任务丢弃未保存编辑 | Task 2 Step 1/3 |
| P1-2 | 保存编辑跳过校验失败仍报成功并清脏 | Task 2 Step 1/3 |
| P1-3 | 批量下载给未打开标签下空工作簿 | Task 2 Step 1/3 |
| P1-4 | 预检失败文案“可直接继续”与后端拒绝矛盾 | Task 2 Step 3（文案）+ Task 6 Step 3（PRECHECK 语义） |
| P1-5 | 筛选控件显示值与实际查询不同源 | Task 2 Step 1/3 |
| P1-6 | 任务中心纯手动刷新 | Task 8 Step 4（裁决后实施） |
| P1-7 | 列表读取失败被吞、页面 fail-open 可编辑 | Task 2 Step 1/3 |
| P1-8 | 重导出先清旧结果与未保存编辑 | Task 2 Step 1/3 |

### 验收证据归属（§3 台账里的“已修待复验”，不占 25 条编号）

| 台账项 | 归属 | 口径 |
| --- | --- | --- |
| D7 零条目假成功 | Task 12 Step 4 | 只验不改：按当前代码与真实作业边界复验 `start_*` 零条目不假成功；下载侧区分归 Task 2；取得真实边界证据前保持“已修待复验” |
| D8 取消后仍执行 / L-150 | Task 12 Step 5 | 本地确定性测试只作本地证据，不得反推三实例；真实三实例三格显式执行才闭环 |

---

## 任务分解

### Task 0: 登记合并口径与裁决，不改历史原文

**Files:**
- Modify: `docs/任务看板.md`（L-152 追加合并结论链接）
- Modify: `docs/research/README.md`（登记本方案与计划）
- Modify: `docs/决策看板.md`（仅记录用户实际裁决）

- [ ] **Step 1:** 在任务看板追加“合并审查已完成、代码静态复核、未跑测试”的链接，不重写原 100 项、223 条和 Issue 的历史结论。
- [ ] **Step 2:** 按合并稿 §5 的完整清单逐项登记裁决：历史保留与敏感面、预检区 supersede、create_task、Q-08、任务中心刷新、仅模型步重跑与失效替换、压缩首帧、表单场景/队列、24h 到期、armed 跨 park、summary-model、regenerate/编辑提示、历史思考、通知队列等；每项标所属 Task 和阻塞关系。**2026-10-26 已裁，裁决件 = `docs/research/裁决-AI交互整改实施口径-2026-10-26.md`**：历史范围/保留期/失败现场 = **AR-01**、预检区 supersede = **AR-02**、create_task = **AR-03**、Q-08 = **AR-04**、仅模型步重跑与"不做半截替换" = **AR-05**、压缩首帧 = **AR-06**、表单队列 = **AR-07**、任务中心刷新 = **AR-08**、继续 / 重试本轮 = **AR-09**、普通 regenerate 本批不做 = **AR-17**、24h 到期与 armed 跨 park = **AR-10**、summary-model = **AR-11**、历史思考 = **AR-12**、通知 = **AR-13**、复制页签 = **AR-14**、虚拟化 = **AR-15**；**本步不再有"未裁不代选"的项**。
- [ ] **Step 3:** 对照表已在本文“追溯矩阵”给出（25 条处理方向 → Task、review-07 P1-1…8 → Task，以及“已修待复验”的验收证据归属），登记时逐行核对每条都能在对应 Task 的 Files/Steps 找到落点，有出入先改表再改看板；再执行文档门禁并人工核对本次新增链接（`check-docs` 不覆盖两份看板与 research 索引的全部内容）。

Run: `node scripts/ci/check-docs.mjs`
Expected: 全部通过：6/6 断言绿；新增链接另行人工核对存在。

### Task 1: 阻断条件语义反转，保留显式清空语义

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:249-340`
- Modify: `frontend/src/types/condition.ts:169-211,295-341,397-405`
- Modify: `frontend/src/views/ExportWizardView.vue:773-779,1672-1684`
- Test: `backend/src/test/java/com/example/configmgr/ai/tool/WizardActionGuardTest.java`
- Test: `frontend/tests/ai-region-cond-errors.spec.ts`
- Test: `frontend/tests/sheet-condition-parity.spec.ts`
- Test: `frontend/tests/export-wizard-step-outcome.spec.ts`
- Test: `frontend/tests/task4-export-condition-fidelity.spec.ts`

**Interfaces:**
- Consumes: 现有 `parseConditionJson`、`buildQueryCondition`、`stringifyCondition`。
- Produces: 对非法空值/重复字段的结构化拒绝；草案未被用户修改时不把服务端条件覆盖为 `{}`。

- [ ] **Step 1:** 写红测：`EQ ""`、空白、`IN []`、重复 fieldCode 拒绝；`EMPTY/NOT_EMPTY` 合法；显式清空与畸形空值分用例。
- [ ] **Step 2:** 在 backend 运行 `WizardActionGuardTest`，确认新增用例按预期失败。
- [ ] **Step 3:** 在守卫补空值、空数组和重复字段校验；前端压平后为空时阻断开始导出并提示，不用 `{}` 静默覆盖；草案未改时保留服务端原值。
- [ ] **Step 4:** 跑后端定向与前端条件定向，预期全绿；不通过时不得放宽全量导出断言。

Run（shell=Git Bash，cwd=`backend`）: `export IDEA_MAVEN="<IDEA_MAVEN>"` 后 `"$IDEA_MAVEN" -o -Dtest=WizardActionGuardTest test`
Run（cwd=`frontend`）: `npm test -- tests/ai-region-cond-errors.spec.ts tests/sheet-condition-parity.spec.ts tests/export-wizard-step-outcome.spec.ts tests/task4-export-condition-fidelity.spec.ts`

### Task 2: 修复下载、保存与只读判定的数据真相

**Files:**
- Modify: `frontend/src/views/ExportWizardView.vue:470-535,906-977,1144-1387,1517-1556`
- Modify: `frontend/src/views/ImportWizardView.vue:653-668,835,124-130,2100-2132`
- Modify: `frontend/src/views/TaskCenterView.vue:20-33,189-211`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`
- Modify: `backend/src/main/java/com/example/configmgr/job/service/JobService.java:293-310`
- Test: `frontend/tests/export-wizard-batch-download.spec.ts`
- Test: `frontend/tests/export-wizard-step-outcome.spec.ts`
- Test: `frontend/tests/task4-import-file-fidelity.spec.ts`
- Test: `frontend/tests/import-watch-recycle.spec.ts`
- Test: `backend/src/test/java/com/example/configmgr/job/service/JobMutexGuardTest.java`
- Test: `backend/src/test/java/com/example/configmgr/job/service/StaleJobRecoveryMultiInstanceTest.java`

**Interfaces:**
- Produces: 下载内容就绪闸；保存结果 `saved/failed/skipped` 真实分类；读取失败 fail-closed；仅非终态作业活性检查。

- [ ] **Step 1:** 写红测覆盖：未打开标签、真实 0 行、预载失败、服务端字节回落、校验失败不清脏、列表读取失败不可编辑、重导出失败保留旧结果、导入勾选/上传/换任务重载前判脏及离页提醒、筛选翻页同源。
- [ ] **Step 2:** 运行上述现有定向套件，确认新断言先红；修正 batch-download 夹具绕开 gridRefs 的盲区。
- [ ] **Step 3:** 下载前逐码确保载入或回落服务端字节，任一失败整批显式失败；保存校验失败计入失败；读取失败禁用写操作；重导出前判脏并保留旧结果至新作业成功；导入重载/离页前判脏；删除预检失败后“可直接继续”的中英文误导文案。
- [ ] **Step 4:** JobService 仅遍历非终态作业，保持 task 行锁和 Redis 不可用 fail-closed；跑本任务定向测试与 typecheck。

Run: `npm test -- tests/export-wizard-batch-download.spec.ts`（cwd=`frontend`）
Run: `npm run typecheck`（cwd=`frontend`）
Expected: 未载入/失败不再产出静默空包，所有新增断言通过。

### Task 3: 统一恢复失败出口与前端等待状态

**Files:**
- Modify: `frontend/src/stores/ai.ts:1685-1775,2131-2202,2666-2781,3386-3405,4819-4972`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue:77-104,180,1844-1846`
- Modify: `frontend/src/components/AiPanel/ContextPopover.vue`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`
- Test: `frontend/tests/a10-recovery-paths.spec.ts`
- Test: `frontend/tests/restore-reconcile.spec.ts`
- Test: `frontend/tests/aie2e-refresh.spec.ts:221-248`
- Test: `frontend/tests/context-pill.spec.ts`

**Interfaces:**
- Produces: `historyError/contextError` 视图错误态；手动 `reattachActive(runId)` 入口；notice 自带 severity；恢复早退统一 seal。

- [ ] **Step 1:** 写红测：history/current 失败不永久 loading、上下文明细失败可重试、409 可重挂不暴露 runId、park 显示等待、陈旧卡只读人话、新会话不误红。
- [ ] **Step 2:** 跑定向确认红；保留“不向用户暴露内部 ID”的既有断言，只改变动作与文案。
- [ ] **Step 3:** 实现错误条 + 重试 + 草稿保留；所有恢复早退统一封口；409 提供手动重挂；通知 severity 不再从上一轮结局推断。镜像 schema 的唯一 owner 是 Task 4，本任务不得改镜像字段。
- [ ] **Step 4:** 修正复制页签（按 **AR-14**：只补“这是新会话”的一次性告知，**不做**接回原会话入口）、空态保存（按 **AR-01** 必须**同时写清三类载体**——浏览器镜像 / Redis 运行态·记忆 24h 滑动 / 既有完整历史 DB **无 TTL**；**不得**写成“不落库”，并删除与现行实现矛盾的表述）、机器码与草稿孤儿键；chips 与 composer 共用可用性闸；关键错误归错误卡（按 **AR-13**），通知**不新增**全局队列 / 通知中心，只做 severity 自带与归卡。跑定向和 typecheck。

Expected: 无静默永久“工作中…”，无自动重挂轮询，无新增存储 `clear()`；Task 3 完成后 Task 4 才能改镜像 schema 和错误现场。

### Task 4: 保存失败现场，提供中性未确认状态与窄恢复入口

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1068-1180,1342-1375,1757-1779`（`1268-1282` 只读核对，不改服务端 unconfirmed 终帧；`1342-1375` 是 `emptyReply()` 的 FAILED 写口）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`（取消优先终态守卫/CAS）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:746-749`
- Modify: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:466-479`
- Modify: `backend/src/main/java/com/example/configmgr/ai/history/AiChatHistoryRecorder.java:177-196`
- Modify: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:1284-1325`（synthetic/未完成读侧）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/UpstreamErrorClassifier.java`
- Modify: `frontend/src/types/ai.ts`、`frontend/src/stores/ai.ts`、`frontend/src/utils/error-card.ts`
- Modify: `backend/src/main/resources/messages_zh.properties`、`backend/src/main/resources/messages_en.properties`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/EmptyReplyTerminalTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/UpstreamErrorRetryPolicyTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/CancelDuringRetryBackoffTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/conformance/ChatTurnFrameSequenceConformanceTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/history/AiChatHistoryRecorderTest.java`

**Interfaces:**
- Consumes: Task 3 的恢复错误态与统一 seal；镜像 schema 只能由本任务升级，并兼容旧镜像。
- Produces: 失败现场（partial + 工具台账 + error code）；前端消费既有 `turn_end=unconfirmed` 的中性视图态；可选 `retryModelStep(runId)` 窄入口，不重放工具。

- [ ] **Step 1:** 保留 CancelDuringRetryBackoffTest 现有 DONE/取消用例作为回归锁，不假装它们先红；FAILED 写口共三处（`fail()`、`emptyReply()`、`AiController` 环外异常），逐一为“取消态被覆盖”写新红测，并在 RunStore 增加取消优先读 + CAS/守卫，`fail()`/`emptyReply()`/环外异常共用同一守卫，禁止裸 save。
- [ ] **Step 2:** 写 EMPTY_REPLY 仅在 `!accumulator.emittedAny()`（含零思考）且零副作用时重试的红测，补“只有 reasoning、正文为空 ⇒ 不重试且思考段不重复”；第二次仍空则终止。前端为既有 `turn_end=unconfirmed` 落中性视图，不改服务端终帧；中文派生码文案前后端一致。
- [ ] **Step 3（不依赖保存裁决）：** 镜像升级并携带 errorCard/code，兼容旧镜像且不再把失败归一为完整答案；开流前零副作用瞬时失败做有界重试；归档窗口缺口显示“不可完整恢复”，不伪造轮失败。
- [ ] **Step 4（按 AR-01 收窄为"不扩 DB"）：** 失败现场的**持久面不扩**：不新增列 / 不新增表 / 不改 TTL / 不把帧面已有而 DB 面无的东西搬进 DB；两条轻路径 = ①Redis 快照与帧日志（24h 内可逐帧还原）②浏览器镜像（Step 3 的 schema）。**只保留两件事**：展示通道复用 synthetic 判据（摘要不再冒充用户消息、不显示内部配置键）+ 读侧对既有数据的"未完成"标识。"持久化失败半截"原句**作废**（不实施）；敏感面按 AR-01 第 1 条不动（不新增工具响应原文、不新增思考展示字段），**不恢复**任何 DLP 组件（延续 10-24 删 DLP 裁决）；前端查看入口属新产品入口，AR-01 明确**本批不做**。
- [ ] **Step 5（按 AR-05 / AR-09 / AR-17，口径已生效、不再另等批准）：** 仅模型步重跑限失败、无在途、待决全结算、工具轮配对、**无半截**、同模型、同固定历史（七条全满足才允许），且**消息归属可证明**（`message.runId` 优先；只有当前在途宿主才允许回退 `activeRunId`，否则拒绝、不出入口）；断言工具零重执行、无新用户消息。**有半截时不做自动"失效替换"**（AR-05 明确不做），只给中性未确认视图 + 手动入口。三动作语义按 AR-09 分离：**继续**（追加驱动、不删历史）/ **重试本轮**（仅零产出可用、替换本轮、不追加用户气泡）/ **普通消息级 regenerate = 本批不做（AR-17）** —— 入口整体隐藏、错误卡不跳假动作，**不得**保留禁用按钮 / unsupported 死 UI，也**不得**本地截断后 `send`。编辑用户消息前必须提示"将删除 N 条后续消息"（N 实测）并由用户确认。
- [ ] **Step 6（按 AR-10）：** 把"只清结果 + 95% 收尾 + 越窗"写进规格；**armed 跨 park 持久化**（挂起不清零、恢复继续累计、保持"本轮预算"语义）——这是**行为变更**，必须同步规格、文案与回归面，不得顺手做；95% 收尾沿用既有 synthetic 判据，不得包装成普通错误或冒充用户消息。
- [ ] **Step 7:** 逐个识别已知瞬态异常，不开放未知桶；最后跑本任务定向测试（`EmptyReplyTerminalTest`、`UpstreamErrorRetryPolicyTest`、`CancelDuringRetryBackoffTest`、`ChatTurnFrameSequenceConformanceTest`、`AiChatHistoryRecorderTest`），全绿才算本任务收口。

Expected: 取消优先与帧序 conformance 全绿；未批准“失效替换”前不实现有半截自动重跑；服务端 unconfirmed 语义不被改坏。

### Task 5: 压缩阶段可见、独立保活与首帧超时

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:471-497,786-793`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:990-1053`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`
- Modify: `frontend/src/api/sse.ts:381-525`
- Modify: `frontend/src/stores/ai.ts:1410,5420-5439`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`（压缩状态条）
- Modify: `frontend/src/types/sse.ts`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/ContextFrameWiringTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/ResumeContextCompactionTest.java`
- Test: `frontend/tests/sse-watchdog.spec.ts`

**Interfaces:**
- Produces: 压缩 `phase=compacting` 阶段与独立 heartbeat；前端首帧/帧间分阶段超时；UI 仅显示真实已用时间。

- [ ] **Step 1（按 AR-06 定案）：** 首帧方案**已定**：把 `out.start` **前移到首轮压缩之前**（首轮压缩发生在 `run.create` 前：runId 已由控制器生成、快照尚未建立），并**必须同时定义“已 start 未建快照”阶段的语义**——reattach 返回“准备中”（可重试、带已用时间，不得返回 404 / “轮不存在”）、首帧超时从 start 起算且压缩期由独立保活维持（45s 看门狗不放宽）、该阶段异常仍走取消优先终态守卫（禁止裸 save / 禁止新建快照覆盖）。**不采用**另造“无快照前置阶段”协议（会新增帧或状态，破 16 帧冻结）。写红测：`phase=compacting` 开始帧由请求线程在**进入同步压缩调用之前**发出，同步调用自身不发任何帧；start 仍为首帧；压缩进行中的后续保活只由 Step 3 的独立定时器产生；上游无事件仍保活；park 后无新增帧。
- [ ] **Step 2:** 跑定向确认红；**按 AR-06 采用 start 前移**，据此明确更新 `ChatTurnFrameSequenceConformanceTest` 的精确序列与 replay 断言，不再声称旧帧序不变（16 帧**类型集**不变，只是首帧位置与阶段语义变）。
- [ ] **Step 3:** 服务端用独立、可取消的定时保活覆盖压缩/上游静默/慢工具（压缩期的心跳与已用时间只由该定时器出帧，不同步压缩调用不得被写成“自己会发帧”）；定时器在终态/异常/取消/**park（RunParkedException）**全部释放，park 收流后不得再写帧或扰 seq。
- [ ] **Step 4:** 前端补建连/首帧阶段，渲染“正在压缩·已用 N 秒”和压缩完成提示，不伪造百分比；跑定向和 typecheck。
- [ ] **Step 5（按 AR-11 定案）：** `app.ai.context.summary-model` 现为不生效的历史键（`AiProperties.Context` 仅保留绑定、`application.yml` 仍写空值）——**删除该键**（yml 空值行 + 属性绑定一并删），并在启动/加载时对仍写该键的外部配置给**一次** WARN（退役告警，说明压缩使用主模型，不重复刷）；**不恢复**独立摘要模型分支；文档不再宣称可切换。

Expected: 无上游事件时仍有保活；45s 看门狗不误杀正常压缩；后台标签节流不成为服务端保活依赖。

### Task 6: 修正确认卡语义、知情信息与终态披露

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:1251-1277`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/AiContext.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java:311-447`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: `frontend/src/utils/blocks.ts:318-369`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue:531-533,2850-2878,3107-3109,3498-3507`
- Test: `frontend/tests/blocks.spec.ts`
- Test: `frontend/tests/tool-outcome-envelope.spec.ts`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightGateTest.java`

**Interfaces:**
- Produces: `notApproved/notExecuted` 与 `approved/notExecuted` 区分；预检 payload 含影响面、条件摘要、权威发布模式；taskStatus 进入披露上下文。

- [ ] **Step 1:** 写红测：预检未通过不含“已批准”；批准后执行前冲突才显示“已批准·未执行”；COMPLETED 不披露 start_export。
- [ ] **Step 2:** 跑定向确认红，保留 DANGER 正常批准/拒绝/取消语义。
- [ ] **Step 3（6a，不依赖裁决）：** 后端补显式未进门结果，前端只修“未批准/已批准·未执行”语义；预检补配置项/上传文件/目标一致性，执行前复核保留。
- [ ] **Step 4（6a，不依赖裁决）：** 工具描述补前置条件并发 version；披露与执行共用终态判据；空 scope 在披露/执行同判，启动断言回调表 ⊆ 元数据表并把 scope 下限从 26 提至 37；跑定向测试。
- [ ] **Step 5（6b，按 AR-02 已解锁）：** 10-20“撤预检区”由 **AR-02** supersede，**范围严格三字段**：影响面（`impact`，后端已下发、前端现在不渲染）+ 条件摘要（后端在预检 payload 内补，不由前端反解文本）+ 权威发布模式（取宿主 `publishMode` 枚举，**模型文本不得作权威来源**）。三项以**只读摘要行**（可折叠、默认收起）呈现在确认卡内；**不恢复**旧预检区五段式、**不恢复**“查看详情”抽屉、**不新增**第二张卡。
- [ ] **Step 6（按 AR-10 已解锁）：** 24h **只做可见化**：卡面显示剩余时长或到期时刻（按既有读秒分档），到期给**可达出口**（重新发起 / 明确“已失效”），**不得**静默消失；**不改 TTL、不加续期、不缩短**。

Expected: 不把“披露可见”当授权，不把模型文本当发布模式权威来源；6a 可独立交付，6b 按 **AR-02** 实施（不再等裁决）。

### Task 7: 表单队列、级联反馈与结构化选择

**Files:**
- Modify: `frontend/src/stores/ai.ts:3595-3605,5356`
- Modify: `frontend/src/components/AiPanel/FormRenderer.vue`
- Modify: `frontend/src/types/form-schema.ts:632-683,755-773`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/FormOptionSource.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:159-212`（SYSTEM_PROMPT）
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:515-517,622-627`
- Test: `frontend/tests/gf-cascade-prune.spec.ts`
- Test: `frontend/tests/gf-draft.spec.ts`
- Test: `frontend/tests/parts-reducer.spec.ts`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormSchemaRulesTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormSubmissionRulesTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormProBatchRulesTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormRobustnessTest.java`

**Interfaces:**
- Produces: 同一时刻仅一个活动表单（不覆盖、无死卡，实时/恢复同构；第二张排队还是拒绝按裁决）；级联清值通知；空选项必填 schema 拒绝；表单场景/选项源按批准扩展。

- [ ] **Step 1:** 写红测：同批两表单不覆盖、恢复顺序一致、无死卡（第二张**按 AR-07 断言排队**：“第一张结算后第二张按到达序呈现、每一张都可取消”）；父值变化清值有提示；空选项必填不可提交。
- [ ] **Step 2:** 跑定向确认红，固定同一时刻只有一个活动表单但无死卡。
- [ ] **Step 3:** 实现“同一时刻仅一个活动表单、不覆盖、无死卡”与 schema fail-closed；第二张**按 AR-07 排队**（实时与恢复同构、不提供批量一次提交）；常用筛选用一屏，高级条件渐进披露，不强制一屏一问。
- [ ] **Step 4:** 按 AR-07 的同一口径扩展定义枚举选项源和提示词；清空条件使用显式动作并过风险确认，不与非法空值混用。
- [ ] **Step 5:** 跑定向测试；真模型自然语言触发表单采样留到获批验收，不能用桩替代服从率证据。

### Task 8: 工作区复位、动作串行与作业同步

**Files:**
- Modify: `frontend/src/stores/workspace.ts:144-177,259-274,419-470,583-630`
- Modify: `frontend/src/stores/task.ts:383-400`
- Modify: `frontend/src/stores/ai.ts:864-868,5356,5685`
- Modify: `frontend/src/views/DataBrowserView.vue:360-361`
- Modify: `frontend/src/views/AiSettingsView.vue:223`
- Modify: `frontend/src/views/TaskCenterView.vue`
- Test: `frontend/tests/workspace-audience.spec.ts`
- Test: `frontend/tests/workspace-run-job-publish-confirm.spec.ts`
- Test: `frontend/tests/ai-start-job-sync.spec.ts`
- Test: `frontend/tests/session-purge-reset.spec.ts`
- Test: `frontend/tests/task-center-view.spec.ts`

**Interfaces:**
- Produces: 页面进入完整上下文声明；同消息动作串行；仅 handled 成功记同步；restore_task 权威类型；start_publish 作业绑定。

- [ ] **Step 1:** 写红测：设置/数据页无陈旧 taskId，失败动作不报已同步，导入任务不进导出向导，发布作业绑定页面，handler 失败可见。
- [ ] **Step 2:** 跑定向确认红。
- [ ] **Step 3:** 实现复位、串行、成功才 touch、异步权威类型恢复和发布映射；导航后等待目标页就绪。
- [ ] **Step 4（按 AR-08）：** 任务中心刷新 = **事件后 + 仅在途 + 可见时**三条件同时成立才刷（AI 工具事件后、存在非终态作业、`visibilityState==='visible'`），并显示**数据截至时刻**；**不引入**常驻轮询 / 后台标签刷新 / 跨页签广播刷新；刷新失败进入错误位（不静默）。
- [ ] **Step 5:** 跑定向和 typecheck。

### Task 9: 按裁决实现 create_task 确认与幂等

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:278-312`
- Modify: `backend/src/main/java/com/example/configmgr/task/service/TaskService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`（仅确认门方案需要）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`（提示词）
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`（仅软确认方案需要）
- Test: `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsInvocationTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsThrowPathTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/ToolPreflightGateTest.java`

**Interfaces:**
- Produces: 创建前确认；宿主幂等键与单轮上限 1；幂等命中复用或明确冲突；不自动重复创建。

- [ ] **Step 1（按 AR-03 已定形态）：** 形态 = **DANGER 确认门**（进 `ToolPreflightService` 与既有确认卡链路），**不做软确认**；写“未批准不创建、拒绝不创建、同键不重复、单轮第二次不创建”红测。
- [ ] **Step 2:** 跑定向确认红。
- [ ] **Step 3:** 实现最小确认链 + **宿主幂等**（幂等键 = 规范化 `name` + `parentId` + 稳定参数哈希，**不得以标题为唯一依据**；命中即复用或返回明确冲突）+ **单轮上限 1**（第二次直接拒绝创建并给可读原因，不静默丢弃）；不由模型决定绕过确认。
- [ ] **Step 4:** 同步提示词、工具描述、测试和文档；跑定向测试。

Expected: create_task + navigate_to 的额外汇返被明确测试；不以全局“所有 WRITE 都确认”扩散改动。

### Task 10: 思考计时、文案与可访问性

**Files:**
- Create: `frontend/src/components/AiPanel/ReasoningHeader.vue`
- Modify: `frontend/src/utils/parts.ts:45-59,509-531`
- Modify: `frontend/src/types/ai.ts`（ReasoningPart timing）
- Modify: `frontend/src/components/AiPanel/AiPanel.vue:146-155,286-323,1844-1846,3093-3096`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`
- Test: `frontend/tests/ai-reasoning-frame.spec.ts`
- Test: `frontend/tests/ai-panel-parts-render.spec.ts`
- Modify: `scripts/ci/i18n-residue-baseline.json`（只允许基线下降）

**Interfaces:**
- Produces: `startedAt/endedAt/lastFrameAt`（服务端 atMs）；ReasoningHeader 自持计时；无起点不显示时长。

- [ ] **Step 1:** 写红测：实时/续跑/重连起止、无起点不显示、多段各自计时、aria 三项；历史已完成轮不伪造计时。
- [ ] **Step 2:** 跑定向确认红，保留现有摘要节点顺序，移除过时“帧不带时间”注释。
- [ ] **Step 3:** 实现段级 timing 和局部秒表；aria-live=off，补 log 可访问名、aria-busy、aria-expanded/controls。
- [ ] **Step 4（按 AR-12）：** 历史回放**不新增**后端字段 / 不改表 / 不改记忆 schema；历史已完成轮**不显示思考耗时**（不伪造、不按当前时刻回算）；实时 / 续跑 / 重连的计时**纯前端**（`startedAt/endedAt/lastFrameAt` 来自服务端 `atMs`）；历史思考按记忆里既有 `metadata.reasoningContent` 展示（有则展示、无则占位），**不为补全而新写**。
- [ ] **Step 5:** 跑定向、typecheck 和 i18n 残留检查。

### Task 11: 修复确定的渲染生命周期问题并量化性能

**Files:**
- Modify: `frontend/src/components/AiPanel/AiPanel.vue:1940-1974,2562-2587,3557-3690`
- Modify: `frontend/src/stores/ai.ts:217,870-907,1541-1559`
- Modify: `frontend/src/utils/blocks.ts:181-210`
- Modify: `frontend/src/utils/tool-summaries.ts:75-85`
- Modify: `frontend/src/utils/markdown.ts`（mermaid 水合、渲染器生成围栏 key、净化白名单）
- Modify: `docs/03-技术方案文档-v2.1.md`（15 种高亮语言与降级口径）
- Test: `frontend/tests/markdown-render-perf.spec.ts`
- Test: `frontend/tests/ai-panel-scroll-follow.spec.ts`
- Test: `frontend/tests/parts-double-write.spec.ts`

**Interfaces:**
- Produces: expanded 生命周期迁移；组件层 mdCache LRU；ToolPart WeakMap 派生缓存；围栏 key 只由渲染器生成、模型原生 HTML 不得占用同槽属性；>1 万字符降级给一次性提示；性能测量记录，不预设虚拟化。

- [ ] **Step 1:** 写红测：收起/展开后 RO、横滚和 mermaid 重新工作；mdCache 命中刷新 LRU；同长不同文不误命中；ToolPart 单次解析复用；模型正文自带 `data-fence-key`（原生 HTML 或围栏属性）不得成为块身份、不得与渲染器生成的槽位撞车；>1 万字符降级只提示一次。
- [ ] **Step 2:** 跑定向确认红，再实现观察器迁移、pending 清理和缓存边界。
- [ ] **Step 3:** 收敛布局读取与镜像写入；配额失败一次性可见；mermaid 在可见且 idle 时水合，不宣称 Promise.race 可中断同步渲染。同批修渲染规格三件：围栏 key 只允许渲染器按围栏出现序自增生成，`html:true` 透传的模型原生 HTML 不得携带/覆盖 `data-fence-key` 等渲染器槽位属性（白名单不向模型来源节点放行该属性，由渲染器在净化后回填；其余原生 HTML 仍按既有 DOMPurify 白名单收窄）；>1 万字符降级（`DEGRADE_AT_CHARS`）目前对用户完全静默，补一次性提示且不逐帧刷屏、不粘滞到下一条。
- [ ] **Step 4:** 用真实长会话做 profile（需用户单独批准浏览器验证），记录消息数、DOM、P50/P95/max 帧耗时、长任务和内存；未测前不改虚拟化决策。
- [ ] **Step 5:** `docs/03-技术方案文档-v2.1.md` 的高亮语言表逐条对照 `markdown.ts` 已注册清单（javascript/typescript/java/python/sql/json/bash/yaml/xml/css/markdown/diff/go/csharp/kotlin = 15 种）书写，降级口径写成“按条 >1 万字符关高亮与 mermaid、结构照渲 + 一次性提示”，不列未注册语言。
- [ ] **Step 6:** 跑定向和 typecheck；只有测量超既有阈值才另提虚拟化方案，不预选依赖。

### Task 12: Q-08 与剩余恢复边界

**Files:**
- Modify: `scripts/verify-e2e.ps1:3993-4008`（仅维持 park 语义方案）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/RunLivenessTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/web/AiControllerZombieGateTest.java`
- Test: `backend/src/test/java/com/example/configmgr/job/service/ZeroItemJobTerminalTest.java`（D7 复验复用，只读不改断言）
- Test: `scripts/ci/cluster3-probe.mjs`（真实三实例专项，验收期）

- [ ] **Step 1（按 AR-04）：** 产品语义**维持**（park 后释放会话门，续跑方用同一 runId 重新取门）。把 Q-08 改成**确定语义**的用例：断言①无幻影气泡 ②历史条数不变 ③前端无假提示；**历史检查不得排在**可能提前抛出的 409 之后；"挂起期同会话必 409"这一断言**移除**（现行语义下不可确定）。
- [ ] **Step 2（按 AR-04，必做）：** 409 面另立**可控时序**用例（门确定持有的窗口内），覆盖 409 与 `runId/reattach` 回执，不靠 sleep/重试制造通过；**不采用**跨 park 持锁。
- [ ] **Step 3:** 为运行期死轮增加受租约和僵尸年龄约束的低频扫描，写“活跃不接管、挂起已决可接管、失败终态不复活”定向测试。
- [ ] **Step 4（D7 复验，只验不改）：** 按当前代码与真实作业边界复验零条目：`start_*`（`start_export`/`start_job` 等）在零条目时不得把作业判成功——`ExportJobRunner`/`ImportJobRunner`/`PrecheckJobRunner`/`PublishJobRunner` 的 BZ-01 守卫须落成显式失败终态 + 可见错误计数 + 作业级 issue，且“未载入 / 真实 0 行 / 读取失败”三者可区分；复用 `ZeroItemJobTerminalTest` 跑一遍并按真实作业边界（含 AI 触发的 `start_*`）核对回执。下载侧的区分已归 Task 2，本步不重做；只有取得真实边界证据才把台账 D7 改为“已验”，否则保持“已修待复验”。
- [ ] **Step 5（L-150 / D8，本地证据 ≠ 三实例证据）：** D8 的本地确定性测试（`SpToolCallingManager`、`RunStore` 用例）只作**本地证据**，不得用 cluster 16/16 或本地绿反推三实例；在获批验收环境用 `scripts/ci/cluster3-probe.mjs` 显式执行 L-150 三格确定性三实例用例，实际跑出三格结果才记为闭环，未执行一律保持未闭环。
- [ ] **Step 6:** 定向验证后，主 CI 在同一 SHA 重跑到三次独立成功；Q-08 与 L-143 不合并归因。

### Task 13: 文档同步与分级验收

**Files:**
- Modify: `docs/02-需求设计文档-v2.2-冻结版.md`
- Modify: `docs/03-技术方案文档-v2.1.md`
- Modify: `docs/AI对话日志链路追踪手册.md`
- Modify: `docs/任务看板.md`、`docs/决策看板.md`
- Create: `docs/evidence/ai-interaction-remediation-<执行日期>/README.md`（执行时建立）

- [ ] **Step 1:** 每个已完成批次回写真实 commit、定向测试和未验边界；未做项保留，不改历史数字。
- [ ] **Step 2:** 统一 6h/24h/完整历史/park/去超时/重试/压缩/表单/创建确认口径（**按 AR-01，两类保留期分开写**：完整历史功能级已存在；**Redis 运行态 / 记忆 = 24h 滑动**；**既有完整历史 DB 无 TTL、维持现状且不扩大**；失败现场不新增 DB 写入（镜像 + Redis 快照）；**按 AR-10**：Redis 侧 24h 只可见化、该 TTL 不变、armed 跨 park 累计；**按 AR-11**：`summary-model` 已删键 + 退役告警，不再宣称可切换；**按 AR-05 / AR-09 / AR-17**：继续 / 重试本轮本批交付；普通消息级 regenerate 本批不做、不写"已支持"、A7 保留缺项）。本步只负责文档与配置示例同步。
- [ ] **Step 3:** 用户批准全量后运行后端、前端、build、文档门禁；Maven 禁止 clean。

Run（shell=Git Bash，cwd=`backend`）: `export IDEA_MAVEN="<IDEA_MAVEN>"` 后 `"$IDEA_MAVEN" -o test`
Run（cwd=`frontend`）: `npm test && npm run build`
Run（cwd=仓根）: `node scripts/ci/check-docs.mjs`

- [ ] **Step 4:** 按用户批准运行五工作流；e2e-real 两 job 必须实际执行，主 CI 三次独立成功才记达成。
- [ ] **Step 5:** 单列真实 PostgreSQL、生产 Redis Cluster、真实 Lettuce 中断、L-150、L-143 的覆盖证据；缺失则明确未验，不以 H2/环境级冒烟代替。
