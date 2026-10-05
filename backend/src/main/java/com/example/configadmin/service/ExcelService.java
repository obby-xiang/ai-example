package com.example.configadmin.service;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.Issue;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.Level;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.*;

/**
 * Excel 能力（POI 实现，与前端 SpreadJS 通过 xlsx 格式互通）：
 * - 模板生成：说明 sheet + 数据 sheet（表头+示例行+下拉数据验证）
 * - 导出文件生成：模板 + 查询结果行
 * - 上传解析：表头归一化匹配（中文名或编码）、单元格值规范化、未知列警告
 * - 错误明细文件生成
 */
@Component
public class ExcelService {

    /** 说明 sheet 名与识别关键字 */
    public static final String INFO_SHEET = "说明";
    public static final String DATA_SHEET = "数据";
    public static final String SCOPE_HEADER = "范围";

    /** 下拉验证覆盖的数据行范围（模板预留空行数） */
    private static final int TEMPLATE_ROWS = 500;

    private final ConfigDefService defService;

    public ExcelService(ConfigDefService defService) {
        this.defService = defService;
    }

    // ==================== 模板 / 导出 ====================

    /** 生成模板工作簿（含说明 sheet、数据 sheet 表头+示例行、下拉验证）。 */
    public XSSFWorkbook buildTemplate(ConfigDef def, List<FieldDef> fields) {
        XSSFWorkbook wb = new XSSFWorkbook();
        buildInfoSheet(wb, def, fields);
        Sheet data = wb.createSheet(DATA_SHEET);
        writeHeadersAndValidation(wb, data, def, fields, true);
        return wb;
    }

    /** 生成导出工作簿：模板结构 + 查询结果行。rows 为已归一化数据（含 __scope__）。 */
    public XSSFWorkbook buildExport(ConfigDef def, List<FieldDef> fields, List<Map<String, Object>> rows) {
        XSSFWorkbook wb = buildTemplate(def, fields);
        Sheet data = wb.getSheet(DATA_SHEET);
        int r = 1;
        CellStyle textStyle = wb.createCellStyle();
        textStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        for (Map<String, Object> row : rows) {
            Row excelRow = data.createRow(r++);
            writeDataRow(data, excelRow, def, fields, row, textStyle, r);
        }
        return wb;
    }

