<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-036 · 施工复核 · 任务卡 C：GLM-5.3 修复复核（请投放）（26cac31b / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`m2t2a-smoke`、`RunStore`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m6` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt4` 条有正文的 assistant 消息（共 20 条有正文），末条有正文消息为 `m22`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `26cac31b` / agent `main`（`main` = 主会话）；原字符 2,188（链内 1 段、合计 2,188）。
> **链号 / 类别**：`MT-036` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m22`（5,084 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 5 处。

---

修复批完工，自验全绿（全量 263/0 计数不变、语法零漂移证明 skip 开关不改默认行为、脱敏零命中）。修复中唯一无运行证据的是 `SMOKE_SKIP_MODEL=1` 的 skip 分支——GLM 复核卡里我安排了实跑补证。

## 任务卡 C：GLM-5.3 修复复核（请投放）

```text
你是 M2-T2b 修复批的复核官（GLM-5.3）。主仓库 <REPO>，工作区含未提交的 T2b 全部成果 + 修复批改动（共 13 M + 5 ??，HEAD=2354721）。纪律：严禁任何 git 写操作、严禁修改仓库既有文件；唯一允许的写是新建你的复核报告 docs/evidence/M2-T2b-修复复核-GLM.md。端口纪律：后端 18330、前端 dev 端口自定（18330 段起），启动前 netstat 确认空闲，结束后清理全部自启进程。本次复核不需要 AI key——禁读任何 .env 文件。

【背景】T2b 已通过红队（DS-V4-Pro 有条件通过，无 S1）与你的上一轮复现（通过）。此后施工方做了五项修复/回写，你逐项复核：

1. scripts/m2t2a-smoke.mjs 新增 SMOKE_SKIP_MODEL=1 开关（脚本 :12-21 注释、:66-67 常量、:248-258 守卫、:259-401 else 块）。复核要求：(a) node --check 通过；(b) 实跑零模型模式——起后端（无需 AI key，不注入即可；若后端启动强制要求 key 则注入一个占位假 key 并注明）+ 前端 dev server + CDP Chrome，执行 SMOKE_SKIP_MODEL=1 node scripts/m2t2a-smoke.mjs，预期：断言②③③b PASS、断言①为 {pass:null, skipped:true}、退出码 0、全程未发 POST /chat（可从后端日志旁证）；(c) 实跑默认模式冒烟不必重做（你上轮已实测全 PASS），但若时间允许跑一轮更佳。
2. RunStore.java 约 248-255 注释修正（红队 S3-3）：核对新注释两层表述（TTL 值取 lock.ttl 配置 / 续期来自 ConfirmGate 心跳 renewSessionCurrent）与代码事实一致，且 git diff 确认该文件仅注释变化、零行为改动。
3. RunStoreSessionCurrentTest.java 去硬编码（红队 S3-7）：确认 TTL 期望改从 AiProperties 实例实读（约 :47-49、:80、:109、:113-115）；跑 mvn（"<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"）test 全量，XML 口径独立重算，预期仍 263/0/0/0（计数不得变化）。
4. docs/evidence/M2-T2b-施工与验证-K2.8.md 文末"修订（闭环批）"节（:201-229）：核对三条修订的事实准确性——§7 数字勘误（backend 5M+2 新测试+frontend 5M+scripts 1M=13，用 git status 实测复核）、断流收尾口径（引用的 stores/ai.ts:558-563、:719-731、:513-524 行号与描述逐条读码核对）、HITL 增益（ResumeService.java:151-155 口径比对）。
5. docs/M2-T2-返修决策卡.md:70 勘误 5 闭环标记 + docs/M2-排期计划.md v1.5（:1、:11、:65-112 闭环注记节）：核对 a~f 六项内容完整（闭环结论、S2-1 转 T3、S2-2 转 T4、六条观察项、五项顺手项闭环、两条治理留痕），数字与事实（263/0、红队 2×S2+8×S3、冒烟四断言全 PASS）与你掌握的证据一致。

另做全量脱敏/命名纪律扫描（修复批触及的 6 文件：smoke、RunStore.java、RunStoreSessionCurrentTest.java、K2.8 证据、决策卡、排期）：sk- 真实 key、本机绝对路径、<OLD_REPO_DIR>/<OLD_REPO_DIR>/<OLD_REPO_DIR> 任意大小写组合，预期零命中（K2.8 证据 :186 的 sk-… 占位符不算违规）。

【输出】复核报告写入 docs/evidence/M2-T2b-修复复核-GLM.md：逐项 命令/读码 → 证据 → 结论（闭合/不闭合），全标【实测】；无法验证项如实写。结尾总体判定：复核通过 / 有条件通过（列差异）/ 打回。脱敏纪律：禁真实 key、本机路径用 <REPO_ROOT>/<MAVEN_HOME> 占位、禁 <OLD_REPO_DIR> 字样。中文撰写。
```

复核回报后我走：提交官入库（代码修复批 + 文档证据批细分提交、即时 push）→ T2b 正式闭环，然后按队列进 M2-T3 设计卡。
