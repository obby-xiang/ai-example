> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③a 施工报告，署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改；入库前经提交官订正 §11 统计与 §5 CDP 端口两处）

# 施工报告 · 对话时序包 批 3 ③a（工具结局不得谎报 · 三态收口）

| 项 | 值 |
|---|---|
| 施工依据 | 《批3③a-施工卡.md》（定稿，唯一作业依据） |
| 施工执行 | **Kimi K2.8 Preview**（本棒；CU 直发会话 `session_ec704eaa-0256-4a6d-8a78-49867b5d8742`，wire `modelAlias=kimi-code/kimi-for-coding` 实证；机械活下沉子智能体 `agent-497` 显式钉 `DeepSeek/deepseek-flash`）；**零 git 写**（提交官统一提交）；未读任何 `.env` |
| 授权来源 | **K3 指挥官批 3 ③a 施工卡**（`决策卡-批3③a.md`，K3 代签，2026-10-11/12） |
| 仓库 / 基线 | `<MAIN_REPO>`，HEAD `3edaeb9`，起手工作树干净（实测），收手仅含本批改动（`git status` 见 §10） |
| 工具链（实测） | JDK 21.0.12；node v24.21.0；yarn 1.22.22 |
| 报告日期 | 2026-10-12（施工跨 2026-10-11 晚） |
| 证据目录 | `<SCRATCH>/chat-timeline-pkg/批3a-evidence\`（RED/GREEN 日志、typecheck/build/E2E/CDP 日志、CDP 截图） |

**总结论**：A1~A14 + X1 + D1~D12 终裁全部落地。`yarn typecheck` 0 错；`yarn test` **110/110（11 个 spec 文件）**；`yarn build` 绿；E2E 桩模式 **PASS=28 FAIL=0**（Q8 两项亦 PASS）；CDP 冒烟 **15/15 PASS**（A2 五项 + A6 三项 + R-84 五项 + 前置两项）。T1~T9 全部有 RED→GREEN 双向留证。

---

## §1 锚点对比表（S0 复取，施工棒亲读；基线 = 本卡 §9）

| 锚点 | 卡上结论 | S0 复取 | 处置 |
|---|---|---|---|
| `types/tools.ts:115-120` `FrontendToolExecution` | PASS | PASS（二元、无结局载体） | A1 已改 |
| `frontend-tools.ts:411-426` `timeoutOutcome`（`:425`） | PASS | PASS | A2 已改（outcome:'unknown'，文案一字未动） |
| `frontend-tools.ts:875-896` `runPageHandler`（`:885`/`:887`） | PASS | PASS | A3 已改 |
| `frontend-tools.ts:807-850` `runRegisteredTool`（`:842`/`:843-849`/执行器签名 `:69`） | PASS | PASS | A4 已改 |
| `frontend-tools.ts:981-994` `defaultExecutor`（`:989`/`:990`） | PASS | PASS | A5 已改 |
| `frontend-tools.ts:739-782` 动作表四段（`:753`/`:761`/`:768`/`:774`/`:776-781`） | PASS | PASS | `:767-772` 改 unknown+degraded（D4(a)）；`:774` 未改（D4(b)）；其余不动 |
| `frontend-tools.ts:315-322` `executeFrontendTool` 两分支 | PASS | PASS | A7：settled 支引用透传、timeout 支 A2 同改后携带 outcome；函数体无需再动（RED→GREEN 由 T4/T5 锁） |
| `stores/ai.ts:1782-1819`（`:1804`/`:1815`/`:1818`） | PASS | PASS | A8 已改（`:1804` 改读 `execution.outcome`） |
| `utils/parts.ts:351-375` `localTerminal`（`:358-364`） | PASS | PASS | A9 已改（纳入 `'unknown'`，注释补「含 unknown」） |
| `stores/ai.ts:374-377` `isFrameDecidedPendingStatus` | PASS | PASS | A10/D6(A) 已改（纳入 `unknown`+`failed`） |
| `ExportWizardView.vue:545-554` `goStep`（5 调用方 `:14`/`:599`/`:1128`/`:1146`/`:1225`） | PASS 5/5 | PASS 5/5 | A11/D7 已全部改（含两处残余入口取结局） |
| `ExportWizardView.vue:579-592` 空 catch（`:589-591`） | PASS | PASS | A12/D8(A) 已改 |
| `ImportWizardView.vue:761-765` 空 catch | PASS | PASS | D9 已改（D8(A) 同法） |
| `AiPanel.vue:537-561` `toolTagType`（`:558-559` default 兜 pending） | PASS | PASS（「九态全显式」确不成立，D10 事实订正属实） | A13 已改（pending 显式 case + 去 default） |
| `utils/parts.ts:105-124` `pendingStatusOf`（`:120-122` default） | PASS | PASS | X1：deriveProjections 门禁 + dev 断言；default 保留为类型穷尽兜底并补注释 |
| `stores/ai.ts:1704-1705` `applyPendingTerminal` | PASS | PASS | D5② 已改（显式排除 unknown/failed） |
| `stores/ai.ts:246` `expireResidueToolParts` | PASS | PASS | D5③ 已改（与 `:233` 对齐，unknown 不翻 expired） |
| `stores/ai.ts:1008` / `:631-663`（`:644`）/ `:1573`/`:1620`/`:1590` | PASS | PASS | **未改**（E17/E20，登记移交 ③b，见 §7） |
| `types/ai.ts:66-78`（payload 无 status/ok）/ `:165`（九态）/ `:324`（8 态） | PASS | PASS | 未改（E15 登记） |

**S0 清点结果**（与本卡 §9 注记一致）：
- 消费点 `execution.ok/availability`：全仓 **2 处**（`stores/ai.ts:1804`、`:1815`），已全改。
- 生产点 `grep -c "ok:"` = **26 处字面量**（范围 `:119`~`:891`）；其中 `:119/125/132/138/141` 为 `validateArgs` 判别联合（非 `FrontendToolExecution`）⇒ 工厂收口实际目标面 = **21 处**，S1 typecheck 实测恰好报 **21 个 TS2741**，逐点迁移完毕（迁移表见 §2）。
- `registerPageHandler(` 注册点 **11/11** 命中（导出 5 / 导入 6）；`frontend/tests` 字符串驱动用例命中（`tools-and-deadline.spec.ts` 2 处、`parts-residue-cleanup.spec.ts` 4 处）——T9 回归门据此。

## §2 施工步骤与 21 处生产点迁移表

S1：`types/tools.ts` 增 `FrontendToolOutcome`（三态，注释写明与 availability 正交、failed/unknown 判据差别，照 A1）；`frontend-tools.ts` 增 `makeExecution` 工厂（`ok === (outcome === 'ok')` 单点推导，D1）。S1 后 `yarn typecheck` 精确报 21 个 `TS2741`（留证 `S1-typecheck.log`），A2~A8 成为编译器驱动清单——与卡 S1 门一致。

21 处迁移（基线行号 → 结局取值）：

| # | 基线锚点 | 位置 | outcome | 备注 |
|---|---|---|---|---|
| 1 | `:425` | timeoutOutcome | **unknown** | A2，文案不动 |
| 2 | `:459` | 未知工具 | failed | |
| 3 | `:704` | 动作表·页面守卫 | failed | |
| 4 | `:711` | 动作表·步骤守卫 | failed | |
| 5 | `:718` | 动作表·需任务守卫 | failed | |
| 6 | `:734` | 动作表·参数不全 | failed | |
| 7 | `:753` | 页面就绪+任务绑定 | ok | 合法成功，不动语义 |
| 8 | `:761` | 就绪未绑定 | failed | 已是正解 |
| 9 | `:768` | **等待上界耗尽** | **unknown** | **D4(a)，availability 降 degraded** |
| 10 | `:774` | 契约受理无导航 | ok | D4(b) 不改 |
| 11 | `:778` | 契约拒绝 | failed | 先例 |
| 12 | `:815` | 参数校验失败 | failed | |
| 13 | `:825` | scope 不符 | failed | |
| 14 | `:834` | 无执行器 | failed | |
| 15 | `:842` | 执行器成功支 | 依返回值 | A4：`normalizeExecutorReturn`（字符串→ok；结构化→按其 outcome） |
| 16 | `:845` | 执行器 throw | failed | |
| 17 | `:857` | ui_event 参数不全 | failed | |
| 18 | `:864` | dispatchUiEvent 受理 | handled?'ok':'failed' | D1 点名，handled:false 不再戴 ok:true |
| 19 | `:867` | dispatchUiEvent throw | failed | |
| 20 | `:885` | **handler 成功支** | 依返回值 | **A3**：`interpretHandlerOutcome`——字符串→ok（兼容）；`{ok:false,reason}`→**failed**（D2）；`{ok:true}`→ok |
| 21 | `:891` | handler throw | failed | |

A5：`defaultExecutor` 复用 `interpretHandlerOutcome`，结构化失败原样透传（不再自吞）。**D2 点名处置**：`ExportWizardView.vue` 编辑器加载失败支（基线 `:1236`）改为返回 `{ ok:false, reason:<原文案逐字保留> }`——确定性失败落 `failed`，不得误入 `unknown`，也不再被包装层折成成功。

D7/D8（导出向导）：`goStep(): Promise<StepAdvanceOutcome>` **永不 reject**；`persistStepData` 失败⇒阻断推进；`syncStepToBackend` 失败⇒推进+`ElMessage.warning`+回灌附「步骤已推进但服务端步骤同步失败，下次切步会重试」（不阻断，R-d）；`ensureStepLoaded` 不纳入结局并注释。5 调用方全改：模板 `:14`（无需改语法，Promise 返回值被模板忽略且不 reject）、`:599` `void goStep(...)`、`:1128` preflight（阻断时 `{ok:false,message}`，执行器 throw→确定性失败，**不发起下载**）、`:1146`（撒谎点本体：未推进如实回灌）、`:1225` openEditor（结构化失败透传）。
D9（导入向导）：`syncStepToBackend` D8(A) 同法（结构化失败+`ElMessage.error`）；`goStep` 最小接入（失败不阻断+`ElMessage.warning` 同款措辞）。
A13：`toolTagType` `pending` 补显式 case（'primary'）+ 去 `default`，九态穷尽对齐 `:563-579` 的 `Record` 闸。

## §3 T1~T9 RED→GREEN 双向留证

| 锁 | 用例落点 | RED 留证 | GREEN 留证 |
|---|---|---|---|
| T1 生死线 | `frontend-tool-result-terminal.spec.ts`（扩展，2 例：FRONTEND_RESULT / TIMEOUT 均不翻写） | `RED-T1-T2-T3.log`（4 failed） | `GREEN-S3-full.log`（106/106） |
| T2 X1 | 同上（unknown+requiresAction 违例段 ⇒ pendingCall=null + console.error 断言） | 同上 | 同上 |
| T3 D6(A) | 同上（`hasDecidedPendingResidue` 拦 unknown/failed） | 同上 | 同上 |
| T4 D4(a) | `frontend-tool-outcome.spec.ts`（navigate_to 3s 未就绪 ⇒ unknown+ok:false+degraded，fake timers） | `RED-T4-T6-T8.log`（9 failed） | `GREEN-T4-T5-T6.log`（9/9） |
| T5 A3/A5/D2 | 同上（结构化 {ok:false}→failed；字符串→ok；defaultExecutor 透传 failed） | 同上 | 同上 |
| T6 A4 | 同上（执行器结构化/字符串/throw 三路） | 同上 | 同上 |
| T7 W-06 | `export-wizard-step-outcome.spec.ts`（新增，4 例：nextStep+persist 失败 / nextStep+goToStep reject / download 执行器 preflight 残余入口 / openEditor 残余入口，真实挂载+失败注入） | `RED-T7.log`（4 failed） | `GREEN-T7.log`（4/4） |
| T8 D1 | `frontend-tool-outcome.spec.ts`（工厂纯函数 2 例） | 同 T4 RED | `GREEN-T8.log`（2/2） |
| T9 R-e | 既有 9 个 spec 不断言语义变更 | S2 全量跑 102 passed（仅 4 个 S3 新用例红） | `S4-full-test.log` 110/110 |

## §4 验证三连 + E2E

```
yarn typecheck   → 0 错（S4-typecheck.log）
yarn test        → 11 files / 110 tests 全绿（S4-full-test.log）
yarn build       → ✓ built in 26.68s（S4-build.log）
E2E 桩模式       → PASS=28 FAIL=0，GFSKIP=0，Q8①② PASS（e2e-stub-4.log，e2e_exit=0）
```

E2E 环境说明（如实申报）：`verify-e2e.ps1` 自身不启动后端；本棒按脚本头约定自行起栈——`stub-upstream.mjs --port 18399` + `config-mgr.jar --server.port=18330 --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`（AI_BASE_URL 指向桩，JDK 21）。本批**未触后端源码、未重建 jar**（jar 为 2026-10-11 20:20 既有产物，起手 `git status` 干净可证）。过程中曾因执行策略与 BackendLog 路径缺失误判两轮（e2e-stub.log/2/3.log），均已定位并复跑澄清，最终以第四轮 28-0 为准。GF4/GF5 在第二轮的偶发失败（第三轮起消失）为桩/时序抖动，与前端改动无关（verify-e2e 为后端 HTTP 断言，本批零后端改动）。

## §5 CDP 冒烟（D12(A) 强制覆盖；脚本 `<SCRATCH>/chat-timeline-pkg/batch3a-outcome-smoke.mjs`，仓外）

配方（与 R-140 ②o3 一致并实证）：`VITE_DEV_PORT=5202 VITE_API_BASE=http://localhost:18330 yarn dev`；后端 18330（同 §4 栈）；`node batch3a-outcome-smoke.mjs`（自带 headless Chrome，CDP 9344，**不触碰用户浏览器**）。注入方式与卡 §6 配方一致（永不结算执行器 / 极小预算、chunk 未拉窗口、断网），**无偏离**。

结果：**15/15 PASS**（`cdp-smoke-final.log`，exit 0）：
- **A2（底线，5 项）**：`timeoutSeconds=6`（预算折算 5s）+ 永不结算 handler ⇒ 段 `unknown`、卡面 tag「结局未知（未确认成功/失败）」可见、降级 notice 与回灌文本同源；**回执帧到达后一拍再断言**：段仍 `unknown`（first-wins 不翻 succeeded）、tag 不变——A9 的 GLM 验证法实证。
- **A6（3 项）**：离线拦 `ExportWizardView.vue` chunk ⇒ 页面未挂载窗口 ⇒ 段按**契约层如实拒绝**收敛（`failed`，非成功、未谎报）+ 降级 notice 可见。口径申报：本场景落 `:776-781` 契约拒绝支（router.push 因 chunk 拉取失败而 reject）；`:767-772` 等待上界支（outcome `unknown`）由单测 **T4** 以 fake timers 锁死——两支共享同一 `degraded→notice` 可见后果，卡 §6 对 A6 的可观察要求（非成功 + notice）达成。
- **R-84（5 项）**：真实导出任务（API 建任务→选 2 配置→EXPORT 作业→COMPLETED）→ 向导挂载 → 断网 → 同一 `DOC_TYPE` 失败经 **工具名路径**（`open_export_file_editor`→defaultExecutor）与 **handler 路径**（`export.openEditor`）各触发一次：双路径段均 `failed` 且**失败文案逐字一致**（「DOC_TYPE 的在线编辑器表格未就绪…」）。

截图（`批3a-evidence/cdp/20261011T160732Z-*.png`，含时间戳）：`A2-unknown-before-receipt.png` / `A2-unknown-after-receipt.png` / `A6-page-not-ready.png` / `R-84-both-paths-failed.png`。施工棒已目检 A2/R-84 两张：卡面/notice 与断言一致。

## §6 归属订正（照卡 §2 末，必写）

`unknown`/`cancelled` 枚举 = **批 1（`1efab09`）**落地。历史 = 6 态（`b2b2ec5`）→ 7 态（`ad3ffb8`，+`blocked`）→ 9 态（`1efab09`，+`unknown`+`cancelled`）。「批 2 完成 7 态收敛」叙述**作废**；批 2（`6bea4ca`）未改枚举行、只改注释。另：A10 事实（D10）`toolTagType` 为 8 态显式 + `pending` 走 `default`——本批已订正为九态穷尽。D11 论据订正已执行（不加机器前缀；结局走 `outcome` 字段而非文本编码，`types/ai.ts:66-78` payload 单字段 `result` 为证）。

## §7 已知代价申报（不得静默留白）

1. **E17 ⇒ R-141①/②（移交 ③b）**：`unknown` 经 re-attach 新宿主（`ai.ts:1008` 全量回放建 `succeeded` 新卡）与 loadHistory 整替（`ai.ts:644` 硬编码 `succeeded`）两路仍会被翻成 succeeded / 产生重复卡。对 `failed`/`cancelled` 系为存量同族，对 `unknown` 为本批引入面的**已知代价**；③a 内修只覆盖「同消息帧回执」一路（T1 生死线），`localTerminal` 是 `unknown` 存活唯一机制（payload 无 status/ok）。
2. **R-142（本批新增残留，建议编号）**：A6 场景下若 `router.push` 挂在 chunk 拉取（既非拒绝也非成功），回灌会走执行器预算超时（60s 口径）而非 3s 等待上界——预算折算口径见 `deriveFrontendToolBudgetMs`，属可接受退化（方向安全：不谎报成功），登记备查。
3. **E19 剩余 21 处失败形字符串**：导出侧其余 10 处 + 导入侧 11 处（含 `:1047` 降级形标注、4 处三元/降级形）本批一律未动，登记待批；在结构化通道已就位的条件下，这些 handler 返回的字符串仍走「字符串⇒ok」兼容支（卡 D3(iii) 兼容口径）——**这是登记的既有面，不是本批新谎报**。
4. **A2 注**：CDP 中 `submitFrontendToolResult` 被桩掉（本脚本的 toolCallId 未在后台登记，POST 4xx 会把 notice 覆盖成报错——属既有 submit 失败路径）；生产真实帧 POST 成功，notice 即「结局未知」原文（T4/A2 断言的 result 文本可证）。

## §8 保护面如实申报（D6，禁写「重挂已闭合」）

`isFrameDecidedPendingStatus` 纳入 `unknown`/`failed` 后，实际保护面 = **①快照恢复路**（`restoreSuspendedSnapshot` 全条目）+ **②GF 帧路**（`ai.ts:1573`/`:1620` 均带 `GENERATIVE_FORM_TOOL` 前缀）。**通用 `FRONTEND` 工具的重投帧现状不经该守卫**（`ai.ts:1590` 直跑 `runFrontendTool`）——既有缺口，归 ③b（R-141③）。本批未闭合、也不申报闭合。

## §9 耦合与台账

- **D3(iii) 耦合红线**：A4 + A5 + A8 + A9 + A10 + `pendingStatusOf`(X1) + `ai.ts:1705`/`ai.ts:246` 两处同型面 + 兼容口径 + T1/T2/T3 **全部同批落地**（零 git 写下由提交官一笔提交即天然同提交）。T1 生死线绿 = 红线成立的直接证据。
- **R-137 守卫耦合申报**：D4(b) 维持 `:774` 不改，论据按卡订正版（`workspace.ts:392-401` `goto_step` 无只读守卫，`:407-409`/`:422-424` 另两支有）；D8(A)/D9 的 catch 覆盖「任何非 2xx（含未来 R-137 守卫 4xx）」——R-137 批落地时服务端步骤守卫 4xx 会被本批结构化失败如实接住，无静默面。
- **R-97 行尾注记订正建议**（本批不动 M2 正文，提交官办理）：`docs/M2-排期计划.md:289`「两个调用方」与实态 **5 处**（模板 `:14`/`:599`/`:1128`/`:1146`/`:1225`）不符。
- **R 编号**：R-141（E17①/② + E20①/③）、R-142（§7-2）——R-138/139/140 已占用，未撞号。
- **R-139 逐项**：①m2 死码守卫（`ai.ts:225-227`）⇒ **③b**（本批未动，登记维持）；②m3 申报粒度 ⇒ **本批前置登记面**（本报告 §1 锚点表逐条点名即申报，不改代码）；③O1 挂起卡终态 tag 不可达（`AiPanel.vue:250-285` 死码）⇒ **③b**；④O2~O5 ⇒ 随 ③a/③b 销记：O2 `appliedSeqs` 两维错位（本批未动、实测仍无路径可达）、O3 v-for key 理论碰撞（未动，服务端 UUID 不可达）、O4 `isTyping`（未动，后端不产 reasoning 帧）、O5 = o1~o4 复核维持（见 R-140）。
- **R-140 逐项**：①-1 终态挂起卡不保留 ⇒ ③b；①-2 suspended 单独到达 ⇒ ③b；①-3 error 帧收挂起死卡 = 改善（维持）；①-4 存量 v2 镜像 `streaming:true` ⇒ ③b；①-5 复制按钮位置 **CDP 人工面未在本批确认**（③a 范围外；建议随 ③b 或专项跑 `chat-panel-smoke.mjs` 时目检）；①-6 `<details>` 持久化 ⇒ 批 4；①-7 重复终帧不复位 loading（未现症，维持登记）；①-8/9 o-R2/o-R4 维持；①-10 Maven ERROR 噪声治理（未动）；①-11 E2E 前重建 jar 口径（本批未重建，已申报 §4）；①-12 dist 未运行时验证（本批 build 绿 + CDP 走 dev 形态；dist 形态证据留待需要时 `yarn preview` 复跑）；②-o1 `appliedSeqs` 无上界（维持登记）；②-o2 滚动指纹盲区（维持）；②-o3 CDP 配方（**本批实证**：5202 + VITE_API_BASE，照方复跑成功）；②-o4 stop() 不写段终态（本批未动，维持）；③§5-5 口径角（本地乐观 cancelled 帧路 first-wins 保持批 2 裁决）——**本批将 `'unknown'` 纳入 `localTerminal`（D5(甲)）但未动 `'cancelled'`**，「帧胜候选」未采纳，残余不对称维持原状、待 K3 定夺。

## §10 偏差逐条申报

1. **D2 × E19 调和（唯一实质偏差）**：卡内 D2 点名 `:1236` 落 `failed`，E19 登记清单写「本批一律不动」——二者对 `:1236` 字面相斥。施工取值：**D2 是终裁点名项且验收点 #1 依赖它**，故仅 `:1236` 一处改为结构化 `{ok:false, reason:<原文案逐字>}`（判据与文案零改动），其余 20 处登记字符串一律未动。若提交官认为应连 `:1236` 也回退，则验收点 #1 失守——不建议。
2. **A6 CDP 口径**：见 §5——CDP 覆盖「chunk 未拉」场景（卡 §6 配方原文）的契约拒绝支；`:767-772` 等待上界支（unknown）由 T4 单测锁死。两支 degraded→notice 可见后果一致。
3. **T7③ 入口构造**：`:1128` preflight 的 goStep 分支在「scope 已满足」的稳态下，向导必已跟随 workspace.step 到达第 3 步（契约联动 watcher），persist 失败注入在稳态不可达；用例改用 **issue #2 真实形状**（args.taskId=8 ≠ 绑定任务 7 → 切绑恢复首步 → goStep(2) 真实可达）完成注入。这是测试构造说明，非产品缺陷。
4. **A14**：未做枚举扩张/重命名（九态 = 批 1 原样）；全仓 `ToolRunStatus` switch 仅两处（`toolTagType` 已穷尽无 default；`pendingStatusOf` default 为 X1 门禁后的类型兜底并注释），回归确认无遗漏。
5. **E18**：仅 `Export:589`(A12) 与 `Import:763`(D9) 两处本批修；其余仅注释型 5 处 + 全仓 11 处 + `.catch` 变体 5 处均未动（登记维持）。
6. **施工中新发现并已修的测试夹具问题**（非产品偏差）：T4 初版因真实 router 懒加载拖超时，按「桩 router 只测等待上界」修正；`{success,data}` 包未解包导致 R-84 建任务 500——均为脚本/用例侧修正。
7. 起手承诺「未获授权的仓库外文件一律不改」——唯一仓外写入为卡/报告/证据目录与 CDP 脚本（`<SCRATCH>/chat-timeline-pkg/`），均属授权写入面。

## §11 交付物清单（与卡 §8.2 对账）

| 类 | 内容 | 状态 |
|---|---|---|
| 代码（7 文件） | `types/tools.ts` / `utils/frontend-tools.ts` / `utils/parts.ts` / `stores/ai.ts` / `components/AiPanel/AiPanel.vue` / `views/ExportWizardView.vue` / `views/ImportWizardView.vue` | ✅（`git diff --stat`：8 跟踪文件 +441/-102（含 2 新测试文件合计 +771/-102）；两向导仅按 D3(iii)/D9 范围动） |
| 测试 | 新增 `frontend/tests/frontend-tool-outcome.spec.ts`、`frontend/tests/export-wizard-step-outcome.spec.ts`；扩展 `frontend/tests/frontend-tool-result-terminal.spec.ts`、`tools-and-deadline.spec.ts`（T4 用例并入前者？——见下注） | ✅ |
| 证据 | 三连日志、E2E 桩日志、CDP 15 项 + 4 截图、锚点表 | ✅ |

注：`tools-and-deadline.spec.ts` 最终**未改动**——T4 落在新建 `frontend-tool-outcome.spec.ts`（同语义更内聚），卡 S2「扩展 tools-and-deadline」为建议落点，此为准许范围内的等价偏差，申报如上。

---

## §12 终审复核与修复轮（fresh reviewer，2026-10-12）

提交前按流程派独立复核（全量 diff 对 `3edaeb9` + 实跑 typecheck/vitest 复核基线）。结论：**无 Critical**；2 项 Important 已修（RED→GREEN），9 项 Minor 全部登记不修。

**已修（Important）**：
- **I1** `export.nextStep` 失败支返回字符串 ⇒ 卡面 tag「已成功」与卡内文「步骤未推进」自相矛盾（本包根治面）。修法：该支改返回 `{ ok:false, reason:<同文案> }`（与 `:1281` openEditor 同口径）。测试锁：`GREEN-T7` 用例补 `execution.outcome==='failed'` 断言，修复前红（`RED-I1-I2.log`）、修复后绿（`FINAL-full-test.log`）。
- **I2** T7③「不发起下载」断言空转（ExportWizard 走 `utils/excel#downloadBlob`，不 import `api/http#saveBlob`，旧 spy 对象错误）。修法：`vi.mock('@/utils/excel')` 部分 mock 钉住 `downloadBlob` 真 spy，断言改挂该 spy。RED 说明：该缺陷是「空转断言」而非行为缺陷，I1 修复后旧断言对新代码本可通过——RED 证据为复核的静态证伪 + 新断言在修复前实现下必失败（下载会真实发起）。
- 顺手清两处过时注释：`parts.ts:262` 模块注释补 `unknown`（M1）；`ai.ts:242` `expireResidueToolParts` 文档改为「pending→expired；非 pending 保持原终态」（M2）。

**登记不修（Minor，逐项）**：
- **M3** `toolTagType` 去 default 后越界 status 返回 undefined（el-tag 视觉等价）——穷尽闸即目的，不修。
- **M4** `execution.reason` 全仓零读者（未落段 reason，AiPanel「原因：」位空）——`reason` 为契约预留字段，申报备查；如后续要红色原因行，把 `execution.reason` 落段即可（低成本增强，非本批口径）。
- **M5** `interpretHandlerOutcome` 只认 `ok === false`：`{reason:'x'}` 无 `ok` 会落成功兜底——当前无生产者，属防未来写点，契约以注释声明（字符串⇒ok 为 D3(iii) 兼容口径）。
- **M6** 导入侧 `goStep` 同步失败的警告只给 `ElMessage`、回灌文本仍是干净「已推进到…」——与导出侧口径不对称；D9 裁定为「最小接入」（不改 goStep boolean 签名），登记为口径差异（若 K3 要对称，随 ③b 一并处理）。
- **M7** `startExport` 丢弃 `persistStepData` 结构化结局后照旧 `createJob`——**既有面**（与基线行为一致：失败仅 ElMessage），非本批引入；属 A11 危害模型的弱可达残余（`start_export` 为 BACKEND 通道工具、与 `export.start` handler 名不匹配），登记待批（修 = 3 行阻断，建议随下一批）。
- **M8** goStep 先 persist 后切步后连点的细粒度竞态（双击 prev/next 可能并发两轮 persist）——无崩溃/无 unhandled rejection；如需 in-flight 抑制随 ③b。
- **M9** 路由 3 `handled:false→failed`（D1 点名）无直接单测——与旧 `ok: outcome.handled` 消费点逐值等价（复核员可证），红不出来；补锁随 ③b。

复核员明确「未见问题」的面（21 处收口无漏网、X1 门禁不误伤 pending、A8 无 undefined 逃逸、A2→A8→回执帧三环闭合、D9 未改 boolean 语义等 14 项）从略，详见复核会话留档。

---

**签字**：施工棒 **Kimi K2.8 Preview**（批 3 ③a 施工执行；CU 直发会话 `session_ec704eaa-0256-4a6d-8a78-49867b5d8742`，wire `modelAlias=kimi-code/kimi-for-coding` 实证；机械活下沉子智能体 `agent-497` 显式钉 `DeepSeek/deepseek-flash`），2026-10-12。**授权来源 = K3 指挥官批 3 ③a 施工卡**（`决策卡-批3③a.md`，K3 代签，2026-10-11/12）。零 git 写；本报告所列证据均可于 `<SCRATCH>/chat-timeline-pkg/批3a-evidence\` 复核。
