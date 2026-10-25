# Task 2 · fix2 报告（R-C-1 / R-I-1 / R-I-2 / R-I-4 收口）

**结论：DONE** —— 三处生产改动 + 三处测试/夹具，全部按"先 RED 后 GREEN"落；仲裁在**真实 `StringRedisTemplate`** 下能跑起来（R-C-1），写前/写后失败语义分开（R-I-1），fail-open 有计数（R-I-4），销毁键名有行为级联契约（R-I-2）。未扩 `expireFrontend`、未动 `ensureTtl`、未改架构。

## 0. 交付物与基线

| 项 | 值 |
|---|---|
| 增量补丁 | `.superpowers/sdd/2026-10-25-ai-remediation/task-2-fix2.patch`（6 文件 **+189 / −12**，340 行） |
| 基线 | **实际 main 工作区 + `park-audit-fix2b.patch`**（即上一轮复审树）。补丁对"main 工作区 + fix2b" `git apply --check` **EXIT=0**，`git apply -R --check` 通过，应用后与我改动的树 `diff -r` **无差异** |
| 合并顺序 | main 工作区（含未提交 config/audit + park audit 候选）→ **fix2b** → **fix2**；Task 3 fix1 仍需同批（C-1 的销毁调用点不在本补丁内，见 §7） |
| 隔离方式 | 全部改动在 `<隔离副本>/_fix2-task2`（基线 = 主仓工作区拷贝，`git` 两个提交：`baseline` / `fix2b`，改动用 `git diff` 导出增量）。**主仓未改、未提交、未 push、未 reset/stash**；主仓工作区里只新增了本报告与本补丁两个文件 |
| 测试口径 | 只跑 `ParkSettlementOwnershipTest` + `SettlementObservationPurgeContractTest`（19 例），并额外跑 `ParkSettlementAuditTest`（判是否被 `emit` 拆分影响）→ 28 例全绿；**未跑全量、未跑迁移/文档门禁（本批未动 scripts/docs）、未起服务** |
| Maven | 只用 `<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（经同目录 `mvnx.cmd` 一行 `call` 转发，命令行与 `-s maven-settings.xml -o -B test` 同前），**未 clean** |

## 1. 改动清单

```
 backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java | 13 +-   R-C-1 ARGV 字符串化 + R-I-4 计数
 backend/src/main/java/com/example/configmgr/ai/observe/RedisDegradation.java            | 12 +-   R-I-4 新增 O1_OBSERVATION_CLAIM（含 KNOWN 登记）
 backend/src/main/java/com/example/configmgr/ai/gate/ToolCallEndTrace.java               | 30 +-   R-I-1 写前/写后失败语义拆分
 backend/src/test/java/com/example/configmgr/ai/gate/FakeObservationRedis.java           | 12 +-   TTL ARGV 兼容字符串（旧替身写死了 Number）
 backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementOwnershipTest.java    | 115 +   R-C-1 / R-I-1 / R-I-4 三条用例 + 两个夹具
 backend/src/test/java/com/example/configmgr/ai/gate/SettlementObservationPurgeContractTest.java | 19 +  R-I-2 联合契约钉子
```

## 2. R-C-1（Critical）：真实 `StringRedisTemplate` 下仲裁退化为 fail-open → 已修

### RED（先证，未改生产）

新增用例 `claimArbitratesThroughARealStringRedisTemplate`（`ParkSettlementOwnershipTest.java:447`）：**真实** `StringRedisTemplate` + 桩连接（`evalSha` 按序返回 1/0/1），断言"同一 field 的第二个执行体必须让位"。

```
[ERROR] Failures:
[ERROR]   ParkSettlementOwnershipTest.claimArbitratesThroughARealStringRedisTemplate:466
          [同一 field 的第二个执行体必须让位（真仲裁，不是恒 true）]
