# M2-T3-5 压测定值报告（DS-V4-Flash）

> 施工棒：M2-T3-5 压测施工棒（DS-V4-Flash）｜日期：2026-10-09
> 主仓库工作区：`<MAIN_REPO>`（报告正文一律用本占位符）｜产出落盘：`<SCRATCH-REPORTS>\`（报告与附件的常驻目录）
> 占位符（红队 F11.5 统一为单一指向）：`<SCRATCH>` = 压测临时目录；`<SCRATCH-REDTEAM>` = 红队产物目录。
> 占位符：`<SCRATCH-REPORTS>` = 压测报告目录（t35-reports）。
> 本报告为**定值建议 + 压测证据**；按指挥官批注 **R2（回灌冻结）**，本棒**不回灌** `AiProperties` 生产默认值、
> 不回灌 DC-08/ADR-7/技术方案冻结文档。回灌由后续小棒在"红队 + 复核双卡通过"后执行。

---

## 0. 头部：基线、环境自证、结束态

### 0.1 基线确认（§2，原文输出）

```
$ git log --oneline -5
1a28216 docs: M2-T3a 证据链补挂+探针随证入库——排期 v1.6 注记挂三报告相对链接 + 仓库外探针源码入 docs/evidence/probes/
608167a docs: M2-T3a 外部审查三报告补档——验收盘点（DS-V4-Flash）/红队击破 R1（DS-V4-Pro）/复现+复核（GLM-5.3）
8ff4d17 docs: M2-T3a 证据入库与闭环注记——排期 v1.6（T3a 闭环 + 遗留去向表）+ 设计卡两处施工注记 + README 索引同步
2b1d3d9 test(backend): M2-T3a 用例——帧协议用例修订 + seq 持久化与投递生命周期新用例（修复批第 1/2 轮红→绿留痕）
c86c37f feat(backend): M2-T3a 施工——T3-1 慢订阅者隔离 + T3-2 seq 持久化/delta 跳过归档（含修复批第 1/2 轮 + 微修）

$ git status --porcelain
(空)

$ git rev-parse HEAD
1a28216f34b72f96205be44cd187cc59a7b47924
```

**结论**：HEAD = `1a28216`，工作区干净，与施工提示词预期一致。【实测】

### 0.2 环境自证

| 项 | 实测值 | 来源 |
|---|---|---|
| JDK | `java version "21.0.12" 2026-07-21 LTS` | `java -version` |
| Node | `v22.23.2` | `node -v` |
| yarn | `1.22.22` | `yarn -v` |
| CPU 核数 | **16** | `Runtime.getRuntime().availableProcessors()` / `nproc` |
| reactor boundedElastic 容量 | **160** | `reactor.core.scheduler.Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE` 反射实读（= 10 × 16） |
| Redis | 127.0.0.1:6379 LISTENING，PID 5032（**本棒启动前已在跑，非本棒启动**） | `netstat -ano \| findstr :6379` |
| 后端端口 | 18330（启动前 `netstat` 无 LISTENING，本棒自起自停） | 端口纪律记录见 §S8/S9 |
| 桩上游端口 | 18398（S8 临时桩）/ 18399（S9 官方桩），均启动前查占用 | 同上 |
| PowerShell | `5.1.26100.9444`（E2E 脚本宿主） | `$PSVersionTable` |
| 所跑 E2E 路线 | **桩模式**（`E2E_STUB_MODE=1`，`AI_BASE_URL=http://127.0.0.1:18399`，`AI_MODEL=ci-stub`）；真 key 路线**未跑** | §S9 |

### 0.3 结束态

```
$ git status --porcelain
?? backend/src/test/java/com/example/configmgr/ai/conformance/ThreeArgAssemblyConformanceTest.java
?? docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md
?? docs/evidence/probes/M2-T3-5-ProbeDelta.java
?? docs/evidence/probes/M2-T3-5-ProbeLock.java
?? docs/evidence/probes/M2-T3-5-ProbeMatrix.java
?? docs/evidence/probes/M2-T3-5-ProbeReplay.java

$ git rev-parse HEAD
1a28216f34b72f96205be44cd187cc59a7b47924
```

**共 6 个新增未跟踪文件**（红队 F11.5 修正：原写 5，漏计的正是**本证据文档自身**——自指伪影；git 对该非 ASCII
路径按默认 `core.quotepath=true` 以八进制转义显示，此处按 UTF-8 路径抄录，条目与措辞其余不变）。
本节是**本棒结束态快照**（时点见 §0.1）；修正棒核对时实时 `git status` 已出现本棒之外的**在途并行改动**
（已有若干已跟踪文件被改、并有新增未跟踪文件，清单与数量随其施工推进而变化），属其他小棒的施工在途，
**不计入本节快照**。

**零 git 写操作**（未 commit/add/stash/checkout/reset）；工作区**保持未提交**，改动全部落在授权范围内
（授权 (a) 探针源码 / (b) conformance 新用例 / (c) 证据文档与附件）。**无任何仓库源文件被修改**（含
`AiProperties.java`、冻结文档——按批注 R2 未触碰）——**此断言以本棒时点为真**；时点之后并行小棒的在途
改动（见上注）不属本棒。【实测】

### 0.4 断言标注四级约定

【实测】本棒自跑并留原始输出；【推断】由实测数据按公认公式推得；【假设】未验证前提；【旁证】引用他棒结论。

---

## S1 —— 「N 挂起 × M 慢订阅者」矩阵压测（场景 E）+ 场景 A/D + 联动公式

**方法**：探针 `docs/evidence/probes/M2-T3-5-ProbeMatrix.java`（仓库外另存为 `ProbeMatrix.java` 编译运行）。
每个 cell 新建一个**生产同形投递池**（`ThreadPoolExecutor(core=max=poolSize, LinkedBlockingQueue(poolSize*64), AbortPolicy)`，
与 `SseChatEmitter#configureSharedDeliveryPool` 同构），**不读进程静态池**（隔离坑 4 的首装配者胜污染）。

- 造慢：`SlowEmitter#send` 首帧阻塞在 `CountDownLatch` 上（模拟 TCP 背压），release 后正常排出。
- `N` 个 run 各挂 1 个健康订阅者；`M` 个慢订阅者轮转挂到 N 个 run 上（run 可多订阅者，符合 reattach 扇出语义）。
- 节奏偏差口径：预热后在**同一饱和窗口**内发第 1 批 → 记录 `t0` → 发第 2 批 → `偏差 = 第2帧到达时刻 − t0`；
  测量边界 = 摘除前。等待上限 1500ms（判据 < 1s）。
- 每 cell 另配 1 个**独立 runId 的溢出子探针**（1 个慢订阅者 + 灌 260 条实时帧），测摘除增量与 `complete()` 语义。

### S1.1 场景 E 矩阵（deliveryPoolSize = 4，现状值）— 20/20 格全部跑完，无留白

| N | M | 核心格 | 慢 drain 阻塞数 | 池 active | 池 queueDepth | 健康侧第1批达帧 | 第2批后达标 | 节奏偏差 ms | < 1s | 格内 evictedΔ | 溢出 complete() |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | **✔核心** | 1 | 1 | 0 | 1/1 | 1/1 | **0** | ✅ | 1 | 1 |
| 1 | 4 | | 4 | 4 | 0 | 1/1 | 0/1 | —（饿死） | ❌ | 1 | 1 |
| 1 | 8 | | 4 | 4 | 4 | 1/1 | 0/1 | —（饿死） | ❌ | 1 | 1 |
| 1 | 16 | | 4 | 4 | 12 | 1/1 | 0/1 | —（饿死） | ❌ | 1 | 1 |
| 5 | 1 | **✔核心** | 1 | 4 | 1 | 5/5 | 5/5 | **0** | ✅ | 1 | 1 |
| 5 | 4 | | 4 | 4 | 1 | 4/5 | 0/5 | —（饿死） | ❌ | 1 | 1 |
| 5 | 8 | | 4 | 4 | 7 | 2/5 | 0/5 | —（饿死） | ❌ | 1 | 1 |
| 5 | 16 | | 4 | 4 | 16 | 1/5 | 0/5 | —（饿死） | ❌ | 1 | 1 |
| 10 | 1 | | 1 | 1 | 0 | 10/10 | 10/10 | **0** | ✅ | 1 | 1 |
| 10 | 4 | **✔核心** | 4 | 4 | 6 | 4/10 | 0/10 | —（饿死） | ❌ | 1 | 1 |
| 10 | 8 | | 4 | 4 | 10 | 4/10 | 0/10 | —（饿死） | ❌ | 1 | 1 |
| 10 | 16 | | 4 | 4 | 20 | 2/10 | 0/10 | —（饿死） | ❌ | 1 | 1 |
| 20 | 1 | | 1 | 2 | 6 | 20/20 | 20/20 | **0** | ✅ | 1 | 1 |
| 20 | 4 | | 4 | 4 | 16 | 4/20 | 0/20 | —（饿死） | ❌ | 1 | 1 |
| 20 | 8 | **✔核心** | 4 | 4 | 20 | 4/20 | 0/20 | —（饿死） | ❌ | 1 | 1 |
| 20 | 16 | | 4 | 4 | 28 | 4/20 | 0/20 | —（饿死） | ❌ | 1 | 1 |
| 25 | 1 | | 1 | 2 | 0 | 25/25 | 25/25 | **0** | ✅ | 1 | 1 |
| 25 | 4 | | 4 | 4 | 21 | 4/25 | 0/25 | —（饿死） | ❌ | 1 | 1 |
| 25 | 8 | | 4 | 4 | 25 | 4/25 | 0/25 | —（饿死） | ❌ | 1 | 1 |
| 25 | 16 | **✔核心** | 4 | 4 | 33 | 4/25 | 0/25 | —（饿死） | ❌ | 1 | 1 |

