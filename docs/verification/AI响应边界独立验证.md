# AI 响应边界独立验证

> 载体：`scripts/ci/stub-upstream.mjs` + `scripts/verification/ai-response-boundary-api.mjs`。
> 定位：压测/独立脚本，使用公开 HTTP/SSE 与真实上游桩 chunk；不是普通单元测试，不修改或伪造应用内帧。
> 当前状态：2026-11-11 已精确复现并修复 correction prefix 被后续工具批 settle 回写导致的 `AI_TRANSCRIPT_INCOMPLETE`。原始持久 history 与模型 history 共享同一预算决策，且全部双视图转换和映射校验均在任何预算提交前完成；无 usage/usage delta、真实 L1、HardTrim→wrap-up、L1 后 trim 失配原子性均完成独立进程 RED→GREEN。最终 `correction-prefix-transcript` 与普通 `late-reasoning` 均为 PASS。历史施工记录和其他场景边界见第 7、8 节。

## 1. 请求级公开标记

在最后一条用户消息中使用 `response-boundary=<case>`。无标记或未知值不改变既有桩路径；`standard-no-reasoning` 刻意不使用该标记，只使用既有 `turn-text`。

| CASE | 桩输入形态 | 公开判定 |
| --- | --- | --- |
| `late-reasoning` | 单个 `list_config_defs` tool chunk → `finishReason=tool_calls` → 两片 reasoning 摘要 → 恰好一个 usage chunk → `done=true`；续轮使用 `usage-shape=none` | 固定蓝本为“迟到思考甲：工具 finish 已到达，但完整响应尚未结束；”和“迟到思考乙：这两片与末尾 usage 必须先收齐，再释放工具聚合。”；逐片以既有 SHA-256/`Array.from` 长度核对上游摘要，再以固定拼接串精确核对公开 SSE、history 和第二请求实际 reasoning 回放。第二请求 `receivedAtMs` 不得早于首请求最终 `done` chunk 的 `writtenAtMs`，不使用可能较晚的 outcome 落盘时间；公开 reasoning 先于 `tool_start`；恰一个工具真实执行，history 以 `toolResponses[].id` 和同 run ASSISTANT `toolCallStates[id].status=EXECUTED` 对账；done.usage 只能取首请求唯一末尾 usage 的真值 |
| `second-tool-aggregate` | 首个工具 finish 后追加第二个独立 toolCallId | 预期协议拒绝：`error.code=UPSTREAM_STREAM_ERROR`、run=`FAILED`；本轮后端日志中当前 run 的 `attempt failed ... UPSTREAM_STREAM_ERROR` 必须在相邻异常栈内关联 `IllegalStateException: Tool calls after the tool aggregate finish are not supported`；helper/执行前失败，零工具帧、零 history TOOL、零 ASSISTANT `toolCalls`、零 ASSISTANT `toolCallStates`、零业务副作用、零续轮请求。此结果通过时归类 `EXPECTED_PROTOCOL_REJECTION`，不是健康 PASS |
| `multi-choice` | 普通无工具双 choice，固定六段“多选甲-A：”“多选乙-A：”“甲-B；”“乙-B；”“甲-C。”“乙-C。” | 逐段以 SHA-256/`Array.from` 长度核对上游 `content` 摘要和顺序，并要求存在双 choice chunk；公开正文及 history 与六段固定拼接串精确一致，无重复、丢项或工具执行；健康 `DONE` |
| `cancel-before-complete` | 工具 chunk 后复用 `stall-frames=5000` 留出取消窗口 | 驱动读取桩的实际写帧事件，在工具 chunk 后调用公开 `POST /api/ai/cancel/{runId}`；取消必须早于上游 `done=true` 或使其未到达；run=`CANCELLED`，deferred 工具不释放，零工具执行、零续轮请求。`outcome=ok` 且没有写失败 WARN 可接受；若为 `write-error`，必须有本轮同一 `req-NNN` 的唯一 `upstream response write failed req-N errorName=X` WARN，并满足取消时序。通过时归类 `EXPECTED_CANCELLATION` |
| `standard-no-reasoning` | 无 `response-boundary`、无 `reasoning=<chars>` 的标准文本请求 | 独立固定蓝本精确为“命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。”；按桩既有 `/[\s\S]{1,24}/g` 切片逐段核对上游摘要，公开正文精确等于完整蓝本，history/run 由公共精确对账。上游请求递归无 `reasoning_content`；每个结构化 choice 必须 `hasReasoningContent=false`；所有公开 SSE 帧递归排除 `reasoningContent/reasoning_content` 和 `segments[kind=reasoning]`，history 同样递归排除；健康 `DONE`，零工具执行 |

所有工具用例仍经过既有 `tool=<name>&args=<json>` 披露闸。桩只生成 OpenAI 兼容上游 chunk；`delta{kind=reasoning}`、`tool_start`、history parts 等应用帧必须由产品真实生成。所有 CASE 的同 run history ASSISTANT 均不得出现 `unconfirmed=true`、transcript 降级标记或 `AI_TRANSCRIPT_INCOMPLETE/AI_TRANSCRIPT_WRITE_FAILED`。每个上游请求必须恰好一个 outcome 且为最后事件，chunk 序号连续，`outcome.sequence === chunk 数 + 1`；late/multi/standard 的每个上游请求还必须唯一 `outcome=ok` 且末 chunk `done=true`，双 outcome 或其他终局不会被误判为成功。

