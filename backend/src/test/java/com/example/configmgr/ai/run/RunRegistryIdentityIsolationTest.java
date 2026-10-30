package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>跨身份同 runId 的写出器条目不得混在一桶</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code RunRegistry.emitters}（旧实现 {@code runId → SseChatEmitter}）用<b>裸 runId</b> 定键：
 * 两个租户各自生成同形 runId 是合法且必然的（runId 不是跨租户唯一性证明），于是 A、B 会拿到
 * <b>同一个</b>写出器 —— A 的帧写进 B 的归档、A 的 {@code close} 把 B 的连接一起收掉、
 * A 的订阅让 B 也被判成"本进程有活连接"。
 *
 * <p>本类钉住：本地键 = <b>身份 + runId</b>（无身份 = legacy，独立命名空间）。
 */
class RunRegistryIdentityIsolationTest {

	private static final String RUN_ID = "run-shared-emitter";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	@Test
	void sameRunIdUnderDifferentIdentitiesGetsDistinctEmitters() {
		SseChatEmitter emitterA = as(A, () -> this.registry.of(RUN_ID));
		SseChatEmitter emitterB = as(B, () -> this.registry.of(RUN_ID));

		assertThat(emitterA).as("同 runId 不同身份必须是两个写出器（否则帧与簿记会串桶）")
			.isNotSameAs(emitterB);

		emitterA.attach(mock(SseEmitter.class));

		assertThat(as(A, () -> this.registry.find(RUN_ID))).as("A 取到自己的条目").isSameAs(emitterA);
		assertThat(as(B, () -> this.registry.find(RUN_ID))).as("B 取到自己的条目").isSameAs(emitterB);
		assertThat(as(A, () -> this.registry.hasLiveSubscriber(RUN_ID))).as("A 有活连接").isTrue();
		assertThat(as(B, () -> this.registry.hasLiveSubscriber(RUN_ID))).as("B 的活连接判定不得被 A 订阅冒充")
			.isFalse();
	}

	@Test
	void closingOneIdentityLeavesTheOtherIdentitysEmitterAndSubscriberIntact() {
		SseChatEmitter emitterA = as(A, () -> this.registry.of(RUN_ID));
		SseChatEmitter emitterB = as(B, () -> this.registry.of(RUN_ID));
		emitterA.attach(mock(SseEmitter.class));
		emitterB.attach(mock(SseEmitter.class));

		as(A, () -> {
			this.registry.close(RUN_ID);
			return null;
		});

		assertThat(as(A, () -> this.registry.find(RUN_ID))).as("A 的终态出口摘掉的是 A 的条目").isNull();
		assertThat(emitterA.subscriberCount()).as("A 的连接随 A 的终态收流").isZero();
		assertThat(as(B, () -> this.registry.find(RUN_ID))).as("B 的条目不得被 A 的 close 摘掉")
			.isSameAs(emitterB);
		assertThat(emitterB.subscriberCount()).as("B 的连接不受 A 终态影响").isEqualTo(1);
		assertThat(as(B, () -> this.registry.hasLiveSubscriber(RUN_ID))).as("B 仍被判活").isTrue();
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
