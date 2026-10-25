> 来源=<仓外证据>/final-frontend-guard-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Final independent review — frontend duplicated-tab clear + Redis guard exemption

- Date: 2026-10-25
- Reviewer mode: read-only, no subagents, no edits to the repository
- Repository: `<主仓>`, base `ae883fb` + working tree
- Artifact reviewed: `<仓外证据>/final-code-review.patch` (6648 lines), frontend hunks (patch lines 4469–5075), `scripts/README.md` hunk (5076–5085), guard hunk (6499–6648) — plus the current tree files those hunks land in
- In scope: `frontend/src/stores/ai.ts`, `frontend/src/utils/parts.ts`, `frontend/tests/duplicated-tab-clear.spec.ts`, `frontend/tests/park-request-tool-start-order.spec.ts`, `scripts/ci/forbid-redis-cluster-unsafe.mjs`, `scripts/README.md`

## Verdict

**Merge: approved.** No Critical/blocking defect. One Important (guard exemption is fail-open in two demonstrated corners — cheap to harden, current tree is clean), seven Minor.

## What I actually ran (read-only)

| Command | Result |
|---|---|
| `node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check` | all 29 cases ok, "正向样例 19 条", 规则自证通过 |
| `node scripts/ci/forbid-redis-cluster-unsafe.mjs` | 扫描 311 个文件，0 命中 |
| `node …/forbid-redis-cluster-unsafe.mjs --root <验证副本>/_review-guard-probe/src` | 6 synthetic `.java` probes → 3 hits (see I-1) |
| `git status --porcelain`, `git diff --stat` | patch = 42 files; frontend+scripts = 7 files, matching the review scope |

Existing logs were read for consistency only (not re-run, not treated as production proof): `frontend-full-final2.log` (135 files / 1582 tests, includes `duplicated-tab-clear.spec.ts` 10 tests and `park-request-tool-start-order.spec.ts` 4 tests), `frontend-build-final2.log` (`✓ built in 54.30s`), `tab-clear-fix1-typecheck.log` (`vue-tsc --noEmit exit_code=0`), `tab-clear-fix1-real-browser.log` (real Chromium probe, SUMMARY 14/14 passed). Guard is wired into CI: `.github/workflows/ci.yml:148` (self-check) and `:151` (main scan).

## Declined items (deliberately **not** raised as findings)

1. `scripts/ci/cluster3-probe.mjs` (+1260 lines) and all backend Java hunks — outside the requested scope; not reviewed here.
2. Re-running the full frontend suite/build, or treating green tests as production proof — explicitly excluded; the Chromium probe log is quoted as *evidence of the scenario*, not as proof of correctness.
3. `sessionStorage.clear()` wiping non-AI tab keys — the user-approved 口径 ("clear its own sessionStorage once"). Today the app writes only `ai-session-id`, `ai-tab-page-id`, `ai-mirror:*` and `cm.ai.form.draft.*` to sessionStorage (`stores/ai.ts`, `utils/form-draft.ts` are the only writers; grep over `frontend/src` finds no other `sessionStorage` use, no token/CSRF). Not a data-scope escape.
4. A duplicated tab losing inherited *business* tab state — requested behaviour, covered by `duplicated-tab-clear.spec.ts:260-277` and the browser probe.
5. The genuine JDBC `SELECT pg_advisory_lock(...)` exemption itself (`FlywayStartupLock.java:157-165`) — correct and necessary; not a weakening by itself. Only its *implementation scope* is questioned in I-1.
6. Late `tool_start` no longer refreshing `name`/`args` on an existing pending card — intended and pinned (`park-request-tool-start-order.spec.ts:103`).
7. `'unsupported'` (instead of `'unknown'`) on probe API failure — accepted as the fail-safe direction required by "lock failure never clear".

## Strengths

