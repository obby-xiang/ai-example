# 勘察 · AI 可视可操作专项（DS-Flash，只读勘察，未改主仓）

> **归档说明**：2026-10-26 归档入库（原件创作日期见正文「日期」字段，本批不新增、不改写结论）。源件 = `<LOCAL_WORK>/_shared/勘察-可视可操作专项-DS-Flash.md`，正文逐字保留，仅路径 / 会话标识 / 用户名脱敏；本件为**勘察时点结论**，非现行基线，不表示当前已验证或已修复。

- 日期：2026-10-21
- 勘察对象：`docs/research/设计-AI可视可操作专项-2026-10-21.md`（索引第 42 件）+ `调研-AI可视可操作能力业界惯例-2026-10-21.md`（第 41 件）
- 基线：主仓 `<REPO_ROOT>` @ `a5539c2`（工作树净，仅未跟踪 `data/`）
- 性质：**勘察与矩阵草案，不构成施工令**；未起服务、未跑测试、未改任何文件

## 0. 自证（第 0 步）

`<KIMI_SESSIONS_ROOT>/<WORKSPACE>/<KIMI_SESSION_A>/agents/agent-1369/wire.jsonl` 首行 `profile.bind`：

```
{"type":"profile.bind","agentId":"agent-1369","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}
```

## 1. 结论速览（九条）

1. **设计稿的落地路径成立**，前端零新帧、后端只加哨兵桩的形态与仓库现状逐条对得上（取证见 §2、附录 A）。
2. **`data-ai-*` 仍是全仓零命中**（`grep -a "data-ai-" --include=*.ts --include=*.vue --include=*.java` = 0）⇒ 本专项是纯新增面，无「改造既有标注」的包袱。
3. **设计稿的 `stores/ai.ts` 行号已整体漂移约 +55~+80 行**（`:2739-2763` → 实际 `:2798-2820`；`:2760`「前端工具没有 HITL」→ 实际 `:2819`；`:3059` → `:3139`）。**施工时一律按符号名定位，不按行号**。
4. **帧契约已从 15 冻结为 16**（`context` 帧已随上下文专项批① 入库，`frontend/src/types/sse.ts:113-145`）。本专项 **零变更**，无任何契约诉求（§8）。
5. **三道围栏里有两道要新建、一道可复用**：区域围栏新建、元素围栏新建、**上下文围栏复用 `matchesScope` + `allowPages/allowSteps/needsTask` 三段口径**；后端入参闸**复用 `FrontendToolGuard` SPI**（新 Bean，`SpToolCallingManager` 零改动）。
6. **consequential 拒执是纯前端性质**，不需要新建任何 HITL：`kindOf()` 通道优先（`SpToolCallingManager.java:982-990`）已保证前端工具永远进不了后端确认门 ⇒ 人围栏只能落前端，而本专项对「写」的人围栏 = **不执行**，唯一新增的卡是上传交接（动作是用户手势，不是批准）。
7. **新发现一处设计内部矛盾（必须裁）**：`data-ai-ref` 段字符集写的是 `[a-z0-9-]+`，但同稿示例用 `import.selectDefs.CURRENCY`，且本仓 E2E 真实用到 `CURRENCY`（大写）与 `E2E_TEMP`（下划线）⇒ 现稿的 Guard 正则 `^[a-z0-9-]+(\.[a-z0-9-]+){2,}$` 会**误杀真实 ref**。
8. **「前端工具优先 lodash」在本仓无可复用对象**：`frontend/package.json` 无 `lodash`/`lodash-es`，源码零 import；`tailwindcss@3.4.17` 在。后端 `pom.xml` 亦无 `commons-lang3`/`guava`（源码零命中）⇒ A-5 组件优先级在本专项只能落「spring-ai + 原生 / Tailwind」，要 lodash 得先加依赖（待裁）。
9. **表格专项正在 8 个共享文件上施工（未提交）**，其中 `AiPanel.vue` 已经在 `v-if part.requiresAction` 链上插了 `v-else-if="isSheetPreviewPart(part)"`（wt 内 `:529`）⇒ 本专项的第三条支**必然紧邻冲突**，但可 rebase（§7）。

## 2. 现状取证与漂移表（设计稿行号 × 实测）

| 面 | 设计稿写的 | 实测（`a5539c2`） | 处置 |
|---|---|---|---|
| 前端工具名 | `types/tools.ts:79` 2 个 | ✅ `:78` `FrontendToolName = 'open_export_file_editor' \| 'download_export_files'` | 施工按符号 |
| 前端工具登记表 | `:82-107` 2 条 | ✅ `:81-108` `FRONTEND_TOOLS` 2 条，均 `riskLevel:'READ'`、`scopePatterns:['task:EXPORT/EXPORT']` | — |
| `ToolArgSpec` 类型 | 未提 | `:59` 只有 `string\|number\|boolean\|string[]`（**无 `object`**） | 本专项 7 工具可全用这 4 类（`regions`/`kinds` = `string[]`）⇒ **不需要扩类型**，正好避开表格的 `object/object[]` 改动 |
| 四路由 | `frontend-tools.ts:484-508` | ✅ `:484` `dispatchFrontendTool`，路由 1/2/3/4 与稿一致 | 新增原语走路由 1 |
| scope 匹配 | `:121-146` | ✅ `:121` `matchesScope` | 复用 |
| 统一预算 | `:235` 60s、`:278-291`、`:358-365` | ✅ 全命中（`:235` 常量、`:278` 折算、`:358` `executeFrontendTool` race） | `ui_request_upload` 必须绕出 |
| 结局工厂 | `:100-116` `makeExecution` | ✅ `:100` | 强制复用 |
| 默认执行器 | `:1124-1147` | ✅ `:1124` `defaultExecutor`（只特判 editor） | 7 个原语挂这里 |
| 三级兜底 | `:918-936` | ✅ degraded「本页未注册该能力」 | 区域未注册复用同一文案形态 |
| 工作区动作表 | `:648-707`、别名 `:726-733` | ✅ `:648` `WORKSPACE_ACTIONS` 4 条 | 上下文围栏口径来源 |
| 三段上下文判定 | `:785-805` | ✅ `:785-804`（page/step/taskId 三段，文案可复用） | 复用形态 |
| 帧分支 | `stores/ai.ts:2739-2763` | ❌ 实际 `:2798-2820` | 按 `case 'frontend_tool_request'` 定位 |
| 「前端工具没有 HITL」 | `:2760` | ❌ 实际 `:2819` | 同批订正 |
| GF 特判样板 | `:2127-2162` | ❌ 实际 `openGenerativeForm` `:2182-2210`（解析失败即 `cancelled:true` 回灌） | 照抄 |
| 执行与回灌 | `:3059-3118` | ❌ 实际 `runFrontendTool` `:3139-3198`、`submitFrontendToolResult` 定义 `:2102`、调用 `:3197` | 只读不改 |
| 刷新恢复 | `:1264-1380` | ❌ 实际 `restoreSuspendedSnapshot` `:1319` 起（判据/段写点随之下移） | 不改判据 |
| `activeForm` 状态位 | `:769` | ✅ `:769` | 新增 `activeUploadHandoff` 紧邻 |
| 页面能力注册表 | `workspace.ts:269-282` | ✅ `:271-281`（非响应式 Map） | `registerAiRegion` 照此形态，**另开模块** |
| `PageHandlerName` 11 个 | `types/workspace.ts:14-25` | ✅ `:14-25` 11 名（含 `import.upload`） | +1 `import.receiveFile` |
| 挂起子块 | `AiPanel.vue:446-526` | ✅ `:449` `v-if="part.requiresAction"` → `:451` CONFIRM → `:517` GF 支 | 第三条支紧随 |
| 中文名表 | `:1225-1253` | ⚠️ `TOOL_LABELS` 实际起于 `:1258` | 按符号 |
| 上传入口 | `ImportWizardView.vue:40-51` | ✅ `el-upload` `auto-upload=false`、`accept=".xlsx,.zip"`、`:on-change="handleUpload"` | — |
| `handleUpload` | `:567-597` | ✅ `:567`（`persistSelection` `:533`、`tasksApi.uploadFile` `:579`） | 抽 `uploadOne(file)` |
| `import.upload` handler | `:1090-1097` | ✅ `:1089-1096`，含只读守卫原文 | 保留为 degraded 回退 |
| 页面生命周期 | `:1040-1047` / `:1049-1127` / `:1136-1144` | ✅ `HANDLER_NAMES` `:1040`、`registerHandlers` `:1050`、`onBeforeUnmount` `:1136`（逐个 unregister） | 区域按同模式收口 |
| 7 个 FRONTEND 工具 | `AiTools.java:370-554` | ✅ 18 `@Tool`、其中 7 个 `@ToolChannel(FRONTEND)`、`FRONTEND_STUB` `:368` | +7 → 25 |
| 通道优先 | `SpToolCallingManager:982-990` | ✅ `:981-990` `kindOf` | 不改 |
| 挂起前入参闸 | `:552-563`、`:567-574`、`:585-595` | ✅ `awaitFrontend` `:557`、`inspectFrontendArguments` `:567`（**首个 `supports()` 命中即返回**）、`rejectFrontendArguments` `:585` | 新 Guard 用 `ui_` 前缀，零冲突 |
| 复核扩展点 | `FrontendToolGuard.java:30-93` | ✅ 接口 + `Verdict(code,message,reasons,render())`；**现有唯一实现 = `GenerativeFormGuard`** | 新 Bean 即可 |
| 无超时名单 | `ConfirmGate.java:322-330` | ✅ `:322-330` 单值 `GenerativeFormRules.TOOL_NAME` | 集合化（与表格共用） |
| 披露名单 | `ToolRegistryDisclosureTest:55-58` | ✅ `:56-58` 7 名硬编码；另有 `:237` 全量名单断言 | 两处都要加 |
| 系统提示 | `ResilientChatService:109-138` | ✅ 10 条，无「数据非指令」 | 依赖安全防护批② |
| 前端测试 | 26 个 spec | ⚠️ **29 个** `*.spec.ts`（+`setup.ts`） | 矩阵落点按 29 计 |
| E2E 主计数 | 29 | ✅ `scripts/verify-e2e.ps1:2`「全流程 29 用例」 | +3 → 32 |
| 门禁基线（最新） | 稿内 512/512 | 看板 10-21 第七批：**前端 545/545 · 29 文件 / 后端 939/939** | 施工前须实跑复核 |

