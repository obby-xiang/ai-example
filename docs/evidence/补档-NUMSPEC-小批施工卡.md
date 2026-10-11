> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 数字规格小批施工卡（numeric-spec-audit 裁决落地）

- **起草时点**：2026-10-10
- **施工对象**：`<MAIN_REPO>`（主线分支，HEAD `6ee96c3`，**工作树含批 A 在制品**）
- **起草方式**：**只读**。起草期间未修改 `<MAIN_REPO>` 任何文件、未做任何 git 写操作；本卡落盘于仓库外。
- **输入**：`<SCRATCH>/numeric-spec-audit/清单总账.md`（下称「总账」）、`<SCRATCH>/numeric-spec-audit/业界依据.md`（下称「依据」）。两份均实读完毕。
- **命名纪律**：本卡不出现「旧主仓目录名」及其同名分支名的字样组合；指代本仓一律用 `<MAIN_REPO>`，仓库外一律用 `<SCRATCH>`。
- **本小批范围**：裁决①~⑥ + 4 条立法落地。**⑦（作业等待三套：前端 300s 维持 + 后端补看门狗）不在本小批**，只在 §7 写明边界。

---

## 0. 前置事实（实读核对，施工前必读）

### 0.1 总账行号实读核对【实测】

| 裁决 | 总账锚 | 代码/配置锚（实读现值） | 核对结果 |
|---|---|---|---|
| ① | 总账 `:117`（F1-01） | `frontend/src/api/http.ts:85` `timeout: 60000` | 一致 |
| ② | 总账 `:109`（B2-30） | `backend/.../ai/tools/AiTools.java:139` `.limit(10)`（方法 `:136`，注解 `:133`） | 一致 |
| ③ | 总账 `:52`（B1-09） | `backend/src/main/resources/application.yml:101-103`；`AiProperties.java:115-128`（`:127` 为值）；`ConfirmGate.java:167-188`（两条等待路径共用 `confirmTimeoutSeconds()`） | 一致；`:184` 注释明写"规格 §4 只定义了这一个键" |
| ④ | 总账 `:84`（B2-05）、`:111`（B2-32） | `config/AsyncConfig.java:44-46`（4/16/200）、`:49`（关停 30s）；Hikari 在仓内**完全未出现**（`application.yml:19-23` 无 `hikari` 段） | 一致 |
| ⑤ | 总账 `:192`（S-14）、`:194`（S-16）、`:208`（S-30） | `scripts/verify-e2e.ps1:1810`(3500) / `:1880`(3000) / `:2195`(3000)；13 处 `Start-Sleep -Seconds 2`（行号见 §5.1）；`scripts/m2t2a-smoke.mjs:273` `AWAIT_MS = 240_000` | 一致 |
| ⑥ | 总账 `:154`（F2-01）、`:110`（B2-31） | 前端 `views/DataBrowserView.vue:185` `const size = ref(20)`；后端 `data/controller/DataController.java:49-50`（`page=0` / `size=50`） | 一致 |

### 0.2 与批 A 在制品的文件重叠（**强约束**）

实读 `git status`：批 A 在制品 = 10 个 `M` + 1 个 `??`。与本小批**直接重叠**的文件：

| 文件 | 批 A 已改 | 本小批哪一苞也要动 | 处置 |
|---|---|---|---|
| `backend/.../ai/config/AiProperties.java` | 是（`:118-127` Hitl javadoc +7/-1，**未改数值**） | 苞 A（③） | **必须等批 A 提交后**本苞才开工，否则同一 hunk 冲突 |
| `backend/.../ai/gate/ConfirmGate.java` | 是（类注 `:38-40`、`expire` javadoc `:286-296`、文案 `:311`） | 苞 A（③） | 同上 |
| `backend/.../ai/tools/AiTools.java` | 是（`:483-487` generative_form javadoc +5/-2） | 苞 A（③注释同步）、苞 B（② limit 改值） | 同上；苞 A/B 互不重叠（不同行段）但都与批 A 同文件 |
| `frontend/src/utils/frontend-tools.ts` | 是（+17/-…） | **不在本小批改**（只登记，见 P-1） | 只读引用，勿动 |
| `frontend/src/stores/workspace.ts` | 是 | **不在本小批改** | 只读引用（作为 60s 链的一环登记） |
| `docs/M2-排期计划.md` | 是（+27 行，去向表末行 `R-94` 之后已有 R-95~R-99 与新注记节） | 苞 F（⑤登记文本） | 追加点必须在**批 A 提交后的现态末行**之后，按"只追加"纪律 |

**结论（施工顺序硬约束）**：苞 A / 苞 B / 苞 F 落盘前须先确认批 A 已提交（或已确认其工作树 diff 被本苞完整覆盖）。苞 C / 苞 D / 苞 E / 苞 G 的文件与批 A 无重叠，可先行。

### 0.3 【阻塞项】两个必读前置（**先裁决，再落值**）

裁决③（等人 600s / 等机器 60s）在现仓下有**两处硬约束冲突**，实读证据如下。这不是推翻裁决，而是把"取值可达性"摆到台面上：**结构性拆分可以立即落，两个取值须连带裁决。**

#### P-1 前端执行器预算在 60s 键下塌陷到 5s（**S1 级**）

`frontend/src/utils/frontend-tools.ts:234-247` 的折算式（实读）：

```
usableMs = serverBudgetMs − FRONTEND_TOOL_RESULT_HEADROOM_MS
clampedMs = max(MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS, usableMs)
budget = min(clampedMs, serverBudgetMs)
```

其中 `FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000`（`:200`）、`MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 5_000`（`:208`）。
若 `app.ai.hitl.frontend-tool-timeout = 60s`，帧内 `timeoutSeconds = 60` ⇒ `usableMs = 60000 − 60000 = 0`
⇒ `clampedMs = 5000` ⇒ **执行器预算 = 5s**。

该文件自己的注释（`:225-231`）把 `≤65s` 明确称为**病态配置**："此处上述数学保证必然失效，只能退化为'到点即回灌'"；
而其"下界"论证（`:180-182`）写明实测最慢的一次**成功**回灌发生在 **30.6s**（issue#6 run3）——
即 5s 预算会掐掉已知会成功的路径。**这是把 120s 改成 60s 的真实功能回归面，不是注释问题。**

候选处置（**标【待指挥官裁决】**，本小批默认取 A0）：

- **A0（推荐，两步走）**：苞 A 先落**键结构拆分 + 注释**，`frontend-tool-timeout` 取值暂维持 **120s** 并在键注释中写明"该键降为 60s 的前置 = 前端折算面裁决（P-1）"；618 行级的折算改造另立专项。→ 好处：结构先闭环，不引入回归。
- **A1**：坚持 60s，同批把回灌 POST 单独给更短上限（`api/http.ts` 之外另建实例或 per-request `timeout`），使 `headroom < 60s`、折算不再落病态区。→ 代价：触及 `frontend-tools.ts`（批 A 在制品）+ `api/http.ts`（苞 C 同文件），且该面在等待治理侧**已有登记 R-99 ②**（"回灌单独给更短上限"），应与 R-99 ② 同批做，不在本小批。
- **A2**：坚持 60s 且不动折算，接受执行器预算 = 5s（把 `MIN` 一并下调）。→ **不建议**：以"到点即回灌"替代真实工作，等于把功能失败伪装成超时回执，与本次"消除谎报"的主题相反。

#### P-2 确认门 600s 在 `total-budget = 300s` 下**不可达**（**S1 级**）

实读证据（`total-budget = 300s`，`AiProperties.java:162`）：

| 位置 | 事实 |
|---|---|
| `ai/run/StreamWatchdog.java:130-134` | tick ② **先于** ③ 的"工具活跃期不计静默"判据 ⇒ 挂起期间也会在 300s 触发 `TOTAL_BUDGET` 并 `dispose()` 上游 |
| `ai/web/AiController.java:174` / `:295` / `:664-665` | 两处 `new SseEmitter(budgetMillis())`，`budgetMillis()` = `total-budget` ⇒ **SSE 寿命 300s** |
| `ai/run/ResilientChatService.java:374` | 墙钟兜底 `deadline = turnStart + total-budget + 1s` |
| `ai/run/CancellationRegistry.java:231` | 取消标志键 TTL = `total-budget × 2` = 600s（与 600s 挂起**恰好同刻**） |

