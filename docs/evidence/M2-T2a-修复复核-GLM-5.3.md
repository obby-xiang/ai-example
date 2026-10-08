# M2-T2a 红队修复复核（GLM-5.3）

> 角色：独立复核员。只核不改、零 git 写操作（`git pull` 同步除外）；本文档为唯一新增产物，未提交。
> 复核对象：`docs/evidence/M2-T2a-施工红队-DS-V4-Pro.md`（S1~S7）× `docs/evidence/M2-T2a-红队修复-DS-V4-Flash.md` 的逐项改动。
> 代码基线：分支 `main`，HEAD `5c2a2f6`（修复提交 `5f06555` + 证据入库 `5c2a2f6`；`git pull` 报 Already up to date，工作区干净——修复报告所写"未提交"是其落笔时状态，后随 `5f06555`/`5c2a2f6` 入库，流程正常）。
> 证据等级：**【实测】** = 本次实际读码/实跑确认；**【推断】** = 静态推演未实跑。
> 脱敏：路径相对 `<REPO_ROOT>`；仓库外验证产物目录记 `<SCRATCH>`（与修复报告同指一处）；无密钥、无用户名、无盘符路径。
> 复核日期：2026-10-08。

---

## 0. 结论速览

| 项 | 红队严重度 | 判定 | 关键证据（文件:行号） |
|---|---|---|---|
| S1 映射表漏 `CANCELLED`/`REJECTED_ARGUMENTS` | 严重 | **闭合** | `frontend/src/stores/ai.ts:176-184`、`frontend/src/types/sse.ts:30-34`、`ai.ts:669-675` |
| S2 终态保留限定 generative_form | 建议 | **闭合** | `ai.ts:1165-1186`（新方法）、`:1051-1052`（帧）、`:672-674`（409） |
| S3 守卫前移 | 建议 | **闭合** | `ai.ts:1011-1016`（前置守卫）、`:699-704`（抽函数）、`:716-719`（旧守卫移除） |
| S5 宿主候选限定待决态 | 建议 | **闭合** | `frontend/src/components/AiPanel/AiPanel.vue:416-429` |
| S6 通用 FRONTEND 分支补显式标签 | 建议 | **闭合**（附 1 低级发现 F1） | `AiPanel.vue:240-241` |
| S7 冒烟文案常量集中 | 建议 | **闭合** | `scripts/m2t2a-smoke.mjs:27-33`（常量）、`:141-142,146,176`（引用） |
| S4 断言①留 T2b（裁决留痕） | 建议 | **一致，留痕未闭环**（F5） | 决策卡 `docs/M2-T2-返修决策卡.md:69` × 修复报告 `:6,189,197` |

**独立复跑：`yarn typecheck` EXIT=0、`yarn build` EXIT=0【实测】。冒烟未复跑（非必需），修复方结果文件已逐项静态核验（§3）。**

**总结论：闭合（通过）。** 六项修复全部按红队推荐支落地、无新引入缺陷级回归；登记 2 项低级发现（F1/F5，均不阻断）+ 4 项信息级观察（F2/F3/F4/F6）。无打回项。

---

## 1. 逐项核验（闭合表展开）

### S1｜五支映射补 `CANCELLED`/`REJECTED_ARGUMENTS` —— 闭合【实测】

- **映射层**：`ai.ts:176-180` `case 'CANCELLED': return 'cancelled'`、`:181-184` `case 'REJECTED_ARGUMENTS': return 'rejected'`；函数头注释（`:166-169`）已声明"唯一翻译层必须穷举实发 status"。与红队修复方向 (a) 支一致。
- **类型联合**：`sse.ts:31` `CANCELLED` 原已在联合内（本轮只补出处注释，未重复添加——与修复报告口径一致）；`:33-34` 新增 `REJECTED_ARGUMENTS`。
- **409 收敛路径自动继承**【实测】：`ai.ts:669-675` 读响应体 `status` → 同一 `frameStatusToPendingStatus` → `applyFrontendToolTerminal`。后端 `AiController.duplicateToolCall` 直填 `pending().getStatus()`，与帧同口径，无需另改——修复报告该判断正确。
- **后端实发值核对**【实测】：`ConfirmGate.java:217`（run-cancel：`frontendToolResult(fresh, false, ...)`，条目已置 `CANCELLED`）、`SseChatEmitter.java:282`（`frame.put("status", pending.getStatus())`）、`SpToolCallingManager.java:305`（`REJECTED_ARGUMENTS`，ok=false）。两值确为真实可达的帧 status，映射必要性成立。

**对抗性检查 1：run-cancel 与 form-cancel 终态是否真的一致 —— 一致【实测】**