1. **Ordering is correct and documented**: `clearDuplicatedTabStorage()` → `adoptFreshSession()` → `rotatePageId()` (`frontend/src/stores/ai.ts:4644-4646`). Cleared-then-rebuilt, so the fresh identity cannot be wiped by the clear.
2. **"Once" is structural, not conventional**: `clearDuplicatedTabStorage` (`ai.ts:650-656`) has exactly one call site (`ai.ts:4644`), and the second-mount case (a duplicate that reloads) is pinned by a test.
3. **Destructive clear needs positive evidence**: `probeSessionLock` catch → `'unsupported'` (`ai.ts:468`) and `resolveTabOwnership` short-circuits on it (`ai.ts:564-569`), so `duplicate` requires three genuine `null` callbacks. The slow-reload path has a *second, independent* protection via the one-shot return intent (`ai.ts:4637`).
4. **bfcache is safe by construction**: `pagehide.persisted === true` returns before writing intent (`ai.ts:599-601`); `pageshow` only revalidates and downgrades (`ai.ts:4665-4687`) — never clears.
5. **Blast radius limited and evidenced**: duplicate path touches only this tab's sessionStorage, no `deleteHistory`, no claim of the old session, `localStorage` untouched; `tab-clear-fix1-real-browser.log` independently shows original tab keeps its lock, mirror, draft, business key and localStorage while the copy holds only its own new identity (+ its new mirror).
6. **Nullability contract kept**: `ensureTabOwnership(): Promise<void> | null` (`ai.ts:4606`, `4617-4620`) with `AiPanel.vue:3633-3636` unchanged, so mount timing is untouched; `TabOwnership` already carried `'unsupported'` (`ai.ts:309`), so no type widening; `vue-tsc --noEmit` exit 0.
7. **Guard keeps the sharp edges**: unknown/chained receivers still hit (`factory.getConnection().execute("FLUSHDB")`), same-line mixed calls still hit, `CommandType.SCAN`/`RedisCommand.SELECT` untouched, `REDIS_RECEIVER` aliases untouched; self-check title/count claims are truthful (29 cases re-run, 19 positive).
8. **Contract coherence across the patch**: the backend now emits `tool_start` before the request frames (`emitToolStartOnce`) *and* back-fills a missing `tool_start` before settlement (`ensureToolStartBeforeSettlement`). The frontend guard is therefore needed for the back-fill path (request already emitted, start restored later) — it defends a real order, it does not paper over a backend break.

## Important

### I-1 `scripts/ci/forbid-redis-cluster-unsafe.mjs:143-161` (+`:166`) — JDBC exemption is text-scoped and fail-open

`jdbcStatementVars(text)` scans the **raw file text** (comments, Javadoc, string literals included) for `(?:PreparedStatement|CallableStatement|Statement)\s+<ident>`; the set is computed once per file and any `.execute("SELECT|KEYS|SCAN|FLUSHDB|FLUSHALL")` whose receiver **name** is in it is exempted.

Three false negatives, reproduced by running the production script `--root` against synthetic files (exit code observed, 3 hits total where 6 were expected):

| Probe | Content | Result |
|---|---|---|
| cross-scope / same name, different type | `void sql(...) { Statement statement = ...; }` in one method, `void redis(RedisConnection statement) { statement.execute("FLUSHDB"); }` in another | **not flagged** (false negative) |
| comment pollution | `// The JDBC Statement conn above is unrelated …` + `conn.execute("FLUSHDB");` | **not flagged** (false negative) |
| string-literal pollution | `String doc = "legacy PreparedStatement client";` + `client.execute("SCAN 0");` | **not flagged** (false negative) |
| unknown/chained receiver | `factory.getConnection().execute("FLUSHDB");` | flagged ✔ |
| same-line violation next to an exempt call | `statement.execute("SELECT 1"); connection.execute("FLUSHDB");` | flagged ✔ |
| `this.<redis>.execute("KEYS …")` next to a JDBC `this.statement.execute("SELECT 1")` | `E_field.java` | Redis side flagged ✔, JDBC side exempt ✔ |

So the exemption meets "exempt genuine JDBC SQL" and keeps "same-line / unknown receiver" detection, but the *alias* guarantee is name-based over the whole file, i.e. it can be reached from prose or from an unrelated same-named declaration. Today's tree is clean (311 files / 0 hits), and a realistic future violation (`redisTemplate.execute("FLUSHDB")`, `c.execute("SCAN …")`) would still be caught because those receiver names never collide with a `Statement` declaration — hence Important, not Critical.

Cheap hardening (all local to the guard):
1. strip `//` and `/* … */` comments and string literals before extracting declarations;
2. never exempt a receiver name that is in `REDIS_RECEIVER` (`conn`, `client`, `connection`, `c`, `rt`, `template`, …), regardless of declarations;
3. optionally scope the set per method body rather than per file;
4. add self-check cases for comment/string pollution and cross-scope shadowing.

Test-truthfulness note on the same item: the case at `forbid-redis-cluster-unsafe.mjs:370-374` is named "变量名叫 statement 但类型是 RedisConnection（反例：不得按名豁免）", but it only passes because that snippet contains **no** `Statement statement` declaration. The guard does exempt by name as soon as any such declaration exists anywhere in the file. Rename or extend the case so the name matches the actual guarantee (`scripts/README.md:309` wording — "同文件声明为 Statement / PreparedStatement 的变量按 JDBC 处理" — is accurate).

## Minor

