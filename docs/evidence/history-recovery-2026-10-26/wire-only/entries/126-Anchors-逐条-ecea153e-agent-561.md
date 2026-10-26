> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-561` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：31538 B / 24583 字符 / 529 行；行指纹覆盖率（仓内 + 落盘语料）2.1%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# Anchors（逐条）

| # | 锚点 | 预期 | 实测（行号 + 原文） | 判定 |
|---|---|---|---|---|
| 1a | TaskSseService 文件路径 | `...configmgr/job/service/TaskSseService.java` | **该路径不存在**。实际：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`（包 `com.example.configmgr.task.service`） | **漂移**（包名 `job`→`task`；按原文路径 Read/Glob 会 404） |
| 1b | `:73` subscribe | `subscribe` 方法 | `73: public SseEmitter subscribe(Long taskId) {`（全文 73-90） | 一致 |
| 1c | `:87-88` 首帧 HEARTBEAT | 注释 + `sendEvent(..., "HEARTBEAT", ...)` | `87: // Send initial heartbeat`<br>`88: sendEvent(taskId, emitter, "HEARTBEAT", Map.of());` | 一致 |
| 1d | `:92-101` onTaskChanged→TASK_CHANGED | 注解+方法+发布 | `92: @EventListener`<br>`93: public void onTaskChanged(TaskChangedEvent event) {`<br>`94: publish(event.getTask().getId(), "TASK_CHANGED", Map.of(` … `101: }` | 一致 |
| 1e | 无周期心跳调度 | 无 `schedule/Scheduled/Timer`；`heartbeat` 仅 1 处发布点 | `schedule`/`Scheduled`/`Timer` **0 命中**；`heartbeat`（不区分大小写）**6 命中**：87、88（发布点=88）、153/166/181/199（均为 javadoc/日志/注释文本） | 一致 |
| 2a | `JobWatchdog.java` = 258 行 | 258 | `wc -l` = **258** | 一致 |
| 2b | `:114-115` `@PostConstruct void start()` | — | `114: @PostConstruct`<br>`115: void start() {` | 一致 |
| 2c | `:117` 单线程调度器 | — | `117: ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {` | 一致 |
| 2d | `:123` scheduleWithFixedDelay | — | `123: svc.scheduleWithFixedDelay(this::scanSafely, scanPeriodMs, scanPeriodMs, TimeUnit.MILLISECONDS);` | 一致 |
| 2e | `:159-160` scan() 起 + findByStatusIn | 同一/相邻两行 | `159:` 是 javadoc `/** 扫一遍在途作业并判定（包级可见…）*/`；`160: void scan() {`；**`161: List<Job> inFlight = this.jobRepository.findByStatusIn(IN_FLIGHT);`** | **轻微漂移**（`findByStatusIn` 在 161，非 159-160；scan() 起于 160） |
| 3a | ExportJobRunner:271 | `publish(..., "JOB_PROGRESS", ...)` | `271: taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(`（在 `publishProgress` 内，269 起） | 一致 |
| 3b | ImportJobRunner:216 | 同上 | `216: taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(` | 一致 |
| 3c | PrecheckJobRunner:360 | 同上 | `360: taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(` | 一致 |
| 3d | PublishJobRunner:364 | 同上 | `364: taskSseService.publish(job.getTaskId(), "JOB_PROGRESS", Map.of(` | 一致 |
| 3e | JobTerminalWriter:86 = JOB_DONE | — | `86: taskSseService.publish(job.getTaskId(), "JOB_DONE", doneEvent);`（在 `apply` 内，事务后 82-85 之后） | 一致 |
| 3f | JobService:268 = JOB_DONE | — | `268: taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(`<br>`269: "jobId", jobId, "status", "CANCELLED"));`（在 `cancel(Long, String)`，250 起） | 一致 |
| 4a | yml `:191-198` app.task.sse（write-timeout 15s、emitter-timeout 5m、无 heartbeat 键） | 键在 191-198 | `191: task:`、`192: sse:`，**193-201 全是注释**；真正的键在 **`202: write-timeout: 15s` / `203: emitter-timeout: 5m`**。整个 `app.task.sse` 块 = **191-203**。**无 `heartbeat` 键确认成立**（yml 中 `heartbeat` 仅 208/219 注释 + `220: heartbeat-interval: 30s`，均属 `app.job`） | **漂移**（引用区间只覆盖注释头，键值在区间外 +2~4 行；"无 heartbeat 键"结论为真） |
| 4b | `:219-220` job.watchdog（10s/3/30m） | 219-220 | `220: heartbeat-interval: 30s`；`221: watchdog:`；`222: scan-period: 10s`；`223: dead-multiplier: 3`；**`224: max-execution-time: 30m`**（max-execution-time 不属 watchdog 块，是 `app.job` 同级键） | **漂移**（watchdog 块实为 221-223；max-execution-time 在 224） |
| 4c | `job.heartbeat-interval: 30s` 实际行号 | 待查 | **`220: heartbeat-interval: 30s`** | 一致（值/键名对，行号=220） |
| 5a | types/sse.ts `:337` TaskEventType | 四值联合 | `337: export type TaskEventType = 'HEARTBEAT' \| 'TASK_CHANGED' \| 'JOB_PROGRESS' \| 'JOB_DONE'` | 一致 |
| 5b | `:340-347` TaskChangedPayload 五字段 | 5 字段 | `340: export interface TaskChangedPayload {` `341: taskId: number` `342: version: number` `343: currentStep: string` `344: status: string` `345-346:` 注释 + `summary: string` `347: }` | 一致 |
| 5c | `:350-353` JOB_DONE 仅 `{jobId,status}` | — | `350: export interface JobDonePayload {` `351: jobId: number` `352: status: string` `353: }` | 一致 |

