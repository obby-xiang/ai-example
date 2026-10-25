> 来源=<仓外证据>/ai-full-audit/final-gap/FA2.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA2｜后端运行存储与并发原语 + 属性面

- **归属域**：D1/D3（运行/多实例）
- **规模**：5 个文件 / 3,578 行
- **为什么还要读**：帧存储与并发原语的行为决定 RT-07/09/16 与 ID-04 的最终定性
- **边界**：只读；不得改代码/测试/配置；不得据此宣称「已修复」。

| # | 路径 | 行数 | 现状 | 重点核查点 |
|---|---|---:|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java` | 758 | 未逐行（FINAL §8.2） | seq INCR/自动 ID 回退/窗口与字节预算（RT-07/RT-09）；P1 仅读 337–460 |
| 2 | `backend/src/main/java/com/example/configmgr/ai/run/FrameSizeLimiter.java` | 449 | 未逐行（FINAL §8.2） | 可截字段集合、markTruncated 覆盖语义、split 路径（PK-16） |
| 3 | `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java` | 460 | 未逐行（FINAL §8.2） | 单键 Lua 取门/接管/续期/强释放/降级；F 域称全读，本次独立复核 |
| 4 | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java` | 412 | 未逐行（FINAL §8.2） | tagged/legacy 键推导（ID-04）、RENAME、删除与索引；L/M 只读关键路径 |
| 5 | `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java` | 1,499 | 未逐行（FINAL §8.2） | 全部键的缺省值与守卫（park/lease/sse/limit/stream/context/memory/sheet/kb/resume） |

## 交付要求

1. 逐行读完上表；每个文件给出「与既有 canonical ID 的关系（确认/补充/否证）」。
2. 只允许新增「代码可证」的条目；每条带 `path:line` + 触发条件 + 用户可见效果。
3. 需运行期条件的结论标 `R`，不得写成 `C`。
4. 与既有条目重复时回指 ID，不新开编号。
5. 报告写入 `final-gap/FA2-report.md`，首行声明「未运行任何测试」。
