复核方评议完毕。以下问题均已对照仓库实码（`<REPO_ROOT>`）与 demo v6（`r163-demo/index.html`）逐一核验，非凭草案字面。

---

# R-163 决策卡草案 · 评议（DS V4 Pro）

**总评：方向成立、范围清晰，但有 3 条 MAJOR 必须在定稿前闭合（赞踩前提失实、DOMPurify 无实际口径、验收标准不可测）。按 MAJOR 全闭合后可立项，否则施工棒必踩空。**

## MAJOR（3 条，定稿必修）

**M1 · DOMPurify 白名单只有承诺、无实际口径；且「永不 v-html」松弛是一次安全策略变更**
- 草案写「白名单标签/属性集须文档化」，但没给集合。风险「过宽→XSS / 过窄→残缺」全程悬空，这正是你自己点名的评议重点，却未落一个字。定稿必须给**具体集合**：允许 `p/h1-6/ul/ol/li/table/thead/tbody/tr/th/td/code/pre/blockquote/em/strong/del/a/hr/br` + highlight.js 的 `span.class="hljs-*"` + `code.class="language-*"` + mermaid 的 `svg` 族；属性仅 `a.href`（走 DOMPurify 默认协议过滤）+ `class` + `img.src/alt`（若允许图）；明确禁止 `style/on*/iframe/script/表单控件`（task-lists 已排除，`input[checkbox]` 不进白名单）。
- 「全仓永不 v-html」是一条安全纪律（实锚 `components/AiPanel/FormRenderer.vue:12` 与 `types/form-schema.ts:9` 的注释 + 该文件本身无 v-html 实现）。本次松弛**不是决策卡一句话能改的规则**，需单列安全评审签核，并写明松弛面仅限「AiPanel 正文链路 = markdown-it 产出 → DOMPurify → v-html」，其余面（含 FormRenderer）永不 v-html 不变。
- 引用路径有偏差：草案写 `FormRenderer.vue:12,111`，实际在 `frontend/src/components/AiPanel/FormRenderer.vue`（缺 `AiPanel/` 前缀）；且该处「永不 v-html」是**字段文案安全边界**，本专项不该碰，草案措辞易被误读成「FormRenderer 也要开 v-html」。

**M2 · 赞踩「前端已有图标+本地态」前提失实，且与第 4 项自相矛盾；闭环四要素全缺**
- 实测：AiPanel.vue 元信息行当前只有**复制+编辑**两按钮（`copy-msg`/`edit-msg`），图标 import 仅 `ArrowRight/ChatDotRound/Operation/Promotion/WarningFilled`，全仓 `frontend/src` **无任何 thumbs/赞/踩/rating/feedback**。R-159 立项时明确「赞/踩反馈 = 范围外，转挂账待裁决」（`docs/M2-排期计划.md` R-159 行原文）。
- demo v6 里的赞/踩只是**静态 HTML 示意**（`index.html:712-715,749` 的 SVG），不是真实前端代码。草案第 7 项「前端已有」把 demo 示意与真实代码混淆——照此施工会直接漏做前端赞/踩。
- 卡内自相矛盾：第 4 项把「图标操作（复制/赞/踩）+ 赞踩互斥可取消」列为**新做**，第 7 项又说「前端已有」。必须改：第 7 项 = **纯后端**（上报端点+落库+查看界面），前端图标/本地态/互斥/取消归第 4 项交付并交叉引用。
- 「闭环」四要素一条都没写：**①API 契约**（路径/方法/请求响应体/幂等/租户隔离）；**②落库 schema**（messageId+sessionId+rating+时间戳+可选 reason；重复投票是 upsert 还是追加？取消 = DELETE 还是置 0？）；**③查看界面**（全新前端面，入口在哪/谁能看/列哪些列，demo 不体现且零设计）；**④消费方**（数据喂给谁，否则只是「落库」不是「闭环」）。一句话撑不起一个后端端点 + 一个前端面。

