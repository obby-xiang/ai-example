# GF-D 双 FormRenderer 冒烟验证——同 toolCallId 双挂起卡形态实测

> 角色：DeepSeek V4 Flash 子智能体（CDP 自动化冒烟）。**零产品代码改动、零 git 写操作**；本文档为唯一新增文件，保持未提交（由指挥官统一入库）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `61286e2`。
> 执行日期：2026-10-07。
> 被验对象：工作区既有前端 + 后端（未改动任何源码）；观察项来自 GF-C 收尾（同 toolCallId 被两条 assistant 消息各持一个 `pendingCall`）。
> 证据等级：**全部为实测**（真实前端 dev server + 真实后端进程 + 帧注入驱动真实组件渲染 + CDP 截图）；未见实测的一律标注【未验证】。
> 脱敏：不出现真实盘符路径（仓库统一 `<REPO_ROOT>`、仓库外冒烟目录统一 `<SCRATCH>`）、密钥（统一 `***`）、用户名、主机名、内网 IP；`localhost` 端口号按原值直写。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 判定 | **A–F 六项全 PASS**（首轮 v1 + 加高视口复跑 v2 交叉印证） |
| 双卡实证 | 同一 toolCallId → `提交`/`取消` 按钮各 **2** 个、DOM 表单标题节点 **2** 个、两条消息各持该 toolCallId 的 `pendingCall` |
| 共享态 | `ai.activeForm` **仅一份**（`activeForm.toolCallId` 与注入值相等，`status='filling'`）：倒计时两卡同刻同值、错误文案两卡同现、取消两卡同时收敛 |
| 唯一偏差 | 字段**输入值**为各 FormRenderer 组件本地态、两卡互不同步（详见 §3.C） |
| 一句话 | **双 FormRenderer 为纯视觉重复——两卡共享单一 `activeForm`，倒计时/错误/结局态三处两卡始终一致；唯一例外是字段输入值各自本地，卡②可独立提交自有值。** |

---

## 一、目的与背景

### 1.1 观察项来源

GF-C 端到端执行验证收尾时记录一处待定形：**同一 `toolCallId` 被两条 assistant 消息各持有一个 `pendingCall` 时，AiPanel 会为每条消息各渲染一份 FormRenderer，界面上出现"双挂起卡"。** 前序裁定为**纯视觉重复**（两卡共享同一 `activeForm`，且后端按 `(runId, toolCallId)` 幂等封顶），但该裁定当时**未做界面级实测**。本棒目的即：在无模型参与的条件下注入帧复现该形态，逐项实测双卡行为是否与"纯视觉重复"一致，为 M2 是否修提供证据。

### 1.2 关键源码锚点（本次实测的行为依据）

| 位置 | 事实 |
|---|---|
| AiPanel 挂载点 | `<FormRenderer v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId" :key="liveForm.toolCallId" …>`——**按消息渲染**，故 N 条消息各持同 toolCallId 即有 N 个实例（`key` 相同但分属不同子树，互不复用） |
| AiPanel `liveForm` | `activeForm.status ∈ {filling, submitting}` 才渲染；结局态转 `v-else` 分支显示结局 tag（`表单已取消` / `表单已提交，等待回执` / `表单状态已丢失…`） |
| `openGenerativeForm` | 无论来自哪条消息，**只写单一 `activeForm`**（`toolCallId/runId/form/status/error/deadline`），并只武装一个模块级本地超时计时器 |
| `cancelGenerativeForm` | 把 `activeForm.status` 置 `cancelled` → 处置**全局**，两卡同时失去 `liveForm` 而收起 |
| `submitFrontendToolResult` | 409 分类：`DUPLICATE_TOOL_CALL_ID` → `{duplicate:true}`（不写 error）；`FORM_RESULT_REJECTED` → `{rejected}`；**`UNKNOWN_TOOL_CALL` 落到通用失败分支**（写 error 文案） |
| FormRenderer | 字段值存组件本地 `ref model`，**不读 `activeForm`**——这是 §3.C 隔离现象的结构性原因 |
| 传输形态 | 无 WebSocket：chat 为 `fetch`+SSE 流、reattach 为 `EventSource`；故帧注入只能走 console → Pinia store |

---

## 二、环境与方法

### 2.1 环境与端口（脱敏）

