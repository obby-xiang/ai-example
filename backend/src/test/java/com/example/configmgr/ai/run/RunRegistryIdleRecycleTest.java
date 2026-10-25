package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>park 批：无订阅写出器不冒充"活轮"，且条目可回收</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code RunRegistry.of(runId)} 每次取用都会<b>新建</b>一个写出器条目；请求帧发出后按 B1 收流、
 * 订阅归零，但条目一直留在表里。旧判据"表里有条目 = 本进程还有活连接"于是永远为真 ——
 * 会话门因此永久 409、僵尸判据永远判活（park 的轮连心跳都没有了，这个假象更致命）。
 *
 * <p>本类钉两件事：{@link RunRegistry#hasLiveSubscriber} 只认<b>真实订阅</b>；
 * {@link RunRegistry#recycleIfIdle} 只回收无订阅的条目（有连接的条目不能动）。
 */
class RunRegistryIdleRecycleTest {

	private static final String RUN_ID = "run-idle-recycle";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, new ObjectMapper(), null);

	@Test
	void registryEntryAloneIsNotALiveSubscriber() {
		assertThat(this.registry.of(RUN_ID)).isNotNull();

		assertThat(this.registry.hasLiveSubscriber(RUN_ID))
			.as("写出器条目存在 ≠ 有活连接（B1 收流后条目仍在）").isFalse();
		assertThat(this.registry.recycleIfIdle(RUN_ID)).as("无订阅 ⇒ 可回收").isTrue();
		assertThat(this.registry.find(RUN_ID)).isNull();
		assertThat(this.registry.recycleIfIdle(RUN_ID)).as("幂等").isFalse();
	}

	@Test
	void subscribedEmitterIsLiveAndNeverRecycled() {
		SseChatEmitter out = this.registry.of(RUN_ID);
		out.attach(mock(SseEmitter.class));

		assertThat(this.registry.hasLiveSubscriber(RUN_ID)).isTrue();
		assertThat(this.registry.recycleIfIdle(RUN_ID)).as("有活连接时不得回收").isFalse();
		assertThat(this.registry.find(RUN_ID)).isSameAs(out);
	}

}
