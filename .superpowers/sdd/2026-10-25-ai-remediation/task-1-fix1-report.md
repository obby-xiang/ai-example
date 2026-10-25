# Task 1-fix1 报告：旧快照 key 三分（I-1）+ 主机边界专用码（I-2）+ 证据加固（M-3 / M-7）

- **状态：DONE**（未提交、未推送；主仓源码未改，唯一写入是本报告）
- 输入：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-1-review.md`
- 只处理：**I-1、I-2**，以及复核点名的两条证据加固（**M-3、M-7**）。**未碰** I-3（UNKNOWN 有界重试调度）、
  其余 Minor（M-1 只在 §6 做报告纠偏）、任何终态/租约/探针既有行为。
- 基线：主仓当前工作树（`34fd8f4` + 第一版配置候选 + park-audit 候选）；施工副本
  `<隔离副本>/_verify-20261025-cluster-config-resume`（`8620ee6` = 该工作树快照）
- 补丁（`git apply` 兼容，对**当前主仓**）：`<仓外证据>/task1-fix1-config-seed-resume-key-boundary.patch`
  - **19 文件 / +1025 −41**（= 上一轮 Task 1 的 11 文件 + 本轮 13 文件里的新增/改动；两轮重叠 5 文件）
  - 本轮增量：**13 文件 / +180 −28**（`git diff d59ea2e..HEAD`）

---

## 0 结论

| 复核项 | 处置 | 证据 |
|---|---|---|
| **I-1**：`bindingState(providerId=null)` 把"从未声明 key"也当不可知 ⇒ 旧/冻结快照永不收敛 | 该分支改成**按声明该模型的 provider 逐个看 key**：全 `MISSING`/未声明 ⇒ `ABSENT`（确定不可用）；有 `UNREADABLE` 且无 `OK` ⇒ `KEY_UNREADABLE`（不可知）；出现 `OK`（与"解析为空"自相矛盾）⇒ 保守按不可知 | 两个方向各一条确定性用例；I-1 方向 **RED**（`expected: ABSENT but was: KEY_UNREADABLE`），反方向在 RED 运行中**保持绿** |
| **I-2**：改 origin 保留旧 key 被拒时只有通用 `INVALID_PARAM` ⇒ 用户学不到"重录 key" | 新增专用码 **`KEY_BOUND_TO_HOST`**（400）：后端 `ErrorCodes` + zh/en 后端文案 + 前端 `ErrorCode` 镜像 + `ai.settings.errors.KEY_BOUND_TO_HOST` 词表键 + zh/en 词条；`INVALID_PARAM` 仍只表示通用范围/校验错误 | 后端 **RED**（`expected: "KEY_BOUND_TO_HOST"` 实得 `INVALID_PARAM`）；前端 **RED**（去掉映射后文案退化成兜底"操作失败，请重试。"）；两处均 GREEN |
| **M-3**：seed 用例的 RED 只看 `isFalse()` 短路，没观测到"重复 bump" | 断言重排：先观测 `config.version` / `INCR` 次数 / 行数，**最后**才断言语义返回值，并加"恰好一次"的绝对值断言 | RED 现在直接打在「同一份数据不得再 bump 一次（旧码实测 2→3）」 |
| **M-7**：真实读库失败只注入 1 次 ⇒ 旧码隐式重试成功，RED 落在 ACCEPTED，"永久 FAILED"没被复现 | 改成**持续**失败（`Integer.MAX_VALUE`）+ 同一抖动窗口内**第二次续跑**断言 | RED 现在实测 `expected: STATE_UNKNOWN but was: PROVIDER_GONE`（旧码确实会判死），且第二次尝试仍不得翻成终态 |

---

## 1 改动（本轮 13 文件）

### 1.1 `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java`（I-1）

```java
if (providerId == null) {
    return bindingStateByName(snap, modelId);   // 旧实现：modelDeclared ? KEY_UNREADABLE : ABSENT
}
```

`bindingStateByName` 与 `providerId != null` 分支**同一口径**：

| 该模型名被谁声明 | key 状态 | 结果 |
|---|---|---|
| 没有任何 enabled provider 声明它 | — | `ABSENT`（确定不在） |
| 有声明 | 全部 `MISSING`/未声明（`key_ref=NONE`、env key 为空） | `ABSENT`（确定的配置状态 ⇒ 可判死） |
| 有声明 | 至少一个声明了密文却解不开，且没有可用的 | `KEY_UNREADABLE`（不可知 ⇒ 不判死） |
| 有声明 | 出现 `OK`（本该解析成功） | `KEY_UNREADABLE`（自相矛盾，保守） |

`modelDeclared` 因此退化为内部实现（`bindingStateByName`），类注里的"从未声明 key = 确定"与两分支口径重新一致。

### 1.2 主机边界专用码（I-2）

| 文件 | 改动 |
|---|---|
| `backend/.../common/ErrorCodes.java` | 新增 `KEY_BOUND_TO_HOST`（含语义边界注释：只拦"换 origin 且不重录 key"；同 origin 编辑与"换端口/换协议后再录 key"不受影响；任意 OpenAI 兼容端点仍可配置） |
| `backend/.../config/AiConfigWriter.java` | 新增 `CODE_KEY_BOUND_TO_HOST` 常量；边界拒绝从 `invalidParam(...)` 改为 `new AiConfigException(CODE_KEY_BOUND_TO_HOST, …)`（仍是 400：`AiConfigException#getStatus` 的 default 分支） |
| `backend/src/main/resources/messages_zh/en.properties` | `error.KEY_BOUND_TO_HOST=该供应商的密钥绑定在原主机上：更改主机名（协议/域名/端口）后必须重新录入密钥，或先清空密钥再保存。` / 英文同义 |
| `frontend/src/types/api.ts` | `ErrorCode` 镜像 +1（`ErrorCodesContractTest` 的三方对账要求） |
| `frontend/src/constants/ai-settings-copy.ts` | `ERROR_KEYS.KEY_BOUND_TO_HOST → 'ai.settings.errors.KEY_BOUND_TO_HOST'`（**这是用户能看见文案的关键一列**） |
| `frontend/src/locales/zh-CN.json` / `en-US.json` | 各 +1 词条（"重录 key / 或先清空"） |

