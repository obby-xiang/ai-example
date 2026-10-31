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

/**
 * 工具安全边界批（R3-07 / R3-13）：{@link WizardActionGuard} 的入参闸 ——
 * {@code set_condition} / {@code select_definitions} 这两个"会落服务端的工作选择写"在下发前
 * 必须过一道严格校验。
 *
 * <p>三条口径（与用户裁决一致）：
 * <ol>
 * <li><b>参数畸形一律 fail-closed</b>：非对象 / 未知顶层键 / 空 codes / 非法 mode → 不挂起、不下发；</li>
 * <li><b>未知目标一律 fail-closed</b>：{@code defCode} 或任一 code 不在定义注册表里 → 拒绝
 *     （绝不"静默成功"，否则条件/勾选会落在不存在或错的配置项上）；</li>
 * <li><b>空条件不许静默降级成"导出全部"，但"恰好空对象"是明确的"不设筛选"</b>：{@code conditions}
 *     缺失 / {@code null} / 有键但无有效条件（{@code fields:[]}、{@code scopeKeys:[]}）→ 拒绝；
 *     只有<b>恰好</b> {@code {}} 放行（前端 {@code validateConditionPayload} 落成 {@code "{}"}、
 *     {@code ConditionAssessment} 判 {@code EXPLICIT_UNSET}，三处同口径）。</li>
 * </ol>
 *
 * <p>本类只测"闸门判定"；"不挂起/不发帧"的落地行为由 {@code SpToolCallingManagerScopeGuardTest}
 * 覆盖（那里断言 {@code gate.awaitFrontendResult} 与帧都不发生）。
 */
class WizardActionGuardTest {

    private final DefinitionService definitions = mock(DefinitionService.class);

    private final TaskService tasks = mock(TaskService.class);

    private final WizardActionGuard guard = new WizardActionGuard(this.definitions);

    {
        this.guard.setTaskService(this.tasks);
        // 单条分派桩：CURRENCY / REGION 存在，其余 code 一律抛"配置定义不存在"
        when(this.definitions.findByCode(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            if ("CURRENCY".equals(code)) {
                return definition("CURRENCY", "code", "name", "status");
            }
            if ("REGION".equals(code)) {
                return definition("REGION", "code", "name");
            }
            throw new ResourceNotFoundException("配置定义 不存在");
        });
    }

    @Test
    void supportsTheTwoWizardWritesOnly() {
        assertThat(this.guard.supports("set_condition")).isTrue();
        assertThat(this.guard.supports("select_definitions")).isTrue();
        for (String other : List.of("navigate_to", "confirm_step", "undo_last_sheet_edit", "update_rows",
                "read_sheet_summary", "generative_form")) {
            assertThat(this.guard.supports(other)).as(other).isFalse();
        }
    }

    // ── 参数畸形 ─────────────────────────────────────────────────────────────

    @Test
    void rejectsMalformedJsonAndNonObject() {
        assertRejected("set_condition", "not-json", "JSON");
        assertRejected("set_condition", "[]", "对象");
        assertRejected("select_definitions", "\"CURRENCY\"", "对象");
    }

