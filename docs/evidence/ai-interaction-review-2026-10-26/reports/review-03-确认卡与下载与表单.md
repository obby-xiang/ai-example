> 来源=agent-k2feiim9/output.log（2026-10-26 归档）· 全面代码审查（确认卡知情面、批量下载空文件、单活动表单等 14 项）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

## 审查口径

- 只读静态审查，基线 `61de820`（工作区干净）；未起停服务、未跑测试/构建、未发 HTTP 写请求、未做真实模型调用、未使用浏览器、未读 `.env`、未委派。
- 结论分“可静态证明”“条件成立时成立”“风险/未实测”三档；未运行，不把静态推断写成实测结论。
- 路径均相对本工作区 `<LOCAL_WORK>`，行号为当前工作树。

## 结论摘要

最值得先看的三项：

1. **确认卡把“要批准什么”的关键事实藏掉了**：后端仍算影响面/预检指纹，前端明确不渲染；导出看不到“是否全量”，发布看不到“REPLACE 还是 MERGE”。用户被要求为不可逆操作勾选“若本次为整体替换”，却无法在卡上确认是不是整体替换。
2. **批量下载可能静默产出空文件**：`buildXlsx` 只要表格组件挂载就取它的字节，但非当前标签页从未载入数据；AI 的 `download_export_files` 和“全部打包 zip”都会把空工作簿打进包里，AI 仍回“已发起”。
3. **“口头继续”被系统提示词制度化**：推进步骤/导航后必须结束本轮、让用户回“继续”，即使所需工具在当前披露集里已经齐全；这与“简单留给用户”直接冲突。

---

## 逐项发现

### 1. 确认卡不显示影响面、条件范围、发布模式（知情同意缺失）

- **用户场景**：用户说“把 CURRENCY 导出后发布”。导出卡的字段表只有 `操作 / taskId`；发布卡只有任务号与一个复选框“若本次为整体替换，范围内旧数据将被删除”。卡上不显示：导出是全量还是带条件、影响多少行、发布到底是 REPLACE 还是 MERGE。
- **证据**：
  - 后端确实算好了，却只下发给前端：`<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:170-173`（`impact`/`reportHash`）、`:353-376`（`asMap` 含 target/checks/impact）。
  - 前端明确不渲染：`<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:526-533`（“预检摘要区撤除……字段保留不渲染”）、`:3107-3109`（`gateRows` 只有“操作 + args 全键”）。规格依据：`<REPO_ROOT>/docs/03-技术方案文档-v2.1.md:696`。
  - 发布模式只存在于服务端权威值：`<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:391-401`（`start_publish` 不带模式）；手动路径则先读权威模式再按模式确认：`.../frontend/src/views/ImportWizardView.vue:1367-1393`；AI 卡文案：`.../frontend/src/components/AiPanel/AiPanel.vue:3498-3507`、`.../frontend/src/locales/zh-CN.json:312-318`。
- **严重程度**：高（不可逆发布可能删行；导出可能意外全量）。
- **性质**：旧缺口 + 既有用户裁决（2026-10-20 撤预检区）。不是新引入，但和“能安全自动处理、不推给用户”目标相反——这里不是多一张卡的问题，是卡上缺决策事实。
- **最小方向（只供审）**：把已有 `preflight.impact` 与“条件摘要/全量提示”“服务端解析出的发布模式”三行渲染回确认卡；不恢复倒计时、不增按钮。发布复选框按模式说人话，而不是让用户猜。

### 2. AI/“全部打包”下载对未载入标签页产出空文件

