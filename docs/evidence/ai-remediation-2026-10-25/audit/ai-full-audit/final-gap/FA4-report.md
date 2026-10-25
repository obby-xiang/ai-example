> 来源=<仓外证据>/ai-full-audit/final-gap/FA4-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
未运行任何测试。以下是 FA4-report 正文（文件式交付，可直接落盘为 `final-gap/FA4-report.md`）。

---

# FA4-report｜前端组合界面（向导 / 设置 / 表单 / 上下文 / 结果视图）

> **未运行任何测试**（未 `vitest`、未 `mvn`、未 `pnpm build`、未起服务、未开浏览器）。
> 只读审计：未改任何代码 / 测试 / 配置 / 工作流，未触发 CI，唯一产出是本报告文本。
> 快照：`<仓外证据>/ai-full-audit/tree`（= `ae883fb` + `working.patch`）。
> 口径沿用 `FINAL-AUDIT.md`：**S1–S4 / C（代码可证）、R（需运行期窗口/时序）、T（缺测）、D（已裁决边界）**。
> 本文只收**新条目**与对既有条目的**确认 / 收窄 / 补登**；重复项回指 canonical ID，不新开编号。

## 0. 范围与方法

| 项 | 值 |
|---|---|
| 分配文件 | 11（`final-gap/FA4.md` §表） |
| 逐行读完 | **11/11** |
| 行数守恒 | 1789+834+82+791+111+120+79+68+105+28+15 = **4,022** ✓（与 `wc -l` 实测一致） |
| 读法 | 每个文件整文件顺序读（含模板段与 `<script setup>`），无跳段 |
| 支撑面（为判定这 11 个文件的行为而只读的相邻契约，**不重复其归属组的逐行结论**） | `types/form-schema.ts`（`checkFormIssues/resolveFieldOptions/applyDefaultFrom/visibleValues/isRetryableIssue`）、`utils/resultview.ts`（`normalizeResultView/RESULTVIEW_LIMITS`）、`utils/context-usage.ts`、`components/SpreadGrid.vue`（`fields/toXlsxBlob/validateLocal/loadFromXlsx`）、`utils/excel.ts#buildRowsXlsxBlob`、`stores/ai.ts`（`loadContext/chooseUploadFile/cancelUploadHandoff/formOptionSource/submitFrontendToolResult/submitGenerativeForm/done|error 收尾`）、`components/AiPanel/AiPanel.vue`（FormRenderer / UploadHandoffCard 挂载点）、`locales/zh-CN.json|en-US.json`、后端 `AiConfigController/AiConfigWriter(ModelCommand,ProviderCommand)/GenerativeFormAttachmentController/AiModelResolver` |
| 明确不做 | 业务内核（ConditionForm/DefSelector/SpreadGrid 内核/TaskWizard）不审；后端行为只在"决定前端条目是否成立"时取最小事实 |

## 1. 逐文件覆盖与既有 ID 关系

| # | 文件 | 行 | 读完 | 与既有 canonical ID 的关系 | 本报告新增 |
|---:|---|---:|---|---|---|
| 1 | `views/ExportWizardView.vue` | 1,789 | ✓ | **确认** FE-32（两区域无 `allowSteps`、三步骤 `v-show`）；**确认** FE-15 的"向导侧 enterPage 正常"；**补登** I 域 I-08（§5 未收录，见 §4.1）；FE-16 的机制**不适用**本文件（见 §4.2） | FA4-01…08、13 |
| 2 | `views/AiSettingsView.vue` | 834 | ✓ | **确认** FE-15、MD-08、ID-03 的"用户操作面"、BZ-23；**确认** I-10（§5 未收录） | FA4-09、10、14、15 |
| 3 | `views/AiFeedbackView.vue` | 82 | ✓ | **确认** ID-07 / MD-14（列表面无过滤、`rating∈1/-1` 故赞踩判定正确、超长/并发只影响写面） | —（无新增） |
| 4 | `components/AiPanel/FormRenderer.vue` | 791 | ✓ | **确认** BZ-25（补非对抗触发路径）、**否证** BZ-26 的前端"第二故障点"（见 §4.3）、FE-08（草稿同源） | FA4-11、12、18 |
| 5 | `components/AiPanel/UploadHandoffCard.vue` | 111 | ✓ | **确认 + 细化** PK-12、FE-23、FE-25；**补充** FE-29 | FA4-16、17 |
| 6 | `components/AiPanel/ContextPill.vue` | 120 | ✓ | **确认** MD-03/MD-19 的展示面（未知档 → `none`、负 `free` → "已超"、分母缺失不给百分比） | FA4-19（与 #7 同条） |
| 7 | `components/AiPanel/ContextPopover.vue` | 79 | ✓ | 同上；**补充** FE-29（"上限未知"硬编码中文） | FA4-19 |
| 8 | `components/AiPanel/resultview/ResultViewRenderer.vue` | 68 | ✓ | **确认** PK-17 的"resultView 预算靠约定"（前端三把尺同名实现，无新增越权面）；插值渲染，无 HTML 面 | FA4-20 |
| 9 | `components/AiPanel/resultview/ResultViewTable.vue` | 105 | ✓ | 同上 | FA4-20 |
| 10 | `components/AiPanel/resultview/ResultViewText.vue` | 28 | ✓ | 同上 | FA4-20 |
| 11 | `components/AiPanel/resultview/registry.ts` | 15 | ✓ | **确认**契约成立：`kind` 闭集 = `['table','text']`（`resultview.ts:50`），`chart/form` 走 fallback，不白屏 | — |

