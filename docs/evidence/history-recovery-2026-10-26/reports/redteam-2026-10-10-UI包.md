# 历史补档：UI 施工包 / 修补包 / 命名修单 / 调研沉淀 红队评审 + R2 修补

- **归档来源（已脱敏）**：2 件仓外原件，全部来自 `<LOCAL_WORK>/redteam-ui-pack/`，源件仅仓外、未移动、未改写；分件见下表
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-10 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话**：正文未出现会话 id，来源会话见索引 `README.md` 的来源映射表（模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：439 行 / 50,944 B

| 分件 | 原件（仓外别名） | 源行 | 源字节 | 主题 |
|---:|---|---:|---:|---|
| 1 | `<LOCAL_WORK>/redteam-ui-pack/红队报告.md` | 183 | 23,212 | 红队评审报告（18 项未提交改动；MAJOR/MINOR 分级） |
| 2 | `<LOCAL_WORK>/redteam-ui-pack/修补报告-r2.md` | 256 | 27,732 | 修补报告 R2（4 处修补 + 1 处立项登记 + 门禁复跑 + 锚点重钉） |

---

**〔分件 1／2〕** 原件：`<LOCAL_WORK>/redteam-ui-pack/红队报告.md`

# 红队评审报告 —— UI 施工包 / 修补包 / 命名修单 / 调研沉淀（18 项未提交改动）

- 评审官：GLM（红队 · 对抗性评审）
- 评审日期：2026-10-10
- 评审对象：<REPO_ROOT> 工作树 18 项未提交改动（分支 main，HEAD=d3020ee，本周期零提交）
- 材料包：<LOCAL_WORK>\redteam-ui-pack\bundle.patch（1110 行 / 117679 字节）及配套件
- 立场声明：本报告以找错为目标。所有关键结论均经独立实跑复现，未采信任何背景报告自述。每条断言标注【实测】（本机命令复现）/【推断】（由实测证据逻辑推出）/【假设】（未经复现的推测）/【旁证】（背景材料线索，未独立验证）。

---

## 一、锚点核对结果

```
$ cd <LOCAL_WORK>/redteam-ui-pack && sha256sum -c bundle.sha256
bundle.patch: OK
$ sha256sum bundle.patch
4fc39de7a5faf3eb92ba19ef865568ee8dcd96b97ef13a0304f2a08f338c81af *bundle.patch
```

- 实算值与锚点值 `4fc39de7…81af` 完全一致【实测】。材料无夹带/污染，评审继续。
- 附带核对：仓库 HEAD=`d3020ee2d245c3c38a803c320f0ff90248d5d270`、`git status --porcelain` 恰 18 项、与 status-snapshot.txt 逐项一致【实测】。bundle.patch 全程未做任何行尾转写（Read 工具直读 + sha256 复核）。

---

## 二、分批次攻击过程与发现

### 批次①「UI 施工包」（菜单删两项 + 标题即入口 + 拆抽屉）

**攻击面 1：删菜单是否残留死路由/死代码？**

- 路由表核查：`frontend/src/router/index.ts:20-53` 五条路由全部保留，向导路由仅失去菜单入口、仍由 `restoreWizard`（TaskCenterView.vue:268-271）、`export_started`/`import_batch`（stores/workspace.ts:412-419）、兜底重定向（router/index.ts:52）正常触达【实测】。死路由：**无**。
- App.vue 现态（71 行）仅删两个 `el-menu-item`（App.vue:13-15 现存 3 项），无残留引用【实测】。
- **但发现回归面**：`activeRoute`（App.vue:62）在向导页激活时（`export-wizard`/`import-wizard`）不再匹配任何 `el-menu-item` 的 index → 顶部菜单**无任何高亮项**。改造前「导出向导/导入向导」菜单项高亮承载了「你在哪」的定位反馈，改造后向导页处于「菜单全灰」状态，且该 UX 回归未在 7 处文档注记中登记【实测（代码路径推演）+ 旁证（S44a/S44b 注记只记菜单数量变化，未记高亮行为）】。→ 登记 **MINOR-3**。

**攻击面 2：抽屉拆除是否有遗留状态/监听泄漏？**

- 监听泄漏：抽屉是纯模板态（`v-model="detailVisible"`），无自有 timer/listener；TaskCenterView 的 `onBeforeUnmount` 正确停自刷（TaskCenterView.vue:354-356）【实测】。**无泄漏**。
- 遗留状态：**破防**。抽屉拆了，但它的整个数据供给链没拆，形成一整族「只写不读」死码：
  - `currentTaskId` 的**唯一非空写点**是被删除的 `openTask()`（HEAD 版 stores/task.ts:205）。工作树现态全仓 grep：`currentTaskId` 仅剩声明（task.ts:77）、`refreshCurrent` 的读取（task.ts:180）、`closeTask` 写 null（task.ts:202）、`removeTask` 的比较（task.ts:225）——**没有任何地方再写入非空值**【实测】。
  - 后果链（逐环实测）：`refreshCurrent()`（task.ts:179-199）因 `currentTaskId === null` 恒早退 → `getTaskOverview`（api/tasks.ts:20）成为死调用 → `currentTask/currentJobs/currentFiles/issues/issuesTotal/detailLoading/publishConflict`（task.ts:78-88）全部只写不读（视图层 grep 零消费【实测】）→ `loadIssues` 写入的 `issues` 仅被 `watchPublishOutcome` 内部消费后写入 `publishConflict`，而 `publishConflict` 的唯一渲染点正是被拆除的抽屉（HEAD 版 TaskCenterView.vue:148-153，`git grep` 实证）。
  - **功能回归实锤**：AI 侧经 `ui_event run_job` 发起发布（stores/workspace.ts:398-405）→ `watchPublishOutcome` 组装 `publishConflict`（task.ts:294-321）→ **无人渲染**。改前该冲突在任务中心抽屉以 el-alert 呈现（发布恒 201、冲突以作业 FAILED + issues 异步呈现，Q4 口径，store 头注释 task.ts:11 明文「必须等终态再查 issues」）；改后 AI 发起的发布失败时用户看不到任何冲突明细。用户自己从向导页点发布不受影响（ImportWizardView.vue:856-865 本地 `buildPublishConflict` + :182-188 本地渲染，链路完整【实测】）。→ **MAJOR-1**。
  - 佐证注释失实：api/tasks.ts:19 新注释称「（refreshCurrent 读取当前任务快照用）」——`refreshCurrent` 本身已永久 no-op，注释描述的是一条死链【实测】。
  - 界定：`startJob`/`setImportMode` 两个死 action 系 HEAD 既有（`git grep HEAD` 零调用【实测】），非本批引入；但本批是唯一一次把它们连带清理的合理时机。→ 归并 MAJOR-1。

**攻击面 3：点标题=打开任务是否等价替换？**

- 旧 `openDetail`（删前）= 开抽屉 + `taskStore.closeTask()` + `openTask(id)`；新 `restoreWizard`（TaskCenterView.vue:268-271）= 按类型跳向导页 `?taskId=` 带入。向导侧 `watch(route.query.taskId)`（ImportWizardView.vue:1029-1041）接住绑定并 `loadTask()`，`loadTask` 内 `resumeJobs()`（ImportWizardView.vue:418）恢复作业态，`attachJob` 对 `publish FAILED` 重建冲突卡（ImportWizardView.vue:477-479）【实测】。「详情四块」（步骤/条目/作业/文件）中，作业进度与校验问题在向导页有承接，但**历史作业全量列表与文件清单**在向导页无逐条等价视图【推断，基于两向导视图通读：向导按当前 kind 各挂一卡，不渲染全量作业史】——与 requirements.md:55 注记「任务详情改由向导页承载」的口径大体自洽（需求面收缩已声明），登记**观察项-1**。
- `restoreWizard(row)` 用 `row.task.id ?? 0`：旧 `openDetail` 有 `undefined` 早退防护，新代码把 `undefined` 静默换成 `0`。实测推演：`?taskId=0` 在向导页 `Number(0) > 0` 判 false → 不绑定 → 落到「尚未绑定任务」安全降级【推断，代码路径推演，未起浏览器】。登记**观察项-2**。

