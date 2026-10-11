> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 三方会审评议报告（GLM-5.3，复核视角），署名信息以文内为准；本件为 DC-19 后产出的仓外过程件，脱敏后入库，源文件未改）

# 会审报告 · 批4④ 施工卡草案评议（GLM-5.3 · 复核与测试视角）

| 项 | 值 |
|---|---|
| 执行者署名口径 | **DC-19** |
| **实际执行模型（实证，非配置推定）** | **`Zhipu AI/glm-5.3`** —— 自证方式 = 读本会话 `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_e83f23b1-d6ee-4fdf-80c6-ae871e9d17de\agents\main\wire.jsonl`：`llm.request` 记录 3 次，每次均含 `"modelAlias":"Zhipu AI/glm-5.3"`、`"model":"glm-5.3"`、`"thinkingEffort":"high"` |
| CU 会话 id | `session_e83f23b1-d6ee-4fdf-80c6-ae871e9d17de` |
| 授权来源 | **K3 指挥官三方会审派单 · 批4④ 施工卡草案评议（GLM-5.3 · 复核与测试视角）**（本卡） |
| 评议对象 | `<SCRATCH>\chat-timeline-pkg\批4④-施工卡草案.md`（485 行 / 83,334 B；sha256 `d6835ebcb0404c93d7f17e33020984e1efbc6a38914a0b4424ab851dc5a57e82` 逐字符复核一致【实测】） |
| 实读基线 | 主仓库 `<MAIN_REPO>`，HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`、分支 `main`、`git status --porcelain` 空【实测，两路独立核验】 |
| 纪律申报 | 主仓库**零写**、零 git 写、未读任何 `.env`、未启前后端/dev server、未跑 yarn/mvn/E2E/CDP；对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` **零操作**；唯一写入 = 本报告。**机械活下沉情况**：三只只读探索子智能体（`agent-536` AiPanel+测试锚点 / `agent-537` ai.ts+parts.ts+reason 域 / `agent-538` 脚本+文档基线），派发时**显式钉 `model=DeepSeek/deepseek-flash`**；实际模型自证 = 读各自 `agents/agent-53{6,7,8}/wire.jsonl` 的 `llm.request`，`"modelAlias":"DeepSeek/deepseek-flash"` 命中分别为 8 / 14 / 19 次【实测】。关键判断（A4' 恒假链、断言③ 失效链、D2~D10 立场裁断）由本棒亲读完成 |
| 证据分级 | 【实测】= 本棒或下沉子智能体直接读到原文；【推断】= 由实读事实按代码路径推出（未实跑）；【假设】= 未经核验的前提 |

---

## 1. 总体结论

# **带条件可施工**

- 草案锚点质量**显著高于既往批次**：本棒独立抽验 20+ 项锚点，**行号失锚 0 处**；§6 锚点表 66 行经三路交叉抽验基本全中。
- 十个决策点（D1~D10）**无一须推翻**；四处冲突申报（C-1~C-4）的处置建议方向全部正确。
- 但存在**一个草案未登记的实质性测试面漏洞**：`m2t2a-smoke.mjs` **断言③ 也已静态失效**（不止草案登记的 ③b）——这直接影响 D8 改写范围与 D9 锁的完整性，列为必做条件 C1。
- 另有两处计数/域订正（ToolPart.reason 写点 2→3、docs/evidence/ 引用 8→7）与一处 A4' RED 形态精确化，均不改立场、只改执行口径。

---

## 2. 逐决策点评议（D1~D10）

### D1 合批口径（A1'/A4'/A5' 合批）—— **同意**【实测】

三项落点重叠亲读确认：A1' 在 `AiPanel.vue:179-266`、A4' 旧渲染位在 `:255-257`、A5' 改造区 `:166-267`，同属一个 `v-for` 区【实测】。③b 移交义务实证：`决策卡-批3③b.md:117` K3 终裁原文「① 模板合并**移交批 4④**」【实测】。

**对 §10-5③（拆两次提交）的表态**：建议采纳（条件 C6）——批内拆「结构合并 + 锁改写」与「折叠规则接入」两提交，每提交独立过 D9④ 三连，回归面各减半；不拆也可控（见下），故列为建议而非必做。

