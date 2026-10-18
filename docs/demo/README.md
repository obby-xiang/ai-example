# demo 归档索引（docs/demo/）

> 用途：历史原型 demo 的全量入库与溯源索引。**用户直裁 2026-10-14：近期 demo 全部合入代码库，后续 demo 亦必合入。**
> 合入口径：**定稿 / 末版**进分类主目录，**中间版本与过程稿**进同分类 `archive/`；文件自仓外原路径逐字节复制（原件保留、不移动、不改写）。
> 状态取值：`定稿` / `过程稿` / `harness`。**状态取值扩展（2026-10-17，主题制落地）**：`归档` = 累积版历史件（不再更新）；`现役` = 主题单文件（当前维护）；`主题` = 主题单文件的归类标签（见「现役主题单文件」表 `主题` 列）。2026-10-17 之前的行仍用原三态，不回改。`合入来源` 一律写**仓外相对口径**（按 DC-08 不落盘符绝对路径）。
> 状态取值补充（2026-10-18）：`待选择/未批准` = 候选主题件已入索引、用户尚未裁决；仅用于候选索引，不等于定稿，也不触发双看板状态迁移。
> 脱敏：demo 正文若含个人信息或本机字样，按 DC-08 就地改写，并在下表「一句话说明」注明「入库已脱敏」。
> 盘点来源：`demo-盘点-2026-10-14.md`（仓外盘点报告，15 件清单 + 分类 + 来源路径）。
> 补合入（2026-10-15，提交官）：R-165 视觉重做 demo `v7`~`v7.8` 九版（html + 配套 css 成对，共 18 件）按本索引体例合入 `docs/demo/chat-ux/`（末版 v7.8 入分类主目录，v7~v7.7 沉 `archive/`），逐件溯源见下表、脱敏与改名见「入库纪律与脱敏台账」。
> 补合入（2026-10-16，提交官）：R-165 demo `v7.9`（html + 配套 css 成对，共 2 件）按同体例合入 `docs/demo/chat-ux/` —— 末版 v7.9 入分类主目录，v7.8 下沉 `archive/`；逐件溯源见下表、脱敏与改名见「入库纪律与脱敏台账」。
> 补合入（2026-10-17，提交官）：交接前收尾落库 —— ①R-165 累积版 `v7.10` / `v7.11` / `v7.12`（html + 配套 css 成对，共 6 件）按本索引体例**全部作为累积归档版**入 `chat-ux/archive/`（v7.12 起累积版按主题制归档，不再更新）；②HITL **主题单文件** `hitl-readonly.{html,css}` + `hitl-interrupts.{html,css}`（共 4 件，状态 `现役`）入 `chat-ux/` 主目录。版本登记口径（现役 / 归档 / 主题）取自仓外 `r163-demo/INDEX.md`；逐件溯源见下表、脱敏与改名见台账。
> 补合入（2026-10-18，本笔）：三类型 × 状态结构样张 `chat-ux/card-channels.html`（单件，状态 `定稿`）入 `chat-ux/` 主目录 —— 2026-10-18 用户直裁「demo通过」；来源 = 仓内新建（三类型批内产出，无仓外源件）；外链复用 `chat-ux/hitl-interrupts.css`，未另配同名 css；项目回落结果见 `docs/任务看板.md` 10-18。**版本补记（2026-10-18）**：HITL 左类型图标候选 A–H 比选后用户选定 **G**（Feather `help-circle`）并回落项目，`card-channels.html` 由 v2 升 **v3**（仅 HITL 左图标由盾牌改为圆圈问号，其余结构逐字不动；`data-review-status="approved"` 保留）。

## 目录结构

```
docs/demo/
├── README.md
├── chat-ux/            # AI 对话体验系（R-157 / R-159 / R-163 / R-165）
│   └── archive/
├── scroll/             # 滚动行为专项（R-161）
│   └── archive/
├── hitl/               # HITL 持久化与溢出（R-160）
│   └── archive/
└── batch-download/     # 批量下载（R-156 本轮无 HTML 原型，预留空目录）
    └── archive/
```

