# M2-T2a 红队返修 —— DS-V4-Flash（施工子智能体）

> 角色：修复执行者。**只执行不重新裁决** —— 修复项与修复方向由上游裁决给定（红队报告 + 主智能体修复令），本文档不重开争论，只登记"改了什么、跑出什么"。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD=`1e2edbb`；零 git 写操作，全部产出**未提交**（工作区仅本批 4 个改动文件 + 未跟踪证据文档）。
> 依据：`docs/evidence/M2-T2a-施工红队-DS-V4-Pro.md`（§1.1 S1、§1.2 S2/S3/S5/S6/S7）；行号以该报告为准。
> 上游修复令范围：**S1 / S2 / S3 / S5 / S6 / S7 共 6 项**（S4 不在本轮清单内，见 §4）。
> 证据等级：**【实测】** = 本次真实执行输出；**【推断】** = 静态推演、未实跑。
> 脱敏：路径用 `<REPO_ROOT>`/`<SCRATCH>`，密钥统一 `***`，端口以本轮实际复现端口 18330/5202/9333 记录。
> 修复日期：2026-10-08。

---

## 0. 结论速览

| 项 | 严重度（红队） | 落点（修复后行号） | 形态 |
|---|---|---|---|
| S1 | 严重 | `frontend/src/stores/ai.ts:176-184` + `frontend/src/types/sse.ts:30,33-34` | 补 2 支映射（`CANCELLED`→cancelled、`REJECTED_ARGUMENTS`→rejected）+ 联合类型补 1 个字面量；409 收敛路径经同一函数自动继承，确认无需另改 |
| S2 | 建议 | `frontend/src/stores/ai.ts:1051-1052`（帧）、`:670-674`（409）、`:1158-1186`（新方法） | 新增 `applyFrontendToolTerminal`：终态**保留**只限 generative_form；通用 FRONTEND 工具回到基线 `pendingCall = null` |
| S3 | 建议 | `frontend/src/stores/ai.ts:694-704`（抽函数）、`:1010-1016`（前置调用）、`:716-720`（旧守卫移除） | 守卫判定抽成 `hasDecidedPendingResidue`，前移到 `message.pendingCall` 写入**之前**（含 `suspended`） |
| S5 | 建议 | `frontend/src/components/AiPanel/AiPanel.vue:416-429` | 宿主候选限定 `pendingCall.status ∈ {pending, running, approved}` |
| S6 | 建议 | `frontend/src/components/AiPanel/AiPanel.vue:238-242` | 通用 FRONTEND 分支补 `succeeded`→"已执行"、`blocked`→"未执行（超出范围）" |
| S7 | 建议 | `scripts/m2t2a-smoke.mjs:27-33`（常量）、`:141-142,146,176`（引用） | 按钮/tag 文案抽成文件顶部常量 + 脆弱耦合注释（data-testid 改造留给 T2b） |

**复验结论：typecheck EXIT=0、build EXIT=0、CDP 冒烟三断言全 PASS（含 ③b）EXIT=0、scan-lib.sh 4 个改动文件零命中【实测】。**
另追加 9 项定点验证（S1/S2/S3/S5/S6 的真实帧注入，见 §2.4），全部 PASS EXIT=0【实测】。

---

## 1. 逐项修复（文件:行号 + 改动摘要）

### S1｜`frameStatusToPendingStatus` 补 `CANCELLED` / `REJECTED_ARGUMENTS` 两支【实测】

**改动 1**：`frontend/src/stores/ai.ts:165-194` —— 五支 switch 扩为七支：

- `:176-180` 新增 `case 'CANCELLED': return 'cancelled'`，注释登记实发路径
  （`ConfirmGate.cancel` → 置 `PendingToolCall.CANCELLED` → `SseChatEmitter#frontendToolResult` 取 `getStatus()` 入帧）；
  口径与 form-cancel 的 `FRONTEND_CANCELLED` 对齐，两条取消路径不再分叉。
