package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
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
 * <h2>三个"按"</h2>
 * <ul>
 * <li><b>按连接</b>而非按实例：订阅只在"本实例确实有这个 run 的订阅者"时存在 ——
 * 第一个订阅者挂上时 {@code subscribe}，最后一个断开时 {@code unsubscribe}。
 * 禁止"每实例订阅全量频道"（那会让每个实例被无关轮的唤醒打扰，且订阅数随 run 数无界增长）；</li>
 * <li><b>按目标</b>而非按频道：同一个 run 可能被本实例的多个写出器同时持有（首轮 + 重挂批），
 * 每个目标（{@link WakeTarget} = 一个写出器）自己记"已推送到哪个 seq"，
 * 因此各目标进度独立、重复唤醒幂等（差量已空即 no-op）。</li>
 * <li><b>按"身份 + runId"</b>（租户任务）：唤醒频道名带身份段
 * （{@code ai:wake:<tenantId>:<userId>:<runId>}，见 {@link RedisFrameWakeBus#channel(String)}）。
 * 登记表若只按裸 runId 分桶，两个租户同 runId 时第二个目标会撞上"首个已订阅"的短路
 * ⇒ 它的频道<b>永不订阅</b>（跨实例唤醒静默缺席）；退订若按"当前线程身份"重算频道，
 * 无身份线程（断连回调/投递池）算出来的是 legacy 频道 ⇒ 真条目不被移除（僵尸订阅）。</li>
 * </ul>
 *
 * <p>因此每个目标登记时绑定<b>一个句柄</b>（{@link Registration}：身份 + runId + 分桶键），
 * subscribe 与 unsubscribe 都用它 —— 退订不依赖"这一拍线程碰巧是谁"。
 *
 * <p>本类只做<b>扇出与生命周期</b>，不含 Redis 细节（{@link FrameWakeBus} 负责）；
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

	/** 分桶键（身份段 + runId）→ 目标集（guarded by {@code this}）。 */
	private final Map<String, Set<WakeTarget>> targets = new HashMap<>();

	/** 分桶键 → 该桶的身份与 runId（退订时按同一份身份重算频道）。 */
	private final Map<String, Slot> slots = new HashMap<>();

	/** 目标 → 登记句柄（一个目标只属于一轮；退订按它精确回到同一桶/同一频道）。 */
	private final Map<WakeTarget, Registration> registrations = new HashMap<>();

	public FrameWakeListener(FrameWakeBus bus) {
		this.bus = bus;
	}

	/**
	 * 一个目标在本实例的唤醒登记（身份 + runId + 分桶键）：subscribe/unsubscribe 的<b>同一句柄</b>。
	 */
	public record Registration(TenantUser identity, String runId, String key) {

	}

	/** 桶的身份与 runId（内部登记用）。 */
	private record Slot(TenantUser identity, String runId) {
	}

	/**
	 * 分桶键：有身份 = {@code <tenantId>:<userId>:<runId>}；无身份 = 旧形 {@code <runId>}
	 * （legacy 命名空间，与频道名同源）。
	 */
	static String keyOf(TenantUser identity, String runId) {
		return identity == null ? runId : identity.tenantId() + ":" + identity.userId() + ":" + runId;
	}

	/**
	 * 登记一个目标；该（身份, run）桶的第一个目标触发订阅。
	 *
	 * <p>锁内只维护目标集，{@code subscribe} 在<b>锁外</b>做（它有 Redis I/O 与容器启动，
	 * 锁内做会让"某个 run 首次挂连接"阻塞所有 run 的登记与唤醒快照）。
	 *
	 * <p>身份取<b>登记线程</b>的当前身份（出帧/挂起路径带着请求或恢复身份），并随句柄固定下来：
	 * 频道名与后续退订都用这一份，不受回调线程（Redis 监听线程 / 连接关闭线程）影响。
	 *
	 * @return true = 订阅已生效（或该目标本来就在）；false = 订阅失败，调用方必须回退登记位并等重试
	 */
	public boolean register(String runId, WakeTarget target) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		String key = keyOf(identity, runId);
		boolean first;
		synchronized (this) {
			Set<WakeTarget> set = this.targets.get(key);
			if (set == null) {
				set = new LinkedHashSet<>();
				this.targets.put(key, set);
				this.slots.put(key, new Slot(identity, runId));
			}
			first = set.isEmpty();
			if (!set.add(target)) {
				return true;
			}
			this.registrations.put(target, new Registration(identity, runId, key));
		}
		if (!first) {
			return true;
		}
		boolean subscribed;
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			// 订阅在<b>该桶身份</b>下执行：RedisFrameWakeBus 用线程身份拼频道名。
			subscribed = this.bus.subscribe(runId, upper -> dispatch(key, runId, upper));
		}
		catch (Exception ex) {
			subscribed = false;
		}
		if (!subscribed) {
			// I-3：订阅没建立 ⇒ 把这次登记整体撤掉，下一次 attach/close 会重新走一遍（可重试）
			synchronized (this) {
				Set<WakeTarget> set = this.targets.get(key);
				if (set != null && set.size() == 1 && set.contains(target)) {
					this.targets.remove(key);
					this.slots.remove(key);
					this.registrations.remove(target);
				}
			}
			log.warn("帧唤醒订阅建立失败 runId={} identity={}（跨实例实时补帧缺席；下一次 attach 重试）", runId,
					identity);
			return false;
		}
		log.debug("帧唤醒订阅建立 runId={} identity={}", runId, identity);
		// 订阅期间该桶的最后一个目标可能已注销（subscribe 在锁外）：补一次退订，避免空订阅泄漏
		Slot slot = null;
		synchronized (this) {
			if (!this.targets.containsKey(key)) {
				slot = new Slot(identity, runId);
			}
		}
		if (slot != null) {
			unsubscribe(new Registration(slot.identity(), runId, key));
			return false;
		}
		return true;
	}

	/** 注销一个目标；最后一个目标触发退订（断连即 unsubscribe，不泄漏频道订阅）。 */
	public void unregister(String runId, WakeTarget target) {
		Registration registration;
		boolean last = false;
		Slot slot;
		synchronized (this) {
			registration = this.registrations.remove(target);
			if (registration == null) {
				return;
			}
			Set<WakeTarget> set = this.targets.get(registration.key());
			if (set == null || !set.remove(target)) {
				this.slots.remove(registration.key());
				return;
			}
			last = set.isEmpty();
			slot = last ? this.slots.remove(registration.key()) : null;
			if (last) {
				this.targets.remove(registration.key());
			}
		}
		if (last) {
			unsubscribe(slot == null ? new Registration(null, runId, registration.key()) : new Registration(
					slot.identity(), runId, registration.key()));
			log.debug("帧唤醒订阅释放 runId={} identity={}", runId, registration.identity());
		}
	}

	/** 退订：与 subscribe <b>同一身份</b>（频道名因此逐字相同），与调用线程身份无关。 */
	private void unsubscribe(Registration registration) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(registration.identity())) {
			this.bus.unsubscribe(registration.runId());
		}
		catch (Exception ex) {
			log.warn("帧唤醒退订失败 runId={} identity={}：{}", registration.runId(), registration.identity(),
					ex.getMessage());
		}
	}

	/**
	 * 该 run 当前登记的目标数（诊断/断言用）。
	 *
	 * <p>按 runId 汇总全部身份桶（跨租户同 runId 时是各桶之和）：调用方问的是"这个 runId 在本实例
	 * 还有没有目标"，不是"某个身份有几个"。
	 */
	public synchronized int targetCount(String runId) {
		int total = 0;
		for (Slot slot : this.slots.values()) {
			if (runId.equals(slot.runId())) {
				Set<WakeTarget> set = this.targets.get(keyOf(slot.identity(), slot.runId()));
				total += set == null ? 0 : set.size();
			}
		}
		return total;
	}

	/** 当前有订阅的（身份, run）桶数（诊断/断言用）。 */
	public synchronized int subscribedRuns() {
		return this.targets.size();
	}

	/** 指定身份桶的目标数（诊断/断言用）。 */
	public synchronized int targetCount(TenantUser identity, String runId) {
		Set<WakeTarget> set = this.targets.get(keyOf(identity, runId));
		return set == null ? 0 : set.size();
	}

	/**
	 * 扇出一次唤醒：锁内取快照、锁外逐个回调 —— 回调里会读事件日志并写订阅者队列，
	 * 持锁做会把"一个慢订阅者"的代价摊到所有 run 上。
	 */
	void dispatch(String runId, long upperBoundSeq) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		dispatch(keyOf(identity, runId), runId, upperBoundSeq);
	}

	/** 按分桶键扇出（订阅回调据此只唤醒<b>该桶</b>的目标，不跨租户）。 */
	void dispatch(String key, String runId, long upperBoundSeq) {
		List<WakeTarget> snapshot;
		synchronized (this) {
			Set<WakeTarget> set = this.targets.get(key);
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
