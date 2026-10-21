# 补档 · 上下文统计与压缩专项批① 施工报告（DS-Flash）

- 日期：2026-10-21
- 施工面：**计量 + 展示 + L1 工具结果清除**（设计稿 §6.4 批① **+ L1**，按本次裁决并入）
- 依据：`docs/research/设计-上下文统计与压缩专项-2026-10-21.md`（索引第 34 件）· `docs/research/调研-上下文统计与压缩业界惯例-2026-10-21.md` §②（索引第 31 件）
- 基线：`HEAD = 3c79eaa`（主线已含批②③ + L-19 + 防护批① + 模型配置批①）
- 纪律：**禁 commit**（提交归提交官）· 不起停服务 · 不动 `scripts/` 与 `data/` · 测试矩阵先行 + 红测先行 · 既有断言零删改

## 1. 范围边界（实际施工 vs 设计稿）

| 设计稿 §6.4 分批 | 本批是否施工 | 说明 |
|---|---|---|
| 批① `ContextMeter` + `ContextEstimator` + `ContextBreakdown` + 端点 + pill | ✅ 全部 | 另加 `ContextLimitResolver` / `ContextTrigger`（§3.2 的分母与触发，批① 的"分母"必需件） |
| 批② L1 工具结果清除 + 归档 + 原子落库 | ✅ **并入本批**（裁决） | 归档只做"压缩前原文进 `ai:ctx:archive:`"，**原子三步**按批① 的两个调用点落地（归档 → 记忆 → 快照） |
| 批③ L2 滑窗 + 递归摘要 + 熔断 | ❌ 未施工 | `ContextCompactor.MAX_LEVEL` 刻意封顶 L1；`fuse-threshold/fuse-cooldown/summary-*` 键已建但**无人消费**（成本 = 0，收益 = 配置面先定型） |
| 批④ L3 + 压缩提示条 + 文案 key 化 | ❌ 未施工 | 前端只做底栏 pill 位（裁决：只许在设计稿列的落点改） |

**与裁决的差异报告**：设计稿 §6.4 把 L1 排在批②，本次裁决把它并入批① —— 已按裁决执行；其余边界与设计稿一致。

## 2. 红测证据（逐条）

红测的做法：**先写用例，再跑出红，再实现**；实现后按设计稿 §5 的「证红方式」逐条做**变异**，
确认对应用例真的会红（"一个不可能失败的 fixture 比没有 fixture 更糟"）。

### 2.1 首次红（实现未落地时的真实失败）

| 用例组 | 首次红证据（节选） | 结论 |
|---|---|---|
| `ContextFrameWiringTest` | `帧序一致性违规（帧流=[message_start, delta, message_end, error]）` · `帧类型 done 恰好一帧（实际帧流=[start, message_start, delta, message_end, error]）` | 测试假件缺 `finishReason` + 缺 `start` 帧 ⇒ 被"完成帧校验"判成断流（**说明该用例真的在跑生产链路**，不是空转） |
| `ContextCompactorTest.unmeasuredSessionReportsNone` | `["percent"=0.0 (expected: null)]` | 暴露实现缺陷：**未测过的会话被报成 0%**（"0% 会被读成很空，而事实是不知道"）⇒ 改 `percent = measured ? … : null` |
| `ToolResultClearingTest.c20…` | `[c-1 受保护 ⇒ 清下一条最旧的] expected ["c-2"] but was []` | 暴露用例口径错误（保护**不扩大** pin 窗口）⇒ 用例改用 keep=4 才能区分，并补一条"保护不把窗口往后推"的断言 |

### 2.2 变异证红（实现落地后的敏感度证明）

