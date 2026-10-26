<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-329 · 施工复核 · T12-E#4 改判补丁：已落地并实测闭合（fc21d1da / agent-105）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scan-lib`、`scripts/ci`、`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m178` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt11` 条有正文的 assistant 消息（共 32 条有正文），末条有正文消息为 `m406`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `agent-105`（`main` = 主会话）；原字符 2,421（链内 2 段、合计 5,208）。
> **链号 / 类别**：`MT-329` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 13 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `12%`。
> **与末条归档（138 件）的关系**：末条 `m406`（2,465 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

### 〔段 1／2 · assistant 消息 m178 · 2,421 字符〕

## T12-E#4 改判补丁：已落地并实测闭合

### 补丁行数与位置

| 位置 | 改动（**行内追加**，行数不变） | 对齐的 FAIL 分支写法 |
|---|---|---|
| `scripts/verify-e2e.ps1:1543`（GF1 SKIP） | `Gf-Skip 'GF1' …; Gf-Dump 'GF1' $turn.frames` | `Gf-Dump 'GF1' $turn.frames` |
| `scripts/verify-e2e.ps1:1721`（GF3 SKIP） | `…; Gf-Dump 'GF3' $turn.frames` | `Gf-Dump 'GF3' $turn.frames` |
| `scripts/verify-e2e.ps1:1828`（GF4 SKIP） | `…; Gf-Dump 'GF4' $turn.frames` | `Gf-Dump 'GF4' $turn.frames` |
| `scripts/verify-e2e.ps1:1870`（GF5 SKIP） | `…; Gf-Dump 'GF5' $null` | `Gf-Dump 'GF5' $null` |

- **改动量 4 行**（上限 ≤6），`git diff -U0` 为 4 个**单行改写** hunk（`@@ -1540,7 / -1718,7 / -1825,7 / -1867,7`），**文件总行数不变（2188）**；脚本累计 **+8/−6**（4 行本次 + 2 行 T12-E#1 + 2 行 S5）。
- 未动 `gfskip` 计数、`gfskip14` 硬底线、`E2E_STUB_MODE` 分支、任何断言与既有用例块逻辑。

### 复验记录（全部【实测】）

| 项 | 结果 |
|---|---|
| **违规跑**（`--violate-form-type` + `E2E_STUB_MODE=1`） | `PASS=23 FAIL=0`、`GFSKIP=4`、**退出码 1（红）**，58s。SKIP：GF1/GF3/GF4/GF5 |
| **SKIP 用例转储清单** | **10 个文件全在**：`gfc-GF1.http.txt` 82 B / `gfc-GF1.sse.txt` 3464 B；`gfc-GF3.http.txt` 82 B / `gfc-GF3.sse.txt` 3464 B；`gfc-GF4.http.txt` 82 B / `gfc-GF4.sse.txt` 3464 B；`gfc-GF5.http.txt` 82 B / `gfc-GF5.sse.txt` 95 B（仅表头，与其 FAIL 分支 `$null` 口径一致）；另有 GF2（PASS）的 622/3266 B |
| **归因链闭合（内容佐证）** | `gfc-GF1.sse.txt` 含 8 条帧，其中 `frontend_tool_result` 为 `status=REJECTED_ARGUMENTS` + `result=FORM_SCHEMA_REJECTED：表单 schema 未通过白名单校验…` ⇒ 仅凭 artifact 即可判定"桩坏、产品闸门正确拒绝" |
| **干净跑**（补丁后 + `E2E_STUB_MODE=1`） | `PASS=27 FAIL=0`、`GFSKIP=0`、`Q8 PASS=2 FAIL=0`、**退出码 0**，43s |
| **正常路径零影响（强证据）** | 补丁后干净轮 `gfc-*` 十个文件**字节数与补丁前（`gfc-A`/`gfc-C`）逐项完全相同**（671/3969/622/3266/582/4059/1646/4309/1106/95）⇒ SKIP 分支新增调用在正常路径不可达、无副作用 |
| **PowerShell 语法解析** | `[Parser]::ParseFile` **0 个解析错误** |
| **脱敏复扫**（`scan-lib.sh` SECRET/PATH/USER） | `verify-e2e.ps1` **零命中**（5 个产出文件整体亦零命中）；YAML 解析 OK |
| 纪律 | 复验用 18330/18399（启动前 netstat 确认空闲），结束后本棒进程全停、端口已释放；仍为 **零 git 写操作、全部未提交** |

### 一处范围外的不对称（不自行扩权，供裁决）

裁决只列 GF1/GF3/GF4/GF5，故 **GF2 的 SKIP 分支（`scripts/verify-e2e.ps1:1627`）仍未补 `Gf-Dump`**：桩模式下 GF2 若 SKIP 同样会整棒 FAIL（`GFSKIP≥1`）却无 `gfc-*` 转储。如需对齐，属同一最小改法（+1 行），请裁决。

### 证据文档更新

`docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md`（421 行）：新增 **§3.6.4**（本次补丁 diff 摘要 + 8 项复验记录 + 范围外不对称说明），§3.6.3 标注已闭合；**§8 #4 处置改为"改判采纳——初判'随 #1 联动闭合'经实测证伪，改判为 4 处 SKIP 分支补 `Gf-Dump` 最小补丁，已落地并实测闭合"**，追加项标记为已闭合（附 GF2 遗留项）；**§9 末尾追加裁决原文"T12-E#4 改判（2026-10-08）：初判联动闭合经实测证伪，改判采纳 4 处 SKIP 分支补 Gf-Dump（≤6 行纯增量），实测闭合。"+ 执行回执**；§0/§1/附录同步。

