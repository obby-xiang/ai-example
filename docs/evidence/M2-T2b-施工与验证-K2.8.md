# M2-T2b 施工与验证（Kimi K2.8 Preview）

> 角色：T2b 施工棒（后端 T2b-D#1/#2 + 前端 T2b-D#3 + 排期 v1.4 五项顺手项）。
> 只执行不重新裁决；规格出处：docs/M2-T2-返修决策卡.md（T2b-D#1~#3 + 勘误 2/3/4/5）、
> docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md 裁决结论节（A1/B1/B3/D1/D2 处置）、
> docs/evidence/M2-T2a-修复复核-GLM-5.3.md（F1/F4 顺手项出处）、docs/M2-排期计划.md v1.4。
> 仓库：`<REPO_ROOT>`，分支 main，HEAD `2354721`（施工前 `git pull` 确认 Already up to date）。
> 全程零 git 写操作，产出未提交。不引新依赖，不改 ci.yml，不动 docs/adr。
> 证据等级：**【实测】** = 本棒实际执行/读码确认；**【推断】** = 静态推演未实跑。
> 脱敏：路径相对 `<REPO_ROOT>`，Maven 记 `<MAVEN_HOME>`，验证 scratch 记 `<SCRATCH>`，AI key 一律 `***`。

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 后端 mvn test | **Tests run: 263, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**（基线 249 + 新增 14）【实测】 |
| 前端 yarn typecheck / build | 双 EXIT=0【实测】 |
| CDP 冒烟（真实 DeepSeek 挂起轮） | 连续两轮 ①②③（含③b）全 PASS、退出码 0【实测】 |
| 顺手项 ①~⑤ | 五项全部落地【实测】 |
| 【待裁决】 | 无（2 项偏差登记见 §6，均不阻断） |

---

## 1. 改动清单（文件:行号）

### 1.1 后端

**`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`**（T2b-D#1 索引本体）
- 类 javadoc 键结构（:42-46 附近）：补 `ai:session:current:<sessionId>` 条目，含 B2 约束声明【实测】。
- 常量区：新增 `SESSION_CURRENT_PREFIX = "ai:session:current:"`【实测】。
- `save()`（:165-178 附近）：快照持久化成功后联动索引 —— status=SUSPENDED → `markSessionCurrent`；DONE/FAILED/CANCELLED → `clearSessionCurrent`；RUNNING 不动（索引保留，轮仍 current）。集中挂 save() 的判据：全部终态写入（ResilientChatService finish/cancelTerminal/fail、ResumeService 取消路径）都经此通道，无漏清面【实测】。
- 新增"session → current 轮索引"方法组（:180-253 附近）：`markSessionCurrent`（SET + 锁 TTL）、`renewSessionCurrent`（GET 匹配才 EXPIRE，同锁 watchdog 语义）、`clearSessionCurrent`（GET 匹配才 DEL，同 SessionGate.release 只删自己锁的口径）、`sessionCurrentRunId`（GET）、`sessionCurrentTtl()`（取 `app.ai.session.lock.ttl`=10m，**非** session-ttl 6h）。全部 O(1)、try-catch 降级不抛（索引是体验优化不是正确性依赖）。javadoc 写明：不承诺同 session 多页签共享（B2）、进程死亡键随 TTL 过期（与锁同寿）【实测】。

**`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`**（索引续期）
- `await()` 循环前缓存 sessionId（新增 `snapshotSessionId()`，快照缺失返回 null）；心跳分支（`!signalled`，每 2s）内 `store.renewSessionCurrent(sessionId, runId)`，与 beacon.pulse 同节拍——挂起期索引 TTL 持续续期，与锁 watchdog 对齐【实测】。

