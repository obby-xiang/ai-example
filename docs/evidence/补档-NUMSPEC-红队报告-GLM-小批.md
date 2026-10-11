> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# GLM 红队对抗性审计报告 —— 数字规格小批（未 push 三笔提交 + 工作树苞 A/B/F/G）

- 审计日期：2026-10-10
- 审计方：GLM（红队），机械检索由 DeepSeek 子智能体承担（仅检索，结论与定级均由 GLM 本人做出）
- 审计对象：主仓库 `<MAIN_REPO>`（分支 main，HEAD = `51ee49c`，ahead of origin/main 3）
- 审计方式：只读。未编辑任何仓库文件、未 git add/commit/push、未起服务占端口；运行了后端全量测试（`mvn -B test`）与 `check-docs.mjs` 门禁自跑，测试产生的 `target/` 构建产物除外。

---

## 0. 锚定防夹带（打钉口径与哈希）

审计起点时点：2026-10-10 晚（后端测试实跑开始于 22:11，BUILD SUCCESS 22:13）。两份锚点 diff 均由本人在仓库根用 `git diff` 导出至 `<SCRATCH>/numeric-spec-audit/` 后计算 SHA-256：

| 口径 | 范围 | SHA-256 | 字节 |
|---|---|---|---|
| 工作树未提交改动 | `git diff HEAD`（含未 push 三笔之后的工作树状态；未跟踪测试类另钉） | `74b6d4af08d644eb7e6485f3f64d1518c1c3c3232f23e2d0557ce2f3dd19eeea` | 116341 |
| 已提交未 push 三笔（合并视角） | `git diff 6e59e0c..HEAD`（a56613d + 6d5162b + 51ee49c） | `81a3f869092471f6f12f125223752d19d8da1e365865be72c0b76597c471944f` | 10391 |
| 未跟踪新文件（单独打钉） | `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java`（80 行） | `25ab2a37e2561f63f7c4fcdb9b5666636da5f0c9dc1eb2785c0ff0b5c30d0bf4` | — |

`git status --porcelain` 条目数：**23**（22 个 ` M` 修改 + 1 个 `??` 未跟踪测试类）。

`git diff HEAD --stat` 全量清单（22 文件，+397/−81）：

```
 README.md                                          |  2 +-
 backend/.../ai/config/AiProperties.java            | 53 +++++++++---
 backend/.../ai/exec/AiExecutorConfig.java          |  9 ++-
 backend/.../ai/gate/ConfirmGate.java               | 27 +++++--
 backend/.../ai/run/ResilientChatService.java       |  4 +-
 backend/.../ai/run/StreamWatchdog.java             |  7 +-
 backend/.../ai/tools/AiTools.java                  | 24 +++++-
 backend/.../ai/web/AiController.java               |  2 +
 backend/src/main/resources/application.yml         | 24 ++++--
 backend/.../ai/config/AiPropertiesT35DefaultsTest.java     | 30 ++++++-
 backend/.../ai/gate/ConfirmGateFrontendTimeoutTextTest.java | 81 +++++++++++++---
 backend/.../ai/web/AiControllerHealthSseDeliveryTest.java  | 21 ++++-
 docs/03-技术方案文档-v2.1.md                         |  6 +-
 docs/M2-排期计划.md                                 | 42 +++++++++-
 docs/README.md                                      |  3 +-
 docs/adr/DECISION-REGISTER.md                       |  3 +-
 docs/s4/S4.2-AI-Runtime-合流规格.md                 |  3 +-
 frontend/src/components/SpreadGrid.vue              | 11 ++-
 frontend/src/types/ai.ts                            |  2 +
 frontend/src/utils/frontend-tools.ts                |  9 ++-
 scripts/m2t2a-smoke.mjs                             | 93 ++++++++++++++++++---
 scripts/verify-e2e.ps1                              | 22 +++--
 22 files changed, 397 insertions(+), 81 deletions(-)
```

`git diff 6e59e0c..HEAD --stat`（三笔提交，5 文件，+77/−2）：AsyncConfig.java（+18）、DataController.java（+22/−1）、application.yml（+11）、frontend/src/api/http.ts（+24/−1）、DataBrowserView.vue（+4）。