**攻击面 4：后端纯注释改动。**
- TaskController.java:51 javadoc 删「供详情抽屉使用」六字，+1/-1，无行为变化【实测（bundle.patch:32-40 逐行核对 + 工作树 sed 抽查）】。未破防。

**攻击面 5：文档注记口径（只追加/内容零删）。**
- 7 处注记逐条核对（工作树行号）：M2-排期计划.md:282（R-90 行尾追加「另注」）、S44a:192、S44b:269+329、requirements.md:53/55/58、test-cases.md:212（TM-08）、verification-results.md:150（U-02）+158（TM-09）——均为行尾追加「｜**注记…**」，原表述保留【实测（Read 逐条比对 bundle.patch hunk）】。
- 内容抽查两处硬事实：① requirements.md:53 注记称列表实无「配置项数」列——与 TaskCenterView.vue:43-102 现列（任务/类型/状态/进度/当前步骤/创建时间/更新时间/操作）一致【实测】；② requirements.md:58 注记称 4s 自刷——与 task.ts:32 `TASK_AUTO_REFRESH_MS = 4000` 一致【实测】。未破防。
- 连带失实：M2-排期计划.md:280 R-88 行内引用 `startAutoRefresh:182-199`，本批删 32 行后现态为 task.ts:157-176，行号漂移 25 行【实测】。R-88 行本批未触碰、且「只追加」纪律禁止回改——纪律与准确性的固有冲突，登记**观察项-3**。

### 批次②「修补包」（删 3 个孤儿导出）

**攻击面：删前是否真 0 调用点（不采信报告自述，自行 grep 反证）**

- `jobStatusLabel`：全 frontend/src 仅 store 自身定义（已删），零引用【实测】。
- `JOB_TYPE_LABELS` / `SEVERITY_LABELS`：全 frontend/src 零引用【实测】；连带 import 清理正确：format.ts:7 不再 import `JobType, Severity`（typecheck 绿佐证）【实测】。
- 删除面精确：format.ts -14 行恰为两个 Record 常量；task.ts -32 行 = `jobStatusLabel` 函数 + `latestJob`/`hasRunningJob` getters + `openTask` action【实测（bundle.patch hunk 逐行核对）】。
- `requirements.md:53/:55/:58` 注记回灌在位（见批次①攻击面 5）【实测】。
- **未破防**。唯一保留意见：批次②以「孤儿导出零调用」为由删了 3 个小导出，同批却留下了体量大得多的 store 死状态族（见 MAJOR-1）——「删孤儿」的纪律标准在本批内执行不一致，此点并入 MAJOR-1 定级理由。

### 批次③「命名修单」（check-docs.mjs 三态正则）

**攻击面 1：三态正则是否可绕过（自构变异样本实测，31 样本）**

- 声称覆盖的形态全部实防：基线连写/连字符、全角 U+FF0D（NFKC 折叠）、零宽 U+200B 藏词内/作分隔、全角数字（NFKC）、词首数字守卫（`1<OLD_REPO_DIR>` 不命中——正确，守卫按设计工作）【实测，scratch/attack-re.mjs】。
- **破防——13 个 Unicode 隐形/同形字符绕过全部漏检**【实测，同一脚本】：
  - 软连字符 U+00AD（`main\u00ADv2`）——**最现实形态**，Word/网页/浏览器复制粘贴常携带；`ZERO_WIDTH_RE` 覆盖 U+200B-200D/U+2060/U+FEFF 却漏了它，且 NFKC 不折叠 U+00AD；
  - 蒙古元音分隔 U+180E、函数应用算符 U+2061、不可见算符 U+2062/U+2063、加号算符 U+2064、抑制态 U+206A；
  - Hangul filler U+3164、半角 Hangul filler U+FFA0；
  - 西里尔同形 а=U+0430（`m\u0430in-v2` 与零分隔 `m\u0430inv2`）、希腊同形 ν=U+03BD（`mai\u03BD-v2`）。
- 定级权衡：断言⑤的威胁模型是防夹带（门禁注释自述防「粘贴破折号」等无意识形态 + 对抗性绕过），零宽双态本身即是对抗性设计产物；U+00AD 与已防的零宽族同属一个威胁族、是邻位遗漏而非新开战场，且 R-92 立规（M2:283 R-92 行）明文把「同形字折叠」列入四态终扫家族——实现与立规口径有差距。→ **MAJOR-2**（修复成本低：`ZERO_WIDTH_RE` 补 U+00AD；同形字族可折叠进归一化或登记为已知上限）。
- 公允记录：门禁自述只列举了全角/U+2010-U+2015/U+2212/零宽/零分隔连写形态，上述漏检不在自述清单内——属「覆盖面存在自述之外的缺口」，非自述失实【实测】。

**攻击面 2：自检是否可被掏空而通过**

- **破防——掏空攻击成立**【实测，scratch/attack-hollow.mjs】：同时（a）把 `hitsName` 第三态删掉、（b）把 13 个种子里 5 个连写种子删掉（剩 8 个分隔符/零宽种子），模拟运行 `selfCheckNamingRule` 判定逻辑：8 个种子全部仍被旧双态命中（0 红）、`seedsNew = 8 ≥ 6`（绿）、`NAME_CONCAT_RE` 常量仍在所以 :351 直查绿（绿）→ **自检全绿，第三态已死，真实违禁文本「部署目标是 <OLD_REPO_DIR_CONCAT> 仓库」放行**。
- 根因：`seedsNew` 只统计「经第三态命中」的种子数，而量词 `*`（零个或多个）使**含分隔符的种子也必然命中 NAME_CONCAT_RE**——阈值 6 在任何「还剩 ≥6 个种子」的配置下都自动满足，13/13 全经第三态命中是数学必然而非覆盖证明【实测（check-docs 实跑输出「经第三态连写规则命中 13 例」即佐证）+ 推断】。
- 威胁模型界定：能改 check-docs.mjs 者本可整段删除断言⑤，自检的真实目标是防「无意改窄」而非恶意掏空——故不升 BLOCKER/MAJOR，定 **MINOR-1**。修复建议：`seedsNew` 改为统计「仅第三态可命中」的种子（构造上分隔符为零的种子不被旧双态命中），或直接断言「每个连写种子逐一被 NAME_CONCAT_RE 单独命中且不被 NAME_RE 命中」。
- 附带：`NAMING_CONTROLS` 数量无下限断言（删负对照不红）——需写权限、且 R-92 立规仅要求「至少 1 个」现 11 个全在，登记**观察项-4**。

**攻击面 3：词首守卫是否误伤/削弱判定面**

- 误伤面实测 13 个负样本（domain 族、`src/main.ts`、re<OLD_REPO_DIR>、mainframe、ultra<OLD_REPO_DIR⟨.⟩>.0 等）：**零误伤**【实测】。
- 全仓命中集对比（守卫 vs 无守卫，git ls-files 913 文件 + 第三态口径）：两者命中集合均为空集，完全一致【实测，scratch/guard-compare.mjs】——门禁注释 check-docs.mjs:291-292「加守卫前后全仓命中集合完全一致」自述属实。未破防。

