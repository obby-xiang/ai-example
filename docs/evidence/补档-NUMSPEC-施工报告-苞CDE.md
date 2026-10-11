> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 — 数字规格小批 苞 C / 苞 D / 苞 E

- **施工对象**：`<MAIN_REPO>`（主线仓，分支 `main`）
- **施工时点**：2026-10-10
- **本单范围**：苞 C（裁决①）、苞 D（裁决⑥）、苞 E（裁决④）。**苞 A / B / F / G 未触碰**。
- **提交策略**：每苞一个提交，顺序 C→D→E；**只提交不 push**。
- **落盘位置**：本报告在仓库外，全篇以 `<MAIN_REPO>` 指代仓库，代码锚点一律用仓库相对路径。

---

## 0. 前置核对（动手前实读）

| 项 | 实读结果 | 判据 |
|---|---|---|
| `git status --porcelain` | 空（工作树干净） | 与派单一致 |
| `git log --oneline -3` 顶部 | `6e59e0c docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕…` | 与派单「批 A 已提交 6e59e0c」一致 |
| 当前分支 | `main` | 一致 |

三项全符，遂开工。

### 本轮提交（`git log --oneline` 实读）

| 苞 | 提交 hash | 标题 |
|---|---|---|
| C | **`a56613d`** | `refactor(frontend): axios 超时根 60s 命名常量化 HTTP_TIMEOUT_MS + 依据注释（裁决①，值不变）` |
| D | **`6d5162b`** | `fix(backend): 数据列表分页缺省 50→20 与前端统一 + 前后端依据注释（裁决⑥）` |
| E | **`51ee49c`** | `chore(backend): 容量链 4/16/200 依据注释 + Hikari 上限 10 显式落键（裁决④，值不动）` |

`git diff --stat 6e59e0c..HEAD`：5 个文件、+77 / −2（其中 2 行删除 = 苞 C 的一行内联字面量与苞 D 的一行缺省值字符串）。

---

## 1. 苞 C — 前端超时根 60s 命名常量化

**提交**：`a56613d`　**文件**：`frontend/src/api/http.ts`（+23 / −1）

**diff 摘要**
- 新增 `export const HTTP_TIMEOUT_MS = 60_000`（现 `:103`）—— 原值 `timeout: 60000` 的内联字面量拆出为具名导出常量，**取值零变化**。
- `axios.create({ baseURL: '/api', timeout: HTTP_TIMEOUT_MS })`（现 `:107`）。
- 常量上方 18 行依据注释（`:86-102`），口径要点：
  - 引用**业界依据 §1「HTTP 客户端超时（connect / read / write）」**：axios 官方源码缺省 `timeout: 0` = 不超时（主流客户端中唯一必须显式设置者）；内网企业应用推荐整体 callTimeout **60–120s**，并列 OkHttp 10/10/10s、AWS SDK 2.x connect 5s/socket 30s、OpenAI/Anthropic 600s 作对照；本项目取区间下限 60s，长响应走 SSE 通道不占本上限。
  - 明写「**本值不是新值**」：原为内联字面量、取值不变，裁决① 只做值保留 + 命名常量化 + 补注释，不属行为变更。
  - 明写「**改本值须同批复核这三处**」：`utils/frontend-tools.ts` 的 `FRONTEND_TOOL_RESULT_HEADROOM_MS`、`components/SpreadGrid.vue` 的 `READY_TIMEOUT_MS`、`stores/workspace.ts` 的 `DOWNLOAD_FETCH_TIMEOUT_MS`；并声明三处 `import { HTTP_TIMEOUT_MS }` 的**代码同源化本批不做**（已按裁决登记，那两个文件本批不在改动面）。

**验证证据**
```
cd frontend && npx vue-tsc --noEmit      → EXIT=0（0 error）
cd frontend && yarn build                → 退出码 0，✓ built in 29.43s（日志 <SCRATCH>\numeric-spec-audit\frontend-build.log）
```
`yarn build` 是施工卡苞 C 的验收口径（本单派单只要求 typecheck），额外跑作更强证据；`frontend/dist/` 已被 `.gitignore` 排除，未污染工作树。

---

## 2. 苞 D — 分页缺省前后端统一为 20

**提交**：`6d5162b`　**文件**：`backend/.../data/controller/DataController.java`（+22 / −1）、`frontend/src/views/DataBrowserView.vue`（+4）

**diff 摘要**
- `DataController#list` 的 `@RequestParam(defaultValue = "50") int size` → `"20"`（现 `:70`）。
- 同方法 javadoc 追加依据段（现 `:44-58`），口径要点：引用**业界依据 §8「分页大小主流默认」**（GitHub 默认 30/上限 100、Stripe 默认 10/范围 1–100；内网推荐"默认 20，前端 20/50/100 切换，后端硬上限 100"）；明写「**本值是变更值（50 → 20）**」与影响面（仅"不传 `size` 的直连调用"，前端与 E2E 均显式传参 ⇒ 行为面零回归）；明写「**改本值须连带复核**」前端 `const size = ref(20)` 与同页 `:page-sizes` 选项表；并**登记**四个同族项（`JobController` 50 ↔ `stores/task.ts` 50 同源一致、`TaskController` 10 ↔ `stores/task.ts` 10 同源一致、`ImportWizardView.vue` 的 `getIssues(jobId, 0, 200)` 与 `app.ai.tool-result.max-rows` 的 200 **同值不同义**），明写「是否把 `JobController` 的 50 也统一为 20 属新决策，已登记为待裁决项，本步不动」。
- `DataBrowserView.vue`：`const size = ref(20)` 上方补 4 行注释（现 `:185-188`，值不变、结构不变），指向后端同值与该端点原缺省 50 的统一依据。

