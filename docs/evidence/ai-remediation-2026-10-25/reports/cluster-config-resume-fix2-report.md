> 来源=<仓外证据>/cluster-config-resume-fix2-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# cluster-config-resume · 复核修复（fix2）报告

- 复核对象：`<仓外证据>/cluster-config-resume-review.md`（Critical-1 / Important-1 阻塞，11 条 Minor）
- 修复范围：**只做 Critical-1 + Important-1**（按要求不追 Minor、不改正确的终态行为、不碰 park-audit 的
  `ConfirmGate` / `SpToolCallingManager` 归属批）
- 代码基准：主仓当前工作树 = `34fd8f4` + 本批第一版配置补丁 + park-audit 补丁（均为未提交改动）
- 施工副本：`<验证副本>/_verify-20261025-config-fix2`（`8a20c2f` = 上述工作树快照基线；`522d818` = 本次修复）
- 补丁（对**当前主仓工作树**，`git apply` 兼容）：`<仓外证据>/cluster-config-resume-fix2.patch`
  - 9 文件 / +733 −35；已在**当前主仓工作树的逐字节副本**上 `git apply --check` + 实 apply（见 §5）
- 本批**未触碰**：`ConfirmGate.java`、`SpToolCallingManager.java`、`SheetAudit.java`、`ToolCallEndTrace.java`、
  `ParkSettlementAuditTest.java`（补丁文件清单可证）

---

## 1 结论

| 复核项 | 处置 | 证据 |
|---|---|---|
| **Critical-1**（阻塞）：配置读失败/未知被当成 `PROVIDER_GONE`，并被写成永久 `FAILED` | 引入<b>可观测的刷新结局</b>（`reloadWithOutcome()`）与<b>行级三态</b>（`BindingState`）；续跑只有"刷新成功 + 确定不在"才判死，「刷新失败 / reload 抛错 / 密文解不开」走既有 `STATE_UNKNOWN`（503），快照保持 `SUSPENDED`、不写终态、不出业务帧、收掉当下订阅 | 8 条新用例先红（§3）；`STATE_UNKNOWN`/`PROVIDER_GONE` 的 409/503 区分另有 HTTP 层用例 |
| **Important-1**（阻塞）：种子"恰好一次"有竞态、`force` 从未被读 | `writeSeed` 尊重 `force`；`!force` 且行已存在时用**同一事务内**的 `seedAlreadyLanded()` 判真 no-op（不写/不 bump/不 INCR）；模型行缺失/停用或 env 真变了仍修好并 bump 恰好一次 | 1 条用例先红（版本 +2/INCR 两次）；另两条覆盖"缺失模型仍补""env 变化仍发布一次" |
| 终态行为（B1 的 FAILED + `AI_PROVIDER_GONE` 终帧 + 收流） | **未改动**，只在触发条件前加了"确定"这一道闸 | `ResumeProviderGoneTerminalTest` 的确定用例原样通过 |
| 11 条 Minor | **未追**（M1/M4/M5/M9/M10/M11 等按指令留作后续 hardening），本报告 §6 只做事实登记 | — |

---

## 2 改动（逐文件 + 为什么这样改）

### 2.1 `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java`

| 改动 | 为什么 |
|---|---|
| `reload()` 改为 `return reloadWithOutcome().snapshot();` | 刷新结局必须可观测（`load()` 吞异常 ⇒ `reload()` 既不抛也不报）。**返回契约不变**，写路径 `afterCommit`、`publishAfterCommit`、既有单测的 `reload()` 全部零改动 |
| 新增 `public record ReloadResult(ConfigSnapshot snapshot, boolean ok)` + `public ReloadResult reloadWithOutcome()` | 最小可观测面：`ok=false` 明确表示「这次没读到库，快照可能是旧的/空的」。**没有**改 `reload()` 的签名或抛异常语义 —— 既有 `mock(AiConfigService.class)` 的默认行为（`reload()` 返回 null、不抛）完全不受影响；新方法在替身未打桩时返回 `null`，调用方按「不可知」处理（fail-safe 方向） |
| 新增 `public enum BindingState { RESOLVED, ABSENT, KEY_UNREADABLE }` + `public BindingState bindingState(Long providerId, String modelId)` | `resolveChatTarget` 把三类事实压成一个空值：行没了 / 没声明 key / 密文解不开。判死只允许对第一、二类（配置层面的确定状态），第三类是环境问题（KEK 缺失/轮换/密文损坏），修好即能续。`ABSENT` 与 `KEY_UNREADABLE` 的分界刻意保留既有口径（`ResumeService.Outcome#PROVIDER_GONE` 的注释本就把"密钥不可用"列为确定不可用；`key_ref=NONE` 是显式配置动作） |
| `modelDeclared(...)` 降为私有内部方法 | 只有 `bindingState(null, modelId)` 的兼容路径用它（无 `providerId` 的旧/冻结快照：模型名还在 ⇒ `KEY_UNREADABLE`（保守，不判死）；一个 enabled provider 都不声明它 ⇒ `ABSENT`） |

