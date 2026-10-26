<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-018 · 设计 · 推进 GF-C。先出 GLM-5.3 用例设计卡（结论直接写仓库文档）：（0c4dbfa1 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`generative_form`、`backend/src/main/java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m25` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt24` 条有正文的 assistant 消息（共 44 条有正文），末条有正文消息为 `m47`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `main`（`main` = 主会话）；原字符 2,403（链内 1 段、合计 2,403）。
> **链号 / 类别**：`MT-018` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 9 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `7%`。
> **与末条归档（138 件）的关系**：末条 `m47`（7,479 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 3 处。

---

推进 GF-C。先出 GLM-5.3 用例设计卡（结论直接写仓库文档）：

```text
【角色】复核与测试（GLM-5.3）——GF-C 端到端联合验证棒的用例设计。你只设计与取证，不改产品代码、不做 git 任何写操作；结论写入指定文档。

【项目】ai-example（AI 辅助配置快速实施平台）。仓库 <REPO>，分支 <OLD_REPO_DIR>，HEAD d19da30（GF-B 已入库：前端 FormRenderer + 生成式表单通道）。你在 Windows（Git Bash）下工作。

【背景】
- DC-15 限定生成式 UI：对话内表单两场景——筛选条件（FILTER）与询问澄清（CLARIFY），白名单 schema 驱动。
- 后端 GF-A 已锁契约（docs/evidence/GFa-生成式表单后端工具验证.md §8）：工具 generative_form（scope page:tasks / task:*）；前端收到 frontend_tool_request 帧（args={"form":{…}}，含 toolCallId/timeoutSeconds/expiresAt）；回灌 POST /api/ai/frontend-tool-result：提交 {runId, toolCallId, result, source}（result=值 JSON 字符串）、取消 {runId, toolCallId, cancelled:true}；400 FORM_RESULT_REJECTED + status:PENDING 可同 toolCallId 改值重发；409 DUPLICATE_TOOL_CALL_ID 幂等；用户取消终态 FRONTEND_CANCELLED。
- 前端 GF-B 已落地（docs/evidence/GFb-红队审查-DS-V4-Pro.md 与 GFb-复核验证-GLM-5.3.md 可读）。
- 现有 E2E：scripts/verify-e2e.ps1（PowerShell，22 用例 + Q8 项，全绿），其中 TC16 已有"前端工具挂起+回灌续跑"的先例（navigate_to/select_definitions）——脚本本身扮演前端角色（读 SSE、POST 回灌），无浏览器。

【任务】设计 GF-C 的 E2E 用例方案，产出设计文档 docs/evidence/GFc-E2E用例设计-GLM-5.3.md（新建，UTF-8 中文）。

必须回答的设计问题：
1. 触发策略：真实模型（deepseek-flash）不保证主动调用 generative_form。设计确定性的提示词策略（如显式指令"请用表单收集筛选条件"），并给出降级判据——模型连续不调用时如何判定是模型行为问题还是产品缺陷。先读 backend AI 相关系统提示词/工具描述（backend/src/main/java/com/example/configmgr/ai/ 下），确认工具描述里有没有引导模型何时用 generative_form。
2. 两个场景的用例定义（每个场景给出：前置数据/页面上下文、用户提示词原文、预期帧序列、断言点）：
   a. 筛选条件（FILTER）：模型发 schema →（脚本扮演前端）回灌值 JSON → 续跑产出正确结果（如导出/筛选生效）；
   b. 询问澄清（CLARIFY）：模型发 schema → 回灌 → 续跑；
   c. 取消路径：回灌 cancelled:true → 后端 FRONTEND_CANCELLED 终态 → 模型收到取消回填后续跑；
   d. 复核拒绝路径：回灌一个违反 schema 的值 JSON → 400 FORM_RESULT_REJECTED 且挂起保持 PENDING → 同 toolCallId 改值重发成功。
   e.（可选）幂等：已决条目重复回灌 → 409。
3. 与 verify-e2e.ps1 的集成方式：新增用例如何嵌入现有脚本结构（编号建议、断言风格与现有用例一致、清理责任），保持既有 22 用例零扰动。先通读 scripts/verify-e2e.ps1 弄清其 SSE 读取/回灌/断言的既有机制，设计必须复用这些机制。
4. 逐用例的预期证据形态（帧序列摘录、HTTP 状态码、后端日志关键字），供后续执行棒对照。
5. 观察清单登记：GF-B 遗留"同一 toolCallId 双消息各挂挂起卡致 FormRenderer 双实例"是浏览器层现象，verify-e2e.ps1 层面能否覆盖？若不能，写明理由并给出替代验证建议。
6. 风险与边界：真实模型的不确定性如何隔离（用例失败时如何区分"模型没按预期调工具"与"链路缺陷"）。

【验收标准】
- 设计文档自查：每个用例可机械执行（提示词、断言、预期码全部具体）；不依赖浏览器；既有 22 用例不受影响。
- 每个关键断言标注【实测】（你实际读码/跑命令验证过）/【推断】/【假设】。
- 脱敏：文档不出现真实盘符（<<OLD_REPO_DIR>>/<REPO_ROOT> 占位）、密钥（***）、用户名。
- 禁止：git 写操作；改产品代码；引入依赖。临时文件放仓库外并清理。

【交付物】设计文档写入仓库（保持未提交，提交官统一入库）+ 回复一行总结论（用例数、覆盖路径、是否发现阻碍性问题）。
```

GLM 交设计稿后我的流水线：我审设计 → K2.8 实现脚本增补 → DS Flash 真实执行 → DS V4 Pro 红队审脚本 → 全绿入库，DC-15 闭环。
