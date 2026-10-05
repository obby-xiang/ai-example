package com.example.configmgr.excel;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelWriter {

    private final ObjectMapper objectMapper;

    /**
     * Write a list of data rows to an Excel file at storagePath.
     */
    public void write(ConfigDefinition def, List<Map<String, Object>> rows, String storagePath) throws IOException {
        SXSSFWorkbook wb = new SXSSFWorkbook(100);
        try {
            Sheet sheet = wb.createSheet(def.getName());
            List<ConfigField> fields = def.getFields();

            // Header row
            Row header = sheet.createRow(0);
            CellStyle headerStyle = createHeaderStyle(wb);
            for (int c = 0; c < fields.size(); c++) {
                ConfigField f = fields.get(c);
                Cell cell = header.createCell(c);
                cell.setCellValue((f.isRequired() ? "*" : "") + f.getLabel());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(c, 20 * 256);
            }

            // Data rows
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < fields.size(); c++) {
                    ConfigField f = fields.get(c);
                    Object val = data.get(f.getCode());
                    Cell cell = row.createCell(c);
                    if (val != null) cell.setCellValue(val.toString());
                }
            }

            // _meta sheet (hidden)
            addMetaSheet(wb, fields, def.getCode());

            Path p = Path.of(storagePath);
            Files.createDirectories(p.getParent());
            try (OutputStream os = new FileOutputStream(storagePath)) {
                wb.write(os);
            }
        } finally {
            wb.dispose();
        }
    }

    private void addMetaSheet(SXSSFWorkbook wb, List<ConfigField> fields, String defCode) {
        Sheet meta = wb.createSheet("_meta");
        wb.setSheetHidden(wb.getSheetIndex("_meta"), true);
        Row labelRow = meta.createRow(0);
        Row codeRow = meta.createRow(1);
        labelRow.createCell(0).setCellValue("def_code");
        codeRow.createCell(0).setCellValue(defCode);
        for (int i = 0; i < fields.size(); i++) {
            labelRow.createCell(i + 1).setCellValue(fields.get(i).getLabel());
            codeRow.createCell(i + 1).setCellValue(fields.get(i).getCode());
        }
    }

    private CellStyle createHeaderStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
