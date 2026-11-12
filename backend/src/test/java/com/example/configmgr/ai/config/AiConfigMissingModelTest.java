package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-config-missing-model;DB_CLOSE_DELAY=-1",
        "spring.ai.openai.api-key=missing-model-key",
        "spring.ai.openai.chat.options.model=",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false" })
class AiConfigMissingModelTest {

    @Autowired
    private AiProviderRepository providerRepository;

    @Autowired
    private AiProviderModelRepository modelRepository;

    @Autowired
    private AiAvailability availability;

    @Autowired
    private AiProviderRegistry registry;

    @MockitoBean
    private AiConnectionProbe probe;

    @Test
    void missingEnvModelFailsClosedWithoutSeedingOrCallingUpstream() {
        assertThat(this.availability.isAvailable()).isFalse();
        assertThat(this.availability.code()).isEqualTo(AiConfigService.CODE_NO_MODEL_CONFIGURED);
        assertThat(this.registry.tryCurrent()).isEmpty();
        assertThat(this.providerRepository.count()).isZero();
        assertThat(this.modelRepository.count()).isZero();
        verifyNoInteractions(this.probe);
    }
}
