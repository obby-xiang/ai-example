> 来源=<仓外证据>/resume-start-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Task review — resume `tool_start` backfill (suspend-kill-a gap)

- Repo (patch applied): `<主仓>`; base `ae883fb` + working tree
- Inputs: `<仓外证据>/resume-tool-start-fix-report.md`, `<验证副本>/_verify-20261025-park-seq/resume-tool-start-fix.patch` (applied: `SseChatEmitter.java:1139`, `SpToolCallingManager.java:1824/1834/1864`, new `ResumeToolStartBackfillConformanceTest.java`)
- Read-only; no subagents; no broad suite rerun. Date: 2026-10-25.

## What I verified myself

```
mvn -o -B -Dspring.data.redis.port=1 -Dtest='ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest' test
→ Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  ResumeToolStartBackfillConformanceTest 4/4 (log shows "续跑补发缺失的 tool_start … call-import-1")
  ParkToolStartOrderConformanceTest 2/2
```

Reported evidence cross-checked for existence and totals: `start-backfill-red.log:85,130` = `Tests run: 4, Failures: 3`; `start-backfill-regression2.log:1876` = `Tests run: 326 … BUILD SUCCESS`. Those logs are the implementer's; I re-ran only the two classes above.

## Requirement-by-requirement

| Requirement | Result |
|---|---|
| Original instance dies after park publication, before `tool_start` lands | Modeled and repaired: the resume path backfills exactly one start (`SseChatEmitter.ensureToolStart`, `:1139-1168`; called from `resolvePending` at `SpToolCallingManager.java:1824/1834`). Verified by test 1 (`ResumeToolStartBackfillConformanceTest.java:169`) with real gate + real `ResumeService` + real claim/execution and `invocations==1`. |
| Cross-instance resume: exactly one start **before** result | Met: predicate reads the shared archive (`SseChatEmitter.java:1146`), emits before `executeOnce`/`settleBlocked`, test asserts `suspended < tool_start < tool_result`. The predicate is instance-independent because it reads `FrameStore` (Redis Stream / shared store), not emitter state. |
| No duplicate when a start already exists | Met: any `type=tool_start` with equal `toolCallId` short-circuits (`:1159-1165`); test 2 (`:193`). Also holds for the crash-then-retry case (test 3, `:212`): the backfilled start is archived before the ledger crash, second resume adds none. |
| Repeated / crashing resume must not duplicate side effects | Met for side effects: re-execution is blocked by `claimExecution` (second resume settles `ALREADY_CLAIMED`); tests 3–4 assert `invocations==1` and no frame growth (`ALREADY_RUNNING`, `:235`). |
| Normal `suspended → tool_start → request` preserved | Met: `ensureToolStart` is reachable only through `resolvePending`, whose only caller is `ResumeService.java:842`; the first-round emission path (`emitToolStartOnce`) is untouched. `ParkToolStartOrderConformanceTest` 2/2 green on main. |
| Fencing / non-blocking preserved | Met: the backfill runs inside the resume driving thread (trigger executor), after the entry authority fence (`ResumeService.java:791-802`); the emit itself keeps the existing lease fencing (`SseChatEmitter.java:1480-1481`, drops business frames for `LOST`/`UNAVAILABLE`), so a stale instance cannot archive a duplicate start; no new lock, no new thread, nothing added to the park/HTTP path. |
| Read-degraded behaviour | Met conservatively and by code: `degraded()` or a read exception ⇒ no backfill (`SseChatEmitter.java:1147-1158`). No test for it (see M4). |

## Findings

No Critical, no Important.

### M1 — Minor: the predicate is read-then-emit, not a CAS; duplicate starts are possible only where both resumes pass the write fence

`SseChatEmitter.java:1146-1164`. Two concurrent resumes could both observe "no start" and both emit one. In practice the window is closed by: `ResumeService.resuming` (in-process), the resume lease + `verifyHeld`, the entry authority fence (`ResumeService.java:791-802`) and the emit-path fence (`SseChatEmitter.java:1480-1481`, drops business frames for `LOST`/`UNAVAILABLE`). The residual case is the documented degraded-lease/no-lease mode (report §6.3). Consequence is cosmetic — at most two `tool_start` frames for one id, which the frontend upserts into one part — while tool side effects remain protected by `claimExecution`. Report already states this; no action requested.

### M2 — Minor: the predicate reads a window-clamped slice, and window skipping is not signalled as degraded

