> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 仓外复核报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 复核报告 · 批 3' ③b（一卡全生命周期：段状态机收口 + 死亡代码清除）

| 项 | 值 |
|---|---|
| **执行者模型** | **GLM-5.3**（复核与测试角色，独立复现 + 对抗反查） |
| **授权来源** | **K3 指挥官复核卡（DC-19）** |
| **被复核对象** | Kimi K2.8 Preview 施工的批 3' ③b 工作树未提交改动（10 文件 / +529 / -79） |
| 仓库 | `<MAIN_REPO>`（HEAD `505f6d4`，全程未动） |
| 复核时间 | 2026-10-12 02:30 – 03:00（本地，+08:00） |
| 与红队并发 | 红队棒（DS V4 Pro）另卡并行执行；本棒复核时段内未观测到 18330/18399/5202/9345 被他棒占用（5202 旧进程系施工棒遗留，见 §8-B5） |
| 纪律自证 | **零 git 写**（无 add/commit/stash/checkout/apply；`git apply` 只在系统临时目录由验证器执行）；**零仓库文件修改**（收尾 `git status --porcelain` 复核仍恰为 10 ` M `）；未读任何 `.env`；未重建后端 jar（沿用 `backend/target/config-mgr.jar` 2026-10-11 20:20 产物）；自起进程（vite / 后端 / stub / CDP Chrome）全部按 PID 停止并核端口清空 |

---

## ② 范围与方法

- **锚定核验**：SHA-256 / 字节数 / 段数 / `git status` / 逐文件 EOL 归一化指纹 / 生成器重跑 / 保真验证器（archive→临时目录 apply→逐文件比对）/ `.bak` 单文件交叉核验 —— 全部亲跑。
- **独立复现五连**：typecheck / test / build / E2E 桩模式 / CDP 冒烟 —— 全部亲跑并留证（原始输出见 §④；日志副本存 `/tmp/b3b-review-five-a.log`、`/tmp/b3b-rev-e2e.log`、`/tmp/b3b-rev-cdp/result-20261011T184554Z.json`）。
- **代码亲读**：锚定 patch 全文逐行（949 行）；`stores/ai.ts`（handleFrame 全帧路 `:1555-1756`、D6(B) 守卫 `:1563-1574`、runFrontendTool `:1852-1906`、loadHistory `:596-700`、restoreSuspendedSnapshot `:736-780`）、`utils/parts.ts`（suspended 支 `:420-438`、frontendResultStatus 注记 `:238-252`）、`AiPanel.vue`（死码删除后 `:196-266` 全链）、两向导 goStep/syncStepToBackend、`types/ai.ts:338-344`。关键锚点与关键结论**全部由本棒本体亲读**，未只凭案卷文字落卡。
- **机械活下沉自证（§7 条款）**：本棒**未使用任何子智能体**（无 Agent 调用）——机械活（SHA/指纹/计数/对账）以工具直跑完成，不属于"下沉子智能体须钉 `model=DeepSeek/deepseek-flash`"的适用情形；故本报告无子智能体模型可自证，亦无相应局限需要申报。若按最严口径把"工具调用"视为广义下沉，则下沉对象为 Bash/Read/Grep 工具本体（非模型调用），**关键判读全部由 GLM-5.3 本体完成**。

---

## ③ 锚定核验声明（硬性第一步，全部通过）

| 项 | 结果 |
|---|---|
| `diff-b3b.sorted.patch` SHA-256 | `8b7149bedbd2350f4641068ed9a95bce4eea13117abbc38bd2a51bc2a1eda0c1` **✔**（58420 字节，10 段） |
| `diff-b3b.patch`（同源未排序版）SHA-256 | 同值 `8b7149be…a1eda0c1`，58420 字节 **✔**（与 sorted 版逐字节一致；本批无未跟踪新文件、`git diff` 自身按路径序输出，两版一致合理） |
| `grep -c '^diff --git'` | 两版均 **10** **✔** |
| 夹带自核 | 无 `Binary files` / `GIT binary patch` 段；10 段路径与 §1.5 表逐条一致；无 10 路径以外的 `diff --git` 段。`grep '\.env'` 命中 3 处均为 **`import.meta.env.DEV` 代码标识符**（D12 的 DEV 门实现与注释，`utils/parts.ts`）——非 `.env` 文件读取/凭据泄露，**不判夹带**（按任务卡 §1-2 防误报口径） |
| `git status --porcelain` | **恰 10 ` M ` / 0 `??`**，路径与 §1.5 逐条一致 **✔**（复核收尾时再次确认仍为同一状态——审查对象未漂移） |
| HEAD | `505f6d4e0fa1360f04a3dbe3313842f1513a0952` **✔** |
| **生成器可复现性** | 重跑 `node _anchor-diff-b3b.mjs <repo> <临时目录>`：SHA-256 同值、58420 字节、10 段、+529/-79，输出未覆盖案卷 **✔** |
| **保真验证器** | `node _verify-fidelity-b3b.mjs`：archive HEAD → 系统临时目录 `tar -x` → 临时目录 `git apply -p1` → **10/10 文件 apply 后与工作树 EOL 归一化哈希逐一致**（受审仓库零 apply）**✔** |
| **逐文件指纹** | `_anchor-stats-b3b.mjs` 输出与卡 §1.5 表 **10/10 全部一致**（含 +8/-30…+27/-4 段内增删、字节、CRLF/LF 标注、EOL 归一化 SHA）**✔** |
| **`.bak` 单文件交叉核验** | `批3b-evidence/ExportWizardView.vue.bak` SHA-256 = `756b660a…7584603`（65566 字节）**✔**，且与工作树 `ExportWizardView.vue` `cmp` **逐字节一致** —— T7 恒假变异未残留、该文件自证据生成后未再漂移 **✔** |
| **结论** | **审查字节与锚定一致**。 |
| **局限申报（诚实条款）** | ③b 证据目录**无独立全量 diff 快照**（③a 有 `final-review-v2.diff`）⇒ 本批不存在第三方全量快照交叉核验；主判据为逐文件指纹表 + 生成器复现 + 保真验证器三重链（三者自洽）。本卡如实申报此局限。 |

