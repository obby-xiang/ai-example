> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 复核报告 · 对话面板包（苞 A · R-100 / 苞 B · R-101）

- **复核对象**：`<REPO>`（分支 `main`，HEAD=`03a6cbe`）工作树全部未提交改动
- **复核方式**：独立复现（后端全量 / 前端 typecheck+build / 文档门禁 / E2E 29 用例 / CDP 冒烟 13 断言 / 活体端点探针）+ 对抗反查（实读源码 + 活体构造威胁场景），**不接受施工报告口径**
- **复核纪律**：对 `<REPO>` **严格只读**——零文件编辑、零 `git add/commit/push`；唯一写入是构建产物（`target/`、`frontend/dist/`，均已有 `.gitignore` 豁免）与仓库外的证据目录
- **脱敏**：本报告正文一律 `<>` 占位符，不含实盘路径、盘符、用户名、内网 IP、仓库名

---

## 0. 复核结论

**总体结论：带条件 PASS**

苞 A（R-100）与苞 B（R-101）的**代码交付物全部验证面独立复现为绿**（后端 397/0/0/0、前端 typecheck+build 绿、文档门禁 6/6、E2E 29/0、CDP 冒烟 12 PASS + ⑫ SKIP），语义对抗抽查**零 FAIL**，活体端点行为与设计契约逐条相符（含裸 Map 9 键、幂等 200、删除范围只触及 `chat:mem:*`、SessionGate 只报告不 409）。

带条件的两条：

| 条件 | 内容 | 归属 |
|---|---|---|
| **C1** | 复核期间工作树文件集合由 **6 → 7**：`docs/M2-排期计划.md` 于 `23:30:26` 被追加 15 行。两份施工报告均声明「未动台账、不代写排期」⇒ 该改动**不是苞 A/苞 B 所为**，须由指挥官确认归属与提交范围 | 流程/归属（见 F1） |
| **C2** | `convergeMemory` 复活竞态在**在飞路径下实测为确定性**（2/2 在 ≤500ms 内复活），而 store 注释与施工卡用「毫秒~秒级竞态」「把复活窗口压到最小」表述（偏轻）。「如实暴露」本身 PASS（端点 3 字段 + WARN 日志 + 施工卡 §5.2 已登记），仅措辞需订正或明确接受 | 工程口径（见 F2） |

**未发现 BLOCKER / 功能性缺陷。** 发现清单见 §5（0 BLOCKER / 1 MAJOR / 5 MINOR / 4 观察）。

---

## 1. 复核范围与对象

### 1.1 复核起始时的文件集合（与施工报告自述一致）

```
 M backend/src/main/java/com/example/configmgr/ai/web/AiController.java
 M frontend/src/api/ai.ts
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
?? backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java
?? scripts/chat-panel-smoke.mjs
```
= **4 改 + 2 新增 = 6 个**，与两份施工报告 §1 的文件清单逐字吻合，无夹带。

### 1.2 复核期间文件集合发生变化（**重要，见 F1**）

复核收尾时（`23:34`）再次执行 `git status --porcelain`，多出一个：

```
 M docs/M2-排期计划.md        ← 新增，mtime 2026-10-10 23:30:26（复核进行中）
```

该文件为**只追加 15 行**的台账登记（「AI 对话正文 Markdown / 图表渲染 暂缓登记」），内容引用的是苞 B 落盘后的行号（`AiPanel.vue:168`/`:628`/`:629`），属**旁观者视角的排期登记**，与苞 A/苞 B 的代码交付物无关。已独立复核该 hunk：

- 命名纪律（NFKC + 隐形字符剥离 + 零分隔连写三态正则）：**命中 0**；
- 盘符 / 内网 IP / 用户名：**命中 0**（唯一正则命中是 `https://` 的 `s:/`，假阳性）；
- `check-docs.mjs` 在**该 hunk 落盘后**复跑：**6/6 PASS**，退出码 0；
- 语义：文档类改动，零代码、零契约影响。

⇒ **不做对错判定（非本次复核对象）**，但必须由指挥官明确「这 15 行随哪个提交入库」，否则「6 改动 + 2 新增」的验收口径已失效。

### 1.3 未夹带项（逐项扫描，全部 0 命中）

对**全部 7 个文件**（含 1.2 的台账 hunk）扫描：密钥（`sk-*` / `api_key=` / `password=` / `secret=`）、盘符绝对路径、用户名/家目录、内网 IP、命名纪律禁则（`main` + 分隔符族/隐形字符/零分隔 + `v2`，三态）：**全部 0 命中**。

---

## 2. 各验证面的独立复现数字

全部命令在 `<REPO>` 内执行；包装脚本 `<SCRATCH>/.run-fix/mvn-run.sh`（把 `"$@"` 透传给 Maven 全路径）。

