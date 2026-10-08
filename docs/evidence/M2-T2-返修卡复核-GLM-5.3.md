# M2-T2 返修决策卡复核（GLM-5.3）

> 角色：合流项目复核员。独立复核，不施工、不改代码；全程零 git 写操作，本文档为唯一新增产物，未提交。
> 复核对象：`docs/M2-T2-返修决策卡.md`（T2a-D#1~#5 / T2b-D#1~#3，8 卡已签字）。
> 复核基准：`docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md`（14 条发现）及其 §4 裁决结论（指挥官 K3，14 条处置）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `a71832d`（满足"或更新"前提）。
> 证据等级：**【实测】** = 本次实际读码/读档确认；**【推断】** = 由规格与代码形状推演、未实跑。
> 脱敏：所有路径相对 `<REPO_ROOT>`（不出现盘符）；无密钥、无用户名、无内网 IP。
> 复核日期：2026-10-08。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 闭合核验 | **14/14 条红队发现均有裁决处置与对应卡条款，无遗漏、无偷换** |
| 两阻断（A1/B1）返修路线 | 语义自洽，无结构性新矛盾【实测】 |
| 行号抽验 | 18 处全部命中（HEAD 钉版）【实测】 |
| 新发现问题 | **3 项严重 + 2 项建议**（均为卡内/卡间字段与守卫口径缺口，非路线性矛盾） |
| 总体结论 | **通过（有条件）**：8 卡架构与 14 条处置成立，可继续施工；但 P1（T2a-D#1 守卫与 T2a-D#2 终态单调的字面冲突）等 3 项严重级缺口须以**卡勘误/补注**方式在验收前闭合，不要求重签全部返修卡 |

---

## 1. 逐条闭合表（红队发现 → 裁决处置 → 卡条款）

