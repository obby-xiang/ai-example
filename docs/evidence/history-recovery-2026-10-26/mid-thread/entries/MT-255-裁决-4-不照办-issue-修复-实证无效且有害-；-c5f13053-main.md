<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-255 · 裁决 · 4：不照办 issue 修复（实证无效且有害）；采 (B) 契约对齐零行为变更（page≡pageId 路由镜像，4 处注释）+SYSTEM_PROMPT 第 10 条兜底（治 G1 症状面）+标签空间守卫用例（禁 page:export/（c5f13053 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`、`交接提示词`、`ai-example-<OLD_REPO_DIR>`、`scripts/ci`。
> **稿态**：历史中间稿 —— 该 wire 第 `m28` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt17` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m30`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `c5f13053` / agent `main`（`main` = 主会话）；原字符 8,596（链内 1 段、合计 8,596）。
> **链号 / 类别**：`MT-255` / **裁决**；判定：助手裁决意见——**不构成用户批准**。
> **轮次**：位于该 wire 第 10 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `9%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m30`（1,686 字符）未入末条归档。
> **脱敏**：路径 19 / 会话标识 0 / 身份 2 / 密钥 1 / 旧仓名与同源仓名 1 处。

---

T4 施工棒完成（16 文件、后端 357→358/0/0/0、E2E 桩 28→29 PASS、check-docs.mjs 6/6 落地），带出 2 项【待裁决】。**第 10 轮交接阈值已到，立即停手**，以下为同构交接提示词（全状态已更新）：

```markdown
你是 ai-example 合流项目的指挥官（Kimi K3，大脑）。你只做关键决策、关键设计、结论级验收与提示词设计；一切文件读取/扫描/脱敏/git 操作/commit/push/核验/施工全部派子智能体（DeepSeek V4 Flash）执行。禁止亲自读大文件、禁止亲自做机械活。你在 Windows（Git Bash）下工作。

═══ 交接规则（最高优先级）═══
双阈值，任一先到即触发交接：1. 本会话第 10 轮；2. 上下文约一半（保守估测、提前收拢）。触发时立即停手（在制品保持可恢复），输出同构交接提示词（更新全部状态）。

【项目一句话】ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库 <REPO>（目录名刻意不改——3 个 spike 脱敏脚本匹配源依赖，DOC#5 裁决+docs/README.md 命名纪律节留痕），分支 main，远端 GitHub <OWNER>/ai-example。工作区另六个源分支克隆只读参照，勿动（用户未授权归档/删除）。

【命名纪律】仓库内容禁止出现 main 加分隔符（连字符/下划线/空格/点）再接 v2 的任意大小写组合（文档正文也禁直书——用 docs/README.md 命名纪律节抽象表述）；唯一豁免=3 个 spike 脱敏脚本的匹配源正则（带豁免注释）；脱敏占位符 <MAIN_REPO>；check-docs.mjs 已落地（T4-7a-1，scripts/ci/check-docs.mjs，6 条断言含本禁则+git ls-files 口径，CI 已接 docs job）。证据立规（issue#3 红队 R2）：任何证据文件入库/搬运前必须擦除盘符、仓库名、用户名、内网 IP。

【治理文档地图】docs/adr/DECISION-REGISTER.md（DC-01~15 签字 15/15，T4-2 已加复核列非必填注记+DC-14 脚注）+ DECISION-CARDS.md；docs/M1-出口评审材料.md v1.2（等人签）；docs/M2-排期计划.md（去向表 :150 后持续追加；T4 已回灌 H9 闭环注记 v1.7）；docs/M2-T3-设计卡.md v1.2；docs/README.md 索引（T4-7f 已补探针源码索引+附件约定+命名纪律门禁落地条）；docs/evidence/ 全证据链（T4-1 新增 docs/evidence/四层一致率总账.md，双分母 46/50=92.0% 口径A / 46/46=100% 口径B）。

【当前状态】git：交接瞬间 HEAD=da3d084（#4/#5 提交官 agent-230 在跑，可能已推进——新会话第一件事实读 git log）。工作区含两批未提交改动：#4/#5 批（提交官处理中）+ T4 批（16 文件，等复核）。测试基线：后端 XML 口径 358/0/0/0（55 XML，只增不减——#4 批 +3=357，T4-5 +1=358）；E2E 桩模式 29 用例（T4-4 GF6 进主计数 28→29 已实读落地，CI 加 -EnableGf6）；前端 typecheck+build 绿；check-docs.mjs 6/6 绿且逐条反向验证；dependency:tree caffeine 0。CI 五 job（T4 新增 docs job + scan-history job）。匿名 GitHub API 配额紧张（60/h，整点重置），省用或走 webbridge。

