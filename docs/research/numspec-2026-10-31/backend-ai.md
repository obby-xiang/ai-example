# 后端 AI 面数字规格清单（backend-ai）

> 任务：全项目数字配置/常量逐一清点（后端 AI 子域）。本文档只**登记与审视**，不改任何生产/测试代码或配置。

## 0. 元信息

| 项 | 值 |
|---|---|
| 仓库 | `<REPO_ROOT>`（主仓 `main`） |
| 扫描时 HEAD | 开始 `405e74ebf96b3340c9443c6e3087708814e996f6` → 结束 `c7e2497d537f17ef1d652550381fa00a725ee20f`（扫描期间并行 worker 提交了 `fix(ai): 固化决策身份头与前端类型契约`，2026-10-31 01:32:39） |
| 扫描时点 | 2026-10-31 01:33（+0800），收尾复核约 01:40 |
| 旧基线 | `docs/evidence/补档-NUMSPEC-清单总账.md`（2026-10-10，HEAD `6ee96c3`）—— **只作 ID 对照，所有现值按本次磁盘现态重查** |
| 扫描范围 | `backend/src/main/java/com/example/configmgr/ai/**`（229 个 `.java`，合计 75,147 行）；`backend/src/main/java/com/example/configmgr/config/AiProperties.java`（**不存在**，`app.ai` 唯一绑定在 `ai/config/AiProperties.java`）；`backend/src/main/resources/application*.yml` 的 `app.ai` / `spring.ai` 段（及与 AI 上传链路直接耦合的 `spring.servlet.multipart`）；`backend/src/main/resources/ai/tools.yaml`；`backend/src/main/resources/db/migration/**` 与 `db/postgresql/**` 中 **`ai_*` 表**的 DDL 数值（DEFAULT / 列宽 / 精度标度）；后端提示词/工具描述内的行为数字 |
| 任务边界 | 只读代码与配置；未改生产/测试；未做 git 写操作；未运行测试/服务/浏览器。唯一新增文件 = 本文件 |
| 旧基线的口径沿用 | 分类词表、`配置键 / 硬编码 / 框架缺省`、`有依据 / 部分依据 / 裸数字` 三维沿用旧总账 §0.1，便于逐行对照 |

### 0.1 并行改动登记（必读）

扫描时工作树**有并行改动**，且扫描期间 HEAD 前进了一次（见 §0 表）。开工时 `git status --porcelain` = 29 `M` / 19 `D` / 5 `??`；收尾复核 = 25 `M` / 10 `D` / 6 `??`（部分改动已被并行 worker 提交）。落在本专项扫描面的仍是 11 个 `ai/**` 主源文件 + 1 个删除 + `application.yml`：

> 关键复核：`git diff 405e74e c7e2497 -- backend/.../ai backend/src/main/resources/application.yml backend/src/main/resources/ai` **无任何输出**（本专项面无提交差异）；`application.yml` 的工作树 diff 仍只有注释删除（`grep '^[+-].*[0-9]'` 空），`AiProperties.java` 仍为 `6 +----`（两处注释）。故本清单的现值在收工时刻仍成立。

| 状态 | 文件（本专项面） | 对数字规格的影响 |
|---|---|---|
| M | `ai/config/AiProperties.java` | 仅删除两处退役键注释（`confirm-timeout`、`AiRetiredKeysWarner`），**无数字取值变化** |
| M | `ai/gate/ConfirmGate.java` | 新增 park 到期收敛（`EXPIRY_CLAIM_STALE_MS`、`scheduleParkedFrontendExpiry`）；`dispatchRetryDelay` 缺省回落值 2s/30s 为原有逻辑 |
| M | `ai/run/SseChatEmitter.java` | 新增 attach 本地保活（`DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS = 5000`） |
| M | `ai/run/StartupResumeRunner.java` / `RunRegistry.java` / `CancelledRunFinalizer.java` / `LeaseService.java` | 新增扫描/收敛/关闭路径；未新增可调数字键（`RunRegistry` 的 30m/1m、`LeaseService` 的 1000ms 为既有） |
| M | `ai/session/AiSessionPurgeService.java`、`ai/web/AiController.java`、`ai/context/ContextSummaryClient.java` | 逻辑/注释改动，数字现值未变 |
| M | `backend/src/main/resources/application.yml` | 只删注释（`AiRetiredKeysWarner`、`confirm-timeout`、`app.file.key-migration.enabled`），**`app.ai.*` / `spring.ai.*` 取值一个未变** |
| D | `ai/config/AiRetiredKeysWarner.java` + 其测试 | 退役键 WARN 机制被删；`AiRetiredKeysWarner` 曾被旧总账 B3 段引用（不再存在） |
| D | `file/*` 迁移类、`db/migration/V5,V8,V9,V10` | 不在本专项面；仅提示：`app.file.key-migration.enabled` 已从 yml 删除 |

> 结论：并行改动**没有改动任何本清单登记的数值**；但「退役键 WARN」这条守卫已消失（见 §6 待裁项 P-6）。

## 1. 汇总

- 登记条数：**289 行 / 288 项独立**（主表 A+B+C = 269，附录 7.1 = 10 + 附录 7.4 DDL = 10；`^| N-AI-` 机械计数，ID 无重号）。**N-AI-266 为交叉引用（并入 N-AI-262），不计入独立项**，口径见 §8.4。
- 分布：配置键（yml 显式 67）/ 配置键（代码缺省 11）/ 硬编码常量 / 提示词与工具描述契约数字（逐节计数见 §8.4）。
- 重点待裁项见 §6（**9 条**：P-1…P-9）。
- 可能遗漏面见 §8.3（不写「保证无遗漏」：本次为「机械 grep + 逐条人工复核」，但 AI 包 229 文件、75,147 行，仍可能漏掉纯逻辑字面量）。

## 2. 表 A：`application.yml` 显式数字键（`spring.ai.*` + `app.ai.*`）

> 所有行 `可调性 = 配置键`。单位遵循 yml 写法（`s/m/h`、裸数字 = 计数/字节/字符/token）。