**`backend/src/main/java/com/example/configmgr/ai/web/AiController.java`**（T2b-D#2 端点）
- 新增 `GET /api/ai/runs/current?sessionId=`（:389-458 附近）：
  - sessionId 空白 → 400 裸 Map（仿 confirm() 参数校验惯例）。
  - 索引缺失 / 索引残留但快照已清 → **明确空态** `{found:false, runId:null, snapshotStatus:null, pendingEntries:[], unresolvedExternal:[], awaitingExternal:false, archiveMaxSeq:0}`（进程已死分支：索引随锁同寿过期，键缺失即落此态）。
  - 命中 → `{found:true, runId, snapshotStatus, pendingEntries:[{toolCallId,entryStatus,kind,name,arguments}], unresolvedExternal, awaitingExternal, archiveMaxSeq}`。entryStatus 取条目 status 原文 —— 已超时条目 TIMEOUT / 他端已提交 FRONTEND_RESULT 三分支语义自然落在字段上；unresolvedExternal 对齐 ResumeService.listRuns 口径（status==PENDING 且 kind≠BACKEND）；`awaitingExternal = SUSPENDED && unresolvedExternal 非空`（**D1 消歧**：快照 SUSPENDED 但条目全决 → false，不再误判为可续收）；archiveMaxSeq = `RunStore.lastEventSeq(runId)`。
  - 成功响应套 `ApiResponse.ok`（与 /runs 系列一致，前端 http.ts 拦截器按 `success` 布尔拆信封）；JavaDoc 写明三分支语义、口径对齐与 B2 约束声明【实测】。

**`backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`**（顺手项⑤后半）
- `TOOL_NAME` javadoc（:57-65）补**双侧同步约束**注释：前端判定同字面量用 `frontend/src/types/ai.ts` 的 `GENERATIVE_FORM_TOOL` 常量，本值变更必须同步该常量【实测】。

**`backend/src/test/java/com/example/configmgr/ai/conformance/InMemoryRunStore.java`**（必要连带）
- ConfirmGate.await 新调用 `renewSessionCurrent`，替身构造传 null redis 会 NPE。补 sessionCurrent 内存表 + 四个索引方法 override（renew/clear 保留值匹配语义），其 `save()` 复刻同一联动口径（SUSPENDED 建/终态清/RUNNING 保）。既有 conformance 测试全部不受影响（263 绿含全部 conformance）【实测】。

### 1.2 前端

**`frontend/src/types/ai.ts`**
- :286-293 `export const GENERATIVE_FORM_TOOL = 'generative_form'`（注释：与后端 `GenerativeFormRules.TOOL_NAME` 双侧同步，顺手项⑤）【实测】。
- :295-318 新接口 `AiRunCurrentEntry` / `AiRunCurrent`（按后端契约八字段）【实测】。

**`frontend/src/api/ai.ts`**
- `runsCurrent(sessionId)`：`GET /ai/runs/current?sessionId=`，并入 aiApi（:108-117、:132 附近）【实测】。

**`frontend/src/stores/ai.ts`**（T2b-D#3 核心）
- `loadHistory` 两个成功分支（空历史 :407-410、正常映射 :436-438）置 `historyLoaded=true` 后 `await this.restoreSuspendedSnapshot()`；catch 分支不调——**historyLoaded 前不触发重建，loadHistory 先行完成消除写后写竞态**【实测】。
- 新 action `restoreSuspendedSnapshot()`（:443-537 附近）：loading 守卫 → `runsCurrent`（catch 静默降级为既有镜像/历史形态）→ `!found || !awaitingExternal` 早退（已决条目不重建挂起卡；空态回退既有历史渲染 + 空历史 expired 降级）→ 非 PENDING 条目按 `frameStatusToPendingStatus` + 残留判定调 `applyFrontendToolTerminal` 收敛（不留 pending 残留；TIMEOUT→expired / FRONTEND_RESULT→succeeded 等）→ PENDING 条目逐条过 `hasDecidedPendingResidue` 守卫（**勘误 4 同口径**）后三级宿主优先就地重建（工具卡消息 → 最后一条 assistant → 唯一例外 `appendAssistantMessage('')` 空宿主并 streaming=false/done=true，注释说明理由）→ 写唯一 pendingCall 投影 + `upsertToolRun`（status pending，唯一 upsert 不新增重复工具卡）→ generative_form 且 activeForm 空时防御性 `parseFormSchema`，合法才武装 `activeForm`（`deadline=null` 不武装本地计时器，后端权威超时收敛）→ 置 suspended/activeRunId → 触发续收【实测】。
- 新 action `resumeSnapshotStream(runId, lastSeq, hostMessageId)`（:540-583 附近）：abort 防双连接、`runSeq` 锚定 lastSeq、`reattachRun` 带 `lastSeq=archiveMaxSeq` **只收新帧（禁全量回放）**，onFrame 直写宿主消息；finally 只清 loading/currentAbort、**不**置 suspended=false（卡状态由投影/帧决定）【实测】。
- suspended 帧 case 补 residue 守卫（:1200-1206 附近，顺手项③/GLM F4）：`waiting.name === GENERATIVE_FORM_TOOL && hasDecidedPendingResidue` → break，位于写 message.pendingCall 之前，与 frontend_tool_request case 的 S3 守卫同款【实测】。
- 三处 `'generative_form'` 判定字面量换 `GENERATIVE_FORM_TOOL`（:1142、:1150、:1307 附近，顺手项⑤）【实测】。