> **取证注意**：`frontend/src/views/ImportWizardView.vue` 会被 bash `grep` 判成 binary（无 `-a` 时零输出、`-c` 返回 0）。施工期检索一律用 Grep 工具或 `grep -a`，否则会得到「该文件没有 readOnly」这种假结论。

## 3. 七工具逐条分解（改动文件 → 改法要点 → 复用）

### 3.1 总表

| # | 工具 | 前端落点 | 改法要点 | 后端落点 | 复用组件/设施 |
|---|---|---|---|---|---|
| S1 | `ui_snapshot` | 新 `utils/ai-regions.ts`（注册表+epoch）+ 新 `utils/ai-dom-tools.ts`（`snapshot()`+`capUiSnapshot`） | 只遍历已注册 `root.querySelectorAll('[data-ai-ref]')`；`kinds`→区域→`limit` 三级过滤；尾行恒在；同 epoch+同参 1 条缓存 | `AiTools.java` +1 桩；新 `ai/tool/UiPrimitiveGuard.java`（先只校本工具入参） | `FRONTEND_TOOLS` 登记 + 路由 1（`runRegisteredTool`：`validateArgs` → `matchesScope` → executor → 三级兜底）+ `makeExecution` |
| S2 | `ui_click` | `ai-dom-tools.ts#clickRef`；`stores/ai.ts` 撤销栈；`AiPanel.vue` 摘要行 | 区域→上下文→元素→风险四段链；`consequential` **零点击** + 指路映射表；`data-ai-undo="retoggle"` 才记撤销点；`mutate-local` 必 `touch()` | +1 桩（`@ToolRisk(WRITE)`） | `workspace.touch(summary,'AI')`（`workspace.ts:212-223`）、`makeExecution` 三态 |
| S3 | `ui_fill` | `ai-dom-tools.ts#fillRef` | 原生 setter + `input`/`change`；**写后回读**不一致 ⇒ `failed` 不谎报；`data-ai-fill="allow"` 缺省 deny | +1 桩（`WRITE`） | Vue `v-model` 只认这两个事件（`tests/` 里用 `@vue/test-utils` 断 `modelValue`） |
| S4 | `ui_select` | `ai-dom-tools.ts#selectRef` | 路径 1 区域 `setters[ref]`；路径 2 点开→`[data-ai-option]` **精确匹配**→点选→回读 | +1 桩（`WRITE`） | Element Plus 弹层自绘 ⇒ 必须 setter 优先（调研 §7.2 的结论在仓内同样成立） |
| S5 | `ui_scroll` | `ai-dom-tools.ts#scrollTo` | 只改视口；**不 `touch`**、不记撤销点 | +1 桩（`READ`） | 原生 `scrollIntoView({block})` |
| S6 | `ui_highlight` | `ai-dom-tools.ts#highlightRef` + `AiPanel` `aria-live` 区 | 描边+图标+文案三重冗余（WCAG 1.4.1）；`prefers-reduced-motion` 静态描边 | +1 桩（`READ`） | Tailwind 类（`ring-2 ring-[#2563eb]` 一类），**禁手写 style**（A-5 纪律） |
| S7 | `ui_request_upload` | 新 `components/AiPanel/UploadHandoffCard.vue`；`stores/ai.ts#openUploadHandoff`；`ImportWizardView.vue` 抽 `uploadOne` + `import.receiveFile` + `notifyAiUploadDone` | 高亮入口 + 对话内「选择文件」（**手势内同步**调 `showOpenFilePicker`，之前不得有 `await`）+ 无 API 回退 + 「用户自己传了」联动收口；`cancelled=true` 走既有 `FRONTEND_CANCELLED` | `ConfirmGate` 集合扩项 + `@Tool` 桩 | GF 无超时样板（`openGenerativeForm:2182`）、L0 建卡（`restoreSuspendedSnapshot:1319`）、既有上传链（`handleUpload:567`） |

### 3.2 逐条补充（设计稿未写死、施工必须定的细节）

