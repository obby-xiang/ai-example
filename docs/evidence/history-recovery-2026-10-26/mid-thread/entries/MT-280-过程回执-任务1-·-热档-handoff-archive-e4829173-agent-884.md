<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-280 · 过程回执 · 任务1 · 热档（`<LOCAL_WORK>/handoff-archive/交接提示词-2026-10-13-当前态.md`）（e4829173 / agent-884）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`交接提示词`、`demo v6`。
> **稿态**：历史中间稿 —— 该 wire 第 `m120` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt49` 条有正文的 assistant 消息（共 64 条有正文），末条有正文消息为 `m186`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e4829173` / agent `agent-884`（`main` = 主会话）；原字符 2,814（链内 1 段、合计 2,814）。
> **链号 / 类别**：`MT-280` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 16 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `13%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `13%`。
> **与末条归档（138 件）的关系**：末条 `m186`（1,782 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 任务1 · 热档（`<LOCAL_WORK>/handoff-archive/交接提示词-2026-10-13-当前态.md`）

| 项 | 值 |
|---|---|
| 新行数 / 字节 | **103 行 / 36212 B**（旧 35925 B，+287 B；行数不变，仅两行整行替换） |
| **sha256 全值** | `871a63dcc7568eb6c20db72ac8345e4d400757f207938358ac2e6b57ff843b07` |
| **零差异核验** | 取新件，把 L69 / L103 还原为旧两行后重算 = **35925 B / sha256 `ca3f1688b49e85fad4b1345fe40005004c2590bfe9187b12da2b71ba4cb09346` = 旧件 sha（MATCH=True）** ⇒ 除该两行外**全文逐字节等同旧件**（等价于「抽掉两行后与旧件 diff 为零」）✅ |

**L69 新文**：

```
- **服务常开纪律**：前后端服务（后端+前端 dev）须一直启动着，且必须由指挥官会话以**后台 bash（disable_timeout）**启动、**禁子智能体脱机启动**（服务归属会话任务面板，交接可停可接）；交接会话切换时旧会话先停任务、新会话重起；涉及代码修改后须重启。台账=<LOCAL_WORK>\_shared\services.state（任务ID/PID/端口/日志）。
```

**L103 新文**：

```
服务常开纪律生效（2026-10-14 用户直裁，二裁订正=指挥官会话后台 bash 启动、禁子智能体脱机）：后端 8081=task bash-x5tnnzb1（PID 15568）+ 前端 5202=task bash-szjj61dy（PID 31764）已起（台账 <LOCAL_WORK>\_shared\services.state）；交接先停任务后起、改码必重启。旧脱机 32640/30368、旧 dev 5200/PID 7060 均已停。
```

## 任务2 · 台账（`<LOCAL_WORK>/_shared/services.state`，10 行 / 2529 B，旧 2465 B，+64 B）

```diff
 backend   pid=15568  port=8081  cwd=…\backend  cmd="…java.exe" -jar target/config-mgr.jar  start=2026-10-14 21:00:17（外部重启，非本棒启动）  … log=外部进程未接本台账日志…
+backend   pid=15568  port=8081  任务ID=bash-x5tnnzb1（指挥官会话后台 bash · disable_timeout）  cwd=…\backend  cmd="…java.exe" -jar target/config-mgr.jar  start=2026-10-14 21:00:17（实测：CIM CreationDate）  … log=会话任务面板输出（任务 bash-x5tnnzb1）
 frontend  pid=31764  port=5202  cwd=…\frontend  cmd=node node_modules/vite/bin/vite.js --port 5202 --strictPort  start=2026-10-14 21:00:17（外部重启，非本棒启动）  url=…  现状=HTTP 200  log=本棒 20:54 实例日志=…
+frontend  pid=31764  port=5202  任务ID=bash-szjj61dy（指挥官会话后台 bash · disable_timeout）  cwd=…\frontend  cmd=node node_modules/vite/bin/vite.js --port 5202 --strictPort  start=2026-10-14 21:00:17（实测：CIM CreationDate）  url=…  现状=HTTP 200  log=会话任务面板输出（任务 bash-szjj61dy）
 redis     pid=5264   port=6379  …（本行未动）
-# 本棒 20:53/20:54 起的实例…（旧注记一行）
-# 耐久教训：…改用 CIM/WMI…或 schtasks 起法
+# 承载纪律（2026-10-14 用户二裁）：服务一律由**指挥官会话后台 bash（disable_timeout）**启动、**禁子智能体脱机启动**（服务归属会话任务面板，交接可停可接）
+# 耐久教训（首裁期留痕）：…自 harness 调用起的脱机进程会随调用/任务回收被连带杀 —— 本棒 20:53/20:54 的 32640/30368 即在此窗口消失（backend.log 无优雅关闭行；frontend.log 20:58:52 代理 ECONNREFUSED 留痕）
 # 本棒停掉的旧进程：①…14892…；②…7060…
+；③java PID 32640 + node PID 30368（首裁期本棒脱机启动的实例）→ **2026-10-14 21:00 二裁订正已停**（原因=改由指挥官会话后台任务 bash-x5tnnzb1/bash-szjj61dy 承载；netstat 复核：8081/5202 现由 15568/31764 持有）
 # 未触碰：…18672（demo v6 18330）…、18398/18399
 # 工具：jdk=…；maven 包装=…
```

要点：backend/frontend 两行改为 **任务ID + 新 PID + 启动时刻（21:00:17，CIM `CreationDate` 实测）**；停掉清单追加③ **32640/30368（原因=二裁订正）**；redis 行一字未动。

## 服务现态（实测，未启停任何进程）

```
8081 → PID 15568（task bash-x5tnnzb1）  HTTP 503 AI_UNAVAILABLE = 在线（无 AI_API_KEY env）
5202 → PID 31764（task bash-szjj61dy）  HTTP 200
5200 → 无监听（旧 dev 7060 已停）        6379 → PID 5264 memurai（未动）
```

本棒全程未启动/停止任何服务，仅做两处文档落笔与只读核验。
