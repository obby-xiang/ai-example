package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把进程内 {@link InMemoryRunStore} 的归档面适配成 {@link FrameStore}（测试专用）。
 *
 * <p>为什么必须复用<b>同一个</b> store：帧序用例的断言读 {@code store.events(runId)}；
 * 若注册表写另一份 FrameStore，断言对象与真实写入面就分叉了（测试自证）。
 * 本适配器只做"同一份归档 + 同一份发放号"的视图，不新增存储。
 */
final class RunStoreFrameStore implements FrameStore {

	private final InMemoryRunStore store;

	/** 本地单调兜底：只读租约/登记失败时仍保证本进程取号不重复（与生产降级口径一致）。 */
	private final Map<String, java.util.concurrent.atomic.AtomicLong> localSeqs = new java.util.concurrent.ConcurrentHashMap<>();

	private RunStoreFrameStore(InMemoryRunStore store) {
		this.store = store;
	}

	static RunStoreFrameStore of(RunStore store) {
		if (!(store instanceof InMemoryRunStore memory)) {
			throw new IllegalArgumentException("RunStoreFrameStore 仅用于 InMemoryRunStore（测试替身）");
		}
		return new RunStoreFrameStore(memory);
	}

	@Override
	public long nextSeq(String runId) {
		long local = this.localSeqs.computeIfAbsent(runId, key -> new java.util.concurrent.atomic.AtomicLong())
				.incrementAndGet();
		long persisted = Math.max(this.store.lastIssuedSeq(runId), this.store.lastEventSeq(runId));
		long next = Math.max(local, persisted + 1L);
		// 只读租约/降级时 recordIssuedSeq 是 no-op；本地计数仍前进，避免同一号重复交付。
		this.store.recordIssuedSeq(runId, next);
		return next;
	}

	@Override
	public void append(String runId, Map<String, Object> frame) {
		this.store.appendEvent(runId, frame);
	}

	@Override
	public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		int max = limit <= 0 ? Integer.MAX_VALUE : limit;
		for (Map<String, Object> frame : this.store.events(runId)) {
			Object seq = frame.get("seq");
			long value = seq instanceof Number number ? number.longValue() : 0L;
			if (value <= afterSeq) {
				continue;
			}
			out.add(frame);
			if (out.size() >= max) {
				break;
			}
		}
		return out;
	}

	@Override
	public long lastSeq(String runId) {
		return Math.max(this.store.lastIssuedSeq(runId), this.store.lastEventSeq(runId));
	}
}
