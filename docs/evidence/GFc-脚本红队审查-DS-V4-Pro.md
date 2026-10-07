# GF-C 脚本红队审查（DeepSeek V4 Pro）——verify-e2e.ps1 GF 增量

> 角色：反方审查 / 红队。只审查、不改代码；本文件为唯一新增产物，未做任何 git 写操作。
> 审查对象：`<REPO_ROOT>/scripts/verify-e2e.ps1`（HEAD `d19da30`，工作区增量 +605/−5，GF-C 用例实现，作者 Kimi K2.8，与本审查异厂商）。
> 契约参照：`docs/evidence/GFc-E2E用例设计-GLM-5.3.md`（已定稿，含裁决）、`docs/evidence/GFa-生成式表单后端工具验证.md` §8。
> 证据等级：【实测】= 本次实际读码/静态核对后端源码确认；【推断】= 由代码路径推演、未实跑；【假设】= 需执行棒实测才能确认。
> 脱敏：`<REPO_ROOT>` 代替真实盘符；无密钥、无用户名。
> 执行边界：未运行完整 E2E；后端契约均以 `backend/src/main/java/...` 源码静态核对为准。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 问题总数 | **11**（P0 ×1 / 高 ×2 / 中 ×3 / 低 ×5） |
| 最高严重度 | **P0（致命，确定性）** |
| 总结论 | **打回** |

一句话：GF 增量的**确定性致命缺陷**集中在「`Gf-PendingEntry` 把 `ApiResponse` 信封当成裸数组解析」（全部 pending 断言必抛）+「`Read-Sse-Brief` 不是时间有界读」（GF4-A3 在挂起 run 上阻塞）+「GF5-A4 用 GF4 的陈旧 `lastSeq` 重放已见帧」（GF5 必误判）。这三处不修，GF1–GF5 在真实执行棒上**无法通过**。

---

## 1. 问题清单

### P0-1　`Gf-PendingEntry` 把 pending 端点响应当成裸数组解析 —— 全部 pending 断言确定性失败

- **严重度**：P0（致命，确定性）
- **位置**：`scripts/verify-e2e.ps1:1301-1309`（`function Gf-PendingEntry`）；依赖 `Invoke-Api`（`:73-88`）；设计稿 §1.2 与 §5.2 的错误契约背书。
- **证据**【实测】：
  - 后端 `AiController.java`（`<REPO_ROOT>/backend/.../ai/web/AiController.java`）的挂起快照端点签名是 `public ApiResponse<List<Map<String, Object>>> pending(...)`，返回 `ApiResponse.ok(items)`——即 `{"success":true,"data":[{...}]}` 信封，**不是裸数组**。
  - `Gf-PendingEntry` 的注释写「GET /api/ai/pending/{runId} 返回条目数组，非 ApiResponse 信封」，并直接 `$entry = @($pend.json | Where-Object { $_.toolCallId -eq $toolCallId })[0]`。但 `Invoke-Api` 只 `ConvertFrom-Json` 整个响应体，`$pend.json` 是**信封对象**（有 `success/message/code/data`，无 `toolCallId`），`Where-Object` 过滤永远命中 0 条。
  - 设计稿 §1.2「诊断端点 … → 条目数组」为错误契约，是本实现错误的源头（把 `data` 展开遗漏了）。
- **复现路径**：后端照常启动 → 跑 GF1 → A3（回灌 POST 200）通过 → A4 `Gf-PendingEntry` 抛「pending/{runId} 无 toolCallId=… 条目」→ GF1 FAIL。同理 GF2-A4、GF3-A4、GF4-A2、GF5-A3 全部抛同一错误。
- **修复建议**：`Gf-PendingEntry` 改为 `$pend.json.data` 取数组（或改走 `GetJson` 拿到 `.data` 后过滤）；并同步修正设计稿 §1.2 的表述为「返回 ApiResponse 信封，条目在 `data` 内」。