    @Test
    void rejectsUnknownTopLevelKeys() {
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]},"
                        + "\"selector\":\"#x\"}",
                "selector");
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\"],\"taskId\":7}", "taskId");
        // 明确不设不等于"参数可以随意"：空对象之外的未知键照旧拒
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{},\"step\":\"EXPORT\"}", "step");
    }

    // ── set_condition ────────────────────────────────────────────────────────

    @Test
    void acceptsAWellFormedSetCondition() {
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"],"
                        + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}").accepted())
                .isTrue();
    }

    @Test
    void acceptsAWellFormedScopeKeyOnlyCondition() {
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"]}}").accepted()).isTrue();
    }

    /**
     * 恰好空对象 {@code {}} = 明确不设筛选（用户答"无"/"不筛选"/"全部"）：前端
     * {@code validateConditionPayload} 把它落成 {@code "{}"}，{@code ConditionAssessment} 判
     * {@code EXPLICIT_UNSET}。闸门必须同口径放行 —— 否则模型对每个 defCode 各调一次
     * {@code set_condition(defCode, {})} 会被拒，{@code confirm_step} 再因"非空回灌无草案"死锁。
     */
    @Test
    void acceptsExactlyEmptyObjectAsExplicitlyUnset() {
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{}}").accepted()).isTrue();
        // 多配置逐项：每个 defCode 各一次，互不影响
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"REGION\",\"conditions\":{}}").accepted()).isTrue();
    }

    @Test
    void rejectsMissingOrStructurallyEmptyConditions() {
        // 缺失/null 仍拒绝：前端会把缺失补成 {}，来路不明的空条件不得当成"明确不设"
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\"}", "conditions");
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":null}", "conditions");
        // 有结构但没内容：空数组不可写成"明确不设"（只有恰好 {} 才是）
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[]}}", "导出全部");
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[]}}", "导出全部");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[],\"scopeKeys\":[]}}", "导出全部");
    }

    @Test
    void rejectsUnknownConditionKeyAndMalformedField() {
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}],"
                        + "\"rawSql\":\"1=1\"}}",
                "rawSql");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                "fieldCode");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"BETWEEN\",\"value\":1}]}}",
                "operator");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\"}]}}",
                "value");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":\"CNY\"}]}}",
                "数组");
    }

    @Test
    void rejectsUnknownFieldCodeAgainstTheDefinition() {
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"nope\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                "nope");
    }

    /**
     * 空值 / 空 IN / 重复字段：这三类都能"过闸后在前端压平"，压平即丢弃该字段，
     * 而没有其它条件时服务端条件被覆盖成 {@code {}} ⇒ 静默放大成全量
     * （{@code EQ ""} 原样求值只匹配空串行、{@code IN []} 原样求值是 0 行，压平后都反转成全部）。
     *
     * <p>同时锁定"合法 {@code EMPTY / NOT_EMPTY} 不变"：不带取值（即便给了空串）仍然放行。
     */
    @Test
    void rejectsBlankValuesEmptyInAndRepeatedFieldCodes() {
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"\"}]}}",
                "空串或纯空白");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"CONTAINS\",\"value\":\"   \"}]}}",
                "空串或纯空白");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[]}]}}",
                "空数组");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"CNY\",\"  \"]}]}}",
                "每一项都必须是非空字符串");
        assertRejected("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":["
                        + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"},"
                        + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"USD\"}]}}",
                "重复");
        // 合法 EMPTY / NOT_EMPTY 不变：不带值即合法（表单/模型也可能给空串，一律忽略）
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":["
                        + "{\"fieldCode\":\"code\",\"operator\":\"EMPTY\"},"
                        + "{\"fieldCode\":\"name\",\"operator\":\"NOT_EMPTY\",\"value\":\"\"}]}}").accepted())
                .isTrue();
    }

    @Test
    void rejectsUnknownDefCodeWithoutQueryingNothing() {
        assertRejected("set_condition",
                "{\"defCode\":\"NOPE\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                "NOPE");
        // 明确不设也必须有存在的目标：未知 defCode 不得静默成功
        assertRejected("set_condition", "{\"defCode\":\"NOPE\",\"conditions\":{}}", "NOPE");
        assertRejected("set_condition", "{\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":1}]}}",
                "defCode");
        assertRejected("set_condition", "{\"conditions\":{}}", "defCode");
    }

    // ── set_condition 的任务条目判据（F8：未勾选/未绑定不得假成功）──────────

    /**
     * 后台能读到任务条目时，{@code set_condition} 的目标必须是<b>本任务已勾选</b>的配置项：
     * 未勾选 ⇒ 前端会把它写成"页面上看不见的草案"（回执却成功），后端必须同判据零副作用拒绝。
     */
    @Test
    void rejectsSetConditionForADefinitionThatIsNotSelectedOnTheTask() {
        when(this.tasks.findById(7L)).thenReturn(task("CURRENCY"));

        FrontendToolGuard.Verdict verdict = this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"REGION\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"XN\"}]}}",
                exportQueryCond(7L));

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo(WizardActionGuard.REJECTED_CODE);
        assertThat(String.join("\n", verdict.reasons()))
                .as("拒绝原因必须点名未勾选的配置项并给出纠正动作")
                .contains("REGION")
                .contains("未勾选")
                .contains("select_definitions");
    }

    @Test
    void acceptsSetConditionForADefinitionSelectedOnTheTask() {
        when(this.tasks.findById(7L)).thenReturn(task("CURRENCY"));

        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                exportQueryCond(7L)).accepted()).isTrue();
        // 明确不设同样要落在已勾选条目上
        assertThat(this.guard.inspectArguments("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{}}",
                exportQueryCond(7L)).accepted()).isTrue();
    }

    @Test
    void taskWithoutItemsOrUnknownTaskKeepsShapeOnlyValidation() {
        // 还没有条目（尚未 select_definitions）/ 任务读不到：不新增判据（前端仍有同一道）
        when(this.tasks.findById(7L)).thenReturn(task());
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                exportQueryCond(7L)).accepted()).isTrue();

        when(this.tasks.findById(8L)).thenReturn(null);
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                exportQueryCond(8L)).accepted()).isTrue();
    }

    @Test
    void taskReadFailureDoesNotTurnIntoAShapeRejection() {
        when(this.tasks.findById(7L)).thenThrow(new IllegalStateException("db down"));

        // 读失败不能把一次合法调用判成"参数不合法"；前端仍是最终判据（guard 不新增误拒）
        assertThat(this.guard.inspectArguments("set_condition",
                "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                exportQueryCond(7L)).accepted()).isTrue();
    }

    // ── select_definitions ───────────────────────────────────────────────────

    @Test
    void acceptsAWellFormedSelectDefinitions() {
        assertThat(this.guard.inspectArguments("select_definitions",
                "{\"codes\":[\"CURRENCY\",\"REGION\"],\"mode\":\"ADD\"}").accepted()).isTrue();
        // defCodes 是前端动作表认的别名，不应被判成畸形
        assertThat(this.guard.inspectArguments("select_definitions", "{\"defCodes\":[\"CURRENCY\"]}").accepted())
                .isTrue();
        // 缺省 mode = REPLACE（与前端 normalizeActionArgs 同口径）
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
        assertThat(String.join("\n", verdict.reasons())).contains("NOT_A_DEF");
        // 拒绝清单里点名合法选择，模型无需猜
        assertThat(String.join("\n", verdict.reasons())).contains("CURRENCY");
    }

    @Test
    void rejectsLowercaseOrUnknownMode() {
        // 小写 add 在前端会被静默当成 REPLACE（整表替换）⇒ 必须在下发前拒
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\"],\"mode\":\"add\"}", "mode");
        assertRejected("select_definitions", "{\"codes\":[\"CURRENCY\"],\"mode\":\"MERGE\"}", "mode");
    }

    @Test
    void doesNotTouchTheRegistryWhenShapeIsAlreadyInvalid() {
        // 形状先判：codes 为空时不查定义（少一次无意义读，也避免把"空选"解释成"全部有效"）
        this.guard.inspectArguments("select_definitions", "{\"codes\":[]}");
        verify(this.definitions, never()).findByCode(anyString());
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

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

    /** 任务条目桩（{@code defCodes} 为空 = 还没有勾选任何配置项）。 */
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
