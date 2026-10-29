# 租户迁移 / 断言 / 文档整合修复报告

- 日期：2026-10-30
- 范围：主仓 `<REPO>`
- 未派子 agent；未推送；未修改 `RunStore` / `ResumeService` / `ConfirmGate` 等运行链文件。

## 1. 改动文件

| 文件 | 改动 |
| --- | --- |
| `scripts/redis-run-migration.mjs` | 正向带身份迁移重写 `ai:runs` 成员；rollback 带身份/legacy tagged 双形回退；`ai:runs` 成员计划/应用/输出；脚本头注释同步 |
| `scripts/redis-run-migration.test.mjs` | 内存 fake 增加 SET 支持；新增 4 个索引/回退用例 |
| `backend/src/test/java/com/example/configmgr/ai/session/SessionPurgePendingStoreTest.java` | 队列成员 mock/断言从裸 `sessionId` 改为 pending 键（3 处） |
| `backend/src/test/java/com/example/configmgr/ai/session/RedisClusterScanSupportTest.java` | 队列成员断言改为 pending 键（2 处） |
| `backend/src/main/java/com/example/configmgr/ai/run/SessionKeyRegistry.java` | `ai:beat:` / `ai:activity:` 登记改用 `TenantKeyScope.scoped(prefix, runId)`，与 `RunStore#beatKey/#activityKey` 同键；注释同步 |
| `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java` | 类注修正“所有键族都带身份”的过度声明；`clientCleanup` 身份段复用 `TenantKeyScope.segmentOf`；跳过非本身份 run 写入 `warnings`（枚举 + 删除复核两处） |
| `.superpowers/sdd/2026-10-30-ai-tenant-user/task-migration-fix-report.md` | 本报告 |

## 2. 逐项落实

### 2.1 `ai:runs` 索引（评审 I4-1）

- 正向带身份迁移在数据键全部落位后，把本次 run 的成员从裸 `runId` 重写为
  `tenantId/userId/runId`：`SREM ai:runs <runId>` + `SADD ai:runs <t>/<u>/<runId>`，
  同一 `MULTI` 原子提交。
- 只动本次 run 的成员；其他身份/其他 run 的成员原样保留；不扫描租户列表。
- 索引缺失时补一条；两种形态并存时做去重（删旧形）；已是目标形态时 `identical`（0 写）。
- 数据键已迁完、只剩索引陈旧的现场（源键已删）仍可 `--run-id` + 身份重跑：
  计划为 `ready/index-only`，只修索引、不重写数据键。
- rollback 声明身份时反向恢复为裸 `runId`。
- 成员口径与 `RunKeyScope.indexMember/parseIndexMember` 一致，因此
  `allRunRecords()` 可按成员身份逐条发现（不需要扫描租户列表）。

### 2.2 rollback 双形回退（评审 I4-2）

- 发现面：rollback 声明身份时，带身份成员按声明身份过滤，legacy tagged 成员
  （无身份）仍放行作为回退目标。
- 计划面：每族源键先试带身份 tag，`absent` 再试 legacy tag（`rollbackSourceKeyFor`）；
  注释与实现一致。
- 两个形态都不存在时返回带身份首选键，计划显示 `source-missing`，不再出现
  “注释宣称能回退、实现只拼一个键”的偏差。

### 2.3 队列成员由 sessionId → pending 键的存量断言

任务书写“4 处”，全量清扫实际命中 **5 处**，全部修正：

- `SessionPurgePendingStoreTest`：写失败 mock 的 `remove(..., "s-1")`、
  defer 前/后的 `containsExactly` 两处；
- `RedisClusterScanSupportTest`：Cluster 多 master、standalone 两处
  `contains("s-1", "s-2")`。

`queueWriteFailureKeepsPendingAndGuardArmed` 保持原覆盖：队列写失败仍抛
`AiStateUnavailableException`，pending 保留、守卫 armed。

### 2.4 SessionKeyRegistry beat/activity

- 登记装配从 `BEAT_PREFIX + runId` / `ACTIVITY_PREFIX + runId` 改为
  `TenantKeyScope.scoped(prefix, runId)`，与 `RunStore#beatKey/#activityKey` 逐字同键；
  无身份上下文时仍是 legacy 旧键形。