**矩阵结论**（【实测】）：

1. **验收判据①「单慢订阅者不阻塞同轮其他订阅者（节奏偏差 < 1s）」在 M=1 全 N 阶梯成立**：
   N ∈ {1,5,10,20,25} 全部 0ms 偏差（含 N=25 的 25 路挂起）。M=1 时池里始终留有空闲工作线程。
2. **M ≥ deliveryPoolSize(4) 时健康订阅者被**确定性饿死**（> 1500ms 无帧）——与 N 无关**。
   机制：M 条慢 drain 占满全部 4 条工作线程（`poolActive` 恒 = 4），健康 drain 只能排进池任务队列
   （`poolQueueDepth` 随 M 与 N 增长，M=16/N=25 时达 33）；槽位只有在慢连接被释放或摘除时才回收。
   **这不是缺陷，是 R3 已知语义**（"慢连接占满 4 线程后其余订阅者 drain 排队等待"），
   但**它是 deliveryPoolSize 定值的硬约束**：池容量必须严格大于"预期同时存在的慢连接数"。
3. **摘除路径全部正确**：20/20 格 `格内 evictedΔ = 1`、溢出订阅者 `complete()` 恰好 1 次、
   溢出 run 的 `subscriberCount() = 0` ⇒ 溢出摘除 = **主动断连**（S2-1 语义）【实测】。
4. `slowLiveQueuedPeak` 列未单列：矩阵节奏阶段只灌 2 条实时帧，峰值 **2–3**（20 格 + 16 格扫描逐格原文取值只有 2 或 3，无 1；红队 F11.4 修正）；
   真实溢出触发点由溢出子探针覆盖（灌到第 257 条才摘除，见 §S4.3 的直接锚定：256 条不摘、257 条摘）。【实测】

### S1.2 池容量扫描（N = 10 固定，判据 = 健康侧是否被饿死）

| deliveryPoolSize | M=1 | M=4 | M=8 | M=16 |
|---|---|---|---|---|
| **4（现值）** | ✅ 0ms | ❌ 饿死 | ❌ 饿死 | ❌ 饿死 |
| 8 | ✅ 0ms | ✅ 0ms | ❌ 饿死 | ❌ 饿死 |
| 16 | ✅ 0ms | ✅ 0ms | ✅ 0ms | ❌ 饿死（M == 池容量） |
| **20** | ✅ 1ms | ✅ 0ms | ✅ 0ms | ✅ **0ms** |

**规律（【实测】→【推断】）**：健康订阅者不被饿死 ⟺ **M < deliveryPoolSize**（至少留 1 条空闲工作线程）。
`M == deliveryPoolSize` 即全线程被慢连接占满 ⇒ 饿死。该等式在 poolSize ∈ {4,8,16,20} 四档一致复现。
**现状值 4 的边界**：同轮出现 ≥ 4 条慢连接即饿死全部健康订阅者（含心跳、含同轮其他 run）。

### S1.3 场景 A：并发挂起阶梯（1/5/10/20/25 路）

**方法**：`ProbeMatrix#runLadder`，用 `AiExecutorConfig#aiRunExecutor` 的同形模型
（`ThreadPoolTaskExecutor` core=max=`app.ai.suspend.pool-size`=20、队列容量 0 ⇒ `SynchronousQueue`、
`AbortPolicy`），驱动线程并发提交"长挂起"任务（持有 30s latch）。

| 并发路数 | 受理 | 拒绝 | 拒绝的 HTTP 等价 | 首个受理任务启动时延 |
|---|---|---|---|---|
| 1 | 1 | 0 | — | 5 ms |
| 5 | 5 | 0 | — | 0 ms |
| 10 | 10 | 0 | — | 1 ms |
| 20 | 20 | 0 | — | 1 ms |
| **25** | **20** | **5** | **503 SUSPEND_POOL_SATURATED** | 2 ms |

**结论**【实测】：受理上限 = 20 = `suspend.pool-size`，第 21–25 路快速失败；拒绝到 503 的映射由
`AiController#chat` 的 `catch (RejectedExecutionException)` 分支给出（代码锚点，本棒读码确认，
真 HTTP 503 本棒未单独构造）【旁证】。"首字节时延"在本模型口径 = 首个受理任务启动时延（0–5ms），
**不代表真实上游首字节**——真首字节属 `resilience.first-byte-timeout` 观测面，需真 key 路线才可测，
本棒路线（桩/进程内）**无数据**，见 §S5.3 与【待裁决】6。

### S1.4 场景 D：8 路并发 0 丢消息回归

| 指标 | 值 |
|---|---|
| 路数 × 每路帧数 | 8 × 200 |
| 每路最小送达帧数 | **200**（全部 8 路均 200） |
| 总送达 / 期望 | **1600 / 1600** |
| 每路归档帧数（最小） | 200 |
| 丢消息数 | **0** |
| 摘除增量 | 0 |

**结论**【实测】：8 路并发 0 丢消息回归**不破**；每路 seq 连续、归档帧数与期望一致（验收判据④）。

### S1.5 容量联动公式实测校验

```
CPU 核数                          = 16
boundedElastic 容量（实读反射）    = 160      ← reactor.core.scheduler.Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE
公式要求                           = 专用池 20 × 2 + 投递池 4 = 44
44 ≤ 160                           → 成立（余量 116）
```

**结论**【实测 + 推断】：
- `boundedElastic` 容量 = 160 为**实读**（反射 `Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE`，非假设）；
- 「专用池 × 2 + 投递池 ≤ boundedElastic」在**本机 16 核**上**成立**，余量 116（占用率 27.5%）；
- **危险面不在容量，在语义**：本机 16 核下 boundedElastic=160 很宽松，但公式是 `10 × CPU` 的线性函数，
  **4 核部署机**上 boundedElastic=40，公式要求仍为 44 ⇒ **立即不成立**。按提示词"不成立则如实报红"，
  该风险以"条件不成立"形态进【待裁决】第 3 项。
- **旁证（本棒日志实读）**：S8 的摘除 WARN 由线程 `boundedElastic-1/2` 打出
  （`[oundedElastic-1] ... SseChatEmitter: 摘除订阅者 ... reason=实时队列溢出`），
  即**本轮 SSE 出帧路径确实运行在 reactor boundedElastic 上** —— 与 ADR-2 重审 N1 的
  "BLOCKING 形态每次挂起实占专用池 1 条 + boundedElastic 1 条"一致，为公式口径提供本棒日志级旁证【实测】。

**隔离与乱序说明（坑 4）**：探针每 cell 自建池、不读静态 `sharedDeliveryPool`；S7 的两个 conformance 用例
内先按生产路径装配静态池再构造两参注册表，用例前后反射复位静态引用（理由与实现见用例注释）。

---

## S2 —— `/api/ai/health` 投递池指标实测（真实 HTTP）

**命令原文**：

```bash
curl -s http://127.0.0.1:18330/api/ai/health
```

**响应原文（摘录，脱敏：`instanceId` 中主机名/PID/十六进制后缀已替换为占位符，`checkedAt` 以 `...` 占位；`redis` 下 `checkedAgoMs`/`positiveTtlMs`/`negativeTtlMs` 三字段按摘录省略；其余字段与附件 `.sanitized.json` 逐字段一致——红队 F1 自查项）**【实测】：

```json
{
  "available": true,
  "code": "AI_AVAILABLE",
  "model": "s8-stub",
  "baseUrl": "http://127.0.0.1:18398",
  "memoryBackend": "RedisChatMemoryRepository",
  "sessionTtl": "PT6H",
  "confirmTimeoutSeconds": 120,
  "suspendPoolSize": 20,
  "sseDelivery": { "poolSize": 4, "active": 0, "queueDepth": 0, "evicted": 0 },
  "activeSuspendGates": 0,
  "heldSessions": 0,
  "sessionLockRenewals": 0,
  "resilience": {
    "firstByteTimeout": "PT30S", "maxAttempts": 2,
    "interEventTimeout": "PT1M30S", "totalBudget": "PT5M"
  },
  "resumeOnStartup": true,
  "redis": { "available": true, "probes": 2, "failures": 0, "lastError": null },
  "cancellations": { "redisPolls": 0, "degraded": false, "registeredRuns": 0 },
  "instanceId": "instance-<HOST>-pid<PID>-<HEX>",
  "checkedAt": "..."
}
```