## 2. 新增条目（20 条）

> 每条给：Sev / Cert / `path:line` / 触发 / 用户可见效果 / 现有测试 / 关系。**未运行验证**；`C` = 代码路径可证，`R` = 需特定交错或运行期前提（前提逐条写明）。

### 2.1 S2

**FA4-01｜未加载的结果标签页被当成"当前内容"：`保存修改` 用空工作簿覆盖服务端导出文件（下载同根）**
- Sev **S2** / Cert **C**（前提：`el-tab-pane` 默认非懒惰渲染 ⇒ 非激活页已挂载；见 §5-①）
- path：`ExportWizardView.vue:471-494`（只对首个标签 `ensureGridLoaded`）、`:896-915`、`:917-941`、`:967-977`（`buildXlsx` 只看 `gridRefs[code]` 是否存在）、`:1155-1188`（`saveEdits` 无条件 PUT）、`:1122-1147` / `:1095-1120`（打包下载）；支撑：`SpreadGrid.vue:96`（`fields=[]` 初值）、`:478-484`、`:497-502`；`excel.ts:457-481`
- 触发：一个任务有 ≥2 个导出结果 → 只激活/打开第一个标签（这是 `loadExistingResults`/`refreshResults` 的唯一行为）→ 点「保存修改」（或「全部下载 / 下载勾选」）
- 效果：未激活标签的 `SpreadGrid` 从未 `loadFromXlsx`，`fields` 仍为 `[]` ⇒ `toXlsxBlob()` 产出**只有 `def_code` 元数据表的空工作簿**；`validateLocal()` 对空表返回 `[]`（放行）⇒ `PUT /tasks/{id}/files/{defCode}` 把服务端导出文件替换成空内容，界面仍报「已保存到服务端」并把 `gridDirty` 清零。下游（发布 / 导入 / 其它入口读服务端文件）读到 0 行 ⇒ 直接落进 **BZ-01** 的"零条目"链。下载路径同根：zip 里这一份是空表，AI 回执却写「内容为在线编辑后的当前表格」（`:1638`、`:1657`）
- 测试：**无**；`tests/export-wizard-batch-download.spec.ts:84-89` 恰恰以"`gridRefs` 为空 ⇒ 走后端文件回落支，两支路同名同源"为论证前提，把"有 grid 但未加载"这条真实分支排除在断言之外（`shallowMount` 丢弃 `el-tabs` 插槽）→ 见 §4.5
- 关系：新增；把 **BZ-01** 的触发面从"模型发起"扩到"用户点一次保存"

**FA4-02｜`startExport` 丢弃 `persistStepData` 结局：条件未落库仍起作业**
- Sev **S2** / Cert **C**（触发 = 一次写失败）
- path：`ExportWizardView.vue:763-788`（`:772 await persistStepData()` 后不判 `ok`）、`:1485-1492`（`export.start` handler 直接 `await startExport()` 并回"已启动"）；对照已修形态 `:1422-1430`（`export.nextStep`）、`:1341-1345`（下载前置）、`:1554-1559`（openEditor）
- 触发：`tasksApi.selectDefs` / `setCondition` 失败（网络、5xx、未勾选该配置），随后点「开始导出」或 AI 调 `start_export`
- 效果：作业以**服务端旧/无条件**启动并 COMPLETED；界面显示的条件与实际导出内容不一致（"AI 说设了条件、导出却是全量"），而 AI 收到的回执是 `导出作业 #id 已启动`，无任何失败信号；页面只闪一条 error toast。四个入口里唯一没做阻断的一条
- 测试：**无**（`tests/export-wizard-step-outcome.spec.ts` 自称覆盖"三条真实入口 + openEditor"，T7 清单未列 `export.start`）
- 关系：新增；**W-06 根面的残余入口**（FINAL 只在 `goStep` 侧确认过）

### 2.2 S3

**FA4-03｜`export.start` 用陈旧 `exportJob` 返回"已启动"假成功**
- Sev **S3** / Cert **C**
- path：`ExportWizardView.vue:1485-1492`；`exportJob` 只在成功时赋值、失败/守卫拦下时不清空（`:775-787`）
- 触发：已有一次 EXPORT 作业在 `exportJob` 里（含已 COMPLETED 的），再次 `start_export`：`createJob` 失败，或 `selectedDefs` 为空被 `:768-771` 拦下
- 效果：handler 返回 `导出作业 #<旧 id> 已启动` ⇒ 模型据此宣布"已启动/不必重试"，实际一个新作业都没有
- 测试：无
- 关系：新增（与 FA4-02 同一函数族、不同根因：一个是忽略结局，一个是复用陈旧状态）

