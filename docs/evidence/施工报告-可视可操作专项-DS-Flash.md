# 施工报告 · AI 可视可操作专项（注册式语义动作 + 引用式通用原语）

## 0. DC-19 过程自证（首页）

| 项 | 值 |
|---|---|
| 施工笔 agentId | `agent-1382`（`profile.bind` 单行取自 `…/agents/agent-1382/wire.jsonl`；同目录 `turn.prompt` 首句核对 = 本任务） |
| modelAlias | `DeepSeek/deepseek-flash` |
| profileName | `coder` |
| thinkingEffort | `high` |
| 日期 | 2026-10-21 |
| 分支 / 基点 | `feature/visual-ops` @ `e80e316`（worktree `../wt-visual-ops`，**未 commit**） |
| 设计依据 | `docs/research/设计-AI可视可操作专项-2026-10-21.md`（第 42 件）+ `docs/research/调研-AI可视可操作能力业界惯例-2026-10-21.md`（第 41 件）+ `_shared/勘察-可视可操作专项-DS-Flash.md` |
| 前置 | 表格专项已合流进 main（`e80e316`）⇒ 本专项按裁决 5「共享文件只做尾部追加」施工 |

范围红线（设计稿 §1.1/§1.2）：7 个原语（1 看 + 6 做）+ 三道围栏 + 元素标注规范 + 上传交接；
像素级截图/坐标点击、模型产 selector、点后果性按钮、canvas 内部、跨域 iframe、`ui_undo`/`ui_batch`
第八九工具、自动补全扫描 一律不做。批①（感知）→ 批④（上传）**同批落盘**。

## 1. 测试矩阵（先行登记；落码前写定）

维度代号：`RG`=区域围栏 `CX`=上下文围栏 `EL`=元素围栏 `SZ`=尺寸闸 `RK`=风险分级 `WR`=写入正确性
`UN`=撤销 `QT`=配额/中断 `UP`=上传三态 `RS`=刷新恢复 `CT`=契约终态 `AU`=审计 `DR`=防漂移 `I18`=ref 稳定性

### 1.1 前端（`frontend/tests/`，新增 11 个 spec + 1 夹具）