**`frontend/src/components/AiPanel/AiPanel.vue`**
- 通用 FRONTEND 分支补 approved 显式分支（:245-248 附近）：`<el-tag type="success">已放行</el-tag>`，不再落 else"已执行"（顺手项②/GLM F1，注释说明当前无 FRONTEND+approved 写入路径、属口径修正）【实测】。
- 模板 generative_form 判定换常量（:202）；TOOL_LABELS 键换 `[GENERATIVE_FORM_TOOL]`（:343）；import 常量（:313）【实测】。
- data-testid（顺手项④前置）：挂起卡根 div `:data-testid="'pending-card-' + toolCallId"`（:169）；表单已取消 tag `data-testid="tag-form-cancelled"`（:218）【实测】。

**`frontend/src/components/AiPanel/FormRenderer.vue`**
- 取消/提交按钮 `data-testid="gf-cancel"` / `gf-submit"`（:97-98）【实测】。

### 1.3 冒烟脚本

**`scripts/m2t2a-smoke.mjs`**（230 → 396 行）
- 头部断言①说明改写（:14-26）：占位回归守卫 → 真实挂起轮四步场景，标注 GLM F5/勘误 5 关闭；新增 `SMOKE_API` 环境变量（默认 `http://localhost:18318`）【实测】。
- 顺手项④（:43-45、:157-169、:225）：删除全部 `TEXT_*` 文案常量；计数/点击全改 data-testid 定位（`gf-submit`/`gf-cancel`/`pending-card-<toolCallId>`/`tag-form-cancelled`）【实测】。
- 断言①升级（阶段 3，:237-384）：
  - `PROMPT_GF1` = verify-e2e.ps1:1478 已验证提示词原文；POST /chat 带 `context:{page:'tasks'}`（与 ps1 `Invoke-FormTurn` 口径一致）。
  - `readSse()`（:183-204）：getReader+TextDecoder 收帧，等到 `frontend_tool_request(generative_form)` 后**连接保持不关**（挂起轮活着）；240s 超时 → 断言① FAIL 并记已收帧序列。
  - 刷新重建：注入 `ai-session-id` + 清该 session 镜像键 → 重载 → 快照重建。
  - 断言①：`pendingCards===1 && formRendererCount===1（FormRenderer 真实重建，与旧守卫"无 FormRenderer"相反——升级点）&& toolRunCount===1（无重复工具卡）`。
  - 收敛退出：按 activeForm.form 实际 schema 合成值 → `submitGenerativeForm`；结局帧比 POST 回执先清空 activeForm，故以 `submitted || activeForm===null` 判提交路径收敛，仅"400 被拒回 filling"才降级 cancel（gf-cancel 等价路径）；Node 侧原连接续读 `frontend_tool_result`+`done` 后关闭【实测】。
- 旧镜像路径阶段 3 整段移除；注入式阶段 1/2（断言②③）保留；result-<UTC>.json 分轮保留与退出码语义不变【实测】。

---

## 2. 新增单测清单（14 个，基线 249 → 263）

**`backend/src/test/java/com/example/configmgr/ai/run/RunStoreSessionCurrentTest.java`**（7，Mockito mock StringRedisTemplate）
1. `suspendedSaveWritesIndexWithSessionLockTtl`：save SUSPENDED 后 `sessionCurrentRunId` 得 runId，verify SET 带锁 TTL（10m，非 session-ttl）。
2. `terminalSaveClearsIndex`：DONE/FAILED/CANCELLED 三终态各自清除。
3. `runningSaveKeepsIndex`：SUSPENDED→RUNNING 索引保留（settle 场景）。
4. `renewOnlyExtendsMatchingRunId`：值不匹配不 EXPIRE。
5. `clearOnlyDeletesMatchingRunId`：值不匹配不 DEL。
6. `redisFailureDegradesInsteadOfThrowing`：Redis 异常四方法全不抛、读返回 null。
7. `sameToolCallIdPutPendingTwiceKeepsSingleEntry`：同 toolCallId 两次 putPending 长度仍为 1（红队 §2 排查方法第 1 条固化）。

