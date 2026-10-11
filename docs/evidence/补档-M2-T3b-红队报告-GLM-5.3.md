---
source: GLM-5.3（M2-T3b 红队）
date: 2026-10-09
scope: M2-T3b 红队攻击
target_head: 749fa43
attack_report: T3b-施工报告.md（662 行，9 条目全 PASS 自述）
---

> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）


# M2-T3b 红队攻击报告（GLM-5.3）

**纪律声明**：全程零 git 写操作、未修改主仓库任何文件（仅 target/ 构建产物副作用）、未读写任何 `.env`/密钥（E2E 用 `ci-stub-placeholder` 占位 key）；复跑产物全部落 `<RT>`（红队复跑产物目录）；端口 18330/18399 起前 netstat、收尾只按 PID 停自启进程（后端 java 与桩 node；Memurai PID 5032 未动）。路径占位符：`<MAIN_REPO>` = 主仓库、`<RT>` = 红队复跑产物目录、`<RD>` = 本报告所在目录。

**攻击方法**：本体负责四个必审点与其余攻击面的代码级攻击（逐 diff / 逐方法走查 + `git show HEAD` 改前对照）；5 个子智能体（deepseek-v4-flash）执行机械活：后端全量复跑+E2E 复跑、前端双绿复现、范围对账+H9 冻结+ci.yml 审计、31 文件脱敏终扫、verify-e2e.ps1 TC23 断言审计。

---

## 0. 总裁决

> # **修正后放行**
>
> **9 条 PASS 结论全部维持，无一被打破**；四个红队必审点（A12/A21/A9/A4）全部按卡落地、未发现降级或放宽。
> 「修正」对象是**报告文本勘误（2 处记账错误）与提交卫生处置（工作区混入另一工作流在途改动）**，代码与测试本身无需返修。

| 被攻击结论 | 攻击结果 | 依据 |
|---|---|---|
| T3-3 / T3-4 / T3-6a / T3-6b / T3-7 / T3-8 / T3-9 / T3-10 / S3-4 九条 PASS | **全部维持** | §2 复现一致 + §3/§4 攻击不成立 + §5 弱点均不达判据破坏 |
| A12 空回复语义变更落地 | **成立**（攻击 5 路，详见 §3.1） |
| A21 截断口径变更落地 | **成立**（攻击 6 路，详见 §3.2；1 个 S3 边界留痕） |
| A9 续跑载体与饱和语义落地 | **成立**（攻击 5 路，详见 §3.3；2 个 S3 残余登记） |
| A4 不误伤验证落地 | **成立**（攻击 3 路，详见 §3.4） |

发现分级汇总：**S1×0、S2×2、S3×8**（§6 清单）。无一项构成对条目判据的推翻。

---

## 1. 攻击对象与环境快照

- HEAD = `749fa43`（与报告 `baseline_head` 一致）；报告落盘时（17:45:10）工作区 = **23 M + 8 ?? = 31 文件，与报告 §10/§12 口径吻合**（探针文件与第三方改动的 mtime 全部晚于报告，见 §6 S2-2）。
- 红队终态快照（18:0x）：26 M + 8 ?? —— T3b 自身 23 M + 8 ?? 完整在册，另有 **3 个前端文件（`frontend/src/utils/frontend-tools.ts`、`views/ExportWizardView.vue`、`views/ImportWizardView.vue`）正被另一工作流（issue-1）并发修改**（mtime 17:53:22–31，晚于报告），另有 2 个自称"用后即删"的 `frontend/__issue1_probe.{html,ts}` 曾出现（17:50/17:52）并于红队终态快照前已被删除。
- Memurai 127.0.0.1:6379 LISTENING（PID 5032）；18330/18399 跑前空闲；JDK 21.0.12。

## 2. 门禁与计数复现（攻击面 8）

全部声称值**一次复现一致**【实测，子智能体复跑】：

