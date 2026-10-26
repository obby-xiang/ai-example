> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-591` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：10577 B / 7385 字符 / 103 行；行指纹覆盖率（仓内 + 落盘语料）11.8%
> - 类别：**施工复核**
> - 脱敏：路径 2 / 会话标识 2 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. AiTools 四件 `start_*`（无任何任务状态守卫）

锚点：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:280-342`

| 方法 | 行 | 签名 / 注解 | 守卫 |
|---|---|---|---|
| `startExport` | 280-291 | `@Tool("start_export") @ToolScope({"page:tasks","task:EXPORT/EXPORT"}) @ToolRisk(DANGER)` `public String startExport(@ToolParam Long taskId)` | 无 |
| `startPrecheck` | 300-311 | `@ToolScope({"page:tasks","task:IMPORT/PRECHECK"}) @ToolRisk(DANGER)` `startPrecheck(Long taskId)` | 无 |
| `startImport` | 318-329 | `@ToolScope("task:IMPORT/IMPORT") @ToolRisk(DANGER)` `startImport(Long taskId)` | 无 |
| `startPublish` | 331-342 | `@ToolScope("task:IMPORT/PUBLISH") @ToolRisk(DANGER)` `startPublish(Long taskId)` | 无 |

四件方法体同构（`:283-291` 为例）：

```java
public String startExport(@ToolParam(description = "任务ID") Long taskId) {
    try {
        Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        return String.format("导出作业已启动（作业 #%d），请稍后查看进度", job.getId());
    } catch (Exception e) {
        return "启动导出失败: " + e.getMessage();
    }
}
```

关键调用 = `jobService.createAndStart(taskId, JobType.{EXPORT,PRECHECK,IMPORT,PUBLISH})`；四行分别落在 `:286` / `:306` / `:324` / `:337`。
下游守卫（`JobService.java:78-100`）：`createAndStart` 只有 `assertNoJobInFlight(taskId, jobType)`（`:79`，同类在途互斥）与 `assertPrecheckPassed(taskId, jobType)`（`:80`，仅 PRE-CHECK 前置），**两者都不看任务状态**。
> 现状判断：任务 `COMPLETED/CANCELLED` 上四件 `start_*` 经确认门放行后仍会新建作业并落库写数据——与 R-137 登记原文一致。

## 2. R-97 W-05：AiTools 8 处 catch 旁路

`AiTools.java` 全文件 `catch` 共 **9 处**（grep 实测），按「吞异常 + 返回中文文案」计数为 **8 处旁路**（第 9 处 `:253` 是 `IllegalArgumentException` 分支，属同一 `createTask` 旁路组）：

| # | 锚点 | 摘录 |
|---|---|---|
| 1 | `:126-128` | `} catch (Exception e) {` / `return "未找到配置定义: " + code;` |
| 2 | `:181-183` | `} catch (Exception e) {` / `return "任务 #" + taskId + " 不存在或无法访问";` |
| 3 | `:196-198` | `} catch (Exception e) {` / `return "查询作业失败: " + e.getMessage();` |
| 4 | `:253-257` | `} catch (IllegalArgumentException e) { return "任务类型不合法：…"; }` / `catch (Exception e) { return "创建任务失败: " + e.getMessage(); }` |
| 5 | `:288-290` | `} catch (Exception e) {` / `return "启动导出失败: " + e.getMessage();` |
| 6 | `:308-310` | `} catch (Exception e) {` / `return "启动预检查失败: " + e.getMessage();` |
| 7 | `:326-328` | `} catch (Exception e) {` / `return "启动导入失败: " + e.getMessage();` |
| 8 | `:339-341` | `} catch (Exception e) {` / `return "启动发布失败: " + e.getMessage();` |

> 现状判断：8 处均以字符串回填、不重抛，框架既有 `ok=false` 通道（`SpToolCallingManager#executeOnce` 仅在抛异常时置位）被永久旁路 ⇒ `tool_result{ok:true}`、工具卡落 `succeeded`，而卡片内文写着「启动导出失败」——即 R-97（W-05）原文所指病灶。

## 3. `TaskService.goToStep`（无守卫）

锚点：`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:142-150`

