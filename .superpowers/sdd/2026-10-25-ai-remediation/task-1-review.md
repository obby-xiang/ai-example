# Task 1 独立复核：配置播种、恢复判定与供应商密钥边界

- **复核对象**：`task-1-brief.md` / `task-1-report.md` / `<仓外证据>/task1-config-seed-resume-key-boundary.patch`
- **复核基线**：主仓工作树 `34fd8f4` + 第一版配置候选 + park-audit 候选（未提交），即 `<主仓>` 的**当前内容**（不是任何隔离克隆的 SHA）
- **复核方式**：只读静态审查 + 独立复跑（未提交、未改主仓源码、未起 8081/5202、未跑全量）
- **复核补丁状态**：`git apply --check` 对当前主仓 **PASS（exit 0）**；`git apply --stat` = 11 文件 / +873 −41（与报告一致）

## 结论

| 维度 | 结论 |
|---|---|
| **Spec Compliance** | **PASS** —— brief 四条硬要求全部落地且我独立复现了 RED/GREEN；文件清单有 3 个"列了但未改"的偏差，属设计改位（边界放在写路径），行为与判据由独立用例锁住，可接受 |
| **Task Quality** | **GOOD（可合并，带后续项）** —— 无 Critical；3 条 Important（1 条是状态区分内部不一致，2 条是范围外但产品可见的后续项）；8 条 Minor（多为报告口径与测试覆盖细节） |
| **合并建议** | **Merge**。补丁可干净应用、9/9 与 66/66 均独立复现。建议把 **I-1**（约 5 行，同一文件）随本补丁合入或当场登记 issue；**I-2 / I-3** 属本任务文件清单之外的后续项，不阻塞本补丁 |

## 0. 我独立复现的证据（不是照抄报告）

```
# ① 可应用性（当前主仓，只读）
git apply --check --verbose <仓外证据>/task1-config-seed-resume-key-boundary.patch   → EXIT 0

# ② 相邻 6 类（补丁已应用的核对树 <隔离副本>/_applycheck-task1）
mvn -B -o test -Dspring.data.redis.port=1 \
  -Dtest='AiConfigWriterTest,AiConfigSeederConvergenceTest,ResumeProviderGoneTerminalTest,
          AiResumeExactProviderBindingTest,AiConfigSeederTest,AiConfigWritePersistenceTest'
→ Tests run: 66, Failures: 0, Errors: 0 — BUILD SUCCESS        （日志：<仓外证据>/review-task1-adjacent.log）

# ③ RED 复现（把 3 个行为文件换回主仓版本，保留 AiConfigService 新 API，跑 brief 指定的 9 条）
→ Tests run: 9, Failures: 7                                  （日志：<仓外证据>/review-task1-red.log）
   seedDoesNotPublishTwiceForNoOpOrConcurrentSeed  (AiConfigSeederConvergenceTest:142)
   providerProbeCannotSendRetainedKeyToChangedHost (AiConfigWriterTest:259)
   reloadFailureCannotBecomeProviderGone / keyFailureCannotBecomeProviderGone /
   missingAfterSuccessfulReloadConvergesTerminal   (ResumeProviderGoneTerminalTest:170/170/112)
   unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown /
   unreadableKeyYieldsStateUnknownNotProviderGone  (AiResumeExactProviderBindingTest:193/225)
# 红中仍绿：missingKeyDeclarationIsKnownAbsentAndStillConverges、genuinelyDeletedProviderStillYieldsProviderGone…

# ④ 报告"未在 cap 内重复跑"的两个 carry-over 类（复核补跑，因为 stateUnknown 的收流语义只在这两个类里有断言）
mvn … -Dtest='ResumeProviderGoneDispatchTest,AiControllerResumeOutcomeMappingTest'
→ Tests run: 6, Failures: 0, Errors: 0 — BUILD SUCCESS        （日志：<仓外证据>/review-task1-extra.log）
```

RED 的构造方式与报告 §2 完全一致（回退 `AiConfigSeeder` / `ResumeService` / `AiConfigWriter` 三个行为文件），失败用例集合、行号、断言文案与报告逐条吻合 —— **报告的 RED/GREEN 证据可信，不存在"报告绿、实测红"**。唯一需要修正的是"读库失败"那一条的**旧码实测值**：mock 用例是 `PROVIDER_GONE`（真复现），真实读库用例却是 `ACCEPTED`（旧码隐式重试又成功了），详见 M-7。

