<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-017 · 施工复核 · 指挥官裁决（红队 7 条逐项处置）（0c4dbfa1 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`AiPanel.vue`、`FormRenderer.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m19` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt18` 条有正文的 assistant 消息（共 44 条有正文），末条有正文消息为 `m47`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `main`（`main` = 主会话）；原字符 2,950（链内 1 段、合计 2,950）。
> **链号 / 类别**：`MT-017` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 7 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `5%`。
> **与末条归档（138 件）的关系**：末条 `m47`（7,479 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

两份结论取回并交叉核对完毕。红队 7 条问题（中 2/低 3/信息 2），结论"放行带修复项"；GLM 复核 6/6 PASS 且**独立复现确认了两个中等问题属实**（不采信红队、自行 grep 验证）——双份证据一致，可信度高。脱敏检查两份文档零命中。

## 指挥官裁决（红队 7 条逐项处置）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | 回灌/取消用可变 `activeRunId` 而非表单捕获的 `runId`（中） | **采纳修复**：submit/cancel 直传 `form.runId`，删除死字段 `messageId` |
| 2 | 断流 `onClose` 非终态分支不清表单态/计时器（中） | **采纳修复**：断流路径补 `disarmFormExpiry()` + 清 `activeForm` |
| 3 | 字段 key 未拦 `__proto__`/`constructor`/`prototype`（低） | **采纳修复**：前端 `parseFormSchema` 加原型键黑名单；后端 `GenerativeFormRules` 同步加固**记 M2 候选**（后端契约已被测试锁死，不在本棒动） |
| 4 | 通用 400 失败分支拒绝细节不进表单卡（低） | **采纳修复**：`outcome.message` 并入 `form.error`（备注 C 由此覆盖，红队已证后端该分支为死代码） |
| 5 | 取消回灌失败的错误提示不可见（低） | **采纳修复**：`cancelled` 态 fallback 展示 `form.error` |
| 6 | 提交/超时取消竞态的瞬时 status 覆写（信息） | **采纳修复**：写 status 前校验当前 `activeForm` 同一 `toolCallId`（一行守卫） |
| 7 | 本地超时依赖客户端时钟（信息） | **不改**：与既有确认卡同口径、非本棒新增风险，记 M2 候选 |
| B | FormRenderer 无 `watch(props.schema)` | **采纳红队加固建议**：加 `:key="liveForm.toolCallId"`（一行，显式声明重挂载不变量） |
| A | `el-input-number` 内联 width | **维持预裁**：务实处理，不改 |
| C | 后端 400 死代码分支 | 前端侧由 #4 覆盖；后端死代码清理**记 M2 候选** |

作者修自己的问题是纪律，修复卡给 K2.8：

```text
【角色】主力编码（Kimi K2.8 Preview）——GF-B 修复棒。这是你上一棒（生成式表单前端）的红队修复轮，修复项已全部裁决定稿，你只执行，不重新裁决；拿不准报【待裁决】。

【项目】仓库 <REPO>，分支 <OLD_REPO_DIR>，HEAD b41784a。工作区有你上一棒的 6 项未提交改动（4 改 2 增）+ docs/evidence/ 下 2 份审查文档（红队/复核，不要动它们）。直接在当前工作区继续改。

【必读】docs/evidence/GFb-红队审查-DS-V4-Pro.md（问题清单全文，含位置与证据）。

【修复清单（7 项，全部在 frontend/src 内）】
1.（问题 1，中）stores/ai.ts：submitFrontendToolResult 增加可选 runId 形参（缺省回退 this.activeRunId）；submitGenerativeForm / cancelGenerativeForm / 本地超时自动取消路径一律传表单创建时捕获的 activeForm.runId，不再依赖可变 activeRunId；删除 activeForm 里的死字段 messageId（或实际用作结局帧收敛判据——二选一，倾向删除）。
2.（问题 2，中）stores/ai.ts：send 的 onClose 非终态分支（约 L415-426）补 disarmFormExpiry() + activeForm = null，与 done/error/stop/resetSession 四路径对齐。
3.（问题 3，低）types/form-schema.ts：parseFormSchema 对字段 key 增加原型键黑名单（__proto__ / constructor / prototype 一律拒收返回 null）；buildInitialValues 可顺带改 Object.create(null) 双保险（若改动引起类型报错，只保留黑名单即可）。
4.（问题 4，低）stores/ai.ts：submitGenerativeForm 的通用失败分支把 outcome.message 并入 form.error（表单卡内可见具体原因，不再只有泛化文案）。
5.（问题 5，低）components/AiPanel/AiPanel.vue：挂起卡 fallback 在 cancelled 态下额外展示 form.error（若有）。
6.（问题 6，信息）stores/ai.ts：submitGenerativeForm / cancelGenerativeForm 在 await 之后写 form.status 前，校验 this.activeForm 仍存在且 toolCallId 相同，否则不写（防瞬时覆写）。
7.（备注 B 加固）components/AiPanel/AiPanel.vue：FormRenderer 标签加 :key="liveForm.toolCallId"，显式声明"换表单必重挂载"不变量。

【不改清单（已裁决，勿动）】
- 问题 7（客户端时钟漂移）不改；FormRenderer.vue:29 的内联 width 不改；后端一律不碰；docs/ 不碰。

【验收标准（逐项自证）】
1. cd frontend && yarn typecheck 与 yarn build 全绿。
2. 7 项修复逐项给出代码位置与佐证。
3. 无新增依赖、无 <style> 块、无真实 v-html。
4. git status 改动范围：在既有 6 项之内（可含既有文件的进一步修改），不新增无关文件。
5. 禁止 git 任何写操作，完工保持改动待验。

【交付物】代码改动 + 简明修复报告（直接回复即可，无需写文档）：7 项逐项自证 + 【待裁决】清单（如有）。
```

K2.8 修复交卷后，我派 DS Flash 做机械复验（typecheck/build + 7 项逐点核对），通过后批量工作者顺手修 `frontend-tools.ts:14` 与 `types/tools.ts:34` 两处过时注释，然后提交官细分入库（代码修复与两份审查文档一并入）。
