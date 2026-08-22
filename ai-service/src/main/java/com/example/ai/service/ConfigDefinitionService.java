package com.example.ai.service;

import com.example.ai.entity.ConfigDefinition;
import com.example.ai.repository.ConfigDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigDefinitionService {

    private final ConfigDefinitionRepository repository;

    public List<ConfigDefinition> listAll() {
        return repository.findByEnabledTrueOrderByIdAsc();
    }

    public List<ConfigDefinition> listAllIncludingDisabled() {
        return repository.findAll();
    }

    public Optional<ConfigDefinition> getById(Long id) {
        return repository.findById(id);
    }

    public Optional<ConfigDefinition> getByCode(String code) {
        return repository.findByCode(code);
    }

    @Transactional
    public ConfigDefinition save(ConfigDefinition def) {
        return repository.save(def);
    }

    @Transactional
    public ConfigDefinition create(String code, String name, String description, List<Map<String, Object>> columns) {
        ConfigDefinition def = ConfigDefinition.builder()
                .code(code)
                .name(name)
                .description(description)
                .columns(columns)
                .enabled(true)
                .build();
        return repository.save(def);
    }

    @Transactional
    public void initSeedDataIfEmpty(DataSeedService seedService) {
        if (repository.count() == 0) {
            log.info("初始化配置定义种子数据...");
            seedService.initDefinitions();
            seedService.initDataRows();
            log.info("配置定义与业务数据初始化完成");
        }
    }
}
