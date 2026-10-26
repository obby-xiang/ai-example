<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-083 · 施工复核 · 复审结论（fix round 1，scoped）（45653e92 / agent-1758）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`workspace.ts`、`AiTools.java`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m93` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 4 条有正文），末条有正文消息为 `m103`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1758`（`main` = 主会话）；原字符 6,919（链内 1 段、合计 6,919）。
> **链号 / 类别**：`MT-083` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m103`（3,707 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 复审结论（fix round 1，scoped）

**规格 verdict：ADDRESSED（6/6 + 5/5 全闭环，可验收）**
**质量 verdict：通过（可验收）** — 无新 Critical/Important；6 项 Minor 与 1 处文档漂移，均不阻断。

我这轮**只跑定向**（按指示不重复全量）：
- `npx vitest run tests/import-publish-confirm.spec.ts tests/workspace-run-job-publish-confirm.spec.ts` → **25/25 通过**（18 + 7）
- `ai-panel-parts-render.spec.ts -t 不可撤销` → **7/7**
- `confirm-irreversible.spec.ts` → **3/3**（该文件本轮删了按钮文案，需单独确认）
- `npx vue-tsc --noEmit` → **exit 0**
- **未跑**：后端 Maven（另一 agent 并行占 build，我刻意避开，避免污染其 target/）、前端全量、报告里的"相关 88"。后端项仅做静态核对（断言与实现逐条对齐，未发现会红）。

---

## 逐项裁定

| 项 | 裁定 | 证据（我逐行读过） |
|---|---|---|
| **C-1** 权威模式 + fail-closed | **ADDRESSED** | 新 `frontend/src/utils/publish-mode.ts:29-53`：空/缺失⇒MERGE、`toUpperCase()==='REPLACE'`⇒REPLACE、JSON 不合法⇒`{ok:false}`、getTask 抛错⇒`{ok:false}`；三个前端入口全部改用它：页面按钮 `ImportWizardView.vue:1007`、handler 同函数、`stores/workspace.ts:532-541`；PUT 失败回滚 `ImportWizardView.vue:1219-1238`。与后端 `PublishJobRunner.java:338-355` 逐分支对齐（含 `"replace"` 小写、`{"importMode":null}`、非对象 JSON 均同结论）。fail-closed 分支在页面与 `run_job` 都是"不确认、不建作业、回执未启动" |
| **I-1** `confirm_step`→WRITE，不加门 | **ADDRESSED** | `backend/.../ai/tools/AiTools.java:512` = WRITE（报告写的 `:502` 是 converter import 插入前的老行号，漂移 10 行，内容正确）；`ToolRegistryDisclosureTest.java:92-106` 改成"仅 confirm_step=WRITE、其余 READ"并抽出预期变量；`types/tools.ts:27` 表格→WRITE。不加门：`kindOf` 仍先判 FRONTEND 通道（未改），WRITE 只进 `/api/ai/tools` 展示 |
| **I-2** AI 门卡真实提示 | **ADDRESSED（带解释）** | `AiPanel.vue:3413-3419`：`start_publish` 走 `ai.hitl.publishIrreversible`，不读本地模式；`zh-CN.json:254`/`en-US.json:254` 均点名"若本次为整体替换，范围内旧数据将被删除，执行后无法恢复"。对 MERGE 是条件式提示（略偏保守），但无假话；这比读陈旧本地模式更正确，我接受该取舍（要"REPLACE 时确定式文案"必须走后端帧字段，见残留） |
| **I-3** 结构化 failed | **ADDRESSED** | `ImportWizardView.vue:1352-1358` 返回 `{ok:false, reason}`，`frontend-tools.ts:1330-1347` 解读为 `outcome:'failed'`；新用例经 `executeFrontendTool` 断言 `outcome=failed` 且带出"磁盘写入失败"（不是只看文本） |
| **I-4** 非推进/回退回执 | **ADDRESSED** | `ImportWizardView.vue:1360-1374`：同一步"步骤未改变…本次未触发写回"、回退"已回退…未触发整表写回"、`fromIndex>0` 前进"未从第 1 步离开"；三条用例分别锁住，且断言 `not.toContain('已推进到')` |
| **I-5** 模型描述 + KB | **ADDRESSED** | `AiTools.java:805/823/845` 三处已改（原"只改浏览器内存/不落服务端"消失），并有后端断言 `sheetWriteToolDescriptionsDiscloseImportStepOneWriteBack`（含 `doesNotContain` 两条反断言）；`types/tools.ts:258/274/294` 同步；`docs/kb-samples/16-ai-panel-guide.md:30` 改为写回口径，`KbSampleCorpusTest.java:53-58` 加反断言 |
| Minor 1 `advanced.reason` | **ADDRESSED** | `flushEditors` 新增 `reason`（本地校验摘要/底层异常），`goStep` 拼进 reason，handler 原样带出；部分写回点名分支有用例（REGION 校验失败 + CURRENCY 已写） |
| Minor 2 `job.id` 缺失 | **ADDRESSED** | `ImportWizardView.vue:1445-1447` 不再拼 `#undefined`，用例断言 `not.toContain('#undefined')` |
| Minor 3 取消语义 | **ADDRESSED** | handler 取消返回 `{ok:false}`（`ImportWizardView.vue:1439-1441`），与 `run_job` 的 `fail` 一致；两侧都无法被读成"已启动" |
| Minor 4 冲突提示时机 | **ADDRESSED** | `publishConflict.value = null` 移到确认通过之后（`:1021-1023`），用例断言取消后"发布冲突/冲突注入"仍在 |
| Minor 5 风险档范围 | **ADDRESSED（按声明未做）** | `set_condition`/`select_definitions` 仍 READ；报告明说未动，不冒充收口——与我的原裁定一致（裁决只点了 `confirm_step`） |

