<!-- 第 19 期补档入库 · R-137 · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-DSPro.md`（8007 B，sha256 `b38246b63106a7d17a5f542bcb0fa4a80e206a082aa004a613b67d0aad3d0a4b`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-137** 决策卡与会审报告补档（第 19 期入库）；基线 HEAD = `45b2c6a`。

# R-137 决策卡 · 独立会审报告（DSPro 方）

- 会审对象：`r137-guard/会审卡-R137.md`，sha256 = `8ef374a6088bbc9b12b4b0200b6d4b2fd4b9c0fae8b3d85f70bf293f0cf75990`，与任务书给定值**一致**（sha256sum 实测）。
- 基线核实：主仓 `<MAIN_REPO>` HEAD = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`（= 45b2c6a），与卡声明一致。
- 署名：实际运行模型 **DeepSeek（deepseek-v4-pro，经 Kimi Code 主代理）** · 会话 id **DC-19** · 日期 2026-10-12。
- 评议方式：卡外独立实读（AiTools/JobService/TaskService/PrecheckJobRunner/JobTerminalWriter/SpToolCallingManager/ConfirmGate/SseChatEmitter/workspace.ts/types/ai.ts/frontend-tools.ts/m2t2a-smoke.mjs/verify-e2e.ps1），无其它会审方结论参考。

## 逐条评议

**D1 守卫单点下沉 —— 采纳（前提 D2 修正）。** 单点下沉正确：`createAndStart` 是 REST 与四件 start_* 的共同入口（JobService:62-78），一处覆盖双通道与未来调用方；范式 A（落库前抛、参照 `assertNoJobInFlight` :113-122）正确，被拒不留 PENDING 行。但终态判定口径须按 D2 修正，否则守卫直接堵死导入续作。

**D2 终态集 = {COMPLETED, CANCELLED} 拒绝、FAILED 放行 —— 驳回（终态集须修正）。** 本卡最大隐患。卡称"与前端 `isCurrentTaskReadOnly` 口径对齐"不成立，且后端比前端更严：
- 前端口径（workspace.ts:345-357）对 COMPLETED 是"按类型核最终作业"——仅当最终作业（导入=PUBLISH、导出=EXPORT）COMPLETED 才判只读；对 FAILED 不判只读。注释自述："导入侧预检查 0 错误即置 COMPLETED、但流程仍可继续"。
- 已实读确认：`PrecheckJobRunner:267-274` 预检查 0 错误 → COMPLETED → `JobTerminalWriter.complete:84` → `applyJobOutcome(taskId, COMPLETED)` → 任务置 COMPLETED；而 `goToStep` 只改 currentStep 不改 status（TaskService:142-150）。
- 故按 D2 原文，预检查通过后（任务已 COMPLETED）的 `start_import`/`start_publish` 会被 409 拒掉，**导入流被打断**；且 AI 通道（走 createAndStart 守卫）与手工通道（前端 `run_job` 走 isCurrentTaskReadOnly 放行）分叉——手工能导、AI 不能导，恰是本卡要消除的漂移。
- **修正方向**：CANCELLED 一律拒；COMPLETED 仅当"最终作业已 COMPLETED"才拒（把前端最终作业判据下沉到后端，守卫按任务类型拉最终作业）；或修任务状态机（预检查通过不应置终态 COMPLETED）。FAILED 放行**可接受**（与前端一致，且 assertNoJobInFlight/assertPrecheckPassed 仍兜底），不构成新洞。

**D3 拒绝语义 —— 采纳（附注）。** `ConflictException("TASK_FINALIZED", …)` → 409、机器码随 message，范式同 `JOB_ALREADY_RUNNING`。附注：409/GlobalExceptionHandler 只作用于 REST 通道；AI 通道上该异常被 `executeOnce:360-363` 捕获，变成 `ok=false` + "工具执行失败：任务 #id 已 <status>（TASK_FINALIZED）"。机器码随文可见、可行，但"供 AI 通道判读"应理解为此文案形态而非结构化码。

**D4 goToStep 双守卫 —— 有条件采纳。** ①终态守卫须同步 D2 修正，否则预检查通过后 goToStep 到 IMPORT/PUBLISH 步骤同样被拒。②step 白名单"由施工卡定"可接受；但降级路径（仅状态+非空）下 goToStep 仍可落任意 step 字符串（TaskService:146 无校验），currentStep 可漂移到非法值——建议降级时把"step 白名单"登记为观察项并随 D8 补用例。

**D5 前端 goto_step 补守卫 —— 采纳。** 与 F6 五处（workspace.ts:408/422/435/445/460）同构，在 :392 分支前插判定，正确低风险，与后端 D4 双端一致。

