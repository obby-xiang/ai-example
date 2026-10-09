# M2-T3 韧性机制包 设计红队审查（DeepSeek V4 Pro）

> 角色：M2-T3 设计卡（docs/M2-T3-设计卡.md v1.0）攻击式审查官。对象：工作区唯一新文件——设计卡六项（T3-1~T3-6）。
> HEAD `71b3191`。全程零 git 写操作、零既有文件修改；唯一写操作即本报告文件。
> 证据等级：**【实测】** = 本棒实读代码/实跑命令所得；**【旁证】** = 引用上一轮他方（K2.8 / GLM / 指挥官登记）结论；**【推断】** = 静态推演未实跑；**【假设】** = 设计意图猜测。
> 本棒**未**独立执行后端 `mvn test`，故任何"263 全绿"仅作【旁证】，不作为本棒实测结论。
> 脱敏：路径一律相对仓库根（`<REPO_ROOT>`）或 `docs/...`、`backend/...`；不含任何 API key；正文不复述禁用字面量（规则字面量按 DC-08 拼接构造，占位符 `<...>` 不触发误报）。

---

## 0. 总体判定

**返修后施工。**

六项机制方向大体正确（T3-2 seq 持久化、T3-3 导出落 issue、T3-4 指纹锚定、T3-5 压测定值均可按设计卡返修后施工），但存在 **3 处 S1 级设计缺陷**（T3-1 投递线程模型自相矛盾 + replay 路径漏改；T3-6a 方案 2 前提错误且未触及根因），以及 **1 处登记遗漏**（§13.2 #3/#4/#5/#6 四项被技术方案文档归入 T3 机制包、却未进设计卡与排期 v1.5 T3 批次）。这三处不返修即施工，会直接导致返工（T3-1 慢订阅者隔离目标落空）或线上事故（T3-6a 把重启后全部合法挂起轮判死）。

---

## 1. 结论速览

| # | 级别 | 主题 | 设计卡小节 | 关键证据（文件:行） |
|---|---|---|---|---|
| S1-1 | 设计缺陷 | 投递执行器线程模型未定义且自相矛盾，§13.2 ×2 联动被新执行器打破 | T3-1 | `SseChatEmitter.java:424-446`、`AiProperties.java:56-60` |
| S1-2 | 设计缺陷 | replay 路径仍同步 sendTo 于 emitLock 内，慢重挂者仍阻塞整轮 | T3-1 | `SseChatEmitter.java:122-133,358-375` |
| S1-3 | 设计缺陷 | 方案 2「死闸门判定」前提错误 + 未触根因（confirm 后不 resume） | T3-6a | `StartupResumeRunner.java:124-134`、`ConfirmGate.java:336-340`、`AiController.java:202-231` |
| S2-1 | 建议修改 | 溢出摘除未定义主动 close emitter → 静默丢帧 | T3-1 | `SseChatEmitter.java:458-472` |
| S2-2 | 建议修改 | 队列 256 无依据，未声明「容量<归档窗口」不变量 | T3-1 | `RunStore.java:84` |
| S2-3 | 建议修改 | delta 跳过归档仅在耦合 ai:seq 时安全，取证/计数路径变空 | T3-2 | `SseChatEmitter.java:351-375,389-391` |
| S2-4 | 建议修改 | 「发放即 SET」失败行为未定义，ai:seq 承诺需收窄 | T3-2 | `SseChatEmitter.java:413-422` |
| S2-5 | 建议修改 | resultJson 是死列，写路径/事务口径未指明 | T3-4 | `Job.java:39-40`、`JobProgressService.java:79-90` |
| S2-6 | 建议修改 | 指纹摘要须 fileType 键控；无指纹历史作业兼容未裁决 | T3-4 | `PrecheckJobRunner.java:101-106`、`PrecheckGateGuardTest.java:105-115` |
| S2-7 | 建议修改 | PUBLISH 无文件可指纹，守卫对其语义为空 | T3-4 | `PublishJobRunner.java:158-165` |
| S2-8 | 建议修改 | 压测缺「慢订阅者×并发挂起」，新执行器未入 ×2 联动 | T3-5 | `docs/03-技术方案文档-v2.1.md:644` |
| S2-9 | 建议修改 | 登记遗漏 §13.2 #3/#4/#5/#6 | 范围六项 | `docs/03-技术方案文档-v2.1.md:663` |
| S3-* | 观察 | 见 §4（共 9 条） | — | — |