**FA4-04｜`export.setConditions` 落库失败仍按字符串成功回灌（"兼容⇒ok"支）**
- Sev **S3** / Cert **C**
- path：`ExportWizardView.vue:1454-1483`，失败支 `:1478-1482` 返回**字符串**；同文件 `:1424-1425` 的注释已把这种形态定性为"卡面与回灌自相矛盾"，但只修了 `nextStep`
- 触发：`tasksApi.setCondition` 失败（后端 5xx / 未勾选该配置项）
- 效果：字符串结局被契约层折成 ok ⇒ 工具卡 tag 显示「已成功」，卡内文却写「落库失败…请提示用户」；模型下一轮据"已设置"行事，导出用旧条件（= FA4-02 的输入端）
- 测试：无（`tests/ai-region-cond-errors.spec.ts` 只断言 `condErrors` 留痕）
- 关系：新增；同族于 **I-09**（失败也显示"工作区已同步"）

**FA4-05｜跨任务绑定/落库无代次守卫：A 的选中集与条件可写进 B**
- Sev **S3** / Cert **R**（需两次绑定交错；"无守卫"本身是 C）
- path：`ExportWizardView.vue:399-430`（`loadTask` 无 token，读实时 `taskId.value`）、`:562-582`（`applyTaskBinding`）、`:686-716`（`persistStepData` 用实时 `taskId`）、`:1752-1762`（route watch）；文件自认 `:1226`「不新增序号守卫」
- 触发：`route.query.taskId` 连续两次变化（AI `navigate_to` 连调、或加载中途用户换任务）导致两个 `loadTask` 交错；或切换落在 `goStep → syncStepToBackend` 的等待窗口内
- 效果：后落地的**旧** `loadTask` 覆盖 `task/selectedDefs/stepIndex/workspace.enterPage(旧任务)`，而 `persistStepData`/文件列表/下载都读实时 `taskId` ⇒ 页面标题与只读判据来自 A、落库与下载作用在 B（把 A 的 defCode + 条件写进 B，或把 A 的作业面板挂到 B）；AI 上下文的 `taskId` 与实际被改的任务分叉
- 测试：无（`import-watch-recycle` 是导入侧且不挂视图）
- 关系：新增；**补登 I-08 的同族**（§4.1）

**FA4-06｜`refreshResults` 静默丢弃未保存的在线编辑（与换任务时的提醒口径不一致）**
- Sev **S3** / Cert **C**
- path：`ExportWizardView.vue:836-845`（`loadedGrids.clear()` + 重下载，无 `gridDirty` 判据）、`:587-591`（`ensureStepLoaded(2)`）、`:641-648`（`viewStep`）、`:1777-1787`（工作区步骤跟随）；对照 `:508-512`（换任务时会 `ElMessage.warning(unsavedDiscarded)`）
- 触发：第 3 步改了结果表格（未保存）→ 点上一步再前进 / 点步骤节点 / AI `ui_event goto_step` / `export.syncJob`
- 效果：编辑被服务端文件覆盖、无任何提示；`gridDirty` 仍为 true，之后卸载时又会弹「有未保存编辑」——提示指向已被丢弃的内容
- 测试：无
- 关系：新增

**FA4-07｜重跑导出失败/取消后，旧导出结果从页面消失（文件仍在服务端）**
- Sev **S3** / Cert **C**
- path：`ExportWizardView.vue:779-781`（启动即清 `exportResults`）、`:810-832`（`onFinal` 只在 COMPLETED 时 `refreshResults()`）；`:1325-1332`（下载前置读同一份空清单）
- 触发：对已有结果的任务重跑导出，作业终态 FAILED / CANCELLED
- 效果：页面变「暂无导出结果」，用户以为文件没了（刷新即恢复）；这段窗口里 AI `download_export_files` / `open_export_file_editor` 会报"该任务现有导出结果：（无）"——把"文件仍在"说成不存在（假阴性）
- 测试：无
- 关系：新增

**FA4-08｜只读判据 fail-open：作业列表失败被吞成 `[]` ⇒ 已结束任务变可编辑**
- Sev **S3** / Cert **R**（需一次 `GET /tasks/{id}/jobs` 失败）
- path：`ExportWizardView.vue:440-462`（`:444 listJobs(...).catch(() => [])`，`:449 exportJob = job`）、`:310-316`（`isReadOnly` 依赖 `exportJob.status==='COMPLETED'`）；`:147-149`（模板 `:read-only`）
- 触发：打开一个已 COMPLETED 的导出任务时作业列表请求失败（后端抖动 / 网络）
- 效果：`exportJob=null` ⇒ `isReadOnly=false` ⇒ 向导可编辑，且 `export.selectDefs` / `export.setConditions` / `export.start` / `export.nextStep` 四道只读守卫全部放行（`:1437-1492`），AI 可在已结束任务上改选中集、起作业（后端 409 兜底，但界面与回执先行）
- 测试：无
- 关系：新增；与 `:1437-1492` 的"零写清单"设计意图相反

