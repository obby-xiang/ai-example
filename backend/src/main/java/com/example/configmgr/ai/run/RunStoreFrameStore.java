package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FrameStore} 的<b>兼容期实现</b>：事件日志 = {@code ai:events:<runId>} LIST，序号 = 进程内锚
 * （{@code max(归档末帧, ai:seq) + 1}）。
 *
 * <h2>为什么还留着它</h2>
 * 它是<b>非 Spring 装配路径</b>（单测 / conformance 替身 / 非 Spring 三参构造）的唯一入口：
 * 这些路径的外置状态面是 {@code InMemoryRunStore} 之类"按 LIST 语义重写"的替身，
 * 它们断言的是"归档里有什么、末帧号是多少"，换成 Stream 口径就断言不了。
 * 它的语义逐字等于 B1 收官时的写出器行为（取号锚 = {@code max(归档末帧, ai:seq)}、
 * 发放即登记、心跳不落档），因此既有帧序用例零改动、断言一条不降。
 *
 * <p>
 * <b>生产装配不可达</b>：{@link RunRegistry} 的 Spring 装配路径（{@code @Autowired} 构造）
 * 恒注入 {@link RedisFrameStore}；本类只由 {@link SseChatEmitter} 的兼容构造器创建
 * （包内可见，不进公开 API）。设计稿 §2.1 的"单/多实例同一实现"指的是<b>生产实现</b>唯一。
 */
@Slf4j
final class RunStoreFrameStore implements FrameStore {

	private final RunStore store;

	/** 每轮一个本地号锚（首次取号时按 {@code max(归档末帧, ai:seq)} 惰性初始化）。 */
	private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();

	private RunStoreFrameStore(RunStore store) {
		this.store = store;
	}

	static FrameStore of(RunStore store) {
		return new RunStoreFrameStore(store);
	}

	@Override
	public long nextSeq(String runId) {
		AtomicLong counter = this.seqs.computeIfAbsent(runId,
				id -> new AtomicLong(Math.max(this.store.lastEventSeq(id), this.store.lastIssuedSeq(id))));
		long next = counter.incrementAndGet();
		try {
			this.store.recordIssuedSeq(runId, next);
		}
		catch (Exception ex) {
			// S2-4：登记失败只 WARN，继续出帧（本地号锚已推进）
			log.warn("登记已发放序号失败 runId={} seq={}（继续出帧）：{}", runId, next, ex.getMessage());
		}
		return next;
	}

	@Override
	public void append(String runId, Map<String, Object> frame) {
		this.store.appendEvent(runId, frame);
	}

	@Override
	public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> frame : this.store.events(runId)) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number && number.longValue() <= afterSeq) {
				continue;
			}
			out.add(frame);
			if (limit > 0 && out.size() >= limit) {
				break;
			}
		}
		return out;
	}

	@Override
	public long lastSeq(String runId) {
		return this.store.lastEventSeq(runId);
	}

	/**
	 * 终态释放本轮的进程内号锚（与生产实现 {@link RedisFrameStore#forget} 同口径）。
	 *
	 * <p>下一次取号会按 {@code max(归档末帧, 已发放序号)} 重锚（{@link #nextSeq} 的惰性初始化），
	 * 因此回收不会让号回退。
	 */
	@Override
	public void forget(String runId) {
		this.seqs.remove(runId);
	}

}
