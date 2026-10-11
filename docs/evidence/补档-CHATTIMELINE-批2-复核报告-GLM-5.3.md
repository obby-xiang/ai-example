> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 2 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 复核报告：对话时序包 批 2（子包②权威翻转）

| 项 | 值 |
|---|---|
| 执行者模型 | **GLM-5.3**（复核与测试角色） |
| 授权来源 | **Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）** |
| 复核对象 | 主仓库 HEAD=**2f455ce** 工作树在制品 11 文件（改 6 + 新 5，未提交） |
| 复核日期 | 2026-10-11 |
| 厂商互异 | 复核方（GLM）与施工方（Kimi K2.8）、红队（DeepSeek）、终审（Kimi K3）互异 ✓ |

---

## 1. 范围与方法（含子智能体模型自证）

- **必读输入按序消化**：施工报告-批2（偏差 11 + 观察 12）→ 决策卡 + 施工卡-刷新版（批 2/子包② §4.2、§6.1/§6.3/§6.4、§7 R2-1~R2-5、§10）→ 红队报告-批1（m-R1/m-R2/o-R1~o-R4 原文）→ 复核报告-批1（o1 镜像维度）→ 11 个在制品文件本体（`git diff HEAD` 逐行 + 全文实读 `utils/parts.ts`、`handleFrame` 重构段、`AiPanel.vue` 渲染段、6 个测试文件、后端 diff 与新测试类）→ evidence-b2/ 证据抽检。
- **独立复现四连**：全部真实执行。**机械活下沉子智能体 4 棒，全部显式钉 `model=DeepSeek V4 Flash`（互备 GLM-5.3-Flash）**：前端三连棒、后端 Maven 棒、E2E 桩棒、CDP 冒烟棒——四棒均以后台子智能体（coder 类型 + 显式 model 参数）执行，配方、判定口径与关键判断由本执行者（GLM-5.3）制定并验收；每棒均要求原始输出落盘（`%TEMP%/glu-b2-fe|be|e2e|cdp/`）供抽证。
- **对抗反查**：旧字段写点独立 grep 复算（与施工方 flip-grep-audit.txt 交叉比对）、`upsertToolRun`/`toPendingCall` 删除面独立验证、seq 三态与 residue 守卫的变异思想实验（不实改代码，见 §3.2/§3.4）、m2 六写点变异留证独立抽检重推、pendingCall 派生语义与批 1 getter 的逐条对账、偏差 11 条逐条核实 + 第 12 条排查。
- **纪律**：全程零 git 写（终态 `git status --porcelain` 与复核前逐字一致，HEAD 仍 2f455ce，工作树仍为既有 6M+5??）；零代码改动；未读任何 `.env`；测试临时文件全部限于系统临时目录（`%TEMP%/glu-b2-*`）；未触碰 chat-timeline-pkg 既有文件（本报告为唯一新增写入）；复现用后端/桩/dev server/Chrome 均自起并按 PID 收尾，端口复查全净（18330/18399/5202/9334 无 LISTENING）。

## 2. 独立复现四连结果（全部真实执行）

