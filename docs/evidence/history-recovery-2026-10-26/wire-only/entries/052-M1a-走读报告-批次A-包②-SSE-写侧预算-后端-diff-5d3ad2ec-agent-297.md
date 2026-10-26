> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-297` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：26967 B / 19881 字符 / 270 行；行指纹覆盖率（仓内 + 落盘语料）3.3%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# M1a 走读报告：批次A（包② SSE 写侧预算）后端 diff 逐行走读

## 0. 仓库状态事实（HEAD=21b05f0，工作区）

- 后端已修改 6 个文件（`git diff --stat`）：AiProperties +15、RunRegistry +17/-?、SseChatEmitter +203、AppProperties +76、TaskSseService +158、application.yml +27；合计 465 insertions / 31 deletions。
- 未跟踪新增 4 个 java 文件（`git status -uall` 确认，无隐藏文件）：
  - `backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java`（274 行，git hash-object `4c1b6048…`）
  - `backend/src/test/java/com/example/configmgr/common/sse/SseWriteBudgetTest.java`（231 行，`ce58d678…`）
  - `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWriteBudgetTest.java`（418 行，`1aa2a85c…`）
  - `backend/src/test/java/com/example/configmgr/task/service/TaskSseServiceTest.java`（262 行，`adf83006…`）
- **同一工作区还并存 6 个前端已修改文件**（`frontend/src/stores/ai.ts`、`utils/frontend-tools.ts`、`views/{Definitions,ExportWizard,ImportWizard,TaskCenter}*.vue`，合计 +182/-74）。内容属"前端工具回灌超时预算 90s→60s（issue#6/S2-2）"，与批次A无交集 → 见 §7 范围外。

---

## 1. `backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java`（新增，全文已读）

**变更摘要**：新增进程级写预算单一事实源。`DEFAULT_TIMEOUT_MILLIS=15_000L`（:86）；直执工厂 `direct()`（:118）；带预算工厂 `on(executor,timeoutMillis)`（:123，非正数静默回落 DEFAUILT，:127）；进程级弹性写出池 `sharedElasticWriterPool()`（:131，`ThreadPoolExecutor(0, Integer.MAX_VALUE, 60s, SynchronousQueue)`，守护线程 `sse-write-N`）；`write(SendTask)`（:174）→ 直执：调用线程写+异常转 `FAILED`；预算：提交写出池 + `future.get(timeout)`，`TimeoutException`→`cancel(true)`+计数+WARN 后返回 `TIMED_OUT`（:203-209），`ExecutionException`→拆 `WriteFailure` 原样回传（:210-217），`InterruptedException`→复位中断位+`cancel(true)`+`INTERRUPTED`（:218-222）；`Outcome`（:226）；`Result` record（:238）；`SendTask`（:259）；`WriteFailure`（:265）。

**范围外 hunk**：无（整文件均属包②声称范围）。

**异常标记（只标记）**
| 位置 | 原文 | 类别 |
|---|---|---|
| :127 | `return new SseWriteBudget(executor, timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS);` | 魔数/静默回落：`write-timeout<=0` 无 WARN、无 fail-fast（对比 AppProperties 对 `emitter-timeout` 是 fail-fast） |
| :133 | `new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), …)` | 无界线程：上界只由并发订阅者数约束（javadoc 已声明为刻意） |
| :195-198 | `catch (RejectedExecutionException ex) { … return Result.failed(ex); }` | 异常被转为 `FAILED`：与"客户端断开"同码，调用方日志文本会说成"客户端断开"（会误导） |
| :206-207 | `log.warn("SSE 写超时（>{}ms，取消任务={}）：写出线程仍卡在该帧…")` | 同一事件三处 WARN：本处 + SseChatEmitter:981 + TaskSseService:184 |
| :89 / :205 | `private static final AtomicLong WRITE_TIMEOUTS`；`WRITE_TIMEOUTS.incrementAndGet()` | 跨通道共享记账：AI 与任务通道共用一个静态计数，`/api/ai/health` 的 `writeTimeouts` 含任务通道 |

**与声称范围不符**：无。声称"15s 写预算单一事实源，write()→SENT|FAILED|TIMED_OUT|INTERRUPTED 四态"——四态齐全（:226-228）。

**关键锚点**：`SseWriteBudget#DEFAULT_TIMEOUT_MILLIS:86`、`#sharedElasticWriterPool:131`、`#direct:118`、`#on:123`、`#budgeted:146`、`#write:174`、`#writeTimeouts:156`。

