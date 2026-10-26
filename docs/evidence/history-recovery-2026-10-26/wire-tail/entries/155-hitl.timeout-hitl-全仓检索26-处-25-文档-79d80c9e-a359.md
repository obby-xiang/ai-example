> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-359` 的 wire 末条 assistant 文本（消息序号 m=19；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-10 22:04 → 2026-10-10 22:06（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：38828 B / 29025 字符 / 364 行；行指纹覆盖率（本批复算）0.0%（无单文件重合）
> - 类别：**过程核验**
> - 与既有件关系：—（上一轮判为疑似真缺口）
> - 脱敏：路径 8 / 会话标识 0 / 身份 74 / 密钥 0 / 命名纪律 15
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
检索完成。仓库：`<REPO_ROOT>`（下文所有路径均相对仓库根）。**工作区为脏状态**：21 个已跟踪文件被修改（含 `backend/src/main/resources/application.yml`、`ConfirmGate.java`、`docs/M2-排期计划.md` 等），1 个未跟踪文件 `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java`。`rg` 在环境中不可用（`rg: command not found`），未跟踪文件用 `grep` 补齐。检索范围 = `git grep`（已跟踪全部文件的工作区内容）+ 对唯一未跟踪文件的 `grep`。

---

## 1. `hitl\.timeout`（共 26 处命中）

### (a) Java/YML 活代码引用
**无命中。** 全仓无 `@Value`、无 `Environment.getProperty("app.ai.hitl…")`、无 `getHitl().getTimeout()` 之类活代码引用（`git grep -nE "@Value|getProperty" -- '*.java' | grep -i hitl` → 空）。

### (b) 注释 / javadoc（1 处）
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:197`（javadoc）：`	 * —— 原单键 {@code app.ai.hitl.timeout} 同供三场景已按裁决③拆分。`

### (c) 文档 `.md`（25 处）
`docs/M2-排期计划.md`（3）
- `:233`：`| R-41（阻塞审计·后端AI篇 · S2 · 后端AI B-05）ConfirmGate#await…：CountDownLatch 阻塞运行线程至多 120s（hitl.timeout）…**行尾注记（2026-10-10 数字规格小批苞 A，只追加）**：该键已于苞 A 拆分为 app.ai.hitl.confirm-timeout（240s）/ app.ai.hitl.frontend-tool-timeout（120s）…`
- `:291`：`| R-99（等待治理排查 · §3.3 配置键「一刀切共用」7 项）…① app.ai.hitl.timeout（120s）现同供确认门 / 前端工具挂起 / generative_form 三场景…④ server.tomcat.connection-timeout 120s 与 hitl.timeout 数值重合…**完成留痕（2026-10-10 小批苞 A，只追加）**：…旧单键 app.ai.hitl.timeout（120s 同供确认门 / 前端工具 / generative_form）拆为…`
- `:384`：`> - **观察①（配置键 · 静默回落）**：旧键 app.ai.hitl.timeout 已从类与 yml 双方移除，若外部仍以 --app.ai.hitl.timeout=20s 之类的启动参数覆盖，Spring Boot 对未知属性默认忽略 ⇒ **静默使用 240s，无 WARN**…`

`docs/evidence/GFa-生成式表单后端工具验证.md`（2）
- `:238`：`| TIMEOUT（ConfirmGate#expire，reason FRONTEND_TIMEOUT） | 前端/人**没响应**，等满 app.ai.hitl.timeout（默认 120s）自动取消 | ✗（语义是"没人响应"，不是"用户放弃"；而且要白等 120s） |`
- `:362`：`app.ai.hitl.timeout（默认 120s）超时"的路径**本轮未出现**（真实模型该轮未触发表单），`

`docs/evidence/GFc-E2E用例设计-GLM-5.3.md`（2）
- `:57`：`| 前端通道等待上限 app.ai.hitl.timeout 默认 **120s** | AiProperties.java:70 |`
- `:230`：`> **纠正（裁决 #2）**：真正危险的**不是**"没传处理器"的情形——Invoke-AiTurn 在 $onSuspend 为 $null 时直接 break（:277），根本不发 POST，挂起只会悬到 app.ai.hitl.timeout（120s）自然收摊。…`

`docs/evidence/GFc-端到端执行验证.md`（1）
- `:363`：`- 未出现等待 app.ai.hitl.timeout（120s）或原流 180s 预算的现象；整轮 **93 秒**（19:40:01→19:41:34），`

`docs/evidence/GFc-脚本红线审查-DS-V4-Pro.md`（3）
- `:45`：`- GF4 的时序是 A3（读流 3.5s）**先于** A4（同 toolCallId 改值重发，唤醒 run）。挂起 run 在 A4 之前不会产生任何新帧、也不会关闭，因此 A3 会一直阻塞到 app.ai.hitl.timeout（120s）或 HttpClient 5min 超时——A4 永远不会到达，构成顺序性死锁【推断】。`
- `:104`：`### 低-8　timeoutSeconds 硬编码 120 —— 与可配置 app.ai.hitl.timeout 耦合`
- `:108`：`- **证据**【推断】：后端 frontendToolRequest(pending, timeoutSeconds) 的 timeoutSeconds 来自 app.ai.hitl.timeout（默认 120s，AiProperties）。若执行棒以非默认超时启动，GF1/GF2/GF3/GF4 的 A1 帧契约断言会误 FAIL。…`

`docs/evidence/M2-E2E门禁设计草案-DS-V4-Flash.md`（1）
- `:114`：`| 挂起/确认 | app.ai.hitl.timeout: 120s；app.ai.suspend.pool-size: 20；app.ai.resilience（first-byte 30s / inter-event 90s / max-attempts 2 / total-budget 300s）；…`

