# 数字配置 · 四册总索引与行业审查最终裁决（numspec-2026-10-31）

> **本文性质**：只读索引 + 裁决记录。本次唯一新增文件 = 本文件；未改四册、未改代码/测试/配置，未做 git 写操作，未运行测试/服务/浏览器。
> **裁决依据**：commit **`86f2cab2`**（`fix(ai): 修复导出表单链路并同步批准数字配置`，2026-10-31 10:10:55 +0800；62 文件、+1527/−317）。
> **快照声明（重要）**：四册（`backend-ai` / `backend-business` / `frontend` / `scripts-deploy`）是 **2026-10-31 02:07–02:11** 的磁盘快照，**未回写**本次裁决。因此下文 10 项在四册中显示的仍是**旧值**；判断"现在跑的是什么"请以本文件 §2 与源码为准，判断"当时登记的是什么"看四册。

---

## 0. 一页结论

| 项 | 结果 |
|---|---|
| 覆盖 | 四份行业审查逐行覆盖四册 **881 行**（289 + 106 + 212 + 274），无遗漏行、无「同上」省略 |
| 批准并实施 | **10 项**（§2 总表），全部落在 `86f2cab2`；无"批准但未落地"项 |
| 未批准 | **其余全部保持待裁**（§3）；四册中对应行的「审视建议」仍是建议，**不是现状** |
| 与审查建议的差异 | 4 项裁决值与审查建议不同（list_* 50 vs 建议 40；输入 2000 vs 建议保留 8192；首帧 40s vs 建议 35s/25s；S-12 仅改一处）——差异在 §2 逐项标注 |
| 证据 | 静态值核对 + 测试证据（新增 2 个静态断言 spec、更新十余个既有测试，清单见 §4.2）；导出对话链已完成真实 Chrome 复测，Playwright 视口未另取 `page.viewportSize()`（§4.3） |

---

## 1. 索引

### 1.1 四册 × 行业审查（覆盖与处置分布）

| 四册 | 行数 / 自报独立 | 行业审查件 | 审查覆盖 | 处置分布（审查机械统计） |
|---|---|---|---|---|
| [backend-ai.md](./backend-ai.md) | **289 / 288** | [industry-backend-ai.md](./industry-backend-ai.md) | 289/289 | 改值 **7** / 配置化 **14** / 需压测 **11** / 保留 **257** |
| [backend-business.md](./backend-business.md) | **106 / 106** | [industry-backend-business.md](./industry-backend-business.md) | 106/106 | 保留 **72** / 改值 **21** / 配置化 **8** / 需压测 **2** / 需裁决 **3**；P0 5 / P1 22 / P2 79 |
| [frontend.md](./frontend.md) | **212 / 212** | [industry-frontend.md](./industry-frontend.md) | 212/212 | 保留 **180** / 新值 **14** / 配置化 **10** / 需实测 **8**（34 个动作项） |
| [scripts-deploy.md](./scripts-deploy.md) | **274 / 257** | [industry-scripts-deploy.md](./industry-scripts-deploy.md) | 274/274 | 保留 **162** / 派生 **58** / 新值 **31** / 需实测 **23** |

> 合计：**881 行**（独立项 863，口径见 [README.md](./README.md) §2）。四份行业审查的"建议"列是**提案**，不是已生效值；本文件只把其中 10 项转为裁决。

### 1.2 文档链接

| 文档 | 作用 |
|---|---|
| [README.md](./README.md) | 前序跨册复核索引（881 行 / 863 独立 / 860 行级唯一；同为 02:09 快照） |
| [backend-ai.md](./backend-ai.md) · [industry-backend-ai.md](./industry-backend-ai.md) | AI 面现值 / 业界对标（S1–S16 来源） |
| [backend-business.md](./backend-business.md) · [industry-backend-business.md](./industry-backend-business.md) | 非 AI 后端现值 / 业界对标（S1–S30 官方一手） |
| [frontend.md](./frontend.md) · [industry-frontend.md](./industry-frontend.md) | 前端现值 / 业界对照（Playwright 源码级证据 S12/S13） |
| [scripts-deploy.md](./scripts-deploy.md) · [industry-scripts-deploy.md](./industry-scripts-deploy.md) | 脚本/CI 现值 / 业界基准（274 行全含 URL） |

---

## 2. 最终裁决：已批准并实施（10 项）