- 用临时校验程序在身份 `1/101` 下对比：
  `beat OK: ai:beat:1:101:r1`、`activity OK: ai:activity:1:101:r1`（与 store 输出一致）。

### 2.5 AiSessionPurgeService 注释 / clientCleanup / warnings

- 类注不再宣称“所有键族都带身份”：改为“run/session 数据键带身份段；唯一全局面
  `ai:runs` 用复合成员归属，不扫描租户列表”。
- `clientCleanup` 只从 `TenantKeyScope.segmentOf(identity)` 取身份段：
  - 镜像键 = `ai-mirror:` + segment + sessionId；
  - 草稿前缀 = `cm.ai.form.draft.` + segment（尾部冒号换成 `.`）；
  - legacy 段为空时仍是旧键形。消除了第二套 `tenantId + ":" + userId` 拼法。
- 跳过非本身份 run 现在写入结果 `warnings` 可取证：
  - 枚举阶段（`enumerate`）跳过非本身份快照；
  - 删除复核阶段（`deleteOrDefer`）再次跳过时；
  - `Enumeration` 携带 warnings，`executeDeletion` 合并进最终 `PurgeResult`，
    `aborted` 也回传枚举 warnings。原有 `log.warn` 保留。

## 3. 红 / 绿证据

### 3.1 Node（迁移脚本）

每个新用例先单独执行取得红，再实现后单独执行转绿；最后跑整文件回归。

| 用例 | 红（实现前） | 绿（实现后） |
| --- | --- | --- |
| 正向带身份迁移把 `ai:runs` 成员重写为 `t/u/runId` | `actual ['9/909/run-other','run-1']`，缺少 `2/202/run-1` | pass |
| 已迁移但索引陈旧：只修 `ai:runs`，不重写数据键 | `status 'skipped' !== 'ready'` | pass |
| rollback 声明身份时回退到 legacy tagged 源 | 身份转发修复前被“identity 未传入 plan”掩盖；回退实现后 pass（无回退会全 `source-missing`） | pass |
| rollback 带身份把 `ai:runs` 成员恢复为裸 `runId` | `actual ['run-1'] !== ['2/202/run-1']` | pass |

命令（逐条单测）：

```bash
node --test --test-name-pattern="正向带身份迁移把 ai:runs 成员重写" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="已迁移但索引陈旧" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="rollback 声明身份时回退到 legacy tagged 源" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="rollback 带身份把 ai:runs 成员恢复" scripts/redis-run-migration.test.mjs
```

整文件回归：

```bash
node --test scripts/redis-run-migration.test.mjs
# tests 58 / pass 58 / fail 0
```

### 3.2 Java（逐类精确执行）

Maven：`<MAVEN_HOME>\bin\mvn`（本机 3.9.9 发行版），统一
`-s maven-settings.xml -DfailIfNoTests=false test`，每次只指定一个测试类。

| 测试类 | 结果 |
| --- | --- |
| `SessionPurgePendingStoreTest` | pass（含写失败保留 pending + 守卫 armed） |
| `RedisClusterScanSupportTest` | pass |
| `SessionKeyRegistryTest` | pass |
| `AiSessionPurgeServiceTest` | pass（31/31） |
| `RunMigrationFamilyContractTest` | pass |

补充：`mvn -DskipTests compile` 在最终树态 exit 0。验证期间工作区有其它 agent 的
在飞改动，曾出现一次瞬时编译错误（`ConfirmGate` 找不到 `TenantUser`）与一次
`AiSessionPurgeServiceTest` 失败；同一命令重跑均转绿，未修改那些文件。

## 4. 剩余风险 / 未做项

1. **`scripts/README.md` 仍是旧口径**：仍写“旧裸键 → `{run:<runId>}`”“明确不迁 `ai:runs`”
   和“52/52”。本任务文件范围未包含该文件，未改；建议后续文档批同步为
   “带身份时重写 `ai:runs` 成员 + rollback 双形回退 + 58 例”。
2. **`SessionPurgePendingStore.java:70` 注释仍写“队列成员是 sessionId”**，与实际
   “成员 = pending 键”不一致；该源文件不在本任务允许修改范围，未改。