| # | 验证面 | 命令 | 实测结果 | 退出码 |
|---|---|---|---|---|
| 1 | 后端全量 | `bash .run-fix/mvn-run.sh -B clean test` | **`Tests run: 397, Failures: 0, Errors: 0, Skipped: 0`**，`BUILD SUCCESS`；surefire XML 求和 **61 个 XML / 397 / 0 / 0 / 0** | 0 |
| 1b | 其中新增类 | 同上 | `AiControllerHistoryDeleteTest` **8/8**（新增 XML 文件 `TEST-...AiControllerHistoryDeleteTest.xml` 存在） | — |
| 1c | flaky 类（R-105） | 同上 | `SseChatEmitterWriteBudgetTest` **6/6 绿**（本批未偶发，无需单类重跑） | — |
| 2 | 前端 typecheck | `npm run typecheck`（= `vue-tsc --noEmit`） | 0 error | 0 |
| 3 | 前端 build | `node scripts/build.mjs` | `✓ built in 1m 22s`，dist 分块正常（element / spreadjs / vendor） | 0 |
| 4 | 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 PASS**（① 无盘符 ② 版本一致 ③ 引用存在 ④ 端口一致 ⑤ 命名纪律 `git ls-files` 920 路径/实扫 917 ⑥ 探针不参与构建）；**复跑第二次（含 1.2 新 hunk）仍 6/6** | 0 |
| 5 | 桩上游自检 | `node scripts/ci/stub-upstream.mjs --self-check` | SELF-CHECK PASS（零依赖 / 路由契约 / 白名单逐值对账 / 收敛自证 / 披露驱动 / 帧形态 / 反解，10 项全绿） | 0 |
| 6 | E2E 29 用例（桩模式） | `E2E_STUB_MODE=1 powershell -File scripts/verify-e2e.ps1 -Base :18330 -BackendLog … -ArtifactDir … -EnableGf6` | **`PASS=29 FAIL=0`**、`GFSKIP=0`、`Q8 项：PASS=2 FAIL=0` | 0 |
| 7 | CDP 冒烟 13 断言 | `node scripts/chat-panel-smoke.mjs`（**不设** `SMOKE_KILL_BACKEND`） | **12 PASS + ⑫ SKIP**（SKIP 原因逐字落盘：`SMOKE_KILL_BACKEND 未置 1`），无 FAIL | 0 |
| 8 | 活体端点探针 | `curl` + `<MEMURAI_HOME>/memurai-cli.exe`（见 §3.2/§4） | 见下（裸 Map 9 键、幂等、范围、runActive、竞态 2/2） | — |

### 2.1 关于「397 / 61 只增不减」的口径说明（如实登记）

- 我在 `clean` **之前**读到 `target/surefire-reports/` 已是 **61 XML / 397 / 0 / 0 / 0**（该 target 由当前工作树构建，**已含**新测试类）；`clean test` 之后重新求和**完全一致**（61/397/0/0/0）。
- 施工报告称「开工基线 389 / 60 XML，差值 +8 / +1」。**该基线我无法独立重导**——须 `git stash` 才能构造，与「严格只读」纪律冲突。可核验的是**算术自洽性**：397 − 8 = 389，61 − 1 = 60，且新类恰好贡献 8 个用例 / 1 个 XML ⇒ 报告口径可信，但「+8/+1」属**未独立复现的口径**。
- `target/surefire-reports/` 内确有 1 个 `.dumpstream`（`2026-10-10T23-19-24_899.dumpstream`），与苞 A §6.2 的说明一致（surefire 的 Manifest-JAR 绝对路径告警，非用例结果）。

---

## 3. 语义对抗抽查（实读 + 活体实测，逐项 PASS/FAIL）

### 3.1 后端新端点 `DELETE /api/ai/history/{sessionId}`

