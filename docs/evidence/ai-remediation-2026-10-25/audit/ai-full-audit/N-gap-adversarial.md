> 来源=<仓外证据>/ai-full-audit/N-gap-adversarial.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
I have what I need. One constraint to flag up front: this role has no write tool, so `N-gap-adversarial.md` could not be created; the full file content is below for the caller to persist verbatim.

---

# N — Gap-adversarial verification (G1–G5 top findings)

**Snapshot:** `<仓外证据>/ai-full-audit/tree` (base `ae883fb` + `working.patch`, working tree dirty — all line numbers are the working tree).
**Method:** read-only; every top claim re-derived from production code and its callers/tests. **No build and no test was executed** — every verdict below is static. Where a claim depends on runtime interleaving I say so.
**File note:** I have no write tool; `<仓外证据>/ai-full-audit/N-gap-adversarial.md` was **not** written. Content below is the intended file.

## 1. Corrections to the five gap reports

| # | Claim in gap report | Verdict | Evidence |
|---|---|---|---|
| C1 | G1-12-4: "业务异常→信封这条分支在本组无证据" (implies uncovered) | **FALSE globally**, true only for the assigned 25 files | `backend/src/test/java/com/example/configmgr/ai/conformance/ToolFrameSequenceConformanceTest.java:599` asserts exactly `outcome=failed` + `"工具执行失败：…任务已终态"`; `tools/AiToolsInvocationTest.java:214` captures the ok=false path. Do not list this as an uncovered branch. |
| C2 | G1-03 "S2 fail-open, four dimensions at once" | **PARTLY / latent** | Defaults are real (`ai/tool/ToolRegistry.java:317-327, 371-376, 407-436`) but explicitly documented as intent ("元数据缺失不是越权证据"), all 37 `@Tool` methods are declared directly on `AiTools`/`KbTools` (34+3, verified by count), and both registration failure *and* tool-bean creation failure abort startup (`ToolRegistry.java:119-140`). No current trigger, no proven asymmetry. Keep as a "missing startup invariant" note, not S2. |
| C3 | G2 F7 "two instances compacting the same session → last writer wins" | **Overstated** | `ai/session/SessionGate.java:80-96` is a Redis single-key CAS lock per session; only one run drives a session at a time and compaction happens inside the turn/resume. The RMW-without-CAS *is* real (`ai/run/RunStore.java:2715-2720`) but needs a gate-TTL lapse or takeover. Down-grade to Low-Med conditional. |
| C4 | G3 D-2 / D-3 (no durable audit, `actor=local-user`) | **Out of defect scope** | Documented demo decisions (`ai/run/SecurityEvents.java` javadoc "scope=process"; `AiConfigWriter.java:730-738`). Report as accepted boundary, not as defects. |
| C5 | G4 #9/#10 feedback IDOR / global delete | **Out of the declared boundary** | Single-user, no-auth app (`AiController.java:1931` comment). What survives is robustness only: no length check on `messageId` (schema 64) → 500; `findByMessageId→save` not atomic → 500 on double submit; `rating=0` deletes by messageId alone (`AiController.java:1948-1952`). Low. |
| C6 | G5 U-22 "reattached client waits forever" | **Mechanism CONFIRMED, user impact overstated** | The stopping tab converges locally and deliberately does not wait for `done` (`frontend/src/stores/ai.ts:4383-4403`: "stop 不走 done 帧"). The real break is protocol-level: no terminal frame is ever archived for a parked-cancel (§2 V-13). |
| C7 | G2 F2 "MD-03 is structural, the unknown-limit degradation is unreachable" | **Half right** | `limit-tokens` default 128000 (`application.yml:168`, `AiProperties.java:1341`) and `configured()` returns it whenever a model row has no `max_context` (`ContextLimitResolver.java:121-125`), so "unknown" is unreachable **by default** — but it *is* reachable by setting 0, which is documented. This is a display-default issue, not a dead branch. |
| C8 | G1-02 severity S2, G1-01 severity S3, G4 #6 severity High | **All over-rated** | See V-9, V-8, V-4: none of them corrupts what the model or the user actually reads in the current build. |
| C9 | G3 D-1 effect "permanently blocked" | **Slightly overstated** | Blocked until `allow-private-endpoints=true` or the row is re-seeded; also only affects private/loopback `baseUrl`. Direction is fail-closed (probe denied), not SSRF. |
| C10 | G1-12-6 "MIN_DECLARATIONS=26 vs 37" | **CONFIRMED** (numbers are right: 37 `@Tool` = 34 `AiTools` + 3 `KbTools`; 37–38 `@ToolScope`) | `tools/ToolScopeTagSpaceGuardTest.java:46,75`. ~11 scope losses stay green. |