| 项 | 声称 | 红队实测 | 一致 |
|---|---|---|---|
| 后端控制台 | `Tests run: 346, Failures: 0, Errors: 0, Skipped: 0` | 346/0/0/0（BUILD SUCCESS，55.2s） | ✅ |
| XML 口径求和 | 346/0/0/0，53 个 XML | 逐 XML 求和 346/0/0/0，恰 53 个（跑前已清空 surefire-reports，全部为本轮新生成） | ✅ |
| 前端 | typecheck + build 双绿 | 退出码均 0、零错误（`<RT>/fe-typecheck.log`、`<RT>/fe-build.log`） | ✅ |
| E2E 桩模式 | PASS=28 FAIL=0 GFSKIP=0 Q8 2/0 EXIT=0 | 逐项一致；TC23 PASS（帧 15 / HTTP 取证 2） | ✅ |

- 控制台逐类行 55 条 vs XML 53 个的差异已核实为 `GenerativeFormGateTest` 两个 `@Nested` 分行打印（XML 归并为 1 个根元素 tests=8），**逐类求和与 XML 完全吻合，无缺失类**。
- jar 归因核查：`config-mgr.jar`（finalName 确为 config-mgr）虽 mtime 早于本轮测试，但 `Nothing to compile` + 源码/字节码无新于 jar 者 ⇒ E2E 跑的确实当前工作树代码（含 T3b 全部未跟踪新类）。
- 证据：`<RT>/mvn-test-console.log`、`<RT>/xml-summary.txt`、`<RT>/e2e-console.log`、`<RT>/e2e-artifacts/gfc-TC23.{sse,http}.txt`、`<RT>/e2e-backend.log`、`<RT>/stub/`。

## 3. 四个红队必审点逐条攻击记录

### 3.1 A12 空回复语义变更 —— **攻击不成立（PASS 维持）**

| # | 攻击路径 | 结果 |
|---|---|---|
| 1 | **误伤合法空正文场景**：纯工具轮是否会被误判为空回复失败？ | 不成立。`finish` 只在模型终帧无 tool_calls 时到达（工具轮经 `SpToolCallingManager` 继续循环后仍会回到 finish，此时若正文非空照常 DONE）。`ResilientChatService#finish` 的空文本判定 `!StringUtils.hasText(text)`（空串/纯空白）与"用过工具"正交——`EmptyReplyTerminalTest#emptyTextAfterAToolRoundAlsoBecomesFailed` 断言的正是"用过工具不豁免"，且卡 A12 明文裁定三形态一律 FAILED。语义边界与卡一致，无误伤面。 |
| 2 | **帧协议自洽性**：`message_end→error` 时序是否破坏契约？ | 不成立。走查 `ResilientChatService#emptyReply`（:487-509）：`out.textEnd(text.length())` 幂等（`SseChatEmitter#textEnd` 用 `textSegmentOpen` CAS，未开段不发），再 `out.error(...)`——与 `#fail` 的"运行关闭前先关闭它打开的一切"契约一致。测试断言帧序 `start,message_start,delta,delta,message_end,error`（`containsExactly`，非弱断言）且过 `FrameContract.assertConformant`。 |
| 3 | **watchdog/订阅泄漏**：emptyReply 不接 watchdog 参数，是否漏关？ | 不成立。`watchdog.close()` 与 `subscription.dispose()` 在外层 `finally`（:360-365），覆盖 finish→emptyReply 路径。 |
| 4 | **快照/记忆污染**：空 assistant 是否入记忆？终态字段是否齐全？ | 不成立。`emptyReply` 不 `history.add`（与 `cancelTerminal` 同口径），`convergeMemory(sessionId, history)` 收敛快照现状；快照 `FAILED + error=EMPTY_REPLY：… + finalText 保留原文 + inFlightToolCalls 清空`，测试逐项断言。 |
| 5 | **非空回归是否真未动**：`git show HEAD` 对照 + 用例 | 成立（报告属实）。HEAD 版 `finish` 为 `if (hasText) history.add(...)` + 状态无条件 DONE；当前版仅前插空文本分支，非空路径的帧序（`message_end, done`）与 DONE 语义未动，`nonEmptyTextStillEndsWithDone` 锁住。受影响用例清单（grep 5 类、仅 `ChatTurnFrameSequenceConformanceTest` 走真实 finish 且全非空）经全量 346 绿间接复核。 |

