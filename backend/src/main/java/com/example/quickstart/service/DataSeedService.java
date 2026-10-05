package com.example.quickstart.service;

import com.example.quickstart.dto.FieldDef;
import com.example.quickstart.dto.RefDef;
import com.example.quickstart.entity.ConfigData;
import com.example.quickstart.entity.ConfigDefinition;
import com.example.quickstart.entity.JobItem;
import com.example.quickstart.entity.JobRun;
import com.example.quickstart.repository.ConfigDataRepository;
import com.example.quickstart.repository.ConfigDefinitionRepository;
import com.example.quickstart.repository.JobItemRepository;
import com.example.quickstart.repository.JobRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动播种：H2 为空时写入 6 个示例配置项（各 8~30 行数据）；并清理服务重启中断的作业 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSeedService implements ApplicationRunner {

    private final ConfigDefinitionRepository defRepository;
    private final ConfigDataRepository dataRepository;
    private final JobRunRepository jobRunRepository;
    private final JobItemRepository jobItemRepository;
    private final ObjectMapper om;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        markInterruptedJobsFailed();
        if (defRepository.count() > 0) {
            return;
        }
        log.info("数据库为空，开始播种示例配置项...");
        seed();
        log.info("播种完成：6 个配置项");
    }

    private void markInterruptedJobsFailed() {
        List<JobRun> stale = jobRunRepository.findByStatusIn(List.of("PENDING", "RUNNING"));
        for (JobRun job : stale) {
            job.setStatus("FAILED");
            job.setError("服务重启，作业中断");
            job.setFinishedAt(LocalDateTime.now());
            jobRunRepository.save(job);
            for (JobItem item : jobItemRepository.findByJobIdOrderBySeqAsc(job.getId())) {
                if ("PENDING".equals(item.getStatus()) || "RUNNING".equals(item.getStatus())) {
                    item.setStatus("FAILED");
                    item.setMessage("服务重启，作业中断");
                    jobItemRepository.save(item);
                }
            }
        }
        if (!stale.isEmpty()) {
            log.info("已将 {} 个服务重启前未完成的作业标记为 FAILED", stale.size());
        }
    }

    private void seed() {
        // ==================== COUNTRY（GLOBAL，无依赖） ====================
        seedDef("COUNTRY", "国家字典", "GLOBAL", "国家/地区基础字典，含币种",
                List.of(
                        f("code", "国家编码", "STRING", true, null, 20, null),
                        f("name", "国家名称", "STRING", true, null, 100, null),
                        f("currency", "币种", "ENUM", true, List.of("CNY", "USD", "EUR", "JPY"), null, null)),
                List.of(),
                List.of(
                        row("code", "CN", "name", "中国", "currency", "CNY"),
                        row("code", "US", "name", "美国", "currency", "USD"),
                        row("code", "JP", "name", "日本", "currency", "JPY"),
                        row("code", "DE", "name", "德国", "currency", "EUR"),
                        row("code", "FR", "name", "法国", "currency", "EUR"),
                        row("code", "IT", "name", "意大利", "currency", "EUR"),
                        row("code", "ES", "name", "西班牙", "currency", "EUR"),
                        row("code", "NL", "name", "荷兰", "currency", "EUR")));

        // ==================== PROJECT_TYPE（GLOBAL，无依赖） ====================
        seedDef("PROJECT_TYPE", "项目类型", "GLOBAL", "项目分类字典及启停状态",
                List.of(
                        f("code", "类型编码", "STRING", true, null, 20, null),
                        f("name", "类型名称", "STRING", true, null, 100, null),
                        f("enabled", "是否启用", "BOOLEAN", false, null, null, null)),
                List.of(),
                List.of(
                        row("code", "IMPL", "name", "实施项目", "enabled", true),
                        row("code", "DEV", "name", "开发项目", "enabled", true),
                        row("code", "MAINT", "name", "运维项目", "enabled", true),
                        row("code", "CONSULT", "name", "咨询项目", "enabled", true),
                        row("code", "POC", "name", "概念验证项目", "enabled", true),
                        row("code", "UPGRADE", "name", "升级项目", "enabled", true),
                        row("code", "MIGR", "name", "迁移项目", "enabled", false),
                        row("code", "TRAIN", "name", "培训项目", "enabled", false)));

        // ==================== REGION_GROUP（REGION，依赖 COUNTRY） ====================
        seedDef("REGION_GROUP", "区域分组", "REGION", "按国家划分的区域分组",
                List.of(
                        f("code", "分组编码", "STRING", true, null, 20, null),
                        f("name", "分组名称", "STRING", true, null, 100, null),
                        f("countryCode", "所属国家", "STRING", true, null, 20, new RefDef("COUNTRY", "code"))),
                List.of("COUNTRY"),
                List.of(
                        row("code", "RG-CN", "name", "中国区", "countryCode", "CN"),
                        row("code", "RG-JP", "name", "日本区", "countryCode", "JP"),
                        row("code", "RG-DE", "name", "德国区", "countryCode", "DE"),
                        row("code", "RG-FR", "name", "法国区", "countryCode", "FR"),
                        row("code", "RG-IT", "name", "意大利区", "countryCode", "IT"),
                        row("code", "RG-ES", "name", "西班牙区", "countryCode", "ES"),
                        row("code", "RG-NL", "name", "荷兰区", "countryCode", "NL"),
                        row("code", "RG-US", "name", "美国区", "countryCode", "US")));

        // ==================== TAX_RATE（REGION，依赖 COUNTRY） ====================
        seedDef("TAX_RATE", "税率配置", "REGION", "按国家配置的税种税率",
                List.of(
                        f("countryCode", "国家编码", "STRING", true, null, 20, new RefDef("COUNTRY", "code")),
                        f("taxType", "税种", "ENUM", true, List.of("VAT", "CIT", "CT"), null, null),
                        f("rate", "税率", "NUMBER", true, null, null, null)),
                List.of("COUNTRY"),
                List.of(
                        row("countryCode", "CN", "taxType", "VAT", "rate", 0.13),
                        row("countryCode", "CN", "taxType", "CIT", "rate", 0.25),
                        row("countryCode", "CN", "taxType", "CT", "rate", 0.05),
                        row("countryCode", "US", "taxType", "CIT", "rate", 0.21),
                        row("countryCode", "US", "taxType", "CT", "rate", 0.07),
                        row("countryCode", "DE", "taxType", "VAT", "rate", 0.19),
                        row("countryCode", "DE", "taxType", "CIT", "rate", 0.30),
                        row("countryCode", "FR", "taxType", "VAT", "rate", 0.20),
                        row("countryCode", "FR", "taxType", "CIT", "rate", 0.25),
                        row("countryCode", "JP", "taxType", "CIT", "rate", 0.23),
                        row("countryCode", "JP", "taxType", "CT", "rate", 0.10),
                        row("countryCode", "IT", "taxType", "VAT", "rate", 0.22)));

        // ==================== PROJECT_INFO（PROJECT，依赖 PROJECT_TYPE + REGION_GROUP） ====================
        seedDef("PROJECT_INFO", "项目信息", "PROJECT", "项目主数据，引用项目类型与区域分组",
                List.of(
                        f("code", "项目编码", "STRING", true, null, 20, null),
                        f("name", "项目名称", "STRING", true, null, 100, null),
                        f("typeCode", "项目类型", "STRING", true, null, 20, new RefDef("PROJECT_TYPE", "code")),
                        f("regionCode", "所属区域", "STRING", true, null, 20, new RefDef("REGION_GROUP", "code")),
                        f("budget", "预算", "NUMBER", false, null, null, null),
                        f("startDate", "开始日期", "DATE", false, null, null, null)),
                List.of("PROJECT_TYPE", "REGION_GROUP"),
                List.of(
                        row("code", "P-0001", "name", "智慧园区一期", "typeCode", "IMPL", "regionCode", "RG-CN", "budget", 5800000, "startDate", "2026-01-15"),
                        row("code", "P-0002", "name", "制造执行系统升级", "typeCode", "UPGRADE", "regionCode", "RG-CN", "budget", 2300000, "startDate", "2026-02-01"),
                        row("code", "P-0003", "name", "德国工厂数字化", "typeCode", "DEV", "regionCode", "RG-DE", "budget", 4500000, "startDate", "2026-03-10"),
                        row("code", "P-0004", "name", "法国零售中台咨询", "typeCode", "CONSULT", "regionCode", "RG-FR", "budget", 1200000, "startDate", "2026-03-25"),
                        row("code", "P-0005", "name", "日本物流优化验证", "typeCode", "POC", "regionCode", "RG-JP", "budget", 800000, "startDate", "2026-04-05"),
                        row("code", "P-0006", "name", "美国电商扩展", "typeCode", "DEV", "regionCode", "RG-US", "budget", 6200000, "startDate", "2026-04-20"),
                        row("code", "P-0007", "name", "意大利供应链整合", "typeCode", "MIGR", "regionCode", "RG-IT", "budget", 3100000, "startDate", "2026-05-12"),
                        row("code", "P-0008", "name", "西班牙门店系统运维", "typeCode", "MAINT", "regionCode", "RG-ES", "budget", 950000, "startDate", "2026-06-01"),
                        row("code", "P-0009", "name", "荷兰仓储自动化", "typeCode", "DEV", "regionCode", "RG-NL", "budget", 2700000, "startDate", "2026-06-18"),
                        row("code", "P-0010", "name", "中国区二期推广", "typeCode", "IMPL", "regionCode", "RG-CN", "budget", 3900000, "startDate", "2026-07-08"),
                        row("code", "P-0011", "name", "德国能源管理试点", "typeCode", "POC", "regionCode", "RG-DE", "budget", 1500000, "startDate", "2026-08-22"),
                        row("code", "P-0012", "name", "日本客服中心培训", "typeCode", "TRAIN", "regionCode", "RG-JP", "budget", 600000, "startDate", "2026-09-30")));

        // ==================== UNIT_CONVERSION（GLOBAL，无依赖） ====================
        seedDef("UNIT_CONVERSION", "单位换算", "GLOBAL", "计量单位换算系数",
                List.of(
                        f("fromUnit", "源单位", "STRING", true, null, 20, null),
                        f("toUnit", "目标单位", "STRING", true, null, 20, null),
                        f("factor", "换算系数", "NUMBER", true, null, null, null)),
                List.of(),
                List.of(
                        row("fromUnit", "KM", "toUnit", "M", "factor", 1000),
                        row("fromUnit", "M", "toUnit", "CM", "factor", 100),
                        row("fromUnit", "KG", "toUnit", "G", "factor", 1000),
                        row("fromUnit", "T", "toUnit", "KG", "factor", 1000),
                        row("fromUnit", "L", "toUnit", "ML", "factor", 1000),
                        row("fromUnit", "H", "toUnit", "MIN", "factor", 60),
                        row("fromUnit", "MIN", "toUnit", "S", "factor", 60),
                        row("fromUnit", "M2", "toUnit", "CM2", "factor", 10000),
                        row("fromUnit", "GB", "toUnit", "MB", "factor", 1024),
                        row("fromUnit", "MB", "toUnit", "KB", "factor", 1024)));
    }

    // ================================ 辅助 ================================

    private FieldDef f(String name, String label, String type, boolean required,
                       List<String> options, Integer maxLength, RefDef ref) {
        return new FieldDef(name, label, type, required, options, maxLength, ref);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private void seedDef(String code, String name, String level, String description,
                         List<FieldDef> fields, List<String> dependsOn, List<Map<String, Object>> rows) {
        try {
            ConfigDefinition def = new ConfigDefinition();
            def.setCode(code);
            def.setName(name);
            def.setLevel(level);
            def.setDescription(description);
            def.setFieldsJson(om.writeValueAsString(fields));
            def.setDependsOnJson(om.writeValueAsString(dependsOn));
            defRepository.save(def);

            int rowNo = 1;
            for (Map<String, Object> row : rows) {
                ConfigData data = new ConfigData();
                data.setDefCode(code);
                data.setRowNo(rowNo++);
                data.setDataJson(om.writeValueAsString(row));
                dataRepository.save(data);
            }
        } catch (Exception e) {
            throw new IllegalStateException("播种失败: " + code, e);
        }
    }
}
