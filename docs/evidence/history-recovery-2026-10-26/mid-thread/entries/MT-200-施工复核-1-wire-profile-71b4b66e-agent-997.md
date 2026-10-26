<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-200 · 施工复核 · 1. wire profile（71b4b66e / agent-997）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`docs/demo`、`chat-ux`。
> **稿态**：历史中间稿 —— 该 wire 第 `m136` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt4` 条有正文的 assistant 消息（共 11 条有正文），末条有正文消息为 `m232`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `71b4b66e` / agent `agent-997`（`main` = 主会话）；原字符 3,421（链内 1 段、合计 3,421）。
> **链号 / 类别**：`MT-200` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m232`（2,400 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

已完成独立 demo 施工与中间验收。以下为交接报告。

## 1. wire profile

读取自身 `profile.bind`：
- `agentId`: `agent-997`
- `modelAlias`: `DeepSeek/deepseek-flash`
- `profileName`: `coder`

## 2. modern-web-guidance 检索

命令（按指定 skill-version）：

```bash
cmd //c npx.cmd -y modern-web-guidance@latest search "accessible collapsible card header leading icons" --skill-version 2026_09_04-7de96777
```

返回 5 个 id：`style-parent-with-has`、`shaped-cutouts`、`accessibility`、`size-aware-styling`、`css-layout`。其中只有 **`accessibility`** 与本任务真正相关（键盘、ARIA、装饰 SVG、表单标签），已 retrieve 并实际采用：

- 折叠卡头用原生 `<button>` + `aria-expanded`，不造 `div role="button"`；
- 装饰 SVG 一律 `aria-hidden="true"`，语义由可见文字徽标承担；
- 表单控件全部有显式 `<label for>`，必填用原生 `required`；
- 无正 tabindex；保留现有 focus-visible ring；
- 卡头/操作按钮均为原生按钮，Enter / Space 可直接操作。

其余 id 未硬套（`css-layout` 与 `size-aware-styling` 对本固定 440px 面板无实质收益，且不能新增 CSS）。检索与 retrieve 原文保存在：
- `<LOCAL_WORK>/_cu-card-channels/modern-guidance-search.txt`
- `<LOCAL_WORK>/_cu-card-channels/modern-guidance-retrieve-accessibility.txt`

## 3. 交付文件

项目内只新增这一份文件：

- `docs/demo/chat-ux/card-channels.html`（466 行，约 54 KB）

未修改 `hitl-interrupts.html`、其他 demo、前端代码、文档或 CSS；未 commit / push；未起停服务。`git status` 仅显示该新文件（另有施工前已存在的未跟踪 `data/`）。

实现要点：
- 根节点 `data-card-channels="2026-10-17-v1"`、`data-icon-variant="bare|tile"`；
- 12 张卡：工具运行/成功/失败、普通与高风险确认、GF 待填写、六态已决（已批准 / 已拒绝 / 已超时 / 已批准 · 执行失败 / 已提交 / 已取消）；
- 四通道 `data-channel="tool|confirm|form|decided"`，状态 `data-state` 齐全；D 六态默认 `data-collapsed="1"`；
- 图标槽为内联 SVG：工具=扳手、确认=盾牌/警示三角、表单=清单、已决=状态结果图标；bare 15×15、tile 20×20（圆角浅色托底）；两种 SVG 由切换按钮显隐；
- 切换同时更新根节点、`aria-pressed` 与 URL `?icon-variant=tile`，刷新 URL 可还原；
- 复用现有编译产物 `hitl-interrupts.css`（未新增 CSS、无 `<style>`、无 inline style）；166 个使用到的类全部在该 CSS 中存在（脚本逐类校验通过）。

## 4. TDD 与验收

测试脚本：`<LOCAL_WORK>/_cu-card-channels/test-cards.mjs`，复用 `<LOCAL_WORK>/_cu-hitl-final` 的 `playwright-core`，未安装任何依赖。

| 阶段 | 结果 | 证据 |
|---|---|---|
| RED 1（demo 尚不存在，HTTP 404） | pass 22 / fail 116 | `red-1-404.log` |
| RED 2（最小骨架，断言真正执行） | pass 37 / fail 101 | `red-2-skeleton.log` |
| GREEN（完整实现） | **pass 152 / fail 0** | `green-final.log`、`last-run.json` |

最终 GREEN 覆盖：
- bare/tile 切换、URL 传参、键盘切换；
- tool/confirm/form/result 图标互异；
- 两种变体图标均不跨卡片边界；
- 12 个卡头全部 36.391px（即 36.4px 口径）；
- 9 个可折叠 chevron 可见、最右、距卡右边 10–11px；
- GF 单卡头、无嵌套卡、无内层 12px 卡壳、限高内滚保留（`min(320px,45vh)`）；
- B 使用 confirm 图标、无工具 kind chip、无蓝色内框、动词标题、批准/拒绝按钮；
- D 六态默认折叠、`aria-expanded=false`、摘要/徽标/chevron 齐全、零操作按钮、点击/Enter/Space 展开收起；
- pending B/C 不折叠、无折叠 chevron、卡体可见；
- 面板 440/400/360px 及真实视口 400/360px 无横向溢出，按钮全部 `nowrap`；
- Tab 可达卡头、焦点环可见；
- 无 emoji、无 inline style、无 `<style>`、无重复 id、表单控件均有标签、无嵌套按钮；
- 控制台无报错。

## 5. 截图

全部位于 `<LOCAL_WORK>/_cu-card-channels/`：

- 全页：`shot-bare-full.png`、`shot-tile-full.png`
- bare 局部：`shot-bare-A-tool.png`、`shot-bare-B-confirm-normal.png`、`shot-bare-B-confirm.png`（高风险）、`shot-bare-C-form.png`、`shot-bare-D-decided.png`、`shot-bare-D-decided-expanded.png`
- tile 局部：同名的 `shot-tile-*.png` 7 张
- 窄栏证据：`shot-narrow-400-full.png`、`shot-narrow-400-panel-top.png`、`shot-narrow-360-full.png`、`shot-narrow-360-panel-top.png`

## 6. 约束与未尽项

- 本次仅为**当前 18330 服务上的中间验收**；按指示未重启服务，**最终重启后的独立复验未做**，不得视为最终验收。
- 因只允许新增一个项目文件，demo 复用 `hitl-interrupts.css`。该 CSS 是从 `hitl-interrupts.html` 单独编译的；若日后重编该 CSS，新 demo 独用的类可能被 purge。正式采纳时建议给本 demo 配独立编译产物或在 Tailwind content 中纳入本文件。
- demo 的六态已决卡是静态样张（默认折叠、可手动展开），未接真实后端；普通/高风险确认与 GF 的按钮只做展示，不含真实提交逻辑（按本轮 demo 范围）。
- 未做 resultView、Markdown 表格重设计、窄栏问题 5、输入框问题 4。
