> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 施工报告（V1~V12 全绿），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 施工报告 · 批4④「对话时序包 · 折叠规则」

> 施工依据 = 《批4④-施工卡.md（定稿）》（替代草案，sha256 `d6835ebc…` 者仅存档）。
> 仓库 = `<MAIN_REPO>`（唯一），基线 HEAD `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`，分支 main。
> 证据目录 = `<SCRATCH>\chat-timeline-pkg\evidence-p17\`。
> **零 git 写**：本棒未执行任何 git 写子命令（提交由提交官办理）；工作树保留 6 文件改动供提交官按 §9 两提交拆分。

---

## 0. 施工棒执行模型自证（H1/H2 模板照填）

```
施工棒执行模型自证：
 - 执行者 = main（CU 会话 id = session_9ffc4050-e061-473e-b1ab-c1678f9bcf3d）
 - wire 路径 = <KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_9ffc4050-e061-473e-b1ab-c1678f9bcf3d\agents\main\wire.jsonl
 - 统计命令 = grep -o '"modelAlias":"[^"]*"' wire.jsonl | sort | uniq -c
 - 实测命中 = "modelAlias":"kimi-code/kimi-for-coding" × 107 次；"model":"kimi-code/kimi-for-coding" × 106 次
 - 结论 = 实际运行模型 = kimi-code/kimi-for-coding（wire 实证，非配置推定）
```

- **机械活下沉申报（H3/§11.3）**：本棒**未做机械活下沉**——锚点复取、grep 计数、sha256 对照、CDP 冒烟、E2E 全部由内联完成；无子智能体派发，故无"钉版/子智能体自证"事项。关键锚点与关键结论均由本棒亲读（Read/grep 逐点）。
- **H4 路径红线**：对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` 零操作（未读未写未 git）。

---

## 1. 锚点对比表（TS-10；基线 296ffe2）

S0 逐项复取全 PASS（详见 `锚点实读表.md`，15 项 + 7 项支撑锚点与卡 §12 逐行吻合，**零漂移**）。收口后复取（工作树态）：

| 锚点 | 基线 | 收口后 | 说明 |
|---|---|---|---|
| `stores/ai.ts` `expandedBlocks` state | 不存在 | `:463`（`Record<string, boolean>`，D-C6 定稿类型） | 新增 |
| `setBlockExpanded` / `toggleBlock` | 不存在 | `:1872` / `:1880` | 新增 |
| `resetSession` 清空 expandedBlocks | — | `:1461` 区域（`this.expandedBlocks = {}`） | 新增 |
| A4' 写点（cancelGenerativeForm 失败分支） | `:1351-1353` | `:1358-1380`（`form.error` 原行保留于 `:1359`） | 增补，既有语义未动 |
| `utils/blocks.ts` | 不存在 | 新建 131 行；`defaultExpandedOf:45` / `resolveBlockExpanded:64` / `blockHeader:75` / `groupParts:84` | 新增 |
| `AiPanel.vue` 块分派区 | `:166-267` 段分派 | `:165-330` 块分派（组壳 `:171` 起；bare 支 `:261` 起） | 重构 |
| `blocksOf` / `isBlockExpanded` / `toggleBlock` / `textOf` | 不存在 | `:582` / `:597` / `:602` / `:587` | 新增 |
| `tests/blocks.spec.ts` | 不存在 | 新建（15 例） | 新增 |
| `tests/ai-panel-parts-render.spec.ts` | 243 行 / 7 例 | 19 例（`:75` 段序保留、`:119` N10 零改动、`:134` isTyping 零改动） | 改写 |
| `m2t2a-smoke.mjs` | 488 行 | countExpr 段态化 + 断言③/③b 改写 + injectExpr 顺序注入法 | 改写 |
| 旧死位 `:206`/`:251-253`/`:254-257` | 存在 | **已删**（DOM 零命中断言绿） | D-C7 |
| `.pendingCall` 消费面（frontend/src） | 21 行 / 26 次 | **12 行 / 13 次** | 收窄 9 行 = A1' 删除的投影挂起卡读点（删除清单内）；保留面 = D2 明确保留（见 §8-9） |
| `hasDecidedPendingResidue` 闸 6 调用点 | `:753/:765/:1571/:1615/:1636/:1688` | 现 `:760/:772/:1600/:1644/:1665/:1717`（diff hunk 四段均不覆盖，行内容逐字一致） | 零触碰（TS-8） |

