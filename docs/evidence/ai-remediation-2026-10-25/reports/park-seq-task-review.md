> 来源=<仓外证据>/park-seq-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Task review — park frame ordering + per-subscriber wake floor

- Repository: `<主仓>`
- Base/head: `ae883fbae52be36e45a635ee276cf8298ac24bd2` + uncommitted working tree (7 files)
- Brief: `.superpowers/sdd/punisher-atom-simon-baz/task-park-seq-brief.md`
- Implementer evidence: `.superpowers/sdd/punisher-atom-simon-baz/task-park-seq-report.md`
- Diff: `<仓外证据>/park-seq-review.diff` (read once, 802 lines)
- Provided targeted run: `<仓外证据>/park-seq-targeted.log` — Tests run 159, Failures 0, Errors 0, Skipped 0, BUILD SUCCESS (2026-10-25T16:01:03+08:00); includes `ParkToolStartOrderConformanceTest` (1) and `SseChatEmitterWakeFloorTest` (2).
- Checks I ran myself (targeted only, no broad suite rerun):
  - `frontend: node_modules/.bin/vue-tsc --noEmit` → **5 errors, all in the new spec** (see F1). No other type errors in the project.
  - `frontend: vitest run tests/park-request-tool-start-order.spec.ts` → 4/4 passed (runtime behaviour is correct; only typing is broken).

Verdicts:

- **Spec compliance: PASS with one ambiguity and one partial.** All four functional requirements are implemented as described, but the brief's literal "tool_start before a suspended frame" wording contradicts the repository's established frame contract (suspended first), and requirement 3's "not starved" clause remains only partially covered in a pre-existing lost-wake window.
- **Task quality: NOT ACCEPTABLE AS-IS — one blocking defect (frontend build/typecheck red).** Backend logic is otherwise sound: dedupe is correct, ordering is correct, the per-subscriber floor is updated/read under `emitLock`, and non-park paths are untouched.

---

## Findings

### F1 — Critical (blocking build): new frontend spec fails `vue-tsc`, so `npm run build` fails

`frontend/tests/park-request-tool-start-order.spec.ts:47`

```ts
function toolStartFrame(toolCallId: string, kind = 'FRONTEND') {
```

The default-initialised parameter widens to `kind: string`, and `SseToolStartFrame.kind` is `ToolKind` (`frontend/src/types/sse.ts:21,244-251`). `applyFrameToParts` takes `SseFrame`, so all five call sites are `TS2345`.

Observed (full `vue-tsc --noEmit`, filtered for this file — these are the only 5 errors in the whole project):

```
tests/park-request-tool-start-order.spec.ts(67,32): error TS2345 ... kind: string ...
tests/park-request-tool-start-order.spec.ts(80,32): error TS2345
tests/park-request-tool-start-order.spec.ts(93,32): error TS2345
tests/park-request-tool-start-order.spec.ts(94,32): error TS2345
tests/park-request-tool-start-order.spec.ts(106,32): error TS2345
  Type '{ ...; kind: string; ... }' is not assignable to type 'SseToolStartFrame'.
    Types of property 'kind' are incompatible.
      Type 'string' is not assignable to type 'ToolKind'.
```

Why it blocks: `frontend/package.json` `build` = `vue-tsc --noEmit && node scripts/build.mjs`, and `frontend/tsconfig.json` includes `tests/**/*.ts`. The implementer report claims the frontend test/build gate was "dispatched separately" — no result is present, and the build is in fact red.

Fix is one line: `kind: ToolKind = 'FRONTEND'` (import the type) or annotate the helper's return as `SseToolStartFrame`. Runtime behaviour does not need to change (vitest passes 4/4).

### F2 — Important (behaviour beyond brief scope; needs a decision, not a blocker): pre-loop `tool_start` is emitted for every `suspended` entry, not only for cards whose request frame is published

`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:482-486`

`suspended` contains every pending that was not preflight-blocked/rejected (`SpToolCallingManager.java:389-435`), including plain `BACKEND` read-only tools. In park mode the invocation returns at the first gate `await` (`ConfirmGate.java:622`), so tools ordered after that card **never execute in this invocation**, yet their `tool_start` was already published.