| # | 红队发现（等级） | 裁决处置（§4） | 对应卡条款 | 闭合判定 |
|---|---|---|---|---|
| A1（阻断） | 自动 reattach 回放与 loadHistory 双重渲染+写后写竞态 | 采纳：放弃全量回放，改"快照重建+增量续收"；loadHistory 先行；不 append 新消息 | T2b-D#3 选择 (b)，被否坑 (a) 点名 A1/A3/B3 | **闭合**（附缺口 P2/P3：增量续收的 lastSeq 来源、快照字段宽度未在卡间对齐，见 §4） |
| A2（严重） | 本地 cancel 与帧终态"谁赢"未定义 | 采纳：帧 status 为最终裁决，本地 cancel 乐观置位；已终态不降级 | T2a-D#2 选择 (b) + 状态映射表 | **基本闭合**——但与 T2a-D#1 守卫字面冲突（P1），按 D#1 字面实现会吞掉 A2 的修复 |
| A3（建议） | runSeq 刷新清零，差量去重失效 | 采纳：随快照方案消解，runSeq 不入镜像 | T2b-D#3 选择 (b)（不依赖回放差量去重） | **闭合**【实测：HEAD `ai.ts:836-843` 确认 runSeq 仅同页签累积，路线变更后该机制不再被依赖】 |
| B1（阻断） | runs/current 在已超时/他端已提交/进程已死三场景无法定位 run | 采纳：新建 session→run 索引 + 结构化返回 + 三分支降级口径 | T2b-D#1（索引，选择 b）+ T2b-D#2（结构化状态+三分支） | **闭合**（三分支与 B1 三场景一一对应；带同源缺口 P2/P3） |
| B2（严重） | 多页签输入互不可见+错配提示未定义 | 部分采纳：不承诺多页签共享（文档化）；filling 时跨窗提示并入 T2a | T2b-D#2 约束声明 + T2a-D#2 附带修复（filling 提示） | **闭合**（两部分处置均有落卡） |
| B3（建议） | reattach 回放瞬时复活已死表单卡 | 采纳：随快照方案消解；另加 openGenerativeForm 防御守卫 | T2b-D#3 被否坑 (a) + T2a-D#3 新增守卫 | **闭合**（守卫的"终态"判定源未细化，记 P4 建议） |
| C1（严重） | cancelled 广播误伤已 succeeded 的 toolRun | 采纳：广播对象写死 pendingCall 投影；双守卫；localTerminal 补 succeeded | T2a-D#1 守卫条款（三条全落卡） | **闭合**——但卡在裁决双守卫之外自行加严为"仅覆盖 pending"，与 A2 处置冲突（P1） |
| C2（建议） | cancelled 域扩充的映射与旧镜像兼容缺口 | 采纳：映射表进规格；PendingStatus 联合补 FRONTEND_CANCELLED；旧镜像非 pending 按终态展示 | T2a-D#2 状态映射表 + 镜像兼容条款 | **闭合**【实测：后端 `PendingToolCall.java:40` 确有 FRONTEND_CANCELLED 常量、HEAD `types/sse.ts:22-31` 联合确缺，映射缺口真实且卡的翻译方向正确】 |
| C3（建议） | pendingToolCall getter 非 pending 提前短路漏扫 | 采纳：跳过非 pending 继续向前扫 | T2a-D#2 附带修复（C3） | **闭合**【实测：HEAD `ai.ts:223-224` 确认短路存在；改法直改 getter 可实现，工作区施工代码已体现】 |
| D1（严重） | 快照 SUSPENDED 但条目已决的中间态误判 | 采纳：终态判定对齐 ResumeService unresolvedExternal 口径 | T2b-D#2（终态判定条款，点名消 D1） | **闭合**【实测：`ResumeService.java` listRuns 内确有 unresolvedExternal 口径（PENDING 且非 BACKEND 过滤），对齐目标存在且可实现】 |
| D2（建议） | session→run 索引成本口径未交代 | 采纳：新 Redis 键 O(1)、TTL 与锁 watchdog 对齐；否决全量扫 | T2b-D#1 选择 (b) + 被否坑 (c) | **闭合**（成本口径完整落卡） |
| E1（严重） | 三域前端逻辑零 CI 门禁 | 部分采纳：维持不引 vitest；CDP 冒烟脚本化三断言；nightly/vitest 评估挂 T4；显式承认已接受风险 | T2a-D#5 选择 (b) + 显式风险承认条款 | **闭合**（三断言与裁决 ①②③ 逐字一致；风险承认留痕明确"签字即确认"） |
| E2（建议） | 服务端 E2E 对前端投影态贡献≈0 的错位 | 采纳：验收矩阵显式标注 | T2a-D#5 风险承认条款 | **闭合** |
| F1（建议） | 三子问题捆绑、D#2 拖累 D#3/D#4 | 采纳：拆 T2a（先行，纯前端）/ T2b（端点语义先定后施工） | 卡头拆分声明 + T2a-D#3/D#4 原样保留 + T2b 独立三卡 | **闭合**【实测：`M2-排期计划.md:10` "每任务包独立可交付"原则与拆分方向一致】 |

---

## 2. 重点抽查记录

### 2.1 A1/B1 两阻断的返修路线在 T2b 三卡中的语义自洽性【实测+推断】

**A1 路线（快照重建+增量续收，T2b-D#3）**：
- 消除双重渲染：不 append 新消息 + 不回放帧，直接掐断 A1 的两个重复渲染源（回放重建工具卡、新增重挂消息）——与 HEAD 现状证据链吻合（`reattachActive` 的问题行为 `ai.ts:527-528`、`replayLocked` 全帧保留 `SseChatEmitter.java:358-375`，均已实测确认存在）。
- 消除写后写竞态：`loadHistory` 先行完成（historyLoaded 前不触发重建）——A1 竞态根因是 `ai.ts:347/373` 的无条件整体替换与 reattach 回放无排序约束，"先行完成"建立了显式顺序，语义自洽。
- 残余缺口为**字段口径级**而非路线级：P2（lastSeq 来源）、P3（pendingEntries 字段宽度），见 §4。两缺口均有明确修法（扩 T2b-D#2 返回结构），不动摇"无全量回放"路线本身。
- 卡自带推翻条件（"快照字段不足以重建挂起卡则升级裁决"）——说明卡作者已预见 P3 类风险，属留痕而非隐瞒。

