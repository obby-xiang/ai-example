package com.example.configmgr.ai.run;

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
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 续跑 / 启动扫描 / 死卡兜底的<b>会话门</b>（park 批的收口）。
 *
 * <p>三条口径：
 * <ol>
 * <li><b>SESSION_BUSY 不写 RUNNING、不派发</b>：会话门在快照写与交棒<b>之前</b>取；取不到就
 *     原样返回（快照的 status/updatedAtMs 一个字节都不动），派发执行器调用次数为 0；</li>
 * <li><b>释放按 owner token</b>：续跑用一次性 token 取门，首轮那条迟到的
 *     {@code finally}（token = runId）不得删掉续跑方的锁、也不得停它的 watchdog；</li>
 * <li><b>续期按 owner token</b>：旧 token 的续期期望值不等于锁现值 ⇒ 拒绝续（不替新持有者续命）。</li>
 * </ol>
 *
 * <p>启动扫描（{@code StartupResumeRunner}）与死卡兜底（{@code forceTakeover}）都汇入
 * {@link ResumeService#resumeWithReason(String, boolean, String)}，因此同一组断言覆盖三条入口。
 */
class ResumeSessionGateTest {

	private static final String RUN_ID = "run-gate-resume";

	private static final String OTHER_RUN = "run-gate-other";

	private static final String SESSION_ID = "s-gate-resume";

	private final Map<String, String> locks = new ConcurrentHashMap<>();

	private final Map<String, Duration> expiries = new ConcurrentHashMap<>();

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = gate();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, mock(StringRedisTemplate.class), this.properties,
			this.cancellations, this.limiter);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(this.service, "sessionGate", this.gate);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.chatService.systemPromptText(any(AiContext.class))).thenReturn("SYSTEM");
	}

	@Test
	void busySessionNeitherWritesRunningNorDispatches() {
		RunSnapshot snapshot = seedSuspended(777L);
		assertThat(this.gate.acquire(SESSION_ID, OTHER_RUN)).isNull();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.SESSION_BUSY);
		assertThat(result.body()).containsEntry("heldBy", OTHER_RUN);
		assertThat(snapshot.getStatus()).as("SESSION_BUSY ⇒ 不得把快照改成 RUNNING").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getUpdatedAtMs()).as("不得因拒绝而写快照").isEqualTo(777L);
		verify(this.executor, never()).execute(any(Runnable.class));
		assertThat(this.gate.currentRunId(SESSION_ID)).isEqualTo(OTHER_RUN);
	}

	@Test
	void deadcardTakeoverAlsoWaitsForTheSessionGate() {
		RunSnapshot snapshot = seedSuspended(777L);
		snapshot.setStatus(RunSnapshot.RUNNING);
		this.store.save(snapshot);
		snapshot.setUpdatedAtMs(777L);
		assertThat(this.gate.acquire(SESSION_ID, OTHER_RUN)).isNull();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, true);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.SESSION_BUSY);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(snapshot.getUpdatedAtMs()).as("死卡兜底同样不得先写后判").isEqualTo(777L);
		verify(this.executor, never()).execute(any(Runnable.class));
	}

	@Test
	void staleFirstTurnReleaseCannotDeleteTheResumedHoldersLock() {
		seedSuspended(777L);
		// 首轮：token = runId（旧调用点语义）
		assertThat(this.gate.acquire(SESSION_ID, RUN_ID)).isNull();
		AtomicReference<Runnable> dispatched = new AtomicReference<>();
		doAnswer(invocation -> {
			dispatched.set(invocation.getArgument(0, Runnable.class));
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(dispatched.get()).isNotNull();
		String lockKey = SessionGate.LOCK_PREFIX + SESSION_ID;
		String resumedValue = this.locks.get(lockKey);
		assertThat(resumedValue).as("续跑用自己的 token 接管同一轮的锁").contains("|");

		// 首轮那条迟到的 finally（token = runId）不许删新持有者的锁
		assertThat(this.gate.release(SESSION_ID, RUN_ID, RUN_ID)).isFalse();
		assertThat(this.locks.get(lockKey)).isEqualTo(resumedValue);
		assertThat(this.gate.currentRunId(SESSION_ID)).isEqualTo(RUN_ID);
		// 旧 token 也不许替新持有者续命
		Object renewed = ReflectionTestUtils.invokeMethod(this.gate, "renewLockOnce", SESSION_ID, RUN_ID);
		assertThat((Boolean) renewed).as("续期必须同值校验：旧 token 不是现值").isFalse();

		// 驱动任务收尾：续跑按自己的 token 释放
		dispatched.get().run();
		assertThat(this.locks).doesNotContainKey(lockKey);
		assertThat(this.gate.currentRunId(SESSION_ID)).isNull();
	}

	@Test
	void acceptedResumeReleasesTheGateAfterDispatchEvenWhenDrivingThrows() {
		seedSuspended(777L);
		AtomicReference<Runnable> dispatched = new AtomicReference<>();
		doAnswer(invocation -> {
			dispatched.set(invocation.getArgument(0, Runnable.class));
			return null;
		}).when(this.executor).execute(any(Runnable.class));
		doAnswer(invocation -> {
			throw new IllegalStateException("上游炸了");
		}).when(this.chatService).driveResumed(anyString(), anyString(), any(AiContext.class), any(List.class),
				any(SseChatEmitter.class));

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(this.gate.currentRunId(SESSION_ID)).isEqualTo(RUN_ID);

		dispatched.get().run();

		assertThat(this.gate.currentRunId(SESSION_ID)).as("异常也必须归还会话门（finally）").isNull();
	}

	private RunSnapshot seedSuspended(long updatedAtMs) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		snapshot.setUpdatedAtMs(updatedAtMs);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private SessionGate gate() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			String value = invocation.getArgument(1);
			synchronized (this.locks) {
				if (this.locks.containsKey(key)) {
					return false;
				}
				this.locks.put(key, value);
				this.expiries.put(key, invocation.getArgument(2));
				return true;
			}
		});
		when(values.get(anyString())).thenAnswer(invocation -> this.locks.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			synchronized (this.locks) {
				this.locks.put(invocation.getArgument(0), invocation.getArgument(1));
				this.expiries.put(invocation.getArgument(0), invocation.getArgument(2));
			}
			return null;
		}).when(values).set(anyString(), anyString(), any(Duration.class));
		when(redis.delete(anyString())).thenAnswer(invocation -> this.locks.remove(invocation.getArgument(0)) != null);
		when(redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			synchronized (this.locks) {
				if (!this.locks.containsKey(key)) {
					return false;
				}
				this.expiries.put(key, invocation.getArgument(1));
				return true;
			}
		});
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
			RedisScript<?> script = invocation.getArgument(0);
			String body = script.getScriptAsString();
			List<Object> keys = invocation.getArgument(1);
			Object[] args = java.util.Arrays.copyOfRange(invocation.getArguments(), 2,
					invocation.getArguments().length);
			String key = String.valueOf(keys.get(0));
			synchronized (this.locks) {
				String current = this.locks.get(key);
				if (body.contains("SESSION_LOCK_ACQUIRE")) {
					String value = String.valueOf(args[0]);
					String runId = String.valueOf(args[2]);
					if (current == null || current.equals(runId) || current.startsWith(runId + "|")) {
						this.locks.put(key, value);
						this.expiries.put(key, Duration.ofMillis(Long.parseLong(String.valueOf(args[1]))));
						return 1L;
					}
					return 0L;
				}
				if (body.contains("SESSION_LOCK_RENEW")) {
					if (String.valueOf(args[0]).equals(current)) {
						this.expiries.put(key, Duration.ofMillis(Long.parseLong(String.valueOf(args[1]))));
						return 1L;
					}
					return 0L;
				}
				if (body.contains("SESSION_LOCK_RELEASE_DEAD")) {
					String runId = String.valueOf(args[0]);
					if (runId.equals(current) || (current != null && current.startsWith(runId + "|"))) {
						this.locks.remove(key);
						this.expiries.remove(key);
						return 1L;
					}
					return 0L;
				}
				if (body.contains("SESSION_LOCK_RELEASE")) {
					if (String.valueOf(args[0]).equals(current)) {
						this.locks.remove(key);
						this.expiries.remove(key);
						return 1L;
					}
					return 0L;
				}
				throw new UnsupportedOperationException(body);
			}
		});
		return new SessionGate(redis, this.properties);
	}

}
