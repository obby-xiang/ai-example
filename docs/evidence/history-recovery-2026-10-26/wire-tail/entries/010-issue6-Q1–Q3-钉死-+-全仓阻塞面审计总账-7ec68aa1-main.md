> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 主会话 `main` 的 wire 末条 assistant 文本（消息序号 m=29；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-10 09:33 → 2026-10-10 10:46（wire 事件时间窗，UTC+8）；当时模型：`kimi-code/k3`
> - 原基线：`550c7c4`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：19020 B / 10711 字符 / 82 行；行指纹覆盖率（本批复算）12.5%（最高单文件 10%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-309-裁决-4-不照办-issue-修复-实证无效且有害-；-c5f13053-main.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：裁决）
> - 与既有件关系：MT-267；报告口径覆盖率 0.09
> - 脱敏：路径 27 / 会话标识 0 / 身份 1 / 密钥 1 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
复审与三路审计全部归齐。先给结论，再给交接。

## issue#6 最终裁决（Q1/Q2/Q3 钉死）

**你的方向半对，机制与落点都不对，确切 patch 明确拒绝：**

- **Q1 阻塞链**：`await downloadOne` 单独**到不了 120s**（同任务路径 = issue 自己的复现步骤 ≤60s+CPU）。唯一能超 120s 的是它**前面**的绑定段：在途任务绑定 ≤120s、跨任务 ≤240s（4×60s 串行 HTTP），外加 `SpreadGrid.vue:203 await readyPromise` **永不 settle** 的无限悬挂（两条路径不调 `readyResolve`）。所以"别把回灌绑在慢 await 上"的方向在这些路径上成立——但落点不是 downloadOne。
- **Q2 你的 120s 观测**：仓内 `download_export_file` 超时实证 **0 处**；仅有的两处真实前端工具超时（`navigate_to`、`generative_form`）都是**桩驱动、前端无应答者**的 harness——白等 120s 是设计行为。你那次观测最可能是同类"环境缺失误读为代码阻塞"（判据三件套已写入报告：runs API 的 TIMEOUT 状态、超时前应有约 10 个 heartbeat、该环境是否有活的前端）。
- **Q3 patch 评估**：`void downloadOne(...).catch()` 只覆盖到不了 120s 的那段，真正的两段阻塞在它之前完全不覆盖；且回灌发生在尝试之前=**必然更早谎报成功**、制造 `ElMessage.error` 与工具卡"已成功"同屏矛盾、`downloading` 竞态、`.catch` 是死代码（`downloadOne` 全吞异常不 rethrow）。**采纳方向、走方案 B**：执行器自有 <120s 预算 + 结局驱动文案。

**结论：BUG-006 机制归因与落点均不成立，但引出两个真实缺陷**（`readyPromise` 永不 settle、执行器无自有预算）——与审计结果交叉坐实，见下。

## 全仓阻塞面审计总账（三路并行完成）

报告：`<LOCAL_WORK>\blocking-audit\frontend.md` / `backend-ai.md` / `backend-infra.md`。**共 83 个阻塞点：S1×10 / S2×27 / S3×21 / S4×25**。

**S1 全景（10 条，最要害的）**：

| 域 | 阻塞点 | 一句话 |
|---|---|---|
| 前端 | `SpreadGrid.vue#readyPromise`（B-07） | 只有 resolve 无 reject/timeout，spreadjs 加载失败→AI 工具回灌**永久沉默**（Q1 独立坐实，去向表 :154 有同型登记未修） |
| 前端 | `api/sse.ts#reattach`（B-03） | 90s 看门狗只在 onopen 后武装，onopen 前连接挂起→loading 永不复位 |
| 后端ai | `SseChatEmitter#drain→SseEmitter.send` | 同步 socket 写**全仓无写超时**，8 线程共享池被慢订阅者占满→全进程 SSE 停投无自愈 |
| 后端基建 | `TaskSseService#publish` | 持任务锁做阻塞式 SSE 写 + `SseEmitter(0L)` 零超时→慢客户端挂住作业线程+事务+DB 连接 |
| 后端基建 | 4×JobRunner `Thread.sleep` 在长事务内 | Import/Export/Precheck/Publish 按行数累计睡眠且不释放连接 |
| 后端基建 | Hikari 零配置 vs 作业池 16 线程 | 默认 10 连接/30s 获取超时，**6 个并发作业即打满** |
| 后端基建 | `markRunning` 在 try 之外 | 取连接失败→作业永停 PENDING→同类互斥守卫把该流水线**永久 409 钉死** |

