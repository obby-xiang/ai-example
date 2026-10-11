> 来源=仓库外过程报告补档（2026-10-11，vitest 方案 A 批仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# vitest 方案 A 施工复核报告（独立复现 + 对抗反查）

**执行者 = GLM-5.3（复核与测试）**
**授权来源 = K3 决策卡（2026-10-11 K3 代签批准，复核环节按工作方式 5 独立复现）**

- 复核对象：`<REPO_ROOT>` 工作树未提交改动（10 文件）+ `<SCRATCH>/vitest-run/施工报告.md` + `<SCRATCH>/vitest-prep/调研报告.md`
- 复核日期：2026-10-11
- 复核方式：全部清单项独立执行（未转述施工报告）；yarn.lock 深度分析（diff 结构/闭包可达性/混入反查）下沉子代理脚本化执行，关键判断由本报告整合
- **结论：PASS**（无 B/M 级问题；1 项 m 级为施工报告事实性勘误，不阻断；5 项 O 级观察）

---

## 0. 结论速览

| 清单项 | 结果 |
|---|---|
| 1. 改动恰为 10 文件、backend 零触碰 | ✅ |
| 2. 四项验收独立复现 | ✅ test 28/28（4+4+20）/ typecheck exit 0 / build exit 0 / check-docs 6/6 |
| 3. 三 spec 断言 vs 源码 | ✅ 全部一致（逐值核对见 §3） |
| 4. 等价改道等效性 | ✅ 成立（钉版 / lock 纯增量 / frozen-lockfile exit 0 / 实装三件套核对） |
| 5. 命名纪律 | ✅ 新增文件零命中（复核补位扫描，见 O1） |
| 6. vitest.config.ts 完备性 | ✅ |
| 7. ci.yml 插入位置 + Node 引擎地板 | ✅（R6 假设静态确认成立，见 O5） |
| 8. 施工报告合规 | ⚠️ 署名/佐证合规；有一处事实性误述（m1） |
| 9. 对抗反查（vite 抬升 / lock 混入 / 假绿） | ✅ 三项均未发现 |

## 1. 改动清单核对（清单项 1）

`git status --porcelain --untracked-files=all` 实测：

```
 M .github/workflows/ci.yml        M frontend/package.json      ?? frontend/tests/ready.spec.ts
 M .gitignore                      M frontend/tsconfig.json     ?? frontend/tests/setup.ts
 M frontend/yarn.lock                                          ?? frontend/tests/sse-watchdog.spec.ts
                                                               ?? frontend/tests/tools-and-deadline.spec.ts
                                                               ?? frontend/vitest.config.ts
```

- 5 改 + 5 新 = **恰 10 文件**，与预期清单逐一对应，无夹带。
- `backend/` 零触碰；`git diff --stat` 全部落在上述路径。
- `frontend/tests/` 下恰 4 文件（ready.spec.ts / setup.ts / sse-watchdog.spec.ts / tools-and-deadline.spec.ts），无多余文件。
- `frontend/src/` 下无散落 spec（全量 grep 零命中），测试文件全部收敛于 `tests/`。

## 2. 四项验收独立复现（清单项 2）

完整输出存档于 `<SCRATCH>/vitest-run/_review-frontend-acceptance.log` 与 `_review-check-docs.log`。

| 命令 | 实测结果 | 关键输出 |
|---|---|---|
| `cd frontend && yarn test` | **exit 0，28/28** | `Test Files 3 passed (3)` / `Tests 28 passed (28)`；ready 4 + sse-watchdog 4 + tools-and-deadline 20，与预期分布一致；Duration 1.29s（施工报告 30.99s 为其本机杀软冷缓存口径，复跑热缓存秒级，无碍） |
| `yarn typecheck` | **exit 0** | `vue-tsc --noEmit` Done in 4.64s，无错误 |
| `yarn build` | **exit 0** | `vite v6.4.4 building for production` ✓ 1747 modules、`✓ built in 26.31s`；spreadjs chunk 6,198.64 kB 与施工报告及既有形态一致，无异常膨胀 |
| 仓库根 `node scripts/ci/check-docs.mjs` | **exit 0，6/6** | 断言①~⑥全 PASS；命名纪律扫 git ls-files 957 路径实扫 954、规则自检 5/5 + 24/24 + 负对照 11 全不误伤 |