| ID | 用例 | 维度 | 期望 | 落点 | 结果 |
|---|---|---|---|---|---|
| T-01 | 区域 14 元素、`limit=20` | SZ | 14 行 + 尾行「共 14 个元素（本次回 14 个…）」 | `ai-snapshot.spec.ts` | 绿 |
| T-02 | 30 元素、`limit=20` | SZ | 20 行 + 「共 30 …本次回 20…已截断」 | 同上 | 绿 |
| T-03 | `limit=50` / `limit=0` / 非数字 | SZ | 参数拒绝（人话，无 JSON） | 同上 | 绿 |
| T-04 | 20 行但 >2000 字符 | SZ | 按字符截断，标记含「原 20 行」+「字符」；正文 ≤2000 | 同上 | 绿 |
| T-05 | 无 `data-ai-ref` 的元素 / 区域外元素 | RG | 快照零命中（文案负断言） | 同上 | 绿 |
| T-06 | `data-ai-deny` 元素 | EL | 不进快照；`ui_click` 拒绝且**零点击** | `ai-dom-verbs.spec.ts` | 绿 |
| T-07 | `password`/`file`/`hidden`/`autocomplete=cc-*` | EL | 四类均不进快照（种子值负断言） | `ai-snapshot.spec.ts` | 绿 |
| T-08 | 导入页 ref 在导出页调用 | RG/CX | 拒绝 + 「当前页面为「export」…请先导航到「import」」 | `ai-dom-verbs.spec.ts` | 绿 |
| T-09 | 过期 `epoch` / 缺 `epoch` | EL | 拒绝且零副作用（click 计数 0） | 同上 | 绿 |
| T-10 | 元素 `isConnected=false`（快照过的 ref 随后被移除） | EL | 拒绝 + 「元素已不在页面上」 | 同上 | 绿 |
| T-11 | label 推导链 | WR | `aria-label`→`title`→`textContent`（截 24、空白归一） | `ai-snapshot.spec.ts` | 绿 |
| T-12 | 同 epoch 同参连调两次 / epoch 变后失效 | SZ | 文本一致、遍历计数 = 1；epoch 变后重新遍历 | 同上 | 绿 |
| T-13 | `pageId` 变化 | RS | counter+1、nonce 不变；同页重复进入不刷 | `ai-regions.spec.ts` | 绿 |
| T-14 | **刷新模拟**（重载模块后旧 epoch） | RS | nonce 变 ⇒ 旧 epoch 一律不等 | 同上 | 绿 |
| T-15 | `registerAiRegion` 缺 `risk` / 空 id / detached root | RG | 抛错、无半注册状态 | 同上 | 绿 |
| T-16 | `ui_fill` → `fill=allow` 的 input | WR | 派发 `input` ⇒ 组件状态同步（"只设 .value"会红） | `ai-dom-fill.spec.ts` | 绿 |
| T-17 | `ui_fill` 到未声明 `fill=allow` | EL | 拒绝 + 「未声明可填写」 | 同上 | 绿 |
| T-18 | `value` > 200 / 正好 200 | SZ | 201 拒绝（零写入）、200 放行 | 同上 | 绿 |
| T-19 | 页面校验器改值（回读不一致） | WR | `failed` + 「值未生效…」 | 同上 | 绿 |
| T-20 | `ui_select` 有 setter | WR | setter 1 次、**DOM 零点击**、原选择入撤销栈 | `ai-dom-select.spec.ts` | 绿 |
| T-21 | 无 setter、选项存在（弹层在 body 下） | WR | 点开→精确匹配→点选→回读成功；`MER` 不命中 `MERGE` | 同上 | 绿 |
| T-22 | 选项不存在 | WR | `failed` + 「下拉选项里没有「APPEND」。可选：…」 | 同上 | 绿 |
| T-23 | `ui_highlight` | WR/A11y | 描边类 + `data-ai-highlighted` + 内联 SVG 徽标 + 播报 + `scrollIntoView`；reduced-motion 无 `transition-shadow` | `ai-dom-highlight.spec.ts` | 绿 |
| T-24 | `ui_scroll` | AU | `scrollTop` 变、`dataVersion` 不变、无撤销点 | 同上 | 绿 |
| T-25 | fill→撤销→再撤销 | UN | 回旧值 + 「已撤销」；第二次「没有可撤销的界面操作」 | `ai-dom-undo.spec.ts` | 绿 |
| T-26 | click 到无 `data-ai-undo` 的 read/mutate-local 元素 | UN | 不记撤销点 | 同上 | 绿 |
| T-27 | 单轮第 13 个写动词 | QT | 拒绝 + 「本轮界面操作已达上限（12 次）…」 | `ai-dom-verbs.spec.ts` | 绿 |
| T-28 | 写动词执行中「停止」 | QT | 后续零执行、已执行不回滚；新轮重置 | 同上 | 绿 |
| T-29 | click `read` | RK | `click()` 1 次、无撤销点、无「可撤销」 | 同上 | 绿 |
| T-30 | click `mutate-local` + `data-ai-undo` | RK/AU | `click()` 1 次 + 高亮 + 撤销点 + `touch(source='AI')` | 同上 | 绿 |
| T-31 | click `consequential`（`export.start` / `export.save` / 上传入口） | RK | **零点击** + 「未执行」+ 「请改用注册动作 start_export」/「请提示用户手动完成」/「ui_request_upload」 | `ai-dom-click-risk.spec.ts` | 绿 |
| T-32 | 元素无 risk、区域 `consequential`；元素显式 `read` 覆盖 | RK | 继承拒执；显式降低即可点 | 同上 | 绿 |
| T-33 | 入参含 `filePath`/`path`/`selector` | UP/SZ | 参数拒绝（负断言：永不接受路径/选择器） | `ai-upload-handoff.spec.ts` | 绿 |
| T-34 | 有 `showOpenFilePicker`（stub） | UP | picker 1 次 → `getFile()` → `receiveFile` 1 次 → `available` 回灌 | 同上 | 绿 |
| T-34b | **手势栈**：`chooseUploadFile()` 返回前 picker 已调用 | UP | 同步段断言（首个 `await` 之前） | 同上 | 绿 |
| T-35 | 无 `showOpenFilePicker` | UP | `degraded` + 「不支持在对话内直接选择文件」+ 高亮入口 + 不回灌 | 同上 | 绿 |
| T-36 | 只读页 / 无上传入口 | UP | `unavailable` + 「任务已结束…未执行上传」；零高亮零卡 | 同上 | 绿 |
| T-37 | 用户点「放弃」 | CT | 写调用零次、`cancelled=true`、卡落终态 | 同上 | 绿 |
| T-38 | 刷新后：区域仍注册 / 已不在该页 / 重建 | RS | `waiting` / `missing` / 重建卡不依赖本地 plan | 同上 | 绿 |
| T-39 | 用户自己点了页面上传（`notifyAiUploadDone`） | UP | 活动交接立即 `ok` 回灌并落卡；无活动交接时是空操作 | 同上 | 绿 |
| T-42 | 前端 17 条登记 ↔ 权威名册（7 原语 + 既有 10） | DR | 名称/风险/scope/参数齐全；scope 一律 `*` | `ai-tool-registry.spec.ts` | 绿 |
| T-43 | 工具描述与常量同源 | DR | `ui_snapshot` 描述含「40」「2000」「共」「不是 selector」「数据」 | 同上 | 绿 |
| T-44 | 英文 locale 下同元素两次快照 | I18 | `ref` 完全一致、label 变 | `ai-ref-i18n-stable.spec.ts` | 绿 |
| T-45 | 每个原语调用 | AU | 回执含 `ref`/`verb=click`/`epoch`；`recentActions` 恰 1 条/次 | `ai-dom-verbs.spec.ts` | 绿 |
| T-47 | 区域拆掉再挂回 / 同 id 重注册 | RS | 旧 ref 拒绝、epoch+1、重注册后新快照可取 | `ai-regions.spec.ts` | 绿 |
| T-48 | 点击触发跳转后再点旧 ref | CX | 第二次拒绝（page 已变） | `ai-dom-verbs.spec.ts` | 绿 |
| T-49 | `ui_fill` 打 `el-input-number`（未标 allow） | EL | 拒绝 | `ai-dom-fill.spec.ts` | 绿 |
| T-50 | 快照 `value` 回显 | SZ/EL | 只对 `fill=allow`、≤24 字符；`password/file/cc-*` 永无 value | `ai-snapshot.spec.ts` | 绿 |
| T-51 | 上传文件名含路径分隔/超长 | UP | 只回 basename、≤64 字符（保留扩展名）、无盘符 | `ai-upload-handoff.spec.ts` | 绿 |
| T-52 | 两个写动词连发 | QT | 第二个**排队**（≥80ms 间隔），不并行 | `ai-dom-verbs.spec.ts` | 绿 |
| T-53 | `ui_snapshot` 的 `kinds` 全不匹配 | SZ | 头行 + 「共 0 个元素（本次回 0 个）」 | `ai-snapshot.spec.ts` | 绿 |
| T-54 | `data-ai-ref` 段字符集 `[A-Za-z0-9_-]+` | DR | `CURRENCY`/`E2E_TEMP` 可注册可解析；选择器形态零命中 | `ai-regions.spec.ts` | 绿 |
| T-55 | **路由 1 集成**：`executeFrontendTool('ui_snapshot')` | DR | 走登记表 + 默认执行器 + `makeExecution`，回灌带尾行 | `ai-snapshot.spec.ts` | 绿 |
| T-56 | **帧路由**：`handleFrame(frontend_tool_request/ ui_request_upload)` | UP | 走交接特判（建房、不回灌、不进 60s 预算） | `ai-upload-handoff.spec.ts` | 绿 |
| T-46 | E2E 主用例 29 → 32 | CT/UP | — | `scripts/verify-e2e.ps1` | **未执行**（禁起服务，见 §7） |