| 维度 | run-cancel（点"停止"） | form-cancel（点"取消"） |
|---|---|---|
| 后端条目终态 | `CANCELLED`（`ConfirmGate.java:204-218`） | `FRONTEND_CANCELLED`（`ConfirmGate.java:403-410` dismiss 路径） |
| 结局帧 | `frontend_tool_result(status='CANCELLED', ok=false)` | `frontend_tool_result(status='FRONTEND_CANCELLED', ok=false)` |
| 前端映射落点 | `cancelled`（`ai.ts:176-180`） | `cancelled`（`ai.ts:174-175`） |
| pendingCall 展示 | `AiPanel.vue:218` "表单已取消" | 同左（乐观置位 `ai.ts:801` + 帧覆盖，同落 `cancelled`） |
| toolRun 展示 | ok=false → `failed`（`ai.ts:1039-1046`） | 同左（`GenerativeFormGateTest.java:219` 佐证 dismiss 帧 ok=false） |

两路径在 pendingCall 与 toolRun 双投影上形态完全一致，红队指控的"分叉"消除。见 F2/F3 两点补充观察。

**对抗性检查 2：`REJECTED_ARGUMENTS`→`rejected` 展示语义 —— 正确【实测】**：该帧发生在"入参被 schema 安全闸拒绝、未挂起/未执行"（`SpToolCallingManager.java:306` 日志自述），generative_form 分支 `AiPanel.vue:221` 对 `rejected` 渲染"表单未提交"（warning）——"表单从未挂起/未提交"语义准确；修复方定点验证 S1-b 实测 tag 含"表单未提交"（`<SCRATCH>/verify/verify-result.json`，见 §3）。红队原文亦认可"语义同 REJECTED"。

### S2｜终态保留限定 generative_form —— 闭合【实测】

- 新方法 `ai.ts:1165-1186` `applyFrontendToolTerminal`，判定 `pending.kind !== 'FRONTEND' || pending.name !== 'generative_form'` → 置 null；两处调用点 `ai.ts:1051-1052`（帧）/ `ai.ts:672-674`（409）；`applyPendingTerminal`（`ai.ts:1147-1155`）保留，仅剩 `cancelGenerativeForm`（`ai.ts:801`）一处调用，该域本就 generative_form——与修复报告 §3.3 一致。
- **场景覆盖核查**【实测】：判定顺序为 ①非 generative_form → null（无论 status）；②generative_form 已决（非 pending/cancelled）→ 跳过（first-wins 单调）；③generative_form 在途 + `status===null` → null（旧后端口径收卡）；④在途 + 有 status → 写终态。四类场景全覆盖，且 ② 在 ③ 之前——迟到的无 status 帧不会抹掉已决终态（顺序正确，无 S2 类残留缺陷）。
- **通用 FRONTEND 置 null 后的残留引用排查**【实测】：`pendingToolCall` getter（`ai.ts:258-273`）跳过 null 继续前扫；模板外层 `v-if="m.pendingCall"`（`AiPanel.vue:169`）判空；`formHostMessageId`（`AiPanel.vue:421-427`）optional-chain。置 null 后无悬挂引用。
- 行为变化登记（F6，信息级）：旧实现对已决通用工具 pendingCall 按 first-wins 保留（正是红队指控的冗余卡），新实现重复帧下也置 null——方向正确（消灭冗余卡），非回归。

### S3｜守卫前移 —— 闭合【实测】

- 前置守卫 `ai.ts:1011-1016`：`frontend_tool_request` case 首行，`frame.name === 'generative_form' && hasDecidedPendingResidue(...)` 即 `break`，位于 `this.suspended = true`（`:1017`）与 `message.pendingCall = toPendingCall(...)`（`:1018`）**之前**；抽函数 `ai.ts:699-704`；旧守卫（写入后判定）已从 `openGenerativeForm` 移除（`:716-719` 改注释）。
- **对抗性检查：正常（无残留）路径是否仍正常挂起**【实测，双通道】：
  - 静态：守卫命中条件含 `hasDecidedPendingResidue`，无残留时恒 false → 落入 `:1017-1023` 正常挂起链（suspended / pendingCall / openGenerativeForm / activeForm='filling'），无任何误伤路径。
  - 证据链核验：修复报告 §2.4"S3 对照（防误伤正常路径）"PASS；本复核实读 `<SCRATCH>/verify/verify-result.json:54-61`——detail 为 `{pending:'pending', activeForm:'filling', formRendererButtons:1}`，且验证脚本（`<SCRATCH>/verify-s1s2s3s6.mjs:189` 起）注入的是全新 toolCallId 的真实 `frontend_tool_request` 帧，判别真实非永真。证据链成立。
