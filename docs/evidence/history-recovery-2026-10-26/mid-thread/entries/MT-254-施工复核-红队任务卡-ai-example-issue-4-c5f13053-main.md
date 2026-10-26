<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-254 · 施工复核 · 红队任务卡：ai-example issue#4/#5 修复攻击面评审（GLM-5.3）（c5f13053 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`workspace.ts`、`ResilientChatService`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m19` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m30`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `c5f13053` / agent `main`（`main` = 主会话）；原字符 4,216（链内 1 段、合计 4,216）。
> **链号 / 类别**：`MT-254` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 6 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m30`（1,686 字符）未入末条归档。
> **脱敏**：路径 12 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

双复核完成，进入红队环节。以下是 **GLM-5.3 红队任务卡**，请转交 GLM（报告落盘后您回我"已完成"即可，不用贴全文）：

```markdown
# 红队任务卡：ai-example issue#4/#5 修复攻击面评审（GLM-5.3）

你是红队评审官。任务：对 ai-example 项目 issue#4/#5 的已完成修复（未提交）做攻击面评审，找出施工/复核可能遗漏的缺陷、误判与风险。你的结论决定这批修复能否提交。

## 子智能体分工条款
你可以派自己的子智能体（deepseek-v4-flash）执行机械活：全量扫描、批量 grep、大文件通读、长测试实跑、日志翻找、探针复跑。你本体只做关键判断、攻击面分析、结论裁决与报告撰写。

## 材料（全部必读或派子智能体读）
- 仓库：<REPO>（HEAD=da3d084，工作区含两批未提交改动；报告引用一律用占位符 <MAIN_REPO>，禁直书目录名）
- issue#4：只读分析 <LOCAL_WORK>\issue4-reports\issue4-修复报告.md；施工报告 <LOCAL_WORK>\issue4-reports\issue4-施工报告.md；复核报告 <LOCAL_WORK>\issue4-reports\issue4-复核报告.md；证据 <LOCAL_WORK>\issue4-reports\evidence\
- issue#5：只读分析 <LOCAL_WORK>\issue5-reports\issue5-修复报告.md；施工报告 <LOCAL_WORK>\issue5-reports\issue5-施工报告.md（含复核勘误节）；复核报告 <LOCAL_WORK>\issue5-reports\issue5-复核报告.md；证据 <LOCAL_WORK>\issue5-reports\evidence\ + 复核复跑 <LOCAL_WORK>\issue5-reports\recheck-rerun\

## 修复概要
- #4（裁决=零行为变更契约对齐+症状面兜底）：4 处注释改写（workspace.ts#enterPage 区、types/workspace.ts、ToolScope.java、frontend-tools.ts#matchesScope 文档）；ResilientChatService#SYSTEM_PROMPT 新增第 10 条（推进新步骤后该步骤工具下一轮才可用，引导用户回「继续」）；新增 ToolScopeTagSpaceGuardTest（ASM 扫全仓断言无 page:export/import）；ContextBuilder#pageLabel 页面文案中文化（PAGE_LABELS 镜像前端）；删 workspace.ts#leaveWizard 死代码。后端 354→357/0/0/0。探针 s1-s4 实测（s3 起真后端取端点真值）。
- #5（loading 状态失步修复）：stores/ai.ts R1（finally 以 AbortController 身份判定 isCurrentRound）/R2（handleFrame 终帧按宿主归属判定，非本轮帧整体丢弃）/R3（stop() 不抢清 loading）/R4（双层守卫写 notice 不清输入框，公开 action noticeBusySend()）/R5（resetSession abort+复位；reattachActive 先换身份再 abort 旧连接）/R6（旧轮迟到提示不写新轮）/G6（删 restoreSuspendedSnapshot 死守卫）；AiPanel.vue#send 面板层守卫非静默。探针 q1-q8+q9 全实测。

