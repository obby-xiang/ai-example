package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunStoreTerminalCancellationTest {

	private static final String PLAIN = "w30-cas-plain";
	private static final String FENCED = "w30-cas-fenced";
	private static final String ORDINARY = "w30-cas-ordinary";
	private static final String FLAGGED = "w30-cas-flagged";
	private static final String FENCED_OK = "w30-cas-fenced-ok";
	private static final String FENCED_FLAGGED = "w30-cas-fenced-flagged";

	private static final String REDIS_HOST = System.getProperty("test.redis.host", "127.0.0.1");
	private static final int REDIS_PORT = Integer.parseInt(System.getProperty("test.redis.port", "6379"));
	private static final int REDIS_DB = Integer.parseInt(System.getProperty("test.redis.db", "14"));

	private final ObjectMapper mapper = new ObjectMapper();
	private final AiProperties properties = new AiProperties();

	private LettuceConnectionFactory factory;
	private StringRedisTemplate redis;
	private RunStore plainStore;
	private RunStore fencedStore;
	private LeaseService leases;
	private LeaseService.Lease held;

	@BeforeEach
	void setUp() {
		RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
		configuration.setDatabase(REDIS_DB);
		this.factory = new LettuceConnectionFactory(configuration);
		this.factory.afterPropertiesSet();
		this.redis = new StringRedisTemplate(this.factory);
		this.plainStore = new RunStore(this.redis, this.mapper, this.properties);
		this.leases = new LeaseService(this.redis, this.properties, new FakeRenewScheduler(), "w30-cas-test");
		this.fencedStore = new RunStore(this.redis, this.mapper, this.properties, this.leases);
		for (String runId : List.of(PLAIN, FENCED, ORDINARY, FLAGGED, FENCED_OK, FENCED_FLAGGED)) {
			this.fencedStore.deleteRun(runId);
		}
	}

	@AfterEach
	void tearDown() {
		if (this.held != null) {
			this.leases.release(this.held);
		}
		for (String runId : List.of(PLAIN, FENCED, ORDINARY, FLAGGED, FENCED_OK, FENCED_FLAGGED)) {
			this.fencedStore.deleteRun(runId);
		}
		this.factory.destroy();
	}

	@Test
	void plainDoneCannotOverwriteCancelled() {
		this.plainStore.save(snapshot(PLAIN, RunSnapshot.CANCELLED, true));
		this.plainStore.save(snapshot(PLAIN, RunSnapshot.DONE, false));

		assertThat(this.plainStore.get(PLAIN).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(this.plainStore.get(PLAIN).isCancelled()).isTrue();
	}

	@Test
	void fencedDoneCannotOverwriteCancelled() throws Exception {
		this.held = this.fencedStore.tryAcquireRunLease(FENCED).orElseThrow();
		this.fencedStore.save(snapshot(FENCED, RunSnapshot.RUNNING, false));
		// 证明本用例确实走持租分支（fenced）：authority=HELD 且 epoch>0 ⇒ save 选 FENCED_TERMINAL_SET 脚本
		assertThat(this.fencedStore.runLeaseAuthority(FENCED)).isEqualTo(LeaseService.LeaseAuthority.HELD);
		assertThat(this.fencedStore.heldRunEpoch(FENCED)).isGreaterThan(0L);
		this.redis.opsForValue().set(RunStore.runScopedKey(RunStore.RUN_PREFIX, FENCED),
				this.mapper.writeValueAsString(snapshot(FENCED, RunSnapshot.CANCELLED, true)));
		this.fencedStore.save(snapshot(FENCED, RunSnapshot.DONE, false));

		assertThat(this.fencedStore.get(FENCED).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(this.fencedStore.get(FENCED).isCancelled()).isTrue();
	}

	/**
	 * 脚本里 {@code cancelled == true} 那一支也要单独可证：状态还不是 CANCELLED、但取消位已置位的快照
	 * （终态写入与取消位分开落库时的中间形态）同样不得被 DONE 覆盖。
	 */
	@Test
	void plainDoneRefusedWhenCancelledFlagIsSetWithoutCancelledStatus() {
		this.plainStore.save(snapshot(FLAGGED, RunSnapshot.RUNNING, true));
		this.plainStore.save(snapshot(FLAGGED, RunSnapshot.DONE, false));

		assertThat(this.plainStore.get(FLAGGED).getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(this.plainStore.get(FLAGGED).isCancelled()).isTrue();
	}

	/**
	 * 持租分支的成功路径也要有真实 Redis 证据：RUNNING → DONE 必须真的落库，且 DONE 的
	 * {@code runEpoch} 与 fence 都是本次租约 epoch（证明走的是 {@code FENCED_TERMINAL_SET} 脚本，
	 * 不是绕道 plain）。
	 */
	@Test
	void fencedRunningToDoneSucceedsUnderARealLease() {
		this.held = this.fencedStore.tryAcquireRunLease(FENCED_OK).orElseThrow();
		this.fencedStore.save(snapshot(FENCED_OK, RunSnapshot.RUNNING, false));
		assertThat(this.fencedStore.runLeaseAuthority(FENCED_OK)).isEqualTo(LeaseService.LeaseAuthority.HELD);
		assertThat(this.fencedStore.heldRunEpoch(FENCED_OK)).isEqualTo(this.held.epoch());

		this.fencedStore.save(snapshot(FENCED_OK, RunSnapshot.DONE, false));

		RunSnapshot done = this.fencedStore.get(FENCED_OK);
		assertThat(done.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(done.isCancelled()).isFalse();
		assertThat(done.getRunEpoch()).as("DONE 由持租分支写入：快照 epoch = 租约 epoch")
			.isEqualTo(this.held.epoch());
		assertThat(this.fencedStore.fenceEpoch(FENCED_OK)).as("fence 推进到本次 epoch")
			.isEqualTo(this.held.epoch());
	}

	/**
	 * 持租分支的 {@code cancelled == true} 支也要单独可证：状态还不是 CANCELLED、但取消位已置位的快照
	 * 同样不得被 DONE 覆盖。
	 */
	@Test
	void fencedDoneRefusedWhenCancelledFlagIsSetWithoutCancelledStatus() {
		this.held = this.fencedStore.tryAcquireRunLease(FENCED_FLAGGED).orElseThrow();
		this.fencedStore.save(snapshot(FENCED_FLAGGED, RunSnapshot.RUNNING, false));
		assertThat(this.fencedStore.runLeaseAuthority(FENCED_FLAGGED)).isEqualTo(LeaseService.LeaseAuthority.HELD);
		this.fencedStore.save(snapshot(FENCED_FLAGGED, RunSnapshot.RUNNING, true));
		this.fencedStore.save(snapshot(FENCED_FLAGGED, RunSnapshot.DONE, false));

		RunSnapshot after = this.fencedStore.get(FENCED_FLAGGED);
		assertThat(after.getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(after.isCancelled()).isTrue();
	}

	@Test
	void ordinaryDoneStillWrites() {
		this.plainStore.save(snapshot(ORDINARY, RunSnapshot.RUNNING, false));
		this.plainStore.save(snapshot(ORDINARY, RunSnapshot.DONE, false));

		assertThat(this.plainStore.get(ORDINARY).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(this.plainStore.get(ORDINARY).isCancelled()).isFalse();
	}

	private static RunSnapshot snapshot(String runId, String status, boolean cancelled) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId("session-" + runId);
		snapshot.setStatus(status);
		snapshot.setCancelled(cancelled);
		snapshot.setMessageJson(List.of());
		return snapshot;
	}
}