**回归面实测评估（利好）**：7 例渲染锁的真实改写面比草案估计**更小**——`:119`（N10 复制判据，锚点 `:269-293` 在 `v-for` 区之外）与 `:134`（isTyping，判据不涉 `:166-267`）**零改动**；`:75`（段序）在 D7「单元素组不套壳」下 DOM 序保持、断言大概率原样保留【推断】；必改写仅 `:94`（单槽跟随）1 例 + 三路锁适配 3 例。⇒ 回归面可控，D1 推翻条件(1) 未触发。

### D2 挂起卡数据源改段驱动 —— **同意（含 C-3 处置），核实基本充分、三处订正**

草案 19 处消费面分布经独立 grep 复核**成立**（模板 9 / 组件 script 2 / store 代码 6 / types 注释 1 / parts 派生写点 1）【实测】，另有三处口径订正（详见 C4、F-6）：
1. `stores/ai.ts` 另有 **2 条纯注释行**（`:1710`、`:1799`）含 `.pendingCall`，无语义读点，不影响结论【实测】；
2. 草案普查域仅 `frontend/src`，**漏了 scripts 域**：`scripts/m2t2a-smoke.mjs:174`（countExpr 的 `pendingStatuses` 读 `m.pendingCall`，注入浏览器执行）【实测】——该处将随 D8 断言③ 改写（C1）一并段态化，正好闭环；
3. `frontend/tests/` 夹具大量读写 `.pendingCall`（6 个 spec），属投影保留面，定稿口径应显式写明「普查仅算 `frontend/src`」。

**对 §10-1②（`:482`/`:730` 是否也应改段驱动）的表态**：草案保留立场**正确且理由充分**。`:482`（formHostMessageId）与 `:730`（滚动指纹）读的都是 `deriveProjections` 保留不动的同一投影（`parts.ts:178-217` 亲读确认），零迁移风险；且 `:484` 宿主候选集含 `'approved'`，而段派生投影的 `pendingStatusOf`（`parts.ts:106-127`）**从不产出 `'approved'`** ⇒ 若改段驱动须先清死判据（见 F-4），不宜本批顺手做。`:730` 同理。

### D3 A4' 落点 = 段 reason 位 —— **同意，且草案一处【假设】可升级为【实测】**

**恒假链独立复现**【实测，静态逐行】：`AiPanel.vue:703`（`onFormCancel → ai.cancelGenerativeForm()`）→ `ai.ts:1344`（`applyPendingTerminal(form.toolCallId, 'cancelled')`）→ `ai.ts:1775-1776`（任何 `requiresAction` 段必被收口：覆盖域第三子句 `|| part.requiresAction` 保证）→ `ai.ts:1777` + `parts.ts:216`（`pendingCall = null`）→ 外层门控 `AiPanel.vue:201` 恒假 ⇒ `:255-257` 渲染位不可达。草案 A4' 判断正确。

**对 §10-2 三问的表态**：
- **②（③a M4 同域性）——草案【假设】可升级为【实测】：不同域、不冲突**。`FrontendToolExecution.reason`（`types/tools.ts:142`）在生产代码**零读者**：`runFrontendTool`（`ai.ts:1872-1905`）只读 `execution.outcome/result/availability`，`:1893-1900` 的段补丁**不含 reason** ⇒ 该字段与 `ToolPart.reason` 零传播【实测】。D3(A) 立场因此加固。附注：`frontend/tests/frontend-tool-outcome.spec.ts:50` 有一个测试域读者，「零读者」应表述为「**生产零读者**」【实测】。
- **①（同域共用是否误导）**：会。`ToolPart.reason` 现有**三个**活写点（草案记两个，漏 `ai.ts:188` migrateMirror 的 `reason: run.reason ?? null`【实测】），语义 = 拒绝/失败/迁移残留原因；混入「取消回灌失败」若无前缀将不可区分 ⇒ **结构化前缀升为必做**（C2）。
- **③（是否加结构化前缀）**：是，必做（同上）。

**D3 推翻条件(2) 的预判**【推断】：GF 调用是单工具段时，D7 单元素组不套壳 ⇒ 工具卡直渲、`:195` reason 行恒可见，推翻条件(2) 在最常见形态下不触发；但 GF 段若嵌入多工具相邻组（组完成后默认折叠），reason 会被折叠隐藏——留 C8 建议（组头摘要或强制展开策略 + CDP 冒烟补该形态）。