**残余（S3-7）**：`error` 终帧不带 `attempts/usage`（`done` 帧有），空回复轮的可观测性略降；空回复不自动重试（不属 brokenStream，重试三条件不满足），前端靠 message 文案引导用户重试——均为口径内的小差异，不影响判据。

### 3.2 A21 截断口径变更 —— **攻击不成立（PASS 维持），1 个 S3 边界留痕**

| # | 攻击路径 | 结果 |
|---|---|---|
| 1 | **截断破坏 JSON 结构/帧协议** | 不成立。`FrameSizeLimiter#apply` 只做字段值替换（`frame.put(field, 截断串)`），任何路径都不字节截断；`takeChars` 二分按转义字节数取字符 + 代理对保护，不会产出半个代理项。 |
| 2 | **回放路径对截断帧的兼容** | 不成立。截断发生在 `SseChatEmitter#emit` 的"取号之后、`appendEvent` 落档与入队之前"（:666-694），归档与投递拿同一份帧、同一 seq；`RunStore#events` 回放读到的是合法 JSON，无静默丢帧。`SseFrameSizeLimitTest#oversizedFramesStillConsumeContiguousSeqs` 断言 seq 1/2/3 无洞。 |
| 3 | **死循环/反复截断** | 不成立。迭代上限 `MAX_ITERATIONS=8` + 每轮按超出量收缩预算（`budget -= (retry.length - maxBytes) + margin`）；预算 ≤0 时字段置空串兜底。收敛性有界。 |
| 4 | **判定口径是否真为整帧（含 seq）** | 成立。`emit` 先 `frame.put("seq", nextSeq())` 再 `FrameSizeLimiter.apply`，序列化的是含 seq 的整帧；心跳帧（无 text/result、常量小负载）单独走 `writeValueAsBytes` 分支不进截断器，口径自洽。 |
| 5 | **「无二次序列化」（观察项 7）是否属实** | 属实（但需限定读法）。未超限路径：`apply` 返回的 byte[] 直接 `new String(payloadBytes)` 作投递负载，确实一次序列化（与旧实现持平）；**超限路径**会有 full 序列化 + overhead 探针 + 每轮 retry 序列化（≥3 次）——观察项 7 的"净增=无订阅者场景一次"只对未超限路径严格成立，超限帧成本更高（量级可忽略，S3 留痕即可）。 |
| 6 | **误伤未超限帧** | 不成立。`full.length <= maxBytes` 即原样返回，`smallFrameIsUntouched` 双侧（归档+投递）断言；边界 `恰好=上限` 不截、`+1` 触发（测试用骨架实测反推字节数，非估算）。 |
| 7 | **ToolResultLimiter javadoc/文案修订** | 成立。diff 实读：类 javadoc 第 2 项改为"受单帧字节上限约束的字段级截断 + 标记，台账不受约束"；`marker(...)` 文案同步改"T3-10 口径"。 |

**残余（S3-3）**：`unreachable-limit` 分支——不可截字段占主导（无 text/result 或其余字段本身超限）时，**最终落地帧仍 >64KB**，只挂 `truncated:true + truncatedReason(…unreachable-limit…)`。JSON 合法、seq 无洞，R5 不破；但「落地 ≤ 上限」并非无条件成立。代码注释有说明，报告 §4.4 未显式登记该边界——建议补登记（当前帧负载最大来源 text/result 均可截，实际触发概率低）。

