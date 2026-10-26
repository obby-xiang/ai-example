<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-209 · 施工复核 · 复核 **PASS 放行**：A1-A5 独立复现全绿（361/0/0/0、E2E PASS=29 复核者自己复跑通过）、B1-B10 对抗反查全部成立、**无 S1/S2**，仅 3 条 S3 建议级。（7ec68aa1 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`SseChatEmitter`、`backend/src/main/java`、`SseChatEmitter.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m12` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt11` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m29`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `7ec68aa1` / agent `main`（`main` = 主会话）；原字符 4,269（链内 1 段、合计 4,269）。
> **链号 / 类别**：`MT-209` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 4 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m29`（10,711 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

复核 **PASS 放行**：A1-A5 独立复现全绿（361/0/0/0、E2E PASS=29 复核者自己复跑通过）、B1-B10 对抗反查全部成立、**无 S1/S2**，仅 3 条 S3 建议级。

指挥官对 S3 裁决：**不回卷施工线**，三条全登记观察项并写进红队任务卡让 GLM 一并裁决——
- S3-1（归档同值断言走 mock，建议下沉 conformance）：测试强度增强，登记下批；
- S3-2（同钟等式措辞加 `timeoutSeconds ≥ 0` 限定）：措辞精度，红队裁决是否本批顺手修；
- S3-3（`expiresAtEpochMs` 死防御回落分支静默吸钟，建议改 warn 可见化）：有道理，红队裁决。

复核有两处偏离登记我已知悉：E2E 用 CI 占位 key 而非真 key（正确，密钥纪律优先）、Maven 后台启动用包装脚本（环境问题，非产品问题）。

现在出 **GLM 红队任务卡**，请你转交 GLM-5.3：

```
你是 GLM-5.3 红队，对 ai-example 项目"T4-3 后半：实时 SSE 帧 atMs 覆盖"变更集做对抗评审。

【子智能体分工条款】你可派自己的 deepseek-v4-flash 子智能体执行机械活（跑测试、grep、
读大文件、统计计数）；你本体只做关键判断、攻击面分析、结论裁决与报告撰写。本卡结构即
"关键判断点清单+机械活清单"。报告落盘路径：<LOCAL_WORK>\t43b-reports\T4-3后半-红队报告.md
（你的委托方只回收路径，不贴全文）。报告脱敏：禁盘符/真实用户名/内网 IP，用 <MAIN_REPO>
等占位符。

【对象仓库】<MAIN_REPO>=<REPO>（Windows，Git Bash，
后端 Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/
maven3/bin/mvn.cmd"，前端 yarn）。变更集当前为**未提交工作区改动**（git status 恰 10 行
" M"、HEAD=9fcdcda）。**零 git 写操作、不改工作区文件**（跑测试产生 target/ 产物除外）。

【变更集内容（施工方声称，你的职责是证伪）】
1. SseChatEmitter#frame(String type)（backend/src/main/java/com/example/configmgr/ai/run/
   SseChatEmitter.java）内统一 put("atMs", System.currentTimeMillis())——所有帧构造点带 atMs。
2. SseChatEmitter#expiresAtEpochMs 改为从帧内 atMs 单点取钟，契约：expiresAt - atMs ==
   timeoutSeconds*1000L 精确相等（confirmRequest/frontendToolRequest 两方法）。
3. RunStore#appendEvent 的 putIfAbsent(atMs) 保留为兜底（注释改兜底语义，代码零改动）；
   测试替身 InMemoryRunStore 注释镜像。
4. 前端 frontend/src/stores/ai.ts#foldDeadline 条件链加 atMs > 0（挡 0/负数/NaN 时的
   线性膨胀与"epoch 当剩余时长"两种畸形）。
5. 新增 3 用例于 SseChatEmitterFramesTest（liveFramesCarryAtMsFromEmission /
   confirmRequestsShareOneClockBetweenAtMsAndExpiry / archivedAtMsMatchesLiveAtMs）；
   SseFrameSizeLimitTest 仅私有夹具 emptyTextFrame() 补 atMs 字段（指挥官已裁决：断言零改动）。
6. 注释/文档同步：frontend/src/types/sse.ts、types/ai.ts、AiPanel.vue、stores/ai.ts、
   SseChatEmitter.java、docs/M2-排期计划.md（去向表只追加零删行+行尾注记）。
验证声称：mvn test 361/0/0/0（55 XML，358→361 只增不减）；yarn typecheck+build 绿；
check-docs.mjs 6/6；E2E 桩模式 PASS=29 exit 0。复核线已 PASS（独立复现+对抗反查，
无 S1/S2）。施工报告 <LOCAL_WORK>\t43b-reports\T4-3后半-施工报告.md；复核报告同目录
T4-3后半-复核报告.md（含 B1-B10 反查证据，可对照但不得全信）。