### 1.2 后端（`backend/src/test/java/…`）

| ID | 用例 | 维度 | 期望 | 落点 | 结果 |
|---|---|---|---|---|---|
| T-40 | `ui_request_upload` 帧不带 `timeoutSeconds`；`navigate_to` 仍 120 | CT | `suspendWaitSeconds` null / 120 | `ConfirmGateHumanWaitTest`（名单 +1） | 绿 |
| T-41 | 禁键 / 非法 ref / 非法 epoch / 非法 JSON | SZ | 全部 `accepted=false` + 编号原因 | `UiPrimitiveGuardTest`（新，9 用例） | 绿 |
| T-41b | 合法入参（含大写/下划线 ref、全参可选） | DR | 放行 | 同上 | 绿 |
| T-41c | `accept` 只能收窄（`xlsx/zip` 放行，`csv`/裸串拒绝） | UP | 拒绝 + 原因 | 同上 | 绿 |
| T-41d | `inspectResult` 拒收盘符路径 / `file:` 协议 / `<script>` / `javascript:` | UP | 拒绝 | 同上 | 绿 |
| T-42b | 7 原语通道/风险；披露 `*`（空上下文也披露） | DR | READ×3 / WRITE×4；`FRONTEND` | `ToolRegistryDisclosureTest`（+2 用例） | 绿 |
| T-43b | `ui_snapshot` 描述含「40」「2000」「共」「不是 selector」 | DR | 描述与常量同源 | 同上 | 绿 |
| T-43c | 7 个哨兵桩返回 `FRONTEND_STUB` | DR | 直接调用 | 同上 | 绿 |