---

## 2. `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`（修改）

**变更摘要（逐 hunk）**
- h1（:1-2 区）新增 `import com.example.configmgr.common.sse.SseWriteBudget`。
- h2 类 javadoc 增"包②：SSE 写侧超时预算"小节（:93-116），声明 15s 预算、超时=主动断连、收尾不跨线程 complete、残留 1 线程/订阅者。
- h3 新增字段 `private final SseWriteBudget writeBudget`（:149）+ javadoc。
- h4 新增全参构造 `SseChatEmitter(String, RunStore, ObjectMapper, Executor, int, int, SseWriteBudget)`（:238-248）；原 6 参构造改为委派 `SseWriteBudget.direct()`（:225-227）。
- h5 `deliveryPoolMetrics()` javadoc 增"写超时预算"；新增两个指标键（:303-304）。
- h6 `drain()` 内联 `try{ sub.emitter.send(frame.payload) } catch(Exception){ log.debug; evict }` 替换为 `if (!writeFrame(sub, frame.payload)) return;`（:865-867）。
- h7 新增 `#writeFrame`（:905-954）、`#logWriteFailure`（:957-959）、`#evictOnWriteTimeout`（:976-985）。
- h8 `#completeOne` 增"写在飞交接"（:1037-1044）。
- h9 `Subscription` 新增三字段：`writeInFlight`（:1186）、`volatile completePending`（:1189）、`writeTimedOut`（:1192）。

**归属核对**：h1-h9 全部可归入包②声称范围（drain 逐帧预算 / writeFrame / evictOnWriteTimeout / completeOne 写在飞交接 / INTERRUPTED 走 evict）。h5 的 `deliveryPoolMetrics` 两键、h7 的 `logWriteFailure` 提取、h9 的 `writeTimedOut` 未在声称清单逐条点名，但均与包②直接同源。

**范围外 hunk**：无（未发现无关重构、跨模块顺手改）。

**异常标记**
| 位置 | 原文 | 类别 |
|---|---|---|
| :303-304 | `metrics.put("writeTimeoutMs", SseWriteBudget.DEFAULT_TIMEOUT_MILLIS); metrics.put("writeTimeouts", SseWriteBudget.writeTimeouts());` | 口径不符：`writeTimeoutMs` 报的是**编译期缺省**，不是运行期 `app.ai.sse.write-timeout` 生效值（配置改成 30s 时健康端点仍报 15000）；`writeTimeouts` 为跨通道静态计数 |
| :919-920 | `sub.completePending = false; sub.writeInFlight.set(true);` | 线程安全疑点（窄窗）：并发 `completeOne` 若在 :1037 读到 `writeInFlight==false` 会直接 complete，随后本线程仍对该 emitter `send`（将抛 `IllegalStateException`→FAILED→evict 去重）。属机械事实，是否可接受留给本体裁决 |
| :1037-1043 | `if (sub.writeInFlight.get()) { sub.completePending = true; if (sub.writeInFlight.get()) { return; } sub.completePending = false; }` | 线程安全：`AtomicBoolean`+`volatile` 的"置位→复查"握手（Dekker 式），两处 volatile 读写配对完整；**未发现**丢唤醒窗口，但依赖两字段的内存序正确（`completePending` 声明为 volatile 是必需项） |
| :957-959 | `log.debug("SSE 写出失败（客户端断开？）runId={}: {}", this.runId, ex == null ? "写超时" : ex.getMessage());` | 异常处理：`ex==null` 分支实际只在 `FAILED…failure()==null`（含池拒绝）时出现，文本用"写超时"描述非超时场景 |
| :980 | `EVICTED_TOTAL.incrementAndGet();` | 计数口径：`evictOnWriteTimeout` 不置 `evicted` 标志（javadoc :972-974 自述），若随后又走 `evict`，同一订阅者会被 `EVICTED_TOTAL` 计两次 |
| :1054-1056 | `catch (Exception ex) { log.debug("SSE complete 失败 …"); }` | 吞异常但有 debug 日志（非新引入，h8 未改该段） |
| :238 + :246 | `SseWriteBudget writeBudget` / `writeBudget != null ? writeBudget : SseWriteBudget.direct()` | 6 参构造默认直执（无预算）：生产构造点经 grep 仅 `RunRegistry#of:89` 走 7 参；其余 6 参调用全在 test 目录（`SseChatEmitterDeliveryTest:373/408/553/579/826`、`SseFrameSizeLimitTest:41`）→ 无生产漏挂 |
| 全文 | `System.out`/`printStackTrace`/`TODO`/`FIXME`/注释掉的代码块 | **未发现**（已逐文件正则扫描，命中 0，除 `10.1.54` 版本号字符串误命中） |