---

## ④ 独立复现五连（全部亲跑，原始输出摘录）

**时间**：2026-10-11T18:37:50Z – 18:38:23Z（①②③）；2026-10-12 02:41–02:46（④⑤，本地 +08:00）

| # | 命令 | 结果 | 与案卷对照 |
|---|---|---|---|
| ① | `cd frontend && yarn typecheck` | **0 错**（`TYPECHECK_EXIT=0`） | ✔ |
| ② | `yarn test` | **Test Files 11 passed (11) / Tests 125 passed (125)**（`TEST_EXIT=0`） | = `GREEN-final.log` 125/125、11 spec ✔ |
| ③ | `yarn build` | **`✓ built in 19.73s`**（`BUILD_EXIT=0`；`vite v6.4.4`） | 绿 ✔（案卷 25.74s，耗时差异为机器负载，结论同向） |
| ④ | E2E 桩模式 | **PASS=28 FAIL=0、GFSKIP=0、Q8①② PASS、`e2e_exit=0`** | = `E2E-stub-final.log` 28-0 ✔ |
| ⑤ | CDP 冒烟（`batch3b-lifecycle-smoke.mjs`，SMOKE_OUT=临时目录） | **PASS=10 FAIL=0**（①~⑨ + 前置全过） | = `cdp/result-20261011T182213Z.json` 10/10 ✔ |