## 2. 红测留痕

### 2.1 前端（实现前，真红）

11 个新 spec + 夹具先落盘、`utils/ai-regions.ts` 与 `utils/ai-dom-tools.ts` 尚不存在，跑：

```
Test Files  11 failed | 3 passed (14)
     Tests  211 passed (211)          ← 新用例 0 执行（模块解析即失败）
Error: Failed to resolve import "@/utils/ai-regions" from "tests/ai-upload-handoff.spec.ts"
```

日志：`_shared/red-visual-ops-fe.log`（本机绝对路径已替换为 `<WT>` / `[HOME]`）。
逐轮红→绿推进中另有一次**断言级红**：`T-45` 首跑因回执缺 `verb=`/`epoch=` 而红（实现补齐后才绿）；
`T-17/T-19` 首跑因"风险门先于 fill 声明门"给出泛化文案而红，遂把 `data-ai-fill="allow"` 判定
前移到元素围栏（文案更可行动，风险门随后）。

### 2.2 后端（顺序偏差，如实登记）

`UiPrimitiveGuardTest` 与 `UiPrimitiveGuard` **同批落盘**（未先红跑）——属流程偏差，登记于 §7。
为证明用例确实咬得住，做了**变异红**：把禁键判定短路（`&& false`）后跑单类：

```
[ERROR] Tests run: 9, Failures: 2 -- UiPrimitiveGuardTest
  rejectsSelectorAndPathKeys / renderGivesNumberedReasons
[INFO] BUILD FAILURE
```

恢复后 9/9 绿。

### 2.3 安全性质的变异红（前端，最关键一条）

把 `aiClick` 的 `consequential` 分支改成"先 `click()` 再提示"（即明令禁止的伪确认形态）：

```
× export.start：零点击 + 「未执行」+「请改用注册动作 start_export」
× 无对应注册动作的后果性按钮：指路「请提示用户手动完成」
× 上传入口（consequential）只能走 ui_request_upload，不能 click
× 区域 consequential、元素无 risk ⇒ 拒执
Tests  4 failed | 1 passed (5)
```

恢复后 5/5 绿 ⇒「consequential 拒执 = 零点击」由 spy 计数恒等 0 钉死，不是文案断言。

## 3. 自决清单

### 3.1 原裁 1–6（按勘察建议落，保守优先）