`SseChatEmitter.java:1146` calls `readFromWithStatus(runId, 0, 0)`; the production store clamps the read lower bound to `tail − window` (`RedisFrameStore.java:408`) and only reports `degraded` for read failures / unknown tail (`:402`, `:418-424`), deliberately **not** for the skipped window (`:409-416`). A `tool_start` older than the retention window is therefore invisible to the predicate ⇒ a backfill could add a second start relative to trimmed history. Impact for this defect window is negligible (the crash happens at the archive tail, a handful of frames before the resume), but the report's §6.5 note only frames the window as a cost, not as a predicate blind spot.

### M3 — Minor: predicate keyed on `toolCallId` only

`SseChatEmitter.java:1159-1165`. If a model ever reuses a `toolCallId` in a later round of the same run, the earlier start suppresses the repair for the second call (missing start again). Protocol assumes unique ids per run and the frontend's part identity shares that assumption, so this is acceptable; documenting it in the method javadoc would cost one line.

### M4 — Minor: no test for the degraded-read branch

`SseChatEmitter.java:1147-1158` is unreachable with `InMemoryRunStore`'s default `FrameStore.readFromWithStatus` (always `degraded=false`, `FrameStore.java:83-85`). The branch is small and conservative, but it is exactly the branch that decides "leave the gap vs. risk a duplicate" under Redis trouble and is untested.

### M5 — Minor: the crash model is an in-process frame swallow, not a process death

The test subclasses the emitter to drop the first N `toolStart` calls (`ResumeToolStartBackfillConformanceTest.java:97-106`, `:253`) and throws from `appendLedger` for the mid-settlement crash (`:90-99`, `:215`). This is a faithful model of the *archive* state ("no start, no seq consumed") and it does exercise the real resume, the real claim and a real ledger-write crash. It does **not** model lease loss, a second emitter/registry instance, or a separate JVM, so it cannot prove the takeover sequence — only the predicate and its idempotency. The report is explicit about this (§4.1, §6.7); recording it as a fidelity caveat, not a defect.

### M6 — Minor (acknowledged): one full-window archive read + JSON parse per settled item

`SseChatEmitter.java:1146`, called once per executable/fake-approved item in `resolvePending`. Resume is not a hot path and the report counts this cost (§6.5); fine for now, worth remembering if batch resumes grow.

## Scope / coverage finding (as requested): FRONTEND_RESULT cards remain uncovered

- `resolvePending` backfills only in the `APPROVED + FRONTEND` blocked branch (`SpToolCallingManager.java:1824`) and the `shouldExecute` branch (`:1834`). Every other resolved status — including `FRONTEND_RESULT`, `FRONTEND_CANCELLED`, `REJECTED`, `TIMEOUT` — falls through to the "reuse resultText" tail (`:1838-1847`), which emits no frame.
- For a frontend card, the terminal frame is emitted by the decision path (`ConfirmGate.submitFrontendResult`, `ConfirmGate.java:1581-1585`; same for dismiss/expiry), i.e. **before** the resume runs. So if the original instance died before that card's `tool_start`, the archive permanently holds `frontend_tool_request → frontend_tool_result` with no start, and this patch does not repair it. Because the result frame is already archived by then, a backfill at `resolvePending` time would place the start *after* the result — i.e. it cannot be fixed here at all; the only correct repair point is the decision path, inside its `submitLock`, before the result frame is emitted.
- Reachability is identical to the fixed case: frontend cards get their pre-loop start from the same `emitToolStartOnce` (`SpToolCallingManager.java:490-494`), so the same crash window applies.
- Verdict: **out of this task's stated ownership boundary** (resume/execution settlement) and correctly documented by the implementer (§6.1). It is a genuine residual of the same defect class on a different ownership boundary and needs a separate decision + test; not a defect of this patch, and I am not proposing a fix here.

## Verdicts

- **Spec Compliance: PASS** for the stated requirement set (crash-before-start → exactly one backfilled start before the result; no duplicate when present; no duplicate side effects on crash/repeat resume; first-round order, fencing and non-blocking semantics preserved). The `FRONTEND_RESULT`/decision-path case is explicitly outside the defined boundary and is recorded above as a scope/coverage gap, not a spec failure of this patch.
- **Task Quality: PASS with Minor findings** (M1–M6). No blockers. Evidence level is unit/isolated-copy only: 4/4 focus and 326/326 regression are the implementer's logs (I re-ran 6 tests on main, all green); no real three-instance or `@real` run exists for this change, and the patch does not claim one.