**④ 配方与两次前置失败（如实申报）**：`verify-e2e.ps1` 自身不起栈，本棒自起：`node scripts/ci/stub-upstream.mjs --port 18399` + `java -jar backend/target/config-mgr.jar`（Redis 127.0.0.1:6379 既存监听确认）。首跑 `E2E_EXIT=1` 系 **PowerShell 执行策略拦截**（UnauthorizedAccess）⇒ 加 `-ExecutionPolicy Bypass`；二跑 **27 PASS / 1 FAIL**，失败为 **TC11「后端需以 `--app.job.batch-size=10` 启动」** —— 系本棒复现配方遗漏启动参数（`verify-e2e.ps1:31` 注释行明载该建议参数），非产品缺陷，也非施工报告数字不实。带 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150` 重启后端后三跑 **28-0 / e2e_exit=0**。端口：后端 18330（首启曾漏 `SERVER_PORT` 落 8081，随即重启修正——与产品无关）。跑毕清理 stub 落盘目录 `stub-dump/`、按 PID 停 stub/后端。

**⑤ 配方（D13(B) 纪律照做并留证）**：起手发现 5202 被同仓 vite 旧进程占用（PID 8400，`Get-Process` 实证 StartTime = 2026-10-12 02:21:59，即施工棒 CDP 轮启动的遗留 dev server）⇒ 按 D13(B) **终止该进程**（此刻 9345 无监听、无他棒 CDP 活动，判定为施工遗留而非在飞会话）并**全新启动**（`VITE v6.4.4 ready in 439 ms`，新 PID 32516）后跑冒烟，无 HMR 换版风险。跑毕停 vite、核 5202/9345 释放。**自产截图两张 md5 亦完全相同**（`bce86436…`，各 51823 字节）——独立复现了案卷"两截图同 md5"现象，佐证 §8-附加判定中的根因分析。

---

## ⑤ A 条目施工结果逐条结论（vs 决策卡 D1~D16 终裁）

> 证据分级按【实测】（本棒亲跑/亲读）/【推断】（静态实读代码路径推得）/【假设】（依赖未实跑前提）。锚点行号 = 施工后工作树实读。

| 条目 | 判定 | 分级 | 证据与说明 |
|---|---|---|---|
| **A2 / D3** 删 sanitize 守卫 + 入参契约 | **落实** | 【实测】 | patch 段 2（`ai.ts` hunk `:216` 起）：`if (message.done !== true) { continue }` 已删；注释写明「入参契约 = 调用方须已归一 done」+ 反向风险（直调镜像原始数据 ⇒ `undefined !== true` 全段跳过、不得恢复守卫）——与 D3(A) 三要素齐。唯一调用点 `loadHistory` `:618` 前置归一 `done:true`（`:608`）亲读在位 |
| **A3 / D2-O1** 死码删除 | **落实** | 【实测】 | 删除范围 = 原 `:206`（自动 tag）/ `:250-253`（GF 终态列，含 `tag-form-cancelled`）/ `:264-277`（通用 FRONTEND 列）/ `:280-286`（CONFIRM 终态列）——patch 段 1 逐行核对；**排除项完好**：`:212-231`（CONFIRM+pending 确认按钮/倒计时/拒绝原因，`restoreSuspendedSnapshot` 重建路可达）与 `:251-255`（GF 提交窗口 `cancelled`/`submitted`/`v-else` 状态已丢失）实读在位；**链结构完整**：`:212` `v-if`（CONFIRM+pending）→ `:233` `v-else-if`（GF）→ 内层 `:247` `v-else`（FormRenderer 之外）内 `:251` `v-if`（activeForm cancelled）→ `:252` `v-else-if` → `:253` `v-else`——原 `:254` 链头已收拢为 `v-if`，无悬空链头、无无主 `v-else`；删除痕迹注释 `:248-250`/`:261-264` 在位。**grep 自查**：`AiPanel.vue` 内「自动」= 0、`tag-form-cancelled` = 0（全仓仅 `scripts/m2t2a-smoke.mjs` 3 处引用，与施工报告自报一致） |
| **A5 / D5** suspended 建段 | **落实** | 【实测】（前提为【假设】） | `parts.ts:429-435`：`upsertToolPart(..., {status:'pending', requiresAction:true})`——第三参缺省 = `createIfMissing=true`（`:90` 签名亲读）+ `status:'pending'` ✔；**建段前过闸**落在 store 侧 `handleFrame` suspended case `:1688`（`hasDecidedPendingResidue` 天然跨消息 `:1235` 亲读，防跨消息双段）✔；注释 `:421-426` 写明**双兜底**（轮终态 `expireResidueToolParts` + 快照非 PENDING 收敛 `:749-756` 亲读）、**不依赖「生产不可达」** ✔；前提「后端必发终态帧」在施工报告 §6b 以「未实跑复验」措辞如实申报 ✔；**预期翻红两锁同批改写**（`parts-reducer.spec.ts:184` / `parts-residue-cleanup.spec.ts:465`，patch 段 9/10 核对）+ 新增「建段 → done ⇒ expired」锁（`parts-residue-cleanup.spec.ts:504`）✔，RED-pre 11 红中 3 红属 D5 面（亲读红点为 `toHaveLength(3) but got 2` / `false to be true` / `[] to have length 1`——预期断言非环境错） |
| **A6 / D4** 镜像段级 streaming 归一 | **落实** | 【实测】 | `ai.ts:613`：`parts: message.parts.map(...)` 逐段清 `part.streaming`（数行、非一行）✔；与消息级归一（`:607-608`）同点；**未收进 sanitize**（sanitize 注释与函数体均不含 streaming）✔ |
| **A7 / D6(B)** 店级守卫（switch 前四型） | **落实** | 【实测】 | `ai.ts:1569-1574`：switch 前守卫，条件 = `frame.type ∈ {tool_start, tool_result, frontend_tool_result, confirm_decision}` **恰四型** && `hasDecidedPendingResidue(frame.toolCallId)` && `!findToolPart(message, frame.toolCallId)` ✔。注释 `:1563-1568` 写明职责（跨宿主已决终帧整帧跳过）、只覆盖工具终态/起始帧、挂起语义帧归 D8 case 守卫、契约前提（UUID）。**终态帧 case 一字未动**：`tool_result :1605-1610` / `frontend_tool_result :1656-1676` / `confirm_decision :1624-1630` 与 ③a 版本一致（patch 段 2 该三 case 无 hunk 命中——亲读 diff 确认）；③a E17 回归锁（result-terminal 20 例）在 125 全绿中。**错价论证诚实**：UUID 前提下跨消息已决终帧无正当来源，误杀面≈0；前提破裂则漏写一张卡（不劣于施工前）——成立。**禁语核查**：代码注释与报告均**未出现「A7 已闭合」**（报告 §7-1 反而明示「不得表述 A7 已闭合…只闭合已决面」）✔；未决双卡残余在报告 §7-1 逐条申报 ✔ |
| **A8 / D7(B)** 整替注记 + R-144 | **落实** | 【实测】 | `ai.ts:663-670` 段注释：占位值非事实断言 / 「必然丢失并显示为已成功——这是已申报的已知失真，不是可接受行为」/ 任何消费方不得以该 `succeeded` 做业务判定 / 任何后续批次不得把本注记解读为「历史整替丢 unknown 可以接受」——**四个要件逐字与决策卡 §1-A8 钉版正文比对一致**（含「任何后续批次不得把本注记解读为」措辞，卡面「任何后续批次不得把本注记解读为「历史整替丢 unknown 可以接受」」与代码同义逐字）。硬编码 `status:'succeeded'` + `kind2:'UNKNOWN'` 维持（`:671` 起 map 分支实读 + patch 无翻写）✔。R-144 立项登记于报告 §8-4 ✔ |
| **A9 / D8-①** 闸只拦挂起语义帧 | **落实** | 【实测】 | 守卫仅在 `confirm_request :1615` / `frontend_tool_request :1636` / `suspended :1688` 三 case 内；`frontend_tool_result` / `tool_result` / `confirm_decision` case **零守卫零改动**（activeForm 收敛 `:1665-1673`、`suspended=false :1674`、`tool_result :1605-1610` 原样）——X1 终裁满足；20 例 T1 生死线全绿佐证副作用未吞 |
| **A9 / D8-②** suspended 遍历口径 | **落实** | 【实测】 | `:1686`：`frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')` —— 读复数数组、无 `frame.toolCallId` 单字段读取（该字段在 `SseSuspendedFrame` 不存在，若读会是 `undefined` 恒漏拦）。判据钉在**写面条目**的 toolCallId 上（与 parts.ts `:427` 同口径 find）——见 §8-R3 裁决判定 |
| **A9 / D8-③** break + 职责分界 | **落实** | 【实测】 | 三 case 命中均取 `break`（`:1616`/`:1640`/`:1689`）⇒ switch 前的 seq 记账（`:1539`）与 switch 后的 `markApplied`（`:1749-1751`，注释「半途 break 的帧同样算处理过」）照常执行 ✔；职责分界（闸管语义、seq 管重复）在 case 注释 `:1612-1614` 写明 ✔ |
| **A9 / D8-④** 去 GF 前缀 + 回归留证 | **落实** | 【实测】 | `:1636` 守卫已无 `frame.name === GENERATIVE_FORM_TOOL` 前缀（patch 删除行亲读）✔；既有 GF 用例行为不变由「GF 回归锁」（result-terminal spec，armGenerativeForm + FRONTEND_CANCELLED 终态 + 重投 request ⇒ `activeForm` 仍 null + 段仍 cancelled）承载——**新增**非空转 GF 回归锁（施工卡 §10-47 判定「既有测试无 GF 前缀守卫直接覆盖」与我在 patch 中核对一致：新增于段 8）✔；「收窄面」锁（未决 n1 首轮照常执行、执行器恰 1 次）防误拦 ✔ |
| **A9 / D8-⑤** 终态帧副作用完好 | **落实** | 【实测】 | E17 回归锁（result-terminal 20 例）在 125 全绿中；副作用代码 `:1665-1674` 亲读未动 |
| **A10** rogue 面消解 | **落实** | 【实测】 | D8 关旁路后 `failed + requiresAction` 经 suspended 支复活的路径关闭：suspended 重投锁（result-terminal，failed 段 + suspended 重投 ⇒ `status:'failed', requiresAction:false` 持平、`pendingCall` null）+ CDP ⑥⑦ PASS 双证。未改 `pendingStatusOf`、未加 failed 特例（遵守终裁驳回 C3）✔ |
| **A11 / D9** 三对注记 + 一致性锁 + 误述订正 | **落实** | 【实测】 | ① `parts.ts:238-249` 注记（frontendResultStatus vs frameStatusToPendingStatus，唯一真实分叉 = SUCCEEDED，台账「后者缺 CANCELLED 注释」系误述订正在位）+ `ai.ts:341-345` 同对注记；② `ai.ts:388-393` 注记（isFrameDecidedPendingStatus vs localTerminal 同 7 态无共享常量、不得抽公共表）；**表驱动一致性锁两条**（result-terminal spec：两枚举成员集合一致 7 态逐候选举证 + 两翻译表 SHARED 七值逐对断言 + SUCCEEDED 分叉显式登记 `expect(frameStatusToPendingStatus('SUCCEEDED')).toBeNull()`）✔。R2 裁决（为锁 export 两个函数）见 §8。R-141④ 误述订正（`CANCELLED` 注释实存 `:347-349`——注记文本中引证）落实 |
| **A12 / D10** 入口捕获 + 失配丢弃 | **落实** | 【实测】 | `:1859` `const controllerAtStart = currentAbort`（模块级 currentAbort 身份比对，注释写明 isCurrentRound 非-drop-in）；`:1883-1885` await 返回后比对、失配 `return`（丢弃段写 `:1893-1900` 与回灌 `:1905`）✔；`:1863-1870` 开跑落 `running` **未被误改**（校验点只在 await 后）✔；测试锁以「reattachActive('run-2') 换句柄」制造失配，断言段保持 running + `frontendToolResult` POST 未发 ✔ |
| **A13 / D11** 单点弹 | **落实** | 【实测】 | 两向导 `syncStepToBackend` catch 内 `ElMessage.error` 均删（patch 段 4/5 亲读，注释改为「本函数不自弹…由调用方 goStep 只弹一条 warning」）；goStep 单点 `ElMessage.warning` 保留（Import `:741-743` 亲读）。**D11 单点弹锁**（export-wizard spec：`ElMessage.error` 0 次 + `warning` 恰 1 次，vi.mock element-plus 计数）✔ 且 RED 轮红在 `expected "vi.fn()" to not be called at all, but actually been called 1 times`（双弹被锁实红）——非空转 |
| **A14 / D12** DEV 门 | **落实** | 【实测】 | `parts.ts:289-291` `if (!import.meta.env.DEV) { return }` + 注释（vitest DEV=true ⇒ 既有测试不变）✔；**D12 DEV 门锁**（`vi.stubEnv('DEV', false)` ⇒ 违例段不 `console.error`、投影行为不变）✔，RED 轮红在 `expected "error" to not be called…1 times`（旧代码生产仍 error）——非空转；125 全绿证 vitest DEV=true 路径既有锁未削弱 |
| **A15 / D13** 脚本头纪律 + (A) 注记 | **落实** | 【实测】 | `batch3b-lifecycle-smoke.mjs` 头部 `:8-10`「复跑本脚本前必须重启 dev server——探针以裸路径 import('/src/stores/workspace.ts')…对 HMR 换版脆弱」✔（本棒亲读并照做）；「(A) 已排查无应用内入口」台账注记在报告 §8-2 ✔ |
| **A16 / D14** T7 四例反向留证 | **落实** | 【实测】 | `RED-T7-1/2/3/4.log` 各 **1 failed | 4 skipped (5)**，四例红点亲读均为**预期断言**（T7① `toContain '未推进'` 收到旧成功文案；T7② `toContain '服务端步骤同步失败'`；T7③ `toContain '无法切换'` 收到「已发起下载」；T7④ `toContain 'selectDefs 注入失败'` 收到表格未就绪文案）——非 import/环境错；四例均未变绿 ⇒ D14「锁本身有问题」推翻条件未触发；变异恢复后 `.bak` 与工作树逐字节一致（§③）✔。变异点四处（`:1192`/`:1201`/`:1169`/`:1284` 恒假变异）与报告 §2 表一致 |
| **A17 / D15** 导入侧结构化 | **落实** | 【实测】 | `ImportWizardView.vue:714-745`：`StepAdvanceOutcome` 接口（ok/reason/syncWarning）+ `goStep` 返回结构化 + 越界分支 `{ok:false, reason:'目标步骤…不在导入向导范围内'}` + flushEditors 失败 `{ok:false, reason:…}` ✔；`:1041-1050` handler：`advanced.ok` 判定 + `advanced.syncWarning` 带进回灌（对齐导出 `:1202-1203` 口径）✔ |
| **A21** 行号订正三处 | **落实** | 【实测】 | `parts.ts:250` = `frontendResultStatus` 函数签名行（export 起始；闭区间终点订正属实）✔；`types/ai.ts:341` = `messages: Array<Pick<ChatMessage, 'id'|'role'|'content'|'parts'|'reasoning'|'toolRuns'|'pendingCall'>>`（Pick 不含 done）✔；`ImportWizardView.vue:1057` = `import.selectDefs` 只读守卫降级/指引形 ✔ |
| **A1 / A4**（批 4④）未施工且已登记 | **核销** | 【实测】 | patch 内 `AiPanel.vue` 仅死码删除与注释（无模板合并、无 A4 槽位）；报告 §8-1（R-140①-1 移交批 4④，本批仅登记）+ §7-7(d) ✔；「模板合并未做，挂起卡与工具卡仍并列渲染」在报告总述与 §3-S3（`:179-288` 引证）如实表述 ✔ |
| **A18 / A19 / A20**（M7/M8/M9 移出）未施工且已登记 | **核销** | 【实测】 | patch 十文件清单中无 `frontend-tools.ts`（E19 红线未破）；ExportWizard 改动仅 D11（+3/-4）——`startExport` `:708` persistStepData 丢弃、goStep in-flight 门均未动；报告 §8-2 逐条登记（A18/A19/A20 落点行号 + runSeq 残留 + D13(A)）✔ |