Expecting value to be false but was true
```

原因链（上一轮已定位，本批用真实模板复证）：`ttlSeconds()`（`long`）→ 自动装箱 `Long` 作为 Lua ARGV → `DefaultScriptExecutor` 用模板的 **valueSerializer**（`StringRedisSerializer`）序列化 ARGV → 桥方法 `checkcast String` ⇒ `ClassCastException` → 被 `claim` 的 `catch` 吞成 fail-open ⇒ **每次抢位都 true**，跨实例仲裁整体失效（I-1 双记原形）。

### 生产改动

- `SettlementObservationStore.java:127`：`ttlSeconds()` → **`String.valueOf(ttlSeconds())`**（与项目其它 4 处 Lua 调用同形：`AiRateLimiter`、`LeaseService`×3、`RunStore`）。
- 类注补一条 **ARGV 类型** 的硬口径（`:48-51`），避免下一个人再把数值裸传。

### GREEN

```
[INFO] ParkSettlementOwnershipTest        Tests run: 14, Failures: 0, Errors: 0
[INFO] SettlementObservationPurgeContractTest Tests run: 5, Failures: 0, Errors: 0
[INFO] Tests run: 19, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```
（最终补丁 = 最后一次 GREEN 的树；仅一句夹具注释在两次 GREEN 之间改过，改后重跑了这 19 例。）

用例还断言"不同 `toolCallId` 互不影响"（第三个 field 返回 1 ⇒ true），即仲裁仍是**按 field** 的，不是"全 run 只发一条"。

## 3. R-I-1（Important）：写前失败释放 / 写后失败不释放 → 已闭合并被用例钉住

### RED

新增用例 `auditFailureAfterTheLineIsWrittenKeepsTheMarker`（`:421`）：用一个"先 `super.record()` 再抛"的 `SheetAudit`（`ThrowingAudit`，`:616`）制造**行已写出、计数已记之后**的失败，然后模拟"下一次结算"。

```
[ERROR] Errors:
[ERROR]   ParkSettlementOwnershipTest.auditFailureAfterTheLineIsWrittenKeepsTheMarker:431
          » IllegalState 模拟观测面在行写出之后的审计阶段抛错
```

红在**第二次 `emitOnce` 又进了发点并再次抛错** ⇒ 上一版 `release` 把"写后失败"也当成"写前失败"，占位被放回 ⇒ 生产上第二次结算会**再发一行 + 再记一次计数**（2 条 end / `readCalls=2`），把"恰好一次"变成"至多两条"。

### 生产改动（`ToolCallEndTrace.java:54-100`）

把"抢位 → 发"的顺序与失败边界拆开，**行为等价于原逻辑的无故障路径**：

1. **载荷先构造**（`pairs(...)` 在 `claim` 之前，`:64`）——构造失败时"一行没写、位也没抢"，占位不被消费，**也不需要释放**；
2. 抢不到位 ⇒ 让位返回（不变）；
3. `AiTrace.eventOn(...)` 抛 ⇒ **写行失败** ⇒ `release` 再抛（`:77`，"宁重不宁缺"，下一位可补发）；
4. `recordAudit(...)` 抛 ⇒ **行已写出** ⇒ **不释放**（`:80-82`）——行已在，重复比重记更糟。

`emit` 里的审计段抽成 `recordAudit`（`:97`），`emit` 仍是 private（唯一发口不变），载荷字段/键序**逐字未动**。

### GREEN

```
[INFO] ParkSettlementOwnershipTest   Tests run: 14, Failures: 0, Errors: 0
[INFO] SettlementObservationPurgeContractTest Tests run: 5, Failures: 0, Errors: 0
```
（含上一批已存在的 `aFailedEmissionReleasesTheMarkerSoALaterSettlementCanRetry`：写前失败 ⇒ 释放 ⇒ 重试恰 1 条，未被本次拆分破坏。）

## 4. R-I-4（Important）：fail-open 接入既有 `RedisDegradation` 计数面

- `RedisDegradation.java:99` 新增 `O1_OBSERVATION_CLAIM = "o1.observationClaim"`，并登记进 `KNOWN`（`:105`）⇒ `/api/ai/health` 的 `redis.degradations` 里**固定出现**该键（"从未降级"与"没接线"可区分，与 A1/A5/A6 同姿态）。
- `SettlementObservationStore.java:135`：`claim` 的 `catch` 里 `RedisDegradation.record(...)`（计数不改变返回值，仍是 fail-open）。

### RED → GREEN

```
[ERROR]   ParkSettlementOwnershipTest.failOpenIsRecordedInRedisDegradation:486
          [降级必须可计数（/health.redis.degradations 可见）]
expected: 1L
 but was: 0L
