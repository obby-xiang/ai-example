package com.example.configmgr.definition.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelTemplateBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/definitions")
@RequiredArgsConstructor
public class DefinitionController {

    private final DefinitionService definitionService;
    private final ExcelTemplateBuilder templateBuilder;

    @GetMapping
    public ApiResponse<List<ConfigDefinition>> list(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword) {
        List<ConfigDefinition> defs = level != null
                ? definitionService.findByLevel(ConfigDefinition.ConfigLevel.valueOf(level))
                : definitionService.findAll();
        if (keyword != null && !keyword.isBlank()) {
            defs = defs.stream().filter(d ->
                    d.getCode().contains(keyword) || d.getName().contains(keyword)).toList();
        }
        return ApiResponse.ok(defs);
    }

    @GetMapping("/{code}")
    public ApiResponse<ConfigDefinition> get(@PathVariable String code) {
        return ApiResponse.ok(definitionService.findByCode(code));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConfigDefinition>> create(@RequestBody ConfigDefinition definition) {
        return ResponseEntity.status(201).body(ApiResponse.ok(definitionService.save(definition)));
    }

    @PutMapping("/{code}")
    public ApiResponse<ConfigDefinition> update(@PathVariable String code,
                                                  @RequestBody ConfigDefinition definition) {
        return ApiResponse.ok(definitionService.update(code, definition));
    }

    /**
     * 删除定义（FR-1.4 定义管理；被引用检查与级联策略见 {@link DefinitionService#delete}）。
     *
     * <p>有已发布数据 / 被他定义 REFERENCE 引用 / 被进行中任务选中 → 409 + 机器可读码
     * （{@code DEFINITION_HAS_DATA} / {@code DEFINITION_REFERENCED} / {@code DEFINITION_IN_ACTIVE_TASK}）；
     * 不存在 → 404。放行时回显级联清理计数，并落一条无主体操作流水（ADR-11b）。
     */
    @DeleteMapping("/{code}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String code) {
        DefinitionService.DeleteResult result = definitionService.delete(code);
        return ApiResponse.ok(Map.of(
                "code", result.code(),
                "deleted", true,
                "cascadedFields", result.cascadedFields(),
                "cascadedStagingRows", result.cascadedStagingRows()));
    }

    @GetMapping("/{code}/template")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String code) throws Exception {
        ConfigDefinition def = definitionService.findByCode(code);
        byte[] bytes = templateBuilder.build(def);
        String filename = URLEncoder.encode(code + "_" + def.getName() + ".xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
