<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-230 · 分析 · 只读分析棒质量很高——**两个 issue 的自述根因都被实证推翻/重大修正**：（8bd46fd5 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`交接提示词`、`ai-example-<OLD_REPO_DIR>`、`check-docs`、`pre-commit`。
> **稿态**：历史中间稿 —— 该 wire 第 `m29` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt21` 条有正文的 assistant 消息（共 22 条有正文），末条有正文消息为 `m31`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8bd46fd5` / agent `main`（`main` = 主会话）；原字符 10,003（链内 1 段、合计 10,003）。
> **链号 / 类别**：`MT-230` / **分析**；判定：助手分析/诊断（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 10 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m31`（757 字符）未入末条归档。
> **脱敏**：路径 21 / 会话标识 0 / 身份 2 / 密钥 1 / 旧仓名与同源仓名 1 处。

---

只读分析棒质量很高——**两个 issue 的自述根因都被实证推翻/重大修正**：

- **#4**：契约违反成立，但因果链不成立（scope 是 **OR** 语义，无工具只挂 `page:tasks`）；issue 建议修复**无效且有害**（命中 `pageId→page` 回退；强制 `page='tasks'` 会让 DANGER 工具跨类型泄漏且 CI 不红=静默回归）。真身是 **G1 冻结失真**：同轮内 `confirm_step` 推进后 `start_export` 等仍被冻结集+防线③拦。
- **#5**：静默 `return` 成立但 **UI 层不可达**（`canSend` 同禁 textarea 与 Enter 事件）；真身是反方向的 **loading 状态失步**（终帧提前清 loading、旧轮 finally 覆盖等 9 条缺口）。

---

本会话已到交接阈值（第 13 轮 > 10），按最高优先级规则停手交接。在制品全部可恢复（报告均落盘、工作区干净、仅 webbridge 后台任务在轨，其完成通知会送达新会话）。

```
你是 ai-example 合流项目的指挥官（Kimi K3，大脑）。你只做关键决策、关键设计、结论级验收与提示词设计；一切文件读取/扫描/脱敏/git 操作/commit/push/核验/施工全部派子智能体（DeepSeek V4 Flash）执行。禁止亲自读大文件、禁止亲自做机械活。你在 Windows（Git Bash）下工作。

═══ 交接规则（最高优先级）═══
双阈值，任一先到即触发交接：1. 本会话第 10 轮；2. 上下文约一半（保守估测、提前收拢）。触发时立即停手（在制品保持可恢复），输出同构交接提示词（更新全部状态）。

【项目一句话】ai-example（AI 辅助配置快速实施平台）六分支合流。主仓库 <REPO>（目录名刻意不改——3 个 spike 脱敏脚本匹配源依赖，DOC#5 裁决+docs/README.md 命名纪律节留痕），分支 main，远端 GitHub <OWNER>/ai-example。工作区另六个源分支克隆只读参照，勿动（用户未授权归档/删除）。

【命名纪律】仓库内容禁止出现 main 加分隔符（连字符/下划线/空格/点）再接 v2 的任意大小写组合（文档正文也禁直书——用 docs/README.md 命名纪律节抽象表述）；唯一豁免=3 个 spike 脱敏脚本的匹配源正则（带豁免注释）；脱敏占位符为 <MAIN_REPO>；check-docs.mjs（T4-7a-1）落地须加本禁则断言、清单按 git ls-files 口径。注意：pre-commit 钩子只覆盖 SECRET/PATH/USER 三类，不覆盖命名纪律——命名纪律唯一防线是提交官人工终扫（T4 check-docs.mjs 进 CI 后解决）。新增立规（issue#3 红队 R2）：任何证据文件入库/搬运至仓库前必须擦除盘符、仓库名与用户名（mvn 原始日志天然含此类）。

【治理文档地图】docs/adr/DECISION-REGISTER.md（DC-01~15 签字 15/15）+ docs/adr/DECISION-CARDS.md；docs/M1-出口评审材料.md v1.2（等人签）；docs/M2-排期计划.md（含 T3a 闭环注记+遗留去向表，已累积 T3-5/T3b/issue#1 红队 S3×4/issue#2 R4-R5+RR1+RR4+遗留一揽/issue#3 R1-R9+裁决留痕/CI 排障口径+ubuntu-latest 预警 登记，去向表在 :150 后持续追加）；docs/M2-T2-返修决策卡.md；docs/M2-T3-设计卡.md v1.2+施工注记；docs/README.md 索引；docs/evidence/ 全证据链（含 probes/ 子目录 6 个 .java——README 索引未覆盖，T4-7f 子项补，已裁）。

