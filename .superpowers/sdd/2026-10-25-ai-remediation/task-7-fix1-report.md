# Task 7 — fix1（复审 I1 / I2 / I3 修复报告）

状态：**DONE**（3 条 Important 全部 RED→GREEN；10 条 Minor 与其它已接受语义**未动**）
复审输入：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-7-review.md`
实施位置：隔离副本 `<隔离副本>/_verify-20261025-form-kb`（主树源码未改，只新增本报告与补丁副本）
增量补丁：`<隔离副本>/_verify-20261025-form-kb/task-7-fix1.patch`（unified diff 785 行 / 9 个文件；同内容副本 `.superpowers/sdd/2026-10-25-ai-remediation/task-7-fix1.patch`）
补丁基线：**Task 7 之后**（main + `task-7.patch`）。链式验证：`main → task-7.patch → task-7-fix1.patch` 与隔离副本 **12/12 文件逐字节相同**（含 `core.autocrlf=true`，见 §5）。

---

## 1. 结论

| 编号 | 复审判定 | 本次处置 | 结果 |
|---|---|---|---|
| **I1** 重建永久失败无退避（每 30s 全量重建 + WARN） | 已确认 | 每版本**有界重试预算 3 次 + 退避**（`poll × 次数`，封顶定时刷新间隔）；成功即清零；新版本重置预算；WARN 节流（首条 + 每 4 条，其余 DEBUG） | RED 复现"300ms 内 16 次全量重建"，GREEN 为 4 次（首刷 1 + 预算 3）后彻底安静 |
| **I2** 多选级联陈旧子值无清空入口 | 已确认 | 新增纯函数 `pruneCascadeValues()`（父值变化时按新可解析选项裁剪依赖字段值）+ 接到 `FormRenderer.setValue` | RED 复现 `draft.defs = ['CURRENCY']`（父值切到无分支后仍在），GREEN 为 `[]` 且 `checkFormIssues` 为空 |
| **I3** 两个新可见码缺中英 i18n 键 | 已确认 | `zh-CN.json` / `en-US.json` 各补 2 键（照 `VALUE_STEP_MISMATCH` 体例）+ 新增 locale 回归 spec | RED 3/4 失败（缺键/英文无值），GREEN 4/4 |
| Minor ×10 + 其它语义 | 按要求不动 | 未改 | — |

未新增依赖、未引入忙等循环、未跑全量套件、未提交/推送、未动主树源码、未派发子代理。

---

## 2. 改动文件（9 个）

后端（2）：
- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java`
  - 新增字段：`MAX_VERSION_REBUILD_ATTEMPTS = 3`、`FAILURE_WARN_EVERY = 4`、`retryVersion`、`retryAttempts`、`retryNotBeforeNanos`（`System.nanoTime()` 单调基准）、`volatile int consecutiveFailures`；注释写明"重试三元组只在 kb-refresh 单线程读写"。
  - `pollVersionQuietly()`：新版本 ⇒ 重置预算；预算用完 / 退避窗口内 ⇒ 直接返回（不碰源）；`refreshQuietly()` 后以"`lastObservedVersion == version`"判定成功（成功路径统一记版本）⇒ 清零预算；失败 ⇒ `attempts++`、`notBefore = now + backoffMillis(attempts)`，用尽时打一条"暂停自动重试"WARN。
  - `backoffMillis(attempt)`：`min(max(poll, refreshInterval), poll × attempt)` —— 只用既有两个配置口径。
  - `refreshNow()`：三条成功路径（304 短路 / 整份未变短路 / 重建完成）`consecutiveFailures = 0`；catch 里按 `(n-1) % 4 == 0` 输出 WARN（1/5/9…），其余降到 DEBUG，`lastError`/`status` 口径不变。
- `backend/src/test/java/com/example/configmgr/ai/kb/KbLifecycleRobustnessTest.java`（6 例）
  - 沿用 4 例并给 `failedRebuildIsRetriedOnTheNextVersionPoll` 补"成功后不再重试"（复审 M7 的现象，但属 I1 的必测项）。
  - 新增 `persistentFailureIsBoundedPerVersionAndDoesNotRebuildOnEveryPoll`（有界 + 不热重建 + WARN 节流，Logback `ListAppender` 断言 WARN 恰 2 条且含"暂停自动重试"）。
  - 新增 `aNewVersionGetsAFreshRetryBudget`（预算用尽后 `version(2)` 立刻又能重试）。

