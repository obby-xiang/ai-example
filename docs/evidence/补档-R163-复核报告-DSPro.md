# 复核报告 R-163 · AI 对话体验专项（DSPro 复核棒）

- **DC-19 自证**：wire `agents/main/wire.jsonl` profile.bind 单行实证 `"modelAlias":"DeepSeek/deepseek-v4-pro"`（会话 `session_542af0cf-aa5c-4b44-951b-8540cb669bfc`，52 次命中）。
- **锚定**：复核锚 diff sha256 `78a5d97e…58ef5f`（17 文件 2162+/107-，HEAD=f468579 未 commit，工作树与 diff 一致）。定稿 sha256 `6fe5f389…bf0a` 校验通过。
- **结论：打回**（1 MAJOR 须收口修；3 MINOR / 3 NIT 随批；第 5/6 项裁决见末）。

## 门禁实跑（新鲜复跑，非照抄）
- 前端 **303/303** ✓（21 files 实跑全绿，12.95s；restore-reconcile 负例①实测 7598ms，超原 5s 默认）。
- 后端 **500/500** 未能重跑（本机 `mvn` 不在 PATH，仅 `target/test-classes` 已编译）；以「赞踩 4 例已读 + 后端 diff 最小 + 字段注入不碰构造器」代证，标注为未复跑项。
- package.json 四件套**精确 pin**（无 ^）：markdown-it@15.0.0 / dompurify@3.4.14 / highlight.js@11.11.1 / mermaid@11.16.1，无 shiki/markstream-vue/markdown-it-task-lists ✓。katex@^0.16.45 / marked@^16.3.0 为 mermaid 锁层**传递依赖**（package.json 无 app 级声明）——非「KaTeX 公式」功能，判「未并入暂缓」成立 ✓。

## MAJOR（1 条，收口修）
**M1 · 断流 Alert 回归：`streamInterrupted` 被改死，恒 false**
- 落点：`frontend/src/stores/ai.ts:1044 / 1122 / 1279` 三处断流路径把 `this.streamInterrupted = true` **替换**为 `this.lastRoundOutcome = 'interrupted'`（应为「并列补充」而非「替换」）。
- 后果：`streamInterrupted` 仅剩 4 处 `= false`（:1014/1095/1245/1702）+ 声明，**永不再 true** ⇒ `AiPanel.vue:85` 断流 `<el-alert v-if="ai.streamInterrupted">` 与 `:686` `noticeType` 的 error 档（`if (ai.streamInterrupted) return 'error'`）双双失效。
- 反证：施工报告验收对照「1 断流 Alert（既有）✓」**失实**；spec 无任何 `streamInterrupted`/断流 Alert 断言（仅 T4「继续」按钮，:1666），故 303 全绿未拦住。
- 修法：三处改为 `this.streamInterrupted = true` + `this.lastRoundOutcome = 'interrupted'` 并列，并补一条「断流置位 → Alert 渲染」回归例。

## MINOR（3 条）
- **m1 · 查看界面「消息摘要」= ID 片段而非正文摘录**：`AiFeedbackView.vue:31-33` `summaryOf` 返回「会话 {sessionId前8} · 消息 {messageId前8}」；定稿 §三.4③「消息摘要」+ 消费方「人工复盘」需要看到**被评价消息内容**，ID 片段无法支撑复盘。schema 未存正文摘录。建议补 `message_excerpt` 列，或明示「摘要=ID 片段」口径。
- **m2 · markdown.ts:85 stale 注释与代码矛盾**：JSDoc 写「html:false（双保险）」但实际 `html: true`（偏差 #2 自述 html:false→true）。安全敏感行注释反向误导审计，须同步。
- **m3 · 性能耗时预算 RENDER_BUDGET_MS=24 零实测**：jsdom 未触发降级（偏差 #3 自述），仅 `DEGRADE_AT_CHARS=10000` 长度阈值被实测；24ms 预算路径无测试覆盖，留未测面（可接受，但登记）。

## NIT（2 条）
- **n1**：`api/ai.ts:feedbackList(limit=100)` 传 `?limit=` 但后端 `@GetMapping("/feedback")` 恒硬编码 `findTop100…`，参数死参（定稿即固定 100，可接受，建议后端读参或前端删参）。
- **n2**：定稿 §三.3 标签清单未显式列 `img`（属性表却有 `img.src/alt`），代码 `ALLOWED_TAGS` 含 img（27 项）——spec 与代码 1 处不对称，代码正确，建议定稿补 `img` 字面。
- **n3**：工具全集盘点计数不准——施工报告 T0「后端 @Tool 14 + 前端 15 = 29 名」，实测 `AiTools.java` `@Tool(name=…)` = **18**（非 14），前端 `frontend-tools.ts` `name:` 仅 4。`summaryFor` 覆盖全部 18 后端名无缺漏（映射本体完整，非凭猜），仅报告计数偏差，留痕即可。

## 第 5 项 · 施工报告三处笔误逐项裁决
| 项 | 裁决 | 理由 |
|---|---|---|
| 5a 落点清单漏点名 5 修改文件（App.vue/router/index.ts/api/ai.ts/stores/ai.ts/ai-panel-parts-render.spec.ts） | **留痕即可** | 5 文件均在 diff 内交付，仅报告未枚举；无代码/交付缺口 |
| 5b 「8 新文件」实为 7 新 + 2 改（package.json/yarn.lock 为改） | **留痕即可** | 计数笔误，不影响 ls-files=1103 口径 |
| 5c 白名单「22 种」实为 27 项 | **留痕即可** | 代码 ALLOWED_TAGS 实测 27 项，与定稿/评议 M1 一致；报告数字笔误（安全相关，建议收口 docs 更正） |

## 第 6 项 · 偏差五项逐项裁决
1. restore-reconcile 3 例 5s→15s：**可接受**。断言零改动，仅超时预算放宽；实测负例① 7598ms 确超原默认，非放水。
2. markdown-it html:false→true：**可接受**（定稿 §三.3 本意 + xss 用例 script/onerror/javascript: 零命中实测）；但须修 m2 的 stale 注释。
3. 性能口径（memo+帧批量+hljs memo）：**可接受**；24ms 预算未测见 m3。
4. yarn.lock npm 误改→回滚→yarn 1.22 重生（+807 行四件套及传递依赖）：**可接受**。
5. mermaid 浏览器端异步（jsdom 不测渲染本体）：**可接受**（测占位/源码块/净化口径合理）。

## 红线核验（全过）
后端仅限赞踩端点（AiController 只加 feedback 两法+新包/迁移/测试，构造器签名不变 ✓）；FormRenderer.vue:12 永不 v-html 未触碰（不在 17 文件 ✓）；parts[] 持久化不动 ✓；无多会话、13 暂缓未并入 ✓；工具全集映射落 `tool-summaries.ts`（实测覆盖 AiTools.java 全部 18 @Tool 名 + 前端别名，兜底=名+状态；报告计数「29/后端14」偏差见 n3）✓；T1 唯一链路 + 尾块重渲 + 块级 memo 落实 ✓；赞踩闭环四要素（POST/GET 契约、ai_feedback 幂等 upsert、设置页查看界面、人工复盘消费方）全落地 ✓。
