> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 2 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 红队报告：对话时序包 批 2（子包②权威翻转）

| 项 | 值 |
|---|---|
| 执行者模型 | **DeepSeek V4 Pro**（红队/反方审查） |
| 授权来源 | **Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）** |
| 审查对象 | 主仓库 `<MAIN_REPO>`，HEAD=**2f455ce**，工作树在制品 11 文件（改 6 + 新 5，未提交） |
| 审查日期 | 2026-10-11 |
| 厂商互异 | 红队（DeepSeek）与施工方（Kimi K2.8）、复核方（GLM-5.3）互异 ✓ |
| 目标 | 推翻 GLM 复核 PASS（0B/0M/3m/4O） |

---

## 0. 锚定核验声明（硬性步骤）

```
sha256sum <SCRATCH>/chat-timeline-pkg/红队锚定-批2/diff-b2.patch
= 5a050e3256045fb3c2c6e42d4ef244aedd6bc6c2131cf917c3a8ee823860cd0c  ✓
字节数 = 168188 ✓（锚定说明口径一致）
^diff 段计数 = 11 ✓（patch 全文逐行实读，11 段 diff --git 与锚定清单顺序逐一对应）
```

- 审查全程工作树与 patch 一致：开工 `git rev-parse HEAD` = `2f455ceed9a797bc7f86b4b76e03f08cfe932d4b`（短 2f455ce）；`git status --porcelain -uall` 恰 11 条 = 6 M + 5 ??，与锚定清单逐条吻合；跑测试前后两次 status 逐字节一致。
- **审查字节与锚定一致**，未发现工作树被改动的迹象。
- 纪律：零 git 写、零代码改动、未读 `.env`、未触碰 chat-timeline-pkg 既有文件（本报告为唯一新增写入）；临时文件落 `%TEMP%/dsb2-*`；未起任何自留进程。

## 1. 范围与方法（含子智能体模型自证）

- **必读输入按序消化**：锚定说明 + diff-b2.patch（3330 行全量逐行）→ 复核报告-批2.md（§3 九项与 o1~o4）→ 施工报告-批2.md（偏差 11 + 观察 12）→ 决策卡.md → 11 文件本体全文实读（`utils/parts.ts` 364 行、`stores/ai.ts` 1807 行、`AiPanel.vue` 810 行、`types/ai.ts` 442 行、后端 `RedisChatMemoryRepository.java` 181 行 + `SaveAllEmptyTest.java` 130 行、6 个测试文件）。
- **机械活下沉 3 棒，全部显式钉 `model=DeepSeek/deepseek-flash`（DeepSeek V4 Flash）**（互备 GLM-5.3-Flash），禁靠会话默认：
  - 棒① 前端 `yarn test` + `yarn typecheck`（coder，agent-473）：77/77、8 文件、exit 0；typecheck 0 error；跑前后 git status 一致。
  - 棒② 后端定向 `RedisChatMemoryRepositorySaveAllEmptyTest`（coder，agent-474）：3/0/0/0、BUILD SUCCESS、真 Redis 例 Skipped=0 真实执行。
  - 棒③ 独立核验「frontend_tool_result 终态丢失」主张（explore，agent-475）：逐条给出行号证据。
  - 每棒原始输出落盘 `%TEMP%/dsb2-fe-test.log` / `dsb2-be-test.log` 供抽证。
- **对抗反查**：翻转等价性反查（§3.1）、seq 幂等绕过（§3.2）、守卫拆解（§3.3）、sanitize 攻防（§3.4）、六残留点收口语义（§3.5）、o-R3 反查（§3.6）、渲染回归（§3.7）、性能证伪（§3.8）、测试无效化（§3.9）。

---

## 2. 独立复现结果（3 棒，全部真实执行）

