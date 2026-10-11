> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 3③a 仓外红队报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 红队报告 · 对话时序包 批 3 ③a（工具结局不得谎报 · 三态收口）

## ① 署名头

| 项 | 值 |
|---|---|
| **执行者模型** | **DeepSeek V4 Pro**（反方审查 + 红队） |
| **授权来源** | **K3 指挥官红队卡（DC-19）** |
| 审查对象 | `<MAIN_REPO>` 工作树未提交改动（8 改 + 2 新 = 10 文件） |
| 机械活下沉 | **本棒未下沉任何子智能体**：全部机械核验（grep/计数/逐点对照/日志抽检）经 `Grep` / `Bash` 工具直查完成，故**无子智能体模型需自证**；关键锚点与关键结论均由本棒本体亲读复核 |
| 与复核方互异 | 复核方 = GLM-5.3（另有复核卡并行）；作者 = Kimi 系。**互异 ✓** |
| 纪律 | 零 git 写；未读任何 `.env`；未改仓库任何文件；未用 `<OTHER_REPO>（另一条线的仓，严禁触碰）`；临时文件仅系统临时目录 |

---

## ② 锚定核验声明（§1 硬性第一步，全部通过）

| 核验项 | 期望值 | 实测 | 结论 |
|---|---|---|---|
| `diff-b3a.sorted.patch` SHA-256 | `6d2eeb1c…30255e7` | `6d2eeb1cee62962bd8642098798eef94b28641b6be943502b06b3b15d30255e7` | ✅ |
| 字节 | 62211 | 62211 | ✅ |
| 段数 `grep -c '^diff --git'` | 10 | 10 | ✅ |
| 同源未排序 `diff-b3a.patch` SHA-256 | `87b65ff7…d2dbf7` | `87b65ff75e34539df07db2fd4405df423bb442f2581b9a8c59b14b643ef2dbf7` | ✅ |
| `final-review-v2.diff` SHA-256 | `f48ecbba…3ba8b52` | `f48ecbbafd50a045ed42738878efb795282c586ddf7cf96051d339b7e3ba8b52` | ✅ |
| 夹带自核（.env / 密钥 / 二进制 / 越界段） | 无 | 无 `.env`、无 `GIT binary patch` / `Binary files`、无可疑密钥字面量；段路径恰为 10 个 | ✅ |
| `git status --porcelain` | 恰为 8 改 + 2 新 | ` M`×8 + `??`×2（`frontend-tool-result-terminal.spec.ts` 为改，两个新 spec 为未跟踪） | ✅ |
| HEAD | `3edaeb9` | `3edaeb900dcc446b918913738a6f527c780e7b81` | ✅ |
| 漂移交叉核验（`git diff` 仅跟踪文件 vs `final-review-v2.diff`） | 逐字节一致 | `git diff 2>/dev/null` = `f48ecbba…`，`cmp` 逐字节一致（初判 DIFFERS 系 `git diff` 的 7 行 "LF will be replaced by CRLF" stderr 警告混入，压掉 stderr 后一致，非漂移） | ✅ |
| 逐文件指纹（EOL 归一化 SHA-256，剥 `\r`） | 10 条 | 10 条全部逐字命中（下表） | ✅ |

**逐文件指纹比对（全部一致）**：

| 文件 | 实测 EOL 归一化 SHA-256 | 结论 |
|---|---|---|
| `AiPanel.vue` | `90014146…c83d53` | ✅ |
| `stores/ai.ts` | `96c1b5f6…cff19` | ✅ |
| `types/tools.ts` | `f1d88507…70b85` | ✅ |
| `utils/frontend-tools.ts` | `e5ebb002…a304c` | ✅ |
| `utils/parts.ts` | `70af61df…5fcfc` | ✅ |
| `ExportWizardView.vue` | `ced0afd2…b2ef7` | ✅ |
| `ImportWizardView.vue` | `3748d4af…52a9` | ✅ |
| `export-wizard-step-outcome.spec.ts`（新） | `a445c372…f6f7f` | ✅ |
| `frontend-tool-outcome.spec.ts`（新） | `fdead7b5…0fb23` | ✅ |
| `frontend-tool-result-terminal.spec.ts` | `7bd1aab1…491a51d` | ✅ |