| # | 裁面 | 落法 | 证据/落点 |
|---|---|---|---|
| 1 | 快照上界 | **默认 20 / 硬顶 40 / 2000 字符**（A） | `UI_SNAPSHOT_*` 常量 + T-01/02/03/04/43 |
| 2 | 下载算不算 read | **不算**：`import.upload.templates` 标 `consequential`（A，数据出域面） | `ImportWizardView.vue` 标注 + T-31 同族用例 |
| 3 | 元素级风险可否低于区域 | **允许**（显式即授权）：`riskOfElement` 元素优先、缺省继承区域 | `ai-regions.ts#riskOfElement` + T-32 两向用例 |
| 4 | 上传回退是否加拖拽 | **加**（A）：仅在存在活动交接时接管 `dragover/drop`，走同一条 `uploadOne`，视觉零变化 | `ImportWizardView.vue#onAiDrop` + T-35 文案 |
| 5 | 是否加总开关 | **不加**（A）：写原语"默认开但受围栏"，`enabled` 位留给 i18n 批③ 的 `ai_tool_def` | 无 `application.yml` 改动 |
| 6 | 撤销按钮落点 | **对话内摘要行**（工具卡输出区之下的 `ui-undo-row`；用 `uiUndoToolCallId` 绑定到那一张卡） | `AiPanel.vue` + `stores/ai.ts#undoLastUiAction` |

### 3.2 本笔自决（勘察/设计未写死处）

| # | 事项 | 决定 | 理由 |
|---|---|---|---|
| S-1 | `PageHandlerName +1`（`import.receiveFile`） | 采纳（勘察建议） | 上传交接必须回到页面既有上传链；非帧契约 |
| S-2 | 7 个原语 `@ToolScope({"*"})` | 采纳（勘察建议） | 披露面 ≠ 可达面；围栏在区域/元素两层，page 级限制反而误拦"换页后第一次操作" |
| S-3 | 撤销栈位置 | **模块态在 `utils/ai-dom-tools.ts`，store 只持有投影**（`uiUndoEntry` + `uiUndoToolCallId`） | 设计稿写"栈在 stores/ai.ts"；但写动词执行体在 utils，栈跟着 DOM 操作走才单点。store 仍负责展示与调用（`undoLastUiAction`），语义不变 |
| S-4 | `uploadHandoffAvailability()` 的判据 | **只看区域**（存在 + 非只读 ⇒ waiting）；picker 能力在 `open`/`click` 时分流 | 刷新恢复的真实判据是"页面入口还在不在"；浏览器能力在恢复瞬间不再是决定因素 |
| S-5 | 交接卡 `unavailable` 分支 | 保留（真实路径上只有"只读页/无入口"直接回灌、不建卡） | 组件是纯展示层，保留兜底态避免未来分支缺形态 |
| S-6 | 后端禁键比设计稿多 4 个（`filename/file/dir/directory`） | 采纳加严 | "永不接受路径"应覆盖同义键，否则换个键名即可绕过 |
| S-7 | 共享测试计数 `FRONTEND_TOOLS` 10 → 17 | 同步提高（只升不降） | 新增工具族必然改变总数；断言文字同步为「2 → 10 → 17」 |
| S-8 | `ref` 索引（`refIndex`） | 注册/快照时惰性建，**只用于"元素已卸载"的明确拒绝** | 不加索引则 T-10 退化为"ref 不存在"，模型拿不到"元素没了"这一可行动原因 |

## 4. 设计稿纠偏（不改原文，此处登记）

1. **帧契约「15 种不变」→ 现为 16 种**：`context` 帧已随上下文专项批① 入库
   （`types/sse.ts` 的 `SseFrameTypes` 16 项）。本专项对帧契约的变更诉求 **0 条**
   （设计稿 §0 第 3 条、§4.1、§9 第 4 条仍写 15，属旧口径）。
2. **`data-ai-ref` 段字符集 `[a-z0-9-]+` → `[A-Za-z0-9_-]+`**（含后端 Guard 同款正则）：
   真实 defCode 是 `CURRENCY`（大写）与 `E2E_TEMP`（下划线），旧稿正则会**误杀**按 defCode
   派生的 ref（设计稿 §3.4.2 与 §4.4 的 Guard 正则两处同时纠偏）。指挥官裁决 1 已定。
3. **`ToolPart.uiSummary` 未新增**（裁决 3）：摘要从 `part.result` 派生，少一处与表格专项的
   共享文件冲突；设计稿 §4.3 该行作废。
