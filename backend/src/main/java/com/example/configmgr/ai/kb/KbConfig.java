package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 知识源装配（追加 2/3）：按配置选择 local / http，并在其外套一层 Redis cache-aside。
 *
 * <p>单一装配点，避免两个 {@code @Component} 靠 {@code @Primary} 打架 —— {@code KbService}
 * 只认 {@link KbSource} 抽象，测试直接 {@code new} 具体源即可。
 *
 * <p><b>缓存装配条件</b>：{@code app.ai.kb.cache.enabled=true} 且容器里有
 * {@link StringRedisTemplate}。没有 Redis 的环境（本地起服务、纯单测）自动退回直连源；
 * 运行期 Redis 抖动由 {@link CachedKbSource} 降级，不影响知识问答可用性。
 */
@Slf4j
@Configuration
public class KbConfig {

    @Bean
    public KbSource kbSource(AiProperties properties, ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        AiProperties.Kb kb = properties.getKb();
        KbSource base;
        if ("http".equalsIgnoreCase(kb.getSource())) {
            AiProperties.Kb.Http http = kb.getHttp();
            base = new HttpKbSource(http.getBaseUrl(), http.getConnectTimeout(), http.getReadTimeout(),
                    http.getAuthEnv(), objectMapper);
        }
        else {
            base = new LocalDirKbSource(java.nio.file.Path.of(kb.getDir()), objectMapper);
        }
        if (!kb.getCache().isEnabled()) {
            return base;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("知识库缓存层未装配（容器无 StringRedisTemplate），直连知识源：{}", base.kind());
            return base;
        }
        log.info("知识库缓存层已装配：source={} lock-ttl={} version-poll={}", base.kind(),
                kb.getCache().getLockTtl(), kb.getCache().getVersionPollInterval());
        return new CachedKbSource(base, new KbCache(new RedisKbCacheStore(redis)), objectMapper, kb);
    }
}
