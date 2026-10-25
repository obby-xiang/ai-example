> 来源=<仓外证据>/ai-full-audit/M-adversarial.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# M｜对抗性独立复核（8 条指定结论）

> 对象：`K-synthesis.md` 中由 99 号分派、**未被其任务完整覆盖**的 8 条结论。
> 快照：`<仓外证据>/ai-full-audit/tree`（base `ae883fbae52be36e45a635ee276cf8298ac24bd2` + 未提交 `working.patch`）。
> 方式：只读复核。逐条**自己重读**实现、全部守卫、调用方与测试；不引用域报告结论作为证据；未启动服务、未跑测试、未修改任何源码/配置/工作流/Git 历史。唯一写入物是本文件。
> 判定只用三档：`CONFIRMED`（代码路径可证、触发条件明确）/ `PARTLY`（核心成立但结论中的某一段需要加条件或降级）/ `FALSE POSITIVE`（不成立）。
> 引用格式 `path:line`；行号为该快照实际行号（已逐条核对，不沿用域报告的行号）。

---

## 0. 结论表（先行）

| # | 结论（被复核的表述） | 判定 | 严重度（本复核） | 一句话理由 |
|---|---|---|---|---|
| A1 | 记忆键别名：`sessionId="{abc}"` 可读/删 `sessionId="abc"` 的真实记忆 | **CONFIRMED** | Critical（完整性+机密性） | `legacyKey(x)=prefix+x` 未转义，而 `key(y)=prefix+"{"+escaped(y)+"}"` ⇒ `legacyKey("{abc}")==key("abc")`；读回落、`saveAll` 清理 legacy、`DELETE /session` 三条路都命中受害者键 |
| A2 | 匿名 `GET /api/ai/runs` 枚举 + 敏感参数 | **CONFIRMED**（"匿名"半段属登记边界；"全局枚举含参数/结果"半段超出登记前提） | Important（单机 demo）→ Critical（多会话/内网多人） | 一次 GET 返回**全部**存活轮的 `sessionId/runId/status + pending.arguments + resultText`；`/runs/{runId}` 再加 `finalText` 与台账 `resultText`；这是"id 即主人"模型里唯一的秘密 |
| A3 | 改 baseUrl 保留旧 key，`/test` 把库中 key 发到攻击者主机 | **PARTLY** | Critical（公网外发）/ 条件（LAN 目标） | 保留旧 key 与 Bearer 外发两段**均已证**；但 `AiEndpointGuard` 在写路径与出站路径**都**拦私网/回环/保留地址 ⇒ "攻击者 **LAN** 主机"需 `allow-private-endpoints=true` 或 DNS 检查-连接 TOCTOU；默认配置下成立的是**任意外部公网主机** |
| A4 | park 中取消写 CANCELLED 但无终帧，重挂悬挂 | **CONFIRMED**（"悬挂"为客户端条件性） | Important | `ResumeService:689-698` 只写快照 + `cancelPendings`，**不产帧**；`SseChatEmitter` 收尾判据看归档有无终帧 ⇒ 重挂连接被保留、传输无死线（`-1`）；停止发起页靠前端本地收敛，故受害者是其它页签/客户端与归档契约 |
| A5 | 首轮从不取租约 ⇒ 常规轮全程无 fence，陈旧实例可覆盖新终态 | **CONFIRMED**（机制） | Important（条件风险） | `/chat` 只拿 `SessionGate`，`RunStore.save` 对 `NEVER_HELD` 走 plain SET、对"fence 已推进"只 WARN；`tryClaimExecution` 在 epoch=0 时**跳过** fence 复核；触发需"≥180s 无业务帧 + 另一次接管触发" |
| A6 | `executeOnce` 前无取消复查；`/cancel` 假成功 | **CONFIRMED**（两半） | Important | `awaitConfirm` 见 APPROVED 直接 `executeOnce`；续跑侧 `resolvePending` 对 APPROVED 同样直接执行，而 resume 的取消检查在重建历史/压缩**之前**、逐条结算**无复查** ⇒ 取消落在长压缩窗口即执行；`AiController:862` 无条件 `cancelled:true`（`redisFlagWritten=false`/`runExists=false` 也照写） |
| A7 | 零条目业务作业假 COMPLETED；IMPORT/PRECHECK 缺终态兜底 | **CONFIRMED** | Important（可升级 Critical：任务被锁死） | 四条 runner 都无"条目非空/步骤匹配"校验，0 条目 → 0 行 → COMPLETED 并联动任务 COMPLETED（EXPORT 还会让后续导出 409 TASK_FINALIZED）；`afterCompletion` 兜底只有 EXPORT 有实现 |
| A8 | 重试把 user 消息重复写进记忆并重复进请求 | **CONFIRMED**（机制） | Important | advisor 挂在 `ChatClient` 默认链上，每次 attempt 重建 prompt ⇒ `before` 每拍 `get+add` 一次；`fail()` 不收敛记忆 ⇒ 重复**持久可见**（成功/取消路径被整窗覆盖写掩盖） |

**总体**：8 条里 **7 条 CONFIRMED、1 条 PARTLY（A3）、0 条 FALSE POSITIVE**；其中 A2 需要把"匿名"与"全局枚举载荷"拆成两层，A4/A5 成立但"用户可见影响"的措辞必须收紧（否则容易被理解成"界面整轮卡死"）。没有一条被 `working.patch` 触及对应代码（见 §11）。

---

## 1. A1｜记忆键命名空间别名（CONFIRMED）

### 1.1 键形与碰撞（事实）
- `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:187-189`
  `key(id) = "chat:mem:{" + escapedId(id) + "}"`，`escapedId` 先 `%`→`%25`、再 `{`→`%7B`、`}`→`%7D`。
- 同文件 `:201-204` `legacyKey(id) = "chat:mem:" + id`，**原样、不转义**（注释自认"迁移期旧键形"）。
- ⇒ `legacyKey("{abc}") == "chat:mem:{abc}" == key("abc")`。等价地，`"{abc}"` 这个 session 的 *legacy* 名字正好是 `"abc"` 这个 session 的 *规范* 数据键。
- 反向也成立：任何 `id` 只要形如 `{X}`，其 legacy 键就吃掉名为 `X` 的会话。