---

## 2. S1 设计缺陷（必须返工）

### S1-1 T3-1 投递执行器线程模型未定义且自相矛盾

**证据【实测】**：现状 `emit()` 在 `emitLock` 内「取号→落档→遍历订阅者同步 sendTo」（`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:424-446`）。设计卡改为「每订阅者一条有界 FIFO + 单投递执行器按队列顺序 drain」。

**问题【推断】**：所谓「单投递执行器」只有两种可实现形态，两者都与目标冲突：

- 若为**单线程**执行器：drain 到某个慢订阅者队列时 `emitter.send` 阻塞在 TCP 背压上，**整条 drain 线程被卡住**，其余订阅者的队列全部停止 drain → 帧堆积 → 连锁溢出摘除。慢订阅者**没有被隔离**，只是把「阻塞运行线程」换成了「阻塞投递线程」，T3-1 的验收目标「单慢订阅者不阻塞同轮其他订阅者」无法达成。
- 若为**多线程池**：多个线程可能并发 drain **同一订阅者**的队列，破坏单订阅者 FIFO 顺序（S5c-3「到达顺序=seq 顺序」契约降级为「归档顺序」而非「客户端到达顺序」）。要保序必须给每队列加「drain 进行中」独占标记，而设计卡未提。

同时，新执行器容量与 §13.2 #2 的「专用池 × 2 ≤ boundedElastic」联动（`AiProperties.java:56-60` 明确「BLOCKING 每次挂起占专用池 1 条 + boundedElastic 1 条」）**未纳入联动核算**：若新投递执行器本身复用 boundedElastic，慢订阅者风暴会与挂起争抢同一池；若独立建池，又需重新算上界。设计卡对投递执行器的类型、线程数、队列容量、拒绝策略**全部留白**。

**修改建议**：设计卡必须明确投递执行器线程模型（推荐：共享有界线程池 + 每订阅者「drain 进行中」独占标志保序，或按订阅者分配单线程 drain 但限定并发订阅者上界），并把该池容量纳入 §13.2 ×2 联动与 T3-5 压测场景。否则本项不能通过设计评审。

### S1-2 T3-1 replay 路径漏改，慢重挂者仍能阻塞整轮

**证据【实测】**：设计卡只改 `emit()` 的投递；但 `replayAndAttach`（`SseChatEmitter.java:122-133`）与 `replayTo`（:351-355）都调用 `replayLocked`（:358-375），后者在 `emitLock` 内对归档帧逐帧**同步 `sendTo`**。`replayAndAttach` 是 `GET /api/ai/events/{runId}` 重挂的唯一入口（`AiController.java:292`）。

**问题【推断】**：一个慢速（或故意不消费）的重挂客户端，会在 reattach 时持 `emitLock` 同步写出**最多 3000 帧**（`RunStore.java:84` EVENT_WINDOW），期间整轮心跳、业务帧取号/落档、其他订阅者投递全部停摆。页面刷新是正常用户动作，攻击者甚至无需鉴权即可用 `/events/{runId}` 反复触发。**T3-1 的修复只堵了 `emit()` 一条路，慢订阅者仍可通过 reattach 路径把整轮打停**——修复不完整、可绕过。

**修改建议**：replay 路径与 `emit()` 同走异步投递（回放帧入同一队列），或至少把回放的 `sendTo` 移出 `emitLock` 并纳入同样的背压/摘除机制。设计卡必须显式覆盖 replay 路径，否则 S1-1/S1-2 一并返工。

### S1-3 T3-6a 方案 2 前提错误 + 未触根因（重启后 confirm 不 resume）

**证据【实测】**：