# Verbatim（逐字代码，标行号）

## TaskSseService.java（`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`，共 232 行）

```java
 54	public class TaskSseService {
 55
 56	    private final ObjectMapper objectMapper;
 57	    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
 58
 59	    /** 单帧写出的超时预算（包②；{@code app.task.sse.write-timeout}）。 */
 60	    private final SseWriteBudget writeBudget;
 61
 62	    /** 订阅者生命周期超时（必须非 0；{@code app.task.sse.emitter-timeout}）。 */
 63	    private final long emitterTimeoutMillis;
 64
 65	    public TaskSseService(ObjectMapper objectMapper, AppProperties appProperties) {
 66	        this.objectMapper = objectMapper;
 67	        AppProperties.Task.Sse sse = appProperties.getTask().getSse();
 68	        this.writeBudget = SseWriteBudget.on(SseWriteBudget.sharedElasticWriterPool(),
 69	                sse.getWriteTimeout().toMillis());
 70	        this.emitterTimeoutMillis = sse.getEmitterTimeout().toMillis();
 71	    }
 72
 73	    public SseEmitter subscribe(Long taskId) {
 74	        SseEmitter emitter = new SseEmitter(this.emitterTimeoutMillis);
 75	        // 同一 taskId 的挂/摘必须原子：computeIfPresent 的"空表即摘条目"与 computeIfAbsent 的挂载
 76	        // 若各走各的，会出现"条目被摘掉的瞬间新订阅者挂在已摘除的 list 上"（订阅者永久失联）。
 77	        emitters.compute(taskId, (key, list) -> {
 78	            List<SseEmitter> target = list != null ? list : Collections.synchronizedList(new ArrayList<>());
 79	            target.add(emitter);
 80	            return target;
 81	        });
 82
 83	        emitter.onCompletion(() -> remove(taskId, emitter));
 84	        emitter.onTimeout(() -> remove(taskId, emitter));
 85	        emitter.onError(e -> remove(taskId, emitter));
 86
 87	        // Send initial heartbeat
 88	        sendEvent(taskId, emitter, "HEARTBEAT", Map.of());
 89	        return emitter;
 90	    }
 91
 92	    @EventListener
 93	    public void onTaskChanged(TaskChangedEvent event) {
 94	        publish(event.getTask().getId(), "TASK_CHANGED", Map.of(
 95	                "taskId", event.getTask().getId(),
 96	                "version", event.getTask().getVersion(),
 97	                "currentStep", event.getTask().getCurrentStep(),
 98	                "status", event.getTask().getStatus(),
 99	                "summary", event.getSummary()
100	        ));
101	    }
102
```

publish()（110-150，含 dead 摘除）：