**声明**：审查字节与锚定一致；工作树行尾混合（`ExportWizardView.vue` / `export-wizard-step-outcome.spec.ts` 为 CRLF，其余 LF）与锚定 patch 正文 LF 的差异已按 §1 口径归一化比对，**未以字节/行尾差判漂移**。审查过程中未发现工作树漂移。

**复核报告落盘状态**：`复核报告-批3③a.md` **未落盘**（`chat-timeline-pkg` 下仅有 `复核卡-批3③a-GLM.md`）。故本报告「尝试推翻复核报告『无 Critical』结论」以**决策卡终裁**为基准（决策卡两方会审总判定 = 带条件可施工），并在发现清单中标注相对决策卡/施工报告 §12 复核轮已登记项为「新增」或「复核确认」。

---

## ③ §3 必查面逐项（①~⑧）

> 标注：【实测】= 本棒亲读代码/patch/日志；【推断】= 基于代码结构的推演；【假设】= 未实测的语义假设。本棒未跑测试（静态审查 + 证据日志抽检），故无「运行实测」结论。

### ③① D5 分层四读点闭合（【实测】）

四处逐一核：

1. **`parts.ts` `localTerminal` `:383-386`** 补 `|| existing?.status === 'unknown'` ✅（实测逐字）。first-wins 在 `fromFrame ?? ok 兜底` 之前短路，`unknown` 段恒取 `existing.status`。
2. **`stores/ai.ts:1722` `applyPendingTerminal`** 覆盖域 `&& part.status !== 'unknown' && part.status !== 'failed'` 置于 `(pending || cancelled || requiresAction)` 之前 ✅。**关键澄清**：本 action 唯一调用点 = GF 表单取消 `cancelGenerativeForm`（`:1319` `applyPendingTerminal(form.toolCallId,'cancelled')`）。**409 响应体 / 续收快照的二次收敛走的是 `applyFrontendToolTerminal`（`:729`/`:1183`）而非本函数**，而 `applyFrontendToolTerminal` 对通用 FRONTEND 只收 `requiresAction`、从不碰 status。故「failed 被排除后原本二次收敛对 failed 段的覆盖被削弱」**不成立**——该两路对 `failed` 段本就零覆盖。
3. **`stores/ai.ts:244-256` `expireResidueToolParts`** 改为 `status: part.status === 'pending' ? 'expired' : part.status` ✅，与 `:233` `sanitizeMirrorResidues` 口径对齐；`unknown` 不翻 `expired`。
4. **`pendingStatusOf`（X1）** `default` 保留折 `'pending'` 为类型穷尽兜底，但 `deriveProjections` 在调用前对 `status==='unknown'` 段显式拦截（`assertUnknownTerminalInvariant` + 跳过挂起投影）✅。

**互不矛盾推演**（同一段在四条路径下终态一致）：`unknown` 段在 `localTerminal`（不翻写）、`applyPendingTerminal`（排除）、`expireResidueToolParts`（不翻 expired）、`deriveProjections`（不产挂起卡）四路下均保持 `unknown` 且 `requiresAction=false`，**同向一致**。

**误伤存量**：③对「非 `pending` 的 `requiresAction` 段」（如乐观 `cancelled + requiresAction=true`）由无条件 `expired` 改为保留原终态——是**口径修正**（与 `sanitizeMirrorResidues` 对齐），非误伤；且 `cancelled` 段正常不会 `requiresAction=true`。②`failed` 排除对可达路径零行为变化（见上）。结论：**闭合，无新回归**。

### ③② D6 双纳入的反向回归（【实测】+【推断】）

`isFrameDecidedPendingStatus` `:388-392` 纳入 `unknown` + `failed` ✅。反向回归推演：

