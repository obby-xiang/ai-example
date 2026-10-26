<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-283 · 过程回执 · 任务1 · 台账 `<LOCAL_WORK>/_shared/services.state`（12 行 / **4136 B**；改前 3552 B，**+584 B**，行数不变）（e4829173 / agent-884）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`交接提示词`。
> **稿态**：历史中间稿 —— 该 wire 第 `m141` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt53` 条有正文的 assistant 消息（共 64 条有正文），末条有正文消息为 `m186`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e4829173` / agent `agent-884`（`main` = 主会话）；原字符 2,858（链内 1 段、合计 2,858）。
> **链号 / 类别**：`MT-283` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 20 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `15%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `25%`。
> **与末条归档（138 件）的关系**：末条 `m186`（1,782 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 0 / 身份 0 / 密钥 2 / 旧仓名与同源仓名 0 处。

---

真实 key 切换已落档。

## 任务1 · 台账 `<LOCAL_WORK>/_shared/services.state`（12 行 / **4136 B**；改前 3552 B，**+584 B**，行数不变）

diff 摘要：

```diff
-backend   pid=28972  …  任务ID=bash-xphqr4bx …  env=AI_API_KEY=<REDACTED> AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub  … model=ci-stub / baseUrl=http://127.0.0.1:18399 …
+backend   pid=23064  port=8081  任务ID=bash-s0mcxned（指挥官会话后台 bash · disable_timeout）  …  start=2026-10-14 21:10:40（实测：CIM CreationDate；真实 key 切换重启）  env=AI_API_KEY=***（真实 key：用户直给、环境变量注入、禁落档） AI_BASE_URL=https://api.deepseek.com AI_MODEL=deepseek-flash  health=http://localhost:8081/api/ai/health  现状=HTTP 200 available=true / code=AI_AVAILABLE / model=deepseek-flash / baseUrl=https://api.deepseek.com / redis.available=true（真实 API 直连回包正常，key 有效）  log=会话任务面板输出（任务 bash-s0mcxned）
-# AI 通道切换口径：现为桩模式（model=ci-stub，available=true）。真 key 到位后重启后端并注入即切真实 DeepSeek …
+# AI 通道切换口径：**已切真实 DeepSeek**（2026-10-14 21:10 实测时刻）：model=deepseek-flash / baseUrl=https://api.deepseek.com / available=true（真实 API 直连回包正常）；**key 由用户直给、环境变量注入、禁落档**（台账/文档一律 ***）。**桩模式回退法** = 重启后端并把 env 三变量改指桩上游 `AI_API_KEY=<REDACTED> AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub`（桩 18399 常驻留作 E2E/核查用）。yml 默认值：AI_BASE_URL 默认 https://api.deepseek.com、AI_MODEL 默认 deepseek-flash（:90 / :96），key 走 AI_API_KEY（兼容 DEEPSEEK_API_KEY 兜底，:89）
 # 本棒停掉的旧进程：①②③④⑤…（原文保留）
+；⑥java PID 28972（桩模式实例，任务 bash-xphqr4bx）→ **2026-10-14 21:10 真实 key 切换已 TaskStop**
```

`stub`（PID 31012 / 18399 / task bash-w7tsw168）、`frontend`（PID 31764 / 5202 / bash-szjj61dy）、`redis`（5264）三行**未动**；「承载纪律」「耐久教训」「未触碰」「工具」四行未动。实测依据：`8081 → PID 23064`，`/api/ai/health` = `available:true, code:AI_AVAILABLE, model:deepseek-flash, baseUrl:https://api.deepseek.com`；桩 `/probe` = `{"ok":true,"stub":"ci-upstream"}`；前端 200；`date` 实测 21:11。

## 任务2 · 热档 `<LOCAL_WORK>/handoff-archive/交接提示词-2026-10-13-当前态.md`

| 项 | 值 |
|---|---|
| 新行数 / 字节 | **103 行 / 36534 B**（改前 36370 B，**+164 B**，行数不变） |
| **sha256 全值** | `ac09892259121026aa81292f31ed500b1c486abcf1bcd91af87ceabc524ffbcd` |
| **零差异核验** | 抽掉本次追加句后重算 = **36370 B / sha256 `a02d5911c6f05bbbded53cadb2d28c3f0a6f672865814e8257196e55abb96148` = 改前 sha（MATCH=True）**；追加句出现 1 次且位于 L103 行尾 ⇒ **其余字节零改动** ✅ |

L103 现文：

```
服务常开纪律生效（2026-10-14 用户直裁，二裁订正=指挥官会话后台 bash 启动、禁子智能体脱机）：后端 8081=task bash-xphqr4bx（PID 28972，桩模式）+ 前端 5202=task bash-szjj61dy（PID 31764）已起（台账 <LOCAL_WORK>\_shared\services.state）；交接先停任务后起、改码必重启。旧脱机 32640/30368、旧 dev 5200/PID 7060 均已停。21:07 起后端挂桩上游 18399（task bash-w7tsw168），AI 通道 ci-stub 激活 available=true；真 key 到位须重启后端注入切换。21:10 起后端切真实 DeepSeek（task bash-s0mcxned，key 环境变量注入禁落档、模型 deepseek-flash、实测回包正常），桩 18399 留作核查。
```

一处口径提示（未擅改，仅供裁）：L103 中段仍留「后端 8081=task bash-xphqr4bx（PID 28972，桩模式）」与「21:07 起后端挂桩上游 18399…真 key 到位须重启后端注入切换」两句 —— 它们记录的是切换前史实（本轮按令只追加），若要与现役一致，可在下轮下令把该前段同步为 `bash-s0mcxned（PID 23064，真实 DeepSeek）`。台账 `services.state` 的 backend 行已是现役口径。