### D4 展开态存储 = 内存 store 只增不减 —— **同意**【实测】

规则②原文括注「若入 sessionStorage」亲读确认（`施工卡-刷新版.md:490`）⇒ 刷新持久化是可选项；R-140①-6 台账原文（`docs/M2-排期计划.md:329`「`<details>` 展开态仍不持久化（属 R4-1、④批范围）」）亲读确认，草案「会话内持久即满足」的解读成立，且 §8-2 已要求销记口径写明范围 = 会话内。镜像膨胀论据（`writeMirror` 7 字段、无 appliedSeqs，`ai.ts:291-300`）经核验属实【实测】。

### D5 blockId 稳定性（业务 id，禁下标）—— **同意**【实测】

`upsertToolPart` 就地 splice 不重排（`parts.ts:93-95`）、新段 push 追加（`:101-102`）、`sealTrailingStreamPart` 只封口不迁移【实测】⇒ 组首成员锚定在新帧到达时稳定，草案机制论证成立。推翻条件(1)（跨消息同 id 加消息前缀）保留即可。

### D6 默认展开与 requiresAction 耦合 —— **同意**【实测】

R4-5 逐字亲读（`施工卡-刷新版.md:675`「`requiresAction === true` 的组必须强制展开…验证：确认门等待期间组一定是展开的」）。FRONTEND `pending` 亚 tick 瞬态（R-139 行尾 DS Pro M-2 订正，`M2-排期计划.md:328`）亲读确认，草案推翻条件(1) 的特判预案方向正确。

### D7 单调用组不套壳（LibreChat 规则）—— **同意**【实测】

`施工卡-刷新版.md:484`（「单调用组**直接展开该卡**，不套壳」）与 `:674`（R4-4）逐字亲读一致。

### D8 m2t2a-smoke 改写（不退役）—— **同意方向，改写范围须扩大（必做 C1）**

- **退役不成立的实证加固**：`chat-panel-smoke.mjs` 13 断言（`:454①…:797⑫`）与 m2t2a-smoke 的 GF 表单链路**零断言重叠**——全脚本 `FormRenderer|gf-submit|pending-card|tag-form-cancelled|generative_form` 命中数 = 0【实测】⇒ 草案 D8 理由①「m2t2a 是 GF 链路唯一 CDP 冒烟」**经核验为真**，「已被替代 ⇒ 可退役」路径被封死。
- **★ 草案遗漏：断言③ 也已静态失效**（详见 F-1）：`m2t2a-smoke.mjs:174` `pendingStatuses` 读 `m.pendingCall`，取消链把它置 null（同 D3 恒假链）⇒ 恒空数组 ⇒ `:297` `length === 2` 恒假。失效面 = **③ + ③b 两条**，改写范围必须同时覆盖（段态判据：该 toolCallId 全消息域段 `status==='cancelled'` 且无 `requiresAction===true`）。
- **对 §10-3②（新判据是否够强）**：`formRendererCount===0` + 段态无 requiresAction **足够**；建议再加一条强判据——合并卡上渲染「已取消」状态 tag（`toolStatusLabel('cancelled')` = 「已取消」，`AiPanel.vue:544-560`）【实测】，防「段态对但卡面错」的漏网。

### D9 测试锁六条 —— **覆盖③b 移交三要求，两处须精确化（C1/C2），一处口径以实跑为准**

对照 ③b 移交测试要求【实测，`施工报告-批3③b.md:172-182` 第 7 条原文核验】：① m2t2a 改写 → D8/D9⑤ 覆盖（但锁行未显式列出全脚本退出码，C1 补）；② 重投路渲染锁自承 RED → D9③② 覆盖（但 RED 形态须精确化，C2）；③ testid 引用域口径 → A10' + §8-7 覆盖。**逐项覆盖成立**。

