package com.example.configadmin.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 全局通用工具：所有页面可见。 */
@Component
public class CommonTools {

    @Component("getUiStateTool")
    public static class GetUiState implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("get_ui_state", "读取当前工作区状态（页面、向导步骤、已选配置、条件、任务/批次ID等）",
                    Schemas.obj(List.of(), null), List.of("all"), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            return ToolResult.ok("当前工作区状态：" + ctx.mapper().valueToTree(ctx.snapshot()).toString());
        }
    }

    @Component("navigateToPageTool")
    public static class NavigateToPage implements AiTool {
        @Override
        public ToolDef def() {
            return ToolDef.of("navigate_to_page",
                    "切换左侧工作区页面：export=导出配置、import=导入配置、defs=配置定义管理、data=数据浏览",
                    Schemas.obj(List.of(Schemas.strEnum("page", "目标页面", true, "export", "import", "defs", "data")),
                            List.of("page")), List.of("all"), false);
        }

        @Override
        public ToolResult run(ToolContext ctx, Map<String, Object> args) {
            String page = String.valueOf(args.get("page"));
            return ToolResult.ok("已打开页面：" + pageLabel(page))
                    .event("open_page", "page", page);
        }

        private String pageLabel(String p) {
            return switch (p) {
                case "export" -> "导出配置";
                case "import" -> "导入配置";
                case "defs" -> "配置定义管理";
                case "data" -> "数据浏览";
                default -> p;
            };
        }
    }
}
