package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
@Component
public class RunRegistry {

	private final Map<String, SseChatEmitter> emitters = new ConcurrentHashMap<>();

	private final RunStore store;

	private final ObjectMapper objectMapper;

	public RunRegistry(RunStore store, ObjectMapper objectMapper) {
		this.store = store;
		this.objectMapper = objectMapper;
	}

	/** 取（必要时新建）本轮的写出器。 */
	public SseChatEmitter of(String runId) {
		return this.emitters.computeIfAbsent(runId, id -> new SseChatEmitter(id, this.store, this.objectMapper));
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
