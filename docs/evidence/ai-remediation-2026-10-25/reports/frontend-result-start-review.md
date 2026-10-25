> 来源=<仓外证据>/frontend-result-start-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Task review — decision-side `tool_start` backfill for FRONTEND_RESULT (suspend-kill-a residual)

- Repo (patch applied): `<主仓>`; base `ae883fb` + prior batches (latch fix, resume fix)
- Inputs: `<仓外证据>/frontend-result-start-fix-report.md`, `<验证副本>/_verify-20261025-park-seq/frontend-result-start-fix.patch`
  (applied: `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:1581-1603`, new `backend/src/test/java/com/example/configmgr/ai/conformance/FrontendResultToolStartConformanceTest.java`)
- Read-only; no subagents; no broad suite rerun. Date: 2026-10-25.

## Verification I ran on main

```
mvn -o -B -Dspring.data.redis.port=1 -Dtest='FrontendResultToolStartConformanceTest,
  ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest' test
→ Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS   (4 + 4 + 2)

mvn ... -Dtest='ToolFrameSequenceConformanceTest,GenerativeFormGateTest,
  ConfirmGateRequestEmittedTest,AiControllerFrontendResultTest' test
→ Tests run: 32, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

The second run is my own check that existing exact-frame-sequence / frontend-gate / controller tests were not disturbed by the extra start. Reported evidence exists as claimed: `frontend-result-red.log:81,126` = `4 run / 3 failures`; `frontend-result-regression.log:1978` = `358 … BUILD SUCCESS` (implementer's logs; not re-run by me).

## Requirement-by-requirement

| Requirement | Result |
|---|---|
| Missing `tool_start` ⇒ decision archives exactly one start **before** `frontend_tool_result` | Met. Backfill sits between `putPending` and `frontendToolResult` inside `submitLock` (`ConfirmGate.java:1581-1603`); predicate is the shared archive scan (`SseChatEmitter.ensureToolStart`, `SseChatEmitter.java:1139-1168`). Test 1 (`FrontendResultToolStartConformanceTest.java:169`) asserts count 1 and `suspended < tool_start < frontend_tool_result`. |
| Resume / repeat decision does not duplicate | Met. `FRONTEND_RESULT` is not in the resume's executable set (`SpToolCallingManager.java:1827-1830`), so the resume cannot backfill this card; test 1 resumes afterwards and still sees 1 start / 1 result. Repeat decision returns `DUPLICATE` before the write block (`ConfirmGate.java:1529-1531`), so it never reaches the backfill; test 3 asserts no new frames. |
| Existing start and normal order unchanged | Met. `ensureToolStart` returns false when any `tool_start` with the same `toolCallId` is archived (`SseChatEmitter.java:1159-1165`); fixtures with the original start are unchanged (test 2 asserts `suspended → tool_start → frontend_tool_request`), `ParkToolStartOrderConformanceTest` 2/2, and the frame-sequence/frontend-gate/controller set 32/32. |
| Same emitter identity and `submitLock` ownership | Met. `SseChatEmitter out = this.registry.of(runId)` (`:1589`) is used for both the backfilled start and the result frame (`:1601`); `putPending → backfill → result → signal` all execute inside the one `submitLock` block (`:1581-1603`), `maybeTriggerResume` remains outside as before. |
| Decision idempotency / fencing preserved | Met. Single writer via `tryRecordDecision` before the block; backfill is wrapped in try/catch and only logs on failure (`:1590-1600`), so persistence/frame/wake semantics are unchanged; the emitted frame goes through `SseChatEmitter.emit`'s lease-authority fence (`SseChatEmitter.java:1480-1481`), so a stale instance still cannot archive it. Lock order `submitLock → emitLock` already existed (the result frame was emitted inside the same block before this patch), so no new lock ordering. |
| Non-blocking | Met: one archive read + one existing emit inside the decision critical section; no new thread/latch/wait. |

Additional positive: the repaired archive order is `frontend_tool_request → tool_start → frontend_tool_result`. Because the part is already `requiresAction=true` when the late start arrives, the frontend guard (`frontend/src/utils/parts.ts:545-548`) skips the reset and the card stays submittable until the result settles it — the repaired order is safe for the UI, not just for the bookkeeping.

## Findings — no Critical, no Important

### M1 — Minor: full-window archive read inside the decision critical section
`ConfirmGate.java:1590-1595` (via `SseChatEmitter.java:1146`) reads up to the 6000-frame window under `submitLock`, the same lock that serialises decisions, the park-exit handshake (`ConfirmGate.java:779`) and the waiting thread's poll (`:675`). Milliseconds-scale widening only; acknowledged in report §6.4. No ordering or atomicity change.

### M2 — Minor: second copy of the try/catch + logging shape
`ConfirmGate.java:1590-1600` duplicates the pattern already in `SpToolCallingManager.java:1864-1875`; a shared helper on the manager would avoid drift. Cosmetic.

### M3 — Minor (inherited, not introduced here): predicate is read-then-emit, window-clamped, conservative on degraded reads
`SseChatEmitter.java:1146-1164`. Unchanged from the previous pass. On this path the "not a CAS" concern is largely gone: only the `tryRecordDecision` winner reaches the block, so one card cannot get two decision-side backfills. Window/degraded limits (M2/M4 of the prior review) still apply.

### M4 — Minor (test fidelity): crash is modelled by overriding the emitter
`FrontendResultToolStartConformanceTest.java:90-106` swallows the first `toolStart` and throws from `frontendToolResult`; this reproduces the archive state and the mid-block death, but does not model a lost lease, a second JVM/instance, or a degraded archive read. Same caveat the implementer states (§6.5). No test exercises the degraded-read skip on this path.

## Ledgered residuals (documented by the controller; **not** demanded in this patch)

1. Same defect class on the other terminal-frame paths: `dismiss`/`FRONTEND_CANCELLED` (`ConfirmGate.java:1628`), park-expiry `TIMEOUT`/`FRONTEND_TIMEOUT`, whole-run `CANCELLED`. They still emit a terminal frame without a `tool_start` if the original instance died before it; each is a different ownership boundary and would need its own call site + test. Report §6.1; untouched by this patch (`dismiss` verified unchanged).
2. A crash **between** the backfilled start and the result frame leaves no result forever — frontend cards' terminal frame is produced only by the decision path, so a resume cannot reconstruct it. Fixing that needs idempotent replay/reconstruction of decision frames (report §6.2). Pre-existing semantics, explicitly out of scope.
3. Deterministic window/degraded caveats from the previous two passes carry over unchanged.

## Verdicts

- **Spec Compliance: PASS.** Exactly one start before `frontend_tool_result` when it was missing; no duplicate on resume or repeat decision; existing start and the normal first-round order untouched; same emitter and same `submitLock` ownership; decision idempotency, fencing and non-blocking semantics preserved.
- **Task Quality: PASS with Minor findings (M1–M4).** No blockers. Evidence level remains unit/isolated-copy: 4/4 focus, 13/13 cross-boundary and 358/358 regression are the implementer's logs (I re-ran 10 + 32 tests on main, all green); no real three-instance or `@real` result exists for this patch, and none is claimed.