### 3.3 A9 续跑载体与饱和语义 —— **攻击不成立（PASS 维持），2 个 S3 残余登记**

| # | 攻击路径 | 结果 |
|---|---|---|
| 1 | **ObjectProvider 是否真解环** | 成立（解环真）。`ConfirmGate` 8 参 `@Autowired` 构造器只持 `ObjectProvider<ResumeService>` + `@Qualifier("aiResumeTrigger") Executor`，不直接依赖 `ResumeService`；`getIfAvailable()` 为 null 静默跳过（NO_RESUME_SERVICE）。全量 `@SpringBootTest`（346 例含多上下文）+ 有 key 的 E2E 后端启动（`available:true`）双路证明无环。 |
| 2 | **HTTP 线程立即返回（时序）** | 成立。`maybeTriggerResume` 在 `synchronized(submitLock)` 块**之外**调用（三条 ACCEPTED 路径的 return 表达式）；`confirmReturnsBeforeResumeActuallyRuns` 注入"只捕获不执行"执行器，断言 submitDecision 已返回而 `resume` `verify(never())`。 |
| 3 | **hasActiveLatch 遍历判据的竞态（latch 注册/摘除窗口）** | 见下。`hasActiveLatch` 本身只读遍历 `ConcurrentHashMap.keySet()` 前缀匹配（`runId + "|"`），无锁外状态、不改语义——实现与 A8 完全一致。**残余竞态**：`saveSuspended`（写 SUSPENDED 快照）→ `latches.put`（`awaitDecision` 登记）之间的毫秒窗内，同进程 confirm 看到 `woke=false` 且 `hasActiveLatch=false` → 派发 resume，随后 latch 登记的等待方与 resume 驱动并存（双驱动）。该窗口是**卡内 R4③ 已接受的残余**（"触发前校验"是缓解而非消除，`claimExecution` 原子认领兜底"恰好一次执行"，共享 seq 空间保证帧不乱序、内容可能重复）——实现符合卡，但报告未把"该窗口仍可双驱动、靠 claimExecution 兜底"显式写入观察项（S3-1，建议补登记）。反向竞态（latch 已摘除后才派发）无害：等待方已超时/已醒，resume 走 `ALREADY_RUNNING`/正常续跑。 |
| 4 | **dismiss 触发续跑是否复活已放弃轮次** | **不成立（不复活）**。`ConfirmGate#dismiss` 置 `FRONTEND_CANCELLED`；续跑侧 `SpToolCallingManager#resolvePending`（:419-431）的 `shouldExecute` 集合 = `APPROVED ∪ (PENDING∧BACKEND) ∪ (EXECUTED∧!executed)`——**不含 `FRONTEND_CANCELLED`**，只复用 `resultText`；`ResumeService#resume` 内 `cancelPendings` 收敛残留。已放弃的工具不会被补执行，轮次以"已取消该工具"的语义继续循环并收敛（终帧正常）。`ConfirmGateDeadlockResumeTest#dismissPathAlsoTriggersResume` 断言的是触发续跑（正确行为），不涉复活。 |
| 5 | **EXECUTOR_SATURATED 是否掩盖真问题** | 部分成立 → S3-2。饱和语义本身如实：`AbortPolicy` 拒绝 → 吞掉 + WARN + `resumeOutcome=EXECUTOR_SATURATED` + 外部输入仍 ACCEPTED（`saturatedTriggerExecutorIsReportedNotThrown`），HTTP 不报错——符合卡。**但掩盖面在于**：(a) 重复 confirm 走 `DUPLICATE → NOT_APPLICABLE`，**不会补触发**——错过派发的轮次在同进程内无自愈路径，只能等重启（`StartupResumeRunner` 会拾起 resumeable 轮）或用户 cancel；(b) 前端只读 `code/status`，`resumeOutcome` 用户不可见。容量 2+16 在 M1 单实例下被打满需要 18 个并发外部输入，概率低，但该"静默丢失 + 无进程内恢复"路径值得登记（S3-2）。 |