```java
110	    public void publish(Long taskId, String type, Object data) {
111	        List<SseEmitter> list = emitters.get(taskId);
112	        if (list == null) {
113	            return;
114	        }
115	        final String json;
116	        try {
117	            json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
118	        } catch (Exception e) {
119	            log.warn("SSE 帧序列化失败 taskId={} type={}（本帧未推送）: {}", taskId, type, e.getMessage());
120	            return;
121	        }
122
123	        // 锁内只快照：此后所有网络写都在锁外，单条慢连接不再把同任务的其它推送挡在锁上。
124	        List<SseEmitter> snapshot;
125	        synchronized (list) {
126	            snapshot = new ArrayList<>(list);
127	        }
128	        if (snapshot.isEmpty()) {
129	            return;
130	        }
131
132	        List<SseEmitter> dead = null;
133	        for (SseEmitter emitter : snapshot) {
134	            if (!sendFrame(emitter, json, "taskId=" + taskId + " type=" + type)) {
135	                if (dead == null) {
136	                    dead = new ArrayList<>();
137	                }
138	                dead.add(emitter);
139	            }
140	        }
141	        if (dead != null) {
142	            // 与 subscribe/remove 同键原子：摘元素的同时清掉空表条目（修复前只删元素不删 key，
143	            // 每个 taskId 永久留一个空 list）。
144	            List<SseEmitter> deadList = dead;
145	            emitters.computeIfPresent(taskId, (key, current) -> {
146	                current.removeAll(deadList);
147	                return current.isEmpty() ? null : current;
148	            });
149	        }
150	    }
```

sendEvent helper（178-187，签名 `void`、包内可见）：

```java
178	    void sendEvent(Long taskId, SseEmitter emitter, String type, Object data) {
179	        try {
180	            String json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
181	            if (!sendFrame(emitter, json, "订阅首帧 HEARTBEAT taskId=" + taskId)) {
182	                remove(taskId, emitter);
183	            }
184	        } catch (Exception e) {
185	            log.debug("Failed to send SSE: {}", e.getMessage());
186	        }
187	    }
```

sendFrame（206-222）与 remove（224-231）：

```java
206	    private boolean sendFrame(SseEmitter emitter, String json, String context) {
207	        SseWriteBudget.Result result = this.writeBudget.write(() -> emitter.send(SseEmitter.event().data(json)));
208	        return switch (result.outcome()) {
209	            case SENT -> true;
210	            case FAILED -> {
211	                log.debug("Failed to send SSE（{}）: {}", context,
212	                        result.failure() == null ? "写出失败" : result.failure().getMessage());
213	                yield false;
214	            }
215	            case TIMED_OUT -> {
216	                log.warn("任务 SSE 写超时（>{}ms）摘除该订阅者（{}；前端 EventSource 重连 + 快照轮询自愈）",
217	                        this.writeBudget.timeoutMillis(), context);
218	                yield false;
219	            }
220	            case INTERRUPTED -> true;
221	        };
222	    }
223
224	    private void remove(Long taskId, SseEmitter emitter) {
225	        // 原子收尾：元素摘掉后若表为空则连条目一起摘（修复前只删元素不删 key ⇒ 每个 taskId 永久留一个
226	        // 空 list）。与 subscribe 的 compute 同键互斥，故不会摘掉"刚挂上新订阅者"的表。
227	        emitters.computeIfPresent(taskId, (key, list) -> {
228	            list.remove(emitter);
229	            return list.isEmpty() ? null : list;
230	        });
231	    }
232	}
```

类头 javadoc 关于"超时不 complete"的定调（46-50，原文）：

```java
46	 * 超时后<b>不</b>在调用线程 {@code emitter.complete()}：{@code send}/{@code complete} 同为
47	 * {@code synchronized}，被卡住的写仍持有 emitter 监视器，就地 complete 会把调用线程再挂住。
48	 * 该 emitter 已移出推送表，由它自己的生命周期（客户端断开 / 120s 写超时先把那次写打断 /
49	 * 5m 容器超时 → onError/onTimeout）收尾 —— 回调里的 {@link #remove} 幂等。
```

## application.yml（`backend/src/main/resources/application.yml`，共 232 行）

`app.task.sse` 块（191-203）+ `app.job` 块（204-224）：