| # | 待验命题 | 证据（文件:行 / 活体实测） | 判定 |
|---|---|---|---|
| A1 | **裸 Map（不套统一响应壳）** | 返回类型 `ResponseEntity<Map<String,Object>>`（`AiController.java:561`），经 `json()`（`:501`）或 `ResponseEntity.ok(body)`（`:590`）直出。**活体**：`curl -i -X DELETE …/history/recheck-del-1` → `Content-Type: application/json`，体为 `{"sessionId":…,"deleted":true,"existed":true,"memoryKey":"chat:mem:recheck-del-1","removedFromIndex":true,"runActive":false,"activeRunId":null,"storedBy":"…","note":"…"}` = **9 键，无 `success`/`data` 信封**；同会话 `GET` 仍为 `{"success":true,"data":{…}}` ⇒ 与「最近的幂等先例 `/cancel` 同族」成立 | **PASS** |
| A2 | **删除范围只触及 `chat:mem:*`** | `chatMemory.clear()`（`:576`）→ 官方 `MessageWindowChatMemory`（`AiModelConfig.java:83-88`）→ `RedisChatMemoryRepository.deleteByConversationId` = `DEL chat:mem:<id>` + `SREM chat:mem:__ids__`（`RedisChatMemoryRepository.java:135-138`）。**活体**：删前 `llen=2`、`sismember=1`、`exists ai:session:recheck-del-1`=1；删后 `exists chat:mem:…`=**0**、`sismember`=**0**，而 `--scan *recheck-del-1*` 仅剩 **`ai:session:recheck-del-1`** ⇒ 记忆面已删、`ai:session:` 存活、`ai:run:*` 未被触及 | **PASS** |
| A3 | **幂等（不存在也 200，不 404）** | 活体：从未存在的 `recheck-del-never` → **200** `{"deleted":true,"existed":false,…}`；对已删会话重复删 → **200** `{"deleted":true,"existed":false}` | **PASS** |
| A4 | **Redis 不可用 → 503 `AI_REDIS_UNAVAILABLE`** | 前置检查 `:565-567` 在**任何 Redis 操作之前**；503 体 = `redisUnavailable()`（`:668-674`，`code` = `RedisAvailability.code()` = `"AI_REDIS_UNAVAILABLE"`，`RedisAvailability.java:119-121`）。单测 `redisUnavailableFailsFastWithoutTouchingMemory` 另以 `verify(never()).clear` 钉死「不得触碰记忆」 | **PASS**（单测；真停机未实测，见 §6） |
| A5 | **不查 AI key** | 新方法体内 `availability` **0 次引用**（与 `/history` 同类）；单测 `noAiKeyStillDeletes` / `aiKeyPresentAlsoDeletes` 两形态结果一致 | **PASS** |
| A6 | **SessionGate 只报告、不硬拒、不返回 409** | 仅**只读一次** `sessionGate.currentRunId(sessionId)`（`:569`）——`SessionGate.currentRunId` 只读进程内 map，回退读 Redis 且**异常吞掉返回 null**，**无 `acquire`/`release`/持锁**（`SessionGate.java:126-137`）；`activeRunId != null` 时只 `log.warn`（`:572-575`）。**活体**：在飞挂起轮（`runs/current` = `SUSPENDED`/`awaitingExternal:true`）时 DELETE → **HTTP 200**，`runActive:true`、`activeRunId` = 该轮 runId 逐字相符；重复 2 个会话 2/2 一致 | **PASS** |
| A7 | **`existed` 判据的 null 防御** | `:570-571` `existing != null && !existing.isEmpty()`（读侧 `:508` 无 null 判）⇒ 第三方 `ChatMemory` 返回 null 不会 500 | **PASS**（加分项） |

### 3.2 `resetSession` 重写（`frontend/src/stores/ai.ts:1099-1151`）

| # | 待验命题 | 证据 | 判定 |
|---|---|---|---|
| B1 | **必修缺陷：`this.sessionId` 双写已修** | `const nextId = createSessionId()`（`:1116`，**同一个变量**）→ `sessionStorage.setItem(SESSION_ID_KEY, nextId)`（`:1119`）→ `this.sessionId = nextId`（`:1123`）⇒ 双写同值。**行为级反证是 E2E 之外最硬的一条**：CDP 冒烟 ④ 实测「新会话再发一轮 → `newSessionHistoryCount=2` 且 `oldSessionHistoryCount=0`」——若仍带旧 id，记忆会落在旧 id 上，④ 必红 | **PASS** |
| B2 | **cancel → delete 顺序** | `:1137-1143` `await aiApi.cancel(oldRunId)`（best-effort，仅 `if (oldRunId)`）**先于** `:1145-1150` `await aiApi.deleteHistory(oldSessionId)`；`oldRunId`/`oldSessionId` 都在清状态**之前**取（`:1101-1102`） | **PASS** |
| B3 | **delete 失败的 notice 不被抹** | `this.notice = null` 在 `:1126`；失败提示写在 `:1149`（函数**最后**）⇒ 不会被后续 `notice=null` 抹掉（这正是苞 A §7-D1 相对施工卡表格顺序的修正动机，实读确认已落地） | **PASS** |
| B4 | **同步清屏语义未退化** | 首个 `await` 之前（`:1116-1135`）已完成换 id / 清 `messages` / `loading=false` / `historyLoaded=true` / `stopped=false` ⇒ 非 await 调用方（既有 `scripts/m2t2a-smoke.mjs:475`）观察到的同步行为不变 | **PASS** |

### 3.3 逐条复制（`AiPanel.vue:600-653`）

| # | 待验命题 | 证据 | 判定 |
|---|---|---|---|
| C1 | **`execCommand` 回退真实存在** | `legacyCopy()`（`:600-613`）：离屏只读 `textarea`（`position:fixed/top:-1000px/opacity:0`）→ `select()` + `setSelectionRange` → `document.execCommand('copy')`（`:611`）→ `finally` 移除。主路径 `navigator.clipboard?.writeText` 被拒时落到 `legacyCopy`（`:640`），API 缺失时也落（`:643`）。CDP 冒烟 ⑨（强制 `clipboard=undefined`）/ ⑩（`writeText` reject）实测均走 `execCommand`，回退文本与 `m.content` **逐字符相等** | **PASS** |
| C2 | **`copyState` 是局部态、不占 `notice` 单槽位** | `copyState = ref<{id,ok}\|null>`（`:379`）为**组件局部** ref，2s 自清（`:645-651`），`onBeforeUnmount` 清 timer（`:742-745`）。渲染点用 `data-testid="copy-hint-{id}"`（`:175`）而非 `ai.notice`。**活体**：冒烟 ⑧⑨⑩ 三条路径实测 `notice === null` 恒成立 | **PASS** |
| C3 | **内容口径 = `m.content` 原样（不 trim、不含思考链/工具卡）** | `const text = m.content`（`:630`），无 `trim()`；冒烟 ⑧ 对 `writeText` 拦截得到的字符串做**逐字符相等**断言，实测 `exactMatch:true` | **PASS** |
| C4 | **结构双向正确**（有正文才有按钮） | 冒烟 ⑥ 双向对账：`missingButtons:[]`、`leakedButtons:[]`；默认 `opacity:0`；有消息时骨架屏不出现 | **PASS** |