- **GF 路**：两条帧路守卫（`:1588` `frontend_tool_request`、`:1635` `suspended`）均带 `frame.name/waiting.name === GENERATIVE_FORM_TOOL` 前缀，且 GF 工具**从不会落到 `failed`/`unknown`**（GF 走 `openGenerativeForm`，其终态经 `frameStatusToPendingStatus` 仅映射 succeeded/cancelled/expired/rejected/blocked）。故 `failed`/`unknown` 纳入对 GF 守卫**零影响**。
- **快照恢复路**（`restoreSuspendedSnapshot` 全条目）：`hasDecidedPendingResidue` 现对 `failed`/`unknown` 段返回 true ⇒ ①非 PENDING 条目收敛用 `applyFrontendToolTerminal`（通用 FRONTEND 只收 requiresAction，不碰 status）；②PENDING 条目 `continue` 跳过重建。二者均**阻止终态段被重挂为挂起卡**，方向正确。
- **「合法重投」正向场景**：重投同一 `toolCallId` 仅可能来自帧重放（reattach/续收快照）——**真正的新用户/AI 重试必换新 `toolCallId`**。通用 FRONTEND 的重投帧在 `handleFrame` 直跑 `void this.runFrontendTool`（`:1605`），不经 `hasDecidedPendingResidue` 守卫（= R-141③ 既有缺口），故 `failed` 纳入**不拦截**通用 FRONTEND 的（无论合法与否的）重跑；GF 重投被拦截是正确行为（已决不再重挂）。

**结论**：无反向回归；`failed` 纳入未触碰「本地 failed 后合法重试」正向场景（该场景换新 toolCallId，不涉本守卫）。D6 推翻条件（「failed 纳入后出现本地 failed 段被合法重投」）在静态推演下**不成立**。

### ③③ D7 合并语义与终裁逐字比对（【实测】）

| 终裁 | 施工后 | 结论 |
|---|---|---|
| `goStep` 永不 reject | `goStep` 内仅 `await persistStepData()` / `await syncStepToBackend()`，二者**各自 try/catch**、不 throw；`ensureStepLoaded` 走 `void`（不 await，不并入 goStep 的 promise）⇒ goStep 无 reject 路径 | ✅ |
| persist 失败 ⇒ 阻断推进 | `:566-569` `await persistStepData()` 后 `if(!persisted.ok) return {ok:false,reason}`，**先于** `stepIndex.value = next` | ✅ |
| sync 失败 ⇒ 推进 + 警告 + 回灌附「步骤已推进但服务端步骤同步失败，下次切步会重试」、不阻断 | `:570` 先切步，`:576-581` sync 失败 `ElMessage.warning` + `return {ok:true, syncWarning}` | ✅ |
| `ensureStepLoaded` 不纳入并注释 | `:574` `void ensureStepLoaded(next)` + `:572-573` 注释 | ✅ |
| persistStepData 返回值已改 | `:639` `Promise<{ok:boolean; reason?}>`，catch 内 `ElMessage.error` + `return {ok:false,reason}` | ✅ |

**5 调用方全取结局（实测 `grep 'goStep('` = 定义 + 5 调用点）**：
- `:14` 模板 `@prev="goStep(stepIndex - 1)"`：Promise 被模板忽略且不 reject，绑定安全（终裁口径「改」= 适配新返回，无需取结局）✅
- `:635` `nextStep` `void goStep(stepIndex.value + 1)`（UI 点击，无回灌）✅
- `:1169` `preflightDownload` `await goStep(2)` + `if(!advanced.ok) return {ok:false, message:"…本次未触发下载"}` ✅
- `:1192` `export.nextStep` handler（撒谎点本体）`await goStep(index)` + 失败结构化 `{ok:false,reason}` + `syncWarning` 分支回灌「已推进到…（…同步失败…）」✅
- `:1284` openEditor `await goStep(2)` + 失败结构化 `{ok:false,reason}` ✅