**diff 段序固定 + SHA-256 双份（§1.5-①）**：`<SCRATCH>\chat-timeline-pkg\diff-批4④.sorted.patch`（873 行，按文件路径排序）sha256 = `f713a5167ea146ee0cf2a66abf1bbf0979ff7cd7afd03e96e1e00f5e0e1b55e5`；五文件 sha256 见 `evidence-p17/diff-sha256.log`。**未发生收口修**（一次成型），故无"收口修后重锚"事项。

---

## 2. 各锁 RED/GREEN 双向留证（§11.1-2）

| 锁 | RED（夹具就位仍红） | GREEN | 证据 |
|---|---|---|---|
| S1 模板合并（A1'/D-C7/D-C9/D-C10） | `RED-s1.log`：6 例红——工具名双卡头 2≠1（×2 用例）、D-C9 两卡 0≠2、A9' 旧文案命中（×2）、A4' reason 缺失 | `GREEN-s1.log` 13/13 | 夹具全就位（mountPanel + 段/投影/ activeForm），红因=断言非夹具 |
| TS-3 A4'（G-C2/D11） | 同 `RED-s1.log` 第 6 例：段 reason `undefined`、DOM 无语境文案 | `GREEN-s1.log`（reason 逐字 + DOM 可见） | 同上 |
| A8' 自承 RED（恒假变异） | `RED-a8p-mutant.log`：testid 门控恒真变异（去 `requiresAction` 条件）⇒ 重投路等 4 例红；变异 diff = `RED-a8p-mutant.diff` | `GREEN-a8p-revert.log` 13/13 + `GREEN-a8p-revert.diff` 逐字核验恢复 | 变异/恢复双向 diff 均落盘 |
| S3 块分派（TS-1 ①~⑩ + TS-7 配对 + 组头） | `RED-s3.log`：**红在导入**（`@/utils/blocks` 不存在）——按卡申报取法①，如实标注 | `GREEN-s3-blocks.log` 15/15；`GREEN-s3.log` 两 spec 34/34 | TS-1 用例①~⑩全落 |
| TS-2 展开态三态（面板层） | 随 S1 RED 批次（块分派断言在段分派模板上红） | `vitest-c2.log` 152/152 内含：D6② 手动收起不被帧打开、手动展开新帧不自动折叠、A6' 重挂载不丢、D-C1 组默认展开、D4 resetSession 清空 | — |
| TS-4 仓内三连 | — | 提交1：typecheck ✓ / 131 / build ✓（`typecheck-c1.log`/`vitest-c1.log`/`build-c1.log`）；提交2：typecheck ✓ / **152** / build ✓（`*-c2.log`） | 实跑例数 152（S0 基线 125 → +27） |
| TS-5 m2t2a（D8/D9 锁） | `RED-m2t2a.log`：原脚本（git show HEAD 提取 `_m2t2a-orig.mjs`）对**新前端**退出码 1（断言② FAIL + FATAL） | `GREEN-m2t2a.log`：改写后 **退出码 0**，②③③b 全 PASS；③ = `cancelledSegments:2 && hangingSegments:0`；③b = `formRendererCount:0 && hangingSegments:0 && cancelledStatusTags:3` | 见 §6 |
| TS-6 E2E 桩 | — | `E2E-stub-final.log` 末段：**PASS=28 FAIL=0 GFSKIP=0，e2e_exit=0**，Q8 项 2/2 | 配方见 §5 |
| TS-7 多 requiresAction 段 | 含于 S1 RED（0≠2 卡） | `GREEN-s1.log`（逐段两卡）+ `GREEN-s3-blocks.log` 配对（2 独立 bare 组、defaultExpanded 均 true） | — |
| TS-8 红线零触碰 | — | `ts8-redlines.log`：parts.ts / frontend-tools.ts / sse.ts **sha256 与基线逐字一致**；workspace.ts / backend 零 diff；闸 6 调用点 diff 空 + 行内容一致；`ts8-ai-ts.diff` 四 hunk 均不覆盖闸区 | — |
| TS-9 CDP 形态 | — | 10/10 PASS，6 截图（§3）；`cdp/result-*.json` | — |
| TS-10 锚点复取 | — | `锚点实读表.md`（S0 全 PASS）+ §1 收口后表 | — |

**RED 真实性（H6）**：除 TS-1（导入缺失型 RED，卡准许并申报）与 TS-5（仓外 CDP 脚本，夹具=真实 dev server + store 注入）外，全部 RED 为"夹具就位、断言红"。

---

