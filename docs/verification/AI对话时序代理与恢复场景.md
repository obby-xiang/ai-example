# AI 对话时序代理与恢复场景

## 1. 用途与证据边界

`scripts/verification/history-timing-proxy.mjs` 是 C-138 的仓外证据、只读时序代理。这里的“只读”指代理不读取或改写业务 body、不直接访问 Redis/数据库、不主动发起业务写操作；客户端原本发送的方法和请求体仍原样转发。

代理只改变一次响应时序：进程启动后的第一个 `GET /api/ai/history/` 请求，其上游响应体延迟默认 800 ms 后转发；后续 history 请求和其他所有路径均为 0 ms。延迟从收到上游响应头时开始，响应状态和 body 保持不变。因为请求已先到达上游，旧 revision 可以先在上游形成，再晚于新现场返回，符合 C-138 的构造目的。

代理不是应用内帧生成器，不能用于：

- 编造、补写、重排或删除 SSE/history 内容；
- 伪造 `DONE`、错误帧、恢复完成、表单状态或业务副作用；
- 代替 C-131A/B/C 所需的确定性上游输出；
- 把真实模型偶然产生的文本当成指定变体证据。

当前文档只提供操作方法，不表示浏览器、DeepSeek、Redis、数据库、多实例或 Redis Cluster 已执行或通过。

## 2. 配置与非法配置拒绝

| 环境变量 | 必填/默认值 | 约束 |
| --- | --- | --- |
| `EVAL_ISOLATED_ENV` | 必填 | 必须严格等于 `true`，否则拒绝启动 |
| `EXECUTOR_IDENTITY` | 必填 | 执行者标识，去除首尾空白后不能为空，最长 128 字符且不能含控制字符 |
| `OUT_DIR` | 必填 | 必须是当前仓库之外的绝对路径；已有祖先和最终目录经过符号链接解析后仍必须在仓外 |
| `UPSTREAM_BASE_URL` | 必填 | 仅允许数字 loopback 地址的 HTTP URL：`127.0.0.0/8` 或 `::1`；禁止用户名、密码、query、fragment。为避免 hosts 重映射，不接受 `localhost` |
| `PROXY_PORT` | `18540` | 1～65535 的整数；监听地址固定为 `127.0.0.1` |
| `HISTORY_DELAY_MS` | `800` | 0～60000 的整数，只作用于第一次匹配的 history 响应体 |

缺少必填项、相对路径或仓内 `OUT_DIR`、非 loopback/HTTPS 上游、带凭证/query/fragment 的上游、非法端口或延迟都会在监听端口前拒绝启动。上游主机在配置阶段失败关闭，因此 `Authorization`、Cookie、`X-Api-Key` 等随请求转发的敏感头不会被代理发送到非 loopback；这些头本身不会写入日志。

启动成功只打印 `127.0.0.1` 代理地址、首次延迟和仓外日志路径，不打印上游凭证或请求内容。

## 3. DeepSeek 使用方式

### 3.1 拓扑

DeepSeek 仍由真实后端按既有模型配置直接访问，本代理只位于前端和后端之间：

```text
浏览器 → 前端 18541 → 时序代理 18540 → 后端（例如 8081）→ DeepSeek
```

不要把 `UPSTREAM_BASE_URL` 指向 DeepSeek，也不要把模型密钥配置到代理环境。以下命令是后续人工执行示例，本次交付未执行。

### 3.2 启动代理

在仓库根目录的 bash 环境中执行；`OUT_DIR` 必须替换为实际仓外绝对路径：

```bash
export EVAL_ISOLATED_ENV=true
export EXECUTOR_IDENTITY='<实际执行者>'
export OUT_DIR='<仓外绝对证据目录>'
export UPSTREAM_BASE_URL='http://127.0.0.1:8081'
export PROXY_PORT=18540
export HISTORY_DELAY_MS=800
node scripts/verification/history-timing-proxy.mjs
```

后端如使用其他端口，只修改 loopback `UPSTREAM_BASE_URL` 的端口。IPv6 上游可使用 `http://[::1]:8081`。

### 3.3 启动前端

另开终端，在仓库根目录执行：

```bash
VITE_API_BASE=http://127.0.0.1:18540 npm --prefix frontend run dev -- --port 18541
```