### 2.1 总表

| # | 裁决项 | 四册行（快照旧值） | 终值（旧 → 新） | 实施点 | 主要关联点 |
|---|---|---|---|---|---|
| 1 | `list_config_defs` 单轮上限 | N-AI-205（20） | **20 → 50** | `backend/.../ai/tools/AiTools.java:130`（常量）、`:156`（`@Tool` 描述） | tools.yaml `.18`、后缀自动、测试 SPEC_MAX_ROWS |
| 2 | `list_tasks` 单轮上限 | N-AI-206（20） | **20 → 50** | `AiTools.java:244`、`:274` | 同上（N-AI-260 联动） |
| 3 | 对话输入单条上限 | N-AI-062 / N-AI-078 / N-FE-061（8192） | **8192 → 2000** | `application.yml:395`、`AiProperties.java:1339`、`AiPanel.vue:1816`（`maxlength` 绑定 `:1247` 保留） | 前后端同尺、maxlength 保留 |
| 4 | 前端首帧看门狗 | N-FE-009（30000ms） | **30s → 40s** | `frontend/src/api/sse.ts:87` | 后端首包仍 30s（`application.yml:233`） |
| 5 | 作业帧空闲看门狗 | N-FE-021（90000ms） | **90s → 120s** | `frontend/src/utils/job-watch.ts:44` | 后端判死窗仍 90s（30s×3） |
| 6 | 发布跟踪墙钟 | N-FE-024（300000ms） | **300s → 360s** | `frontend/src/stores/task.ts:100` | 段预算仍 300s（会重置） |
| 7 | 后端异步作业池上限 | N-BE-058（16） | **16 → 8** | `backend/.../config/AsyncConfig.java:71` | Hikari 10、`REQUIRES_NEW` 双连接 |
| 8 | 桩 `stall-frames` 缺省 | N-OPS-061（50000） | **50s → 95s** | `scripts/ci/stub-routes.json:122`、`scripts/ci/stub-upstream.mjs:311` | 产品 `inter-event-timeout` 90s |
| 9 | S-12「无新帧」观察窗 | N-OPS-116（3000×2） | **3000 → 5000**（仅 S-12） | `scripts/verify-e2e.ps1:3411` | 2s 心跳 ×2 + 1s；另一处 `:3681` **未改** |
| 10 | Playwright 实际视口 | N-FE-212（顶层写了 1440×900 但被设备预设覆盖为 1280×720） | **实际生效 1440×900**（11 份） | `frontend/playwright*.config.ts`（9）+ `frontend/e2e/playwright.{gf2,nfezui}.config.ts`（2） | project.use 覆盖顺序、Chrome 复测、测试证据 |

### 2.2 工具有界披露：`list_config_defs` / `list_tasks` = 50

- **终值**：两个常量均为 **50**；`@Tool` 描述同步为「最多返回前 50 个/条」；截断后缀由常量拼接（`:141-142`、`:269`），随常量自动变。
- **`tools.yaml` 升版 `.17 → .18`**（`backend/src/main/resources/ai/tools.yaml:60`）：注释写明「上限 20 → 50、后缀自动、仍 < `result-budget.model.max-rows` 200」；该文件对这两个工具只覆盖 `title`，**描述真值在 `@Tool` 注解**，故必须改注解 + 升 version（破 prompt 缓存）。
- **测试联动**：`AiToolsListTasksTruncationTest.SPEC_MAX_ROWS = 50`；`ListTasksHistoryProjectionTest` 断言「共 51 条，仅显示前 50 条」；`ToolDefinitionOverridesTest` / `KnownInfoToolDescriptionsTest` 随描述改。
- **与审查建议的差异**：审查给的安全方案是 **40**（方案 B：最坏值下三道闸全绿），并明确不建议直接 100。**裁决取 50**。按审查 §3.2 的同一算式外推：50 行最坏 defs ≈10,750 字符、tasks ≈16,250 字符（< `default-max-chars 20000`）；tasks 帧 ≈48.8KB（< 64KiB）；单条 ≈9.6K token。**唯一收紧的是「同轮 5 对工具结果」**：5×9,559 ≈ **47.8K < 48,000**，余量仅 ≈200 token（见 §6 风险 1）。
- 未动（审查也建议不动）：`result-budget.model.max-rows 200`、`default-max-chars 20000`、`memory.token-budget 48000`。

