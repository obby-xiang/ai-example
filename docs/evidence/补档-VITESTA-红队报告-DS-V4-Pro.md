> 来源=仓库外过程报告补档（2026-10-11，vitest 方案 A 批仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# vitest 方案 A 批 —— 红队对抗性审查报告

**执行者 = DeepSeek V4 Pro（反方审查 + 红队）**
**授权来源 = K3 决策卡（2026-10-11 K3 代签批准；工作方式 4/8：Kimi 系施工，DS 系红队）**

- 审查对象：`<REPO_ROOT>` 工作树未提交改动（恰 10 文件）
- 审查日期：2026-10-11
- 审查方式：只做推翻式审查，未改仓库任何文件、未 commit/push、未读 .env、未联网安装、未跑 mvn、未跑 yarn install（未动 node_modules）
- 机械活下沉：diff 重算、哈希核对、批量扫描、lockfile 反查均由脚本执行；本报告只整合关键判断

---

## 0. 锚定核对结果（开工第一步，阻断性）

| 核对项 | 实测 | 结论 |
|---|---|---|
| 锚文件 SHA-256 | `0acd466f8f59472cc1673637d129978fb7d3e0d7b4bb1bb1cec38a1647f4d4b1` | ✅ 与任务卡给定值一致 |
| 锚文件 HEAD | `4a37c401f834208a703bd3b452fbc13e37e8701b` | ✅ 与当前 `git rev-parse HEAD` 一致 |
| Section 1（`git status --porcelain=v1 -uall`） | 5 改 + 5 新，与锚文件逐行一致 | ✅ |
| Section 2（`git diff` 5 跟踪文件） | 重新生成，与锚文件字节级一致 | ✅ |
| Section 3（5 新增文件全文） | 重新生成，与锚文件字节级一致 | ✅ |
| **整体** | 重算 body（Section 1~3）64,319 字节，`diff` 输出**为空** | ✅ **BODY 完全一致** |

**锚定结论：哈希一致、HEAD 一致、body 字节级一致，无夹带、无掉包。开工有效。**

复现路径：
```bash
sha256sum <SCRATCH>/vitest-run/_redteam-anchor.diff   # = 0acd466f...
cd <REPO_ROOT> && git rev-parse HEAD   # = 4a37c401...
# 重算 body（porcelain + git diff + 5 新文件全文）后与锚文件 tail -n +8 逐字节 diff → 空
```

---

## 1. 发现清单（分级）

### B（阻断）
**无。**

### M（MAJOR）
**无。**

### m（minor）

**m1｜施工报告事实性误述：预置 alternation 键的对象写错（已由任务卡预告，红队独立复证）。**
- 落点：`<SCRATCH>/vitest-run/施工报告.md:18`（§1 表 #2）与 `:75`（§5 偏差#1）称在「`vite`、`@types/node` 两条既有条目」上预置 alternation 键。
- 实测：`git diff frontend/yarn.lock | grep -c '@types/node'` = **0**（`@types/node` 零改动，键仍为 `"@types/node@^22.10.2":` 单键）。实际被追加别名的 4 条键行是：
  - `"vite@^6.0.0 || ^7.0.0 || ^8.0.0", vite@^6.0.7:`（`yarn.lock:1996`）
  - `"@types/estree@1.0.9", "@types/estree@^1.0.0":`（`yarn.lock:485`）
  - `picomatch@^4.0.2, picomatch@^4.0.3, picomatch@^4.0.4:`（`yarn.lock:1616`）
  - `tinyglobby@^0.2.11, tinyglobby@^0.2.13, tinyglobby@^0.2.15:`（`yarn.lock:1917`）
- 判定：文档勘误，lockfile 本身正确（见方向 1），不动摇任何验收证据。与 GLM 复核 m1 结论一致。

**m2｜`deriveFrontendToolBudgetMs` 的「×1000 溢出 → 回落 60_000」防假超时分支无回归锁（红队新增，GLM 未点名）。**
- 落点：源码 `frontend/src/utils/frontend-tools.ts:239-243`（`serverBudgetMs` 为 Infinity 时判非法回落 `FRONTEND_TOOL_TIMEOUT_BUDGET_MS`），配套防线 `:279`（`TIMER_MAX_DELAY_MS`）与 `:290-295`（`normalizeBudgetMs` 收口）。
- 实测：`frontend/tests/tools-and-deadline.spec.ts:31-43` 的 `it.each` 回落组只覆盖 `undefined/null/0/-5/Number.NaN`，**未覆盖 `Infinity` 与 `1e307`**（`1e307*1000 === Infinity`）。`Number.NaN` 走的是 `Number.isFinite` 前置短路，与「有限值 ×1000 后溢出为 Infinity」是**两条不同分支**，后者正是源码注释点名的「`setTimeout(Infinity)` 被引擎钳到 ~1ms → 瞬间假超时」场景，当前**零测试覆盖**。
- 复现：`cd frontend && node -e "console.log(1e307*1000)"` → `Infinity`；对该值 `deriveFrontendToolBudgetMs` 返回 `60_000`（正确），但无任何用例锁住此行为。
- 判定：代码正确、注释充分，纯测试覆盖缺口；方向安全（宁缺不缺），建议补一行 `[1e307, 60_000]` 或 `[Infinity, 60_000]`。不阻断。

