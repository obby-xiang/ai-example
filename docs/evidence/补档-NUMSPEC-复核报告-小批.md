> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 独立复核报告 —— 数字规格小批（苞 A/B/F/G + 文档修编）

- 复核对象：`<MAIN_REPO>`（分支 `main`）**全部未提交改动**（22 个已改文件 + 1 个未跟踪新测试类）
- 复核方式：独立复现 + 对抗反查；**严格只读**（未编辑/未 `git add`/未提交；仅产生 `backend/target/`、`frontend/dist/` 构建产物与 `<SCRATCH>` 下的复核证据）
- 复核时点：2026-10-10（本机）
- 证据根目录：`<SCRATCH>/numeric-spec-audit/rev-e2e/`（E2E 实跑编排、栈日志、SSE/HTTP 落盘）
- 结论：**带条件 PASS**（0×BLOCKER / 0×MAJOR / 3×MINOR / 4×观察；条件见文末）

---

## 1. diff 全览与夹带排查 —— PASS

`git status --porcelain` = 22 改 + 1 未跟踪，与清单吻合，无夹带：

| 面 | 文件 | 与清单匹配 |
|---|---|---|
| 苞 A 后端 | `ai/config/AiProperties.java`、`ai/gate/ConfirmGate.java`、`ai/web/AiController.java`、`ai/run/ResilientChatService.java`、`application.yml` | 是 |
| 苞 A 连带 javadoc | `ai/exec/AiExecutorConfig.java`、`ai/run/StreamWatchdog.java`、`ai/tools/AiTools.java`（前端通道口径注释）、`frontend/src/components/SpreadGrid.vue`、`frontend/src/types/ai.ts`、`frontend/src/utils/frontend-tools.ts` | 是（均为旧键名引用同步，属苞 A 语义面） |
| 苞 A 测试 | `AiPropertiesT35DefaultsTest`、`ConfirmGateFrontendTimeoutTextTest`、`AiControllerHealthSseDeliveryTest` | 是 |
| 苞 B | `ai/tools/AiTools.java`、新类 `ai/tools/AiToolsListTasksTruncationTest.java`（未跟踪） | 是 |
| 苞 F | `scripts/verify-e2e.ps1`、`scripts/m2t2a-smoke.mjs` | 是 |
| 苞 G+修编 | `docs/adr/DECISION-REGISTER.md`、`docs/README.md`、`docs/M2-排期计划.md`、`README.md`、`docs/03-技术方案文档-v2.1.md`（行尾注记）、`docs/s4/S4.2-AI-Runtime-合流规格.md`（键表换行） | 是 |

夹带专项排查（全部通过）：