| # | 项 | 实测 | 与 GLM 复核对照 |
|---|---|---|---|
| ① | 前端 `yarn test` | **Test Files 8 passed (8)，Tests 77 passed (77)，exit 0**（3.78s；日志 77 条 `✓` 自洽） | 与复核 77/77 一致 |
| ① | 前端 `yarn typecheck` | **exit 0**，`grep -c 'error TS'` = 0 | 一致 |
| ② | 后端定向 SaveAllEmptyTest | **Tests run 3 / Failures 0 / Errors 0 / Skipped 0**，BUILD SUCCESS，真 Redis 例真跑（ping=PONG） | 一致（定向而非全量，符合权限） |

数字与 GLM 复核四连的前端/后端部分一致。E2E 桩与 CDP 冒烟本批未复跑（权限仅放行 vitest + 定向 Maven；此两项非本批新增攻击面，且 GLM 已四连，风险可控）。

---

## 3. 对抗攻击面 9 项逐项过程与结论

### 3.1 翻转等价性反查 ——【实测】找到 1 处不等价（MAJOR-1 的根因）

`deriveProjections`（`parts.ts:157-189`）与批 1 双写期投影逐字段核对：

- `content` = text 段 join、`reasoning` = reasoning 段 join：与批 1 "按到达顺序拼接"逐字等价——delta/reasoning 锁步 `+=` 与 join 同源（段只切显示不丢字符）。构造 tool 夹文本、交错 reasoning、多轮混合帧序列对账，**均等价**。
- `toolRuns` 逐字段映射（`kind ← kind2`、`result/reason/messageId` 原样）：与批 1 `upsertToolRun` 形状一致。但**不等价点在此暴露**——
- **pendingCall 语义：批 1 保留终态挂起投影（pendingCall.status ∈ {rejected/expired/cancelled/succeeded/blocked/approved…}），批 2 改为「最后一个 requiresAction 段」派生，终态段 requiresAction=false ⇒ pendingCall=null。** 这一差异施工方已在观察 1 申报为「终态挂起卡不再保留，信息由工具卡段状态 tag 承载」。
- **证伪该申报**：工具卡段状态 tag 由 `part.status` 驱动，而 `applyFrameToParts` 的 `frontend_tool_result` 分支只按 `frame.ok` 定 `status`、**完全不读 `frame.status`**（`parts.ts:318`），且 `localTerminal` 集合不含 `cancelled`（`parts.ts:315-317`）。后端对取消/超时/被拒三类结局一律发 `ok=false`（`ConfirmGate.java:292/333/526`、`SpToolCallingManager.java:305`）。⇒ 工具卡段最终落 `failed`，而非 `cancelled`/`expired`/`rejected`。**批 1 由 pendingCard 承载的正确终态，批 2 移除 pendingCard 后彻底丢失，且未迁入工具卡 tag——「等价/信息承载」主张对 frontend_tool_result 路径为假。**（详见 §4 MAJOR-1。）
- 附：多 requiresAction 段并存时，批 2 取「最后一个」，批 1 单槽 pendingCall 亦被最后一帧覆盖——两向等价；但批 2 在「后一个先被收口、前一个仍待决」时能正确浮现前一个（批 1 会丢），属批 2 的**改善**而非缺陷，不计。

**结论**：content/reasoning/toolRuns 三投影等价成立；pendingCall 维度存在 1 处真实不等价，即 MAJOR-1。

### 3.2 seq 幂等绕过 ——【实测】去重本身正确，发现 1 处二维错位（观察）

