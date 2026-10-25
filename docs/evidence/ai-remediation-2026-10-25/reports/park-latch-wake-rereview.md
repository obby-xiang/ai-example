> 来源=<仓外证据>/park-latch-wake-rereview.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Re-review — N1 self-wake fix + H-01 park-exit latch patch

- Repo (both patches applied): `<主仓>`
- Part 1 inputs: `<仓外证据>/wake-self-fix.patch`, `<仓外证据>/wake-self-fix-report.md`; original finding N1 in `<仓外证据>/park-seq-rereview1.md:45-64`
- Part 2 inputs: `<验证副本>/_verify-20261025-park-seq/latch-fix.patch`, `<仓外证据>/park-latch-fix-report.md`, root evidence `<仓外证据>/main-ci-cancel-analysis.md`
- Read-only throughout; no broad suites rerun. Global date: 2026-10-25.

## Verification I ran myself (targeted, on current main)

```
mvn -o -B -Dspring.data.redis.port=1 -Dtest='ConfirmGateParkExitRaceTest,
  AiControllerEventsParkCloseTest,SseChatEmitterCrossInstanceWakeTest,
  SseChatEmitterWakeFloorTest' test
→ Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  ConfirmGateParkExitRaceTest 3/3 · AiControllerEventsParkCloseTest 2/2
  SseChatEmitterCrossInstanceWakeTest 9/9 (the round-1 RED class) · SseChatEmitterWakeFloorTest 3/3 (F3)
```

Cross-checks of the implementers' RED evidence: `_verify.../latch-red2.log:77-96` shows `ConfirmGateParkExitRaceTest` failing `drivers expected 1 but was 0` on pre-patch code with the park/branch log shape; `latch-regression.log:935` shows `Tests run: 177 … BUILD SUCCESS`. Both patches are present in main (`SseChatEmitter.java:1511`, `ConfirmGate.java:612,778`, `AiController.java:776`).

---

## Part 1 — N1 (synchronous self-wake duplicate): **ADDRESSED**

Fix location: `backend/.../run/SseChatEmitter.java:1511-1518` — the local fan-out now skips a frame whose seq is already at/below the subscriber's delivered cursor:

```java
if (liveSeq > 0L && liveSeq <= sub.wakeFloor) { continue; }   // :1511
```

Sub-checks requested:

