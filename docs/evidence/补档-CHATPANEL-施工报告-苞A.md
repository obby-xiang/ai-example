> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 施工报告 · 对话面板包 苞 A（新建对话按钮 + 必修前置缺陷修复）

- 施工日期：2026-10-10
- 施工依据：`<对话面板包目录>\施工卡.md`（定稿 v1.0（2026-10-10））§1（项 1）为主依据；裁决要点以指挥官下达的苞 A 裁决 6 条为准
- 目标仓库：`<MAIN_REPO>`（分支 `main`，开工 HEAD `03a6cbe`，工作树干净）
- 施工范围：**苞 A = 项 1 全量**（后端新端点 + 前端 api/store/面板 + 空态小字）+ 施工卡 §1.2「第一位缺陷」必修
- 不在本苞范围：项 2 单条复制、项 3 停止误闪红条、项 4 骨架屏（施工卡 §7 划入苞 B；但 §1.2 明确要求 `resetSession` 内一并补的 `historyLoaded/stopped/streamInterrupted` 三行属本苞，已落）
- 纪律遵守：**零 git 写操作**（无 add/commit/push/stash/checkout）；未触碰工作区另六个源分支克隆目录；AI_API_KEY 仅环境变量注入、未写入任何文件；测试/桩一律占位 key

---

## 0. 结论摘要（一句话）

苞 A 全部落地：**后端新增 `DELETE /api/ai/history/{sessionId}`（裸 Map、幂等不 404、Redis 不可用 503、查会话门但只报告不 409、只删记忆面）**；**前端头部「新建对话」中文文字按钮（空置灰、无二次确认、点击=立即清屏+换新 sessionId+删旧记忆）**；**空态页签语义小字（保守版逐字）**；并**同批修复「resetSession 换 id 从未同步 store state」的实测缺陷**（修复前后见 §4，行为级反证见 §6.5 CDP 冒烟断言④）。五个验证面全实测通过（§6）。

---

## 1. 改动文件清单（6 个：4 改 + 2 新增）

| # | 路径（仓库相对） | 类型 | 行级说明 |
|---|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | 改 | `:9` 新增 `import …memory.RedisChatMemoryRepository`（记忆键前缀常量）；`:37` 新增 `import …annotation.DeleteMapping`；`:74-76` 类注端点表新增 `DELETE /api/ai/history/{sessionId}` 行；`:82` 类注 Q2 段补「历史的读与删除」；`:531-591` 新增端点方法 `deleteHistory`（`@DeleteMapping` 在 `:560`，含 javadoc 共 61 行）；**构造器零改动** |
| 2 | `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java` | 新增 | 211 行，8 个用例方法（矩阵见 §5）；构造方式照抄 `AiControllerRunsCurrentTest`（`new AiController(...)` + Mockito 桩，不改构造器） |
| 3 | `frontend/src/api/ai.ts` | 改 | `:8` 引入 `del` helper（`http.ts:144-147` 现成，未新增）；`:68-78` 新增 `deleteHistory(sessionId)`（`:75`）；`:138` 挂进 `aiApi` 导出对象 |
| 4 | `frontend/src/stores/ai.ts` | 改 | `:1076-1143` 重写 `resetSession`（签名在 `:1090`）：`(): void` → `async (): Promise<void>`；新增旧值捕获、`sessionId` 双写（必修缺陷，`:1114`）、`stopped/streamInterrupted/historyLoaded` 复位、best-effort `cancel`、末尾 `deleteHistory`（逐行对照见 §4） |
| 5 | `frontend/src/components/AiPanel/AiPanel.vue` | 改 | `:20-29` 头部标题块与「收起」按钮之间插入文字按钮（`:23` `data-testid="new-chat"`、`:25` `:disabled="!ai.messages.length"`）；`:128` 空态 `el-empty` 的 `#description` 内追加页签语义小字一行；`:311-312` 组件头注释补该功能增量；`:545-550` 新增 `newChat()`（`:548`）调用 store |
| 6 | `scripts/chat-panel-smoke.mjs` | 新增 | 302 行，苞 A 项 1 的 CDP 行为级冒烟（独立脚本，**不改动既有 `m2t2a-smoke.mjs`**）；5 条断言见 §6.5 |

