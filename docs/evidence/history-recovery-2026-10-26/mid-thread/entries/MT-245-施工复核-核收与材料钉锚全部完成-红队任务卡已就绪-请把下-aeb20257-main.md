<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-245 · 施工复核 · 核收与材料钉锚全部完成，红队任务卡已就绪。请把下面整段代码块转交 GLM：（aeb20257 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`TaskController.java`、`scripts/ci`。
> **稿态**：历史中间稿 —— 该 wire 第 `m3` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 35 条有正文），末条有正文消息为 `m46`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `aeb20257` / agent `main`（`main` = 主会话）；原字符 3,765（链内 1 段、合计 3,765）。
> **链号 / 类别**：`MT-245` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m46`（7,508 字符）未入末条归档。
> **脱敏**：路径 8 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

核收与材料钉锚全部完成，红队任务卡已就绪。请把下面整段代码块转交 GLM：

```
你是 ai-example 合流项目的红队评审官（GLM）。你的职责是对抗性评审：目标是找出问题，不是确认通过。工作平台 Windows + Git Bash。你可以派出子智能体（默认模型）执行文件读取/命令实跑等机械活，但结论与判断由你负责；派活提示词必须显式写明「后台独立运行、完成自动送达、无需值守」或前台等待，且必须指定报告落盘路径。

═══ 零、防夹带锚点核对（第一步必做）═══
评审材料包在 <LOCAL_WORK>\redteam-ui-pack\：
- bundle.patch（评审对象全量 diff，1110 行 / 117679 字节）
- bundle.sha256、status-snapshot.txt、diff-tracked.patch、diff-untracked.patch、file-manifest.txt
先实跑 `sha256sum -c bundle.sha256`（在 <LOCAL_WORK>\redteam-ui-pack\ 下），必须输出 `bundle.patch: OK`。锚点值应为：
4fc39de7a5faf3eb92ba19ef865568ee8dcd96b97ef13a0304f2a08f338c81af
不一致=材料被夹带/污染，立即停止评审并在报告中以 BLOCKER 上报，不得继续。
注意：bundle.patch 为纯 LF；不要用任何会改写行尾的工具转写它。若你用 patch(1) 类工具施工，改用无注释的 diff-tracked.patch + 单独处理两个新文件。

═══ 一、项目背景（最小必要）═══
仓库：<REPO>（分支 main，HEAD=d3020ee，本周期零提交）。这是 AI 辅助配置快速实施平台（Spring Boot 后端 + Vue3+EP+Tailwind 前端，前端端口 5202，后端 8081）。当前工作树有 18 项未提交改动（16 个已跟踪修改 + 2 个新增文档），分四批：

批次①「UI 施工包」：用户反馈驱动的界面改造——
- frontend/src/App.vue：删顶部「导出向导/导入向导」两个菜单项（菜单 5 项→3 项），因任务中心创建任务即进向导，单点菜单进去显示「未绑定任务」属多余入口。
- frontend/src/views/TaskCenterView.vue（466→357 行）：点任务标题=打开任务；删「打开」按钮；整体拆除任务详情侧边抽屉。
- frontend/src/stores/task.ts、frontend/src/api/tasks.ts：配合手术。
- backend TaskController.java：删一句 javadoc「供详情抽屉使用」（纯注释）。
- 7 处文档注记（docs/M2-排期计划.md、docs/evidence/S44a、S44b、docs/test-cases.md、docs/verification-results.md、docs/requirements.md 等）：记录上述改造事实，口径=只追加+内容零删。

批次②「修补包」：删 3 个孤儿导出（frontend/src/utils/format.ts 的 JOB_TYPE_LABELS、SEVERITY_LABELS，frontend/src/stores/task.ts 的 jobStatusLabel——删前已反证全仓 0 调用点）；docs/requirements.md :53/:55/:58 三处注记回灌。

批次③「命名修单」：命名纪律=仓库内容禁止出现 main 加分隔符或零分隔连写再接 v2 的任意大小写组合（唯一豁免=3 个 spike 脱敏脚本的匹配源正则，带豁免注释）。
- docs/evidence/S44b-五页视图验证.md :20：`<OLD_REPO_DIR>-db-backup` → `<MAIN_REPO>-db-backup`。
- scripts/ci/check-docs.mjs（+87/−14）：断言⑤从双态扩为三态（NFKC 规范化+零宽/空格变体+去分隔符连写检测），加词首守卫防误伤，加规则自检（13 正向种子必须全命中 / 11 负对照必须全不误伤）。