> 复跑副作用：`_applycheck-task1` 的 3 个源码文件在 RED 后已按补丁内容复原（md5 与 RED 前逐字节一致）；其 `target/classes` 里可能还留着 RED 时刻的 class，下次构建会因源码更新而重编（不影响证据）。

## 1. 四条硬要求逐条核对

| 要求 | 结论 | 证据 |
|---|---|---|
| no-op / 并发播种不得重复 bump | **满足** | `AiConfigSeeder.java:161` + `:209-232` 的 `seedAlreadyLanded`（只比"重灌会写的那些列" + 目标模型 enabled/is_default + 无第二个默认行），no-op 返回 false ⇒ `seed()` 在 `publishAfterCommit()` 之前返回（`AiConfigSeeder.java:113-122`）；真变化仍同事务 bump 一次。测试：`seedDoesNotPublishTwiceForNoOpOrConcurrentSeed`（RED:142 → GREEN）、`missingModelRowIsStillRepaired…`、`changedEnvIsAppliedOnce…`，以及真实并发的 `AiConfigSeederTest#concurrentDoubleStartupSeedsExactlyOneRow`（H2 唯一索引，实测日志出现 `UQ_AI_PROVIDER_CODE_INDEX` 冲突后版本恰好 +1） |
| 读库/加载/解密失败 → UNKNOWN/只读，而不是永久 PROVIDER_GONE | **满足** | `AiConfigService.load()` 的 `ok=false` 经 `reloadWithOutcome()`（`AiConfigService.java:201`）上抛；`ResumeService.java:1227-1231` 对 `null`/`!ok` 直接返回 `unknown`；`BindingState.KEY_UNREADABLE`（`AiConfigService.java:763-769`）→ `ResumeService.java:1244`。`stateUnknown`（`ResumeService.java:1264-1276`）**不写终态、不出业务帧**，只收掉当前订阅。测试：mock 两条 + 真实 `AiConfigService` 两条（`pendingProviderLoadFailures`、KEK_A/KEK_B）。RED 实测值并不一致：mock 的 `reloadFailureCannotBecomeProviderGone` 与真实 KEK 的 `unreadableKeyYieldsStateUnknownNotProviderGone` 为 **PROVIDER_GONE**；真实读库的 `unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown` 为 **ACCEPTED**（旧码 `lookup.get()` 里的 `current()` 又重试了一次、第二次读库成功）⇒ 后一条并没有复现"永久判死"，只复现了"旧码靠隐式重试吞掉一次读库失败"（见 M-8） |
| 只有"成功重载后的确定缺失"才 FAILED + 有界终局 | **满足** | `providerGone` 仅在 `ok=true && BindingState.ABSENT` 之后到达（`ResumeService.java:1232-1252`）；终局仍是 `FAILED` + `AI_PROVIDER_GONE` 归档终帧 + 收流。测试 `missingAfterSuccessfulReloadConvergesTerminal` 断言终帧 `type=error, code=AI_PROVIDER_GONE`，护栏 `genuinelyDeletedProviderStillYieldsProviderGone…` 红时即绿 |
| 改 origin 保留旧 key 必须拒绝，且不削弱私网规则 | **满足** | `AiConfigWriter.java:318-323`（`origin = scheme+host+port`，`:878-889`），位于 `checkBaseUrl`（`:287-289`）**之后**；`clearKey`/新 key 放行；`/test` 与 `/models/fetch` 只读库里的 `(baseUrl, key)` 对（`AiConfigController.java:137-160`、`AiModelCatalog.java:81`），探针 `Redirect.NEVER`（`AiConnectionProbe.java:111`）⇒ 写路径拒绝即足以消除 ID-03 的配对。四条用例分别锁"拒 / 同源保留 / 换源带新 key / 私网仍 BLOCKED_PRIVATE_ENDPOINT" |

**文件清单偏差（Minor，非违规）**：brief 的 Files 列了 `AiConfigController` / `AiConnectionProbe` / `AiEndpointGuard`，补丁未改它们 —— 边界改到"配对产生处"后确实不需要改探针；行为由 `privateHostChangeIsStillBlockedByTheEndpointGuard` 与探针既有实现共同保证。`AiConfigVersionPublisher` 也未在本补丁中改动（上一轮 carry-over）。

