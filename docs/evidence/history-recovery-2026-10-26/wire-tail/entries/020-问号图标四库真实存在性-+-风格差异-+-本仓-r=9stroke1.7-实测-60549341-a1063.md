> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1063` 的 wire 末条 assistant 文本（消息序号 m=32；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-18 12:35 → 2026-10-18 12:43（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：11385 B / 8137 字符 / 95 行；行指纹覆盖率（本批复算）2.8%（最高单文件 3%：`docs/任务看板.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：调研）
> - 与既有件关系：—
> - 脱敏：路径 2 / 会话标识 1 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
**自证**
已读 `<KIMI_SESSIONS>` 的 `profile.bind`：`agentId=agent-1063`、`profileName=explore`、`modelAlias=DeepSeek/deepseek-flash`、`thinkingEffort=high`、`cwd=<LOCAL_WORK>`。只读调研；未改文件、未起停服务。

**结论（建议，非事实）**
选 **A「圆圈问号」**，并保留项目现有的 `r=9 / stroke 1.7` 微调；不要把 Lucide 官方 24px 的 `r=10 / stroke 2` 原样缩到 15px。B 只在“图标本身表达回答/回复动作”时用；C 淘汰；D 淘汰。

## 事实

### 1. 各库真实存在情况

以下名称与路径均实际请求过官方页面/原始 SVG；404 也按 404 记录。