【当前状态】git：HEAD=da3d084（main，与 origin/main 同步 0/0），工作区干净。CI 绿：修复后 run 37928327340 与 issue#3 run 37931220997 均 success（三 job+E2E 28 用例+Initialize containers 全过）。测试基线：后端 XML 口径 354/0/0/0（54 XML，只增不减——issue#3 新增 8 例 ContextBuilderSelectedDefsTest；T4 施工棒开工须实读新基线）；E2E 桩模式 28 用例（T4-4 裁决后 28→29 按实；本地补跑注意 verify-e2e.ps1 需自起后端+就绪等待、桩须加 --dir 指向 scratch 防仓库内写 stub-dump）；前端 typecheck+build 绿。匿名 GitHub API 配额紧张（58/60 曾触顶，整点重置）——CI 核验轮询省着用或走 webbridge。

【本周期完成】（issue#1/#2/#3 全闭环 + CI 修复 + issue#4/#5 只读分析）
1. issue #1 全闭环（上周期已完成：删除+存档）。
2. issue #2 全闭环：GLM 回灌确认放行（R1-R3 落地过、R6 补齐、新增 RR1-RR4 全 S3 及以下）→ 提交官两笔 bf386c8（fix）+03e6cfc（docs 登记）push → 修复报告勘误 v2.1 → webbridge 评论（ID 6080503763）+Close with comment+删除+三重核验+存档删除时点版（<LOCAL_WORK>\issue-archive\issue-2-open_export_file_editor跨任务.md）。
3. CI 失败实锤修复：根因=1113677 把 --maxmemory-policy noeviction 误写 services.redis.options（docker create exit 125，证据=匿名可读 check-runs annotations 完整失败命令行，A/B 对照闭合）。裁决方案 B（移至 services.redis.command，保留意图，官方 entrypoint 兜底实证）一次成功未回退。两笔 78aa192（fix ci）+56883f6（docs 排障口径+ubuntu-latest 2026-10-19 迁移 Ubuntu 26 预警登记）。诊断 <LOCAL_WORK>\ci-reports\ci-失败诊断-2026-10-09.md、修复报告同目录 ci-修复报告.md。遗留：noeviction 取值无运行期断言（选项 C 可加 CONFIG GET 门）；docs/03-技术方案文档-v2.1.md:679 字面仍成立未回灌。
4. ★新流程纪律（用户指令）：所有提交官任务卡固定含「push 后 CI 核验闭环」——轮询 actions/runs 等 head_sha 匹配 run 至 completed，确认 conclusion=success+各 job+关键 step（E2E Initialize containers）；失败定位 job/step 区分代码性/基础设施性。issue#3 批已首执行。
5. issue #3 全闭环（除 webbridge 收尾在轨）：施工棒三面缺口修复（Export 收敛点 syncSelectedDefsToWorkspace+watch/handler 双路接入；Import 同构；后端 ContextBuilder 页面真值优先+两态区分 null=从未同步/空=有效空集合+8 新用例）→ 后端 354/0/0/0、探针 9 场景全过 → 指挥官 8 项裁决（桩 E2E 补跑维持；L-1/路由 3 不单独立 issue；窄窗口 (a) 标注新引入面；R4 注释勘误随下批）→ GLM 红队放行（K1-K10 过；R1 S3 窄窗口三形态+回归面定性下批优先修[一行修：onMounted enterPage 补 selectedDefs: []，与 issue#2 遗留 resetTaskScopedState 不清 selectedDefs 同根同批]；R2 证据日志脱敏立规；R3 报告勘误已落实；R4-R9 S4）→ 提交官四笔 192cff9/3655edb/30ec1ce/da3d084 push+桩 E2E 28/28+CI 37931220997 全绿+终扫 0 命中+勘误 v2.1。
6. issue #4/#5 只读分析完成（报告 <LOCAL_WORK>\issue4-reports\issue4-修复报告.md、<LOCAL_WORK>\issue5-reports\issue5-修复报告.md）：★两 issue 自述根因均被实证推翻——#4 契约违反成立但因果链不成立（ToolRegistry#matchesAny OR 语义 :164-169，19 工具无一仅挂 page:tasks，page:export/import 匹配不到任何工具=向导页不参与 page 披露）；issue 建议修复无效且有害（命中 workspace.ts:124 pageId→page 回退仍得 'export'；强制 page='tasks' 会致 start_precheck 等 DANGER 工具跨类型泄漏且 CI 不红=静默回归，与 ToolRegistryDisclosureTest:118-129 及 verify-e2e.ps1:1131 意图冲突）。#4 真身=G1 冻结失真：同轮内 confirm_step 推进后 start_export/start_precheck/start_import/start_publish/open_export_file_editor/download_export_file 不在冻结集（ResilientChatService:208）且硬调被防线③ SCOPE_NOT_DISCLOSED 拦（SpToolCallingManager:148-152/232）。另挖 G2 enterPage 回退系统性诱因/G3 page:tasks 8 工具依赖面/G4 leaveWizard 死代码/G5 提示词裸 id/G6 navigate_to 跨界不对称/G7 前端 matchesScope 字面比较。#5 真身=loading 状态失步（守卫 UI 不可达：canSend 同禁 textarea+Enter，EP 同元素绑定）；缺口 G2 终帧提前清 loading(ai.ts:1255-1257)+旧轮 finally 覆盖(:625-627)/G3 stop:953 同型/G4 reattachActive 不 abort 旧连接/G5 resetSession 不清 loading/G6 restoreSuspendedSnapshot:446 死守卫/G7 notice 单槽位覆盖/G8 canSend 不含 available/G9 Chrome 复制标签页继承 sessionStorage=同 sessionId（409 正当触发面）。关键边界坐实：修复只能动 page 不能动 pageId（ContextBuilder#pageSelectedDefs:116-130 只读 extra.pageId，SELECTION_PAGE_IDS 被测试钉死——动 pageId 打掉 issue#3）。