- `alreadyApplied`（`parts.ts:352-354`）= `appliedSeqs.includes(seq)`，`markApplied`（`:357-364`）去重记账；`handleFrame` 守卫 `seq !== null && alreadyApplied(...) → return`（`ai.ts:1515-1517`）。三态（null 照收/重复去重/乱序只去重）用例经变异思想实验均非恒真。
- **攻击尝试——seq 回绕/跨轮复用/快照重建重放**：`appliedSeqs` 是**消息级**记账，`runSeq`（`ai.ts:1495`）是 **runId 级**记账，两维不共享。快照重建（`restoreSuspendedSnapshot` 落段到既有消息后 `resumeSnapshotStream` 续收）时，宿主消息的 `appliedSeqs` 在刷新后为空（不入镜像），后端按 `archiveMaxSeq` 补发，不撞号；`reattachActive`/`resumeSnapshotStream` 均先 `previous?.abort()` 换身份，双连接竞态窗口内同 seq 落在**不同消息**（各自 `appliedSeqs` 空）不会被去重——但这是批 1 无 seq 记账时代的既有形态，批 2 只增不减。
- **可放行重复帧的构造**：同一消息先后收两个 run 的帧（同 seq 空间）时，第二个 run 的帧会被第一个 run 的 `appliedSeqs` 误判去重。当前无任何路径让同一消息跨 run 收帧（正常每轮新建消息，仅 snapshot 续收复用消息且单 run），故**不可达**，记观察。
- `alreadyApplied(message, null)` 恒 false + store 侧 `seq !== null` 短路，null 帧永不去重——正确（与「旧帧照收」口径一致）。

**结论**：seq 幂等三态成立，无 B/M；二维错位为理论面观察。

### 3.3 守卫拆解 ——【实测】等价，宿主选择改读 parts 无概率劣化

- `handleFrame` 前置守卫（`ai.ts:1479-1517`）`ownedByActiveRound`/宿主查找/`runSeq` 记最大 seq/旧轮迟到帧只收敛自身消息——与批 1 逐句一致。
- `restoreSuspendedSnapshot` 宿主选择由 `toolRuns.some` 改 `parts.some`（`ai.ts:729-731`）：批 2 里 parts 与 toolRuns 由 `deriveProjections` 同源派生，`upsertToolPart` 后均紧跟 `deriveProjections`，不存在「有段无投影」或「有投影无段」的分叉；`find` 首匹配两向同序。迟到帧落错误宿主的概率不因改读 parts 而变。
- 唯一语义差异（`messages.find` 首匹配 vs `formHostMessageId` 反向末匹配）仅在「同一 toolCallId 出现在多条消息」时显形，而 toolCallId 为服务端 UUID、正常单轮唯一，不可达，记观察。

**结论**：守卫保留与宿主选择等价，无新增缺陷。

### 3.4 sanitizeMirrorResidues 攻防 ——【实测】幂等成立，m2 死码确认

- 幂等性：`forEach + splice(替换)` 不改变数组长度与遍历序，每元素访问恰一次；二次执行无 `requiresAction=true` 残留 ⇒ `changed=false` ⇒ 不回写（`savedAt` 不变）。构造重复调用序列无反例。
- **m2 死码**：`message.done !== true → continue`（`ai.ts:225-227`）在唯一调用路径（loadHistory 镜像分支，`ai.ts:574-578` 先强制 `done:true`）恒不可达；且镜像 `Pick`（`types/ai.ts:341`）不含 `done` 字段。若未来调用方直接对**镜像原始数据**（无 done 字段，`undefined !== true`）调用，会**全部跳过**、静默失效——GLM m2 定性准确。构造「未来调用方传内存态消息（带真实 done=false）」场景：守卫正确跳过（防御价值成立）；构造「传镜像态消息」场景：守卫误跳过（防御失效）。两种都非当前可达，维持 m。
- sanitize 与 writeMirror 时序：`startMirror` 防抖 300ms 与 `loadHistory` 内显式 `writeMirror` 并存，单次 loadHistory 内可能双写（显式写 + 防抖写），但两者都写同一份已 sanitize+derive 的一致状态，无竞态脏写。

**结论**：幂等成立，m2 死码复核确认。

### 3.5 六残留点收口语义攻击 ——【实测】MAJOR-1 归属此面（applyFrontendToolTerminal 分叉 + 帧序）