前端（7）：
- `frontend/src/types/form-schema.ts`：新增 `pruneCascadeValues()`（导出、纯函数）+ 私有 `prunedCascadeValue()` / `sameValue()`；复用既有 `resolvableOptions()`（`null` = 无法枚举 ⇒ 不动）。
- `frontend/src/components/AiPanel/FormRenderer.vue`：`setValue` 里先裁剪再 `applyDefaultFrom`（3 行 + 1 个 import）——这是 I2 唯一可行的"用户可操作"入口。
- `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`：各 +2 键（`FIELD_STEP_INVALID`、`FIELD_NUMBER_BOUND_INVALID`）。
- `frontend/tests/gf-schema-robustness.spec.ts`：新增"级联裁剪"纯函数用例 6 条（清空 / 保留合法子集 / enum 清成未选 / 多级 A→B→C / sourceId 不动 / 纯函数不改入参）。
- `frontend/tests/gf-cascade-prune.spec.ts`（新）：只经**渲染器真实交互**断言 3 条（陈旧多选不留在提交里、保留合法子值、合法子值不误清）。
- `frontend/tests/gf-errors-i18n.spec.ts`（新）：I3 回归 4 条（两码存在、英文不含 CJK、中文是本地语言、`errors` 子树键集与占位符一致）。

---

## 3. RED → GREEN 证据

### 3.1 I1（后端）

RED（把 `KbService.java` 换回 Task 7 版本，用例不变）：
```
cd <隔离副本>/_verify-20261025-form-kb/backend
mvn.cmd -o -B -Dspring.data.redis.port=1 -Dtest='KbLifecycleRobustnessTest' test
```
日志 `fix1-be-red.log`：**Tests run: 6, Failures: 1**
`persistentFailureIsBoundedPerVersionAndDoesNotRebuildOnEveryPoll:143` → `expected: 4 but was: 16`
即 20ms 轮询下 300ms 窗口内发生了 **16 次** `fetchManifest`+全量重建（复审 I1 描述的"每拍全量重建"）。

GREEN（修复版 + 4 个相邻类）：
```
mvn.cmd -o -B -Dspring.data.redis.port=1 \
  -Dtest='KbLifecycleRobustnessTest,KbServiceIndexTest,KbServiceIncrementalTest,KbLocalSourceTest,CachedKbSourceTest' test
```
日志 `fix1-be-green.log`：**Tests run: 34, Failures: 0, Errors: 0 — BUILD SUCCESS**
（`KbLifecycleRobustnessTest` 6、`KbServiceIndexTest` 12、`KbServiceIncrementalTest` 5、`KbLocalSourceTest` 5、`CachedKbSourceTest` 6）
最终文件重跑 `fix1-be-green-final.log`：`KbLifecycleRobustnessTest` **6/6、BUILD SUCCESS**（javadoc 收尾后复跑）。

三条必测项分别由：
- *不热重复重建* —— `afterBudgetExhausted` 之后 `Thread.sleep(300)`（≈15 拍）`attempts` 不变；RED 时同一断言 16 vs 4。
- *成功即停* —— `failedRebuildIsRetriedOnTheNextVersionPoll` 末尾 `attemptsAtReady` 之后 240ms（≈12 拍）不再增长。
- *新版本可重试* —— `aNewVersionGetsAFreshRetryBudget`：预算用尽后 `version(2)` ⇒ `attempts` 增长，随后健康即 ready（20 篇）。

### 3.2 I2 / I3（前端）

RED（`form-schema.ts` / `FormRenderer.vue` / 两份 locale 换回 Task 7 版本，spec 用最终版）：
```
cd <隔离副本>/_verify-20261025-form-kb/frontend
npx vitest run tests/gf-cascade-prune.spec.ts tests/gf-errors-i18n.spec.ts
```
日志 `fix1-fe-red.log`：**Tests 6 failed | 1 passed (7)**
- I2 三条全红，第一条正是复审给出的用户症状：`expected [ 'CURRENCY' ] to deeply equal []`（父值切到无分支的 US 之后，draft 里仍带陈旧多选值）。
- I3 三条红（缺键 / 英文无值 / 中文断言），"键集与占位符一致"在修复前**本来就绿**（两份 locale 同样缺键），修复后仍绿 —— 该条防的是"只补一份"，不是本次症状。

GREEN（3 个新 spec + 4 个相邻 spec）：
```
npx vitest run tests/gf-schema-robustness.spec.ts tests/gf-cascade-prune.spec.ts tests/gf-errors-i18n.spec.ts \
  tests/gf-i18n.spec.ts tests/gf-renderer.spec.ts tests/gf-schema-rules.spec.ts tests/gf-draft.spec.ts
```
日志 `fix1-fe-green.log`：**Test Files 7 passed (7), Tests 60 passed (60)**
（新增 11 + 3 + 4 = 18；相邻 `gf-i18n` 3、`gf-renderer` 19、`gf-schema-rules` 14、`gf-draft` 6 = 42；合计 60）
类型检查：`npx vue-tsc --noEmit -p tsconfig.json` → 日志 `fix1-fe-tsc.log` 末行为 `exit=0`（复审 M9 的自证口径）。