- **C2（RED 形态）**：D9③ 现文「旧渲染位不可达的反向留证（RED）」表述有歧义——断言「旧位不可达」是恒绿断言（现在不可达、施工后也不可达），**不构成 RED**。正确形态（循 ③b D14「夹具就位仍红」）：夹具 = `cancelGenerativeForm` 失败分支全就位（activeForm 存续 + 持该 toolCallId 的段 + 回灌失败），断言对象 = **「取消回灌失败语境文案对用户可见」**——施工前该文案只存在于 `form.error`（不可达）而 notice 只有原始错误串（`ai.ts:1222-1224`，无「取消回灌」语境包装）⇒ 断言必红；施工后段 reason 位渲染 ⇒ 绿【推断，RED/GREEN 判据链已实读核验】。
- **例数口径**：`it(` 计数实测 113（11 spec：7/5/9/30/5/15/22/4/3/4/9）【实测】，草案 §6-57 自记的 110 vs 113 分歧维持「S0 以 `yarn test` 实跑为准」处置，正确。
- **对 §10-5②（testid 移位是否削弱三路锁）**：不削弱。三路锁第一半断言 `pending-card-*` 计数 = 0，语义是「无挂起卡」；门控（仅 `requiresAction===true` 挂 testid）后「无 requiresAction ⇒ 无 testid ⇒ 仍 0」，语义保持；`:94` 例夹具 `requiresAction:true` ⇒ testid 在，断言可平移【实测+推断】。但须防空转（C5）：至少一条锁断言**卡内容**（工具名/状态 tag 文案）而非仅计数，防「卡在、testid 门控 bug ⇒ 计数恒 0 假绿」。

### D10 GF submitting 窗口与宿主条件（重审不扩判据）—— **同意**【实测】

`liveForm`（`AiPanel.vue:465-468`，仅 filling/submitting）与 `formHostMessageId`（`:476-489`，候选限定 `:484` pending/running/approved）亲读确认；「提交后由 `:252`『表单已提交，等待回执』tag 承载」的分工成立。扩判据 (B) 的三条坑论证与实读相符。

---

## 3. 条件清单

### 必做（定稿前/施工卡定稿须落）

| # | 条件 | 关联 |
|---|---|---|
| **C1** | **D8/A7' 改写范围扩大为断言 ③ + ③b**：③ 的 `pendingStatuses` 判据（`m2t2a-smoke.mjs:174`）读 `m.pendingCall`，批2 权威翻转起取消即 `pendingCall=null` ⇒ `:297` `length===2` 恒假。两条一并改段态判据；**D9 锁显式加一行**：「`SMOKE_SKIP_MODEL=1` 下改写后全脚本退出码 0」（现草案只在 D8 验证用例里提了 ③b 一条） | D8、D9、F-1 |
| **C2** | **A4' RED 形态精确化**：RED 断言对象 =「取消回灌失败**语境文案**可见」（施工前红，notice 原始串不含语境包装不构成假绿）；GREEN = 段 reason 位渲染。**结构化前缀必做**（如草案建议的「取消回灌失败 —— …」），因 `ToolPart.reason` 现有 3 写点同域混读 | D3、D9③、F-2 |
| **C3** | **reason 写点计数订正**：`ToolPart.reason` 活写点 = **3 处**（`parts.ts:336` tool_result / `parts.ts:372` confirm_decision / **`ai.ts:188` migrateMirror**），草案 A4'「两个活写点」漏迁移路写点 | D3、F-2 |
| **C4** | **计数/引用域订正落定稿**：① A10'/C-2 的「docs/evidence/ 域 ≥8 份」→ 实为 **docs/evidence/ 7 份 + docs/ 全域 8 份**（`docs/M2-排期计划.md` 不在 evidence/ 子域）；② pendingCall 消费面口径 = 「frontend/src 代码 19 处 + 注释 2 行 + **scripts 域 1 处（m2t2a:174，随 C1 段态化）**」 | A10'、D2、F-6 |
| **C5** | **testid 门控下的锁防空转**：`ai-panel-parts-render.spec.ts` 改写后，三路单卡锁至少一条增加**卡内容断言**（工具名或状态 tag 文案出现于 DOM），不得只依赖 `pending-card-*` 计数 | D9②、§10-5② |

### 建议