| ID | 分类 | 文件:行 | 配置键 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-001 | 模型 | `application.yml:119` | `spring.ai.openai.chat.options.temperature` | `0.2` | 采样温度；`AiProviderRegistry#buildOptions` 以本段 options 为基底（env 合成行与 DB 空表时逐值等价），DB `ai_setting.default.temperature` 可覆盖 | 配置键 | 裸数字（yml 与类注均无数值论证；旧账同判） | 保留；若要论证，按「工具型 Agent 低温」给依据 | B1-05 |
| N-AI-002 | 模型 | `application.yml:120` | `spring.ai.openai.chat.options.max-tokens` | `4096`（token/次） | 单轮输出上限；同 `buildOptions` 基底，DB `default.maxTokens` 覆盖；与 `context.reserve-tokens 13000` 自洽（128K 下 L3 余额 5.75K > 4096） | 配置键 | 部分依据（自洽性有算式，4096 本身无模型对照） | 保留；若换小窗口模型须重算 reserve 与 L3 余额不等式 | B1-06 |
| N-AI-003 | 轮询 | `application.yml:142` | `app.ai.config.reload-interval` | `1s` | 读路径版本比对最小间隔（`AiConfigService`，Redis `ai:cfg:ver` 一 GET；不可用时一次 DB 主键查询）；单测置 0 | 配置键 | 部分依据（「稳态至多 1 次/秒/实例」是设计目标，非实测） | 保留 | — |
| N-AI-004 | 超时 | `application.yml:148` | `app.ai.config.probe-timeout` | `5s` | `/test` 与 `/models/fetch` 单次上游超时（`AiConnectionProbe`）；不按聊天预算取值 | 配置键 | 部分依据（注释给「跨公网握手 + /models 常见耗时」，无实测） | 保留；建议补一次实测（见 P-7） | — |
| N-AI-005 | TTL | `application.yml:150` | `app.ai.config.models-cache-ttl` | `10m` | 远端模型清单缓存 TTL（`ai:cfg:models:<providerId>`，不落库） | 配置键 | 裸数字（只引设计稿 §3.1，无数值论证） | 保留；可配 5–30m 区间，无风险 | — |
| N-AI-006 | TTL | `application.yml:153` | `app.ai.session-ttl` | `24h` | 会话滑动 TTL；run 级外置键（快照/待决/台账/帧/序号/心跳/取消/帧归档/上下文三件套/安全台账/幂等）同取本值，每次写/心跳整额续期 | 配置键 | 有依据（2026-10-20 用户裁决 6h→24h；类注 + `SessionKeyRegistry` 登记） | 保留 | B1-07 |
| N-AI-007 | 缓冲 | `application.yml:158` | `app.ai.memory.max-messages` | `40`（条） | `MessageWindowChatMemory` 窗口大小；记忆窗口批后为兜底（预算为主判据） | 配置键 | 有依据（调研区间 40–60 取下沿；与 min 同值 ⇒ 条数固定 40） | 保留 | B1-08 / B3-03 |
| N-AI-008 | token | `application.yml:163` | `app.ai.memory.token-budget` | `48000`（token） | 读入历史的 token 预算（`MemoryWindow` 主判据）；0 = 关闭预算 | 配置键 | 有依据（调研区间 32k–48k 取上限，工具结果偏大） | 保留；换模型窗口时复核 | B3-01 |
| N-AI-009 | 缓冲 | `application.yml:168` | `app.ai.memory.min-messages` | `40`（条） | 预算淘汰的条数下限（`MemoryWindow`）；min=max=40 ⇒ 条数固定、超预算靠清旧工具结果 | 配置键 | 有依据（2026-10-22 用户裁「业界主流的来吧，40」） | 保留 | B3-02 |
| N-AI-010 | 容量 | `application.yml:175` | `app.ai.context.limit-tokens` | `128000`（token） | 上下文上限的**过渡/回退键**（`ContextLimitResolver`）；真源 = `ai_model.max_context`；0 = 未知降级 | 配置键 | 有依据（默认模型 128K 窗口；L-89 起仅作回退） | 保留（回退值）；换默认模型须同步 | B1-41 |
| N-AI-011 | 缓冲 | `application.yml:178` | `app.ai.context.reserve-tokens` | `13000`（token） | 输出+工具回环预留；比例阈值分母 = limit − 有效预留；`ContextTrigger#effectiveReserve` 再收口为 `min(本值, limit/2)` | 配置键 | 有依据（调研 §① Claude Code 13K + 自洽算式） | 保留 | B1-42 |
| N-AI-012 | 百分比 | `application.yml:181` | `app.ai.context.l1-percent` | `70`（%） | L1 工具结果清除触发线（`ContextTrigger#decide`） | 配置键 | 有依据（调研 Q2 70–75 取中值） | 保留 | B1-43 |
| N-AI-013 | 百分比 | `application.yml:182` | `app.ai.context.l2-percent` | `80`（%） | L2 滑窗+递归摘要触发线；也是 `ContextCompactor` 前缀主导判据 | 配置键 | 有依据（80–85 取中值） | 保留 | B1-44 |
| N-AI-014 | 百分比 | `application.yml:183` | `app.ai.context.l3-percent` | `95`（%） | L3 硬截断触发线 | 配置键 | 有依据（95 + 自洽：剩 5.75K > 4096） | 保留 | B1-45 |
| N-AI-015 | 百分比 | `application.yml:185` | `app.ai.context.hysteresis-percent` | `60`（%） | 压缩后滞回解锁线（`ContextTrigger#hysteresisUnlocked`）；启动期校验 ≤ l1 | 配置键 | 有依据（调研 Q4） | 保留 | B1-46 |
| N-AI-016 | 缓冲 | `application.yml:187` | `app.ai.context.keep-tool-pairs` | `5`（对） | L1 不清理的最近工具结果数（按 response 条数计）；`MemoryWindow` 同源 | 配置键 | 有依据（调研 3–5 取上限） | 保留 | B1-47 |
| N-AI-017 | 缓冲 | `application.yml:189` | `app.ai.context.keep-recent-turns` | `6`（轮） | L2 逐字保留轮数（`RollingSummarizer` / `HardTrim`） | 配置键 | 有依据（调研 6–8 取下限） | 保留 | B1-48 |
| N-AI-018 | token | `application.yml:191` | `app.ai.context.chunk-tokens` | `8000`（token） | 递归摘要分块上限（`RollingSummarizer`）；启动期校验 < reserve | 配置键 | 有依据（设计稿：必须小于 reserve） | 保留 | B1-49 |
| N-AI-019 | 超时 | `application.yml:193` | `app.ai.context.summary-timeout` | `60s` | 一次摘要请求硬上限（`ContextSummaryClient`）；到点中断→熔断 | 配置键 | 部分依据（设计稿缺省，无压测） | 需压测（与 30s 首包同量级 + 分块余量） | B1-50 |
| N-AI-020 | 熔断 | `application.yml:195` | `app.ai.context.fuse-threshold` | `3`（连续失败） | `CompactionFuse` 连续失败即 open | 配置键 | 有依据（调研「连续 3 次」） | 保留 | B1-51 |
| N-AI-021 | 熔断 | `application.yml:196` | `app.ai.context.fuse-cooldown` | `10m` | 熔断冷却窗（到点半开一次，不升指数） | 配置键 | 部分依据（设计稿缺省，无数据） | 保留 | B1-52 |
| N-AI-022 | 超时 | `application.yml:208` | `app.ai.hitl.frontend-tool-timeout` | `120s` | **只**管 6 个即时执行类前端工具的回执兜底（`ConfirmGate#suspendWaitSeconds`）；等人类工具与确认门无上限 | 配置键 | 有依据（业界 §6 60s 常见、Vercel 5–60s；本仓维持 120s 的前端折算约束已写明） | 保留（降 60s 需先改前端回灌折算面，登记为 P-1） | B1-09 |
| N-AI-023 | 超时 | `application.yml:215` | `app.ai.tool.exec-timeout` | `120s` | 单次后端工具执行上限（`SpToolCallingManager#executeWithTimeout`）；到点 `TOOL_TIMEOUT/executed=false`，轮继续 | 配置键 | 有依据（决策看板 :16） | 需压测（长任务 opt-in 免超时未做，见 P-3） | B2-02 |
| N-AI-024 | 池 | `application.yml:218` | `app.ai.suspend.pool-size` | `20`（线程） | 挂起专用有界池容量（`AiExecutorConfig#aiRunExecutor`，队列 0 + AbortPolicy ⇒ 饱和 503）；同时派生工具超时池容量 | 配置键 | 有依据（压测证据 S1.3：20 受理 / 25 路 5 拒绝） | 保留；容量联动约束（20×2+8 ≤ boundedElastic）纳入压测 | B1-10 |
| N-AI-025 | 超时 | `application.yml:233` | `app.ai.resilience.first-byte-timeout` | `30s` | 上游首包静默上限（`StreamWatchdog`） | 配置键 | 部分依据（自述「初值，待 §13.2 #2 压测」） | 需压测 | B1-11 |
| N-AI-026 | 超时 | `application.yml:234` | `app.ai.resilience.inter-event-timeout` | `90s` | 事件间静默上限（`StreamWatchdog`） | 配置键 | 部分依据（同上） | 需压测 | B1-12 |
| N-AI-027 | 重试 | `application.yml:235` | `app.ai.resilience.max-attempts` | `2`（次，首轮+1） | 单轮尝试次数（`ResilientChatService`）；退避见 N-AI-094…097 | 配置键 | 部分依据（类注只说明口径；0.5s×2+抖动来自旧批） | 保留 | B1-13 |
| N-AI-028 | 超时 | `application.yml:236` | `app.ai.resilience.total-budget` | `300s`（**段**预算） | 段预算 = 段起点→现在（挂起点段结束、恢复点重置）；看门狗 + `awaitSettled` 墙钟兜底 | 配置键 | 有依据（决策看板 :14） | 保留；与 E2E 等待窗口同源 | B1-14 |
| N-AI-029 | 步数 | `application.yml:237` | `app.ai.resilience.max-tool-steps` | `25`（步，累计） | 工具调用步数笼（`SpToolCallingManager`，跨挂起不重置）；到顶 `TOOL_STEP_LIMIT` + 收尾 | 配置键 | 有依据（决策看板 :14 + 依据件 Q1） | 保留 | B2-01 |
| N-AI-030 | 轮询 | `application.yml:242` | `app.ai.resume.startup-delay` | `8s` | 启动后延迟多久开始自动续跑（`StartupResumeRunner`） | 配置键 | 部分依据（注释只说明「等容器/Redis 就绪」） | 保留；可配 5–15s | B1-15 |
| N-AI-031 | 阈值 | `application.yml:249` | `app.ai.resume.zombie-age` | `180s` | 僵尸判据（`ResumeService`/`RunLiveness`）；启动期强制 ≥ max(exec 120s, inter-event 90s) + margin | 配置键 | 有依据（180 = 120 + 60，含 fail-fast 校验） | 保留；与 N-AI-023/N-AI-026 联动，改任一须复核 | B1-16 |
| N-AI-032 | 阈值 | `application.yml:250` | `app.ai.resume.zombie-margin` | `60s` | 僵尸阈值余量（`AiProperties#verifyZombieAge`） | 配置键 | 部分依据（说明余量用途，60 本身无数据） | 保留 | — |
| N-AI-033 | 阈值 | `application.yml:251` | `app.ai.resume.max-on-startup` | `10`（轮） | 单次启动扫描最多续跑轮数（`StartupResumeRunner`） | 配置键 | 部分依据（「防启动瞬间占满挂起池」定性） | 保留 | B1-17 |
| N-AI-034 | 轮询 | `application.yml:260` | `app.ai.resume.runtime-sweep-interval` | `120s` | 运行期死轮扫描周期（`StartupResumeRunner#sweep`；不参与判死，判死窗口恒为 zombie-age） | 配置键 | 部分依据（注释说明「只决定多久看一眼」） | 保留 | — |
| N-AI-035 | 池 | `application.yml:263` | `app.ai.resume.trigger-pool-size` | `2`（线程） | 续跑触发池线程数（`AiExecutorConfig#aiResumeTrigger`） | 配置键 | 部分依据（注释解释独立池理由，2 无论证） | 保留 | B1-18 |
| N-AI-036 | 队列 | `application.yml:264` | `app.ai.resume.trigger-queue-capacity` | `16`（任务） | 触发池有界队列（满即 Abort → `EXECUTOR_SATURATED`） | 配置键 | 部分依据（同上） | 保留 | B1-19 |
| N-AI-037 | 重试 | `application.yml:280` | `app.ai.park.max-dispatch-retries` | `15`（次） | park 后派发失败的有界重派发次数（`ConfirmGate` / `CancelledRunFinalizer`）；15 次 ≈ 360s 窗口 | 配置键 | 有依据（算式 2+4+8+16+30×11≈360s > 300s+60s） | 保留 | — |
| N-AI-038 | 退避 | `application.yml:281` | `app.ai.park.dispatch-retry-delay` | `2s` | 重派发首次间隔（指数 ×2） | 配置键 | 部分依据（算式见上，2s 起点无独立依据） | 保留 | — |
| N-AI-039 | 退避 | `application.yml:282` | `app.ai.park.dispatch-retry-max-delay` | `30s` | 重派发间隔封顶 | 配置键 | 部分依据（同上） | 保留 | — |
| N-AI-040 | TTL | `application.yml:288` | `app.ai.session.lock.ttl` | `10m` | 会话锁互斥窗口上界（持锁进程死后最久多久可接管）；watchdog 续期、终态释放 | 配置键 | 部分依据（语义已订正；10m vs 轮预算无等式，10m 本身无数据） | 保留；可评估 2–5×total-budget（现 2×300s=600s=10m，恰好 2 倍） | B1-20 |
| N-AI-041 | 轮询 | `application.yml:289` | `app.ai.session.lock.watchdog` | `30s` | 会话锁续期间隔（`SessionGate`） | 配置键 | 部分依据（TTL/20，容忍多次失败；无数值论证） | 保留 | B1-21 |
| N-AI-042 | 轮询 | `application.yml:316` | `app.ai.kb.refresh-interval` | `10m` | 知识库定时增量刷新（`KbService` scheduleWithFixedDelay） | 配置键 | 部分依据（只说明「sha 变才重建」） | 保留 | — |
| N-AI-043 | 分页 | `application.yml:318` | `app.ai.kb.top-k-default` | `5`（条） | `kb_search` 默认条数 | 配置键 | 部分依据（有服务端强截口径，5 无独立论证） | 保留 | — |
| N-AI-044 | 上限 | `application.yml:319` | `app.ai.kb.top-k-max` | `10`（条） | `kb_search` 服务端硬上限（工具描述同值） | 配置键 | 部分依据 | 保留 | — |
| N-AI-045 | 上限 | `application.yml:321` | `app.ai.kb.read-max-chars-default` | `4000`（字符） | `kb_read_doc` 默认字符数 | 配置键 | 部分依据（与 8000 回填上限同量级） | 保留 | — |
| N-AI-046 | 上限 | `application.yml:322` | `app.ai.kb.read-max-chars-max` | `8000`（字符） | `kb_read_doc` 服务端字符上限 | 配置键 | 部分依据（与 `result-budget` 8000 档同量级） | 保留 | — |
| N-AI-047 | 缓冲 | `application.yml:324` | `app.ai.kb.chunk-min-chars` | `300`（字符） | 切块合并下限（`MarkdownChunker`） | 配置键 | 部分依据（只给区间规则，300 无数据） | 保留 | — |
| N-AI-048 | 缓冲 | `application.yml:325` | `app.ai.kb.chunk-max-chars` | `800`（字符） | 切块硬上限（超长段落硬切） | 配置键 | 部分依据（同上） | 保留 | — |
| N-AI-049 | 阈值 | `application.yml:327` | `app.ai.kb.match-high-ratio` | `0.7`（相对分） | 命中档 high 阈值（对本次最高分取比） | 配置键 | 裸数字（无注释论证） | 保留；可配 | — |
| N-AI-050 | 阈值 | `application.yml:328` | `app.ai.kb.match-medium-ratio` | `0.4`（相对分） | 命中档 medium 阈值 | 配置键 | 裸数字 | 保留；可配 | — |
| N-AI-051 | TTL | `application.yml:335` | `app.ai.kb.cache.lock-ttl` | `30s` | 知识刷新权锁 SET NX PX 的 TTL（不续租） | 配置键 | 部分依据（防崩溃实例永久锁死） | 保留 | — |
| N-AI-052 | 轮询 | `application.yml:337` | `app.ai.kb.cache.version-poll-interval` | `30s` | 版本号轮询间隔（多实例发现刷新） | 配置键 | 部分依据（= lock-ttl，让位周期对齐） | 保留 | — |
| N-AI-053 | 超时 | `application.yml:341` | `app.ai.kb.http.connect-timeout` | `5s` | HTTP 知识源连接超时 | 配置键 | 裸数字（无注释） | 保留 | — |
| N-AI-054 | 超时 | `application.yml:342` | `app.ai.kb.http.read-timeout` | `10s` | HTTP 知识源读超时 | 配置键 | 裸数字 | 保留 | — |
| N-AI-055 | 字节 | `application.yml:357` | `app.ai.stream.byte-budget` | `67108864`（Byte = 64MiB） | 单轮事件日志字节软上限（`RedisFrameStore` 等效窗口 = 预算 ÷ (最大帧+256B)）；0 = 关闭 | 配置键 | 有依据（maxmemory 16GiB；20 轮×64MiB≈8%） | 保留；多实例下为「各实例观察之和」的近似界（类注已登记） | — |
| N-AI-056 | 轮询 | `application.yml:361` | `app.ai.stream.index-prune-interval` | `10m` | `ai:runs` 索引机会式惰性清理间隔（0 = 关闭）；O(索引大小) 故必须节流 | 配置键 | 部分依据（说明必须节流，10m 无数据） | 保留 | — |
| N-AI-057 | 缓冲 | `application.yml:369` | `app.ai.result-budget.model.max-rows` | `200`（行） | 工具结果回填模型前行数上限（`ToolResultLimiter`）；0 = 不限 | 配置键 | 有依据（G5 双通道对齐） | 保留 | B1-22 / B3-04 |
| N-AI-058 | 缓冲 | `application.yml:372` | `app.ai.result-budget.model.default-max-chars` | `20000`（字符） | 具名工具回填模型的默认字符档 | 配置键 | 有依据（业界 10k–25k token ≈ 4 万字符） | 保留；换小窗口模型时由 C13 剩余窗口二次收刀 | B3-05 |
| N-AI-059 | 缓冲 | `application.yml:374` | `app.ai.result-budget.model.fallback-max-chars` | `8000`（字符） | 未知/未注册工具兜底档 | 配置键 | 有依据（维持旧 8000 口径，保守） | 保留 | B1-23 / B3-06 |
| N-AI-060 | 字节 | `application.yml:384` | `app.ai.result-budget.ui.max-frame-bytes` | `65536`（Byte = 64KiB） | SSE 单帧 UTF-8 字节上限（`FrameSizeLimiter`；delta/reasoning 分片，result 字段级降采样）；启动下限 1024 | 配置键 | 有依据（T3-10 裁定 + 拆帧专项下限） | 保留 | B1-24 / B3-07 |
| N-AI-061 | 缓冲 | `application.yml:388` | `app.ai.result-budget.callback.max-chars` | `8000`（字符） | 前端工具回灌端点单条上限（超限截断+标记，不 400） | 配置键 | 有依据（G5 第三通道；拒绝回灌会让轮永久挂起） | 保留 | B3-08 |
| N-AI-062 | 上限 | `application.yml:396` | `app.ai.input.max-chars` | `8192`（UTF-16 码元） | 单条 message 上限（`AiController:376`；超限 400 MESSAGE_TOO_LONG，不截断） | 配置键 | 有依据（与前端 maxlength 同尺；8192 汉字 = 24576 字节仍放行） | 保留 | — |
| N-AI-063 | 速率 | `application.yml:403` | `app.ai.limit.session-per-minute` | `20`（次/分） | sessionId 维度滑动窗口额度（`AiRateLimiter`，Redis Lua） | 配置键 | 部分依据（「远超手速」定性 + E2E 21 次/全程的约束） | 保留 | — |
| N-AI-064 | 速率 | `application.yml:404` | `app.ai.limit.ip-per-minute` | `60`（次/分） | RemoteAddr 维度额度（兜「换 sessionId 绕会话额度」） | 配置键 | 部分依据（60 ≫ 20 的比例关系，非实测） | 保留；多实例经 LB 建议配 `trusted-proxies` | — |
| N-AI-065 | TTL | `application.yml:405` | `app.ai.limit.window` | `1m` | 滑动窗口长度（Lua ZSET 回收 + PEXPIRE）；启动期必须 > 0 | 配置键 | 部分依据（1 分钟与「次/分」键名对齐） | 保留 | — |
| N-AI-066 | 超时 | `application.yml:423` | `app.ai.sse.write-timeout` | `15s` | 单帧写出预算（`SseWriteBudget`）；超预算摘除订阅者、前端带 lastSeq 重挂；启动期必须 > 0 | 配置键 | 有依据（tomcat 字节码反证写侧 ≈116s，是 15s 的 8 倍宽） | 保留 | B1-27 |

### 2.1 与 AI 上传链路直接耦合的 `spring.servlet.multipart`（跨专项引用）

| ID | 分类 | 文件:行 | 配置键 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-067 | 上限 | `application.yml:33-34` | `spring.servlet.multipart.max-file-size` / `max-request-size` | `5MB` / `6MB` | 表单附件上传（`GenerativeFormAttachmentController.MAX_ATTACHMENT_BYTES = 5MB` 同口径；1MB 余量给 multipart 边界/表单字段） | 配置键（框架） | 有依据（注释写明与单件上限同口径） | 保留；改单件上限须同改两处 | — |

## 3. 表 B：`app.ai.*` 代码缺省键（yml **不**显式写出，值取 `AiProperties` 字段初值）

> 依据 = `application.yml:290-303` 的「代码缺省键」显化段（该段刻意不写值）。`可调性 = 配置键（代码缺省）`；运维可写 yml/env 覆盖，但配置面 grep 不到现值。

| ID | 分类 | 文件:行 | 配置键 / 常量 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-068 | 池 | `ai/config/AiProperties.java:1067` | `app.ai.sse.delivery-pool-size` | `8`（线程） | SSE 共享投递池工作线程数（`RunRegistry` 装配 → `SseChatEmitter.configureSharedDeliveryPool`；`/api/ai/health.sseDelivery.poolSize` 回显） | 配置键（代码缺省） | 有依据（T3-5 压测：8；原 4 在 ≥4 慢连接时饿死健康订阅者） | 保留；改值须复核「慢连接数 < 池容量」等式 | B1-25 / B1-62 |
| N-AI-069 | 队列 | `ai/config/AiProperties.java:1082`（常量）、`:1094`（字段） | `app.ai.sse.delivery-queue-capacity` | `256`（帧/订阅者） | 每订阅者**实时**段队列容量（回放初始批豁免）；溢出即摘除；不变量 < `RunStore.EVENT_WINDOW` 3000 | 配置键（代码缺省） | 有依据（T3-5 §S5.2：3000 回放 + 100 实时不误摘，第 257 条才摘） | 保留 | B1-26 / B1-63 |
| N-AI-070 | TTL | `ai/config/AiProperties.java:1284` | `app.ai.lease.ttl` | `30s` | 续跑权（fencing）租约有效期 = 续租 ×3；`RunStore`/`LeaseService`/`ConfirmGate` 续租与抢租 | 配置键（代码缺省） | 部分依据（「TTL/3 容忍两次续租失败」是定性；30s 无实测） | 保留；与 N-AI-071 成 3:1 | B1-57 |
| N-AI-071 | 轮询 | `ai/config/AiProperties.java:1287` | `app.ai.lease.renew-interval` | `10s` | 租约持有者续租间隔（`LeaseService#markDriving` scheduleAtFixedRate） | 配置键（代码缺省） | 部分依据（= TTL/3） | 保留 | B1-58 |
| N-AI-072 | 退避 | `ai/config/AiProperties.java:1290` | `app.ai.lease.retry-delay` | `35s` | 抢租失败后的退避重试间隔（= TTL + 5s 余量） | 配置键（代码缺省） | 部分依据（算式 TTL+5s；5s 余量无独立依据） | 保留 | B1-59 |
| N-AI-073 | TTL | `ai/config/AiProperties.java:1305` | `app.ai.lease.resource-ttl` | `60s` | L1 资源互斥锁（任务锁 `ai:lock:task:<id>` / 作业锁 `ai:lock:job:<taskId>:<TYPE>`）有效期，不续租，只作临界区上界 | 配置键（代码缺省） | 有依据（60 = 2×续跑 TTL；"宁可放开也不续出事实永锁"的裁决） | 保留 | B1-60 |
| N-AI-074 | 上限 | `ai/config/AiProperties.java:213` | `app.ai.sheet.sample-max-rows` | `20`（行） | 表格采样行数上界；只能收紧（有效值 = min(本键, 20)），`SheetSwitches` 收敛并经 `/health.sheet` 下发 | 配置键（代码缺省） | 部分依据（= 硬顶 20，D2 授权的物理边界；20 本身来自 R3） | 保留 | — |
| N-AI-075 | 上限 | `ai/config/AiProperties.java:224` | `app.ai.sheet.projection-max-columns` | `5`（列） | 投影列数上界；只能收紧（min(本键, 5)）；缺省投影 = 主键列 + 前 4 列 | 配置键（代码缺省） | 部分依据（= 硬顶 5；「主键 + 前 4」的 4 无独立论证） | 保留 | — |
| N-AI-076 | 字节 | `ai/config/AiProperties.java:1175` | `ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES` | `65536`（Byte = 64KiB） | 单帧上限的**单一事实源常量**（字段缺省 + `RunRegistry` 非 Spring 路径回落） | 硬编码（常量，字段可配） | 有依据（同 N-AI-060） | 保留；与 N-AI-060 是同一数字的两处事实源（yml 显式 + 常量缺省） | — |
| N-AI-077 | 字节 | `ai/config/AiProperties.java:1188` | `ResultBudget.Ui.MIN_FRAME_BYTES` | `1024`（Byte = 1KiB） | `max-frame-bytes` 的**启动下限**（`verifyResultBudget` fail-fast）；与 `FrameSizeLimiter.MIN_FRAME_BYTES` 同值 | 硬编码（校验下限） | 有依据（低于 1KiB ⇒ 帧数爆炸/正文被清空，拆帧专项） | 保留；两处必须同值（跨类重复，建议单向引用） | — |
| N-AI-078 | 上限 | `ai/config/AiProperties.java:1339` | `Input.DEFAULT_MAX_CHARS` | `8192`（UTF-16 码元） | `app.ai.input.max-chars` 的单一事实源常量 + 字段缺省 | 硬编码（常量，字段可配） | 有依据（同 N-AI-062） | 保留；注意 yml 又显式写 8192（两份事实源） | — |