`git diff --stat`：4 个跟踪文件 **+148 / −8**；两个新增文件共 513 行。工作区其余文件零改动（`frontend/dist`、`backend/target` 均被忽略，`git status --porcelain` 恰为本表 6 项）。

---

## 2. 新端点契约

### 2.1 方法与路径

| 项 | 值 |
|---|---|
| 方法 + 路径 | `DELETE /api/ai/history/{sessionId}` |
| 相对既有读侧 | 与 `GET /api/ai/history/{sessionId}`（`AiController.java:505-529`）**同资源、同路径、异动词** —— 被删的正是该 GET 返回的那份"记忆窗口原文" |
| 路径变量 | `sessionId`（前端页签级会话 id；`encodeURIComponent` 由前端负责） |
| 鉴权 | **无**（DC-10：本期无认证鉴权，与全仓 AI 端点一致，未新增任何锁） |
| 可用性门 | **不查 AI key**（记忆是纯 Redis 操作，无 key 时 `chatMemory` bean 依然存在，与 `/history` 同类）；**查 Redis**：`!redisAvailability.isAvailable()` → `503` |

### 2.2 返回形态（裸 Map，不套统一响应壳）

成功（**200**，`Content-Type: application/json`，`ResponseEntity.ok(body)`，裸 `Map<String,Object>` 顶层 9 键）：

```json
{
  "sessionId": "<oldId>",
  "deleted": true,
  "existed": true,
  "memoryKey": "chat:mem:<oldId>",
  "removedFromIndex": true,
  "runActive": false,
  "activeRunId": null,
  "storedBy": "<instanceId>",
  "note": "只删记忆面（chat:mem:<sessionId> 与其会话 id 索引）；不删 ai:session:<sessionId> 与 ai:run:*（两者各有自己的 TTL）。删除不存在的会话同样 200（幂等）。runActive=true 表示该会话仍有在跑轮次，该轮收尾的记忆收敛可能把窗口重新写回同名键。"
}
```

- `existed` = 删除**之前** `chatMemory.get(sessionId)` 是否非空（实测计数，非猜测）；
- `deleted` 恒 `true`（幂等语义 = "已处于删除状态"），与施工卡 §1.5-E4 口径一致；
- `removedFromIndex` = 删除**之后**该会话 id 是否仍在记忆仓储的会话 id 索引里（`s-1` 仍在索引里时为 `false`）。**口径（复核 F2/F3/F4 收口订正）**：判存实现已由 `findConversationIds().contains(...)`（`SMEMBERS` + 逐 id `EXISTS`，N 倍往返）改为仓储内的**单次 `SISMEMBER`**；且该字段是**响应时点**事实 —— 在跑轮次收盘的 `clear + add` 会把成员写回，故 `true` 只表示"此刻已不在索引里"，**不保证随后不被覆盖**。（原表述「"删除被收尾写回覆盖"这一事实可被观测」与实测不符，已订正。）
- `storedBy` 复用既有 `runStore.instanceId()`（与 `/cancel`、`/confirm` 同族字段）。

错误体（复用既有 `json()` 私有 helper，**显式指定 JSON 内容类型**）：

| 分支 | 状态码 | 体 |
|---|---|---|
| `sessionId` 全空白 | 400 | `{code:"BAD_REQUEST", message:"sessionId 必填"}` |
| Redis 探活失败 | 503 | `{code:"AI_REDIS_UNAVAILABLE", message:<探活原因>, redis:{…}}`（复用 `redisUnavailable()`，与 `/chat`、`/resume` 同口径） |
| 会话不存在 / 重复删除 | **200** | 同上成功体，`existed:false`（**明确不 404**） |
| 会话仍有在跑轮次 | **200** | 同上成功体，`runActive:true` + `activeRunId`（**明确不 409**） |

