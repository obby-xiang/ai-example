> 来源=<仓外证据>/migration-drain-restore-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Migration lock integration regression — drain-window restore (Fix Round 1a)

- Review: `<仓外证据>/migration-rereview1.md` C1
- Work tree: `<验证副本>/_verify-20261025-migration-race/backend`
- Main: `<主仓>/backend` (read-only reference; no main edits)
- Scope: restore the readiness `drain-window` block in `application-h2tcp.yml`, add a profile/Binder contract test, prove RED→GREEN. Lock implementation untouched.
- No commit/push; no docs/九册; no keys/Redis/real ports/three instances; no subagents.

## 1. Important environment note (copy predated the readiness merge)

The isolated copy was created before the readiness drain task was merged into main. To run the requested `ReadinessDrainWindowTest`, these readiness-task files were synchronized **from current main** into the copy (they are byte-identical to main, so they do not appear in the regression patch):

```text
src/main/java/com/example/configmgr/health/ReadinessProperties.java
src/main/java/com/example/configmgr/health/ReadinessService.java
src/main/java/com/example/configmgr/health/ShutdownDrainGate.java
src/test/java/com/example/configmgr/health/ReadinessDrainWindowTest.java
src/main/resources/application.yml            (drain-window: 0s block, identical main)
src/main/resources/application-cluster.yml    (5s block, identical main)
src/main/resources/application-postgresql.yml (5s block/comment, identical main)
```

Only `application-h2tcp.yml` was semantically merged instead of copied: the new FLYWAY_LOCK comment and existing Hikari millisecond settings were preserved, and the missing readiness block was inserted between `server.shutdown: graceful` and the lock-timeout comment.

## 2. Exact files in this regression patch

```text
src/main/resources/application-h2tcp.yml
src/test/java/com/example/configmgr/config/ProfileConfigContractTest.java
```

Patch: `<仓外证据>/migration-drain-restore.diff`

`git apply --check` against current main:

```bash
cd <主仓>
git apply --check <仓外证据>/migration-drain-restore.diff
# exit 0
```

Patch content:

- `application-h2tcp.yml`: +8 lines, exact readiness block:

```yaml
app:
  health:
    readiness:
      # 多实例必须有 draining 窗口：Spring Boot 的 graceful shutdown 会立刻关闭监听 socket，
      # 没有窗口时 /health/ready 的 503 无法被反代探测到（三实例实测 +55ms 起 ECONNREFUSED）。
      # 5s ≥ HAProxy 的 inter 2s × fall 2（4s）＋余量。
      drain-window: 5s
```

- `ProfileConfigContractTest.java`: + `java.time.Duration` import and the focused Binder/profile test:
  - default `application.yml` → `app.health.readiness.drain-window == "0s"`;
  - `cluster`, `h2tcp`, `postgresql` each bind `Duration.ofSeconds(5)` via real YAML loading + Spring `Binder`.

No changes to `FlywayStartupLock*`, `V1~V9`, `pom.xml`, or other configs.

## 3. RED

### 3.1 Initial broken copy (all readiness blocks absent)

```bash
... mvn.cmd -o -Djacoco.skip=true \
  -Dtest=ProfileConfigContractTest#readinessDrainWindowDefaultsToZeroAndMultiInstanceProfilesUseFiveSeconds \
  -DfailIfNoTests=false test
```

Log: `target/drain-red1.log`

```
Tests run: 1, Failures: 1
[默认单实例必须显式为 0s（不等待）]
but was: null
```

### 3.2 Relevant h2tcp-only RED (readiness support synced, h2tcp block still missing)

This is the regression-specific RED requested:

```bash
... mvn.cmd -o -Djacoco.skip=true \
  -Dtest=ProfileConfigContractTest#readinessDrainWindowDefaultsToZeroAndMultiInstanceProfilesUseFiveSeconds \
  -DfailIfNoTests=false test
```

Log: `target/drain-red-h2tcp.log`

```
Tests run: 1, Failures: 1
[h2tcp profile 必须有 5s draining 窗口（readiness 摘流链依赖它）]
but was: null
```

## 4. GREEN

After inserting the h2tcp block:

```bash
... mvn.cmd -o -Djacoco.skip=true \
  -Dtest=ProfileConfigContractTest#readinessDrainWindowDefaultsToZeroAndMultiInstanceProfilesUseFiveSeconds \
  -DfailIfNoTests=false test
```

Log: `target/drain-green1.log` → `Tests run: 1, Failures: 0`

Full requested targeted set:

```bash
... mvn.cmd -o -Djacoco.skip=true \
  -Dtest=FlywayStartupLockTest,FlywayStartupLockConfigTest,FlywayStartupLockContextTest,ProfileConfigContractTest,ReadinessDrainWindowTest \
  -DfailIfNoTests=false test
```

Log: `target/drain-green-final.log`

```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Breakdown:

| Class | tests |
|---|---:|
| FlywayStartupLockTest | 14 |
| FlywayStartupLockConfigTest | 3 |
| FlywayStartupLockContextTest | 1 |
| ProfileConfigContractTest | 6 |
| ReadinessDrainWindowTest | 1 |

`test-compile`:

```bash
... mvn.cmd -o -DskipTests -Djacoco.skip=true test-compile
```

Log: `target/drain-test-compile-final.log` → `BUILD SUCCESS`.

## 5. Preservation checks

- `FLYWAY_LOCK` schema/DDL comment in `application-h2tcp.yml` retained.
- Hikari `connection-timeout: 5000` (and all millisecond settings) retained; `ProfileConfigContractTest#hikariTimeoutValuesAreMilliseconds` green.
- `FlywayStartupLock.java`, `FlywayConnectionPin.java`, `FlywayStartupLockTest.java` unchanged by this regression round; their 14+3+1 tests green.
- V1~V9 untouched; no new migrations.
- `app.health.readiness.drain-window`: default `0s`; `cluster`/`h2tcp`/`postgresql` `5s`.
- Lock implementation remains exactly as reviewed in Round 1.

## 6. Caveats

- Current main (`<主仓>`) actually **still lacks** the h2tcp block at the time of this check (its `git diff` only shows the FLYWAY_LOCK comment); the patch is generated against that actual state and `git apply --check` passes. If another stream applies the block first, this patch would need rebasing (or become a no-op).
- The copy was pre-readiness; readiness source/config items were synced from main as noted in §1. They are identical to main and excluded from the patch.
- Real PG / real three JVMs remain CI boundaries (not claimed locally).
- Model selection is fixed by the harness; cannot switch to DeepSeek/deepseek-flash from this subagent.
