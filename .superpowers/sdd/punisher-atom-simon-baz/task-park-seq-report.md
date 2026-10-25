# Park/SSE implementation evidence

Implementation was supplied by agent-73 in an isolated copy and selectively integrated by the controller. This is not an independent review.

Main-tree backend command: IDEA Maven `mvn.cmd -B -Dspring.data.redis.port=1 -Dtest='ParkToolStartOrderConformanceTest,SseChatEmitterWakeFloorTest,ReattachLastSeqConformanceTest,SseFrameSequenceConformanceTest,ChatTurnFrameSequenceConformanceTest,ToolFrameSequenceConformanceTest,SseChatEmitterFramesTest,SseChatEmitterDeliveryTest,SseChatEmitterDrainHandoffTest,SseChatEmitterBusinessActivityTest,SseChatEmitterAuthorityFailureTest,FrameWakeListenerTest,CancelDecisionFramesTest,SeqPersistenceTest,RunStoreActivityAnchorTest,MultiCardParkResumeTest,HumanWaitParkTest,AiControllerEventsParkCloseTest,AiControllerParkWiringTest,FrontendGuardPreSuspendTest,ParkedFrontendExpiryTest,SpToolCallingManagerGateArmTest,SpToolCallingManagerBatchStopTest,SpToolCallingManagerTurnBudgetTest,ToolPreflightGateTest,ConfirmGateRequestEmittedTest' test`.

Observed main-tree result in <仓外证据>/park-seq-targeted.log: Tests run 159, Failures 0, Errors 0, Skipped 0; BUILD SUCCESS, finished 2026-10-25T16:01:03+08:00.

The original intermittent symptom is not claimed fixed by this unit run. New deterministic cases cover park frame order and the per-subscriber replay wake floor. Frontend full test/build has been dispatched separately against the current integration tree. No real three-instance or @real result is claimed for this patch yet.

## Fix round 1 (independent review F1 / F3 / F7; F2 ruling recorded)

Scope: the seven-file park/SSE patch. Work was done in the isolated copy `<隔离副本>/_verify-20261025-park-seq`; before editing, all touched files were compared byte-for-byte with main and were identical (the copy also predates agent-75's later health/job/file changes, which were intentionally not imported). Main source was not modified by this round; patches are provided for the controller.

Files changed in the copy (patches apply cleanly to current main, `git apply --check -p1` = 0):
- Backend (4): `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`, `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java` (comment only), `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWakeFloorTest.java`, `backend/src/test/java/com/example/configmgr/ai/conformance/ParkToolStartOrderConformanceTest.java`.
- Frontend (1): `frontend/tests/park-request-tool-start-order.spec.ts` (typing only; `src/utils/parts.ts` unchanged this round).
- Patches: `<隔离副本>/_verify-20261025-park-seq/fix-round1-backend.patch` (380 lines), `fix-round1-frontend.patch` (19 lines).

F1 (blocking build) — fixed, RED→GREEN:
- RED: `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` → 5 × TS2345 in the new spec (`kind: string` vs `ToolKind`) — `<隔离副本>/_verify-20261025-park-seq/tsc-before.log`.
- Fix: helper annotated `kind: ToolKind = 'FRONTEND'` and return type `SseToolStartFrame` (imported from `@/types/sse`). No runtime change.
- GREEN: `vue-tsc --noEmit` exit 0 (`tsc-after.log`); `npm run build` exit 0, built in 2m 9s (`frontend-build.log`); spec 4/4.

F3 (accepted gap, real defect) — fixed, RED→GREEN:
- New deterministic test `SseChatEmitterWakeFloorTest#aLostWakeIsStillDeliveredToACursorlessSubscriberAfterAnotherReplayAdvancesTheSharedBound`: cursorless subscriber B has 1..3; another instance writes 4 and the wake is lost; another subscriber reattaches with `lastSeq=3`, its replay reads 4 and advances the shared `lastFanoutSeq` to 4; the retried wake for 4 must still reach B.
- RED (`-Dtest=SseChatEmitterWakeFloorTest`, log `F3-red.log`): B's frames = `[1,2,3]`, missing `4` (3 run / 1 failure).
- Fix (smallest per-subscriber delivery cursor, ordered handoff and `?lastSeq` semantics preserved): `Subscription.wakeFloor` is now the per-subscriber delivered cursor advanced by **replay** (`stageReplay` → `max(maxReadSeq, lastSeq)`), **local live fan-out** (`emit`, `liveSeq` for non-heartbeat frames) and **wake** (`onWake` per successful enqueue); `onWake` reads from `minDeliveredSeq()` (min over live subscribers' cursors) instead of the shared `lastFanoutSeq`, and still skips per-subscriber `seq <= wakeFloor`. `lastFanoutSeq` remains only an emitter-level high-water mark (self-wake idempotence/diagnostics); docs updated accordingly. Lease/fencing/epoch untouched.
- GREEN: `SseChatEmitterWakeFloorTest` 3/3; no duplicate for the fast subscriber in the same test.

F7 (missing coverage) — added:
- `ParkToolStartOrderConformanceTest#resumeAfterParkDoesNotEmitASecondToolStartForTheSameCard`: real `SpToolCallingManager` + real `ConfirmGate` (park on) + real `ResumeService` + real emitter + real `MessageJsonCodec`; parks a two-card batch, marks both `FRONTEND_RESULT`, runs `ResumeService.resume`, asserts `ACCEPTED`, `driveResumed` called, and per card exactly one `tool_start` and one `frontend_tool_request` after the resume (plus no in-flight entries left). Passes on the current code (locks the invariant; no behaviour change was needed).

F2 (controller ruling) — recorded, no behaviour change:
- `SpToolCallingManager` pre-start block now documents: `tool_start` is emitted for every accepted (suspended) tool as an **announcement/scheduled event, not proof of execution**; in a mixed batch, tools ordered after the parked card visibly render `running` for the whole human wait (deliberate; frontend UX not broadened); resume settles them via `resolvePending` without another start; narrowing to gate cards only would recreate the missing-start gap. Frame order remains `suspended → tool_start → request`.

F4 / F5 / F6: deliberately untouched (deferred minor observations); no opportunistic changes.

Verification (IDEA Maven `mvn.cmd -o -B -Dspring.data.redis.port=1`, no clean; no real ports/Redis):
- Backend regression (26 classes, same list as the original evidence plus the two new tests): **Tests run 161, Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS** (`<隔离副本>/_verify-20261025-park-seq/backend-regression.log`).
- Frontend focused (8 specs incl. the new one and parts/gate/stream): **8 files / 100 tests passed** (`frontend-focused.log`); `npm run build` exit 0.
- No CI, three-instance, or @real claims are made for this round; main-tree integration and real gates remain with the controller.