### S2.1 四键齐备断言

| 键 | 实测值 | 语义核对（读 `SseChatEmitter#deliveryPoolMetrics`） |
|---|---|---|
| `poolSize` | 4 | `pool.getCorePoolSize()` = **池容量（工作线程数）**，**非当前线程数** —— 实测 `active=0` 而 `poolSize=4`，两值解耦，断言成立【实测】 |
| `active` | 0 | `pool.getActiveCount()`（瞬时值）【实测】 |
| `queueDepth` | 0 | `pool.getQueue().size()`（瞬时值）【实测】 |
| `evicted` | 0 | `EVICTED_TOTAL.get()`（**进程级累计值**，见下）【实测】 |

### S2.2 `EVICTED_TOTAL` 语义标注（硬要求，每处引用并列）

> **`EVICTED_TOTAL` 是 `static final AtomicLong`，进程级累计值（实时队列溢出 / 写出失败 / 投递池拒绝
> 三类共用同一计数器），非窗口值、非本轮值、不区分摘除原因。**

本棒所有引用点均按此标注：§S2.2（`evicted=0`）、§S2.3（差值 2）、§S1 矩阵（"格内增量"，按 cell 前后差值计）、
§S8（healthBefore=1 → healthAfter=2 → 差值 1）。**差值口径假设**：两次 `/health` 采样之间，
除本棒构造的慢订阅者外无其他摘除源（本棒为独占单实例、压测前已清 Redis `ai:*` 键、无第三方客户端）；
该假设不成立时差值会高估本轮摘除数。

### S2.3 压测前后差值（S8 会话内）

| 采样点 | `sseDelivery` |
|---|---|
| 压测前（run 前） | `{"poolSize":4,"active":0,"queueDepth":0,"evicted":1}` |
| 摘除瞬间 | `{"poolSize":4,"active":1,"queueDepth":0,"evicted":2}` |
| 会话结束 | `{"poolSize":4,"active":0,"queueDepth":0,"evicted":2}` |

本轮摘除数 = 2 − 1 = **1**（进程级累计语义，非窗口值）【实测】。

### S2.4 boundedElastic 相关字段

`/health` **未暴露** boundedElastic 容量（现有字段只有 `suspendPoolSize=20` 与 `resilience.*`）。
容量 160 由**本棒 JVM 侧反射实读**（§S1.5）。`/health` 缺失该字段记为**观测面缺口**，进【待裁决】6。
其余定值对象在 `/health` 的暴露情况：`confirmTimeoutSeconds=120`（hitl 120s ✅）、`suspendPoolSize=20` ✅、
`resilience` 四值 ✅、`sessionTtl=PT6H` ✅；**心跳 2s/5s、投递池容量与队列容量 256 未暴露**（缺口，同进【待裁决】6）。

---

## S3 —— 锁内 Redis 往返量化

**方法**：探针 `docs/evidence/probes/M2-T3-5-ProbeLock.java`。**真 Memurai**（127.0.0.1:6379）经
`StringRedisTemplate` + `LettuceConnectionFactory` 构造真 `RunStore`；**未使用 `InMemoryRunStore`**。
每项 ≥1000 次迭代（`events` LRANGE 因单次数毫秒降为 50 次）；单位 **µs**；**读数与本机环境绑定**。

**命令原文**：

```bash
java -cp "<classes;test-classes;deps>" ProbeLock
```

### S3.1 Memurai RTT 基线与锁内单操作

| 操作 | n | p50 µs | p95 µs | p99 µs | max µs | mean µs |
|---|---|---|---|---|---|---|
| `PING`（基线 RTT） | 1000 | 177.8 | 522.9 | 879.7 | 39346.0 | 262.1 |
| `lastEventSeq`（空归档） | 1000 | 135.9 | 304.1 | 434.4 | 9465.8 | 169.8 |
| `lastIssuedSeq`（`GET ai:seq`） | 1000 | 104.5 | 277.4 | 375.4 | 2063.8 | 140.7 |
| `recordIssuedSeq`（`SET ai:seq`） | 1000 | 132.1 | 272.1 | 403.6 | 10861.4 | 162.4 |
| `touchActivity`（`SET ai:beat`） | 1000 | 82.5 | 185.5 | 235.1 | 1051.2 | 102.9 |
| `appendEvent`（rightPush+trim+expire，空归档起） | 1000 | 302.4 | 693.1 | 1295.4 | 2228.0 | 366.1 |
| `lastEventSeq`（归档 = 3000） | 1000 | 68.4 | 112.3 | 401.1 | 3649.7 | 87.3 |
| `appendEvent`（归档 = 3000，trim 生效） | 1000 | 205.2 | 306.9 | 820.1 | 5297.9 | 239.3 |
| `events`（`LRANGE 0..-1` 3000 帧 + 反序列化） | 50 | **3452.1** | 9772.9 | 25112.8 | 25112.8 | 4518.9 |

**读数注记**（【实测】+【推断】）：
- `LLEN_FULL=3000 LLEN_AFTER_TRIM=3000` ⇒ 归档窗口裁剪生效，稳态长度恒为 3000（坑 2 的上界事实）。
- 单次 Redis 往返 p50 在 **102–366 µs**，量级与 `PING` 基线（177.8 µs）同阶 ⇒ 操作开销由网络往返主导，
  非 Redis 服务端处理。
- 若干 `max` 出现 9–39 ms 的孤立尖峰（`PING` max 39.3 ms、`lastEventSeq` max 9.5 ms）：为 JIT/GC/OS 调度噪声，
  p99 与之相差一个数量级，**不应作为锁内延迟的定值依据**（如实登记，不做平滑）。
- **`appendEvent` 是锁内最贵的单操作**（p50 302 µs，含 rightPush+trim+expire 三次往返）；
  `events`（LRANGE 3000）p50 3.45 ms —— 它是 `stageReplay` 的主体，见 §S4。

### S3.2 端到端对照：同一写出器连发 1000 帧

| 路径 | n | p50 µs | p95 µs | p99 µs | max µs |
|---|---|---|---|---|---|
| `emit(状态帧)`：取号 + `SET ai:seq` + `appendEvent` + 入队 | 1000 | **359.6** | 773.3 | 1154.6 | 3005.2 |
| `emit(delta 帧)`：取号 + `SET ai:seq` + 跳档 + 入队 | 1000 | **145.3** | 248.6 | 901.0 | 5614.5 |
| `emit(心跳帧)`：`touchActivity`（1 次 SET）+ 入队 | 1000 | **75.3** | 107.3 | 166.0 | 1124.7 |

（投递池为生产形 4 线程，网络写出在锁外；`EMIT_DELIVERED=3000` 表示 3000 帧全部实际写出。）

**结论（锁内 Redis 往返对出帧延迟的贡献）**【实测 + 推断】：

| 对照 | 锁内 Redis 往返贡献（差分近似） | 占比 |
|---|---|---|
| 状态帧 vs 心跳帧 | 359.6 − 75.3 = **284.3 µs（p50）** | 79.1%（284.3/359.6） |
| delta 帧 vs 心跳帧 | 145.3 − 75.3 = **70.0 µs（p50）** | 48.2% |

- **状态帧相对心跳帧的增量锁内 Redis 成本 284.3 µs（约占状态帧 p50 的 79%）**——增量 = 取号登记 `SET ai:seq` + 落档 `rightPush/trim/expire`。
  这是 T3a 修复的**有意代价**（"取号→落档→入队同锁内完成"换取到达顺序 = seq 顺序），
  本棒量化其绝对量级为**亚毫秒**（p50 0.36 ms、p99 1.15 ms）⇒ 在 16 核本机、单实例、Memurai 同机部署下**可接受**。
  **口径为差分近似（红队 F10 修正）**：心跳帧 emit **并非"纯本地"**——它自身含一次 `touchActivity`（`SET ai:beat`）往返，
  差分把这一次一并扣除，故 79% 是**下限**：真实占比只会更高，**方向安全、数字精度有限**。
  旁证：心跳 emit p50（75.3 µs）低于单测口径的 `touchActivity` p50（82.5 µs，§S3.1），两循环互不校准，印证差分含混。
- **delta 帧只付一次 SET**（跳档不落档），锁内成本约为状态帧的 40%（145.3 µs vs 359.6 µs）
  ⇒ T3-2 的"delta 跳过归档"同时是**锁内延迟优化**（附带收益，非其设计目的）。
- 三条路径均含一次序列化 + 入队；扣除后的 75 µs 实为**心跳帧全量**（含其 `touchActivity` SET），
  故该值**不是"纯本地"量**，仅作差分基线（红队 F10 修正）。