### 2.2 `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`

| 改动 | 为什么 |
|---|---|
| 新增 `record ResolvedIdentity(ChatTarget target, boolean unknown, String detail)` | 三态判据的载体：`present` / `absent` / `unknown(detail)` |
| `resolveForResume(runId, lookup, binding)` 重写 | 顺序：①缓存命中 ⇒ PRESENT；②强制 `reloadWithOutcome()`，**`null`/抛错/`ok=false` ⇒ UNKNOWN**；③刷新后再解析，命中 ⇒ PRESENT；④`bindingState`：`KEY_UNREADABLE` ⇒ UNKNOWN、`RESOLVED` 但解析为空 ⇒ UNKNOWN（自相矛盾按保守处理并 WARN）、`ABSENT` ⇒ ABSENT |
| 预检分支改为三态消费（两个分支：精确绑定 / 无 providerId 的冻结兼容） | `unknown` ⇒ `stateUnknown(...)`；只有 `target == null` 且非 unknown 才 `providerGone(...)`（= 唯一的判死入口） |
| 新增 `stateUnknown(runId, snapshot, detail)` | 对外 503 语义：`reason=STATE_UNKNOWN, code=AI_REDIS_UNAVAILABLE`（与"待决条目读失败"、"取消状态未知"同口径，`AiController` 早已映射 503）；**不写快照、不出终帧**；并调用 `releaseSubscriptionsWithoutFrame` |
| 新增 `releaseSubscriptionsWithoutFrame(runId)` | 「不留无界 emitter」：用 `registry.find(runId)`（不存在就不造）+ `completeSubscriptions()`（park 的"挂起即断"同一条语义：不置 `terminating`、不写帧、不摘注册表条目），连接干净收尾，重挂仍可回放并重试 |
| `providerGone(...)` 只加注释约束 | 终态收敛行为**一字未改**（FAILED + `AI_PROVIDER_GONE` 终帧 + `registry.close`），只在 javadoc 里写明"只有确定才到这里" |

### 2.3 `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java`

| 改动 | 为什么 |
|---|---|
| `writeSeed(boolean force)` 真正读 `force`：行已存在 + `!force` + `seedAlreadyLanded(provider)` ⇒ `return false`（不写、不 bump、不 INCR） | 复核的交错：C 读到 `count()==0` → A 完整种好提交 → C 才进事务，`findByCode` 现在看得见 A 的行，旧实现会原样重灌并再 bump（版本 +2 / INCR 两次） |
| 新增 `seedAlreadyLanded(provider)` | **只比"种子会写的字段"**：displayName / baseUrl / completionsPath / keyLast4 + 目标模型行存在且 `enabled && isDefault` + 不存在第二个默认行。用"将要写的值 vs 行里的值"比较，而不是"行存在就跳过"，所以**模型行缺失/停用、env 真变了（baseUrl/model 换名）仍然照常修好并 bump 一次**（"不得跳过缺失或变更的模型"） |
| force 分支的日志改为 `重灌 provider=…（force=…）` | 旧文案"按 seed-force 重灌"在 `!force` 的补写路径上会误导 |

### 2.4 测试（6 文件，含 1 个新增）

| 文件 | 用例 |
|---|---|
| `AiConfigSeederConvergenceTest`（+3） | ① `rowAppearingAfterTheCountCheckIsATrueNoOp`（复核交错：`count()` 重打桩为 0，第二次 `seed()` 必须 false，版本/INCR/行数全不变）；② `missingModelRowIsStillRepairedByTheInterleavedSeedRun`（模型行被删 ⇒ 仍补 + bump 一次）；③ `changedEnvIsAppliedOnceEvenWhenTheRowAppearedConcurrently`（env baseUrl 变了 ⇒ 写入 + bump 一次） |
| `AiResumeExactProviderBindingTest`（+3，真实 `AiConfigService`） | ① `unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown`（注入读 provider 表失败，`load()` 吞掉 ⇒ `ok=false`）；② `unreadableKeyYieldsStateUnknownNotProviderGone`（写侧 KEK_A 加密、读侧 KEK_B ⇒ `KEY_UNREADABLE`）；③ `missingKeyDeclarationIsKnownAbsentAndStillConverges`（`key_ref=NONE` ⇒ `ABSENT` ⇒ 仍 GONE + FAILED，锁住两条口径的边界）；并把 `genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload` 从"只断 outcome"加强到"断 FAILED"（复核要求的"刷新成功后真删除仍收敛"） |
| `ResumeProviderGoneTerminalTest`（+3） | `unsuccesfulReloadIs…` / `throwingReloadIs…` / `unreadableKeyIs…` ⇒ 均断言 `STATE_UNKNOWN` + `reason=STATE_UNKNOWN` + 快照 `SUSPENDED` + 归档 0 帧 + 不驱动 |
| `ResumeProviderGoneDispatchTest`（+2） | 重挂自愈 / 直接确认各一条 UNKNOWN：快照仍 `SUSPENDED`、**无终帧**（done/error 均不得出现；决策回执这类非终帧不受影响）、并且**真挂一条订阅后 `subscriberCount()` 必须归零**（= 不留无界 emitter） |
| `ResumeProviderGoneTest` | 替身补 `reloadWithOutcome()→ok` 与 `bindingState→ABSENT`（"确定"口径），`verify(...).reload()` 改为 `reloadWithOutcome()` |
| `AiControllerResumeOutcomeMappingTest`（**新增**） | HTTP 面区分：`STATE_UNKNOWN` ⇒ **503**（可重试），`PROVIDER_GONE` ⇒ **409**（判死）；锁的是"B2 修正之后这个区分真的会被用到" |