Effect: for a mixed batch (`[backend_a, confirm_b, backend_c]`) with production park on, `backend_c` renders as `running` (`frontend/src/utils/parts.ts:549-556`) for the whole, unbounded human wait, then gets its `tool_result` only after the decision + resume. The frame type's own doc allows `tool_start` as a "挂起前的公告" (`frontend/src/types/sse.ts:243`), so this is not a contract violation — but it is a user-visible state change the brief did not ask for ("an already-emitted **gate request** must publish its tool_start"). The counter-argument is real: `ResumeService` settles decided tools via `resolvePending` and never emits a start, so emitting early is the only way these tools get any start frame at all. Recommend an explicit decision: either limit the pre-emission to `KIND_CONFIRM`/`KIND_FRONTEND` (bit-exact brief scope, restores the old "no start for parked backend tools" gap) or keep it and record the "running during park" semantics.

### F3 — Important (pre-existing, NOT introduced by this patch): the shared emitter-level upper bound can still starve a cursorless subscriber

`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:879-883` (replay advances `lastFanoutSeq` to the archive tail) together with `:1549-1555` (wake returns early when `upperBoundSeq <= from`).

Sequence: instance C has subscriber B attached and caught up to `lastFanoutSeq = 3`; another instance writes 4 and the wake to C is lost (documented as acceptable: "唤醒可丢，正确性由 ?lastSeq 重连兜底", `:1514`); a reattach on C — **even cursorless** — replays through 4 and moves the shared `lastFanoutSeq` to 4; the retried wake for 4 now hits the `upperBoundSeq <= from` early return, and `readFrom(4)` only ever returns >4. B permanently misses seq 4 on that connection.