| 变异 | 改坏的内容 | 变红的用例（实测） |
|---|---|---|
| M-1（C-02） | `ContextMeter` 删 R2 去重 + 改累加 | `c02RepeatedUsageIsNotAmplified`（expected 100）· `monotonicFramesAdoptTheLastValue`（expected 200）= **2 红** |
| M-2（C-03） | 去掉"空帧（0/0/0）不参与"判定 | `c03TrailingEmptyFrameIsIgnored`（空帧计数）· `c04NoUsageFallsBackToEstimate` · `c06AttemptsDoNotShareState` = **3 红** |
| M-3（C-11） | 去掉守恒的差额补偿 | `c11SumEqualsUsedForRandomInputs`（第 0 组 used=135346）· `c11EdgeCasesKeepConservation` = **2 红** |
| M-4（C-15） | 清除改成**删消息** | `c15…NoMessageIsDropped`（消息条数必须不变）· `c16Placeholder…` · `partiallyCleared…`（IndexOutOfBounds）= **3 红** |
| M-5（C-19/C-20） | `keep-tool-pairs` 失效（全 pin） | `c19KeepPairsBoundsTheClearing` · `c20…` · `c15…` · `savedTokens…` · `partiallyCleared…` + `ContextCompactorTest` 2 例 = **7 红** |
| M-6（C-32） | 前端 `>=` → `>` （warn 边界） | `69/70、84/85、94/95 逐点切换（边界归高档）` = **1 红** |

> 每条变异跑完即恢复原文；恢复后同一批用例复跑全绿（见 §4 终态门禁）。

## 3. 绿数字（前后端）

| 门 | 基线 | 施工后 | 说明 |
|---|---|---|---|
| 后端 `mvn -q -o test` | **859 / 859**（136 suite） | **937 / 937** | +78 例（施工 68 + 收口 10），0 删改；`TEST-*.xml` 求和一致 |
| 前端 `vitest run` | **522 / 522 · 27 文件** | **545 / 545 · 29 文件** | +23 例（`context-pill` 17 + `context-restore` 6），新增 2 文件；收口批只改 1 处文案断言（计数不变） |
| 前端 `vue-tsc --noEmit` | 0 error | **0 error** | |
| `check-docs` | 6/6 | **6/6** | |

## 4. 改动文件清单

### 后端新增（`backend/src/main/java/com/example/configmgr/ai/context/`）

| 文件 | 职责 |
|---|---|
| `ContextEstimator.java` | 估算器（CJK/1.7 + 其余/4.0）+ 消息/工具 schema 体量 |
| `ContextMeter.java` | usage 计量 R1–R6（空帧/去重/取最后/单调/回退/作用域） |
| `ContextBreakdown.java` | 六项 + `free` 的归类与**守恒归一**（Σ分项 == used） |
| `ContextLimitResolver.java` | 分母单一取数点（DB > 配置 > 0=未知） |
| `ContextTrigger.java` | `nextEst` 四项相加、70/80/95 阈值、滞回、最多升一级 |
| `ToolResultClearing.java` | L1 纯函数：plan（pin 窗口 + 保护集 + 幂等 + 占位符） |
| `ContextState.java` | `ai:ctx:` 的字段契约 + 宽松解析 |
| `ContextStateStore.java` | 记账存储接口（生产 = `RunStore`，单测 = 内存实现） |
| `ContextCompactor.java` | 统一入口：判定 → 归档 → 写值 → 台账；失败保持原状 |
| `ContextBreakdownService.java` | 端点载荷（七项 + `compaction`；未测回 `source:none`） |
| `ContextConfig.java` | 装配（刻意不并进 `AiModelConfig` —— 与模型配置批③ 的硬碰撞文件） |

### 后端修改

