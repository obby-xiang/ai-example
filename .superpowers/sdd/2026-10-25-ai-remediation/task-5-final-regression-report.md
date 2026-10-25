# Task 5 · 末次全量回归 5 FAIL 归因报告（前端失败归因 · 最小修复）

**结论：5 条失败全部是「用例内首次 import / 首次装配的一次性成本撞上单用例 timeout」这一类，外加 1 条由此派生的
测试隔离级联失败；生产代码零缺陷、零改动。** 生产 diff = **空**。仅附一个**仅测试侧**的可选预热补丁
（不动断言、不动 timeout、不动 vite/vitest 全局配置），用于把这 5 条（含预检里的 `truncated-answer.spec.ts`）从
"首条必超时"变成"首条 10–160ms"。

- 基线：main 工作区（`2eb2539` + 全部未提交在途改动），未提交、未推送、主仓源码逐字节未改。
- 工作副本（隔离）：`<隔离副本>/_p2-attrib`（robocopy 自 main 工作区，含 `frontend/node_modules`；
  收尾核对：`frontend/src/stores/ai.ts`、`frontend/vitest.config.ts` 与 main 工作区**逐字节一致**）。
- 全量失败日志：`<仓外证据>/final-frontend-full-rerun.log`（145 files / 1666 tests，5 failed）。
- 可选补丁：`.superpowers/sdd/2026-10-25-ai-remediation/task-5-final-regression.patch`
  （87 行 / 5006 B，`md5 c26abf3183fbc2063ba6ac8f0a2982f6`，5 个 spec 文件、**无**生产文件）
- 证据日志目录：`<隔离副本>/_p2-attrib/_p2-logs/`（下表逐项给出文件名）

---

## 0 一句话归因表

| # | 失败用例 | 表面现象 | 真实根因 | 是否生产回归 | 证据等级 |
|---|---|---|---|---|---|
| 1 | `restore-reconcile.spec.ts` 负例① | 15000ms 超时 | 文件内**首条**用例在 `freshStore()` 里付 `@/stores/ai` 的首次 transform+装配（空载 1.9s，满载十几秒） | 否 | A |
| 2 | `restore-request-emitted.spec.ts` 一批三卡 | 15000ms 超时 | 同上（同文件首条） | 否 | A |
| 3 | `rg-s13-s14-stopped-label.spec.ts` 挂起中 stop | 5000ms 超时 | 同上（首条；空载 1.9–3.1s，预算 5s，余量仅 ~1.6×） | 否 | A |
| 4 | 同文件「迟到的 done{cancelled} 不得抹掉已停止」 | `messages[1].stopped === undefined` | **不是语义回归**：其为 #3 的**级联**——#3 在 import 上超时后，被放弃用例的续体在第 2 条用例期间恢复，两条 `freshStore()` 落到**同一个 pinia store 实例**上（`stopping=true`/`stopped=true`/`activeRunId='run-a'` 是上一条用例的残留），第 2 条的 `stop()` 命中 `if (this.stopping) return` 提前返回，从未打标 | 否（测试隔离缺陷） | A− |
| 5 | `truncated-answer-panel.spec.ts` 中文徽标 | 5000ms 超时 | 文件内**首条**用例付 `AiPanel.vue` 的首次编译+装配（空载 3.38s，预算 5s，余量仅 ~1.5×） | 否 | A |
| 6（预检遗留） | `truncated-answer.spec.ts` | 5s 超时（本次全量已 6/6 通过） | 同 #1/#2（首条付 store 首次 import） | 否 | A |

生产侧无任何一条需要改代码。**`stop()` 的「已停止」打标逻辑经插桩证明是正确的**（见 §3.4 trace）。

---

## 1 复现方法（为什么不能都叫"负载"）

对每条都做了**单变量**复现，而不是拿"机器忙"当解释：

1. **同代码、单文件隔离跑**：4 个失败 spec 全部 4/4、8/8、4/4、3/3 通过 —— 证明失败与代码状态无关，
   只是**同一份代码在不同成本条件下**表现不同。日志：`green-isolated-<spec>.log`。
2. **文件内对照**：同文件第 2 条用例走**完全同一条代码路径**，耗时 10–40ms（全量日志同位）——
   首次成本只落在"第一条"身上，不是被测逻辑慢。