The new per-subscriber `wakeFloor` fixes the case the brief names for the *wake fan-out* loop (another subscriber's cursor suppressing delivery), and a cursorless subscriber attached sequentially is never skipped (verified by test 2), but the shared floor above still reproduces "another subscriber's replay starves me" whenever a wake is lost. Not a regression (this line is unchanged), but requirement 3 is only partially met. Needs either explicit acceptance ("reconnect-on-gap is the contract") or a follow-up (per-subscriber delivered ledger rather than a shared `lastFanoutSeq` used as the read cursor).

### F4 — Minor: `emitToolStartOnce` re-fetches the emitter instead of using the invocation's `out`

`SpToolCallingManager.java:718-722` (`this.registry.of(runId).toolStart(pending)`) vs `SpToolCallingManager.java:362` (`SseChatEmitter out = this.registry.of(runId)`).

`RunRegistry.recycleIfIdle` can remove the entry concurrently (`AiController.java:835`, gated on zero subscribers + dead-round judgement). If that happens mid-batch, `suspended`/requests/results go to the old emitter while `tool_start` goes to a freshly created one — split identity, late/reordered delivery for any subscriber that attached to the new instance. The same pattern already exists in `ConfirmGate.publishRequestsForParkedBatch` (`ConfirmGate.java:476`), so this is not a new class of bug, but the helper had `out` in hand; passing `out` removes both the split and a per-card map lookup. Also note `toolStarted.add(null)` would silently suppress starts for any null `toolCallId` (defensive paths elsewhere guard for null).

### F5 — Minor: the "single source of truth" park check is still read twice

`ConfirmGate.java:452` (`publishesRequestsAheadOfLoop`) is evaluated at `SpToolCallingManager.java:482`, then re-read inside `publishRequestsForParkedBatch` (`ConfirmGate.java:472-474`) and again in `await` (`ConfirmGate.java:527`). Because `AiProperties` is plain `@ConfigurationProperties` with no actuator/`@RefreshScope` in the tree, the flag cannot change at runtime, so the divergence is unreachable today — but the comment at `ConfirmGate.java:445-451` claims a structural invariant the code does not enforce. If it ever became refreshable, enabled→disabled between the reads yields a duplicate request frame in `await`, and disabled→enabled yields exactly the original G-01 order (request published without the pre-loop start). Passing one decided boolean through both calls would make the claim true.

### F6 — Minor: the frontend guard skips the whole merge, including `args`/`name`

`frontend/src/utils/parts.ts:545-548`

```ts
const pending = findToolPart(message, frame.toolCallId)
if (pending && pending.requiresAction) { break }
```

Dropping the entire `upsertToolPart` (rather than only the `status`/`requiresAction` downgrade) means a late `tool_start` no longer fills `args`/`name` on an existing action part. In the contract order the `suspended` placeholder (`parts.ts:820-835`) has no `args`, so the form schema now arrives only with `frontend_tool_request` (which does carry `args`), so this is not reachable as a defect in the current frame set — and the new test deliberately locks the "idempotent, no rewrite" choice. Flagging only because a merge preserving `requiresAction`/`status` while filling missing `args` would be stricter without changing intent. Also the docstring says "pending + requiresAction" while the code checks only `requiresAction`; today `requiresAction === true` implies `pending`, so the guard is behaviourally safe.

### F7 — Minor: test coverage gaps on behaviours the brief names

- `ParkToolStartOrderConformanceTest` covers only the parked invocation (throws `RunParkedException`, then asserts archive order and exactly-one `tool_start` per card). **"No duplicate `tool_start` on resume" is not asserted anywhere**: `MultiCardParkResumeTest` asserts responses/status only, and no test counts starts across park + resume. It holds by construction (resume settles through `resolvePending`; the only `tool_start` call site is `SseChatEmitter#toolStart`, reached solely via `emitToolStartOnce`), but the brief calls it out as a requirement, so it deserves a lock.
- `SseChatEmitterWakeFloorTest` is sequential (one thread, direct `onWake` calls against a hand-appended archive). It does drive the exact regression (staged 0 with `lastSeq=2` → floor 2) and the "cursorless subscriber not starved" ordering, but not: floor after a non-empty replay (`lastSeq=2` with frames 3,4 already archived at attach), local live fan-out followed by a wake, or two interleaved wakes. The concurrency safety argument rests on `emitLock` coverage (`emit`, `onWake`, `stageReplay` all take it), read from code, not proven by test.
- No test covers park-enabled ordering for frontend-rejected / preflight-blocked entries (they legitimately get `tool_start` only in the loop) or for the mixed backend + gate batch that produces F2.
- Frontend tests assert the projection (`deriveProjections` → `pendingCall`), not the GF submit control in `AiPanel`; consistent with existing `parts-*` specs, acceptable.

---

## Spec compliance detail

| Brief requirement | Result |
|---|---|
| Already-emitted gate request publishes `tool_start` before the request; no duplicate on resume; rejection + ordinary streaming order preserved | Met for gate cards: order is `suspended → tool_start* → request*` (`SpToolCallingManager.java:475-491`), matching `ToolFrameSequenceConformanceTest.java:69,162`. Dedupe via `toolStarted` (`:393,718-722`) covers all five loop branches; resume cannot re-emit (no other call site). Rejection/preflight branches unchanged. Non-park path byte-identical in behaviour (set stays empty). |
| `requiresAction=true` part must not be downgraded by a late `tool_start`; other merge semantics unchanged | Met (`parts.ts:539-557`); other branches untouched; 4/4 vitest pass. See F6 for the (intentional) loss of the `args` refresh. |
| `?lastSeq=N` reattach must not receive wake replay of seq ≤ N; cursorless subscriber must not be starved by another's cursor; persisted replay/live order contracts retained | Mostly met: per-subscriber `wakeFloor` initialised at `SseChatEmitter.java:666`, raised in `stageReplay` (`:880`) and per successful wake enqueue (`:1588`), read under `emitLock` (`:1580`); replay filtering (`isOrphan`) and live FIFO ordering untouched. Partial in the lost-wake window — see F3. |
| No blocking wait thread restored; no session affinity / provider-specific behaviour; no secrets, topology, ports 8081/5202 | Met — none of these appear in the diff; park still throws `RunParkedException` (`ConfirmGate.java:622`). |
| Deterministic tests for suspend/tool_start order, subscriber wake floors, frontend pending-card regression | Backend tests deterministic and passing (159/159 supplied log). Frontend regression passes at runtime but **breaks the build** (F1). Resume duplicate-start not covered (F7). |

### Ambiguity in the brief (wording vs repository contract)

The brief says "an already-emitted gate request must publish its `tool_start` **before a suspended frame**". The repository contract, the existing conformance suite and the new test all put `suspended` first and `tool_start` second (`ToolFrameSequenceConformanceTest.java:69,162`; new test asserts `suspended < firstStart`). I treated the repository contract as authoritative, so the patch is compliant. If the brief is meant literally, the patch and the existing conformance test both need to change — that needs an owner decision, not an implementer guess.

## Evidence gaps (not treated as absence)

- Frontend full `vitest run` and the ordinary `npm run build` result for the integration tree are not in the provided artifacts. I did not rerun the full frontend suite; I ran the single new spec (4/4 pass) and the project typecheck (5 errors, F1).
- No backend re-run was performed (the supplied 159/159 log was used as-is).
- No production multi-instance (three-instance streaming-kill) or `@real` model evidence exists for this patch, as the implementer report itself states. Nothing here proves the original intermittent symptom is fixed in production; the unit tests only pin the two deterministic defects (archive frame order, per-subscriber wake floor).