**非数字开关（同一段、不属「数字」但常被一起裁）**：`app.ai.reasoning.display-enabled=false` / `persist-content=true`（`AiProperties.java:1577/1584`）、`app.ai.config.seed-force=false`（:115）、`allow-private-endpoints=false`（:139）、`app.ai.sheet.read-enabled=true` / `write-enabled=false`（:189/:204）、`app.ai.lock.enabled=true`（:1320）、`app.ai.session.purge-retry.periodic-enabled=true`（:1038）、`app.ai.resume.on-startup=false`（:907，**yml 为 true**）、`runtime-sweep-enabled=false`（:962，**yml 为 true**）、`app.ai.park.enabled=false`（:864，**yml 为 true**）、`app.ai.kb.enabled=true` / `cache.enabled=true`。三个「代码 false / yml true」是刻意不一致（已登记在 §6 P-8）。

## 4. 表 C：`ai/**` 代码常量与行为字面量

> `可调性 = 硬编码`（除非另注）。同一常量多消费点的，在该行「作用与消费点」列全。`Duration`/`TimeUnit` 已折成可读单位并保留源码写法。

### 4.1 上下文计量与压缩（`ai/context/**`、`ai/tool/ToolResultLimiter.java`）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-079 | 估算 | `context/ContextEstimator.java:32` | `CJK_CHARS_PER_TOKEN` | `1.7`（字符/token） | 中文 token 折算除数；也是 `ToolResultLimiter#charsForRemainingTokens` 反算系数（取更保守侧） | 硬编码 | 有依据（调研「1.6–1.8 取中值」） | 保留；换分词器需重估 | B1-54 |
| N-AI-080 | 估算 | `context/ContextEstimator.java:35` | `OTHER_CHARS_PER_TOKEN` | `4.0`（字符/token） | 非 CJK 折算除数 | 硬编码 | 有依据（GPT 系经验值） | 保留 | B1-55 |
| N-AI-081 | 池 | `context/ContextSummaryClient.java:91` | `POOL_THREADS` | `4`（线程） | 摘要专用有界平台池线程数 | 硬编码 | 部分依据（类注说明「摘要只由 L2/L3 触发」；4 无论证） | 需压测（并发压缩峰值下的池饱和 503/拒绝路径） | — |
| N-AI-082 | 队列 | `context/ContextSummaryClient.java:94` | `QUEUE_CAPACITY` | `8`（任务） | 摘要池有界队列（满即 AbortPolicy，摘要请求失败→熔断计数） | 硬编码 | 部分依据（同上） | 需压测 | — |
| N-AI-083 | 池 | `context/ContextSummaryClient.java:227` | `ThreadPoolExecutor(... 30L, TimeUnit.SECONDS ...)` | `30s`（空闲线程存活） | 摘要池空闲线程回收 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-084 | 缓冲 | `context/RollingSummarizer.java:94` | `PROMPT_RESERVE_TOKENS` | `96`（token） | 摘要请求的定界/声明预留（正文按 chunk − 96 收口） | 硬编码 | 部分依据（只说明用途，96 无数据） | 保留 | — |
| N-AI-085 | 缓冲 | `context/ToolResultShrink.java:50` | `MIN_KEEP_TOKENS` | `200`（token） | 单条工具结果收窄后的最少保留量（再小只剩标记） | 硬编码 | 部分依据（定性：再小模型看不懂） | 保留 | — |
| N-AI-086 | 重试 | `context/ToolResultShrink.java:129` | `for (attempt < 3)` | `3`（轮） | 单条结果收窄的迭代尝试上限（每轮按超出量再切） | 硬编码 | 裸数字 | 保留 | — |
| N-AI-087 | 阈值 | `context/TurnBudgetPlan.java:99` | `MAX_ARMED_BATCHES` | `2`（批） | armed（已发收尾指令）后仍请求工具的最大批次数，超出按 OVERFLOW 判定 | 硬编码 | 有依据（类注论证：防不配合模型循环到 300s 看门狗） | 保留 | — |
| N-AI-088 | 百分比 | `context/TurnBudgetPlan.java:198` | `wrapUpPercent` 缺省回落 | `95`（%） | 未传百分比时的收尾触发线（正常由 `l3-percent` 注入） | 硬编码（防御缺省） | 部分依据（= l3 缺省语义，未与配置同源引用） | 建议改为引用 `Context.L3Percent` 缺省（配置化一致性） | — |
| N-AI-089 | 百分比 | `context/TurnBudgetPlan.java:202` | `l1Percent` 缺省回落 | `70`（%） | 未传 L1 百分比时的工具清除触发线 | 硬编码（防御缺省） | 部分依据（= l1 缺省） | 同上 | — |
| N-AI-090 | 百分比 | `context/ContextCompactor.java:309` | `l2` 缺省回落 | `80`（%） | 未配置 L2 时的「前缀主导」判定线 | 硬编码（防御缺省） | 部分依据（= l2 缺省） | 同上 | — |
| N-AI-091 | 百分比 | `context/ContextTrigger.java:126` | `Math.min(reserve, Math.max(1, limitTokens / 2))` | 除数 `2` | 有效预留的窗口收口：`min(reserve, 窗口/2)`（C13，防小窗口被 13000 压成退化分母） | 硬编码 | 有依据（C13 算式） | 保留 | — |
| N-AI-092 | 缓冲 | `context/MemoryWindow.java:82`、`:141` | `keep` 缺省 `5`；`new Budget(0, 1, 0, 5)` | `5`（对）/ `0,1,0` | `context == null` 时的 pin 窗口缺省；`budget == null` 时的兜底 Budget（0 = 无预算、min 1、max 0） | 硬编码（防御缺省） | 部分依据（与 `keep-tool-pairs` 同源，未引用常量） | 建议引用配置缺省，避免第三份 5 | — |
| N-AI-093 | 重试 | `tool/ToolResultLimiter.java:275` | `for (int i = 0; i < 4; i++)` | `4`（轮） | 结构化 JSON 结果的标记收缩尝试上限（保证生成合法且 ≤ 上限） | 硬编码 | 裸数字 | 保留 | — |