## 2. 重点检查项

### 2.1 `reloadWithOutcome` API 与 Mockito/默认行为 —— 通过

- `reload()` 改为委托 `reloadWithOutcome().snapshot()`（`AiConfigService.java:175-177`），签名/返回/语义逐字不变；`load()` 本就返回 `LoadOutcome(snapshot, ok)`（`:284-302`），补丁没有改 `load()`/`applyLoad`，`current()` 路径 `:166` 也保持原样 ⇒ 无行为漂移。
- `Mockito.mock(AiConfigService)` 的 `reloadWithOutcome()` 默认返回 **null**，`ResumeService.java:1227` 显式把 null 当 unknown（"替身没打桩就宁可不判死"），不会 NPE、也不会误判死。三个 resume 用例（`ResumeProviderGoneTest` / `…TerminalTest` / `…DispatchTest`）都补了 stub；唯一 `verify(reload())` 已改成 `verify(reloadWithOutcome())`（`ResumeProviderGoneTest:141` 一带）。
- 我按包扫过 `setField(… "aiConfigService")` 与 `mock(AiConfigService.class)`：除上述 3 个类 + `AiResumeExactProviderBindingTest`（真实服务）外，其余都在 `AiController` 上注入，不进 `ResumeService` 的新分支 ⇒ **没有未更新而静默失效的旧用例**。
- 生产侧 `reload()` 的其它调用点（`AiConfigSeeder:130`、`AiConfigVersionPublisher:99`、`AiConfigService.current()`）不受影响。
- 唯一"未来的坑"：新写的 mock 用例若忘记打桩 `reloadWithOutcome()`，会**静默**落到 UNKNOWN（而不是像旧码那样 PROVIDER_GONE）。建议测试夹具里显式提供默认 stub，让"默认=不可知"这件事可见（Minor）。

### 2.2 绑定状态的精确区分 —— 一处内部不一致（I-1）

三个状态本身口径正确：`RESOLVED` / `ABSENT`（行/模型不在、停用、模型不在 allowlist、**从未声明 key**）/ `KEY_UNREADABLE`（声明了却解不开）。`resolveKey` 的 `MISSING` 只在 keyRef=NONE 或 ENV 无 key 时出现，`UNREADABLE` 只在 DB 密文解密抛 `KeyCipherException` 时出现（`AiConfigService.java:948-974`），所以 `:769` 的三分是站得住的。

**但 `providerId == null` 的旧/冻结快照分支把"有声明但不可用"一律当成不可知**（`AiConfigService.java:746-749`）：

```java
return modelDeclared(snap, modelId) ? BindingState.KEY_UNREADABLE : BindingState.ABSENT;
```

`modelDeclared` 只看"某个 enabled provider 的可选模型里有这个名字"，**不看 key 是否声明/可解**。于是旧快照 + 某个 provider 的 `keyRef=NONE`（或 ENV key 被移除）⇒ 判成 `KEY_UNREADABLE` ⇒ `STATE_UNKNOWN`，永远不收敛（见 I-1）。这与本类自己写的"从未声明 key 是确定的配置状态"、以及 `providerId != null` 分支的口径相反。方向是 fail-safe（不会误判死），但让一个**确定**状态落进了"永不需要终局"的桶。

### 2.3 事务 / after-commit / 并发交错 —— 通过

- no-op 分支在 `TransactionTemplate` 内只读、不写、不 bump（`AiConfigSeeder.java:161-166`），返回 false 后 `seed()` 在 `versions.publishAfterCommit()` **之前** return（`:113-122`）⇒ "数据没写就不推进版本、不 INCR、不 evict" 三件事同时成立。
- 真变化仍是"同事务 `bumpInTransaction()`（`:198`）+ 提交后 publish" —— 未改动。
- 交错覆盖：`count()==0` 之后别人提交（确定性桩）+ 真双线程并发（Spring/H2 唯一约束）。两种路径下版本都恰好 +1；输掉竞争方整个事务回滚（含 bump）。**残余但非新问题**：两个实例若在"别人已提交"之后都读到**不同 env**，会各写一次、各 bump 一次（`bumpInTransaction` 是读-改-写，理论上可丢一格）；数据本身也是后写覆盖，与 `AiConfigVersionPublisher` 文档里声明的前提（低频人工写入）一致，不构成本任务缺陷。
- `stateUnknown` 在 `resume()` 的 `try` 内提前返回，`finally` 的 `resuming` 清理/租约归还逻辑不变；`Outcome.STATE_UNKNOWN` 在 `releasesLease()` 白名单内（`ResumeService.java:289-295`）⇒ 不会攥着租约无人驱动。

