# Task 7 — fix1 定向复审（仅 I1 / I2 / I3）

复审对象：`task-7-fix1-report.md` / `task-7-fix1.patch`（785 行，9 个文件）、对应日志与隔离副本源码
复审方式：只读。未复跑任何套件；用"证据链核验 + 增量补丁与源码逐字节比对 + 语义/并发静态审计"替代。
复审日期：2026-10-25

---

## 0. 结论

| 复审项 | 判定 | 一句话依据 |
|---|---|---|
| **I1** 重建永久失败不得每拍全量重建 | **ADDRESSED** | 同一版本预算 3 次 + 退避（`KbService.java:235-277`），用完静默交回 10m 定时刷新；新版本重置预算；WARN 节流不参与控制流 |
| **I2** 级联父值变化后裁剪非法子值 | **ADDRESSED** | 纯函数 `pruneCascadeValues`（`form-schema.ts:632-689`）接到 `setValue`（`FormRenderer.vue:557-566`）；不改入参、保留合法值、`sourceId` 不动；渲染器真实交互用例覆盖 |
| **I3** 两码中英 i18n | **ADDRESSED** | `zh-CN.json:351-352` / `en-US.json:351-352`，占位符一致、英文零 CJK；`issueText` 回落不再被触发 |
| **新增 Critical** | **无** | — |
| **新增 Important** | **无** | 候选项全部落在 Minor（见 §4） |
| **合并建议** | **合并（Merge）** | 无阻塞；3 条 Important 全部闭环，证据链可复核 |

复核对 10 条 Minor 与其它已接受语义**不做判定**（按指示），仅在其与本批改动相交处提一句。

---

## 1. 证据链核验（不重跑套件）

1. **补丁链正确**：临时仓（`core.autocrlf=true`）上 `main 的 7 个原件 → task-7.patch → task-7-fix1.patch`；`task-7-fix1.patch` 的 `git apply --check` 通过，应用后与 `<隔离副本>/_verify-20261025-form-kb` 的**12 个文件逐一 diff = 12/12 完全一致**（Task 7 的 7 件 + `FormRenderer.vue` + 两份 locale + 2 个新 spec）。因此日志描述的就是这份补丁，不存在"日志跑的另一版"。
2. **后端 RED**：`fix1-be-red.log` = `Tests run: 6, Failures: 1`，失败即 `persistentFailureIsBoundedPerVersionAndDoesNotRebuildOnEveryPoll`，`expected: 4 / but was: 16` —— 正是"20ms 轮询 × 300ms ≈ 15 拍全部回源"的热循环。
3. **后端 GREEN**：`fix1-be-green.log` = `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS`；分类 = KbLifecycle 6 + CachedKbSource 6 + KbLocalSource 5 + Incremental 5 + Index 12 = **34** ✓（= 1 新类 + 4 相邻类，符合"≤4 相邻类"上限；其中 `KbLocalSourceTest` / `CachedKbSourceTest` 是 Task 7 未跑的两个相关类，本批补上）。收尾复跑 `fix1-be-green-final.log` = KbLifecycle **6/6** ✓。
4. **前端 RED**：`fix1-fe-red.log` = `6 failed | 1 passed (7)` = cascade-prune 3 条全红（首条正是复审 I2 的用户症状 `expected [ 'CURRENCY' ] to deeply equal []`）+ i18n 3 条红；"键集/占位符一致"这条修复前就绿，报告已如实说明其防的是"只补一份"。
5. **前端 GREEN**：`fix1-fe-green.log` = `7 files / 60 tests`，逐文件 4+3+11+14+3+19+6 = **60** ✓（新增/扩充 18 = 11 + 3 + 4；相邻 42）。**口径订正**：不存在独立的"60+4"两跑，`4` 就是 `gf-errors-i18n` 的 4 条，已含在 60 里。
6. **类型检查**：`fix1-fe-tsc.log` 内容为 `exit=0`（7 字节）—— 复审 M9 的"日志须自证退出码"被采纳。
7. **时间线**：fe-red 20:11 → be-red 20:15 → fe-green/tsc 20:20 → be-green 20:23 → patch 20:23 → report 20:25，无"报告先于证据"。
8. **RED 与交付测试的一致性**：RED 栈行 `KbLifecycleRobustnessTest.java:143`，交付文件对应断言行在 `:146`；差 3 行，与补丁里**RED 后才加**的类级 javadoc（`+ *` / `+ * <p>fix1…` / `+ * 同一版本…` 共 3 行，位于 `:39-44`）**恰好抵消**。推断为"RED 后只补了注释"，断言文本与失败信息完全一致（`expected: 4 / but was: 16`，6 例 1 败）。报告"用例不变"严格说是"断言不变"，属表述略宽（见 M5），不影响 RED 结论。

