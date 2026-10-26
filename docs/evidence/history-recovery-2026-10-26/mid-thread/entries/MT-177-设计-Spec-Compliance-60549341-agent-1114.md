<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-177 · 设计 · Spec Compliance（60549341 / agent-1114）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m30` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 3 条有正文），末条有正文消息为 `m44`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `60549341` / agent `agent-1114`（`main` = 主会话）；原字符 4,402（链内 1 段、合计 4,402）。
> **链号 / 类别**：`MT-177` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m44`（2,543 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

自证：本子智能体 wire 文件为 `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/<OTHER_SESSION>-bbd4-47c1-98c4-fc81edc0dadd/agents/agent-1113/wire.jsonl`，`profile.bind` 单行实测为 `agentId=agent-1113` / `modelAlias=DeepSeek/deepseek-flash` / `profileName=coder` / `thinkingEffort=high`。

审查包已生成：`<LOCAL_WORK>/_cu-composer-project-r26/phase2-review.diff`（BASE `cb0e926…` → 未提交工作树，1006 行；3 文件，+748/−79；`?? data/` 未入 diff）。未改仓库、未提交、未起停服务、未重跑测试。

### Spec Compliance

- ✅ `send()` 返回结构严格为 `{ outcome, userMessageId }`：空内容/`loading` 早退为 `not-sent + null`（`frontend/src/stores/ai.ts:1493-1502`）；`ok`/开流前异常/断流/主动 stop 分别映射 `ok`/`error`/`interrupted`/`cancelled`（`:1556-1596`）；`skipUserRecord` 返回 `null`（`:1504-1505`）。
- ❌ **SSE 终帧 `error` 实际映射为 `ok`**：`handleFrame` 的 `case 'error'` 只置 `errorCard`/`notice`/`lastRoundOutcome='error'`（`frontend/src/stores/ai.ts:2886-2900`），不置 `stopped`/`streamInterrupted`；而 `sse.ts` 把 `error` 视为 terminal 并**正常 resolve**（`frontend/src/api/sse.ts:157-179`、`:366-368`），因此 `settleOutcome('ok')`（`stores/ai.ts:1575`）返回 `ok`，`AiPanel.vue:2691` 按成功返回，不恢复草稿、不设锚点。这正是重点检查项“开流前错误与 SSE error 映射”的缺口。
- ✅ 组件发送后焦点：清空后立即 `focus({ preventScroll:true })`（`AiPanel.vue:2685-2686`）；`error/interrupted/not-sent` 仅在 `inputText === ''` 时恢复原稿并二次聚焦，已有新输入不覆盖；`cancelled`/`ok` 不恢复（`:2690-2701`）。
- ✅ 恢复路径只写 `inputText`/`editingMessageId`/焦点，不调用 `send()` 或 `recordUserMessage()`（`:2698-2701`）。
- ✅ 重发防重复：新消息失败锚到 `userMessageId`，编辑重跑失败沿用发送前 `editId`，重发走既有 `skipUserRecord`（`AiPanel.vue:2671-2688`；真实 store 用例 `tests/ai-panel-parts-render.spec.ts:3614`、`:3634` 均断言用户消息数仍为 1）。
- ✅ 未改 `canSend`/`stop`/`handleChatError`/`lastRoundOutcome` 语义，未改 API/后端/队列；仅三个允许文件被修改。
- ✅ 阶段一形态/Enter/禁用无回归：阶段一 153 个用例包含在 167 passed 中，全量 438 passed（`phase2-green-targeted.log`、`phase2-full.log`）；targeted 日志 0 条 `Vue warn`。
- ⚠️ 无法从 diff 验证：真实浏览器中的焦点/IME 体验（施工报告已声明未做实机验收）；SSE error 结论来自 `sse.ts`+`handleFrame` 代码路径阅读，因要求不重跑测试，未新增/运行针对性用例。

### Strengths