- **用户场景**：导出两个配置后直接点“全部打包 zip”，或用 AI `download_export_files(["A","B"])` 下载 B（B 标签页没打开过）。下载得到的 zip 里 B 只有表头/空表；AI 回执是“已发起打包下载……无需重试”。
- **证据**：
  - 只有当前标签页载入：`.../frontend/src/views/ExportWizardView.vue:526-530`（只 `ensureGridLoaded(first)`）、`:1120-1124`（切 tab 才载入）。
  - `buildXlsx` 只看组件是否存在，不看内容是否载入：`:1143-1154`；批量与全部下载都直接调它：`:1243-1270`、`:1299-1316`；AI 前置只校验文件清单存在：`:1517-1556`。
  - 空表会被当成合法字节：`.../frontend/src/components/SpreadGrid.vue:478-484`（`fields` 为空返回 `[]`）、`:497-502`（只在引擎未就绪时抛错）。
  - 测试恰好绕开该路径：`.../frontend/tests/export-wizard-batch-download.spec.ts:85-90`（夹具明确说明 `gridRefs` 为空、固定走 `downloadFile` 回落）。
- **严重程度**：高（静默内容缺失，用户以为拿到全量）。
- **性质**：旧缺口（懒加载 + `buildXlsx` 回落判据），被“即发即回/单次打包”新规格放大；不是下载超时问题。
- **最小方向（只供审）**：`buildXlsx` 先 `await ensureGridLoaded(code)`，失败则回落服务端字节并在回执里说明“取的是服务端文件、不是在线编辑版”；批量前并发预载，任一失败整批显式失败（与无效码同口径）。

### 3. 单活动表单：同一批两张 `generative_form` 会让第一张变成死卡

- **用户场景**：模型在一轮里发出两张表单（例如“筛选条件 + 澄清问题”）。界面只渲染最后一张；第一张卡只剩卡头和“待填写”，没有提交/取消控件。填完第二张后输入框仍禁用，因为第一张仍 `requiresAction`；后端等人无超时，页面只能靠“停止”或刷新脱困。
- **证据**：
  - 后到覆盖先到：`.../frontend/src/stores/ai.ts:3595-3604`（`openGenerativeForm` 直接给 `activeForm` 赋值）。
  - 只有宿主匹配才渲染表单：`.../frontend/src/components/AiPanel/AiPanel.vue:606-628`（`liveForm.toolCallId === part.toolCallId`）。
  - 恢复路径反而是串行的：`.../frontend/src/stores/ai.ts:2384-2401`、`:2413-2430`；实时路径没有这条队列。
  - 表单无超时：`.../backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:147-152`；输入禁用判据：`.../stores/ai.ts:1415-1429`、`:3134-3137`；`AiPanel.vue:1582-1590`。
- **严重程度**：中高（概率取决于模型，但一旦发生就是无 UI 出口的死锁）。
- **性质**：旧缺口（GF-B 起就是单 `activeForm`）；park 无上限让“等它自己超时”这条自愈路径不存在。
- **最小方向（只供审）**：把 GF 也纳入“一次只武装一张”的串行队列（实时与恢复同构）；或后端在一批里拒绝第二张 GF，明确回“上一张表未决，不能同时下发两张”。

### 4. 预检覆盖面不足 + 目标校验后置：批准后才失败，且失败徽标说“超出当前范围”

- **用户场景 A**：任务一个配置项都没选，或预检查任务没有上传文件。卡仍正常弹出，用户批准后作业立刻 FAILED（导出“0 条目未成功”、预检查“未找到上传文件”）。
- **用户场景 B**：页面绑着任务 #7，AI 为任务 #8 发 `start_export`。预检四项全过（#8 存在、类型对、无在途、指纹项不适用），用户批准后执行前被目标校验挡下；卡面随后显示“超出当前范围”，而不是“已批准但未执行”。
- **证据**：
  - 预检只做四项，且签名拿不到工作区上下文：`.../backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:73-79`、`:106-174`；EXPORT/PRECHECK 的“指纹新鲜”恒通过：`:160-168`。
  - 目标任务一致性在执行前才判：`.../backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:119-121`、`.../SpToolCallingManager.java:1549-1554`。
  - 空任务/无文件的失败：`.../backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:118-124`、`.../PrecheckJobRunner.java:144-154`。
  - 徽标：`blocked` 被排除在只读决策卡之外：`.../frontend/src/utils/blocks.ts:316-322`、`:343-380`；`cardStatusOf` 回普通状态标签：`.../utils/blocks.ts:493-504`。