### 2.3 删除范围（按裁决 key 范围）

| 键 | 是否删 | 依据 |
|---|---|---|
| `chat:mem:<sessionId>`（记忆窗口 LIST） | **删** | 走官方 `ChatMemory#clear(sessionId)`（DC-05「Spring AI 已有能力禁止自研」）；`MessageWindowChatMemory` 落到 `RedisChatMemoryRepository#deleteByConversationId` 的 `DEL` |
| `chat:mem:__ids__`（会话 id 索引 SET） | **删该成员**（`SREM`） | 同一函数内，非新增写路径；`removedFromIndex` 字段即其结果 |
| `ai:session:<sessionId>`（会话滑动 TTL） | **不删** | 裁决 2 + 施工卡 §1.5-E4：各有自己的 TTL；删它会把"会话生命周期"与"记忆删除"耦合 |
| `ai:run:*`（轮次台账/快照/取消标志/锁） | **不删** | 同上：删了会让"重启续跑"与"取证"失去依据 |
| `ai:session:lock:<sessionId>` | **不删**（只**读**一次） | 该键是 SessionGate 的持锁事实，删它等于绕过会话门 |

**实现落点选择**：优先 `this.chatMemory.clear(sessionId)`（官方能力）而**非**直接调 `deleteByConversationId`（死代码行）—— 二者最终落到同一行 Redis `DEL + SREM`，但走官方路径符合 DC-05，且**不需要新增构造器依赖**（`chatMemory` 已在构造器注入）。

### 2.4 SessionGate 行为：**只报告，不硬拒（明确否掉 409）**

- 端点内**只读一次** `sessionGate.currentRunId(sessionId)`（不 `acquire`、不持锁、不改锁）；
- `activeRunId != null` 时：① 返回体报告 `runActive:true` + `activeRunId`；② 打一条 **WARN 日志**（"只记录/报告"的"记录"落地）——实测日志样例：
  ```
  WARN c.e.configmgr.ai.web.AiController - 删除会话记忆时该会话仍有在跑轮次（记忆可能被该轮收尾的收敛写回）sessionId=s-1 activeRunId=r-1
  ```
- **仍然执行删除并返回 200**，绝不返回 409（裁决 3 明确否掉；用例 `activeRunIsReportedNotRejected` 钉死该契约）；
- 复活窗口**不由前端顺序收口**（复核 F2 订正）：`resetSession` 内确实先 `POST /api/ai/cancel/{oldRunId}` 再 `DELETE`，但 `cancel` 只是"置取消标志 + 唤醒收尾"、轮次收盘是异步的 ⇒ **在飞路径下 DELETE 必然先于收盘重写**（顺序必然，不是概率竞态），该顺序**不缩小**复活窗口；残留受会话 TTL 约束、旧 id 已被前端丢弃（用户不可见）。

---

## 3. 【必修前置缺陷】resetSession 换 id 从未同步 store state —— 单列留痕

> 施工卡 §1.2「第一位缺陷」/ §9.1 D-1。**该缺陷不是设计变更，是接线前必须发现并修掉的既存缺陷**，本苞同批修复并单列。

### 3.1 修复前（`HEAD` 版本，实读）

```ts
    /** 重置会话（新页签语义：清 sessionId 与镜像）。 */
    resetSession(): void {
      …
      try {
        sessionStorage.removeItem(MIRROR_PREFIX + this.sessionId)
        sessionStorage.setItem(SESSION_ID_KEY, createSessionId())   // ← 只写 sessionStorage
      } catch {
        // 隐私模式下忽略
      }
      this.messages = []
      …
    }
```

**实读证据【实测】**（对 `HEAD` 版本执行 `git show HEAD:frontend/src/stores/ai.ts`）：

