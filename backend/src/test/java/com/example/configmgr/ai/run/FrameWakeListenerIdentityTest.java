package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>唤醒登记表按"身份 + runId"键控，且订阅/退订用同一身份</b>（真实频道键形
 * {@code ai:wake:<tenantId>:<userId>:<runId>}）。
 *
 * <h2>被钉住的两条</h2>
 * <ol>
 * <li><b>两个租户同 runId 必须各自订阅</b>：登记表若只按裸 runId 分桶，第二个目标的登记会撞上
 *     "首个已订阅"的短路 ⇒ 它的频道永不订阅 ⇒ 该租户的跨实例唤醒静默缺席；</li>
 * <li><b>退订用订阅时的身份</b>：连接关闭/投递池线程没有请求身份，若退订时按"当前线程身份"重算
 *     频道（{@code ai:wake:<runId>} legacy 形），真条目不会被移除 ⇒ 僵尸订阅（它捕获的身份还会
 *     在每次唤醒时生效）。本用例在<b>无身份线程</b>上退订，断言退的是同一频道。</li>
 * </ol>
 */
class FrameWakeListenerIdentityTest {

	private static final String RUN_ID = "run-wake-shared";

	private static final TenantUser TENANT_A = new TenantUser(1L, 101L);

	private static final TenantUser TENANT_B = new TenantUser(2L, 202L);

	private final ChannelBus bus = new ChannelBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void twoTenantsWithTheSameRunIdSubscribeToTheirOwnChannels() {
		RecordingTarget targetA = new RecordingTarget();
		RecordingTarget targetB = new RecordingTarget();

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A.tenantId(),
				TENANT_A.userId())) {
			assertThat(this.listener.register(RUN_ID, targetA)).isTrue();
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B.tenantId(),
				TENANT_B.userId())) {
			assertThat(this.listener.register(RUN_ID, targetB)).isTrue();
		}

		assertThat(this.bus.channels()).as("两个身份必须各自订阅自己的频道")
			.containsExactlyInAnyOrder(channelOf(TENANT_A), channelOf(TENANT_B));
		assertThat(this.listener.subscribedRuns()).as("两个身份桶各一条订阅").isEqualTo(2);
		assertThat(this.listener.targetCount(RUN_ID)).as("同一 runId 的目标数按身份桶汇总").isEqualTo(2);
	}

	@Test
	void unregisterReleasesTheSameChannelEvenFromAnIdentityLessThread() {
		RecordingTarget targetA = new RecordingTarget();
		RecordingTarget targetB = new RecordingTarget();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A.tenantId(),
				TENANT_A.userId())) {
			this.listener.register(RUN_ID, targetA);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B.tenantId(),
				TENANT_B.userId())) {
			this.listener.register(RUN_ID, targetB);
		}

		// 断连回调/投递池线程：没有请求身份
		assertThat(RequestContextUtils.currentOrNull()).isNull();
		this.listener.unregister(RUN_ID, targetA);

		assertThat(this.bus.channels()).as("退订必须落到 A 的那条频道上（不是 legacy 形）")
			.containsExactly(channelOf(TENANT_B));
		assertThat(this.listener.targetCount(TENANT_A, RUN_ID)).isZero();
		assertThat(this.listener.targetCount(TENANT_B, RUN_ID)).as("B 的订阅不受 A 退订影响").isEqualTo(1);
	}

	@Test
	void wakeFromOneChannelOnlyReachesThatIdentitiesTargets() {
		RecordingTarget targetA = new RecordingTarget();
		RecordingTarget targetB = new RecordingTarget();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A.tenantId(),
				TENANT_A.userId())) {
			this.listener.register(RUN_ID, targetA);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B.tenantId(),
				TENANT_B.userId())) {
			this.listener.register(RUN_ID, targetB);
		}

		this.bus.inject(channelOf(TENANT_A), RUN_ID, 7L);

		assertThat(targetA.wakes).containsExactly(7L);
		assertThat(targetB.wakes).as("A 的唤醒不得扇到 B 的目标上").isEmpty();
	}

	private static String channelOf(TenantUser identity) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(identity.tenantId(),
				identity.userId())) {
			return RedisFrameWakeBus.channel(RUN_ID);
		}
	}

	/** 按<b>频道名</b>键控的总线替身（频道名由当前线程身份算出，与生产实现同一拼法）。 */
	private static final class ChannelBus implements FrameWakeBus {

		private final Map<String, LongConsumer> subscribers = new ConcurrentHashMap<>();

		private final List<String> subscribeJournal = new CopyOnWriteArrayList<>();

		@Override
		public void publish(String runId, long upperBoundSeq) {
			LongConsumer listener = this.subscribers.get(RedisFrameWakeBus.channel(runId));
			if (listener != null) {
				listener.accept(upperBoundSeq);
			}
		}

		@Override
		public boolean subscribe(String runId, LongConsumer onWake) {
			String channel = RedisFrameWakeBus.channel(runId);
			this.subscribeJournal.add(channel);
			this.subscribers.put(channel, onWake);
			return true;
		}

		@Override
		public void unsubscribe(String runId) {
			this.subscribers.remove(RedisFrameWakeBus.channel(runId));
		}

		void inject(String channel, String runId, long upperBoundSeq) {
			LongConsumer listener = this.subscribers.get(channel);
			assertThat(listener).as("频道 %s 应有订阅者（实际 %s）", channel, this.subscribers.keySet()).isNotNull();
			listener.accept(upperBoundSeq);
		}

		List<String> channels() {
			return List.copyOf(this.subscribers.keySet());
		}

	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		private final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
