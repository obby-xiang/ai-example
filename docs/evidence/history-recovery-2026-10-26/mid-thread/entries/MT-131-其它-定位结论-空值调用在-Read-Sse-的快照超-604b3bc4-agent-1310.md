<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-131 · 其它 · 定位结论：空值调用在 `Read-Sse` 的快照超时路径（604b3bc4 / agent-1310）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`b23l1-dual-instance`、`SseChatEmitter.java`、`RunStore`。
> **稿态**：历史中间稿 —— 该 wire 第 `m112` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 23 条有正文），末条有正文消息为 `m386`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1310`（`main` = 主会话）；原字符 2,606（链内 1 段、合计 2,606）。
> **链号 / 类别**：`MT-131` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 3 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `12%`。
> **与末条归档（138 件）的关系**：末条 `m386`（2,759 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 定位结论：空值调用在 `Read-Sse` 的快照超时路径

**具体行：`scripts/b23l1-dual-instance.ps1:248-250`（修前）**

```powershell
$resp = $script:http.SendAsync(...).Result
if (-not $resp.IsSuccessStatusCode) {
    $t = $resp.Content.ReadAsStringAsync().Result   # ← $resp 为 $null 时，空值上调用 ReadAsStringAsync()
```

**为什么 `$resp` 会是 null**：B 侧对 `?lastSeq=4` 的差量回放是 **0 帧**（`instance-b.log:213`：`reattach runId=05afe6e7… status=SUSPENDED lastSeq=4 回放帧数=0`）。后端在 `replayAndAttach` 里 `staged==0` 时既不 `kickDrain` 也不 `complete`（`SseChatEmitter.java:519-533`），SSE 一个字节都不写 ⇒ 响应头不 commit。脚本这边 4s 的 `CancelAfter` 打断等待时，**`.Result` 不抛异常而是返回 `$null`**（本地用「只接受连接、不回任何字节」的 mock 精确复现：`SEND_RETURNED respNull=True`）。下一行 `$resp.Content.ReadAsStringAsync()` 正好触发 `You cannot call a method on a null-valued expression.`，与二跑报错逐字一致。时间线也吻合：快照 07:43:35.365 发起，整棒 07:43:40.161 结束（≈4s 超时）。

**上一轮修复本身是有效的**（任务/context 都已生效，不是它的问题）：
- `stub.log:7`：`[req-003] tool_call tool=start_export route=TC16-EXPORT —— 命中路由 TC16-EXPORT`
- `instance-a.log:240`：`上下文=task:EXPORT/EXPORT 披露工具=[… start_export …]`（15 个）
- `wake-A.sse.txt`：`suspended`(seq2) → `tool_start`(seq3) → `confirm_request`(seq4)，`preflight` 四项全 `pass:true`，`taskId=1`
- `stub/req-003.tools.txt` 已含 `start_export`

## 修改（仍只改 `scripts/b23l1-dual-instance.ps1`）

1. **`Read-Sse` 空响应防御（`:246-262`）**：`.Result` 返回 `$null` 时按「没收到任何帧」收尾（与本函数读取循环的既有超时口径一致），写空证据文件（`wake-B-snapshot.sse.txt`）后返回空 `ArrayList`；不再进入 `$resp.Content...`。
2. **建任务响应防御（`:357-376`）**：`PostJson` 返回 null 或 `ok=false` 立即 FAIL 并打印响应体；`ConvertFrom-Json` 包 try/catch；`data`/`data.id` 逐层判空，取不到或 `≤0` 立即 FAIL 并打印**实际响应体前 400 字**；`select-defs` 同样先判 null/ok。语义仍是「先建任务再发消息」。
3. **后台长连接防御（`:427-428`）**：`$task.Result` 若为 null，直接抛 `B 侧 SSE 长连接未取得响应（<uri>）`，不再让作业内部变成空值方法调用。

## 验证

- 语法：`SYNTAX_OK`。
- **用脚本里真实的 `Read-Sse` 函数**（从文件抽取后 `Invoke-Expression`）对本地「接受连接、永不响应」的 mock 跑 1500ms 超时：修后 `RETURNED frames=0 null=False elapsedMs=1540`，空证据文件已写，无异常；同款 mock 修前就是上述空值方法调用。
- 建任务解析分支离线核对：`{"success":true,"data":{"id":7}}` → `7`；`{"success":true}` / 空体 → 走 FAIL 分支（打印体摘要）。
- `git diff --stat -- scripts/b23l1-dual-instance.ps1`：
  ```
   scripts/b23l1-dual-instance.ps1 | 184 +++++++++++++++++++++++++++++++++++-----
   1 file changed, 165 insertions(+), 19 deletions(-)
  ```
- **需要说明**：当前工作树里另有 5 个已修改 + 11 个未跟踪的 `backend/` 文件（`RunStore`/`LeaseService`/`PendingToolCall` 等，属并发的批②工作，不是我改的）。我本轮只 Edit 了 `scripts/b23l1-dual-instance.ps1` 这一个文件；单独对 `scripts/` 做 diff 仍只有该文件。另：8081/8082/18399 当前无监听，二跑的收尾这次看起来生效了（最终以主会话核验为准）。

## 复跑预期

快照会稳定耗 4s 后返回 0 帧（设计使然）→ 后台长连接走 TCP 就绪哨兵 → 批准确认 → wake 帧经 Redis 扇出到 B → 终帧 `done.seq > lastSeq`。未运行 harness，也未起停任何服务。