**S1 `ui_snapshot`**
- 新文件职责切分：`ai-regions.ts` 只管「谁被注册 / ref 反查 / epoch」；`ai-dom-tools.ts` 只管「遍历 / 裁剪 / 动词」。这样表格专项将来若要 `data-ai-*`（大概率不会，见 §7）不会抢文件。
- `epoch` 形态 `'<6位base36 nonce>-<counter>'`：nonce **模块加载期**生成（`crypto.getRandomValues` 或 `Math.random` 皆可，只要每次加载不同）；`registerAiRegion` / `unregisterAiRegion` / `pageId` 变化都 `counter+1`。
- 角色过滤建议用**显式映射表**而非 `el.matches('button')`：`button`（button/[role=button]）、`input`（input:text/textarea/[contenteditable]）、`select`（select/[role=combobox]）、`toggle`（input:checkbox/radio/[role=switch]/[aria-pressed]）、`tab`（[role=tab]）、`link`（a[href]）。Element Plus 的 `el-radio` 渲染成 `input[type=radio]`（原生）→ 可覆盖；`el-select` 是自绘弹层 → 只能作为 `select` 的**触发器**出现在快照里，取值走 setter（与 S4 一致）。
- 常量集中：`UI_SNAPSHOT_DEFAULT_ROWS=20 / MAX_ROWS=40 / MAX_CHARS=2000 / LINE_MAX_CHARS=120 / VALUE_MAX_CHARS=24 / WRITE_VERBS_PER_TURN=12 / UNDO_DEPTH=1`。

**S2 `ui_click`**
- 判定链顺序不可换：`resolveAiRef`（区域）→ 页面/步骤/任务（上下文）→ 内置 deny + `data-ai-deny` + `data-ai-verbs` + `isConnected` + `epoch`（元素）→ 风险分流。
- 「read」的定义是**标注责任**：本仓 `data-ai-risk="read"` 的按钮若触发服务端写，围栏拦不住（设计稿 §8.2 已诚实登记）。建议批③ 把 `data-ai-risk` 的**默认值设为区域 risk**，且**区域默认 risk 必须显式给**（`registerAiRegion` 缺 `risk` 抛错）。
- 撤销点只在 `data-ai-undo="retoggle"` 上记「再点一次」；`ui_fill`/`ui_select` 记旧值。栈在 `stores/ai.ts`（不进镜像）。
- 摘要行数据源：**建议不新增 `ToolPart.uiSummary` 字段**，直接从 `part.result` 第一行派生（少一个类型改动 = 少一处与表格 `types/ai.ts` 的冲突）。此项列入待裁（§9-N4）。

**S3 `ui_fill`**
- 只对**原生** `input`/`textarea`/`[contenteditable]` 生效；Element Plus 复合控件（`el-input-number`、`el-date-picker`、`el-cascader`）**批② 不上**，标 `data-ai-fill="deny"` 或干脆不给 `data-ai-ref`。理由：这些组件内部有格式化与校验替换，回读不一致率极高，会把「不谎报」变成大量 `failed` 噪声。
- `value` 上限 200 字符在**前端执行器**与**后端 Guard** 各校一次（双通道同源，R4）。

**S4 `ui_select`**
- `setters` 是 `registerAiRegion` 的可选字段，键 = 元素 `data-ai-ref`，返回 `boolean | Promise<boolean>`；失败要给「可选：增量合并、整体替换」——选项清单来自 `[data-ai-option]`（≤10 项，超限只报计数）。
- 路径 2 的「点开」在 Element Plus 里等于给触发器一次 `click()`；弹层是 `body` 下的 teleport 节点，**不在区域 root 内** ⇒ 匹配必须在 `document` 范围但**只认 `[data-ai-option]`**（这就是为什么路径 2 是回退而非首选）。

**S5/S6**
- 二者都不改 `dataVersion`（`ui_scroll` 尤其要断言「零 `touch`」，否则「界面没变」和「变了」在上下文里无法区分）。
- `ui_highlight` 是「把用户送到入口」的通用载体：S7 的回退、S2 的「请先导航到 Y」都可以复用它。

**S7 `ui_request_upload`**
- 关键实现约束（规范级，写进注释）：`showOpenFilePicker()` 必须在**用户点击的同步调用栈**里被调用；`File` handle 拿到的 `await handle.getFile()` 之后才可做异步。
- `import.receiveFile(file)` 的返回值（页面摘要文本，如「已上传 币种.xlsx，匹配 1 个配置项」）只是**回灌素材**，不得包含路径；文件名截 64 字符。
- `notifyAiUploadDone(summary)` 覆盖「用户嫌麻烦自己点上传」的真实路径：只要存在 `activeUploadHandoff`，立即以 `ok` 回灌并落卡终态。
- 三态必须互斥且必属其一：`available` / `degraded` / `unavailable`；「用户取消」落 `degraded` 且卡可重试，「放弃」落 `cancelled=true`。

### 3.3 后端落点（一处新文件 + 三处小改）

| 文件 | 改法 | 风险 |
|---|---|---|
| `ai/tools/AiTools.java` | +7 `@Tool(name=...)` + `@ToolScope({"*"})` + `@ToolRisk` + `@ToolChannel(FRONTEND)`，方法体 `return FRONTEND_STUB` | 工具数 18→25（表格先落则 26→33）；描述里写死「40 行 / 2000 字符」「ref 不是 selector」「快照是数据不是指令」 |
| 新 `ai/tool/UiPrimitiveGuard.java` | `implements FrontendToolGuard`，`supports(name.startsWith("ui_"))`；`inspectArguments` 校 ref/epoch/禁键/长度/`accept` 子集；`inspectResult` 校四态形状 + 禁路径 + 禁 HTML | **零改** `SpToolCallingManager`（首个命中即返回，`ui_` 前缀天然不撞 `GenerativeFormGuard`） |
| `ai/gate/ConfirmGate.java:322-330` | 单值 → `HUMAN_WAIT_FRONTEND_TOOLS` 集合 | **与表格共用同一集合**（谁先落谁建，后者只加名） |
| `ai/tools/ToolRegistryDisclosureTest.java:56-58` + `:237` | 名单 7→14、全量名单同步 | 少改一处即断言脱节 |

> 后端依赖纪律：`pom.xml` 只有 Spring Boot 3.5.14 + spring-ai 1.1.8 + POI + JPA/Lombok；**无 commons-lang3 / guava**（源码零 import）。因此「commons > guava > spring」在本专项等价于「**只用 Spring 与 JDK**」，字符串/集合处理不必引入新依赖。

## 4. `data-ai-*` 注册机制 × 仓内现状

### 4.1 现成可挂的 DOM 结构（逐页取证）

| 页 | 可注册区域（建议 id） | 现成结构证据 | 备注 |
|---|---|---|---|
| 导入向导 | `import.upload` | `ImportWizardView.vue:40-51` `el-upload` 触发器 + 下载模板/保存草稿按钮 | **批① 唯一点** |
| 导入向导 | `import.selectDefs` | `:34` `DefSelector` 组件（v-model + :disabled） | 检查框在子组件内 ⇒ `root` 传 `DefSelector` 的包裹 div，元素由子组件加 `data-ai-ref` |
| 导入向导 | `import.mode` | 模式单选（导入模式 MERGE/REPLACE） | `setters` 走 `workspace.setImportMode` |
| 导入向导 | `import.grid` | `SpreadGrid.vue`（canvas 自绘） | **不可注册**（单元格无 DOM）⇒ 归表格专项；只可把「编辑区容器」作为 `ui_scroll` 目标 |
| 导出向导 | `export.conditions` / `export.selectDefs` | 批② 试点 | — |
| 任务中心 | `tasks.list` | 批③ 试点 | 行级元素用**业务键**（`tasks.list.<taskId>.open`，`taskId` 是数字 ⇒ 见 §9-N1 字符集冲突） |

