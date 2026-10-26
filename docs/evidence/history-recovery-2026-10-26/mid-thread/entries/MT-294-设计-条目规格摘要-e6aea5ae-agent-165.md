<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-294 · 设计 · 条目规格摘要（e6aea5ae / agent-165）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ConfirmGate`、`backend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m76` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 3 条有正文），末条有正文消息为 `m87`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e6aea5ae` / agent `agent-165`（`main` = 主会话）；原字符 3,666（链内 1 段、合计 3,666）。
> **链号 / 类别**：`MT-294` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m87`（1,190 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 条目规格摘要

- **T3-3**：导出作业的项级 catch 与外层作业级 catch 都要落 `validation_issues`（severity=ERROR、defCode 必有、rowKey/fieldCode 留空、message=异常类名+摘要且列宽 1024 用足），写前先 `deleteByJobId` 幂等；「前端零改动」只是【推断】，须 smoke 确认 `JobProgress` 分组渲染。
- **T3-4**：激活 `Job.resultJson` 死列，`PrecheckJobRunner` 在数据面事务内写 `{defCode:{fileType:{sha256,size}}}` 指纹；`JobService#assertPrecheckPassed` 仅对 IMPORT 比对（PUBLISH 维持状态守卫），不一致或历史无指纹一律 409 `PRECHECK_STALE`，指纹用流式 digest。
- **T3-6**：6a 采红队第 4 方案——`ConfirmGate#submitDecision`/`#submitFrontendResult` 在 `woke=false` 且快照 SUSPENDED 时触发 `ResumeService.resume`，把死卡收敛为真实续跑（按 R4 三缺口施工）；6b 新造 CONFIRM 卡完整 E2E（真实挂起→整页刷新→`/runs/current` 重建→confirm→收敛），27→28+。
- **T3-7**：统一终态全集 DONE/FAILED/CANCELLED（+rejected 归 FAILED 口径）为唯一判定函数，消除散点字面量比对；§13.2 #3 原文问的是「空回复即失败是否采纳」，与设计卡表述不完全重合。
- **T3-8**：`session-ttl` 6h 为绝对上限，续期不得突破「创建时刻+6h」；先实测 `touchActivity` 等 7 处续期是否突破，突破则加绝对上限字段。
- **T3-9**：裁定 Redis 淘汰策略 noeviction（AI 键宁报错不丢帧），落「部署文档与 docker/配置」并验 `ai:seq` 耐久性——**仓库内无 docker/、无部署文档，落点缺失**。
- **T3-10**：`ai:events` 窗口维持 3000，另裁单帧上限（初裁 64KB/帧，超限截断+标记），快照 JSON 同裁；R5 硬约束=必须字段级截断，严禁帧字节级截断。
- **S3-4**：delta 不入档后断流期正文尾部不可由回放复原，只保证状态帧 seq 无洞，尾部能否补齐取决于前端兜底路径，归 T3b 确认且「不改设计口径，仅登记后果」。

## 含糊点清单（26 条，详见草稿附录 A，含预选与推荐）