### 1.2 三条命中路径（全部读过调用点）
1. **读（跨会话读）**：`findByConversationId` 在 tagged 键为空时回落 legacy（`:281-291`）。`GET /api/ai/history/{sessionId}` → `chatMemory.get(sessionId)`（`AiController:1251-1256`），`GET /context/{sessionId}` 同族。攻击者用 `"{abc}"` 取 `"abc"` 的整窗记忆。
2. **写一轮就删（跨会话删）**：`saveAll("{abc}")` 先 `RENAME` 到 tagged，然后 `deleteLegacyQuietly(legacyKey("{abc}"))`（`:314,338`）⇒ 删除 `"abc"` 的数据键。也就是说**不需要 DELETE 端点**：攻击者用这个 sid 正常发一轮对话（`/chat` 的轮末 `convergeMemory` 就会调用 `saveAll`），受害者的记忆就没了。
3. **显式删**：`deleteByConversationId`（`:350-354`）两条 DEL：`key(id)` + `legacyKey(id)`；`DELETE /api/ai/session/{sessionId}`（`AiSessionPurgeController:78`）→ `AiSessionPurgeService:719` → 该方法。攻击者 `DELETE .../session/%7Babc%7D` 直接抹掉 `"abc"` 的记忆。
4. 另加一条**放大**：攻击者那一轮的模型上下文来自 `chatMemory.get("{abc}")`（`ResilientChatService:363`）；快照 `full` = `[system] + memory + [user]`（`:412-420`，注释自认"快照里存的就是模型真正看到的那串消息"）⇒ 受害者的消息**进了模型输入、也进了攻击者这一轮的 `messageJson`**；随后 `convergeMemory` 把它整窗写入**攻击者自己的** tagged 键（`:1414-1440`）。即"读走 + 复制到自己名下 + 删掉原件"在一次正常对话内完成。

### 1.3 已检查的守卫（都不拦）
- `AiController:313`：`sessionId` 只校验 `StringUtils.hasText`，**没有形态/长度/字符集/熵校验**（`{` `}` 合法）。J 域把它登记为 ID-08，本复核确认它对 A1 是直接使能项。
- `AiSessionPurgeService:254` 的 javadoc 明写"会话 id 允许特殊字符，键名/扫描均做了精确化处理"——**该前提对 `{`/`}` 不成立**（只有 tagged 键做了转义）。
- `SessionPurgeGuard` 只挡"已销毁会话再写"，与别名无关。
- 产品前端生成的 id 是 UUID 或 `s-<ts>-<rand>`（`frontend/src/stores/ai.ts:202-211`），**永远不含花括号** ⇒ 正常用户不会自己踩到；只有**构造请求的客户端**能踩到。这不降低危害：接口无认证（见 §2），且 sid 由客户端在 `/chat` body 里自由给出。

### 1.4 触发前提
1. 能直接调 HTTP API（demo 无认证 ⇒ 同网段任意客户端；这也是 §3 要区分的边界）。
2. 知道受害者的 `sessionId`（A2 的 `/runs` 一条 GET 就给全；或从浏览器 sessionStorage/日志得到）。
3. 受害者会话有记忆数据（发生过至少一轮）。

### 1.5 用户影响
- 机密性：他人会话的完整对话（含工具结果）被读走，并作为"自己的历史"长期留在攻击者 sid 下。
- 完整性/可用性：受害者下一轮发现历史全没了（`/history` 空、模型失忆）；这是**静默数据销毁**，不是拒绝服务（无报错、无提示）。
- 取证：删除走的是受害者自己的键名，日志里表现为"受害者键被正常清理"，`legacy 键清理失败` 之类 WARN 都不会出现。

### 1.6 测试现状
- 转义测试只覆盖 **tagged × tagged** 不碰撞：`RedisChatMemoryRepositoryClusterSafetyTest:103-107`（`"{a}b"` vs `"%7Ba%7Db"`）。
- legacy 相关测试（`RedisChatMemoryRepositoryDeleteTest`、`...SaveAllEmptyTest`）用的 id 是 `s-1`，**不含花括号** ⇒ 别名路径零覆盖。
- 没有"legacy(x) 与 key(y) 冲突"的红用例；K 建议补，本复核同意（这是唯一能锁住该缺陷的形态）。

### 1.7 补丁状态
`working.patch` **不含** `RedisChatMemoryRepository.java`。不可假设已修。

### 1.8 与 K 的差异
K 的 S-02 表述（"读并删除"）准确；补充两点 K 没写：(a) **写一轮即删**（`saveAll` 的 legacy 清理），比"DELETE 才删"更易触发且更像正常使用；(b) 删除后**索引残留**（`removeFromIndexQuietly("{abc}")` 只移除假 id），受害者的 `abc` 索引成员留着但数据没了，`findConversationIds` 会把它当 stale 清掉——数据不可恢复。

---
## 2. A2｜匿名 `GET /api/ai/runs` 全局枚举（CONFIRMED，需拆两层）

### 2.1 事实
- 端点：`AiController:934-941`（`GET /api/ai/runs`，无任何鉴权/限流/参数），调用 `ResumeService.listRuns()`（`:293-360`）。
- 数据源：`RunStore.allRunIds()`（`:1170-1183`）读**全局** `RUN_INDEX` 集合（不是按 session 过滤）⇒ 返回系统内**所有**存活轮。
- 每条含：`runId`、`sessionId`、`status`、`round`、`messageCount`、`createdBy`、`resumedBy`、`pending`（**`PendingToolCall.asMap()` 全量**）、`ledgerCounts`、`cancelled`、`zombieRunning`、`resumeable`…
- `PendingToolCall.asMap()`（`:280-306`）逐字包含 `arguments`（模型给的原始 JSON 参数：配置值、行数据、taskId、表单值）、`resultText`、`reason`、`preflight`（`ToolPreflightService` 的影响面摘要）。
- 二次放大：`GET /runs/{runId}`（`AiController:943-948`）→ `ResumeService.describe`（`:378-398`）再给 `snapshot.asMap()`（含 `finalText`，即模型完整回答，`RunSnapshot:220-222`）、`inFlight`（同样 `asMap`）、`ledger`（`resultText`）。
- 无认证的根因：`backend/pom.xml` **无** `spring-boot-starter-security`（grep 零命中）；全仓唯一 Filter 是 `SecurityHeadersFilter`（只加响应头）；`AiController:115` 的 `@RequestMapping("/api/ai")` 下 19 个 mapping（另有 `AiConfigController` 13 个、`AiSessionPurgeController` 1 个、`AiKbController` 若干）共用同一无主体上下文。

### 2.2 为什么它"超出已登记前提"（关键区分）
DC-10 登记的是"**没有用户/角色/权限**"，且其安全边界是"demo 仅限内网/本地受信环境"（`docs/02-需求设计文档-v2.2-冻结版.md:23,28,33,143`）。在这个登记下，"任何内网客户端能调任何端点"**不是新缺陷**。

