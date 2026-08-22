package com.example.ai.service;

import com.example.ai.entity.ConfigDataRow;
import com.example.ai.entity.ConfigDefinition;
import com.example.ai.repository.ConfigDataRowRepository;
import com.example.ai.repository.ConfigDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataSeedService {

    private final ConfigDefinitionRepository defRepository;
    private final ConfigDataRowRepository rowRepository;

    @Transactional
    public void initDefinitions() {
        // CONFIG_A: 渠道销售配置
        defRepository.save(ConfigDefinition.builder()
                .code("CONFIG_A")
                .name("渠道销售配置")
                .description("管理各渠道的销售字段,包括销量、价格、销售员等")
                .columns(List.of(
                        col("a", "销售员姓名", "string", true, Collections.emptyList(), ""),
                        col("b", "销量", "number", true, Collections.emptyList(), 0),
                        col("c", "价格等级", "select", true, List.of("高", "中", "低"), "中"),
                        col("d", "销售日期", "date", false, Collections.emptyList(), LocalDate.now().toString()),
                        col("e", "备注", "string", false, Collections.emptyList(), "")
                ))
                .enabled(true)
                .build());

        // CONFIG_B: 员工配置
        defRepository.save(ConfigDefinition.builder()
                .code("CONFIG_B")
                .name("员工配置")
                .description("员工基础信息配置")
                .columns(List.of(
                        col("x", "工号", "string", true, Collections.emptyList(), ""),
                        col("y", "姓名", "string", true, Collections.emptyList(), ""),
                        col("z", "部门", "select", true, List.of("研发部", "市场部", "销售部", "人力资源部", "财务部"), "研发部"),
                        col("w", "职级", "select", false, List.of("P5","P6","P7","P8","P9"), "P6"),
                        col("v", "入职日期", "date", false, Collections.emptyList(), LocalDate.now().toString())
                ))
                .enabled(true)
                .build());

        // CONFIG_C: 产品参数配置
        defRepository.save(ConfigDefinition.builder()
                .code("CONFIG_C")
                .name("产品参数配置")
                .description("各类产品的技术参数")
                .columns(List.of(
                        col("sku", "产品编码", "string", true, Collections.emptyList(), ""),
                        col("name", "产品名称", "string", true, Collections.emptyList(), ""),
                        col("category", "产品类别", "select", true, List.of("电子产品","家居用品","办公用品","食品饮料"), "电子产品"),
                        col("weight", "重量(kg)", "number", false, Collections.emptyList(), 0),
                        col("price", "指导价", "number", true, Collections.emptyList(), 0),
                        col("enabled", "是否启用", "boolean", false, Collections.emptyList(), true)
                ))
                .enabled(true)
                .build());

        // CONFIG_D: 订单规则配置
        defRepository.save(ConfigDefinition.builder()
                .code("CONFIG_D")
                .name("订单规则配置")
                .description("订单匹配与处理规则配置")
                .columns(List.of(
                        col("ruleCode", "规则编码", "string", true, Collections.emptyList(), ""),
                        col("ruleName", "规则名称", "string", true, Collections.emptyList(), ""),
                        col("minAmount", "最小金额", "number", true, Collections.emptyList(), 0),
                        col("discount", "折扣率", "number", false, Collections.emptyList(), 1),
                        col("priority", "优先级", "number", false, Collections.emptyList(), 100),
                        col("status", "状态", "select", true, List.of("启用","停用"), "启用")
                ))
                .enabled(true)
                .build());
    }

    @Transactional
    public void initDataRows() {
        Random rnd = new Random(12345L);
        Optional<ConfigDefinition> a = defRepository.findByCode("CONFIG_A");
        if (a.isPresent()) {
            String[] names = {"张三","李四","王五","赵六","钱七","孙八","周九","吴十","郑十一","冯十二"};
            String[] levels = {"高","中","低"};
            List<ConfigDataRow> rows = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("a", names[rnd.nextInt(names.length)]);
                data.put("b", rnd.nextInt(1000) + 1);
                data.put("c", levels[rnd.nextInt(levels.length)]);
                data.put("d", LocalDate.now().minusDays(rnd.nextInt(365)).toString());
                data.put("e", i % 3 == 0 ? "大客户订单" : "");
                rows.add(ConfigDataRow.builder().configDefId(a.get().getId()).rowData(data).build());
            }
            rowRepository.saveAll(rows);
        }

        Optional<ConfigDefinition> b = defRepository.findByCode("CONFIG_B");
        if (b.isPresent()) {
            String[] names = {"陈A","林B","黄C","刘D","杨E","朱F","徐G","马H","胡I","郭J"};
            String[] depts = {"研发部", "市场部", "销售部", "人力资源部", "财务部"};
            String[] ranks = {"P5","P6","P7","P8","P9"};
            List<ConfigDataRow> rows = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("x", "E" + String.format("%05d", 10000 + i));
                data.put("y", names[rnd.nextInt(names.length)] + (i+1));
                data.put("z", depts[rnd.nextInt(depts.length)]);
                data.put("w", ranks[rnd.nextInt(ranks.length)]);
                data.put("v", LocalDate.now().minusDays(365L * rnd.nextInt(8)).toString());
                rows.add(ConfigDataRow.builder().configDefId(b.get().getId()).rowData(data).build());
            }
            rowRepository.saveAll(rows);
        }

        Optional<ConfigDefinition> c = defRepository.findByCode("CONFIG_C");
        if (c.isPresent()) {
            String[] cats = {"电子产品","家居用品","办公用品","食品饮料"};
            String[] prefix = {"SKU-PC","SKU-HOME","SKU-OF","SKU-FD"};
            String[] prodNames = {"笔记本电脑","手机","耳机","台灯","椅子","办公桌","矿泉水","饼干","方便面","显示器","键盘","鼠标"};
            List<ConfigDataRow> rows = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int ci = rnd.nextInt(cats.length);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sku", prefix[ci] + "-" + String.format("%04d", i));
                data.put("name", prodNames[rnd.nextInt(prodNames.length)] + " " + (i+1) + "号");
                data.put("category", cats[ci]);
                data.put("weight", Math.round(rnd.nextDouble() * 50 * 100.0) / 100.0);
                data.put("price", Math.round((rnd.nextDouble() * 5000 + 10) * 100.0) / 100.0);
                data.put("enabled", rnd.nextInt(5) != 0);
                rows.add(ConfigDataRow.builder().configDefId(c.get().getId()).rowData(data).build());
            }
            rowRepository.saveAll(rows);
        }

        Optional<ConfigDefinition> d = defRepository.findByCode("CONFIG_D");
        if (d.isPresent()) {
            List<ConfigDataRow> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ruleCode", "R" + String.format("%03d", i));
                data.put("ruleName", "订单规则-" + i);
                data.put("minAmount", new int[]{0, 100, 500, 1000, 3000, 5000, 10000}[rnd.nextInt(7)]);
                double disc = Math.round((0.5 + rnd.nextDouble() * 0.5) * 100.0) / 100.0;
                data.put("discount", disc);
                data.put("priority", rnd.nextInt(200));
                data.put("status", rnd.nextInt(6) == 0 ? "停用" : "启用");
                rows.add(ConfigDataRow.builder().configDefId(d.get().getId()).rowData(data).build());
            }
            rowRepository.saveAll(rows);
        }
    }

    private Map<String, Object> col(String key, String label, String type, boolean required,
                                    List<Object> options, Object defaultValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("type", type);
        m.put("required", required);
        m.put("options", options);
        m.put("defaultValue", defaultValue);
        return m;
    }
}
