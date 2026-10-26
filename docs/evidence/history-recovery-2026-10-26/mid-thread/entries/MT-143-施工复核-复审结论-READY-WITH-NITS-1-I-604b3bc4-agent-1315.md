<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-143 · 施工复核 · 复审结论：**READY-WITH-NITS**（1 Important：续跑侧压缩的帧契约缺口，需修或显式登记）（604b3bc4 / agent-1315）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`、`scripts/ci`、`SseChatEmitter`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m320` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 11 条有正文），末条有正文消息为 `m392`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1315`（`main` = 主会话）；原字符 8,307（链内 1 段、合计 8,307）。
> **链号 / 类别**：`MT-143` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 18 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `18%`。
> **与末条归档（138 件）的关系**：末条 `m392`（12,168 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 复审结论：**READY-WITH-NITS**（1 Important：续跑侧压缩的帧契约缺口，需修或显式登记）

审查对象：主工作树相对 `3c79eaa` 的全部未提交改动（17 改 + 11 新类 + 8 新测试类 + 3 新测试 + 3 前组件/2 前端 spec + 5 文档）。只读：未改文件、未起停服务、未提交。

### 复跑证据（实测）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/.../mvn -q -o test` | **EXIT=0**；汇总 **927**（report 求和 917 + `GenerativeFormGateTest` 10 条 `@Nested`）＝ 859 + **68** ✔ |
| 新增测试 | 逐类求和 | 9+11+7+6+7+7+8+4+2+7 = **68** ✔ |
| 前端 | `node node_modules/vitest/vitest.mjs run` | **29 files / 545 tests / 0 errors / EXIT=0** ＝ 522 + **23** ✔ |
| typecheck | `npx vue-tsc --noEmit` | **0 错** ✔ |
| build | `npm run build` | **✓（45.87s）** ✔ |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6** ✔ |

---

## 一、逐项核验

### a. 计量正确性 —— **通过**
`ContextMeter` 六条规则逐条落地：R1 空帧（含三维全 null / 全 ≤0）不参与 · R2 与已采纳值三维全等去重 · R3 采纳最后一个非空候选 · R4 `prompt` 回退判脏（`dirty++`、保留旧值、不抛）· R5 无候选回退估算（`est=true/source=estimate`）· R6 每 attempt 新建实例（`Accumulator` 内 `new ContextMeter()`，`runAttempts` 每次 attempt 新建 `Accumulator`）。`ContextMeterTest` 的 C-01..C-06 + monotonic 逐条对应，类注写明"kill 改哪一行"。`done.usage` 四增量键（`est/source/frames/dirty`）追加、既有三维与 `UsageDetails.enrich` 不动 ✔。「分母 = 下一次请求 prompt_tokens」的口径落地正确：`usedNow` = 上次 `promptTokens`（展示），`nextEst = used + lastCompletion + estimate(本轮输入) + Σ(上次真值后新增工具结果)`（触发），续跑侧把重建出的 tool 响应作为真增量传入（`rebuiltToolTokens`）✔。

### b. 拆分守恒 —— **通过**
`normalizeTo`：`floor(v_i × used / Σv)` + 差额补给 `argmax`（并列取 `USED_KEYS` 序前）⇒ 恒有 `Σ六项 == used`；`Σv==0` 全部补 `history`；`free` 派生、不参与守恒、可负。`ContextBreakdownTest.c11SumEqualsUsedForRandomInputs` 用**固定种子** 1000 组（含 `used < Σv` 与 `used > Σv` 两侧）+ 边界用例（used=0/7/100000/空 values）——属性式断言可信（确定性、可复现）✔。`parts()` 顺序固定、`free` 末位、上限未知时不产 `free` ✔；C-12/13/14 锁"类目不串味 / tools 反映披露 / summary 独立" ✔。