### 4.2 挂载点模式（与现有页面能力注册表同形）

```ts
// 现成范式（stores/workspace.ts:271-281，非响应式 Map）
registerPageHandler(name, handler) / unregisterPageHandler(name) / getPageHandler(name)

// 本专项照此另开模块（放 utils/，不进 stores/，避免与表格抢 workspace.ts）
registerAiRegion({ id, root, risk, label?, verbs?, setters?, uploadEntry?, receiveFile? })
unregisterAiRegion(id)
listAiRegions()
resolveAiRef(ref)
currentAiEpoch()
```

页面生命周期照抄 `ImportWizardView.vue:1040`（`HANDLER_NAMES`）→ `:1050 registerHandlers` → `:1136 onBeforeUnmount`（逐个 unregister）三条式；区域必须进同一个 `onBeforeUnmount`（漏收 = 跨页脏 ref，且 `isConnected` 拦得住「元素没了」，拦不住「ref 指向另一页同名元素」）。

### 4.3 与现有 DOM/组件约定的三处张力

1. **`data-ai-label` 与 aria 现状**：`ImportWizardView.vue` 的 `aria-label` 覆盖极低（调研 §9.6 记「零命中」）。label 推导链 `aria-label → title → textContent(截24)` 在本仓会大量落到 `textContent` —— 中文按钮文案是稳定的，可接受；但**这也意味着 label 含可见文案**，切英文后 label 变、ref 不变（设计稿已把 ref 的 locale 无关性列为第一性质，T-44 锁）。
2. **Element Plus 自绘组件**：`el-select`/`el-radio-group`/`el-date-picker` 的 DOM 结构不适合盲点；必须靠 `setters` 与 `[data-ai-option]`。**不要**试图用 `querySelector('.el-select-dropdown__item')` 一类类名匹配（既脆又等于让实现依赖 Element 版本）。
3. **`el-upload` 的手势边界**：`el-upload` 自带隐藏 `input[type=file]`，其 `click()` **不是**用户手势下的 `showPicker`；AI 侧只能「高亮 + 让用户点」，或走 `showOpenFilePicker`（Chrome）并自己把 `File` 交给 `uploadOne`。**不得** `input.click()` 冒充用户操作（在无手势时会被拒，且是规范级反模式）。

## 5. 三道围栏与 consequential 拒执的落点（复用 vs 新建）

### 5.1 现状已有的「闸门」清单（先认清不重叠的部分）

| 现有闸门 | 位置 | 管什么 | 本专项关系 |
|---|---|---|---|
| 后端风险确认门 `ConfirmGate`（KIND_CONFIRM） | `SpToolCallingManager:981-990` 首判通道 | `@ToolRisk(DANGER)` 的后端工具 | **前端工具永不进入**（通道优先）⇒ 本专项**不新增**确认卡 |
| 后端 scope 围栏 `blockOutOfScope` | `SpToolCallingManager:322` + `toolRegistry.matchesContext` | 工具披露范围（page/step/task） | 后端**已有**，本专项的 `@ToolScope("*")` 等于放弃这一层，改由前端区域围栏承担 |
| 后端入参闸 `FrontendToolGuard` | `:557-595` | 逐工具参数契约（现只有 GF） | **复用扩展点**，新 Bean |
| 前端 scope 校验 `matchesScope` | `frontend-tools.ts:121`、`:909` | 登记工具的 `scopePatterns` | 原语登记 `['*']` ⇒ 实际不拦（围栏在区域层） |
| 前端动作三段 `allowPages/allowSteps/needsTask` | `:785-804` | 工作区动作的上下文 | **复用形态**（区域声明同名字段） |
| GF 无超时挂起 + L0 建卡 | `ConfirmGate:322-330`、`ai.ts:1319`、`AiPanel:449` | 等人填表 | **复用**（上传交接走同一条路） |
| `REQUEST` 去重 / `hasDecidedPendingResidue` | `ai.ts:2794-2806` | 已决 toolCallId 不复活 | 不改 |

### 5.2 三道围栏落点判定

| 围栏 | 落点 | 复用/新建 | 判定依据 |
|---|---|---|---|
| ① 区域围栏（ref→registry 反查） | `utils/ai-regions.ts#resolveAiRef` | **新建** | 仓内无任何「ref 注册表」概念；这是纯新增能力 |
| ② 上下文围栏（page/step/task） | `ai-dom-tools.ts` 内复刻 `frontend-tools.ts:785-804` 三段 + `workspace.snapshot()`（`workspace.ts:250-263`） | **复用口径、新建代码** | 现成实现只作用于 `WorkspaceAction`，不作用于「ref」；把 `allowPages/allowSteps/needsTask` 做成区域可选字段即可同构 |
| ③ 元素围栏（内置 deny + `data-ai-*` + isConnected + epoch） | `ai-dom-tools.ts` | **新建** | 全仓零命中 |
| ④ 后端入参闸（ref/epoch/禁键/长度） | 新 `ai/tool/UiPrimitiveGuard.java`，挂 `FrontendToolGuard` SPI | **复用机制、新建实现** | `inspectFrontendArguments` 首个 `supports()` 命中即返回（`:567-574`），加 Bean 零改框架 |
| ⑤ 人围栏（唯一一处） | `ConfirmGate:322-330` 集合 + `ai.ts` 特判 + `AiPanel` 第三条支 | **复用全套，仅扩名单/加分支** | GF 样板已把「无超时挂起 + 取消收敛 + L0 重建」跑通三批 |

### 5.3 consequential 拒执落点

- **只在 `ai-dom-tools.ts#clickRef` 的第四段**：命中 `data-ai-risk="consequential"` ⇒ 直接返回 `makeExecution({outcome:'failed', availability:'available', result: '未执行：…请改用注册动作 X'})`，`element.click()` **调用次数恒 0**（测试用 spy 锁死）。
- **不新建任何 HITL**：后端 `ConfirmGate` 收不到前端工具的确认请求（通道优先），这是本设计的**安全性质**而非缺陷——通用原语结构上无法成为绕过确认门的后门。
- 与 `blockOutOfScope` 的关系：后端 `@ToolScope("*")` 使 `blockOutOfScope` 对这些工具恒放行，**要接受这一点并写进设计注释**（披露面 ≠ 可达面）。

## 6. 测试矩阵草案

维度代号：`RG`=区域围栏 `CX`=上下文围栏 `EL`=元素围栏 `SZ`=尺寸闸 `RK`=风险分级 `WR`=写入正确性 `UN`=撤销 `QT`=配额/中断 `UP`=上传三态 `RS`=刷新恢复 `CT`=契约终态 `AU`=审计 `DR`=防漂移 `I18`=ref 稳定性

