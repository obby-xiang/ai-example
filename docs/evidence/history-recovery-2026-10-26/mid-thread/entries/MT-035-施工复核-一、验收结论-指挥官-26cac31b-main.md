<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-035 · 施工复核 · 一、验收结论（指挥官）（26cac31b / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`、`ai-example-<OLD_REPO_DIR>`、`RunStore`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m3` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt1` 条有正文的 assistant 消息（共 20 条有正文），末条有正文消息为 `m22`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `26cac31b` / agent `main`（`main` = 主会话）；原字符 4,635（链内 1 段、合计 4,635）。
> **链号 / 类别**：`MT-035` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `5%`。
> **与末条归档（138 件）的关系**：末条 `m22`（5,084 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 1 / 旧仓名与同源仓名 8 处。

---

验收盘点完成，质量很高。T2b 全条目 PASS、无范围外改动、基线自洽（249+14=263 全绿）、脱敏与命名纪律零违规。以下是我的验收裁决，之后是双卡（红队 DS-V4-Pro + 复现 GLM-5.3），请你分别投放。

## 一、验收结论（指挥官）

**T2b 施工通过验收，进入红队+复现双卡阶段。** 逐项全 PASS，关键确认：断言①升级形态真实发生（冒烟实测 `formRendererCount:1 / toolRunCount:1` 且镜像键已清，重建确由快照路径发生）；`PROMPT_GF1` 与 verify-e2e.ps1 字节级相等；`unresolvedExternal` 口径与 ResumeService 逐字一致。

## 二、对验收子智能体上报 5 项的裁决（留痕用）

1. **断言①验收口径**：以已签字裁决 **T2b-D#3（快照重建+增量续收）形态为验收基准**。S4/F5 的"重放历史帧"字面建议已被 T2b-D#3 明确否决，属被后续裁决取代——红队可攻击判别力论证，但不得以"未按 S4 字面实现"打回。
2. **冒烟脚本无 key 退化**：裁决**补 `SMOKE_SKIP_MODEL=1` 开关**（仅跑②③的零模型回归守卫模式），列入 T2b 修复批（红队/复现回报后统一施工，避免红队审非最终态）。
3. **多 PENDING 单槽位覆盖**：维持 K2.8 登记留痕，转**观察项**（generative_form 单轮单卡为现行场景，多卡并挂出现时再立卡），不阻塞闭环。
4. **快照路径断流收尾口径差异**：**接受差异**，理由——快照路径的挂起态由后端权威管理（TTL/锁在后端），前端不留本地假超时；用户可刷新重试、后端 409/结局帧收敛。要求闭环批在证据文档补一节口径说明。
5. **留痕回写缺口**：闭环批同批回写——决策卡勘误 5 标闭环、排期升 v1.5 标顺手项五项闭环、K2.8 证据文档 §7 数字勘误（backend 实为 5M+2 新测试）。
6. 附带：HITL 确认卡被快照重建属**范围外增益**，闭环批补一句留痕；"无 FRONTEND+approved 写入路径"表述精确化为"无正常写入路径"。

## 三、任务卡 A：DS-V4-Pro 红队（请投放）

```text
你是 M2-T2b 施工的红队审查官（DeepSeek V4 Pro）。目标：攻击式审查 ai-example 主仓库工作区中未提交的 T2b 施工成果，找出会推翻验收结论的严重问题。

【仓库与纪律】仓库 <REPO>，HEAD=2354721，T2b 成果为未提交工作区改动（11 M + 2 新测试类 + docs/evidence/M2-T2b-施工与验证-K2.8.md）。严禁任何 git 写操作、严禁修改既有文件；你唯一允许的写操作是新建你的红队报告文件。构建测试产物允许。

【施工范围】T2b-D#1 session→run 索引（RunStore 新增 ai:session:current:<sid> 键 + save() 联动 SET/DEL + ConfirmGate 心跳续约）；T2b-D#2 新端点 GET /api/ai/runs/current（AiController:388-455）；T2b-D#3 前端快照重建+增量续收（stores/ai.ts:380-575）；勘误2 archiveMaxSeq、勘误3 pendingEntries 全字段、勘误4 hasDecidedPendingResidue 同口径守卫、勘误5/顺手项① scripts/m2t2a-smoke.mjs 断言①升级为真实挂起轮四步场景；顺手项② AiPanel FRONTEND+approved"已放行"显式分支、③ suspended 帧 residue 守卫前移（stores/ai.ts:1202-1206）、④ data-testid 定位、⑤ GENERATIVE_FORM_TOOL 常量固化。规格原文见 docs/M2-T2-返修决策卡.md（勘误 1~5 是规格组成部分）与 docs/M2-排期计划.md v1.4 T2b 注记；自验证据见 docs/evidence/M2-T2b-施工与验证-K2.8.md。

