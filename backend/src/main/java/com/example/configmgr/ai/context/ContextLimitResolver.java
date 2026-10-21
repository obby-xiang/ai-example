package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 上下文上限（分母）的<b>单一取数点</b>（上下文统计与压缩专项批①；设计稿 §3.2）。
 *
 * <p>
 * 优先级（C-08 锁死的三条）：
 * <ol>
 * <li>模型配置的 DB 值 {@code ai_model.max_context}（模型配置专项批① 已建表建列）；</li>
 * <li>过渡键 {@code app.ai.context.limit-tokens}（波次 1 分母 = 128000）；</li>
 * <li>都没有 ⇒ <b>0 = 未知</b>：端点 {@code limitTokens:null}、界面只显示已用不显示百分比、
 * 压缩不做比例判定（降级态）。</li>
 * </ol>
 *
 * <p>
 * <b>为什么收敛成一处</b>：模型配置专项落地后 DB 列才是真源，而"上限"会被计量、触发、展示、
 * 端点、健康检查五处消费。分散读会让"某个面还在用过渡键"这种漂移无人发现 —— 这一条是两专项的
 * 接口契约，切换时只改 {@link #resolve}。
 *
 * <p>
 * DB 值的<b>脏值容错</b>与 {@code AiProviderRegistry#buildOptions} 同一姿态：非正数视为"没有"、
 * 回落下一级（{@code max_context=0} 若被当真值，任何轮次都会立刻判"已满"）。
 */
@Slf4j
public final class ContextLimitResolver {

	/**
	 * DB 值来源（{@code ai_model.max_context}）。
	 *
	 * <p>做成函数式接口是为了让解析优先级可单测（不必起 Spring + DB），生产实现由
	 * {@link ContextConfig} 用 {@code AiConfigService} 的快照拼出。
	 */
	@FunctionalInterface
	public interface ModelContextSource {

		/**
		 * @param providerId 库内 provider id（{@code null} = 未指定，按 modelId 全局匹配）
		 * @param modelId    模型名（可为 null）
		 * @return 该模型声明的上下文上限；拿不到返回 {@code null}
		 */
		Integer maxContext(Long providerId, String modelId);

	}

	private final AiProperties properties;

	private final ModelContextSource source;

	public ContextLimitResolver(AiProperties properties, ModelContextSource source) {
		this.properties = properties;
		this.source = source;
	}

	/** 无 DB 源（局部上下文/单测）：只用过渡键。 */
	public ContextLimitResolver(AiProperties properties) {
		this(properties, null);
	}

	/**
	 * 解析上限。
	 *
	 * @return {@code > 0} 的 token 上限；{@code 0} = 未知（降级态）
	 */
	public int resolve(Long providerId, String modelId) {
		if (modelId == null || modelId.isBlank()) {
			// 没有模型身份就无从查 DB —— 过渡键仍可用（"未指定模型"是合法调用：端点重建时快照里没模型）
			return configured();
		}
		return fromDatabase(providerId, modelId);
	}

	private int fromDatabase(Long providerId, String modelId) {
		if (this.source == null) {
			return configured();
		}
		Integer maxContext;
		try {
			maxContext = this.source.maxContext(providerId, modelId);
		}
		catch (RuntimeException ex) {
			// 配置快照读失败不该让一轮对话失败：上限是观测/压缩的输入，降级到过渡键即可
			log.warn("读取 ai_model.max_context 失败 model={}：{}（回落 app.ai.context.limit-tokens）", modelId,
					ex.getMessage());
			return configured();
		}
		if (maxContext == null || maxContext <= 0) {
			return configured();
		}
		return maxContext;
	}

	private int configured() {
		int tokens = this.properties == null || this.properties.getContext() == null ? 0
				: this.properties.getContext().getLimitTokens();
		return Math.max(0, tokens);
	}

}
