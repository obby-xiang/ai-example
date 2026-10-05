package com.example.configadmin.ai;

import com.example.configadmin.dto.Cond;
import com.example.configadmin.dto.ExportFileEntry;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.entity.ExportTask;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 导出配置任务相关工具（仅导出页可见——渐进式披露）。 */
@Component
public class ExportTools {

    private static final String PAGE = "export";

    private List<String> selectedDefs(ToolContext ctx) {
        Map<String, Object> st = ctx.pageState(PAGE);
        Object o = st == null ? null : st.get("selectedDefs");
        return o instanceof List<?> l ? l.stream().map(String::valueOf).collect(Collectors.toList()) : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Cond>> conditions(ToolContext ctx) {
        Map<String, Object> st = ctx.pageState(PAGE);
        Object o = st == null ? null : st.get("conditions");
        if (!(o instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        Map<String, Map<String, Cond>> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            Map<String, Cond> conds = new LinkedHashMap<>();
            ((Map<String, Object>) v).forEach((fk, fv) ->
                    conds.put(fk, ctx.mapper().convertValue(fv, Cond.class)));
            out.put(String.valueOf(k), conds);
        });
        return out;
    }

    private Long taskId(ToolContext ctx, Object arg) {
        if (arg != null) return Long.valueOf(String.valueOf(arg));
        Map<String, Object> st = ctx.pageState(PAGE);
        Object o = st == null ? null : st.get("taskId");
        return o == null ? null : Long.valueOf(String.valueOf(o));
    }

    /** 选择要导出的配置（多选）。 */
    @Component("selectExportDefsTool")
    public static class SelectExportDefs implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("select_export_defs", "选择要导出的配置定义（可多选），同步勾选导出向导第一步",
                    Schemas.obj(List.of(Schemas.strList("defCodes", "配置编码列表", true)), List.of("defCodes")),
                    List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) args.get("defCodes");
            for (String c : codes) {
                ctx.defs().getByCode(c); // 校验存在
            }
            ctx.updatePageState(PAGE, "selectedDefs", codes);
            return ToolResult.ok("已选择导出配置：" + String.join("、", codes))
                    .event("select_defs", Map.of("task", "export", "defCodes", codes));
        }
    }

    /** 设置某配置的导出查询条件。 */
    @Component("setExportConditionsTool")
    public static class SetExportConditions implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("set_export_conditions",
                    "设置某个已选配置的导出查询条件，同步填充导出向导第二步的表单。conditions 形如 {\"env\":{\"op\":\"eq\",\"value\":\"生产\"}}",
                    Schemas.obj(List.of(
                            Schemas.str("defCode", "配置编码", true),
                            Schemas.prop("conditions", "object", "条件对象：{字段编码:{op,value[,value2]}}", true)
                    ), List.of("defCode", "conditions")), List.of(PAGE), false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String defCode = String.valueOf(args.get("defCode"));
            ctx.defs().getByCode(defCode);
            Map<String, Object> conds = (Map<String, Object>) args.get("conditions");
            Map<String, Object> st = ctx.pageState(PAGE);
            Map<String, Object> all = st == null ? new LinkedHashMap<>()
                    : (Map<String, Object>) st.computeIfAbsent("conditions", k -> new LinkedHashMap<String, Object>());
            all.put(defCode, conds);
            ctx.updatePageState(PAGE, "conditions", all);
            return ToolResult.ok("已设置 " + defCode + " 的查询条件：" + ctx.mapper().valueToTree(conds).toString())
                    .event("set_conditions", Map.of("defCode", defCode, "conditions", (Object) conds));
        }
    }

    /** 启动导出任务（读取当前影子状态中的选择与条件）。 */
    @Component("startExportTool")
    public static class StartExport implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("start_export", "按当前选择与查询条件启动导出任务，任务异步执行并显示进度",
                    Schemas.obj(List.of(), null), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            List<String> codes = outer.selectedDefs(ctx);
            if (codes.isEmpty()) {
                return ToolResult.fail("尚未选择要导出的配置，请先调用 select_export_defs 或让用户先勾选。");
            }
            Map<String, Map<String, Cond>> conds = outer.conditions(ctx);
            ExportTask t = ctx.exports().create(codes, conds);
            ctx.runner().runExport(t.getId());
            ctx.updatePageState(PAGE, "taskId", t.getId());
            ProgressEvent snap = ctx.exports().snapshot(ctx.exports().get(t.getId()));
            return ToolResult.ok("导出任务已启动（任务ID " + t.getId() + "，共 " + codes.size()
                    + " 个配置），右侧可查看进度，导出完成后可在线编辑与下载。")
                    .event("export_started", Map.of("taskId", t.getId(), "snapshot", (Object) snap))
                    .event("goto_step", "step", 3);
        }
    }

    /** 查询导出进度。 */
    @Component("getExportProgressTool")
    public static class GetExportProgress implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("get_export_progress", "查询导出任务进度与文件清单",
                    Schemas.obj(List.of(Schemas.num("taskId", "导出任务ID（缺省取当前任务）", false)), null),
                    List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = outer.taskId(ctx, args.get("taskId"));
            if (id == null) return ToolResult.fail("当前没有导出任务，请先启动导出。");
            ProgressEvent snap = ctx.exports().snapshot(ctx.exports().get(id));
            StringBuilder sb = new StringBuilder("导出任务 ").append(id).append("：状态=").append(snap.getStatus())
                    .append("，进度=").append(snap.getProgress()).append("%，").append(snap.getMessage());
            List<?> files = (List<?>) snap.getDetail().getOrDefault("files", List.of());
            if (!files.isEmpty()) {
                sb.append("\n文件：");
                files.forEach(f -> {
                    Map<String, Object> m = (Map<String, Object>) f;
                    sb.append("\n  ").append(m.get("fileName")).append("（").append(m.get("rowCount")).append(" 行）");
                });
            }
            return ToolResult.ok(sb.toString());
        }
    }

    /** 触发前端下载单个导出文件。 */
    @Component("downloadExportFileTool")
    public static class DownloadExportFile implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("download_export_file", "下载某个导出文件（xlsx），触发浏览器下载",
                    Schemas.obj(List.of(
                            Schemas.str("defCode", "配置编码", true),
                            Schemas.num("taskId", "导出任务ID（缺省取当前任务）", false)
                    ), List.of("defCode")), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = outer.taskId(ctx, args.get("taskId"));
            if (id == null) return ToolResult.fail("当前没有导出任务，请先启动导出。");
            String defCode = String.valueOf(args.get("defCode"));
            return ToolResult.ok("已触发下载：" + defCode + ".xlsx")
                    .event("download", Map.of("url", "/api/export/tasks/" + id + "/files/" + defCode,
                            "filename", defCode + ".xlsx"));
        }
    }

    /** 触发前端打包下载。 */
    @Component("packageExportFilesTool")
    public static class PackageExportFiles implements AiTool {
        private final ExportTools outer = new ExportTools();

        @Override
        public ToolDef def() {
            return ToolDef.of("package_export_files", "将导出文件打包为 zip 下载（可指定部分配置，缺省全部）",
                    Schemas.obj(List.of(
                            Schemas.strList("defCodes", "要打包的配置编码（缺省=全部）", false),
                            Schemas.num("taskId", "导出任务ID（缺省取当前任务）", false)
                    ), null), List.of(PAGE), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            Long id = outer.taskId(ctx, args.get("taskId"));
            if (id == null) return ToolResult.fail("当前没有导出任务，请先启动导出。");
            Object o = args.get("defCodes");
            List<String> codes = o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            String url = "/api/export/tasks/" + id + "/package" + (codes.isEmpty() ? "" : "?defCodes=" + String.join(",", codes));
            return ToolResult.ok("已触发打包下载：" + (codes.isEmpty() ? "全部文件" : String.join("、", codes)))
                    .event("download", Map.of("url", url, "filename", "export-task-" + id + ".zip"));
        }
    }
}
