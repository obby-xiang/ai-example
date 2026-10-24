package com.example.configmgr.excel;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelReader {

    private final ObjectMapper objectMapper;

    /**
     * Read rows from an Excel file. Returns list of data maps keyed by field code.
     * Reads _meta sheet first to determine column mapping; falls back to header text matching.
     */
    public List<Map<String, Object>> read(ConfigDefinition def, byte[] fileBytes) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(fileBytes))) {
            // Try to find column mapping from _meta sheet
            MetaSheet meta = readMetaSheet(wb);
            List<String> fieldCodes = meta == null ? null : meta.fieldCodes();
            if (meta != null) {
                requireMetaDefCode(def, meta.defCode());
            }
            if (fieldCodes == null) {
                fieldCodes = readHeaderRow(wb, def);
            }
            if (fieldCodes == null || fieldCodes.isEmpty()) {
                throw new CodedIllegalArgumentException(ErrorCodes.EXCEL_COLUMN_MAPPING_INVALID, "无法解析文件列映射，请确认文件格式正确");
            }

            // Read data sheet (first non-hidden, non-meta sheet)
            Sheet dataSheet = findDataSheet(wb);
            if (dataSheet == null) {
                throw new CodedIllegalArgumentException(ErrorCodes.EXCEL_SHEET_NOT_FOUND, "未找到数据工作表");
            }

            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();

            // Start from row 1 (skip header row 0)
            for (int r = 1; r <= dataSheet.getLastRowNum(); r++) {
                Row row = dataSheet.getRow(r);
                if (row == null || isRowEmpty(row, formatter)) continue;

                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int c = 0; c < fieldCodes.size(); c++) {
                    String code = fieldCodes.get(c);
                    if (code == null || code.isBlank() || code.startsWith("_")) continue;
                    Cell cell = row.getCell(c);
                    Object value = getCellValue(cell, formatter, evaluator);
                    if (value != null) {
                        rowData.put(code, value);
                    }
                }
                if (!rowData.isEmpty()) {
                    result.add(rowData);
                }
            }
        }
        return result;
    }

    /**
     * 读取 {@code _meta}：def_code（旧文件可能缺）+ 数据列映射。
     *
     * <p>R3-25：第 0 列本来就是为了"这份文件属于哪个配置定义"写的，读取端必须把它交给
     * {@link #requireMetaDefCode} 比对，而不是只跳过后静默丢掉。
     */
    private MetaSheet readMetaSheet(XSSFWorkbook wb) {
        Sheet meta = wb.getSheet("_meta");
        if (meta == null) return null;
        Row row = meta.getRow(1); // row 0 = headers, row 1 = values
        if (row == null) return null;
        DataFormatter fmt = new DataFormatter();
        // 模板第 0 列是 def_code 元信息列（row0 标签为 "def_code"），跳过以保证与数据列对齐
        Row labelRow = meta.getRow(0);
        boolean firstIsDefCode = labelRow != null
                && "def_code".equals(fmt.formatCellValue(labelRow.getCell(0)).trim());
        String defCode = firstIsDefCode ? fmt.formatCellValue(row.getCell(0)).trim() : null;
        List<String> codes = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            if (c == 0 && firstIsDefCode) continue;
            codes.add(fmt.formatCellValue(row.getCell(c)).trim());
        }
        return new MetaSheet(codes, defCode);
    }

    /**
     * R3-25：{@code _meta.def_code} 必须与本次读取的目标定义一致 —— A 配置的文件改名/套错模板
     * 当 B 上传时立即失败，而不是把对不上的列全丢成 {@code _unknown_*}、0 行后判成功。
     * 没有 {@code _meta} 或旧文件未写 def_code 时不做比对（沿用既有表头映射回退）。
     */
    private void requireMetaDefCode(ConfigDefinition def, String metaDefCode) {
        if (metaDefCode == null || metaDefCode.isBlank()) return;
        if (!metaDefCode.equals(def.getCode())) {
            throw new CodedIllegalArgumentException(ErrorCodes.EXCEL_COLUMN_MAPPING_INVALID,
                    "文件配置定义不匹配：文件为 " + metaDefCode + "，当前目标为 " + def.getCode()
                            + "，请确认上传的文件与配置项一致");
        }
    }

    /** {@code _meta} 的读取结果：def_code（可能缺失）+ 数据列映射。 */
    private record MetaSheet(List<String> fieldCodes, String defCode) {
    }

    private List<String> readHeaderRow(XSSFWorkbook wb, ConfigDefinition def) {
        Sheet dataSheet = findDataSheet(wb);
        if (dataSheet == null) return null;
        Row header = dataSheet.getRow(0);
        if (header == null) return null;

        // Build label -> code map
        Map<String, String> labelToCode = new HashMap<>();
        for (ConfigField f : def.getFields()) {
            labelToCode.put(f.getLabel().replace("*", "").trim(), f.getCode());
            labelToCode.put(f.getCode(), f.getCode());
        }

        DataFormatter fmt = new DataFormatter();
        List<String> codes = new ArrayList<>();
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String label = fmt.formatCellValue(header.getCell(c)).replace("*", "").trim();
            codes.add(labelToCode.getOrDefault(label, "_unknown_" + c));
        }
        return codes;
    }

    private Sheet findDataSheet(XSSFWorkbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = wb.getSheetName(i);
            if (!name.startsWith("_") && !wb.isSheetHidden(i)) {
                return wb.getSheetAt(i);
            }
        }
        return wb.getSheetAt(0);
    }

    private Object getCellValue(Cell cell, DataFormatter fmt, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        String s = fmt.formatCellValue(cell, evaluator).trim();
        return s.isEmpty() ? null : s;
    }

    private boolean isRowEmpty(Row row, DataFormatter fmt) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (!fmt.formatCellValue(row.getCell(c)).isBlank()) return false;
        }
        return true;
    }
}
