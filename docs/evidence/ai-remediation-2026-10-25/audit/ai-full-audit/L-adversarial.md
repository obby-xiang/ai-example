> 来源=<仓外证据>/ai-full-audit/L-adversarial.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# L｜对抗性复核（只读验收）

- 复核对象：`<仓外证据>/ai-full-audit/tree`（`ae883fbae52be36e45a635ee276cf8298ac24bd2` + 审计开始时的未提交补丁 `working.patch`）；行号一律指该快照。
- 复核方式：只读。未改产品代码/测试/配置，未跑 mvn/vitest/Playwright，未起服务，未改 CI。
- 外部证据：用 `gh run view`（只读）核验了 3 个 run 的真实日志，未以报告转述为准。
- **I-frontend-workspace.md 不存在**：目录内只有 A/B/C/D/E/F/G/H/J 九份。本报告对"前端工作区"的复核只能落到 H（对话面板）与 A/H 中与工作区状态相关的断言，无法对不存在的 I 做逐条对账。

判定口径：**CONFIRMED**＝代码/日志可按原报告所述路径与效果成立；**PARTLY**＝主体成立但至少一处表述、范围或前提需要更正；**FALSE POSITIVE**＝所述缺陷不存在，或已被守卫/测试/设计前提消解。

---

## 0. 判定总表

| 编号 | 被复核断言（来源） | 判定 | 一句话 |
|---|---|---|---|
| L-1 | AI 面无认证/无主体/无租户边界（A-F1、G-F1/F3/F4、H-2、B-F-M、J-F-14） | **CONFIRMED（事实）**；作为 **Critical 缺陷 = 声明边界** | 代码事实成立，但它是 DC-10 明文裁决 + ADR-11b 登记风险，不是代码回归 |
| L-1b | `GET /api/ai/runs` 全局枚举，推翻"sid 不可枚举"前提（G-F2） | **CONFIRMED** | 返回 sessionId + 待决工具 `arguments` 原文；超出登记前提 |
| L-1c | 记忆键 legacy/tagged 别名可读/删他人记忆（G-F5） | **CONFIRMED（代码级）** | `sid="{abc}"` 的 legacy 键恰等于 `sid="abc"` 的 tagged 键；非声明边界可覆盖 |
| L-2 | 多张前端卡时第一张回灌后 `suspended=false`，最后一张提交不再重连（A-F2） | **CONFIRMED（静态）**，范围比原报告更宽 | 另有一条 `clearSuspendedIfNoOtherConfirms` 只数 CONFIRM 卡的同类缺口 |
| L-2b | 多张"人机等待"卡共用单例，先到的卡既不能提交也不能取消（A-F2b） | **PARTLY** | 生成式表单：真；表格预览：**有"放弃"键**（原报告错）；上传交接：老卡"放弃"会取消新卡（另一种错） |
| L-3 | 租约权威判定失败时业务帧被丢弃且不取号，心跳照发（B-F-A） | **CONFIRMED（机制/效果）**；"无测试覆盖"= **FALSE** | 行为是刻意 fail-closed；`SseChatEmitterAuthorityFailureTest` 正好断言"业务帧全丢 + 心跳仍投递" |
| L-4 | 陈旧 RUNNING 被僵尸接管后整窗覆盖会话记忆并把 current 指回旧轮（B-F-E2） | **CONFIRMED（代码级，未运行复现）** | 唯一守卫是"新轮正在跑则 SESSION_BUSY"；新轮已完结时无守卫 |
| L-5 | 生产 `park=true`，单测缺省 `false`（A-F3） | **CONFIRMED（配置事实）**；"2000+ 多数"= **PARTLY** | 58 个 `@SpringBootTest` 会加载 yml（park=true）；"多数走阻塞路径"只对非 Spring 单测成立 |
| L-6 | 真 Redis Cluster + 产品 JVM 从未验证；≥3 JVM 无粘滞有证据（B§3.2、J-F-08） | **CONFIRMED** | 3 JVM harness 真实存在且无粘滞两项 PASS；Cluster+JVM = 0 条 |
| L-6b | 集群网"6 条 FAIL 同签名 ⇒ 可复现"（J-F-02） | **PARTLY** | 5 条真产品红 + `suspend-cross-instance` 假红 + `cold-start-3` 间歇 |
| L-7 | 39 条 P2 E2E 红不阻塞退出码（J§3.2、§3.3） | **CONFIRMED** | 17/74/39 分布与退出码逻辑均已核对 |
| L-7b | `aie2e-*` 是 mock-only，不是端到端（A/H/J） | **CONFIRMED（限定范围）** | 仅前端 `tests/aie2e-*` 层成立；`verify-e2e.ps1`/`cluster3-probe.mjs` 是真 HTTP/SSE |
| L-8 | 非安全上下文无 Web Locks ⇒ 复制页签共用会话（H-1） | **CONFIRMED（代码级/平台事实，未真机）** | `unsupported` 分支存在；HTTP 内网面是仓库自认的部署形态 |
| L-9 | start 帧前点停止会 cancel 上一轮 runId（H-5） | **CONFIRMED（代码级）** | `stop()` 用 `activeRunId`，只在 start 帧写入且 stop 不清 |