| 判据 | 命中 |
|---|---|
| `this.sessionId =`（**赋值**命中数） | **0** |
| `this.sessionId` 全部命中 | 6 处，**全为读取**：`:360`（写镜像）/`:423`（读镜像）/`:435`（`aiApi.history`）/`:490`（`runsCurrent`）/`:695`（`aiApi.chat` 请求体）/`:1089`（删镜像键） |

**后果**：`send:695` 仍以**旧 id** 请求 ⇒ "新建对话"实际继续使用旧会话的服务端记忆；镜像写到旧键 ⇒ 刷新后又从旧键恢复。三项后果均属功能级，且因唯一调用方是 E2E 收尾清理（`scripts/m2t2a-smoke.mjs`）而从未暴露在用户可见路径上。

### 3.2 修复后（本苞）

```ts
      const oldSessionId = this.sessionId          // ① 旧值先捕获
      const oldRunId = this.activeRunId
      …（abort / 模块级句柄复位 / loading）
      const nextId = createSessionId()             // ② 只生成一次
      try {
        sessionStorage.removeItem(MIRROR_PREFIX + oldSessionId)
        sessionStorage.setItem(SESSION_ID_KEY, nextId)
      } catch { /* 隐私模式/配额：持久化失败不阻断重置 */ }
      this.sessionId = nextId                      // ③ ← 修复点：state 双写（响应式生效）
```

- 赋值命中数从 **0 → 1**（`frontend/src/stores/ai.ts:1114`）；
- 同一个 `nextId` 同时进 `sessionStorage` 与 `state`，**不会**出现"两次 `createSessionId()` 生成两个 id"的口径漏洞（施工卡 §1.2 硬要求）；
- `removeItem` 用捕获的 `oldSessionId`，不会"先覆盖后删"删错键；
- 失败分支（隐私模式）也保证 state 换新 id（此前该分支会让新旧 id 与网络请求三方错位）。

### 3.3 行为级反证（实跑，见 §6.5）

CDP 冒烟**断言④**：点「新建对话」后在新会话再发一轮 → `GET /api/ai/history/{新 id}` 的 `count=2`，而 `GET /api/ai/history/{旧 id}` 仍 `count=0`。若 `send` 仍带旧 id（缺陷未修），记忆会写回旧 id、断言④ 必红。**实测结果：PASS（新 id=2 / 旧 id=0）**。

---

## 4. `resetSession` 改动逐行对照

| 步骤 | 修复前 | 修复后（本苞） | 说明 |
|---|---|---|---|
| 1 | 直接 `abort()` | `const oldSessionId = this.sessionId`；`const oldRunId = this.activeRunId`；再 `abort()` | **必须在清 `activeRunId` 之前取值**（施工卡 §1.2） |
| 2 | — | `currentAbort=null`；`activeRoundHostId=null`；`this.loading=false`（原样保留） | 不变 |
| 3 | `sessionStorage.setItem(SESSION_ID_KEY, createSessionId())` | `const nextId = createSessionId()`；`removeItem(MIRROR_PREFIX + oldSessionId)`；`setItem(SESSION_ID_KEY, nextId)`；**`this.sessionId = nextId`** | 必修缺陷修复点 |
| 4 | 清 `messages/activeRunId/notice/busyConflict/poolSaturated/suspended/activeForm` | 同上 + **`stopped=false`、`streamInterrupted=false`、`historyLoaded=true`** | 施工卡 §1.2/§4.2 要求：新会话不继承上一轮的停止/断流事实，且清空后不落回骨架屏 |
| 5 | — | `if (oldRunId) try { await aiApi.cancel(oldRunId) } catch {}` | best-effort，吞异常（口径同 `stop()`）；只 abort 本地不断后端，故必须显式 POST |
| 6 | — | `try { await aiApi.deleteHistory(oldSessionId) } catch { this.notice='旧会话记忆删除失败（不影响新对话）' }` | **网络动作的最后一步**（先 cancel 后 delete） |

