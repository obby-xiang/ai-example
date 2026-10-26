> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-148` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：22558 B / 17986 字符 / 455 行；行指纹覆盖率（仓内 + 落盘语料）7.6%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# SseChatEmitter / RunStore / RunRegistry 并发盘点（工作区实测行号）

文件：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`（843 行）、`RunStore.java`（705 行）、`RunRegistry.java`（89 行）。行号均以当前工作区实读为准。

---

## 1. emitLock 内操作清单

`emitLock` 定义：`SseChatEmitter.java:136` `private final Object emitLock = new Object();`

一共 **3 个** `synchronized (this.emitLock)` 块，另有 1 个**不同**的锁 `seqLock`（`SseChatEmitter.java:108`）。

### 1.1 块 A — `replayAndAttach` L248–L265

```java
246	public int replayAndAttach(SseEmitter emitter, Long lastSeq, boolean terminal) {
247		Subscription sub = new Subscription(emitter, terminal);
248		synchronized (this.emitLock) {
249			int staged = stageReplay(sub, lastSeq);
250			if (terminal) {
251				if (staged == 0) {
252					completeOne(sub);
253				}
254				else {
255					kickDrain(sub);
256				}
257			}
258			else {
259				attachSubscription(sub);
260				if (staged > 0) {
261					kickDrain(sub);
262				}
263			}
264			return staged;
265		}
266	}
```

| 行 | 锁内操作 | 类别 |
|---|---|---|
| L249 → L310 | `this.store.events(runId)` = Redis `LRANGE 0 -1` **全量读** | **IO 读（网络）** |
| L249 → L312–L328 | 遍历帧、type 过滤（L314）、`isOrphan`（L317→L333，纯内存）、L321 `sub.queue.add(objectMapper.writeValueAsString(frame))` | 序列化 + 入队（内存） |
| L249 → L325 | `log.error(...)` 序列化失败分支 | 日志 |
| L252 → L727–L740 | `completeOne(sub)`：L730 CAS `completed`、L733 `subscribers.remove(sub)`、L735 `sub.emitter.complete()` | **网络写出（响应提交）** |
| L255 / L261 / L261 链 | `kickDrain(sub)` → L647 CAS `draining` → L648 `submitDrain` → L657 `deliveryExecutor.execute(...)` | 线程池提交（可能阻塞：AbortPolicy/LinkedBlockingQueue 入队） |
| L255/L261 → L657 → L659–L662 | 池拒绝分支：L660 `sub.draining.set(false)`、L661 `evict(sub,...)` → L716 CAS、L719 `subscribers.remove`、L720 `queue.clear()`、L723 `completeOne` → L735 `emitter.complete()` | **网络写出**，且 `draining` 复位 |
| L259 → L228–L233 | `this.subscribers.add(sub)`；`emitter.onCompletion/onTimeout/onError` 三个回调注册 | 订阅表增 |

`stageReplay` 原文（L309–L330）——被持锁调用，自身不取锁：

```java
309	private int stageReplay(Subscription sub, Long lastSeq) {
310		List<Map<String, Object>> frames = this.store.events(this.runId);
311		int staged = 0;
312		for (Map<String, Object> frame : frames) {
313			String type = String.valueOf(frame.get("type"));
314			if (RunStore.DELTA_TYPE.equals(type) || RunStore.HEARTBEAT_TYPE.equals(type)) {
315				continue;
316			}
317			if (isOrphan(frame, lastSeq)) {
318				continue;
319			}
320			try {
321				sub.queue.add(this.objectMapper.writeValueAsString(frame));
322				staged++;
323			}
324			catch (Exception ex) {
325				log.error("回放帧序列化失败 runId={} type={}: {}", this.runId, type, ex.getMessage());
326				break;
327			}
328		}
329		return staged;
330	}
```

**判定（事实陈述）：块 A 锁内存在 Redis 读（L310）与 `emitter.complete()` 网络写出（L252 / L255 拒绝分支路径）。**

### 1.2 块 B — `replayTo` L289–L298

```java
287	public int replayTo(SseEmitter emitter, Long lastSeq) {
288		Subscription sub = new Subscription(emitter, true);
289		synchronized (this.emitLock) {
290			int staged = stageReplay(sub, lastSeq);
291			if (staged == 0) {
292				completeOne(sub);
293			}
294			else {
295				kickDrain(sub);
296			}
297			return staged;
298		}
299	}
```