### 3.4 三处 `onClose` 的 `stopped` 抑制（无第四处遗漏）

| # | 待验命题 | 证据 | 判定 |
|---|---|---|---|
| D1 | **全仓 `onClose` 调用点穷举** | `grep -rn "onClose" frontend/src scripts`：**handler 定义仅 3 处**（`stores/ai.ts:609`、`:676`、`:823`）；`sse.ts:66` 是类型声明、`sse.ts:232` 是**唯一**触发点（`finish()` 内 `handlers.onClose?.(info)`，全仓只此一个出口）。⇒ `finish` → 3 个 handler 是**全量路径**，无第四处 | **PASS** |
| D2 | **三处都带 `&& !this.stopped`** | `:614`（`resumeSnapshotStream`）、`:682`（`send`）、`:828`（`reattachActive`）三处条件逐字为 `if (!info.terminal && !this.stopped)` | **PASS** |
| D3 | **`stopped` 生命周期封闭** | 全仓 `this.stopped` 写入点仅 4 处：声明 `:272`（false）、`stop()` `:1059`（true）、`send()` `:657`（false）、`resetSession()` `:1133`（false）⇒ 语义注释（`:266-271`：「只由 `stop()` 置位、只由 `send()` 复位」）与实际一致（`resetSession` 的复位是苞 A 新增，注释未提及该处，属极小措辞缺口） | **PASS** |
| D4 | **不削弱真断流路径** | 真断流时 `stopped===false` ⇒ 条件等价于原 `!info.terminal`，判定不变。CDP 冒烟 ⑪（主动停止：`streamInterrupted=false`、无红条、`notice='已请求取消本轮对话'`）实测通过 | **PASS**（⑫ 真断流对照按纪律 SKIP，见 §6） |

### 3.5 骨架屏 / 空态互斥（`AiPanel.vue:117-137`）

| # | 待验命题 | 证据 | 判定 |
|---|---|---|---|
| E1 | **`v-if` / `v-else-if` 链正确** | `:121` `v-if="!ai.messages.length && !ai.historyLoaded"` → 骨架屏；`:127` `v-else-if="!ai.messages.length"` → 空态；再往下 `v-for="m in ai.messages"` ⇒ 三态互斥（有消息则两者都不渲染） | **PASS** |
| E2 | **`historyLoaded` 不会永久 false** | `loadHistory` 三条出口**全部**置 true：后端不可达 catch `:442`、历史为空 `:452`、有历史 `:481` ⇒ 骨架屏最长存活 = 一次请求超时，不会卡死 | **PASS** |
| E3 | **`resetSession` 补位** | `:1135` `this.historyLoaded = true`（新会话无可对账内容） | **PASS** |
| E4 | **活体三态切换** | 冒烟 ⑬：`Fetch` 域挂住 `*api/ai/history*` 时 → `skeleton:true` / `historyLoaded:false` / `messages:0` / **`emptyCard:false`（空态不同框）**；放行请求后 → `skeleton:false` / `emptyCard:true` | **PASS** |

---

## 4. 对抗反查

### 4.1 鉴权 / 会话归属缺口 —— **MINOR（存量平台问题 + 本次新增破坏性动词）**

**事实（实读 + 实测）**

- 全仓**无鉴权**：`backend/pom.xml` 无 `spring-security`；`grep -l "OncePerRequestFilter|SecurityFilterChain|WebSecurityConfigurer" backend/src/main/java` = **0**；`config/WebConfig.java` 仅做 Web 配置。类注已明文登记 DC-10「本期无认证鉴权，与全仓 AI 端点一致」（`AiController.java:542-544`）。
- 新端点**无任何 sessionId 归属校验**：任意 `sessionId` 直接成为 Redis 键（活体：`DELETE /api/ai/history/你好-*?[]` → 200，键名 `chat:mem:你好-*?[]`）。**「任意 sessionId 可删他人记忆」在技术上成立**。

**威胁模型分级**

| 维度 | 评估 |
|---|---|
| 可达性 | 需要**已知**目标 sessionId。`createSessionId()`（`stores/ai.ts:58-66`）优先 `crypto.randomUUID()`（122 bit，不可猜）；**回退**形态 `s-<base36 时间戳>-<8 位 base36 随机>`（约 41 bit，且时间分量可猜）。该回退恰在**非安全上下文**（经内网 IP + http 访问，正是苞 B 项 2 处理的同一场景）下被激活——即**最脆的 id 形态出现在最弱的信任域里** |
| 破坏力 | 仅删该会话的 `chat:mem:*`（活体证：`ai:session:`、`ai:run:*` 不受影响）⇒ 影响是「对话上下文丢失」，非数据/配置破坏 |
| 与存量的关系 | **读侧早已同面暴露**：`GET /api/ai/history/{sessionId}` 无鉴权、且 E2E TC22 实测「未知会话返回空」；更严重的是同一无鉴权面上还有 `POST /api/export/...`、发布、定义删除等业务写端点。⇒ **不能**把「能删他人记忆」判为本次引入的越权缺陷 |
| 本次净增量 | 在**同一命名空间内新增了一个「破坏性」动词**（读 → 毁的语义升级），且幂等返回 200 使得「试探性删除」不产生 404 噪声 |