- **口径边界**：本机 Memurai 同机部署（PING p50 177.8 µs）；**跨机 Redis** 下单次往返可达 0.5–2 ms，
  则状态帧锁内成本将升至 2–8 ms 量级 —— 本结论**不外推到跨机部署**。【推断】

---

## S4 —— `stageReplay` 锁内序列化 3000 帧成本量化（二分结论）

**方法**：探针 `docs/evidence/probes/M2-T3-5-ProbeReplay.java`。预置**真 Redis** `ai:events:<runId>` 3000 条
状态帧（`LLEN_BEFORE_REPLAY=3000` 已断言）；投递池 = 生产形 4 线程 + 队列 256。

### S4.1 `replayAndAttach` / `replayTo` 墙钟（锁内 LRANGE + 3000 帧序列化 + 入队）

| 指标 | n | p50 | p95 | max |
|---|---|---|---|---|
| `replayAndAttach` 墙钟（**去预热 17 轮**） | 17 | **8.86 ms** | 17.64 ms | 17.64 ms |
| `replayAndAttach` + 3000 帧全量写出（端到端，去预热） | 17 | 10.51 ms | 19.47 ms | 19.47 ms |
| **首次调用（冷启动）** | 1 | — | — | **125.68 ms** |

逐轮墙钟（µs，含冷启动）：`[125676, 17102, 14845, 17637, 12357, 12468, 11792, 10453, 13168, 8855, 9094, 12165, 8458, 7356, 7037, 7986, 6788, 6994, 6440, 8596]`
`REPLAY_STAGED = 3000 × 20 轮`（3000 帧全部入队，无截断）。

**要点**【实测】：
- **稳态锁内成本 p50 8.86 ms / p95 17.64 ms**，**冷启动首轮 125.7 ms**（JIT + Jackson 首次序列化 + Lettuce 首连）。
  端到端（含全量写出）p50 10.51 ms ≈ 锁内 + 异步 drain ⇒ **写出确实在锁外**（差值 1.65 ms）。
- 与 §S3.1 的 `events`（LRANGE 3000）p50 3.45 ms 对账：锁内成本 = LRANGE+反序列化 3.45 ms + 3000 次
  `writeValueAsString` + 入队 ≈ 8.9 ms，**序列化约占 5.4 ms（61%）**。

### S4.2 并发 `emit` 阻塞量化

一线程持续 `emit` 状态帧；另一线程在窗口内触发 3000 帧回放（共 20 轮）。

| 指标 | n | p50 | p95 | max |
|---|---|---|---|---|
| `emit` 单帧耗时（回放窗口**外**，基线） | 8438 | 469.5 µs | 1285.3 µs | 32043.4 µs |
| `emit` 单帧耗时（回放窗口**内**） | 5 | 2058.3 µs | 4301.4 µs | 4301.4 µs |
| **`emit` 等 `emitLock`（回放线程持锁时定点触发）** | 20 | **1630.1 µs** | **4162.1 µs** | **4162.1 µs** |

回放窗口实测：`[21.3, 6.0, 3.6, 3.6, 4.8, 3.7, 3.5, 4.7, 6.8, 3.4, 4.0, 6.1, 4.0, 3.3, 3.2, 3.2, 3.5, 3.1, 8.6, 4.2]` ms。

**要点**【实测】：定点测量下 `emit` 的等锁时长 p50 **1.63 ms**、max **4.16 ms**；
窗口内 `emit` 上界 4.30 ms，与定点测量同阶。窗口外基线 max 32 ms 是无关噪声（GC），
p50 差值（2.06 − 0.47 = 1.59 ms）与定点等锁 p50 一致 ⇒ **阻塞量级 = 锁被回放持有的剩余时长**。

### S4.3 判据二分结论（**直译设计卡"回放 3000 帧不阻塞 emit"，不许圆场**）

| 判据半边 | 结论 | 依据 |
|---|---|---|
| ① **网络写出不阻塞 emit**（异步投递池，drain 在 `emitLock` 外） | **✅ 成立** | 3000 帧全量写出端到端 p50 10.51 ms ≈ 锁内 8.86 ms；窗口内 `emit` 上界 4.30 ms ≪ 3000 帧写出总时长；慢订阅者首帧写出被 latch 卡住期间 `replayAndAttach` 仍即时返回（`STAGED_BLOCKING=3000`）【实测】 |
| ② **锁内收集 + 逐帧序列化可忽略** | **❌ 不成立（不可忽略）** | 锁内墙钟 **p50 8.86 ms / p95 17.64 ms / 冷启动 125.68 ms**；定点实测 `emit` 被阻塞 **p50 1.63 ms / max 4.16 ms**。相对单帧 `emit` 基线（p50 0.47 ms）是 **3.5×–9×** 的放大。属**一次性、可预测、有界**的开销（每次 reattach 一次），但**不是"可忽略"**。【实测】 |

**结论**【实测】：设计卡验收句"**回放 3000 帧不阻塞 emit**"**只在"网络写出"这一半成立**；
"锁内收集 + 序列化"这一半**不成立**——重挂帧入队期间，同轮其他出帧线程会被 `emitLock` 挡
p50 1.63 ms / max 4.16 ms；冷启动首轮达 125.7 ms。**该断言成立边界 = 只对"网络写出"成立**，
锁内成本显著（8.9 ms p50），是否可接受属定值裁决（见【待裁决】4）。

**R1 裁决复核（回放批豁免实时容量）**【实测】：

| 观测 | 实测值 |
|---|---|
| `staged`（3000 帧回放入队） | **3000**（不受实时容量 256 约束） |
| 入队后 `liveQueued` | **0**（回放批不计入实时段计数） |
| 入队后队列绝对长度 | 3000 |
| 追加 100 条实时心跳后 | `liveQueued = 100`，`evictedΔ = 0`（**健康订阅者未被误摘**） |
| 追加到第 257 条实时帧 | `complete()` = 1，`evictedΔ = 1`、`subscriberCount = 0`（**实时段自身超限仍摘除**） |

⇒ R1 裁决的"回放批豁免 / 实时闸按实时段计数"**双向锚定成立**：回放 backlog 3000 帧压在队列里时
插入实时帧不误摘健康订阅者（红队 S1 修复点回归通过），而实时段自己超 256 仍摘除（S2-1 语义不变）。
**坑 2 说明**：回放队列长度（3000）**不可**当实时溢出读；realtime 闸的判据是 `Subscription#liveQueued`。

---

## S5 —— 投递池定值建议表（按批注 R2：只出建议，不回灌）

### S5.1 建议表（一条一行：键 / 现值 / 建议值 / 依据节 / 约束校验）

| 键 | 现值 | **建议值** | 依据节 | 约束校验 |
|---|---|---|---|---|
| `app.ai.sse.delivery-pool-size` | 4 | **8** | §S1.2（健康订阅者不被饿死 ⟺ M < poolSize；pool=8 时 M ∈ {1,4} 全绿，pool=4 时 M=4 即饿死）、§S1.1（M≥池容量时 `poolActive` 恒 = 池容量，健康 drain 只能排队） | 约束 1：20×2 + 8 = **48 ≤ 160** ✅（余量 112）；4 核机 48 > 40 ❌（条件不成立，见【待裁决】3） |
| `app.ai.sse.delivery-queue-capacity` | 256 | **256（维持不变）** | §S4.3（256 条实时帧 + 3000 帧回放 backlog 不误摘；第 257 条才摘）、§S4.1（回放批豁免该容量，上界 = EVENT_WINDOW 3000） | 约束 2：256 < `EVENT_WINDOW`=3000 ✅（`SseChatEmitterDeliveryTest#liveQueueCapacityStaysBelowTheEventWindowAndComesFromASingleSource` 锚定，本棒线绿）；约束 3：见 §S5.2 |
| `app.ai.suspend.pool-size` | 20 | **20（维持不变）** | §S1.3（并发 20 受理 / 25 路 5 拒绝，阶梯线性，无过早饱和）、§S4（挂起期 2s 心跳在 8.9ms 级锁内成本下远未触及 256 队列） | 约束 1 同上（专用池 ×2 项） |
| `app.ai.hitl.timeout` | 120s | **120s（维持不变）** | §S1.3（挂起并发 20 路在 120s 上限下线性受理，未见与 resilience 的相互放大） | 与 `inter-event-timeout` 90s 的牵制关系（场景 C）本棒**无独立数据**，标【推断】 |
| `app.ai.resilience.inter-event-timeout` | 90s | **90s（维持不变）** | — 本棒**无数据**（场景 C"inter-event 90s < hitl 120s 靠 ToolActivityBeacon"的牵制复验需真 key 长挂起路线） | 标【推断】 |
| `app.ai.resilience.first-byte-timeout` | 30s | **30s（维持不变）** | — 本棒**无数据** | 标【推断】 |
| `app.ai.resilience.max-attempts` | 2 | **2（维持不变）** | — 本棒**无数据** | 标【推断】 |
| `app.ai.resilience.total-budget` | 300s | **300s（维持不变）** | §S8（6004 帧真实 HTTP 流全程 < 3s，预算远未触及；撞预算路径本棒未构造） | 标【推断】 |
| 心跳 2s（挂起期）/ 5s（流式期） | 2s / 5s | **维持不变（未改）** | §S3.2（心跳帧锁内 p50 75 µs，2s 一次 ⇒ 锁占用率 ≈ 0.004%） | 【推断】，且**该值当前在 `/health` 不可观测**（见【待裁决】6） |

