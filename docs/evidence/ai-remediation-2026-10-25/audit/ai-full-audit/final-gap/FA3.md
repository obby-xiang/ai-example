> 来源=<仓外证据>/ai-full-audit/final-gap/FA3.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA3｜作业与导入导出数据面（跨前后端）

- **归属域**：D3/D6（作业/数据面）
- **规模**：6 个文件 / 4,471 行
- **为什么还要读**：BZ-01/BZ-02 的前端可见面与作业判死的跨实例语义
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java` | 789 | 未逐行（FINAL §8.2） | beat/runner-active/deadjudge 三键族、判死单飞、TTL 与多实例语义 |
| 2 | `backend/src/main/java/com/example/configmgr/job/service/JobService.java` | 555 | 未逐行（FINAL §8.2） | createAndStart 四守卫与可执行性缺口（BZ-01）、取消与条目收敛 |
| 3 | `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java` | 479 | 未逐行（FINAL §8.2） | 本机扇出 + 写预算 + 首末订阅；跨实例去回声 |
| 4 | `frontend/src/utils/excel.ts` | 865 | 未逐行（FINAL §8.2） | 导出/下载产物的 xlsx 构造与单码/多码字节路径 |
| 5 | `frontend/src/utils/spreadjs.ts` | 86 | 未逐行（FINAL §8.2） | SpreadJS 宿主注册；sheet 适配器的实例/世代来源 |
| 6 | `frontend/src/views/ImportWizardView.vue` | 1,697 | 未逐行（FINAL §8.2） | 换任务回收（FE-16）、发布确认（FE-19）、只读闸与 syncJob 绑定 |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA3-report.md`，首行声明「未运行任何测试」。