`INVALID_PARAM` 保留原义（范围/取值/默认目标校验），未被挪用。

### 1.3 测试（本轮 4 个文件）

| 文件 | 用例 |
|---|---|
| `AiResumeExactProviderBindingTest` | +`legacyFrozenSnapshotWithUndeclaredKeyIsKnownAbsentNotUnknown`（旧/冻结快照 + `key_ref=NONE` ⇒ `ABSENT` ⇒ `PROVIDER_GONE` + `FAILED`）；+`legacyFrozenSnapshotWithUnreadableKeyStaysUnknown`（同一分支另一半：KEK 不符 ⇒ `KEY_UNREADABLE` ⇒ `STATE_UNKNOWN` + `SUSPENDED`）；`unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown` 改为**持续**读库失败 + 二次续跑断言（M-7） |
| `AiConfigSeederConvergenceTest` | `seedDoesNotPublishTwiceForNoOpOrConcurrentSeed` 断言重排 + 绝对值（M-3） |
| `AiConfigWriterTest` | `providerProbeCannotSendRetainedKeyToChangedHost` 改为断言 `CODE_KEY_BOUND_TO_HOST`（I-2） |
| `frontend/tests/i18n-script-copy.spec.ts` | +`KEY_BOUND_TO_HOST 有专属 zh/en 文案（不是通用参数错误）`（zh 含"重新录入密钥"、不等于 `INVALID_PARAM` 文案；en 含 "re-enter the key"、不是兜底文案） |

---

## 2 RED → GREEN（实测）