**攻击点逐条**：
- **双击 prev/next 并发绕过阻断**：`nextStep` 每次以 `stepIndex.value + 1` 取 `next`；连点两次时 `stepIndex.value` 尚未推进（推进发生在 `await persistStepData` 之后），两次算得**同一 `next` 值**，故**不会跳步**。两次 persist 并发（无 `persisting` 门）下，若一次成功一次失败，成功那次推进、失败那次回 `{ok:false}`——细粒度竞态，**无崩溃、无 unhandled rejection**（已登记 M8）。不构成「绕过阻断」（阻断语义 = persist 失败不推进，成功那次推进是正确语义）。
- **`:1169` 阻断分支真不发起下载**：`preflightDownload` 返回 `{ok:false,message}` ⇒ 执行器 `download_export_file` `throw new Error(preflight.message)` ⇒ 不发起下载（T7③ 断言 `downloadBlob` 未调用）✅。
- **`:1192` 所有失败形态如实**：persist 失败 → 结构化 `{ok:false}`；sync 失败 → 回灌带 `syncWarning` 文案（与卡面 `ElMessage.warning` 措辞同源「步骤已推进但服务端步骤同步失败，下次切步会重试」）。⚠️**例外**见发现 m-1（只读拒绝支/越界支仍回字符串）。
- **`syncStepToBackend` 抛错双提示**：`syncStepToBackend` catch 内 `ElMessage.error`（`:624`）+ goStep 的 `ElMessage.warning`（`:579`）**同事件双弹**，error/warning 严重度不一致。见发现 **m-2**。

### ③④ D9 导入侧范围（【实测】）

- `:758-776` `syncStepToBackend` D8(A) 同法（结构化失败 + `ElMessage.error`）✅；`:729-732` `goStep` 最小接入（sync 失败不阻断 + `ElMessage.warning`）✅；**boolean 签名不变**（`Promise<boolean>`，仍只 `return false` 于越界/flushEditors 失败）✅。
- **`:727` 旧语义「`await syncStepToBackend()` 弃用返回值」已改**为 `const synced = await …; if(!synced.ok) ElMessage.warning(…)` ✅。
- **导入侧 11 处失败形字符串未动**：由 patch 自证——`ImportWizardView.vue` 段内仅 +16/-6，且**全部**落在 `goStep`/`syncStepToBackend` 两函数，handler 注册区字符串零改动。`E19` 清单（含 `:1047` 降级形标注、4 处三元/降级形）未动 ✅。
- ⚠️ 导入侧 `goStep` 同步失败的警告**只给 `ElMessage`、回灌文本仍为干净「已推进到…」**，与导出侧不对称——已登记 **M6**，D9「最小接入」裁定范围内，**复核确认**。

### ③⑤ 工厂函数 21 处迁移（【实测】）

- **`makeExecution` 为唯一生产点**：`grep -nE 'ok:\s*(true|false|outcome\.|input\.|value\.|handled)' frontend-tools.ts` 实测，仅剩 `:107`（工厂内部 `ok: input.outcome === 'ok'`）与 `:151/:157/:164/:170/:173`（`validateArgs` 判别联合 `{ok:true,coerced}|{ok:false,reason}`，**非 `FrontendToolExecution`、无 availability/result，正确未并入**）。**无残留手写 `{ok, availability, result}` 字面量** ✅。
- **21 处逐点命中**：亲读 `frontend-tools.ts` 全文，21 个生产点（timeoutOutcome/未知工具/动作表 4 守卫+4 结局/runRegisteredTool 4 支/runUiEventAction 3 支/runPageHandler 2 支/defaultExecutor 1 支 + dispatchFrontendTool 1 支等）全部经 `makeExecution` ✅。
- **availability/result 逐点保持原值（只换 ok⇐outcome）**：逐点比对，仅 **D4(a) `:804-809` 一处 intentionally 将 `availability:'available'→'degraded'`**（决策卡 C5/D4(a) 钉死点），其余 availability/result 逐字保持 ✅。
- **outcome 语义与终裁一致**：`handled:false ⇒ 'failed'`（`:921`）、`throw ⇒ 'failed'`（`:882/:927/:955`）、字符串 ⇒ `'ok'`（`normalizeExecutorReturn`/`interpretHandlerOutcome`）✅。
- **`validateArgs` 5 处未并入**：类型安全论证成立（判别联合与 `FrontendToolExecution` 不同型、无 outcome/availability/result 维度）✅。

### ③⑥ D4(a) 降级 notice 影响面（【实测】）