- `:181-184` 新增 `case 'REJECTED_ARGUMENTS': return 'rejected'`（`SpToolCallingManager#rejectFrontendArguments` 实发值，未挂起/未执行，语义同 `REJECTED`）。
- `:166-169` 函数头注释补一句"映射必须穷举后端经 `frontend_tool_result` / 409 响应体实发的 status"——漏支会被下游兜底吞成"卡片消失"。

**改动 2**：`frontend/src/types/sse.ts:30,33-34` —— `PendingStatus` 联合补 `| 'REJECTED_ARGUMENTS'`，并给既有 `| 'CANCELLED'` 补出处注释（`CANCELLED` 原已在联合内，**未重复添加**）。

**409 收敛路径**：`AiController.duplicateToolCall` 直填 `submission.pending().getStatus()`，与帧同口径，走的是同一个 `frameStatusToPendingStatus`，**自动继承**新映射，无需另改（`ai.ts:670-674` 只把收敛落点换成 S2 的新方法，映射层未动）【实测：读码确认】。

**复验**：定点验证 S1-a / S1-b 用真实帧注入跑通（§2.4），`status:"CANCELLED"` 现收敛为 `cancelled` 且渲染"表单已取消" tag；修复前该帧会走 `else` 把卡片置 null。

### S2｜终态保留投影限定 generative_form【实测】

**新增方法** `frontend/src/stores/ai.ts:1158-1186` `applyFrontendToolTerminal(toolCallId, status | null)`：

- 判定依据只用**该 pendingCall 的既有字段**：`kind === 'FRONTEND' && name === 'generative_form'`（`PendingToolCall` 已有 `kind`/`name` 字段，见 `frontend/src/types/ai.ts:198-219`），不依赖帧字段，帧缺 `name` 也不误判。
- 通用 FRONTEND 工具（`navigate_to` / `download_export_file` 等）：`item.pendingCall = null`，回到基线语义（结局由 toolRun 卡承载）。
- generative_form：仍按广播域守卫只覆盖 `pending`/乐观 `cancelled` 的条目（first-wins 单调），`status === null`（帧无 status 且 `ok=false` 的旧后端口径）沿用基线收卡。

**两处调用点**：

- `frontend_tool_result` handler：`ai.ts:1049-1052` —— 三分支合并为
  `const terminal = frameStatusToPendingStatus(frame.status) ?? (frame.ok ? 'succeeded' : null)` + 单次 `applyFrontendToolTerminal`（原来内联的 else 收卡循环随之删除，语义等价、位置收敛到方法内）。
- 409 收敛：`ai.ts:672-674` —— `applyPendingTerminal` → `applyFrontendToolTerminal`。

`applyPendingTerminal`（`ai.ts:1147-1155`）**保留**，仅剩一处调用：`cancelGenerativeForm`（`ai.ts:801`）—— 该路径本就是 generative_form 单工具域，语义不变。

**复验**：定点验证 S2 —— 通用工具 `navigate_to` 收到 `FRONTEND_RESULT` 后 `pendingCall === null` 且只留 1 张 toolRun 卡；同场景 generative_form 的 `pendingCall.status === 'succeeded'` 保留【实测，§2.4】。

### S3｜终态守卫前移【实测】

- 抽函数：`ai.ts:694-704` `hasDecidedPendingResidue(toolCallId)` —— 终态判定看**全部消息**的 pendingCall 投影（复用既有 `isFrameDecidedPendingStatus`）。
- 前置调用：`ai.ts:1010-1016`（`frontend_tool_request` case 首行）—— `frame.name === 'generative_form' && hasDecidedPendingResidue(...)` 即 `break`，**在 `this.suspended = true` 与 `message.pendingCall = toPendingCall(...)` 之前**。
- 旧守卫移除：`ai.ts:716-720` 原 `openGenerativeForm` 内的 residue 判定 + `return` 删除，改为注释指向调用方守卫（避免同一判定两处判、且原位置在写入之后本已失效）。

**效果**：已决 toolCallId 不再产生任何新 pending 投影（不写 `pendingCall`、不置 `suspended`、不武装 `activeForm`），消灭"回放先渲染一张瞬时挂起卡"的窗口。