- 重启后，`StartupResumeRunner.scan` 对 `resumeable=false` 的轮**只 skip、不落终态、不重建等待循环**（`backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:124-134`）；`ResumeService.resume` 对仍 PENDING 的外部项返回 `PENDING_UNRESOLVED`、不驱动（`ResumeService.java:290-299`）。
- `submitDecision` 的 `woke = signal(runId, toolCallId)`，重启后新进程 `latches` 为空 → `woke=false`（`ConfirmGate.java:336-340`）；`AiController.confirm` 拿到 `woke=false` 后**只返回，不触发 resume**（`AiController.java:202-231`）；前端 confirm 也仅 `this.suspended=false`，不调 resume（`frontend/src/stores/ai.ts:745-756`）。

**问题【推断】**：设计卡开放点假设「重启后无活闸门是瞬态正常」。**该假设对 SUSPENDED+PENDING 轮不成立**——这类轮被 StartupResumeRunner 永久跳过（`PENDING_UNRESOLVED`），既无活闸门、也不会有（除非人工干预），即「快照 SUSPENDED + 无活闸门」是**稳态**而非瞬态。因此：

1. 方案 2（`/runs/current` 对「SUSPENDED + 无活闸门」返 `expired`/`found:false`）会在**任意一次进程重启后，把全部合法挂起待确认的轮判死**，前端不再重建卡片 → 用户连确认入口都没有，轮永久卡死。这是方案 2 直接引入的回归。
2. 更根本：现状下重启后用户即便点确认，`woke=false` 且无任何 resume 触发，决策写进 Redis（APPROVED）但**没人驱动执行**——重建出的确认卡本就是「死卡」。方案 2 只是把死卡**藏起来**，根因（confirm 后不 resume）原样保留。
3. 候选 3「ResumeService 主动落终态」也**不成立**：其一，`scan` 当前并未主动落终态（只 skip）；其二，SUSPENDED+PENDING 是**合法等待态**，落终态等于杀死合法挂起流。

**修改建议（明确倾向）**：**弃方案 2 及三候选**，改为第 4 方案——「外部输入落库且 `woke=false`、快照为 SUSPENDED 时，由确认/回灌端点触发 `ResumeService.resume(runId)`」。此时条目已非 PENDING，`resume()` 会走正常续跑（重建历史→执行 APPROVED 工具），把死卡收敛为真实续跑，死闸门组合天然消失，无需任何「活锁检测」。M1 单实例下 `woke=false` 仅出现在重启恢复路径，触发条件安全。这是本项唯一触根因的方案；方案 2 不可施工。

---

## 3. S2 建议修改

### S2-1 T3-1 溢出摘除须主动 close emitter

**证据【实测】**：`sendTo` 失败只 `subscribers.remove(emitter)` + return false，**不 close/complete emitter**（`SseChatEmitter.java:458-472`）；`attach` 只在 `onCompletion/onTimeout/onError` 才 remove（:105-110）。

**问题【推断】**：现状同步模型下，send 抛异常即客户端已断，无需主动 close。改成异步后，「队列溢出摘除」时客户端**并未断开**，若只从列表 remove 而不 `emitter.complete()`/`completeWithError()`，前端 SSE 连接仍在、只是收不到帧，**不会触发重挂**（前端只在连接断开才 reattach），被摘除的 256 帧永久丢失。设计卡「等价断连」未落地为「主动断连」。

**修改建议**：摘除订阅者时必须显式结束该 emitter（触发前端 onerror/onclose → reattach），并补验收用例「溢出摘除后前端断点续收 seq 无洞」。

### S2-2 T3-1 队列 256 需依据与「容量 < 归档窗口」不变量

**证据【实测】**：归档窗口 `EVENT_WINDOW = 3000`（`RunStore.java:84`）；前端重挂 `lastSeq = archiveMaxSeq`（`frontend/src/stores/ai.ts:533-534`、`AiController.java:445`）。

**问题【推断】**：溢出摘除靠「前端 lastSeq 差量重挂」补帧，前提是被摘除者落后的帧数 < 归档窗口 3000。队列 256 < 3000 当前成立，但设计卡既未给出 256 的取值依据，也未把「队列容量 < EVENT_WINDOW」作为**不变量**显式声明。一旦后续调大队列或调小窗口，补帧完整即被破坏。

