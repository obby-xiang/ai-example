# 补档 · 上下文统计与压缩专项批① 复审报告（DS-Flash，2026-10-21）

## 0 自证与纪律

- 复审方自证行以复审链原始报告为准；本补档由合流提交官转录（体例同 `补档-模型配置批①-复审报告-DS-Flash.md`），**不代签**（提交官自证行见 `-提交报告-` §0）。
- 与施工方互盲：只读落库的施工报告 + 工作树代码与用例，未读其 wire。
- 只读纪律：零 commit / 零 push / 零服务启停。
- 复审时点：施工笔之后、收口之前（收口落地结果见 `-复核确认-` 与施工报告 §9）。

## 1 结论：**READY-WITH-NITS**（0 Critical / 1 Important / 6 Minor / 5 Nit）

- **0C 的判据**：本批是**新增观测面 + 一处新增压缩动作**，不改变"模型看到什么"以外的任何语义；L1 只清工具结果、不删消息、不动 `assistant(tool_calls)` ⇒ 构造上不产生孤儿对（`ToolResultClearingTest` 的 C-15…C-20 锁住"消息条数不变"）。计量侧六条规则（R1–R6）各自有反例注入证明敏感度（施工报告 §2.2 变异 M-1/M-2/M-3）。
- **1I 的判据**：同一会话**换个入口进来就不发帧/不报级别** —— 属"首轮/续跑口径分叉"，是本专项要根治的那类缺陷本身，故提到 Important（§2）。
- **6M / 5N 的分布**：M-1（启动期守卫）、M-2（顺序注释与现实不符）、M-4（文案承诺未落地的手段）随收口笔落地；M-3 / M-5 / M-6 与 5 条 N **登记不改**（§3 / §4 逐条给理由与去向）。
- 断言只升不降：本批无一处"把断言改绿"；唯一改到的既有断言是 M-4 的**文案断言同步**（换的是被断言的字面，锁的性质不变，见 §3 M-4）。

## 2 Important（1 条，必修）

### I-1 续跑侧压缩不可见（缺帧 + 级别恒 `none`）

| 面 | 复审结论 |
| --- | --- |
| **现象** | 首轮压缩会发 `context` 帧、`done.context.level` 报实际级别；续跑路径压缩成功后**一帧不发**，且 `done.context.level` 恒 `none` |
| **根因** | ① 发帧只在 `ResilientChatService#chat` 的实现里；② 级别靠"压缩时刻 ≥ 轮起点"反推 —— 续跑是**压完才交棒**，压缩时刻必然早于轮起点，反推恒为假 |
| **为什么是 Important** | ① 续跑正是在"挂起→回来"之后，用户最需要看见"刚才压过一轮"；② `done.context.level` 是运维/取证面唯一的级别真值，恒 `none` 等于**系统性地报错账**（不是"少报一个字段"） |
| **修法（收口已落）** | ① 续跑侧在压缩成功后、`suspendExecutor.execute` 交棒**之前**同步发帧，载荷与首轮**同一份**（两处都调 `ContextCompactor.framePayload(outcome)`，杜绝两次拼装漂移）；② 级别改为**显式交接**：`noteCompaction(runId, level)` 写一次性槽位 → `driveResumed` 读后即弃（`resumedTurn` 取 `compactionLevels.remove(runId)`） |
| **位置约束** | `start` 已由原轮发出（归档里）⇒ 本帧必然晚于 `start`；交棒在发帧之后 ⇒ 必然早于交棒后的首个 `delta`。`FrameContract` 六条不变量因此零影响 |
| **用例** | `ResumeContextCompactionTest#resumeClearsOldToolResultsAndPersistsTheCompactedHistory`（帧序 `start → context → message_start → delta → message_end → done` + 载荷九键 + `verify(noteCompaction)`）、`ContextFrameWiringTest#resumeCompactionLevelReachesDoneContext`、`#compactionLevelHandoffIsOneShot`（读后即弃） |

## 3 Minor（6 条）

### 3.1 随收口落地（5 条）

