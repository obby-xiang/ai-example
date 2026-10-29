package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行快照的身份归属（additive 字段）：首轮创建时从 {@link RequestContextUtils} <b>固定</b>
 * {@code tenantId/userId}，随快照持久化，续跑只读。
 *
 * <p>
 * 为什么必须进快照：续跑/恢复/跨实例接管都发生在原 HTTP 请求早已结束之后，唯一还能证身份的地方
 * 就是这条记录本身。没有它，恢复只能猜默认身份或扫描租户目录。
 */
class RunSnapshotTenantIdentityTest {

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	@Test
	void createFixesTheTenantAndUserOfTheTurnFromTheContext() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			RunSnapshot created = this.store.create("run-tenant", "s-tenant", AiContext.empty(),
					List.of(new UserMessage("你好")), this.codec);
			RunSnapshot persisted = this.store.get("run-tenant");

			assertThat(created.getTenantId()).isEqualTo(1L);
			assertThat(created.getUserId()).isEqualTo(101L);
			assertThat(persisted.getTenantId()).as("身份必须随快照持久化").isEqualTo(1L);
			assertThat(persisted.getUserId()).isEqualTo(101L);
			assertThat(created.asMap()).containsEntry("tenantId", 1L).containsEntry("userId", 101L);
		}
	}

	@Test
	void realStoreWritesTheIdentityIntoThePersistedSnapshotJson() {
		FakeLeaseRedis fake = new FakeLeaseRedis();
		ObjectMapper mapper = new ObjectMapper();
		RunStore realStore = new RunStore(fake.redis, mapper, new AiProperties());

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 202L)) {
			realStore.create("run-real-tenant", "s-real-tenant", AiContext.empty(),
					List.of(new UserMessage("你好")), new MessageJsonCodec(mapper));
		}

		RunSnapshot persisted = realStore.get("run-real-tenant");
		assertThat(persisted.getTenantId()).as("真实 RunStore 的 JSON 持久化必须带上租户").isEqualTo(2L);
		assertThat(persisted.getUserId()).isEqualTo(202L);
	}

}
