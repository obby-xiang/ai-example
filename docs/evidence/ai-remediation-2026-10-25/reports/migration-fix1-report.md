> 来源=<仓外证据>/migration-fix1-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Migration lock — Fix Round 1 report

- Review: `<仓外证据>/migration-task-review.md`
- Work tree: `<验证副本>/_verify-20261025-migration-race/backend` (from current main `<主仓>/backend`, HEAD `ae883fb`)
- Scope: only Flyway cold-start lock; no main edit, no subagents, no docs/九册/keys/Redis/ports/three instances; no commit/push.
- Model: runtime model is fixed by the harness; the tool layer cannot switch it to DeepSeek/deepseek-flash. Flagged as a constraint, not claimed as done.

## 1. Changed files vs current main

```text
src/main/java/com/example/configmgr/config/FlywayStartupLock.java
src/main/java/com/example/configmgr/config/FlywayConnectionPin.java
src/test/java/com/example/configmgr/config/FlywayStartupLockTest.java
src/main/resources/application-h2tcp.yml
```

Unchanged relative to main: `FlywayStartupLockConfig.java`, `FlywayStartupLockConfigTest.java`, `FlywayStartupLockContextTest.java`, `pom.xml`, all V1–V9 migration files, `PostgresMigrationConfig`.

Patch: `<仓外证据>/migration-fix1.diff` (696 lines, unified diff).

## 2. Review item → fix

| Review item | Fix |
|---|---|
| I1: PG unlock failure swallowed; no same-connection test | `releasePostgres` now uses `executeQuery("SELECT pg_advisory_unlock(...)")`, treats `false`/SQLException as failure, calls `connection.abort(...)`, then close, and propagates `FlywayException` after a successful migrate. `sameConnectionPinningIsAssertedFromInsideFlywayMigrate` asserts `pin.current() == lockConnection` inside `Flyway.migrate()`; removing the `pin(...)` line makes it red. |
| I2: ~3% race flake was primary evidence | Removed flaky no-lock test. New deterministic test holds the lock via a blocking mock Flyway, starts a second (real Flyway) migrator, asserts it is still not done after 300 ms, releases the holder, then asserts both finish and exactly V1–V9 executed once / one history table. |
| I4: no negative tests | Added H2 bounded-wait timeout (300 ms, mock Flyway must not run), H2 migrate-exception then immediate re-acquire (2 s bound), PG migrate-exception still unlocks, PG unlock false/SQLException → abort + propagate. |
| I5: H2 out-of-band lock connection | H2 lock now uses the primary pooled connection (Hikari `connection-timeout: 5000` in h2tcp), not `DriverManager` with no timeout. After migrate it verifies `isValid(2)` **and** that the `holder` token is still ours; invalid connection or changed holder → startup failure. |
| I6 / second lock DB risk | Removed the second `<db>_flyway_lock` database entirely. H2 lock table is now in the **business DB's separate schema `FLYWAY_LOCK`**; tests/context prove Flyway still baselines/migrates correctly (V1–V9 once). h2tcp header documents the lock schema and DDL privilege. |
| M1: contradictory comments | `FlywayConnectionPin` says same-connection is PG-only; `FlywayStartupLockTest` no longer claims H2 `SET EXCLUSIVE`; `FlywayStartupLock` documents H2 independent connection as a hard boundary. |
| M2: swallow all DDL errors | H2 schema/table/insert catches only “already exists”/SQLState 23505/unique violation; other errors propagate. |
| M5: unlock failure invisible | Unlock false/SQLException is a `FlywayException`, connection aborted; no silent return to pool. |

## 3. RED evidence

### 3.1 PG unlock failure (old swallow behavior)

Temporarily restored the old `releasePostgres` (catch-and-ignore), ran the new behavioral tests:

```bash
"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -Djacoco.skip=true \
  -Dtest='FlywayStartupLockTest#postgresUnlockFalseIsPropagatedAndPhysicalConnectionIsAborted+postgresUnlockSqlExceptionIsPropagatedAndConnectionAborted' \
  -DfailIfNoTests=false test
```

Log: `target/round1-red-unlock.log`

```
Tests run: 2, Failures: 2, Errors: 0
java.lang.AssertionError: Expecting code to raise a throwable.
```

### 3.2 Same-connection pin removed

Temporarily removed `try (AutoCloseable pinned = this.pin.pin(lockConnection))` around `flyway.migrate()`:

```bash
... -Dtest='FlywayStartupLockTest#sameConnectionPinningIsAssertedFromInsideFlywayMigrate' test
```

Log: `target/round1-red-pin.log`

```
Tests run: 1, Failures: 1
Expecting actual:
  null
...
```

### 3.3 Historical race evidence (not primary anymore)

Prior probe: 100 no-lock rounds, 3 exact failures:

```
Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty.
```

This remains background evidence only; the primary contention evidence is now the deterministic latch test.

## 4. GREEN evidence

```bash
"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -Djacoco.skip=true \
  -Dtest=FlywayStartupLockTest,FlywayStartupLockConfigTest,FlywayStartupLockContextTest,MigrationSetGuardTest,PostgresMigrationSqlContractTest,ProfileConfigContractTest,TaskChildFkMigrationTest,AiModelReasoningBackfillMigrationTest \
  -DfailIfNoTests=false test
```