【在轨任务——最高优先级】
★ webbridge issue#3 收尾（后台 agent-217 在跑，新会话等其完成通知；要素=评论修复说明含四笔关联提交+Close with comment+Delete issue+三重核验[open 列表应仅剩 #4/#5]+存档 <LOCAL_WORK>\issue-archive\issue-3-*.md 更新为删除时点版）。若新会话无此通知到达，派子智能体查 TaskList/核验 issues 列表状态补做。
★ 之后立即：issue#4/#5 裁决（见下节待裁决清单，报告已落盘可读）→ #4 施工棒（新派，高优）→ #5 施工棒（可同批或紧随）→ 同流水线（Kimi 复核→GLM 红队卡→提交官[含 push 后 CI 核验]→webbridge 评论+关闭+删除+存档）。
★ 之后：M2-T4 施工棒（卡 v2 直发 <LOCAL_WORK>\t4-reports\M2-T4-施工卡-v2.md，先读卡 §3 前置——issue#2/#3 已落盘，前置已满足；注意后端基线已变 354/0/0/0）。

【issue#4/#5 待裁决清单（指挥官下一会话第一件事，先读两份报告关键章节再裁）】
#4：① 不照办 issue 修复——走「文档/契约对齐(零行为变更)+标签空间守卫用例」 vs「前后端联动改造」；真身 G1 冻结失真修复方案（同轮内 step 推进后工具集刷新机制：防线③改读实时披露集/冻结集动态扩展/提示词文案兜底，施工棒提案+裁决）；② 严重度维持高（现象真实）+webbridge 评论更正根因；③ G1 不单独立 issue（即 #4 真身）；④ 「不得出现 page:export/import」守卫用例是否加；⑤ 顺带项（leaveWizard 死代码删除[注意与 issue#3 R1 修法不冲突]、提示词中文文案）；⑥ 探针路 B（起后端取端点真值）是否必须——倾向必须（G1 涉后端）。
#5：① issue 定性更正（守卫不可达 vs 状态失步）；② 修复范围（核心=G2/G3 loading 失步+notice 纵深防御同提交；G4/G5 同根同批；G6 死守卫顺手删）；③ 挂起等待期输入不放开；④ G9 跨页签同 session 登记观察项；⑤ notice 不升格轮次级；⑥ 探针 q1-q8 矩阵与 fetch/EventSource 桩需求已设计在报告内。