即：确认门等到 300s 时，本轮已被判 `TOTAL_BUDGET` 收口、SSE 已断，而挂起线程仍会继续等到它自己的 600s 才写 `CONFIRM_TIMEOUT`。
**用户可感知的上限是 300s，不是 600s。**

候选处置（**标【待指挥官裁决】**，本小批默认取 B0）：

- **B0（推荐）**：苞 A 落键拆分，`confirm-timeout` 取 **600s**（忠于裁决），并在 `application.yml` 的该键注释中**显式写明**："实际可达上界受 `app.ai.resilience.total-budget`（300s）约束，600s 须与'轮次预算 / emitter 寿命 / 兜底等待三语义拆分'（等待治理已登记 R-99 ③）同批才生效"；同时新登记一条"`total-budget ≥ confirm-timeout + 余量`"的上限关系约束。→ 忠于裁决 + 不留谎报。
- **B1**：`confirm-timeout` 过渡取 **240s**（< 300s，留 60s 余量），600s 由专项（total-budget 拆分）承接后再抬。
- **B2**：同批把 `total-budget` 抬到 ≥ 660s。→ **超出本小批授权**（该键三语义合一，牵动 emitter 寿命/重试判定/注册表 TTL/resume TTL 五处），不建议夹带。

> **本卡默认按 A0 + B0 起草**（结构立即落、取值标注可达性约束，两个下调/上抬都走专项）。若指挥官选 A1/A2/B1/B2，只改 §2 苞 A 的取值与注释段落，其余步骤不变。

---

## 1. 分包与提交粒度建议

| 苞 | 内容 | 主要文件 | 建议提交数 | 可与其他苞合并？ |
|---|---|---|---|---|
| **A** | ③ `app.ai.hitl` 双键拆分（等人 / 等机器） | `AiProperties.java`、`ConfirmGate.java`、`application.yml`、`ConfirmGateFrontendTimeoutTextTest.java`、4 处 javadoc 同步 | **3**（① 结构+键拆分；② 测试适配；③ 注释/文档同步） | **必须独立成苞** —— 唯一改"运行时行为 + 协议取值（帧内 `timeoutSeconds`、`/health` 键）"的苞，且带 P-1/P-2 两个待裁决取值。拆开便于"结构先落、取值待裁"两步走 |
| **B** | ② `list_tasks` 10→20 + 「共 N 条仅显示前 20」告知 | `AiTools.java`、新增 `AiToolsListTasksTruncationTest.java` | 2（代码；测试） | 可与 C 合并为"小改一苞"，但**不建议**（后端 AI 链路 vs 前端网络层，无关） |
| **C** | ① 前端超时根 60s 命名常量化 + 依据注释 | `frontend/src/api/http.ts` | 1 | 可与 D 合并为"前端口径苞"，但**不建议**（DC-04 一提交一逻辑变更） |
| **D** | ⑥ 分页默认前后端统一为 20 | `DataController.java`、`DataBrowserView.vue`（注释） | 2（后端缺省值；前端注释） | 见上 |
| **E** | ④ 容量链 4/16/200 + Hikari 10 依据注释 | `AsyncConfig.java`、`application.yml` | 2（Java 注释；yml 注释+显式键） | 不建议合并（与 A 同动 `application.yml`，须顺序提交） |
| **F** | ⑤ E2E 三数 | `verify-e2e.ps1`（2 处读窗 + 函数头注释）、`m2t2a-smoke.mjs`（AWAIT 事件驱动）、`docs/M2-排期计划.md`（13 处登记） | 3（读窗；AWAIT 改造；登记文本） | 独立 |
| **G** | 4 条立法落地 | `docs/adr/DECISION-REGISTER.md`、`docs/README.md` | 1~2 | **最后落**（它引用 A~F 的依据注释口径） |

**顺序约束**：批 A 提交 → {C, D, E 可并行} → A → B → F → G。`application.yml` 被 A 与 E 先后写，须**顺序**（不可同批同文件并行编辑）。

---

## 2. 逐步施工卡

### 苞 A — ③ `app.ai.hitl.timeout` 120s 三场景拆分

> 三场景 = 确认门（等人）/ 前端工具（等机器）/ `generative_form`（属前端工具通道）。拆成**两个键**：
> `app.ai.hitl.confirm-timeout`（等人）与 `app.ai.hitl.frontend-tool-timeout`（等机器，前端工具与 `generative_form` 共用）。
> **键名替代方案【待指挥官裁决】**：保留旧键名 `timeout` 承载确认门（外部部署零改名成本），新键只加 `frontend-tool-timeout`。
> 本卡默认取**双新键**（与立法 3「同值不同义禁共用」一致，且键名自述场景）。

#### A-1 拆键（`AiProperties.Hitl`）

- **主锚**：`backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:115-128`
- **现状**：单一字段 `private Duration timeout = Duration.ofSeconds(120);`（`:127`），类注（`:118-127`，批 A 已改）自述"确认门与前端工具挂起共用"。
- **改法**：
  1. 删除 `timeout` 字段，新增 `private Duration confirmTimeout = Duration.ofSeconds(600);` 与 `private Duration frontendToolTimeout = Duration.ofSeconds(60);`（取值按 §0.3 的 A0/B0 默认：`confirmTimeout=600s`、`frontendToolTimeout=120s` 或 60s 待裁）。
  2. 类注重写为"两个场景、两个键"，并把原批 A 的"结局语义不同"段落保留（那段是 R-95 的收尾成果，不得删）。
- **依据注释文案**（`confirmTimeout`）：

  > 确认门等待上限（**等人**）：AI 请求人类对破坏性操作（导出/检查/导入/发布）作出决策。
  >
  > 取值依据（业界依据 §5「人机确认门（HITL approval）超时」）：该节唯一有官方硬依据的结论是"不设超时 = 永久卡死"（AWS Step Functions 官方最佳实践），取值本身业界无统一标准；同节对"内网、用户量小、有明确操作人的交互式确认门"给出的推荐值是 **10 分钟**，超时后自动拒绝并释放锁（fail-closed，不自动放行）。本键取 600s。
  >
  > 与前端工具键刻意分开：本键等的是**人**的决策延迟（分钟级），那个键等的是**机器**的回执延迟（秒级），同值共用会让两者互相迁就（2026-10-10 数字规格清点裁决③；登记见 `docs/M2-排期计划.md` 去向表 R-99①）。
  >
  > **实际可达上界约束**：本轮还受 `app.ai.resilience.total-budget`（300s）约束 —— `StreamWatchdog` 的总预算判据先于"工具活跃期不计静默"生效，`SseEmitter` 寿命亦取该值。600s 的全量生效须与 total-budget 三语义拆分（等待治理 R-99③）同批；在此之前用户可感知上限仍是 300s。

- **依据注释文案**（`frontendToolTimeout`）：

  > 前端工具（含 `generative_form`）回执等待上限（**等机器**）。
  >
  > 取值依据（业界依据 §6「前端工具调用超时（AI 工具调用执行预算）」）：MCP 规范只要求 SHOULD 设超时、不规定数值；实现层默认常见 **60s**，Vercel AI SDK 官方 `toolMs` 区间为 5–60s（跨系统工具取 60s）。本键取 60s。
  >
  > **已知约束**：前端执行器预算由帧内 `timeoutSeconds` 折算（`frontend/src/utils/frontend-tools.ts#deriveFrontendToolBudgetMs`，折算式 `预算 = 服务端上限 − 回灌 POST 上限(60s)`）；本键 = 60s 时该式为 0、预算落 5s 下限 —— 该文件自己把 ≤65s 列为病态配置。取值下调的前置 = 前端折算面裁决（数字规格清点 P-1）。

- **验收**：`cd backend && mvn -B test`（编译面）。

#### A-2 两条等待路径分别取键