上游 `events.jsonl` 不再包含 `raw`。驱动直接消费 `{sequence,type,writtenAtMs,done,choices,usage?}`；choice 使用 `finishReason/hasContent/hasReasoningContent/content/reasoningContent/toolCalls`，usage 使用 `promptTokens/completionTokens/totalTokens/cachedTokens/reasoningTokens`。`hasReasoningContent` 表示扩展键存在，包括空字符串和 null；只有非空文本才带 SHA-256 和长度。固定蓝本只由驱动独立定义，不能从哈希反推正文。

公开 SSE 每个连接都必须读到 EOF，不在首个 `done/error` 后停止或取消读取。首个终帧后的任何第二终帧、工具、reasoning 或其他 data 帧都会先记录再使整案 FAIL；注释和无 data 心跳继续忽略，非 JSON 对象 data（包括 `data:null`）也会记录并 FAIL。正常生产收尾只有首个终帧后接 EOF，不会因该闸门误伤。

## 2. 配置闸

每次进程只接受一个 `CASE`，不支持逗号列表或 `ROUNDS>1`。以下变量均为硬门槛：

- `EVAL_ISOLATED_ENV=true`
- `EVAL_ALLOW_WRITES=true`：只授权创建/取消/删除本轮会话及公开只读业务查询，不授权任意业务写入
- `EVAL_STUB_ONLY=true`
- `REASONING_DISPLAY_ENABLED=true`、`REASONING_PERSIST_CONTENT=true`：外部模式的执行者声明；受管模式显式设置既有 Spring 配置，最终仍以实际 SSE/history 判定
- `EXECUTOR_IDENTITY`：实际执行模型/人员上下文
- `TENANT_ID`、`USER_ID`：显式身份；会话号只取 `POST /api/ai/session` 的 `sessionIdText`
- `OUT_DIR`：绝对路径，创建前后均校验 realpath 必须位于仓外
- `START_SERVICES=true|false`：必须显式选择

脚本不读取或转发真实 API Key，不允许非 loopback `BASE_URL`，不启动真实 provider。受管子进程使用最小环境白名单和固定占位 key。Redis 是现有 AI 会话存储前置条件，脚本不自起 Redis；直接 Redis 键/TTL 验证和直接数据库验证不属于本脚本结论。

## 3. 隔离后端 + 桩启动

预先准备当前代码的 `backend/target/config-mgr.jar`；脚本不编译、不打包。受管模式执行以下方式（示例仅说明用法，本轮未执行）：

```bash
export CASE=late-reasoning
export OUT_DIR=/absolute/outside/repository/ai-response-boundary
export START_SERVICES=true
export BACKEND_JAR=/absolute/path/to/config-mgr.jar
export BACKEND_PORT=28630
export STUB_PORT=28699
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export TENANT_ID=1
export USER_ID=101
export EXECUTOR_IDENTITY='<actual-executor>'
export EVAL_ISOLATED_ENV=true
export EVAL_ALLOW_WRITES=true
export EVAL_STUB_ONLY=true
export REASONING_DISPLAY_ENABLED=true
export REASONING_PERSIST_CONTENT=true
node scripts/verification/ai-response-boundary-api.mjs
```

受管模式先检查两个端口空闲，启动现有 `scripts/ci/stub-upstream.mjs`，再以仓外临时 H2 文件库、独立工作目录和文件存储启动后端；示例端口 `28630/28699` 与既有 `18530` 后端分离。桩只写独立的 `upstream-live/` 运行目录；驱动直接读取该目录并生成有界摘要，不复制 `req-*` 原始文件，也不存在源、目标相同的 `copyFile`。结束时会话清理后依次停止后端和桩，确认端口关闭并删除本轮 H2、工作目录和文件存储。上游证据收集和日志关联完成后，还必须删除证据 runDirectory 内的 `upstream-live/`、`backend.log`、`stub.log`，写入 `sanitization.json` 和 `managed.sanitization-complete` 断言；任一删除失败整案 FAIL，不能掩盖。

## 4. 接已启动的隔离后端 + 桩

外部模式必须提供 `BASE_URL`、`STUB_DUMP_DIR`、`BACKEND_LOG_FILE`、`STUB_LOG_FILE`，其余闸不变，并设置 `START_SERVICES=false`。三个原始输入路径的 realpath 必须同时位于仓外和 `OUT_DIR` 之外，避免原始输入混入证据根。`STUB_DUMP_DIR` 必须是本案专用且启动前没有任何 `req-*` 文件，避免混入其他 CASE 或重复轮次。后端必须由执行者以同样的临时 H2、占位 key、当前桩和 reasoning 配置预先启动；脚本不接管这些外部进程，也不删除外部输入。