---

## ⑥ D1~D16 逐条落实度

| D | 落实度 | 依据（详见 §⑤ 对应行） |
|---|---|---|
| D1(B) 只做状态机与死码 | **全落实** | 模板合并未做且如实写；`AiPanel.vue` 唯一改动 = §5 死码；批 4④ 串行风险声明在案 |
| D2(A) 先关旁路再删死码 | **全落实** | 定序 S1(D8)→S2(D5)→S3(O1 殿后) 由 patch 段内依赖（D8 守卫 hunk 先于 tag 删除落点）与 GREEN 里程碑可证；死码清单逐行号 + 两排除项 + 链收拢全部兑现 |
| D3(A) 删守卫 + 契约注释 | **全落实** | A2 行 |
| D4(A) 分处落地 | **全落实** | A6 行（loadHistory 侧 map、未收进 sanitize） |
| D5(A) 补建段 + 双兜底 + 过闸 + expired 锁 | **全落实** | A5 行（「后端必发终态帧」前提为未复验申报——D5 终裁原文如此要求） |
| D6(B) 店级守卫 + 收窄申报 | **全落实** | A7 行（落点 = switch 前，施工棒裁决①，见 §8-R1） |
| D7(B) 维持硬编码 + 注记本体 + R-144 | **全落实** | A8 行 |
| D8(B) 收窄版七要素 | **全落实** | A9/A10 行（①只拦挂起语义帧 ②case 内落点 + suspended 遍历 ③break 保记账/职责分界 ④GF 回归留证 ⑤统一 7 态 ⑥非帧路不收入（restoreSuspendedSnapshot 双查 `:753`/`:765` 亲读在位、applyFrontendToolTerminal/applyPendingTerminal 未动）⑦红线：报告无「重挂已闭合」销记） |
| D9(C) 两表 + 三对注记 + 订正 | **全落实** | A11 行 |
| D10(A) 捕获点 | **全落实** | A12 行 |
| D11(A) 单点提示 | **全落实** | A13 行 |
| D12(A) DEV 门 | **全落实** | A14 行 |
| D13(B) 脚本头 + (A) 注记 | **全落实** | A15 行 |
| D14(A) 四例反向留证 | **全落实** | A16 行 |
| D15(A) 导入侧对齐 | **全落实** | A17 行 |
| D16(A) 边界裁决 | **全落实** | A1/A4/A18/A19/A20/A22 核销行 + R-84 计数 21→22（`:1340` 补登）+ 台账 §8 逐项点名（16 项可销记 + 行尾注记 + R-144）✔ |

