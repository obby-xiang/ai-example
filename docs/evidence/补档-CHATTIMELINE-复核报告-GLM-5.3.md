> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 1 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 复核报告：对话时序包 批 1（双写 + R-130/R-131/R-132/R-133）

| 项 | 值 |
|---|---|
| 执行者模型 | **GLM-5.3**（复核与测试角色） |
| 授权来源 | **Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）** |
| 复核对象 | 主仓库 HEAD=7879f53 工作树在制品 7 文件（4 改 + 3 新，未提交） |
| 复核日期 | 2026-10-11 |
| 厂商互异 | 复核方（GLM）与施工方（Kimi K2.8）、终审（Kimi K3）互异 ✓ |

---

## 1. 复核范围与方法

- **必读输入**：决策卡（K3 代签）、施工卡-刷新版（§4.0/§4.1/§6.4/§10.3/§7/§8）、施工报告-批1（含自报偏差 4 条）、evidence-b1/ 与 e2e-artifacts/ 证据、7 个在制品文件本体（`git diff` 逐行 + 全文实读）。
- **独立复现**：五连验收在本机真实执行（前端三连 / 后端 Maven 全量 / 桩模式 E2E 29 用例 / CDP 冒烟 13 断言），后端与桩均为复核方自起进程、跑完按 PID 清理；E2E 走真实 Redis（127.0.0.1:6379，TC19 实调 Lua 删除路径）。
- **对抗反查**：旧投影写点全量 grep 对账（`upsertToolRun`/`toolRuns.push`/`pendingCall =` 全部命中点逐一核对双写）、守卫移除思想实验（负例断言是否恒真）、`currentAbort` 赋值面核查、AiPanel 两映射函数与新枚举的交互、偏差第 5 条专项排查。
- **纪律**：全程零 git 写操作（终态 `git status --porcelain` 与复核前一致，HEAD 仍 7879f53）；零代码改动；未读任何 .env；测试临时文件限于系统临时目录并已清理；未触碰 chat-timeline-pkg 既有文件。机械活（跑测试、grep 对账、XML 统计）全部经后台任务/工具执行，关键判断由本执行者完成。

## 2. 独立复现五连结果（全部真实执行）