---

## 1. L-1｜无身份/租户边界：代码事实成立，但"Critical 缺陷"是声明边界

**结论：CONFIRMED（事实）；作为 Critical 缺陷 = 声明边界（非代码回归）。**

代码证据（逐条核对）：

- `backend/pom.xml` 依赖清单无 `spring-boot-starter-security`；`backend/src/main/java` 全量搜 `SecurityFilterChain|@PreAuthorize|Principal|SecurityContext` 为零命中。
- `ai/web/AiController.java:115` 类级 `/api/ai`；敏感端点均无主体参数：`chat:299`、`confirm:524`、`frontend-tool-result:590`、`events:740`、`cancel:855`、`runs:934`、`runs/current:974`、`history:1251`、`messages:1284`、`feedback:1933/1984`。
- `config/WebConfig.java:17-29` 只有 CORS：`allowedOriginPatterns=localhost:*`、`allowCredentials=true`——CORS 只约束浏览器，不约束任意 HTTP 客户端；`SecurityHeadersFilter` 只加响应头。
- `application.yml:1-3` 只有 `server.port: 8081`，无 `server.address` ⇒ 默认全网卡监听（0.0.0.0）。
- `frontend/src/api/http.ts:133-145`：axios 只有 `baseURL`/`timeout` + `Accept-Language` 拦截器，无 Authorization/凭证。
- `ai/web/AiChatRequest.java`（record）：`sessionId` 无格式/长度/熵校验；`AiController.chat:313` 只 `StringUtils.hasText(...)`。
- 会话 id 由客户端生成：`stores/ai.ts:202-211`（`crypto.randomUUID` 不可用时回退 `s-<time36>-<8字符>`，约 41 bit）。

会话归属是**登记在案的接受项**，不是本审计的推断：

- `docs/02-需求设计文档-v2.2-冻结版.md:33-40`：无用户/角色/权限/登录，"API 不区分调用主体，也不存在'越权'语义，验收中不再设越权项"；安全边界＝仅内网/本地受信环境。
- `docs/03-技术方案文档-v2.1.md:458,460`（ADR-11b 补记 CH-P7）：`sessionId` 与主体的绑定"无锚点"，本期按**已知风险接受**，前提 = 受信环境 + sessionId 不可枚举；转生产时"sid↔用户绑定 + 确认/回灌归属 + 越权用例"为一揽子前置。
- `docs/任务看板.md:593`（L-30）：明写"任何人拿到 sessionId 即可读该会话历史""多用户/多实例部署前必须先补"；用户 2026-10-24 还明确裁定"开发形态的暴露面不该管"（监听面收窄已撤销）。

因此：

- **代码级事实无争议**，G §1.1/§1.2 的取证是对的。
- **严重度标签需要改**：G-F1/G-F3/G-F4 标 `Critical`（J-F-14 同）与该报告自己引用的 DC-10/CH-P7 口径冲突——同一份报告里既说"登记在案的接受项"，又把它记为 Critical 缺陷，属于**把声明边界当代码回归**。A 的 `Important（条件升级）`更准确。建议统一为"声明边界（DC-10）/ 触发条件=非受信网络或多用户"。
- **仍然超出边界的三条**：(a) `/runs` 全量枚举（见 L-1b，直接推翻登记风险的前提）；(b) 记忆键别名（L-1c，是存储命名缺陷，不是"无主体"）；(c) 配置写 + 探活外发既有 key（G-F4）——它的利用者只需能访问 API，而 DC-10 只承诺"网络受信"，并未登记"任意受信主机可改写 provider 并取走 key"；虽然根因同属无认证，但风险面大于 CH-P7 的措辞，建议单列为"边界内残余风险"而非纯回归。

**守卫是否另有拦截**：无。限流按 `sessionId+clientIp` 计数（`AiController:326`）不构成身份；CORS 不拦非浏览器；`ContextFieldGate` 只做 page 白名单。故除"网络不可达"外没有第二道闸。

---

## 1b. L-1b｜`/runs` 枚举：CONFIRMED，且确实推翻登记前提

- `AiController.java:934-941` → `ResumeService.listRuns()`（`:293-349`）无条件列出所有 run：`sessionId`、`runId`、`status`、`createdBy`(实例 id)、`pending`(=`PendingToolCall::asMap`)。
- `run/PendingToolCall.java:280-305` 的 `asMap` 含 `arguments` / `resultText` / `executedBy` 原文。
- `GET /runs/{runId}`（`:943-950`）→ `ResumeService.describe(runId)` 还会给 `assistantContent`/`finalText`（按类注）。
- 触发：任意能访问 8081 的客户端 `GET /api/ai/runs` → 得到 victim 的 `sessionId+runId+toolCallId` → `POST /confirm {approved:true}` 真实执行导入/发布（`AiController:524` → `ConfirmGate:1103-1216`）。
- 判定：**CONFIRMED**。CH-P7 的前提是"sid 用不可枚举的随机长 ID"，`/runs` 把这个前提变成空话；G 的这条不是"无鉴权的同义反复"，而是**超出登记口径**的具体缺陷。低成本的收敛是把它挡到管理面/加开关，而不必等整套认证。