---

### 〔段 2／2 · assistant 消息 m201 · 2,787 字符〕

## T12-E#7：GF2 SKIP 分支补齐 —— 已落地并实测闭合

### 补丁位置与行数

`scripts/verify-e2e.ps1:1627`（GF2 的 `GF-SKIP:*` 分支）**行内追加**，与同用例 FAIL 分支（`:1628`）的 `Gf-Dump 'GF2' $turn.frames` **逐字对齐**：

```powershell
- if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF2' $_.Exception.Message.Substring(8) }
+ if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF2' $_.Exception.Message.Substring(8); Gf-Dump 'GF2' $turn.frames }
```

- **1 行**（上限 ≤2），`git diff -U0` 为单行改写（hunk `@@ -1625,7`），**文件行数不变（2188）**；脚本累计 diff **+9/−6**。
- 五卡口径拉齐：`1543/1627/1721/1828` 用 `$turn.frames`、`1870` 用 `$null`（GF5 无模型轮次，与其 FAIL 分支一致）。未动 `gfskip` 计数、`gfskip14` 底线、`E2E_STUB_MODE` 分支、任何断言与用例块逻辑。

### GF2 SKIP 落盘验证方式与结果

**手段边界（如实说明）**：桩现有 `--violate-form-keys` / `--violate-form-type` **只作用于 `GF-FILTER`**（`applyViolation` 内 `if (route.id !== 'GF-FILTER') continue`），打不到 `GF-CLARIFY`。按裁决"不要为测试扩权改桩"，我**未改桩代码、未改仓库内路由表**；改用桩**已有的** `--routes <path>` 开关，喂一份**仓库外**临时路由表（`<RUN_DIR>/stub-routes-gf2-skip.json`，仅把 `GF-CLARIFY` 的 form 去掉 `exportScope`，其余逐字同仓库内路由表）。`git status` 中 `scripts/ci/` 仍只有原来两个文件。

| 项 | 结果 |
|---|---|
| GF2 SKIP 场景跑 1 次（`E2E_STUB_MODE=1`） | `PASS=26 FAIL=0`、`GFSKIP=1`、**退出码 1**，52s。`[SKIP] GF2 => 模型 3/3 次尝试 schema 漂移（最近：缺字段 exportScope（实际: includeInactive））` |
| **GF2 SKIP 落盘** | **`gfc-GF2.sse.txt` 1778 B 新落盘**（改前该场景零转储）；`gfc-GF2.http.txt` 82 B（漂移路径 `break` 前不发 POST，仅表头，与 FAIL 分支同形态） |
| 转储内容即归因证据 | 含 4 条帧（`start/tool_start/suspended/frontend_tool_request`），挂起帧 `name=generative_form`、`args.form.scenario=CLARIFY`、`fields=[('includeInactive','boolean')]` ⇒ 仅凭 artifact 即可看出"桩下发的 schema 缺 exportScope" |
| 静态对账（补充佐证） | 新增调用与 FAIL 分支的 `Gf-Dump 'GF2' $turn.frames` 逐字相同；GF1/GF3/GF4 同款写法已在 §3.6.4 实测有效 |

### 干净跑对比（含一处必须澄清的度量口径）

- **干净跑（补丁后，`E2E_STUB_MODE=1`）**：`PASS=27 FAIL=0`、`GFSKIP=0`、`Q8 PASS=2 FAIL=0`、**退出码 0**，43s。
- **字节数不等（如实报告）**：本轮 672/3970/623/3267/583/4060/1648/4310/1108/95 B 与上一轮（671/3969/…）**逐项差 1–2 字节**。逐行 diff 定位为**纯随机标识长度**（`sessionId=gfc-<guid>`、`storedBy=instance-<HOST>-pid<N>-<随机后缀>`），与行为无关；此前几轮"恰好相等"属巧合。**字节数不是稳定判据。**
- **改用可靠判据**：屏蔽 runId/toolCallId GUID、`gfc-<sid>`、`instance-*`、帧序号、时间戳与其余数字后逐文件比对——**补丁前（`gfc-A`）与补丁后（`gfc-D2`/`gfc-E2`）十份转储内容完全一致 ✓**；且 `gfc-D2` 轮与 `gfc-E2` 轮 **27 条用例结论行逐字一致 ✓**。§3.6.4 原先"字节数逐项相同"的表述已在文档中修正并注明正确口径。

### 自检与脱敏

- PowerShell 语法解析：**0 个解析错误**。
- `scan-lib.sh`（SECRET/PATH/USER）：`verify-e2e.ps1` **零命中**；5 个产出文件整体零命中；无盘符样式字面量；YAML 解析 OK（`e2e.env={'E2E_STUB_MODE':'1'}`）。
- 复验用 18330/18399（启动前 `netstat` 确认空闲），结束后本棒进程全停、端口已释放；**仍为零 git 写操作、全部未提交**。

### 证据文档更新

`docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md`（453 行）：新增 **§3.6.5**（补丁 + 手段边界 + 9 项复验）；§3.6.4 第 5 项度量口径修正；**§8 追加 T12-E#7 行**（"#7 采纳，已落地……五卡 SKIP 落盘口径已拉齐"）；**§9 末尾追加**"T12-E#7（2026-10-08）：GF2 SKIP 分支 Gf-Dump 补齐——采纳，同最小改法，五卡 SKIP 落盘口径拉齐。"+ 执行回执；§0/§1/附录同步。**无遗留不对称项，无新增【待裁决】。**