**FA4-09｜设置页「测试连接」测的是**已保存**端点，不是表单里的端点；探活结论不随表单/保存失效**
- Sev **S3** / Cert **C**
- path：`AiSettingsView.vue:365-374`（只传 `provider.id`，不先保存）、`:376-384`、`:671-678`（结论常驻卡片）、`:287-297`（保存成功后 `load()` 不清 `probeResults`）
- 触发：改 `baseUrl`/`completionsPath`/`modelsPath` 后**直接**点「测试连接」；或改完保存后回看
- 效果：探活打的是库里旧端点 ⇒ 用户拿到"连接成功"却保存了一个不可达地址（反向亦然）；`probeResults` 按 provider id 缓存，编辑/保存后仍显示上一次结论（绿/红），与当前表单不同源
- 测试：`tests/ai-settings-view.spec.ts` 覆盖 M-1 文案映射，**不**断言"探活目标=表单值"或"改表单后结论失效"
- 关系：新增（与 ID-03 相关但不重合：这里不是外发目标错，而是"验证的对象错"）

**FA4-10｜「拉取模型」结果跨 provider 复用，可把 A 的模型加进 B**
- Sev **S3** / Cert **C**
- path：`AiSettingsView.vue:50-51`（`fetchedModels/fetchedCached` 无 provider 维度）、`:415-434`（写入）、`:436-449`（`addFetched` 用当前 `selectedProvider`）、`:713-734`（下拉与条目渲染）
- 触发：对 A 拉取模型 → 把下拉切到 B → 点某条「加入」（或先切 provider 再等 A 的响应落地）
- 效果：`addModel(B, modelId_of_A)` 落库 ⇒ B 的 allowlist 出现 B 不提供的模型；之后解析到该模型时上游 404 / `UPSTREAM_REJECTED`；`pulledCached` 文案同样跨 provider 误标
- 测试：无（视图测试无 fetch→switch 序列）
- 关系：新增

**FA4-11｜级联字段：本地不校验数据源取值 + 父值切换不清子值 ⇒ "看不见的旧值"被提交**
- Sev **S3** / Cert **C**
- path：`FormRenderer.vue:556-570`（`setValue` 只补 `defaultFrom`，不清依赖字段）、`:595-597`（`optionsOf` 传 external）、`:782-789`（提交复核走 `checkFormIssues`）；`types/form-schema.ts:540-560`（`resolveFieldOptions` 无 external 时 `sourceId` 分支返回 `[]`）、`:748-750`（`options.length>0` 才判成员）、`:566-590`（`applyDefaultFrom` 无映射时保留原值）
- 触发：(a) `optionsFrom.sourceId` 字段改动父值；(b) 父=P1 选子=C1，再把父改成 P2（无该分支 / 分支里没有 C1）
- 效果：(a) 本地复核因 `options=[]` **跳过**"值不在选项内"，只有后端 400 才拦 ⇒ 用户每次都要走一趟网络才看到"取值不在选项内"，而屏幕上是一个空下拉；(b) `<select>` 无匹配 option ⇒ 浏览器显示空白，`model` 仍持 C1，`visibleValues` 把 C1 提交 ⇒ 用户看不见、也没选过的值落库（map 分支会被本地成员校验拦住并报错指向"看着是空的"字段；**无分支 / sourceId 分支则两侧都放行**，即 **BZ-25** 的非对抗触发路径）
- 测试：`tests/gf-renderer.spec.ts:182-214` 只断言"切父值会重新请求数据源"，不断言选项内容、子值清理与本地成员校验
- 关系：**确认并补充 BZ-25**（给出用户可自然走到的触发路径，而非"父字段被隐藏"的对抗路径）

**FA4-12｜数据源级联异步无序号/取消：旧响应覆盖新选项**
- Sev **S3** / Cert **R**（需两次响应乱序）
- path：`FormRenderer.vue:603-620`（`refreshSourceOptions` 无 token / abort）、`:564-568`（父值变化循环触发）
- 触发：连续改父值 A→B，A 的响应后到
- 效果：下拉展示 A 的选项而当前父值是 B；用户就此选值 ⇒ 提交一个属于旧分支的值（后果链同 FA4-11(b)）；卡面没有任何"选项已过期"信号
- 测试：无（`formOptionSource` 在测试里被 mock 成立即 resolve）

**涉及导出向导的小项**

**FA4-13｜行数预览标签：`null` 也触发渲染，且手改条件后旧计数常驻**
- Sev **S4** / Cert **C**
- path：`ExportWizardView.vue:57`（`v-if="counts[code] !== undefined"` 对 `null` 为真）、`:1465`（`counts[defCode] = null` 作"清空"）、`:724-727`（`onConditionChange` 只删 `condErrors`，不删 `counts`）
- 触发：AI `set_conditions` 之后（null）；或用户手工改条件
- 效果：出现一条无意义的绿色「命中 … 行」标签（null 插值），或一直显示**上一次**条件的命中数 ⇒ 用户按错误规模判断导出范围
- 测试：`ai-region-cond-errors.spec.ts` 不涉及 `counts`

