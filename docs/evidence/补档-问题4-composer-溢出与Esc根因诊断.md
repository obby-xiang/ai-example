> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# 只读根因界定：360/400 溢出与 tip Esc

## 自证 / 基线

- `profile.bind`：`agentId=agent-1084`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 前端 `http://localhost:5202/tasks` 运行中；本次只读，未修改文件/服务，未提交。
- 基线提交：`cb0e926 fix(demo): 居中 composer 停止钮图标`。
- `git diff cb0e926 --name-only` 仅：
  - `frontend/src/components/AiPanel/AiPanel.vue`
  - `frontend/src/stores/ai.ts`
  - `frontend/tests/ai-panel-parts-render.spec.ts`
- `git diff cb0e926 -- frontend/src/App.vue` = **0 行**；`frontend/src/styles` = **0 行**。本批没有改应用外壳/面板宽度/头部。

---

## 1) 360/400 `document.scrollWidth=493` 根因

### 实测边界

| 视口 | document scrollWidth | header | headerLeft | el-menu | 右按钮（隐藏 AI 栏） | aside | section |
|---|---:|---:|---:|---:|---:|---:|---:|
| 360 | **493** | x0 w360 | w408.11，right 424.11 | w374.11，right 424.11 | x424.11 → **right 493.13** | x32 w400，right 432 | w32 |
| 400 | **493** | x0 w400 | w408.11，right 424.11 | w374.11，right 424.11 | x424.11 → **right 493.13** | x32 w400，right 432 | w32 |

- 最右元素就是 header 的 `el-button`（“隐藏 AI 栏”），right **493.13**，与 document scrollWidth 493 对齐。
- header 计算：`display:flex; flex-wrap:nowrap; justify-content:space-between; overflow:visible; width:360/400px`；headerLeft 实际 **408.11px**，menu **374.11px**；`el-menu :ellipsis="false"`（App.vue:9）使菜单不省略；toggle 默认 `white-space:nowrap`、69.02px。header 自身盒子只有 360/400，但内容溢出到 493，`overflow:visible` 将其计入 document 横向滚动。
- aside：`width:400px; flex:0 0 auto; flex-shrink:0; overflow:hidden`，x32..**432**；是第二处溢出，但不是 493 的最大值。
- main/section：`section` 被固定 400px aside 挤到 **32px**；section 有 `overflow-x:hidden`（App.vue:27），所以内部 1250px 表格被裁在 section 内，**不贡献 document scrollWidth**。

### 责任元素与 CSS 来源

1. **主责任：header 的 nowrap flex 行**（`frontend/src/App.vue:3`）
   - `flex items-center justify-between`、无 `flex-wrap`、无 `min-w-0`；
   - 左侧品牌 + `el-menu`（`:ellipsis="false"`，App.vue:5-16）最小内容宽 408.11；
   - 右侧 `el-button`“隐藏/显示 AI 栏”（App.vue:19-21）69.02；
   - 合计右边 493.13；header 盒子不扩张，内容溢出计入 document。
2. **次责任：固定 400px AI 面板**（`frontend/src/App.vue:30-35` + `panelWidth` App.vue:72）
   - `el-aside :width="panelWidth"`，展开 `400px`、收起 `48px`；
   - `flex:0 0 auto; flex-shrink:0`，在 360/400 视口下 right=432，超出视口；同时把 section 挤到 32px。
3. **composer 不是责任元素**（`AiPanel.vue:733/742/748/759`）
   - composer wrap 368px，位于 x47..415；aside right 432 → `wrapInsideAside=true`；
   - textarea right 414、toolbar right 414、send right 405，全部 ≤ wrap 415；
   - `descendantBeyondAside=[]`；composer 内部无超出父容器的内容。

### 收起后仍 493 说明什么

