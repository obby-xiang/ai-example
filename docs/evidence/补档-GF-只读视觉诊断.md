> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# GF「选择导出任务」已提交卡只读诊断报告

日期：2026-10-19
前端：http://localhost:5202/（Vite，实际进程 PID <PID>）
后端：http://localhost:8081/（config-mgr.jar，PID <PID>）
会话：`<SESSION_ID_1>`
工具调用：`call_00_Q3S8wSer1JxUNHrOvzMR7869`（`generative_form`）
证据目录：`<SCRATCH>/_cu-gf-readonly-diagnosis/`

## 0. 结论先行

| 分项 | 结论 | 一句话 |
|---|---|---|
| 数据是否丢失 | **PASS** | 后端 `submittedValues={"taskId":"new"}`；刷新/折叠/展开后 DOM model 仍为 `{"taskId":"new"}`，显示值仍为「都不是，新建一个导出任务」。没有丢。 |
| 只读交互是否真禁用 | **PASS** | 原生 `disabled=true`；聚焦失败、点击/selectOption 超时、`showPicker()` 抛 `InvalidStateError`，值保持 `new`。 |
| 只读视觉是否可辨（字段级） | **FAIL** | 背景纯白、边框 `#e3e6ea` 与可编辑态完全相同；仅文字 `#57606a` + 原生 `opacity:0.7` 变浅，仍保留下拉箭头。像占位符，不像锁定值。 |
| 卡级状态是否可辨 | **PASS（但弱）** | 卡头有「✓ 已提交」小徽标 + 摘要「选择导出任务 都不是，新…」；徽标仅 9.5px，字段视觉权重更大。 |

**根因判断：不是 model 为空，也不是显示静态 label/placeholder。是 model 有值、只读交互也真禁用，但只读字段的视觉样式太接近可编辑态，且灰色文字让真实选中值看起来像占位符。**

## 1. 目标卡定位（只读）

在 `GET /api/ai/history/<SESSION_ID_1>` 中找到：

- 用户消息：`启动导出作业`
- GF 标题：`确认要启动的导出任务`
- 字段：`key=taskId`，`label=选择导出任务`，`type=enum`，`required=true`
- 选项包含 `#295 回执回归任务（步骤：导出，可直接启动）`
- schema 默认值：`295`
- **实际提交**：`{"taskId":"new"}`，选项文案「都不是，新建一个导出任务」
- 后端状态：`FRONTEND_RESULT`，`kind=FRONTEND`，`decidedAt=1792392646670`
- 工具响应：`{"taskId":"new"}`

证据：`backend-gf-evidence.json`、`history-<SESSION_ARTIFACT>.json`

> 注意：GF 的默认值是 `#295`，但用户实际点选并提交的是 `new`。卡面显示 `new` 是忠实回放，不是把 `#295` 弄丢了。

## 2. 展开现场：label / value / model

浏览器（真实 Chromium，1440×1000，DPR 2）恢复同一会话后：

- 卡头标题：`确认要启动的导出任务`
- 卡头摘要：`选择导出任务 都不是，新建一个导出任务`
- 状态徽标：`✓ 已提交`，`data-state="submitted"`
- 字段 label：`选择导出任务 *`
- 字段显示值：`都不是，新建一个导出任务`
- `select.value`：`new`
- `selectedIndex`：`5`
- 实际表单 model（从 Vue `FormRenderer` setupState 读取）：`{"taskId":"new"}`
- `FormRenderer` props：`readonly=true`，`submitting=false`，`remainingSeconds=null`，`initialValues={"taskId":"new"}`
- 卡体控件清单：只有 1 个 `select`，无 submit/cancel 按钮

证据：`dom-snapshots.json` → `expanded`；截图 `02-restored-expanded-full.png`、`03-restored-card.png`、`04-field-zoom.png`

## 3. 只读属性与交互探针

| 检查项 | 结果 |
|---|---|
| `select.disabled` | `true` |
| `select.matches(':disabled')` | `true` |
| `readOnly` 属性 | `null`（原生 select 无此属性） |
| `aria-readonly` | `null` |
| `aria-disabled` | `null` |
| 聚焦 `select.focus()` | 失败；`document.activeElement` 仍是卡头按钮 |
| 键盘 ArrowDown | 值仍为 `new` |
| Playwright `click()` | 2s 超时（disabled 不可点） |
| Playwright `selectOption('353')` | 2.5s 超时（disabled 不可选） |
| `select.showPicker()` | `InvalidStateError: showPicker() cannot be used on immutable controls.` |
| 探针后值 | 仍为 `new`，`disabled=true` |

说明：没有 `aria-readonly` / `aria-disabled`，但原生 `disabled` 已足够让辅助技术识别为不可用；这不是交互漏洞。下拉确实打不开。

证据：`dom-snapshots.json` → `interaction`、`selectOptionResult`、`clickResult`、`afterSelectAttempt`

## 4. 刷新 / 折叠 / 展开对比

| 时点 | 显示值 | model | disabled | 徽标 |
|---|---|---|---|---|
| 首次恢复、展开 | 都不是，新建一个导出任务 | `{"taskId":"new"}` | true | ✓ 已提交 |
| 刷新后、展开 | 都不是，新建一个导出任务 | `{"taskId":"new"}` | true | ✓ 已提交 |
| 折叠后 | 摘要仍为「选择导出任务 都不是，新…」 | `{"taskId":"new"}` | true | ✓ 已提交 |
| 再展开 | 都不是，新建一个导出任务 | `{"taskId":"new"}` | true | ✓ 已提交 |

结论：刷新不丢值；折叠/展开不丢值；model 不回落默认 `#295`，也不变空。