**攻击面 4：门禁盲区**

- 断言⑤扫描面 = `git ls-files`，**untracked 新文件在 commit 前不在扫描面**【实测（机制审读 task.ts:374 gitLsFiles + 两新文件此刻未被扫描）】。兜底核查：本批 2 个新文件经红队终扫器独立扫描干净（见批次④）；CI `docs` job 在 push 后跑、文件已 tracked、盲区自动闭合【实测（ci.yml:255-267 + pre-commit hook 内容审读：hook 不跑 check-docs）】。登记**观察项-5**，不阻塞。

### 批次④「调研沉淀」（两份脱敏调研入库）

**攻击面 1：隐私/命名纪律泄漏**

- 红队终扫器（四态命名 + 盘符 + 用户名 + 内网 IP + 仓库名，附 6 植入对照全检出、3 负对照零误伤的自检）对 18 文件工作树现态全扫：**唯一命中 = check-docs.mjs:87 注释中的正则示意 `<DRIVE>/`**（盘符字母通配示意、非本机路径，且该文件不在断言①的 NAV_DOCS 扫描面）【实测，scratch/final-scan.mjs】。两份新文档零泄漏：无盘符、无用户名、无内网 IP、无旧仓目录名（含零分隔连写与红队加搜的同形/软连字符盲区形态）【实测】。未破防。

**攻击面 2：「原文全文未改写」保真比对**