**与声称范围不符**：无。声称的 `#drain`/`#writeFrame`/`#evictOnWriteTimeout`/`#completeOne`/INTERRUPTED→evict 全部存在（:855/:905/:976/:1036/:949-951）。

**关键锚点**：`SseChatEmitter#writeBudget:149`、`#SseChatEmitter(7参):238`、`#deliveryPoolMetrics:295`、`#drain:855`、`#writeFrame:905`、`#logWriteFailure:957`、`#evictOnWriteTimeout:976`、`#canTakeOverDrain:995`、`#evict:1008`、`#completeOne:1036`、`Subscription:1143`、`Subscription#writeInFlight:1186`、`#completePending:1189`、`#writeTimedOut:1192`、`#expiresAtEpochMs:1067`。

---

## 3. `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`（修改）

**变更摘要（逐 hunk）**
- h1 import 调整：新增 `SseWriteBudget`、`AppProperties`；**删除 `lombok.RequiredArgsConstructor` 与 `java.io.IOException`**；`java.util.*` 改显式 4 项。
- h2 类 javadoc 增"包②"小节（:23-52，含 18330 实测/jstack 证据与 120s 容器写超时口径）。
- h3 删 `@RequiredArgsConstructor`（:53 区），新增两字段 `writeBudget:60`、`emitterTimeoutMillis:63` 与显式构造 `TaskSseService(ObjectMapper, AppProperties):65`。
- h4 `subscribe`：`new SseEmitter(0L)` → `new SseEmitter(this.emitterTimeoutMillis)`（:74）；挂载由 `computeIfAbsent(...).add(...)` 改 `emitters.compute(...)`（:77-81）。
- h5 `publish`：null 检查拆分（:111-114）；**序列化提到循环外只做一次**（:115-121）；`synchronized(list)` 内只 `new ArrayList<>(list)` 快照（:125-127）；锁外逐 emitter 走 `sendFrame` 收集 `dead`（:132-140）；摘除改 `emitters.computeIfPresent` 且空表置 null（:141-149）。
- h6 `sendEvent`：catch 由 `IOException` 放宽为 `Exception`，写出改走 `sendFrame(...,"订阅首帧 HEARTBEAT")`（:152-159）。
- h7 新增 `#sendFrame`（:174-190，四态 switch；`FAILED`/`TIMED_OUT`→false，`INTERRUPTED`→true）。
- h8 `#remove` 改 `computeIfPresent` + 空表摘条目（:192-199）。

