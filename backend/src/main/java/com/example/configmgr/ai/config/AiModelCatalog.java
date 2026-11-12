package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.observe.LogSampling;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 远端模型清单（{@code POST /api/ai/providers/{id}/models/fetch}；设计稿 §3.5）。
 *
 * <h2>不落库</h2>
 * 拉回来的 id 只进 Redis 缓存（键 {@code ai:cfg:models:<providerId>}，TTL 缺省 10min），
 * <b>不</b>自动写 {@code ai_model}：allowlist 是人的决定（"哪些模型允许被选"），拉取只是给他一个勾选清单。
 *
 * <h2>缓存语义</h2>
 * <ul>
 * <li>{@code refresh=true}（默认，用户点「拉取模型」）⇒ 真打上游，成功才更新缓存；</li>
 * <li>{@code refresh=false}（页面加载时预填）⇒ 缓存命中就直接用（{@code cached=true}），
 * 否则真打；</li>
 * <li>Redis 不可用 ⇒ 每次都真打（{@code cached=false}），不假装有缓存。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelCatalog {

    public static final String CACHE_KEY_PREFIX = "ai:cfg:models:";

    private final AiConnectionProbe probe;

    private final AiConfigService configService;

    private final StringRedisTemplate redis;

    private final AiProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 拉一次（或读缓存）。
     *
     * @param refresh true = 忽略缓存
     * @throws AiConfigException {@code PROVIDER_NOT_FOUND}（id 不存在）
     */
    public CatalogResult fetch(Long providerId, boolean refresh) {
        AiProvider provider = this.configService.provider(providerId)
                .orElseThrow(() -> new AiConfigException(AiConfigWriter.CODE_PROVIDER_NOT_FOUND,
                        "Provider not found: " + providerId));
        AiConfigService.KeyResolution key = this.configService.resolveKey(provider);
        // M-1（复审）：key 解不开就没有"拉取"可言 —— 直接回 KEY_UNREADABLE（与 /test 同口径），
        // 而不是拿 null key 打上游换一个 AUTH_FAILED（用户会去改 key，但真正要修的是 KEK）。
        // 位置在缓存读<b>之前</b>：缓存命中也不该掩盖"这行 key 已经解不开"这个更重要的事实。
        if (key.status() == AiConfigService.KeyStatus.UNREADABLE) {
            return new CatalogResult(false, List.of(), false, AiConfigService.CODE_KEY_UNREADABLE,
                    "库里的密钥无法解密：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置");
        }
        if (!refresh) {
            Optional<List<String>> cached = readCache(providerId);
            if (cached.isPresent()) {
                return new CatalogResult(true, cached.get(), true, null, null);
            }
        }
        // env 种子行是受信来源（私网闸对它是例外；否则 CI 的 127.0.0.1 桩会被拦）
        boolean trusted = AiProvider.SOURCE_ENV_SEED.equals(provider.getSource());
        AiConnectionProbe.ProbeResult result = this.probe.fetchModels(provider.getBaseUrl(),
                provider.getModelsPath(), key.apiKey(), trusted);
        if (!result.ok()) {
            return new CatalogResult(false, List.of(), false, result.code(), result.message());
        }
        writeCache(providerId, result.models());
        return new CatalogResult(true, result.models(), false, null, null);
    }

    private Optional<List<String>> readCache(Long providerId) {
        if (this.redis == null) {
            return Optional.empty();
        }
        try {
            String key = CACHE_KEY_PREFIX + providerId;
            String raw = this.redis.opsForValue().get(key);
            if (!StringUtils.hasText(raw)) {
                return Optional.empty();
            }
            if (Long.valueOf(-1L).equals(this.redis.getExpire(key))) {
                this.redis.delete(key);
                return Optional.empty();
            }
            return Optional.of(this.objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            }));
        } catch (Exception ex) {
            if (LogSampling.canAcquire("model-catalog-cache-read")) {
                log.warn("model catalog cache read failed providerId={} (treating as cache miss)", providerId, ex);
            }
            return Optional.empty();
        }
    }

    private void writeCache(Long providerId, List<String> models) {
        if (this.redis == null) {
            return;
        }
        Duration ttl = AiProperties.effectiveModelsCacheTtl(this.properties.getConfig().getModelsCacheTtl());
        try {
            this.redis.opsForValue().set(CACHE_KEY_PREFIX + providerId, toJson(models), ttl);
        } catch (Exception ex) {
            if (LogSampling.canAcquire("model-catalog-cache-write")) {
                log.warn("model catalog cache write failed providerId={} (response is unaffected)", providerId, ex);
            }
            // 缓存写入失败不影响响应。
        }
    }

    private String toJson(List<String> models) throws Exception {
        return this.objectMapper.writeValueAsString(new ArrayList<>(models));
    }

    /**
     * 一次拉取的结果。
     *
     * <p>
     * N-2（复审）：删掉了 {@code failed(code, status, message)} 静态工厂 —— 它<b>没有调用点</b>，
     * 且收了 {@code status} 却不使用（把"上游状态码"这个信息悄悄丢掉）。保留的
     * {@link #ok(List, boolean)} 在 {@code /test} 的测试桩里有真实调用方。
     *
     * @param cached true = 本次来自缓存（没有打上游）
     * @param code   稳定错误码（ok=true 时为 null）
     */
    public record CatalogResult(@JsonProperty("ok") boolean ok, @JsonProperty("models") @NonNull List<String> models,
                                @JsonProperty("cached") boolean cached, @JsonProperty("code") String code,
                                @JsonProperty("message") String message) {

        public CatalogResult {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public static CatalogResult ok(List<String> models, boolean cached) {
            return new CatalogResult(true, models, cached, null, null);
        }
    }

}