| # | 意见 | 落点 / 证据 |
| --- | --- | --- |
| **M-1** | `verifyContext` 缺 `chunk-tokens < reserve-tokens` 的启动期守卫：配错时"摘要请求自身越窗"，表现为**每次都压、每次都失败**，且无启动期信号 | `AiProperties#verifyContext`（严格小于 + 消息点名两键与后果）· `AiPropertiesContextGuardTest` 8 例（含边界 `chunk = reserve − 1` 合法）；**先红** = `Expecting code to raise a throwable` |
| **M-2** | 顺序注释与实际执行相反：代码是「记忆 → 快照」，三处注释写「快照 → 记忆」 | ① `ContextCompactor` 类注新增「与设计稿 §3.5 的差异」段（设计原文 `归档 → RunStore.save → chatMemory.clear+add` 不改，差异在此登记）；② `ResilientChatService#compactBeforeRequest` 方法注 + 内联注；③ `ResumeService` 压缩段注（本路径 = 归档 → 快照 → 轮末记忆，并说明方向性理由）。**提交官复核补第 4 处**（`compact()` 方法注仍写反）见 `-复核确认-` §2 M-a |
| **M-4** | danger 档文案「会先清理历史工具结果」当时读作"承诺了未落地的手段"（摘要属批③） | 文案改为只承诺已落地的 L1 动作，`context-pill.spec.ts` 断言同步（`toContain('清理历史工具结果')`）。**这是文案修正、非断言降格**：仍锁"高危档必须给出具体动作"，只把"承诺未实现的手段"换成"承诺已实现的手段" |
| **M-5** | 两条缺口未登记：E2E-CTX-1..4 + 三形态 usage 桩未落；空回复轮不记账 | 施工报告 §7.8 / §7.9 补登记 + `docs/任务看板.md` 本批行「依据」列同两句（前者归 E2E 批；后者是**既有行为边界**，非本批引入） |
| **M-6** | `docs/technical-design.md` 新增段写「11 键」，实际 `app.ai.context.*` 为 **13 键**（口径漂移，运维按文档对数会少两键） | `docs/technical-design.md:327` 改为 13 键 |

### 3.2 登记不改（1 条）

| # | 意见 | 为什么不修 | 去向 |
| --- | --- | --- | --- |
| **M-3** | 条目原文未随转录链落地（施工报告 §9 亦无 M-3 行）。就本批可核的同族面 = **上限未知的降级态没有绝对阈值兜底**（`reason=limit_unknown` 直接 no-op） | 绝对阈值（keepPairs / keepTurns 的数值）设计稿未给，**本批不发明阈值**——发明一个数就是"实现没做、行为却承诺了"的另一种形态 | 批③（与 L2/L3 的 keepTurns 一起定）；登记见施工报告 §7.4 |

> M-3 与 N-5 同口径：**没有条文原文就不臆造修法**。若原文属代码面且需落一处注释，以复审原文为准补一行（施工报告 §9 对 N-5 已用同一处置）。

## 4 Nit（5 条，全部登记 / 落注释）

| # | 意见 | 处置 | 证据 |
| --- | --- | --- | --- |
| **N-1** | `ContextMeter` 的模型名口径与 usage 的"取最后"看似矛盾 | 改「**首个非空 metadata**」，并在类注新增一段说明"取值对象不同"（usage 取最后可用候选 vs 模型名取首个声明者） | `ContextMeter` 类注 + `model()` javadoc |
| **N-2** | NUMSPEC `B1-47` 的条数口径不明（一条 tool 消息可含多份响应） | 依据列注明「按 `ToolResponseMessage` 里的 **response 条数**计」（按消息计会把 pin 窗口放大 N 倍 —— 正是 L1 最容易踩的错） | `docs/evidence/补档-NUMSPEC-清单总账.md` B1-47 |
| **N-3** | `RunStore#write` 用 `HSET putAll` ⇒ **字段级合并不删旧键**（`reason`/`model` 为 null 时不产字段，旧值残留） | 类注点名边界 + 说明为何不做 `DEL+HSET`（会丢 `runEpoch` 等并发写入的字段）+ 残留只影响观测不影响判定 | `RunStore#write` javadoc |
| **N-4** | 端点响应体的有界性未说明（会不会随轮数增长？） | 类注写清：`breakdown` 恒 6/7 项、`ledger` ≤ 20 条（`CONTEXT_LEDGER_MAX` + `LPUSH/LTRIM`）、分项只遍历记忆窗口（40 条）⇒ 响应体与轮数无关 | `ContextBreakdownService` 类注 |
| **N-5** | 条目原文未随转录链落地 | 按裁决**登记不改**（与 M-3 同口径：无原文不臆造修法） | 本表 |