3. **单变量 RED 复现**：在隔离副本的 `vitest.config.ts` 里加一个**只在首次** transform 时 sleep 的插件
   （不动业务代码、不动 spec、不动 timeout），把"首次 import 成本"这一个变量调到超阈值，
   **1:1 复现了全量日志的失败签名**（包括"首条超时、同文件其余用例照常通过"以及
   `restore-reconcile` 负例② 3.0s / `restore-request-emitted` 第 2 条 3.0s ——与全量日志的 2636ms / 2514ms 同型）。
4. **同条件 GREEN**：同一 sleep 插件下，换成带收集期预热的 spec 副本，全部通过（§4）。

### 1.1 全量日志里的"指纹"

```
restore-reconcile  首条 × 15223ms (预算 15000)  次条 ✓ 2636ms  其余 ✓ 51/39ms
restore-emitted    首条 × 15281ms (预算 15000)  次条 ✓ 2514ms  其余 ✓ 109/39/38/42/21/24ms
rg-s13             首条 × 14950ms (预算 5000)   次条 × 2501ms  第3条 ✓ 1206ms  第4条 ✓ 20ms
truncated-panel    首条 × 15354ms (预算 5000)   次条 ✓ 3759ms  第3条 ✓ 35ms
```

四条失败全是**文件内首次被执行的用例**；同一份代码在历史全量里既红过也绿过：
`_a-toolsfe.log` / `_a-toolsfe2.log` / `_b3e-agent-full-vitest.log` / 主仓 `frontend/test-results/vitest-vl.log`
（同 2 条红） vs `_a-toolsfe3.log` / `_b3c-fe-full3.log` / `_b3d-commit/vitest.log` / `vitest-run2.log`（全绿）。

### 1.2 机械原因（为什么"第一条"贵）

这些 spec 每个用例内部都走 `vi.resetModules()` + `await import('@/stores/ai')`（或 `AiPanel.vue`）。
Vite 的 **transform 结果按模块 id 进程内缓存**，`resetModules` 只清实例缓存不清 transform 缓存：
- 首次 import = transform（~5.5k 行 store 及其图） + 首次装配 → 空载 1.9–3.4s；
- 之后每次 import = 仅重执行 → 10–40ms。
全量日志的汇总行印证了这一层：`import 449.51s / environment 539.25s`（145 文件并行时同样的 transform 被 CPU 争用拉长数倍）。
这一条成本**落在第一个用例自己的 timeout 预算里**，所以它先超时；与其说是"负载把断言跑坏了"，不如说是
"**一次性装配成本被记在了一个正常只有毫秒级预算的用例头上**"。

### 1.3 当前 diff 是否加重了这条成本（A/B）

把隔离副本的 `stores/ai.ts` 分别替换为 **HEAD 版本**与**main 工作区版本**，各跑一次 `rg-s13`：
首条用例 2321ms(HEAD) vs 1947ms(工作区)（另一次对照 2790ms vs 3106ms）—— 差异在噪声内、方向还相反。
结论：在途 diff（+269 行 store 改动）**不是**首条成本上升的原因。日志：`ab-store-head.log` / `ab-store-worktree.log`。

---

## 2 逐项 RED / GREEN

RED = 单变量插件把"首次 transform"拉长到超阈值；GREEN = 同条件 + 收集期预热（§4）。

| # | spec | RED（日志） | GREEN（日志） |
|---|---|---|---|
| 1 | restore-reconcile | 负例① × 15023ms（预算 15s），负例② ✓ 3027ms `red-slowfirst-restore-reconcile.log` | 4/4 ✓，首条 17–20ms `green-patched-slowfirst-16s.log` |
| 2 | restore-request-emitted | 一批三卡 × 15012ms，第 2 条 ✓ 3040ms `red-slowfirst-restore-emitted.log` | 8/8 ✓，首条 18–19ms 同上 |
| 3 | rg-s13 挂起中 stop | × 5018ms（预算 5s）`red-slowfirst-rg-s13.log` | 4/4 ✓，首条 21–24ms `green-patched-slowfirst-7s.log` |
| 4 | rg-s13 停止标记 | × `expected undefined to be true`（4141ms，被 #3 超时级联）同上 | ✓ 12–14ms 同上 |
| 5 | truncated-answer-panel | 中文 × 5013ms `red-slowfirst-truncated-panel.log` | 3/3 ✓，中文 70–81ms `green-patched-slowfirst-7s.log` |
| — | 低负载单文件基线（未打补丁） | — | 4/4、8/8、4/4、3/3 全绿 `green-isolated-*.log` |

