<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-188 · 施工复核 · 核验完成，5/5 项全 PASS。（6d2ca6dd / agent-592）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`。
> **稿态**：历史中间稿 —— 该 wire 第 `m9` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 8 条有正文），末条有正文消息为 `m11`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `6d2ca6dd` / agent `agent-592`（`main` = 主会话）；原字符 2,241（链内 1 段、合计 2,241）。
> **链号 / 类别**：`MT-188` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m11`（63 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

核验完成，5/5 项全 PASS。

| 核验项 | 期望 | 实测 | PASS/FAIL |
|---|---|---|---|
| 1a HEAD | `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837` | 完全同值 | PASS |
| 1b 工作树 | `status --porcelain` 空 | 输出 0 行 | PASS |
| 1c log 头两笔 | `45b2c6a`(docs) + `93230ad`(feat frontend) | `45b2c6a docs(m2,evidence,adr): …8 份补档入库 + R-88/R-89 注记 + R-146 落行 + DC-22 登记` / `93230ad feat(frontend): 轮询治理一期三步（条件轮询+visibilitychange 纪律+SSE 事件触发刷新）+ 收口修 F1~F13` | PASS |
| 2 上游同步 | `@{u}`==HEAD，ahead 0 | `@{u}`=`45b2c6ab…`==HEAD；`rev-list --left-right --count @{u}...HEAD` = `0 0`；分支 `main` → `origin/main` | PASS |
| 3a CI conclusion | success | run `38196730825` / run_number **102** / head_sha `45b2c6ab…` / status `completed` / **conclusion `success`** / event `push` | PASS |
| 3b CI jobs | total_count=5，五 job 全 success | total_count=**5**：前端类型检查与构建｜后端编译与单测｜文档一致性门禁（check-docs）｜阻断｜全历史脱敏扫描｜E2E 全流程（29 用例）—— 五者均 `completed` + `success` | PASS |
| 4 报告第 18 期段 | 第 2994 行附近 | `grep -n "提交官报告（第 18 期"` 命中 **2994 行** = `# ai-example 合流项目 · 提交官报告（第 18 期：轮询治理一期 …全量入库 —— 两笔细分提交（feat + docs，27 文件）…` | PASS |
| 5a R-146 落行 | `docs/`（M2 台账）内有 R-146 | `docs/M2-排期计划.md:335`：行首 `| R-146（轮询治理一期 · 观察项包，第 18 期提交官落行…`；且该行由 `45b2c6a` 引入（该提交 diff 内 `R-146` 命中 5 次） | PASS |
| 5b DC-22 登记 | DECISION-REGISTER 有 DC-22 | `docs/adr/DECISION-REGISTER.md:26`：`| DC-22 | 提示词成本治理（PC-1~PC-6 全套）…`；`:45` 有 DC-22 追加注记；`45b2c6a` 对该文件新增 `+…DC-22` 行 2 处 | PASS |

旁证（非核验项）：两笔提交文件数 `93230ad`=16 / `45b2c6a`=11，合计 27，与 docs 提交信息「27 文件」自洽；`docs/README.md` 补档计数 24→61 的 drift 注记同批落盘。

脱敏：报告全文 0 真实组织名字面，远端一律以 `<REPO-URL>` 指代。

**自证**：agent id = `agent-592`；wire = `<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_6d2ca6dd\agents\agent-592\wire.jsonl`；实测 `llm.request.modelAlias` = **`DeepSeek/deepseek-flash`**（全文件 modelAlias 采样 6 次，**6/6 同值**）；该 wire 首条记录为 `{"type":"turn.prompt","agentId":"agent-592",…}` 且内容即本卡，佐证本棒身份。

**零写操作声明 / 需知会的一处偏差**：全程仅用 git 只读、curl 只读、Read/Grep，未 commit、未改仓库任何文件。唯一次「写」是首次 CI 取证时 `curl -o` 的落盘——因 Windows Python 不解析 msys `/tmp`，该文件未按预期被读到，我随即改为管道直读、之后不再落盘。残留两个临时文件（**在主仓库之外、不在任何 git 树内**）：`<USER_HOME>\AppData\Local\Temp\run18.json`、`<USER_HOME>\AppData\Local\Temp\run18jobs.json`。依「禁任何写操作」纪律我未删除它们，交由主 agent 决定是否清理。

未发现任何 FAIL、未发现任何口径不符。