- done/error 双路径 `expireResidueToolParts` 收口：done 用例双挂起段 `[['expired',false],['expired',false]]` 正确；error 同口径。
- confirm() 乐观置位/DUPLICATE/失败回退：true→running、false→rejected、DUPLICATE→running、失败回退→pending+requiresAction=true。**永久挂起攻击**：回退仅「先乐观 running 再 await 失败回退 pending」，若后端已处理但响应丢失，用户重试撞 DUPLICATE→running，自愈；不存在永久挂起。DUPLICATE 路径不置 `suspended=false` 为批 1 既有（diff 未触碰该行），非本批回归，记观察。
- **applyFrontendToolTerminal 的 first-wins 与 GF/非 GF 分叉 + 帧序颠倒反例 = MAJOR-1**：`handleFrame` 的 `frontend_tool_result` case **先** `applyFrameToParts`（`ai.ts:1594`，把段 status 按 `frame.ok` 置为 succeeded/failed）**后** `applyFrontendToolTerminal`（`ai.ts:1596`，本想把 `frame.status` 映射终态写段）。但 `applyFrontendToolTerminal` 的 GF 分支守卫是 `part.status === 'pending' || part.status === 'cancelled'`（`ai.ts:1733`），此时段已被改写成 succeeded/failed ⇒ 守卫恒假 ⇒ 终态广播**永不落段**。非 GF 分支本就只收 requiresAction 不动 status。这是「帧序颠倒反例」的直接成立形态，详见 §4。
- error 帧收挂起卡后后端补发终帧的双收口幂等：`expireResidueToolParts` 只对 `requiresAction=true` 段降级，已降级段（requiresAction=false）不重复触发；后到终帧（done）再次 `expireResidueToolParts` 无残留 ⇒ 幂等，无双收口冲突。

**结论**：done/error/confirm 收口正确；applyFrontendToolTerminal 的帧序颠倒反例成立（MAJOR-1）。

### 3.6 o-R3 修复反查 ——【实测】通过

- `saveAll` 空/null 分支委托 `deleteByConversationId`（`RedisChatMemoryRepository.java:157-161`），复用 `DEL+SREM` Lua 原子脚本；非空路径 `delete + rightPushAll + applyTtl + SADD` 未变。
- null/undefined/空白 id 路径：`key(conversationId)` = `"chat:mem:" + null`（或 `"chat:mem:"`），Lua `DEL`/`SREM` 对不存在键为 no-op，不崩溃；与修复前 `delete(key())` 同态，非新增。
- 「空列表写后索引无成员」：空写语义 = 清空会话 ⇒ 索引成员应移除（`findConversationIds` 只列有记忆的会话），**一致性正确**，非新不一致。非空写 SADD、空写 SREM，两端语义对称。
- 与 Lua 交互：`DELETE_WITH_INDEX` 单次 `execute`，键序 `[数据键, 索引键]`，ARGV=conversationId；真 Redis 例前置写非空记忆后空写，断言数据键删除 + 索引成员移除，Skipped=0 真跑通过。
- SaveAllEmptyTest 3 例：`empty/nullSaveAllDelegatesToAtomicScript` 锁 `times(1) execute` + `never().delete/opsForSet` + 脚本体 DEL/SREM；`emptySaveAllOnRealRedisRemovesDataAndIndex` 真 Redis 行为等价。独立复跑 3/0/0/0。

**结论**：o-R3 修复正确，无新不一致。

### 3.7 渲染翻转回归面 ——【实测】发现 1 处死码（观察）+ M1 的渲染后果