## 2. Confirmed defects, ranked (path:line, trigger, effect)

**V-1 — Session-memory key alias: a crafted `sessionId` reads and deletes another session's memory. CONFIRMED. Highest.**
`ai/memory/RedisChatMemoryRepository.java:187-188` (tagged key escapes), `:202-204` (legacy key does **not** escape), `:281-291` (read falls back to legacy), `:314,338` (`saveAll` deletes legacy), `:350-353` (`delete` deletes legacy). `key("x") == "chat:mem:{x}" == legacyKey("{x}")`.
Trigger: `POST /api/ai/chat`, `GET/DELETE /api/ai/history/{id}` with `sessionId="{x}"`; the only validation is non-blank (`ai/web/AiController.java:313`, `:1251-1256`).
Effect: read session `x`'s memory; **one message to `{x}` silently deletes session `x`'s memory** (`deleteLegacyQuietly`); `DELETE` of `{x}` destroys `x`.
Guards/tests: none. `RedisChatMemoryRepositoryDeleteTest` uses plain ids. Mitigation that lowers grade: the UI only generates UUIDs (`frontend/src/stores/ai.ts:201-211`), so this is API-level; in a no-auth single-user demo the surviving harm is destructive aliasing between two user-chosen ids. Severity: **High (data destruction) / Medium inside the demo boundary**.

**V-2 — Editing a seeded provider revokes its probe trust permanently. CONFIRMED.**
`ai/config/AiConfigWriter.java:329-331` (unconditional `source=DB`), `:292-294` (URL gate skipped when `baseUrl` unchanged), `ai/web/AiConfigController.java:152` and `ai/config/AiModelCatalog.java:80` (`trusted = source==ENV_SEED`), `ai/config/AiEndpointGuard.java:95` (`|| trusted`).
Trigger: any edit (display name / key / sort order) of an env-seeded provider whose `baseUrl` is loopback/private — the documented CI/E2E stub path.
Effect: `/providers/{id}/test` and `/models/fetch` return `BLOCKED_PRIVATE_ENDPOINT` although the address never changed; `AiConfigSeeder.writeSeed` only rewrites `ENV_SEED` rows, so it never self-heals. Fail-closed, no SSRF. No test edits-then-probes (`AiConfigWriteEndpointTest` covers only the untouched row). Severity **Medium-High** for any private-endpoint deployment.

**V-3 — A legal-but-empty KB manifest wipes the retrieval surface and reports ready. CONFIRMED.**
`ai/kb/KbManifest.java:37-46` (javadoc: "docs 为空…视为源不可用") vs `:48-61` (`validate()` has no empty check); `ai/kb/KbService.java:250` (validate passes), `:274-289` (builds `nextDocs` empty → new snapshot, `lastError=""`), `:745-757` (`status.ready=true, docCount=0`); `ai/kb/KbSampleSeeder.java:181-183` (existing `manifest.json` ⇒ never seeds).
Trigger: a publish that produces `{"docs":[]}` (empty dir + manifest, or a truncated upstream manifest).
Effect: old index retired, empty index served, `search` returns `no_match` with no suggestions, `/health/kb` and the tool report "ready". This is the one fail-empty path in an otherwise fail-open design. Severity **Medium**.

**V-4 — `@PreDestroy stop()` leaves a retired snapshot installed: `acquire()` busy-spins forever; status still says ready. CONFIRMED (reachability narrow).**
`ai/kb/KbService.java:172-186` (sets `stopped`, retires, never nulls `this.snapshot`; `stopped` is written and never read — `:131,173`), `:792-800` (loop re-reads the same retired snapshot), `:903-910` (`tryAcquire` always false after retire).
Trigger: any KB tool/status call that races bean destruction (in-flight AI turn during shutdown).
Effect: 100% CPU spin, no exit; `status()`/`catalog()` still return the retired snapshot, so readiness lies after shutdown. No test. Severity **Low** in normal Spring shutdown (web server drained first), **High** if a shutdown-time tool call happens.

**V-5 — Form attachments are consumed/deleted *before* the decision claim, and a multi-attachment submission that fails halfway loses every already-consumed file. CONFIRMED.**
`ai/gate/ConfirmGate.java:1465` (`inspectResult` → guard consumes) runs **before** `:1474` (`tryRecordDecision` claim). `ai/form/GenerativeFormGuard.java:133-139` (metadata read), `:228-240` (`consume`; returns false on the first already-consumed id, no rollback), `:253-263` (physical file deleted per consumed item).
Trigger: (a) two attachment fields where the second is expired/consumed; (b) two tabs submitting the same card — the loser has already consumed and deleted files, then is discarded by `convergeOnDecided`.
Effect: the whole submission is rejected with "请重新上传" while the already-consumed files are gone → user must re-upload all attachments; the winning submission may carry different values. Tests cover only single-attachment cases. Severity **Medium** (asset loss + retry, no silent wrong data).