**结论与建议**：分级 **MINOR**。当前无鉴权口径下可接受，但必须在引入鉴权时**同步补会话归属校验**（否则会留下一个「鉴权后仍可删他人记忆」的隐性洞）。建议登记为待办而不是在无鉴权框架里提前造一个假归属校验。

### 4.2 `resetSession` 竞态（在跑轮次 `convergeMemory` 残留）—— **已如实暴露；措辞偏轻（MINOR）**

**机制（实读）**：`ResilientChatService.convergeMemory`（`:576-593`）在**成功 / 空回复 / 取消**三种终局都执行 `chatMemory.clear(sessionId) + add(sessionId, windowed)`（调用点 `:467` / `:508` / `:530`）⇒ 在飞轮次的终局**必然重写**同名键。

**独立复现（活体，2/2 确定性）**

对两个独立会话重复执行「起挂起轮 → DELETE → POST cancel → 轮询」：

```
rep1  sessionId=recheck-race-1  runId=67ae4ac5-…
      删前 count=1（llen=1）
      DELETE（在飞）→ 200 {deleted:true, existed:true, removedFromIndex:true, runActive:true, activeRunId:67ae4ac5-…}
      redis 紧随：exists chat:mem:recheck-race-1 = 0          ← 删除生效
      POST /cancel/67ae4ac5-… → {cancelled:true, statusBeforeCancel:"SUSPENDED"}
      轮询：**t+500ms count=1** ⇒ 记忆已被该轮收尾的 clear+add 写回（内容 = 快照历史里的 USER 消息）
rep2  sessionId=recheck-race-2  runId=92bb4d0a-…
      DELETE → {runActive:true, activeRunId:92bb4d0a-…, existed:true}
      轮询：**t+500ms count=1** ⇒ 同样复活
```

**判定**：端点的 `runActive` 报告做了它在契约内的全部（活体 `runActive:true` + 正确 `activeRunId` + WARN 日志），施工卡 §5.2 与苞 A §2.4/§7 也已把它登记为**已知代价** ⇒ **「如实暴露」= PASS**。

**偏差（MINOR）**：口径偏轻。`stores/ai.ts:1096` 写「毫秒~秒级竞态」、`:1144` 写「先 cancel 再 delete，把记忆收敛的复活窗口**压到最小**」。实测事实相反：DELETE 在 `cancel` **之后**发起，而取消轮的终局**必然发生在 cancel 之后** ⇒ 在飞路径下「DELETE 先于收盘重写」是**顺序必然而非概率事件**，`cancel→delete` 并不缩小该窗口。「最坏结果 = 旧会话记忆被写回」这一后果描述本身准确（且用户不可见、旧 id 被丢弃、残留受 6h TTL 约束）。

**建议**：把该注释与施工卡措辞改为「在飞路径下旧键**必然**被收盘重写、残留至 TTL」；若要真正收口，只有「等终局后再删」或「终局不重写已删会话」两条路（后者的取舍属更高风险面，不建议在本包内动）。

### 4.3 `removedFromIndex` 字段的时点性 —— **MINOR**

苞 A §7-D2 声明该字段的收益是「『删除被收尾写回覆盖』这一事实可观测」。实测否证该收益：在飞路径下响应报 **`removedFromIndex: true`**（0ms 时刻确实已 DEL + SREM），**500ms 后**同一键被写回。⇒ 字段只能观测「调用时点已处于已删状态」，**观测不到**「随后被覆盖」这一后果；真正观测到覆盖的只有单测里用 mock 构造的形态（`when(findConversationIds()).thenReturn(List.of("s-1"))`，`AiControllerHistoryDeleteTest.java:173`）。

**建议**：改名（如 `removedAtResponseTime`）或在 `runActive=true` 时于 `note` 中加一句「本字段为响应时点事实，不保证随后不被收盘重写」。**同时**：`deleted` 恒 `true`（苞 A §8-3 已自列为待裁决）与 `runActive` 组合已足以表达语义，前端**不读任何字段**（`stores/ai.ts:1146` 丢弃返回值）⇒ 影响限于取证口径。

### 4.4 新端点的额外 Redis 成本 —— **MINOR**

`:583` 每次调用都执行 `chatMemoryRepository.findConversationIds()`，其实现是 `SMEMBERS chat:mem:__ids__` + **对每个存活 id 一次 `hasKey`**（`RedisChatMemoryRepository.java:88-108`）。实测本机 Redis 内 `chat:mem:__ids__` 有 **~100 个**成员 ⇒ 每次「新建对话」约 **+100 次同步 Redis 往返**，仅为算一个前端不读的诊断字段。

