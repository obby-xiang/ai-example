package com.example.configadmin.service;

import com.example.configadmin.dto.FieldDef;
import com.example.configadmin.dto.RowDraft;
import com.example.configadmin.entity.ConfigDef;
import com.example.configadmin.entity.Level;
import com.example.configadmin.repository.ConfigDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据初始化：配置定义 + 生效数据。
 * 覆盖全部层级（全局/地区/项目）、全部字段类型（文本/长文本/数字/布尔/日期/下拉/引用）
 * 以及配置依赖（SERVER_EXTEND 依赖 SERVER_PARAM）。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ConfigDefRepository defRepo;
    private final ConfigDataService dataService;

    public DataSeeder(ConfigDefRepository defRepo, ConfigDataService dataService) {
        this.defRepo = defRepo;
        this.dataService = dataService;
    }

    @Override
    public void run(String... args) {
        if (defRepo.count() > 0) {
            log.info("检测到已有配置定义，跳过演示数据初始化");
            return;
        }
        log.info("初始化演示数据（4 个配置定义 + 生效数据行）...");

        // 1. 服务器参数配置（全局）
        createDef("SERVER_PARAM", "服务器参数配置", Level.GLOBAL, "基础设施服务器参数", null,
                List.of(
                        text("server_name", "服务器名", true),
                        text("ip", "IP地址", true),
                        number("port", "端口", false, 1.0, 65535.0),
                        bool("primary_node", "是否主节点", false),
                        select("env", "环境", true, "生产", "测试", "开发"),
                        select("machine_room", "机房", false, "北京", "上海", "广州", "深圳")
                ));
        seedRows("SERVER_PARAM", List.of(
                row(null, Map.of("server_name", "srv-app-01", "ip", "10.0.0.11", "port", 8080, "primary_node", true, "env", "生产", "machine_room", "北京")),
                row(null, Map.of("server_name", "srv-app-02", "ip", "10.0.0.12", "port", 8080, "primary_node", false, "env", "生产", "machine_room", "北京")),
                row(null, Map.of("server_name", "srv-db-01", "ip", "10.0.0.21", "port", 3306, "primary_node", true, "env", "生产", "machine_room", "上海")),
                row(null, Map.of("server_name", "srv-cache-01", "ip", "10.0.0.31", "port", 6379, "primary_node", false, "env", "测试", "machine_room", "广州")),
                row(null, Map.of("server_name", "srv-dev-01", "ip", "10.0.0.41", "port", 8080, "primary_node", true, "env", "开发", "machine_room", "深圳"))
        ));

        // 2. 地区计费规则（地区）
        createDef("REGION_BILLING", "地区计费规则", Level.REGION, "按地区设置的计费规则", null,
                List.of(
                        number("unit_price", "单价(元)", true, 0.0, null),
                        number("discount", "折扣(0-1)", false, 0.0, 1.0),
                        select("currency", "币种", true, "CNY", "USD", "EUR"),
                        date("effective_date", "生效日期", false)
                ));
        seedRows("REGION_BILLING", List.of(
                row("华东区", Map.of("unit_price", 100, "discount", 0.9, "currency", "CNY", "effective_date", "2026-01-01")),
                row("华南区", Map.of("unit_price", 120, "discount", 0.85, "currency", "CNY", "effective_date", "2026-01-01")),
                row("华北区", Map.of("unit_price", 95, "discount", 1.0, "currency", "CNY", "effective_date", "2026-01-01"))
        ));

        // 3. 项目资源配额（项目）
        createDef("PROJECT_QUOTA", "项目资源配额", Level.PROJECT, "按项目设置的资源配额", null,
                List.of(
                        number("quota_cpu", "CPU核数", true, 1.0, 64.0),
                        number("quota_mem", "内存(GB)", true, 1.0, 512.0),
                        text("owner", "负责人", true),
                        textarea("remark", "备注", false)
                ));
        seedRows("PROJECT_QUOTA", List.of(
                row("P1001", Map.of("quota_cpu", 4, "quota_mem", 8, "owner", "张三", "remark", "电商主站")),
                row("P1002", Map.of("quota_cpu", 8, "quota_mem", 16, "owner", "李四", "remark", "数据中台")),
                row("P1003", Map.of("quota_cpu", 2, "quota_mem", 4, "owner", "王五", "remark", ""))
        ));

        // 4. 服务器扩容配置（全局，依赖 SERVER_PARAM，含引用字段）
        createDef("SERVER_EXTEND", "服务器扩容配置", Level.GLOBAL, "服务器扩容申请（依赖服务器参数配置）",
                List.of("SERVER_PARAM"),
                List.of(
                        reference("server_ref", "服务器名", "SERVER_PARAM", "server_name"),
                        number("add_cores", "扩容核数", true, 1.0, 128.0),
                        textarea("comment", "备注", false)
                ));
        seedRows("SERVER_EXTEND", List.of(
                row(null, Map.of("server_ref", "srv-app-01", "add_cores", 4, "comment", "扩容扩容")),
                row(null, Map.of("server_ref", "srv-db-01", "add_cores", 8, "comment", ""))
        ));

        log.info("演示数据初始化完成");
    }

    // ---------- 构造辅助 ----------

    private FieldDef text(String code, String label, boolean required) {
        FieldDef f = base(code, label, FieldDef.FieldType.TEXT, required);
        return f;
    }

    private FieldDef textarea(String code, String label, boolean required) {
        return base(code, label, FieldDef.FieldType.TEXTAREA, required);
    }

    private FieldDef number(String code, String label, boolean required, Double min, Double max) {
        FieldDef f = base(code, label, FieldDef.FieldType.NUMBER, required);
        f.setMin(min);
        f.setMax(max);
        return f;
    }

    private FieldDef bool(String code, String label, boolean required) {
        return base(code, label, FieldDef.FieldType.BOOLEAN, required);
    }

    private FieldDef date(String code, String label, boolean required) {
        return base(code, label, FieldDef.FieldType.DATE, required);
    }

    private FieldDef select(String code, String label, boolean required, String... options) {
        FieldDef f = base(code, label, FieldDef.FieldType.SELECT, required);
        f.setOptions(List.of(options));
        return f;
    }

    private FieldDef reference(String code, String label, String refDef, String refField) {
        FieldDef f = base(code, label, FieldDef.FieldType.REFERENCE, true);
        f.setRefDefCode(refDef);
        f.setRefFieldCode(refField);
        return f;
    }

    private FieldDef base(String code, String label, FieldDef.FieldType type, boolean required) {
        FieldDef f = new FieldDef();
        f.setCode(code);
        f.setLabel(label);
        f.setType(type);
        f.setRequired(required);
        return f;
    }

    private void createDef(String code, String name, Level level, String desc,
                           List<String> dependsOn, List<FieldDef> fields) {
        ConfigDef def = new ConfigDef();
        def.setCode(code);
        def.setName(name);
        def.setLevel(level);
        def.setDescription(desc);
        def.setFieldsJson(dataService.writeJson(fields));
        def.setDependsOnJson(dataService.writeJson(dependsOn == null ? List.of() : dependsOn));
        def.setSortOrder(defRepo.count() > 3 ? 4 : (int) defRepo.count() + 1);
        defRepo.save(def);
    }

    private RowDraft row(String scope, Map<String, Object> data) {
        RowDraft d = new RowDraft();
        d.setScope(scope);
        d.setData(new LinkedHashMap<>(data));
        d.setPublished(true);
        return d;
    }

    private void seedRows(String defCode, List<RowDraft> rows) {
        for (RowDraft r : rows) {
            dataService.saveRow(defCode, r);
        }
    }
}