**V-6 — Cancel-mid-batch writes tool responses that bypass the envelope, the neutralizer and the size budget. CONFIRMED.**
`ai/run/ResilientChatService.java:1164-1173` uses `ToolResultEnvelope.of(...).toJson()`; `toJson()` has no `TOOL_RESULT_NOTICE`, no `<tool_result>` shell, no `neutralizeTagEdges`, no limiter (`ai/tool/ToolResultEnvelope.java:176-188` vs `toModelJson`), and the result text is the **full** `item.getResultText()` of an already-executed tool. `:1279` → `convergeMemory` (`:1414-1448`) persists it into `chat:mem`.
Trigger: user stops while a tool in the same batch has finished (result in the pending entry).
Effect: next turn's prompt carries an unbounded, un-delimited, un-neutralized tool result — the only line of defence on the tool path ("闭标签中和 + 外壳") is skipped, and a large result bypasses the 20000-char budget. Severity **Medium**.

**V-7 — The synthetic wrap-up instruction is persisted in the user channel and replayed. CONFIRMED.**
`ai/gate/SpToolCallingManager.java:902` appends `TURN_BUDGET_WRAP_UP` as a `UserMessage`; `ai/run/ResilientChatService.java:1420-1422` filters **SYSTEM only**, `:1446-1448` writes it to memory. `ai/history/AiChatHistoryRecorder.java:177-186` filters it only from the *user-visible* history.
Trigger: any turn that hits the in-turn budget/step ceiling.
Effect: a later, healthy turn receives "请不要再次调用工具…" as a user instruction (a higher-trust channel than the tool data it distilled), can refuse tools, and `ConversationTurns` counts it as a turn start; it also names a deprecated config key. Doc/prompt mismatch, model-dependent. Severity **Medium**.

**V-8 — Cancel path aside, the two `returned` numbers disagree inside one envelope. CONFIRMED (cosmetic).**
`ai/tool/ToolResultLimiter.java:170-176` — `notice(...)` is rebuilt with `kept.length()` (body only), while `Truncation.returnedChars = visited.length()` (`:94-101`, `:175`) includes the marker (~100-150 chars). `ai/tool/ToolResultEnvelope.java:180-186` publishes both.
Effect: the model reads `"returnedChars":20000` next to "返回 19860 字符". No test asserts notice↔field consistency. Severity **Low** (not S3-high; nothing downstream consumes the notice number).

