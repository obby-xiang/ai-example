> 来源=agent-ql5j7t06/output.log（2026-10-26 归档）· 全面代码审查（自动续传三档边界、F1–F8 与外部断言核验）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

## 一、结论先行

### 1. 三档边界（代码事实，非建议）

| 边界 | 现状 | 是否自动 | 证据 |
|---|---|---|---|
| **安全的自动续传**（可以自动进行，不应让用户处理） | ① 首包前失败（连接、DNS、5xx、429、首包 30s 超时、空流缺终帧）自动重试 1 次；② 前端断流后自动重挂（4 次退避 1/2/4/8s ±25%，建连 15s + 帧间 45s 双看门狗）；③ 刷新后静默续收（RUNNING 全量重放、SUSPENDED 差量续收）；④ 人工等待（park）决策后自动重建执行、派发退避 15 次；⑤ 启动扫描自动续跑 + 僵尸接管；⑥ 重挂时"已决无驱动方"自愈对账 | 已有 | `ResilientChatService.java:833`、`sse.ts:39/73`、`ai.ts:2224-2256/2440-2500`、`AiController.java:776-801` |
| **只能整轮重跑（当前只剩人工）** | 中途断流（已产出正文或思考）、工具已执行后失败、超预算、内容过滤、空回复 → 快照 `FAILED`，前端出错误卡 + 「继续」按钮，用户手点后发送一条新的用户消息 | 无 | `ResilientChatService.java:826-833 / 1757-1779`、`AiPanel.vue:3201-3221` |
| **必须由人决定** | 上下文越窗、鉴权失败、4xx 拒绝、provider 被删、内容过滤、取消后仍待决的卡 | 无（有意为之） | `UpstreamErrorClassifier.java:148-163`、`ResumeService.java:749-755 / 777-810` |

### 2. 四个直接问题

**① 已有 partial text 能否自动续写/替换？不能，而且两处不支持。**
- 后端从未在"已产出"后重试：`emitted = accumulator.emittedAny()`（`emittedAny` 把 **reasoning 也算已产出**，`ResilientChatService.java:2285-2289`），闸门是 `outcome.retryable() && !emitted && !sideEffect && hasNext && !overBudget`（`:833`）。
- 失败终局**不落半截**：`fail()` 只写 `FAILED` + 收尾正文段 + `error` 帧，既不 `convergeMemory`，也不把半截写进快照（`:1757-1779`）。对比取消路径：`cancelTerminalLocked` 会把半截 + 配对的 tool 响应写进快照/记忆（`:1600-1624`），`emptyReply` 也 `convergeMemory`（`:1342-1367`）。**同一个"半截"，取消能活过刷新，失败不能。**
- 前端没有"替换"语义：`message_start/delta` 是 append 渲染；只有刷新恢复会先清段再全量重放（`ai.ts:2246-2256`）。所以任何"自动从半截续写"若直接 append，就是"半截 + 全文"两遍。

**② 工具成功后失败，能否只重跑失败的 model step？机制在，入口没有。**
- 能力其实已经具备：`ResumeService` 能按 ledger/pending 复用**已结算**的工具结果重建 `assistant(tool_calls)+tool`，再 `driveResumed`，且每次 attempt 用同一份固定 `history` 重建 Prompt（`ResumeService.java:870-967`、`ResilientChatService.java:599-604`）——这正是"只重跑模型步、不重跑工具"。
- 但这条路径**只对 SUSPENDED（park）可达**：`FAILED` 是终态，`ResumeService.java:749-755` 直接 `ALREADY_FAILED`；运维端点也一样。而首轮 inline 工具轮的失败被 `sideEffect=true` 一刀切掉重试（`:828-829`）→ `fail()` → 永久不可续。
- 结果分叉：走 park 的工具轮（确认门/前端工具/写类）在续跑路径里**能安全重试模型步**；不走 park 的后端只读工具轮，模型步失败即终局。（`ConfirmGate.java:721` 才是 park 出口，未决才 park。）