**R-141 四项 + R-142 + R-143 择项 + ③a §12 M6 全部承接**：R-141①②③④ / R-142 / R-143①②③④ / M6 分别由 A7/A8/A9+A10/A11 / A12 / A13/A15/A14/A16 / A17 落地。

---

## ⑦ 测试锁判别力（存在性 + 非空转 + 变异思想实验）

> 逐锁做「实现回退一处（或改判据）该用例是否真会红」的思想实验；RED 日志红点已逐一核对为预期断言。

| 锁 | 存在性 | 变异思想实验（回退实现 ⇒ 是否红） | 结论 |
|---|---|---|---|
| **(a) D8 不重执行锁** | ✔（result-terminal：succeeded 重投 / unknown 重投 / suspended 重投 failed / confirm_request 重投 cancelled 四面 + GF 回归 + 收窄面，共 6 例） | ①去掉 `frontend_tool_request` 守卫 ⇒ 重投帧再触发执行器 ⇒「执行器不被二次调用」断言 `toHaveBeenCalledTimes(1)` **红**（RED-pre 实证：got 2 times）②去掉 suspended 守卫 ⇒ requiresAction 复活 ⇒ `toMatchObject({status:'failed', requiresAction:false})` **红**（RED-pre 实证）③去掉 confirm_request 守卫 ⇒ 挂起卡复活 ⇒ `pendingCall` null 断言**红**（RED-pre 实证）。suspended 重投用例的 toolCalls 数组口径已覆盖遍历判据 | **非空转**（RED 实证 ×3） |
| **(b) 渲染面单卡锁三路** | ✔（render spec 3 新例：重投路 / 新宿主路 / 历史整替路，各 `pending-card-*` 计数 + 全消息域同 id 段数断言） | 重投路：去掉守卫 ⇒ 执行器真实收敛后段终态、无挂起卡 ⇒ 该路计数仍 0/1 **不红**（green-at-RED，施工报告如实申报）；**但其 RED 面由 result-terminal 的 D8 store 级锁承载**（同判据同数据面）；新宿主路：去掉 D6(B) 守卫 ⇒ 终态帧在新宿主建第二段 ⇒ `toHaveLength(1)` **红**（RED-pre 实证：got 2）——**该锁独立锁住 D6(B)**；历史整替路：单卡口径（见 (c)） | **新宿主路非空转（RED 实证）；重投路为特征化锁 + store 级互证——见 §8-B2 判定** |
| **(c) 历史整替断言口径** | ✔ | 断言只数卡（`segments.toHaveLength(1)` + `pending-card` 计数 0），**不锁状态**；注释明写「D7(B)：h1 的 succeeded 是载荷缺状态的占位值（R-144 承接）——本锁只数卡数」 | **口径正确**，未与 D7(B) 打架 |
| **(d) D5 翻红两锁改写 + expired 锁** | ✔（parts-reducer :184 改写 / parts-residue-cleanup :465 改写 / :504 新增生命周期锁） | ①回退 createIfMissing=false ⇒ 建段断言（`toHaveLength(3)` / `some(...)=true` / `toMatchObject status:'pending'`）**红**（RED-pre 实证 ×2）②回退双兜底（去掉 done 帧 expireResidue 调用）⇒ expired 锁（`toMatchObject status:'expired'`）**红**（RED-pre 实证：`[] to have length 1`——段未建即红，连带锁建段）③RED-pre 11 红 = 3 D5 面 + 4 D8 + 1 D10 + 1 D12 + 1 D11 + 1 D6 新宿主 = **11 对账吻合** | **非空转（RED 实证 ×3）** |
| **(e) 既有 110 例不红回归门** | ✔ | 施工后 125 = 110 既有 + 15 增量。增量核算【实测】：result-terminal +10（grep `it(` 从 20→30）、render +3（4→7）、parts-reducer +0 例（改写 1 例名 + 断言）、parts-residue-cleanup +1（21→22，新增 expired 生命周期锁；写点③为改写）、export-wizard +1（4→5，D11 锁）= **3+10+1+1 = 15** ✔；除 (d) 两改写外既有例全绿（125/125） | **成立** |
| **补强① D10 轮次失配锁** | ✔ | 去掉 `:1883` 比对（回退） ⇒ 晚到结果覆盖 running 段 + POST 发出 ⇒ `toMatchObject({status:'running'})` + `frontendToolResult not called` **红**（RED-pre 实证：got `status:'succeeded'` 即段被覆盖） | **非空转（RED 实证）** |
| **补强② D11 单点弹锁** | ✔ | 恢复 syncStepToBackend 自弹 ⇒ `ElMessage.error not called` **红**（RED-pre 实证：called 1 times） | **非空转（RED 实证）** |
| **补强③ D12 DEV 门锁** | ✔ | 去掉 DEV 门 ⇒ DEV=false 下 `spy not called` **红**（RED-pre 实证：called 1 times） | **非空转（RED 实证）** |
| **补强④ D9 三对一致性锁** | ✔（2 例） | ①任一枚举漂移（如 localTerminal 少收 unknown）⇒ 行为枚举 partSide/storeSide 集合不再相等 ⇒ **红**；②任一翻译表改映射 ⇒ SHARED 逐对断言**红**；SUCCEEDED 分叉显式登记（`toBeNull()`）——若响应体表未来收录 SUCCEEDED 该断言**红**，强制重议注记 | **非空转（判据为逐值行为枚举 + 逐对断言，非形式断言）** |