3. **`ai:runs` 类型异常**（非 SET）时该 run 记为 `conflict` 并拒绝 apply，需人工修复索引；
   这是有意的 fail-closed。
4. **rollback 双形回退按族选择**：若历史数据本身已经混合了带身份/legacy 两种形态，
   回退会逐族取“带身份优先、否则 legacy”，可能拼出一条混合来源的旧 run。正常迁移不会
   产生混合形态；离线窗口假设不变。
5. **未声明身份的迁移不重写 `ai:runs`**：这是设计口径（legacy 命名空间），迁移后的轮次
   仍只能被无身份路径发现；要进入新身份发现面必须显式声明身份。
6. **跳过非本身份 run 的 `warnings`** 已接线并通过编译/既有套件，但由于
   `AiSessionPurgeServiceTest` 不在本任务允许修改范围，未新增永久用例；建议后续补一条
   “A 身份销毁看到 B 身份同 sessionId 快照时结果含 `跳过非本身份` warning”的断言。
7. **未推送**。提交只包含本任务 6 个代码/测试文件与本报告；工作区中其它 agent 的
   `RunStore` / `ResumeService` / `ConfirmGate` 等在飞改动未纳入本次提交。

## 5. 小范围收尾（2026-10-30，§4 第 1/2 条已解决）

本轮只改文档/注释，不扩范围；未改业务代码、未推送。

### 5.1 改动

| 文件 | 改动 |
| --- | --- |
| `scripts/README.md` | 迁移说明改为“带身份 tagged 目标键 + `ai:runs` 成员重写 `tenantId/userId/runId` + rollback 带身份/legacy tagged 双形回退”；用例数 52/52 → **58/58**；`ai:runs` 不再笼统列为“明确不迁” |
| `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java` | `QUEUE_KEY` 顶部注释由“成员是 sessionId”改为“成员是 pending 键：键本身带完整复合身份” |
| 本报告 | 追加本节收尾结果 |

### 5.2 精确文本核对（全部 PASS）

```text
PASS README 表行：带身份 tagged 键
PASS README：ai:runs 索引重写小节
PASS README：索引成员重写命令（SREM 旧形 + SADD 新形，同一 MULTI）
PASS README：rollback 双形回退小节
PASS README：回退语义（先试带身份 tag，absent 再回退 legacy tagged）
PASS README：ai:runs 不再笼统列为不迁
PASS README：58/58 恰好 2 处
PASS README：无 52/52
PASS README：无旧的 ai:runs 排除写法
PASS 源码：队列成员是 pending 键
PASS 源码：无“成员是 sessionId”
```

### 5.3 check-docs

首次运行：`⑤ 命名纪律禁则` 红 1 处 —— 本报告第 4 行仍含旧主仓目录名。
按门禁口径改为 `<REPO>` 占位符后重跑：

```text
① 导航/口径文档无盘符绝对路径                 PASS
② 技术栈版本与 pom.xml / package.json 一致    PASS
③ 导航/口径文档引用的文件存在                 PASS
④ 端口表与 application.yml / vite.config.ts   PASS
⑤ 命名纪律禁则（git ls-files 全量）            PASS
⑥ docs/evidence/probes 的 .java 探针不参与构建 PASS
全部通过：6/6 断言绿。
```

### 5.4 结论

- §4 剩余风险第 1、2 条（README 旧口径、SessionPurgePendingStore 陈旧注释）已修掉。
- 本轮未跑代码测试（只改文档/注释）；上一轮 `node --test` 58/58 与 Java 定向用例结果仍然有效。
- 未推送。

## 6. 最终审查阻断项 I-1 修复（2026-10-30）

### 6.1 问题与修复

最终审查 I-1：正向带身份迁移只逐字节搬 `ai:run` 键，旧快照的 `tenantId/userId` 仍是 0/0；
`RunStore.readSnapshot` 在显式身份读口下按记录身份复核，0/0 记录读不到，迁移后的轮次不可续跑。

修复（未改 `RunStore` 的 fail-closed 规则）：

- 正向声明身份时，`ai:run` 不再是字节复制：写目标前 `JSON.parse` 快照，把
  `tenantId/userId` 置为命令行声明值（源已是其他身份则拒绝；0/0 或同身份可改写）。