命名规约：`<轮次>-<主题>-<版本>.html`。主目录只放定稿 / 末版 / harness，其余版本一律 `archive/`；过程中的调试稿前缀 `_` 的写法在入库时改为语义化名（如 `_trace.html` → `r161-scroll-trace.html`）。
成对件规约（R-165 批起）：原型若外链配套样式表，样式表与 HTML **同名同目录成对入库**；入库改名时 HTML 内 `<link href>` 同步指向入库名（改名处见「入库纪律与脱敏台账」）。
主题单文件规约（2026-10-16 用户新规，2026-10-17 落库）：demo 改按**主题单文件**写 —— 一个主题一个文件 + 一份配套 CSS，互不叠加；**累积式超长版自 v7.12 起归档**（历史记录、不再更新），新裁决 demo 一律起新的主题单文件（命名 `<主题>.html` + `<主题>.css`，不再带轮次/版本号）。

## chat-ux/（AI 对话体验系）

| 文件 | 关联 issue/轮次 | 版本 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| `chat-ux/r157-generating-indicator.html` | R-157（AI 对话面板 UX 二批） | v1 | 过程稿 | 2026-10-13 | 「生成中」指示器视觉效果候选稿（原标题标注「待审视」，未落定稿，仅存参考） | 仓外卡件 `r157-ux2/决策卡-R157-定稿.md` | 仓外 `r157-ux2/指示器预览.html` |
| `chat-ux/r159-stream-issue13-v3.html` | issue #13 / R-159 | v3（定稿） | 定稿 | 2026-10-13 | 流式回复体验方案定稿：渐进时间戳 + 操作按钮紧邻时间戳 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13-v3.html` |
| `chat-ux/r163-chat-ui-v6.html` | R-163 | v6（合并前五版） | 定稿 | 2026-10-14 | AI 对话体验改造全场景定稿候选（15 场景 + 工具卡 9 态）—— **入库已脱敏**（工作区标签与样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/index.html` |
| `chat-ux/r165-chat-ui-v7.9.html` + `chat-ux/r165-chat-ui-v7.9.css` | R-165（视觉重做；源起 R-163 v6 纯 Tailwind 复刻） | v7.9（末版 · 回执原卡只读态收口 19/20/21/22） | 定稿 | 2026-10-16 | 双栏裁决版末版：22 点「左现状 ｜ 右改后」并排 + 主全场景面板（v7.8 的 18 点 + 新增 19~22）= 19 回执卡退役·原卡只读态 / 20 A-B 原始参数留否（定裁 B：完全不留 JSON）/ 21 思考过程展开体限高 160px 内滚 / 22 v7.8 `:967` 旧注订正 + 「查看详情」钮全稿清理。长内容审计 15 项 + 样张区 + 场景 19 原卡只读态五态；勾位区 2026-10-16 用户直裁**四条全过**（19 / 21 / 22 通过、20 裁 B）—— **入库已脱敏**（工作区标签改占位；样例用户名源件即 `demo-user` 占位）+ 外链改入库名 | 仓内 `docs/research/裁决-回执原卡只读态-2026-10-15.md`（用户直裁 19~22；追加节 2026-10-16） | 仓外 `r163-demo/v7.9.html` + `v7.9.css` |
| `chat-ux/archive/r159-stream-issue13-v1.html` | issue #13 / R-159 | v1 | 过程稿 | 2026-10-13 | 流式回复体验方案首版 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13.html` |
| `chat-ux/archive/r159-stream-issue13-v2.html` | issue #13 / R-159 | v2 | 过程稿 | 2026-10-13 | v2：操作按钮紧邻时间戳 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13-v2.html` |
| `chat-ux/archive/r163-chat-ui-v1.html` | R-163 | v1 | 过程稿 | 2026-10-14 | 对话体验改造首版 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v1.html` |
| `chat-ux/archive/r163-chat-ui-v2.html` | R-163 | v2 | 过程稿 | 2026-10-14 | 全场景铺开版 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v2.html` |
| `chat-ux/archive/r163-chat-ui-v3.html` | R-163 | v3 | 过程稿 | 2026-10-14 | 加代码高亮与 mermaid —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v3.html` |
| `chat-ux/archive/r163-chat-ui-v4.html` | R-163 | v4 | 过程稿 | 2026-10-14 | 工具卡与排版细化 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v4.html` |
| `chat-ux/archive/r163-chat-ui-v5.html` | R-163 | v5 | 过程稿 | 2026-10-14 | 赞踩互斥 + 提示 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v5.html` |
| `chat-ux/archive/r165-chat-ui-v7.html` + `chat-ux/archive/r165-chat-ui-v7.css` | R-165（源起 R-163 v6） | v7 | 过程稿 | 2026-10-15 | 纯 Tailwind 工具类复刻 v6 首版（全工具类 DOM + 外链编译产物 css，取代手写样式表路线）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v7.md` | 仓外 `r163-demo/v7.html` + `v7.css` |
| `chat-ux/archive/r165-chat-ui-v7.1.html` + `chat-ux/archive/r165-chat-ui-v7.1.css` | R-165 | v7.1 | 过程稿 | 2026-10-15 | 回执卡堆叠三处丑点修正稿 —— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v71.md` | 仓外 `r163-demo/v7.1.html` + `v7.1.css` |
| `chat-ux/archive/r165-chat-ui-v7.2.html` + `chat-ux/archive/r165-chat-ui-v7.2.css` | R-165 | v7.2 | 过程稿 | 2026-10-15 | R-165 S4 六项用户裁决并入静态 demo（常驻操作行 / 长消息折叠 / 间距对齐 v7 等）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v72.md` | 仓外 `r163-demo/v7.2.html` + `v7.2.css` |
| `chat-ux/archive/r165-chat-ui-v7.3.html` + `chat-ux/archive/r165-chat-ui-v7.3.css` | R-165 | v7.3 | 过程稿 | 2026-10-15 | 变更裁决版（裁决区 14 点铺开 + 主全场景面板）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v73.md` | 仓外 `r163-demo/v7.3.html` + `v7.3.css` |
| `chat-ux/archive/r165-chat-ui-v7.4.html` + `chat-ux/archive/r165-chat-ui-v7.4.css` | R-165 | v7.4 | 过程稿 | 2026-10-15 | 二轮裁决版（裁决项逐条改后形态定稿对照）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v74.md` | 仓外 `r163-demo/v7.4.html` + `v7.4.css` |
| `chat-ux/archive/r165-chat-ui-v7.5.html` + `chat-ux/archive/r165-chat-ui-v7.5.css` | R-165 | v7.5 | 过程稿 | 2026-10-15 | 双栏裁决版（每点「左现状 ｜ 右改后」双 panel 并排，逐栏可裁决）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v75.md` | 仓外 `r163-demo/v7.5.html` + `v7.5.css` |
| `chat-ux/archive/r165-chat-ui-v7.6.html` + `chat-ux/archive/r165-chat-ui-v7.6.css` | R-165 | v7.6 | 过程稿 | 2026-10-15 | 裁决区 04 长表格「纵向滚动条贯穿 sticky 表头」小修（其余 17 点逐字不动）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v76.md` | 仓外 `r163-demo/v7.6.html` + `v7.6.css` |
| `chat-ux/archive/r165-chat-ui-v7.7.html` + `chat-ux/archive/r165-chat-ui-v7.7.css` | R-165 | v7.7 | 过程稿 | 2026-10-15 | 终裁三项（06 GF 字段区 240px / 11 长消息折叠 184px / 12 mermaid 预览·源码双态限高 + 缩放平移复位）—— **入库已脱敏 + 外链改入库名**（见台账） | 仓外卡件 `r163-chat-ui/施工报告-demo-v77.md` | 仓外 `r163-demo/v7.7.html` + `v7.7.css` |
| `chat-ux/archive/r165-chat-ui-v7.8.html` + `chat-ux/archive/r165-chat-ui-v7.8.css` | R-165 | v7.8（尾批五点收口 04/15/16/17/18；v7.9 定稿后下沉 `archive/`） | 过程稿 | 2026-10-15 | 双栏裁决版**上一版定稿**，v7.9 起被末版取代：18 点「左现状 ｜ 右改后」并排 + 主全场景面板（18 点 = 已通过锁定 4 + 改后 14）；长内容限高族收口（工具卡输入 160 / 回执详情 200 / 表头 gutter 一体化 / 展开钮统一 / GF 提交后回执形态）—— **入库已脱敏**（工作区标签与样例用户名改占位）+ 外链改入库名（形态未改，仅移位） | 仓外卡件 `r163-chat-ui/施工报告-demo-v78.md`（用户逐轮直裁） | 仓外 `r163-demo/v7.8.html` + `v7.8.css` |
| `chat-ux/archive/r165-chat-ui-v7.10.html` + `chat-ux/archive/r165-chat-ui-v7.10.css` | R-165（累积版 v7~v7.12 族） | v7.10（**累积归档版**：工具卡通用渲染 + 全场景覆盖） | 归档 | 2026-10-16 | 累积版倒数第三版：ajv1 通用渲染规则（`resultView` 双通道数据契约 + 形状→元素五形态 + 18 工具逐一映射）+ ajv2 场景总表重建（A~G 族骨架 + 9 态 × kind2 矩阵）+ N1~N19 登记 / 订正；2026-10-16 用户直裁 **21 项全过**（通用渲染只做通用渲染、无专属卡）—— **入库已脱敏**（工作区标签 2 处改占位）+ 外链改入库名 | 仓内 `docs/research/裁决-回执原卡只读态-2026-10-15.md` + 仓内 `docs/evidence/施工报告-S6批1.md`（蓝本与落点） | 仓外 `r163-demo/v7.10.html` + `v7.10.css` |
| `chat-ux/archive/r165-chat-ui-v7.11.html` + `chat-ux/archive/r165-chat-ui-v7.11.css` | R-165（累积版 v7~v7.12 族） | v7.11（**累积归档版**：对话输入框重画） | 归档 | 2026-10-16 | 累积版倒数第二版：对话输入框重画（自适应 1 行 ≈40px → 上限 4 行 101px 内滚 / 可编辑白底 + 清晰边框 / 盒内工具栏钉底 / 圆形发送钮 / 两钮悬停 tip / Enter 三缺陷修复 / 运行中 Enter 排队）+ 窄栏口径（右侧窄栏 ≈400px，composer ≤ 面板高 15–20%）；v7.11 区块 **3 勾位待用户裁决**（①形态 ②流式可编辑行为变更 ③运行中 Enter 排队）—— **入库已脱敏**（工作区标签 2 处改占位）+ 外链改入库名 | 仓外 `r163-demo/INDEX.md`（版本登记）+ 本页「现役主题单文件」节 | 仓外 `r163-demo/v7.11.html` + `v7.11.css` |
| `chat-ux/archive/r165-chat-ui-v7.12.html` + `chat-ux/archive/r165-chat-ui-v7.12.css` | R-165（累积版 v7~v7.12 族） | v7.12（**累积归档版 · 末版**：HITL / GF 只读态纠偏） | 归档 | 2026-10-16 | 累积版**末版**（顶部挂归档横幅）：HITL / GF 只读态**纠偏** = 原样 UI 锁定渲染（GF 同一张表单六型控件切只读 + 值回填；门卡参数区原样 + 按钮整块消失 + 徽标上卡头；锁定不降对比度），纠 v7.9「label-value 清单」为误裁；7 张已决卡（②-h / ②-j / 16-a / 19-a~19-d）按新口径渲染，折出旧形态标注「已废弃（误裁）」—— **入库已脱敏**（工作区标签 2 处改占位）+ 外链改入库名 | 本页「现役主题单文件」节（`hitl-readonly.*` 同口径） | 仓外 `r163-demo/v7.12.html` + `v7.12.css` |
| `chat-ux/card-channels.html` | ai-example #3 三类型批（tool / HITL / form 卡头统一） | v3（`data-card-channels="2026-10-18-v3"`；主题单文件） | 定稿 | 2026-10-18 | 三类型 × 状态结构样张：左类型图标（扳手 / 圆圈问号（help-circle）/ 清单）· 右状态文字标签；会话 A 待处理 + 会话 B 已决回放；图标槽 bare / tile 双变体切换。**2026-10-18 用户直裁「demo通过」**；**v3（2026-10-18）**：HITL 左图标按用户选定 G（Feather `help-circle`）回落，由盾牌改为圆圈问号，其余结构 / 状态 / 切换脚本逐字不动。口径 = 双变体**仅供对照**，项目只落中性裸 SVG；项目保留中文 `toolLabel` 与 2A 状态色 / ✓ ✗ ! 字形；demo 表单静态 `disabled` **不移植**（项目待填 GF 保持可编辑）；**仅结构基准，不做像素级照搬**（配色 / 文案以项目已审口径为准）—— 零脱敏命中、无仓外源件 | 用户直裁 2026-10-18「demo通过」· 本批三类型回落；G 图标回落 = 用户选定候选 G 2026-10-18 | 仓内新建（无仓外源件） |
| `chat-ux/hitl-icon-candidates.html` | ai-example 二十六棒（HITL 左侧类型图标候选） | 候选 A–H（E–H 本轮重点；用户已选定 G） | 归档 | 2026-10-18 | HITL 左侧类型图标 A–H 候选：首屏同组实尺寸对照（参考 tool 扳手 / 生成式表单清单 + E–H），E–H 为 Feather 官方 `message-circle` / `message-square` / `help-circle` / `user`（各含真实待确认卡 + 56px 放大预览），A–D 保留为上一轮紧凑对照；仅 demo。**用户已选定 G 并回落，候选页保留原貌**（不再更新，未迁移双看板）；**归档特例，候选页冻结原位**（保留在 `chat-ux/` 主目录，不迁 `archive/`） | 用户 2026-10-18 口头意见「HITL 当前盾牌不合适，HITL 是询问」+「做 E 看看，多做几个图标；把 tool、生成式表单放一起看效果」；复选后用户选定 G 并回落 | 仓内新建（无仓外源件；E–H 路径取自 Feather 官方 SVG） |