4. **`registerAiRegion` 字段比设计稿多 4 个**（`page / allowSteps / needsTask / readOnly`）：
   围栏②（上下文）与只读判定没有别的落点；设计稿 §3.4.1 的接口签名据此扩。
5. **围栏② 的实现口径**：`resolveAiRef` **不按页过滤**（否则围栏② 与① 重叠、拒绝文案退化为
   "ref 不存在"，模型拿不到"请先导航到 X"）。页/步骤/任务三段判定在围栏② 里，与
   `WORKSPACE_ACTIONS` 的 `allowPages/allowSteps/needsTask` 同口径。设计稿 §3.5 的
   "区域 page 必须等于 workspace.pageId" 因此落在**判定**而非**解析**上。
6. **`ui_scroll` 不做风险门**（设计稿 §3.6 表已如此，此处明示）：它只改视口，
   consequential 元素也允许滚动到/高亮（"把用户送过去"是安全动作）。
7. **前端 spec 数**：勘察记 29 → 现 46（本笔 +11）。
8. **工具数**：后端 26 → 33（前端通道 15 → 22），前端登记 10 → 17。

## 5. 门禁数字（基线 → 现值）

| 门禁 | 基线（`e80e316`） | 现值 | 结论 |
|---|---|---|---|
| 后端 `mvn -o test` | 1101 / 0F / 0E | **1113 / 0F / 0E** | 绿（+12） |
| 前端 `vitest run` | 598 / 35 文件 | **708 / 46 文件** | 绿（+110 / +11） |
| 前端 `vue-tsc --noEmit` | 0 | **0** | 绿 |
| 前端 `vite build`（`TMP/TEMP=_tmpx`） | ✓ | **✓ built in 38.95s** | 绿 |
| `node scripts/ci/check-docs.mjs` | 6/6 | **6/6** | 绿 |
| 脱敏 | 0 命中 | **0 命中**（新文件无本机绝对路径、无 `sk-` 字面） | 绿 |
| 界面纪律 | — | 新增 UI 无 emoji、图标全内联 SVG、全 Tailwind 无 `style=` | 绿 |

> 注：后端新增用例 = `UiPrimitiveGuardTest` 9 个 + `ToolRegistryDisclosureTest` +3
> （`ConfirmGateHumanWaitTest` 只扩名单、不新增用例）；1101 + 12 = 1113。

## 6. 文件清单

**新增（后端 2）**
- `backend/src/main/java/com/example/configmgr/ai/tool/UiPrimitiveGuard.java`（入参闸 + 回灌闸，197 行）
- `backend/src/test/java/com/example/configmgr/ai/tool/UiPrimitiveGuardTest.java`（9 用例）

**新增（前端 5 源 + 12 测试）**
- `frontend/src/utils/ai-regions.ts`（注册表 / epoch / 围栏①，290 行）
- `frontend/src/utils/ai-dom-tools.ts`（快照/尺寸闸/三道围栏/动词/配额/撤销/高亮/上传原语）
- `frontend/src/types/ui-handoff.ts`（`ActiveUploadHandoff` 等）
- `frontend/src/components/AiPanel/UploadHandoffCard.vue`（交接卡，109 行）
- `frontend/tests/ai-fixtures.ts` + `ai-regions / ai-snapshot / ai-dom-verbs / ai-dom-fill /
  ai-dom-select / ai-dom-highlight / ai-dom-undo / ai-dom-click-risk / ai-upload-handoff /
  ai-tool-registry / ai-ref-i18n-stable` 共 11 个 spec

**修改（共享文件一律尾部追加）**
- `frontend/src/types/tools.ts`（`UI_*` 常量 + `FrontendToolName | UiToolName` + 7 条登记）
- `frontend/src/types/workspace.ts`（`PageHandlerName + import.receiveFile`）
- `frontend/src/utils/frontend-tools.ts`（文件头口径订正 + `defaultExecutor` 增原语分支 + 执行桥）
- `frontend/src/stores/ai.ts`（`activeUploadHandoff`/`uiUndoEntry`/`uiUndoToolCallId`/`uiAnnouncement`
  + 7 个 action + 帧分支 + 恢复路径 + `stop()` 置停止位 + `runFrontendTool` 同步运行时态）
