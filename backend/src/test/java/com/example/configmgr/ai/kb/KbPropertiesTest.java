package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 矩阵 M24：知识库配置缺省与启动期校验（倒挂/非正值 fail-fast，不静默纠正）。
 */
class KbPropertiesTest {

    @Test
    void defaultsMatchContract() {
        AiProperties.Kb kb = new AiProperties().getKb();
        assertThat(kb.isEnabled()).isTrue();
        assertThat(kb.getSource()).isEqualTo("local");
        assertThat(kb.getDir()).isEqualTo("./data/kb");
        assertThat(kb.getIndexDir()).isEqualTo("./data/kb-index");
        assertThat(kb.getRefreshInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(kb.getTopKDefault()).isEqualTo(5);
        assertThat(kb.getTopKMax()).isEqualTo(10);
        assertThat(kb.getReadMaxCharsDefault()).isEqualTo(4000);
        assertThat(kb.getReadMaxCharsMax()).isEqualTo(8000);
        assertThat(kb.getChunkMinChars()).isEqualTo(300);
        assertThat(kb.getChunkMaxChars()).isEqualTo(800);
        assertThat(kb.getMatchHighRatio()).isEqualTo(0.7);
        assertThat(kb.getMatchMediumRatio()).isEqualTo(0.4);
        assertThat(kb.getHttp().getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(kb.getHttp().getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(kb.getHttp().getAuthEnv()).isEqualTo("KB_HTTP_AUTH");
    }

    @Test
    void defaultsPassStartupVerification() {
        new AiProperties().verifyKb();
    }

    @Test
    void topKInversionFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getKb().setTopKDefault(20);
        assertThatThrownBy(properties::verifyKb)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k-default");
    }

    @Test
    void chunkInversionFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getKb().setChunkMinChars(900);
        assertThatThrownBy(properties::verifyKb)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-min-chars");
    }

    @Test
    void matchRatioOrderingFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getKb().setMatchMediumRatio(0.9);
        assertThatThrownBy(properties::verifyKb)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("match-medium-ratio");
    }

    @Test
    void httpSourceRequiresBaseUrl() {
        AiProperties properties = new AiProperties();
        properties.getKb().setSource("http");
        assertThatThrownBy(properties::verifyKb)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void zeroRefreshIntervalFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getKb().setRefreshInterval(Duration.ZERO);
        assertThatThrownBy(properties::verifyKb)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-interval");
    }

    @Test
    void topKAndMaxCharsAreClampedOnTheServer() throws Exception {
        KbService service = KbTestSupport.samplesService();

        assertThat(service.effectiveTopK(null)).isEqualTo(5);
        assertThat(service.effectiveTopK(0)).isEqualTo(5);
        assertThat(service.effectiveTopK(3)).isEqualTo(3);
        assertThat(service.effectiveTopK(999)).isEqualTo(10);

        assertThat(service.effectiveMaxChars(null)).isEqualTo(4000);
        assertThat(service.effectiveMaxChars(-1)).isEqualTo(4000);
        assertThat(service.effectiveMaxChars(2000)).isEqualTo(2000);
        assertThat(service.effectiveMaxChars(99999)).isEqualTo(8000);
    }
}