命令口径：`MVN="<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"`，
`-B -o test -Dspring.data.redis.port=1`，**无 `clean`**；前端 `npx vitest run tests/i18n-script-copy.spec.ts`。
RED 的构造都是"只把**目标行为**回退，保留编译所需的增量 API"，即被测缺陷的真实旧码。

| # | RED（旧码） | GREEN（本补丁） |
|---|---|---|
| I-1 | `AiConfigService` 回退到 carry-over（`d5916df`）：`-Dtest='…#legacyFrozenSnapshotWithUndeclaredKeyIsKnownAbsentNotUnknown+legacyFrozenSnapshotWithUnreadableKeyStaysUnknown'` → **Tests run: 2, Failures: 1**：`expected: ABSENT but was: KEY_UNREADABLE`（`…UndeclaredKey…:256`）；同批 `…UnreadableKeyStaysUnknown` **绿**（证明是两个方向的区分，不是一刀切） | 同一对 → **2/2 绿** |
| I-2 后端 | 注册完成但 writer 仍抛 `INVALID_PARAM`：`-Dtest='AiConfigWriterTest#providerProbeCannotSendRetainedKeyToChangedHost'` → **1 run / 1 F**：`expected: "KEY_BOUND_TO_HOST"` | 1/1 绿（`AiConfigWriterTest` 全量 31/31） |
| I-2 前端 | 去掉 `ERROR_KEYS.KEY_BOUND_TO_HOST` 映射后跑 spec → **1 failed**：`expected '操作失败，请重试。' to contain '重新录入密钥'`（正是 I-2 描述的"用户看不到重录提示"） | 恢复映射 → **15/15 绿**；`npm run typecheck` **0 错误** |
| M-3 | `AiConfigSeeder` 回退到主仓版本：`-Dtest='AiConfigSeederConvergenceTest#seedDoesNotPublishTwiceForNoOpOrConcurrentSeed'` → **1 run / 1 F**：`[同一份数据不得再 bump 一次（旧码实测 2→3）] expected: 2`（**重复 bump 被直接观测到**，不再只看到 `isFalse` 短路） | 6/6 绿 |
| M-7 | `ResumeService` 回退到主仓版本 + 持续读库失败：`…unreadableDatabaseDuringTheForcedReloadYieldsStateUnknown` → **1 run / 1 F**：`expected: STATE_UNKNOWN but was: PROVIDER_GONE`（旧码确实把读库失败判成终态） | 11/11 绿（含二次续跑仍 `SUSPENDED`） |

> 合并 RED 运行（`Seeder`+`ResumeService` 回主仓、`AiConfigService` 回 carry-over、`AiConfigWriter` 留新码）：
> `-Dtest` 5 条选择器 → **Tests run: 5, Failures: 4**，其中 I-1 反方向与主机边界用例保持绿（符合"只红在缺陷点"）。

---

## 3 聚焦回归（6 类，符合 plan cap）

```
mvn -B -o test -Dspring.data.redis.port=1 -Dtest='AiConfigWriterTest,AiResumeExactProviderBindingTest,
  AiConfigSeederConvergenceTest,ResumeProviderGoneTerminalTest,ErrorCodesContractTest,AiConfigWritePersistenceTest'
```

| 类 | tests |
|---|---|
| `AiConfigWriterTest`（边界码 + 同源/换源/私网） | 31 / 0F |
| `AiResumeExactProviderBindingTest`（I-1 两向 + 持续读库失败 + 真删除/无 key 护栏） | 11 / 0F |
| `AiConfigSeederConvergenceTest`（no-op/并发/env 变化/模型缺失） | 6 / 0F |
| `ResumeProviderGoneTerminalTest`（UNKNOWN 三例 + 确定判死 + 只读/幂等） | 6 / 0F |
| `ErrorCodesContractTest`（新码的三方对账：后端码 / messages zh+en / 前端镜像） | 6 / 0F |
| `AiConfigWritePersistenceTest`（**Spring 真实写路径**：新码不影响落库/审计/版本） | 8 / 0F |
| **合计** | **68 tests / 0 failures / 0 errors / BUILD SUCCESS** |