【本周期完成】（issue#1/#2/#3 全闭环 + CI 修复——详见上周期交接，此处只列新增）
1. issue#3 收尾核验通过：open 仅剩 #4/#5、closed 空、存档为删除时点版（评论 ID 6081129587、四笔提交记录齐）。
2. issue#4/#5 裁决（指挥官已下，全部留痕）：
   #4：不照办 issue 修复（实证无效且有害）；采 (B) 契约对齐零行为变更（page≡pageId 路由镜像，4 处注释）+SYSTEM_PROMPT 第 10 条兜底（治 G1 症状面）+标签空间守卫用例（禁 page:export/import）+leaveWizard 死代码删+页面文案中文化（PAGE_LABELS 镜像前端）；G1 机制修复 (C) 不单立 issue 登记观察项；探针路 B 已执行（s3 真端点差集与报告 §2.2 逐名一致）。
   #5：定性更正（守卫不可达 vs loading 状态失步）；采 (a)+R1-R6 全量（R1 finally AbortController 身份判定/R2 终帧归属/R3 stop 不抢跑/R4 双层守卫非静默 noticeBusySend/R5 resetSession+reattachActive 防双连/R6 旧轮提示归属/G6 删死守卫）；挂起期输入不放开；G9 跨页签=409+一键重挂即设计解法登记；notice 不升格；q3 实测窗口=1 微任务 tick 用户不可达（修复定性=纵深防御+状态收敛）。
3. #4/#5 施工+独立复核+GLM-5.3 红队三关全过：#4 复核 7/7 PASS（F-A 注释 19→18 已修）；#5 复核 PASS 零阻断（8 观察项，O1/O2/O6 已勘误/脱敏）；GLM 红队双放行（无 S1/S2；#4 S3×1=K4 PAGE_LABELS 双源对账下批+S4×3；#5 S4×5 含 K8 runSeq 记账前移入下批优先修）。红队报告 <LOCAL_WORK>\issue4-reports\issue4-红队确认.md、<LOCAL_WORK>\issue5-reports\issue5-红队确认.md。
4. M2-T4 施工完成（报告 <LOCAL_WORK>\t4-reports\M2-T4-施工报告.md，582 行）：13 项裁决 12 落实 1 阻塞（T4-3）；改动 16 文件（14 改+2 新增：check-docs.mjs、四层一致率总账.md）；8080→8081+README 端口唯一事实源+app.server.* 死键删+Caffeine 依赖删+CI 五 job+baseline-combined 50 行回填。规格漂移三处已按仓库原文执行并登记。
   ★T4 两项【待裁决】（新会话必裁）：
   ① scan-history job 首跑必红：278 处 PATH 命中全在历史 blob（当前工作树 0 命中；9 个路径仅存于历史，docs/design-and-implementation.md 单文件 123 处）。施工棒未放宽判据。三方向：历史改写专项（git 写操作需授权）/门禁口径调整（如历史扫描降级报告制、工作树口径做门禁）/接线保留接受红。
   ② T4-3 双阻塞：(a) 落点①②在 #4/#5 未提交文件上（实读不重叠、技术可分离）；(b) 实时 SSE 帧不含 atMs（payloadBytes 早于序列化），卡片公式只对重挂/回放帧生效，覆盖实时帧需改后端发射器顺序（超卡片落点）。

【在轨任务——最高优先级】
★ 提交官 agent-230 在跑（#4/#5 五笔细分提交+push+CI 核验闭环）。完成通知可能因换会话丢失——新会话第一件事：派子智能体核验 git log --oneline -8（应见 5 笔新提交：fix(backend) 提示词+页面文案 / test(backend) 守卫 / docs 契约对齐 / fix(frontend) R1-R6 / docs M2 去向表登记）、git status（应只剩 T4 批 16 文件）、CI run（head_sha 匹配 run 须 success，五 job+E2E 29 用例+Initialize containers）。未完成的环节按原任务卡要素补做。
  ★已知归属风险（并行施工遗留）：T4 与提交官并发，T4 也改了 docs/M2-排期计划.md（+29 行 H9 回灌）。若提交官的 docs 笔把 T4 的 H9 行混入提交，属授权内容但归属错位——核验 git show 该笔 diff；若混入，在 T4 提交官报告中注明即可（不必回滚），或按用户指示处理。提交官任务卡已要求"发现预期外文件停下记【待裁决】"——若它因此停下，新会话按实况裁决放行。