| ID | 用例 | 维度 | 期望 | 落点文件 | 证红方式 |
|---|---|---|---|---|---|
| T-01 | 区域 14 元素、`limit=20` | SZ | 14 行 + 尾行「共 14 个元素（本次回 14 个…）」 | `tests/ai-snapshot.spec.ts`（新） | 去尾行 ⇒ 红 |
| T-02 | 30 元素、`limit=20` | SZ | 20 行 + 「共 30 …（本次回 20，已截断）」 | 同上 | 回 30 ⇒ 红 |
| T-03 | `limit=50` / `limit=0` | SZ | 参数拒绝（人话、无 JSON） | 同上 | 放开上限 ⇒ 红 |
| T-04 | 20 行但 3000 字符 | SZ | 行优先→字符截，标记含「原 20 行 / 3000 字符」 | 同上 | 只判行 ⇒ 超 2000 ⇒ 红 |
| T-05 | 无 `data-ai-ref` 的元素 | RG | 快照零命中（文案负断言） | 同上 | 自动收录 ⇒ 红 |
| T-06 | `data-ai-deny` 元素 | EL | 不进快照；`ui_click` 拒绝且**零点击** | `tests/ai-dom-verbs.spec.ts`（新） | 只挡 fill ⇒ 红 |
| T-07 | `password`/`file`/`hidden`/`autocomplete=cc-number` | EL | 四类均**不进快照**（种子值负断言） | `ai-snapshot.spec.ts` | 进了再拦 ⇒ 负断言红 |
| T-08 | 拿导入页 ref 在导出页调用 | RG | 拒绝 + 「该 ref 不在当前页面…请重新 ui_snapshot」 | `ai-dom-verbs.spec.ts` | 直接 querySelector ⇒ 误点 ⇒ 红 |
| T-09 | 过期 `epoch` | EL | 拒绝且零副作用（click/fill 计数 0） | 同上 | 不带 epoch ⇒ 红 |
| T-10 | 元素 `isConnected=false` | EL | 拒绝 + 「元素已不在页面上」 | 同上 | 对 detached click 假成功 ⇒ 红 |
| T-11 | label 推导链 | WR | `aria-label`→`title`→`textContent(截24,空白归一)` | `ai-snapshot.spec.ts` | 回全长 ⇒ 字符断言红 |
| T-12 | 同 epoch 同参连调两次 | SZ | 文本一致、遍历计数 = 1 | 同上 | 每次重扫 ⇒ 计数 2 ⇒ 红 |
| T-13 | `pageId` 变化 | RS | epoch counter+1、缓存失效 | `tests/ai-regions.spec.ts`（新） | 不 bump ⇒ 旧缓存复用 ⇒ 红 |
| T-14 | **刷新模拟**（重载模块后旧 epoch） | RS | 旧 epoch 一律不等（即使 counter 同） | 同上 | 只用计数器 ⇒ 撞号 ⇒ **红（关键）** |
| T-15 | `registerAiRegion` 缺 `risk` | RG | 抛错、无半注册状态 | 同上 | 默认 read ⇒ 红 |
| T-16 | `ui_fill` → `fill=allow` 的 input | WR | `mount` 组件后 `modelValue` 生效 | `tests/ai-dom-fill.spec.ts`（新） | 只设 `value` 不派发 `input` ⇒ 红 |
| T-17 | `ui_fill` 到未声明 `fill=allow` | EL | 拒绝 + 「该输入框未声明可填写」 | 同上 | 默认 allow ⇒ 红 |
| T-18 | `value` > 200 字符 | SZ | 参数拒绝（前后端各一次） | 同上 | 截断后写入 ⇒ 红 |
| T-19 | 页面校验器改值（回读不一致） | WR | `failed` + 「值未生效…」 | 同上 | 写完报成功 ⇒ 红 |
| T-20 | `ui_select` 有 setter | WR | setter 调 1 次、**DOM 零点击** | `tests/ai-dom-select.spec.ts`（新） | 走盲点 ⇒ spy 红 |
| T-21 | 无 setter、选项存在 | WR | 点开→精确匹配→点选→回读 | 同上 | 模糊匹配 ⇒ 选错 ⇒ 红 |
| T-22 | 选项不存在 | WR | `failed` + 「下拉选项里没有「XX」。可选：…」 | 同上 | 静默不改报 ok ⇒ 红 |
| T-23 | `ui_highlight` | WR | 高亮类 + `scrollIntoView` + `aria-live`；reduced-motion 无动画类 | `tests/ai-dom-highlight.spec.ts`（新） | 只改颜色 ⇒ 三重冗余断言红 |
| T-24 | `ui_scroll` | AU | `scrollTop` 变、`dataVersion` 不变、无撤销点 | 同上 | 顺手 `touch` ⇒ 红 |
| T-25 | fill→撤销→再撤销 | UN | 回旧值 + 「已撤销」；第二次「没有可撤销的界面操作」 | `tests/ai-dom-undo.spec.ts`（新） | 不存旧值 ⇒ 红 |
| T-26 | click 到无 `data-ai-undo` 的 read 元素 | UN | 不记撤销点、摘要行无「撤销」 | 同上 | 一律记 ⇒ 红 |
| T-27 | 单轮第 13 个写动词 | QT | 拒绝 + 「本轮界面操作已达上限（12 次）…」 | `ai-dom-verbs.spec.ts` | 不限 ⇒ 红 |
| T-28 | 第 2 个写动词时点停止 | QT | 后续零执行、已执行不回滚 | 同上 | 停止后仍执行 ⇒ 红 |
| T-29 | click `read` | RK | `click()` 1 次、无「可撤销」 | 同上 | 缺分级 ⇒ 红 |
| T-30 | click `mutate-local` | RK/AU | `click()` 1 次 + 高亮 + 撤销点 + `touch(source='AI')` | 同上 | 不 touch ⇒ 红 |
| T-31 | click `consequential`（`export.start`） | RK | **零点击** + 「未执行」+「请改用注册动作 start_export」 | `tests/ai-dom-click-risk.spec.ts`（新） | 点了再提示 ⇒ spy 红 |
| T-32 | 元素无 risk、区域 `consequential` | RK | 继承区域风险 ⇒ 拒执 | 同上 | 继承成 read ⇒ 红 |
| T-33 | 入参含 `filePath`/`path` | UP/SZ | 参数拒绝（负断言：永不接受路径） | `tests/ai-upload-handoff.spec.ts`（新） | 接受后忽略 ⇒ 红 |
| T-34 | 有 `showOpenFilePicker`（stub） | UP | 卡渲染「选择文件」；点击调 stub 1 次；`getFile()`→`import.receiveFile` 1 次 | 同上 | 自己读字节 ⇒ 违约 ⇒ 红 |
| T-34b | **手势栈**：点击回调内首个 `await` 之前必须已调 `showOpenFilePicker` | UP | 调用顺序断言（记录 `await` 前的同步段） | 同上 | 先 `await` 再调 ⇒ 浏览器抛 `NotAllowedError` ⇒ 红 |
| T-35 | 无 `showOpenFilePicker` | UP | `degraded` + 「点左侧高亮按钮，或拖到高亮区域」+ 高亮执行 | 同上 | 报 unavailable ⇒ 红 |
| T-36 | 只读页 | UP | `unavailable` + 「任务已结束…」；零高亮零按钮 | 同上 | 仍渲染按钮 ⇒ 红 |
| T-37 | 用户点「放弃」 | CT | 写调用零次、`cancelled=true`、卡落终态（不恒 running） | 同上 + `ai-panel-parts-render.spec.ts`（扩） | 不落终态 ⇒ 红 |
| T-38 | 刷新后：区域仍注册 / 已不在该页 | RS | 前者按钮可点；后者禁用 + 只有「放弃」 | `restore-reconcile.spec.ts`（扩）+ `ai-upload-handoff.spec.ts` | 一律按失效 ⇒ 前者退化 ⇒ 红 |
| T-39 | 用户自己点页面上传（`notifyAiUploadDone`） | UP | 活动交接立即 `ok` 回灌并落卡 | `ai-upload-handoff.spec.ts` | 一直等 ⇒ 红 |
| T-40 | 后端：上传帧不带 `timeoutSeconds`；`navigate_to` 仍 120 | CT | `suspendWaitSeconds` null / 120 | 后端 `ConfirmGateTest`（扩） | 名单未扩 ⇒ 带 120 ⇒ 红 |
| T-41 | 后端 `UiPrimitiveGuard`：`selector` 键 / 非法 ref / 非法 epoch | SZ | 不挂起、不发帧、`REJECTED_ARGUMENTS` + 编号原因 | `UiPrimitiveGuardTest`（新） | 无 Guard ⇒ 非法入参下发 ⇒ 红 |
| T-42 | 前端 9 条 ↔ 后端 14 名一致 | DR | 名称/风险/scope 三方一致 | `tests/ai-tool-registry.spec.ts`（新）+ `ToolRegistryDisclosureTest` | 少登记 ⇒ 「未知前端工具」⇒ 红 |
| T-43 | 工具描述与常量同源 | DR | 描述含「40」与「2000」 | 同上 | 描述写 20 ⇒ 红 |
| T-44 | 英文 locale 下同元素两次快照 | I18 | `ref` 完全一致 | `tests/ai-ref-i18n-stable.spec.ts`（新） | ref 由文案生成 ⇒ 红 |
| T-45 | 每个原语调用 | AU | 段回执含 `ref+verb+epoch`；`recentActions` 恰 1 条/次 | `ai-dom-verbs.spec.ts` | 不记 ⇒ 红 |
| T-46 | E2E 三条主用例 | CT/UP | 上传交接 / 改模式 + 撤销 / 导出拒执指路 | `scripts/verify-e2e.ps1`（29→32） | 断言失败 ⇒ 红 |
| **T-47** | 区域 root 被 `v-if` 拆掉再挂回（同 id 重注册） | RS | 旧 ref 拒绝；重注册后 epoch+1；新快照可取 | `ai-regions.spec.ts` | 复用旧 ref ⇒ 红 |
| **T-48** | `ui_click` 触发路由跳转后立刻再 `click` 旧 ref | CX/RG | 第二次拒绝（page 已变） | `ai-dom-verbs.spec.ts` | 仍能点 ⇒ 红 |
| **T-49** | `ui_fill` 打 `el-input-number`（未标 allow） | EL | 拒绝；标了 allow 的复合控件不纳入批② 范围 | `ai-dom-fill.spec.ts` | 写入后值被组件格式化 ⇒ 回读不一致 ⇒ 已有 T-19 兜底 |
| **T-50** | 快照里 `value` 回显 | SZ/EL | 只对 `fill=allow`、≤24 字符；`password/file/cc-*` 永无 value | `ai-snapshot.spec.ts` | 回显密码 ⇒ 红 |
| **T-51** | 上传文件名含路径分隔/中文/超长 | UP | 只回文件名、≤64 字符、无盘符 | `ai-upload-handoff.spec.ts` | 回原样 ⇒ 红 |
| **T-52** | 两个写动词连发 80ms 节流 | QT | 第二个被排队而非并行；停止后队内清零 | `ai-dom-verbs.spec.ts` | 并行 ⇒ 红 |
| **T-53** | `ui_snapshot` 的 `kinds` 全不匹配 | SZ | 头行 + 「共 0 个元素（本次回 0 个）」 | `ai-snapshot.spec.ts` | 空字符串/无尾行 ⇒ 红 |