## 3. 三 spec 断言 vs 主仓源码（清单项 3）

### 3.1 tools-and-deadline.spec.ts 六处改法逐值核对

源码实读 `frontend/src/utils/frontend-tools.ts`：

| 依据 | 源码落点 | spec 断言 | 核对 |
|---|---|---|---|
| `FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000` | `frontend-tools.ts:192` | `toBe(60_000)`（spec :26） | ✅ |
| `FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000` | `frontend-tools.ts:201` | `toBe(60_000)`（spec :27）；和 = 120_000（:28） | ✅ |
| `MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 5_000` | `frontend-tools.ts:209` | 经 it.each 间接覆盖 | ✅ |
| 折算式 `min(max(serverMs−60_000, 5_000), serverMs)` | `frontend-tools.ts:235-248` | 见下行逐值 | ✅ |
| 非法/缺失回落 60_000 | `frontend-tools.ts:236-243`（非 number / ≤0 / 非有限 / ×1000 溢出均判非法） | 五行回落 60_000 | ✅ |

it.each 逐值实算（公式手算复核）：

| 输入 | 实算 | spec 期望 | 核对 |
|---|---|---|---|
| 120 | 120000−60000=60000 | 60_000 | ✅ |
| 200 | 200000−60000=140000，min(140000,200000)=140000 | 140_000 | ✅（"不再封顶"语义与源码 :223、:264-266 注释一致） |
| 61 | 61000−60000=1000 → max(5000,1000)=5000 | 5_000 | ✅ |
| 30 / 5 | 负值/等值均落 5s 下限 | 5_000 / 5_000 | ✅ |
| 3 | min(5000,3000)=3000（上限优先防倒挂，源码 :228-229） | 3_000 | ✅ |
| undefined/null/0/-5/NaN | 全部落入非法分支 → 60_000 | 60_000 | ✅ |

- 「预算恒不超过服务端上限」对 1..600：由 `Math.min(clampedMs, serverBudgetMs)`（`frontend-tools.ts:247`）结构性保证，spec :47-51 的 10 点抽样成立。
- `parseToolArgs`（`frontend-tools.ts:145`）：合法 JSON 解析 / 非法与 undefined 回落 `{}`，与 spec :76-80 一致。
- `executeFrontendTool`（`frontend-tools.ts:315`）：预算到点产出 `{ ok:false, availability:'degraded', result }`，result 含「前端超时」与「结局未知」（`frontend-tools.ts:418-425`）——spec :55-65 断言逐字命中；`registerPageHandler/unregisterPageHandler` 确为 `stores/workspace.ts:268/272` 导出。
- `foldDeadline`（`stores/ai.ts:87-100`）：四例逐值手算全部吻合——正常帧 `now+(expiresAt−atMs)`；`atMs` 缺失/0/负 → 回落绝对 `expiresAt`（守卫 `atMs>0 && expiresAt>=atMs`，`ai.ts:94`）；三者皆无 → null；S3-R3 膨胀面（正小 atMs + epoch 级 expiresAt → 年份 >2078）为登记在案的非密闭面，spec :98-104 如实固定。

### 3.2 ready.spec.ts（4 例）

对照 `frontend/src/components/SpreadGrid.vue` 实测：

- `READY_TIMEOUT_MS = 60_000`（`SpreadGrid.vue:104`）→ spec ② 推进 60_000ms 后断言「表格引擎加载超时」✅（文案源 `:139`）。
- 四条结算路径文案逐一在源码命中：加载失败 `:187`、加载超时 `:139`、未挂载 `:148`、未就绪 `:195` → spec ①/③ 断言的「表格引擎加载失败」「chunk 拉取失败」、`/卸载|未就绪/` 均有真实来源 ✅。
- `emit('ready')`（`SpreadGrid.vue:173`）→ spec ④ `wrapper.emitted('ready')` ✅。
- `vi.resetModules()` + 动态 `import('@/components/SpreadGrid.vue')` 对冲模块级 `readyPromise`/看门狗；mock 只打在 `@/utils/spreadjs` 引擎懒加载入口（4.7MB chunk），**被测组件本体未 mock** ✅。

### 3.3 sse-watchdog.spec.ts（4 例）

