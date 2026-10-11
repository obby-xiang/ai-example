> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 1 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 红队报告：对话时序包 批 1（双写 + R-130/R-131/R-132/R-133）

| 项 | 值 |
|---|---|
| 执行者模型 | **DeepSeek V4 Pro**（红队 / 对抗审查） |
| 授权来源 | **Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）** |
| 审查对象 | 主仓库 HEAD=**7879f53** 工作树在制品 7 文件（4 改 + 3 新，未提交） |
| 审查日期 | 2026-10-11 |
| 厂商互异 | 红队（DeepSeek）与施工（Kimi K2.8）、复核（GLM-5.3）、终审（Kimi K3）互异 ✓ |

---

## ① 锚定核验声明

- 锚定 patch：`<SCRATCH>/chat-timeline-pkg/红队锚定-批1/diff-b1.patch`
- SHA-256：**60aacbbb91f2bf444218d1579b30c414bb9e1fb8f62a5302c089a64df6409e65**
- 字节数：**59554**
- 结论：与任务卡给定值**逐字节一致**，锚定通过。

工作树一致性核验（`git hash-object` 逐文件比对 patch 后像 blob，全值前缀一致）：
- `frontend/src/types/ai.ts` → `e12b023…` ✓
- `frontend/src/stores/ai.ts` → `e102e9b…` ✓
- `frontend/src/components/AiPanel/AiPanel.vue` → `dca00d1…` ✓
- `backend/.../RedisChatMemoryRepository.java` → `33cea9a…` ✓
- `backend/.../RedisChatMemoryRepositoryDeleteTest.java` → `6985165…` ✓
- `frontend/tests/parts-double-write.spec.ts` → `51f602f…` ✓
- `frontend/tests/restore-reconcile.spec.ts` → `d5cd774…` ✓

**声明：审查字节与锚定一致；全程未发现工作树与 patch 不一致（无人改动）；全程零 git 写操作、零代码改动、未读 .env。**

---

## ② 独立复现（机械活下沉子智能体实测）

| 项 | 实测 | 判定 |
|---|---|---|
| 前端 `yarn test` | Test Files **5 passed (5)** / Tests **37 passed (37)**，Duration 1.86s，exit 0 | 绿 |
| 后端定向 Maven `-Dtest=RedisChatMemoryRepositoryDeleteTest,AiControllerHistoryDeleteTest` | Tests run **13 / Failures 0 / Errors 0 / Skipped 0**，BUILD SUCCESS，exit 0 | 绿 |

与 GLM 复核报告 §2 数字一致（37/37、删除路径 13 例）。

---

## ③ 攻击面 7 项逐项攻击过程与结论

### 攻击面 1：双写竞态

- **同帧内旧投影与 parts 双写交错窗口**：【实测】不存在。`handleFrame`（`stores/ai.ts:1395-1728`）整个 switch 无任何 `await`，`upsertToolRun`/`upsertToolPart` 均为同步内存写且语句相邻；SSE `onFrame` 回调单线程事件循环假设成立。唯一的异步写路径 `runFrontendTool`（:1842）在 `await executeFrontendTool` 之后，其 `upsertToolRun`(:1853) + `upsertToolPart`(:1861) 仍相邻同步，双写对原子。
- **writeMirror 异步落盘与内存双写时序**：【实测】无漂移。`$subscribe` deep + 300ms 防抖（:450-461），`writeMirror`(:203) 在 fire 时读整份 `messages` 快照（含 `parts` 与 `toolRuns`），两投影同同步块写，故镜像永处一致态。
- **旧轮迟到帧守卫绕过（宿主消息被删除/迁移）**：【实测】正常路径不绕过。`resetSession`(:1281) 同步清 `messages` + `activeRoundHostId=null` + `currentAbort=null`，迟到帧 `ownedByActiveRound=false` 且宿主查找 miss → 整帧丢弃(:1406-1410)。**唯一缺口**：`loadHistory` 历史分支(:575)整体替换 `messages` 时**不清** `activeRoundHostId`，若此时有在跑轮，迟到帧 `ownedByActiveRound` 仍 true、宿主 miss 后落到 `currentAssistantMessage()`（错误宿主）。但 `loadHistory` 仅挂载单点调用（`AiPanel.vue:743`），轮次进行中不可达【推断】；且该缺陷 pre-existing（非本批引入），本批双写只是把 parts 写到与旧投影相同的错误宿主，不产生新漂移。

### 攻击面 2：对账绕过（R-130/R-132）