| # | 项 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| ① | 前端 `yarn test` | 77/77，8 spec | Test Files **8 passed (8)**，Tests **77 passed (77)**，Duration 2.55s，exit 0（经 PIPESTATUS 取真实退出码） | **PASS** |
| ① | 前端 `yarn typecheck` | exit 0 | `vue-tsc --noEmit` Done in 4.10s，0 error，exit 0 | **PASS** |
| ① | 前端 `yarn build` | 成功 | `✓ 1748 modules transformed`，built in 28.16s（墙钟 33.14s），exit 0，仅常规 chunk 体积告警 | **PASS** |
| ② | 后端 Maven 全量 | 442/0/0/0，70 XML | `Tests run: 442, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（01:04 min），exit 0；surefire XML **70**；XML 属性独立聚合 `files=70 tests=442 failures=0 errors=0 skipped=0` 与汇总行吻合；全部 70 XML mtime 落本次运行窗口（非陈旧充数）；新测试类 `RedisChatMemoryRepositorySaveAllEmptyTest` 3 例真实执行且 Skipped=0（真 Redis 例未跳过） | **PASS** |
| ③ | E2E 桩模式 | PASS=29 | **PASS=29 FAIL=0 GFSKIP=0，Q8 项 PASS=2 FAIL=0，E2E_EXIT=0**；桩 18399（`--violate-form-type` + routes）、后端 18330（AI_BASE_URL 指桩，起前 netstat 确认空闲）、jar 新鲜度核验（mtime 晚于全部源码，`find -newer` 为空）、跑毕按 PID 停自起进程并复查端口 | **PASS** |
| ④ | CDP 冒烟 | 12 PASS+1 SKIP | 断言①–⑪⑬ **12 PASS** + ⑫ SKIP（SMOKE_KILL_BACKEND 未授权，环境常态）+ 剪贴板回读旁证 TIMEOUT SKIP（非断言项），exit 0，零 FAIL | **PASS** |

四连数字与施工报告 §3 声称**逐项一致**，无任何不符。

## 3. 复核重点 9 项逐项结论

### 3.1 权威翻转正确性 ——【实测】通过

- **唯一真值成立**：全文实读 `utils/parts.ts`（364 行）与 `stores/ai.ts` diff（±687 行）后独立 grep 复算，与施工方 flip-grep-audit.txt 交叉比对**逐条吻合**：
  - `stores/ai.ts` 中 `content`/`reasoning`/`toolRuns`/`pendingCall` 四旧字段的赋值点**仅剩 2 处白名单**：`ai.ts:1534` `message.content += frame.text`（delta）与 `:1540` `message.reasoning += frame.text`（reasoning）——正是 §6.1 性能注允许的锁步增量维护；其余全仓唯一命中 `utils/parts.ts:185-188`（`deriveProjections` 的四个赋值），无第三处。
  - `upsertToolRun`/`toPendingCall` 两个 store action 已删除，全仓 grep 无任何调用点（余下命中全为注释与 `parts.ts` 内部 `toPendingCallProjection` 函数名）。
  - 帧级段写入 11 处 `applyFrameToParts(message, frame)` 调用点独立数齐（message_start/message_end 合并 case、delta、reasoning、tool_start、tool_result、confirm_request、confirm_decision、frontend_tool_request、frontend_tool_result、suspended）。
- **投影等价性**：`deriveProjections` 的 `content = text 段 join`（段只切显示不丢字符 ⇒ 与批 1 "整轮按到达顺序拼接"逐字等价）；`toolRuns` 逐字段映射（`kind ← kind2`）形状与 `ToolRun` 接口逐字段核对一致；`pendingCall` 取**最后一个** requiresAction 段（见 §3.6 语义对账）。批 1 建立的 `parts-double-write.spec.ts` 5 例在翻转后仍全绿 = 双侧一致性的跨批回归锁继续有效。
- **段→投影方向自洽**：所有非白名单写入路径均"写段后 derive"（帧处理尾部 `DERIVE_FRAME_TYPES` 集合、confirm/cancelGenerativeForm/restore 等直接调 `deriveProjections`）；`markApplied` 记账在 case 之后（半途 break 的帧也记账，不被重放）。

### 3.2 帧级守卫保留 + seq 幂等三态 ——【实测】通过

- **守卫原样保留**：现态 `handleFrame` 前置段（:1473-1519）实读确认 `ownedByActiveRound` 判定、宿主消息查找（找不到整帧丢弃）、`runSeq` 记最大 seq、旧轮迟到帧只收敛自身消息收尾（`isTerminalFrame → streaming=false, done=true` 后 return）——与批 1 基线逐句一致，未被重构破坏；parts 写入全部位于守卫之后。
- **seq 幂等三态用例有效性（变异思想实验）**：
  - null 照收：`alreadyApplied(message, null)` 恒 false（`appliedSeqs.includes(null)` 不可能命中，且 seq!==null 守卫在 store 侧短路）→ 用例①绿是正确行为；若把 `seq !== null &&` 守卫删掉，`markApplied(message, null)` 会记入 null 使后续 null 帧被吞 → 用例①（两张无 seq delta 逐帧累加）必红。**有效**。
  - 重复去重：若删 `alreadyApplied` 早返回 → 用例② `content` 变 `'你好你好'` 必红（这正是 flip-red-handleframe.txt 留证的第 1 个 RED）。
  - 乱序只去重：`alreadyApplied` 是"成员存在性"判定而非"小于最大值"判定 → seq=3 迟到帧照收（用例：`'五三'` 而非 `'五'`）；若实现者换成 `seq <= max(appliedSeqs)` 的连续性判定，乱序用例（`五`+`三`= `'五三'`）必红。**三态判定均非恒真**。
  - 工具帧整帧跳过由用例②（重复 tool_start 不重建段）与端到端用例④（回灌帧不新建段）共同锁定。
- **R2-5**：resetSession 后旧轮帧用例（宿主已不在 ⇒ 整帧丢弃，新会话零污染）绿；思想实验：删宿主查找早返回则 `currentAssistantMessage()` 凭空 append → `messages.length===0` 断言必红。**有效**。

### 3.3 六残留点收口语义 + 镜像 sanitize ——【实测】通过

- **逐点对照决策卡口径**（以 `parts-residue-cleanup.spec.ts` 21 例 + 代码实读核对）：
  - done/error → `expireResidueToolParts`（全部 requiresAction 段 → `expired` + `requiresAction=false`），done 用例双挂起段断言 `[['expired',false],['expired',false]]`；error 同口径。✅
  - confirm() 乐观置位：true→running / false→rejected（`ai.ts:1083` 区）/ DUPLICATE→running（approved 并入，与 m-R1 口径一致）/ 失败回退→pending+requiresAction=true。四用例逐条锁定。✅
  - cancelGenerativeForm → 经 `applyPendingTerminal('cancelled')` 段侧落 `cancelled`+false（函数本体未改，收口随 applyPendingTerminal 翻转自动生效）。✅
  - applyPendingTerminal：匹配域 = `pending || cancelled || requiresAction`（比卡面多第三判据，覆盖"status 已终态但仍挂 requiresAction"的残留形态），approved→running 映射，first-wins 单调。✅
  - applyFrontendToolTerminal：非 GF 只收 requiresAction 不动 status；GF pending/cancelled 落映射终态；null 只收 requiresAction。三用例锁定。✅
- **镜像 sanitizeMirrorResidues**：仅 done 消息、requiresAction→false、pending→expired、幂等（二次读取 savedAt 不变）；v1 迁移与 v2 intact 均生效（3 用例 RED→GREEN 留证核对：RED 期 v2 intact 例断言失败于段 status 仍 pending——方向正确非伪造）。回写条件 `migrated || sanitized` 合理（无变化不写盘）。
- **偏差 2（守卫经 loadHistory 不可达）核实：属实**。镜像分支在落展示态前强制 `done: true, streaming: false`（`ai.ts:576-578`），且 `writeMirror` 的 `Pick` 不含 `done` 字段（`types/ai.ts:341`）⇒ `message.done !== true` 分支在当前唯一调用路径上恒不可达，守卫为防御性死码（记 m2，见 §5）。存活挂起确实由 `restoreSuspendedSnapshot` 重建路径重新置真（upsertToolPart requiresAction=true）。

### 3.4 m-R2 补测有效性 ——【实测】通过（含 2 点独立变异思想实验）

- 六写点用例（parts-residue-cleanup.spec.ts 施工项3）与卡面清单逐条对齐：①frontend_tool_request 建段（同步可见）②frontend_tool_result 就地更新③suspended 只更新不新建④GF 失败收尾⑤runFrontendTool 本地结局（succeeded/failed 两路）⑥loadHistory 段构造 + 端到端（pending→running→succeeded 全程同一段、seg/toolCallId 不变）。红队批 1 点名的 6 个无锁写点全部入锁。
- **m2-mutation-{1..6} 变异留证独立抽检**（抽 mutation-1 与 mutation-3 全文重读）：
  - mutation-3（逐点注释 suspended 帧 applyFrameToParts → 改为 no-op）：留证显示**只有**写点③用例红（requiresAction 未复位）、其余 20 例全绿——精确命中，证明该用例绑定的正是这个写点、无邻例假红。**有效**。
  - mutation-1（注释 frontend_tool_request 建段）：6 例红全部以 `soleToolPart` 长度 0 / parts 缺 tool 段失败——与该写点被注释的预期失败面完全一致。
  - 独立变异思想实验重做两点：(a) 若只删写点②的 `messageId/result` 断言而保 status 断言——用例②的 `toMatchObject` 含 `messageId:'tool-r2', result:'ok'`，删写点后必红；(b) 若删端到端用例第②步的 running 中间态断言——runFrontendTool 的 running 段写入（偏差 1）无任何其他用例锁定，该断言是唯一锁。**两写点锁均有效，非恒真**。

### 3.5 o-R3 修复 ——【实测】通过

- **单行复用核实**：`saveAll` 空/null 分支改为 `deleteByConversationId(conversationId)` 一行委托（diff 共 12 行：一行改动 + JSDoc），已 Lua 化的 `DEL+SREM` 原子脚本被复用；非空路径的 `delete+rightPushAll+SADD` 顺序未变（o2 批外登记维持）。
- **可达性证据抽检**：`mwcm-javap.txt` 字节码实读核对——`MessageWindowChatMemory.add` 仅三段 Assert 后直走 find→process→saveAll，**无 isEmpty 短路**（与静态证据.md 转录一致）；`clear` 直走 `deleteByConversationId`（旁证）。本仓 `convergeMemory` 的全 TOOL 尾裁剪产出空 windowed 的走查结论【推断】合理。
- **SaveAllEmptyTest 3 例**：2 Mockito 锁定单次 `execute` + 键序 `[chat:mem:s-1, chat:mem:__ids__]` + `never delete/opsForSet` 反断言 + 脚本体含 DEL/SREM；1 真 Redis 例（ping 守卫 + 前置写入证明"有东西可清" + 行为等价断言）。本复核 Maven 全量中该类 3 例 Skipped=0 真实执行（本机 Memurai 在线），**动态实证成立**。

### 3.6 AiPanel 渲染翻转 ——【实测】通过（渲染等价性论证）

- **v-for parts 分派**：text/reasoning/tool 三分支按段序渲染，key 用 `toolCallId` / `kind-seg`（业务稳定 id，R4-3 合规）；工具卡内部结构（卡头/args/result/reason）与批 1 逐字相同仅数据源换段。
- **挂起卡迁移**：整块迁入 tool 分支内、`v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"` 跟随其工具段位置——渲染组件用例②断言挂起卡在 c1 工具卡之后、c2 工具卡之前（不下沉消息末尾）；单槽 pendingCall 保证每消息至多一张。
- **两处 `v-if="m.content"` 全部处置**：:168 正文迁入段循环（`v-if="part.kind==='text' && part.text"`）；:172 复制块判据改 `m.parts.some(text 非空)`——N10 用例以"段有正文/投影为空"与"投影有正文/段为空"两个合成态锁定判据方向（各恰好出/不出按钮）。
- **isTyping / 滚动 watcher**：isTyping 改"无任何非空 text part"（用例④锁 typing 与 speaking 两态）；watcher 指纹改 parts 口径（messages.length + parts.length + 末段 kind/text长度/status + pendingCall.status），内容增量与工具状态变化均有触发面（非末段盲区记 o2，见 §5）。
- **视觉回归面论证**：无工具轮（单 text 段）与纯工具轮（段序即原 toolRuns 序 + 挂起卡跟随）与批 1 逐像素等价；`:122` history-skeleton、挂起卡 tag 全集、toolTagType/toolStatusLabel、formHostMessageId 均未动（diff 核实零触碰）。变化面仅限"工具夹文本"轮的顺序显真——正是批 2 的目标行为。骨架屏/复制/停止/骨架断言⑬⑥⑦⑪经 CDP 冒烟全 PASS。