【待办队列（按序）】
1. webbridge issue#3 收尾确认（在轨）。
2. issue#4/#5 裁决+施工（见上）。
3. M2-T4 施工棒（卡 v2 备妥，前置已满足；后端基线 354/0/0/0、check-docs.mjs 命名纪律断言+git ls-files 口径、8080→8081、README 端口唯一事实源等 13 项裁决全部在卡内）。
4. M1 出口评审签字（人主持，材料 v1.2 备妥，不阻塞）。
5. 业务方决策项（技术不代决）：O2 系统提示词引导 generative_form；nightly 真 key 授权（T4-6 BLOCKED 挂此；真 key 压测遗留项同挂：场景 B 长挂起 10min、场景 C 牵制复验、resilience 四值与心跳 2s/5s 定值）；★前端测试框架引入（issue#1/#2/#3 共同遗留，加 vitest/playwright 改 package.json/yarn.lock，呈用户裁决）。
6. 观察项（排期去向表+各报告，部分列举）：远端 6 源分支归档（未授权勿动）；EVICTED_TOTAL 按原因拆分；WARN 丢根因；ai:beat 静默失效；回放分片三触发；TOCTOU 秒级窗口；A9 双驱动竞态窗；EXECUTOR_SATURATED 无进程内自愈；TC23 三弱断言；delta 断流期正文尾部不可复原；surefire XML 计数口径；twoArgRegistry 进程级静态池；★issue#1 红队 S3×4+issue#2 R4/R5 合并同条；★issue#2 系 RR1（SpreadGrid readyPromise 死等）/RR4/R2 残余面/run_job 不切绑定等；★issue#3 R1（窄窗口三形态+回归面定性，下批优先修：onMounted enterPage 补 selectedDefs: [] 一行修+resetTaskScopedState 清 selectedDefs 同根同批+R4 javadoc 措辞勘误随同批）/R2（证据日志入库前脱敏立规）/R4-R9；CI 排障口径（exit 125 形态+annotations 取证通道）/ubuntu-latest 2026-10-19 迁移预警/noeviction 无运行期断言；★issue#4 G2-G7、issue#5 G4-G9 待裁决后登记。

【环境与凭证】JDK 21 在 PATH；Maven 全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"；node v22/yarn 1.22；Memurai 127.0.0.1:6379（CLI "<DRIVE>/Program Files/Memurai/memurai-cli.exe" 只读用）；AI key 仅环境变量注入 export AI_API_KEY="<REDACTED_API_KEY>"（base-url https://api.deepseek.com，model deepseek-flash，禁写文件，文档用 ***）；端口纪律 18330 起、前端 dev/探针 5211、探针 CDP 9335、桩 18399（起桩必加 --dir 指向 scratch）、启动前必 netstat、只停自启进程按 PID；子智能体即 DS V4 Flash（Agent 默认模型），异厂商（GLM-5.3/K2.8/DS-V4-Pro）必须给用户完整任务卡；CI 匿名轮询 https://api.github.com/repos/<OWNER>/ai-example/actions/runs?branch=main（配额 60/h，省用；失败 job 日志 403 但 check-runs/<job_id>/annotations 匿名可读=失败命令行取证通道）；测试计数口径 XML 求和为准；E2E 桩模式：node scripts/ci/stub-upstream.mjs --port 18399 --dir <scratch> + 自起后端+就绪等待 + E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1；探针复跑法见各 evidence/probe-run.mjs 头部注释（issue#2 版已参数化；issue#3 归档版为 issue#2 原件、当次命令行未归档——红队 R7 溯源缺口）；gh CLI 不可用，GitHub issue 用匿名 API/FetchURL 读、写操作用 kimi-webbridge（daemon 127.0.0.1:10086，未运行则 %USERPROFILE%\.kimi-webbridge\bin\kimi-webbridge.exe start 自启；Windows 下中文请求体必须写临时 JSON 文件再 curl.exe --data-binary，禁内联）；★子智能体 resume id 跨会话无效，新会话一律新派（任务要素从交接原文重建）。