- 敏感文件：`git status` 无 `.env`/密钥/凭据类文件新增；`git ls-files` 命中的唯一 `*env*` 是既有 `frontend/.env.example`（未改）。
- 新增行内容扫描（`git diff -U0` 新增行）：无 `sk-…` 形态密钥、无 `api-key/password/token=` 赋值、无盘符绝对路径、无用户主目录写法、无内网网段字面量 —— **0 命中**。
- 仓内文件盘符路径：对 22 个改动文件逐文件 grep `[A-Za-z]:\` 与 `[A-Za-z]:/` —— **0 命中**；新增文档引用一律 `<SCRATCH>`/`<REPO_ROOT>` 占位符。
- 命名纪律（`main` + 分隔符 + `v2`）：`node scripts/ci/check-docs.mjs` 断言 ⑤ 按 `git ls-files` 全量扫（919 路径，实扫 916，豁免 3）PASS；未跟踪新类路径 `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java` 人工核对无该 token。
- 构建产物未污染工作树：`frontend/dist/`、`backend/target/` 均被 `.gitignore` 覆盖；复核前后 `git status` 条目数恒为 23。

---

## 2. 语义对抗抽查（打开文件实读）—— PASS

### 苞 A 拆双键

| 抽检项 | 实读位置 | 结论 |
|---|---|---|
| `application.yml` `confirm-timeout: 240s` | `backend/src/main/resources/application.yml:126` | PASS；注释（`:112-125`）写明"等人/依据 §5/10 分钟推荐值/本轮 240s 过渡值/受 `total-budget` 300s 约束" |
| `application.yml` `frontend-tool-timeout: 120s` | 同文件 `:127` | PASS；注释写明"等机器/依据 §6/降到 60s 的前置 = 前端折算面专项（P-1 / R-99②）" |
| 注释含"降 60s 前置=折算专项" | 同文件 `:124-125` | PASS（点名 `frontend/src/utils/frontend-tools.ts` 的"预算 = 服务端上限 − 回灌 POST 60s"与 `≤65s 病态配置`、`实测最慢成功回灌 30.6s`） |
| 确认门路径读 `confirmTimeout` 并传 `confirm=true` | `ai/gate/ConfirmGate.java:191`（`awaitDecision` → `await(..., confirmTimeoutSeconds(), true)`），取键在 `:169-171` | PASS |
| 前端工具路径读 `frontendToolTimeout` | `ConfirmGate.java:200`（`awaitFrontendResult` → `await(..., frontendToolTimeoutSeconds(), false)`），取键在 `:180-182` | PASS（两条路径键不同 + `confirm` 标志不同，无串读） |
| `/health` 含 `frontendToolTimeoutSeconds` | `ai/web/AiController.java:538`（`@GetMapping("/health")` 在 `:527`，类前缀 `:82` ⇒ `/api/ai/health`） | PASS |
| 诊断快照键拆分 | `ai/run/ResilientChatService.java:770-771`（`confirmTimeout` / `frontendToolTimeout`，旧 `hitlTimeout` 已删） | PASS |
| 旧字段彻底移除 | 全仓 `getHitl()` 调用仅 `getConfirmTimeout()`/`getFrontendToolTimeout()`；无 `Hitl#getTimeout` 残留 | PASS |
| **活代码：yml 值 → 运行值**（独立复现） | 起 jar 后 `GET /api/ai/health` 实测回 `confirmTimeoutSeconds=240 frontendToolTimeoutSeconds=120`，`resilience.totalBudget=PT5M` | PASS（后端实际绑定的就是这两个新键） |

### 苞 B list_tasks

- `AiTools.java:141` `static final int LIST_TASKS_MAX_ROWS = 20;`，javadoc 写明依据（业界 §8：GitHub 30/Stripe 10/内网推荐 20）**且**必须 `< app.ai.tool-result.max-rows`（实读 `application.yml:164` = 200，20 < 200 成立）。
- `AiTools.java:155` `tasks.stream().limit(LIST_TASKS_MAX_ROWS)`。
- `AiTools.java:159-160` 截断告知分支：`if (tasks.size() <= LIST_TASKS_MAX_ROWS) return rows;` 否则追加 `（共 N 条，仅显示前 20 条）` —— 边界"恰好 20 条不告知"逻辑正确。
- `AiTools.java:152` `@Tool description` = "…最多返回前 20 条，超出时给出总条数"，与常量/告知三处口径一致。
- 新测试类实跑：`tests="2" errors="0" failures="0"`（`AiToolsListTasksTruncationTest`，21 条→截断+告知；20 条→不告知且含"任务 20"）。

### 苞 F 读窗与等待改造

- `scripts/verify-e2e.ps1` `Read-Sse-Brief` 全部调用点实读 = **3 处，均 5000**：`:1818`（GF4-A3）、`:1888`（GF5-A4）、`:2203`（TC23④）。全文件 `3000`/`3500` 仅剩函数头注释（`:1309`/`:1312`/`:1313`）——**无第四处遗漏**。
- 函数头依据注释 `:1307-1314`：判据"窗口 ≥ 2 个心跳周期 + 抖动余量 = 2s×2+1s"，引 `ConfirmGate.HEARTBEAT_SECONDS = 2`，并标注三条调用点与 `R-104`。
- 调用点文案同步：GF4 `:1816-1819`、`:1851`（"5s 无帧"）实读一致。
- `scripts/m2t2a-smoke.mjs`：`node --check` 语法 PASS；`deriveAwaitCap`（`:209-229`）读 `/api/ai/health` → `resilience.totalBudget`；`parseIsoDurationMs`（`:199-206`）实测 `PT5M→300000`、`PT300S→300000`、非法值（`''`/`P`/`PT`/`PT0S`/非字符串）→ `null` 回落；回落常量 `FALLBACK_AWAIT_MS = 330_000`（= 缺省 300s + 30s，与服务端现值推导值同值）。
- `readSse`（`:243-271`）新增 `until` 终止帧早停（`done`/`error` → `stopReason=terminal:<type>`）与 `stopReason` 四态归因；第三参兼容旧数字签名；全文件唯一调用点 `:331` 使用对象形态。
- M2 登记"13 处 `Start-Sleep -Seconds 2`"：实读**恰好 13 处**，行号 `:1523/:1530/:1615/:1622/:1695/:1702/:1789/:1796/:1932/:2017/:2025/:2096/:2180` 与 `R-104` 行逐一对应；且"苞 F 前实读值 = 现态 − 8"（函数头净增 8 行注释）**13 条全部对得上**（如 `1523-8=1515`✓、`2180-8=2172`✓）。