**M3 · 验收标准不可测**
- 「demo v6 全场景（24 项）」：demo 实际是**「场景覆盖清单 15 项」+「工具卡 9 态」= 24**，但草案未枚举、未锚定该分解（`index.html:770-786` 有 15 项清单）。验收必须逐条列出 15 场景 + 9 态，否则「24 项」是魔法数字。
- 「前端 **291+** 全绿」：`+` 无上下限。草案自述 7 处 stream-caret + 2 处 whitespace-pre-wrap 断言**须改写（会减既有）**，又新增 markdown/白名单/赞踩用例（会增），净计数无预期值 → 不可测。应定「改写后 291 + N 新例（N=具体数）」。
- 「脱敏终扫 0 硬命中」：未定义「脱敏终扫」是哪个工具/哪条门禁，缺锚点，不可测。
- 「赞踩上报-落库-**查看界面**链路 E2E 走通」：查看界面未定义 → 该条不可验收。
- 「「继续」按钮三态可触发」这条相对可测，保留。

## MINOR（6 条，建议定稿吸收）

**m1 · 流式重渲染方案过薄**：30-60ms 节流 + 围栏降级不够。markdown-it 是**非增量**解析器，每次节流对**整条消息全量重解析 = O(n²)**；叠加「正文不截断」+ 400px 栏长 JSON 墙，是最重的组合。需补：只重渲染**未闭合尾部块**、已完成块 DOM 冻结/块级 memo 不重解析；并给长文（>10k char）降级阈值与重渲染耗时预算。

**m2 · mermaid 兜底无触发条件 + 安全配置缺失**：「渲染失败」未定义（render error / 超时几 ms / 非法语法？）；流式期 mermaid 不可增量，围栏闭合前应保持源码块、闭合后一次性渲染——需明示。安全面：需 `securityLevel:'strict'` + `htmlLabels:false` + `suppressErrorRendering:true`，输出 SVG 再过 DOMPurify（SVG profile）；并核 mermaid@11.16.1 对 dompurify@3.4.14 的 peer 版本兼容（草案已锁 3.4.14，须确认 11.x 接受 3.x）。

**m3 · chSplit/CH_TAIL 退役是 R-159 语义回归面**：`chSplit`/`CH_TAIL`(:787-798) 逐字符 `span.ch` 与 caret(:925) 绑定，7 处 stream-caret 测试依赖它。渲染层重做后 R-159「新字符渐显」语义将**消失**，需在定稿显式声明「渐显语义被 Markdown 渲染取代」，并同步改写 R-159 相关 spec 口径，不是静默丢。

**m4 · 工具卡摘要映射表不完整**：给了 9 态 + 1 个模板（str_replace_editor），未枚举**工具全集**与逐工具动宾模板；「失败/未知工具兜底=工具名+状态」需先给出工具清单（web_search / str_replace_editor / … 前端工具全集），否则施工只能猜。

**m5 · 「持久化不动」与赞踩落库自相矛盾**：§三.4 说「Redis/DB parts[] 维持现状、DB 升级挂账」，但第 7 项赞踩要「后端落库」= 新增持久化面。措辞需分列：parts[] 不动，赞踩是 net-new 表/集合，二者不冲突。

**m6 · 引用行号偏差（低危）**：`parts.ts:29,44` 是 `nextSeg`/`openStreamPart`，非「保序」锚（保序是 parts 数组顺序 + seg 单调的隐含性质）；「291/291·21 spec」是 R-162 现状口径，与本批断言改写后的目标计数应分开表述。复核方照草案锚点会踩。

## NIT（2 条）

- **n1**：chSplit 退役后，`stream-caret` / `span.ch` 相关 testid 若再无消费方须清理，避免死 testid（可并入 m3）。
- **n2**：本批引入赞踩后端，但未写后端红线。建议明确「本批后端改动**仅限**赞踩上报端点，其余后端禁改」，否则「前端为主」边界被撑破。

---

**结论建议**：M1/M2/M3 三条约需在定稿卡逐条闭合（尤其 M2 的「前端已有」必须改为「前端属第 4 项交付」+ 补闭环四要素；M1 须给白名单实际集合；M3 须锚定 15 场景+9 态清单与「291+N」具体 N）。闭合后可立项；现草案状态不建议直接开工。
> 入库脱敏（DC-08 · 提交官 2026-10-15）：仓根盘符路径 → `<REPO_ROOT>`；仓外盘符路径 → 仓外相对口径（去 `<SCRATCH>` 前缀）；密钥/令牌 0 命中（`sk-` 扫描 0）；其余逐字节同仓外源。