- `:801-810` 等待上界耗尽支 = `makeExecution({outcome:'unknown', availability:'degraded', result})`，availability 由 `'available'` 降 `'degraded'` ✅（C5 钉死）。
- **notice 触发链**：`runFrontendTool`（`:1838-1840`）`availability==='degraded'` ⇒ `this.notice = execution.result`（「仍在加载中…请稍后重试或提示用户手动操作」）。文案与 `unknown` 语义一致（结局未知 + 行动指引）✅。
- **过度打扰**：3s 等待上界超时才触发一次 notice（`this.notice` 单字段、非反复 toast），非「页面只是慢每次都弹」；D4(a) 推翻条件（活跃用户实测过度打扰）**无证据**，不构成 MAJOR。
- **与 `:776-781` 契约拒绝支重复提示**：两支互斥（等待上界耗尽 vs `outcome.handled===false`），同一动作不会同弹 ✅。
- **文案逐字未动**：patch 中 result 串 `…仍在加载中；若要继续在该页动作，请稍后重试或提示用户手动操作` 与基线逐字一致（仅 `ok:true/availability:'available'` → `outcome:'unknown'/availability:'degraded'`）✅。

### ③⑦ 原子性 / 半成品态（A8+A9+A10+pendingStatusOf+`:1722`+`:246`+T1/T2/T3）（【实测】）

9 项原子红线（A4/A5/A8/A9/A10/X1/`:1705`/`:246`/D3(iii)/T1-T3）**全部在 patch 内、零 git 写下天然同提交** ✅。四项推演级验证：

1. 「A4/A5 缺 ⇒ `{ok:false}` 被 `defaultExecutor` 折成成功」：A4/A5 在 ⇒ `defaultExecutor` 结构化失败透传（`:1086-1093`）→ `normalizeExecutorReturn` 落 failed，**不复现** ✅。
2. 「A9 缺 ⇒ 回执帧把 `unknown` 翻成 succeeded」：A9 在 ⇒ `localTerminal` 含 unknown、first-wins，**不复现**（T1 生死线绿为证）✅。
3. 「A10/X1/`:1722`/`:246` 缺 ⇒ 挂起卡复活/终态被改写」：四处俱在 ⇒ `unknown` 终态不可改、不复活（③①）✅。
4. **A14**：`ToolRunStatus` 九态未扩张/未重命名（`types/ai.ts:165` 原样）✅；全仓 `ToolRunStatus` switch 仅两处——`toolTagType`（九态穷尽、去 default）、`pendingStatusOf`（default 为 X1 门禁后类型兜底）✅。

### ③⑧ C1~C8 逐项核销（本棒前身条件）

| 条件 | 结论 | 证据 |
|---|---|---|
| **C1** A9/A10 未表述成「收口」；两结构性缺口 + 三同型面处置/登记 | **已满足** | 决策卡 D5 明确「一行收口表述作废」；三同型面（`:1722`/`:246`/`pendingStatusOf`）本批修；两结构性缺口（re-attach `ai.ts:1008` / loadHistory 整替 `:644`）登记移交 ③b（R-141①/②）+ 施工报告 §7 申报已知代价，无静默留白 |
| **C2** `failed` 纳入 | **已满足** | `isFrameDecidedPendingStatus:391` `\|\| status === 'failed'` |
| **C3** D7 合并语义钉死 | **已满足** | persist 阻断 / sync 推进+警告 / `ensureStepLoaded` 不纳入并注释（③③） |
| **C4** 失败形清单订正重列 | **已满足** | 施工卡 E19：补 `Import:1074-1076`、`Export:1204`；`:1181` 判非三元；三元/降级形 4 处（`Export:1204`+`Import:1056/1065/1074-1076`） |
| **C5** `:767-772` availability 钉死 degraded | **已满足** | `:804-809` `availability:'degraded'` |
| **C6** 空 catch 全量登记 + defer 边界 | **已满足** | 施工卡 E18：两向导 7 处 + 全仓 11 处 + `.catch` 5 处逐条；仅 `Export:589`/`Import:763` 本批修 |
| **C7** pending 补显式 case 去 default | **已满足** | `AiPanel.vue:542-543` `case 'pending': return 'primary'` + 去 default |
| **C8** 台账逐项点名不打包 | **已满足** | 施工报告 §9 逐项点名 R-139①②③④ / R-140①12条+②o1-o4+③§5-5 / R-141/R-142 新增残留 |