- **主锚**：`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:167-188`
- **现状**（实读）：`:168-171` `confirmTimeoutSeconds()` 读 `properties.getHitl().getTimeout()`；`:178-180` `awaitDecision(...)` 与 `:182-188` `awaitFrontendResult(...)` **都**传 `confirmTimeoutSeconds()`（`:184` 注释明写"同用一个键"）。
- **改法**：
  1. `:167-171` 拆成两个方法：`confirmTimeoutSeconds()`（读 `confirmTimeout`）与**新增** `frontendToolTimeoutSeconds()`（读 `frontendToolTimeout`），两者的 `Math.max(1, …)` 收口保持一致。
  2. `:179` 保持 `confirmTimeoutSeconds()`；`:187` 改 `frontendToolTimeoutSeconds()`。
  3. `:182-188` 的方法 javadoc 整段重写（现文"等待上限与确认门同用一个键 —— 规格 §4 只定义了这一个键"已不成立）。
  4. 类注 `:38-40` 的"超时"一条：`{@code app.ai.hitl.timeout}（默认 120s…）` 改为两个键名 + 各自默认值（`600s` / `60s`），并把"结局语义分场景"的说明保留。
  5. `:497-500`（`dismiss` javadoc）里引用的 `app.ai.hitl.timeout` 改为前端工具键。
- **不改**：`expire`（`:297-323`）的文案已经是"秒数由实参传入"（`:309-311`），**无需改动**——这也正是批 A 回归锁能继续生效的原因。
- **依据注释文案**（新方法上）：

  > 前端工具回执等待上限（秒）：`app.ai.hitl.frontend-tool-timeout`。
  > 与确认门键（`confirm-timeout`）**刻意分开**：两者等的对象不同（机器回执 vs 人的决策），
  > 延迟分布差一个数量级，共用一个键会使"人等得起的时长"被迫压成"机器回执的时长"（裁决③）。

- **验收**：`cd backend && mvn -B test -Dtest='ConfirmGate*'` 全绿。

#### A-3 回归测试适配（**它注入的是哪个键，必须想清楚**）

- **主锚**：`backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java`
- **现状**（实读）：`:134-138` `gateWithTimeoutSeconds(int seconds)` 只设 `properties.getHitl().setTimeout(Duration.ofSeconds(seconds))`；两类用例**共用**这一个 helper ——
  - `:82-110` 参数化前端工具用例（`:83` 注入 2/3 秒）；
  - `:112-130` 确认门用例（`:67` `CONFIRM_TIMEOUT_SECONDS = 1`）。
- **结论（"注入的是哪个键"）**：该测试当前注入的是**那一个共用键**；拆键后，**参数化用例注入 `frontendToolTimeout`，确认门用例注入 `confirmTimeout`**。
- **改法**：
  1. `:134-138` 拆为两个 helper：`gateWithFrontendTimeoutSeconds(int)`（设 `frontendToolTimeout`）与 `gateWithConfirmTimeoutSeconds(int)`（设 `confirmTimeout`）；未设的那个键保持类默认值（这本身不参与断言，但**不得**让默认值落在 1s 附近，否则用例会意外走另一条分支——当前默认 600s/60s 安全）。
  2. `:86` 改调前端 helper；`:115` 改调确认门 helper。
  3. `:54` 的类注"上限的注入走生产路径 `app.ai.hitl.timeout`"改为两个键名；`:96` 的 `.as("…app.ai.hitl.timeout…")` 同步改名。
  4. **保留** `:70` 的正则 `未在 (\d+) 秒内收到前端回执`与 `:101-107` 的"旧措辞必须消失"断言（这是 R-95 的回归锁，不得因改名而削弱）。
  5. **建议同批加一条参数化到 2/3 秒的确认门用例**（可选）：现有确认门用例只注入 1s，无法证明"确认门文案的秒数也随配置走"；拆键后两条路径各自独立，这条覆盖缺口才暴露出来。→ 建议但**非必须**（标【待指挥官裁决】）。
- **验收**：`cd backend && mvn -B test -Dtest=ConfirmGateFrontendTimeoutTextTest` 全绿（3 用例：2 个参数化前端 + 1 个确认门）。

#### A-4 配置面与其它读键点同步

| # | 主锚（实读） | 现状 | 改法 |
|---|---|---|---|
| A-4-1 | `backend/src/main/resources/application.yml:101-103` | `hitl:` 段只有 `# 确认门/前端工具挂起的等待上限（ADR-2 上限约束…）` + `timeout: 120s` | 拆两键 + 依据注释；`confirm-timeout: 600s`、`frontend-tool-timeout: 60s`（按 A0 取值可暂 120s）。注释须含 §0.3 的 P-1/P-2 两句可达性约束 |
| A-4-2 | `backend/src/main/resources/application.yml:111-112` | `resilience` 注释引"`hitl.timeout(120s)`" | 改为"确认门 600s（受本段 `total-budget` 300s 约束）/ 前端工具 60s" |
| A-4-3 | `AiProperties.java:149`（`interEventTimeout` javadoc） | "挂起等待（确认门默认 120s > 90s）" | 改为两键各自与 90s 的关系；**须保留**"工具执行期不计静默"的论证，并说明"前端工具 60s < 90s 仍正确" |
| A-4-4 | `ai/run/StreamWatchdog.java:22` | 类注引 `app.ai.hitl.timeout` | 改为"挂起等待上限"（两键并列），不引单一键名 |
| A-4-5 | `ai/exec/AiExecutorConfig.java:18` | "挂起等待（确认门 ≤ `app.ai.hitl.timeout`）" | 改为 `confirm-timeout`；并**顺手登记**：600s × 挂起池 20（队列 0）会把 503 窗口从 2 分钟级拉到 10 分钟级（关联等待治理 R-41 的"多挂起累计预算"） |
| A-4-6 | `ai/tools/AiTools.java:483` | generative_form javadoc"时间上限沿用既有前端通道口径（`app.ai.hitl.timeout`，默认 120s）" | 改为 `app.ai.hitl.frontend-tool-timeout`（默认 60s） |
| A-4-7 | `ai/web/AiController.java:536` | `/health` 只回 `confirmTimeoutSeconds` | **建议**增 `frontendToolTimeoutSeconds`（可观测性对称；一处一行，低风险）。→ 标【待指挥官裁决】；若不加，须在苞 F 的 AWAIT 事件驱动方案里改用 `resilience.totalBudget` 而非该键（见 F-2） |
| A-4-8 | `ai/run/ResilientChatService.java:769` | 诊断快照键 `hitlTimeout` = 旧键值 | 改为两条：`confirmTimeout` / `frontendToolTimeout`（键名与语义一对齐）。**须先 grep 确认无测试断言 `hitlTimeout`**：实读 `grep -rn "hitlTimeout" backend/src` 仅此一处，安全 |
| A-4-9 | `frontend/src/utils/frontend-tools.ts:165-166, :218, :264` | 三处注释引"`app.ai.hitl.timeout`（默认 120s，与确认门共用）" | 改名为前端工具键 + 新默认值。**文件是批 A 在制品 → 只改注释行，改动前实读现态行号** |
| A-4-10 | `frontend/src/components/SpreadGrid.vue:97` | "60s 明显小于后端 `ConfirmGate` 的挂起超时（120s）" | **该论证在拆键后失效**（60s 前端工具键下 `READY_TIMEOUT_MS=60s` 不再严格小于）。改法 = 如实改写为"与前端工具挂起上限同为 60s，两者同刻到点，边界由帧下发值兜底"；或按 P-1 裁决一并复核。→ **必改，不得留存"明显小于"的假话** |
| A-4-11 | `frontend/src/stores/workspace.ts:320-327` | `DOWNLOAD_FETCH_TIMEOUT_MS` 注释引 `http.ts:85` | 不受拆键影响；**只登记**（苞 C 若改 `http.ts` 行号，此处 `http.ts:85` 锚点会位移，须同步） |
| A-4-12 | `scripts/verify-e2e.ps1:1387` | "不硬编码 120，与 `app.ai.hitl.timeout` 解耦" | 改为"与 `app.ai.hitl.frontend-tool-timeout` 解耦"（断言本身 `> 0` 不变，**这正是"不硬编码"纪律的正面案例，不得削弱**） |
| A-4-13 | `docs/s4/S4.2-AI-Runtime-合流规格.md:69` | 键表一行 `app.ai.hitl.timeout \| 120s \| 确认门超时` | 拆两行 + 依据一句话 |
| A-4-14 | `docs/03-技术方案文档-v2.1.md:551`、`:270`、`:642` | 引旧键名 `app.ai.hitl-timeout`（更早的历史命名） | **【待指挥官裁决】**：该文档为"现行技术方案"且属冻结文档（批 A 已在其上追加 +2 行）。建议只加行尾注记指向新键，不改被引正文 |
| A-4-15 | `docs/M2-排期计划.md:291`（R-99 行） | R-99① 已登记本项（建议值 30~60s）+ "第 1 项须与 R-41 的端口口径一致" | 行尾**追加**完成留痕（只追加；锚点 = 批 A 提交后的现态行号）。留痕须写明：实际取值（600s/60s）、P-1/P-2 两个可达性约束、是否已闭环 |