### c. L1 清除正确性 —— **通过（1 处注释口径待统一）**
- `ToolResultClearing.plan` 是纯函数：按 slot（response）遍历、pin 最近 `keep-tool-pairs` 条、跳过未决 HITL `toolCallId`、跳过已是占位符的（**幂等，不重复计费**）；**不删消息、不改 id、不动 assistant(tool_calls)**，只重建被改的那条 `ToolResponseMessage`（其余消息同引用）⇒ 构造上无孤儿对；占位符含工具名 + `≈tokens` + `toolCallId` + `ai:ledger:<runId>`，且**刻意不复用** `TRUNCATION_MARKER` ✔。测试 C-15/C-16/C-19/C-20 + 幂等 + "部分清除保留其余 response" + savedTokens 有界 ✔。
- **归档与失败面**：`ContextCompactor.compact` 的 try 覆盖"归档 → 写 `ai:ctx:` 真值 → 台账"，任一失败 ⇒ ERROR + 返回**原消息列表** + `reason=compaction_failed`（`archiveFailureKeepsHistoryUntouched` 锁住）✔；归档仅在有 `messageJsonBefore` 时写、TTL 同会话 ✔。
- **待统一**：落库顺序三处口径不一（见 M-2）。

### d. `ContextTrigger` —— **通过**
`nextEst` 四项求和（各取 `max(0,…)`）· 分母 `limit − reserve`（钳 ≥1）· 70/80/95 阶梯 · `advanceTo` 每次最多升一级 · 滞回 `usedNow/limit < 60%`（只看真值，`everCompacted=false` 直接解锁）· `limit<=0` ⇒ `Level.NONE + ratio=0 + degraded` ✔。`ContextCompactor.MAX_LEVEL = L1` 封顶 ⇒ 占比到 80/95% 也**只报 L1**（不谎报级别）✔，`batchOneNeverReportsAboveL1` 锁住。

### e. 帧契约 15→16 —— **通过（续跑路径缺口见 I-1）**
- `SseChatEmitter.context()` 新帧走同一条取号/落档通道（`contextFrameIsLegalAnywhereBeforeTheTerminal` 断言 `types == start,context,message_start,delta,message_end,done`、`seq==2`、进归档）✔；位置在 `out.start` 之后、首个 delta 之前 ✔。
- 前端 `SseFrameTypes` 加 `'context'`、判别联合加 `SseContextFrame`、store 加 `case 'context'`（只记 `contextCompaction`，不刷 pill），未知帧走 `default` 静默忽略（`:2950`）⇒ 旧客户端前向兼容 ✔。
- **`FrameContract` 没有类型白名单**（我上轮已核）⇒ 设计稿要求的"同批改 conformance 白名单"实际无需改，且有 `contextFrameIsLegal...` 直接验完整帧流 ✔。
- `done.context` 未装配时**不写键**（`unassembledServiceOmitsContextKey`）✔；`done.context.limitTokens/percent` 在上限未知时为 null ✔。

### f. 端点与 `/health` —— **通过**
`GET /api/ai/context/{sessionId}`：空 sessionId → 400；Redis 不可用 → 503（`redisUnavailable()`）；计量未装配 → 503 `AI_CONTEXT_UNAVAILABLE`；读记忆异常 → 503（不假装 0）✔。载荷七项 + `compaction` 固定形状 + `?ledger=1` 才带 20 条台账（`ledgerIsOnlyReturnedOnDemand`）✔；`source` 三态（stream/estimate/none）、未测时 `percent=null`（c20 修正已落）✔。`/health` 新增 `contextLimitTokens`（未知为 null，因 `non_null` 序列化而不出现）+ `compactionOn`（= 装配 ∧ 分母已知）✔ additive。