```yaml
191	  task:
192	    sse:
193	      # 包②：任务级 SSE 通道（GET /api/tasks/{id}/events）的写侧预算，与 app.ai.sse 同口径。
194	      #   write-timeout：单帧写出预算（15s，与 AI 通道同一缺省来源 SseWriteBudget#DEFAULT_TIMEOUT_MILLIS）；
195	      #     **必须 > 0**（启动期 fail-fast 校验，见 AppProperties）：0/负值会被 SseWriteBudget
196	      #     静默回落成缺省 15s（配置面与运行面说两套话），故启动期直接失败。
197	      #   emitter-timeout：订阅者生命周期超时，**必须 > 0**（启动期 fail-fast 校验，见 AppProperties）。
198	      #     原为 SseEmitter(0L) = 该 emitter 没有生命周期超时 —— 但这不等于"连接没有释放手段"：
199	      #     上面那条 120s 容器写超时仍在，只是它随连接配置漂移、通常比生命周期超时更早到。
200	      #     5m 与 AI 通道的 emitter 生命周期同量级。
201	      #     前端 EventSource 会按 retry 自动重连，job-watch 的快照轮询兜底。
202	      write-timeout: 15s
203	      emitter-timeout: 5m
204	  job:
205	    demo-batch-delay-ms: 300
206	    batch-size: 100
207	    # ── 作业看门狗（R-96 批 B 新建，用户裁决 Q1~Q6，2026-10-11）────────────────
208	    # heartbeat-interval：作业心跳间隔（分片边界续期，生产口径不含 demo 装饰节拍）。
209	    #   依据 = 业界依据 §12「心跳 30–60s」下沿（K8s 缺省 periodSeconds 10s ×
210	    #   failureThreshold 3 ≈ 30s 同量级；裁决 Q4 取 30s ⇒ 判死窗 90s）。
211	    # watchdog.scan-period：看门狗扫描周期 10s（业界依据 §12 的「1 分钟扫表」对本项目
212	    #   极小表规模偏保守）；判死最坏发现延迟 = 判死窗 + 一个扫描周期 ≈ 100s，
213	    #   E2E Wait-Job 窗口 120s = 90s + 10s + 20s 余量由此派生（总账 S-10）。
214	    # watchdog.dead-multiplier：判死倍数 3 —— K8s failureThreshold 缺省 3 /
215	    #   TIBCO「约 3.5×心跳」（业界依据 §12；裁决 Q4）。
216	    # max-execution-time：作业绝对上限 30m —— Hangfire InvisibilityTimeout 官方缺省
217	    #   30min、§12 推荐 30–60min 下沿（裁决 Q3；demo-batch-delay-ms 装饰节拍不计入）。
218	    # 启动期 fail-fast（AppProperties#verifyJobWatchdogKeys）：四项必须 > 0 且
219	    #   heartbeat-interval × dead-multiplier < max-execution-time。
220	    heartbeat-interval: 30s
221	    watchdog:
222	      scan-period: 10s
223	      dead-multiplier: 3
224	    max-execution-time: 30m
```

相关上游键（供"120s 容器写超时"引用）：`6:    connection-timeout: 120000`。

## frontend/src/api/sse.ts（共 593 行）

常量（22-43）：

```ts
 22	export const DEFAULT_IDLE_TIMEOUT_MS = 90_000
 23
 24	/** 断流后自动重挂的最大次数。 */
 25	export const DEFAULT_MAX_RECONNECTS = 2
 26
 27	/** 首次重连等待（后续指数退避）。 */
 28	export const DEFAULT_RECONNECT_DELAY_MS = 1_000
...
 43	export const DEFAULT_CONNECT_TIMEOUT_MS = 15_000
```

chat 通道 = fetch POST（非 EventSource，341-346）：

```ts
341	      response = await fetch(SSE_PATHS.chat, {
342	        method: 'POST',
343	        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
344	        body: JSON.stringify(request),
345	        signal: controller.signal
346	      })
```

`new EventSource` 构造点（549-558）：

```ts
549	    const open = (): void => {
550	      // T6：优先用"本连接已收到的最大序号"（增量重连），退回调用方给的 lastSeq
551	      const since = state.lastSeq ?? options.lastSeq
552	      source = new EventSource(SSE_PATHS.events(runId, since ?? undefined))
553	      // R-03：看门狗必须在 onopen 之前武装（构造是同步的，首个事件只会在此后的任务里派发，
554	      // 故此处与构造之间没有可丢失事件的窗口）
555	      startConnect()
556	      source.onopen = () => {
557	        handlers.onOpen?.()
558	        startIdle()
559	      }
```

收尾/摘下（464-475）、帧间看门狗（481-494）、建连看门狗（506-521）：

