package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuccessSnapshotToolPairingTest {

	private static final String RUN_ID = "run-success-tool-pairing";

	private static final String SESSION_ID = "session-success-tool-pairing";

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void successSnapshotPreservesToolPairsReasoningAndEntryIdentity() throws Exception {
		AssistantMessage form = assistant("gf-1", AiToolNames.GENERATIVE_FORM, "前缀", "assistant-form-entry", "推理甲");
		AssistantMessage create = assistant("create-1", AiToolNames.CREATE_TASK, "后缀", "assistant-create-entry", "推理乙");
		List<Message> original = List.of(new UserMessage("创建导出任务"), form,
				toolResponse("gf-1", AiToolNames.GENERATIVE_FORM, "rejected"), create,
				toolResponse("create-1", AiToolNames.CREATE_TASK, "waiting"));
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setCreatedAtMs(1L);
		snapshot.setMessageJson(this.codec.serializeAll(original));
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀后缀")).toMap());
		RunStore store = mock(RunStore.class);
		when(store.get(RUN_ID)).thenReturn(snapshot);
		when(store.isWritePermitted(RUN_ID, "terminal snapshot/memory")).thenReturn(true);
		when(store.pendingsLenient(RUN_ID)).thenReturn(List.of(
				pending("gf-1", AiToolNames.GENERATIVE_FORM, ErrorCodes.FORM_SLOTS_ALREADY_KNOWN),
				pending("create-1", AiToolNames.CREATE_TASK, null)));
		when(store.ledger(RUN_ID)).thenReturn(List.of());
		ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);
		when(transcript.readPage(SESSION_ID, 1L, 101L, null, 500))
				.thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
		when(transcript.append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				this.codec, store, mock(RunRegistry.class), mock(ToolRegistry.class), mock(ContextBuilder.class),
				mock(AiSessionService.class), new AiProperties(), mock(CancellationRegistry.class),
				mock(ToolActivityBeacon.class), null);
		ReflectionTestUtils.setField(service, "transcript", transcript);
		SpToolCallingManager.TurnTextCapture capture = new SpToolCallingManager.TurnTextCapture();
		capture.reset("终");
		java.lang.reflect.Constructor<?> constructor = Class.forName("com.example.configmgr.ai.run.ResilientChatService$Accumulator")
				.getDeclaredConstructor(String.class, SpToolCallingManager.TurnTextCapture.class);
		constructor.setAccessible(true);
		Object accumulator = constructor.newInstance(RUN_ID, capture);

		ReflectionTestUtils.invokeMethod(service, "persistSuccessSnapshot", RUN_ID, SESSION_ID, accumulator, 1,
				null, null, null, "", false, "终", "", "终", snapshot);

		List<Message> messages = this.codec.deserializeAll(snapshot.getMessageJson());
		assertThat(messages).hasSize(6);
		assertThat(messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
				.flatMap(message -> message.getToolCalls().stream())).extracting(AssistantMessage.ToolCall::id)
				.containsExactly("gf-1", "create-1");
		assertThat(messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
				.flatMap(message -> message.getResponses().stream())).extracting(ToolResponseMessage.ToolResponse::id)
				.containsExactly("gf-1", "create-1");
		AssistantMessage persistedForm = (AssistantMessage) messages.get(1);
		assertThat(persistedForm.getMetadata()).containsEntry("entryId", "assistant-form-entry")
				.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "推理甲");
		AssistantMessage persistedCreate = (AssistantMessage) messages.get(3);
		assertThat(persistedCreate.getMetadata()).containsEntry("entryId", "assistant-create-entry")
				.containsEntry(ResilientChatService.REASONING_METADATA_KEY, "推理乙");
	}

	private static AssistantMessage assistant(String id, String name, String text, String entryId, String reasoning) {
		return AssistantMessage.builder().content(text)
				.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
				.properties(Map.of("entryId", entryId, ResilientChatService.REASONING_METADATA_KEY, reasoning))
				.build();
	}

	private static ToolResponseMessage toolResponse(String id, String name, String text) {
		return ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse(id, name, text)))
				.build();
	}

	private static PendingToolCall pending(String id, String name, String code) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(id);
		pending.setName(name);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setCode(code);
		pending.setStatus(PendingToolCall.REJECTED_ARGUMENTS);
		return pending;
	}

}
