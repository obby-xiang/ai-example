> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-901` 的 wire 末条 assistant 文本（消息序号 m=23；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-15 00:54 → 2026-10-15 00:57（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：19209 B / 14395 字符 / 220 行；行指纹覆盖率（本批复算）3.5%（最高单文件 3%：`docs/research/设计-真机E2E用例全集-2026-10-23.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：分析）
> - 与既有件关系：设计-真机E2E；报告口径覆盖率 0.03
> - 脱敏：路径 1 / 会话标识 1 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
调查完成。以下是完整报告。

---

# R-164 样式纪律全量排查报告（只排查，零改动）

## 0. 自证行

`modelAlias = **DeepSeek/deepseek-flash**`（profileName=`explore`，agentId=`agent-901`）
wire 路径：`<KIMI_SESSIONS>`（第 1 条 `type=profile.bind`，mtime 与当前时刻同秒，`turn.prompt` 中即本任务 prompt，身份可核）。

排查面：`<REPO_ROOT>\frontend`（src 56 文件 / 17669 行）。`index.html` 无 `<style>`、`public/` 无 css；`frontend` 下 css 文件仅 2 个（`find` 全量核过）。

---

## 1. 面一：手写样式面

### 1.1 `<style` 块 — 命中 **1 个文件 / 1 块**

| 文件:行 | 行数 | 内容性质 |
|---|---|---|
| `frontend/src/components/AiPanel/AiPanel.vue:1444` `<style scoped>` → `:1457` `</style>` | 14 行（含标签） | 2 条手写规则：`.scroll-smooth { scroll-behavior: smooth }`（1447-1449，R-161 平滑回底，由脚本 `classList.add` 动态挂/摘）；`@media (prefers-reduced-motion: reduce) { .caret { animation: none } }`（1452-1456，纯装饰抑制） |

非 CSS 的 `<style` 命中（**非违例**，误报说明）：`components/AiPanel/FormRenderer.vue:111`（注释文字「无 `<style>` 块」）、`utils/excel.ts:610`（OOXML `styleSheet` 字符串）。
`AiPanel.vue:1438-1443` 为对应说明注释。

> 顺带核实：`.caret` 节点在 `AiPanel.vue:188` 的**模板**里（不是 v-html 注入），所以 scoped 属性选择器能命中，此条**不是死规则**；但它与 `ai-chat.css:33` 的 `.ai-chat .caret{animation:aiBlink}` 构成 specificity 平手（均 (0,2,0)），胜负取决于样式表注入顺序 —— 见 §7 Top 风险 2。

### 1.2 `.css` 文件 — **2 个 / 404 行**

**A. `frontend/src/styles/ai-chat.css` — 362 行，100% 手写（0 行 Tailwind）**
用途：AI 对话视觉 demo v6 1:1 移植。分区：

| 行段 | 内容性质 |
|---|---|
| 1-7 | 文件头注释（自称「唯一口径 = 移植映射-R163D.md」） |
| 10-30 | 设计令牌 17 个挂 `.ai-chat` 宿主（`--ink/--line/--blue/--green/--red/--r/--font/--mono`） |
| 33-45 | `.caret` 打字机光标 + `@keyframes aiBlink` |
| 48-77 | `.loading-breath/.dot/.txt` + `aiBreathe`、`.fade-out` + `aiFadeOut` |
| 80-90 | `.tchev` chevron + `.is-open` 旋转 |
| 94-172 | `.md-body` Markdown 排版全套（h1-h6/p/ul/ol/li/blockquote/code/a/hr/img）+ `.md-table-scroll` 表格滚动壳 |
| 175-231 | `figure.code` 代码块图容器（`.lg/.hlib/.copy`）+ `pre` + **9 条 `.hljs-*` 手写配色** |
| 234-291 | mermaid 卡（`.mmd/.mmd-hd/.mi/.mt/.mlib/.mmd-fig/.mmd-foot/.mmd-toggle/.mmd-code/.is-open`） |
| 294-322 | HITL 确认门卡（`.gate/.gate-hd/.ic/.cd/.gate-ft`） |
| 325-338 | 回执卡（`.hitl-receipt/.rt`） |
| **341-362** | **EP 变量覆写（见面二）** |