但 A2 的实质不是"没鉴权"，而是：
1. 系统把 `sessionId` 当作**唯一所有权凭证**（前端"会话即页签"、`409 SESSION_BUSY` 带 runId 让前端重挂、`/events/{runId}` 认 runId 即放行），其安全前提是"id 不可枚举"；
2. `/runs` 把这个凭证**成批公开发放**，还附送业务载荷（工具参数/结果/模型终稿）；
3. 于是 A1 从"需要猜 id"变成"一条 GET 拿全 id"，且任何后续补认证的设计（M2 计划里的 RBAC）都会继承"端点自身不做主体过滤"的坑——**它不是"缺认证"，而是"端点级无作用域"**。

### 2.3 触发前提 / 用户影响
- 前提：至少存在一个存活轮（RUNNING/SUSPENDED，或终态未过期）；无需 AI key（`resumeService.getIfAvailable()` 为 `@Service` 常温装配）。
- 影响：单机单人 demo 下是"运维面泄露"；一旦同网段有第二个人（DC-10 明确"内网受信"，但内网≠单人）就是**全量会话内容泄露**，且给 A1 提供弹药。规模无上限（`listRuns` 无分页、无 `limit`）。

### 2.4 测试现状 / 补丁
- 无任何针对 `/runs` 的越权/作用域用例（G-2 只做了可读性核对）。
- `working.patch` 不含 `AiController`/`ResumeService` ⇒ 未改。

### 2.5 判定细化
- "匿名"二字：**属登记边界**，不单独计缺陷。
- "全局枚举 + pending 参数 + finalText/ledger"：**超出边界，独立成立**。建议按 K 的分层口径写成"Important（单机）/ Critical（多会话）"，并且**修法应是"按 sid 过滤 + 去掉参数正文"**，而不是等认证。

---

## 3. A3｜改 baseUrl 保留旧 key，`/test` 外发（PARTLY）

### 3.1 已证的两段（核心）
1. **保留旧 key**：`AiConfigWriter.updateProvider` 的 key 三态（`:305-315`）：`clearKey=true` → 清空；`apiKey` 非空 → 重封；**否则 `key=null` ⇒ 不触碰 `keyCipher`**。这是**有意为之且有测试**：`AiConfigWriterTest:214-233` `updateWithoutApiKeyKeepsTheCipher`（同一用例里 baseUrl 就从 `api.deepseek.com` 改成了 `gw.example.com`）。
2. **外发**：`POST /api/ai/config/providers/{id}/test`（`AiConfigController:137-158`）→ `resolveKey(provider)` 解密库中 key → `probe.fetchModels(provider.getBaseUrl(), provider.getModelsPath(), key.apiKey(), trusted)`（`:153-155`）→ `AiConnectionProbe:125-151` 组 `Authorization: Bearer <明文 key>`。第二条同源外发口：`POST /providers/{id}/models/fetch` → `AiModelCatalog:65,81-82`（同 `fetchModels`）。
- 攻击序列（4 个请求，全部无认证）：`GET /providers` 拿 id → `PUT /providers/{id}` 只改 `baseUrl`（不带 `apiKey`）→ `POST /providers/{id}/test` → key 到目标主机；随后可再 `PUT` 改回，审计只记 `baseUrl` 前后值（`audit` 的 diff 不含密文），且**无主体字段**（DC-10 的"无主体流水"）。

### 3.2 为什么 LAN 目标只能算条件
`AiEndpointGuard`（`:64-113`）**两处都生效**：
- 写路径：`AiConfigWriter:294` → `checkBaseUrl` → `guard.checkForWrite`（`:899`），改 baseUrl 必过闸；
- 出站路径：`AiConnectionProbe:128` → `checkForOutbound`。
闸拒绝：非 http/https、内嵌凭据、端口越界、元数据主机名、**解析到 loopback/anyLocal/linkLocal/siteLocal/multicast/CGNAT/192.0.0.0-24/IPv6 ULA**（`:175-196`）。`AiConfigWriterGuardTest:94-104` 用 `192.168.1.10` 固化了"改私网仍被 400 BLOCKED_PRIVATE_ENDPOINT"。
所以"发到攻击者的 **LAN** 主机"需要下列之一：
- `app.ai.config.allow-private-endpoints=true`（`:139` 默认 false；文档口径是"给内网自建网关开"，运维一开就同时给这条路开了门）；
- **DNS TOCTOU**：`resolve()` 对解析失败的主机返回空数组并**放行**（`:165-173`，注释"不判脏值"），且"闸的解析"与"HttpClient 的连接解析"是两次独立查询 ⇒ 攻击者用 0-TTL/轮换应答把"检查时=公网、连接时=私网"错开（可重试 `/test` 撞时序）。这是真实但概率性的路径；
- 若部署本身无公网出口，则"公网主机"这条路也不通——外发能力取决于 egress 策略。

**默认配置下 100% 成立的是"任意外部公网主机"**：闸对公网 IP/域名一律放行（这是设计意图，不是 bug），而"保留旧 key"让攻击者不需要知道 key。

### 3.3 缓解项（已检查，不能掩盖缺陷）
- 重定向：`HttpRequest.newBuilder(uri)` 未显式设置策略，JDK 默认 `NEVER` ⇒ 不能用 302 绕过闸（但仍可用"自己就是目标"）。
- 超时/字节上限（`AiConnectionProbe` 的 `ofByteArray` + `boundedBody`）只防大响应，不防外发。
- `GET /providers` 只回 `keyLast4`（不回首密文）——但本攻击不需要密文，只需要"服务端替我发"。

### 3.4 用户影响
服务端保管的模型 API Key（计费凭证）被复制到攻击者主机；可长期盗刷、可伪造上游响应（进一步污染对话/工具结果）。**这直接违反文档口径"API Key 仅服务端环境变量注入，前端不持有任何模型凭证"**——虽然现在 key 存在 DB，但"key 不出服务端"这条边界仍在文档里（`docs/02-...v2.2...:143`）。

### 3.5 测试现状 / 补丁
- 有"私网被拦"（`AiConnectionProbeTest:219-229`、`AiConfigWriterGuardTest:94-104`）与"保留 key"（`AiConfigWriterTest:214-233`）两个半边，**没有**"改 host 后 /test 把旧 key 发到新 host"的联动用例——即两个正确行为拼起来产生缺陷的用例缺失。
- `working.patch` 不含 `AiConfigController/AiConfigWriter/AiConnectionProbe/AiEndpointGuard` ⇒ 未改。

### 3.6 判定
`PARTLY`：核心机制 CONFIRMED；结论里"attacker **LAN** host"必须加条件（默认拦私网），应改写为"任意外部主机（默认）/ LAN 主机（allow-private-endpoints 或 DNS TOCTOU）"。修法建议（不在本报告范围，仅指方向）：改 host 时强制重录 key，或 `/test` 只用"当前 host 与保存时一致"的 key，并对出站做"连接时再校验一次解析结果"。