**回归面（全绿才算收口）**：前端 `frontend-tool-outcome.spec.ts`、`frontend-tool-result-terminal.spec.ts`、`restore-request-emitted.spec.ts`、`restore-reconcile.spec.ts`、`parts-reducer/parts-double-write/parts-residue-cleanup`、`ai-panel-parts-render.spec.ts`、`tools-and-deadline.spec.ts`、`b1-suspend-disconnect.spec.ts`、`r166-gate-link.spec.ts`；后端 `ToolRegistryDisclosureTest`、`ConfirmGate*`、`ResilientChatService*`、`FrontendToolGuard*`/`GenerativeFormGuard*`。

**测试分工的一条硬事实**：jsdom 无布局 ⇒ `scrollIntoView`/`getBoundingClientRect` 恒 0；高亮的**视觉**只能交 E2E，单测只断「类名/属性/调用次数/文本」。

## 7. 与表格专项的共享文件清单与避让

### 7.1 表格专项在飞现状（`wt-table-ai`，未提交）

```
 M frontend/src/components/AiPanel/AiPanel.vue      (+24)
 M frontend/src/components/SpreadGrid.vue          (+74)
 M frontend/src/stores/ai.ts                       (+240)
 M frontend/src/types/ai.ts                        (+44)
 M frontend/src/types/tools.ts                     (+153)
 M frontend/src/utils/frontend-tools.ts            (+135)
 M frontend/src/views/ExportWizardView.vue         (+37)
 M frontend/src/views/ImportWizardView.vue         (+35)
?? frontend/src/components/AiPanel/SheetPreviewCard.vue
?? frontend/src/utils/sheet-tools.ts
?? frontend/tests/sheet-{condition-parity,fixtures,tools-read,tools-registry,tools-write}
```

**已定型的接口形态（本专项直接照抄，不要另立一套）**：

| 表格已落形态 | 位置 | 本专项对应 |
|---|---|---|
| `SHEET_TOOL_NAMES` / `SHEET_WRITE_TOOLS` / `SHEET_PREVIEW_TOOLS` 常量 | `types/tools.ts`（wt） | `UI_TOOL_NAMES` / `UI_HANDOFF_TOOLS` 同位置同体例 |
| `FrontendToolName = '...' \| '...' \| SheetToolName` | `:107`（wt） | 追加 `\| UiToolName`（**同一行**） |
| `FRONTEND_TOOLS` 尾部追加 8 条 | `:135+`（wt） | 追加 7 条（**同一数组尾部**） |
| `SHEET_SCOPE_PATTERNS` | `:109`（wt） | `['*']`（本专项不同，理由见设计 §4.4） |
| `v-else-if="isSheetPreviewPart(part)"` | `AiPanel.vue:529`（wt） | 第三条支插在其后（**同一 v-if 链**） |
| `activePreview` / `stalePreviewToolCallId` 状态位 | `stores/ai.ts:777+`（wt） | `activeUploadHandoff` 紧邻追加 |
| `frontend_tool_request` 分支特判（`needsSheetPreview`） | `stores/ai.ts`（wt） | `UI_HANDOFF_TOOLS.has(frame.name)` 特判（**同一 break 段**） |
| `dispatchFrontendTool` 头部 `delete_rows` 早返回 | `frontend-tools.ts:509+`（wt） | 原语走 `defaultExecutor`（**不在此处加分支**） |
| `sheet.current` page handler | `ImportWizardView.vue:1050+`（wt） | `import.receiveFile` + 区域注册（**同文件**） |

### 7.2 共享文件清单（谁先落谁定，后者 rebase）

