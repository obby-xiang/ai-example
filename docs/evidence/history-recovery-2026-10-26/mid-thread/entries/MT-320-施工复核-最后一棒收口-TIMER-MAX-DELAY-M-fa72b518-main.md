<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-320 · 施工复核 · 最后一棒收口（TIMER_MAX_DELAY_MS 钳制+同源口径，v4 **111 断言全 PASS**，含真机子进程对照：未钳制 2^31→0ms 触发、钳制后恒 2147483647 不触发）。**全部后台施工/复核线已清空**，达到（fa72b518 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`backend/src/main/java`、`SseChatEmitter.java`、`TaskSseService.java`、`RunRegistry`。
> **稿态**：历史中间稿 —— 该 wire 第 `m76` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt54` 条有正文的 assistant 消息（共 65 条有正文），末条有正文消息为 `m91`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fa72b518` / agent `main`（`main` = 主会话）；原字符 3,725（链内 1 段、合计 3,725）。
> **链号 / 类别**：`MT-320` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 37 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m91`（7,405 字符）未入末条归档。
> **脱敏**：路径 7 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

最后一棒收口（TIMER_MAX_DELAY_MS 钳制+同源口径，v4 **111 断言全 PASS**，含真机子进程对照：未钳制 2^31→0ms 触发、钳制后恒 2147483647 不触发）。**全部后台施工/复核线已清空**，达到红队②门槛。

请转交 GLM-5.3 红队任务卡（第二轮）：

```
你是 GLM-5.3 红队，对 ai-example 项目第二批修复做对抗审查。你是攻击者：目标是找出施工与复核都漏掉的缺陷，不是走流程放行。

【子智能体分工条款】可派自己的 deepseek-v4-flash 子智能体执行机械活（批量走读、grep、跑验证脚本、门禁复跑）；本体只做攻击面分析、关键判断与结论裁决。

【对象】仓库（只读，零 git 写操作、不改任何文件）：主仓库工作树，分支 main，HEAD=21b05f0（包①+包⑤已合入，不在本轮范围）。工作树在制品三批共 12 M + 4 ??：

批次A 包② SSE 写侧预算（后端，XML 361→378）：
- 新增 backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java（15s 写预算单一事实源，write()→SENT|FAILED|TIMED_OUT|INTERRUPTED）
- 改 ai/run/SseChatEmitter.java（#drain 逐帧预算、#writeFrame、#evictOnWriteTimeout、#completeOne 写在飞交接、INTERRUPTED 改走 evict 不静默丢帧）
- 改 task/service/TaskSseService.java（#publish 锁内只快照+锁外带预算写、#sendFrame、#subscribe 非 0 超时+compute 原子挂载、#remove computeIfPresent）
- 改 ai/config/AiProperties.java、ai/run/RunRegistry.java（两参恒 direct()）、config/AppProperties.java（Task.Sse 配置+@PostConstruct emitterTimeout>0 fail-fast 守卫）、application.yml（两新配置块）
- 新增测试 SseWriteBudgetTest(6)/SseChatEmitterWriteBudgetTest(7)/TaskSseServiceTest(4)

批次B UI 修复包（前端 3 views，44+/35−）：
- TaskCenterView.vue：创建任务弹窗重叠修复（el-radio-group flex-col + radio !h-auto !whitespace-normal）、label-width="auto"、作业表 8 列 width→min-width、任务名称非必填方案B（trim()||defaultTaskTitle() 恒传非空字符串绝不 null）
- DefinitionsView.vue：label-width="auto" + 字段表 min-width
- ImportWizardView.vue：collapse #title 块级化、ElMessageBox.alert 改 h() vnode 渲染（pre-line）

批次C 红队 S2 修单+S2-a（前端 3 文件）：
- ExportWizardView.vue：ensureGridLoaded 返回 {ok,error}，PAGE_HANDLER_OPEN_EXPORT_EDITOR handler 按结局分支回灌（失败含引擎原文、不含"已打开"）
- utils/frontend-tools.ts：预算公式 min(max(server−60000,5000),server)（120→60s）、FRONTEND_TOOL_TIMEOUT_BUDGET_MS=60_000、新增 TIMER_MAX_DELAY_MS=2_147_483_647 钳制（normalizeBudgetMs 同源收口）、1e307 溢出防线
- stores/ai.ts：仅注释同步

