package com.example.configmgr.excel;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-25：{@code _meta} 第 0 列写了 {@code def_code}，读取端曾只取列映射、不比对目标定义 ——
 * 把 A 配置的导出件改名/套错模板当 B 上传时，表头对不上 → 所有列变 {@code _unknown_*} 被跳过 →
 * 0 行 → 预检查 {@code totalErrors=0} 判 COMPLETED，数据在无任何报错的情况下没变。
 *
 * <p>本类只钉"{@code _meta} 存在时 def_code 必须与目标定义一致"这一条：
 * 不一致立即以既有 {@link ErrorCodes#EXCEL_COLUMN_MAPPING_INVALID} 失败。
 * 不引入"未知列即失败"或"0 行守卫"等新策略（超出本任务边界）。
 */
class ExcelReaderMetaDefCodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExcelTemplateBuilder templateBuilder = new ExcelTemplateBuilder(objectMapper);

    private final ExcelReader reader = new ExcelReader(objectMapper);

    private static ConfigDefinition definition(String code, String name, String fieldCode, String label) {
        ConfigDefinition def = new ConfigDefinition();
        def.setCode(code);
        def.setName(name);
        def.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        ConfigField field = new ConfigField();
        field.setDefCode(code);
        field.setCode(fieldCode);
        field.setLabel(label);
        field.setFieldType(ConfigField.FieldType.STRING);
        def.setFields(new ArrayList<>(List.of(field)));
        return def;
    }

    private static byte[] withDataRow(byte[] template, String sheetName, String value) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Row row = wb.getSheet(sheetName).createRow(1);
            row.createCell(0).setCellValue(value);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] withoutMetaSheet(byte[] template, String sheetName) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            int index = wb.getSheetIndex("_meta");
            if (index >= 0) wb.removeSheetAt(index);
            // _meta 被移除后，模板里剩下的 _dict 仍是隐藏表；数据表名与 def.name 一致
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void metaDefCodeMismatchFailsWithColumnMappingInvalid() throws Exception {
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        ConfigDefinition roleDict = definition("ROLE_DICT", "角色字典", "roleCode", "角色代码");
        byte[] fileOfCurrency = withDataRow(templateBuilder.build(currency), currency.getName(), "CNY");

        assertThatThrownBy(() -> reader.read(roleDict, fileOfCurrency))
                .as("A 配置的文件冒充 B：_meta.def_code=CURRENCY 与目标 ROLE_DICT 不一致必须失败")
                .isInstanceOfSatisfying(CodedIllegalArgumentException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.EXCEL_COLUMN_MAPPING_INVALID))
                .hasMessageContaining("CURRENCY")
                .hasMessageContaining("ROLE_DICT");
    }

    @Test
    void matchingMetaDefCodeStillReadsRows() throws Exception {
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        byte[] file = withDataRow(templateBuilder.build(currency), currency.getName(), "CNY");

        assertThat(reader.read(currency, file)).containsExactly(Map.of("currencyCode", "CNY"));
    }

    @Test
    void fileWithoutMetaSheetStillFallsBackToHeaderMatching() throws Exception {
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        byte[] noMeta = withoutMetaSheet(
                withDataRow(templateBuilder.build(currency), currency.getName(), "CNY"), currency.getName());
        // 无 _meta 的文件没有 def_code 可比对：沿用既有表头标签映射（不因缺少元数据而失败）
        ConfigDefinition sameShapeOtherCode =
                definition("ROLE_DICT", "角色字典", "currencyCode", "货币代码");

        assertThat(reader.read(sameShapeOtherCode, noMeta))
                .containsExactly(Map.of("currencyCode", "CNY"));
    }

    /** 改写 {@code _meta} 第 0 列的标签/值；{@code label} 为 null 时保留原标签。 */
    private static byte[] rewriteMetaFirstColumn(byte[] template, String label, String value) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            if (label != null) wb.getSheet("_meta").getRow(0).getCell(0).setCellValue(label);
            wb.getSheet("_meta").getRow(1).getCell(0).setCellValue(value);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void blankMetaDefCodeIsTreatedAsLegacyFileAndReadsRows() throws Exception {
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        byte[] file = rewriteMetaFirstColumn(
                withDataRow(templateBuilder.build(currency), currency.getName(), "CNY"), null, "");

        assertThatCode(() -> reader.read(currency, file)).doesNotThrowAnyException();
        assertThat(reader.read(currency, file))
                .as("_meta 存在但 def_code 为空串：按旧文件处理，不比对、不报错")
                .containsExactly(Map.of("currencyCode", "CNY"));
    }

    @Test
    void nonDefCodeFirstMetaColumnIsTreatedAsLegacyFile() throws Exception {
        ConfigDefinition currency = definition("CURRENCY", "货币字典", "currencyCode", "货币代码");
        ConfigDefinition roleDict = definition("ROLE_DICT", "角色字典", "roleCode", "角色代码");
        byte[] file = rewriteMetaFirstColumn(
                withDataRow(templateBuilder.build(currency), currency.getName(), "CNY"),
                "field_code", "CURRENCY");

        // 首列表头不是 def_code ⇒ 不把它当元信息列：第 0 列仍按普通数据列处理，不做 def_code 比对
        assertThatCode(() -> reader.read(roleDict, file)).doesNotThrowAnyException();
        assertThat(reader.read(roleDict, file))
                .as("首列表头非 def_code 的旧文件：不报错，第 0 列照旧参与映射")
                .containsExactly(Map.of("CURRENCY", "CNY"));
    }
}