**D6 八处 catch 改重抛 —— 有条件采纳（限缩范围+计数修正）。** 诊断正确：`executeOnce:356-363` 仅当工具方法**抛异常**才置 ok=false；四件 start_* 用 `catch(Exception){return "启动X失败:…"}` 吞异常 ⇒ `tool_result{ok:true}` 而内文写"失败"。但：
- **计数错**：AiTools 实为 **9 处** catch-return，卡漏掉 createTask 第二处 `:255 catch(Exception) return "创建任务失败:…"`（8 处清单各锚点本身命中，:255 未列入）。
- **范围过宽**：`getConfigDef`(:126)/`getWorkspaceState`(:181)/`checkJobStatus`(:196) 的"未找到/不存在/查询失败"对只读工具是**正常负结果**，统一改抛会把良性 not-found 转成 ok=false，劣化模型处理。应把改抛范围收敛到 4 个 DANGER start_*（及 createTask 参数校验分支），只读工具保留友好文案。
- 风险注记"呈现形态须施工期实测"正确，保留。

**D7 N-6 修复 —— 部分采纳（三子项归属与语义须澄清）。**
- ①"挂起帧 timeoutSeconds 收编至配置键"：**诊断对象错位**。生产挂起帧已由 `ConfirmGate.frontendToolTimeoutSeconds():180-182` → `app.ai.hitl.frontend-tool-timeout` 驱动（SseChatEmitter:599-608 入帧），非硬编码。硬编码 `timeoutSeconds:120` 只在测试脚本 `m2t2a-smoke.mjs:143-145`（frameExpr 注入侧）。收编方向正确（注入侧读事实源，与 verify-e2e.ps1:1395"不硬编码 120"纪律归一），但应定性为**测试注入侧收编**。
- ②"阶段 2 轮询 12×500ms 随键/常量"：该轮询是 smoke 脚本等"取消收敛"的**本地轮询**（实读 `:308`），语义 ≠ `frontend-tool-timeout`（服务端 HITL 回执上限）；强行并入同一键语义错误，应作脚本本地常量或独立标注，不入 app.ai.hitl.*。
- ③"ConfirmGate 非取消回灌按实际结果置 ok"：方向可接受但属**两端契约改动**，且锚点漂移（硬编码 `frontendToolResult(pending, true, …)` 实读在 **:491**，非卡称 :478）。前端回灌体 `FrontendToolResultRequest`（types/ai.ts:66-78）**不含 outcome/ok 字段**：前端虽已算 `outcome: ok|failed`（frontend-tools.ts:919-922）但 POST 只传 result/source（ai.ts:1200-1204），后端无从"按实际结果"取值。须先扩前端契约上传 outcome；且 ok 现有语义=回灌成功/调用收敛（非业务成败），直接改会波及 parts.ts 归约与 frontend-tool-result-terminal.spec.ts 回归锁。建议不硬改 ok，而把前端 outcome 折入 result 文本或独立帧字段。

**D8 补测随批 —— 采纳（补一用例）。** MAJOR-2/3 合理。**漏项**：须补"预检查通过后（任务 COMPLETED）start_import/goToStep(IMPORT) 不被拒"的回归用例，作为 D2 修正案锁定测试。

## 新增风险点与漏项

- **R1（D2/D4 连带）**：终态守卫按原文落地会打断导入流并造成 AI/手工通道分叉，是唯一会"拒掉正常业务"的项，优先级最高。
- **R2（锚点漂移）**：卡声明"全部锚点按 45b2c6a 实读核实"，但 `12×500ms` 实际在 m2t2a-smoke.mjs:**308**（卡写 :294），ConfirmGate ok=true 实际在 **:491**（卡写 :478）。建议施工前重读。
- **R3（TOCTOU）**：assertTaskNotFinalized 检查→落库之间任务仍可能被并发作业置终态，与既有 assertNoJobInFlight/assertPrecheckPassed 同类，属可接受残留，建议记录不闭合。
- **R4（FAILED 中间态）**：FAILED 放行下，IMPORT 失败后 start_publish 仍可经 assertPrecheckPassed（仅要求存在通过预检查，不要求导入成功）发布部分暂存数据。属既有面，D2 不使其恶化，建议登记观察项。
- **R5（D6 范围外同类）**：createTask `:255` 吞 Exception 的 ok:true+失败文案与 D6 同病，D6 不改则它仍在。

## 结论

D1/D3/D5/D8 采纳；D2 驳回（终态集改为"CANCELLED 一律拒 + COMPLETED 按最终作业判拒"）；D4 有条件采纳；D6 限缩范围+计数修正后采纳；D7 部分采纳（① 改定性为测试注入侧收编，② 轮询不入配置键，③ 需先扩前端契约且慎改 ok 语义）。D2 是本卡唯一会造成正常业务回归的点，须先行修正再施工。