**B. `frontend/src/styles/main.css` — 42 行，≈34 行手写**
用途：全局入口。逐段：`:3` `@import './ai-chat.css'`（**手写 CSS 级联入口，绕过 main.ts**）；`:5-7` `@tailwind base/components/utilities`；`:11-17` `html,body,#app{height:100%;margin:0;padding:0}` 复位；`:21-25` `*{box-sizing:border-box}`（注释自陈：preflight 关闭后无兜底，曾实测 w-full+padding 外溢 20px）；`:27-32` `body{background:#f5f7fa;color:#303133;font-family:'Microsoft YaHei'...}`；`:37-41` `@layer utilities { .writing-vertical }`。

**附加：二方库 CSS 引入 2 处**（非手写，但属「样式入口」清单）
- `src/main.ts:10` `import 'element-plus/dist/index.css'`（EP 全量样式）
- `src/utils/markdown.ts:23` `import 'highlight.js/styles/github.css'` —— 与 `ai-chat.css:223-231` 的 9 条手写 `.hljs-*` **重复定义同一批 token 配色**（双份真相，迁移时必须同批处理，否则删手写块会回退到库主题）

### 1.3 行内 `style="` / `:style` — **5 处**

| 文件:行 | 内容 | 性质 |
|---|---|---|
| `components/AiPanel/AiPanel.vue:368` | `style="margin:0;padding-left:2px"` | **静态内联**，可零成本换工具类（最硬违例） |
| `components/AiPanel/AiPanel.vue:378` | `:style="m.streaming && m.role === 'assistant' ? 'visibility: hidden' : ''"` | 动态内联，裸 CSS 字符串 |
| `components/AiPanel/CollapsibleText.vue:77` | `:style="collapsed ? { maxHeight: maxHeight + 'px', maskImage: 'linear-gradient(to bottom, #000 55%, transparent)' } : {}"` | 动态内联 + 内嵌色值 `#000`；**被测试直接断言**（见 §7） |
| `components/AiPanel/FormRenderer.vue:29` | `style="width: 100%"` | **静态内联**在 `el-input-number` 上（兼属 EP 面） |
| `components/SpreadGrid.vue:6` | `:style="{ height }"` | 动态内联（宿主高度 prop），业务区 |

另：`AiPanel.vue:131/255/289` 走 EP `color="#..."` prop 硬编码 hex（`#2563eb`×2、`#c0392b`），性质同族，计入面二。

---

## 2. 面二：element-plus 覆写面

### 2.1 `.el-` 选择器出现在项目自写样式里 — **仅 7 条，全部在 `ai-chat.css:341-362`（22 行），别处零命中**

| 文件:行 | 覆写对象 | 手段 |
|---|---|---|
| `styles/ai-chat.css:341` | `.ai-chat .el-tag--primary` | 覆写 `--el-tag-text/border/bg-color` |
| `styles/ai-chat.css:342` | `.el-tag--success` | 同上 |
| `styles/ai-chat.css:344` | `.el-tag--warning` | 同上（收敛为中性灰族） |
| `styles/ai-chat.css:345` | `.el-tag--danger` | 同上 |
| `styles/ai-chat.css:346` | `.el-tag--info` | 同上 |
| `styles/ai-chat.css:347-354` | `.el-button--primary` | 覆写 6 个 `--el-button-*-color` / `-border-color` |
| `styles/ai-chat.css:355-362` | `.el-button--danger` | 同上 |
| （`:343` 为注释行） | | |

全仓 `.el-` 出现次数合计 = 7，无第二处 —— 你的「已知存量」清单是完整的。

### 2.2 模板里给 EP 组件挂样式类 — **67 处**（含跨行标签），分三级

**A 级｜真掩盖 EP 内部样式（4-5 处，最需裁决）**
- `views/TaskCenterView.vue:124` `<el-radio class="!h-auto !whitespace-normal !items-start !mr-0">` — 4 个 `!important` 直接压 `.el-radio` 的 height/white-space/align-items/margin-right
- `views/TaskCenterView.vue:128` 同上（IMPORT 项）
- `views/TaskCenterView.vue:123` `<el-radio-group class="flex flex-col items-start gap-1">` — 把 EP radio-group 的行内横向排布强改纵向
- `views/TaskCenterView.vue:66` `<el-progress class="flex-1">` — 尺寸类（边缘 A 级）
- `frontend/src/App.vue:6` `<el-menu class="!border-b-0">` — `!important` 抹掉 EP 菜单底边