- **resetSession→快速 restore→旧快照迟到**：【实测】拦截。入口捕获 `sid`（:636），`resetSession` 换 `this.sessionId` 后 `this.sessionId !== sid` 命中，早返回。
- **同会话 send() 新轮与 reattach 竞态**：【实测】拦截。`send`(:827) 换 `currentAbort` 句柄，`currentAbort !== abortAtEntry` 命中。
- **currentAbort 换柄点是否遗漏第四处**：【实测】grep 得赋值点 7 处，非报告所述 3 处：置 controller 的 `:755/:827/:973`（send/reattachActive/resumeSnapshotStream）+ 置 null 的 `:790/:882/:1021`（三 finally）+ `:1292`（resetSession）。`resetSession` 由 sid 对账覆盖（不构成遗漏）；但**三 finally 置 null 恰构成 ABA 缺口**（见 o-R2）。
- **快照整体丢弃的资源泄漏**：【实测】无。早返回(:647-649)发生在 `resumeSnapshotStream` 调用(:737)之前，不起续收连接；快照重建里的 `activeForm.deadline = null`(:726) 不武装计时器，无定时器泄漏。

### 攻击面 3：Lua 原子性反查（R-131/R-133）

- **脚本在异常参数下行为**：空 id 被 `AiController` `hasText` 挡（`AiController.java:568`）；非法字符/超长键由 `KEY_PREFIX + id` 拼键，仅影响键名、无命令注入；DEL 不存在键返回 0、SREM 空集返回 0，天然幂等【实测】。
- **execute 序列化/反序列化吞错**：【实测】不吞。`DefaultRedisScript<Long>`+`Long.class` 返回 DEL 计数被忽略（o2 已记），脚本语法/运行时错误由 `execute` 抛异常传播，定向测试 13 例全绿。
- **SISMEMBER 判存与 Lua 删除间 TOCTOU**：【推断】pre-existing。`deleteHistory` 先 `chatMemory.clear()`（:582，经 `ChatMemory.clear→deleteByConversationId` 走 Lua）再 `isConversationIndexed`(:590)，其间并发 `convergeMemory` 可重写，`AiController` JSDoc(:615) 已如实登记「响应时点事实」。
- **saveAll 非原子是否被低估**：o2 已登记，但登记口径不完整——**`saveAll` 空列表分支（:151-156）本身就是一条未 Lua 化的删除路径**（`delete` + `SREM` 拆两步，与已修的 `deleteByConversationId` 同构同漏洞）。见 o-R3。
- **Cluster hash tag 约束**：JSDoc(:54-56) 已登记，本仓单实例，无环境实测【假设】。
- **哨兵键碰撞**：`conversationId="__ids__"` ⇒ `key = "chat:mem:__ids__" = 索引键`，Lua DEL 整删索引；旧实现 `delete(key)` 同病，非本批引入。见 o-R4。

### 攻击面 4：注入 / 越权

- **conversationId 进 Lua KEYS/ARGV 拼接注入面**：【实测】无。`redis.execute(script, List.of(key(...), CONVERSATION_IDS_KEY), conversationId)` 以独立参数传入，非字符串拼脚本体，无 Lua 注入；`key()` 仅字符串拼接，无命令注入。
- **删除接口幂等 200 掩盖越权删除**：【实测】无新增越权面。`deleteHistory` 端点本无鉴权（单用户本地应用、无多租户概念），`SessionGate` 只读报告不 409 是既有口径（`AiController.java:574-578` JSDoc），本批仅改 `deleteByConversationId` 内部 2 步→1 Lua，未触碰授权面。
- **前端会话切换旧会话 parts 泄入新会话 UI**：【实测】无。`parts` 本批零渲染（grep 证实 `AiPanel.vue` 只读 `toolRuns`/`pendingCall`），`resetSession` 同步清 `messages`，R-130 对账拦旧快照写回。

### 攻击面 5：测试无效化（每个新测试 ≥2 变异体判定）

