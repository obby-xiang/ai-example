> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-91` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：11574 B / 8055 字符 / 93 行；行指纹覆盖率（仓内 + 落盘语料）4.5%
> - 类别：**施工复核**
> - 脱敏：路径 2 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# GF-C E2E 脚本修复轮静态复验（14 项）

复验对象：`<REPO_ROOT>`（分支 `<OLD_REPO_DIR>`，HEAD `d19da30`），工作区 `scripts/verify-e2e.ps1`（2094 行，HEAD 1421 行，净 +673）。
边界遵守：只做只读+解析检查；未运行 E2E，未改文件，未做 git 写操作（只用了 `status/diff/show/fsck/stash list`）。行号除特别标注外均为**工作区当前行号**。

---

## 逐项结论

### 1. P0-1 pending 信封解析 —— PASS
- `scripts/verify-e2e.ps1:1312-1318` `Gf-PendingEntry`：`1314` `if (-not $pend.ok -or -not $pend.json -or -not $pend.json.data) { throw … }`；`1315` `$entry = @($pend.json.data | Where-Object { $_.toolCallId -eq $toolCallId })[0]`。
- `1322-1332` `Gf-Converge`：`1326` 校验 `.data`、`1327` 同样取 `$pend.json.data`；`1329` 收敛 POST 仍走 `Invoke-Api`（409/失败不抛，函数内 `catch { }` 收尾）。
- 全脚本 grep `pend\.json` 仅 4 处命中，全部是 `$pend.json.data`，**无裸 `$pend.json |`** 残留。
- 契约核对（后端源码）：`backend/.../ai/web/AiController.java:558-565` `public ApiResponse<List<Map<String,Object>>> pending(...)` → `ApiResponse.ok(items)`（信封，条目在 `data`）；`backend/.../ai/run/PendingToolCall.java:111-128` 的 `asMap()` 确有脚本断言的 `toolCallId/status/resultText/reason/executed`。修复方向与契约一致。

### 2. 高-2 Read-Sse-Brief 时间有界 —— PASS（附 1 条非阻断残留）
- `1276-1308`：`1278` `$sw = [Stopwatch]::StartNew()`；`1289` `while ($sw.ElapsedMilliseconds -lt $millis)`（窗口为界）；`1290-1291` 剩余量守卫；`1292-1293` `$readTask = $reader.ReadLineAsync()` + `if (-not $readTask.Wait($remaining)) { break }  # 窗口到点：主动断开`；`1295` EOF break；`1305` `finally { $reader.Dispose(); $resp.Dispose(); $cts.Dispose() }`。挂起 run 只发心跳不关流的场景不再能阻塞——**原无限阻塞路径结构上已消除**。
- 残留（非阻断）：`1306` 的裸 `catch { }` 仍在，会把 `1286` 的 `throw "SSE … => HTTP …"` 和读故障一并吞掉，返回空帧集。两个调用点（`1706` GF4-A3、`1773` GF5-A4）都是「期望无帧」断言，故请求本身失败时会**真空通过**。建议至少落一行 WARN 或返回可区分的哨兵。另外 `Wait($remaining)` 超时后仍有挂起的 `ReadLineAsync`，紧随的 `Dispose()` 预期会中断连接（静态无法证明其必然不阻塞），建议执行棒顺带确认。

### 3. 高-3 GF4 续跑 seq 回写 —— PASS
- `1693` `$script:gf4LastSeq = $lastSeqGf4`；`1717-1718` 续跑 `Read-Sse "…?lastSeq=$lastSeqGf4" … @('done','error') $cont`；`1721` `foreach ($cf in $cont) { if ($cf.seq -and [int]$cf.seq -gt $script:gf4LastSeq) { $script:gf4LastSeq = [int]$cf.seq } }`。
- `1772` GF5-A4 消费：`foreach ($pair in @(@($script:gf4Run, $script:gf4LastSeq), @($script:gf3Run, $script:gf3LastSeq)))`。
- 后端核对：`SseChatEmitter.emit`（`run/SseChatEmitter.java:425-444`）对所有**非心跳**业务帧 `frame.put("seq", nextSeq())` 并落归档 → `done` 也带 seq，回写即最终 seq；`replayLocked` 只回放 `seq > lastSeq`，故 GF5-A4 重放为空。

### 4. 中-4 漂移判定提前 + 重试 + SKIP 记账 —— PASS
- 顺序：GF1 `1412`（漂移分支，`$driftCount++; … continue`）→ `1413` `if ($fe.Count -ge 1)`；GF2 `1505/1506`、GF3 `1587/1588`、GF4 `1679/1680` 同构。
- 3 次后：`1422` `throw "GF-SKIP:模型 $driftCount/3 次尝试 schema 漂移（最近：$drift），判模型行为"`，否则 `1423` `throw 'GF-SKIP:模型 3 次尝试均未触发表单（披露正常），判模型行为'`（GF2/GF3/GF4 对应 `1515/1516`、`1597/1598`、`1689/1690`）。
- 死代码消除：grep `schema 均漂移` = 0 命中；写 `$driftVar` 的仅 `Gf-Parse-Form:1361`，读 `$script:gfSchemaDrift` 全在循环内（每轮 `1408/1501/1583/1675` 先置 `$null`）。