### 2.4 错误码兼容 —— 基本通过，有一处语义借用（M-5）

- `PROVIDER_GONE` 仍是 409 + `reason/message`（`AiController.java:1118`），行为未变；`STATE_UNKNOWN` 仍是 503（`:1112`），与"待决条目读失败/取消状态未知"同口径 —— 这是 **复用**，不是新造。
- `stateUnknown` 的 `code` 借用了 `AI_REDIS_UNAVAILABLE`（`ResumeService.java:1272`），而本次触发它的多是 PG 读失败或 KEK 问题，"Redis 不可用"对排障有误导。前端并不消费这个码（`POST /runs/{id}/resume` 在前端无调用点；resume 走 GET /events 的自愈），所以没有用户可见的错文案，但 API/脚本侧会读到错码。
- 未发现任何调用方依赖"刷新失败也回 409"。

### 2.5 key 保留 vs 合法同源编辑 —— 通过（覆盖有缺口）

- 同源（scheme+host+port 相同）只改显示名/路径/开关 ⇒ 放行（`sameHostEditsKeepTheRetainedKey`），`updateWithoutApiKeyKeepsTheCipher` 也改成同源编辑，语义"留空=不改"仍在。
- 换 origin + 新 key / `clearKey=true` ⇒ 放行（`hostChangeWithAFreshKeyIsAllowed`；`clearKey` 走 `KeyMaterial.cleared()`，非 null ⇒ 跳过新闸）。
- 换 origin + 保留旧 key ⇒ `INVALID_PARAM`，整体拒绝（无半行写入、无版本变更）。
- 私网规则仍先跑（`checkBaseUrl` 在 key 判定之前），错误码可区分。
- **缺口**：`clearKey=true` 与"换 origin"的**组合**没有用例 —— 这正是"已有 key 的 provider 换主机"唯一被允许的操作序列，值得补一条（M-4）；`https→http` / 仅换端口这两种"origin 变了但 host 没变"的用例也没有（`originOf` 的核心主张就是覆盖它们）。

### 2.6 测试是否真正复现原缺陷 —— 大体是，三条要打折

| brief 用例 | RED 实际证明的是 | 评价 |
|---|---|---|
| `seedDoesNotPublishTwiceForNoOpOrConcurrentSeed` | 旧码对"别人已种好且逐值一致"返回 **true**（会重灌） | 真复现；但断言顺序让 RED 在第一个 `isFalse()` 就失败，**版本/INCR 的重复从未被观测到**（M-3） |
| `reloadFailureCannotBecomeProviderGone` | 旧码 `STATE_UNKNOWN` → `PROVIDER_GONE`（真行为差异） | mock 版真复现；**真实 `AiConfigService` 版不算**（只注入 1 次读库失败，旧码隐式重试成功、RED 值是 ACCEPTED，见 M-7） |
| `keyFailureCannotBecomeProviderGone` | 同上（解不开 → 判死） | 真复现（真实服务版用 KEK_A/KEK_B 复现） |
| `missingAfterSuccessfulReloadConvergesTerminal` | 只有 `verify(reloadWithOutcome())` 落空；`FAILED`+终帧在旧码**本来就绿** | **不是缺陷复现**，是"判死前提"的契约断言；报告 §0 把它列进"目标缺陷 RED"略有夸大（M-2） |
| `providerProbeCannotSendRetainedKeyToChangedHost` | 旧码"改 host + 留旧 key"不抛（配对可创建） | 真复现（写路径级；探针本身未出网，符合 brief 约束） |

## 3. 问题清单

### Critical
无。

### Important