浏览器只访问前端实际打印的 loopback 地址。前端 API 基址必须是 `http://127.0.0.1:18540`，不能继续直连后端，否则不会产生 C-138 时序证据。

### 3.4 每次只执行一个变体

1. 按 C-131A、C-131B、C-131C 之一准备输入和确定性上游输出，形成该变体要求的多工具、多表单或非前缀正文；真实 DeepSeek 可作为独立真实供应商证据，但不能替代固定 A/B/C 输出。
2. 每个变体、每个独立轮次只启动一个代理实例。重启代理会重置“第一次 history”状态并创建新日志；不要把多个变体或轮次混在同一首次延迟状态里。
3. 经代理触发恢复：第一次 history 响应体延迟 800 ms，使旧 revision 后返回；第二次 history 为 0 ms。随后正常刷新做全量重放，并通过公开 `lastSeq` 做增量续传。
4. 比较有界白名单证据：history/SSE 的序号、状态、事件类型、长度与哈希，以及 DOM 白名单业务字段和业务计数。同 run 只能形成一个有序 UI 时间线；普通 SSE 只能应用 `seq > lastAppliedSeq`；旧 generation/revision 不得覆盖新现场；重复 toolCallId、`(entryId, segments[].seq)` 和既有压缩 eventId 不得增加 parts 或副作用。
5. 通过正文长度/哈希和白名单文本字段核对 A/B/C，工具和表单数量及顺序必须精确一致。接口 200、run 为 DONE 或代理进程正常均不能抵消内容差异。
6. 每变体执行 3 个独立轮次，保存当轮代理日志、history/SSE 的白名单摘要（序号、状态、事件类型、长度/哈希）、DOM 白名单字段、业务计数和清理证据；禁止保存原始 SSE/history 响应体。每次只运行一个变体，不并行串扰首次延迟状态。

## 4. 转发、失败与清理语义

- 请求方法、端到端请求头和请求 body 原样转发；`Host` 改为上游地址。`content-length` 不因代理重新计算。
- 按 HTTP 语义剔除 `Connection` 及其声明的逐跳头，以及 keep-alive、upgrade、transfer-encoding 等连接级字段；chunked 由 Node 在对应 hop 上重新处理，不改动应用 body。
- 上游状态码、状态文本和端到端响应头原样传递；每个下游响应均设置 `res.sendDate=false`，上游无 `Date` 时 Node 不会自动新增该端到端头。SSE 及普通响应均逐 chunk `pipe`，不读取完整流、不解析事件、不伪造帧。
- 上游返回 4xx/5xx 时保留真实状态。尚未收到上游响应就发生连接失败时，代理返回空 body 的 502；响应头已发出后发生失败则截断连接，不把失败改写成成功或额外 SSE 帧。
- 客户端断开时清理待执行延迟定时器并销毁对应上下游流。正常结束和异常结束只记录一次；停止信号和日志流错误都进入统一停止流程，日志流错误同时设置非零退出码。停止后最多等待 5 秒再强制销毁剩余 socket，随后保留有界 25 ms 让请求 close/error 日志结算，再关闭日志流，避免同轮 `write after end` 或丢失最后记录。

## 5. 日志字段与限制

代理在 `OUT_DIR` 创建权限受限的 JSONL 日志，每条包含：

- `sequence`：进程内有界序号 1～999999999，达到上限后从 1 重新计数；
- `executorIdentity`：必填执行者标识；
- `path`：仅 URL pathname，不含 query，最长 512 字符；
- `delayMs`：该请求实际选用的延迟，第一次 history 默认 800，其余为 0；
- `status`：客户端实际收到的状态；上游响应前失败为 502，客户端提前断开时可为 `null`；
- `startedAt` / `endedAt`：ISO 8601 起止时间；
- `errorName`：有界错误名或 `null`。

日志不记录 `Authorization`、Cookie、API 密钥、请求/响应 body 或原始异常消息和堆栈。代理日志只能证明请求路径、时序和状态，不能单独证明正文、parts、卡片、Redis 键/TTL 或业务数据库结果；这些仍须由有界白名单字段、序号、状态、长度/哈希和独立业务计数核验，不得保存原始响应体。