## 关键判断点清单（你本体裁决）
- K1：#4「零行为变更」是否名副其实——逐 hunk 审 diff，找任何运行时语义变化（ContextBuilder 中文化与提示词增补是裁决内改动，其余都该是注释/测试）。
- K2：守卫测试「page:export/import 永不得出现」是 feature 还是 future footgun——若未来确需页面级披露粒度，该守卫挡路的代价与收益。
- K3：SYSTEM_PROMPT 第 10 条的副作用——是否与既有条款冲突？是否给模型「推进后甩锅让用户发继续」的摆烂借口？措辞会不会被泛化滥用（该推进时不推进）？
- K4：ContextBuilder#PAGE_LABELS 后端镜像与前端 PAGE_LABELS 双处维护的漂移风险；pageLabelMirrorsFrontendLabels 测试是真对账还是硬编码复读。
- K5：ContextBuilderExtraTest :74 断言更新是否掩盖了非预期行为变化。
- K6：G1 只修症状面（提示词兜底）不修机制——残余风险评估：模型不服从提示词时防线③ SCOPE_NOT_DISCLOSED 的行为形态是否可接受；观察项登记是否足够。
- K7：R1 身份判定覆盖完整性——所有可能复位 loading/currentAbort 的路径（send/reattachActive/resumeSnapshotStream/stop/resetSession）是否都过了归属判定，有无漏网路径。
- K8：R2「非本轮帧整体丢弃」误伤面——重点复核报告 O4：runSeq 记账在丢弃判定之前，缺 runId 的帧会用 activeRunId 抬水位，理论上使 reattach 的 lastSeq 偏大漏补帧；构造该场景评估真实性与严重度。
- K9：R3 无超时兜底（已裁决不加）——stop() 后若 abort 收尾异常（reject 未触发/finally 未跑），loading 是否可能永远 true？构造攻击场景，若成立评估严重度与是否推翻裁决。
- K10：R4 notice 可见性——守卫写的 notice 会不会被 send() 开头或 start 帧的清 notice 逻辑立即抹掉？守卫命中时无新流，但评估所有相邻路径。
- K11：q3「失步窗口=1 微任务 tick、用户不可达」的结论强度（复核已标【实测+推断】）——若该定性成立，#5 严重度是否应从「低」再降或维持；这影响关闭评论措辞。
- K12：G6 死守卫真死——restoreSuspendedSnapshot 全部调用链核实。
- K13：整体回归面——suspended 确认卡/generative_form/409 重挂/断流重连之外，找施工与复核都没覆盖的交互路径；桩 E2E 28 用例是否需要本地补跑（CI 会跑）。
- K14：两批修复的交互——#4 的 SYSTEM_PROMPT 增补与 #5 的轮次管理是否在同一会话内产生叠加效应（如「请回复继续」恰好撞上 loading 失步窗口）。

## 机械活清单（派子智能体）
- git diff HEAD 全量逐 hunk 转述给你（两批改动 10 文件）。
- 后端全量测试复跑（mvn 路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"，XML 口径应 357/0/0/0、55 XML）。
- 证据文件与报告结论对账抽查。
- 如需探针复跑：scratch 影子副本法（src 复制+node_modules 目录联接+git show HEAD:<path> 注入 before 源码，仓库零写入）；端口用 18332/5213/9337（避开 18331/5211/5212/9335/9336），启动前 netstat，只停自启进程。

## 纪律
- 全程只读+scratch，零 git 写操作，不改仓库任何文件。
- 严重度分级：S1 阻断（必须打回）/S2 高（提交前必修）/S3 中（下批修）/S4 低（登记观察项）。放行条件=无 S1/S2。
- 断言标【实测】/【推断】/【假设】/【旁证】；代码引用以「类名#方法名」为主锚、行号为辅。
- 产出脱敏：禁出现盘符、仓库目录名、用户名、内网 IP、AI key；占位符 <MAIN_REPO>/<REPO_ROOT>/<LAN_IP>/<SCRATCH-REDTEAM>。命名纪律：禁出现 main 加分隔符再接 v2 的任意大小写组合。

## 交付（用户只回路径，不贴全文）
- <LOCAL_WORK>\issue4-reports\issue4-红队确认.md（K1-K6+K14 之 #4 侧结论）
- <LOCAL_WORK>\issue5-reports\issue5-红队确认.md（K7-K13+K14 之 #5 侧结论）
每份含：逐项 K 结论（PASS/攻击成立+严重度+证据）、总判定（放行/打回+理由）、建议（若有）。
```

---

**当前状态小结**：

| 环节 | #4 | #5 |
|---|---|---|
| 施工 | ✅ 357/0/0/0，s1-s4 实测 | ✅ R1-R6+G6，q1-q9 实测 |
| 复核 | ✅ 7/7 PASS，F-A 已修 | ✅ PASS 零阻断，8 观察项 |
| 红队 | ⏳ 等 GLM（卡片在上方） | ⏳ 等 GLM |

杂务棒（agent-225）正在处理复核 O6（内网 IP 脱敏）与 O1/O2（报告行数/动作名勘误）。红队放行后我派提交官（细分提交+push+CI 核验闭环+终扫四模式），然后 webbridge 收尾。

**登记观察项新增**：#4 的 G1 机制修复（回灌边界刷新增补）、G2/G6/G7；#5 的 R3 超时兜底（已裁不加）、O4 runSeq 水位边角、O5 busyConflict 极窄窗口、stop 后红条既有行为、notice 单槽位、G9 跨页签 409 即设计解法。这些随提交官 docs 笔登入 M2 排期去向表。
