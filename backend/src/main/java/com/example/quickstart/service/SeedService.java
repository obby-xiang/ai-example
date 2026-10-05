package com.example.quickstart.service;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDef;
import com.example.quickstart.entity.ConfigField;
import com.example.quickstart.entity.ScopeDict;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ConfigDefRepository;
import com.example.quickstart.repository.ConfigFieldRepository;
import com.example.quickstart.repository.ScopeDictRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 种子数据初始化：8 个配置项覆盖三层 + 依赖示范。
 * 重复启动不重复插入（幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedService {

    private final ConfigDefRepository defRepo;
    private final ConfigFieldRepository fieldRepo;
    private final ConfigDataRepository dataRepo;
    private final ScopeDictRepository scopeRepo;

    @Transactional
    public void seedIfEmpty() {
        if (defRepo.count() > 0) {
            return;
        }
        log.info("开始初始化种子数据 ...");
        seedScopes();
        seedConfig(new DefSpec("SYS_PARAM", "系统参数配置", "GLOBAL", "平台级全局参数", null, 40, List.of(
                new FieldSpec("paramKey", "参数名", "TEXT", true, null, true, "session.timeout.minutes"),
                new FieldSpec("paramValue", "参数值", "TEXT", true, null, false, "30"),
                new FieldSpec("paramType", "参数类型", "ENUM", true, "[\"STRING\",\"NUMBER\",\"BOOLEAN\"]", false, "NUMBER"),
                new FieldSpec("description", "参数说明", "TEXT", false, null, false, "会话超时时间（分钟）"),
                new FieldSpec("editable", "是否可在线修改", "BOOL", true, null, false, "true"))));
        seedConfig(new DefSpec("METRIC_DICT", "指标字典", "GLOBAL", "监控指标定义", null, 30, List.of(
                new FieldSpec("metricCode", "指标编码", "TEXT", true, null, true, "cpu.usage"),
                new FieldSpec("metricName", "指标名称", "TEXT", true, null, false, "CPU使用率"),
                new FieldSpec("metricUnit", "计量单位", "ENUM", true, "[\"百分比\",\"毫秒\",\"MB\",\"次\"]", false, "百分比"),
                new FieldSpec("alarmEnabled", "是否启用告警", "BOOL", true, null, false, "true"))));
        seedConfig(new DefSpec("ALARM_THRESHOLD", "告警阈值配置", "GLOBAL", "按指标配置告警阈值（依赖指标字典）",
                "[{\"def\":\"METRIC_DICT\",\"field\":\"metricCode\",\"refField\":\"metricCode\",\"label\":\"指标编码\"}]",
                120, List.of(
                        new FieldSpec("thresholdName", "阈值名称", "TEXT", true, null, true, "CPU使用率过高"),
                        new FieldSpec("metricCode", "指标编码", "TEXT", true, null, false, "cpu.usage"),
                        new FieldSpec("warnThreshold", "预警阈值", "DECIMAL", true, null, false, "80"),
                        new FieldSpec("criticalThreshold", "严重阈值", "DECIMAL", true, null, false, "95"),
                        new FieldSpec("effectiveDate", "生效日期", "DATE", true, null, false, "2026-01-01"))));
        seedConfig(new DefSpec("ROLE_DICT", "成员角色字典", "GLOBAL", "项目成员可分配的角色", null, 8, List.of(
                new FieldSpec("roleCode", "角色编码", "TEXT", true, null, true, "PM"),
                new FieldSpec("roleName", "角色名称", "TEXT", true, null, false, "项目经理"),
                new FieldSpec("permissionLevel", "权限级别", "INT", true, null, false, "1"),
                new FieldSpec("builtin", "是否内置角色", "BOOL", true, null, false, "true"))));
        seedConfig(new DefSpec("REGION_NETWORK", "地区网络配置", "REGION", "各地区网络带宽与出口", null, 15, List.of(
                new FieldSpec("__scope", "适用地区", "SCOPE", true, null, true, null),
                new FieldSpec("bandwidthMbps", "带宽(Mbps)", "INT", true, null, true, "1000"),
                new FieldSpec("gateway", "出口网关", "TEXT", true, null, false, "10.0.0.1"),
                new FieldSpec("redundancy", "是否双链路", "BOOL", true, null, false, "true"))));
        seedConfig(new DefSpec("REGION_TARIFF", "地区资费配置", "REGION", "各地区资费标准", null, 25, List.of(
                new FieldSpec("__scope", "适用地区", "SCOPE", true, null, true, null),
                new FieldSpec("tariffName", "资费名称", "TEXT", true, null, true, "标准版"),
                new FieldSpec("monthlyFee", "月费(元)", "DECIMAL", true, null, false, "299.00"),
                new FieldSpec("billingCycle", "计费周期", "ENUM", true, "[\"月付\",\"季付\",\"年付\"]", false, "月付"))));
        seedConfig(new DefSpec("PROJECT_MEMBER", "项目成员配置", "PROJECT", "项目成员及角色（依赖成员角色字典）",
                "[{\"def\":\"ROLE_DICT\",\"field\":\"roleCode\",\"refField\":\"roleCode\",\"label\":\"角色编码\"}]",
                80, List.of(
                        new FieldSpec("__scope", "适用项目", "SCOPE", true, null, true, null),
                        new FieldSpec("memberName", "成员姓名", "TEXT", true, null, true, "张三"),
                        new FieldSpec("memberEmail", "成员邮箱", "TEXT", true, null, false, "zhangsan@example.com"),
                        new FieldSpec("roleCode", "角色编码", "TEXT", true, null, false, "PM"),
                        new FieldSpec("joinDate", "加入日期", "DATE", true, null, false, "2026-01-15"))));
        seedConfig(new DefSpec("PROJECT_ENV", "项目环境配置", "PROJECT", "项目各环境资源规格", null, 40, List.of(
                new FieldSpec("__scope", "适用项目", "SCOPE", true, null, true, null),
                new FieldSpec("envName", "环境名称", "ENUM", true, "[\"开发\",\"测试\",\"预发\",\"生产\"]", true, "开发"),
                new FieldSpec("cpuCores", "CPU核数", "INT", true, null, false, "4"),
                new FieldSpec("memoryGb", "内存(GB)", "INT", true, null, false, "16"),
                new FieldSpec("enabled", "是否启用", "BOOL", true, null, false, "true"))));
        log.info("种子数据初始化完成");
    }

    private void seedScopes() {
        List<String[]> regions = List.of(
                new String[]{"REGION_NORTH", "华北地区"}, new String[]{"REGION_EAST", "华东地区"},
                new String[]{"REGION_SOUTH", "华南地区"}, new String[]{"REGION_SW", "西南地区"},
                new String[]{"REGION_NE", "东北地区"});
        List<String[]> projects = List.of(
                new String[]{"PRJ-1001", "智慧园区一期"}, new String[]{"PRJ-1002", "智慧园区二期"},
                new String[]{"PRJ-1003", "工业互联网平台"}, new String[]{"PRJ-1004", "数据中台"},
                new String[]{"PRJ-1005", "物联感知平台"});
        for (String[] r : regions) {
            ScopeDict s = new ScopeDict();
            s.setScopeType("REGION");
            s.setCode(r[0]);
            s.setName(r[1]);
            scopeRepo.save(s);
        }
        for (String[] p : projects) {
            ScopeDict s = new ScopeDict();
            s.setScopeType("PROJECT");
            s.setCode(p[0]);
            s.setName(p[1]);
            scopeRepo.save(s);
        }
    }

    private void seedConfig(DefSpec spec) {
        ConfigDef def = new ConfigDef();
        def.setCode(spec.code);
        def.setName(spec.name);
        def.setLevel(spec.level);
        def.setDescription(spec.description);
        def.setDependsOn(spec.dependsOn);
        def.setCreatedAt(LocalDateTime.now());
        def = defRepo.save(def);

        List<ConfigField> fields = new ArrayList<>();
        int i = 0;
        for (FieldSpec f : spec.fields) {
            ConfigField cf = new ConfigField();
            cf.setDefId(def.getId());
            cf.setFieldCode(f.code);
            cf.setFieldName(f.name);
            cf.setDataType(f.type);
            cf.setRequired(f.required);
            cf.setOptions(f.options);
            cf.setIsKey(f.isKey);
            cf.setSortNo(i++);
            cf.setSampleValue(f.sample);
            fields.add(cf);
        }
        fieldRepo.saveAll(fields);
        dataRepo.saveAll(generateRows(def, fields, spec.rowCount));
    }

    private List<ConfigData> generateRows(ConfigDef def, List<ConfigField> fields, int rowCount) {
        List<ScopeDict> scopes = scopeRepo.findByScopeTypeOrderByCodeAsc(
                "PROJECT".equals(def.getLevel()) ? "PROJECT" : "REGION");
        List<ConfigData> rows = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int n = 0; n < rowCount; n++) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (ConfigField f : fields) {
                values.put(f.getFieldCode(), sampleValueFor(def, f, n, base, scopes));
            }
            ConfigData d = new ConfigData();
            d.setDefId(def.getId());
            d.setScopeLevel("GLOBAL".equals(def.getLevel()) ? null : def.getLevel());
            d.setScopeValue("GLOBAL".equals(def.getLevel()) ? null : scopes.get(n % scopes.size()).getCode());
            d.setFieldValues(JsonUtil.write(values));
            d.setStatus("PUBLISHED");
            d.setCreatedAt(LocalDateTime.now());
            d.setUpdatedAt(LocalDateTime.now());
            rows.add(d);
        }
        return rows;
    }

    private Object sampleValueFor(ConfigDef def, ConfigField f, int n, LocalDate base, List<ScopeDict> scopes) {
        String code = def.getCode();
        String fc = f.getFieldCode();
        switch (f.getDataType()) {
            case "SCOPE":
                return scopes.isEmpty() ? "" : scopes.get(n % scopes.size()).getCode();
            case "BOOL":
                return n % 2 == 0 ? "true" : "false";
            case "INT":
                if ("permissionLevel".equals(fc)) {
                    return String.valueOf(n % 5 + 1);
                }
                return String.valueOf(100 + (n % 30) * 50);
            case "DECIMAL":
                return String.format("%.2f", 50 + (n % 40) * 12.5);
            case "DATE":
                return base.plusDays(n % 120).toString();
            case "ENUM": {
                List<String> opts = JsonUtil.read(f.getOptions(), new TypeReference<List<String>>() {
                });
                return opts.get(n % opts.size());
            }
            default:
                if ("paramKey".equals(fc)) {
                    return "param." + (n + 1);
                }
                if ("metricCode".equals(fc)) {
                    int m = n % 30;
                    return "metric." + String.format("%03d", m + 1);
                }
                if ("thresholdName".equals(fc)) {
                    return "阈值规则-" + String.format("%03d", n + 1);
                }
                if ("roleCode".equals(fc)) {
                    return "ROLE_" + String.format("%02d", n % 8 + 1);
                }
                if ("memberName".equals(fc)) {
                    return "用户" + String.format("%03d", n + 1);
                }
                if ("memberEmail".equals(fc)) {
                    return "user" + String.format("%03d", n + 1) + "@example.com";
                }
                if ("gateway".equals(fc)) {
                    return "10." + (n % 5) + ".0.1";
                }
                if ("tariffName".equals(fc)) {
                    return "资费套餐-" + (n / 5 + 1) + "-" + (n % 5 + 1);
                }
                if ("paramValue".equals(fc)) {
                    return String.valueOf((n % 10 + 1) * 10);
                }
                if ("metricName".equals(fc)) {
                    return "指标-" + String.format("%03d", n + 1);
                }
                if ("roleName".equals(fc)) {
                    return "角色-" + String.format("%02d", n % 8 + 1);
                }
                if ("description".equals(fc)) {
                    return "自动生成的参数说明 #" + (n + 1);
                }
                return fc + "-" + (n + 1);
        }
    }

    public record DefSpec(String code, String name, String level, String description, String dependsOn,
                          int rowCount, List<FieldSpec> fields) {
    }

    public record FieldSpec(String code, String name, String type, boolean required, String options,
                            boolean isKey, String sample) {
    }
}