### 高-2　`Read-Sse-Brief` 不是时间有界读 —— GF4-A3「3.5s 无帧窗口」在挂起 run 上阻塞（潜在死锁）

- **严重度**：高
- **位置**：`scripts/verify-e2e.ps1:1271-1300`（`function Read-Sse-Brief`）；调用点 `:1649`（GF4-A3）、`:1702`（GF5-A4）。
- **证据**【实测（代码）+ 推断（运行时）】：
  - `$cts.CancelAfter($millis)` 只把 `$cts.Token` 传给了 `$http.SendAsync(..., ResponseHeadersRead, $cts.Token)`；`ResponseHeadersRead` 在收到响应头即返回，`SendAsync` 早已完成，取消令牌**不作用于后续的阻塞 `$reader.ReadLine()`**。
  - `while($true){ $line = $reader.ReadLine(); if ($null -eq $line) { break } ... }` 只有流关闭（EOF）才退出；外层 `catch { }` 吞掉一切异常。
  - 对**挂起态（PENDING）run**，`GET /api/ai/events/{runId}?lastSeq=N` 的 `replayAndAttach` 回放完后会「续接实时帧」，流保持打开不关闭（后端 `SseChatEmitter.replayAndAttach`/`AiController` reattach 语义【实测】）。若后端在挂起期间发心跳帧，`ReadLine` 会持续返回心跳行而永不退出，进一步放大阻塞【推断】。
  - GF4 的时序是 A3（读流 3.5s）**先于** A4（同 toolCallId 改值重发，唤醒 run）。挂起 run 在 A4 之前不会产生任何新帧、也不会关闭，因此 A3 会一直阻塞到 `app.ai.hitl.timeout`（120s）或 HttpClient 5min 超时——A4 永远不会到达，构成顺序性死锁【推断】。
- **复现路径**：GF4 违规 POST 得到 400 → A3 `Read-Sse-Brief(…, 3500)` → 阻塞（挂起 run 无新帧不关流）→ 用例挂在 A3。
- **修复建议**：用真正的时间有界读（对 `ReadLineAsync` 传 CancellationToken，或读循环内按 `Stopwatch` 判断截止并主动断开），且「无帧窗口」读必须能在超时后自行结束；或把 A4 的唤醒 POST 提前、A3 改为「先重挂读、窗口到期即断开」的独立可中断任务。

### 高-3　GF5-A4 用 GF4 的陈旧 `lastSeq` —— 重放已见 `frontend_tool_result`/`done` 帧，GF5 确定性误判「新帧」

- **严重度**：高（确定性）
- **位置**：`scripts/verify-e2e.ps1:1636`（`$script:gf4LastSeq = $lastSeqGf4`）与 `:1698-1707`（GF5-A4 重挂过滤）。
- **证据**【实测】：
  - `$lastSeqGf4 = $turn.lastSeq` 记录的是 `Invoke-FormTurn` 首次读流里最后一条帧的 seq，即 `frontend_tool_request` 的 seq（GF4 的 handler 收到该帧即 `return @{ break = $true }`，不再读后续帧）。
  - GF4-A5 的续跑 `Read-Sse "…?lastSeq=$lastSeqGf4"` 读到的 `frontend_tool_result`/`done` 只落进局部 `$cont`，**不回写 `$script:gf4LastSeq`**。故 `gf4LastSeq` 停留在 `frontend_tool_request` 的 seq。
  - GF5-A4 用 `?lastSeq=$script:gf4LastSeq` 重挂，后端 `replayLocked` 只回放 `seq > lastSeq` 的状态帧（`SseChatEmitter.replayLocked`【实测】），于是把 GF4 早已产生的 `frontend_tool_result` 与 `done` **当新帧重放**，命中 `$badFrames` 过滤（`:1703`），抛「409 后重挂出现新帧: frontend_tool_result」→ GF5 FAIL。
  - GF3 那一半正确（`$script:gf3LastSeq = $turn.lastSeq` 记录的是 `done` 的最终 seq，重放为空），故问题只在 GF4 半边。