| # | 条件 | 关联 |
|---|---|---|
| **C6** | 批内拆两提交：先「A1'/A4' 结构合并 + 7 例锁改写 + RED/GREEN」、后「A5'~A7'/A9'/A10' 折叠规则与死位清除 + blocks.spec.ts」，各提交独立过 D9④ 三连 | D1、§10-5③ |
| **C7** | D9② 改写边界缩小化：`:119`/`:134` 零改动（不触 `:166-267`）、`:75` 预期原样保留（施工棒实跑确认即可）、必改仅 `:94`；避免无差别重写引入新风险 | D9②、F-8 |
| **C8** | GF 取消失败 reason 在**多工具折叠组**内的可见性（组头摘要或强制展开）留实现者取舍，CDP 冒烟除两张必含截图外补该形态一张 | D3 推翻条件(2) |
| **C9** | 登记（不修）：`AiPanel.vue:484` 宿主候选 `'approved'` 判据在段派生投影下不可达（`pendingStatusOf` 永不产出 approved），属遗留死判据，去向 = 后续清理批/轮询治理批 | F-4 |
| **C10** | 草案 D10「决策卡 D2 的排除项 `:254-256`」锚点消歧：决策卡 `:254-256` 实为 D16 第 1-3 条内容，「排除项」裁决在 `:258`；逐行排除清单实体在 `批3③b-施工卡.md:349-355`（其 `:254-256` 指 AiPanel.vue 的 GF 提交窗口）。定稿改为「AiPanel.vue GF 提交窗口（`:247-258` 现值）」口径 | D10、F-5 |

---

## 4. 自由发现（草案之外）

| # | 发现 | 证据 | 分级 |
|---|---|---|---|
| **F-1** | **m2t2a 断言③ 静态失效（草案只登记了 ③b）**。链：`AiPanel.vue:703` onFormCancel → `ai.ts:1344` applyPendingTerminal → `:1775-1776`（`\|\| part.requiresAction` 保证任何挂起段被收口）→ `parts.ts:216` pendingCall=null ⇒ `m2t2a-smoke.mjs:174` `pendingStatuses` 恒空数组、`:294` 空数组 `every()` 立即真提前 break、`:297` `length === 2` 恒假。该脚本若现在实跑，**退出码 1（③ 与 ③b 双红）**，「静默假绿」实为「静默双红」——退役更不可取，改写必要性更强 | 见上文件:行号 | 链【实测】；「实跑必红」【推断】（未实跑） |
| **F-2** | `ToolPart.reason` 第三个写点 `ai.ts:188`（migrateMirror：`reason: run.reason ?? null`）；另核得 `toolRuns[].reason` 亦无 UI 读者（唯一读点 = 同一迁移行）——D3「复用 `:195` 零新增结构」论证仍成立 | `ai.ts:188`、`AiPanel.vue:195`、`parts.ts:199` | 【实测】 |
| **F-3** | ③a M4「execution.reason 零读者」与 `ToolPart.reason` **不同域**（生产零读者实证：`runFrontendTool` 段补丁不含 reason）；草案 A4' 的【假设】可销——D3(A) 无冲突 | `types/tools.ts:142`、`ai.ts:1872-1905`、`ai.ts:1893-1900` | 【实测】 |
| **F-4** | `AiPanel.vue:484` 宿主候选 `'approved'` 死判据：`pendingStatusOf`（`parts.ts:106-127`）输出域不含 approved；migrateMirror 后投影经 `deriveProjections` 重算 ⇒ 该比较支不可达。本批不动（D2 保留面），登记 C9 | `AiPanel.vue:484`、`parts.ts:106-127` | 【实测】 |
| **F-5** | 草案 D10 的「`:254-256` 排除项」引注错位到决策卡 D16 区（见 C10） | `决策卡-批3③b.md:254-258` | 【实测】 |
| **F-6** | pendingCall 消费面普查漏 scripts 域（`m2t2a-smoke.mjs:174`）；草案 §11-④ 自记「未核对 apps/ 目录」部分兑现——`frontend/dist/` 有 1 处打包产物命中但属 gitignore 非源码 | `m2t2a-smoke.mjs:174` | 【实测】 |
| **F-7** | 微行号订正（不影响结论）：m2t2a `assert` 函数实为 `:183-187`（`:188` 空行）；`verify-e2e.ps1` GFSKIP 的 Say 语句实在 `:2352`（`:2346` 为摘要块首行）；决策卡 D1 块完整区间 `:115-121`（草案引 `:117-120` 实质成立，K3 终裁正文起点 `:117` 核对无误） | 各文件对应行 | 【实测】 |
| **F-8** | 草案 D9②「4 例同步改」高估：`:119`（锚点 `:269-293` 在 v-for 区外）与 `:134`（判据不涉该区）零改动；`:75` 在 D7 下 DOM 序保持、预期原样过——回归面比草案写的更小（利好 D1 合批） | `ai-panel-parts-render.spec.ts:75/:119/:134` | 【实测】（锚点）+【推断】（:75 原样过） |