**本次的一处显式偏差（已留痕，见 §7-D1）**：施工卡 §1.2 表格把两个网络动作排在"清状态块"**之前**，若照排则 `this.notice = null`（清状态块内）会**抹掉**删除失败的提示。本苞改为「本地清屏/换 id（同步）→ cancel → delete」，即：点击立即清屏的体验不变、`notice` 提示不被抹掉、"先 cancel 再 delete 且 delete 放最后"两条硬要求仍满足。

**异步化的兼容性**：唯一既有调用方 `scripts/m2t2a-smoke.mjs` 是 `ai.resetSession(); return true`（不 await、不读返回值）⇒ 改 async 不破坏该脚本（实测：E2E/冒烟均未受影响，见 §6.6）。

---

## 5. 新增测试用例矩阵（`AiControllerHistoryDeleteTest`，8 条 + 复核收口 F4 追加 2 条 = 10 条）

| # | 用例方法 | 场景 | 断言要点 |
|---|---|---|---|
| 1 | `existingMemoryIsDeletedAndReported` | 有记忆 → DELETE | 200；`deleted=true`、`existed=true`、`memoryKey=chat:mem:s-1`、`removedFromIndex=true`、`storedBy=inst-1`；**`chatMemory.clear("s-1")` 被调用** |
| 2 | `missingMemoryIsIdempotentOkNotNotFound` | 无记忆 / TTL 已过期 | **200（不得 404）**；`deleted=true`、`existed=false` |
| 3 | `repeatedDeleteSecondCallReportsNotExisted` | 重复 DELETE | 两次都 200；第二次 `existed=false` |
| 4 | `redisUnavailableFailsFastWithoutTouchingMemory` | Redis 不可用 | **503** + `code=AI_REDIS_UNAVAILABLE`；**`clear` 从未被调用**（不得先删后报错） |
| 5 | `noAiKeyStillDeletes` | 无 AI key（`availability=false`，用例内先 `assertFalse` 自证桩值） | **200**（不得 503 `AI_UNAVAILABLE`）；`existed=true`；`clear` 被调用 |
| 6 | `aiKeyPresentAlsoDeletes` | 有 AI key | 200，与无 key 形态行为一致（本端点与 key 无关） |
| 7 | `activeRunIsReportedNotRejected` | 会话仍有在跑轮次（`currentRunId="r-1"`），且索引里仍留着该 id（模拟收尾写回） | **200（明确不 409）**；`runActive=true`、`activeRunId=r-1`、`removedFromIndex=false`（"删得不彻底"可观测）；`clear` 仍被调用 |
| 8 | `blankSessionIdIsBadRequest` | `sessionId` 全空白 | 400 + `code=BAD_REQUEST`；`clear` 从未被调用。**注**：该分支走 HTTP 到不了（MockMvc 会把 `%20` 双编码成字面量 `"%20"`，实测 `sessionId="%20"` 且非空白；真正空白段在 Spring 里不匹配本 mapping）⇒ 用例改为**直接调用端点方法**验证守卫，并在用例内注明缘由。**复核 F8 口径补注**：该"到达不了"只在 **MockMvc** 下成立，生产容器下该 `%20` 空白段可达守卫并返回 **400**（已登记台账，注释措辞待议） |
| 9 | `indexedCheckIsSingleSismemberNotFullIndexScan`（复核 F4 追加） | 真 `RedisChatMemoryRepository` 装配，索引里仍有 `s-1` | 200；`removedFromIndex=false`；`SISMEMBER chat:mem:__ids__ s-1` **恰好一次**；**`SMEMBERS` 从未被调用、逐 id `EXISTS` 从未被调用** |
| 10 | `absentMemberReportsRemovedFromIndexTrueWithSingleRoundTrip`（复核 F4 追加） | 同上装配，成员不在索引里（`SISMEMBER` 回 null） | 200；`deleted=true`、`removedFromIndex=true`；仍只走那一次 `SISMEMBER`（无全量索引、无逐 id `EXISTS`） |

