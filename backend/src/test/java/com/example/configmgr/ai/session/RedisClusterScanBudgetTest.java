package com.example.configmgr.ai.session;

import com.example.configmgr.common.AiStateUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.ScanOptions;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class RedisClusterScanBudgetTest {

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private void pending(String sessionId) {
		this.fake.strings.put(SessionPurgePendingStore.PENDING_PREFIX + sessionId, sessionId);
	}

	private List<String> scan(RedisClusterScanSupport.ScanBudget budget) {
		return RedisClusterScanSupport.scan(this.fake.redis,
				ScanOptions.scanOptions().match(SessionPurgePendingStore.PENDING_PREFIX + "*").count(256).build(), budget);
	}

	@Test
	void cursorAdvanceOverflowFailsInsteadOfReturningPartialResult() {
		pending("s-1");
		pending("s-2");

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 1, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.standaloneCursorCloses).isEqualTo(1);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(1);
	}

	@Test
	void zeroMatchLargeKeyspaceAlsoFailsWhenWorkBudgetIsExceeded() {
		this.fake.strings.put("other:large-keyspace", "x");
		this.fake.beforeHasNext = () -> LockSupport.parkNanos(3_000_000_000L);

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 10, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.hasNextCalls).hasValue(1);
		assertThat(this.fake.standaloneCursorCloses).isEqualTo(1);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(1);
	}

	@Test
	void exactMaxKeysWithOnlyDuplicateResultsOnSameNodeIsAccepted() {
		pending("s-1");
		this.fake.standaloneScanDuplicates.add(SessionPurgePendingStore.PENDING_PREFIX + "s-1");

		List<String> scanned = scan(RedisClusterScanSupport.ScanBudget.of(1, 10, Duration.ofSeconds(2)));

		assertThat(scanned).containsExactly(SessionPurgePendingStore.PENDING_PREFIX + "s-1");
		assertThat(this.fake.hasNextCalls).hasValue(3);
		assertThat(this.fake.standaloneCursorCloses).isEqualTo(1);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(1);
	}

	@Test
	void exactMaxKeysWithOnlyDuplicateResultsAcrossMastersIsAccepted() {
		this.fake.clusterMode = true;
		RedisClusterNode first = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode second = this.fake.addClusterMaster("10.0.0.2", 7001);
		this.fake.assignToMaster(first, SessionPurgePendingStore.PENDING_PREFIX + "s-1");
		this.fake.assignToMaster(second, SessionPurgePendingStore.PENDING_PREFIX + "s-1");

		List<String> scanned = scan(RedisClusterScanSupport.ScanBudget.of(1, 10, Duration.ofSeconds(2)));

		assertThat(scanned).containsExactly(SessionPurgePendingStore.PENDING_PREFIX + "s-1");
		assertThat(this.fake.closedNodeCursors).containsExactlyInAnyOrder(first, second);
		assertThat(this.fake.clusterConnectionCloses).hasValue(1);
	}

	@Test
	void standaloneConnectionTimeoutRejectsNullCursor() {
		doAnswer(invocation -> {
			LockSupport.parkNanos(3_000_000_000L);
			return this.fake.standaloneConnection;
		}).when(this.fake.connectionFactory).getConnection();
		when(this.fake.keyCommands.scan(any(ScanOptions.class))).thenReturn(null);

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 10, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(1);
	}

	@Test
	void standaloneScanTimeoutRejectsEmptyCursor() {
		doAnswer(invocation -> {
			LockSupport.parkNanos(3_000_000_000L);
			return null;
		}).when(this.fake.keyCommands).scan(any(ScanOptions.class));

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 10, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(1);
	}

	@Test
	void clusterTopologyTimeoutRejectsBeforeAnyMasterScan() {
		this.fake.clusterMode = true;
		this.fake.addClusterMaster("10.0.0.1", 7001);
		AtomicInteger scanCalls = new AtomicInteger();
		doAnswer(invocation -> {
			LockSupport.parkNanos(3_000_000_000L);
			return this.fake.clusterMasters;
		}).when(this.fake.clusterConnection).clusterGetNodes();
		doAnswer(invocation -> {
			scanCalls.incrementAndGet();
			return null;
		}).when(this.fake.clusterConnection).scan(any(RedisClusterNode.class), any(ScanOptions.class));

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 10, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(scanCalls).hasValue(0);
		assertThat(this.fake.clusterConnectionCloses).hasValue(1);
	}

	@Test
	void allMasterNullCursorsCannotBypassDeadline() {
		this.fake.clusterMode = true;
		this.fake.addClusterMaster("10.0.0.1", 7001);
		this.fake.addClusterMaster("10.0.0.2", 7001);
		doAnswer(invocation -> {
			LockSupport.parkNanos(3_000_000_000L);
			return null;
		}).when(this.fake.clusterConnection).scan(any(RedisClusterNode.class), any(ScanOptions.class));

		assertThatThrownBy(() -> scan(RedisClusterScanSupport.ScanBudget.of(10, 10, Duration.ofSeconds(2))))
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.clusterConnectionCloses).hasValue(1);
	}

}