---

## 5. 锚点抽验记录（本棒亲读 + 三路下沉交叉核验，≥10 项）

| # | 锚点 | 判定 | 证据 |
|---|---|---|---|
| 1 | 草案指纹（sha256 / 大小 / 行数） | **PASS** | `d6835ebc…57e82` 逐字符一致；83,334 B；485 行【实测】 |
| 2 | 仓库基线（HEAD / 分支 / 工作树） | **PASS** | `296ffe2ae2…`、`main`、porcelain 空【实测，两路独立】 |
| 3 | `AiPanel.vue:166-169` v-for + key | **PASS** | `v-for="part in m.parts"` + `:key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg"`【实测亲读】 |
| 4 | `AiPanel.vue:174-177` 原生 `<details>` | **PASS** | `:174` details / `:175` summary「思考过程」/ `:176` max-h-40 / `:177` 闭合【实测亲读】 |
| 5 | `AiPanel.vue:179-196` 工具卡（`:181` 无 testid、`:195` reason 红字） | **PASS** | 逐行亲读；`:195` `原因：{{ part.reason }}` + `text-[#f56c6c]`【实测亲读】 |
| 6 | `AiPanel.vue:201` 挂起卡门控 + testid | **PASS** | `v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"` + `:data-testid="'pending-card-' + …"`【实测亲读】 |
| 7 | `AiPanel.vue:212-259` CONFIRM 块 / GF 分支 / `:238` 宿主 / `:251-253` 三 tag / `:255-257` 旧渲染位 | **PASS** | 逐行亲读，与草案 §6 行 9/10/12/13 逐字吻合【实测亲读】 |
| 8 | `AiPanel.vue:465-489` liveForm / formHostMessageId | **PASS** | `:467` filling/submitting；`:484` pending/running/approved【实测亲读】 |
| 9 | `AiPanel.vue:730` 滚动指纹 + `:741-745` watch pendingConfirm | **PASS** | 亲读 `:715-754` 区间【实测亲读】 |
| 10 | `parts.ts:178-217` deriveProjections（`:202`/`:206-210`/`:216`） | **PASS** | 亲读全文段；`pendingCall` = 最后一个 requiresAction 段【实测亲读】 |
| 11 | `parts.ts:326-328` / `:372` reason 写点 | **PASS** | 亲读；**另发现第 3 写点 `ai.ts:188`（见 F-2）**【实测】 |
| 12 | `ai.ts:1336-1354` cancelGenerativeForm 恒假链 | **PASS** | `:1341`/`:1344`/`:1345`/`:1346`/`:1351-1353` 逐行亲读【实测亲读】 |
| 13 | `ai.ts:1765-1780` applyPendingTerminal | **PASS** | `:1775` 三子句覆盖域 / `:1776` / `:1777` 亲读【实测亲读】 |
| 14 | `ai-panel-parts-render.spec.ts` 7 例结构 + 三路锁计数断言 | **PASS** | 全文亲读；`it(` 于 `:75/:94/:119/:134/:165/:186/:217`；三路锁均 `[data-testid^="pending-card-"]` `toHaveLength(0)` + 段数 `toHaveLength(1)`【实测亲读】 |
| 15 | `m2t2a-smoke.mjs` `:53` 常量 / `:176-180` 计数 / `:285/:297/:299/:346/:457` 断言点 / `:299` 原文 | **PASS** | 亲读 `:28-72`/`:150-189`/`:280-309`；断言调用 5 处全中【实测亲读+子核】 |
| 16 | `types/ai.ts:243-268` ToolPart 形状（reason/requiresAction/timeoutSeconds） | **PASS** | 亲读【实测亲读】 |
| 17 | `施工卡-刷新版.md:475-499` §4.4 三规则 / `:669-675` R4-1~R4-5 | **PASS** | 亲读，三规则与 R4-5 逐字与草案引用一致【实测亲读】 |
| 18 | `pkg-health/体检报告.md` §9（批4④ 前置刷新注记） | **PASS** | 亲读 `:569-629`；114 刷新/3 失锚/§9.3 关键现值与草案用法一致【实测亲读】 |
| 19 | `frontend/src/utils/blocks.ts` 不存在 + `expandedBlocks\|groupParts\|blocksOf` 全仓零命中 | **PASS** | 【实测，子核】 |
| 20 | `frontend/tests/` 11 spec + `it(` 总数 | **PASS** | 113（7/5/9/30/5/15/22/4/3/4/9）【实测，子核】；与草案 §6-57 自记一致 |
| 21 | `tag-form-cancelled` 引用域：frontend/ 0 处、`m2t2a-smoke.mjs` 3 处、docs/evidence/ 文档数 | **PASS（计数订正）** | frontend/ 0【实测】；脚本 3 处（`:39`/`:53`/`:166`）【实测】；**docs/evidence/ = 7 份、docs/ 全域 = 8 份**（草案「≥8」口径scope 不严，见 C4）【实测，子核】 |
| 22 | ③b E2E 基线（PASS=28/GFSKIP=0/e2e_exit=0）与 CDP 证据文件存在性 | **PASS** | `批3b-evidence/E2E-stub-final.log` 末段 + `cdp/20261011T182213Z-*` 两 png + result json【实测，子核】 |
| 23 | `chat-panel-smoke.mjs` 13 断言及其与 m2t2a 重叠度 | **PASS（重叠度订正）** | 13 具名断言（`:454①`…`:797⑫`）；**与 m2t2a GF 链路零断言重叠**【实测，子核】 |
| 24 | 草案 A7'「断言③b 是唯一失效断言」 | **FAIL** | 断言③ 亦静态失效（`:174`/`:297`，见 F-1）——本会审核心新发现【实测（静态链）】 |
| 25 | 草案 A4'「`ToolPart.reason` 两个活写点」 | **FAIL** | 实为 3 处（+`ai.ts:188`，见 F-2）【实测】 |

