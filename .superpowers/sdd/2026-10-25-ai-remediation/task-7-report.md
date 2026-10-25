# Task 7 — 表单与知识库健壮性（实施报告）

状态：**DONE**（5 个已确认缺陷全部按 RED→GREEN 闭环；`FormRenderer.vue` 无确认缺陷，未改）
实施位置：隔离副本 `<隔离副本>/_verify-20261025-form-kb`（主树 `<主仓>` 未改动源码，只新增本报告）
补丁：`<隔离副本>/_verify-20261025-form-kb/task7.patch`，同内容副本已放入 `.superpowers/sdd/2026-10-25-ai-remediation/task-7.patch`（unified diff 851 行 / 7 个文件；两者在主树 `git apply --check -p1` 均为 0）

隔离副本只含必需的树：`backend/{pom.xml,maven-settings.xml,src}`、`docs/kb-samples`（`KbTestSupport.samples()` 的相对路径依赖）、`frontend/{package.json,yarn.lock,vitest.config.ts,vite.config.ts,tsconfig.json,postcss.config.js,tailwind.config.js,index.html,src,tests,public,scripts,node_modules}`（node_modules 整份复制，未安装新依赖）。

## 1. 结论一览

| # | 缺陷（已确认） | 影响 | 修法（最小） |
|---|---|---|---|
| 1 | 级联 enum/multi_select 父值缺失时子值任意通过 | 后端接受 `{"province":"任意值"}`；前端本地复核也放行 | 把"空集"与"无法枚举"分开：`optionsFrom.map` 解析不出选项 = 空集 ⇒ 提交任何值判 `VALUE_OUT_OF_OPTIONS`；只有 `sourceId`（规则层无法枚举）跳过 |
| 2 | 非有限 `step` 冒到 500 | schema 期放行 `"step":1e999`（Jackson → `Infinity`），回灌期 `BigDecimal.valueOf(∞)` 抛 `NumberFormatException`；`ConfirmGate.doSubmitFrontendResult` → `GenerativeFormGuard.inspectResult` 该调用点无 catch；`GlobalExceptionHandler#handleGeneral(Exception)` 兜到 `HttpStatus.INTERNAL_SERVER_ERROR` ⇒ HTTP 500 | schema 期 `finiteOrNull` 拒绝非有限 min/max/step；回灌期非有限值判 `VALUE_TYPE_MISMATCH`、非有限 step/min/max 判字段错误而不做数值运算 |
| 3 | 空 manifest 变成 ready | `KbManifest.validate()` 未查 `docs` 为空（与其类注释"docs 为空视为源不可用"相反）⇒ 建出空索引、`ready()=true`，"在线但检索面全空" | `validate()` 首查 `docs.isEmpty()` → `KbSourceException`（各源 + KbService 原有 fail-open 路径自动生效） |
| 4 | 重建失败被标记为"版本已见" | `pollVersionQuietly` 先推 `lastObservedVersion` 再刷新；`start()` 提交的首刷也事后无条件记版本 ⇒ 一次失败后同一版本号沉默，最长等 10m 定时刷新 | 只由 `refreshNow` 成功路径记版本；轮询/首刷失败保持旧值，下一拍同版本重试 |
| 5 | 停机后 `acquire()` 自旋 | `stop()` 只 retire、不摘 `snapshot` 引用；retired 快照的 `tryAcquire()` 恒 false 且字段不变 ⇒ `while (current != null)` 死循环（停机窗口内的 search/read/lookup 卡死 + 100% CPU） | `stop()` 先 `this.snapshot = null` 再 retire；`acquire()` 改为"停机即退出 + 最多重读一次"的有界循环 |

## 2. 改动文件（7 个，均在副本内）

后端：
- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`
  - `numberBoundIssues`：`doubleOrNull` → `finiteOrNull`（min/max/step 的非有限数一律 `FIELD_NUMBER_BOUND_INVALID` / `FIELD_STEP_INVALID`）。
  - `valueIssues#number`：非有限提交值 → `VALUE_TYPE_MISMATCH` 并短路；min/max 存在但非有限 → `FIELD_NUMBER_BOUND_INVALID`；step 存在但非有限 → `FIELD_STEP_INVALID`（不再进 `BigDecimal`）；其余算术路径不变。
  - `runtimeOptionValues`：返回类型改为可空；`sourceId`（或没有任何选项来源）返回 `null`，map 级联父值缺失/无分支返回**空集**。
  - `valueIssues#enum / #multi_select`：`options != null` 时严格做成员校验（空集 ⇒ 拒绝）；新增 `optionsHint()` 与 `finiteOrNull()`；删除已无引用的 `doubleOrNull()`。