后端其余测试类**零改动**（`AiController` 构造器签名未变，全仓 ≥5 处 `new AiController(...)` 调用点零 churn）。

---

## 6. 全部实测数字（全部实跑）

### 6.1 后端 · 定向

```
<mvn-run.sh> -B test -Dtest=AiControllerHistoryDeleteTest -DfailIfNoSpecifiedTests=false
→ Tests run: 8, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

（首次运行的 1 红为用例 8 的断言口径问题，已按 §5 说明改写为方法级守卫验证后转绿；**不是产品缺陷**。）

### 6.2 后端 · 全量（`clean test`）

```
<mvn-run.sh> -B clean test
→ Tests run: 397, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS（01:27 min）

surefire XML 求和：61 个 XML / tests=397 / failures=0 / errors=0 / skipped=0
基线（开工时给定）：389 / 0 / 0 / 0，60 个 XML
差值：+8 用例 / +1 XML —— 分解 = 新增 1 个测试类 AiControllerHistoryDeleteTest（8 用例），既有 60 个 XML 一个未减
```

- `target/surefire-reports/` 内 1 个 `.dumpstream`（内容为 surefire 关于"Boot Manifest-JAR 含绝对路径"的告警提示），与用例结果无关；
- 已知 flaky `SseChatEmitterWriteBudgetTest`：**本批全量实跑未见红**（无需单类重跑）。

### 6.3 前端 · typecheck + build

```
cd frontend && yarn typecheck   → vue-tsc --noEmit，Done in 5.23s（0 error）
           && yarn build        → ✓ built in 39.14s（dist 产物正常）
```

### 6.4 门禁

```
node scripts/ci/check-docs.mjs
→ ① 无盘符绝对路径 PASS   ② 技术栈版本一致 PASS   ③ 引用文件存在 PASS
   ④ 端口表一致 PASS      ⑤ 命名纪律（git ls-files 920 路径，实扫 917）PASS   ⑥ 探针不参与构建 PASS
   全部通过：6/6 断言绿