`docs/evidence/M2-T2b-施工红线-DS-V4-Pro.md`（1）
- `:92`：`- **TTL 取 lock.ttl（非 session-ttl）**：正确【实测】。挂起期每次 await ≤ app.ai.hitl.timeout（120s），心跳每 2s 续期（ConfirmGate.java:190-193）…`

`docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md`（2）
- `:418`：`| app.ai.hitl.timeout | 120s | **120s（维持不变）** | §S1.3（挂起并发 20 路在 120s 上限下线性受理，未见与 resilience 的相互放大） | 与 inter-event-timeout 90s 的牵制关系（场景 C）本棒**无独立数据**，标【推断】 |`
- `:440`：`上表标【推断】的 6 项（hitl.timeout 的牵制关系、resilience 四值、心跳 2s/5s）`

`docs/evidence/S42-P1-核心运行时验证.md`（2）
- `:33`：`| com/example/configmgr/ai/config/AiProperties.java | 规格 §4 配置绑定：app.ai.session-ttl / memory.max-messages / suspend.pool-size / hitl.timeout / resilience.total-budget |`
- `:56`：`| <REPO_ROOT>/backend/src/main/resources/application.yml | 新增 spring.data.redis.*；…新增 app.ai.{session-ttl,memory.max-messages,suspend.*,hitl.timeout,resilience.total-budget}（基座 app.ai 旧键原样保留） |`

`docs/evidence/S42-P2-挂起续跑会话门验证.md`（4）
- `:164`：`工具选的是 start_publish（@ToolRisk(DANGER)…）。三场景分别用独立任务…，确认门等待上限默认 120s，超时场景另起实例设 --app.ai.hitl.timeout=20s。`
- `:187`：`**（c）超时自动取消**（sessionId s42p2-c1c，--app.ai.hitl.timeout=20s）：`
- `:465`：`| N2 | 前端工具挂起的等待上限**复用** app.ai.hitl.timeout（规格 §4 只有这一个"挂起等待上限"键） | 复用，并在类注释里写明 | 是否给前端工具单独一个键（如 app.ai.frontend-tool.timeout）—— 前端动作的合理等待时长与人工确认不是一回事 |`
- `:508`：``# 超时场景：启动时追加 --app.ai.hitl.timeout=20s``

`docs/evidence/S42-P3-集成冒烟验证.md`（4）
- `:152`：`| **(a) 挂起期上游静默** | 确认门挂起（hitl.timeout=150s），决策**故意等 100s** 才放行（包间阈值 90s） | **不误判断流**：…`
- `:164`：`配置：--app.ai.session.lock.ttl=6s --app.ai.session.lock.watchdog=30s --app.ai.hitl.timeout=60s。`
- `:211`：`| **(c) 超时** | 不放行，等满 hitl.timeout=120s | 60 帧 heartbeat → confirm_decision{decision:timeout,reason:CONFIRM_TIMEOUT,waitedMs:120000} → …`
- `:314`：`| R1 | **包间超时 90s < 确认门上限 120s**：硬规范①"阈值 > 最长工具耗时"目前靠"工具活跃期不计静默"实现…是否把 inter-event-timeout 直接提到 > app.ai.hitl.timeout（例如 150s）而不再依赖；或把两件事拆成两个键…`

未跟踪文件 `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsListTasksTruncationTest.java`：`hitl`/`timeout` 均无命中。

---

## 2. `hitl:` 在 yml/yaml/properties 中的出现及上下文

仓库内被跟踪的 yml/yaml/properties 仅 6 个：`backend/src/main/resources/application.yml`、`spike/sp01ab|sp01c|sp01d|sp02/src/main/resources/application.yml`、`.github/workflows/ci.yml`。**不存在 test resources 目录**（`git ls-files | grep test/resources` → 空），故无测试态 yml/properties。

**`backend/src/main/resources/application.yml`（唯一含 `hitl:` 键块的 yml）**
```
106:  #    max-iterations/session-ttl-minutes/hitl-timeout-minutes/thinking-disabled 已删除，   ← 注释
107:  #    其中 thinking-disabled 在新运行时早已失效：官方循环 + reasoning 补丁不设 extraBody.thinking）──
108:  ai:
109:    session-ttl: 6h
110:    memory:
111:      max-messages: 40
112:    hitl:                                                        ← 键块声明（活配置）
113:      # 挂起等待上限**分场景**（2026-10-10 数字规格清点裁决③；原单键 timeout: 120s 同供三场景）：
114:      #   confirm-timeout（等人）：确认破坏性操作时等人类决策。
115:      #     依据 = 业界依据 §5：该节唯一硬结论是"不设超时=永久卡死"（AWS Step Functions），
116:      #     取值无统一标准，对内网/小用户量的交互式确认门推荐 10 分钟（600s），超时自动拒绝。
117:      #     本键**本轮取 240s 为过渡值**：600s 才是目标值，但实际可达上界受本文件
118:      #     app.ai.resilience.total-budget（300s）约束 —— 看门狗的总预算判据先于"工具活跃期
119:      #     不计静默"生效，SseEmitter 寿命亦取该值。240s < 300s（留 60s 余量）故值真实可达；
120:      #     抬到 600s 须与"轮次预算 / emitter 寿命 / 兜底等待三语义拆分"（等待治理 R-99③）同批。
121:      #   frontend-tool-timeout（等机器）：前端工具与 generative_form 的回执等待。
122:      #     依据 = 业界依据 §6：MCP 只规定 SHOULD 设超时；实现层默认常见 60s，Vercel AI SDK
123:      #     toolMs 区间 5–60s。**本键本轮暂维现状 120s**：降到 60s 会把前端执行器预算折算压到
124:      #     5s 下限（frontend/src/utils/frontend-tools.ts 的"预算 = 服务端上限 − 回灌 POST 60s"，
125:      #     该文件自述 ≤65s 为病态配置，而实测最慢成功回灌 30.6s）—— 降到 60s 的前置 = 前端折算面
126:      #     （回灌 POST 单独给更短上限）专项（数字规格清点 P-1 / 等待治理 R-99②），本次不动。
127:      confirm-timeout: 240s                                       ← 活配置值
128:      frontend-tool-timeout: 120s                                 ← 活配置值
129:    suspend:
137:      #   "配置值本身要不要直接大于 app.ai.hitl.confirm-timeout"列为待裁决。   ← 注释（resilience 节内）
```

