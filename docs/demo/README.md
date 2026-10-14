# demo 归档索引（docs/demo/）

> 用途：历史原型 demo 的全量入库与溯源索引。**用户直裁 2026-10-14：近期 demo 全部合入代码库，后续 demo 亦必合入。**
> 合入口径：**定稿 / 末版**进分类主目录，**中间版本与过程稿**进同分类 `archive/`；文件自仓外原路径逐字节复制（原件保留、不移动、不改写）。
> 状态取值：`定稿` / `过程稿` / `harness`。`合入来源` 一律写**仓外相对口径**（按 DC-08 不落盘符绝对路径）。
> 脱敏：demo 正文若含个人信息或本机字样，按 DC-08 就地改写，并在下表「一句话说明」注明「入库已脱敏」。
> 盘点来源：`demo-盘点-2026-10-14.md`（仓外盘点报告，15 件清单 + 分类 + 来源路径）。

## 目录结构

```
docs/demo/
├── README.md
├── chat-ux/            # AI 对话体验系（R-157 / R-159 / R-163）
│   └── archive/
├── scroll/             # 滚动行为专项（R-161）
│   └── archive/
├── hitl/               # HITL 持久化与溢出（R-160）
│   └── archive/
└── batch-download/     # 批量下载（R-156 本轮无 HTML 原型，预留空目录）
    └── archive/
```

命名规约：`<轮次>-<主题>-<版本>.html`。主目录只放定稿 / 末版 / harness，其余版本一律 `archive/`；过程中的调试稿前缀 `_` 的写法在入库时改为语义化名（如 `_trace.html` → `r161-scroll-trace.html`）。

## chat-ux/（AI 对话体验系）

| 文件 | 关联 issue/轮次 | 版本 | 状态 | 日期 | 一句话说明 | 上游决策卡 | 合入来源 |
|---|---|---|---|---|---|---|---|
| `chat-ux/r157-generating-indicator.html` | R-157（AI 对话面板 UX 二批） | v1 | 过程稿 | 2026-10-13 | 「生成中」指示器视觉效果候选稿（原标题标注「待审视」，未落定稿，仅存参考） | 仓外卡件 `r157-ux2/决策卡-R157-定稿.md` | 仓外 `r157-ux2/指示器预览.html` |
| `chat-ux/r159-stream-issue13-v3.html` | issue #13 / R-159 | v3（定稿） | 定稿 | 2026-10-13 | 流式回复体验方案定稿：渐进时间戳 + 操作按钮紧邻时间戳 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13-v3.html` |
| `chat-ux/r163-chat-ui-v6.html` | R-163 | v6（合并前五版） | 定稿 | 2026-10-14 | AI 对话体验改造全场景定稿候选（15 场景 + 工具卡 9 态）—— **入库已脱敏**（工作区标签与样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/index.html` |
| `chat-ux/archive/r159-stream-issue13-v1.html` | issue #13 / R-159 | v1 | 过程稿 | 2026-10-13 | 流式回复体验方案首版 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13.html` |
| `chat-ux/archive/r159-stream-issue13-v2.html` | issue #13 / R-159 | v2 | 过程稿 | 2026-10-13 | v2：操作按钮紧邻时间戳 | 仓外卡件 `r159-stream/决策卡-R159-定稿.md` | 仓外 `r159-stream/demo-issue13-v2.html` |
| `chat-ux/archive/r163-chat-ui-v1.html` | R-163 | v1 | 过程稿 | 2026-10-14 | 对话体验改造首版 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v1.html` |
| `chat-ux/archive/r163-chat-ui-v2.html` | R-163 | v2 | 过程稿 | 2026-10-14 | 全场景铺开版 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v2.html` |
| `chat-ux/archive/r163-chat-ui-v3.html` | R-163 | v3 | 过程稿 | 2026-10-14 | 加代码高亮与 mermaid —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v3.html` |
| `chat-ux/archive/r163-chat-ui-v4.html` | R-163 | v4 | 过程稿 | 2026-10-14 | 工具卡与排版细化 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v4.html` |
| `chat-ux/archive/r163-chat-ui-v5.html` | R-163 | v5 | 过程稿 | 2026-10-14 | 赞踩互斥 + 提示 —— **入库已脱敏**（样例用户名改占位） | 仓外卡件 `r163-chat-ui/决策卡-R163-定稿.md` | 仓外 `r163-demo/v5.html` |

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

## 维护

- 新增 demo 一律按上表体例追加行，并同步分类主目录 / `archive/` 落位；状态三态不得新增取值。
- 已入库 demo 的后续修订以新版本文件追加，不覆盖旧版（旧版下沉 `archive/`）。