前端：`i18n-script-copy.spec.ts` **15/15**、`vue-tsc --noEmit` **0 错误**。未跑全量、未跑 E2E。

---

## 4 补丁可应用性（对**当前主仓**，含并发改动）

```
cp -a <主仓> <隔离副本>/_applycheck-task1fix1
cd <隔离副本>/_applycheck-task1fix1
git apply --check <仓外证据>/task1-fix1-config-seed-resume-key-boundary.patch   # OK
git apply <patch>                                                                     # APPlIED
```

- **并发漂移**：本轮期间主仓被另一任务改过两处 locale（`frontend/src/locales/{zh-CN,en-US}.json` 在 20:31 新增
  `FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID`）。补丁在这两个文件上**干净应用且保留对方新增键**
  （应用后该文件仍只多我这一行）；其余 17 个文件与应用检查时与基线逐字节一致。
- 在**打上补丁的当前主仓副本**上重跑同一组 6 类：全量重编 **305 main / 433 test**（比施工副本多 5 个测试源，
  即其它任务新增的类）→ **Tests run: 68, Failures: 0 → BUILD SUCCESS**；
  前端 spec 在该副本同样 **15/15 绿**。

---

## 5 与上轮报告的口径纠偏（复核 M-1）

上轮报告 §1.2 写成"三处测试改名"不准确：**只有** `confirmedProviderGoneLandsAsFailedTerminalStateWithAMachineReadableFrame`
→ `missingAfterSuccessfulReloadConvergesTerminal` 是改名；`seedDoesNotPublishTwiceForNoOpOrConcurrentSeed`、
`reloadFailureCannotBecomeProviderGone`、`keyFailureCannotBecomeProviderGone` 在当时是**新增**用例（此前不存在同名断言）。
本轮未改这些用例的语义，只按 I-1/I-2 与 M-3/M-7 增补断言。

---

## 6 未做 / 边界（明确）

1. **I-3 未做**（按要求）：`STATE_UNKNOWN` 仍无派发方有界重试，重试路径 = 下一次重挂自愈 / 启动扫描 / 客户端重连。
   这是一条显式 follow-up（给 `STATE_UNKNOWN` 一个与 `SESSION_BUSY` 同形的有界重派发），本批保留该限制。
2. 其余 Minor（M-2/M-4/M-5/M-6/M-8）未追：`clearKey+换 origin`/`https→http`/仅换端口 三种组合仍缺用例；
   `STATE_UNKNOWN` 仍借用 `AI_REDIS_UNAVAILABLE` 码；报告措辞类问题除 M-1 外未逐条改。
3. 真实 PG / 三 JVM / 真实 KEK 轮换 / 真实出网探测仍是 CI 边界；本机只用 H2 内存与替身。
4. `/chat` 运行时路径未加同一道闸（与上轮一致：写路径已使"旧 key + 新 origin"的配对无法经 API 产生；
   直接改库仍是残余边界，需持久化 key↔host 锚，本任务文件清单外）。
5. 前端只做了文案键表与 spec 级验证，未起浏览器看设置页实际渲染（Task 5/8 的浏览器批次覆盖）。

---

## 7 一句话

I-1 让"旧/冻结快照"与"带 providerId 的快照"用同一套三分口径（未声明 key = 确定不可用；声明了却解不开 = 不可知），
I-2 给"改主机名保留旧 key"一个能自解释的专用码与双语文案（不再藏在通用参数错误里），
并把两条被复核打折的证据补成真红：种子的重复 bump 现在被直接观测到（2→3 / INCR 1→2），
持续读库失败现在能证明旧码会落 `PROVIDER_GONE`/`FAILED`。
