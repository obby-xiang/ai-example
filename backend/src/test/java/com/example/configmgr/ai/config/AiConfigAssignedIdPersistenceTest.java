package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiConfigAudit;
import com.example.configmgr.ai.config.db.AiConfigAuditRepository;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-config-assigned-id;DB_CLOSE_DELAY=-1",
        "spring.data.redis.database=14",
        "spring.ai.openai.api-key=",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false" })
class AiConfigAssignedIdPersistenceTest {

    @Autowired
    private AiProviderRepository providerRepository;

    @Autowired
    private AiProviderModelRepository modelRepository;

    @Autowired
    private AiConfigAuditRepository auditRepository;

    @Autowired
    private AiConfigWriter writer;

    @Autowired
    private StringRedisTemplate redis;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void deleteCounterKeys() {
        this.redis.delete(List.of("ai:config:id:provider", "ai:config:id:model", "ai:config:id:audit"));
    }

    @Test
    void assignedIdIsInsertedWithItsValue() {
        AiProvider saved = this.providerRepository.saveAndFlush(provider(42L, "assigned", "Assigned"));

        assertThat(saved.getId()).isEqualTo(42L);
        assertThat(this.providerRepository.findById(42L)).isPresent();
    }

    @Test
    void assignedIdConflictDoesNotOverwriteExistingProvider() {
        this.providerRepository.saveAndFlush(provider(41L, "original", "Original"));
        AiProvider conflicting = provider(41L, "conflicting", "Changed");

        assertThatThrownBy(() -> this.providerRepository.saveAndFlush(conflicting))
                .isInstanceOf(DataIntegrityViolationException.class);

        AiProvider existing = this.providerRepository.findById(41L).orElseThrow();
        assertThat(existing.getCode()).isEqualTo("original");
        assertThat(existing.getDisplayName()).isEqualTo("Original");
        assertThat(this.providerRepository.findByCode("conflicting")).isEmpty();
    }

    @Test
    void writerAssignsIdsAndKeepsModelAssociationAuditOrderAndJsonStrings() throws Exception {
        AiProvider provider = this.writer.createProvider(new AiConfigWriter.ProviderCommand(
                "custom-1", "Custom", null, "https://api.deepseek.com", null, null, null, null, null, null));
        AiProviderModel first = this.writer.addModel(provider.getId(), modelCommand("m-1", true));
        AiProviderModel second = this.writer.addModel(provider.getId(), modelCommand("m-2", false));

        assertThat(provider.getId()).isGreaterThan(1_000_000_000_000L);
        assertThat(first.getProviderId()).isEqualTo(provider.getId());
        assertThat(second.getProviderId()).isEqualTo(provider.getId());
        assertThat(first.getId()).isNotEqualTo(second.getId());
        List<Long> auditIds = this.auditRepository.findAllByOrderByIdDesc()
                .stream().map(AiConfigAudit::getId).toList();
        assertThat(auditIds).hasSize(3).isSortedAccordingTo((left, right) -> right.compareTo(left));

        JsonNode providerJson = this.mapper.valueToTree(provider);
        assertThat(providerJson.path("id").isTextual()).isTrue();
        assertThat(providerJson.path("id").asText()).isEqualTo(provider.getId().toString());
        JsonNode modelJson = this.mapper.valueToTree(first);
        assertThat(modelJson.path("providerId").isTextual()).isTrue();
        assertThat(modelJson.path("providerId").asText()).isEqualTo(provider.getId().toString());
    }

    private static AiConfigWriter.ModelCommand modelCommand(String modelId, boolean isDefault) {
        return new AiConfigWriter.ModelCommand(modelId, modelId, 8000, 2048, true, null, null, true,
                isDefault, 0);
    }

    private static AiProvider provider(Long id, String code, String displayName) {
        AiProvider provider = new AiProvider();
        provider.setId(id);
        provider.setCode(code);
        provider.setDisplayName(displayName);
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setCompletionsPath("/chat/completions");
        provider.setModelsPath("/models");
        return provider;
    }
}