- v-for key：`tool ? toolCallId : kind + '-' + seg`（`AiPanel.vue:167-168`）。parts 只 append/splice 不重排，索引 key 亦安全，业务稳定 key 更稳。**key 碰撞**：toolCallId 恰等于 `"text-0"` 时与 text 段 key 撞，属理论面（服务端 UUID），记观察。
- 挂起卡跟随工具段 + 单槽 pendingCall：位置用例（c1 之后、c2 之前）通过；`m.pendingCall.toolCallId === part.toolCallId` 匹配正确。
- **死码（观察，M1 的旁证）**：`AiPanel.vue:250-285` 的挂起卡终态 tag 全集（rejected/expired/cancelled/succeeded/blocked/approved/running）在翻转后**不可达**——因为 pendingCall 仅在「存在 requiresAction=true 段」时非 null，而 requiresAction=true 恒与 status='pending' 成对（confirm_request/frontend_tool_request/suspended/快照重建都落 pending），故 `m.pendingCall.status` 恒为 'pending'。这批分支残留说明作者预期终态 pendingCall 仍可能存活，与实现（deriveProjections 终态→null）相矛盾，是「终态信息未真正迁入工具卡」的模板层证据。
- isTyping：`streaming && 无任何非空 text 段`（`AiPanel.vue:528-530`）。仅 reasoning 段流式、无 text 时三点与思考链同现——后端当前不产 reasoning 帧，理论面，记观察。
- 复制按钮 N10：判据 `parts.some(text 非空)`，内容取 `m.content`（join 派生同源）。合成态用例（段有正文/投影空、投影有正文/段空）锁方向正确。边界：text 段为纯空白 `' '` 时 `.some(part.text)` 判 truthy（`' '` truthy）出按钮，但 `copyMessage` 里 `m.content=' '` truthy 也走复制——一致，无边界反例。
- **M1 的渲染后果**：取消/超时/被拒的前端工具段 status 落 `failed`，工具卡 tag 显示「执行失败」（danger 红），而本应显示「已取消/会话已失效/已拒绝」——用户可见的错误终态标签。

**结论**：段序/挂起卡位置/N10 正确；发现 1 处死码观察；M1 的渲染后果已明确。

### 3.8 性能主张证伪 ——【实测】成立

- delta/reasoning 锁步不调 derive：`DERIVE_FRAME_TYPES` 不含 delta/reasoning（`ai.ts:258-270`），`handleFrame` 仅对工具/边界/终态帧调 `deriveProjections`（`ai.ts:1673-1675`）。锁步期 `content/reasoning` 同步 `+=`，`toolRuns` 数组身份不变（`toBe(runsBefore)` 结构性断言锁定）。
- 「锁步期间投影被读取」窗口：滚动 watcher（`AiPanel.vue:743-752`）在锁步期读 `last.parts[末段]` 与 `last.pendingCall.status`——delta 帧在锁步内已同步改 text 段文本（reactive），watcher 能触发跟随；pendingCall 在 delta 期不变（正确）。镜像 `$subscribe` 防抖 300ms 后 `writeMirror` 读的是同步一致态（content 与 parts 同帧内都更新）。**无锁步期投影被读到脏值的窗口**。
- 唯一代价：`message.content += frame.text` 每 token 字符串拼接 O(n)（与批 1 同），非本批回归。

**结论**：性能主张成立，未证伪。

### 3.9 测试无效化 ——【实测】抽样判定（未实改代码，变异思想实验）