---

## 1c. L-1c｜记忆键别名：CONFIRMED（代码级），且不是声明边界能覆盖的

- 键推导：`memory/RedisChatMemoryRepository.java:187-189`（tagged = `chat:mem:{<escapedId>}`）、`:219-221`（`escapedId`：`%`→`%25`、`{`→`%7B`、`}`→`%7D`）、`:202-204`（legacy = `chat:mem:<raw id>`，不转义）。
- 读回落：`:281-291` tagged 空则读 legacy。
- 写迁移：`:304-340` staging+RENAME 后 `:338 deleteLegacyQuietly(legacyKey)` 无条件 DEL。
- 删：`:350-354` 同时 DEL tagged 与 legacy。

精确条件与效果（以受害 sid=`abc` 为例）：

1. victim 的真实键 = `chat:mem:{abc}`（tagged）。
2. 攻击者用 sid=`{abc}` 发 `POST /api/ai/chat`：
   - `findByConversationId("{abc}")`：tagged = `chat:mem:{%7Babc%7D}`（不存在）→ 回落 legacy = `chat:mem:{abc}` = **victim 的 tagged 键** ⇒ victim 全部消息进入攻击者这一轮的模型上下文；
   - 本轮结束 `saveAll("{abc}")`：RENAME 到攻击者 tagged 后 `deleteLegacyQuietly("chat:mem:{abc}")` ⇒ **victim 记忆被删**。
3. `DELETE /api/ai/session/%7Babc%7D`（controller 解码为 `{abc}`）→ `deleteByConversationId` 同样命中 victim tagged。

**守卫检查**：无。`requireId` 只拒空白；无 `{}` 拒绝、无转义后相等性校验；`RedisChatMemoryRepositoryClusterSafetyTest:103-106` 只测 `{a}b`/`}x` 的转义形状，没有 `sid="{x}"` vs `sid="x"` 的隔离用例。G-F5 的判定成立，而且它**在补上认证后仍可绕过归属**（攻击者用自己的合法 sid `{victim}` 命中 victim 的键），因此不应被 DC-10 的"无主体"免责。

（G 自己也标注了路径 `%7B` 的第二/三条未起服务验证；第 1 条只走 JSON body，不依赖路径解码，是无可争议的入口。）


---

## 2. L-2｜多张前端卡：终帧丢失 CONFIRMED，范围比原报告更宽

**A-F2：CONFIRMED（静态代码路径；未在本机做真时序复现）。**

证据链（逐段对齐）：

- 挂起即断的前提成立：`ConfirmGate.java:588` 在请求帧发出后 `out.completeSubscriptions()`；`:599-622` park 时直接 `throw RunParkedException`（`ResilientChatService:741-744` → `parkTurn` 不写终帧）。一批多 toolCall 的请求帧由 `publishRequestsForParkedBatch` 一次性公布（`:585-587` 注释 + `SpToolCallingManager:490-494`），所以"两张卡都到、连接已断"是设计内形态。
- 重连唯一入口：`stores/ai.ts:3211` 提交成功后 `this.reconnectSuspendedRun(...)`；`:2462` 该方法**第一行** `if (!this.suspended) return`。
- 提前清挂起：`:5090` `frontend_tool_result` 分支**无条件** `this.suspended = false`（不看是否还有其它 FRONTEND PENDING）。

触发（两条都可达）：

1. 一轮两张即时类前端工具（如 `download_export_files` + `navigate_to`）：第 1 张结果帧先到 → `suspended=false`；第 2 张较慢，提交后 `reconnectSuspendedRun` 直接 return ⇒ 不建连接，后端后续 `delta/done` 只进归档。
2. 一张表格/表单 + 一张即时前端工具：第一张回灌后同理。

**原报告没写到的更宽缺口**：`clearSuspendedIfNoOtherConfirms`（`:2987-2992`）的"其它卡"判据是 `pendingConfirms`（`:1387-1400`，**只收 `kind2==='CONFIRM'`**）。因此"确认卡 + 前端卡"同批时，确认卡决策后会因 `hasOther=false` 把 `suspended` 清掉，前端卡的提交同样不重连。A-F2 只举了"两张前端卡"，实际混批也会中。

用户可见效果：最后一张卡提交后无连接、终帧不落地；`pendingToolCall`（`:1362-1372`，由段 `pendingCall` 派生）仍非空 ⇒ `composerHardDisabled`（`AiPanel.vue:1580-1586`）保持禁用，气泡停在流式光标；刷新走历史才看到答案。与 A 的描述一致。

**守卫检查**：`hasDecidedPendingResidue`（`:5031`）只拦"已决 id 的重投"；`restoredPendingQueue`（`:2358-2375`）只服务快照恢复且明确"不发请求、不新建连接"；均救不了实时帧路径。

---

## 2b. L-2b｜多张"等人"卡：PARTLY