```ts
464	    const settle = (terminal: boolean, reason?: string): void => {
465	      if (settled) {
466	        return
467	      }
468	      settled = true
469	      clearIdle()
470	      clearConnect()
471	      source?.close()
472	      source = null
473	      options.signal?.removeEventListener('abort', onAbort)
474	      resolve(finish(state, handlers, terminal, reason))
475	    }
...
481	    const startIdle = (): void => {
482	      clearIdle()
483	      // 连接已被证明活着（onopen / 收到帧），建连看门狗让位
484	      clearConnect()
485	      idleTimer = setTimeout(() => {
486	        if (state.terminal) {
487	          settle(true)
488	          return
489	        }
490	        source?.close()
491	        source = null
492	        scheduleReconnect('帧间静默超时，判定断流')
493	      }, idleTimeoutMs)
494	    }
...
506	    const startConnect = (): void => {
507	      clearConnect()
508	      connectTimer = setTimeout(() => {
509	        connectTimer = null
510	        if (settled) {
511	          return
512	        }
513	        if (state.terminal) {
514	          settle(true)
515	          return
516	        }
517	        source?.close()
518	        source = null
519	        scheduleReconnect('重挂连接建立超时（未收到 onopen）')
520	      }, DEFAULT_CONNECT_TIMEOUT_MS)
521	    }
```

退避链 `scheduleReconnect`（523-547）+ onerror（571-583）：

```ts
523	    const scheduleReconnect = (reason: string): void => {
524	      if (settled) {
525	        return
526	      }
527	      // 退避期间不保留任何看门狗：重试由下面的 delay 计时器负责，open() 会重新武装建连看门狗
528	      clearIdle()
529	      clearConnect()
530	      if (options.signal?.aborted) {
531	        settle(state.terminal, '连接已取消')
532	        return
533	      }
534	      if (attempts >= maxReconnects) {
535	        settle(state.terminal, `${reason}（重挂已尝试 ${attempts} 次）`)
536	        return
537	      }
538	      attempts += 1
539	      state.reconnects = attempts
540	      const delay = baseDelay * 2 ** (attempts - 1)
541	      handlers.onReconnect?.(attempts, delay)
542	      setTimeout(() => {
543	        if (!settled && !options.signal?.aborted) {
544	          open()
545	        }
546	      }, delay)
547	    }
```

```ts
571	      source.onerror = () => {
572	        if (settled) {
573	          return
574	        }
575	        if (state.terminal) {
576	          // 已终态轮次：后端回放完即 complete()，浏览器把正常关流也报成 error
577	          settle(true)
578	          return
579	        }
580	        source?.close()
581	        source = null
582	        scheduleReconnect('重挂连接中断')
583	      }
```

`open()` 的初始调用（586-591）与 tryReattach（391-408）：

```ts
586	    if (options.signal?.aborted) {
587	      settle(false, '连接已取消')
588	      return
589	    }
590	    options.signal?.addEventListener('abort', onAbort, { once: true })
591	    open()
592	  })
593	}
```

```ts
391	async function tryReattach(
392	  state: StreamState,
393	  handlers: SseFrameHandlers,
394	  options: SseStreamOptions
395	): Promise<{ terminal: boolean; reason?: string }> {
396	  const maxReconnects = options.maxReconnects ?? DEFAULT_MAX_RECONNECTS
397	  if (!state.runId) {
398	    return { terminal: false, reason: '未收到 start 帧（无 runId 可重挂），判定断流' }
399	  }
400	  if (options.signal?.aborted) {
401	    return { terminal: false, reason: '连接已取消' }
402	  }
403	  if (maxReconnects <= 0) {
404	    return { terminal: false, reason: '未收到终帧（已禁用自动重挂）' }
405	  }
406	  const info = await reattach(state.runId, handlers, options, state)
407	  return info.terminal ? { terminal: true } : { terminal: false, reason: info.reason ?? '重挂未能收到终帧' }
408	}
```

## frontend/src/types/sse.ts 330-353（逐字）