**副作用（如实登记，见 §4 待裁决）**：旧实现在守卫前先执行了 `disarmFormExpiry()`，守卫命中即提前 return，会把**别的**在挂表单的本地超时计时器一并中和；新实现命中守卫时整个帧被跳过，不再触碰计时器 —— 判为修正而非回归。

**复验**：定点验证 S3（残留 `cancelled` + 同 toolCallId 重放请求 → 新消息 `pendingCall === null`、`activeForm === null`、`suspended === false`、0 个"提交"按钮）+ S3 对照（无残留时正常挂起：`pendingCall === 'pending'`、`activeForm === 'filling'`、1 个"提交"按钮）【实测】。

### S5｜`formHostMessageId` 宿主候选限定在途 status【实测】

`frontend/src/components/AiPanel/AiPanel.vue:416-429`：宿主循环由"最后一条持该 toolCallId 的消息"改为"最后一条持该 toolCallId 且 `status ∈ {pending, running, approved}` 的消息"；`:411-414` 注释同步（终态残留消息不作宿主）。取代理读法（`const pending = ai.messages[i]?.pendingCall`）保持原有窄化写法，未引入类型断言。

**复验**：定点验证 S5 —— 消息 A（`pending`，早）与消息 B（`cancelled`，晚）同 toolCallId，`activeForm` 在填 → "提交"按钮所在消息块判别为 **A**（修复前会取到 B）【实测】。

### S6｜通用 FRONTEND 分支补显式分支【实测】

`frontend/src/components/AiPanel/AiPanel.vue:238-241`：新增
`succeeded`→`<el-tag type="success">已执行</el-tag>`、
`blocked`→`<el-tag type="warning">未执行（超出范围）</el-tag>`，
`:238-239` 注释登记口径与 generative_form 分支（`AiPanel.vue:221` 的 `rejected || blocked`）对齐。`:242` 的兜底 `else` 保留，但类型联合（`types/ai.ts:218`）的 7 个成员现已全部显式覆盖，`else` 只对越契约运行值生效。

**复验**：定点验证 S6 —— `blocked` 渲染"未执行（超出范围）"且**不出现**"已执行"；切到 `succeeded` 后渲染"已执行"【实测】。

### S7｜冒烟脚本文案常量化 + 耦合注释【实测】

`scripts/m2t2a-smoke.mjs:27-33` 新增集中常量与注释：

```
const TEXT_SUBMIT = '提交'
const TEXT_CANCEL = '取消'
const TEXT_FORM_CANCELLED = '表单已取消'
```

引用点：`:141`（formRendererCount）、`:142`（cancelButtons）、`:146`（cancelledTags）、`:176`（阶段 2 点"取消"）。注释写明来源（`FormRenderer.vue:97-99` 的取消/提交按钮、`AiPanel.vue` 的"表单已取消" tag）、误报机理（文案变更未同步则静默计不到），以及 T2b 候选改造（data-testid）。**data-testid 改造本轮不做**（按指令）。

---

## 2. 复验（真实执行）

### 2.1 环境【实测】

| 项 | 值 |
|---|---|
| HEAD | `1e2edbb`（`git status` 除本批 4 个 `M` 与未跟踪证据文档外干净） |
| 端口预检 | 启动前 `netstat`：18330 / 5202 / 9333 均**无 LISTENING**；6379 Memurai 系统既有服务在线（未启停） |
| 后端 | `java -jar backend/target/config-mgr.jar --server.port=18330`；`AI_API_KEY` 取自会话环境（本轮未设置，按指令 `export AI_API_KEY="***"` 仅注入进程环境，**未写任何文件**） |
| 后端启动 | `Tomcat started on port 18330 (http)` / `Started ConfigMgrApplication in 7.414 seconds` |
| 探活 | `GET http://localhost:18330/api/ai/health` → HTTP 200，`{"available":true,"code":"AI_AVAILABLE","model":"deepseek-flash","baseUrl":"https://api.deepseek.com","memoryBackend":"RedisChatMemoryRepository",...,"redis":{"available":true,...}}` |
| 前端 | `cd frontend && VITE_API_BASE=http://localhost:18330 yarn dev` → `VITE v6.4.4 ready in 552 ms`，Local `http://localhost:5202/`（进程环境变量覆盖，`.env.local` 未改动） |
| 冒烟 | `SMOKE_OUT=<SCRATCH>/m2t2a-fix-out/smoke node scripts/m2t2a-smoke.mjs`（产物与 Chrome profile 全在**仓库外**） |