### 3.7 性能注（delta/reasoning 不调 derive）——【推断】成立

- derive 全量重算每帧 O(parts) 并整体替换 `toolRuns` 数组 ⇒ 每 token 触发工具卡列表重渲染，锁步 `+=` 规避合理；用例以**数组身份不变**（`toBe(runsBefore)`）结构性断言 delta 路径不重算，工具帧后 `not.toBe` 断言重算发生——时机锁定非恒真（flip-red-handleframe 第 3 个 RED 即此断言翻转前失败）。
- 锁步一致性由批 1 双写用例的 join 等价断言延续覆盖。

### 3.8 偏差 11 条逐条复核 + 第 12 条排查 + 观察项 12 条落点

**偏差 11 条：全部属实，定性正确**（逐条以代码/用例/diff 核实，关键几条）：

| # | 复核结论 |
|---|---|
| 1 running 中间态增补 | 属实且必要（端到端用例②是唯一锁）；`await Promise.resolve()` 让出同步窗口使写点①的同步可观测语义成立 |
| 2 sanitize 守卫不可达 | 属实（见 §3.3，记 m2 补充定性） |
| 3 既有迁移断言改写 | 属实：`requiresAction:true→false` 正是被 sanitize 收口的对象（done 消息 + 段已终态），改写有据 |
| 4 timeoutSeconds/expiresAt/atMs 落段 | 属实且必要：pendingCall 现由段派生，元数据不落段即丢倒计时；用例锁帧→段→派生三段一致 |
| 5 hasDecidedPendingResidue 改读段 | 属实且必要：投影不再保留终态，不改即守卫静默失效；变异留证（改回读投影→红）核对 |
| 6 回写条件保持 `migrated\|\|sanitized` | 属实：幂等断言（savedAt 不变）锁"无变化不写盘"，derive 幂等不回写不影响正确性 |
| 7 删除两 action | 属实：映射逻辑迁 `toPendingCallProjection`，全仓无残留调用 |
| 8 handleChatError 判据改 parts | 属实（`ai.ts:941` 区） |
| 9 message_end 取 R2-2 交集 | 属实：`sealTrailingStreamPart` 只封口不改段结构，segDigest 用例锁定 |
| 10 组件级用例增补 | 属实：段序/挂起卡位置/N10 判据须挂载才验得到 |
| 11 前置棒 8 断言改写 | 抽查 rejected/cancelled/GF-succeeded 三处改写：全部锁"投影保留终态"的双写期实现细节而非行为契约（终态卡信息改由工具卡段状态 tag 承载），改写方向与翻转一致 |

