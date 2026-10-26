> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-203` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：13720 B / 9322 字符 / 167 行；行指纹覆盖率（仓内 + 落盘语料）5.9%
> - 类别：**过程核验**
> - 脱敏：路径 2 / 会话标识 3 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## evidence 目录清单（Glob 结果）

`<SCRATCH>/issue2-reports/evidence/` 共 **49 个文本文件 + 46 个 png**（另有空目录 `downloads/`，无文件）。

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| `probe-v2before-*.json` | **12** | 与 v2after 一一同名对应 |
| `probe-v2after-*.json` | **12** | cross-open / cross-missing-file / same-task / task-missing / issue1-regress-switch-task / issue1-regress-mounted-no-task / jobwatch-switch / dirty-switch-warning / slowfiles-navigate-then-open / slowfiles-navigate-then-download / navigate-then-open-immediate / navigate-then-download-immediate |
| 同名 `.png` 截图 | 24 | v2before/v2after 各 12 |
| 早批 `probe-before-*.json` / `probe-after-*.json` | 6 / 9 | 批 1 存档 |
| 其他 | `probe-src.ts`、`probe-run.mjs`、`probe.html`、`fix.diff`、`gate-{build,typecheck}-v{1,2}.log` | — |

---

## 抽查一：场景 K2（慢 files 1.5s + navigate_to 后紧邻 open）+ L（download）

### K2 / open（`probe-v2before|v2after-slowfiles-navigate-then-open.json`）

| 字段 | v2after（`11:35:06.662Z`） | v2before（`11:34:20.756Z`） |
| --- | --- | --- |
| `K2.execution.ok` | **true** | **false** |
| `availability` | `available` | `degraded` |
| `result` | `已打开任务 #1 的 CURRENCY 在线编辑器（导出结果表格）` | 见下方原文（含「（无）」） |
| `K2.newCalls` | `["GET /tasks/1/files → 200", "GET /tasks/1/files/CURRENCY → 200"]` | `[]` |
| `K3`（~0.5s 后） | ok=true（同文案），`newCalls=[]` | **仍 ok=false**（同「（无）」文案），`newCalls=["GET /tasks/1/files → 200","GET /tasks/1/files/CURRENCY → 200"]` |
| `K4`（窗口外） | ok=true | ok=true（自愈） |

v2before K2 文案原文：

```
打开导出文件在线编辑器执行失败：任务 #1 里没有 CURRENCY 的导出结果文件（该任务现有导出结果：（无））。请在任务 #1 上完成 CURRENCY 的导出后重试，或核对传入的 taskId 与 defCode；本次未打开在线编辑器。请提示用户手动重试。
```

时序线索（无 `elapsed` 字段，文件键只有 `phase/scenario/capturedAt/ok/probe/pageDownloads/consoleLogs/pageText`）：慢 files 桩使 `K1_navigate_to_task1` 的 `newCalls` 只有 `["GET /tasks/1 → 200"]`（files 尚未回），窗口被拉宽；v2after 的 K2 命中窗口且正确等待在途绑定（`newCalls` 里两个 files 请求）、v2before 的 K2 读到刚清空的 `exportResults` 而假阴性。对照「正常速度」版 `navigate-then-open-immediate`（v2before）：K1 的 `newCalls` 已含两个 files 请求 → 窗口未命中，K2 也 ok=true，与「窗口宽度依赖后端延迟」的解释自洽。

### L / download（`probe-v2before|v2after-slowfiles-navigate-then-download.json`）

v2after 原文：
```
L2.execution.ok = true   availability = available
result = 已触发下载任务 #1 的 CURRENCY 导出文件（在线编辑后的内容）
newCalls = ["GET /tasks/1/files → 200", "GET /tasks/1/files/CURRENCY → 200"]
messages = ["success: 已下载 <KIMI_SESSION>.xlsx"]
downloads = [{"name": "<KIMI_SESSION>.xlsx", "bytes": 6061}]
pageDownloads[0] = {"event": "willBegin", "suggestedFilename": "<KIMI_SESSION>.xlsx"}
```
v2before 原文：
```
L2.execution.ok = false  availability = degraded
result = 下载导出文件执行失败：任务 #1 里没有 CURRENCY 的导出结果文件（该任务现有导出结果：（无））。请在任务 #1 上完成 CURRENCY 的导出后重试，或核对传入的 taskId 与 defCode；本次未触发下载。请提示用户手动重试。
newCalls = []   downloads = []
pageDownloads = 仅探针自身的 issue2-probe-v2before-….json 落盘（3454 bytes）
```

