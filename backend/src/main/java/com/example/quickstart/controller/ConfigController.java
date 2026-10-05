package com.example.quickstart.controller;

import com.example.quickstart.dto.ConfigDefDto;
import com.example.quickstart.dto.QueryRequest;
import com.example.quickstart.dto.QueryResponse;
import com.example.quickstart.dto.TemplateInfoDto;
import com.example.quickstart.service.ConfigDefService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/config-defs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigDefService configDefService;

    @GetMapping
    public List<ConfigDefDto> list() {
        return configDefService.listDefs();
    }

    @GetMapping("/{code}")
    public ConfigDefDto get(@PathVariable String code) {
        return configDefService.getDef(code);
    }

    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest req) {
        return configDefService.query(req);
    }

    @GetMapping("/{code}/template-info")
    public TemplateInfoDto templateInfo(@PathVariable String code) {
        return configDefService.templateInfo(code);
    }
}