- **复现路径**：GF4 通过后进入 GF5 → A1/A2/A3 通过 → A4 对 GF4 run 重挂 → 重放 `frontend_tool_result`+`done` → 抛「出现新帧」→ GF5 FAIL。
- **修复建议**：GF4 续跑读完后把最终 `lastSeq` 回写（如 `$script:gf4LastSeq = $lastSeqGf4` 在 A5 读流后用 `$cont` 里最大 seq 更新，或直接把 `done` 帧 seq 记下来）；或 GF5-A4 对「已决 run 重挂」只断言**无 `frontend_tool_request`** 而非全部状态帧（已决 run 重放结局帧属预期）。

### 中-4　schema 漂移的 SKIP/重试逻辑是死代码 —— 缺 key 直接 FAIL，且「schema 均漂移」分支不可达

- **严重度**：中
- **位置**：`scripts/verify-e2e.ps1:1339-1355`（`Gf-Parse-Form` 缺 key 分支）、`:1392-1404` / `:1472-1483` / `:1542-1552` / `:1622-1633`（各 GF 的 while 循环与 `$drift` 读取）。
- **证据**【实测】：
  - `Gf-Parse-Form` 仅在「缺字段」时 `Set-Variable -Name $driftVar -Scope Script …` 并 `return $null`；而 handler 只有在收到 `name=generative_form` 的 `frontend_tool_request` 帧后才会调 `Gf-Parse-Form`——即此时 `$fe.Count >= 1`。
  - 各 GF 的 while 循环在 `$fe.Count -ge 1` 时 `break`，于是 `$drift = $script:gfSchemaDrift` 这行**永远不被执行**，`$drift` 恒为 `$null`；`if ($drift) { throw "GF-SKIP:…schema 均漂移" }` 成为不可达死代码。
  - 实际后果：模型给出**缺 key 的合法 schema**（设计 §3.3 判定为「模型行为→重试→SKIP」）时，handler `return @{ break = $true }` 导致 `$script:gfPosts` 为空 → 断言走到 `$post` 为空抛「未执行回灌 POST」→ 首次尝试即 FAIL，**既不重试也不 SKIP**，且错误信息误导。
- **复现路径**：提示词故意让模型少给一个 key（或模型自然漂移）→ 该 GF 首次即 FAIL（报「未执行回灌 POST」），与设计 §3.3 的「重试 3 次→SKIP」完全不符。
- **修复建议**：把「缺 key（schema 漂移）」与「未触发表单」区分开：缺 key 时不 `break` 记本次漂移并继续下一轮重试，3 次都漂移才 SKIP；`$fe.Count -lt 1` 的 SKIP 分支与漂移分支的触发条件需按「是否收到过 `frontend_tool_request`」重写。

### 中-5　SKIP 判据缺「无 tool_start」前置校验 —— 后端缺陷可能被洗成 SKIP

- **严重度**：中
- **位置**：`scripts/verify-e2e.ps1:1399-1404`（及各 GF 同构的 `if ($fe.Count -lt 1)` SKIP 分支）。
- **证据**【推断】：
  - 设计 §3.3 判定表第 1 行的 SKIP 症状是「整轮无 `frontend_tool_request`，**也无 `tool_start(name=generative_form)`**」，并要求交叉取「披露端点 + 历史无工具卡片」。
  - 实现只判 `$fe.Count -lt 1`（`fe` = `frontend_tool_request` 且 name=generative_form），**不检查 `tool_start`、不检查历史、也不区分 REJECTED_ARGUMENTS**。
  - 若产品存在「工具已 `tool_start` 但前端挂起帧未下发/被吞」类缺陷（不产生 `frontend_tool_request`、也无 `REJECTED_ARGUMENTS`），会被判为「模型未触发」→ SKIP，把真 FAIL 洗掉。