### 4.2 Run / SSE / Redis 事件日志（`ai/run/**`、`ai/exec/**`）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-094 | 轮询 | `run/StreamWatchdog.java:58` | `TICK_MILLIS` | `1000`（ms） | 看门狗节拍（首包/事件间/总预算/取消判定；`AiExecutorConfig#aiStreamWatchdog` 每 tick 调度） | 硬编码 | 部分依据（节拍=1s 的精度/开销取舍，无实测） | 保留 | B2-10 |
| N-AI-095 | 退避 | `run/RetryBackoff.java:29` | `BASE_MS` | `500`（ms） | 流式重试首次退避基线（之后 ×2） | 硬编码 | 有依据（类注：首次退避足够短、对客户端瞬时） | 保留 | — |
| N-AI-096 | 退避 | `run/RetryBackoff.java:32` | `MAX_MS` | `5000`（ms） | 单次退避上限 | 硬编码 | 部分依据（「再多等不如把预算留给下一次」定性） | 保留 | — |
| N-AI-097 | 退避 | `run/RetryBackoff.java:35` | `JITTER_RATIO` | `0.25`（±25%） | 退避抖动幅度（防多实例/多页签共振） | 硬编码 | 部分依据（业界惯用 ±25%，与前端 `RECONNECT_JITTER_RATIO` 同值不同义） | 保留 | — |
| N-AI-098 | 超时 | `run/RetryBackoff.java:38` | `BUDGET_MARGIN_MS` | `1000`（ms） | 退避留给下一次尝试的最小余量（剩余预算不足即收缩/不睡） | 硬编码 | 部分依据（定性：不睡到「刚醒就超预算」） | 保留 | — |
| N-AI-099 | 重试 | `run/RetryBackoff.java:51` | `n - 1 >= 4` | 指数封顶阶 `4` | 2^(n-1) 溢出防线（500×2^4=8000 > 5000 即取上限） | 硬编码（算术） | 有依据（算式自洽） | 保留 | — |
| N-AI-100 | 重试 | `config/AiRetryDefaults.java:47` | `MAX_ATTEMPTS` | `3`（次，首轮+2 重试） | **底层模型调用**（非整轮）重试上限；`AiRetryConfig` 用它顶掉 Spring AI 缺省 10 次 | 硬编码 | 有依据（类注：再多不如把时间留给上层整轮重试） | 保留；注意与 N-AI-027（整轮 2 次）是两层 | — |
| N-AI-101 | 退避 | `config/AiRetryDefaults.java:50` | `INITIAL_BACKOFF_MS` | `500`（ms） | 底层调用重试退避基线（与 RetryBackoff 独立实现） | 硬编码 | 有依据（同 0.5s 起点论证） | 保留；两处 500 不同实现，建议单源 | — |
| N-AI-102 | 退避 | `config/AiRetryDefaults.java:53` | `MAX_BACKOFF_MS` | `5000`（ms） | 底层调用单次退避上限 | 硬编码 | 部分依据（最坏总和 1875ms = 摘要父预算 60s 的 3.2% ≤ 20%） | 保留 | — |
| N-AI-103 | 退避 | `config/AiRetryDefaults.java:56` | `MULTIPLIER` | `2`（倍） | 指数退避倍率 | 硬编码 | 部分依据（业界指数退避惯例） | 保留 | — |
| N-AI-104 | 退避 | `config/AiRetryDefaults.java:59` | `JITTER_RATIO` | `0.25`（±25%） | 底层重试抖动 | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-105 | 心跳 | `run/ResilientChatService.java:133` | `STREAMING_HEARTBEAT_MILLIS` | `5000`（ms） | 流式/压缩/静默期保活心跳节拍（`TurnKeepalive` 定时器；不占业务 seq、不落归档） | 硬编码 | 有依据（前端首帧 30s / 帧间 45s 看门狗，5s 明显更早且不必更密） | 保留 | B2-12 |
| N-AI-106 | 日志 | `run/ResilientChatService.java:3512` | `TAIL_SIZE` | `6`（条） | 末帧形态留档条数（日志/诊断） | 硬编码 | 裸数字 | 保留 | B2-13 |
| N-AI-107 | 日志 | `run/ResilientChatService.java:4332` | `LOG_BODY_MAX_CHARS` | `1024`（字符） | 日志正文截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-108 | 轮询 | `run/ResilientChatService.java:1897` | `Math.min(250L, deadline - now)` | `250`（ms） | 重试退避期的取消检查切片 | 硬编码 | 部分依据（切片只为及时响应取消，无实测） | 保留 | — |
| N-AI-109 | 超时 | `run/ResilientChatService.java:1965-1972` | `budgetMs + 1000L` / `Math.max(1000L, …)` | `1000`（ms）×3 处 | 墙钟兜底等待的余量与最小切片（挂起中走 1s 短切片复查） | 硬编码 | 部分依据（「看门狗先判、这里只兜底」定性） | 保留 | — |
| N-AI-110 | 心跳 | `run/SseChatEmitter.java:377`（消费点 `:392`、`:739`） | `DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS` | `5000`（ms） | 非驱动方 attach 连接的本地保活节拍（共享单线程调度器） | 硬编码 | 有依据（对齐驱动侧 5s；明显早于前端 30s/45s 看门狗） | 保留 | — |
| N-AI-111 | 池 | `run/SseChatEmitter.java:676` | `ThreadPoolExecutor(..., 60L, TimeUnit.SECONDS, ...)` | `60s`（空闲线程存活） | SSE 投递池空闲线程回收 | 硬编码 | 裸数字 | 保留 | B2-03 |
| N-AI-112 | 队列 | `run/RunRegistry.java:161` | `deliveryPoolSize * 64` | `池容量 × 64`（= 512 任务，缺省池 8） | 投递池的**池任务队列**容量（非订阅者队列；满即拒绝 → 摘除该订阅者） | 硬编码（派生） | 部分依据（无注释论证；64 倍系数的依据缺失） | 需压测；建议显式常量 + 注释（P-4） | — |
| N-AI-113 | 池 | `run/RunRegistry.java:63` | `DEFAULT_IDLE_RECYCLE_AFTER_MS` | `30m` | 写出器空闲回收窗口（零订阅 + 不在写 + 非终态收尾且 30m 无 `of()`） | 硬编码 | 部分依据（类注解释必须有时间窗；30m 无数据） | 保留 | — |
| N-AI-114 | 轮询 | `run/RunRegistry.java:66` | `DEFAULT_SWEEP_INTERVAL_MS` | `1m` | 空闲扫描最小间隔（摊在 `of()` 上，不额外占线程） | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-115 | 重试 | `run/FrameSizeLimiter.java:59` | `MAX_ITERATIONS` | `8`（轮） | 单帧超限后迭代收缩上限（防死循环） | 硬编码 | 部分依据（只说明防病态输入） | 保留 | B2-19 |
| N-AI-116 | 字节 | `run/FrameSizeLimiter.java:62` | `STRUCTURE_MARGIN_BYTES` | `64`（Byte） | 截断预算里让给 JSON 结构（键名/冒号/逗号）的字节 | 硬编码 | 部分依据（结构化余量估算） | 保留 | B2-20 |
| N-AI-117 | 字节 | `run/FrameSizeLimiter.java:80` | `MIN_FRAME_BYTES` | `1024`（Byte = 1KiB） | 单帧上限的**运行期**下限（构造器装配期直接失败） | 硬编码 | 有依据（帧骨架 + 最坏码点 12B 仍放不下 ⇒ 无法安全分片） | 保留；与 N-AI-077 同值（跨类重复） | — |
| N-AI-118 | 容量 | `run/RedisFrameStore.java:110` | `STREAM_WINDOW` | `6000`（条） | `ai:xevents` Stream 的条数窗口（`MAXLEN ~`） | 硬编码 | 部分依据（「B1 风险从 3000 翻倍缓解，非根治」） | 需压测/容量复核（与 byte-budget 等效窗口取 min） | — |
| N-AI-119 | 容量 | `run/RedisFrameStore.java:121` | `MIN_WINDOW` | `256`（条） | 字节治理收窄后的最窄窗口（低于则计数 `c1.streamByteFloor` + WARN） | 硬编码 | 有依据（与实时队列 256 同量级，保证回放仍能补一次重连） | 保留 | — |
| N-AI-120 | 字节 | `run/RedisFrameStore.java:133` | `ENTRY_OVERHEAD_BYTES` | `256`（Byte/条） | Stream 记录固定开销估算（entry ID + 字段名 + 长度前缀余量；保守方向） | 硬编码 | 有依据（构成已列，取大于实测） | 保留 | — |
| N-AI-121 | 分页 | `run/RedisFrameStore.java:136` | `TAIL_PROBE` | `16`（条） | 末号探测读尾部条数（升级期自动 ID 打乱物理位次） | 硬编码 | 部分依据（只说明为何探测多条） | 保留 | — |
| N-AI-122 | 序号 | `run/RedisFrameStore.java:215` | `SEQ_PLACEHOLDER_DIGITS` | `18`（位）+ `SEQ_PLACEHOLDER_MIN = 100_000_000_000_000_000L`（:218） | 降级占位号的位宽与下界（保证不与正常号空间重叠、不溢出 Redis 整数） | 硬编码 | 部分依据（注释说明 1e12 会被 Redis 拒；1e17 是选取的安全区） | 保留 | — |
| N-AI-123 | TTL | `run/RedisFrameStore.java:248` | `ACK_TTL_SECONDS` | `120`（s） | 幂等落档标记 `ai:xack:<token>` 的存活时长（只覆盖出帧重试窗口，不随会话 TTL） | 硬编码 | 部分依据（「秒级重试窗口」定性，120 无数据） | 保留；可降到秒级数十 | — |
| N-AI-124 | 容量 | `run/RedisFrameStore.java:481` | `MAX_TRACKED_RUNS` | `4096`（轮） | 进程内每轮簿记硬上限（超限按最旧淘汰） | 硬编码 | 部分依据（「兜底界，正常远小于」定性） | 保留 | — |
| N-AI-125 | 轮询 | `run/RedisFrameStore.java:484` | `SWEEP_INTERVAL_MS` | `60000`（ms） | 簿记清理检查间隔（一拍一次 CAS） | 硬编码 | 部分依据（每帧做太贵，一拍一次足够） | 保留 | — |
| N-AI-126 | 重试 | `run/RedisFrameStore.java:491` | `SEQ_ALLOCATE_ATTEMPTS` | `2`（次） | 取号脚本重试上限（只在「刚重建 + 末号读不出」时） | 硬编码 | 有依据（评审 I4：瞬时抖动第二拍恢复；不收敛交给降级计数） | 保留 | — |
| N-AI-127 | 重试 | `run/RedisFrameStore.java:501` | `APPEND_ATTEMPTS` | `3`（次） | 原子落档重试上限（回包未知/号不可证；同 token 幂等） | 硬编码 | 部分依据（「并发竞争第二拍通常落定」经验，非实测） | 保留 | — |
| N-AI-128 | 容量 | `run/RunStore.java:286` | `EVENT_WINDOW` | `3000`（条） | 每轮外置帧归档窗口（LIST 兼容口径）；实时队列容量必须小于它 | 硬编码 | 部分依据（与 SP-02 同取 3000；不变量有测试锚定） | 保留 | B2-17 |
| N-AI-129 | 容量 | `run/RunStore.java:146` | `CONTEXT_LEDGER_MAX` | `20`（条） | 上下文压缩台账保留条数（LPUSH + LTRIM） | 硬编码 | 有依据（设计稿 §3.6「最近 20 条」） | 保留 | B1-56 |
| N-AI-130 | 容量 | `run/RunStore.java:159` | `SAFETY_LEDGER_MAX` | `20`（条） | 安全事件台账保留条数（无原文，只记 atMs/runId/ruleId…） | 硬编码 | 部分依据（「一页几十次命中，20 条够复盘」定性） | 保留 | — |
| N-AI-131 | 分页 | `run/RunStore.java:210` | `ACTIVITY_TAIL_PROBE` | `16`（条） | 活动锚点读取时 LIST/Stream 各探尾部条数（取 max 抗乱序/钟漂） | 硬编码 | 部分依据（只说明为何取多条） | 保留 | — |
| N-AI-132 | 容量 | `run/RunStore.java:316` | `FENCE_AHEAD_WARNED_CAP` | `512`（runId） | 「无租约写回但 fence 已推进」告警节流集合上限（超限整体清空） | 硬编码 | 部分依据（只说明有界防泄漏） | 保留 | — |
| N-AI-133 | 容量 | `run/FrameStore.java:51` | `WAKE_BATCH_LIMIT` | `6000`（条） | 唤醒总线单批读取上限（与 STREAM_WINDOW 同值，但语义是唤醒批量而非归档窗口） | 硬编码 | 裸数字（无注释） | 保留；与 6000 同值不同义，勿合并 | — |
| N-AI-134 | 退避 | `run/CancellationRegistry.java:78` | `DEGRADED_POLL_BACKOFF_MS` | `5000`（ms） | Redis 降级期取消标志轮询退避 | 硬编码 | 部分依据（降级期少打 Redis 的取舍） | 保留；与 N-AI-146 同值不同义 | B2-16 |
| N-AI-135 | 并发 | `run/CancelledRunFinalizer.java:107` | `MONITOR_STRIPES` | `64`（条带） | per-run 监视器分段数（降低锁竞争、限制对象数） | 硬编码 | 裸数字 | 保留 | — |
| N-AI-136 | 退避 | `run/CancelledRunFinalizer.java:364`、`:367` | `base = 2s` / `cap = 30s` | `2s` 起、`30s` 封顶 | 取消收尾派发失败的重派发退避（缺省回落，正常读 `park.dispatch-retry-*`） | 硬编码（防御缺省） | 部分依据（与配置缺省同值，未引用） | 建议引用 `Park` 缺省（避免第二份 2s/30s） | — |
| N-AI-137 | 心跳 | `run/LeaseService.java:436` | `HEALTH_PROBE_INTERVAL_MS` | `1000`（ms） | 租约设施健康自证的最小间隔（避免断连时每帧等 PING 超时） | 硬编码 | 部分依据（定性：节流探针） | 保留 | — |
| N-AI-138 | TTL | `run/LeaseService.java:1240`、`run/CancellationRegistry.java:356`、`session/AiSessionPurgeService.java:1470`、`session/SessionPurgeGuard.java:112/151`、`session/SessionPurgePendingStore.java:536` | 缺省 TTL 回落 `Duration.ofHours(24)` | `24h` | 配置缺失/0/负值时的会话 TTL 兜底（与 `app.ai.session-ttl` 24h 同值，但语义是「配置不可用时的最后防线」） | 硬编码（防御缺省） | 部分依据（与出厂值同源，未引用配置常量） | 建议收敛为单一常量（现在 6 处字面量） | — |
| N-AI-139 | 版本 | `run/RunSnapshot.java:152` | `PARAMS_VERSION_FROZEN` | `1`（版本号） | 冻结参数的协议版本（续跑兼容判据） | 硬编码 | 部分依据（版本号，无注释论证） | 保留；升级冻结参数结构时 +1 | — |
| N-AI-140 | 池 | `exec/AiExecutorConfig.java:79` | `newScheduledThreadPool(2, …)` | `2`（线程） | 流式看门狗调度器线程数（每 tick 常数次原子读 + 偶尔一次 Redis GET） | 硬编码 | 部分依据（注释说明节拍体轻；2 无数据） | 保留 | B2-04 |
| N-AI-141 | 池 | `exec/AiExecutorConfig.java:51` | `setQueueCapacity(0)` | `0`（任务） | 挂起池队列 = 0（池满即 Abort → 503 SUSPEND_POOL_SATURATED） | 硬编码 | 有依据（排队会让请求等无上限时长；SP-02 实测形态） | 保留 | B2-07 |
| N-AI-142 | 池 | `gate/SpToolCallingManager.java:224`、`:242` | `TOOL_TIMEOUT_THREADS_DEFAULT` / `toolTimeoutThreads()` | `20`（线程，缺省派生 `suspend.pool-size`） | 工具执行超时监视线程池（SynchronousQueue + AbortPolicy，饱和记「本次未执行」） | 硬编码（派生配置） | 有依据（「一轮至多一个工具在跑，容量即并发上限」） | 保留 | — |
| N-AI-143 | 池 | `gate/SpToolCallingManager.java:254` | `ThreadPoolExecutor(0, threads, 30L, TimeUnit.SECONDS, SynchronousQueue)` | `30s`（空闲存活） | 工具超时池空闲线程回收（daemon，不阻止 JVM 退出） | 硬编码 | 裸数字 | 保留 | — |

### 4.3 会话销毁与 pending 重试（`ai/session/**`）

| ID | 分类 | 文件:行 | 常量 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-144 | 超时 | `session/AiSessionPurgeService.java:134` | `DEFAULT_ACTIVE_WAIT` | `1500`（ms） | 销毁前等待活跃 run 让位的缺省上限 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-145 | 超时 | `session/AiSessionPurgeService.java:144` | `MEMORY_VERIFY_DELAY_MS` | `60000`（ms = 60s） | 会话记忆删除后的复核延迟（验证真的删掉） | 硬编码 | 部分依据（复核用途明确，60s 无数据） | 保留 | — |
| N-AI-146 | 轮询 | `session/AiSessionPurgeService.java:146` | `ACTIVE_POLL_MILLIS` | `50`（ms） | 等待活跃 run 退出的轮询切片 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-147 | 上限 | `session/AiSessionPurgeService.java:152` | `MAX_DERIVED_ARCHIVE_TURNS` | `2000`（轮） | 从索引派生归档轮次的上限（防一次销毁扫爆 Redis） | 硬编码 | 裸数字 | 保留；建议注释「单会话轮次上界」依据 | — |
| N-AI-148 | 分页 | `session/AiSessionPurgeService.java:795`、`:828` | `ScanOptions…count(256)` | `256`（键/批） | 会话键族 SCAN 批大小 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-149 | 退避 | `session/SessionPurgePendingStore.java:74` | `BASE_BACKOFF_MS` | `10000`（ms = 10s） | 销毁 pending 首次重试退避 | 硬编码 | 部分依据（退避语义明确，10s 无数据） | 保留 | — |
| N-AI-150 | 退避 | `session/SessionPurgePendingStore.java:77` | `MAX_BACKOFF_MS` | `300000`（ms = 5m） | 销毁 pending 退避上限 | 硬编码 | 部分依据（同上） | 保留；5m 封顶需与运维重试窗口对齐 | — |
| N-AI-151 | 分页 | `session/SessionPurgePendingStore.java:337` | `ScanOptions…count(256)` | `256`（键/批） | pending 键 SCAN 批大小 | 硬编码 | 裸数字 | 保留；与 N-AI-148 同值不同目标 | — |
| N-AI-152 | 锁 | `session/SessionPurgeRetryRunner.java:64` | `LOCK_TTL_SECONDS` | `300`（s = 5m） | pending 重试的单飞锁 TTL | 硬编码 | 部分依据（防多实例并跑；5m 无数据） | 保留 | — |
| N-AI-153 | 轮询 | `session/SessionPurgeRetryRunner.java:66` | `INITIAL_DELAY` | `10s` | 周期任务首次延迟 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-154 | 轮询 | `session/SessionPurgeRetryRunner.java:68` | `PERIOD` | `30s` | pending 重试周期（`app.ai.session.purge-retry.periodic-enabled` 控制是否注册） | 硬编码 | 部分依据（30s 与退避 10s 同量级，无数据） | 保留 | — |
| N-AI-155 | 阈值 | `session/SessionPurgeRetryRunner.java:70` | `MAX_CANDIDATES` | `10`（个） | 单拍最多处理的 pending 候选数 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-156 | 重试 | `session/SessionPurgeRetryRunner.java:72` | `MAX_ATTEMPTS` | `10`（次） | 单条 pending 的累计尝试上限 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-157 | 重试 | `session/SessionPurgeRetryRunner.java:75` | `MAX_DEFER_PASSES` | `2`（轮） | 「活跃 run 未终态 ⇒ 延后」的最大延后轮数 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-158 | 容量 | `session/SessionPurgeRetryRunner.java:77` | `MAX_RECOVERY_KEYS` | `200`（键） | 启动 SCAN 恢复 orphan pending 的读取上限 | 硬编码 | 裸数字 | 保留 | — |

### 4.4 确认门 / 前端工具 / 限流 / 输入闸（`ai/gate/**`）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-159 | 心跳 | `gate/ConfirmGate.java:138` | `HEARTBEAT_SECONDS` | `2`（s） | 挂起等待循环的心跳/跨进程决策发现节拍；同时续期会话锁、租约（每 5 跳）、卡后台数据 TTL；`/api/ai/health.suspend.heartbeatIntervalMs` 回显 | 硬编码 | 部分依据（旧账：心跳语义明确；2s 无数据，且 E2E `Read-Sse-Brief 3s` 依赖它） | 保留；改动会影响 E2E 读窗（P-5） | B2-11 |
| N-AI-160 | 阈值 | `gate/ConfirmGate.java:1300`（消费点 `:1235`） | `EXPIRY_CLAIM_STALE_MS` | `30000`（ms = 30s） | 到期 guard 判「持有者已死」的陈旧阈值（远大于一次正常落库） | 硬编码 | 部分依据（只说明「远大于正常落库」） | 保留 | — |
| N-AI-161 | 退避 | `gate/ConfirmGate.java:2617`、`:2621`（fallback 值在 `:2619`、`:2623`） | `base = 2s` / `cap = 30s` | `2s` / `30s` | 重派发退避的缺省回落（正常读 `app.ai.park.dispatch-retry-*`） | 硬编码（防御缺省） | 部分依据（与配置缺省同值，未引用） | 建议引用 `Park` 缺省（第三份 2s/30s 字面量，见 N-AI-136） | — |
| N-AI-162 | 清洗 | `gate/InputSanitizer.java:54` | `MAX_PASSES` | `3`（轮） | 输入清洗的最多迭代轮次（嵌套定界剥离，防绕过） | 硬编码 | 裸数字 | 保留 | — |
| N-AI-163 | 上限 | `gate/ContextFieldGate.java:45` | `STEP_MAX_CHARS` | `64`（字符） | 工作区上下文 step 字段截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-164 | 上限 | `gate/ContextFieldGate.java:48` | `PAGE_MAX_CHARS` | `32`（字符） | 工作区上下文 page 字段截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-165 | 上限 | `gate/ToolPreflightService.java:857` | `summary.length() > 240` → `substring(0, 239)` | `240`/`239`（字符） | 预检摘要展示截断（239 + 省略号 = 240） | 硬编码 | 裸数字 | 保留 | — |
| N-AI-166 | 步数 | `gate/SpToolCallingManager.java:773`、`:836`、`:991`、`:1241` | 单轮 `create_task` 次数 = `1` | `1`（次/轮） | AR-03：同轮第二次 `create_task` 结算成 `skipped/CREATE_TASK_TURN_LIMIT`（无配置键） | 硬编码 | 有依据（AR-03 裁决；`tools.yaml` 与提示词同口径） | 保留；建议提到常量（现为散落判据） | — |
| N-AI-167 | 速率 | `gate/AiRateLimiter.java:201` | `(retryAfterMs + 999L) / 1000L` | 取整 `999/1000` | Retry-After 由毫秒向上取整成秒（至少 1s） | 硬编码（算术） | 有依据（向上取整保证不早于窗口滑出） | 保留 | — |