---
## 4. A4｜park 中取消：写 CANCELLED、无终帧、重挂悬挂（CONFIRMED，"悬挂"有条件）

### 4.1 代码链（逐跳）
1. `POST /api/ai/cancel/{runId}`（`AiController:855-883`）：先写 Redis 取消标志；若 `park.enabled` 且快照 `SUSPENDED` → `confirmGate.triggerResumeForParked(runId)`（`:873-878`）。
2. `ConfirmGate.triggerResumeForParked` → `triggerResume(runId,false,true)`（`:1606-1658`）：只对 `SUSPENDED` 派发；抢续跑租约后**异步**提交到 `resumeTriggerExecutor`（`:1660-1680`）。
3. `ResumeService.resumeInternal` 的取消优先分支（`:681-698`）：`snapshot.setStatus(CANCELLED)`、`setCancelled(true)`、`store.save`、`store.cancelPendings`，然后 `return ALREADY_CANCELLED`。
   **此处没有任何 `out.done(...)` / `out.error(...)` / `confirmDecision(cancel)` / `frontendToolResult`。**（对照：运行中取消走 `ResilientChatService.cancelTerminal`，那里既补 `confirm_decision(cancel)` 又发 `done{cancelled:true}`，`:1249-1310`。）
4. 因此该 run 的帧归档永远缺 `done`/`error`。

### 4.2 重挂为何悬挂（服务端事实）
- `GET /events/{runId}`（`AiController:740-779`）：`SseEmitter` 用 `NO_TRANSPORT_DEADLINE = -1`（`:1911`）；`replayAndAttach(..., isTerminal(status)=true)`。
- `SseChatEmitter.replayAndAttachWithStatus`（`:666-714`）的收尾判据是**归档里有没有终帧**（L-143 注释明写"不是快照是否终态"）：无终帧且本 emitter 未 `terminating` ⇒ `waitForLiveTerminalFrame=true` ⇒ **保留订阅等一个永远不会来的实时终帧**。没有服务端定时器（注释里把兜底明确甩给"前端看门狗"）。
- 前端：`frontend/src/api/sse.ts:34` 45s 帧间静默 + 4 次重连（`DEFAULT_MAX_RECONNECTS`），之后按断流处理；`frontend/src/stores/ai.ts:2533-2555` 在 `terminal=false && !stopped` 时置 `streamInterrupted` + "结果可能不完整"，且**保留**待决卡（注释：只认终帧/取消才收口）。
- 停止**发起页**不会受害：`ai.ts` 的 `stop()`（`:4341-4400`）先本地把段收敛成 cancelled、写镜像（含 done）、`stopped=true`，然后才 POST cancel；`onClose` 里 `this.stopped` 抑制断流提示。所以受害面是：另一个页签（多页签受支持）、刷新后无镜像可用的客户端、以及任何非浏览器消费者（CI/E2E/运维脚本）。

### 4.3 更糟的两个分支（K 未写）
- **派发可能根本没发生**：租约在别处（`LEASE_HELD_ELSEWHERE`）或触发池饱和（`EXECUTOR_SATURATED`）时 `parkedResume=skipped`（`:1649-1655,1676-1680`），而端点仍返回 `cancelled:true`。此时快照留在 `SUSPENDED`、条目仍 `PENDING`、取消标志只在 Redis 里"待生效"，**没有终帧也没有收敛**，要等下一次有人决策/重挂/重启扫描。
- **取消与探活竞态**：`cancel` 是异步的，所以响应体里的 `statusBeforeCancel` 仍是 `SUSPENDED`；客户端若在收到 200 后立刻 `GET /events`，会命中"仍 SUSPENDED 且仍有 PENDING" ⇒ `completeSubscriptions()` 主动收流（`AiController:766-768`）⇒ 客户端看到"正常收尾"（`info.suspended`），卡面继续挂着，随后才被异步写成 CANCELLED（无帧）。刷新后 `clearSessionCurrent` 已清索引 ⇒ `/runs/current` `found:false`，卡直接消失，用户不知道这轮是被取消的还是丢了。

### 4.4 "归档违反恰好一个终帧"——比 K 说的更弱
`FrameContract.terminalContract`（`backend/src/test/java/com/example/configmgr/ai/conformance/FrameContract.java:166-190`）只检查：首帧是 `start`、**终帧不多于一个**、终帧后无帧。**终帧为零不报错**（`terminals.isEmpty()` 直接跳过）。也就是说"恰好一个"只写在 javadoc 里，**校验器本身不执行这条**；A4 这类"零终帧"不会被任何现有 conformance 用例抓到。

### 4.5 测试现状 / 补丁
- `HumanWaitParkTest:230-243` 明确断言 park 期归档 `doesNotContain("done","error")`（把"没有终帧"固化成了期望）；`stopAfterParkTriggersResumeSoCancellationConverges`（`:250-273`）只断言"派发了 resume"，**不断言终帧/CANCELLED 帧**。
- `CancelDecisionFramesTest` 测的是 `emitCancelOutcomes`（运行中取消的补帧），不覆盖 resume 侧取消。
- `working.patch` 改了 `SseChatEmitter`，但只改 `wakeFloor`/去重语义（见 patch 段），**没有**"终态快照无终帧时合成 done"。`ResumeService` 未改。⇒ 不可假设已修。

### 4.6 判定
`CONFIRMED`（无终帧 + 服务端保留连接 + 无传输死线）。"整轮挂死"应改写为"**重挂连接被挂住直到客户端自己的 45s×n 看门狗；停止发起页本地已收敛**"，否则会被读成"用户界面一直转圈"。

---

## 5. A5｜首轮无租约 ⇒ 全程无 fence（CONFIRMED，机制）

### 5.1 取租点全表（`grep -rn acquireRunLease`）
只有 3 处生产调用：
1. `ResumeService.resumeWithReason:530`（所有续跑入口：启动扫描、运维 `/resume`、决策/回灌触发的 `triggerResume`、死卡兜底）；
2. `ConfirmGate.triggerResume:1640`（`acquireFenced`，死卡/决策派发路径）；
3. `RunStore:1077`（`tryAcquireRunLease` 便捷口，供上述使用）。
**`/chat` 不在其中**：`AiController.chat`（`:299-488`）只 `sessionGate.acquire` + `runRegistry.of` + 提交线程池，没有任何租约动作。