与块 A 同构：锁内 L290 Redis 全量读、L292/L295 走 `completeOne` 或线程池提交（含 L661 `evict`→`emitter.complete()` 路径）。

### 1.3 块 C — `emit` L589–L626

```java
584	private void emit(Map<String, Object> frame) {
585		frame.putIfAbsent("runId", this.runId);
...
589		synchronized (this.emitLock) {
590			if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
...
596				frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
597				this.store.touchActivity(this.runId);
598			}
599			else {
600				frame.put("seq", nextSeq());
601				this.store.appendEvent(this.runId, frame);
602			}
603			if (this.subscribers.isEmpty() || this.terminating.get()) {
...
606				return;
607			}
608			final String payload;
609			try {
610				payload = this.objectMapper.writeValueAsString(frame);
611			}
612			catch (Exception ex) {
...
614				log.error("SSE 帧序列化失败 runId={} type={}: {}", this.runId, frame.get("type"), ex.getMessage());
615				return;
616			}
617			for (Subscription sub : this.subscribers) {
618				if (!enqueueLive(sub, payload)) {
...
621					evict(sub, "实时队列溢出（容量 " + this.queueCapacity + "）");
622					continue;
623				}
624				kickDrain(sub);
625			}
626		}
627	}
```

| 行 | 锁内操作 | 类别 |
|---|---|---|
| L590 | `String.valueOf(frame.get("type"))` 判断帧类型 | 读（内存） |
| L596 | `heartbeatSeq.incrementAndGet()`（`AtomicLong` L120） | 原子自增 |
| L597 → RunStore L613–L620 | `store.touchActivity(runId)` = Redis `SET ai:beat:<runId>` **写 + TTL** | **IO 写（网络）** |
| L600 → nextSeq L562–L582 | L563 `seq.get()`；L564 `synchronized (this.seqLock)`（**嵌套锁**）；L566 `store.lastEventSeq`（`LRANGE -1 -1`）+ `store.lastIssuedSeq`（`GET ai:seq:`）；L567 `seq.set`；L571 `seq.incrementAndGet()`；L575 `store.recordIssuedSeq`（`SET ai:seq:<runId>` 带 TTL） | **IO 读 + IO 写；持 emitLock 再取 seqLock** |
| L601 → RunStore L543–L562 | `store.appendEvent(runId, frame)`：非 heartbeat/delta 时 `rightPush` + `trim(-3000,-1)` + `expire`；heartbeat/delta 时内部改调 `touchActivity`（L544–L550） | **IO 写（网络）** |
| L603 | 读 `subscribers.isEmpty()`（`CopyOnWriteArrayList` L103）与 `this.terminating.get()` | 读点 |
| L610 | `objectMapper.writeValueAsString(frame)` | 序列化（CPU） |
| L614 | 序列化失败 `log.error` + return | 日志 |
| L617→L637–L643 | `subscribers` 迭代 + `enqueueLive`：L638 `sub.queue.size() >= queueCapacity` 判定、L641 `sub.queue.add(payload)` | 入队（内存） |
| L621 → L715–L724 | `evict(sub, ...)`：L716 CAS、L719 `subscribers.remove`、L720 `queue.clear()`、L721 `EVICTED_TOTAL.incrementAndGet()`、L722 `log.warn`、L723 `completeOne` → L735 `sub.emitter.complete()` | **网络写出** |
| L624 → L646–L650 / L655–L663 | `kickDrain`：L647 CAS `draining`；成功则 `submitDrain`：L657 `deliveryExecutor.execute(() -> drain(sub))`；L659–L662 池拒绝分支 `draining.set(false)` + `evict`（→`emitter.complete()`） | 线程池提交 / 网络写出 |

**判定（事实陈述）：块 C 锁内包含 Redis 写两次以上（`touchActivity` / `appendEvent` / `recordIssuedSeq`）、Redis 读两次（`lastEventSeq` / `lastIssuedSeq`，仅首帧初始化路径）、序列化、入队、线程池提交，以及 `evict` 路径上的 `emitter.complete()`（L621 / L659 拒绝分支）。**
**`sub.emitter.send(payload)` 只出现在 L681（`drain` 内），不在任何 emitLock 块内。**

### 1.4 锁关系与调用线程（事实）

- `emitLock` 的获取者：`replayAndAttach` L292（`AiController.java:292`，HTTP 线程）、`replayTo`、`emit` L589（多线程：运行线程心跳、HTTP 线程 decision，见类注 L126–L129）。
- 嵌套次序：`emitLock`（L589）→ `seqLock`（L564，仅经 `nextSeq` L600 进入）。全类无「先 `seqLock` 再 `emitLock`」路径。
- `subscribers` 的非锁访问点：L230–L232（emitter 回调）、L697 `this.subscribers.contains(sub)`（drain finally，投递池线程，**不持 emitLock**）、L719 / L733。