提交侧核对防夹带时以上述哈希为准；工作树锚点文件为 `_anchor_worktree.diff` / `_anchor_commits.diff`（均在 `<SCRATCH>/numeric-spec-audit/`）。

---

## 1. 三笔已提交未 push 的审查

### 1.1 a56613d（HTTP_TIMEOUT_MS 常量化）

- `frontend/src/api/http.ts`：新增 `export const HTTP_TIMEOUT_MS = 60_000`，`axios.create` 改引用；原 `timeout: 60000` 字面量已移除（`git show` 核对，diff 内无残留双写）。取值零变化，与提交说明一致。
- 依据注释完整（axios 缺省 0 = 不超时、内网 60–120s 区间、SSE 通道另述），并交代三个下游常量的对齐关系与「本批不做同源化」的范围纪律。
- **对抗路径**：试图找「注释宣称对齐根但下游实际不等于 60s」的反证 —— 逐一核对 `frontend-tools.ts` 的 `FRONTEND_TOOL_TIMEOUT_HEADROOM` 链、`SpreadGrid.vue` 的 `READY_TIMEOUT_MS = 60_000`（本批 diff 内亲见）、`workspace.ts` 的 `DOWNLOAD_FETCH_TIMEOUT_MS = 60_000`。注释只声称「对齐关系」而非「代码同源」，且已声明同源化为下一步候选 —— 措辞与事实相符，**排除**。
- 结论：无发现。

### 1.2 6d5162b（分页缺省 50→20）

- `DataController#list` 的 `@RequestParam(defaultValue = "20")` 亲见；前端 `DataBrowserView.vue` 补注释、值 20 未动。
- **对抗路径 1（连带面真实性）**：提交宣称 JobController 50、TaskController 10 属「同源一致、本批不动」。抽查属实（本批 diff 未触这两个文件）。
- **对抗路径 2（注释是否过度宣称）**：javadoc 写「内网小数据量场景推荐……后端硬上限 100」，而 `DataController#list` **实际没有任何 size 上限 clamp**（亲读 :69-79，`size` 直通 `PageRequest.of(page, size)`，传 `size=100000` 不会被拒）。注释援引的业界依据段含「后端硬上限 100」推荐，实现未落。这不是本批引入（原 50 时代同样无 clamp），且 javadoc 措辞是「依据段转述」而非「本端点已设上限」的声明，但读者极易误读为已实现 → 记 **MINOR-1**。
- 结论：除 MINOR-1 外无发现。

### 1.3 51ee49c（容量链注释 + Hikari 显式落键）

- `AsyncConfig` 四个数值（4/16/200/30s）零改动，仅追加依据段；`application.yml` 新增 `spring.datasource.hikari.maximum-pool-size: 10`，与 Spring Boot 缺省等价（行为零变化），`minimumIdle` 刻意不设并写明理由 —— 均与提交说明一致。
- 「已知张力」段如实登记 16 > 10 的排队关系，非隐瞒。
- **对抗路径**：试图验证 Hikari 显式化是否真的零行为变化 —— `maximum-pool-size: 10` 即 HikariCP 官方缺省，显式写出不改变绑定结果；「同 `app.ai.sse.delivery-pool-size` 先例」的引用成立（既有配置已有显式化先例）。**排除**。
- 结论：无发现。

---

## 2. 苞 A：双键拆分（confirm-timeout=240s / frontend-tool-timeout=120s）

### 2.1 旧键活引用排查（对抗方向 1）

全仓 `hitl\.timeout` 共 26 处命中（DeepSeek 检索，本人逐类判定）：

- **Java/YML 活代码引用：0 处**。无 `@Value`、无 `Environment.getProperty("app.ai.hitl…")`、无 `getHitl().getTimeout()`；`AiProperties.Hitl.timeout` 字段已被 `confirmTimeout` / `frontendToolTimeout` 两字段物理替换（diff 亲见 `-private Duration timeout` 行）。`application.yml` 旧键 `timeout: 120s` 已删除（diff 亲见 `-      timeout: 120s`），新键两行落盘。
- **javadoc 历史陈述 1 处**（`ConfirmGate.java:197`「原单键……已按裁决③拆分」）—— 属变更说明而非活引用。
- **文档 25 处**：全部位于 `docs/evidence/`（证据/冻结文档，16 处）与 `docs/M2-排期计划.md`（3 处，均为登记原文或行尾注记）—— 属历史留痕性质。其中 `docs/03-技术方案文档-v2.1.md` 三处旧键名（`app.ai.hitl-timeout`，注意连字符形态）按冻结文档不回改、仅行尾注记，R-99 完成留痕里已如实登记该处置。**判定：无语义串读风险**。
- `ResilientChatService` 诊断快照旧键 `hitlTimeout` 已拆为 `confirmTimeout` / `frontendToolTimeout` 两键（diff 亲见），全仓无其它 `hitlTimeout` 消费方（grep 亲证）。

