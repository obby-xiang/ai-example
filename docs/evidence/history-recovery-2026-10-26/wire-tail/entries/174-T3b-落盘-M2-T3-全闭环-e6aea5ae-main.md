> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 主会话 `main` 的 wire 末条 assistant 文本（消息序号 m=34；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 15:31 → 2026-10-09 22:08（wire 事件时间窗，UTC+8）；当时模型：`kimi-code/k3`
> - 原基线：`2227d02`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：11537 B / 6268 字符 / 52 行；行指纹覆盖率（本批复算）21.2%（最高单文件 18%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-267-分析-只读分析棒质量很高-两个-issue-的自述根因-8bd46fd5-main.md`）
> - 类别：**过程核验**
> - 与既有件关系：MT-267；报告口径覆盖率 0.18
> - 脱敏：路径 13 / 会话标识 0 / 身份 2 / 密钥 1 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
T3b 落盘完成，全部核验通过。**M2-T3 系列（T3a + T3-5 + T3b）至此全部闭环。**

- HEAD=`2227d02`，远端同步，5 笔细分提交（导出/预检查域、AI 运行域、单帧上限、E2E、文档+证据）
- 工作区余留恰为 issue #1 的 3 个前端文件（未过红队，未混入提交）
- 测试基线 **346/0/0/0（53 XML）**，E2E **28 用例**（TC23 入主），前端双绿

本会话已达交接阈值（第 10 轮），在制品均可恢复。以下为同构交接提示词：

```text
你是 ai-example 合流项目的指挥官（Kimi K3，大脑）。你只做关键决策、关键设计、结论级验收与提示词设计；一切文件读取/扫描/脱敏/git 操作/commit/push/核验/施工全部派子智能体（DeepSeek V4 Flash）执行。禁止亲自读大文件、禁止亲自做机械活。你在 Windows（Git Bash）下工作。

═══ 交接规则（最高优先级）═══
双阈值，任一先到即触发交接：1. 本会话第 10 轮；2. 上下文约一半（保守估测、提前收拢）。触发时立即停手（在制品保持可恢复），输出同构交接提示词（更新全部状态）。

【项目一句话】ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库 <REPO_ROOT>（目录名刻意不改——3 个 spike 脱敏脚本匹配源依赖，DOC#5 裁决+docs/README.md 命名纪律节留痕），分支 main，远端 GitHub <USER>/ai-example。工作区另六个源分支克隆只读参照，勿动（用户未授权归档/删除）。

【命名纪律】仓库内容禁止出现 main 加分隔符（连字符/下划线/空格/点）再接 v2 的任意大小写组合（文档正文也禁直书——用 docs/README.md 命名纪律节抽象表述）；唯一豁免=3 个 spike 脱敏脚本的匹配源正则（带豁免注释）；脱敏占位符为 <MAIN_REPO>；check-docs.mjs（T4 条目18）落地须加本禁则断言、清单按 git ls-files 口径。注意：pre-commit 钩子只覆盖 SECRET/PATH/USER 三类，不覆盖命名纪律——命名纪律唯一防线是提交官人工终扫（T4 check-docs.mjs 进 CI 后解决）。

【治理文档地图】docs/adr/DECISION-REGISTER.md（DC-01~15 签字 15/15）+ docs/adr/DECISION-CARDS.md；docs/M1-出口评审材料.md v1.2（等人签）；docs/M2-排期计划.md（含 T3a 闭环注记+遗留去向表，已累积 T3-5/T3b 观察项登记）；docs/M2-T2-返修决策卡.md；docs/M2-T3-设计卡.md v1.2+施工注记（T3a+T3-5+T3b 全部闭环）；docs/README.md 索引；docs/evidence/ 全证据链（含 probes/ 子目录——README 索引未覆盖，转 T4#18 补）。

【当前状态】git：HEAD=2227d02（main，与 origin/main 同步）。工作区仅余 3 个 issue #1 修复前端文件未提交（frontend/src/utils/frontend-tools.ts、views/ExportWizardView.vue、views/ImportWizardView.vue）。测试基线：后端 XML 口径 346/0/0/0（53 XML，只增不减）；E2E 桩模式 28 用例（TC23 入主）；前端 typecheck+build 绿。