---

## 2. 三标志 + 辅助标志读写矩阵

### 2.0 标志声明

| 标志 | 声明位置 | 类型 |
|---|---|---|
| `terminating`（轮级） | `SseChatEmitter.java:153` | `AtomicBoolean`，final 引用 |
| `Subscription.terminating`（订阅者级） | `SseChatEmitter.java:828` | `volatile boolean`（非 Atomic，无 CAS） |
| `Subscription.terminal` | `SseChatEmitter.java:834` | `final boolean`（构造 L836–L839 写入，不可变） |
| `Subscription.draining` | `SseChatEmitter.java:818` | `AtomicBoolean` |
| `Subscription.evicted` | `SseChatEmitter.java:821` | `AtomicBoolean` |
| `Subscription.completed` | `SseChatEmitter.java:831` | `AtomicBoolean` |

**注意区分两个同名概念**：`this.terminating`（轮级，L153）与 `sub.terminating`（订阅者级，L828）——后者是 `volatile` 普通写，**无 CAS，无复位**。

### 2.1 `draining`（`AtomicBoolean`）

| 行 | 方法 | 操作 | 是否在 emitLock 内 | 说明 |
|---|---|---|---|---|
| L818 | 字段声明 | `new AtomicBoolean(false)` | — | 初值 false |
| L647 | `kickDrain` | **CAS false→true** | **是**（仅经 L624 / L255 / L261 进入，三者都在锁内） | 抢到则 L648 提交 drain |
| L660 | `submitDrain` catch `RejectedExecutionException` | **set(false) 复位** | 视调用者：L648 路径锁内；L698/L798 路径**锁外** | 池拒绝后复位再 `evict` |
| L695 | `drain` finally 首句 | **set(false) 复位** | **否**（投递池线程，全程无锁） | R2 顺序第 ① 步 |
| L696 | `drain` finally（L695 之后） | **CAS false→true 重抢** | **否** | R2 顺序第 ② 步；条件 `!queue.isEmpty()` |
| L792 | `completeWhenDrained` | **CAS false→true** | **否**（`complete()` L772 / `RunRegistry.close` L85 调用线程不持锁） | 抢到后看队空（L793） |
| L794 | `completeWhenDrained` 队空分支 | **set(false) 复位** | **否** | 紧接 L795 `completeOne` |

- `draining` **没有任何 `.get()` 读点**；唯一的条件性读取就是 L696 / L647 / L792 的 CAS 本身。
- L696 的 CAS 与 L697 的三条件（`sub.terminal || sub.terminating || this.subscribers.contains(sub)`）不是原子组合：CAS 成功但 L697 为假时，**`draining` 保持 true 且不 `submitDrain`**（该分支无复位语句）。
- L695 `set(false)` 与 L696 `compareAndSet` 之间无原子性保护（同一线程顺序执行，但其它线程可在此窗口介入）。

### 2.2 `terminating`（轮级 `AtomicBoolean` L153）

| 行 | 方法 | 操作 | 是否在 emitLock 内 |
|---|---|---|---|
| L771 | `complete()` | **写 set(true)** | **否** |
| L603 | `emit` | 读 `.get()`（停扇出判据：`subscribers.isEmpty() || terminating.get()`） | **是** |
| L697 | `drain` finally | 读（补投条件之一） | **否** |
| L702 | `drain` finally | 读（收尾条件之一） | **否** |

无复位、无 CAS（只有 `set` / `get`）。

### 2.3 `Subscription.terminating`（`volatile boolean` L828）

| 行 | 方法 | 操作 | 是否在 emitLock 内 |
|---|---|---|---|
| L788 | `completeWhenDrained` | **写 `sub.terminating = true`**（普通 volatile 写） | **否** |
| L697 | `drain` finally | 读 | **否** |
| L702 | `drain` finally | 读 | **否** |

`emit`（L589 块）**不读** `sub.terminating`；只有轮级 `this.terminating` 在 L603 被读。

### 2.4 `terminal`（`final boolean` L834）

| 行 | 方法 | 操作 |
|---|---|---|
| L247 | `replayAndAttach` 构造 | 写（实参 terminal） |
| L288 | `replayTo` 构造 | 写（固定 true） |
| L838 | 构造器 | 写 |
| L697 | `drain` finally | 读 |
| L702 | `drain` finally | 读 |