- 步骤条文档：原始稿（<LOCAL_WORK>\ux-research\）与入库版占位符归一化后 diff ——**仅头部标题加「（入库脱敏版）」+ 3 行定位说明，正文零差异**【实测】。
- 对话时序文档：同样仅头部差异；正文所有差异均为脱敏替换（`<LOCAL_WORK>\agui-raw\md\` → `<SCRATCH>/agui-raw/md/` 等 6 处，及原始稿里真实旧仓目录名 1 处被替换为空/占位符）【实测】。承诺「内容与结论、来源 URL、证据分级、设计启示全部保留」属实——来源 URL 清单逐条在位【实测】。
- **MINOR-2**：两个新文件均无 EOF 换行（`tail -c 1` = 0x82，file-manifest eof_nl=NO 佐证），与仓内其余 md（全部有 eof newline）不一致；git 将永久显示 `\ No newline at end of file`【实测】。

**攻击面 3：索引与目录合规**

- docs/README.md:24 索引行 +1，指向的两文件真实存在（docs/research/ 下），`<SCRATCH>` 占位符合规【实测】。断言③实测通过（check-docs 6/6 绿内含）。未破防。

---

## 三、分级清单

### BLOCKER（必须修，否则禁止提交）

- **无。**

### MAJOR（应修，可裁决）

1. **MAJOR-1｜批次①：拆抽屉未做配套收尾——store「当前任务族」整链死码 + AI 侧发布冲突失去唯一渲染点**
   - 证据：`frontend/src/stores/task.ts:77-88`（死状态族）、`task.ts:179-199`（`refreshCurrent` 恒早退）、`task.ts:294-321`（`watchPublishOutcome` 写入无人消费的 `publishConflict`）、`frontend/src/api/tasks.ts:19-20`（`getTaskOverview` 死调用 + 注释失实）、`stores/workspace.ts:398-405`（AI `run_job` 发布路径）、HEAD 版 TaskCenterView.vue:148-153（被拆的唯一渲染点，`git grep` 实证）。
   - 攻击过程：批次①攻击面 2（逐环 grep + HEAD 基线对比）。
   - 影响：AI 发起发布且冲突时（恒 201 + 作业 FAILED 异步形态），用户不可见冲突明细（改前在抽屉可见）；`currentTaskId` 永久 null 使 6 处 `refreshCurrent()` 调用全部 no-op。
   - 修复建议（二选一，需裁决）：A. 补渲染点——`workspace.ts run_job` 的 publish 分支把冲突提示落到全局消息/AI 侧回执而非死 store 字段；B. 顺势清死链——删 `currentTask/currentJobs/currentFiles/issues/issuesTotal/detailLoading/publishConflict` 状态族与 `refreshCurrent/loadIssues/watchPublishOutcome/closeTask` 及 `getTaskOverview` 前端封装（后端 overview 端点保留否另裁），AI 发布冲突提示按 A 补。注意批次②已以「孤儿零调用」为由删导出，本批内部标准应一致。

2. **MAJOR-2｜批次③：三态正则存在 13 个 Unicode 隐形/同形字符绕过**
   - 证据：`scripts/ci/check-docs.mjs:301`（`ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g` 漏 U+00AD/U+180E/U+2061-2064/U+206A 等）、NFKC 不折叠西里尔 а/希腊 ν 同形字；13 绕过样本实测输出存 `scratch/attack-re.mjs`。
   - 攻击过程：批次③攻击面 1（31 变异样本实跑，15 绕过中 2 个为红队自身构造错误已剔除，13 个成立）。
   - 影响：`main\u00ADv2`（软连字符，复制粘贴常见）等形态可全绿入库；与 R-92 立规「同形字折叠」口径存在差距。
   - 修复建议：`ZERO_WIDTH_RE` 补 `\u00AD\u180E\u2061-\u2064\u206A\u3164\uFFA0`（删除态+分隔态双态沿用）；同形字折叠（а→a、ν→n）并入归一化，或明文登记为已知上限并在注释收敛自述。

### MINOR（建议修）

1. **MINOR-1｜批次③：自检 `seedsNew` 阈值形同虚设，掏空攻击实测成立**——`check-docs.mjs:340-353`；`seedsNew≥6` 因量词 `*` 使含分隔符种子也命中第三态而成数学必然；实测「删第三态+删 5 连写种子」后自检仍全绿、违禁文本放行（`scratch/attack-hollow.mjs`）。修复建议见批次③攻击面 2。威胁模型内（需门禁写权限）故不升 MAJOR。
2. **MINOR-2｜批次④：两个新文档缺 EOF 换行**——`docs/research/步骤条交互-业界调研.md`、`docs/research/对话时序混排-业界调研.md` 末尾无换行符，与仓内 md 惯例不一致【实测】。
3. **MINOR-3｜批次①：向导页激活时顶部菜单无高亮项**——`App.vue:62` `activeRoute` 不再匹配任何 `el-menu-item` index，向导页「你在哪」的定位反馈丢失且未登记【推断（代码路径推演，未起浏览器实测视觉）】。建议：向导页时高亮「任务中心」或登记该 UX 变化。

### 观察项（不阻塞，登记）

1. **观察项-1**：任务详情「历史作业全量列表 + 文件清单」在向导页无逐条等价视图（向导按 kind 各挂一卡）；requirements.md:55 已声明需求面收缩，口径自洽【推断】。
2. **观察项-2**：`TaskCenterView.vue:269` `row.task.id ?? 0` 把 undefined 静默换成 0（旧 `openDetail` 有早退防护）；降级路径安全（向导显示「尚未绑定任务」）【推断】。
3. **观察项-3**：M2-排期计划.md:280 R-88 行内 `startAutoRefresh:182-199` 因本批删 32 行漂移为现态 157-176；R-88 行本批未触碰、「只追加」纪律禁止回改，纪律与准确性冲突的固有代价【实测】。
4. **观察项-4**：`NAMING_CONTROLS` 数量无下限断言，删负对照不红；现 11 个全在、符合 R-92「至少 1 个」立规【实测】。
5. **观察项-5**：断言⑤按 `git ls-files` 扫描，untracked 新文件在本地 commit 前不在扫描面（pre-commit hook 不跑 check-docs）；CI push 后兜底闭合，本批两新文件经红队终扫干净【实测】。
6. **观察项-6**：`check-docs.mjs:87` 注释含正则示意 `<DRIVE>/`，盘符字母恰与本机盘符重合——非本机路径、不在断言①扫描面，无碍【实测】。

---

## 四、实跑验证记录

| # | 命令（工作目录） | 退出码 | 关键输出 |
|---|---|---|---|
| 1 | `sha256sum -c bundle.sha256`（<LOCAL_WORK>\redteam-ui-pack） | 0 | `bundle.patch: OK`；实算值=锚点值 `4fc39de7…81af` |
| 2 | `yarn typecheck`（frontend/，node v22.23.2 / yarn 1.22.22） | 0 | `vue-tsc --noEmit` 零错误（6s）【实测，scratch/build-report.md】 |
| 3 | `yarn build`（frontend/） | 0 | vite `✓ built in 28.48s`，1747 modules，零告警（34s）【实测，同上】 |
| 4 | `node scripts/ci/check-docs.mjs`（仓库根） | 0 | 6/6 断言绿；⑤扫描 913 文件、自检种子 13 全命中/对照 11 全过 |
| 5 | `node scratch/attack-re.mjs`（31 变异样本） | 0 | 14 符合预期 / **15 绕过**（其中 2 个系红队样本构造错误，有效绕过 13）/ 0 误伤 |
| 6 | `node scratch/attack-hollow.mjs`（掏空模拟） | 0 | **掏空攻击成立**：自检全绿 + 第三态已死 + 违禁文本放行 |
| 7 | `node scratch/guard-compare.mjs`（全仓守卫对比） | 0 | 带守卫/无守卫命中集均为空集，完全一致（913 文件） |
| 8 | `node scratch/final-scan.mjs`（18 文件四态隐私终扫） | 0 | 自检 6 植入对照全检出/3 负对照零误伤；唯一命中 check-docs.mjs:87 `<DRIVE>/` 示意 |
| 9 | `diff`（原始稿 vs 入库版，占位符归一化） | 0/1 | 步骤条：仅头部 +3 行；对话时序：头部 + 脱敏替换 6 处盘符路径 + 1 处旧仓名；正文零删改 |
| 10 | `git rev-parse HEAD` / `git status --porcelain \| wc -l` | 0 | `d3020ee…`，恰 18 项，与 status-snapshot 一致 |
| 11 | `tail -c 1` 两新 md | 0 | 末字节 0x82（无 EOF 换行） |
| 12 | 多轮 `grep -rn`（frontend/src 全量：孤儿导出/currentTaskId/publishConflict/抽屉残留/restoreWizard 消费面） | 0/1 | 见各批次攻击过程；DefinitionsView 的 openDetail/el-drawer 系另一处既有抽屉（界外，未展开） |

- 全程零 git 写操作（add/commit/stash/checkout/reset 均未执行）、仓库内文件零修改；全部变异样本与脚本落在 <LOCAL_WORK>\redteam-ui-pack\scratch\。
- 附：子智能体后台实跑记录（typecheck/build）已落盘 scratch/build-report.md；其结论与红队独立审读一致后方采信。

---

## 五、结论：是否放行提交

**有条件放行：无 BLOCKER；2 项 MAJOR 应修后再合入（或经明确裁决接受后合入），3 项 MINOR 建议随手修，6 项观察项登记不阻塞。**

- **MAJOR-1（拆抽屉配套收尾缺失）**是本批最大的真实缺口：不是「删多了」而是「删了消费者、留下了供给链」。AI 侧发布冲突提示回归 + 一整族死码，与批次②「删孤儿」的口径自相矛盾。建议按修复方案 B（清死链 + 补全局提示）处理后提交；若裁决接受 AI 侧提示缺失，须在 M2 排期登记为已知让步。
- **MAJOR-2（软连字符等 13 形态绕过）**修复成本低（`ZERO_WIDTH_RE` 一行扩展），建议本批内顺手修，避免「门禁刚立杆就有已知洞」。
- 三项验证命令（typecheck / build / check-docs）全绿属实【实测】；两份调研文档保真承诺属实【实测】；18 文件隐私/命名终扫干净【实测】；批次②三孤儿导出零调用属实【实测】。
- 若 MAJOR 两项均裁决接受不修，本批亦可提交（不构成禁止提交级缺陷），但 MAJOR-1 的 AI 发布冲突提示缺失应至少登记入 M2 排期，MAJOR-2 的绕过面应在门禁注释中收敛「无法绕过」的自述口径。

—— 红队评审官 GLM，2026-10-10

**〔分件 2／2〕** 原件：`<LOCAL_WORK>/redteam-ui-pack/修补报告-r2.md`

# 修补报告 R2 —— 红队裁决后的「本批修补包」（4 处修补 + 1 处立项登记 + 门禁复跑 + 锚点重钉）

- 日期：2026-10-10（Asia/Shanghai；锚点生成时间戳见 `status-snapshot.txt`）
- 仓库：`<REPO_ROOT>`（分支 `main`，HEAD `d3020ee2d245c3c38a803c320f0ff90248d5d270`，工作树 18 项未提交改动）
- 本批**只改文件、未提交**：全程未执行 `git add` / `git commit`，未使用 `--no-verify`【实测（本报告落盘前后 `git status --porcelain` 恒为 18 项）】
- 来源裁决：GLM 红队 UI 包评审（`redteam-ui-pack/round1/红队报告.md`）—— MAJOR-1 / MAJOR-2 / MINOR-1 / MINOR-2 / MINOR-3
- 标注口径：【实测】= 本轮命令输出/字节级取证；【推断】= 由代码路径或格式推演；【假设】= 未验证前提；【旁证】= 他人/他轮材料佐证

---

## 一、修补总表

| # | 对应红队项 | 文件 | 状态 | 门禁结果 |
|---|---|---|---|---|
| 1 | MAJOR-2 + MINOR-1 | `scripts/ci/check-docs.mjs` | **已修**（扩隐形字符面 + 自检拆两组严格化 + 已知缺口登记 + 自述口径收敛） | `check-docs` 6/6 绿【实测】 |
| 2 | MINOR-2 | `docs/research/步骤条交互-业界调研.md`、`docs/research/对话时序混排-业界调研.md` | **已修**（各补 1 字节 EOF `\n`） | `tail -c 1` = `0x0a`【实测】 |
| 3 | MINOR-3 | `frontend/src/App.vue` | **已修**（向导路由菜单高亮归算到任务中心） | `typecheck` / `build` 双绿【实测】 |
| 4 | 立项登记（MAJOR-1） | `docs/M2-排期计划.md` | **已登记 R-94**（正式立项，非观察项；只追加 1 行，表体内） | `check-docs` 6/6 绿【实测】 |
| — | 锚点重钉 | `<LOCAL_WORK>\redteam-ui-pack\`（6 件覆盖 + 旧件归档 `round1/`） | **已重钉** | `sha256sum -c bundle.sha256` → OK【实测】 |

---

## 二、逐项前后对照

### 修补 1：`scripts/ci/check-docs.mjs`（MAJOR-2 绕过面 + MINOR-1 自检严格化）

改动 4 处，均为 `git diff` 可核；本批该文件总增删 `+169 / -20`【实测（`git diff --numstat`）】。

**(a) 隐形字符面扩容（对应 MAJOR-2 中"能靠扩字符类解决"的部分）**

| 位置 | 修补前 | 修补后 |
|---|---|---|
| `:301`（前）→ `:321`（后） | `const ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g` | `const INVISIBLE_RE = /[\u00AD\u115F\u1160\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0]/g` |
| 归一函数 `:302-303`（前）→ `:322-323`（后） | `normalizeName/separateName` 均基于 `ZERO_WIDTH_RE` | 同一字符面（`INVISIBLE_RE`）同时供删除态与分隔态使用 |

- 扩容登记（`scripts/ci/check-docs.mjs:317-320` 定义处注释 + `:284-289` 口径注释）：零宽（U+200B-200F）、方向格式（U+202A-202E / U+2066-206F）、不可见算符（U+2061-2064）、弃用格式（U+206A-206F）、填充符（U+115F-1160 / U+3164 / U+FFA0）、变体选择符（U+FE00-FE0F）、BOM（U+FEFF）、软连字符（U+00AD）、蒙古元音分隔（U+180E）。
- **实现要点（踩坑记录）**：归一是「NFKC → 隐形字符」两步，**U+FFA0 会被 NFKC 先折成 U+1160**，故 U+115F/U+1160 必须同时在类内才拦得住（否则半角 Hangul filler 仍可绕过）【实测（`attack-re-r2.mjs`：`<OLD_REPO_DIR_CONCAT>` → hit=true）】。
- **旁及评估（自查"字符面是否散落在其他正则"）**：三态检测里另一处字符类是分隔符类 `NAME_SEP_CLASS`（`\\s\\-_.` + Unicode 连字符族）。隐形字符不并入该类 —— 它们在归一阶段被删除态/分隔态处理，若再并入分隔符类会造成两套口径互相遮蔽；本批未发现第三处涉字符面的正则【实测（逐条通读断言⑤相关正则：`NAME_GUARD` / `NAME_SEP_CLASS` / `INVISIBLE_RE` 三处）】。

**(b) 同形字母缺口登记 + 自述口径收敛（本批不修）**

- 新增缺口登记段：`scripts/ci/check-docs.mjs:301-308`。内容：NFKC 不折叠的同形字母（西里尔小写 a、希腊小写 ν 等）可穿过本断言；本批不修的理由（须全字母表级映射表，漏一个字母即等于没折叠；折叠后与真实英文标识符判定面互污，须连负对照集单独立项）；同族未覆盖的还有"以分隔符类之外字符充当分隔"的形态（如以 `/` 充当分隔）。
- 自述口径收敛（删除"无法绕过"式表述）：
  - 模块头注释 `:13-19`：原“……使全角形态、U+2010-U+2015/U+2212 等"粘贴破折号"、零宽分隔与零分隔连写形态都无法绕过本断言” → 改为“……覆盖全角/兼容折叠形态、……隐形字符分隔与零分隔连写形态；**该覆盖不等于"无法绕过"**：NFKC 不折叠的同形字母（西里尔/希腊等）与本断言注释末尾登记的其他已知缺口仍可穿过”。
  - `--help` 文本 `:510-513`：补“**已知缺口**：NFKC 不折叠的同形字母……详见断言 ⑤ 段注释末尾的缺口登记”。
  - 口径结论句（`:307-308`）："本断言的覆盖口径以本条为准：**覆盖「NFKC 可折叠 + 隐形字符 + 分隔符量化（零个或多个）」三类绕过面，不等于"无法绕过"，也不等于"全部拆形手法"。**"

**(c) 红队被堵死的样本全部收编进正向种子（对应 MAJOR-2 修复后回归）**

- 新增 `NAMING_SEEDS_SEPARATED`（`:347-372` 数组，24 行种子 `:348-371`），其中 **10 例标红队来源**（`:356-363` + `:365-366`：软连字符 U+00AD ×2、U+180E、U+2061/2062/2063/2064、U+206A、U+3164、U+FFA0）+ **6 例同族补样**（`:364` U+206F、`:367` U+115F、`:368` U+200E、`:369` U+202B、`:370` U+2066、`:371` U+FE00）【实测（逐行实读计数 = 10 + 6 = 16，与 8 例原种子合计 24）】。
- 收编口径 = "已被堵死的全部"：红队 13 个**有效**绕过样本里，10 例为隐形字符面（全部收编）、3 例为同形字母（**不收编**，属登记缺口，见 (b)）。

**(d) 自检严格化（MINOR-1：`seedsNew≥6` 形同虚设）**

| 位置 | 修补前 | 修补后 |
|---|---|---|
| 种子表 | 单一 `NAMING_SEEDS`（13 例混装） | 拆两组：`NAMING_SEEDS_CONCAT`（5 例连写，`:340-346`）/ `NAMING_SEEDS_SEPARATED`（24 例分隔，`:347-372`）+ 下限常量 `NAMING_SEEDS_CONCAT_MIN=5` / `NAMING_SEEDS_SEPARATED_MIN=24`（`:338-339`） |
| 自检函数 `:339-358`（前）→ `:387-432`（后） | 统计"经 `NAME_CONCAT_RE` 命中所占例数"，`<6` 才红 | ① 连写组逐例断言 `hitsName` 为真 **且** 前两态（`NAME_RE` 删除态/分隔态）双双不命中 —— 命中必须**唯一**来自第三态；② 分隔组逐例断言命中来自前两态之一；③ 两组按**与数组长度解耦**的常量下限计数（删种子行即红） |
| 输出行 `:393`（前）→ `:467`（后） | `正向种子 13 全命中、其中经第三态连写规则命中 13 例` | `连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤` |

- **"别只做数字游戏"的落实**：下限写成独立常量（不写 `arr.length`），否则删掉种子行后下限会静默跟随、永不报红【实测（变异 M4/M5，见 §五）】。
- 现态自检实测输出（`node scripts/ci/check-docs.mjs`）：`连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤`【实测】

**(e) 实跑**

```
$ node scripts/ci/check-docs.mjs          # exit 0
  [PASS] ①..⑥（6/6）
  全部通过：6/6 断言绿。