### chat-ux/ 现役主题单文件（主题制，2026-10-17 起）

| 文件 | 关联 issue/轮次 | 主题 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| `chat-ux/hitl-readonly.html` + `chat-ux/hitl-readonly.css` | HITL 系（问题 2 批 2A 蓝本） | `主题` = HITL / GF 只读态与超时门卡纠偏 | **现役** | 2026-10-16 | 只读态与超时纠偏主题件（管线 `_hitl/`）：GF 已提交只读表单（原样控件 + 锁定态）/ 门卡已决四态 / 超时门卡纠偏（超时 = 已决态、按钮整块消失，不挂「等待确认」）；含落点·判据行与页尾 **9 勾位**（①GF 已提交只读态 ②门卡已决四态 ③超时门卡纠偏 ④按钮文字防折行 ⑤中危确认门动作四件 ⑥高危确认门 ⑦低危软确认 ⑧单选询问卡 ⑨多选询问卡，均未勾、待裁）—— 零脱敏命中、逐字节同源。**口径提示**：本件成于 2026-10-16，勾位 ⑤ 的「暂不处理 / 修改后批准」与 2026-10-17 型谱终裁不一致（裁决不采纳、挂账），施工以裁决件为准 | 仓内 `docs/research/裁决-HITL型谱与问题2方案-2026-10-17.md` §2 只读态口径 + 仓内 `docs/research/裁决-回执原卡只读态-2026-10-15.md` | 仓外 `r163-demo/hitl-readonly.html` + `hitl-readonly.css` |
| `chat-ux/hitl-interrupts.html` + `chat-ux/hitl-interrupts.css` | HITL 系（问题 2 批 2B/2C 蓝本） | `主题` = HITL 五类打断卡（CopilotKit 口径） | **现役** | 2026-10-16 | 五类打断主题件（管线 `_hitl5/`）：①Approve/Reject ②Edit, then continue ③Choose among options ④Provide missing input ⑤Retry with guidance，各「进行态 → 决策后只读 / 后续态」成对样张；统一中断卡结构（顶部风险 + checkpoint + 审计标注 / 中部拟执行动作·候选·缺字段 / 底部可审计决策按钮行）；页内真实交互（倒计时环 / 不执行理由必填 / 编辑改动标记 / 选项单选 / 稍后填写 / 幕1→幕2）；页尾 6 勾位（五类各一 + 统一结构一条）—— 零脱敏命中、逐字节同源 | 仓内 `docs/research/裁决-HITL型谱与问题2方案-2026-10-17.md` §1 型谱 + 仓内 `docs/research/调研-CopilotKit-HITL实现-2026-10-17.md` | 仓外 `r163-demo/hitl-interrupts.html` + `hitl-interrupts.css` |