`emit` 不读 `terminal`；`drain` 的两个读点均在锁外。

### 2.5 `evicted`（`AtomicBoolean` L821）

| 行 | 方法 | 操作 | 是否在 emitLock 内 |
|---|---|---|---|
| L716 | `evict` | **CAS false→true（唯一写点，去重）** | 视调用者：L621（`emit`，**锁内**）、L661（`submitDrain` 拒绝分支，锁内/锁外皆可）、L689（`drain` 写失败，**锁外**） |
| L789 | `completeWhenDrained` | 读 `.get()`（早退） | **否** |

无复位点。`evict` 原文 L715–L724：

```java
715	private void evict(Subscription sub, String reason) {
716		if (!sub.evicted.compareAndSet(false, true)) {
717			return;
718		}
719		this.subscribers.remove(sub);
720		sub.queue.clear();
721		EVICTED_TOTAL.incrementAndGet();
722		log.warn("摘除订阅者 runId={} reason={}", this.runId, reason);
723		completeOne(sub);
724	}
```

### 2.6 `completed`（`AtomicBoolean` L831）

| 行 | 方法 | 操作 | 是否在 emitLock 内 |
|---|---|---|---|
| L730 | `completeOne` | **CAS false→true（唯一写点，去重）** | 视调用者（见下） |
| L733 | `completeOne` | `subscribers.remove(sub)` | 同上 |
| L735 | `completeOne` | `sub.emitter.complete()` | 同上 |

`completeOne` 的全部调用点：

| 调用点 | 行 | 上下文 | 是否在 emitLock 内 |
|---|---|---|---|
| `replayAndAttach` | L252 | staged==0，terminal 回放 | **是** |
| `replayTo` | L292 | staged==0 | **是** |
| `drain` finally | L703 | 终态且队空 | **否** |
| `evict` | L723 | 摘除收尾 | 视 evict 调用者（L621 锁内 / L661 视路径 / L689 锁外） |
| `completeWhenDrained` | L795 | 队空直接收尾 | **否** |

无复位点。原文 L727–L740：

```java
727	private void completeOne(Subscription sub) {
728		// 幂等去重（S1-1）：轮终态排空、drain 队空、摘除三条路径可能并发命中同一个订阅者，
729		// 重复 complete 会二次触发前端 onCompletion/onError。
730		if (!sub.completed.compareAndSet(false, true)) {
731			return;
732		}
733		this.subscribers.remove(sub);
734		try {
735			sub.emitter.complete();
736		}
737		catch (Exception ex) {
738			log.debug("SSE complete 失败 runId={}: {}", this.runId, ex.getMessage());
739		}
740	}
```

### 2.7 辅助标志/原子量

| 变量 | 声明 | 写点 | 读点 | 锁内？ |
|---|---|---|---|---|
| `textSegmentOpen` | L142 `AtomicBoolean` | L390 CAS false→true、L399 CAS true→false | 同两处 CAS | 无锁 |
| `seq` | L106 `AtomicLong(-1)` | L567 `set(anchor)`、L571 `incrementAndGet()` | L563、L565 | 锁内（经 `nextSeq` L600，持 `seqLock`） |
| `sequencesLock` = `seqLock` | L108 `Object` | — | — | `synchronized` 于 L564，`emitLock` 内层 |
| `heartbeatSeq` | L120 `AtomicLong(0)` | L596 `incrementAndGet()` | 无 | 锁内（L596） |
| `sharedDeliveryPool` | L85 `static volatile` | L198 赋值 | L204、L210 | 无锁；装配在 `static synchronized` L189 |
| `EVICTED_TOTAL` | L88 `static final AtomicLong` | L721 `incrementAndGet()` | L215 `get()` | L721 视 evict 调用者 |
| `subscribers` | L103 `CopyOnWriteArrayList` | add L229、remove L230/L231/L232（回调）、L719、L733 | L345 `size()`、L603 `isEmpty()`（锁内）、L617 迭代（锁内）、L697 `contains`（**锁外**） | 混合 |

---

## 3. drain 循环完整状态机（代码原文）

### 3.1 `kickDrain` / `submitDrain` / `drain`（L645–L706）

