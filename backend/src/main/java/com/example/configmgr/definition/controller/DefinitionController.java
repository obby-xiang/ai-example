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