- **依据注释文案**（`application.yml` 的 `hitl` 段，可直接粘贴）：

  ```yaml
      hitl:
        # 挂起等待上限**分场景**（2026-10-10 数字规格清点裁决③；原单键 timeout: 120s 同供三场景）：
        #   confirm-timeout（等人）：确认破坏性操作时等人类决策。
        #     依据 = 业界依据 §5：该节唯一硬结论是"不设超时=永久卡死"（AWS Step Functions），
        #     取值无统一标准，对内网/小用户量的交互式确认门推荐 10 分钟（600s），超时自动拒绝。
        #     **可达上界受本文件 app.ai.resilience.total-budget（300s）约束**：看门狗的总预算判据先于
        #     "工具活跃期不计静默"生效，且 SseEmitter 寿命亦取该值 —— 600s 全量生效须与该键三语义
        #     拆分同批（等待治理 R-99③）；在此之前用户可感知上限仍是 300s。
        #   frontend-tool-timeout（等机器）：前端工具与 generative_form 的回执等待。
        #     依据 = 业界依据 §6：MCP 只规定 SHOULD 设超时；实现层默认常见 60s，Vercel AI SDK
        #     toolMs 区间 5–60s。**注意**：该键下调到 60s 会把前端执行器预算折算压到 5s 下限
        #     （frontend/src/utils/frontend-tools.ts 的"预算 = 服务端上限 − 回灌 POST 60s"，
        #     该文件自述 ≤65s 为病态配置）—— 取值须与前端折算面（数字规格清点 P-1）同批裁决。
        confirm-timeout: 600s
        frontend-tool-timeout: 60s
  ```

#### A-5 苞 A 验收

```bash
cd backend
mvn -B test -Dtest='ConfirmGate*'          # 目标用例（含 ConfirmGateFrontendTimeoutTextTest / DeadlockResume / Wiring）
mvn -B test                                # 全量（回归）
```
绿判据：`BUILD SUCCESS`；`ConfirmGateFrontendTimeoutTextTest` 的 `Tests run: 3, Failures: 0`（或加确认门参数化后 5）；
**且**任一时长出错的用例不得"等满新默认值才收口"（关注总时长：拆键后确认门默认 600s，若某测试忘了注入小值，总时长会暴涨——这是本苞的敏感指标）。

---

### 苞 B — ② `list_tasks` 截断 10→20 + 告知（消除隐蔽谎报）

#### B-1 改值 + 加告知

- **主锚**：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:133-143`
- **现状**（实读）：
  ```java
  @Tool(name = "list_tasks", description = "列出最近的任务列表（含类型/步骤/状态）")   // :133
  public String listTasks() {                                                        // :136
      List<Task> tasks = taskService.findAll();                                      // :137
      if (tasks.isEmpty()) return "当前没有任务，可以创建一个新任务";                    // :138
      return tasks.stream().limit(10).map(t -> ...).collect(Collectors.joining("\n")); // :139
  }
  ```
- **改法**：
  1. 类内新增常量 + 依据注释（见下）。
  2. `:139` `limit(10)` → `limit(LIST_TASKS_MAX_ROWS)`。
  3. 列表末尾追加告知：`tasks.size() > LIST_TASKS_MAX_ROWS` 时追加 `"\n（共 " + tasks.size() + " 条，仅显示前 " + LIST_TASKS_MAX_ROWS + " 条）"`。
  4. **建议（同一步内，标【待指挥官裁决】）**：`:133` 的 `@Tool` description 由"列出最近的任务列表（含类型/步骤/状态）"改为"列出最近的任务列表（含类型/步骤/状态），最多返回前 20 条，超出时给出总条数"——让模型在**调用前**就知道上限，与"消除谎报"同一目的。实读确认：无测试/脚本断言该 description（`grep -rn "列出最近的任务" backend/src/test scripts` 0 命中；`scripts/ci/stub-routes.json:7` 只登记工具名）。
  5. **明确不做（边界）**：不改 `taskService.findAll()` 全表读（属 R-98 W-12 的"浪费面"，未授权）；不加分页/游标参数（会改工具 schema，超出裁决）。
- **依据注释文案**：

  > `list_tasks` 一轮最多回填给模型的任务条数。
  >
  > 取值依据（业界依据 §8「分页大小主流默认」）：GitHub 默认 30 / 上限 100，Stripe 默认 10，
  > 内网小数据量场景推荐**默认 20** —— 本值即该推荐值。且必须 **< `app.ai.tool-result.max-rows`**
  > （200，见 application.yml），否则二次截断会先于本上限生效，下方"共 N 条仅显示前 M 条"的告知就成了假话。
  >
  > 为什么必须带告知：本工具是模型判断"任务是否存在、处于哪一步"的入口；静默截断会让模型把
  > 不完整清单当完整清单（2026-10-10 数字规格清点裁决②：原值 10 无注释、无告知、无截断标记）。

- **验收**：见 B-2。

#### B-2 回归锁（**推荐新增**，理由：这是"谎报消除"，须防回退）

- **新增文件**：`backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java`
- **形态**：纯 JUnit + Mockito（同 `ConfirmGateDeadlockResumeTest` 风格：`new AiTools(mock(TaskService.class), …)`，只 stub `findAll()` 返回 21 个 `Task`）。
- **断言**：
  1. 返回文本行数 = 20（+ 告知行）；
  2. 含 `共 21 条` 与 `仅显示前 20 条`；
  3. 恰 20 条成功时**不**出现告知（防"总是追加"的假告知）。
- **验收**：
  ```bash
  cd backend && mvn -B test -Dtest=AiToolsListTasksTruncationTest
  ```
  绿判据：`Tests run: 2, Failures: 0`。

---

### 苞 C — ① 前端超时根 60s：值保留 + 命名常量化 + 依据注释

- **主锚**：`frontend/src/api/http.ts:83-86`（`timeout: 60000` 在 `:85`）
- **现状**（实读）：内联字面量 `60000`，**无任何注释**；总账 `:117` 判为裸数字，且它是四个下游常量的对齐根（`frontend-tools.ts:200` 回灌余量、`SpreadGrid.vue:99` 表格就绪、`workspace.ts:327` 下载拉取、以及 `FRONTEND_TOOL_TIMEOUT_BUDGET_MS` 的折算语义）。
- **改法**：导出 `export const HTTP_TIMEOUT_MS = 60_000`（值不变），`axios.create({ baseURL: '/api', timeout: HTTP_TIMEOUT_MS })`。
- **范围纪律**：**只改本文件**。下游三处改 `import { HTTP_TIMEOUT_MS }`（把"注释里对齐"变成"代码同源"）是**下一步候选**，因 `workspace.ts` / `frontend-tools.ts` 是批 A 在制品，本小批**只登记不实施**（标【待指挥官裁决】）。
- **依据注释文案**：

  > 全部 axios 请求的单次上限（毫秒）。
  >
  > 取值依据（业界依据 §1「HTTP 客户端超时（connect / read / write）」）：axios 官方源码缺省
  > `timeout: 0` = **不超时**（`lib/defaults/index.js`，是所有主流客户端里唯一"必须显式设置"的），
  > 故本值不可省；同节对内网企业应用给出的推荐区间是**整体 callTimeout 60–120s**
  > （对照：OkHttp 缺省 10/10/10s、AWS SDK for Java 2.x 官方示例 connect 5s / socket 30s、
  > OpenAI/Anthropic SDK 整体 600s）。本项目取区间**下限 60s**：内网直连、响应短，
  > 长响应走 SSE 通道（`api/sse.ts` 自有 90s 帧间静默 + 15s 建连看门狗），不占本上限。
  >
  > 它同时是另外三个前端超时常量的**对齐根**：回灌 POST 的最坏耗时
  > （`utils/frontend-tools.ts` 的 `FRONTEND_TOOL_RESULT_HEADROOM_MS`）、表格引擎就绪
  > （`components/SpreadGrid.vue` 的 `READY_TIMEOUT_MS`）、下载拉取
  > （`stores/workspace.ts` 的 `DOWNLOAD_FETCH_TIMEOUT_MS`）。**改本值须同批复核这三处**
  > （2026-10-10 数字规格清点裁决①：值保留、命名常量化、补本注释）。

- **验收**：
  ```bash
  cd frontend && yarn typecheck && yarn build
  ```
  绿判据：`vue-tsc --noEmit` 0 error；`vite build` 成功。**无测试基建**（`frontend/package.json` 无 test 脚本，全仓 0 个 spec）⇒ 本步无可跑单测，验收以 typecheck+build 为准（如需取值回归锁，须先有前端测试框架裁决，见总账 §3 与等待治理 S3-2）。

---

### 苞 D — ⑥ 分页默认值前后端统一为 20

#### D-1 后端缺省值 50 → 20

- **主锚**：`backend/src/main/java/com/example/configmgr/data/controller/DataController.java:48-50`（方法 `list`，`page` 缺省在 `:49`，`size` 缺省在 `:50`）
- **现状**（实读）：`@RequestParam(defaultValue = "50") int size`；其 javadoc 在 `:40-47`。
- **改法**：`:50` 的 `"50"` → `"20"`；在 `:40-47` 的 javadoc 末尾追加依据段落（见下）。
- **影响面（实读确认）**：前端 `DataBrowserView.vue:252` 总是**显式传参** `size: size.value`（`:185` = 20），故本次改动**只影响"不传 size 的直连调用"**（curl / 集成测试 / 将来的第三方消费方）；行为面零回归。
- **依据注释文案**：

  > **分页缺省值（2026-10-10 数字规格清点裁决⑥：前后端统一为 20）**：本端点缺省页大小原为 50，
  > 与数据浏览器（`frontend/src/views/DataBrowserView.vue` 的 `size = ref(20)`）不一致 —— 同一资源
  > 两套默认（前端总显式传参故未爆，但口径已分叉）。统一取 **20**，依据业界依据 §8
  > 「分页大小主流默认」：GitHub 默认 30 / 上限 100、Stripe 默认 10 / 范围 1–100；内网小数据量场景
  > 推荐"默认 20，前端提供 20/50/100 切换，后端硬上限 100"。
  > 同批登记（不在本步改动面）：作业问题列表 `JobController` 缺省 50 ↔ `stores/task.ts` 缺省 50
  > （同源一致）、任务列表 `TaskController` 缺省 10 ↔ `stores/task.ts` 缺省 10（同源一致）、
  > `views/ImportWizardView.vue` 拉问题列表 `getIssues(jobId, 0, 200)`（与 `tool-result.max-rows`
  > 200 **同值不同义**）。

- **验收**：
  ```bash
  cd backend && mvn -B test
  ```
  绿判据：`BUILD SUCCESS`。另 grep 自查：`grep -rn "size=50\|size\" *, *50" backend/src/test scripts` 确认无用例依赖旧缺省绑定（**若有用例显式断言缺省 50，须随本步同步改为 20 并注明依据**）。

#### D-2 前端顺手补注释（立法 4：随包顺手）

- **主锚**：`frontend/src/views/DataBrowserView.vue:185`（`const size = ref(20)`）、`:129`（`:page-sizes="[20, 50, 100]"`）
- **现状**：两处裸数字，无注释。
- **改法**：在 `:185` 上方加一行注释（不改值、不改结构）：

  > `// 默认页大小 20（业界依据 §8：GitHub 30/上限 100、Stripe 10/上限 100，内网推荐默认 20、可选 20/50/100）。
  > // 与后端 DataController 的缺省页大小同值 —— 该端点原缺省 50，2026-10-10 数字规格清点裁决⑥统一为 20。`