**载体核实**：bean `aiResumeTrigger`（`AiExecutorConfig`，core=max=2、queue=16、AbortPolicy、`ai-resume-trigger-` 前缀、destroyMethod=shutdown）与报告 §3.2 逐项一致；禁嵌套理由（resume 内部再向挂起池提交）与 `ResumeService` 实现吻合；`AiPoolCapacityBudget` 未纳入触发池属观察项 8 已登记的口径。

### 3.4 A4 不误伤验证 —— **攻击不成立（PASS 维持）**

| # | 攻击路径 | 结果 |
|---|---|---|
| 1 | **用例⑤是否真走「预检查→替换→重新预检查→导入」全链** | 成立（真全链）。实读 `PrecheckFingerprintGuardTest#legitimateReUploadAndRePrecheckStillAllowsImport`（:144-165）：真写 UPLOAD v1（生产 `ExcelWriter`）→ 同步驱动**真实** `precheckJobRunner.run`（COMPLETED + resultJson 含 UPLOAD/sha256）→ 替换 v2 并先断言被拦（`expectImportStale` 409 + **不留导入作业行**）→ 重新真跑预检查（写新指纹）→ **`MockMvc POST /api/tasks/{id}/jobs {"IMPORT"}` 走真实 `JobService#createAndStart`**（守卫→指纹比对→落库→异步执行，非直驱绕过）→ 201 → `waitTerminal` 轮询至 **COMPLETED**。全链成立。 |
| 2 | **「合法重跑」是否只是弱断言（只看 201 不看作业结局）** | 不成立。除 201 外断言 `jobType=IMPORT`、jobId 提取、轮询 200×50ms 至终态且 `==COMPLETED`（10s 超时抛 AssertionError）。 |
| 3 | **TOCTOU 窗口是否有比登记更大的实际暴露面** | 部分成立 → S3-6。窗口边界核实：guard（`assertImportFingerprintFresh`，事务内）→ 作业落库 → afterCommit → **ImportJobRunner 实际读盘**——后段含异步拾取与排队延迟，**秒级而非"极窄（毫秒）"**；观察项 4 的量级措辞偏乐观。但 L1 指纹比对确实把"整轮窗口"收窄为"校验后到读盘前"，且该窗口内的替换属恶意竞态（正常用户流程不会命中），维持观察项定级合理（S3-6 建议改措辞）。另核实从严分支完备：`digestOf` 失败写 `{sha256:null,size:null}` → `expectedSha==null` → STALE；`writeFingerprintSummary` 失败仅 WARN（作业仍 COMPLETED）→ 导入侧读不到摘要 → STALE——两处失败都收敛到从严，无绕过面。 |

---

## 4. 其余攻击面（5/6/7/9/10）

### 4.1 T3-8 绝对上限（攻击面 5）——**PASS 维持**

- `RunStore` 新增 `remainingTtl(createdAtMs, nowMs, sessionTtl)` 纯函数：剩余 ≤0 → `EXPIRED_RENEWAL=1s`（SET/EXPIRE 不收非正 TTL，用最小正 TTL 让键随即消失，**不复活**）；`createdAtMs<=0`（无锚点）→ 回落整额（不误杀旧数据）。7 个续期点（save/putPending/claimExecution/appendLedger/appendEvent/recordIssuedSeq/touchActivity）逐一改走 `ttlFor`，`git show HEAD` 行号 176/351/405/436/556/596/615 与报告 §4.2 完全一致【实测】。
- **`ai:run`/`ai:beat`/`ai:events` 三键一致应用**：三键分别经 save/touchActivity/appendEvent，全部走 `ttlFor`；报告收尾的运行中实测（21594→21553，随时间下降且 ≤ 创建+6h 剩余量）+ 红队 E2E 全绿（TC23 挂起轮正常续期收尾）双路佐证。`ai:seq`/`ai:pending`/`ai:claim`/`ai:ledger` 同走 `ttlFor`（一致面比报告表述的更宽）。
- 边界用例：整额/取剩余/恰 1s/恰 0/已过上限/null-零-无锚点回落，`RunStoreTtlAbsoluteCapTest` 5 例断言齐全。进程内 `runCreatedAtMs` 缓存以 `createdAtMs` 不可变为前提（快照确实只在创建时写一次），`delete` 时摘除，无陈旧面。`session→current` 索引键用 10m `sessionCurrentTtl()` 不纳入——边界已在报告登记。

