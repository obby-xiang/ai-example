# Excel 能力补齐实施计划

## Context（为什么做这个改动）

当前 ai-example 项目两个前端范式（ai-ui 后端 Agent Loop、ai-ui-vercel 前端 AI Runtime）虽然 `package.json` 都声明了 `@grapecity/spread-excelio ^17.1.10` 依赖，但代码中**从未 import/调用 ExcelIO**，导致四项 Excel 能力全部缺失：

1. **无导入模板下载** — IMPORT 场景直接进编辑态，用户无从下手填数据
2. **无 Excel 文件导入** — 只能手动在表格里逐行录入
3. **导出页只能只读预览** — `StepResult.vue` 用 `el-table` 预览前 100 行，不能在线调整
4. **只能导 JSON 不能导 Excel** — `downloadJson`/`downloadDef` 仅生成 `.json`

用户已确认范围：**两范式都改 + UI 按钮 + AI 工具两者都要**（工具清单 11 → 13，新增 `excel_import`/`excel_export`）。核心诉求：模板要**内嵌 Excel 原生数据验证规则**（下拉/数字范围/必填），用户在 Excel/WPS 打开就能用下拉——是 xlsx 不是 csv。

## 技术前提确认（已验证）

| 项 | 结论 |
|---|---|
| ExcelIO 包 | 两范式已声明依赖，ai-ui-vercel 已安装。API：`new spreadExcel.IO()` → `.open(file, ok, err)` / `.save(json, ok, err)` |
| DataValidation 保留性 | ✅ SpreadJS 的 `createListValidator`/`createNumberValidator`/`createFormulaValidator` 经 `ExcelIO.save` 导出 xlsx 后，保留为 Excel 原生 Data Validation（下拉/数字范围/必填）；反向导入亦保留。技术路径成立 |
| file-saver | 不新增依赖。复用 [StepResult.vue:172-178](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/views/wizard/StepResult.vue#L172-L178) 手写 `saveBlob`（Blob + `<a download>`） |
| SpreadJS LicenseKey | ⚠️ 两范式 `main.js` 均未设 `GC.Spread.Sheets.LicenseKey`，运行在评估模式（水印 + 限制）。ExcelIO 自动继承 Workbook 授权。**正式使用需购买授权** |
| SchemaFormRenderer | 现支持 8 类型（text/textarea/number/boolean/single_select/multi_select/button_group/date），**不含 file**。两范式都有此组件。excel_import 依赖此组件新增 `file` 渲染分支 |

## 一、共享模块：excel-io.js（两范式各一份）

两范式各新建 `src/utils/excel-io.js`，**接口签名完全一致**（仅 import 路径不同，业务逻辑可原样复制）：

```
generateTemplate(configDef, opts?)        // 生成 .xlsx 模板（内嵌 Excel 原生数据验证）并下载
importExcel(file, configDef, opts?)       // 解析 .xlsx → 表头映射 → 类型转换 → 必填/选项校验 → rows
exportExcel(spread, fileName, opts?)      // spread 实例 → 导出 .xlsx
exportExcelByRows(configDef, rows, fileName)  // rows + configDef → 离线 workbook → 导出 .xlsx
exportExcelMultiSheet(defsAndRows, fileName)  // 多 configDef → 多 sheet 合并导出
saveBlob(blob, fileName)                   // 复用自 StepResult.vue saveFile
```

**核心复用点**：从 [SpreadSheet.vue:107-175](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/components/SpreadSheet.vue#L107-L175) 的 `applyColumns` 抽取纯函数 `applyColumnsToSheet(sheet, configDef, mode)`：
- `mode='runtime'`（默认）：维持现状，含 `__id`/`__mark` 隐藏列（列 0/1），数据列从列 2 开始
- `mode='template'`：跳过隐藏列，数据列从列 0 开始（模板里不出现奇怪隐藏列）

**离线 Workbook 构造**（不挂载到可见 DOM）：
```js
function createOfflineWorkbook(configDef, mode = 'template') {
  const host = document.createElement('div')
  host.style.cssText = 'position:fixed;left:-99999px;top:0;width:800px;height:600px'
  document.body.appendChild(host)              // 非零尺寸，否则 SpreadJS 部分 API 异常
  const spread = new GC.Spread.Sheets.Workbook(host, { sheetCount: 1 })
  applyColumnsToSheet(spread.getActiveSheet(), configDef, mode)
  return { spread, sheet: spread.getActiveSheet(), host }
}
```

## 二、四项能力实现要点

### 2.1 模板下载（generateTemplate）

1. `createOfflineWorkbook(configDef, 'template')` 建表 + 设 DataValidation
2. `spread.toJSON()` → `excelIO.save(json, blob => saveBlob(blob, 'CONFIG_X-模板.xlsx'))`
3. 列定义 → Excel 原生验证映射（复用现有逻辑，无新代码）：

| 列 type | SpreadJS DataValidation | Excel 保留形态 |
|---|---|---|
| select + options | `createListValidator(options.join(','))` | Excel 下拉箭头 |
| number | `createNumberValidator(gte, -1e18, 1e18)` | Excel 数字验证 |
| required | `createFormulaValidator('NOT(ISBLANK(...))')` | Excel 自定义验证 |
| date | `setFormatter('yyyy-mm-dd')` | 日期格式 |
| boolean | `CellTypes.CheckBox` | TRUE/FALSE |

4. **select 选项超 255 字符限制**：`options.join(',').length > 200` 时改用隐藏 sheet（`_validation`）写选项 + DV 引用 `=_validation!$A$1:$A$N`（Excel List 跨表引用，业界标准做法）
5. ComboBox CellType 不导出到 xlsx（ExcelIO 只导 DataValidation 部分）——正是想要的效果

### 2.2 Excel 导入（importExcel）

1. `excelIO.open(file, json => ...)` 加载 xlsx → 离线 `spread.fromJSON(json)`
2. 读表头行（行 0），按 label 反查 key，**归一化匹配**（去首尾空格 + 转 half-width + 忽略大小写 + 处理 `* label` 必填前缀 + 兜底匹配 key）
3. 收集数据行（行 1 起）→ `{ id: null, data: {key: value}, mark: 'added' }`
4. 类型转换：boolean(TRUE/FALSE,1/0)、number、date(序号→YYYY-MM-DD)
5. 校验：缺失必填列→errors；select 值不在 options→errors；多余列→warnings；缺失可选列→填 defaultValue
6. 返回 `{ ok, rows, errors, warnings, stats }`，rows 直接可喂 `batchSave`

**导入后灌入**（推荐路径，复用现有数据流）：
`importExcel → 校验通过 → configStore.batchSave(defId, rows, 'append') → window.__spreadsheets[defId].refreshFromServer() → ElMessage`

### 2.3 导出页改造（StepResult.vue）

1. `el-dialog` 内 `el-table` 只读预览 → 换成 `SpreadSheet` 组件，支持在线编辑
2. SpreadSheet.vue 新增 `height` prop（默认 400px）+ watch `props.mode` 重设 `isProtected`（避免 `:key` 强制重挂）
3. 增加只读↔编辑切换按钮组（`previewMode`，切换用 `:key` 强制重挂）
4. 增加"导出此配置 Excel"和"下载全部 Excel（多 sheet）"按钮
5. `downloadDefExcel`：优先用预览 Sheet 当前内容（用户可能编辑过）`previewSheetRef.collectRows()`，否则 `configStore.allData`
6. 多 sheet 导出：sheet 名用 `configDef.name`（截断 31 字符 + 替换非法字符 `: \ / ? * [ ]`，重名追加 `_2`）

### 2.4 Excel 导出（exportExcel/exportExcelByRows/exportExcelMultiSheet）

- 单配置：`exportExcelByRows(configDef, rows, fileName)` → 离线 workbook + setRows + ExcelIO.save
- 多配置：`exportExcelMultiSheet([{def, rows}], fileName)` → 循环 addSheet + applyColumnsToSheet + setRows

## 三、AI 工具新增（11 → 13）

### 3.1 工具入参 schema

**excel_import**（IMPORT/ADD/MODIFY 场景 · VIEW_DEFS 步骤 · needConfirm=true · inputMode=true）：
```yaml
parameters:
  configDefId: { type: integer, description: 要导入到的配置定义 ID }
  mode: { type: string, enum: [append, merge], default: append }
required: [configDefId]
```
> 文件本体不入参。execute 内部复用 `collect_user_input` 的 form 暂停机制，固定 fields=`[{type:'file'}]` 等用户上传。**依赖 SchemaFormRenderer 新增 `file` 类型渲染分支（el-upload）**。

**excel_export**（EXPORT 场景 · RESULT 步骤 · needConfirm=false · 只读导出）：
```yaml
parameters:
  configDefIds: { type: array, items: { type: integer }, description: 要导出的 ID 数组；空则导出当前任务 selectedDefIds 全部 }
  fileName: { type: string }
```

### 3.2 ai-ui-vercel 前端改动

| 文件 | 改动 |
|---|---|
| [src/tools/index.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/tools/index.js) | 新增 `excel_import`/`excel_export` 两个 `tool()`（带 execute）；`TOOLS_BY_STEP` IMPORT/ADD/MODIFY 的 VIEW_DEFS 加 `excel_import`，EXPORT 的 RESULT 加 `excel_export`；`getToolsForStep` all 对象加二者 |
| [src/tools/registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/tools/registry.js) | 新增两 entry + 信任分级（excel_import: needConfirm/inputMode；excel_export: autoExec 类只读） |
| [src/ai/system-prompt.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/ai/system-prompt.js) | 增第 8 条能力描述：用户说"上传 Excel/导入 xlsx"→调 excel_import；"导出 Excel/下载 xlsx"→调 excel_export |
| [src/components/SchemaFormRenderer.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/src/components/SchemaFormRenderer.vue) | 新增 `type==='file'` 渲染分支（el-upload，accept `.xlsx,.xls`） |

### 3.3 ai-ui 后端 + 前端改动

| 文件 | 改动 |
|---|---|
| [ai-service/src/main/resources/tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml) | 追加 excel_import / excel_export 两个 tool 定义（schema 同上） |
| [ai-service/src/main/resources/scenarios.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/scenarios.yaml) | IMPORT/ADD/MODIFY 的 VIEW_DEFS.tools 加 excel_import；EXPORT 的 RESULT.tools 加 excel_export |
| [ai-service/.../tool/FrontendTools.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java) | `kindOf` switch 加两 case → FRONTEND；`defaultNeedConfirm` 加 excel_import=true/excel_export=false；`summarizeImpact` 加两 case 摘要 |
| [ai-ui/src/utils/frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) | 新增两 entry（validate + execute，excel_import 走 inputMode 由 AiPanel 渲染上传卡片，excel_export 直接调 excel-io） |
| [ai-ui/src/components/SchemaFormRenderer.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/SchemaFormRenderer.vue) | 同 vercel 加 file 类型分支 |

## 四、两范式数据落点差异

| 维度 | ai-ui-vercel | ai-ui |
|---|---|---|
| 数据源 | configStore 内存 + localStorage | H2 数据库 |
| excel_import 落点 | `configStore.batchSave` → 改内存 rows | `configStore.batchSave` → 转发 `POST /api/configs/data/batch-save` → ConfigDataService 落库 |
| excel_export 取数 | `configStore.allData` 内存切片 | `configStore.allData` → `GET /api/configs/data/all` |
| 后端接口 | 无需新增 | **无需新增**（batchSave 已存在），Excel 解析全在浏览器 |
| 刷新表格 | `window.__spreadsheets[defId].refreshFromServer()` | 同左（SpreadSheet.vue refreshFromServer 走 configApi.all） |

两范式 `configStore.batchSave` 签名一致，excel-io.js 调用方代码可基本原样复制。

## 五、风险与边界

1. **SpreadJS LicenseKey**：评估模式有水印和功能限制。正式使用需购买授权，在 `main.js` 加 `GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''`，通过 `.env.local` 注入（不入库）
2. **select 选项 255 字符限制**：excel-io.js 内统一判断 `options.join(',').length > 200` → 切辅助 sheet 引用模式
3. **大文件性能**：ExcelIO.open/save 同步阻塞，>5MB 提示用户 + loading 遮罩；导出用 `setTimeout(0)` 让出一帧
4. **表头匹配鲁棒性**：归一化（trim + 半角 + 忽略大小写 + 去 `* ` 前缀 + 兜底 key 匹配）
5. **IMPORT 场景语义重叠**：excel_import 工具=上传 Excel 文件；IMPORT 场景=导入新配置项数据。在 system-prompt 和按钮文案明确区分

## 六、文件清单

### 新建（2 个）
- `ai-ui-vercel/src/utils/excel-io.js`
- `ai-ui/src/utils/excel-io.js`

### 修改 — ai-ui-vercel
- `src/components/SpreadSheet.vue`（抽取 applyColumnsToSheet + height prop + watch mode）
- `src/components/SchemaFormRenderer.vue`（加 file 类型）
- `src/views/wizard/StepViewDefs.vue`（加下载模板/上传 Excel 按钮）
- `src/views/wizard/StepResult.vue`（预览换 SpreadSheet + xlsx 导出）
- `src/tools/index.js`、`src/tools/registry.js`、`src/ai/system-prompt.js`

### 修改 — ai-ui
- `src/components/SpreadSheet.vue`、`src/components/SchemaFormRenderer.vue`、`src/views/wizard/StepViewDefs.vue`、`src/views/wizard/StepResult.vue`、`src/utils/frontend-tool-registry.js`

### 修改 — ai-service
- `src/main/resources/tools.yaml`、`src/main/resources/scenarios.yaml`、`src/main/java/com/example/ai/tool/FrontendTools.java`

## 七、实施顺序

1. **阶段 1**（vercel）：新建 excel-io.js + 抽取 applyColumnsToSheet + SpreadSheet.vue 改造 + StepViewDefs 加下载模板按钮 → 验证模板内嵌 Excel 验证
2. **阶段 2**（vercel）：excel-io.js 加 importExcel + StepViewDefs 加上传 Excel 按钮 → 验证导入灌入
3. **阶段 3**（vercel）：StepResult 换 SpreadSheet + xlsx 导出 + exportExcel 系列 → 验证导出页在线编辑
4. **阶段 4**（vercel）：AI 工具接入（tools/index.js + registry + system-prompt + SchemaFormRenderer file 类型）→ 验证 AI 触发导入导出
5. **阶段 5**（ai-ui）：复制 excel-io.js + 同步 SpreadSheet/StepViewDefs/StepResult/SchemaFormRenderer/frontend-tool-registry 改动
6. **阶段 6**（后端）：tools.yaml + scenarios.yaml + FrontendTools.java
7. **阶段 7**（收尾）：select 选项辅助 sheet 模式 + 大文件 loading + LicenseKey 配置

## 八、验证方法（端到端）

1. **模板验证**：下载 CONFIG_A 模板 → 用 Excel/WPS 打开 → 确认"价格等级"列有下拉（高/中/低）、"销售员姓名"必填列有输入限制 → 填几行数据
2. **导入验证**：上传刚填的模板 → 表格自动灌入数据 + 计数刷新；故意把"价格等级"填成"超高" → 触发校验报错
3. **导出页验证**：导出场景到 RESULT → 预览改成 SpreadJS 表格 → 切"编辑"模式改一行 → 点"导出此配置 Excel" → 下载 xlsx → 打开确认数据/格式/下拉都在
4. **AI 工具验证**：右栏 AI 说"帮我导出 Excel" → 触发 excel_export 工具 → 下载 xlsx；说"我想上传 Excel 导入到 CONFIG_A" → 触发 excel_import → 弹上传卡片 → 上传后落库刷新
5. **两范式对称验证**：ai-ui（后端 Agent Loop）重复上述 1-4，确认行为一致；ai-ui 的导入数据通过 batchSave 落 H2，刷新页面仍在
6. **边界验证**：select 选项超 200 字符的配置（可临时构造）→ 模板用辅助 sheet 引用 → Excel 打开下拉正常