```java
645	/** 入队后 CAS 抢占 drain 权：抢不到说明在跑的 drain 会带走新帧（单订阅者 FIFO）。 */
646	private void kickDrain(Subscription sub) {
647		if (sub.draining.compareAndSet(false, true)) {
648			submitDrain(sub);
649		}
650	}
651
652	/**
653	 * 提交 drain 任务（R3：池拒绝 = 摘除触发本次提交的订阅者）。
654	 */
655	private void submitDrain(Subscription sub) {
656		try {
657			this.deliveryExecutor.execute(() -> drain(sub));
658		}
659		catch (RejectedExecutionException ex) {
660			sub.draining.set(false);
661			evict(sub, "投递池拒绝（池队列满）");
662		}
663	}
664
665	/**
666	 * 把队列里的帧按 FIFO 写给一个订阅者（T3-1：一条慢连接至多占一个工作线程）。
667	 *
668	 * <p>
669	 * <b>R2 收尾顺序（固定，不可调换）</b>：先清 CAS 独占标志，再复查队列非空则重新 CAS
670	 * 抢回并补投 —— 若先复查后清标志，"判空 → 他线程入队后 CAS 失败（以为有在跑 drain）
671	 * → 清标志退出"会让那一帧永远无人投递（drain 窗口丢失）。
672	 */
673	private void drain(Subscription sub) {
674		try {
675			while (true) {
676				String payload = sub.queue.poll();
677				if (payload == null) {
678					break;
679				}
680				try {
681					sub.emitter.send(payload);
682				}
683				catch (Exception ex) {
684					// Q8② 第二层防护：对端断开的写失败（IOException / AsyncRequestNotUsableException /
685					// IllegalStateException: 已完成的 emitter）一律吞没在这里 —— 它是"这一个订阅者没了"，
686					// 不是本轮失败。若让它冒泡到 HTTP 栈，全局异常处理器会对一个内容类型已固定为
687					// text/event-stream 的响应再写一次 JSON，抛 HttpMessageNotWritableException（日志噪音）。
688					log.debug("SSE 写出失败（客户端断开？）runId={}: {}", this.runId, ex.getMessage());
689					evict(sub, "写出失败（客户端断开）");
690					return;
691				}
692			}
693		}
694		finally {
695			sub.draining.set(false);
696			if (!sub.queue.isEmpty() && sub.draining.compareAndSet(false, true)
697					&& (sub.terminal || sub.terminating || this.subscribers.contains(sub))) {
698				submitDrain(sub);
699			}
700			// S1-1：终态（回放批 sub.terminal / 轮终态 sub.terminating）只在队列<b>真正空了</b>
701			// 才收尾 —— drain 可能因补投而多跑一轮，早一步 complete 就丢掉队列里还没写出的尾帧。
702			if ((sub.terminal || sub.terminating) && sub.queue.isEmpty()) {
703				completeOne(sub);
704			}
705		}
706	}
```

### 3.2 `drain` 状态机标注

| 环节 | 行 | 内容 |
|---|---|---|
| 循环入口 | L675 `while (true)` | 无限循环，唯一正常出口是队空 `break`（L677–L679）与写失败 `return`（L690） |
| 取帧 | L676 `sub.queue.poll()` | 单消费者摘除（`LinkedBlockingQueue` L815） |
| **队空分支** | L677–L679 `payload == null → break` | 跳出循环进 finally |
| 网络写出 | L681 `sub.emitter.send(payload)` | **唯一的网络写出点，锁外** |
| 写失败分支 | L683–L691 `log.debug`（L688）→ `evict(sub,...)`（L689）→ `return`（L690） | `evict` 内部 L720 `queue.clear()`，故 finally 时队列已空 |
| finally ① 复位 | L695 `sub.draining.set(false)` | **R2 顺序第 ① 步：先清标志** |
| finally ② 重新抢权 | L696 `!queue.isEmpty() && sub.draining.compareAndSet(false, true)` | **R2 顺序第 ② 步：先复查队列非空再 CAS 抢回** |
| finally ② 附加守卫 | L697 `&& (sub.terminal \|\| sub.terminating \|\| this.subscribers.contains(sub))` | 三条件为「或」；`subscribers.contains` **在锁外调用** |
| finally ② 补投 | L698 `submitDrain(sub)` | 经 L657 再次 `execute(() -> drain(sub))` |
| **终态回放分支** | L702–L704 `if ((sub.terminal \|\| sub.terminating) && sub.queue.isEmpty()) completeOne(sub)` | 队空才收尾；两个判据都在锁外读 |
| 收尾去重 | L703 → L730 CAS `completed` | — |

### 3.3 `complete()` / `completeWhenDrained`（L770–L801）