- **验收**：`cd frontend && yarn typecheck && yarn build` 全绿。

#### D-3 登记（不改）

`JobController.java:60-61`（50）↔ `stores/task.ts:275`（50）；`TaskController.java:45-46`（10）↔ `stores/task.ts:65`（10）；`views/TaskCenterView.vue:114`（`[10, 20, 50]`）；`views/ImportWizardView.vue:841`（200）；`views/DataBrowserView.vue:129`（`[20,50,100]`）。
→ 是否把 `JobController` 的 50 也统一为 20，属**新决策**（现为"同源一致"，非冲突）→ 标【待指挥官裁决】，**本小批不动**。

---

### 苞 E — ④ 容量链 4/16/200 + Hikari 10：值不动，补依据注释

#### E-1 `AsyncConfig` 4/16/200 依据注释

- **主锚**：`backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:27-32`（现有 `<h2>为什么池大小是这几个数</h2>` **定性**论证）、`:44-46`（`4/16/200`）、`:49`（`awaitTerminationSeconds(30)`）、`:50`（`AbortPolicy`）
- **改法**：保留 `:27-32` 原文（不得删），在其后**追加**一段"取值依据补充 + 已知张力"；`:44-46` 与 `:49` 保持原值。
- **依据注释文案**：

  > **取值依据补充（2026-10-10 数字规格清点裁决④：值不动、补依据）**：业界依据 §7「数据库连接池
  > （HikariCP）」明确官方反对"按并发数放大池子"（原文："too many connections have a clear and
  > demonstrable negative impact on performance"），内网小用户量单实例下默认值即可用；同节 §12
  > 「任务看门狗 / 僵尸任务判定超时」的横向对照（Quartz 60s / Hangfire 30min / Celery 1h /
  > K8s `periodSeconds` 10s×`failureThreshold` 3 = 约 30s）说明本项目这类内部批处理的并发量级
  > 远低于需要放大池子的规模。故本类四个数（core 4 / max 16 / queue 200 / 关停等待 30s）**维持现值**。
  >
  > **已知张力（如实登记，不在本批改动面）**：本池上限 16 **大于** Hikari 缺省连接上限 10
  > （见 application.yml 的 `spring.datasource.hikari` 注释）—— 16 条作业线程中最多 10 条能同时
  > 持有连接，其余在 Hikari `connectionTimeout` 内排队。内网小并发下接受；若作业并发实测逼近上限，
  > 这两个数须一并重审（等待治理排查已登记为 R-68 同面）。

- **验收**：`cd backend && mvn -B test`（纯注释改动，只需证明未破坏编译与既有用例）。

#### E-2 Hikari 10：显式落键 + 依据注释

- **主锚**：`backend/src/main/resources/application.yml:19-23`（`spring.datasource` 段，**现无任何 hikari 配置**）
- **改法**：在该段末尾追加（**值 = 框架缺省，行为零变化**）：
  ```yaml
      hikari:
        # 连接池上限维持 10（2026-10-10 数字规格清点裁决④：值不动、补依据）。
        # 依据 = 业界依据 §7「数据库连接池（HikariCP）」：官方默认 maximumPoolSize = 10，
        # 且官方明确"不要按并发数放大池子"；内网小用户量、单实例下默认值即推荐值。
        # minimumIdle 刻意不设 —— 同节官方建议不设（默认 = maximumPoolSize，让池表现为固定大小）。
        # 此前本项在仓内完全未出现（取框架缺省值），显式写出只为"口径可见 + 可调剂"（同 app.ai.sse
        # 的 delivery-pool-size 先例）。
        # 已知张力：业务作业池上限 16（config/AsyncConfig）> 本上限 10，详见该类注释。
        maximum-pool-size: 10
  ```
- **风险核对**：Hikari 缺省 `maximumPoolSize = 10` 已是生效值，显式写出为**等价配置**；`minimumIdle` 不设同缺省行为；测试与 CI 均用 H2，行为不变。
- **验收**：
  ```bash
  cd backend && mvn -B test
  ```
  绿判据：`BUILD SUCCESS`（尤其 `@SpringBootTest` 起真上下文那批，证明 `spring.datasource.hikari.*` 键被正确绑定、无未知属性告警）。