```

---

### 修补 2：两份调研文档补 EOF 换行（MINOR-2）

| 文件 | 前（bytes / sha256 前 16 / 末字节） | 后（bytes / sha256 前 16 / 末字节） | 全量 sha256（后） |
|---|---|---|---|
| `docs/research/步骤条交互-业界调研.md` | 25931 / `50be962642ac90f8` / `0x82`（无换行） | 25932 / `c395573dca844add` / `0x0a` | `c395573dca844addecc4b2643ea45c2f1e5fd15b0900b183dc43a52856eda200` |
| `docs/research/对话时序混排-业界调研.md` | 34589 / `47067a823b4d42c8` / `0x82`（无换行） | 34590 / `9f94a6ef7e0147fd` / `0x0a` | `9f94a6ef7e0147fd2e950b0e875b3384d2127dc7d7f96923b1ee6d1e1790f4f1` |

- 手法 = 仅追加 1 字节 `\n`（`printf '\n' >> <file>`），**未动任何既有字节**【实测（bytes 恰 +1；末 2 字节由 `…82` 变 `82 0a`；manifest 行 `eof_nl` 由 `NO` 变 `yes`）】。
- 副作用（正常）：两文件的 `git diff --no-index` hunk 头由 `+173/+261`（末尾无换行时代码行 = lines+1）变为 `+174/+262`（= lines）【实测（`file-manifest.txt` 表头注记已同步说明）】。

---

### 修补 3：向导页菜单高亮（MINOR-3）

`frontend/src/App.vue:62`（修补前，工作树行号）：

```ts
const activeRoute = computed(() => String(route.name ?? ROUTE_NAMES.taskCenter))
```

修补后 `frontend/src/App.vue:62-67`：

```ts
/** 菜单项只有 3 条（任务中心/配置定义/数据浏览）：两向导页无独立菜单项，高亮归算到任务中心。 */
const MENU_FALLBACK_NAMES: string[] = [ROUTE_NAMES.exportWizard, ROUTE_NAMES.importWizard]
const activeRoute = computed(() => {
  const name = String(route.name ?? ROUTE_NAMES.taskCenter)
  return MENU_FALLBACK_NAMES.includes(name) ? ROUTE_NAMES.taskCenter : name
})
```

- 先实读确认命名：菜单 3 项 = `ROUTE_NAMES.taskCenter / definitions / dataBrowser`（`App.vue:13-15`）；路由表 5 条具名路由含 `exportWizard='export-wizard'` / `importWizard='import-wizard'`（`frontend/src/router/index.ts:10-16,28-39`）【实测】。
- 语义：向导页（`/export`、`/import`）激活时 `activeRoute` 归算为 `task-center`，顶部菜单不再"全灰"；其余三页仍按路由名精确高亮（`definitions` / `data-browser` 命中各自菜单项）【推断（`el-menu :default-active` 按 index 精确匹配的 Element Plus 既有语义）】。
- 双绿：`yarn typecheck` → exit 0（`Done in 4.36s`）；`yarn build` → exit 0（`✓ built in 27.13s`，`Done in 32.16s`）【实测，日志留档 `scratch/r2-typecheck.log` / `scratch/r2-build.log`】。

---

### 立项登记：`docs/M2-排期计划.md` 去向表追加 R-94

- 现态最大 R 号实读 = **R-93**（`:285`），追加新行 = **R-94**，落在表体内（`:286`，其后 `:287` 空行、`:288` 为 `## M2-T4 闭环注记` 标题）【实测】。
- 纪律合规：`git diff --numstat` = `+2 / -1`，其中 `-1/+1` 为本批之前既有的 R-90 行尾注记重写（**非本轮改动**），本轮贡献恰为 `+1` 行【实测】；R-83~R-93 各行内容零删、行号未动。
- 行内要点（中文，两格）：
  - 问题格：**AI 侧发布冲突提示失去唯一渲染点**（唯一渲染点 = 被拆除的任务详情抽屉，HEAD 版 `TaskCenterView.vue:148-153`）+ **store「当前任务族」整链死码**（`currentTaskId` 无非空写点 → `refreshCurrent` 恒早退空转 → `getTaskOverview` 死调用且 `api/tasks.ts:19` 注释失实 → `currentTask/currentJobs/currentFiles/issues/issuesTotal/detailLoading/publishConflict` 只写不读）。
  - 去向格：**正式立项（下批执行）** = ① 死码族清除（含 HEAD 既有死 action `startJob`/`setImportMode`）+ ② 发布冲突提示落点重建（**落点设计待定**，候选 = `workspace.ts run_job` publish 分支落全局消息或 AI 侧回执，非死 store 字段）；来源 = GLM 红队 UI 包评审 MAJOR-1；**级别 = 正式立项，非观察项**。
