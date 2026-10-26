<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-038 · 设计 · 任务卡 F：K2.8 施工 M2-T3a（请投放）（26cac31b / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`scripts/ci`、`SseChatEmitter`、`RunStore`。
> **稿态**：历史中间稿 —— 该 wire 第 `m20` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt18` 条有正文的 assistant 消息（共 20 条有正文），末条有正文消息为 `m22`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `26cac31b` / agent `main`（`main` = 主会话）；原字符 2,227（链内 1 段、合计 2,227）。
> **链号 / 类别**：`MT-038` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m22`（5,084 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

T3 设计批闭环入库：HEAD=`2125126`，两笔提交钩子全过，远端同步，工作区干净。T3 设计阶段正式闭环（设计 v1.2 + 红队 + 复核 + R1 裁决 + 施工注记全入库）。

## 任务卡 F：K2.8 施工 M2-T3a（请投放）

```text
你是 ai-example 合流项目的施工工程师（K2.8）。施工 M2-T3a：SSE 韧性批次（T3-1 慢订阅者隔离 + T3-2 seq 持久化，两项强耦合同批，禁止拆批）。

【仓库与纪律】仓库 <REPO>，HEAD=2125126（施工前 git pull 确认）。零 git 写操作（不 add/commit/push/stash），完工保持全部改动未提交。端口纪律：18330 起、启动前 netstat、只停自启进程。AI 凭证：本批次机制开发不需要真实 key（涉及模型调用处用 scripts/ci/stub-upstream.mjs 桩上游）；如需 key 仅 export AI_API_KEY 注入，禁读任何 .env 文件、禁写文件。文档纪律：禁用字面量按 DC-08 抽象表述（参照 docs/README.md 命名纪律节的写法，禁直书盘符路径、禁直书"main 加分隔符再接 v2"的组合字样、key 记 ***）；证据文档脱敏扫描以 git status 全量为准、含证据文档自身。证据分级：【实测】必须本棒实跑/实读，引用他棒结论标【旁证】。

【规格事实源】docs/M2-T3-设计卡.md v1.2（已入库）的 T3-1、T3-2 两节 + R1 裁决小节 + 施工注记 R2/R3 两条。严格按设计卡施工；发现设计卡与代码现实冲突时停工报告，不擅自改设计。

【T3-1 要点】SseChatEmitter 改造：emitLock 内只保留取号/落归档/touchActivity/入队；新增共享有界投递池（线程数初值 4，经 AiProperties 可配）+ 每订阅者有界队列（容量 256 初值可配）+ CAS drain 独占标志；drain 收尾顺序固定为"先清标志、再复查队列非空则重抢"（R2）；池拒绝语义=摘除触发本次提交的订阅者（R3）；溢出摘除必须显式 complete emitter 触发前端重挂；replayAndAttach 锁内收集回放帧入队首、写出走队列，回放批豁免 256 容量、上界 EVENT_WINDOW 3000（R1 裁决）；AiProperties 容量联动注释更新为"专用池×2 + 投递池 ≤ boundedElastic"；/api/ai/health 增投递池指标（poolSize/active/queueDepth/evicted）。

【T3-2 要点】新增 ai:seq:<runId> 键：nextSeq 在 emitLock 内 max(归档末帧, ai:seq)+1、发放即 SET、每帧同刷 TTL=session-ttl（与归档同寿）；SET 失败 WARN 日志+继续出帧；appendEvent 对 delta 帧跳过归档；删除 replayTo 的 includeDelta 参数与死分支（全仓零调用，复核已证）、persistedFrameCount 死代码处置（删除或标注，择一并留痕）；InMemoryRunStore 替身同步 ai:seq 语义。施工前必查四个帧协议测试（ChatTurnFrameSequenceConformanceTest、ReattachLastSeqConformanceTest、SseFrameSequenceConformanceTest、SseChatEmitterFramesTest）有无依赖 delta 入档断言，有则同步修订并说明。

【新单测要求】双不变量锚定（实时段 256 溢出摘除 + 回放段 3000 全量可入队）；SET 失败分支（WARN+继续出帧）；drain 收尾竞态并发用例（入队与 drain 收尾交叠不丢帧）；摘除→emitter complete→前端可重挂语义；重号场景（发放 3 帧、落档第 2/3 帧失败、新实例续号得 seq=4）；delta 不进归档（LLEN 只计状态帧）。

【验证】cd backend && "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test 全量绿，XML 口径独立重算（基线 263，只增不减）；前端零改动则 typecheck 跑一次确认即可；scripts 零改动。可用桩上游做一轮集成冒烟（挂起轮 + 慢订阅者场景）作为【实测】旁证，非强制。

【产出】证据文档 docs/evidence/M2-T3a-施工与验证-K2.8.md：逐项 设计卡条款→实现落点（文件:行号）→验证证据→结论，【待裁决】节列设计冲突/偏差；文末附脱敏自查（含本文档自身）。中文撰写。
```

**交接预告**：下轮即本周期第 10 轮，触发双阈值交接规则——T3a 施工回报到达时，我会先输出同构交接提示词（含全部状态更新），再由新会话接续验收。
