# 最终 7 红项小范围修复报告

- 日期：2026-10-30
- 范围：主仓 `E:/temp/ai-example-code/ai-example-main-v2`
- 未派子 agent；未推送；未改任何迁移文件（`db/migration`、`db/postgresql` 零改动）；只跑下文 4 条精确单点命令，未跑任何批量/全量。

## 1. 红项清单（施工前实测，7 项）

| # | 测试 | 红形态 |
| --- | --- | --- |
| 1 | `ErrorCodesContractTest#frontendMirrorMatchesBackendCodes` | 前端镜像缺 `TENANT_IDENTITY_REQUIRED` / `TENANT_IDENTITY_UNKNOWN` |
| 2 | `ErrorCodesContractTest#messagesCoverEveryCodeInBothLocales` | `messages_zh`（及 `messages_en`）缺上述两键 |
| 3 | `GlobalExceptionHandlerTest#allCodesHaveChineseAndEnglishText` | `NoSuchMessageException: error.TENANT_IDENTITY_REQUIRED` |
| 4 | `FlywayStartupLockTest#secondMigratorWaitsAtTheLockThenOnlyOneMigrationSequenceRuns` | 实际 `1..11`，断言停在 `1..9` |
| 5 | `FlywayStartupLockTest#migrateExceptionReleasesTheH2Lock` | 同上 |
| 6 | `FlywayStartupLockTest#h2RowLockOnBusinessSchemaDoesNotLeakIntoFlywayBaseline` | 同上（含 `historyCount` 9 vs 11） |
| 7 | `FlywayStartupLockContextTest` | 上下文加载失败：`SessionIdGenerator` 在多实例判据 `profile=h2tcp` 下缺 `app.instance-slot`（版本断言从未执行到） |

