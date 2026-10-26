<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-016 · 施工复核 · 机械验收 **6/6 PASS、0 FAIL**，且契约关键点逐条与后端源码对齐（含动态断言复测 `parseFormSchema`/`checkFormValues`）。K2.8 交卷质量合格，进入异厂商审查环节。（0c4dbfa1 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`AiPanel.vue`、`FormRenderer.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m17` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt16` 条有正文的 assistant 消息（共 44 条有正文），末条有正文消息为 `m47`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `main`（`main` = 主会话）；原字符 4,071（链内 1 段、合计 4,071）。
> **链号 / 类别**：`MT-016` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 6 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `15%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m47`（7,479 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 6 处。

---

机械验收 **6/6 PASS、0 FAIL**，且契约关键点逐条与后端源码对齐（含动态断言复测 `parseFormSchema`/`checkFormValues`）。K2.8 交卷质量合格，进入异厂商审查环节。

**先落三个裁决**（留痕用）：

- **裁决 GF-B-③**（K2.8 登记项 2）：刷新后表单态不进镜像——契约内兜底（超时收敛）已足够，**本棒不改，记 M2 候选"表单态入镜像/刷新可续填"**。
- **裁决 GF-B-④**：`frontend-tools.ts:14` + `types/tools.ts:34` 过时注释，采纳修复，提交前由批量工作者顺手改掉。
- **裁决 GF-B-⑤**（验收备注预裁）：备注 A（`el-input-number` 内联 width）属务实处理，接受；备注 B（无 schema watcher）、C（非表单 400 码提示不精确）交红队裁定是否真实风险，若裁"不修"则记 M2。

以下两张卡请**并行**开两个对话执行（红队与复核互不依赖）。按你的新要求，结论直接写入仓库指定文档，你无需再复制。

```text
【角色】反方审查 / 红队（DeepSeek V4 Pro）。对一批前端代码做推翻式、对抗性审查，目标是找出真问题，不是走过场。你只审查，不改代码；结论写入指定文档。

【项目】ai-example（AI 辅助配置快速实施平台）。仓库 <REPO>，分支 <OLD_REPO_DIR>，HEAD b41784a。工作区有 6 项未提交改动（GF-B 生成式表单前端棒，作者 Kimi K2.8，与你异厂商）：
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/api.ts
?? frontend/src/components/AiPanel/FormRenderer.vue
?? frontend/src/types/form-schema.ts
用 git diff HEAD 与直接读两个新文件来审查。

【背景契约（后端已用测试锁死，前端必须遵守）】
详见 docs/evidence/GFa-生成式表单后端工具验证.md §8：frontend_tool_request 帧 name=generative_form、args={"form":{…}}；回灌 POST /api/ai/frontend-tool-result；提交 body {runId, toolCallId, result, source}（result 为值 JSON 字符串）；取消 body 带 cancelled:true；400 FORM_RESULT_REJECTED + status:PENDING 表示挂起仍在等待、可同 toolCallId 改值重发；409 DUPLICATE_TOOL_CALL_ID 幂等；帧带 timeoutSeconds/expiresAt。后端源码在 backend/src/main/java/com/example/configmgr/ai/ 下可对照。

【对抗性审查清单（至少覆盖，鼓励加码）】
1. 注入/XSS：schema 文案（title/label/options/description）全链路是否真无 v-html/innerHTML/属性注入；el-select 选项渲染是否安全。
2. 回灌幂等与竞态：重复点击提交、提交中与取消竞态、本地超时与后端超时竞态、400 重发与超时到点竞态、结局帧 frontend_tool_result 到达时 activeForm 是否已收敛；done/error/停止/重置路径是否都清理表单态与计时器（防泄漏到下一轮会话）。
3. 越权/串会话：runId/toolCallId 是否可能跨会话错配；activeRunId 变化时旧表单回灌是否可能污染新 run。
4. 状态机漏洞：submitting 态能否被重复触发；filling→submitting→filling（被拒）转换是否可能卡死；expiresAt 缺失/过去时间戳的行为。
5. 类型与校验绕过：checkFormValues 能否被构造输入绕过（number 的 NaN/Infinity、date 的 Date.parse 宽松性、multi_select 数组去重、boolean 真值）；parseFormSchema 拒收后是否真的必走 cancelled:true。
6. 机械验收遗留 3 条备注，逐条裁定是否真实风险：
   A. FormRenderer.vue:29 el-input-number 用内联 style="width: 100%"（指挥官已预裁：务实处理可接受，你若反对给证据）；
   B. FormRenderer.vue:138 model 只在初始化建值、无 watch(props.schema)——是否存在真实复用路径导致旧值残留；
   C. 后端 400 还可能返回 FORM_RESULT_REJECTED 之外的 code（AiController verdict==null 分支），前端只特判其一——行为是否仍可恢复、提示是否误导。

