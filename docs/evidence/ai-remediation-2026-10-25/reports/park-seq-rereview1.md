> 来源=<仓外证据>/park-seq-rereview1.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Re-review — park/SSE fix round 1 (F1 / F3 / F7)

- Repository (main, patches applied): `<主仓>`, base `ae883fbae52be36e45a635ee276cf8298ac24bd2` + working tree
- Original review: `<仓外证据>/park-seq-task-review.md`
- Implementer evidence incl. append: `.superpowers/sdd/punisher-atom-simon-baz/task-park-seq-report.md` (lines 11-42)
- Fix diffs: `<验证副本>/_verify-20261025-park-seq/fix-round1-backend.patch`, `fix-round1-frontend.patch`; both present in main (`git diff --stat`: SseChatEmitter +95/−…, SpToolCallingManager comment-only block, two new tests, frontend spec +1 type import/annotation)
- Controller ruling accepted as given: F2 keeps starts for all accepted suspended tools; order stays `suspended → tool_start → request`. F4/F5/F6 deferred.

## Checks I ran (targeted only, no broad suite)

| Check | Result |
|---|---|
| `frontend: node_modules/.bin/vue-tsc --noEmit` | **exit 0** (F1 red→green on main) |
| `frontend: vitest run tests/park-request-tool-start-order.spec.ts` | **4/4 passed** |
| `backend: mvn -o -B -Dspring.data.redis.port=1 -Dtest=SseChatEmitterWakeFloorTest,ParkToolStartOrderConformanceTest test` | **5/5, BUILD SUCCESS** (WakeFloor 3, ParkToolStart 2) |
| `backend: mvn ... -Dtest=SseChatEmitterCrossInstanceWakeTest test` | **9 run, 1 FAILURE — BUILD FAILURE** (`selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame`: `Expecting actual [1L, 1L] to contain exactly [1L]`) |

The failed class is **not** in the round's 161-test regression list (report line 40), which is why the round's "161/161 green" does not contradict this result — it simply never ran the guard for self-wake idempotence.

## Finding-by-finding verdicts

### F1 — ADDRESSED

- `frontend/tests/park-request-tool-start-order.spec.ts:22` now imports `SseToolStartFrame, ToolKind`; `:48` reads `function toolStartFrame(toolCallId: string, kind: ToolKind = 'FRONTEND'): SseToolStartFrame`.
- Main-tree `vue-tsc --noEmit` exits 0 (previously the only 5 project errors were here), and the spec still passes 4/4. `npm run build`'s first stage (`vue-tsc --noEmit`) is therefore green; the implementer's `frontend-build.log` (exit 0) is consistent.
- No runtime change, as intended.

### F3 — ADDRESSED (in scope of the reported scenario)

- `backend/.../run/SseChatEmitter.java:1564` — `onWake` now takes its read lower bound from `minDeliveredSeq()` instead of the shared `lastFanoutSeq`; `:1622-1633` computes the minimum over non-closed subscribers; `:1517-1520` advances each subscriber's cursor on successful local fan-out; `:884` keeps replay's `max(maxReadSeq, lastSeq)`; `:1598-1600` keeps the per-subscriber skip.
- New deterministic test `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWakeFloorTest.java:96` (`aLostWakeIsStillDeliveredToACursorlessSubscriberAfterAnotherReplayAdvancesTheSharedBound`) reproduces exactly the finding's sequence and passes in main (3/3 class run).
- Residual boundary (unchanged contract, not a defect of this fix): recovery still requires a subsequent/retried wake or a client reattach — a permanently lost wake with no later frame is still recovered only by `?lastSeq` reconnect, which is the documented at-most-once wake contract.
- The fix keeps per-subscriber FIFO/seq monotonicity: a wake can only add frames the subscriber's cursor has not passed, and it runs under the same `emitLock` as the local fan-out.

### F7 — ADDRESSED

- `backend/src/test/java/com/example/configmgr/ai/conformance/ParkToolStartOrderConformanceTest.java:158` (`resumeAfterParkDoesNotEmitASecondToolStartForTheSameCard`) — real `SpToolCallingManager` + real `ConfirmGate` (park on) + real `ResumeService` (with a mocked model driver and inline executor); parks the two-card batch, settles both as `FRONTEND_RESULT`, resumes, asserts `ACCEPTED`, `driveResumed` invoked, and exactly one `tool_start` and one `frontend_tool_request` per card, plus no in-flight entries.
- Passes in main (2/2 in the class run). This is a genuine lock on the invariant, not an inference: a future re-emission in `resolvePending` would flip the count to 2.