### 4.5 业务知识库（`ai/kb/**`）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-168 | 上限 | `kb/KbService.java:99` | `FIRST_SCREEN_CHARS` | `1200`（字符） | `kb_read_doc` 省略 section 时「目录 + 前 1200 字」 | 硬编码 | 部分依据（定稿口径，1200 无独立论证） | 保留 | — |
| N-AI-169 | 分页 | `kb/KbService.java:102`、`:515` | `SEARCH_OVERFETCH` + `Math.max(limit*4, 20)` | `4`（倍）/ 最小 `20`（条） | Lucene 检索过取（去重后再截到 topK） | 硬编码 | 部分依据（过取为去重服务，4 倍/20 无数据） | 保留 | — |
| N-AI-170 | 重试 | `kb/KbService.java:132` | `MAX_VERSION_REBUILD_ATTEMPTS` | `3`（次） | 索引版本变化后的重建重试上限 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-171 | 日志 | `kb/KbService.java:135` | `FAILURE_WARN_EVERY` | `4`（次） | 刷新失败 WARN 的节流周期 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-172 | 上限 | `kb/KbService.java:529` | `KbText.snippet(..., 160)` | `160`（字符） | 检索命中摘要长度 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-173 | 上限 | `kb/KbService.java:591`、`kb/KbText.java:181` | 查询词/展示词上限 `10` | `10`（个） | 检索词分析与「未命中提示」的词列表上限 | 硬编码 | 部分依据（KbText 注释「最多 10 个」） | 保留 | — |
| N-AI-174 | 上限 | `kb/KbService.java:924` | `message.length() > 300` | `300`（字符） | 状态/错误消息截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-175 | 上限 | `kb/KbService.java:777`、`:783`、`:806`、`kb/KbTools.java:301` | `stream().limit(3)` | `3`（条） | 「未命中」时给候选文档/候选分段/建议的最大条数 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-176 | 分页 | `kb/KbTools.java:72` | `RENDER_ROWS_RESERVE` | `12`（行） | `kb_read_doc` 页行数预算里给定界/声明/表头/目录框/尾行预留的行数 | 硬编码 | 部分依据（构成已列，12 无数据） | 保留 | — |
| N-AI-177 | 上限 | `kb/KbTools.java:75` | `MIN_PAGE_CHARS` | `200`（字符） | 页字符收缩下限（再小失去翻页意义） | 硬编码 | 部分依据（定性） | 保留 | — |
| N-AI-178 | 重试 | `kb/KbTools.java:78` | `PAGE_FIT_ATTEMPTS` | `4`（轮） | 缩页重读最多轮次（正常 1 轮收敛） | 硬编码 | 部分依据（「正常 1 轮」经验） | 保留 | — |
| N-AI-179 | 上限 | `kb/KbPromptSection.java:68` | `CATALOG_MAX_CHARS` | `4000`（字符） | 知识库目录段提示词上限（超出截断并告知） | 硬编码 | 部分依据（20 篇实测远小于此；上限本身无数据） | 保留 | — |
| N-AI-180 | 上限 | `kb/KbText.java:37` | `MAX_RESULT_CHARS` | `8000`（字符） | 知识库工具结果回填前的硬闸（与 8000 兜底档同量级） | 硬编码 | 部分依据（同 8000 口径，未引用 `result-budget`） | 建议改为引用 `result-budget.model.fallback-max-chars`（单源） | — |
| N-AI-181 | 缓冲 | `kb/KbText.java:40`、`:161` | `MARKER_RESERVE` / `MAX_RESULT_CHARS - 48` | `64` / `48`（字符） | 结果截断时给标记/省略号预留的字符（两个不同常量） | 硬编码 | 部分依据（用途明确，数值裸） | 保留；两处预留语义重叠，建议统一 | — |
| N-AI-182 | 缓冲 | `kb/MarkdownChunker.java:230` | `maxChars * 0.75` | `0.75`（比例） | 超长段落硬切的下一窗口起点（重叠 25%） | 硬编码 | 裸数字 | 保留；建议注释说明重叠目的 | — |
| N-AI-183 | 超时 | `kb/CachedKbSource.java:215` | `for (i < 30) sleep(100L)` | `30 × 100ms = 3s` | 其它实例正在冷启动刷新时，本地等待缓存清单出现的总时长（锁窗口 30s 内给 3s） | 硬编码 | 有依据（注释写明「锁 30s，本地等 3s」） | 保留 | — |
| N-AI-184 | 轮询 | `kb/CachedKbSource.java:281` | `for (i < 6) sleep(25L)` | `6 × 25ms = 150ms` | 写窗可见性重试（doc → manifest → INCR 的版本差） | 硬编码 | 部分依据（用途明确，150ms 无数据） | 保留 | — |
| N-AI-185 | 上限 | `kb/LocalDirKbSource.java:128`、`kb/AiKbController.java:60` | `sha.substring(0, 12)` | `12`（hex 字符） | 文档 sha 的展示截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-186 | 阈值 | `kb/KbTools.java:65` | 文案「持续超过 1 分钟」 | `1`（分钟） | 「知识库加载中」提示里给用户的等待阈值（**仅文案，无代码判定**） | 硬编码（文案） | 裸数字 | 建议与真实建索引耗时对齐或去掉具体数字 | — |
| N-AI-288 | 阈值 | `kb/KbService.java:94-96` | `BOOSTS`（六个字段权重） | title `3.0` / section `2.0` / aliases `4.0` / aliasesEn `4.0` / tags `1.0` / body `1.0`（Lucene boost 倍数） | Lucene 检索的字段加权（`:573` 逐字段 `BoostQuery`）：标题/别名/章节高于正文——决定「命中哪一段」与排序，直接影响 `kb_search` 质量；权重全为硬编码、无配置键 | 硬编码 | 部分依据（注释只给「标题/章节/别名高于正文，定稿要求标题域加权」；`aliases 4 > title 3` 与 `section 2` 的数值无推导） | 保留；建议标注取值来源（定稿章节）并纳入检索评测回归（作为 §6 之外的新鉴待裁项登记） | — |
| N-AI-289 | 退避 | `kb/CachedKbSource.java:317`（消费点 `:151`） | `factor = 0.9 + rand*0.2` | 抖动 `±10%`（区间 `0.9–1.1`） | **远端回源节拍的去同步抖动**：`jitteredRefreshInterval()` = `app.ai.kb.refresh-interval × factor`，与 `lastRemoteCheck` 年龄比较后才回源 HTTP（仅 `source=http` 形态；本地调度仍用固定 `refresh-interval`） | 硬编码 | 裸数字（无注释；`0.9/0.2` 两处字面量） | 保留（错峰有效）；建议抽常量 + 注释「±10% 防多实例同拍回源」；与 `refresh-interval`（N-AI-042）是**乘数关系**，不是同一个键 | — |

> 注：N-AI-288 / N-AI-289 是第二轮独立复核补扫新增（ID 续编，按域放在本节）；§4.5 现有 19 条 + 补扫 2 条 = 21 条。

### 4.6 工具上下文 / 结果视图 / 结果预算（`ai/tool/**`、`ai/resultview/**`、`ai/tools/**`、`ai/observe/**`）

| ID | 分类 | 文件:行 | 常量 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-187 | 上限 | `tool/ContextBuilder.java:136` | `EXTRA_VALUE_MAX_CHARS` | `300`（字符） | 工作区上下文 extra 单值截断 | 硬编码 | 裸数字（旧账同判） | 保留 | B2-22 |
| N-AI-188 | 上限 | `tool/ContextBuilder.java:139` | `EXTRA_TOTAL_MAX_CHARS` | `800`（字符） | 工作区上下文 extra 总长截断 | 硬编码 | 裸数字 | 保留 | B2-23 |
| N-AI-189 | 上限 | `tool/ContextBuilder.java:157` | `TITLE_MAX_CHARS` | `128`（字符） | 任务标题进提示词的截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-190 | 上限 | `tool/ContextBuilder.java:160` | `STEP_MAX_CHARS` | `64`（字符） | 工作区 step 值截断 | 硬编码 | 裸数字 | 保留；与 N-AI-163 同值不同类（Gate 侧） | — |
| N-AI-191 | 上限 | `tool/ContextBuilder.java:163` | `SELECTED_DEF_MAX_CHARS` | `64`（字符） | 单个已选配置定义编码截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-192 | 上限 | `tool/ContextBuilder.java:166` | `MAX_SLOT_VALUE_CHARS` | `64`（字符） | 任务槽位值截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-193 | 上限 | `tool/ContextBuilder.java:169` | `SELECTED_DEFS_MAX_CHARS` | `2000`（字符） | 已选定义列表总长截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-194 | 上限 | `tool/SheetSwitches.java:40` | `HARD_MAX_SAMPLE_ROWS` | `20`（行） | 表格采样行数**硬顶**（配置只能收紧）；`effectiveSampleMaxRows = min(配置, 20)`，`/health.sheet` 下发 | 硬编码 | 部分依据（R3 物理边界；20 本身来自 D2 授权） | 保留 | — |
| N-AI-195 | 上限 | `tool/SheetSwitches.java:43` | `HARD_MAX_PROJECTION_COLUMNS` | `5`（列） | 投影列数**硬顶**（min(配置, 5)）；与 `columnCodes` 5 列硬校同源 | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-196 | 上限 | `tool/UiPrimitiveGuard.java:68` | `VALUE_MAX` | `200`（字符） | `ui_fill` 写入值上限（Guard 侧硬校验） | 硬编码 | 部分依据（与工具描述 200 同口径） | 保留 | — |
| N-AI-197 | 上限 | `tool/UiPrimitiveGuard.java:70` | `NOTE_MAX` | `60`（字符） | `ui_note` 提示文本上限 | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-198 | 上限 | `tool/UiPrimitiveGuard.java:72` | `LIMIT_MAX` | `40`（行） | `ui_snapshot` 返回行数上限 | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-199 | 上限 | `tool/UiPrimitiveGuard.java:74` | `DELTA_MAX` | `2000`（px） | `ui_scroll` 像素增量绝对值上限 | 硬编码 | 部分依据（同上） | 保留 | — |
| N-AI-200 | 上限 | `resultview/ResultViews.java:35` | `MAX_ROWS` | `200`（行） | 结构化结果视图（resultView）行数上限 | 硬编码 | 部分依据（与 `result-budget.model.max-rows` 200 同值，语义不同） | 保留；同值不同义（视图 vs 回填） | — |
| N-AI-201 | 上限 | `resultview/ResultViews.java:38` | `MAX_COLUMNS` | `50`（列） | 结果视图列数上限 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-202 | 字节 | `resultview/ResultViews.java:41` | `MAX_BYTES` | `262144`（Byte = 256KiB） | 结果视图整体字节上限（远大于单帧 64KiB，分帧投递） | 硬编码 | 裸数字 | 保留；需与分帧/背压一起压测 | — |
| N-AI-203 | 字节 | `resultview/ListTasksResultViewProvider.java:57` | `FRAME_RESERVE_BYTES` | `4096`（Byte = 4KiB） | 生成视图时为帧信封预留的字节 | 硬编码 | 部分依据（预留用途明确） | 保留 | — |
| N-AI-204 | 字节 | `resultview/ListTasksResultViewProvider.java:59` | `MIN_VIEW_BUDGET` | `4096`（Byte = 4KiB） | 视图预算下限（低于则不产视图/降级纯文本） | 硬编码 | 部分依据 | 保留 | — |
| N-AI-205 | 上限 | `tools/AiTools.java:130` | `LIST_CONFIG_DEFS_MAX_ROWS` | `20`（条） | `list_config_defs` 单轮返回上限（源码 `.limit(LIST_CONFIG_DEFS_MAX_ROWS)`，**不是字面量 `.limit(20)`**）；超限追加「共 N 个，仅显示前 20 个」（后缀 `TOTAL_SUFFIX` 由常量拼接，`:141-142`）；必须 < `result-budget.model.max-rows` 200 | 硬编码 | 有依据（业界分页默认 20；「超限必须告知 + 排序稳定」是硬约束） | 保留；改值只需改本常量 + 该工具 `@Tool` 描述字面量（`:156`）——后缀随常量自动变 | —（旧 B2-30 是 `list_tasks` 10） |
| N-AI-206 | 上限 | `tools/AiTools.java:244` | `LIST_TASKS_MAX_ROWS` | `20`（条） | `list_tasks` 单轮返回上限（**旧值 10**），超出追加「共 N 条，仅显示前 20 条」 | 硬编码 | 有依据（GitHub 30/100、Stripe 10，内网推荐 20；旧账裁决②要求带告知） | 保留 | B2-30 / H-02 |
| N-AI-207 | 上限 | `tool/SheetAudit.java:104`、`:112` | `planId` 64 / 字符串值 128 | `64`、`128`（字符） | 表格审计日志字段截断/白名单长度 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-208 | 上限 | `observe/AiTrace.java:53` | `MAX_VALUE_CHARS` | `160`（字符） | trace 事件单值截断 | 硬编码 | 裸数字 | 保留 | — |

### 4.7 生成式表单与附件（`ai/form/**`）