**B 级｜改 EP 控件尺寸默认值（≈22 处）**：`ConditionForm.vue:4/18/28/38/44/54/62`、`DefSelector.vue:10`、`DefinitionsView.vue:24/141/194/202`、`DataBrowserView.vue:18/26/31/40`、`TaskCenterView.vue:18/19/23`、`FormRenderer.vue:38/48/64`（`w-[130px]`/`w-[180px]`/`w-[220px]`/`w-[240px]`/`w-[260px]`/`w-full`/`w-1/2`）

**C 级｜仅加外边距/包装布局，不碰 EP 内部（≈40 处）**：`AiPanel.vue:52/64/74/84/98/107`（`el-alert class="m-2"`）、`AiPanel.vue:61/93`（`mt-1`）、`AiPanel.vue:320`（`ml-auto`）、`AiPanel.vue:122`（`el-skeleton p-2`）、`AiPanel.vue:255/289`（`el-icon shrink-0/mt-px`）、`JobProgress.vue:12/28`、`TaskWizard.vue:8/17/27`、`DataBrowserView.vue:16/65/77`、`DefinitionsView.vue:80/150/213`、`ExportWizardView.vue:20/29/42/88/95`、`ImportWizardView.vue:20/29/65/66/98/106/136/189`、`App.vue:30`（`el-aside` 加 border/bg/transition）

按文件分布：`DefinitionsView 7`、`ImportWizardView 8`、`TaskCenterView 8`、`ConditionForm 7`、`DataBrowserView 7`、`ExportWizardView 5`、`FormRenderer 4`、`TaskWizard 3`、`AiPanel 13`、`App 2`、`JobProgress 2`、`DefSelector 1` = 67。

另外 3 处色值走 EP prop 而非工具类：`AiPanel.vue:131` `el-icon :size="36" color="#2563eb"`、`:255` `color="#2563eb"`、`:289` `color="#c0392b"`。

---

## 3. 面三：Tailwind 使用现状

**配置（`frontend/tailwind.config.js`，17 行）**
- `content: ['./index.html', './src/**/*.{vue,ts}']`
- `corePlugins.preflight: false`（注释：避免与 EP / SpreadJS 冲突）
- `theme.extend.colors = { workspace: '#f5f7fa', aiPanel: '#fafbfc' }`，`plugins: []`
- 现状：`bg-aiPanel` 仅用 1 次（`AiPanel.vue:36`）；**`workspace` 令牌全仓 0 次使用**（`main.css:28` 仍手写 `#f5f7fa`）→ 令牌层等于半废
- `postcss.config.js`：`tailwindcss` + `autoprefixer`

**任意值 hex 类：110 处出现 / 23 个不同色值**

| 色值 | 次数 | 色值 | 次数 |
|---|---|---|---|
| `#909399` | 27 | `#f6f7f9` | 3 |
| `#c0c4cc` | 11 | `#c0392b` | 3 |
| `#8b949e` | 10 | `#409eff` | 3 |
| `#606266` | 10 | `#2563eb` | 3 |
| `#f56c6c` | 6 | `#eef0f3` | 2 |
| `#303133` | 6 | `#e4e7ed` | 2 |
| `#ebeef5` | 5 | `#fdf1ef`/`#fafafa`/`#eff4ff`/`#e6a23c`/`#7b61ff`/`#1f2328`/`#1a7f45` | 各 1 |
| `#eaecf0` / `#e3e6ea` / `#57606a` | 各 4 | | |

按文件：`AiPanel.vue 54`、`DefinitionsView.vue 11`、`AiFeedbackView.vue 9`、`TaskCenterView.vue 7`、`JobProgress.vue 5`、`TaskWizard.vue 5`、`ImportWizardView.vue 5`、`FormRenderer.vue 4`、`SpreadGrid.vue 3`、`DataBrowserView.vue 2`、`App.vue 1`、`CollapsibleText.vue 1`、`ConditionForm.vue 1`、`DefSelector.vue 1`、`ExportWizardView.vue 1`。

