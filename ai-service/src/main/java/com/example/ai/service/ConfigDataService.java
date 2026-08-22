package com.example.ai.service;

import com.example.ai.entity.ConfigDataRow;
import com.example.ai.entity.ConfigDefinition;
import com.example.ai.repository.ConfigDataRowRepository;
import com.example.ai.repository.ConfigDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigDataService {

    private final ConfigDataRowRepository rowRepository;
    private final ConfigDefinitionRepository defRepository;

    public long count(Long defId) {
        return rowRepository.countByConfigDefId(defId);
    }

    /**
     * 按配置定义分页查询行数据
     */
    public Page<ConfigDataRow> page(Long defId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), Math.min(size, 1000), Sort.by(Sort.Direction.ASC, "id"));
        return rowRepository.findByConfigDefId(defId, pageable);
    }

    /**
     * 按定义ID拉全量(用于导出/SSE推送),控制上限10万条
     */
    public List<ConfigDataRow> listAll(Long defId, int limit) {
        if (limit <= 0 || limit > 100000) limit = 100000;
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "id"));
        return rowRepository.findByConfigDefId(defId, pageable).getContent();
    }

    /**
     * 批量保存行,支持三种模式
     */
    @Transactional
    public Map<String, Object> batchSave(Long defId, String mode, List<com.example.ai.dto.ConfigDTO.RowItem> rows) {
        ConfigDefinition def = defRepository.findById(defId)
                .orElseThrow(() -> new RuntimeException("配置定义不存在:" + defId));

        int added = 0, updated = 0, deleted = 0;
        List<ConfigDataRow> saved = new ArrayList<>();

        if ("replace".equals(mode)) {
            rowRepository.deleteByConfigDefId(defId);
            for (var r : rows) {
                if (r.getData() == null) continue;
                ConfigDataRow row = ConfigDataRow.builder()
                        .configDefId(defId)
                        .rowData(cleanAndFillDefault(def, r.getData()))
                        .build();
                saved.add(rowRepository.save(row));
                added++;
            }
        } else if ("append".equals(mode)) {
            for (var r : rows) {
                if (r.getData() == null) continue;
                ConfigDataRow row = ConfigDataRow.builder()
                        .configDefId(defId)
                        .rowData(cleanAndFillDefault(def, r.getData()))
                        .build();
                saved.add(rowRepository.save(row));
                added++;
            }
        } else { // merge (default)
            for (var r : rows) {
                String mark = r.getMark() == null ? "" : r.getMark();
                if ("deleted".equals(mark) && r.getId() != null) {
                    rowRepository.deleteById(r.getId());
                    deleted++;
                    continue;
                }
                if (r.getData() == null) continue;
                if (r.getId() == null) {
                    ConfigDataRow row = ConfigDataRow.builder()
                            .configDefId(defId)
                            .rowData(cleanAndFillDefault(def, r.getData()))
                            .build();
                    saved.add(rowRepository.save(row));
                    added++;
                } else {
                    Optional<ConfigDataRow> opt = rowRepository.findById(r.getId());
                    if (opt.isPresent()) {
                        ConfigDataRow row = opt.get();
                        Map<String, Object> merged = new HashMap<>(row.getRowData());
                        merged.putAll(cleanAndFillDefault(def, r.getData()));
                        row.setRowData(merged);
                        saved.add(rowRepository.save(row));
                        updated++;
                    } else {
                        ConfigDataRow row = ConfigDataRow.builder()
                                .configDefId(defId)
                                .rowData(cleanAndFillDefault(def, r.getData()))
                                .build();
                        saved.add(rowRepository.save(row));
                        added++;
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("updated", updated);
        result.put("deleted", deleted);
        result.put("total", rowRepository.countByConfigDefId(defId));
        result.put("rows", saved.stream().limit(100).toList()); // 最多返回前100条
        return result;
    }

    /**
     * 条件更新: params = { where: { field, op, value }, set: { field: value } }
     */
    @Transactional
    public Map<String, Object> updateWhere(Long defId, Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> where = (Map<String, Object>) params.get("where");
        @SuppressWarnings("unchecked")
        Map<String, Object> setData = (Map<String, Object>) params.get("set");
        if (where == null || setData == null) throw new RuntimeException("updateWhere: where/set 参数缺失");

        String field = String.valueOf(where.getOrDefault("field", ""));
        String op = String.valueOf(where.getOrDefault("op", "="));
        Object value = where.get("value");

        List<ConfigDataRow> all = rowRepository.findByConfigDefIdOrderByIdAsc(defId);
        int affected = 0;
        List<Long> affectedIds = new ArrayList<>();
        for (ConfigDataRow row : all) {
            Object fv = row.getRowData().get(field);
            if (match(fv, op, value)) {
                for (Map.Entry<String, Object> entry : setData.entrySet()) {
                    row.getRowData().put(entry.getKey(), entry.getValue());
                }
                rowRepository.save(row);
                affected++;
                if (affectedIds.size() < 50) affectedIds.add(row.getId());
                if (affected >= 10000) break; // 单次最多影响1万行,保护数据
            }
        }
        return Map.of("affected", affected, "affectedIds", affectedIds);
    }

    /**
     * 按序号删除行: params = { fromRow: 1, toRow: 10 } 序号从1开始(表格行号语义)
     */
    @Transactional
    public Map<String, Object> deleteByIndex(Long defId, Map<String, Object> params) {
        int from = toInt(params.get("fromRow"), 1);
        int to = toInt(params.get("toRow"), Integer.MAX_VALUE);
        List<ConfigDataRow> all = rowRepository.findByConfigDefIdOrderByIdAsc(defId);
        List<Long> ids = new ArrayList<>();
        for (int i = from - 1; i < all.size() && i < to; i++) {
            ids.add(all.get(i).getId());
        }
        rowRepository.deleteAllById(ids);
        return Map.of("affected", ids.size());
    }

    /**
     * 字符串替换: params = { field, search, replace, regex }
     */
    @Transactional
    public Map<String, Object> replaceField(Long defId, Map<String, Object> params) {
        String field = String.valueOf(params.getOrDefault("field", ""));
        String search = String.valueOf(params.getOrDefault("search", ""));
        String replace = String.valueOf(params.getOrDefault("replace", ""));
        boolean regex = Boolean.TRUE.equals(params.get("regex"));
        List<ConfigDataRow> all = rowRepository.findByConfigDefIdOrderByIdAsc(defId);
        int affected = 0;
        for (ConfigDataRow row : all) {
            Object fv = row.getRowData().get(field);
            if (fv == null) continue;
            String s = String.valueOf(fv);
            String replaced = regex ? s.replaceAll(search, replace) : s.replace(search, replace);
            if (!s.equals(replaced)) {
                row.getRowData().put(field, replaced);
                rowRepository.save(row);
                affected++;
            }
        }
        return Map.of("affected", affected);
    }

    private boolean match(Object fv, String op, Object target) {
        if (fv == null) return false;
        try {
            return switch (op) {
                case "=", "==" -> String.valueOf(fv).equals(String.valueOf(target));
                case "!=" -> !String.valueOf(fv).equals(String.valueOf(target));
                case ">" -> num(fv) > num(target);
                case ">=" -> num(fv) >= num(target);
                case "<" -> num(fv) < num(target);
                case "<=" -> num(fv) <= num(target);
                case "contains", "like" -> String.valueOf(fv).contains(String.valueOf(target));
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private double num(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private int toInt(Object o, int dft) {
        if (o == null) return dft;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return dft; }
    }

    /** 按照字段定义填充默认值并过滤未知字段 */
    private Map<String, Object> cleanAndFillDefault(ConfigDefinition def, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> keys = def.getFieldKeys();
        for (var col : def.getColumns()) {
            String k = String.valueOf(col.get("key"));
            Object v = data.get(k);
            if (v == null || String.valueOf(v).isBlank()) {
                Object defVal = col.get("defaultValue");
                if (defVal != null) v = defVal;
            }
            out.put(k, v);
        }
        // 保留未知字段(兼容灵活场景)但不强制
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!keys.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