**I-1｜旧快照分支把"确定缺失"误标成"不可知"，可无限挂起**
`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:746-749`（补丁 hunk `AiConfigService.java` +165…+171）
`bindingState(null, model)` 只要模型名被**任何** enabled provider 声明就返回 `KEY_UNREADABLE`，不区分该 provider 的 key 是 `UNREADABLE` 还是 `MISSING`/未声明。后果：providerId 为空的旧/冻结快照命中"provider 有该模型但从未声明 key / ENV key 被移除"时，`ResumeService.java:1244` 走 `stateUnknown` ⇒ 快照永远 `SUSPENDED`、无终帧，与本类 `:767-769` 及报告 §4.1 自述的"从未声明 key = 确定"相矛盾。
方向是 fail-safe（不会误判死、无数据破坏），且只影响 I3 之前的旧快照，概率低。
建议：该分支按"声明该模型的 provider"逐个看 key：存在 `OK` ⇒ 不可知（自相矛盾，保守）；存在 `UNREADABLE` 且无 `OK` ⇒ `KEY_UNREADABLE`；全为 `MISSING`/未声明 ⇒ `ABSENT`。约 5 行。

**I-2｜新拒绝在设置页只显示"参数不在允许范围 / 请在默认模型下拉重选供应商"，无法自解释**（范围外，需后续项）
`backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java:318-323` 抛 `INVALID_PARAM`；前端 `frontend/src/constants/ai-settings-copy.ts:133` → locale `ai.settings.errors.INVALID_PARAM`（zh: "参数不在允许范围，或默认目标不合法：…在「默认模型」下拉中重新选择供应商后重试"），`frontend/src/views/AiSettingsView.vue:293-295` 只按 code 取文案、**从不回显后端 message**（这是既有纪律）。
即：用户改 host 保存 → 被拒 → 看到一条与"重录 key"完全无关的提示。功能上安全（拒绝生效），可发现性为零。
建议：为这条边界单独发码（如 `KEY_BOUND_TO_HOST`）并在前后端词表各加一句"改主机需重新录入 key 或先清空 key"；或至少在 Task 3/8 的前端批次里补。属 brief 文件清单外，不阻塞本补丁。

**I-3｜`STATE_UNKNOWN` 后端无有界重试驱动，重试完全依赖客户端重挂**（已披露，需登记）
`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1264-1276`：不写终态是对的（要求如此），但 `ConfirmGate.runResume` 只对 `POOL_SATURATED`/`SESSION_BUSY` 做有界重派发（`ConfirmGate.java:1973-1981`），启动扫描只在进程启动时跑；未知态的再驱动只有"客户端重挂 → `AiController` 自愈对账 `triggerResumeForParked`"（`AiController.java:782`）以及前端 `tryReattach` 的 `maxReconnects`。一次持续数分钟的 PG 抖动会让该轮停在"决策已落库、无人驱动"，直到刷新/重启。
报告 §6.5 已如实登记；建议写成显式 follow-up（如给 `STATE_UNKNOWN` 一个与 `SESSION_BUSY` 同形的有界重派发），并在本批交付说明里保留该限制。

### Minor