**③ "并非完全没有自动恢复"——成立。**
已有：断流自动重挂（`sse.ts:583-763`，含建连看门狗）、差量回放与孤儿过滤（`SseChatEmitter.java:799-923`）、刷新静默续收（RUNNING 全量、SUSPENDED 增量）、终帧归档判定（`bbc2089` 的 L-143 修复）、park 决策后自动续跑 + 有界重派发（`ResumeService.java:1051-1110`、`application.yml:265-267`）、启动扫描与僵尸接管（`StartupResumeRunner.java:69-194`）、重挂自愈对账（`AiController.java:776-801`）、会话门死轮放行（`RunLiveness.java:42-62`）。

**④ 已有 partial 时绝不能引入的自动重放**（这些正是现在没做自动化的原因）：
1. **已产出正文/思考后整轮重跑** —— 思考会叠一段、正文重复；`retry` 帧会落在 delta 之后，违反帧序契约（代码注释 `:2224-2228` 明说）。
2. **工具已开始执行后整轮重跑** —— 重复副作用（表格写/撤销/发布/导入作业/上传交接）；且首轮重试从 memory 重建会**丢 `assistant(tool_calls)` 与 TOOL 段**，W-21 实测请求变成 `[system,user,user]`（`RetryWithSuspendedToolRoundTest.java:51-75`、`docs/evidence/.../ci-failure-followup-2026-10-26.md:92`）。
3. **挂起工具轮当"空轮"重试** —— W-21 真事故：重试用 DONE 把挂着的一轮抹掉；`152b3a9` 的 Fix A 已封（`:834-846`、`:2443-2450`），不能回退。
4. **对 FAILED/CANCELLED 自动 resume** —— 会覆盖终态/双写历史（`ResumeService.java:749-755`、`RunStore.java:806-812` 的终态 CAS/清索引）。
5. **静默换模型续跑** —— `PROVIDER_GONE` 409 是红线（`ResumeService.java:777-810`）。
6. **同一轮开第二条连接** —— 双连接会让工具卡/终帧重复落地；现有 `roundEpoch`、`loading`、防双连接守卫不能放松（`ai.ts:2443-2460`）。
7. **读侧 touch 刷新 RUNNING 业务锚点** —— 会把卡死轮越读越年轻、僵尸判据永远等不到（I5，`RunStore.java:2772-2798`）。

---

## 二、逐场景设计审查

| 场景 | 用户看到 | 后台实际 | 判定 |
|---|---|---|---|
| **首字节失败** | 「上游连接中断，正在重试（本轮尚未产出内容）」→ 成功则继续写；两次都失败出错误卡「AI 响应超时…」+「继续」 | 首包 30s（`application.yml:227`）→ `FIRST_BYTE_TIMEOUT`；`emitted=false, sideEffect=false` → 退避 0.5s×2 后 attempt 2（`:757-767/917-949`） | 合理。唯一瑕疵：notice 文案对所有重试都说"上游连接中断"，超时/429 也用这句（`zh-CN.json:138`） |
| **中途断流（已有正文）** | 半截正文保留 + 错误卡「AI 返回中断，已显示的内容不完整，请重试」+「继续」 | `retryable=false`（emitted）→ `fail()`：快照 FAILED、`_AFTER_PARTIAL` 码、不发 retry 帧 | 不自动续写是**有意边界**；真问题见 F1/F2 |
| **缺终帧（TCP 静默 FIN）** | 通常表现为"连接中断"通知，随后自动重挂；重挂拿到归档终帧则静默收敛 | `!sawTerminal()` → `INCOMPLETE`（`:802-806`）；无产出则重试，有产出则失败。重挂收尾只看**归档里有没有 done/error**（`SseChatEmitter.java:642-712 / 724-741`） | `bbc2089` 修掉的正是"快照已终态、done 还没落档"导致重连者永远等不到 done 的窗口；现在判定正确 |
| **超时（帧间静默 / 段预算）** | 前端 45s 先判静默 → 自动重挂；后端 90s/300s 再判 | 两层刻意错层：45s < 60s（代理）< 90s（`sse.ts:22-31`）；看门狗工具活跃期不判静默、挂起期不判预算（`StreamWatchdog.java:157-187`） | 设计正确；`TOTAL_BUDGET` 不进可重试集（`StreamViolationException.java:79-83`） |
| **工具后失败** | 错误卡「…且本轮已有工具执行；请先核对结果再重试」+「继续」 | `sideEffect=true` → 不重试 → FAILED；工具轮已由 `recordToolRound` 写 DB 历史、`settle` 写快照（`SpToolCallingManager.java:687-708/2227-2237`），但 **memory 不收敛**；FAILED 不可 resume | 见 F2；这是"能力存在、入口缺失"的典型 |
| **人工等待（park）** | 卡面停留、连接被服务端主动收流、无"断流"提示；决策后自动继续写 | `saveSuspended` → `RunParkedException` → `parkTurn` 什么都不写（`:2452-2473`）；决策后 `triggerResume`，派发池饱和/会话被占按 2→30s 退避 15 次（`application.yml:260-267`） | 这条链的自动恢复最完整，**且它天然不会重跑工具**（ledger 已结算复用） |
| **多实例接管** | 任何实例都能重挂并回放；旧主人丢租后不再出帧 | 帧在 Redis Stream（跨实例 stateless），丢租/ fence 越界时业务帧（含终帧）一律丢弃（`SseChatEmitter.java:1492-1523`）；只有 SUSPENDED+全决的重挂会自愈派发（`AiController.java:781-801`） | **RUNNING 死轮在集群运行期没有兜底**：只有进程启动扫描才带 `forceTakeover`（`StartupResumeRunner.java:69-101`），无周期扫描（全仓无 `@Scheduled` 承载）→ 见 F4 |
| **页面刷新** | RUNNING：那条回答继续写（无横幅、无 runId）；SUSPENDED：卡重建、增量续收；已失败：**错误卡消失** | `restoreInFlightRound` 清段 + `lastSeq=0` 全量重放（`ai.ts:2224-2256`）；`restoreSuspendedSnapshot` 用 `archiveMaxSeq` 增量（`:2127-2210`）；终态轮 `/runs/current` 已清（`RunStore.java:809-811`）→ `sealLeftoverInFlight` | 见 F1：失败轮的"错误"没有任何持久面 |