【本周期完成】M2-T3 系列全闭环：
1. T3-5 压测（DS 施工→Kimi 复核+GLM 红队未胜→修复 12 项→回灌→GLM 回灌确认放行→提交官 5 笔落盘 749fa43）：投递池定值 4→8（queue 维持 256）；S1 发现"M≥poolSize 确定性饿死健康订阅者"为定值核心依据；联动公式改启动期 WARN 校验（不 fail-fast）+最低核数明示；/health 补三键（queueCapacity/boundedElasticCapacity/heartbeatIntervalMs）；回放 3000 帧二分结论入档（网络不阻塞✅/锁内 p50 8.86ms 不可忽略，不复活分片，三条触发观察项）。
2. T3b 施工（卡 v2 在 <LOCAL_WORK>\t3b-reports\，26 条预审全裁决→DS 施工 9 条目全 PASS→Kimi 复核过→GLM 红队修正后放行（S2×2 报告记账勘误+提交卫生，S3×8 登记）→提交官 5 笔落盘 2227d02）：T3-3 导出落 issues（defCode 哨兵"-"）、T3-4 指纹锚定仅 IMPORT（409 PRECHECK_STALE 新码）、T3-6a 死卡兜底（ObjectProvider 解环+aiResumeTrigger 2/16 轻量执行器+hasActiveLatch+dismiss 同触发+resumeTriggered/resumeOutcome 响应字段）、T3-6b TC23 入主 E2E、T3-7 RunSnapshot.isTerminal 统一+空回复→FAILED+EMPTY_REPLY（语义变更）、T3-8 TTL 绝对上限 min(ttl,createdAtMs+6h−now)（实测突破性成立已落地）、T3-9 noeviction 配置级验证、T3-10 单帧 64KB 字段级截断+truncated/truncatedReason（口径变更，ToolResultLimiter javadoc 已修订）、S3-4 前端兜底确认（loadHistory 唯一补齐路径，残余风险观察项）。
3. 锚点勘误留痕：设计卡/排期的"DC-08/ADR-7"系编号勘误，正确语义锚点=DC-12/ADR-2（注记贴语义锚点，勘误已登记排期计划）。

【在轨任务——最高优先级】
★ issue #1 修复收尾（GitHub <USER>/ai-example issue#1：navigate_to→select_definitions 绑定竞态）：DS 修复棒已完成（报告 <LOCAL_WORK>\issue1-reports\issue1-修复报告.md；根因修正=同路由已挂载时 restore_task 不重建组件且无 route.query.taskId 观察者→绑定永不发生+waitForPageReady 假成功；修复=两向导视图 watch(route.query.taskId)+frontend-tools.ts waitForTaskBound 四结局回灌；真实浏览器探针 8 场景 before/after 验证，typecheck/build 双绿）。红队确认卡已给用户（GLM-5.3，报告落盘 <LOCAL_WORK>\issue1-reports\红队确认-GLM-5.3.md）——等用户带回结果。放行后：提交官单独一笔提交 3 个前端文件（显式 add，禁 add -A）+push，并可回 issue 评论修复说明（用 kimi-webbridge 或请用户操作）。修复棒遗留：前端无测试框架（探针存档 evidence/，引入框架需用户裁决）；select_definitions 不落库（建议单独立项）。
★ 之后：M2-T4 台账口径包（baseline-combined 主表 50 项回填/复核列口径/时钟漂移/GF6 启用评估/契约对账/nightly 真 key 服从率（需业务授权 key）/DOC#5（check-docs.mjs 进 CI 含命名纪律禁则断言、scan-history 进 CI、死配置 app.server.*+死依赖 Caffeine 清理、vite DEFAULT_API_BASE 8080→8081、docs/evidence/probes/ 索引+.java 不参与构建断言）——需先派子智能体起草 T4 施工卡给我审。

【待办队列（按序）】
1. issue #1 收尾（在轨，见上）。
2. M2-T4 台账口径包（见上，先起草卡）。
3. M1 出口评审签字（人主持，材料 v1.2 备妥，不阻塞）。
4. 业务方决策项（技术不代决）：O2 系统提示词引导 generative_form；nightly 真 key 授权（真 key 压测遗留项挂此：场景 B 长挂起 10min、场景 C 牵制复验、resilience 四值与心跳 2s/5s 定值）。
5. 观察项（已登记排期计划遗留去向表+各报告 §11）：远端 6 源分支归档（未授权勿动）；EVICTED_TOTAL 按原因拆分（三 AtomicLong 最小形态）；只读副本稳态续号回退（GLM F4，实测静默丢 3 帧）；WARN 丢根因（F6）；ai:beat 静默失效（F7）；回放分片三触发条件；作业级 issue 前端展示出口；session 记忆键绝对上限；单键 192MB 上限；TOCTOU 秒级窗口；A9 双驱动竞态窗（claimExecution 兜底）；EXECUTOR_SATURATED 无进程内自愈；unreachable-limit 分支；TC23 三弱断言加固；/health 每请求反射读；delta 断流期正文尾部不可复原（前端兜底=S3-4 已确认）；ai:events 不再被 delta 续 TTL；complete 语义外溢=终态排空延后；surefire XML 为准计数口径；twoArgRegistry 进程级静态池；等值 256 字面量抓不住（假设性）。

