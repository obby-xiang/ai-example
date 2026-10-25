> 来源=<仓外证据>/wake-self-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# N1 fix — synchronous self-wake duplicates the frame to a live subscriber

- **Status: DONE** (one regression fixed, RED→GREEN, targeted regression green)
- Finding fixed: **N1** only, from `<仓外证据>/park-seq-rereview1.md:45-64`
- Workspace: isolated backend `<验证副本>/_verify-20261025-wake-self/backend` (copy of main's working tree after park round 1)
- Patch: `<仓外证据>/wake-self-fix.patch` (sha256 `2b91510bee37aca4a2ad57b6e15df2a56a337f544bdd7b5a354fb438c9ed97ba`, 6,022 bytes)
- Main repo `<主仓>` **not modified** (patch verified with `git apply --check` only)

## Root cause

`emit()` registers the emitter high-water mark and calls `publishWake(seq)` **before** the local fan-out loop
(`SseChatEmitter.java:1491-1498`), while the local fan-out advanced each subscriber's cursor only afterwards
(`:1517-1520`). After the F3 fix, `onWake` takes its read floor from `minDeliveredSeq()` (`:1564`), not from
`lastFanoutSeq`. With a synchronous wake bus (the repo's `FakeWakeBus`, and any in-process dispatch the class
documents as supported), the wake callback re-entered `onWake` *on the publishing thread*, saw the subscriber
cursor still at `seq-1`, read the just-appended frame from the `FrameStore`, enqueued it and advanced the cursor;
`emit()` then enqueued the same payload a second time in its fan-out loop, which had no cursor check.

Production (`RedisFrameWakeBus`, listener thread) was already safe because the callback blocks on `emitLock`
until the fan-out finished — the guard matters for the synchronous/in-process dispatch mode, which the class
documentation treats as the stricter, supported ordering.

## Fix — one condition in the local fan-out (no cursor moved earlier)

`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java` (`emit()`, fan-out loop):

```java
if (sub.closed) { ... continue; }
if (liveSeq > 0L && liveSeq <= sub.wakeFloor) {
    // 自唤醒（同步总线 / 进程内分发）已经把这一帧投给本订阅者：publishWake 在本扇出
    // 之前，回调在 emit 内部重入 onWake，从归档补的正是刚落的这一帧并推进了游标。
    // 游标只在成功入队之后推进（onWake 与下面分支同一口径），故"游标已过该号"
    // 等价于"这一帧已交付"；此处再投就会让订阅者收到同一个 seq 两次。
    continue;
}
if (!enqueueLive(sub, payload)) { ... }
```

Why this and not the alternatives the re-review sketched:

- **Not "advance the cursors before `publishWake`"**: that would advance a cursor before any successful delivery
  and would leave an overflow-evicted frame counted as delivered. The task's constraint (cursor only on actual
  delivery) forbids it. Here `sub.wakeFloor` is still written in exactly two places — `onWake` after a successful
  `enqueueLive` (`:1606`) and the fan-out after a successful `enqueueLive` (`:1524`) — both unchanged.
- **Not restoring `lastFanoutSeq` as the read floor**: `onWake` still reads `minDeliveredSeq()`, so F3
  (a lost wake is still delivered to a slower subscriber after another subscriber's replay advanced the shared
  bound) is preserved; `SseChatEmitterWakeFloorTest` is green.
- The local fan-out now applies **the same per-subscriber rule `onWake` already applies** (`seq <= sub.wakeFloor`
  ⇒ skip), which makes "本地扇出 + 自唤醒" idempotent by the same token that makes repeated wakes idempotent.
- `liveSeq > 0L` keeps heartbeats (seq-less, never archived) broadcast to every subscriber, unchanged.
- Async cross-instance ordering is untouched: `onWake` still runs entirely under `emitLock`, so wake-driven
  frames cannot interleave with a fan-out; per-subscriber FIFO / seq monotonicity is unchanged.
- The skip can only fire inside `emitLock` when (a) a nested synchronous `onWake` already delivered this seq (or a
  higher one), or (b) the subscriber's own cursor is ahead from replay/`?lastSeq` — in case (b) `onWake` would
  skip that same frame too, matching the documented "a bogus `lastSeq` only makes that subscriber receive less".

## Changed files (2, both in the backend module)

| File | Change |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java` | +6 / −5 code: the `liveSeq <= sub.wakeFloor` skip in the fan-out (`emit`). Rewrote two now-false comments: the `lastFanoutSeq` field javadoc (`:256-271`) and the pre-`publishWake` note in `emit` (`:1492-1495`). |
| `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterCrossInstanceWakeTest.java` | Comments only (test bodies unchanged): javadoc of `selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame` (`:109-118`) and the kill note of `repeatedWakeIsIdempotent` (`:62`) no longer claim `lastFanoutSeq` is the dedupe gate; both now name the real gate (per-subscriber `wakeFloor`) and the new kill criterion. |

No production file other than `SseChatEmitter.java` is touched. `ConfirmGate`, `AiController`, `ResumeService`, the
lease/latch code and all other files are byte-identical to main.

## RED → GREEN evidence

Maven used throughout:
`"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -B -Dspring.data.redis.port=1 -Dtest=... test`
(no `clean`, Redis deliberately unreachable on port 1; cwd = `<验证副本>/_verify-20261025-wake-self/backend`)

