> 来源=<仓外证据>/ai-full-audit/final-gap/FA4.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA4｜前端组合界面（向导 / 设置 / 表单 / 上下文 / 结果视图）

- **归属域**：D5（前端对话/界面）
- **规模**：11 个文件 / 4,022 行
- **为什么还要读**：FE-15/16、PK-12、BZ-25/26 的用户可见面
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `frontend/src/views/ExportWizardView.vue` | 1,789 | 未逐行（FINAL §8.2） | 换任务 stopJobWatchForTaskSwitch、批量下载换任务竞态、步骤结果回执 |
| 2 | `frontend/src/views/AiSettingsView.vue` | 834 | 未逐行（FINAL §8.2） | 保存/回滚/掩码/探活/版本回显；与面板 health 的跨页一致性（FE-15） |
| 3 | `frontend/src/views/AiFeedbackView.vue` | 82 | 未逐行（FINAL §8.2） | 赞踩列表渲染与单用户前提 |
| 4 | `frontend/src/components/AiPanel/FormRenderer.vue` | 791 | 未逐行（FINAL §8.2） | 表单渲染/联动/校验/附件/只读取值面（BZ-25/26 的前端表现） |
| 5 | `frontend/src/components/AiPanel/UploadHandoffCard.vue` | 111 | 未逐行（FINAL §8.2） | 交接卡四态与「放弃」的目标（PK-12） |
| 6 | `frontend/src/components/AiPanel/ContextPill.vue` | 120 | 未逐行（FINAL §8.2） | 上下文占用的未知档/负值/七项展示 |
| 7 | `frontend/src/components/AiPanel/ContextPopover.vue` | 79 | 未逐行（FINAL §8.2） | 明细弹层与刷新 |
| 8 | `frontend/src/components/AiPanel/resultview/ResultViewRenderer.vue` | 68 | 未逐行（FINAL §8.2） | resultView 渲染分发 |
| 9 | `frontend/src/components/AiPanel/resultview/ResultViewTable.vue` | 105 | 未逐行（FINAL §8.2） | 列渲染与截断三件套呈现 |
| 10 | `frontend/src/components/AiPanel/resultview/ResultViewText.vue` | 28 | 未逐行（FINAL §8.2） | 文本态与降级 |
| 11 | `frontend/src/components/AiPanel/resultview/registry.ts` | 15 | 未逐行（FINAL §8.2） | kind 到组件的注册表 |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA4-report.md`，首行声明「未运行任何测试」。