**GF 回归锁 / 收窄面锁**（green-at-RED 特征化锁）：GF 回归锁在去前缀前后的行为均绿（前缀去除 = 放宽、GF 仍在守卫内）——其价值是**防未来回归**（若有人把守卫改回 GF 前缀 ⇒ 通用工具重投不再被拦 ⇒ (a) 锁红；若有人误删守卫 ⇒ GF 回归锁红），存在性成立、与 (a) 互补，非空转设计。

---

## ⑧ 施工报告裁决 R1~R6 + 偏差 B1~B5 + CDP 截图判定

> 报告实读 §9 = **6 条裁决**、§10 = **5 条偏差**（与派棒口径「4 项裁决」不符——以报告原文为准逐条判定，计数差异 = 报告比派棒口径多列 R5/R6 两条，均有实质内容，非凑数）。

### 裁决（每条：接受/打回 + 理由 + 证据）

| # | 裁决 | 判定 | 理由与证据 |
|---|---|---|---|
| R1 | D6(B) 落点 = handleFrame **switch 前**四型跨宿主守卫 | **接受** | 卡面 D6(B) 只说「店级守卫」未指定落点；switch 前落点是**同时满足**「终态帧 case 一字不动（S1 红线）」与「拦跨宿主已决终帧」的唯一低损位置——落 case 内则须动终态帧 case（破红线），落 handleFrame 入口统拦则吞副作用（X1 反例）。条件亲读恰为「四型 + 已决残留 + 当前宿主无段」三合取（`ai.ts:1569-1574`），当前宿主已持段的首轮收敛/first-wins 不受影响（`!findToolPart` 条件）。错价论证诚实：UUID 前提下跨消息已决终帧无正当来源（误杀≈0）；前提破裂的劣化 = 漏写一张卡 ≤ 施工前现状。RED 轮新宿主路实证（got 2）支撑必要性 |
| R2 | 为 D9 一致性锁 export `frontendResultStatus` / `frameStatusToPendingStatus` | **接受** | 亲读：两函数仅加 `export` 关键字 + 注记（patch 段 2/3），函数体零改动；**生产调用点不变**（`frameStatusToPendingStatus` 仍只有 `ai.ts:752`/`:1204` 两处，`frontendResultStatus` 仍只有 `parts.ts:405` 一处——grep 实证）。替代法（测 localTerminal 与 store 枚举的行为耦合）确实存在但正是 D9 锁第一例的形态——两例合璧已覆盖；export 扩大是注解级 API 面变化，代价已申报。属可接受的等价扩大 |
| R3 | suspended「遍历」口径 = 判据钉在写面条目（find 首个 CONFIRM/FRONTEND）的 toolCallId | **接受（附观察）** | 代码事实：suspended 帧（parts.ts `:427` 与 ai.ts `:1686`）**只写 find 出的那一个条目**——守卫判同一 find 结果即覆盖**全部写面**，语义完备（不写的条目无须拦）。施工棒自述「首个已决+后续未决 ⇒ 后续条目不建段，其后 confirm_request/frontend_tool_request 帧仍会建」【推断成立】：后端帧序契约 = suspended 先发、随后每工具一帧（SpToolCallingManager 实发序，卡 §10-44 亲证），故未决条目必随后续单工具帧建段，语义不丢。**观察（O 级）**：若后端未来在单条 suspended 帧内并列多个 CONFIRM/FRONTEND 条目（现协议未见），只有首个进入写面——D8 终裁推翻条件第 5 条（多条目须逐条目粒度）已预置该重议点，无需本批处理 |
| R4 | D10 测试形态 = 「重挂换句柄」制造失配 | **接受** | 判据本体 = `currentAbort !== controllerAtStart`（身份比对），与制造方式无关：reattachActive 首个 await 前同步换句柄（`ai.ts:1062` 区闭包）与 stop() 触发 finally 复位（`:935` 实读 `currentAbort = null`）在判据上**同效**（两者都改 currentAbort 身份）。回退变异（去掉 `:1883` 比对）⇒ 该锁红（RED-pre 实证），判别力成立。stop() 窗口残留属卡已声明的已知代价（§8.4-4），不须额外造锁 |
| R5 | 导入侧越界分支一并结构化（D15 未点名） | **接受** | D15 终裁 = 「对齐导出侧」；导出侧越界分支本就返回 `{ok:false, reason}`（同族语义），导入侧 boolean→结构化升级若独留越界分支返回旧形则接口内型不齐（TS 须联合类型或丢 reason）。改动 = 迁移既有语义至新形（`{ok:false, reason:'目标步骤…不在导入向导范围内'}`），handler 消费方式（`advanced.ok` 判定）不变——属「完成 D15 所必需的最小完备化」，非范围扩张。准许范围内等价偏差 |
| R6 | D11 锁只落导出侧 | **接受（附登记要求）** | 事实核实【实测】：`ls frontend/tests/` 11 spec 无 ImportWizard 测试文件（清单见 §④ 前文）；施工卡 §9.1 测试清单亦未列导入侧。导入侧行为由 typecheck（`StepAdvanceOutcome` 型齐）+ 与导出侧**代码对称性**（同一 `syncStepToBackend`/`goStep` 范式复制）保障。可接受；**登记**：导入侧弹数无测试锁，若批 4④ 或后续批触 ImportWizardView 建议顺手补最小锁（非本批义务） |