脚本在执行 CASE 前记录后端和桩日志的字节偏移，只分析本轮追加内容；不会用全文件 `includes` 接受旧 marker。追加窗口最大 2 MiB，若发生截断则整案 FAIL，不能借截断漏掉异常。五个 CASE 应分别调用五次，每次使用新的专用空 stub dump 和输出子目录，不在一个进程内批量循环。

## 5. 证据与失败分类

每次在 `OUT_DIR/ai-response-boundary/<CASE>/<timestamp-uuid>/` 保存有界白名单证据。断言仍在内存中读取实际公开响应、完整帧和请求体做精确比较；落盘只保留断言所需的固定字段、序号、时间戳、长度和 SHA-256，不保存完整原始 SSE block、完整上游请求体、完整 run/history、原始异常体或原始日志附件。

- `public-requests.jsonl`：固定合成公开请求的会话/客户端标识、`contextIsNull`、消息长度和哈希，以及 HTTP 状态和白名单业务摘要，不记录 Authorization；`path` 只保留 pathname，query 仅保留数字形式的 `latest/lastSeq/page/size`，不保存 history `before` 游标或其他完整 query；
- `upstream-summary.json`：请求判定、请求体和消息哈希、白名单 role/tool/usage、`chunkCount`、chunk 序号与时间戳、`done`、choice 的 content/reasoning 摘要和唯一 outcome；不读取或保存 `events.raw`，不复制 `upstream/req-*` 原始文件；
- `sse.jsonl`：按到达顺序保存 channel、观测时间、block 哈希及白名单解析帧；正文和 reasoning 只保留长度与哈希，工具 ID/名称/状态和 usage 保留实际白名单值；
- `history.json`、`run.json`：公开 history 和同 run 权威状态的白名单摘要、消息/正文/reasoning/工具结果哈希、工具调用状态及 segments 摘要，不是完整原始响应；每页 history 顶层 `truncated` 缺失时按 `false` 处理；字段存在但非 boolean 则 FAIL，任一页显式 `truncated=true` 就令 `complete=false`，证据保留逐页及汇总值，公共断言同时要求 `complete=true` 和 `truncated=false`，不自设成功；
- `business-before.json`、`business-after.json`：公开任务/作业 API 的有界分页结果指纹、ID 集合和前后对照，不冒充直接数据库证据；
- `log-offsets.json`（外部模式）与 `log-analysis.json`：本轮起始偏移、读取范围、WARN/ERROR/FATAL/Exception 的行号、分类和行哈希；second/cancel 白名单关联不满足时整案 FAIL；
- `cancel.json` 或 `expected-rejection.json`：同一请求的取消/outcome/日志关联，或当前 run 的协议拒绝行与相邻异常栈关联；`zeroToolExecution` 与 second 主断言使用同一完整口径：公开工具帧、history TOOL、ASSISTANT `toolCalls` 和 ASSISTANT `toolCallStates` 均为零；`upstreamContinuationCount` 由实际上游请求数减一计算；
- `cleanup.json`、`managed-cleanup.json`：两次 DELETE 的 `complete/deferred/errors`、删除后 history、业务后态、进程退出、端口和临时数据清理；
- `sanitization.json`：受管 `upstream-live/`、`backend.log`、`stub.log` 的逐项删除结果；外部模式为 `NOT_MANAGED`；
- `summary.json`：有界化的逐断言 expected/actual、真实失败及 `HEALTHY_PASS`、`EXPECTED_PROTOCOL_REJECTION` 或 `EXPECTED_CANCELLATION` 分类；非固定短标识的字符串以长度和哈希保存。

上游证据最多 16 个请求，每请求 JSON 最大 1 MiB、事件文件最大 8 MiB/4098 条；公开 SSE 最多 2048 帧，history 最多 20 页。超出边界按失败处理，不通过无限留存换取可排障性。

`summary.status=PASS` 只表示本案全部公开断言、清理和受管 sanitization 通过。`statusScope` 明确 real provider、direct Redis、direct database 均为 `NOT_RUN`、没有 verdict；浏览器、Cluster 和多实例也不得外推。

## 6. 本轮静态验证边界

- `node --check scripts/verification/ai-response-boundary-api.mjs`：PASS（本轮仅语法检查，不代表任何 CASE 可运行或通过）。
- `node --check scripts/ci/stub-upstream.mjs`：NOT_RUN（本轮未重复执行；既有施工记录不作为本轮动态证据）。
- 桩 `--self-check`：NOT_RUN，按施工要求未执行。
- 五个真实 CASE、服务起停、测试、压测、普通测试、Maven 构建、真实 API/SSE、业务与清理：全部 NOT_RUN；本文不声明任何动态 PASS。

## 7. 2026-11-10：宿主表单后的 415 字最终纯文本复核

### 7.1 调用链与当前算法（只读审查）

`ResilientChatService.completeTimeline` 按终态历史条目顺序调用 `populateAssistantSegments`，后者逐条调用 `SseChatEmitter.transcriptSegments(entryId, toolCalls, text, visibleReasoning)`。当前 `SseChatEmitter` 的边界是：