```
- 命名纪律自查（复刻断言⑤三态正则，扫 `git status` 全量 6 个变更文件，**含 2 个未跟踪的新文件**——断言⑤ 只扫 `git ls-files`，未跟踪文件需自查补齐）：**命中 0 处**。

### 6.5 CDP 冒烟（苞 A 项 1 行为级证据，`scripts/chat-panel-smoke.mjs`）

编排：桩上游（18399）+ 后端 jar（18330，占位 key）+ 前端 dev（5202，代理指向 18330）+ 自启 Chrome（9334）。端口纪律：18330 起、桩 18399；启动前 netstat 预检；收尾只按监听 PID 杀自启进程，实测四个端口全部释放。

> key 口径：本苞**全程未使用真实 AI key**（无真模型轮），后端与桩一律占位 key 经环境变量注入（`AI_API_KEY=ci-stub-placeholder`），密钥零落盘、零写入任何文件。苞 A 的验收面不依赖模型行为（新建/清空/删除记忆都是确定性链路）。

| 断言 | 内容 | 实测 |
|---|---|---|
| ① | 空态：无消息时按钮**置灰**（`disabled===true`）、按钮文案逐字 `新建对话`、空态小字可见 | **PASS** `{messages:0, buttonDisabled:true, buttonText:"新建对话", hintVisible:true}` |
| ② | 一轮真实对话后：按钮**可点**、后端 `GET /api/ai/history/{sid}` 有记忆 | **PASS** `{messages:2, buttonDisabled:false, backendHistoryCount:2}` |
| ③ | 点「新建对话」：立刻清屏 + 回置灰 + **`ai.sessionId` 与 `sessionStorage['ai-session-id']` 同步且都变了**（必修缺陷验收）+ 旧镜像键已删 + 后端旧会话 `count===0` + `notice===null`（删除未失败） | **PASS** `{messages:0, buttonDisabled:true, notice:null, sessionIdChanged:true, stateMatchesStorage:true, oldMirrorRemoved:true, oldSessionHistoryCount:0}` |
| ④ | **缺陷修复的行为级反证**：新会话再发一轮 → 记忆记在**新 id**（旧 id 仍 0） | **PASS** `{newSessionHistoryCount:2, oldSessionHistoryCount:0}` |
| ⑤ | 置灰态点击无副作用（点不动） | **PASS** `{sessionIdUnchanged:true, buttonDisabled:true}` |

```
SMOKE_EXIT=0   elapsed=7s   结果 JSON 落在 <SCRATCH>/smoke-out/result-<UTC 时间戳>.json
```

**复跑**：为把"删除未失败"钉进断言（阶段③ 增判 `notice === null`），同一脚本**第二次实跑**仍 **5/5 PASS、`SMOKE_EXIT=0`、7s**（结果 JSON 分轮落盘，两次互不覆盖）⇒ 可重复性成立。

### 6.6 E2E 回归（桩模式 29 用例 · `-EnableGf6` · `E2E_STUB_MODE=1`）

```
<run-e2e.sh>（桩自检 PASS → 桩上游 18399 --violate-form-type → 后端 jar 18330 → verify-e2e.ps1 -EnableGf6）
→ <见下方「本轮结果」段>
```

**本轮结果（实跑）**：

```
桩上游自检：SELF-CHECK PASS（路由表契约/白名单对账/收敛自证/披露驱动/帧形态/反解全通过）
E2E_EXIT=0  elapsed=56s
 结果：PASS=29  FAIL=0   （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6（含 -EnableGf6 的 GF6））
 GFSKIP=0              （桩模式零容忍：GFSKIP≥1 即整棒 FAIL）
 Q8 项：PASS=2  FAIL=0  （不计入主用例计数）