- **复现路径**：构造/注入后端在 `generative_form` 调用后不下发 `frontend_tool_request` 的缺陷 → 脚本记 SKIP 而非 FAIL。
- **修复建议**：SKIP 前置补上「本轮帧中 `tool_start(name=generative_form)` 计数为 0」，存在 `tool_start` 但无 `frontend_tool_request` 且无 `REJECTED_ARGUMENTS` 时抛普通错误（FAIL）。

### 中-6　delta/回显断言依赖 reattach 后 live 读流，而 reattach 回放不含 delta —— A6/A7 存在竞态 flaky

- **严重度**：中
- **位置**：`scripts/verify-e2e.ps1:1423-1428`（GF1-A6）、`:1500`（GF2-A6）、`:1584`（GF3-A7）、`:1660-1661`（GF4-A5 echo4）；机制见 `Invoke-FormTurn:1197-1244` 与 `Read-Sse:205-250`。
- **证据**【实测（后端回放语义）+ 推断（竞态）】：
  - 后端 reattach 端点 `replayAndAttach` 硬编码 `replayLocked(emitter, false, lastSeq)`——回放**始终排除 delta/heartbeat**（`SseChatEmitter.replayLocked` 的 `includeDelta=false` 分支【实测】），delta 只存在于 live 流。
  - 脚本在收到 `frontend_tool_request` 时 `Read-Sse` 命中 stopOn 即断开（关闭客户端 SSE 连接），回灌 POST 后 `Start-Sleep 500` 再重挂。**回灌唤醒→重挂之间**模型产出的 delta 帧既不落客户端、也因「重放不含 delta」无法补回，永久丢失。
  - 若模型在该 500ms 窗口内（快模型/短回显）产完回显文本，A6/A7 收集到的 delta 为空 → 误判 FAIL。真实 LLM 首 token 延迟通常大于 500ms，故「大概率能过」，但属竞态，非确定性。
- **复现路径**：快速模型或短回显（如「keyword=E2EGFC\nscope=XN」单块产出）→ 回灌后 500ms 内 delta 全部产出 → 重挂读不到 → A6/A7 FAIL。
- **修复建议**：要么回灌时不关闭流（在读循环内边读 `frontend_tool_request` 边 POST、继续读同一条流），要么对回显断言改用「pending.resultText 原样等于回灌 JSON」（服务端已存，确定性）+ 把 delta 回显降级为弱旁证；或至少在报告里把 A6/A7 标记为「可能受模型速度影响」。

### 低-7　GF2-A6 无唯一合成 token —— 值回显子串断言可被「复述提示词」满足

- **严重度**：低
- **位置**：`scripts/verify-e2e.ps1:1500`（GF2-A6：`@('ALL', 'includeInactive=')`）。
- **证据**【实测】：
  - GF2 提示词原文含「选项 ALL 和 SELECTED」，故 `ALL` 子串即使模型**未收到回灌值**、仅复述 schema 也会命中；唯一略具区分度的是 `includeInactive=`（要求 `key=` 形态），但仍可被遵从「按 key=值 回报」指令、却未真正收到值的模型凑出。
  - 对比 GF1/GF4 有 `E2EGFC`（合成值，不在提示词内）作真锚点，GF2 没有等价唯一 token。
- **复现路径**：模型复述字段说明而不等待表单结果 → GF2-A6 仍可能通过（弱化「值回显」证据力）。
- **修复建议**：GF2 也引入不在提示词内的合成值（如 `exportScope` 取一个提示词未点名的选项值，或给 boolean 一个非默认值），或用 `pending.resultText` 精确相等作为主锚点。

### 低-8　`timeoutSeconds` 硬编码 120 —— 与可配置 `app.ai.hitl.timeout` 耦合