### 2.2 typecheck / build【实测】

```
$ cd frontend && yarn typecheck
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 4.37s.                                                    TYPECHECK_EXIT=0

$ cd frontend && yarn build
✓ 1746 modules transformed.
✓ built in 27.24s.
Done in 32.24s.                                                   BUILD_EXIT=0
```

（两处 `EXIT` 均取自 `set -o pipefail` 后的管道退出码，非 `tail` 的退出码。）

### 2.3 CDP 冒烟三断言明细【实测】

命令：`SMOKE_OUT=<SCRATCH>/m2t2a-fix-out/smoke node scripts/m2t2a-smoke.mjs` → `SMOKE_EXIT=0`，结果文件 `<SCRATCH>/m2t2a-fix-out/smoke/result-20261008T114409Z.json`。

```
[app] ready
[阶段1 双消息] {"formRendererCount":1,"cancelButtons":1,"pendingCards":2,"pendingStatuses":["pending","pending"],"activeFormStatus":"filling","cancelledTags":0}
[断言②] PASS {"formRendererCount":1,"cancelButtons":1,"expect":1}
[阶段2 取消] {"formRendererCount":0,"cancelButtons":0,"pendingCards":2,"pendingStatuses":["cancelled","cancelled"],"activeFormStatus":"cancelled","cancelledTags":2}
[断言③] PASS {"pendingStatuses":["cancelled","cancelled"]}
[断言③b] PASS {"formRendererCount":0,"cancelledTags":2}
[阶段3 刷新前] {"formRendererCount":1,"cancelButtons":1,"pendingCards":1,"pendingStatuses":["pending"],"activeFormStatus":"filling","cancelledTags":2}
[阶段3 刷新后] {"formRendererCount":0,"cancelButtons":0,"pendingCards":1,"pendingStatuses":["expired"],"activeFormStatus":null,"cancelledTags":2}
[断言①] PASS {"pendingCards":1,"formRendererCount":0,"pendingStatuses":["expired"]}
[done] result written to <SCRATCH>/m2t2a-fix-out/smoke/result-20261008T114409Z.json
SMOKE_EXIT=0
```

三断言 + ③b 全 PASS；阶段计数与施工/复现文档口径一致（本批未触碰这三条断言的判定逻辑，只把文案字面量抽成常量）。

### 2.4 追加定点验证（S1/S2/S3/S5/S6 的判别性实跑）【实测】

脚本 `<SCRATCH>/m2t2a-fix-out/verify-s1s2s3s6.mjs`（**仓库外**一次性脚本，CDP 直连独立 Chrome + Pinia 帧注入，无模型参与）→ `VERIFY_EXIT=0`，结果文件同目录 `verify-result.json`。九项判定：

| 判定 | 观测 | 结果 |
|---|---|---|
| S1-a `CANCELLED`→cancelled | before `{pending:'pending',activeForm:'filling'}` → after `'cancelled'`，`activeForm` 清空，tag 含"表单已取消" | PASS |
| S1-b `REJECTED_ARGUMENTS`→rejected | after `'rejected'`，tag 含"表单未提交" | PASS |
| S2 通用工具收卡 | `navigate_to` 收到 `FRONTEND_RESULT` 后 `pendingCall === null`、持该 toolCallId 的挂起卡 0 张、toolRun 卡 1 张；同场景 generative_form 终态 `'succeeded'` 保留 | PASS |
| S3 已决不产生新投影 | 残留 `cancelled` + 同 toolCallId 重放请求 → 新消息 `pendingCall === null`、`activeForm === null`、`suspended === false`、0 个"提交"按钮；残留消息仍 `cancelled` | PASS |
| S3 对照（防误伤正常路径） | 无残留时同一帧仍 `pending` + `filling` + 1 个"提交"按钮 | PASS |
| S6 `blocked` 文案 | tags `["可用","自动","未执行（超出范围）"]`，**无**"已执行" | PASS |
| S6 `succeeded` 文案 | tags `["可用","自动","已执行"]` | PASS |
| S5 宿主判别 | 早消息（`pending`）+ 晚消息（`cancelled`）同 toolCallId、`activeForm` 在填 → "提交"按钮所在的**消息块**判别为 `a(pending)`，表单只渲染 1 份 | PASS |