### 5. 中-5 SKIP 前置校验 —— PASS
- `1416-1418`：`$toolStartN`（`type='tool_start' -and name='generative_form'`）、`$rejN`（`frontend_tool_result` + name + `status='REJECTED_ARGUMENTS'`），`if ($toolStartN -gt 0 -and $rejN -lt 1) { throw "模型已 tool_start(generative_form) 但未收到 frontend_tool_request（亦无 REJECTED_ARGUMENTS 结局帧）——挂起帧未下发，产品缺陷" }`。
- 该 throw **无 `GF-SKIP:` 前缀** → 走 `catch` 的 else 分支进 `No`（FAIL），不会被洗成 SKIP。GF2/GF3/GF4 同构：`1509-1511`、`1591-1593`、`1683-1685`。
- 判据有效性核对：`tool_start` 帧带 `name`（`SseChatEmitter.java:216-221`），`frontend_tool_result` 帧带 `name/ok/status`（`SseChatEmitter.java:277-287`）→ 两个 filter 都能命中，无假 FAIL；且检查位于 `$fe.Count≥1` break 之后，正常路径不会误判。

### 6. 中-6 主锚点 resultText 精确相等 + delta 降级 —— PASS
- 主锚点：GF1 `1436` `if ($entry.resultText -ne $script:gf1Posted)`；GF2 `1527`；GF4 新增 A4b `1714-1715` `if ($entryG4.resultText -ne '{"keyword":"E2EGFC","scope":"XN"}')`。
- delta 弱旁证（`if ($echo) {…} else { INFO }`）：GF1 `1445-1451`、GF2 `1535-1540`、GF3 `1632-1633`（仅 INFO）、GF4 `1729-1734` —— 收集不到不 FAIL。

### 7. 低-7 GF2 ALL 子串断言移除 —— PASS
- `1535-1540`：仅 `1537` `if ($echo -notlike '*includeInactive=*')`；`1533` 注释说明移除理由。文件中 `'ALL'` 仅剩 `1487` 的 `$prefer` 取值（回灌输入值，非断言）。

### 8. 低-8 timeoutSeconds 断言 —— PASS
- `1345` `if ([int]$f.timeoutSeconds -le 0) { throw "… timeoutSeconds=$($f.timeoutSeconds)，期望 > 0（不硬编码 120…）" }`；grep `timeoutSeconds` 全脚本仅此 1 处断言，无 `-ne 120` 残留。

### 9. 低-9 Invoke-AiTurn 兜底 —— PASS
- `291-303`：`292` `if ($s.name -eq 'generative_form' -and $act -is [System.Collections.IDictionary])`；`293-294` `$act.Keys -contains 'cancelled'` / `-contains 'result'`；`297-299` 收敛分支 `$converged = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ … cancelled = $true … }` + `if (-not $converged.ok -and $converged.status -ne 409) { throw … }`。
- `Invoke-Api`（`73-88`）不做 `EnsureSuccessStatusCode`，非 2xx 只回 `{status,ok,text,json}` → 409 不 throw ✅。
- 逐字节核对：把 `function Invoke-AiTurn` 分别从 `git show HEAD:scripts/verify-e2e.ps1` 与工作区抽出后 diff，**只有一个 hunk = else 分支**，HEAD 的两行（`$res = …`、`PostJsonRaw …`）原样保留在新 `else`（`301-302`），函数其余部分零差异 ✅。

### 10. 低-10 GF6 独立提示词 —— PASS
- `$promptGf1` 仅出现在 `1381`（定义）、`1409`（GF1 调用）以及 `1804` 的注释里；GF6 自带 `$promptGf6`（`1805` 定义，`1806` 调用），未引用 `$promptGf1`。

### 11. 取舍② New-FormValues 空 options 跳过 —— PASS
- `1252` `if (@('enum','multi_select') -contains $field.type -and @($field.options).Count -lt 1) { continue }`，位于 `switch`（`1253`）之前，`$field.options[0].value`（`1258/1262`）不再可能对空 options 产出 null；`@($null).Count` 为 0，缺 `options` 属性同样跳过。

### 12. [Parser]::ParseFile 零错误 —— PASS（实跑解析）
`powershell.exe -NoProfile -Command "[Parser]::ParseFile('…\scripts\verify-e2e.ps1',[ref]$toks,[ref]$errs)"` → **`PARSE_ERRORS=0`**，`AST_FUNCTIONS=36`。

### 13. git diff HEAD 全量 hunk 清单 —— PASS（可证部分全过；逐字节一致性因无快照只能佐证）
`git diff HEAD -- scripts/verify-e2e.ps1`：**1 file changed, 678 insertions(+), 5 deletions(-)**，hunk 共 7 个（旧行区间 / 新行区间 / 内容）：