| 文件 | 改动 |
|---|---|
| `ai/config/AiProperties.java` | 新增 `Context` 内部类（13 键）+ `verifyContext()` 启动期校验 |
| `ai/run/ResilientChatService.java` | `Accumulator` 改喂 `ContextMeter`；`done.usage` 带 `est/source/frames/dirty`；新增 `compaction` 调用点、`done.context`、`context` 帧发射、轮末记账；`disclosedToolTokens()` 给续跑侧复用 |
| `ai/run/ResumeService.java` | 续跑重建历史**之后**压缩（第二个调用点），压缩后写回快照 + 响应体 `contextCompaction` |
| `ai/run/RunStore.java` | 实现 `ContextStateStore` + 三个键族（`ai:ctx:` / `ai:ctx:ledger:` / `ai:ctx:archive:`） |
| `ai/run/SseChatEmitter.java` | `context` 帧 + `done(..., context)` 重载 |
| `ai/web/AiController.java` | `GET /api/ai/context/{sessionId}`（`?ledger=1`）+ `/health` 两字段；两者均**字段注入**（构造器签名不变） |
| `resources/application.yml` | `app.ai.context.*` 13 键（逐行依据注释） |

### 前端

| 文件 | 改动 |
|---|---|
| `src/types/sse.ts` | `SseFrameTypes` **15 → 16**（新增 `context`）+ `SseContextFrame` + `SseDoneFrame.context` + `ContextUsage` |
| `src/types/ai.ts` | `ContextUsage` 转出 + `ContextBreakdownItem` / `ContextCompaction` / `ContextSnapshot` |
| `src/api/ai.ts` | `getContext(sessionId, ledger?)` |
| `src/stores/ai.ts` | `contextUsage/contextBreakdown/contextCompaction` 三态；`done` 帧入库；`context` 帧记现场；`loadContext()`（刷新重建）；新建对话清空 |
| `src/components/AiPanel/AiPanel.vue` | 底栏 pill 位（发送/停止钮左侧，一行插入 + 一行 import） |
| `src/components/AiPanel/ContextPill.vue` | 新：药丸（三档配色 / `≈` / 未知上限两态 / 点击展开） |
| `src/components/AiPanel/ContextPopover.vue` | 新：七项浮层（顺序跟服务端 / `free<0` 显示"已超"） |
| `src/utils/context-usage.ts` | 新：纯函数（`formatTokens` / `contextTier` / `contextPillText` / 分项文案） |
| `src/utils/error-card.ts` | 注释「15 类帧」→「16 类帧」（口径同步） |

### 测试新增

后端 10 类 68 例：`context/{ContextMeterTest, ContextEstimatorTest, ContextLimitResolverTest, ContextTriggerTest, ContextBreakdownTest, ToolResultClearingTest, ContextCompactorTest, InMemoryContextStateStore(替身)}` + `run/{ContextFrameWiringTest, ResumeContextCompactionTest}` + `web/AiControllerContextEndpointTest`。
前端 2 文件 23 例：`tests/context-pill.spec.ts`、`tests/context-restore.spec.ts`。

### 文档

`docs/technical-design.md`（新增「上下文计量与压缩」节）· `docs/test-cases.md`（CTX-01…08）· `docs/任务看板.md`（状态推进 + 数据截止）· `docs/evidence/补档-NUMSPEC-清单总账.md`（追加 13 键 + 3 常量）· 本报告。

## 5. 帧契约变更清单（15 → 16 的精确面）