一处自查修正（不是产品缺陷）：`visibleValues()` 只丢"可选 + 空串"，所以裁剪后 `province` 清成的 `''` 不进提交投影；断言已按真实契约改为 `{ country:'US', defs: [] }`。

---

## 4. 语义边界（刻意保留）

1. **重试预算的作用面**：只作用于**版本轮询通道**。10m 定时刷新与 `POST /kb/reload`（`refreshNow(true)`）语义不变 —— 它们本来就是"人为触发/低频"，不需要预算。
2. **"成功"的判据**：`lastObservedVersion == version`（任一成功路径都会记版本，含"整份未变"短路）。`CachedKbSource.fetchManifest` 不会返回 `notModified`（`fromCache` 恒 `modified`），因此该判据在缓存源上是完备的。
3. **退避值**：`poll × attempt`（默认 30s → 30s/60s/90s），封顶 `refresh-interval`（10m）。全部来自既有配置，无新配置项。
4. **WARN 节流口径**：连续失败第 1 条 WARN，之后每 4 条一条（1/5/9…），其余 DEBUG；`lastError` 与 `status()/health` 始终是最新原因。跨"预算用尽"后由 10m 定时刷新继续失败时，WARN 间隔约 40 分钟。
5. **I2 裁剪范围**：只碰 `optionsFrom.dependsOn` 的字段；`sourceId`（无法枚举）不动；值合法则原样；enum 清成 `''`、multi_select 保留合法子集。`defaultFrom` 仍在裁剪**之后**套用（未被用户碰过的子字段可被默认值补上），与既有顺序一致。
6. **I3 文案**：`「{label}」的步长设置不可用，请重新生成表单` / `"{label}" has an unusable step setting; please regenerate the form`；`FIELD_NUMBER_BOUND_INVALID` 用"数值范围"对应措辞。`NON_RETRYABLE_CODES`（复审 M4）**未动** —— 属 Minor。

---

## 5. 补丁可用性与链式一致

- 增量补丁基线 = Task 7 之后。`fix1-stage`（Task 7 状态的临时 git 仓）上 `git apply --check -p1 task-7-fix1.patch` ⇒ **OK**。
- 链式检查（`core.autocrlf=true`，与主树一致）：`main` 的 7 个原件 → `git apply task-7.patch` → `git apply task-7-fix1.patch`，与隔离副本逐一 `diff` ⇒ **12/12 逐字节相同**（7 个 Task 7 文件 + 2 个 fix1 新建 spec + 2 个 Task 7 新建测试 + `FormRenderer.vue`/两份 locale）。
  - 第一次链检曾暴露 2 个新 spec 是 LF（`git apply` 在 autocrlf=true 下写出 CRLF）⇒ 已把两个新 spec 统一成 CRLF 后重新生成补丁并复检通过（仓库既有文件均为 CRLF）。
- 主树 `git status` 中本次涉及的源码路径仍然干净（补丁基线正确）。

---

## 6. 关注点（供合流复核）

1. **`KbService` 的两个通道并发**：`retryVersion/retryAttempts/retryNotBeforeNanos` 只在 `kb-refresh` 单线程读写（已注释）；`consecutiveFailures` 为 `volatile`（管理端点线程也会调 `refreshNow`）。即使交错，最坏只是"WARN 级别判定差一拍"，不影响重试预算与检索面。
2. **预算用尽后的可见性**：`status().lastError` 保留失败原因、"暂停自动重试"WARN 带版本号与刷新间隔，健康面仍能看出"知识库停在旧版本"。若后续要更醒目，可在 `KbStatus` 增 `degraded` 位（属 Minor/后续批）。
3. **重试用例的时序假设**：用 20ms 轮询 + 300/240ms 观测窗口（≈15/12 拍）。断言方向是"跨多个轮询周期仍不增长"，对 CI 抖动有一定余量（预算 3 次即使全部提前完成也不会误红）；若未来把默认 poll 调大，用例仍成立（窗口按拍数而非固定时间给定）。
4. **I2 的 draft 恢复路径**：`initialModel()` 从草稿/`initialValues` 恢复时**不裁剪**（只做类型/选项形状过滤，非法枚举值会被丢弃，但"父值+陈旧子值"这种组合不会主动清）。复审只要求"父值变化时"的裁剪，未扩大范围；如需覆盖恢复路径，建议后续批在同一纯函数上再加一次入口。
5. **未覆盖的相邻面**（按上限）：`aie2e-*`、`form-result-limit`、`rg-form-boolean` 等未跑；最终统一验证批覆盖。
6. **未处理**：复审 10 条 Minor（M1 注释口径、M2 非有限 default、M3 `KbManifest.empty()`、M4 可重试码、M5 空必填 enum 码、M6 数值父值键口径、M7/M9 证据口径、M8 stop/refresh 交错、M10 报告笔误）与"跨批一致性提示"，一律按指示未动。