## scroll/（滚动行为专项）

| 文件 | 关联 issue/轮次 | 版本 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| `scroll/r161-scroll-demo.html` | R-161 | v1（定稿） | 定稿 | 2026-10-13 | 滚动行为定稿方案（pinBottom 统一 / 回声闸 / 滞回阈值等八项） | 仓外卡件 `r161-scroll/决策卡-R161-定稿.md` | 仓外 `r161-scroll/demo-scroll.html` |
| `scroll/r161-scroll-m1-harness.html` | R-161 | v1 | harness | 2026-10-13 | M1 滚动验证 harness（非展示稿，仅供验证用） | 仓外卡件 `r161-scroll/决策卡-R161-定稿.md` | 仓外 `r161-scroll/m1-harness.html` |
| `scroll/archive/r161-scroll-trace.html` | R-161 | v1 | 过程稿 | 2026-10-13 | 滚动轨迹调试原型（过程稿） | 仓外卡件 `r161-scroll/决策卡-R161-定稿.md` | 仓外 `r161-scroll/_trace.html` |
| `scroll/archive/r161-scroll-trace2.html` | R-161 | v2 | 过程稿 | 2026-10-13 | 滚动轨迹调试原型 v2（过程稿） | 仓外卡件 `r161-scroll/决策卡-R161-定稿.md` | 仓外 `r161-scroll/_trace2.html` |