---

### 苞 F — ⑤ E2E 三数

#### F-1 `Read-Sse-Brief` 3000 → 5000（立即改）

- **主锚**：`scripts/verify-e2e.ps1:1880`（GF5-A4）、`:2195`（TC23 ④）——两处 `Read-Sse-Brief ... 3000`
- **函数定义**：`:1307`（`function Read-Sse-Brief($path, $millis)`），函数头注释在 `:1305-1306`
- **改法**：
  1. `:1880` 与 `:2195` 的 `3000` → `5000`。
  2. 在 `:1305-1306` 的函数头注释后追加依据（见下），使后续调用点不必各写一遍。
  3. **【待指挥官裁决】第三个数 `:1810` 的 `3500`（GF4-A3）**：裁决⑤只点了"3000→5000"。按同一判据（窗口 ≥ 2 个心跳周期 + 余量）3500ms 同样不达标（1.75 个周期）。**建议同改 5000**，但**未经授权不动**，登记为待裁决项（见 §5.2）。
- **依据注释文案**（PowerShell 注释）：

  ```powershell
  # 读窗下界由**被观察方的节拍**决定，不是随手取的整数：挂起期心跳每 2s 一帧
  # （ConfirmGate.HEARTBEAT_SECONDS = 2），故 3000ms 窗口只兜得住 1 个心跳周期，抖动一次即漏帧
  # （漏帧会让"无新帧"断言假绿）。判据取「窗口 ≥ 2 个心跳周期 + 抖动余量」，即 2s × 2 + 1s = 5000ms；
  # 参照业界依据 §2（`heartbeat < min(代理超时)` 实践公式）与 §12（K8s `periodSeconds` ×
  # `failureThreshold` 的"连续 N 次失败才判死"模式）。2026-10-10 数字规格清点裁决⑤：3000 → 5000。
  ```
- **验收**：见 F-4。

#### F-2 `AWAIT_MS = 240_000` 恒真断言 → 事件驱动（**给方案**）

- **主锚**：`scripts/m2t2a-smoke.mjs:273`（`const AWAIT_MS = 240_000`）、`:275-277`（`readSse(chatRes, pred, Date.now() + AWAIT_MS)`）、`:280-282`（断言① FAIL 分支）
- **问题（实读复述）**：等待上界 240s **大于**后端 `hitl.timeout`（120s）——若生成式表单挂起键取 60s 后差距更大。后果是：任何"后端根本没给帧"的失败都要**先烧满 240s**才报出来，而这段时间里后端早已进入并退出挂起（终帧 `done`/`error` 也会被这条长窗掩盖），断言退化为"等超时"而不是"看事件"。
- **事件驱动方案（可直接实施）**：
  1. **早停条件**：给 `readSse` 增加第二个判定 `until`（终止帧）。只要读到 `done` / `error` 任一，立即停读并把结局记进证据：
     ```js
     const awaited = await readSse(chatRes,
       f => f.type === 'frontend_tool_request' && f.name === 'generative_form',
       { until: f => f.type === 'done' || f.type === 'error',
         label: 'a-await-frontend_tool_request' })
     ```
     `readSse` 的签名建议改为 `(res, predicate, opts)`，`opts` 兼容旧的 `deadlineMs`（避免一次性改所有调用点）。
  2. **失败原因可区分**：`awaited` 增加 `stopReason`（`matched` / `terminal:done` / `terminal:error` / `window`），断言① FAIL 时把它写进 detail。于是失败有"后端给了终帧（问题在业务）"与"窗内什么都没读到（问题在环境/超时）"两种可读结论，**不再是一个恒真的超时**。
  3. **硬上限改为推导值而非写死 240s**：上界取"服务端自己声明的轮次预算"，来源用**已在跑**的 `/api/ai/health`（`AiController:536-546` 的 `resilience.totalBudget`，形如 `PT5M`）：解析 ISO-8601 时长 → `capMs = budgetMs + 30_000`（余量 = 一次心跳 + 抖动）。解析失败时回落一个**具名常量**并打日志：
     ```js
     // 回落值 = app.ai.resilience.total-budget 缺省 300s + 30s 余量；解析成功时以服务端值为准
     const FALLBACK_AWAIT_MS = 330_000
     ```
     > 若指挥官选择**不改** `/health`（A-4-7 不增键），本方案正好只需要既有 `resilience.totalBudget`，**不依赖** A-4-7。二者无耦合。
  4. **登记不改**：`:362` 的 `deadline = Date.now() + 150_000`（3d 收敛等待）同样写死，但裁决⑤未授权 → 只登记（见 §5.2）。
- **依据注释文案**（JS）：

  ```js
  // 等待上界不写死：由服务端自报的轮次预算（/api/ai/health 的 resilience.totalBudget）
  // 推导为 budgetMs + 30s（余量 = 一个心跳周期 + 抖动）。写死 240s 的问题是它**大于**后端
  // app.ai.hitl 的挂起上限（原 120s，拆分后等人 600s / 等机器 60s）——任何"后端没给帧"的失败
  // 都要先烧满窗才报出，而期间后端早已给出终帧，断言退化为"等超时"而非"看事件"。
  // 2026-10-10 数字规格清点裁决⑤：改事件驱动（早停于 done/error，硬上限由事实源推导）。
  ```
- **验收**：见 F-4。

#### F-3 13 处 `Start-Sleep -Seconds 2`：只登记不换（登记文本草案见 §5.1）

- **不改代码**。追加登记行到 `docs/M2-排期计划.md` 的去向表（锚点 = 批 A 提交后现态末行之后）。

#### F-4 苞 F 验收

```powershell
# 全量 E2E（需 redis + 桩上游 + 后端；沿用 CI 口径）
pwsh -NoProfile -File scripts/verify-e2e.ps1 -EnableGf6 ...
```
绿判据：
1. 退出码 0、`FAIL=0`；
2. **GF5 与 TC23 的"无新帧"断言仍绿**（读窗放大后不得由"漏帧"转成"误报有帧"）；
3. m2t2a-smoke：`node scripts/m2t2a-smoke.mjs` 的断言①在有帧时 < 30s 内返回（**早停生效的实证**）；无帧场景下 `stopReason` 落在 `terminal:*` 而非 `window`；
4. 时序：GF/TC 用例总时长不得比改前显著变长（读窗 +2s × 3 处 ≈ +6s 属预期，超过需查早停是否反被拖慢）。

---

### 苞 G — 4 条立法落地（**落点标【待指挥官裁决】**）

#### G-1 落点建议（给指挥官，附理由）

| 方案 | 落点 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **G-A（推荐）** | `docs/adr/DECISION-REGISTER.md` 新增 **DC-16**（纪律条文）+ `docs/README.md:65` 的「命名纪律」节后加**指针**（一行，不复制条文） | DECISION-REGISTER 自述是"哪些决策已生效/推翻条件是什么"的**唯一事实源**，纪律条文属"准入规则"且需要推翻条件；README 是导航入口，只放指针符合它"只做导航，不复制结论"的自述口径（`:3`） | 与既有 15 条 DC 的形态（多为业务/技术裁定）略有差异，属"工程纪律"类 | **取此**。`docs/README.md:65-72` 的命名纪律正是同族先例（纪律写在 README），故**两处都写、职责分开**：README 一行指针 → REGISTER 全文 |
| G-B | 仅 `docs/README.md` 新增「数字纪律」节 | 与命名纪律同址，写作时最易被看到 | README 自述"只做导航"；纪律条文放这里会与 DC 体系脱节，无"生效/推翻条件"锚点 | 备选 |
| G-C | 两处都不写，只做 CI 门禁 | 强制力最强 | 门禁实现成本高（需静态判"数字有无注释"，误报率高）；且先有规则后有门禁 | 不建议作为唯一落点 |

> **门禁跟进（登记，不在本小批）**：立法 1（新数字无依据注释不得入库）的自动化形态建议作为 `scripts/ci/check-docs.mjs` 的**新断言**评估，但该门禁当前 6 条断言均为"字面/清单"级判定，做"注释存在性"判定误报风险高，须先有免责清单机制。→ 登记为观察项（见 §5.2）。