- **parts-double-write.spec.ts 例①（text→tool→text）**：删 `tool_start` 双写→`toolRunsOf===toolPartsOf` 红；删 `tool_result` 双写→`parts[1].status==='succeeded'` 红；`tool_result` 改新建段→`kinds` 红；`sealTrailingStreamPart` 删→`parts[0].streaming===false` 红。**有效**。
- **restore-reconcile 负例①（sid 失配）**：只删 :647 sid 守卫→旧快照写回、`messages.length===0` 红（abort 句柄在 resetSession 后为 null 不救场）。**有效**。
- **restore-reconcile 负例②（abort 失配）**：只删 :647 abort 守卫→`activeRunId==='run-live'` 红。**有效**；但把守卫换成 `!this.loading` 仍绿（send 置 loading=true 不落），说明等价观测面绑定的是 `currentAbort` 而非其它轮次信号——与报告 o5 同族的绑定敏感性问题。
- **RedisChatMemoryRepositoryDeleteTest 例①/②**：键序错→红；`delete`/`opsForSet` 重出→`never()` 红；脚本体删 SREM→test② 红。**有效**。但脚本内部**硬编码错误键、DEL/SREM 顺序颠倒、SREM 先于 DEL**→两例仍绿（结构性锁不锁语义，o3 已登记 + E2E TC19 真实 Redis 补偿）。
- **m2（GLM 未留 TDD RED 证据）补偿是否充分**：对账两负例的思想实验补偿充分（上述变异判定证伪恒真）；但双写测试覆盖不完整，补偿不充分（见 m-R2）。

### 攻击面 6：类型与映射

- **pendingCall approved→running 映射**：类型上 `'approved'` 是唯一非 `ToolRunStatus` 的 pendingCall 状态，映射必要【实测】。但 `migrateMirror` 用 pendingCall 派生值**覆盖** toolRun 派生段 status 是错的（m-R1）。
- **ToolRunStatus 枚举扩展**：`AiPanel.vue` `toolTagType`(:512) 与 `toolStatusLabel`(:538) 穷举 9 态，`Record<ToolRunStatus,string>` 类型强制 + 9 键齐全【实测】，无漏消费。
- **migrateMirror `status==='pending'` 判据语义等价主张**：`pendingCall.kind ∈ {CONFIRM, FRONTEND}` 类型强制，`'pending'` 只可能来自这两类挂起，等价成立【实测】；措辞差异 m3 已登记。构造反例失败（等价主张本身成立），但**该判据被用于覆盖而非仅标 `requiresAction`，引出 m-R1 的真反例**。

### 攻击面 7：requiresAction 残留面（评估是否应升 MAJOR）

- 【实测】`requiresAction` 全仓（`frontend/src`）**零读取点**：grep 证实仅对象字面量写入（ai.ts 14 处）与类型声明（types/ai.ts:251/268），无任何渲染读取。故残留 true 在批 1 完全不可见。
- 【推断】随镜像 v2 持久化的残留（o1）是批 2 权威翻转前收口项，但当前无用户可见危害。
- **结论：维持 observation，不升级 MAJOR**（升级证据不足：无当前危害、无崩溃/安全面、明确在批 2 范围外）。o1 口径成立。

---

## ④ 发现清单（红队新增；GLM 已登记的 3m/5O 逐条复核全部属实，此处不重复计数）

**BLOCKER：0　MAJOR：0　minor：2　观察：4**

### minor（2）

- **m-R1**　`migrateMirror` 用 `pendingCall.status` **覆盖** toolRun 派生段的 `status`，在存量 v1 镜像「toolRun 已终态 + pendingCall 仍 pending/approved」时产出与旧投影不一致的段状态，违反本批核心不变式「两侧逐字一致」。
  - 锚点：`stores/ai.ts:188-195`（`parts.splice(index,1,{...existing, kind2, status: pending.status==='approved'?'running':pending.status, ...})` 覆盖了 :175 由 `run.status` 派生的段 status）。
  - 反例①（已完成 CONFIRM 轮）：v1 镜像 `toolRun.status='succeeded'` + `pendingCall.status='approved'`（confirm_decision:1544 置 approved，tool_result:1498 置 succeeded，pendingCall 永不清）→ 迁移得 part `status='running'`，而旧投影 `toolRuns='succeeded'`。
  - 反例②（FRONTEND 本地已执行、结果帧未到）：`toolRun.status='succeeded'` + `pendingCall.status='pending'` → 迁移得 part `status='pending'`。
  - 证据：`parts-double-write.spec.ts:1096-1109` 测试⑤ 显式断言 part `status:'pending'` 而 toolRun `status:'succeeded'`——**测试本身把该漂移锁成了行为**。
  - 危害：批 1 无视觉影响（parts 不渲染）；批 2 权威翻转若沿用此迁移口径，刷新后已完成轮的工具卡状态「回退」为执行中/挂起。修复方向：段 `status` 应以 `run.status` 为权威，`pendingCall` 只供 `kind2`/`requiresAction`。