- 副作用处置核验：守卫命中不再执行 `disarmFormExpiry()`（旧实现守卫前统一 disarm 会误中和**别的**在挂表单计时器）。修复报告 §4.2 已如实登记并判"修正而非回归"——本复核同意：已决 toolCallId 的帧不应产生波及无关表单计时器的副作用，旧行为属误伤。
- 关联观察 F4（信息级）：`suspended` 帧 case（`ai.ts:1065-1081`）写 `message.pendingCall` 前无同款守卫——属红队 S3 范围外的既有行为；seq 序回放下 suspended 先于 result 帧，残留不可能先在，可达性理论化。提请 T2b 快照重建落地时顺手对齐（勘误 4 已定重建路径同 `isFrameDecidedPendingStatus` 口径）。

### S5｜宿主候选限定待决态 —— 闭合【实测】

`AiPanel.vue:416-429`：候选限定 `status ∈ {pending, running, approved}`，终态残留（cancelled/succeeded/blocked/expired）不作宿主；注释（`:411-414`）同步。取代理读法 `const pending = ai.messages[i]?.pendingCall` 保持窄化，无类型断言。修复方定点验证实跑"早消息 pending + 晚消息 cancelled → 宿主判别为 a(pending)"（verify-result.json:78-84），与红队场景吻合。

### S6｜通用 FRONTEND 分支补显式标签 —— 闭合（附发现 F1）【实测】

`AiPanel.vue:240-241`：`succeeded`→"已执行"（success）、`blocked`→"未执行（超出范围）"（warning），与 generative_form 分支（`:221` 的 `rejected || blocked`）口径对齐。定点验证两项（blocked 无"已执行"、succeeded 有"已执行"）实跑 PASS（verify-result.json:62-77）。

**对抗性检查：else 兜底还剩什么值落入**【实测】：`PendingToolCall.status` 联合（`frontend/src/types/ai.ts:218`）共 **8** 个成员：pending/running/approved/rejected/expired/cancelled/succeeded/blocked。该分支显式覆盖 7 个（`:233-241`），**唯一落入 else（`:242`"已执行"）的联合内成员是 `approved`**。`approved` 对 kind='FRONTEND' 的投影无任何写入路径（全部 approved 写入均在 CONFIRM 域：`ai.ts:621,628,988`），故运行时安全，else 只对越契约值生效——**安全性成立**。但见 F1。

### S7｜冒烟文案常量集中 —— 闭合【实测】

`scripts/m2t2a-smoke.mjs:27-33` 三个常量（TEXT_SUBMIT/TEXT_CANCEL/TEXT_FORM_CANCELLED）+ 耦合机理注释 + T2b data-testid 候选登记；引用点 `:141,142,146,176` 全部换用常量。按修复令范围未做 data-testid 改造（留 T2b），与红队"至少集中并加注释"的最低支一致。

---

## 2. 对抗性检查发现（分级）

| 编号 | 级别 | 发现 | 处置建议 |
|---|---|---|---|
| F1 | **低** | S6 修复报告声称"类型联合的 7 个成员现已全部显式覆盖"（`红队修复.md:88`）——事实不准：联合实为 **8** 成员（`types/ai.ts:218`），`approved` 仍落 else"已执行"。当前无 FRONTEND+approved 写入路径，运行时安全；但若未来出现该写入，会误标"已执行"。 | 补一支 `approved` 显式分支（或修正报告口径为"7/8，approved 越 FRONTEND 域不可达"）。可并入 T2b 顺手项。 |
| F5 | **低** | S4 终局裁决留痕未闭环：决策卡勘误 5（`M2-T2-返修决策卡.md:69`，"完整刷新续填场景待 T2b 落地后复验"）与本轮"断言①留 T2b 升级"处置**方向一致**；但仓库内最后留痕停在修复报告 §4.1 的"请裁决"（`红队修复.md:197`），"采纳留 T2b"这一终局裁决未见回写决策卡或独立裁决记录；冒烟脚本断言①处（`m2t2a-smoke.mjs:15`）也无红队 S4 修复方向 (b) 支的"T2b 前置占位断言"显式标注。 | 建议在决策卡补一笔勘误/裁决回写（或由本复核报告充当留痕触发），并在脚本断言①处加一行占位标注。不阻断。 |
| F2 | 信息 | run-cancel 场景下 `stop()`（`ai.ts:814-827`）先 abort 本地 SSE 再 POST /cancel，`CANCELLED` 结局帧通常经 **reattach 回放 / 409** 才到达本端（live 帧到达需连接未断）。修复对三条到达路径统一有效（同一映射层），终态一致性不受影响。 | 无需处置；T2b 复验断言①时建议覆盖"run-cancel 后刷新"场景。 |
| F3 | 信息 | 两条取消路径 toolRun 侧均因 ok=false 落"执行失败"（`ai.ts:1039-1046`）——两路径**一致**（红队"不分叉"目标在双投影上均达成），但"执行失败"文案对"用户主动取消"语义略失真；属 T2a 之前既有口径，非本轮引入。 | 留 T2b/后续文案打磨候选，不阻断。 |
| F4 | 信息 | `suspended` 帧 case（`ai.ts:1065-1081`）写 pendingCall 前无 residue 守卫（S3 范围外、既有行为、seq 序下不可达）。 | T2b 快照重建落地时对齐 `isFrameDecidedPendingStatus` 口径（勘误 4 已定）。 |
| F6 | 信息 | S2 行为变化：已决通用工具 pendingCall 在后续重复帧下也置 null（旧实现 first-wins 保留）。方向正确（正是要消灭的冗余卡），无回归。 | 无需处置。 |