1. **RED → GREEN on the existing guard.** `SseChatEmitterCrossInstanceWakeTest.selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame` (was `[1L, 1L]` in round 1) now passes; full class 9/9 on main (my run above). This is the same TDD vehicle, no test weakening — the test body is unchanged, only its javadoc/kill note now names the real gate (patch lines 70-83).
2. **Enqueue-failure cursor semantics.** `wakeFloor` is written in exactly three places and only after a frame is actually queued/delivered: replay `:881-882` (frames go into the unbounded `sub.queue` directly, no capacity failure), local fan-out `:1526` (after `enqueueLive` succeeds, `:1520`), wake `:1613` (after `enqueueLive` succeeds, `:1608`). The new skip at `:1511` runs **before** `enqueueLive`, so it can only fire when a nested `onWake` already delivered this seq (or the subscriber's own `?lastSeq` cursor is ahead — same rule `onWake` applies at `:1605`). If the nested wake's `enqueueLive` fails, the subscriber is `evict`-ed (`:1610`) → `closed` → the outer fan-out skips it at the `sub.closed` check (`:1506-1509`). No path marks an undelivered frame as delivered.
3. **No loss of F3 behaviour.** `onWake` still derives its read floor from `minDeliveredSeq()` (`:1571`), not `lastFanoutSeq`; `stageReplay` untouched except docs. `SseChatEmitterWakeFloorTest` 3/3 on main, including the lost-wake/other-replay case.
4. **Ordering / FIFO / heartbeats.** The nested wake enqueues the frame once, at the head-order position it already owns; the outer fan-out would have appended the same seq after it. Per-subscriber queue order stays archive order. `liveSeq > 0L` keeps heartbeats (no business seq) broadcast to everyone.
5. `lastFanoutSeq` is now genuinely unused except as a diagnostic high-water mark; the field javadoc (`:256-271`) and the pre-`publishWake` comment (`:1492-1495`) were rewritten to say so, so no false invariant remains. (Dead-field cleanup is explicitly left as follow-up — acceptable.)

Accepted consequence (not a defect): a client that sends an absurd `?lastSeq` larger than the seq space now also stops receiving *local* live frames ≤ that cursor (previously only wake frames were suppressed). That is the same self-inflicted rule the wake path already documented ("a bogus lastSeq only makes that subscriber receive less") and is reported as a deliberate choice (report lines 53-55).

**No new Critical/Important issue in this patch.**

---

## Part 2 — H-01 park-exit atomic lifecycle: **PASS on the named risks, with one new Important error-path issue (I1 below)**

### Exactly one driver — decision before / during / after the park transition: PASS

Handshake (all on the same `submitLock`): decision side writes the pending entry **and** signals inside the lock (`ConfirmGate.java:1260` for confirm, `:1581` for frontend result); the park exit reads + releases lease + clears latches inside the lock (`:778-790`). Because the pending write and the exit's re-read are serialized, only two outcomes exist:

- exit reads **resolved** → returns it (`:779-782`), caller takes over driving (`await` returns `fresh`, mirroring the existing decision branch at `:675-681`). Decision side had `woke=true` → `maybeTriggerResume` skips. Driver count = 1 (in-thread).
- exit reads **unresolved** → lease released, then all run latches removed (`:786-788`) → subsequent `signal` must miss → decision side `woke=false` → `triggerResume` dispatch = 1. Driver count = 1 (resume).

The `tryRecordDecision` guard is written outside `submitLock`, but the guard does not change `pendingLenient`'s view (`:779` reads the raw entry, by design — cf. the comment at `:1239`), so the interleaving "guard written, `putPending` not yet" is read as unresolved → clear + park → the decision's later signal misses → dispatch. Still exactly one. The observed case "resolved but not yet executed" (APPROVED / FRONTEND_RESULT) counts as resolved (`PendingToolCall.java:227-229`), so the window case really does take over. Verified by test `aDecisionLandingInsideTheParkExitWindowStillGetsExactlyOneDriver` (3/3, main), whose RED shows `drivers=0` pre-fix.

### submitLock ordering / lease ordering: PASS

Release-lease-then-clear-latches is inside the lock as documented (`:786-788`), so no decision can observe "latches gone but lease still mine", and the reverse hazard (decision re-entering the lease with the same owner and then the park thread deleting that lease) is removed. Cost: one Redis RTT (`releaseWaitingLease`) under `submitLock` on the park exit path — latency, not correctness.

### Latch cleanup: PASS

`clearWaiterLatches` (`:796-809`) removes every `runId|` latch and its `armedKeys` entry, weakly-consistent over `ConcurrentHashMap` (`:194,197`). The manager's later `disarm(runId, gateWaitIds)` is idempotent (`:421-443`, guarded by `armedKeys.contains`). The take-over branch deliberately leaves the latches registered; I checked that this is not a leak but the in-process "someone is driving" marker for the SUSPENDED window: they are removed by `disarm` in `SpToolCallingManager.executeToolCalls`'s finally, and `settle()` (`SpToolCallingManager.java:1902-1912`) flips the snapshot to RUNNING before that, so the two markers hand over with no gap (relevant to the self-heal below).

### Cancel handle: PARTIAL (pre-existing gap, not a regression)

The two new early exits now close it (`:617`, `:625`) and the normal `finally` reuses the same helper (`:745-757`). However the park branch's `cancelState == CANCELLED` early return at `:602-604` still returns before any `closeCancelHandle`, so the `cancellations.onCancel` listener is not deregistered on that path. That is the pre-patch shape (the patch narrowed, not widened, the leak) — flagged only because the checklist names the cancel handle; not a blocker and not caused by this round.

### Reattach self-heal idempotency: PASS (with an evidence caveat)

New call: `AiController.events`, settled-SUSPENDED branch → `confirmGate.triggerResumeForParked(runId)` (`AiController.java:776`), after the subscription is attached (`:759`) and only when no PENDING entry exists (`stillWaitingForInput`, `:791-804`; read failure → treated as still-waiting → old close behaviour).

Why it cannot double-drive in the analysed cases:
- during the take-over execution the snapshot is still SUSPENDED, but the leftover latch makes `triggerResume` return `ACTIVE_LATCH_IN_PROCESS` (`:1684-1687`); after `settle` the status is RUNNING → `SNAPSHOT_NOT_SUSPENDED` (`:1688-1691`);
- an in-flight legit resume in the same process holds the lease (reentrant acquire gives `created=false`, never a steal) and holds `ResumeService.resuming` for the whole drive (`ResumeService.java:196,632,1002/1030`) → `IN_PROGRESS`;
- another instance → lease `LEASE_HELD_ELSEWHERE` + one bounded retry;
- real waiters on any card → `hasActiveLatch` true → skipped (and the PENDING branch does not call the heal at all).

Tests: `AiControllerEventsParkCloseTest` asserts heal-called for settled and `never()` for PENDING (2/2, main). Caveat already stated in the report §6.3: this is a mocked-`ConfirmGate` assertion, not an end-to-end controller+gate+resume wiring test; no real-instance evidence exists for it.

### PENDING close behaviour: PASS

Unchanged branch (`completeSubscriptions()` only when a PENDING entry exists); test asserts `subscriberCount()==0` and no heal. Read failure is conservative (`stillWaitingForInput` → true).

### Non-park compatibility: PASS

`switchParkedLifecycle` and the controller heal are both gated on `properties.getPark().isEnabled()` (`:601`, `AiController.java:766`); the non-park wait loop, its `finally` lease/cancel cleanup (`:743-745`), and the blocking semantics are unchanged. `withoutParkTheWaitingThreadKeepsDrivingAndNoResumeIsDispatched` passes.

---

## New issues

### I1 — Important (new, error path): unguarded heal call after the emitter is attached can leak a subscriber

`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:776`

`triggerResumeForParked(runId)` runs on the request thread **after** `out.replayAndAttach(emitter, …)` at `:759` has already registered the `Subscription` in the emitter. `triggerResume` performs `this.store.get(runId)` (`ConfirmGate.java:1688`), and `RunStore.get` **throws** `AiStateUnavailableException` on Redis failure (`RunStore.java:991-998`). The `events` handler has no try/catch around this call (nor a handler that can complete a never-returned emitter), so on such a failure:
- Spring answers 500 and the `SseEmitter` is never returned to the container, so its `onCompletion/onTimeout/onError` callbacks (`SseChatEmitter.attachSubscription`) can never fire;
- the subscription therefore stays in `subscribers` for the process lifetime → `hasLiveSubscriber(runId)` stays true, `RunRegistry.recycleIfIdle` refuses (`RunRegistry.java:180-186`), and every later wake/replay for that run keeps queueing into a dead subscriber.

Blast radius is bounded to that run (the session gate is released when the turn ends), but it is a permanent leak and a wrong "alive" signal until restart. Before this patch there was no throwing call between `replayAndAttach` and `return` (`stillWaitingForInput` catches; `completeSubscriptions` is exception-safe). Suggested minimal fix: wrap the heal in `try/catch (RuntimeException)` and log at debug/warn, mirroring `stillWaitingForInput`; optionally `out.completeSubscriptions()` on failure so the connection does not hang.

Not a concurrency race and not a blocker for exercising the E2E gate, but it is a new failure mode introduced by this patch and cheap to close.

### Observation (pre-existing, not introduced): cancel-listener leak on the CANCELLED-in-park exit

`ConfirmGate.java:602-604` returns before `closeCancelHandle` (only at `:617`/`:625`/`:745`). Pre-patch shape; the patch narrowed the leak set but did not close this one. No action requested this round.

## Non-findings I specifically checked and cleared

- Take-over vs. `out.completeSubscriptions()`: a reattached client still receives subsequent live frames because `completeSubscriptions()` sets only per-subscription `terminating` (`SseChatEmitter.java:2059-2074`, `completeWhenDrained:2083-2086`), not the emitter-level `terminating` (`:343`, set only by `complete()`), so the H-01 client that reattaches ~0.5 s after the decision still gets `frontend_tool_result`/`done`.
- `clearWaiterLatches` removing pre-registered latches of the same batch: intended (those cards will never be awaited in this invocation) and idempotent with `disarm`.
- `triggerResumeForParked` on cancellation path (`AiController.java:886`) is pre-existing and its throw cannot leak a subscriber (JSON endpoint).
- The heal's one-shot lease retry on `LEASE_HELD_ELSEWHERE` cannot self-perpetuate (`triggerResume(..., allowRetry=false)` inside the retry).

## Overall verdicts

- **N1 fix: ADDRESSED** — red guard now green on main, per-subscriber cursor only advances on successful delivery, F3 preserved, no new Critical/Important issue.
- **H-01 patch: PASS on all named risks** (exactly one driver across before/during/after, submitLock ordering, latch cleanup, self-heal idempotency, PENDING close, non-park compatibility); **one new Important error-path issue (I1)** — an exception from the new heal call after the subscription is attached leaks that subscriber permanently. Recommend closing I1 (a try/catch) before the real-instance/E2E gate, or explicitly accepting it; nothing in this round blocks running the real three-instance/@real gate, and no evidence is claimed here for it.
- Evidence caveats: 5/5 focus and 177/177 are the implementers' logs (I re-verified 17 of those tests on main, not the full lists); the self-heal has no end-to-end wiring test; no CI/three-instance/@real result exists for either patch yet.