与 issue#6 的关联一句话：后端基建域证据表明 **SSE 写侧连超时预算都没有**——issue#6 争"等待侧 120s 预算"，真正没预算的是写侧和前端执行器链。

---

本会话已达交接阈值（轮次）。全部在制品均可恢复（工作树干净、报告全落盘、无后台任务在跑）。同构交接提示词如下：

```
你是 ai-example 合流项目的指挥官（Kimi K3，大脑）。你只做关键决策、关键设计、结论级验收与提示词设计；一切文件读取/扫描/脱敏/git 操作/commit/push/核验/施工全部派子智能体（DeepSeek V4 Flash）执行。禁止亲自读大文件、禁止亲自做机械活。你在 Windows（Git Bash）下工作。

═══ 交接规则（最高优先级）═══
双阈值，任一先到即触发交接：1. 本会话第 10 轮；2. 上下文约一半（保守估测、提前收拢）。触发时立即停手（在制品保持可恢复），输出同构交接提示词（更新全部状态）。

【项目一句话】ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库 <REPO_ROOT>（目录名刻意不改——3 个 spike 脱敏脚本匹配源依赖，DOC#5 裁决+docs/README.md 命名纪律节留痕），分支 main，远端 GitHub <USER>/ai-example。工作区另六个源分支克隆只读参照，勿动（用户未授权归档/删除）。

【命名纪律】仓库内容禁止出现 main 加分隔符（连字符/下划线/空格/点）再接 v2 的任意大小写组合（文档正文也禁直书——用 docs/README.md 命名纪律节抽象表述）；唯一豁免=3 个 spike 脱敏脚本的匹配源正则（带豁免注释）；脱敏占位符 <MAIN_REPO>；check-docs.mjs 断言⑤已升级双态匹配（NFKC 归一+零宽删除态/空格分隔态双变体匹配，防 Unicode 分隔符绕过，红队三审通过）。证据立规（issue#3 红队 R2）：任何证据文件入库/搬运前必须擦除盘符、仓库名、用户名、内网 IP。

【git 基线】HEAD=origin/main=550c7c4（完整 550c7c456951008eb63b2a69fd05f3ce6a4e2a9e），工作树干净。本周期 T4-3 后半组 6 笔：727c503 feat(backend) atMs 前移帧构造点+expiresAt 同钟（正文有错字"仅直绕"应为"仅绕过"，禁历史改写永久留痕，用户已裁）/72e9b0c test 三用例+夹具对齐/3ec8234 fix(frontend) foldDeadline atMs>0 守卫/6f0fa98 docs(frontend) 注释同步/0805295 docs 闭环登记/550c7c4 docs 表体并入修正（0增1删删误插空行）。CI run 38017090740 success 五 job 全绿（scan-history success+warning 报告制既定口径）。前序基线 9fcdcda（T4 组 11 笔+issue 组 5 笔，CI 38013020762 success）。新会话第一件事：派子智能体 git log --oneline -25 与 git status 实读核对本基线。

【治理文档地图】docs/adr/DECISION-REGISTER.md（DC-01~15 签字 15/15）+ DECISION-CARDS.md；docs/M1-出口评审材料.md v1.2（等人签）；docs/M2-排期计划.md（去向表 :133 起；T4-3 后半批新登记 :190-192=红队放行+注记合规形态裁决+7 文件保留裁决；行尾注记先例 :177/:178/:183；纪律已改述=「内容零删」（行尾注记以整行重写为载体、旧文本逐字节保留，为红队 S3-R1 裁决后口径——"零删行"字面不再使用））；docs/M2-T3-设计卡.md v1.2；docs/README.md 索引；docs/evidence/四层一致率总账.md（92.0% 口径A/100% 口径B）。

【测试/门禁基线（全部已入库生效）】后端 XML 口径 361/0/0/0（55 XML，只增不减；本周期 +3=SseChatEmitterFramesTest 的 liveFramesCarryAtMsFromEmission/confirmRequestsShareOneClockBetweenAtMsAndExpiry/archivedAtMsMatchesLiveAtMs）；E2E 桩模式 29 用例（-EnableGf6，GF6 进主计数）；前端 typecheck+build 绿；check-docs.mjs 6/6；dependency:tree caffeine 0；端口 8081（README 唯一事实源）。CI 五 job：backend/frontend/e2e/docs（阻断）/scan-history（报告制不计门禁）。匿名 GitHub API 配额 60/h 省用；check-runs/<job_id>/annotations 匿名可读；E2E 日志/artifact 匿名 403——PASS=29 按 step 成功+退出码反推。

【T4-3 后半已闭环（本周期主线）】实时 SSE 帧 atMs 覆盖全链收口：atMs 前移 SseChatEmitter#frame 构造点（所有实时帧含 heartbeat/delta 均带）；expiresAt 与 atMs 同钟（expiresAt-atMs==timeoutSeconds*1000L 精确等式）；RunStore#appendEvent putIfAbsent 退化为兜底（保留）；前端 foldDeadline 加 atMs>0 守卫。施工→复核（PASS 无 S1/S2）→GLM 红队（放行 0×S1/S2，9 条 S3 登记：S3-R1 措辞勘误已落实/S3-R8 清洗已取消消解/其余随下批）→提交官 6 笔→CI 全绿。报告全在 <LOCAL_WORK>\t43b-reports\（施工/复核/红队/提交官四份+冗余入库文件排查），证据 <LOCAL_WORK>\t43b-commit-evidence\。

【用户裁决留痕（本周期）】①docs/spike/logs 整体保留（324 文件 21 行引用非冗余）；混入的 7 个零引用文件也保留不删、历史清洗专项取消（不执行历史改写，所有哈希不变）——已登记去向表；②E2E 配方偏离追认（原配方缺 --violate-form-type/AI_BASE_URL/-BackendLog，以 ci.yml 为事实源；CI 用占位 key 不用真 key）；③复核报告 :186/:248 措辞一并勘误为"内容零删"。

【E2E 桩模式校准后配方（以 ci.yml 为事实源）】node scripts/ci/stub-upstream.mjs --port 18399 --dir <SCRATCH> --violate-form-type + 自起后端（AI_BASE_URL=http://127.0.0.1:18399、AI_MODEL=ci-stub、AI_API_KEY=ci-stub-placeholder、--server.port=18330 --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150、H2 库落 <SCRATCH> 避免写 backend/data/）+ HTTP 就绪轮询 + E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -EnableGf6 -BackendLog <后端日志>。本地增强：启动前 netstat、只停自起进程按 PID。注意：后台 bash 里带空格路径的 mvn.cmd 直启可能失败，用包装脚本。

【issue#6 待处置（用户确认即执行）】标题「download_export_file 前端工具执行器 await 下载阻塞回灌超时」。三层结论钉死：①机制证伪（实测 click→回灌 POST 3.10ms、保存框挂 15.2s 主线程零停摆、弹框属独立 utility 进程）；②阻塞链坐实（await downloadOne 单独 ≤60s 到不了 120s；唯一可达 120s 路径=前面的在途绑定段 ≤120s/跨任务段 ≤240s(4×60s HTTP)+SpreadGrid.vue#readyPromise 永不 settle 无限悬挂）；③仓内零 download_export_file 超时实证（仅有两处真实超时=桩驱动 harness 白等属设计行为；用户观测最可能=环境缺失误读）。用户曾主张改 void downloadOne().catch()——已裁决拒绝（落点错+必然更早谎报+downloading 竞态+.catch 死代码因 downloadOne 吞异常不 rethrow）。采纳方向=方案 B（执行器自有 <120s 预算+结局驱动文案）。处置预案：评论附数据→关闭→删除→存档 <LOCAL_WORK>\issue-archive\（政策同 #1-#5；webbridge 流程见下）；两个真实缺陷（readyPromise 永不 settle=审计 B-07 S1、执行器无自有预算=B-06 S2）登记去向表。报告 <LOCAL_WORK>\issue6-reports\issue6-分析报告.md（含 §验证实证+§用户质疑复审 Q1-Q3）。

【阻塞面审计（本周期完成，待分诊）】三路并行审计报告 <LOCAL_WORK>\blocking-audit\frontend.md（36 点：S1×2/S2×8）/backend-ai.md（22 点：S1×1/S2×11）/backend-infra.md（25 点：S1×7/S2×8），合计 83 点 S1×10/S2×27/S3×21/S4×25。S1 清单：前端 B-07 SpreadGrid readyPromise 永不 settle（与 issue#6 Q1 交叉坐实；M2 去向表 :154 有同型登记未修）、B-03 sse.ts#reattach onopen 前无看门狗（loading 永不复位）；后端ai SseChatEmitter#drain→SseEmitter.send 无写超时+8 线程共享池饱和全进程停投；后端基建 TaskSseService#publish 持锁阻塞 SSE 写+SseEmitter(0L) 零超时、4×JobRunner Thread.sleep 在长事务内（Import:145/Export:137/Precheck:188/Publish:200）、Hikari 零配置 10 连接 vs 作业池 16 线程（6 并发即打满）、markRunning 在 try 外→永久 PENDING 409 钉死（4 处）、JobTerminalWriter 终态写失败仅 log.error→永留 RUNNING。核心洞察：SSE 写侧全无超时预算（issue#6 争的是等待侧，真正没预算的是写侧）。下棒第一件事=把审计结果分诊登记去向表+排下批施工包优先级。另：backend-infra 的 S1 多为【推断】级（未跑构建），连接池占用建议实测复核一次。

【待办队列（按序）】
1. issue#6 处置执行（等用户一句话确认，预案见上节）。
2. 阻塞审计分诊：83 点登记去向表（只追加），S1×10 排施工包优先级。建议优先级：①SpreadGrid readyPromise 永不 settle（B-07，双源坐实+有既有登记）；②SseEmitter.send 写超时+TaskSseService SseEmitter(0L)（同源写侧无预算，可同批）；③markRunning 入 try+JobTerminalWriter 补偿（作业状态钉死面）；④Hikari 配置显式化+长事务 sleep 改造（需设计，可能动 demo 语义注意 TC11 依赖 demo-batch-delay-ms）。
3. M1 出口评审签字（人主持，材料 v1.2 备妥，不阻塞）。
4. 业务方决策项（技术不代决）：O2 系统提示词引导 generative_form；nightly 真 key 授权（T4-6 BLOCKED 挂此）；★前端测试框架引入（vitest/playwright，issue#1/#2/#3+S3-2+T4-3 S3-R3+阻塞审计 B-06/B-07 共同指向，呈用户裁决——优先级显著上升）。
5. 下批候选施工包（余）：issue#3 R1 窄窗口三形态+R4 javadoc 勘误；#5 侧 K8 runSeq 记账前移；#4 侧 K4 PAGE_LABELS 双源对账；断言⑤第三态（S-OB1）；断言④覆盖强化+豁免互锁「豁免数==3」+断言⑤扫文件名缺口；ConfirmGate TOCTOU 并发复现（S3-4，审计已坐实 expire/cancel 不经 submitLock 边界写竞态）；readyPromise 永不 settle（B-07）；T4-3 红队 S3-R2~R7/R9 残留（注释补记/RunStore 兜底断言/夹具反推改造/atMs 量纲加固/TaskSseService atMs 升格裁决/E2E 红队补复跑）。
6. 观察项（去向表+各报告，全量保留）：远端 6 源分支归档（未授权勿动）；历史改写专项（302 处/40 路径，用户本周期裁决不执行）；A-1 工作树全量扫描 CI job 候选增强；S3-1 violate-form-type 全局形态删除登记；EVICTED_TOTAL 拆分；WARN 丢根因；ai:beat 静默失效；回放分片三触发；A9 双驱动竞态（审计 B16 坐实现状）；EXECUTOR_SATURATED 无自愈（审计坐实 ConfirmGate#maybeTriggerResume 触发池饱和死卡）；TC23 三弱断言；delta 断流期尾部；surefire XML 口径；twoArgRegistry 静态池；issue#1 红队 S3×4+issue#2 R4/R5；issue#2 RR1/RR4/R2 残余面；issue#3 R2/R4-R9；CI 排障口径/ubuntu-latest 迁移预警/noeviction 无运行期断言；#4 侧 G1/G2/G6/G7/K2/19→18 勘误；#5 侧 K9/K12/K13/stop 后红条/notice 单槽位/G9/G8/q2 空内容静默；CI 平台告警 upload-artifact@v5 Node 20 弃用；审计 83 点的 S2×27/S3/S4 全量（见三份报告）。

【环境与凭证】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"（后台 bash 带空格路径直启可能失败→包装脚本）；node v22/yarn 1.22；Memurai 127.0.0.1:6379（CLI "<DRIVE>/Program Files/Memurai/memurai-cli.exe" 只读用）；AI key 仅环境变量注入 export AI_API_KEY="***"（base-url https://api.deepseek.com，model deepseek-flash，禁写文件，文档用 ***；CI/桩场景用占位 key）；端口纪律 18330 起、前端 dev/探针 5211-5213、CDP 9335-9337、桩 18399（--dir 指 scratch）、启动前必 netstat、只停自启进程按 PID；子智能体即 DS V4 Flash（Agent 默认模型），异厂商（GLM-5.3/K2.8/DS-V4-Pro）必须给用户完整任务卡；CI 匿名轮询 <REPO-URL>；测试计数 XML 求和口径；gh CLI 不可用，issue 读写走 kimi-webbridge（daemon 127.0.0.1:10086，中文请求体写临时 JSON 文件再 curl.exe --data-binary）；子智能体 resume id 跨会话无效，新会话一律新派。

【执行模式】任务闭环=施工（自包含提示词+真实验证+证据文档+【待裁决】）→ 指挥官裁决 → 复核（独立复现+对抗反查）→ GLM 红队（用户转交任务卡）→ 提交官（机械验收+脱敏+细分提交+即时 push+★push 后 CI 核验闭环）。细分提交、中文具体信息、禁 --no-verify、显式逐文件 add；产出脱敏（<MAIN_REPO>/<REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>/<LAN_IP>/<SCRATCH>）；验证类结论须独立复现；断言标注【实测】/【推断】/【假设】/【旁证】；同任务连续失败 2 次升级角色；子智能体长任务派后台，完成通知自动送达，不要 poll；派活写明"保持未提交、零 git 写操作"（提交官除外）；★终扫四模式=SECRET/PATH/USER+命名纪律人工扫（git show Author 信封行不计命中）；★提交前硬性步骤=重新生成完整 git status 逐一归属；★M2-排期计划.md 纪律=去向表只追加、「内容零删」（行尾注记以整行重写为载体合法，红队 S3-R1 裁决后口径）、编辑前必重新实读工作区实况；提交表体行注意锚点落在表体内（勿插到表体闭合空行之后——550c7c4 修正笔教训）。

【任务卡纪律（用户指令）】① 异厂商贵模型任务卡必须含"子智能体分工条款"（可派自己的 deepseek-v4-flash 子智能体执行机械活，本体只做关键判断/攻击面分析/结论裁决/报告撰写；任务卡结构=关键判断点清单+机械活清单）。② 所有任务卡必须指定报告落盘路径，用户只回路径不贴全文。③ 代码位置以"类名#方法名"为主锚、行号为辅。

【用户偏好与禁忌】界面全中文；EP+Tailwind 能用禁自写；不问技术细节、自主决策留痕；只有异厂商模型或业务签字节点才找用户；签字节点只有人能签；机械活全派子智能体；任务卡用代码块输出；结论让执行模型直接写仓库文档；★issue 政策：关闭即删、删前存档 <LOCAL_WORK>\issue-archive\；用户会挑战结论——有证据就摆证据坚持，用户的机制主张被证伪时逐层钉死（机制/落点/patch 分开评），方向对的部分明确采纳。

【关键报告索引（仓库外）】<LOCAL_WORK>\t35-reports\；<LOCAL_WORK>\t3b-reports\；<LOCAL_WORK>\issue1-reports\；<LOCAL_WORK>\issue2-reports\；<LOCAL_WORK>\issue3-reports\；<LOCAL_WORK>\issue4-reports\；<LOCAL_WORK>\issue5-reports\；<LOCAL_WORK>\issue45-reports\；<LOCAL_WORK>\ci-reports\；<LOCAL_WORK>\t4-reports\；<LOCAL_WORK>\t4-commit-evidence\；<LOCAL_WORK>\issue-archive\（政策 README 含 #1-#5 清单+全存档）；<LOCAL_WORK>\t43b-reports\（T4-3 后半施工/复核/红队/提交官四报告+冗余入库文件排查）；<LOCAL_WORK>\t43b-commit-evidence\；<LOCAL_WORK>\issue6-reports\（分析报告含验证实证+用户质疑复审）；<LOCAL_WORK>\blocking-audit\（frontend/backend-ai/backend-infra 三份阻塞审计）。

【新会话第一件事】1. 派子智能体核验 git 基线：git log --oneline -25（应见 T4-3 后半组 6 笔，HEAD=550c7c4）+ git status（应干净）+ CI run 38017090740 复核 success。2. 与用户确认下一棒选题（建议序：issue#6 处置执行[等确认] / 阻塞审计分诊+S1 施工包排序 / 前端测试框架裁决呈批 / M1 签字筹备）。
```

**下一棒建议**（新会话直接可用）：issue#6 处置你只欠一句"执行"；阻塞审计 83 点待分诊登记+S1×10 排包；前端测试框架裁决因 B-06/B-07 优先级显著上升，建议尽早呈批。