---

## 3 第 4 条：为什么它不是语义回归（证据链）

这条是唯一一条"断言失败"，必须单独钉死。

### 3.1 生产逻辑本身是对的（插桩 trace）

在隔离副本的 `stores/ai.ts` 里临时插了两行 trace（收尾已还原，`cmp` 与 main 工作区逐字节一致）：

```
[stop-enter] stopping=false loading=false suspended=true stopped=false runId=run-s13b hadRound=true ids=[u-s13b, a-s13b]
[mark]       activeRoundHostId=null lastId=a-s13b content="" kinds=["tool"] hasBody=true
```

即 `markStoppedMessage()` 判定 `hasBody=true`（工具段成立，不要求正文）、打标对象正是 `messages[1]`。
独立跑该用例（`-t` 单条）单独通过 2091ms；带预热副本通过 12–14ms。**「已停止」语义在现网代码里成立。**

### 3.2 失败时的现场（同一 trace 在 RED 条件下）

RED 条件下第 2 条用例的现场是：

```
[PROBE-B] freshStore 3932ms; same-as-A? true
          state={"suspended":false,"loading":false,"stopping":true,"stopped":true,"activeRunId":"run-a"}
[PROBE-B] stop 1ms; right after stop: [{id:u-s13b},{id:a-s13b}]   ← 没有任何 stop-enter/mark trace
```

- `same-as-A? true`：第 1 条（已超时）与第 2 条两次 `freshStore()` 最终落到**同一个 store 实例**；
- `stopping:true / stopped:true / activeRunId:'run-a'`：state 带着**上一条用例**的残留（第 2 条 `freshStore()`
  明明把这些字段重置过，随后被第 1 条迟到的续体覆盖）；
- 第 2 条 `stop()` 1ms 返回、**没有**任何 trace ⇒ 命中 `stop()` 首行 `if (this.stopping) return`，直接早退，根本没有打标。
- 第 1 条迟到的续体确实在跑（trace 里出现 `runId=run-a / ids=[u-a,a-a]` 的 stop-enter+mark，时间在第 2 条之后）。

### 3.3 触发条件

只有当"第 1 条用例在 `await import('@/stores/ai')` 期间就已经超时"时才会发生：被放弃的用例不会停，
它的 `freshStore()` 续体在后面的用例里恢复，与后一条用例的 `freshStore()` 争同一份 pinia/模块实例。
单变量插件把首次 transform 拉到 7s（>5s 预算）时稳定复现；拉到 20s 时（后一条也等不到 transform 而双双超时）
则不会出现这条断言失败。

### 3.4 结论

`stopped` 丢失是**测试夹具的隔离级联**（`vi.resetModules()` + 超时后续体 + 单实例 pinia），
不是 `stop()` / `markStoppedMessage()` 的语义缺陷；不满足"改动生产代码"的条件。

**证据等级：A−**。A 的证据是"确定性 RED→GREEN + 单变量"；减一档的原因：完整复现依赖单变量插件构造的
"首条在 import 上超时"这一时序，真实全量里只能观测到最终断言，观测不到同一时刻的实例同一性
（全量日志不含插桩）。

---

## 4 可选最小修复：仅测试侧「收集期预热」

**不提高 `testTimeout`、不改任何断言/口径、不改 `vitest.config.ts`、不改生产代码。**
每个 spec 在 `vi.mock('@/api/ai', …)` 之后加 2–3 行模块级 import，把"首次 transform + 首次装配"从
**用例计时窗口内**挪到**收集期**（收集不受单用例 timeout 约束）：

```ts
// 收集期预热：……（注释说明原因与实测数据）
import '@/stores/ai'
import '@/components/AiPanel/AiPanel.vue'   // 只有挂载面板的两个 spec 需要
```

- 覆盖文件（5 个）：`restore-reconcile.spec.ts`、`restore-request-emitted.spec.ts`、`truncated-answer.spec.ts`、
  `rg-s13-s14-stopped-label.spec.ts`、`truncated-answer-panel.spec.ts`
