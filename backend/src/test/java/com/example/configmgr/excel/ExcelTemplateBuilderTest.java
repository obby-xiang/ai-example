package com.example.configmgr.excel;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.entity.ConfigField.FieldType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelTemplateBuilderTest {

    private final ExcelTemplateBuilder builder = new ExcelTemplateBuilder(new ObjectMapper());

    @Test
    void enumDictionaryValidationAndFieldCommentsKeepTheirStructure() throws Exception {
        ConfigField field = new ConfigField();
        field.setDefCode("DOC");
        field.setCode("status");
        field.setLabel("状态");
        field.setFieldType(FieldType.ENUM);
        field.setRequired(true);
        field.setKey(true);
        field.setRefDefCode("STATUS_DICT");
        field.setRefFieldCode("statusCode");
        field.setOptionsJson("[{\"value\":\"A\",\"label\":\"启用\"},{\"value\":\"D\",\"label\":\"停用\"}]");
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("DOC");
        definition.setName("文档");
        definition.setFields(List.of(field));

        byte[] bytes = builder.build(definition);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("_dict").getRow(0).getCell(0).getStringCellValue()).isEqualTo("status");
            assertThat(workbook.getSheet("_dict").getRow(1).getCell(0).getStringCellValue()).isEqualTo("启用");
            assertThat(workbook.getSheet("_dict").getRow(2).getCell(0).getStringCellValue()).isEqualTo("停用");
            assertThat(workbook.getSheet("文档").getRow(0).getCell(0).getCellComment().getString().getString())
                    .contains("必填: 是")
                    .contains("主键: 是")
                    .contains("引用: STATUS_DICT.statusCode");
            assertThat(workbook.getSheet("文档").getDataValidations().size()).isEqualTo(1);
        }
    }
}