### 偏差（每条：接受/打回）

| # | 偏差 | 判定 | 理由 |
|---|---|---|---|
| B1 | D10 锁落点：卡列 `frontend-tool-outcome.spec.ts`，实落 `frontend-tool-result-terminal.spec.ts` | **接受（等价偏差成立）** | 【实测】`frontend-tool-outcome.spec.ts` 存在（9 it）但只 mock `@/router`、**无 `@/api/ai` mock**（grep 实证仅 1 处 vi.mock）——D10 锁须断言 `api.frontendToolResult` POST 不发，outcome spec 无该环境；result-terminal 已有完整 api hoisted mock + 本批新增 executeFrontendTool 桩（patch 段 8）。同 ③a「等价偏差」先例，语义不变 |
| B2 | 重投路渲染锁 green-at-RED（RED 面由 result-terminal D8 锁承载） | **接受（附条件登记）** | 分析：重投路渲染锁断言「无挂起卡 + 同 id 一段」，在旧代码上真实执行器快速收敛后同样成立（旧代码的重投副作用是**二次执行 + 段改回 pending 后再收敛**，终态观测点时已收敛）⇒ 该锁自身在 RED 轮绿属实——施工报告**如实申报**而非掩饰。非空转性的支撑：①同一行为的 store 级判据（执行器计数、段 status 时序）在 result-terminal D8 锁有 RED 实证；②渲染锁的价值 = 防「store 对但渲染层漏挂起卡」的投影面回归（若 deriveProjections/模板回归 ⇒ 计数断言红）。两处互证成立。**登记**：该锁对「重投」这一行为本身是特征化锁，其 RED 面非自身承载——后续批次若单独动渲染层，此互证链须重估（非本批缺陷） |
| B3 | `scripts/m2t2a-smoke.mjs` 断言③b 按设计不再成立（只登记未改） | **接受** | 【实测】grep 确认该脚本 `:299` 断言 `cancelledTags === 2` 依赖被删的 `tag-form-cancelled` 旁路（`:53` TESTID 常量引用已无 DOM 对应物）；脚本不在 `yarn test`（vitest 只跑 `tests/*.spec.ts`）与 `verify-e2e.ps1` 链路内（ps1 无 m2t2a 引用）。红线「不动未授权文件」⇒ 仅登记正确。**要求**：须写入台账/批 4④ 承接——报告 §10-3 已给「随批 4④ 改写或退役」建议，但**未入 §8 台账销记表正文**——已在下方 O-2 登记为台账补登项（提交官办理） |
| B4 | E2E 首轮空跑（未起栈 27 例空值失败） | **接受** | 申报充分（§10-4 明示首轮未起栈、以 `E2E-stub-final.log` 28-0 为准）；本棒复现亦经历起栈过程（且额外踩了 batch-size 参数坑），最终 28-0 与案卷一致。非产品缺陷 |
| B5 | 终止 5202 既有进程（PID 3804/8400） | **接受** | 本棒独立评估：该进程为施工棒自己 CDP 轮启动的同仓 vite（本棒查证时 PID 已换为 8400、StartTime 02:21:59——系机器上同一条遗留链），服务于受审仓库 frontend；D13(B) 明文要求复跑前重启 dev server（裸路径探针对 HMR 换版脆弱 ⇒ 不重启可能假红）。两害相权（终止可能属他棒的进程 vs 假红污染复核结论）：该进程**先于本复核轮**且 9345/CDP 无活动迹象，判定为施工遗留。处置正确且已在报告 §10-5 申报「如属其他在飞会话请知悉」。本棒照做并留证。**若**当时有并行红队棒正在用 5202 则应先探测——本棒复核时段内未观测到占用冲突 |
| **附加** | CDP 两截图 md5 完全相同 | **接受（命名易误读，登记改名建议）** | **根因亲证**：`batch3b-lifecycle-smoke.mjs:176` `shotBaseline` 取在重投断言②~⑤**之后**、`:191` `shotFinal` 取在 suspended 重投断言⑥⑦之后——两次截图之间仅发生**被闸拦截的 suspended 重投**（UI 无变化：单卡 + 0 挂起卡不变）⇒ 字节相同**逻辑自洽**，非造假。**本棒自跑复现同现象**（两图 md5 均 `bce86436…`）——独立佐证。判定：①「baseline」命名易误读为「执行前」——**可接受但须登记**（见 O-3：建议后续批次改名「重投后」或补真执行前截图）；②截图**有效**作为底线条目「重投后仍只有一张卡」的可视证据：截图时刻 = 重投断言全过之后，画面内容（仅一张 `batch3b.probe` 卡、tag「前端工具/已成功」、无挂起卡——本棒自产截图目检同构）正是该断言要证的状态 |