- **m-R2**　双写回归锁覆盖不完整 + 复核 §3.1「删除任一双写即红，非恒真」断言过强。
  - 锚点：`parts-double-write.spec.ts` 全文 vs `stores/ai.ts` 各写点。
  - 证据：11 处 `upsertToolPart` 调用中，`:1591`（frontend_tool_request）、`:1630`（frontend_tool_result）、`:1680`（suspended）、`:1148`（openGenerativeForm 失败收尾）、`:1861`（runFrontendTool 本地结局）5 处 + `loadHistory` 历史分支 parts 构造（`:587-601`）共 **6 个写点无任何测试驱动**。删除其中任一 `upsertToolPart` 仍 37/37 绿【推断：基于覆盖矩阵，未实跑变异——禁止改代码】。
  - 影响：该 6 写点的「改坏仍绿」风险高于复核所述；`工具→挂起→前端执行→回灌` 这条真实链路（frontend_tool_request→runFrontendTool→frontend_tool_result）恰好是**最无回归锁**的一段。

### 观察（4）

- **o-R1**　段 `streaming` 封口不对称：`openStreamPart`（:238）在 reasoning/text 交错时**不封口**被中断的上一段，与 tool 插入的 `sealTrailingStreamPart`（:255，仅 :1828 调用）不对称；`done`/`message_end` 也不封口末尾段。`part.streaming` 全仓零读取（grep 证实仅 :1454/:1463 两处写入），且后端当前不产 reasoning 帧 ⇒ 生产不可达。测试 `segDigest`（spec:883-889）刻意剔除 streaming、test③ `toMatchObject` 不锁 streaming，未覆盖该语义。批 2 启用 reasoning 渲染前应补封口。
- **o-R2**　对账 abort 句柄为「采样值」而非「单调轮次纪元」，存在 ABA 缺口：若 `runsCurrent` await 窗口内一轮「起又结」，`currentAbort` 走 null→controller→null，`currentAbort !== abortAtEntry`（null===null）漏判、旧快照写回。实测不可达（runsCurrent 往返 ms 级、AI 轮次秒级）【推断】。另：复核 §3.2「currentAbort 更全（覆盖三换柄点）」主张不成立——`activeRoundHostId` 与 `currentAbort` 在 7 处赋值点成对同步（:755/:827/:973/:790/:882/:1021/:1292），二者**等价**而非「更全」。
- **o-R3**　`saveAll` 空列表分支（`RedisChatMemoryRepository.java:151-156`）是**未 Lua 化的删除路径**：`delete` + `SREM` 拆两步，与已修的 `deleteByConversationId`（:164-165）同构同漏洞，R-131/R-133 的崩溃/并发窗口在该分支仍存。o2 仅按「写入路径」登记，未点名此分支的「删除语义」。是否实际可达待确认（Spring AI `MessageWindowChatMemory.clear` 走 `deleteByConversationId`，空分支或仅为防御）【推断】。
- **o-R4**　保留哨兵键碰撞面（pre-existing）：`conversationId="__ids__"` ⇒ `key() = "chat:mem:__ids__" = CONVERSATION_IDS_KEY`（:61/:63/:165），Lua `DEL KEYS[1]` 整删会话索引。旧实现 `delete(key)` 同病、非本批引入；正常 `createSessionId()` 产出 UUID 不可达，未鉴权端点可构造；影响=索引丢失（数据键不删），下次 `saveAll` SADD 自愈。

---

## ⑤ 总结论

**带条件 PASS** —— 红队新增 **0B / 0M / 2m / 4O**（不含 GLM 已登记的 3m/5O，后者经逐条复核全部属实）。

- **成立的部分**：五连验收独立复现全绿（37/37、13/0/0/0）；live 双写路径真原子（同帧同步、无交错窗口、镜像快照一致）；R-130/R-132 对账负例断言有效且修复方向正确；Lua 删除真原子且经真实 Redis 实证；注入/越权无新增面；枚举消费穷举完整；requiresAction 残留维持 observation 不升级。
- **推翻/削弱的部分**：
  1. `migrateMirror` 的 status 覆盖逻辑是**复核漏掉的真语义缺陷**（m-R1），违反本批「两侧逐字一致」核心不变式，必须在批 2 权威翻转前修掉，否则刷新后已完成轮工具卡状态回退。
  2. 复核 §3.1「删除任一双写即红」**实质过强**（m-R2）：6 个双写写点无回归锁，含最真实的「前端工具执行」链路。
- **带条件**：批 1 对「视觉零变化 + live 双写一致 + 对账有效 + 删除原子」的交付目标达成，可放行；但 m-R1 与 m-R2 必须进入批 2 收口清单（m-R1 为必修，m-R2 为补测），o-R1/o-R2/o-R3/o-R4 随批 2 一并评估。