**归属核对**：h3-h5、h7-h8 均可归入声称范围（#publish 锁内快照+锁外预算写、#sendFrame、#subscribe 非 0 超时+compute 原子挂载、#remove computeIfPresent）。以下**未在声称清单逐条点名的额外改动**（均属邻近面，但应登记）：
- 序列化由"每 emitter 一次"改为"整帧一次"（:115-121）——行为改动，不只是"锁内只快照"；
- `publish` 摘除时**同时清空表条目**（:145-148）——声称只写了"锁内只快照+锁外带预算写"；
- `sendEvent`（首帧 HEARTBEAT）也纳入写预算（:155）——声称只列 `#sendFrame`；
- 构造签名由 `TaskSseService(ObjectMapper)`（Lombok 单参注入）变为 `(ObjectMapper, AppProperties)`，**删掉 `@RequiredArgsConstructor`** —— 全仓 caller 只有 4 处测试调用（`TaskSseServiceTest:58/72/106/124`），无生产 caller 受影响；
- 删除 `import java.io.IOException`（主类已无 IOException 引用，已确认 grep 0 命中）。

**异常标记**
| 位置 | 原文 | 类别 |
|---|---|---|
| :155 + :174 | `sendFrame(emitter, json, "订阅首帧 HEARTBEAT");` / `private boolean sendFrame(...)`（返回值被 `sendEvent` 丢弃） | 逻辑不一致：首帧心跳**超时/失败时不摘除**该 emitter（与 `publish` 的 false→摘除不同），该订阅者留在推送表，直到下一次 publish 再超时才被摘除 |
| :156-158 | `catch (Exception e) { log.debug("Failed to send SSE: {}", e.getMessage()); }` | 吞异常但有 debug 日志（`sendFrame` 自身已不再抛，此 catch 现只接序列化异常） |
| :184-185 | `log.warn("任务 SSE 写超时（>{}ms）摘除该订阅者（{}；前端 EventSource 重连 + 快照轮询自愈）"` | 双 WARN：与 `SseWriteBudget:206` 对同一次超时各记一条 |
| :117 / :146 | `Map.of("type", type, "data", data)` / `current.removeAll(deadList)` | 无异常吞没；但 `publish` 的 `list = emitters.get(taskId)`（:111）与后续 `computeIfPresent`（:145）之间非原子，快照可能含已摘 emitter（已被 `sendFrame` 的失败路径容忍） |
| :60/:63/:68 | `SseWriteBudget.on(SseWriteBudget.sharedElasticWriterPool(), sse.getWriteTimeout().toMillis())` | 无 NPE 守卫：若 `app.task.sse.write-timeout` 被显式置 null，`toMillis()` NPE（yml 未置 null，属理论面） |
| 全文 | System.out/printStackTrace/TODO/FIXME/注释掉的代码块/盘符路径/用户名/IP/`sk-` 凭据 | **未发现**（正则扫描 0 命中；注释里的"18330"是端口号非 IP、"10.1.54"是 tomcat 版本号） |

**与声称范围不符**：声称的四个方法全部存在且改动方向一致（`#publish:110`、`#sendFrame:174`、`#subscribe:73`、`#remove:192`）；上述 4 项额外改动见上。

**关键锚点**：`TaskSseService#writeBudget:60`、`#emitterTimeoutMillis:63`、`#TaskSseService:65`、`#subscribe:73`、`#publish:110`、`#sendEvent:152`、`#sendFrame:174`、`#remove:192`。

---

## 4. `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`（修改，+15）

**变更摘要**：h1 新增 import `SseWriteBudget`（:3）；h2 在嵌套类 `Sse`（:199）内新增 `private Duration writeTimeout = Duration.ofMillis(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);`（:257，带 javadoc 说明"单帧写出预算 ≠ 流寿命"）。属声称范围。

**范围外 hunk**：无。**异常标记**：无（与 `RunRegistry:77-79` 的 `getSse().getWriteTimeout()` 键名一致，已验证绑定存在）。

**锚点**：`AiProperties.Sse:199`、`AiProperties.Sse#writeTimeout:257`。

---

## 5. `backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java`（修改，+17）