Log: `target/round1-final-all3.log`

```
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Breakdown:

| Class | tests |
|---|---:|
| FlywayStartupLockTest | 14 |
| FlywayStartupLockConfigTest | 3 |
| FlywayStartupLockContextTest | 1 |
| MigrationSetGuardTest | 9 |
| PostgresMigrationSqlContractTest | 1 |
| ProfileConfigContractTest | 5 |
| TaskChildFkMigrationTest | 2 |
| AiModelReasoningBackfillMigrationTest | 3 |

`test-compile`:

```bash
... mvn.cmd -o -DskipTests -Djacoco.skip=true test-compile
```

Log: `target/round1-test-compile4.log` → `BUILD SUCCESS`.

## 5. Key deterministic tests

- `secondMigratorWaitsAtTheLockThenOnlyOneMigrationSequenceRuns`
  - Real shared H2; first migrator holds the lock and blocks on a latch; second starts and is asserted **not done after 300 ms**; release; both finish; `version IS NOT NULL` = `1..9` exactly, one `flyway_schema_history`.
- `boundedWaitTimeoutFailsClosedInsteadOfMigratingConcurrently`
  - Holder blocks; second lock has `waitTimeout=300ms`; `FlywayException` in <5 s; mock Flyway has **zero interactions** (no migration).
- `migrateExceptionReleasesTheH2Lock`
  - Mock Flyway throws; then a 2 s-limit lock acquires immediately and runs real Flyway; V1–V9 applied.
- `postgresMigrateExceptionStillUnlocksAndPropagates`
  - Mock PG; migrate throws; unlock is still issued; original exception propagates.
- `postgresUnlockFalseIsPropagatedAndPhysicalConnectionIsAborted`
- `postgresUnlockSqlExceptionIsPropagatedAndConnectionAborted`
- `sameConnectionPinningIsAssertedFromInsideFlywayMigrate`
- `h2RowLockOnBusinessSchemaDoesNotLeakIntoFlywayBaseline` — real H2: V1–V9 once with `FLYWAY_LOCK` schema present.
- `h2SameConnectionRowLockIsReleasedByCommitSoItCannotCoverFlyway` — exact evidence that a same-connection `SELECT ... FOR UPDATE` lock is released by commit, so H2 cannot use it across Flyway's commits.
- `h2StaleLockConnectionIsDetectedAfterMigration`, `h2HolderMismatchAfterMigrationFailsClosed` — liveness/token fail-closed.

## 6. Actual lock semantics after fix

| DB | Lock | Bounded wait | Lock connection vs Flyway | Release/failure |
|---|---|---|---|---|
| PostgreSQL | `pg_advisory_lock(20261025)` | `SET lock_timeout='<ms>'` (default 60000 ms) | **same physical connection** (pinned) | `pg_advisory_unlock`; false/exception → abort + close + throw |
| H2 TCP | `FLYWAY_LOCK.flyway_startup_lock` row lock (`SELECT ... FOR UPDATE`) in business DB | `SET LOCK_TIMEOUT <ms>` (default 60000 ms) | **separate connection** (H2 hard boundary) | rollback + close; verify `isValid` + holder token before release; failure → abort/throw |

H2 no longer creates a second lock database. The lock schema lives in the business DB; it does not make Flyway baseline PUBLIC (verified by real H2 test and full h2tcp context test).

## 7. Unverified / remaining boundaries

1. **Real PostgreSQL** not run. `pg_advisory_lock`, `pg_advisory_unlock` boolean handling, `lock_timeout` behavior, and abort/eviction are covered by precise Mockito contract + behavioral tests only. CI boundary.
2. **Real H2 TCP / real three JVMs** not run. Shared in-memory H2 with multiple connections is the local evidence; server-side row-lock semantics should carry over, but TCP URL/credential/server lifecycle is not locally verified. CI boundary.
3. **H2 same-connection requirement still not met**: H2 lacks a session-level named lock that survives commits; `SET EXCLUSIVE` rejects new connections and its wait is not interruptible. The exact commit-release evidence is in `h2SameConnectionRowLockIsReleasedByCommitSoItCannotCoverFlyway`. The H2 lock connection is independent, with liveness/token fail-closed.
4. H2 lock schema requires DDL privilege (`sa` in h2tcp); documented in `application-h2tcp.yml`. If an H2 user has no schema-create privilege, startup fails closed.
5. H2 residual risk: if the lock connection silently dies **during** migrate, another instance can acquire; post-migrate `isValid`/holder check detects and fails startup, but the overlapping migration already happened. Eliminating this requires an H2 session-level lock (not available).
6. No full backend suite run; targeted migration/context tests only, per review round.
7. Runtime model is fixed by the harness; DeepSeek/deepseek-flash cannot be selected from this subagent.

## 8. Evidence files

- RED unlock: `target/round1-red-unlock.log`
- RED pin: `target/round1-red-pin.log`
- GREEN final: `target/round1-final-all3.log`
- Test compile: `target/round1-test-compile4.log`
- Patch: `<仓外证据>/migration-fix1.diff`