### 2.3 S4（设置页状态分支）

**FA4-14｜任意一次保存后的 `load()` 会把整页打回 loading 并重建全部卡片 ⇒ 其它卡片未保存的输入（含刚输入的密钥）被清空**
- Sev **S4** / Cert **C**；path `AiSettingsView.vue:199-221`、`:93-103`、`:287-297`（保存成功即 `load()`）
- 触发：A 卡改了 displayName/密钥未保存，去保存 B 卡 / 保存默认参数 / 建模型行
- 效果：`fillForms()` 重建 `cardForms`（含 `key: ''`）⇒ 未保存编辑丢失；密钥被清后表现为"保存完仍无 key"
- 测试：无

**FA4-15｜删除当前选中的 provider 后，模型区静默落到第一家（下拉显示空白）**
- Sev **S4** / Cert **C**；path `AiSettingsView.vue:340-363`、`:68-70`（`?? providers[0]`）、`:713-715`（select 无匹配项 ⇒ 空显）
- 触发：删除 `selectedProviderId` 指向的 provider（硬删或软删回落）
- 效果：下拉空白，但模型表格/拉取/加入/保存全部作用在 `providers[0]` ⇒ "看着没选，实际在改另一家"
- 测试：无

### 2.4 S4（交接卡 / 表单 / 上下文 / 结果视图）

**FA4-16｜交接卡 `unavailable` 态无生产者；已结束轮次的卡仍可点，且文案硬编码中文**
- Sev **S4**（效果侧接 FE-25/FE-23）/ Cert **C**
- path：`UploadHandoffCard.vue:19`（四态声明）、`:56-67`（`unavailable` 提示）、`:33`（`'上传 xlsx / zip'` 默认标签）；`AiPanel.vue:2720-2726`（`uploadHandoffAvailability() ?? 'missing'`）、`stores/ai.ts:3674-3687`（返回值只有 `waiting|missing|degraded|null`）、`:5146-5206`（`done`/`error` 只 `dropActiveForm()`，**不动** `activeUploadHandoff`）
- 触发：轮次终止后交接卡仍在（FE-25 的当前形态）；英文界面
- 效果：(a) "任务已结束，仅供查看"这条守卫**永不出现** ⇒ 卡面仍是 `waiting`、主按钮可点，点下去会对已收口的轮次做一次回灌（FE-23 的可见面）；(b) `missing` 提示恒为中文硬编码（还写死"导入向导第 1 步"），`degraded`/`unavailable` 兜底句与默认标签同为硬编码 ⇒ en-US 界面中英混排
- 测试：`tests/ai-upload-handoff.spec.ts` 覆盖三态，不断言 i18n，也无"轮次已结束"用例

**FA4-17｜「放弃」对"不属于本卡的交接"同样可点，且作用于单例 ⇒ 取消的是新卡**
- Sev **S4** / Cert **C**
- path：`UploadHandoffCard.vue:101-108`（无条件渲染 cancel）、`AiPanel.vue:646-653`（每张卡都接同一个 `ai.cancelUploadHandoff()`）、`stores/ai.ts:3642-3648`（作用于 `activeUploadHandoff`）
- 触发：同页两张交接卡（旧卡 `missing`、新卡 `waiting`）
- 效果：点旧卡的「放弃」→ 终止的是**新卡**正在等待的交接（"点了这张，断的是那张"）
- 测试：无（PK-12 记为"无 live 双卡用例"，本条给出组件级机制）

**FA4-18｜隐藏字段的复核问题仍进 live region；本地问题不随编辑清除**
- Sev **S4** / Cert **C**
- path：`FormRenderer.vue:209`（`role=alert` 的 `liveSummary`）、`:732-740`（遍历 **全部** `schema.fields`）、`:700-715`、`:466`（视觉只渲染 `visibleFields`）；对照 `form-schema.ts:653-656`（`checkFormIssues` 跳过不可见字段，但**已记录**的 `localIssues` 不会因 visibility 变化被剔除）
- 触发：提交失败 → 改父值把出问题的子字段设为不可见
- 效果：视觉错误条消失，读屏仍播报"「X」为必填项"，且一直播到下一次提交；用户与读屏使用者看到的错误集不一致
- 测试：无

**FA4-19｜上下文浮层拉取失败会永久停在"加载中"（无错误、无重试）**
- Sev **S4** / Cert **C**
- path：`ContextPopover.vue:50`（`parts.length===0` ⇒ loading 文案）、`ContextPill.vue:67-73`（打开即 `void ai.loadContext()`）；`stores/ai.ts:1654-1656`（catch 静默）；`done` 帧会先 `contextBreakdown=null`（`:5179-5181`）⇒ 必然落到"空 ⇒ 加载中"
- 触发：`GET /api/ai/context/{sid}` 503/网络失败后打开 pill
- 效果：弹层一直显示"正在加载用量明细…"，没有失败态也没有重试入口；开合只重复静默失败
- 测试：`tests/context-pill.spec.ts` 只覆盖"有数据/无数据"两态

