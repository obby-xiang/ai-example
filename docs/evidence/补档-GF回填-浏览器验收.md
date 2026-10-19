> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# generative_form 提交后只读回填 · 浏览器验收报告

日期：2026-10-19
目标：`http://localhost:5202/tasks`（前端 5202、后端 8081）
基线：`5274419`（`docs(board): 闭合 composer 项目回落批次`）
被验修改（工作树既有，非本次新增）：`frontend/src/components/AiPanel/FormRenderer.vue`、`frontend/src/stores/ai.ts`、`frontend/src/utils/parts.ts` 及相关测试。

## 0. 结论摘要

| # | 验收项 | 裁决 | 关键证据 |
|---|---|---|---|
| 1 | 内置浏览器生成 GF、待填写渲染 | **PASS** | `builtin-observations.json`（会话 `<SESSION_ID_1>`，`15:27:05`） |
| 2 | 内置浏览器 GF 120s 超时收尾、不回填 | **PARTIAL**（终态 PASS；字段级 BLOCKED） | `builtin-observations.json`（`15:29:13`） |
| 3 | 非默认值提交后只读回填（不刷新） | **PASS** | `gf2-submit.json` |
| 4 | 卡头摘要与字段值一致 | **PASS** | `gf2-submit.json`、`gf-history-inspect.json` |
| 5 | 不回落默认值 / 第一项 | **PASS** | `selectValue="都不是，新建…"` |
| 6 | 提交后字段真 `disabled` | **PASS** | `selectDisabled=true`、`inputDisabled=true` |
| 7 | 折叠 / 展开 | **PASS** | `block → none → block`，值保持 |
| 8 | 刷新后持久 | **PASS** | `gf2-submit.json.refresh`、`gf-postcheck-expanded.png` |
| 9 | enum + text 两类字段 | **PASS** | `gf-enum-inspect.json`、`gf2-submit.json` |
| 10 | 取消卡不回填旧值 | **PASS** | `gf-cancel.json`、`gf-postcheck.json` |
| 11 | 只读视觉白底、无新增只读标记 | **PASS**（视觉） | 截图像素 `255,255,255`；`readonlyMarker=false` |
| 12 | 同会话多消息（不同 `toolCallId`） | **PASS** | 两个 GF 摘要同时正确 |
| 13 | 同 `toolCallId` 多消息 / 多段 | **BLOCKED** | 未自然复现，未伪造 |
| 14 | 帧 / POST 时序 | **PASS（POST-first）**；frame-first 未单独观测 | `gf2-submit.json` |
| 15 | 业务副作用记录 | 已记录，未清理 | 见 §5 |

**总体裁决：GF 核心缺陷（提交后回落默认值、摘要错、刷新后才正确）已修复，验收 PASS。** 但内置浏览器的“提交→只读回填”一段因控制被系统收回未能执行（§4），同 `toolCallId` 多段与超时字段级核验为 BLOCKED；这些边界不降低已观测项的结论，但不能宣称全覆盖。

## 1. 自证

`profile.bind`：`agentId=agent-1084`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。

验收期间**未修改任何代码、文档、服务配置，未提交 commit**；只在本目录写入证据文件。工作树中 `FormRenderer.vue` / `ai.ts` / `parts.ts` 等改动为被验对象，开工前已存在。

## 2. 证据来源与边界（重要）

| 来源 | 覆盖内容 | 限制 |
|---|---|---|
| **内置浏览器**（Kimi Code Desktop） | 打开 5202、新建会话 `<SESSION_ID_1>`、发送 GF 指令、待填写卡渲染、120s 超时终态 | 提交阶段控制被收回：`TAB_NOT_ACTIVE` / `BROWSER_UNAVAILABLE(this session is not being viewed)`，无法继续点击/选择；工具无截图落盘路径 |
| **补充 Chromium（Playwright）** | 非默认值选择、提交、POST 时序、只读 DOM 精确值、折叠/展开、刷新、取消 | 真实 Chromium 访问同一 `localhost:5202` 与同一后端；非内置浏览器 |

因此本报告的“内置为主”部分覆盖 GF 生成与超时收尾；**提交后回填的完整交互证据来自补充 Chromium**，未用 Playwright 证据冒充内置证据。

