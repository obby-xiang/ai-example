# AI 可见性与后台执行独立验证

日期：2026-11-09
依据：`docs/superpowers/specs/2026-11-09-ai-visibility-background-execution-design.md` 与对应实施计划 Task 3。

## 当前结论与边界

**C-147～C-152 全部为 `NOT_RUN`。独立验收资产施工只新增两个脚本和本文，未执行任何场景、普通测试或服务启动；生产代码构建由主会话另行完成，见 `AI对话恢复与导出内容验收-2026-11-08.md` 第 4 节，不能代替本专项动态验收。** `node --check` 仅证明脚本语法有效，不是动态验收证据。

| 场景 | 当前状态 | 独立入口 | 未执行原因 |
| --- | --- | --- | --- |
| C-147 后台四类工具 | NOT_RUN | `scripts/verification/ai-visibility-action-browser.mjs` | 本批禁止运行场景 |
| C-148 视窗外滚动与无法操作 | NOT_RUN | 同上 | 同上 |
| C-149 无客户端到期 | NOT_RUN | `scripts/verification/ai-visibility-expiry-api.mjs` | 同上 |
| C-150 实例切换 | NOT_RUN | 同上 | 同上；实跑还需要操作者停启实例 A |
| C-151 重放幂等 | NOT_RUN | `ai-visibility-action-browser.mjs` | 本批禁止运行场景 |
| C-152 后台完整导出恢复 | NOT_RUN | 同上 | 同上 |
| 真实 DeepSeek/其他真实供应商 | NOT_RUN | 无 | 脚本使用现有上游桩；不得把桩证据外推为真实供应商证据 |
| 直接 Redis、直接数据库、普通测试 | NOT_RUN | 无 | 不在本批许可范围 |

脚本只使用 Node 内置模块、现有 CDP、公开 API、现有 `scripts/ci/stub-upstream.mjs` 的请求级工具路由，以及项目已有的 `frontend` JSZip 依赖；零新增依赖。C-149/C-150 在“无客户端”阶段没有 DOM，报告明确记为 `DOM=NOT_APPLICABLE_NO_CLIENT`，不伪造 DOM 结论。

## 执行纪律

1. **每个场景一个独立 Node 进程**，不得用逗号合并场景，不得把多场景包装成一次动态验收。每个进程强制 `SESSIONS=3`，即三个由后端或真实 UI 新发的不同会话；缺任一新会话、run 或断言即为失败，不能用另一轮补齐。
2. 服务、CDP 浏览器、上游桩、Redis 与日志由执行者按隔离环境既有方式预先准备；脚本不安装依赖、不启动构建、不启动服务。C-150 的停启实例是唯一人工服务操作，由脚本控制台明确提示。
3. `EVAL_STUB_ONLY=true` 是硬闸。提示词只经真实输入框和现有上游桩形成真实应用 SSE；脚本不合成、不重写、不注入应用内 SSE，不直接写 Pinia 消息、history、run、工具结局或业务结果。
4. 后台通过真实 CDP target 切换形成：激活遮挡 target 后必须读到 `document.hidden === true` 和 `visibilityState === 'hidden'`；不得改写 `document.hidden`、`hasFocus()` 或可见性事件。无法形成真实后台时为 `NOT_RUN`。
5. C-149/C-150 关闭真实 chat SSE 后，只读公开 run/history/health/business API；不得调用 `/events`、`/resume` 或 `/startup-resume/scan` 强制到期。到期下界只认公开 `pending[].resolvedAtMs`：该权威结算时间加 2000ms 时钟误差必须不早于 `expiresAt`；客户端首次观察时间只作辅助，不能替代结算时间。若字段缺失，该断言为 `NOT_RUN`（`PUBLIC_RESOLVED_AT_MS_REQUIRED`），所需最小生产 additive 字段就是 `pending[].resolvedAtMs`（epoch 毫秒），不得伪造通过或自行改生产。C-150 只有连接失败/超时等真实不可达才算实例 A 停止；HTTP 401/403/500 均表示实例仍可访问，不得算作停止。B 收敛后恢复 A 并交叉对账。
6. 已构造场景但行为不符为 `FAIL`；只有无法形成必要环境条件才是 `NOT_RUN`。清理、日志、CDP 错误、网络加载或 HTTP≥400 失败或证据数组越界都会把总结果拉为 `FAIL`，不会保留表面 `PASS` 或 `NOT_RUN`。
7. 退出码：`0=PASS`、`1=FAIL`、`2=NOT_RUN`。报告位于仓外 `OUT_DIR` 下，浏览器场景为 `summary.json` 与每轮 `round-N/round.json`，API 场景为单个 `summary.json`。