## 2. 改动文件

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/resources/messages_zh.properties` | 补 `error.TENANT_IDENTITY_REQUIRED` / `error.TENANT_IDENTITY_UNKNOWN` 两行（其余键一字不动） |
| `backend/src/main/resources/messages_en.properties` | 同上（en） |
| `frontend/src/types/api.ts` | `ErrorCode` 镜像补两码，位置与后端声明顺序一致（`AI_CONTEXT_UNAVAILABLE` 之后） |
| `frontend/src/utils/error-card.ts` | 两码共用一张错误卡：`CODE_CARD_TEXT` + `CODE_SUMMARY_KEY` 各两条映射（同一组文案，不分叉） |
| `frontend/src/locales/zh-CN.json` | 新增 `ai.card.error.tenantIdentity.title/guide` 与 `ai.card.error.summary.tenantIdentity` |
| `frontend/src/locales/en-US.json` | 同上（en，键集与 zh 对齐） |
| `backend/src/test/java/com/example/configmgr/config/FlywayStartupLockTest.java` | 3 处 `containsExactly` 补 `"10","11"`，2 处 `historyCount` 9→11 |
| `backend/src/test/java/com/example/configmgr/config/FlywayStartupLockContextTest.java` | `@TestPropertySource` 补 `app.instance-slot=0`；计数 9→11；方法名 `AppliesV1ToV9Once`→`AppliesV1ToV11Once` |
| `.superpowers/sdd/2026-10-30-ai-tenant-user/task-runtime-fix-report.md` | 删除文件末尾多余空行（171 行处的 blank line at EOF） |
| `.superpowers/sdd/2026-10-30-ai-tenant-user/task-final-small-fix-report.md` | 本报告 |

## 3. 逐项说明

### 3.1 两码补文案 + 前端镜像（红项 1–3）

- 后端按现有风格各补一句用户向短句，键序与 `ErrorCodes` 声明序一致（`AI_CONTEXT_UNAVAILABLE` 之后）：
  - zh：`缺少租户/用户身份，请先选择身份后重试` / `租户/用户身份未登记，请重新选择身份后重试`
  - en：`Tenant/user identity is missing…` / `Tenant/user identity is not registered…`
- 前端两码合成**一张卡**（`ai.card.error.tenantIdentity.*`）：标题「身份校验未通过」、引导「请在上方选择租户与用户后重试。」、
  摘要一句「这次请求没有带上可识别的租户/用户身份。」——两码对用户是同一件事，不新增第二套说法，故共用文案。
- 其他错误码零改动：`messages_*` 未增删其他键，`ErrorCode` 未动其他项，`error-card.ts` 未动既有映射。

### 3.2 Flyway 迁移序列（红项 4–6）

- 真实序列（`MigrationSetGuardTest` 已锁定同一口径）：H2 为 `1,2,3,4,5,6(Java),7,8,9,10,11`，其中
  **V10 删 `ai_chat_message`**、**V11 反馈身份列**。
- 三处 `containsExactly` 由 9 项补到 11 项、两处 `historyCount` 9→11：仍是"逐版本严格相等 + 总数相等"，断言强度不降。
- 迁移文件零改动。

### 3.3 上下文测试起不来（红项 7，附带必要修正）

施工前实测该用例并非"版本断言红"，而是 `ApplicationContext` 起不来：
`SessionIdGenerator` 的实例位守卫把 `h2tcp` 判为多实例 profile，缺 `app.instance-slot` 即启动失败
（`SessionIdGenerator.resolveInstanceSlot`）。因此补两处：

1. `@TestPropertySource` 增加 `app.instance-slot=0`（单进程测试固定 0；仅测试侧，生产配置与守卫逻辑零改动）；
2. 计数 9→11，方法名同步为 `AppliesV1ToV11Once`（原名字已与真实迁移集不符）。

不改守卫、不改 `application-h2tcp.yml`：多实例强制实例位的语义是设计的一部分，测试补属性才是不削弱它的做法。

### 3.4 报告末尾空行（红项 3 之外的空白项）

`task-runtime-fix-report.md` 末尾原有 `\n\n`（空末行），`git show --check` 会报 `171: new blank line at EOF.`。
改为单个 `\n` 后：`git diff --check` 无输出（仅 git 的 `core.autocrlf` CRLF 提示，属提示非错误）。

## 4. 红绿命令（均为逐条精确单点，无批量）

工作目录 `backend`，Maven：`E:/temp/_tooling-maven/apache-maven-3.9.9/bin/mvn`。

**红（施工前，一次对照运行 4 个目标）：**

```bash
mvn -o -B test -Dtest='ErrorCodesContractTest,GlobalExceptionHandlerTest#allCodesHaveChineseAndEnglishText,FlywayStartupLockTest,FlywayStartupLockContextTest' -DfailIfNoTests=false
# Tests run: 22, Failures: 5, Errors: 2 —— 恰为上文 7 项
```

**绿（逐项）：**

```bash
mvn -o -B test -Dtest='ErrorCodesContractTest#frontendMirrorMatchesBackendCodes+messagesCoverEveryCodeInBothLocales' -DfailIfNoTests=false
# Tests run: 2, Failures: 0, Errors: 0 —— BUILD SUCCESS

mvn -o -B test -Dtest='GlobalExceptionHandlerTest#allCodesHaveChineseAndEnglishText' -DfailIfNoTests=false
# Tests run: 1, Failures: 0, Errors: 0 —— BUILD SUCCESS

mvn -o -B test -Dtest='FlywayStartupLockTest#secondMigratorWaitsAtTheLockThenOnlyOneMigrationSequenceRuns+migrateExceptionReleasesTheH2Lock+h2RowLockOnBusinessSchemaDoesNotLeakIntoFlywayBaseline' -DfailIfNoTests=false
# Tests run: 3, Failures: 0, Errors: 0 —— BUILD SUCCESS

mvn -o -B test -Dtest='FlywayStartupLockContextTest' -DfailIfNoTests=false
# Tests run: 1, Failures: 0, Errors: 0 —— BUILD SUCCESS（27.9s，含整上下文启动）
```

**空白项：**

```bash
git show --check f91f9753 -- .superpowers/sdd/2026-10-30-ai-tenant-user/task-runtime-fix-report.md
# 红：task-runtime-fix-report.md:171: new blank line at EOF.

git diff --check
# 绿：无输出（退出码 0）
```

## 5. 未做 / 说明

- 未跑前端测试与 `vue-tsc`（按"只跑上述单点"约束）：前端改动仅新增镜像码表两条与一套 locale 文案，
  已用 `node` 校验两个 locale JSON 可解析且新键在 zh/en 双向存在。
- 未跑其余 15 条已绿用例的复跑（同类的其他方法）。
- 未推送；未改迁移文件；未改 `SessionIdGenerator` 守卫与 `application-h2tcp.yml`。