苞 A §7-D2 把成本登记为「一次 SMEMBERS」——**低估了 N 倍**（还有 N 次 `EXISTS`）。建议改为 `SISMEMBER chat:mem:__ids__ <sessionId>`（1 次往返，语义等价：该端点关心的是「本会话是否还在索引里」）。不阻断，属可优化项。

### 4.5 复制链路的 XSS / 注入面 —— **无（PASS）**

- `m.content` 只走两条路径：模板文本插值 `{{ m.content }}`（`AiPanel.vue:168` 一带）与 `textarea.value = text`（**属性赋值，非 HTML 解析**）⇒ 均不解析 HTML。
- 新按钮的 `data-testid` 用 `m.id` 拼接（`:175`/`:185`），而 `m.id` **全部由前端生成**：`u-<ts>-<rand>` / `a-<ts>-<rand>`（`stores/ai.ts:1157`/`:1172`）、历史重建用 `history-<index>`（`:470`）⇒ 无服务端可控字符串进入属性位。
- 全仓 `v-html` 仅出现在注释（纪律声明），**零渲染用途** ⇒ 本次改动未打开任何 HTML 注入面。苞 B 把这一点写进 `docs/M2-排期计划.md` 的新 hunk（独立复核内容属实）。

### 4.6 空 / 特殊字符路径参数 —— **PASS（含一处注释口径差异，观察）**

| 输入 | 实测 | 说明 |
|---|---|---|
| `%20`（空白段） | **400** `{"code":"BAD_REQUEST","message":"sessionId 必填"}` | **生产 Tomcat 下可达该守卫**（`StringUtils.hasText` false）。注意 `AiControllerHistoryDeleteTest.java:186-188` 注释称「走 HTTP 到达不了这一分支」——该结论**只在 MockMvc 下成立**（`%20` 被双编码成字面量）；真实容器下可达。守卫本身有效，建议把注释口径补一句以免后人误读 |
| `a%2Fb`（编码斜杠） | **400**（Tomcat HTML 错误页） | 容器层拒绝，非应用层；`createSessionId()` 产出的 id 不含 `/` ⇒ 不可达 |
| `你好-*?[]` / unicode / glob 元字符 | **200**，键名 `chat:mem:你好-*?[]` | Redis 键二进制安全；`contains()` 是精确比较，`*`/`?` **不构成 glob 注入**；`findConversationIds()` 的惰性清理用精确 `hasKey`，无模式匹配 |
| 尾随空段 `/api/ai/history/` | **500** | **非本次引入**：`GET /api/ai/nosuchthing`、`GET /api/ai/history/xyz/extra` 同为 500。`GlobalExceptionHandler.java:52-65` 的 `@ExceptionHandler(Exception.class)` 兜住了 `NoResourceFoundException`（`boot.log` 实测栈）⇒ 全仓「未知路由 → 500 而非 404」的**既有**缺陷。用与本次改动完全无关的路径复现，**不归本次改动** |
| 超长 / NUL 等 | 未单独实测 | 无格式校验（与 `GET /history` 同），仅进 Redis 键空间；`MAX_MEMORY` 键长无限制属存量面 |

### 4.7 其它对抗点（均已排除）

- **`activeRunId` 归属混淆**：`runActive` 由 `sessionGate.currentRunId(sessionId)` 计算，**按会话**判定 ⇒ 他会话在跑不影响本会话删除（活体 rep1/rep2 各判各的）。
- **`deleted` 恒 true 的前端后果**：前端忽略返回体 ⇒ 无「假成功掩盖失败」的用户可见路径；唯一由前端消费的是**抛出**（`catch` → notice）。
- **`copyState` 的 timer 泄漏**：`clearTimeout` + `onBeforeUnmount` 清理（`:645-651`/`:742-745`）⇒ 无悬挂 timer。
- **骨架屏闪现**：苞 B §4.3 自陈「有镜像刷新时同一任务链内会有数毫秒骨架屏闪现」，属可接受且已留痕，未发现夸大。
- **`notice` 竞态（新增引入，见 F5）**：`resetSession` 的两处网络失败提示**无** `isCurrentRound()` 式守卫。

---

## 5. 发现清单

分级口径：BLOCKER（阻断交付）/ MAJOR（需指挥官裁决后才可入库）/ MINOR（可入库但建议修）/ 观察（记录，不要求动作）。

