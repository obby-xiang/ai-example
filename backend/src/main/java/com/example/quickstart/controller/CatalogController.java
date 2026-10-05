package com.example.quickstart.controller;

import com.example.quickstart.common.ApiResponse;
import com.example.quickstart.common.BizException;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.service.CatalogService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/configs")
    public ApiResponse<List<CatalogDTO.ConfigItem>> listConfigs() {
        return ApiResponse.ok(catalogService.listConfigs());
    }

    @GetMapping("/scopes")
    public ApiResponse<List<CatalogDTO.ScopeItem>> listScopes(@RequestParam(required = false) String type) {
        return ApiResponse.ok(catalogService.listScopes(type));
    }

    @Data
    public static class TaskTypeDTO {
        private String code;
        private String name;
        private String description;
        private List<String> steps;
    }

    @GetMapping("/task-types")
    public ApiResponse<List<TaskTypeDTO>> taskTypes() {
        TaskTypeDTO export = new TaskTypeDTO();
        export.setCode("EXPORT_CONFIG");
        export.setName("导出配置");
        export.setDescription("选择配置 → 设置查询条件 → 导出为 Excel（可在线编辑、打包下载）");
        export.setSteps(List.of("选择配置", "查询配置", "导出配置"));
        TaskTypeDTO imp = new TaskTypeDTO();
        imp.setCode("IMPORT_CONFIG");
        imp.setName("导入配置");
        imp.setDescription("选择配置 → 上传/在线编辑 → 检查 → 导入 → 发布");
        imp.setSteps(List.of("选择配置", "上传配置", "检查配置", "导入配置", "发布配置"));
        return ApiResponse.ok(List.of(export, imp));
    }
}
