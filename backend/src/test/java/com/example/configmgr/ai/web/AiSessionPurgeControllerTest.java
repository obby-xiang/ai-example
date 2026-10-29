package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.session.AiSessionPurgeService.AttachmentView;
import com.example.configmgr.ai.session.AiSessionPurgeService.ClientCleanup;
import com.example.configmgr.ai.session.AiSessionPurgeService.ContextView;
import com.example.configmgr.ai.session.AiSessionPurgeService.DeferredView;
import com.example.configmgr.ai.session.AiSessionPurgeService.MemoryView;
import com.example.configmgr.ai.session.AiSessionPurgeService.PurgeResult;
import com.example.configmgr.ai.session.AiSessionPurgeService.RunView;
import com.example.configmgr.ai.session.AiSessionPurgeService.SessionView;
import com.example.configmgr.ai.session.SessionPurgeDatabase;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/ai/session/{sessionId}} 的 HTTP 契约。
 *
 * <p>关键点：成功与"部分成功/活跃 run 延后"都是 200，但 {@code complete} <b>不恒 true</b>；
 * Redis 不可用才 503；响应给前端 {@code clientCleanup}（sessionStorage 镜像/草稿只能由前端删）。
 */
class AiSessionPurgeControllerTest {

	private final AiSessionPurgeService service = mock(AiSessionPurgeService.class);

	private final RunStore runStore = mock(RunStore.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiSessionPurgeController controller = new AiSessionPurgeController(this.service, this.runStore,
			this.redisAvailability);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	@Test
	void completePurgeReturns200WithDeletedDetailAndClientCleanup() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(true, List.of()));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value("s-1"))
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.found").value(true))
			.andExpect(jsonPath("$.aborted").value(false))
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(true))
			.andExpect(jsonPath("$.memoryKey").value("chat:mem:{s-1}"))
			.andExpect(jsonPath("$.removedFromIndex").value(true))
			.andExpect(jsonPath("$.runActive").value(false))
			.andExpect(jsonPath("$.database.feedbackDeleted").value(1))
			.andExpect(jsonPath("$.runs[0].runId").value("r-1"))
			.andExpect(jsonPath("$.runs[0].deleted").value(true))
			.andExpect(jsonPath("$.clientCleanup.mirrorKey").value("ai-mirror:s-1"))
			.andExpect(jsonPath("$.clientCleanup.draftToolCallIds[0]").value("call-1"))
			.andExpect(jsonPath("$.storedBy").value("inst-1"));
	}

	@Test
	void legacyHistoryPathUsesTheSameFullPurgeAndKeepsLegacyFields() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(true, List.of()));

		purgeWithIdentity(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(true))
			.andExpect(jsonPath("$.memoryKey").value("chat:mem:{s-1}"))
			.andExpect(jsonPath("$.removedFromIndex").value(true))
			.andExpect(jsonPath("$.runActive").value(false))
			.andExpect(jsonPath("$.storedBy").value("inst-1"));

		verify(this.service).purge("s-1", "inst-1");
	}

	@Test
	void deferredActiveRunIs200ButCompleteAndDeletedAreFalse() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1"))
			.thenReturn(result(false, List.of(new DeferredView("r-2", "RUNNING", "RUN_ACTIVE"))));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.deleted").value(false))
			.andExpect(jsonPath("$.runActive").value(true))
			.andExpect(jsonPath("$.activeRunId").value("r-2"))
			.andExpect(jsonPath("$.deferred[0].runId").value("r-2"))
			.andExpect(jsonPath("$.deferred[0].reason").value("RUN_ACTIVE"));
	}

	/**
	 * <b>purge 响应与身份同步</b>（租户任务 2 收尾组）：响应回报这次销毁的身份快照 ——
	 * H2 面的反馈行就是按 {@code sessionId + tenantId + userId} 删的（跨租户同 sessionId 不误删）。
	 */
	@Test
	void purgeResponseEchoesTheCurrentIdentity() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(true, List.of()));

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			this.mockMvc.perform(delete("/api/ai/session/s-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.identity.tenantId").value(1))
				.andExpect(jsonPath("$.identity.userId").value(101));
		}
	}

	@Test
	void redisUnavailableFailsFastWithoutCallingTheService() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(delete("/api/ai/session/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_REDIS_UNAVAILABLE"));

		verify(this.service, never()).purge(anyString(), anyString());
	}

	@Test
	void blankSessionIdIsBadRequest() {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		ResponseEntity<Map<String, Object>> response = this.controller.purge(" ");

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("BAD_REQUEST", response.getBody().get("code"));
		verify(this.service, never()).purge(any(), any());
	}

	@Test
	void serviceIllegalArgumentIsBadRequest() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenThrow(new IllegalArgumentException("sessionId 非法"));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	/**
	 * <b>无身份 fail-closed</b>（租户任务 2 收尾组）：销毁是身份内的操作，没有身份上下文时
	 * 不执行任何删除（不回落"默认租户"，也不按 sessionId 全量删），直接 400 {@code TENANT_IDENTITY_REQUIRED}。
	 */
	@Test
	void purgeWithoutIdentityFailsClosed() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		this.mockMvc.perform(delete("/api/ai/session/s-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TENANT_IDENTITY_REQUIRED"));

		verify(this.service, never()).purge(anyString(), anyString());
	}

	/** 带身份执行请求（MockMvc 在 {@code perform} 内即执行，断言可在作用域外继续）。 */
	private org.springframework.test.web.servlet.ResultActions purgeWithIdentity(
			org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			return this.mockMvc.perform(request);
		}
	}

	private static PurgeResult result(boolean complete, List<DeferredView> deferred) {
		return new PurgeResult("s-1", "purge-1", complete, true, null,
				new SessionPurgeDatabase.Snapshot(1), new SessionPurgeDatabase.Deleted(1),
				new MemoryView(true, 2, true), "chat:mem:{s-1}",
				List.of(new RunView("r-1", "DONE", false, true, false)), deferred,
				new ContextView(true, true, 2, true, 5), new AttachmentView(1, 1, 1, List.of()),
				new SessionView(true, true), true,
				new ClientCleanup("ai-mirror:s-1", "cm.ai.form.draft.", List.of("call-1")),
				List.of(), List.of());
	}

}