- **严重程度**：中。
- **性质**：旧缺口（F5 预检范围窄、目标校验后置）。与已知第③问“预检失败卡误显示已批准”不是同一形态，但同属“卡面与真实结局不一致”。
- **最小方向（只供审）**：预检增加“任务有配置项/上传文件”“目标任务与工作区一致”两项；把 `ToolTargetResolver` 的判据前移到 `ToolPreflightService`；`blocked` 且 `decision=approved` 的卡显示“已批准·未执行（原因）”。

### 5. AI 起作业后页面/任务中心不同步（发布尤其缺）

- **用户场景 A**：用户在任务中心让 AI“导出某任务”。确认卡批准后作业已跑，但任务中心没有任何自动刷新，进度列还是旧的；只能点右上角刷新或等下次进入。
- **用户场景 B**：用户在导入向导发布步让 AI 发布。`start_publish` 不在 `START_JOB_SYNC` 里，向导的发布进度面板不会绑定该作业；页面看不出作业在跑还是结束，只有 AI 卡上有一句回执。
- **证据**：
  - 任务中心手动刷新口径：`.../frontend/src/views/TaskCenterView.vue:152-158`、`:360-364`；`refresh_tasks` 的 ui_event 存在但模型无同名工具可达：`.../frontend/src/stores/workspace.ts:627-630`。
  - 同步映射漏了发布：`.../frontend/src/stores/ai.ts:864-868`；触发是静默的、页面 handler 的“未同步”文案不会呈现给用户：`:5664-5686`。
  - 页面侧绑定实现：`.../frontend/src/views/ImportWizardView.vue:1703-1726`、`.../ExportWizardView.vue:1596-1616`。
- **严重程度**：中（发布为高频主流程，观感是“我批准了但页面没反应”）。
- **性质**：旧缺口 + 手动刷新批（M1）的副作用；不是新引入，但新规格让 AI 与页面的耦合更紧，缺口更显眼。
- **最小方向（只供审）**：补 `start_publish → import.syncJob`；工具结果到达时对任务中心做一次 `refreshNow`（或把 `refresh_tasks` 变成模型可达工具）；页面 handler 的失败回执至少进 AI 面板提示位。

### 6. “口头继续”被规则制度化，且比实际披露约束更保守

- **用户场景**：用户说“帮我导出 CURRENCY”。AI 每推进一个步骤就停下，要求用户回“继续”；从任务中心出发时，`select_definitions`、`set_condition`、`confirm_step`、`start_export` 本来都在 `page:tasks` 披露集里，模型完全可以一轮做完，但仍被规则要求结束本轮。
- **证据**：
  - 系统提示第 10 条：导航/推进后“用一句话引导用户回复继续指令”，`.../backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:186-190`。
  - 披露面本身允许链式：`.../backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:343`（`start_export` 含 `page:tasks`）、`:476`、`:518`、`:565`。
  - 建议条没有“继续”这个一键项：`.../frontend/src/utils/ai-suggestions.ts:95-118`。
- **严重程度**：中（高频；每次多一轮人工输入）。
- **性质**：旧规格（披露冻结时期的保守策略），与用户这次“简单留给用户”的新要求冲突。不是缺陷，是规格取舍。
- **最小方向（只供审）**：只在“下一步所需工具确实未在本轮披露集”时要求继续；或提供一键“继续”chip。后者不改安全语义，只省打字。

### 7. 任务状态“已完成”在预检查通过/导入完成时就显示，发布还没做

- **用户场景**：导入任务的预检查 0 错误后，后端把任务置 COMPLETED；向导头部标签变“已完成”，任务中心整行绿色“已完成”，`list_tasks` 也回 `状态:COMPLETED`。但用户还停在“检查配置”，导入/发布都没做。
- **证据**：
  - 预检查成功即任务 COMPLETED：`.../backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java:295`；口径自认：`.../JobService.java:62-67`。
  - 向导只读判据特意绕过它：`.../frontend/src/views/ImportWizardView.vue:428-445`；但头部仍直接显示任务状态：`.../frontend/src/components/TaskWizard.vue:149-159`。
  - 任务中心标签：`.../frontend/src/views/TaskCenterView.vue:231-235`。
