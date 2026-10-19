> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# composer 修后只读复验（final）

## 自证 / 环境

- `profile.bind`：`agentId=agent-1084`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 前端 `http://localhost:5202/tasks` 运行中；只读复验，未修改文件/服务，未提交。
- 基线 `cb0e926`；当前 diff 仍仅：
  - `frontend/src/components/AiPanel/AiPanel.vue`
  - `frontend/src/stores/ai.ts`
  - `frontend/tests/ai-panel-parts-render.spec.ts`
- `git diff cb0e926 -- frontend/src/App.vue` = **0 行**；`frontend/src/styles` = **0 行**（`diff-evidence.txt`）。

## 总结论

| 范围 | 结论 |
|---|---|
| **composer 内部（本批）** | **PASS** |
| tip Esc 修复 | **PASS** |
| 360/400 页面级 | **FAIL（既有问题5，挂账；非本批引入）** |
| composer 内部 360/400 | **PASS（无新增内部溢出）** |
| 真实中文 IME | **BLOCKED**（仍非实机验收） |
| App.vue/styles 本批改动 | **PASS：0 行** |

**不能给整体项目 PASS**：页面级 360/400 仍 FAIL；但 composer 内部不应因此被判 FAIL。

---

## 1) tip 修后状态机（PASS）

实现位置：`AiPanel.vue:728-733` 容器监听；`AiPanel.vue:1345-1440` 共享状态机；`AiPanel.vue:1430-1440` Escape；mermaid 独立监听在 `AiPanel.vue:2228`。

实测（1280）：

| 场景 | 发送 tip | 停止 tip |
|---|---|---|
| hover | display:block, opacity 1 | display:block, opacity 1 |
| Escape | **display:none, hidden class** | **display:none, hidden class** |
| 按钮内部移动（Esc 后） | 仍 hidden（未误清） | — |
| 外部 mouse 重新进入 | display:block, hidden 移除 | display:block, hidden 移除 |
| Escape 后新的键盘 focus | active + focus-visible，tip 恢复可见 | active + focus-visible，tip 恢复可见 |
| Escape 事件默认行为 | `defaultPrevented=false`，`cancelBubble=false` | 同左 |
| 真实 mermaid 全屏 | 打开后 Escape → `mmd-viewer` 消失（closed=true），无 JS error | — |

- 发送/停止 tip 的 hover 与 focus 均可见；Esc 后 `hidden`/display:none；键盘 focus 和外部 mouse 重新进入均可恢复；按钮内部移动不误清。
- Escape 未调用 `preventDefault`/`stopPropagation`；mermaid 全屏仍可 Esc 关闭，全局默认行为未被阻断。
- 截图：`shots/tip-send-hover.png`、`tip-send-esc-hidden.png`、`tip-send-keyboard.png`、`tip-stop-hover.png`、`tip-stop-esc-hidden.png`、`tip-stop-keyboard.png`、`mermaid-after-esc.png`。
- 数据：`tip-final.json`、`mermaid-escape.json`。

---

## 2) 最近改动 smoke（PASS）

- 1/4/6 行：**40 / 101 / 101px**；6 行 `scrollHeight=143 > clientHeight=101`，`scrollTop 32→42`，工具栏 top 前后均 **727**。
- 流式（持流）：
  - posts=1；普通 Enter 前后 value/caret **11/11** 完全不变；停止钮仍在、发送钮仍隐藏、无 notice、无发送/换行/排队。
  - Shift+Enter → value 追加换行、caret 12。
  - 停止方块：按钮 `display:flex`、28×28、`border-radius 9999px`；内部白方块 9×9、中心 **dx 0 / dy 0**。
- 真实 stop：停止后输入为空、停止钮消失、发送钮出现、无 error card、notice “已请求取消本轮对话”、**不自动发送**；停止后 Enter 新增一条发送（posts 1→2、用户气泡 2），第二次 Enter 不双发（气泡仍 2）。
- 失败保稿（开流前 503）：原草稿 `失败保稿复验` 恢复、焦点回 textarea、用户气泡 1；重发仍 503 后 posts=2，但用户气泡仍 **1**（编辑重跑路径，不重复）。
- 截图：`shots/final-1line.png`、`final-4line.png`、`final-6line.png`、`final-stream-held.png`、`final-real-stop.png`、`final-after-stop-send.png`、`final-failure-503.png`、`final-failure-retry.png`。
- 数据：`smoke-final.json`。

---

## 3) 360/400 边界（页面级 FAIL / composer 内部 PASS）

| 项 | 360 | 400 |
|---|---:|---:|
| document `scrollWidth` | **493** | **493** |
| page-level overflow | **FAIL** | **FAIL** |
| header 右按钮 right | 493.13 | 493.13 |
| aside 展开 | 400px，right 432 | 400px，right 432 |
| composer wrap | 368px，right 415 | 368px，right 415 |
| wrap 在 aside 内 | **true** | **true** |
| composer 子元素越出 aside | **[]** | **[]** |
| 收起后 scrollWidth | **493** | **493** |
| 收起后 aside | 48px | 48px |

- **页面级 FAIL**：493 仍由 header nowrap 菜单 + “隐藏/显示 AI 栏”按钮决定；收起面板后仍 493（header 右按钮 right 493.13）。这是既有问题5页面壳层，不是本批引入；`App.vue/styles` 本批 0 diff。
- **composer 内部 PASS**：composer 368px 完全在 400px aside 内，right 415 < aside 432，`descendantBeyondAside=[]`；没有 composer 内部新增横向溢出。360/400 下 composer 被裁是父面板整体越界/视口裁切，不是 composer 内容越界。
- 截图：`shots/final-page-360-expanded.png`、`final-page-360-collapsed.png`、`final-page-400-expanded.png`、`final-page-400-collapsed.png`。

### 挂账

- `360/400 页面级横向溢出`：归入既有“问题5 窄栏落地”（`docs/任务看板.md:26`，在途）；责任元素为 header 右按钮（right 493.13）与固定 400px aside（right 432）。不要通过 composer 内部宽度掩盖。
- **不能把页面级问题改判 PASS**；如果发布闸门把 360/400 作为整体门槛，则整体仍 FAIL，直到问题5页面壳层修复。composer 内部范围可单独 PASS。

---

## 4) 真实 IME / 批准范围

- 真实中文 IME：**BLOCKED**。本次未实机验收；合成组合期/keyCode229/30ms 守卫此前 PASS，且本次 tip 修复未触及 composer keydown。不得声称真实 IME 已验收。
- 本次 tip 修复仅新增容器事件/状态机，未改 App.vue/styles；无新增服务端数据依赖。

## 证据

- `<SCRATCH>/_cu-composer-project-r26/browser/final/report.md`
- `tip-final.json`、`mermaid-escape.json`、`smoke-final.json`
- `diff-evidence.txt`、`source-lines.txt`
- 截图目录：`<SCRATCH>/_cu-composer-project-r26/browser/final/shots/`

未修码，未改服务，未提交。页签已关闭。