| 项 | 实测值 | 说明 |
|---|---|---|
| 前端 dev server | **`http://localhost:5202`** | 端口由 `frontend/.env.local` 的 `VITE_DEV_PORT=5202` 覆盖（`vite.config.ts` 默认值为 5200，未生效） |
| 代理目标 | **`http://localhost:18318`** | 由 `frontend/.env.local` 的 `VITE_API_BASE` 覆盖（`vite.config.ts` 默认 8080，未生效）；`/api` 经 Vite proxy 转发，前端请求 URL 表现为同源 `:5202/api/...` |
| 后端 | **`http://localhost:18318`**，Tomcat 启动 8.0s | 复用预编译 `backend/target/config-mgr.jar` + `--server.port=18318`（与代理目标对齐）；后端源码零改动，未重建 |
| Redis | `127.0.0.1:6379` **在线** | Memurai 已在监听，冒烟前 `netstat` 确认，未启停该服务 |
| H2 | 演示库 `backend/data/config_mgr_db`（`.gitignore` 已排除） | 后端启动会正常写入该文件，**非跟踪文件** |
| AI 能力 | **降级**（未注入 `DEEPSEEK_API_KEY`） | 面板顶部常驻黄条「AI 能力暂不可用（已降级，业务功能不受影响）」；本轮**不产生任何 chat 轮次、不调模型**，AI 降级不影响本棒可观测行为 |
| 前置端口检查 | `netstat -ano` 确认 5202 / 18318 / 9333 / 9334 均空闲 | 启动任何服务前逐一确认，未占用任务清单外端口 |

### 2.2 自动化方法（仓库内零依赖安装）

1. 仓库内**无** playwright / puppeteer（`npx --no-install playwright --version` 未命中，`node_modules` 下无相应目录），**未安装任何依赖**。
2. 改用 **Node v22.23.2 内置 `WebSocket` + `fetch` 直连 CDP**：以独立 `--user-data-dir` 与 `--remote-debugging-port=9333/9334` 启动系统 Chrome → `GET /json` 取 page target 的 ws 地址 → `Runtime.evaluate` 注入 JS、`Page.captureScreenshot` 截图、`Network.*` 采集回灌请求响应。
3. **脚本与截图全部落在仓库外 `<SCRATCH>`**，不写入仓库：

| 产物 | 说明 |
|---|---|
| `<SCRATCH>/smoke.mjs` | 首轮 v1；视口 1384×1365（双卡未能同框，故补跑 v2） |
| `<SCRATCH>/smoke2.mjs` | 复跑 v2；`Emulation.setDeviceMetricsOverride` 宽 1400 × 高 **2600**，使两卡落在同一帧内 |
| `<SCRATCH>/result.json` / `result-v2.json` | 两轮的断言返回值原文（本文引用数值来源） |
| `<SCRATCH>/S1.png` – `S4.png` | 四张截图（S1/S2 为 v2 加高视口版） |
| `<SCRATCH>/vite-dev.log` / `backend.log` | 两个服务的启动日志 |

### 2.3 注入方式与帧内容

面板展开与建帧均走 console → Pinia（`document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')`）：

```js
const ai = <上述取法>
const FRAME = { type:'frontend_tool_request', runId:'<UUID1>', toolCallId:'<UUID2>',
  name:'generative_form', timeoutSeconds:120, expiresAt:Date.now()+N,
  args: JSON.stringify({ form:{ scenario:'FILTER', title:'导出筛选条件', fields:[
    {key:'keyword',        label:'配置编码关键词', type:'text',         required:true},
    {key:'limit',          label:'最大行数',       type:'number'},
    {key:'includeDisabled',label:'包含停用',       type:'boolean'},
    {key:'asOfDate',       label:'数据日期',       type:'date'},
    {key:'level',          label:'层级',           type:'enum',         options:[{value:'high',label:'高'},{value:'mid',label:'中'}]},
    {key:'tags',           label:'标签',           type:'multi_select', options:[{value:'a',label:'A'},{value:'b',label:'B'}]}
  ]}})}

const m1 = ai.appendAssistantMessage('（冒烟①）'); ai.handleFrame(FRAME, m1.id)
const m2 = ai.appendAssistantMessage('（冒烟②）'); ai.handleFrame(FRAME, m2.id)   // 必须投到两条不同消息
```

