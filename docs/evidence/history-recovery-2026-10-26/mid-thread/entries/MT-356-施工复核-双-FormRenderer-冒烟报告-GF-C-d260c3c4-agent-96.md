<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-356 · 施工复核 · 双 FormRenderer 冒烟报告（GF-C 观察项实测）（d260c3c4 / agent-96）

> **项目归属**：**上下文推定**（正文无路径锚点；依据该 agent 任务提示与项目作业面判定，入库前建议补锚点）。
> **归属依据**：任务提示指向本项目、正文为项目作业面；**无路径/类名锚点**（故标上下文推定）。
> **稿态**：历史中间稿 —— 该 wire 第 `m30` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt10` 条有正文的 assistant 消息（共 13 条有正文），末条有正文消息为 `m51`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `d260c3c4` / agent `agent-96`（`main` = 主会话）；原字符 4,408（链内 1 段、合计 4,408）。
> **链号 / 类别**：`MT-356` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `2%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m51`（2,036 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

All services stopped, ports free, repo untouched. Report below.

---

# 双 FormRenderer 冒烟报告（GF-C 观察项实测）

## 0. 环境与偏差（与任务书的差异，事先未预见）
| 项 | 任务书假设 | 实测 | 说明 |
|---|---|---|---|
| 前端端口 | 5200 | **5202** | `frontend/.env.local` 覆盖 `VITE_DEV_PORT=5202`；5200 无需回退 |
| 代理目标 | vite.config 默认 8080 | **18318** | `frontend/.env.local` 的 `VITE_API_BASE=http://localhost:18318` |
| 后端 | 尽力启动 | **在线** | 用预编译 `backend/target/config-mgr.jar` + `--server.port=18318`（对齐代理目标），启动 8.0s，Tomcat 18318，Redis 6379 已连通 |
| 浏览器自动化 | playwright 优先 | **无 playwright/puppeteer** | 仓库内无该依赖，未安装；改用 Node v22.23.2 内置 `WebSocket`+`fetch` 直连 CDP（脚本在仓库外） |
| AI 能力 | 不需要 key | 后端在线但 AI 降级（未配 key），面板顶部有「AI 能力暂不可用（已降级）」黄条 | 不调 chat，不影响本次冒烟 |

后端在线，故 E 步的 HTTP 分支已实测（非"后端离线"注释）。

## 1. 复现方式
- 帧注入走 console → Pinia store（无 WebSocket，符合已核验事实）：
  `ai.appendAssistantMessage('（冒烟①）')` → `handleFrame(FRAME, m1.id)`；`m2` 同 toolCallId 再投一次（UUID 随机，runId/toolCallId 各一）。
- 脚本：`<LOCAL_WORK>\gfc-dualform-smoke\smoke.mjs`（首轮，视口 1384x1365）、`smoke2.mjs`（复跑，`Emulation.setDeviceMetricsOverride` 1400x2600 让双卡同框）、结果 `result.json` / `result-v2.json`。仓库内未装任何依赖、未改任何文件。

## 2. 逐项结论（以 v2 复跑为准，v1 作交叉印证）

**A. 客观计数 —— PASS**
- `提交` 按钮 = **2**，`取消` 按钮 = **2**；仅 AiPanel 一处挂载点，故 = 2 个 FormRenderer 实例。
- `ai.messages.length = 2`；`messages.filter(m => m.pendingCall?.toolCallId === <UUID2>).length = 2`。
- `ai.activeForm.toolCallId === <UUID2>` 且仅一份（`activeFormStatus: 'filling'`）。
- DOM 侧「导出筛选条件」标题叶节点 = 2。
- 证据 S1.png：视口 1400×2600 内两卡矩形 y=655–1071 与 y=1387–1804，**均在同一帧内**，肉眼可见两张完整表单，各自 6 型控件齐全（text / `el-input-number` / `el-switch` / `el-date-picker` / enum `el-select` / multi_select `el-select`）+ 各自「取消」「提交」按钮，两条消息头分别为（冒烟①）（冒烟②）。**六型控件无缺失、无兜底 alert**。

**B. 倒计时同步 —— PASS**
- v2：t0 两卡均 `剩余填写时间 5:00` → t+2s 两卡均 `剩余填写时间 4:58`（同刻同值跳变）。
- v1 交叉印证：`2:00/2:00` → `1:58/1:58`。
- 与共享 `ai.activeForm.deadline`（单一值）一致，无相位漂移。