```ts
330	/**
331	 * 任务级帧类型（TaskSseService：大写 type + 包一层 data）。
332	 *
333	 * `JOB_PROGRESS` 由各作业执行器在分片边界发布
334	 * （ExportJobRunner/ImportJobRunner/PrecheckJobRunner/PublishJobRunner#publishProgress），
335	 * 载荷是**行级** processed/total + defCode + 百分比，是向导"SSE 进度"的数据来源。
336	 */
337	export type TaskEventType = 'HEARTBEAT' | 'TASK_CHANGED' | 'JOB_PROGRESS' | 'JOB_DONE'
338
339	/** TASK_CHANGED 的 data（TaskSseService#onTaskChanged）。 */
340	export interface TaskChangedPayload {
341	  taskId: number
342	  version: number
343	  currentStep: string
344	  status: string
345	  /** 动作摘要（如"跳转到步骤: EXPORT"）—— 前端操作流水的来源 */
346	  summary: string
347	}
348
349	/** JOB_DONE 的 data（JobService#cancel 等发布点）。 */
350	export interface JobDonePayload {
351	  jobId: number
352	  status: string
353	}
```

## 前端任务通道（评估"EventSource 连接数"时配套）

`frontend/src/api/tasks.ts:105-115`（无任何看门狗，无 retry 控制）：

```ts
105	export function openTaskEvents(taskId: number, onFrame: (frame: TaskEventFrame) => void): EventSource {
106	  const source = new EventSource(`/api/tasks/${taskId}/events`)
107	  source.onmessage = (event: MessageEvent<string>) => {
108	    try {
109	      onFrame(JSON.parse(event.data) as TaskEventFrame)
110	    } catch {
111	      // 非 JSON 帧（心跳注释等）忽略
112	    }
113	  }
114	  return source
115	}
```

`frontend/src/utils/job-watch.ts:57, 76-84, 106-119`：1 个 EventSource + 3s 轮询 + `dispose()`：

```ts
 57	  const source = tasksApi.openTaskEvents(options.taskId, (frame: TaskEventFrame) => {
...
 76	  source.onopen = () => {
 77	    emitChannel('realtime')
 78	  }
 79	  source.onerror = () => {
 80	    // EventSource 自身会按 retry 间隔重连；这里只把状态标为降级，业务继续靠轮询推进
 81	    if (source.readyState !== EventSource.OPEN) {
 82	      emitChannel('polling')
 83	    }
 84	  }
...
106	  const timer = setInterval(() => {
107	    if (!disposed) {
108	      void syncSnapshot()
109	    }
110	  }, pollIntervalMs)
111
112	  function dispose(): void {
113	    if (disposed) {
114	      return
115	    }
116	    disposed = true
117	    clearInterval(timer)
118	    source.close()
119	  }
```

# Semantic answers（a~b）

## a. TaskSseService 的 emitter 注册/移除时机；是否存在半开态

**注册**：`subscribe()` 先挂表（73-81，`emitters.compute` 原子挂入 `taskId → synchronizedList`），再注册三个回调（83-85），最后发首帧（88）。`SseEmitter` 构造即带生命周期超时（74，`this.emitterTimeoutMillis` ← `app.task.sse.emitter-timeout` = 5m，yml:203 / 默认值 `AppProperties.java:240 = Duration.ofMinutes(5)`）。

**移除出口共 5 条**（全部汇入幂等的 `remove`，224-231；条目清空即删 key）：

| 出口 | 行号 | 触发条件 |
|---|---|---|
| `onCompletion` | 83 | 容器完成响应（正常结束/客户端断开被容器感知） |
| `onTimeout` | 84 | **emitter-timeout（5m）到点** ⇒ 这就是 `emitter-timeout` 的对应处理（构造参数 + 本回调），**没有**任何自建定时摘除 |
| `onError` | 85 | 异步请求出错 |
| `publish` → `sendFrame` 返回 false | 134-140、141-149 | 写出 **FAILED** 或 **TIMED_OUT(>15s)** 时批量摘除；**`INTERRUPTED` 保留订阅者**（220） |
| `sendEvent` 首帧失败 | 181-183 | 订阅首帧 HEARTBEAT 写出失败 |

**半开态结论：不存在"永久半开"，但存在"有界静默窗口 + 摘除≠断连"两个尖锐点。**