- **M-1｜报告 §1.2 的"改名"叙述与基线不符**：`rowAppearingAfterTheCountCheckIsATrueNoOp`、`unsuccesfulReloadIsStateUnknownNotProviderGone`、`unreadableKeyIsStateUnknownNotProviderGone` 在当前基线**任何测试文件里都不存在**（我全仓 grep 无命中）；真实改名只有 `confirmedProviderGoneLandsAsFailed…` → `missingAfterSuccessfulReloadConvergesTerminal`。其余是**新增**用例。报告口径需修正，避免合并读者误以为这些断言早已存在。
- **M-2｜`missingAfterSuccessfulReloadConvergesTerminal` 的 RED 是"契约形状"而非行为缺陷**：`ResumeProviderGoneTerminalTest:112` 的 RED 来自 `Wanted but not invoked: reloadWithOutcome()`；旧码的 `FAILED`+终帧本来就通过。缺陷本身由 mock/真实服务的三条 UNKNOWN 用例与删除护栏覆盖，建议报告把它标注为"前提断言"而非"缺陷复现"。
- **M-3｜seed 用例的 RED 没观测到"重复 bump"**：`AiConfigSeederConvergenceTest:142` 先断言 `seed()` 为 false，失败即短路，后面的 version/INCR 断言没跑。建议先断言版本/INCR（或用 soft assertions），让 RED 直接打出"+2 / INCR 两次"；同时说明"并发"是 `count()` 打桩模拟，真并发窗口只有 Spring 用例间接覆盖。
- **M-4｜主机边界用例缺口**：`AiConfigWriter.java:318-323` 的放行分支 `clearKey=true` + 换 origin、以及"scheme 变更""仅换端口"三种组合无用例；后两者正是 `originOf` 主张覆盖的关键（`AiConfigWriter.java:878-889`）。
- **M-5｜错误码借用**：`ResumeService.java:1272` 用 `AI_REDIS_UNAVAILABLE` 表示"配置库/密钥不可确认"；建议在既有 503 家族内新增专用码（保留 503 + `reason=STATE_UNKNOWN` 兼容面）。
- **M-6｜报告 §6.1/6.2 的不变式表述可再收紧**：写路径拒绝只保证"/test 与 /models/fetch 不会把旧 key 发往新 origin"（这两条探针 `Redirect.NEVER`，`:111`）。`/chat` 运行时使用的客户端重定向策略未验证；同 origin 下"改 path + 服务端 302"仍是理论残余（需攻陷同源服务，且属 brief 明示的范围外 hardening）。报告"旧 key 永远只会发往它被录入时的那台主机"宜加限定词。
- **M-7｜真实读库失败用例只注入 1 次失败，RED 落在 ACCEPTED 而不是 PROVIDER_GONE**：`AiResumeExactProviderBindingTest:180-205`（`pendingProviderLoadFailures.set(1)`）。旧码在该用例里经 `resolveForResume → lookup.get() → current()` 又读了一次库且成功，于是"读库抖动 ⇒ 永久 FAILED"这个原始缺陷**没有被这条用例复现**（它证明的是"新码拒绝了一次旧码会靠隐式重试偷偷放行的续跑"，方向安全）。建议把注入改成持续失败（连续抛到断言结束），让 RED 直接落在 `PROVIDER_GONE`/`FAILED`；否则这条用例在旧码上是"绿得没有信息量"的。
- **M-8｜其它小缺口**：`seedAlreadyLanded` 里"第二个默认行"的修复分支（`AiConfigSeeder.java:225-231`）没有专门用例；`AiConfigControllerResumeOutcomeMappingTest` 锁的是**既有**控制器映射（补丁未改控制器），价值是回归而不是缺陷复现；`AiController/AiConnectionProbe/AiEndpointGuard` 三个 brief 列出的文件未改，建议在报告的文件清单处显式写明"改位理由"。

## 4. 未验证 / 已知边界

1. **未跑全量测试**（按要求）。我按 `mock(AiConfigService)` / `setField(…"aiConfigService")` 扫过测试目录，未发现其它会进入新分支的用例；`AiConfigControllerTest`、`AiConfigWriteEndpointTest`、`ProviderRegistryHotReloadTest` 等不受影响（mock 或未触达）。另外我补跑了报告"未在 cap 内"的两个 carry-over 类（`ResumeProviderGoneDispatchTest`、`AiControllerResumeOutcomeMappingTest`）= 6/6 绿。仍存在"全量里某个 Spring 用例依赖旧 seed/写入语义"的残余风险。
2. 未跑真实 PostgreSQL / 三 JVM / 真实 Redis；未跑 Playwright E2E。E2E 侧我只做了源码核对：现有 Y 域用例的编辑只改显示名，没有"改 host 保留旧 key"的既有流程 ⇒ 预期不被本改动打断。
3. 未做真实 KEK 轮换、未做真实出网探测（按 brief 禁用真实密钥）。
4. `_applycheck-task1`（核对树）在 RED 复跑后源码已按补丁复原（md5 与 RED 前逐字节一致），随后的补跑已重新全量编译（305 main / 428 test）⇒ `target/classes` 现在与补丁一致。

## 5. 一句话

四条要求都真的做到了，而且 9/9 与 66/66、9 红 7 失败我都独立复现（含与报告逐条吻合的行号）；种子不再重复 bump、读库/解密失败留在 UNKNOWN 且不写终态、确定缺失才 FAILED、改 origin 必须重录 key——**无 Critical，可合并**。合并前/后建议处理：I-1（旧快照分支的状态区分，5 行）、I-2（前端提示无法自解释，需专用码）、I-3（UNKNOWN 无后端有界重试，登记后续）。