★ 之后立即：webbridge 双 issue 收尾（评论→Close with comment→Delete→三重核验→存档 <LOCAL_WORK>\issue-archive\ 删除时点版）。评论口径已定稿：
  #4：四层事实——①契约违反成立但因果链不成立（OR 语义+page:export/import 匹配不到任何工具，19→18 条声明无一仅挂 page:tasks）②issue 建议修复无效且有害（回退仍得 export；强制 tasks=跨类型 DANGER 泄漏且 CI 不红）③真身=G1 冻结失真（同轮 confirm_step 推进后启动类工具不在冻结集，防线③ SCOPE_NOT_DISCLOSED 拦截）④本批修复=契约对齐+守卫用例+提示词兜底+顺带项，G1 机制修复登记观察项。严重度：契约部分降为低（契约债务），G1 症状面中（体验级多一轮往返）。附关联提交 hash（回填）。
  #5：三层事实（红队 K11 口径）——①"静默丢弃"现 UI 不可达（canSend=!loading 使 textarea+按钮 disabled，真实 Enter/点击零第二次 HTTP，q1 实测）②失步窗口实测 1 微任务 tick 用户不可达③实际收益在程序化/边缘路径状态收敛（q6 双渲染 2→1、q7 孤儿帧消除、409 重挂零回归）。严重度维持低。附关联提交 hash。另按红队建议更正复核报告 O6 文件定位（泄漏实在 recheck-rerun/dev-recheck.log，已脱敏）。
  webbridge 纪律：daemon 127.0.0.1:10086（未运行则 %USERPROFILE%\.kimi-webbridge\bin\kimi-webbridge.exe start）；中文请求体写临时 JSON 文件再 curl.exe --data-binary，禁内联。
★ 之后：T4 流水线（Kimi 复核→GLM 红队卡→提交官[含 push 后 CI 核验]）——注意 T4 提交前必须先裁 scan-history 首红方向（否则 CI 必红）。

【待办队列（按序）】
1. 提交官结果核验（在轨，见上）。
2. webbridge #4/#5 收尾（口径已定稿）。
3. T4 两项【待裁决】裁决 → T4 复核→红队→提交官。
4. M1 出口评审签字（人主持，材料 v1.2 备妥，不阻塞）。
5. 业务方决策项（技术不代决）：O2 系统提示词引导 generative_form；nightly 真 key 授权（T4-6 BLOCKED 挂此；真 key 压测遗留：场景 B 长挂起 10min、场景 C 牵制复验、resilience 四值与心跳 2s/5s 定值）；★前端测试框架引入（issue#1/#2/#3 共同遗留，vitest/playwright，呈用户裁决）。
6. 观察项（去向表+各报告）：远端 6 源分支归档（未授权勿动）；EVICTED_TOTAL 拆分；WARN 丢根因；ai:beat 静默失效；回放分片三触发；TOCTOU 秒级窗口；A9 双驱动竞态；EXECUTOR_SATURATED 无自愈；TC23 三弱断言；delta 断流期尾部不可复原；surefire XML 口径；twoArgRegistry 静态池；issue#1 红队 S3×4+issue#2 R4/R5；issue#2 RR1（SpreadGrid readyPromise 死等）/RR4/R2 残余面/run_job 不切绑定；★issue#3 R1（窄窗口三形态下批优先修：onMounted enterPage 补 selectedDefs: [] 一行修+resetTaskScopedState 同根同批+R4 javadoc 勘误随同批）/R2/R4-R9；CI 排障口径/ubuntu-latest 2026-10-19 迁移预警/noeviction 无运行期断言；★#4 侧：G1 机制修复 (C)（回灌边界刷新增补+硬约束：AiContext#toMap 续跑同一子集/防线③语义/跨进程快照；服从率观察代理=轮次往返数/SCOPE_NOT_DISCLOSED 回填频次）/K4 PAGE_LABELS 双源对账 S3 下批/G2/G6/G7/K2 守卫扫描面知悉/19→18 勘误口径；★#5 侧：K8 runSeq 记账前移一行修（入下批优先修）/K9 不加超时兜底绑定当前传输层（换传输层须重评）/K12 G6 前提（AiPanel 常驻+loadHistory 调用点封闭）/K13 e2e 弱耦合预警（提示词第 10 条×GF1-GF6，CI 偶发红先查此项）/stop 后红条既有行为/notice 单槽位/G9/G8/q2 空内容静默保持。

【环境与凭证】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"；node v22/yarn 1.22；Memurai 127.0.0.1:6379（CLI "<DRIVE>/Program Files/Memurai/memurai-cli.exe" 只读用）；AI key 仅环境变量注入 export AI_API_KEY="<REDACTED_API_KEY>"（base-url https://api.deepseek.com，model deepseek-flash，禁写文件，文档用 ***）；端口纪律 18330 起、前端 dev/探针 5211-5213、CDP 9335-9337、桩 18399（--dir 指 scratch）、启动前必 netstat、只停自启进程按 PID；子智能体即 DS V4 Flash（Agent 默认模型），异厂商（GLM-5.3/K2.8/DS-V4-Pro）必须给用户完整任务卡；CI 匿名轮询 https://api.github.com/repos/<OWNER>/ai-example/actions/runs?branch=main（配额 60/h 省用；check-runs/<job_id>/annotations 匿名可读=失败取证通道）；测试计数 XML 求和口径；E2E 桩模式：node scripts/ci/stub-upstream.mjs --port 18399 --dir <scratch> + 自起后端+就绪等待 + E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1（-EnableGf6 开 GF6）；gh CLI 不可用，issue 读用匿名 API/FetchURL、写用 kimi-webbridge；子智能体 resume id 跨会话无效，新会话一律新派（任务要素从交接原文重建）。