---

## 6. 会审五问直答（草案 §10）

1. **D2 消费面核实充分吗？** 充分（19 处代码面复核成立），三处订正见 C4/F-6；`:482`/`:730` 保留正确（读保留投影零风险，且 `:484` 死判据须先清才可段化，本批不动）。
2. **D3 取舍？** 段 reason 位正确；③a M4 同域性已由【假设】升级为【实测·不同域】；结构化前缀必做（3 写点同域）；notice 通道确不可作唯一渠道（`:1222-1224` 原始串无语境）。
3. **D8 改写 vs 退役？** 改写。chat-panel-smoke 零覆盖 GF 链路（实测），退役即丢唯一端到端证据面；但改写范围必须扩为 ③+③b（C1），新判据段态 + formRendererCount===0 足够，建议加「已取消」状态 tag 强判据。
4. **D9 是否覆盖③b 移交全部测试要求？** 覆盖（①→D8/D9⑤、②→D9③、③→A10'），两处精确化：C1（锁行补全脚本退出码）、C2（RED 断言对象改为「语境文案可见」——「旧位不可达」是恒绿断言不构成 RED）。
5. **合批回归面可控？** 可控且好于草案估计（F-8：必改仅 `:94` 1 例 + 3 例适配）；建议批内两提交（C6）；testid 门控不削弱三路锁但须 C5 防空转。

---

## 7. 本棒自限

- 未跑 `yarn typecheck|test|build`、`verify-e2e.ps1`、CDP 冒烟；A4'/A9'/断言③ 的「不可达/必红」结论 = **静态实读链**【实测】+「若实跑则红」【推断】。
- `apps/` 目录、后端 Java 文件未逐一面核（本批零后端改动，风险面不在此）。
- 锚点抽验以草案 §6 高风险行（AiPanel/ai.ts/parts/spec/m2t2a/基线文档）为主，66 行锚点表未逐行重跑（抽验 25 项 + 三路子核全覆盖交叉）。

**结论重申：带条件可施工——C1~C5 必做落定稿后即可发施工棒。**