| 面 | 变更 | 兼容性 |
|---|---|---|
| 帧类型集 | **15 → 16**：新增 `context`（压缩事件） | 旧客户端 `stores/ai.ts` 的 `default` 分支静默忽略未知帧；`toSseFrame` 白名单同批改 |
| `context` 帧载荷 | `{level, before, after, turns, lostTokens, reason, est, savedTokens, clearedPairs}` | 非终帧 / 非工具帧 ⇒ `FrameContract` 6 条不变量零影响（已用 `assertConformant` 锁） |
| `context` 帧出现时机 | **仅真发生压缩时**；位置 = 本轮第一个 `delta`/`tool_start` 之前 | 未压缩则一帧不发（不是"每轮发 level=none"） |
| `done` 帧 | 新增**可选** `context` 子对象 `{usedTokens, limitTokens, percent, level, est, circuit}` | 未装配压缩设施时**不写该键**（"未装配"与"计量为 0"可区分） |
| `done.usage` | 新增 `est / source / frames / dirty` 四键；既有三维不动 | 旧后端缺四键 ⇒ 前端按"未知"处理（不显示 `≈`） |
| 端点 | 新增 `GET /api/ai/context/{sessionId}`；`/health` 增 `contextLimitTokens` / `compactionOn` | 均为 additive |
| Redis | 新增 `ai:ctx:<sid>` / `ai:ctx:ledger:<sid>` / `ai:ctx:archive:<sid>:<turnNo>` | TTL 与 `app.ai.session-ttl` 同寿、读写 touch |
| **conformance 白名单** | 前端 `SseFrameTypes`（唯一白名单）+ `SseFrame` 判别联合 + `types` 注释 | 本批裁决范围内；**其余既有断言零删改** |

## 6. 回归锁零改动确认

- 后端既有 859 例**一行未改**（新增用例全部落在新文件；`InMemoryContextStateStore` 是新增替身文件）；
- 既有构造器签名零改动：`ResilientChatService` / `ResumeService` / `AiController` 的新依赖全部**字段注入**（`@Autowired(required=false)`），`new Xxx(...)` 的三份 conformance 与四份 resume 用例零触碰；
- `SseChatEmitter.done(usage, model, extra)` 三参重载保留（新四参版本委托）；
- `FrameContract` / `SseFrameSizeLimit` / `UsageDetails` / `ConvergeMemoryToolCallStates` / `ResumeEnvelopeShape` / `RunStore*` 全套未改且全绿；
- 前端 522 例零改（新用例独立文件）。

## 7. 边界与挂账（如实登记）

1. **L2/L3/递归摘要/熔断未实现**：`circuit` 恒 `closed`、`fuseFails` 恒 0；占比到 80%/95% 也只升到 L1（`MAX_LEVEL` 封顶，**不谎报级别**）。
2. **压缩提示条未做**（设计稿排批④）：`context` 帧已在前端"记现场"（`contextCompaction`），提示条 UI 留给批④。
3. **续跑侧记忆写回**：`ResumeService` 无法访问 `ChatMemory`，压缩后的历史只写快照；轮末 `convergeMemory` 会以快照为准整体写回（故障窗口内偏差方向 = "记忆更旧"，不会多算术、不会丢配对）。
4. **上限未知（降级态）不做绝对阈值兜底**：裁决/设计稿提到"L1 的 keepPairs + L3 的 keepTurns 绝对阈值"，但其数值未在设计中给出；本批**不发明阈值**，只做 `reason=limit_unknown` 的 no-op（已登记为设计缺口）。
5. **`seedHistoryPlusRebuild` 一类"自证式断言"已删除**：`ResumeContextCompactionTest` 最终用**绝对条数 15** 锁"L1 不删消息"。
6. **`ContextBreakdownService` 的 `system/tools` 锚点**来自轮初写入 `ai:ctx:` 的值；端点重建时若锚点缺失（老会话）这两项为 0 —— 分项守恒仍成立（差额补给 argmax），但"system 占比"会偏低，登记为已知偏差。
7. **`/health.compactionOn`** 只表示"比例判定会执行"（compactor 装配 + 上限已知），不表示"一定会压缩"。
8. **E2E-CTX-1..4 未落**（复审 M-5 补登记）：设计稿 §5.8 的四条 E2E（真值 / 重复 usage 不放大 / 末块 0 取中间值 / 无 usage 回退估算）+ `scripts/ci/stub-upstream.mjs` 的三形态 usage 桩**均未落** —— 本批明令不动 `scripts/`，归 **E2E 批**。当前覆盖等价物 = 后端 `ContextMeterTest`（C-01…C-06）与 `ContextFrameWiringTest`（`done.usage` 的 `est/source`）；缺的是"真上游桩 + 真 HTTP"那一段。
9. **空回复轮不记账**（复审 M-5 补登记 = **既有行为边界**，非本批引入）：`emptyReply` 走 `error` 终帧、不发 `done`，故该轮不写 `ai:ctx:`（`turnNo` 不推进、占用条不刷新）。这与"空回复即失败"（T3-7/A12）的既有口径一致；若将来要让失败轮也记占用，需要单独裁决"失败轮是否算一轮"。

