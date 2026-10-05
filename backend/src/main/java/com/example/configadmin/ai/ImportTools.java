package com.example.configadmin.ai;

import com.example.configadmin.dto.ImportFileEntry;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.entity.ImportBatch;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 导入配置任务相关工具（仅导入页可见——渐进式披露；检查为只读，导入/发布需用户确认）。 */
@Component
public class ImportTools {

    private static final String PAGE = "import";

    private static List<String> selectedDefs(ToolContext ctx) {
        Map<String, Object> st = ctx.pageState(PAGE);
        Object o = st == null ? null : st.get("selectedDefs");
        return o instanceof List<?> l ? l.stream().map(String::valueOf).collect(Collectors.toList()) : List.of();
    }

    private static Long batchId(ToolContext ctx, Object arg) {
        if (arg != null) return Long.valueOf(String.valueOf(arg));
        Map<String, Object> st = ctx.pageState(PAGE);
        Object o = st == null ? null : st.get("batchId");
        return o == null ? null : Long.valueOf(String.valueOf(o));
    }

    /** 选择要导入的配置（多选）。 */
    @Component("selectImportDefsTool")
    public static class SelectImportDefs implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("select_import_defs", "选择要导入的配置定义（可多选），同步勾选导入向导第一步",
                    Schemas.obj(List.of(Schemas.strList("defCodes", "配置编码列表", true)), List.of("defCodes")),
                    List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) args.get("defCodes");
            for (String c : codes) {
                ctx.defs().getByCode(c);
            }
            ctx.updatePageState(PAGE, "selectedDefs", codes);
            return ToolResult.ok("已选择导入配置：" + String.join("、", codes))
                    .event("select_defs", Map.of("task", "import", "defCodes", codes));
        }
    }

    /** 触发模板下载。 */
    @Component("downloadTemplatesTool")
    public static class DownloadTemplates implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("download_templates",
                    "下载导入模板（单个 xlsx 或多个配置的 zip），触发浏览器下载，用户填好后上传",
                    Schemas.obj(List.of(
                            Schemas.strList("defCodes", "配置编码列表（缺省=当前已选）", false),
                            Schemas.bool("zip", "是否打包 zip（多配置时默认 true）", false)
                    ), null), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Object o = args.get("defCodes");
            List<String> codes = o instanceof List<?> l ? l.stream().map(String::valueOf).collect(Collectors.toList())
                    : ImportTools.selectedDefs(ctx);
            if (codes.isEmpty()) {
                return ToolResult.fail("未指定配置且当前没有选择，请先调用 select_import_defs。");
            }
            boolean zip = args.get("zip") == null ? codes.size() > 1 : Boolean.parseBoolean(String.valueOf(args.get("zip")));
            String url = "/api/import/templates?defCodes=" + String.join(",", codes) + "&zip=" + zip;
            return ToolResult.ok("已触发模板下载（" + String.join("、", codes) + "，格式："
                    + (zip ? "zip" : "xlsx") + "），请填写后上传或在线编辑后上传。")
                    .event("download", Map.of("url", url, "filename", zip ? "templates.zip" : codes.get(0) + ".xlsx"));
        }
    }

    /** 创建/复用导入批次。 */
    @Component("ensureImportBatchTool")
    public static class EnsureImportBatch implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("ensure_import_batch", "为当前已选配置创建（或复用）导入批次，返回批次ID",
                    Schemas.obj(List.of(), null), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            List<String> codes = ImportTools.selectedDefs(ctx);
            if (codes.isEmpty()) {
                return ToolResult.fail("尚未选择导入配置，请先调用 select_import_defs。");
            }
            ImportBatch b = ctx.imports().ensureBatch("AI 导入批次", codes);
            ctx.updatePageState(PAGE, "batchId", b.getId());
            return ToolResult.ok("导入批次就绪（批次ID " + b.getId() + "，配置："
                    + String.join("、", codes) + "）。请上传文件或下载模板填写后上传。")
                    .event("import_batch", Map.of("batchId", b.getId(), "defCodes", codes))
                    .event("goto_step", "step", 1);
        }
    }

    /** 启动检查（只读操作，后端直接执行；同时下发 ui_event 让前端同步跳转并订阅进度）。 */
    @Component("startImportCheckTool")
    public static class StartImportCheck implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("start_import_check", "启动导入预检查（异步，含依赖顺序与引用校验），同步跳转到检查步骤显示进度与结果",
                    Schemas.obj(List.of(Schemas.num("batchId", "批次ID（缺省取当前批次）", false)), null),
                    List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = ImportTools.batchId(ctx, args.get("batchId"));
            if (id == null) return ToolResult.fail("当前没有导入批次，请先调用 ensure_import_batch。");
            ImportBatch batch = ctx.imports().getBatch(id);
            if (batch.getStatus() == com.example.configadmin.entity.BatchStatus.CHECKING) {
                return ToolResult.fail("检查正在执行中，请稍后查询结果。");
            }
            ctx.runner().runCheck(id);
            return ToolResult.ok("已启动导入检查（批次 " + id + "），检查完成后会显示每个文件的结果明细。")
                    .event("import_action", Map.of("batchId", id, "action", "check"))
                    .event("goto_step", "step", 2);
        }
    }

    /** 查询批次状态。 */
    @Component("getBatchStatusTool")
    public static class GetBatchStatus implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("get_batch_status", "查询导入批次的状态、进度与每个文件的检查/导入结果",
                    Schemas.obj(List.of(Schemas.num("batchId", "批次ID（缺省取当前批次）", false)), null),
                    List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = ImportTools.batchId(ctx, args.get("batchId"));
            if (id == null) return ToolResult.fail("当前没有导入批次。");
            ProgressEvent snap = ctx.imports().snapshot(ctx.imports().getBatch(id));
            StringBuilder sb = new StringBuilder("批次 ").append(id).append("：状态=").append(snap.getStatus())
                    .append("，进度=").append(snap.getProgress()).append("%，").append(snap.getMessage());
            List<?> files = (List<?>) snap.getDetail().getOrDefault("files", List.of());
            for (Object f : files) {
                ImportFileEntry m = f instanceof ImportFileEntry ie
                        ? ie : ctx.mapper().convertValue(f, ImportFileEntry.class);
                sb.append("\n  ").append(m.getDefCode()).append("[").append(m.getStatus()).append("]")
                        .append(" 行数=").append(m.getRowCount())
                        .append(" 错误=").append(m.getErrorCount())
                        .append(" 警告=").append(m.getWarnCount());
                if (m.getMessage() != null && !m.getMessage().isBlank()) {
                    sb.append("（").append(m.getMessage()).append("）");
                }
            }
            return ToolResult.ok(sb.toString());
        }
    }

    /** 导入（写操作，需确认；后端直接执行）。 */
    @Component("startImportTool")
    public static class StartImport implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("start_import", "执行导入（导入前会再次检查，通过的文件落库为“未发布”草稿，不影响线上数据）",
                    Schemas.obj(List.of(Schemas.num("batchId", "批次ID（缺省取当前批次）", false)), null),
                    List.of(PAGE), true);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = ImportTools.batchId(ctx, args.get("batchId"));
            if (id == null) return ToolResult.fail("当前没有导入批次。");
            ImportBatch batch = ctx.imports().getBatch(id);
            if (batch.getStatus() == com.example.configadmin.entity.BatchStatus.IMPORTING) {
                return ToolResult.fail("导入正在执行中，请稍后查询结果。");
            }
            ctx.runner().runImport(id);
            return ToolResult.ok("已启动导入（批次 " + id + "），通过的文件将入库为草稿（未发布），进度与结果实时可见。")
                    .event("import_action", Map.of("batchId", id, "action", "import"))
                    .event("goto_step", "step", 3);
        }
    }

    /** 发布（写操作，需确认；后端直接执行）。 */
    @Component("startPublishTool")
    public static class StartPublish implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("start_publish", "发布批次（发布前再次检查，通过后草稿转为生效数据并替换旧生效数据）",
                    Schemas.obj(List.of(Schemas.num("batchId", "批次ID（缺省取当前批次）", false)), null),
                    List.of(PAGE), true);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = ImportTools.batchId(ctx, args.get("batchId"));
            if (id == null) return ToolResult.fail("当前没有导入批次。");
            ImportBatch batch = ctx.imports().getBatch(id);
            if (batch.getStatus() != com.example.configadmin.entity.BatchStatus.IMPORTED) {
                return ToolResult.fail("只有完成导入的批次才能发布（当前状态：" + batch.getStatus() + "），请先执行检查与导入。");
            }
            ctx.runner().runPublish(id);
            return ToolResult.ok("已启动发布（批次 " + id + "），发布检查通过后配置数据即对外生效。")
                    .event("import_action", Map.of("batchId", id, "action", "publish"))
                    .event("goto_step", "step", 4);
        }
    }

    /** 汇总错误。 */
    @Component("summarizeImportErrorsTool")
    public static class SummarizeImportErrors implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("summarize_import_errors", "汇总检查/导入的错误明细（某配置或全部），便于向用户解释失败原因",
                    Schemas.obj(List.of(
                            Schemas.str("defCode", "配置编码（缺省=全部有错误的配置）", false),
                            Schemas.num("batchId", "批次ID（缺省取当前批次）", false)
                    ), null), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = ImportTools.batchId(ctx, args.get("batchId"));
            if (id == null) return ToolResult.fail("当前没有导入批次。");
            String onlyDef = args.get("defCode") == null ? null : String.valueOf(args.get("defCode"));
            List<ImportFileEntry> entries = ctx.imports().results(id);
            StringBuilder sb = new StringBuilder("错误明细：\n");
            int shown = 0;
            for (ImportFileEntry e : entries) {
                if (onlyDef != null && !onlyDef.equals(e.getDefCode())) continue;
                if (e.getErrorCount() == 0) continue;
                sb.append("【").append(e.getDefCode()).append(" ").append(e.getDefName()).append("】错误 ")
                        .append(e.getErrorCount()).append(" 条：\n");
                ctx.imports().issuesDetail(id, e.getDefCode()).stream()
                        .filter(i -> "ERROR".equals(i.getLevel()))
                        .limit(10)
                        .forEach(i -> sb.append("  · 第").append(i.getRowIndex()).append("行 ")
                                .append(i.getField()).append("：").append(i.getMessage()).append("\n"));
                shown++;
            }
            if (shown == 0) {
                return ToolResult.ok("没有错误明细（所有文件检查均通过）。");
            }
            return ToolResult.ok(sb.toString());
        }
    }
}