**变更摘要**：import `SseWriteBudget`；新增字段 `writeBudget:60`；三参构造（`:66-67`，`@Autowired`）中按 `properties != null` 装配 `SseWriteBudget.on(sharedElasticWriterPool(), properties.getSse().getWriteTimeout().toMillis())`，否则 `direct()`（:77-80）；`of(runId)` 构造 SseChatEmitter 改传 7 参（含 `this.writeBudget`，:89）。两参构造（:62-64）委派三参并传 `null` ⇒ **恒 `direct()`**，与声称一致（已逐行核对）。

**范围外 hunk**：无。**异常标记**：`:77-79` 无 null 守卫（`getSse().getWriteTimeout()` 为默认非 null，理论面）。无吞异常/线程安全新增问题（`sharedElasticWriterPool()` 自身 `synchronized`+`volatile`）。

**锚点**：`RunRegistry#writeBudget:60`、`#RunRegistry(2参):62`、`#RunRegistry(3参):66`、`#of:85`。

---

## 6. `backend/src/main/java/com/example/configmgr/config/AppProperties.java`（修改，+76）

**变更摘要**：h1 新增 import `SseWriteBudget`、`jakarta.annotation.PostConstruct`、`java.time.Duration`；h2 新增 `private Task task = new Task();`（:27）与 `@PostConstruct void verifyTaskSseEmitterTimeout()`（:45-54，`emitterTimeout == null || isZero() || isNegative()` → `throw new IllegalStateException`，**fail-fast**）；h3 新增 `@Data public static class Task`（:67）含 `Sse sse`，`@Data public static class Sse`（:80）含 `writeTimeout = 15s`（:86）、`emitterTimeout = Duration.ofMinutes(5)`（:107）。属声称范围。

**范围外 hunk**：无。**异常标记**
| 位置 | 原文 | 类别 |
|---|---|---|
| :45-54 | 校验只覆盖 `emitter-timeout`（`isZero()\|isNegative()`），**未校验 `app.task.sse.write-timeout`** | 魔数/守卫缺口：`write-timeout<=0` 只被 `SseWriteBudget.on` 静默换成 15s（SseWriteBudget:127） |
| :107 | `private Duration emitterTimeout = Duration.ofMinutes(5);` | 硬编码魔数 5m（javadoc 已论证与 AI 通道 300s 同量级，但两条 15s/5m 常量在 yml 与 java 双写） |
| :3-6 import 顺序 | `lombok.Data` 排在 `org.springframework…` 之后 | 命名/风格纪律（与本文件原有顺序一致，非新引入；仅登记） |

**锚点**：`AppProperties#task:27`、`#verifyTaskSseEmitterTimeout:45`、`AppProperties.Task:67`、`Task.Sse:80`、`Sse#writeTimeout:86`、`Sse#emitterTimeout:107`。

---

## 7. `backend/src/main/resources/application.yml`（修改，+27）

**变更摘要**：`app.ai.sse` 新增块（:147-162）：`delivery-pool-size: 8`、`delivery-queue-capacity: 256`、**`write-timeout: 15s`**；`app.task.sse` 新增块（:163-173）：`write-timeout: 15s`、`emitter-timeout: 5m`。声称"两新配置块"属实。

**范围外 hunk**：无。

**异常标记**
| 位置 | 原文 | 类别 |
|---|---|---|
| :161-162、:172-173 | `delivery-pool-size: 8` / `delivery-queue-capacity: 256` / `write-timeout: 15s` / `emitter-timeout: 5m` | 魔数双写：与 `AiProperties.Sse#deliveryPoolSize=8`、`DEFAULT_DELIVERY_QUEUE_CAPACITY=256`、`SseWriteBudget.DEFAULT_TIMEOUT_MILLIS=15000`、`AppProperties.Sse#emitterTimeout=5m` 两处各写一遍；改代码不必然改 yml（`delivery-*` 两项为"显式写出以见口径"，但**覆盖生效**而非仅注释） |
| :157 | `#     即写超时 = server.tomcat.connection-timeout（本配置 120s）` | 引用型魔数（120000 在 :6）；注释与 `AppProperties`/`SseWriteBudget` javadoc 三处重复同一实测口径 |
| 全文 | 无 profile 覆盖文件（`backend/src/test/resources` 不存在；resources 下仅 `application.yml` + `db/`），无重复 `task:`/`sse:` 键，无 `app.task.sse.emitter-timeout: 0` 冲突 | — |