**B1 路线（索引+结构化端点，T2b-D#1/D#2）**：
- 索引语义闭环：挂起时写、轮终态清、TTL 与锁 watchdog 对齐——精确对治 B1 实证的"锁仅活轮存活"三场景（已实测 `SessionGate.java:116-127` currentRunId 读锁值、`AiController.java:184-188` finally 释放锁）。
- 三分支降级与 B1 三场景一一对应：已超时→TIMEOUT/expired、他端已提交→FRONTEND_RESULT/终态展示、进程已死→索引键缺失/回退+expired，且"任何分支不留 pending 残留"封住 GF-B 原始症状回归。
- D1 中间态由 unresolvedExternal 对齐条款覆盖（见闭合表 D1 行），B1 路线内无新引入矛盾。

### 2.2 C1 双守卫、A2 终态单调、C3 getter 在 T2a-D#1/D#2 中的条款化与可实现性【实测】

- **C1 双守卫**：T2a-D#1 守卫条款三条（广播仅 pendingCall 投影 / toolRun 已终态不改写 / localTerminal 补 succeeded）中后两条与裁决 C1 逐字对应且可实现——工作区施工代码 `ai.ts`（施工中版本）localTerminal 已补 `succeeded`，证明可实现性。但第一条"仅覆盖 status=='pending'"**超出裁决 C1 原文**（裁决的双守卫无此限制），并与 A2 处置正面冲突 → P1。
- **A2 终态单调**：T2a-D#2 选择 (b) + 映射表条款化完整（FRONTEND_RESULT→succeeded 等五项映射 + PendingStatus 补 FRONTEND_CANCELLED + 镜像兼容），可实现（工作区 `applyPendingTerminal` 已按此实现）。但如上，其"帧终态可覆盖乐观 cancelled"的必要语义被 D#1 字面守卫挡住 → P1。
- **C3 getter**：T2a-D#2 附带修复条款化明确（"跳过非 pending 继续向前扫"），HEAD 现状（`ai.ts:223-224` 提前 return null）实测确认缺陷存在，改法为单点修改，可实现。

### 2.3 E1/E2 风险承认在 T2a-D#5 的显式留痕【实测】

T2a-D#5 含专段"显式风险承认（红队 E1/E2，签字即确认）"：前端态在阻塞 CI 门禁内零自动回归属已接受风险、服务端 E2E 对前端投影态贡献≈0、nightly 与 vitest 评估挂 T4——三项留痕与裁决 E1/E2 处置一一对应，且有推翻条件（冒烟脚本三次误报/漏报或 T4 结论反转）。闭合。

### 2.4 新卡相互冲突排查【实测+推断】

- **T2a-D#3 openGenerativeForm 守卫 vs T2b-D#3 快照重建**：无冲突。守卫位于帧路径（openGenerativeForm），快照重建不经过帧路径、由 T2b-D#2 的 entryStatus 把关；两路径互补覆盖。但守卫的"已有终态"判定源（toolRuns[].status 还是 pendingCall.status、本消息还是跨消息）未写明 → P4。
- **T2a-D#1 vs T2a-D#2**：**存在字面冲突**（P1，详 §4）。佐证：T2a-D#2 自己的验证用例"他端提交+本地取消竞态冒烟，终态恒 succeeded 无翻闪"在 D#1 字面守卫下**不可能通过**（乐观 cancelled 不在"仅 pending"覆盖域内，帧永远改不动它）——卡内验证用例反证卡间守卫口径矛盾。
- **T2a-D#5 vs T2b 验证**：三断言清单中"①刷新后单卡"实为 T2b 场景（T2a 不实现刷新重建）；按 T2a 先行交付的时序，该断言在 T2a 阶段只能验镜像重建路径的单卡性，T2b 落地后才验完整场景。属断言清单跨包复用，非冲突，记观察不立问题。

---

## 3. 行号抽验记录（HEAD `a71832d` 钉版，18 处）

**基线有效性**【实测】：`git diff 9de199d a71832d -- frontend/ backend/` 为空——红队审查基线与当前 HEAD 代码零差异，红队全部行号引用在 HEAD 仍有效。
**工作区漂移警示**【实测】：复核期间工作区存在**进行中的未提交 T2a 施工**（`frontend/src/stores/ai.ts`、`types/ai.ts`、`types/sse.ts`、`AiPanel.vue` 四文件 modified，复核时点 +121/-11，`ai.ts` 在复核期间从 1089 行涨至 1167 行，仍在变动）。本报告所有行号抽验一律钉在 `git show HEAD:` 版本；引用工作区施工代码处均单独标注"施工中版本"。