#### G-2 条文草案（可直接粘贴为 DC-16）

> **DC-16 | 数字规格纪律（四条）**：
> ① **新数字无依据注释不得入库**：任何新增/变更的数值型规格（超时 / 重试 / 退避 / 轮询 / 池 / 分页 / 缓冲 / 心跳 / TTL / 阈值 / 上限），必须在**引入处**（配置键注释、常量 javadoc、脚本注释）写明**取值依据**——业界来源（含章节与来源名）、实测数据、或正式裁定编号，三者至少其一。写不出依据的，先补依据再入库，不得只留一个数。
> ② **用户可感知且跨层共享的数字必须配置键化**：同时满足"用户可感知"与"跨层共享/被多个模块引用"的数字，不得留在代码字面量里，须落到 `application.yml` 或等效配置面（并遵守项目既有的 `@ConfigurationProperties` 绑定口径）。仅前端内部同层共享的，可保留为导出常量。
> ③ **同值不同义禁止共用**：语义不同的两处规格**不得共用一个键/常量**，即使当前取值相同（同值会让人误读为同源，改动一处静默影响另一处）。分场景即分键，键名须自述场景。
> ④ **既有裸数字不返工，随包顺手补注释**：不为"清点裸数字"专门开批返工；凡因其他原因改到该文件/该行，顺手把该处的依据注释补齐（不为改注释而改行为）。
>
> **证据等级**：【军规】（工程纪律，源自 2026-10-10 数字规格清点：全仓 145 条数字规格中 72 条为裸数字、107 条硬编码）。
> **推翻条件**：①/③ 导致合理的共享常量被强行拆散、或注释维护成本超过收益时重审；② 与框架缺省值冲突（如 Hikari/Tomcat 缺省）时允许"显式写出缺省值 + 注释"的等价形态。
> **实施锚点**：本纪律的首次落地批次 = 数字规格小批（裁决①~⑥），其注释文案见该批施工卡。

#### G-3 `docs/README.md` 指针（草案）

在 `:67`（命名纪律第一条）之后新增一条：

> - **数字纪律（DC-16）**：新增/变更数值型规格（超时/重试/退避/轮询/池/分页/缓冲/心跳/TTL/阈值）必须在引入处写明取值依据（业界来源/实测/裁定编号）；用户可感知且跨层共享的数字须配置键化；同值不同义禁止共用一个键；既有裸数字不返工、随包顺手补注释。全文见 `docs/adr/DECISION-REGISTER.md` DC-16。

#### G-4 苞 G 验收

```bash
node scripts/ci/check-docs.mjs
```
绿判据：6 条断言全过（尤其断言 ①「导航文档无盘符路径」—— 新增的 README 行不得含盘符/本机路径；断言 ⑤「命名纪律」—— 新增文本不得出现被禁字样组合）。
另：`git diff docs/adr/DECISION-REGISTER.md` 须为**纯追加**（表内插入一行 + 表尾不动历史行）。

---

## 3. 依据注释文案汇总（速查）

| 数字 | 处 | 引用来源（业界依据.md 章节名） |
|---|---|---|
| 60s（保留） | `api/http.ts` | §1「HTTP 客户端超时（connect / read / write）」：axios 官方源码缺省 0 = 不超时；内网推荐整体 60–120s |
| 20 | `AiTools.LIST_TASKS_MAX_ROWS` | §8「分页大小主流默认」：GitHub 30/上限 100、Stripe 10/上限 100，内网推荐默认 20 |
| 600s | `app.ai.hitl.confirm-timeout` | §5「人机确认门（HITL approval）超时」：唯一硬结论"不设超时=永久卡死"（AWS Step Functions）；交互确认推荐 10 分钟 |
| 60s | `app.ai.hitl.frontend-tool-timeout` | §6「前端工具调用超时（AI 工具调用执行预算）」：MCP 只规定 SHOULD；实现默认常 60s，Vercel AI SDK `toolMs` 5–60s |
| 4/16/200 + 30s | `AsyncConfig` | §7「数据库连接池（HikariCP）」：官方反对按并发数放大池子；§12「任务看门狗」各框架默认值横向对照 |
| 10 | `spring.datasource.hikari.maximum-pool-size` | §7：HikariCP 官方默认 `maximumPoolSize = 10`；`minimumIdle` 官方建议不设 |
| 20 | `DataController` 分页缺省 | §8：同上 |
| 5000ms | `Read-Sse-Brief` 读窗 | §2「SSE 心跳间隔与写超时」（`heartbeat < min(代理超时)` 实践公式）+ §12（K8s `periodSeconds`×`failureThreshold` 模式）；被观察节拍 = `ConfirmGate.HEARTBEAT_SECONDS = 2` |
| 推导上界（非写死） | `m2t2a-smoke.mjs` AWAIT | 事实源 = 服务端 `/api/ai/health` 的 `resilience.totalBudget`（`app.ai.resilience.total-budget`） |

**统一口径要求**：每条注释都须写明「本值是/不是新值」与「改本值要连带看哪里」，与立法 1/3 呼应。

---

## 4. 验收标准（总）

| 层 | 命令 | 绿判据 | 适用苞 |
|---|---|---|---|
| 后端单测 | `cd backend && mvn -B test` | `BUILD SUCCESS`；`Failures: 0, Errors: 0`；总时长不得异常膨胀（拆键后留意未注入小值的用例） | A/B/D/E |
| 后端定向 | `mvn -B test -Dtest='ConfirmGate*'`、`-Dtest=AiToolsListTasksTruncationTest` | 定向用例全绿 | A/B |
| 前端 | `cd frontend && yarn typecheck && yarn build` | `vue-tsc --noEmit` 0 error；build 成功 | C/D |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6 条断言全过 | G（及所有改到导航文档/注释的苞） |
| E2E | `pwsh -NoProfile -File scripts/verify-e2e.ps1 -EnableGf6 …`（CI 口径：桩上游 + redis + jar） | 退出码 0、`FAIL=0`；GF5/TC23 无新帧断言仍绿 | F（A 的间接回归面） |
| 冒烟 | `node scripts/m2t2a-smoke.mjs` | 断言①早停生效（有帧 < 30s 返回；无帧 stopReason 非 `window`） | F |
| 提交纪律 | `git log`/`git show --stat` | 每提交一个逻辑变更；`docs/adr/DECISION-REGISTER.md` 为纯追加 | 全部（DC-04） |
| 命名纪律 | `node scripts/ci/check-docs.mjs` 断言⑤ | 新增/改动的代码与文档注释不得出现被禁字样组合 | 全部 |

---

## 5. 登记文本草案

### 5.1 13 处 `Start-Sleep -Seconds 2`（**只登记不换**）

追加到 `docs/M2-排期计划.md` 去向表（批 A 提交后现态末行之后，只追加）：

> | R-1xx（数字规格清点 · E2E 时序脆弱面）`scripts/verify-e2e.ps1` 的 **13 处 `Start-Sleep -Seconds 2`**（固定等待替代条件等待，行号按当前工作树实读）：GF1 `:1515`（schema 漂移重试）/ `:1522`（未触发表单重试）；GF2 `:1607` / `:1614`；GF3 `:1687` / `:1694`；GF4 `:1781` / `:1788`；GF6 `:1924`；TC17 `:2009`（换会话前）/ `:2017`（未调用工具重试）；TC18 `:2088`；TC23 `:2172`。**九处属"模型抖动重试的 back-off"、四处属"换会话 / 等挂起落地"的节拍**；仅 3 处附近有注释说明理由。 | **观察项（登记不换，2026-10-10 数字规格清点裁决⑤）**：本轮**不改**。理由：换成条件等待需为**每一处**给出可观测判据（`Wait-Until` / `GetJson` 轮询目标），属逐用例改造；散改会同时抬高"用例失败"与"基础设施失败"的混淆面，且必须在同一次改动里重跑全量 E2E 才敢定论。**下批候选统一形态** = 全部收敛为 `Wait-Until { <条件> } 60 '<用例X的Y就绪>'`（函数已存在，`:166`），并在每个用例头注明"为什么这个条件是这个"；改造须与 E2E 时序包同批设计。来源 = 仓库外数字规格清点报告 `<SCRATCH>/numeric-spec-audit/清单总账.md` §2 H-06 / §1.6 S-16 |