原报告 `A-F2b` 的核心（单例 `activeForm`/`activePreview`/`activeUploadHandoff` + 面板按 `toolCallId` 渲染）**代码属实**：

- `stores/ai.ts:3328` `openGenerativeForm` 无条件覆盖 `activeForm`；`:3427` `activePreview = {...planned.plan}`；`:3533` `activeUploadHandoff = {...}`。
- `AiPanel.vue:614` `FormRenderer v-if="liveForm && liveForm.toolCallId === part.toolCallId ..."`；`:632-639` 表格预览卡；`:645-653` 上传交接卡。

但"先到的卡既不能提交也不能取消"对三类卡并不一致：

| 卡型 | 先到的卡还能做什么 | 结论 |
|---|---|---|
| `generative_form` | 无 `FormRenderer` ⇒ 卡内只有空 `div`，无提交/取消；`suspended` 若被别的路径清掉，输入区还可能解禁 | **成立**（A 的描述准确） |
| 表格写预览 | `sheetPreviewFor(part)`（`AiPanel.vue:2776-2778`）对非活动卡返回 `null`，`SheetPreviewCard.vue:3-14` 渲染"预览已失效 + 放弃"，点击按 `part.toolCallId` 调 `abandonStalePreview`（`ai.ts:4114-4124` 显式支持多张失效卡） | **原报告 FALSE**：先到的卡**能取消**（放弃） |
| 上传交接 | `uploadHandoffStatus(part)`（`AiPanel.vue:2720-2723`）对非活动卡返回 `missing` ⇒ "选择文件"禁用，但"放弃"按钮调 `ai.cancelUploadHandoff()`（`ai.ts:3642-3648` 只操作 `activeUploadHandoff`）⇒ 点老卡的放弃，取消的是**新卡** | **另一种缺陷**：不是"不能取消"，而是"取消错卡"；老卡自身仍未收口 |

因此 A-F2b 的严重度与"整轮无法收尾"结论主要成立于 `generative_form`；对表格预览的表述需要撤回，对上传交接应改写成"取消目标错位"。

补充：`openWritePreview` 对 `undo_last_sheet_edit` 有一道**显式拒建卡**守卫（`:3392-3399`），也对"多张预览互相挤掉"做了 `stalePreviewToolCallId` 处理，这正说明该面被专门设计过，A 的"无任何串行/多实例结构"说法过强。

可达性：后端没有"一轮只允许一个 generative_form/预览"的限制（`grep` 无此类闸；`SpToolCallingManager:442` 注释明确"确认门与 generative_form 现在无上限"），模型一轮返回两个 `generative_form` 在协议上可达；但原报告也如实标注"未用真模型复现"。我同样未运行复现。


---

## 3. L-3｜租约权威失败丢业务帧、心跳照发：CONFIRMED（机制），"无测试"是错的

**机制 CONFIRMED**：

- `run/SseChatEmitter.java:1422` 心跳豁免；`:1425` 逐帧 `store.runLeaseAuthority(runId)`；异常 catch 一律折成 `UNAVAILABLE`（`:1428-1435`）；`:1437-1443` `readOnly()` ⇒ 直接 `return`（**在取号之前**，所以既不占 seq、不落档、不 publish、不扇出；`done/error` 同样被丢）。
- `run/LeaseService.java:337-339` `readOnly = LOST || UNAVAILABLE`；`:797-815` 的判定顺序 = 墓碑 → 视图 → `facilityHealthy()?NEVER_HELD:UNAVAILABLE`；`:822-841` 的 `facilityHealthy` 只在已失败后按 `HEALTH_PROBE_INTERVAL_MS=1000`（`:422`）做一次 `PING`。`facilityHealthy=false` 的置位点遍布 `acquire:563`、`verify:656`、`renew:704`、`release:737`（+`leaseOk:994`）⇒ **任一租约命令失败会让整个进程的这一分支变 false**，与具体 run 无关。
- `run/RunStore.java:695-699` 同源：`save` 在 `readOnly` 时直接 return（终态也写不进）。
- 心跳分支（`:1456-1476` 一带）确实继续 `touchActivity` + 序列化 + 广播，且心跳**不进事件日志**（含 `seq` 的帧才是业务帧）。

精确触发与效果：

1. 同 JVM 内任一租约命令失败（Cluster 单槽瞬断、CLIENT PAUSE、超时）⇒ `facilityHealthy=false`；
2. 同一秒内，本 JVM 上一条**普通文本** `/chat` 正在出帧 ⇒ `authority()` → `PING` 仍失败 ⇒ `UNAVAILABLE` ⇒ 所有业务帧在取号前被丢；
3. 心跳（park 2s / 流式 5s 节拍）继续到客户端 ⇒ 前端 45s 帧间看门狗不触发；丢掉的 `delta` 从未取号 ⇒ 重连 `?lastSeq` 也补不回。
4. Redis 恢复后 `PING` 成功 ⇒ 后续帧恢复，但**中间那段永久缺失且无任何缺口信号**（`stores/ai.ts:4930-4935` 明确"只做同序号去重，不做连续性判定"）。

