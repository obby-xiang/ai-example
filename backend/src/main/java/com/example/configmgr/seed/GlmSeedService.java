package com.example.configmgr.seed;

import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.definition.service.DependencyResolver;
import com.example.configmgr.masterdata.repo.ProjectRepository;
import com.example.configmgr.masterdata.repo.RegionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * glm-5.3 种子并集移植：在基座既有 7 个定义之外追加 glm 的 8 个定义与三层级范围数据。
 * 与基座 {@code DataSeedRunner} 的 7 个定义完全隔离（不改其播种逻辑），
 * 幂等判据为逐定义 {@code existsByCode}，故单定义缺失时可自愈补播。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlmSeedService {

    private final ConfigDefinitionRepository definitionRepository;
    private final DefinitionService definitionService;
    private final ConfigDataService dataService;
    private final DependencyResolver dependencyResolver;
    private final RegionRepository regionRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void seedIfAbsent() throws Exception {
        List<DefSpec> specs = specs();
        List<DefSpec> pending = specs.stream()
                .filter(s -> !definitionRepository.existsByCode(s.code()))
                .toList();
        if (pending.isEmpty()) {
            log.info("GLM 种子定义已存在，跳过（{}/{}）", specs.size(), specs.size());
            return;
        }
        log.info("GLM 种子并集移植：待播种 {}/{} 个定义", pending.size(), specs.size());

        for (DefSpec spec : pending) {
            definitionService.save(toDefinition(spec));
        }

        // glm 的 dependsOn（定义级 JSON）在基座以 REFERENCE 字段表达，
        // 故用基座 DependencyResolver 求依赖先序，作为数据播种顺序。
        List<String> ordered = dependencyResolver.sort(pending.stream().map(DefSpec::code).toList());
        log.info("GLM 种子数据播种顺序（依赖拓扑序）：{}", ordered);

        for (String code : ordered) {
            DefSpec spec = pending.stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
            seedData(spec);
        }
        log.info("GLM 种子并集移植完成：新增 {} 个定义", pending.size());
    }

    private void seedData(DefSpec spec) throws Exception {
        List<String> keyFields = spec.fields().stream().filter(FieldSpec::key).map(FieldSpec::code).toList();
        List<String> scopeCodes = scopeCodes(spec.level());
        int written = 0;
        if (scopeCodes.isEmpty()) {
            written = dataService.batchSave(spec.code(), "GLOBAL", null, rowsOf(spec, null, 0), keyFields);
        } else {
            for (int s = 0; s < scopeCodes.size(); s++) {
                String scopeCode = scopeCodes.get(s);
                written += dataService.batchSave(spec.code(), spec.level().name(), scopeCode,
                        rowsOf(spec, scopeCode, s), keyFields);
            }
        }
        log.info("GLM 种子：{} 落库 {} 行（每范围 {} 行，范围 {}）",
                spec.code(), written, spec.rowsPerScope(), scopeCodes);
    }

    /**
     * 范围取值改用基座主数据：glm 自带 ScopeDict 的编码体系与基座 regions/projects 不同，
     * 且基座 {@code projects.region_code} 非空，搬运 glm 项目需伪造地区归属。
     */
    private List<String> scopeCodes(ConfigDefinition.ConfigLevel level) {
        if (level == ConfigDefinition.ConfigLevel.REGION) {
            return regionRepository.findAll().stream().map(r -> r.getCode()).sorted().toList();
        }
        if (level == ConfigDefinition.ConfigLevel.PROJECT) {
            return projectRepository.findAll().stream().map(p -> p.getCode()).sorted().toList();
        }
        return List.of();
    }

    // ---------- 数据生成 ----------
    // 命名约定：s = 范围序号（0 起）、k = 范围内行序（0 起）、g = 全局行序（s * rowsPerScope + k）。
    // glm 生成器用单一全局行号 n 同时承担这三个角色（隐含 scopeCount=5），此处按语义拆开：
    // 范围内周期性字段用 k、范围内序号型字段用 g、范围相关字段用 s。
    // GLOBAL 定义无范围，k == g == n，故其数据与 glm 逐值一致。

    private List<Map<String, Object>> rowsOf(DefSpec spec, String scopeCode, int scopeOrdinal) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int k = 0; k < spec.rowsPerScope(); k++) {
            Map<String, Object> values = new LinkedHashMap<>();
            int g = scopeOrdinal * spec.rowsPerScope() + k;
            for (FieldSpec f : spec.fields()) {
                values.put(f.code(), valueOf(f, k, g, scopeCode, scopeOrdinal));
            }
            rows.add(values);
        }
        return rows;
    }

    private Object valueOf(FieldSpec f, int k, int g, String scopeCode, int scopeOrdinal) {
        return switch (f.type()) {
            case SCOPE -> scopeCode;
            case BOOL -> k % 2 == 0;
            case INT -> "permissionLevel".equals(f.code()) ? g % 5 + 1 : 100 + (k % 30) * 50;
            case DECIMAL -> Math.round((50 + (k % 40) * 12.5) * 100) / 100.0;
            case DATE -> LocalDate.of(2026, 1, 1).plusDays(k % 120).toString();
            case ENUM -> f.enumOptions().get(k % f.enumOptions().size());
            case TEXT -> textValue(f.code(), k, g, scopeOrdinal);
        };
    }

    private String textValue(String code, int k, int g, int scopeOrdinal) {
        return switch (code) {
            case "paramKey" -> "param." + (g + 1);
            case "paramValue" -> String.valueOf((k % 10 + 1) * 10);
            case "description" -> "自动生成的参数说明 #" + (g + 1);
            case "metricCode" -> "metric." + String.format("%03d", g % 30 + 1);
            case "metricName" -> "指标-" + String.format("%03d", g + 1);
            case "roleCode" -> "ROLE_" + String.format("%02d", g % 8 + 1);
            case "roleName" -> "角色-" + String.format("%02d", g % 8 + 1);
            case "thresholdName" -> "阈值规则-" + String.format("%03d", g + 1);
            case "gateway" -> "10." + scopeOrdinal + ".0.1";
            case "tariffName" -> "资费套餐-" + (k + 1) + "-" + (scopeOrdinal + 1);
            case "memberName" -> "用户" + String.format("%03d", g + 1);
            case "memberEmail" -> "user" + String.format("%03d", g + 1) + "@example.com";
            default -> code + "-" + (g + 1);
        };
    }

    // ---------- 模型映射：glm 定义模型 → 基座 ConfigDefinition/ConfigField ----------

    private ConfigDefinition toDefinition(DefSpec spec) throws Exception {
        ConfigDefinition def = new ConfigDefinition();
        def.setCode(spec.code());
        def.setName(spec.name());
        def.setLevel(spec.level());
        def.setDescription(spec.description());
        def.setSortOrder(spec.sortOrder());

        List<ConfigField> fields = new ArrayList<>();
        for (FieldSpec f : spec.fields()) {
            ConfigField cf = new ConfigField();
            cf.setCode(f.code());
            cf.setLabel(f.label());
            cf.setFieldType(f.refDefCode() != null ? ConfigField.FieldType.REFERENCE : baseType(f.type()));
            cf.setRequired(f.required());
            cf.setKey(f.key());
            cf.setDefCode(spec.code());
            if (f.type() == GlmType.ENUM) {
                List<Map<String, String>> opts = new ArrayList<>();
                for (String o : f.enumOptions()) {
                    opts.add(Map.of("value", o, "label", o));
                }
                cf.setOptionsJson(objectMapper.writeValueAsString(opts));
            }
            cf.setRefDefCode(f.refDefCode());
            cf.setRefFieldCode(f.refFieldCode());
            fields.add(cf);
        }
        def.setFields(fields);
        return def;
    }

    /** glm 字段类型 → 基座 FieldType（基座无 SCOPE 类型，SCOPE 落为范围载体字符串字段）。 */
    private ConfigField.FieldType baseType(GlmType type) {
        return switch (type) {
            case TEXT, SCOPE -> ConfigField.FieldType.STRING;
            case INT, DECIMAL -> ConfigField.FieldType.NUMBER;
            case BOOL -> ConfigField.FieldType.BOOLEAN;
            case DATE -> ConfigField.FieldType.DATE;
            case ENUM -> ConfigField.FieldType.ENUM;
        };
    }

    // ---------- 8 个 glm 定义（字段/必填/业务键/枚举项/引用/依赖逐项对齐 glm SeedService） ----------

    private List<DefSpec> specs() {
        return List.of(
            new DefSpec("SYS_PARAM", "系统参数配置", ConfigDefinition.ConfigLevel.GLOBAL, "平台级全局参数", 100, 40, List.of(
                f("paramKey", "参数名", GlmType.TEXT, true, true, null, null, null),
                f("paramValue", "参数值", GlmType.TEXT, true, false, null, null, null),
                f("paramType", "参数类型", GlmType.ENUM, true, false, List.of("STRING", "NUMBER", "BOOLEAN"), null, null),
                f("description", "参数说明", GlmType.TEXT, false, false, null, null, null),
                f("editable", "是否可在线修改", GlmType.BOOL, true, false, null, null, null))),
            new DefSpec("METRIC_DICT", "指标字典", ConfigDefinition.ConfigLevel.GLOBAL, "监控指标定义", 101, 30, List.of(
                f("metricCode", "指标编码", GlmType.TEXT, true, true, null, null, null),
                f("metricName", "指标名称", GlmType.TEXT, true, false, null, null, null),
                f("metricUnit", "计量单位", GlmType.ENUM, true, false, List.of("百分比", "毫秒", "MB", "次"), null, null),
                f("alarmEnabled", "是否启用告警", GlmType.BOOL, true, false, null, null, null))),
            new DefSpec("ALARM_THRESHOLD", "告警阈值配置", ConfigDefinition.ConfigLevel.GLOBAL,
                "按指标配置告警阈值（依赖指标字典）", 102, 120, List.of(
                f("thresholdName", "阈值名称", GlmType.TEXT, true, true, null, null, null),
                f("metricCode", "指标编码", GlmType.TEXT, true, false, null, "METRIC_DICT", "metricCode"),
                f("warnThreshold", "预警阈值", GlmType.DECIMAL, true, false, null, null, null),
                f("criticalThreshold", "严重阈值", GlmType.DECIMAL, true, false, null, null, null),
                f("effectiveDate", "生效日期", GlmType.DATE, true, false, null, null, null))),
            new DefSpec("ROLE_DICT", "成员角色字典", ConfigDefinition.ConfigLevel.GLOBAL, "项目成员可分配的角色", 103, 8, List.of(
                f("roleCode", "角色编码", GlmType.TEXT, true, true, null, null, null),
                f("roleName", "角色名称", GlmType.TEXT, true, false, null, null, null),
                f("permissionLevel", "权限级别", GlmType.INT, true, false, null, null, null),
                f("builtin", "是否内置角色", GlmType.BOOL, true, false, null, null, null))),
            new DefSpec("REGION_NETWORK", "地区网络配置", ConfigDefinition.ConfigLevel.REGION, "各地区网络带宽与出口", 104, 3, List.of(
                f("regionCode", "适用地区", GlmType.SCOPE, true, true, null, null, null),
                f("bandwidthMbps", "带宽(Mbps)", GlmType.INT, true, true, null, null, null),
                f("gateway", "出口网关", GlmType.TEXT, true, false, null, null, null),
                f("redundancy", "是否双链路", GlmType.BOOL, true, false, null, null, null))),
            new DefSpec("REGION_TARIFF", "地区资费配置", ConfigDefinition.ConfigLevel.REGION, "各地区资费标准", 105, 5, List.of(
                f("regionCode", "适用地区", GlmType.SCOPE, true, true, null, null, null),
                f("tariffName", "资费名称", GlmType.TEXT, true, true, null, null, null),
                f("monthlyFee", "月费(元)", GlmType.DECIMAL, true, false, null, null, null),
                f("billingCycle", "计费周期", GlmType.ENUM, true, false, List.of("月付", "季付", "年付"), null, null))),
            new DefSpec("PROJECT_MEMBER", "项目成员配置", ConfigDefinition.ConfigLevel.PROJECT,
                "项目成员及角色（依赖成员角色字典）", 106, 16, List.of(
                f("projectCode", "适用项目", GlmType.SCOPE, true, true, null, null, null),
                f("memberName", "成员姓名", GlmType.TEXT, true, true, null, null, null),
                f("memberEmail", "成员邮箱", GlmType.TEXT, true, false, null, null, null),
                f("roleCode", "角色编码", GlmType.TEXT, true, false, null, "ROLE_DICT", "roleCode"),
                f("joinDate", "加入日期", GlmType.DATE, true, false, null, null, null))),
            new DefSpec("PROJECT_ENV", "项目环境配置", ConfigDefinition.ConfigLevel.PROJECT, "项目各环境资源规格", 107, 4, List.of(
                f("projectCode", "适用项目", GlmType.SCOPE, true, true, null, null, null),
                f("envName", "环境名称", GlmType.ENUM, true, true, List.of("开发", "测试", "预发", "生产"), null, null),
                f("cpuCores", "CPU核数", GlmType.INT, true, false, null, null, null),
                f("memoryGb", "内存(GB)", GlmType.INT, true, false, null, null, null),
                f("enabled", "是否启用", GlmType.BOOL, true, false, null, null, null)))
        );
    }

    private FieldSpec f(String code, String label, GlmType type, boolean required, boolean key,
                        List<String> enumOptions, String refDefCode, String refFieldCode) {
        return new FieldSpec(code, label, type, required, key, enumOptions, refDefCode, refFieldCode);
    }

    /** glm ConfigField.dataType 的 7 种取值（glm 实体注释：TEXT/INT/DECIMAL/DATE/BOOL/ENUM/SCOPE）。 */
    private enum GlmType { TEXT, INT, DECIMAL, BOOL, DATE, ENUM, SCOPE }

    private record FieldSpec(String code, String label, GlmType type, boolean required, boolean key,
                             List<String> enumOptions, String refDefCode, String refFieldCode) {
    }

    /** rowsPerScope：GLOBAL 定义即总行数；REGION/PROJECT 定义为每范围行数（glm 的每范围行密度）。 */
    private record DefSpec(String code, String name, ConfigDefinition.ConfigLevel level, String description,
                           int sortOrder, int rowsPerScope, List<FieldSpec> fields) {
    }
}