**第 12 条未申报偏差排查**：未发现语义性隐瞒偏差。已排查：stop() 路径不写段终态（与 done/error 双路径收口设计自洽，后端 cancel 驱动帧收敛 + send finally 标 done + 刷新后 sanitize 兜底，记 o4）；`sanitizeMirrorResidues` 用 `forEach+splice` 就地替换（行为正确——替换不改变数组长度与遍历序，非删除；风格上 `for..of` 更稳，不构成问题）；m-R1 的修复已落地但未点名申报（记 m3，见 §5）；CDP 配方 dev 端口口径（记 o3）。除此之外 diff 全量逐行无其他未申报语义变化。

**观察项 12 条落点**：逐条核对全部属实——1（终态挂起卡不保留，落③b）、2（suspended 单独到达不可达，落③b）、3（error 收掉死卡=行为改善）、4（存量 v2 镜像 streaming 假态，sanitize 只收 requiresAction 不封口——代码核实确认）、5（复制按钮移到消息末尾，CDP 人工面未逐像素比对，批 3 复核补）、7（重复终帧不复位 loading）、8/9（o-R2/o-R4 登记维持）、10（Maven ERROR 行——本复核抽检确认 16 行 ERROR 全部来自负例测试会话 s-chat-turn/s-empty-reply）、11（E2E 前重建 jar——本复核核验 jar mtime 晚于源码，同样未重建）、12（dist 未被运行时验证——本复核 CDP 同走 dev 形态，同口径）。

