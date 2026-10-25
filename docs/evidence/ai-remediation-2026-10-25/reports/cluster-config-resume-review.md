> 来源=<仓外证据>/cluster-config-resume-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# cluster-config-resume · 独立任务复核（B2 未知态专项）

- 日期：2026-10-25
- 被评对象：`<仓外证据>/cluster-config-resume-report.md` + `cluster-config-resume-34fd8f4.patch`（16 文件 / +1013 −87）
- 代码基准：主仓 `<主仓>` 工作树（`34fd8f4` + park-audit + 本补丁，补丁为未提交改动，HEAD 仍是 `34fd8f4`）。所有行号在该工作树上核对；与 `<验证副本>/_applycheck-34fd8f4`（HEAD `04facb4` = 34fd8f4 + 本补丁）逐字节一致，仅 CRLF 差异，故行号两边相同。
- 只读评审：未改任何源码/测试/文档（只新增本文件），未跑 Maven/前端构建，未起服务，未跑探针 run 模式与三实例拓扑。测试数字按任务要求采信报告，仅做日志/证据交叉核对（见 §4）。

---

## 0. 结论

| 维度 | 结论 | 说明 |
|---|---|---|
| **Spec Compliance** | **FAIL（阻塞）** | 任务 4 条硬要求：seed 发布版本（落实，但"恰好一次"有竞态 ⇒ Important-1）、判死前强制刷新（落实）、真缺失收敛终态（落实）、**"lease/state/config UNKNOWN 必须只读、不得转成失败"（违反）**：配置刷新的失败分支、以及"刷新没报错但读库失败"的静默空快照，都会走 `providerGone` ⇒ 落 `FAILED` 终态 + 终帧，把一次瞬时读库抖动变成不可恢复的判死（Critical-1）。 |
| **Task Quality** | **FAIL / 待修**（结构本身不错） | 唯一发布实现、两条派发入口共用收敛点、有界 SSE、幂等、断言升级带回例自证都属于高质量做法；但 B2 的错误处理恰恰是规范点名禁止的"把不知道当确定"，**无任何用例覆盖该分支**，报告 §6.3 还把它的描述写错（说成"行为与修复前一致"），属于"测试全绿但契约不成立"。 |
| **Blockers** | **1 必需 + 1 建议同批** | Critical-1 必须改后才可合入；Important-1 若"exactly once / no-op 不抖动"是验收口径，则同为阻塞（修复量 = 一行守卫 + 一条用例）。 |

---

## 1. 需求逐条核对

| # | 要求 | 结论 | 落点 |
|---|---|---|---|
| 1 | 种子写入必须发布 DB + Redis 配置版本 | **满足** | `AiConfigSeeder.java:113-124`（同事务 `bumpInTransaction`，提交后 `publishAfterCommit`）；`AiConfigVersionPublisher.java:74-101` |
| 2 | 并发实例下"恰好一次"、no-op 不抖动 | **部分满足（Important-1）** | no-op 路径正确（`AiConfigSeeder.java:97-101`、`:115-119`、`:145-150`）；但 `count()` 在事务外 + 重灌分支未受 `force` 约束 ⇒ 存在"版本 +2 / INCR 两次"的交错 |
| 3 | 判死前对陈旧快照强制 reload | **满足**（正常路径） | `ResumeService.java:1169-1185`；正例/反例用例见 `AiResumeExactProviderBindingTest`（stale 行在库 ⇒ ACCEPTED；真删除 ⇒ 仍 GONE） |
| 4 | reload 失败不得把 UNKNOWN 判成 PROVIDER_GONE | **不满足（Critical-1）** | `ResumeService.java:1178-1183` + `:1184` + `:1206` + `:1242` |
| 5 | 真缺失 provider 收敛 FAILED + 机器可读终态 + 有界 SSE | **满足** | `ResumeService.java:1197-1208`、`:1226-1258`；`RunSnapshot.isTerminal` 含 FAILED；`registry.close` 收流；重挂回放终帧后收流 |
| 6 | 租约未知必须只读、不得判死 | **满足（租约侧）** | `ResumeService.java:1231-1236`（`LOST`/`UNAVAILABLE` ⇒ 不写终态、不出业务帧）；用例 `ResumeProviderGoneTerminalTest#convergenceIsSkippedWhenTheLeaseAuthorityIsUnreadable` |
| 7 | 提交后语义（after-commit） | **满足** | 种子：`executeWithoutResult` 返回（已提交）后才发布（`AiConfigSeeder.java:114/122`）；写路径：`afterCommit()` 在 `executeInTransaction` 之后（`AiConfigWriter.java:260-268` 等 7 处） |
| 8 | Publisher 重构兼容（写路径行为不变） | **满足** | `AiConfigWriter.java:157-176` 构造器签名不变；`bumpVersion()/afterCommit()` 与 `34fd8f4` 的旧实现**逐字等价**（已 `git show 34fd8f4:` 对照）；`AiConfigWriterTest` 27 例零改动绿 |
| 9 | 错误码 / locale / 前端镜像 | **满足**（带 2 个 Minor） | `ErrorCodes.java:361`（`ALL` 走反射，自动纳入）；`messages_zh/en.properties:122`；`frontend/src/types/api.ts:209`；`ErrorCodesContractTest` 6/6 绿 |
| 10 | 直接确认 vs 重挂自愈两条入口都收敛 | **满足** | 收敛点只在 `ResumeService`，两条入口都经过它；用例 `ResumeProviderGoneDispatchTest`（真实 `ResumeService` + 真实 `ConfirmGate`，2/2） |
| 11 | 终态幂等 | **基本满足**（带 Minor） | `ResumeService.java:1239-1241` 终态跳过；`ResumeService.java:709-720` 入口先判终态 ⇒ 二次调用根本到不了 `providerGone`；用例 `alreadyTerminalRunIsNotRewrittenOnASecondAttempt` |
| 12 | 冷启动断言真实性 | **满足** | `cluster3-probe.mjs` 新增 `seededProviderIdentity / assertColdProviderIdentity / waitColdSeededProvider(30s)`；历史红证已解码复核（§4.1） |