【验收基准与已裁决项——不得以这些为由打回，除非你有推翻性新证据】
1. 断言①以 T2b-D#3 快照重建形态为验收基准（指挥官已裁决：S4/F5"重放历史帧"字面建议被 T2b-D#3 否决）。你可以也必须攻击其判别力论证：该断言能否区分 T2a 缺陷形态（formRendererCount>1 / toolRunCount>1）与修复形态？清镜像键 ai-mirror:<sid> 是否足以证明重建走快照路径？
2. 多 PENDING 条目共用宿主单槽位后写覆盖：已登记为观察项（现行场景单轮单卡）。
3. 快照路径 onClose 不清 activeForm、deadline=null 无本地超时：已裁决接受（挂起态后端权威管理），但请核查是否存在用户无法自救的死态（如后端也永远不收束的路径）。
4. unresolvedExternal 口径含 CONFIRM 类 → HITL 确认卡也会被快照重建：已登记为范围外增益。

【重点攻击面（不限于）】
- RunStore.save() 状态机联动：是否存在绕过 save() 的状态写入导致索引残留？TTL 取 lock.ttl 而非 session-ttl 的正确性？watchdog 续约与锁释放竞态？
- /runs/current 三分支与并发：sessionCurrentRunId 指向已终态 run 的窗口期行为？400 裸 Map 与全局异常处理一致性？
- 前端快照重建：lastSeq=archiveMaxSeq 之后是否可能漏帧/重帧？reattachRun 与 abort 防双连的竞态？historyLoaded 置位时序？
- 冒烟脚本新断言①的稳定性（ flaky 风险：SSE 时序、CDP 等待）。
- 新增两个测试类是否真的覆盖关键路径，有无假绿。

【输出要求】直接把你的红队报告写入 docs/evidence/M2-T2b-施工红队-DS-V4-Pro.md（新建文件，其余零写）。格式：按严重度分级（S1 阻塞打回 / S2 修复建议 / S3 观察），每条给 文件:行号 证据，结论逐条标注【实测】/【推断】/【假设】；结尾给总体判定（通过 / 有条件通过 / 打回）。脱敏纪律：文档禁出现真实 API key、本机绝对路径（用 <REPO_ROOT>/<MAVEN_HOME> 占位）、禁出现 <OLD_REPO_DIR>/<OLD_REPO_DIR>/<OLD_REPO_DIR> 任意大小写组合。中文撰写。
```

## 四、任务卡 B：GLM-5.3 独立复现（请投放，可与红队并行）

```text
你是 M2-T2b 施工的独立复现验证官（GLM-5.3）。目标：不看施工方的结论，亲手复现验证 ai-example 主仓库工作区未提交的 T2b 成果。

【仓库与纪律】仓库 <REPO>，HEAD=2354721，T2b 成果为未提交工作区改动。严禁任何 git 写操作、严禁修改仓库既有文件；唯一允许的写是新建你的复现报告。构建产物允许。端口纪律：后端用 18330，启动前 netstat -ano | findstr :18330 确认空闲；只停你自己启动的进程，结束后全部清理。

【复现四项】
1. 后端测试：cd backend && "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test。记录总执行数/失败数，并独立按 surefire XML（target/surefire-reports/TEST-*.xml 的 tests/failures/errors/skipped 求和）重算一遍。预期：XML 口径 263 执行、0 失败；其中新增 RunStoreSessionCurrentTest 与 AiControllerRunsCurrentTest 各 7 个。若数字不符，如实报告。
2. 前端：cd frontend && yarn typecheck && yarn build，均须退出码 0。
3. 冒烟脚本实跑（scripts/m2t2a-smoke.mjs，CDP 驱动真实 Chrome）：先 node --check 确认语法；然后起后端（AI key 仅环境变量注入：先执行 export AI_API_KEY="<REDACTED_API_KEY>"，禁写入任何文件；另需 export AI_BASE_URL="https://api.deepseek.com" 与 export AI_MODEL="deepseek-flash"，若 application 配置键名不同以仓库配置为准）与前端 dev server，按脚本头部说明运行。预期断言①②③③b 全 pass，断言① detail 应含 formRendererCount:1、toolRunCount:1、convergePath:"submit"。若冒烟环境无法搭建，如实报告卡点，不得编造结果。仓库外 scratch 目录（如 <LOCAL_WORK>/m2t2b-repro/）存放运行产物。
4. 脱敏与命名纪律独立扫描：对工作区全部改动/新增文件 grep：(a) sk- 开头真实 key；(b) <LOCAL_WORK>、<DRIVE>/Program Files、<DRIVE>/Users 本机路径；(c) <OLD_REPO_DIR>|<OLD_REPO_DIR>|<OLD_REPO_DIR> 任意大小写组合（豁免仅 spike/sp01ab/scripts/sp01ab-sanitize.mjs、spike/sp01d/scripts/make-attachments.mjs、spike/sp02/scripts/sp02-sanitize.mjs 三支脚本内的匹配源正则）。预期零违规。

【输出要求】复现报告写入 docs/evidence/M2-T2b-复现验证-GLM.md（新建文件，其余零写）。逐项：执行命令 → 原始关键输出 → 与预期比对 → 结论（一致/不一致），全部标注【实测】；任何无法复现项如实写明环境与报错。脱敏纪律同上（key 记 ***，路径用 <REPO_ROOT>/<MAVEN_HOME>/<SCRATCH> 占位）。结尾给总体判定：复现通过 / 部分通过（列差异）/ 不通过。中文撰写。
```

两张卡投放后把回报（或直接让我知道完成）给我，我做处置 → 复核 → 提交官入库 → T2b 闭环注记。
