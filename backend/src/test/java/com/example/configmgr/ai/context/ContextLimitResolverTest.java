package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分母取数（C-08，设计稿 §5.2）：当前契约只保留"模型行声明了 {@code ai_model.max_context}"
 * 与"未知"两态，<b>没有配置键回退</b>。
 *
 * <p>这组用例锁三件事：
 * <ol>
 * <li>有声明值 ⇒ 用它，来源 {@code schema}；</li>
 * <li>没有声明值 / 脏值 / 读失败 ⇒ 未知（0），不做比例判定，也不回落到任何配置键；</li>
 * <li>没有 provider/模型身份 ⇒ 不去查源（不按模型名跨 provider 猜）。</li>
 * </ol>
 */
class ContextLimitResolverTest {

	@Test
	void declaredDatabaseValueWinsAsSchemaSource() {
		ContextLimitResolver resolver = new ContextLimitResolver((providerId, modelId) -> 64000);

		ContextLimitResolver.ResolveResult result = resolver.resolveDetailed(1L, "deepseek-flash");

		assertThat(result.limitTokens()).isEqualTo(64000);
		assertThat(result.source()).isEqualTo(ContextLimitResolver.Source.SCHEMA);
		assertThat(result.source().wire()).isEqualTo("schema");
		assertThat(resolver.resolve(1L, "deepseek-flash")).isEqualTo(64000);
	}

	@Test
	void missingDatabaseValueIsUnknownWithoutFallback() {
		ContextLimitResolver resolver = new ContextLimitResolver((providerId, modelId) -> null);

		ContextLimitResolver.ResolveResult result = resolver.resolveDetailed(1L, "deepseek-flash");

		assertThat(result.limitTokens()).as("没有回退键：缺声明就是未知").isZero();
		assertThat(result.source()).isEqualTo(ContextLimitResolver.Source.UNKNOWN);
		assertThat(result.isKnown()).isFalse();
	}

	@Test
	void nonPositiveDatabaseValueIsUnknown() {
		ContextLimitResolver zero = new ContextLimitResolver((providerId, modelId) -> 0);
		ContextLimitResolver negative = new ContextLimitResolver((providerId, modelId) -> -5);

		assertThat(zero.resolve(1L, "m")).isZero();
		assertThat(negative.resolve(1L, "m")).isZero();
	}

	@Test
	void missingIdentitySkipsTheSourceEntirely() {
		AtomicBoolean called = new AtomicBoolean(false);
		ContextLimitResolver resolver = new ContextLimitResolver((providerId, modelId) -> {
			called.set(true);
			return 64000;
		});

		assertThat(resolver.resolve(null, "m")).isZero();
		assertThat(resolver.resolve(0L, "m")).isZero();
		assertThat(resolver.resolve(1L, null)).isZero();
		assertThat(resolver.resolve(1L, "  ")).isZero();
		assertThat(called).as("无身份时不得按模型名跨 provider 猜").isFalse();
	}

	@Test
	void databaseFailureDegradesToUnknownNotFatal() {
		ContextLimitResolver resolver = new ContextLimitResolver((providerId, modelId) -> {
			throw new IllegalStateException("配置快照不可读");
		});

		ContextLimitResolver.ResolveResult result = resolver.resolveDetailed(1L, "m");

		assertThat(result.limitTokens()).isZero();
		assertThat(result.source()).isEqualTo(ContextLimitResolver.Source.UNKNOWN);
	}
}