- **严重度**：低
- **位置**：`scripts/verify-e2e.ps1:1330`（`Gf-Assert-FrameContract` 的 `[int]$f.timeoutSeconds -ne 120`）。
- **证据**【推断】：后端 `frontendToolRequest(pending, timeoutSeconds)` 的 `timeoutSeconds` 来自 `app.ai.hitl.timeout`（默认 120s，`AiProperties`）。若执行棒以非默认超时启动，GF1/GF2/GF3/GF4 的 A1 帧契约断言会误 FAIL。当前启动约定不改该项，故低。
- **修复建议**：断言改为 `> 0` 或与后端配置对齐的参数化值，而非硬编码 120。

### 低-9　`Invoke-AiTurn` 兜底的两处潜在脆弱（当前不可达）

- **严重度**：低
- **位置**：`scripts/verify-e2e.ps1:291-302`。
- **证据**【推断】：
  - `$act -is [hashtable]`：若处理器返回 `[ordered]@{}`（`OrderedDictionary`），`-is [hashtable]` 为 `$false`，`$formSpecific` 恒假，兜底会把「显式 cancelled/自定义 result」的表单专用载荷误当通用值而强行 `cancelled:true` 收敛。当前 5 个调用方均返回普通 `@{}`，故仅潜在。
  - 兜底用 `PostJsonRaw`（非 2xx 即 throw）：若模型自发调用 `generative_form` 时条目恰已超时/已决（409），`PostJsonRaw` 抛异常会把既有用例转 FAIL，而非静默收敛。属收敛路径的健壮性缺口。
- **复现路径**：需模型在既有用例中自发调表单（当前不可达，设计已登记为 O3）。
- **修复建议**：`-is [System.Collections.IDictionary]` 更稳；收敛 POST 改走 `Invoke-Api` 并按 409/已决判定是否仍算成功收敛。

### 低-10　GF6 依赖 GF1 `try` 块内定义的 `$promptGf1`（脆弱耦合）

- **严重度**：低
- **位置**：`scripts/verify-e2e.ps1:1733`（`$promptGf1 + '为了演示安全闸…'`）。
- **证据**【实测】：`$promptGf1` 是 GF1 `try{}` 首行的脚本级变量，GF6 在 `-EnableGf6` 下引用它；由于 GF1 恒先于 GF6 且首行必执行，实际可用，但跨用例的隐式依赖易在后续重构中折断。
- **修复建议**：GF6 用独立常量或显式重写提示词，消除对 GF1 局部变量的依赖。

### 低-11　裁决 #2 把 TC22 列为「受扰用例」过度计数

- **严重度**：低（信息性，非行为缺陷）
- **位置**：设计稿裁决结论 #2 与 §5.3。
- **证据**【实测】：TC22（`:1924-1938`）只 `GetJson /api/ai/history/{aiSession15}`，**不调用 `Invoke-AiTurn`**；`Invoke-AiTurn` 兜底变更对 TC22 无直接作用（TC22 依赖的 `aiSession15` 由 TC15 写入，而 TC15 用 `continueHandler` 且不触发 generative_form）。受扰用例实为 TC15/TC16/TC17/TC18 四处调用点。
- **修复建议**：裁决/设计文档把 TC22 从受扰清单更正（或注明「仅经由 TC15 会话间接受影响」），避免执行棒验收时误以为 TC22 也走该路径。

---

## 2. 作者报告的四条实现取舍逐条裁定

