# M2-T3 韧性机制包 设计卡 v1.1 复核（GLM-5.3）

> 角色：M2-T3 设计卡 v1.1 复核官。对象：指挥官返修版 `docs/M2-T3-设计卡.md` v1.1 对红队（`docs/evidence/M2-T3-设计红队-DS-V4-Pro.md`，判定"返修后施工"，3×S1 + 9×S2 + 9×S3）21 条的闭合情况。
> HEAD `71b3191`。全程零 git 写操作、零既有文件修改；唯一写操作即本报告文件。
> 证据等级：**【实测】** = 本棒实读代码/文档所得；**【旁证】** = 引用红队/设计卡结论；**【推断】** = 静态推演未实跑。
> 本棒未执行 `mvn test`，"263 全绿"仅作【旁证】。
> 脱敏：路径一律相对仓库根（`docs/...`、`backend/...`、`frontend/...`、`scripts/...`）；无 API key；无本机盘符绝对路径；正文不复述禁用字面量。

---

## 0. 总体判定（详见 §5）

**有条件通过。**

21 条红队意见全部被 v1.1 显式处置且绝大部分为实质闭合（非文字应付）；但复核发现 **1 处 v1.1 新引入的设计内部矛盾（R1：replay 回放帧并入 256 容量队列）**，不补裁决即施工 T3-1 会在验收阶段直接返工。条件：设计卡补一小节 R1 容量裁决后 T3a 可施工；R2~R5 为施工注记随施工落实，不阻塞。

---

## 1. 21 条逐条闭合表

处置列引设计卡 v1.1 小节；证据列"实测"均本棒 Read/Grep 所得。

