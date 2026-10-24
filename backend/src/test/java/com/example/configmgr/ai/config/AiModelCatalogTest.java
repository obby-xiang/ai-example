package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/ai/providers/{id}/models/fetch} 的落点（模型配置专项 §3.5 + 复审 M-1）：
 * 不落库、Redis 10min 缓存、key 不可解时短路。
 *
 * <p>
 * <b>证红</b>：
 * <ul>
 * <li>去掉 UNREADABLE 短路 ⇒ {@link #unreadableKeyShortCircuitsWithoutCallingUpstream()} 红
 * （会拿 null key 打上游，回 MODELS_UNSUPPORTED/AUTH_FAILED，而不是 KEY_UNREADABLE）；</li>
 * <li>去掉 {@code refresh=false} 的缓存读 ⇒ {@link #cacheHitAvoidsTheUpstreamCall()} 红；</li>
 * <li>失败也写缓存 ⇒ {@link #probeFailureIsPropagatedAndNotCached()} 红。</li>
 * </ul>
 */
class AiModelCatalogTest {

	private static final Long PROVIDER_ID = 7L;

	private final Map<String, String> redisStore = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiConnectionProbe probe = mock(AiConnectionProbe.class);

	private final AiProperties properties = new AiProperties();

	private final AiModelCatalog catalog = new AiModelCatalog(this.probe, this.configService, this.redis,
			this.properties, new ObjectMapper());

	@BeforeEach
	@SuppressWarnings("unchecked")
	void wireRedis() {
		when(this.redis.opsForValue()).thenReturn(this.ops);
		when(this.ops.get(anyString())).thenAnswer(invocation -> this.redisStore.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			this.redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.ops).set(anyString(), anyString(), any(Duration.class));
		AiProvider provider = new AiProvider();
		provider.setId(PROVIDER_ID);
		provider.setCode("custom-1");
		provider.setBaseUrl("https://api.deepseek.com");
		provider.setModelsPath("/models");
		provider.setKeyRef(AiProvider.KEY_REF_DB);
		provider.setSource(AiProvider.SOURCE_DB);
		provider.setEnabled(true);
		when(this.configService.provider(PROVIDER_ID)).thenReturn(Optional.of(provider));
		when(this.configService.resolveKey(any()))
			.thenReturn(new AiConfigService.KeyResolution(AiConfigService.KeyStatus.OK, "probe-key", "fp-0011223344"));
	}

	@Test
	void successProbesUpstreamAndCachesTheList() {
		when(this.probe.fetchModels(eq("https://api.deepseek.com"), eq("/models"), eq("probe-key"), eq(false)))
			.thenReturn(AiConnectionProbe.ProbeResult.ok(200, 9L, List.of("m-1", "m-2")));

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, true);

		assertThat(result.ok()).isTrue();
		assertThat(result.cached()).isFalse();
		assertThat(result.models()).containsExactly("m-1", "m-2");
		assertThat(this.redisStore).containsKey(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID);
		assertThat(this.redisStore.get(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID)).contains("m-1", "m-2");
	}

	@Test
	void cacheHitAvoidsTheUpstreamCall() {
		this.redisStore.put(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID, "[\"cached-1\",\"cached-2\"]");

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, false);

		assertThat(result.ok()).isTrue();
		assertThat(result.cached()).as("来自缓存").isTrue();
		assertThat(result.models()).containsExactly("cached-1", "cached-2");
		verify(this.probe, never()).fetchModels(any(), any(), any(), anyBoolean());
	}

	@Test
	void refreshTrueIgnoresTheCache() {
		this.redisStore.put(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID, "[\"cached-1\"]");
		when(this.probe.fetchModels(any(), any(), any(), anyBoolean()))
			.thenReturn(AiConnectionProbe.ProbeResult.ok(200, 3L, List.of("fresh-1")));

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, true);

		assertThat(result.cached()).isFalse();
		assertThat(result.models()).containsExactly("fresh-1");
	}

	@Test
	void unreadableKeyShortCircuitsWithoutCallingUpstream() {
		when(this.configService.resolveKey(any())).thenReturn(
				new AiConfigService.KeyResolution(AiConfigService.KeyStatus.UNREADABLE, null, null));

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, true);

		assertThat(result.ok()).isFalse();
		assertThat(result.code()).as("M-1：直接报 KEY_UNREADABLE（不是 AUTH_FAILED/UNREACHABLE）")
			.isEqualTo(AiConfigService.CODE_KEY_UNREADABLE);
		// ② 直接 API 调用也不得看到变量名/机制词，只给设置指引
		assertThat(result.message()).contains("设置 → AI")
			.doesNotContain("AI_SECRET_KEK", "KEK", "provider");
		verify(this.probe, never()).fetchModels(any(), any(), any(), anyBoolean());
		assertThat(this.redisStore).doesNotContainKey(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID);
	}

	@Test
	void cacheNeverMasksAnUnreadableKey() {
		this.redisStore.put(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID, "[\"cached-1\"]");
		when(this.configService.resolveKey(any())).thenReturn(
				new AiConfigService.KeyResolution(AiConfigService.KeyStatus.UNREADABLE, null, null));

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, false);

		assertThat(result.cached()).as("缓存命中也不掩盖\"key 解不开\"").isFalse();
		assertThat(result.code()).isEqualTo(AiConfigService.CODE_KEY_UNREADABLE);
	}

	@Test
	void probeFailureIsPropagatedAndNotCached() {
		when(this.probe.fetchModels(any(), any(), any(), anyBoolean()))
			.thenReturn(AiConnectionProbe.ProbeResult.failed(AiConnectionProbe.CODE_AUTH_FAILED, 401, 5L,
					AiConnectionProbe.MESSAGE_AUTH_FAILED));

		AiModelCatalog.CatalogResult result = this.catalog.fetch(PROVIDER_ID, true);

		assertThat(result.ok()).isFalse();
		assertThat(result.code()).isEqualTo(AiConnectionProbe.CODE_AUTH_FAILED);
		assertThat(result.models()).isEmpty();
		assertThat(this.redisStore).doesNotContainKey(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID);
	}

	@Test
	void missingProviderIsAStable404Code() {
		when(this.configService.provider(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.catalog.fetch(999L, true))
			.isInstanceOf(AiConfigException.class)
			.extracting(ex -> ((AiConfigException) ex).getCode())
			.isEqualTo(AiConfigWriter.CODE_PROVIDER_NOT_FOUND);
	}

	@Test
	void peekCacheExposesTheStoredListForDiagnostics() {
		this.redisStore.put(AiModelCatalog.CACHE_KEY_PREFIX + PROVIDER_ID, "[\"m-1\"]");
		assertThat(this.catalog.peekCache(PROVIDER_ID)).contains(List.of("m-1"));
	}

}