| ID | 分类 | 文件:行 | 常量 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-209 | 上限 | `form/GenerativeFormRules.java:185` | `MAX_FIELDS` | `20`（字段） | `generative_form` 表单字段数上限（schema 校验；工具描述同值） | 硬编码 | 部分依据（与 20 形成「小表单」口径，无独立论证） | 保留 | B2-21 |
| N-AI-210 | 上限 | `form/GenerativeFormRules.java:187` | `MAX_OPTIONS` | `50`（项/字段） | 单个 enum/multi_select 字段的选项数上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-211 | 上限 | `form/GenerativeFormRules.java:189` | `MAX_KEY_CHARS` | `40`（字符） | 字段键长度上限（正则 `{0,39}`） | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-212 | 上限 | `form/GenerativeFormRules.java:191` | `MAX_TITLE_CHARS` | `100`（字符） | 表单标题上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-213 | 上限 | `form/GenerativeFormRules.java:193` | `MAX_LABEL_CHARS` | `100`（字符） | 字段标签上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-214 | 上限 | `form/GenerativeFormRules.java:195` | `MAX_PLACEHOLDER_CHARS` | `100`（字符） | 占位提示上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-215 | 上限 | `form/GenerativeFormRules.java:197` | `MAX_OPTION_TEXT_CHARS` | `100`（字符） | 选项值与展示文本上限（`DefinitionSelectionForm` 截断同源） | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-216 | 上限 | `form/GenerativeFormRules.java:213` | `MAX_TEXT_VALUE_CHARS` | `200`（字符） | 单字段文本值上限（maxLength 硬顶） | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-217 | 上限 | `form/GenerativeFormRules.java:216` | `MAX_LONGTEXT_VALUE_CHARS` | `2000`（字符） | longText 变体的值上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-218 | 深度 | `form/GenerativeFormRules.java:219` | `MAX_LINKAGE_DEPTH` | `2`（层） | 级联（optionsFrom/defaultFrom）最大依赖层数 | 硬编码 | 部分依据（多级联成本定性） | 保留 | B2-21 |
| N-AI-219 | 上限 | `form/GenerativeFormRules.java:221` | `MAX_FORM_CHARS` | `8000`（字符） | 表单 schema 序列化上限 | 硬编码 | 部分依据（与 8000 回填档同量级） | 保留 | B2-21 |
| N-AI-220 | 上限 | `form/GenerativeFormRules.java:223` | `MAX_RESULT_CHARS` | `8000`（字符） | 回灌值 JSON 上限 | 硬编码 | 部分依据 | 保留 | B2-21 |
| N-AI-221 | 上限 | `form/GenerativeFormRules.java:229` | `^att_[A-Za-z0-9_-]{8,64}$` | `8..64`（字符） | 附件 id 形态约束 | 硬编码（协议形态） | 部分依据（格式约定） | 保留 | — |
| N-AI-222 | 字节 | `form/GenerativeFormAttachmentController.java:57` | `MAX_ATTACHMENT_BYTES` | `5242880`（Byte = 5MiB） | 单件附件上限（与 `spring.servlet.multipart.max-file-size` 同口径） | 硬编码 | 有依据（注释：够放截图/PDF，不做文件搬运通道） | 保留；改值须同改 yml multipart | — |
| N-AI-223 | 字节 | `form/GenerativeFormAttachmentController.java:65` | `MAX_TOTAL_ATTACHMENT_BYTES` | `20971520`（Byte = 20MiB） | 单张表单附件总字节上限（件数上限 = 表单 attachment 字段数） | 硬编码 | 有依据（注释：件数 + 总量才把磁盘占用变有界） | 保留 | — |
| N-AI-224 | TTL | `form/RedisFormAttachmentStore.java:63` | `DEFAULT_TTL` | `24h` | 表单附件登记在 Redis 的 TTL（与 session-ttl 同值；卡活着附件要活着） | 硬编码（缺省） | 部分依据（与 session-ttl 24h 同源，未引用配置） | 建议引用 `app.ai.session-ttl`（现为字面量） | — |
| N-AI-225 | 上限 | `form/GenerativeFormGuard.java:616` | `flat.length() <= 200` | `200`（字符） | 表单/选项扁平化文本截断 | 硬编码 | 裸数字 | 保留 | — |

### 4.8 配置读写 / 上游连接 / 密钥 / 安全（`ai/config/**`）

| ID | 分类 | 文件:行 | 常量 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-226 | 上限 | `config/AiConfigWriter.java:118` | `MAX_KEY_LENGTH` | `512`（字符） | provider API key 输入长度上限（与 `ai_provider.base_url` 列宽 512 同值，语义不同） | 硬编码 | 部分依据（只与列宽对齐，512 本身无独立论证） | 保留 | — |
| N-AI-227 | 上限 | `config/AiConfigWriter.java:127` | `MIN_KEY_LENGTH` | `8`（字符） | key 最短长度（防 last4 展示整串） | 硬编码 | 有依据（注释：≤4 会原样展示；8 是最小保证） | 保留 | — |
| N-AI-228 | 阈值 | `config/AiConfigWriter.java:129` | `MIN_TEMPERATURE` | `0` | 写路径 temperature 下界（OpenAI 语义） | 硬编码 | 有依据（协议区间） | 保留 | — |
| N-AI-229 | 阈值 | `config/AiConfigWriter.java:131` | `MAX_TEMPERATURE` | `2` | temperature 上界 | 硬编码 | 有依据（协议区间 0–2） | 保留 | — |
| N-AI-230 | 阈值 | `config/AiConfigWriter.java:133` | `MIN_TOP_P` | `0` | top_p 下界 | 硬编码 | 有依据（协议区间） | 保留 | — |
| N-AI-231 | 阈值 | `config/AiConfigWriter.java:135` | `MAX_TOP_P` | `1` | top_p 上界 | 硬编码 | 有依据（协议区间） | 保留 | — |
| N-AI-232 | 阈值 | `config/AiConfigWriter.java:137` | `MIN_TOKENS` | `1` | max_tokens/maxOutput 下界 | 硬编码 | 有依据（正整数语义） | 保留 | — |
| N-AI-233 | 上限 | `config/AiConfigWriter.java:139`、`:843` | `MAX_TOKENS` | `200000`（token） | max_tokens / maxOutput 写路径上界 | 硬编码 | 裸数字（200k 无模型对照） | 保留；可改为按模型声明上界 | — |
| N-AI-234 | 上限 | `config/AiConfigWriter.java:141` | `MAX_CONTEXT_CEILING` | `10000000`（token） | `ai_model.max_context` 上界（防明显笔误） | 硬编码 | 裸数字 | 保留 | — |
| N-AI-235 | 上限 | `config/AiConfigWriter.java:247`、`:300`、`:809`、`:820` | 文本字段 128 | `128`（字符） | provider displayName / modelId / 通用字段上限 | 硬编码 | 部分依据（与 DB 列宽 128 对齐） | 保留 | — |
| N-AI-236 | 上限 | `config/AiConfigWriter.java:116`（`CODE_PATTERN`）、`:770`、`:774` | `[a-z0-9][a-z0-9_-]{0,63}` | `1..64`（字符） | provider code 形态与长度 | 硬编码 | 部分依据（与列宽 64 对齐） | 保留 | — |
| N-AI-237 | 其他 | `config/AiConfigWriter.java:904` | `https → 443 / http → 80` | `443`/`80`（端口） | base-url 未显式给端口时的默认端口（用于 URL 闸/展示） | 硬编码 | 有依据（协议固定端口） | 保留（见附录 A） | — |
| N-AI-238 | 字节 | `config/AiConnectionProbe.java:72` | `MAX_BODY_BYTES` | `1048576`（Byte = 1MiB） | `/models` 响应解析前截断（防上游回超大 JSON） | 硬编码 | 部分依据（注释：≈数万 model id，远超真实 /models） | 保留 | — |
| N-AI-239 | 上限 | `config/AiEndpointGuard.java:51` | `MAX_URL_LENGTH` | `512`（字符） | base-url 长度上限（与列宽 512 一致） | 硬编码 | 有依据（与列宽同值） | 保留 | — |
| N-AI-240 | 范围 | `config/AiEndpointGuard.java:91` | `port` 不在 `[1,65535]` 即非法 | `1..65535` | URL 端口合法性 | 硬编码 | 有依据（TCP 端口域） | 保留（附录 A） | — |
| N-AI-241 | 密钥 | `config/AiKeyCipher.java:81` | `IV_BYTES` | `12`（Byte） | AES-GCM IV 长度 | 硬编码 | 有依据（GCM 推荐 96-bit nonce） | 保留（附录 A） | — |
| N-AI-242 | 密钥 | `config/AiKeyCipher.java:83` | `TAG_BITS` | `128`（bit） | GCM 认证标签长度 | 硬编码 | 有依据（GCM 标准） | 保留（附录 A） | — |
| N-AI-243 | 密钥 | `config/AiKeyCipher.java:85` | `KEK_BYTES` | `32`（Byte = 256bit） | KEK 长度（`AI_SECRET_KEK` base64 32B） | 硬编码 | 有依据（AES-256） | 保留（附录 A） | — |
| N-AI-244 | 密钥 | `config/AiKeyCipher.java:87`、`:91` | `FINGERPRINT_HEX_CHARS` / kid `{1,16}` | `16`（hex）/ `1..16`（字符） | key 指纹展示长度、KEK kid 长度 | 硬编码 | 部分依据（指纹只作缓存键一部分，16 hex 无独立论证） | 保留 | — |
| N-AI-245 | 超时 | `config/AiProviderRegistry.java:125` | `UPSTREAM_SOCKET_IDLE_TIMEOUT` | `300s` | 上游流式 WebClient 读空闲兜底（半开连接回收）；不参与判据（看门狗优先） | 硬编码 | 有依据（决策看板 :16：socket 层留 300–600s 兜底取下沿） | 保留；需与 inter-event 90s 保持 ≫ | B2-03 |
| N-AI-246 | 上限 | `config/AiProviderRegistry.java:544` | `text.length() <= 32` | `32`（字符） | provider 标签/错误上下文截断 | 硬编码 | 裸数字 | 保留 | — |
| N-AI-247 | 阈值 | `config/AiProviderRegistry.java:484`、`:490` | 请求覆盖区间 `0..2` / `0..1` | temperature `0–2`、topP `0–1` | 逐请求 options 覆盖的窄窗口兜底（越界 WARN + 忽略） | 硬编码 | 有依据（协议区间） | 保留；与 N-AI-228…231 同值不同面 | — |
| N-AI-248 | TTL | `config/RedisAvailability.java:47` | `POSITIVE_TTL_MS` | `0`（ms） | Redis 探活**成功**结果缓存时长（0 = 不缓存，每次真 PING） | 硬编码 | 部分依据（「健康时每次真探」的选择） | 保留 | B2-14 |
| N-AI-249 | TTL | `config/RedisAvailability.java:53` | `NEGATIVE_TTL_MS` | `5000`（ms） | Redis 不可用时的重试/负缓存间隔 | 硬编码 | 部分依据（「最多延迟 5s 被感知」） | 保留；与 N-AI-134 同值不同义 | B2-15 |
| N-AI-250 | 容量 | `config/AiConfigService.java:69` | `ENV_SEED_MAX_CONTEXT` | `128000`（token） | env 种子写入 `ai_model.max_context` 的缺省值（与 `context.limit-tokens` 同值，语义是**种子事实**） | 硬编码 | 部分依据（同 128K 窗口；未引用配置键） | 建议与 `ContextLimitResolver` 回退值同源 | — |
| N-AI-251 | 清洗 | `feedback/ExcerptGuard.java:32` | `PRIVATE_KEY_BODY_MAX` | `8192`（字符） | PEM 私钥块匹配的最大正文长度（超长不匹配） | 硬编码 | 部分依据（防 regex 回溯/误伤） | 保留 | — |

### 4.9 Web 层与反馈（`ai/web/**`、`ai/feedback/**`）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-252 | 轮询 | `web/AiController.java:1194` | `PREPARING_RETRY_AFTER_MS` | `1000`（ms） | 「已 start、快照未建」重挂时建议给前端的重试间隔（响应体 `retryAfterMs`） | 硬编码 | 部分依据（用途明确，1s 无数据） | 保留 | — |
| N-AI-253 | 超时 | `web/AiController.java:2727`、`:470`、`:1040` | `NO_TRANSPORT_DEADLINE = -1L`；`new SseEmitter(-1L)` | `-1`（无死线） | AI 通道 emitter **不设传输层生命周期**（等人无上限）；机器预算仍由看门狗判 | 硬编码 | 有依据（长注释：确认门/表单等人无上限；三条释放路径） | 保留；`-1` 为语义哨兵，不可配 | — |
| N-AI-254 | 上限 | `web/AiController.java:2730`、`:2803` | `REASON_MAX_CHARS` | `1024`（字符） | 确认门 reason / 反馈 reason 截断（与 `ai_feedback.reason` 列宽 1024 同尺） | 硬编码 | 有依据（与列宽同尺） | 保留 | — |
| N-AI-255 | 上限 | `web/AiController.java:2810` | `safeExcerpt.length() > 200` | `200`（字符） | 反馈消息摘录截断（**注意**：`ai_feedback.message_excerpt` 列宽为 400，见附录 B） | 硬编码 | 部分依据（注释只说明脱敏策略；200 vs 列宽 400 无对照） | 建议校核：200 截断 < 400 列宽是安全的，但两处口径未写明 | — |
| N-AI-256 | 阈值 | `web/AiController.java:650` | `maxTokens` 不在 `[1,200000]` 即 400 | `1..200000`（token） | `/chat` 请求参数校验（与写路径 `MAX_TOKENS` 同值，**第二份字面量**） | 硬编码 | 部分依据（同 N-AI-233） | 建议引用 `AiConfigWriter.MAX_TOKENS`（现为重复字面量） | — |
| N-AI-257 | 重试 | `web/AiSessionController.java:62` | `SESSION_ID_ATTEMPTS` | `3`（次，含首次） | 同身份会话号撞号时的有界重取 | 硬编码 | 有依据（注释：连续撞号即 503，防死循环） | 保留 | — |
| N-AI-258 | 退避 | `web/AiSessionController.java:73` | `RENUMBER_JITTER_MAX_MILLIS` | `4`（ms） | 重取号前的随机抖动上界（实际 `[1,4]`） | 硬编码 | 有依据（注释：固定步长会集体对撞；下界 1ms 因毫秒发号器） | 保留；换发号器（雪花/UUID）后可删 | — |
| N-AI-259 | 上限 | `feedback/AiFeedbackRepository.java:19` | `findTop100…` | `100`（条） | 设置页 AI 反馈列表上限（方法名派生，无独立常量） | 硬编码（查询派生） | 部分依据（注释「上限 100」，100 无数据） | 保留 | — |

### 4.10 提示词 / 工具描述 / `ai/tools.yaml` 中的行为数字

| ID | 分类 | 文件:行 | 位置（文案载体） | 现值+单位 | 作用与消费点 | 可调性 | 依据 | 审视建议 | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-AI-260 | 上限 | `tools/AiTools.java:156`、`:274`（两处 `@Tool` 描述字面量）；`:141-142`、`:269`（两处后缀，**已由常量拼接**） | `@Tool` 描述字面量 + 常量拼接后缀 | 「前 20」×4 处（2 处字面量 + 2 处拼接） | 模型可见的 `list_config_defs` / `list_tasks` 上限告知：改值 = 改 2 个常量（N-AI-205/206）+ 2 处 `@Tool` 描述字面量；两处截断后缀随常量自动变，**不需要手改** | 硬编码（文案） | 有依据（注释要求「告知与上限一致」；后缀已用常量拼接，注释里「三处同改」的旧口径只对 `@Tool` 字面量成立） | 保留；建议把 `@Tool` 描述也改为常量拼接（彻底单源） | — |
| N-AI-261 | 上限 | `tools/AiTools.java:973-1071` | 表格读/写工具 `description` | `≤20 行` / `2000 字符` / `20 组` / `20 条` | 表格工具对模型的采样契约；后端披露受 `SheetSwitches`（20/5）约束，逐格截断在前端执行器 | 硬编码（文案 + 前端契约） | 部分依据（与硬顶 20 对齐；2000 字符无独立依据） | 保留；建议在后端 Guard 侧也校验 2000（现只在描述里） | — |
| N-AI-262 | 上限 | `tools/AiTools.java:1148-1163`；`run/ResilientChatService.java:264` | `ui_snapshot` 描述 + 系统提示词规则 22 | `600 字符` / `12 行` 硬顶；`limit 1..40，默认 20` | 模型对 `ui_snapshot(text=true)` 的预期；后端 `UiPrimitiveGuard.LIMIT_MAX=40` 校验 limit，600/12 由前端执行器实现。**独立项**：同一契约的提示词文案另见 N-AI-266（交叉引用、不重复计数） | 硬编码（文案 + 前端契约） | 部分依据（600/12 无后端强制，属跨端契约） | 需在契约文档固定；建议后端 Guard 也校验 text 段长度（P-2） | — |
| N-AI-263 | 上限 | `tools/AiTools.java:1182-1235` | `ui_fill` / `ui_scroll` / `ui_note` 描述 | `200 字符` / `±2000 px` / `60 字符` | 与 `UiPrimitiveGuard` 的 200/2000/60 同值（声明 + 强制两处） | 硬编码（文案） | 部分依据（同 N-AI-196…199） | 保留；建议描述由常量拼接 | — |
| N-AI-264 | 上限 | `tools/AiTools.java:809-956` | `generative_form` 工具参数 schema 描述 | `≤20` 字段 / `≤50` 选项 / `≤100` 文本 / `≤40` key / `200`/`2000` 值长 | 与 `GenerativeFormRules` 常量同值（声明面），后端校验是强制面 | 硬编码（文案） | 部分依据（同 N-AI-209…217） | 保留；建议由常量生成描述（防漂移） | — |
| N-AI-265 | 步数 | `resources/ai/tools.yaml:84` | `create_task` 描述 | 「同一轮最多一次」 | 与 `SpToolCallingManager` 的单轮 1 次硬判据同口径（N-AI-166） | 硬编码（文案） | 有依据（AR-03） | 保留 | — |
| N-AI-266 | 交叉引用 | `run/ResilientChatService.java:264` | 系统提示词规则 22（N-AI-262 的文案子集） | 「最多 600 字符」 | **非独立项**：只是 N-AI-262 同一契约（600/12 + limit）在系统提示词里的一次复述，无独立消费点、无独立取值；保留本行用于「提示词侧是否漂移」的对照 | 硬编码（文案） | 部分依据（同 N-AI-262） | 并入 N-AI-262 维护；**不单独计数**（见 §8.4 独立项口径） | — |
| N-AI-267 | 契约 | `resources/ai/tools.yaml:57` | `version: 2026-10-27.17` | 版本号（日期.序号） | 工具定义覆盖版本；改描述必须升 version（破 prompt 缓存） | 硬编码（版本） | 有依据（文件头反复写明「改描述必升 version」） | 保留（不算数字规格，登记避免误删） | — |