其他任意值（非 hex）：`w-[130px] [180px] [200px] [220px] [240px] [260px]`、`rounded-[12px_12px_3px_12px]`、`text-[13.8px] [13.5px] [11.4px] [11.5px] [12.2px] [10px]`、`leading-[1.7] [1.8]`、`py-[8px] px-[10px]`、`shadow-[0_2px_8px_rgba(0,0,0,0.13)]`、`transition-[width]`、`[overflow-wrap:anywhere]`。
`!important` 修饰符命中：`App.vue:11`、`TaskCenterView.vue:124/128`（同 A 级掩盖）；深层选择器 `[&...]`：**0 处**。

---

## 4. 面四：v-html 动态内容面（渲染层吐什么班）

唯一 v-html 站点：`components/AiPanel/AiPanel.vue:187` → `<div class="md-body" v-html="markdownHtml(m, block)">`。

**产出标签白名单**（`utils/markdown.ts:38-43`）：`p h1-h6 ul ol li table thead tbody tr th td code pre blockquote em strong del a hr br img span figure figcaption button div`

**产出 class 清单与落点（改造的 3 个代码点）**

| 落点（文件:行） | 产出的 class |
|---|---|
| `utils/markdown.ts:185-187`（字符串模板） | `figure.code` > `span.lg` / `span.hlib` / `button.copy` |
| `utils/markdown.ts:198-199` | `div.md-table-scroll`（表格滚动壳） |
| `components/AiPanel/AiPanel.vue:910-917`（`document.createElement` + `innerHTML`） | `div.mmd` > `.mmd-hd`(`.mi/.mt/.mlib`) / `.mmd-fig` / `.mmd-foot`(`.mmd-toggle`) / `.mmd-code`；`.is-open` 由 `:890`/`:923` 动态切换 |
| highlight.js 自动产出 | `span.hljs-*`（9 类，`markdown.ts:127` hook 收窄）、`code.language-*`（`:135`） |

**模板侧自定义 class hook（靠手写 CSS 存活，共 31 个）**：`ai-chat.css` 内 29 个 —— `.caret .loading-breath .dot .txt .fade-out .tchev .is-open .md-body .md-table-scroll .gate .gate-hd .ic .cd .gate-ft .hitl-receipt .rt .code .lg .hlib .copy .mmd .mmd-hd .mi .mt .mlib .mmd-fig .mmd-foot .mmd-toggle .mmd-code`（+ `.hljs-*` 9 个库 token 类 + `.ai-chat` 宿主 = 39；再 + 7 个 `.el-*` = 46 个类选择器）；另有 `main.css:38` `.writing-vertical`（用点 `AiPanel.vue:9`）、`AiPanel.vue:1447` `.scroll-smooth`（用点 `:612/:617`）。

---

## 5. 面五：EP 组件使用全清单（`<el-` 标签 316 个）

| 组件 | 数 | 组件 | 数 | 组件 | 数 |
|---|---|---|---|---|---|
| el-button | 61 | el-table | 9 | el-form | 2 |
| el-table-column | 58 | el-form-item | 7 | el-drawer | 2 |
| el-option | 24 | el-icon | 5 | el-divider | 2 |
| el-tag | 22 | el-radio-group | 4 | el-descriptions | 2 |
| el-alert | 19 | el-menu-item | 4 | el-date-picker | 2 |
| el-input | 16 | el-switch | 3 | el-upload | 1 |
| el-select | 15 | el-card | 3 | el-steps | 1 |
| el-descriptions-item | 12 | el-tabs / el-tab-pane | 2 / 2 | el-step | 1 |
| el-empty | 11 | el-radio | 2 | el-skeleton | 1 |
| el-radio-button | 10 | el-progress / el-pagination | 2 / 2 | el-menu | 1 |
| | | el-input-number | 2 | el-dialog / el-collapse(-item) | 1 / 1 |
| | | | | el-checkbox(-group) | 1 / 1 |
| | | | | el-aside | 1 |