- A1 T3-3｜作业级 issue 的 `defCode` 取值未定（列 NOT NULL），且按 defCode 分组的前端明细区根本显示不出作业级 issue。
- A2 T3-3｜「施工前 smoke」形态未定（是否必须驱动浏览器），是否计入验收未定。
- A3 T3-3｜导出作业每轮新 jobId，`deleteByJobId` 幂等是否仍强制。
- A4 T3-4｜目标 fileType（UPLOAD）指纹缺失时的处置未定义。
- A5 T3-4｜`PRECHECK_STALE` 是全仓零现存量的新机器可读码，前端/文档是否需同步未说明。
- A6 T3-4｜摘要写入粒度（每项即时 vs 收尾一次性）与项级失败 defCode 是否入摘要未写明。
- A7 T3-4｜必受影响的 `PrecheckGateGuardTest#latestPassingPrecheckAllowsImportAndPublish` 改法未指定（补指纹 vs 改断言 409）。
- A8 T3-6a｜「本进程无该 runId 活跃等待」的判据源未定（`ConfirmGate#latches` 私有、无 runId 级查询 API）。
- A9 T3-6a｜异步触发载体未定（suspendExecutor 满 → 503 vs 投递池满 → 摘除订阅者）。
- A10 T3-6a｜confirm/frontend-tool-result 响应体是否新增「已触发续跑」字段未定。
- A11 T3-6a｜`dismiss`（前端放弃，终态 FRONTEND_CANCELLED）路径是否也触发未定。
- A12 T3-7｜#3 原文「空回复即失败是否采纳」未表态（现状空文本仍置 DONE），与设计卡表述不一致。
- A13 T3-7｜「rejected 归 FAILED 口径」指哪一层不明（后端无轮次级 rejected）。
- A14 T3-7｜唯一判定函数的落点与可见性未定。
- A15 T3-8｜「创建时刻+6h」的「创建」主体（run 有 createdAtMs / session 无载体）未定义。
- A16 T3-8｜若实测「不突破」是否就不实现（是否允许「实测结论 → 不实现 + 登记」）。
- A17 T3-9｜noeviction 落点缺失（仓库无 docker/、无部署文档），是否新建文档需裁决。
- A18 T3-9｜`ai:seq` 耐久性验证方式未定（是否要求 Memurai 实跑 `CONFIG SET/GET` 留原文）。
- A19 T3-10｜64KB 判定口径未定（整帧 JSON 字节数 vs 单字段值字符数）及截断计算方式。
- A20 T3-10｜「单键消息体上限」是否另设，与「窗口 3000 × 单帧 64KB（≈192MB）」的关系未说明。
- A21 T3-10｜truncated 标记字段名/前端契约未定，且与 `ToolResultLimiter` javadoc「前端帧全文不裁剪」的旧承诺冲突。
- A22 S3-4｜「S3-4」在仓库内有三处同名异义（T3-2 后果注记／排期计划终态索引观察项／T3-3 前端零改动是推断），本棒要确认哪一个。
- A23 S3-4｜验收口径未定（只「确认+登记残余风险」还是要求「补齐正文尾部能力」）。
- A24 全局｜基线口径冲突：设计卡门禁总则仍写 263，排期计划注记与任务书写 284。
- A25 T3-6b｜新用例编号/计数归属（27→28+ 的落法）与「整页刷新」前端断言是否必须真实浏览器未定。
- A26 T3-7~10｜裁定结论是否像 T3-5 那样回灌技术方案 §13.2／ADR 定值表并标闭环，设计卡只说「落配置/文档」。

## 草稿路径

- 施工卡草稿：`<LOCAL_WORK>/t3b-reports/T3b-施工卡草稿.md`（62KB；自包含提示词：硬约束 H1~H8／环境条款／基线确认／逐条规格原文摘录（含 R4/R5/R6 与 §13.2#3~#6 原文）／八组代码锚点／五节施工步骤／门禁总则／报告模板／DoD／附录 A 26 条待裁决-预审）
- 勘察报告：`<LOCAL_WORK>/t3b-reports/第1棒-T3b勘察与草稿报告.md`

## 需要指挥官注意的事实

- 本棒**只读 + 两次 Write 到仓库外**，未做任何 git 写操作；仅跑了只读 `git status`/`rev-parse` 核验。
- `git rev-parse --short HEAD` = `1a28216`【实测】，与任务书一致。
- **工作区当前不干净**：有 5 个未跟踪文件属 **M2-T3-5 压测棒在途产物**（`docs/evidence/probes/M2-T3-5-Probe{Delta,Lock,Matrix,Replay}.java` + `backend/src/test/java/.../ai/conformance/ThreeArgAssemblyConformanceTest.java`），其中那个 conformance 测试**可能改变测试计数**。施工卡 §3 已写入提醒：T3b 施工棒应在 T3-5 闭环后再开工（或由指挥官明确并行放行），且不得把在途产物计入 284 基线。
- 本棒**未跑** `mvn test`／`yarn typecheck`／E2E、未起后端与 Redis，故 284 基线为【旁证】（排期计划 v1.6 闭环注记 + T3a 施工报告 XML 口径），施工棒须自跑复核。
- 设计卡「门禁与验收总则」的「基线 263」为过期未同步（A24）；设计卡 T3-4 验收里点名的 `PrecheckGateGuardTest` 实际有 8 个 `@Test`，「五分支」指其中 5 个拒绝分支。