### 5.2 无租约时的写权限（关键）
- `RunStore.save`（`:690-726`）：`authority = runLeaseAuthority(runId)`；`LeaseService.authority`（`:797-813`）是**纯进程内视图**：`ended(LOST/UNAVAILABLE)` → 只读；`held` 有 → HELD；**否则 → `facilityHealthy() ? NEVER_HELD`**。一个"从未参与这一轮"的实例（哪怕别人正持租、fence 已推进）在这里得到的就是 `NEVER_HELD`。
- `NEVER_HELD` 分支：`warnIfFenceIsAhead`（`:764-789`）**只 WARN**（且按 runId 节流），然后 `redis.opsForValue().set(...)` —— **plain SET，覆盖任何主人的快照**。
- 对比：`epoch>0` 走 `fencedSet`（Lua：`fence<=epoch` 才写），陈旧写被拒（`:712-716`）。
- 帧落档同样只看 authority（`SseChatEmitter:1425-1440`、`RunStore.appendEvent:2128-2135`）⇒ 陈旧实例**还能往归档里追帧**，包括第二个 `done`。
- `tryClaimExecution`（`:1688-1705`）：`epoch>0` 才查 `fenceEpoch > epoch`；`epoch==0` 时**整段 fence 复核被跳过**，直接 `HSETNX ai:claim` 并执行。

### 5.3 触发前提（这是最关键的限制）
需要同时满足：
1. 存在一个"epoch=0 的写者"——首轮 `/chat` 线程；且它还**活着**（线程未返回、未被 kill）；
2. 另一实例/重启后的同一实例把它判为僵尸并**接管**：`StartupResumeRunner` 只在 `ApplicationReadyEvent` 扫一次（`:74-104`），或人工 `POST /api/ai/startup-resume/scan`（`AiController:918-932`，无认证）；判据 `RUNNING && 无未决外部输入 && (now - max(updatedAtMs, 末条非心跳业务帧)) > 180s`（`application.yml:243`；`ResumeService.listRuns:326-345`）；
3. 接管方走完续跑（拿到 lease epoch≥1，fence 推进），之后**原实例才落笔**。

现实成因：进程被暂停（长 GC / VM 暂停 / 调试）、实例与 Redis 之间分区（业务帧落不了档 ⇒ 业务活动锚点停），分区恢复后原线程继续跑并落笔。**不是"随便两个实例同时跑同一会话"就会发生**：这不满足 180s 静默条件，因为 `/chat` 有 `SessionGate` 互斥、且流式有业务帧。

### 5.4 用户影响
- 快照被旧实例覆盖（状态、`messageJson`、`finalText` 回退），任务/会话索引被旧实例 `clearSessionCurrent`/`markSessionCurrent` 再改一次；
- 归档出现**第二个 `done`/`error`**（违反"至多一个终帧"，`FrameContract` 会红），前端 `mergeBySeq` 之后出现两段收尾；
- 记忆随后被 `convergeMemory` 以旧实例的历史整窗覆盖（丢新主人生成的工具/正文）；
- 只有一条 WARN（`无租约写回 runId=… fence 已推进到 epoch=N`），无拒绝、无回滚。

### 5.5 测试现状 / 补丁
- 有 `LeaseLossReadOnlyTest`、`LeaseServiceTriStateTest`、`LeaseEpochPerRunTest` 覆盖**持租者丢租**后只读，但**没有**"从未持租的写者 vs 已推进的 fence"用例（即 `NEVER_HELD` 与 fence 的交互），也没有"首轮无租约 + 接管后旧写者落笔"的用例。
- `working.patch` 不含 `AiController`/`RunStore`/`LeaseService`/`StartupResumeRunner` ⇒ 未改；K §10 也自认"首轮取租约/fence 未改"。

### 5.6 判定
`CONFIRMED`（机制、代码可证），但严重度按"条件风险"更诚实：需要一次 ≥180s 的静默 + 一次接管触发。修法方向（不在本案范围）：首轮也取 run 租约（或在 `save`/`appendEvent` 的 plain 分支加"fence>0 ⇒ 拒写"），并把"只 WARN"改成拒写。

---
## 6. A6｜`executeOnce` 前无取消复查 + `/cancel` 假成功（CONFIRMED，两半）

### 6.1 缺复查：证据与可达性
- `SpToolCallingManager.awaitConfirm`（`:1320-1328`）：
  `resolved = gate.awaitDecision(...)` → `if (APPROVED) return executeOnce(...)`，中间**没有任何** `checkCancelled`。
- 现有取消检查只有两处：执行前（循环顶部 `:515`，**在进入 await 之前**）与执行后（`:607`）。`executeOnce`/`tryClaimExecution` **都不看取消标志**（`RunStore:1688-1705` 只查租约/fence，不查 `ai:cancel`）。
- 阻塞路径（`park.enabled=false`，或单测/非 Spring 装配的默认语义）：**窗口很窄，不构成"确定性缺陷"**。`ConfirmGate.await` 的循环每 2s（`HEARTBEAT_SECONDS=2`，`:129`）在**顶部**读取消标志（`:639-645`），读条目在同一迭代的后面；等待期间到达的取消会被下一次顶部复查拦下（本进程取消还会经 `onCancel` 监听唤醒 latch，立刻回顶部复查）。真正的窗口只剩"顶部读取消"到"读到 APPROVED 并 return"之间的毫秒级 TOCTOU（含一次 `pending` 的 Redis 读与 `handOffLease`）。**这一半按 Minor 计，不按 Important 计。**
- 续跑路径（生产 `park=true` 下的真正通路）：**窗口宽得多，这才是实质危害点**。`resumeInternal` 只在**开头**查一次取消（`:681-698`），随后重建历史、`MemoryWindow` 规划、以及 N 次串行压缩（每次 ≤60s，`ResumeService:884-960`；R1-09 注释自认长压缩期无业务帧）——这段期间**没有任何取消复查**；接着逐条 `resolvePending` → 对 `APPROVED`（及 `PENDING+BACKEND`）直接 `executeOnce`（`SpToolCallingManager:1816-1830`），**逐条也不复查取消**（对照首轮 `executeToolCalls` 循环在 `:515` 有逐条检查）。因此"用户批准 → resume 已过取消检查 → 用户在重建/压缩期间点停止"会得到 `cancelled:true`，然后该 DANGER 工具照常执行（import/publish 的不可逆写）。

### 6.2 `/cancel` 假成功
- `AiController:855-883`：`body.put("cancelled", true)` **无条件**（`:862`），与 `result.redisWritten()` / `snapshot==null` 无关；`redisFlagWritten`/`runExists`/`degraded` 只是**附带字段**，前端 `stop()` 只读 `alreadyCancelled`（`ai.ts:4390-4398`），从不看 `redisFlagWritten`。
- `CancellationRegistry.cancel`（`:147-154`）：`writeRedisFlag` 失败时 `redisWritten=false`，但本地 `markLocal` 已置位 ⇒ 同一进程内的看门狗/gate 可能仍看到"已取消"（内存态），跨实例则**完全无效**。
- 叠加读侧：`ConfirmGate` 对 `UNKNOWN`（Redis 读失败/退避）**继续等待**（`:643-651`），不按取消收口；`SpToolCallingManager.checkCancelled` 对 `UNKNOWN` 抛 `STATE_UNAVAILABLE`（fail-closed）。即：Redis 抖动期"停止"→ 200 cancelled:true → 卡片继续等、工具在下一个边界才终止；若这轮正卡在 `await` 且没有新的工具边界，就一直等。