- 收起后 aside 变为 **48px**，但 `document.scrollWidth` 仍 **493**。
- 点击“隐藏 AI 栏”时，按钮本身在 x424，浏览器必须横向滚动才能点到它：实测 `scrollX` 在 360 为 **133**、400 为 **93**；页面因此左移，但 scrollWidth 仍 493。
- 说明 493 由 **header 的最小内容宽度（菜单 + 按钮）**决定，不由 AI 面板宽度决定。收起面板不能修复 header 的 nowrap 溢出。

### 本批 diff 边界

- `git diff cb0e926 -- frontend/src/App.vue` = 0；`frontend/src/styles` = 0；本批只改 `AiPanel.vue / ai.ts / tests`。
- `App.vue` 的 header/aside/panelWidth 规则均为**既有、本批未改**。
- 本批新增 composer 使用 `w-full`（AiPanel.vue:734）、`max-h-[101px]`、`max-w` 无固定超宽；composer 自身在 400 面板内（368px）无内部溢出。
- 因此必须区分：
  - **【页面级既有溢出】**：header nowrap + `:ellipsis="false"` + 固定 400px aside；本批未引入；归属既有“问题5 窄栏落地”（`docs/任务看板.md:26`，状态“在途”），并可细分为 page-shell/header 子项。
  - **【composer 内部新增溢出】**：**不存在**；composer 及所有子元素均在 aside 可视父容器内。360/400 的 composer 被裁是**父面板整体越界**导致，不是 composer 内容越界。

**边界结论：** 360/400 是页面壳层/问题5 的既有响应式缺口；本批没有新增 composer 内部溢出。但按验收原文“360/400 无横向溢出”，该项仍应判 **FAIL**；不能用“composer 内部没溢出”把验收改判 PASS，也不能把页面壳层问题算到 composer 交付头上。

---

## 2) tip Esc：项目确实没有 composer tip 的 Escape/hidden 清理

### demo v2（有）

- `docs/demo/chat-ux/composer-decisions.html:382-416`：
  - `keydown` Escape → 对 `button:hover, button:focus` 的 `.tip` 加 `hidden`；
  - `mouseover` / `focusin` → 移除 `hidden`；
  - `mouseout` / `focusout` → 移除 `hidden`。
- demo v2 的 tip Esc 是“Esc 隐藏 + 后续重新进入恢复”的完整状态机。

### 当前 AiPanel.vue（没有）

- 全前端 `grep -rn 'Escape' frontend/src` 只有：`frontend/src/components/AiPanel/AiPanel.vue:2119-2123` 的 **mermaid 查看器** `onMmdViewerKeydown`，且仅在 `mmdViewerOpen` 时注册（2125-2133）。
- composer tip：`AiPanel.vue:753`（停止）、`763`（发送）使用 `TIP_CLASS`；`TIP_CLASS` 在 `frontend/src/utils/markdown.ts:268` 只有 `opacity-0` + `group-hover/tip:opacity-100` + `group-focus-visible/tip:opacity-100`，**没有 hidden 类**。
- 项目中没有任何 composer tip 的 Escape 处理器或 hidden 清理；Esc 后 tip 仍 `opacity:1`、class 不变（浏览器实测）。
- 本批 diff 新增了 composer tip 的 span（diff 中 +73/+78/+84/+88 行），但没有同步 demo 的 Escape/清理；旧 composer 没有 tip，因此这是**本批新增 composer UI 的行为遗漏**，不是页面壳层问题。

### 最小同步点

- **一个共享的 tip-hide 状态机**，对齐 demo v2 的 382-416：
  - Escape 对当前 `button:hover, button:focus` 的 tip 隐藏；
  - `mouseover` / `focusin` / `mouseout` / `focusout` 恢复；
- 因为 app tip 是 `opacity-0`/group-hover 体系，最小同步需要一个能覆盖 opacity 的隐藏类（如 demo 的 `hidden`/display:none），而不是只改 opacity。不要逐按钮各写一套，否则焦点/hover 组合会漂移。