- 标签文字区分两卡（（冒烟①）/（冒烟②）），便于截图肉眼定卡。
- `runId`/`toolCallId` 均为随机 UUID：v1 = `ecd9ab03…` / `2be31f6d…`，v2 = `172eb48f…` / `512559d5…`。
- 两轮均**同一 toolCallId 投两条消息**；若投同一条消息只会覆写，永远只有一张卡（与既有结论一致）。

### 2.4 双卡同框的量化确认（v2）

`Emulation.setDeviceMetricsOverride` 视口 1400×2600，`scrollTop=0` 后实测两张表单根元素矩形：

| 卡 | top | bottom | 高 |
|---|---|---|---|
| 卡①（冒烟①） | 655 | 1071 | 417 |
| 卡②（冒烟②） | 1387 | 1804 | 417 |

两矩形均完整落在 2600 高视口内，S1 截图为 `captureBeyondViewport` 全量帧 —— **双卡确在同框**。

---

## 三、逐项结果

> 判定口径：每项给出**断言返回值原文**（引用 `result-v2.json`，v1 作交叉印证）+ 截图肉眼复核结论。

### A. 客观计数 —— **PASS**

| 断言 | v2 实测值 | v1 实测值 | 判定 |
|---|---|---|---|
| `document.querySelectorAll('button')` 中文本 `提交` 的数量 | **2** | 2 | PASS |
| 同法 `取消` 数量 | **2** | 2 | PASS |
| `ai.messages.length` | **2** | 2 | PASS |
| `ai.messages.filter(m => m.pendingCall.toolCallId === <UUID2>).length` | **2** | 2 | PASS |
| `ai.activeForm.toolCallId === <UUID2>`（**仅一份**） | `512559d5…`（相等） | `2be31f6d…`（相等） | PASS |
| `ai.activeForm.status` | `filling` | `filling` | PASS |
| DOM 叶节点文本为「导出筛选条件」的标题数 | 2 | 2 | PASS |

**截图 S1 自查（v2，1400×2600）**：AI 栏内两条消息（冒烟①）（冒烟②）各带一张完整表单，**两卡肉眼同框可见**；每卡六型控件**全部齐全且无兜底 alert**——文本输入框（配置编码关键词）、数字框（最大行数，含 `−`/`+`）、开关（包含停用）、日期选择器（数据日期，占位「选择日期」+ 日历图标）、单选下拉（层级，占位「请选择层级」）、多选下拉（标签，占位「请选择标签（可多选）」），各自带「取消」「提交」按钮。两卡标题行均为「导出筛选条件 筛选条件 剩余填写时间 5:00」。首轮 v1（1384×1365）同结论，仅卡②落到折线以下，故补跑 v2 取同框图。

> 结论：**"同一 toolCallId → 两个 FormRenderer 实例"客观成立**；同时 `activeForm` 全局**仅一份**，与"共享同一 activeForm"的裁定一致。

### B. 倒计时同步 —— **PASS**

| 采样 | 卡① | 卡② |
|---|---|---|
| t0（v2） | `剩余填写时间 5:00` | `剩余填写时间 5:00` |
| t0 + 2s（v2） | `剩余填写时间 4:58` | `剩余填写时间 4:58` |
| t0（v1，交叉印证） | `剩余填写时间 2:00` | `剩余填写时间 2:00` |
| t0 + 2s（v1） | `剩余填写时间 1:58` | `剩余填写时间 1:58` |

两轮均**同刻同值跳变，无相位漂移**；与源码一致——倒计时来自单一 `activeForm.deadline`（v1 采样 `deadline` 为单值，`now = deadline − 119s`），非每卡各起一个计时器。

### C. 填值隔离 —— **PASS（同时是本轮最重要的非预期发现）**

用原生 setter + `input`/`change` 事件给**卡①**的「配置编码关键词」写入 `DOC_TYPE`，再读两卡同名字段：

| 断言 | v2 实测值 | v1 实测值 |
|---|---|---|
| 卡① `keyword` 输入框 `value` | `DOC_TYPE` | `DOC_TYPE` |
| 卡② 同名字段 `value` | `""`（空） | `""`（空） |

**截图 S2 自查**：卡①关键词框内可见 `DOC_TYPE`，卡②同位置为空白占位；两卡倒计时仍同步显示 `4:57`（与 B 项相互印证：**结局态共享、输入态不共享**）。

