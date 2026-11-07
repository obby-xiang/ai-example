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
 * 第一个订阅者挂上时建订阅（拿句柄），最后一个断开时 close 该句柄。
 * 禁止"每实例订阅全量频道"（那会让每个实例被无关轮的唤醒打扰，且订阅数随 run 数无界增长）；</li>
 * <li><b>按目标</b>而非按频道：同一个 run 可能被本实例的多个写出器同时持有（首轮 + 重挂批），
 * 每个目标（{@link WakeTarget} = 一个写出器）自己记"已推送到哪个 seq"，
 * 因此各目标进度独立、重复唤醒幂等（差量已空即 no-op）。</li>
 * <li><b>按"身份 + runId"</b>：唤醒频道名带身份段
 * （{@code ai:wake:<tenantId>:<userId>:<runId>}，见 {@link RedisFrameWakeBus#channel}）。
 * 登记表只按身份桶 + runId 分桶，两个租户同 runId 互不影响；没有身份就不登记
 * （不存在裸 runId 桶，也不靠"当前线程碰巧是谁"重算频道）。</li>
 * </ul>
 *
 * <p>因此每个目标登记时绑定<b>一个句柄</b>（{@link Registration}：身份 + runId + 分桶键），
 * subscribe/unsubscribe 都用它 —— 退订不依赖"这一拍线程碰巧是谁"；桶持有的
 * {@link FrameWakeBus.Subscription} 句柄则让退订精确到"这一条监听器"。
 *
 * <h2>并发口径（非阻塞 + 句柄退订）</h2>
 * 订阅/退订都是锁外 Redis I/O，期间可能穿插别的登记与注销。两条硬约束：
 * <ul>
 * <li><b>不阻塞调用方</b>：调用方可能是 Redis 订阅连接的分发线程（唤醒回调 → 写出器的
 *     {@code applyWakeSync} → {@link #register}），在那里等待会把所有频道的投递一起拖住。
 *     订阅在途时来的目标<b>照样进桶并保留</b>（订阅成功后与发起方一样收到唤醒），只是拿到
 *     "未确认"（false）—— 调用方下次 attach/出帧再登记时就会拿到 true；</li>
 * <li><b>句柄退订</b>：旧桶的补偿退订可能晚于继任桶的订阅落地。退订只摘本桶句柄捕获的
 *     那一条监听器（{@link FrameWakeBus.Subscription#close()}），因此旧退订不会误退继任订阅，
 *     继任者也<b>不必等待</b>旧退订完成。</li>
 * </ul>
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

	/** 分桶键（身份段 + runId）→ 桶（目标集 + 订阅状态；guarded by {@code this}）。 */
	private final Map<String, Bucket> buckets = new HashMap<>();

	/** 目标 → 登记句柄（一个目标只属于一轮；退订按它精确回到同一桶/同一频道）。 */
	private final Map<WakeTarget, Registration> registrations = new HashMap<>();

	/** 订阅代次发号器（guarded by {@code this}）：每次建桶 = 一代，仅用于日志与诊断。 */
	private long generationSeq;

	public FrameWakeListener(FrameWakeBus bus) {
		this.bus = bus;
	}

	/**
	 * 一个目标在本实例的唤醒登记（身份 + runId + 分桶键）：subscribe/unsubscribe 的<b>同一句柄</b>。
	 * 订阅句柄本身在桶上（{@link Bucket}），不随每个目标复制。
	 */
	public record Registration(TenantUser identity, String runId, String key) {

	}

	/**
	 * 桶的订阅状态（guarded by 监听器锁）。
	 *
	 * <p>状态必须跟着桶走，不能靠"目标集非空"推断：订阅在锁外进行，期间桶里可能已有别的目标，
	 * 而那一刻订阅<b>尚未建立</b>（见 {@link #register}）。
	 */
	private enum State {

		/**
		 * 有线程正在为该桶建订阅：桶内目标<b>已登记并保留</b>（订阅成功后即生效），但此刻不能对外
		 * 宣称"已订阅"，因此后来者拿到的是"未确认"（false）而不是等待。
		 */
		SUBSCRIBING,

		/** 订阅已建立：后来者直接加入。 */
		SUBSCRIBED

	}

	/**
	 * 一个（身份, runId）桶：目标集 + 订阅状态 + 订阅句柄。
	 *
	 * <p>句柄是"退订不误伤继任"的依据：旧桶那条晚到的 close 只摘它自己捕获的监听器，
	 * 继任桶（另一个句柄）的监听器不受影响。代次只留作诊断。
	 */
	private static final class Bucket {

		private final TenantUser identity;

		private final String runId;

		/** 本次订阅的代次（本实例内单调递增，仅用于日志/诊断）。 */
		private final long generation;

		/**
		 * 本次订阅的精确句柄（guarded by 外层监听器的锁）。
		 *
		 * <p>{@code SUBSCRIBING} 期间为 null；订阅成功后与 {@link #state} 一起置位。
		 * 桶被摘掉时由最后一个离开者取出并在锁外 close —— 只摘这一条监听器。
		 */
		private FrameWakeBus.Subscription subscription;

		/** 桶内目标（guarded by 外层监听器的锁）。 */
		private final Set<WakeTarget> targets = new LinkedHashSet<>();

		/**
		 * <b>已确认</b>的目标（{@code register} 返回过 true 的那些；guarded by 外层监听器的锁）。
		 *
		 * <p>订阅在途时加入的目标是<b>未确认</b>的：它照样在 {@link #targets} 里、订阅成功后照样收到
		 * 唤醒，但调用方拿到的是 false —— 而调用方（{@code SseChatEmitter}）在 false 时不会来退订。
		 * 因此桶的订阅由<b>已确认</b>的持有者支撑，但最后一个确认者离开时<b>不是整桶摘掉</b>：
		 * 只要桶里还有存活目标，就<b>就地提升</b>一个为确认者、保留现有订阅（自愈，不必等下一次
		 * attach/出帧）；只有真正没有目标时才退订。这样"不丢目标"与"不留僵尸订阅"同时成立，
		 * 也避免"整桶摘 + 客人重新登记"造成的订阅/退订抖动。
		 */
		private final Set<WakeTarget> confirmed = new LinkedHashSet<>();

		/** 订阅状态（guarded by 外层监听器的锁）。 */
		private State state;

		private boolean wakeDispatching;
		private long pendingWakeUpper;

		Bucket(TenantUser identity, String runId, long generation, State state) {
			this.identity = identity;
			this.runId = runId;
			this.generation = generation;
			this.state = state;
		}

	}

	/**
	 * 分桶键：恒为 {@code <tenantId>:<userId>:<runId>}。
	 *
	 * <p>身份缺失不是"旧命名空间"而是配置/调用错误：没有身份就拼不出可判定的归属，
	 * 不得退回裸 {@code runId}（那会与身份桶形成两套唤醒面）。{@link #register} 在调用本方法前
	 * 已拒绝 null 身份。
	 */
	static String keyOf(TenantUser identity, String runId) {
		if (identity == null) {
			throw new IllegalArgumentException("frame wake bucket requires explicit tenant/user identity");
		}
		return identity.tenantId() + ":" + identity.userId() + ":" + runId;
	}

	/**
	 * 登记一个目标；该（身份, run）桶的<b>第一个</b>目标触发订阅。
	 *
	 * <p>锁内只维护目标集与订阅状态，{@code subscribe} 在<b>锁外</b>做（它有 Redis I/O 与容器启动，
	 * 锁内做会让"某个 run 首次挂连接"阻塞所有 run 的登记与唤醒快照）。
	 *
	 * <p><b>本方法绝不阻塞调用方</b>（调用方可能是 Redis 订阅连接的分发线程：唤醒回调 →
	 * {@code SseChatEmitter.applyWakeSync} → 这里；在那条线程上等待会把<b>所有频道</b>的投递一起拖住）。
	 * 三种结局：
	 * <ul>
	 * <li>桶已订阅 ⇒ 加入桶并返回 true；</li>
	 * <li>桶正在建订阅 ⇒ 目标<b>照样加入桶</b>（订阅成功后它与发起方一样收到唤醒），
	 *     但此刻不能宣称"已订阅"，返回 <b>false（未确认）</b>；调用方下一次 attach/出帧时会再登记，
	 *     那时若已订阅就得到 true；</li>
	 * <li>订阅失败 ⇒ <b>按桶完整回滚</b>（含在途期间加入的目标），失败方留在表里会让后来者
	 *     被当成"已有订阅"。</li>
	 * </ul>
	 *
	 * <p>身份取<b>登记线程</b>的当前显式身份（出帧/挂起路径按本轮记录身份建立作用域），并随句柄
	 * 固定下来：频道名与后续退订都用这一份，不受回调线程（Redis 监听线程 / 连接关闭线程）影响。
	 * 没有身份 ⇒ <b>不登记</b>（没有可判定的桶/频道，调用方下一拍重试），绝不退回裸 runId。
	 *
	 * @return true = 订阅已生效且本目标已在桶里（<b>已确认</b>）；false = <b>未确认</b>（目标已保留、
	 * 订阅尚未建立）、订阅失败（整桶已回滚）或身份缺失（未登记）。三种情形调用方都可安全重试
	 */
	public boolean register(String runId, WakeTarget target) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null) {
			log.warn("frame wake register skipped runId={} (no explicit tenant/user identity)", runId);
			return false;
		}
		String key = keyOf(identity, runId);
		Bucket bucket;
		synchronized (this) {
			Bucket current = this.buckets.get(key);
			if (current != null) {
				if (current.targets.add(target)) {
					this.registrations.put(target, new Registration(identity, runId, key));
				}
				if (current.state == State.SUBSCRIBED) {
					current.confirmed.add(target);
					return true;
				}
				// SUBSCRIBING：目标已保留（订阅成功后照样收到唤醒），但订阅尚未确认 ⇒ 如实回 false
				return false;
			}
			bucket = new Bucket(identity, runId, ++this.generationSeq, State.SUBSCRIBING);
			this.buckets.put(key, bucket);
			bucket.targets.add(target);
			this.registrations.put(target, new Registration(identity, runId, key));
		}
		FrameWakeBus.Subscription subscription;
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			// 订阅在<b>该桶身份</b>下执行：RedisFrameWakeBus 用线程身份拼频道名。
			// 句柄捕获本次安装的监听器：退订按对象摘，迟到的 close 碰不到继任订阅。
			subscription = this.bus.subscribeHandle(runId, bucket.generation,
					upper -> dispatch(key, runId, upper));
		}
		catch (Exception ex) {
			subscription = null;
		}
		catch (Throwable error) {
			// Error（如容器级 OOM/断言）也必须先把桶撤掉再抛出：否则该 key 永远停在"订阅在途"，
			// 后续登记只能拿到"未确认"、这条 run 的唤醒再也建不起来。
			rollback(key, bucket, runId, identity);
			throw error;
		}
		if (subscription == null) {
			// 订阅没建立 ⇒ 整桶回滚（含在途期间加入的目标），下一次 attach/close 会重新走一遍
			rollback(key, bucket, runId, identity);
			return false;
		}
		boolean keep;
		synchronized (this) {
			// 发起方在订阅在途期间可能已注销：那就没有任何"已确认"的持有者，这次订阅必须撤掉，
			// 不能靠"未确认的客人"把订阅养着（客人拿到的是 false，会自行重试）。
			keep = bucket.targets.contains(target);
			if (keep) {
				bucket.state = State.SUBSCRIBED;
				bucket.subscription = subscription;
				bucket.confirmed.add(target);
			}
			else {
				dropBucket(key, bucket);
			}
		}
		if (!keep) {
			// 补偿退订按句柄：只摘本次安装的监听器，继任桶（另一句柄）不受影响
			closeSubscription(subscription, runId, identity);
			return false;
		}
		log.debug("frame wake subscription established runId={} identity={} generation={}", runId, identity, bucket.generation);
		return true;
	}

	/**
	 * 订阅失败后的<b>按桶完整回滚</b>：桶内所有目标一起撤（目标集、登记句柄、桶）。
	 *
	 * <p>为什么不是"只撤发起方"：这次订阅没建立，桶里任何目标都不算已订阅；把它们留在表里
	 * 既永远等不到退订，又会让后来的登记看到"桶非空 ⇒ 已有订阅"而误记已订阅。
	 */
	private void rollback(String key, Bucket bucket, String runId, TenantUser identity) {
		synchronized (this) {
			dropBucket(key, bucket);
		}
		log.warn("frame wake subscription setup failed runId={} identity={} (bucket rolled back; next attach retries)", runId, identity);
	}

	/** 摘掉整桶及其全部登记句柄（含"未确认"的客人）；guarded by {@code this}。 */
	private void dropBucket(String key, Bucket bucket) {
		for (WakeTarget target : bucket.targets) {
			this.registrations.remove(target);
		}
		bucket.targets.clear();
		bucket.confirmed.clear();
		this.buckets.remove(key, bucket);
	}

	/** 当前登记句柄数（诊断/断言用：被提升的客人关闭后必须归零，否则写出器被 map 引用）。 */
	synchronized int registrationCount() {
		return this.registrations.size();
	}

	/**
	 * 注销一个目标；最后一个<b>已确认</b>目标离开时触发退订（断连即 unsubscribe，不泄漏频道订阅）。
	 *
	 * <p>"未确认"的客人（{@code register} 回过 false 的目标）在<b>最后一个确认者离开</b>时被
	 * <b>就地提升</b>为确认者、订阅保持不变（它们仍在正常收流；摘掉订阅会让这条 run 的跨实例唤醒
	 * 静默缺席到下一次出帧/重挂）。只有<b>真正没有目标</b>时才退订。
	 */
	public void unregister(String runId, WakeTarget target) {
		Registration registration;
		Bucket bucket = null;
		FrameWakeBus.Subscription released = null;
		synchronized (this) {
			registration = this.registrations.remove(target);
			if (registration == null) {
				return;
			}
			bucket = this.buckets.get(registration.key());
			if (bucket == null || !bucket.targets.remove(target)) {
				return;
			}
			bucket.confirmed.remove(target);
			boolean subscribed = bucket.state == State.SUBSCRIBED;
			if (bucket.targets.isEmpty()) {
				// 立刻摘桶 ⇒ 继任者可以马上建新桶订阅（不等旧退订完成）；
				// 旧退订只摘自己的句柄，晚到也碰不到继任的监听器。
				released = bucket.subscription;
				dropBucket(registration.key(), bucket);
				// 订阅还在途时句柄为 null（发起方返回时会自己走"无可确认持有者 ⇒ 按句柄补偿退订"）。
			}
			else if (subscribed && bucket.confirmed.isEmpty()) {
				// <b>返修第 3 条（并入 Task 4 复审遗留）</b>：最后一个确认者离开，但桶里还有存活目标
				// （订阅在途时登记、拿到"未确认"的客人）—— 它们<b>仍在正常收流</b>，只是调用方还没再
				// 登记过。整桶摘掉会让这条 run 的跨实例唤醒从这一刻起静默缺席，直到客人自己再出帧/重挂
				// （期间只能靠前端看门狗兜底）。正确做法是<b>就地提升</b>一个客人为确认者：订阅保持不变
				// （同代次、零次 unsubscribe），自愈；客人之后再登记只会拿到 true（幂等）。
				WakeTarget promoted = bucket.targets.iterator().next();
				bucket.confirmed.add(promoted);
				log.debug("frame wake last confirmed subscriber left but live targets "
						+ "remain, promoting to confirmed runId={} identity={} generation={}", registration.runId(), registration.identity(), bucket.generation);
			}
		}
		if (released != null) {
			closeSubscription(released, runId, registration.identity());
			log.debug("frame wake subscription released runId={} identity={} generation={}", runId, registration.identity(),
					bucket.generation);
		}
	}

	/**
	 * 退订：只 close 本桶的句柄（它摘的就是本次安装的监听器，与"频道当前是谁"无关）。
	 *
	 * <p>仍在<b>登记身份</b>作用域里 close：句柄退订本身按监听器对象摘，但保持作用域可与
	 * 订阅侧的频道口径完全一致（断连/投递池线程没有请求身份）。
	 */
	private void closeSubscription(FrameWakeBus.Subscription subscription, String runId, TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			subscription.close();
		}
		catch (Exception ex) {
			log.warn("frame wake unsubscribe failed runId={} identity={}", runId, identity, ex);
		}
	}

	/** 当前<b>订阅已建立</b>的（身份, run）桶数（诊断/断言用）。 */
	public synchronized int subscribedRuns() {
		int subscribed = 0;
		for (Bucket bucket : this.buckets.values()) {
			if (bucket.state == State.SUBSCRIBED) {
				subscribed++;
			}
		}
		return subscribed;
	}

	/** 指定身份桶的目标数（诊断/断言用）。 */
	public synchronized int targetCount(TenantUser identity, String runId) {
		if (identity == null) {
			return 0;
		}
		Bucket bucket = this.buckets.get(keyOf(identity, runId));
		return bucket == null ? 0 : bucket.targets.size();
	}

	/** 每桶单飞；读档期间到达的上界合流成下一轮最大值，不叠加并发差量读。 */
	void dispatch(String key, String runId, long upperBoundSeq) {
		Bucket bucket;
		synchronized (this) {
			bucket = this.buckets.get(key);
			if (bucket == null || bucket.targets.isEmpty()) {
				return;
			}
			bucket.pendingWakeUpper = Math.max(bucket.pendingWakeUpper, upperBoundSeq);
			if (bucket.wakeDispatching) {
				return;
			}
			bucket.wakeDispatching = true;
		}
		while (true) {
			List<WakeTarget> snapshot;
			long upper;
			synchronized (this) {
				if (this.buckets.get(key) != bucket || bucket.targets.isEmpty()) {
					return;
				}
				upper = bucket.pendingWakeUpper;
				bucket.pendingWakeUpper = 0L;
				snapshot = new ArrayList<>(bucket.targets);
			}
			for (WakeTarget target : snapshot) {
				try {
					target.onWake(runId, upper);
				}
				catch (Exception ex) {
					log.warn("wake handling failed runId={} seq={} (target skipped this tick; next tick/reattach replays)", runId,
							upper, ex);
				}
			}
			synchronized (this) {
				if (this.buckets.get(key) != bucket) {
					return;
				}
				if (bucket.pendingWakeUpper <= 0L) {
					bucket.wakeDispatching = false;
					return;
				}
			}
		}
	}

}