## hitl/（HITL 持久化与溢出）

| 文件 | 关联 issue/轮次 | 版本 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| `hitl/r160-hitl-issue14.html` | issue #14 / R-160 | v1（定稿） | 定稿 | 2026-10-13 | HITL 卡片持久化与文本溢出方案（文本溢出 **用户直裁方案 A**，本 demo 为事实源） | 仓外卡件 `r160-hitl/决策卡-R160-定稿.md` | 仓外 `r160-hitl/demo-issue14.html` |

## batch-download/（批量下载）

| 文件 | 关联 issue/轮次 | 版本 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| （本轮无 HTML 原型，预留空目录） | issue #9 / R-156 | — | — | — | R-156 批量下载修复批未产出 HTML 原型（决策卡 + 会审报告即依据） | 仓外卡件 `r156-batchdl/决策卡-R156-定稿.md` | — |

## 入库纪律与脱敏台账

- **入库方式**：全部 `cp` 自仓外原路径，仓外原件保留；除下表所列脱敏点外**逐字节同源**（`diff` 实测）。
- **脱敏台账**（DC-08，共 **6 件 / 7 处**，均为 R-163 批）：

| 文件 | 命中规则 | 原状（不落字面） | 改写后 |
|---|---|---|---|
| `chat-ux/r163-chat-ui-v6.html` | ⑤ 命名纪律（仓外工作区目录名） | 1 处，UI 上下文条的工作区标签 | `ai-example`（与其余 demo 的 `brand` 口径一致） |
| `chat-ux/r163-chat-ui-v6.html` | USER（个人信息 / 样例用户名） | 1 处，GF 回执样例 JSON 的 `user` 字段 | `demo-user` |
| `chat-ux/archive/r163-chat-ui-v1.html` | USER | 同上（1 处） | `demo-user` |
| `chat-ux/archive/r163-chat-ui-v2.html` | USER | 同上（1 处） | `demo-user` |
| `chat-ux/archive/r163-chat-ui-v3.html` | USER | 同上（1 处） | `demo-user` |
| `chat-ux/archive/r163-chat-ui-v4.html` | USER | 同上（1 处） | `demo-user` |
| `chat-ux/archive/r163-chat-ui-v5.html` | USER | 同上（1 处） | `demo-user` |