### 2.2 取值路径（对抗方向 2）

`ConfirmGate.java` 亲读 :168-201：

- `confirmTimeoutSeconds()`（:170）→ `getConfirmTimeout()`；`frontendToolTimeoutSeconds()`（:181）→ `getFrontendToolTimeout()`。
- `awaitDecision`（:190→:191）传 `confirmTimeoutSeconds()` + `confirm=true`；`awaitFrontendResult`（:199→:200）传 `frontendToolTimeoutSeconds()` + `confirm=false`。**无串读**。
- 调用方归属亲证：`SpToolCallingManager.java:273`（`awaitFrontendResult`）与 `:313`（`awaitDecision`）；挂起种类由 `kindOf`（:448-456）决定 —— `channelOf(toolName) == FRONTEND` → `KIND_FRONTEND`。`generative_form` 在 `AiTools.java:511` 注册为前端通道工具 → 归 FRONTEND → 走前端工具键。与文档宣称「generative_form 共用前端工具键」**一致**。
- `/health`：`AiController.java:536-538` 两字段各取各的方法；`AiController.java:667` `budgetMillis()` 取 `totalBudget` —— 苞 A 注释「SseEmitter 寿命亦取该值」的论证属实。

### 2.3 测试锁强度（对抗方向 8，脑内构造绕过）

- `ConfirmGateFrontendTimeoutTextTest`：拆键后两条路径**各自独立注入**（`gateWithFrontendTimeoutSeconds` / `gateWithConfirmTimeoutSeconds`），未注入键保持类默认（240/120）。构造错误实现逐一检验：
  - 「两键串读（确认门误用前端工具键）」→ 新增确认门参数化用例（2/3s 注入）的 `verify(emitter).confirmRequest(entry, injectedSeconds)` 抓住；
  - 「文案秒数硬编码」→ 2/3 双值参数化 + 从产出物正则读回（`confirmSecondsInText`）抓住；
  - 「前端工具文案误入确认门」→ `doesNotContain("收到前端回执")` 抓住；
  - 「确认门文案误入前端工具」→ `doesNotContain("结局未知")` 抓住。
  - 锁是真实的，**排除**。
- `AiControllerHealthSseDeliveryTest#healthExposesBothSuspendTimeoutsAfterKeySplit`：两个 stub 值刻意不同（240/120）且各挂各的 mock ——「两键写反」会被 jsonPath 配对断言抓住。**排除**。
- **可绕过面（构造成功）**：`AiPropertiesT35DefaultsTest#hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget` 用 `new AiProperties()` 类默认值；`AiControllerHealthSseDeliveryTest` 用 mock 桩值；仓内 `backend/src/test` **不存在 application.yml**（无 `src/test/resources`），也没有任何用例把 yml 绑进 `AiProperties`。构造错误形态：**只改 `application.yml`**（如 `confirm-timeout: 300s` 甚至 `> total-budget`）而不动类默认 —— 三处测试全部照绿，`confirm-timeout < total-budget` 的上限关系断言不再覆盖真实运行值。现态无缺陷（yml = 类默认 = 240/120 亲证一致），但锁存在「配置绑定」缺口 → **MINOR-2**（与既有复核报告 MINOR-1 同一条，登记在案待收口，本轮仍未闭环 —— 红队确认该缺口客观存在且未施工）。

### 2.4 其余对抗

