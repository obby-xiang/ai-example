# Task 1 报告：配置播种、恢复判定与供应商密钥边界

- **状态：DONE**（未提交、未推送；主仓源码未改，唯一写入是本报告）
- 基线：主仓当前工作树 = `34fd8f4` + 第一版配置补丁 + park-audit 补丁（均为未提交候选），逐字节副本
  `<隔离副本>/_verify-20261025-cluster-config-resume`（快照提交 `8620ee6` = "current main"）
- 补丁（`git apply` 兼容，对**当前主仓工作树**）：`<仓外证据>/task1-config-seed-resume-key-boundary.patch`
  - 11 文件 / **+873 −41**（含上一轮 carry-over 640 行；本轮新增 4 文件 / +143 −9）
  - 已在新拷贝的当前主仓上 `git apply --check` + 实 apply，且 apply 后**逐文件 md5 与验证树一致**（§5）
- 命令口径：`MVN="<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"`，
  `-B -o test -Dspring.data.redis.port=1`，**无 `clean`**，未起 8081/5202，未连真实 Redis/PG，未使用真实密钥

---

## 0 五个 Step-1 用例 → 结果

| # | 用例（brief 指定名） | 位置 | 结论 | 目标缺陷（RED 现场） |
|---|---|---|---|---|
| 1 | `seedDoesNotPublishTwiceForNoOpOrConcurrentSeed` | `AiConfigSeederConvergenceTest` | **RED→GREEN** | `count()==0` 之后另一实例提交 ⇒ 旧码把同一份种子重灌并再 bump（版本 +2 / INCR 两次） |
| 2 | `reloadFailureCannotBecomeProviderGone` | `ResumeProviderGoneTerminalTest` | **RED→GREEN** | `load()` 吞异常、`reload()` 无成功信号 ⇒ 读库抖动被判 `PROVIDER_GONE` + 永久 `FAILED` |
| 3 | `keyFailureCannotBecomeProviderGone` | `ResumeProviderGoneTerminalTest` | **RED→GREEN** | 行在、模型在、密文解不开 ⇒ 同样被判死（KEK 修好也回不来） |
| 4 | `missingAfterSuccessfulReloadConvergesTerminal` | `ResumeProviderGoneTerminalTest` | **RED→GREEN** | 旧码没有"刷新成功"这个可观测事实（只调 `reload()`），无法证明判死建立在成功快照上 |
| 5 | `providerProbeCannotSendRetainedKeyToChangedHost` | `AiConfigWriterTest` | **RED→GREEN** | 改 host 保留旧 key ⇒ `/test`、`/models/fetch` 直接把库里的 key 发到新主机（ID-03） |

另有 3 条同源护栏（RED 时即绿，用来钉"不过度纠正"）：`genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload`、
`missingKeyDeclarationIsKnownAbsentAndStillConverges`、`sameHostEditsKeepTheRetainedKey` / `hostChangeWithAFreshKeyIsAllowed` /
`privateHostChangeIsStillBlockedByTheEndpointGuard`。

---

## 1 本轮改动（4 文件）

### 1.1 `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java`（+42）

**ID-03 的主机边界**：`updateProvider` 在算出"这次要不要动 key"之后、进事务之前判一次：

```java
if (key == null && existing.isKeyDeclared()
        && !originOf(existing.getBaseUrl()).equals(originOf(baseUrl))) {
    throw invalidParam("改主机名（origin）后必须重新录入 key 或先清空 key：…");
}
```

- **origin = scheme + host + port**（默认端口归一化）：https→http 或换端口同样把 key 交到另一服务上，只比 host 不够。
- **为什么放在写路径**：`/test` 与 `/models/fetch` 读的就是库里的 `(baseUrl, key)` 对。只要这条配对不可能被创建，
  旧 key 就永远只会发往它被录入时的主机 —— 不需要给探活加状态、不需要新表/新列，也没有"先读后发"的 TOCTOU 窗口。
- **私网规则保持独立**：`checkBaseUrl`（`AiEndpointGuard`）先跑，改私网地址仍报
  `BLOCKED_PRIVATE_ENDPOINT`；两条判据互不掩盖（各有用例）。
- `clearKey=true` 或随请求带新 key 时放行（没有"旧 key 配新主机"这回事）。

### 1.2 三处测试命名对齐 + 断言加固（brief 指定名）

- `AiConfigSeederConvergenceTest#seedDoesNotPublishTwiceForNoOpOrConcurrentSeed`（原 `rowAppearingAfterTheCountCheckIsATrueNoOp`）
- `ResumeProviderGoneTerminalTest#reloadFailureCannotBecomeProviderGone`（原 `unsuccesfulReloadIsStateUnknownNotProviderGone`）
- `ResumeProviderGoneTerminalTest#keyFailureCannotBecomeProviderGone`（原 `unreadableKeyIsStateUnknownNotProviderGone`）
- `ResumeProviderGoneTerminalTest#missingAfterSuccessfulReloadConvergesTerminal`（原 `confirmedProviderGoneLandsAsFailed…`）
  新增"判死的前提"两条断言：`verify(reloadWithOutcome())` + `verify(bindingState(providerId, modelId))` —— 这一条让它
  在当前主仓上**必然 RED**（主仓只有 `reload()`，没有"刷新成功与否"可观测）。

