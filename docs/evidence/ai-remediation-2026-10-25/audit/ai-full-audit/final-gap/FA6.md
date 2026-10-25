> 来源=<仓外证据>/ai-full-audit/final-gap/FA6.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA6｜前端 store / 类型 / 格式化

- **归属域**：D5/D6（状态与契约）
- **规模**：11 个文件 / 4,606 行
- **为什么还要读**：FE-14~16、FE-19、BZ-25/26 的类型与状态根因
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `frontend/src/stores/workspace.ts` | 639 | 未逐行（FINAL §8.2） | buildContext 五键、recentActions、只读闸、ui_event 分支可达性（FE-14/15/16） |
| 2 | `frontend/src/stores/task.ts` | 424 | 未逐行（FINAL §8.2） | 发布冲突跟踪单槽（FE-19）、300s 墙钟 |
| 3 | `frontend/src/types/ai.ts` | 984 | 未逐行（FINAL §8.2） | 请求/响应/上下文类型与请求级 modelId 的退场 |
| 4 | `frontend/src/types/sse.ts` | 622 | 未逐行（FINAL §8.2） | 帧类型白名单与 reasoning 说明 |
| 5 | `frontend/src/types/tools.ts` | 563 | 未逐行（FINAL §8.2） | 工具元数据与 UI_EVENT_TYPES 闭集 |
| 6 | `frontend/src/types/form-schema.ts` | 832 | 未逐行（FINAL §8.2） | 表单 schema 类型契约（BZ-25/26 的类型面） |
| 7 | `frontend/src/types/resultview.ts` | 117 | 未逐行（FINAL §8.2） | resultView 列类型/kind 契约 |
| 8 | `frontend/src/utils/format.ts` | 118 | 未逐行（FINAL §8.2） | 数字/时间/业务类型标签（FE-29） |
| 9 | `frontend/src/utils/context-usage.ts` | 200 | 未逐行（FINAL §8.2） | 上下文占用的格式化与未知上限 |
| 10 | `frontend/src/utils/form-draft.ts` | 87 | 未逐行（FINAL §8.2） | 按 toolCallId 的草稿键（FE-08） |
| 11 | `frontend/src/utils/form-scope.ts` | 20 | 未逐行（FINAL §8.2） | 表单 scope 归一 |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA6-report.md`，首行声明「未运行任何测试」。