| # | 取舍 | 裁定 | 依据 |
|---|---|---|---|
| ① | GF4 回调 `return @{ break = $true }` 手动时序（把回灌动作外置到用例块，用 `Expect-Fail`/`Invoke-Api` 拿原始 400/200） | **方向正确，但引入副作用** | 设计 §5.2「回灌动作外置」与 GF4 需要原始 400/409 响应的诉求成立，`break` 手动时序本身可工作。但直接导致 `gf4LastSeq` 停留在 `frontend_tool_request` seq、且续跑读流不回写 seq，触发问题 #3（GF5-A4 确定性误判）。需在 break 后同步维护最终 seq。 |
| ② | `New-FormValues` 的 `$prefer`/`$includeOptional` 参数 | **合理且必要，实现基本正确** | `$includeOptional` 保证「可选字段」也被回灌以便回显可断言（GF1 scope、GF2 includeInactive），`$prefer` 把回显收紧到提示词固定值，均必要。PS 5.1 侧 `[ordered]@{}` 走 `ConvertTo-Json` 产出对象外壳正确【实测：设计 §1.4 已锁定】。小瑕疵：enum/multi_select 分支 `$field.options[0].value` 对空 `options` 会产出 null 值（低）。 |
| ③ | GF5-A4 对 heartbeat 放行（只过滤业务帧） | **意图正确，当前无法达成目的** | heartbeat 确非业务帧、应放行，过滤口径（`frontend_tool_request/result/done/error`）本身没问题。但 `Read-Sse-Brief` 无时间上界（#2），且 GF4 半边陈旧 lastSeq（#3）使重放帧误命中过滤，A4 目前既不能可靠「读到空」也不能正确「排除 heartbeat」。 |
| ④ | GF2 fixture 失败残留靠预清理兜底（用例内自删 + 预清理追加段） | **合理，无碰撞** | `GFC-*` 前缀与既有 `S5B-*` 命名无交集【实测：预清理 :434-444 与既有 :420-432 并存】，双保险满足幂等重跑；`size=500` 分页上限为既有口径，非新增问题。GF2 用例内 `DeleteJson` 即时清理先例亦成立。 |

---

## 3. 对既有 22 用例扰动面的核对（对应审查清单 #2/#3）

- **`Invoke-AiTurn` 兜底（裁决 #2）**：判据 `$s.name -eq 'generative_form' && !$formSpecific` 正确落在「处理器返回通用值」的危险路径；`$onSuspend -eq $null` 的 `break` 路径（`:280`）未被触碰【实测：兜底分支在 else 内，前置 `if (-not $onSuspend) { break }` 不变】。逐个过 TC15（continueHandler）、TC16（handler16/handler16b）、TC17（handler17 ×2）、TC18（continueHandler）五处调用：其 frontend 帧 name 均非 `generative_form`（list_config_defs 为普通工具、工作区动作非表单），兜底分支不可达，**零行为变化**【实测】。
- **预清理 GFC 段**：`GFC-*` 与既有 `S5B-*` 无碰撞，不误删既有 fixture【实测】。
- **摘要/退出码**：`exit 1` 由 `failed>0` 扩展为 `failed>0 || gfskip14>=4`；既有运行中 `gfskip14=0`，语义不变；打印文案改为「22 既有 + GF 新增」仅改文案【实测】。

---

## 4. 总结论

**打回。**

GF 增量的框架（`Invoke-FormTurn` 蓝本、`Invoke-AiTurn` 兜底、`New-FormValues`、`Gf-Converge`、SKIP 记账与硬底线）整体方向与设计契约一致，兜底加固对既有 22 用例零扰动——这部分成立。但存在 **1 个确定性致命缺陷（pending 信封解析）** 与 **2 个高危缺陷（Read-Sse-Brief 无时间上界、GF5-A4 陈旧 lastSeq）**，三者叠加足以让 GF1–GF5 在真实执行棒上无法通过；另有 schema 漂移 SKIP 逻辑死代码、SKIP 判据缺 `tool_start` 前置、delta 回显竞态等中危问题。修复 P0-1、高-2、高-3 三项后可达到「放行带修复项」，中危项（#4/#5/#6）建议同轮修掉后再行全量取证。

---

## 附：证据标注汇总

- 【实测】：P0-1、高-3、中-4、低-7、低-10、低-11、§3 扰动面核对、取舍裁定（读后端源码/静态控制流/逐行核对脚本）。
- 【实测 + 推断】：高-2（代码结构实测、运行时阻塞/死锁为推断）、中-6（后端回放语义实测、竞态为推断）。
- 【推断】：中-5、低-8、低-9。
