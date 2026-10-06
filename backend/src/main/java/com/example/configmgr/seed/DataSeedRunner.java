package com.example.configmgr.seed;

import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.masterdata.entity.Project;
import com.example.configmgr.masterdata.entity.Region;
import com.example.configmgr.masterdata.repo.ProjectRepository;
import com.example.configmgr.masterdata.repo.RegionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeedRunner implements ApplicationRunner {

    private final RegionRepository regionRepository;
    private final ProjectRepository projectRepository;
    private final DefinitionService definitionService;
    private final ConfigDataService dataService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (regionRepository.count() > 0) {
            log.info("Seed data already exists, skipping");
            return;
        }
        log.info("Seeding initial data...");
        seedRegions();
        seedProjects();
        seedDefinitions();
        log.info("Seed data complete");
    }

    private void seedRegions() {
        List<Region> regions = List.of(
            region("HE", "华东区"), region("HS", "华南区"),
            region("HB", "华北区"), region("XN", "西南区")
        );
        regionRepository.saveAll(regions);
    }

    private void seedProjects() {
        List<Project> projects = List.of(
            project("HE-P001", "上海智慧园区", "HE"),
            project("HE-P002", "杭州数字工厂", "HE"),
            project("HS-P001", "广州南沙项目", "HS"),
            project("HS-P002", "深圳湾科技园", "HS"),
            project("HB-P001", "北京朝阳总部", "HB"),
            project("XN-P001", "成都天府新区", "XN"),
            project("XN-P002", "重庆两江新区", "XN")
        );
        projectRepository.saveAll(projects);
    }

    private void seedDefinitions() throws Exception {
        // 1. CURRENCY (GLOBAL)
        ConfigDefinition currency = def("CURRENCY", "货币字典", ConfigDefinition.ConfigLevel.GLOBAL,
            "企业使用的货币种类配置", 0,
            List.of(
                field("code", "货币代码", ConfigField.FieldType.STRING, true, true, 0, null, null),
                field("name", "货币名称", ConfigField.FieldType.STRING, true, false, 1, null, null),
                field("exchangeRate", "对人民币汇率", ConfigField.FieldType.NUMBER, true, false, 2, null, null),
                field("decimalPlaces", "小数位数", ConfigField.FieldType.NUMBER, false, false, 3, null, null)
            ));
        definitionService.save(currency);
        // Seed data
        List<Map<String,Object>> currencyRows = List.of(
            Map.of("code","CNY","name","人民币","exchangeRate",1.0,"decimalPlaces",2),
            Map.of("code","USD","name","美元","exchangeRate",7.24,"decimalPlaces",2),
            Map.of("code","EUR","name","欧元","exchangeRate",7.89,"decimalPlaces",2),
            Map.of("code","GBP","name","英镑","exchangeRate",9.15,"decimalPlaces",2),
            Map.of("code","JPY","name","日元","exchangeRate",0.048,"decimalPlaces",0)
        );
        dataService.batchSave("CURRENCY","GLOBAL",null,currencyRows,List.of("code"));

        // 2. DOC_TYPE (GLOBAL)
        String docTypeOpts = objectMapper.writeValueAsString(List.of(
            Map.of("value","PURCHASE","label","采购单"),
            Map.of("value","SALES","label","销售单"),
            Map.of("value","TRANSFER","label","调拨单"),
            Map.of("value","INVENTORY","label","盘点单")
        ));
        ConfigDefinition docType = def("DOC_TYPE","单据类型",ConfigDefinition.ConfigLevel.GLOBAL,"业务单据类型定义",1,
            List.of(
                field("code","单据编码",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("name","单据名称",ConfigField.FieldType.STRING,true,false,1,null,null),
                field("category","单据分类",ConfigField.FieldType.ENUM,true,false,2,docTypeOpts,null),
                field("enabled","是否启用",ConfigField.FieldType.BOOLEAN,true,false,3,null,null)
            ));
        definitionService.save(docType);
        List<Map<String,Object>> docTypeRows = List.of(
            Map.of("code","PO","name","采购订单","category","PURCHASE","enabled",true),
            Map.of("code","SO","name","销售订单","category","SALES","enabled",true),
            Map.of("code","TO","name","调拨申请","category","TRANSFER","enabled",true),
            Map.of("code","IC","name","库存盘点","category","INVENTORY","enabled",true),
            Map.of("code","PR","name","请购单","category","PURCHASE","enabled",true)
        );
        dataService.batchSave("DOC_TYPE","GLOBAL",null,docTypeRows,List.of("code"));

        // 3. APPROVE_ROLE (GLOBAL)
        ConfigDefinition approveRole = def("APPROVE_ROLE","审批角色",ConfigDefinition.ConfigLevel.GLOBAL,"审批流程角色定义",2,
            List.of(
                field("code","角色编码",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("name","角色名称",ConfigField.FieldType.STRING,true,false,1,null,null),
                field("description","描述",ConfigField.FieldType.STRING,false,false,2,null,null)
            ));
        definitionService.save(approveRole);
        List<Map<String,Object>> roleRows = List.of(
            Map.of("code","DEPT_MGR","name","部门经理","description","部门级别审批"),
            Map.of("code","FINANCE","name","财务审批","description","财务合规审批"),
            Map.of("code","VP","name","副总裁","description","高价值合同审批"),
            Map.of("code","CEO","name","总裁","description","战略项目审批")
        );
        dataService.batchSave("APPROVE_ROLE","GLOBAL",null,roleRows,List.of("code"));

        // 4. TAX_RATE (REGION)
        String taxTypeOpts = objectMapper.writeValueAsString(List.of(
            Map.of("value","VAT","label","增值税"),
            Map.of("value","CONSUMPTION","label","消费税"),
            Map.of("value","SERVICE","label","服务税")
        ));
        ConfigDefinition taxRate = def("TAX_RATE","税率配置",ConfigDefinition.ConfigLevel.REGION,"各地区适用税率",3,
            List.of(
                field("taxCode","税种编码",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("taxType","税种类型",ConfigField.FieldType.ENUM,true,false,1,taxTypeOpts,null),
                field("rate","税率(%)",ConfigField.FieldType.NUMBER,true,false,2,null,null),
                field("effectiveDate","生效日期",ConfigField.FieldType.DATE,true,false,3,null,null),
                field("regionCode","地区编码",ConfigField.FieldType.STRING,true,true,4,null,null)
            ));
        definitionService.save(taxRate);
        // Seed region-level data for each region
        for (String regionCode : List.of("HE","HS","HB","XN")) {
            List<Map<String,Object>> taxRows = List.of(
                Map.of("taxCode","VAT-STD","taxType","VAT","rate",13.0,"effectiveDate","2024-01-01","regionCode",regionCode),
                Map.of("taxCode","VAT-LOW","taxType","VAT","rate",9.0,"effectiveDate","2024-01-01","regionCode",regionCode),
                Map.of("taxCode","VAT-ZERO","taxType","VAT","rate",0.0,"effectiveDate","2024-01-01","regionCode",regionCode),
                Map.of("taxCode","SRV-TECH","taxType","SERVICE","rate",6.0,"effectiveDate","2024-01-01","regionCode",regionCode)
            );
            dataService.batchSave("TAX_RATE","REGION",regionCode,taxRows,List.of("taxCode","regionCode"));
        }

        // 5. PROJ_PARAM (PROJECT)
        ConfigDefinition projParam = def("PROJ_PARAM","项目参数",ConfigDefinition.ConfigLevel.PROJECT,"项目运营参数配置",5,
            List.of(
                field("paramKey","参数键",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("paramValue","参数值",ConfigField.FieldType.STRING,true,false,1,null,null),
                field("description","说明",ConfigField.FieldType.STRING,false,false,2,null,null),
                field("projectCode","项目编码",ConfigField.FieldType.STRING,true,true,3,null,null)
            ));
        definitionService.save(projParam);

        // 6. PROJ_APPROVE (PROJECT) — references APPROVE_ROLE
        ConfigDefinition projApprove = def("PROJ_APPROVE","项目审批流",ConfigDefinition.ConfigLevel.PROJECT,"项目审批流程配置",6,
            List.of(
                field("stepCode","环节编码",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("stepName","环节名称",ConfigField.FieldType.STRING,true,false,1,null,null),
                fieldRef("approveRole","审批角色",2,"APPROVE_ROLE","code"),
                field("stepOrder","顺序",ConfigField.FieldType.NUMBER,true,false,3,null,null),
                field("docTypeCode","适用单据",ConfigField.FieldType.STRING,false,false,4,null,null),
                field("projectCode","项目编码",ConfigField.FieldType.STRING,true,true,5,null,null)
            ));
        definitionService.save(projApprove);

        // 7. PROJ_PRICE (PROJECT) — large dataset for demo, references CURRENCY and TAX_RATE
        ConfigDefinition projPrice = def("PROJ_PRICE","项目价格表",ConfigDefinition.ConfigLevel.PROJECT,"项目商品价格配置",8,
            List.of(
                field("skuCode","商品编码",ConfigField.FieldType.STRING,true,true,0,null,null),
                field("skuName","商品名称",ConfigField.FieldType.STRING,true,false,1,null,null),
                field("unitPrice","单价",ConfigField.FieldType.NUMBER,true,false,2,null,null),
                fieldRef("currencyCode","货币",3,"CURRENCY","code"),
                field("taxCode","税率码",ConfigField.FieldType.STRING,true,false,4,null,null),
                field("unit","计量单位",ConfigField.FieldType.STRING,true,false,5,null,null),
                field("projectCode","项目编码",ConfigField.FieldType.STRING,true,true,6,null,null)
            ));
        definitionService.save(projPrice);

        // Seed large price data for demo
        List<String> projectCodes = List.of("HE-P001","HE-P002","HS-P001","HS-P002");
        List<String> currencies = List.of("CNY","USD","EUR");
        List<String> taxCodes = List.of("VAT-STD","VAT-LOW","VAT-ZERO");
        List<String> units = List.of("个","箱","套","台","件","米","公斤");
        Random random = new Random(42);
        for (String projCode : projectCodes) {
            List<Map<String,Object>> priceRows = new ArrayList<>();
            for (int i = 1; i <= 300; i++) {
                String skuCode = String.format("SKU-%s-%04d", projCode.substring(projCode.length()-4), i);
                priceRows.add(Map.of(
                    "skuCode", skuCode,
                    "skuName", "商品-" + i,
                    "unitPrice", Math.round((10 + random.nextDouble() * 990) * 100.0) / 100.0,
                    "currencyCode", currencies.get(random.nextInt(currencies.size())),
                    "taxCode", taxCodes.get(random.nextInt(taxCodes.size())),
                    "unit", units.get(random.nextInt(units.size())),
                    "projectCode", projCode
                ));
            }
            dataService.batchSave("PROJ_PRICE","PROJECT",projCode,priceRows,List.of("skuCode","projectCode"));
        }

        log.info("Definitions and seed data created successfully");
    }

    // Helper builders
    private Region region(String code, String name) {
        Region r = new Region(); r.setCode(code); r.setName(name); return r;
    }
    private Project project(String code, String name, String regionCode) {
        Project p = new Project(); p.setCode(code); p.setName(name); p.setRegionCode(regionCode); return p;
    }
    private ConfigDefinition def(String code, String name, ConfigDefinition.ConfigLevel level,
                                   String desc, int sort, List<ConfigField> fields) {
        ConfigDefinition d = new ConfigDefinition();
        d.setCode(code); d.setName(name); d.setLevel(level);
        d.setDescription(desc); d.setSortOrder(sort);
        fields.forEach(f -> f.setDefCode(code));
        d.setFields(new ArrayList<>(fields));
        return d;
    }
    private ConfigField field(String code, String label, ConfigField.FieldType type,
                               boolean required, boolean isKey, int sort,
                               String options, String refDef) {
        ConfigField f = new ConfigField();
        f.setCode(code); f.setLabel(label); f.setFieldType(type);
        f.setRequired(required); f.setKey(isKey); f.setSortOrder(sort);
        f.setOptionsJson(options); f.setRefDefCode(refDef);
        return f;
    }
    private ConfigField fieldRef(String code, String label, int sort, String refDef, String refField) {
        ConfigField f = new ConfigField();
        f.setCode(code); f.setLabel(label); f.setFieldType(ConfigField.FieldType.REFERENCE);
        f.setRequired(true); f.setKey(false); f.setSortOrder(sort);
        f.setRefDefCode(refDef); f.setRefFieldCode(refField);
        return f;
    }
}