- **严重程度**：中（会误导用户和模型“已经结束/不用再做”）。
- **性质**：旧状态机缺口。与已知第④问“COMPLETED 仍披露 start_export”是同一状态语义家族的另一个面，但不是同一症状。
- **最小方向（只供审）**：展示层复用已有的“最终作业成功才算结束”判据（`isReadOnly` 已是这个口径），或新增“预检查通过/待发布”状态；至少向导头部不要在发布前显示“已完成”。

### 8. 披露面与执行面不一致：工具在任务中心可见，但前端守卫要求向导页/特定步骤

- **用户场景**：用户在任务中心让 AI“设一下条件/勾选配置”。工具确实被披露（`page:tasks`），但 `WizardActionGuard` 的 `allowPages/allowSteps` 只允许导出/导入向导的特定步骤，调用直接失败并回“请先导航/推进步骤”，白跑一轮。
- **证据**：
  - 工具 scope：`.../backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:476`、`:518`。
  - 前端守卫：`.../frontend/src/utils/frontend-tools.ts:872-897`（`select_definitions` 只允许 export/import 页、`set_condition` 只允许 export 且 `QUERY_COND`）。
- **严重程度**：中低（可恢复，但增加往返与困惑）。
- **性质**：旧缺口（补丁② 与 2026-10-25 守卫批的边界没对齐）。
- **最小方向（只供审）**：让 `@ToolScope` 与 `allowPages/allowSteps` 同源；或守卫在“页面对但步骤不对”时自动推进，而不是失败。

### 9. 安全可逆的“清空”被推给用户手工

- **用户场景**：用户说“把条件去掉，导出全部”或“取消全选”。这是可逆、可见的动作，但 `set_condition` 空条件被守卫拒（理由是“空 = 全量，不能误判成功”），`select_definitions` 空数组也被拒；AI 只能让用户自己去界面清。
- **证据**：`.../backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:183-185`、`:232-252`；工具描述已把“清空只能手动”写死：`.../backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:475-484`、`:516-517`。
- **严重程度**：中低。
- **性质**：新引入（2026-10-25 `WizardActionGuard` 的 fail-closed）。当初防“静默导出全量”是对的，但把显式清空也一起挡了。
- **最小方向（只供审）**：增加显式 `clear=true`（或 `conditions:null` 的专门分支），要求模型说明意图，而不是要求用户手工；仍然拒绝“缺失/畸形被补成 {}”。

### 10. 重复 `create_task` 与空任务在列表里不可辨

- **用户场景**：模型超时/抖动后重发 `create_task`（新 `toolCallId`），或一轮里发两次，会建出多条同名空任务；任务中心没有“配置项数/空任务”列，用户只能逐个点开再删。
- **证据**：`create_task` 无去重/幂等键：`.../backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:292-318`；`TaskService.create` 直接落库：`.../task/service/TaskService.java:117-124`；列表列：`.../frontend/src/views/TaskCenterView.vue:42-106`（无条目数）。
- **严重程度**：中低。
- **性质**：旧缺口。**不重复**已知第①问的“WRITE 无门/2B 不得静默建库未施工”，这里只谈重复与可见性。
- **最小方向（只供审）**：同会话短窗口内同 `type + title` 复用现有任务；列表加“配置项数”或“空任务”标识。

### 11. GF 级联：无分支时必填子项变成死路；裁剪静默清值

- **用户场景**：父字段选了某个值，`optionsFrom.map` 没有该分支（或数据源失败返回空），子下拉只剩占位项；若子项必填，提交只能得到“必填”，没有任何“为什么没有选项/该改哪里”的提示，只能取消。另一个副作用：父值一变，已选的子值被 `pruneCascadeValues` 静默清掉，界面没有“已清空 N 项”的提示。
- **证据**：
  - 无分支返回空集：`.../frontend/src/types/form-schema.ts:559-583`；渲染为只有占位项的下拉：`.../frontend/src/components/AiPanel/FormRenderer.vue:124-140`。
  - 静默裁剪：`.../frontend/src/components/AiPanel/FormRenderer.vue:557-565`、`.../types/form-schema.ts:632-683`；提交校验：`:747-773`。
