package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WizardActionGuardTest {

    private final DefinitionService definitions = mock(DefinitionService.class);

    private final TaskService tasks = mock(TaskService.class);

    private final WizardActionGuard guard = new WizardActionGuard(this.definitions);

    {
        this.guard.setTaskService(this.tasks);
        when(this.definitions.findByCode(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            if ("CURRENCY".equals(code)) {
                return definition("CURRENCY", "code", "name", "status");
            }
            if ("REGION".equals(code)) {
                return definition("REGION", "code", "name");
            }
            if ("DOC_TYPE".equals(code)) {
                return definition("DOC_TYPE", "documentType");
            }
            throw new ResourceNotFoundException("配置定义不存在");
        });
    }

    @Test
    void supportsOnlySelectDefinitionsAndBatchSetConditions() {
        assertThat(this.guard.supports("set_conditions")).isTrue();
        assertThat(this.guard.supports("select_definitions")).isTrue();
        assertThat(this.guard.supports("set_condition")).isFalse();
        assertThat(this.guard.supports("generative_form")).isFalse();
        assertThat(this.guard.inspectArguments("{\"defCode\":\"CURRENCY\",\"conditions\":{}}").accepted())
                .as("无工具名不得按形状推断")
                .isFalse();
    }

    @Test
    void acceptsDifferentItemsAndExplicitlyUnsetConditions() {
        String args = "{\"items\":["
                + "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"],\"fields\":["
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}},"
                + "{\"defCode\":\"DOC_TYPE\",\"conditions\":{\"fields\":["
                + "{\"fieldCode\":\"documentType\",\"operator\":\"EQ\",\"value\":\"OFFICIAL\"}]}},"
                + "{\"defCode\":\"REGION\",\"conditions\":{}}]}";

        assertThat(this.guard.inspectArguments("set_conditions", args).accepted()).isTrue();
        assertThat(this.guard.inspectArguments("set_conditions",
                "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"]}}]}").accepted())
                .isTrue();
        assertThat(this.guard.inspectArguments("set_conditions",
                "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":["
                        + "{\"fieldCode\":\"code\",\"operator\":\"EMPTY\"},"
                        + "{\"fieldCode\":\"name\",\"operator\":\"NOT_EMPTY\",\"value\":\"\"}]}}]}").accepted())
                .isTrue();
    }

    @Test
    void rejectsEmptyDuplicateMissingAndLegacyShapes() {
        assertRejected("set_conditions", "{\"items\":[]}", "不能为空");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}},"
                + "{\"defCode\":\"CURRENCY\",\"conditions\":{}}]}", "重复");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\"}]}", "conditions");
        assertRejected("set_conditions", "{\"defCode\":\"CURRENCY\",\"conditions\":{}}", "items");
        assertRejected("set_conditions", "not-json", "JSON");
        assertRejected("set_conditions", "[]", "对象");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{},"
                + "\"selector\":\"#x\"}]}", "selector");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}}],"
                + "\"step\":\"EXPORT\"}", "step");
    }

    @Test
    void rejectsStructurallyEmptyAndUnknownConditionParts() {
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\"}]}", "conditions");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":null}]}",
                "conditions");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[]}}]}",
                "导出全部");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[]}}]}",
                "导出全部");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}],"
                + "\"rawSql\":\"1=1\"}}]}", "rawSql");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"operator\":\"EQ\",\"value\":\"CNY\"}]}}]}", "fieldCode");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"BETWEEN\",\"value\":1}]}}]}", "operator");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\"}]}}]}", "value");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":\"CNY\"}]}}]}", "数组");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"nope\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}]}", "nope");
    }

    @Test
    void rejectsBlankValuesEmptyInAndRepeatedFieldCodes() {
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"\"}]}}]}", "空串或纯空白");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"CONTAINS\",\"value\":\"   \"}]}}]}",
                "空串或纯空白");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[]}]}}]}", "空数组");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"  \"]}]}}]}",
                "每一项都必须是非空字符串");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"},"
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"USD\"}]}}]}", "重复");
    }

    @Test
    void rejectsUnknownDefCode() {
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"NOPE\",\"conditions\":{}}]}", "NOPE");
        assertRejected("set_conditions", "{\"items\":[{\"defCode\":\"NOPE\",\"conditions\":{"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}]}", "NOPE");
        assertRejected("set_conditions", "{\"items\":[{}]}", "defCode");
    }

    @Test
    void rejectsWholeBatchWhenAnyDefinitionIsNotSelected() {
        when(this.tasks.findById(7L)).thenReturn(task("CURRENCY"));
        String args = "{\"items\":["
                + "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":["
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}},"
                + "{\"defCode\":\"REGION\",\"conditions\":{\"fields\":["
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"XN\"}]}}]}";

        FrontendToolGuard.Verdict verdict = this.guard.inspectArguments("set_conditions", args, exportQueryCond(7L));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
        assertThat(String.join("\n", verdict.reasons()))
                .contains("REGION")
                .contains("未勾选")
                .contains("select_definitions");
    }

    @Test
    void acceptsBatchWhenEveryDefinitionIsSelected() {
        when(this.tasks.findById(7L)).thenReturn(task("CURRENCY", "REGION"));
        String args = "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}},"
                + "{\"defCode\":\"REGION\",\"conditions\":{}}]}";

        assertThat(this.guard.inspectArguments("set_conditions", args, exportQueryCond(7L)).accepted()).isTrue();
    }

    @Test
    void rejectsBatchWhenTaskIsMissingOrHasNoSelectedItems() {
        when(this.tasks.findById(7L)).thenReturn(task());
        assertRejectedWithContext(7L);
        when(this.tasks.findById(8L)).thenReturn(null);
        assertRejectedWithContext(8L);
    }

    @Test
    void rejectsBatchWhenTaskSelectionCannotBeRead() {
        when(this.tasks.findById(7L)).thenThrow(new IllegalStateException("db down"));

        assertRejectedWithContext(7L);
    }

    @Test
    void rejectsBatchWhenTaskServiceIsMissingButTaskContextExists() {
        WizardActionGuard withoutTasks = new WizardActionGuard(this.definitions);

        assertThat(withoutTasks.inspectArguments("set_conditions",
                "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}}]}", exportQueryCond(7L)).accepted())
                .isFalse();
    }

    @Test
    void rejectsRealFrontendCallWhenContextOrTaskIdIsMissing() {
        WizardActionGuard withoutTasks = new WizardActionGuard(this.definitions);
        String args = "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}}]}";
        AiContext withoutTaskId = AiContext.of("export", "EXPORT", "QUERY_COND", null, "ACTIVE", Map.of());

        assertThat(withoutTasks.inspectArguments("set_conditions", args, null).accepted()).isFalse();
        assertThat(withoutTasks.inspectArguments("set_conditions", args, withoutTaskId).accepted()).isFalse();
    }

    @Test
    void acceptsShapeOnlyProbeWithoutTaskContextOrTaskService() {
        WizardActionGuard withoutTasks = new WizardActionGuard(this.definitions);

        assertThat(withoutTasks.inspectArgumentsFor("set_conditions",
                "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}}]}").accepted()).isTrue();
    }

    private void assertRejectedWithContext(long taskId) {
        FrontendToolGuard.Verdict verdict = this.guard.inspectArguments("set_conditions",
                "{\"items\":[{\"defCode\":\"CURRENCY\",\"conditions\":{}}]}", exportQueryCond(taskId));
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
        assertThat(String.join("\n", verdict.reasons())).contains("任务");
    }

    @Test
    void acceptsAWellFormedSelectDefinitions() {
        assertThat(this.guard.inspectArguments("select_definitions",
                "{\"codes\":[\"CURRENCY\",\"REGION\"],\"mode\":\"ADD\"}").accepted()).isTrue();
        assertThat(this.guard.inspectArguments("select_definitions", "{\"defCodes\":[\"CURRENCY\"]}").accepted())
                .isTrue();
        assertThat(this.guard.inspectArguments("select_definitions", "{\"codes\":[\"CURRENCY\"]}").accepted())
                .isTrue();
    }

    @Test
    void rejectsEmptyOrNonArrayCodes() {
        assertRejected("select_definitions", "{}", "codes");
        assertRejected("select_definitions", "{\"codes\":[]}", "codes");
        assertRejected("select_definitions", "{\"codes\":\"CURRENCY\"}", "codes");
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\",\"  \"]}", "codes");
    }

    @Test
    void rejectsUnknownCodeInTheSelection() {
        FrontendToolGuard.Verdict verdict = this.guard.inspectArguments("select_definitions",
                "{\"codes\":[\"CURRENCY\",\"NOT_A_DEF\"]}");
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
        assertThat(String.join("\n", verdict.reasons())).contains("NOT_A_DEF").contains("CURRENCY");
    }

    @Test
    void rejectsLowercaseOrUnknownMode() {
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\"],\"mode\":\"add\"}", "mode");
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\"],\"mode\":\"MERGE\"}", "mode");
    }

    @Test
    void doesNotTouchTheRegistryWhenShapeIsAlreadyInvalid() {
        this.guard.inspectArguments("select_definitions", "{\"codes\":[]}");
        verify(this.definitions, never()).findByCode(anyString());
    }

    private void assertRejected(String tool, String args, String expectedReasonFragment) {
        FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(tool, args);
        assertThat(verdict.accepted()).as("%s 应被拒：%s", tool, args).isFalse();
        assertThat(verdict.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
        assertThat(String.join("\n", verdict.reasons())).as("%s 的拒绝原因", args)
                .contains(expectedReasonFragment);
    }

    private static AiContext exportQueryCond(long taskId) {
        return AiContext.of("export", "EXPORT", "QUERY_COND", taskId, "ACTIVE", Map.of());
    }

    private static Task task(String... defCodes) {
        Task task = new Task();
        task.setId(7L);
        task.setType(Task.TaskType.EXPORT);
        task.setCurrentStep("QUERY_COND");
        task.setStatus(Task.TaskStatus.ACTIVE);
        List<TaskItem> items = new java.util.ArrayList<>();
        for (String defCode : defCodes) {
            TaskItem item = new TaskItem();
            item.setTaskId(7L);
            item.setDefCode(defCode);
            items.add(item);
        }
        task.setItems(items);
        return task;
    }

    private static ConfigDefinition definition(String code, String... fieldCodes) {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode(code);
        definition.setName(code);
        definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        List<ConfigField> fields = new java.util.ArrayList<>();
        for (String fieldCode : fieldCodes) {
            ConfigField field = new ConfigField();
            field.setDefCode(code);
            field.setCode(fieldCode);
            field.setLabel(fieldCode);
            field.setFieldType(ConfigField.FieldType.STRING);
            fields.add(field);
        }
        definition.setFields(fields);
        return definition;
    }
}
