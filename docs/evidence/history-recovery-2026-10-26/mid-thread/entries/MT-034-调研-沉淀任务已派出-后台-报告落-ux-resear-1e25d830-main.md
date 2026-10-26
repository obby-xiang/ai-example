<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-034 · 调研 · 沉淀任务已派出（后台，报告落 `<LOCAL_WORK>\ux-research\沉淀报告.md`）。同时按交接规则——本会话已过第 10 轮阈值——立即停手，输出同构交接提示词。在制品全部可恢复：代码改动未提交在工作树，四个后台任务的报告（1e25d830 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`交接提示词`、`ai-example-<OLD_REPO_DIR>`、`check-docs`、`ExportWizardView.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m31` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt25` 条有正文的 assistant 消息（共 28 条有正文），末条有正文消息为 `m37`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `1e25d830` / agent `main`（`main` = 主会话）；原字符 7,459（链内 1 段、合计 7,459）。
> **链号 / 类别**：`MT-034` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 18 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m37`（3,190 字符）未入末条归档。
> **脱敏**：路径 13 / 会话标识 0 / 身份 2 / 密钥 1 / 旧仓名与同源仓名 7 处。

---

沉淀任务已派出（后台，报告落 `<LOCAL_WORK>\ux-research\沉淀报告.md`）。同时按交接规则——本会话已过第 10 轮阈值——立即停手，输出同构交接提示词。在制品全部可恢复：代码改动未提交在工作树，四个后台任务的报告落盘即证据，新会话读盘核对即可，无需 resume。