## 3. 不刷新页面的完整复验（第二个 GF）

会话：`<SESSION_ID_2>`（Playwright 页签）
GF：标题 `GF 回填验收 2`
`toolCallId`：`call_00_QYn96qIWrNdfMn6eo0Ug7031`
run：`0865b537-4c12-4efc-b366-485e1da4d6f1`

提交值（显式选择第 6 项，非默认、非首项）：

```json
{"action":"都不是，新建…","note":"GF回填备注：第二次非默认"}
```

提交后**未刷新**读取：

| 观测 | 值 |
|---|---|
| `roSummary` | `验收动作 都不是，新建…，备注 GF回填备注：第二次非默认` |
| `roBadge` | `✓ 已提交` |
| `selectValue` | `都不是，新建…` |
| `selectDisabled` | `true` |
| `inputValue` | `GF回填备注：第二次非默认` |
| `inputDisabled` | `true` |
| `roBodyDisplay` | `block` |
| `readonlyMarker` | `false` |
| `duplicateToggles` / `duplicateBodies` | `1` / `1` |

- 卡头摘要由字段值派生，逐字一致；未回落 `导出全量配置`（默认）或列表第一项。
- 折叠/展开：`block → none → block`，两次展开值保持。
- 刷新后：只读卡仍在（默认折叠），展开后字段值与摘要均保持；同时存在第一个 GF 的正确摘要：

```text
ro-summary-call_00_1pi5QLUqsbNbRbNGAMlK2847 = 验收动作 都不是，新建…，备注 GF回填备注：非默认值
ro-summary-call_00_QYn96qIWrNdfMn6eo0Ug7031 = 验收动作 都不是，新建…，备注 GF回填备注：第二次非默认
```

截图：`shots/gf2-readonly-expanded.png`、`shots/gf-postcheck-expanded.png`（刷新后展开）。

## 4. 帧 / POST 时序

第二个 GF（`gf2-submit.json`）：

| 事件 | wall (ms) | 本地时间 |
|---|---|---|
| click 提交 | 1792394670660 | 15:24:30.660 |
| POST `/api/ai/frontend-tool-result` request | 1792394670699 | 15:24:30.699 |
| response 200 | 1792394670714 | 15:24:30.714 |
| DOM 只读卡 + 正确摘要出现 | 1792394670714 | 15:24:30.714 |

结论：本次观测为 **POST 响应完成即回填**（点击后 54ms 内）。SSE 结局帧先到、POST 未回的路径未单独捕获；该路径由 `parts.ts` 同帧落值与 `FormRenderer` watch 覆盖，并有单测，但**不属于本次浏览器实测**。

第一个 GF（`gf-submit.json`）：POST request `+46ms`、response `+58ms`；首次脚本因只读卡选择器写成 pending/tool 卡，800ms 内未命中，属脚本问题；随后 `gf-history-inspect.json` 确认只读摘要正确。

## 5. 取消与超时

### 5.1 取消（PASS）

GF `GF 取消验收`，`toolCallId=call_00_iVcf1WSb7dwJBKctyHso9854`。
填写 `按范围导出` + 备注 `cancel-only 不应回填` 后点击取消：

| 观测 | 值 |
|---|---|
| `roSummary` | `未提交，填写内容不作记录` |
| `roBadge` | `✗ 已取消` |
| `selectValue` | `导出全量配置`（默认） |
| `inputValue` | `""` |
| 两者 `disabled` | `true` |

未回填 `按范围导出` / `cancel-only`。刷新后复核相同（`gf-postcheck.json`）。

（注：早前 `bodyHasCancelValue=true` 是正则命中了 `<option>` 文本，不是选中值；以 `selectValue` 为准。）

### 5.2 超时（PARTIAL）

内置浏览器会话 `<SESSION_ID_1>`，GF `GF 内置浏览器验收`：

- `15:27:05` 待填写，倒计时 `1:58`，默认 `导出全量配置`。
- 未操作，等待 120s。
- `15:29:13` 卡片收敛为 `未提交，填写内容不作记录` + `✗ 已取消`，折叠态；未出现任何提交值。
- 字段值未能展开核验：此时内置浏览器控制已被收回（§2 错误），且超时前未实际录入值，故“超时前已填写旧值不回填”**无法实测**，标 **BLOCKED**。

