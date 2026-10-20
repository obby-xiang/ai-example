package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帧唤醒监听（B23L1 批① / 设计稿 §2.1）：<b>按连接</b>订阅 {@code ai:wake:<runId>}，
 * 收到唤醒后让每个本地目标去事件日志补 {@code > 已推送上界} 的差量。
 *
 * <h2>两个"按"</h2>
 * <ul>
 * <li><b>按连接</b>而非按实例：订阅只在"本实例确实有这个 run 的订阅者"时存在 ——
 * 第一个订阅者挂上时 {@code subscribe}，最后一个断开时 {@code unsubscribe}。
 * 禁止"每实例订阅全量频道"（那会让每个实例被无关轮的唤醒打扰，且订阅数随 run 数无界增长）；</li>
 * <li><b>按目标</b>而非按频道：同一个 run 可能被本实例的多个写出器同时持有（首轮 + 重挂批），
 * 每个目标（{@link WakeTarget} = 一个写出器）自己记"已推送到哪个 seq"，
 * 因此各目标进度独立、重复唤醒幂等（差量已空即 no-op）。
 * 注意粒度：水位是<b>每写出器（每 run）</b>一个，不是每订阅者一个 —— 本地扇出对所有订阅者同步广播，
 * 同一写出器内的订阅者恒同步；写成"每订阅者一个水位"会引来不必要的按连接计数。</li>
 * <li><b>登记路径不做 Redis I/O 于锁内</b>（M-6）：subscribe/unsubscribe 在锁外执行，
 * 锁内只维护目标集；首个登记与"登记期间最后一个目标恰好注销"的竞态由订阅后的空集复查收口。</li>
 * </ul>
 *
 * <p>
 * 本类只做<b>扇出与生命周期</b>，不含 Redis 细节（{@link FrameWakeBus} 负责）；
 * 差量读取与去重在目标侧（{@link SseChatEmitter#onWake}）。
 */
@Slf4j
@Component
public class FrameWakeListener {

	/** 唤醒接收方：一个 run 在本实例的一个"已推送上界"持有者。 */
	public interface WakeTarget {

		/** 有新帧（上界 seq）。实现方按自己的已推上界取差量，已空则 no-op。 */
		void onWake(String runId, long upperBoundSeq);

	}

	private final FrameWakeBus bus;

	/** runId → 目标集（guarded by {@code this}）。 */
	private final Map<String, Set<WakeTarget>> targets = new HashMap<>();

	public FrameWakeListener(FrameWakeBus bus) {
		this.bus = bus;
	}

	/**
	 * 登记一个目标；该 run 的第一个目标触发订阅。
	 *
	 * <p>
	 * 锁内只维护目标集，{@code subscribe} 在<b>锁外</b>做（它有 Redis I/O 与容器启动，
	 * 锁内做会让"某个 run 首次挂连接"阻塞所有 run 的登记与唤醒快照）。
	 *
	 * @return true = 订阅已生效（或该目标本来就在）；false = 订阅失败，调用方必须回退登记位并等重试
	 */
	public boolean register(String runId, WakeTarget target) {
		boolean first;
		synchronized (this) {
			Set<WakeTarget> set = this.targets.computeIfAbsent(runId, key -> new LinkedHashSet<>());
			first = set.isEmpty();
			if (!set.add(target)) {
				return true;
			}
		}
		if (!first) {
			return true;
		}
		boolean subscribed;
		try {
			subscribed = this.bus.subscribe(runId, upper -> dispatch(runId, upper));
		}
		catch (Exception ex) {
			subscribed = false;
		}
		if (!subscribed) {
			// I-3：订阅没建立 ⇒ 把这次登记整体撤掉，下一次 attach/close 会重新走一遍（可重试）
			synchronized (this) {
				Set<WakeTarget> set = this.targets.get(runId);
				if (set != null && set.size() == 1 && set.contains(target)) {
					this.targets.remove(runId);
				}
			}
			log.warn("帧唤醒订阅建立失败 runId={}（跨实例实时补帧缺席；下一次 attach 重试）", runId);
			return false;
		}
		log.debug("帧唤醒订阅建立 runId={}", runId);
		// 订阅期间该 run 的最后一个目标可能已注销（subscribe 在锁外）：补一次退订，避免空订阅泄漏
		boolean empty;
		synchronized (this) {
			empty = !this.targets.containsKey(runId);
		}
		if (empty) {
			this.bus.unsubscribe(runId);
			return false;
		}
		return true;
	}

	/** 注销一个目标；最后一个目标触发退订（断连即 unsubscribe，不泄漏频道订阅）。 */
	public void unregister(String runId, WakeTarget target) {
		boolean last;
		synchronized (this) {
			Set<WakeTarget> set = this.targets.get(runId);
			if (set == null || !set.remove(target)) {
				return;
			}
			last = set.isEmpty();
			if (last) {
				this.targets.remove(runId);
			}
		}
		if (last) {
			this.bus.unsubscribe(runId);
			log.debug("帧唤醒订阅释放 runId={}", runId);
		}
	}

	/** 该 run 当前登记的目标数（诊断/断言用）。 */
	public synchronized int targetCount(String runId) {
		Set<WakeTarget> set = this.targets.get(runId);
		return set == null ? 0 : set.size();
	}

	/** 当前有订阅的 run 数（诊断/断言用）。 */
	public synchronized int subscribedRuns() {
		return this.targets.size();
	}

	/**
	 * 扇出一次唤醒：锁内取快照、锁外逐个回调 —— 回调里会读事件日志并写订阅者队列，
	 * 持锁做会把"一个慢订阅者"的代价摊到所有 run 上。
	 */
	void dispatch(String runId, long upperBoundSeq) {
		List<WakeTarget> snapshot;
		synchronized (this) {
			Set<WakeTarget> set = this.targets.get(runId);
			if (set == null || set.isEmpty()) {
				return;
			}
			snapshot = new ArrayList<>(set);
		}
		for (WakeTarget target : snapshot) {
			try {
				target.onWake(runId, upperBoundSeq);
			}
			catch (Exception ex) {
				log.warn("唤醒处理失败 runId={} seq={}（该目标跳过本拍，下拍/重挂补帧）：{}", runId,
						upperBoundSeq, ex.getMessage());
			}
		}
	}

}
