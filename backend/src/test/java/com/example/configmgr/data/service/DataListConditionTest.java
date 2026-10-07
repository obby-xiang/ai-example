package com.example.configmgr.data.service;

import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.4b 遗留 3（P2）用例：数据浏览列表端点补 {@code conditions} 入参。
 *
 * <p>口径与 {@code /count}、导出作业同源（{@link ConditionEvaluator}），故这里同时断言
 * "列表命中行数 == /count 命中行数"，防止两处口径再次分叉。
 * 用例覆盖：枚举 EQ、数字范围（GTE/LTE）、文本 LIKE/CONTAINS、IN、条件后分页、无条件回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s44f-data-conditions;DB_CLOSE_DELAY=-1"
})
class DataListConditionTest {

    private static final String DEF = "S44F_COND";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private ConfigDefinitionRepository definitionRepository;

    @BeforeEach
    void seed() {
        cleanup();
        ConfigDefinition def = new ConfigDefinition();
        def.setCode(DEF);
        def.setName("S44F 条件过滤基座");
        def.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        def.setSortOrder(999);
        def.setFields(new java.util.ArrayList<>(List.of(
                field("code", "编码", ConfigField.FieldType.STRING, true, true),
                field("orderName", "订单名", ConfigField.FieldType.STRING, true, false),
                field("category", "类别", ConfigField.FieldType.ENUM, true, false),
                field("amount", "金额", ConfigField.FieldType.NUMBER, false, false))));
        definitionRepository.save(def);

        // 6 行：类别 PURCHASE/SALES/TRANSFER 各 2 行；金额 10/20/30/40/50/60；名称 订单-1..6
        insert("S44F-K1", "订单-1", "PURCHASE", 10);
        insert("S44F-K2", "订单-2", "PURCHASE", 20);
        insert("S44F-K3", "订单-3", "SALES", 30);
        insert("S44F-K4", "订单-4", "SALES", 40);
        insert("S44F-K5", "订单-5", "TRANSFER", 50);
        insert("S44F-K6", "订单-6", "TRANSFER", 60);
    }

    @AfterEach
    void cleanup() {
        dataRowRepository.findByDefCodeOrderByRowKey(DEF).forEach(dataRowRepository::delete);
        definitionRepository.findByCode(DEF).ifPresent(definitionRepository::delete);
    }

    @Test
    void enumEqualityFiltersRowsBeforePaging() throws Exception {
        String conditions = """
                {"fields":[{"fieldCode":"category","operator":"EQ","value":"SALES"}]}
                """;

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].rowKey").value("S44F-K3"))
                .andExpect(jsonPath("$.data.content[1].rowKey").value("S44F-K4"));

        assertSameCountAsCountEndpoint(conditions, 2);
    }

    @Test
    void numberRangeFiltersRows() throws Exception {
        String conditions = """
                {"fields":[
                  {"fieldCode":"amount","operator":"GTE","value":"30"},
                  {"fieldCode":"amount","operator":"LTE","value":"50"}]}
                """;

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].rowKey").value("S44F-K3"))
                .andExpect(jsonPath("$.data.content[2].rowKey").value("S44F-K5"));

        assertSameCountAsCountEndpoint(conditions, 3);
    }

    @Test
    void textLikeAndContainsFilterRows() throws Exception {
        String like = """
                {"fields":[{"fieldCode":"orderName","operator":"LIKE","value":"订单-1"}]}
                """;
        String contains = """
                {"fields":[{"fieldCode":"orderName","operator":"CONTAINS","value":"订单-1"}]}
                """;

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", like))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rowKey").value("S44F-K1"));

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", contains))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        assertSameCountAsCountEndpoint(like, 1);
    }

    @Test
    void inOperatorAndCombinationWithScopeKey() throws Exception {
        String conditions = """
                {"fields":[{"fieldCode":"category","operator":"IN","value":["PURCHASE","TRANSFER"]}]}
                """;

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));

        // 范围键收窄（与本配置 GLOBAL 数据的 scopeKey=null 不符）→ 0 行，证明 scopeKey 仍在起作用
        mockMvc.perform(get("/api/data/{def}", DEF)
                        .param("conditions", conditions).param("scopeKey", "XN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        assertSameCountAsCountEndpoint(conditions, 4);
    }

    @Test
    void pagingAppliesAfterFiltering() throws Exception {
        String conditions = """
                {"fields":[{"fieldCode":"category","operator":"NE","value":"PURCHASE"}]}
                """;

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions)
                        .param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].rowKey").value("S44F-K3"));

        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions)
                        .param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].rowKey").value("S44F-K5"));

        // 越界页返回空内容但总数不变
        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", conditions)
                        .param("page", "9").param("size", "2"))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void withoutConditionsListStaysOnDatabasePaging() throws Exception {
        mockMvc.perform(get("/api/data/{def}", DEF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.content.length()").value(6));
    }

    @Test
    void malformedConditionsJsonIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/data/{def}", DEF).param("conditions", "{不是 JSON"))
                .andExpect(status().isBadRequest());
    }

    /** 列表端点（新）与 /count 端点（既有）在相同条件下必须同数。 */
    private void assertSameCountAsCountEndpoint(String conditions, long expected) throws Exception {
        mockMvc.perform(get("/api/data/{def}/count", DEF).param("conditions", conditions))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(expected));
    }

    private ConfigField field(String code, String label, ConfigField.FieldType type, boolean required, boolean key) {
        ConfigField f = new ConfigField();
        f.setDefCode(DEF);
        f.setCode(code);
        f.setLabel(label);
        f.setFieldType(type);
        f.setRequired(required);
        f.setKey(key);
        return f;
    }

    private void insert(String rowKey, String orderName, String category, int amount) {
        ConfigDataRow row = new ConfigDataRow();
        row.setDefCode(DEF);
        row.setScopeType("GLOBAL");
        row.setRowKey(rowKey);
        row.setDataJson("{\"code\":\"" + rowKey + "\",\"orderName\":\"" + orderName
                + "\",\"category\":\"" + category + "\",\"amount\":" + amount + "}");
        dataRowRepository.save(row);
    }
}
