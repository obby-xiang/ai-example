# AI 响应边界独立验证

> 载体：`scripts/ci/stub-upstream.mjs` + `scripts/verification/ai-response-boundary-api.mjs`。
> 定位：压测/独立脚本，使用公开 HTTP/SSE 与真实上游桩 chunk；不是普通单元测试，不修改或伪造应用内帧。
> 当前状态：2026-11-10 已单独执行 `confirm-step-timeline` 的“中间纯文本 207 + 宿主表单 + 最终纯文本 415”变体，旧生产算法 **GREEN**；未形成真实 RED，故未修改生产算法。其余场景仍为 **NOT_RUN**。历史施工记录、环境失败和本轮证据边界见第 7 节。

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