- `backend/src/main/java/com/example/configmgr/ai/kb/KbManifest.java`：`validate()` 拒绝 `docs` 为空。
- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java`：`start()` 首刷不再事后记版本；`pollVersionQuietly()` 不再预记版本（注释写明口径）；`stop()` 先摘 `snapshot` 引用；`acquire()` 有界循环（新增注释解释前提）。

前端：
- `frontend/src/types/form-schema.ts`：新增私有 `resolvableOptions()`（可空语义），`resolveFieldOptions()` 变成 `resolvableOptions(...) ?? []`（对外契约/渲染口径不变）；`fieldValueIssues` 的 enum / multi_select 改用可空版本。
- **未改** `frontend/src/components/AiPanel/FormRenderer.vue`：它的取值/校验只经 `buildInitialValues / checkFormIssues / resolveFieldOptions`，本次缺陷都在规则层；渲染侧 `optionsOf()` 走 `resolveFieldOptions` 保持返回数组，`externalOptions` 只在 `sourceId` 字段被写入。没有确认缺陷就不动（避免"顺手清理"）。

测试（新增 3 个文件）：
- `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormRobustnessTest.java`（11 例）
- `backend/src/test/java/com/example/configmgr/ai/kb/KbLifecycleRobustnessTest.java`（4 例，含可控失败的 `FlakyCachedSource`）
- `frontend/tests/gf-schema-robustness.spec.ts`（5 例）

## 3. RED → GREEN 证据

### 3.1 后端 RED（最终用例集 + 主树原始源码）

命令（IDEA Maven，无 clean，离线，无真实 Redis/端口）：
```
cd <隔离副本>/_verify-20261025-form-kb/backend
mvn.cmd -o -B -Dspring.data.redis.port=1 -Dtest='GenerativeFormRobustnessTest,KbLifecycleRobustnessTest' test
```
日志 `<隔离副本>/_verify-20261025-form-kb/red-final.log`：**Tests run: 15, Failures: 11, Errors: 0**
- `cascadeValueWithoutParentIsRejected`、`cascadeValueForUnknownParentBranchIsRejected`、`multiSelectCascadeValueWithoutParentIsRejected`：期望 `["province"]` / `["defs"]` 的 `VALUE_OUT_OF_OPTIONS`，实际 `[]`（级联值直接放行）。
- `nonFiniteStepIsAFieldErrorAtSchemaTime`、`nonFiniteMinAndMaxAreFieldErrorsAtSchemaTime`：期望 `FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID`，实际 `[]`（`1e999` 以 `Infinity` 通过 schema 闸门）。
- `pendingFormWithNonFiniteStepYieldsFieldErrorInsteadOfThrowing`、`nonFiniteSubmittedNumberIsAValueErrorNotAnException`：抛
  `java.lang.NumberFormatException: Character I is neither a decimal digit … at java.math.BigDecimal.valueOf(BigDecimal.java:1371)` ← `GenerativeFormRules.valueIssues(GenerativeFormRules.java:1114)` ← `resultIssues(:353)`（即线上 500 的来源）。
- `emptyManifestNeverBecomesReady` / `emptyManifestKeepsTheOldIndexInsteadOfWipingIt`：`Expecting value to be false but was true`（`refreshNow()` 返回 true、`ready()` 为 true，空清单把旧索引清空）。
- `failedRebuildIsRetriedOnTheNextVersionPoll`：`等待条件超时：2 次尝试（实际 1）`（失败后同版本不再触发）。
- `searchAfterStopReturnsNotReadyInsteadOfSpinning`：`stop() 之后 search() 仍在 acquire 自旋（3s 未返回）`（探针线程为 daemon，RED 运行时随 surefire JVM 退出）。
- 同期 4 个"正例锁定"用例本来就绿：分支内取值放行、分支外拒绝、`sourceId` 跳过、有限 step 的既有行为。

> 说明：`red.log` 是同一批用例的首次运行（当时"父值无分支"用了 `country=FR`，该父值本身也不在自身 options 里，失败输出混进了 `country` 的选项错误：`Expecting ["country"] to contain exactly ["province"]`，断言不够干净）；改成"父值合法但没有对应分支"（`country=US`）后重跑得到 `red-final.log`，即上表证据。两次运行的失败集合与根因一致（后端 11 failures，含同一个 `NumberFormatException`；前端 3 failures）。

### 3.2 后端 GREEN（新增 + 4 个相邻类）

```
mvn.cmd -o -B -Dspring.data.redis.port=1 \
  -Dtest='GenerativeFormRobustnessTest,KbLifecycleRobustnessTest,GenerativeFormProBatchRulesTest,GenerativeFormSchemaRulesTest,KbServiceIndexTest,KbServiceIncrementalTest' test