## 8. 自证

- 施工 agent：`agent-1349` · `modelAlias = DeepSeek/deepseek-flash` · `profileName = coder` · `thinkingEffort = high`（`profile.bind` 实测 9 条，取首条）
- wire：`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4-1812-4fe3-b259-6c0a97160f45/agents/agent-1349/wire.jsonl`

## 9. 收口批（复审 READY-WITH-NITS → 逐条处置）

复审结论 = **READY-WITH-NITS**，指挥官逐条裁决收口。下表按"条目 → 处置 → 证据"给出。

| 条目 | 处置 | 落点 / 证据 |
|---|---|---|
| **I-1**（必修） | ① **续跑侧也发 `context` 帧**：`ResumeService` 在压缩成功后、`suspendExecutor.execute` 交棒**之前**同步发帧（`start` 已由原轮发出/归档，本帧取号在后 ⇒ 晚于 `start`、早于交棒后的首个 `delta`）；载荷与首轮**同一份**（两处都调 `ContextCompactor.framePayload(outcome)`，杜绝两次拼装漂移）。② **`done.context.level` 续跑轮不再恒 `none`**：级别随 `TurnContext` 显式传入（`compactionLevel`），由 `ResumeService` 交棒前 `noteCompaction(runId, level)` 写入一次性交接位、`driveResumed` 读后即弃；删掉原来"压缩时刻 ≥ 轮起点"的时刻反推（续跑必然误判）。 | `ResumeService.java`（压缩段 + 发帧 + 交接）· `ResilientChatService.java`（`TurnContext.compactionLevel` / `noteCompaction` / `compactionLevels` / `Compaction.levelWire()`）· `ContextCompactor.framePayload` · 用例 `ResumeContextCompactionTest`（帧序 + 载荷 + `verify(noteCompaction)`）、`ContextFrameWiringTest#resumeCompactionLevelReachesDoneContext`、`#compactionLevelHandoffIsOneShot` |
| **M-1** | `verifyContext` 补 `chunk-tokens < reserve-tokens` 的 fail-fast（严格小于；消息点名两键与后果"摘要请求自身越窗"）。 | `AiProperties#verifyContext` · 用例 `AiPropertiesContextGuardTest`（8 例，含边界 `chunk = reserve − 1` 合法） |
| **M-2** | 三处顺序注释统一为**实际顺序「记忆 → 快照」**并写理由（`store.create` 失败时残留"记忆已压、快照未建"是幂等的；反序残留"快照已压、记忆未压" ⇒ 两条历史线）。设计稿 §3.5 措辞**不动**，在 `ContextCompactor` 类注里点名差异（含"设计稿写的是 归档 → RunStore.save → chatMemory.clear+add"）。 | `ContextCompactor` 类注差异段 · `ResilientChatService#compactBeforeRequest` 方法注 + 内联注 · `ResumeService` 压缩段注（本路径 = 归档 → 快照 → 轮末记忆，并说明为何不同） |
| **M-4** | danger 档文案「会先清理历史工具结果」（摘要是批③，文案不得承诺未落地的手段）；`context-pill.spec.ts` 断言同步改为 `toContain('清理历史工具结果')`。**这是文案修正、非断言降格**（仍然锁"高危档必须给出具体动作"，且从"承诺未实现的手段"改为"承诺已实现的手段"）。 | `utils/context-usage.ts` `tierTitle` · `tests/context-pill.spec.ts` |
| **M-5** | 施工报告 §7 补登记两条缺口（第 8 条 E2E-CTX-1..4 + 三形态 usage 桩未落、归 E2E 批；第 9 条空回复轮不记账 = 既有行为边界）；`docs/任务看板.md` 本批行补同两句。 | 本报告 §7.8 / §7.9 · `docs/任务看板.md` 10-21 本批行「依据」列 |
| **M-6** | `docs/technical-design.md` 新增段「11 键」→「13 键」。 | `docs/technical-design.md:327` |
| **N-1** | `ContextMeter` 的模型名口径由「末帧」改为「**首个非空 metadata**」（类注新增一段：与 usage 的"取最后"不矛盾 —— 取值对象不同）。 | `ContextMeter` 类注 + `model()` javadoc |
| **N-2** | NUMSPEC `B1-47` 依据列注明「**按 `ToolResponseMessage` 里的 response 条数计**」（一条 tool 消息可含多份响应，按消息计会把 pin 窗口放大 N 倍）。 | `docs/evidence/补档-NUMSPEC-清单总账.md` B1-47 |
| **N-3** | `RunStore#write` 类注点名 `HSET putAll` 的**字段级合并不删旧键**边界（`reason`/`model` 为 null 时不产字段 ⇒ 旧值残留），并说明为何不做 `DEL+HSET`、以及残留只影响观测不影响判定。 | `RunStore#write` javadoc |
| **N-4** | 端点注释点明**有界性来源**：`breakdown` 恒 6/7 项、`ledger` ≤20 条（`CONTEXT_LEDGER_MAX` + LPUSH/LTRIM）、分项只遍历记忆窗口（40 条）⇒ 响应体与轮数无关。 | `ContextBreakdownService` 类注 |
| **N-5** | 按裁决**登记不改**（本条无代码/文档改动）。本收口轮未收到 N-5 的具体条文，故按"登记不改"处置；若其内容需要落一处注释，请以复审原文为准补一行。 | 本表 |