| # | 项 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| ① | 前端 typecheck + build | 绿 | `vue-tsc --noEmit` Done in 3.87s（0 error）；`✓ built in 27.84s` | **PASS** |
| ② | 前端 `yarn test` | 37/37，5 spec | Test Files 5 passed (5)，Tests **37 passed (37)**（含 parts-double-write 5 + restore-reconcile 3 新例），Duration 1.94s | **PASS** |
| ③ | 后端 Maven 全量 | 439/0/0/0 | `Tests run: 439, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（01:05 min）；**XML 求和口径 439/0/0/0**（69 个 XML，无重复类；基线 68 测试类 + 新增 1 类 = 69，只增不减成立） | **PASS**（注记见 m1） |
| ④ | E2E 桩模式 | PASS=29 | **PASS=29 FAIL=0，GFSKIP=0，Q8 项 PASS=2 FAIL=0**，exit 0；TC15/16/18/22/23/19 与 GF1–GF6 全绿，TC19 经真实 Redis 实调 Lua 删除路径 | **PASS** |
| ⑤ | CDP 冒烟 | 12 PASS + 1 SKIP | 断言①–⑪⑬ 共 **12 PASS** + ⑫ SKIP（SMOKE_KILL_BACKEND 未授权，常态不假绿）+ 剪贴板回读旁证 TIMEOUT SKIP（非断言项），exit 0 | **PASS** |

五连数字与施工报告 §3 声称**逐项一致**，无任何不符。

## 3. 复核重点 6 项逐项结论

### 3.1 双写一致性 ——【实测】通过

- **写口全量对账**：grep 全部旧投影写点（`upsertToolRun` 9 处调用、`toolRuns.push` 1 处、`pendingCall =` 6 处），逐点核对：
  - `tool_start`（:1467 push 旁）、`tool_result`（:1498/:1507）、`confirm_request`（:1523/:1530）、`confirm_decision`（:1548/:1554 与 :1563/:1570）、`frontend_tool_request`（:1590/:1598）、`frontend_tool_result`（:1622/:1630）、restore 快照重建（:699/:703）——全部有同帧 `upsertToolPart` 双写，状态字面量两侧共用同一 const/同一变量。
  - `pendingCall`-only 写点（:691/:1521/:1590/:1669/:565/:1781/:1789）均不产生 toolRuns，无段可写，不构成漏写。
  - **旧轮迟到帧防护（R2-5 前置）**：所有双写均位于 `handleFrame` 的 `ownedByActiveRound` 守卫与宿主消息查找（:1243-1250）之后，早返回分支不写 parts。
- **卡外 2 处（:1148/:1861）判定：必要，非越权**。两处均有紧邻的真实 `upsertToolRun` 旧投影写口（:1140 openGenerativeForm 失败收尾、:1853 runFrontendTool 浏览器侧结局），按"凡写 toolRuns 必双写"总则漏写即漂移，属正确补写。
- **失败行为对称性**：`upsertToolPart` 为内存 push/splice，无独立失败路径；两侧写在同一同步语句序列，无双写窗口。`writeMirror` try/catch 容错（配额/隐私模式），行为与基线一致。【推断】
- **对账断言有效性**：`parts-double-write.spec.ts` 的 `toolRunsOf`/`toolPartsOf` 七字段逐项 `toEqual`，删除任一双写即红，非恒真。

### 3.2 对账两负例（R-130/R-132）——【实测】通过

- **负例①（sid 失配）**：手控 deferred 将 store 停在 runs/current 的 await 上，期间 `resetSession()`，断言新会话 messages=0、`activeRunId=null`、`reattachRun` 未被调。思想实验：移除 :647 守卫则旧快照宿主消息落入新会话 → messages≥1 → 断言必红。**有效**。
- **负例②（abort 失配）**：await 期间同会话 `send()` 起新轮（`send` 在 :755 同步替换 `currentAbort` 句柄），断言在跑轮 `activeRunId` 未被旧快照 runId 顶替、`reattachRun` 未被旧快照调用、新轮消息不被污染。思想实验：只保留 sid 对账不拦截同会话新轮 → 旧快照覆盖 activeRunId 并起续收连接 → 断言必红。**有效**。
- **观测面设计**：`currentAbort` 为模块级私有句柄，测试以「最终状态未被污染 + 续收连接未起」为等价观测面，正当。思想实验验证见上，非恒真。
- **修复方向**：以入口捕获身份（sid + abortAtEntry）为权威，过期快照**整体丢弃**（不写回、不 abort、不起续收）——与决策卡/施工卡 §10.3「不覆盖、不 abort」口径一致；权威源 = 当前现场而非旧快照，方向正确。
- **判据选择**：R-132 用 `currentAbort` 而非施工卡建议的 `activeRoundHostId`，覆盖 send/reattach/resumeSnapshotStream 三个换柄点（:755/:827/:973），判据等价且更全。【推断】

### 3.3 Lua 原子性（R-131/R-133）——【实测】通过

- **脚本本体**（:65-74）：`DEL KEYS[1]` + `SREM KEYS[2] ARGV[1]`，单次 `execute`（:164-165）→ Redis 服务端 Lua 原子执行，DEL 与索引清理不可分割，N2（崩溃窗口 stale 成员）与 N2-b（并发 SADD 被迟到 SREM 抹掉）同段一并消解。**真原子**。
- **键序/参数**：`[chat:mem:<id>, chat:mem:__ids__]`、ARGV=conversationId，由测试 `deleteIssuesSingleAtomicScript` 锁死（含 `never delete/opsForSet` 反断言）。
- **SISMEMBER 判存**：`isConversationIndexed`（:137-139）与 AiController `removedFromIndex`（:590）未动，行为不变。
- **幂等 200 语义**：AiController.deleteHistory（:566-597）「不存在/重复删除均 200、deleted 恒 true」JSDoc 与行为一致，未动。
- **Cluster 约束**：JSDoc「两 key 不同 hash slot，Cluster 需 hash tag 改造；本仓单实例」与行为契约一致；无 Cluster 环境如实登记不假测。单测为行为契约锁而非真脚本执行——已由 **E2E TC19 + 冒烟③④ 在真实 Redis 上实调该 Lua 路径**补强（oldSessionHistoryCount=0 证明索引与数据同删）。
- **新增测试 2 例**：原子结构锁 + 脚本体 DEL/SREM 存在性，均有效（改坏实现即红）。三态中「幂等/不存在」由既有 AiControllerHistoryDeleteTest 11 例承载（见 o3）。

### 3.4 requiresAction 残留面 ——【实测】按决策卡口径处理，未提前翻转

- 残留 true 面完整枚举：`done`（:1698，清 pendingCall 不写段）、`error`（:1710-1716，同）、`confirm()` 乐观置位（:1035，只写 pendingCall）、`cancelGenerativeForm`（:1224，经 applyPendingTerminal 只写投影）、`applyPendingTerminal`（:1755）/`applyFrontendToolTerminal`（:1773）整体只作用 pendingCall 维度。
- 批 1 面板仍读旧投影（`m.toolRuns`/`m.pendingCall`，AiPanel :195/:213），**未提前翻转** ✓；残留面已在施工报告 §5.2 登记为批 2 权威翻转前收口项，口径与决策卡一致。
- 补充一个报告未点名的维度：**残留 true 会随镜像 v2 持久化**（writeMirror 现在带 parts），批 2 翻转若不连同镜像维度收口，刷新会复活挂起语义（见 o1）。

### 3.5 验收五连独立复现 ——【实测】全绿

见 §2 表。五项数字与施工报告声称逐项一致。

### 3.6 K2.8 自报偏差 4 条逐条复核 + 第 5 条排查 ——【实测】4 条全部属实

| # | 自报偏差 | 复核结论 |
|---|---|---|
| ① | RedisScript 包路径笔误（方案写 `core.RedisScript`，实为 `core.script.RedisScript`） | **属实**。实现在 :11-12 按 `org.springframework.data.redis.core.script.*` 导入，编译 + 全量测试绿。 |
| ② | 双写点比卡清单多 2 处（:1148/:1861） | **属实且必要**。两处均紧邻真实旧投影写口（:1140/:1853），非越权多加（详见 §3.1）。 |
| ③ | suspended 帧 `createIfMissing=false` | **属实且取舍正确**。该帧只写 pendingCall（:1669）不写 toolRuns，若默认新建会产生"只有 parts 才有的卡"，与旧投影漂移。 |
| ④ | `pendingCall.status==='approved'` → 段 status `'running'` | **属实且口径一致**。`approved` 不在 ToolRunStatus（类型强制），映射与 `confirm()` :1035（放行 → pending.status='running'）同口径。 |
| ⑤（排查） | 是否存在未申报偏差 | **未发现语义性隐瞒偏差**。排查了：历史投影 requiresAction=false（符合 §3.7 确定性投影，非偏差）、tool_result 帧无条件传 reason（含 null，与旧代码行为逐字一致，无双写引入的新漂移）、delta 帧 `streaming=true` 冗余赋值（纯冗余无行为差）。唯一口径差异：`migrateMirror` 的 requiresAction 判据实现为 `status==='pending'`，施工卡 §6.4 写的是 `pending&&(CONFIRM|FRONTEND)`——因 pending 状态只可能来自这两类挂起，**语义等价**，且已由镜像迁移测试锁定；但该差异在施工报告 §5.1.5 是以"其余 minor 取舍"打包带过、未逐条列名，申报粒度不足（记 m3）。 |

## 4. 问题清单（0B / 0M / 3m / 5O）

**BLOCKER：0　MAJOR：0**

### minor（3）

- **m1** 「68 XML」计数口径不精确：任务卡与施工报告 §3.2 以「68 XML 只增不减」表述，实测 surefire XML 为 **69**（= 基线 68 个测试类 + 新增 RedisChatMemoryRepositoryDeleteTest 1 类，无重复无遗留）。「只增不减」实质成立，纯文档措辞差。锚点：施工报告-批1.md §3.2。
- **m2** 前端 8 个新用例未留 TDD RED 证据（后端 2 例有 RED 声明：实现前 Errors=2 @旧实现:147）。对账负例（restore-reconcile）若先于守卫实现编写本可留红证，现在只能靠本复核的思想实验补证其非恒真。锚点：frontend/tests/restore-reconcile.spec.ts。
- **m3** 偏差申报粒度：`migrateMirror` 的 requiresAction 判据（`status==='pending'`）与施工卡 §6.4 措辞（`pending&&(CONFIRM|FRONTEND)`）的差异、pendingCall kind 覆盖 kind2 的口径选择，被 §5.1.5「其余 minor 取舍」打包带过未逐条列名（语义等价已核，不影响结论）。锚点：frontend/src/stores/ai.ts `migrateMirror`（:158 起）。

### 观察（5）

- **o1** requiresAction 残留 true 随镜像 v2 **持久化**（writeMirror 现带 parts）：批 2 权威翻转前收口时，除 done/error/乐观路径写段外，还须评估存量镜像里已持久化的残留 true（迁移幂等不会修正它）。施工报告 §5.2 已登记主项，本条补"镜像持久化"维度。
- **o2** Lua 返回值（DEL 计数）被忽略（无影响）；`saveAll` 的 DEL+RPUSH+EXPIRE+SADD 仍非原子（写入路径崩溃窗口），R-133 范围仅登记删除路径——写入侧属既有登记，批外维持。
- **o3** 新增 DeleteTest 2 例只锁「原子性」结构与脚本体；「幂等/不存在」三态由既有 AiControllerHistoryDeleteTest 11 例 + E2E TC19 真实路径承载，Lua 对不存在键 DEL/SREM 返回 0 天然幂等【推断，无 Cluster 实测环境】。
- **o4** CDP 冒烟 ⑫ SKIP（未授权杀后端）与剪贴板回读旁证 TIMEOUT SKIP 均为环境常态、与基线一致（如实 SKIP 不假绿），不构成发现，仅留痕。
- **o5** restore-reconcile 负例②的等价观测面绑定 `reattachRun` mock；若将来 `resumeSnapshotStream` 改用其他续收 API，`expect(api.reattachRun).not.toHaveBeenCalled()` 会静默弱化——建议届时同步维护该断言（测试维护性，非正确性问题）。

## 5. 总结论

**PASS（无条件）** —— 0B / 0M / 3m / 5O。

- 独立复现五连全绿，数字与施工报告逐项一致（37/37、439/0/0/0、PASS=29/GFSKIP=0/Q8 2-0、12 PASS+1 SKIP）。
- 复核重点 6 项全部通过：双写写口全量对账无漏（卡外 2 处判定必要）、对账两负例断言有效且修复方向与决策卡一致、Lua 删除路径真原子且经真实 Redis 实证、requiresAction 残留面按口径登记未提前翻转、自报偏差 4 条全部属实、未发现语义性隐瞒偏差。
- 3 条 minor 均为文档措辞/申报粒度/测试证据留痕问题，不触及代码正确性；o1 需带入批 2 收口清单。