---

## 3 RED → GREEN（先红后绿，均为实测）

命令（IDEA 自带 Maven；**无 `clean`**、`-o` 离线、`-Dspring.data.redis.port=1`；不启 8081/5202、不起真实拓扑）：

```
MVN="<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
cd backend && "$MVN" -B -o test -Dspring.data.redis.port=1 -Dtest=<类名>
```

RED 的构造方式：把 **`AiConfigSeeder.java` + `ResumeService.java` 两文件回退到基线 `8a20c2f`**（即复核所指的实现），
保留 `AiConfigService` 的**新增** API（纯增量，替身仍可 stub），于是用例编译得过、语义按旧行为跑：

| 类 | RED | GREEN |
|---|---|---|
| 四类合计 | **Tests run: 25, Failures: 8**（`BUILD FAILURE`） | **25, Failures: 0**（`BUILD SUCCESS`） |
| `AiConfigSeederConvergenceTest` | 1：`rowAppearingAfterTheCountCheckIsATrueNoOp:142 [另一实例已种好且与 env 逐值一致 ⇒ 真 no-op]` | 6/6 |
| `AiResumeExactProviderBindingTest` | 2：`unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown:193 [刷新没成功 ⇒ 不可知（503），不是 PROVIDER_GONE]`、`unreadableKeyYieldsStateUnknownNotProviderGone:225 [key 不可用是'不可知'，不是'端点被删']` | 9/9 |
| `ResumeProviderGoneDispatchTest` | 2：`reattachSelfHealOnUnknownConfigReleasesTheHungStream`、`directConfirmOnUnreadableKeyReleasesTheHungStream`（均卡在「不可知 ⇒ 不得判死」，实测被判成 FAILED） | 4/4 |
| `ResumeProviderGoneTerminalTest` | 3：`unsuccesfulReloadIs…`、`throwingReloadIs…`、`unreadableKeyIs…`（均卡在「503 语义（STATE_UNKNOWN），不是 409 PROVIDER_GONE」） | 6/6 |

> 旧实现为什么必红：`load()` 吞异常 ⇒ `ok=false` 时快照仍是旧/空，旧 `resolveForResume` 把"空"一律当 GONE；
> `bindingState` 那两类（`KEY_UNREADABLE`）在旧实现里根本不参与判断。两者都落到 `convergeProviderGone` ⇒ `FAILED` +
> 终帧，正是复核点名的"一次抖动 = 永久失败"。

---

## 4 回归面（聚焦）

| 批次 | 选择 | 结果 |
|---|---|---|
| cfg | `AiConfig*Test,AiResume*Test,ExactProviderBindingTest,ProviderRegistryHotReloadTest,AiProviderRegistry*Test,AiDefaultTargetBindingTest,AiChatTargetResolutionTest,AiProperties*Test` | **193 / 0F / 0E** |
| resume | `Resume*Test,MultiCardParkResumeTest,StartupResumeRunner*Test,*ConformanceTest,FrameContractFalsificationTest,FrameTypeSetFrozenTest` | **165 / 0 / 0** |
| errloc | `ErrorCodes*Test,MessageSource*Test,*Locale*Test` | **43 / 0 / 0** |
| webgate | `AiControllerResumeOutcomeMappingTest,AiController*Test,ConfirmGate*Test,HumanWaitParkTest,ParkSettlementAuditTest` | **235 / 0 / 0** |
| 合计 | | **636 tests / 0 failures / 0 errors / BUILD SUCCESS** |

