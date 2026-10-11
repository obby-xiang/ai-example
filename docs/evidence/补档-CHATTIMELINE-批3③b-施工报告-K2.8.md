> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③b 施工报告，署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改；入库前经提交官按复核 / 红队 7 条件 + 3 项 minor 办理文书层修订：§6 补 D6(B) return 形态申报与「suspended 先发」契约前提、§3 死码口径区分（terminal 不可达 / FRONTEND pending 瞬态可达）与引用域限定「代码/测试域」、§3-S4 记 D9 注记相对锚收口修、§5 CDP 截图命名注记、§7 补列 appliedSeqs 跨宿主失效、§8 补 m2t2a-smoke 断言失效台账项）

# 施工报告 · 批 3' ③b（一卡全生命周期：段状态机收口 + 死亡代码清除）

| 项 | 值 |
|---|---|
| 施工依据 | 《批3'③b-施工卡.md》（定稿，K3 代签 DC-19） |
| 仓库 | `<MAIN_REPO>`（分支 `main`，起手 HEAD `505f6d4`，`git status` 空） |
| 施工执行 | **Kimi K2.8 Preview**（CU 直发会话 `session_47442361-5ef8-4cd5-b19c-de2fc878f8c1`，wire `modelAlias=kimi-code/kimi-for-coding` 实证；inline 施工，机械活零下沉 —— 锚点与关键结论全部施工棒本体亲读） |
| 授权来源 | **K3 指挥官批 3' ③b 施工卡**（`决策卡-批3③b.md`，K3 代签，2026-10-12） |
| 纪律自证 | **零 git 写**（未提交，工作区 10 文件改动留给提交官）；未读任何 `.env`；未触后端；**未触 `utils/frontend-tools.ts`** |
| 证据目录 | `<SCRATCH>/chat-timeline-pkg\批3b-evidence\`（RED/GREEN/E2E/CDP/BackendLog 全量日志 + CDP 截图 2 张） |
| 改动文件 | 源码 5：`stores/ai.ts`、`utils/parts.ts`、`AiPanel.vue`（仅 §5 死码删除）、`ImportWizardView.vue`、`ExportWizardView.vue`；测试 5：`parts-reducer`、`parts-residue-cleanup`（改写+扩展）、`frontend-tool-result-terminal`、`ai-panel-parts-render`、`export-wizard-step-outcome`（扩展） |

**总结论**：A2/A3/A5/A6/A7/A8/A9/A10/A11/A12/A13/A14/A15/A16/A17/A21 全部按终裁落地。`yarn typecheck` 0 错；`yarn test` **125/125（11 个 spec）全绿**；`yarn build` 绿；E2E 桩模式 **PASS=28 FAIL=0**（GFSKIP=0，Q8①② PASS）；CDP 冒烟 **10/10 PASS**（底线项「重投后仍只有一张卡」截图留证）。11 个新/改锁全部 RED→GREEN 双向留证；T7①②③④ 全部有「夹具就位仍红」的反向留证。

---

## §1 锚点对比表（S0 施工棒亲读；基线 = 施工卡 §10，全部 PASS）

| # | 锚点（卡 §10） | 结论 | 实读结果 |
|---|---|---|---|
| 1 | `ai.ts:225-227` sanitize 守卫 | **PASS** | 守卫 `:225-227` 实读如卡；函数 `:222-240`；唯一调用点 `:601` |
| 2 | `ai.ts:290-298` writeMirror 7 字段 | **PASS** | `:290-298` 无 `done`；`:289` appliedSeqs 不入镜像注释实存 |
| 3 | `ai.ts:593-597`/`:601` 归一+调用 | **PASS** | 同值命中 |
| 4 | `ai.ts:646-678`/`:658`/`:659` 整替硬编码 | **PASS** | 同值命中；`:683` restoreSuspendedSnapshot |
| 5 | `ai.ts:340-364` + `:347-349` 注释 | **PASS** | CANCELLED 三行依据注释实存，**无 SUCCEEDED** |
| 6 | `ai.ts:388-392` 7 态判据 | **PASS** | 7 态实读如卡 |
| 7 | `ai.ts:1210-1219` hasDecidedPendingResidue | **PASS** | 跨消息 `messages.some` 实读如卡 |
| 8 | `ai.ts:723-731` 非 PENDING 收敛 | **PASS** | `:728` 守卫命中 |
| 9 | `ai.ts:736-767` 重建路 | **PASS** | `:748-752` 宿主优先、`:760-767` upsert pending+requiresAction |
| 10 | `ai.ts:811/883/1037/1428` 四闭包 | **PASS（4/4）** | `:811`/`:883`/`:1037` = AbortController 身份；`:1428` = sessionId+messages 判定 |
| 11 | `ai.ts:929-943` send finally | **PASS** | `:933-936` 仅复位轮次级状态；`:938-942` 只标 done；**`:935` `currentAbort = null`**（D10 判据依据，亲读补登） |
| 12 | `ai.ts:1022/1023/1035/1046` re-attach | **PASS** | 同值命中 |
| 13 | `ai.ts:1509-1516` seq 记账 | **PASS** | 同值命中 |
| 14 | `ai.ts:1573-1578` confirm_request | **PASS** | `:1573-1579`，**实读结论与卡一致：无 residue 守卫**（③b 已补，见 §3-S1） |
| 15 | `ai.ts:1587-1606` frontend_tool_request | **PASS** | `:1588` GF 前缀守卫、`:1605` 直跑点实读如卡 |
| 16 | `ai.ts:1608-1626` 终态帧副作用 | **PASS** | `:1617-1625` activeForm 收敛、`:1626` suspended=false |
| 17 | `ai.ts:1629-1645` suspended case | **PASS** | `:1633` 复数数组 find、`:1635` GF 前缀守卫实读如卡 |
| 18 | `ai.ts:1661/1678` expireResidue | **PASS** | done/error 分支命中 |
| 19 | `ai.ts:1697-1699` markApplied + `:1701-1703` | **PASS** | switch 之后、break 帧照记 |
| 20 | `ai.ts:1713-1728` / `:1749-1774` | **PASS** | 覆盖域排除 unknown/failed（`:1720-1722`）；通用 FRONTEND 只收 requiresAction |
| 21 | `ai.ts:1800-1842` runFrontendTool | **PASS** | `:1816-1821` 无守卫 await 实读如卡；`stop()` `:1332-1357` 只 abort 不复位句柄（亲读补登） |
| 22 | `ai.ts:1376-1437` resetSession 未清 runSeq | **PASS** | 全段无 runSeq 清理 |
| 23–31 | parts.ts `:41`/`:77-103`/`:106-127`/`:129-139`/`:196-205`/`:232-250`/`:359-372`/`:383-386`/`:402-416` | **PASS** | 全部同值命中（`:250` 函数闭合订正确认） |
| 32–34 | `types/sse.ts:219-226` / `types/ai.ts:133-150` / `:341` | **PASS** | suspended 无单数 toolCallId；历史载荷无状态字段；Pick 不含 done |
| 35–40 | AiPanel.vue 死码/活分支/两卡并列 | **PASS** | `:206`/`:250-253`/`:264-277`/`:280-286` 死码；`:213-232`/`:254-256` 活分支；`:258-260` A4 |
| 41 | `frontend-tools.ts:908-933`/`:921-925` | **PASS（只读）** | 红线：本批零改动 |
| 42–43 | 两向导锚点（A13/A17/A21 + 11 处） | **PASS** | 双弹点 `:624`/`:579`/`:773`/`:731` 同值；`goStep(` 导出 5/5、导入 4/4（`:714`/`:780`/`:788`/`:1028`） |
| 44 | `SpToolCallingManager.java:163/169` | **PASS** | `:163` suspended 先发、`:169` checkCancelled（③a 落稿订正沿用了 `:169`，实质结论不变） |
| 45–50 | 两锁/ mock 范式 / 20 例 / 110 例 / 5202 / 台账行 | **PASS** | 全中；基线实测 **110/110 绿**（`GREEN-final.log` 前基线，RED-pre 前同口径） |

**grep 复核**：`hasDecidedPendingResidue` 调用点 4（`:728`/`:740`/`:1588`/`:1635`）✔；`runSeq` 记账点 4（`:444`/`:800`/`:1022`/`:1514`）✔。

---

## §2 各锁 RED/GREEN 双向留证

**RED 前置一轮（`RED-pre.log`，125 例中 11 红 114 绿）**：

| 锁 | RED 证据 | GREEN 证据 |
|---|---|---|
| §6(d)① `parts-reducer.spec.ts:184` 改写（suspended 建段） | × 同用例（改写后即红） | GREEN-s4.log ✓ |
| §6(d)② `parts-residue-cleanup.spec.ts:465` 改写（写点③建段） | × 同用例 | GREEN-s4.log ✓ |
| §6(d)新增「suspended 建段→done⇒expired」 | × 生命周期锁 | GREEN-s4.log ✓ |
| §6(a) D8 不重执行锁（succeeded 重投） | × 执行器被二次调用 | GREEN-s4.log ✓ |
| §6(a) D8（unknown 重投，③a 已知代价面） | × 未拦 | GREEN-s4.log ✓ |
| §6(a) D8 suspended 重投（failed 段 rogue 面） | × requiresAction 复活 | GREEN-s4.log ✓ |
| §6(a) D8 confirm_request 重投 | × 挂起卡复活 | GREEN-s4.log ✓ |
| 补强① D10 轮次失配锁 | × 段被覆盖+POST 发出 | GREEN-s4.log ✓ |
| 补强③ D12 DEV 门锁 | × 生产形态仍 console.error | GREEN-s4.log ✓ |
| §6(b) D6/D8 新宿主路渲染锁 | × **回放建出第二张卡**（终态帧跨宿主写段实证） | GREEN-s4.log ✓ |
| 补强② D11 单点弹锁（T7②扩展） | × error+warning 双弹 | GREEN-s4.log ✓ |

GREEN 侧说明：§6(a) 的 GF 回归锁、收窄面锁与 §6(b) 重投路/历史整替路为特征化锁，RED 轮即绿（施工前后行为不变/该路不受本批改动），如实申报。重投路渲染锁在 RED 轮因真实执行器快速收敛而绿 —— 该锁的 RED 面由 result-terminal 的 D8 用例承载（同一行为的 store 级留证），两处互证。

**§6(d) 两锁「改写前红/改写后绿」口径**：两条旧锁的改写前绿由基线 110/110 运行留证（起手实测）；改写后新断言在 RED-pre 轮即红（对旧代码），S2 施工后绿 —— 双向闭环。

**D14 反向留证（T7 全四例，夹具就位仍红）**：对 `ExportWizardView.vue` 逐处做「恒假变异」复现旧撒谎行为，单测夹具全就位，四例全部 RED（`RED-T7-1/2/3/4.log`），变异恢复后 5/5 绿 + `diff` 逐字核验恢复：

| 例 | 变异点 | RED 内容 |
|---|---|---|
| T7① | `export.nextStep` 的 `if (!advanced.ok)` → `if (false)`（:1192） | 回灌不再含「未推进」 |
| T7② | `if (advanced.syncWarning)` → `if (false)`（:1201） | 回灌不再含同步失败警告 |
| T7③ | preflight 的 `if (!advanced.ok)` → `if (false)`（:1169） | 照发起下载（downloadBlob 被调） |
| T7④ | openEditor 的 `if (!advanced.ok)` → `if (false)`（:1284） | 结局不再 failed（selectDefs 失败被吞） |

四例均**未变绿** ⇒ D14 的「锁本身有问题」推翻条件未触发，四例断言全部成立。

---

## §3 施工内容（按定序 S1→S2→S3，S4/S5 殿后）

**S1 · D8 闸（关旁路）** — `ai.ts` `handleFrame`：
- `confirm_request` case 补已决守卫（原无守卫，卡 §10-14 亲证）；
- `frontend_tool_request` 守卫**去 GF 前缀**（判据统一 7 态）；
- `suspended` case 守卫去 GF 前缀；**遍历口径**写明：帧无单数 `toolCallId` 字段（`types/sse.ts`），判据钉在写面条目（parts.ts 同口径 find 首个 CONFIRM/FRONTEND）的 `toolCallId` 上；
- 实现取 `break`：**seq 记账（switch 前 `:1510-1516`）与 `markApplied`（switch 后统一收口）照常** —— 即"被拦帧不走语义写、但照记账"；**职责分界**：语义再投以**闸**兜底，seq 幂等只管同序号重复投递，两者互补不互替。
- 终态帧 case **一字未动**（③a E17 回归锁 20 例全绿可证）。

**S1 · D6(B) 店级守卫（A7 已决面的实际落点 —— 施工棒裁决①）**：`handleFrame` switch 前新增跨宿主已决终帧守卫 —— `tool_start`/`tool_result`/`frontend_tool_result`/`confirm_decision` 四型，若 `hasDecidedPendingResidue(toolCallId)`（已跨消息、7 态）且**当前宿主消息没有该 id 的段**，整帧跳过。RED 轮的「新宿主路」实证了不拦它则回放终态帧在新宿主建出第二张卡（旧卡 unknown + 新卡 succeeded = 状态矛盾）；当前宿主已持段的正常收敛（含 T1 生死线 first-wins）不受影响。**只闭合已决面**；未决双卡残余如实申报（§7-1）。

**S2 · D5 建段** — `parts.ts` suspended 支：`createIfMissing=true` + `status:'pending'`（占位段 requiresAction=true）；**建段前过闸**由 S1 的 suspended 守卫承担（`hasDecidedPendingResidue` 天然跨消息 ⇒ 他消息已决不建，防跨消息双段）；生命周期双兜底（轮终态 `expireResidueToolParts` + 快照非 PENDING 收敛）。store 侧 `upsertToolPart` 注释同步（"suspended 帧不再用 createIfMissing=false 形态"）。

**S3 · O1 死码殿后** — `AiPanel.vue` 按 §5 逐行号删除：`:206`（自动 tag）、`:250-253`（GF 终态 tag 列，含 `data-testid="tag-form-cancelled"`）、`:264-277`（通用 FRONTEND 8 tag 列）、`:280-286`（CONFIRM 终态 tag 列）；**排除 `:213-232` 与 `:254-256`**；`:254` 链头由 `v-else-if` 收拢为 `v-if`（无悬空链）；`:264`/`:280` 整块删除后 `:234` 的 v-else-if 链自然闭合。删前 **代码 / 测试域** grep：`frontend/tests/` 零引用；**可执行代码域**仅 `scripts/m2t2a-smoke.mjs`（`:53` TESTID 常量 / `:299` 断言）引用该 testid（见 §10 偏差③）；**`docs/evidence/` 下另有 3 份历史证据文档引用**（`M2-T2b-施工与验证-K2.8.md` ×2、`补档-CHATPANEL-施工报告-苞B.md` ×1、`补档-CHATPANEL-施工卡.md` ×1，均**非可执行代码**、不受删除影响）⇒ 原文「仓内仅 … 引用」**未限定域，按 DS Pro O-6 口径订正为「代码/测试域」**。
- **死码口径精确化（DS Pro M-2 办理）**：删除的四块须分两类 —— **终态 tag 列不可达（确证）**：`running`/`rejected`/`expired`/`cancelled`/`succeeded`/`blocked`/`approved` 七态 tag 依赖 `requiresAction=true` 的挂起投影，而**终态 ⇒ `requiresAction=false` 为不变式**（D8 关旁路后无写点可再置回 `true`）⇒ 确不可达；**但 FRONTEND `pending`「待浏览器自动执行」tag 可达** —— 首次投递的 FRONTEND 工具在 `frontend_tool_request` **同步建段**（`ai.ts:1642-1654`，`:1645` `deriveProjections`）至 `void runFrontendTool(...)` 写 `running` 之间，存在 **亚 tick 瞬态** `pendingCall = FRONTEND + pending`；**D8 只拦「已决 toolCallId 重投」，与该瞬态无关**（RED 轮已证 D8 拦不住首次投递）。**后果** = 该瞬态窗口内挂起卡 body 由「待浏览器自动执行」变空；**可能不 paint**（写 `running` 与 Vue 批渲染同微任务周期），属**非功能性回归、亚 tick 级**。⇒ 原「不可达死码」表述过宽，此处按 M-2 口径订正为「**终态列不可达；`pending` 瞬态可达**」。
- **A6/D4**：`loadHistory` 镜像分支 `parts.map` 逐段清 `part.streaming`（与 `:595` 消息级归一对齐；数行非一行；不收进 sanitize —— 防职责扩大）。
- **A2/D3**：删 `sanitizeMirrorResidues` 的 `done !== true` 守卫 + 注释写明**入参契约（调用方须已归一 done）**与**反向风险**（直调镜像原始数据 ⇒ 全段跳过，不得恢复守卫）。

**S4** — A11/D9：两表 + 两枚举三对防漂移注记落代码（`frontendResultStatus` / `frameStatusToPendingStatus` / `isFrameDecidedPendingStatus` 文档区），**维持两表**；**注记自引用一律取相对锚（DS Pro M-3 / 条件③ 办理）**：初版注记引用的 `localTerminal` **绝对行号** `parts.ts:383-386` 已被 ③b **自身新增注记**下移（工作树实测 `:401`）—— 即「防漂移注记自身漂移」；**本项由提交官在入库前以注释级收口修办理**（本批唯一授权的代码级例外）：仅改 `frontend/src/utils/parts.ts` 与 `frontend/src/stores/ai.ts` 内 D9 注记 `localTerminal` **两行的措辞**，绝对行号 → 相对锚（「本文件 `frontend_tool_result` case 内」/「parts.ts 的 `frontend_tool_result` case 内」），**零行数变化、零语义变化**，改后重锚（入库前实读定位 = `parts.ts:246` / `ai.ts:389`；**代码内注记此后不再写绝对行号**，故上列定位仅供本报告阅读，不构成新的漂移面）。本行后续文字（D10/D11/D12/A8）按原样；D10：`runFrontendTool` 入口捕获 `controllerAtStart = currentAbort`，await 返回后失配 ⇒ 丢弃段写 + 回灌；D11：两向导 `syncStepToBackend` 删 `ElMessage.error`（goStep 单点 warning 保留）；D12：`assertUnknownTerminalInvariant` 补 `import.meta.env.DEV` 门；A8：D7(B) 注记正文**逐字**落入 `loadHistory` 段注释。

**S5** — A17/D15：导入侧 `goStep` 升级 `StepAdvanceOutcome`（ok/reason/syncWarning，对齐导出），`:1028` handler 回灌带警告（越界分支一并结构化，对齐导出侧同分支语义）；D14 四例反向留证；A15：冒烟脚本头写入「复跑前必须重启 dev server」纪律（`batch3a-outcome-smoke.mjs` 头部 + 新建 `batch3b-lifecycle-smoke.mjs` 同口径）。

---

## §4 验证三连 + E2E

```
yarn typecheck → 0 错
yarn test      → 11 files / 125 tests 全绿（GREEN-final.log）
yarn build     → ✓ built in 25.74s（BUILD.log）
E2E 桩模式     → PASS=28 FAIL=0，GFSKIP=0，Q8①② PASS，e2e_exit=0（E2E-stub-final.log）
```

E2E 环境（如实申报，沿 ③a §4 口径）：`verify-e2e.ps1` 自身不起栈，本棒自行起栈 —— `node scripts/ci/stub-upstream.mjs --port 18399` + 既有 jar `backend/target/config-mgr.jar`（2026-10-11 20:20 产物，**本批零后端改动故未重建**，Redis 127.0.0.1:6379 既存）。首轮因未起栈全量空值失败（E2E-stub.log），起栈后一次通过。E2E 完成后已清理桩 dump 目录 `stub-dump/`（桩默认落盘，非仓库改动）。

---

## §5 CDP 冒烟（D16 第 4 条强制覆盖）

- **配方**：`VITE_DEV_PORT=5202 VITE_API_BASE=http://localhost:18330 yarn dev`（vite `strictPort` ⇒ 显式 5202）；后端 18330（§4 同栈）；`node batch3b-lifecycle-smoke.mjs`（自带 headless Chrome，CDP 9345，不触碰用户浏览器）。
- **复跑前重启 dev server 留证**：起手发现 5202 被今日 00:00 启动的**同仓** vite 旧进程（PID 3804，先于本批全部改动）占用 —— 正是 D13(B) 的 HMR 换版风险场景；已终止旧进程并**全新启动** dev server（vite-dev.log：`VITE v6.4.4 ready in 412 ms`）后跑冒烟，无换版风险。
- **结果 10/10 PASS**（`批3b-evidence/cdp/result-*.json`）：①首轮执行一次收敛 succeeded；②**重投不二次执行（探针计数仍 1）**；③重投不改写终态；④全消息域同 id 工具段恰一张；⑤挂起卡 0 张；⑥suspended 重投 requiresAction 不复活；⑦挂起卡仍 0 张；⑧suspended 单独到达建占位段；⑨done 帧收敛 expired。
- **截图（含 ISO 时间戳）**：`cdp/20261011T182213Z-重投后仍只有一张卡-baseline.png` / `-final.png` —— 底线项「重投后仍只有一张卡」可视证据（截图内 AI 面板仅一张 `batch3b.probe` 工具卡，tag「前端工具/已成功」，无第二张、无挂起卡）。
- **注入方式**：与 ③a 脚本同配方（`$pinia` 直读 ai store + `handleFrame` 驱动真实帧处理链；探针 handler 经裸路径 `import('/src/stores/workspace.ts')` 注册）——**无偏离**。
- E7（R-140①-5 复制按钮位置）：截图中复制按钮为 hover 显形，无法静态目检 ⇒ **未销记**，不列为本批施工项（如实申报）。
- **截图命名注记（DS Pro O-10 / 条件⑦ 办理）**：`-baseline.png` 的**实取时刻在重投断言②~⑤之后**（脚本 `batch3b-lifecycle-smoke.mjs:176` `shotBaseline`），`-final.png` 在 suspended 重投断言⑥⑦之后（`:191` `shotFinal`）—— 两次截图之间仅发生**被闸拦截的 suspended 重投**（UI 无变化：单卡 + 0 挂起卡不变）⇒ **两图 md5 相同（`62564e…`；GLM 复核自产同现象 `bce86436…`，均 51516 字节）属逻辑自洽、非造假**（探针计数与段计数断言同时佐证）。**但 `baseline` 命名易被误读为「执行前」** ⇒ 本批**不改名、不补拍**（红线：仓外脚本不在本批授权改动面，且改名不改变证据效力），**移交后续批次复用该脚本时办理**（改名如 `-重投后.png` 单张，或补一张真执行前截图）；底线项「重投后仍只有一张卡」的证据效力 = **截图 + CDP result JSON 的探针/段计数断言双证**，不依赖 before/after 对比。

---

## §6 D8 契约前提四条 + 职责分界（§8.1⑥⑦）

1. **后端不重发已终态 `toolCallId`**（UUID 唯一 + 回放仅 re-attach/续收场景）——闸的正当性前提；
2. **`toolCallId` 跨轮不复用**（依据未复验，保留边界）——否则闸会误拦正当的新一轮调用；
3. **未来合法重试语义 ⇒ 闸须重议**——后端若引入对同一 toolCallId 的合法重发，D8/D6(B) 守卫需按新契约重开裁决；
4. **`suspended` 先于 `confirm_request` / 前端执行到达**（**DS Pro O-8 办理**）——实发序依据 = `SpToolCallingManager.java` **`:163` `out.suspended(...)` 先发**、**`:169` 循环内 `checkCancelled`** 为取消边界（另 `:192` 同类点、`:211` 方法定义；`:172` 系 GLM / 终裁稿引用的偏差值，实质结论不变）。本序是 **§7-add** 自报「迟到的 `suspended` 命中未决非 `pending` 段 ⇒ 段 status 被降回 `pending`」的**唯一缓释前提**：违反该序即该降级面成立（正常链路不可达）。**该前提未实跑复验**（静态实读），与 §6b 的「必发终态帧」前提**并列**、各自承担一条兜底。

职责分界（§8.1⑦）：被拦帧实现取 `break` ⇒ **seq 记账与 `markApplied` 照常**（下次同 seq 再投仍被 seq 幂等挡）；**语义再投（不同 seq）以闸兜底**，与 seq 幂等互补。

**D6(B) 取 `return` 形态（与 D8 三 case 的 `break` 形态不同；DS Pro M-1 办理）**：D6(B) 跨宿主已决终帧守卫位于 `handleFrame` **switch 之前**（判据 `ai.ts:1563-1568`，整帧 `return` 落 `:1569-1574`），故被拦帧**进入不了 switch** ⇒ **跳过** `deriveProjections`（switch 后 `:1745`）与 `markApplied`（`:1749-1751`）；**但 `runSeq` 记账（switch 前 `:1536-1541`）仍照常执行**。⇒ 台账 / 报告**不得把两种形态混同表述为「统一 break」**：**D6(B) 的幂等由守卫条件自身保证**（「已决残留 + 当前宿主无该 id 段」为幂等判据，后续同 id 终帧仍被同一条件拦），**非** seq 幂等兜底；D8 三 case 的 `break` 幂等则由「seq 记账 + `markApplied` 照常」承担。经 DS Pro ③-9②/③ 推演**无功能后果**（arming 被 D8 对称守卫 ⇒ 无对象可解除，O-4 同证），**属申报口径缺口、非实现缺陷**，本行订正。

## §6b · D5 前提申报（§8.1⑧）

D5(A) 建段后的生命周期依赖**「后端必发终态帧」**（done/error）兜底占位段 —— 该前提**未实跑复验**（静态实读 `SpToolCallingManager` 的取消竞态路径 `:163`/`:169` 推断；若后端存在永不收尾的路径，占位段将挂到下次刷新由快照/镜像收口）。

---

## §7 已知代价与残余面（§8.4 六条逐条申报 + 附加）

1. **未决双卡面（移交后续）**：旧段 `pending`/`running` + 本端断流 re-attach 新宿主回放 ⇒ 同 toolCallId 两张**未决**卡 —— 闸判终态、不拦（`parts.ts` 单消息查域 + 新建）。**不得表述「A7 已闭合」**：本批只闭合**已决**面（R-141① 行尾注记按此口径承接）。
2. **`loadHistory` 整替丢真（R-141② → R-144）**：本地 unknown/failed 被占位 `succeeded` 覆盖 —— D7(B) 注记已逐字落码，修复属后端（历史携带状态），R-144 立项。
3. **非帧路不在闸内**：409 响应体 / 续收快照走 `applyFrontendToolTerminal` / `applyPendingTerminal`，与闸语义同向但覆盖面独立（并列申报）。
4. **`stop()` 在途执行器窗口残留**：D10 只拦「await 返回晚于轮次收尾」；stop() 至本轮 finally 复位之间返回的执行器仍写段（当时仍当前轮）——窗口残留如实申报。
5. **D5 建段生命周期依赖轮终态兜底**：见 §8 占位（前提未实跑复验）。
6. **后端契约前提四条**：见 §6（含 ④` suspended` 先发序，DS Pro O-8 办理）。
**6b. `appliedSeqs` 消息级幂等跨宿主失效（DS Pro §四-6 第 3 条 / O-9 逐条点名办理）**：`appliedSeqs` 去重为**消息级**（`alreadyApplied` / `markApplied` 传 `message`，类型声明 `types/ai.ts:216`；`markApplied` 实读 `ai.ts:1749-1751`），**不入镜像**（`writeMirror` 7 字段无该项，实读 `:290-298`）⇒ 本端断流 re-attach 建**新宿主消息**后其 `appliedSeqs` 为空 ⇒ **seq 幂等跨宿主失效**。**本批的缓释 = D6(B) 跨宿主已决终帧守卫 + D8 三 case 闸**（判据落在 `toolCallId` / 已决判据而非 seq）⇒ **无新增幂等缺陷**；四象限逐一实读：同 seq 同 id → 同宿主 seq 幂等短路；同 seq 不同 id → 各自正常处理；不同 seq 同 id → 闸判已决拦截；不同 seq 不同 id → 正常。**残余两条**：①「跨宿主 + 未决」象限不在闸保护内（与 §7-1 未决双卡面同源，移交后续）；② `runSeq` 跨会话残留（`resetSession` `ai.ts:1376-1437` **未清 `runSeq`**；声明 `:444`、写点 `:800` / `:1022` / `:1514`；key = runId）⇒ 仅当后端**复用 runId** 才水位串味，**登记不修**（已落 R-143 行尾注记）。
7. **附加申报（施工棒增补）**：(a) 重投路渲染锁 green-at-RED 说明（§2）；(b) `scripts/m2t2a-smoke.mjs` 影响（§10-③，台账落行见 §8-7）；(c) `frontend/dist` 未运行时验证（本批证据走 dev 形态；需要时 `yarn preview` 复跑）；(d) **不得销记「重挂已闭合」**——A7 保护面=已决面，未决残余与 re-attach 空宿主 UX 残留仍在；(e) **D9 防漂移注记自引用改相对锚**（DS Pro M-3 办理）——注释级收口修仅动 `parts.ts:246` / `ai.ts:389` 两行的措辞（绝对行号 → 相对锚），见 §3-S4。

### §7-add · 施工观察（D5 补丁边界，登记不修）
D5 按卡面逐字落 `status:'pending'` 进 suspended 补丁 ⇒ 若**迟到的 suspended 帧**命中**未决非 pending 段**（典型：approval 后 `running` 的 CONFIRM 段 / 本地执行中 `running` 的 FRONTEND 段，二者均非终态、D8 不拦），段 status 会被降回 `pending`。可达性：违反「suspended 先于 confirm_request/执行」的帧序契约（`SpToolCallingManager` 实发序），正常链路不可达；即使到达，影响为卡面瞬时状态摆动（挂起卡短暂再现，终态帧到达后收敛）。卡面 D5 文本为终裁钉版，未擅自加「仅建段时置 status」特判；如后续批次认为需要，属 D5 的增修而非本批缺陷。

---

## §8 台账销记建议（提交官办理；逐项点名，不打包）

| # | 项 | 口径 |
|---|---|---|
| 1 | **③b 可销记（16 项）** | **R-139①**（A2+D3 删守卫+入参契约注释）；**R-139③**（A3+D2 关旁路后删死码，排除项 :213-232/:254-256 保留，链结构收拢）；**R-140①-1**（A4 **移交批 4④**——GF 取消回灌失败文案渲染位随 A1 模板合并给独立槽位，本批仅登记）；**R-140①-2**（A5+D5 建段+双兜底+expired 锁）；**R-140①-4**（A6+D4 loadHistory 段级归一）；**R-141①**（A7+D6(B) **只闭合已决面**，注记不得写「已闭合」）；**R-141②**（A8+D7(B) 维持硬编码+注记+**R-144**）；**R-141③**（A9/A10+D8 去前缀三 case 守卫，rogue 面锁消解）；**R-141④**（A11+D9 维持两表+防漂移注记+误述订正——`CANCELLED` 注释实存 `:347-349`，唯一真实分叉=SUCCEEDED）；**R-142**（A12+D10 捕获点+丢弃）；**R-143①**（A13+D11 单点弹）；**R-143②**（A15+D13 脚本头纪律+「(A) 已排查无应用内入口」）；**R-143③**（A14+D12 DEV 门）；**R-143④**（A16+D14 四例反向留证全红）；**③a §12 M6**（A17+D15 导入侧对齐，无翻红面） |
| 2 | **R-143 行尾注记追加** | **M7/M8/M9（A18/A19/A20）移出 ③b、登记不修**（落点：`Export:699-720`/`:708`、`Export:561-583`/`:566`、（A20 登记面，本批零改动））；D13(A) 已排查无应用内入口；**`runSeq` 跨会话残留**（`resetSession :1376-1437` 未清 `runSeq`，本批登记未修） |
| 3 | **R-84 行尾注记** | 补登 `ExportWizardView.vue:1340` ⇒ 同族面 **21→22**；GLM §四-5 口径（机制：`interpretHandlerOutcome` `frontend-tools.ts:974-975` 字符串折 ok；**判定词「未闭合」**；后果：只读/越界拒绝时卡面显「已成功」；去向：R-84 批结构化）；逐处行号见 §8.6 |
| 4 | **R-144 新立项** | 后端历史携带状态（使 `loadHistory` 整替能还原真实终态）——出 ③b |
| 5 | **行号订正三处** | `parts.ts:250`（frontendResultStatus 终点）/ `types/ai.ts:341`（Pick 位）/ `ImportWizardView.vue:1057`（降级形）——本批实读全部属实 |
| 6 | **R-140①-5** | 复制按钮位置目视：本批未销记（E7 截图无法静态目检，不列为施工项） |
| 7 | **`scripts/m2t2a-smoke.mjs` 断言失效入台账（红队 O-6 / GLM O-2 / 条件④）** | 该脚本断言③b（`:299` `cancelledTags === 2`；TESTID 常量 `:53`）依赖的正是 **A3 被删旁路**（suspended 给终态段补 `requiresAction` ⇒ 终态 tag 经挂起投影渲染）⇒ **D8 关旁路 + A3 删 tag 后按设计不再成立**；脚本**不在** `yarn test`（vitest 只收 `tests/*.spec.ts`）与 `verify-e2e.ps1` / CI 链路内 ⇒ **静默假绿、不会假红**；红线（不动未授权文件）⇒ **本批仅登记**，须随**批 4④ 改写或退役**落行；批 4④ 施工卡同时应把「**重投路渲染锁改为自承 RED**」（GLM O-4 互证链重估）列入测试锁要求。同批落定 `data-testid="tag-form-cancelled"` **引用域 = 代码 / 测试域**口径（仅该脚本 2 处；`docs/evidence/` 3 份历史文档非可执行代码、不受影响），见 §3-S3 订正。 |

### §8.6 同族「字符串⇒ok」谎报面逐处行号（`505f6d4` 实读，供 R-84 行尾注记直接落行）
- 导出侧 11：`:1183` / `:1188` / `:1211` / `:1227` / `:1231` / `:1238` / `:1250-1251` / `:1258` / `:1261` / `:1267` / **`:1340`（补登）**
- 导入侧 11：`:1019` / `:1024` / `:1030-1032` / `:1040` / `:1055` / `:1063` / `:1066` / `:1072` / `:1075` / `:1081` / `:1084-1086`
- 标注不计入：`ImportWizardView.vue:1057`（降级/指引形）
- 计数：**22（未闭合）**，随 R-84 批结构化；③b 不搭车（D16(A)）

---

## §9 施工棒裁决（Rulings）

1. **D6(B) 落点**（最重要裁决）：卡的 A7 施工取值只说「店级守卫」未指定落点；RED 轮「新宿主路」实证 —— 只关 D8 三 case 旁路，回放终态帧仍在新宿主建第二张状态矛盾卡（unknown+succeeded）。裁决：在 `handleFrame` **switch 前**加跨宿主已决终帧守卫（四型：tool_start/tool_result/frontend_tool_result/confirm_decision；条件=已决残留 + 当前宿主无该 id 段），**终态帧 case 一字不动**（守 S1 红线）。错价：UUID 前提下不存在正当跨消息终帧，误杀面≈0；若前提破裂则漏写一张卡（不大于施工前）。
2. **D9 测试可达性**：`frontendResultStatus`/`frameStatusToPendingStatus` 原为模块私有；为 D9 表驱动一致性锁加 `export`（纯注解级面性扩大，生产调用点不变）。错价：两个内部函数进入模块 API 面。
3. **suspended「遍历」口径**：判据钉在写面条目（find 首个 CONFIRM/FRONTEND）的 toolCallId —— parts.ts 只写该条目，守卫覆盖全部写面；`toolCalls[]` 复数数组逐条目读取，不用不存在的 `frame.toolCallId`。错价：首条已决+后续未决的组合里后续条目不建段（其后 confirm_request/request 帧仍会建，语义不丢）。
4. **D10 测试形态**：以「重挂换句柄」制造失配（stop() 的 finally 复位同效，`:935` 亲证）；stop() 单独调用不复位句柄属卡已声明的窗口残留，不额外造锁。错价：无（锁的语义=返回晚于收尾即丢弃，与制造方式无关）。
5. **导入侧越界分支结构化**：D15 未点名该分支；为对齐导出侧同分支语义（`{ok:false, reason}`）一并升级。错价：无（handler 消费方式不变）。
6. **D11 锁只落导出侧**：仓内无 ImportWizard 测试文件（卡 §9.1 测试清单亦无）；导入侧经 typecheck + 与导出侧代码对称性保障，报告如实申报。错价：导入侧弹数无测试锁（行为与导出侧同构）。

## §10 偏差逐条申报

1. **D10 锁落点偏差**：卡 §9.1 列「扩展 `frontend-tool-outcome.spec.ts`」；实际落在 `frontend-tool-result-terminal.spec.ts`（该文件已具备 api mock + 可注入 `executeFrontendTool` 桩，D10 需要两者；outcome spec 无 api mock 环境）。同 ③a「等价偏差」先例，语义不变。
2. **重投路渲染锁 green-at-RED**（§2 已述）：RED 面由 result-terminal D8 锁承载，两处互证。
3. **`scripts/m2t2a-smoke.mjs` 受影响（只读发现，未改动）**：仓内 GF 冒烟脚本的断言③b（`cancelledTags === 2`）依赖的正是 A3 被删旁路（suspended 给终态段补 requiresAction ⇒ 终态 tag 经挂起投影渲染）。D8 关旁路 + A3 删 tag 后该断言**按设计不再成立**；该脚本不在 `yarn test` 与 `verify-e2e.ps1` 链路内，属里程碑 2 时代遗留，建议随批 4④ 一并改写或退役。红线（不动未授权文件）⇒ 本批仅登记。
4. **E2E 首轮空跑**：未起栈导致 27 例空值失败（E2E-stub.log），起栈后 28-0（E2E-stub-final.log）为准 —— 非产品缺陷，申报留痕。
5. **5202 既有进程处置**：终止了今日 00:00 启动的同仓 vite 旧进程（PID 3804）以满足 D13(B) 重启纪律；如该进程属其他在飞会话，请知悉（其服务于本仓 frontend，本批期间无其他批次在飞）。

---

## §11 证据清单（`<SCRATCH>/chat-timeline-pkg\批3b-evidence\`）

| 文件 | 内容 |
|---|---|
| `RED-pre.log` | 施工前红：11 红 114 绿（125 例全量） |
| `GREEN-s4.log` / `GREEN-final.log` | S4 后 / 最终全绿 125 例 |
| `BUILD.log` | `yarn build` ✓ 25.74s |
| `RED-T7-1/2/3/4.log` | D14 四例反向留证（夹具就位仍红） |
| `ExportWizardView.vue.bak` | T7 变异前备份（恢复核验用，已 diff 一致） |
| `E2E-stub.log` / `E2E-stub-final.log` | 首轮空跑（未起栈）/ 正式 28-0 + `e2e_exit=0` |
| `stub.log` / `backend.log` / `vite-dev.log` | 桩 / 后端 / dev server 全程日志 |
| `cdp/20261011T182213Z-重投后仍只有一张卡-{baseline,final}.png` | 底线项截图 2 张（含 ISO 时间戳） |
| `cdp/result-20261011T182213Z.json` | CDP 10 项断言结果 |
| 仓外 | `batch3b-lifecycle-smoke.mjs`（新建，③b 冒烟脚本，不入库）；`batch3a-outcome-smoke.mjs` 头部加 D13(B) 纪律（仓外，不入库） |