- `frontend/src/components/AiPanel/AiPanel.vue`（第三条挂起支 + 中文名表 +7 + 操作条 + `aria-live` + 撤销行）
- `frontend/src/views/ImportWizardView.vue`（`uploadOne` 抽取 + 上传区标注 + 区域注册/注销 +
  `import.receiveFile` + `notifyAiUploadDone` + 拖拽回退 + `import.upload` 话术追加一句）
- `frontend/tests/sheet-tools-registry.spec.ts`（计数 10 → 17，只升）
- `backend/.../ai/tools/AiTools.java`（类尾 +7 `@Tool` 哨兵桩）
- `backend/.../ai/gate/ConfirmGate.java`（`HUMAN_WAIT_FRONTEND_TOOLS` + `ui_request_upload`）
- `backend/.../ai/tools/ToolRegistryDisclosureTest.java`（+3 用例、`allTools` +7）
- `backend/.../ai/gate/ConfirmGateHumanWaitTest.java`（名单 +1）

## 7. 遗留与偏差（如实登记）

1. **页面标注只落了导入向导第 1 步的上传区**（`import.upload.templates/choose/flush` + 区域
   `import.upload`）。原计划（裁决 6）为"避让表格专项，页面标注延到本专项批②"；表格已合流，
   避让前提消失，但为控制单笔风险仍只落**上传闭环所必需**的那一块。待批②补：
   `import.selectDefs.*`（勾选框 + `data-ai-undo="retoggle"`）、`import.mode.*`（单选 + `setters`）、
   `import.grid.viewport`（滚动容器）、导出向导 / 任务中心试点——**当前这些页面对 AI 一律不可见**。
2. **`ui_select` 的 `setters` 目前在页面侧还没有注册点**（只有夹具覆盖）：与上条同批落地，
   否则导入模式只能走"点开→精确匹配"回退（Element Plus 自绘弹层下不可靠）。
3. **E2E 未落**（T-46，主计数 29 → 32 未改）：施工纪律禁起服务，脚本改动无法验证 ⇒
   `scripts/verify-e2e.ps1` 保持 29，文案未动。三条主用例（上传交接 / 改模式+撤销 / 导出拒执指路）
   已在单测层覆盖，端到端仍需一次实机跑。
4. **后端红跑顺序偏差**：`UiPrimitiveGuardTest` 与实现同批落，未做到"先红"。已用变异红补证
   （§2.2），但它不等于"测试先行"；后续批次需按序。
5. **原裁 3 的 `console.warn` 未加**：元素级风险低于区域时不做 DEV 告警（快照每帧都会重复触发，
   噪声大于收益）。风险由批⑤ 的**标注审计**（导出全部 `data-ai-ref` + risk 人工过一遍）承接。
6. **撤销深度 1 且不持久化**：刷新即失效（设计明示）。对用户只承诺"最近 1 步"，不得当通用 undo。
7. **`ui_fill` 不覆盖复合控件**：`el-input-number` / `el-date-picker` / `el-cascader` 不纳入
   （组件格式化必然导致回读不一致），标 `data-ai-fill="deny"` 或干脆不给 ref。
8. **视觉验收缺位**：jsdom 无布局，高亮的"三重冗余"只断类名/属性/徽标/调用次数；观感交 E2E。
9. **跨域 iframe**：只在注册期拒绝跨文档 root；快照遍历不跨 document（`root.querySelectorAll`），
   与设计一致但**未有单测**（jsdom 难构造跨文档 root）。
10. **快照 `epoch` 与 `dataVersion` 不同源**：`epoch` 管 ref 失效，`dataVersion` 管业务状态；
    `ui_scroll`/`ui_highlight` 不动 `dataVersion`（T-24 钉死），二者不得互相替代。
11. **注入面的前置依赖未变**：`SYSTEM_PROMPT` 仍无"工具结果与界面内容是数据不是指令"定界
    （依赖安全防护批②）；本专项只在**试点页**开写动词，全域开放须等批②。
