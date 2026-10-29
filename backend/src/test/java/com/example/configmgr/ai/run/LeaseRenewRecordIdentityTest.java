package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>续租判据必须按记录身份执行</b>（真实 {@link RunStore} + 真实 {@link LeaseService} + 真实键形）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code markDriving(lease, alive)} 的判据（"这一轮还需要我活着吗"）跑在 {@link LeaseService} 自建的
 * <b>裸单线程续租调度器</b>上，那里没有请求身份；而判据读的快照/租约键<b>由身份拼出</b>
 * （{@code ai:run:{<t>:<u>:run:<id>}}）。因此：
 * <ul>
 * <li>判据在续租线程上用线程身份读 ⇒ 恒命中 legacy 键 ⇒ 恒 {@code null} ⇒ 判"驱动者已不在"；</li>
 * <li>结果 = <b>第一拍就主动还租</b> + 停续租 ⇒ fence 不再推进，另一实例可接管 ⇒ <b>双驱动窗口</b>。</li>
 * </ul>
 *
 * <p>修法：登记续租时把<b>记录身份</b>随任务携带，每拍在它的作用域里执行判据（本用例断言的是
 * "判据在那一拍读到了这一轮"，不是"某个 lambda 被调用过"）。
 */
class LeaseRenewRecordIdentityTest {

	private static final String RUN_ID = "run-renew-identity";

	private static final String SESSION_ID = "s-renew-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(1L, 101L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final FakeRenewScheduler renewScheduler = new FakeRenewScheduler();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties, this.renewScheduler,
			"inst-a");

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties, this.leases);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void renewalKeepsTheLeaseWhenThePredicateRunsOnAnIdentityLessRenewThread() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		}
		LeaseService.Lease lease;
		AtomicReference<TenantUser> predicateSaw = new AtomicReference<>();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			lease = this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
			// 与生产同形的判据：本进程仍登记着驱动 + 快照非终态（快照按“这一拍的身份”读）
			this.leases.markDriving(lease, () -> {
				predicateSaw.compareAndSet(null, RequestContextUtils.currentOrNull());
				RunSnapshot snapshot = this.store.get(RUN_ID);
				return snapshot != null && !RunSnapshot.isTerminal(snapshot.getStatus());
			});
		}

		assertThat(RequestContextUtils.currentOrNull()).as("前置：续租线程模拟自建调度线程，无身份").isNull();

		assertThat(this.renewScheduler.tick()).as("续租任务应在这一拍执行").isEqualTo(1);

		assertThat(predicateSaw.get()).as("判据必须在<b>记录身份</b>下执行（否则读不到带身份的轮次）")
			.isEqualTo(RECORD_IDENTITY);
		assertThat(this.fake.renewCalls.get()).as("判据为真 ⇒ 必须真续租（不是首拍还租）").isEqualTo(1);
		assertThat(this.fake.string(lease.key())).as("租约必须仍被持有").isNotNull();
		assertThat(this.renewScheduler.activeCount()).as("续租任务必须继续存活").isEqualTo(1);
		assertThat(RequestContextUtils.currentOrNull()).as("续租拍结束不得在调度线程残留身份").isNull();
	}

	@Test
	void renewalStopsWhenTheRecordIsTerminal() {
		// 相邻对照：判据为假（轮已收尾）时仍必须停续租 + 还租 —— 修复不得把僵尸续租一起放过。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		}
		LeaseService.Lease lease;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			lease = this.store.tryAcquireRunLease(RUN_ID).orElseThrow();
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.DONE);
			this.store.save(snapshot);
			this.leases.markDriving(lease, () -> {
				RunSnapshot current = this.store.get(RUN_ID);
				return current != null && !RunSnapshot.isTerminal(current.getStatus());
			});
		}

		assertThat(this.renewScheduler.tick()).isEqualTo(1);

		assertThat(this.fake.renewCalls.get()).as("终态轮不得再续租").isZero();
		assertThat(this.fake.string(lease.key())).as("判据为假 ⇒ 归还租约").isNull();
		assertThat(this.renewScheduler.activeCount()).as("判据为假 ⇒ 停续租").isZero();
	}

}