【既有证据（可复核不可尽信）】
<LOCAL_WORK>\pkg2-reports\{施工报告.md,复核报告.md,修补报告.md,step0-实测.md}（step0 含 jstack 物证；复核含 Dekker 交接同步序证明、红→绿独立复现 5 败、XML 376 复跑；修补含红变体 2 败+XML 378）
<LOCAL_WORK>\ui-fix\{施工报告.md,复核报告.md}（实机截图前后对照：重叠 −16px→+4px、横滚 200→0/90→0、空名→默认名 POST 实证；复核 8/8 项过、零 XSS 面）
<LOCAL_WORK>\redteam-pkg15\{S2修单施工报告.md（含三轮复核追加节+第四轮施工回执）}；验证脚本 <LOCAL_WORK>\pkg5-scratch\budget-check-v4.mjs（111 PASS）、<LOCAL_WORK>\redteam-pkg15\scratch\export-editor-outcome.mjs（30 PASS）

【关键判断点清单（本体裁决）】
J1 包②并发正确性：completeOne 置位→复查双 volatile 交接、evictOnWriteTimeout 与写在飞竞争、INTERRUPTED→evict 新路径（中断位语义）、TaskSseService compute/computeIfPresent 同键互斥——逐一构造交织找丢帧/双投/漏摘/误摘/死锁。
J2 T4-3 帧口径不破：atMs 全帧覆盖、expiresAt−atMs==timeoutSeconds×1000、心跳不占 seq——抽验 SseChatEmitterWriteBudgetTest 断言原文。
J3 配置面：15s 写预算/5m emitter 超时取值论证是否成立；emitterTimeout>0 守卫 fail-fast 是否误伤现网配置（yml 显式值=缺省）。
J4 ★同值交互：后端写预算 15s vs 前端 sse reattach 建连看门狗 15s（HEAD 已含）同值——慢写摘除与前端重连的时序竞争是否有害（重连风暴/误摘活连接）？
J5 UI 包：label-width="auto" 的量宽补偿机制有无副作用（窄屏/超长 label）；width→min-width 后窄视口表现；方案B 默认名全分支恒非空字符串（null 红线）；h() vnode 渲染零 XSS 面。
J6 批次C：{ok,error} 契约三消费点兼容；预算公式稠密域（1~300s）不变量 预算+60000≤server（≥65s）；TIMER_MAX_DELAY 同源钳制口径（病态区>24.86 天退化为不超时）是否如实注释；60s 预算 vs SpreadGrid 60s 看门狗同值竞争的文案一致性。
J7 跨批组合：三批叠加+HEAD 既有（foldDeadline/atMs/ConfirmGate 120s）有无新组合竞态。
J8 命名纪律与证据立规：全部在制品 diff 扫「main+分隔符+v2」（含 NFKC/零宽变体）、盘符、用户名、内网 IP、sk- 凭据。

【机械活清单（可派子智能体）】
M1 12 M+4 ?? 全量 diff 逐行走读+归属核对；M2 后端 mvn 全量测试 XML 求和应 378/0/0/0（58 XML），Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" 须包装脚本；M3 frontend yarn typecheck+yarn build；M4 node scripts/ci/check-docs.mjs 应 6/6；M5 复跑 budget-check-v4.mjs（111 PASS）与 export-editor-outcome.mjs（30 PASS）；M6 命名纪律四态扫描。

【定级与产出】S1 阻断/S2 应修/S3 建议/S4 观察，每条给 类名#方法名 锚点+攻击场景+证据。结论二值：放行（0×S1）或驳回（S1 清单）。★报告落盘 <LOCAL_WORK>\redteam-round2\红队报告.md，只回路径不贴全文。
```

红队②放行后的收尾序列（无需你再操作，除非驳回）：提交官三批细分提交+CI 闭环 → 去向表闭环登记（R-38/R-59 口径回灌、观察项、轮询治理与 CT 核查结论）→ **交接提示词**。