结构性原因：FormRenderer 的 `model` 是组件本地 `ref`（`buildInitialValues(schema)` 初始化），不读取 `activeForm`；两实例各持一份，故互不影响。**含义（供 M2 参考）**：双卡并非完全意义的"视觉重复"——卡②可被独立输入，且卡②的「提交」会把**卡②自己的本地值**回灌给同一 `toolCallId`。后端按 `(runId, toolCallId)` 幂等封顶（先到者胜、后到者 `DUPLICATE_TOOL_CALL_ID`），**无数据写坏风险**；但"哪张卡的值被采纳"取决于用户点了哪张，属可收敛项。

### D. 共享态一致 —— **PASS**

置 `ai.activeForm.error = '冒烟错误X'` 后：

| 断言 | v2 实测值 | v1 实测值 |
|---|---|---|
| `.el-alert__title` 中等于 `冒烟错误X` 的数量 | **2** | **2** |
| 逐卡判定 `card.textContent.includes('冒烟错误X')` | `[true, true]` | `[true, true]` |
| 其余 alert 标题 | `["AI 能力暂不可用（已降级，业务功能不受影响）", "冒烟错误X", "冒烟错误X"]` | 同 |

**截图 S3 自查**：两卡底部同时出现红底 alert「冒烟错误X」，卡内六型控件与两卡倒计时（`4:57`）仍一致；随后置回 `null` 生效。共享态（`error` / `status` / `deadline`）确为**单一来源、两卡同现**。

### E. 取消收敛 —— **PASS**（HTTP 分支为 409 `UNKNOWN_TOOL_CALL` 实测，分支归属见 §四）

由 DOM `click()` 点**卡①**的「取消」按钮：

| 断言 | v2 实测值 | v1 实测值 |
|---|---|---|
| 点击命中 | `true` | `{"clicked":true}` |
| 文本为 `表单已取消` 的 `.el-tag` 数量 | **2** | **2** |
| 剩余 `提交` 按钮数 | **0** | 0 |
| `ai.activeForm.status` | `cancelled` | `cancelled` |
| `ai.activeForm.error` | `取消回灌未送达（未知或已过期的 runId/toolCallId），后端将在超时后自动收尾` | 同 |
| 两条消息的 `pendingCall.status` | `["pending","pending"]` | `["expired","expired"]`（详见下） |
| Network：`POST /api/ai/frontend-tool-result` | 请求发出 → 响应 **409**（`application/json`） | 同（两轮各一次，v1 另含一次直连探针同为 409） |
| 直连后端探针（重复回灌同 toolCallId） | **409**，体 `{"code":"UNKNOWN_TOOL_CALL","message":"未知或已过期的 runId/toolCallId"}` | 同 |

**截图 S4 自查**：两卡**同时**收起——各自只剩悬挂卡头部（工具名「生成式表单」+`自动` tag+args 原文）与右侧 `表单已取消` tag，红色说明文字两卡同款；表单控件（六型 + 取消/提交按钮）在**两卡中一并消失**，页面无残留孤卡。

关键归因（源码级）：点击两卡中**任意一卡**的取消，都只调用 `cancelGenerativeForm()` → 只改**全局唯一** `activeForm.status = 'cancelled'` → `liveForm` 立即变 `null` → **两卡同时**退出 `v-if` 落入结局 tag 分支。故收敛是**全局的一次性动作**，不存在"卡①取消、卡②仍可填"的分裂态。

> 注：卡内红字为构造态产物，非缺陷——本次注入的 `toolCallId` 是客户端伪造值，后端无此挂起，回灌必然返回 `UNKNOWN_TOOL_CALL`；而 `submitFrontendToolResult` **只把 `DUPLICATE_TOOL_CALL_ID` 判为 `duplicate`**（`duplicate` 与 `ok` 均**不写** `error`），`UNKNOWN_TOOL_CALL` 落到通用失败分支才写该文案。真实后端下发的 toolCallId 走 200 / `DUPLICATE_TOOL_CALL_ID`，不会出现该红字（分支覆盖情况见 §四）。

### F. 清理 —— **PASS**

| 断言 | v2 实测值 | v1 实测值 |
|---|---|---|
| `ai.resetSession()` 后 `ai.messages.length` | **0** | 0 |
| `ai.activeForm` | **`null`** | `null` |
| 剩余 `提交` 按钮数 | **0** | 0 |
| 剩余 `表单已取消` tag 数 | 0（v1 采样：0） | 0 |