**未找到**：`application-{dev,test,prod}.yml` 不存在（无覆盖面，故 fail-fast 守卫不会被 profile 绕过，也无法被 profile 泄掉）。

---

## 8. 新增测试文件

### 8.1 `backend/src/test/java/com/example/configmgr/common/sse/SseWriteBudgetTest.java`（231 行）
- **摘要**：7 个用例 —— `timedOutWriteReturnsToCallerWithinTheBudget:55`（200ms 预算、`TIMED_OUT`、无 failure、计数 +1）、`successfulWriteReturnsSent:85`（写出在线程 `budget-test-sent`）、`failedWriteCarriesTheOriginalExceptionBack:106`（`isSameAs(disconnected)`）、`directBudgetWritesInlineAndReportsFailureWithoutWrapping:130`、`bothChannelsTakeTheSameDefaultWriteBudget:153`（AI/任务两通道缺省 == `DEFAULT_TIMEOUT_MILLIS`，且 `emitterTimeout > DEFAULT`）、`sharedElasticWriterPoolIsIdempotentAndDaemon:169`、`zeroTaskEmitterTimeoutFailsStartupWhileTheDefaultPasses:185`（最小 `AnnotationConfigApplicationContext` + `MapPropertySource("app.task.sse.emitter-timeout","0s")` 断言 refresh 失败，`rootCause().hasMessageContaining("app.task.sse.emitter-timeout")`）。
- **异常标记**
  - `:221-230` `private static Object executorOf(SseWriteBudget budget)` —— **定义后无任何调用者**（全文件 grep 仅 :221 一处），属未使用私有方法（死代码）；其保留的 `import java.lang.reflect.Field`（:12）因此也未失效。
  - `:45-50` `blocking(...)` 的 `release.await(5, SECONDS)` —— 测试内硬编码 5s，写出线程最长挂 5s（默认超时后自然返回）。
  - 无 `System.out`/`printStackTrace`/TODO/FIXME/注释掉代码块。

### 8.2 `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWriteBudgetTest.java`（418 行，J2 证据文件）
- **摘要**：6 个用例 `:74`（单线程投递池不被卡住写拖死）、`:103`（超时摘除但不在调用线程 complete，收尾落在 `budget-writer*` 线程）、`:128`（INTERRUPTED 与 TIMED_OUT 同出口摘除、不丢帧续投）、`:166`（写在飞期间溢出摘除不阻塞出帧线程 <2s）、`:193`（T4-3 帧契约回归）、`:243`（直执 = 投递池线程内写出）。桩件 `MonitorFaithfulEmitter:275`（`send`/`complete` 同实例 `synchronized`，首帧可卡住）。
- **异常标记**
  - `:62` `new ThreadPoolExecutor(0, 8, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), daemon("budget-writer"))` —— 魔数 8/60L 复刻生产池形状（不含断言）。
  - `:317-326` 桩件卡住循环 `deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)` 且 `catch (InterruptedException ex) { /* 忽略 */ }` —— 硬编码 10s 上限；若 `release()` 未在 10s 内调用，`send` 会**静默返回并照常记 payloads**（"卡住"用例可能假绿）。
  - `:323-326`、`:131-133` 忽略中断为刻意（javadoc 已论证 Tomcat 行为）—— 吞异常但有注释说明。
  - 时序敏感断言：`:89-91`（<3000ms）、`:111`、`:154-157`、`:181-182`（<2000ms）等基于墙钟阈值，非纪律问题，仅登记为潜在 flaky 面。
  - 无 System.out/printStackTrace/TODO/FIXME/注释掉代码块。