---

## 三、外部 AI 两个断言的代码核实

**A.「`!emitted&&!sideEffect` 使重试几乎永远不触发」——结论：部分成立，措辞过强。**

- 闸门确实存在且是四关之一（`:826-833`），`emittedAny()` 含 reasoning（`:2285-2289`）⇒ **对 reasoning 模型极早失效**（思考先到，之后任何断流都不能重试）。
- 但它**不是"几乎不触发"**：整个"首包前"类别都会重试，并有单元测试锁定：500 两次调用（`UpstreamErrorRetryPolicyTest.java:169-181`）、IOException 两次（`:183-196`）、429+Retry-After=0 两次（`:198-210`）、`tool_calls` 作末帧且无产出两次（`:284-297`）。
- 真正的失效面是**中途断流**：`:230-243`（有半截 + 500 → 只调用 1 次）、`:299-312`（有半截 + tool_calls → 1 次）、`:314-324`（只有 `[DONE]` 无 finishReason + 有半截 → 1 次）。这些是**有意**的（防重复正文/重复思考/重复工具）。
- 另需澄清：Spring AI 内部**没有**第二层流式重试。我用字节码核对了 `spring-ai-openai-1.1.7` 的 `OpenAiChatModel`：`retryTemplate.execute(...)` 只出现在 `lambda$internalCall$3`（非流式），`internalStream` 链路无 RetryTemplate；项目自建策略是 3 次（`AiRetryDefaults.java:46-47`），也只作用于非流式调用。

**B.「2 次实际仅 1 次」——结论：配置语义是"(总)尝试次数=2"，即最多 1 次重试；这不是 bug，但注释措辞容易误读。**

- `AiProperties.java:776-777` 写"单轮最大尝试次数（首轮 + 重试次数-1）"，`application.yml:229` = 2，循环 `for attempt = 1..maxAttempts`（`:754`），测试断言 `getAttempts()==2`、`stream()` 两次。
- 对"中途断流"场景，实际确实只有 1 次（三重条件挡掉）；对"首包前"场景是实打实 2 次。**所以"2 次实际仅 1 次"只在有产出的场景成立。**

---

## 四、真问题与证据（按严重度）

