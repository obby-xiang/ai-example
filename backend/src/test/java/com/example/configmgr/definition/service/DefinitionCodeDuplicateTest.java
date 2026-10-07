package com.example.configmgr.definition.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 收尾守卫④（S5.b §6.2 待裁决 #2 裁决"采纳"）的回归用例：
 * <b>重复编码创建定义 → 409 + 机器可读码 {@code DEFINITION_CODE_DUPLICATE}</b>，
 * 不再返回 500 且不再回显原始 JDBC/SQL 报文。
 *
 * <p>实测原状（S5.b §6.2）：唯一约束命中抛 {@code DataIntegrityViolationException} → 全局处理器
 * 的 500 分支，报文含 {@code insert into config_definitions ... [23505-232]}。
 * 现改为 {@link DefinitionService#save} 的前置校验（与 {@code delete} 的 409 口径一致）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5f-definition-duplicate;DB_CLOSE_DELAY=-1"
})
class DefinitionCodeDuplicateTest {

    private static final String CODE = "S5F_DUP_DEF";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DefinitionService definitionService;

    @Test
    void duplicateCodeIsRejectedAs409WithoutSqlLeak() throws Exception {
        String body = definitionJson(CODE, "S5F 重复编码");

        mockMvc.perform(post("/api/definitions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult dup = mockMvc.perform(post("/api/definitions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DEFINITION_CODE_DUPLICATE"))
                .andExpect(jsonPath("$.message").value(containsString(CODE)))
                .andReturn();

        String text = dup.getResponse().getContentAsString();
        assertThat(text).as("响应体不得泄漏 SQL/JDBC 报文")
                .doesNotContainIgnoringCase("insert into")
                .doesNotContain("Unique index")
                .doesNotContain("23505")
                .doesNotContain("could not execute statement");

        // 原定义零变化（未被重复创建覆盖）
        ConfigDefinition stored = definitionService.findByCode(CODE);
        assertThat(stored.getName()).isEqualTo("S5F 重复编码");
        assertThat(stored.getFields()).hasSize(1);
    }

    @Test
    void serviceLayerThrowsConflictExceptionWithCode() {
        ConfigDefinition def = definition(CODE + "_SVC", "S5F 服务层重复编码");
        assertThat(definitionService.save(def).getCode()).isEqualTo(CODE + "_SVC");

        ConfigDefinition again = definition(CODE + "_SVC", "S5F 服务层重复编码-第二次");
        assertThatThrownBy(() -> definitionService.save(again))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode())
                        .isEqualTo("DEFINITION_CODE_DUPLICATE"));
    }

    @Test
    void newCodeStillCreatesFine() throws Exception {
        mockMvc.perform(post("/api/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(definitionJson(CODE + "_NEW", "S5F 新编码")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value(CODE + "_NEW"));

        mockMvc.perform(get("/api/definitions/{code}", CODE + "_NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(CODE + "_NEW"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private String definitionJson(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"level\":\"GLOBAL\",\"sortOrder\":98,"
                + "\"fields\":[{\"code\":\"k\",\"label\":\"主键\",\"fieldType\":\"STRING\",\"required\":true,\"key\":true}]}";
    }

    private ConfigDefinition definition(String code, String name) {
        ConfigField key = new ConfigField();
        key.setCode("k");
        key.setLabel("主键");
        key.setFieldType(ConfigField.FieldType.STRING);
        key.setRequired(true);
        key.setKey(true);

        ConfigDefinition def = new ConfigDefinition();
        def.setCode(code);
        def.setName(name);
        def.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        def.setSortOrder(98);
        def.setFields(new ArrayList<>(List.of(key)));
        return def;
    }
}
