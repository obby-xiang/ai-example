package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.Cond;
import com.example.configadmin.dto.DefSaveRequest;
import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.ImportFileEntry;
import com.example.configadmin.dto.ProgressEvent;
import com.example.configadmin.dto.RowDraft;
import com.example.configadmin.entity.BatchStatus;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.ExportTask;
import com.example.configadmin.entity.ImportBatch;
import com.example.configadmin.entity.Level;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 工具集：全部以 Spring AI @Tool 注解方法声明，
 * JSON Schema 由 Spring AI 自动生成（不再手工构建）。
 * 页面可见性（渐进式披露）与 HITL 确认标记见 ToolMeta。
 */
@Component
public class AiTools {

    // ==================== 通用 ====================

    @Tool(name = "get_ui_state", description = "读取当前工作区状态（页面、向导步骤、已选配置、条件、任务/批次ID等）")
    public String getUiState(ToolContext ctx) {
        return "当前工作区状态：" + services(ctx).mapper().valueToTree(session(ctx).getContext()).toString();
    }

    @Tool(name = "navigate_to_page", description = "切换左侧工作区页面：export=导出配置、import=导入配置、defs=配置定义管理、data=数据浏览、tasks=任务管理")
    public String navigateToPage(ToolContext ctx,
                                 @ToolParam(description = "目标页面") String page) {
        sink(ctx).emit(Map.of("type", "open_page", "page", page));
        return "已打开页面：" + page;
    }