| # | 级别 | 红队要点 | v1.1 处置 | 闭合判定 | 证据 |
|---|---|---|---|---|---|
| S1-1 | S1 | 投递执行器线程模型未定义/自相矛盾，未入 ×2 联动 | T3-1.2：共享有界投递池（容量初值 4，压测定值）+ 每订阅者 CAS drain 独占标志 + 「专用池×2+投递池 ≤ boundedElastic」联动公式纳入 §13.2#2 与 AiProperties 注释 | **闭合**（附施工注记 R2/R3，见 §3） | 【实测】`AiProperties.java:56-60` 现注释即 ×2 联动口径，扩公式兼容 |
| S1-2 | S1 | replay 路径漏改，慢重挂者持锁同步写出阻塞整轮 | T3-1.3：replay 并轨——锁内只读收集（LRANGE）+创建订阅者+回放帧入队首+挂订阅，网络写出统一走异步队列 | **部分闭合**：锁内阻塞消除【实测】可成立；但回放帧量与队列容量的矛盾未解决（新风险 R1，见 §2b/§3） | 【实测】`SseChatEmitter.java:122-133,351-375` 现状即锁内同步 sendTo |
| S1-3 | S1 | 方案 2 前提错误（SUSPENDED+无活闸门重启后是稳态）+ 未触根因 | T3-6a：弃三候选与 hasLiveGate 方案，采红队第 4 方案（`woke=false` + 快照 SUSPENDED → `ResumeService.resume(runId)`） | **闭合**（附施工注记 R4：循环依赖/触发点/毫秒窗口，见 §2c/§3） | 【实测】红队引用的四处现状全部复核属实（`StartupResumeRunner.java:124-134`、`ResumeService.java:290-299`、`ConfirmGate.java:336-340`、`AiController.java:202-231`） |
| S2-1 | S2 | 溢出摘除未主动 close → 静默丢帧 | T3-1.4：摘除显式 `emitter.complete()`，补"摘除后断点续收 seq 无洞"用例 | 闭合 | 【实测】`SseChatEmitter.java:458-472` 现状只 remove 不 close，确需修订 |
| S2-2 | S2 | 队列 256 无依据、未声明容量<窗口不变量 | T3-1.5：容量<归档窗口 3000 不变量+单测锚定；256 为初值、T3-5 压测定值 | 闭合 | 【实测】`RunStore.java:84` EVENT_WINDOW=3000；`frontend/src/stores/ai.ts:533-534` 重挂 `lastSeq=archiveMaxSeq` 属实 |
| S2-3 | S2 | delta 跳过归档与 ai:seq 强耦合须写死；includeDelta 取证路径变空 | T3-2.4/5：强耦合声明禁拆批；`replayTo(includeDelta=true)` 死路径删除；`persistedFrameCount` 语义变化登记；施工前必查四个既有帧协议测试 | 闭合 | 【实测】Grep 全仓：`replayTo` 生产零调用、测试调用全部传 `false`（ChatTurnFrameSequenceConformanceTest:147、SseChatEmitterFramesTest:151/169、ReattachLastSeqConformanceTest 多处、SseFrameSequenceConformanceTest:234）；`persistedFrameCount` 零调用方。删除裁决与代码现实一致 |
| S2-4 | S2 | SET 失败行为未定义、承诺过宽 | T3-2.3：WARN 级+继续出帧+承诺收窄为"归档部分失败场景"+整体宕机重号登记观察项+SET 失败分支用例 | 闭合 | 【实测】`RunStore.java:533-536` appendEvent 失败 DEBUG 吞没、`SseChatEmitter.java:413-422` nextSeq 惰性读归档尾，红队前提属实 |
| S2-5 | S2 | resultJson 死列、写路径/事务口径未指明 | T3-4.1：PrecheckJobRunner 数据面事务内 `setResultJson`+`save`，先于 afterCommit 终态提交 | 闭合（事务时序深核成立，见 §2e） | 【实测】`Job.java:39-40` CLOB 死列；`setResultJson` 全仓零调用；`PrecheckJobRunner.java:59-60` `@Transactional`；`JobTerminalWriter.java:67-90` afterCommit+REQUIRES_NEW |
| S2-6 | S2 | 指纹须 fileType 键控；历史无指纹兼容未裁决 | T3-4.2/4：`{defCode:{fileType:{sha256,size}}}` 键控；历史无指纹从严 409 PRECHECK_STALE 视同未预检查，同步改既有用例 | 闭合 | 【实测】`PrecheckJobRunner.java:101-106` 回落 EXPORT、`ImportJobRunner.java:100-104` 只读 UPLOAD 无回落、`PrecheckGateGuardTest.java:104-115` 无指纹放行用例——与设计卡改法吻合 |
| S2-7 | S2 | PUBLISH 无文件可比对，守卫语义为空 | T3-4.3：指纹守卫仅 IMPORT 生效，PUBLISH 维持既有状态守卫 | 闭合 | 【实测】`JobService.java:115-130` `assertPrecheckPassed(taskId, jobType)` 已带 jobType 参数可分支；`PublishJobRunner.java:158-165` 读 staging 不读 task_files |
| S2-8 | S2 | 压测缺"慢订阅者×并发挂起"、新池未入联动、观测面缺指标 | T3-5：新增 E 场景「N 挂起 × M 慢订阅者」矩阵；`/api/ai/health` 增投递池 poolSize/active/queueDepth/evicted；联动公式扩为「专用池×2+投递池 ≤ boundedElastic」 | 闭合 | 【实测】`AiController.java:520-546` health 现无投递池指标，增项兼容；`docs/03-技术方案文档-v2.1.md:644` §13.2#2 原文核实 |
| S2-9 | S2 | §13.2 #3/#4/#5/#6 登记遗漏 | T3-7~T3-10 四项裁定纳入范围（红队建议方案 a）；拆分 T3a/T3b 采纳 | 闭合 | 【实测】`docs/03-技术方案文档-v2.1.md:663` 修订注记原文核实（见 §4 范围核查） |
| S3-1 | S3 | 事务模板改引 ImportJobRunner | T3-3.1 改引 `ImportJobRunner.java:169-180` | 闭合 | 【实测】该处即项级 issue 空 rowKey 落库先例（catch 内 issue.setJobId/defCode/severity/save） |
| S3-2 | S3 | 幂等 deleteByJobId + 外层 catch 落作业级 issue | T3-3.1/3 均采纳 | 闭合 | 【实测】`PrecheckJobRunner.java:73` deleteByJobId 先例、`ExportJobRunner.java:169-175` 外层 catch 现状只落终态不落 issue |
| S3-3 | S3 | 500 截断与既有口径不一致；message 列宽 1024 | T3-3.2：异常类名+摘要、1024 用足不硬截、完整栈留日志 | 闭合 | 【实测】`ValidationIssue.java:31` message 列宽 1024 属实 |
| S3-4 | S3 | 前端零改动是推断 | T3-3.4：显式标【推断】+施工前 smoke 确认 | 闭合 | 【旁证】前端 issue 渲染本棒亦未读，维持推断口径正确 |
| S3-5 | S3 | "流式读成本≈0"表述错误 | T3-4.5：撤回表述，改 DigestInputStream 流式 digest，成本实测记录 | 闭合 | 【实测】`file/FileStorageService.java:39-41` 即 `Files.readAllBytes` 非流式，撤回正确 |
| S3-6 | S3 | ai:seq TTL 须与归档同寿 | T3-2.1：每帧 emit 同刷 TTL=session-ttl，与 ai:events 同刻过期 | 闭合 | 【实测】`RunStore.java:522-537` appendEvent 每帧 `expire(eventsKey, ttl())`，同刻可实现 |
| S3-7 | S3 | hasLiveGate O(N) | 随第 4 方案作废 | 闭合（作废合理） | 【旁证】方案 4 确无活闸门检测 |
| S3-8 | S3 | 桩 :700 非完整挂起 E2E | T3-6b：新造"真实挂起→刷新→重建→confirm→收敛"E2E，27→28+ | 闭合 | 【实测】`scripts/ci/stub-upstream.mjs:700` 一带确为 start_publish 帧形态自检（JSON 字段断言），非完整 E2E |
| S3-9 | S3 | touchActivity 应留 emitLock | T3-1.1 显式写明"touchActivity 留锁内同步" | 闭合 | 【实测】`RunStore.java:548-555`、`SseChatEmitter.java:436` 现状即在锁内 |