- 轮次污染防护是认真做的：`settleOutcome` 先判 `isCurrentRound()`（`stores/ai.ts:1556-1559`），`stopped`/`streamInterrupted` 在每轮 `send`（`:1508-1510`）、`reattachActive`（`:1687-1691`）、`resetSession`（`:2437-2438`）均有复位，旧轮 `onClose` 也有身份闸（`:1529`、`:1716`）。我按“旧轮收尾是否会污染新轮”逐一核对，未发现可达污染。
- 失败保稿分支克制且无副作用：不重发、不写记录，只恢复 UI 状态（`AiPanel.vue:2698-2701`）。
- 编辑/重发复用既有同一条链路，没有另造一套“重试”路径；`skipUserRecord` 的语义与注释一致。
- 旧 mock 未被削弱：仅将错误卡用例的 `mockResolvedValue(undefined)` 同步为新结构（`spec:4506`），其余断言保留；全量通过。
- TDD 证据完整：RED 12 failed / 155 passed，失败原因均为缺返回结构/焦点/保稿；GREEN 167/167、全量 438/438、typecheck exit 0。

### Issues

#### Critical (Must Fix)

无。

#### Important (Should Fix)

1. **SSE terminal `error` 未映射为 `error`** — `frontend/src/stores/ai.ts:1556-1566` + `:2886-2900`；`frontend/src/api/sse.ts:157-179`、`:366-368`。
   - 现状：后端 `error` 终帧到达时 `aiApi.chat` 正常 resolve，`settleOutcome('ok')` 走到 fallback，返回 `ok`。组件因此不恢复草稿、不设编辑锚点，与批准口径中 `error` 的语义不符；该路径在现有测试中完全未覆盖（`spec:4755` 起的 store 映射组只测了 `mockRejectedValueOnce` 的开流前异常、断流、stop）。
   - 建议修复：`settleOutcome` 增加终帧判断，例如 `const info = await aiApi.chat(...); outcome = settleOutcome(info.lastFrame?.type === 'error' ? 'error' : 'ok')`；或在 `handlers.onFrame` 捕获 `frame.type === 'error'` 的本地标志。不要直接用 `lastRoundOutcome` 兜底（catch 分支不置它，且其语义被要求保持不变）。并补一条“error 帧到达后正常 resolve ⇒ outcome='error'”的 store 用例。

#### Minor (Nice to Have)

1. **store 注释已过期** — `frontend/src/stores/ai.ts:1498` 仍写“当前 UI 下真实 Enter 到不了这里（输入区 disabled）”。阶段一后流式中 textarea 已可编辑，真正拦住 Enter 的是 `onComposerKeydown` 的 `ai.loading` 分支（`AiPanel.vue:174-188`）；组件侧注释已更新（`:2660-2664`），store 侧漏改。
2. **旧轮覆盖分支缺测试** — `stores/ai.ts:1557-1559` 的 `!isCurrentRound() → interrupted` 未被任何新用例覆盖；本次通过代码阅读确认其优先级能挡住新轮 `stopped`/`streamInterrupted` 污染，但建议补一条“旧轮收尾晚于新轮开始”的用例，锁住这个保守口径。
3. **会话重置时的锚点残留** — 若 `adoptFreshSession` 在发送在途时清空消息（`stores/ai.ts:2397-2405`），旧轮返回 `interrupted` 后组件会把 `editingMessageId` 设为已不存在的 `userMessageId`（`AiPanel.vue:2700`）。下一次发送因 `cut < 0` 回落普通发送（`:2675-2683`），**不会误删历史或重复消息**，只是锚点短暂悬空；可在设置锚点前确认消息仍存在。
4. **全量日志 406 条 `Vue warn`** — `phase2-full.log` 中均为既有 EP 桩/指令解析警告，targeted 日志为 0，非本次引入；按“测试输出应干净”的标准记录，当前任务无需处理。
5. **术语歧义** — `AiPanel.vue:2430` 的“HTTP/网络异常不置 outcome”指的是既有 `lastRoundOutcome`，与本阶段新增的 `AiSendOutcome` 同名易混，可改成“不置 `lastRoundOutcome`”。

### Assessment

**Task quality:** Needs fixes

**Reasoning:** 返回结构、焦点回收、失败保稿、防重复与旧轮污染防护均实现正确且有真实 store 用例支撑；唯一实质缺口是 SSE `error` 终帧仍被归为 `ok`，使批准口径中的 `error` 路径对该场景不可达，需补映射与用例后才能放行。

**READY / NOT READY:** **NOT READY**（仅因上述 1 个 Important；修复后其余项可放行）。
