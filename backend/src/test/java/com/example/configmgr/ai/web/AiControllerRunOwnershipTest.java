package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.GlobalExceptionHandler;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>只带 runId 的接口必须核对归属</b>（租户任务2 核心组；设计稿「ID 与归属约束」）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code /events/{runId}}、{@code /cancel/{runId}}、{@code /pending/{runId}} 这些接口只有一个
 * runId：如果"键里有这条记录"就算数，那么只要猜到/拿到别人的 runId 就能读事件、看待决条目、
 * 甚至取消别人的轮次。runId 不是跨租户唯一性证明（设计稿明说），必须把快照读出来核对
 * {@code tenantId/userId}。
 *
 * <h2>本组口径</h2>
 * 快照存在但归属 ≠ 当前身份 ⇒ 一律按<b>不存在</b>回答（不回显"这一轮在别人手里"）：
 * 事件 404、待决空列表、取消不把别人的快照当成自己的（{@code runExists=false}）。
 */
class AiControllerRunOwnershipTest {

	private static final String RUN_ID = "run-ownership";

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = mock(RunStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private MockMvc mvc;

	private RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void setUp() {
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), this.store, mock(RunRegistry.class), this.cancellations,
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, new AiProperties(),
				mock(Environment.class), mock(JobWatchdog.class));
		when(this.cancellations.cancel(anyString(), anyString()))
			.thenReturn(new CancellationRegistry.CancelResult(true, false, RUN_ID));
		when(this.cancellations.status(anyString())).thenReturn(java.util.Map.of());
		when(this.store.instanceId()).thenReturn("test-instance");
		this.mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler(new ErrorBodyFactory(provider())))
			.build();
		this.tenantScope = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void tearDown() {
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	@Test
	void eventsEndpointTreatsAnotherIdentitiesRunAsUnknown() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot(2L, 202L));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCodes.UNKNOWN_RUN));
	}

	@Test
	void pendingEndpointReturnsNothingForAnotherIdentitiesRun() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot(2L, 202L));

		this.mvc.perform(get("/api/ai/pending/{runId}", RUN_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());

		verify(this.store, never()).pendingsLenient(anyString());
	}

	@Test
	void cancelEndpointDoesNotAdoptAnotherIdentitiesSnapshot() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot(2L, 202L));

		this.mvc.perform(post("/api/ai/cancel/{runId}", RUN_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runExists").value(false))
			.andExpect(jsonPath("$.statusBeforeCancel").doesNotExist());
	}

	@Test
	void theSameIdentityStillSeesItsOwnRun() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot(1L, 101L));

		this.mvc.perform(post("/api/ai/cancel/{runId}", RUN_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runExists").value(true))
			.andExpect(jsonPath("$.statusBeforeCancel").value(RunSnapshot.RUNNING));
	}

	private static RunSnapshot snapshot(long tenantId, long userId) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId("s-ownership");
		snapshot.setTenantId(tenantId);
		snapshot.setUserId(userId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
