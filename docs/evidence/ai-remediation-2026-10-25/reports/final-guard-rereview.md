> 来源=<仓外证据>/final-guard-rereview.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Guard re-review — scoped re-check of Important I-1 (JDBC exemption scope)

- Date: 2026-10-25
- Mode: read-only, no subagents, no edits to the repository
- Inputs: `<仓外证据>/redis-guard-scope-fix-report.md`, `<仓外证据>/redis-guard-scope-fix.patch`, applied tree of `<主仓>`
- Scope: **only** the guard Important finding from `final-frontend-guard-review.md` (I-1). Frontend Minors M-1…M-7 stay deferred; guard Minor M-5 (positive-case floor 12) also stays deferred (report §7.6).

## Verdict: **ADDRESSED**

All three concrete false negatives I produced are closed by the applied patch (reproduced myself, not taken from the report's log). Genuine JDBC `Statement` / `PreparedStatement` / `CallableStatement` stays exempt in every form I could construct that main could plausibly use. Redis aliases and shadow declarations fail closed in all idiomatic forms. No detection loss: self-check still contains all 29 original cases, my original 6-probe set still yields 6/6 hits, and the 16-hit negative-control set is byte-identical under my own run of the applied guard.

No new Critical or Important. Two new Minor items inside this patch (one new fail-closed false positive, one residual fail-open corner that pre-dates the fix), both with a one-line fix; neither is a blocker.

## 1. Patch ↔ tree identity

`git apply --check -R redis-guard-scope-fix.patch` in the repo → **passed**, i.e. the working tree is exactly "patch applied", no drift, no extra edits to the two files (`git diff --numstat`: `1/1` README, `304/5` guard vs base `ae883fb`). Guard/README only; no Java or CI-workflow change.

## 2. The three false negatives I reported — all closed

Same synthetic files I used in the first review (`<验证副本>/_review-guard-probe/src/x`), re-run against the applied guard:

| Probe (my original file) | Before | Now | Line reported |
|---|---|---|---|
| `A_crossScope.java` — `Statement statement` in method A, `RedisConnection statement` in method B, `statement.execute("FLUSHDB")` | 0 (miss) | **1** | `:12` |
| `B_comment.java` — `// … the JDBC Statement conn …` + `conn.execute("FLUSHDB")` | 0 (miss) | **1** | `:6` |
| `B2_string.java` — `String doc = "legacy PreparedStatement client";` + `client.execute("SCAN 0")` | 0 (miss) | **1** | `:6` |

Same run still reports the three pre-existing true positives (`C_chain` chained receiver, `D_sameline` same-line `JDBC SELECT; connection.execute("FLUSHDB")`, `E_field` `this.redis.execute("KEYS …")` with a JDBC `this.statement` correctly exempt) → **6/6 hits, exit 1**. Nothing was lost while closing the three.

The report's RED claim is truthful: `evidence-redis-guard-scope/selfcheck-scope-RED-final.log` fails exactly the four cases (the three above plus the "变量名叫 statement 但类型是 RedisConnection" case I called out as an idle test), and that RED file is built from the **pre-fix** implementation (`red-run/…/forbid-redis-cluster-unsafe.mjs:143-145`, still `jdbcStatementVars`).

## 3. Genuine JDBC remains exempt — my own matrix

`<验证副本>/_review-guard-probe2/src/x/F_genuine_jdbc.java` — JDBC `Statement` as a **field**, as a **local**, `PreparedStatement` in **try-with-resources**, `CallableStatement` local, `Statement` in a **for-init**, used from another method → **0 hits** ✔.

Self-check also gained two preservation cases that are not tautological (`CallableStatement` exemption, JDBC field used inside a method) and kept all four original JDBC cases; the four fragment-shaped JDBC cases were wrapped into full `class { void m() { … } }` units — the assertions (hits: 0) and semantics are unchanged, which I confirmed by running them.

Main scan: **311 files / 0 hits**, 0.31 s, exit 0 — so `FlywayStartupLock.java:157-165` (simple-name `Statement` / `PreparedStatement` in try-with-resources) is still exempt in production code.

## 4. Aliases and shadow declarations fail closed

| Probe | Expectation | Result |
|---|---|---|
| `H_alias_stmt.java` — `Statement client = conn.createStatement(); client.execute("SCAN 0")` | hit (alias never exempt) | 1 ✔ |
| `X_alias_declared.java` — `PreparedStatement conn = …; conn.execute("FLUSHDB")` | hit | 1 ✔ |
| `evidence/probe-root/AliasDeclaredAsStatement.java` | hit | 1 ✔ (my run) |
| `J_innerclass_field.java` — outer JDBC field `statement`, **inner class** RedisConnection field `statement`, `statement.execute("FLUSHDB")` | hit (shadow) | 1 ✔ |
| `I_lambda_crossmethod.java` — JDBC `statement` in method A, lambda **parameter** `RedisConnection statement` in method B | hit (scope closed) | 1 ✔ |
| `evidence/probe-root/ShadowParam.java` — JDBC field + Redis **parameter** | hit | 1 ✔ (my run) |
| `K_textblock.java` — text block containing `Statement conn` + `conn.execute("FLUSHDB")` | hit (literals stripped) | 1 ✔ |
| `M_brace_skew.java` — `// }` and `"}{"` before a JDBC try-with-resources | 0 (stripping protects brace spans) | 0 ✔ |
| `U_apostrophe_comment.java` — `// don't … Statement conn` + JDBC try | 0 (apostrophe in prose cannot open a char literal) | 0 ✔ |
| `V_char_literal.java` — `char c = '"'`, `"http://…/*"` + JDBC | 0 | 0 ✔ |
| `N_multiresource.java` — `try (Statement s = …; Closeable c = null)` | 0 (widened fallback scope still contains the call) | 0 ✔ |
| `O_thisfield.java` — `this.conn.execute("FLUSHDB")` + `this.statement.execute("SELECT 1")` | 1 (Redis side only) | 1 ✔ |
| `Y_unknown.java` — `holder.stmt.execute("FLUSHDB")`, no declaration | hit | 1 ✔ |

`evidence/probe-root` re-run by me: **4 hits** (BlockComment, AliasDeclaredAsStatement, Chained, ShadowParam) — matches the report.

## 5. No detection loss (independent reproduction)

| Check | Result |
|---|---|
| Self-check, my run | 34/34 ok, 正向样例 22 条, exit 0; **all 29 original case names still present** (`grep "name: '"` → 34), none deleted |
| Main scan, my run | 311 files, 0 hits, exit 0 |
| Negative-control `evidence/fixture-root` (16 injected violations, all 10 rules) | my run of the **applied** guard → 16 hits, and my normalized set is **byte-identical** to `norm-hardened.txt` (which is itself identical to `norm-currentmain.txt`, 16/16) → no rule narrowed |
| My original 6-probe set | 6/6 hits (was 3/6) |

Timing: full 311-file scan 0.31 s (per-file `stripCommentsAndLiterals` + `braceSpans` are O(n); `enclosingSpan` is O(#spans) per declaration/call — no practical cost at this size).

## 6. New findings inside this patch (both Minor, neither blocking)

### Minor N-1 (new, fail-closed false positive) — fully-qualified JDBC types are no longer exempt

`scripts/ci/forbid-redis-cluster-unsafe.mjs:255-258`. The new "shadow" collector records `java.sql.Statement statement = …` as an **other-type** declaration, because the skip test compares the whole captured type against the bare names:

```js
if (/^(?:PreparedStatement|CallableStatement|Statement)$/.test(m[1])) continue
```

`m[1]` is `java.sql.Statement` (the regex allows package prefixes: `(?:[a-z][\w$]*\.)*[A-Z][\w$]*`), so it is not skipped, and `isJdbcExempt` then sees a "shadow" of the same name and denies the exemption.

Reproduced (`G_fq_jdbc.java`): `java.sql.Statement statement = connection.createStatement(); statement.execute("SELECT 1");` → **1 hit**; the pre-fix implementation (`red-run` copy) reports **0**. So this is a genuine new false positive in the fail-closed direction — no Redis detection is weakened, and main contains no fully-qualified JDBC statement today (311/0), but a legitimate future `java.sql.Statement s = …; s.execute("SELECT pg_advisory_lock(…)")` would fail CI.
Fix (one line): strip the package prefix before the skip test, e.g. `m[1].split('.').pop()`; add one self-check case.

### Minor N-2 (pre-existing, still fail-open) — shadow declaration mis-scoped when its initializer contains `{`

`declarationScope()` (`:220-228`) treats "first `{` after the declaration offset beats the next `;`" as "this is a parameter / try-resource, scope = that block". A **local** declaration whose initializer contains a brace before the terminating `;` therefore gets its scope recorded as the *initializer's* block, not the enclosing block:

```java
class L {
  Statement statement;                                                  // JDBC field, class body contains everything
  void m(RedisConnectionFactory f) {
    RedisConnection statement = f.getConnection(() -> { });             // shadow mis-scoped to the lambda body
    statement.execute("FLUSHDB");                                       // → exempted: 0 hits
  }
}
```

Verified `L_anon_initializer.java` → **0 hits**, against the applied guard **and** against the pre-fix `red-run` implementation (also 0) → **not a regression**, it is the one corner of I-1 that the new shadow rule does not reach. Requires a same-named JDBC `Statement` in an enclosing block plus a lambda/anonymous-class initializer on the Redis variable — contrived, but it is the exact class of thing I-1 was about.
Fix (small, fail-closed): when recording **other** declarations, push *both* `declarationScope(...)` and `enclosingSpan(...)` (a shadow veto can only add hits; valid Java cannot have a JDBC and a non-JDBC declaration of the same name in one scope, so false-positive risk is negligible). Add one self-check case for the lambda-initializer shadow.

### Documented boundaries I re-confirmed (not findings)

- `var statement = connection.createStatement(); statement.execute("SELECT 1")` → 1 hit (`Q_var.java`): still fail-closed, as documented in report §7.3, unchanged from before.
- Chained `createStatement().execute("SELECT …")` → hit (no exemption), unchanged.
- Positive-case floor still 12 while there are 22 positive cases (`:588`): that is deferred Minor M-5, explicitly out of scope per the report.

## 7. Requirements checklist for this re-review

| Item | Result |
|---|---|
| Comment-declaration false negative closed | ✔ `B_comment.java` → hit |
| String-declaration false negative closed | ✔ `B2_string.java` → hit |
| Same-name `RedisConnection` in another scope closed | ✔ `A_crossScope.java` → hit; also inner-class field (`J`) and lambda param (`I`) |
| Genuine `Statement` / `PreparedStatement` / `CallableStatement` stays exempt | ✔ simple-name forms: field, local, try-with-resources, for-init, cross-method, main 311/0; ⚠ fully-qualified form regressed (N-1, Minor, fail-closed) |
| Redis aliases fail closed | ✔ `client`, `conn`, `connection` declared as `Statement`/`PreparedStatement` still hit |
| Shadow declarations fail closed | ✔ idiomatic forms; ⚠ brace-in-initializer corner still open (N-2, pre-existing, Minor) |
| All original detections remain | ✔ 29/29 original self-check cases retained, fixture 16/16 identical, my 6-probe set 6/6, `--self-check` 34/34, main 311/0 |
| Evidence claims in the report | ✔ all reproduced by me: RED 4 failures, GREEN 34/34 (22 positive), main 311/0, fixture 16/16 both directions, probe-root 4 hits, patch↔tree identity |
| No scope expansion | ✔ only the two `scripts/` files changed; frontend Minors untouched |

## 8. Bottom line

I-1 is addressed. The exemption now has real evidence requirements (stripped source, enclosing block, no same-name shadow, alias veto) and every case I originally reported as a miss now fires, with no measurable loss of existing detections. Two small residuals — one fail-closed false positive for fully-qualified JDBC types (N-1, new) and one contrived fail-open shadow corner (N-2, pre-existing) — are worth a follow-up commit but do not block.