### 6.3 用户影响
用户看到"已停止"（本地收敛），后端继续执行 `start_import`/`start_publish`/`start_export`（真正写库/写文件），或继续等待人工。事后台账/审计里这轮显示"已取消 + 工具已执行"，与用户认知相反。对不可逆业务写，这是**数据后果**，不只是提示问题。

### 6.4 测试现状 / 补丁
- `grep` 全部 `gate/*Test`：无"批准后取消 → 不得执行"用例；`ConfirmGateLeaseTest:452-484` 测的是"取消时 gate 自己收敛条目"，不是"已批准条目在取消后是否执行"。
- `working.patch` 改了 `SpToolCallingManager` 与 `ConfirmGate`，但**只是把 park 批次的 `tool_start` 前移到 request 之前**（新增 `emitToolStartOnce`、`publishesRequestsAheadOfLoop`）——不涉及取消复查、不涉及 `/cancel` 响应体。⇒ 未修。

### 6.5 判定
`CONFIRMED`。两半都独立成立：即使把 `/cancel` 的假成功修好，"已批准 → 取消 → 仍执行"仍然存在，且**主危害在续跑路径的宽窗口**（resume 的取消检查在重建/压缩之前 + 逐条结算无复查），不在阻塞路径的毫秒级 TOCTOU。

---

## 7. A7｜零条目假 COMPLETED + IMPORT/PRECHECK 缺终态兜底（CONFIRMED）

### 7.1 没有任何"可执行性"校验（守卫全表）
`JobService.createAndStart` 的守卫段（`:156-165`）只有一条行锁 + 四道守卫：
- `taskService.lockForUpdate`（行锁）；
- `assertTaskNotFinalized`（`finalizedConflict`：CANCELLED 恒拒；COMPLETED 且已有成功终作业才拒；`ACTIVE/FAILED` 放行，`:91-113`）；
- `assertNoJobInFlight`（同任务同类型互斥）；
- `assertNoRunnerInFlight`（共享 runner 存活）；
- `assertPrecheckPassed`（只对 IMPORT/PUBLISH：最近一次 PRECHECK 必须 `COMPLETED && errorCount==0`，`:354-374`）。
**没有**：条目非空、任务步骤与 jobType 匹配、任务类型与 jobType 匹配、上传文件存在、暂存行非空。
入口：`JobController:26-32`（`POST /tasks/{taskId}/jobs`，`Job.JobType.valueOf(body.jobType)` **任意四类**）；AI 侧 `AiTools:319-374`（`start_export/precheck/import/publish`，`@ToolScope` 只是"披露面"，不做可执行性校验，注释自认 S44d 的退化）。

### 7.2 四条链的 0 条目终局
- `ExportJobRunner`（`:112-121`）：`items = findByTaskIdOrderBySortOrder`；空 → 循环不执行 → `estimatedRows=0`、`totalRows=denominator(0,0,0)=0`（`JobProgressTotals:36-39`）→ `status=COMPLETED`（`:281-292`）→ `terminalWriter.complete(..., successStep="EXPORT")` → `writeCompletionStep(task,"EXPORT")` + `applyJobOutcome(COMPLETED)`（`JobTerminalWriter:103-146`；`TaskService:319-343`）⇒ **任务变 COMPLETED 但没有任何 `task_files` 行**（文件只在有 item 时才写）。
  **连带伤害**：此后对同一任务再发 EXPORT 会被 `finalizedConflict` 判 `409 TASK_FINALIZED`（"已有成功 EXPORT 作业"）⇒ 任务被"假成功后锁死"，用户只能新建任务。
- `ImportJobRunner`（`:60-61,191-197`）：同样 `sorted` 为空 → `COMPLETED`、`errorCount=0`。
- `PrecheckJobRunner`（`:65,272-280`）：`sorted` 为空 → `COMPLETED`、`totalErrors=0` ⇒ **"零条目预检查"被 `assertPrecheckPassed` 认作"已通过"**，从而**解锁** IMPORT/PUBLISH（这是"零数据全链"的关键一环）。
- `PublishJobRunner`（`:88-105,111-130`）：`items` 为空 → 无 upsert、无差集删除 → `totalErrors=0` → `COMPLETED` + `successStep="PUBLISH"` ⇒ 任务 COMPLETED，生产数据一行未动。
⇒ 完整链：建空任务 → PRECHECK（COMPLETED/0 错）→ IMPORT（COMPLETED/0 行）→ PUBLISH（COMPLETED/0 行）→ 任务 COMPLETED。四步全部"成功"，四步全部零数据。AI 侧还会对用户说"作业已启动…请稍后查看进度"（`AiTools:325,344,361,373`）。

### 7.3 缺终态兜底（IMPORT/PRECHECK）
- 失败面：`run()` 是 `@Transactional`（`ImportJobRunner:60`、`PrecheckJobRunner:65`）；`markRunning` 走 `REQUIRES_NEW` 并**立即提交**（`JobProgressService:89-96`），条目 `startItem` 同样独立提交。若**提交期**失败（延迟约束、连接在 commit 断、参与者回滚），异常从 `run()` 抛出 → `AsyncJobExecutor.execute` 只 `log.error`（`AsyncJobExecutor:27-45`）→ 作业停在 `RUNNING`、条目停在 `RUNNING`。
- 兜底：`JobWatchdog` 的**绝对上限**与心跳无关（`:193-207`，`max-execution-time: 30m`，`application.yml:454`）⇒ 最长 30 分钟后写 FAILED（且补丁后在 `finish` 里顺带把条目收敛 FAILED）。
- 对照：**只有 EXPORT** 注册了 `afterCompletion` 回滚收敛（`ExportJobRunner:84-101`，非提交即 `completeNow(FAILED)`）；`grep registerSynchronization` 在 `ImportJobRunner`/`PrecheckJobRunner`/`PublishJobRunner` 中**零命中**。K 的 S-12 说"EXPORT 侧已修、此两处漏修"，本复核**确认**（PUBLISH 靠 `transactionTemplate + catch → recordFailure` 自带终态，属于另一种写法，不算漏）。