- **旧键静默回落**：旧键删除后，外部 `--app.ai.hitl.timeout=20s` 启动参数会被 Spring Boot 静默忽略（未知属性无 WARN），运行值 240s。S42-P2 证据文档曾用 `--app.ai.hitl.timeout=20s` 做超时场景，若有人照旧文档重跑将得到错误时长且无提示。仓内活代码无此用法，属外部操作面 → **观察①**（既有复核报告观察①同款，登记知悉即可）。
- `AiExecutorConfig` 新增登记段如实交代「240s 抬升后池满 503 饱和窗口同比例翻倍」的连带面，非隐瞒。
- `frontend/src/types/ai.ts` 新增 `frontendToolTimeoutSeconds` 字段类型对齐 —— 前端暂无实际消费点（grep 亲证，`health.frontendToolTimeoutSeconds` 零命中）。观测面对齐意图明确（脚本侧 `m2t2a-smoke.mjs` 读的是 `resilience.totalBudget` 而非此字段），不构成缺陷 → **观察②**。

---

## 3. 苞 B：list_tasks 截断告知

`AiTools.java` 亲读（diff + 现态）：

- `LIST_TASKS_MAX_ROWS = 20`（package-private 常量）；`limit(LIST_TASKS_MAX_ROWS)`；`if (tasks.size() <= LIST_TASKS_MAX_ROWS) return rows;` 才追加「（共 N 条，仅显示前 20 条）」。
- **边界判定（对抗方向 3）**：恰好 20 条 → `20 <= 20` → 不告知，正确；21 条 → 告知，`tasks.size()` = 21 为全量总数，正确；总数字符串与常量同源插值（`"前 " + LIST_TASKS_MAX_ROWS + " 条"`），无第二处硬编码。`@Tool` description 同步写明「最多返回前 20 条，超出时给出总条数」，与实现一致（但见 5.3 观察④的硬编码描述文本问题）。
- **测试锁（对抗方向 8）**：`AiToolsListTasksTruncationTest` 两用例。构造绕过检验：
  - 「改回 10」→ `assertThat(AiTools.LIST_TASKS_MAX_ROWS).isEqualTo(SPEC_MAX_ROWS)` 互锁抓住；
  - 「恒告知（恰 20 也加尾行）」→ 边界用例 `doesNotContain("仅显示前")` 抓住；
  - 「告知总数写成 20」→ `isEqualTo("（共 21 条，仅显示前 20 条）")` 全字面锁定抓住；
  - 「第 21 条泄进行列表」→ `doesNotContain("任务 21")` 抓住。
  - 锁是真实的。唯一理论缺口是「常量与 SPEC_MAX_ROWS 同时被改成同一个错值」，属可接受的锁同源极限。**排除**。
- W-12 的另两面（全表 `findAll()` 读 + 挂起池线程占用）本轮未动，已在 R-98 行尾注记如实登记「本轮未动」。**排除**。
- 结论：无发现。

---

## 4. 苞 F：读窗 5000 + m2t2a 事件驱动

### 4.1 verify-e2e.ps1（对抗方向 5）

- `Read-Sse-Brief` 全部调用点亲数：**恰 3 处**（:1818 / :1888 / :2203），全部已是 `5000`；`3000`/`3500` 残留仅在函数头注释的论据文字里（:1309/:1312/:1313），无第四处遗漏。
- 注释判据自洽：「窗口 ≥ 2 个心跳周期 + 抖动余量」= 2s×2+1s = 5000ms；对 3500 的批评（1.75 个周期）与对 3000（1 个周期）的批评口径一致。
- 同步面：GF4 的 `Ok` 收尾文案与 A3 注释里的「3s」均已改「5s」（diff 亲见两处）；帧契约断言 :1395 的错误文案同步改为新键名。**排除**。
- 附带核对：R-104 行声称的 13 处 `Start-Sleep -Seconds 2` 行号抽查 4 处（:1523/:1615/:2017/:2180）全部命中 `Start-Sleep -Seconds 2` —— 行号锚点可信。

### 4.2 m2t2a-smoke.mjs（对抗方向 4）