### O（观察）

**O1｜全锁文件经第三方镜像源解析（华为云，非本批引入）。**
- 落点：`frontend/yarn.lock` 全部 `resolved` 指向 `https://mirrors.huaweicloud.com/repository/npm/...`。
- 实测：HEAD 时 238 条、当前 318 条，主机分布**均为** `mirrors.huaweicloud.com`，**零 registry 切换**（非本批篡改面）。integrity 字段 318/318 齐备（`resolved` 行数 = `integrity` 行数 = 318，awk 反查「有 resolved 无 integrity」的条目数 = 0），且每条 `resolved` 另带 `#<sha1>` 分片，内容完整性不依赖镜像源信任。
- 判定：既有工程供应链姿态，非本批问题；integrity 完好可防镜像篡改。登记备查即可。

**O2｜`vitest.config.ts` 与 `vite.config.ts` 的 `@` 别名 / `@vitejs/plugin-vue` 为重复声明，漂移会「静默分叉」测试与构建解析。**
- 落点：`frontend/vitest.config.ts:6-7` vs `frontend/vite.config.ts:19-22`。
- 现状两者同形（`fileURLToPath(new URL('./src', import.meta.url))` + `vue()`，plugin-vue 同 `^5.2.1`）；存在 `vitest.config.ts` 后 vitest 不读 `vite.config.ts`，故重声明为必需。风险仅在未来一端改动未同步另一端时（测试绿 ≠ 构建绿 / 反之），系任务卡既定取舍。不阻断。

**O3｜tsconfig `include` 扩进 `tests/**` + `vitest.config.ts` 后，生产构建的类型门与测试文件类型正确性耦合。**
- 落点：`frontend/tsconfig.json:30`；`build` 脚本 = `vue-tsc --noEmit && node scripts/build.mjs`（`frontend/package.json:8`）。
- 实测：`yarn typecheck` exit 0，当前无破坏；但此后任一测试文件出现类型错误会直接阻断生产 `yarn build`（`vue-tsc` 跑全量 include）。系施工方声明的「与 src 同款 strict 口径」之有意结果，且 CI 全量安装 devDeps（vitest 类型可用）。方向安全（收紧），仅提示耦合存在。不阻断。

**O4｜`it.each` 未断言恰好 60s / 65s 两个边界值（语义与已测 61s 相同）。**
- 落点：`frontend/tests/tools-and-deadline.spec.ts:31-43`。
- 公式 `min(max(serverMs−60_000, 5_000), serverMs)` 下 `timeoutSeconds=60` 与 `65` 均返回 `5_000`，与已测的 `61`（→5_000）同落点，属低价值补测项。真正的缺口是 m2 的溢出分支，非此二边界。不阻断。

**O5｜`frontend` job 名称未涵盖单测步骤。**
- 落点：`.github/workflows/ci.yml:54` job 名仍为「前端类型检查与构建」。纯文案，与 GLM O4 同。

**O6｜本红队未重跑 `yarn build`。**
- 任务卡授权的独立验证命令为 `yarn test` / `yarn typecheck` / `check-docs`（均实跑通过）；`yarn build` 未在授权清单内且会写 `frontend/dist/`，故未重跑。其 `vue-tsc` 门已由 `yarn typecheck` exit 0 覆盖，`vite build` 产物形态已由 GLM 复核独立复现（built 26.31s、spreadjs chunk 6,198.64 kB 无异常膨胀）。本报告对「build ✓」的判断采信 GLM 独立复现 + 本红队 typecheck 复证，而非直接实跑。

---

## 2. 各方向审查方法 + 结论（方向 1~7 全覆盖）

