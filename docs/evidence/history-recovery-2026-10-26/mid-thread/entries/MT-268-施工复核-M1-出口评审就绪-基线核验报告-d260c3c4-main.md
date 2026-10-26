<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-268 · 施工复核 · M1 出口评审就绪 — 基线核验报告（d260c3c4 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`ai-example-code/ai-example-main`、`pre-commit`。
> **稿态**：历史中间稿 —— 该 wire 第 `m3` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 21 条有正文），末条有正文消息为 `m30`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `d260c3c4` / agent `main`（`main` = 主会话）；原字符 3,955（链内 1 段、合计 3,955）。
> **链号 / 类别**：`MT-268` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `1%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m30`（8,585 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 10 处。

---

# M1 出口评审就绪 — 基线核验报告

## 一、基线确认【实测】

- **git**：HEAD=`61286e2`（<OLD_REPO_DIR>），工作区干净，与 `<OLD_REPO_DIR>` 完全同步，与交接基线一致。
- **CI**：最新 run #29 即 `61286e2` 本次提交，`conclusion=success`；近 5 次 run（#25–#29）连续 success。

## 二、决策登记表"待确认"扫描【实测，子智能体执行】

- `DECISION-REGISTER.md`：7 个状态词 **0 命中**；DC-01~15 状态列全部「生效」（DC-09 部分条目有明确推翻关系指向 DC-10），签字列 15/15 齐备。**清零成立**。
- `docs/adr/` 其余文件 14 处"待裁决"命中，经上下文逐条核对**全部为已闭环的历史留痕**（S3 Challenger 审查/重审存档），真待办 **0 项**。

两个**备查观察项**（不阻塞出口，但评审时可能被问到，已写入 GLM 复核卡请其给独立意见）：

1. 登记表「复核」列 DC-01~13、DC-15 为「—」——形式性缺口，状态与签字均齐备；
2. 技术方案 §13.2 仍有 11 项**排期性**待裁决（S4.x/M2 激活包锚点），不属登记表范畴。

## 三、M1 出口标准对照

| 出口标准 | 现状 | 证据 |
|---|---|---|
| 测试全绿（后端 246 / E2E 27 / Q8 2/2 / GFSKIP=0） | 已在库（五轮复跑后三轮连续全绿） | GFc-端到端执行验证 |
| 四层行为对照一致率 ≥95% | 已在库 | baseline-combined / V2 / V5 重放证据 |
| 决策登记表"待确认"清零 | **本轮实测清零** | 上方扫描结论 |
| CI 绿 | 近 5 run 连续 success | GitHub Actions #25–#29 |

**信号：M1 出口评审就绪。** 出口评审由业务方主持、**人签字**——这是等您安排的签字节点，我不代签。

## 四、可直接转发的任务卡：GLM-5.3 异厂商复核（出口前最后一道独立验证）

上一会话的复核卡您已持有；以下为按当前 HEAD 与出口标准重新起草的版本（自包含，新对话直接可用）：