- 其余 9 件（r157 / r159×3 / r161×4 / r160）脱敏预扫**零命中**，逐字节同源入库。
- demo 内未发现盘符绝对路径与 `sk-` 类密钥字样（PATH / SECRET 规则预扫零命中）。
- **脱敏台账（R-165 demo 补合入批，2026-10-15，共 9 件 / 18 处；每件均为 ⑤ + USER 各 1 处，故按件合并为一行）**：

| 文件 | 命中规则 | 原状（不落字面） | 改写后 |
|---|---|---|---|
| `chat-ux/r165-chat-ui-v7.8.html` | ⑤ 命名纪律（仓外工作区目录名）+ USER（样例用户名） | 各 1 处：上下文条工作区标签 / GF 回执样例 JSON 的 `user` 字段 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.1.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.2.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.3.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.4.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.5.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.6.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |
| `chat-ux/archive/r165-chat-ui-v7.7.html` | 同上（各 1 处） | 同上 | `ai-example`；`demo-user` |

- 本批 18 件（9 html + 9 同名 css）：同名 css **零命中**、逐字节同源入库；HTML 除上表 18 处外零命中（命名纪律 / PATH / SECRET 规则预扫）。全批未发现盘符绝对路径与 `sk-` 类密钥字样。
- **入库改名（非脱敏，属命名规约）**：9 件 HTML 的外链 `<link rel="stylesheet" href="v7.X.css">` 各 1 处改为入库名 `r165-chat-ui-v7.X.css`（共 9 处）；除脱敏 18 处与改名 9 处外，HTML 逐字节同源（反向还原实测一致）。
- **脱敏台账（v7.9 合入批，2026-10-16，共 1 件 / 1 处）**：