## 5. 与旧基线（`补档-NUMSPEC-清单总账.md`）的差异要点

> 旧值一律按旧文件原文对照；本表只列**变化**与**已不存在**的项，未列者现态与旧值相同但**行号已全部重查**（旧账行号是 2026-10-10 的，不可再用）。

| 旧 ID | 旧登记 | 现态 | 性质 |
|---|---|---|---|
| B1-16 | `app.ai.resume.zombie-age = 60s` | `180s`（`application.yml:249`）+ 新增 `zombie-margin 60s`（:250）与启动期 `verifyZombieAge` | **值变更**（R1-09：必须 ≥ max(exec 120s, inter-event 90s) + 余量） |
| B1-22 / B1-23 | `app.ai.tool-result.max-rows 200` / `max-chars 8000` | 键族改名为 `app.ai.result-budget.model.max-rows 200` / `default-max-chars 20000` / `fallback-max-chars 8000` | **键名与值变更**（G5） |
| B1-24 | `app.ai.frame.max-bytes 65536` | `app.ai.result-budget.ui.max-frame-bytes 65536` + 启动下限 `MIN_FRAME_BYTES 1024` | **键名变更 + 新增下限** |
| B1-53 | `app.ai.context.summary-model = ""` | **键已删除**（AR-11；绑定与 yml 行都不存在） | **已删** |
| B2-09 | `ai/config/AiPoolCapacityBudget.java` `CPUS_PER_THREAD_BUDGET = 10` | 文件与常量**已不存在**（全仓 grep 无命中） | **已删** |
| B2-18 | `ai/run/RunStore.java` `EXPIRED_RENEWAL = 1s` | **已不存在**（现 RunStore 无该常量） | **已删** |
| B2-21 | `GenerativeFormRules` 10 条上限 | 现为 **12 条**（新增 `MAX_LINKAGE_DEPTH 2`、`MAX_FORM_CHARS 8000`、`MAX_RESULT_CHARS 8000` 中的后两条与 depth 按现值计；共 20/50/40/100/100/100/100/200/2000/2/8000/8000） | **条数扩充** |
| B2-30 / H-02 | `ai/tools/AiTools.java:139` `.limit(10)`（`list_tasks`） | `LIST_TASKS_MAX_ROWS = 20`（`AiTools.java:244`）；`list_config_defs` 也为 20（:130），均带「共 N 条，仅显示前 M」告知 | **值变更 10→20** |
| —（旧账未登记） | — | 新增：`app.ai.park.*`（15/2s/30s）、`app.ai.stream.*`（64MiB/10m）、`app.ai.kb.*`（12 个数字键）、`app.ai.config.*`（1s/5s/10m）、`ConfirmGate.EXPIRY_CLAIM_STALE_MS`、`SseChatEmitter.DEFAULT_ATTACH_KEEPALIVE_INTERVAL_MILLIS`、`RedisFrameStore.*`（6000/256/256B/16/120s/4096/60s/2/3）、`ResultViews`（200/50/256KiB）、`FrameStore.WAKE_BATCH_LIMIT 6000`、`AiRetryDefaults`（3/500/5000/2/0.25）、`AiSessionPurgeService`/`SessionPurgeRetryRunner` 共 12 个、`KbService/KbTools/KbText/KbPromptSection/MarkdownChunker/CachedKbSource` 共 19 个 | **新增面** |
| 旧账 §6.3 自述遗漏 | `docs/` 自身数字、历史退役键、运行时生效值、测试代码 | 本清单同样**未**覆盖这四类（见 §8） | 沿用 |

**旧 ID 到本清单的映射**：`B1-05→N-AI-001`、`B1-06→002`、`B1-07→006`、`B1-08→007`、`B1-09→022`、`B1-10→024`、`B1-11→025`、`B1-12→026`、`B1-13→027`、`B1-14→028`、`B1-15→030`、`B1-16→031`、`B1-17→033`、`B1-18→035`、`B1-19→036`、`B1-20→040`、`B1-21→041`、`B1-24→060`、`B1-25→068`、`B1-26→069`、`B1-27→066`、`B1-41…B1-52→N-AI-010…021`、`B1-54→079`、`B1-55→080`、`B1-56→129`、`B1-57→070`、`B1-58→071`、`B1-59→072`、`B1-60→073`、`B1-62→068`、`B1-63→069`、`B2-01→029`、`B2-02→023`、`B2-03→245`、`B2-10→094`、`B2-11→159`、`B2-12→105`、`B2-13→106`、`B2-14→248`、`B2-15→249`、`B2-16→134`、`B2-17→128`、`B2-19→115`、`B2-20→116`、`B2-22→187`、`B2-23→188`、`B3-01→008`、`B3-02→009`、`B3-03→007`、`B3-04→057`、`B3-05→058`、`B3-06→059`、`B3-07→060`、`B3-08→061`。

## 6. 重点待裁项（只建议，不改）

| # | 主题 | 事实 | 风险 | 建议裁决 |
|---|---|---|---|---|
| P-1 | `app.ai.hitl.frontend-tool-timeout = 120s`（N-AI-022） | 前端执行器预算 = 服务端上限 − 回灌 POST 60s；降到 60s 会得 0 并落到 5s 病态下限 | 等机器超时偏松（业界常见 60s），但单改本键会掐断已知成功路径（实测最慢成功回灌 30.6s） | 先改前端折算面（回灌 POST 单独预算），再把本键降到 60–90s；否则保留 |
| P-2 | `ui_snapshot(text=true)` 的 `600 字符 / 12 行`（N-AI-262） | 后端 `UiPrimitiveGuard` 只校验 `limit ≤ 40`，600/12 只在**工具描述 + 前端执行器**里 | 后端无法阻止超长文本段回灌（模型/前端任一侧漂移即失守），且 2000 总闸是前端概念 | 在后端 `FrontendToolResultRequest`/Guard 侧加 `text` 段长度与行数校验（或明确登记为前端契约并加契约测试） |
| P-3 | `app.ai.tool.exec-timeout = 120s`（N-AI-023） | 作业型工具（start_export/start_import/start_publish/start_precheck）也被同一上限约束；opt-in 免超时「本批不做」 | 长作业型工具若同步等待超过 120s，会被结算成 `TOOL_TIMEOUT/executed=false`（副作用可能已发生） | 裁决是否为作业型工具加 opt-in 免超时（或把 exec-timeout 提到 ≥ 单作业最长同步等待） |
| P-4 | SSE 投递池队列 = `deliveryPoolSize × 64`（N-AI-112） | 派生值无注释、无压测；缺省 8×64=512 | 池任务队列与「每订阅者 256 实时队列」两套容量语义易混淆；饱和时摘除订阅者 | 显式常量 + 注释；纳入 T3-5 压测矩阵（M 慢连接 × 队列 512） |
| P-5 | `ConfirmGate.HEARTBEAT_SECONDS = 2`（N-AI-159） | E2E `Read-Sse-Brief` 读窗 3–3.5s 以 2s 心跳为前提（旧账 H-05） | 改心跳会连带改 E2E 判据；2s 本身无压测/业界依据 | 保留；若改，同批改 `scripts/verify-e2e.ps1` 读窗并登记 |
| P-6 | 退役键守卫缺失（并行改动） | `AiRetiredKeysWarner` 及测试已删除（工作树 `D`）；`summary-model`、`app.ai.tool-result.*`、`app.ai.frame.max-bytes`、`confirm-timeout` 现**静默忽略** | 部署面仍写旧键时，配置面「配了」与运行面「没读」两套话，无任何 WARN | 恢复「退役键 WARN」或改成启动期 fail-fast；至少在发布说明中列出退役键 |
| P-7 | `probe-timeout 5s` / `models-cache-ttl 10m`（N-AI-004/005） | 5s 无实测；`/models` 响应体上限 1MiB（N-AI-238） | 大清单/慢公网下 `/test` 与 `/models/fetch` 可能假 UNREACHABLE | 压测一次真实 `/models`（含 1MiB 边界）后再定 5s；TTL 可保持 |
| P-8 | 配置面「代码缺省 vs 出厂 yml」两套事实源（§3 末） | `park.enabled` / `resume.on-startup` / `runtime-sweep-enabled` 代码 false、yml true；另有 8 个键 yml 完全不写（lease/sse/sheet/purge-retry） | 运维看 yml 看不到 lease 30s/续租 10s/SSE 池 8/256；`/health` 只回显部分 | 在 `/api/ai/health` 或启动 INFO 一次性回显全部代码缺省键现值（与 yml 注释纪律一致：只回显、不写值） |
| P-9 | 同值多处字面量（DRY 风险） | 24h 缺省 TTL 6 处（N-AI-138）、`MAX_TOKENS 200000` 2 处（N-AI-233/256）、`MAX_FRAME_BYTES 65536` 2 处（N-AI-060/076）、`MIN_FRAME_BYTES 1024` 2 处（N-AI-077/117）、`2s/30s` 重派发 2 处（N-AI-038/039/161/136）、`8192` 2 处（N-AI-062/078）、`8000` 5 处（N-AI-059/180/219/220/045/046）、`200` 3 处不同义（N-AI-057/200/225）、`6000` 2 处不同义（N-AI-118/133） | 改一处漏另一处即「配置面说 A、运行面做 B」 | 按「同义合并 / 不同义留独立命名常量」逐组收口；优先 24h、200000、65536、1024 |

## 7. 附录：协议固定常量 / 形态约束 / 存储列宽 / DDL 数值（不参与主表计数，但登记以免遗漏）

### 7.1 协议与形态固定值（可单列，不属可调规格）

| ID | 分类 | 文件:行 | 常量 / 表达式 | 现值 | 说明 |
|---|---|---|---|---|---|
| N-AI-268 | 审计 | `tool/SheetAudit.java:49` | `ALLOWED_FIELDS.subList(0, 8)` | `8`（字段） | 进日志面的 `tool_call_end` 固定 8 字段（设计稿 §4.2；后 2 个 defCode/page 只进台账） |
| N-AI-269 | 协议 | `tool/UiPrimitiveGuard.java:59`、`:62`；`web/FrontendToolResultRequest.java:39` | `REF {2,}` / `EPOCH ^[a-z0-9]{6}-\d+$` / `UI_CODE ^UI_[A-Z_]{2,40}$` | `≥3` 段 / `6` 位 / `2..40` | UI 引用/token 的形态契约（协议，非可调） |
| N-AI-270 | 协议 | `form/GenerativeFormRules.java:231`、`:233`、`:237` | EMAIL `{2,}` TLD / TEL `{5,19}` / IDCARD `15` 或 `18` 位 | — | 字段格式校验的标准形态（RFC/国标） |
| N-AI-271 | 协议 | `feedback/ExcerptGuard.java:48-55` | `sk-…{20,}` / `AKIA[0-9A-Z]{16}` / `Bearer {24,}` / JDBC `{1,64}:{1,128}` / 短链 `{1,20}://{1,64}:{1,128}` / PEM `{0,32}` | — | 凭据模式启发式（脱敏判据），非规格阈值 |
| N-AI-272 | 契约 | `run/TurnKeepalive.java:31` | 「帧类型集仍是 16」 | `16`（种） | SSE 帧类型数量契约（注释声明，代码无常量） |
| N-AI-273 | 契约 | `session/AiSessionPurgeService.java:61` 等 | 「13 个 run 级键」 | `13`（键/run） | 会话销毁时逐 run 删除的键族计数（`RunStore#deleteRun`） |
| N-AI-274 | 校验 | `config/AiProperties.java:550-553` | `checkPercent` 0–100 | `0..100` | 四个百分比键的取值域（协议域） |
| N-AI-275 | 版本 | `config/AiKeyCipher.java:56`、`:59`；`resultview/ResultView.java:25`、`ResultViews.java:44` | `k1` / `v1` / `1.0` / `configmgr/resultview/v1` | — | 密钥信封与结果视图的协议版本（字符串） |
| N-AI-276 | 哨兵 | `session/SessionPurgeDatabase.java:49`、`:51` | `LEGACY_TENANT_ID` / `LEGACY_USER_ID` | `0` | 历史行（无租户/用户列时代）的归属哨兵值（身份标识，不是阈值） |
| N-AI-277 | 哨兵 | `tenant/TenantUser.java:18` | `SYSTEM_USER_ID` | `-1` | 系统身份的保留 userId（身份标识，不是阈值；`TenantUserDirectory` 的 101/102 等属夹具） |

### 7.2 数据库列宽（存储契约，非运行期行为数字；按实体归并）

| 实体 | 列宽（`@Column(length=…)`） |
|---|---|
| `ai/config/db/AiProvider.java` | code 64 / display_name 128 / protocol 32 / base_url 512 / completions_path 128 / models_path 128 / key_cipher 2048 / key_fingerprint 64 / source 16 |
| `ai/config/db/AiProviderModel.java` | model_id 128 / display_name 128 |
| `ai/config/db/AiSetting.java` | setting_key 64 / value_json 2048 |
| `ai/config/db/AiConfigAudit.java` | actor 64 / action 32 / target_type 32 / target_id 64 / diff_json 4096 |
| `ai/feedback/AiFeedback.java` | message_id 64 / session_id 64 / reason 1024 / message_excerpt 400 / created_by 64 |

> 说明：这些是 DB 契约宽度，不直接决定运行期行为；但 `base_url 512`、`reason 1024`、`message_excerpt 400` 与代码常量（N-AI-239/254/255）存在同尺关系，改列宽须同改常量。

### 7.3 明确排除的面（不登记）

