package com.example.configmgr.definition.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import com.example.configmgr.definition.repo.ConfigFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DefinitionService {

    private final ConfigDefinitionRepository definitionRepository;
    private final ConfigFieldRepository fieldRepository;

    public List<ConfigDefinition> findAll() {
        return definitionRepository.findAllOrdered();
    }

    public List<ConfigDefinition> findByLevel(ConfigDefinition.ConfigLevel level) {
        return definitionRepository.findByLevel(level);
    }

    public ConfigDefinition findByCode(String code) {
        return definitionRepository.findByCode(code)
                .orElseThrow(() -> ResourceNotFoundException.of("配置定义", code));
    }

    @Transactional
    public ConfigDefinition save(ConfigDefinition definition) {
        // Set defCode on all fields
        if (definition.getFields() != null) {
            for (int i = 0; i < definition.getFields().size(); i++) {
                ConfigField f = definition.getFields().get(i);
                f.setDefCode(definition.getCode());
                f.setSortOrder(i);
            }
        }
        return definitionRepository.save(definition);
    }

    @Transactional
    public ConfigDefinition update(String code, ConfigDefinition update) {
        ConfigDefinition existing = findByCode(code);
        existing.setName(update.getName());
        existing.setDescription(update.getDescription());
        existing.setLevel(update.getLevel());
        existing.setSortOrder(update.getSortOrder());

        // Replace fields
        existing.getFields().clear();
        if (update.getFields() != null) {
            for (int i = 0; i < update.getFields().size(); i++) {
                ConfigField f = update.getFields().get(i);
                f.setId(null);
                f.setDefCode(code);
                f.setSortOrder(i);
                existing.getFields().add(f);
            }
        }
        return definitionRepository.save(existing);
    }

    public long count() {
        return definitionRepository.count();
    }
}