**FA4-20｜结果视图：列截断零提示；`kind=text` 的 `empty.text` 永不被使用**
- Sev **S4** / Cert **C**
- path：`ResultViewRenderer.vue:64`（空态分支只对 `kind==='table'`）、`ResultViewTable.vue:33-39`（只认 `meta.truncated`）、`ResultViewText.vue:17-21`（只取 `description` / `fallback.text`）；`utils/resultview.ts:284`（列裁到 50）、`:320-322`（置 `status='partial'` 但**不**写 `meta.truncated`）
- 触发：表格列数 > 50；或 text 视图 `status='empty'` 且给了 `empty.text`
- 效果：用户看到 50 列且无"列被裁掉"的说明（行的截断有提示、列没有）；text 态忽略后端写的空态文案，回落到 description/fallback
- 测试：`tests/resultview-renderer.spec.ts:179-205` 的空态用例全部是 `kind='table'`

## 3. 对既有条目的确认 / 收窄 / 否证 / 补登

### 3.1 确认（不新开编号）

| ID | 本次新增证据 | 结论 |
|---|---|---|
| **FE-15** | `AiSettingsView.vue` 全文件无 `useWorkspaceStore`/`enterPage`/`noteAiPage`（导入区 `:20-25`）；`AiFeedbackView.vue:29` 同；全仓 `enterPage` 只出现在 tasks/export/import/definitions/data 五处；`router/index.ts` 无钩子重置 | **确认**：进出 `/settings/ai*` 不改变 `workspace.pageId/step/taskId`，AI 仍被披露为"在向导里"并可写看不见的任务 |
| **FE-15 / I-10** | `AiPanel.vue:3628` 只在挂载 `probeHealth()`，`:3141` 另一处；`stores/ai.ts:2718-2736` 仅 5 个错误码后重探；设置页保存只 `ElMessage.success` | **确认 + 补强**：设置页保存后**整个会话内**面板的 model/health 标签不会刷新（面板不随路由重挂）。I-10 在 §5 未收录，建议并入 FE-15 |
| **FE-32** | `ExportWizardView.vue:1682-1705` 两区域注册**无** `allowSteps`，`:19/:42/:72` 三步骤全 `v-show` | **确认**（步闸为死码；导出侧第二个实例） |
| **PK-12** | `UploadHandoffCard:101-108` + `AiPanel.vue:646-653` + `ai.ts:3642-3648` | **确认 + 细化**：见 FA4-17（不是"取消错卡"的含糊说法，而是"任何一张残留卡的放弃都会取消单例里的当前交接"） |
| **FE-25** | `ai.ts:5155-5158`、`:5193-5196`（`done`/`error` 只 `dropActiveForm()`）；`uploadHandoffAvailability()` 不看轮次 | **确认**：上传交接单例与 `stalePreviewToolCallId` 一样在终帧后残留；并补：组件里本该兜底的 `unavailable` 态无生产者（FA4-16） |
| **FE-23** | 同上 + `UploadHandoffCard` 选择按钮在 `waiting` 下恒可点 | **确认**（可见面：已结束轮次的卡仍提供"选择文件"） |
| **BZ-25** | `FormRenderer:556-570/595-597` + `form-schema:540-560/748-750` | **确认 + 补非对抗触发路径**（FA4-11(b)） |
| **ID-03 / BZ-23** | `AiSettingsView:365-374`（探活）、`:255-285`（只提交变化字段的"双保险"前端半边）、`:299-313`（清 key） | **确认**：设置页是这两个后端条目的唯一操作面；前端"只带变化字段"确实避免了"改显示名即重跑 URL 闸"，但**探活仍可把库里 key 发到库里 baseUrl**（ID-03 语义不变） |
| **MD-08** | `api/aiConfig.ts:58` 有 `AiDefaults.version`，页面既不读也不回传；provider 视图无版本字段 | **确认**：本页无乐观并发，并发编辑静默互相覆盖（写面 CAS 缺失的展示侧证据） |
| **ID-07 / MD-14** | `AiFeedbackView.vue:44-51,73-78`；后端 `AiController:1985-2000`（`rating∈1/-1/0`，0=删） | **确认**：赞踩判定（非 1 即踩）在当前数据下正确；列表无会话过滤、`excerpt` 明文展示、上限 100 条——单用户边界内成立 |

### 3.2 收窄 / 否证