1. 服务端**只有订阅时的一帧 HEARTBEAT**（88）；文件内无 `schedule/Scheduled/Timer`、`heartbeat` 仅此一个发布点。之后除非该 taskId 上有 TASK_CHANGED/JOB_PROGRESS/JOB_DONE 事件，否则**服务端一帧不发**。
2. 因此移除是"被动/条件式"的：要么等一次 `publish` 去探活（134），要么等 5m 生命周期超时（84）。一个"连接活着、无事件、客户端不读也不断"的订阅者最多在推送表里驻留 **≈5m**，然后被容器关连接 → `onTimeout` → 摘除 → 前端 EventSource 自动重连 → 新订阅 + 新 HEARTBEAT。**不存在永不摘除的半开**，且删除是幂等的。
3. **摘除 ≠ 断连（值得红队盯）**：TIMED_OUT/FAILED 时 `publish` 只把 emitter 移出推送表，**故意不调用 `complete()`**（类注释 46-50；`sendFrame` 206-222 内无 complete）。`SseWriteBudget` 的 TIMED_OUT 分支同样不碰 emitter，只 `future.cancel(true)`（尽力而为，`SseWriteBudget.java:203-209`；同类注释 59-62 明说"线程中断对 socket 写无效""`send`/`complete` 同为 synchronized，就地 complete 会把调用线程再挂住"）。于是：写出线程仍卡在那次 socket 写上并持有 emitter 监视器，**HTTP 响应保持打开**，客户端看到的是"连接正常、无帧"。真正的收尾只能等：客户端断开 / 容器 120s 写超时（yml:6 `connection-timeout: 120000`，由 `NioEndpoint#setSocketOptions` 派生）/ 写出自己返回。也就是说，应用层"摘除"后仍可能有一段最长约 2 分钟的"服务端已停发、连接却看似活着"的窗口——这正是任务通道**没有周期心跳**时最容易误判的形态。
4. 前端侧没有配平手段：任务通道 `openTaskEvents`（tasks.ts:105-115）无 idle 看门狗，`job-watch.ts` 只有 `onerror` 降级 + 3s 轮询兜底（79-84、106-110）。对比 AI 通道有 90s 帧间看门狗（sse.ts:22）。所以"服务端静默"在任务通道不会被前端识破，业务连续性完全靠轮询（这是既有设计，不是缺陷，但语义上必须说清）。
5. 一处**可达性诚实声明**值得注意：`sendEvent` 的首帧摘除（181-183）在生产路径上通常不可达——容器 `initialize` 之前 `send` 只入 Spring 的 `earlySendAttempts` 缓冲（javadoc 163-168），故生产首帧一般是 SENT；该收口只覆盖"写出真的失败/并发收尾"。

## b. api/sse.ts 的 EventSource 连接数与看门狗/退避

**AI 通道开几个 EventSource：**
- **对话本身不开 EventSource**：`streamChat` 走 `fetch` POST + `ReadableStream` 自解帧（sse.ts:341-346、237-319）。
- **只有 reattach 通道用 EventSource**，且**单次 `reattach()` 调用内任意时刻 ≤1 条**：`new EventSource` 仅在 `open()`（552）出现，而 `open()` 只有两个调用点——初始（591）与退避计时器（542-546）；每次迁移都先 `source?.close(); source = null`（settle 471-472、帧间超时 490-491、建连超时 517-518、onerror 580-581）。退避期间两个看门狗都清（528-529），`settle` 后 `settled=true` 使 542-546 的守卫不再重开 ⇒ **同一调用内不会双连接**。
- **跨调用去重由 store 负责**：3 个入口 `streamChat→tryReattach`（375/381）、`stores/ai.ts: reattachActive`（1046 起，调 `aiApi.reattachRun` 1075）、`stores/ai.ts: resumeSnapshotStream`（830 起，848 调用）；两处 store 入口都做 `previous?.abort()`（846 / 1073）+ `isCurrentRound()` 身份判定，注释明写"防双连接""否则两条 EventSource 同时往不同宿主消息写帧（双渲染）"（1070-1072）。abort 事件同步派发 ⇒ 旧连接在同一任务内 `settle` → `close()`。
- **任务通道**另有每被跟踪作业 1 条：`utils/job-watch.ts:57` → `tasksApi.openTaskEvents`（tasks.ts:106），调用点 `ExportWizardView.vue:729` / `ImportWizardView.vue:844`，靠返回的 `dispose` 收尾（job-watch.ts:112-122）。