### g. 前端 —— **通过（1 处文案超前）**
`ContextPill`：`[用量 pill][发送/停止]` 位置正确（`AiPanel.vue:749-753`）、无数据不渲染、`data-tier` 标记、点击开 popover 并**按需拉端点**、外点/Esc 关闭且监听器成对解绑 ✔。`context-usage.ts` 把档位/缩写/文本做成纯函数（70/85/95 边界 `>=`、`<1000` 不缩写、`k` 一位、`≈` 前缀、上限未知只显示已用、未测空串）并有逐点单测 ✔。`ContextPopover` 七项按服务端顺序、`free` 负值红色"已超"、固定"分项为估算、总量为账单"说明、无数据显式加载态 ✔。Tailwind 全工具类、无 emoji ✔。`stores/ai.ts`：只在 `done` 写（流式中不跳动）、`context` 帧只记现场、新会话清空、`loadHistory` 并行重建 + 跨 await 会话身份守卫、端点失败保持现状、`source=none` ⇒ 隐藏 pill ✔；`context-restore.spec` 6 例锁"刷新重建 / none 不说谎 / 503 不影响历史 / 新会话清空 / 流式中不跳动 / context 帧不刷 pill" ✔。

### h. 断言只升不降 —— **通过**
`git status` 显示**没有任何既有测试文件被修改**（新增全部落在新文件：7 个 context 测试类 + `InMemoryContextStateStore` 替身 + 3 个 run/web 测试 + 2 个前端 spec）；`done(usage,model,extra)` 三参重载保留（新四参委托）⇒ 既有调用零改；`Accumulator.usage()` → `usage(int)` 是内部方法，既有用例未触。859→927、522→545 与新增清单逐数吻合 ✔。

### i. `verifyContext()` 完备性 —— **基本完备（缺 1 条文档已承诺的校验，见 M-1）**
覆盖 13 键中 12 个数值键：`limit<0` / `reserve<=0` / `reserve>=limit`（limit>0 时）/ 四个 percent 的 0–100 / `l1<l2<l3` 严格递增 / `hysteresis<=l1` / `keep*>=0` / `chunk>0` / `summary-timeout>0` / `fuse-threshold>0` / `fuse-cooldown>0`；`summary-model` 是字符串不校验 ✔。**缺**：类注与 NUMSPEC 都把"`chunk-tokens >= reserve-tokens` ⇒ 摘要请求自身可能越窗"列为 fail-fast 理由，代码里没有这条检查。

### j. 文档一致性 —— **基本一致（2 处文档缺陷，见 M-5/M-6）**
`docs/technical-design.md` 新增段与实现逐条对齐（R1–R6 / 分母三级 / 双轨 / 六项守恒 / L1 边界 / 原子性 / 帧与端点 / 配置）✔；`docs/test-cases.md` 新增 CTX-01..08 ✔；NUMSPEC 追加 13 键 + 3 个非配置常量逐行登记依据，并把设计稿标题"11 键"登记为笔误 ✔；看板行 📋→📤 且写清"批① + L1 并入、L2/L3 仍待发令" ✔。

---

## 二、分级清单

### Important
**I-1 续跑侧压缩不发 `context` 帧，且 `done.context.level` 会报 `none`**
- 位置：`backend/.../run/ResumeService.java:566-601`（压缩只写 `body.contextCompaction` + 快照）；`backend/.../run/ResilientChatService.java:250`（唯一的 `out.context(...)` 在首轮 `chat()`）；`ResilientChatService.java:1053-1056`（`level = before.atMs() >= turnStartedAtMs ? before.level() : "none"`）。
- 问题：续跑压缩发生在 `ResumeService.resume`（此时 `driveResumed` 的 `turnStartedAtMs` 尚未取值），故 ① 压缩**不产生 `context` 帧**；② 该轮 `done.context.level` 恒为 `"none"`，与真实级别（L1）不符。设计稿 §3.6/§4 要求"压缩发生时另发 `context` 帧"，D5 理由明写"挂起/续跑期间也可能出现"；验收 §9-8 要求"日志、台账、context 帧**三处**都能查到同一组值"——续跑路径只满足两处。
- 影响：批① 前端不渲染提示条 ⇒ 今日无用户可见影响；但契约与验收口径不成立，且 `level` 错误会误导批④ 的提示条与排障。
- 修法（择一，均小）：① 让 `ResumeService` 在开始收流前用同一份 `outcome` 发 `context` 帧（注意必须晚于 `start`、早于首个 delta）；② 若不发帧，把压缩时刻随 `TurnContext` 传入（`compactedAtMs`）使 `done.context.level` 如实为 `L1`，并在收口报告登记"续跑不发 `context` 帧"。