```
你是 GLM-5.3，担任 ai-example 项目的独立复核员（Verifier）。你的职责是不信任任何
既有结论，独立复现 M1 出口标准的每一项并输出复核报告。作者链条为 Kimi/DeepSeek 系，
你是异厂商复核，必须独立验证。

【项目背景】
ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库：
<REPO>，分支 <OLD_REPO_DIR>，预期 HEAD=61286e2
（与 origin 同步、工作区干净）。M1 = 单实例里程碑，即将举行出口评审。

【环境】
- Windows + Git Bash；JDK 21 在 PATH
- Maven 全路径："<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
- node v22 / yarn 1.22
- Memurai（Redis 兼容）127.0.0.1:6379（E2E 前置，确认在跑）
- AI 服务（OpenAI 兼容）：base-url https://api.deepseek.com，model deepseek-flash，
  API key 由用户在执行前以环境变量注入（AI_API_KEY，兼容 DEEPSEEK_API_KEY）。
  禁止把 key 写入任何文件；文档中一律用 ***。
- 端口纪律：18080/18290-18299/18301-18321 为历史占用段，新任务从 18330 起分配；
  启动前必 netstat 确认；只能停止你自己启动的进程。

【M1 出口标准——逐项复核，逐项给 通过/不通过 + 证据】
1. git 基线：HEAD=61286e2、工作区干净、与 <OLD_REPO_DIR> 同步。
   git -C <LOCAL_WORK>/<REPO> log --oneline -3
   git -C <LOCAL_WORK>/<REPO> status --short --branch
2. 后端单测全绿：backend/ 目录用上述 mvn.cmd 跑 test。测试计数口径：正则
   ^\s*@Test(\s*$|\() 精确匹配（@TestPropertySource 等会虚增，禁止模糊计数）。
   预期 246 个 @Test 全绿；以你实测数为准，若与 246 不符，如实报告并分析差异。
3. 前端质量门：frontend/ 目录 typecheck 与 build 全绿（以 frontend/package.json
   实际脚本名为准）。
4. E2E 全绿：scripts/verify-e2e.ps1，预期 27 用例全绿（22 既有 + GF1–GF5
   生成式表单），Q8 项 2/2，GFSKIP=0。运行前置（后端实例、Redis、环境变量、
   端口）先读 scripts/verify-e2e.ps1 本体与 docs/evidence/ 下"GFc-端到端执行
   验证"证据文档中记录的标准跑法，严格复刻。真实 AI 调用耗时长属正常，用后台
   任务方式跑，不要中途放弃。若环境阻塞无法复跑，改为静态复核该证据文档，
   结论降级标注【推断】并说明阻塞原因。
5. 四层行为对照一致率 ≥95%：定位 docs/evidence/ 下 baseline-combined 及 V2/V5
   重放相关证据文档，独立重算一致率（对照项总数、一致项数、百分比），并抽样
   3 个对照项做原文核对（不只看结论数字），判断 ≥95% 断言是否成立。
6. 决策登记表"待确认"清零：扫描 docs/adr/DECISION-REGISTER.md 与
   docs/adr/DECISION-CARDS.md 中的 待确认/待裁决/待定/未确认/TBD/待签字/
   待评审，区分真待办与历史留痕。另对两个备查项给出你是否认同"不阻塞出口"
   的独立意见：①登记表复核列 DC-01~13、DC-15 为"—"；②docs/03-技术方案文档
   -v2.1.md §13.2 仍有 11 项排期性待裁决（不属登记表）。
7. CI 状态：匿名轮询
   https://api.github.com/repos/<OWNER>/ai-example/actions/runs?branch=<OLD_REPO_DIR>
   确认最近 run（应对应 61286e2）conclusion=success，且近 5 次连续 success。

【输出要求】
- 复核报告写入
  <REPO>\docs\evidence\M1-出口复核-GLM-5.3.md
  保持未提交，零 git 写操作（禁止 add/commit/push）。
- 报告结构：逐项复核表（标准项｜预期｜实测｜证据｜通过/不通过）→ 备查项独立
  意见 → 不符合项清单 → 总结论（M1 出口复核：通过/不通过 + 一句话理由）。
- 每个关键断言标注【实测】/【推断】/【假设】；证据给命令与关键输出摘录。
- 脱敏纪律：API key 一律 ***；本机绝对路径用占位符 <<OLD_REPO_DIR>>、<MAVEN_HOME>、
  <MEMURAI_HOME>；禁止真实用户名/内网 IP。注意：连"本文档无盘符路径泄漏"
  这类自指句里也不得字面写出盘符路径样式（会被 pre-commit 钩子拦截）。
- 发现任何与预期不符，不要自行修复，如实记录并给出复核判断。
```

**执行提示**：跑之前请先 `export AI_API_KEY="sk-..."`（您持有 key）；E2E 为真实 AI 调用，建议让 GLM 侧把长跑任务移后台。报告回来后在您新会话里 @我，我做结论级验收 + 派提交官脱敏入库。

## 五、非阻塞可选项（随时可做，不卡出口）

- **双 FormRenderer 手动冒烟**（GF-C 收尾观察项）：按 `docs/evidence/` 下 GFb 复核 §6 的帧路径，在浏览器 console 两次投递同一 `toolCallId` 的 `frontend_tool_request` 帧，肉眼核对是否双挂起卡。已裁定为纯视觉重复（共享同一 activeForm、后端 409 幂等封顶），结果只决定是否在 M2 修。

---

**本轮标注汇总**：① git/CI/扫描结论 = 已完成验收【实测】；② GLM-5.3 复核卡 = 可直接转发的任务卡；③ M1 出口评审开会与签字 = 给您的决策请求（人签字节点，等您安排）。