| 文件 | 命中规则 | 原状（不落字面） | 改写后 |
|---|---|---|---|
| `chat-ux/r165-chat-ui-v7.9.html` | ⑤ 命名纪律（仓外工作区目录名） | 1 处，UI 上下文条的工作区标签 | `ai-example`（与其余 demo 的 `brand` 口径一致） |

- 本批 2 件（1 html + 1 同名 css）：同名 css **零命中**、逐字节同源入库；HTML 的 **USER 规则零命中**（GF 回执样例 `user` 字段源件即为 `demo-user` 占位，无需改写）；PATH / SECRET 规则预扫零命中（`sk-` 0 处、盘符路径 0 处，正文 3 处 `xx:/` 命中均为 `https://` 链接）。
- **入库改名（非脱敏，属命名规约）**：HTML 外链 `<link rel="stylesheet" href="v7.9.css">` 1 处改为入库名 `r165-chat-ui-v7.9.css`；除脱敏 1 处与改名 1 处外，HTML 与仓外源**逐字节同源**（实测仅 2 行差异，反向还原一致）。
- **本批落位**：`v7.9` 入 `chat-ux/` 主目录为末版定稿；`v7.8`（html + css）`git mv` 下沉 `archive/`，仓内文件内容未改（纯改名）。
- **脱敏台账（v7.10~v7.12 累积归档版合入批，2026-10-17，共 3 件 / 6 处）**：