- **题名口径（如实登记一处不符）**：裁决转述的"既有观察项「refreshCurrent 空转族彻底解耦」"在本表既有行内**未命中** —— `refreshCurrent` 在本文件零出现（`grep -n refreshCurrent docs/M2-排期计划.md` 零命中）【实测】。本行已①注明"被吸收进本立项"的同族既有面 = R-89② （`watchPublishOutcome`/`publishConflict` 消费面无着落，S2 观察级）+ R-26/R-88 的 4s 无条件自刷族（`refreshCurrent` 的调用方面）+ R-13（发布悬置静默吞没，随本立项复核），②声明**本行即该题名的登记载体与唯一准据**。→ 若裁决方持有该题名的原始出处（本执行子智能体在仓库 `docs/` 与红队材料内均未找到），请以原始出处覆盖本口径【假设：该题名可能来自未被落盘的裁决转述】。

---

## 三、门禁三连（复跑结果）

| 项 | 命令（cwd） | 退出码 | 关键输出 |
|---|---|---|---|
| 文档门禁 | `node scripts/ci/check-docs.mjs`（仓库根） | **0** | `全部通过：6/6 断言绿。`；自检行见 §二(1)(d) |
| 类型检查 | `yarn typecheck`（`frontend/`，node v22.23.2 / yarn 1.22.22） | **0** | `$ vue-tsc --noEmit` / `Done in 4.36s.` |
| 构建 | `yarn build`（`frontend/`） | **0** | `✓ built in 27.13s` / `Done in 32.16s.`（产出写入 `frontend/dist`，该目录被 gitignore，未进 `git status`）【实测】 |

---

## 四、攻击样本复跑对照表（MAJOR-2 堵死面）

**方法学说明（重要）**：红队 round1 的 `scratch/attack-re.mjs` 把规则字面量**手工复刻在脚本内**（其第 2 行注释："正则逐字复刻自 check-docs.mjs:293-305"）。改门禁后**原样重跑它不反映现态**（实测仍报 15 个绕过）【实测】。故本轮新增 `scratch/attack-re-r2.mjs`：**从门禁源码活取规则块**（`scratch/gate-block.mjs` 按 `const NAME_GUARD` … `NAME_CONCAT_RE.test(normalizeName(text))` 切片 + `new Function` 求值），样本表逐字沿用 round1 的 28 例，杜绝样本↔现态漂移。

| # | 样本（round1 判定） | round1 | round2（现态门禁） | 归类 |
|---|---|---|---|---|
| 1 | 基线 `<OLD_REPO_DIR>` | 命中 | ✅ 命中 | — |
| 2 | 基线 `<OLD_REPO_DIR_CONCAT>` | 命中 | ✅ 命中 | — |
| 3 | 全角连字符 `<OLD_REPO_DIR>`（NFKC） | 命中 | ✅ 命中 | — |
| 4 | 零宽藏词内 `<OLD_REPO_DIR_CONCAT>` | 命中 | ✅ 命中 | — |
| 5 | 软连字符 U+00AD `<OLD_REPO_DIR_CONCAT>` | **绕过** | ✅ **已堵死** | 扩字符类 |
| 6 | 蒙古元音分隔 U+180E | **绕过** | ✅ **已堵死** | 扩字符类 |
| 7 | 不可见算符 U+2061 | **绕过** | ✅ **已堵死** | 扩字符类 |
| 8 | 不可见算符 U+2062 | **绕过** | ✅ **已堵死** | 扩字符类 |
| 9 | 不可见分隔 U+2063 | **绕过** | ✅ **已堵死** | 扩字符类 |
| 10 | 加号算符 U+2064 | **绕过** | ✅ **已堵死** | 扩字符类 |
| 11 | 弃用格式 U+206A | **绕过** | ✅ **已堵死** | 扩字符类 |
| 12 | U+00AD + 连字符 `<OLD_REPO_DIR>` | **绕过** | ✅ **已堵死** | 扩字符类（删除态归一后落 `NAME_RE`） |
| 13 | Hangul filler U+3164 | **绕过** | ✅ **已堵死** | 扩字符类 |
| 14 | 半角 Hangul filler U+FFA0 | **绕过** | ✅ **已堵死** | 扩字符类（NFKC→U+1160 链路，见 §二(1)(a) 踩坑） |
| 15 | 西里尔同形 `mаin-v2` | **绕过** | ❌ 仍绕过 | **同形字母（本批立项不修，已登记缺口）** |
| 16 | 西里尔同形零分隔 `mаinv2` | **绕过** | ❌ 仍绕过 | 同上 |
| 17 | 希腊同形 `maiν-v2` | **绕过** | ❌ 仍绕过 | 同上 |
| 18 | 全角数字 `main-２` | **绕过** | ❌ 仍"未命中" | **round1 样本构造/期望不符（非绕过）**，见下 |
| 19 | 全角数字零分隔 `main２` | **绕过** | ❌ 仍"未命中" | 同上 |
| 20-28 | 误伤面 9 例（do<OLD_REPO_DIR⟨␠⟩> / do<OLD_REPO_DIR> / do<OLD_REPO_DIR_CONCAT> / URL / `src/main.ts v2` / re<OLD_REPO_DIR> / mainframe v2 / ultra<OLD_REPO_DIR⟨.⟩>.0 / 词首守卫 `1<OLD_REPO_DIR>`） | 全不误伤 | ✅ 全不误伤（词尾守卫 `<OLD_REPO_DIR>1` 仍命中 = 期望） | — |

