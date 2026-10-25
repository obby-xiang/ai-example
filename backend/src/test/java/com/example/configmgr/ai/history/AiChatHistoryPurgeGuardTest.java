package com.example.configmgr.ai.history;

import com.example.configmgr.ai.history.AiChatHistoryService.AppendCommand;
import com.example.configmgr.ai.history.AiChatHistoryService.MessageView;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.common.AiStateUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 跨分支竞态：会话销毁守卫必须在<b>历史实际写入的最低层</b>（{@link AiChatHistoryService#append}）
 * 生效，而不是只拦某一条编排路径。
 *
 * <p>确定性用例：
 * <ol>
 * <li>guard armed ⇒ 零插入、append 返回 null；</li>
 * <li>未 armed ⇒ 正常写入；</li>
 * <li>guard 读取异常 ⇒ 不写、异常原样上抛（fail-closed，不吞）；</li>
 * <li>未 armed 的重复 append 仍按 (session, run, messageId) 幂等。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-chat-history-purge-guard;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.ai.openai.api-key=",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false",
        "app.ai.kb.enabled=false" })
class AiChatHistoryPurgeGuardTest {

    private static final String SESSION = "sess-purge-guard";

    @Autowired
    private AiChatHistoryService history;

    @Autowired
    private AiChatMessageRepository repository;

    @Autowired
    private AiChatHistoryRecorder recorder;

    @MockitoBean
    private SessionPurgeGuard purgeGuard;

    @BeforeEach
    void clean() {
        this.repository.deleteAll();
        reset(this.purgeGuard);
    }

    @Test
    void armedGuardInsertsNothingAndReturnsNull() {
        when(this.purgeGuard.isArmed(SESSION)).thenReturn(true);

        MessageView result = this.history.append(command());

        assertThat(result).as("销毁语义下静默跳过，不返回历史行").isNull();
        assertThat(this.history.count(SESSION)).as("不得插入任何历史行").isZero();
    }

    @Test
    void normalAppendStillWorksWhenGuardNotArmed() {
        when(this.purgeGuard.isArmed(SESSION)).thenReturn(false);

        MessageView result = this.history.append(command());

        assertThat(result).isNotNull();
        assertThat(result.seq()).isEqualTo(1L);
        assertThat(this.history.count(SESSION)).isEqualTo(1);
    }

    @Test
    void guardFailurePropagatesAndInsertsNothing() {
        when(this.purgeGuard.isArmed(SESSION))
                .thenThrow(new AiStateUnavailableException("无法确认会话销毁守卫状态（fail-closed）"));

        assertThatThrownBy(() -> this.history.append(command()))
                .isInstanceOf(AiStateUnavailableException.class)
                .hasMessageContaining("fail-closed");
        assertThat(this.history.count(SESSION)).as("守卫读取失败必须 fail-closed，不得写历史").isZero();
    }

    @Test
    void recorderDoesNotSwallowGuardFailure() {
        when(this.purgeGuard.isArmed(SESSION))
                .thenThrow(new AiStateUnavailableException("无法确认会话销毁守卫状态（fail-closed）"));

        assertThatThrownBy(() -> this.recorder.recordUser(SESSION, "run-recorder-guard",
                UserMessage.builder().text("旧轮消息").build()))
                .isInstanceOf(AiStateUnavailableException.class)
                .hasMessageContaining("fail-closed");
        assertThat(this.history.count(SESSION)).as("recorder 不得把 guard 失败降级成 WARN 后继续").isZero();
    }

    @Test
    void normalDuplicateAppendRemainsIdempotent() {
        when(this.purgeGuard.isArmed(SESSION)).thenReturn(false);

        MessageView first = this.history.append(command());
        MessageView second = this.history.append(command());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.seq()).isEqualTo(first.seq());
        assertThat(this.history.count(SESSION)).isEqualTo(1);
    }

    private static AppendCommand command() {
        return new AppendCommand(SESSION, "run-purge-guard", "assistant-run-purge-guard", "ASSISTANT",
                "旧轮终局回答", null, null, "{\"reasoningContent\":\"思考\"}", false);
    }
}