## 环境变量与命令

以下命令在仓库根目录的 Git Bash 中执行。路径仅为示例，必须换成隔离环境的绝对路径；`OUT_DIR` 和日志路径必须在仓库外，且日志文件已存在。非本机地址还需显式设置 `EVAL_CONFIRM_REMOTE=true`。

```bash
cd <仓库根目录>
export APP_URL=http://127.0.0.1:5173
export BASE_URL=http://127.0.0.1:8081
export CDP_URL=http://127.0.0.1:9222
export TENANT_ID=1
export USER_ID=101
export EXECUTOR_IDENTITY=DeepSeek-no-CU
export OUT_DIR=<仓外证据目录绝对路径>
export BACKEND_LOG_FILE=<实例A日志文件绝对路径>
export EVAL_ISOLATED_ENV=true
export EVAL_ALLOW_WRITES=true
export EVAL_STUB_ONLY=true
export SESSIONS=3
```

执行 C-149/C-150 前，必须按隔离环境现有配置为 `com.example.configmgr.ai.gate.ConfirmGate` 开启 DEBUG，并保持 Task 2 的共享周期扫描启用；确认日志能够实际出现工具级 `DEBUG ... park expiry entry converged by this instance`。不得为了验收去修改生产日志实现。脚本只接受两类**同时含 runId/toolCallId** 的工具级证据：一次性 `INFO ... frontend receipt expired after park; entry converged`，或周期 `DEBUG ... park expiry entry converged by this instance`。run-only 的 `park expiry convergence done` 汇总行永远不算工具级证据，不能用聚合计数猜中。

逐条执行，每条都是新进程：

```bash
SCENARIO=C-147 node scripts/verification/ai-visibility-action-browser.mjs
SCENARIO=C-148 node scripts/verification/ai-visibility-action-browser.mjs
SCENARIO=C-149 node scripts/verification/ai-visibility-expiry-api.mjs
```

C-150 需要第二个**真实不同实例**。脚本先输出 `ACTION_REQUIRED: stop backend instance A now`；操作者停止 A 后，它只在 B 上等待三个到期结果。随后输出 `ACTION_REQUIRED: restart backend instance A now`；操作者恢复 A 后，脚本交叉核对并清理。即使前序断言失败，清理阶段也会再次提示恢复 A。

```bash
export BASE_URL_B=http://127.0.0.1:8082
export BACKEND_LOG_FILE_B=<实例B日志文件绝对路径>
SCENARIO=C-150 node scripts/verification/ai-visibility-expiry-api.mjs
```

其余两个浏览器场景：

```bash
SCENARIO=C-151 node scripts/verification/ai-visibility-action-browser.mjs
SCENARIO=C-152 node scripts/verification/ai-visibility-action-browser.mjs
```

可选超时：`TIMEOUT_MS`（浏览器默认 180000ms，API 默认 240000ms）、`POLL_MS`、`INSTANCE_TIMEOUT_MS`（C-150 停启各默认 180000ms）。不得通过加大超时掩盖永久 `PENDING` 或重复副作用。

## 每场景次数与硬断言