### 1.3 两条既有用例的调整（它们恰好制造了 ID-03 的配对）

| 用例 | 原来 | 现在 | 为什么 |
|---|---|---|---|
| `updateWithoutApiKeyKeepsTheCipher` | 改 host（`api.deepseek.com`→`gw.example.com`）且留空 key | 改**同 origin 的路径**（`/v1`）且留空 key | "留空=不改"的语义不变；"换 origin 保留旧 key"正是被明确禁止的组合（由用例 5 锁） |
| `userUpdateDetachesTheSeedRowSoSeedForceCannotOverwriteIt` | 改 host（`env-endpoint`→`user-edited.example.com`）保留 env key | 改**同 origin 的路径**（`/v1`） | 该用例锁的是"用户一改即脱离种子、seed-force 不覆盖"，与换主机无关 |

> 这两处不是"为了让测试过而改断言"：被删掉的行为（换主机+保留旧 key）**就是**本轮要消除的缺陷配对，
> 其反例（旧 key 只能去原主机）由用例 5 正面锁住；retention 本身仍由同 origin 的用例覆盖。

---

## 2 RED → GREEN（实测，均对**当前主仓生产代码**）

RED 的构造：把 `AiConfigSeeder.java` / `ResumeService.java` / `AiConfigWriter.java` 三个**行为**文件回退到
基线 `8620ee6`（= 当前主仓内容），保留 `AiConfigService` 的新增 API（纯增量，替身可 stub），于是用例编译得过、语义按主仓跑：

```
mvn -B -o test -Dspring.data.redis.port=1 -Dtest='AiConfigSeederConvergenceTest#seedDoesNotPublishTwiceForNoOpOrConcurrentSeed,
  ResumeProviderGoneTerminalTest#reloadFailureCannotBecomeProviderGone+keyFailureCannotBecomeProviderGone+missingAfterSuccessfulReloadConvergesTerminal,
  AiResumeExactProviderBindingTest#unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown+unreadableKeyYieldsStateUnknownNotProviderGone
    +missingKeyDeclarationIsKnownAbsentAndStillConverges+genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload,
  AiConfigWriterTest#providerProbeCannotSendRetainedKeyToChangedHost'
```

| 阶段 | 结果 |
|---|---|
| **RED（主仓代码）** | **Tests run: 9, Failures: 7** — 逐条：`seedDoesNotPublishTwiceForNoOpOrConcurrentSeed:142`（「另一实例已种好且与 env 逐值一致 ⇒ 真 no-op」）、`providerProbeCannotSendRetainedKeyToChangedHost:259`（改 host 未被拒）、`reloadFailureCannotBecomeProviderGone:141→170`（「503 语义（STATE_UNKNOWN），不是 409 PROVIDER_GONE」）、`keyFailureCannotBecomeProviderGone:163→170`（同上）、`missingAfterSuccessfulReloadConvergesTerminal:112`（`verify(reloadWithOutcome())` 落空）、`unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown:193`、`unreadableKeyYieldsStateUnknownNotProviderGone:225` |
| 红中仍绿（护栏，符合预期） | `missingKeyDeclarationIsKnownAbsentAndStillConverges`、`genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload` |
| **GREEN（本补丁）** | 同一组选择器 **Tests run: 9, Failures: 0**（`BUILD SUCCESS`） |

---

## 3 相邻回归（6 个类，符合 plan cap）

```
mvn -B -o test -Dspring.data.redis.port=1 -Dtest='AiConfigWriterTest,AiConfigSeederConvergenceTest,
  ResumeProviderGoneTerminalTest,AiResumeExactProviderBindingTest,AiConfigSeederTest,AiConfigWritePersistenceTest'
```

| 类 | tests |
|---|---|
| `AiConfigWriterTest`（含 4 条主机边界用例） | 31 / 0F |
| `AiConfigSeederConvergenceTest`（含 seed 恰好一次/缺失模型/env 变化） | 6 / 0F |
| `ResumeProviderGoneTerminalTest`（含 3 条 UNKNOWN 用例 + 确定判死 + 只读/幂等） | 6 / 0F |
| `AiResumeExactProviderBindingTest`（真实 `AiConfigService`：读库失败 / KEK 不符 / 真删除 / 无 key 声明） | 9 / 0F |
| `AiConfigSeederTest`（**Spring 真实装配**：种子发布版本、并发恰好 +1） | 6 / 0F |
| `AiConfigWritePersistenceTest`（**Spring 真实写路径**：落库/审计/版本） | 8 / 0F |
| **合计** | **66 tests / 0 failures / 0 errors / BUILD SUCCESS** |

