package com.example.configmgr.ai.form;

import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CorrectionPrefixes;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostFormFullTextTest {

	private static final String RUN_ID = "run-host-full-text";

	@Test
	void secondHostParkUsesCurrentFullTextAndKeepsToolPairs() {
		RunStore store = mock(RunStore.class);
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of("createTaskCorrectionPrefix", "P")).toMap());
		when(store.get(RUN_ID)).thenReturn(snapshot);
		SseChatEmitter emitter = mock(SseChatEmitter.class);
		when(emitter.transcriptFullText()).thenReturn("P+A", "P+A+B");
		RunRegistry registry = mock(RunRegistry.class);
		when(registry.of(RUN_ID)).thenReturn(emitter);
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		List<Message> history = List.of(
				AssistantMessage.builder().content("A")
					.toolCalls(List.of(new AssistantMessage.ToolCall("create-1", "function", AiToolNames.CREATE_TASK, "{}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("create-1", AiToolNames.CREATE_TASK, "waiting")))
					.build());
		snapshot.setMessageJson(codec.serializeAll(history));
		doAnswer(invocation -> {
			CorrectionPrefixes.recordParkedFullText(snapshot, invocation.getArgument(2));
			snapshot.setMessageJson(codec.serializeAll(invocation.getArgument(4)));
			return null;
		}).when(store).saveSuspended(eq(RUN_ID), any(), any(), anyList(), anyList(), eq(codec), any());
		TestHostForm form = new TestHostForm(store, registry, mock(ConfirmGate.class), codec);

		assertThat(form.park(history, pending("call-1"))).isTrue();
		assertThat(form.park(history, pending("call-2"))).isTrue();

		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("P+A+B");
		List<Message> persisted = codec.deserializeAll(snapshot.getMessageJson());
		AssistantMessage assistant = (AssistantMessage) persisted.get(0);
		ToolResponseMessage response = (ToolResponseMessage) persisted.get(1);
		assertThat(assistant.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("create-1");
		assertThat(response.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("create-1");
	}

	private static PendingToolCall pending(String id) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(id);
		pending.setName(AiToolNames.GENERATIVE_FORM);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private static final class TestHostForm extends HostForm {

		private TestHostForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec) {
			super(store, registry, gate, codec, new com.example.configmgr.ai.config.AiProperties());
		}

		private boolean park(List<Message> history, PendingToolCall pending) {
			this.properties.getPark().setEnabled(true);
			return shouldParkAll(RUN_ID, history, List.of(pending));
		}
	}
}