**C1~C8 全部满足**。

---

## ④ §4 对抗推演（逐场景推演链 + 结论）

**场景 1：`unknown` 段 × `FRONTEND_RESULT` 回执帧（先后两序）**
`localTerminal` 含 unknown（`:386`）⇒ 无论回执帧先到还是后到，`applyFrameToParts` 在 `fromFrame`/ok 兜底之前短路取 `existing.status`。ok 兜底通道（`frame.ok ? 'succeeded' : 'failed'`）仅当 `!localTerminal && fromFrame===null` 才可达，对 unknown 段**不可达**。结论：**恒 unknown，无越权改写**（T1 两序锁定）。

**场景 2：`unknown` 段 × re-attach 新宿主 / loadHistory 整替**
re-attach 建新宿主消息，`findToolPart(新消息)` 找不到旧段 ⇒ 建 `succeeded` 新卡、旧 `unknown` 卡并存（两张矛盾卡）；loadHistory 整替 `:659` 硬编码 `status:'succeeded'` 丢弃本地 unknown 段。二者**仍会把 unknown 翻成 succeeded / 产重复卡**。但：**与施工报告 §7-1 / §8 的已知代价申报描述完全一致**（R-141①/② 已登记移交 ③b、未静默留白）⇒ **不构成 MAJOR**（属如实申报的已知代价，符合 C1）。结论：**仍失守，但已如实申报、非谎报**。

**场景 3：`unknown` × 本地乐观 `cancelled`/`failed` 交叉**
`unknown` 段正常 `requiresAction=false`（A8 落卡时恒 false）；GF 取消经 `applyPendingTerminal`（唯一调用点 `:1319`）现**显式排除 unknown/failed**，故取消不覆盖 unknown/failed 段。终态归属确定（unknown/failed/cancelled 各自 first-wins），无 `requiresAction` 残留 ⇒ 无挂起卡复活。结论：**确定、无复活**。

**场景 4：`unknown` × `expireResidueToolParts` / `applyPendingTerminal` / 重投帧 四读点同向**
expire 保留原终态（不翻 expired）、applyPendingTerminal 排除（不翻 cancelled）、localTerminal first-wins（不翻 succeeded）、isFrameDecidedPendingStatus 视为已决（防重挂）。四读点**同向**：`unknown` = 不可改写终态。结论：**同向一致**。

**场景 5：`unknown + requiresAction:true` 违例段**
`deriveProjections:200-204` 显式 `if(part.status==='unknown') assertUnknownTerminalInvariant(part)` + 跳过 `pending`（工具卡仍如实显示 unknown）。**dev 断言实际非 dev 门控**——`assertUnknownTerminalInvariant` 是无条件 `console.error`（无 `import.meta.env.DEV`），生产也触发（无害、仅 console 留痕），且「不产挂起卡」恒成立。结论：**不产挂起卡且留痕；「dev 断言」口径不实（见 O-1）**。另注：X1 只拦截 `unknown`、**未拦截 `failed`**——rogue `failed+requiresAction=true` 仍会经 `pendingStatusOf('failed')→default→'pending'` 投影为挂起卡（该路径仅经 R-141③ 通用 FRONTEND 重投帧可达，属已登记既有缺口，见 O-5）。

**场景 6：模板 `:14` `@prev` × `goStep` 各类失败**
`goStep` 无 reject 路径（persist/sync 各自 catch、`void ensureStepLoaded` 不并入）。`ensureStepLoaded` 内部 `refreshResults→loadExistingResults`（try/catch、返回 boolean）+ `ensureGridLoaded`（结构化 `{ok:false}` 不 throw），**不产生未处理拒绝**。结论：**无 UnhandledRejection**。

---

## ⑤ §5 测试锁绕过检查（T1~T9）