**注**：`delivery-pool-size` 的池任务队列容量当前硬编码为 `poolSize * 64`（`RunRegistry` 三参构造），
**不是独立配置键**，故不在建议表内；pool=8 时该值为 512。若采纳 pool=8，此联动自动跟随。

### S5.2 约束 3 的实测依据（256 的合理性）

| 约束 | 实测依据 | 判定 |
|---|---|---|
| 回放 backlog 排空期实时帧峰值 | §S4.3：3000 帧回放 backlog 在队 + 100 条实时心跳 ⇒ `liveQueued=100` 不摘除；第 257 条才摘除 | 256 的上界由**实时段自身**决定，与回放 backlog 解耦 ✅ |
| 心跳（2s）积压 | §S3.2：心跳帧锁内 p50 75 µs ⇒ 256 条需 512 s 才能积满；即慢连接窗口内 2s 心跳占不到容量的 1% | ✅ 256 对心跳积压**极宽松** |
| 真实 HTTP 慢客户端（§S8） | 真实 TCP 背压下慢客户端在 **414 ms** 内即被溢出摘除（摘除时健康侧已收 1699 帧） | ✅ 256 在真实背压下**足够敏感**（< 1 s 摘除） |

⇒ **256 在两个方向（不误摘 / 及时摘）上都经实测校验，维持建议**。

### S5.3 无数据项的处理

上表标【推断】的 6 项（`hitl.timeout` 的牵制关系、resilience 四值、心跳 2s/5s）
**本棒未取得可支撑改值的实测数据**（需真 key 路线构造长挂起 + 上游静默/断流），
**一律不改、保留现值**，并列入【待裁决】6。

> **回灌状态**：本表**仅为建议**。按指挥官批注 **R2**，回灌 `AiProperties` 生产默认值与
> DC-08/ADR-7/技术方案 §13.2#2 闭环措辞，**待指挥官裁决 + 红队复核双卡通过后由后续小棒执行**。

---

## S6 —— 真实 `RunStore` delta 跳档分支直测

**方法**：探针 `docs/evidence/probes/M2-T3-5-ProbeDelta.java`，真 Redis（Memurai）。

### S6.1 delta 不入档 + `ai:seq` 刷新 / TTL

```json
DELTA_SKIP {"llenAfter5Delta":0,"llenAfterState":1,"llenAfterHeartbeat":1,
            "seqKeyValue":"7","seqKeyTtlSeconds":21600,"eventsKeyTtlSeconds":21600,
            "configuredSessionTtlSeconds":21600}
```

**结论**【实测】：
- 连发 5 条 delta 后 `LLEN ai:events:<runId>` = **0**（delta **不入档**）；
  再发 1 条状态帧 → 1；再发心跳 → 仍 1（心跳同样不入档，与 T7 一致）。
- `ai:seq:<runId>` 随 `recordIssuedSeq` 刷新（值 `"7"`），**TTL = 21600 s = 6h = `app.ai.session-ttl`**；
  `ai:events:<runId>` 的 TTL 同为 21600 s ⇒ **两键同寿**（承诺成立）。

### S6.2 跳档分支：归档末帧落后于已发放 seq ⇒ 跨写出器续号取 `max+1`

```json
DELTA_JUMP {"firstEmitterSeqs":[1,2,3],"archivedLastSeq":1,"issuedSeqAiSeq":3,"llenAfterSkip":1,
            "resumedEmitterSeqs":[4,5],"expectedResumeStart":4}
```

构造：写出器 #1 发 `状态帧(seq1)` → `delta(seq2)` → `delta(seq3)`；归档末帧 = 1（delta 跳档），
`ai:seq` = 3。**模拟进程重启**：新建写出器 #2（自身计数器从 −1 起）发两帧 ⇒ 实测得到 `[4, 5]`，
`expectedResumeStart = max(1, 3) + 1 = 4`。

**结论**【实测】：**续号取 `max(归档末帧, ai:seq) + 1`**，无重号、无跳号 ——
T3-2"发放即登记 + 跳档续号"在**真 Redis** 上端到端成立（单测为 `InMemoryRunStore` 口径，
本棒补真 Redis 口径）。

### S6.3 SET 失败分支（真 Redis 不可达端点，**非 mock**）

```json
DELTA_SET_FAILURE {"threwException":false,"exception":"","framesDelivered":4,"deliveredSeqs":[1,2,3,-1],
 "warnCount":3,"warnSamples":[
  "WARN 登记已发放序号失败 runId=<runId> seq=1（继续出帧，跨进程续号可能回退）：Unable to connect to Redis",
  "WARN 登记已发放序号失败 runId=<runId> seq=2（继续出帧，跨进程续号可能回退）：Unable to connect to Redis",
  "WARN 登记已发放序号失败 runId=<runId> seq=3（继续出帧，跨进程续号可能回退）：Unable to connect to Redis"]}
```

**方法**：`RunStore` 绑定到 127.0.0.1:**6399**（无监听的真实不可达端点），并挂 logback `ListAppender`
捕获 `RunStore` 的 WARN。**未使用 mock/桩对象**。

**结论**【实测】：`SET` 失败路径 **不抛异常**（`threwException=false`），出帧继续
（4 帧全部送达，业务 seq `[1,2,3]` + 心跳帧无业务 seq `−1`）；**恰好 3 条 WARN**（每条对应一次
`recordIssuedSeq` 失败），日志文案与 `RunStore#recordIssuedSeq` 的 S2-4 口径一致 ⇒
"登记失败仅 WARN + 继续出帧"在真故障注入下**成立**。

**命令级拒写补测（红队 F5 落实，原注入口径窄于声称场景）**【旁证】：上文"真故障注入"在本棒只覆盖
**连接级**失败（6399 无监听 ⇒ `RedisConnectionFailureException ← ConnectException`），未覆盖"Redis 在线但拒绝写"。
GLM-5.3 红队另建假 RESP2 服务器注入**命令级**拒写（READONLY / OOM 两模式：`SET` 家族恒被拒，
`GET/LRANGE/RPUSH/LTRIM/PEXPIRE` 照常）补测同一路径，**五项判据逐项通过**：不抛异常 / 4 帧全达 /
`[1,2,3,-1]` / 恰 3 条 WARN / 文案一致（顶层异常 `RedisSystemException ← RedisReadOnlyException｜
RedisCommandExecutionException`，与连接级同被 `catch (Exception)` 兜住）。
产物：`<SCRATCH-REDTEAM>\setfail\`（`probe-{readonly,oom,deadport,rollback}.log` + `server-*.log` + `RESULTS.md`）。
⇒ 该结论已在**连接级 + 命令级两类失败**上锚定。

### S6.4 断流期正文尾部不可复原（S3-4 后果登记，不改设计口径）

```json
DELTA_TAIL_LOSS {"deltasEmitted":50,"archivedFrames":1,"replayedFrameTypes":["retry"],"replayedStaged":1}
```

**结论**【实测】：发出 50 条 delta 正文后，归档只有 **1** 帧（那条状态帧）；
`replayTo` 只回放出 `["retry"]` 1 帧 ⇒ **未被前端收到即断流的正文尾部永久丢失**。
这是 delta 跳档（T3-2）的**登记后果**，本棒**只登记、不改设计口径**（同 S3-4 裁定）。

---

## S7 —— conformance 层三参装配用例（GLM S3-2 复核意见落实）

**新增用例**：`backend/src/test/java/com/example/configmgr/ai/conformance/ThreeArgAssemblyConformanceTest.java`
（5 个用例，源 GLM S3-2 复核意见：三参装配路径无直接自动化用例）。

| # | 用例 | 锁住的事实 |
|---|---|---|
| 1 | `threeArgAssemblyUsesTheSharedAsyncPoolAndTheConfiguredQueueCapacity` | 三参（`properties != null`）⇒ `deliveryExecutor` 是**异步共享投递池**（`isInstanceOf(ThreadPoolExecutor)` 且 `isSameAs(sharedDeliveryExecutorOrDirect())`），池容量 = `delivery-pool-size`；队列容量 = 配置缺省且 `< EVENT_WINDOW` |
| 2 | `threeArgAssemblyHonoursCustomDeliveryPoolAndQueueCapacity` | 自定义 `delivery-pool-size=3 / delivery-queue-capacity=128` 生效 |
| 3 | `twoArgAssemblyStaysInlineEvenAfterTheSharedPoolIsConfigured` | 静态池已装配时，**两参仍固定同步直执**（隐性竞态隔离性） |
| 4 | `staticPoolIsNotConfiguredByTheTwoArgPathAndEvictedTotalIsOnlyMonotonic` | 两参路径**不装配**进程静态池；`EVICTED_TOTAL` 只断言单调不减（**不**断言绝对值——进程累计语义） |
| 5 | `threeArgAssemblyKeepsSingleSubscriberFifoAndContiguousSeq` | 三参（异步池）路径下 200 帧仍**单订阅者 FIFO + seq 1..200 连续** |

**静态态卫生实现**（用例内注释已说明理由）：`@BeforeEach` 反射置 `SseChatEmitter.sharedDeliveryPool`
为 `null`、`@AfterEach` 复位到进入时的原值（并 `shutdownNow` 本用例新建的池）——
不改生产代码（授权禁止），只隔离"首个装配者胜"的跨用例污染。【实测】

**门禁回跑（全量）**：

```bash
"<MAVEN_HOME>/bin/mvn.cmd" -f backend/pom.xml -B test
→ [INFO] Tests run: 289, Failures: 0, Errors: 0, Skipped: 0
→ [INFO] BUILD SUCCESS        (EXIT=0)
```

**XML 口径核对**（`backend/target/surefire-reports/TEST-*.xml` 求和，43 个文件）：

```
tests=289 failures=0 errors=0 skipped=0
```

| 项 | 基线 | 本棒 | 判定 |
|---|---|---|---|
| 执行用例数 | 284（设计卡门禁基线） | **289** | **只增不减 ✅**（+5 = 新用例类 5 条） |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | 0 / 0 / 0 | ✅ |

---

## S8 —— 真实 HTTP + 慢客户端集成冒烟

**口径声明**：S8 = **真实 HTTP/TCP**【实测】；§S1 = **进程内替身**（`RecordingEmitter` 造慢 SseEmitter）
【实测】。两处结论**不互相替代**。

**方法**：
- 后端：18330（启动前 `netstat` 无 LISTENING），日志 `<SCRATCH>/boot.log`，`AI_BASE_URL=http://127.0.0.1:18398`
  指向**临时上游桩** `<SCRATCH>/s8-stub.mjs`（不入仓，6000 帧 / 20 字符每帧；启动前查 18398 占用）。