**修改建议**：设计卡补一句「队列容量恒小于归档窗口，且加单测锚定该关系」，并给 256 的估算依据（或标注压测后定值、暂挂观察）。

### S2-3 T3-2 delta 跳过归档的「行为无变化」需限定 + 取证路径变空

**证据【实测】**：`replayLocked` 在 `includeDelta=false` 时跳过 delta（`SseChatEmitter.java:363-365`），生产 reattach 走此路径（`replayAndAttach` → `replayLocked(..., false, ...)`）；但 `replayTo(..., includeDelta=true, ...)`（:351-355）是「取证用」，全仓仅测试调用（`grep replayTo` 命中 `SseChatEmitterFramesTest`/`SseFrameSequenceConformanceTest`）；`persistedFrameCount()`（:389-391）读 `events().size()`，全仓无调用方。

**问题【推断】**：① delta 不落档后，`includeDelta=true` 的「取证回放」返回空 delta，取证语义作废（无生产调用方，影响小但应登记）；② `persistedFrameCount` 变低估（死代码，无影响）；③ delta 跳过之所以不引发重启重号，**唯一前提是同一变更里 ai:seq 键已生效**（`nextSeq` 锚点 = max(归档尾, ai:seq)）。设计卡「行为无变化」只在「delta 跳过 + ai:seq 强耦合、同批上线」时成立，必须写死该耦合、禁止拆开施工。

**修改建议**：设计卡明示「delta 跳过归档与 ai:seq 键强耦合，不可拆批」，登记 `includeDelta=true` 取证路径变空与 `persistedFrameCount` 死代码，并核查既有 `SseFrameSequenceConformanceTest`/`SseChatEmitterFramesTest` 是否有依赖 delta 入档的断言需同步调整。

### S2-4 T3-2「发放即 SET」失败行为未定义 + 承诺需收窄

**证据【实测】**：`nextSeq` 惰性读 `store.lastEventSeq`（归档尾帧）（`SseChatEmitter.java:413-422`）；`appendEvent` 落档失败 DEBUG 吞没（`RunStore.java:533-536`）。

**问题【推断】**：① 设计卡未定义 ai:seq `SET` 失败时的行为。若照 `appendEvent` 口径吞没，则「Redis 完全不可用」时 ai:seq 与归档**同时失败**，重启仍重号——ai:seq 只解决「归档 LIST 写失败但 STRING 写成功」的**部分故障**（如内存压力/单键淘汰），不解决整体宕机。承诺「不再依赖落档成功」需收窄为该窄窗。② 每帧 emitLock 内新增一次 SET，出帧 Redis 往返 3→4 次。

**修改建议**：① 设计卡写明 SET 失败日志等级（建议 WARN，区别于 appendEvent 的 DEBUG）、失败后行为（继续出帧，登记「整体 Redis 宕机下重号残留」观察项）；② 承诺改为「在归档写入部分失败（如 maxmemory 淘汰）场景下续号不再依赖落档」；③ 验收用例补「ai:seq SET 失败」分支，而非仅测「落档失败但 SET 成功」。

### S2-5 T3-4 resultJson 是死列，写路径与事务口径未指明

**证据【实测】**：`Job.resultJson`（`Job.java:39-40`，CLOB）全仓 `setResultJson` 零调用（`grep setResultJson` 仅命中字段声明）；`doneEvent` Map 仅用于 SSE `JOB_DONE` 推送（`JobTerminalWriter.java:86`），不落库；`JobProgressService.finish` 只写 status/errorCount/progress/total/finishedAt，不写 resultJson（`JobProgressService.java:79-90`）。

**问题【推断】**：设计卡「落 PRECHECK 结果摘要 JSON（jobs 表既有结果字段，不新建表）」名不副实——列虽存在但**从未被写**。「不新建表」真，但等价于新增一套写入逻辑；且终端写走 `JobProgressService`（`REQUIRES_NEW`），与数据面事务分离，指纹摘要若要在 IMPORT 建作业时（`assertPrecheckPassed` 读 PRECHECK 作业）可见，必须在 PRECHECK 自己的 `@Transactional` 数据面事务内写 resultJson 并随批提交。设计卡未指明「谁写、何时写、哪个事务」。