### 4.2 T3-10 整帧判定 + 序列化复用（攻击面 6）——**PASS 维持**

见 §3.2（判定含 seq 属实；"无二次序列化"对未超限路径属实、超限路径 ≥3 次序列化，S3 留痕）。配置单一事实源（`AiProperties.Frame.DEFAULT_MAX_BYTES=64*1024` + `app.ai.frame.max-bytes: 65536` + `RunRegistry` 装配取 properties）核实一致。

### 4.3 T3-6b/TC23 断言强度（攻击面 7）——**PASS 维持，3 个弱断言登记**

子智能体逐行审计 `verify-e2e.ps1:2119-2240`：整段**无"只打印不判定"步骤**，全部 `throw → No() → $script:failed++ → exit 1`；runs/current 九项契约、A10 双字段、`done 恰 1`、`error 0`、`executed/executedBy`、台账存在、PUBLISH `COMPLETED` 均为真断言（字段与产品代码逐一比对存在）；「孤儿过滤」是真计数（实收帧按 seq 过滤），并有心跳帧证据证明 3s 窗口连接真实建立（非空集空转）；`Read-Sse-Brief` 握手失败显式抛（防恒真）。可满足性有双轮日志佐证（首跑 FAIL → 只改脚本断言 → 复跑全绿，与报告留痕一致）。**三个最弱断言**（S3-4）：

1. `:2229-2230` 暂存行 `PUBLISHED`——`/diff` 空集时恒通过（缺 TC17 式 `-lt 1` 守卫与数据面交叉验证），且 `/diff` 响应未落盘取证，"非空转"无法事后复核；
2. `:2182-2184` 孤儿过滤锚点 = 服务端自报 `archiveMaxSeq`（自证），`archiveMaxSeq` 被低估但非零时"锚点失真→重复投递"打不中；
3. `:2219-2220` 台账只断言存在（`-lt 1`）不断言恰好一次（`ledgerCounts` 可用未用）——报告把"恰好一次"表述为台账口径证据，TC23 自身未承载该判据（claimExecution 在单测层已覆盖）。

另：`finally` 的 cancel 清理完全吞异常不断言（对比 TC17 有断言）；`L2318` 摘要行文案未含 TC23（报告 §5.3 原样引用了过期标签）。均不构成判据破坏。

### 4.4 范围对账与 H9/ci.yml（攻击面 9）——**映射完整 PASS；报告 2 处记账错误（S2-1）**

- **逐文件对账**：T3b 的 23 个 M 与报告 §10 表格**一一对应、无漏登、无暗改**（分文件行数 23 项全部与实测 ins/del 一致）；8 个新文件全部存在、归属正确、@Test 计数与报告 §7 吻合（47+1）。
- **S2-1 记账错误两处**：① 正文「22 个修改文件」——报告自己的表格列了 23 行、实测 23，**off-by-one 措辞错**；② 「+1011/-69」——分文件数值全对但其自身求和应为 **1013**，汇总手算错 2。另 §12「31 个文件 + 本报告 = 32」与结论行「31 文件全量含本报告」内部口径差 1（时点分母 31 本身正确，见 §1）。**均为报告文本缺陷，非漏登**。
- **H9 文档冻结**：三文档 numstat = 14/0、6/0、2/0，删除列全 0 ⇒ 纯追加，逐 hunk 核对插入点均在历史段落之后（含 M2-T3-设计卡 :55 后追加，与报告声称位置一致）。**PASS**。
- **ci.yml**：6 hunk = redis `--maxmemory-policy noeviction` + 3 行理由注释 + 4 处 27→28 文案；timeout/needs/if/步骤集合零变化。**PASS（无暗改）**。
- **改前行为抽查**（`git show HEAD`）：RunStore 7 处裸 `ttl()`、finish 空文本无条件 DONE、`PrecheckGateGuardTest` 改前 fixture（无配置项/无 resultJson/期望 201）——**3/3 与报告声称属实**。