### 方向 1｜供应链 / lockfile 篡改面 —— 无发现
审查方法：
- `git show HEAD:frontend/yarn.lock` vs 工作树，`grep -oE 'https?://[^/" ]+' | sort | uniq -c` 比对 resolved 主机分布（238 → 318，均为 `mirrors.huaweicloud.com`，**无 registry 切换**）。
- integrity 齐备性：`grep -c '  resolved '` = `grep -c '  integrity '` = 318；awk 逐块反查「有 resolved 无 integrity」= 0 条。
- 降级/改写：`git diff frontend/yarn.lock | grep '^-'` 仅 4 行删除，全部为「键行追加别名」形式的键行重排（vite / @types/estree / picomatch / tinyglobby），`version/resolved/integrity/dependencies` 零改动、零降级。
- alternation 键一致性：4 个追加别名均指向与既有单键**同一解析版本**（vite→6.4.4、@types/estree→1.0.9、picomatch→4.0.7、tinyglobby→0.2.17），不构成版本抬升或二次解析面。
- 攻击者视角：CI 用 `yarn install --frozen-lockfile`，lockfile 为定值解析；即便非 frozen 重装，追加的 `^` 区间键与既有 `^` 键同属标准 semver 语义，**不引入比既有锁文件更高的漂移风险**；vitest 4.1.11 peer `vite@^6||^7||^8` 由 6.4.4 满足。
结论：**无 B/M/m 级供应链问题**（O1 为既有镜像姿态观察，非本批）。

### 方向 2｜测试假绿面 —— 无发现
审查方法：三 spec 逐行对照源码手推；查空断言 / `.skip`/`xit`/`todo` / 误 catch / 计时器泄漏。
- 28 用例分布核对：ready 4 + sse-watchdog 4 + tools-and-deadline 20 = 28，与 `yarn test` 实测 `Tests 28 passed (28)` 一致。
- ready.spec ①~④ 逐条对 `SpreadGrid.vue` 四条结算路径（失败 `:187`、超时 `:139`、未挂载 `:148`、未就绪 `:195`）实测吻合；② 的 `pending.catch()` 为先挂 handler 防 unhandled rejection 噪声、再对**捕获值**断言，属正当用法，非吞异常假绿。
- sse-watchdog 4 例对 `sse.ts` 常量（`15_000`/`90_000`）与 48s 收敛账目（三轮 15s + 退避 1s/2s）手推吻合；`getTimerCount()===0/1` 均有 `clearIdle/clearConnect` 行为支撑。
- tools-and-deadline 20 例逐值手算全部吻合（详见方向 5）。
- 隔离与计时器：`vi.resetModules()` + 动态 `import()` 正确对冲组件模块级 `readyPromise`/看门狗（组件状态实为 per-mount，resetModules 为防御性冗余、无害）；`vi.useFakeTimers()` 均在 `afterEach` 恢复真实计时器，无跨用例泄漏；被测模块（SpreadGrid.vue / frontend-tools.ts / sse.ts / ai.ts / workspace.ts）真实导入，mock 仅打在引擎懒加载入口（`@/utils/spreadjs`，spreadjs.ts 本身是动态 `import()`，测试未拉 4.7MB 真实 chunk）与 `EventSource` 全局两处外部边界。
- flaky 面：所有时序由 fake timers 推进或微任务 `flush()` 确定性收敛；S3-R3 的 `Date.now()` 年份断言（`>2078`）为单调安全（常量 + `now` 递增，只增不减），不随机器/时区 flaky。
结论：**无假绿、无计时器泄漏、无确定性 flaky 面**。

### 方向 3｜CI 阻断有效性 —— 无发现
审查方法：读 `.github/workflows/ci.yml` frontend job 结构。
- `yarn test` 步骤位于 `yarn typecheck` 与 `yarn build` **之间**（`:78-81`），无 `continue-on-error`、无 `if: always()`、无 `shell` 覆写。
- 继承 job 级 `defaults.run.working-directory: frontend`（`:57-59`），与相邻步一致。
- GitHub Actions `run` 默认 shell 为 `bash -e`（errexit 开），`vitest run` 失败退出码非 0 → step 失败 → job 失败 → 后续 `yarn build` 不执行 → CI 红。vitest 28 用例存在（非空跑），`passWithNoTests` 不触发。
结论：**测试失败无任何路径仍绿**。O5 为 job 名文案。

### 方向 4｜配置漂移面 —— 无当前缺陷（O2/O3 为登记性观察）
审查方法：比 `vitest.config.ts` 与 `vite.config.ts`、`tsconfig.json` include 与 `package.json` build 脚本。
- 别名 / plugin-vue 双声明当前同形，漂移风险见 O2；`tests/**` 入 include 后 `vue-tsc`（build 内）全量受检，当前 exit 0，耦合见 O3。均无即时缺陷。