**修改建议**：设计卡明确 resultJson 由 PrecheckJobRunner 在数据面事务内 `setResultJson` + `jobRepository.save`（或经 JobProgressService 扩展 finish 签名），并说明与 `afterCommit` 终态写的先后关系，避免「IMPORT 建作业时 PRECHECK 摘要尚未提交」的竞态。

### S2-6 T3-4 指纹摘要须 fileType 键控 + 无指纹历史作业兼容未裁决

**证据【实测】**：`PrecheckJobRunner` 读盘回落分支——先取 UPLOAD，空则取 EXPORT（`PrecheckJobRunner.java:101-106`）；`ImportJobRunner` 只读 UPLOAD、无回落（`ImportJobRunner.java:99-104`）；`PrecheckGateGuardTest.latestPassingPrecheckAllowsImportAndPublish` 造一条无指纹（resultJson=null）的 COMPLETED PRECHECK 并断言 IMPORT 放行（`PrecheckGateGuardTest.java:105-115`）。

**问题【推断】**：① 设计卡「各 defCode 上传文件指纹」未区分 fileType。若 PRECHECK 因无 UPLOAD 而回落到 EXPORT 并记了 EXPORT 指纹，而 IMPORT 读 UPLOAD，则摘要须以 `(defCode, fileType)` 键控，`assertPrecheckPassed` 须比对下游 runner **实际读取**的 fileType；否则「PRECHECK 记 EXPORT、IMPORT 读 UPLOAD」会恒 409。② 「历史无指纹 PRECHECK 放行还是强制重检」未裁决：若强制重检，上述既有通过用例会变红（需改测）；若放行，旧作业的替换窗口依旧敞开，T3-4 目标对存量任务落空。

**修改建议**：摘要结构定为 `{defCode: {fileType: {sha256,size}}}`；守卫对 IMPORT 比对 UPLOAD 指纹、对 PRECHECK 摘要缺失该条目时按「未预检查」从严 409；对「历史 PRECHECK 无指纹」给出明确兼容裁决（建议从严：无指纹视为 stale，强制重预检查，并同步改 `PrecheckGateGuardTest` 补指纹）。

### S2-7 T3-4 PUBLISH 无文件可指纹，守卫对其语义为空

**证据【实测】**：`PublishJobRunner.doPublish` 读 `ConfigStagingRow`（`PublishJobRunner.java:158-165`），不读任何 `task_files`；文件替换窗口只影响 IMPORT（读 UPLOAD）。

**问题【推断】**：设计卡「IMPORT/PUBLISH 409 PRECHECK_STALE」对 PUBLISH 无可比对的「当前文件」。若强行对 PUBLISH 也算 UPLOAD 指纹，则「预检查后替换上传件、但未重新导入」会在 PUBLISH 时误 409（而 PUBLISH 发布的是暂存数据，与上传件无关）。PUBLISH 的完整性已由「IMPORT 守卫 + baseVersion 冲突检测」双重覆盖。

**修改建议**：指纹守卫**仅对 IMPORT 生效**（`assertPrecheckPassed` 内按 jobType 区分）；PUBLISH 维持既有 `PRECHECK_NOT_PASSED` 状态守卫即可，不叠加指纹比对。

### S2-8 T3-5 压测缺「慢订阅者×并发挂起」，新执行器未入 ×2 联动

**证据【实测】**：§13.2 #2 明确「压测范围须含 boundedElastic 容量与专用池容量的联动校验」（`docs/03-技术方案文档-v2.1.md:644`）；观测面 `/api/ai/health` 暴露 `suspendPoolSize`/`activeSuspendGates`/`resilience` 四值，但**无投递执行器池指标**（`AiController.java:530-538`）。

**问题【推断】**：① 四场景（A~D）未覆盖「慢订阅者 + 并发挂起」组合——正是 T3-1 新执行器与挂起争抢 boundedElastic 的最坏态；② 新投递执行器的池容量/队列深度未纳入 ×2 联动校验，§13.2 #2 的「定值」结论会在 T3-1 上线后被打破；③ 观测面缺新执行器指标，压测时无法看到 drain 堆积/摘除。

