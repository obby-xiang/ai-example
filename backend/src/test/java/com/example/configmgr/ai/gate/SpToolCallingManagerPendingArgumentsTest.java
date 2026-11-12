package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.TaskTypeForm;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpToolCallingManagerPendingArgumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SpToolCallingManager manager;

    private ToolPreflightService preflight;

    @BeforeEach
    void setUp() {
        this.manager = new SpToolCallingManager(mock(RunStore.class), mock(RunRegistry.class), mock(ConfirmGate.class),
                mock(ToolRegistry.class), mock(MessageJsonCodec.class), mock(ToolActivityBeacon.class),
                mock(CancellationRegistry.class), new ToolResultLimiter(new AiProperties()), List.of());
        DefinitionService definitionService = mock(DefinitionService.class);
        when(definitionService.findByCode(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            if ("CURRENCY".equals(invocation.getArgument(0))) {
                return currencyDefinition();
            }
            throw new ResourceNotFoundException("unknown definition");
        });
        this.preflight = new ToolPreflightService(mock(JobService.class), mock(TaskFileRepository.class),
                mock(ConfigStagingRowRepository.class), definitionService);
    }

    @Test
    void argumentsAreByteIdenticalAcrossUserTexts() {
        String original = "{\"type\":\"EXPORT\",\"openWizard\":false,\"conditions\":{}}";
        String empty = bind(original, "").getArguments();
        assertThat(bind(original, "只创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "仅创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "不要创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "导出CONFIG_CODE").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "create an export task").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "今天天气如何").getArguments()).isEqualTo(empty);
    }

    @Test
    void missingTypeStaysOnTaskTypePath() {
        PendingToolCall pending = bind("{\"conditions\":{}}", "创建导出任务");
        assertThat(TaskTypeForm.typeMissingOrInvalid(pending.getArguments())).isTrue();
    }

    @Test
    void missingDefCodesStayForPostCreateCollection() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"conditions\":{}}", "导出货币字典");
        assertThat(read(pending.getArguments()).has("defCodes")).isFalse();
    }

    @Test
    void knownDefCodesPassPreflight() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}", "普通问答");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void unknownDefCodesFailPreflight() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[\"NOT_REAL\"],\"conditions\":{}}", "普通问答");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isFalse();
    }

    @Test
    void exportWithoutConditionsIsRejectedEvenWhenTextCouldSupplyThem() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\"}", "导出CNY货币字典");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isFalse();
    }

    @Test
    void exportEmptyConditionsRemainExplicitlyUndecided() {
        String original = "{\"type\":\"EXPORT\",\"conditions\":{}}";
        PendingToolCall pending = bind(original, "导出CNY货币字典");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void exportNonEmptyConditionsPassUnchanged() {
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void importWithoutConditionsPasses() {
        String original = "{\"type\":\"IMPORT\"}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void openWizardDefaultsToTrue() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isTrue();
    }

    @Test
    void openWizardTrueStaysTrue() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"openWizard\":true,\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isTrue();
    }

    @Test
    void openWizardFalseStaysFalse() {
        String original = "{\"type\":\"EXPORT\",\"openWizard\":false,\"conditions\":{}}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isFalse();
    }

    @Test
    void openWizardNonBooleanStaysFalse() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"openWizard\":\"yes\",\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isFalse();
    }

    private PendingToolCall bind(String arguments, String userText) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId("call-create");
        pending.setName("create_task");
        pending.setArguments(arguments);
        Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
        ReflectionTestUtils.invokeMethod(this.manager, "bindPendingArguments", "run-pending-arguments", AiContext.empty(),
                prompt, null, List.of(pending));
        return pending;
    }

    private com.fasterxml.jackson.databind.JsonNode read(String arguments) {
        try {
            return this.mapper.readTree(arguments);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ConfigDefinition currencyDefinition() {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("CURRENCY");
        definition.setName("货币字典");
        definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        ConfigField field = new ConfigField();
        field.setDefCode("CURRENCY");
        field.setCode("code");
        field.setLabel("货币代码");
        field.setKey(true);
        field.setFieldType(ConfigField.FieldType.STRING);
        definition.getFields().add(field);
        return definition;
    }
}