对照 `frontend/src/api/sse.ts` 实测：

- `DEFAULT_IDLE_TIMEOUT_MS = 90_000`（`sse.ts:22`）、`DEFAULT_CONNECT_TIMEOUT_MS = 15_000`（`sse.ts:43`）✅。
- 48s 收敛账目：三轮建连 15s + 退避 1s/2s（`DEFAULT_RECONNECT_DELAY_MS = 1_000`，`sse.ts:28`；指数退避 `baseDelay * 2^(attempts-1)`）= 48s，与源码注释 `sse.ts:38` 逐字一致；spec 传 `maxReconnects: 2` 与 `DEFAULT_MAX_RECONNECTS = 2`（`sse.ts:26`）同口径，推进 47s 不结算、48s 结算 ✅。
- 收尾文案：重挂耗尽走 `settle(state.terminal, '${reason}（重挂已尝试 N 次）')`，reason 含「重挂连接建立超时」（`sse.ts:519`）→ spec 断言 `terminal===false`、reason 含「建立超时」✅；abort 走「连接已取消」（`sse.ts:531`）→ spec 断言「取消」✅。
- 「settle 后无残留计时器」（`vi.getTimerCount()===0`）与「onopen 后只余帧间看门狗」（===1）均有源码行为支撑（退避/收尾路径 `clearIdle()+clearConnect()`，`sse.ts:527-528`）✅。

## 4. 等价改道核验（清单项 4）

| 核验点 | 实测 | 结论 |
|---|---|---|
| package.json 三件套钉版 | `"@vue/test-utils": "2.5.1"`、`"jsdom": "30.1.2"`、`"vitest": "4.1.11"`（diff 实读），无 `^`/`~` | ✅ 等价 `--exact` |
| yarn.lock diff 结构 | 551 增 / 4 删；**零个已有条目的 version/resolved/integrity/dependencies 被改动，零删除**；4 条删除行全部是键行以「追加别名」形式重现（见 m1）；新增行全部落在 80 个新块内（行数配平自证：467+4+80=551） | ✅ 纯增量 |
| 条目数 | HEAD 238 → 当前 318（键行口径复现） | ✅ +80 |
| `yarn install --frozen-lockfile` | 复跑 exit 0（`success Already up-to-date`） | ✅ CI 口径无虞 |
| node_modules 实装 | `vitest=4.1.11 jsdom=30.1.2 @vue/test-utils=2.5.1`（node -e 实读） | ✅ |
| yarn 真实接受性 | `.yarn-integrity` 的 362 条 lockfileEntries 与当前 lockfile 362 个别名串**集合完全一致**（含 `vitest@4.1.11`、`"vite@^6.0.0 \|\| ^7.0.0 \|\| ^8.0.0"→6.4.4` 等手写键）——真实 `yarn install` 已按手写 lockfile 运行且未改写任何条目 | ✅ 强证据 |
| alternation 键对齐 | 键行与 package.json 声明、新条目内部 dep spec 逐字对齐（`yarn.lock:1996/2027/2031/2033`） | ✅ |

**等效性判定：成立。** 手写路径达成了 `yarn add -D --exact` 的全部验收语义（钉版、lock 同步、frozen-lockfile 通过、实装版本正确），且比 `yarn add` 多出的差异仅为一处无害的双版本共存（O3）。

## 5. 命名纪律（清单项 5）

对全部 5 个新增文件 + 4 个改动文件（ci.yml / .gitignore / package.json / tsconfig.json）+ yarn.lock diff 增量做 `main[-_. ]?v2 | main⟨无分隔⟩v2` 任意大小写扫描：**零命中**。

注意（见 O1）：check-docs 断言⑤只扫 `git ls-files`（已跟踪文件），本批新增文件在 commit 前不在其覆盖内——复核已补位扫描，commit 后该断言将自动覆盖这批文件且必然绿。

## 6. vitest.config.ts 完备性（清单项 6）

`frontend/vitest.config.ts:1-13` 实读：