按文件：`DefinitionsView 84`、`TaskCenterView 41`、`DataBrowserView 34`、`ImportWizardView 30`、`AiPanel 30`、`JobProgress 21`、`ExportWizardView 17`、`DefSelector 16`、`ConditionForm 14`、`FormRenderer 13`、`TaskWizard 7`、`App 7`、`AiFeedbackView 2`。

含义：EP 覆盖面广（含 `el-table-column` 58 = 表格主呈现、`el-descriptions` 元数据、`el-steps` 向导），「不接受 EP 原样就不准用 EP」这条纪律一旦严格执行，**表格/描述列表/向导步骤/表单控件五大块基本都要重做**（且不能手写样式 → 只能用 Tailwind 工具类 + 原生元素重搭）。这是本次改造成本的决定性变量。

---

## 6. 整改分区归表

| 区 | 硬违例（手写 CSS/内联/EP 覆写） | EP 掩盖类实例 | 样式字面量（hex 类 + EP color prop） | 渲染层产出点 | CSS 耦合 hook | 小计 | 整改量 |
|---|---|---|---|---|---|---|---|
| **区1 AI 面板消息区** | 13（`ai-chat.css` 本体 1 + 8 语义段 + `AiPanel.vue:1444-1457` 1 + 内联 4 + `.el-` 覆写 7） | 9（`AiPanel 122/255/289/311/320` + `FormRenderer 38/48/64/92`） | 51（`AiPanel` 消息区 43 + `FormRenderer` 4 + `CollapsibleText` 1 + EP color prop 3） | 3 | 30 | **106** | **大** |
| **区2 AI 面板外壳**（头部/上下文条/告警/输入区） | 0（令牌块 `ai-chat.css:10-30` 与区1 共用，按主责归区1） | 8（`AiPanel 52/61/64/74/84/93/98/107`） | 11（`AiPanel 5/14/15/17/18/36/466/477`） | 0 | 1（`.writing-vertical`） | **20** | **小** |
| **区3 业务区**（任务中心/配置定义/数据浏览/AI 反馈/两向导） | 1（`SpreadGrid.vue:6` 内联；手写 CSS **0**） | 48（见 §2.2 各视图） | 51（视图 hex 50 + `AiFeedbackView.vue:60` TS 内返回 `text-[#409eff]/[#f56c6c]` 1） | 0 | 0 | **100** | **中**（唯 A 级 4 处需裁决） |
| **区4 全局入口** | 2（`main.css` 文件 + `main.css:3` 手写 `@import`） | 2（`App.vue:6/30`） | 1（`App.vue:30`） | 0 | 0 | 7（+入口附加 3，见下） | **中**（承载性最强） |
| **合计** | **15** | **67** | **114** | **3** | **31** | **230（±5）** | — |

区4 入口附加 3 条：`main.ts:10` EP 全量 CSS 引入、`utils/markdown.ts:23` highlight.js 主题 CSS 引入、`tailwind.config.js` `preflight:false` + 仅 2 令牌（`workspace` 令牌死令牌）。
（±5 来源于跨区条目归属差异 —— 令牌块、`ai-chat.css` 文件本体、跨区复用组件；分类计数本身是精确的。）

**违例总条数 = 15（结构性手写样式）+ 67（EP 掩盖）+ 114（样式字面量）+ 3（渲染层产出点）+ 31（CSS 耦合 hook）= 230 条**
四区分布：**区1 106 / 区2 20 / 区3 100 / 区4 ≈10**。

**四区整改量**：区1 **大**（~330 行手写 CSS + 渲染层 3 点 + 10 个 hook + 13 处测试断言同步）；区2 **小**（纯机械 hex→令牌）；区3 **中**（100 条但零手写 CSS，95% 是可批量替换的 hex，A 级 4 处需一次产品裁决）；区4 **中**（行数最少但改动必须整批回归）。

---

## 7. Top 风险点 3 条

**① `main.css` 复位与 `preflight:false` 咬合 —— 删手写 CSS 的「顺序雷」**
`main.css:21-25` 的 `*{box-sizing:border-box}` 是 preflight 关闭后**唯一的**盒模型兜底（`main.css:19-20` 注释自陈：CU 复测实测 content-box、`w-full`+padding 外溢 20px）。同理 `:11-17` 的 `html,body,#app{height:100%}` 是全站双栏骨架前提。任何整改若先动 `main.css`（或先开 preflight）而不整批重跑，会立刻产生全站布局外溢/高度塌陷。**必须先决策「开 preflight 还是保兜底」，再动 CSS。**