- 已认领 `entryId` 的段必须再次通过全文、reasoning 和工具 ID 精确对账；
- 空正文/空 reasoning 的工具条目只取首个至末个工具锚点，不向前吸收未认领纯文本；
- 纯文本候选在工具锚点或已认领条目处断开，候选全文必须精确等于当前条目正文，不能模糊匹配；
- 本地时间线不完整时从归档帧重建；归档读降级、截断或精确对账失败仍返回 `null`，不得伪报 `historyComplete=true`。

这意味着 c258851e 针对“空工具条目吸收前段纯文本”的修复在当前源码中确实存在，但仅凭错误码不能证明另一设备的 task609/task737 与当前复现形态相同。

### 7.2 独立变体与参数

复用 `confirm-step-timeline` 的公开 API/SSE、真实宿主表单和公开回灌，不直接写 Redis、数据库或 history。验收脚本新增可选 `FINAL_TEXT_LENGTH`（32..4096），仅在显式设置时增加 `timeline.final-text-length` 精确断言；默认行为不变。

本案使用仓外独立桩，蓝本与既有 `[207,99]` 能力同族：

- 中间纯文本：`前段正文：` + 202 个 `甲`，合计 207 字符；
- 最终纯文本：`终段正文：` + 足够数量的 `乙`，总长度由 `FINAL_TEXT_LENGTH=415` 决定；
- 两次纯文本非空前缀关系；三次上游请求、宿主条件表单和宿主步骤确认均由产品真实生成。

仓外桩路径为 `<仓外独立桩>/stub-upstream.mjs`；它不是仓内生产文件或新增依赖。执行器为 Kimi Code，CASE 单进程只运行一次，未批量或循环普通测试。

### 7.3 RED / GREEN 事实

**真实 RED：NOT_OBSERVED。** 旧 `SseChatEmitter.java` 未做任何修改，目标变体直接 GREEN，因此本轮没有修改生产算法，也没有把环境或桩构造失败伪装成 RED。

有效 GREEN 证据目录：

`<仓外证据根>/ai-response-boundary/confirm-step-timeline/2026-11-10T12-38-23-188Z-d767ad35-98fd-450c-a1d4-6c2131ccbfa7/`

- `summary.json`：`status=PASS`、`failedAssertions=0`、终帧 `done`、`seq=44`，无 `AI_TRANSCRIPT_INCOMPLETE` / `AI_TRANSCRIPT_WRITE_FAILED`，无 `transcriptDegraded` 或 `historyComplete=false`；
- 公开帧序：初始步骤工具 `seq=2..5` → 中间纯文本 delta `seq=7..15` → 宿主表单 `seq=17..19` → 宿主步骤确认 `seq=21..23` → 最终纯文本 `message_start/delta/message_end` `seq=24..43` → `done seq=44`；
- `history.json`：共 9 条消息；中间纯文本条目正文 207、其 text segment 也为 207（`seq=7`）；宿主表单与宿主步骤 ASSISTANT 均只有各自工具锚点；最终纯文本条目正文 415、其 text segment 也为 415（`seq=25`）；没有工具条目吸收前段正文；
- `run.json`：运行状态 `DONE`，`archiveReadable=true`、`archiveTrimmed=false`、`recoverable=complete`，`finalText` 为同一 415 字正文；
- 上游请求数、公开回灌接受、业务夹具清理、会话清理和本轮日志分类均通过。

以下执行不作为产品 RED：

1. 受管首次运行因 `6379` 无 Redis 监听而停在创建会话前，`sessionId/runId` 均未产生；后续改用本案专用 Memurai 和仓外日志。
2. 外部模式切换时，前一后端未退出导致新后端端口冲突，另一请求得到 `SESSION_ID_UNAVAILABLE`；换新端口并确认 Redis 可用后重新执行。
3. 使用仓内默认桩时，蓝本实际是中间 18、最终 562，不是目标形状；该轮产品仍为健康 `DONE` 且无转录降级，失败仅因新增 415 长度断言不匹配。随后恢复原有公开工具结果，改用上述仓外 207/415 蓝本。

### 7.4 构建、格式化与未覆盖边界

- `node --check scripts/verification/ai-response-boundary-api.mjs`：PASS。
- IDEA 内置 Maven：`<IDEA Maven 可执行文件> -Dmaven.test.skip=true compile`（工作目录 `backend/`）：**BUILD SUCCESS**，358 个源文件编译完成；编译覆盖当前工作区，含其他并行 agent 的未提交后端改动。
- 初次 `package` 曾因正在运行的旧 jar 在 Windows 上无法重命名而失败；没有停止他人进程，改为仓外启动产物。该失败不作为编译通过证据，最终成功证据仅取上述 `compile`。
- 生产 `SseChatEmitter.java` **零改动**，故未对其执行 Reformat，也不存在生产 diff。
- 本轮只证明同进程、公开 API/SSE、独立桩下的 207→宿主表单→415 形态；task609/task737 的另一设备原始归档帧与终态条目尚未取得，不能断言其当前根因已经定位或修复。
- **NOT_RUN**：挂起期间进程重启/跨实例驱动后的归档重建、归档截断或读降级、真实模型、真实浏览器、多实例/Cluster、普通批量测试、直接 Redis/DB 验证，以及其他 CASE。需要后续证据时，优先取得复发 run 的完整帧序、每条 history 的 expected/actual 文本归属及是否发生 emitter 更换；不得仅凭 415 长度或相同错误码继续拟合。