**结论**：K2/L 两场景 pass，假阴性复现 ↔ 修复后可判可下的方向正确。
**一处口径修正**：JSON 里**没有** CDP 层字段（顶层键只有 `phase/scenario/capturedAt/ok/probe/pageDownloads/consoleLogs/pageText`），「CDP `downloadWillBegin` 双证据」在 JSON 中只能由 `pageDownloads[].event="willBegin"` 体现；报告若宣称另有 CDP 记录，需另附来源。

---

## 抽查二：场景 M（R2 脏表格提醒）

v2after（`probe-v2after-dirty-switch-warning.json`）原文：

```
M1_cross_open_task3: tool=open_export_file_editor args={"taskId": 3, "defCode": "PROJ_APPROVE"}
  ok=true avail=available  url=/export?taskId=3  ws.taskId=3
  result=已打开任务 #3 的 PROJ_APPROVE 在线编辑器（导出结果表格）
  messages=["warning: 表格有未保存的在线编辑内容，本次切换任务已丢弃这些改动（可先用「保存编辑到服务端」写回再切换）"]
R2_after_switch.messages=["warning: 表格有未保存的在线编辑内容，本次切换任务已丢弃这些改动（可先用「保存编辑到服务端」写回再切换）"]
allMessages=同上单条
```

v2before 同字段：`M1.messages=[]`、`R2_after_switch.messages=[]`（**静默**），但 `result/ok/url/ws.taskId/newCalls` 与 v2after **逐字相同**（切换照样成功，无阻断）。

前置置脏证据两份一致：`R2_dirty_mark = {"emitted":"SpreadGrid.emit(data-changed) 已触发","recentActions":[…,"导出结果表格内容已修改（尚未保存到服务端文件）"]}` —— 说明 before 的静默是「有脏但无提示」，不是「没置脏」。

**结论**：pass（提醒出现且不阻断切换）。

---

## 抽查三：场景 N（R3 作业 watcher 停表）

`/jobs/77` 精确计数（按 `probe.allCalls` 数组内 `"GET /jobs/77 → 200"` 元素计）：

| 指标 | v2after | v2before |
| --- | --- | --- |
| `N2_cross_open_task3.newCalls` 内 `/jobs/77` | **0** | **0** |
| `allCalls` 内 `/jobs/77` 总数（含 N1 启动时的 1 次） | **1** | **3** |
| 改绑后**新增轮询数** = `R3_after_switch.jobPollsDuringWait` | **0** | **2** |
| `R3_after_switch.jobPollsAtSwitch / jobPollsAfterWait` | 1 / **1** | 1 / **3** |
| 原文文件内子串 `/jobs/77` 出现次数（含 pageText 与 compact 重复） | 5 | 9 |

切换后 DOM 原文：

- v2after `R3_after_switch.dom`：`jobPanel="尚未启动作业"`, `jobProgress=null`, `jobTags=["进行中","全局","项目","项目"]`，`head="导出配置向导 #3 · 项目审批导出 进行中 … 尚未启动作业 可在线编辑表格内容；…"`
- v2before `R3_after_switch.dom`：`jobPanel="在跑作业面板在场"`, `jobProgress="3 / 10"`, `jobTags=[…,"运行中"]`，`head="… 运行中 （实时通道已降级为轮询）3 / 10 取消作业 可在线编辑表格内容；…"`

`pageText` 关键词计数（v2after / v2before）：`尚未启动作业` 11 / 2、`在跑作业面板在场` 4 / 8、`取消作业` 4 / 9（注意 v2after 的 pageText 同时包含 N1 时段的旧面板文本，故计数不为 0；判定应以 `R3_after_switch.dom` 为准）。