【输出要求】
把审查结论写入 docs/evidence/GFb-红队审查-DS-V4-Pro.md（新建），结构：问题清单（编号/严重度/位置/证据/复现路径/修复建议）→ 对 A/B/C 的逐条裁定 → 总结论（放行 / 放行带修复项 / 打回）。每个断言标注【实测】/【推断】/【假设】。
脱敏纪律：文档里不出现真实盘符（用 <<OLD_REPO_DIR>> 占位）、不出现密钥（用 ***）、不出现用户名。
禁止：git 任何写操作；改动除结论文档外的任何文件；引入依赖。写文档时用 UTF-8 中文。
完成后回复：问题数、最高严重度、总结论一行。
```

```text
【角色】复核与测试（GLM-5.3）。对 GF-B 前端棒做独立复现验证：不采信作者与验收者的结论，一切自己重跑。你只验证，不改代码；结论写入指定文档。

【项目】ai-example。仓库 <REPO>，分支 <OLD_REPO_DIR>，HEAD b41784a。工作区有 6 项未提交改动（作者 Kimi K2.8）：M AiPanel.vue / stores/ai.ts / types/ai.ts / types/api.ts，新增 components/AiPanel/FormRenderer.vue 与 types/form-schema.ts。

【待独立复现的验证项】
1. git status --porcelain 恰好 6 项；git diff 未碰 backend/、.github/、scripts/githooks、package.json、yarn.lock。
2. cd frontend && yarn typecheck 绿；yarn build 绿（记录耗时与 exit code）。
3. 动态断言复测 types/form-schema.ts：把该文件复制到仓库外临时目录，用 node --experimental-strip-types 跑断言——parseFormSchema 对非法输入（非对象/缺 scenario/空 fields/非法 type/重复 key/enum 无 options/裸字符串 options）一律 null、合法 schema 通过；checkFormValues 覆盖必填缺失/未知字段/enum 越界/date 坏格式/multi_select 越界。断言脚本用完即删，不入仓。
4. 契约静态核对：stores/ai.ts 中 generative_form 特判不落通用执行器兜底；取消与 schema 解析失败路径均带 cancelled:true；400/409 分支区分；本地超时自动 cancelled:true 回灌。与后端 backend/src/main/java/com/example/configmgr/ai/ 下 FrontendToolResultRequest（cancelled 字段）、GenerativeFormRules（FORM_* 错误码）对齐。
5. 视觉纪律抽查：FormRenderer.vue 无真实 <style> 块、无 v-html（注释命中不算）、模板全 el-* 组件。
6. 作者完工报告里的手动冒烟说明（纯前端构造帧路径）是否可执行——审其逻辑可行性即可，不要求真实跑浏览器；如你发现明显不可执行的步骤，如实记录。

【输出要求】
把复核结论写入 docs/evidence/GFb-复核验证-GLM-5.3.md（新建），结构：复现环境 → 逐项 PASS/FAIL + 命令与输出摘录 → 与作者报告/机械验收报告不一致处（如有，重点写）→ 总结论。每个断言标注【实测】/【推断】/【假设】。
脱敏纪律：不出现真实盘符（用 <<OLD_REPO_DIR>>）、密钥（***）、用户名。
禁止：git 任何写操作；改动除结论文档外的任何文件；引入依赖。全程 UTF-8 中文。
完成后回复：逐项结论 + 总结论一行。
```

两份结论入库后我会逐条核对、处置问题清单（修复或显式驳回），然后派批量工作者修那两处过时注释、派提交官细分入库。