---

## 专项核查（你点名的三件）

**1）权威模式与 fail-closed 是否所有入口一致 —— 是**

| 入口 | 模式来源 | 不可知时 | 结论 |
|---|---|---|---|
| 页面「确认发布」按钮 | `resolvePublishMode(taskId)`（服务端） | 拒绝发布 + error 提示 | 一致 |
| `import.startPublish` handler | 同 `startPublish()` | 同上，`{ok:false}` | 一致 |
| `run_job` PUBLISH | `resolvePublishMode(event.taskId)` | `fail("…未启动")` 零 createJob | 一致 |
| AI `start_publish`（后端 DANGER 门） | 不读前端模式；作业执行时后端解析 | settings 不可解析 ⇒ `PublishJobRunner:126-130` 抛错、整批回滚、作业 FAILED | 一致（机制不同、结果同为"不会静默 REPLACE"） |

`workspace.importMode` 已彻底退出判定链（`run_job` 不再读它），r0 的两条 fail-open 触发（PUT 失败后本地 MERGE / getTask 抛错兜底 MERGE）均已消除；`importMode` 与 `readImportMode` 现在只用于 UI 展示与回滚。

**2）语言 Continue 与 11 处 converter —— 均保留**
- `AiPanel.vue:3127-3137`：`continueRound()` 仍是 `ai.send(t('ai.panel.continue'))`，注释里的 A3-Y8 语义完整，未被本轮门卡改动覆盖。
- `AiTools.java`：`resultConverter = PlainTextToolResultConverter.class` **计数 11**，`import com.example.configmgr.ai.tool.PlainTextToolResultConverter` 在 `:6`。该 converter 对 `CharSequence` 原样返回，`start_publish` 结果的 `result` 仍是纯文本 ⇒ `ai.ts` 的"结果文本取作业号"链路不受影响。
- 另核：`confirm-irreversible.ts` 删掉 `confirmButtonText/cancelButtonText` 属语言专项改动。已确认安全：Element Plus 2.14.7 的 `provideGlobalConfig` 在首个 ConfigProvider 挂载时写入模块级 `globalConfig`（`use-global-config.mjs:44`），命令式 MessageBox 无父级 inject 时回落到它；`App.vue:2` 有根级 `<el-config-provider :locale>`，故按钮文案仍跟随语言（`confirm-irreversible.spec.ts` 3/3 绿）。

**3）KB manifest sha —— 正确**
`16-ai-panel-guide.md` 的 **LF 归一化 sha256 = `c4285aac…343c7a`**，与 `manifest.json:383-384` 完全一致，且与其余 19 篇同一约定（我抽验 `01`~`04`：manifest 全部等于 LF 归一化值，而非 CRLF 原始字节）。注意：本工作树是 CRLF 检出，`KbLocalSourceTest#sampleManifestIsValidAndShaMatchesFiles`（比原始字节）在本地会对**全部 20 篇**报不一致——这是既有环境/换行问题，不是本轮引入；播种/CI 侧按 LF 落地，不受影响。