- 健康客户端 A：`POST /api/ai/chat`（本轮首订阅者），正常读流。
- 慢客户端 B：`GET /api/ai/events/{runId}`（**同轮第 2 订阅者**，即同一 `SseChatEmitter` 扇出），
  读 25 帧后 `res.pause()`，制造**真实 TCP 背压**。
- 摘除判定：轮询 `/api/ai/health` 的 `sseDelivery.evicted` 差值。

**命令原文**：

```bash
node <SCRATCH>/s8-slow-client.mjs --base http://127.0.0.1:18330 --out <SCRATCH>
```

### S8.1 结果（附件：`T3-5-附件-S8-慢客户端结果.json`）

| 观测项 | 实测值 | 判定 |
|---|---|---|
| 慢客户端 pause 前已收帧 | 25 帧（cap 摘要 seq 1,2,4,5…） | — |
| **摘除耗时（pause → evicted 增量可见）** | **414 ms** | ✅ 溢出摘除及时 |
| `/health` `sseDelivery` 压测前 → 摘除瞬间 | `evicted 1 → 2`（进程级累计语义，非窗口值） | ✅ 差值 1 > 0 |
| **慢客户端终态事件** | `closeEvent = "end"`（服务端主动 `emitter.complete()` 后正常收流尾） | ✅ 主动断连 |
| 慢客户端最终状态 | `slowClosed = true` | ✅ |
| **健康客户端窗口内相邻帧最大间隔** | **3 ms**（窗口内 1460 个间隔样本，窗口 = pause → 摘除） | ✅ **< 1s** |
| 健康客户端总收帧 | 6004 / 6004（含 `start`/`message_start`/6000 delta/`message_end`/`done`） | ✅ 零丢帧 |
| 健康客户端终态 | `closeEvent = "end"`（`done` 帧后正常收尾） | ✅ |
| 重挂请求 | `GET /api/ai/events/{runId}?lastSeq=1227` | ✅ 走 `replayAndAttach` 链路 |
| 重挂时本轮是否仍在跑 | `attachedRunWasLive = true` | ✅ 覆盖在飞轮重挂 |
| 重挂收帧数 | 4293（回放 + 续接实时） | — |
| 重挂帧 seq 严格递增 | `true` | ✅ |
| 重挂帧全部 `> lastSeq` | `true` | ✅ 孤儿过滤生效 |
| 重挂帧与慢客户端已收帧**重复** | `[]`（0 条） | ✅ **无重号** |
| **归档面（非 delta）覆盖度** | 健康侧归档面 4 帧（`start`/`message_start`/`message_end`/`done`），`archivedFaceMissingInRecoverable = []` | ✅ **归档状态帧子序列无洞** |
| delta 面 | 6000 帧（**按 T3-2 不落档**） | 见 S8.3 边界说明 |
| 后端日志 ERROR 条数 | **0**（佐证：附件 `T3-5-附件-S2S8-bootlog时间线摘录.txt` 的"零 ERROR 佐证行"；`grep -c " ERROR " boot.log` 复算 = 0） | ✅ 仅预期 WARN（摘除） |

**摘除 WARN 原文（脱敏）**【实测】：

```
WARN  <pid> --- [config-mgr] [oundedElastic-2] c.e.configmgr.ai.run.SseChatEmitter
      : 摘除订阅者 runId=<UUID> reason=实时队列溢出（容量 256）
```

（`[oundedElastic-2]` = logback 15 字符截断的 `boundedElastic-2` —— 即出帧路径运行在 reactor
boundedElastic 线程上，为 §S1.5 的联动公式口径提供日志级实测旁证。）

### S8.2 与 S1（替身）口径差异

| 维度 | S1（`ProbeMatrix`） | S8（真实 HTTP） |
|---|---|---|
| 慢的来源 | `SseEmitter#send` 阻塞在 latch（进程内替身） | 真实 TCP：客户端 `pause()` → 内核收发缓冲填满 → 服务端 write 阻塞 |
| 订阅拓扑 | 每 run 单/多订阅者（探针自建） | 同一 `SseChatEmitter` 的真实扇出（`/chat` + `/events/{runId}`） |
| 摘除触发点 | 显式灌 257 条实时帧 | 真实上游流（6000 delta）自然灌满 |
| 可测性 | 可穷举 N×M 矩阵 | 单点场景，不可穷举 |
| 结论 | **M < poolSize 才不饿死**（矩阵） | **M=1 时零影响**（3 ms 偏差）—— 与矩阵 M=1 行一致 |

两口径**互相印证**：S1 的 M=1 行（偏差 0ms）与 S8 的 3ms 同向；S1 的 M≥4 饿死结论
**未**在 S8 复现（S8 只有 1 个慢客户端），因此 S8 **不构成**对 S1 矩阵结论的反驳。

### S8.3 "seq 无洞"的成立边界（诚实标注）

- ✅ 成立：**归档状态帧子序列无洞**（`archivedFaceFullyCovered=true`，重挂帧与已收帧零重复、
  严格递增、全部 `> lastSeq`）。
- ⚠️ **不成立**：**delta 帧序列跨断档不可复原** —— 6000 条 delta 按 T3-2 跳过归档，
  慢客户端断连期间未收到的 delta（实测断档区间为 seq 1227→1712 之间）**重挂补不回来**。
  这与 §S6.4 的后果登记同源，属 **T3-2 已裁定的设计口径**，本棒**只登记、不改**。

---

## S9 —— E2E 27 用例回跑门禁

**所跑路线**：**桩模式（必跑路线）**。真 key 路线（可选）**未跑**（本棒时间预算用于 E1–E8 定值证据，
真 key 不改变本棒任何定值结论）。

**命令原文**：

```bash
node scripts/ci/stub-upstream.mjs --self-check
node scripts/ci/stub-upstream.mjs --port 18399 > <SCRATCH>/stub.log 2>&1 &
"<MAVEN_HOME>/bin/mvn.cmd" -f backend/pom.xml -B -DskipTests package
# 起后端：AI_API_KEY=ci-stub-placeholder AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub
#   --server.port=18330 --spring.datasource.url="jdbc:h2:file:./data/ci_e2e_db;DB_CLOSE_DELAY=-1"
#   --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150
#   --logging.level.com.example.configmgr=DEBUG  日志重定向 <SCRATCH>/boot.log
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog <SCRATCH>/boot.log -ArtifactDir <SCRATCH>/e2e-artifacts
```