**F1（重要）中途断流后的半截正文"server 侧无落点"，刷新后错误消失、看起来像完整答案**
- `fail()` 不落半截、不收敛记忆（`:1757-1779`）；取消路径却落（`:1600-1624`）。
- 前端镜像只写 `content/parts/reasoning/toolRuns/pendingCall/stopped/truncated`，**不含 `errorCard`**（`ai.ts:891-901`）；`loadHistory` 把镜像消息一律归一 `done:true`（`:1726-1735`），随后把镜像尾条并入历史（`:2090-2100`）。
- 终态轮清了 session→current（`RunStore.java:809-811`）⇒ 刷新走"无在途轮"分支，不会重挂补错误帧。
- 用户后果：刷新后只看到半截正文、没有错误卡、没有"已中断"标记；点「继续」时模型内存里**既没有半截答案、也没有工具轮**（只发一条"继续"新用户消息）。
- 是否近期引入：**否**。该口径早于本轮五个提交（`fail` 的形态可追到 S4.2 批次）。但 park 批（`20c56bb` 起工具轮改为先落 `SUSPENDED`）让"工具轮在 DB、内存在缺失"的不对称更常见。

**F2（重要）"工具成功后模型失败"没有安全的重跑入口，而能力其实已经写好**
- 首轮 path：`sideEffect` 命中 → 不重试（`:828-833`）；`fail()` 落 FAILED（`:1766-1770`）；`ResumeService` 拒绝终态（`:749-755`）。
- 续跑 path：同一 `history` 每次 attempt 重建、已结算工具复用、不重发用户消息（`:599-604`、`ResumeService.java:870-967`），**这才是"只重跑模型步"的正确形态**，但只有 park 轮用得上。
- 是否近期引入：否（终态拒绝起于 `cb52622`，三条件重试起于 `b40ee46`）。但要注意 `152b3a9` 的 W-21 Fix A 是**刻意收窄**（挂起轮不得重试），方向正确。

**F3（中）失败码缺"已产出/已副作用"的组合，文案丢掉了"内容不完整/请先核对"的提醒**
- 已登记的组合只有 8 组（`ErrorCodes.java:336-361`、`messages_zh.properties:107-120`），**`UPSTREAM_UNAVAILABLE_AFTER_PARTIAL`、`UPSTREAM_RATE_LIMITED_*`、`UPSTREAM_REJECTED_*` 等未登记**；`failureCode` 组合未登记就退回基础码（`:2737-2744`）。
- 测试直接印证：有半截 + 500 的终帧码是 `UPSTREAM_UNAVAILABLE`，不是 `_AFTER_PARTIAL`（`UpstreamErrorRetryPolicyTest.java:230-243`）→ 用户看到"AI 服务暂时不可用，请稍后重试"，没有"已显示内容不完整"。
- 影响面有限（流已建立后 5xx 多以连接异常出现），但"有半截 + 上游拒绝/限流"一类仍会落到基础码。是否近期引入：否。

**F4（中）集群运行期的 RUNNING 死轮没有自动接管，用户要等 180s 且只得到通用文案**
- 只有启动扫描带 `forceTakeover=true`（`StartupResumeRunner.java:69-101/136-161`），运维端点 `resume` 不带（`AiController.java:1105-1115`）；无周期扫描。
- reattach 自愈只覆盖 `SUSPENDED` 且条目全决（`AiController.java:776-801`）。
- 用户侧：断流 → 重挂 4 次耗尽 → 「连接中断，结果可能不完整」；180s 内点「继续」→ 409 → 前端只显示「这条消息没有发出去」并回收乐观气泡（`ai.ts:2690-2697`、`AiController.java:417-444`、`RunLiveness.java:42-62`）。
- 是否近期引入：部分。死轮"放行新发送"是 G6 的近期改进，但"接管旧轮"仍只挂在启动路径。

**F5（小）`chat()` 环外异常只发 error 帧、不写 FAILED 快照**
- `AiController.java:469-479` 只 `out.error(AI_RUN_FAILED)`，`chat()` 此时未进 `runAttempts`，快照停在 `RUNNING` 且索引不清 ⇒ 之后每次刷新都会静默重挂这个已死轮（replay 里有 error 帧，能收敛，但状态长期不真）。

**F6（小）开流前瞬时失败（503/网络）不自动重试**
- `sse.ts:505-520` 直接抛 `ApiError`；`handleChatError` 只出提示（`ai.ts:2775-2832`）。这类请求零副作用，是最适合"有限自动重试"的一类，却是纯手工。