| # | 引用位置（红队→本报告核验） | 结果 |
|---|---|---|
| 1 | `ai.ts:214-228` pendingToolCall getter | ✓ 命中：最新向前扫，非 pending 的 pendingCall 在 223-224 提前 return null |
| 2 | `ai.ts:209` runSeq 初值 `{}` | ✓ 命中 |
| 3 | `ai.ts:103-134` 镜像读写（127 pendingCall 序列化、109 裸 JSON.parse） | ✓ 命中 |
| 4 | `ai.ts:343-396` loadHistory（347/373 整体替换、375-382 toolRuns 重建、389 pendingCall:null、363-371 空历史仅 pending→expired） | ✓ 全部命中 |
| 5 | `ai.ts:520-566` reattachActive（527 lastSeq、528 appendAssistantMessage） | ✓ 命中 |
| 6 | `ai.ts:723-738` cancelGenerativeForm（728 同步置 cancelled 后 await POST） | ✓ 命中 |
| 7 | `ai.ts:950-975` frontend_tool_result（localTerminal 仅 failed/rejected/blocked 不含 succeeded、pendingCall 置 null、activeForm 清空） | ✓ 命中，C1/A2 的证据基础成立 |
| 8 | `ai.ts:937-945` frontend_tool_request→openGenerativeForm | ✓ 命中 |
| 9 | `types/ai.ts:161` ToolRunStatus（七值无 cancelled） | ✓ 命中 |
| 10 | `types/ai.ts:213` PendingToolCall.status（五值无 cancelled/succeeded/blocked） | ✓ 命中（HEAD 行 213 恰为该联合类型） |
| 11 | `types/sse.ts:22-31` PendingStatus 联合（无 FRONTEND_CANCELLED） | ✓ 命中；后端 `PendingToolCall.java:40` 确有该常量，前端缺口真实 |
| 12 | `SessionGate.java:116-127` currentRunId（inProcess 或 Redis 锁值） | ✓ 命中 |
| 13 | `ResumeService.java:118-140` listRuns 全量扫 allRunIds + `unresolvedExternal` 口径（PENDING 且非 BACKEND） | ✓ 命中 |
| 14 | `AiController.java:184-188` finally 释放锁、`290-302` replayAndAttach + isTerminal 仅 DONE/FAILED/CANCELLED | ✓ 命中 |
| 15 | `SseChatEmitter.java:358-375` replayLocked 只跳 delta/heartbeat | ✓ 命中 |
| 16 | `RunStore.java:217-228` putPending 用 HASH field=toolCallId | ✓ 命中 |
| 17 | `frontend/package.json:6-11` 无 test 脚本；`.github/workflows/ci.yml` frontend job 仅 typecheck+build | ✓ 命中，E1 证据成立 |
| 18 | `M2-排期计划.md:10,23` 排期原则 / T2 批次捆绑表述 | ✓ 命中，F1 证据成立 |

抽验结论：**红队全部抽验引用与 HEAD 代码现状一致，无失实引用**。

---

## 4. 复核新发现问题清单

### 严重

**P1｜T2a-D#1 守卫"广播仅覆盖 status=='pending'"与 T2a-D#2（裁决 A2）"帧终态可覆盖乐观 cancelled"字面冲突**
- 证据【实测】：裁决 C1 原文双守卫为"目标消息 toolRun 已终态不改写；localTerminal 补 succeeded"，**并无**"仅覆盖 pending"限制——该限制是卡 T2a-D#1 自行加严引入的。A2 场景（本地先置 cancelled、他端 succeeded 帧后到）下：按 D#1 字面，cancelled 不在覆盖域 → 帧永远改不动它 → 卡片停留 cancelled，A2 修复被守卫吞掉；而 T2a-D#2 的验证用例"终态恒 succeeded 无翻闪"在此守卫下不可能通过——卡内用例反证卡间矛盾。工作区施工代码（施工中版本，`ai.ts` frontend_tool_result 处理与 `applyPendingTerminal` 注释）已实际按"pending/乐观 cancelled 均可被帧终态覆盖"实现，佐证矛盾真实存在且正确修法已被施工者采用。
- 修法：将 T2a-D#1 守卫勘误为"广播仅覆盖 status 为 pending 或乐观 cancelled 的 pendingCall；已被帧终态落定（succeeded/rejected/blocked 等）不降级不改写"。一行勘误即可闭合，不触动卡架构。