| 文件 | 表格改什么 | 本专项改什么 | 冲突强度 | 避让建议 |
|---|---|---|---|---|
| `frontend/src/types/tools.ts` | `ToolArgSpec` +`object/object[]`；`FrontendToolName`；`FRONTEND_TOOLS` +8；`SHEET_*` 常量 | `FrontendToolName` +7；`FRONTEND_TOOLS` +7；`UI_*` 常量 | **高**（同一行/同一数组） | 后落者只做**尾部追加**；不改既有行序；`ToolArgSpec` 本专项**不动** |
| `frontend/src/utils/frontend-tools.ts` | `dispatchFrontendTool` 头部早返回；`validateArgs` 增 `object`/`object[]`；`sheet` 执行器注册；`resolveSheetContext` | 文件头注释订正；`defaultExecutor` +7 分支 | **高** | 后落者只改 `defaultExecutor` 与文件头；不碰 `validateArgs` |
| `frontend/src/stores/ai.ts` | `activePreview`/`stalePreviewToolCallId`；`confirmWritePreview/cancelWritePreview/abandonStalePreview`；`frontend_tool_request` 特判；恢复路径 stale 收口 | `activeUploadHandoff`；`openUploadHandoff/cancelUploadHandoff/resolveUploadHandoff`；`undoLastUiAction`；特判；`:2819` 注释订正 | **高** | 特判段按「GF → 表格 → 上传 → runFrontendTool」固定顺序插入；后落者**整段追加**而非改写 |
| `frontend/src/types/ai.ts` | `SheetPreviewLevel`/`SheetWritePlan` | `ActiveUploadHandoff`（+ 待裁的 `uiSummary`） | 中 | 同文件不同段，追加即可 |
| `frontend/src/components/AiPanel/AiPanel.vue` | `SheetPreviewCard` 支 + 5 个 helper + import | `UploadHandoffCard` 支 + 中文名表 +7 + `ai-operating-bar` | **高**（同一 `v-if` 链 + 同一 import 块 + 同一名表） | 后落者插在 `isSheetPreviewPart` 支**之后**；名表按字母/工具族追加 |
| `frontend/src/views/ImportWizardView.vue` | `sheet.current` handler；`HANDLER_NAMES` +1；`gridRefs` 适配器 | 上传按钮 `data-ai-*`；区域注册；`import.receiveFile`；`uploadOne` 抽取；`notifyAiUploadDone` | **高**（同文件、同 `HANDLER_NAMES`、同 `registerHandlers`） | 三个插入点都在函数尾部 ⇒ 可顺序追加；`uploadOne` 抽取要先于 `handleUpload` 改 |
| `frontend/src/views/ExportWizardView.vue` | `sheet.current` | 批③ 后试点（不在首批） | 低（时间错开） | 本专项批① **不碰**该文件 |
| `backend/.../ai/tools/AiTools.java` | +8 `@Tool` | +7 `@Tool` | 中（同一类尾部） | 追加到类尾；工具计数断言由后落者改 |
| `backend/.../ai/gate/ConfirmGate.java` | 名单集合（5 名） | 同一集合 +`ui_request_upload` | **高**（同一行） | **谁先落谁建集合**；后落者只加名字，不改语义/注释 |
| `backend/.../ai/tools/ToolRegistryDisclosureTest.java` | 名单 2→10 | 名单 →14、全量名单 | 中 | 后落者按实际名册重写这两处 |
| `frontend/tests/*`（回归面 9 个 spec） | 扩 `ai-panel-parts-render` / `restore-reconcile` | 扩同名 + 新增 9 个 spec | 低（不同新文件）+ 中（两处扩同一 spec） | 后落者只加 `describe` 块，不改既有断言 |
| `scripts/verify-e2e.ps1` | 主计数 +N | 29→32（若表格先落则 29+N→29+N+3） | 中 | 后落者按实际总数改，汇总文案同批 |

### 7.3 边界结论（回答「表格是否复用 `data-ai-*`」）

**不复用，也不应该复用。** 证据：

1. 表格专项设计稿全文零命中 `data-ai`（`grep -c "data-ai"` = 0），其操作对象是 `SheetAdapter`（`getValue/setValue/getArray`），走 `sheet.current` page handler；
2. `SpreadGrid.vue` 用 `new GC.Spread.Sheets.Workbook(host)` 自绘，单元格**没有 DOM 节点** ⇒ 本专项的快照/`ref`/`click` 对它**完全无效**（这也是两稿共同写明的「不是子集」）；
3. 两者唯一的共享面是**前端工具通道 + 人围栏名单 + 卡面容器 + 工具名表**，不是注册机制。

因此：**表格专项不需要在 `SpreadGrid.vue` 里加 `data-ai-*`，本专项也不需要读懂 `sheet-tools.ts`**。这条边界建议写进两份施工报告的「不做面」，防后手顺手扩面。

## 8. 帧契约 16 冻结核对

### 8.1 现状

`frontend/src/types/sse.ts:113-145` 的 `SseFrameTypes` 共 **16** 个：`start`、**`context`（新增，上下文专项批①）**、`message_start`、`delta`、`message_end`、`tool_start`、`tool_result`、`confirm_request`、`confirm_decision`、`frontend_tool_request`、`frontend_tool_result`、`suspended`、`heartbeat`、`retry`、`done`、`error`。设计稿仍写「15 种不变」——**是旧口径，施工文档须改字为 16**。

### 8.2 逐条核对（有无契约变更诉求）

| 契约面 | 本专项用法 | 是否需改 | 结论 |
|---|---|---|---|
| 帧类型集 | 不加任何帧 | ❌ 否 | **零变更**（15→16 已由上下文批完成，本专项不叠加） |
| `frontend_tool_request.name` | 7 个新工具名 | ❌ 否 | `name: string`（`sse.ts:291`），无枚举约束 |
| `frontend_tool_request.args` | 7 组 JSON 字符串 | ❌ 否 | `args?: string`（`:292`） |
| `timeoutSeconds`/`expiresAt` | 上传交接不带 | ❌ 否 | 既有「缺省 = 无上限」语义（`:296-302`） |
| `frontend_tool_result` | 三态回灌走 `result` | ❌ 否 | `ok/executed/status/result/outcome/code`（`:308-330`）齐备 |
| 用户放弃 | `cancelled=true` ⇒ `FRONTEND_CANCELLED` | ❌ 否 | 既有 `ConfirmGate:91-93` 口径 |
| 快照内容 | 只进 `result` 文本 | ❌ 否 | 台账全文留档（R4 双通道同源），**不需要**单独一帧 |
| `ui_event` 15 类 | 不加 | ❌ 否 | `UI_EVENT_TYPES`（`types/tools.ts:177-193`）不变 |
| `ToolPart` 类型 | 可能加 `uiSummary` | ⚠️ **待裁** | 这是**前端内部类型**，不是帧字段；建议不加（从 `result` 派生） |
| `PageHandlerName` | +`import.receiveFile` | ⚠️ 非帧 | 前端联合类型，`types/workspace.ts:14-25` |
| `@ToolScope` | `{"*"}` | ⚠️ 非帧 | 后端注解参数，披露面 |
| 工具计数 | 18→25 | ⚠️ 非帧 | `ToolRegistryDisclosureTest` 断言 |

**结论：本专项对 SSE 帧契约的变更诉求 = 0 条**；另有 3 条「待裁」属前端内部类型 / 注解参数 / 名单断言，均不触碰冻结契约。

## 9. 待裁项汇总（设计稿 6 条 + 勘察新发现 5 条）