    /** 生成错误明细工作簿。 */
    public XSSFWorkbook buildErrors(String defName, List<Issue> issues) {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("错误明细");
        String[] headers = {"行号", "字段", "级别", "问题描述", "原值"};
        Row h = s.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
            s.setColumnWidth(i, 20 * 256);
        }
        int r = 1;
        for (Issue issue : issues) {
            Row row = s.createRow(r++);
            row.createCell(0).setCellValue(issue.getRowIndex());
            row.createCell(1).setCellValue(issue.getField());
            row.createCell(2).setCellValue("WARN".equals(issue.getLevel()) ? "警告" : "错误");
            row.createCell(3).setCellValue(issue.getMessage());
            row.createCell(4).setCellValue(issue.getValue() == null ? "" : issue.getValue());
        }
        return wb;
    }

    /** 写表头 + 示例行 + 下拉数据验证。 */
    private void writeHeadersAndValidation(XSSFWorkbook wb, Sheet data, ConfigDef def,
                                           List<FieldDef> fields, boolean withExample) {
        Row header = data.createRow(0);
        int col = 0;
        if (def.getLevel() != Level.GLOBAL) {
            header.createCell(col).setCellValue(SCOPE_HEADER);
            data.setColumnWidth(col, 16 * 256);
            col++;
        }
        Map<String, Integer> colOfField = new LinkedHashMap<>();
        for (FieldDef f : fields) {
            header.createCell(col).setCellValue(f.getLabel());
            data.setColumnWidth(col, 18 * 256);
            colOfField.put(f.getCode(), col);
            col++;
        }

        // 下拉数据验证：隐藏选项 sheet，规避 Excel List 公式 255 字符上限
        for (FieldDef f : fields) {
            if (f.getType() == FieldDef.FieldType.SELECT && f.getOptions() != null && !f.getOptions().isEmpty()) {
                String sheetName = "选项_" + f.getCode();
                Sheet opt = wb.createSheet(sheetName);
                for (int i = 0; i < f.getOptions().size(); i++) {
                    opt.createRow(i).createCell(0).setCellValue(f.getOptions().get(i));
                }
                int wbIdx = wb.getSheetIndex(opt);
                wb.setSheetHidden(wbIdx, true);

                DataValidationHelper helper = data.getDataValidationHelper();
                DataValidationConstraint constraint = helper.createFormulaListConstraint(
                        "'" + sheetName + "'!$A$1:$A$" + f.getOptions().size());
                int colIdx = colOfField.get(f.getCode());
                CellRangeAddressList regions = new CellRangeAddressList(2, TEMPLATE_ROWS + 1, colIdx, colIdx);
                DataValidation dv = helper.createValidation(constraint, regions);
                dv.setShowErrorBox(true);
                dv.createErrorBox("取值错误", "请从下拉列表中选择");
                dv.setSuppressDropDownArrow(false);
                data.addValidationData(dv);
            }
        }

        if (withExample) {
            Row example = data.createRow(1);
            Map<String, Object> sample = new LinkedHashMap<>();
            if (def.getLevel() != Level.GLOBAL) {
                sample.put("__scope__", def.getLevel() == Level.REGION ? "华东区" : "P1001");
            }
            for (FieldDef f : fields) {
                sample.put(f.getCode(), exampleValue(f));
            }
            writeDataRow(data, example, def, fields, sample, null, 0);
        }
    }

    private Object exampleValue(FieldDef f) {
        if (f.getDefaultValue() != null && !f.getDefaultValue().isBlank()) {
            return f.getDefaultValue();
        }
        return switch (f.getType()) {
            case NUMBER -> f.getMin() != null ? f.getMin() : 1;
            case BOOLEAN -> true;
            case DATE -> LocalDate.now().toString();
            case SELECT -> f.getOptions().isEmpty() ? "示例" : f.getOptions().get(0);
            case REFERENCE -> "（填写引用配置中已存在的值）";
            case TEXTAREA, TEXT -> "示例";
        };
    }

    /** 写一行数据（sample 未归一化时按原值写；导出数据已归一化）。 */
    private void writeDataRow(Sheet sheet, Row row, ConfigDef def, List<FieldDef> fields,
                              Map<String, Object> data, CellStyle dateStyle, int excelRowNo) {
        int col = 0;
        if (def.getLevel() != Level.GLOBAL) {
            setCellValue(row.createCell(col++), data.get("__scope__"), dateStyle);
        }
        for (FieldDef f : fields) {
            setCellValue(row.createCell(col++), data.get(f.getCode()), dateStyle);
        }
    }

    private void setCellValue(Cell cell, Object v, CellStyle dateStyle) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (v instanceof LocalDate d) {
            if (dateStyle != null) cell.setCellStyle(dateStyle);
            cell.setCellValue(d.toString());
        } else {
            cell.setCellValue(String.valueOf(v));
        }
    }

    /** 说明 sheet：列定义、依赖、导入须知。 */
    private void buildInfoSheet(XSSFWorkbook wb, ConfigDef def, List<FieldDef> fields) {
        Sheet info = wb.createSheet(INFO_SHEET);
        info.setColumnWidth(0, 22 * 256);
        info.setColumnWidth(1, 60 * 256);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"配置编码", def.getCode()});
        lines.add(new String[]{"配置名称", def.getName()});
        lines.add(new String[]{"层级", levelName(def.getLevel())});
        lines.add(new String[]{"依赖配置", defService.parseDependsOn(def).isEmpty()
                ? "无" : String.join(", ", defService.parseDependsOn(def))});
        lines.add(new String[]{"", ""});
        lines.add(new String[]{"【列定义】", ""});
        lines.add(new String[]{"表头(字段编码)", "类型 / 必填 / 说明"});
        if (def.getLevel() != Level.GLOBAL) {
            lines.add(new String[]{"范围", "文本，必填。填写本行数据所属的" + levelName(def.getLevel()) + "名称"});
        }
        for (FieldDef f : fields) {
            StringBuilder desc = new StringBuilder();
            desc.append(typeName(f.getType()));
            if (f.isRequired()) desc.append("，必填");
            if (f.getType() == FieldDef.FieldType.SELECT) {
                desc.append("，选项：").append(String.join("/", f.getOptions()));
            }
            if (f.getType() == FieldDef.FieldType.NUMBER) {
                if (f.getMin() != null || f.getMax() != null) {
                    desc.append("，范围：").append(f.getMin() == null ? "-∞" : f.getMin())
                            .append(" ~ ").append(f.getMax() == null ? "+∞" : f.getMax());
                }
            }
            if (f.getType() == FieldDef.FieldType.REFERENCE) {
                desc.append("，必须存在于配置 ").append(f.getRefDefCode())
                        .append(" 的字段 ").append(f.getRefFieldCode()).append(" 的取值中");
            }
            if (f.getType() == FieldDef.FieldType.DATE) desc.append("，格式 yyyy-MM-dd");
            if (f.getType() == FieldDef.FieldType.BOOLEAN) desc.append("，填写 true/false 或 是/否");
            lines.add(new String[]{f.getLabel() + "(" + f.getCode() + ")", desc.toString()});
        }
        lines.add(new String[]{"", ""});
        lines.add(new String[]{"【导入须知】", ""});
        lines.add(new String[]{"1", "请勿修改表头行；“说明”表可删除。"});
        lines.add(new String[]{"2", "文件名需为 “" + def.getCode() + ".xlsx”（或带序号后缀，如 " + def.getCode() + "(1).xlsx），用于自动匹配配置。"});
        lines.add(new String[]{"3", "上传到系统后会先执行“检查”，检查通过才能导入；导入后需“发布”才对外生效。"});

        int r = 0;
        for (String[] line : lines) {
            Row row = info.createRow(r++);
            row.createCell(0).setCellValue(line[0]);
            row.createCell(1).setCellValue(line[1]);
        }
    }

    private String typeName(FieldDef.FieldType t) {
        return switch (t) {
            case TEXT -> "文本";
            case TEXTAREA -> "长文本";
            case NUMBER -> "数字";
            case BOOLEAN -> "布尔";
            case DATE -> "日期";
            case SELECT -> "下拉选择";
            case REFERENCE -> "引用";
        };
    }

    private String levelName(Level l) {
        return switch (l) {
            case GLOBAL -> "全局";
            case REGION -> "地区";
            case PROJECT -> "项目";
        };
    }

    // ==================== 解析（导入） ====================

    public record ParseResult(List<Map<String, Object>> rows, List<Issue> issues) {
        public ParseResult() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * 解析上传的 xlsx。
     * - 定位“数据”sheet（跳过说明/选项隐藏表）；
     * - 表头行按 中文名 或 字段编码 归一化匹配，支持“范围”列；
     * - 数值/日期/布尔单元格规范化输出（布尔先转原始文本，由校验引擎再归一）。
     */
    public ParseResult parse(InputStream in, ConfigDef def, List<FieldDef> fields) throws IOException {
        ParseResult result = new ParseResult();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet data = locateDataSheet(wb, def);
            if (data == null) {
                result.issues().add(new Issue("ERROR", 0, "-", "未找到“" + DATA_SHEET + "”工作表", ""));
                return result;
            }
            int firstRow = data.getFirstRowNum();
            if (data.getLastRowNum() < firstRow) {
                result.issues().add(new Issue("ERROR", 0, "-", "工作表为空", ""));
                return result;
            }
            Row header = data.getRow(firstRow);
            // 表头 → 列索引映射
            Map<String, Integer> colOfField = new LinkedHashMap<>();
            Integer scopeCol = null;
            Set<String> unknownHeaders = new LinkedHashSet<>();
            for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                if (cell == null) continue;
                String h = cellString(cell).trim();
                if (h.isEmpty()) continue;
                if (isScopeHeader(h)) {
                    scopeCol = c;
                    continue;
                }
                FieldDef match = null;
                for (FieldDef f : fields) {
                    if (h.equals(f.getLabel()) || h.equalsIgnoreCase(f.getCode())
                            || h.replace("（", "(").replace("）", ")")
                            .equals(f.getLabel() + "(" + f.getCode() + ")")) {
                        match = f;
                        break;
                    }
                }
                if (match != null) {
                    colOfField.put(match.getCode(), c);
                } else {
                    unknownHeaders.add(h);
                }
            }
            for (String uh : unknownHeaders) {
                result.issues().add(new Issue("WARN", firstRow + 1, uh, "存在未识别的列，将被忽略", uh));
            }
            // 必填字段列缺失 → 整体错误
            for (FieldDef f : fields) {
                if (f.isRequired() && !colOfField.containsKey(f.getCode())) {
                    result.issues().add(new Issue("ERROR", firstRow + 1, f.getCode(),
                            "缺少必填字段列：“" + f.getLabel() + "”", ""));
                }
            }
            if (def.getLevel() != Level.GLOBAL && scopeCol == null) {
                result.issues().add(new Issue("ERROR", firstRow + 1, SCOPE_HEADER,
                        "地区/项目层级配置必须包含“范围”列", ""));
            }

            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            for (int r = firstRow + 1; r <= data.getLastRowNum(); r++) {
                Row row = data.getRow(r);
                if (row == null) continue;
                Map<String, Object> raw = new LinkedHashMap<>();
                boolean any = false;
                for (Map.Entry<String, Integer> e : colOfField.entrySet()) {
                    Cell cell = row.getCell(e.getValue());
                    String s = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!s.isEmpty()) any = true;
                    raw.put(e.getKey(), s);
                }
                if (scopeCol != null) {
                    Cell cell = row.getCell(scopeCol);
                    String s = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!s.isEmpty()) any = true;
                    raw.put("__scope__", s);
                }
                if (!any) continue; // 空行跳过
                result.rows().add(raw);
            }
        }
        return result;
    }

    private Sheet locateDataSheet(XSSFWorkbook wb, ConfigDef def) {
        Sheet byName = wb.getSheet(DATA_SHEET);
        if (byName != null) return byName;
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            String name = wb.getSheetName(i);
            if (name.startsWith(INFO_SHEET) || name.startsWith("选项_")) continue;
            return wb.getSheetAt(i);
        }
        return wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
    }

    private boolean isScopeHeader(String h) {
        String n = h.replace("*", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return n.equals("范围") || n.equals("scope") || n.equals("scopes");
    }

    private String cellString(Cell cell) {
        DataFormatter fmt = new DataFormatter(Locale.CHINA);
        return fmt.formatCellValue(cell);
    }

    public void write(XSSFWorkbook wb, OutputStream out) throws IOException {
        wb.write(out);
    }
}