**P2｜T2b-D#2 返回结构缺"归档最大 seq"（或等价的无回放挂载模式），T2b-D#3"lastSeq=归档最大 seq 挂 SSE"前端无从取值**
- 证据【实测+推断】：T2b-D#2 定义返回 `{runId, snapshotStatus, pendingEntries, unresolvedExternal}`，无 seq 字段。而 lastSeq 的唯一前端来源 `runSeq` 刷新即失且裁决 A3 明确不入镜像（HEAD `ai.ts:209/836-843` 实测确认仅同页签累积）。前端不发起全量回放（被否路线）就无法从任何现有接口得知归档最大 seq——两卡按字面无法组合。T2b 的立包宗旨正是"端点语义先定后施工"，此缺口恰属必须先定的语义。
- 修法：T2b-D#2 返回结构补 `archiveMaxSeq`（或 reattach 端点增"仅订阅新帧"模式），一行字段口径即可闭合。

**P3｜T2b-D#2 的 `pendingEntries[{toolCallId, entryStatus}]` 字段宽度不足以支撑 T2b-D#3 的挂起卡重建**
- 证据【实测+推断】：重建表单卡需要工具入参（generative_form 的 schema 内嵌于 arguments）。后端 `PendingToolCall` 条目快照（asMap，`types/sse.ts:34-50` 前端镜像类型与后端字段实测）**携带 arguments**，但 T2b-D#2 把 pendingEntries 收窄为仅 toolCallId+entryStatus——T2b-D#3 按此字面规格拿不到 schema。T2b-D#3 自带推翻条件（"快照字段不足以重建挂起卡则升级裁决"）说明卡作者已预见，但与其施工时触发推翻升级裁决，不如现在对齐字段口径。
- 修法：pendingEntries 扩为完整条目快照（至少含 arguments/kind/name），或在 T2b-D#2 显式声明返回全量 `PendingToolCall.asMap`。

### 建议

**P4｜T2a-D#3 B3 守卫"查该 toolCallId 已有终态"未写明判定源与作用域**：终态取自 `toolRuns[].status` 还是 `pendingCall.status`、本消息还是跨消息扫描，未定义；且该守卫仅护帧路径，对 T2b-D#3 快照重建路径不生效（重建路径由 entryStatus 把关，自洽但两处口径宜统一表述）。建议施工前补一句判定源定义。

**P5｜T2a-D#5 三断言之"①刷新后单卡"跨包复用需注明时序**：T2a 阶段仅能验镜像重建路径的单卡性，完整刷新续收单卡须待 T2b 落地。建议断言清单标注所属包，避免 T2a 验收时误判漏项。

---

## 5. 总体结论

**通过（有条件）。**

- 14 条红队发现 → 裁决处置 → 卡条款三链路全部闭合，无遗漏、无处置降级、无偷换语义；两阻断（A1/B1）的返修路线（快照重建+增量续收 / 索引+结构化端点）在 T2b 三卡内语义自洽，无路线性新矛盾。
- 红队 18 处行号抽验全部命中，证据链可信（红队基线 9de199d 与 HEAD a71832d 代码零差异，已实测）。
- 但发现 3 项严重级卡内/卡间口径缺口（P1 守卫覆盖域冲突、P2 lastSeq 来源缺失、P3 pendingEntries 字段宽度不足）：均为**文字/字段层面的精确性缺口**，修法各为勘误一行至数行，不动摇已签字的 8 卡架构与拆分决策；其中 P1 已被工作区施工代码按正确口径（A2 权威方向）实现，佐证为措辞问题而非路线问题。
- 处置建议：P1/P2/P3 以**卡勘误/补注**（保持签字链留痕）方式在 T2a 验收前（P1）与 T2b 端点定稿前（P2/P3）闭合；闭合后本复核转为无条件通过。无需打回重签。

---

*复核方法留痕：只读操作（Read/Grep/Glob/git show/git log/git diff/git status），零 git 写操作；报告为唯一新增文件，保持未提交。工作区并行施工（T2a 四文件未提交改动）为复核期间的观测事实，本报告结论一律钉在 HEAD `a71832d` 基线，与移动中的工作区解耦。*