**前置**：压测前清 Redis `ai:*` 键（`DELETED_AI_KEYS=16 REMAINING=0`）+ 删 H2 `ci_e2e_db` 文件，取干净起点。
端口 18330/18399 启动前均 `netstat` 确认空闲；后端与桩均**按 PID** 停止（未用 `taskkill /IM java.exe`）。

### S9.1 门禁判据逐条

| 判据 | 要求 | 实测 | 判定 |
|---|---|---|---|
| E2E 主用例 | PASS=27 FAIL=0 | `结果：PASS=27  FAIL=0` | ✅ |
| GFSKIP | 0 | `GFSKIP=0` | ✅ |
| Q8 项 | PASS=2 FAIL=0 | `Q8 项：PASS=2  FAIL=0` | ✅ |
| 退出码 | 0 | `E2E_EXIT=0` | ✅ |
| 后端日志 | 零 ERROR | `grep -c " ERROR " <SCRATCH>/boot-e2e.log` = **0** | ✅ |
| 后端单测 XML | 284 执行 / 0/0/0，只增不减 | `tests=289 failures=0 errors=0 skipped=0` | ✅（+5 新用例） |
| 前端 typecheck | 绿 | `yarn typecheck` → `Done in 4.49s`，EXIT=0 | ✅ |
| 前端 build | 绿 | `yarn build` → `✓ built in 28.52s`，EXIT=0 | ✅ |
| 工作区未提交 | 授权范围内 | §0.3（6 个新增未跟踪文件） | ✅ |

**四项判据的佐证出处（红队 F11.1 缺口登记）**：上表"E2E 主用例 / GFSKIP / Q8 项"三项可在附件
`T3-5-附件-S9-E2E日志.log` 的汇总块逐字复核 ✅；**退出码 `E2E_EXIT=0`、`boot-e2e.log` 零 ERROR、
桩自检 9 项 PASS** 三项本棒只留在清单外 `<SCRATCH>/`（`boot-e2e.log` 与自检会话输出），**未随证入附件**
⇒ 在附件范围内不可复核，按本报告自己的证据标准仍属**缺口**（红队已在清单外复验全部为真，见红队报告 §F11.1）。
`boot.log` 一侧的时间线已按红队 F11.1 补入附件清单（`T3-5-附件-S2S8-bootlog时间线摘录.txt`）；
上述三项留待后续棒补挂。

**桩自检**（`--self-check`）全 9 项 PASS：零运行时依赖 / 路由表无原型污染 / 结构自洽 / 白名单对账 /
收敛自证 / 等长重发自证 / 披露清单驱动 / SSE 帧形态 / 工具结果反解。

**GF 用例明细（5/5 PASS）**：GF1 FILTER 表单值回灌、GF2 CLARIFY 表单续跑回显、GF3 取消路径
（帧/pending/日志三面一致）、GF4 复核拒绝→改值重发、GF5 幂等 409 双向。

---

## 【待裁决】

> 每项：建议 + 依据 + 不决后果。**本棒只给建议，一切回灌动作按批注 R2 延后。**

**1. 投递池定值建议（`delivery-pool-size` / `delivery-queue-capacity`）是否采纳并授权回灌 `AiProperties`**
- 建议：`delivery-pool-size` **4 → 8**；`delivery-queue-capacity` **维持 256**。
- 依据：§S1.2（健康订阅者不被饿死 ⟺ `M < poolSize`；现值 4 意味"同轮 ≥4 条慢连接即饿死全部健康订阅者"，
  pool=8 时 M ∈ {1,4} 全绿）；§S4.3 + §S5.2（256 在两个方向都经实测校验）；§S1.5（48 ≤ 160，余量 112）。
- 不决后果：`delivery-pool-size` 维持 4 时，生产上任何"4 路以上同轮慢连接"（移动端弱网、离线标签页、
  客户端暂停读流）都会让**同轮全部健康订阅者（含心跳）停投**，只能等慢连接被 TCP 超时/摘除后恢复；
  用户观感 = "AI 回复卡住不动"。

**2. 冻结文档回灌范围（DC-08 / ADR-7 / 技术方案 §13.2#2 标闭环的措辞建议）**
- 建议：闭环措辞**只声明本棒已实测的三项**——① 投递池容量/队列容量定值（`4→8` / `256 维持`）；
  ② 联动公式"专用池 ×2 + 投递池 ≤ boundedElastic"在 **16 核部署机**成立（44 ≤ 160）；
  ③ 验收判据①④（慢订阅者隔离、8 路 0 丢）本棒复跑通过。
  **不建议**在同一句里声明 resilience 四值与心跳 2s/5s 已闭环（本棒无数据，见第 6 项）。
- 依据：§S1、§S4、§S9；§S5.3。
- 不决后果：若把"定值对象"整包标闭环，会把 6 个**无实测支撑**的值一并冻结，等于用压测报告为
  未验证的常量背书（后续真 key 压测若推翻，需二次解冻）。

**3. 联动公式若不成立时的处置（降专用池 / 增 boundedElastic / 改公式，三选一）**
- 现状：**本机（16 核）成立**；但公式是 `10 × CPU` 的线性函数 ⇒ **4 核部署机** boundedElastic=40 < 要求 44，
  **立即不成立**（8 核机：80 ≥ 44 成立；若采纳 pool-size=8 则要求 48，4 核仍不成立）。
- 建议（三选一）**推荐第 3 条**：**改公式为"按部署核数校验的准入式"** ——
  把「专用池 ×2 + 投递池 ≤ 10 × CPU」写成**启动期校验/告警**（或文档明示最低核数：
  采纳 pool=8 时要求 **CPU ≥ 5**），而不是继续当作一条"恒真的设计不变量"。
  次选降专用池（20→15）：要求 15×2+8 = **38 ≤ 40**，在 4 核机**成立**（20→16 的 16×2+8 = 40 ≤ 40 亦成立，
  即降到 **≤16 均可**）——原写"15×2+8=38 > 40? 仍不成立"系**算术错误**（红队 F3）。**次选可行，但把挂起
  并发上限从 20 压到 15–16**；推荐项仍取第 3 条，理由是**不牺牲容量且对全部核数自适应**。
  不建议增 boundedElastic（官方硬编码 `subscribeOn(boundedElastic())`，容量由 reactor 默认推导（可由系统属性 reactor.schedulers.defaultBoundedElasticSize 覆盖））。
- 依据：§S1.5（实读 160；线性外推 4 核 = 40）。
- 不决后果：在低核数容器/VM 部署时，`boundedElastic` 会在挂起并发攀升时排队，
  **拖慢全部 AI 流式（含无关会话）**，而 `/health` 又不可观测（见第 6 项），排障只能靠现场推断。

**4. 「回放 3000 帧不阻塞 emit」成立边界（S4 二分结论）+ "回放分片/游标续灌"是否复活**
- 实测二分：**网络写出不阻塞 ✅ 成立；锁内收集 + 序列化不可忽略 ❌**（p50 8.86 ms / p95 17.64 ms /
  冷启动 125.68 ms）；`emit` 被阻塞 p50 1.63 ms / max 4.16 ms。
- 建议：**当前不复活"分片/游标续灌"**。理由：8.86 ms/次、每次 reattach 一次的**有界一次性开销**，
  在 16 核同机 Redis 下对 p99 出帧延迟（1.15–1.85 ms 基线）的冲击量级为个位数毫秒；
  而分片化会**破坏 `replayAndAttach` 的交接原子性**（S5c-2：回放快照 + 挂订阅同锁），
  引入新的丢帧窗口，代价高于收益。**但设观察项**：若出现 ① 跨机 Redis、② 归档窗口上调（> 3000）、
  ③ p99 出帧延迟 SLA < 10 ms 三者之一，则重新评估分片/游标续灌。
- 依据：§S4.1–§S4.3、§S3.1（`events` LRANGE p50 3.45 ms）。
- 不决后果：不裁决则"判据①/②"的**成立边界**在文档里语焉不详，后续复核会重复争议同一句验收词；
  若误按"全句成立"回灌冻结文档，则是一次**未经实测支撑的断言冻结**。

**5. `EVICTED_TOTAL` 是否拆分按原因计数**
- 建议：**本轮不改，登记为观察项**。若要改，最小形态 = 三个 `AtomicLong`（overflow / writeFail / poolReject）
  并在 `/health` 的 `sseDelivery` 下展开为三个键。
- 依据：§S2.2（现为单一 `static final AtomicLong`，溢出/写出失败/池拒绝共用）；
  §S1.1（20/20 格差值非零但**无法区分**是"实时溢出"还是"池拒绝"）；§S8.1（真实摘除原因只能从 DEBUG 日志确认）。
- 不决后果：线上出现"evicted 增长"时**无法从 `/health` 直接定位摘除原因**，
  必须翻 `WARN` 日志；对"扩大投递池后 evicted 仍增长"这类回归定位尤其低效。

