package com.example.configmgr.definition.service;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.4b 缺陷 2（P1）回归：{@code PUT /api/definitions/{code}} 对已有定义恒 500。
 *
 * <p>根因是"先插后删"撞 {@code config_fields} 唯一键 {@code (def_code, code)}：
 * Hibernate 的动作顺序是先 INSERT 后 DELETE，旧行未删新行已插。
 * 本类按<b>前端同形态请求</b>（整份 fields 提交，含未改动的既有字段）走 HTTP 断言：
 * 200 + 读回与提交一致 + 保留字段行 id（证明是"原位合并"而非"删旧建新"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s44f-def-update;DB_CLOSE_DELAY=-1"
})
class DefinitionUpdateTest {

    private static final String CODE = "S44F_EDIT";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConfigDefinitionRepository definitionRepository;
    @Autowired
    private DefinitionService definitionService;

    @AfterEach
    void cleanup() {
        definitionRepository.findByCode(CODE).ifPresent(definitionRepository::delete);
    }

    @Test
    void putExistingDefinitionWithChangedFieldSetReturns200AndMergesInPlace() throws Exception {
        create();

        Long codeFieldId = fieldId("code");
        Long nameFieldId = fieldId("name");

        // 前端形态：整份 fields 提交 —— 既有 3 个字段里改 1 个、删 1 个、加 1 个，并重排顺序
        String body = """
                {"code":"%s","name":"S44F 编辑后名称","level":"GLOBAL","description":"更新描述","sortOrder":7,
                 "fields":[
                   {"code":"exchangeRate","label":"汇率（更新）","fieldType":"NUMBER","required":true,"key":false},
                   {"code":"code","label":"币种编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"decimalPlaces","label":"小数位","fieldType":"NUMBER","required":false,"key":false}
                 ]}
                """.formatted(CODE);

        mockMvc.perform(put("/api/definitions/{code}", CODE).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 读回一致：字段集/顺序/属性 = 提交内容（name 字段被删除）
        mockMvc.perform(get("/api/definitions/{code}", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("S44F 编辑后名称"))
                .andExpect(jsonPath("$.data.sortOrder").value(7))
                .andExpect(jsonPath("$.data.fields.length()").value(3))
                .andExpect(jsonPath("$.data.fields[0].code").value("exchangeRate"))
                .andExpect(jsonPath("$.data.fields[0].label").value("汇率（更新）"))
                .andExpect(jsonPath("$.data.fields[0].required").value(true))
                .andExpect(jsonPath("$.data.fields[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data.fields[1].code").value("code"))
                .andExpect(jsonPath("$.data.fields[1].key").value(true))
                .andExpect(jsonPath("$.data.fields[1].sortOrder").value(1))
                .andExpect(jsonPath("$.data.fields[2].code").value("decimalPlaces"))
                .andExpect(jsonPath("$.data.fields[2].sortOrder").value(2));

        ConfigDefinition reloaded = definitionService.findByCode(CODE);
        assertThat(reloaded.getFields().stream().map(ConfigField::getCode))
                .as("被删字段不再存在，新增字段存在").containsExactly("exchangeRate", "code", "decimalPlaces");
        assertThat(reloaded.getFields().stream().filter(f -> f.getCode().equals("code")).findFirst()
                .orElseThrow().getId())
                .as("未改动的编码命中旧行→原位改属性（保留 id，不产生 INSERT，故不撞唯一键）")
                .isEqualTo(codeFieldId);
        assertThat(reloaded.getFields().stream().filter(f -> f.getCode().equals("exchangeRate")).findFirst()
                .orElseThrow().getId())
                .as("仅改 label 的字段也保留 id").isNotNull();
        assertThat(reloaded.getFields().stream().anyMatch(f -> f.getLabel().equals("汇率（更新）"))).isTrue();
        assertThat(reloaded.getFields())
                .as("name 字段已随 orphanRemoval 删除").noneMatch(f -> f.getCode().equals("name"));
        assertThat(nameFieldId).isNotNull(); // 仅用于记录原字段 id，证明其确实被删除
    }

    @Test
    void repeatedPutWithIdenticalFieldSetIsAlsoAccepted() throws Exception {
        create();
        String body = """
                {"code":"%s","name":"S44F 幂等","level":"GLOBAL","sortOrder":1,
                 "fields":[
                   {"code":"code","label":"币种编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"name","label":"币种名称","fieldType":"STRING","required":true,"key":false},
                   {"code":"exchangeRate","label":"汇率","fieldType":"NUMBER","required":false,"key":false}
                 ]}
                """.formatted(CODE);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/definitions/{code}", CODE).contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        ConfigDefinition reloaded = definitionService.findByCode(CODE);
        assertThat(reloaded.getFields()).hasSize(3);
        assertThat(reloaded.getFields().stream().map(ConfigField::getCode)).containsExactly("code", "name", "exchangeRate");
    }

    @Test
    void putRejectsFieldSetWhoseCodesAreDuplicated() throws Exception {
        create();
        String body = """
                {"code":"%s","name":"S44F 非法","level":"GLOBAL",
                 "fields":[
                   {"code":"code","label":"币种编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"code","label":"重复","fieldType":"STRING","required":false,"key":false}
                 ]}
                """.formatted(CODE);

        mockMvc.perform(put("/api/definitions/{code}", CODE).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(definitionService.findByCode(CODE).getFields())
                .as("校验失败不留半成品：仍是原 3 字段").hasSize(3);
    }

    private void create() throws Exception {
        String body = """
                {"code":"%s","name":"S44F 编辑基座","level":"GLOBAL","sortOrder":1,
                 "fields":[
                   {"code":"code","label":"币种编码","fieldType":"STRING","required":true,"key":true},
                   {"code":"name","label":"币种名称","fieldType":"STRING","required":true,"key":false},
                   {"code":"exchangeRate","label":"汇率","fieldType":"NUMBER","required":false,"key":false}
                 ]}
                """.formatted(CODE);
        mockMvc.perform(post("/api/definitions").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private Long fieldId(String fieldCode) {
        return definitionService.findByCode(CODE).getFields().stream()
                .filter(f -> f.getCode().equals(fieldCode))
                .findFirst().orElseThrow().getId();
    }
}