- **until 漏分支**：`readSse`（:243）在 `onFrame` 未命中时检查 `cfg.until(frame)`（done/error 任一早停，`stopReason='terminal:done'|'terminal:error'`），到窗/断开归 `'window'`。调用点仅一处（:331，阶段 3a），旧签名数字形态向后兼容（`typeof opts === 'number'`），无其它调用点漏改（grep 亲证）。**无漏分支**。
- **stopReason 归因**：`stopped=true` 时 `matched` 或 `terminal:*`；`stopped=false` 时 `window`。调用方判据从 `!awaited.stopped` 收紧为 `awaited.stopReason !== 'matched'`（:340）—— 语义正确且更严。
- **deriveAwaitCap 三路回落**（!r.ok / 解析失败 / 异常）：每路都有 `console.warn` + `capSource` 具名来源，失败不静默。回落常量 `FALLBACK_AWAIT_MS = 330_000` = 缺省 300s+30s。**掩盖面评估**：若服务端 totalBudget 被配成病态小值（如 PT1S），脚本会照读并得出 31s 上窗 —— 但这恰是「以服务端事实源为准」的设计本意（服务端真会在 1s 收口，脚本早停于 done/error 反而正确）；回落路径只在 /health 不可达时兜底。三路回落不构成配置错误掩盖 → **排除**。但需点名一个**实测不可区分**问题：当前缺省配置下推导值（300s+30s=330s）与回落常量 330s **同值**，本批实测无法证明「真的读到了服务端值」（需强制回落实验）—— 既有复核报告观察②已登记，红队确认成立 → **观察③**。
- **`parseIsoDurationMs` 正则**：`PT` 后各段可选、要求至少一段 >0；`PT5M`→300000 验算正确；`PT0S`/`abc`/`5M`（缺 PT）→ null 回落，分支自洽。
- **尾窗未改造**：阶段 3d 读结局帧的 `deadline = Date.now() + 150_000`（:431）仍是写死窗口（非事件驱动），但其循环退出条件本含「两帧到齐即停」，写死的只是上界。该遗留已在 M2「小批苞 G 登记」N-5 条如实登记（登记不改，入下批 E2E 时序包），且 N-6 条点名了同文件 `timeoutSeconds:120` 硬编码与 ps1 侧「不硬编码 120」纪律相反的口径冲突。两处均为**已登记的既知遗留**，非夹带 → 分别并入观察④（见 5.3）。
- 结论：无新增发现；三处观察均已在仓内登记闭环。

---

## 5. 苞 G：文档与登记

### 5.1 DC-16 与编号纪律

- `DECISION-REGISTER.md` DC 行计数 = **16**（grep 亲证），DC-16 编号唯一（无重复行）；四条纪律正文完整（依据注释/键化/同值不同义禁共用/不返工随包补注）。
- 三处同步口径亲证：根 `README.md`（DC-01~16）、`docs/README.md`（两处 DC-01~16 + 新增数字纪律指针行）、`REGISTER:32` 判定对象行（DC-01~DC-16 均已签定）—— 三者一致，无漏改。
- `check-docs.mjs` 门禁本人实跑：**6/6 断言绿**（含命名纪律禁则全量扫描：919 路径，豁免 3 个 spike 脚本），与既有验证基线一致。

### 5.2 M2 去向表

- R 编号行总数 104，逐号 `uniq -c` 无重复；`R-102` / `R-103` **确为空号**（grep 0 命中），与「编号重编号与同批立项留痕」节的声明自洽（R-100/101 为对话面板包保留号，苞 F/G 让号后移）。
- 新增 5 行（R-100/R-101/R-104/R-105/R-106）列数全部 = 表体 2 列（编号列 + 内容列），与既有行同构，无断列。
- 行号锚点抽查：R-104 的 4 处 `Start-Sleep` 行号、R-106 的 `:143 timeoutSeconds:120`、`:294 轮询 12×500ms`、`:431 deadline 150_000`、`injectExpr :150-153`、`ai.ts :1157 轮次身份守卫` —— **全部实测命中**。
- 措辞自洽抽查：R-99 完成留痕如实区分「已闭环（键结构拆分）」与「未闭环（②③取值面）」；R-41/R-98 行尾注记均标「本行为登记原文，不回改」。
- **一处数字张力（非缺陷，需点名）**：R-105 行与「小批苞 A 观察项登记」节记载全量首跑 `Tests run: 387, Failures: 1`（苞 A 时点口径），而本轮实测已是 **389**（新增 AiToolsListTasksTruncationTest 2 例后）。R-105 行内写明「387」是登记时点的事实留痕，且 R-104 行有「行号口径（苞 F 落盘后）」的同类时点声明先例，属登记惯例内的时点快照，不算错。但 387→389 之间**没有任何行声明「用例数 387 → 389 的增量来源」**（可由本批 +2 用例推出，但未落文字）→ **观察⑤**。