## 8. 2026-11-11：correction prefix 规范化历史被后续工具批写回

### 8.1 精确写回路径与 RED

本次只复用既有 `correction=empty-suffix-tool` 资产，不再泛化搭建其他场景。公开驱动顺序为：

1. 首个上游响应输出 149 字 correction prefix，触发 create_task correction；
2. 第二响应输出同一前缀 + 49 字有效后缀 + `create_task`，公开 `/api/ai/confirm` 拒绝创建，业务任务/作业均为零；
3. 第三响应调用 `list_config_defs`，形成 create_task 之后的真实工具批 settle；
4. 第四响应输出 392 字终答。

静态与动态共同确认：`correctionHistory` 将快照历史规范化为模型输入；后续 `settleBatch` 从该 prompt 取 history 并调用 `isSettle`，使 49 字旧 create_task 被写回 `messageJson`。终局从该快照得到的 expected=49，而 SSE 归档保留一次 149 前缀 + 49 后缀 = 198，导致精确比较失败。

RED 证据目录：

`<仓外证据根目录>/correction-prefix/precise/evidence/ai-response-boundary/correction-prefix-transcript/2026-11-11T03-38-34-645Z-692f8122-a28b-43e7-9f3a-db2c979609c7/`

- `summary.json`：`status=FAIL`，终帧 `error.code=AI_TRANSCRIPT_INCOMPLETE`，`transcriptDegraded=true`、`historyComplete=false`、`runStatus=DONE`；上游请求数恰为 4，业务前后均为零任务/作业。
- `run.json`：运行 `DONE`，`finalText` 为 590 字（149+49+392），没有重复前缀。
- `history.json`：create_task 的 SSE/条目原文为 198；后续 `list_config_defs` 工具及响应存在；终局历史写入失败。
- 后端日志：`terminal transcript rejected ... AI_TRANSCRIPT_INCOMPLETE`，`AI turn end ... historyEntries=0`。

### 8.2 最小生产修复

只修改两处生产行为：

- `SpToolCallingManager.settleBatch`：`persistedHistory` 从当前快照原始 `messageJson` 前进，只有快照为空才回落 prompt instructions；`modelHistory` 从 prompt instructions 前进。`applyTurnBudget` 对同一决策分别推进模型/持久视图：L1 与 shrink 按相同 ID 应用计划，HardTrim 按真实 UserMessage/run/toolCallId 结构应用相同轮次删除，wrap-up 同步追加同一合成消息；`TurnBudgetOutcome` 同时携带两份 history。`isSettle` 使用持久视图，下一跳使用 `CorrectionPrefixes.historyWithSingleCorrectionPrefix(budget.modelHistory(), ...)`；映射不可靠时在记账前 fail-closed，预算档位与溢出出口没有放宽或绕过。
- `ResilientChatService.persistSuccessSnapshot`：现有 `removeAssistantWithText` 改用 `correctionPrefix(snapshot)` 获取真实前缀，在清除标记前移除独立前缀条目；没有修改该方法，也没有删除带 tool_calls 的 assistant。

未修改 `SseChatEmitter`、`removeAssistantWithText`、前端、协议、reasoning 或工具/卡片实现；没有新增字符串过滤、长度/话术/模型特判或依赖。`persistSuccessSnapshot` 的 `correctionPrefix` 形参保留：本轮没有需要机械清理的冗余点，继续改签名会扩大调用面，与根修无关。

### 8.3 首次 GREEN 与普通回归

首次精确场景 GREEN 证据目录：

`<仓外证据根目录>/correction-prefix/precise/green2-evidence-pass/ai-response-boundary/correction-prefix-transcript/2026-11-11T03-54-04-930Z-f2f01763-b793-4a63-ab20-ddd7a7946c52/`

- `summary.json`：`status=PASS`、`failedAssertions=0`，终帧 `done`，无 `AI_TRANSCRIPT_INCOMPLETE`、无 `transcriptDegraded`、无 `historyComplete=false`。
- create_task 正文与 segments 均保留 198 字原始前缀+后缀；后续模型请求中的 create_task 为规范化后的 49 字，证明两个边界没有混用。
- 后续 `list_config_defs` 工具调用/响应完整，392 字终答完整；history 与 SSE/finalText 均为 590 字，前缀和终答各出现一次；无重复 entryId、无业务副作用。
- 会话删除和业务清理通过；唯一 rejection WARN 以 runId 和完整文案精确备案，其他 WARN/ERROR 未放行。

首次普通无 correction prefix 回归 `late-reasoning`：

`<仓外证据根目录>/correction-prefix/precise/regression2-evidence/ai-response-boundary/late-reasoning/2026-11-11T03-54-55-250Z-c7ccdc16-7265-4458-a8d6-5350b23896ac/`

`status=PASS`、`failedAssertions=0`。当时两个生产 Java 文件均已执行 IDEA Reformat，Maven `-Dmaven.test.skip=true compile` 编译 358 个源文件成功；`node --check` 通过。首次生产 diff 为 `SpToolCallingManager.java` +3/-1、`ResilientChatService.java` +1/-1。另一次回归因证据目录指错而没有形成有效产品结论，未计为 PASS 或 RED。