---

## 2. I1 逐点验证（per-version 预算 / 退避 / 成功判据 / 定时与重载）

### 2.1 永久失败不会再"每拍全量重建"

- 预算常量与状态：`MAX_VERSION_REBUILD_ATTEMPTS = 3`（`KbService.java:132`）、`retryVersion` / `retryAttempts` / `retryNotBeforeNanos`（`:141-147`）。
- 轮询顺序（`KbService.java:235-270`）：`version < 0 || version == lastObservedVersion` ⇒ 退出 → **版本变化则重置预算** → `retryAttempts >= MAX` ⇒ 退出（**不碰源**）→ 退避窗口内 ⇒ 退出 → 才 `refreshQuietly()`。
- 所以同一版本的失败次数上限 = 1（`start()` 首刷，不计入预算）+ 3（轮询）= 4 次全量回源/重建；之后轮询每拍只剩一次 `cachedVersion()` 读取（廉价、无源调用）。测试断言 `attempts == afterBudgetExhausted` 覆盖约 15 拍（`KbLifecycleRobustnessTest.java:140-146`）。
- 预算用尽时有一条可行动 WARN（`:265-269`：版本号 + 次数 + 定时刷新间隔 + `lastError`），且 `status().lastError` 保留原因 ⇒ 健康面仍能看出"停在旧版本"。

### 2.2 退避 / 上限

- `backoffMillis(attempt) = min(max(poll, refreshInterval), poll × attempt)`（`:272-277`）：默认 30s → 30s/60s/90s，封顶 10m；只用既有两个配置，无新配置项。`retryNotBeforeNanos` 用 `System.nanoTime()`（单调），不受墙钟跳变影响。乘积累加在 `long` 内（≤10m × 1e6）不溢出。

### 2.3 退避/上限/节流"不挡成功、也不挡后续版本"

- **不挡后续版本**：`version != retryVersion` 分支先于预算与退避检查，且同时把 `retryNotBeforeNanos` 归零（`:243-248`）⇒ 新版本立即获得全新预算与零退避。测试 `aNewVersionGetsAFreshRetryBudget`（`:161-178`）在预算耗尽后 `version(2)` 立刻再触发，并最终 ready=20 篇。
- **不挡成功**：成功判据 `lastObservedVersion == version`（`:256`）。任一成功路径都会记版本（304 短路 `:307` 区、整份未变短路 `:343` 区、重建完成 `:356` 区），而 `CachedKbSource.fetchManifest` 在缓存可用时恒 `modified`（`syncLocal` 恒返回 `modified`；`refreshHttp` 恒走 `fromCache`→`modified`）⇒ 判据在缓存源上完备。成功即执行清零分支（`:258-261`）；即使预算中途耗尽，10m 定时刷新与 `POST /api/ai/kb/reload`（`AiKbController.java:45` 直接 `refreshNow(true)`）**都不经预算**，仍可成功。
- **节流不参与控制流**：`(consecutiveFailures-1) % 4 == 0` 只决定 WARN/DEBUG（`KbService.java:370-378`），`lastError` 与 `status()` 不受影响。

### 2.4 定时/重载语义是否"诚实"

报告 §4.1/§4.3 的三条声明与代码一致：预算只作用于版本轮询通道；退避值与 WARN 间隔数值可复算（1/5/9…；预算用尽后由 10m 定时刷新继续失败 ⇒ 约 40 分钟一条 WARN）。唯一需要读者自己补上的语义（报告在 §4.1 已写、§6.2 亦提示）：**预算用尽后同一版本只能等定时刷新/新版本/人工 reload**，因此"瞬时抖动 3 次后恢复正常"的最坏可见延迟 = 一个刷新间隔（10m）。这是 I1 要求的取舍，不是隐藏行为。