- **parts-reducer.spec.ts（15 例）**：变异「`deriveProjections` 丢 `reason` 字段」→ ⑧ 用例 `toEqual` 断言 `reason: null`，丢后变 `undefined ≠ null` ⇒ RED；「`alreadyApplied` 改 `seq <= max` 连续性判定」→ 乱序用例 `'五三'` 变 `'五'` ⇒ RED；「delta 也调 derive」→ `toolRuns` 身份 `toBe` ⇒ RED。三变体均被锁。**盲区**：`pendingStatusOf` 对 `unknown/failed` 的映射无用例，但 requiresAction+非 pending 生产不可达，非真实缺口。
- **parts-residue-cleanup.spec.ts（21 例）**：变异「`applyFrontendToolTerminal` 的 mapped 终态写错（如 cancelled→blocked）」→ **无任何用例给 frontend_tool_result 传终端 status 并断言段 status**，故该变异**不红**——这恰是 MAJOR-1 能绿灯出货的原因。其余六写点变异（施工方 m2-mutation-1..6）与独立思想实验确认有牙。
- **ai-panel-parts-render.spec.ts（4 例）**：变异「v-for 改 index key」→ 段序/挂起卡位置/N10 三用例仍绿（append-only 下 index key 行为等价），R4-3 属 lint/设计规约非行为锁，可接受；「挂起卡 v-if 拆散到消息末尾」→ 位置用例 `otherTool > pendingCard` 断言 ⇒ RED，有牙。
- **parts-double-write.spec.ts（5 例，改）**：镜像迁移/等价断言改写后仍锁 join 等价 + appliedSeqs 不入镜像，有牙。
- **SaveAllEmptyTest（3 例）**：变异「空分支改回 `delete + opsForSet.remove` 两次往返」→ `verify(never()).delete/opsForSet` ⇒ RED；「`deleteByConversationId` 内部非原子化」→ 脚本体 DEL+SREM 断言 + 单次 execute ⇒ RED。有牙。
- **「删除即红」抽检**：`upsertToolRun`/`toPendingCall` 已删除，全仓无调用点（独立 grep 复核），删除后类型检查与 77 例全绿证明无残留依赖。

**结论**：新增用例总体有牙，但 MAJOR-1 所在路径（frontend_tool_result × 终端 status）无任何覆盖，是测试面的真实缺口。

---

## 4. 问题清单

**BLOCKER：0　MAJOR：1（推翻 0M）**

### MAJOR（1，新增）

- **M1　`frontend_tool_result` 帧的 `status` 字段被忽略 ⇒ 前端工具/生成式表单「取消/超时/被拒」误显示为「执行失败」，批 1 的正确终态在翻转后彻底丢失。**
  - 锚点：`frontend/src/utils/parts.ts:311-329`（`frontend_tool_result` 分支只读 `frame.ok`、`localTerminal` 不含 `cancelled`）；`frontend/src/stores/ai.ts:1589-1596`（先 `applyFrameToParts` 后 `applyFrontendToolTerminal`）；`frontend/src/stores/ai.ts:1733`（GF 终态守卫 `pending||cancelled` 恒假）；后端 `ConfirmGate.java:292/333/526`、`SpToolCallingManager.java:305`（取消/超时/被拒一律 `frontendToolResult(..., false, ...)`）。
  - 证据：【实测】代码逐行追踪 + 后端源码 grep + 独立子智能体 agent-475 五步核验全部成立。
  - 影响：`FRONTEND_CANCELLED`（用户取消 GF 表单）、`CANCELLED`（整轮取消时前端工具挂起）、`TIMEOUT`（前端工具超时）、`REJECTED_ARGUMENTS`（入参被安全闸拒）四类终态，工具卡 tag 恒显示红色「执行失败」（`toolStatusLabel('failed')`），而非「已取消/会话已失效/已拒绝」。后端状态、模型回填、续跑均正确（纯显示层），但属**批 1 → 批 2 的用户可见回归**：批 1 由 pendingCard 承载正确终态，批 2 移除 pendingCard（观察 1）时声称「信息由工具卡段状态 tag 承载」，此断言对 frontend_tool_result 路径为假——正确终态既不在 pendingCard（已删）也不在工具卡（误标 failed）。
  - 对比反差：同一文件 `tool_result` 分支**正确读** `frame.status`（`parts.ts:244`，BLOCKED/REJECTED 映射正确），`frontend_tool_result` 分支漏读，暴露为批 2 翻转时的口径不对称。
  - 测试盲区：现有 cancelled 用例（`parts-reducer.spec.ts:440/456`、`parts-residue-cleanup.spec.ts:193/216`）只走到本地取消为止，其后不投递任何结局帧，绿灯恰停在 bug 触发点之前；全仓无 `frontend_tool_result` × 终端 status 断言。

