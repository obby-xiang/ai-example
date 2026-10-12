# 施工报告 · R-154 后端面 #16（历史分页 · 仅分页参数，DS 系）

## 署名（DC-19 三要素）

- 实际运行模型：`DeepSeek/deepseek-v4-pro`（profile=agent，thinkingEffort=high）
- 会话 id：`session_ebd21528-e6d6-457d-87a2-74a89b178645`
- wire 实证：`agents/main/wire.jsonl` 首条 `profile.bind` = `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",…}`（亲读单行）

## 开工核验

- 卡 sha256 现算 `a0d2c80a…f35a` 一致；开工 `git rev-parse HEAD` = **d775b9f**（R-144 已入库，开工闸满足）；工作树净（仅 `?? data/`）。
- 前置调研件已读：`调研-AI对话组件-本仓盘点`（「历史分页｜无｜单次全量」= #16 缺口）与 `调研-工具调用UI-本仓根因`。

## 改锚表（卡基准 06d95e7 → 开工 HEAD d775b9f）

| 锚点 | 卡值 | 现值 | 原因 |
|---|---|---|---|
| `AiController.history` `@GetMapping` | :508 | :512 | R-144 携 toolCallStates 落码 +4 行 |
| 方法体 | :509-533 | :513-540 | 同上（+8 行：javadoc + toolCallStates 块） |
| `memory.maxMessages` | `AiProperties.java:76-79` | :76-79（`=40`） | 无漂移 |

## 改动清单（2 文件，零超面）

1. **`AiController.java`** `history` 端点：新增可选 `@RequestParam offset/limit`；抽出 `toHistoryItem(Message)`；切片 `items.subList(from, to)`（`from=min(total,max(0,offset))`、`to=limit==null?total:min(total,from+max(0,limit))`）；响应体**新增** `total`（窗口总条数）与 `hasMore`（`from>0`，切片前仍有更早条目）；既有 `sessionId/count/messages` 形状不变（`count`=本次返回条数）。
2. **`AiControllerHistoryPayloadTest.java`**（R-144 既有类内新增 4 例分页断言，复用 `controller()` 桩）。

## #16 分页语义（窗口 40）

- 语义 = **记忆窗口内切片**（`memory.maxMessages=40`，非无限历史），卡面与报告同口径。
- `messages` 按窗口序（旧→新）切片；`offset` 从最旧端跳过、`limit` 限条数。
- **默认（不传参）保持全量语义**：`count=total`、`hasMore=false`，不误伤 mirror/reattach 全量路径；前端 `history()` 无参调用零影响。
- 边界：负 `offset/limit` 归 0；`offset>total` 归空片；`limit=0` 空片。

## 硬门槛实测（自跑）

- 后端全量 `mvn -B test`：**490 / 0 / 0 / 0 · BUILD SUCCESS**（486 基线 + 4 新增分页用例，归因 = 本面 `AiControllerHistoryPayloadTest` +4；**77 XML**，与基线同，无新增类——surefire 目录 78 含 1 个 R-148 已删类 `AiPoolCapacityBudgetTest` 的历史残留，不计）。
- `node scripts/ci/check-docs.mjs`：**6/6** 断言绿，exit 0。
- 前端三项（vitest/typecheck/build）= 前端面 K2.8，本面不涉。

## 超面自证 / §四未做项

- 改动面 = 仅 `AiController.java`（分页参数）+ 既有测试类 +4 例；未触前端、未触其它后端文件。
- §四未做（复述，不夹带）：markdown/高亮、图表/表格/citation/媒体段、artifact/语音/斜杠/@、reasoning 帧、#1 历史侧全文回存与 `ToolResultLimiter` 改造、#7 反向项、#12 runtime 建议词、token/耗时、跨页签持久化、快捷键/aria；后端仅加分页参数、不改既有字段语义与响应体形状。

## 结论

**后端面 #16 完工**（分页参数落地；490/0/0/0 全绿 + check-docs 6/6；零超面；零 git 写；未动 data/）。

## 收口修注记（R-154 后端面 #16 · DS 系续接）

- 自证（DC-19）：实际运行模型 = `DeepSeek/deepseek-v4-pro`；会话 id = `session_bf7a4f62-9829-438b-bfe8-8af3239fe0e5`；wire 首条 `profile.bind` 亲读单行（原文，本注记截断引）= `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent running on a user's computer.…`（单行全长 24383B）。
- 修项①（MINOR-1 溢出安全 clamp，`AiController.java:528`）：`from + Math.max(0, limit)` → `(int) Math.min((long) total, (long) from + Math.max(0, limit))`；消除 `offset≥1 ∧ limit=Integer.MAX_VALUE` 的 int 负溢出 → `subList` 越界 500 面；「切片窗口内」语义不变。
- 修项②（边界测试 +2，`AiControllerHistoryPayloadTest.java`）：追加 `paginationNegativeOffsetAndLimitClampToZero`（负 offset + 负 limit ⇒ count=0、total 保留、hasMore=false、空片）与 `paginationOffsetBeyondTotalReturnsEmptySliceWithTotal`（offset>total ⇒ count=0、total 保留、hasMore=false、空片）。为锁死 ② 的 `hasMore=false`，`AiController.java:535` `hasMore` 由 `from > 0` 收窄为 `from > 0 && from < total`（offset 越界即空片、hasMore=false，javadoc 同步微调）；既有四例分页断言逐项不变，已随全量复跑验证。
- 复跑实测（`bash <本机 mvn 运行脚本> -B test`）：**492 / 0 / 0 / 0 · BUILD SUCCESS**（490 基线 + 2 新增，MVN_EXIT=0）；定向类 `AiControllerHistoryPayloadTest` = 9/0/0/0。
- 时刻：2026-10-12T23:55:18+08:00。
