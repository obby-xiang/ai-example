<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-287 · 施工复核 · 结果：直发成功，字节级核验通过（BYTE-EXACT-EQUAL）（e4829173 / agent-885）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-code/ai-example-main`。
> **稿态**：历史中间稿 —— 该 wire 第 `m45` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt25` 条有正文的 assistant 消息（共 89 条有正文），末条有正文消息为 `m239`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e4829173` / agent `agent-885`（`main` = 主会话）；原字符 3,096（链内 6 段、合计 14,716）。
> **链号 / 类别**：`MT-287` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `19%`。
> **与末条归档（138 件）的关系**：末条 `m239`（3,029 字符）未入末条归档。
> **脱敏**：路径 23 / 会话标识 7 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

### 〔段 1／6 · assistant 消息 m45 · 3,096 字符〕

## 结果：直发成功，字节级核验通过（BYTE-EXACT-EQUAL）

### 1. DC-19 首轮自证
- 自身 wire：`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_e4829173/agents/agent-885/wire.jsonl` 第 5 行单行：
  `{"type":"profile.bind","agentId":"agent-885","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
- 佐证：该会话 `agents/main/wire.jsonl` 末条 `subagent.started subagentId=agent-885`。

### 2. payload 留证
- 文件 `<LOCAL_WORK>/_cu-r163-card/payload.txt`：**4175 字节 / 2403 字符 / sha256 `482b59087bc92baf5c8fdfa4632cf7eeabc0ab0ce61833efe0587d404a607ba9`**
- UTF-8 无 BOM、CR=0、22 个 LF、末尾单个 LF；ESM 模块往返读回逐字节相等（`gen-payload-r163.json`）。
- 剪贴板 setclip 复核：`SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true。

### 3. display_text 比对结论
`turn.prompt.origin.clientMetadata[0].display_text`（wire 第 2433 行，`turnId=3`，`promptId=msg_01M4WY89V17GVA6WNDZWSN0MTY`，time `2026-10-14T10:10:16.077Z`）与 payload.txt：**逐字节全等，字节数 4175=4175，双方 sha256 同值，差异定位 `firstDiffByte=-1`**。`turn.prompt` 计数 3→4，仅新增 1 条（无重发）。

### 4. 目标会话确认（截图要点）
| 判据 | 实测 |
|---|---|
| 侧栏行 | `[244] 组 class="se"` + 子文本 `R-161 AI 面板滚动行为专项`（= state.json title），点击后变 `class="se on"` |
| URL | `app://renderer/sessions/session_0a528d85` |
| 模型 pill | `按钮 name="K2.8 Preview · High" class="model-pill"` |
| 分组 | 选择器内 `文本 name="managed:kimi-code"` 下方 `菜单项单选按钮 name="K2.8 Preview" … selected=true`（仅开菜单观察后 Escape 关闭，未点任何行） |
| 归档 | `state.json archived=false`，无需恢复路径 |
| 收卡实证 | 新 `llm.request turnStep=3.1`：`model=kimi-for-coding / modelAlias=kimi-code/kimi-for-coding / provider=openai` → K2.8 Preview 实际承接；界面已出现「思考过程 · 4s」+ `grep -o '"profile.bind"...'` 工具调用，即它在执行卡内 DC-19 自证 |

### 5. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-card/dispatch-evidence-r163.json`（含双方 sha256/字节数、发送时刻、observation 摘要、守卫与事故记录）
- 字节核验：`bytecheck-r163.json` + `bytecheck-r163.mjs`；剪贴板回读：`clipboard-copyback-r163.txt`
- 截图：`01-target-session-selected-k28.png`、`02-picker-managed-k28-selected.png`、`03-presend-payload-in-composer.png`、`04-postsent-bubble-requesting.png`、`05-postsent-observed.png`、`06-k28-received-dc19-selfproof-running.png`

### 6. 事故与口径限制（均无副作用）
- 前 3 次原子 cell 均被自己写的 guard 拦下，**未按发送**：①会话行判据误按行内文本匹配（标题在子节点）；②composer 的 UIA rect 偶发错报 `608,-91 542x892`，点击落到消息区（已加 rect 合法性校验＋composer-card 回退＋点击后 focus 断言）；③UIA `value` 被截断为 120 字符且换行压成空格、贪心正则吞掉后续 `class` 属性（已修正解析）。
- 另有 3 次 cell 在声明期因 kernel 变量名冲突/陈旧 `PAY` 绑定失败（已改 IIFE + cell 内 `await import`）。
- 发送用显式点击 `按钮 name="发送 ↵"` `(1168,811)`，非 Enter（沿用 b4fix 先例，规避全选态下 Enter 替换选区）。
- **限制须报**：UIA 无法暴露 composer 全文（截断 120 字符），故 cell 内守卫只能是「前 120 字符与压平后载荷全等 + placeholder 消失 + composer 撑高 271px」；发送前 Ctrl+A/Ctrl+C 回读剪贴板得到与 payload 同 sha 的快照（2403 字符 / 482b5908… / CR=0 / 末尾 LF），属**诊断性**证据（理论上 Ctrl+C 静默失败会残留同 sha 载荷），权威判据是发送后的 wire `display_text` 字节比对。

### 7. 边界
未对主仓库 `<REPO>` 做任何操作；未进入或改动其他会话（21棒会话仅被观察）；所有新文件均在 `<LOCAL_WORK>/_cu-r163-card/` 内（payload.txt、payload-r163.txt、gen-payload-r163.mjs/json、`node_modules/r163card-payload/`、setclip-r163.ps1/out、bytecheck-r163.mjs/json、clipboard-copyback-r163.txt、evidence-r163.mjs、dispatch-evidence-r163.json、6 张 png）。

---

### 〔段 2／6 · assistant 消息 m61 · 2,134 字符〕

## 结果：复核卡直发成功，字节级核验通过（BYTE-EXACT-EQUAL）

### 1. payload 留证
- `<LOCAL_WORK>/_cu-r163-review/payload.txt`：**3024 字节 / 1829 字符 / sha256 `13844056b79704beefb48799fc216779f1ec98fcde49987d1a945bd4fce9e7de`**
- UTF-8 无 BOM、CR=0、20 个 LF、末尾单个 LF；键字符已单项验证（`T0–T6` 用 U+2013、`78a5d97e…58ef5f` 用 U+2026）。
- ESM 模块往返读回逐字节相等；剪贴板 setclip 后 `SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true。

### 2. display_text 比对结论
`turn.prompt.origin.clientMetadata[0].display_text`（wire 第 394 行，`turnId=2`，`promptId=msg_01M4X3DX5C461F3YVCH7X1T1P4`，`2026-10-14T11:40:42.583Z`）与 payload.txt：**逐字节全等（3024=3024 B，sha256 同值，`firstDiffByte=-1`）**。`turn.prompt` 计数 2→3，仅新增 1 条。

### 3. 目标会话确认要点
| 判据 | 实测 |
|---|---|
| 侧栏行 | 精确标题 `施工报告复核与格式化`（与另存的 `施工报告复核` 行区分，未误点），点击后 `class="se on"` |
| URL | `app://renderer/sessions/session_542af0cf` |
| 模型 pill | `按钮 name="DeepSeek V4 Pro · High" class="model-pill"` |
| 分组 | 选择器分组标题 `DeepSeek` 下 `DeepSeek V4 Pro` `selected=true`（`is-active`）；`DeepSeek V4.1 Flash` selected=false；仅开菜单观察后 Escape 关闭，未点任何行 |
| 归档 | `state.json archived=false` |
| 收卡实证 | 新 turn 之后 `llm.request 2.1→2.4` 全部 `modelAlias=DeepSeek/deepseek-v4-pro / provider=openai`；界面已出现「运行 1 条命令 / 读取了 2 个文件 / 工作中…」，DSPro 已在读材料复核 |

### 4. 发送口径
- 单 cell 原子：新鲜 observation 复核身份（URL/pill/选中行/无菜单/composer）→ 聚焦 composer（候选链 + 直播 image_height 校验 rect 合法性 + focus 断言）→ `Ctrl+A/Delete/Ctrl+V` → 守卫通过 → `Ctrl+A/Ctrl+C` 回读快照 → **显式点击「发送 ↵」(1191,936)**。
- 守卫值：UIA value 前 120 字符与压平载荷全等、placeholder 消失、composer 撑高 110→318px。
- 剪贴板发送前快照：`1829 字符 / sha 13844056… / CR=0 / 末尾 LF`（诊断性，权威判据仍是 wire 字节比对）。
- 本轮无失败尝试、无重发；坐标系切换会话后由 1568x634 漂移到 1442x976，守卫用直播尺寸校验、未用硬编码点。

### 5. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-review/dispatch-evidence-r163-review.json`
- 字节核验：`bytecheck-r163-review.json` / `.mjs`；剪贴板：`clipboard-copyback-r163-review.txt`、`setclip-r163-review.out`
- 截图：`01-target-session-selected-dspro.png`、`02-picker-deepseek-v4pro-selected.png`、`03-presend-review-in-composer.png`、`04-postsent-review-bubble.png`、`05-dspro-reviewing-after-receipt.png`

### 6. 边界
未触碰主仓库（无 git 操作、无仓库写入）；未进入/修改其他会话（K2.8 会话仅在上一轮被派发后保持自主运行，本轮未干预）；模型未被切换；全部新文件均在 `<LOCAL_WORK>/_cu-r163-review/` 下。

---

### 〔段 3／6 · assistant 消息 m74 · 2,214 字符〕

## 结果：收口修卡直发成功，字节级核验通过（BYTE-EXACT-EQUAL）

### 1. payload 留证
- `<LOCAL_WORK>/_cu-r163-fix/payload.txt`：**1777 字节 / 1020 字符 / sha256 `c767e15575444eda59bd042001b7144b22c229e235bfeda1416e21819f91e849`**
- UTF-8 无 BOM、CR=0、10 个 LF、末尾单个 LF；键字符单项验证（`｜` U+FF5C ×1、`✓` U+2713 ×1）。
- ESM 模块往返逐字节相等；setclip 后 `SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true。

### 2. display_text 比对结论
wire 第 4678 行 `turn.prompt`（`turnId=4`，`promptId=msg_01M4X3ZGY1027FHYASX03S68DV`，`2026-10-14T11:50:19.883Z`）`origin.clientMetadata[0].display_text` 与 payload.txt：**逐字节全等（1777=1777 B，sha256 同值，`firstDiffByte=-1`）**。`turn.prompt` 计数 4→5，仅新增 1 条。

### 3. 目标会话确认要点
| 判据 | 实测 |
|---|---|
| 侧栏行 | 精确标题 `R-161 AI 面板滚动行为专项`（与失败行 `# 施工卡 R-161（AI 面板滚动行为专项 …` 区分），点击后 `class="se on"` |
| URL | `app://renderer/sessions/session_0a528d85` |
| 模型 pill | `按钮 name="K2.8 Preview · High" class="model-pill"` |
| 分组 | wire `profile.bind modelAlias=kimi-code/kimi-for-coding`（= managed:kimi-code 的 K2.8 Preview 别名）；发后 `llm.request 4.1` 同别名实测承接（msgCount=953）。**本轮未重开模型选择器**（K2.8 分组映射已在前轮截图留证） |
| 空闲/直发 | 发送前 wire 已 `agent.turn.ended turnId=3`、`lastTurnReason=completed`；composer 未出现「加入队列」占位符 → 非排队直发 |
| 归档 | `state.json archived=false` |
| 收卡实证 | 界面出现收口修卡气泡（19:50）→「收口修接令。先做复核报告对 F 项上下文，并实证 F1 路径：」→ 已运行 2 条命令、思考中、工作中… |

### 4. 发送口径
- 单 cell 原子：切换会话（精确标题定位侧栏行）→ 新鲜 observation 复核身份（URL/pill/选中行/无菜单/composer）→ 聚焦 composer（候选链 + 直播尺寸校验 rect + focus 断言）→ `Ctrl+A/Delete/Ctrl+V` → 守卫通过 → `Ctrl+A/Ctrl+C` 快照 → **显式点击「发送 ↵」(999,617)**。
- 守卫值：UIA value 前 120 字符与压平载荷全等、placeholder 消失、composer 撑高 50→192px。
- 剪贴板发送前快照：`1020 字符 / sha c767e155… / CR=0 / 末尾 LF`（诊断性；权威判据仍是 wire 字节比对）。
- 本轮守卫一次通过，无失败尝试、无重发；坐标系为 VDD 缩放空间（1568x634 / window_rect 3440x1392），全部 rect 用直播尺寸校验，无硬编码点。

### 5. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-fix/dispatch-evidence-r163-fix.json`
- 字节核验：`bytecheck-r163-fix.json` / `.mjs`；剪贴板：`clipboard-copyback-r163-fix.txt`、`setclip-r163-fix.out`
- 截图：`01-target-session-selected-k28.png`、`02-presend-fix-in-composer.png`、`03-postsent-fix-bubble.png`、`04-k28-executing-fix.png`

### 6. 边界
未触碰主仓库（无 git 操作、无仓库写入）；未进入/修改其他会话（DSPro 会话 542af0cf 的复核轮保持自主运行，未被干预）；未切换模型；全部新文件均在 `<LOCAL_WORK>/_cu-r163-fix/` 下。三轮（施工卡→复核卡→收口修卡）均已完成派发，其中前两轮已分别由 K2.8、DSPro 实际承接。

---

### 〔段 4／6 · assistant 消息 m87 · 2,424 字符〕

## 结果：新会话已建、模型正确、直发成功、字节级核验通过

### 1. 新会话 uuid
**`session_57c9e467`**
- 创建路径：侧栏 `[34] 按钮 name="新建会话 Ctrl N"` rect=4,22 115x15 → 点 (62,30)；创建后 URL=`app://renderer/`、`empty-logo` 在场、workspace chip = `temp`（<LOCAL_WORK>）；`state.json cwd=<LOCAL_WORK>`、`createdAt=2026-10-14T12:04:37.574Z`、`archived=false`。
- 独立发现核对：按 mtime 扫描 `wd_temp_38dcbf7ced2f/session_*`，最新项 = 该 uuid（排除 e4829173/0a528d85/542af0cf 后仍为最新），与界面 URL 观察值一致（`uiMatchesDiscovery=true`）。

### 2. 模型实证（重点，未选错）
| 环节 | 实测 |
|---|---|
| 新会话继承模型 | `K3 · High`（非目标，已改） |
| 选择器候选行 | `DeepSeek V4.1 Flash DeepSeek` rect=868,**178** 12px / `DeepSeek V4 Pro DeepSeek` rect=868,**190** 12px（相邻仅 12px） |
| 点击点守卫 | 点 (928,196)，断言 `insideTargetRow=true`、`insideV41FlashRow=false`，否则拒绝点击 |
| 选后 pill | `按钮 name="DeepSeek V4 Pro · High" class="model-pill"` |
| 重开复核 | 选择器内 `DeepSeek V4 Pro` `is-active selected=true`；`DeepSeek V4.1 Flash` `selected=false` |
| wire 自证 | `profile.bind`（第 5 行）`modelAlias=DeepSeek/deepseek-v4-pro`；发后 `llm.request 0.1→0.6` 全部 `model=deepseek-v4-pro / provider=openai`（msgCount 4→21，实际承接） |

### 3. payload 与比对结论
- `<LOCAL_WORK>/_cu-r163-confirm/payload.txt`：**1412 字节 / 966 字符 / sha256 `7ec0892943fa5787a577612105567257c9a2e382bb5203cac639860f5ae2aad3`**，无 BOM、CR=0、8 LF、末尾单 LF，①②③ 各 1、✓ 1。
- setclip 复核 `SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true；发送前 Ctrl+C 快照回读同 sha。
- wire 第 8 行 `turn.prompt`（`turnId=0`，`promptId=msg_01M4X4SPT2KHSB248ZR29H1DRE`，`2026-10-14T12:04:37.861Z`）`display_text` vs payload.txt：**逐字节全等（1412=1412 B，sha256 同值，`firstDiffByte=-1`）**；新会话 `turn.prompt` 计数 = 1（无重发）。
- 发送：显式点击 `按钮 name="发送 ↵"` (999,420)，sendClickedAt=12:04:37.409Z；守卫（前 120 字符压平等 + placeholder 消失 + composer 撑高 63→193px）通过；composer 已清空，时间线出现气泡并进入「思考中/工作中…」，现已 `Skill(verification-before-completion)` 起手做三点核对。

### 4. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-confirm/dispatch-evidence-r163-confirm.json`（含新会话 uuid 全值、双方 sha、observation 摘要、选择器行几何与守卫）
- 核验：`bytecheck-r163-confirm.json` / `.mjs`；剪贴板：`clipboard-copyback-r163-confirm.txt`、`setclip-r163-confirm.out`
- 截图：`01-new-empty-session-created.png`、`02-picker-deepseek-v4pro-before-click.png`、`03-picker-deepseek-v4pro-selected-true.png`、`04-pill-deepseek-v4-pro.png`、`05-presend-confirm-card-in-composer.png`、`06-postsent-confirm-bubble.png`、`07-new-session-verifying-three-points.png`

### 5. 边界
未触碰主仓库；旧复核会话 542af0cf **未发送任何内容**；K2.8（0a528d85，收口修执行中）仅被观察、未受干扰；未切换既有会话的模型；全部新文件在 `<LOCAL_WORK>/_cu-r163-confirm/` 下。

---

### 〔段 5／6 · assistant 消息 m158 · 2,331 字符〕

## 结果：补修卡 R-163B 直发成功，字节级核验通过（BYTE-EXACT-EQUAL）

### 1. payload 留证
- `<LOCAL_WORK>/_cu-r163-fix2/payload.txt`：**2855 字节 / 1764 字符 / sha256 `e61241a16b539f8f0db369172a66b7c38a90aa8213415bb372ff58c528d2c45c`**
- UTF-8 无 BOM、CR=0、19 个 LF、末尾单 LF；特殊字符单项核对：`⇒`×1、`↔`×2、`≈`×1、`→`×1、`①`×1、`—`×6。
- ESM 模块往返逐字节相等；setclip 回读 `SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true。

### 2. display_text 比对结论
wire 第 4983 行 `turn.prompt`（`turnId=5`，`promptId=msg_01M4X9TGT8NWG13HYWMPH96QXM`，`2026-10-14T13:32:27.343Z`）`origin.clientMetadata[0].display_text` 与 payload.txt：**逐字节全等（2855=2855 B，sha256 同值，`firstDiffByte=-1`）**。`turn.prompt` 计数 5→6，仅新增 1 条（无重发）。

### 3. 目标会话确认要点
| 判据 | 实测 |
|---|---|
| 侧栏行 | 精确标题 `R-161 AI 面板滚动行为专项`（与失败行 `# 施工卡 R-161（…` 区分），点击后 `class="se on"` |
| URL | `app://renderer/sessions/session_0a528d85` |
| 模型 pill | `按钮 name="K2.8 Preview · High" class="model-pill"` |
| 分组 | wire `profile.bind modelAlias=kimi-code/kimi-for-coding`（= managed:kimi-code 的 K2.8 Preview）+ 发后 `llm.request 5.1` 同别名实测承接（msgCount=1012）；本轮未动模型选择器 |
| 发送前状态 | wire 已 `agent.turn.ended turnId=4`、`lastTurnReason=completed`、promptCount=5 → 空闲；composer 无「加入队列」→ 非排队直发 |
| 归档 | `archived=false` |
| 收卡实证 | 界面出现补修卡气泡（21:32）→「请求中…」；最终观察会话处于工作态、卡片已在时间线 |

### 4. 发送口径
- 单 cell 原子：切会话（精确标题定位侧栏行）→ 新鲜 observation 复核身份 → 聚焦 composer（候选链 + 直播尺寸校验 rect + focus 断言）→ `Ctrl+A/Delete/Ctrl+V` → 守卫（前 120 字符压平全等 + placeholder 消失 + composer 撑高 50→192px）→ `Ctrl+A/Ctrl+C` 快照 → **显式点「发送 ↵」(999,617)**。
- 剪贴板发送前快照：`1764 字符 / sha e61241a1… / CR=0 / 末尾 LF`（诊断性；权威判据为 wire 字节比对）。

### 5. 事故（两次被自己的守卫拦下，均未粘贴未发送，无副作用）
1. 第 1 次：宿主窗口当前活动会话是**本 agent 自身会话 e4829173**，`urlIsTarget=false` 拦截 → 截图 `00-gate-abort-wrong-active-session.png`；随后改为单 cell 内「切会话 + 复核 + 粘贴 + 发送」。
2. 第 2 次：已切到目标会话（URL/pill/选中行全绿），但 `noMenuOpen` 判据过宽——树内有 4 个**隐藏**菜单节点（画面并无打开菜单）→ 命中误拦；改用「菜单矩形是否与 composer 重叠」判据后通过（`menuOverlapsComposer=false`）。

### 6. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-fix2/dispatch-evidence-r163-fix2.json`
- 核验：`bytecheck-r163-fix2.json` / `.mjs`；剪贴板：`clipboard-copyback-r163-fix2.txt`、`setclip-r163-fix2.out`
- 截图：`00-gate-abort-wrong-active-session.png`、`01-target-session-before-paste.png`、`02-presend-fix2-in-composer.png`、`03-postsent-fix2-bubble.png`

### 7. 边界
未触碰主仓库（无 git 命令、无仓库写入）；未进入/修改其他会话（e4829173、542af0cf、57c9e467 仅被观察）；未切换模型；所有新文件均在 `<LOCAL_WORK>/_cu-r163-fix2/` 下。

---

### 〔段 6／6 · assistant 消息 m218 · 2,517 字符〕

## 结果：新会话已建、模型正确、直发成功、字节级核验通过

### 1. 新会话 uuid
**`session_91f256e4`**
- 创建：侧栏 `新建会话 Ctrl N` → 点 (62,30)；创建后 URL=`app://renderer/`、`empty-logo` 在场、workspace `temp`（<LOCAL_WORK>）；`state.json cwd=<LOCAL_WORK>`、`createdAt=2026-10-14T14:10:06.308Z`、`archived=false`
- 独立发现核对：mtime 扫描 `wd_temp_38dcbf7ced2f/session_*`，最新项即该 uuid（排除 e4829173/0a528d85/542af0cf/57c9e467 后仍最新），与界面 URL 观察一致（`uiMatchesDiscovery=true`）

### 2. 模型实证（重点：未选错同名 API Key 版）
| 环节 | 实测 |
|---|---|
| 新会话继承模型 | `DeepSeek V4 Pro · High`（非目标，已改） |
| 选择器候选行 | `K2.8 Preview managed:kimi-code` rect=838,**151**（目标）／`K2.7 Code Highspeed managed:kimi-code` y=**163**（相邻）／`kimi-for-coding Kimi` y=**199**（同名 API Key 版）／`DeepSeek V4 Pro`（继承态） |
| 点击守卫 | 点 (912,157)，断言 `insideApiKeyRow=false`、`insideK27Row=false`，否则拒绝点击 |
| 选后 pill | `按钮 name="K2.8 Preview · High" class="model-pill"` |
| 重开复核 | 选中行 = `菜单项单选按钮 name="K2.8 Preview" … is-active selected=true` |
| wire 自证 | `profile.bind`（第 5 行）`modelAlias=`**`kimi-code/kimi-for-coding`** ✅（= managed:kimi-code 分组 K2.8 Preview）＋ 发后 `llm.request 0.1→0.3` 全部同别名（provider openai、msgCount 5→10） |

### 3. payload 与比对结论
- `<LOCAL_WORK>/_cu-r163-c/payload.txt`：**1611 字节 / 960 字符 / sha256 `a711469b8dc844ca443fe846e26277f4d14def10a9e8d3cb1ea54387c449fa49`**；UTF-8 无 BOM、CR=0、8 LF、末尾单 LF
- setclip 回读 `SRC_SHA == CLIP_SHA`、CR=0、末尾 LF=true；发送前 Ctrl+C 快照回读同 sha（960 字符）
- wire 第 10 行 `turn.prompt`（`turnId=0`、`promptId=msg_01M4XBZF2SBQMY6FT0Q53VKWVY`、`2026-10-14T14:10:06.602Z`）`display_text` vs payload.txt：**逐字节全等（1611=1611 B，sha256 同值，`firstDiffByte=-1`）**；新会话 `turn.prompt` 计数 = **1**（无重发）
- 发送方式：单 cell 原子（新鲜 observation 复核空新会话 → 聚焦 composer → Ctrl+A/Delete/Ctrl+V → 守卫：前 120 字符压平全等 + placeholder 消失 + composer 撑高 63→193px → Ctrl+A/Ctrl+C 快照 → **显式点击「发送 ↵」(999,420)**，非 Enter）

### 4. 收卡实证
界面出现闭合卡气泡（22:10）→「我是 R-163C 闭合卡执行者，先读前置材料和目标仓当前现状。」→ 读取 2 个文件 / 运行 1 条命令 /「工作中…」；pill 仍为 `K2.8 Preview · High`。

### 5. 证据路径
- 主证据：`<LOCAL_WORK>/_cu-r163-c/dispatch-evidence-r163c.json`（含新会话 uuid 全值、双方 sha256/字节数、mtime 发现结果、点击守卫几何、发送时刻）
- 核验：`bytecheck-r163c.json` / `.mjs`；剪贴板：`clipboard-copyback-r163c.txt`、`setclip-r163c.out`
- 截图：`00-new-session-inherited-deepseek.png`、`01-picker-rows-before-select.png`、`02-picker-k28-selected-true.png`、`03-pill-k28-preview.png`、`04-presend-card-in-composer.png`、`05-postsent-card-bubble.png`

### 6. 本轮事故
**零**：守卫链一次通过（新建会话→选模型→原子发送），无需重试。

### 7. 边界
旧施工会话 **0a528d85 未发送任何内容**（仅观察：promptCount=7、turn 6 已 ended）；未改主仓库任何文件（无 git 操作）、未动模型以外的既有会话；全部新文件均在 `<LOCAL_WORK>/_cu-r163-c/` 下。
