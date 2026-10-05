package com.example.configadmin.controller;

import com.example.configadmin.common.R;
import com.example.configadmin.entity.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统元信息（验证/运维用）。 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${spring.ai.openai.chat.options.model:deepseek-flash}")
    private String model;

    @Value("${server.port:18080}")
    private String port;

    @GetMapping
    public R<Map<String, Object>> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", appName);
        m.put("version", "1.0.0");
        m.put("model", model);
        m.put("modelProvider", "DeepSeek (OpenAI 兼容)");
        m.put("port", port);
        m.put("levels", Level.values());
        m.put("time", java.time.LocalDateTime.now().toString());
        return R.ok(m);
    }
}