## 5 回归锁核对

| 面 | 结论 |
| --- | --- |
| 后端既有 859 例 | **一行未改**（新增用例全在新文件；唯一"改"到的既有断言是 M-4 的前端文案断言） |
| 既有构造器签名 | 零改动：`ResilientChatService` / `ResumeService` / `AiController` 的新依赖全部**字段注入**（`@Autowired(required=false)` 或 `ReflectionTestUtils` 直灌），`new Xxx(...)` 的 conformance 与 resume 用例零触碰 |
| 帧契约 | `FrameContract` / `SseFrameSizeLimit` / `UsageDetails` / `ConvergeMemoryToolCallStates` / `ResumeEnvelopeShape` / `RunStore*` 全套未改且全绿；`context` 帧是非终帧、非工具帧 ⇒ 六条不变量零影响（`assertConformant` 锁） |
| 前端既有 522 例 | 零改（新增 2 个独立 spec 文件） |
| conformance 白名单 | `SseFrameTypes` 15 → 16 属**裁决批准的显式例外**（帧类型集变更），判别联合与注释同批改；`error-card.ts` 的「15 类帧」注释同步为 16 |

## 6 攻击面（对本批新增面的定向核对）

| # | 面 | 判据 | 结论 |
| --- | --- | --- | --- |
| a | **压缩后历史与快照分叉**（SM-01 同族） | 两个调用点同源；压缩后历史写回快照（`ResumeContextCompactionTest` 断言快照里已是占位符） | 通过；残余偏差方向 = "记忆更旧"（`ResumeService` 拿不到 `ChatMemory`，轮末 converge 以快照为准整体覆盖），已登记 |
| b | **清错对象**（把未决 HITL 的结果清掉） | pin 集 = 最近 5 对 + 未决 `toolCallId` 保护集；C-20 断言"保护不扩大 pin 窗口" | 通过 |
| c | **重复清除的账**（同一会话反复压） | 占位符幂等（`isAlreadyCleared`），二次 plan 无 `changed` ⇒ `reason=nothing_to_clear`，不计 `clearedPairs` | 通过 |
| d | **级别谎报** | `MAX_LEVEL = L1` 封顶：到 80/95% 也只升 L1，帧里不会出现 L2/L3 | 通过（这条是本批"不做假账"的核心） |
| e | **失败静默截断** | 归档/记账任一步抛错 ⇒ ERROR + 返回原消息列表 + 本轮照常发请求（`reason=compaction_failed`） | 通过（对齐调研 Q3"绝不静默截断"） |
| f | **计量放大**（上游每 chunk 重复同一份 usage） | R2 三维全等去重 + R1 空帧不参与；变异 M-1/M-2 各 2–3 例变红 | 通过 |
| g | **分项求和 ≠ used**（占比自相矛盾） | `normalizeTo` 差额补给 argmax ⇒ Σ分项 == used 恒成立（含随机输入 200 组 + 边界） | 通过 |
| h | **观测面反噬业务面** | 首轮/续跑的发帧路径是否可能让一轮失败 —— 复审**未覆盖**（只在收口期暴露形态），由提交官复核补 N-b（`-复核确认-` §2） | 提交官已加固 |

> 攻击面口径：a–e 是压缩动作的正确性，f–h 是"看得见的数"与"跑得成的事"之间的边界。h 是本批唯一在复审面**漏掉**的攻击面（复审只核了"帧发没发/对不对"，没核"帧发不出去会怎样"），如实点名。