### 2.3 对话输入上限 = 2000（同尺 + maxlength 保留）

- **终值**：后端 `app.ai.input.max-chars: 2000`、`Input.DEFAULT_MAX_CHARS = 2000`、前端 `MESSAGE_MAX_CHARS = 2000`，三处同值。
- **前后端输入同尺**：判据是 **UTF-16 码元**（`String#length` / `<textarea maxlength>` 同一把尺），中文按 1 字算；2000 汉字 ≈ 6000 字节仍放行，超限仍是 **400 `MESSAGE_TOO_LONG` 拒绝、不截断**。
- **maxlength 保留**：`AiPanel.vue:1247` 仍是 `:maxlength="MESSAGE_MAX_CHARS"`（只把常量从 8192 改为 2000，**没有**移除属性或改由 JS 拦截）；底栏四档提示文案同步为「已达 2000 字上限」。
- **与审查建议的差异**：审查 §4.3 的明确建议是**保留 8192**（理由：远低于任何模型/协议上限，是「误粘贴护栏」；降到 4096 会切掉「贴一段日志 + 一句话」的排障场景）。**裁决改为 2000** —— 属裁决覆盖审查建议，不是"审查建议已实施"。代价按审查口径：长日志场景需走附件通道（`MAX_ATTACHMENT_BYTES 5MiB` / 总 20MiB）。
- **测试**：`frontend/tests/ai-panel-composer-limit.spec.ts` 断言 `maxlength=2000`、到顶「已达 2000 字上限」；后端 `AiControllerChatGateTest` 边界随之调整。
- **副作用（观察，未裁决）**：四档提示里的「余量 ≤500 转琥珀」阈值**没有随值缩放**——8192 时 500 是 6%，2000 时 500 是 25%（1500 字就转琥珀）。是否按比例改（≈120 或百分比）属交互口径，未在本次批准面内（同 §3.1 #3 一族）。

### 2.4 三处前端看门狗错层：40s / 120s / 360s

| 项 | 现值（后端对应） | 裁决 | 错层含义 | 与审查建议 |
|---|---|---|---|---|
| 首帧 | 40s（后端首包 30s） | **前端更晚 10s** | 先让后端 30s 判死并发出错误帧，前端再兜底 | 审查建议 35s（收后端错误帧）或 25s（前端自带文案）；**裁决取 40s**，方向与 35s 相同、余量更大 |
| 作业帧空闲 | 120s（后端判死窗 90s） | **前端更晚 30s** | 优先等 `FAILED` 终态帧，而不是在后端判死同刻降级轮询 | 审查给 75s 或 120s 二选一；**取 120s**（与建议一致） |
| 发布墙钟 | 360s（段预算 300s） | **前端更晚 60s** | 段预算会因挂起/恢复重置，墙钟不会；1.2× 覆盖一次挂起恢复 | 审查建议 360s（与建议一致） |

- **联动**：首帧 40s 与帧间 45s 只差 5s（原为 30/45）；若后续把帧间也调小，两者需一起复核。
- **未批准的同族项**：审查建议「判定用单调时钟」（N-FE-024 行内建议）**未实施**——`task.ts` 仍用 `setTimeout`/`Date.now` 口径；后台标签被 Chrome 节流到约 1 次/分钟的风险仍在（见 §3.1）。

### 2.5 后端异步作业池 = 8（Hikari 关系）

- **终值**：`AsyncConfig.setMaxPoolSize(8)`；`corePoolSize 4`、`queueCapacity 200`、`awaitTerminationSeconds 30` **未变**。
- **Hikari 关系**：Hikari 上限仍是框架缺省 **10**（`application.yml` 不显式写键）。8 ≤ 10 ⇒「作业线程数不再多于连接数」；但注释已如实改写：分片边界的 `REQUIRES_NEW` 会让**单个作业峰值占 2 条连接**（外层 + 新事务），故 8 条作业线程仍可能在 `connectionTimeout` 内排队。这是"容量链修复"，不是"零排队保证"。
- **未批准的同族项（保持待裁）**：`N-BE-010` 默认 profile `connectionTimeout 30s → 5s`、`N-BE-059` `queueCapacity 200 → 100`（或 50）。二者与 8 同属审查的 P0 容量链，但**本批未改**，现值仍是 30s / 200。

