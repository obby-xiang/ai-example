package com.example.configmgr.ai.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 上下文上限（分母）的<b>单一取数点</b>（上下文统计与压缩专项批①；设计稿 §3.2）。
 *
 * <p>
 * 唯一来源 = 模型行的 {@code ai_model.max_context}，且必须按显式的
 * {@code (providerId, modelId)} 精确命中：
 * <ol>
 * <li>命中且 {@code > 0} ⇒ 用它（来源 {@link Source#SCHEMA}）；</li>
 * <li>没有 provider 身份 / 模型名空白 / 行未声明 {@code max_context} / 读库失败 ⇒
 * <b>0 = 未知</b>（来源 {@link Source#UNKNOWN}）：端点 {@code limitTokens:null}、界面只显示已用
 * 不显示百分比、压缩不做比例判定（降级态）。</li>
 * </ol>
 *
 * <p>
 * 同名模型可以挂在多个 provider 下、窗口各不相同，因此<b>没有</b>"按模型名全局匹配"的第二条路；
 * 也没有配置键回退：上限漂移会让"显示的百分比"与"触发百分比"各用一套分母。
 *
 * <p>
 * DB 值的<b>脏值容错</b>与 {@code AiProviderRegistry#buildOptions} 同一姿态：非正数视为"没有"、
 * 归到未知（{@code max_context=0} 若被当真值，任何轮次都会立刻判"已满"）。
 */
@Slf4j
@RequiredArgsConstructor
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
		 * @param providerId 库内 provider id（必填；{@code null}/非正数 = 无身份，返回 {@code null}）
		 * @param modelId    模型名（可为 null）
		 * @return 该模型声明的上下文上限；拿不到返回 {@code null}
		 */
		Integer maxContext(Long providerId, String modelId);

	}

	private final ModelContextSource source;

	/**
	 * 上限的来源（只读诊断面：{@code /context}、{@code /health}）。
	 *
	 * <p>只保留"模型行声明了"与"未知"两态：没有配置键回退，因此不存在 {@code fallback} 来源。
	 */
	public enum Source {

		/** 模型行明确声明了 {@code max_context}。 */
		SCHEMA("schema"),

		/** 无 provider 身份 / 模型行缺失或未声明 {@code max_context} ⇒ 未知（不做比例判定）。 */
		UNKNOWN("unknown");

		private final String wire;

		Source(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return this.wire;
		}

	}

	/**
	 * 解析结果（限值 + 来源）。
	 *
	 * @param limitTokens {@code > 0} 的 token 上限；{@code 0} = 未知
	 * @param source     取数来源（{@link Source#wire()} 是线上值）
	 */
	public record ResolveResult(@JsonProperty("limitTokens") int limitTokens, @JsonProperty("source") Source source) {

		public boolean known() {
			return isKnown();
		}

		@JsonIgnore
		public boolean isKnown() {
			return this.limitTokens > 0;
		}

	}

	/**
	 * 解析上限（只要数值；保留给既有调用点）。
	 *
	 * @return {@code > 0} 的 token 上限；{@code 0} = 未知（降级态）
	 */
	public int resolve(Long providerId, String modelId) {
		return resolveDetailed(providerId, modelId).limitTokens();
	}

	/**
	 * 解析上限与来源（A6）。
	 *
	 * <p>没有 provider 身份就没有可判定的归属：返回未知，不按模型名跨 provider 猜。
	 */
	public ResolveResult resolveDetailed(Long providerId, String modelId) {
		if (providerId == null || providerId <= 0L || StringUtils.isBlank(modelId)
				|| this.source == null) {
			return new ResolveResult(0, Source.UNKNOWN);
		}
		Integer maxContext;
		try {
			maxContext = this.source.maxContext(providerId, modelId);
		}
		catch (RuntimeException ex) {
			// 配置快照读失败不该让一轮对话失败：上限是观测/压缩的输入，降级为未知即可
			log.warn("reading ai_model.max_context failed providerId={} model={} (limit unknown)",
					providerId, modelId, ex);
			return new ResolveResult(0, Source.UNKNOWN);
		}
		if (maxContext == null || maxContext <= 0) {
			return new ResolveResult(0, Source.UNKNOWN);
		}
		return new ResolveResult(maxContext, Source.SCHEMA);
	}

}