### 7.4 用户影响
- 零条目：任务显示"已完成"、没有可下载文件/没有发布任何数据；EXPORT 还让任务永久不可再导出（409）。对 AI 驱动场景，用户会以为"已经导好了"。
- 提交期失败：作业在 30 分钟内一直"运行中"（进度条可能已满），期间删除/重跑被 runner/互斥守卫挡住；30 分钟后变 FAILED，但已浪费半小时且用户没有任何提示。

### 7.5 测试现状 / 补丁
- 有 `ExportFlowJobTest`、`ImportFlowJobTest`、`PrecheckGateGuardTest`、`TaskFinalizedGuardTest` 等，**没有**"0 条目 → 应为 400/FAILED"的用例（`ImportFlowJobTest:323` 的 `emptyUploadProducesNoError` 是"空文件内容"，不是"无条目"）。
- `working.patch` 确实改了作业面（`JobService` 取消时 `convergeOpenItems`、`JobProgressService.finish` FAILED 收敛、`JobItemRepository` 条件 UPDATE），但**只是"条目状态收敛"**，与"可执行性校验"和"IMPORT/PRECHECK 回滚兜底"无关；`grep` patch 无 `ImportJobRunner`/`PrecheckJobRunner`/`JobWatchdog` 改动 ⇒ **A7 不可假设被修**。

### 7.6 判定
`CONFIRMED`。建议严重度按"任务被锁死 + 用户误判交付完成"计 Critical（业务面），至少 Important。

---
## 8. A8｜重试重复写记忆并重复进请求（CONFIRMED，机制）

### 8.1 机制
- advisor 装配：`AiModelConfig:77-81` `MessageChatMemoryAdvisor.builder(chatMemory).build()`；`AiProviderRegistry` 在**每次**构建 runtime 时把它挂成 `ChatClient` 的 `defaultAdvisors`（`:308,341,415`）。用的是官方 1.1.7 实现（`pom.xml:22,149-160` 冻结版本），其 `before()` 语义是"读记忆 → 把本次 prompt 的指令（user 消息）**追加写回记忆** → 用'记忆+指令'重写 prompt"。
- 重试循环：`ResilientChatService.runAttempts`（`:683-803`）每次 attempt 都重新执行 `attemptStream.apply(attempt)`，而该 lambda（`:458-471`）是 `runtime.chatClient().prompt()...stream()` ⇒ **每次 attempt 都会再走一遍 advisor.before()**。
- 默认 `max-attempts: 2`（`application.yml:229`；`AiProperties:777`），且重试条件只要求"本 attempt 未产出正文 + 无副作用 + 未超预算"（`:754-762`）⇒ 首包 5xx/超时/早断这类**最常见**的瞬时故障正好满足。
- 后果：第 N 次 attempt 发往模型的 prompt 里，同一条 user 消息出现 **N 次**（记忆里 N-1 条 + 本次 1 条）；Redis 记忆里也累积 N 条。

### 8.2 持久化差异（必须写清，否则会误判严重度）
- **成功**：`finish` → `convergeMemory`（`:1006`）以快照 `messageJson` **整窗覆盖**（`RedisChatMemoryRepository.saveAll` = staging + RENAME），而快照历史是轮初 `chatMemory.get(sessionId)`（`:363`）的快照 + 本轮消息 ⇒ **不含** advisor 的重复追加 ⇒ 重复被覆盖掉。
- **取消**：`cancelTerminal` 同样整窗收敛（`:1280`）⇒ 覆盖掉。
- **失败**：`fail()`（`:1378-1403`）只写快照 FAILED + `error` 帧，**不调用 `convergeMemory`** ⇒ 重复**留在 Redis 记忆里**；下一轮 `chatMemory.get` 会带上它，`GET /history` 也会显示用户"说过两遍"。
- **park 中断**：`parkTurn` 不收敛，重复在挂起期可见；续跑成功后收敛覆盖。
⇒ K 的 MD-01 表述（"重试把 user 消息重复写进记忆并重复进请求；`fail()` 不收敛 ⇒ 重复持久可见"）**准确**。需要补的是"成功/取消路径会掩盖，所以只能靠失败轮或请求抓包观察"。

### 8.3 测试现状（结构性盲区）
- `ResilientChatServiceMemoryWriteTest` 的核心断言恰好锁死了"服务自己**不**追加"：`normalFinishWritesTheWholeWindowWithOneSaveAllAndNeverClearsChatMemory`（`:141`）——它注入 `mock(ChatMemory)`，**advisor 不在回路里**；服务级用例普遍如此（K 的 S-11 说"全部 mock 掉 advisor"，本复核确认为真）。
- 没有"attempt=2 时上游收到的 messages 序列"的断言（`ChatModelContractTest`/`ReasoningReplayRequestTest` 只测请求形态的个别字段）。
- `working.patch` 不含 `AiModelConfig`/`ResilientChatService`/`AiProviderRegistry` ⇒ 未修。

### 8.4 判定
`CONFIRMED`（机制）。用户影响：重试轮里模型看到重复提问（可能答非所问/重复执行意图），失败轮的历史里用户消息重复（刷新后可见），并挤占记忆窗口（40 条上限下重复条目会挤掉真实历史）。

---

## 9. 无认证/demo 边界 vs 具体缺陷（这条必须分清，否则会把 8 条全打成"已登记"）

**已登记（不应重复计缺陷）**：无用户/角色/权限、无登录、审计无主体。依据 `docs/02-需求设计文档-v2.2-冻结版.md:23,28,33,143`（DC-10）+ K §5 的 X-1 分层。推论：**"谁能调这个端点"本身不是缺陷**；"内网受信环境"里任何客户端能调 `/api/**` 是产品裁决。

**超出登记前提、必须按具体缺陷计的四类**（本复核 8 条落在其中 3 类）：

| 类型 | 为什么"不是没认证" | 涉及 |
|---|---|---|
| 键命名空间别名 | 即使明天加了认证/归属校验，`legacyKey` 仍会把 A 的 id 映射到 B 的数据键；这是**存储键设计**错误，认证修不了 | A1 |
| 端点级无作用域 + 泄露业务载荷 | 登记的是"没有主体"，不是"端点可以返回所有人的 `pending.arguments`/`finalText`"；且它**发放所有权凭证**，直接推翻"id 不可猜"这个被多处依赖的前提 | A2 |
| 密钥外发 | 文档明文"API Key 仅服务端注入/前端不持有"，且"key 不出服务端"是密钥管理的底线；写路径保留旧 key + 探活外发是**密钥处理缺陷**，与有没有登录无关（M2 加 RBAC 后，一个只有"设置页"权限的普通用户仍可盗走系统 key） | A3 |
| （同类，未在本 8 条内）反馈 IDOR、`/providers` 暴露 baseUrl/keyLast4 | 同上：数据作用域缺失，不是"缺登录" | 参见 K ID-07/ID-15 |