| # | 级别 | 发现 | 证据 | 建议 |
|---|---|---|---|---|
| **F1** | **MAJOR** | 复核期间工作树文件集合 **6 → 7**：`docs/M2-排期计划.md`（mtime `23:30:26`，+15 行只追加）。两份施工报告**均声明**「未动台账、不代写排期」（苞 A §7-D6 / §8-1）⇒ 归属不明，且「6 改动 + 2 新增」验收口径已失效 | §1.2；`git diff docs/M2-排期计划.md` | **属流程/归属问题，非苞 A/B 缺陷**。须由指挥官确认：该 hunk 随哪次提交入库、是否单列提交。内容本身已复核（命名纪律/盘符/IP/用户名 0 命中，`check-docs` 复跑 6/6） |
| **F2** | MINOR | 复活竞态口径偏轻：实测**在飞路径下确定性**（2/2 ≤500ms 复活），而注释/施工卡写「毫秒~秒级竞态」「把复活窗口压到最小」 | §4.2；`stores/ai.ts:1096`/`:1144`；施工卡 §5.1 | 订正为「在飞路径下 DELETE 必然先于收盘重写 ⇒ 旧键必被写回」；或在报告中明确接受该措辞 |
| **F3** | MINOR | `removedFromIndex` 是**响应时点**事实，观测不到「随后被收盘写回」；苞 A §7-D2 声称的收益不成立（活体反证：报 true 后 500ms 被写回） | §4.3；`AiController.java:583` | 改名（`removedAtResponseTime`）或在 `note` 补时点说明 |
| **F4** | MINOR | 新端点每次调用触发 `findConversationIds()` = `SMEMBERS` + **每个存活 id 一次 `EXISTS`**（本机 ~100 成员 ⇒ ~100 次往返），仅为前端不读的诊断字段；苞 A §7-D2 登记的成本「一次 SMEMBERS」低估 N 倍 | §4.4；`AiController.java:583`；`RedisChatMemoryRepository.java:88-108` | 改 `SISMEMBER`（1 次往返），或把该字段移出热路径 |
| **F5** | MINOR | `resetSession` 的网络失败提示**无「仍是本轮」守卫**：`:1149` 的 `notice='旧会话记忆删除失败（不影响新对话）'` 异步落地（`HTTP_TIMEOUT_MS=60_000`），后端不可达时可达 60s 后被写入**新会话**的 `notice`，覆盖新轮提示。store 其它异步落盘点均有 `isCurrentRound()` 守卫，此处没有 | `stores/ai.ts:1145-1150`；`api/http.ts:103` | 加一次性快照守卫（记下调用时的 sessionId，回写前比对），或失败只 `log` 不写 `notice` |
| **F6** | 观察 | **既有**缺陷：未知路由 → **500**（非 404）。`GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜住 `NoResourceFoundException` | §4.6；`GlobalExceptionHandler.java:52-65`；`boot.log` 栈 | 用与本次无关的路径复现 ⇒ **不归本次改动**，登记待收口 |
| **F7** | 观察 | 鉴权/归属：新端点可删任意**已知** sessionId 的记忆；全仓无鉴权（DC-10）。净增量 = 同命名空间内新增破坏性动词；最脆的 id 回退形态（~41 bit）恰在非安全上下文激活 | §4.1 | 引入鉴权时必须同步补会话归属校验（登记待办） |
| **F8** | 观察 | 新测试类用例 8 的注释「走 HTTP 到达不了这一分支」**只在 MockMvc 下成立**；生产 Tomcat 实测 `%20` 可达该守卫并返回 400 | §4.6；`AiControllerHistoryDeleteTest.java:186-188` | 注释补一句「MockMvc 口径；生产容器可达」 |
| **F9** | 观察 | 冒烟 ⑧ 的证据边界：断言对象是「传给 `writeText` 的字符串 === `m.content`」（严格相等，可靠），**系统剪贴板回读未获独立确认**——本次实跑 `clipboardReadback="Shiki "`（与内容无关，属自动化 profile 环境） | 冒烟日志阶段 8；脚本已如实标为非断言 | 结论表述为「内容已交给剪贴板 API / execCommand 通道」，不要说「系统剪贴板内容已验证」 |
| **F10** | 观察 | 命名纪律门禁 ⑤ 只扫 `git ls-files` ⇒ 两个**未跟踪**新文件不在门禁内（CI checkout 后即覆盖，盲区仅在提交前） | `scripts/ci/check-docs.mjs` 注释 | 本次已按要求复刻三态正则独立扫码：**命中 0**（含盘符/IP/用户名） |

---

## 6. 未独立复现项（如实登记，**不视为通过**）

| 项 | 原因 | 替代证据 |
|---|---|---|
| CDP 冒烟 **⑫ 真断流仍亮红条** | 按本次复核的进程纪律**未设** `SMOKE_KILL_BACKEND`（脚本三重闸第①道不成立）⇒ 脚本**如实报 SKIP**（`backendKill.ok=false`，`reason='SMOKE_KILL_BACKEND 未置 1'`），未假绿 | ① 机制层：`finish` → 3 个 handler 是**全量**路径（§3.4-D1），三处守卫均为 `&& !this.stopped`，真断流时 `stopped=false` ⇒ 判定等价于改动前；② 反向面 ⑪ 已实测（主动停止不亮红条） |
| 后端 `clean test` 的「开工基线 389 / 60 XML」 | 需 `git stash` 构造，与「严格只读」冲突 | 算术自洽（397−8=389、61−1=60）+ 新类恰好 8 用例/1 XML（§2.1） |
| **Redis 不可用 → 503** 真停机实测 | 本机 Redis（`127.0.0.1:6379`）是**共享服务**；停机或 `CLIENT PAUSE` 会波及其他并行运行的工作 | 单测 `redisUnavailableFailsFastWithoutTouchingMemory`（含 `verify(never()).clear`）+ 代码顺序实读（`:565` 在任何 Redis 操作之前）+ `RedisAvailability.code()="AI_REDIS_UNAVAILABLE"` 实读 |
| **无 AI key** 形态重启实测 | 需以无 key 重启后端并重跑全套 | 单测两形态（`noAiKeyStillDeletes` / `aiKeyPresentAlsoDeletes`）+ 新方法体内 `availability` 0 引用实读 |
| 系统剪贴板内容 | 见 F9 | ⑧ 的 `writeText` 拦截逐字符相等 + ⑨/⑩ 的 `activeElement.value` 回退文本核对 |

---

## 7. 复现命令与证据落盘（均在仓库外）

```bash
# 1) 后端全量（包装脚本透传 Maven 全路径，规避含空格路径）
bash <SCRATCH>/.run-fix/mvn-run.sh -B clean test            # → 397/0/0/0, 61 XML, exit 0