- **M-1 `frontend/src/stores/ai.ts:564-578`, `4617-4620`** — `'unsupported'` is now terminal, so a transient Web Locks failure at mount permanently opts the tab out of ownership arbitration (no claim, no lock, no re-probe: `revalidateTabOwnership` requires `'owner'`). Pre-fix the role stayed `'unknown'` (also un-retried in practice, but re-probeable). Degradation only — no destructive consequence, and it follows the required fail-safe direction.
- **M-2 `frontend/src/stores/ai.ts:4673-4687`** — `revalidateTabOwnership` releases the lock *before* probing. If the probe now returns `'unsupported'` (API failure), the function returns with `tabOwnership === 'owner'` and **no lock held**, i.e. a lockless "owner" until the next `pageshow`. Pre-fix that path settled `'duplicate'` (after 3×`null`) and downgraded. One-line fix: on `'unsupported'` after the release, re-claim (`claimSessionOwnership(sid)`) or set the role to `'unsupported'`.
- **M-3 `frontend/tests/duplicated-tab-clear.spec.ts:274`** — asserts the post-clear sessionStorage holds exactly `[SESSION_ID_KEY, PAGE_ID_KEY]`, but the production mount order (`AiPanel.vue:3626-3636`: `startMirror()` before `ensureTabOwnership()`) also leaves `ai-mirror:<new session>` — the browser probe log itself shows 3 keys. The spec passes because it never calls `startMirror()`. Fidelity gap in the test, not a product defect; consider asserting "inherited keys gone + exactly one new mirror" and mounting through the real order.
- **M-4 `frontend/src/stores/ai.ts:594-604`** — the return intent exists only between `pagehide` and the next mount. A crash-restored tab (no intent) that finds the same session lock held by another tab is judged duplicate and clears its inherited workspace. It requires an already-degraded second tab holding that session, and it follows the approved lock-only rule — recorded so the boundary is explicit.
- **M-5 `scripts/ci/forbid-redis-cluster-unsafe.mjs:391`** — the floor is `positive < 12` while there are 19 positive cases, so up to 7 positive cases can be deleted/renamed without the self-check failing. Relatedly, the removed `rule.regex.lastIndex = 0` is only safe while no rule regex carries the `g` flag; `ruleHits` builds its own `/gi` regex per line, non-receiver rules do not.
- **M-6** Untracked artefacts in the repo root: `_b3e-agent-mvn-test.log`, `_b3e-kqc.log`, `_b3e-kqc-r2.log`, `_b3e-vl.log`, `data/`, `test-results/`. Not part of the 42-file patch, so not a patch defect — but they must not be swept in by `git add -A`.
- **M-7 `frontend/src/utils/parts.ts:539-548`** — the frame-order defence is scoped to `requiresAction` cards. A `tool_start` arriving for a card that is already terminal (e.g. `succeeded`) without `requiresAction` would still reset it to `running`; this is identical to pre-patch behaviour and the backend now guarantees start-before-settlement, so it is a residual, not a regression.

## Requirements checklist

| Requirement | Result |
|---|---|
| Duplicated tab clears only after reliable detection | ✔ 3 genuine `null` callbacks required; API failure → `'unsupported'` (`ai.ts:438-473`, `564-578`) |
| …clears its own sessionStorage exactly once | ✔ single call site (`ai.ts:650`, `4644`); second-mount case pinned by test |
| …then gets a fresh identity | ✔ `adoptFreshSession()` + `rotatePageId()` after the clear, new `sessionId`/`pageId` verified |
| Reload / bfcache / lock failure never clear | ✔ lock-free reload → owner; `persisted` bfcache → no intent, `pageshow` only downgrades; API failure → no clear; real-browser probe confirms reload keeps sessionStorage |
| Original tab / localStorage / server data untouched | ✔ no `deleteHistory`, no old-session claim, localStorage untouched; browser probe: original keeps lock, mirror, draft, business key |
| Late `tool_start` cannot downgrade pending cards | ✔ `parts.ts:545-548`, pinned by 4 tests incl. idempotent name/args |
| Type safety | ✔ `TabOwnership` already had `'unsupported'`; `Promise<void> \| null` contract preserved; `vue-tsc --noEmit` exit 0 |
| Guard exempts genuine JDBC SQL without weakening Redis raw detection (aliases / same-line / unknown receivers) | ⚠ mostly ✔ — same-line and unknown receivers keep firing, aliases are intact for realistic code, but the exemption is name-scoped per file and can be reached from comments/strings or a same-named declaration of another type (I-1) |
| Test truthfulness | ✔ 29/29 self-check and 311/0 main scan reproduced; 135 files / 1582 tests and the build log are consistent with the new specs being present. One naming overstatement (I-1) and one harness-dependent assertion (M-3) |
