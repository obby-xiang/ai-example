package com.example.configmgr.definition.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import com.example.configmgr.definition.repo.ConfigFieldRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefinitionService {

    private final ConfigDefinitionRepository definitionRepository;
    private final ConfigFieldRepository fieldRepository;
    private final ConfigDataRowRepository dataRowRepository;
    private final ConfigStagingRowRepository stagingRowRepository;
    private final TaskItemRepository taskItemRepository;

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
        ConfigDefinition saved = definitionRepository.save(definition);
        log.info("操作流水: 动作=CREATE_DEFINITION 对象=def#{} 摘要=name={},level={},fields={} 来源=界面/API 时间={}",
                saved.getCode(), saved.getName(), saved.getLevel(), saved.getFields().size(), LocalDateTime.now());
        return saved;
    }

    /**
     * 删除定义（FR-1.4 / ADR-11b：被引用检查 + 级联策略 + 无主体操作流水）。
     *
     * <h2>被引用检查（命中即拒绝，409 + 机器可读码）</h2>
     * <ol>
     * <li>{@code DEFINITION_HAS_DATA}：已发布数据行（{@code config_data_rows}）非空 ——
     *     <b>不做级联删除</b>。已发布数据是业务事实，删除定义不得连带删除它（ADR-7 的"发布"语义：
     *     数据由导入/发布通路写入与清理）；要删定义须先移除数据。</li>
     * <li>{@code DEFINITION_REFERENCED}：仍被他定义的 REFERENCE 字段指向 —— 否则被引用方消失后，
     *     引用方的字段级校验/动态渲染会指向不存在的配置（FR-1.2 引用完整性）。</li>
     * <li>{@code DEFINITION_IN_ACTIVE_TASK}：被未终态任务（ACTIVE）选中 —— 该任务的
     *     检查/导入/发布作业仍会按 defCode 取定义，删掉即任务中途断裂。</li>
     * </ol>
     *
     * <h2>级联策略（放行的场景）</h2>
     * <ul>
     * <li>{@code config_fields}：随定义删除（{@code @OneToMany cascade=ALL, orphanRemoval}）。</li>
     * <li>{@code config_staging_rows}：清理该配置的未发布暂存行（导入中间态；定义已不存在，
     *     这些行再没有可发布的落点）。仅历史任务（终态）的暂存行可能残留到这一步。</li>
     * <li>{@code task_items}：<b>保留</b>——历史任务的任务条目是任务台账的一部分，删除会篡改
     *     已完成任务的内容；定义消失只让该条目失去可打开的定义详情，不影响任务自身的终态记录。</li>
     * </ul>
     *
     * @return 级联清理计数（供调用方回显与操作流水留痕）
     */
    @Transactional
    public DeleteResult delete(String code) {
        ConfigDefinition existing = findByCode(code);

        long publishedRows = dataRowRepository.countByDefCode(code);
        if (publishedRows > 0) {
            throw new ConflictException("DEFINITION_HAS_DATA",
                    "配置定义 " + code + " 存在 " + publishedRows + " 行已发布数据，禁止删除（不级联删除业务数据）");
        }

        List<ConfigField> referencing = fieldRepository.findByRefDefCode(code);
        if (!referencing.isEmpty()) {
            String refs = referencing.stream()
                    .map(f -> f.getDefCode() + "." + f.getCode())
                    .distinct().sorted().reduce((a, b) -> a + ", " + b).orElse("");
            throw new ConflictException("DEFINITION_REFERENCED",
                    "配置定义 " + code + " 被他定义引用（" + refs + "），请先解除引用再删除");
        }

        long activeTaskRefs = taskItemRepository.countByDefCodeAndTaskStatus(code, Task.TaskStatus.ACTIVE);
        if (activeTaskRefs > 0) {
            throw new ConflictException("DEFINITION_IN_ACTIVE_TASK",
                    "配置定义 " + code + " 被 " + activeTaskRefs + " 个进行中任务选中，请先结束或删除这些任务");
        }

        long cascadedStagingRows = stagingRowRepository.countByDefCode(code);
        long cascadedFields = existing.getFields().size();
        String before = "name=" + existing.getName() + ",level=" + existing.getLevel()
                + ",fields=" + cascadedFields + ",stagingRows=" + cascadedStagingRows
                + ",publishedRows=0,referencedBy=0";

        stagingRowRepository.deleteByDefCode(code);
        definitionRepository.delete(existing); // fields 随 cascade=ALL + orphanRemoval 删除

        // FR-1.4「变更记录流水」本期落地形态：结构化流水行（动作/对象/前值摘要/来源/时间，无主体维度，
        // ADR-11b）。落库的 AuditLog 表属 DC-10 的 P2，与 FR-4.4 同口径，见证据文档【待裁决】。
        log.info("操作流水: 动作=DELETE_DEFINITION 对象=def#{} 前值摘要={} 来源=界面/API 时间={}",
                code, before, LocalDateTime.now());

        return new DeleteResult(code, cascadedFields, cascadedStagingRows);
    }

    /** 删除结果：级联清理计数（回显给调用方，同时作为操作流水的落点摘要）。 */
    public record DeleteResult(String code, long cascadedFields, long cascadedStagingRows) {
    }

    /**
     * 更新定义（整份 fields 替换语义，S4.4b 缺陷 2 修复）。
     *
     * <p><b>为什么不能"清空集合 + 挂 id=null 的新实体"</b>：{@code config_fields} 上有唯一键
     * {@code (def_code, code)}（V1__schema.sql {@code uq_field_def_code}），而 Hibernate 的
     * 动作顺序是 <b>先 INSERT 后 DELETE</b> —— 旧行还没删，新行（同 def_code + 同 code）已经插入
     * → 唯一键冲突 500（实测报文见 docs/evidence/S44b-五页视图验证.md §7.1）。
     *
     * <p>故这里按<b>字段编码匹配合并</b>：命中同编码的旧行原地改属性（保留 id，不产生 INSERT），
     * 未命中的旧行随 {@code orphanRemoval} 删除，编码不在旧集里才插入。三者与"Hibernate 先插后删"
     * 均不冲突：保留项无 INSERT、删除项与新增项的编码互不相交。
     */
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

        mergeFields(existing, update.getFields());
        ConfigDefinition saved = definitionRepository.save(existing);
        log.info("操作流水: 动作=UPDATE_DEFINITION 对象=def#{} 摘要=name={},level={},fields={}({}) 来源=界面/API 时间={}",
                code, existing.getName(), existing.getLevel(), existing.getFields().size(),
                existing.getFields().stream().map(ConfigField::getCode).toList(), LocalDateTime.now());
        return saved;
    }

    /**
     * 按字段编码合并字段集：同编码原地改属性（保留行 id，避免唯一键"先插后删"冲突）、
     * 旧集中未出现的编码随 orphanRemoval 删除、新编码才新建实体；sortOrder 一律按入参顺序重排。
     */
    private void mergeFields(ConfigDefinition existing, List<ConfigField> incoming) {
        Map<String, ConfigField> retained = new LinkedHashMap<>();
        for (ConfigField f : existing.getFields()) {
            retained.put(f.getCode(), f);
        }

        List<ConfigField> merged = new ArrayList<>();
        int order = 0;
        for (ConfigField src : incoming == null ? List.<ConfigField>of() : incoming) {
            ConfigField target = retained.remove(src.getCode());
            if (target == null) {
                target = new ConfigField();
                target.setCode(src.getCode());
            }
            target.setDefCode(existing.getCode());
            target.setLabel(src.getLabel());
            target.setFieldType(src.getFieldType());
            target.setRequired(src.isRequired());
            target.setKey(src.isKey());
            target.setOptionsJson(src.getOptionsJson());
            target.setRefDefCode(src.getRefDefCode());
            target.setRefFieldCode(src.getRefFieldCode());
            target.setSortOrder(order++);
            merged.add(target);
        }

        // retained 中剩余 = 入参里已移除的字段：从集合移除即被 orphanRemoval 删除
        existing.getFields().clear();
        existing.getFields().addAll(merged);
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
