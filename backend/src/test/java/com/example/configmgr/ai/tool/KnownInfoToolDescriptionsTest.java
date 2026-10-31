package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已知信息复用（2026-10-28）：工具描述与系统提示词同批收口 —— 已知配置定义/条件直接调用，
 * {@code generative_form} 只补真实缺口。
 *
 * <p><b>为什么注解与 YAML 都要断言</b>：YAML 是可选覆盖（注解是缺省），两边写反了模型看到的口径就
 * 不一样；本用例把两份描述锁在同一组关键句上，防漂移。
 *
 * <p><b>证红</b>：把 {@code select_definitions} 的「用户已明确配置定义时直接调用」删掉 ⇒ 第 1 条红；
 * {@code generative_form} 的「只用于真实信息缺口」删掉 ⇒ 第 2 条红；{@code set_condition} 的
 * 「已知条件直接使用」删掉 ⇒ 第 3 条红；版本不升 ⇒ 第 4 条红。
 */
class KnownInfoToolDescriptionsTest {

    private static String annotationDescription(String method, Class<?>... parameterTypes) throws Exception {
        Method target = AiTools.class.getMethod(method, parameterTypes);
        Tool tool = target.getAnnotation(Tool.class);
        assertNotNull(tool, method + " 必须带 @Tool");
        return tool.description();
    }

    @Test
    void selectDefinitionsDescriptionBansFormRecollectionForKnownDefinitions() throws Exception {
        String annotation = annotationDescription("selectDefinitions", String[].class, String.class);
        assertTrue(annotation.contains("用户已明确配置定义时直接调用"), annotation);
        assertTrue(annotation.contains("不用表单重复询问"), annotation);

        ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("select_definitions");
        assertNotNull(yaml);
        assertTrue(yaml.hasDescription(), "select_definitions 必须保留 YAML 描述覆盖");
        assertTrue(yaml.description().contains("用户已明确配置定义时直接调用"), yaml.description());
        assertTrue(yaml.description().contains("不用表单重复询问"), yaml.description());
        assertTrue(yaml.description().contains("SELECT_DEFS"), "步骤前置口径保留：" + yaml.description());
    }

    @Test
    void generativeFormDescriptionBansKnownInfoConfirmationAndAutoReask() throws Exception {
        String annotation = annotationDescription("generativeForm", AiTools.FormSpec.class);
        assertTrue(annotation.contains("只用于真实信息缺口"), annotation);
        assertTrue(annotation.contains("不用于已知信息"), annotation);
        assertTrue(annotation.contains("取消后自动重问"), annotation);

        ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("generative_form");
        assertNotNull(yaml);
        assertTrue(yaml.hasDescription(), "generative_form 必须保留 YAML 描述覆盖");
        assertTrue(yaml.description().contains("只用于真实信息缺口"), yaml.description());
        assertTrue(yaml.description().contains("不用于已知信息"), yaml.description());
        assertTrue(yaml.description().contains("取消后自动重问"), yaml.description());
        assertTrue(yaml.description().contains("不推进步骤"), "表单不推进步骤的口径保留：" + yaml.description());
    }

    @Test
    void generativeFormDeclaresPurposeAndSlotRelease() throws Exception {
        String annotation = annotationDescription("generativeForm", AiTools.FormSpec.class);
        assertTrue(annotation.contains("purpose"), annotation);
        assertTrue(annotation.contains("SELECT_DEFINITIONS"), annotation);
        assertTrue(annotation.contains("SET_CONDITIONS"), annotation);
        assertTrue(annotation.contains("GENERAL_CLARIFY"), annotation);

        ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("generative_form");
        assertNotNull(yaml);
        assertTrue(yaml.hasDescription(), "generative_form 必须保留 YAML 描述覆盖");
        assertTrue(yaml.description().contains("purpose"), yaml.description());
        assertTrue(yaml.description().contains("SELECT_DEFINITIONS"), yaml.description());
        assertTrue(yaml.description().contains("SET_CONDITIONS"), yaml.description());
        assertTrue(yaml.description().contains("GENERAL_CLARIFY"), yaml.description());
        assertTrue(yaml.description().contains("任务槽位"), yaml.description());
    }

    @Test
    void setConditionDescriptionReusesKnownConditionWithinStepChain() throws Exception {
        String annotation = annotationDescription("setCondition", String.class, AiTools.FieldQueryCondition.class);
        assertTrue(annotation.contains("已知条件直接使用"), annotation);
        assertTrue(annotation.contains("confirm_step"), annotation);

        ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("set_condition");
        assertNotNull(yaml);
        assertTrue(yaml.hasDescription(), "set_condition 必须保留 YAML 描述覆盖");
        assertTrue(yaml.description().contains("已知条件直接使用"), yaml.description());
        assertTrue(yaml.description().contains("QUERY_COND"), yaml.description());
        assertTrue(yaml.description().contains("confirm_step"), yaml.description());
    }

    @Test
    void knownDefinitionsIncludeAliasesAndToolConfirmedMappings() throws Exception {
        String annotation = annotationDescription("selectDefinitions", String[].class, String.class);
        assertTrue(annotation.contains("中文名称/别名"), annotation);
        assertTrue(annotation.contains("get_config_def"), annotation);

        ToolDefinitionOverride yaml = new YamlToolDefinitionSource().load().find("select_definitions");
        assertNotNull(yaml);
        assertTrue(yaml.description().contains("中文名称/别名"), yaml.description());
        assertTrue(yaml.description().contains("list_config_defs"), yaml.description());

        String formAnnotation = annotationDescription("generativeForm", AiTools.FormSpec.class);
        assertTrue(formAnnotation.contains("中文名称/别名"), formAnnotation);
        ToolDefinitionOverride form = new YamlToolDefinitionSource().load().find("generative_form");
        assertNotNull(form);
        assertTrue(form.description().contains("中文名称/别名"), form.description());
    }

    @Test
    void toolsVersionBumpedForKnownInfoDescriptions() {
        YamlToolDefinitionSource shipped = new YamlToolDefinitionSource();
        assertEquals("2026-10-27.18", shipped.version(), "改描述必须发新 version（破 prompt 缓存）");
    }
}