- **严重程度**：中低。
- **性质**：裁剪是**新引入**（`cee7fc5`）；“无分支必填死路”是既有 schema 质量问题。
- **最小方向（只供审）**：无选项时给可行动提示（“当前父项没有可选子项，请改选或联系管理员”）；schema 校验 `required` 子项在静态 map 各分支都有选项；裁剪后追加一条可见提示。

### 12. 作业进度查询依赖模型“记住 jobId”

- **用户场景**：几分钟后用户问“导出完成了吗”。`check_job_status` 必须给 `jobId`；`list_tasks`/`get_workspace_state` 都不带最近作业号。经过几轮工具结果清理/上下文压缩后，jobId 可能已不在上下文里，AI 只能让用户去任务中心看。
- **证据**：`.../backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:234-245`（必填 jobId）、`:195-211`（任务行不含 jobId）；工具结果按“保留最近 5 对”清理：`.../backend/src/main/java/com/example/configmgr/ai/context/ToolResultClearing.java:22-35`。
- **严重程度**：低。
- **性质**：旧缺口。
- **最小方向（只供审）**：增加 `list_jobs(taskId)`，或让 `list_tasks`/`get_workspace_state` 带“最近作业号 + 状态”。

### 13. 同批重复的 DANGER 调用会生成两张相同确认卡

- **用户场景**：模型抖动，一轮里发两次相同的 `start_export(taskId=7)`。两张一模一样的卡；用户都批准后，第二张执行时被“在途作业/资源锁”挡下，卡面转成阻断态。
- **证据**：`newPendings` 不去重：`.../backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2198-2213`；两张卡都走预检并挂起：`:420-471`。
- **严重程度**：低（可恢复，但属于“重复确认”）。
- **性质**：旧缺口；整批确认卡让它更容易被看见。
- **最小方向（只供审）**：同一批内按 `name + arguments` 去重 DANGER 调用，回执说明“同一动作已在等待确认”。

### 14. 残留风险：批准后 park 续跑派发失败时，卡停在“已批准”，界面无解释

- **用户场景**：用户点批准，决策已落库，但续跑派发遇到 `POOL_SATURATED`/`SESSION_BUSY`/`CANCEL_STATE_UNKNOWN`。卡面是“已批准”，没有执行进展；要等前端看门狗才可能出现“结果可能不完整”，用户不知道是排队还是失败。
- **证据**：派发失败的有界重试与结局表：`.../backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:222-243`、`:2097-2148`；前端没有对应的人话状态位。
- **严重程度**：中低（条件触发，未实测）。
- **性质**：旧缺口/风险，不作为主结论。

---

## 最近规格变化逐项评估

| 规格变化 | 评估 |
|---|---|
| 整批确认卡（park 前一次公布整批请求帧） | **本身可用**：live 路径多卡同时呈现、各自可决；真正的问题是模型重复发出相同 DANGER 调用（第 13 项）。恢复路径按 M-2b 串行补卡，与 live 不同但可恢复，未计为缺陷。 |
| park（等人无上限、线程交还） | 方向正确；残留是“批准后派发失败无用户可见态”（第 14 项）与“GF 死卡无法自愈”（第 3 项）。 |
| 预览绑定版本（fingerprint + sheetVer + instanceId + loadGeneration） | **未发现可证的绕过**：应用前逐行复核指纹、版本变则失效，`sheetVer` 用预览时值而非重算值（`.../frontend/src/stores/ai.ts:4231-4238`、`.../utils/sheet-tools.ts:2868-2883`）。成本仅是失效后需用户点“放弃”再让 AI 重发起；文案已如实，未计为缺陷。 |
| WizardActionGuard | 防“空条件静默全量”的主方向正确；**新引入**了两个摩擦：清空条件/空选择被推给用户（第 9 项）、披露面与执行面不一致（第 8 项）。 |
| GF 级联裁剪（`cee7fc5`） | 解决了“旧值残留导致提交必败”，但**新引入**静默清值与无分支必填死路（第 11 项）。 |
| 下载超时（L-82 30s 前置上限 + 即发即回） | 超时叙事是诚实的（明确“未触发下载”），比旧的 120s 假失败好；真正的问题是**内容就绪**没被前置校验覆盖（第 2 项），不是超时。 |
| 任务代次（generation/`taskGeneration`） | 起作业、切任务、挂 watcher 的代次守卫基本到位；本报告未发现新缺口。跨任务 zip 命名的既有 I-08 已登记，不重复。 |