### 苞 G / 文档修编

- `docs/adr/DECISION-REGISTER.md:20` DC-16 行**全文四条**在位（①无依据不得入库 ②可感知且跨层共享须键化 ③同值不同义禁止共用 ④既有裸数字不返工随包补注释 + 实施锚点），状态列 `生效`。
- DC 表 16 行，`DC-01`…`DC-16` 各**恰好 1 次**，**编号唯一**、**列数一致（9 列）**；`:32` 判定对象同步为 `DC-01~DC-16`。
- 根 `README.md:7` 同步 `DC-01~16`；`docs/README.md:15` 同步 `DC-01~16 全部已签`，并新增末行"数字纪律（DC-16）"指针。
- M2 去向表（`:191`~`:296`，106 行）**列数全为 2 列，无错行**；末段编号格局实读 = `…R-99, R-100, R-101, R-104, R-105, R-106` —— **`R-102`/`R-103` 空号成立**（表内无对应行），与"重编号留痕"小节（`:377`）与"空号说明"一致；`R-98` 行尾注记（W-12 `.limit(10)` 现值 20）、`R-99` 行完成留痕、`R-41` 行尾注记均在位且为"只追加"。
- `scripts/verify-e2e.ps1:1395` 的"不硬编码 120，与 `app.ai.hitl.frontend-tool-timeout` 解耦"与 N-6 行引用的 `:1395`（苞 F 前 `:1387`）**实读一致**。
- `docs/s4/S4.2-AI-Runtime-合流规格.md:69-70`：旧单行 `app.ai.hitl.timeout | 120s` **已被替换**为两行（240s / 120s），无重复行。

---

## 3. 独立复现（测试实测数字）

### 3.1 后端全量（XML 口径）

命令：`<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test`（在 `backend/`，走项目既有 wrapper）

| 指标 | 实测 |
|---|---|
| surefire XML 数 | **60**（基线 `6e59e0c` 为 59 ⇒ **+1 类**，只增不减 ✓） |
| 汇总 tests | **389** |
| failures / errors / skipped | **0 / 0 / 0** |
| Maven 退出 | `BUILD SUCCESS`，`MVN_EXIT=0` |

- 与规格预期一致：`≥389 / 0 / 0 / 0`，60 XML。较基线的增量 = 苞 A `+4`（`ConfirmGateFrontendTimeoutTextTest` 3→5、`AiPropertiesT35DefaultsTest` 2→3、`AiControllerHealthSseDeliveryTest` 2→3）+ 苞 B `+2`（新类），合计 `+6`（383→389）。
- 已知 flaky（R-105）`SseChatEmitterWriteBudgetTest`：本次全量**未复现红**（`Tests run: 6, Failures: 0`），故无需单类重跑。
- 抽查类内计数：`ConfirmGateFrontendTimeoutTextTest` tests=5（前端工具 2 参数 + 确认门 1s 文案锁 + **确认门新增 2 参数**）、`AiPropertiesT35DefaultsTest` tests=3、`AiControllerHealthSseDeliveryTest` tests=3。

### 3.2 前端

| 命令 | 结果 |
|---|---|
| `yarn typecheck`（`vue-tsc --noEmit`） | **绿**，`TYPECHECK_EXIT=0` |
| `yarn build`（`vue-tsc && vite build`） | **绿**，`✓ built in 1m 38s`，`BUILD_EXIT=0` |

### 3.3 门禁