**复跑判定（`node scratch/attack-re-r2.mjs`，exit 0）**：`24 符合预期 / 5 个绕过成功 / 0 个误伤`【实测】。

- **13 个有效绕过 → 堵死 10 / 剩余 3**：剩余 3 例全部是同形字母（西里尔 а、希腊 ν），**按本批范围界定不修**，已在门禁注释内如实登记为已知缺口，并同步收敛门禁自述口径（不再暗示"无法绕过"）【实测】。
- **另 2 例（全角数字）说明**：round1 样本字面量是 `main-２` / `main２`（**缺字母 v**），NFKC 后为 `main-2` / `main2`，**本就不属于禁词形态**，故"未命中"是正确行为，round1 的 `expect=true` 属样本构造错误（红队报告亦自述"15 绕过中 2 个为红队自身构造错误已剔除"【旁证】）。按真实禁词形态补测（同一脚本第四节）：`<OLD_REPO_DIR>` → 命中、`<OLD_REPO_DIR_CONCAT>` → 命中，两例**修补前即已命中**（NFKC 折叠链路原本就覆盖全角数字，与本次扩字符类无关）【实测】。

---

## 五、自检严格化验证（MINOR-1：掏空攻击前后对照 + 6 例变异植入对照）

### 5.1 掏空攻击：前后对照

| 轮次 | 手法 | 结果 |
|---|---|---|
| round1（红队，模拟） | `scratch/attack-hollow.mjs`：自复刻 hitsName 去掉第三态 + 自复制种子表删 5 个连写种子，按旧判定逻辑（`seedsNew < 6` 才红） | `seedsNew = 8 → 绿`；违禁文本`<OLD_REPO_DIR_CONCAT>`放行 → **掏空攻击成立**【实测（重跑 round1 脚本，输出"结论：掏空攻击成立"）】 |
| round2（本轮，**真实门禁**） | `scratch/mutate-gate.mjs` M1：对真实 `scripts/ci/check-docs.mjs` 删第三态 + 清空 `NAMING_SEEDS_CONCAT` | **⑤ FAIL，exit 1**：`连写组 0/0 例「仅第三态可捕获」（下限 5）` → 「连写组"仅第三态可捕获"的种子不足（0 < 下限 5；种子行被删或被改宽）」【实测】 |

### 5.2 变异植入对照（6 例，全部对真实门禁文件实施，跑完逐例 SHA-256 校验复原）

| 变异 | 期望 | 实测 | 报红证据 |
|---|---|---|---|
| M0 无操作变异（反向对照：证明跑测链路活着） | 绿 | ✅ 绿（exit 0） | ⑤ PASS |
| M1 删第三态 + 清空连写种子组（= 掏空攻击真实版） | 红 | ✅ 红（exit 1） | 连写组下限 0 < 5 |
| M2 只删第三态（种子表不动） | 红 | ✅ 红（exit 1） | 连写组种子未被捕获 ×5（逐例点名） |
| M3 隐形字符面退回旧类（只留 U+200B-200D/U+2060/U+FEFF） | 红 | ✅ 红（exit 1） | 分隔组 8/24 < 下限 24，点名红队 11 例样本 |
| M4 删 1 个连写种子行 | 红 | ✅ 红（exit 1） | 连写组 4 < 下限 5 |
| M5 删 1 个分隔种子行（U+FFA0 例） | 红 | ✅ 红（exit 1） | 分隔组 23 < 下限 24 |

- 复原校验：`门禁源 SHA-256（变异前）= 门禁源 SHA-256（恢复后）= d2db39603358316dd445b33ec09b036792f632eec147ab5fb43af88f8c5b2f3e`（逐字节一致）【实测】；该值亦等于 `file-manifest.txt` 第 16 行的现态 sha256 → 锚点所钉内容 = 当前内容【实测】。
- 日志留档：`scratch/r2-mutate-gate.log`。

---

## 六、终扫结果（四模式 + 植入对照）

脚本：`scratch/final-scan-r2.mjs`（相对 round1 的 `final-scan.mjs` 的三点改进：命名规则**活取门禁源码**、文件清单**实拉 `git status --porcelain`**、每条子模式各配正向/负对照）。

- **扫描器自检：植入对照 13 条全检出、负对照 7 条零误伤 → PASS**，未过则中止（防"假武装裸扫描"，对应 R-92 立规）【实测】
  - 命名面植入 5：`<OLD_REPO_DIR>` / `<OLD_REPO_DIR_CONCAT>`（零分隔连写）/ 软连字符 U+00AD / 半角 Hangul filler U+FFA0 / U+2061 —— 均为 DC-08-R2 新覆盖面的代表种子。
  - 盘符面植入 5：`<LOCAL_WORK>\...` / `<DRIVE>/Users/...` / 示意串 `（<DRIVE>/…）`（应落 `drive-schematic` 单列类）/ 示意串**旁边紧跟真路径**（真路径仍须被抓 —— 证明"示意串豁免"不是豁口）。
  - 用户名 / 内网 IP 植入 3：`<DRIVE>/Users\<本机用户名>\...` / `192.168.1.23` / `10.0.0.5` / `172.20.3.4`。
  - 负对照 7：`<MAIN_REPO>` 占位符、`https://example.com/do<OLD_REPO_DIR_CONCAT>`、`src/main.ts`、`127.0.0.1:5173`（回环口径豁免）、`/usr/local/bin/node`（MSYS 非盘符段）、`<DRIVE>/xxx/placeholder/redacted.txt`（占位符豁免）、普通中文数字串。
- **扫描面 = 18 项**（16 项已跟踪改动 + 2 项未跟踪新文件，实拉，非硬编码）【实测】
- **结果：四模式实盘真命中 0 行；盘符"示意串" 1 行（单列登记）；同形字行 0 行**【实测】
  - 唯一 1 行示意串 = `scripts/ci/check-docs.mjs:89` 的注释 `// 正则两条与 scan-lib.sh 的 scan_rule_path 逐字同构（<DRIVE>/… 与 <DRIVE>/… 两种形态）；` —— HEAD 版即存在（`HEAD:scripts/ci/check-docs.mjs:85`），是**正则示意而非实盘路径**（路径体为空、以省略号结尾；门禁断言①自身的 `PATH_RE` 要求路径体 `{1,60}` 故不命中，且该文件不在断言①的 NAV_DOCS 扫描面）【实测】；与红队 round1 的观察项-6 同一条，本批**未新增**，按单列口径登记、不阻断。
- 日志留档：`scratch/r2-final-scan.log`。

---

## 七、新锚点材料（`<LOCAL_WORK>\redteam-ui-pack\`，覆盖式；旧件已归档 `round1/`）