另登记修复报告一处计数偏差（信息级）：§2.4 称"九项判定"，实读 `<SCRATCH>/verify/verify-result.json` 为 **8** 项（S1-a/S1-b/S2/S3/S3对照/S6-blocked/S6-succeeded/S5），报告表格亦只列 8 行。不影响结论。

---

## 3. 独立复跑与结果文件核验【实测】

### 3.1 typecheck / build（本复核实跑）

```
$ cd frontend && yarn typecheck
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 4.51s.                                                   TYPECHECK_EXIT=0

$ cd frontend && yarn build
✓ built in 26.67s.
Done in 31.71s.                                                  BUILD_EXIT=0
```

与修复报告 §2.2 双 EXIT=0 一致（耗时差异属机器负载正常波动）。

### 3.2 冒烟/定点验证（未复跑——非必需；结果文件静态核验）

- 冒烟：`<SCRATCH>/smoke/result-20261008T114409Z.json` 实读——断言 ②③③b① 全 `pass:true`，各阶段计数（dualMessage 2×pending/cancelledTags 0 → cancelDual 2×cancelled/2 tags → refresh 单卡 expired）与修复报告 §2.3 逐字吻合；产物时间戳、runId/toolCallId 为独立 UUID，形态真实。
- 定点验证：`<SCRATCH>/verify/verify-result.json` 实读——8 项全 `pass:true`，detail 与修复报告 §2.4 表逐字吻合；验证脚本（`<SCRATCH>/verify-s1s2s3s6.mjs`）经抽读确认为真实 `ai.handleFrame` 帧注入 + 残留态直接构造（`:105-109` CANCELLED 帧、`:141-150` S2 双场景、`:167-169` S3 残留+重放、`:223-226` S5 双消息），判别真实、非永真构造。

### 3.3 修复提交改动面核查【实测】

`git show 5f06555 --stat`：4 文件（`ai.ts`/`sse.ts`/`AiPanel.vue`/`m2t2a-smoke.mjs`），+104/−35；逐行 diff 与修复报告 §1 六项登记一一对应，无夹带改动。`5c2a2f6` 仅入库 3 份证据文档。

---

## 4. S4 裁决留痕核验（专列）

留痕链【实测】：

```
勘误 5（决策卡 :69，K3 裁决 2026-10-08）          "T2a 阶段仅验镜像重建路径单卡形态；完整刷新续填待 T2b 落地后复验"
  ← 上溯 GLM 复现 P5（施工复现-GLM-5.3.md:141）    "建议 T2b 复验时将断言①升级为含后端历史/重放场景"
红队 S4（施工红队 :62-70）                          再提"近永真"，修复方向 (a) 升级断言 / (b) 显式占位标注
本轮处置（修复报告 :6,189,197）                     修复令排除 S4；§4.1 如实登记"未处置、请裁决"
```

**判定：方向一致（勘误 5 的"待 T2b 复验"≡"断言①留 T2b 升级"），留痕链成立但未闭环**——终局裁决（采纳"留 T2b"支）停在修复报告的"请裁决"，未回写决策卡；脚本内亦无 (b) 支占位标注。详见 F5。不构成打回理由：S4 本就不在本轮修复令内，且该边界已被勘误 5 预先规格化登记。

---

## 5. 总结论

**闭合（通过）。**

- S1/S2/S3/S5/S6/S7 六项修复全部按红队推荐支落地，逐项有 文件:行号 实证；修复提交无夹带改动。
- 重点对抗性检查四问全部核验通过：① 两条取消路径双投影终态一致（S1）；② applyFrontendToolTerminal 场景覆盖完备、置 null 后无残留引用（S2）；③ 守卫前移后正常路径静态+实测双通道确认仍正常挂起，对照证据链真实（S3）；④ S6 else 兜底仅剩联合内不可达的 `approved` 与越契约值，运行时安全（S6）。
- 独立复跑 typecheck/build 双 EXIT=0；修复方冒烟与定点验证结果文件实读核验为真、判别有效。
- 登记低级发现 F1（S6 报告"7 成员全覆盖"口径不准，approved 落 else）与 F5（S4 终局裁决留痕未闭环），连同 F2/F3/F4/F6 四项信息级观察，建议随 T2b 一并收敛——均不阻断本轮闭合。

本文档保持未提交。