观察到一条既有文案问题（非本批修复范围）：超时结局帧到达时，本地仍在 filling，`ai.ts:2836` 统一提示“该表单已在其他窗口提交，本地填写已停止”。本次实际无其他窗口提交，该提示对超时场景有误导性；不影响回填正确性。

## 6. 只读视觉

`gf2-submit.json`：`roBodyBg=rgba(0,0,0,0)`（表单体透明，继承卡片白底），`roBodyClass="pb-[11px]"`，无新增只读标记/水印。
`gf-postcheck-expanded.png` 展开区域像素抽样 6 点，5 点为 `(255,255,255)`（纯白），1 点落在文字抗锯齿上。禁用输入框为浅灰边框、浅灰文字，无黄色/蓝色只读底。

截图：`shots/gf-readonly-expanded.png`、`shots/gf-postcheck-expanded.png`。

## 7. 同 `toolCallId` 多消息 / 多段

- 同会话两条消息、两个不同 `toolCallId`：两张只读卡摘要各自正确，`duplicateToggles=1`、`duplicateBodies=1`，无重复卡。**PASS**。
- 同一 `toolCallId` 出现于多条消息 / 同消息多段：自然流程未复现（每次 GF 只有一个宿主段），按约束标 **BLOCKED**，未伪造。对应 `writeSubmittedValues` 全量遍历逻辑仅有单测覆盖（`r166b-fixes.spec.ts`），未在浏览器中构造。

## 8. 业务副作用（如实记录）

| 环境 | 会话 | 记录 | 结局 |
|---|---|---|---|
| 补充 Chromium | `<SESSION_ID_2>` | GF `GF 回填验收`（`call_00_1pi5...`） | 已提交，run `ca8ccbdf...` DONE |
| 补充 Chromium | 同上 | GF `GF 回填验收 2`（`call_00_QYn9...`） | 已提交，run `0865b537...` |
| 补充 Chromium | 同上 | GF `GF 取消验收`（`call_00_iVcf...`） | 用户取消 |
| 内置浏览器 | `<SESSION_ID_1>`（短 id） | GF `GF 内置浏览器验收` | 120s 超时/未提交 |

- 未创建任何业务任务：任务中心 `#354`（14:50）、`#353`（12:45）在 GF 提交（15:23/15:24）之前已存在。
- 上述 AI 会话与 GF 记录**未删除**，保留作为证据；未观察到可安全清理的入口。内置浏览器页签 `panel-tab-119` 仍打开于 5202。

## 9. 证据文件

JSON：

- `gf-live-inspect.json`、`gf-enum-inspect.json`（enum+text 选项与默认值）
- `gf-submit.json`（第一个 GF 提交、POST 时序）
- `gf-history-inspect.json`（第一个 GF 历史只读摘要）
- `gf2-submit.json`（第二个 GF：不刷新提交、DOM 值、折叠/展开、刷新、时序）
- `gf-cancel.json`（取消卡）
- `gf-postcheck.json`（刷新后第二 GF 与取消卡字段值）
- `builtin-observations.json`（内置浏览器待填写 + 超时终态 + 控制丢失错误）

截图（`shots/`）：`gf2-chosen.png`、`gf2-readonly-expanded.png`、`gf2-after-refresh.png`、`gf-readonly-expanded.png`、`gf-cancel-before.png`、`gf-cancel-readonly.png`、`gf-postcheck-expanded.png`、`gf-history-inspect.png` 等。

脚本：`submit-gf.cjs`、`submit-gf2.cjs`、`cancel-gf.cjs`（仅本目录，未改产品代码）。

## 10. 未覆盖 / BLOCKED 清单

1. 内置浏览器“选择非默认值→提交→只读回填”未实测（控制收回）。
2. 同 `toolCallId` 多消息/多段浏览器级复现未完成。
3. 超时前先填写旧值、再超时的“旧值不回填”未实测（超时前无录入；字段未展开）。
4. frame-first（SSE 结局帧先于 POST 响应）浏览器时序未单独捕获。
5. 内置浏览器无截图落盘，仅有 DOM 文本证据。

以上均未以推测替代实测；其余已观测项结论有效。