批次④「调研沉淀」：两份业界调研脱敏版新增入库 docs/research/（步骤条交互、对话时序混排），docs/README.md 索引 +1 行。这两份文档是为后续「步骤条节点可点击查看」「对话界面按时序混排」两个终案立的业界依据，本批次只评审文档本身质量与合规，不评审终案设计。

═══ 二、评审要求 ═══
1. 对抗性立场：默认这批改动有问题，逐批找茬。每批至少尝试 3 个攻击面（如：删菜单后是否残留死路由/死代码？抽屉拆除是否有遗留状态/监听泄漏？删孤儿导出是否真的 0 调用——自己用 grep 反证，不许采信报告自述？check-docs.mjs 三态正则是否可绕过或误伤——自己构造变异样本实测？断言⑤自检的种子/对照是否可被掏空而通过？新增调研文档是否有盘符/用户名/内网 IP/仓库名泄漏或命名纪律违禁？）。
2. 独立复现：关键结论必须自己实跑验证，禁止只读 diff 下结论。允许在主仓库工作树态实跑（只读+构建类命令）：前端 `yarn typecheck`、`yarn build`（frontend/ 目录，node v22 + yarn 1.22）；`node scripts/ci/check-docs.mjs`（期望 6/6 绿）。禁止任何 git 写操作（add/commit/stash/checkout/reset 一律禁止），禁止修改仓库内任何文件；如需构造变异样本测试正则，一律写在 <LOCAL_WORK>\redteam-ui-pack\scratch\ 下。
3. 主锚定位：所有发现必须给 文件:行号 主锚（如 TaskCenterView.vue:???、check-docs.mjs:???），引用代码片段。报告里的行号必须是工作树现态行号（file-manifest.txt 有各文件行数）。
4. 命名纪律自查：对 18 个文件全文检查是否引入新的「main+v2」形态（含零分隔连写、任意大小写、零宽字符变体）与未脱敏盘符/用户名/内网 IP；3 个 spike 脱敏脚本豁免段不在范围。
5. 输出分级：BLOCKER（必须修，否则禁止提交）/ MAJOR（应修，可裁决）/ MINOR（建议修）/ 观察项（不阻塞，登记）。每条=一件具体的事+证据+攻击过程+修复建议。没有找到问题的批次也要写明攻击过程与为何未破防，不许只写「PASS」。
6. 背景报告可参照（但结论不许采信，只许当线索）：<LOCAL_WORK>\ui-title-open\{施工报告,复核报告,修补报告,命名修补报告}.md、<LOCAL_WORK>\ux-research\沉淀报告.md。

═══ 三、报告落盘 ═══
报告写 <LOCAL_WORK>\redteam-ui-pack\红队报告.md。结构：锚点核对结果 → 分批次攻击过程与发现（含 文件:行号）→ 分级清单（BLOCKER/MAJOR/MINOR/观察项）→ 实跑验证记录（命令+退出码+关键输出）→ 结论（是否放行提交）。所有断言标注【实测】/【推断】/【假设】/【旁证】。报告与 scratch 内禁止出现盘符路径、用户名、内网 IP 以外的隐私——本机路径 <LOCAL_WORK> 与仓库目录名允许出现（红队报告在仓库外，不走入库脱敏口径）。

评审范围仅限上述 18 项改动及其直接牵连面；发现牵连面之外的问题登记为观察项即可，不展开。
```

---

给你（用户）的说明，大白话版：

- **基线核对通过**：HEAD 还是 `d3020ee`，本周期零提交；工作树 18 项改动全部对上号（施工 12+修补 3+命名 2+调研 3 含 README 索引行，有重叠已标注），没有来历不明的文件。
- **四份在途报告全部落盘且经独立复测**：issue#6 确认已删除（GitHub 返回 410，仓库 issue 总数为 0）；命名修单的 check-docs.mjs 实跑 6/6 绿、哈希与报告钉死值一致；两份调研文档字节数与 MD5 与沉淀报告逐值相同。
- **红队材料已钉死**：`<LOCAL_WORK>\redteam-ui-pack\bundle.patch`（1110 行）+ SHA-256 锚点 `4fc39de7…c81af`，GLM 开工第一步必须核对哈希，防夹带。
- 三处口径小事已登记（README 索引行归属④、报告内 mtime 快照过期、闭环登记改按 18 项口径），不阻塞，提交官批次顺带处理即可。

你转交任务卡给 GLM，红队报告回来后我裁决分级清单，然后放提交官。