---

## 2. 阻塞项

### Critical-1（必须改）：配置读失败/未知被当成 `PROVIDER_GONE`，并被写成永久 `FAILED`

**位置**
- `<主仓>/backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1178`（`catch (RuntimeException) → return null`）
- `<主仓>/backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1184`（刷新后仍空 ⇒ `null`）
- 消费方：`.../ResumeService.java:1206`（`providerGone` 一律收敛）→ `.../ResumeService.java:1242`（`FAILED`）
- 失败之所以"看不出来"：`.../config/AiConfigService.java:175-181`（`reload()` 无成功信号）与 `.../config/AiConfigService.java:269-275`（`load()` **吞掉所有 Exception**，失败时返回"上一份好快照或空快照"）

**问题**

1. `reload()` 在真实读库故障（Hikari 获取连接超时、迁移/表锁、只读切换、Redis 版本键读抖动后回落 DB 版本）时**不会抛异常**：`load()` 自己 catch 掉，返回 `ok=false` + 旧快照/空快照。于是报告写的"重载异常按仍为空处理并 WARN"这条分支在生产上基本是**死代码**；真正发生的是**静默**的"刷新没刷新到" ⇒ `resolveForResume` 第二次 `lookup.get()` 仍为空 ⇒ 判死。`AiConfigService` 目前也没有任何公开的 `lastLoadFailed()`/`reloadOutcome()` 让调用方区分"刷新成功但仍没有"与"刷新根本没成功"。
2. 即使 `reload()` 真抛异常（目前只有 `Error` 等极端情形才会绕过 `load()` 的 catch），`ResumeService.java:1181-1182` 也直接 `return null`，同样是判死。
3. 判死的后果在**本批被升级成不可恢复**：`providerGone` 现在会 `convergeProviderGone` ⇒ 快照 `FAILED` + 归档 `error` 终帧 + 收流。修复前同样的误判只回 409，快照留在 `SUSPENDED`，配置恢复后重试续跑即可自愈；现在是"一次 DB 抖动 = 这一轮永久失败"。这正是复核要求里点名要拒的形态（"state/config unknown must NOT be converted to failure"）。
4. 触发条件不苛刻：**快照里没有这一行**（= B2 要救的陈旧/空快照场景，也正是 run 40182796512 里 B/C 的形态）+ **任意一次读库抖动**，两者同时成立即可。集群/共享 H2-TCP/Postgres 的抖动脉冲在本仓历史批次里是常态（Hikari 超时、迁移锁都做过专项）。
5. 判据本身也比"确定没了"宽：`resolveChatTarget → targetFor`（`AiConfigService.java:540-547`）把"行在但 key 解不开/未配置（`KeyStatus != OK`）"也算空 ⇒ 同样进终态收敛。key 暂时解不开（KEK 配置缺失/轮换）属于"此刻不知道能不能用"，与"端点被删"不是同一件事；终帧文案却统一说"已被删除或停用"。本批用例只覆盖了"行被删/模型下架"，没有区分这一类。
6. 与仓内自身口径不一致：`resume()` 对"待决条目读失败"（`ResumeService.java:668-681`）与"取消状态未知"（`:691-698`）都返回 `STATE_UNKNOWN` + 503（`AI_REDIS_UNAVAILABLE`），**不判死**；只有这里的配置未知被例外处理成终态。
7. 用例缺口：`ResumeProviderGoneTerminalTest.java:93`、`ResumeProviderGoneDispatchTest.java:84` 只 stub `resolveChatTarget → empty`，`reload()` 走 Mockito 默认（返回 null、不抛）；`AiResumeExactProviderBindingTest` 的 fixture reload 永远成功。**没有任何用例覆盖"reload 失败/load ok=false"**，483 全绿不能证明该分支正确。