### 5.3 冻结文档处置

- `docs/03-技术方案文档-v2.1.md` 三处旧键名按冻结文档不回改 + 行尾注记，注记内容（240/120 拆分）与实现一致。
- `docs/s4/S4.2-AI-Runtime-合流规格.md` 配置键表直接改写为新键两行（该文档非冻结件，属现行规格，改写合规）。
- **纪律冲突点名（既有登记确认）**：`m2t2a-smoke.mjs:143` 硬编码 `timeoutSeconds:120`（拆键后已不对应任何配置键）与 `verify-e2e.ps1:1395`「不硬编码 120，与 app.ai.hitl.frontend-tool-timeout 解耦」**同仓两种相反纪律并存**。已在 N-6 登记、复核观察④登记，本轮未收口 → **观察④**（同一客体，双登记在案）。

---

## 6. 全局脱敏与纪律扫描（对抗方向 7）

| 扫描项 | 结果 | 判定 |
|---|---|---|
| 密钥泄露 | 命中全为占位符（`<在此填入…>`、`${AI_API_KEY:…}`、`ci-stub-placeholder`、`***`）；`sk-` 正则 4 命中均为 `task-` 子串误报；Bearer 长串 0 | **无泄露** |
| 盘符绝对路径 | 工作树内容仅 `check-docs.mjs:89` 注释「D:/… 与 /c/… 两种形态」（扫描器自身的正则描述，非实际路径）；其余命中均为正则定义文本与 `file:///<REPO_ROOT>` 脱敏形态 | **无违规** |
| Windows 用户名 | `docs/merge/01-分支评价报告.md:3` 含 GitHub URL 中的用户名（该行为既有文件、非本批改动面；且 URL 属公开仓库标识而非本机路径泄露）；其余为 `tinyglobby` 依赖名误报 | **观察⑥**（既有、非本批引入） |
| 内网 IP | 84 处命中全部为 `10.x` 段**演示/种子数据**（测试夹具的 `gateway` 字段、证据文档录制值），无 172.16-31/192.168 段，无真实环境地址特征 | **无违规**（演示数据） |
| 命名纪律（`main`+分隔+`v2` 任意组合/零分隔） | 命中 6 处全部位于 3 个 spike 脱敏脚本的**正则字面量**内（脱敏规则自身要匹配本仓库路径才能替换为 `<MAIN_REPO>`），属 check-docs 断言⑤的既有豁免面（豁免 3 个文件 + 头部豁免注释）；`docs/`、`README`、`scripts/`、代码字符串零命中 | **无违规**（豁免机制在案，门禁 6/6 绿亲证） |

本批改动文件内的增量内容（22 个修改文件 diff 全文 + 1 个新测试文件）经锚点 diff 逐文件审阅：**无新增盘符/用户名/内网 IP/密钥/仓库名夹带**。

---

## 7. 验证基线复核（不采信、亲测）

| 基线项 | 声称值 | 本人实测 | 判定 |
|---|---|---|---|
| 后端 XML 口径 | 389/0/0/0（60 个 TEST-*.xml） | `mvn -B test` BUILD SUCCESS，`Tests run: 389, Failures: 0, Errors: 0, Skipped: 0`；XML 聚合亲算 60 个文件、389/0/0/0 | **一致** |
| E2E 桩模式 29/29 | 29/29 | 未运行（起服务+真实 E2E 超出只读审计授权面：需占端口起后端与前端） | 未复核，如实声明 |
| 前端 typecheck+build 绿 | 绿 | 未运行（build 产物除外条款仅限后端测试；前端构建不在授权面） | 未复核，如实声明 |
| check-docs 6/6 | 6/6 | 本人实跑：6/6 断言绿，EXIT=0 | **一致** |
| /health 运行值 240/120 | confirmTimeoutSeconds=240 / frontendToolTimeoutSeconds=120 | 未起服务实测；改用静态证据链闭合：yml 值（240s/120s 亲读）= 类默认（240/120 亲读）= /health 单测桩值断言（240/120 亲读）三口径一致 | 静态一致，未运行时验证 |