【关键判断点清单（你本体裁决）】
J1 帧构造全覆盖：是否存在任何帧不经 SseChatEmitter#frame 直达 emit/序列化的旁路？
   （复核 B2 称 14 处构造点=14 处 emit 调用点、全文件仅 frame() 一处造帧——请独立验证）
J2 同钟契约：expiresAtEpochMs 的 instanceof Number 回落分支（帧内无 atMs 时取第二时钟）
   是否构成隐患？负 timeoutSeconds 经 Math.max(0,…) 钳位后等式退化为 0==负数，当前无可达
   路径（ConfirmGate 保证 ≥1）——该死防御是保留/改 warn/删除？（复核 S3-2/S3-3 挂此）
J3 atMs>0 守卫：foldDeadline 条件链（typeof number && isFinite && >0 && expiresAt>=atMs）
   是否存在绕过形态（如 expiresAt=0+atMs=0、expiresAt<atMs 但 atMs>0、超大 atMs）？守卫
   落地后实时帧从"回落分支"切到"折算分支"——这是结构性行为变更，请推演挂起倒计时在
   慢网络/时钟回拨/跨进程快照恢复（restoreSuspendedSnapshot 不填 expiresAt/atMs）下的行为。
J4 字节预算：全帧 +约21 字节 atMs——FrameSizeLimiter#overhead 动态计费是否在所有帧型
   （尤其 delta 高频帧与恰好边界的 tool_result）下仍保硬上限？截断语义是否被惊扰？
J5 归档=投递同值：archivedAtMsMatchesLiveAtMs 走 mock RunStore，真实复制路径未被执行
   （复核 S3-1 挂此）——该用例是否构成"看起来在测实际没测"的假安全感？是否要求本批补强？
J6 SseFrameSizeLimitTest 夹具对齐：确认 git diff 中该类 6 条断言文本零改动、仅夹具方法体
   +注释变化；评估"手搓骨架反推字符数"夹具模式本身的脆性是否被本次改动放大。
J7 文档声称 vs 代码实况：sse.ts/types/ai.ts/AiPanel.vue/M2-排期计划.md 新口径逐句对照代码；
   M2 去向表零删行纪律（git diff 该行区只有+行与行尾注记，先例 :177 同构性）。
J8 命名纪律+脱敏：对 10 文件 diff 人工扫 main+分隔符+v2 任意大小写组合、盘符、用户名、
   内网 IP（git show Author 信封行不计命中）。
J9 E2E 配方偏离追认：施工/复核均声称任务书原配方缺 --violate-form-type/AI_BASE_URL/
   -BackendLog 三项、以 .github/workflows/ci.yml 为事实源校准——请实读 ci.yml 独立核实
   该声称（这决定交接配方是否需要勘误）。
J10【附带小项】另有一笔"冗余文件历史清洗"待执行：拟剔除 docs/spike/logs 下 7 个文件
   （glm-review/sp01ab/conc-session.mjs、sp01ab/kill-during-suspend.mjs、sp01c/conc-write.mjs、
   sp01d/conc-sid.mjs、sp01d/digest.js、sp01d/short-timeout-5s-tool.mjs、sp02/E1-instance2-
   sse-raw-copy.txt）。分析声称 7 文件外部引用全为 0、E1-...-copy 与 E1-instance2-sse-raw.txt
   md5 逐字节相同。请抽查核实（md5 复算+全仓引用 grep），因为清洗含历史改写、不可逆性强。

【机械活清单（可派你的子智能体）】
M1 复跑 backend mvn test 全量+XML 求和（应 ≥361/0/0/0）、yarn typecheck+build、
   check-docs.mjs（6/6）。E2E 桩模式可选复跑（配方以 ci.yml 为准，端口 18330/18399 起、
   启动前 netstat、只停自起进程）。
M2 git diff 全量逐行过（154+/34− 量级）找施工方与复核方都没看到的东西。
M3 J1-J10 中需要 grep/统计/实读的取证动作。

【裁决格式】总判：放行 / 打回（S1 阻断 / S2 须修 分级清单，每条给证据与修法建议）；
J1-J10 逐条结论；S3 级观察项另行登记。历史打回先例：S2 级须修项施工方全修后复审。
```

**你转交后**，GLM 报告回来只给我路径即可。红队放行后我立即派提交官（本地细分提交 T4-3 → 历史清洗剔除 7 路径 → 全量验证 → 单次 force push → CI 五 job 闭环）。
