<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-023 · 施工复核 · 总账（0c4dbfa1 / agent-82）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m95` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt73` 条有正文的 assistant 消息（共 123 条有正文），末条有正文消息为 `m198`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `agent-82`（`main` = 主会话）；原字符 4,639（链内 1 段、合计 4,639）。
> **链号 / 类别**：`MT-023` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 8 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `12%`。
> **与末条归档（138 件）的关系**：末条 `m198`（4,184 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

E2E 全量照跑完成。**既有 22 用例全绿（零扰动），GF3/GF5 PASS，GF1/GF2/GF4 FAIL —— 三个 FAIL 全部落在同一处"值回显"弱旁证断言上，链路主锚点全通，判定为测试侧假阴性。**

## 总账

```
 结果：PASS=24  FAIL=3  （22 既有 + GF 新增）
 GFSKIP=0  （模型未触发 SKIP，不计退出码；GF1–GF4 全部 SKIP 判整棒 FAIL，裁决 #1）
 Q8 项：PASS=2  FAIL=0
E2E_EXIT=1
```

- **既有 22 用例（TC1–TC22）：PASS=22 / FAIL=0**，Q8 两项 PASS=2/FAIL=0。
- **GF1 FAIL**（`值回显缺 'E2EGFC'`）、**GF2 FAIL**（`值回显缺 'includeInactive='`）、**GF3 PASS**、**GF4 FAIL**（`续跑值回显缺 E2EGFC`）、**GF5 PASS**。
- **GFSKIP=0，硬底线未触发**：GF1–GF4 全部在**首轮尝试**即收到 `frontend_tool_request`（日志 4 个 `gfc-` 轮次均为"本轮第 1 次尝试组装请求"，无换会话重试、无 schema 漂移）。退出码 1 的成因是 3 个 A6 FAIL，**不是** SKIP 底线。

## 验收点逐条

**1. 22 零扰动 + Q8** ✅ 实测全绿（详见文档 §2.1/§5）。GF 块插在 TC16 与 TC17 之间，未影响任何既有断言；TC19 演示数据恢复 5 行、预清理（GF）残留 0 个。

**2. GF 逐例 + GFSKIP + 硬底线** ✅ 见上。`GFSKIP=0`，硬底线（GF1–GF4 全 SKIP）未触发。

**3. SKIP 归因** — 本轮**无 SKIP 对象**（4/4 首轮触发），设计稿 §3.3 判定表未被触发。反向事实两组都成立：披露端点含 `generative_form`（GF1 前置断言 + TC15 独立断言 `page:tasks` 13 个工具），模型侧也确实触发。

**4. 两条残留实证**
- **`Read-Sse-Brief` 无意外阻塞** ✅ GF 块整体仅 **18 秒**（19:40:42 → TC17 首轮 19:41:00），整轮 **93 秒**（19:40:01→19:41:34，上一棒纯 22 例约 70 秒，增量约 23 秒）。GF4 的"拒绝→3s 窗口→改值重发"链条时间戳 `19:40:54.072` → `19:40:57.641`，窗口按设计时长收束，未等到 120s/180s。
- **无 catch 吞错** ✅ 三例失败全部外显为带原因的 `[FAIL]`；新增代码内仅三处兜底 `catch`，均为窄口径（窗口读 / 已记 FAIL 后的收尾收敛 / JSON 解析兜底且紧随显式 throw），无一掩盖主错误。**但发现一处隐患已登记为 O1**（见下）。

**5. 既有用例是否自发调用 `generative_form`** — 落在**"0 次"**这一态 ✅ 全日志 22 处 `generative_form` 命中逐条归类后，全部属于启动注册(1) + 披露清单(DEBUG) + **4 个 `gfc-` 轮次**；**TC15/TC16/TC17/TC18 的 `s5b-*` 会话无任何一次表单 `tool_start`/`frontend_tool_request`/闸门行**。故设计稿 §5.3 的 `cancelled` 兜底分支**未被触发**（也未出现"被兜底收敛"的第二态）。

## 关键证据摘录

**根因定位（决定性）**：三例失败信息是该弱断言的唯一抛出文本，而脚本为**顺序断言**——A6 位于 A3/A4/A5 之后，能走到它就说明**帧契约、回灌 200/`FRONTEND_RESULT`、`pending.resultText` 与回灌 JSON 精确相等、结局帧恰 1 条且 `done` 恰 1 条全部通过**。

链路正确性的独立佐证（会话记忆原文）：

| 会话 | msg1(ASSISTANT) | msg2(TOOL) responseData | msg3(ASSISTANT) 回显 |
|---|---|---|---|
| GF1 | `text=""` + toolCall(generative_form) | `{"keyword":"E2EGFC","minRows":1,"effectiveDate":"2026-10-07","scope":"XN"}` | 含 `- keyword=E2EGFC` / `scope=XN` |
| GF2 | 同上 | `{"exportScope":"ALL","includeInactive":true}` | 含 `exportScope=ALL` / `includeInactive=true` |
| GF4 | 同上 | `{"keyword":"E2EGFC","scope":"XN"}` | 含 `keyword=E2EGFC` / `scope=XN` |