**F7（边界，供知情）超长轮的全量回放可能撞 replayGap**
- 刷新走 `lastSeq=0` 全量（`ai.ts:2256`），事件日志窗口 `STREAM_WINDOW=6000` 且按字节再收窄（`RedisFrameStore.java:81/401-460`）；游标早于幸存的窗口时 `degraded=true` → 回放侧补 `AI_REDIS_UNAVAILABLE` 错误帧收尾（`SseChatEmitter.java:686-690/838-857`）。即"极长回答 + 刷新"可能显示"服务暂不可用"而非答案。

**F8（小）错误卡「稍后重试」发的是新的"继续"消息**
- `AiPanel.vue:1424-1436` → `continueRound()` → `ai.send('继续')`。在"什么都没产出"的 429/5xx 场景，语义上应重发同一请求，而不是往对话里塞一条"继续"（会产生多一条用户气泡，模型也可能重复自问）。

---

## 五、最小修复方向（仅供审，未改任何文件）

1. **让失败也留下可解释的现场（对应 F1）**：`fail()` 在 `emitted` 时把"半截 + 已结算工具轮"写入快照/DB 历史（可复用 `stoppedRoundMessages` 的配对构造，元数据换成 `failed=true`，不写 `stopped`），并让前端镜像携带 `errorCard`/`errorCode`，刷新后仍显示"已中断"。**先只做持久面与展示面，不改重试语义。**
2. **窄入口"仅模型步重跑"（对应 F2）**：仅当 `FAILED && inFlight 为空 && 所有 pending 已结算 && messageJson 末段是配对的 tool 轮 && 无半截正文` 时，允许一次有界的"重建历史 → 只调模型"路径（复用 `ResumeService` 的重建 + `driveResumed` 的固定 history 重试）。**不重执行工具、不追加用户消息、不跨模型。** 有半截正文的情形**不得**走这条（会 append 出"半截+全文"），必须另有"截断替换"帧或前端清段重放语义才可谈自动化。
3. **补码面/文案（对应 F3）**：登记 `UPSTREAM_UNAVAILABLE_AFTER_PARTIAL` 等缺失组合，或让 `messageFor` 在码带后缀时追加"已显示内容不完整/已有工具执行"这一句（不动码面，最小）。
4. **死轮兜底（对应 F4）**：在实例内加一个受租约与 `zombie-age` 约束的**低频**扫描（或让 reattach 在 RUNNING+无租约+超龄时走 `deadcard` 接管），与 `RunLiveness` 同源判据；不要放宽僵尸窗口。
5. **开流前有限自动重试（对应 F6）**：仅"未收到 `start`"或"零产出"时按现有退避重发一次，复用 `reconnectDelayMs`。
6. **环外异常补终态（对应 F5）**：`AiController` 的 catch 里按现有 `fail()` 语义落 FAILED（或至少清 session→current），避免 RUNNING 幽灵索引。

---

## 六、本次审查的边界（如实申报）

- 全程只读：未修改任何文件、未启动/停止服务、未运行测试或构建、未做真实模型调用、未发 HTTP 写请求。
- 未读 `.env`/凭证类文件；未输出任何密钥。
- 证据全部来自源码、测试断言、提交历史与仓库内证据文档；**没有把桩失败当成现网失败**：W-21/W-30 的"3ms 桩文本轮""每路由一次"等是确定性桩复现（用于锁定真实代码路径的缺陷），我在文中已按"复现手段"标注，未据此推断现网时序。
- 唯一的外部依赖核实：为判断"流式是否还有第二层重试"，我反编译了本机 `~/.m2` 中 `spring-ai-openai-1.1.7.jar` 的 `OpenAiChatModel` 字节码（结论：`retryTemplate` 只在 `internalCall` 生效）。
- 未能确认的一点：`MessageChatMemoryAdvisor` 在"流正常结束（tool_calls 收尾）后写 memory"的确切时机，我是依据 W-21 的实测注释与证据文档推断 `[system,user,user]` 的成因；若要据此做 F2 的修复，建议先用一个确定性用例锁住"重试请求的消息序列"。