## 3. CDP 仓外专项冒烟（TS-9；§7 强制覆盖）

- **配方（逐字）**：dev 前端 `http://localhost:5202/`（`cd frontend && VITE_DEV_PORT=5202 VITE_API_BASE=http://localhost:18330 yarn dev`，**全新启动**，起手 `netstat` 确认 5202 无占用）；CDP = 脚本自起 headless Chrome（端口 9347，`--user-data-dir` 于证据目录）；注入 = `$pinia` 直读 ai store + `handleFrame` 真实帧处理链（与 batch3b 同配方）；脚本 = `<SCRATCH>\chat-timeline-pkg\batch44-fold-smoke.mjs`（**仓外，不入库**，头部含 D13(B) 复跑纪律）；形态2/3 的回灌失败确定化 = 页内替身 `submitFrontendToolResult ⇒ {ok:false, message:'网络异常'}`（与卡 §4 S2 夹具同 outcome 形状）。**复跑前已重启 dev server 的留证**：devserver.log 首行时间戳 04:2x 全新进程（PID 27400），与 ③b 旧进程无继承。
- **截图**（`evidence-p17/cdp/20261011T203247Z-*.png`，ISO 时间戳命名；脚本运行时钟为 UTC，本地 2026-10-12 04:32）：
  - `1a-折叠组-默认折叠一行摘要.png`：正文 →「工具活动 · 3 段」组（折叠，body `display:none`）→ 正文；
  - `1b-折叠组-手动展开后新帧保持展开.png`：组展开含 3 卡 + 新到「列出任务 调用中…」裸卡（D6② 实证）；
  - `1c-确认门等待形态.png`：CONFIRM 待决卡（确认执行按钮）直渲、强制展开；
  - `2-GF取消失败文案可见.png`：卡内红字「原因：取消回灌未送达（网络异常），后端将在超时后自动收尾」+「已取消」段状态 tag；
  - `3-取消失败reason在折叠组内可见.png`：GF 取消段与两工具同组（3 段），组默认展开（D-C1），reason 组内可见；
  - `4-单工具轮不套壳.png`（独立文件，未并入 1c）：单工具裸卡无组壳。
- 断言全文：`cdp/result-20261011T203247Z.json`，10/10 PASS。

## 4. A1' 合并后实际结构（§11.1-4）

- 单卡结构：卡根 div 唯一，`:data-testid="part.requiresAction ? 'pending-card-' + part.toolCallId : undefined"`（段驱动，不经投影）；卡头/参数/结果/原因全部取段（`toolLabel(part.name)` / `kind2` tag / `toolStatusLabel(part.status)` 九态 tag）。
- 挂起子块（`v-if="part.requiresAction"` 内，段判据）：`CONFIRM && status==='pending'` → 确认块（倒计时/拒绝原因/双按钮，原 `:212-231` 内容）；`FRONTEND && name===GENERATIVE_FORM_TOOL` → FormRenderer（宿主条件 `liveForm && liveForm.toolCallId === part.toolCallId && m.id === formHostMessageId` 逐字保留，D10）；其余 → 无子块。
- **已删行（296ffe2 基线逐行点名）**：`:206`「会话已失效」tag、`:251-253` GF 非宿主三 tag、`:254-257` activeForm 错误位；孤儿函数 `argsText` 一并删除（唯一调用方在旧挂起卡）。块分派后新行号见 §1 表。
- A9'①③⑤ DOM 零命中断言绿（`表单已取消`/`表单已提交，等待回执`/`表单状态已丢失`）；④「会话已失效」由段状态 tag 承接（v1 镜像夹具用例绿）。

## 5. A4' 落点与文案留证（§11.1-5）

- 写点：`stores/ai.ts:1358-1380`（`cancelGenerativeForm` 失败分支）；patch 形状逐字（D-C2）：
  `{ toolCallId, name: existing.name, kind2: existing.kind2, requiresAction: false, reason }`，第三参 `createIfMissing=false`；**不经 `applyPendingTerminal`**。
- 文案（D11 钉死，同源自 `form.error`，无第二份字面量）：`取消回灌未送达（{outcome.message ?? '网络异常'}），后端将在超时后自动收尾`；`auto=true` 前缀「表单已超时，」沿用。
- DEV 警告（未命中段时）：`[批4④ A4'] 取消回灌失败：未找到 toolCallId=${form.toolCallId} 的工具段，reason 未落段（不新建段）`。
- 段 reason 实际样例（GREEN-m2t2a / CDP 形态2 实证）：`取消回灌未送达（网络异常），后端将在超时后自动收尾`；渲染位 = 工具卡 `原因：` 行（段驱动无条件渲染 ⇒ activeForm 七处清空不影响可见性）。