| 库 | 圆圈+? | 气泡+? | 裸 ? | 官方名称与链接 |
|---|---|---|---|---|
| Lucide | 有 | 有（圆形气泡） | 无 | [`circle-question-mark`](https://lucide.dev/icons/circle-question-mark)、[`message-circle-question-mark`](https://lucide.dev/icons/message-circle-question-mark)；`message-square-question*` 均 404 |
| Heroicons | 有 | 无 | 无 | [`question-mark-circle`](https://heroicons.com/24/outline/question-mark-circle)；`chat-bubble-*` 家族没有问号变体 |
| Material Symbols | 有 | 有（方形/圆形两种） | 有 | [`help`](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:help)、[`live_help`](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:live_help)、[`contact_support`](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:contact_support)、[`question_mark`](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:question_mark) |
| Ant Design Icons | 有 | 无 | 有 | [`QuestionCircleOutlined`](https://raw.githubusercontent.com/ant-design/ant-design-icons/master/packages/icons-svg/svg/outlined/question-circle.svg)、[`QuestionOutlined`](https://raw.githubusercontent.com/ant-design/ant-design-icons/master/packages/icons-svg/svg/outlined/question.svg)；`CommentOutlined` 无问号 |
| Fluent UI System Icons | 有 | 有（单/双气泡） | 有 | [`Question Circle`](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Question%20Circle/SVG/ic_fluent_question_circle_24_regular.svg)、[`Chat Help`](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Chat%20Help/SVG/ic_fluent_chat_help_24_regular.svg)、[`Chat Bubbles Question`](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Chat%20Bubbles%20Question/SVG/ic_fluent_chat_bubbles_question_24_regular.svg)、[`Question`](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Question/SVG/ic_fluent_question_24_regular.svg) |
| Carbon | 有 | 无 | 无 | [`help`](https://raw.githubusercontent.com/carbon-design-system/carbon/main/packages/icons/src/svg/16/help.svg)（仅 16/32）；`chat` 仅 32 且无问号 |

补充边界事实：
- Lucide 的 `circle-help` 网页会 **308** 跳到 `circle-question-mark`；但 `circle-help.svg`、`help-circle.svg` 均 404。
- Lucide `message-square-question.svg` 与 `message-square-question-mark.svg` 均 404。
- 没有任何库提供字面名为 `chat-bubble-question-mark` 的图标。
- Material `live_help` 是近方形气泡+?，但它是单件图标，不是 `message-square-question` 家族；`contact_support` 是圆形气泡+?，`support_agent` 才是耳机人形。
- Ant `CustomerServiceOutlined` 是耳机人形，不是问号气泡。
- Fluent 还有 `Person Question Mark`、`Shield Question`，分别是账户/安全语义，不要误当 HITL。

### 2. 主流造型

- **圆圈问号是唯一跨库通用形：6/6。** 同一造型在不同库叫 `help`、`question-circle`、`question-mark-circle`、`Question Circle`、`circle-question-mark`。命名在“帮助”和“问题”之间分裂，但轮廓高度一致。
- **气泡问号是第二惯例：3/6**（Lucide、Material、Fluent）。语义集中在 chat / live support / ask，不是“待确认”专用。
- **裸问号是少数派：3/6**（Material、Ant、Fluent）。Lucide、Heroicons、Carbon 都没有裸问号；它更像排版符号/未知值，而不是一个完整的类型标记。
- **方形气泡问号不是行业家族：0/6**。最接近的是 Material `live_help`，但它是单件近方形气泡，不是通用的 `message-square` 家族。
- **没有库提供“待用户回答 / 待确认 / 人工审批”专用图标。** 这六个库都把相关图形归在 help / question / chat / support 下；“待确认”必须由文字、颜色或右侧状态胶囊承载。
- 对勾、盾牌、人形在这六个库里分别是完成/通过、安全、账户/支持语义；不应拿来当“等待回答”。

### 3. 风格差异（24px 基准）

| 库 | 画布/渲染 | 线宽/线帽 | 外框与留白 |
|---|---|---|---|
| Lucide | 24；stroke、`fill=none` | 2px；圆帽/圆角；开放路径全圆帽 | 圆环 `r=10` + 2px 描边 → 外径 22/24（91.7%），边距 1px；规范要求至少 1px 安全区 |
| Heroicons | 24 outline；20/16 为 solid | 1.5px；圆帽/圆角 | `r=9` + 1.5px → 外径 19.5/24（81.3%），边距 2.25px；最透气 |
| Material Symbols | 24dp；单路径 fill；weight 100–700（400 默认）、fill 0–100、opsz 20–48 | 无 stroke；线宽由 weight 轴控制 | 圆环 800/960（83.3%），边距 2dp；仅 20/24px 完全对齐像素网格 |
| Ant Design | 1024 画板；fill；64px bleed | 无 stroke；线宽 72/1024 ≈ 1.69px at 24 | 圆环 896/1024（87.5%），边距 1.5px at 24；几何、直角小圆角 |
| Fluent UI | 24（另有 16/20/28/32/48）；fill-outline | 无 stroke；regular 以填充路径模拟描边，圆角终端 | 圆环 20/24（83.3%），边距 2px；16px 版 14/16（87.5%），边距 1px |
| Carbon | 16/20/24/32；fill | 无 stroke；16px 为 1px 线，32px 为 2px | 圆环 14/16（87.5%），边距 1px；偏方正、几何 |

缩到 15px 后的线宽/边距约值：
- Lucide 官方：**1.25 / 0.63px**
- Heroicons：**0.94 / 1.41px**
- Ant：**1.05 / 0.94px**
- Carbon：**0.94 / 0.94px**
- Fluent 16px 版：**约 1 / 0.94px**
- 项目 A：**1.06 / 1.34px**

项目 A 比官方 Lucide 明显更透气，线宽几乎与 Ant 一致，边距接近 Heroicons。Lucide 自己的在线预览器把尺寸限制在 **16–256px**，15px 属于非标尺寸，直接缩官方图会有光学风险。

### 4. 本项目实测（非行业来源）

`<LOCAL_WORK>\_cu-hitl-icon-r26\measurements.json:137`：15px 卡头 ink 为 A `13×13/72px`、B `13×13/71px`、C `13×12/81px`、D `7×11/20px`。四款统一 `24 viewBox / stroke 1.7 / 圆帽圆角`（`hitl-icon-candidates.html:399`），扳手与清单也是同一规格（`card-channels.html:62`、`:163`）。

## 建议（调研者）

1. **首选 A（圆圈问号），保留现有 `r=9 / 1.7` 几何。**
   - 6/6 的跨库通用形，识别成本最低。
   - 15px 下只有“圆环 + 问号 + 点”三层，没有尾巴和四个角；ink 72px，与 B 的 71px 基本持平，但轮廓更简单。
   - 圆环是封闭的“类型标记”边界，和清单的矩形、扳手的斜线形成形状区分；C 的方形气泡会与清单的矩形重复。
   - 卡片本身已在对话里，B 的气泡是重复语境；A 的问号才补充“这条需要回答”的缺失信息。

2. **不要把官方 Lucide `circle-question-mark` 原样缩到 15px。**
   官方 `r=10 / 2px` 在 15px 下外径约 13.75px、线宽 1.25px、边距仅 0.63px；项目 A 是 12.3px、1.06px、1.34px。官方图会更重、更贴边，容易压过扳手/清单。现有 A 实际上已经把 Lucide 造型调成了更接近 Heroicons/Ant 的 15px 光学版本。

3. **B（圆形气泡问号）只在“可点的回答/回复动作”场景用。**
   它的语义比 A 更接近“询问”，但尾巴在 15px 下最先糊；而且当前官方 `message-circle-question-mark` 的气泡更大、尾部更复杂，比项目 B 的简化路径更重。若选 B，建议保留项目现成的 `r=9 / 1.7` 版本，并在 1x 屏实拍确认尾巴不结块。

4. **C（方形气泡问号）淘汰。**
   `message-square-question` 不是 Lucide 图标，两个候选文件名均 404；Heroicons、Ant、Carbon 也没有对应家族。它 15px 的 ink 最高（81px），四个角 + 尾巴 + 问号在同尺寸下最密，而且方形轮廓与“清单”重复。

5. **D（裸问号）淘汰。**
   15px 下 ink 仅 `7×11`，远轻于同组 13×13 的扳手/清单；没有外框，容易被读成标点或“缺失值”，而不是一个类型标记。裸问号只在 Material/Ant/Fluent 作为通用 question 存在，不是 HITL 惯例。

6. **语义缺口要用文字补。**
   圆圈+? 在行业里主要叫 help，不是 pending。作为左侧“类型图标”成立；若把它单独当状态灯，会被读成帮助入口。项目右侧已有“待确认”胶囊，所以 A 的语义由文字补齐。若产品要求“零文字也能看懂这是要用户回答”，B 更接近，但 15px 光学上更差——这是产品取舍，不是图标本身能同时解决的。

**给主指挥官的最终一句：推荐 A（圆圈问号），保留现有 15px 微调；B 作为“回答动作”的备选，C/D 淘汰。**

**官方来源**
- Lucide：[circle-question-mark](https://lucide.dev/icons/circle-question-mark)、[message-circle-question-mark](https://lucide.dev/icons/message-circle-question-mark)、[设计规范](https://lucide.dev/contribute/icons/specification)、[原始 SVG](https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/circle-question-mark.svg)
- Heroicons：[question-mark-circle](https://heroicons.com/24/outline/question-mark-circle)、[仓库](https://github.com/tailwindlabs/heroicons)、[原始 SVG](https://raw.githubusercontent.com/tailwindlabs/heroicons/master/src/24/outline/question-mark-circle.svg)
- Material Symbols：[help](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:help)、[live_help](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:live_help)、[contact_support](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:contact_support)、[question_mark](https://fonts.google.com/icons?selected=Material+Symbols+Outlined:question_mark)、[仓库](https://github.com/google/material-design-icons)
- Ant Design：[图标库](https://ant.design/components/icon/)、[图标规范](https://ant.design/docs/spec/icon)、[question-circle SVG](https://raw.githubusercontent.com/ant-design/ant-design-icons/master/packages/icons-svg/svg/outlined/question-circle.svg)
- Fluent UI：[图标清单](https://github.com/microsoft/fluentui-system-icons/blob/main/icons_regular.md)、[Question Circle SVG](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Question%20Circle/SVG/ic_fluent_question_circle_24_regular.svg)、[Chat Help SVG](https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Chat%20Help/SVG/ic_fluent_chat_help_24_regular.svg)
- Carbon：[图标库](https://carbondesignsystem.com/elements/icons/library/)、[help 16 SVG](https://raw.githubusercontent.com/carbon-design-system/carbon/main/packages/icons/src/svg/16/help.svg)、[chat 32 SVG](https://raw.githubusercontent.com/carbon-design-system/carbon/main/packages/icons/src/svg/32/chat.svg)