- 存在 `vitest.config.ts` 后 vitest 不读 `vite.config.ts`（vitest 文档化优先级），故需自声明的两项均已重声明：`@vitejs/plugin-vue`（`:6`）与 `@` → `./src` 别名（`:7`，与 `vite.config.ts:19-22` 同形）✅。
- `environment: 'jsdom'`（`:9`）、`include: ['tests/**/*.spec.ts']`（`:10`）、`setupFiles: ['./tests/setup.ts']`（`:11`）✅。
- 不设 `globals`，三 spec 均 `import { describe, expect, it, vi } from 'vitest'` 显式导入 ✅。
- `vite.config.ts` 的 server.proxy / build.rollupOptions 为 dev 与打包专用，单测不需要，未搬运属正确取舍（别名/插件重复声明为任务卡既定取舍，漂移会自曝）。

## 7. ci.yml 插入位置 + Node 引擎地板（清单项 7）

`.github/workflows/ci.yml` frontend job（`:53` 起，`defaults.run.working-directory: frontend`）实测：

- `yarn test` 步骤（`:80-81`）位于「类型检查（vue-tsc --noEmit）」（`:76-77`）与「生产构建」（`:83-84`）**之间** ✅。
- 阻断（run 步骤默认 fail-fast，无 `continue-on-error`）✅；未新立 job ✅；working-directory 沿用 job 级 defaults，与相邻步一致 ✅。
- **R6 遗留【假设】静态确认**：ci 用 `node-version: '22'`（`:67`），jsdom 30.1.2 引擎地板 `^22.22.2 || ^24.15.0 || >=26.0.0`（node_modules/jsdom/package.json 实读）。npm registry 实查 Node 22 线最新已至 **22.23.3 ≥ 22.22.2**，`setup-node` 的 `'22'` 解析为最新 22.x，满足地板；且 yarn 1 对依赖 engines 仅告警不阻断，运行时亦不强制。**假设成立，可销账**（见 O5）。

## 8. 施工报告合规（清单项 8）

- 首页署名「执行者 = Kimi K2.8 Preview（主力编码）」+「授权来源 = K3 决策卡」✅（`施工报告.md:3-4`，符合 DC-19）。
- 「已完成」断言均有佐证：四项验收输出摘录（§3）、六处改法对照表（§4）、偏差台账（§5）齐备；本复核独立复现与之一致。
- **一处事实性误述**：见 m1。

## 9. 对抗反查（清单项 9）

| 反查项 | 手段 | 结果 |
|---|---|---|
| vite 是否被间接抬升 | HEAD vs 当前 lockfile：vite 均解析 **6.4.4**（node_modules 实装同）；`@vitejs/plugin-vue` 5.2.4、vue 3.5.43、rollup 4.64.0、esbuild 0.25.12 均逐字节不变；HEAD 中全部旧 dep spec 的解析版本变化数 = **0** | ✅ 未抬升 |
| yarn.lock 是否混入非本批依赖 | 以三件套为根做 dependencies+optionalDependencies 闭包 BFS：闭包 146 条、未解析 spec 0；**80 个新条目 100% 落在闭包内**，孤儿条目 0；babel/eslint/postcss 插件/构建链路包匹配为空集；反向以 package.json 全部 22 个声明为根做闭包恰覆盖 318 条全部键行 | ✅ 无混入 |
| 测试是否假绿 | 三 spec 逐行审查：无空断言、无 `.skip`/`xit`/`todo`、无吞异常的误 catch（ready.spec ② 的 `pending.catch()` 是先挂 handler 防噪音后**对其值做断言**，正当用法）；被测模块（SpreadGrid.vue / frontend-tools.ts / sse.ts / ai.ts / workspace.ts）均真实导入未 mock，mock 仅打在引擎懒加载与 EventSource 全局两处外部边界；超时路径用 fake timers 推进后 `await` 真实 promise，悬挂会表现为测试超时而非通过 | ✅ 无假绿 |
| +80 vs 380~450 去重解释 | 闭包量化对账：146 = 62 复用 HEAD 已有条目（几乎全为 esbuild/rollup/postcss 等 vite 工具链）+ 4 别名预置 + 80 真新增，账目闭合 | ✅ 解释成立；380~450 系推断口径本身高估（O2） |

## 10. 问题清单（分级）

### B（阻断）
无。

### M（MAJOR）
无。

### m（minor）

