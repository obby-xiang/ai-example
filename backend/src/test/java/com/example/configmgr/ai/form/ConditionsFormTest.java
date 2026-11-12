package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionsFormTest {

    private static final String RUN_ID = "run-condition-mode";

    private static final String SESSION_ID = "s-condition-mode";

    private static final long TASK_ID = 42L;

    private final InMemoryRunStore store = new OrderedRunStore();

    private final ObjectMapper mapper = new ObjectMapper();

    private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final AiProperties properties = new AiProperties();

    private final TaskService taskService = mock(TaskService.class);

    private Task task;

    private ConditionsForm form;

    private RequestContextUtils.SafeCloseable identity;

    @BeforeEach
    void setUp() {
        this.identity = RequestContextUtils.useContext(1L, 101L);
        this.properties.getPark().setEnabled(true);
        when(this.registry.of(anyString())).thenReturn(this.emitter);
        this.task = task("CURRENCY");
        when(this.taskService.findById(TASK_ID)).thenAnswer(invocation -> this.task);
        this.form = new ConditionsForm(this.store, this.registry, this.gate, this.codec, this.properties,
                this.taskService);
        RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
        snapshot.setContext(context().toMap());
        snapshot.setMessageJson(this.codec.serializeAll(history()));
        this.store.save(snapshot);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (this.identity != null) {
            this.identity.close();
        }
    }

    @Test
    void hostFormHasRequiredCustomDefaultAndAllOption() {
        JsonNode schema = issueHostFormSchema();

        assertThat(schema.path("fields")).hasSize(2);
        JsonNode mode = schema.path("fields").get(0);
        assertThat(mode.path("key").asText()).isEqualTo("conditionMode");
        assertThat(mode.path("type").asText()).isEqualTo("enum");
        assertThat(mode.path("required").asBoolean()).isTrue();
        assertThat(mode.path("defaultValue").asText()).isEqualTo("CUSTOM");
        assertThat(mode.path("options")).extracting(option -> option.path("value").asText())
                .containsExactly("CUSTOM", "ALL");
        JsonNode conditions = schema.path("fields").get(1);
        assertThat(conditions.path("key").asText()).isEqualTo("conditions");
        assertThat(conditions.path("required").asBoolean()).isFalse();
        assertThat(conditions.path("visibleWhen").path("key").asText()).isEqualTo("conditionMode");
        assertThat(conditions.path("visibleWhen").path("op").asText()).isEqualTo("eq");
        assertThat(conditions.path("visibleWhen").path("value").asText()).isEqualTo("CUSTOM");
    }

    @Test
    void hostFormUsesEnglishModeOptionCopy() {
        Locale previous = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            JsonNode mode = issueHostFormSchema().path("fields").get(0);

            assertThat(mode.path("options").get(0).path("label").asText()).isEqualTo("Custom filters");
            assertThat(mode.path("options").get(1).path("label").asText()).isEqualTo("All data (no filters)");
        }
        finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    @Test
    void allWithConditionsOmittedParksOnlyConfirmStepForExport() {
        PendingToolCall answered = issueHostForm();
        answer(answered, "{\"conditionMode\":\"ALL\"}");

        assertThat(this.form.feedbackOf(RUN_ID, TASK_ID, List.of("CURRENCY"), this.task).blank()).isTrue();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();

        PendingToolCall advance = this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID);
        assertThat(advance).isNotNull();
        assertThat(advance.getName()).isEqualTo("confirm_step");
        assertThat(advance.getArguments()).isEqualTo("{\"step\":\"EXPORT\"}");
        assertThat(this.store.pendingsLenient(RUN_ID))
                .noneMatch(pending -> "set_condition".equals(pending.getName())
                        || "set_conditions".equals(pending.getName()))
                .noneMatch(pending -> !answered.getToolCallId().equals(pending.getToolCallId())
                        && GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
    }

    @Test
    void legacySingleTextBlankIsNotCollected() {
        PendingToolCall legacy = frontendCall(ConditionsForm.toolCallId(RUN_ID, TASK_ID),
                GenerativeFormRules.TOOL_NAME, "{\"form\":{\"scenario\":\"FILTER\",\"purpose\":\"SET_CONDITIONS\","
                        + "\"fields\":[{\"key\":\"conditions\",\"label\":\"筛选条件\",\"type\":\"text\"}]}}");
        this.store.putPending(RUN_ID, legacy);
        answer(legacy, "{\"conditions\":\"\"}");

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, List.of("CURRENCY"), this.task);
        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.nonEmpty()).isFalse();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isFalse();

        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"conditionMode\":\"CUSTOM\"}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":null}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":\"  \\n\\t\"}"
    })
    void customBlankTextParksOnlyConfirmStepForExport(String resultText) {
        answer(issueHostForm(), resultText);

        assertThat(this.form.feedbackOf(RUN_ID, TASK_ID, List.of("CURRENCY"), this.task).blank()).isTrue();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();

        PendingToolCall advance = this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID);
        assertThat(advance).isNotNull();
        assertThat(advance.getName()).isEqualTo("confirm_step");
        assertThat(this.store.pendingsLenient(RUN_ID))
                .noneMatch(pending -> "set_condition".equals(pending.getName())
                        || "set_conditions".equals(pending.getName()));
    }

    @Test
    void customNonEmptyTextStillRequiresModelStructuring() {
        answer(issueHostForm(), "{\"conditionMode\":\"CUSTOM\",\"conditions\":\"code = CNY\"}");
        PendingToolCall confirm = frontendCall("call-confirm", "confirm_step", "{}");

        ConditionsForm.ConfirmStepDecision decision = this.form.inspectConfirmStep(RUN_ID, context(), confirm, history());

        assertThat(decision.outcome()).isEqualTo(ConditionsForm.ConfirmStepDecision.Outcome.REJECTED);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("set_condition"));
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
        assertThat(this.store.pendingsLenient(RUN_ID))
                .noneMatch(pending -> "set_condition".equals(pending.getName())
                        || "set_conditions".equals(pending.getName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"conditionMode\":\"SOMETIMES\"}",
            "{\"conditionMode\":\"all\"}",
            "{\"conditionMode\":1}",
            "{\"conditionMode\":true}",
            "{\"conditionMode\":null}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":{}}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":[]}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":1}",
            "{\"conditionMode\":\"CUSTOM\",\"conditions\":true}",
            "{}"
    })
    void invalidModeOrValueShapeNeverAdvances(String resultText) {
        answer(issueHostForm(), resultText);

        assertThat(this.form.feedbackOf(RUN_ID, TASK_ID, List.of("CURRENCY"), this.task).blank()).isFalse();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isFalse();

        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
        assertThat(this.store.pendingsLenient(RUN_ID))
                .noneMatch(pending -> "set_condition".equals(pending.getName())
                        || "set_conditions".equals(pending.getName()));
    }

    @Test
    void cancelledFormNeverAdvances() {
        PendingToolCall cancelled = issueHostForm();
        cancelled.setStatus(PendingToolCall.FRONTEND_CANCELLED);
        cancelled.setResolvedAtMs(System.currentTimeMillis());
        this.store.putPending(RUN_ID, cancelled);

        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isFalse();

        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @Test
    void oldAllAnswerDoesNotCoverNewlyAddedDefinition() {
        PendingToolCall answered = issueHostForm();
        answer(answered, "{\"conditionMode\":\"ALL\"}");
        this.task.getItems().getFirst().setConditionJson("{}");
        this.task.getItems().add(item("RATE"));
        this.task.setUpdatedAt(LocalDateTime.now().plusSeconds(1));

        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();

        String gapFormId = ConditionsForm.toolCallId(RUN_ID, TASK_ID, List.of("RATE"), currentDefCodes());
        assertThat(this.store.pending(RUN_ID, gapFormId)).isNotNull();
        assertThat(this.store.pending(RUN_ID, gapFormId).getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @Test
    void allSubmittedAfterDefinitionWasAddedCoversOnlyFrozenFormDefinitions() {
        PendingToolCall answered = issueHostForm();
        this.task.getItems().add(item("RATE"));
        answer(answered, "{\"conditionMode\":\"ALL\"}");

        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();

        String gapFormId = ConditionsForm.toolCallId(RUN_ID, TASK_ID, List.of("RATE"), currentDefCodes());
        assertThat(this.store.pending(RUN_ID, gapFormId)).isNotNull();
        assertThat(this.store.pending(RUN_ID, gapFormId).getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @Test
    void allWithoutProvableFrozenCoverageIsNotCollected() {
        PendingToolCall issued = issueHostForm();
        PendingToolCall unscoped = frontendCall(ConditionsForm.toolCallId(RUN_ID, TASK_ID), issued.getName(),
                issued.getArguments());
        answer(unscoped, "{\"conditionMode\":\"ALL\"}");

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);
        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.nonEmpty()).isFalse();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isFalse();
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @Test
    void allFromCurrentFullDefinitionSetParksConfirmStep() {
        this.task.getItems().add(item("RATE"));
        answer(issueHostForm(), "{\"conditionMode\":\"ALL\"}");

        assertThat(this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task).blank()).isTrue();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID).getName()).isEqualTo("confirm_step");
    }

    @Test
    void hyphenatedDefinitionCodeKeepsFrozenCoverageRoundTrip() {
        this.task = task("CUR-RENCY");
        answer(issueHostForm(), "{\"conditionMode\":\"ALL\"}");

        assertThat(this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task).blank()).isTrue();
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID).getName()).isEqualTo("confirm_step");
    }

    @Test
    void nonOkAllResultIsNotCollectedAndConfirmStepIsRejected() {
        PendingToolCall answered = issueHostForm();
        answer(answered, "{\"conditionMode\":\"ALL\"}");
        answered.setOutcome(ToolOutcome.REJECTED.wire());
        this.store.putPending(RUN_ID, answered);

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);
        ConditionsForm.ConfirmStepDecision decision = this.form.inspectConfirmStep(RUN_ID, context(),
                frontendCall("call-confirm", "confirm_step", "{}"), history());

        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.covered()).isEmpty();
        assertThat(decision.outcome()).isEqualTo(ConditionsForm.ConfirmStepDecision.Outcome.REJECTED);
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    @Test
    void latestBlankAnswersAggregateAcrossDefinitionsForBothAdvancePaths() {
        answer(issueHostForm(), "{\"conditionMode\":\"ALL\"}");
        this.task.getItems().add(item("RATE"));
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();
        PendingToolCall second = this.store.pending(RUN_ID,
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, List.of("RATE"), currentDefCodes()));
        answer(second, "{\"conditionMode\":\"ALL\"}");

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);
        ConditionsForm.ConfirmStepDecision decision = this.form.inspectConfirmStep(RUN_ID, context(),
                frontendCall("call-confirm", "confirm_step", "{}"), history());

        assertThat(feedback.covered()).containsExactlyInAnyOrder("CURRENCY", "RATE");
        assertThat(feedback.blank()).isTrue();
        assertThat(decision.outcome()).isEqualTo(ConditionsForm.ConfirmStepDecision.Outcome.ALLOW);
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID).getName()).isEqualTo("confirm_step");
    }

    @Test
    void newerNonEmptyAnswerMasksEarlierBlankForTheSameDefinition() {
        PendingToolCall older = issueHostForm();
        answer(older, "{\"conditionMode\":\"ALL\"}");
        this.task.getItems().add(item("RATE"));
        PendingToolCall newer = frontendCall(
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, currentDefCodes(), currentDefCodes()), older.getName(),
                older.getArguments());
        answer(newer, "{\"conditionMode\":\"CUSTOM\",\"conditions\":\"code = USD\"}");
        newer.setResolvedAtMs(older.getResolvedAtMs() + 1000L);
        this.store.putPending(RUN_ID, newer);

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);

        assertThat(feedback.covered()).isEmpty();
        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.nonEmpty()).isTrue();
    }

    @Test
    void newerCancellationMasksEarlierBlankForTheSameDefinition() {
        PendingToolCall older = issueHostForm();
        answer(older, "{\"conditionMode\":\"ALL\"}");
        this.task.getItems().add(item("RATE"));
        PendingToolCall newer = frontendCall(
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, currentDefCodes(), currentDefCodes()), older.getName(),
                older.getArguments());
        answer(newer, "{\"conditionMode\":\"ALL\"}");
        newer.setStatus(PendingToolCall.FRONTEND_CANCELLED);
        newer.setResolvedAtMs(older.getResolvedAtMs() + 1000L);
        this.store.putPending(RUN_ID, newer);

        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);

        assertThat(feedback.covered()).isEmpty();
        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.cancelled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void tiedBlankAndCancellationAreNotCollectedInEitherOrder(boolean reverseOrder) {
        seedTiedAnswers("{\"conditionMode\":\"ALL\"}", PendingToolCall.FRONTEND_CANCELLED, reverseOrder);

        assertTiedAnswerDoesNotAdvance();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void tiedBlankAndNonEmptyAreNotCollectedInEitherOrder(boolean reverseOrder) {
        seedTiedAnswers("{\"conditionMode\":\"CUSTOM\",\"conditions\":\"code = USD\"}",
                PendingToolCall.FRONTEND_RESULT, reverseOrder);

        assertTiedAnswerDoesNotAdvance();
    }

    @Test
    void sameToolCallIdIsIdempotentWhenFeedbackIsReadRepeatedly() {
        answer(issueHostForm(), "{\"conditionMode\":\"ALL\"}");

        ConditionsForm.ConditionFeedback first = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);
        ConditionsForm.ConditionFeedback second = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);

        assertThat(first).isEqualTo(second);
        assertThat(first.blank()).isTrue();
    }

    private void seedTiedAnswers(String secondResultText, String secondStatus, boolean reverseOrder) {
        PendingToolCall older = issueHostForm();
        answer(older, "{\"conditionMode\":\"ALL\"}");
        older.setResolvedAtMs(100L);
        older.setRequestedAtMs(0L);
        this.store.putPending(RUN_ID, older);
        this.task.getItems().add(item("RATE"));
        PendingToolCall first = frontendCall(
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, currentDefCodes(), currentDefCodes()), older.getName(),
                older.getArguments());
        PendingToolCall second = frontendCall(
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, List.of("RATE", "CURRENCY"), currentDefCodes()),
                older.getName(), older.getArguments());
        prepareTiedAnswer(first, "{\"conditionMode\":\"ALL\"}", PendingToolCall.FRONTEND_RESULT);
        prepareTiedAnswer(second, secondResultText, secondStatus);
        if (reverseOrder) {
            this.store.putPending(RUN_ID, second);
            this.store.putPending(RUN_ID, first);
        }
        else {
            this.store.putPending(RUN_ID, first);
            this.store.putPending(RUN_ID, second);
        }
    }

    private void prepareTiedAnswer(PendingToolCall pending, String resultText, String status) {
        pending.setStatus(status);
        pending.setOutcome(ToolOutcome.OK.wire());
        pending.setResultText(resultText);
        pending.setResolvedAtMs(1000L);
        pending.setRequestedAtMs(1000L);
    }

    private void assertTiedAnswerDoesNotAdvance() {
        ConditionsForm.ConditionFeedback feedback = this.form.feedbackOf(RUN_ID, TASK_ID, currentDefCodes(), this.task);
        ConditionsForm.ConfirmStepDecision decision = this.form.inspectConfirmStep(RUN_ID, context(),
                frontendCall("call-confirm", "confirm_step", "{}"), history());

        assertThat(feedback.blank()).isFalse();
        assertThat(feedback.covered()).isEmpty();
        assertThat(decision.outcome()).isEqualTo(ConditionsForm.ConfirmStepDecision.Outcome.REJECTED);
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isFalse();
        assertThat(this.store.pending(RUN_ID, "host-cond-next-" + TASK_ID)).isNull();
    }

    private JsonNode issueHostFormSchema() {
        return parse(issueHostForm().getArguments()).path("form");
    }

    private PendingToolCall issueHostForm() {
        assertThat(this.form.parkIfDueAtQueryCond(RUN_ID, context(), history())).isTrue();
        PendingToolCall pending = this.store.pending(RUN_ID,
                ConditionsForm.toolCallId(RUN_ID, TASK_ID, currentDefCodes(), currentDefCodes()));
        assertThat(pending).isNotNull();
        return pending;
    }

    private List<String> currentDefCodes() {
        return this.task.getItems().stream().map(TaskItem::getDefCode).toList();
    }

    private void answer(PendingToolCall pending, String resultText) {
        this.task.setUpdatedAt(LocalDateTime.now().minusSeconds(2));
        pending.setStatus(PendingToolCall.FRONTEND_RESULT);
        pending.setOutcome(ToolOutcome.OK.wire());
        pending.setResultText(resultText);
        pending.setResolvedAtMs(System.currentTimeMillis());
        this.store.putPending(RUN_ID, pending);
    }

    private JsonNode parse(String text) {
        try {
            return this.mapper.readTree(text);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static PendingToolCall frontendCall(String id, String name, String arguments) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(id);
        pending.setName(name);
        pending.setArguments(arguments);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        return pending;
    }

    private static AiContext context() {
        return AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", Map.of());
    }

    private static List<Message> history() {
        return List.of(new SystemMessage("PERSONA"), new UserMessage("导出配置"));
    }

    private static Task task(String defCode) {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setType(Task.TaskType.EXPORT);
        task.setCurrentStep("QUERY_COND");
        task.setStatus(Task.TaskStatus.ACTIVE);
        task.setTitle("条件模式用例");
        task.getItems().add(item(defCode));
        return task;
    }

    private static TaskItem item(String defCode) {
        TaskItem item = new TaskItem();
        item.setTaskId(TASK_ID);
        item.setDefCode(defCode);
        return item;
    }

    private static final class OrderedRunStore extends InMemoryRunStore {

        private final List<PendingToolCall> ordered = new java.util.ArrayList<>();

        @Override
        public void putPending(String runId, PendingToolCall pending) {
            super.putPending(runId, pending);
            this.ordered.removeIf(item -> item.getToolCallId().equals(pending.getToolCallId()));
            this.ordered.add(pending);
        }

        @Override
        public List<PendingToolCall> pendingsLenient(String runId) {
            return new java.util.ArrayList<>(this.ordered);
        }
    }
}
