package com.example.configmgr.excel;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelTemplateBuilder {

    private final ObjectMapper objectMapper;

    /**
     * Generate a template xlsx for a config definition.
     * Includes header row, data validation, _meta hidden sheet, _dict hidden sheet.
     */
    public byte[] build(ConfigDefinition def) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet dataSheet = wb.createSheet(def.getName());
            List<ConfigField> fields = def.getFields();

            // Header row
            XSSFRow header = dataSheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(wb);
            for (int c = 0; c < fields.size(); c++) {
                ConfigField f = fields.get(c);
                XSSFCell cell = header.createCell(c);
                cell.setCellValue((f.isRequired() ? "*" : "") + f.getLabel());
                cell.setCellStyle(headerStyle);
                dataSheet.setColumnWidth(c, 20 * 256);

                // Add comment with field description
                String comment = buildComment(f);
                if (!comment.isBlank()) {
                    XSSFDrawing drawing = dataSheet.createDrawingPatriarch();
                    XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, c, 0, c + 2, 4);
                    XSSFComment xcomment = drawing.createCellComment(anchor);
                    xcomment.setString(wb.getCreationHelper().createRichTextString(comment));
                    xcomment.setAuthor("系统");
                    cell.setCellComment(xcomment);
                }
            }

            // Add _dict sheet for ENUM options (hidden)
            addDictSheet(wb, fields);

            // Data validation for ENUM fields
            addDataValidations(wb, dataSheet, fields);

            // Add _meta sheet (hidden)
            addMetaSheet(wb, fields, def.getCode());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void addDictSheet(XSSFWorkbook wb, List<ConfigField> fields) throws Exception {
        XSSFSheet dict = wb.createSheet("_dict");
        wb.setSheetHidden(wb.getSheetIndex("_dict"), true);
        int col = 0;
        for (ConfigField f : fields) {
            if (f.getFieldType() == ConfigField.FieldType.ENUM && f.getOptionsJson() != null) {
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, String>> opts = objectMapper.readValue(f.getOptionsJson(), List.class);
                XSSFRow labelRow = dict.getRow(0);
                if (labelRow == null) labelRow = dict.createRow(0);
                labelRow.createCell(col).setCellValue(f.getCode());
                for (int i = 0; i < opts.size(); i++) {
                    XSSFRow row = dict.getRow(i + 1);
                    if (row == null) row = dict.createRow(i + 1);
                    Object val = opts.get(i).get("label");
                    if (val == null) val = opts.get(i).get("value");
                    row.createCell(col).setCellValue(val != null ? val.toString() : "");
                }
                col++;
            }
        }
    }

    private void addDataValidations(XSSFWorkbook wb, XSSFSheet dataSheet, List<ConfigField> fields) throws Exception {
        XSSFSheet dict = wb.getSheet("_dict");
        if (dict == null) return;
        XSSFDataValidationHelper dvHelper = new XSSFDataValidationHelper(dataSheet);

        int dictCol = 0;
        for (int c = 0; c < fields.size(); c++) {
            ConfigField f = fields.get(c);
            if (f.getFieldType() == ConfigField.FieldType.ENUM && f.getOptionsJson() != null) {
                @SuppressWarnings("unchecked")
                List<java.util.Map<String, String>> opts = objectMapper.readValue(f.getOptionsJson(), List.class);
                int optCount = opts.size();
                String dictSheetName = "_dict";
                String formula = "'" + dictSheetName + "'!$" + colLetter(dictCol) + "$2:$" + colLetter(dictCol) + "$" + (optCount + 1);

                CellRangeAddressList addressList = new CellRangeAddressList(1, 1000, c, c);
                XSSFDataValidationConstraint constraint = (XSSFDataValidationConstraint)
                        dvHelper.createFormulaListConstraint(formula);
                XSSFDataValidation dv = (XSSFDataValidation) dvHelper.createValidation(constraint, addressList);
                dv.setSuppressDropDownArrow(false);
                dv.setShowErrorBox(true);
                dv.createErrorBox("无效值", "请从下拉列表中选择有效值");
                dataSheet.addValidationData(dv);
                dictCol++;
            }
        }
    }

    private void addMetaSheet(XSSFWorkbook wb, List<ConfigField> fields, String defCode) {
        XSSFSheet meta = wb.createSheet("_meta");
        wb.setSheetHidden(wb.getSheetIndex("_meta"), true);
        XSSFRow labelRow = meta.createRow(0);
        XSSFRow codeRow = meta.createRow(1);
        labelRow.createCell(0).setCellValue("def_code");
        codeRow.createCell(0).setCellValue(defCode);
        for (int i = 0; i < fields.size(); i++) {
            labelRow.createCell(i + 1).setCellValue(fields.get(i).getLabel());
            codeRow.createCell(i + 1).setCellValue(fields.get(i).getCode());
        }
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String buildComment(ConfigField f) {
        StringBuilder sb = new StringBuilder();
        sb.append("类型: ").append(f.getFieldType().name());
        if (f.isRequired()) sb.append("\n必填: 是");
        if (f.isKey()) sb.append("\n主键: 是");
        if (f.getRefDefCode() != null) sb.append("\n引用: ").append(f.getRefDefCode()).append(".").append(f.getRefFieldCode());
        return sb.toString();
    }

    private static String colLetter(int col) {
        StringBuilder s = new StringBuilder();
        col++;
        while (col > 0) {
            col--;
            s.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return s.toString();
    }
}