### 2.5 线程面

`retryVersion/retryAttempts/retryNotBeforeNanos` 只在 `kb-refresh` 单线程（轮询任务）读写（`:137-147` 注释已声明）；`refreshNow` 由定时线程与管理端点线程共用但 `synchronized`，且轮询在 `refreshQuietly()` 返回后经 `volatile lastObservedVersion` 读结果 ⇒ 无数据竞争。`consecutiveFailures` 为 `volatile`，最坏只影响 WARN 级别判定一拍。

---

## 3. I2 逐点验证（裁剪语义 / 不可变 / 合法与 sourceId 保留）

### 3.1 语义

- `pruneCascadeValues(spec, values, changedKey)`（`form-schema.ts:632-657`）：BFS 从被改字段出发，只处理 `optionsFrom.dependsOn === parent` 的子字段；**子值确实变了**才入队下探（`:645-651`）⇒ A→B→C 链式裁剪成立，且不会误伤"分支键没变"的下游。
- `prunedCascadeValue`（`:660-683`）：只处理 `enum` / `multi_select`；未填值原样返回；`resolvableOptions(...) === null`（`sourceId` 未解析、或无可枚举来源）⇒ **原样返回**（判不了就不猜）；enum：合法保留、非法清成 `''`；multi_select：保留仍合法的子集（空即 `[]`）。
- `sameValue`（`:685-689`）：数组逐元素比较，其余严格相等 ⇒ 无可避免的"每次改值都换新数组"抖动（合法多选不会被反复复制）。
- 渲染器接线（`FormRenderer.vue:557-570`）：先裁剪，再 `applyDefaultFrom`，再 emit draft。父值变化触发裁剪的唯一用户入口就是这里；`touched` 仍只标被改字段，未被用户碰过的子字段照旧可被 `defaultFrom` 补默认（顺序与既有语义一致）。

### 3.2 不改入参 / 不吞合法值

- `next = { ...values }`（浅拷贝），只在裁剪时才写子键；数组一律 `filter` 出新数组，未变则复用原引用（无原地修改）。纯函数用例 `gf-schema-robustness.spec.ts:196-206` 用快照比对入参不变。
- 保留类：`gf-schema-robustness.spec.ts:138-155`（只裁不合法部分、enum 合法保留）、`:156-183`（A→B→C）、`:185-194`（`sourceId` 不动）；渲染器真实交互 `gf-cascade-prune.spec.ts:67-115` 三条（陈旧多选不留在提交里、合法子集保留、合法值不误清）——第一条在 RED 期正是用户症状，说明用例确有鉴别力。

### 3.3 边界复核

- 父值被清空（`''`/`undefined`）⇒ 子选项解析为空集 ⇒ 子值被裁 ✓。
- 多选作为下一级父值：被裁成 `[]` 后 `String([]) === ''` ⇒ 下游同样解析为空集并被裁 ✓（用例未覆盖"多选父值"，但逻辑闭合）。
- 未填/空数组 ⇒ 不裁（`isFilled`）⇒ 不会把"没填"变成"报错" ✓。
- 不可见字段也会被裁：不可见字段不提交，裁剪不产生副作用；重新可见时其值本就已非法 ✓。
- `sourceId` 子字段在渲染器里由 `refreshSourceOptions` 另路取选项，裁剪刻意不动 ⇒ 不会把"数据源暂时取不到"误判成"用户填错" ✓（与 Task 7 的 null/空集口径一致）。
- **草稿恢复路径**（报告 §6.4 自认未覆盖）：实测该路径本来就不会带陈旧级联值 —— `initialModel()` 对 enum 只接受**静态 options** 内的值（`FormRenderer.vue:386-389`），对 multi_select 用静态 options 过滤（`:396-402`）；级联字段无静态 options ⇒ 恢复值必为 `''` / `[]`。故 §6.4 的"缺口"实际不可达，无需扩大本批范围（可在后续批顺手删除该悬念说明）。

---

## 4. I3 验证