**因此**：A1/A2(载荷段)/A3(公网外发) 在任何"demo 也要修"的清单里；A2 的"匿名"字样、A4/A5/A6/A7/A8 与认证无关（纯并发/协议/业务校验缺陷）。

---

## 10. 对 K 的 4 处修正建议（对抗性输出）

1. **S-04 的目标限定**：把"任意主机"改成"**任意外部主机（默认配置可证）**；LAN 主机需 `allow-private-endpoints=true` 或 DNS TOCTOU"。否则会被"闸明明拦私网"一句话反驳（`AiConfigWriterGuardTest:94-104` 就是反例）。
2. **S-05 的后果措辞**：不是"整轮挂死"，而是"**归档零终帧 + 重挂连接被服务端保留至客户端看门狗**；停止发起页靠本地收敛，受害者是其它页签/刷新后无镜像的客户端/非浏览器消费者"。另外补：派发可能 `skipped`（`LEASE_HELD_ELSEWHERE`/`EXECUTOR_SATURATED`），此时连"快照转 CANCELLED"都不保证。
3. **S-05 的"违反恰好一个终帧"**：`FrameContract.terminalContract` 只检查"至多一个 + 在最后"，**零终帧不报错**（`FrameContract.java:166-190`）⇒ 现有 conformance 体系抓不到此类；这条应同时记为"**校验器缺口**"。
4. **S-09 的连带伤害**：补一条"零条目 EXPORT 会把任务写成 COMPLETED，从而被 `TASK_FINALIZED` 永久锁死"（`JobService:104-112` + `ExportJobRunner:290`）。这比"零数据"更严重，也更难被用户察觉。

---

## 11. 补丁覆盖核对（为什么 8 条都不能假设"已修"）

`working.patch` 改动文件（全部）：`ConfirmGate`、`SpToolCallingManager`、`SseChatEmitter`、`ReadinessProperties`、`ReadinessService`、`JobItemRepository`、`JobProgressService`、`JobService`、`FileController`、`application*.yml`、`parts.ts` + 6 个新测试。

| 结论 | 相关文件是否在 patch 内 | 实际改了什么 | 能否假设已修 |
|---|---|---|---|
| A1 | 否（`RedisChatMemoryRepository` 未动） | — | 不能 |
| A2 | 否（`AiController`/`ResumeService` 未动） | — | 不能 |
| A3 | 否（`AiConfig*`/`AiEndpointGuard` 未动） | — | 不能 |
| A4 | 部分（`SseChatEmitter` 有改；`ResumeService` 未动） | 只改 `wakeFloor`/去重下界 | 不能（无终帧合成） |
| A5 | 否（`RunStore`/`LeaseService`/`AiController` 未动） | — | 不能 |
| A6 | 部分（`SpToolCallingManager`/`ConfirmGate` 有改） | 只改 `tool_start` 前移与去重（`emitToolStartOnce`、`publishesRequestsAheadOfLoop`） | 不能（无取消复查、`/cancel` 响应体未动） |
| A7 | 部分（`JobService`/`JobProgressService`/`JobItemRepository` 有改） | 只改"取消/FAILED 时条目收敛" | 不能（无 0 条目校验、无 IMPORT/PRECHECK 回滚兜底） |
| A8 | 否（`AiModelConfig`/`ResilientChatService`/`AiProviderRegistry` 未动） | — | 不能 |

此外：新加的 6 个测试**没有一个**覆盖上述任何一条（`ParkToolStartOrderConformanceTest` 管帧序、`SseChatEmitterWakeFloorTest` 管算法、`JobWatchdogItemConvergenceTest` 管条目收敛、`FileDownloadByteIdentityTest`/`ReadinessDrainWindowTest`/`park-request-tool-start-order.spec.ts` 各自无关）。"补丁零 CI 验证"这一点本报告不做独立断言（属 J 域），但即使它全绿，也**不能**推出 A1–A8 已修。

---

## 12. 残余不确定性（如实列出）

1. **A1**：未在真 Redis 上跑过 `legacyKey("{abc}")` 与 `key("abc")` 的键相等性（本报告是纯读码推导 + 现有测试的键形对照）。`MessageWindowChatMemory` 的 40 条裁剪会让"读走的内容"少于全窗，但 40 条足以覆盖整轮上下文。
2. **A2**：`/runs` 的实际返回规模取决于 `RUN_INDEX` 的清理时机（`allRunIds` 会顺手清 dead id，`:1175-1190`），未实测大数量下的响应体大小。
3. **A3**：未复现 DNS TOCTOU（需要可控 DNS 与两次解析时序）；`allow-private-endpoints=true` 只是配置推演。JDK HttpClient 的重定向默认值按 JDK 规范（`NEVER`）推导，本仓未显式设置。
4. **A4**：`parkedResume=skipped` 的两种成因（租约在别处/池饱和）是代码路径，未在真三实例复现；"另一个页签"是否会自动重挂取决于前端交互路径（`resumeSnapshotStream`/409 reattach），未逐条走 UI。
5. **A5**：未构造"暂停 > 180s + 接管 + 旧写者落笔"的真拓扑；`touchBusinessActivity` 的写入点覆盖度（哪些帧推进业务锚点）只做了抽样核对（`RunStore.appendEvent:2121-2145`）。
6. **A6**：续跑路径的宽窗口是代码顺序的直接推论（取消检查在 `:681-698`，逐条结算在 `:842`，两者之间无复查），但未写用例实测；窗口长度取决于 `ContextCompactor` 是否真的串行多次摘要（`ResumeService:927-935` 注释自认 N 次、每次 ≤60s）与历史/记忆窗口的规模。阻塞路径那一半已在本报告内降级为 Minor。
7. **A7**：0 条目任务能否被 UI 正常创建/进入"执行"步未实测（本报告只证后端无校验）；`PublishJobRunner` 的差集删除在 0 条目下"什么都不删"是代码推论。
8. **A8**：未抓真实上游请求验证"第 2 次 attempt 的 messages 里有两条相同 user"（需真模型/桩 + 框架 advisor 实测）；结论基于 Spring AI 1.1.7 `MessageChatMemoryAdvisor` 的既定语义 + 本仓装配方式。

以上 8 条中若有一条被后续拓扑/真机证据推翻，最可能的顺序是 **A5（触发条件太苛刻）→ A3 的 LAN 段 → A4 的"用户可见"段**；A1/A2/A7/A8 是纯代码路径，A6 的路径也由代码顺序决定（只有窗口长度依赖压缩次数/历史规模），这五条除非实现被改，否则不依赖拓扑。