### 3.9 记账核对 ——【实测】确认不一致（文档计数错）

施工报告 §6 写「新增 4」但实列 5 件；`git status` 实测新文件恰为 5 个（`utils/parts.ts`、`parts-residue-cleanup.spec.ts`、`parts-reducer.spec.ts`、`ai-panel-parts-render.spec.ts`、`RedisChatMemoryRepositorySaveAllEmptyTest.java`）——**工作树与清单一致，仅 §6 计数字错**（"新增 4"应为"新增 5"）。记 m1。

## 4. 红队批 1 遗留项处置核对（m-R1/m-R2/o-R1~o-R4 对题性）

| 项 | 红队要求 | 批 2 实际处置 | 对题 |
|---|---|---|---|
| m-R1（migrateMirror status 覆盖，**必修**） | 批 2 权威翻转前修掉 | **已修**：`migrateMirror` 段 status 一律以 `run.status` 为权威（`ai.ts:197-203`，注释明示"不被待决口径覆盖"），pendingCall 只供 kind2/requiresAction；v1 迁移用例锁"toolRun succeeded + pendingCall pending → part succeeded"。但施工报告未逐条点名申报（记 m3） | ✅ |
| m-R2（六写点无回归锁，**补测**） | 六写点 + 真实链路入锁 | 21 例新 spec + 六次逐点变异留证 + 端到端用例 | ✅ |
| o-R1（streaming 封口不对称） | 批 2 启用 reasoning 渲染前补封口 | `openStreamPart` 交错先封口（与 sealTrailing 对称）+ 轮终态/message_end 封口 + 3 用例 | ✅ |
| o-R2（ABA 缺口） | 随批 2 评估 | 登记（不修）+ 落点 restoreSuspendedSnapshot——与红队"实测不可达（ms 级 vs 秒级）"结论一致，处置对题 | ✅ |
| o-R3（saveAll 空分支未 Lua 化） | 评估可达性 | 可达性实测（javap + 走查）后修：单行委托 + 3 例测试 + 真 Redis 实证 | ✅ |
| o-R4（哨兵键碰撞） | — | 登记（不修）+ 落点 key()——pre-existing、UUID 不可达、SADD 自愈，与红队原文一致 | ✅ |
| GLM 批 1 o1（镜像持久化残留） | 批 2 翻转连同镜像维度收口 | `sanitizeMirrorResidues` 读取侧收口 + 回写前完成 + 幂等 | ✅ |

