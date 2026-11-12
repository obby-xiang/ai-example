package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelDefaultsContractTest {

    private final AiConfigTestFixture fixture = new AiConfigTestFixture();

    @Test
    void v1ToolCallingDefaultsFalseForBothDialects() throws Exception {
        String sql = new ClassPathResource("db/migration/V1__schema.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("supports_tool_calling BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @Test
    void postgresqlV1ToolCallingDefaultsFalse() throws Exception {
        String sql = new ClassPathResource("db/postgresql/V1__schema.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("supports_tool_calling BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @Test
    void entityToolCallingDefaultsFalse() {
        assertThat(new AiProviderModel().isSupportsToolCalling()).isFalse();
    }

    @Test
    void envSeedLeavesWindowUnknownAndCapabilitiesDisabled() {
        AiProviderModel model = this.fixture.service().envSeedModel();

        assertThat(model.isSupportsToolCalling()).isFalse();
        assertThat(model.getSupportsReasoning()).isFalse();
        assertThat(model.getMaxContext()).isNull();
    }

    @Test
    void missingModelRowDisablesToolCalling() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://placeholder");

        assertThat(this.fixture.service().isToolCallingSupported(provider.getId(), "missing-model")).isFalse();
    }
}