**关键更正（FALSE 部分）**：B-F-A 的"对应测试只断言该帧不落档、**没有**断言'心跳仍发 + 正文缺失'组合"不成立。`backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterAuthorityFailureTest.java:51-77` 的用例名就是 `authorityFailureTurnsBusinessFramesReadOnlyButKeepsHeartbeats`，断言：`journal` 空、事件日志空、`bus.published` 空、wire 无业务帧，且随后 `out.heartbeat(...)` 仍产出唯一一帧 heartbeat（无 `seq`）。这正是 B 声称"缺失"的组合。真正缺的是**端到端/用户面**断言（无 gap 标记、无指标），而不是"没有测试"。

**性质判定**：这是**刻意的 fail-closed fencing 取舍**（类注与用例注释都写明"宁可这一拍不发，也不能让旧主人继续写"）。所以我把它定为"机制 CONFIRMED、作为缺陷的可归责性存疑"：代码行为与设计一致；可讨论的是"Redis 抖动时是否应给客户端一个缺口信号或改为错误收尾"，而不是"实现写错了"。

**守卫检查**：`facilityHealthy` 的 PING 只能恢复**后续**帧，不能回填已丢帧；SseChatEmitter 内**没有** `RedisDegradation` 计数（grep 零命中），只有一次 `readOnlyWarned` WARN ⇒ 运维面也几乎不可见。

---

## 4. L-4｜僵尸 RUNNING 接管覆盖新记忆 / current 指针：CONFIRMED（代码级）

**CONFIRMED**，链路完整：

1. 判定可续跑：`ResumeService.java:326-349` —— `RUNNING && now-activeAt > zombie-age(180s，application.yml:243)` ⇒ `zombieRunning=true`，`resumeable=!cancelled && 无外部 PENDING && !terminal && (zombie || !RUNNING)`。**不看该 session 是否已有更新的一轮**。
2. 触发源：`StartupResumeRunner.java:69-108` `@EventListener(ApplicationReadyEvent)` 每实例一次 `scan(false)`；`:155-162` 对 `zombie=true` 调 `resume(runId, true)`；运维 `POST /api/ai/startup-resume/scan`（`AiController:918`）可强制重扫（集群探针正是用它：`cluster3-probe.mjs:2756-2772`）。
3. 接管：`ResumeService.java:710-729` `forceTakeover` 只校验快照自身（状态/活跃时刻），**没有**"会话是否已前进 / current 是否指向别的 run"的校验；`:804-806` 重建历史只用 `snapshot.getMessageJson()`（不是会话记忆），所以新轮消息根本不在里面。
4. 写回：`:875-882`（in-flight 分支）或 `:931-932` 把快照置 RUNNING 并 `store.save(snapshot)`；`RunStore.java:733-735` 对 RUNNING **无条件** `markSessionCurrent(sessionId, runId)`（`:815-825` 是 plain `SET`，非 SETNX/compare）⇒ `/runs/current` 指回旧轮。
5. 轮末收敛：`ResilientChatService.java:1414-1448` `convergeMemory` → `persistMemory:1463-1480` → `ChatMemoryRepository.saveAll`；`RedisChatMemoryRepository:304-340` 是 **staging + RENAME 整窗替换** ⇒ 旧轮完整历史覆盖掉用户之后已完成新轮写下的记忆。模型下一问"忘记"最近一轮。
6. 前端放大：刷新后 `/runs/current` 返回旧 run（RUNNING，非 terminal）⇒ `stores/ai.ts:2169-2202` `restoreInFlightRound` 找到本轮宿主（最后一条 user 之后的 assistant，很可能就是**新轮已完成的那条**）→ `:2191-2199` 清空 `parts/content/reasoning/toolRuns/pendingCall/appliedSeqs` → `lastSeq=0` 全量重放旧轮 ⇒ 界面把新轮答案覆盖成旧轮半截。

**守卫检查（这是本条能否成立的关键）**：

- `ResumeService.java:772-789` 在续跑前重新 `sessionGate.acquire`；若**新轮正在跑**，返回 `SESSION_BUSY` 并留给派发方重试 ⇒ 这是唯一有效守卫。
- 但若新轮**已经正常结束**（最常见：用户等 3 分钟后重发并拿到答案，随后任一实例重启），会话门已释放，守卫不触发。
- `listRuns` 不看 `ai:session:current`；`resumeInternal` 不看记忆窗口；`max-on-startup: 10` 只限条数，不限"会话已前进"。
- 测试：`ResumeServiceZombieThresholdTest` 只测阈值/心跳口径；`StartupResumeRunnerSessionGateTest` 只有一例 `startupScanReportsSessionBusyWithoutTouchingTheSnapshot`。**没有**"旧轮接管 + 会话已前进 + 记忆被覆盖"的用例。

条件汇总：驱动进程死亡（RUNNING 残留）→ 会话空闲 >180s → 任一实例启动或强制扫描 → 且用户的新轮已完成。效果：记忆窗口回退、`/runs/current` 指错轮、刷新界面回放旧轮。我未运行该时序，但每一步都有确定的代码路径。