- 写后回读目标并校验：记录身份必须等于声明值，且除 `tenantId/userId` 外其他字段逐值不变；
  不通过即失败，不进入 `--delete-source`。
- `--delete-source` 的校验与 DEL 前复验同样按“快照身份 + 其余字段”对账；目标键在但记录是 0/0
  时拒绝删源。
- 未声明身份：快照逐字节保留（含 0/0），dry-run/apply 明确告警“仅具归档价值、不属于任何新身份”。
- `ai:runs` 成员重写保留；配合快照身份写入，显式身份读口（键 + 记录身份复核）可命中。
- README/脚本头注同步说明“快照身份同步写入 + 写后回读校验”。

### 6.2 红 / 绿（逐条单点执行）

| 用例 | 红（旧实现） | 绿（修复后） |
| --- | --- | --- |
| 正向带身份：快照身份写入 + RunStore 式显式读口命中 + 其他字段不变 | `tenantId undefined !== 2` | pass |
| 未声明身份：不改写身份、仅归档告警 | `warnings=[]` | pass |
| 索引陈旧重跑：首次 `apply --delete-source` 写身份并通过删除校验 | `tenantId undefined !== 2` | pass（源已删除、索引为 `2/202/run-1`） |
| delete-source：目标回退成 0/0 时拒绝删源 | `Missing expected rejection`（旧实现会删源） | pass |
| 写后回读：目标身份被并发改成别的身份时 apply 失败 | 旧消息 `写后对账失败...value mismatch`（无身份校验） | pass（`写后身份校验失败`） |

命令示例（每次只跑一个 pattern）：

```bash
node --test --test-name-pattern="正向带身份迁移把 ai:runs 成员重写为 t/u/runId，且快照身份" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="未声明身份的 legacy 迁移不改写快照身份" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="已迁移但索引陈旧" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="delete-source 前复核目标快照身份" scripts/redis-run-migration.test.mjs
node --test --test-name-pattern="写后回读校验：目标快照身份被并发改成别的身份" scripts/redis-run-migration.test.mjs
```

整文件回归：**61/61 pass**。`node --check` 对脚本与测试文件均通过。

### 6.3 README 文本核对

10 项精确文本断言全部 PASS：快照身份同步小节、声明值写入、写后回读、delete-source 身份复核、
legacy 仅归档、`RunStore.get(runId, t, u)` 显式读口、61/61 恰好 2 处、无 58/58、脚本头注两处。

### 6.4 check-docs

`node scripts/ci/check-docs.mjs` 当前 **6/6 中 5 项 PASS、⑤ 红 1 处**：

```text
[FAIL] ⑤ 命名纪律禁则
.superpowers/sdd/2026-10-30-ai-tenant-user/task-final-small-fix-report.md:4
=> 命中命名纪律禁则（旧主仓目录名/同名分支名）
```

该文件是另一笔已入库报告（`e30c4f1c`），其中第 4 行含本机绝对路径；不在本任务允许修改范围，
未改动。本任务修改的 README/脚本/本报告均不在失败行内；若该报告按门禁口径改为 `<REPO>` 占位符，
check-docs 应恢复 6/6。

### 6.5 顾虑

- Node 用例中的“RunStore 式显式身份读取”是按 `RunStore.readSnapshot` 口径镜像（键 + 记录身份复核），
  不是直接调用 Java `RunStore.get(runId,t,u)`；本任务文件范围不允许改 Java 测试。
- rollback 仍按原样复制 tagged 值（不剥离/补写身份）；I-1 只要求正向带身份写入。
- check-docs 的红来自上述外部报告，提交前无法在不越界的前提下转绿。
- 未推送；未修改 `RunStore` / `ResumeService` / `ConfirmGate` 等运行链文件。

## 7. 文档门禁收尾（2026-10-30）

只处理 `check-docs` 的第 ⑤ 项：

- `task-final-small-fix-report.md:4` 的本机绝对路径改为 `<REPO>` 占位符；其他内容未动。
- `node scripts/ci/check-docs.mjs`：修前 5/6（⑤ 命中该行），修后 **6/6 全部通过**。
- 本任务未改其他文件；未推送。