### minor（复核确认 GLM 3 条，均仍成立）

- **m1**（GLM）：施工报告 §6「新增 4」应为「新增 5」。复核确认。补充事实：任务卡所称「4 个新增 spec」中 `parts-double-write.spec.ts` 实为 HEAD 已跟踪文件（` M` 而非 `??`），真正新增文件为 5 个（3 个前端 spec + `parts.ts` + 后端测试类），锚定清单正确、仅文字计数漂移。
- **m2**（GLM）：`sanitizeMirrorResidues` 的 `done !== true` 守卫在当前唯一调用路径不可达（防御性死码）。复核确认，见 §3.4。
- **m3**（GLM）：m-R1（migrateMirror status 权威化）已修但未逐条点名申报。复核确认（`ai.ts:197-203` 已落「status 以 run.status 为权威」）。

### 观察（新增，供批 3 处置）

- **O1**　`AiPanel.vue:250-285` 挂起卡终态 tag 全集在翻转后不可达（pendingCall 恒为 pending 状态），与「终态信息迁入工具卡」的意图自相矛盾——是 M1 的模板层旁证，建议批 3 随 ③b 一并清理。
- **O2**　`appliedSeqs`（消息级去重）与 `runSeq`（runId 级记账）两维错位：同一消息跨 run 收帧会误去重，当前无路径可达，理论面。
- **O3**　v-for key `toolCallId` 与 `kind-seg` 跨类型可能碰撞（toolCallId 恰为 `text-0` 时重复 key），服务端 UUID 不可达，理论面。
- **O4**　`isTyping` 在「仅 reasoning 段流式、无 text」时三点与思考链同现；后端当前不产 reasoning 帧，理论面。
- **O5**（对 GLM o1~o4 复核确认）：o1（appliedSeqs 无上界）、o2（滚动 watcher 仅末段指纹）、o3（CDP dev 端口口径）、o4（stop() 不写段终态）均属实，维持。

---

## 5. 总结论

**部分推翻** —— 独立复跑前端 77/77 + typecheck 0 + 后端定向 3/0/0/0 与复核一致；锚定核验一致；9 项攻击面中 8 项（seq 幂等、守卫保留、sanitize 幂等、o-R3 修复、段序渲染、性能注、done/error/confirm 收口、测试有牙）维持 PASS；但**第 3.1/3.5/3.7 项交叉锁定的 MAJOR-1** 推翻了「0M」结论。

- 权威翻转的三个硬不变式（parts 唯一真值、deriveProjections 唯一派生写口、白名单仅 delta/reasoning 锁步）经独立 grep 与全文实读成立，但**「派生投影与批 1 双写期逐字段等价」这一核心等价主张在 pendingCall 维度不成立**：终态信息（cancelled/expired/rejected）在翻转后被静默丢失，而施工/复核宣称的承载载体（工具卡段状态 tag）因 `frontend_tool_result` 分支漏读 `frame.status` 而显示错误终态。
- 该 MAJOR 属纯显示层误导（后端正确性、模型回填、续跑均不受影响），不是 BLOCKER；但它在用户主动取消/超时这两条常见路径上把「已取消/会话已失效」渲染成红色「执行失败」，且无测试覆盖，属批 2「权威翻转」交付物内的真实正确性缺陷，应在合入前修复（修法：`applyFrameToParts` 的 `frontend_tool_result` 分支改用 `frame.status` 映射终态、`localTerminal` 补入 `cancelled`，与 `tool_result` 分支对称）。
- 建议批 3 处置清单：M1 修复 + 补「frontend_tool_result × 终端 status」回归用例；O1 死码清理；m2/m3 随 ③a/③b 收口。
