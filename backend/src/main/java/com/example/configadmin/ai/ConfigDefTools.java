package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.dto.DefSaveRequest;
import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.RowDraft;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.Level;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 配置定义/数据相关工具（读操作全页面可见；写操作需确认）。 */
@Component
public class ConfigDefTools {

    private static final List<String> ALL_PAGES = List.of("all");

    /** 列出配置定义（渐进式披露之外的“读数据”能力，各页面可用）。 */
    @Component("listConfigDefsTool")
    public static class ListConfigDefs implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("list_config_defs",
                    "列出系统中的配置定义（可按键字/层级过滤），返回编码、名称、层级、字段摘要、生效行数",
                    Schemas.obj(List.of(
                            Schemas.str("keyword", "名称/编码关键字过滤", false),
                            Schemas.strEnum("level", "层级过滤", false, "GLOBAL", "REGION", "PROJECT")
                    ), null), ALL_PAGES, false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String kw = args.get("keyword") == null ? null : String.valueOf(args.get("keyword")).toLowerCase(Locale.ROOT);
            String level = args.get("level") == null ? null : String.valueOf(args.get("level"));
            List<ConfigDef> defs = ctx.defs().listAll().stream()
                    .filter(d -> level == null || d.getLevel().name().equals(level))
                    .filter(d -> kw == null || d.getName().toLowerCase(Locale.ROOT).contains(kw)
                            || d.getCode().toLowerCase(Locale.ROOT).contains(kw))
                    .toList();
            if (defs.isEmpty()) {
                return ToolResult.ok("没有匹配的配置定义。");
            }
            StringBuilder sb = new StringBuilder("共 ").append(defs.size()).append(" 个配置定义：\n");
            defs.forEach(d -> sb.append("• ").append(ctx.defs().describeForAi(d)).append("\n"));
            return ToolResult.ok(sb.toString());
        }
    }

    /** 查看单个配置定义详情。 */
    @Component("describeConfigDefTool")
    public static class DescribeConfigDef implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("describe_config_def", "查看某个配置定义的完整字段结构（含必填、选项、引用、依赖）",
                    Schemas.obj(List.of(Schemas.str("code", "配置编码", true)), List.of("code")), ALL_PAGES, false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            ConfigDef def = ctx.defs().getByCode(String.valueOf(args.get("code")));
            StringBuilder sb = new StringBuilder(ctx.defs().describeForAi(def)).append("\n");
            ctx.defs().parseFields(def).forEach(f ->
                    sb.append("  - ").append(f.getCode()).append("（").append(f.getLabel()).append("）类型：")
                            .append(f.getType()).append(f.isRequired() ? "，必填" : "")
                            .append(f.getOptions() == null || f.getOptions().isEmpty() ? ""
                                    : "，选项：" + String.join("/", f.getOptions()))
                            .append("\n"));
            return ToolResult.ok(sb.toString());
        }
    }

    /** 创建配置定义（写操作，需确认；确认后经 ui_event 打开配置定义页并刷新）。 */
    @Component("createConfigDefTool")
    public static class CreateConfigDef implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("create_config_def",
                    "新建一个配置定义（动态建模）。fields 为字段数组；dependsOn 为依赖的配置编码数组（导入顺序约束）",
                    Schemas.obj(List.of(
                            Schemas.str("code", "配置编码（字母/数字/下划线，唯一，用于导入文件名匹配）", true),
                            Schemas.str("name", "配置名称（中文）", true),
                            Schemas.strEnum("level", "层级：GLOBAL=全局、REGION=地区、PROJECT=项目", true, "GLOBAL", "REGION", "PROJECT"),
                            Schemas.str("description", "说明", false),
                            Schemas.prop("fields", "array", "字段定义数组（每项：code/label/type/required/options/min/max/refDefCode/refFieldCode/defaultValue）", true),
                            Schemas.strList("dependsOn", "依赖的配置编码列表", false)
                    ), List.of("code", "name", "level", "fields")), List.of("defs"), true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            DefSaveRequest req = new DefSaveRequest();
            req.setCode(String.valueOf(args.get("code")));
            req.setName(String.valueOf(args.get("name")));
            req.setLevel(Level.valueOf(String.valueOf(args.get("level"))));
            req.setDescription(args.get("description") == null ? null : String.valueOf(args.get("description")));
            List<Map<String, Object>> rawFields = (List<Map<String, Object>>) args.getOrDefault("fields", List.of());
            req.setFields(rawFields.stream().map(m -> ctx.mapper().convertValue(m, FieldDef.class)).collect(Collectors.toList()));
            req.setDependsOn((List<String>) args.getOrDefault("dependsOn", List.of()));
            ConfigDef created = ctx.defs().create(req);
            return ToolResult.ok("配置定义创建成功：" + created.getCode() + "（" + created.getName() + "），可在配置定义页维护数据")
                    .event("open_page", "page", "defs")
                    .event("refresh_defs", "code", created.getCode());
        }
    }

    /** 更新配置定义（写操作，需确认）。 */
    @Component("updateConfigDefTool")
    public static class UpdateConfigDef implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("update_config_def",
                    "更新配置定义的部分信息（如追加字段、修改说明、调整依赖）。fields 提供时将整体替换字段列表",
                    Schemas.obj(List.of(
                            Schemas.str("code", "配置编码", true),
                            Schemas.str("name", "新的配置名称", false),
                            Schemas.str("description", "新的说明", false),
                            Schemas.strList("dependsOn", "新的依赖列表", false),
                            Schemas.prop("fields", "array", "新的字段定义数组（整体替换）", false)
                    ), List.of("code")), List.of("defs"), true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String code = String.valueOf(args.get("code"));
            ConfigDef existing = ctx.defs().getByCode(code);
            DefSaveRequest req = new DefSaveRequest();
            req.setCode(code);
            req.setName(args.get("name") == null ? existing.getName() : String.valueOf(args.get("name")));
            req.setLevel(existing.getLevel());
            req.setDescription(args.get("description") == null ? existing.getDescription() : String.valueOf(args.get("description")));
            req.setDependsOn(args.get("dependsOn") == null ? ctx.defs().parseDependsOn(existing)
                    : (List<String>) args.get("dependsOn"));
            if (args.get("fields") == null) {
                req.setFields(ctx.defs().parseFields(existing));
            } else {
                List<Map<String, Object>> rawFields = (List<Map<String, Object>>) args.get("fields");
                req.setFields(rawFields.stream().map(m -> ctx.mapper().convertValue(m, FieldDef.class)).collect(Collectors.toList()));
            }
            ConfigDef updated = ctx.defs().update(code, req);
            return ToolResult.ok("配置定义已更新：" + updated.getCode())
                    .event("refresh_defs", "code", updated.getCode());
        }
    }

    /** 查询配置数据（读操作）。 */
    @Component("queryConfigDataTool")
    public static class QueryConfigData implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("query_config_data",
                    "查询某配置的数据行（生效数据），支持字段条件过滤，返回行数与样例",
                    Schemas.obj(List.of(
                            Schemas.str("defCode", "配置编码", true),
                            Schemas.prop("filters", "object", "字段条件：{字段编码:{op,value}}，op=eq/contains/gt/lt/between/in", false)
                    ), List.of("defCode")), ALL_PAGES, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String defCode = String.valueOf(args.get("defCode"));
            Map<String, Object> rawFilters = (Map<String, Object>) args.getOrDefault("filters", Map.of());
            Map<String, com.example.configadmin.dto.Cond> conds = new LinkedHashMap<>();
            rawFilters.forEach((k, v) -> conds.put(k, ctx.mapper().convertValue(v, com.example.configadmin.dto.Cond.class)));
            List<Map<String, Object>> rows = ctx.data().queryRows(defCode, conds, null, true, null);
            StringBuilder sb = new StringBuilder("配置 ").append(defCode).append(" 生效数据共 ").append(rows.size()).append(" 行。");
            if (!rows.isEmpty()) {
                sb.append(" 前 ").append(Math.min(5, rows.size())).append(" 行样例：\n");
                rows.stream().limit(5).forEach(r -> sb.append("  ").append(r.get("data")).append("\n"));
            }
            return ToolResult.ok(sb.toString());
        }
    }

    /** 手工新增数据行（写操作，需确认）。 */
    @Component("addConfigRowsTool")
    public static class AddConfigRows implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("add_config_rows",
                    "向配置定义新增数据行（直接写入生效数据）。每行：scope(地区/项目层级必填)+data(字段编码->值)",
                    Schemas.obj(List.of(
                            Schemas.str("defCode", "配置编码", true),
                            Schemas.prop("rows", "array", "行数组：[{scope?, data:{字段编码:值}}]", true)
                    ), List.of("defCode", "rows")), List.of("data"), true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String defCode = String.valueOf(args.get("defCode"));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) args.getOrDefault("rows", List.of());
            int ok = 0;
            StringBuilder errors = new StringBuilder();
            for (Map<String, Object> r : rows) {
                RowDraft draft = new RowDraft();
                draft.setScope(r.get("scope") == null ? null : String.valueOf(r.get("scope")));
                draft.setData((Map<String, Object>) r.getOrDefault("data", Map.of()));
                draft.setPublished(true);
                try {
                    ctx.data().saveRow(defCode, draft);
                    ok++;
                } catch (ApiException e) {
                    errors.append(e.getMessage()).append("；");
                }
            }
            String text = "已写入 " + ok + " 行" + (errors.isEmpty() ? "" : "，失败：" + errors);
            return ToolResult.ok(text).event("refresh_data", "defCode", defCode);
        }
    }
}
