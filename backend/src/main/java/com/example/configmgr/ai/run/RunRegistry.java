package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
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
 * 帧照样写进 {@code ai:events:<runId>}（外置优先），前端重挂时回放即可 —— 这正是
 * M3 与 ADR-5 reattach 的咬合点。
 *
 * <p>
 * T3-1：持有 AiProperties 的构造（Spring 装配路径）会用 {@code app.ai.sse.*} 配置并启用
 * 进程级共享有界投递池；两参构造（单测/非 Spring）保持同步直执，帧序用例的确定性不受影响。
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
	 * 有界投递池（异步，快慢订阅者隔离）；两参构造（单测 / 非 Spring）<b>固定同步直执</b>，
	 * 不读进程静态池 —— 静态池一旦被 {@code @SpringBootTest} 装配，两参路径就会由"同步直执"
	 * 悄悄变异步投递，同一 JVM 内出现测试顺序相关竞态（帧序用例要求"发完即到"）。
	 */
	private final Executor deliveryExecutor;

	/**
	 * 写出<b>超时预算</b>（包②：SSE 写侧超时预算）。
	 *
	 * <p>
	 * 口径与投递执行器一致（S2-1）：Spring 三参装配路径用<b>进程级弹性写出池</b> +
	 * {@code app.ai.sse.write-timeout}；两参构造（单测 / 非 Spring）固定 {@link SseWriteBudget#direct()}
	 * —— 不读进程静态池，避免"同一 JVM 内测试顺序相关"（理由同 {@link #deliveryExecutor}）。
	 */
	private final SseWriteBudget writeBudget;

	public RunRegistry(RunStore store, ObjectMapper objectMapper) {
		this(store, objectMapper, null);
	}

	@Autowired
	public RunRegistry(RunStore store, ObjectMapper objectMapper, AiProperties properties) {
		this.store = store;
		this.objectMapper = objectMapper;
		this.properties = properties;
		if (properties != null) {
			SseChatEmitter.configureSharedDeliveryPool(properties.getSse().getDeliveryPoolSize(),
					properties.getSse().getDeliveryPoolSize() * 64);
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
		int maxFrameBytes = this.properties != null ? this.properties.getFrame().getMaxBytes()
				: AiProperties.Frame.DEFAULT_MAX_BYTES;
		return this.emitters.computeIfAbsent(runId, id -> new SseChatEmitter(id, this.store, this.objectMapper,
				this.deliveryExecutor, queueCapacity, maxFrameBytes, this.writeBudget));
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