**修改建议**：压测场景增补「N 路 CONFIRM 挂起 + M 路慢订阅者」矩阵；`/health` 增投递执行器 poolSize/active/queueDepth/evicted 计数；×2 联动公式改为「专用池 × 2 + 投递池 ≤ boundedElastic 上界」并实测校验。

### S2-9 登记遗漏：§13.2 #3/#4/#5/#6 未进设计卡

**证据【实测】**：技术方案文档 §13.2 修订注记明示「#3 空回复即失败、#4 会话 TTL 绝对上限、#5 Redis 淘汰策略、#6 单键消息体上限——四项均为参数级/机制级取值，随 T3（先设计卡后施工）一并裁定」（`docs/03-技术方案文档-v2.1.md:663`）；而排期 v1.5 T3 批次仅条目 8-11（`docs/M2-排期计划.md:41-46`），设计卡范围六项也仅 T3-1~T3-6，四项均无归属。

**问题【推断】**：四项与 T3 强相关——#6（单键消息体上限）直接约束 `ai:events` LIST 3000 帧窗口与 `ai:seq` 键；#5（淘汰策略）决定 ai:seq 键在内存压力下是否比归档 LIST 更耐久（S2-4 的前提）；#4（TTL 绝对上限）影响 seq 键与归档的「谁后过期」问题。四项在「随设计卡一并裁定」的语境下被设计卡漏掉，属登记缺口，会造成排期与文档结论漂移。

**修改建议**：二选一——(a) 设计卡扩为七项，把 #3/#4/#5/#6 纳入范围并给裁定；或 (b) 在排期 v1.5 与技术方案文档 §13.2 同步一条修订注记，把四项重锚至 T4 台账包并说明理由。无论哪种，必须消除「文档说随 T3、计划没排 T3」的矛盾。

---

## 4. S3 观察

| # | 主题 | 证据（文件:行） |
|---|---|---|
| S3-1 | T3-3 设计卡参照「PrecheckJobRunner:109-118 事务口径」不精确——更贴切的模板是 `ImportJobRunner:169-180`（已实现同款项级 issue 空 rowKey 落库）；建议改引 | `ImportJobRunner.java:169-180` |
| S3-2 | T3-3 ExportJobRunner 补 issue 时应同时 `issueRepository.deleteByJobId` 幂等（对齐 PrecheckJobRunner:73），且外层 catch（整作业失败，`ExportJobRunner.java:169-175`）也应落一条作业级 issue | `PrecheckJobRunner.java:73` |
| S3-3 | T3-3 500 字符截断与既有 Import/Precheck 全量 message 口径不一致；`e.getMessage()` 对 NPE 等常为 "null" 不足以定位（建议带异常类名，完整栈留日志）；`ValidationIssue.message` 列宽 1024，可放宽到 1024 | `ValidationIssue.java:31` |
| S3-4 | T3-3「前端零改动」依赖「前端已消费 Import 项级 issue」这一事实，本棒未读前端 issue 渲染分组，标【推断】待施工前 smoke 确认 | `frontend/src`（未读） |
| S3-5 | T3-4 `FileStorageService.read` 是 `Files.readAllBytes` 非流式，「流式读成本≈0」表述错误；守卫新增一次全量重读（百 MB 级），应注明成本 | `FileStorageService.java:39-41` |
| S3-6 | T3-2 ai:seq 键 SET 是否带 TTL/续期未明；须与归档同寿（每帧 emit 同刷），否则 ai:seq 先过期 → 重号 | `RunStore.java:522-537` |
| S3-7 | T3-6a `hasLiveGate` 若按 `runId|` 前缀扫 `latches` 全表为 O(N)，规模大时应加 runId→count 索引（与现有 `pendingGates()` 同源） | `ConfirmGate.java:88,445-447` |
| S3-8 | T3-6b 桩 `start_publish` 在 `stub-upstream.mjs:700` 仅为自检帧形态断言，非完整挂起 E2E；施工仍需新造「真实挂起→刷新→confirm→收敛」用例（设计卡已承认） | `scripts/ci/stub-upstream.mjs:700` |
| S3-9 | T3-1 心跳走队列后，「心跳节奏」= 投递节奏，受 drain 影响；`touchActivity` 应留在 emitLock（同步），需在设计中写明，否则僵尸判定输入会随投递延迟漂移 | `RunStore.java:548-555` |

