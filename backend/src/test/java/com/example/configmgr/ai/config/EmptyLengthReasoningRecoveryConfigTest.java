package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmptyLengthReasoningRecoveryConfigTest {

    private final AiConfigTestFixture fixture = new AiConfigTestFixture();

    @Test
    void missingTargetPolicyIsDisabled() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true);
        AiConfigService service = this.fixture.service();
        AtomicReference<Object> policy = new AtomicReference<>();

        assertThatCode(() -> policy.set(ReflectionTestUtils.invokeMethod(service,
                "emptyLengthReasoningRecovery", provider.getId(), "model-a")))
                .doesNotThrowAnyException();
        assertThat(policy.get()).isNull();
    }

    @Test
    void unknownPolicyFieldIsRejectedWhenRead() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true)
                .setEmptyLengthReasoningRecovery("{\"reasoningEffort\":\"low\",\"unknown\":true}");
        AiConfigService service = this.fixture.service();

        assertThatThrownBy(() -> service.emptyLengthReasoningRecovery(provider.getId(), "model-a"))
                .isInstanceOf(AiConfigException.class)
                .extracting(ex -> ((AiConfigException) ex).getCode())
                .isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
    }

    @Test
    void duplicateReasoningEffortFieldIsRejectedAsConflicting() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true)
                .setEmptyLengthReasoningRecovery("{\"reasoningEffort\":\"low\","
                        + "\"requestExtraBody\":{\"reasoning_effort\":\"high\"}}");
        AiConfigService service = this.fixture.service();

        assertThatThrownBy(() -> service.emptyLengthReasoningRecovery(provider.getId(), "model-a"))
                .isInstanceOf(AiConfigException.class)
                .extracting(ex -> ((AiConfigException) ex).getCode())
                .isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
    }

    @Test
    void recoveryCannotDefineASecondTokenBudget() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true)
                .setEmptyLengthReasoningRecovery("{\"requestExtraBody\":{\"max_completion_tokens\":1}}");
        AiConfigService service = this.fixture.service();

        assertThatThrownBy(() -> service.emptyLengthReasoningRecovery(provider.getId(), "model-a"))
                .isInstanceOf(AiConfigException.class)
                .extracting(ex -> ((AiConfigException) ex).getCode())
                .isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
    }

    @Test
    void nonTextReasoningEffortIsRejectedWithInvalidParam() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true)
                .setEmptyLengthReasoningRecovery("{\"reasoningEffort\":1}");
        AiConfigService service = this.fixture.service();

        assertThatThrownBy(() -> service.emptyLengthReasoningRecovery(provider.getId(), "model-a"))
                .isInstanceOf(AiConfigException.class)
                .extracting(ex -> ((AiConfigException) ex).getCode())
                .isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
    }

    @Test
    void nonObjectExtraBodyIsRejectedWithInvalidParam() {
        AiProvider provider = this.fixture.addEnvProvider("custom", "http://custom");
        this.fixture.addModel(provider.getId(), "model-a", true)
                .setEmptyLengthReasoningRecovery("{\"requestExtraBody\":[]}");
        AiConfigService service = this.fixture.service();

        assertThatThrownBy(() -> service.emptyLengthReasoningRecovery(provider.getId(), "model-a"))
                .isInstanceOf(AiConfigException.class)
                .extracting(ex -> ((AiConfigException) ex).getCode())
                .isEqualTo(AiConfigWriter.CODE_INVALID_PARAM);
    }

}