```java
770	public void complete() {
771		this.terminating.set(true);
772		for (Subscription sub : this.subscribers) {
773			completeWhenDrained(sub);
774		}
775	}
```

```java
785	private void completeWhenDrained(Subscription sub) {
786		// 终态标志先落到订阅者上下文上：在跑的 drain 只在"自己的 finally 里看到该标志且队列已空"
787		// 时才收尾，标志晚置一步这一轮 drain 就会空转退出而永不再收尾。
788		sub.terminating = true;
789		if (sub.evicted.get()) {
790			return;
791		}
792		if (sub.draining.compareAndSet(false, true)) {
793			if (sub.queue.isEmpty()) {
794				sub.draining.set(false);
795				completeOne(sub);
796			}
797			else {
798				submitDrain(sub);
799			}
800		}
801	}
```

| 环节 | 行 | 内容 |
|---|---|---|
| 轮级置位 | L771 `this.terminating.set(true)` | 先停新帧扇出的判据（`emit` L603） |
| 遍历订阅者 | L772–L774 | `CopyOnWriteArrayList` 快照迭代，**不持 emitLock** |
| 订阅者级置位 | L788 `sub.terminating = true` | volatile 普通写，先于 CAS |
| 已摘除早退 | L789–L791 `if (sub.evicted.get()) return;` | 读 `evicted`（无锁） |
| 抢权失败 | L792 CAS 失败 ⇒ 直接返回（唯一出口无动作） | 交给在跑 drain 的 L702 分支 |
| 抢权成功 + 队空 | L793–L795 | `draining.set(false)` 后 `completeOne`（**先复位再 complete**） |
| 抢权成功 + 队非空 | L797–L799 | `submitDrain`（`draining` 保持 true） |

### 3.4 调用入口（drain 相关链路）

| 调用点 | 行 | 线程/锁 |
|---|---|---|
| `kickDrain` ← `emit` | L624 | emitLock 内，多来源线程 |
| `kickDrain` ← `replayAndAttach` | L255、L261 | emitLock 内（HTTP 线程，`AiController.java:292`） |
| `kickDrain` ← `replayTo` | L295 | emitLock 内 |
| `drain` ← `submitDrain` L657 | — | 投递池线程（`sse-delivery-N`，L194）或 `Runnable::run` 同步直执（L156、`RunRegistry.java:65`） |
| `submitDrain` ← `drain` finally | L698 | 投递池线程，无锁 |
| `submitDrain` ← `completeWhenDrained` | L798 | `complete()` 调用线程（无锁） |
| `complete()` | L770 | `RunRegistry.close` L85、`ResumeService.java:360`、`AiController`（无锁） |

---

## 4. RunStore 侧的锁内落点（供竞态对照）

| 方法 | 行 | Redis 操作 | 被 emitLock 内调用 |
|---|---|---|---|
| `appendEvent` | L543–L562 | heartbeat/delta → L549 `touchActivity`；否则 L552 `putIfAbsent("atMs")` + L554 `rightPush` + L555 `trim(-3000,-1)` + L556 `expire` | 是（`SseChatEmitter` L601） |
| `touchActivity` | L613–L620 | L615 `SET ai:beat:<runId>` 带 TTL | 是（L597 与 L601 的 delta/heartbeat 分支） |
| `recordIssuedSeq` | L594–L602 | L596 `SET ai:seq:<runId>` 带 TTL；失败仅 WARN（L599） | 是（经 `nextSeq` L575） |
| `events` | L683–L699 | L684 `LRANGE 0 -1`（全量） | 是（经 `stageReplay` L310） |
| `lastEventSeq` | L667–L681 | L669 `LRANGE -1 -1` | 是（经 `nextSeq` L566，仅首帧初始化） |
| `lastIssuedSeq` | L574–L583 | L576 `GET ai:seq:` | 是（同上） |
| `expire`/`trim` TTL 来源 | L701–L703 `ttl()` = `properties.getSessionTtl()` | — | — |

`RunStore` 本身**无任何 synchronized / 锁字段**（全文仅 `@Component` 与 Redis 调用）；其线程安全完全依赖 Redis 单命令原子性与 `SseChatEmitter` 的 `emitLock`。

`RunRegistry`：`emitters` = `ConcurrentHashMap` L32；`of` L72 `computeIfAbsent`，`find` L78 `get`，`close` L83 `remove` + L85 `emitter.complete()`。`close` 与 `of` 均不持 `emitLock`。