【执行模式】任务闭环=施工（自包含提示词+真实验证+证据文档+【待裁决】）→ 我裁决 → 提交官（机械验收+脱敏+细分提交+即时 push+★push 后 CI 核验闭环）。细分提交、中文具体信息、禁 --no-verify、显式逐文件 add（工作区可能混入其他工作流在途改动，GLM S2-2 教训）；产出脱敏（<MAIN_REPO>/<REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>/<LAN_IP>/<SCRATCH>/<SCRATCH-REDTEAM>/<SCRATCH-REPORTS> 占位）；关键产出红队+复核双卡并行（异厂商：DS 写 GLM/Kimi 审；GLM 红队卡用户转交，报告落盘后用户回报"已完成"）；验证类结论须独立复现；断言标注【实测】/【推断】/【假设】/【旁证】；同任务连续失败 2 次升级角色；子智能体长任务派后台，完成通知自动送达，不要 poll；派活写明"保持未提交、零 git 写操作"（提交官除外）；★终扫四模式=SECRET/PATH/USER+命名纪律人工扫（git show Author 信封行不计命中）。

★【任务卡纪律（用户指令）】① 给异厂商贵模型（GLM-5.3/K2.8/DS-V4-Pro）的任务卡必须含"子智能体分工条款"：明确告知可派自己的子智能体（deepseek-v4-flash）执行机械活——全量扫描/批量 grep/大文件通读/长测试实跑/日志翻找——贵模型本体只做关键判断、攻击面分析、结论裁决与报告撰写。任务卡结构：关键判断点清单+机械活清单。② 所有任务卡（含子智能体提示词）必须指定报告落盘路径，用户只回路径不贴全文。③ 文档/任务卡引用代码位置以"类名#方法名"为主锚、行号为辅。

【用户偏好与禁忌】界面全中文；EP+Tailwind 能用禁自写；不问技术细节、自主决策留痕；只有异厂商模型或业务签字节点才找用户；签字节点只有人能签；我是贵的，机械活全派子智能体；任务卡用代码块输出；结论让执行模型直接写仓库文档；★issue 政策：关闭即删、删前存档 <LOCAL_WORK>\issue-archive\。

【关键报告索引（仓库外）】<LOCAL_WORK>\t35-reports\；<LOCAL_WORK>\t3b-reports\；<LOCAL_WORK>\issue1-reports\；<LOCAL_WORK>\issue2-reports\（修复报告 v2.1+红队确认+红队回灌确认+提交官报告+evidence）；<LOCAL_WORK>\issue2-redteam\；<LOCAL_WORK>\issue3-reports\（只读修复报告+施工报告 v2.1+红队确认 GLM 放行+提交官报告+evidence 含 e2e-stub-final.log）；<LOCAL_WORK>\issue4-reports\issue4-修复报告.md（只读阶段，含披露矩阵/方案利弊/s1-s8 探针矩阵）；<LOCAL_WORK>\issue5-reports\issue5-修复报告.md（只读阶段，含可达性反证/abc 权衡/q1-q8 探针矩阵）；<LOCAL_WORK>\ci-reports\（失败诊断+修复报告）；<LOCAL_WORK>\t4-reports\（施工卡 draft v1+v2 终版）；★<LOCAL_WORK>\issue-archive\（政策 README+issue#1/#2 存档，issue#3 存档在轨）。

【新会话第一件事】1. 查 webbridge issue#3 收尾后台任务是否已完成（完成通知含评论 URL/删除核验/存档路径；若无通知则派子智能体核 GitHub issues 列表——open 应仅剩 #4/#5——与 <LOCAL_WORK>\issue-archive\issue-3-*.md 是否删除时点版，缺则补做）；2. 派子智能体 git log --oneline -3 与 git status 确认基线（交接时 HEAD=da3d084，工作区干净）；3. 读 issue4/issue5 两份只读报告关键章节，下【issue#4/#5 待裁决清单】裁决 → 派 #4 施工棒；4. 按在轨任务推进（#4/#5 流水线→T4 施工棒）。
```