## 6. D8 改写留证（§11.1-6）

- 改写前（原脚本，HEAD 提取）：`SMOKE_SKIP_MODEL=1` 退出码 **1**（`RED-m2t2a.log`：断言② FAIL `formRendererCount:0` + FATAL）。
- 改写后：`SMOKE_SKIP_MODEL=1` 退出码 **0**（`GREEN-m2t2a.log`：②③③b 全 PASS，断言① skipped）。
- `cancelledStatusTags` **取法逐字**：`[...document.querySelectorAll('body *')].filter(e => e.children.length === 0 && e.textContent.trim() === '已取消').length`（全文档范围、叶元素、文本恰等于「已取消」）。**实际值**：阶段1（pending 态）= **1**（任务中心 JobProgress 挂件常量文案，与工具 tag 无关——特异性局限如实申报）；阶段2（取消后）= **3**（Δ+2 = 双消息逐段出卡两枚状态 tag）。底线 `>=1` 达成，且与段态判据 `cancelledSegments===2` 互补。
- 退役仍否决的实证依据（复述落行）：`scripts/chat-panel-smoke.mjs` 与 GF 链路零重叠（`FormRenderer|gf-submit|pending-card|tag-form-cancelled|generative_form` 命中 0，落稿棒口径复核一致）——退役 = 丢仓内唯一 GF 链路 CDP 证据面。

## 7. 已知代价与未闭合面（§11.1-7）

1. **A9'④ v1 镜像组合面**：夹具用例（`pendingCall.status='pending' × 段 status='expired'`）**实测可达**于渲染层（手搭消息直接渲染），卡面经段状态 tag 显示「会话已失效」✅——非死码，用例锁绿。
2. **G-C9 挂账**：`AiPanel.vue` 宿主候选 `'approved'` 死判据**未动**（D2 保留面）；段派生投影下 `pendingStatusOf` 输出域不含 `approved` ⇒ 不可达；去向 = 后续清理批/轮询治理一期，**R 编号请提交官取 R-145**（全新编号）。
3. **③b 闸零触碰**：TS-8 留证（§2）。
4. **`docs/evidence/` 引用域不动作**：A10' 口径见 §8-3/§8-7。
5. **E2E 后端 jar 说明**：`backend/target/config-mgr.jar` 文件 mtime 虽为 2026-10-11 20:20，但**构建时点晚于该时刻**——其后两次 backend 提交中的 `c3f2579`（21:48:05）**改了主码** `RedisChatMemoryRepository.java`（saveAll 空列表分支改 Lua 委托），且 jar 内该类字节码**已含** `deleteByConversationId` + `DEL+SREM` Lua 脚本特征串 ⇒ jar 实为**包含该改动的新构建**（定性更正：复核 m-6 实证，原「均为测试类改动、不动主码」陈述有误，方向为保守侧错误）。主码与 HEAD 一致、E2E 有效的结论不变（复核用同一 jar 复跑 PASS=28/GFSKIP=0 旁证）。本批零后端改动，未跑 `mvn`（按复核建议口径订正，不补构建）。
6. **E2E 环境副作用清退**：后端运行时生成仓根 `data/`（H2 库 + files），已删除，工作树恢复"仅本批 6 文件"。

## 8. 台账承接清单逐项落行（不打包）