### 2.6 Playwright 实际视口 = 1440×900（11 份；覆盖顺序 + Chrome 复测）

- **根因（配置覆盖顺序）**：Playwright 的 `use` 是**合并**关系且 `project.use` 合并在后；`devices['Desktop Chrome']` 自带 `viewport: 1280×720`，会覆盖顶层 `use.viewport`。因此"顶层写了 1440×900"不等于生效。
- **修法**：在 **`...devices[...]` 展开之后**显式写 `viewport: { width: 1440, height: 900 }`，让 project 级同名键取到 1440×900。
- **覆盖面从 9 扩到 11**：审查/四册只覆盖 9 份（`playwright.config.ts` + 8 份域配置）；实施时补上 `frontend/e2e/playwright.gf2.config.ts`、`frontend/e2e/playwright.nfezui.config.ts`，共 **11 份**。新增测试把清单也锁死（增删域配置会红）。
- **配置覆盖顺序的另外两处**（同一族语义，供排查时对照）：E2E 的 `process.env.X ??= default` 是**显式传参优先**；桩的 `stall-frames` 取值为 `stub-routes.json` 的 `default`，缺失时回落 `stub-upstream.mjs` 的内建同值（两处已同改为 95000）。
- **Chrome 复测与测试证据**：
  - 已有（静态）：`frontend/tests/playwright-viewport-config.spec.ts` —— 断言 11 份配置的 `project.use` 在 devices 展开**之后**写 1440×900，并断言清单恰好 11 份。
  - **未做**（本次复核不运行浏览器）：真实 Chrome 的 `page.viewportSize()` 取证。审查 §10.2 也把"跑一次确认"列为收尾动作；建议在 CI 或本机跑一棒并记录 `viewportSize=1440×900`，作为该条闭环的运行时证据。

### 2.7 桩与 E2E 三值：95000 / 5000 / 20s

