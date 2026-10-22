package com.example.configmgr.ai.form;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选项数据源注册表（{@code optionsFrom.sourceId} 的唯一解析入口）。
 *
 * <p>注册面是<b>编译期闭集</b>：只有实现 {@link FormOptionSource} 的 Bean 会被登记，
 * 且 id 必须出现在 {@link GenerativeFormRules#OPTION_SOURCE_IDS}（schema 闸门用它判定
 * "是不是注册过的数据源"）。两边漂移时启动日志告警、调用侧按未注册处理（拒绝解析）——
 * 宁可选项空，也不执行未注册来源。
 */
@Slf4j
@Component
public class FormOptionSourceRegistry {

	private final Map<String, FormOptionSource> sources = new LinkedHashMap<>();

	public FormOptionSourceRegistry(List<FormOptionSource> registered) {
		if (registered != null) {
			for (FormOptionSource source : registered) {
				this.sources.put(source.id(), source);
				if (!GenerativeFormRules.OPTION_SOURCE_IDS.contains(source.id())) {
					log.warn("选项数据源 {} 未登记在 GenerativeFormRules.OPTION_SOURCE_IDS，"
							+ "schema 闸门不会放行引用它的表单", source.id());
				}
			}
		}
	}

	/** 已注册的数据源 id（顺序稳定，供排障/用例断言）。 */
	public List<String> ids() {
		return List.copyOf(this.sources.keySet());
	}

	/** 按 id 解析选项；未注册返回 null（调用方按"未知数据源"处理，绝不回落到任何外部来源）。 */
	public List<Map<String, String>> options(String sourceId, String parentValue) {
		FormOptionSource source = this.sources.get(sourceId);
		return source == null ? null : source.options(parentValue);
	}

	public boolean supports(String sourceId) {
		return this.sources.containsKey(sourceId);
	}

}