1. **R-140①-1**：已兑现——A4'+A1' 落**段 `reason` 位**（复用工具卡原因行）+ 模板合并闭合；文案钉死见 D11/§5。可销记。
2. **R-140①-6**：已消解——A6' 随 A5' 受控化（原生 `<details>` 删除，展开态读 store）；**持久化范围 = 会话内**（D4：sessionStorage 未做，内存态随 resetSession 清空）；「手动收起」为 D-C6 新增验收面（TS-1⑩ + 面板 D6② 用例）。可销记。
3. **R-139③ 余脉（A10'）**：引用域口径 = 代码/测试域；实读计数（基线）= `m2t2a-smoke.mjs` **3 处**（`:53` 常量 + `:39`/`:166` 注释）；`frontend/` **0 处**；`docs/evidence/` **7 份 16 处**（只读不动）；`docs/` 根 1 份 2 处（M2-排期计划，不动）。**施工后**：代码/测试域 **0 处**（常量与断言随 D8 改写删除）——仓内彻底消失。
4. **m2t2a 登记销记**：按 D8(A) 改写落行（范围 = 断言③+③b + countExpr 段态化 + injectExpr 注入法修正），③b 登记项销记；退役否决实证见 §6。
5. **R4-1~R4-5 对应**：R4-1→D4/A6'（TS-1⑨⑩ + A6' 重挂载用例）；R4-2→TS-1⑤（成员 kind 闸 + DEV console.error）；R4-3→D5/TS-1⑥（blockId=首成员稳定 id）；R4-4→D7/TS-1③/CDP 形态4；R4-5→D6/TS-1⑦（强制展开 + 手动收起不生效）。
6. **A8' 销记**：重投路渲染锁已改**自承 RED**（TS-3② 变异法，§2），不再依赖 `frontend-tool-result-terminal` 的互证。
7. **`tag-form-cancelled`**：本批后仓内（代码/测试域）彻底消失（§8-3）。
8. **G-C9 挂账**：见 §7-2，**R-145 新编号待取**。
9. **计数口径订正（G-C4+D-C4+D-C11）落行**：① 施工前 `pendingCall` 消费面 = `frontend/src` **21 行/26 次**（纯代码 18 + 注释 3）+ `scripts` 域 **1 处**（m2t2a 原 `:174`）+ `frontend/tests` 夹具面（deriveProjections 保留面，不计消费面）；**施工后** = `frontend/src` **12 行/13 次** + scripts 域 **0 处**（收窄 9+1 行全在 A1' 删除清单与 TS-5 改写内）；② 仓库根无 `package.json`（"根 package.json 零命中"的准确表述）。
10. **行号精度订正（U-7+A-4）**：本棒全程按卡 §12 新行号（pendingToolCall `:461-476`、restoreSuspendedSnapshot `:725-817`、runFrontendTool `:1852-1908`、activeForm=**7 处**、assert `:183-187`、GFSKIP Say `:2351`）——S0 复取零漂移，无需再订。
11. **G-C10 锚点消歧**：D10 排除项口径 = 「`AiPanel.vue` GF 提交窗口（原 `:247-258`）」——本棒 FormRenderer 宿主条件逐字保留（现块分派两分支内各一份，条件字符串与原 `:238` 一致）。
12. **不作销记项（保护面不虚高）**：① 未决双卡残余 R-141①——**本批未动**；② loadHistory 整替丢真 R-144（后端批）——**未动**；③ 22 处字符串⇒ok R-84——**未动**；④ M7/M8/M9 R-143——**未动**；⑤ ③b 闸——零触碰（TS-8）。
13. **R-140①-5（复制按钮位置目视）**：CDP 截图中复制按钮未列为断言项，**未销记**（如实写；③b 亦未销记）。

## 9. 偏差逐条申报（Ruling）