证据：`dom-snapshots.json` → `expanded`、`refreshed`、`collapsedAgain`、`reexpanded`；截图 `05-after-refresh-expanded.png`

## 5. 视觉量测：只读 vs 同值可编辑

### 5.1 计算样式

| 属性 | 只读字段 | 可编辑对照（同值 DOM 克隆） |
|---|---|---|
| background | `rgb(255,255,255)` | `rgb(255,255,255)` |
| border | `1px solid rgb(227,230,234)` | `1px solid rgb(227,230,234)` |
| color | `rgb(87,96,106)` = `#57606a` | `rgb(31,35,40)` = `#1f2328` |
| opacity | `0.7`（原生 disabled select） | `1` |
| cursor | `default` | `default` |
| 下拉箭头 | 保留，灰色 | 保留，深色 |

### 5.2 截图像素采样

| 采样 | 只读字段 `08-readonly-select.png` | 可编辑对照 `09-editable-select.png` |
|---|---|---|
| 背景 | `R255 G255 B255` | `R255 G255 B255` |
| 左边框 | `R235 G237 B240`（#e3e6ea 抗锯齿） | `R227 G230 B234`（#e3e6ea） |
| 文字最暗像素 | `R136 G143 B150`（#888f96） | `R31 G35 B40`（#1f2328） |
| 文字区域平均 | `R150 G156 B162` | `R81 G84 B88` |
| 右侧箭头附近 | `R136 G143 B150` | `R31 G35 B40` |

计算校验：`#57606a` 以 0.7 不透明度叠在白底上 ≈ `#898f97`，与实测最暗文字 `#888f96` 一致。可编辑态是 `#1f2328` 全不透明，与只读态形成明显深浅差。

### 5.3 为什么用户看不出只读

1. **背景没有变灰。** 只读分支用的是 `FI_READONLY = disabled:bg-white disabled:text-[#57606a]`，而不是普通 disabled 的 `FI_DISABLED = disabled:bg-[#f6f7f9] disabled:text-[#8b949e]`。字段底色保持纯白。
2. **边框完全一样。** `FI_IDLE = border-[#e3e6ea] bg-white`，只读和可编辑共用同一套边框/底色。
3. **只弱化了文字和箭头。** 原生 disabled 让文字变 `#57606a` 并叠 0.7 透明度，实际渲染成浅灰 `#898f97`；下拉箭头也一起变浅，但仍然存在。
4. **浅灰文字像占位符。** 真实值「都不是，新建一个导出任务」被染成浅灰后，第一眼很像「未选择 / 请选择」的占位文案；而真正的占位 option 文案是「请选择选择导出任务」，并未显示。
5. **字段级没有锁/只读标识。** 没有锁图标、没有「只读」文字、没有灰色填充；唯一状态提示是卡头右侧 9.5px 的「✓ 已提交」小徽标，视觉权重远低于字段本身。

因此，用户看到的不是「空 model + 静态 label/placeholder」，而是「有值 + 真 disabled + 视觉上不够像 disabled」。

## 6. 证据文件

| 文件 | 内容 |
|---|---|
| `01-restored-collapsed-full.png` | 恢复后默认折叠态（含摘要 + 已提交徽标） |
| `02-restored-expanded-full.png` | 展开后的完整页面 |
| `03-restored-card.png` | 目标卡特写 |
| `04-field-zoom.png` | 字段区域放大 |
| `05-after-refresh-expanded.png` | 刷新后展开态 |
| `06-editable-control.png` | 同值可编辑 DOM 对照（整页） |
| `07-editable-control-zoom.png` | 同值可编辑 DOM 对照（特写） |
| `08-readonly-select.png` | 只读 select 原始像素裁图 |
| `09-editable-select.png` | 可编辑 select 原始像素裁图 |
| `dom-snapshots.json` | model / 属性 / 计算样式 / 交互探针 / 网络方法 |
| `backend-gf-evidence.json` | 后端历史中的 GF schema、提交值、响应 |
| `pixel-measurement.txt` | 截图像素采样输出 |
| `builtin-browser-taskcenter-snapshot.txt` | 内置浏览器只读核对：任务中心存在 #295/#354；内置页签是全新会话 <SESSION_ID_2> |
| `capture.mjs` | 复现脚本（只读） |

## 7. 方法与限制

- 内置浏览器已用于打开 `http://localhost:5202/tasks`（`panel-tab-106` / `panel-tab-112`，标题「配置管理平台」），并只读核对任务中心里确有 `#295 回执回归任务`、`#354 配置导出任务`。但该工具没有 JS/Storage 注入能力；Vite 的 `server.fs.allow` 只允许项目 frontend 目录，拒绝从证据目录提供同源 bootstrap 页（403 Restricted）。因此，为恢复「页签级 sessionStorage 里的既有会话」，改用本机真实 Chromium（Playwright）设置 `sessionStorage['ai-session-id']` 后加载同一前端。渲染、DOM、CSS、原生控件行为均来自 `localhost:5202` 的实际应用。
- 内置浏览器新页签的 AI 会话为 `<SESSION_ID_2>`，与历史会话 `<SESSION_ID_1>` 不同；这验证了会话是页签级的，UI 没有「历史会话列表」入口。证据：`builtin-browser-taskcenter-snapshot.txt`。
- 全程网络方法白名单校验：`nonGet=0`，只有 GET；没有提交表单、没有创建任务、没有修改任何业务数据。
- 10 个可见会话中未发现「待填写（pending）」的 GF 真实样本；可编辑对照是同一字段的 DOM 克隆（移除 disabled、保留同值），仅用于视觉/样式对照，不写回应用、不提交、不产生数据。该对照在报告中明确标注为「非应用数据」。
