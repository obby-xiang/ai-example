package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分母取数（L 组：C-08，设计稿 §5.2）。
 *
 * <p>优先级 <b>DB &gt; 配置 &gt; 0（未知）</b>。这组用例的存在理由是：上限会被计量、触发、展示、
 * 端点、健康检查五处消费，优先级一旦在某一处写反，"某个面还在用过渡键"这种漂移就无人发现。
 */
class ContextLimitResolverTest {

	private static AiProperties props(int limitTokens) {
		AiProperties properties = new AiProperties();
		properties.getContext().setLimitTokens(limitTokens);
		return properties;
	}

	/** C-08 ①：DB 值优先（模型配置的 {@code ai_model.max_context}）。 */
	@Test
	void databaseValueWins() {
		ContextLimitResolver resolver = new ContextLimitResolver(props(128000), (providerId, modelId) -> 64000);

		assertThat(resolver.resolve(1L, "deepseek-flash")).isEqualTo(64000);
	}

	/** C-08 ②：DB 无值 ⇒ 过渡键。 */
	@Test
	void configuredValueIsTheFallback() {
		ContextLimitResolver resolver = new ContextLimitResolver(props(128000), (providerId, modelId) -> null);

		assertThat(resolver.resolve(1L, "deepseek-flash")).isEqualTo(128000);
	}

	/** C-08 ③：脏值（0/负）视为"没有"，不当作真值 —— 否则任何一轮都立刻判"已满"。 */
	@Test
	void nonPositiveDatabaseValueFallsThrough() {
		ContextLimitResolver zero = new ContextLimitResolver(props(128000), (providerId, modelId) -> 0);
		ContextLimitResolver negative = new ContextLimitResolver(props(128000), (providerId, modelId) -> -5);

		assertThat(zero.resolve(1L, "m")).isEqualTo(128000);
		assertThat(negative.resolve(1L, "m")).isEqualTo(128000);
	}

	/** C-08 ④：两级都没有 ⇒ 0 = 未知（端点 limitTokens:null、界面不显示百分比）。 */
	@Test
	void unknownWhenNeitherSourceHasAValue() {
		ContextLimitResolver resolver = new ContextLimitResolver(props(0), (providerId, modelId) -> null);

		assertThat(resolver.resolve(1L, "m")).isZero();
	}

	/** DB 源抛错（配置快照读失败）不得让解析失败：回落过渡键。 */
	@Test
	void databaseFailureDegradesToConfigured() {
		ContextLimitResolver resolver = new ContextLimitResolver(props(128000), (providerId, modelId) -> {
			throw new IllegalStateException("配置快照不可读");
		});

		assertThat(resolver.resolve(1L, "m")).isEqualTo(128000);
	}

	/** 没有模型身份时无从查 DB（端点重建/未指定模型），过渡键仍可用。 */
	@Test
	void blankModelSkipsDatabase() {
		ContextLimitResolver resolver = new ContextLimitResolver(props(128000), (providerId, modelId) -> 64000);

		assertThat(resolver.resolve(1L, null)).isEqualTo(128000);
		assertThat(resolver.resolve(1L, "  ")).isEqualTo(128000);
	}

}
