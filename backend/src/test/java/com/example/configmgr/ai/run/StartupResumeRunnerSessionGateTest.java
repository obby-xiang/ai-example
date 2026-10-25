package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>启动扫描也必须过会话门</b>（续跑/启动/死卡三条入口同一口径的第 2 条）。
 *
 * <p>{@link StartupResumeRunner} 不自己取门：它把每一轮交给 {@link ResumeService}，由后者在
 * "写 RUNNING / 交棒"<b>之前</b> acquire。本用例锁的是这个漏斗真的成立 —— 会话被另一轮占着时，
 * 启动扫描必须报 {@code SESSION_BUSY}、快照一字节不动、驱动执行器零调用，且不得把别人的门释放掉。
 */
class StartupResumeRunnerSessionGateTest {

	private static final String RUN_ID = "run-startup-gate";

	private static final String OTHER_RUN = "run-startup-other";

	private static final String SESSION_ID = "s-startup-gate";

	private final Map<String, String> locks = new ConcurrentHashMap<>();

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = gate();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, mock(RunRegistry.class), this.codec,
			this.chatService, mock(SpToolCallingManager.class), this.executor, mock(StringRedisTemplate.class),
			this.properties, this.cancellations, this.limiter);

	private final AiAvailability availability = mock(AiAvailability.class);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(this.service, "sessionGate", this.gate);
		when(this.availability.isAvailable()).thenReturn(true);
		this.properties.getResume().setOnStartup(true);
		when(this.chatService.systemPromptText(any(AiContext.class))).thenReturn("SYSTEM");
	}

	@Test
	void startupScanReportsSessionBusyWithoutTouchingTheSnapshot() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		snapshot.setUpdatedAtMs(777L);
		assertThat(this.gate.acquire(SESSION_ID, OTHER_RUN)).isNull();

		Map<String, Object> report = new StartupResumeRunner(this.service, this.store, this.properties,
				this.availability).scan(true);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> resumed = (List<Map<String, Object>>) report.get("resumed");
		assertThat(resumed).hasSize(1);
		assertThat(resumed.get(0)).containsEntry("outcome", "SESSION_BUSY");
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getUpdatedAtMs()).as("SESSION_BUSY 不得写快照").isEqualTo(777L);
		verify(this.executor, never()).execute(any(Runnable.class));
		assertThat(this.gate.currentRunId(SESSION_ID)).as("不得误释放别的轮的门").isEqualTo(OTHER_RUN);
	}

	@SuppressWarnings("unchecked")
	private SessionGate gate() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			synchronized (this.locks) {
				if (this.locks.containsKey(key)) {
					return false;
				}
				this.locks.put(key, invocation.getArgument(1));
				return true;
			}
		});
		when(values.get(anyString())).thenAnswer(invocation -> this.locks.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			synchronized (this.locks) {
				this.locks.put(invocation.getArgument(0), invocation.getArgument(1));
			}
			return null;
		}).when(values).set(anyString(), anyString(), any(Duration.class));
		when(redis.delete(anyString())).thenAnswer(invocation -> this.locks.remove(invocation.getArgument(0)) != null);
		when(redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation ->
			this.locks.containsKey(invocation.getArgument(0)));
		return new SessionGate(redis, this.properties);
	}

}