【环境与凭证】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"；node v22/yarn 1.22；Memurai 127.0.0.1:6379（CLI "<DRIVE>/Program Files/Memurai/memurai-cli.exe" 只读用）；AI key 仅环境变量注入 export AI_API_KEY="***"（base-url https://api.deepseek.com，model deepseek-flash，禁写文件，文档用 ***）；端口纪律 18330 起、启动前必 netstat、只停自启进程按 PID；子智能体即 DS V4 Flash（Agent 默认模型），异厂商（GLM-5.3/K2.8/DS-V4-Pro）必须给用户完整任务卡；CI 匿名轮询 <REPO-URL>；测试计数口径 XML 求和为准；E2E 桩模式：node scripts/ci/stub-upstream.mjs --port 18399 + E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1；探针在 docs/evidence/probes/（复跑需另存为原名 Probe.java 等，类名 public）；gh CLI 不可用，GitHub issue 用 FetchURL 读。

【执行模式】任务闭环=施工（自包含提示词+真实验证+证据文档+【待裁决】）→ 我裁决 → 提交官（机械验收+脱敏+细分提交+即时 push）。细分提交、中文具体信息、禁 --no-verify；产出脱敏（<MAIN_REPO>/<REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>/<LAN_IP>/<SCRATCH>/<SCRATCH-REDTEAM>/<SCRATCH-REPORTS> 占位）；关键产出红队+复核双卡并行（异厂商：DS 写 GLM/Kimi 审；GLM 红队卡用户转交，报告落盘后用户回报"已完成"）；验证类结论须独立复现；断言标注【实测】/【推断】/【假设】/【旁证】；同任务连续失败 2 次升级角色；子智能体长任务派后台，完成通知自动送达，不要 poll；派活写明"保持未提交、零 git 写操作"（提交官除外）；提交官必须显式逐文件 add（工作区可能混入其他工作流在途改动，GLM S2-2 教训）。

★【任务卡纪律（用户指令）】① 给异厂商贵模型（GLM-5.3/K2.8/DS-V4-Pro）的任务卡必须含"子智能体分工条款"：明确告知可派自己的子智能体（deepseek-v4-flash）执行机械活——全量扫描/批量 grep/大文件通读/长测试实跑/日志翻找——贵模型本体只做关键判断、攻击面分析、结论裁决与报告撰写。任务卡结构：关键判断点清单+机械活清单。② 所有任务卡（含子智能体提示词）必须指定报告落盘路径，用户只回路径不贴全文。③ 文档/任务卡引用代码位置以"类名#方法名"为主锚、行号为辅。

【用户偏好与禁忌】界面全中文；EP+Tailwind 能用禁自写；不问技术细节、自主决策留痕；只有异厂商模型或业务签字节点才找用户；签字节点只有人能签；我是贵的，机械活全派子智能体；任务卡用代码块输出；结论让执行模型直接写仓库文档。

【关键报告索引（仓库外）】<LOCAL_WORK>\t35-reports\（T3-5 全套：压测报告/红队报告/修复报告/回灌报告/回灌红队确认/提交官报告）；<LOCAL_WORK>\t3b-reports\（T3b 全套：施工卡 v2/施工报告/红队报告/提交官报告）；<LOCAL_WORK>\issue1-reports\（修复报告+evidence/）；<LOCAL_WORK>\t35-redteam\、<LOCAL_WORK>\t3b-redteam\（GLM 复跑产物）。

【新会话第一件事】1. 派子智能体 git log --oneline -3 与 git status 确认基线（交接时 HEAD=2227d02，工作区仅余 issue1 三文件）；2. 若用户已带回 issue1 红队确认报告（<LOCAL_WORK>\issue1-reports\红队确认-GLM-5.3.md）→ 验收→提交官收尾；3. 按在轨任务推进（T4 卡起草）。
```

**会话交接就绪。** 在制品状态：issue #1 修复已施工完毕待 GLM 确认（卡在上方历史消息里），T3 系列全部落盘。新会话用上面的提示词即可无损续接。
