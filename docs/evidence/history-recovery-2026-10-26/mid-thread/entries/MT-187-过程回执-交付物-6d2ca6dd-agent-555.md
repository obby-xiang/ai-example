<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-187 · 过程回执 · 交付物（6d2ca6dd / agent-555）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`workspace.ts`、`AiPanel.vue`、`TaskSseService.java`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m28` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt4` 条有正文的 assistant 消息（共 7 条有正文），末条有正文消息为 `m52`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `6d2ca6dd` / agent `agent-555`（`main` = 主会话）；原字符 4,851（链内 1 段、合计 4,851）。
> **链号 / 类别**：`MT-187` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m52`（3,404 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 2 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

草案已落盘并完成全部锚点亲验。

## 交付物

| 项 | 值 |
| --- | --- |
| 路径 | `<LOCAL_WORK>\polling-gov\施工卡草案.md` |
| 字节数 | 57859 |
| 行数 | 435 |
| sha256 | `101d221a7a13c61c7d07f91df913cc61c1dd558ce54828d66798c209c10d535d` |
| 主仓库状态 | `HEAD = 6c9c699aa680297ecf0a83ec39eeae8716b68431`，`git status --porcelain` 空（改动零、仅落盘草案一份） |

草案结构：卡头 / §0 刷新速览 / §1 轮询总账（前端 5 + 后端 JobWatchdog 1）/ §2 三步分解（刷新后锚点）/ §3 vitest 锁回归 T1~T7 / §4 验收门禁 / §5 不做清单 / §6 K3 立场 D1~D16 / §7 排除项 E1~E7 / §8 风险反查 R1-1~R3-4 / §9 锚点抽检 15 条 / §10 署名。

## 锚点抽检证据（15 条，摘要；逐条全文见草案 §9）

| 锚点 | 预期 | 实测 | 判定 |
| --- | --- | --- | --- |
| `stores/task.ts` | :32 4000 / :58 声明 / :162 setInterval / :157-168 start / :170-176 stop / :114-116 短路 | 逐处命中 | 一致 |
| `stores/task.ts:294-321` | :298 无 signal、:308-310 静默置 null | 逐处命中，`:299` 仅 intervalMs、`:300-305` 仅 onTick | 一致 |
| `utils/job-watch.ts` | :21=3000 / :46-84 / :96 / :106-110 / :121 | 逐处命中 | 一致 |
| `api/tasks.ts:105-115` | :106 EventSource，无 onerror | 命中共坐实「仅 onmessage」 | 一致 |
| `api/jobs.ts` | :46 signal? / :52 300s / :63-65 return job / :66 sleep | 逐处命中 | 一致 |
| `types/sse.ts` | :337 四类型 / :340-347 TASK_CHANGED 五字段 | 逐处命中 | 一致 |
| `TaskCenterView.vue` | :8 文案 / :351 启动 / :355 停止 | 逐处命中 | 一致 |
| `stores/workspace.ts` | :285 / :442-456(450-454) / :499-513 / :514-517 | 逐处命中 | 一致 |
| `ImportWizardView.vue` | beginWatchJob :840-888 / :806→:818 / :504→:516 / :887 / :1115-1116 | 逐处命中 | 一致 |
| `AiPanel.vue` | :839-841 / :850-853 / :452-454 / :792-805 | 逐处命中 | 一致 |
| `TaskSseService.java` | :73 / :88 / :92-101 | 命中；**附加实测：无周期心跳，:88 是任务级唯一 HEARTBEAT 发布点** | 一致+新事实 |
| 四 Runner/终态点 | Export :271 / Import :216 / Precheck :360 / Publish :364 / JobTerminalWriter :86 / JobService :268 | 全部命中（原卡 Export :256、Publish :327 已漂移） | 一致（用新值） |
| `JobWatchdog.java` | 补登记：258 行 / start :114-123 / scan :159-160 | 全部命中 | 一致 |
| 台账/文档 | M2 :280/:281/:218/:206/:205/:286；requirements :58 | 全部命中；R-88 行内 `startAutoRefresh:182-199`、R-89 行内 `ImportWizardView.vue:748-795` 均**确为漂移值** | 一致（N-4 成立） |
| 基线数字 | setInterval 3 / EventSource 2 / visibilitychange 0 / TASK_CHANGED 消费 0 / 谓词 0；154·12；typecheck exit 0；backend 空 | 全部一致 | 一致 |

## D1~D16 承载确认（全部勾选，§6 逐条承载，裁决文字未改）

- [x] D1 vitest 锁回归（TDD）· 禁新增框架 — 轨道实证 `package.json:11-12` + `vitest.config.ts` + 154/154
- [x] D2 R-94 收窄 + R-94 行尾注记建议义务 — 已写为施工报告交付项
- [x] D3 `utils/polling.ts` + `createPollingController` 签名 — 该路径实测**不存在**
- [x] D4 `utils/task-events.ts` 落点 — 实测**不存在**
- [x] D5 上限 3 + 四出口 close() + 可观测字段（四出口锚点已给）
- [x] D6 realtime 停表 / polling 恢复 3s + 首帧与 onopen 各拉一次 — **附承载注记（提请复核）**
- [x] D7 refreshNow 重置 interval 起点（注释 + 判据 + T2④）
- [x] D8 第 0 步实测 T、阈值 max(2T,10s) — **附实测反例：任务级通道无周期心跳，T 无值可代**
- [x] D9 dirty flag + 收尾补发（锁回归 T4）
- [x] D10 谓词以筛选后列表行为准 + 30s 保底 tick（验收数字随之改「≤1 次」口径）
- [x] D11 苏醒错峰 0~250ms（T2⑤）
- [x] D12 Map<kind,dispose>，蓝本 Export :489-495
- [x] D13 N-2 维持挂账（§7 E1）
- [x] D14 第 0 步测三段基线，原卡「7~8 次/30s」降级为待测
- [x] D15 文案 :8（并提示 :151 注释同改）
- [x] D16 红线全量展开为 §5 十条

## 需要上层注意的四处订正 / 反例（已在草案 §9「订正登记」C1~C4 列明）

1. **任务级 SSE 无周期心跳（最重要）** — `TaskSseService.java:87-88` 仅在订阅时补发一帧 HEARTBEAT；全后端其余 `HEARTBEAT` 命中全属 AI 通道（`ConfirmGate.java:77`=2s、`ResilientChatService.java:96`=5s）；`application.yml:191-198` 的 `app.task.sse` 无 heartbeat 键。⇒ **D8 的 `max(2T,10s)` 无值可代**；且 D6 的「realtime 停表」将失去假死发现面（R3-3 被加重："长时间无帧"在事件通道上是正常态）。草案给出替代建议（realtime 降到 30s 保底而非清零；新鲜度判据只用于"JOB_DONE 后未收敛"短窗），**未改裁决文字，标注请 K3 裁定**。
2. **E2E 基数是 29 不是 28** — `docs/M2-排期计划.md:347` 记 `PASS=29`（GF6 已进主计数），`.github/workflows/ci.yml:108/212` 亦按 29 命名；28 是 GF6 关闭态对照值。门禁已按「≥29 不回退」写。
3. **`SpreadGrid` 标签订正** — 「`readyPromise :137 声明:107`」实为 `readyTimer`（声明 :107、武装 :137）；`readyPromise` 在 `:132-134`。
4. **原卡 §6.6 依据失实** — 现 `package.json:6-12` 含 `test`/`test:watch` 六条，非「只有 dev/build/typecheck/preview」。

另：草案记录了两处**原卡遗漏的台账面** —— R-88 行内 `startAutoRefresh:182-199`、R-89 行内 `ImportWizardView.vue:748-795` 均已漂移（施工报告须同批给更新建议）；`refreshCurrent` 恒空转（`:179-199`，`currentTaskId` 无非空写点）与 R-94 立项口径一致。

## 自证

- **执行者 agent id**：`agent-555`（子智能体，由 K3 会话 `session_6d2ca6dd` 派发）
- **实际运行模型**：`DeepSeek/deepseek-flash`
- **wire 取证**：`<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_6d2ca6dd\agents\agent-555\wire.jsonl`（254 行）→ `grep -o '"modelAlias":"[^"]*"'` **23 命中，全部 `"DeepSeek/deepseek-flash"`**；样本 = 第 15 行 `{"type":"llm.request","agentId":"agent-555","provider":"openai","model":"deepseek-flash","modelAlias":"DeepSeek/deepseek-flash","thinkingEffort":"high",...}`，第 5 行 `profile.bind` 佐证 agentId 归属
- **纪律**：仓库只读（除落盘草案外零改动，`git status` 空白）、未读任何 `.env`、未触碰 trae 线两个仓库

**已跑验证**：`yarn test` = 12 spec / 154 passed；`npx vue-tsc --noEmit` = exit 0 无输出；`git status --porcelain backend/` = 空；§1.5 六条 grep；§9 十五条锚点逐处 `Read`/`grep -n`/`awk` 亲验。

**未做（已在草案 §10 声明）**：未起前后端环境、未做浏览器 DevTools 实测 ⇒ §4 所有请求数/连接数运行时判据仍为【推断】，待 D14 第 0 步回填；未跑 `yarn build` 与 E2E 桩（需桩上游 + Redis 容器）。