| 命令 | 结果 |
|---|---|
| `node scripts/ci/check-docs.mjs` | **6/6 断言绿**，`EXIT=0`（含断言 ⑤ 命名纪律规则自检 5/5 + 24/24 + 负对照 11 零误伤） |

### 3.4 E2E 桩模式全量（29 用例 · `-EnableGf6`）

编排：`<SCRATCH>/numeric-spec-audit/rev-e2e/run-e2e-rev.sh`（与仓库既有 `scripts/verify-e2e.ps1` 用法一致：桩 `--violate-form-type` + 现树重打包 jar + redis）

- 端口纪律：启动前 `netstat` 预检 `18399/18330` 均空闲、`6379` 在听；收尾只按监听端口的 PID `taskkill`（`18330` PID、`18399` PID 各一），结束核验"已释放"。
- 被复现的 jar **含本批代码**：`mvn -DskipTests package` `BUILD SUCCESS`；解包核对 `BOOT-INF/classes/…/AiProperties$Hitl.class` 仅含 `confirmTimeout`/`frontendToolTimeout`（旧 `timeout` 无）；源码 mtime（20:58/21:12）< class mtime（21:13），编译"up-to-date"成立。
- 结果：**`结果：PASS=29  FAIL=0`**、`GFSKIP=0`、`Q8 项：PASS=2 FAIL=0`、`E2E_EXIT=0`（elapsed 60s）。
- 关键新面实跑命中：`GF4 复核拒绝：违规值 400（PENDING 保持/5s 无帧）…` ⇒ **5000 读窗在真链路跑通**；`TC15` 工具面 13 个工具；`TC16/TC17/TC23` 确认门与前端挂起双路径全绿；`GF1–GF6` 全绿。
- 复核后工作树 `git status` 条目数仍 = 23（只读未被破坏）。

---

## 4. 对抗反查（主动找茬）

| 反查项 | 方法 | 结果 |
|---|---|---|
| 旧键残留（`hitl.timeout` / `hitl-timeout`） | 全仓（排除 `.git`/`node_modules`/`target`）grep | 活代码**0 处**；剩余命中全部可解释：① 冻结文档 `docs/03-技术方案文档-v2.1.md:270/551/642` 三处**行尾注记**（原文不回改，已注明拆分）；② M2 登记原文 `R-41`/`R-99` 行 + 行尾注记；③ `docs/evidence/**` 历史证据（档案，非依据）；④ `AiProperties.java:15`/`AppProperties.java:17` 提到的基座 `hitl-timeout-minutes`（**另一个**已删键的解释性 javadoc，非活引用） |
| 旧键被脚本/CI 覆盖使用 | 非 md 文件 grep `app.ai.hitl` / `hitl` | 无脚本/配置文件使用旧键（无"静默回落"现实面） |
| `list_tasks` 别处硬编码 10 | 全仓 grep `limit(10)` | 仅 M2 `R-98` 行正文（含苞 B 行尾注记说明现值 20），**代码 0 处** |
| 读窗第四处遗漏 | `Read-Sse-Brief` 全部调用点 + `3000`/`3500` 全文件 grep | 调用点 3 处全 5000；`3000/3500` 只剩注释 —— 无遗漏 |
| M2 表格列数一致 | 逐行解析 | 去向表 106 行全 2 列；`R-100~R-106` 5 行在位 |
| DC-16 编号唯一 | 逐行解析 | `DC-01`…`DC-16` 各 1 次，列数 9 全一致 |
| 文档行号锚点自洽 | `R-104` 13 锚点 ±8 校验、`R-106`/N-5/N-6 锚点实读 | 13/13 对得上；N-6 的 `:1395`、`:431`、`:143` 一致；`injectExpr` 引 `:151-160` 而函数签名实在 `:150`（体 151-162）—— ±1 行偏差 |
| `R-106`（CDP 冒烟既有缺陷）技术判断 | 实读 `frontend/src/stores/ai.ts:1157-1187` + 确认提交 `99d00d2` | **判断成立**：`assistantId` 有值时 `ownedByActiveRound=false`（`activeRoundHostId===null`），非终帧走 `if (!ownedByActiveRound) { … return }`，`pendingCall` 确不会被写入 ⇒ 注入式冒烟阶段 1/2 断言必 FAIL |
| 新测试是否真的能守住"值" | 实读三个测试类 + grep 测试资源 | 见 MINOR-1（yml 值无绑定断言） |