| 文件 | 命中规则 | 原状（不落字面） | 改写后 |
|---|---|---|---|
| `chat-ux/archive/r165-chat-ui-v7.10.html` | ⑤ 命名纪律（仓外工作区目录名） | 2 处：UI 上下文条工作区标签 1 处 + 裁决正文引用该标签 1 处 | `ai-example` |
| `chat-ux/archive/r165-chat-ui-v7.11.html` | 同上（2 处） | 同上 | `ai-example` |
| `chat-ux/archive/r165-chat-ui-v7.12.html` | 同上（2 处） | 同上 | `ai-example` |

- 本批 6 件（3 html + 3 同名 css）：同名 css **零命中**、逐字节同源入库；HTML 除上表 6 处外零命中；**USER 规则零命中**（GF 回执样例 `user` 字段源件即 `demo-user` 占位，未改）；PATH / SECRET 零命中（盘符 0 处、`sk-` 0 处；`xx:/` 型命中均为 `https://` 链接）。
- **入库改名（非脱敏，属命名规约）**：3 件 HTML 的外链 `<link rel="stylesheet" href="v7.X.css">` 各 1 处改为入库名 `r165-chat-ui-v7.X.css`（共 3 处）；除脱敏 6 处与改名 3 处外 HTML 与仓外源**逐字节同源**（差异行实测 = 3 行 / 件：外链 1 行 + 工作区标签 2 行）。
- **主题单文件批（2026-10-17，共 4 件）**：`chat-ux/hitl-readonly.{html,css}` + `chat-ux/hitl-interrupts.{html,css}` —— ⑤ 命名纪律 / USER / PATH / SECRET **全零命中**，逐字节同源入库；文件名不改（外链自指同名 css，无需改名）。
- **本批落位**：`v7.10` / `v7.11` / `v7.12`（html + css 各三对）入 `chat-ux/archive/` 为**累积归档版**（v7.12 为末版，顶挂归档横幅）；`hitl-readonly` / `hitl-interrupts`（各一对）入 `chat-ux/` 主目录为**现役主题单文件**；`chat-ux/r165-chat-ui-v7.9.*` 本批未迁移、未回改（S6 批 1 施工蓝本，保持原位）。
- **三类型样张（2026-10-18，共 1 件）**：`chat-ux/card-channels.html` 为**仓内新建**（非仓外导入，无逐字节同源基线）；⑤ 命名纪律 / USER / PATH / SECRET **全零命中**，零脱敏；外链指向既有 `chat-ux/hitl-interrupts.css`，不新增样式表、无改名动作。

## 维护

- 新增 demo 一律按上表体例追加行，并同步分类主目录 / `archive/` 落位；状态取值 = 原三态（`定稿` / `过程稿` / `harness`）**或**主题制二值（`归档` / `现役`，见文件头「状态取值扩展」），不得再新增其它取值。
- 已入库 demo 的后续修订以新版本文件追加，不覆盖旧版（旧版下沉 `archive/`）。
- **主题制（2026-10-17 起）**：新裁决 demo 起新主题单文件（`<主题>.html` + `<主题>.css`），累积式版本一律不再更新、只归档；主题单文件**现役**长期维护，新主题另起文件、不叠加。