- **纯状态码/错误码**：400/401/403/409/410/429/500/503、`AI_UNAVAILABLE`/`SUSPEND_POOL_SATURATED`/`TOOL_TIMEOUT`/`CREATE_TASK_TURN_LIMIT` 等（属契约，不是可调数字）。
- **枚举序号 / 数组下标 / 字符码点**：`ordinal()+1`、`bytes[0]&0xFF`、`for (i=0; i<n)` 等（算法实现细节）。
- **枚举成员数量**：如 `SheetWriteGuard` 的「11 个既有算子」、9 个受治理表格工具、5 个纯读工具——是枚举基数，不是可调阈值（16 帧类型因被注释当作契约计数，单列 N-AI-272）。
- **测试断言与测试夹具**：`backend/src/test/**`、`frontend/tests/**`、`TenantUserDirectory` 的 1/2/101/102 种子行、E2E 断言 15 个定义等。
- **依赖版本**：`pom.xml`、`package.json`、lockfile。
- **提示词规则编号**：系统提示「第 10/17/24/26/29/30 条」是**规则标识**，不是阈值；未登记（规则内容里的行为数字如 600 已由 N-AI-262 登记）。
- **同一契约的重复文案不重复计数**：同一数字在多处文案复述时只登记**一个独立项**（有独立消费点/独立取值的那一行），其余记「交叉引用」行便于漂移对照，但**不计入独立项**。当前唯一实例：`600 字符`（N-AI-262 独立；N-AI-266 的提示词复述为交叉引用）。`UiPrimitiveGuard` 的 200/60/40/2000（N-AI-196…199）与 `AiTools` 描述里的同值属「声明 + 强制」两处**不同消费点**，仍各计独立项。
- **其他专项面**：`common/sse/**`（`SseWriteBudget.DEFAULT_TIMEOUT_MILLIS 15000`、弹性写出池上限——本清单只登记 AI 配置键 15s，常量本身属通用面）、`config/AppProperties.java`（job/task sse/file/health）、`ai/` 以外的 job/excel/file 数字、前端全部数字、CI 脚本数字、`spike/**`、`.github/**`。
- **DB migration 版本号本身**（V5/V8/V9/V10 等）：版本号是迁移标识、不是规格；但 **`ai_*` 表的 DEFAULT / 列宽 / 精度标度已补扫并登记在 §7.4**（非 AI 表的 DDL 仍未扫描）。
- **yyyy-MM-dd / GMT+8 / 时区偏移**等展示与格式常量。

> 交叉引用（不属本专项，但 AI 面数值依赖它们）：`server.tomcat.connection-timeout = 120000`（SSE 写侧事实兜底 ≈116s，见 `application.yml:6`）、`SseWriteBudget.DEFAULT_TIMEOUT_MILLIS = 15000`（`AiProperties.sse.write-timeout` 的缺省来源）、`spring.data.redis.timeout/connect-timeout = 2s`（AI 可用性前置探活的时间基础）。

### 7.4 `ai_*` 表 DDL 数值（`db/migration/**` H2 与 `db/postgresql/**` PG）

**扫描结果（先给结论，避免把非数字约束硬凑）**：

- **数值 DEFAULT 只有三处**：`ai_provider.sort_order = 0`、`ai_model.sort_order = 0`、`ai_feedback.tenant_id/user_id = 0`（V11），外加种子行 `ai_setting('config.version') = '1'`。其余 DEFAULT 全是 `TRUE/FALSE/CURRENT_TIMESTAMP/字符串`（`protocol/completions_path/models_path/key_ref/source`），不属数字规格。
- **数值列无 DEFAULT（NULL = 未声明，运行期回落）**：`ai_model.max_context`、`ai_model.max_output`；`ai_feedback.rating` 是 `INT NOT NULL` 但无 DEFAULT（1/-1 由写路径保证）；`ai_chat_message.seq` 是 `BIGINT NOT NULL` + `CHECK (seq > 0)`。
- **精度/标度：无**。两个目录的 `ai_*` 表里**没有任何 `NUMERIC(p,s)` / `DECIMAL` / `REAL` / `DOUBLE`** 列（金额/比率类字段不在此面）——这一点是扫描结论，不是遗漏。
- **方言差异**：`V3/V4/V11` 的 H2 与 PG 两版**逐字节相同**；`V8` 唯一差异是 `CLOB → TEXT`（PG 无 CLOB），表/列/约束/索引逐项一致；`V9` 无方言差异。
- **历史面**：`ai_chat_message` 由 V8 建表、V9 加唯一索引、**V10 `DROP TABLE IF EXISTS` 删除** ⇒ 运行期不存在该表；其列宽/`seq > 0` 仅作迁移链完整性登记。
- **无 ai_* 数字的迁移**：`V1/V2/V7` 不含 `ai_` 表；`V5` 只回填布尔能力位（`supports_reasoning`，无数字）；`V10` 只 DROP。

| ID | 分类 | 文件:行 | 对象 | 现值 | 说明（含运行期影响） |
|---|---|---|---|---|---|
| N-AI-278 | 列宽 | `db/migration/V3__ai_feedback.sql:7-12`（PG 同 7-12） | `ai_feedback` 列宽 | message_id 64 / session_id 64 / reason 1024 / message_excerpt 400 / created_by 64 | 与代码 `REASON_MAX_CHARS 1024`（N-AI-254）同尺；摘录列宽 400 而写入端只取前 200（N-AI-255）——**列宽 > 写入上限，属安全冗余**，改一侧不会立刻截断 |
| N-AI-279 | 取值域 | `db/migration/V3__ai_feedback.sql:9` | `ai_feedback.rating` | `1`（赞）/ `-1`（踩）/ `0`（取消：不落库，落库前删行） | 领域枚举而非可调阈值；**无 CHECK 约束** ⇒ 脏 rating 可入库（读侧只按 1/-1 统计则静默丢行） |
| N-AI-280 | DEFAULT | `db/migration/V11__ai_feedback_tenant_scope.sql:18-19`（PG 同） | `ai_feedback.tenant_id` / `user_id` | `0`（BIGINT NOT NULL） | legacy「无归属」命名空间（存量行一律 (0,0)）；与代码 `SessionPurgeDatabase.LEGACY_TENANT_ID/USER_ID = 0`（N-AI-276）同值，两处须同改 |
| N-AI-281 | 列宽 | `db/migration/V4__ai_provider_config.sql:11-21`（PG 同） | `ai_provider` 列宽 | code 64 / display_name 128 / protocol 32 / base_url 512 / completions_path 128 / models_path 128 / key_cipher 2048 / key_last4 8 / key_fingerprint 64 / key_ref 8 / source 16 | 与 `AiConfigWriter`/`AiEndpointGuard` 的 512/128/64 同尺（N-AI-226/235/236/239）；`key_last4` 宽 8 但只展示**末 4 位**（N-AI-227 的 MIN_KEY_LENGTH 8 即为此服务）；`key_fingerprint` 存储 16 hex（N-AI-244） |
| N-AI-282 | DEFAULT | `db/migration/V4__ai_provider_config.sql:23`（PG 同） | `ai_provider.sort_order` | `0`（INT NOT NULL） | provider 列表排序缺省（`AiConfigService` 按它排序读取）；无数值上限约束 |
| N-AI-283 | 列宽/上限 | `db/migration/V4__ai_provider_config.sql:34-37`（PG 同） | `ai_model` 列宽 + 窗口列 | model_id 128 / display_name 128；`max_context INT`、`max_output INT` **无 DEFAULT（NULL）** | **运行上限的真源列**：NULL = 未声明 ⇒ `ContextLimitResolver` 回退 `app.ai.context.limit-tokens 128000`（N-AI-010）；写路径上界 `MAX_CONTEXT_CEILING 10000000` / `MAX_TOKENS 200000`（N-AI-233/234）只校验不落 DDL |
| N-AI-284 | DEFAULT | `db/migration/V4__ai_provider_config.sql:43`（PG 同） | `ai_model.sort_order` | `0`（INT NOT NULL） | 模型 allowlist 排序缺省 |
| N-AI-285 | 列宽/种子 | `db/migration/V4__ai_provider_config.sql:51-56`（PG 同） | `ai_setting` 列宽 + `config.version` 初值 | setting_key 64 / value_json 2048；seed `'1'` | `config.version` 初值 1（DB 真源；跨实例失效另走 Redis `ai:cfg:ver`）；`value_json` 2048 约束默认参数 JSON（temperature/topP/maxTokens）的存储上限 |
| N-AI-286 | 列宽 | `db/migration/V4__ai_provider_config.sql:62-66`（PG 同） | `ai_config_audit` 列宽 | actor 64 / action 32 / target_type 32 / target_id 64 / diff_json 4096 | `diff_json` 4096 是审计明细上限（key 只写 `updated/last4`，永不写密文/明文） |
| N-AI-287 | 列宽/约束 | `db/migration/V8__ai_chat_message.sql:23-38`（PG 版 `:27-42`，仅 CLOB→TEXT） | `ai_chat_message` 列宽 + `seq` 约束 | session_id 128 / run_id 64 / message_id 128 / role 16；`seq BIGINT` + `CHECK (seq > 0)`；正文 `CLOB`(H2) / `TEXT`(PG) | **历史面：V10 已 DROP 该表**，运行期不存在；V9 的 `(session_id, run_id, message_id)` 唯一索引随表消失。保留登记只为迁移链可复核（重复回放 V8/V9 不会复活表） |

> 与主表的联动：`ai_model.max_context`（N-AI-283）决定上下文分母 → 影响 L1/L2/L3 触发（N-AI-012…014）；`ai_feedback.reason 1024`（N-AI-278）与 `REASON_MAX_CHARS`（N-AI-254）必须同尺；`tenant_id/user_id 0`（N-AI-280）与代码哨兵（N-AI-276）必须同值。这三组是「DDL 改一处、代码漏一处」风险最高的位置。

## 8. 扫描模式、方法与可能遗漏

### 8.1 机械扫描模式（在 `ai/**` 229 文件 + 4 个 yml + tools.yaml + 两个 db 目录上执行）

| 面 | 模式 |
|---|---|
| 常量 | `static final [^=]*= *[-0-9.]`；补扫 `static final[^=]*=[^;]*[0-9]`（派生/正则常量） |
| 时间 | `Duration.of*` / `Duration.between` / `TimeUnit.*` / `*_MS` / `*_SECONDS` / `*_MILLIS` / `Thread.sleep` |
| 调度 | `@Scheduled`（**0 命中**：AI 面全部用 `ScheduledExecutorService#scheduleAtFixedRate/WithFixedDelay` 或 `ApplicationRunner`+Thread） |
| 池/队列 | `newFixedThreadPool` / `newScheduledThreadPool` / `newSingleThreadScheduledExecutor` / `ThreadPoolExecutor` / `ArrayBlockingQueue` / `LinkedBlockingQueue` / `SynchronousQueue` / `setCorePoolSize` / `setMaxPoolSize` / `setQueueCapacity` |
| 上限/分页 | `\.limit(` / `PageRequest` / `ScanOptions…count(` / `substring(0,` / `.subList(` / `Math.min` / `Math.max` |
| 数值字面量 | 否定前缀（`*` / `//` / `/*` / `import`）+ `\b[0-9]{2,}` 的行（282 行命中，逐行分类） |
| 配置 | `application.yml` / `application-cluster.yml` / `application-h2tcp.yml` / `application-postgresql.yml` 的 `app.ai`、`spring.ai`、`spring.servlet.multipart`；`resources/ai/tools.yaml` 全文 |
| DB 契约 | `@Column(... length = …)`；DDL：`CREATE TABLE` / `ALTER TABLE` / `DEFAULT` / `VARCHAR(n)` / `INT`·`BIGINT` / `NUMERIC`·`DECIMAL` / `CHECK`（`db/migration/**` + `db/postgresql/**` 的 `ai_*` 表，逐个文件实读） |

### 8.2 人工复核

- 逐行通读 `ai/config/AiProperties.java`（1588 行全读：9 个 `@PostConstruct` 校验 + 全部字段初值 + 内嵌类）。
- 通读 `application.yml` 的 `spring.ai`（95-122）与 `app.ai`（124-433）两段全文（含注释依据）。
- 通读 `resources/ai/tools.yaml`（202 行）。
- 对 40+ 个命中文件逐一读常量注释与消费点（`grep getter()` 确认调用方）。

### 8.3 可能遗漏（如实登记，不承诺「无遗漏」）

1. **纯逻辑单数字面量**：`== 1`、`< 2`、`Math.max(1, …)` 这类守卫/钳位没有全量穷举；本次只登记了有行为语义的（如 create_task 单轮 1 次、MAX_ARMED_BATCHES 2）。
2. **前端实际强制的数字**：`ui_snapshot` 600/12、表格工具 2000 字符/20 行等由 `frontend/src/utils/frontend-tools.ts` 与表格执行器实现，本清单只登记后端声明面；前端值可能已漂移（未读前端）。
3. **`common/sse/**`**：`SseWriteBudget` 的 15000ms、弹性写出池上限（core 0/max=Integer.MAX_VALUE/keepAlive 60s/SynchronousQueue）属通用面，仅交叉引用。
4. **测试资源覆盖**：`src/test/resources/application.properties` 对 `resume.on-startup`、`purge-retry` 等的覆盖值未列入（属测试面）。
5. **运行时生效值未验证**：未启服务读 `/api/ai/health`（`sseDelivery`/`heartbeatIntervalMs`/`writeTimeoutMs`/`sheet.*`），本清单是静态源码/配置面结论。
6. **`docs/` 内历史值**：旧 evidence 文档中的实测值未逐条回溯（仅作依据来源被引用）。
7. **非 AI 表的 DDL**：`ai_*` 表已补扫并登记在 §7.4（DEFAULT / 列宽 / 精度 / `CHECK`）；`config_*`、`tasks`、`jobs`、`task_*`、`validation_issues`、`app_lock` 等业务表的 DDL 默认值/列宽**仍未扫描**（属其他分册）。§7.4 已明确：两个 db 目录的 `ai_*` 表中**没有** `NUMERIC/DECIMAL` 精度标度列，数值 DEFAULT 仅 3 处 + 1 个种子。
8. **本清单的「依据」判定**是**注释文本判断**，不是行为验证；标「有依据」≠ 该值已被实测证明（尤其 30s/90s/300s 三个「初值待压测」项）。

### 8.4 计数

- 主表（A + B + C）行数：**269**（`N-AI-001`…`N-AI-267` 共 267 行 + 补扫 `N-AI-288`/`N-AI-289` 2 行；ID 无重号）。
- 附录 7.1 补充行：**10**（`N-AI-268`…`N-AI-277`）。
- 附录 7.4（`ai_*` 表 DDL 数值）行：**10**（`N-AI-278`…`N-AI-287`）。
- **合计 289 行**。
- **独立项口径**：一行 = 一个独立数字语义（同常量多消费点可一行列全）；同一契约在多处文案复述时只计一次，其余行标「交叉引用」。
  - 交叉引用行：**1**（`N-AI-266`，并入 `N-AI-262`）。
  - **独立项 = 289 − 1 = 288**。
- 分布（主表，按小节机械计数）：yml 显式 66 + 跨专项 1；代码缺省键 11；上下文/压缩 15；Run/SSE/Redis 50；会话清理 15；Gate/限流 9；知识库 **21**（19 + 补扫 2）；工具/结果视图 22；表单 17；配置/安全 26；Web/反馈 8；提示词/工具描述 8。合计 269（逐行以 ID 为准）。