```java
@Transactional
public Task goToStep(Long taskId, String step) {
    Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
    task.setCurrentStep(step);
    task = taskRepository.save(task);
    publishTaskChanged(task, "跳转到步骤: " + step);
    return task;
}
```

> 现状判断：**零守卫**——既不校验任务状态（`COMPLETED/CANCELLED` 照改）、也不校验 `step` 是否在合法集内，只有「任务不存在」这一条 `orElseThrow`。

## 4. 前端 `goto_step` 的 `ui_event` 守卫（仍缺）

- 守卫工具函数：`frontend/src/stores/workspace.ts:345-357` `isCurrentTaskReadOnly()` —— `CANCELLED ⇒ true`；`COMPLETED` 时按类型核「最终作业」（IMPORT→PUBLISH / EXPORT→EXPORT）查 `jobsApi.listJobs` 取 `latest.status === 'COMPLETED'`。
- `goto_step` 分支：`workspace.ts:392-401`

```ts
case 'goto_step': {
  const taskId = workspace.taskId
  if (taskId === null) { return fail('当前没有进行中的任务，无法跳转步骤') }
  await tasksApi.goToStep(taskId, event.step)
  workspace.setStep(event.step)
  await taskStore.refreshCurrent()
  return ok(`已跳转到步骤 ${event.step}`)
}
```

- 对照：F6 已补守卫的 5 处 —— `select_defs`(`:407-410`)、`set_conditions`(`:421-424`)、`set_import_mode`(`:434-437`)、`run_job`(`:443-447`，并在 `:444` 注释「后端终态守卫另见 R-137 登记」)、`cancel_job`(`:458-462`)。
> 现状判断：`goto_step` 是唯一**无只读守卫**的写事件分支（收口修 F6 按红队 minor-4 只补 5 处、未扩围），与 R-137 补充裁决「goto_step 前端 ui_event 守卫并入本批」对齐。

## 5. 台账登记原文（`docs/M2-排期计划.md`）

- **R-97（W-05/W-06/W-07）** → `:289`，逐字关键句：「① 后端 `AiTools` 8 处 `catch (Exception e) { return "<中文失败文案>" }` 吞异常 ⇒ 框架既有 `ok=false` 通道…被永久旁路 ⇒ `tool_result{ok:true}` ⇒ 前端工具卡落 `succeeded`，而卡片内文写『启动导出失败: …』（模型能从文本自救，受损的是**人**的卡面判读）」；行尾另注「`ConfirmGate.java:478` `submitFrontendResult` 非取消回灌硬编码 `ok=true`（N-6，批 A 复核登记）」。状态 = 「立项（批 C · 并入 R-84/对话时序包③a 一并设计）」，**W-05 单独仍未施工**（W-06/W-07 已由批 3③a 闭环）。
- **N-6** → `:412`（小批苞 G 登记节，`:406` 起）：「`frameExpr` 手造的 `generative_form` 挂起帧内写死 `timeoutSeconds:120`（现态实读 `:143`）；阶段 2『取消→收敛』轮询写死 12 次 × 500ms（现态实读 `:294`）… 必须点名的口径冲突：苞 A 拆键后该 `120` 不再对应任何配置键…而 `scripts/verify-e2e.ps1:1395` 恰恰执行相反纪律『不硬编码 120』」。
- **R-137** → `:326`：「**落点** = `AiTools.java:280-342` 的 `start_export`/`start_precheck`/`start_import`/`start_publish` 四件…直调 `JobService.createAndStart`（`JobService.java:78-100` 仅按作业类型的在途互斥 + 预检查前置守卫），**无任何任务终态守卫**——已 COMPLETED/CANCELLED 任务上 AI 经确认门调 `start_*` 仍可新建作业、落库写数据」；携 MAJOR-2/MAJOR-3 补测项；行末补充：「`goto_step` 前端 ui_event 守卫…与后端 `TaskService.goToStep` 状态守卫并入本批」。状态 = 已登记·待排期。

## 6. 红队 MAJOR-2 / MAJOR-3 原文