| # | 待裁 | 现状证据 | 影响面 | 建议 |
|---|---|---|---|---|
| 裁 1 | 快照上界 20/40 | 设计 §7-裁 1 | 常量 + T-02/T-03/T-43 | A：默认 20 / 硬顶 40 |
| 裁 2 | 下载算不算 `read` | `download_export_files` 已是注册工具（`@ToolRisk(READ)`） | `import.upload.templates` 标注 + 审查清单 | A：标 `consequential`（数据出域面） |
| 裁 3 | 元素级风险可否低于区域 | 设计 §7-裁 3 | `resolveAiRef` + T-32 | A：允许但 DEV `console.warn` |
| 裁 4 | 上传回退是否加拖拽 | 设计 §7-裁 4 | `ImportWizardView.vue:40-51` 包裹 + T-35 文案 | A：加，视觉零变化 |
| 裁 5 | 是否加总开关 | `application.yml` 无新键 | `AiProperties` + Guard 一处 | A：不加（留给 i18n 批③ 的 `ai_tool_def.enabled`） |
| 裁 6 | 撤销按钮落点 | 设计 §7-裁 6 | `AiPanel.vue` + T-25/T-26 | A：对话内摘要行右侧 |
| **N1** | **`data-ai-ref` 段字符集** | 设计写 `[a-z0-9-]+`（§3.4.2）与 Guard 正则 `^[a-z0-9-]+(\.[a-z0-9-]+){2,}$`；但同稿示例 `import.selectDefs.CURRENCY`，且仓内 `CURRENCY`（大写）与 `E2E_TEMP`（下划线）是真实 defCode（`scripts/verify-e2e.ps1:44-45,469`） | 不改则**所有按 defCode 派生的 ref 被 Guard 误杀**（含快照里的勾选框、切换 tab） | **必须裁**：段字符集改 `[A-Za-z0-9_-]+`，并把 `ref` 的「大小写敏感 + 不做 NFKC」写进规范；或改用哈希/序号（但违反「业务键」原则，不推荐） |
| **N2** | **lodash 不可用** | `frontend/package.json` 无 `lodash`/`lodash-es`，源码零 import；A-5 纪律写「前端工具优先 lodash」 | 7 工具实现（节流/深比较/取值） | 裁「允许原生实现」（推荐，零依赖）或「本批新增 lodash-es」（需依赖审查 + 打包体积评估） |
| **N3** | **`uiSummary` 是否新增 `ToolPart` 字段** | `types/ai.ts:283-330` 现无该字段 | `types/ai.ts` + `parts.ts` + 表格同文件改动 | 建议**不加**（摘要行从 `part.result` 首行派生），减少共享文件改动 |
| **N4** | **`import.upload` 既有 handler 文案是否改指向交接卡** | `ImportWizardView.vue:1089-1096` 现在只说「请提示用户点击…」 | 两套话术并存会让模型选错路径 | 建议：文案主体不动，仅追加一句「若已在对话内出现『选择文件』按钮，请让用户点它」 |
| **N5** | **批① 试点页与表格在飞冲突** | `wt-table-ai` 已改 `ImportWizardView.vue`（+35）与 `AiPanel.vue` | 批① 一开就撞 | 裁「本专项批① 等表格批① 合流后再开」；若必须并行，批① 只允许改**新文件**（`ai-regions.ts`/`ai-dom-tools.ts`/spec），页面标注推迟到批② |

## 10. 风险与诚实边界（勘察补充）

1. **行号漂移是施工期最大的一次性成本**：设计稿的 `stores/ai.ts` 引用整体偏 55~80 行。建议施工第一笔就建「符号 → 行号」索引（用 `grep -n "case 'frontend_tool_request'"` 之类），而不是照抄行号。
2. **`grep` 的 binary 误判**：`ImportWizardView.vue` 用 bash grep 无 `-a` 时会静默返回零命中。任何「仓内零命中」的结论都要用 Grep 工具或 `grep -a` 复核。
3. **标注质量是信任根**：本设计的全部安全性建立在 `data-ai-risk` 标得对上；兜底只有「内置 deny 清单 + 批⑤ 人工标注审计」两道。若批⑤ 被压缩掉，这套设计的性质就从「结构性安全」退化为「约定性安全」。
4. **注入面放大是既有事实的延伸**：`SYSTEM_PROMPT`（`ResilientChatService.java:109-138`）今天仍无「数据非指令」定界；在安全防护批② 落之前，本专项只应在**单页试点**开写动词（与设计 §6 的处置一致）。
5. **`ui_fill` 的复合控件**：Element Plus 的 `el-input-number`/`el-date-picker` 写入必然被组件格式化 ⇒ 回读不一致会频繁触发 `failed`。批② 明确不纳入，可避免「不谎报」变成噪声源。
6. **撤销只 1 步 + 不持久化**：刷新即失效，这是刻意的（DOM 不是事务）。对用户的承诺必须写清楚「最近 1 步」，否则会被当成通用 undo。

## 附录 A · 取证命令与结果（可复跑）

```bash
cd <REPO_ROOT>
git log --oneline -1                          # a5539c2
git status --short                            # 仅 ?? data/
grep -rn "data-ai-" --include=*.ts --include=*.vue --include=*.java .   # 0 命中
grep -ac "@Tool(name = " backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java  # 18
grep -n "case 'frontend_tool_request'" frontend/src/stores/ai.ts        # :2798（稿写 2739）
grep -n "前端工具没有 HITL" frontend/src/stores/ai.ts                    # :2819（稿写 2760）
grep -n "export const SseFrameTypes" -A 33 frontend/src/types/sse.ts    # 16 项（含 context）
sed -n '322,330p' backend/.../ai/gate/ConfirmGate.java                  # 单值 GenerativeFormRules
grep -n "suspendWaitSeconds" backend/.../ai/gate/ConfirmGate.java       # :322 定义 / :416 调用
grep -rln "implements FrontendToolGuard" backend/src/main/java           # 仅 GenerativeFormGuard
ls frontend/tests/*.spec.ts | wc -l                                     # 29（稿写 26）
sed -n '1,6p' scripts/verify-e2e.ps1                                    # 「全流程 29 用例」
cd <LOCAL_WORK>/ai-example-code/wt-table-ai && git status --short             # 8 M + 10 ??
ls frontend/package.json | xargs grep -n "lodash\|tailwind"              # 仅 tailwindcss 3.4.17
```

## 附录 B · 建议施工顺序（在表格专项之后）

| 批 | 内容 | 前置 | 允许并行度 |
|---|---|---|---|
| 批① 感知 | `ai-regions.ts` + `ai-dom-tools.ts`（`ui_snapshot` + 三道围栏 + 尺寸闸）+ 后端 1 桩 + Guard | 表格批① 合流 | 页面标注可延后到批②，以避开 `ImportWizardView.vue` |
| 批② 低风险 | `fill/select/scroll/highlight` + 撤销点 + 摘要行 + 配额/节流 | 批① | 串行 |
| 批③ 点击 | `ui_click` + 风险分流 + 拒执映射 + 操作条 + 停止判据 + 全量 risk 标注 + read 清单复核 | 批② | 串行 |
| 批④ 上传 | `ui_request_upload` + `UploadHandoffCard` + `import.receiveFile` + `uploadOne` + `notifyAiUploadDone` + 后端名单 | 批②③ | 串行 |
| 批⑤ 收口 | 审计 + i18n 7 条 + 工具定义数据行 + E2E +3 + 文档 + 自动补全评估 | 批③④ + i18n 批① | — |