| 文件 | 字节 | 行数 | 说明 |
|---|---|---|---|
| `status-snapshot.txt` | 1621 | 27 | 头（repo label / branch / HEAD 全 sha / 生成时间戳）+ `git log --oneline -5` + `git status --porcelain`（`-c core.quotepath=false`） |
| `diff-tracked.patch` | 65535 | 726 | 全量 `git -c core.quotepath=false diff` |
| `diff-untracked.patch` | 62071 | 456 | 两新文件逐块 `git -c core.quotepath=false diff --no-index /dev/null "<path>"`（每块 = 2 行头注释 + diff + 1 行退出码备注 + 空行），退出码 1（正常）已逐块留痕 |
| `bundle.patch` | 129227 | 1209 | `status-snapshot.txt + diff-tracked.patch + diff-untracked.patch` 顺序 `cat` 拼接；组成已用字节级比对复核（`bundle == status+tracked+untracked` → True）【实测】 |
| `bundle.sha256` | 79 | 1 | `sha256sum -c bundle.sha256` → **`bundle.patch: OK`**【实测】 |
| `file-manifest.txt` | 3268 | 24 | 18 项逐文件：状态 / 增删 / 行数 / 字节 / 工作树 EOL / EOF 换行 / 工作树原始字节 SHA-256 |

- **新 bundle SHA-256（64 位完整值，提交官防夹带锚点）**：

```
1e63e0d5c4349f4554ed57d809f259e807b675d13d3890ef24dc705c32f967d1  bundle.patch
```

  （round1 对应值 `4fc39de7a5faf3eb92ba19ef865568ee8dcd96b97ef13a0304f2a08f338c81af`，已归档 `round1/bundle.sha256`【实测】）
- **LF 纯净化**：6 件锚点文件 CRLF=0、lone-CR=0、均以 LF 结尾【实测（Python 按原始字节判定）】
- **清单一致性复核**：`scratch/verify-manifest.mjs` 重算 18 项的 bytes/lines/EOL/EOF/sha256 与 manifest 逐项比对 → **全部一致（工作树未漂移）**【实测】
- **一处易误读，提前说明**：`diff-tracked.patch` 恰为 **65535 字节**（= 2¹⁶−1），**不是截断** —— 已用两种独立方法证明完整性：① 逐文件 `git diff -- <path>` 字节求和 = 65535 恰等于整份 diff 字节数；② 尾部 3 行为 `check-docs.mjs` 末段上下文（`⑥ …探针不参与构建` / 空行 / `扫描范围：①②③④ …`），与 `--help` 文本末段吻合，无半行/中途截断【实测】。（另注：同一份 diff 若用默认 `core.quotepath=true` 输出为 66135 字节 —— 差异 600 字节全部来自中文文件名的八进制转义，与本批内容无关。）
- 归档动作（copy，未删原件；`round1/` 内 7 件）：`status-snapshot.txt`(1617B) / `diff-tracked.patch`(53935B) / `diff-untracked.patch`(62127B) / `file-manifest.txt`(3225B) / `bundle.patch`(117679B) / `bundle.sha256`(79B) / `红队报告.md`(23212B)【实测】。`红队报告.md` 为 round1 评审报告，**仍在包根保留**（本批不覆盖它），`round1/` 内为其快照副本。

**新文件清单口径**：`git status --porcelain` = **18 项**（与预期一致，未新增/未删除文件）；M2 去向表追加 1 行使 `docs/M2-排期计划.md` 的 diff 增删由 `+1/-1` 变为 `+2/-1`、并使 `bundle.patch` 体量增加，属**正常变化**【实测】。全仓 numstat 合计 `+201 / -200`（round1 为 `+112 / -193`）【实测】。

---

## 八、遗留与未决（本批未做/待裁决）

1. **同形字母缺口（登记，不修）**：西里尔/希腊等同形字母仍可穿过断言⑤（3 例实测）。已在门禁注释 `check-docs.mjs:301-312` 如实登记，并同步收敛模块头与 `--help` 的自述口径；若后续要修，需连同"折叠表范围 + 负对照集"单独立项【实测】。
2. **红队观察项-4（负对照无下限断言）**：`NAMING_CONTROLS` 仍无数量下限（删负对照不红）。本批范围为"连写组严格化"，未扩到负对照面 —— 属**未修**，保留登记【实测（现态自检只输出负对照条数，无下限常量）】。
3. **红队观察项-5（untracked 不在断言⑤扫描面）**：两新文件在本地 commit 前不进 `check-docs` 的 `git ls-files` 面；本轮已由终扫器独立扫描（0 命中）兜底，CI push 后闭合【旁证（红队 round1 观察项-5）+ 实测（本轮终扫器覆盖 2 个新文件）】。
4. **红队观察项-3（R-88 行号漂移）**：`docs/M2-排期计划.md:280` 行内引用 `startAutoRefresh:182-199`，因 UI 包删 32 行、现态为 `stores/task.ts:157-176`。"只追加/内容零删"纪律禁止回改，本批**未动**（R-94 行内已注明该漂移随死码族清除批一并修注）【实测】。
5. **MAJOR-1 的落点设计**：R-94 登记为"下批执行"，其中"发布冲突提示落点重建"的落点（全局消息 vs AI 侧回执）**待裁决/待设计**；本批未实现任何前端行为修复（严格按"本批修补包"范围只做门禁/文档/高亮三项）【实测（`frontend/src/stores/task.ts`、`api/tasks.ts` 本批零改动）】。
6. **题名口径不符**（见 §二立项登记末段）："既有观察项「refreshCurrent 空转族彻底解耦」"在仓库 `docs/` 与红队材料内**均未找到**原始出处；R-94 已承载该题名并注明被吸收的同族既有面，如需以原始出处为准请覆盖。
7. **本轮新增两个仓外脚手架**（不属交付物，供复核复现）：`scratch/gate-block.mjs`（活取门禁规则块）、`scratch/attack-re-r2.mjs`、`scratch/mutate-gate.mjs`、`scratch/final-scan-r2.mjs`、`scratch/verify-manifest.mjs`、`scratch/build-anchors.mjs` 及日志 `r2-*.log`【实测】。

---

## 九、本批改动文件一览（5 个；均未提交）

| 文件 | numstat | 现态 sha256（工作树原始字节） | 说明 |
|---|---|---|---|
| `scripts/ci/check-docs.mjs` | +169/-20（含前批 +87/-14） | `d2db39603358316dd445b33ec09b036792f632eec147ab5fb43af88f8c5b2f3e` | 修补 1（537 行 / 31959 字节） |
| `frontend/src/App.vue` | +6/-3（含前批 0/-2） | `06b824200e118b454e94002a933f980eac304d058f11f62f7000406f8999b753` | 修补 3（76 行 / 3382 字节） |
| `docs/M2-排期计划.md` | +2/-1（本轮 +1 行） | `584caeb8517ecb9214719c7c68f926cc09b938a21a666a71021923f30804d950` | 立项登记 R-94（323 行 / 110287 字节） |
| `docs/research/步骤条交互-业界调研.md` | +174(new) | `c395573dca844addecc4b2643ea45c2f1e5fd15b0900b183dc43a52856eda200` | 修补 2（EOF 换行） |
| `docs/research/对话时序混排-业界调研.md` | +262(new) | `9f94a6ef7e0147fd2e950b0e875b3384d2127dc7d7f96923b1ee6d1e1790f4f1` | 修补 2（EOF 换行） |

> 其余 13 项为前批（UI 包）在制品，本轮**零触碰**：以 `round1/file-manifest.txt` 与 `file-manifest.txt` 的 `path+sha256` 两列逐行程序化比对，结果 **13 行完全一致 / 仅 5 行变化 —— 变化的 5 行恰为本表 5 个文件**（行号 02/09/16/17/18）【实测（比对脚本内联于本轮命令，输出「一致行数: 13  变化行数: 5」）】。
