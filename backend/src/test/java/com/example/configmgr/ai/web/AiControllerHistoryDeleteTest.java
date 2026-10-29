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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旧 {@code DELETE /api/ai/history/{sessionId}} 的<b>兼容契约</b>。
 *
 * <p>用户 2026-10-25 的最终口径覆盖旧的"只删记忆窗口"语义：旧路径现在与
 * {@code DELETE /api/ai/session/{sessionId}} 一样委托 {@link AiSessionPurgeService}。
 * 本类只钉住旧响应字段/HHTTP 语义，防止"两套删除语义长期并存"。
 */
class AiControllerHistoryDeleteTest {

	private final AiSessionPurgeService service = mock(AiSessionPurgeService.class);

	private final RunStore runStore = mock(RunStore.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiSessionPurgeController controller = new AiSessionPurgeController(this.service, this.runStore,
			this.redisAvailability);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	@Test
	void existingMemoryIsDeletedAndLegacyFieldsAreKept() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(true, true, List.of()));

		purgeWithIdentity(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value("s-1"))
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(true))
			.andExpect(jsonPath("$.memoryKey").value("chat:mem:{s-1}"))
			.andExpect(jsonPath("$.removedFromIndex").value(true))
			.andExpect(jsonPath("$.runActive").value(false))
			.andExpect(jsonPath("$.storedBy").value("inst-1"));

		verify(this.service).purge("s-1", "inst-1");
	}

	@Test
	void missingMemoryIsIdempotentOkNotNotFound() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(true, false, List.of()));

		purgeWithIdentity(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(false));
	}

	@Test
	void activeRunIsReportedAsDeferredAndNotRejected() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.service.purge("s-1", "inst-1")).thenReturn(result(false, true,
				List.of(new DeferredView("r-1", "RUNNING", "RUN_ACTIVE"))));

		purgeWithIdentity(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.deleted").value(false))
			.andExpect(jsonPath("$.runActive").value(true))
			.andExpect(jsonPath("$.activeRunId").value("r-1"))
			.andExpect(jsonPath("$.deferred[0].reason").value("RUN_ACTIVE"));
	}

	@Test
	void redisUnavailableFailsFastWithoutCallingTheService() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_REDIS_UNAVAILABLE"));

		verify(this.service, never()).purge(any(), any());
	}

	@Test
	void blankSessionIdIsBadRequest() {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		ResponseEntity<Map<String, Object>> response = this.controller.purge(" ");

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("BAD_REQUEST", response.getBody().get("code"));
		verify(this.service, never()).purge(any(), any());
	}

	/** 带身份执行请求（MockMvc 在 {@code perform} 内即执行；无身份时销毁端点 fail-closed 400）。 */
	private org.springframework.test.web.servlet.ResultActions purgeWithIdentity(
			org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			return this.mockMvc.perform(request);
		}
	}

	private static PurgeResult result(boolean complete, boolean existed, List<DeferredView> deferred) {
		return new PurgeResult("s-1", "purge-1", complete, true, null,
				new SessionPurgeDatabase.Snapshot(1), new SessionPurgeDatabase.Deleted(1),
				new MemoryView(existed, existed ? 1 : 0, true), "chat:mem:{s-1}",
				List.of(new RunView("r-0", "DONE", false, true, false)), deferred,
				new ContextView(true, true, 1, true, 3), new AttachmentView(0, 0, 0, List.of()),
				new SessionView(true, true), true, new ClientCleanup("ai-mirror:s-1", "cm.ai.form.draft.",
						List.of()),
				List.of(), List.of());
	}

}