**注释内事实的逐条实读核对**（写入前实读，避免注释失真）

| 注释陈述 | 实读证据 |
|---|---|
| `JobController` 缺省 50 | `job/controller/JobController.java:61` `defaultValue = "50"` |
| `TaskController` 缺省 10 | `task/controller/TaskController.java:46` `defaultValue = "10"` |
| `stores/task.ts` 缺省 10 / 50 | `:65` `size: 10`、`:275` `loadIssues(jobId, page = 0, size = 50)` |
| `ImportWizardView.vue` 拉问题 200 条 | `:841` `jobsApi.getIssues(jobId, 0, 200)` |
| `app.ai.tool-result.max-rows` = 200 | `application.yml:139` `max-rows: 200` |
| 前端 `:page-sizes` = [20, 50, 100] | `DataBrowserView.vue:129` |

**"旧缺省 50 是否被用例依赖"自查（施工卡 D-1 要求）**
- `backend/src/test` 内无显式断言缺省 50 的用例。唯一走缺省绑定的用例是 `DataListConditionTest#withoutConditionsListStaysOnDatabasePaging`（只播种 6 行、断言 `content.length() = 6`），20 ≥ 6 仍满足 ⇒ 无需同步改。
- `scripts/verify-e2e.ps1` 中的 `size=50` 全部是**显式传参**（如 `/api/data/CURRENCY?page=0&size=50`），不受缺省值影响。

**验证证据**
```
bash <SCRATCH>\redteam-batch-a\.run-fix\mvn-run.sh -B test
  → BUILD SUCCESS；surefire XML 聚合 tests=383 failures=0 errors=0 skipped=0；XML 文件 59 个
  （日志 <SCRATCH>\numeric-spec-audit\mvn-D.log）
cd frontend && npx vue-tsc --noEmit  → EXIT=0
cd frontend && yarn build            → 退出码 0
```

---

## 3. 苞 E — 容量链 4/16/200 与 Hikari 10：值不动、补依据

**提交**：`51ee49c`　**文件**：`backend/.../config/AsyncConfig.java`（+18）、`backend/src/main/resources/application.yml`（+11）

**diff 摘要**
- `AsyncConfig.java`：**保留**既有 `<h2>为什么池大小是这几个数</h2>` 定性段（未删一字），其后追加依据段（现 `:33-54`），口径要点：引用**业界依据 §7「数据库连接池（HikariCP）」**官方反对"按并发数放大池子"（附英文原文）与**§12「任务看门狗 / 僵尸任务判定超时」**横向对照（Quartz 60s / Hangfire 30min / Celery 1h / K8s `periodSeconds` 10s × `failureThreshold` 3 ≈ 30s）；明写「**本值不是新值**」（四个数 4/16/200/30s 本次零改动、只补注释）与「**改本值须连带看哪里**」（core/max/queue 是一套、`awaitTerminationSeconds` × `waitForTasksToCompleteOnShutdown` 决定停机窗口、须与 `app.ai.suspend.pool-size` 一并复核）；并以独立段落**如实登记已知张力**：本池上限 16 > Hikari 连接上限 10（最多 10 条作业线程能持连接、其余在 `connectionTimeout` 内排队），指向等待治理 R-68 同面。
- `application.yml`：`spring.datasource` 段末尾新增 `hikari:`（现 `:24-34`），显式 `maximum-pool-size: 10`；注释写明「本值不是新值」（= 框架缺省值，显式化属**等价配置、行为零变化**）、来源（§7 官方默认 10 且官方反对按并发放大）、`minimumIdle` 按同节官方建议刻意不设、以及连线到 `AsyncConfig` 的张力；并说明"此前仓内完全未出现（取框架缺省），显式写出只为口径可见 + 可调剂（同 `app.ai.sse` 的 `delivery-pool-size` 先例）"。
- **范围纪律**：本提交**只动 Hikari 段**，未触 `application.yml` 任何其它键（苞 A 后续另改同文件的 `hitl` 段，不冲突）。

**验证证据**
```
bash <SCRATCH>\redteam-batch-a\.run-fix\mvn-run.sh -B test
  → BUILD SUCCESS；XML 聚合 383/0/0/0；XML 文件 59 个（日志 <SCRATCH>\numeric-spec-audit\mvn-E.log）
```
绑定正确性的正面证据（同一日志内实读）：
- `HikariDataSource : HikariPool-1 - Starting...` → `HikariPool-1 - Start completed.`（真上下文起池成功）
- `grep -iE "unknown property|not bound|Failed to bind|unrecognized"` → **0 命中**
- `grep -c "\[ERROR\]"` → **0**