| 场景 | 每进程真实次数 | 必须核对的动态事实 |
| --- | --- | --- |
| C-147 | 3 个新 UI 会话；每会话依次执行 `navigate_to → select_definitions → confirm_step → set_condition`，共 **12 个工具 run、12 次真实前端回执** | 每次完成时文档仍真实 hidden；真实 DOM 恰一张成功卡；history 恰一个 `EXECUTED` 状态和一个响应；run=`DONE`；回执恰一次 HTTP 200；工作区路由、选中集、步骤与 CNY 草案真实变化；业务仅预期任务推进且零 job，非目标任务指纹不变 |
| C-148 | 3 个新 UI 会话；每会话 **2 次 `ui_snapshot` + 2 次 `ui_fill`**，共 **12 个工具 run、12 次回执** | 先等待搜索元素挂载，两次真实快照都必须包含 `export.selectDefs.search`，以此证明 AI 区域已注册；epoch 只能取真实快照结果；可滚动样本先真实位于视窗外，成功后值匹配 CNY、元素完整进入视窗且中心命中目标；固定定位不可达样本必须 `failed/rejected/blocked`，值保持 CNY、不得成功或悬挂；几何样式恢复，业务指纹完全不变 |
| C-149 | 3 个后端新会话；每会话一个真实 `navigate_to` 挂起，共 **3 个无客户端 run、3 个到期结局** | 只观察并关闭真实应用 SSE；`SUSPENDED+PENDING+executed=false`；帧与 pending 的 `expiresAt` 一致；公开 `resolvedAtMs+2000ms>=expiresAt`，观察时间仅辅助；无客户端和无恢复调用下转为 `TIMEOUT/outcome=timeout`；run 到终态；history 恰一个 TIMEOUT 与一个响应；后续对账无重复；业务指纹覆盖任务 items、条件哈希、jobs 和文件白名单/哈希且前后不变；实例 A 每会话至少一条同时含其 runId/toolCallId 的一次性工具级 INFO 或周期工具级 DEBUG 到期日志，run-only 汇总不算，run 快照再权威绑定 sessionId |
| C-150 | 3 个新会话和挂起 run；**A 停止 1 次、B 接管 3 次、A 恢复 1 次** | 在 C-149 全部断言基础上，A/B 的公开 `instanceId` 必须不同；A 只有真实不可达才记停止，HTTP 错误状态不算；A 停止后仅读 B，B 每会话至少一条按 runId/toolCallId 关联的到期日志；B 对账后恢复 A，再核对 run/history 不变；跨实例比较排除“读取实例 ID”本身，但不排除 run、pending、ledger 或结局差异；A 最终健康恢复 |
| C-151 | 3 个新 UI 会话；每会话一次真实 `confirm_step`，再做 **1 次同体重放 + 1 次冲突重放 + 1 次刷新恢复**；合计 **3 个工具 run、3 次浏览器原始回执、6 次手工重放请求** | 原始执行在后台完成且业务只推进一次；同体重放只能是 `200+duplicate=true` 或 `409+DUPLICATE_TOOL_CALL_ID`；冲突重放不得成功且必须可见为 409；两次重放后 history/run/ledger/业务均不变，history 响应仍恰一次；刷新后 DOM 成功卡恰一张且不再产生浏览器回执；请求体只留散列 |
| C-152 | 3 个新 UI 会话；每会话 **1 次 start_export + 1 次人工确认 + 1 次后台 download_export_files + 1 个完整下载 + 2 次刷新恢复**；合计 **6 个工具 run、3 个下载、6 次恢复** | HITL 必须在前台由真实点击放行，不在后台代替人决策；隐藏期间完成 1 个 EXPORT job、1 个 CURRENCY 文件和下载，**下载完成时仍 hidden**；浏览器工作簿与公开下载端点的全部单元格内容散列一致；history/run/回执与业务不重复；两次恢复均只有原成功卡，chat/confirm/frontend-result 次数不增加 |

所有浏览器场景按上表精确断言每轮 run 数量，runId 和 toolCallId 在轮内及三轮间都不得重复。所有场景还共同核对：完整单页 history（`latest=100`，不得 `hasMore/truncated`）、公开 run/pending/ledger 摘要、相关及非相关业务副作用、服务新增日志、两次会话删除与删除后 history 为空或 410、任务删除后 404、CDP target 关闭。C-152 还恢复下载行为并删除下载字节。