---

## 5. L-5｜`park=true` vs 单测缺省 `false`：CONFIRMED（配置事实）；"多数"表述 PARTLY

- `application.yml:258-259` `app.ai.park.enabled: true`；`:255-257` 注释自认"代码缺省 false、本文件 true 是**刻意不一致**"。
- `ai/config/AiProperties.java:855` `private boolean enabled = false;`；`:844-845` 明确"出厂 yml=true"。
- 显式置 true 的测试：8 个文件（`AiParkConfigTest` 是配置断言，另 7 个是行为用例：`ParkToolStartOrderConformanceTest`、`HumanWaitParkTest`、`ParkedFrontendExpiryTest`、`ResumeDispatchRetryTest`、`AiControllerParkWiringTest`、`AiControllerEventsParkCloseTest`、`FrontendGuardPreSuspendTest`）。A 说"仅 7 个"——把配置测试算进去是 8，属口径差异，不影响结论。
- 规模：后端 395 个 `*Test.java` / 2607 个 `@Test`，其中 `@SpringBootTest` **58** 个。Spring 上下文用例会加载 `application.yml` ⇒ park=true；其余用 `new AiProperties()` 或 mock 的用例 park=false。

判定：**配置事实与风险方向 CONFIRMED**（"改坏 park 路径仍可能全绿"），但"2,000+ 单测**大多**验证阻塞老路径"需要限定：以文件数看，Spring 用例只占 ~15%，所以"多数"在数量上大概率成立；但 `@SpringBootTest` 那 58 个恰恰是集成度最高的一层，不能一概说"测试全在验老路径"。这是**测试可信度问题，不是产品缺陷**（A 自己也这么标）。修复方向（一条真 Redis + park=true 的 chat→挂起→决策→续跑→done `@SpringBootTest`）成立。

---

## 6. L-6｜真 Redis Cluster+产品 JVM、≥3 JVM/无粘滞：CONFIRMED（用 run 日志核验）

**6.1 真 Cluster + 产品 JVM = 0 条：CONFIRMED。**

- `scripts/ci/cluster3-run.sh:357-363`：起的是 `redis:7.2.16` 单节点（`-p PORT:6379`，无 `--cluster-enabled`）。
- `.github/workflows/ci.yml:240-249` e2e 的 `services.redis` 同为单节点；`cluster-3node.yml` 同栈。
- `backend/src/main/resources/application-cluster.yml:29` `cluster.nodes: ${AI_REDIS_CLUSTER_NODES}`；唯一相关测试 `ai/run/ClusterProfileConfigTest.java` 只用 `ApplicationContextRunner` 做属性绑定/fail-fast，不建连接。
- `scripts/redis5-cluster-smoke.sh:16-24` 自认"只有环境级 redis-cli 证据；产品 available/503 未在本网验证"，且不起 JVM。
- 全仓 `grep testcontainers|embedded-redis` 零命中；AI 测试里唯一"真 Redis"是 `RedisChatMemoryRepositorySaveAllEmptyTest` 用 `assumeTrue(connect())` 连本机 127.0.0.1:6379/DB9，连不上即 SKIP，且单节点。
- ⇒ 生产 Cluster profile 的 `MOVED/ASK`、跨 master `SCAN`、槽迁移、hash tag 同槽等，在产品链路上确实一次都没跑过。J-F-08 成立。

**6.2 ≥3 JVM 无粘滞：证据存在，但是红的。** 用 `gh run view` 核验：

- `gh run list --workflow cluster-3node.yml` 最近 4 次：`2ed8f95 / 20c56bb / 965e6ef / ae883fb` **全部 failure**（4/4），触发方式 `workflow_dispatch`（`.github/workflows/cluster-3node.yml:24-25`）。
- `40174291244` @ae883fb：日志 `结论：**FAIL**（PASS 9 / FAIL 7）`。逐用例：PASS `proxy-no-sticky`、`same-tcp-across-abc`、`reattach-any-instance`、`history-pagination`、`seq-cross-instance`、`idempotency-cross-instance`、`session-purge-cross-instance`、`config-hot-reload`、`redis-outage-recovery`；FAIL `cold-start-3`、`suspend-cross-instance`、`shared-file-download`、`suspend-kill-a`、`streaming-kill-a`、`job-kill-a`、`readiness-removal`。与 J-F-02 完全一致。
- `proxy-no-sticky` + `same-tcp-across-abc` 通过 ⇒ **"三 JVM + HAProxy roundrobin + 无 stick-table/cookie"确实被真实跑过**（配置 `cluster3-run.sh:113`，静态自检 `:231-233` 断言"无 stick-table / 无 cookie / 无 balance source/hdr"）。所以"没有 no-affinity 证据"是错的；准确说法是"有，但整网从来没绿过"。
- 具体红因与 J 的引用一致：`streaming-kill-a：期望 >= 3，实得 1`；`shared-file-download` sha256 两次 run 都不同；`job-kill-a` 残留 RUNNING 条目；`readiness-removal` 14.6s 起 `ECONNREFUSED`；`suspend-kill-a：tool_start 期望 1 实得 0`。