| 项 | 终值 | 不等式（为什么会是这个数） | 实施点 |
|---|---|---|---|
| 桩 `stall-frames` 缺省 | **95000ms** | 必须 **> 产品 `inter-event-timeout` 90s**，否则缺省值永远触发不了帧间看门狗（"假绿"陷阱） | `stub-routes.json:122` + `stub-upstream.mjs:311`（两处同值） |
| S-12「无新帧」观察窗 | **5000ms** | ≥ 2 个 2s 心跳周期 + 1s 抖动余量；3000 只覆盖 1.5 个心跳 | `verify-e2e.ps1:3411` |
| m2t2a 取消收敛 | **40×500ms = 20s** | 覆盖取消重试节奏 2s/6s/**14s** 的末次 + 余量；原 6s 只到第 2 次 | `m2t2a-smoke.mjs:147-148` |

- **S-12 是部分落地**：`N-OPS-116` 覆盖两处（快照 `:3410` S-12、`:3680` H-04 逐卡确认；现为 `:3411` / `:3681`）。本批只改 **S-12**；**H-04（`:3681`）仍是 3000**，属未批准，保持待裁（§3.1）。
- 测试证据：`frontend/tests/script-budgets-contract.spec.ts` 锁三条不等式与两处同值（并读 `application.yml` 的 90s）。

---

## 3. 未批准：保持待裁（**不得写成已改**）

### 3.1 与批准项同族、最容易被误读为"已改"的未批准项

| # | 项 | 四册行 | 现值 | 审查建议（**未实施**） | 备注 |
|---|---|---|---|---|---|
| 1 | `max-tokens 4096` | N-AI-002 | 4096 | 8192（与 `MAX_FORM_CHARS 8000` 结构性冲突） | 本批未改 |
| 2 | `park.max-dispatch-retries` | N-AI-037 | 15（≈360s） | 18（450s，消除与 300+60 的零余量） | 本批未改 |
| 3 | 输入 4 档提示改 2 档 | N-FE-061 行内建议 | 4 档 | ≤500 才显示剩余 | 随 2000 落地后仍 4 档（文案已改） |
| 4 | 发布判定改单调时钟 | N-FE-024 行内建议 | `setTimeout` + `Date.now` | 单调时钟 / 读服务端 deadline | 本批未改 |
| 5 | `connectionTimeout` | N-BE-010 | 30000ms（缺省） | 5000ms | 与 pool 8 同属容量链，未改 |
| 6 | 作业队列容量 | N-BE-059 | 200 | 100（或 50） | 未改 |
| 7 | H-04 逐卡确认观察窗 | N-OPS-116（第二处） | 3000ms（`:3681`） | 5000ms | S-12 已改，H-04 未改 |
| 8 | 探针首帧判据 | N-OPS-121 | 2000ms | 1000ms（= 1/2 心跳） | 未改 |
| 9 | Playwright `retries` 与两处 180s | N-FE-181 / 183 / 189 | 0 / 180s / 180s | CI retries=1；180s 补实测或回落 | 未改 |
| 10 | 页面就绪 8s / 定义就绪 10s / 10s | N-FE-017 / 173 / 174 | 8s / 10s / 10s | 一次裁决统一或写清差异 | 未改 |

> 上表的"审查建议"列只是**提案文本**。四册中这些行的「审视建议」列同样只提不改；**四册正文不是执行状态**。

### 3.2 四册未批准总量（按审查处置分布）

| 分册 | 已批准 | 其余（待裁） | 待裁去哪看 |
|---|---|---|---|
| backend-ai | 2（N-AI-205/206；N-AI-260 随描述联动） | 改值 4（002/037/186/283）+ 配置化 14 + 需压测 11 + 保留 257 | industry-backend-ai §7 统计、§8 优先顺序 |
| backend-business | 1（N-BE-058） | 改值 20 + 配置化 8 + 需压测 2 + 需裁决 3 + 保留 72 | industry-backend-business §9（P0 5 / P1 22 / P2 79） |
| frontend | 4（N-FE-009/021/024/212） | 新值 10 + 配置化 10 + 需实测 8 + 保留 180 | industry-frontend §9.2（34 个动作项） |
| scripts-deploy | 3（N-OPS-061/116-S-12/150） | 新值 28 + 派生 58 + 需实测 23 + 保留 162 | industry-scripts-deploy §11（优先级）、§12（锁步联动） |

> 数量口径：AI 的「改值 7」= 002/037/186/205/206/260/283；本批批准 205/206、260 随描述联动，故记「已批准 2、待裁改值 4」。FE 的「新值 14」含 212（视口），故记 4。

---

## 4. 证据与验证

### 4.1 静态核对（源码现值，逐项回读）

| 项 | 现值 | 位置 |
|---|---|---|
| list 上限 ×2 | 50 / 50 | `AiTools.java:130` / `:244`；描述 `:156` / `:274` |
| tools.yaml 版本 | `2026-10-27.18` | `ai/tools.yaml:60` |
| 输入上限 ×3 | 2000 | `application.yml:395`、`AiProperties.java:1339`、`AiPanel.vue:1816`；`maxlength` 绑定 `:1247` |
| 首帧 | 40000ms | `frontend/src/api/sse.ts:87` |
| 作业帧空闲 | 120000ms | `frontend/src/utils/job-watch.ts:44` |
| 发布墙钟 | 360000ms | `frontend/src/stores/task.ts:100` |
| 异步池 | core 4 / max 8 / queue 200 | `AsyncConfig.java:70-72` |
| 桩 stall-frames | 95000 / 95000 | `stub-routes.json:122`、`stub-upstream.mjs:311` |
| S-12 窗口 | 5000 | `verify-e2e.ps1:3411`（H-04 `:3681` 仍 3000） |
| m2t2a 收敛 | 40 × 500ms | `m2t2a-smoke.mjs:147-148` |
| Playwright 视口 | 1440×900（11 份，devices 之后） | 9 份 `frontend/playwright*.config.ts` + 2 份 `frontend/e2e/playwright*.config.ts` |

### 4.2 测试证据（随 `86f2cab2` 落地）

| 测试 | 锁什么 |
|---|---|
| `frontend/tests/playwright-viewport-config.spec.ts`（新） | 11 份配置清单 + `...devices[...]` 之后显式 1440×900 |
| `frontend/tests/script-budgets-contract.spec.ts`（新） | stall 95000 > inter-event 90s（两处同值）、S-12 5000、m2t2a = 20000 > 14000 |
| `frontend/tests/ai-panel-composer-limit.spec.ts`（改） | `maxlength=2000`、到顶提示「已达 2000 字上限」、放行边界 |
| `frontend/tests/b3-sse-stage-timeout.spec.ts`（改） | `DEFAULT_FIRST_FRAME_TIMEOUT_MS = 40_000` |
| `frontend/tests/job-watch-channel.spec.ts`（改） | `FRAME_IDLE_WATCHDOG_MS = 120_000` |
| `frontend/tests/watch-publish-outcome.spec.ts`（改） | `PUBLISH_WATCH_WALL_CLOCK_MS = 360_000` |
| `frontend/tests/limits-matrix.spec.ts` / `frontend-tool-outcome.spec.ts`（改） | 上限矩阵 / 工具结果形态随动 |
| `backend/.../AiToolsListTasksTruncationTest`（改） | `SPEC_MAX_ROWS = 50` |
| `backend/.../ListTasksHistoryProjectionTest`（改） | 归档投影「共 51 条，仅显示前 50 条」 |
| `backend/.../ToolDefinitionOverridesTest` / `KnownInfoToolDescriptionsTest`（改） | `@Tool` 描述与 tools.yaml 覆盖 |

### 4.3 未做（如实登记）

1. **未运行任何测试**（本文件是静态索引/裁决记录）：上表测试"存在且断言了对的值"，但本次复核未执行。
2. **未做真实 Chrome 实跑**：viewport 的 1440×900 目前只有**静态证据**（合并顺序 + 配置断言），缺 `page.viewportSize()` 的运行时取证（§2.6）。
3. **未回写四册**：四册仍是旧值（§5）。
4. `.env.local`、运行时 `/api/ai/health` 未读。

---

## 5. 快照与回写（四册状态）

- 四册 + README 是 **02:07–02:11** 的快照；`86f2cab2` 于 **10:10** 实施。二者之间**没有**回写动作。
- 因此在这 10 项上，四册显示的是旧值（20 / 8192 / 30s / 90s / 300s / 16 / 50s / 3000 / 1280×720 的实际生效值），**不是现在的运行值**。
- 若后续决定回写四册，按 §2.1 的「四册行」逐行改，并同步该行「依据」「审视建议」两列（例：N-AI-205 的"业界分页默认 20"要改成"裁决 50"；N-FE-212 的"实际生效 1280×720"要改成"已修，静态锁 1440×900，待 Chrome 取证"）。
- **本次不改**：按任务边界，只新增本文件。

---

## 6. 残余风险（数字层面，供回归/压测；不是待补文档）

1. **list_* = 50 的 5 对最坏 token 余量**：按审查 §3.2 行宽算式外推，tasks 单条最坏 ≈9,559 token，同轮 5 对 ≈47.8K，距 `token-budget 48000` 仅 ≈200。建议把"5 对最坏同时在场"纳入一次回归（或确认 L1 清除会在第 5 对前介入）。
2. **首帧 40s vs 帧间 45s**：错层只剩 5s；若未来把帧间调到 40s 以下，两条判据会重新相等。
3. **池 8 vs `REQUIRES_NEW` 双连接**：8 条作业线程峰值可占 16 条连接需求，`connectionTimeout` 仍 30s（N-BE-010 未批准），高峰排队时间不变。
4. **发布墙钟 360s + 后台节流**：Chrome 后台标签定时器被节流时，墙钟可能显著晚触发；单调时钟未改（§3.1 #4）。
5. **S-12（5000）与 H-04（3000）并存**：同一"无新帧"判据两种窗口，H-04 未批准（§3.1 #7）。
6. **Playwright 视口的运行时证据缺口**：静态锁已生效（测试会红），但真实渲染值未经本批实跑确认（§4.3）。
7. **输入提示阈值未随上限缩放**：「剩余 ≤500 转琥珀」原为 8192 的 6%，在 2000 下变成 25%（1500 字即琥珀）；属交互口径，未在批准面内（§2.3 末条、§3.1 #3）。

---

## 7. 复核声明

- 本文件只新增本文件本身；未改四册、README、代码、测试、配置，未执行 git 写操作，未运行测试/服务/浏览器。
- 文中的"已实施"一律指 **`86f2cab2` 中的源码现值**（§4.1 逐项回读）；"未批准/待裁"一律指**代码未按该建议改动**。
- 与四份行业审查的差异已在 §2 逐项标注；如后续要"全按审查建议执行"，请以各 industry 文件的 §7/§9/§11 优先级表为输入，不要从本文件的 10 项外推。