**结论**：pass —— 改绑后 0 次新增 `/jobs/77`，面板清空为「尚未启动作业」；before 为 2 次新增轮询且旧任务面板残留。

---

## 抽查四：回归组逐字相同宣称（a / c / d / e / H / I）

比对方法：对 `probe` 子对象做**全字段递归 diff**（含 `result/ok/availability/url/workspace.taskId/newCalls/messages/downloads/dom/allCalls/allMessages/enter/…`），唯一的已知易变项做归一化（`phase` 标记、探针自身落盘文件名、blob UUID、`receivedBytes`）。

| 组 | 场景文件（before↔after） | 逐字相同 | 差异 |
| --- | --- | --- | --- |
| a 跨任务 open | `cross-open` | ✅ | 仅 `probe.phase`（`v2before`↔`v2after`）与探针自落盘 blob/文件名；`ok=true`、`result=已打开任务 #1 的 CURRENCY 在线编辑器（导出结果表格）`、`newCalls=["GET /tasks/1 → 200","GET /tasks/1/files → 200","GET /tasks/1/files/CURRENCY → 200"]`、`url=/export?taskId=1`、`ws.taskId=1` 全部一致 |
| c 跨任务无该文件 | `cross-missing-file` | ✅ | 同上仅 phase；`ok=false`、`availability=degraded`、`result` 含「（该任务现有导出结果：CURRENCY）」、`newCalls` 三项一致 |
| d 同任务 open+download | `same-task` | ✅ | 同上仅 phase；xlsx 落盘 UUID 不同，`bytes=6061` 一致 |
| e 任务 404 | `task-missing` | ✅ | 同上仅 phase；`ok=false`、`url=/export?taskId=3`、`ws.taskId=3`、`newCalls=["GET /tasks/404 → 404"]`、`result` 含「任务 #404 无法加载（任务不存在：404）：未切换向导，当前仍绑定任务 #3」一致 |
| H 已挂载无任务 | `issue1-regress-mounted-no-task` | ✅ | 同上仅 phase；H1/H2 两步全字段一致 |
| I 切任务后 | `issue1-regress-switch-task` | ✅ | 同上仅 phase；I1/I2/I3 三步（含 `availability=unavailable` 的拒绝文案）全字段一致 |

归一化后 `probe` 与 `pageText`（叙述段 + compact 段）**完全相等（True）**，差异字符数恰等于 `v2before`→`v2after` 的 −1 字符（如自落盘 `receivedBytes` 2657 vs 2656），可判定为**纯粹的 phase 标记差异**，无实质行为差异。

---

## 抽查五：d 组 v2after 同任务零额外请求

v2 批的 d 组是**一个 JSON 内含两步**（`probe-v2after-same-task.json`，probe 键：`enter, D1_open_same_task, D2_download_same_task, allCalls, allMessages, downloads, phase`），不另存 download 单独文件。`newCalls` 字段原文：

```
D1_open_same_task     : "newCalls": []
D2_download_same_task : "newCalls": []
```

`allCalls` 全程仅 5 条（`GET /tasks`、`GET /definitions`、`GET /tasks/1`、`GET /tasks/1/files`、`GET /tasks/1/files/CURRENCY`，均为进入页面时发生）。v2before 同字段亦为 `[]` —— 同任务路径零额外请求成立。

---

## 扫描六：脱敏/命名纪律（C7）

扫描范围：`<SCRATCH>/issue2-reports/` 下全部非 png 文本文件（`*.md` 2 个 + `evidence/` 47 个，含 `*.json` 24+15、`*.log` 4、`*.diff`、`*.ts`、`*.mjs`、`*.html`）= **49 个文件**。

| 组 | 正则 | 扫描文件 | 原始命中 | 排除后残余 | 判定 |
| --- | --- | --- | --- | --- | --- |
| a 盘符绝对路径 | `[A-Za-z]:[\\/]` | 49 | **90 行** | **0** | ✅ 零残余 |
| b 密钥形态 | `sk-` | 49 | **19 行**（10 种上下文） | 严格密钥形 `(?<![A-Za-z0-9])sk-[A-Za-z0-9]{8,}` = **0** | ✅ 零真命中 |
| c 禁则命名 | `(?i)main[\-_ \.]{1,3}v2` | 49 | **0** | — | ✅ 零命中 |

