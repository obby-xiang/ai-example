package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>续跑侧的压缩级别交接（{@code compactionLevels}）按「身份 + runId」分条</b>。
 *
 * <h2>缺陷形态</h2>
 * 旧表用裸 runId：A 的续跑压了 {@code L2}、把级别交接给驱动侧，而 B 的同名轮次先被驱动 ——
 * {@code done.context.level} 于是对 B 说"这一轮压过 L2"（一次没做过的压缩），
 * 上下文分项与真实历史分家。
 *
 * <p>本类钉住：交接只在本身份内可见、只被本身份消费/丢弃。
 */
class ResilientChatServiceCompactionHandoffIdentityTest {

	private static final String RUN_ID = "run-shared-compaction";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class),
			mock(ChatMemory.class), new MessageJsonCodec(new ObjectMapper()),
			new com.example.configmgr.ai.conformance.InMemoryRunStore(), mock(RunRegistry.class),
			mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
			mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), watchdog());

	@Test
	void compactionHandoffIsScopedToIdentityPlusRunId() {
		as(A, () -> {
			this.service.noteCompaction(RUN_ID, "L2");
			return null;
		});

		assertThat(as(A, () -> this.service.pendingCompactionLevel(RUN_ID))).as("A 的交接仍在 A 名下")
			.isEqualTo("L2");
		assertThat(as(B, () -> this.service.pendingCompactionLevel(RUN_ID)))
			.as("B 的同名轮次不得消费 A 的压缩级别交接")
			.isNull();

		as(B, () -> {
			this.service.clearCompaction(RUN_ID);
			return null;
		});
		assertThat(as(A, () -> this.service.pendingCompactionLevel(RUN_ID))).as("B 的丢弃不得清掉 A 的交接")
			.isEqualTo("L2");

		as(A, () -> {
			this.service.clearCompaction(RUN_ID);
			return null;
		});
		assertThat(as(A, () -> this.service.pendingCompactionLevel(RUN_ID))).isNull();
	}

	private static ScheduledExecutorService watchdog() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "compaction-handoff-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		return executor;
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