---

## 5. 发现清单（分级）

### BLOCKER
无。

### MAJOR
无。

### MINOR

1. **`application.yml` 的两个挂起键取值缺"配置绑定"断言**（苞 A）
   `AiPropertiesT35DefaultsTest#hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget` 用的是 `new AiProperties()` 的**类默认值**；`AiControllerHealthSseDeliveryTest#healthExposesBothSuspendTimeoutsAfterKeySplit` 断言的是 **mock 桩值**（`when(gate.confirmTimeoutSeconds()).thenReturn(240)`），`backend/src/test` 下不存在 `application.yml`（无 `src/test/resources`），也没有任何测试把 yml 绑进 `AiProperties`。后果：若只改 `application.yml`（例如把 `confirm-timeout` 改成 300s 而不动类默认值），上述用例**全部照绿**，而运行值已变、`confirm-timeout < total-budget` 的上限关系断言也不再覆盖真实值。
   现状无缺陷（我实读 yml = 类默认 = 240/120，且起 jar 实测 `/health` 回 240/120），故判 MINOR；建议同批或下批补一条绑定断言（`@SpringBootTest` 或 `ApplicationContextRunner` 读 yml 断言两键值与上限关系）。

2. **M2 `N-5`/`R-106` 的 `injectExpr` 行号锚点 ±1 行**（苞 G）
   `docs/M2-排期计划.md` 引 `injectExpr`（`:151-160`），实读函数签名在 `scripts/m2t2a-smoke.mjs:150`、函数体到 `:162`（`:153` 的 `ai.handleFrame(FRAME, m${i}.id)` 引用正确）。该节自称"行号口径按苞 F 落盘后实读"，故属轻微口径偏差，不影响结论。

3. **同节"全文件无 `R-102`/`R-103` 残留"措辞与字面张力**（苞 G）
   `docs/M2-排期计划.md:377` 的括注称"全文件无 `R-102` / `R-103` 残留"，但该行正文本身即出现 `R-102`/`R-103`（描述让号与空号）。按"去向表内无对应行"读是对的，按"全文件"字面读偏低。建议后续措辞收窄为"去向表编号序列无该两号"。

### 观察项（不影响判定）

1. **旧键被外部覆盖会静默回落**：`app.ai.hitl.timeout` 已从类与 yml 双方移除，若运维仍以 `--app.ai.hitl.timeout=20s` 启动，Spring Boot 默认忽略未知属性 ⇒ 静默使用 240s，无 WARN。仓内无此用法（已全仓核实），故仅登记。
2. **"事实源"与"回落值"当下不可区分**：`scripts/m2t2a-smoke.mjs` 的 `deriveAwaitCap` 回落常量 330_000 与实测服务端推导值（`PT5M`+30s）**同值**，因此本次实跑无法证明它真的读到了服务端值（需强制回落实验才能验证分支）。逻辑与解析函数已实测正确（含非法输入→`null`）。
3. **`verify-e2e.ps1` 13 处 `Start-Sleep` 未改（登记不换）**：与 `R-104` 行结论一致（本轮不改，入下批 E2E 时序包）；本次全量 29/29 绿说明当下不构成风险。
4. **`scripts/m2t2a-smoke.mjs` 与 `verify-e2e.ps1:1395` 纪律相反的既存面**：前者硬编码 `timeoutSeconds:120`、后者断言"不硬编码 120"。苞 A 拆键后被登记为新口径冲突（`N-6`）且**本轮未收口**，判断属实、属既存面。

---

## 6. 结论与条件

**带条件 PASS。** 五个验证面全部实测通过，未发现夹带、遗漏或语义不一致达到 MAJOR 及以上；三处 MINOR 均为"缺锁/行号口径/措辞"层面，不改行为。

带条件的"条件"（供指挥官裁决，非阻断）：

1. MINOR-1（yml 值无绑定断言）建议同批补一条绑定用例，否则"拆键后的取值"只有人工核对兜底；
2. MINOR-2/3（M2 行号与措辞）建议在下一次文档顺手改时收口，不必单独开批。

报告中所有路径均脱敏为 `<MAIN_REPO>`/`<SCRATCH>`，未含盘符、用户名或网段。
