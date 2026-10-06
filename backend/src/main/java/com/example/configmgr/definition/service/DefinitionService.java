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
        validateFields(definition);
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

        // 校验新字段结构（编码唯一、至少一个主键、REFERENCE 引用完整）
        update.setCode(code);
        validateFields(update);

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

    /**
     * 字段结构校验：编码非空且唯一、至少一个主键、REFERENCE 字段必须声明引用目标。
     */
    private void validateFields(ConfigDefinition definition) {
        List<ConfigField> fields = definition.getFields();
        if (fields == null || fields.isEmpty()) {
            return; // 允许空定义（后续再补字段）
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (ConfigField f : fields) {
            if (f.getCode() == null || f.getCode().isBlank()) {
                throw new IllegalArgumentException("字段编码不能为空");
            }
            if (!f.getCode().matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("字段编码必须以字母开头，只允许字母、数字、下划线: " + f.getCode());
            }
            if (!codes.add(f.getCode())) {
                throw new IllegalArgumentException("字段编码重复: " + f.getCode());
            }
            if (f.getFieldType() == ConfigField.FieldType.REFERENCE
                    && (f.getRefDefCode() == null || f.getRefFieldCode() == null)) {
                throw new IllegalArgumentException("REFERENCE 字段 [" + f.getCode() + "] 必须指定引用配置与引用字段");
            }
        }
        if (fields.stream().noneMatch(ConfigField::isKey)) {
            throw new IllegalArgumentException("至少需要一个主键字段");
        }
    }

    public long count() {
        return definitionRepository.count();
    }
}