    @Tool(name = "list_config_defs", description = "列出系统中的配置定义（可按关键字/层级过滤），返回编码、名称、层级、字段摘要、生效行数")
    public String listConfigDefs(ToolContext ctx,
                                 @ToolParam(description = "名称/编码关键字过滤", required = false) String keyword,
                                 @ToolParam(description = "层级过滤：GLOBAL/REGION/PROJECT", required = false) String level) {
        Services s = services(ctx);
        String kw = keyword == null ? null : keyword.toLowerCase();
        List<ConfigDef> defs = s.defService().listAll().stream()
                .filter(d -> level == null || d.getLevel().name().equals(level))
                .filter(d -> kw == null || d.getName().toLowerCase().contains(kw) || d.getCode().toLowerCase().contains(kw))
                .toList();
        if (defs.isEmpty()) {
            return "没有匹配的配置定义。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(defs.size()).append(" 个配置定义：\n");
        defs.forEach(d -> sb.append("• ").append(s.defService().describeForAi(d)).append("\n"));
        return sb.toString();
    }

    @Tool(name = "describe_config_def", description = "查看某个配置定义的完整字段结构（含必填、选项、引用、依赖）")
    public String describeConfigDef(ToolContext ctx,
                                    @ToolParam(description = "配置编码") String code) {
        Services s = services(ctx);
        ConfigDef def = s.defService().getByCode(code);
        StringBuilder sb = new StringBuilder(s.defService().describeForAi(def)).append("\n");
        s.defService().parseFields(def).forEach(f ->
                sb.append("  - ").append(f.getCode()).append("（").append(f.getLabel()).append("）类型：")
                        .append(f.getType()).append(f.isRequired() ? "，必填" : "")
                        .append(f.getOptions() == null || f.getOptions().isEmpty() ? ""
                                : "，选项：" + String.join("/", f.getOptions()))
                        .append("\n"));
        return sb.toString();
    }

    @Tool(name = "query_config_data", description = "查询某配置的数据行（生效数据），支持字段条件过滤，返回行数与样例")
    public String queryConfigData(ToolContext ctx,
                                  @ToolParam(description = "配置编码") String defCode,
                                  @ToolParam(description = "字段条件：{字段编码:{op,value}}，op=eq/contains/gt/lt/between/in", required = false) Map<String, Object> filters) {
        Services s = services(ctx);
        Map<String, Cond> conds = new LinkedHashMap<>();
        if (filters != null) {
            filters.forEach((k, v) -> conds.put(k, s.mapper().convertValue(v, Cond.class)));
        }
        List<Map<String, Object>> rows = s.dataService().queryRows(defCode, conds, null, true, null);
        StringBuilder sb = new StringBuilder("配置 ").append(defCode).append(" 生效数据共 ").append(rows.size()).append(" 行。");
        if (!rows.isEmpty()) {
            sb.append(" 前 ").append(Math.min(5, rows.size())).append(" 行样例：\n");
            rows.stream().limit(5).forEach(r -> sb.append("  ").append(r.get("data")).append("\n"));
        }
        return sb.toString();
    }

    @Tool(name = "create_config_def", description = "新建一个配置定义（动态建模）。fields 为字段数组；dependsOn 为依赖的配置编码数组（导入顺序约束）")
    public String createConfigDef(ToolContext ctx,
                                  @ToolParam(description = "配置编码（字母/数字/下划线，唯一，用于导入文件名匹配）") String code,
                                  @ToolParam(description = "配置名称（中文）") String name,
                                  @ToolParam(description = "层级：GLOBAL=全局、REGION=地区、PROJECT=项目") String level,
                                  @ToolParam(description = "说明", required = false) String description,
                                  @ToolParam(description = "字段定义数组（每项：code/label/type/required/options/min/max/refDefCode/refFieldCode/defaultValue）") List<Map<String, Object>> fields,
                                  @ToolParam(description = "依赖的配置编码列表", required = false) List<String> dependsOn) {
        Services s = services(ctx);
        DefSaveRequest req = new DefSaveRequest();
        req.setCode(code);
        req.setName(name);
        req.setLevel(Level.valueOf(level));
        req.setDescription(description);
        req.setFields(fields == null ? List.of() : fields.stream()
                .map(m -> s.mapper().convertValue(m, FieldDef.class)).collect(Collectors.toList()));
        req.setDependsOn(dependsOn == null ? List.of() : dependsOn);
        ConfigDef created = s.defService().create(req);
        sink(ctx).emit(Map.of("type", "open_page", "page", "defs"));
        sink(ctx).emit(Map.of("type", "refresh_defs", "code", created.getCode()));
        return "配置定义创建成功：" + created.getCode() + "（" + created.getName() + "），可在配置定义页维护数据";
    }

    @Tool(name = "update_config_def", description = "更新配置定义的部分信息（如追加字段、修改说明、调整依赖）。fields 提供时将整体替换字段列表")
    public String updateConfigDef(ToolContext ctx,
                                  @ToolParam(description = "配置编码") String code,
                                  @ToolParam(description = "新的配置名称", required = false) String name,
                                  @ToolParam(description = "新的说明", required = false) String description,
                                  @ToolParam(description = "新的依赖列表", required = false) List<String> dependsOn,
                                  @ToolParam(description = "新的字段定义数组（整体替换）", required = false) List<Map<String, Object>> fields) {
        Services s = services(ctx);
        ConfigDef existing = s.defService().getByCode(code);
        DefSaveRequest req = new DefSaveRequest();
        req.setCode(code);
        req.setName(name == null ? existing.getName() : name);
        req.setLevel(existing.getLevel());
        req.setDescription(description == null ? existing.getDescription() : description);
        req.setDependsOn(dependsOn == null ? s.defService().parseDependsOn(existing) : dependsOn);
        req.setFields(fields == null ? s.defService().parseFields(existing) : fields.stream()
                .map(m -> s.mapper().convertValue(m, FieldDef.class)).collect(Collectors.toList()));
        ConfigDef updated = s.defService().update(code, req);
        sink(ctx).emit(Map.of("type", "refresh_defs", "code", updated.getCode()));
        return "配置定义已更新：" + updated.getCode();
    }

    @Tool(name = "add_config_rows", description = "向配置定义新增数据行（直接写入生效数据）。每行：scope(地区/项目层级必填)+data(字段编码->值)")
    public String addConfigRows(ToolContext ctx,
                                @ToolParam(description = "配置编码") String defCode,
                                @ToolParam(description = "行数组：[{scope?, data:{字段编码:值}}]") List<Map<String, Object>> rows) {
        Services s = services(ctx);
        int ok = 0;
        StringBuilder errors = new StringBuilder();
        for (Map<String, Object> r : rows == null ? List.<Map<String, Object>>of() : rows) {
            RowDraft draft = new RowDraft();
            draft.setScope(r.get("scope") == null ? null : String.valueOf(r.get("scope")));
            draft.setData((Map<String, Object>) r.getOrDefault("data", Map.of()));
            draft.setPublished(true);
            try {
                s.dataService().saveRow(defCode, draft);
                ok++;
            } catch (ApiException e) {
                errors.append(e.getMessage()).append("；");
            }
        }
        sink(ctx).emit(Map.of("type", "refresh_data", "defCode", defCode));
        return "已写入 " + ok + " 行" + (errors.isEmpty() ? "" : "，失败：" + errors);
    }

    // ==================== 导出 ====================

    @Tool(name = "select_export_defs", description = "选择要导出的配置定义（可多选），同步勾选导出向导第一步")
    public String selectExportDefs(ToolContext ctx,
                                   @ToolParam(description = "配置编码列表") List<String> defCodes) {
        Services s = services(ctx);
        defCodes.forEach(c -> s.defService().getByCode(c));
        updatePageState(ctx, "export", "selectedDefs", defCodes);
        sink(ctx).emit(Map.of("type", "select_defs", "task", "export", "defCodes", defCodes));
        return "已选择导出配置：" + String.join("、", defCodes);
    }

    @Tool(name = "set_export_conditions", description = "设置某个已选配置的导出查询条件，同步填充导出向导第二步的表单。conditions 形如 {\"env\":{\"op\":\"eq\",\"value\":\"生产\"}}")
    public String setExportConditions(ToolContext ctx,
                                      @ToolParam(description = "配置编码") String defCode,
                                      @ToolParam(description = "条件对象：{字段编码:{op,value[,value2]}}") Map<String, Object> conditions) {
        Services s = services(ctx);
        s.defService().getByCode(defCode);
        Map<String, Object> st = pageState(ctx, "export");
        Map<String, Object> all = st == null ? new LinkedHashMap<>()
                : (Map<String, Object>) st.computeIfAbsent("conditions", k -> new LinkedHashMap<String, Object>());
        all.put(defCode, conditions);
        updatePageState(ctx, "export", "conditions", all);
        sink(ctx).emit(Map.of("type", "set_conditions", "defCode", defCode, "conditions", conditions));
        return "已设置 " + defCode + " 的查询条件：" + s.mapper().valueToTree(conditions).toString();
    }

    @Tool(name = "start_export", description = "按当前选择与查询条件启动导出任务，任务异步执行并显示进度")
    public String startExport(ToolContext ctx) {
        Services s = services(ctx);
        List<String> codes = selectedDefs(ctx, "export");
        if (codes.isEmpty()) {
            return "尚未选择要导出的配置，请先调用 select_export_defs 或让用户先勾选。";
        }
        Map<String, Map<String, Cond>> conds = conditions(ctx);
        ExportTask t = s.exportService().create(codes, conds);
        s.taskRunner().runExport(t.getId());
        updatePageState(ctx, "export", "taskId", t.getId());
        ProgressEvent snap = s.exportService().snapshot(s.exportService().get(t.getId()));
        sink(ctx).emit(Map.of("type", "export_started", "taskId", t.getId(), "snapshot", snap));
        sink(ctx).emit(Map.of("type", "goto_step", "step", 3));
        return "导出任务已启动（任务ID " + t.getId() + "，共 " + codes.size() + " 个配置），右侧可查看进度，导出完成后可在线编辑与下载。";
    }

    @Tool(name = "get_export_progress", description = "查询导出任务进度与文件清单")
    public String getExportProgress(ToolContext ctx,
                                    @ToolParam(description = "导出任务ID（缺省取当前任务）", required = false) Long taskId) {
        Services s = services(ctx);
        Long id = taskIdOrShadow(ctx, taskId);
        if (id == null) return "当前没有导出任务，请先启动导出。";
        ProgressEvent snap = s.exportService().snapshot(s.exportService().get(id));
        StringBuilder sb = new StringBuilder("导出任务 ").append(id).append("：状态=").append(snap.getStatus())
                .append("，进度=").append(snap.getProgress()).append("%，").append(snap.getMessage());
        List<?> files = (List<?>) snap.getDetail().getOrDefault("files", List.of());
        for (Object f : files) {
            Map<String, Object> m = (Map<String, Object>) f;
            sb.append("\n  ").append(m.get("fileName")).append("（").append(m.get("rowCount")).append(" 行）");
        }
        return sb.toString();
    }

    @Tool(name = "download_export_file", description = "下载某个导出文件（xlsx），触发浏览器下载")
    public String downloadExportFile(ToolContext ctx,
                                     @ToolParam(description = "配置编码") String defCode,
                                     @ToolParam(description = "导出任务ID（缺省取当前任务）", required = false) Long taskId) {
        Long id = taskIdOrShadow(ctx, taskId);
        if (id == null) return "当前没有导出任务，请先启动导出。";
        sink(ctx).emit(Map.of("type", "download",
                "url", "/api/export/tasks/" + id + "/files/" + defCode,
                "filename", defCode + ".xlsx"));
        return "已触发下载：" + defCode + ".xlsx";
    }

    @Tool(name = "package_export_files", description = "将导出文件打包为 zip 下载（可指定部分配置，缺省全部）")
    public String packageExportFiles(ToolContext ctx,
                                     @ToolParam(description = "要打包的配置编码（缺省=全部）", required = false) List<String> defCodes,
                                     @ToolParam(description = "导出任务ID（缺省取当前任务）", required = false) Long taskId) {
        Long id = taskIdOrShadow(ctx, taskId);
        if (id == null) return "当前没有导出任务，请先启动导出。";
        List<String> codes = defCodes == null ? List.of() : defCodes;
        String url = "/api/export/tasks/" + id + "/package" + (codes.isEmpty() ? "" : "?defCodes=" + String.join(",", codes));
        sink(ctx).emit(Map.of("type", "download", "url", url, "filename", "export-task-" + id + ".zip"));
        return "已触发打包下载：" + (codes.isEmpty() ? "全部文件" : String.join("、", codes));
    }

    // ==================== 导入 ====================

    @Tool(name = "select_import_defs", description = "选择要导入的配置定义（可多选），同步勾选导入向导第一步")
    public String selectImportDefs(ToolContext ctx,
                                   @ToolParam(description = "配置编码列表") List<String> defCodes) {
        Services s = services(ctx);
        defCodes.forEach(c -> s.defService().getByCode(c));
        updatePageState(ctx, "import", "selectedDefs", defCodes);
        sink(ctx).emit(Map.of("type", "select_defs", "task", "import", "defCodes", defCodes));
        return "已选择导入配置：" + String.join("、", defCodes);
    }

    @Tool(name = "download_templates", description = "下载导入模板（单个 xlsx 或多个配置的 zip），触发浏览器下载，用户填好后上传")
    public String downloadTemplates(ToolContext ctx,
                                    @ToolParam(description = "配置编码列表（缺省=当前已选）", required = false) List<String> defCodes,
                                    @ToolParam(description = "是否打包 zip（多配置时默认 true）", required = false) Boolean zip) {
        List<String> codes = defCodes == null || defCodes.isEmpty() ? selectedDefs(ctx, "import") : defCodes;
        if (codes.isEmpty()) {
            return "未指定配置且当前没有选择，请先调用 select_import_defs。";
        }
        boolean doZip = zip == null ? codes.size() > 1 : zip;
        String url = "/api/import/templates?defCodes=" + String.join(",", codes) + "&zip=" + doZip;
        sink(ctx).emit(Map.of("type", "download", "url", url,
                "filename", doZip ? "templates.zip" : codes.get(0) + ".xlsx"));
        return "已触发模板下载（" + String.join("、", codes) + "，格式：" + (doZip ? "zip" : "xlsx") + "），请填写后上传或在线编辑后上传。";
    }

    @Tool(name = "ensure_import_batch", description = "为当前已选配置创建（或复用）导入批次，返回批次ID")
    public String ensureImportBatch(ToolContext ctx) {
        Services s = services(ctx);
        List<String> codes = selectedDefs(ctx, "import");
        if (codes.isEmpty()) {
            return "尚未选择导入配置，请先调用 select_import_defs。";
        }
        ImportBatch b = s.importService().ensureBatch("AI 导入批次", codes);
        updatePageState(ctx, "import", "batchId", b.getId());
        sink(ctx).emit(Map.of("type", "import_batch", "batchId", b.getId(), "defCodes", codes));
        sink(ctx).emit(Map.of("type", "goto_step", "step", 1));
        return "导入批次就绪（批次ID " + b.getId() + "，配置：" + String.join("、", codes) + "）。请上传文件或下载模板填写后上传。";
    }

    @Tool(name = "start_import_check", description = "启动导入预检查（异步，含依赖顺序与引用校验），同步跳转到检查步骤显示进度与结果")
    public String startImportCheck(ToolContext ctx,
                                   @ToolParam(description = "批次ID（缺省取当前批次）", required = false) Long batchId) {
        Services s = services(ctx);
        Long id = batchIdOrShadow(ctx, batchId);
        if (id == null) return "当前没有导入批次，请先调用 ensure_import_batch。";
        ImportBatch batch = s.importService().getBatch(id);
        if (batch.getStatus() == BatchStatus.CHECKING) {
            return "检查正在执行中，请稍后查询结果。";
        }
        s.taskRunner().runCheck(id);
        sink(ctx).emit(Map.of("type", "import_action", "batchId", id, "action", "check"));
        sink(ctx).emit(Map.of("type", "goto_step", "step", 2));
        return "已启动导入检查（批次 " + id + "），检查完成后会显示每个文件的结果明细。";
    }

    @Tool(name = "get_batch_status", description = "查询导入批次的状态、进度与每个文件的检查/导入结果")
    public String getBatchStatus(ToolContext ctx,
                                 @ToolParam(description = "批次ID（缺省取当前批次）", required = false) Long batchId) {
        Services s = services(ctx);
        Long id = batchIdOrShadow(ctx, batchId);
        if (id == null) return "当前没有导入批次。";
        ProgressEvent snap = s.importService().snapshot(s.importService().getBatch(id));
        StringBuilder sb = new StringBuilder("批次 ").append(id).append("：状态=").append(snap.getStatus())
                .append("，进度=").append(snap.getProgress()).append("%，").append(snap.getMessage());
        List<?> files = (List<?>) snap.getDetail().getOrDefault("files", List.of());
        for (Object f : files) {
            ImportFileEntry m = f instanceof ImportFileEntry ie
                    ? ie : s.mapper().convertValue(f, ImportFileEntry.class);
            sb.append("\n  ").append(m.getDefCode()).append("[").append(m.getStatus()).append("]")
                    .append(" 行数=").append(m.getRowCount())
                    .append(" 错误=").append(m.getErrorCount())
                    .append(" 警告=").append(m.getWarnCount());
            if (m.getMessage() != null && !m.getMessage().isBlank()) {
                sb.append("（").append(m.getMessage()).append("）");
            }
        }
        return sb.toString();
    }

    @Tool(name = "start_import", description = "执行导入（导入前会再次检查，通过的文件落库为“未发布”草稿，不影响线上数据）")
    public String startImport(ToolContext ctx,
                              @ToolParam(description = "批次ID（缺省取当前批次）", required = false) Long batchId) {
        Services s = services(ctx);
        Long id = batchIdOrShadow(ctx, batchId);
        if (id == null) return "当前没有导入批次。";
        ImportBatch batch = s.importService().getBatch(id);
        if (batch.getStatus() == BatchStatus.IMPORTING) {
            return "导入正在执行中，请稍后查询结果。";
        }
        s.taskRunner().runImport(id);
        sink(ctx).emit(Map.of("type", "import_action", "batchId", id, "action", "import"));
        sink(ctx).emit(Map.of("type", "goto_step", "step", 3));
        return "已启动导入（批次 " + id + "），通过的文件将入库为草稿（未发布），进度与结果实时可见。";
    }

    @Tool(name = "start_publish", description = "发布批次（发布前再次检查，通过后草稿转为生效数据并替换旧生效数据）")
    public String startPublish(ToolContext ctx,
                               @ToolParam(description = "批次ID（缺省取当前批次）", required = false) Long batchId) {
        Services s = services(ctx);
        Long id = batchIdOrShadow(ctx, batchId);
        if (id == null) return "当前没有导入批次。";
        ImportBatch batch = s.importService().getBatch(id);
        if (batch.getStatus() != BatchStatus.IMPORTED) {
            return "只有完成导入的批次才能发布（当前状态：" + batch.getStatus() + "），请先执行检查与导入。";
        }
        s.taskRunner().runPublish(id);
        sink(ctx).emit(Map.of("type", "import_action", "batchId", id, "action", "publish"));
        sink(ctx).emit(Map.of("type", "goto_step", "step", 4));
        return "已启动发布（批次 " + id + "），发布检查通过后配置数据即对外生效。";
    }

    @Tool(name = "summarize_import_errors", description = "汇总检查/导入的错误明细（某配置或全部），便于向用户解释失败原因")
    public String summarizeImportErrors(ToolContext ctx,
                                        @ToolParam(description = "配置编码（缺省=全部有错误的配置）", required = false) String defCode,
                                        @ToolParam(description = "批次ID（缺省取当前批次）", required = false) Long batchId) {
        Services s = services(ctx);
        Long id = batchIdOrShadow(ctx, batchId);
        if (id == null) return "当前没有导入批次。";
        List<ImportFileEntry> entries = s.importService().results(id);
        StringBuilder sb = new StringBuilder("错误明细：\n");
        int shown = 0;
        for (ImportFileEntry e : entries) {
            if (defCode != null && !defCode.equals(e.getDefCode())) continue;
            if (e.getErrorCount() == 0) continue;
            sb.append("【").append(e.getDefCode()).append(" ").append(e.getDefName()).append("】错误 ")
                    .append(e.getErrorCount()).append(" 条：\n");
            s.importService().issuesDetail(id, e.getDefCode()).stream()
                    .filter(i -> "ERROR".equals(i.getLevel()))
                    .limit(10)
                    .forEach(i -> sb.append("  · 第").append(i.getRowIndex()).append("行 ")
                            .append(i.getField()).append("：").append(i.getMessage()).append("\n"));
            shown++;
        }
        if (shown == 0) {
            return "没有错误明细（所有文件检查均通过）。";
        }
        return sb.toString();
    }

    // ==================== 上下文辅助 ====================

    private AiSession session(ToolContext ctx) {
        return (AiSession) ctx.getContext().get("session");
    }

    private UiEventSink sink(ToolContext ctx) {
        return (UiEventSink) ctx.getContext().get("sink");
    }

    private Services services(ToolContext ctx) {
        return (Services) ctx.getContext().get("services");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pageState(ToolContext ctx, String page) {
        Object o = session(ctx).getContext().get(page);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private void updatePageState(ToolContext ctx, String page, String key, Object value) {
        Map<String, Object> root = (Map<String, Object>) session(ctx).getContext();
        Map<String, Object> st = (Map<String, Object>) root.computeIfAbsent(page,
                k -> new LinkedHashMap<String, Object>());
        st.put(key, value);
        Object v = root.getOrDefault("workspaceVersion", 0);
        root.put("workspaceVersion", (v instanceof Number n ? n.intValue() : 0) + 1);
    }

    private List<String> selectedDefs(ToolContext ctx, String page) {
        Map<String, Object> st = pageState(ctx, page);
        Object o = st == null ? null : st.get("selectedDefs");
        return o instanceof List<?> l ? l.stream().map(String::valueOf).collect(Collectors.toList()) : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Cond>> conditions(ToolContext ctx) {
        Map<String, Object> st = pageState(ctx, "export");
        Object o = st == null ? null : st.get("conditions");
        if (!(o instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        Map<String, Map<String, Cond>> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            Map<String, Cond> conds = new LinkedHashMap<>();
            ((Map<String, Object>) v).forEach((fk, fv) ->
                    conds.put(fk, services(ctx).mapper().convertValue(fv, Cond.class)));
            out.put(String.valueOf(k), conds);
        });
        return out;
    }

    private Long taskIdOrShadow(ToolContext ctx, Long arg) {
        if (arg != null) return arg;
        Map<String, Object> st = pageState(ctx, "export");
        Object o = st == null ? null : st.get("taskId");
        return o == null ? null : Long.valueOf(String.valueOf(o));
    }

    private Long batchIdOrShadow(ToolContext ctx, Long arg) {
        if (arg != null) return arg;
        Map<String, Object> st = pageState(ctx, "import");
        Object o = st == null ? null : st.get("batchId");
        return o == null ? null : Long.valueOf(String.valueOf(o));
    }
}