### Minor
- **M-1** `verifyContext` 缺 `chunk-tokens < reserve-tokens` 校验：`backend/.../config/AiProperties.java`（`verifyContext` 内 `chunkTokens <= 0` 那一支之后）。类注与 NUMSPEC 都把它当 fail-fast 理由，代码未实现；批③ 消费该键前补上成本最低。
- **M-2** 压缩落库顺序三处口径不一：`ContextCompactor.java:130-132`（"调用方按『快照 → 记忆』"）、`ResilientChatService.java:1063-1080`（实际"记忆 → 快照"）、设计稿 §3.5（"归档 → 快照 → 记忆"）。功能上两种顺序都只留同类失败窗口（无回滚），但注释应统一，并写明"记忆先行"的理由（`store.create` 失败时不会留"快照已压、记忆未压"的组合）。
- **M-4** pill title 的 danger 档承诺了未实现的行为：`frontend/src/utils/context-usage.ts:91`"下一次请求前会先做**摘要压缩**"（`context-pill.spec.ts:138` 用 `toContain('摘要压缩')` 锁死）。批① 只有 L1（工具结果清除），摘要属批③。修法：改为"会先清理历史工具结果"，测试同步。
- **M-5** 两条缺口**未在仓内文档登记**：① 设计稿 §5.8 的 E2E-CTX-1..4（29→33）未落；② 空回复轮（`finish()` 空正文早退到 `emptyReply`，`ResilientChatService.java:527-530`）**不记账**（`turnNo` 不增、`ail:ctx:` 不更新）。`施工报告 §7` 只列 7 条（不含这两条），看板 📤 行也未提。验收 §9-2 在本批**未满足**，必须显式登记而非静默。
- **M-6** `docs/technical-design.md` 新增段写"`app.ai.context.*` **11 键**"，紧随其后却列出 **13** 个键（设计稿 §3.7 的同一笔误，NUMSPEC 已登记但技术文档照抄）。修法：改成 13 键。

### Nit
- **N-1** `ContextMeter` 类注说 `model()` 是"末帧口径"，实现取**首个**非空 metadata 的 model（`:78-80`）。改注释即可。
- **N-2** `keep-tool-pairs` 按"工具结果**条数**"计数（`ToolResultClearing.java:100-101`：一个 assistant 带 3 个 tool_call 时算 3 条而非 1 对）；已注释，建议在 NUMSPEC 依据列注明"按 response 条数"。
- **N-3** `ContextState.write` 走 `putAll` 部分更新，`model`/`reason` 为 null 时不写 ⇒ 旧值残留（`ContextState.java:77-85`）；影响可忽略。
- **N-4** 端点无显式响应尺寸闸（ledger 20 条 + 七项固定 ⇒ 构造上有界）；建议在端点注释点明有界性来源。
- **N-5** `ContextPopover` 的 `role="dialog"` 无 `aria-modal`/焦点管理（与面板既有浮层惯例一致）；登记即可。

---

## 三、未复核/边界

- **E2E-CTX-1..4 与 stub usage 桩未落**（`scripts/` 本棒禁改）⇒ 验收 §9-2（E2E 计量四例）在本批不成立，仅由单测（C-01..C-06 + `AiControllerContextEndpointTest` + `ContextFrameWiringTest`）覆盖；见 M-5。
- 我**未跑 E2E**（禁起服务）；前端 build 实跑 ✓、后端/前端/typecheck/check-docs 均实跑。

## 四、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。
