<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-258 · 过程回执 · 构建方式（摸查结果 + 实际做法）（cbdb20ea / agent-960）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`docs/demo`、`chat-ux`、`scripts/ci`、`check-docs`。
> **稿态**：历史中间稿 —— 该 wire 第 `m127` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt12` 条有正文的 assistant 消息（共 33 条有正文），末条有正文消息为 `m435`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `cbdb20ea` / agent `agent-960`（`main` = 主会话）；原字符 2,654（链内 1 段、合计 2,654）。
> **链号 / 类别**：`MT-258` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 3 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `12%`。
> **与末条归档（138 件）的关系**：末条 `m435`（2,485 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

F7 残留已闭环：CSS 重建完成、限高内滚真正生效、全量断言全绿、已入库推送。

## 构建方式（摸查结果 + 实际做法）

- **原产物的生成方式**：`git log -- docs/demo/chat-ux/hitl-interrupts.css` 只有一次合入（`841e321`），仓内没有随附的 tailwind 配置或构建脚本；产物是**纯 Tailwind 输出**（唯一非类规则是 preflight，`ro-bd/ro-chev` 也只是 `[&.is-open_.ro-bd]:block` 这类任意变体，非手写组件类）。逐字节特征显示它带**自定义主题 + 自定义 base 层**（中文友好字体栈、6px 滚动条五条、`@supports` 兜底），而 `frontend/tailwind.config.js` 是关 preflight 的应用配置，因此**不能直接复用该配置**。
- **实际工具链**：复用 `frontend/node_modules` 里的 **tailwindcss 3.4.19**（与 frontend `^3.4.17` 装出的版本一致），`node .../tailwindcss/lib/cli.js -c <临时 config> -i <临时 input.css> -o out.css --minify`。未装任何新依赖（因此没动 npm 镜像）。
- **配置复原**：`theme.extend.fontFamily.sans / mono` 按旧产物逐字复原（`-apple-system,…,PingFang SC,…,sans-serif` / `Cascadia Mono,…,monospace`）；输入 CSS 复原既有 base 层（`::-webkit-scrollbar` / `-track` / `-thumb` / `-thumb:hover` / `-corner` + `@supports not selector(::-webkit-scrollbar)` 的 `scrollbar-width/color`）。
- **content**：r4 HTML + 历代 HTML（`841e321` / `74f5f37` / `0b32b8a` 的 `hitl-interrupts.html`，用 `git show` 取到临时目录），保证旧类不回退。
- 原文件已备份：`<LOCAL_WORK>\_cu-hitl-final\hitl-interrupts.css.bak-r4`（18744 B）。

## 产物 diff 规模

| 指标 | 旧产物 | 新产物 |
|---|---|---|
| 选择器数 | 264 | **266**（+2） |
| 丢失选择器 | — | **0** |
| 新增选择器 | — | 恰 2：`.max-h-[240px]`、`.overflow-y-auto` |
| 字节 | 18744 | 18811（+67） |
| 规则块多重集比对 | — | **only-old = 0**，only-new = 2 |

首轮试编（只给默认主题）暴露了 10 条会丢失的规则：`html` / `code,kbd,pre,samp` 的字体栈、`.font-sans` / `.font-mono`、6px 滚动条 5 条 + `@supports` 兜底——补齐主题与 base 层后归零。**结论：零丢失，严格超集。**

## 验证

- 类名白名单：**191/191 全命中**（missing=0）。
- Playwright 全量复跑（`<LOCAL_WORK>\_cu-hitl-final\v4-test.mjs`）：**PASS=277 FAIL=0**，其中新增/收紧：
  - `F7 限高生效：computed max-height=240px / overflow-y=auto`
  - `F7 内容超高：scrollHeight(519) > clientHeight(240)`
  - `F7 容器可内滚（scrollTop 实际生效）`
  - 类名白名单断言由「允许 2 条已知缺口」改为**零缺口**
  - 已决卡底部留白断言对含内滚容器的卡改为按滚动内容测量（仍全绿）
- `curl` 复核：`hitl-interrupts.css` 200 / 18811 B（与文件一致）、`hitl-interrupts.html` 200 / 112490 B，页面渲染与重建前一致（字体、间距、卡片布局无位移）。
- `node scripts/ci/check-docs.mjs` → **6/6 绿**。

## 截图

`<LOCAL_WORK>\_cu-hitl-final\v4b-a5-submitted-scroll.png` —— 多步只读单卡滚到中段（顶部「已确认回滚快照可用」被裁、底部分割线与「填写发布窗口」可见），可直观看到 240px 内滚生效。其余 v4 截图在同轮复跑中已按新 CSS 重新生成。

## 入库

| commit | 内容 |
|---|---|
| `ed5f1c6` | `docs/demo/chat-ux/hitl-interrupts.css` **单独一笔**（Tailwind 3.4.19 重编、类集合来自 r4 html + 历代 html、264→266 零丢失） |
| `3c484d8` | `docs/research/裁决-HITL型谱与问题2方案-2026-10-17.md` §10.3 改为「已闭环」并记录构建方式 / diff / 验证 / 锚 `ed5f1c6`（**额外一笔**：§10.3 原文写的是「不生效」，已失实） |

两笔均已 `push origin main`（`665d0d4..3c484d8`）；工作树除既有的未跟踪 `data/` 外干净。

## 自证

读自身 `wire.jsonl` 的 `profile.bind` 单行：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。