**`backend/src/test/java/com/example/configmgr/ai/web/AiControllerRunsCurrentTest.java`**（7，standalone MockMvc + mock RunStore，沿用 AiControllerFrontendResultTest 惯例）
1. `missingSessionIdIsBadRequest`：缺参/空白 → 400。
2. `missingIndexReturnsExplicitEmptyState`：索引缺失 → found:false 空态七字段。
3. `staleIndexWithMissingSnapshotReturnsEmptyState`：索引残留但快照消失 → found:false。
4. `suspendedWithPendingFrontendEntryIsAwaitingExternal`：SUSPENDED+PENDING(FRONTEND) → entryStatus=PENDING、awaitingExternal=true、archiveMaxSeq=42（stub lastEventSeq）。
5. `timedOutEntryIsReportedAsTimeout`：条目 TIMEOUT → entryStatus=TIMEOUT、awaitingExternal=false（分支一）。
6. `decidedEntryDisambiguatesSuspendedSnapshot`：快照 SUSPENDED 但条目 FRONTEND_RESULT → awaitingExternal=false（**D1 消歧**，分支二）。
7. `backendPendingEntryDoesNotCountAsUnresolvedExternal`：BACKEND PENDING 不计入 unresolvedExternal（口径对齐 ResumeService）。

---

## 3. 验证原始输出

### 3.1 后端 mvn test【实测】

```
$ cd backend && "<MAVEN_HOME>/bin/mvn.cmd" test
Tests run: 263, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
Total time: 48.924 s
```
（基线 249 先跑确认全绿，施工后 263 全绿；全量日志 `backend/target/t2b-mvn-test.log`）

### 3.2 前端 typecheck / build【实测】

```
$ cd frontend && yarn typecheck   → Done in 4.5s   EXIT=0（vue-tsc --noEmit 无诊断）
$ cd frontend && yarn build       → ✓ built in 26.9s  EXIT=0
```

### 3.3 环境（启动前 netstat 确认 18330/18331/18332/5202/9333 全空闲；6379 Memurai 已 LISTENING）【实测】

- 后端：`<MAVEN_HOME>/bin/mvn.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=18330"`，env 注入 `AI_API_KEY=***` / `AI_BASE_URL=https://api.deepseek.com` / `AI_MODEL=deepseek-flash`（key 全程未落盘）；约 10s 就绪，`/api/ai/health` = AI_AVAILABLE / deepseek-flash / Redis 在线。
- 前端：`VITE_API_BASE=http://localhost:18330 yarn dev`（5202，`.env.local` 未改）。
- 验证 scratch：`<SCRATCH>/m2t2b-verify/`（backend.log / frontend.log / smoke1 / smoke2）。

### 3.4 CDP 冒烟两轮（真实 DeepSeek 轮，可重复性验证）【实测】

| 轮次 | 退出码 | ② 唯一实例 | ③ 收敛 / ③b | ① 真实挂起轮刷新 |
|---|---|---|---|---|
| smoke1（修复后重跑） | 0 | PASS | PASS / PASS | PASS |
| smoke2 | 0 | PASS | PASS / PASS | PASS |

smoke2 断言明细（`smoke2/result-*.json` 实读）：
- ② `{formRendererCount:1, cancelButtons:1}`；③ `{pendingStatuses:["cancelled","cancelled"]}`；③b `{formRendererCount:0, cancelledTags:2}`。
- ① `{pendingCards:1, formRendererCount:1, toolRunCount:1, pendingStatuses:["pending"], convergePath:"submit", frontendToolResult:true, done:true}`。
- realRound 现场：sessionId `m2t2b-…`，runId `c8b57c38-…`，awaitSeenTypes `["start","suspended","tool_start","frontend_tool_request"]`；提交值 `{keyword:"smoke", minRows:1, effectiveDate:"2026-10-08", scope:"XN"}`（与模型实发 schema 四字段一致）；Node 侧原连接收到 `frontend_tool_result` 与 `done` 终帧。
- 首跑（smoke1 首次）曾因脚本自身缺陷在阶段 3d 误判（提交后固定 sleep 读状态，结局帧已先清空 activeForm 致 fallback 点不到 gf-cancel）——**脚本问题非业务代码问题**，修复为"轮询 submitting → submitted||activeForm===null 即收敛，仅 filling 被拒才取消降级"后 smoke1 重跑与 smoke2 连续 PASS；后端会话复查 `runs/current` = found:false，无僵尸轮次。