会话态、镜像与表单态全部归零，无残留挂起卡；随后正常停服（见 §4.5）。

---

## 四、未验证项与边界

1. **真实 `toolCallId` 的"首取消 200 / 重复取消 409 `DUPLICATE_TOOL_CALL_ID`"分支未在本构造态命中**。本棒全部帧为客户端注入，`toolCallId` 后端不认，回灌只能得 409 `UNKNOWN_TOOL_CALL`；`UNKNOWN_TOOL_CALL` 与 `DUPLICATE_TOOL_CALL_ID` 在本构造下**同码不同分支**，无法在纯前端构造态区分。命令行核实：该两分支此前已由 GF-C E2E 以真实 AI 轮次覆盖（GF3 取消终态、GF5 409 双向幂等），故不再单列（见 §五 裁决 4）。
2. **卡②的「提交」回灌分支未构造**（本棒只点了卡①的取消）。§3.C 已暴露"两卡可持不同本地值"的结构性事实，但"卡②提交 → 后端封顶 → 卡内提示"这一路径**未实测**。
3. **六型控件仅验证"渲染完整 + text 型取值隔离"**；`number` / `boolean` / `date` / `enum` / `multi_select` 的**交互级点验**（下拉展开选择、开关切换、日期面板选值、多选 tag 折叠）未逐一点验。
4. **一处构造态副产物（非缺陷，记录备查）**：v1 在取消后约 4s，两条消息的 `pendingCall.status` 由 `pending` 被降级为 **`expired`**，两卡额外出现「会话已失效」tag；根因是后端会话对账发现该 session 的 history 中不存在这两个（伪造的）挂起条目而按既有规则降级——**同属构造态伪 toolCallId 的产物**，与 §五 裁决 3 所述"卡片靠 `activeForm` 置空收敛"为同一机制的两个观察面：取消后 `pendingCall` 本身不被清理，卡片收敛完全依赖 `activeForm` 置空。

### 4.5 收尾与仓库状态

- 已停：前端 dev server（5202）与后端（18318）均由本棒启动、本棒停止；`netstat` 复查两端口**无 LISTENING**。
- 已停：两个冒烟用 Chrome 实例（调试端口 9333/9334，独立 `user-data-dir` 在 `<SCRATCH>`）；按进程命令行过滤确认**无本棒残留**，用户自有浏览器进程未被触碰。
- **仓库改动**：本文档为唯一新增文件且保持未提交，**无任何已跟踪文件被修改**；未执行任何写操作型 git 命令。

---

## 五、裁决结论（指挥官 K3，2026-10-07）

> 以下五条为指挥官裁决**原文照录**，未作任何改动。

1. 冒烟通过：双 FormRenderer 为纯视觉重复，两卡共享单一 activeForm（倒计时/错误文案/取消收敛两卡一致），维持原判定，观察项关闭。
2. 新发现记 M2 候选（低优先）：两卡输入值为组件本地态、互不同步，卡②可独立提交自有值；后端按 (runId,toolCallId) 幂等封顶（先到者胜、后到 409 DUPLICATE_TOOL_CALL_ID），无数据风险；M2 评估收敛为单卡或卡②禁用提交。
3. E 步观察到取消后两条消息的 pendingCall.status 仍为 pending（卡片靠 activeForm 置空收敛）：与 GF-B-③"表单态入镜像/刷新可续填"同一根源，并入该 M2 项，不单列。
4. 未验证项处置：真实 toolCallId 的首取消 200/重复取消 409 DUPLICATE 分支已由 GF-C E2E（GF3 取消终态、GF5 409 双向幂等）以真实 AI 轮次覆盖，不再单列；构造态伪 toolCallId 的 409 UNKNOWN_TOOL_CALL 红字与 v1 出现的「会话已失效」降级 tag 均为构造态预期产物，非缺陷。
5. 六型控件渲染完整（S1 截图双卡同框各自六型齐全），交互级点验留待 M2 收敛单卡时一并处理。

> **记录补充（非裁决，供机械复核）**：裁决 3 所述"取消后 `pendingCall.status` 仍为 `pending`"对应 v2 复跑采样值 `["pending","pending"]`；首轮 v1 在取消后约 4s 采样到 `["expired","expired"]`（§四 第 4 条已归因），两轮为同一机制在不同采样时点的表现，均不改裁决 3 的定性。