```
RED 用字面量 `"o1.observationClaim"` 断言（那时常量还不存在，引常量会编译失败）；GREEN 后改用 `RedisDegradation.O1_OBSERVATION_CLAIM` 常量（编译期绑定，改名即红）。

## 5. R-I-2（Important）：`ai:observed:` 键名联合契约（行为级，非同义反复）

**无生产改动**（键名本就一致，缺的是"谁来钉住它"）。新增 `SettlementObservationPurgeContractTest#destroySideLiteralDeleteTargetsTheKeyClaimWrote`（`:89`）：

1. 用 `claim` 写入（走 store 的常量）；
2. 用 **Task 3 侧的字面量** `"ai:observed:" + runId` 直接 `delete`（复刻 `AiSessionPurgeService` 的硬编码前缀，它刻意不引用本类）；
3. 断言：删到了（`true`）、本 run 键消失、他会话键仍在、删后同一 field **重新可抢**。

任一侧改名 ⇒ 第 3 步的"删到了/重新可抢"必红，而不是生产上静默少删。该用例**出生即绿**（本批无生产改动可 RED），报告按要求如实标注，不伪装成修复。

## 6. 明确不动的

- **R-I-3（`ensureTtl` 冗余）**：按控制者重评，**不动**（不改、不删、不重命名用例）。
- `expireFrontend`（park 后前端超时 0 条）：**不扩**，仍为已登记残余。
- exactly-once 机制、`RunStore`、`SessionKeyRegistry`、`AiSessionPurgeService`、迁移脚本、文档：本批**零改动**（`git diff --stat` 可验）。

## 7. 未验证边界（如实登记）

1. **没有真 Redis**：R-C-1 的证明是"真实 `StringRedisTemplate` + 桩连接"（ARGV 序列化、脚本调用、结果反序列化都走 Spring 真实实现，只有网络与 Redis 内核是替身）；脚本内部 `HSETNX`+`EXPIRE` 的原子性仍未在真 Redis/Cluster 上跑过（沿用上一批的契约用例 + 文本断言）。
2. **TTL 过期语义**未实测（没有可控时钟/真过期）；`session-ttl=24h` 的滑动续期只按代码口径。
3. **写后失败窗口**：真实 `SheetAudit#record` 不会抛（`appendLedger` 自吞异常），本批用 `ThrowingAudit` **注入**了该故障来钉语义；生产上这一窗仍属"日志框架自身抛错"级别，用例覆盖的是**语义**而非"生产必现"。
4. **本补丁不含 C-1 的销毁调用点**：`AiSessionPurgeService` 里的 `ai:observed:` 单键 DEL 在 **Task 3 fix1**（已核实存在）。**只合 fix2b + fix2、不合 Task 3 fix1 ⇒ 绑定要求仍失守**。
5. 未跑全量/E2E；未跑 `node --test scripts/redis-run-migration.test.mjs` 与 `check-docs`（本批未触碰 scripts/docs，上一轮为 52/52 与 6/6）。
6. 夹具 `FakeObservationRedis` 现在**同时接受** `Number` 与 `CharSequence` 的 TTL ARGV —— 这是刻意的宽容（避免替身继续把类型错误掩盖成"通过"），真实类型由 R-C-1 的真实模板用例钉住。

## 8. 复跑（真实命令与输出）

```bash
# 树：<隔离副本>/_fix2-task2（基线 = 主仓工作区 + fix2b，git 已提交）
cd <隔离副本>/_fix2-task2/backend
set JAVA_HOME=<本机工具链>\Java\jdk-21.0.12
..\mvnx.cmd -s maven-settings.xml -o -B test \
  -Dtest=ParkSettlementOwnershipTest,SettlementObservationPurgeContractTest -DfailIfNoTests=false
#   → Tests run: 19, Failures: 0, Errors: 0；BUILD SUCCESS

..\mvnx.cmd -s maven-settings.xml -o -B test \
  -Dtest=ParkSettlementOwnershipTest,SettlementObservationPurgeContractTest,ParkSettlementAuditTest \
  -DfailIfNoTests=false
#   → Tests run: 28, Failures: 0, Errors: 0；BUILD SUCCESS

# 补丁可应用性（在"主仓工作区 + fix2b"的干净拷贝上）
git apply --check task-2-fix2.patch      # EXIT=0
git apply -R --check task-2-fix2.patch   # 通过；应用后与原树 diff -r 无差异
```

证据文件（隔离树内，非交付物）：`<隔离副本>/_fix2-task2/{red.log, green.log, green3.log, task-2-fix2.patch}`。