### 4.5 脱敏与命名纪律终扫（攻击面 10）——**T3b 31 文件零命中 PASS**

- 31 个 T3b 在途文件（23M+8??）+ 施工报告全文：四规则（主仓库目录名字面量 / sk- 密钥 / 绝对盘符路径 / 内网 IP）**零命中**【实测】；`127.0.0.1` 回环、`ci-stub-placeholder` 占位 key、spike 三脚本豁免等边界情况逐一甄别无误伤。报告 §12「零命中」结论成立（分母时点也正确）。
- 范围外命中 2 处：`<RD>/T3b-施工卡-v2.md:3、:599` 出现暂存盘绝对路径（施工卡是指挥官产物、非施工棒交付物、不在 §12 自查范围）——移交指挥官酌处（S3-8 附带）。

---

## 5. 攻击不成立但值得留痕的实现细节（非缺陷）

1. **ResumeService 终态判定顺序位移**（S3-5）：改前 DONE/FAILED 早退在取消标志检查**之前**；改后取消标志优先、`isTerminal` 统一判定在后 ⇒ 「快照已 DONE/FAILED 但 Redis 取消标志仍存活」的交叉态，返回由 `ALREADY_DONE`（无副作用）变为 `ALREADY_CANCELLED` + `cancelPendings` 副作用。代码有注释说明位置原因（CANCELLED 快照也要收敛残留 PENDING），但报告 §4.1「保持既有副作用面」对该交叉态**不精确**——建议报告补一句留痕。结局码语义（FAILED→ALREADY_DONE(reason=ALREADY_FAILED)）保持不动，与卡一致。
2. 前端门禁两命令非独立证据链：`build` = `vue-tsc --noEmit && vite build`（typecheck 跑了两次），独立第二证据是 vite 打包——不影响双绿结论。
3. `E2E 摘要行 L2318` 文案未随 TC23 更新（报告引用属实但标签过期）。

## 6. 发现清单（严重级 + 锚点）