### 9.1 收口批红证据（先红后绿）

| 用例 | 红（实现前） | 绿（实现后） |
|---|---|---|
| `AiPropertiesContextGuardTest.chunkTokensMustBeSmallerThanReserve` | `Expecting code to raise a throwable`（无闸） | 8/8 绿 |
| `ContextFrameWiringTest.resumeCompactionLevelReachesDoneContext` | `{"level"="none", …} to contain ["level"="L1"]` | 6/6 绿 |
| `ContextFrameWiringTest.compactionLevelHandoffIsOneShot` | 同上（交接位不存在） | 同上 |
| `ResumeContextCompactionTest.resumeClearsOldToolResultsAndPersistsTheCompactedHistory` | `Expecting ["start","message_start","delta","message_end","done"] to contain exactly ["start","context","message_start","delta","message_end","done"]` | 2/2 绿 |

### 9.2 收口批绿数字

- 后端 **937 / 937**（`MVN_EXIT=0`，新增 10 例：`AiPropertiesContextGuardTest` 8 + `ContextFrameWiringTest` 2）
- 前端 **545 / 545 · 29 文件**（`TSC_EXIT=0`，收口批只改断言文案，计数不变）
- `check-docs` **6/6**
- 自证：`agent-1349` · `DeepSeek/deepseek-flash` · `coder` · `thinkingEffort=high`

> **提交官补记（2026-10-21，非施工方口径）**：入库前复核出三条残余微修（M-a 第四处顺序注释 / N-a 池饱和清交接位 / N-b 发帧失败不阻断续跑），并各补 1 条加固性用例 ⇒ 后端 **939 / 939**（937 + 2，连跑 2 次同数）。**既有 937 例零改动**，本报告的数字保留施工/收口时点口径；入库实测与红/绿证据见 `-提交报告-` §1 / §3。