**② `TaskCenterView.vue:123-128` 的 EP 掩盖 —— 必须由用户裁决，无技术捷径**
`el-radio-group` 被 `flex flex-col items-start gap-1` 强改纵向 + `el-radio` 被 `!h-auto !whitespace-normal !items-start !mr-0` 四个 `!important` 压内部几何。按新纪律只有两条路：**接受 EP 原本的横向单行 radio**，或**弃用 `el-radio` 自绘**（而自绘又不能手写样式 → 只能用 Tailwind 工具类拼原生 `input[type=radio]` + `sr-only`）。这条牵动任务中心「创建任务」表单，是区3 唯一不能机械替换的点。

**③ 样式 hook 三方耦合：测试断言 + 渲染层吐 class + 内联 style 被直接断言**
- `frontend/tests/ai-panel-parts-render.spec.ts` 有 **13 处**断言依赖样式 hook/内联：`:1031/:1123` 断言 `.md-body`、`:318/:1044/:1060/:1082/:1086/:1089` 断言 `loading-breath`/`fade-out`、`:889/:920/:922/:928/:953` 断言 `hitl-receipt`、`:1204/:1207` 断言 `scroll-smooth` 类挂/摘。
- `:980-981` **直接断言 `wrap.style.maxHeight === '96px'` 与 `style.maskImage`** —— 正是 `CollapsibleText.vue:77` 的内联 style；把它改成工具类会直接打断这两条断言（maskImage 无法用 Tailwind v3 工具类表达，需转 `@layer utilities` 自定义工具类或保留内联，属**规则冲突点**，需提前定义该类场景的口径）。
- 派生子风险：`ai-chat.css:223-231` 手写 9 条 `.hljs-*` 与 `markdown.ts:23` 引入的 `highlight.js/styles/github.css` 双份定义，删手写块会静默回退库主题配色。

**附：`tailwind.config.js` `theme.extend.colors.workspace`（`#f5f7fa`）全仓 0 次引用**，`main.css:28` 仍手写同值 —— 说明令牌层已具备但未启用，整改可直接以「23 个 hex → theme.extend 令牌」为主要抓手（`#909399` 单值 27 处即最典型重复）。

---

## 8. 回报摘要（给主智能体）

- **自证**：`modelAlias = DeepSeek/deepseek-flash`（profile `explore`，agent-901）。
- **违例总条数**：**230 条** = 结构性手写样式 15（1 个 `<style>` 块 + 2 个手写 `.css` 共 404 行 + 5 处内联 style + 7 条 `.el-` 覆写 22 行）+ EP 掩盖实例 67 + 样式字面量 114（110 处任意值 hex 类 / 23 色值 + 3 处 `el-icon color` + 1 处 TS 返回色）+ 渲染层 class 产出点 3 + 手写 CSS 耦合 hook 31。
- **四区分布**：区1 AI 面板消息区 106 / 区2 面板外壳 20 / 区3 业务区 100 / 区4 全局入口 ≈10；整改量 区1 大、区2 小、区3 中、区4 中。
- **Top 3 风险**：① `main.css:21-25` box-sizing 兜底与 `preflight:false` 咬合，删 CSS 顺序错即全站布局崩；② `TaskCenterView.vue:123-128` 对 `el-radio` 的 4 个 `!important` 掩盖，需「接受 EP 原样 or 弃用 EP」产品裁决；③ 样式 hook 与 `tests/ai-panel-parts-render.spec.ts` 13 处断言耦合（含 `:980-981` 直接断言 `CollapsibleText.vue:77` 的内联 style），改样式必同步改测试，且 maskImage 无 Tailwind v3 对应工具类。
- **附带发现**：`tailwind.config.js` 的 `workspace` 令牌 0 引用；`ai-chat.css:223-231` 与 `highlight.js/styles/github.css` 双重定义 hljs 配色；`.caret` 的 reduced-motion 抑制存在 specificity 平手导致的注入顺序竞态。
