package com.example.configmgr.data.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.data.service.QueryCondition;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final ConfigDataRowRepository dataRowRepository;
    private final DefinitionService definitionService;
    private final ConfigDataService configDataService;

    /** scopeType 未传时按配置定义的层级推导（而非写死 GLOBAL） */
    private String effectiveScopeType(String defCode, String scopeType) {
        if (scopeType != null && !scopeType.isBlank()) {
            return scopeType;
        }
        try {
            return definitionService.findByCode(defCode).getLevel().name();
        } catch (Exception e) {
            return "GLOBAL";
        }
    }

    /**
     * 数据列表（分页）。
     *
     * <p>S4.4b 遗留 3 修复：新增 {@code conditions} 入参（{@link QueryCondition} 的 JSON），
     * 与 {@code /count}、导出作业同构 —— 无条件时走 DB 分页（scopeType + scopeKey 收窄），
     * 有条件时按同口径逐行求值后再分页，故前端不再需要"只筛当前页"的镜像求值器。
     */
    @GetMapping("/{defCode}")
    public ApiResponse<Page<ConfigDataRow>> list(@PathVariable String defCode,
                                                   @RequestParam(required = false) String scopeType,
                                                   @RequestParam(required = false) String scopeKey,
                                                   @RequestParam(required = false) String conditions,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        QueryCondition cond = configDataService.parseCondition(conditions);
        if (cond != null) {
            return ApiResponse.ok(configDataService.findPagedFiltered(defCode, scopeKey, cond, page, size));
        }
        return ApiResponse.ok(dataRowRepository.findRowsInScopePaged(
                defCode, effectiveScopeType(defCode, scopeType), scopeKey,
                org.springframework.data.domain.PageRequest.of(page, size)));
    }

    /**
     * 预估行数：支持带查询条件（conditions 为 QueryCondition 的 JSON），
     * 与导出作业的过滤口径完全一致（并与列表端点的 {@code conditions} 同源，见
     * {@link ConfigDataService#findPagedFiltered}）。
     */
    @GetMapping("/{defCode}/count")
    public ApiResponse<Map<String, Long>> count(@PathVariable String defCode,
                                                  @RequestParam(required = false) String scopeType,
                                                  @RequestParam(required = false) String scopeKey,
                                                  @RequestParam(required = false) String conditions) {
        QueryCondition cond = configDataService.parseCondition(conditions);

        long count = cond != null
                ? configDataService.countFiltered(defCode, scopeKey, cond)
                : dataRowRepository.countByScope(defCode, effectiveScopeType(defCode, scopeType), scopeKey);
        return ApiResponse.ok(Map.of("count", count));
    }
}