**C. 填值隔离 —— PASS（且是本轮最重要的非预期发现）**
- 用原生 setter + `input`/`change` 事件给卡①的「配置编码关键词」填 `DOC_TYPE` 后：`{card1:"DOC_TYPE", card2:""}`。
- 两卡的输入值**互不同步**：FormRenderer 的 `model` 是组件本地 `ref`，两实例各持一份。控制台对 `activeForm` 改值不会传导到已输入内容（S2 截图两卡倒计时仍同步 4:57，但关键词一空一填）。
- 含义：这不是"纯视觉重复"的全貌——**卡②可被独立输入，并且卡②的「提交」会把卡②自己的本地值回灌给同一个 toolCallId**。后端幂等/终态判定会兜住（先到者胜，后到者 409），故不会写坏数据；但"哪张卡的值会被采纳"取决于用户点哪张，属于 M2 可考虑收敛的点。

**D. 共享态一致 —— PASS**
- `ai.activeForm.error = '冒烟错误X'` 后：`.el-alert__title` 中该文案出现 **2 次**（`count: 2`），S3.png 两卡底部同时出现红底「冒烟错误X」alert；随后清除成功。共享态（error / status / deadline）确实单一来源。

**E. 取消收敛 —— PASS（HTTP 分支已实测，但走的是 UNKNOWN 分支，见下）**
- `click` 卡①的「取消」→ `cancelledTagCount: 2`，两卡**同时**收起为「表单已取消」tag，`submitButtons = 0`，`activeFormStatus: 'cancelled'`；S4.png 可见两卡各带一个「表单已取消」tag + 同一句红色说明。
- Network 实捕：`POST http://localhost:5202/api/ai/frontend-tool-result` → **409**（直连后端复测同结果：`{"code":"UNKNOWN_TOOL_CALL","message":"未知或已过期的 runId/toolCallId"}`）。
- 卡内文案为 `取消回灌未送达（未知或已过期的 runId/toolCallId），后端将在超时后自动收尾`。**该红字是构造态伪 toolCallId 的产物，不是双卡缺陷**：`submitFrontendToolResult` 只把 `DUPLICATE_TOOL_CALL_ID` 判为 `duplicate`，`UNKNOWN_TOOL_CALL` 落到通用失败分支才写 error；真实后端下发的 toolCallId 首取消应 200、重复取消应 409 DUPLICATE_TOOL_CALL_ID 且**不写 error**。因此"后端幂等封顶"这一条的**重复回灌分支本次未能真正命中**（两种 409 在本次构造下同码），属未验证项。
- 另一条副作用：v1 在取消后约 4s，两卡 pendingCall 被后端会话对账降级为 `expired`，额外出现两个「会话已失效」tag（`syncFromBackend` 对 history 为空的镜像 pending 降级）。同属构造态伪 toolCallId 的产物，非缺陷。

**F. 清理 —— PASS**：`ai.resetSession()` → `messages: 0`、`activeForm: null`、`submitButtons: 0`、`cancelledTags: 0`。

## 3. 收尾
- 已停：vite dev（node PID 35172，`taskkill /T /F`）、后端 java（18318）；`netstat` 复查 5202/18318 **无 LISTENING**。
- 我启动的两个 Chrome（9333/9334，profile 在 `<LOCAL_WORK>\gfc-dualform-smoke\chrome-profile*`）已随脚本退出，按命令行过滤确认**无残留**；系统里其余 chrome.exe 是用户自己的 profile，未触碰。
- **仓库无改动**：`git status --short` 仅两条冒烟前就存在的未跟踪 docs（`M1-出口评审材料.md`、`M1-出口复核-GLM-5.3.md`）；`backend/data/`（H2 文件，启动后端时正常写入）在 `.gitignore` 第 3 行内，非跟踪文件。
- 产物：`<LOCAL_WORK>\gfc-dualform-smoke\{S1,S2,S3,S4}.png`（S1/S2 为 v2 加高视口版，双卡同框）、`smoke.mjs`、`smoke2.mjs`、`result.json`、`result-v2.json`、`vite-dev.log`、`backend.log`。

## 4. 未验证 / 待跟进
1. 真实后端下发 toolCallId 的取消路径（200 首取消、409 DUPLICATE_TOOL_CALL_ID 重复取消且不写 error 文案）未实测——需真实 chat 轮次（要 AI key）。
2. 卡②的「提交」回灌分支未构造（本次只点了卡①取消）；C 步已暴露"两卡可持不同值"的隐患，建议 M2 一并评估。
3. 六型控件只验证了渲染与 text 型取值；number/boolean/date/enum/multi_select 的交互未逐一点验。

**双 FormRenderer 冒烟：通过 —— 两条消息各持同 toolCallId 的 pendingCall 时确实渲染两张 FormRenderer，共享单一 `activeForm`（倒计时/错误/结局态两卡同步一致，取消即双卡同时收敛），与"纯视觉重复"判定一致；唯一偏差是两卡的输入值各自本地、互不同步，卡②可独立提交自己的值（后端幂等会封顶），建议 M2 据此决定是否收敛为单卡。**