| # | 级 | 发现 | 主锚点 | 处置建议 |
|---|---|---|---|---|
| S2-1 | S2 重要 | 施工报告两处记账错误：「22 个修改文件」（实 23，自身表格即 23 行）；「+1011」（分文件全对，求和应为 1013）；§12 内部 31/32 口径差 1 | `T3b-施工报告.md` §10/§12 | **报告勘误后归档**（不改代码） |
| S2-2 | S2 重要 | 工作区未冻结：另一工作流（issue-1）17:53 起并发修改 3 个前端文件（曾出现 2 个 `__issue1_probe.*` 临时文件，后被其自删）；T3b 自身 31 文件无责（mtime 全部早于报告），但**提交官若 `git add -A` 会卷入范围外改动**，且报告 §10/§12 的自查结论已无法对当前工作区复现 | 工作区快照（§1） | 提交官按 §10 登记清单**显式逐文件 add**；或先让 issue-1 工作流收尾/隔离 |
| S3-1 | S3 观察 | A9 残余竞态：`saveSuspended→latches.put` 毫秒窗内 confirm 仍可双驱动（实现符合卡 R4③ 的"缓解+兜底"，但报告未把该残余显式入观察项） | `ConfirmGate#maybeTriggerResume` / `SpToolCallingManager#awaitDecision` | 报告观察项补登一句 |
| S3-2 | S3 观察 | `EXECUTOR_SATURATED` 错过派发后无进程内自愈：重复 confirm 走 DUPLICATE 不补触发，恢复只能靠重启（StartupResumeRunner）或 cancel；前端不展示 `resumeOutcome`，用户无感知 | `ConfirmGate#maybeTriggerResume`（DUPLICATE 分支）/ `AiController#confirm` | 登记观察项；后续可考虑 DUPLICATE 时也探测"快照 SUSPENDED 且无进展"补触发 |
| S3-3 | S3 观察 | `unreachable-limit` 分支：不可截字段占主导时落地帧仍 >64KB（JSON 合法、seq 无洞，R5 不破；「落地≤上限」非无条件） | `FrameSizeLimiter#apply` | 报告 §4.4 补登该边界 |
| S3-4 | S3 观察 | TC23 三个弱断言：`/diff` 空集可空转且无取证落盘；孤儿过滤锚点自证（archiveMaxSeq 低估时打不中）；台账只证存在不证恰好一次 | `verify-e2e.ps1:2229-2230 / 2182-2184 / 2219-2220` | 下棒顺手加固（加 `-lt 1` 守卫、实收最大 seq 作锚、`ledgerCounts -eq 1`） |
| S3-5 | S3 观察 | ResumeService 终态判定顺序位移的副作用面变化（DONE/FAILED+存活取消标志 → ALREADY_CANCELLED+cancelPendings），报告「保持既有副作用面」表述不精确 | `ResumeService#resume` | 报告补留痕 |
| S3-6 | S3 观察 | TOCTOU「极窄窗口」量级措辞偏乐观（窗口=guard→导入实际读盘，含异步拾取延迟，秒级） | `JobService#assertImportFingerprintFresh` | 观察项 4 改措辞 |
| S3-7 | S3 观察 | `EMPTY_REPLY` 的 error 终帧不带 attempts/usage；空回复不自动重试 | `ResilientChatService#emptyReply` | 可接受，留痕 |
| S3-8 | S3 观察 | 施工卡-v2.md 自身 2 处绝对路径命中（指挥官产物，非施工棒责任）；E2E 摘要行/头部注释个别文案未随 TC23 更新 | `T3b-施工卡-v2.md:3,599` / `verify-e2e.ps1:2318` | 移交指挥官/顺手勘误 |

## 7. 攻击结论（逐必审点一句话）

- **A12**：五路攻击（误伤面/帧序/泄漏/记忆污染/非空回归）全部不成立，三形态语义与帧协议自洽，非空路径未动——**PASS 维持**。
- **A21**：七路攻击（JSON 破坏/回放兼容/死循环/整帧口径/序列化复用/误伤/javadoc）不成立，唯一残余是不可截字段边界（S3-3）——**PASS 维持**。
- **A9**：解环真实、时序有据、dismiss 不复活（`resolvePending` 白名单不含 FRONTEND_CANCELLED）、饱和如实回报；两个残余（竞态窗 S3-1、饱和无自愈 S3-2）均为卡内已接受或观察项级——**PASS 维持**。
- **A4**：用例⑤走真实全链（MockMvc→createAndStart→守卫→异步→COMPLETED），非弱断言；TOCTOU 量级措辞需修正（S3-6）——**PASS 维持**。
- **门禁**：346/0/0/0（53 XML）、E2E 28/0/0（EXIT 0）、前端双绿、脱敏零命中——**全部一次复现**。
- **范围**：无漏登、无暗改、H9 未破、ci.yml 干净——**PASS 维持**；报告记账两处错（S2-1）与工作区混流风险（S2-2）须在归档/提交前处置。

**最终裁决：修正后放行**——修正对象为施工报告文本勘误（S2-1）与提交卫生（S2-2，按登记清单显式 add）；9 条目 PASS 与四个必审点结论全部维持，代码、测试、文档回灌均无需返修。