- **m1｜施工报告事实性误述：预置 alternation 键的对象写错。** `施工报告.md:18`（§1 表 #2）与 `施工报告.md:75`（§5 偏差 #1）均称在「`vite`、`@types/node` 两条既有条目」上预置 alternation 键。实测 `git diff -- frontend/yarn.lock` 中 **`@types/node` 零改动**（diff 内 grep 无命中）；实际被追加别名的 4 条键行是：`"vite@^6.0.0 || ^7.0.0 || ^8.0.0"`（`yarn.lock:1996`）、`"@types/estree@^1.0.0"`（`:485`）、`picomatch@^4.0.3`（`:1616`）、`tinyglobby@^0.2.15`（`:1917`）。lockfile 本身经独立验证无误（见 §4），此为报告描述错误，不影响验收结论；建议提交官以本复核报告为准，或在 commit 前更正施工报告该两处表述。

### O（观察）

- **O1｜check-docs ⑤ 对未跟踪文件不生效。** 断言⑤扫 `git ls-files`（957 个已跟踪路径），本批 5 个新增文件 commit 前不在覆盖内。复核已补位扫描零命中（§5），commit 后自动纳入且必绿。无需行动。
- **O2｜“预期 380~450 条”推断值本身高估。** 三件套在本仓解析空间下的整个闭包仅 146 条，380~450 ≈ 其 2.6~3.1 倍；即便全量重装整套 package.json 也只落在 ≈318 条量级。施工报告的去重解释**方向正确且可精确对账**（62 复用 + 4 别名 + 80 新增），偏差根因是调研推断口径（可能计入全新 lockfile 总行数或 npm 式非去重树），非施工缺漏。建议调研侧销账时修正该推断方法。
- **O3｜vue-component-type-helpers 双版本共存指纹。** 当前 lockfile 同时存在 `@^3.0.0→3.3.13`（新，@vue/test-utils 链，`yarn.lock:2041`）与 `@^3.3.11→3.3.12`（HEAD 原有，element-plus 链，`:2046`）。3.3.12 已满足 `^3.0.0`，真实 `yarn add` 会合并进已有键行；手写条目按 registry 最新解析出 3.3.13，树里多一份重复版本（node_modules 实测两份并存）。纯类型 dev 辅助包，无功能影响；但严格说 80 条新条目中至少这 1 条不是 `yarn add` 的精确指纹。无需处理，登记备查。
- **O4｜frontend job 名称未涵盖测试。** `ci.yml:54` job 名仍为「前端类型检查与构建」，现含单测步骤。纯文案，可在下次顺手更正。
- **O5｜Node 引擎地板满足方式为“浮动 latest”语义。** `node-version: '22'` 依赖 setup-node 解析最新 22.x（当前 22.23.3 ≥ 22.22.2 地板）。由于 22.23.3 已发布且 22 线只升不降，该假设**已静态成立**；若求绝对确定可钉 `'22.22.2'` 或升 `'24'`，非必需。
- **O6｜测试时长口径差异。** 施工报告 test Duration 30.99s（其本机杀软冷缓存）vs 复跑 1.29s（热缓存）。环境差异，无实质影响；CI（ubuntu-latest）预计秒级。

## 11. 结论

**PASS。**

- 改动恰为预期 10 文件，backend 零触碰，无夹带。
- 四项验收独立复现全绿（28/28 / exit 0 / exit 0 / 6/6）。
- 三 spec 断言与主仓当前源码逐值一致，被测模块真实导入、无假绿。
- 等价改道（手写钉版 + lock 预置 + yarn install）与 `yarn add -D --exact` **等效**：钉版精确、lock 纯增量零改写、frozen-lockfile 通过、实装版本核对无误、`.yarn-integrity` 佐证 yarn 真实接受。
- 对抗反查三项（vite 抬升 / 依赖混入 / 假绿）均未发现问题。
- 唯一 m 级问题为施工报告对“预置 alternation 键对象”的误述（m1），系文档勘误，不动摇任何验收证据；建议以本复核报告 §4/m1 为准。
- R6 遗留假设（ci Node 22 满足 jsdom 30.1.2 引擎地板）静态确认成立，可销账。

复核过程产物：`<SCRATCH>/vitest-run/_review-frontend-acceptance.log`（四项验收完整输出）、`<SCRATCH>/vitest-run/_review-check-docs.log`（check-docs 输出）。
