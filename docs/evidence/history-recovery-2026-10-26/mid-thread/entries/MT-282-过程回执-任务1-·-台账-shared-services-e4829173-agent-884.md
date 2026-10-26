<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-282 · 过程回执 · 任务1 · 台账 `<LOCAL_WORK>/_shared/services.state`（12 行 / 3552 B；改前 10 行 / 2529 B，**+2 行 / +1023 B**）（e4829173 / agent-884）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`scripts/ci`、`demo v6`、`交接提示词`。
> **稿态**：历史中间稿 —— 该 wire 第 `m131` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt51` 条有正文的 assistant 消息（共 64 条有正文），末条有正文消息为 `m186`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e4829173` / agent `agent-884`（`main` = 主会话）；原字符 2,827（链内 1 段、合计 2,827）。
> **链号 / 类别**：`MT-282` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 18 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `17%`。
> **与末条归档（138 件）的关系**：末条 `m186`（1,782 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 0 / 身份 0 / 密钥 1 / 旧仓名与同源仓名 1 处。

---

## 任务1 · 台账 `<LOCAL_WORK>/_shared/services.state`（12 行 / 3552 B；改前 10 行 / 2529 B，**+2 行 / +1023 B**）

diff 摘要（`-` 旧 / `+` 新）：

```diff
-backend   pid=15568  port=8081  任务ID=bash-x5tnnzb1 …  现状=HTTP 503 AI_UNAVAILABLE（无 AI_API_KEY env） …
+backend   pid=28972  port=8081  任务ID=bash-xphqr4bx（指挥官会话后台 bash · disable_timeout）  …  start=2026-10-14 21:06:48（实测：CIM CreationDate；桩模式切换重启）  env=AI_API_KEY=<REDACTED> AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub  health=http://localhost:8081/api/ai/health  现状=HTTP 200 available=true / code=AI_AVAILABLE / model=ci-stub / baseUrl=http://127.0.0.1:18399 / redis.available=true  log=会话任务面板输出（任务 bash-xphqr4bx）
+stub      pid=31012  port=18399  任务ID=bash-w7tsw168（指挥官会话后台 bash · disable_timeout）  cwd=…\<REPO>  cmd=node scripts/ci/stub-upstream.mjs --port 18399（桩上游）  start=2026-10-14 21:06:28（实测：CIM CreationDate）  健康=http://127.0.0.1:18399/probe → {"ok":true,"stub":"ci-upstream"}  用途=桩模式上游（E2E/GF 用例；后端 AI_* 三变量整体指向它）
 frontend  pid=31764  port=5202  任务ID=bash-szjj61dy …（本行未动，实测 5202=HTTP 200，CIM CreationDate 仍 21:00:17）
 redis     pid=5264   port=6379  …（本行未动）
+# AI 通道切换口径：现为桩模式（model=ci-stub，available=true）。真 key 到位后重启后端并注入即切真实 DeepSeek —— 默认 AI_BASE_URL=https://api.deepseek.com、默认 AI_MODEL=deepseek-flash（yml :90 / :96），key 走 AI_API_KEY（兼容 DEEPSEEK_API_KEY 兜底，yml :89）
 # 承载纪律（…二裁…）；# 耐久教训（首裁期留痕）…（两行未动）
 # 本棒停掉的旧进程：①②③…（原文保留）
+；④java PID 15568（无 key 实例，任务 bash-x5tnnzb1）→ **2026-10-14 21:06 桩模式切换已 TaskStop**；⑤node PID 10028（旧桩上游 18399 进程）→ **2026-10-14 21:06 已 taskkill**（netstat 复核：18399 现由 PID 31012 持有）
-# 未触碰：…、18398/18399 核查服务
+# 未触碰：node PID 18672（demo v6 服务 127.0.0.1:18330，用户在验看用）、18398 核查服务（node PID 16780）
```

实测依据（本棒只读复核）：`8081 → PID 28972`（`/api/ai/health` = `available:true, code:AI_AVAILABLE, model:ci-stub, baseUrl:http://127.0.0.1:18399`）、`18399 → PID 31012`（`/probe` = `{"ok":true,"stub":"ci-upstream"}`）、`5202 → PID 31764`（200）、`6379 → 5264`；PID 10028 已消失。

## 任务2 · 热档 `<LOCAL_WORK>/handoff-archive/交接提示词-2026-10-13-当前态.md`

| 项 | 值 |
|---|---|
| 新行数 / 字节 | **103 行 / 36358 B**（改前 36212 B，**+146 B**，行数不变） |
| **sha256 全值** | `58f1ab253eaa5028be8e33138cea998fa707229606f658409ebdc1260861ec8e` |
| **零差异核验** | 将 L103 行尾追加句抽掉后重算 = **36212 B / sha256 `871a63dcc7568eb6c20db72ac8345e4d400757f207938358ac2e6b57ff843b07` = 改前 sha（MATCH=True）** ⇒ 本次**仅追加该句**，其余字节零改动 ✅ |

L103 现文（末尾即本次追加句）：

```
服务常开纪律生效（2026-10-14 用户直裁，二裁订正=指挥官会话后台 bash 启动、禁子智能体脱机）：后端 8081=task bash-x5tnnzb1（PID 15568）+ 前端 5202=task bash-szjj61dy（PID 31764）已起（台账 <LOCAL_WORK>\_shared\services.state）；交接先停任务后起、改码必重启。旧脱机 32640/30368、旧 dev 5200/PID 7060 均已停。21:07 起后端挂桩上游 18399（task bash-w7tsw168），AI 通道 ci-stub 激活 available=true；真 key 到位须重启后端注入切换。
```

提示一处口径差（未擅改）：L103 前段仍写「后端 8081=task bash-x5tnnzb1（PID 15568）」，而现已切换为 `bash-xphqr4bx（PID 28972）`；本轮按令**只追加、未动其余**，如需同步该前段请下令，我再补一笔。