---

## 5. T3-6a 开放点：明确倾向

结论：**三候选全部否决，改采第 4 方案「外部输入 woke=false + SUSPENDED → 触发 resume」**。

- 候选 1（宽限窗）：建立在「无活闸门是瞬态」的错误前提上；对 SUSPENDED+PENDING 轮是稳态，宽限窗只是把误杀推迟 60s，不改变结论。否决。
- 候选 2（活动戳联合判定）：SUSPENDED+PENDING 轮在重启后无 await 循环、无心跳，`lastEventAtMs`/`ai:beat` 停留进程死前，活动戳同样判「死」，与「无活闸门」等价。否决。
- 候选 3（ResumeService 主动落终态）：`StartupResumeRunner.scan` 现状只 skip 不落终态（`StartupResumeRunner.java:124-134`）；且 SUSPENDED+PENDING 是合法等待态，落终态会杀死正常挂起流。否决。
- 推荐第 4 方案：`ConfirmGate.submitDecision`/`submitFrontendResult` 在 `woke=false` 且快照 `SUSPENDED` 时，回调 `ResumeService.resume(runId)`。此时条目已 APPROVED/RESULT（非 PENDING），`resume()` 走正常续跑，重建卡即活、决策即执行，死闸门组合天然不存在。M1 单实例下 `woke=false` 仅现于重启恢复，安全。

---

## 6. 依赖顺序与施工拆分建议

- **硬依赖**：T3-2 的「delta 跳过归档」强依赖同批的「ai:seq 键」，禁止拆开施工（S2-3/S1 关联）。T3-4 的指纹守卫强依赖 resultJson 写路径先落地（S2-5）。
- **建议拆分**：T3-2 与 T3-1 可并行，但 T3-1 必须先补线程模型 + replay 路径（S1-1/S1-2）再施工；T3-6a 需先返修为第 4 方案再施工。T3-5 应后置于 T3-1（否则压测定值缺新执行器维度，S2-8）。
- **是否再拆 T3a/T3b**：建议 T3 拆为 **T3a（SSE 韧性：T3-1 + T3-2，含投递执行器与 seq 持久化）** 与 **T3b（作业/机制：T3-3 + T3-4 + T3-6a/b + 遗漏四项）**。T3-5 作为 T3a 的压测收尾，T3-6b 作为 T3b 的验证收尾。理由：T3a 改的是 AI 运行时热路径，风险独立；T3b 是作业治理面，改动面与回归面分离。

## 7. 验收标准可测性补遗

- T3-1 验收「挂起期心跳间隔偏差 < 1s」在异步队列下应改为「投递节奏」口径，且须定义「慢订阅者已被摘除」后的测量边界（S3-9）。
- T3-2 验收「delta 不进归档（LLEN 只计状态帧）」可测，但须补充「ai:seq SET 失败」分支用例（S2-4）。
- T3-4 验收须补「历史无指纹 PRECHECK」的兼容用例（放行/重检二选一，S2-6）与「PRECHECK 回落 EXPORT 后 IMPORT 读 UPLOAD」的 fileType 交叉用例（S2-6）。

---

## 8. 脱敏自查

- 本报告未含 API key；路径均为 `docs/...`、`backend/...`、`frontend/...`、`scripts/...` 相对形式；未出现本机绝对路径；正文未复述禁用字面量（规则字面量按 DC-08 拼接构造，占位 `<...>` 不触发误报）；HEAD 以短哈希 `71b3191` 引用。
- 证据等级自证：全部「代码 文件:行」均本棒【实测】Read/Grep 所得；`263 全绿`未自跑，故不标注【实测】，仅在门禁语境引用时作【旁证】。