- **FE-16 的范围**：该条只适用**导入**向导。导出向导已有 `resetTaskScopedState`（`:508-526`）与 `stopJobWatchForTaskSwitch`（`:535-541`）两道，并在 `applyTaskBinding` 首行调用（`:564-566`）；`resumeJobs` 也按新 `taskId` 重取（`:440-462`）。⇒ **不比照扩到导出**；导出侧残留的是**代次缺失**（FA4-05），不是"旧作业串新任务"。
- **BZ-26 的前端半边**：`FormRenderer.vue:75` 把 `field.step` 原样给原生 `<input type="number">`，`:573-581` 把非有限值折成 `undefined`；后端 `NumberFormatException` 只在服务端。⇒ 前端**不是**该 500 的第二故障点，也无法在前端拦掉（不构成新条目）。
- **I-08 的机理需修正（但结论成立）**：`gridRefs` 不会在改绑瞬间"随组件卸载被删"（若 `el-tabs` 已挂载，组件保留到 `exportResults` 清空后的下一次渲染）；真正的跨任务字节来自于 `buildXlsx` 在**解析时**读 `taskId.value`（`:973-976`）以及 `loadGrid` 在下载前才读 `taskId.value`（`:927`）。⇒ 结论（zip 名取自起始任务、内容可能取当前任务）成立，路径描述应改为"实时读 `taskId` + 无代次"，不写"组件已卸载"。

### 3.3 补登（I 域已成立、但 §5 canonical 表未收录）

`FINAL-AUDIT.md §5` 的前端分组只有 FE-01…FE-44；I 域的 **I-08 / I-09 / I-10** 在 `I-frontend-workspace.md` 里已成文，但在 §5 无对应行（I-01→FE-15、I-05→FE-19、I-06→FE-18/20、I-11 亦无行）。建议：

- **I-08**（批量下载跨任务）→ 与 FA4-05 合并登记，避免两套措辞；
- **I-09**（失败回执仍显示"工作区已同步"）→ 独立补登，与本报告的 FA4-03/FA4-04 同族（回执与事实相反）；
- **I-10**（设置页保存后面板标签不刷新）→ 并入 FE-15。
- 另：**I-11**（一批 `ui_event` 分支无模型可达入口）在 §5 无行，且与本组无冲突，交由 FA6/控制器裁决。

## 4. 测试真实性新增（本组文件的假绿/盲区）

| # | 事实 | 后果 |
|---|---|---|
| 1 | `tests/export-wizard-batch-download.spec.ts:84-89` 明写"本仓库测试不装 element-plus（未解析组件丢弃插槽）⇒ `gridRefs` 为空，`buildXlsx` 取文件回落支。**两支路同名同源**" | 把"grid 存在但内容未加载"这条真实分支排除在断言之外 ⇒ **FA4-01 在该 spec 里结构性不可见**；且"同名同源"这个前提本身是错的 |
| 2 | `tests/export-wizard-step-outcome.spec.ts` 列举"三条真实入口 + openEditor"并逐一注入失败 | 缺第五入口 `export.start` ⇒ **FA4-02/03 无回归锁** |
| 3 | `tests/ai-settings-view.spec.ts`（490 行）覆盖掩码/只带变化字段/默认目标二元组/code 文案 | 不覆盖 fetch→切 provider、探活目标、保存后卡片重置、删除当前 provider ⇒ **FA4-09/10/14/15 盲区** |
| 4 | `tests/gf-renderer.spec.ts:182-214` 只断言"切父值会重新请求 `formOptionSource`" | 不断言选项内容、子值清理、本地成员校验 ⇒ **FA4-11/12 盲区** |
| 5 | `tests/resultview-renderer.spec.ts` 的空态/截断用例全部是 `kind='table'` | text 态 `empty.text` 与列截断零提示无覆盖（FA4-20） |
| 6 | `tests/context-pill.spec.ts` 全为纯函数与两态渲染 | 无拉取失败态（FA4-19） |
| 7 | `tests/ai-upload-handoff.spec.ts` 覆盖三态外观 | 无"轮次已结束"、无 i18n、无双卡（FA4-16/17） |

## 5. 未解决分支 / 运行期前提（不得据此派工的部分）