**6.3 对 J-F-02"6 条可复现"的更正（PARTLY）**：

- `40173316364` @965e6ef 是 10 PASS / 6 FAIL。与 ae883fb 的 7 FAIL 取交集 = 6 条（`suspend-cross-instance`、`shared-file-download`、`suspend-kill-a`、`streaming-kill-a`、`job-kill-a`、`readiness-removal`），`cold-start-3` 在该 run 是 PASS（间歇）。
- 但这 6 条里 `suspend-cross-instance` 是**假红**：J-F-10 的论证成立——探针在决策**之前**就 `reattachOn('c', runId, 0)`（`cluster3-probe.mjs:2157-2162`），此时条目仍 PENDING；产品按 park 契约回放完即收流（`AiController.java:766-768` + `stillWaitingForInput`），随后却要求同一连接收到 `done`，与契约直接冲突（日志正是"流已结束（已收=tool_start）"）。所以真实"同签名产品红"是 **5 条**（shared-file / suspend-kill / streaming-kill / job-kill / readiness），不是 6 条。J 在 F-10 已识别，但 F-02 的"6 条可复现"仍会让读者误读为 6 个产品缺陷。


---

## 7. L-7｜P2 E2E 不阻塞 + mock-only：CONFIRMED（需限定层）

**7.1 P2 不阻塞：CONFIRMED。**

- `scripts/verify-e2e.ps1:5723-5725`：
  - `if (($script:failed - $aiP2Fail) -gt 0) { exit 1 }`
  - `if ([int]$script:aiP0Fail -gt 0 -or [int]$script:aiP1Fail -gt 0) { exit 1 }`
  ⇒ P2 失败只计数，不改变退出码。
- 分布核对：`Add-AiCase ... 'P0'/'P1'/'P2'` 计数 = **17 / 74 / 39**，与 `:5530` 的 MANIFEST 期望 `17/74/39` 和总数 130 一致；`:5488-5492` 把 `SKIP:*` 强制改 FAIL；`:5507-5530` 有清单/域分布自校验（计数错会额外产生一条 FAIL）。所以"P2 红仍可绿"是真实的口径，而非统计噪声。
- 门禁实测（gh）：`40174289141` @ae883fb 的 e2e job `cancelled`，25 分钟超时（07:54:19 → 08:19:35）；后端 3m41s 绿。日志里 `AIE2E-*` 唯一用例数 = **17**，最后一条是 07:59:23 的 `M-09`，之后 20 分钟无输出，`##[error]The operation was canceled.`；注册顺序上下一条正是 `H-01`（`verify-e2e.ps1:3061` → `:3126`）。J-F-01 的"130 只跑 17"**精确成立**。
- 相关机制：`HttpTimeoutMs = (300+60)*1000 = 360000`（`:117`），用例循环（`:5481-5506`）**没有 per-case 超时** ⇒ 单条失控可吃掉整个 job 预算。这一条与 F-01 的因果链一致。

**7.2 mock-only：CONFIRMED，但只能限定在"前端 aie2e 层"。**

- `frontend/tests/aie2e-refresh.spec.ts:36` `vi.mock('@/api/ai', ...)`（api 用 `vi.hoisted` 手写假实现，`start` 帧由测试自造）；`aie2e-panel.spec.ts:34`、`concurrency-matrix.spec.ts:35` 同形。⇒ 这些"E2E"是 vitest+jsdom+store 记账，不是链路。
- 但 `verify-e2e.ps1`（真 JVM + 真 Redis 单机 + 真 HTTP/SSE + 桩上游）与 `cluster3-probe.mjs`（真 3 JVM + HAProxy + 真 kill/`SIGSTOP`）**不是 mock**，断言到台账/幂等索引/sha256/seq。J §3.2 与 A §5 的写法是准确的；若把"测试全是 mock"当成总体结论则是错的——原报告没有这么写。
- 前端真浏览器层不在门禁：`ci.yml` 无 `playwright` 命中；`changed-files.mjs:74-79` 的 `frontend` 规则只置 `frontend:true`，不置 `e2e`（后端/脚本/workflow 才置 e2e）⇒ 纯前端改动不跑 E2E 面。J-F-12 成立。

---

## 8. 其他抽查（H 报告的高影响断言）

**L-8 H-1（非安全上下文无 Web Locks ⇒ 复制页签共用会话）：CONFIRMED（代码级 + 平台事实，未真机）。**

- `stores/ai.ts:419-427` `lockManager()` 取 `navigator.locks`；`:4588-4590` 取不到即 `tabOwnership='unsupported'` 并 `return null`；`:4679-4691` 的接手逻辑只对 `tabOwnership==='duplicate'` 生效。
- 会话 id 存在 `sessionStorage`（`initSessionId:637-649`），复制页签会继承；`unsupported` 下没有任何 fallback 判定 ⇒ 两页签共用同一 sid。
- 平台事实：Web Locks 仅安全上下文（HTTPS/localhost）。仓库自认存在 `http://<内网IP>` 非安全上下文面（`AiPanel.vue:1103`、`:3393` 注释，因 `vite.config.ts` `server.host='0.0.0.0'`）。我未用真浏览器在 http 源复现，但代码路径与平台约束都支持 H-1。