**判定**：报告 §1 把 B2 描述为"首次解析为空时强制 reload 一次再判"，只说对了前半句；§6.3 的"库抖动导致 reload() 抛错时按仍为空判定（并 WARN），**行为与修复前一致**"两处都不成立——reload 不抛，且修复后的行为是"写终态"，与修复前"可重试的 409"不同。

**最小修复建议（任一实现，二选一即需带红用例）**
- 让刷新结果可观测：`AiConfigService` 增 `ReloadResult reloadOrThrow()` / `LoadOutcome`（或至少 `boolean lastLoadOk()`），`resolveForResume` 在"刷新未成功"时返回一个三态标记；
- 调用点把该标记映射为既有 `Outcome.STATE_UNKNOWN`（503 + `AI_REDIS_UNAVAILABLE`，body 带 `reason=STATE_UNKNOWN`），**不调用** `providerGone` / `convergeProviderGone`，快照保持 `SUSPENDED`，由派发方/自愈重试；
- `convergeProviderGone` 的触发条件收紧为"**刷新成功**且解析仍为空"；
- 补两条 RED：① `reload()` 抛异常 ⇒ 仍是 `SUSPENDED`、归档 0 帧、outcome 非 `PROVIDER_GONE`；② `load()` 失败（ok=false，快照仍空）⇒ 同上。两条都应在当前实现下变红。
- 若团队坚持"不刷新成功就拒一次"，最低限度也必须**去掉终态收敛**（只 409，不改快照、不出终帧），否则一次抖动仍是永久失败。

---

### Important-1：种子"恰好发布一次"存在竞态；`writeSeed(force)` 的 `force` 参数从未被读

**位置**
- `.../ai/config/AiConfigSeeder.java:97`（`count()` 在事务**外**）→ `:114`（事务开始）
- `.../ai/config/AiConfigSeeder.java:140`（`private boolean writeSeed(boolean force)`，函数体内 **`force` 一次都没读**）
- `.../ai/config/AiConfigSeeder.java:143-156`（行已存在且 `source=ENV_SEED` ⇒ 无条件"重灌"）
- `.../ai/config/AiConfigSeeder.java:181`（重灌分支照样 `bumpInTransaction()`）→ `:122`（`INCR`）

**交错（可直接推演，不需要真实拓扑）**

1. 实例 C：`count()==0`（`:97`，A 尚未提交）。
2. 实例 A：完整走完 `writeSeed` → 提交（版本 1→2、`INCR` 0→1）。
3. 实例 C：进入事务（`:114`）→ `findByCode` 现在**看得到** A 已提交的行（READ_COMMITTED），`source=ENV_SEED`、`force=false`，但 `:145` 只拦"非 ENV_SEED"，于是走 `:151-156` 的"重灌"分支（值与 A 完全相同）→ `:181` 再 bump 一次 → 版本 2→3、`INCR` 1→2。

即：**同一份种子数据、同一次冷启动，版本与失效信号各推进两次**。数据收敛没问题（值相同、无重复行），但违反"exactly once / no-op 不抖动"这条要求，并让三实例多白跑一次全表 reload。`AiConfigSeederTest.java:239-276` 只覆盖了"两人都插入 → 唯一约束回滚"这一种交错；上面这种交错命中时该用例会以 `expected +1` 变红（既是缺陷，也是该用例的抖动源）。

