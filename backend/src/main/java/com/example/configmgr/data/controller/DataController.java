package com.example.configmgr.data.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.service.ConfigDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final ConfigDataService dataService;

    @GetMapping("/{defCode}")
    public ApiResponse<Page<ConfigDataRow>> list(@PathVariable String defCode,
                                                   @RequestParam(required = false) String scopeType,
                                                   @RequestParam(required = false) String scopeKey,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(dataService.findPaged(defCode, scopeType, scopeKey, page, size));
    }

    @GetMapping("/{defCode}/count")
    public ApiResponse<Map<String, Long>> count(@PathVariable String defCode,
                                                  @RequestParam(required = false) String scopeType,
                                                  @RequestParam(required = false) String scopeKey) {
        long count = dataService.count(defCode, scopeType, scopeKey);
        return ApiResponse.ok(Map.of("count", count));
    }
}