**看门狗与退避（关键函数名 + 行号）：**
- 建连看门狗 `startConnect`（506-521），预算 `DEFAULT_CONNECT_TIMEOUT_MS = 15_000`（43）；覆盖"构造函数 → `onopen`"这段原生 EventSource 无计时的窗口（javadoc 496-505）。
- 帧间看门狗 `startIdle`（481-494），预算 `DEFAULT_IDLE_TIMEOUT_MS = 90_000`（22）；`onopen` 与每帧都刷新（556-558、560-561）。
- 二者**互斥**（"任一时刻只武装一个"，注释 452-455）：`startIdle` 内 `clearConnect()`，`scheduleReconnect` 内两个都清。
- 退避 `scheduleReconnect`（523-547）：`attempts >= maxReconnects`（`DEFAULT_MAX_RECONNECTS = 2`，25）即 `settle(false, …)`；否则 `delay = baseDelay * 2 ** (attempts - 1)`，`baseDelay = DEFAULT_RECONNECT_DELAY_MS = 1_000`（28）。三轮建连总预算 15+1+15+2+15 = 48s（javadoc 38-39）。
- 终态收尾在 `onmessage`（567-569）与 `onerror` 的终态分支（575-579），均走 `settle`。

**同域连接挤压评估**：SSE 侧最多 1 条 AI reattach + 每被跟踪作业 1 条任务通道（典型页面 1~2 条；`watchJob` 每次调用都是一条新连接，若调用方漏 `dispose` 才会累积）。其余 API 走 axios（非 EventSource）。因此浏览器 HTTP/1.1 同域 6 连接上限在本仓 SSE 用法下**不可达**，除非重挂风暴或漏 dispose 叠加——后两者是唯一需要盯的放大路径。

# Notable findings

1. **锚点 1 的路径是失实的（最需要修的一条）**：仓内不存在 `com/example/configmgr/job/service/TaskSseService.java`；真实路径是 `com/example/configmgr/task/service/TaskSseService.java`。文件内所有行号锚点（73/87-88/92-101）逐字命中，所以这是**纯路径漂移**，但下游若按原路径取证据会 404。
2. **锚点 4 的 yml 行号区间偏了 2~4 行**：`app.task.sse` 的键在 **202/203**（191-198 只是注释头）；`job.watchdog` 块实为 **221-223**，`max-execution-time: 30m` 在 **224**（不在 watchdog 块内），`heartbeat-interval: 30s` 在 **220**。结论面（write-timeout 15s / emitter-timeout 5m / scan-period 10s / dead-multiplier 3 / max-execution-time 30m / **`app.task.sse` 下无 heartbeat 键**）全部为真。
3. **锚点 2 的 `:159-160` 差 1 行**：`scan()` 起于 160，`findByStatusIn(IN_FLIGHT)` 在 **161**。
4. **"任务通道有周期心跳"是常见误读**：`heartbeat` 在 `TaskSseService` 出现 6 次，但**只有 88 行是发布点**，其余是注释/javadoc；`app.task.sse` 下**无 heartbeat 键**。任务通道的"心跳"仅指订阅首帧。
5. **后端唯一的周期调度器是 JobWatchdog**：`@EnableScheduling`/`@Scheduled` 在 `src/main/java` 下 **2 命中且都在 JobWatchdog.java:52 的 javadoc 自述里**（即真实注解 0 处），与类注释"仓内无 @EnableScheduling/@Scheduled"一致；单线程 daemon `job-watchdog`，`scheduleWithFixedDelay(scanSafely, 10s, 10s)`，`@PreDestroy` 里 `shutdownNow()`。
6. **JOB_DONE 有两个发布点**（不止一个）：`JobTerminalWriter.java:86`（终态写入器，事务提交后）与 `JobService.java:268`（`cancel()` 内，立即置 CANCELLED）。两处均 `publish(taskId, "JOB_DONE", …)`，载荷形状与 `types/sse.ts:350-353` 的 `{jobId,status}` 一致（JobService 侧显式只给这两个字段）。
7. **`publish` 摘除死订阅者后不断连**（a.3）：本轮"半开"最值得实测的点——摘除后 socket 由容器 120s 写超时或 5m 生命周期关闭，前端在此窗口内看到的是"连接 alive 但无帧"，且任务通道前端无 idle 看门狗可识别。建议红队以"不读也不断开"的客户端 + 一次 publish 实测服务端摘除时刻与实际连接关闭时刻之差。
8. `sendFrame` 对 `INTERRUPTED` 返回 true（220）即**保留订阅者**，这是刻意口径（作业线程被取消 ≠ 连接坏），别误判为漏摘。