小结：21 条中 19 条闭合、1 条部分闭合（S1-2，受新风险 R1 拖累）、1 条闭合但带施工注记（S1-1，R2/R3）。无"未处置"或"文字应付"条目；未被采纳的原方案（T3-6a 三候选、hasLiveGate）均已给出实读依据的裁决理由。

---

## 2. 关键闭合点深核（a~e）

### a. S1-1 修订（共享有界投递池 + 每订阅者 CAS drain 独占）可落地性【实测+推断】

**对照现状**：`SseChatEmitter.java:424-446` 的 `emit()` 现为 emitLock 内「取号→落档→遍历同步 sendTo」。v1.1 改为锁内只做取号/落档/touchActivity/入队，投递移出锁——与现状结构兼容，改造面集中在 emit 尾部循环与新增投递组件，可落地。

**drain 与 emit 入队的竞态（丢 drain 窗口）**：v1.1 写「入队后 CAS 抢占 drain 权，抢不到则由在跑 drain 者带走新帧」。该模式存在一个经典窗口：drain 者判队列空 → 入队者 CAS 失败（标志仍 true）→ drain 者清标志退出 → 新帧无人投递。闭合该窗口要求固定顺序——**drain 者必须"先清独占标志、再复查队列非空则重新 CAS 抢回"**（清标志后的竞态由 emit 侧 CAS 与 drain 侧复查二者必有其一接手）。v1.1 未写明这一顺序约束，属施工指引缺口（R2）：方案本身正确，但施工者若按"poll 到空即退"实现会引入偶发丢投递。**结论：模型可落地，设计卡需补一句 drain 收尾复查的顺序约束。**

另有一处语义含糊（R3）：「共享有界投递池（容量初值 4）」未指明 4 是线程数还是含任务队列总界；「溢出即摘除该订阅者」在池维度（提交 drain 任务被拒时摘谁）与订阅者队列维度（256 满摘除自己）之间口径未分。慢连接最多占 4 条 drain 线程（CAS 独占保证每连接一条），第 5 路并发慢连接时池行为需要明确定义。

### b. S1-2 修订（replay 锁内收集入队首、写出走队列）原子交接【实测+推断】

对照 `:122-133`（replayAndAttach）与 `:351-375`（replayTo/replayLocked）现状：v1.1 的锁内序列「LRANGE 只读收集 → 创建订阅者队列 → 回放帧入队首 → attach 挂订阅」与现状的原子交接语义**保持**——attach 之后的新帧走 emit 入队，天然排在回放帧之后，FIFO 由队列保证；锁内无网络 IO 写，慢重挂者不再阻塞 emit，红队 S1-2 的阻塞关切被消除。

**但发现 v1.1 内部矛盾（R1，§3 详述）**：全量回放最多 3000 帧（EVENT_WINDOW），远超每订阅者队列容量 256——「回放帧入队首」与「队列满即摘除」直接冲突，验收项「慢重挂者回放 3000 帧不阻塞 emit」按 256 容量实现不可达成（reattach 客户端会被立刻摘除断连）。前端虽常规带 `lastSeq=archiveMaxSeq`（`frontend/src/stores/ai.ts:533-534`【实测】）差量回放，但断连窗口期产帧可超 256，且 `AiController.events` 的 `lastSeq` 可选（不传即全量）。**该矛盾必须补裁决**，可选方向：回放初始批豁免有界容量（队列初始容量=回放帧数、上界=EVENT_WINDOW，仅实时段受 256 约束）；或回放分批入队、由 drain 进度驱动续灌；或回放首帧直写+其余入队。

