<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-064 · 过程回执 · fix round1 完成（45653e92 / agent-1745）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-code/ai-example-main`、`backend/src/`、`RedisFrameStore.java`、`backend/src/main/java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m144` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt43` 条有正文的 assistant 消息（共 50 条有正文），末条有正文消息为 `m161`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1745`（`main` = 主会话）；原字符 3,024（链内 1 段、合计 3,024）。
> **链号 / 类别**：`MT-064` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 3 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m161`（2,298 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

## fix round1 完成

元数据核对：仓库 `<REPO>`，`main`，HEAD `f2931f5`；评审 I1/I3 均属实（已用 md5 与源码实测复核）。本轮**只修 I1 + I3 + 接口注释**，未 commit/push、未改 docs、未起停服务、未派子 agent。

### I1 修复：两条 LIST WARN 现在各自被独立用例钉住

**问题实证**：`failListRead=true` 让 `LRANGE -1 -1` 先抛，`readFrom → legacyFramesAbove` 在末帧探测处短路，`readLegacyList` 永不执行；两条 WARN 又共用「兼容期 LIST 读取失败」前缀，旧用例命中的是末帧探测那条。因此 `:409` 回退 `debug` 仍全绿（假保护）。

- `backend/src/test/java/com/example/configmgr/ai/run/FakeFrameRedis.java`：`failListRead` 拆为两个独立开关 —— `failListTailProbe`（只让 `LRANGE -1 -1` 抛）、`failListFullRead`（只让整段 `LRANGE -窗口 -1` 抛），按参数区分。
- `backend/src/test/java/com/example/configmgr/ai/run/RedisFrameStoreTest.java`：旧用例拆成两条并断言互斥文案：
  - `legacyListTailProbeFailureIsReportedInsteadOfSilentlyEmpty` → contains「末帧探测」+ **doesNotContain**「本批 LIST 帧缺失」；
  - `legacyListFullReadFailureIsReportedInsteadOfSilentlyEmpty` → contains「本批 LIST 帧缺失」+ **doesNotContain**「末帧探测」。
- 生产侧 WARN **未改**（按指示保持）。

**变异证明**（每次只改一行日志级别，跑完即恢复）：

| 变异 | 证据日志 | 结果 |
|---|---|---|
| `RedisFrameStore.java:409` 整段读 `warn→debug` | `_p2-review/frame50-mutation-m1-409debug.log` | 2 跑 1 败：整段读用例红、末帧探测用例绿 |
| `RedisFrameStore.java:437` 末帧探测 `warn→debug` | `_p2-review/frame50-mutation-m2-437debug.log` | 2 跑 1 败：末帧探测用例红、整段读用例绿 |

恢复核对：生产文件 `git diff` 与留档 `_p2-review/P0-frame50.diff` 中该文件的段**逐字节相同**（md5 `d981127109726a0ee9e50436cc9256bf`），变异无残留。

### I3 证据卫生

新建 `<LOCAL_WORK>/_p2-review/P0-frame50-evidence-note.md`（历史日志**未删未改**），如实写明：
- `_a-frame5-red-canonical.log` 与 `_a-frame5-green-mvn2.log` md5 同为 `68919d74…` ⇒ **副本、非独立运行、不得引用**；同时披露 `_a-frame5-green-canonical.log` 也是 `green-mvn3` 的副本（有效运行是 green-mvn3）。
- RED 只以 `_a-frame5-red.log` 为准：23 例 / **12 败**（9 差量读 + 3 WARN），回退状态 = **原生产文件 + 新替身 + 新用例**；并更正“11 败”的写法。
- `green-mvn2`（= red-canonical）因增量编译沿用旧 `exclusive` 字节码 + 他人并发改动，**数字与归因作废**，既非 RED 也非 GREEN。
- 列出当前有效 GREEN（含本轮最新 `frame50-green-final.log` 32/32），并注明哪些日志不作计数证据（`-q` 无 summary、全量 77 errors 系并发 clean、`red-mvn` 止于编译）。
- 附 6 条可直接追加的 **errata** 措辞，不改历史正文。

### 接口注释同步（不改行为）

`backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java` 的 `readFrom` javadoc 新增：**「空结果 ≠ 证明『没有帧』」**——读失败同样返回空并 WARN 留痕（带 runId/区间），调用方不得把空当无帧证明；本方法不抛异常。该文件 diff **只有注释行**。

### GREEN（`mvn -o test`，BUILD SUCCESS）

- `_p2-review/frame50-i1-green.log` — RedisFrameStoreTest **24/24**（拆分为 24 条后）
- `_p2-review/frame50-green-final.log` — 定向 3 类 **32/32**（含 I1 拆分用例 + 接口注释），最新
- 生产文件与 round1 逐字节一致，故 round1 的相邻证据仍成立：`_a-frame5-adjacent2.log` **422/422**

### 本轮改动文件

- `backend/src/main/java/…/run/FrameStore.java`（仅 javadoc）
- `backend/src/test/java/…/run/FakeFrameRedis.java`（拆开关）
- `backend/src/test/java/…/run/RedisFrameStoreTest.java`（拆用例；总数 23→24）
- `backend/src/main/java/…/run/RedisFrameStore.java`（仅用于变异、已恢复原状）
- 仓库外：`<LOCAL_WORK>/_p2-review/P0-frame50-evidence-note.md` 及本轮 5 个新日志

### 未做（按指示不扩，主会话挂账）

I2（回放读失败仍正常 `complete`）、Minor 4（自动 ID 兜底 + `COUNT` 窗口遗漏）、WARN 去重/限流、`ex.toString()` 丢栈、替身 `xRevRange` 区间过滤、`frameOf`/LIST 单帧坏损 debug —— 均未触碰。另注意：工作区仍有大量其它 agent 的在途改动，本轮构建期间未见其阻塞（定向测试一次通过）。