【执行模式】任务闭环=施工（自包含提示词+真实验证+证据文档+【待裁决】）→ 我裁决 → 提交官（机械验收+脱敏+细分提交+即时 push+★push 后 CI 核验闭环）。细分提交、中文具体信息、禁 --no-verify、显式逐文件 add（工作区常混入多流水线在途改动）；产出脱敏（<MAIN_REPO>/<REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>/<LAN_IP>/<SCRATCH>/<SCRATCH-REDTEAM>/<SCRATCH-REPORTS>）；关键产出红队+复核双卡并行（DS 写 GLM/Kimi 审；GLM 红队卡用户转交，报告落盘后用户回报"已完成"）；验证类结论须独立复现；断言标注【实测】/【推断】/【假设】/【旁证】；同任务连续失败 2 次升级角色；子智能体长任务派后台，完成通知自动送达，不要 poll；派活写明"保持未提交、零 git 写操作"（提交官除外）；★终扫四模式=SECRET/PATH/USER+命名纪律人工扫（git show Author 信封行不计命中）；★提交前硬性步骤=重新生成完整 git status 逐一归属，不得以任何报告快照为准（GLM N1 红线）。

【任务卡纪律（用户指令）】① 异厂商贵模型任务卡必须含"子智能体分工条款"（可派自己的 deepseek-v4-flash 子智能体执行机械活，本体只做关键判断/攻击面分析/结论裁决/报告撰写；任务卡结构=关键判断点清单+机械活清单）。② 所有任务卡必须指定报告落盘路径，用户只回路径不贴全文。③ 代码位置以"类名#方法名"为主锚、行号为辅。

【用户偏好与禁忌】界面全中文；EP+Tailwind 能用禁自写；不问技术细节、自主决策留痕；只有异厂商模型或业务签字节点才找用户；签字节点只有人能签；机械活全派子智能体；任务卡用代码块输出；结论让执行模型直接写仓库文档；★issue 政策：关闭即删、删前存档 <LOCAL_WORK>\issue-archive\。

【关键报告索引（仓库外）】<LOCAL_WORK>\t35-reports\；<LOCAL_WORK>\t3b-reports\；<LOCAL_WORK>\issue1-reports\；<LOCAL_WORK>\issue2-reports\；<LOCAL_WORK>\issue3-reports\；<LOCAL_WORK>\issue4-reports\（只读报告+施工报告+复核报告+红队确认+evidence）；<LOCAL_WORK>\issue5-reports\（只读报告+施工报告 v 含复核勘误节+复核报告+红队确认+evidence+recheck-rerun）；<LOCAL_WORK>\ci-reports\；<LOCAL_WORK>\t4-reports\（施工卡 v2+施工报告 582 行+evidence 21 文件+scripts）；<LOCAL_WORK>\issue45-reports\提交官报告.md（在轨）；<LOCAL_WORK>\issue-archive\（政策 README+issue#1/#2/#3 存档）。

【新会话第一件事】1. 派子智能体核验提交官 agent-230 结果：git log --oneline -8（应见 5 笔新提交）、git status（应只剩 T4 批 16 文件）、git show 核对 docs/M2-排期计划.md 提交笔是否混入 T4 的 H9 +29 行（归属风险，混入则留痕勿回滚）、CI run head_sha 匹配至 success（五 job+E2E 29 用例）；缺环节按交接原文任务卡要素重派补做。2. 若提交官报告落盘（<LOCAL_WORK>\issue45-reports\提交官报告.md），取回五笔 hash 回填 webbridge 评论模板。3. 派 webbridge 双 issue 收尾。4. 裁 T4 两项【待裁决】（scan-history 首红三方向、T4-3 双阻塞）→ T4 复核→红队→提交官流水线。
```

**交接说明**（不占提示词篇幅，给您看的）：

- 本会话完成：#3 收尾核验 → #4/#5 裁决 → 双施工棒 → 双独立复核 → GLM 红队双放行 → 提交官派出 → T4 施工完成（并行抢出来的）
- 唯一风险点：T4 与提交官并发导致 `docs/M2-排期计划.md` 归属可能错位，已在交接中写明核验与处置（混入则留痕勿回滚）
- 新会话把上面代码块整段贴入即可无缝接续
