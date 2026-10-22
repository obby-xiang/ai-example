package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 进程内的轮次写出器注册表：{@code runId → SseChatEmitter}。
 *
 * <p>
 * 为什么需要它：挂起发生在<b>官方循环内部</b>（{@code SpToolCallingManager} 的扩展点上），
 * 那里不在 HTTP 请求栈里，拿不到 controller 手里的响应对象；而 reattach 又要求后来者能挂到
 * 同一个 runId 上。于是本轮的全部帧写出一律经 {@link #of(String)} 取到同一个写出器。
 *
 * <p>
 * 重启后不存在旧实例的写出器：此时 {@link #of(String)} 惰性新建一个"零订阅者"的写出器，
 * 帧照样写进事件日志（{@link FrameStore}；生产 = Redis Stream），前端重挂时回放即可 ——
 * 这正是 M3 与 ADR-5 reattach 的咬合点。
 *
 * <p>
 * <b>B23L1 批①（2026-10-21）</b>：Spring 装配路径（{@code @Autowired} 六参构造）给写出器注入
 * 事件日志 + 唤醒发布 + 唤醒订阅三件套 ⇒ 单/多实例同一套代码；非 Spring/单测的三参构造
 * 退回兼容口径（LIST 写、不跨实例广播），既有帧序用例的确定性不受影响。
 *
 * <p>
 * T3-1：持有 AiProperties 的构造（Spring 装配路径）会用 {@code app.ai.sse.*} 配置并启用
 * 进程级共享有界投递池；显式传 {@code null} properties 的非 Spring/单测路径保持同步直执，帧序用例的确定性不受影响。
 */
@Component
public class RunRegistry {

	private final Map<String, SseChatEmitter> emitters = new ConcurrentHashMap<>();

	private final RunStore store;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	/**
	 * 本注册表创建写出器时使用的投递执行器。
	 *
	 * <p>
	 * <b>口径（S2-1 修复批）</b>：Spring 三参装配路径（{@code properties != null}）用进程级共享
	 * 有界投递池（异步，快慢订阅者隔离）；显式传 {@code null} 的非 Spring/单测路径<b>固定同步直执</b>，
	 * 不读进程静态池 —— 静态池一旦被 {@code @SpringBootTest} 装配，该路径就会由"同步直执"
	 * 悄悄变异步投递，同一 JVM 内出现测试顺序相关竞态（帧序用例要求"发完即到"）。
	 */
	private final Executor deliveryExecutor;

	/**
	 * 写出<b>超时预算</b>（包②：SSE 写侧超时预算）。
	 *
	 * <p>
	 * 口径与投递执行器一致（S2-1）：Spring 三参装配路径用<b>进程级弹性写出池</b> +
	 * {@code app.ai.sse.write-timeout}；显式传 {@code null} 的非 Spring/单测路径固定 {@link SseWriteBudget#direct()}
	 * —— 不读进程静态池，避免"同一 JVM 内测试顺序相关"（理由同 {@link #deliveryExecutor}）。
	 */
	private final SseWriteBudget writeBudget;

	/** G5：模型侧裁剪器（null = 不标注模型侧视图）。 */
	private final ToolResultLimiter resultLimiter;

	/** B23L1 批①：事件日志（Spring 装配路径恒为 {@link RedisFrameStore}）。 */
	private final FrameStore frames;

	/** B23L1 批①：唤醒发布面。 */
	private final FrameWakeBus wakeBus;

	/** B23L1 批①：唤醒订阅面（按连接 subscribe/unsubscribe）。 */
	private final FrameWakeListener wakeListener;

	/**
	 * 非 Spring/单测装配（三参）：事件日志走 {@code ai:events:} LIST 兼容口径、不跨实例唤醒。
	 *
	 * <p>
	 * 保留它是为了让 {@code new RunRegistry(store, mapper, null)} 这类 conformance 装配
	 * （外置状态面 = {@code InMemoryRunStore}）零改动；Spring 装配<b>不会</b>选这个构造器
	 * （{@code @Autowired} 在六参构造上）。
	 */
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties) {
		this(store, objectMapper, properties, null, null, null);
	}

	/**
	 * 生产装配（B23L1 批①）：写出器一律拿到 Redis Stream 事件日志 + wake 扇出。
	 *
	 * <p>
	 * {@code frames/wakeBus/wakeListener} 允许为 null（局部上下文/测试）：为 null 时写出器
	 * 退回兼容口径（LIST 写、不跨实例广播），语义与 B1 收官一致。
	 */
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties, FrameStore frames,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener) {
		this(store, objectMapper, properties, frames, wakeBus, wakeListener, null);
	}

	/**
	 * 生产装配（G5 起多注入模型侧裁剪器）：{@code tool_result} 帧据此挂上"模型视图是否被截断"。
	 *
	 * <p>{@code resultLimiter} 允许为 null（局部上下文/测试）：为 null 时写出器不标注模型侧视图，
	 * 帧面其余语义不变。
	 */
	@Autowired
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties, FrameStore frames,
			FrameWakeBus wakeBus, FrameWakeListener wakeListener, ToolResultLimiter resultLimiter) {
		this.store = store;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.frames = frames;
		this.wakeBus = wakeBus;
		this.wakeListener = wakeListener;
		// 非 Spring 装配（三参/六参）也要有模型侧标注：裁剪器是无状态、纯配置驱动的，
		// 这里按同一份 AiProperties 现造一个，判定与 Spring 注入的 bean 完全一致。
		this.resultLimiter = resultLimiter != null || properties == null ? resultLimiter
				: new ToolResultLimiter(properties);
		if (properties != null) {
			// 超时面治理批① 第 3 项：把绑定后的写侧预算一并登记，供 /api/ai/health 如实回报
			SseChatEmitter.configureSharedDeliveryPool(properties.getSse().getDeliveryPoolSize(),
					properties.getSse().getDeliveryPoolSize() * 64,
					properties.getSse().getWriteTimeout().toMillis());
		}
		// 池必须先装配再取用（configureSharedDeliveryPool 幂等，首装配者胜）
		this.deliveryExecutor = properties != null ? SseChatEmitter.sharedDeliveryExecutorOrDirect() : Runnable::run;
		this.writeBudget = properties != null
				? SseWriteBudget.on(SseWriteBudget.sharedElasticWriterPool(),
						properties.getSse().getWriteTimeout().toMillis())
				: SseWriteBudget.direct();
	}

	/** 取（必要时新建）本轮的写出器。 */
	public SseChatEmitter of(String runId) {
		int queueCapacity = this.properties != null ? this.properties.getSse().getDeliveryQueueCapacity()
				: AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY;
		int maxFrameBytes = this.properties != null ? this.properties.getResultBudget().getUi().getMaxFrameBytes()
				: AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES;
		if (this.frames == null) {
			return this.emitters.computeIfAbsent(runId,
					id -> new SseChatEmitter(id, this.store, RunStoreFrameStore.of(this.store), this.objectMapper,
							this.deliveryExecutor, queueCapacity, maxFrameBytes, this.writeBudget, null, null,
							this.resultLimiter));
		}
		return this.emitters.computeIfAbsent(runId,
				id -> new SseChatEmitter(id, this.store, this.frames, this.objectMapper, this.deliveryExecutor,
						queueCapacity, maxFrameBytes, this.writeBudget, this.wakeBus, this.wakeListener,
						this.resultLimiter));
	}

	/** 只在已有写出器时返回（避免"查一下"就凭空造出一个）。 */
	public SseChatEmitter find(String runId) {
		return this.emitters.get(runId);
	}

	/** 轮终态：完成全部订阅者并把写出器移出注册表。 */
	public void close(String runId) {
		SseChatEmitter emitter = this.emitters.remove(runId);
		if (emitter != null) {
			emitter.complete();
		}
	}

}