### c. S1-3 修订（第 4 方案：woke=false + SUSPENDED → ResumeService.resume）【实测+推断】

**触发路径可行性**：`ConfirmGate.submitDecision`（`gate/ConfirmGate.java:315-342`）落库后 `woke = signal(...)`（:336-340 锁内）；`ResumeService.resume` 的准入过滤只拦 `status==PENDING` 的外部项（`ResumeService.java:290-299`）——submitDecision 已把条目置 APPROVED/REJECTED（非 PENDING），resume 会通过准入进入 :305-333 重建执行段（`resolvePending` 对 APPROVED 且 executed=false 补执行）。**路径成立**【实测代码结构+推断执行】。

**正常同进程 confirm（woke=true）不误触发**：条件即 `woke==false`，同进程活闸门被 signal 唤醒时 woke=true，不触发 ✔。

**重入/递归**：submitDecision（HTTP 线程）→ resume 不会回到 submitDecision（resume 走执行侧 SpToolCallingManager，不重入提交侧）；若续跑中模型再发 confirm 工具，那是新挂起的新条目，语义正常。无递归风险。

**但发现三个施工缺口（R4）**：
1. **构造器循环依赖**【实测】：ConfirmGate 若直接注入 ResumeService，则 ConfirmGate→ResumeService→SpToolCallingManager（`SpToolCallingManager.java:101,120` 构造依赖 ConfirmGate）→ConfirmGate 成环，Spring 默认拒绝。AiController 已有 `ObjectProvider<ResumeService>` 惰性注入先例（`web/AiController.java:117`），施工须沿用；设计卡未提示。
2. **触发点与异步化**【推断】：resume 的同步段含快照重建与 `resolvePending` 工具执行（:301-333 在 `suspendExecutor.execute` 之前），HTTP 线程内直调会拖长 confirm 响应至工具执行完；且若在 `submitLock` 内触发会拉长该锁持锁时间（等待侧 `await` :175 与提交侧同锁）。宜在 submitLock 外、异步（suspendExecutor 或投递池）触发。设计卡未写明。
3. **毫秒级双驱动窗口**【实测+推断】：红队「M1 单实例下 woke=false 仅现于重启恢复」不严密——`SpToolCallingManager.java:162` `saveSuspended`（写 SUSPENDED 快照）到 `ConfirmGate.await` :152 `latches.put` 之间有数帧间隔（suspended/toolStart/beacon），此窗口内外部 confirm 到达会 ACCEPTED 且 `woke=false`（闸门未注册）+ 快照已 SUSPENDED，误触发条件全部满足。缓解因素：`RunStore.claimExecution` 原子认领保证工具恰好一次执行【旁证，ResumeService javadoc :47-50 口径】；但原循环与 resume 循环可能并发两次模型调用（帧不乱序——同把 emitLock，seq 单调——但内容重复）。建议触发前校验本进程无该 runId 活跃等待（latches/beacon），设计卡未覆盖。

### d. T3-2 深核【实测】

- **ai:seq 与归档同寿**：`RunStore.appendEvent`（:522-537）每帧 `expire(eventsKey, ttl())`；ai:seq 每帧 SET+TTL 同刷即可同刻（毫秒级偏差），可行。
- **SET 失败 WARN、delta 禁拆批**：均已在 T3-2.3/4 写死，口径完整（含 Redis 往返 3→4 的隐含成本由"发放即 SET"接受）。
- **includeDelta 死路径删除的读者盘点**：Grep 全仓，`replayTo` 调用仅 4 个测试文件且**全部传 false**（ChatTurnFrameSequenceConformanceTest:147、SseChatEmitterFramesTest:151/169、ReattachLastSeqConformanceTest:53/88/102/127/149/242、SseFrameSequenceConformanceTest:234）【实测】；`persistedFrameCount` 零调用方【实测】；`store.events()` 生产读者仅 SseChatEmitter 两处（:359 replayLocked、:390 persistedFrameCount），与设计卡 §6"events 端点全量读是回放唯一读者路径"一致。**无遗漏读者**；设计卡"施工前必查四个帧协议测试"清单恰好覆盖全部受影响调用点，SseChatEmitterFramesTest:168 注释（"delta 按 includeDelta=false 跳过"）delta 不入档后断言数需同步改，已在必查范围。
- 唯一关联注意：R1 的回放容量裁决会影响 replayLocked 的收集段实现（收集上限=窗口），与 T3-2 无冲突但同文件施工，建议同批。