**位置更正**：`<LOCAL_WORK>\polling-gov\` 下只有「轮询一期」批的报告，**不含 MAJOR-2/3**（grep 零命中）；原文实际在 **`<LOCAL_WORK>\step-wizard-pkg\红队报告.md:48` 与 `:57`**。

- **MAJOR-2（`:48-55`）**「AI 后端高危工具绕行只读——『AI 通道零写』是前端专属」：落点 `AiTools.java:280-291 / :300-311 / :318-329 / :331-342`；证据（【实测】）如上 §1；「为何结构性测不到」= 四件工具是**后端执行**（无 `@ToolChannel(FRONTEND)`），GLM 的零写 CDP 探针「结构上无法观察后端 AI 工具的执行」；定性 = 既有后端行为、非本批回归，但施工卡 §S3e 验收口径「只读态 AI 侧任何驱动路径都不产生写请求」**未达成**。**建议 = 「后端 `createAndStart` 增加『任务已终态则拒建作业』守卫（或 `start_*` 工具入口判任务态）；或指挥官显式接受『只读=前端只读』并升级登记表述」**——即运行期补测要求（在已结束任务上实跑 `start_*` 可达性）。
- **MAJOR-3（`:57-64`）**「`SpreadGrid` 无 `watch(readOnly)`，导出完成后场内翻只读仍可编辑」：落点 `SpreadGrid.vue:216-221`（`applyReadOnly()`）、调用点 `:165/:248/:291`，`:36` import 无 `watch`；触发链 = `ExportWizardView.vue:704-720` `onFinal` 先 `refreshResults()`（`isReadOnly` 仍 false）后 `getTask`（翻 true）⇒ 已加载表格 `isProtected` 仍 false；违背 §3-S3d「敲键盘改单元格断言改不进去」；边界 = **仅内存可编辑、零落库**。**建议 = `SpreadGrid.vue` 增 `watch(() => props.readOnly, applyReadOnly)`（约 3 行）**；补测要求 = 浏览器断言「只读态敲键盘改单元格改不进去」（F2 已修代码，补测随 R-137 复跑）。

## 7. 后端「状态/终态守卫」范式参照（1~2 处）

- **范式 A（拒绝 + 机器可读码）**：`job/service/JobService.java:113-122` `assertNoJobInFlight` —— 先读在途作业，命中即 `throw new ConflictException("JOB_ALREADY_RUNNING", "任务 #… 已有同类作业在途…")`；`:73-75` 注释点明「两者都在**作业落库之前**抛出，故被拒的请求不会留下 PENDING 作业行」+ 由 `GlobalExceptionHandler` 转 409。可直接套用为 `assertTaskNotFinalized(taskId)`。
- **范式 B（按任务状态判定 + 幂等）**：`definition/service/DefinitionService.java:124-128` —— `long activeTaskRefs = taskItemRepository.countByDefCodeAndTaskStatus(code, Task.TaskStatus.ACTIVE); if (activeTaskRefs > 0) throw new ConflictException("DEFINITION_IN_ACTIVE_TASK", "…被 N 个进行中任务选中，请先结束或删除这些任务");`；互补范式见 `TaskService.applyJobOutcome`（`:215-234`，终态 switch + 「目标态与当前态相同则不动库不发事件」幂等）。状态枚举为 `Task.TaskStatus { ACTIVE, COMPLETED, CANCELLED, FAILED }`（`task/entity/Task.java:57`）。
> 现状判断：后端**已有**「作业互斥 / 定义被活跃任务占用」两类守卫范式，但**无任何**「任务已终态则拒建作业」的守卫；AiTools / TaskService 内均无此类现成方法可复用，须新建。

---
自证：本次运行模型 = `deepseek-flash`，modelAlias = `DeepSeek/deepseek-flash`（provider `openai`，thinkingEffort `high`，profile `explore`；取自 `~/.kimi-code/sessions/<WORKSPACE>/session_<KIMI_SESSION>-…/agents/agent-591/wire.jsonl` 第 5 行 `profile.bind` / 第 15 行 `llm.request`）。全程只读：未写任何文件、未执行 git 写命令；仓库实读点 = `<REPO_ROOT>`（HEAD `45b2c6a`），未触碰两条 trae 禁地。