其它门禁：`node scripts/ci/cluster3-probe.mjs --self-check` → **PASS 28 / FAIL 0**；`node scripts/ci/check-docs.mjs` → **6/6**。
（本批未改探针与前端；`webgate` 里 `ParkSettlementAuditTest`、`ConfirmGate*Test` 全绿即"没破坏审计批"的回归证据。）

---

## 5 补丁可应用性（对**当前主仓工作树**）

```
cp -a <主仓当前工作树> <验证副本>/_applycheck-config-fix2       # 逐字节副本，含两笔未提交补丁
cd <验证副本>/_applycheck-config-fix2
git apply --check <仓外证据>/cluster-config-resume-fix2.patch   # OK（9 文件 / +733 −35）
git apply <patch>                                                        # APPLIED OK
cd backend && mvn -B -o test -Dspring.data.redis.port=1 -Dtest='AiConfigSeederConvergenceTest,AiConfigSeederTest,
  AiResumeExactProviderBindingTest,ResumeProviderGoneTerminalTest,ResumeProviderGoneDispatchTest,
  ResumeProviderGoneTest,AiControllerResumeOutcomeMappingTest'
# → 见 §5.1 实测数字
```

§5.1 实测（打上补丁后的当前主仓副本）

```
[INFO] Compiling 305 source files with javac ... to target\classes
[INFO] Compiling 428 source files with javac ... to target\test-classes
[INFO] Tests run: 6, Failures: 0 ... AiConfigSeederConvergenceTest
[INFO] Tests run: 6, Failures: 0 ... AiConfigSeederTest          （Spring 真实装配）
[INFO] Tests run: 9, Failures: 0 ... AiResumeExactProviderBindingTest
[INFO] Tests run: 4, Failures: 0 ... ResumeProviderGoneDispatchTest
[INFO] Tests run: 6, Failures: 0 ... ResumeProviderGoneTerminalTest
[INFO] Tests run: 4, Failures: 0 ... ResumeProviderGoneTest
[INFO] Tests run: 2, Failures: 0 ... AiControllerResumeOutcomeMappingTest
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 补丁**不含** `ConfirmGate.java` / `SpToolCallingManager.java` / `SheetAudit.java`（`grep '^diff --git'` 清单可证），
  park-audit 的归属批原样保留；apply 后 `git status` 里那三笔仍是审计批自己的改动。
- 施工副本 `cluster3-run.sh --self-check` 的 `tree_clean` 项会因"工作树带本批改动"红 —— 这是守卫按设计工作
  （上一批已说明）；本批未改该脚本，也未改探针。

---

## 6 未做 / 剩余风险（如实）

1. **Minor 全部未追**（按指令）：M1（驱动路径仍发 `AI_RESUME_FAILED`）、M4（409 body 无 `code`）、M5（前端无专属卡文案）、
   M9（`save` 成功但 `out.error` 抛错时"终态无终帧"的边角）、M10/M11（上一版报告的措辞与日志补档）。
2. **UNKNOWN 无自动有界重试**：派发方（`ConfirmGate`）只对 `POOL_SATURATED`/`SESSION_BUSY` 重派；`STATE_UNKNOWN`
   的重试路径是"下一次重挂自愈 / 启动扫描 / 客户端重试"。刻意没动 `ConfirmGate`（审计批归属面），
   这不会造成悬挂（订阅已收），但抖动恢复后需要一次新的触发。
3. **`MISSING`（从未声明 key）仍判死**（`ABSENT`），与既有 `PROVIDER_GONE` 口径一致；若团队希望"没配 key 也算可恢复"，
   改 `bindingState` 一行即可（并有 `missingKeyDeclarationIsKnownAbsentAndStillConverges` 充当口径锁）。
4. **真实 PG / 三 JVM 拓扑仍是 CI 边界**：本机只跑单测与探针自证（未起 8081/5202、无真实 Redis/PG）。
5. `reloadWithOutcome()` 在替身未打桩时返回 `null`，调用方按"不可知"处理；这是刻意的 fail-safe 方向（不判死），
   已在代码注释与用例（`throwingReload…`/`unsuccesfulReload…`）里锁住。

---

## 7 一句话

复核的两个阻塞都按"把不知道当知道"的方向收口了：**刷新成功与否可观测、行级可用性三态化**，续跑从此只有
「刷新成功 + 确定不在」才落 `FAILED`，其余一律 `STATE_UNKNOWN`（503、快照仍 `SUSPENDED`、无终帧、连接收干净）；
种子写路径把 `force` 真用起来并在**同一个事务**里判"这一版种子是否已完整落地"，使 `count()` 之后别人提交的交错
退化为真 no-op（版本/失效信号各恰好一次），同时不跳过缺失/变更的模型行。