### e. T3-4 深核【实测】

- **键控结构**：`PrecheckJobRunner.java:101-106` 先 UPLOAD 空则回落 EXPORT、`ImportJobRunner.java:100-104` 只读 UPLOAD 无回落——与设计卡"PRECHECK 可能记 EXPORT、IMPORT 读 UPLOAD，须按各自 fileType 取指纹"的判断完全一致，`{defCode:{fileType:{sha256,size}}}` 键控是对症解。
- **仅 IMPORT 生效**：`JobService.assertPrecheckPassed`（:115-130）已按 `jobType` 参数分支（:116 现拦 IMPORT/PUBLISH 两种），改法兼容；`PublishJobRunner.java:158-165` 读 `ConfigStagingRow` 不读 task_files，PUBLISH 维持状态守卫的裁决与代码现实一致。
- **历史从严 409**：`PrecheckGateGuardTest.java:104-115` 造无指纹 COMPLETED PRECHECK 断言 IMPORT 放行——设计卡已登记"同步改既有受影响用例"，无遗漏。
- **resultJson 事务时序**：`PrecheckJobRunner.run` 为 `@Transactional`（:59-60），指纹摘要在该数据面事务内 setResultJson+save；`JobTerminalWriter.complete`（:65-77）在事务同步 active 时注册 afterCommit、`apply` 走 REQUIRES_NEW（:79-91）——摘要提交必然先于终态写，IMPORT 建作业（`assertPrecheckPassed` 读 PRECHECK 作业）时摘要可见，**竞态闭合成立**【实测结构+推断时序】。

---

## 3. 新引入风险清单（v1.1 相对 v1.0 新内容）

| # | 级别 | 风险 | 处置建议 |
|---|---|---|---|
| R1 | **S2（返修项）** | T3-1.3「回放帧入队首」与 T3-1.5「队列容量 256、溢出即摘除」矛盾：全量回放最多 3000 帧 >> 256，reattach 客户端会被立刻摘除断连，验收「回放 3000 帧不阻塞 emit」不可达成。差量回放（前端带 lastSeq）常态帧数小，但断连窗口期产帧可超 256，且 `lastSeq` 可选参数不传即全量【实测 AiController.java:284-285】 | **施工前设计卡补一小节裁决**：回放初始批豁免有界容量（上界=EVENT_WINDOW）/ 分批入队 / 首帧直写+余帧入队，三选一并写明与「容量<窗口」不变量的关系（建议：不变量只约束实时段，回放段以窗口为上界，单测同时锚定两者） |
| R2 | S3（施工注记） | drain 收尾竞态闭合细节未写：需「先清独占标志、再复查队列非空则重抢」的顺序约束，否则存在丢 drain 窗口（入队者 CAS 失败、drain 者恰好退出） | 施工时按该顺序实现并加并发单测（入队与 drain 收尾交叠） |
| R3 | S3（施工注记） | 投递池「容量 4」含糊（线程数 or 总界）；池满提交被拒时摘除对象未定义；慢连接占满 4 条 drain 线程后其余队列的 drain 任务排队行为未写 | 施工时明确：4=线程数、池任务队列策略、拒绝时摘除触发本次提交的订阅者；纳入 T3-5 场景 E 实测 |
| R4 | S2/S3（施工注记） | 第 4 方案三缺口：①构造器循环依赖（ConfirmGate↔ResumeService 经 SpToolCallingManager 成环，Spring 默认禁）须 ObjectProvider 惰性注入；②触发点应在 submitLock 外且异步化（resume 同步段含工具执行）；③saveSuspended→latch 注册毫秒窗内同进程 confirm 也满足 woke=false+SUSPENDED，可能双模型循环（工具执行由 claimExecution 幂等兜底） | ①②施工时落实（AiController 已有 ObjectProvider 先例）；③建议触发前校验本进程无活跃等待/执行，或接受双驱动并登记观察项（claimExecution 保证恰好一次执行） |
| R5 | S3（施工注记） | T3-10「64KB/帧超限截断+标记」未指明截断层级：帧字节级截断会破坏 JSON，`RunStore.events()` 反序列化失败帧被静默忽略（:629）→ 回放丢帧=seq 洞 | 必须字段级截断（如 result/text 值截断+truncated 标记），禁止截帧字节；施工时落为裁定细则 |
| R6 | 观察 | 编号空间重叠：技术方案 :663 注记的「T3-8」指排期 T3 批次**条目 8**（SSE 扇出），与设计卡 T3-8（TTL 裁定）同名异义 | 施工与证据引用时加"排期条目/设计卡条目"前缀，防误读 |