**修复建议**
- `writeSeed` 里把 `force` 用起来：`if (existing.isPresent() && !force) { log.debug(...); return false; }`（`source!=ENV_SEED` 的分支保留在 `force=true` 时也跳过）。这样"count 之后才出现的行"退化为真正的 no-op，不 bump、不 INCR；
- 或把 `count()` 判断并入同一事务（`SELECT ... FOR UPDATE` 锁 `config.version` 行）再决定是否写；
- 补一条确定性用例：先手工插入 ENV_SEED 行，再调 `seeder.seed()`（`force=false`）⇒ 版本与 `INCR` 计数均不变（当前实现会 +1）。

---

## 3. 非阻塞问题（Minor）

| # | 位置 | 问题 |
|---|---|---|
| M1 | `ResumeService.java:1003` + `:1067` | 同一语义两套码：驱动路径遇到 `ProviderGoneException` 仍发 `AI_RESUME_FAILED`，但快照 error 前缀写 `PROVIDER_GONE`；新增的 `AI_PROVIDER_GONE` 只覆盖预检路径。客户端在"预检通过后才消失"的窗口里拿到的是另一个码（报告 §6.2 已登记双终帧，但没提码不一致）。 |
| M2 | `ResumeService.java:1232` | `authority != null && authority.readOnly()`：若某实现返回 `null` 表示"未知"，这里会**放行写终态**（fail-open），与"未知只读"相反。当前 `RunStore.runLeaseAuthority`（`:1121-1128`）已把 null 归一成 `NEVER_HELD`，所以只是潜在坑；终态写入口建议 fail-closed。 |
| M3 | `ResumeService.java:1244` | 收敛只清快照 `inFlightToolCalls`，**没有结算 DB 侧 PENDING 条目**（对比取消路径 `:704` 会 `cancelPendings`）。`GET /api/ai/pending/{runId}`（`AiController.java:1942`）仍会把它们列出来；前端靠 `awaitingExternal`（要求 `SUSPENDED`）才没建卡，但留下悬空条目。 |
| M4 | `ResumeService.java:1200-1205`、`AiController.java:1118` | 409 body 仍**没有 `code` 字段**（只有 `reason`），前端 `ApiError.code` 落成 `"409"` ⇒ `frontend/src/stores/ai.ts:2773` 的 `case 'PROVIDER_GONE'` 依旧不可达，代码注释"409 + 稳定码"名不副实。新码只存在于 SSE 终帧。 |
| M5 | `frontend/src/utils/error-card.ts:74` | 前端没有 `AI_PROVIDER_GONE` 的专属卡文案/动作（`CODE_CARD_TEXT` 无此键、也不在 `DEDICATED_ALERT_CODES:44`），只会落通用红卡 + detail 透传；类型镜像满足了契约测试，但用户面没有"重新发送一轮"的引导。 |
| M6 | `ResumeService.java:1205` vs `:1262-1274` | 409 body 的 message 恒中文（常量），SSE 终帧按 locale（en 走 `messages_en`）；同一次事件两个语言出口。属既有行为，本批只统一了文案来源，未统一 locale。 |
| M7 | `messages_zh.properties:122` vs `ResumeService.java:121-125` | zh 常量以"。"结尾、properties 无句号；zh 分支实际返回常量，`messages_zh` 的 zh 值成为死文案（只被 `ErrorCodesContractTest` 的存在性检查消费）。 |
| M8 | `AiConfigVersionPublisher.java:89-101` | `publishAfterCommit` 只兜 Redis 异常；`reload()`/`evictAll()` 若抛错会在**已提交之后**冒泡到 `seed()`（`AiConfigSeeder.java:122`），`run()` 记 "AI 配置种子未完成"（误导，数据其实已落库）。建议把后两者也包 try/catch（reload 目前内部已吞，evictAll 风险低）。 |
| M9 | `ResumeService.java:1245-1251` | `save` 与 `error()+close` 不是原子的：若 `save` 成功而 `out.error` 抛错，快照为终态但归档没有终帧；重挂时 `replayAndAttach` 走"等实时终帧"（`SseChatEmitter.java:692-705`）⇒ 又变成无界悬挂（概率低：需要序列化/落档失败，Redis 全挂时 `lastSeqIfKnown` 会先 503）。建议在 `finally` 里 close，或在重挂侧对"终态快照 + 无终帧"补一帧/直接收流。 |
| M10 | 报告 §6.3（`cluster-config-resume-report.md:119`） | 描述不准确：`reload()` 实际不抛（`AiConfigService.java:269`），且修复后不再"与修复前一致"（见 Critical-1）。应改为已识别风险 + 本轮未修/待修。 |
| M11 | 报告 §5（`:104-111`） | `run.sh --self-check 53/53` 缺**本补丁之后**的日志：`_p2-review/cluster3-clean-selfcheck-34fd8f4.log` 时间 18:00，早于补丁 18:48；`probe --self-check 28/28` 有 18:57 的 `probe-followup1.log` 为证。建议补一条 after-patch 自证日志（或注明"未留档"）。 |