### 2.5 收尾与敏感信息扫描【实测】

- 停止本轮启动的全部进程：`taskkill /F /T` 后端 java（PID 206964）与前端 vite（PID 141484 + 子 esbuild）；随后 `netstat` 复查 —— **18330 / 5202 / 9333 / 9334 均已释放**；残留在跑的 Chrome 进程经命令行核对均为用户本人既有实例（用户自有 profile 目录 `<USER_PROFILE>`，与本轮 `<SCRATCH>/.../chrome-profile` 无关，未触碰）。
- `scripts/githooks/scan-lib.sh` 引擎（`scan_init` + `scan_engine_blob`，SECRET/PATH/USER 三规则，USER 规则启用）扫 4 个改动文件 + 本文档：

```
frontend/src/stores/ai.ts            : hits=0
frontend/src/types/sse.ts            : hits=0
frontend/src/components/AiPanel/AiPanel.vue : hits=0
scripts/m2t2a-smoke.mjs              : hits=0
docs/evidence/M2-T2a-红队修复-DS-V4-Flash.md : hits=0
```

---

## 3. 与红队报告的偏离 / 未做项

1. **并未"重开裁决"**：6 项修复全部按红队"修复方向"的推荐支落地（S1 采 (a) 映射补齐支；S2 采"限定 generative_form + 通用工具维持基线置 null"支；S3 采"抽独立函数前置调用"支）。
2. **S4 未做**（断言①近永真）—— 不在本轮修复令清单内，见 §4。
3. `applyPendingTerminal` 未删除：`cancelGenerativeForm` 仍在用（该处域本就是 generative_form），删除会扩大改动面。
4. 未新增测试文件/脚本进仓库：仓库前端无测试目录，冒烟脚本改动仅限文案常量化（按指令）。定点验证脚本放仓库外，不进仓库。

---

## 4. 【待裁决】

1. **S4（断言①对修复前后不判别，近永真）本轮未处置** —— 上游修复令未包含该项，红线是"只执行不重新裁决"，故未自行改动断言①。当前断言①仍会以 PASS 呈现（上文 §2.3 已如实标注其形态为"回归守卫"而非缺陷判别）。是否按红队建议改为"双消息同 toolCallId 注入后刷新仍单卡"，请裁决。
2. **S3 的行为变化需确认**：守卫命中时不再执行 `disarmFormExpiry()`（旧实现在守卫前统一 disarm）。本次判为"修正旧实现的误伤"，但它确实改变了"已决 toolCallId 帧到达 ⇒ 在挂表单计时器被中和"这一旧可观测行为。若业务侧依赖该副作用，请回退为"守卫前置 + 仍 disarm"。
3. **S2 的判定字面量固化**：`applyFrontendToolTerminal` 以 `name === 'generative_form'` 识别渲染器通道工具（红队指定"用既有字段判定"）。若后端后续新增第二个走渲染器通道的前端工具，需同步该处（现无此类工具）。
4. **S1 的口径后果**：run-cancel 路径现保留一张"表单已取消"卡（而非卡片消失）。此为红队修复方向 (a) 的预期结果；若业务方原意是"整轮取消后卡片应消失"，则 S1 应改走红队 (b) 支并补一条覆盖该口径的断言 —— 该选择权在上游。