说明：后端全量测试是本人在本机用 Maven 3.9.9（取自另一仓库的工具目录，非公网下载）对审计时点工作树实跑的结果，R-105 登记的 `SseChatEmitterWriteBudgetTest` 时序脆弱用例本轮**未复发**（389 全绿）。

---

## 8. 发现清单汇总

分级统计：**BLOCKER 0 / MAJOR 0 / MINOR 2 / 观察 6**。

### MINOR-1 —— 分页注释宣称的「后端硬上限 100」未实现
- 主锚：`backend/src/main/java/com/example/configmgr/data/controller/DataController.java:51-54`（javadoc 依据段）与 `:69-79`（实现）
- 问题：javadoc 转述业界依据「内网推荐默认 20、后端硬上限 100」，但端点对 `size` 无任何 clamp/校验，`size=100000` 直通 `PageRequest.of`。读者（尤其按注释做安全评审者）易误读为上限已落地。非本批引入（原 50 时代同样无 clamp），但本批把「硬上限 100」写进了依据注释，扩大了误读面。
- 复现：`GET /api/data/{defCode}?size=999999`（无任何拒绝/截断）。
- 建议修法：要么补 `size = Math.min(size, 100)`（或 `@Max` 校验 + 400），要么把 javadoc 措辞改为「上限 100 属推荐、本端点尚未实现，登记见 R-xx」。属一行级修复或一行级措辞收口。

### MINOR-2 —— 挂起双键缺「yml 配置绑定」断言锁（既有登记项，仍未闭环）
- 主锚：`backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesT35DefaultsTest.java:75-86`；`backend/src/main/resources/application.yml:127-128`
- 问题：现有测试只锁类默认值与 mock 桩值；`backend/src/test` 无 application.yml，无任何用例把 yml 绑进 `AiProperties`。构造绕过：只改 yml（`confirm-timeout: 300s`，甚至 > total-budget）→ 全部测试照绿而运行值已变。
- 复现：临时改 yml 值 → 跑 `AiPropertiesT35DefaultsTest` + `AiControllerHealthSseDeliveryTest` → 全绿（本人静态推演，未实际改动仓库文件——只读纪律）。
- 建议修法：补一条 `ApplicationContextRunner` / `@SpringBootTest`（限定 yml 绑定面）断言两键绑定值与 `confirm-timeout < total-budget` 不变量。已在 M2 复核登记节标【待裁决】，红队建议尽快收口（这是「confirm-timeout < total-budget」机器化断言唯一没盖住的进风口）。

### 观察① —— 旧键启动参数静默回落
- 主锚：`application.yml:112-128`（旧键已删）；历史用法见 `docs/evidence/S42-P2-挂起续跑会话门验证.md:187`
- 问题：外部 `--app.ai.hitl.timeout=20s` 现被 Spring Boot 静默忽略（无 WARN），按旧证据文档重跑超时场景会得到 240s 而非 20s。仓内活代码无此用法。
- 建议：知悉即可；若求稳可在 README/运维文档加一句「旧键已拆」提示。

### 观察② —— /health 新字段前端暂无消费方
- 主锚：`frontend/src/types/ai.ts:103`
- 问题：`frontendToolTimeoutSeconds` 仅类型对齐，`frontend/src` 与 `scripts/` 无实际读取点（脚本读的是 `resilience.totalBudget`）。观测面对齐意图成立，非缺陷。
- 建议：知悉；将来前端做超时提示时再接。

### 观察③ —— deriveAwaitCap 事实源与回落值同值，实测不可区分
- 主锚：`scripts/m2t2a-smoke.mjs:196,224`
- 问题：缺省配置下推导值（300s+30s）与 `FALLBACK_AWAIT_MS` 同为 330s，本批实测无法证明读到了服务端值（日志里 capSource 可区分，但两种来源的 capMs 相同，无法用行为验证）。
- 建议：后续任意一次 totalBudget 改值的批次顺带验证；或回落常量改用可区分的注释口径。已登记（复核观察②）。

### 观察④ —— 同仓两种相反的「120」纪律并存（N-6 面）
- 主锚：`scripts/m2t2a-smoke.mjs:143`（硬编码 `timeoutSeconds:120`）vs `scripts/verify-e2e.ps1:1395`（「不硬编码 120，与 app.ai.hitl.frontend-tool-timeout 解耦」）
- 问题：拆键后 mjs 里的 120 已不对应任何配置键，与 ps1 的纪律相反。已在 N-6 + 复核观察④双登记，收口方向已写明（帧内值由服务端键下发）。
- 建议：随下批 E2E 时序包收口，勿再拖（这是「同值不同义」禁则的现存违例活标本）。