### 8.4 独立审查返修：两条预算路径

首次修复虽然从快照原文构造 history，却把同一份原始 history 同时交给 `applyTurnBudget` 与 `isSettle`：原文中独立 149 字前缀和 create_task 内的 149+49 都会被计入，和下一跳实际模型输入不一致。新增独立 `CorrectionPrefixTurnBudgetTest`，只依赖生产接口与 Mockito；旧 `SpToolCallingManagerTurnBudgetTest` 已恢复到 HEAD，未修复其既有装配漂移。

两条路径分别定向 `javac`，并在独立 Maven Surefire 进程中先 RED、后 GREEN。阈值由真实 `ContextEstimator` 动态计算，使规范化输入低于 L1、包含重复 prefix 的反事实达到 L1 但低于收尾线；两条断言同时要求旧结果不被提前清理、快照 create_task 保留 198、返回模型 history 为 49。

- 无 usage 整包估算：`<仓外证据根目录>/correction-prefix/review/budget-estimate/`。`red.log` 为 `Tests run: 1, Failures: 1`，`nextEst=327 / limit=404`，旧结果被清成 `[较早的工具结果已清理]`；`green.log` 为 `Tests run: 1, Failures: 0, Errors: 0`、`BUILD SUCCESS`。
- 有 usage delta（`promptTokens=5000`）：`<仓外证据根目录>/correction-prefix/review/budget-usage/`。`red.log` 为 `Tests run: 1, Failures: 1`，`nextEst=5167 / limit=7318`、`usage=true`，同样提前清旧结果；`green.log` 为 `Tests run: 1, Failures: 0, Errors: 0`、`BUILD SUCCESS`。

参考命令中的 `-Dmaven.compiler.testSkip=true` 实际仍触发 713 个测试源码编译，并被既有 14 个 `AiRateLimiter/ClientIpResolver` 缺失错误阻断；该次没有执行到目标测试，不计为 RED。随后使用 `mvn -Dtest=CorrectionPrefixTurnBudgetTest#<单点> surefire:test` 直接运行已定向编译的类，未修改或绕过任何产品断言，也未批量执行普通测试。

### 8.5 返修后的精确 GREEN、构建与边界

最终精确场景：

`<仓外证据根目录>/correction-prefix/review/correction-evidence3/ai-response-boundary/correction-prefix-transcript/2026-11-11T04-38-26-039Z-65fa3de3-6b3c-41c0-bc4f-022f51445b61/`

- `status=PASS`、`failedAssertions=0`。`modelCreateTexts` 现在取第四请求全部 create_task assistant，断言恰好一条且为 49；同一请求全部 assistant 文本中的 149 字 prefix 总次数断言为 1，不再先排除 198 后计数。
- 持久 create_task 与 segments 保留 198，history/SSE/finalText 均为 590；后续工具、392 字终答、公开拒绝、业务零副作用与清理均通过。
- 最终普通 `late-reasoning`：`<仓外证据根目录>/correction-prefix/review/late-evidence/ai-response-boundary/late-reasoning/2026-11-11T04-38-47-118Z-feef38b1-ce6b-424c-b0ce-329a597813ba/`，`status=PASS`、`failedAssertions=0`。
- 两个日志窗口 `unexpected=[]`。启动期既有 `min-messages == max-messages` 配置提示与 Vector API incubator 提示已逐条复核；运行期仅有隔离环境 lease-less 和本次故意拒绝 create_task 的备案 WARN，没有 `AI_TRANSCRIPT_INCOMPLETE` 或 `historyEntries=0`。
- 两次托管服务尝试未计为产品结论：第一次误用普通仓内桩，未形成目标时序；第二次产品断言已通过，但托管启动期日志和 Windows 子进程清理使整体为 FAIL。最终 PASS 使用同一当前类 JAR、同一专用桩的外部服务模式，启动日志另行复核，服务与 Memurai 均显式关闭。

构建与最终 diff：

- `node --check scripts/verification/ai-response-boundary-api.mjs`：PASS。
- 返修后的 `SpToolCallingManager.java` 使用 IntelliJ IDEA 2026.2.4 `format.bat`：1 file scanned、1 file formatted；`ResilientChatService.java` 沿用首次已格式化内容，本轮未再改动。
- IDEA 内置 Maven 在 `backend/` 执行 `-Dmaven.test.skip=true compile`：返修时 358 个源文件 **BUILD SUCCESS**；格式化后最终命令再次 **BUILD SUCCESS**，显示 `Nothing to compile - all classes are up to date`。
- 最终生产 diff 为 `SpToolCallingManager.java` **+14/-9**、`ResilientChatService.java` **+1/-1**，合计 **15 行新增、10 行删除**；新增独立测试、验收脚本和本文档不属于生产代码。

**NOT_RUN**：真实模型、真实浏览器、多实例/Cluster、直接 Redis/DB 验证、批量普通测试、其他 CASE、审批通过 create_task 后的完整宿主执行链、turn-budget 的 L3/溢出与空快照回落变体。两条预算单点覆盖的是 L1 边界及原始持久化/模型投影分离，不能外推为全部预算或 correction 形态已经验证。