`msg1.text=""` 是关键**排除性证据**：模型挂起前没有任何文本 ⇒ 挂起前无 delta；而捕获到的 delta 非空却不含所填值 ⇒ **捕获到的是续跑后那条消息的不完整片段**（值位于文本靠前处）。机制上：脚本"命中 stopOn 断开原流 → POST → sleep 500ms → 重挂续读"，间隙内后端把续跑帧写给了**已放弃的流**（日志实证 `SSE 写出失败（客户端断开？）… type=frontend_tool_result`），而 reattach 回放**不含 delta** ⇒ 间隙内 delta 永久丢失。对照 GF3 只要求"delta 至少 1 条"故 PASS ✅ 与根因一致。

其他取证：GF3 取消 `seq5 frontend_tool_result ok=false status=FRONTEND_CANCELLED`、`pending` 终态 `reason=FRONTEND_DISMISSED`、日志 `前端工具被用户取消 runId=8ffa313b… name=generative_form`；GF4 拒绝 reasons **恰 4 条**（与裁决 #3 推演一致）+ WARN `code=FORM_RESULT_REJECTED`；GF4-A3 与 GF5-A4 的"无新帧"有服务端背书（`reattach … status=SUSPENDED 回放帧数=0`、`… status=DONE 回放帧数=0`），**非空转**。GF5 本棒另独立复现：值路径/取消路径均 **409 `DUPLICATE_TOOL_CALL_ID`**、`duplicate=true`、回显既有终态不改写。

## 文档路径

`docs/evidence/GFc-端到端执行验证.md`（UTF-8 中文，**保持未提交**）。结构：结论速览 → 环境与命令（脱敏，含"复用 jar"的两条可机械复核依据：`git diff --name-only <上次构建HEAD>..HEAD -- backend/` 为空 + 无 `backend/src` 文件比 jar 新）→ 逐用例结果表 → 逐个取证（帧/HTTP 码/日志 + 附件占位路径）→ **3 个 FAIL 的根因判定** → 22 零扰动论证 → SKIP 归因 → 残留观察 O1–O5 → **【待裁决】5 项** → 附件与清理。附件 16 个全部落仓库外 `<TMP>`（脚本 stdout、后端日志、日志摘录、`gfc-GF{1..4}.sse.txt`/`history.json`/`pending.json`、GF5 两次 409 原始响应），文中以 `<TMP>/…` 占位引用。

**【待裁决】核心一项**：GF1/GF2/GF4 的 A6 建议判为**测试侧假阴性**，处置三选一——(a) 删除 A6（主锚点 A4 已足够）／(b) 降级为非致命／(c) **改用 `GET /api/ai/history/{sessionId}` 做值回显断言（该通道本轮已实证含全部所填值，不受 delta 丢失影响）**。修复后重跑预期 `PASS=27 FAIL=0`。

**另发现一处隐患（O1，本轮未发生）**：`Read-Sse-Brief` 的兜底 `catch { }` 会把 `if (-not $resp.IsSuccessStatusCode) { throw … }` 一并吞掉，使 GF4-A3/GF5-A4 的"无新帧"断言在重挂失败时空转恒真；本轮后端日志证明两处窗口读取都到达服务端，故断言有效。**O4**：脚本自身不落帧文件（`grep 'sse\.txt|http\.txt|Out-File'` 零命中），与设计稿 §6 归档建议有偏差，本棒以"跑后只读回放 + 会话记忆 + pending 快照 + 后端日志"四通道补齐并注明回放帧不含 delta 的边界。

## 自证与清理

后端（PID 24332，端口 18330）已 `taskkill /F` 停止，端口已回收；`backend/data/e2e_gfc_db.mv.db` 已删，演示库 `config_mgr_db.mv.db` mtime 仍 13:48 未变；`data/files` 无本轮窗口（19:38 后）新文件；仅删本轮的 `%TEMP%/s5b-e2e-…` 目录（未再波及历史目录）。密钥仅经环境变量注入，未落任何文件。

```
$ git status --porcelain
 M scripts/verify-e2e.ps1                                      ← 被验对象（既有改动）
?? docs/evidence/GFc-E2E用例设计-GLM-5.3.md                    ← 既有（设计稿）
?? docs/evidence/GFc-端到端执行验证.md                          ← 本棒新增（唯一）
?? docs/evidence/GFc-脚本红队审查-DS-V4-Pro.md                  ← 既有（红队稿）
$ git status -sb
## <OLD_REPO_DIR>...<OLD_REPO_DIR>        （HEAD = d19da30，与远端同步）
```

脚本与 docs 之外**无任何新改动**，本棒**零 git 写操作**。文档脱敏已自查通过（无盘符 / 密钥 / 用户名 / 主机名；`grep` 仅命中刻意掩码的 `AI_API_KEY=***`）。
