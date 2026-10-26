> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-328` 的 wire 末条 assistant 文本（消息序号 m=8；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-10 16:10 → 2026-10-10 16:11（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`d3020ee2d245c3c38a803c320f0ff90248d5d270`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：13112 B / 7685 字符 / 118 行；行指纹覆盖率（本批复算）3.5%（最高单文件 2%：`docs/evidence/history-recovery-2026-10-26/reports/redteam-2026-10-10-UI包.md`）
> - 类别：**施工复核**
> - 与既有件关系：红队报告；报告口径覆盖率 0.02
> - 脱敏：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
全部核验完成。以下是结构化核收摘要。

---

# 红队报告核收摘要（GLM 对抗性评审 · AI 施工包 18 项未提交改动）

## 0. 落盘确认

- 报告位置：`<LOCAL_WORK>\redteam-ui-pack\红队报告.md`（路径正确）
- 完整性：**183 行 / 23212 字节**，已读到 EOF（最后一行为 `—— 红队评审官 GLM，2026-10-10`），无截断
- 目录同层配套件：`bundle.patch`(117679B)、`bundle.sha256`、`diff-tracked.patch`、`diff-untracked.patch`、`file-manifest.txt`、`status-snapshot.txt`、`scratch/`（含 4 个攻击脚本 + build-report.md + 原始日志）
- 报告署名：GLM 红队，评审日期 2026-10-10

## 2a. 锚点核对结果

- 报告载明执行了 `sha256sum -c bundle.sha256`，输出 `bundle.patch: OK`，并附 `sha256sum bundle.patch` 实算值。
- **我独立复算**：`bundle.sha256` 内容 = `4fc39de7a5faf3eb92ba19ef865568ee8dcd96b97ef13a0304f2a08f338c81af`，实算 `bundle.patch` 的 SHA-256 **完全一致** → 锚点核对**属实**。
- 附带核对（我复验）：HEAD = `d3020ee2d245c3c38a803c320f0ff90248d5d270`；`git status --porcelain | wc -l` = **恰 18 项** → 与报告及 status-snapshot 一致，**属实**。

## 2b. 分级清单（逐条全列）

### BLOCKER（禁止提交级）
**无。** 红队明确写「BLOCKER（必须修，否则禁止提交）：无。」

### MAJOR（应修，可裁决）——共 2 条

**MAJOR-1｜批次①：拆抽屉未做配套收尾——store「当前任务族」整链死码 + AI 侧发布冲突失去唯一渲染点**
- 锚点：`frontend/src/stores/task.ts:77-88`（死状态族）、`task.ts:179-199`（`refreshCurrent` 恒早退）、`task.ts:294-321`（`watchPublishOutcome` 写入无人消费的 `publishConflict`）、`frontend/src/api/tasks.ts:19-20`（`getTaskOverview` 死调用 + 注释失实）、`frontend/src/stores/workspace.ts:398-405`（AI `run_job` 发布路径）、HEAD 版 `frontend/src/views/TaskCenterView.vue:148-153`（被拆的唯一渲染点，`git grep` 实证）
- 一句话：拆抽屉只删了消费者、没删供给链，导致 `currentTaskId` 永久 null、`refreshCurrent()` 恒 no-op、`publishConflict` 无人渲染——**AI 侧发起的发布冲突用户彻底不可见**。
- 证据类型：**【实测】**（逐环 grep + HEAD 基线对比）

**MAJOR-2｜批次③：三态正则存在 13 个 Unicode 隐形/同形字符绕过**
- 锚点：`scripts/ci/check-docs.mjs:301`（`ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g` 漏 U+00AD/U+180E/U+2061-2064/U+206A 等）；NFKC 不折叠西里尔 а/希腊 ν 同形字；绕过样本实测输出存 `scratch/attack-re.mjs`
- 一句话：`main\u00ADv2`（软连字符，复制粘贴常见）等 13 种形态可全绿入库，与 R-92「同形字折叠」立规口径有差距。
- 证据类型：**【实测】**（31 变异样本实跑，15 绕过中剔除 2 个红队自身构造错误，有效 13）

### MINOR（建议修）——共 3 条

**MINOR-1｜批次③：自检 `seedsNew` 阈值形同虚设，掏空攻击实测成立**
- 锚点：`scripts/ci/check-docs.mjs:340-353`
- 一句话：`seedsNew≥6` 因量词 `*` 使含分隔符种子也必然命中第三态，13/13 全命中是数学必然而非覆盖证明；实测「删第三态 + 删 5 个连写种子」后自检仍全绿、违禁文本放行。
- 证据类型：**【实测】**（`scratch/attack-hollow.mjs`）

**MINOR-2｜批次④：两个新文档缺 EOF 换行**
- 锚点：`docs/research/步骤条交互-业界调研.md`、`docs/research/对话时序混排-业界调研.md` 末尾无换行符
- 一句话：与仓内 md 惯例不一致，git 将永久显示 `\ No newline at end of file`。
- 证据类型：**【实测】**（`tail -c 1` = 0x82，file-manifest `eof_nl=NO` 佐证）

**MINOR-3｜批次①：向导页激活时顶部菜单无高亮项**
- 锚点：`frontend/src/App.vue:62`（`activeRoute`）
- 一句话：向导页（`export-wizard`/`import-wizard`）不再匹配任何 `el-menu-item` index → 顶部菜单全灰，「你在哪」定位反馈丢失且未登记。
- 证据类型：**【推断】**（代码路径推演，未起浏览器实测视觉）+ **【旁证】**（S44a/S44b 注记只记菜单数量未记高亮）

### 观察项（不阻塞，登记）——共 6 条

1. **观察项-1**：任务详情「历史作业全量列表 + 文件清单」在向导页无逐条等价视图（向导按 kind 各挂一卡）；`requirements.md:55` 已声明需求面收缩，口径自洽【推断】
2. **观察项-2**：`TaskCenterView.vue:269` `row.task.id ?? 0` 把 undefined 静默换成 0（旧 `openDetail` 有早退防护）；降级路径安全（向导显示「尚未绑定任务」）【推断】
3. **观察项-3**：`docs/M2-排期计划.md:280` R-88 行内 `startAutoRefresh:182-199` 因本批删 32 行漂移为现态 157-176；R-88 本批未触碰、「只追加」纪律禁止回改，纪律与准确性冲突的固有代价【实测】
4. **观察项-4**：`NAMING_CONTROLS` 数量无下限断言，删负对照不红；现 11 个全在、符合 R-92「至少 1 个」立规【实测】
5. **观察项-5**：断言⑤按 `git ls-files` 扫描，untracked 新文件在本地 commit 前不在扫描面（pre-commit hook 不跑 check-docs）；CI push 后兜底闭合，本批两新文件经红队终扫干净【实测】
6. **观察项-6**：`check-docs.mjs:87` 注释含正则示意 `D:/`，盘符字母恰与本机盘符重合——非本机路径、不在断言①扫描面，无碍【实测】

## 2c. 红队实跑验证记录（报告第四节，12 行）

| # | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 1 | `sha256sum -c bundle.sha256` | 0 | `bundle.patch: OK`，实算=锚点 |
| 2 | `yarn typecheck`（node v22.23.2/yarn 1.22.22） | 0 | `vue-tsc --noEmit` 零错误（6s） |
| 3 | `yarn build` | 0 | vite `✓ built in 28.48s`，1747 modules，零告警（34s） |
| 4 | `node scripts/ci/check-docs.mjs` | 0 | 6/6 断言绿；⑤扫描 913 文件、种子 13 全命中/对照 11 全过 |
| 5 | `node scratch/attack-re.mjs`（31 样本） | 0 | 14 符合预期 / **15 绕过**（有效 13）/ 0 误伤 |
| 6 | `node scratch/attack-hollow.mjs` | 0 | **掏空攻击成立**：自检全绿 + 第三态已死 + 违禁文本放行 |
| 7 | `node scratch/guard-compare.mjs` | 0 | 带/无守卫命中集均为空集、完全一致（913 文件） |
| 8 | `node scratch/final-scan.mjs`（18 文件终扫） | 0 | 6 植入全检出/3 负对照零误伤；唯一命中 check-docs.mjs:87 `D:/` |
| 9 | `diff`（原始稿 vs 入库版） | 0/1 | 步骤条仅头部 +3 行；对话时序仅头部 + 脱敏 6 处盘符 + 1 处旧仓名；正文零删改 |
| 10 | `git rev-parse HEAD` / `status \| wc -l` | 0 | `d3020ee…`，恰 18 项 |
| 11 | `tail -c 1` 两新 md | 0 | 末字节 0x82（无 EOF 换行） |
| 12 | 多轮 `grep -rn`（孤儿导出/currentTaskId/publishConflict/抽屉残留/restoreWizard 消费面） | 0/1 | 见各批次攻击过程 |

报告声明：全程零 git 写操作、仓库文件零修改，变异样本与脚本全部落在 `<LOCAL_WORK>\redteam-ui-pack\scratch\`。

## 2d. 红队总评结论

**有条件放行**：无 BLOCKER；2 项 MAJOR 应修后再合入（或经明确裁决接受后合入），3 项 MINOR 建议随手修，6 项观察项登记不阻塞。
- 若 MAJOR 两项均裁决接受不修，本批**亦可提交**（不构成禁止提交级缺陷）；但 MAJOR-1 的 AI 发布冲突提示缺失应至少登记入 M2 排期，MAJOR-2 的绕过面应在门禁注释中收敛「无法绕过」的自述口径。
- MAJOR-1 被定性为「本批最大的真实缺口：不是删多了，而是删了消费者、留下了供给链」。
- 同时确认为**属实**的正面项：三项验证命令（typecheck/build/check-docs）全绿、两份调研文档保真承诺、18 文件隐私/命名终扫干净、批次②三孤儿导出零调用。

## 2e. 对既有背景报告的反驳/打脸点（红队打脸清单）

1. **打脸批次①「拆抽屉」施工包**：自述视为收尾完成，实际只删了消费者、留下整链死码（MAJOR-1）。`currentTask/currentJobs/currentFiles/issues/issuesTotal/detailLoading/publishConflict` 全部只写不读；`getTaskOverview` 成死调用。
2. **打脸批次②「修补包」自身口径**：批次②以「孤儿导出零调用」为由删了 3 个小导出，却在同批留下体量大得多的 store 死状态族——**「删孤儿」纪律在本批内执行不一致**，此点被并入 MAJOR-1 定级理由。
3. **实证注释失实**：`api/tasks.ts:19` 新注释称「（refreshCurrent 读取当前任务快照用）」——描述的是已永久 no-op 的死链。
4. **打脸批次③门禁自述**：门禁自述列举了全角/U+2010-U+2015/U+2212/零宽/零分隔等形态，13 个漏检不在自述清单内（红队公允记录为「覆盖面存在自述之外的缺口，非自述失实」，但仍与 R-92「同形字折叠」立规口径有差距）。
5. **打脸 check-docs 自检设计的「覆盖证明」**：红队指出「13/13 全经第三态命中」是量词 `*` 导致的**数学必然**，而非覆盖证明（MINOR-1）。
6. **既有排期文档失准**：`M2-排期计划.md:280` R-88 行内 `startAutoRefresh:182-199` 已漂移为 157-176（25 行），但「只追加」纪律禁止回改（观察项-3）。
7. **对 requirements.md 注记未打脸**：注记称列表实无「配置项数」列、4s 自刷——红队实测**均属实**（未破防）；`requirements.md:55` 需求面收缩声明也被判口径自洽（观察项-1）。
8. **门禁盲区**：pre-commit hook 不跑 check-docs，untracked 新文件在本地 commit 前不在断言⑤扫描面（观察项-5）。

## 3. 抽查实测（我独立复读工作树现态核验）

| # | 报告断言 | 实测结果 | 判定 |
|---|---|---|---|
| 1 | **MAJOR-1**：`task.ts:77-88` 死状态族；`currentTaskId` 无任何非空写点 | 全文 grep 仅 4 处：`:77` 声明 null、`:180` 读取、`:202` 写 null、`:225` 比较——**确无非空写点**；`:88` 为 `publishConflict` | **【实测属实】** |
| 2 | **MAJOR-1**：HEAD 版 `TaskCenterView.vue:148-153` 是 `publishConflict` 唯一渲染点 | `git show HEAD` 实测 :145 起为 `el-drawer`，:148 `v-if="taskStore.publishConflict"`，:153 标题引用；工作树现态 `TaskCenterView.vue` 已无此引用，且 `ImportWizardView.vue:288` 的 `publishConflict` 是**视图本地 ref**（另一物） | **【实测属实】** |
| 3 | **MAJOR-1**：`workspace.ts:398-405` AI `run_job` 发布路径；`api/tasks.ts:19-20` 注释失实 | 实测 :398-405 恰为 `run_job` case（调 `refreshCurrent` + `watchPublishOutcome`）；`tasks.ts:19` 注释原文「（refreshCurrent 读取当前任务快照用）」在 :20 为 `getTaskOverview` | **【实测属实】** |
| 4 | **MAJOR-2**：`check-docs.mjs:301` 正则漏 U+00AD 等 | 实测 :301 逐字为 `const ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g`；我另用同一规则独立构造 8 个样本复跑，**8/8 全部 BYPASS**（U+00AD、U+180E、U+2061、U+3164、U+FFA0、西里尔 а 分隔/连写两态、希腊 ν） | **【实测属实】**（行号零漂移） |
| 5 | **MINOR-2**：两个新调研文档无 EOF 换行 | 两文件 `tail -c 1` 均 = `0x82` | **【实测属实】** |
| 6 | **MINOR-3**：`App.vue:62` 向导页菜单无高亮 | `App.vue:62` = `String(route.name ?? ROUTE_NAMES.taskCenter)`；菜单仅 3 项（:13-15 taskCenter/definitions/dataBrowser）；`router/index.ts:20-53` 确认 5 条具名路由（含 exportWizard/importWizard）+ :52 兜底重定向 | **【实测属实】** |
| 7 | **观察项-3**：M2:280 R-88 引用 `startAutoRefresh:182-199` 漂移 | `sed -n '280p'` 确含 `startAutoRefresh:182-199`；现态 `task.ts` 该函数实际在 157-176 | **【实测属实】**（漂移 25 行） |

**行号漂移情况**：抽查 7 条中仅 MINOR-1 的行区间有轻微外扩（报告写 `340-353`，实际函数体为 `339-358`，核心阈值判断 `:348`、`seedsNew` 计数 `:346` 均在报告区间内），不影响断言成立。**无一条出现「行号漂移但问题不存在」或造假情形。**

## 总体判定

红队报告**完整、锚点真实、结论可复核**。所有被我抽查的 BLOCKER/MAJOR/MINOR 关键锚点在 `<REPO_ROOT>` 工作树现态下**逐条命中**（行号最多 25 行漂移且原因报告已自陈）。报告自身定位为「有条件放行、无 BLOCKER、2 MAJOR 待裁决」，其打脸点集中在批次①「删消费者留供给链」与批次②「删孤儿纪律不一致」的内部矛盾，以及对门禁自述覆盖面的收敛要求。

**遗留/建议关注**：MAJOR-1（AI 侧发布冲突提示回归 + store 死码族）本就是需求面收缩的连带风险，是否按方案 A/B 修复或登记入 M2 排期，属需上游裁决事项；MAJOR-2 修复成本低（`ZERO_WIDTH_RE` 一行扩展），报告建议本批内顺手修。本次为只读核验，未修改任何文件、未执行任何 git 写操作。