## 5. 问题清单（0B / 0M / 3m / 4O）

**BLOCKER：0　MAJOR：0**

### minor（3）

- **m1** 施工报告 §6 记数错：「新增 4」实列 5 件，与 git status（5 个新文件）不符。纯文档计数错，工作树与清单本体一致。锚点：施工报告-批2.md §6。
- **m2** `sanitizeMirrorResidues` 的「进行中轮不触碰」守卫（`message.done !== true` continue）在当前唯一调用路径上**不可达**（loadHistory 镜像分支落展示态前强制 `done:true`，且镜像 `Pick` 不含 `done` 字段）——偏差 2 已如实申报「经 loadHistory 不可达」，本复核补充定性：该守卫现为**防御性死码**，其"未来防护"价值完全依赖后续调用方传内存态消息（带真实 done）而非镜像态；若未来有人直接对镜像原始数据调它，守卫判断的 `done` 恒 undefined !== true 反而全部跳过。建议 ③b 动镜像形态时一并处理。锚点：`frontend/src/stores/ai.ts:222-240`。
- **m3** 红队批 1 必修项 **m-R1 的修复未逐条点名申报**：`migrateMirror` 的 status 权威化（修复本体）+ 对应断言改写散落在偏差 3/偏差 11 的表述里，报告 §1.1 只申报了 o1 维度的 sanitize；红队报告明确要求"m-R1 必须进入批 2 收口清单"，实际已修但申报粒度不足（同批 1 m3 性质，不影响结论）。锚点：施工报告-批2.md §1.1/§4；`frontend/src/stores/ai.ts:197-203`。