### 3.5 收尾【实测】

后端/前端 dev/脚本自启 Chrome 全部停止；netstat 复查 18330/5202/9333 无 LISTENING（仅 TIME_WAIT 残余）；Memurai（系统服务）未动；用户浏览器未触碰。

---

## 4. 冒烟断言明细（升级后口径）

| 断言 | 语义 | 通过判据 |
|---|---|---|
| ① | **真实后端挂起轮**（Node 发真实 /chat，模型实发 generative_form，挂起保持）→ 注入页签 sessionId → 整页刷新 → 快照重建 | 挂起卡恰 1 张（`pending-card-<toolCallId>`）＋ FormRenderer 真实重建（gf-submit 恰 1）＋ 工具卡恰 1（无重复）；随后表单提交收敛、原连接收 frontend_tool_result+done |
| ② | 同 toolCallId 双消息注入（GFd 双卡形态）→ 唯一实例 | gf-submit 恰 1、gf-cancel 恰 1 |
| ③ / ③b | 取消后消息级收敛 | 两条消息 pendingCall.status 均 cancelled；FormRenderer 消失；tag-form-cancelled 恰 2 |

① 与 T2a 阶段差异（勘误 5 关闭点）：T2a 断言①只验镜像重建路径单卡形态且"无 FormRenderer"；T2b 升级为后端历史/快照场景，**要求 FormRenderer 真实重建**（快照 arguments 内嵌 schema 驱动），判别力从"计数"升为"续填语义"。

---

## 5. 设计依据对账

| 规格 | 落点 |
|---|---|
| T2b-D#1 索引：新键、挂起写/终态清、TTL 对齐锁 watchdog、O(1) | RunStore 方法组 + save() 联动 + ConfirmGate 心跳续期；否决支（复用锁值/全量扫）未采用 |
| T2b-D#2 端点：结构化返回、unresolvedExternal 口径对齐、三分支、B2 约束注释 | AiController `/runs/current` + javadoc；三分支各一至二条单测覆盖 |
| 勘误 2 archiveMaxSeq | 端点字段 + 单测 stub lastEventSeq=42 断言 + 冒烟 lastSeq 增量续收实跑 |
| 勘误 3 pendingEntries 全字段 | entryStatus/kind/name/arguments 五字段；冒烟以 arguments 内嵌 schema 真实重建 FormRenderer 验证宽度足够（**推翻条件未触发**：快照字段足以重建挂起卡） |
| 勘误 4 重建守卫同口径 | restoreSuspendedSnapshot 用 hasDecidedPendingResidue（isFrameDecidedPendingStatus 口径，含乐观 cancelled） |
| 勘误 5 断言①升级 | §4 冒烟断言①真实后端场景，两轮 PASS |
| 顺手项 ①~⑤ | ① §1.3/§4；② AiPanel approved 分支；③ suspended 帧守卫；④ data-testid 全量替换文案定位；⑤ 前端 GENERATIVE_FORM_TOOL 常量 + 后端 TOOL_NAME 同步注释 |

---

## 6. 偏差与【待裁决】

无【待裁决】。两项偏差登记（均不阻断）：

1. **AI key 注入方式偏差**：施工令给定 `export AI_API_KEY="sk-…"`，实施时 shell 环境无该变量，改从用户既有 `ai-example-deepseek/backend/.env.local` 提取 key 注入 shell env（全程未打印未落盘）。验证有效性不受影响（真实 DeepSeek 轮、真实挂起）。若该取法不合规，可手工注入后按 §3.3 环境随时重验（脚本与环境均已就绪）。
2. **多 PENDING 条目宿主槽位**：快照路径按规格字面执行——多条 PENDING 条目共用宿主时 pendingCall 单槽位后写覆盖先写。后端一轮挂起通常单条目（本棒两轮真实轮均单 frontend_tool_request），实际影响小；如需"多卡并挂"语义属新裁决项，留痕待后续。

另登记一处口径备注（非偏差）：端点 400 分支走裸 Map、成功分支套 ApiResponse.ok——与 /runs 系列契约及前端拦截器拆信封行为自洽（`isApiResponse` 按 `success` 布尔判定），前后端零契约漂移。

---

## 7. 留痕