---

## 覆盖面清单

**后端已读/抽查**

- `ai/tools/AiTools.java`（全文）：工具面、风险、披露、描述口径、`create_task`、`start_*`、GF schema、表格工具。
- `ai/gate/ToolPreflightService.java`（全文）、`ToolTargetResolver.java`（全文）、`WizardActionGuard.java`（全文）。
- `ai/gate/SpToolCallingManager.java`：预检/挂起/整批发布/执行循环/预检阻断/`newPendings`/`executeOnce` 入口与目标校验；未逐行读台账与续跑后半段。
- `ai/gate/ConfirmGate.java`：等待/park/请求帧/决策/回灌/放弃/结算/Javadoc 口径；未逐行读派发重试与租约细节。
- `job/service/ExportJobRunner.java`、`PrecheckJobRunner.java`、`JobService.java`、`task/service/TaskService.java`：零条目、无文件、终态守卫、状态联动。
- `ai/run/ResilientChatService.java` 的 `SYSTEM_PROMPT`；`ai/context/ToolResultClearing.java`；`ai/run/PendingToolCall.java` / `SseChatEmitter.java` / `AiController.java`（按字段 grep）。
- `resources/ai/tools.yaml`（全文）；`ai/form/GenerativeFormRules.java` 只做了边界抽查，未逐行。

**前端已读/抽查**

- `components/AiPanel/AiPanel.vue`：确认卡/GF/预览/上传卡、卡头标题、状态、composer、建议条、拒绝/批准的提交；未逐行读滚动与 markdown。
- `components/AiPanel/FormRenderer.vue`（全文）、`types/form-schema.ts`（关键函数）、`utils/parts.ts` / `utils/blocks.ts`（状态机与只读态）。
- `stores/ai.ts`：会话/恢复/确认/GF/预览/同步/帧处理；未逐行读所有发送与历史镜像段。
- `stores/workspace.ts`（全文）、`stores/task.ts`（刷新口径）、`utils/frontend-tools.ts`（路由/守卫/scope）、`utils/job-watch.ts`（全文）、`utils/ai-suggestions.ts`。
- `views/TaskCenterView.vue`（全文）、`views/ExportWizardView.vue` / `views/ImportWizardView.vue`（步骤、下载、作业、AI handler、只读、发布语义）、`components/TaskWizard.vue`、`SpreadGrid.vue`（载入/导出）。
- `locales/zh-CN.json` / `en-US.json` 的确认卡文案。

**明确未覆盖**

- `ConfirmGate` 的租约/fence/取消收尾全链、`ResumeService` 的重建细节、`SpToolCallingManager` 的台账/认领/资源锁后半段（仅按调用面核对语义）。
- `GenerativeFormRules` 的完整规则体、`resultview`、`ai-dom-tools` / `ai-regions` 的原语内部、设置页、鉴权与多租户。
- 未运行任何测试/服务/浏览器；第 2、3 项是静态可证但未在真实浏览器复现，第 14 项属条件风险。

## 与已知六问的边界（未重复）

本报告不复述：`create_task` WRITE 无门与 2B 裁决未施工；提示层允许纯文字询问配置/筛选；预检失败 CONFIRM 卡误显示已批准；COMPLETED 仍披露 `start_export`。

相邻但不同：

- 第 4 项是“预检**通过**了不该通过的作业/目标不一致”，不是“预检失败卡装成已批准”。
- 第 7 项是“任务状态标签把预检查通过说成已完成”，不是“COMPLETED 仍披露工具”。
- 第 10 项是重复建任务与空任务可见性，不是建库门槛裁决。
