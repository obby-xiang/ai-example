package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.feedback.AiFeedback;
import com.example.configmgr.ai.feedback.AiFeedbackRepository;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-163 T5：赞踩端点 {@code POST/GET /api/ai/feedback}（幂等 upsert / 取消=删行 / 校验 400 / 查看列表）。
 * 仓储为字段注入（本批后端红线仅限赞踩），standaloneSetup 用 ReflectionTestUtils 灌入。
 */
class AiControllerFeedbackTest {

	private final AiFeedbackRepository feedbackRepository = mock(AiFeedbackRepository.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

	@Test
	void firstUpsertCreatesAndSecondUpsertUpdatesSameMessage() throws Exception {
		when(this.feedbackRepository.findByMessageId("m-1")).thenReturn(null);

		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"m-1\",\"sessionId\":\"s-1\",\"rating\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.saved").value(true))
			.andExpect(jsonPath("$.data.cancelled").value(false));

		ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
		verify(this.feedbackRepository).save(captor.capture());
		assertThat(captor.getValue().getMessageId()).isEqualTo("m-1");
		assertThat(captor.getValue().getRating()).isEqualTo(1);

		// 幂等 upsert：同 messageId 二次上报 = 更新同一条（saved=false）；携正文快照 excerpt（收口修 F2）
		AiFeedback existing = captor.getValue();
		when(this.feedbackRepository.findByMessageId("m-1")).thenReturn(existing);
		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"m-1\",\"sessionId\":\"s-1\",\"rating\":-1,\"reason\":\"答非所问\",\"excerpt\":\"被评价消息正文快照\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.saved").value(false));
		verify(this.feedbackRepository, times(2)).save(existing);
		assertThat(existing.getRating()).isEqualTo(-1);
		assertThat(existing.getReason()).isEqualTo("答非所问");
		assertThat(existing.getMessageExcerpt()).isEqualTo("被评价消息正文快照");
	}

	@Test
	void ratingZeroCancelsByDeletingRow() throws Exception {
		AiFeedback existing = new AiFeedback();
		existing.setMessageId("m-2");
		when(this.feedbackRepository.findByMessageId("m-2")).thenReturn(existing);

		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"m-2\",\"sessionId\":\"s-1\",\"rating\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.saved").value(false))
			.andExpect(jsonPath("$.data.cancelled").value(true));
		verify(this.feedbackRepository).delete(existing);

		// 取消幂等：无该行时取消 = 空操作（不报错、不删）——先清调用记录再断负例
		org.mockito.Mockito.clearInvocations(this.feedbackRepository);
		when(this.feedbackRepository.findByMessageId("m-3")).thenReturn(null);
		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"m-3\",\"sessionId\":\"s-1\",\"rating\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelled").value(false));
		verify(this.feedbackRepository, never()).delete(any());
	}

	@Test
	void invalidPayloadRejectedWith400() throws Exception {
		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"messageId\":\"m-1\",\"sessionId\":\"s-1\",\"rating\":5}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));

		this.mockMvc.perform(post("/api/ai/feedback").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sessionId\":\"s-1\",\"rating\":1}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void feedbackListExposesTimeSummaryRatingReason() throws Exception {
		AiFeedback entry = new AiFeedback();
		entry.setMessageId("msg-abc-12345");
		entry.setSessionId("session-xyz-678");
		entry.setRating(-1);
		entry.setReason("结果有误");
		entry.setMessageExcerpt("被评价消息正文快照");
		entry.setCreatedAt(LocalDateTime.of(2026, 10, 14, 10, 30));
		entry.setUpdatedAt(LocalDateTime.of(2026, 10, 14, 10, 30));
		when(this.feedbackRepository.findTop100ByOrderByUpdatedAtDesc()).thenReturn(List.of(entry));

		this.mockMvc.perform(get("/api/ai/feedback"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].messageId").value("msg-abc-12345"))
			.andExpect(jsonPath("$.data[0].sessionId").value("session-xyz-678"))
			.andExpect(jsonPath("$.data[0].rating").value(-1))
			.andExpect(jsonPath("$.data[0].reason").value("结果有误"))
			.andExpect(jsonPath("$.data[0].excerpt").value("被评价消息正文快照"));
	}

	private AiController controller() {
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), mock(RunStore.class), mock(RunRegistry.class),
				mock(CancellationRegistry.class), mock(RedisAvailability.class),
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(),
				mock(Environment.class), mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "aiFeedbackRepository", this.feedbackRepository);
		return controller;
	}

	private static <T> ObjectProvider<T> provider() {
		@SuppressWarnings("unchecked")
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		return provider;
	}
}
