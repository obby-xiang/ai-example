package com.example.configmgr.ai.web;

import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S6 尾项（批3二）：AI 端点错误体在非中文 locale 下取 {@code messages_en} 的码级兜底。
 *
 * <p>口径三条：① 响应形态仍是裸 {@code {code, message}}（不套信封，前端/脚本按现有形状读）；
 * ② 中文（含无 locale 上下文）逐字取调用方原文 —— 存量中文断言与 E2E 字节不变；
 * ③ 英文缺键回落原文，绝不回显裸 key。
 */
class AiControllerErrorBodyLocaleTest {

    /** 只测拒绝分支：其余依赖全 null，被拒绝的请求不会触碰它们。 */
    private final AiController controller = new AiController(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
        Locale.setDefault(originalDefault);
    }

    private static ErrorBodyFactory factory() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
        return new ErrorBodyFactory(beans.getBeanProvider(org.springframework.context.MessageSource.class));
    }

    private Map<String, Object> body() {
        return (Map<String, Object>) this.controller.runsCurrent(null).getBody();
    }

    @Test
    void withoutLocaleContextKeepsCallerChineseText() {
        Locale.setDefault(Locale.US);
        LocaleContextHolder.resetLocaleContext();
        ReflectionTestUtils.setField(this.controller, "errorBodyFactory", factory());

        Map<String, Object> body = body();
        assertEquals(ErrorCodes.BAD_REQUEST, body.get("code"));
        assertEquals("sessionId 为必填", body.get("message"));
        assertEquals(2, body.size(), "响应形态不变：只有 code + message 两个键");
    }

    @Test
    void englishLocaleTakesMessagesEn() {
        LocaleContextHolder.setLocale(Locale.US);
        ReflectionTestUtils.setField(this.controller, "errorBodyFactory", factory());

        Map<String, Object> body = body();
        assertEquals(ErrorCodes.BAD_REQUEST, body.get("code"));
        assertEquals("Bad request", body.get("message"));
    }

    @Test
    void chineseLocaleKeepsCallerText() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ReflectionTestUtils.setField(this.controller, "errorBodyFactory", factory());

        assertEquals("sessionId 为必填", body().get("message"));
    }
}
