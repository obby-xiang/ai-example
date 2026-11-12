package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiPropertiesModelsCacheTtlGuardTest {

    @Test
    void defaultsPass() {
        assertThatCode(new AiProperties()::verifyModelsCacheTtl).doesNotThrowAnyException();
    }

    @Test
    void nullIsRejected() {
        AiProperties properties = new AiProperties();
        properties.getConfig().setModelsCacheTtl(null);

        assertThatThrownBy(properties::verifyModelsCacheTtl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("models-cache-ttl");
    }

    @Test
    void zeroIsRejected() {
        AiProperties properties = new AiProperties();
        properties.getConfig().setModelsCacheTtl(Duration.ZERO);

        assertThatThrownBy(properties::verifyModelsCacheTtl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("models-cache-ttl");
    }

    @Test
    void negativeIsRejected() {
        AiProperties properties = new AiProperties();
        properties.getConfig().setModelsCacheTtl(Duration.ofMinutes(-1));

        assertThatThrownBy(properties::verifyModelsCacheTtl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("models-cache-ttl");
    }
}
