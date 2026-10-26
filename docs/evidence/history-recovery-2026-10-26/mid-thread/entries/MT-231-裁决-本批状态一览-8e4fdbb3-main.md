<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-231 · 裁决 · 本批状态一览（8e4fdbb3 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`、`ai-example-code/ai-example-main`、`ConfirmGate`、`ResilientChatService`。
> **稿态**：历史中间稿 —— 该 wire 第 `m11` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt11` 条有正文的 assistant 消息（共 49 条有正文），末条有正文消息为 `m54`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8e4fdbb3` / agent `main`（`main` = 主会话）；原字符 2,851（链内 1 段、合计 2,851）。
> **链号 / 类别**：`MT-231` / **裁决**；判定：助手裁决意见——**不构成用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m54`（8,894 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

MINOR 收口完成。四项裁决：MINOR-1 绑定断言锁**下批收口**；观察②维持登记知悉（不排强制回落实验）；观察④随 E2E 时序包同批定；空号措辞已知悉（未来取用 R-102/R-103 时回改两处）。

施工+复核已闭环。现在到 **GLM 红队**环节——这是异厂商模型，按规定由你转交任务卡。范围=本地全部未 push 三笔（苞 C/D/E）+ 当前工作树全部未提交改动（苞 A/B/F/G+修编+收口）。

## 本批状态一览

| 项 | 状态 |
|---|---|
| 后端测试 | 389/0/0/0（60 XML，只增不减） |
| E2E 桩模式 | 29/29 绿（Δ=+4s） |
| 前端 typecheck+build | 绿 |
| check-docs.mjs | 6/6 |
| 运行值独立复现 | confirmTimeout=240 / frontendToolTimeout=120 |
| 复核 | 带条件 PASS（0 BLOCKER/0 MAJOR，3 MINOR 已收口/登记，4 观察已登记） |

## GLM 红队任务卡（请原样转交 GLM）

```
你是 GLM 红队审计员，对 ai-example 项目一批「未 push 变更」做对抗性审计。你在 Windows Git Bash 环境工作。你只读审计：禁止编辑任何文件、禁止 git add/commit/push、禁止起服务占端口（如需运行测试，仅允许后端测试命令，构建产物除外）。可派 DeepSeek 子智能体做机械检索，但结论与定级必须由你本人做出。

## 审计对象
主仓库 <REPO>（分支 main）。
范围 = 两部分：
1. 本地已提交未 push 三笔：a56613d（前端超时根常量化 HTTP_TIMEOUT_MS）、6d5162b（分页缺省 50→20）、51ee49c（容量链+Hikari 10 依据注释）——用 git show <sha> 审。
2. 工作树全部未提交改动（git status / git diff）：数字规格小批苞 A/B/F/G+修编——
   - 苞 A：app.ai.hitl.timeout 拆双键 confirm-timeout=240s / frontend-tool-timeout=120s（AiProperties/ConfirmGate/application.yml/AiController /health 增 frontendToolTimeoutSeconds/ResilientChatService 诊断快照/javadoc 同步/测试适配+确认门参数化新用例）；
   - 苞 B：list_tasks limit 10→20（常量 LIST_TASKS_MAX_ROWS）+截断告知+@Tool description 同步+新测试类 AiToolsListTasksTruncationTest；
   - 苞 F：scripts/verify-e2e.ps1 读窗 3000/3500→5000 三处+函数头注释；scripts/m2t2a-smoke.mjs AWAIT_MS 事件驱动（deriveAwaitCap 读 /api/ai/health resilience.totalBudget+回落 330s；readSse 增 until/stopReason）；
   - 苞 G+修编：docs/adr/DECISION-REGISTER.md 增 DC-16（数字规格纪律四条）、docs/README.md 指针、docs/M2-排期计划.md 去向表 R-100/R-101/R-104/R-105/R-106 立项+注记+重编号留痕、根 README 与 REGISTER:32 的 DC-01~16 同步。

## 锚定防夹带（必做第一步）
对审计起点打钉：执行 git diff HEAD > 临时文件并计算其 SHA-256（含已提交三笔用 git diff 6e59e0c..HEAD 合并视角，或分开打钉均可，报告中写明口径与哈希值）。报告须含：哈希值、git status 条目数、diff --stat 全量清单。提交侧将以此哈希核对防夹带。

## 既有验证基线（供你核对，不得直接采信——你的职责是找反证）
后端 XML 口径 389/0/0/0（60 个 TEST-*.xml）；E2E 桩模式 29/29；前端 typecheck+build 绿；node scripts/ci/check-docs.mjs 6/6；/health 运行值 confirmTimeoutSeconds=240 / frontendToolTimeoutSeconds=120。

## 重点对抗方向（不限于此）
1. 双键拆分是否有活代码仍读旧键 app.ai.hitl.timeout 或语义串读（确认门 240s 被前端工具路径误用，或反之）；grep hitl.timeout 区分冻结文档行尾注记与代码活引用。
2. ConfirmGate.awaitDecision / awaitFrontendResult 取值路径（ConfirmGate.java 约 :191/:200）。
3. list_tasks 截断告知逻辑边界（恰好 20 条不应告知；21 条应告知且总数正确）。
4. m2t2a-smoke.mjs 事件驱动改造的正确性：until 命中与 stopReason 归因是否有漏分支；deriveAwaitCap 三路回落是否掩盖配置错误。
5. 读窗 5000 三处是否有第四处遗漏；ps1 注释与代码是否一致。
6. 文档表列数、DC-16 编号唯一性、R-102/R-103 空号与留痕措辞自洽。
7. 是否有密钥/盘符/用户名/内网 IP 写入仓内文件；命名纪律违规（"main"+分隔符+"v2" 任意大小写组合、零分隔连写）。
8. 测试是否真的锁住了行为（试着脑内构造一个能绕过断言的错误实现）。

## 输出要求
报告落盘 <LOCAL_WORK>/numeric-spec-audit/GLM红队报告-小批.md（脱敏：禁盘符/用户名/内网 IP，用 <MAIN_REPO>/<SCRATCH> 占位；全中文）。发现分级 BLOCKER/MAJOR/MINOR/观察，每条给：文件#行号主锚、问题、复现/构造路径、建议修法。无发现也要写明对抗路径与为何排除。末尾给总体结论：PASS / 带条件 PASS / FAIL。
```

红队期间我停手等待。报告回来后我做裁决，然后派提交官统一提交+push+CI 守夜闭环（预计数分钟，属规定动作）。