---

## 4. 验证证据汇总

| 层 | 命令 | 结果 |
|---|---|---|
| 前端类型 | `cd frontend && npx vue-tsc --noEmit` | 苞 C 后 0 error；苞 D 后 0 error |
| 前端构建 | `cd frontend && yarn build` | 退出码 0，`✓ built in 29.43s`（苞 C/D 口径） |
| 后端全量 | `bash <SCRATCH>\redteam-batch-a\.run-fix\mvn-run.sh -B test` | 苞 D 后 / 苞 E 后均 **BUILD SUCCESS**，XML 聚合 **383 / 0 / 0 / 0**，**59** 个 XML |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 全绿**（含断言⑤ 命名纪律全量扫描：919 路径 / 实扫 916，规则自检连写组 5/5、分隔组 24/24、负对照 11 不误伤；盘符路径与禁词组合均 0 命中） |
| 提交卫生 | 逐文件显式 `git add`；`--no-verify` 未使用 | 三次 pre-commit 敏感信息扫描均「通过（暂存区无命中）」 |
| 推送状态 | `git status -sb` | `## main...origin/main [ahead 3]` —— **未 push**，符合本单要求 |

日志落盘：`<SCRATCH>\numeric-spec-audit\mvn-D.log`、`mvn-E.log`、`mvn-baseline-6e59e0c.log`、`frontend-build.log`。

---

## 5. 偏差与需指挥官注意的项

### 5.1 【需复核】测试计数基线不符：实测 383/59，派单给的 381/58 属批 A 之前的旧基线

派单要求"XML 口径应仍 **381**/0/0/0，**58** XML 只增不减"。实测：

| 状态 | tests | failures | errors | skipped | XML 数 |
|---|---|---|---|---|---|
| 派单声称的基线 | 381 | 0 | 0 | 0 | 58 |
| **`6e59e0c`（批 A 已提交、我动手前）独立实测** | **383** | 0 | 0 | 0 | **59** |
| 苞 D 后（工作树） | 383 | 0 | 0 | 0 | 59 |
| 苞 E 后（工作树） | 383 | 0 | 0 | 0 | 59 |

- 为排除"是我引入的"，我用 `git archive 6e59e0c` 把该提交导出到**仓库外**独立目录（`<SCRATCH>\numeric-spec-audit\baseline-6e59e0c`）跑了同一命令，得到同样的 **383/0/0/0 + 59 XML**（日志 `mvn-baseline-6e59e0c.log`）。故 381/58 是**批 A 提交之前**的口径；批 A 里 `43f7d0a` 新增 `ConfirmGateFrontendTimeoutTextTest` 一个测试类，正是 XML 58→59 的来源。
- 本轮三苞**未新增/删除任何测试、未改任何测试源码**（`git diff 6e59e0c..HEAD --name-only` 只有 5 个文件：2 前端 + 3 后端主源/配置），用例计数**零变化**是构造上成立的。
- 另：在全 `<SCRATCH>` 日志中检索不到 `Tests run: 381` 这个数（只检索到 346/353/354/357/358/361/376/378/380/383 等历史值），故无法与 381 直接对照。

**结论**：苞 D/E 的回归判据按"与 `6e59e0c` 基线逐项相等"成立（383/0/0/0、59 XML 完全一致）；派单里的 381/58 基线取值建议指挥官按批 A 后的现态更新。

### 5.2 【已决策并留痕】提交粒度取"每苞一提交"，与施工卡 §1 的"建议提交数"不同

- 施工卡 §1 建议：苞 C 1 个、苞 D **2** 个（后端缺省值 / 前端注释）、苞 E **2** 个（Java 注释 / yml 注释）。
- 本单派单的硬性纪律写的是"**每苞独立提交**"并要"三苞提交 hash"，故按**每苞一提交**执行（共 3 个）。
- 理由：苞 D 的两处同属一个逻辑变更（前后端分页默认统一为 20），苞 E 的两处同属"补依据注释、值不动"，合并后仍满足 DC-04"一提交一逻辑变更"。
- 若需按施工卡拆细，`git reset --soft` 后重拆即可，代码内容不受影响。

### 5.3 其余

- **额外跑了 `yarn build`**（派单只要求 `vue-tsc --noEmit`）：结果为绿，作为施工卡苞 C/D 验收口径的补强证据；`frontend/dist/` 已被 gitignore，工作树保持干净。
- **未做的事（全部在授权边界内）**：苞 C 未做下游三处 `import` 同源化（已裁决登记）；苞 D 未动 `JobController` 的 50（已裁决登记为待裁决项）；苞 E 未调任何数值、未碰 `application.yml` 其它键；未 push、未跑 E2E / 冒烟（F 苞范围）。
- **收尾物**：仓库外导出目录 `<SCRATCH>\numeric-spec-audit\baseline-6e59e0c`（19 MB，含 target/）为基线证据，可随时删除，不影响仓库。