| 锁 | 能否被「顺手优化」绕过 | 判定 |
|---|---|---|
| **T1** 生死线 | 断言直接 `toMatchObject({status:'unknown', requiresAction:false})`，非弱断言/顺序可绕；若实现回退「localTerminal 去掉 unknown」则 RED（`RED-T1-T2-T3.log` 实测红在 `status` 不匹配） | **真锁** |
| **T2** X1 | 断言 `pendingCall===null` + `console.error` spy 被调（双落）；「只在 dev 生效」的担忧**不成立**——断言是无条件 `console.error`，生产也触发（见 O-1，方向更安全） | **真锁** |
| **T3** D6 | `hasDecidedPendingResidue` 对 unknown/failed 返回 true；注释未写「全覆盖」（明写快照恢复路可测口径） | **真锁** |
| **T4** D4(a) | 断言 `outcome==='unknown'` + `ok===false` + **`availability==='degraded'`**（C5 钉死点，非弱断言）；`vi.useFakeTimers` 驱动 3s 超时，非 mock 自证 | **真锁** |
| **T5** A3/A5/D2 | `{ok:false}`（结构化）与 `:1236` 类（handler 返回）**分列断言**；字符串→ok 兼容单独断言 | **真锁** |
| **T6** A4 | `throw` 负例真存在（`RED-T4-T6-T8.log` 红在 throw 支应 failed）；三路（结构化/字符串/throw）各一 | **真锁** |
| **T7** W-06 | **真注入失败**（`selectDefs.mockRejectedValue` / `goToStep.mockRejectedValue`），非靠 mock 自证「不空转」；`RED-T7.log` 实测 4 例全红 | **真锁（⚠️③④ RED 见 O-4）** |
| **T8** D1 | 工厂纯函数等价改写断言（`ok===(outcome==='ok')`、reason 透传），非逐点手写 | **真锁** |
| **T9** 既有 9 spec | `FINAL-full-test.log` = 11 files / 110 passed；既有 9 spec 未改断言语义（`tools-and-deadline.spec.ts` 最终未动，见施工报告 §11 注） | **真锁** |

**RED/GREEN 抽检（实测日志）**：`RED-T1-T2-T3.log`=4 failed（红在 T1/T2/T3 预期断言）；`RED-T4-T6-T8.log`=9 failed（红在 T4/T5/T6/T8）；`RED-T7.log`=4 failed；`GREEN-T7.log`=4 passed；`FINAL-full-test.log`=11/110 passed；`FINAL-typecheck.log`=vue-tsc 0 错；`FINAL-build.log`=built。与施工报告 §3/§4 计数一致。

---

## ⑥ 发现清单（分级：B=BLOCKER / M=MAJOR / m=MINOR / O=OBSERVATION）