## 证据白名单与上限

允许保存的只有：

- 场景/轮次/状态、后端发行的 `sessionId`、`runId`、`toolCallId`、任务/作业 ID、状态、业务码、HTTP 状态、次数、时间戳、布尔值和有界枚举。
- DOM 的 `data-testid`、卡状态、真实 `document.hidden/visibilityState`、元素矩形、视窗尺寸、滚动高度、命中测试布尔值，以及输入值的长度、SHA-256 和是否等于预期值的布尔结果；**不保存原始输入 value**。
- history/run/business 的显式白名单投影及规范化散列，包括公开 `pending[].expiresAt/resolvedAtMs`。`part.result`、完整 `workspace/queryConditions`、正文、提示词、工具结果和条件文本只保留长度与 SHA-256；断言附件也使用同一投影，不因调试需要回存原对象。
- C-149/C-150 的业务指纹另含每个任务的 items、条件哈希、jobs，以及文件 `id/defCode/fileType/size` 白名单投影、逐文件 digest 和整组 filesDigest，防止漏掉配置项或文件副作用。
- 日志新增片段的字节数、SHA-256、WARN/ERROR 数，以及每会话 `{sessionId,runId,toolCallId,matchCount,oneShotInfoMatchCount,periodicDebugMatchCount}` 关联摘要；只有计数，不保存原始日志行、异常 message、堆栈或响应体。每场景每实例新增日志超过 1 MiB 时无法完整核对，不截断后冒充完整。
- C-152 工作簿的字节数、SHA-256、PK 标记、sheet/row/cell 数、解析字符数和全单元格内容散列。浏览器下载与公开下载只在内存中比较；下载文件随后删除，原始工作簿不进入证据附件。

禁止保存原始 API/SSE 响应、请求/回执体、DOM 文本、完整工作区对象、上游内容、截图、密钥、凭据、异常消息或业务原文。网络请求观察上限 4096，CDP 错误只留最多 64 条尾部摘要，网络失败只留最多 16 条尾部摘要；任何 CDP 错误、网络加载失败或 HTTP≥400 响应都为 `FAIL`。SSE 最多 256 帧且证据只留 16 条类型尾部；history 最多 100 条，且每条消息的 `toolCallStates` 键和 `toolResponses` 各最多 64；run pending 和 `ledgerCounts` 键各最多 64；浏览器消息/parts/selectedDefs 各最多 64、卡片最多 256 张；每任务 items/jobs/files 各最多 512 条；**任何数组或键集合超限均为 `FAIL`，不得只截断后放行。** 业务分页最多 100 页、每页 100 条；分页变化或证据不完整同样不得放行。`OUT_DIR` 及其实际路径必须位于仓库外。

C-152 在 JSZip 前限制下载总字节为 **16 MiB**；ZIP 条目最多 **256** 个，单 XML 条目最多 **8 MiB**，累计解析内容最多 **32 MiB**，shared strings 最多 **10000** 条，工作表最多 **32** 张，每表最多 **10000** 行且总计最多 **50000** 行，每行最多 **256** 个单元格且总计最多 **250000** 个单元格。上述任一超限均为 `FAIL`，不得以截断内容生成“完整导出”证据。

## 本批实际执行记录

仅执行了以下语法检查，均退出码 0：

```bash
node --check scripts/verification/ai-visibility-action-browser.mjs
node --check scripts/verification/ai-visibility-expiry-api.mjs
```

未运行上述六条场景命令，未读取或生成任何场景 `summary.json`，因此本文所有场景继续保持 `NOT_RUN`。agent-1166 自身仅修改上述两个验证脚本和本文；共享复测清单由主会话补入 C-147～C-152 并建立交叉引用，不属于 agent-1166 的改动范围。生产代码、普通测试和其他文档均未由 agent-1166 修改。