命中明细：

- **a 组 90 行全部落在 URL 协议段内**（形如 `blob:http://localhost:5211/<uuid>` 与 `https://github.com/vuejs/router/blob/main/...`）——即 `p:` + `//` 被正则误捕，非 Windows 路径。按「剥离 `[A-Za-z][A-Za-z0-9+.-]*://` 后再匹配」重扫，残余 **0**。未发现任何 `X:\` 或 `X:/` 形式的本地路径（`fix.diff` 的路径均为仓库相对路径 `a/frontend/...`）。
- **b 组 19 行全部是 `sk-` 子串误捕**，来自单词 `task-missing` / `same-task-missing-download`（如 `"scenario": "task-missing",`、`suggestedFilename": "issue2-probe-after-task-missing.json"`、`probe-run.mjs:30` 的场景清单、`probe-src.ts:446/450` 的 `scenario ===` 判断）。无任何真实 `sk-…` 密钥形态。
- **c 组零命中**。报告统一使用 `<MAIN_REPO>` / `<SCRATCH>` 占位（`<MAIN_REPO>` 共 **7** 处：`issue2-修复报告.md` 4 处、`红队确认-GLM-5.3.md` 3 处）。全文本另检出仓库名近似词 `ai-example` **1** 处（`红队确认-GLM-5.3.md:1` 标题「# 红队确认报告：ai-example issue #2 修复…」），属**项目代号**，既非盘符路径也不构成 `main`+分隔符+`v2` 组合。

正向对照（证明扫描有效、路径无误）：

| 对照 | 预期 | 实测 |
| --- | --- | --- |
| `MAIN_REPO` | 有命中 | **7** 命中 |
| `tasks/1/files` | 有命中 | **178** 命中 |
| 正则自检（内存样本，未落盘）`<REPO_ROOT>` / `repo-<OLD_REPO_DIR⟨_⟩>` / `repo <OLD_REPO_DIR⟨␠⟩>` / `repo.<OLD_REPO_DIR⟨.⟩>`，以及带尾缀的变体 | True | **全部 True** |
| 同上样本 `probe-v2after` / `MAIN-REPO` / `v2after` | False（不误伤） | **全部 False** |
| 盘符正则自检 `<DRIVE>/work/x.ts`、`<DRIVE>/repo/…` → True；`frontend/src/x.vue`、`http://localhost:5211/a` | — | True / True / False / True（后者即 a 组误捕来源，已定性） |

---

## 额外发现（超出指定抽查范围，供红队裁量）

1. **K′（正常速度 immediate）存在未申报的行为差异**。`probe-v2after-navigate-then-open-immediate.json` 的 `K2_open_task1_currency` 出现 `messages=["error: 表格引擎尚未就绪"]`（该子串在文件中出现 3 处、compact 段共 5 次），而 v2before 同一步 `messages=[]`；两步 `ok=true`、`result` 一致。报告 B 表 K′/L′ 行宣称「同（ok=true，行为不变）」，**该新增错误提示未被申报**，建议核明是修复引入的瞬时噪声还是真实回归。
2. **L′ 下载字节数不一致**：`navigate-then-download-immediate` 的 `downloads[0].bytes` 为 v2before **5505** vs v2after **6061**（同任务场景两版均为 6061）。文件名相同、事件相同，内容体积不同，需确认是桩数据/时序差异还是下载内容被前移状态影响。
3. **红队报告 §69 的交叉校验表述不成立**：`红队确认-GLM-5.3.md:69` 写「交叉校验：全部文本文件 `v2` 计数为 0」。实测字面量 `v2` 共 **125 次、分布 26 个文件**（`issue2-修复报告.md` 27 次，各 `probe-v2*` JSON 各 4 次）——该断言应为「禁则组合计数为 0」，字面表述需勘误。
4. 无阻塞：v2before 的 K3 仍假阴性、K4 自愈，与报告 K/L 行描述一致；M/N 的 before 静默与不停表均已复现，「修前有害、修后无痕」的对照方向可信。