| 编号 | 级别 | 标题 | 锚点 + 证据 | 相对复核/施工报告 |
|---|---|---|---|---|
| **m-1** | MINOR | 两向导 handler 的「只读态拒绝 / 越界拒绝」字符串仍被折成 `outcome:'ok'` ⇒ 卡面 tag「已成功」与回灌文本「任务已结束，仅供查看，不能推进步骤」自相矛盾 | `ExportWizardView.vue:1182-1184`（`return '任务已结束，仅供查看，不能推进步骤'`）、`:1187-1189`（越界拒绝字符串）；`interpretHandlerOutcome`（`frontend-tools.ts:974-975`）字符串⇒ok。`export.selectDefs`/`export.setConditions`/`export.start`/`import.*` 只读守卫同族 | **新增**（复核 I1 只收了 persist 失败支，漏了同 handler 的只读/越界两支） |
| **m-2** | MINOR | `goStep` 内 `syncStepToBackend` 失败时双提示：`syncStepToBackend` 自身 `ElMessage.error` + `goStep` 的 `ElMessage.warning` 同事件双弹，error/warning 严重度不一致 | `ExportWizardView.vue:624`（error）+ `:579`（warning）；`ImportWizardView.vue:773`（error）+ `:731`（warning） | **新增** |
| **O-1** | OBSERVATION | `assertUnknownTerminalInvariant` 被卡面/注释标为「dev 断言」，实为无条件 `console.error`（无 `import.meta.env.DEV` 门），生产也触发；无害但口径不实（方向更安全） | `parts.ts:134-139` | **新增** |
| **O-2** | OBSERVATION | `runUiEventAction` `handled:false → outcome:'failed'` 但 `availability:'available'`（无降级 notice）；迁移保持原值、无直接单测 | `frontend-tools.ts:921-925`；施工报告 §12 M9 | **复核确认**（M9） |
| **O-3** | OBSERVATION | `goStep` 双击 prev/next 细粒度竞态（同 next 值、无崩溃、无 unhandled rejection） | `ExportWizardView.vue:561-583`；施工报告 §12 M8 | **复核确认**（M8） |
| **O-4** | OBSERVATION | T7③/T7④ 的 RED 留证红在「夹具未就位」（scope 未拨 / `getFiles` 未回结果）而非红在 D7 断言本体；GREEN 终态断言是真锁 | `RED-T7.log`（③红于「当前上下文…不支持」、④红于「没有 DEMO 的导出结果文件」）；施工报告 §10.3 | **新增** |
| **O-5** | OBSERVATION | X1 不变式只拦截 `unknown`、**未拦截 `failed`**：rogue `failed+requiresAction=true` 段经 `pendingStatusOf('failed')→default→'pending'` 仍可投影为挂起卡；可达面仅 R-141③（通用 FRONTEND 重投帧 `suspended` 支不经守卫重设 requiresAction），属已登记既有缺口 | `parts.ts:200-204`（仅 `status==='unknown'` 拦截）、`pendingStatusOf` default；`ai.ts:1633-1643`（suspended 守卫仅 GF） | **新增**（D6 已修复守卫类路径，此为非守卫的 `suspended` 帧路径残余，登记 R-141③ 范畴内） |

**无 BLOCKER、无 MAJOR**。相对复核报告「无 Critical」结论：**未推翻**（未发现 Critical/MAJOR 级新缺陷）；新增 2 MINOR + 3 OBSERVATION，复核确认 2 OBSERVATION（M8/M9）。

---

## ⑦ 总结论

**带条件 PASS。**

- **锚定**：SHA-256 / 字节 / 段数 / 夹带自核 / 漂移交叉 / 逐文件指纹全部通过，审查字节与锚定一致，无漂移。
- **核心红线**：9 项原子提交红线（A4/A5/A8/A9/A10/X1/`:1705`/`:246`/D3(iii)/T1-T3）全部落地且同提交；D1~D12 + X1/X2 终裁逐字落实；C1~C8 全部满足。
- **三态收口**：`unknown` 四个连带读点（`localTerminal`/`isFrameDecidedPendingStatus`/`pendingStatusOf`/`applyPendingTerminal`+`expireResidueToolParts`）互不矛盾、同向一致；对抗推演 6 场景无一处复现旧缺陷。
- **测试锁**：T1~T9 均真锁（断言钉死、非弱断言可绕），RED/GREEN 双向留证与 `FINAL` 110/110 + typecheck 0 错 + build 绿一致。
- **未发现可证 Critical/MAJOR**。

**带条件（PASS 附 2 项 MINOR 处置建议，移交后续批次/③b，不阻断本批收口）**：
1. **m-1**：两向导 handler 的只读态拒绝 / 越界拒绝字符串 → `ok` 的残留谎报面，应登记（若提交官认可，可随 D3(iii) 的「失败形字符串」同批补结构化，或移交 ③b 一并处理）。
2. **m-2**：`syncStepToBackend` 失败的双提示（error + warning）措辞/严重度统一（可选：sync 失败由 goStep 单点提示，`syncStepToBackend` 不重复 `ElMessage`）。

---

**静态结论声明**：本棒未执行 `yarn test` / `yarn typecheck` / `yarn build` / CDP / E2E（遵守 §6「以静态实读为主、避免与并行 GLM 复核棒争抢资源/端口」）；§4/§5 结论基于**代码/patch 亲读 + 证据日志抽检**，非运行实测。分级结论与方法一致（凡「实测」均标注并给出锚点/日志行）。

*落盘：`<SCRATCH>/chat-timeline-pkg/红队报告-批3③a.md`。*