**`spike/sp01ab/src/main/resources/application.yml`**（键块名 `spike:`，非 `hitl:`）
```
8: spring:
9:   application:
10:     name: sp01ab-frontend-tool-hitl          ← 应用名（活配置）
21:   # 前端工具挂起等待上限：超时后循环得到“前端未响应”语义并继续
22:   frontend-tool-timeout-seconds: 60
23:   # HITL 确认门挂起上限：超时 = 自动取消（未执行）
24:   confirm-timeout-seconds: 30
25:   # 心跳周期：挂起期间保活 SSE
26:   heartbeat-ms: 2000
```
（`spike:` 缩进顶格，`spike.sp01ab…` 结构；上下各 2~3 行已列。）

**`spike/sp02/src/main/resources/application.yml`**
```
21: spike:
22:   # 前端工具挂起等待上限（阻塞式形态；等待期间挂起态已外置到 Redis）
23:   frontend-tool-timeout-seconds: 120
24:   # HITL 确认门挂起上限                       ← 注释含 "HITL"
25:   confirm-timeout-seconds: 120
26:   # 故障注入窗口：approve 之后、真正执行之前插入的延迟（用于复现“已放行未执行”崩溃）
27:   exec-delay-ms-after-approve: ${SP02_EXEC_DELAY_MS:0}
```
`spike/sp01c`、`spike/sp01d` 的 application.yml：**hitl 无命中**。`.github/workflows/ci.yml`：**hitl 无命中**。

---

## 3. 活代码中的 Java `hitl` / `Hitl`

