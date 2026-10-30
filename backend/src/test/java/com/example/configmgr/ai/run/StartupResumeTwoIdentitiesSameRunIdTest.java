package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * <b>系统扫描必须逐记录恢复身份</b>：两条记录用<b>同一个 runId</b>、分属两个身份，也各自在自己的键上续跑。
 *
 * <h2>被钉住的缺陷</h2>
 * 扫描跑在无身份线程上，而 run 级键、待决条目、会话索引的键名都由 {@code tenantId/userId} 拼出。
 * 旧实现"先 {@code store.get(runId)} 再猜身份"在定时线程上只能读到 legacy 键 ⇒ 恒 {@code null}
 * （或者更糟：沿用扫描线程残留的某个身份）。同 runId 的另一个身份则会被写错命名空间 ——
 * A 的续跑读到 B 的快照/会话，runId 与 sessionId 一起串。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>候选来自真实 {@code ai:runs} 复合成员（真实 {@link ResumeService#listRuns()}），
 * 两条记录身份不同、runId 相同；</li>
 * <li>每一条续跑都发生在<b>该条记录</b>的身份里：续跑时线程身份 = 记录身份；
 * 线程身份读口 {@code store.get(runId)} 读到的 sessionId 必须是这一条自己的；</li>
 * <li>扫描结束不残留线程身份（下一位使用者不得以别人的身份执行）。</li>
 * </ul>
 */
class StartupResumeTwoIdentitiesSameRunIdTest {

	private static final TenantUser IDENTITY_A = new TenantUser(1L, 101L);

	private static final TenantUser IDENTITY_B = new TenantUser(2L, 202L);

	private static final String RUN_ID = "run-shared-by-two-identities";

	private static final String SESSION_A = "sess-a-of-shared-run";

	private static final String SESSION_B = "sess-b-of-shared-run";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	@Test
	void systemScanResumesEachRecordUnderItsOwnIdentityWithoutSessionOrThreadBleed() {
		when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		seedSuspendedRun(IDENTITY_A, SESSION_A);
		seedSuspendedRun(IDENTITY_B, SESSION_B);

		ResumeService realService = new ResumeService(this.store, mock(RunRegistry.class), this.codec,
				mock(ResilientChatService.class), mock(SpToolCallingManager.class), mock(ThreadPoolTaskExecutor.class),
				mock(StringRedisTemplate.class), this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));
		ResumeService service = spy(realService);
		List<TenantUser> resumedIdentities = new ArrayList<>();
		List<String> resumedSessions = new ArrayList<>();
		doAnswer(invocation -> {
			resumedIdentities.add(RequestContextUtils.currentOrNull());
			RunSnapshot snapshot = this.store.get((String) invocation.getArgument(0));
			resumedSessions.add(snapshot == null ? null : snapshot.getSessionId());
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
		}).when(service).resume(anyString(), anyBoolean());
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		StartupResumeRunner runner = new StartupResumeRunner(service, this.store, this.properties, availability);

		Map<String, Object> report;
		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			report = runner.scan(true);
		}

		assertThat(report).containsEntry("scanned", true);
		assertThat(report).containsEntry("candidates", 2);
		assertThat(resumedItems(report)).as("同 runId 的两条记录都要在系统扫描里受理").hasSize(2)
			.allSatisfy(item -> assertThat(item).containsEntry("runId", RUN_ID));
		assertThat(resumedIdentities).as("续跑必须按记录身份建立作用域（不是扫描线程身份）")
			.containsExactlyInAnyOrder(IDENTITY_A, IDENTITY_B);
		assertThat(resumedSessions).as("每条记录读到的必须是自己的会话").containsExactlyInAnyOrder(SESSION_A,
				SESSION_B);
		Map<TenantUser, String> sessionByIdentity = new LinkedHashMap<>();
		for (int i = 0; i < resumedIdentities.size(); i++) {
			sessionByIdentity.put(resumedIdentities.get(i), resumedSessions.get(i));
		}
		assertThat(sessionByIdentity).as("身份与会话必须一一对应，不得串 sessionId")
			.containsExactlyInAnyOrderEntriesOf(Map.of(IDENTITY_A, SESSION_A, IDENTITY_B, SESSION_B));
		assertThat(reportIdentityPairs(report)).as("报告项携带各自的记录身份")
			.containsExactlyInAnyOrder("1/101", "2/202");
		assertThat(RequestContextUtils.currentOrNull()).as("扫描结束不得在扫描线程残留身份").isNull();

		assertThat(this.store.get(RUN_ID, IDENTITY_A.tenantId(), IDENTITY_A.userId()).getSessionId())
			.as("A 的快照与会话未被 B 的续跑改写").isEqualTo(SESSION_A);
		assertThat(this.store.get(RUN_ID, IDENTITY_B.tenantId(), IDENTITY_B.userId()).getSessionId())
			.as("B 的快照与会话未被 A 的续跑改写").isEqualTo(SESSION_B);
	}

	/** 两条同名 runId 的记录：走真实 {@code ai:runs} 复合成员，系统扫描据此发现。 */
	private void seedSuspendedRun(TenantUser identity, String sessionId) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			RunSnapshot snapshot = this.store.create(RUN_ID, sessionId, AiContext.empty(),
					List.of(new UserMessage("继续这一轮")), this.codec);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> resumedItems(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.getOrDefault("resumed", List.of());
	}

	private static Set<String> reportIdentityPairs(Map<String, Object> report) {
		Set<String> pairs = new java.util.LinkedHashSet<>();
		for (Map<String, Object> item : resumedItems(report)) {
			pairs.add(item.get("tenantId") + "/" + item.get("userId"));
		}
		return pairs;
	}

}
