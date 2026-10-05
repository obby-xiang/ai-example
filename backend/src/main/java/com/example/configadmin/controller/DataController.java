package com.example.configadmin.controller;

import com.example.configadmin.common.R;
import com.example.configadmin.dto.Cond;
import com.example.configadmin.dto.RowDraft;
import com.example.configadmin.entity.ConfigRow;
import com.example.configadmin.service.ConfigDataService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 配置数据：生效/草稿查询（含条件）、手工增删改。 */
@RestController
@RequestMapping("/api/data")
public class DataController {

    private final ConfigDataService dataService;

    public DataController(ConfigDataService dataService) {
        this.dataService = dataService;
    }

    /** 分页查询（无字段条件）。 */
    @GetMapping("/rows")
    public R<Map<String, Object>> rows(@RequestParam String defCode,
                                       @RequestParam(defaultValue = "true") boolean published,
                                       @RequestParam(required = false) String batchId,
                                       @RequestParam(required = false) String scope,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(dataService.queryRowsPaged(defCode, Map.of(), scope, published, batchId, page, size));
    }

    /** 带字段条件的分页查询（导出查询条件引擎复用）。 */
    @PostMapping("/query")
    public R<Map<String, Object>> query(@RequestBody Map<String, Object> req) {
        String defCode = String.valueOf(req.get("defCode"));
        boolean published = req.get("published") == null || Boolean.parseBoolean(String.valueOf(req.get("published")));
        String batchId = req.get("batchId") == null ? null : String.valueOf(req.get("batchId"));
        String scope = req.get("scope") == null ? null : String.valueOf(req.get("scope"));
        int page = req.get("page") == null ? 1 : Integer.parseInt(String.valueOf(req.get("page")));
        int size = req.get("size") == null ? 20 : Integer.parseInt(String.valueOf(req.get("size")));
        @SuppressWarnings("unchecked")
        Map<String, Cond> conditions = (Map<String, Cond>) req.getOrDefault("conditions", Map.of());
        return R.ok(dataService.queryRowsPaged(defCode, conditions, scope, published, batchId, page, size));
    }

    /** 新增一行（默认直接生效；batchId 指定时入库为草稿）。 */
    @PostMapping("/rows")
    public R<Long> create(@RequestParam String defCode, @RequestBody RowDraft draft) {
        ConfigRow row = dataService.saveRow(defCode, draft);
        return R.ok(row.getId());
    }

    @PutMapping("/rows/{id}")
    public R<?> update(@RequestParam String defCode, @PathVariable Long id, @RequestBody RowDraft draft) {
        dataService.updateRow(defCode, id, draft);
        return R.ok();
    }

    @DeleteMapping("/rows/{id}")
    public R<?> delete(@PathVariable Long id) {
        dataService.deleteRow(id);
        return R.ok();
    }
}