### 8.6 第二次独立复审：预算决策同步到双视图

第二次复审指出：第 8.2 节早期实现把未经预算变更的 `persistedHistory` 直接交给 `isSettle`，导致 L1 清理、overflow shrink、HardTrim 与 wrap-up 只影响模型 history，而预算台账/armed 已推进。此次只修改 `SpToolCallingManager` 的预算执行面，复用既有 `ToolResultClearing.Plan`、`ToolResultShrink.Plan`、`HardTrim.Plan`，没有修改这些策略类或新增平行清理框架。

最终 `TurnBudgetState` 同时持有 `current`（模型）与 `persistedCurrent`（原始持久），同一次 `TurnBudgetPlan.Decision` 分别应用：

- L1：两份计划 `clearedIds` 必须一致，随后同步替换对应 tool response；
- shrink：两份计划 `shrunkIds` 必须一致，随后应用各自对应的收缩结果；
- HardTrim：轮次数及裁后真实 UserMessage/run/toolCallId 结构必须一致，不使用列表下标对齐，允许 correction 投影删除独立 assistant；
- wrap-up：同一 `TURN_BUDGET_WRAP_UP` 同时进入两份 history。

任一映射失配都在预算记账与 settle 前抛出 `STATE_UNAVAILABLE`，没有只清理模型侧后继续。`TurnBudgetOutcome` 同时返回 `modelHistory/persistedHistory`，分别用于下一跳和 `isSettle`。

新增单点均在独立 Surefire 进程中先 RED、后 GREEN：

- L1：`<仓外证据根目录>/correction-prefix/review/budget-l1-sync-red.log` 真实触发 `CLEAR_TOOL_RESULTS`（`nextEst=239 / limit=299`），模型已清而快照仍为 `旧结果一：任务列表 A`，`Tests run: 1, Failures: 1`；`budget-l1-sync-green.log` 为 `Tests run: 1, Failures: 0, Errors: 0`，两份 history 的 old-1 都为清理占位符，快照 create_task=198、模型=49。
- HardTrim→wrap-up：`<仓外证据根目录>/correction-prefix/review/budget-trim-wrap-sync-red.log` 真实删除 2 个旧用户轮次并进入 `WRAP_UP`（`trimmedTurns=2`、`armed=true`），快照缺同一合成收尾消息，`Tests run: 1, Failures: 1`；`budget-trim-wrap-sync-green.log` 为 `Tests run: 1, Failures: 0, Errors: 0`，两份 history 都删除旧轮、保留 create_task、以同一合成 UserMessage 收尾，快照 create_task=198、模型=49。

此前两条不提前清理路径也在双视图最终实现上分别重跑：

- `<仓外证据根目录>/correction-prefix/review/budget-estimate/dual-view-green.log`：无 usage，`Tests run: 1, Failures: 0, Errors: 0`。
- `<仓外证据根目录>/correction-prefix/review/budget-usage/dual-view-green.log`：usage delta，`Tests run: 1, Failures: 0, Errors: 0`。

最终公开场景：

- `correction-prefix-transcript`：`<仓外证据根目录>/correction-prefix/review2/correction-evidence-final2/ai-response-boundary/correction-prefix-transcript/2026-11-11T05-18-33-041Z-5cd258c3-7094-40fc-a80c-e25143c4e0b1/`，`status=PASS`、`failedAssertions=0`。
- `late-reasoning`：`<仓外证据根目录>/correction-prefix/review2/late-evidence-final2/ai-response-boundary/late-reasoning/2026-11-11T05-18-45-257Z-ddd4e8c1-7d44-46b8-b78a-1cc0e95f593b/`，`status=PASS`、`failedAssertions=0`。
- 两个日志窗口 `unexpected=[]`；启动期 warning 类型与第 8.5 节相同，运行期仅隔离 lease-less 与故意拒绝 create_task 的备案 WARN。最终 JAR 的 `SpToolCallingManager.class` 与编译 class 的 SHA-256 均为 `46db7b45505ac1c4f8e64401995d44743d47cf4c5ca9132633781532c65b2c78`，后端、专用桩和 Memurai 均已关闭。
- 较早公开复测曾因 JAR  staging 目录嵌套而沿用旧 class，不作为最终字节证据；重新从基础 JAR 组装并核对上述哈希后才形成本组最终 PASS。

最终构建与 diff：

- `SpToolCallingManager.java` 再次执行 IDEA 2026.2.4 Reformat：1 file scanned、1 file formatted。
- 双视图实现编译 358 个源文件 **BUILD SUCCESS**；格式化后 Maven `-Dmaven.test.skip=true compile` 再次 **BUILD SUCCESS**，显示 `Nothing to compile - all classes are up to date`。一次从仓库根目录误执行 Maven 的 `MissingProjectException` 已在 `backend/` 正确重跑，不计为编译通过。
- 最终生产 diff：`SpToolCallingManager.java` **+88/-22**、`ResilientChatService.java` **+1/-1**，合计 **89 行新增、23 行删除**。第二次复审新增的是双视图状态、计划映射与 fail-closed 校验；其他生产文件未改。