- **R-1（D-C9 隔离规则）**：TS-7 配对断言要求「2 个 requiresAction 段 ⇒ 2 个独立 bare 组」⇒ `groupParts` 采用** requiresAction 段独立成组（前后断组）**。这是 A5' 规则①（相邻即合并）与 D-C9 配对断言的折中，以卡内两条终裁的**配对断言**为准。代价：HITL 卡永不与他段同组（确认门/GF 卡恒直渲——CDP 形态1c 实证，与 D7 视觉口径一致）。
- **R-2（bare 判定扩到 reasoning 单段）**：D7 条文只提 tool；若单 reasoning 段套壳会出现"组头+思考头"双折叠控制 ⇒ `bare = parts.length === 1`（不限成员类型）。代价：无（思考链 UX 与旧 `<details>` 等价）。
- **R-3（空 text 段不打断组）**：空正文段模板不渲染（`v-if="part.text"`）⇒ 视觉相邻即同组，`groupParts` 跳过空 text 且不打断。与渲染真值一致；卡未明文，取实现一致性优先。
- **R-4（m2t2a 改写面扩到 injectExpr，纠正卡推断）**：卡的"静默双红"静态推断**不完整**——实测原脚本对新前端 **断言②也红**（根因：R2/R6 轮次归属守卫（`ai.ts:1552`）使 T2b 时代注入法 `handleFrame(FRAME, m.id)` 被整帧丢弃，`activeRoundHostId` 未登记 ⇒ 非本轮回滚）。若只改 ③/③b，D9 锁「改写后退出码 0」**不可达** ⇒ 注入法修正（不传 assistantId、逐条 append 后立即注帧，借 `currentAssistantMessage()` 归属当前轮）属"修复失效断言"的必要组成；**未改判据掩盖失败**（③ 判据按卡钉死，② 判据原样）。
- **R-5（cancelledStatusTags 特异性局限）**：全文档取法会命中任务中心 JobProgress 常量「已取消」（阶段1 基线 1）；与段态判据互补后仍满足 G-C1 底线（防"段态对但卡面错"的卡面全丢形态）。取法与实值已逐字落 §6。
- **R-6（A9'② 无独立零命中断言）**：旧渲染位（`:254-257`）在旧代码下亦不可达（恒假链，卡 A4' 条已载）⇒ "旧文案零命中"在新旧 DOM 下均成立，不能作 RED 主体；② 的消解由 TS-3 的 RED/GREEN 承载（卡原文允许："旧位不可达不构成 RED 主体"）。
- **R-7（TS-1 RED 取法）**：红在导入（文件不存在），按卡申报两取法之一，未用"先建空壳再断言红"。
- **R-8（A8' 变异体选择）**：选 testid 门控做恒真变异（直接命中"挂起卡复活"语义），重投/新宿主/历史整替 4 例同时红，恢复后 diff 逐字核验。
- **R-9（E2E 首跑失败定性）**：首跑 FAIL=27/GFSKIP=1 系**环境未备**（后端/桩未起，HTTP 空响应），非产品回归；带全环境（Redis 已在 + 自起桩:18399 + 后端:18330 指向桩 + `-BackendLog`）复跑 PASS=28/FAIL=0/GFSKIP=0。后端 jar 时滞说明见 §7-5。
- **R-10（store 测试落点）**：`expandedBlocks` 写读/清空用例落于 `ai-panel-parts-render.spec.ts`（`blocks.spec.ts` 保持纯函数直测口径），夹具复用 mountPanel。

## 10. 双基线记录（§11.1-9）

- `SMOKE_SKIP_MODEL=1`：改写前退出码 **1**（`RED-m2t2a.log`）→ 改写后退出码 **0**（`GREEN-m2t2a.log`）。
- `yarn test` 实跑：S0 基线 **125**（11 files）→ 提交1 后 **131** → 提交2 后 **152**（12 files：新增 `blocks.spec.ts`；`ai-panel-parts-render.spec.ts` 7→19 例）。静态 `it(`：113（基线）→ 140（+15 blocks +12 面板净增）；运行时以实跑 152 为准（含 `it.each` 12）。

## 11. 验收对照（§10）

| 项 | 结果 | 证据 |
|---|---|---|
| V1 块分派形态 | ✅ | TS-1①② + CDP 1a/1b/4 |
| V2 展开态三态承诺 | ✅ | TS-1⑦⑧⑨⑩ + 面板 D6②×2 + D-C1 + CDP 1b/3 |
| V3 单卡不变式 | ✅ | 三路锁全绿（计数口径未放宽）+ G-C5 内容断言（重投路 `probe.tool` 于 DOM） |
| V4 GF 取消失败文案 | ✅ | TS-3 RED→GREEN + CDP 2/3 |
| V5 m2t2a 有效 | ✅ | TS-5（退出码 0，判据未掩盖） |
| V6 仓内三连 | ✅ | TS-4（152/152 + build ✓ ×2 提交） |
| V7 E2E 桩 | ✅ | PASS=28/FAIL=0/GFSKIP=0 |
| V8 CDP 形态 | ✅ | 6 截图 + 配方 + 复跑纪律（§3） |
| V9 红线零触碰 | ✅ | TS-8 sha256/diff |
| V10 锚点复取 | ✅ | §1（零漂移）+ diff sorted + sha256 |
| V11 台账承接 | ✅ | §8 十三项逐项 |
| V12 报告自证 | ✅ | §0（wire 实证，未照抄卡头署名） |

---

## 12. 未做/未复验事项（H7）

- 未跑 `mvn`（本批零后端改动，卡明示不要求）。
- 断言①（m2t2a 真实模型挂起轮）未跑——需真实 AI key，本环境仅有桩；SKIP_MODEL 模式按设计跳过（卡口径）。
- CDP 形态2/3 的回灌失败为页内替身 outcome（非真实断网）；真实断网路径与替身共享同一 outcome 形状（`{ok:false, message}`），未单独构造网络级故障。
- `docs/evidence/` 7 份 16 处历史引用未改写（A10' 口径：只读不动）。
- 机械活未下沉（§0），无子智能体自证事项。
