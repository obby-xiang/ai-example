> 来源=<仓外证据>/ai-full-audit/final-gap/FA5.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA5｜前端工具执行与状态辅助

- **归属域**：D6（前端业务联动）
- **规模**：11 个文件 / 3,807 行
- **为什么还要读**：FE-30、FE-10、FE-09 与 ui_event 可达性
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `frontend/src/utils/frontend-tools.ts` | 1,560 | 未逐行（FINAL §8.2） | 四级路由、工作区动作表、ui_event 白名单、下载/上传执行器与超时预算 |
| 2 | `frontend/src/utils/blocks.ts` | 585 | 未逐行（FINAL §8.2） | 工具卡参数表 argRows 与掩码正则（FE-30） |
| 3 | `frontend/src/utils/tool-summaries.ts` | 265 | 未逐行（FINAL §8.2） | 工具卡摘要文案与 i18n |
| 4 | `frontend/src/utils/resultview.ts` | 410 | 未逐行（FINAL §8.2） | 前端 resultView 归一/降级 |
| 5 | `frontend/src/utils/ui-receipt.ts` | 93 | 未逐行（FINAL §8.2） | 回执条构造 |
| 6 | `frontend/src/utils/error-card.ts` | 188 | 未逐行（FINAL §8.2） | 错误卡分级与文案（FE-09 邻） |
| 7 | `frontend/src/utils/ai-suggestions.ts` | 112 | 未逐行（FINAL §8.2） | 空态建议词（FE-10） |
| 8 | `frontend/src/utils/job-watch.ts` | 253 | 未逐行（FINAL §8.2） | 作业 watch 的 SSE + 轮询 + 空闲看门狗（FE-16 邻） |
| 9 | `frontend/src/utils/polling.ts` | 248 | 未逐行（FINAL §8.2） | 轮询控制器与判据 |
| 10 | `frontend/src/utils/publish-mode.ts` | 61 | 未逐行（FINAL §8.2） | 发布模式解析（fail-closed） |
| 11 | `frontend/src/utils/publish-links.ts` | 32 | 未逐行（FINAL §8.2） | 发布冲突链接落点 |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA5-report.md`，首行声明「未运行任何测试」。