- 键位正确：两码落在 `ai.gf.errors` 子树（`zh-CN.json:351-352`、`en-US.json:351-352`），`errors` 键集 23/23 一致（实测解析 JSON 后比对）。
- 覆盖率：`FIELD_STEP_INVALID`（步长）/ `FIELD_NUMBER_BOUND_INVALID`（数值范围）都补了，措辞与回灌期含义一致。
- 英文无中文回落：英文文案纯英文（`has an unusable …; please regenerate the form`），整个 EN `errors` 子树 CJK 命中 **0**（实测）。`FormRenderer.vue` 的 `te()` 回落路径对这两码不再生效。
- 回归钉子：`gf-errors-i18n.spec.ts:32-66` 三条 + 键集/占位符一致性；其中"英文不含中文"扫的是**整棵** errors 子树，能拦住今后再往英文里塞中文；"键集/占位符一致"防只补一份。RED 期 3/4 失败、第 4 条本就绿（两份同样缺键），报告已如实交代。

---

## 5. 本批引入的问题（仅 Critical/Important）

**无 Critical，无 Important。**

候选但判为 Minor（均不影响合并，且都已有披露或影响面极小）：

- **M1（可用性 ≤ 一个刷新间隔）** `KbService.java:249-251`：预算用尽后同一版本不再由轮询尝试，只会被 10m 定时刷新/新版本/人工 reload 带回。瞬时抖动 3 次后恢复的场景，最坏可见延迟 10m（改前 ≤30s）。方向是 I1 明确要求的取舍，报告 §4.1 已写明；若想再压一档，可在每次定时刷新后给该版本"再给一个预算"（仍是有界：3 次/10m）。判 Minor 而非 Important：不阻塞合成，且 `status().lastError` 始终可见。
- **M2（成功判据是代理量）** `KbService.java:256`：`lastObservedVersion == version` 在"刷新过程中别的实例 INCR 到更高版本"或"缓存刚恢复导致走 304 短路"时会把一次成功计成一次失败（最多 1 次/事件，随后 `version == lastObservedVersion` 自然退出）。后果仅为多消耗 1 个预算单位，极端情形下可能打出一条 `lastError` 为空的"暂停自动重试" WARN。可考虑以 `refreshNow` 的返回值（或专门的"本次是否成功"局部量）替代代理量。
- **M3（测试度量口径）** `KbLifecycleRobustnessTest.java:140-146`：`attempts` 计的是 `fetchManifest` 次数（失败注入点在 fetch），并非"索引重建次数"。它足以证明热循环消失（预热症状就是 fetch+重建同拍发生），但严格说"不再全量重建"是推断而非直测；若想更硬，可在 build 路径上加一个计数。
- **M4（常量重复）** `KbLifecycleRobustnessTest.java:50`：预算 3 在测试里写成字面量（为让 RED 在旧树上可编译，理由正当），但从此常量改 5 测试不会红。建议 GREEN 分支另加一条引用 `KbService.MAX_VERSION_REBUILD_ATTEMPTS` 的一致性断言。
- **M5（证据表述）** 报告 §3.1 "用例不变"严格说是"断言未变"：RED 后类级 javadoc 增了 3 行（行号 143→146）。建议后续证据说明写"断言未变"。
- **M6（裁剪的类型不对称）** `form-schema.ts:676-681`：enum 的**非字符串**子值在父值变化时会被清成 `''`（原本会留到类型校验），multi_select 的非数组值则原样保留。两者都无害，但口径不一致；可统一为"类型不符也保留，交给类型校验"。

---

## 6. 合并建议

**合并。** 三条 Important 均有 RED→GREEN 闭环、补丁与被测代码逐字节一致、证据数字自洽（后端 34、前端 60/7 文件、tsc exit=0），且本批**未引入新的 Critical/Important**。上面 6 条 Minor 可并入后续清理批；其中 M2、M3 建议随下一批一并处理（都属于"让成功判据与测试度量更贴近真实语义"的小改动）。

判定依据之外的提醒：本批复审只覆盖 I1/I2/I3 与"本批是否引入新问题"，不构成对 Task 7 全部十项 Minor 的关闭；那 10 条按报告 §6.6 仍未动。