未跑全量（按要求）；`AiConfigWriteEndpointTest`（writer 被 mock）、`AiConfigWritePersistenceTest`（已跑）是本改动
最相关的两个邻居；`AiControllerResumeOutcomeMappingTest`、`ResumeProviderGoneDispatchTest` 属上一轮 carry-over
（本轮未改其断言，随补丁一并交付，未在 cap 内重复跑）。

---

## 4 保留的语义（与评审口径一致）

1. **UNKNOWN ≠ 确定缺失**：`reloadWithOutcome()` 的 `ok` 是唯一"刷新成功"证据；失败/抛错/`null` ⇒ `STATE_UNKNOWN`
   （快照保持 `SUSPENDED`、不写终态、不出业务终帧、收掉悬挂订阅）；`KEY_UNREADABLE`（密文解不开）⇒ 同 UNKNOWN。
2. **确定缺失仍判死**：成功刷新 + `BindingState.ABSENT`（行/模型不在、停用、**从未声明 key**）⇒ `PROVIDER_GONE` +
   `FAILED` + `AI_PROVIDER_GONE` 终帧 + 收流（行为与上一轮一致，未改）。
3. **版本语义**：no-op 不 bump；真变化同事务 bump 一次；提交后才 `INCR` + reload + evict。并发交错（`count==0`
   之后别人提交）退化为真 no-op，但**不跳过**缺失/停用的模型行与 env 真变化。
4. **`reload()` 契约不变**：签名/返回/替身默认行为不变（新入口并列），`Mockito.mock(AiConfigService)` 的既有
   stub 不受影响。

---

## 5 补丁可应用性（对当前主仓）

```
cp -a <主仓> <隔离副本>/_applycheck-task1          # 逐字节副本（含两笔未提交候选）
cd <隔离副本>/_applycheck-task1
git apply --check <仓外证据>/task1-config-seed-resume-key-boundary.patch   # OK
git apply <patch>                                                               # APPLIED
# apply 后逐文件 md5 与验证树（_verify-…-cluster-config-resume）一致：0 处不同
mvn -B -o test -Dspring.data.redis.port=1 \
  -Dtest='AiConfigWriterTest,ResumeProviderGoneTerminalTest,AiConfigSeederConvergenceTest'
# 全量重编 305 main / 428 test；Tests run: 43, Failures: 0 → BUILD SUCCESS
```

补丁**不含** `ConfirmGate.java` / `SpToolCallingManager.java` / `SheetAudit.java` / `ToolCallEndTrace.java` /
`ParkSettlementAuditTest.java`（park-audit 归属批原样保留）；也不含前端与探针文件（上一轮已交付且本批未改）。

---

## 6 未验证边界 / 需要裁决的点

1. **直接改库仍能造出"旧 key + 新 host"**：本轮边界在写路径（产品唯一入口）。绕过应用改 `ai_provider.base_url`
   的行仍会让 `/test`、`/models/fetch` 把旧 key 发出去 —— 要覆盖它需要给 key 持久化一个"绑定主机"锚（新列 + 迁移），
   本任务未做（文件清单未含迁移，且需与 Task 3/8 的迁移窗口对齐）。**建议登记为后续 hardening**。
2. **`/chat` 运行时路径未加同一条闸**：`AiProviderRegistry` 用同一份 `(baseUrl, key)` 建运行态。写路径拒绝后，
   通过 API 无法造出该配对，故本任务未改运行时（文件清单外、无独立用例证明）。
3. **换 origin 需要重录 key 属行为变更**：两条既有用例因此改为同 origin 编辑；若产品希望"换 host 时自动清空 key
   而不是报错"，只需把 `invalidParam(...)` 换成 `key = KeyMaterial.cleared()`（语义更宽松，同样不外泄）。
4. **真实 PG / 三 JVM 仍是 CI 边界**：本轮只有单测与 Spring 上下文（H2 内存）证据；未跑真实拓扑、未跑全量。
5. 上一轮已登记、本轮未碰：`STATE_UNKNOWN` 无派发方自动有界重试（重试路径是下一次重挂/启动扫描/客户端）；
   `save` 成功而 `out.error` 抛错时"终态无终帧"的边角（M9）。

---

## 7 一句话

五条 Step-1 用例全部先红后绿：种子在并发交错下不再重复 bump；读库失败与"密文解不开"都留在 `STATE_UNKNOWN`
（快照 `SUSPENDED`、无终帧、连接收干净），只有"刷新成功 + 行/模型确实不在"才落 `FAILED` + `AI_PROVIDER_GONE`；
供应商 key 与主机在产生处绑定 —— 换 origin 必须重录 key，`/test`、`/models/fetch` 因此不可能把旧 key 发往新主机，
而私网规则仍是独立判据。
