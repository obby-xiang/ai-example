package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-config-seeder-assigned-id;DB_CLOSE_DELAY=-1",
        "spring.data.redis.database=14",
        "spring.ai.openai.api-key=seed-env-placeholder-11112222",
        "spring.ai.openai.base-url=https://api.deepseek.com",
        "spring.ai.openai.chat.options.model=seed-model",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false" })
class AiConfigSeederAssignedIdTest {

    @Autowired
    private AiProviderRepository providerRepository;

    @Autowired
    private AiProviderModelRepository modelRepository;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void deleteCounterKeys() {
        this.redis.delete(List.of("ai:config:id:provider", "ai:config:id:model", "ai:config:id:audit"));
    }

    @Test
    void startupSeederAssignsTimeBasedIdsAndKeepsProviderModelAssociation() {
        AiProvider provider = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE).orElseThrow();
        List<AiProviderModel> models = this.modelRepository.findByProviderId(provider.getId());

        assertThat(provider.getId()).isGreaterThan(1_000_000_000_000L);
        assertThat(models).hasSize(1);
        assertThat(models.getFirst().getId()).isGreaterThan(1_000_000_000_000L);
        assertThat(models.getFirst().getProviderId()).isEqualTo(provider.getId());
    }
}