T3-7~T3-10 裁定方向本身与代码现实无矛盾：T3-7 终态全集与 `RunSnapshot.java:30-39` 常量（RUNNING/SUSPENDED/DONE/FAILED/CANCELLED）一致【实测】；T3-8 预判正确——`RunStore.touchActivity`（:548-555）现即滑动 TTL 续期，会突破"创建+6h"，加绝对上限字段的预案必要【实测】；T3-9 noeviction 与 S2-4 承诺收窄口径自洽（无淘汰下 ai:seq 与归档同耐久，仅整体宕机重号）。

---

## 4. 范围完整性【实测】

对照 `docs/03-技术方案文档-v2.1.md:663` 修订注记与 `docs/M2-排期计划.md` v1.5 T3 批次（条目 8-11）：

- **#3/#4/#5/#6 → T3-7/T3-8/T3-9/T3-10**：技术方案注记原文「#3、#4、#5、#6 → T3 机制包（本轮新建）……随 T3（先设计卡后施工）一并裁定」——设计卡 v1.1 扩为十项正是该注记的落实，四项均有初裁方向与"施工时验证落锤"口径。**无遗漏**。
- **排期条目 8-11 全覆盖**：条目 8（R1 慢订阅者）→ T3-1；条目 9（R2 seq 持久化 / 导出失败落 issues）→ T3-2 + T3-3；条目 10（预检查替换窗口）→ T3-4；条目 11（§13.2#2 压测）→ T3-5。
- **非 T3 项各归其位**：#8→T2-6、#10→T2-4、#12→排期条目 8（SSE 扇出，含于 T3-1）、#13→M2 激活包、#9→接入推理模型时（:663 注记原文核实），均无需纳入本卡。
- **T3-6**（T2b 转入：死卡兜底 + CONFIRM 卡验证）为设计卡 v1.0 既有范围、v1.1 保留并按第 4 方案重构，登记口径自洽。
- 技术方案"文档说随 T3、计划没排 T3"的矛盾（红队 S2-9）已通过方案 a（设计卡扩十项）消除：归属锚点是"T3 机制包"概念，设计卡即其细化载体，排期条目粒度无需改动。

---

## 5. 总体判定

**有条件通过。**

- 21 条红队意见全部显式处置，19 条实质闭合、闭合质量整体良好；S1-1/S1-2/S1-3 三个重构方向经代码深核均与现状兼容、方案正确。
- **条件（施工前必办）**：设计卡补 R1 一小节——replay 回放帧与订阅者队列容量的裁决（含与「容量<窗口」不变量的关系），否则 T3-1 验收项「回放 3000 帧不阻塞 emit / 摘除后续收 seq 无洞」自相矛盾，施工即返工。
- **随施工落实（不阻塞）**：R2（drain 收尾顺序）、R3（池拒绝语义）、R4（第 4 方案三缺口：ObjectProvider 解环、锁外异步触发、毫秒窗口校验）、R5（T3-10 截断层级）、R6（编号引用前缀）——建议指挥官将本清单批注进设计卡施工注记节。
- 范围十项与上游文档/排期无漂移；T3a/T3b 拆分与施工顺序依赖合理。

---

## 6. 脱敏自查

- 无真实 API key；无本机盘符绝对路径（路径均为 `docs/...`、`backend/...`、`frontend/...`、`scripts/...` 相对形式）；正文未复述禁用字面量（未出现仓库代称字样组合）；HEAD 以短哈希 `71b3191` 引用。
- 扫描口径：`git status` 全量（工作区 = 设计卡 + 红队报告 + 本报告，共 3 个未跟踪文件，无既有文件改动，无 git 写操作）。
- 证据等级自证：标注【实测】处均本棒 Read/Grep 所得（含 `SseChatEmitter`、`RunStore`、`ConfirmGate`、`ResumeService`、`StartupResumeRunner`、`SpToolCallingManager`、`AiController`、`AiProperties`、`JobService`、三个 JobRunner、`JobTerminalWriter`、`JobProgressService`、`FileStorageService`、`ValidationIssue`、`PrecheckGateGuardTest`、前端 `ai.ts`、`stub-upstream.mjs`、两份上游文档）；"263 全绿"未自跑，仅【旁证】。
