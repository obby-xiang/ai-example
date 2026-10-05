package com.example.configadmin.controller;

import com.example.configadmin.common.ApiException;
import com.example.configadmin.common.R;
import com.example.configadmin.dto.DefSaveRequest;
import com.example.configadmin.dto.DefView;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.service.ConfigDefService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 配置定义管理：动态配置建模（增删改查/启停）。 */
@RestController
@RequestMapping("/api/defs")
public class DefController {

    private final ConfigDefService defService;

    public DefController(ConfigDefService defService) {
        this.defService = defService;
    }

    @GetMapping
    public R<List<DefView>> list(@RequestParam(required = false) Boolean enabled) {
        List<ConfigDef> defs = enabled == null || !enabled ? defService.listAll() : defService.listEnabled();
        return R.ok(defs.stream().map(defService::toView).toList());
    }

    @GetMapping("/{code}")
    public R<DefView> get(@PathVariable String code) {
        return R.ok(defService.toView(defService.getByCode(code)));
    }

    @PostMapping
    public R<DefView> create(@Valid @RequestBody DefSaveRequest req) {
        return R.ok(defService.toView(defService.create(req)));
    }

    @PutMapping("/{code}")
    public R<DefView> update(@PathVariable String code, @Valid @RequestBody DefSaveRequest req) {
        return R.ok(defService.toView(defService.update(code, req)));
    }

    @DeleteMapping("/{code}")
    public R<?> delete(@PathVariable String code) {
        defService.delete(code);
        return R.ok();
    }

    @PostMapping("/{code}/toggle")
    public R<DefView> toggle(@PathVariable String code) {
        return R.ok(defService.toView(defService.toggle(code)));
    }

    @GetMapping("/check-code")
    public R<Boolean> checkCode(@RequestParam String code) {
        try {
            defService.getByCode(code);
            return R.ok(true);
        } catch (ApiException e) {
            return R.ok(false);
        }
    }
}