### 8.3 `backend/src/test/java/com/example/configmgr/task/service/TaskSseServiceTest.java`（262 行）
- **摘要**：4 用例 `:55`（`emitter.getTimeout()` == 配置值且 >0）、`:71`（同任务第二条 publish 不被慢连接挡在锁上 <4s）、`:105`（写超时摘除 + 空表条目清理 `doesNotContainKey(TASK_ID)`）、`:123`（帧体 `{"type":..,"data":..}` 与 data 透传不变）。
- **异常标记**
  - `:161-171` `subscribersOf` / `emittersOf` 白盒反射私有字段 `emitters` 并**直接向内部 list 写入**（:162 `computeIfAbsent(...)` 造同步 list）—— 测试与实现内部结构强耦合（javadoc 已自述理由）。
  - `:239-248` `BlockingEmitter` 5s 上限 + `catch (InterruptedException ex) { /* 忽略 */ }`（刻意，同 Tomcat 口径）。
  - 无 System.out/printStackTrace/TODO/FIXME/注释掉代码块。

### 8.4 `backend/src/test/java/com/example/configmgr/common/sse/` 目录构成
**事实**：该目录下**仅 1 个文件** `SseWriteBudgetTest.java`（`find` 已确认），不存在其他文件。

---

## 9. J2 裁决证据原文抄录（`SseChatEmitterWriteBudgetTest.java`，逐字）

**用例入口**：`void budgetedWritePathKeepsTheFrameAndT43Contract()`（:193）；帧序列 `out.start("s-wb"); out.delta("正文"); out.confirmRequest(pending("c-1"), 120); out.heartbeat(Map.of("phase","suspended")); out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));`（:198-202，`pending` 用 `KIND_CONFIRM`、`arguments={"taskId":7}`，:410-417）。

**atMs（全帧覆盖 + 归档同钟）**
- :210-213
```
// T4-3 后半：atMs 在帧构造时刻定型 ⇒ 实时投递帧全部带 atMs
for (Map<String, Object> frame : live) {
    assertThat(frame.get("atMs")).as("实时帧 %s 必须带 atMs", frame.get("type")).isInstanceOf(Number.class);
}
```
- :225-237
```
// T4-3：归档（start / confirm_request / done）与实时投递帧取的是同一个 atMs
List<Map<String, Object>> archived = FrameWire.normalize(store.events(RUN_ID));
assertThat(archived).extracting(frame -> frame.get("type"))
        .containsExactly("start", "confirm_request", "done");
for (Map<String, Object> archivedFrame : archived) {
    long seq = ((Number) archivedFrame.get("seq")).longValue();
    Map<String, Object> liveFrame = live.stream()
            .filter(frame -> frame.get("seq") instanceof Number number && number.longValue() == seq)
            .findFirst()
            .orElseThrow();
    assertThat(archivedFrame.get("atMs")).as("seq=%d 归档 atMs 与实时投递原文一致", seq)
            .isEqualTo(liveFrame.get("atMs"));
}
```

**`expiresAt − atMs == timeoutSeconds × 1000`**
- :220-223
```
// T4：expiresAt 与同帧 atMs 同钟 ⇒ 差恒等于 timeoutSeconds * 1000
long expiresAt = ((Number) live.get(2).get("expiresAt")).longValue();
long atMs = ((Number) live.get(2).get("atMs")).longValue();
assertThat(expiresAt - atMs).as("confirm_request 的 expiresAt − atMs").isEqualTo(120_000L);
```
（注：原文断言是**字面量 `120_000L`**，不是表达式 `timeoutSeconds*1000`；`timeoutSeconds` 由 `confirmRequest(..., 120)` 传入 = 120，故 120×1000=120000。同口径的实现侧证据在 `SseChatEmitter#expiresAtEpochMs:1067-1071`，javadoc 原文含 `expiresAt - atMs == timeoutSeconds * 1000L`（:1064）。）

