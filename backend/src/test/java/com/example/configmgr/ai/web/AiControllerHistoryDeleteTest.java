package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.session.AiSessionPurgeService.AttachmentView;
import com.example.configmgr.ai.session.AiSessionPurgeService.ClientCleanup;
import com.example.configmgr.ai.session.AiSessionPurgeService.ContextView;
import com.example.configmgr.ai.session.AiSessionPurgeService.DeferredView;
import com.example.configmgr.ai.session.AiSessionPurgeService.MemoryView;
import com.example.configmgr.ai.session.AiSessionPurgeService.PurgeResult;
import com.example.configmgr.ai.session.AiSessionPurgeService.RunView;
import com.example.configmgr.ai.session.AiSessionPurgeService.SessionView;
import com.example.configmgr.ai.session.AiSessionPurgeService.TranscriptView;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话销毁端点（原 {@code DELETE /api/ai/history/{sessionId}} 的兼容路径已退场）。
 *
 * <p>现行契约只有 {@code DELETE /api/ai/session/{sessionId}}：统一委托
 * {@link AiSessionPurgeService}，响应是 {@link PurgeResult#toBody()} 裸对象 + {@code identity}
 * 身份快照。旧“只删记忆窗口”的响应字段（{@code deleted/existed/memoryKey/removedFromIndex/
 * runActive/storedBy}）已不存在，本类按当前响应面钉住删除语义、幂等、活跃 run 延后、失败与身份。
 */
class AiControllerHistoryDeleteTest {

	private final AiSessionPurgeService service = mock(AiSessionPurgeService.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiSessionPurgeController controller = new AiSessionPurgeController(this.service,
			this.redisAvailability);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller).build();

	@Test
	void existingSessionIsPurgedAndResponseKeepsCurrentFields() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.service.purge(eq("s-1"), anyString())).thenReturn(result(true, true, List.of()));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value("s-1"))
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.found").value(true))
			.andExpect(jsonPath("$.memory.existed").value(true))
			.andExpect(jsonPath("$.memory.messages").value(1))
			.andExpect(jsonPath("$.memory.deleted").value(true))
			.andExpect(jsonPath("$.database.feedbackRows").value(1))
			.andExpect(jsonPath("$.database.feedbackDeleted").value(1))
			.andExpect(jsonPath("$.clientCleanup.mirrorKey").value("ai-mirror:1:101:s-1"))
			.andExpect(jsonPath("$.identity.tenantId").value(1))
			.andExpect(jsonPath("$.identity.userId").value(101));

		verify(this.service).purge(eq("s-1"), anyString());
	}

	@Test
	void missingSessionIsIdempotentOkNotNotFound() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.service.purge(eq("s-1"), anyString())).thenReturn(result(true, false, List.of()));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(true))
			.andExpect(jsonPath("$.found").value(false))
			.andExpect(jsonPath("$.memory.existed").value(false));
	}

	@Test
	void activeRunIsReportedAsDeferredAndNotRejected() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.service.purge(eq("s-1"), anyString())).thenReturn(result(false, true,
				List.of(new DeferredView("r-1", "RUNNING", "RUN_ACTIVE"))));

		purgeWithIdentity(delete("/api/ai/session/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.deferred[0].runId").value("r-1"))
			.andExpect(jsonPath("$.deferred[0].reason").value("RUN_ACTIVE"));
	}

	@Test
	void redisUnavailableFailsFastWithoutCallingTheService() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		this.mockMvc.perform(delete("/api/ai/session/s-1"))
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

	private static PurgeResult result(boolean complete, boolean found, List<DeferredView> deferred) {
		return new PurgeResult("s-1", "purge-1", complete, found, null,
				new SessionPurgeDatabase.Snapshot(1), new SessionPurgeDatabase.Deleted(1),
				new MemoryView(found, found ? 1 : 0, true),
				new TranscriptView(found, found ? 1 : 0, true),
				List.of(new RunView("r-0", "DONE", false, true, false)), deferred,
				new ContextView(true, true, true, 3), new AttachmentView(0, 0, 0, List.of()),
				new SessionView(true, true), new ClientCleanup("ai-mirror:1:101:s-1", "cm.ai.form.draft.1:101.",
						List.of()),
				List.of(), List.of());
	}

}