```
日志 `<隔离副本>/_verify-20261025-form-kb/green-final.log`：**Tests run: 77, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
- 新增：`GenerativeFormRobustnessTest` 11/11、`KbLifecycleRobustnessTest` 4/4
- 相邻：`GenerativeFormProBatchRulesTest` 20、`GenerativeFormSchemaRulesTest` 25、`KbServiceIndexTest` 12、`KbServiceIncrementalTest` 5 —— 全绿（无既有口径下降）

### 3.3 前端 RED → GREEN

RED（把 `src/types/form-schema.ts` 换回主树原件，spec 为最终版）：
```
cd <隔离副本>/_verify-20261025-form-kb/frontend && npx vitest run tests/gf-schema-robustness.spec.ts
```
日志 `<隔离副本>/_verify-20261025-form-kb/fe-red.log`：**Tests 3 failed | 2 passed**（父值缺失、父值无分支、multi_select 级联三例得到 `[]`，期望 `VALUE_OUT_OF_OPTIONS`）。

GREEN（恢复修复版）：
```
npx vitest run tests/gf-schema-robustness.spec.ts \
  tests/gf-schema-rules.spec.ts tests/gf-renderer.spec.ts tests/form-result-limit.spec.ts tests/gf-draft.spec.ts
```
日志 `<隔离副本>/_verify-20261025-form-kb/fe-green-final.log`：**Test Files 5 passed (5), Tests 47 passed (47)**（新 spec 5/5；相邻表单 spec 42 例全绿）。

### 3.4 类型检查（前端改动只涉及 .ts）

`npx vue-tsc --noEmit -p tsconfig.json`（修复版 `form-schema.ts`）→ **exit 0，无输出**（日志 `<隔离副本>/_verify-20261025-form-kb/fe-tsc.log` 为空即无诊断）。

## 4. 补丁可用性

`task7.patch` 由"主树原件 → 修复版 + 新测试"的临时 git 仓库生成（a/ b/ 前缀、含 `new file mode`），在**当前主树**执行：
```
cd <主仓> && git apply --check -p1 <隔离副本>/_verify-20261025-form-kb/task7.patch
```
结果：**通过（exit 0）**。主树这 6 个被改/新增路径在 `git status` 里本来就干净（即补丁基线 = 主树当前内容），主树其它未提交候选（ConfirmGate/SpToolCallingManager/ResumeService 等）与本次补丁无交集。

## 5. 口径变化与关注点（供合流复核）

1. **行为变化（有意）**：`optionsFrom.map` 级联字段在父值未选/无分支时，任何子值都被判 `VALUE_OUT_OF_OPTIONS`（前后端同判据）。已挂起但父值为空、子值有内容的旧表单，提交会得到一条字段错误（此前被静默接受）。`sourceId` 级联完全保持跳过（前后端一致）。
2. **空 manifest 的语义收紧**：`docs: []` 现在走"源不可用"（保留旧索引 + `lastError` + 不就绪）。若真的存在"合法空知识库"的运营场景，本修复会选择"永不就绪"，需要产品确认（本批 brief 明确要求"空 manifest 不得 ready"）。生效点比 `KbService` 更广：`HttpKbSource`、`CachedKbSource.fromCache`、`LocalDirKbSource`、`KbSampleSeeder` 都调 `validate()`，一处收紧同时覆盖。
3. **`FIELD_STEP_INVALID` / `FIELD_NUMBER_BOUND_INVALID` 作为回灌期字段错误**：这两个码原本是 schema 期码。前端 `ai.gf.errors.*` 没有这两个 i18n 键，渲染层按既有逻辑回落 `message`（人读中文），不会空白；若要双语，需要另加 locales（本任务未碰前端不动文件之外的资源，且按"不做顺手清理"原则未加）。
4. **前端对 `sourceId` 的宽松边界**：外部结果为空（取不到/父值为空/请求失败）时视为"无法枚举"并跳过成员校验，避免把"数据源暂时不可用"变成"用户填的全错"。后端对 `sourceId` 一律跳过，两侧一致。
5. **未修复的相邻观察（不在本任务范围）**：`KbService.start()` 在 `stop()` 之后不可重入（`stopped` 不回置）；`KbManifest.empty()` 依然存在且现在是"不可用清单"；`GenerativeFormRules.defaultValueIssues` 对 `1e999` 这类非有限默认值仍会接受为 number（渲染侧 `buildInitialValues` 会丢弃非有限数，无 500 风险）。建议后续批次单独处理。
6. **未覆盖的相邻测试类**（按"最多 4 个相邻类/spec"上限）：`KbLocalSourceTest`、`KbSampleSeedingTest`、`KbSampleSeederConcurrencyTest`、`KbHttpSourceTest`、`GenerativeFormContractTest`、`GenerativeFormSubmissionRulesTest`、`GenerativeFormAttachment*Test` 未跑。它们都经 `validate()`/`validate` 路径但用的都是非空 manifest（样例仓 20 篇），风险低；最终统一验证批会覆盖。
7. **RED 证据的性质**：自旋用例用"3s 内不返回即失败"的探针线程证明（断言消息即缺陷描述）；RED 运行时该线程持续自旋，靠 daemon + surefire JVM 退出收尾，单跑/全跑都安全。
