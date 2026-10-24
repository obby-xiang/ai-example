package com.example.configmgr.common;

import com.example.configmgr.ai.run.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1-13：零键基座 {@code messages.properties} 让 Spring Boot 的
 * {@code ResourceBundleCondition} 成立，从而启用 Boot 原生的
 * {@link ResourceBundleMessageSource}，而不是 Spring 兜底的
 * {@code DelegatingMessageSource}；基座本身不承载任何文案，只负责触发自动配置。
 *
 * <p>本文件锁住三条事实：en 请求端到端拿到英文（Accept-Language → resolver →
 * LocaleContextHolder → ErrorBodyFactory → MessageSource）；zh 由
 * {@link ErrorBodyFactory} 短路为调用方原文（不查资源束）；resolver 把未支持 locale
 * 归一为 zh-CN（随后同样走 zh 短路）。基座零键与缺 key 断言保证它不会遮蔽
 * zh/en 束或兜底回退。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:i18n-message-source;DB_CLOSE_DELAY=-1")
class MessageSourceI18nSpringTest {

    private static final String MISSING_DEFINITION = "__r1_13_missing__";

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ErrorBodyFactory bodyFactory;

    @Test
    void autoConfiguredMessageSourceIsResourceBundleMessageSource() {
        assertThat(this.messageSource)
                .as("零键基座也必须产出 Boot 原生的 ResourceBundleMessageSource")
                .isInstanceOf(ResourceBundleMessageSource.class);
        assertThat(((ResourceBundleMessageSource) this.messageSource).getBasenameSet())
                .containsExactly("messages");
    }

    @Test
    void bundleResolvesRegionalLocalesThroughLocaleChains() {
        assertThat(this.messageSource.getMessage("error.TASK_FINALIZED", null, Locale.SIMPLIFIED_CHINESE))
                .as("zh-CN 经 messages_zh_CN → messages_zh → messages 链")
                .isEqualTo("任务已终态，不可再修改");
        assertThat(this.messageSource.getMessage("error.TASK_FINALIZED", null, Locale.US))
                .as("en-US 经 messages_en_US → messages_en → messages 链")
                .isEqualTo("The task is finalized and can no longer change");
        assertThat(this.messageSource.getMessage("error.TASK_FINALIZED", null, Locale.UK))
                .as("en-GB 经 messages_en_GB → messages_en → messages 链")
                .isEqualTo("The task is finalized and can no longer change");
    }

    @Test
    void zeroKeyBaseCannotShadowLocaleBundlesOrMissingKeys() {
        assertThat(ResourceBundle.getBundle("messages", Locale.ROOT).keySet())
                .as("基座 messages.properties 必须零键：任何键都会成为所有 locale 的父束兜底")
                .isEmpty();
        ResourceBundle noFallback = ResourceBundle.getBundle("messages", Locale.GERMANY,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        assertThat(noFallback.keySet())
                .as("de-DE 无专属束；按 fallback-to-system-locale=false 落到零键基座，而不是 JVM 默认的 zh 束")
                .isEmpty();
        assertThatThrownBy(() -> this.messageSource.getMessage("error.TASK_FINALIZED", null, Locale.GERMANY))
                .as("de-DE 不得被基座救活")
                .isInstanceOf(NoSuchMessageException.class);
        assertThatThrownBy(() -> this.messageSource.getMessage("error.R1_13_NO_SUCH_CODE", null, Locale.US))
                .as("en 缺 key 时不得被基座补出文案")
                .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void contextLevelResumeFailureEnglishTextMatchesWhitelistConstant() {
        assertThat(this.messageSource.getMessage("error." + ErrorCodes.AI_RESUME_FAILED, null, Locale.US))
                .as("上下文注入的 MessageSource 命中 messages_en，且与白名单常量逐字一致")
                .isEqualTo(ResumeService.AI_RESUME_FAILED_MESSAGE_EN);
    }

    @Test
    void englishRequestGetsEnglishApiErrorMessage() throws Exception {
        this.mockMvc.perform(get("/api/definitions/" + MISSING_DEFINITION)
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RESOURCE_NOT_FOUND))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void englishLanguageOnlyRequestGetsEnglishApiErrorMessage() throws Exception {
        this.mockMvc.perform(get("/api/definitions/" + MISSING_DEFINITION)
                        .header("Accept-Language", "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void chineseRequestKeepsCallerText() throws Exception {
        // zh 在 ErrorBodyFactory 短路，不查资源束；基座存在与否都不影响该断言。
        this.mockMvc.perform(get("/api/definitions/" + MISSING_DEFINITION)
                        .header("Accept-Language", "zh-CN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("配置定义 不存在: " + MISSING_DEFINITION));
    }

    @Test
    void unsupportedLocaleStillFallsBackToChineseDefault() throws Exception {
        // de-DE 在 resolver 即归一为 zh-CN，随后走 zh 短路，不到资源束。
        this.mockMvc.perform(get("/api/definitions/" + MISSING_DEFINITION)
                        .header("Accept-Language", "de-DE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("配置定义 不存在: " + MISSING_DEFINITION));
    }

    @Test
    void missingKeyFallsBackToCallerText() {
        assertThat(this.bodyFactory.message("R1_13_NO_SUCH_CODE", "调用方原文", null, Locale.US))
                .as("en 缺 key 时回落原文，绝不回显裸 key")
                .isEqualTo("调用方原文");
    }
}