### 观察⑤ —— 用例数 387→389 的增量来源未落文字
- 主锚：`docs/M2-排期计划.md` R-105 行（「Tests run: 387」为苞 A 时点）与「小批苞 A 观察项登记」节
- 问题：本轮实测 389（含本批新增 2 例），登记文本仍停在 387，无一行声明增量来源。属时点快照惯例内的口径漂移，非错记。
- 建议：下次触碰该节时顺手补一句「+2 例 = AiToolsListTasksTruncationTest」。

### 观察⑥ —— 历史文档含 GitHub 用户名 URL
- 主锚：`docs/merge/01-分支评价报告.md:3`
- 问题：文件内容含 `https://github.com/<用户名>/ai-example` 形态 URL。属既有文件、公开仓库标识、非本批改动面；但按「禁用户名入仓」的严格口径值得知悉。
- 建议：维持现状（改动属历史文档回写，代价大于收益）；如未来收紧口径，纳入同批豁免清单即可。

---

## 9. 对抗路径与排除记录（无发现面的交代）

1. **旧键活引用**（对抗方向 1）：26 处命中逐类判定（见 §2.1），活代码零引用、冻结文档全部行尾注记覆盖、`hitlTimeout` 诊断键无残留消费方 → 排除。
2. **语义串读**（方向 2）：取值路径亲读 + generative_form 归属链（`channelOf`→`KIND_FRONTEND`→`awaitFrontendResult`）亲证 → 排除。
3. **截断边界**（方向 3）：`<=` 边界 + 总数同源插值 + 双向用例锁 → 排除。
4. **事件驱动漏分支**（方向 4）：`readSse` 全调用点枚举（仅 1 处）、旧签名兼容、三路回落全部具名 warn → 排除；尾窗 150s 与硬编码 120 属已登记遗留。
5. **读窗第四处**（方向 5）：`Read-Sse-Brief` 调用点全数（3 处）亲数，残留 3000/3500 仅注释论据 → 排除。
6. **文档编号**（方向 6）：DC-16 唯一、三处 DC-01~16 同步、R-102/103 空号与留痕声明自洽、新增行列数同构、抽查行号锚点全命中 → 排除。
7. **脱敏**（方向 7）：五类扫描逐项判定（见 §6 表），命中均为占位/演示数据/既有豁免面 → 排除。
8. **测试锁绕过**（方向 8）：苞 A/苞 B 每个测试类做了错误实现构造推演，除 MINOR-2 的 yml 绑定缺口外全部无法绕过 → 排除。
9. **提交侧夹带**：三笔提交 diff 逐行审阅，改动面与提交说明逐条对得上，无超范围改动（a56613d 未动下游三处、6d5162b 未动 JobController、51ee49c 未触 Hikari 段外配置键）→ 排除。

---

## 10. 总体结论

**带条件 PASS**（0×BLOCKER / 0×MAJOR / 2×MINOR / 6×观察）。

- 代码面（苞 A/B/F）：双键拆分取值路径干净、无旧键活引用、截断边界正确、脚本改造无漏分支；后端全量 389/0/0/0 与 check-docs 6/6 本人复测通过。
- 两个 MINOR 均为「锁/注释的完备性缺口」而非行为缺陷：MINOR-1 是注释宣称与实现的一步之差（一行收口），MINOR-2 是已登记待裁决的测试缺口（红队确认其客观性，建议尽快施工）。
- 条件：**MINOR-2 建议在下一批收口**（yml 绑定断言是「confirm-timeout < total-budget」不变量唯一没盖住的进风口）；MINOR-1 随任意一批顺手一行收口。观察④（两种「120」纪律并存）已两处登记，提醒勿使其在 E2E 时序包之外再度顺延。
- 未复核项如实声明：E2E 桩模式 29/29、前端 typecheck+build、/health 运行时值（需起服务，超出本次只读授权面）；后三项中 /health 已用静态证据链（yml = 类默认 = 单测桩值）三方闭合。
