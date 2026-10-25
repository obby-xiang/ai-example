> 来源=<仓外证据>/ai-full-audit/final-gap/FA1.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA1｜后端工具契约层（工具定义 / 披露 / 守卫 / 截断 / 结果视图）

- **归属域**：D2（门/工具/表单）
- **规模**：10 个文件 / 3,888 行
- **为什么还要读**：工具契约覆盖「定义→披露→执行→结果」四段，是 PK-11 / PK-17 的最后落点
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java` | 1,052 | 未逐行（FINAL §8.2） | 20 个 @Tool 桥接体；args/resources 校验与异常回填；与 ToolRegistry 披露子集的一致性 |
| 2 | `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java` | 486 | 未逐行（FINAL §8.2） | meta 扫描/继承链、forContext 与 matchesContext 双出口、risk/channel/category 默认值 |
| 3 | `backend/src/main/java/com/example/configmgr/ai/tool/ContextBuilder.java` | 411 | 未逐行（FINAL §8.2） | 上下文 5 键与白名单、workspace 段定界与截断、selectedDefs 注入 |
| 4 | `backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java` | 393 | 未逐行（FINAL §8.2） | 向导动作参数闸（未知键/空 codes/小写 mode/未知 defCode/非法算子） |
| 5 | `backend/src/main/java/com/example/configmgr/ai/tool/SheetWriteGuard.java` | 361 | 未逐行（FINAL §8.2） | 表格写范围/上界/白名单列与 audit 元数据 |
| 6 | `backend/src/main/java/com/example/configmgr/ai/tool/FrontendToolGuard.java` | 226 | 未逐行（FINAL §8.2） | 前端工具两道闸的接口面与结构化判定入口 |
| 7 | `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultLimiter.java` | 315 | 未逐行（FINAL §8.2） | 行/字符双闸、notice 与 returnedChars 的口径（PK-17） |
| 8 | `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultEnvelope.java` | 256 | 未逐行（FINAL §8.2） | 信封五字段、toJson 与 toModelJson 的差异（PK-17） |
| 9 | `backend/src/main/java/com/example/configmgr/ai/tool/SheetAudit.java` | 269 | 未逐行（FINAL §8.2） | ai:safety 写入点与 process 级计数（RT-16） |
| 10 | `backend/src/main/java/com/example/configmgr/ai/resultview/ListTasksResultViewProvider.java` | 119 | 未逐行（FINAL §8.2） | 唯一 provider 的 maxBytes 约定与列裁剪 |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA1-report.md`，首行声明「未运行任何测试」。