**L-9 H-5（start 帧前停止会 cancel 上一轮）：CONFIRMED（代码级）。**

- `stores/ai.ts:4343` `const runId = this.activeRunId`；`activeRunId` 只在 `start` 帧写入、`stop()` 不清；`hadRound` 判据（`:4345`）在 `loading/suspended` 为真时可通过 ⇒ 首包前点停止会用上一轮 runId 发 `POST /ai/cancel/{runId}`。后端对未知/终态 runId 幂等收敛，通常无害；本轮新 run 在服务端是否继续取决于写失败自杀（H 标注未证实，我未验证）。H-5 的"条件+效果"写法是克制的，成立。

---

## 9. 对原报告的更正清单（按可执行性排序）

1. **B-F-A**：删掉"没有测试断言心跳仍发"的表述；`SseChatEmitterAuthorityFailureTest:51-77` 已覆盖该组合。应改成"缺少用户面/指标证据（无 gap 标记、无 degradation 计数）"。
2. **A-F2b**：表格预览的"先到卡不能取消"应撤回——存在 `sheet-preview-abandon` 与按 `toolCallId` 的 `abandonStalePreview`；上传交接应改写为"老卡'放弃'取消的是新卡（`cancelUploadHandoff` 只读 `activeUploadHandoff`）"。
3. **A-F2**：补一条更宽的触发形态——`clearSuspendedIfNoOtherConfirms`（`ai.ts:2987-2992`）只统计 CONFIRM 卡，混批（CONFIRM + FRONTEND）同样会提前清 `suspended`。
4. **J-F-02**：把"6 条可复现"改成分层表述：5 条真产品红 + `suspend-cross-instance` 假红（F-10 已论证）+ `cold-start-3` 间歇（一次 PASS 一次 FAIL）。
5. **G-F1/F3/F4、J-F-14**：Critical 标签与 DC-10/CH-P7 的"登记接受项"口径冲突；建议至少加"声明边界"标记，或把 F-2（/runs 枚举）与 F-5（别名）单独升级——这两条确实超出登记前提。
6. **A-F3**："仅 7 个测试文件"应注为"7 个行为用例 + 1 个配置断言（`AiParkConfigTest`）"；"2,000+ 单测大多验阻塞路径"需限定非 `@SpringBootTest` 层（58 个 Spring 用例加载 yml ⇒ park=true）。
7. **行号小幅漂移**（不影响判定）：A 引 `openWritePreview:3371`/`openUploadHandoff:3479` 是函数声明行，实际赋值在 `3427`/`3533`；`AiPanel.vue:613` 是注释行，`v-if` 在 `614`；`reconnectSuspendedRun` 的守卫在 `2462`（A 写 2461-2463，范围覆盖）。
8. **I-frontend-workspace.md 缺失**：无法对账；若指挥官需要"工作区联动"域，应补一份，或明确由 H + E 覆盖。


---

## 10. 未验证 / 本报告的边界

- 未运行任何测试、未起服务、未做真时序复现：L-2（多卡时序）、L-3（Redis 抖动窗口）、L-4（kill+重启+会话前进）、L-5（park 路径回归）、L-8（真浏览器 http 源）均为**代码级推演**。
- 未验证 G-F5 的路径形式 `%7B`（Spring 解码行为）；只确认 JSON body 入口（不依赖解码）。
- 未对 J 的 F-03/F-04/F-05/F-06/F-07 做独立复现，只在核验 F-02 时顺带读到了同一批日志里的对应证据（sha256、残留 RUNNING、ECONNREFUSED、`实得 1`），这些与 J 的描述一致。
- CI 证据来自 `gh run view` 的历史日志，属只读外部证据；不构成对 HEAD 后续提交的保证。
- 未复核 D/E/F/C 四份报告（不在本次指定范围）。

## 11. 结论（给指挥官）

1. **七条指定断言里，五条半成立**：无身份边界（事实成立/定性应改）、多卡丢恢复（成立且范围更宽）、租约丢帧+心跳（机制成立、"测试缺失"说法不成立）、僵尸接管覆盖记忆（成立）、park 配置错位（成立）、Cluster 缺口（成立）、P2 不阻塞 + mock-only（成立，需限定层）。
2. **两处应改判**：A-F2b 对表格预览"不能取消"是**误判**；J-F-02 的"6 条可复现"应降为"5 条真红 + 1 条假红 + 1 条间歇"。
3. **最该先修的不是 Critical 标签最大的那条**：DC-10 的无鉴权是**声明边界**，改标签即可；真正超出边界、且补认证后仍会复发的是 **G-F5 记忆键别名**（跨 sid 读+删）与 **G-F2 `/runs` 全量枚举**。其次才是 L-2（终帧丢失）与 L-4（僵尸覆盖记忆）这两条用户可见、代码路径确定的缺陷。