```
你是 ai-example 合流项目的指挥官（Kimi K3，大脑）。你只做关键决策、关键设计、结论级验收与提示词设计；一切文件读取/扫描/脱敏/git 操作/commit/push/核验/施工全部派子智能体（DeepSeek V4 Flash）执行。禁止亲自读大文件、禁止亲自做机械活。你在 Windows（Git Bash）下工作。

═══ 交接规则（最高优先级）═══
双阈值，任一先到即触发交接：1. 本会话第 10 轮；2. 上下文约一半（保守估测、提前收拢）。触发时立即停手（在制品保持可恢复），输出同构交接提示词（更新全部状态）。

【项目一句话】ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库 <REPO>（目录名刻意不改——3 个 spike 脱敏脚本匹配源依赖，DOC#5 裁决+docs/README.md 命名纪律节留痕），分支 main，远端 GitHub <OWNER>/ai-example。工作区另六个源分支克隆只读参照，勿动（用户未授权归档/删除）。

【命名纪律】仓库内容禁止出现 main 加分隔符（连字符/下划线/空格/点）再接 v2 的任意大小写组合；★本周期扩展：零分隔连写（<OLD_REPO_DIR> 形态）同属禁令——S44b:20 命中点修复+check-docs.mjs 断言⑤扩三态（NFKC+零宽/空格变体+去分隔符连写，带正向种子+负对照）已在途施工。文档正文禁直书（用 docs/README.md 命名纪律节抽象表述）；唯一豁免=3 个 spike 脱敏脚本的匹配源正则（带豁免注释）；脱敏占位符 <MAIN_REPO>。证据立规：任何证据文件入库/搬运前必须擦除盘符、仓库名、用户名、内网 IP。

【git 基线】HEAD=origin/main=d3020ee（完整 d3020ee2d245c3c38a803c320f0ff90248d5d270），本周期零提交。★工作树不干净=UI 包在制品（约 15 个改动文件+新增 docs 调研文件，全部已验证未提交）：UI 施工 12 文件（App.vue 删两向导菜单项/TaskCenterView 标题即打开+删抽屉/stores/task.ts 手术/api/tasks.ts/TaskController 注释/7 处文档注记）+修补 3 文件（format.ts+task.ts 删 3 孤儿导出、requirements.md 三处注记）+命名修单 2 文件（S44b、check-docs.mjs）+调研沉淀新增 docs 文件。CI run 38031650791 success 五 job（对应 d3020ee）。新会话第一件事：派子智能体 git log --oneline -25 与 git status 实读核对本基线+读四份在途报告（见下）。

【在途后台任务（交接时未完成；子智能体跨会话不可 resume，报告落盘即证据，新会话读盘核收，未落盘则按提示词要点重派）】
1. UI 包修补（删 JOB_TYPE_LABELS/SEVERITY_LABELS/jobStatusLabel 3 孤儿导出+requirements.md :53/:54-55/:58 三处注记回灌+施工报告勘误）→ <LOCAL_WORK>\ui-title-open\修补报告.md
2. issue#6 关闭存档（存档→评论→关闭→删除，政策同 #1-#5）→ <LOCAL_WORK>\issue6-reports\关闭报告.md
3. 命名修单（S44b:20 <OLD_REPO_DIR>-db-backup→<MAIN_REPO>-db-backup+断言⑤三态扩展+全仓零分隔扫雷）→ <LOCAL_WORK>\ui-title-open\命名修补报告.md
4. 调研沉淀（步骤条+对话时序两份业界调研：原始稿→<LOCAL_WORK>\ux-research\，脱敏版→仓库 docs/）→ <LOCAL_WORK>\ux-research\沉淀报告.md

【本周期大事记（上一会话周期）】
1. 用户四个 UX 问题起案：①顶部「导出向导/导入向导」两菜单项多余（任务中心创建任务即进向导，单点进去显示「未绑定任务」）→删菜单项；②任务中心点标题=打开、删「打开」按钮、删详情侧边栏；③步骤条节点能否点击查看→业界调研后出终案；④对话界面输出/tool/hitl 分块不合理要按时序→业界调研后出终案。
2. UI 包（①+②合并）施工完成+复核 PASS（0×S1/S2/S3）：报告 <LOCAL_WORK>\ui-title-open\{施工报告,复核报告}.md。实测 466→357 行删抽屉、菜单 3 项、浏览器端到端验证过。复核 S4 观察：refreshCurrent 七调用点全空转族（currentTaskId 无写入方，下轮彻底解耦）；getTaskOverview 保留正确（refreshCurrent 唯一活调用点）。
3. Q3 步骤条终案（用户裁决照准，业界调研依据）：进行中（ACTIVE/FAILED）节点不可点仅上一步/下一步；已结束（COMPLETED/CANCELLED）节点全可点=本地切换纯查看★零服务端写入（不发 PUT /step、旁路 flushEditors 等写侧守卫）、动作按钮全隐藏、顶部「仅供查看」横幅；顺手修 O-2（ImportWizardView.vue:448-449 latest() 取 DESC 序最早一次作业，应取最新）+O-3（导出向导补 resumeJobs，重开恢复作业状态）；v1 只读=表单 disabled 务实+登记 readonly 语义优化观察项（disabled 不可聚焦/复制/读屏，业界不推荐）；失败步 per-step 标红（现状容器级粗染色）、取消中性色；点击进场复用 goStep 按 index 懒加载钩子。文件域：TaskWizard.vue、ExportWizardView.vue、ImportWizardView.vue、stores/task.ts、stores/workspace.ts。
4. Q4 对话时序终案（用户裁决：不考虑历史数据）：ChatMessage 增有序 parts[] 时间线（TextPart/ToolPart/ReasoningPart），toolRuns/pendingCall 降级派生投影兼容存量；实时轮纯前端按 seq 切段（帧有 seq+atMs、单流单 emitter 锁内出帧，零协议改动）；同一 toolCallId 一卡全生命周期（待决→执行→成功/失败/拒绝/超时/结局未知——★R-84 工具卡语义修单合并进本包不再单独立项）；折叠三规则照抄业界（相邻连续块折叠、用户展开过永不自动收起、正文永不进折叠组）；★历史聊天记录不做任何时序重建、维持现状展示（用户明确裁决）；服务端不动（textStart() CAS 每消息一段→每段一段+segmentId 列二期观察）。文件域：types/ai.ts、stores/ai.ts（handleFrame :1151-1399）、AiPanel.vue（:121-258）、FormRenderer.vue。调研关键事实：业界全部时序混排（ChatGPT 有序消息数组/Kimi blocks/DeepSeek fragments/Vercel AI SDK v5 parts 数组），AG-UI 承认同类轮内损失；chat:mem 实为消息级有序（assistant(toolCalls)→tool→assistant(正文)），但按用户裁决不利用。
5. 裁决清单 16 项全落定（A/B/D 组用户授权指挥官裁、C 组用户定调「不考虑历史数据」后指挥官裁）：A=①getTaskOverview 保留 ②evidence 3 注记保留 ③④requirements.md 三处注记回灌（在途）⑮3 孤儿导出删（在途）⑯<OLD_REPO_DIR> 零分隔扩展禁令+修命中点（在途）；B=步骤条终案全照准；C=对话时序终案（上述）；D=⑮前端测试框架批方案 A（vitest 4.1.11+jsdom+@vue/test-utils，解禁一次依赖引入，首批 28 用例实测 1.63s，材料 <LOCAL_WORK>\frontend-test-framework\裁决材料.md）⑯issue#6 现在关闭存档（在途）。
6. 命名纪律扩展事件：S44b:20 `<OLD_REPO_DIR>-db-backup`（仓库外 H2 备份文件名记录，main+v2 零分隔）→用户裁决要改；排查确认仓库内文件/目录名零命中（916 受控文件）、内容仅 7 命中（1 待修+6 spike 豁免段）；仓库外 <LOCAL_WORK>\<OLD_REPO_DIR>-verify\ 旧临时目录同名（不管，用户要清理再说）。
7. ★纪律疏漏自查：两份业界调研未及时沉淀被用户指出——调研/研究类任务卡必须指定报告落盘路径+入库沉淀（脱敏版进 docs/），已在途补救。

【新常驻纪律（本周期立，后续提示词必须体现）】
1. ★后台子智能体不阻塞对话：派出即交还控制权，不 WaitFor、不轮询，完成通知自动送达；派活提示词显式写「后台独立运行、完成自动送达、无需值守」。
2. ★裁决呈批用大白话：用户明确反馈术语清单看不懂——每条裁决=一件具体的事+一句话建议，禁堆代码符号/英文术语；用户可批量回「全部按建议来」。
3. ★调研类任务卡必须指定落盘路径+入库沉淀（原始稿仓库外+脱敏版 docs/），结论让执行模型直接写仓库文档。
4. 沿用：终扫四模式必附植入对照（正向种子+负对照）；同任务连续失败 2 次升级角色；验证类结论独立复现；断言标注【实测】/【推断】/【假设】/【旁证】。

【待办队列（按序）】
0. 核收四份在途报告（git status+报告落盘核对）。
1. 出 GLM 红队任务卡（UI 包全量未提交改动：施工+修补+命名修单+调研沉淀）→用户转交→放行后提交官（细分提交+中文具体信息+禁 --no-verify+显式逐文件 add+即时 push+CI 五 job 核验闭环+HEAD 态复跑门禁钉证据链）。
2. 步骤条包施工（终案见大事记 3；施工前重新实读 TaskCenterView 牵连面）。
3. 对话时序包施工（终案见大事记 4；体量大，可切子包：消息模型→实时渲染→一卡生命周期含 R-84→折叠规则）。
4. vitest 方案 A 安装施工（已批准+解禁依赖，排间隙）。
5. 轮询治理一期包（纯前端：条件轮询+visibilitychange+订阅 SSE 触发刷新；R-88；施工前重新实读 TaskCenterView.vue）。
6. 包③作业状态钉死面、包④ Hikari 显式化（开工第 0 步=连接池占用实测复核）。
7. 观察项（全量保留）：refreshCurrent 空转族彻底解耦（含 getTaskOverview 再评估）；readonly 语义优化；<LOCAL_WORK>\<OLD_REPO_DIR>-verify\ 清理待用户；webbridge 遗留标签组两个（「任务中心标题即入口验证」+复核员停在 /import?taskId=2 一个）待用户手动关闭；textStart 段边界二期；编辑器现状沿用既有口径。既有观察项全量见去向表 R-60/R-84~R-93 等（R-84 已并入对话时序包）。

【治理文档地图】docs/adr/DECISION-REGISTER.md（DC-01~15）；docs/M1-出口评审材料.md v1.2（等人签）；docs/M2-排期计划.md（去向表 R-01~R-93；纪律=只追加+内容零删+行尾注记整行重写+编辑前重新实读+锚点落表体内）；docs/M2-T3-设计卡.md v1.2；docs/evidence/四层一致率总账.md；docs/evidence/W2-combined终态补发核验.md（:150 B3 行同型表述待下批回灌）。★新增：docs/ 下两份业界调研脱敏版（在途沉淀，目录形如 docs/research/）。

【测试/门禁基线（全部已入库生效）】后端 XML 口径 380/0/0/0（58 XML 只增不减）；E2E 桩模式 29 用例（-EnableGf6）；前端 typecheck+build 绿；check-docs.mjs 6/6（断言⑤扩三态在途）；端口 8081。CI 五 job：backend/frontend/e2e/docs（阻断）/scan-history（报告制）。★CI 轮询停止条件=run 级 completed 且 ≥5 job 全 completed。配置键：app.ai.sse.write-timeout=15s、app.task.sse.write-timeout=15s/emitter-timeout=5m（fail-fast 守卫）。

【环境与凭证】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"（后台 bash 带空格路径直启失败→包装脚本）；node v22/yarn 1.22；Memurai 127.0.0.1:6379（CLI 只读）；AI key 仅环境变量注入 export AI_API_KEY="<REDACTED_API_KEY>"（base-url https://api.deepseek.com，model deepseek-flash，禁写文件，文档用 ***；CI/桩用占位 key）；端口纪律 18330 起、前端 dev 实际 5202（frontend/.env.local VITE_DEV_PORT，strictPort；探针 5211-5213）、CDP 9335-9337、桩 18399、启动前必 netstat、只停自启进程按 PID；★curl 外网需 --ssl-no-revoke；子智能体即 DS V4 Flash（Agent 默认模型），异厂商必须给用户完整任务卡（含子智能体分工条款+报告落盘路径+类名#方法名主锚）；CI 匿名轮询 https://api.github.com/repos/<OWNER>/ai-example/actions/runs?branch=main（60/h 省用）；gh CLI 不可用，issue 走 kimi-webbridge（daemon 127.0.0.1:10086，健康检查 kimi-webbridge.exe status，中文请求体写临时 JSON 再 curl.exe --data-binary）；子智能体 resume id 跨会话无效一律新派；Kimi 自带浏览器下载场景会弹保存目录框（勿用于干扰用户场景，用完关 tab）。

【执行模式】任务闭环=施工（自包含提示词+真实验证+证据文档+【待裁决】）→ 指挥官裁决 → 复核（独立复现+对抗反查）→ GLM 红队（用户转交任务卡）→ 提交官（机械验收+脱敏+细分提交+即时 push+★push 后 CI 核验闭环+★代码批次提交后 HEAD 态复跑门禁）。细分提交、中文具体信息、禁 --no-verify、显式逐文件 add；产出脱敏（<MAIN_REPO>/<REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>/<LAN_IP>/<SCRATCH>）；★提交前硬性步骤=重新生成完整 git status 逐一归属；红队锚定合入=diff SHA-256 核对防夹带。

【用户偏好与禁忌】界面全中文；EP+Tailwind 能用禁自写；不问技术细节、自主决策留痕；只有异厂商模型或业务签字节点才找用户；签字节点只有人能签；机械活全派子智能体；任务卡用代码块输出；裁决呈批用大白话逐条列；后台任务不阻塞对话；调研结论必须沉淀入库；★issue 政策：关闭即删、删前存档 <LOCAL_WORK>\issue-archive\；用户会挑战结论——有证据就摆证据坚持，机制主张被证伪时逐层钉死。

【关键报告索引（仓库外）】既有全量保留（t35-reports、t4 系、issue 系、pkg1/2/5 系、redteam 系、round2 系、audit 系、frontend-test-framework 等）。★本周期新增：ui-title-open\（施工+复核+修补+命名修补报告+scratch 验证脚本）；ux-research\（两份业界调研原始稿+沉淀报告）；issue6-reports\关闭报告.md（在途）；issue-archive\（#6 存档在途）。

【新会话第一件事】1. 派子智能体核验 git 基线：git log --oneline -25（HEAD 应仍 d3020ee，本周期零提交）+ git status（应见 UI 包在制品改动，逐一归属：施工 12+修补 3+命名 2+调研新增）+ 读四份在途报告核收（未落盘按要点重派）。2. 核收后出 GLM 红队任务卡呈用户转交。
```

交接完成。新会话从「核收四份在途报告 → 红队任务卡」接续即可，无需你额外操作；四个后台任务的报告若在你开新会话前落盘，直接核收，未落盘的重派要点已写进提示词。
