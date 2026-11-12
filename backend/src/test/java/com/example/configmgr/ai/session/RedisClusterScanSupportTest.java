package com.example.configmgr.ai.session;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.ScanOptions;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisClusterScanSupportTest {

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private void pending(String sessionId) {
		this.fake.strings.put(SessionPurgePendingStore.PENDING_PREFIX + sessionId,
				"{\"sessionId\":\"" + sessionId + "\",\"purgeId\":\"p-" + sessionId
						+ "\",\"requestedBy\":\"t\",\"attempts\":0,\"nextAtMs\":0,\"createdAtMs\":0}");
	}

	private List<String> scanPending(int maxKeys) {
		return RedisClusterScanSupport.scan(this.fake.redis,
				ScanOptions.scanOptions().match(SessionPurgePendingStore.PENDING_PREFIX + "*").count(256).build(),
				maxKeys);
	}

	@Test
	void clusterScansEveryMasterAndClosesEachNodeCursor() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		pending("s-1");
		pending("s-2");
		this.fake.assignToMaster(m1, SessionPurgePendingStore.PENDING_PREFIX + "s-1",
				"ai:ctx:archive:b:1");
		this.fake.assignToMaster(m2, SessionPurgePendingStore.PENDING_PREFIX + "s-2",
				"ai:ctx:archive:b:2");

		List<String> scanned = scanPending(0);

		assertThat(scanned).containsExactlyInAnyOrder(
				SessionPurgePendingStore.PENDING_PREFIX + "s-1",
				SessionPurgePendingStore.PENDING_PREFIX + "s-2");
		assertThat(this.fake.closedNodeCursors).as("每个 master 的 cursor 都要关闭").containsExactlyInAnyOrder(m1, m2);
		assertThat(this.fake.clusterConnectionCloses).as("cluster 连接关闭一次").hasValue(1);
		assertThat(this.fake.standaloneConnectionCloses).hasValue(0);
		verify(this.fake.redis, never()).scan(any(ScanOptions.class));
	}

	@Test
	void standaloneScansSingleConnectionAndClosesIt() {
		pending("s-1");
		pending("s-2");

		List<String> scanned = scanPending(0);

		assertThat(scanned).containsExactlyInAnyOrder(
				SessionPurgePendingStore.PENDING_PREFIX + "s-1",
				SessionPurgePendingStore.PENDING_PREFIX + "s-2");
		assertThat(this.fake.standaloneConnectionCloses).as("standalone 连接关闭一次").hasValue(1);
		assertThat(this.fake.standaloneCursorCloses).as("standalone cursor 关闭一次").isEqualTo(1);
		assertThat(this.fake.clusterConnectionCloses).hasValue(0);
		verify(this.fake.redis, never()).scan(any(ScanOptions.class));
	}

	@Test
	void oneMasterFailurePropagatesAndNoPartialResultIsReturned() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		pending("s-1");
		pending("s-2");
		this.fake.assignToMaster(m1, SessionPurgePendingStore.PENDING_PREFIX + "s-1");
		this.fake.assignToMaster(m2, SessionPurgePendingStore.PENDING_PREFIX + "s-2");
		this.fake.failNode = m2;

		assertThatThrownBy(() -> scanPending(0))
			.as("某 master 扫描失败必须上抛，不能用部分结果冒充完整")
			.isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.closedNodeCursors).as("已扫完的节点照常关闭，失败节点不产出 cursor")
			.containsExactly(m1);
		assertThat(this.fake.clusterConnectionCloses).as("失败路径也要释放 cluster 连接").hasValue(1);
	}

	@Test
	void resultOverflowFailsInsteadOfReturningPartialResult() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		pending("s-1");
		pending("s-2");
		this.fake.assignToMaster(m1, SessionPurgePendingStore.PENDING_PREFIX + "s-1");
		this.fake.assignToMaster(m2, SessionPurgePendingStore.PENDING_PREFIX + "s-2");

		assertThatThrownBy(() -> scanPending(1)).isInstanceOf(AiStateUnavailableException.class);
		assertThat(this.fake.closedNodeCursors).containsExactly(m1, m2);
		assertThat(this.fake.clusterConnectionCloses).hasValue(1);
	}

	@Test
	void clusterArchiveScanReturnsKeysFromEveryMaster() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		this.fake.strings.put("ai:ctx:archive:b:1", "[]");
		this.fake.strings.put("ai:ctx:archive:b:2", "[]");
		this.fake.assignToMaster(m1, "ai:ctx:archive:b:1");
		this.fake.assignToMaster(m2, "ai:ctx:archive:b:2");

		List<String> scanned = RedisClusterScanSupport.scan(this.fake.redis,
				ScanOptions.scanOptions().match("ai:ctx:archive:b:*").count(256).build(), 0);

		assertThat(scanned).containsExactlyInAnyOrder("ai:ctx:archive:b:1", "ai:ctx:archive:b:2");
		assertThat(this.fake.closedNodeCursors).containsExactlyInAnyOrder(m1, m2);
	}

	@Test
	void archivePatternIsStillPerSessionAndEscapesGlobCharacters() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		this.fake.strings.put("ai:ctx:archive:a*b:1", "[]");
		this.fake.strings.put("ai:ctx:archive:axb:1", "[]");
		this.fake.assignToMaster(m1, "ai:ctx:archive:a*b:1");
		this.fake.assignToMaster(m2, "ai:ctx:archive:axb:1");

		List<String> scanned = RedisClusterScanSupport.scan(this.fake.redis,
				ScanOptions.scanOptions().match("ai:ctx:archive:a\\*b:*").count(256).build(), 0);

		assertThat(scanned).containsExactly("ai:ctx:archive:a*b:1");
		assertThat(this.fake.closedNodeCursors).containsExactlyInAnyOrder(m1, m2);
	}

	@Test
	void conversationEnumerationKeepsFilteringUnescapeAndOrdering() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		this.fake.assignToMaster(m1, "chat:mem:1:101:{z}");
		this.fake.assignToMaster(m2, "chat:mem:1:101:{%7Ba%7Db}", "chat:mem:staging:1:101:{ignored}:token",
				"chat:mem:2:202:{other}");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
					new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(1));

			assertThat(repository.findConversationIds()).containsExactly("z", "{a}b");
		}
		assertThat(this.fake.closedNodeCursors).containsExactlyInAnyOrder(m1, m2);
	}

	@Test
	void conversationEnumerationDoesNotReturnIdsFromFailedMaster() {
		this.fake.clusterMode = true;
		RedisClusterNode m1 = this.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fake.addClusterMaster("10.0.0.2", 7001);
		this.fake.assignToMaster(m1, "chat:mem:1:101:{first}");
		this.fake.assignToMaster(m2, "chat:mem:1:101:{second}");
		this.fake.failNode = m2;

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
					new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(1));

			assertThatThrownBy(repository::findConversationIds).isInstanceOf(AiStateUnavailableException.class);
		}
		assertThat(this.fake.closedNodeCursors).containsExactly(m1);
		assertThat(this.fake.clusterConnectionCloses).hasValue(1);
	}

}
