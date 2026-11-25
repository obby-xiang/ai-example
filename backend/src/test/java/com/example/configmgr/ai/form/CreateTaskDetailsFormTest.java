package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Kind;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Resolution;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Status;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.UnresolvedReference;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateTaskDetailsFormTest {

    private static final String RUN_ID = "run-create-details-form";

    private static final String ORIGINAL_CALL = "call-create-original";

    private static final String ORIGINAL_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"usd\"}]}}";

    private final InMemoryRunStore store = new InMemoryRunStore(true);

    private final ObjectMapper mapper = new ObjectMapper();

    private final RunRegistry registry = mock(RunRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final AiProperties properties = new AiProperties();

    private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

    private CreateTaskDetailsForm form;

    private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

    @BeforeEach
    void setUp() {
        this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
        this.properties.getPark().setEnabled(true);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        CancellationRegistry cancellations = mock(CancellationRegistry.class);
        when(cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
        });
        ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, cancellations,
                mock(ToolActivityBeacon.class), List.of(this.guard));
        this.form = new CreateTaskDetailsForm(this.store, this.registry, gate,
                new MessageJsonCodec(this.mapper), this.properties);
        RunSnapshot snapshot = this.store.seedRun(RUN_ID, "session-create-details-form");
        snapshot.setTenantId(1L);
        snapshot.setUserId(101L);
        snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
        snapshot.setMessageJson(new MessageJsonCodec(this.mapper).serializeAll(history()));
        this.store.save(snapshot);
    }

    @AfterEach
    void tearDown() {
        this.identity.close();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void parksOnlyUnresolvedReferencesWithAuthoritativeOptions() {
        Resolution resolution = new Resolution(Status.UNRESOLVED, ORIGINAL_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.DEFINITION, 0, -1, "未知字典", List.of()),
                new UnresolvedReference(Kind.FIELD, 0, 0, "未知字段", List.of()),
                new UnresolvedReference(Kind.ENUM_VALUE, 0, 0, "usd", List.of("CNY", "USD"))),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);

        boolean parked = this.form.parkIfUnresolved(RUN_ID, ORIGINAL_CALL, ORIGINAL_ARGUMENTS, resolution, history());

        assertThat(parked).isTrue();
        List<PendingToolCall> pendings = this.store.pendingsLenient(RUN_ID);
        assertThat(pendings).hasSize(1);
        PendingToolCall pending = pendings.getFirst();
        assertThat(pending.getToolCallId()).isEqualTo(CreateTaskDetailsForm.toolCallId(ORIGINAL_CALL));
        assertThat(pending.getCreateTaskToolCallId()).isEqualTo(ORIGINAL_CALL);
        assertThat(pending.getCreateTaskArguments()).isEqualTo(ORIGINAL_ARGUMENTS);
        assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);

        JsonNode fields = read(pending.getArguments()).path("form").path("fields");
        assertThat(fields).hasSize(3);
        assertThat(fields.findValuesAsText("key")).containsExactly("definition_0", "field_0_0", "enum_value_0_0");
        assertThat(fields.get(0).path("type").asText()).isEqualTo("text");
        assertThat(fields.get(1).path("type").asText()).isEqualTo("text");
        assertThat(fields.get(2).path("type").asText()).isEqualTo("enum");
        assertThat(fields.get(2).path("options").findValuesAsText("value")).containsExactly("CNY", "USD");
        assertThat(fields).allMatch(field -> field.path("required").asBoolean());
        assertThat(read(pending.getArguments()).path("form").path("purpose").asText())
                .isEqualTo(FormPurposes.CREATE_TASK_DETAILS);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
    }

    @Test
    void formPassesWhitelistAndUsesReplyLanguage() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        Resolution resolution = new Resolution(Status.UNRESOLVED, ORIGINAL_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.ENUM_VALUE, 0, 0, "usd", List.of("CNY", "USD"))),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);

        boolean parked = this.form.parkIfUnresolved(RUN_ID, ORIGINAL_CALL, ORIGINAL_ARGUMENTS, resolution, history());

        assertThat(parked).isTrue();
        PendingToolCall pending = this.store.pendingsLenient(RUN_ID).getFirst();
        assertThat(this.guard.inspectArguments(GenerativeFormRules.TOOL_NAME, pending.getArguments(),
                AiContext.of("tasks", null, null, null, null)).accepted()).isTrue();
        assertThat(pending.getArguments()).contains(CreateTaskDetailsForm.TITLE_EN)
                .contains("Value 1")
                .doesNotContain(CreateTaskDetailsForm.TITLE)
                .doesNotContain("选项值");
        assertThat(ReplyLanguage.current().english()).isTrue();
    }

    private static List<Message> history() {
        return List.of(new SystemMessage("PERSONA"), new UserMessage("create a task"));
    }

    private JsonNode read(String json) {
        try {
            return this.mapper.readTree(json);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

}