### 生产代码 — 活代码（非常量、非注释）
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:35`（代码·字段）：`	private Hitl hitl = new Hitl();`
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:116`（代码·内部类声明）：`	public static class Hitl {`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:170`（代码）：`		Duration timeout = this.properties.getHitl().getConfirmTimeout();`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:181`（代码）：`		Duration timeout = this.properties.getHitl().getFrontendToolTimeout();`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:770`（代码）：`		out.put("confirmTimeout", String.valueOf(this.properties.getHitl().getConfirmTimeout()));`
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:771`（代码）：`		out.put("frontendToolTimeout", String.valueOf(this.properties.getHitl().getFrontendToolTimeout()));`
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`：`confirmTimeout`/`frontendToolTimeout` 字段声明（`:144`、`:174` 附近，字段名本身不含 hitl）

### 生产代码 — 注释/javadoc
- `AiProperties.java:15`：` * {@code AppProperties.Ai}（max-iterations / session-ttl-minutes / hitl-timeout-minutes /`
- `AiProperties.java:182`：`		 * 但"配置值本身要不要直接大于确认门键 {@code app.ai.hitl.confirm-timeout}"列为【待裁决】。`
- `AiExecutorConfig.java:18`：` * 一轮对话占用一条线程直至终帧 —— 挂起等待（确认门 ≤ {@code app.ai.hitl.confirm-timeout}，`
- `ConfirmGate.java:38,39`：javadoc 两条（`app.ai.hitl.confirm-timeout` 默认 240s / `app.ai.hitl.frontend-tool-timeout` 默认 120s）
- `ConfirmGate.java:168`：`	/** 确认门等待上限（秒）：{@code app.ai.hitl.confirm-timeout}（等人，默认 240s）。 */`
- `ConfirmGate.java:175,177,195,196,197`：javadoc（前端工具键说明、与确认门键刻意分开、`原单键 {@code app.ai.hitl.timeout} 同供三场景已按裁决③拆分`）
- `ConfirmGate.java:511`：`	 * 用户关掉表单时前端<b>没有合法动作</b>，挂起只能悬到 {@code app.ai.hitl.frontend-tool-timeout} 才由`
- `StreamWatchdog.java:22`：` * {@code app.ai.hitl.confirm-timeout} 与 {@code app.ai.hitl.frontend-tool-timeout} 两键按场景给出：`
- `AiTools.java:501`：`     * 时间上限沿用既有前端通道口径（{@code app.ai.hitl.frontend-tool-timeout}，默认 120s）…`
- `AppProperties.java:17`：` * 带 max-iterations/session-ttl-minutes/hitl-timeout-minutes/thinking-disabled 四个键，`

### 测试代码
活代码：
- `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesT35DefaultsTest.java:75`：`	void hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget() {`
- `…AiPropertiesT35DefaultsTest.java:78,81,84`：`assertThat(defaults.getHitl().getConfirmTimeout())` / `getFrontendToolTimeout()` / `getConfirmTimeout()`（链式断言）
- `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java:178`：`return gate(properties -> properties.getHitl().setFrontendToolTimeout(Duration.ofSeconds(seconds)));`
- `…ConfirmGateFrontendTimeoutTextTest.java:182`：`return gate(properties -> properties.getHitl().setConfirmTimeout(Duration.ofSeconds(seconds)));`

注释/javadoc：
- `AiPropertiesT35DefaultsTest.java:29,30`（类 javadoc：`app.ai.hitl 拆键后的默认值与上限关系`…）
- `ConfirmGateFrontendTimeoutTextTest.java:59,65`（类 javadoc）
- `ConfirmGateFrontendTimeoutTextTest.java:111,161`（代码内字符串·断言消息）：`"文案秒数必须等于注入的 app.ai.hitl.frontend-tool-timeout（硬编码常量过不了两个用例）"` / `…app.ai.hitl.confirm-timeout…`

### spike 模块（`com.example.spike.hitl`，独立 PoC，非主干）
- 活代码：`spike/sp01ab/.../SpikeToolCallingManager.java:10-12`（import）、`bus/EventHub.java:9`、`bus/Heartbeat.java:8`、`web/SpikeController.java:11-13`、`hitl/ConfirmDecision.java:1`、`hitl/Gate.java:1`、`hitl/SessionState.java:1`（package 声明）
- 活代码：`spike/sp02/.../RunStore.java:39`（`public static final String HITL_PREFIX = "sp02:hitl:";`）、`:79`（`public String hitlKey(String runId)`）、`:132,161,162,170`（键使用）、`Sp02ToolCallingManager.java:100`、`ExplicitLoopRunner.java:301`、`ToolCallExecutor.java:122`、`Sp02Controller.java:302,353,388,491`
- 注释：`RunSnapshot.java:15`、`RunStore.java:26`、`Sp02ToolCallingManager.java:31`、`RunStore.java:157`（`// ---------------- hitl / pending ----------------`）

---

## 4. 密钥泄露特征扫描

**严格正则 `(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*['\"][^'\"$\{]{8,}` → 2 处命中，均为 `<...>` 占位符（按排除规则应剔除）：**
- `backend/config/application.yml.example:7`（注释）：`# 也可以用环境变量代替：$env:DEEPSEEK_API_KEY = "<你的密钥>"`
- `backend/config/application.yml.example:12`（活配置·示例文件）：`      api-key: "<在此填入你的 DeepSeek API Key>"`

**实际密钥字面量扫描（补充）：**
- `git grep -cE "sk-[A-Za-z0-9]{8,}"` → 4 处，**全部为 "task-" 子串误报**：`TaskConditionJsonTest.java:33`（`jdbc:h2:mem:dc14-task-condition`）、`docs/evidence/baseline-combined-附件-B1-导出全链路原文.txt:136`（`b1-8b-task-overview`）、`docs/research/对话时序混排-业界调研.md:107,255`（URL `…/a2a-task-lifecycle/`）。
- `git grep -ncE "Bearer [A-Za-z0-9._-]{16,}"` → 0。

**宽松匹配（`(api[_-]?key|apikey|secret|token|password|passwd|pwd)\s*[:=]\s*['\"]?`，共 94 处）中值非空的全部为掩码/占位，逐处列代表（无真值）：**
- `backend/src/main/resources/application.yml:23`：`    password:`（空值，按规则排除）
- `backend/src/main/resources/application.yml:91`：`      api-key: ${AI_API_KEY:${DEEPSEEK_API_KEY:}}`（env 占位）
- `.github/workflows/ci.yml:168`：`          AI_API_KEY=ci-stub-placeholder \`
- `README.md:67`：`$env:AI_API_KEY = "***"`
- `frontend/.env.example:10`：`VITE_SPREADJS_KEY=`（空值）
- `docs/evidence/G1-main基线构建验证.md:10,22,95,148,238,380`；`GC`/`GF`/`S4x`/`V3`/`V4`/`M1`/`M2` 系列证据文档中 `AI_API_KEY=***` / `DEEPSEEK_API_KEY=***` / `AI_API_KEY=dummy` / `ci-stub-placeholder` / `AI_API_KEY="sk-…"`（占位引用，非真值）——如 `docs/evidence/M2-T2b-施工与验证-K2.8.md:186` 仅以 `sk-…` 省略号形式引用。
- `spike/sp02/scripts/sp02-experiment.mjs`：4 处形如 `apiKey` 的键名赋值（脚本内变量名/占位），无字面密钥值。

---

## 5. 盘符绝对路径（`[A-Za-z]:[\\/]`、`<DRIVE>\\`/`<DRIVE>\\`/`<DRIVE>\\` 等，在 .md/.java/.ts/.vue/.mjs/.ps1/.yml/.json 内容内）

**内容里出现的绝对盘符路径：1 处**
- `scripts/ci/check-docs.mjs:89`（注释）：`// 正则两条与 scan-lib.sh 的 scan_rule_path 逐字同构（<DRIVE>/… 与 <DRIVE>/… 两种形态）；`

**同主题但非"实际路径"的命中（扫描器正则定义文本，逐处列出供判定）：**
- `scripts/ci/check-docs.mjs:91`（代码·正则定义）：`  /(^|[^A-Za-z0-9_])[A-Za-z]:[\\/][-A-Za-z0-9_.\\/]{1,60}/g,`
- `scripts/ci/check-docs.mjs:92`（代码·正则定义）：`  /(^|[^A-Za-z0-9_/])\/[cdefgh]\/[-A-Za-z0-9_.\\/]{1,60}/g`
- `scripts/githooks/scan-lib.sh:91`（代码·正则定义）：`    "(^|[^$SCAN_W])[A-Za-z]:[\\\\/][-${SCAN_A}_.\\\\/]{1,60}" \`
- `scripts/githooks/scan-lib.sh:92`（代码·正则定义）：`    "(^|[^$SCAN_W/])/[cdefgh]/[-${SCAN_A}_.\\\\/]{1,60}"`

**用宽正则（`[A-Za-z]:[\\/]` 不限左界）产生的纯误报（正文实为 `file:/`、`file:///`，非盘符路径）：**
- `backend/src/test/java/com/example/configmgr/ai/tools/ToolScopeTagSpaceGuardTest.java:121`：`    /** {@code file:/…/com/example/configmgr/ai/tools/AiTools.class} → {@code com.example.configmgr.ai.tools.AiTools}。 */`
- `docs/spike/logs/sp02/E5-summary.json:17`：`"stack": "TypeError: …\n    at scenarioE5 (file:///<REPO_ROOT>/spike/sp02/scripts/sp02-experiment.mjs:675:40)…"`

未跟踪文件 `AiToolsListTasksTruncationTest.java`：盘符路径无命中。

---

## 6. Windows 用户名痕迹（只看文件内容）

- `docs/merge/01-分支评价报告.md:3`：`> 评价对象：<REPO-URL> 的 6 个分支（main、claude-opus-5.5、claude-opus-5.5+deepseek-v4-pro、deepseek-v4-pro、glm-5.3、kimi-k3）`
- 误报（`<USER>` 是包名子串）：`frontend/yarn.lock:1431`（`tinyglobby "^0.2.11"`）、`:1481`（`tinyglobby@^0.2.11, tinyglobby@^0.2.13:`）、`:1483`（`resolved "…/tinyglobby/-/tinyglobby-0.2.17.tgz#…"`）、`:1534`（`tinyglobby "^0.2.13"`）
- `liuxiang` / `<USER>`（大写）：文件内容中**无命中**
- 非文件内容（仅供参考，按你的规则不算）：`git remote -v` → `origin <REPO-URL>`（仓库配置，非文件内容）

---

## 7. 内网 IP（84 处，11 个不同值：`<LAN_IP>`×34、`<LAN_IP>`×33、`<LAN_IP>`×21、`<LAN_IP>`×18、`<LAN_IP>`×12、`<LAN_IP>`×9、`<LAN_IP>`×7、`<LAN_IP>`×4、`<LAN_IP>`×2、`<LAN_IP>`×1、`<LAN_IP>`×1）

全部集中在测试夹具与证据产物；无 172.16-31/192.168 段命中，无 127.0.0.1/0.0.0.0/localhost 混入（已排除）。逐文件逐行（值 = 命中 IP）：

**Java 测试夹具（`gateway` 字段演示值）**
- `backend/src/test/java/com/example/configmgr/job/service/ImportFlowJobTest.java`：`:144`=<LAN_IP>、`:145`=<LAN_IP>、`:196`=<LAN_IP>、`:254`=<LAN_IP>
  - `:144` 原文：`                row("regionCode", "HE", "bandwidthMbps", "100", "gateway", "<LAN_IP>", "redundancy", "true"),`
- `backend/src/test/java/com/example/configmgr/job/service/PublishSemanticsTest.java`：`:66`=<LAN_IP>、`:74`=<LAN_IP>、`:87`=<LAN_IP>、`:107`=<LAN_IP>、`:129`=<LAN_IP>、`:130`=<LAN_IP>
  - `:107` 原文：`                dataJson("HE", "100", "<LAN_IP>"), staleSnapshot));`

**证据文档 / 录制产物**
- `docs/evidence/V2-glm范围内替换发布核验.md`：`:112,113,114`=<LAN_IP>；`:120,121,122`=<LAN_IP>；`:144`=<LAN_IP>（×2）；`:183,184`=<LAN_IP>；`:190,191,192`=<LAN_IP>；`:203,204,206,207,209,210`=<LAN_IP>；`:221,222,223`=<LAN_IP>；`:225,226`=<LAN_IP>；`:254,255,257,258`=<LAN_IP> — 上下文如 `"rows":[{"__scope":"REGION_NORTH","bandwidthMbps":"9999","gateway":"<LAN_IP>","redundancy":"true"}]`
- `docs/evidence/G2-glm种子并集移植.md`：`:301,302,303`=<LAN_IP>；`:305,306,307`=<LAN_IP>；`:309,310,311`=<LAN_IP>；`:313,314,315`=<LAN_IP> — 上下文如 `   rowKey=HB|100   {"regionCode":"HB","bandwidthMbps":100,"gateway":"<LAN_IP>","redundancy":true}`
- `docs/evidence/S43a-发布模块重写验证.md`：`:142`=<LAN_IP>；`:144,145`=<LAN_IP>；`:166,173`=<LAN_IP>；`:221,224`=<LAN_IP>；`:223`=<LAN_IP>
- `docs/evidence/V2-附件-compare-output.txt`：`:4,5,7,8,10,11`=<LAN_IP>；`:22,23,24`=<LAN_IP>；`:26,27`=<LAN_IP>
- `docs/evidence/V2-附件-compare2-output.txt`：`:4,5,7,8`=<LAN_IP>
- `docs/evidence/V2-附件-trace-R1-before.txt:22`=<LAN_IP>（同行为长 JSON，含 3 次 <LAN_IP>）
- `docs/evidence/V2-附件-trace-R1-after.txt:22`=<LAN_IP>（同行为长 JSON，2 次）
- `docs/evidence/V2-附件-trace-R2-before.txt:22`=<LAN_IP>（同行为长 JSON，3 次）
- `docs/evidence/V2-附件-trace-R2-after.txt:22`=<LAN_IP>（同行为长 JSON，3 次）
- `docs/evidence/V2-附件-trace-ALL-before.txt:22`=<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP> 混排（长 JSON，多次）
- `docs/evidence/V2-附件-trace-ALL-after.txt:22`=<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP> 混排（长 JSON，多次）
- `docs/evidence/V2-附件-trace-ALL-final.txt:22`=<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP>/<LAN_IP> 混排（长 JSON，多次）
- `docs/evidence/V2-附件-trace-publish.txt:19,20`=<LAN_IP>
- `docs/evidence/V2-附件-trace-publish2.txt:7,8`=<LAN_IP>
- `docs/evidence/V2-附件-catalog-configs.json:1`=<LAN_IP>（`"gateway":…"sampleValue":"<LAN_IP>"`）

无任何命中行带"示例"字样标注（均为种子/夹具/证据录制值，`REGION_EAST|华东地区` 等）。

---

## 8. 命名纪律：`main[-_ .]?v2`（`git grep -i`，任意分隔符含零分隔）

命中 6 处，**全部在 spike 脱敏脚本的正则字面量内**：

- `spike/sp01ab/scripts/sp01ab-sanitize.mjs:13`（代码）：`  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code[\\/]<REPO>[\\/]spike[\\/]sp01ab/g, '<MAIN_REPO>/spike/sp01ab'],`
- `spike/sp01ab/scripts/sp01ab-sanitize.mjs:14`（代码）：`  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code[\\/]<REPO>/g, '<MAIN_REPO>'],`
- `spike/sp01d/scripts/make-attachments.mjs:14`（代码）：`    .replace(/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+<REPO>/g, '<MAIN_REPO>')`
- `spike/sp01d/scripts/make-attachments.mjs:15`（代码）：`    .replace(/\/e\/temp\/ai-example-code\/<REPO>/g, '<MAIN_REPO>')`
- `spike/sp02/scripts/sp02-sanitize.mjs:14`（代码）：`  [/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+<REPO>[\\/]+spike[\\/]+sp02/gi, '<MAIN_REPO>/spike/sp02'],`
- `spike/sp02/scripts/sp02-sanitize.mjs:15`（代码）：`  [/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+<REPO>/gi, '<MAIN_REPO>'],`

`docs/`、`README.md`（根与各子目录）、`scripts/`（`scripts/*`、`scripts/ci/*`、`scripts/githooks/*`）、`.github/workflows/ci.yml`、代码字符串内：**无命中**（`<REPO>`、`<REPO>`、`<REPO>` 等变体同样无命中，因该正则为 `-i` 且分隔符可选）。

---

## 9. `frontend-tool-timeout` 与 `confirm-timeout` 全仓逐处

### `frontend-tool-timeout`（共 13 处）
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:39`（javadoc）：` * 或 {@code app.ai.hitl.frontend-tool-timeout}（前端工具，默认 120s）（ADR-2 上限约束）`
- `…ConfirmGate.java:175`（javadoc）：`	 * 前端工具回执等待上限（秒）：{@code app.ai.hitl.frontend-tool-timeout}（等机器）。`
- `…ConfirmGate.java:195`（javadoc）：`	 * 前端工具：阻塞等前端回灌结果。等待上限 = {@code app.ai.hitl.frontend-tool-timeout}`
- `…ConfirmGate.java:511`（javadoc）：`	 * 用户关掉表单时前端<b>没有合法动作</b>，挂起只能悬到 {@code app.ai.hitl.frontend-tool-timeout} 才由`
- `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:22`（javadoc）：` * {@code app.ai.hitl.confirm-timeout} 与 {@code app.ai.hitl.frontend-tool-timeout} 两键按场景给出：`
- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:501`（javadoc）：`     * 时间上限沿用既有前端通道口径（{@code app.ai.hitl.frontend-tool-timeout}，默认 120s）：用户长时间不提交即`
- `backend/src/main/resources/application.yml:121`（注释）：`      #   frontend-tool-timeout（等机器）：前端工具与 generative_form 的回执等待。`
- `backend/src/main/resources/application.yml:128`（活配置）：`      frontend-tool-timeout: 120s`
- `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java:52`（javadoc，写为 `{@code confirm-timeout 240s} / {@code frontend-tool-timeout 120s}`）
- `…ConfirmGateFrontendTimeoutTextTest.java:59`（javadoc）：` * {@code app.ai.hitl.confirm-timeout}（等人）与 {@code app.ai.hitl.frontend-tool-timeout}`
- `…ConfirmGateFrontendTimeoutTextTest.java:60`（javadoc）：` * （等机器）后，前端工具用例注入 {@code frontend-tool-timeout}、确认门用例注入`
- `…ConfirmGateFrontendTimeoutTextTest.java:111`（代码·断言消息字符串）：`                .as("文案秒数必须等于注入的 app.ai.hitl.frontend-tool-timeout（硬编码常量过不了两个用例）")`
- 另（同名前缀、非同一键）：`spike/sp01ab/src/main/resources/application.yml:22`（`frontend-tool-timeout-seconds: 60`）、`spike/sp02/src/main/resources/application.yml:23`（`frontend-tool-timeout-seconds: 120`）—— 键名带 `-seconds` 后缀，属 spike 自有键。

### `confirm-timeout`（共 26 处）
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:182`（javadoc）：`		 * 但"配置值本身要不要直接大于确认门键 {@code app.ai.hitl.confirm-timeout}"列为【待裁决】。`
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java:18`（javadoc）：` * 一轮对话占用一条线程直至终帧 —— 挂起等待（确认门 ≤ {@code app.ai.hitl.confirm-timeout}，`
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:38`（javadoc）：` * <li><b>超时</b>：等待超过 {@code app.ai.hitl.confirm-timeout}（确认门，默认 240s）`
- `…ConfirmGate.java:168`（javadoc）：`	/** 确认门等待上限（秒）：{@code app.ai.hitl.confirm-timeout}（等人，默认 240s）。 */`
- `…ConfirmGate.java:177`（javadoc）：`	 * <p>与确认门键（{@code confirm-timeout}）<b>刻意分开</b>：两者等的对象不同（机器回执 vs 人的决策），`
- `…ConfirmGate.java:196`（javadoc）：`	 * （等机器，默认 120s），与确认门键（{@code confirm-timeout}，等人，默认 240s）<b>分场景</b>各自取值`
- `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:22`（javadoc）：` * {@code app.ai.hitl.confirm-timeout} 与 {@code app.ai.hitl.frontend-tool-timeout} 两键按场景给出：`
- `backend/src/main/resources/application.yml:114`（注释）：`      #   confirm-timeout（等人）：确认破坏性操作时等人类决策。`
- `backend/src/main/resources/application.yml:127`（活配置）：`      confirm-timeout: 240s`
- `backend/src/main/resources/application.yml:137`（注释）：`      #   "配置值本身要不要直接大于 app.ai.hitl.confirm-timeout"列为待裁决。`
- `backend/src/test/java/com/example/configmgr/ai/config/AiPropertiesT35DefaultsTest.java:71`（javadoc）：`	 * <p>本用例是那条约束的机器化落点：日后若有人把 {@code confirm-timeout} 抬到 600s 而不同批抬高`
- `…AiPropertiesT35DefaultsTest.java:85`（代码·断言消息）：`				.as("上限关系（N-2）：confirm-timeout 必须严格小于 total-budget，否则确认门等不满自己的上限")`
- `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java:52`（javadoc）：` * {@code confirm-timeout 240s} / {@code frontend-tool-timeout 120s}）的上限`
- `…ConfirmGateFrontendTimeoutTextTest.java:59`（javadoc）：` * {@code app.ai.hitl.confirm-timeout}（等人）与 {@code app.ai.hitl.frontend-tool-timeout}`
- `…ConfirmGateFrontendTimeoutTextTest.java:61`（javadoc）：` * {@code confirm-timeout}，未注入的那个键保持类默认值`
- `…ConfirmGateFrontendTimeoutTextTest.java:161`（代码·断言消息）：`                .as("文案秒数必须等于注入的 app.ai.hitl.confirm-timeout（硬编码常量过不了两个用例）")`
- `docs/03-技术方案文档-v2.1.md:270`（文档·冻结原文+行尾注记）：`…并由 app.ai.hitl-timeout 设**上限约束**…〔行尾注记（2026-10-10 数字规格小批苞 A，只追加）：该键已于苞 A 拆分为 app.ai.hitl.confirm-timeout（240s）/ app.ai.hitl.frontend-tool-timeout（120s）；本行为冻结原文，不回改。〕`
- `docs/03-技术方案文档-v2.1.md:551`（文档·配置键表行）：`| app.ai.hitl-timeout | 确认门等待上限 | …〔行尾注记…confirm-timeout（240s）/ frontend-tool-timeout（120s）…〕|`
- `docs/03-技术方案文档-v2.1.md:642`（文档·风险表行）：`| **确认门保留在流式管道内，使"断流发现延迟"与"确认等待"绑定**（取舍条目） | …app.ai.hitl-timeout **上限约束**…〔行尾注记…〕|`
- `docs/M2-排期计划.md:233`（文档）：`…**行尾注记**：…拆分为 app.ai.hitl.confirm-timeout（240s）/ app.ai.hitl.frontend-tool-timeout（120s）…本轮实取 **240s**…`
- `docs/M2-排期计划.md:291`（文档）：`…**完成留痕**：…拆为 app.ai.hitl.confirm-timeout（**等人**）与 app.ai.hitl.frontend-tool-timeout（**等机器**…）…`
- `docs/M2-排期计划.md:371`（文档）：`> **N-6 —— 合成帧 timeoutSeconds: 120 与轮询 12 × 500ms**…苞 A 把 app.ai.hitl 拆为 confirm-timeout（现 240s）与 frontend-tool-timeout（现 120s）之后，帧内的 120 不再对应任何配置键…`
- `docs/M2-排期计划.md:383`（文档）：`> - **MINOR-1（后端 · 苞 A）挂起双键取值缺「配置绑定」断言锁**：AiPropertiesT35DefaultsTest#hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget…若**只改 application.yml**（例如 confirm-timeout 改为 300s 而不动类默认值），上述用例**全部照绿**…`
- `docs/s4/S4.2-AI-Runtime-合流规格.md:69`（文档·规格表）：`| app.ai.hitl.confirm-timeout | 240s | 确认门（等人）超时；裁决③拆键后独立于前端工具键。**过渡值**：目标 600s，可达上界受 total-budget 300s 约束…（R-99③） |`
- `docs/s4/S4.2-AI-Runtime-合流规格.md:70`（文档·规格表）：`| app.ai.hitl.frontend-tool-timeout | 120s | 前端工具（含 generative_form，等机器）回执等待；裁决③拆键后与确认门分场景。降至 60s 的前置 = 前端折算面专项（P-1 / R-99②） |`
- `frontend/src/components/SpreadGrid.vue:96,97`（代码·注释）：` * 后端"前端工具"挂起上限（app.ai.hitl.frontend-tool-timeout，默认 120s；裁决③拆键后与` / ` * 确认门键 app.ai.hitl.confirm-timeout 240s 分场景），保证"前端先失败、先给出可读错误"，`
- `frontend/src/types/ai.ts:102`（代码·注释）：`  /** 前端工具挂起上限（秒；app.ai.hitl.frontend-tool-timeout 的折算值，与确认门键分场景） */`
- `frontend/src/utils/frontend-tools.ts:165,166,219,265`（代码·注释）：`:165` `…后端对前端工具挂起的等待上限是 app.ai.hitl.frontend-tool-timeout`；`:166` `（默认 **120s**，裁决③拆键后**只供前端工具**，与确认门键 app.ai.hitl.confirm-timeout`；`:219` `（app.ai.hitl.frontend-tool-timeout，可在配置里改）。以后端值为基算出预算…`；`:265` `…而服务端上限来自 app.ai.hitl.frontend-tool-timeout`
- `scripts/verify-e2e.ps1:1395`（代码·断言）：`    if ([int]$f.timeoutSeconds -le 0) { throw "frontend_tool_request timeoutSeconds=$($f.timeoutSeconds)，期望 > 0（不硬编码 120，与 app.ai.hitl.frontend-tool-timeout 解耦）" }`
- 另（spike 自有键 `-seconds` 后缀）：`spike/sp01ab/src/main/resources/application.yml:24`（`confirm-timeout-seconds: 30`）、`spike/sp02/src/main/resources/application.yml:25`（`confirm-timeout-seconds: 120`）

---

## 10. `scripts/` 内 `5000` / `3000` / `3500`，及 `m2t2a-smoke.mjs` 的 `AWAIT_MS`/`deriveAwaitCap`/`totalBudget`/`330`

### `scripts/` 内三个数字（全部仅落在 `scripts/verify-e2e.ps1`；`scripts/m2t2a-smoke.mjs`、`scripts/ci/*`、`scripts/README.md`、`scripts/githooks/*` 均无命中）
含上下文 2 行：

```
1307:#                                                       ← 上下文
1308:# 读窗下界由**被观察方的节拍**决定，不是随手取的整数：挂起期心跳每 2s 一帧
1309:# （ConfirmGate.HEARTBEAT_SECONDS = 2），故 3000ms 窗口只兜得住 1 个心跳周期，抖动一次即漏帧     [3000]
1310:# （漏帧会让"无新帧"断言假绿）。判据取「窗口 ≥ 2 个心跳周期 + 抖动余量」，即 2s × 2 + 1s = 5000ms；[5000]
1311:# 参照业界依据 §2（heartbeat < min(代理超时) 实践公式）与 §12（K8s periodSeconds ×
1312:# failureThreshold 的"连续 N 次失败才判死"模式）。2026-10-10 数字规格清点裁决⑤：3000 → 5000。      [3000][5000]
1313:# 三处调用点本轮一律取 5000（GF4-A3 / GF5-A4 / TC23④）—— 原 3500ms 同样不达标（1.75 个心跳周期）， [5000][3500]
1314:# 按同一判据同改（2026-10-10 小批苞 F，登记见 docs/M2-排期计划.md 去向表 R-104）。
1315:function Read-Sse-Brief($path, $millis) {
```
活代码调用点（均为 5000）：
```
1816:    # A3 拒绝路径不发帧不唤醒：5000ms（5s）窗口内重挂 events 无 frontend_tool_result
1817:    # （裁决 #3：该窗口读失败会显式抛出，不再退化为空集让本断言空转）
1818:    $win = Read-Sse-Brief "/api/ai/events/$($script:gf4Run)?lastSeq=$lastSeqGf4" 5000
...
1888:        $w = Read-Sse-Brief "/api/ai/events/$($pair[0])?lastSeq=$($pair[1])" 5000
...
2203:    $brief23 = Read-Sse-Brief "/api/ai/events/$run23`?lastSeq=$([int]$cur23.archiveMaxSeq)" 5000
```
（`3000` 仅出现在 `:1309`、`:1312` 注释；`3500` 仅出现在 `:1313` 注释；3 个调用点 `:1818`、`:1888`、`:2203` 取 `5000`。）

### `scripts/m2t2a-smoke.mjs`
```
189:// 等待上界不写死：由服务端自报的轮次预算（/api/ai/health 的 resilience.totalBudget）    [totalBudget]
196:const FALLBACK_AWAIT_MS = 330_000                                                          [AWAIT_MS][330]
208:/** 事实源 = 已在跑的服务端 /api/ai/health：读 resilience.totalBudget（不依赖任何新增端点）。 */  [totalBudget]
209:async function deriveAwaitCap() {                                                           [deriveAwaitCap]
210:  const fallbackSource = 'FALLBACK_AWAIT_MS（app.ai.resilience.total-budget 缺省 300s + 30s 余量）'  [AWAIT_MS]
215:      return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }                            [AWAIT_MS]
218:    const raw = body?.resilience?.totalBudget                                                 [totalBudget]
221:      console.warn('[阶段3a] /health 的 resilience.totalBudget 无法解析（' + JSON.stringify(raw) + '），等待上界回落具名常量')  [totalBudget]
222:      return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }                            [AWAIT_MS]
224:    return { capMs: budgetMs + 30_000, capSource: `/api/ai/health resilience.totalBudget=${raw}` }  [totalBudget]
227:    return { capMs: FALLBACK_AWAIT_MS, capSource: fallbackSource }                              [AWAIT_MS]
320:    //    硬上限由服务端自报的轮次预算推导（见 deriveAwaitCap），不再写死 240s；                  [deriveAwaitCap]
330:    const { capMs: AWAIT_CAP_MS, capSource: AWAIT_CAP_SOURCE } = await deriveAwaitCap()          [deriveAwaitCap]
334:        deadlineMs: Date.now() + AWAIT_CAP_MS,
339:      'capMs =', AWAIT_CAP_MS, 'capSource =', AWAIT_CAP_SOURCE, 'seen =', JSON.stringify(awaited.seenTypes))
344:        awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE, seenTypes: awaited.seenTypes
348:        awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE, seenTypes: awaited.seenTypes
350:      throw new Error(`阶段3a 未收到 frontend_tool_request（awaitStopReason=${awaited.stopReason}，capMs=${AWAIT_CAP_MS}）`)
356:      awaitStopReason: awaited.stopReason, awaitCapMs: AWAIT_CAP_MS, awaitCapSource: AWAIT_CAP_SOURCE,
```
`m2t2a-smoke.mjs` 内 `5000`/`3000`/`3500` 无命中（文件内数字为 `330_000`、`30_000`、`240` 等）。