| # | hunk | 内容 | 是否触及 TC1–TC22 |
|---|---|---|---|
| 1 | `@@ -38 +38,2 @@` | param 增 `[switch]$EnableGf6` | 否（TC1 在 HEAD:417） |
| 2 | `@@ -46,0 +48,2 @@` | `$script:gfskip` / `$script:gfskip14` 计数器 | 否 |
| 3 | `@@ -283,2 +286,18 @@` | Invoke-AiTurn else 兜底分支（2→18 行） | 否（函数体内） |
| 4 | `@@ -415,0 +435,12 @@` | 预清理（GF）`GFC-*` 段 | 否（插在 HEAD:416 前） |
| 5 | `@@ -1152,0 +1184,640 @@` | GF1–GF6 整块（**纯插入，0 删除**，落在 HEAD:1152 = TC16 收尾 `catch` 与 HEAD:1154 = TC17 分节注释之间） | 否 |
| 6 | `@@ -1418 +2089,2 @@` | 摘要 `（22 既有 + GF 新增）` + 新增 `GFSKIP={0}` 行 | 否（Q8 收尾之后） |
| 7 | `@@ -1421 +2093,2 @@` | `exit 1` 条件扩为 `failed>0 -or gfskip14>=4`（`2094`） | 否 |

- **既有 22 用例块零 hunk**：可证——5 处删除行全部落在 param(1)、Invoke-AiTurn(2)、摘要(1+1)，其余 hunk 为纯插入；插入点分别在 TC1 之前、TC16 与 TC17 之间；TC1 注释 HEAD:417 → 现 `448`，TC17 现 `1825`，TC22 现 `1993-2011`，均无改动。
- **预清理/摘要/param/计数器 4 个 hunk 与上一棒一致**：内容与红队 §3 对上一棒的记载逐字吻合（`GFC-*` 前缀 + `size=500`；`（22 既有 + GF 新增）`；`$script:failed -gt 0 -or $script:gfskip14 -ge 4`）；行号对齐也支持不变——红队上一棒引用的**未改区**行号与当前完全一致（`Invoke-Api` 73-88、`Read-Sse` 205-250、`if (-not $onSuspend) { break }` 280、既有预清理 `Say` 432），且本修复轮全部 73 行净增都在 TC22 块之前（红队上一棒 TC22 记 1924 → 现 1997 = 1924+73，与前缀 hunk 1+2+3+4 = +31 自洽）。
- 明确的证据边界：**不存在上一棒的逐字节快照**——`git stash list` 为空；`git fsck --unreachable` 的 23 个 blob 无一含 `Gf-PendingEntry`；工作区无 `.bak`/副本（`grep -rl Gf-PendingEntry <LOCAL_WORK> --include=*.ps1/.bak/.txt` 仅命中本文件）。故「与上一棒一致」属内容+行号对齐的**佐证**，非字节级证明。另注：红队自身对 GF 预清理段的引用 `:434-444` 与实际的 `435-445` 差 1 行（引用噪声）。

### 14. git status --porcelain —— PASS
实跑输出恰为预期三条（CJK 名被 git 转义，解码后即所列两文档）：
```
 M scripts/verify-e2e.ps1
?? docs/evidence/GFc-E2E用例设计-GLM-5.3.md
?? docs/evidence/GFc-脚本红队审查-DS-V4-Pro.md
```
无其他改动或未跟踪文件。附注：工作区文件为 LF（`grep -c $'\r'` = 0）而 `core.autocrlf=true`，git 仅提示「提交时转 CRLF」，未造成行尾级 diff 噪声（hunk 仍为 7 个）。

---

## 非阻断残留（供执行棒留意，不影响本轮放行）
1. `Read-Sse-Brief:1306` 裸 `catch { }` 会吞掉 `1286` 的 HTTP 错误 → 重挂失败时 GF4-A3/GF5-A4 会真空通过（健全性缺口，非活性缺陷）；建议加 WARN 或哨兵。
2. `Wait($remaining)` 超时后 `Dispose()` 对挂起读的中断行为属运行时性质，静态不可证；建议执行棒顺带确认无额外阻塞。
3. PowerShell 集合展开细节：`Read-Sse-Brief` 空 `ArrayList` 会展开成 `$null`、单帧会展开成裸对象；两处调用都用 `@(...)` 包裹，无实际影响，仅备案以免后续误判。
4. GF1–GF4 若模型自己连出 3 次非法 schema（REJECTED_ARGUMENTS），SKIP 文案会显示「未触发表单（披露正常）」，措辞略不精确，行为与记账正确。

**总结论：14 项全部 PASS（第 13 项的「与上一棒逐字节一致」因无快照，为内容+行号对齐佐证而非字节级证明）——11 个红队问题的修复真实落地且与后端契约一致，未触碰既有 22 个 TC 用例块，工作区状态与预期一致，可放行进入执行棒实测。**