---

## ⑨ 问题清单（B/M/m/O 分级）

**BLOCKER = 0 / MAJOR = 0 / MINOR = 0 / Observation = 4**

| # | 级 | 问题 | 锚点/证据 | 建议 |
|---|---|---|---|---|
| O-1 | O | D6(B) 守卫对 `tool_start` 的拦截是「终态帧」语义的延伸（起始帧非终态）——卡面终裁条件原文「四型：tool_start/tool_result/frontend_tool_result/confirm_decision」已含 tool_start，施工照卡面落地无偏差；但 tool_start 拦截依赖「已决 ⇒ 不该再有 start」契约，若后端未来对同 id 有合法 start 重发（如重试），该帧会被吞。此为契约前提⑥的既有边界，非本批缺陷 | `ai.ts:1569-1574`；报告 §6 契约前提三条 | 维持现状（卡面钉死）；后端契约变更时按 D8 推翻条件③重议 |
| O-2 | O | B3（m2t2a-smoke 受影响）仅在报告 §10-3 申报，未入 §8 台账销记表正文——台账完整性小缺口 | 施工报告 §8 表 vs §10-3 | 提交官把「m2t2a-smoke.mjs 断言③b 随 A3 删除失效、随批 4④ 改写或退役」补入台账登记 |
| O-3 | O | CDP 截图命名 `baseline`/`final` 语义相同（两图同为「重投后」态）——命名易误读为前后对比 | `批3b-evidence/cdp/` 两 png 同 md5；脚本 `:176`/`:191` | 后续批次复用该脚本时改名（如 `-重投后.png` 单张）或补一张真正执行前截图；不影响本批证据效力 |
| O-4 | O | B2 重投路渲染锁的 RED 面依赖 result-terminal 互证链——若后续批次单独动渲染层（批 4④ 模板合并正会如此），该互证链须重估 | §8-B2 分析 | 批 4④ 施工卡须把「重投路渲染锁改为自承 RED」列入其测试锁要求 |

**未发现问题清单级缺陷的正面声明**：锚定一致性、五连数字、判据边界、禁语（「A7 已闭合」/「重挂已闭合」全案卷检索无）、D8 闸拦截面（终态帧 case 零改动）、死码排除项与链结构、D7(B) 注记逐字、RED 红点真实性——均亲验通过。

---

## ⑩ 总结论

# **PASS**

**依据**：
1. **锚定核验全部通过**（SHA/字节/段数/状态/指纹 10/10/生成器复现/保真 10/10/`.bak` 交叉；「无全量 diff 快照」局限已如实申报，三重替代链自洽）；
2. **独立复现五连全绿**（typecheck 0 错 / 125 tests 11 files / build 绿 / E2E 桩模式 28-0 + GFSKIP=0 + Q8①② + e2e_exit=0 / CDP 10/10 PASS 含底线项，两次配方性失败均定位为复现者环境/参数问题并修正，与产品无关）；
3. **A2/A3/A5~A17/A21 全部落实**【实测】，A1/A4/A18/A19/A20 未施工且已登记核销；D1~D16 十六项终裁全部落实；
4. **测试锁 (a)~(e) + 补强①~④ 存在且非空转**（RED-pre 11 红逐点核对为预期断言；新宿主路/expired/D10/D11/D12 均有 RED 实证；重投路渲染锁为特征化锁 + 互证链，已申报并登记 O-4）；
5. **裁决 R1~R6 全部接受、偏差 B1~B5 全部接受**、CDP 截图同 md5 根因自洽且证据有效（命名建议登记 O-3）；
6. **零 BLOCKER / 零 MAJOR / 零 MINOR**，4 条 Observation 均为登记性建议（台账补登 / 命名 / 后续批次注意事项），不构成本批返工条件。

**移交后续批次的登记项**（均已在施工报告 §7/§8 申报，本复核确认申报如实）：未决双卡残余（R-141① 行尾）、R-144（后端历史携带状态）、R-84 同族 22 处、M7/M8/M9、runSeq 跨会话残留、m2t2a-smoke 退役（O-2 补登）、D5「后端必发终态帧」前提未复验、D8 契约前提三条。

---

## 附：本棒复核操作时间线（供审计）

| 时间（本地 +08:00） | 操作 |
|---|---|
| 02:30–02:32 | §1 锚定核验（SHA/status/指纹/生成器/保真/`.bak`） |
| 02:33–02:38 | 五连①②③（后台串行）；patch 全文 + 决策卡 + 施工卡 + 施工报告亲读 |
| 02:39–02:41 | E2E 起栈（stub 18399 + 后端 18330；首启 8081 修正、二启带 batch-size） |
| 02:41–02:45 | E2E 三跑 28-0；期间完成 §4/§5/§6 静态核验（grep/亲读/证据对账） |
| 02:45–02:46 | D13(B)：停 vite 8400 → 全新启动（439ms）→ CDP 冒烟 10/10 → 自产截图核验 |
| 02:46–02:50 | 清理：停 vite/后端/stub（按 PID）、清 stub-dump/临时目录、终态 `git status` 复核（10 ` M ` 未变） |
| 02:50–03:00 | 复核报告落盘 |

（报告完）