1. **`el-tab-pane` 的挂载语义**：FA4-01 的"覆盖服务端文件"需要"非激活页已挂载但未加载"。Element Plus 默认 `lazy=false`（内容立即渲染）是**框架默认值**，本次未运行验证。若实际为懒惰渲染，FA4-01 降级为"AI/全部下载取服务端字节"（不再覆盖文件，但 FA4-01 的下载侧表述仍成立）。
2. **`saveEdits` 与引擎竞速**：若某个未加载标签的 `SpreadGrid` 引擎尚未就绪，`toXlsxBlob()` 抛"表格尚未就绪"（`SpreadGrid.vue:497-500`），则该次保存失败而非覆盖。是否"先抛错"取决于点击时机——未验证。
3. **Jackson 对 `""` → `Integer` 的强制转换**：`AiSettingsView.vue:483-491` 把 `v-model.number` 清空后的 `''` 提交为 `maxContext`。空串是被反序列化成 `null`（"不动"）还是 400，取决 Jackson 版本/特性 —— **未验证，故不立条目**。
4. **`export.start` 与 `jobRunning` / 后端四守卫的交互**：`export.start` 不看 `jobRunning`（`:1485-1492`），后端 `JobService` 的并发/终态拒绝语义属 FA3/后端域，本报告未追；因此"AI 可起第二个作业"的确切失败码未定。
5. **`withBusy` 的重入**：`withBusy` 只置标志、不排队；按钮 `:disabled` 是唯一闸。`<el-button>` 的 loading 是否 100% 吞掉双击由组件实现决定，未验证。
6. **`<select :value>` 在值不在 options 内时的显示**：FA4-11(b) 依赖浏览器"无匹配项 ⇒ 显示空白"的规范行为（HTML 标准），未在任何浏览器实机验证。
7. **数据源后端是否也解析不到选项**：FA4-11 的"两侧都放行"分支要求服务端 `GenerativeFormRules.runtimeOptionValues` 对无选项场景同样放行（BZ-25 已确认该机制），但"sourceId 数据源返回空数组时后端是否放行"未逐行复核（属 FA1/后端）。
8. **`workspace.step` 与 `viewStep` 是否可能互相触发**：静态看是单向（`viewStep` 不写 `workspace`），未加运行期断言。
9. **`FormRenderer` 的 `interactive`/`scope` 是否为常量常量安全**：`AiPanel.vue:399-414` 的只读卡与 `:613-627` 的待填卡处于不同父节点 ⇒ 实例不复用，`props.readonly` 不会后置翻转。**本次判定为"无缺陷"**；若未来改为同实例翻转只读，`:348-349` 的非响应式 `interactive` 会让同页两卡 DOM id 撞车（记录为设计约束，不立条目）。
10. **`refreshResults` 与 `gridDirty` 的"误报"**：`onBeforeUnmount` 的提醒是否真的会在编辑被丢弃后再弹一次，取决于是否走同一生命周期，未实机验证（FA4-06 的次生表述仅按代码推断）。
11. **`AiFeedback` 的 `updatedAt` 为空**（历史行）：`AiFeedbackView.vue:74` 用可选链，渲染为空单元格；未验证是否存在该历史行。
12. **`resultview` 的 `fallback.text` 长度无上限**：`fallbackTextOf`（`utils/resultview.ts:189-200`）直接取原文，`ResultViewRenderer` 无字节尺；由于帧本身有 64KB 上限，当前不可放大——**不立条目**，仅登记。

## 6. 计数

| 类别 | 数量 | 明细 |
|---|---:|---|
| 新增条目 | **20** | S2 ×2（FA4-01、02）｜S3 ×9（03、04、05、06、07、08、09、10、11、12 → 实为 10 条；见下表修正） |
| — 按 Sev | S2 2｜S3 10｜S4 8 | 逐条见 §2 |
| — 按 Cert | C 16｜R 4（FA4-05、08、12 及 FA4-01 的运行期前提另注） | — |
| 对既有条目 | 确认 10 条（FE-15/32/25/23、PK-12、BZ-25、ID-03、MD-08、ID-07、I-10）｜收窄/否证 3 条（FE-16 范围、BZ-26 前端半边、I-08 机理）｜补登 3 条（I-08、I-09、I-10） | — |
| 测试真实性新增 | 7 条 | §4 |
| 未解决分支 | 12 条 | §5 |

> 逐条复核后的准确分布（以 §2 编号为准）：S2 = FA4-01、02；S3 = FA4-03、04、05、06、07、08、09、10、11、12（10 条）；S4 = FA4-13、14、15、16、17、18、19、20（8 条）。合计 20。

### 建议派工优先级（只按"用户可感知损害 × 证据强度"，不含修法）

| 序 | 条目 | 一句话 |
|---:|---|---|
| 1 | **FA4-01** | 一次「保存修改」就可能把服务端导出文件换成空表，界面还说"已保存" |
| 2 | **FA4-02** | 条件写失败后仍起作业 ⇒ 导出全量而界面显示已筛选 |
| 3 | **FA4-06** | 退回上一步再前进就静默吃掉未保存的表格编辑 |
| 4 | **FA4-05 / 07 / 08** | 换任务与作业失败窗口里的"说 A 实际给 B"、"文件看着没了"、"已结束任务变可编辑" |
| 5 | **FA4-11** | 级联字段提交用户看不见的旧值（BZ-25 的自然触发路径） |
| 6 | **FA4-03 / 04** | 两条"回执与事实相反"（假成功、字符串折成 ok） |

## 7. 不声称

1. 不声称本报告的 20 条**已被修复**或已被任何人确认；全部为本次静态阅读所得。
2. 不声称"这 11 个文件已无更多缺陷"：§5 的 12 条运行期前提未消解；`AiSettingsView`/`ExportWizardView` 的行为断言依赖后端 DTO 与 Element Plus 版本语义。
3. 不声称 §2 的 S2 两条在真实浏览器里必然复现（前提已逐条写明）。
4. 不声称 `FINAL-AUDIT.md §8.1/§8.2` 的"已逐行"计数可以据此更新——**须控制器确认本报告后再并入**。