### 5.2 其余需登记的条目（建议同批追加）

| # | 条目 | 状态/处置 |
|---|---|---|
| **N-1** | **P-1 前端执行器预算在 60s 键下塌陷到 5s**（`frontend-tools.ts:191/200/208/225-247`：`预算 = 服务端上限 − 回灌 POST 60s`，60s 键 ⇒ `usableMs=0` ⇒ 5s 下限；该文件自述 ≤65s 为病态配置；实测最慢成功回灌 30.6s） | **立项（S1 级，本小批的必读前置）**：取值下调须与"回灌 POST 单独给更短上限"（等待治理 R-99 ②）同批；本小批按 A0 处理（键结构先落、取值暂 120s） |
| **N-2** | **P-2 确认门 600s 在 `total-budget` 300s 下不可达**（`StreamWatchdog:130` 总预算判据先于"工具活跃期不计静默"；`AiController:174/:295/:664` emitter 寿命 = 300s；`ResilientChatService:374` 墙钟兜底；`CancellationRegistry:231` 取消键 TTL = 600s 与挂起同刻） | **立项（S1 级）**：`total-budget ≥ confirm-timeout + 余量` 的上限关系须显式化；600s 全量生效随 R-99 ③（total-budget 三语义拆分）同批 |
| **N-3** | `SpreadGrid.vue:97` 的"60s 明显小于后端 120s"论证在拆键后失效（前端就绪 60s vs 前端工具挂起 60s **同值**） | **苞 A 内必改**（A-4-10）；改写为如实口径 |
| **N-4** | 读窗 `:1810` 的 **3500ms**（GF4-A3） | **【待指挥官裁决】**：裁决⑤只授权 3000→5000；按同一判据建议同改 5000 |
| **N-5** | `m2t2a-smoke.mjs:362` `deadline = Date.now() + 150_000`（3d 收敛等待） | **登记不改**（裁决⑤未授权；与 F-2 同族，改法同上：事件驱动 + 推导上界） |
| **N-6** | `m2t2a-smoke.mjs:143/239` 合成帧 `timeoutSeconds: 120` + 轮询 `12 × 500ms` | **登记不改**（S-31 已登记；注意：拆键后 120 不再对应任何键，与 `verify-e2e.ps1:1387`"不硬编码 120"的纪律形成**同仓两种相反做法**，须在留痕里点名） |
| **N-7** | `spring.datasource.hikari` 从"框架缺省"升为显式键后，`/health` 未回显连接池 | **登记（可观测性对称）**：与既有"投递池指标实调"同族，非本小批 |
| **N-8** | 立法 1 的门禁化（`check-docs.mjs` 新断言：数字有无依据注释） | **登记（观察项）**：当前离线静态判定误报率高，须先有免责清单机制 |
| **N-9** | A 增/不增 `/health` 的 `frontendToolTimeoutSeconds` | **【待指挥官裁决】**：不加也不阻塞 F-2（F-2 用既有 `resilience.totalBudget`） |
| **N-10** | `docs/03-技术方案文档-v2.1.md:551/:270/:642` 引旧键名 `app.ai.hitl-timeout` | **【待指挥官裁决】**：建议只加行尾注记指向新键，不回改冻结正文 |

---

## 6. 【待指挥官裁决】清单（汇总，按优先级）

1. **P-1 前端折算塌陷**（A0 / A1 / A2）—— 决定 `frontend-tool-timeout` 本批取值是 60s 还是暂维 120s。
2. **P-2 600s 与 `total-budget` 300s**（B0 / B1 / B2）—— 决定 `confirm-timeout` 本批取值与是否夹带 total-budget 调整。
3. **键名形态**：双新键（`confirm-timeout` + `frontend-tool-timeout`）vs 保留旧键 `timeout` 承载确认门。
4. **N-4**：`Read-Sse-Brief` 的 3500ms 是否同改 5000。
5. **苞 G 落点**：G-A（REGISTER DC-16 + README 指针，推荐）/ G-B（仅 README）/ G-C（仅门禁）。
6. 次要：`@Tool` description 是否补上限（B-1.4）、`/health` 是否增键（N-9）、`DataBrowserView` 之外的分页缺省（`JobController` 50）是否统一（D-3）、确认门是否补参数化用例（A-3.5）、`docs/03` 冻结正文是否回改（N-10）。

---

## 7. 边界（⑦ 与明确不做）

- **⑦ 作业等待三套（前端 300s 维持 + 后端补看门狗：心跳×3 判死 + 30min 绝对上限）**：**不在本小批**，随批 B。本卡只写边界：
  - 前端 `api/jobs.ts:52` `timeoutMs ?? 300_000` **维持现值**（等待治理 R-89② 已登记"超时返回非终态而不抛错"的面）；
  - 后端看门狗（心跳×3 判死 + 30min 绝对上限）的方案依据在 `业界依据 §12`（K8s `periodSeconds`×`failureThreshold`、Hangfire 30min、Quartz 60s），落地位置与 E2E `Wait-Job` 的 96s 上限（`verify-e2e.ps1:157-164`，S-10）的同步调整须**同批**；
  - 本小批**不得**顺手改 `api/jobs.ts` 或作业侧超时（避免与批 B 撞车）。
- **明确不做**（超出授权）：`AsyncConfig` 数值调整、Hikari `maximumPoolSize` 取值调整、`AiTools` 的 `findAll` 优化/分页参数化、`api/sse.ts` 的重连退避与 jitter、`api/tasks.ts` 的重连接管、Excel 面（`utils/excel.ts` / `ExcelWriter` / `ExcelTemplateBuilder`）、`ContextBuilder` 300/800、双 1024、`B2-15/B2-16` 同值不同义拆分、`S-10`/`S-11`/`S-12`/`S-15`/`S-31`~`S-33`、`V-03`、`S-01`、`B2-21` 表单十项、spike 工程。以上均在总账有登记，本小批不触碰。

---

## 8. 起草方法与覆盖面（供复核）

- **实读面**：`<MAIN_REPO>` 工作树现态（含批 A 在制品）逐文件实读：`AiProperties.java` 全 343 行、`ConfirmGate.java` 全 654 行、`application.yml` 全 189 行、`ConfirmGateFrontendTimeoutTextTest.java` 全 165 行、`http.ts` 全 156 行、`AiTools.java`/`AsyncConfig.java`/`DataController.java`/`JobController.java`/`TaskController.java`/`frontend-tools.ts`/`SpreadGrid.vue`/`workspace.ts`/`DataBrowserView.vue` 相关段；`verify-e2e.ps1` 的 13 处 + 3 处读窗 + `Read-Sse-Brief` 函数体；`m2t2a-smoke.mjs` 阶段 3a/3d；`docs/README.md` 全 72 行、`docs/adr/DECISION-REGISTER.md` 全 34 行、`docs/adr/DECISION-CARDS.md` 首 60 行、`docs/M2-排期计划.md` 去向表与注记节、`docs/s4/S4.2-*.md:60-80`。
- **机读核对**：`grep -n` 核对总账全部 6 个裁决的行号（§0.1 表）；`grep -rn "hitl"` 全仓定位拆键的全部读键点（§2 苞 A-4 的 15 行）；`grep -rn "hitlTimeout"` 确认诊断快照键无测试断言。
- **未做的事**：未修改 `<MAIN_REPO>` 任何文件；未做 git 写操作；未跑构建与测试（起草阶段无改动可测）；未对 §0.3 的两个阻塞项做动态验证（**静态阅读结论**，建议苞 A 开工前用一次 `mvn -B test` + 一次真实挂起复现来确认 P-2 的"300s 先到"）。
- **可能的遗漏**：`docs/evidence/` 内历史证据文档引用的旧键名未逐条追查；`spike/*/application.yml` 的 `confirm-timeout-seconds` / `frontend-tool-timeout-seconds`（SP-01ab/SP-04）与产品键的命名对应关系未对齐（spike 是**非产品路径**，本卡不动，登记备查）。