---

## 新发现（无 Critical / 无 Important）

**Minor（建议随批顺手修，均一行级，不阻断验收）**

1. **确认期间按钮未置 busy，存在双击窗口** — `ImportWizardView.vue:1007-1024`：`busy` 只在 `startJob` 内设置，而 `getTask`+确认框在它之前。快速双击可弹两个对话框（后端有在途作业守卫，不会真发两个作业，但会有一次多余 409/报错）。修法：`startPublish` 入口 `busy.value='publish'`，`finally` 复位（或加 `publishPending` ref 挡按钮）。
2. **纠正后的模式没回写 workspace** — `ImportWizardView.vue:1015` 只改本地 `importMode.value`；`workspace.importMode` 仍可能是旧值，而它是 `buildContext().extra.importMode`（`stores/workspace.ts:292`）与 `get_workspace_state` 的输入，模型可能对用户说错当前模式。修法：同一处补 `workspace.importMode = resolution.mode`（直接赋字段，避免 `setImportMode()` 记一条假"界面动作"）。
3. **同一步仍会 `PUT /step`，且该分支丢弃 syncWarning** — `ImportWizardView.vue:1360-1362` 的"步骤未改变"分支是在 `goStep()` **之后**才判的，而 `goStep` 已执行 `syncStepToBackend()`（`:891`）；若这次无意义 PUT 失败，`syncWarning` 不进回执。修法：在调用 `goStep` 之前短路 `index === fromIndex`（直接返回"步骤未改变"），或在两个分支里补 `syncWarning`。
4. **`parsePublishMode` 的入参假设偏硬** — `publish-mode.ts:33` 前的 `settingsJson.trim()` 在 `try` 之外：若 API 契约被破坏成非字符串，会抛 TypeError 直穿 `startPublish`（点击无反馈）。一行加固：`if (typeof settingsJson !== 'string' || settingsJson.trim() === '')`。
5. **大小写对齐无用例** — `parsePublishMode` 特意与后端 `equalsIgnoreCase` 对齐，但测试只覆盖 `REPLACE/MERGE/null/坏 JSON`。补一条 `'{"importMode":"replace"}' ⇒ REPLACE` 可防将来回退成 `readImportMode` 的精确匹配（`types/task.ts:143-145` 目前仍精确匹配，用于单选初始化/回滚——不影响安全性，只影响极端数据的展示）。
6. **取消=失败 的语义仍是取舍** — 两入口现在一致落 `failed`；模型可能据此重试发布并再弹一次确认。若要收口，可在 `UiEventResult`/`FrontendToolExecution` 引入"用户取消"结局；本批不改可接受，文本已明确"未启动"。

**文档漂移（非代码）**：`docs/03-技术方案文档-v2.1.md:715` 仍写"渲染为「确认启动发布作业，执行后无法恢复」"，与本次门卡文案不一致；按本批"不改 docs"的边界留给文档 owner 扫。

**报告引用小漂移（不影响结论）**：`AiTools.java:502` 实际 `:512`（converter import 插行导致）；`ToolRegistryDisclosureTest` 新增断言实际在 `:104-116` 而非 `:112`。

---

## 未验证面 / 验收条件

- 我**没有**复跑：前端全量（报告 1466/1468）、后端 57+8 与工具全量、"相关 88"。这些仍需主会话或 CI 出数；本轮定向 25/25 + 7/7 + 3/3 + tsc 0 已由我实测。
- 后端两条新断言（WRITE 口径、三处描述）我只做静态核对，未执行 Maven；从代码看断言与实现一致，不会红。
- 残留风险（非缺陷、建议登记）：确认与作业执行之间仍是 **TOCTOU**——确认后到 `PublishJobRunner` 读 settings 之间模式仍可能被别处改；前端已尽到"读最新再确认"，彻底消除需后端在创建作业时回带生效模式（本批不做）。

**可验收：是。** 规格 6+5 项全部 ADDRESSED（无 NOT），无新 Critical/Important；建议在 commit 前顺手带上 Minor 1/2（各一行，分别消除重复对话框与"模型看到过期模式"），其余 Minor 可作为后续清理。