**V-9 — Dual-channel truncation: the frame limiter overwrites the model-side truncation fields. CONFIRMED mechanism, LATENT trigger.**
`ai/run/FrameSizeLimiter.java:56` (`TRUNCATABLE_FIELDS = text, result`), `:104-142`, `:274-296` (unconditional `put` of `truncated/noticeCode/strategy/originalChars/returnedChars/notice/truncatedReason`; `fields.isEmpty()` also removes the model-side `strategy`).
Trigger requires one `tool_result` frame that is both model-side truncated and >64 KB (`application.yml:367`), i.e. a `resultView` that alone nearly fills the frame. Today the only provider bounds its view by the frame budget (`ai/resultview/ListTasksResultViewProvider.java:92-99`), so it needs a *second* provider (exactly G1-10's hypothetical) or a CJK-heavy `result` plus a view. Severity **Low now / Medium latent**; the missing `noticeCode`-priority test is real.

**V-10 — Compaction state is read-modify-write with no CAS; a second compaction in one turn overwrites the archive.**
`ai/run/RunStore.java:2715-2720` (`HSET`+`EXPIRE` field merge; `:2714-2727` documents stale `reason/model` residue), `ai/context/ContextCompactor.java:319-324, 328-330`. Archive key is `(session, turnNo)` (`RunStore.java:2671-2673, 2803-2806`) and `turnNo` only increments at turn end (`ResilientChatService.java:2204`), so first-round and resume compaction of the same turn share a key → the earlier pre-compaction original is lost.
Classification: archive part **CONFIRMED** (forensics loss, Low); RMW race **PARTLY** (serialized by `SessionGate` unless the gate lapses). `CompactionFuseTest` calls a pure function twice — it does not exercise a second writer.

**V-11 — `/context` denominator and compaction denominator come from different inputs. CONFIRMED (display only).**
Endpoint: `ai/context/ContextBreakdownService.java:51` `limitTokens(null, state.model())`; `state.model` is the **upstream echo** (`ResilientChatService.java:2204` ← `ContextMeter.model()`), and `null` providerId means a global modelId match (`ai/context/ContextConfig.java:75-90`). Compaction uses `(runtime.providerId(), runtime.modelId())` (`ResilientChatService.java:1978-1981`, `ResumeService.java:922-925`).
Effect: an aliased echo (`gpt-4o-2024-08-06`) misses the row → 128000 fallback; a modelId registered under two providers picks the wrong row. Percent/free are wrong while the frame/`done.context` are right. Severity **Medium** (trust in the pill), not High.

**V-12 — `/history` returns 500, not 503, during a Redis outage. CONFIRMED.**
`ai/web/AiController.java:1256` `chatMemory.get(sessionId)` with no availability pre-check or catch; `ai/memory/RedisChatMemoryRepository.java:281-291` does not wrap into `AiStateUnavailableException`; `common/GlobalExceptionHandler.java:320` → 500. Contract says 503 (`AiController.java:103-110`); `/context` (`:1557`) and `/events` (`:747-755`) do it correctly. Severity **Low-Med**.

**V-13 — Cancelling a **parked** run emits no terminal frame, violating the "exactly one `done`/`error`" invariant. CONFIRMED.**
`ai/run/ResumeService.java:691-699` sets `CANCELLED`, `cancelPendings`, returns — the only `done{cancelled:true}` emitter is the live-turn path (`ResilientChatService.java:1286-1300`). `ai/web/AiController.java:872-878` only triggers the resume. `ai/run/SseChatEmitter.java:640-644, 694-710` decides terminality from the archive, so `GET /events/{runId}` after the cancel replays nothing, `waitForLiveTerminalFrame=true`, and the connection is held with no terminal frame (and the park-close branch requires `SUSPENDED`, so it will not close it).
Effect: archive/snapshot disagree (terminal snapshot, no terminal frame); a client that reattaches after cancel hangs until its own watchdog; the documented invariant and the "stop 归档" test contract are unmet. Severity **Medium** (the foreground tab converges locally — see C6).

**V-14 — `AiUnavailableException` has no handler; the narrow-window path emits no stable code. CONFIRMED.** Thrown at `ai/config/AiProviderRegistry.java:141-146`, referenced only in a comment (`ResilientChatService.java:373`); falls through to the generic handler. Severity **Low**.

**V-15 — `reload()` records a config version it did not load. CONFIRMED.** `ai/config/AiConfigService.java:175-181` (`applyLoad(load(), readRedisVersion())`, left-to-right) vs `:164-166, 218-229` (version read first). A concurrent INCR during `load()` is marked seen → stale snapshot until the next bump. Contradicts the class's own "只在加载成功后推进版本" rationale. Severity **Low**.

**V-16 — `NEVER_HELD ⇒ plain SET` lets a first-round writer overwrite a fenced snapshot. CONFIRMED mechanism, conditional impact.** `ai/run/RunStore.java:705-726, 758-782` (only a throttled WARN when the fence is ahead); the first round never takes a lease (only `ResumeService.java:530` acquires). Needs two JVMs + a frozen first-round writer + zombie takeover (`ai/run/ResumeService.java:722-745`). This is the concrete fencing hole; the declared red line E3 is the reason it exists.

**V-17 — Metadata-only KB change leaves stale titles in section paths. CONFIRMED.** `ai/kb/KbService.java:256-265` (`withMeta` reuses chunks/sections when sha is unchanged), `ai/kb/KbDoc.java:17-23`, `ai/kb/KbSection.java:31-37` (`path` = old title > …), matching at `KbService.java:665-680`. Effect: TOC/chunk `sectionPath` use the old title; `kb_read_doc(section="新标题 > X")` → `SECTION_NOT_FOUND` + candidates. Recoverable via suggestions. Severity **Low-Med**.

**V-18 — KB search marks the top hit `high` whenever there are ≥3 hits.** `ai/kb/KbService.java:463-478`: thresholds are ratios of `topScore`, and the top hit *is* `topScore`; `lowConfidence` only for ≤2 hits. A garbage query with 3 weak hits is reported as high confidence. **CONFIRMED**, Low (design/UX).

**V-19 — Table chunking can exceed `chunk-max-chars`.** `ai/kb/MarkdownChunker.java:262-276` validates only `lines[2]`; afterwards `piece == header` appends any row unchecked. **CONFIRMED**, Low (no current consumer breaks; `KbText`'s 8000 cap still protects the tool surface).

**V-20 — Attachment `sessionId` is client-declared and never matched to the pending call.** `ai/form/GenerativeFormAttachmentController.java:80-107, 142-152` validates only "a generative_form is pending"; `RedisFormAttachmentStore.findBySessionId:172-184` enumerates by that declared id. Effect: passing another session's id makes that session's destroy delete this file; omitting it makes the file unenumerable (orphan until TTL). **CONFIRMED**, Low-Med.

**V-21 — `suspend-cross-instance` is a deterministic false red (gate, not product). CONFIRMED.** `scripts/ci/cluster3-probe.mjs:2157-2171` reattaches B and C *before* the confirm, while `AiController.java:762-768` deliberately closes a parked reattach (`completeSubscriptions()` when `stillWaitingForInput`), pinned by `AiControllerEventsParkCloseTest:84-105`. `reattachOn` is a single `openSse` with no reconnect (`:1515-1521`), so `waitTerminal` can never see a terminal. Fix is test-side.

## 3. Test assertions that fake safety (verified)

| # | Test | Why it cannot go red | Evidence |
|---|---|---|---|
| T-1 | `RunSnapshotModelIdentityTest` / `RunParamsFreezeTest` | Both use `InMemoryRunStore`, which **duplicates** `create()` including identity + freeze; the javadoc's stated kill ("remove `setProviderId` ⇒ red") is impossible. The real `RunStore.create` lines `ai/run/RunStore.java:648-651` have no direct assertion (every real-store call passes `null,null`). | `test/.../conformance/InMemoryRunStore.java:205-221`; `RunSnapshotModelIdentityTest.java:16-21, 29-37` |
| T-2 | Lease scripts | `FakeLeaseRedis` dispatches on marker comments and re-implements acquire/renew/release in Java; **no** test asserts the Lua text for any lease script. Changing `>=` to `>` in the fence compare, or the TTL argument order, stays green. | `test/.../run/FakeLeaseRedis.java:541-593`; only `lastScriptBody` assertions are IDEM_PUSH / rate-limiter / memory |
| T-3 | `ContextEstimatorTest.messagesSkipSystemAndSumToolBodies` | `assertThat(all).isLessThan(all + systemOnly)` is a tautology (`systemOnly > 0`); "system is skipped" is never asserted. | `test/.../context/ContextEstimatorTest.java:65-68` |
| T-4 | `ToolScopeTagSpaceGuardTest` | Lower bound 26 vs 37 actual `@ToolScope` → ~11 lost scopes stay green. | `tools/ToolScopeTagSpaceGuardTest.java:46,75` |
| T-5 | `ToolDefinitionOverridesTest.shippedYamlCoversEveryDeclaredTool` | Iterates a hardcoded 27-name list; no assertion that registered tools == YAML ∪ defaults (10 `kb_*`/`ui_*` have no entry). | `tool/ToolDefinitionOverridesTest.java:261-273` |
| T-6 | `ResultViewFrameTest` | 3-arg emitter assembly (`limiter=null`), hand-built `ResultView`; no provider→frame→truncation path. | `resultview/ResultViewFrameTest.java:39` |
| T-7 | `AiControllerContextEndpointTest` | Builds the resolver **without** a DB source → V-11 is invisible to the suite (claim correct). | `web/AiControllerContextEndpointTest.java:64-66` |
| T-8 | `CompactionFuseTest.twoInstancesBothSeeTheHalfOpenWindow` | Two calls to a pure function; pins intent, not multi-instance behaviour. | `context/CompactionFuseTest.java:105-123` |

## 4. What I could not close
- No execution: V-3 (does Lucene build succeed with zero docs? code path says yes, unrun), V-5 concurrency ordering, V-13 client behaviour, V-16 fencing, V-20 — all static.
- KbService `acquire()` spin is certain as code, but whether any deployment reaches it depends on Spring bean-destroy order and on in-flight AI turns at shutdown.
- The multi-instance items (V-16, V-10 race, KB local-disk version ping-pong) need a 2-JVM SIGSTOP run; nothing in the tree provides it.

**Bottom line for the final audit:** the genuinely material defects are V-1 (cross-session memory destruction via a crafted id), V-5 (attachment loss before the decision claim), V-6 (cancel path bypasses the tool-result envelope/limiter), V-3 (legal empty manifest silently empties the KB and reports ready), V-13 (parked-cancel has no terminal frame), V-2 (seeded-provider trust flip), plus V-7/V-11 as trust-of-display issues. G1-01, G1-02, G4 #3/#4, G2 F7 and G4 #6 should be down-graded as described; G1-12-4 is a false gap; and the T-1/T-2 test claims are the ones that most distort the evidence base.