---

## 4. 证据与测试数字核对

### 4.1 历史红证（解码后与报告一致）

`<仓外证据>/cluster3-34fd8f4-evidence/cluster3-evidence-40182796512/cases/config-hot-reload/config-reload.json` 的 `healthBefore`（body 为 Buffer，已解码）：

```
a: available=true  providerId=1    providers=[(id=1, synthetic=false, code=deepseek)]
b: available=true  providerId=null providers=[(id=null, synthetic=true,  code=deepseek)]
c: available=true  providerId=null providers=[(id=null, synthetic=true,  code=deepseek)]
redisVersionBefore = ""
```

- 证实报告/T1 的现场：B/C 只有合成行、默认 providerId 为空、`ai:cfg:ver` 未发布；同时证实合成行**确实省略 `id`**，探针按 `synthetic` 判据（而不是"有没有 id 键"）是对的。
- 也证实新断言（每实例 ≥1 条非合成行 + providerId 落在其中 + 三实例一致）在旧代码上会立刻红；`waitColdSeededProvider(30s)` 只是把"永远空"变成"有界失败 + 落最后观测"，不改变判定方向。

### 4.2 自证/测试日志

| 证据 | 内容 | 结论 |
|---|---|---|
| `_p2-review/probe-followup1.log`（18:57，补丁后） | `PASS 28 / FAIL 0`，含 `PASS 冷启动种子身份（非合成库行 + 三实例一致）（1 正例 + 5 反例）` | 与报告 28/28 一致 |
| `_p2-review/backend-full-followup1.log`（18:58，补丁后，主仓工作树） | 360 个测试类 / **2338 tests / 0 F / 0 E**（日志末尾被截断，无 `BUILD` 行，不能当"全量通过"的完整证据）；含 `AiConfigSeederConvergenceTest 3/3`、`AiResumeExactProviderBindingTest 6/6`、`ResumeProviderGoneDispatchTest 2/2`、`ResumeProviderGoneTerminalTest 3/3`、`ResumeProviderGoneTest 4/4`、`ErrorCodesContractTest 6/6`、`MessageSourceI18nSpringTest 9/9` | 与"聚焦 483"不矛盾；新增码的三方对账确实绿 |
| `_p2-review/cluster3-clean-selfcheck-34fd8f4.log`（18:00） | `PASS 53 / FAIL 0` | **补丁前**的树，不能作为本批 53/53 的证据（M11） |

### 4.3 未做（按要求/如实声明）

- 未跑任何 Maven/前端构建、未跑探针 run 模式、未起三实例拓扑（不假设真实拓扑）。
- 未验证真实 CI 的 `suspend-cross-instance` 是否转绿（依赖下一次 `cluster-3node`）；静态上 A1 的两个收敛通道（DB 版本 / `ai:cfg:ver`）成立。
- 未对 Critical-1 做运行时复现（需要注入读库失败；按"只读评审 + 不跑测试"的边界，改用源码路径推演 + 现有用例缺口证明）。

---

## 5. 放行建议

1. **先修 Critical-1**（不得把"刷新未成功"当 `PROVIDER_GONE`，不得据此写终态），补两条 RED 用例后再复跑本批聚焦集。
2. 同批修 Important-1（`writeSeed` 尊重 `force` + 一条 no-op 确定性用例），否则"恰好一次"这条验收口径不成立，且现有并发用例带抖动风险。
3. Minor 里建议至少处理 M1（同一语义两码）、M4/M5（前端可达性与引导）、M9（终态无终帧的无界边角）、M10/M11（报告纠偏与证据补档）；其余可记为后续 hardening。
4. 修完后本批的主体设计（唯一发布实现、单点收敛、有界终帧、断言前置）质量是好的，预期可从 FAIL 转为 PASS WITH ISSUES。