### 观察（4）

- **o1** `appliedSeqs` 每消息无上界增长：一轮数百帧即数百个 number 常驻；不入镜像、刷新即清，实际内存增量微小。若未来消息生命周期变长（跨会话驻留）可设滚动上限。锚点：`frontend/src/types/ai.ts` appliedSeqs 注释、`utils/parts.ts:357-364`。
- **o2** 滚动 watcher 指纹只取**末段**（末段 kind/text/status）+ parts.length：非末段的中段工具状态变化（如 [text, tool(pending→succeeded), text] 的 tool 段翻转）不触发自动滚动；实际由后续帧（结果后模型继续说话）恢复触发，盲区窗口小。锚点：`AiPanel.vue` 滚动 watch（批 2 改写处）。
- **o3** CDP 冒烟复现配方口径：仓库 vite 默认 dev 端口实为 **5200**（`strictPort:true`），批 1/批 2 报告均按 5202 表述——复现需显式 `VITE_DEV_PORT=5202` 且 `VITE_API_BASE` 指向后端（默认代理 8081 会与 SMOKE_API 打架）。施工报告观察 12 未披露此步骤，后来者按字面复现会踩坑。锚点：`frontend/vite.config.ts`、施工报告-批2.md §5 观察 12。
- **o4** `stop()` 本地停止路径不写段终态：与「done/error 帧收口 + send finally 标 done」的双路径设计自洽（后端 cancel 会驱动终帧或 finally 收尾；若两者皆失，刷新后 sanitize 因消息已 done 而收口残留），链路成立但较长，登记备查。锚点：`frontend/src/stores/ai.ts` stop()。

## 6. 总结论

**PASS（无条件）** —— 独立复现四连全绿（77/77 + typecheck 0 + build；442/0/0/0 · 70 XML；PASS=29/GFSKIP=0/Q8 2-0；12 PASS + 1 SKIP），数字与施工报告逐项一致；复核重点 9 项全部通过；红队批 1 遗留（m-R1/m-R2/o-R1/o-R3）处置全部对题、o-R3 经真 Redis 动态实证；偏差 11 条全部属实、未发现第 12 条语义性隐瞒偏差；3 条 minor 均为文档计数/申报粒度/死码定性问题，不触及代码正确性，其中 m2/m3 建议带入批 3（③a/③b）处置清单。

- 权威翻转的三个硬不变式（parts 唯一真值、deriveProjections 唯一派生写口、白名单仅 delta/reasoning 锁步两处）经独立 grep 复算与施工方留证交叉吻合，且由翻转前后两批共 77 例 + 6 次逐点变异留证锁定。
- 帧级守卫（ownedByActiveRound/宿主/runSeq/旧轮迟到帧）原样保留，seq 幂等三态用例经变异思想实验证非恒真。
- 视觉等价性在无工具轮/纯工具轮两态成立；「工具夹文本」顺序显真为目标行为，挂起卡跟随工具段位置经组件用例与 CDP 冒烟双重覆盖；复制按钮位置变化（观察 5）留待批 3 CDP 人工面确认。
