package com.example.configmgr.data.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigDataService {

    @PersistenceContext
    private EntityManager entityManager;

    private final ConfigDataRowRepository dataRowRepository;
    private final DefinitionService definitionService;
    private final ObjectMapper objectMapper;

    public List<ConfigDataRow> findAll(String defCode, String scopeType, String scopeKey) {
        return dataRowRepository.findRowsInScope(defCode,
                scopeType != null ? scopeType : "GLOBAL", scopeKey);
    }

    public Page<ConfigDataRow> findPaged(String defCode, String scopeType, String scopeKey, int page, int size) {
        return dataRowRepository.findRowsInScopePaged(
                defCode,
                scopeType != null ? scopeType : "GLOBAL",
                scopeKey,
                PageRequest.of(page, size));
    }

    /**
     * 带查询条件的分页列表（S4.4b 遗留 3：列表端点补 {@code conditions} 入参）。
     *
     * <p>口径与 {@code GET /api/data/{defCode}/count}、导出作业（{@link com.example.configmgr.job.service.ExportJobRunner}）
     * <b>同源</b>：都走 {@link ConditionEvaluator} 逐行求值，故"前端预估命中行数"与"翻页看到的行"不会打架。
     *
     * <p>注意口径细节（与 /count 一致）：有条件时按该配置的<b>全部已发布行</b>过滤（仅按 scopeKey 收窄，
     * 不再按 scopeType 收窄），这是既有 /count 的实现语义；无条件时仍走 DB 分页并按 scopeType + scopeKey 收窄。
     */
    public Page<ConfigDataRow> findPagedFiltered(String defCode, String scopeKey, QueryCondition condition,
                                                  int page, int size) {
        List<ConfigDataRow> matched = filterRows(defCode, scopeKey, condition);
        PageRequest pageable = PageRequest.of(page, size);
        int from = (int) Math.min(pageable.getOffset(), matched.size());
        int to = (int) Math.min(from + pageable.getPageSize(), matched.size());
        return new PageImpl<>(matched.subList(from, to), pageable, matched.size());
    }

    /** 条件命中行数（与 {@link #findPagedFiltered} 同一份过滤逻辑）。 */
    public long countFiltered(String defCode, String scopeKey, QueryCondition condition) {
        return filterRows(defCode, scopeKey, condition).size();
    }

    /** 条件 JSON（可为空）→ {@link QueryCondition}；解析失败即 400（口径与 /count 一致）。 */
    public QueryCondition parseCondition(String conditions) {
        if (conditions == null || conditions.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(conditions, QueryCondition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("条件 JSON 解析失败: " + e.getMessage());
        }
    }

    /** 逐行求值：范围键收窄 + {@link ConditionEvaluator} 字段级条件（AND）。 */
    private List<ConfigDataRow> filterRows(String defCode, String scopeKey, QueryCondition cond) {
        return dataRowRepository.findByDefCodeOrderByRowKey(defCode).stream()
                .filter(r -> scopeKey == null || scopeKey.isBlank() || scopeKey.equals(r.getScopeKey()))
                .filter(r -> {
                    try {
                        Map<String, Object> data = objectMapper.readValue(r.getDataJson(), new TypeReference<>() {});
                        return ConditionEvaluator.matches(data, cond);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    public long count(String defCode, String scopeType, String scopeKey) {
        return dataRowRepository.countByScope(defCode,
                scopeType != null ? scopeType : "GLOBAL", scopeKey);
    }

    @Transactional
    public ConfigDataRow save(String defCode, String scopeType, String scopeKey,
                               String rowKey, Map<String, Object> data) throws Exception {
        String dataJson = objectMapper.writeValueAsString(data);
        Optional<ConfigDataRow> existing = dataRowRepository
                .findRow(defCode, scopeType != null ? scopeType : "GLOBAL", scopeKey, rowKey);

        ConfigDataRow row = existing.orElseGet(ConfigDataRow::new);
        row.setDefCode(defCode);
        row.setScopeType(scopeType != null ? scopeType : "GLOBAL");
        row.setScopeKey(scopeKey);
        row.setRowKey(rowKey);
        row.setDataJson(dataJson);
        return dataRowRepository.save(row);
    }

    @Transactional
    public int batchSave(String defCode, String scopeType, String scopeKey,
                          List<Map<String, Object>> rows, List<String> keyFields) throws Exception {
        int count = 0;
        for (Map<String, Object> data : rows) {
            String rowKey = buildRowKey(data, keyFields);
            save(defCode, scopeType, scopeKey, rowKey, data);
            count++;
        }
        return count;
    }

    public Map<String, Object> parseRow(ConfigDataRow row) throws Exception {
        return objectMapper.readValue(row.getDataJson(), new TypeReference<>() {});
    }

    public static String buildRowKey(Map<String, Object> data, List<String> keyFields) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyFields) {
            if (sb.length() > 0) sb.append("|");
            Object v = data.get(k);
            sb.append(v != null ? v.toString() : "");
        }
        return sb.toString();
    }

    // ────────────── 发布路径（ADR-7 / Q14：行级 upsert + 范围差集删除）──────────────

    /**
     * 行级 upsert：按「配置项 × 业务键（含范围维度）」定位已发布行。
     *
     * <ul>
     * <li>{@code existing != null}：原地更新字段值，<b>保留行 id</b>（行身份延续，非删建），
     * 并施加 {@code OPTIMISTIC_FORCE_INCREMENT} —— combined 原语义：即使内容与库中一致
     * 也强制版本递增，否则 Hibernate 脏检查会跳过 UPDATE，快照比对（并发冲突检测）将失效；</li>
     * <li>{@code existing == null}：插入新业务键行，版本从 1 起。</li>
     * </ul>
     */
    @Transactional
    public ConfigDataRow upsertPublished(String defCode, String scopeType, String scopeKey,
                                         String rowKey, String dataJson, ConfigDataRow existing) {
        ConfigDataRow row = existing != null ? existing : new ConfigDataRow();
        row.setDefCode(defCode);
        row.setScopeType(scopeType);
        row.setScopeKey(scopeKey);
        row.setRowKey(rowKey);
        row.setDataJson(dataJson);
        if (existing != null) {
            entityManager.lock(existing, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        return dataRowRepository.save(row);
    }

    /**
     * 范围差集删除：删除「本次导入覆盖范围」内、未出现在导入数据中的旧 PUBLISHED 行
     * —— "范围内替换"业务语义的实现（glm 语义等价的 upsert 版）。
     *
     * <p>
     * 并发保护（无静默覆盖）：未出现在导入数据、但在本次导入之后被其他操作修改过的行
     * <b>不删除</b>，作为冲突候选返回给调用方落 issue —— 否则并发发布同范围时，
     * 后发者会静默删掉先发者刚写入的行（该行不在后发者的导入数据里，行级快照比对覆盖不到）。
     *
     * @param staged     本次导入涉及的暂存行（其 scopeKey 集合 = 覆盖范围，rowKey 集合 = 保留键）
     * @param importedAt 本次导入时刻（暂存行创建时间的最早值）；null 表示不做并发保护
     * @return 被并发保护而未删除的已发布行
     */
    @Transactional
    public List<ConfigDataRow> deleteRowsOutOfRange(String defCode, String scopeType,
                                                    List<ConfigStagingRow> staged,
                                                    LocalDateTime importedAt) {
        Map<String, Set<String>> keptKeysByScope = new HashMap<>();
        for (ConfigStagingRow sr : staged) {
            keptKeysByScope.computeIfAbsent(sr.getScopeKey(), k -> new HashSet<>()).add(sr.getRowKey());
        }

        List<ConfigDataRow> protectedRows = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : keptKeysByScope.entrySet()) {
            for (ConfigDataRow row : dataRowRepository.findRowsInScope(defCode, scopeType, entry.getKey())) {
                if (entry.getValue().contains(row.getRowKey())) {
                    continue; // 出现在导入数据中：交给 upsert 更新，行 id 保留
                }
                if (importedAt != null && row.getUpdatedAt() != null
                        && !row.getUpdatedAt().isBefore(importedAt)) {
                    protectedRows.add(row);
                    continue;
                }
                dataRowRepository.delete(row);
            }
        }
        return protectedRows;
    }
}