# 2) 前端
cd <REPO>/frontend && npm run typecheck && node scripts/build.mjs   # 均 exit 0

# 3) 文档门禁（跑两次：一次在 1.2 新 hunk 之前，一次之后）
cd <REPO> && node scripts/ci/check-docs.mjs                 # 6/6, exit 0

# 4) 桩自检 + 编排（桩 127.0.0.1:18399 → 后端 jar :18330，工作目录在仓库外）
node <REPO>/scripts/ci/stub-upstream.mjs --self-check
cd <SCRATCH>/e2e-run && AI_API_KEY=<占位串> AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub \
  java -jar <REPO>/backend/target/config-mgr.jar --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/ci_e2e_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150        # 就绪：/api/ai/health 的 available+redis.available 均 true；/api/definitions 达 15

# 5) E2E 29 用例（桩模式，含 GF6）
cd <REPO> && E2E_STUB_MODE=1 powershell -NoProfile -ExecutionPolicy Bypass \
  -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 \
  -BackendLog <SCRATCH>/e2e-artifacts/boot.log \
  -ArtifactDir <SCRATCH>/e2e-artifacts/gfc -EnableGf6            # → PASS=29 FAIL=0 GFSKIP=0, exit 0

# 6) 前端 dev（代理指向 18330）+ CDP 冒烟（未设 SMOKE_KILL_BACKEND）
cd <REPO>/frontend && VITE_API_BASE=http://localhost:18330 VITE_DEV_PORT=5202 npx vite
cd <REPO> && SMOKE_OUT=<SCRATCH>/smoke-out SMOKE_CDP_PORT=9334 \
  SMOKE_API=http://localhost:18330 SMOKE_APP=http://localhost:5202/ \
  node scripts/chat-panel-smoke.mjs                              # → 12 PASS + ⑫ SKIP, exit 0

# 7) 活体端点探针（裸 Map / 幂等 / 范围 / runActive / 竞态）
curl -N -X POST :18330/api/ai/chat -d '{"sessionId":"<sid>","message":"你好"}'
curl -i -X DELETE :18330/api/ai/history/<sid>
<MEMURAI_HOME>/memurai-cli.exe -p 6379 {exists,llen,sismember,smembers,scan} …
```

| 证据 | 位置（仓库外） |
|---|---|
| 后端全量日志 | `<SCRATCH>/mvn-full-recheck.log` |
| 前端 build 输出 | 终端（`frontend/dist/`，`.gitignore` 已豁免） |
| E2E 日志 + GF 证据 | `<SCRATCH>/e2e-recheck.log`、`<SCRATCH>/e2e-artifacts/{boot.log,stub/,gfc/}` |
| CDP 冒烟日志 + 结果 JSON | `<SCRATCH>/smoke-recheck.log`、`<SCRATCH>/smoke-out/result-20261010T152437Z.json` |
| 活体竞态复现 | 本报告 §4.2 原文（含 runId / count 数字） |

---

## 8. 复核动作的只读性与环境清理

- **仓库只读**：对 `<REPO>` 零文件编辑、零 `git add/commit/push`；`git status --porcelain` 在复核前后均为 **4 改 + 2 新增**（+1.2 的台账 hunk，非我所为），无新增未跟踪文件、无遗留脚本。
- **构建产物**：仅 `backend/target/`（maven）、`frontend/dist/`（build），二者均在 `.gitignore` 内；仓库根 `data/` 与 `backend/data/` 经 mtime 核对为**改动前既有**（`2026-10-09` / `2026-10-07`），非本次产生。
- **端口纪律**：启动前 `netstat` 预检（18330/18399/5202/9334 全空 + 共享 Redis 6379 在线）；收尾按**监听 PID** 停自启进程（`java.exe`=18330、`node.exe`=18399、`node.exe`=5202），实测 **四端口全部释放**；共享 Redis（6379）与用户自己的 Chrome 进程**未触碰**；冒烟自启的 Chrome 已由脚本自身回收（按命令行核对 `*redteam-batch-a*` 零残留）。
- **未使用真实 AI key**：全程占位串（`ci-stub-placeholder`）经环境变量注入，密钥零落盘。
