package com.example.ai.controller;

import com.example.ai.dto.ConfigDTO;
import com.example.ai.dto.R;
import com.example.ai.entity.ConfigDataRow;
import com.example.ai.entity.ConfigDefinition;
import com.example.ai.service.ConfigDataService;
import com.example.ai.service.ConfigDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigDefinitionService definitionService;
    private final ConfigDataService dataService;

    // ========= 定义 =========
    @GetMapping("/definitions")
    public R<List<ConfigDefinition>> listDefinitions() {
        return R.ok(definitionService.listAll());
    }

    @GetMapping("/definitions/{id}")
    public R<ConfigDefinition> getDefinition(@PathVariable Long id) {
        return R.ok(definitionService.getById(id).orElseThrow(() -> new RuntimeException("配置定义不存在:" + id)));
    }

    @PostMapping("/definitions")
    public R<ConfigDefinition> createDefinition(@RequestBody ConfigDefinition def) {
        return R.ok(definitionService.save(def));
    }

    // ========= 数据 =========
    @GetMapping("/data/count")
    public R<Map<String, Object>> count(@RequestParam Long defId) {
        long total = dataService.count(defId);
        var def = definitionService.getById(defId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configDefId", defId);
        m.put("total", total);
        def.ifPresent(configDefinition -> m.put("name", configDefinition.getName()));
        return R.ok(m);
    }

    @GetMapping("/data/page")
    public R<Page<ConfigDataRow>> page(@RequestParam Long defId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return R.ok(dataService.page(defId, page, size));
    }

    @GetMapping("/data/all")
    public R<List<ConfigDataRow>> all(@RequestParam Long defId,
                                      @RequestParam(defaultValue = "100000") int limit) {
        return R.ok(dataService.listAll(defId, limit));
    }

    @PostMapping("/data/batch-save")
    public R<Map<String, Object>> batchSave(@RequestBody ConfigDTO.BatchSaveReq req) {
        return R.ok(dataService.batchSave(req.getConfigDefId(), req.getMode(), req.getRows()));
    }

    @PostMapping("/data/operations")
    public R<Map<String, Object>> applyOperation(@RequestBody ConfigDTO.ApplyBatchOperationReq req) {
        Map<String, Object> res;
        switch (req.getOperation()) {
            case "update_where" -> res = dataService.updateWhere(req.getConfigDefId(), req.getParams());
            case "delete_rows" -> res = dataService.deleteByIndex(req.getConfigDefId(), req.getParams());
            case "replace" -> res = dataService.replaceField(req.getConfigDefId(), req.getParams());
            default -> throw new RuntimeException("未知操作:" + req.getOperation());
        }
        return R.ok(res);
    }
}