**6. 施工中发现的规格缺口 / 异常**
- **(6a) `/health` 观测面缺口**：`sseDelivery` **未暴露** boundedElastic 容量、心跳间隔（2s/5s）、
  `delivery-queue-capacity`。本棒只能把容量从 JVM 侧反射读出（§S2.4）。
  建议：把 `deliveryQueueCapacity` 与 boundedElastic 容量补进 `/health`（后者需 reactor 内部常量，可退化为
  `10 × availableProcessors()` 计算值并标注来源）。
- **(6b) 场景 B（长挂起 10 分钟开销）本棒未跑**：需真实 upstream 保持 10 分钟静默且不触发
  `inter-event-timeout=90s`（需真 key 路线 + 特殊上游行为），**本棒无数据**，不作为闭环依据。
- **(6c) 场景 C（两条牵制复验）本棒未跑**：同理需真 key 长挂起路线；`inter-event 90s < hitl 120s`
  靠 `ToolActivityBeacon` 的机制关系**仅在设计中成立**，本棒**无实测**。按批注 R5，联动公式不成立时进本项；
  此处登记的是**"牵制关系无数据"**这一缺口（非公式不成立）。
- **(6d) 真 key 路线未跑**（可选路线）：`resilience` 四值与心跳间隔的定值**因此全部无本棒数据**（§S5.3）。
- **(6e) `/health` 的 `evicted` 基数非零起点**（红队 F2：改为事实版）：**本棒首采实为 0**
  （§S2 响应摘录 `checkedAt` 15:47:42.7、`evicted=0`，即 pid 15640 启动后约 3 秒）——此前"本棒会话内
  首采即为 1"系**误记**。`evicted=1` 的真实来源是**同进程**（PID 15640 全程未重启）在 **15:48:19** 另有
  一轮更早的 S8 式运行（runId `2ff2ae15`）先摘除一次（0→1）；附件记录轮是 **15:48:42** 那轮（1→2）。
  支撑该时间线的 `boot.log` 关键行已按红队 F11.1 摘录补入附件清单
  （`T3-5-附件-S2S8-bootlog时间线摘录.txt`）。所有读数仍以**差值**口径处理并在正文标注；
  跨棒复用同一进程时会污染读数。
- **(6f) 提请注意（非缺陷）**：§S3.1 中 `PING` max 39.3 ms 等孤立尖峰，属 JIT/GC 噪声，
  **不宜**被后续棒引用为"Memurai 抖动"证据。

**7. 故障形态可观测性观察项（红队 F4 / F6 / F7 并入；与第 5 项 `EVICTED_TOTAL` 拆分同族）**
- **F4【S2 级·风险登记域扩大】只读副本下"跨进程续号回退"是稳态可达**：只读副本 / `maxmemory` 触顶 /
  ACL 只读等形态下 `SET` 恒被拒而 `RPUSH`/`LTRIM`/`PEXPIRE` 照常成功（`RunStore#appendEvent` 走 LIST），
  归档面看起来完全健康，续号锚点 `ai:seq` 却恒为 0。红队以假 RESP2 服务器构造"SET 被拒、LIST 可用"
  稳态实测：进程 1 实发 seq 1..4（2/3/4 为不落档 delta）→"重启"后锚点 = `max(归档末帧 1, ai:seq 0) = 1`
  → 新写出器**重发 seq 2**，落入前端 `lastSeq=4` 覆盖区被 `SseChatEmitter#isOrphan` **静默丢弃**：
  **回退 3 帧、零报错**。⇒ `RunStore#recordIssuedSeq` 的 javadoc 把该回退风险限定为"整体 Redis 宕机"
  场景，**口径偏窄**（实为任何"SET 被拒而读/LIST 可用"形态下的稳态行为）。
  **注记：javadoc 措辞修正归后续棒，本棒只登记。** 产物 `<SCRATCH-REDTEAM>\setfail\probe-rollback.log` + `server-rollback.log`。
- **F6【S3·可观测性】命令级失败的 WARN 只打 `Error in execution`，不含 READONLY/OOM 判据词**：
  `RunStore#recordIssuedSeq` 记的是 `ex.getMessage()`（Spring 外层包装的固定串），**未遍历 cause 链**
  （连接级为 `Unable to connect to Redis`）⇒ 运维拿到命令级 WARN **无法区分**"只读副本 / OOM / ACL"
  三种故障性质。建议随第 5 项一并评估最小改法（遍历 cause 链取根因判据词）。
- **F7【S3·可观测性】`ai:beat` 活动证据在命令级拒写下静默失效**：`RunStore#touchActivity` 的 catch
  仅 **debug 级**，命令级拒写时 delta/心跳携带的 `SET ai:beat:*` 同样被拒且无 WARN（红队服务器日志实测
  readonly 臂 2 次、rollback 臂 3 次全静默）⇒ `ResumeService#activeAtMs` 的跨进程活动证据
  （`lastEventAtMs` / `lastBeatAtMs`）双双归零，**长轮可能被僵尸判据误接管（同一轮执行两次）**。
  登记为观察项，与 F4 同族。

---

## 附件清单

| 附件 | 内容 |
|---|---|
| `T3-5-附件-S1-矩阵原始stdout.log` | 场景 E 20 格 + 池容量扫描 16 格原始 `CELL`/`SIZING` JSON。**provenance 注记（红队 F11.2）**：本附件（矩阵原始 stdout）由探针**修订前**版本产出、**以未捕获异常收尾**（`IllegalArgumentException at ProbeMatrix.runLadder`，无 `PROBE DONE`）；入仓探针源码是崩溃后的修订版，**场景 A/D 数据来自附件二**（`T3-5-附件-S1-场景AD与联动公式.log`）的重跑 |
| `T3-5-附件-S1-场景AD与联动公式.log` | 场景 A 阶梯、场景 D 8 路、联动公式原始 JSON（场景 A/D 的唯一数据源） |
| `T3-5-附件-S2-health压测前.json` + `.sanitized.json` | `/api/ai/health` 原文（`.sanitized.json` 为入正文引用的脱敏版；`probes=2`，正文摘录已按红队 F1 与此对齐） |
| `T3-5-附件-S2S8-bootlog时间线摘录.txt` | **新增（红队 F11.1）**：`<SCRATCH>/boot.log` 关键时间线摘录——进程启动（PID 15640）/ 15:48:19 摘除（runId `2ff2ae15`，0→1）/ 15:48:42 摘除（runId `45b879a4`，1→2）/ 零 ERROR 佐证行；支撑 §S2（首采 0）、§S2.3（差值 1）、§S8.1（后端零 ERROR）与【待裁决】6e |
| `T3-5-附件-S3-锁内Redis往返原始stdout.log` | 12 项 p50/p95/p99/max 原始 `METRIC` JSON |
| `T3-5-附件-S4-回放成本原始stdout.log` | 回放墙钟逐轮值、emit 阻塞分布、豁免实测 JSON。**单位注记（红队 F9）**：`REPLAY_WALL_ALL_US` / `REPLAY_FULL_ALL_US` 字段名误标 `_US`，实装**纳秒**（同文件 `p50us=8855.0` 即其 1/1000）；正文 §S4.1 换算正确 |
| `T3-5-附件-S6-delta跳档原始stdout.log` | 4 组 `DELTA_*` 原始 JSON（含 WARN 原文） |
| `T3-5-附件-S7-后端全量单测.log` | `mvn -B test` 全量控制台输出 |
| `T3-5-附件-S8-慢客户端结果.json` | S8 完整结果（含帧样本序列） |
| `T3-5-附件-S8-慢客户端stdout.log` | S8 分步 stdout |
| `T3-5-附件-S8-慢客户端脚本.mjs` / `T3-5-附件-S8-临时上游桩.mjs` | `<SCRATCH>` 临时脚本随证留存（不入仓） |
| `T3-5-附件-S9-E2E日志.log` | E2E 全量输出（27 用例逐条 + 摘要） |
| `T3-5-附件-S9-桩stdout.log` | 官方桩 `stub-upstream.mjs` 的请求级决策日志 |
| `T3-5-附件-S9-前端门禁.log` | `yarn typecheck` + `yarn build` 结果摘要 |

**附件内仍不可复核者（缺口，红队 F11.1 保留登记）**：`E2E_EXIT=0`、`boot-e2e.log` 零 ERROR、桩自检 9 项输出
（三者本棒只留在清单外 `<SCRATCH>/`）；`t35-scratch/backend.pid` 记录的 PID 与 JVM 实际 PID 不一致
（红队 F11.3，未纳入本棒修正清单，留后续棒）。

**探针入仓**（`docs/evidence/probes/`，不参与构建与测试计数）：
`M2-T3-5-ProbeMatrix.java`、`M2-T3-5-ProbeLock.java`、`M2-T3-5-ProbeReplay.java`、`M2-T3-5-ProbeDelta.java`。
其中 `M2-T3-5-ProbeMatrix.java` 头部已按红队 F11.2 补入 provenance 注记（注释级，探针逻辑未改）。