### 对 mermaid 全局 Escape 的影响

- mermaid 的 Escape 是独立监听，仅在 `mmdViewerOpen` 时注册（AiPanel.vue:2125-2133），只 `closeMmdViewer()`。
- 新增 tip Escape 只要不 `preventDefault`/`stopPropagation`，两个监听可并存：tip 隐藏不影响 mermaid 关闭；mermaid 打开时 tip 处理器即使运行也只隐藏 tip（无伤）。
- 不要用一个会阻断传播的全局 Escape 替换 mermaid 监听。

---

## 3) 指挥官处理建议

### 必须本批修

1. **tip Esc（composer 内）**：本批新增 tip UI，demo v2 已有完整 Esc/hidden 状态机，当前完全缺失；按验收项 5 应修。最小同步点见上，风险局部；不要把它挂到问题5。
2. 其他 composer 内部项无需修：停止钮 28×28/9×9/中心 0/0、1/4/6 行 40/101/101、流式 Enter/Shift+Enter/真实 stop/失败保稿/hard-disabled 均已 PASS。composer 内部无新增溢出。

### 登记问题5（挂账）

- **360/400 页面级横向溢出**：header nowrap + `:ellipsis="false"` + 固定 400px aside；`App.vue` 与 styles 本批 0 diff；属于既有“问题5 窄栏落地”（`docs/任务看板.md:26`，在途）。建议在问题5下补 page-shell/header 子项，精确责任元素为 header 右按钮（right 493.13）与固定 aside（right 432）；不要通过改 composer 内部宽度来掩盖页面壳层问题。
- 若发布闸门把 360/400 视为 composer 交付门槛，则该门槛目前被问题5页面壳层阻塞；可明确记录为“本批未引入、但验收原文仍 FAIL”，由指挥官决定是否把 composer-only 批准与页面壳层挂账分开。不得把验收口径改成 PASS。

### BLOCKED 的批准范围

- **真实中文 IME BLOCKED**：合成组合期/keyCode229/30ms 守卫已 PASS；真实 IME 是环境/硬件验收限制，不是 composer 代码失败。若验收允许 BLOCKED，不阻塞 composer 批准；但不得声称真实 IME 已验收。
- **live HITL 圆圈问号 BLOCKED**：本次未产生 live HITL gate（避免触发数据变更）。既有 #3 证据可沿用：
  - 提交 `390abf9` 将 HITL 图标改为圆圈问号；
  - 当前 `frontend/tests/ai-panel-parts-render.spec.ts:1737-1750` 仍精确断言 circle r10 + 问号 path + 底部点 + `data-icon-type=hitl`，并断言 tool/hitl/form 三图标互异；
  - 本次 live tool 卡（扳手 / “已成功”）与 GF 卡（剪贴板 / “待填写”）均 PASS。
  - 因此 #3 的**图标结构/右状态非回归**可沿用既有证据；**live HITL gate** 仍 BLOCKED，但不属于 composer 批准范围。若 #3 的验收要求 live gate，则单独记账，不得伪装为 live 已验证。

## 证据文件

- `<SCRATCH>/_cu-composer-project-r26/browser/scope-diagnosis/diff-cb0e926.patch`
- `diff-stat.txt`、`diff-name-only.txt`、`app-diff.txt`（0 行）
- `scope-measure.json`、`collapse-scroll.json`、`tip-esc.json`（app tip Esc 实测 opacity 仍 1）
- `source-lines.txt`、`app-escape-search.txt`、`demo-tip-search.txt`、`diff-tip-lines.txt`
- `issue5-excerpt.txt`、`hitl-tests-excerpt.txt`
- 截图：`expanded-360.png`、`collapsed-360.png`、`expanded-400.png`、`collapsed-400.png`；tip：`shots/tip-hover-project.png`、`shots/tip-keyboard-project.png`

未修改代码/服务，未提交。
