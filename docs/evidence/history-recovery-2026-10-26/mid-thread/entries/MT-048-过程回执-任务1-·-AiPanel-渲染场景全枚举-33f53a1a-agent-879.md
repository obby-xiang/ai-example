<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-048 · 过程回执 · 任务1 · AiPanel 渲染场景全枚举（33f53a1a / agent-879）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`AiPanel.vue`、`generative_form`、`FormRenderer.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m26` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 4 条有正文），末条有正文消息为 `m49`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `33f53a1a` / agent `agent-879`（`main` = 主会话）；原字符 3,099（链内 1 段、合计 3,099）。
> **链号 / 类别**：`MT-048` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m49`（2,461 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

自证：agent-879 · modelAlias=`DeepSeek/deepseek-flash` · profile=explore · effort=high（wire.jsonl 首行 profile.bind 实测）

## 任务1 · AiPanel 渲染场景全枚举
路径：`frontend/src/components/AiPanel/AiPanel.vue`（行号=模板行）；块分派 `blocksOf`→`groupParts`（`utils/blocks.ts:86`，每段独立成块）
1. 收起竖排条 | !ai.expanded | 48px 竖条 | 3
2. 展开壳+头部(新建/收起) | else 分支 | 12
3. 上下文条(health tag/工作区/数据版本/contextSync) | 36,46
4. 顶部 Alert×6 | busyConflict 52 · poolSaturated 64 · !available 降级 74 · streamInterrupted 84 · notice 98 · lastUiEventResult 107
5. 历史骨架屏 | 无消息且 !historyLoaded | el-skeleton | 122
6. 空态+建议词 chip | 无消息且已加载 | 129
7. 用户消息 | role==='user' | 右对齐蓝气泡+人形头像+编辑钮 | 161,169,381；**仅单 text 段、无附件/图片**（`stores/ai.ts:1714` recordUserMessage）
8. 助手气泡 | 180
9. text 块 | kind==='text' | pre-wrap+末 8 字 CH_TAIL span.ch 渐显+caret(193)；showCaret=流式且为末尾 text 块(925)；完成后 300ms 淡出(754,355)
10. reasoning 块 | kind==='reasoning'&&text | 「思考过程▸」折叠+CollapsibleText(160) | 233
11. tool 卡 | kind==='tool' | 单卡；卡头=工具名+kind tag(前端工具/确认门)+状态 tag；v-show 折体 | 247-268
12. 卡内子项 | args(pretty)/result(已截断角标)/reason 红底 | 270,275,286
13. 状态枚举 9 态 | `types/ai.ts:179` | toolStatusLabel(860)：等待确认/调用中…/已成功/执行失败/已拒绝/超出当前范围/会话已失效/已取消/结局未知
14. 确认门待批 | requiresAction&&CONFIRM&&pending | 倒计时+拒因框+拒绝/确认，超时置灰 | 296
15. GF 表单宿主 | requiresAction&&FRONTEND&&generative_form | FormRenderer（六型控件/校验错/提交 loading/超时禁用/取消） | 318, `FormRenderer.vue:1`
16. receipt 回执块 | block.kind==='receipt'（已决 CONFIRM/GF，`blocks.ts:75`）| 蓝底只读：徽标+摘要+decidedAt+已提交值+原始参数 | 198
17. 元信息行 | 有 text 或助手流式中 | 「工作中…」呼吸→完成换时间戳+编辑/复制 | 349
18. 回到底部浮标 | 上滑且 has messages | 410
19. 输入区 | textarea+运行态+停止/发送 | 422
错误/中止：**无独立错误气泡**；error 帧→notice+顶部 Alert(`stores/ai.ts:1976`)；断流→streamInterrupted；done.cancelled→「本轮已取消」
时间戳 R-162 | `utils/format.ts:75` | 今天 HH:mm／昨天／近 7 天周X／同年 M月D日／跨年 YYYY年M月D日；无效→空串不渲染 | 365,204

时序结论：`parts` 是数组、帧序 append、seg 单调递增（`parts.ts:29,44`），text/reasoning/tool 原地交错保序；groupParts 依 parts 序出块且 activity 恒单段块，AiPanel 直按块序渲染 ⇒ **渲染侧可直接按序渲染**，无需重排。

## 任务2 · 依赖版本+license
| 包 | 最新 | license | 用途 | 周下载 |
|---|---|---|---|---|
| markstream-vue | 2.0.19 | MIT | Vue 流式 MD 渲染 | 23,007 |
| markdown-it | 15.0.2 | MIT | 可插拔 MD 解析 | 28,802,285 |
| dompurify | 3.4.17 | (MPL-2.0 OR Apache-2.0) | HTML/SVG XSS 净化 | 71,623,117 |
| shiki | 4.5.0 | MIT | 语法高亮 | 25,919,718 |
| highlight.js | 11.12.0 | BSD-3-Clause | 高亮+自动识别 | 39,033,189 |
| mermaid | 12.2.0 | MIT | 图表渲染 | 18,782,236 |
| katex | 0.19.0 | MIT | 数学排版 | 29,655,764 |
| marked | 18.1.1 | MIT | MD 解析 | 86,429,242 |
| vue-markdown-render | 2.3.1 | MIT | markdown-it 的 Vue 封装 | 78,450 |

markstream-vue 直接依赖（均 MIT）：`markstream-core@2.0.19`(17,770 周下载)、`@floating-ui/dom@^1.8.0`、`@chenglou/pretext@^0.0.9`、`stream-markdown-parser@1.2.20`。全部仓库在 GitHub（Simon-He95/markstream-vue、markdown-it/markdown-it、cure53/DOMPurify、shikijs/shiki、highlightjs/highlight.js、mermaid-js/mermaid、KaTeX/KaTeX、markedjs/marked、cloudacy/vue-markdown-render）。**无 GPL/AGPL**；唯一非纯宽松为 dompurify 双许可（Mozilla 2.0 或 Apache-2.0，可择后者）。

（合规提示：`marked`/`markdown-it`/`vue-markdown-render` 均含 HTML 透传能力，若最终采用须搭配 dompurify 白名单净化；markstream-vue 周下载仅 2.3 万，供应链关注度低、维护者单一。）
