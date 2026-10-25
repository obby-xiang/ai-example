package com.example.configmgr.ai.tool;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import org.junit.jupiter.api.Test;

import java.util.List;

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
 * <li><b>空条件不许静默降级成"导出全部"</b>：{@code conditions} 缺失或既无 {@code scopeKeys}
 *     又无 {@code fields} → 拒绝（前端会补 {@code {}}，那正是"工具成功、条件却是空的"的来源）。</li>
 * </ol>
 *
 * <p>本类只测"闸门判定"；"不挂起/不发帧"的落地行为由 {@code SpToolCallingManagerScopeGuardTest}
 * 覆盖（那里断言 {@code gate.awaitFrontendResult} 与帧都不发生）。
 */
class WizardActionGuardTest {

    private final DefinitionService definitions = mock(DefinitionService.class);

    private final WizardActionGuard guard = new WizardActionGuard(this.definitions);

    {
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

    @Test
    void rejectsMissingOrEmptyConditions() {
        // 前端把缺失的 conditions 补成 {} ⇒ 条件为空 ⇒ 导出全部（静默放大范围），必须在下发前拒
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\"}", "conditions");
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{}}", "导出全部");
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{\"fields\":[]}}", "导出全部");
        assertRejected("set_condition", "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[]}}", "导出全部");
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

    @Test
    void rejectsUnknownDefCodeWithoutQueryingNothing() {
        assertRejected("set_condition",
                "{\"defCode\":\"NOPE\",\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}",
                "NOPE");
        assertRejected("set_condition", "{\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":1}]}}",
                "defCode");
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
