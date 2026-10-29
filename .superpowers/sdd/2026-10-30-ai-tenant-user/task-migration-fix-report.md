# 租户迁移 / 断言 / 文档整合修复报告

- 日期：2026-10-30
- 范围：主仓 `ai-example-code/ai-example-main-v2`
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