- 效果（同条件对照）：首条 1.9–3.4s → **10–160ms**；5 个 spec 合跑 `tests 818–864ms`（此前同类场景 `tests 24.73s`）
- 有效性：在 **RED 的同一插件条件下**（7s / 16s）打补丁后 **7/7、12/12 全绿**；低负载下 5 spec 合跑 **25/25 绿**
- 与既有语义无冲突：`vi.mock` 由 vitest 提升到所有 import 之上，预热 import 拿到的仍是 mock 后的 `aiApi`；
  `vi.resetModules()` 的"每用例干净现场"不受影响（预热实例不被用例使用，用例内的动态 import 仍会重执行出新实例）

### 4.1 应用方式（注意仓库行尾混合）

仓库无 `.gitattributes`，5 个文件里 3 个 CRLF、2 个 LF（`truncated-answer*.spec.ts` 是 LF），
而本机 `core.autocrlf=true`。已验证：

```bash
git -c core.autocrlf=false apply --check -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-final-regression.patch
git -c core.autocrlf=false apply       -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5-final-regression.patch
```

在该方式下，5 个文件应用结果与已验证副本**逐字节一致**（`cmp` 全 identical）。
若直接 `git apply`（autocrlf=true），内容等价但 2 个 LF 文件会被写成 CRLF（提交后 git 归一化，无内容差异）。

---

## 5 未验证边界 / 需要下游知道的事

1. **没有重跑全量**（按要求）。所以本文只声明"这 5 条在文件级/5-spec 级已 GREEN、根因已定位"，
   **不**声明"全量 1666 条现在全绿"。
2. **没有在实际 145 文件并行负载下再验一次**。负载量级来自失败日志的耗时与汇总行（`import 449.51s`），
   单变量 RED 是等价构造而非真实负载复刻。
3. **第 4 条的实例同一性只在构造条件下被直接观测**（见 §3.4 的 A− 说明）。
4. `truncated-answer-panel` 的空载余量最薄（3.38s / 5s）。即使不打补丁，只要首次装配成本不涨 1.5× 它就不会红；
   打补丁后余量变成 60×。
5. **未跑 `vue-tsc`**：本次无生产改动，类型面未变；补丁只新增裸 import，不引入类型。
6. 其它 3 个失败 spec 之外，只跑了 1 个 Task 5 相邻 spec（`truncated-answer.spec.ts`，预检遗留项），
   合计 5 个 spec，未跑全量、未跑 e2e、未跑后端。
7. 多卡 / 409 / park 恢复 / 文件代次相关的回归锁都在上述 5 个 spec 内（I-1、G2、BACKEND 混批、GF 表单、
   已决+未决并存、preflight 摘要），本次全部通过 ⇒ **未回退**。这批之外的相关锁未在本次复跑范围内。
8. 可选补丁是"降低误报概率"的测试基建改动，不是缺陷修复；是否落地由后续决定。若采纳，建议同样审视
   其它"首条用例内动态 import 大模块"的 spec（本次未逐一排查 151 个 spec 文件）。

---

## 6 证据文件清单

隔离副本 `<隔离副本>/_p2-attrib/`：

- `_p2-logs/green-isolated-{restore-reconcile,restore-request-emitted,rg-s13-s14-stopped-label,truncated-answer-panel}.spec.ts.log`
- `_p2-logs/red-slowfirst-{rg-s13,truncated-panel,restore-reconcile,restore-emitted}.log`
- `_p2-logs/ab-store-head.log`、`_p2-logs/ab-store-worktree.log`
- `_p2-logs/green-patched-5spec.log`、`_p2-logs/green-patched-slowfirst-7s.log`、`_p2-logs/green-patched-slowfirst-16s.log`
- `_probe/run-evidence.sh`（一键重放上述全部步骤）、`_probe/vitest.config.slow.ts`（单变量插件，**未**进任何提交/补丁）
- `_patched/`（5 个预热后的 spec 文件，与补丁应用结果逐字节一致）

主仓：`.superpowers/sdd/2026-10-25-ai-remediation/task-5-final-regression.patch`（仅测试侧，5 文件，87 行）。
