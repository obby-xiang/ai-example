package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮对话的 SSE 帧写出器（S4.2 §3 时序的终端契约）。
 *
 * <p>
 * 与第一棒相比的两点变化（M3 挂起态外置 + ADR-5 reattach 的必然结果）：
 * <ol>
 * <li><b>多订阅者扇出</b>：一帧发给本轮全部订阅者 —— 首轮客户端断开重连后，
 * {@code GET /api/ai/events/{runId}} 可以挂到同一个写出器上继续收流
 * （ADR-5 补记：409 携 runId + reattach 重挂进行中轮的流）；</li>
 * <li><b>每帧同时外置</b>：帧原文进 {@code ai:events:<runId>}（{@link RunStore}），
 * 于是"进程在挂起期死亡"不再连 SSE 时序一起丢，重挂时先回放外置帧再续接实时帧。</li>
 * </ol>
 *
 * <p>
 * 帧类型：{@code start}（首包）→ 若干 {@code delta}/{@code tool_*}/
 * {@code retry}/{@code confirm_*}/{@code frontend_tool_*}/{@code heartbeat}/{@code suspended} →
 * 恰好一个 {@code done}（正常完成或取消：{@code cancelled=true}/{@code false}）或 {@code error} 终帧。帧体是 JSON 对象且带 {@code type} 字段
 * （无名 SSE 事件 + 帧内 type），与基座 SSE 形态一致，前端不必改用 addEventListener。
 *
 * <p>
 * <b>序号口径（S5c-1 + T3-2）</b>：业务帧带单调 {@code seq}（自 1 起、跨进程续号）。
 * 心跳帧<b>不占</b>业务序号，只在独立空间里带 {@code heartbeatSeq}。T3-2 起每个业务帧
 * 发放序号时同步 {@code SET ai:seq:<runId>}（发放即登记，TTL 与归档同寿）—— delta 帧
 * 跳过归档后"归档末帧"不再是可靠锚点，续号候选 = max(归档末帧, {@code ai:seq}) + 1。
 * <b>出帧串行（S5c-3）</b>：取号、落归档、入队在同一 {@code emitLock} 内完成，
 * 多线程出帧的到达顺序与 seq 顺序一致。
 *
 * <h2>T3-1 慢订阅者隔离（R1 裁决 + 施工注记 R2/R3）</h2>
 * {@code emitLock} 内只保留：取号、落归档、{@code touchActivity}（S3-9：必须留锁内同步，
 * 否则僵尸判定输入随投递延迟漂移）、<b>帧入各订阅者队列</b>。网络写出全部走
 * <b>共享有界投递池</b>（工作线程数 T3-5 定值 8，{@code app.ai.sse.delivery-pool-size} 可配，
 * T3-5 压测定值）：
 * <ul>
 * <li>每订阅者一条队列（实时段容量 T3-5 定值 256，{@code app.ai.sse.delivery-queue-capacity}
 * 可配；生产者在 {@code emitLock} 内串行入队、单消费者摘除，故容量判定精确）
 * + 一个 CAS drain 独占标志：入队后 CAS 抢占 drain 权，抢不到说明在跑的 drain 会带走新帧
 * —— 单订阅者 FIFO 且一条慢连接至多占一个工作线程，慢订阅者的 TCP 背压不再阻塞整轮
 * （含心跳与同轮其他订阅者）；</li>
 * <li><b>drain 收尾顺序固定（R2）</b>：先清 CAS 独占标志、再复查队列非空则重新 CAS 抢回
 * —— 倒序会丢"判空 → 他线程入队 CAS 失败 → 清标志退出"的 drain 窗口；</li>
 * <li><b>池拒绝语义（R3）</b>：池任务提交被拒（池队列满）时摘除触发本次提交的订阅者
 * （显式 {@code emitter.complete()}，前端 onerror/onclose 后带 lastSeq 重挂补帧）；</li>
 * <li><b>溢出摘除 = 主动断连（S2-1）</b>：实时队列满即摘除并 complete，不静默丢帧
 * （前端重挂走差量补发，seq 无洞）；</li>
 * <li><b>实时闸按实时段计数（S1 修复批第 2 轮，红队击破后修）</b>：容量闸判
 * {@code Subscription#liveQueued}（该订阅者<b>队列中的实时帧条数</b>），<b>不再</b>判队列绝对长度
 * —— 回放批与实时帧共用<b>同一条</b>队列（单队列 FIFO 混合序，seq 连续性语义依赖），
 * 若按绝对长度判定，慢重挂者回放 3000 帧入队后，排空期间任一实时帧（挂起期每 2s 心跳）
 * 就会把队列里的回放 backlog 误判成实时溢出而摘除一个健康订阅者；重挂后剩余回放 ≥ 容量
 * 再被摘除 ⇒ 确定性饥饿（与 R1 裁决"回放批豁免有界容量"直接相悖，红队探针已实测复现）。
 * 回放帧入队经 {@link QueuedFrame} 标记为 {@code live=false}，drain 弹出实时帧时递减计数；</li>
 * <li><b>生命周期回调统一清理（S3-1 修复批第 2 轮）</b>：{@code onCompletion/onTimeout/onError}
 * 与 {@code evict/completeOne} 同走 {@link #closeSubscription}（清队列 + 实时段计数归零 +
 * 复位 drain 独占标志 + 标记 closed）—— 否则连接超时/断开后队列里滞留的回放帧无人释放；</li>
 * <li><b>终态收尾（S1-1 修复批）</b>：{@link #complete()}（轮终态）先置终态标志<b>停止收新帧</b>，
 * 再让各订阅者把<b>已入队</b>的帧排空后才收尾 —— "队空才 complete" 直接复用 drain 的收尾分支
 * （与终态回放路径同一口径），异步 drain 因此不会在队列还留着尾帧（{@code done}/{@code error}/
 * {@code suspended}）时被 {@code emitter.complete()} 抢断；收尾不阻塞调用线程（生产调用点是
 * 轮终态线程的 {@code finally}，同步等排空会拖住轮次收尾）；</li>
 * <li><b>回放批豁免（R1 裁决）</b>：{@code replayAndAttach} 锁内收集回放帧（{@code LRANGE}
 * 只读，不做 IO 写）入队不受实时容量 256 约束，上界 = 归档窗口 {@code EVENT_WINDOW}
 * （3000，{@code RunStore#events} 天然裁剪）——"队列容量 &lt; 归档窗口"不变量只约束实时段。</li>
 * </ul>
 *
 * <p>
 * 写出失败（客户端断开）只记日志、摘除该订阅者，不打断运行线程；终帧与
 * {@code emitter.complete()} 由调用方（{@code AiController} / {@code ResumeService}）保证。
 */
@Slf4j
public class SseChatEmitter {

	/**
	 * T3-1：进程级共享有界投递池（{@link #configureSharedDeliveryPool} 用 AiProperties 装配一次）。
	 */
	private static volatile ThreadPoolExecutor sharedDeliveryPool;

	/** 投递池指标：累计摘除的订阅者数（溢出 / 写出失败 / 池拒绝共用，供 /api/ai/health）。 */
	private static final AtomicLong EVICTED_TOTAL = new AtomicLong();

	private final String runId;

	private final RunStore store;

	private final ObjectMapper objectMapper;

	/** T3-1：本写出器的投递执行器。生产 = 共享有界投递池；三参构造（测试/非 Spring）= 同步直执。 */
	private final Executor deliveryExecutor;

	/** T3-1：每订阅者实时队列容量（回放批豁免，见类注）。 */
	private final int queueCapacity;

	/** T3-1：订阅者 = emitter + 有界队列 + CAS drain 独占标志。 */
	private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();

	/** T6：本轮<b>已发放</b>的最大业务号（首帧前惰性从 max(归档末帧, ai:seq) 初始化）。 */
	private final AtomicLong seq = new AtomicLong(-1);

	private final Object seqLock = new Object();

	/**
	 * S5c-1：心跳的<b>独立</b>序号空间（自 1 起）。
	 *
	 * <p>
	 * 心跳不取业务 {@code seq}：心跳不落归档（T7），若它占用业务序号，则"归档末帧"这个
	 * 跨进程续号锚点就会落后于实际发放过的号 —— 重启后的新写出器会重用心跳用过的号，
	 * 而该号已在前端的 {@code lastSeq} 覆盖范围内，于是新帧在差量重挂里被判为孤儿而
	 * 永久丢弃（DC-14 §3.6 实测形态）。独立计数保留"挂起期心跳到第几次"的可观测性，
	 * 又与业务时间线彻底解耦。
	 */
	private final AtomicLong heartbeatSeq = new AtomicLong(0);

	/**
	 * 出帧互斥（S5c-3 / S5c-2）：取号 → 落归档 → 入队必须是一次不可分割的动作。
	 *
	 * <p>
	 * 为什么必须有：<b>出帧线程不唯一</b> —— 运行线程在挂起等待里每 2s 发心跳
	 * （{@code ConfirmGate#await}），HTTP 线程在 {@code POST /api/ai/confirm|frontend-tool-result}
	 * 里发决策/回灌帧。不加锁时先取号的线程可能后写出，订阅者看到的到达顺序可以逆序
	 * （实测已复现，见证据文档 S5c-3）。
	 *
	 * <p>
	 * 同一把锁也让 {@link #replayAndAttach} 的"回放收集 + 回放帧入队 + 挂订阅"成为
	 * 原子段落（S5c-2），消掉 reattach 的交接丢帧窗口。T3-1 起锁内不再做网络写出，
	 * 但回放帧的<b>入队</b>仍在锁内先于挂订阅完成，交接原子性不变。
	 */
	private final Object emitLock = new Object();

	/** T5：本轮 assistant 正文消息的身份（结果铸为消息 / 消息级渲染去重的判据）。 */
	private final String assistantMessageId;

	/** T5：正文消息边界是否已开（start/content/end 三段式的 start 只发一次）。 */
	private final AtomicBoolean textSegmentOpen = new AtomicBoolean(false);

	/**
	 * 轮终态标志（S1-1 修复批）：置位后不再向订阅者收新帧，已入队帧由各自 drain 排空后收尾。
	 *
	 * <p>
	 * 为什么不直接清订阅表：{@code complete()} 被调用时队列里可能还压着尾帧（慢订阅者的
	 * {@code done}/{@code error}/{@code suspended}），立刻 {@code emitter.complete()} 会
	 * 确定性地把它们丢掉（真实 {@code SseEmitter} 完成后拒绝再写）。标志与订阅者是"或"关系：
	 * 任一方说"该收尾了"，drain 的队空分支就完成该订阅者。
	 */
	private final AtomicBoolean terminating = new AtomicBoolean(false);

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper) {
		this(runId, store, objectMapper, Runnable::run, AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);
	}

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper, Executor deliveryExecutor,
			int queueCapacity) {
		this.runId = runId;
		this.store = store;
		this.objectMapper = objectMapper;
		this.deliveryExecutor = deliveryExecutor;
		this.queueCapacity = queueCapacity;
		this.assistantMessageId = "assistant-" + runId;
	}

	/**
	 * 本写出器每订阅者的实时队列容量（单一事实源 = {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY}，
	 * 三参构造与注册表两条装配路径都取它）。
	 *
	 * <p>
	 * 包内可见：只给"队列容量 &lt; 归档窗口"不变量断言用（S3-1 修复批的测试锚点）——
	 * 值改坏即红，且不允许出现第二处字面量。
	 */
	int queueCapacity() {
		return this.queueCapacity;
	}

	/**
	 * 装配进程级共享投递池（{@link RunRegistry} 用 {@code AiProperties} 调一次；幂等）。
	 *
	 * <p>
	 * 池形参：{@code corePoolSize = maxPoolSize = 池容量（工作线程数，R3 语义）}，
	 * 有界任务队列 + AbortPolicy —— 提交被拒时由 {@link #submitDrain} 摘除
	 * 触发本次提交的订阅者。
	 */
	public static synchronized void configureSharedDeliveryPool(int poolSize, int poolQueueCapacity) {
		if (sharedDeliveryPool == null) {
			AtomicLong threadNo = new AtomicLong();
			ThreadPoolExecutor pool = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(poolQueueCapacity), runnable -> {
						Thread thread = new Thread(runnable, "sse-delivery-" + threadNo.incrementAndGet());
						thread.setDaemon(true);
						return thread;
					}, new ThreadPoolExecutor.AbortPolicy());
			sharedDeliveryPool = pool;
		}
	}

	/** 共享投递池（未装配时回落同步直执，测试与非 Spring 场景的默认）。 */
	public static Executor sharedDeliveryExecutorOrDirect() {
		ThreadPoolExecutor pool = sharedDeliveryPool;
		return pool != null ? pool : Runnable::run;
	}

	/** 投递池指标（供 {@code GET /api/ai/health}）：poolSize / active / queueDepth / evicted。 */
	public static Map<String, Object> deliveryPoolMetrics() {
		ThreadPoolExecutor pool = sharedDeliveryPool;
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("poolSize", pool == null ? 0 : pool.getCorePoolSize());
		metrics.put("active", pool == null ? 0 : pool.getActiveCount());
		metrics.put("queueDepth", pool == null ? 0 : pool.getQueue().size());
		metrics.put("evicted", EVICTED_TOTAL.get());
		return metrics;
	}

	public String runId() {
		return this.runId;
	}

	/** 挂一个订阅者（首轮响应 / reattach 重挂共用）。 */
	public void attach(SseEmitter emitter) {
		attachSubscription(new Subscription(emitter, false));
	}

	/**
	 * 挂订阅并登记<b>生命周期回调</b>（S3-1 修复批第 2 轮：三回调统一走清理路径）。
	 *
	 * <p>
	 * 修复前三个回调只做 {@code subscribers.remove(sub)}：慢连接超时/断开（{@code onTimeout} /
	 * {@code onError}）时队列里还压着的帧（生产上是重挂回放的千级状态帧）无人释放 = 内存滞留，
	 * 且 drain 独占标志留在原地。现在与 {@code evict/completeOne} 共用
	 * {@link #closeSubscription}，清理口径只有一处。
	 */
	private void attachSubscription(Subscription sub) {
		this.subscribers.add(sub);
		sub.emitter.onCompletion(() -> closeSubscription(sub, "生命周期 onCompletion"));
		sub.emitter.onTimeout(() -> closeSubscription(sub, "生命周期 onTimeout"));
		sub.emitter.onError(ex -> closeSubscription(sub, "生命周期 onError"));
	}

	/**
	 * 关闭一个订阅者的<b>投递上下文</b>（生命周期回调 / 摘除 / 收尾的统一清理路径）。
	 *
	 * <p>
	 * 顺序有意如此：先摘表（此后 {@link #emit} 的扇出不再触及它），再清队列与计数，
	 * 最后复位 drain 独占标志 —— 这样"在跑的 drain"最多多空转一轮（队列已空，{@code poll} 即退出），
	 * 不会把清理结果改回去。
	 *
	 * <p>
	 * {@code closed} 是"安全取消挂起 drain"的开关：置位后 {@link #emit} 的扇出直接跳过本订阅者，
	 * 于是 CAS 抢到的那个 drain 到点只会看到空队列并退出，不会再把帧写向一个已终态的 emitter
	 * （{@code SseEmitter} 上完成后再写会抛 {@code IllegalStateException}，只留日志噪音）。
	 * 计数归零用 {@code set(0)} 而非递减：清队列丢掉了队列快照，残留计数比轻微少计更危险
	 * （漏摘除 = 队列无界增长）；在跑的 drain 若随后再弹出一个已消失的实时帧，其递减由
	 * {@link #liveDequeued} 兜到 0 为止，不会为负。
	 *
	 * <p>
	 * 本方法<b>不</b>碰 {@code Subscription#completed}：终结 emitter 只有 {@link #completeOne}
	 * 一处（{@code completed} 是它的去重标志），摘除路径 = 本方法 + {@code completeOne}。
	 */
	private void closeSubscription(Subscription sub, String reason) {
		this.subscribers.remove(sub);
		sub.closed = true;
		sub.queue.clear();
		sub.liveQueued.set(0);
		sub.draining.set(false);
		log.debug("订阅者投递上下文已关闭 runId={} reason={}", this.runId, reason);
	}

	/**
	 * <b>S5c-2 + T3-1</b>：重挂的原子交接 —— 回放帧收集（{@code LRANGE} 只读）、回放帧入队、
	 * 挂订阅在<b>同一把出帧锁</b>内完成；网络写出统一走异步队列（回放批豁免实时容量，R1 裁决）。
	 *
	 * <p>
	 * 为什么必须原子：{@code 回放 → attach} 两段之间若有实时帧写出，那一帧既不在回放快照里、
	 * 也还没被这个订阅者接住 = 丢帧窗口（且 SSE 丢帧不报错，只会静默少一段）。
	 *
	 * @param terminal true = 该轮已终态（回放完即收尾，不再订阅）
	 * @return 实际入队的回放帧数（异步写出前的入队口径）
	 */
	public int replayAndAttach(SseEmitter emitter, Long lastSeq, boolean terminal) {
		Subscription sub = new Subscription(emitter, terminal);
		synchronized (this.emitLock) {
			int staged = stageReplay(sub, lastSeq);
			if (terminal) {
				if (staged == 0) {
					completeOne(sub);
				}
				else {
					kickDrain(sub);
				}
			}
			else {
				attachSubscription(sub);
				if (staged > 0) {
					kickDrain(sub);
				}
			}
			return staged;
		}
	}

	/**
	 * 把外置状态帧回放给一个新订阅者（不入订阅表，回放发完即 complete）。
	 *
	 * <p>
	 * 只回放<b>状态类帧</b>（跳过 {@code delta}/{@code heartbeat}）：挂起中的轮次不产出正文，
	 * 回放与订阅之间没有 delta 竞态，前端也就不会把同一段文本渲染两遍；而
	 * {@code start} / {@code message_*} / {@code suspended} / {@code confirm_request} / {@code tool_*} /
	 * {@code confirm_decision} / {@code done} / {@code error} 全部保留 —— 重挂者据此重建
	 * "这一轮发生过什么"，包括已经结束的轮次的终帧。
	 *
	 * <p>
	 * <b>T6 增量补发 + 孤儿过滤</b>：给了 {@code lastSeq}（前端已知的最大帧序号）时，
	 * 只回放 {@code seq > lastSeq} 的帧 —— 孤儿（seq ≤ lastSeq，前端本地已有）不重发，
	 * 于是长轮次的 reattach 代价从"整轮全部帧"降到"差量"。没有 {@code seq} 的老帧
	 * （升级前写进归档的）无法判定，按"照发"处理（宁可重复，不可丢帧）。
	 *
	 * @param lastSeq 前端已知的最大 seq；null = 全量回放
	 * @return 实际入队的回放帧数（异步写出前的入队口径）
	 */
	public int replayTo(SseEmitter emitter, Long lastSeq) {
		Subscription sub = new Subscription(emitter, true);
		synchronized (this.emitLock) {
			int staged = stageReplay(sub, lastSeq);
			if (staged == 0) {
				completeOne(sub);
			}
			else {
				kickDrain(sub);
			}
			return staged;
		}
	}

	/**
	 * 锁内收集回放帧并入队（调用方持 {@link #emitLock}）。
	 *
	 * <p>
	 * <b>R1 裁决</b>：本方法入队<b>不受实时容量 256 约束</b>（回放批豁免）——慢重挂者的
	 * 初始批上界 = 归档窗口 {@code EVENT_WINDOW}（3000，{@code RunStore#events} 天然裁剪），
	 * "队列容量 &lt; 归档窗口"不变量只约束实时段。
	 */
	private int stageReplay(Subscription sub, Long lastSeq) {
		List<Map<String, Object>> frames = this.store.events(this.runId);
		int staged = 0;
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if (RunStore.DELTA_TYPE.equals(type) || RunStore.HEARTBEAT_TYPE.equals(type)) {
				continue;
			}
			if (isOrphan(frame, lastSeq)) {
				continue;
			}
			try {
				sub.queue.add(new QueuedFrame(this.objectMapper.writeValueAsString(frame), false));
				staged++;
			}
			catch (Exception ex) {
				log.error("回放帧序列化失败 runId={} type={}: {}", this.runId, type, ex.getMessage());
				break;
			}
		}
		return staged;
	}

	/** 孤儿判定：帧序号不大于前端已知的最大序号 ⇒ 该帧前端已有，不重发。 */
	private static boolean isOrphan(Map<String, Object> frame, Long lastSeq) {
		if (lastSeq == null) {
			return false;
		}
		Object seqValue = frame.get("seq");
		if (seqValue instanceof Number number) {
			return number.longValue() <= lastSeq;
		}
		return false;
	}

	public int subscriberCount() {
		return this.subscribers.size();
	}

	// ── 帧 ──────────────────────────────────────────────────────────────────

	/** 首包：runId 由服务端生成，前端据此做 reattach。 */
	public void start(String sessionId) {
		Map<String, Object> frame = frame("start");
		frame.put("runId", this.runId);
		frame.put("sessionId", sessionId);
		emit(frame);
	}

	public void delta(String text) {
		Map<String, Object> frame = frame("delta");
		frame.put("messageId", this.assistantMessageId);
		frame.put("text", text);
		emit(frame);
	}

	/**
	 * 消息边界（T5 / R8-2）：正文段（kind={@code text}）或思考段（kind={@code reasoning}）的
	 * start / end 标记，配合中间的 content 帧（{@code delta}）构成三段式生命周期。
	 *
	 * <p>
	 * 为什么需要它：一条 assistant 消息可能"先吐思考、再吐正文、中间夹工具调用"，
	 * 而前端此前只有 content 帧 —— 无法判断两段文本属于同一条消息还是两条
	 * （SP-01ab §7.3 的混合消息渲染痛点）。边界帧 + {@code messageId} 让"哪几帧属于同一条消息"
	 * 成为协议事实，而不是靠客户端猜。
	 *
	 * <p>
	 * 纯增量：老客户端不认识这两个帧类型会忽略它们，content 帧照旧可独立渲染。
	 */
	public void messageBoundary(String phase, String kind, Map<String, Object> extra) {
		Map<String, Object> frame = frame("message_" + phase);
		frame.put("messageId", this.assistantMessageId);
		frame.put("kind", kind);
		if (extra != null) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	/** 正文段 start（同一条消息只开一次；重复调用无副作用）。 */
	public boolean textStart() {
		if (!this.textSegmentOpen.compareAndSet(false, true)) {
			return false;
		}
		messageBoundary("start", "text", null);
		return true;
	}

	/** 正文段 end（带本段字符数，便于前端与 done 帧对账）；未开过段则不发（幂等）。 */
	public boolean textEnd(int chars) {
		if (!this.textSegmentOpen.compareAndSet(true, false)) {
			return false;
		}
		messageBoundary("end", "text", Map.of("chars", chars));
		return true;
	}

	/** 挂起公告：快照已外置（带 Redis 键名与写入者实例，供前端"可重挂"提示）。 */
	public void suspended(List<PendingToolCall> pendings, String stateKey, String pendingKey, String writtenBy) {
		Map<String, Object> frame = frame("suspended");
		frame.put("runId", this.runId);
		frame.put("toolCalls", pendings.stream().map(PendingToolCall::asMap).toList());
		frame.put("stateKey", stateKey);
		frame.put("pendingKey", pendingKey);
		frame.put("writtenBy", writtenBy);
		emit(frame);
	}

	/** 挂起期心跳：证明"挂起期间连接仍然活着"，也是前端展示倒计时的节拍。 */
	public void heartbeat(Map<String, Object> data) {
		Map<String, Object> frame = frame("heartbeat");
		frame.putAll(data);
		emit(frame);
	}

	public void toolStart(PendingToolCall pending) {
		Map<String, Object> frame = frame("tool_start");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("args", pending.getArguments());
		emit(frame);
	}

	public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result) {
		Map<String, Object> frame = frame("tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("ok", ok);
		frame.put("executed", pending.isExecuted());
		frame.put("executedBy", pending.getExecutedBy());
		frame.put("reused", reused);
		frame.put("status", pending.getStatus());
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		emit(frame);
	}

	public void confirmRequest(PendingToolCall pending, int timeoutSeconds) {
		Map<String, Object> frame = frame("confirm_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		frame.put("summary", pending.getArguments());
		frame.put("timeoutSeconds", timeoutSeconds);
		frame.put("expiresAt", expiresAtEpochMs(timeoutSeconds));
		frame.put("callback", "POST /api/ai/confirm {runId, toolCallId, approved, reason}");
		emit(frame);
	}

	public void confirmDecision(PendingToolCall pending, String decision, String reason, long waitedMs) {
		Map<String, Object> frame = frame("confirm_decision");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("decision", decision);
		frame.put("approved", PendingToolCall.APPROVED.equals(pending.getStatus()));
		frame.put("status", pending.getStatus());
		frame.put("reason", reason);
		frame.put("waitedMs", waitedMs);
		emit(frame);
	}

	public void frontendToolRequest(PendingToolCall pending, int timeoutSeconds) {
		Map<String, Object> frame = frame("frontend_tool_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		frame.put("timeoutSeconds", timeoutSeconds);
		// T4 同口径（纯增量）：前端工具挂起与确认门共用同一个等待上限，绝对时钟一并给出，
		// 前端不必用本地 "120s 常量" 推断到期时刻。
		frame.put("expiresAt", expiresAtEpochMs(timeoutSeconds));
		frame.put("callback", "POST /api/ai/frontend-tool-result {runId, toolCallId, result}");
		emit(frame);
	}

	public void frontendToolResult(PendingToolCall pending, boolean ok, String result) {
		Map<String, Object> frame = frame("frontend_tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("ok", ok);
		frame.put("executed", false);
		frame.put("status", pending.getStatus());
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		emit(frame);
	}

	/**
	 * 断流重试公告（韧性棒）：本轮第 {@code nextAttempt} 次尝试即将开始 ——
	 * 前端据此展示"上游连接中断，正在重试"，并知道本轮<b>没有</b>产出过内容
	 * （重试三条件保证：已产出即不重试）。
	 */
	public void retry(Map<String, Object> data) {
		Map<String, Object> frame = frame("retry");
		frame.putAll(data);
		emit(frame);
	}

	/**
	 * 终帧：{@code done}。
	 *
	 * @param extra 附加信息（韧性棒：{@code cancelled} / {@code attempts} /
	 * {@code upstreamEvents} / {@code terminalSignal}）；取消终态为 {@code cancelled=true}，
	 * 与"正常完成"共用同一个终帧类型（前端的终态处理不必分两套）。
	 */
	public void done(Map<String, Object> usage, String model, Map<String, Object> extra) {
		Map<String, Object> frame = frame("done");
		frame.put("runId", this.runId);
		if (usage != null && !usage.isEmpty()) {
			frame.put("usage", usage);
		}
		if (model != null) {
			frame.put("model", model);
		}
		if (extra != null) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	public void error(String code, String message) {
		Map<String, Object> frame = frame("error");
		frame.put("runId", this.runId);
		frame.put("code", code);
		frame.put("message", message);
		emit(frame);
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	private Map<String, Object> frame(String type) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		return frame;
	}

	/**
	 * 下一帧的单调序号（T6 + T3-2）。
	 *
	 * <p>
	 * 首帧前惰性续号：候选 = max(归档末帧, {@code ai:seq}) + 1（进程重启后由续跑新建的
	 * 写出器<b>不会从 0 重来</b>，否则归档/前端 lastSeq 会出现两段重叠的序号，差量补发漏帧）。
	 * 归档为空（全新轮次）则从 1 开始。
	 *
	 * <p>
	 * <b>T3-2：发放即登记</b> —— 每帧取号后立刻 {@code SET ai:seq:<runId>}（TTL=session-ttl）。
	 * delta 帧跳过归档后归档末帧可能落后，跨进程续号必须由 {@code ai:seq} 接管；
	 * SET 失败仅 WARN + 继续出帧（{@link RunStore#recordIssuedSeq} 内部口径，S2-4）。
	 *
	 * <p>
	 * S5c-1：这条推理成立的前提是<b>心跳不占业务号</b>（见 {@link #emit}）。
	 */
	private long nextSeq() {
		if (this.seq.get() < 0) {
			synchronized (this.seqLock) {
				if (this.seq.get() < 0) {
					long anchor = Math.max(this.store.lastEventSeq(this.runId), this.store.lastIssuedSeq(this.runId));
					this.seq.set(anchor);
				}
			}
		}
		long next = this.seq.incrementAndGet();
		// S2-4：SET 失败仅 WARN + 继续出帧（韧性优先）。RunStore 实现内部已做同类防护，
		// 这里再兜一层，保证"发放即登记"的任何实现异常都不会中断出帧。
		try {
			this.store.recordIssuedSeq(this.runId, next);
		}
		catch (Exception ex) {
			log.warn("登记已发放序号失败 runId={} seq={}（继续出帧，跨进程续号可能回退）：{}", this.runId, next,
					ex.getMessage());
		}
		return next;
	}

	private void emit(Map<String, Object> frame) {
		frame.putIfAbsent("runId", this.runId);
		// S5c-3：取号 → 落归档 → 入队在同一把锁内完成 —— 多线程（运行线程的心跳 / HTTP 线程的
		// 决策与回灌）并发出帧时，订阅者队列里的先后与 seq 的先后必然一致；网络写出在锁外
		// 由投递池异步完成（T3-1 慢订阅者隔离），单订阅者 FIFO 由 CAS drain 独占标志保证。
		synchronized (this.emitLock) {
			if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				// T7：心跳照常广播（下面的 subscribers），但不进归档窗口 —— 只留一个轻量活动戳，
				// 让"这轮最近有过活动"仍可跨进程判定（僵尸判据的输入）。归档侧的同类跳过见
				// RunStore#appendEvent（任何其他调用方直接塞心跳帧也不会占位）。
				// S5c-1：心跳<b>不取业务 seq</b>，只在独立序号空间里计数 —— 于是"归档末帧"
				// （跨进程续号锚点）恒等于已发放过的最大业务号，心跳永远不会把它顶到前面去。
				frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
				this.store.touchActivity(this.runId);
			}
			else {
				frame.put("seq", nextSeq());
				this.store.appendEvent(this.runId, frame);
			}
			if (this.subscribers.isEmpty() || this.terminating.get()) {
				// S1-1：轮终态已置 ⇒ 不再向订阅者收新帧（帧照旧取号落档，重挂回放仍可见）——
				// 否则终态之后的新帧会把各 drain 的"排空"无限推迟，收尾永远等不到队空。
				return;
			}
			final String payload;
			try {
				payload = this.objectMapper.writeValueAsString(frame);
			}
			catch (Exception ex) {
				// 序列化失败不再阻断其他订阅者（T3-1：锁内只做取号/落档/入队）
				log.error("SSE 帧序列化失败 runId={} type={}: {}", this.runId, frame.get("type"), ex.getMessage());
				return;
			}
			for (Subscription sub : this.subscribers) {
				if (sub.closed) {
					// S3-1：生命周期回调已清理该订阅者（COW 快照与摘除的窄窗）—— 不再入队、不再抢 drain
					continue;
				}
				if (!enqueueLive(sub, payload)) {
					// S2-1：溢出摘除 = 主动断连 —— 显式 complete 触发前端 onerror/onclose，
					// 前端带 lastSeq 重挂走差量补发（seq 无洞），胜过静默丢帧。
					evict(sub, "实时队列溢出（容量 " + this.queueCapacity + "）");
					continue;
				}
				kickDrain(sub);
			}
		}
	}

	/**
	 * 实时段入队（T3-1 不变量：队列容量 &lt; 归档窗口 3000）。
	 *
	 * <p>
	 * 队列本体无界、容量闸由本方法显式把守：所有生产者都在 {@code emitLock} 内串行入队、
	 * 消费者只摘除，判定因此是精确的。闸的判据是 {@code Subscription#liveQueued}
	 * （队列中<b>实时帧</b>的条数，S1 修复批第 2 轮）而<b>不是</b>队列绝对长度 —— 回放批
	 * 走 {@link #stageReplay} 的豁免口径（R1 裁决）且不计入该计数，两类入队互不挤占：
	 * 慢重挂者排空 3000 帧回放期间到来的实时帧，不会因队列里压着回放帧而被误判成实时溢出。
	 *
	 * <p>
	 * <b>递增必须先于入队</b>：反序时并发 drain 可在 {@code add} 与 {@code incrementAndGet}
	 * 之间弹出该帧并递减，随后递增落地 ⇒ 计数永久 +1，容量闸逐帧泄漏直至误摘健康订阅者。
	 * 反方向（递增后才入队）不会产生幽灵：{@code LinkedBlockingQueue} 无界，{@code add} 不会失败。
	 */
	private boolean enqueueLive(Subscription sub, String payload) {
		if (sub.liveQueued.get() >= this.queueCapacity) {
			return false;
		}
		sub.liveQueued.incrementAndGet();
		sub.queue.add(new QueuedFrame(payload, true));
		return true;
	}

	/**
	 * 实时段出队（drain 弹出实时帧）：计数减一。
	 *
	 * <p>
	 * 兜底不减到负：唯一的"计数已归零却又弹出实时帧"来源是并发清空（{@link #closeSubscription}
	 * 的 {@code set(0)} 落在本次 {@code poll} 与本次递减之间）——此时该订阅者已被关闭，
	 * 计数保持 0 即正确口径（为负会让容量闸永久失效，比少计更危险）。
	 */
	private static void liveDequeued(Subscription sub) {
		sub.liveQueued.updateAndGet(current -> current > 0 ? current - 1 : 0);
	}

	/** 入队后 CAS 抢占 drain 权：抢不到说明在跑的 drain 会带走新帧（单订阅者 FIFO）。 */
	private void kickDrain(Subscription sub) {
		if (sub.draining.compareAndSet(false, true)) {
			submitDrain(sub);
		}
	}

	/**
	 * 提交 drain 任务（R3：池拒绝 = 摘除触发本次提交的订阅者）。
	 */
	private void submitDrain(Subscription sub) {
		try {
			this.deliveryExecutor.execute(() -> drain(sub));
		}
		catch (RejectedExecutionException ex) {
			sub.draining.set(false);
			evict(sub, "投递池拒绝（池队列满）");
		}
	}

	/**
	 * 把队列里的帧按 FIFO 写给一个订阅者（T3-1：一条慢连接至多占一个工作线程）。
	 *
	 * <p>
	 * <b>R2 收尾顺序（固定，不可调换）</b>：先清 CAS 独占标志，再复查队列非空则重新 CAS
	 * 抢回并补投 —— 若先复查后清标志，"判空 → 他线程入队后 CAS 失败（以为有在跑 drain）
	 * → 清标志退出"会让那一帧永远无人投递（drain 窗口丢失）。
	 *
	 * <p>
	 * <b>修复批第 2 轮的两点加固</b>：① 复查里的 CAS 移到<b>整条条件链的最后</b>（前置条件
	 * 不满足就不再抢权），于是"抢到标志却因守卫判假而放弃提交"的残留窗口消失 —— 旧形态
	 * {@code CAS(...) && (terminal || terminating || subscribers.contains(sub))} 在订阅者刚被
	 * 摘除时会让 {@code draining} 永远停在 true；② 出队元素带实时/回放标记，弹出实时帧时
	 * 递减实时段计数（容量闸的判据，见 {@link #enqueueLive}）。
	 */
	private void drain(Subscription sub) {
		try {
			while (true) {
				QueuedFrame frame = sub.queue.poll();
				if (frame == null) {
					break;
				}
				if (frame.live) {
					liveDequeued(sub);
				}
				try {
					sub.emitter.send(frame.payload);
				}
				catch (Exception ex) {
					// Q8② 第二层防护：对端断开的写失败（IOException / AsyncRequestNotUsableException /
					// IllegalStateException: 已完成的 emitter）一律吞没在这里 —— 它是"这一个订阅者没了"，
					// 不是本轮失败。若让它冒泡到 HTTP 栈，全局异常处理器会对一个内容类型已固定为
					// text/event-stream 的响应再写一次 JSON，抛 HttpMessageNotWritableException（日志噪音）。
					log.debug("SSE 写出失败（客户端断开？）runId={}: {}", this.runId, ex.getMessage());
					evict(sub, "写出失败（客户端断开）");
					return;
				}
			}
		}
		finally {
			sub.draining.set(false);
			if (canTakeOverDrain(sub) && sub.draining.compareAndSet(false, true)) {
				submitDrain(sub);
			}
			// S1-1：终态（回放批 sub.terminal / 轮终态 sub.terminating）只在队列<b>真正空了</b>
			// 才收尾 —— drain 可能因补投而多跑一轮，早一步 complete 就丢掉队列里还没写出的尾帧。
			if ((sub.terminal || sub.terminating) && sub.queue.isEmpty()) {
				completeOne(sub);
			}
		}
	}

	/**
	 * drain 收尾"是否还值得抢回"的守卫（R2 的第二半 + 修复批第 2 轮的 CAS 后置）。
	 *
	 * <p>
	 * 队列非空且本订阅者未被关闭、且（终态回放 ∨ 轮终态 ∨ 仍在订阅表）。守卫<b>先</b>判、
	 * CAS<b>后</b>抢：条件不成立时标志保持"无人持有"，不会留下 {@code draining=true} 的残影
	 * （残影会让该订阅者永远不再被投递）。
	 */
	private boolean canTakeOverDrain(Subscription sub) {
		return !sub.closed && !sub.queue.isEmpty()
				&& (sub.terminal || sub.terminating || this.subscribers.contains(sub));
	}

	/**
	 * 摘除订阅者（S2-1/R3 的统一出口）：关闭投递上下文（摘表 + 清队列 + 计数归零 + 复位标志）
	 * + 显式 complete。
	 *
	 * <p>
	 * complete 触发前端 onerror/onclose ⇒ 前端带 lastSeq 重挂，差量补发把断点之后的帧
	 * 补回来（seq 无洞）。并发重复摘除由 {@code evicted} 标志去重。
	 */
	private void evict(Subscription sub, String reason) {
		if (!sub.evicted.compareAndSet(false, true)) {
			return;
		}
		EVICTED_TOTAL.incrementAndGet();
		log.warn("摘除订阅者 runId={} reason={}", this.runId, reason);
		closeSubscription(sub, "摘除：" + reason);
		completeOne(sub);
	}

	/**
	 * 完成单个订阅者（摘除收尾 / 终态回放发完）：显式 complete，触发前端重挂。
	 *
	 * <p>
	 * 与 {@link #closeSubscription} 的分工：本方法只负责"终结 emitter"（{@code completed} 是它的
	 * 去重标志），投递上下文的清理由 {@code closeSubscription} 负责 —— 这里补一次清队列与计数归零，
	 * 使"收尾 ⇒ 队列空、实时段计数 0"成为结构性事实（所有调用点的队列本已空，故不丢帧）。
	 */
	private void completeOne(Subscription sub) {
		// 幂等去重（S1-1）：轮终态排空、drain 队空、摘除三条路径可能并发命中同一个订阅者，
		// 重复 complete 会二次触发前端 onCompletion/onError。
		if (!sub.completed.compareAndSet(false, true)) {
			return;
		}
		closeSubscription(sub, "完成订阅者");
		try {
			sub.emitter.complete();
		}
		catch (Exception ex) {
			log.debug("SSE complete 失败 runId={}: {}", this.runId, ex.getMessage());
		}
	}

	/** T4：挂起等待的绝对到期时刻（= 本帧生成时刻 + 等待上限）。 */
	private static long expiresAtEpochMs(int timeoutSeconds) {
		return System.currentTimeMillis() + Math.max(0, timeoutSeconds) * 1000L;
	}

	/** T5：工具结果消息的身份（结果铸为消息）—— 由 toolCallId 确定性派生，重放/重挂不变。 */
	private static String toolMessageId(PendingToolCall pending) {
		return "tool-" + pending.getToolCallId();
	}

	/**
	 * 轮终态：<b>先置终态标志停止收新帧，再让每个订阅者把已入队的帧排空后收尾</b>（S1-1 修复批）。
	 *
	 * <p>
	 * 旧实现对每个订阅者直接 {@code completeOne}（立刻 {@code emitter.complete()}）：慢订阅者
	 * 队列里压着的尾帧（{@code done}/{@code error}/{@code suspended}）被确定性丢弃 ——
	 * 完成后的 write 在真实 {@code SseEmitter} 上还会失败（"已完成的响应"）。新实现把"收尾"
	 * 复用到 drain 的队空分支上（与终态回放路径同一口径）：
	 * <ol>
	 * <li>置 {@code terminating}：此后 {@link #emit} 不再向订阅者收新帧（帧照旧取号落档，
	 * 重挂回放仍可见），于是"排空"不会被新帧无限推迟；</li>
	 * <li>逐个订阅者 {@link #completeWhenDrained}：有 drain 在跑就交给它（它排空后在队空分支
	 * 收尾），没有就跑一个，队列已空则立即收尾；</li>
	 * <li><b>不同步等待</b>：生产调用点是轮终态线程的 {@code finally}（{@code AiController} /
	 * {@code ResumeService}），等排空会拖住轮次收尾；"排空才 complete"的语义已由 drain 的
	 * 队空分支保证。</li>
	 * </ol>
	 */
	public void complete() {
		this.terminating.set(true);
		for (Subscription sub : this.subscribers) {
			completeWhenDrained(sub);
		}
	}

	/**
	 * S1-1：把一个订阅者的收尾交给"排空其队列的那一个 drain"。
	 *
	 * <p>
	 * {@code draining} 抢得到 ⇒ 此刻既没有在跑的、也没有排队的 drain 任务（帧不会正写在半途）：
	 * 队列已空就直接收尾，否则自己跑一个 drain 去排空并收尾。抢不到 ⇒ 交给在跑的那一个 ——
	 * 它会在队空分支看到终态标志并 {@code completeOne}（见 {@link #drain} 的 {@code finally}）。
	 */
	private void completeWhenDrained(Subscription sub) {
		// 终态标志先落到订阅者上下文上：在跑的 drain 只在"自己的 finally 里看到该标志且队列已空"
		// 时才收尾，标志晚置一步这一轮 drain 就会空转退出而永不再收尾。
		sub.terminating = true;
		if (sub.evicted.get()) {
			return;
		}
		if (sub.draining.compareAndSet(false, true)) {
			if (sub.queue.isEmpty()) {
				sub.draining.set(false);
				completeOne(sub);
			}
			else {
				submitDrain(sub);
			}
		}
	}

	/**
	 * T3-1：一个订阅者的投递上下文 —— emitter + 帧队列 + CAS drain 独占标志。
	 *
	 * <p>
	 * {@code queue} 本体无界：实时段容量由 {@link #enqueueLive} 显式把守
	 * （缺省 {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY}），
	 * 回放批按 R1 裁决豁免（上界 = 归档窗口 3000）——两类入队分口径，回放不挤实时。
	 *
	 * <p>
	 * <b>单队列 + 入队标记（S1 修复批第 2 轮）</b>：回放批与实时帧共用这一条队列以保住
	 * FIFO 混合序（seq 连续性语义依赖它），但入队元素带实时/回放标记，于是
	 * {@link Subscription#liveQueued} 只数队列里的<b>实时</b>帧 —— 容量闸据此判定，回放 backlog
	 * 不再挤占实时额度。
	 */
	private final class Subscription {

		final SseEmitter emitter;

		final BlockingQueue<QueuedFrame> queue = new LinkedBlockingQueue<>();

		/**
		 * 队列中<b>实时帧</b>的条数（S1 修复批第 2 轮）：容量闸的判据（{@link #enqueueLive}），
		 * 入队 +1、drain 弹出实时帧 -1、清空/收尾时归零；回放批（{@code live=false}）不计入。
		 */
		final AtomicInteger liveQueued = new AtomicInteger();

		/** CAS drain 独占标志：true = 有（或正抢）一个 drain 任务负责清空本队列。 */
		final AtomicBoolean draining = new AtomicBoolean(false);

		/** 摘除去重（溢出 / 写出失败 / 池拒绝 / 轮终态 complete 可能并发命中同一个）。 */
		final AtomicBoolean evicted = new AtomicBoolean(false);

		/**
		 * 投递上下文已关闭（S3-1 修复批第 2 轮）：生命周期回调 / 摘除 / 收尾置位后，
		 * {@link #emit} 的扇出跳过本订阅者（不再入队、不再抢 drain），视为"安全取消挂起 drain"。
		 */
		volatile boolean closed;

		/**
		 * S1-1：本订阅者已被轮终态 {@link SseChatEmitter#complete()} 置终态 —— 与 {@link #terminal}
		 * 同族（"队列排空后收尾"），区别只在来源：{@code terminal} 是回放批自带的终态属性，
		 * 本字段是轮终态运行时置上的（可变的，故不能合成一个 final 字段）。
		 */
		volatile boolean terminating;

		/** 收尾去重（轮终态排空 / drain 队空 / 摘除三条路径可能并发命中同一个订阅者）。 */
		final AtomicBoolean completed = new AtomicBoolean(false);

		/** true = 终态回放订阅（不入订阅表，回放发完即 complete）。 */
		final boolean terminal;

		Subscription(SseEmitter emitter, boolean terminal) {
			this.emitter = emitter;
			this.terminal = terminal;
		}

	}

	/**
	 * 队列元素：帧原文 + <b>所属段</b>（实时 / 回放）。
	 *
	 * <p>
	 * 为什么要标记而不是拆成两条队列：seq 连续性与"到达顺序 = 取号顺序"依赖单订阅者
	 * <b>单条</b> FIFO（回放批在前、实时帧续在其后的混合序），拆双通道就必须自己实现
	 * 两通道的拼接与排空判定。标记法把"哪条闸管它"这一位信息留在元素上，队列结构不变。
	 */
	static final class QueuedFrame {

		final String payload;

		/** true = 实时段（计入 {@link Subscription#liveQueued}）；false = 回放批（豁免实时容量）。 */
		final boolean live;

		QueuedFrame(String payload, boolean live) {
			this.payload = payload;
			this.live = live;
		}

	}

}