**seq（业务帧单调、心跳不占业务 seq）**
- :214-219
```
// 业务帧带单调 seq；心跳不占业务 seq（S5c-1），只在独立空间计数
assertThat(live.get(0)).containsEntry("seq", 1L);
assertThat(live.get(1)).containsEntry("seq", 2L);
assertThat(live.get(2)).containsEntry("seq", 3L);
assertThat(live.get(4)).containsEntry("seq", 4L);
assertThat(live.get(3)).doesNotContainKey("seq").containsKey("heartbeatSeq");
```

**事件顺序（终端契约）**
- :206-208
```
// 事件顺序（S4.2 §3 终端契约）：start → delta → confirm_request → heartbeat → done
assertThat(live).extracting(frame -> frame.get("type"))
        .containsExactly("start", "delta", "confirm_request", "heartbeat", "done");
```

**心跳帧其他相关断言**
- 心跳帧 = `live.get(3)`，断言只在 :219（`doesNotContainKey("seq")` + `containsKey("heartbeatSeq")`）；:212 的 atMs 遍历**包含**心跳帧（循环覆盖 `live` 全部 5 帧）。
- 另一处心跳语境断言在 :87 附近（`awaitSize(healthy, 2, 3_000L)` 计的是 delta 帧数），非心跳帧断言。

**"超时不静默丢帧"的对照断言（J2 相关）**
- :112-114 `assertThat(slow.completes()).as("超时时不得调用 complete：写线程仍持有 emitter 监视器，就地 complete 只会再挂一个线程").isZero();`
- :154-156 `assertThat(out.subscriberCount()).as("中断 ⇒ 与写超时同出口摘除订阅者（主动断连让前端重挂对账），而非丢帧且不断连").isZero();`

---

## 10. 与声称范围的汇总差异（只列事实）

| 声称 | 实际 | 结论 |
|---|---|---|
| 新增 `common/sse/SseWriteBudget.java` | 存在，274 行，四态齐全 | 一致 |
| `common/sse/` 测试目录"全部文件（含 SseWriteBudgetTest）" | 目录内**仅** `SseWriteBudgetTest.java` 一个文件 | 一致（无遗漏文件） |
| `SseChatEmitterWriteBudgetTest.java`、`TaskSseServiceTest.java` | 均存在（418 / 262 行） | 一致 |
| `RunRegistry` 两参恒 `direct()` | :62-64 委派 null ⇒ direct() | 一致 |
| `AppProperties` Task.Sse + `@PostConstruct` fail-fast | 存在（:27/:45/:67/:80/:86/:107） | 一致 |
| `application.yml` 两新配置块 | `app.ai.sse`（3 键，其中 2 键为既有属性显式化）+ `app.task.sse`（2 键） | 一致，但 `delivery-pool-size/delivery-queue-capacity` 是**旧属性首次写进 yml**，非新键 |
| TaskSseService 仅 4 个方法改动 | 另有序列化提到循环外、摘除时清空表条目、sendEvent 纳入预算、删 Lombok 构造 | **超出点名范围**（见 §3） |
| SseChatEmitter 5 项改动 | 另有 `logWriteFailure` 提取、metrics 两新键、`writeTimedOut` 字段 | 邻近，未点名 |
| 批次A 仅后端 | 同工作区另有 6 个前端文件已修改（issue#6/S2-2 工具回灌预算 90s→60s） | **范围外共存改动**（若要归因需明确批次归属） |

**未找到项**：无。声称的所有文件、方法、配置块均已定位；`SseWriteBudget.java`、`SseChatEmitter#writeFrame/#evictOnWriteTimeout/#completeOne 交接`、`TaskSseService#sendFrame/#subscribe compute/#remove computeIfPresent`、`AiProperties.Sse#writeTimeout`、`AppProperties#verifyTaskSseEmitterTimeout`、`application.yml app.ai.sse + app.task.sse` 全部存在。唯一的"不存在"是 §8.4 所述：`common/sse` 测试目录里没有除 `SseWriteBudgetTest.java` 之外的文件。
