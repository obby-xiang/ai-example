package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectionPrefixResumeTest {

	@Test
	void arbitraryRepeatedPrefixesCollapseToOneWithoutChangingToolOwnership() {
		String prefix = "前缀";
		AssistantMessage first = AssistantMessage.builder().content(prefix + prefix + prefix + "一")
				.toolCalls(List.of(new AssistantMessage.ToolCall("tool-1", "function", "create_task", "{}")))
				.properties(Map.of("owner", "first")).build();
		AssistantMessage second = AssistantMessage.builder().content(prefix + prefix + "二")
				.toolCalls(List.of(new AssistantMessage.ToolCall("tool-2", "function", "start_export", "{}")))
				.properties(Map.of("owner", "second")).build();

		var normalized = CorrectionPrefixes.historyWithSingleCorrectionPrefix(
				List.of(new org.springframework.ai.chat.messages.UserMessage("导出"), first, second), prefix);

		assertThat(normalized).hasSize(3);
		AssistantMessage normalizedFirst = (AssistantMessage) normalized.get(1);
		AssistantMessage normalizedSecond = (AssistantMessage) normalized.get(2);
		assertThat(normalizedFirst.getText()).isEqualTo(prefix + "一");
		assertThat(normalizedSecond.getText()).isEqualTo("二");
		assertThat(normalizedFirst.getToolCalls()).containsExactlyElementsOf(first.getToolCalls());
		assertThat(normalizedSecond.getToolCalls()).containsExactlyElementsOf(second.getToolCalls());
		assertThat(normalizedFirst.getMetadata()).containsEntry("owner", "first");
		assertThat(normalizedSecond.getMetadata()).containsEntry("owner", "second");
	}

	@Test
	void parkedCorrectionRestoresPrefixBeforeNonEmptyToolSuffix() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀后缀")).toMap());
		PendingToolCall create = new PendingToolCall();
		create.setToolCallId("create-1");
		create.setName(AiToolNames.CREATE_TASK);
		snapshot.setInFlightToolCalls(List.of(create));
		snapshot.setAssistantContent("后缀");
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), mock(RunStore.class), mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);

		Object resumed = ReflectionTestUtils.invokeMethod(service, "correctionResume", snapshot, List.of());

		assertThat(ReflectionTestUtils.getField(resumed, "fullText")).isEqualTo("前缀后缀");
		assertThat(ReflectionTestUtils.getField(resumed, "pendingSegment")).isEqualTo("");
	}

	@Test
	void parkedCorrectionUsesPersistedFullText() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀",
						"correctionParkedFullText", "前缀后缀")).toMap());
		PendingToolCall create = new PendingToolCall();
		create.setToolCallId("create-1");
		create.setName(AiToolNames.CREATE_TASK);
		snapshot.setInFlightToolCalls(List.of(create));
		snapshot.setAssistantContent("前缀后缀");
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), mock(RunStore.class), mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);

		Object resumed = ReflectionTestUtils.invokeMethod(service, "correctionResume", snapshot, List.of());

		assertThat(ReflectionTestUtils.getField(resumed, "fullText")).isEqualTo("前缀后缀");
		assertThat(ReflectionTestUtils.getField(resumed, "pendingSegment")).isEqualTo("");
	}

	@Test
	void settledCorrectionUsesParkedFullTextWithoutChangingPrefix() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀",
						"correctionParkedFullText", "前缀后缀")).toMap());
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), mock(RunStore.class), mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);
		AssistantMessage create = AssistantMessage.builder().content("前缀后缀")
				.toolCalls(List.of(new AssistantMessage.ToolCall("create-1", "function", AiToolNames.CREATE_TASK, "{}")))
				.build();

		Object resumed = ReflectionTestUtils.invokeMethod(service, "correctionResume", snapshot, List.of(create));

		assertThat(ReflectionTestUtils.getField(resumed, "fullText")).isEqualTo("前缀后缀");
		assertThat(ReflectionTestUtils.getField(resumed, "pendingSegment")).isEqualTo("");
		assertThat(CorrectionPrefixes.correctionPrefix(snapshot)).isEqualTo("前缀");
	}

	@Test
	void settledCorrectionFragmentsAreNotSeededAsUnpersistedText() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前缀后缀")).toMap());
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), mock(RunStore.class), mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);
		AssistantMessage form = AssistantMessage.builder().content("前缀")
				.toolCalls(List.of(new AssistantMessage.ToolCall("gf-1", "function", AiToolNames.GENERATIVE_FORM, "{}")))
				.build();
		AssistantMessage create = AssistantMessage.builder().content("后缀")
				.toolCalls(List.of(new AssistantMessage.ToolCall("create-1", "function", AiToolNames.CREATE_TASK, "{}")))
				.build();

		Object resumed = ReflectionTestUtils.invokeMethod(service, "correctionResume", snapshot, List.of(form, create));

		assertThat(ReflectionTestUtils.getField(resumed, "fullText")).isEqualTo("前缀后缀");
		assertThat(ReflectionTestUtils.getField(resumed, "pendingSegment")).isEqualTo("");
	}

	@Test
	void emptyPrefixStillUsesParkedFullText() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of("correctionParkedFullText", "完整正文")).toMap());
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), mock(RunStore.class), mock(RunRegistry.class),
				mock(ToolRegistry.class), mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);

		Object resumed = ReflectionTestUtils.invokeMethod(service, "correctionResume", snapshot, List.of());

		assertThat(ReflectionTestUtils.getField(resumed, "fullText")).isEqualTo("完整正文");
		assertThat(ReflectionTestUtils.getField(resumed, "pendingSegment")).isEqualTo("");
	}

	@Test
	void multipleParkedSegmentsDoNotUseTriggerPendingSegment() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "触",
						CorrectionPrefixes.PENDING_SEGMENT_KEY, "触")).toMap());

		CorrectionPrefixes.recordParkedFullText(snapshot, "第一段");
		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("第一段");
		CorrectionPrefixes.recordParkedFullText(snapshot, "");
		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("第一段");
		CorrectionPrefixes.recordParkedFullText(snapshot, "第二段");
		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("第二段");
		CorrectionPrefixes.recordParkedFullText(snapshot, "");
		CorrectionPrefixes.recordParkedFullText(snapshot, "第三段");
		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("第三段");
	}

	@Test
	void createCorrectionPrefixDrivesParkingWithoutReadingLegacyKeys() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("tasks", "EXPORT", null, null, Map.of(
				"createEntryCorrectionPrefix", "创建前缀",
				"createTaskCorrectionPrefix", "legacy前缀")).toMap());

		assertThat(CorrectionPrefixes.correctionPrefix(snapshot)).isEqualTo("创建前缀");
		CorrectionPrefixes.recordParkedFullText(snapshot, "创建前缀后续");

		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEqualTo("创建前缀后续");
		assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
				.containsEntry("createEntryCorrectionPrefix", "创建前缀")
				.containsEntry("createTaskCorrectionPrefix", "legacy前缀");
	}

	@Test
	void legacyCreateCorrectionKeysAreIgnoredWithoutCleanupWhileExportRemainsActive() {
		java.util.Map<String, Object> mixedExtra = new java.util.LinkedHashMap<>();
		mixedExtra.put("createTaskCorrectionUsed", true);
		mixedExtra.put("createTaskCorrectionPrefix", "旧create前缀");
		mixedExtra.put(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "export前缀");
		RunSnapshot mixed = new RunSnapshot();
		mixed.setContext(AiContext.of("export", "EXPORT", null, null, mixedExtra).toMap());

		assertThat(CorrectionPrefixes.correctionPrefix(mixed)).isEqualTo("export前缀");
		ReflectionTestUtils.invokeMethod(ResilientChatService.class, "clearExportCorrectionPrefix", mixed);
		java.util.Map<String, Object> cleared = AiContext.fromMap(mixed.getContext()).getExtra();
		assertThat(cleared).containsEntry("createTaskCorrectionUsed", true)
				.containsEntry("createTaskCorrectionPrefix", "旧create前缀")
				.doesNotContainKey(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY);

		RunSnapshot legacyOnly = new RunSnapshot();
		legacyOnly.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of("createTaskCorrectionUsed", true, "createTaskCorrectionPrefix", "旧create前缀")).toMap());
		assertThat(CorrectionPrefixes.correctionPrefix(legacyOnly)).isEmpty();
		CorrectionPrefixes.recordParkedFullText(legacyOnly, "不得持久化");
		assertThat(CorrectionPrefixes.parkedFullText(legacyOnly)).isEmpty();
		assertThat(AiContext.fromMap(legacyOnly.getContext()).getExtra())
				.containsEntry("createTaskCorrectionUsed", true)
				.containsEntry("createTaskCorrectionPrefix", "旧create前缀")
				.doesNotContainKey(CorrectionPrefixes.PARKED_FULL_TEXT_KEY);
	}

	@Test
	void newCorrectionDoesNotInheritPreviousParkedFullText() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
				Map.of(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "旧前缀",
						CorrectionPrefixes.PENDING_SEGMENT_KEY, "旧段",
						CorrectionPrefixes.PARKED_FULL_TEXT_KEY, "旧完整正文")).toMap());
		RunStore store = mock(RunStore.class);
		when(store.get("run-new-correction")).thenReturn(snapshot);
		when(store.isWritePermitted("run-new-correction", "close correction marker")).thenReturn(true);
		ResilientChatService service = new ResilientChatService(mock(AiProviderRegistry.class), mock(ChatMemory.class),
				new MessageJsonCodec(new ObjectMapper()), store, mock(RunRegistry.class), mock(ToolRegistry.class),
				mock(ContextBuilder.class), mock(AiSessionService.class), new AiProperties(),
				mock(CancellationRegistry.class), mock(ToolActivityBeacon.class), null);

		ReflectionTestUtils.invokeMethod(service, "isMarkCorrectionUsed", "run-new-correction", "新前缀",
				ResilientChatService.EXPORT_CORRECTION_MARKER, ResilientChatService.EXPORT_CORRECTION_PREFIX_KEY);

		assertThat(CorrectionPrefixes.parkedFullText(snapshot)).isEmpty();
	}

}