- 验证产物：`<SCRATCH>/m2t2b-verify/`（backend.log、frontend.log、smoke1 两轮 result-*.json、smoke2 result-*.json、chrome-profile×2）；mvn 全量日志 `backend/target/t2b-mvn-test.log`。
- 改动面：`git status` 13 文件（backend 4M + 2 新测试、frontend 5M、scripts 1M），HEAD 保持 `2354721`，未提交。
- 本文档保持未提交。

本文档为 M2-T2b 闭环证据；T2 包（T2a+T2b）至此全部落地。

---

## 修订（闭环批，2026-10-09）

> 本节为**追加修订**：上文 §0~§7 保持施工轮原貌（含已过时数字），不改写历史正文。来源：红队审查（`docs/evidence/M2-T2b-施工红队-DS-V4-Pro.md`）、独立复现（`docs/evidence/M2-T2b-复现验证-GLM.md`）与指挥官裁决。

### 修订 1：§7 改动面数字勘误

原文 §7 记「`git status` 13 文件（backend 4M + 2 新测试、frontend 5M、scripts 1M）」——**文件总数 13 正确，后端分摊口径漏计 1 个**。实测（`git status --porcelain` 逐条核对）正确拆分如下：

| 面 | 计数 | 明细 |
|---|---|---|
| backend 修改 | 5M | `GenerativeFormRules.java`、`ConfirmGate.java`、`RunStore.java`、`AiController.java`、`InMemoryRunStore.java`（测试支持类，位于后端测试树内，原文未计入） |
| backend 新增 | 2 | `RunStoreSessionCurrentTest.java`、`AiControllerRunsCurrentTest.java` |
| frontend 修改 | 5M | `api/ai.ts`、`components/AiPanel/AiPanel.vue`、`components/AiPanel/FormRenderer.vue`、`stores/ai.ts`、`types/ai.ts` |
| scripts 修改 | 1M | `m2t2a-smoke.mjs` |
| 合计 | 13 | 总数与原文一致，仅后端拆分数订正为 5M + 2 新测试类 |

### 修订 2：快照路径断流收尾口径（指挥官裁决留痕）

`resumeSnapshotStream`（`frontend/src/stores/ai.ts:558-563`）的 `onClose` 仅在非终态时置 `streamInterrupted` + `notice`：**不清 `activeForm`、不撤表单到期定时器**；与 `reattachActive`（`frontend/src/stores/ai.ts:719-731` 同型分支会 `disarmFormExpiry()` + `activeForm = null`）口径不同 —— **系有意设计**：快照路径的挂起态由后端权威管理（会话锁 / 索引 TTL 都在后端），重建表单时不武装本地计时器（`deadline: null`，见 `frontend/src/stores/ai.ts:513-524`：快照无 `expiresAt` 折算依据），前端不留本地假超时；断流后用户可刷新重试，后端以 409（他端已决）/ 结局帧收敛。

**残余风险（已登记，转 T3 韧性机制包）**：进程死亡窗口（0 ~ 索引 TTL 10 分钟）内，"快照重建出来的挂起卡"**没有零帧收敛兜底** —— 后端已无活 gate/emitter 产帧，前端只能保留挂起卡（不可服务死卡），靠索引 TTL（10 分钟）过期后刷新自愈（红队 S2-1）。

### 修订 3：范围外增益留痕（HITL 确认卡的快照重建）

`GET /api/ai/runs/current` 的 `unresolvedExternal` 口径与 `ResumeService`（`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:151-155`）**逐字一致**：`status == PENDING` 且 `kind != BACKEND`。`PendingToolCall` 的 kind 取值为 `CONFIRM / FRONTEND / BACKEND`，**CONFIRM 类不在排除集内** ⇒ HITL 确认门挂起时该端点同样返回 `awaitingExternal=true`，前端 `restoreSuspendedSnapshot`（`frontend/src/stores/ai.ts:478-530`，`kind = entry.kind === 'CONFIRM' ? 'CONFIRM' : 'FRONTEND'`）会照常重建确认卡并 upsert 唯一投影 —— 即"刷新后确认卡被快照重建"是本次快照重建通道的**范围外收益**（T2b-D#3 原始论证只覆盖 `generative_form` 表单卡）。

- 证据级别：**仅静态证据**（读码口径对齐）。本次施工与两轮冒烟只覆盖 `FRONTEND` + `generative_form` 路径，CONFIRM 挂起 → 刷新 → 确认卡重建 → 决策收敛的运行验证转 **T3**。