### F2 / F4 / F5 / F6

- F2: ruling is recorded in code comments (`SpToolCallingManager.java:483-491`) with no behaviour change — consistent with the controller ruling; no action taken here.
- F4/F5/F6: confirmed untouched in the round-1 patch (`ConfirmGate.java` unchanged this round; `parts.ts` unchanged; `emitToolStartOnce` still calls `registry.of(runId)`). Deferred as instructed; not re-scored.

## New breakage introduced by round 1 — N1 (Important, round-blocking)

**Synchronous self-wake now delivers the same frame twice to a live subscriber.**

- Cause: `SseChatEmitter.java:1496-1498` registers the emitter high-water mark (`liveSeq`, `lastFanoutSeq`) and calls `publishWake(seq)` **before** the local fan-out loop at `:1506-1522` advances each subscriber's cursor (`:1517-1520`). `onWake` now reads from `minDeliveredSeq()` (`:1564`) instead of the pre-published `lastFanoutSeq`. So when the wake callback runs on the publishing thread (synchronous bus / in-process dispatch — a mode this file explicitly supports, see the comment at `:1494-1495`), the reentrant `onWake` sees the cursor still at `seq-1`, reads the just-appended frame from `FrameStore`, enqueues it and advances the cursor; `emit` then enqueues the same payload a second time in its fan-out loop, which has no cursor check.
- Proof: `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterCrossInstanceWakeTest.java:120-129` (`selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame`) fails on main:

```
[自唤醒不重复投递]
Expecting actual: [1L, 1L]
to contain exactly (and in same order): [1L]
  ...SseChatEmitterCrossInstanceWakeTest.java:127
Tests run: 9, Failures: 1, Errors: 0, Skipped: 0 — BUILD FAILURE
```

  This is a regression, not pre-existing: round 0's `onWake` used `long from = this.lastFanoutSeq.get();`, which `emit` set before `publishWake`, so the reentrant callback early-returned. The test's own javadoc names precisely this kill criterion ("把水位登记挪到本地扇出之后（或删掉）⇒ 本用例收到两帧，变红").
- Production reachability, stated honestly: with `RedisFrameWakeBus` the wake callback runs on the Redis listener thread and blocks on `emitLock` until `emit` has finished the fan-out and advanced the cursors, so the analysed production sequence does **not** duplicate. The failure is in the synchronous-dispatch mode (the repo's test double `FakeWakeBus`, and any future in-process bus), which the class documentation continues to treat as a supported, stricter ordering. There is no duplicate-detection at the client (a repeated `delta.seq` would be rendered again), so the guard matters.
- Stale documentation left behind by the change: `SseChatEmitter.java:257` ("跨实例唤醒按它取差量") and `:263` ("同一帧不会被本实例的'本地扇出 + 自唤醒'投递两次") plus `:1494-1495` still assert the invariant that no longer holds. `lastFanoutSeq` now has no readers at all (writes at `:886`, `:1497`, `:1609`).
- Suggested direction (not prescriptive): restore self-wake idempotence without reintroducing F3 — e.g. advance the per-subscriber cursors for the local frame *before* `publishWake` (the wake then sees `upperBound ≤ from` and returns), or have the fan-out skip a frame whose seq is already queued; do not revert the read floor to `lastFanoutSeq`.
- Also: add `SseChatEmitterCrossInstanceWakeTest` to the round's regression class list; the current list cannot detect this class of break.

## Overall round verdict

- **F1: ADDRESSED. F3: ADDRESSED. F7: ADDRESSED.**
- **Round 1: NOT ACCEPTABLE — one new delivery regression (N1) that leaves an existing, previously green guard test red in the touched module.** The backend "161/161" figure is accurate for its class list but incomplete: it omits the self-wake idempotence guard.
- No other new Critical/Important issue found in the fix diff: park frame order (`suspended → tool_start → request`) is unchanged, the dedupe set and the five loop branches are untouched, the per-subscriber skip still prevents duplicate/`?lastSeq` replay, replay and live FIFO contracts are preserved, and no lease/fencing/epoch or frontend runtime code was changed this round.

## Evidence gaps

- I did not rerun the 26-class backend suite or the 8-spec frontend set; I ran the two new backend classes, the one failing guard class, the frontend typecheck and the new spec. The implementer logs (`backend-regression.log`, `frontend-focused.log`, `frontend-build.log`) were not independently re-validated beyond the checks above.
- No three-instance / Redis-cluster or `@real` model evidence exists for this round, and none is claimed; the production-async-path mitigation argued for N1 is a code reading, not an experiment.