| Step | Command | Result | Log |
|---|---|---|---|
| RED (pre-fix file, main's copy) | `-Dtest=SseChatEmitterCrossInstanceWakeTest` | **Tests run 9, Failures 1** — `selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame:127`, `Expecting actual: [1L, 1L] to contain exactly (and in same order): [1L]` — BUILD FAILURE (exit 1) | `<仓外证据>/wake-self-red.log` |
| GREEN (with fix) | same class + `SseChatEmitterWakeFloorTest` inside the 16-class run below | `SseChatEmitterCrossInstanceWakeTest` 9/9, `SseChatEmitterWakeFloorTest` 3/3 — BUILD SUCCESS | `<仓外证据>/wake-self-green.log` |

The RED run reproduced the review's exact failure text (9 run / 1 failure / `[1L, 1L]`), on an unmodified copy of
main's `SseChatEmitter.java`.

## Regression runs (targeted, on the fixed file)

**SSE frame / delivery family — 120/120, BUILD SUCCESS** (`wake-self-green.log`):

| Class | Tests |
|---|---|
| `SseChatEmitterCrossInstanceWakeTest` | 9 |
| `SseChatEmitterWakeFloorTest` | 3 |
| `SseChatEmitterDeliveryTest` | 18 |
| `SseChatEmitterFramesTest` | 16 |
| `SseChatEmitterFrameStoreTest` | 9 |
| `SseChatEmitterBusinessActivityTest` | 9 |
| `SseChatEmitterWriteBudgetTest` | 7 |
| `SseChatEmitterReasoningFramesTest` | 4 |
| `SseChatEmitterWriteTimeoutMetricTest` | 3 |
| `SseChatEmitterDrainHandoffTest` | 2 |
| `SseChatEmitterAuthorityFailureTest` | 1 |
| `FrameWakeListenerTest` | 5 |
| `ReattachLastSeqConformanceTest` | 10 |
| `SseFrameSequenceConformanceTest` | 7 |
| `DeltaFrameSplitTest` | 10 |
| `DeltaFrameSplitLimitGuardTest` | 7 |

**Conformance / gate contracts that exercise this fan-out — 66/66, BUILD SUCCESS**
(`wake-self-green-conformance.log`): `ChatTurnFrameSequenceConformanceTest` 12, `FrameContractFalsificationTest` 22,
`FrameTypeSetFrozenTest` 2, `ParkToolStartOrderConformanceTest` 2, `SessionBusyFrameIsolationConformanceTest` 3,
`ThreeArgAssemblyConformanceTest` 5, `ToolFrameSequenceConformanceTest` 11, `SuspendDisconnectGateTest` 3,
`ToolPreflightGateTest` 6.

Environment note: `FrameTypeSetFrozenTest` reads the repo-relative `../frontend/src/types/sse.ts`; the isolated
copy only contains `backend/`, so I copied that single read-only fixture
(`main/frontend/src/types/sse.ts` → `_verify-20261025-wake-self/frontend/src/types/sse.ts`) into the scratch tree.
Without it the class errors with `NoSuchFileException` — environmental, unrelated to this change (no frame type
was touched).

## Patch

`<仓外证据>/wake-self-fix.patch` — two `diff -u` sections with `a/`…`b/` repo-relative labels
(EOL-normalized to LF, matching main's `core.autocrlf=true`; both files are CRLF in the worktree).

```
cd <repo root>
git apply -p1 <仓外证据>/wake-self-fix.patch
```

Verified against current main: `git -C <主仓> apply --check -p1 -v
<仓外证据>/wake-self-fix.patch` → `Checking patch backend/.../SseChatEmitter.java... Checking patch
backend/.../SseChatEmitterCrossInstanceWakeTest.java...` exit 0. Round-trip verified: applying the patch to main's
pre-image (`patch -p1`) reproduces the fixed files byte-for-byte (after EOL normalization).

## Deliberately not done / notes

- `lastFanoutSeq` is still written (3 sites) and now has no reader. I kept it (minimal change; task said do not
  restore it as the read floor) and rewrote its javadoc to say plainly that it is a diagnostics-only emitter
  high-water mark. Removing the field is a follow-up cleanup, not part of this regression fix.
- No new tests were added: the existing guard `SseChatEmitterCrossInstanceWakeTest.selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame`
  was the TDD vehicle (RED → GREEN), and it is the right lock — it fails again if the new skip is removed, if
  `onWake` advances a cursor before enqueue, or if the local fan-out stops consulting the cursor.
- Process note carried over from the re-review: `SseChatEmitterCrossInstanceWakeTest` must be in the round's
  regression class list (it was missing from round 1's 161-test list, which is why N1 escaped).
- Production-readability note for the review: with `RedisFrameWakeBus` the duplicate never occurred (listener
  thread blocks on `emitLock`), so this is a correctness fix for the synchronous/in-process dispatch mode and for
  the documented "no client-side duplicate detection" property; there is no behaviour change for the async path.
- Untouched as instructed: `ConfirmGate`, `AiController`, `ResumeService`, lease/latch work (agent-73), and
  unrelated review findings.