### 方向 5｜断言语义正确性（tools-and-deadline 20 例）—— 公式全对，一处覆盖缺口（m2）+ 两边界未锁（O4）
审查方法：`min(max(serverMs−60_000, 5_000), serverMs)` 手算逐值比对源码 `frontend-tools.ts:235-248`；`foldDeadline` 对 `stores/ai.ts:87-100`；`parseToolArgs` 对 `:145`；`executeFrontendTool` 超时文案对 `:411-425`。
- 11 条 it.each 全部吻合（120→60000、200→140000、61/30/5→5000、3→3000、undefined/null/0/-5/NaN→60000）；「预算恒 ≤ 上限」10 点抽样由 `Math.min(clampedMs, serverBudgetMs)` 结构性保证。
- foldDeadline 4 例吻合：正常帧 `now+(expiresAt−atMs)`；`atMs` 缺失/0/负 → 回落绝对 `expiresAt`（守卫 `atMs>0 && expiresAt>=atMs`）；三者皆无 → null；S3-R3 正小 atMs + epoch 级 expiresAt → 年份 >2078（登记在案的非密闭面，如实固定）。
- **负数区（serverMs<60_000）已覆盖**（30/5/3 三行）；**恰好 60_000/65_000 未锁**（O4，语义同 61）；**超大值溢出（1e307→Infinity→回落 60000）未覆盖**（m2，源码 `:239-243` 的防「瞬间假超时」防线无回归锁）。

### 方向 6｜命名纪律与脱敏 —— 无发现
审查方法：对 10 个改动文件做 `main[-_/. ]v2`、盘符 `[A-Za-z]:[\\/]`、用户名 `<USER>`、`<USER_HOME>` 全量扫描。
- **零真实命中**：`main+分隔符+v2` 组合、盘符、用户名均无。
- 说明（方法透明）：盘符正则 `[A-Za-z]:[\\/]` 命中全部来自 `http://` / `https://`（含华为镜像 URL）中 `p:/`、`s:/` 子串，为**正则误报**，非真实盘符泄漏；剔除后实际命中数为 0。
- 与 `check-docs` 断言⑤（`git ls-files` 全量，957 路径实扫 954）独立复跑 6/6 一致；新增文件 commit 前不在 `git ls-files` 覆盖内，红队已补位扫描零命中，commit 后自动纳入且必绿。

### 方向 7｜报告真实性 —— 无夸大，独立重跑全绿
审查方法：独立重跑 `yarn test` / `yarn typecheck` / `check-docs`（不转述施工/复核报告）。
- `yarn test`：**exit 0，`Test Files 3 passed (3)` / `Tests 28 passed (28)`**，ready 4 + sse-watchdog 4 + tools-and-deadline 20；Duration 1.26s（热缓存）。
- `yarn typecheck`：**exit 0**，`vue-tsc --noEmit` Done in 4.71s。
- `node scripts/ci/check-docs.mjs`：**exit 0，6/6**（①~⑥ 全 PASS，命名纪律扫 957 路径实扫 954）。
- 「28/28 绿」「typecheck ✓」「check-docs 6/6」**均真实**，无夸大；施工报告唯一事实性误述即 m1（已在两处登记/勘误）。

---

## 3. 结论

**通过（PASS）。**

- 锚定核对：哈希/HEAD/body 三重一致，无夹带，审查基准可信。
- 供应链：registry 未切换、integrity 318/318 齐备、零降级、4 条键行追加别名均指向同一版本，无解析到非预期版本的面。
- 测试：28/28 独立重跑绿，全为真实断言、无假绿、无计时器泄漏、无 flaky。
- CI：`yarn test` 步真阻断，无测试失败仍绿的路径。
- 配置：无即时缺陷，两项漂移/耦合风险为任务卡既定取舍（登记观察）。
- 命名/脱敏：零真实命中。
- 报告：无夸大，四项验收中三项由本红队独立重跑复证（build 采信 GLM 独立复现 + 本红队 typecheck 复证，见 O6）。

**0 B / 0 M / 2 m / 6 O。** 两项 m 均为文档/覆盖层面：m1 为施工报告勘误（已预告、红队复证，不动摇验收）；m2 为本红队新增的溢出分支回归锁缺失（建议补一行用例，方向安全）。

## 4. 对 GLM-5.3 复核结论的背书 / 反背书

**背书（PASS 一致）。** GLM 复核的 0B/0M/1m/6O 与本红队独立审查结论一致：改动恰 10 文件无夹带、四项验收可独立复现、三 spec 断言与源码逐值一致、等价改道等效、命名纪律零命中、无 vite 抬升 / 无依赖混入 / 无假绿。

**增量（不构成反背书）：**
- 红队独立复证 m1（`@types/node` 零改动、实际 4 键为 vite/@types/estree/picomatch/tinyglobby），与 GLM m1 完全一致。
- 红队新增 **m2**（`deriveFrontendToolBudgetMs` 溢出回落分支无回归锁），GLM 未点名，系覆盖缺口而非缺陷，不影响 PASS。
- 红队补记 **O1**（华为云镜像源为全仓既有姿态、integrity 完好可防镜像篡改），GLM 未列入，亦为观察级。

综上：**维持 GLM 的 PASS，无推翻、无部分推翻。** 唯一建议动作（非阻断）：在 `tools-and-deadline.spec.ts` 的回落组补 `[1e307, 60_000]` / `[Infinity, 60_000]` 一行，把源码里最重要的「防瞬间假超时」分支固化入库。