**NOT_RUN**：真实模型、真实浏览器、多实例/Cluster、直接 Redis/DB、批量普通测试、其他 CASE、审批通过 create_task 的完整宿主链、overflow shrink 的双视图专项触发、映射失配矩阵及空快照回落。此次已实际触发 L1 和 HardTrim→wrap-up，但未覆盖所有预算恢复分支。

### 8.7 最终复审：映射失配前的预算提交原子性

最终复审覆盖的可达路径为：overflow → L1 双视图清除及记账成功 → 清理后仍 overflow → shrink/HardTrim 映射失配。第 8.6 节实现会在失配前写入 L1 状态、估算基线和台账，造成 settle 未执行但预算状态已推进。

本次只增加提交前预校验：`applyTurnBudget` 先在 `TurnBudgetState.forValidation()` 的独立状态上执行完整预算 passes，`commit=false` 时复用相同的双视图转换、ID/结构校验与重判，但不允许 L1/trim 记账或 wrap-up 标记。全部成功后才以 `commit=true` 按既有顺序执行真实 L1/trim 记账、wrap-up 标记和 settle。没有事后删除或回滚 Redis 状态，也没有忽略映射失败。

新增 `trimMappingFailureDoesNotCommitEarlierL1OrAnyOtherBudgetState` 单点：L1 双视图计划成功且清理后仍达到硬上限，保留轮次的真实 UserMessage 结构失配使 HardTrim 必败。断言覆盖 L1 上下文状态/基线/台账、trim/wrap-up 标记、`RunStore.save`、快照原文和 round。

- RED：`<仓外证据根目录>/correction-prefix/review2/atomicity-red.log`。日志先写 `in-turn L1 clearing recorded`，随后 `NeverWantedButInvoked` 证实 `ContextStateStore.write` 已写 `level=L1, usedTokens=1166, est=true`，`Tests run: 1, Failures: 1`。
- GREEN：`<仓外证据根目录>/correction-prefix/review2/atomicity-green.log`。仍为 `STATE_UNAVAILABLE`，但没有 L1/trim 记账日志；`write/appendLedger/appendEvent/markTurnBudget/save` 均为零调用，快照原文和 round=0 不变，`Tests run: 1, Failures: 0, Errors: 0`。

正常路径独立复跑均通过：

- L1：`<仓外证据根目录>/correction-prefix/review2/l1-atomicity-green.log`，真实 `CLEAR_TOOL_RESULTS`，1/1 通过。
- HardTrim→wrap-up：`<仓外证据根目录>/correction-prefix/review2/trim-wrap-atomicity-green.log`，`trimmedTurns=2`、`armed=true`，1/1 通过。
- 无 usage：`<仓外证据根目录>/correction-prefix/review2/estimate-atomicity-green.log`，1/1 通过。
- usage delta：`<仓外证据根目录>/correction-prefix/review2/usage-atomicity-green.log`，1/1 通过。

最终公开场景：

- `correction-prefix-transcript`：`<仓外证据根目录>/correction-prefix/review2/correction-evidence-atomic/ai-response-boundary/correction-prefix-transcript/2026-11-11T05-44-01-036Z-8d20b763-d29a-4bd7-8880-c2d1485fe048/`，`status=PASS`、`failedAssertions=0`。
- `late-reasoning`：`<仓外证据根目录>/correction-prefix/review2/late-evidence-atomic/ai-response-boundary/late-reasoning/2026-11-11T05-44-14-515Z-996f6489-2c7e-4306-83af-a115d9770058/`，`status=PASS`、`failedAssertions=0`。
- 两个日志窗口 `unexpected=[]`；最终 JAR 与当前编译的 `SpToolCallingManager.class` SHA-256 均为 `1c8e347b7ec46b922ec1d21f42ac5884ad913805db48503859ad6379a00cb7a9`，后端、专用桩和 Memurai 均已关闭。

构建与最终 diff：

- `SpToolCallingManager.java` 已执行 IDEA 2026.2.4 Reformat：1 file scanned、1 file formatted。formatter 退出阶段出现项目 dispose 日志，扫描与格式化已完成，后续编译另行核验。
- 原子性返修编译 358 个源文件 **BUILD SUCCESS**；格式化后 Maven `-Dmaven.test.skip=true compile` 再次 **BUILD SUCCESS**，显示 `Nothing to compile - all classes are up to date`。
- 最终生产 diff：`SpToolCallingManager.java` **+117/-34**、`ResilientChatService.java` **+1/-1**，合计 **118 行新增、35 行删除**。此次新增仅为 `forValidation`、`commit` 开关及提交前完整 passes。

**NOT_RUN**：真实模型、真实浏览器、多实例/Cluster、直接 Redis/DB、批量普通测试、其他 CASE、审批通过 create_task 的完整宿主链、overflow shrink 双视图专项触发、其他映射失配组合及外部 Redis 写入失败矩阵。此次证明的是映射失配发生前不提交预算状态，不声称所有基础设施失败都能事务回滚。