```

⇒ **项 1 的前后端改动对既有 29 用例零回归**；`scripts/m2t2a-smoke.mjs` 收尾调用的 `resetSession()` 改 async 后行为不变（该脚本不 await、不读返回值，异常已被 store 内吞掉）。

---

## 7. 偏差与处置

| # | 偏差 | 处置 / 理由 |
|---|---|---|
| D1 | 施工卡 §1.2 表格把「cancel + delete」排在清状态块之前；本苞改为「本地清屏/换 id（同步）→ cancel → delete」 | ① 裁决原文是"点击=**立即清屏**+换新 id+调新端点"，网络动作在后更贴合；② 照卡排会把删除失败的 `notice` 被随后的 `this.notice=null` 抹掉（真实的提示丢失）；③ "先 cancel 再 delete、delete 放最后"两条硬要求仍满足。**未改动任何可观测契约** |
| D2 | 端点返回体多打了一次 Redis 读算 `removedFromIndex` | 逐字采施工卡 §1.5-E4 草案字段集。**复核收口订正（F4）**：判存已改为仓储的单次 `SISMEMBER`（原为 `findConversationIds()` = `SMEMBERS` + 逐 id `EXISTS`，本行原写"成本 = 一次 SMEMBERS"低估 N 倍，已订正）；**收益口径亦订正（F3）**：该字段是响应时点事实，**观测不到**"随后被收尾写回覆盖"，前端不读该字段、影响仅限取证口径 |
| D3 | 施工卡 §1.5-E4 建议"`existed` 取 `chatMemory.get` 非空"；本苞额外做了 **null 防御**（`existing != null && !existing.isEmpty()`） | `AiController:508`（读侧）既有写法不判 null；新方法（`:570-571`）加判空避免第三方 `ChatMemory` 实现返回 null 时抛 500（端点必须幂等可靠） |
| D4 | 用例 8 未走 HTTP 而直接调方法 | `%20` 被 MockMvc 双编码成字面量，HTTP 面无法构造空白路径变量；用例内已注明实测事实 |
| D5 | `resetSession` 内 `historyLoaded=true` 属苞 B（项 4 骨架屏）的消费前提 | 施工卡 §1.2 明确把它列进苞 A 的 `resetSession` 清理块，故本苞落地；当前无消费点，属"零成本确定性收口" |
| D6 | 未新增 `docs/evidence/` 证据文档、未生成 R-100 排期行文本 | 施工卡 §6.3「证据文档是否入库由指挥官定」；排期登记体例为"只追加"，写台账属指挥官动作，本苞不代写（见 §8） |
| D7 | `<SCRATCH>` 内的编排脚本与原始日志含本机路径；仓库内 6 个文件的命名纪律实测命中 0 | 脱敏要求针对**仓库内容与报告正文**：仓库内 6 个文件零命中（含 2 个新文件）；**报告正文**一律 `<>` 占位符（盘符/用户名/内网 IP 全零命中，实测）；`<SCRATCH>` 原始日志在仓库外、未入库，另出脱敏摘录 `<SCRATCH>/证据摘要-苞A.md` |

---

## 8. 【待裁决】

| # | 事项 | 本苞处置与建议 |
|---|---|---|
| 1 | **R-100 排期行文本是否入库**（施工卡 §8.2 已给草案；体例为"只追加、既有行零改动"） | 未动台账（零 git 写 + 不代写排期）；建议由指挥官按 §8.2 草案追加，编号 `R-100` |
| 2 | **是否补 `docs/evidence/` 证据文档**（本苞的实测数字与冒烟结果 JSON 现落 `<SCRATCH>`，仓库外） | 未新增；若需入库，可把本报告 §6 数字 + 冒烟结果 JSON 摘要落 `docs/evidence/`（走既有 M2 证据命名体例） |
| 3 | **`deleted` 恒 `true` vs 复核式 `deleted`** | 本苞按施工卡 §1.5-E4「`deleted` 恒为 true（幂等语义）」实现，同时用 `removedFromIndex=false` 如实暴露"可能被收尾写回"。若指挥官要 `deleted` 也如实（复核后置假），改 1 行即可 |
| 4 | **`scripts/chat-panel-smoke.mjs` 的归属与扩展** | 本苞按施工卡 §6.2 建议新增，当前只覆盖**项 1** 断言（① ~ ⑤）。苞 B 落地项 2/3/4 时可在同一脚本追加阶段（复制/停止抑制/骨架屏），或另起脚本 —— 由指挥官定 |
| 5 | 旧会话记忆的"复活残留"（`convergeMemory` 竞态，≤ TTL 的 Redis 残留） | 按裁决与施工卡 §5.2 **登记为已知代价**，不做墓碑键/端点锁，不改 `convergeMemory`；端点已用 `runActive`/`activeRunId`/WARN 日志报告该事实 |

---

## 9. 附录：证据落盘位置（均在仓库外）

| 证据 | 位置 |
|---|---|
| **脱敏证据摘要**（本报告 §6 数字的原始出处摘录） | `<SCRATCH>/证据摘要-苞A.md` |
| 全量测试日志 | `<SCRATCH>/mvn-full-after.log` |
| 定向测试日志 | 见 §6.1（同一包装脚本 `-Dtest=` 口径） |
| 打包日志（E2E/冒烟用 jar） | `<SCRATCH>/mvn-package.log` |
| CDP 冒烟日志 + 结果 JSON | `<SCRATCH>/chat-panel-smoke.log`、`<SCRATCH>/smoke-out/result-*.json` |
| 冒烟编排脚本 | `<SCRATCH>/run-smoke.sh` |
| E2E 日志 + 编排脚本 | `<SCRATCH>/e2e-run.log`、`<SCRATCH>/orchestrate-e2e.log`、`<SCRATCH>/run-e2e.sh` |
| 命名纪律自查脚本 | `<SCRATCH>/naming-check.mjs` |
