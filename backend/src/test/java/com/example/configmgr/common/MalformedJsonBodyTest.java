package com.example.configmgr.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>L-96</b>：畸形 / 不可解析的请求体 → <b>400</b>（此前落到兜底 {@code Exception} handler 回 500）。
 *
 * <p>
 * 缺陷形态（2026-10-23）：{@code HttpMessageNotReadableException} 无专门 handler，被
 * {@link GlobalExceptionHandler#handleGeneral} 兜成 {@code 500 INTERNAL_ERROR} —— 客户端把
 * "请求体写错了"判成"服务端炸了"（重试/告警/上报全都指错方向）。
 *
 * <p>
 * 口径：状态 400；错误体形态与其它错误一致（业务信封 {@code code+message}，{@code Accept} 含
 * {@code application/problem+json} 时切 RFC 9457）；code 复用既有 {@link ErrorCodes#BAD_REQUEST}；
 * en 走 {@code messages_en} 的 {@code error.BAD_REQUEST} 兜底（zh 逐字用原文）。
 *
 * <p><b>证红</b>：去掉 {@code handleUnreadableBody} ⇒ 三条用例全红（实际 500 / code INTERNAL_ERROR）。
 */
class MalformedJsonBodyTest {

    private final ResourceBundleMessageSource messageSource = messageSource();

    private final GlobalExceptionHandler handler = handlerWithMessages();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        // 与 WebI18nConfig 同口径：无 Accept-Language 头 = zh（不随机器 JVM 默认 locale 漂移）
        localeResolver.setDefaultLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        this.mockMvc = MockMvcBuilders.standaloneSetup(new EchoController())
                .setControllerAdvice(handler)
                .setLocaleResolver(localeResolver)
                .build();
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void malformedJsonIsRejectedAs400Not500() throws Exception {
        this.mockMvc.perform(post("/api/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("请求体格式不合法"));
    }

    @Test
    void emptyBodyIsAlsoA400() throws Exception {
        this.mockMvc.perform(post("/api/test/echo")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST));
    }

    @Test
    void englishLocaleFallsBackToMessagesEn() throws Exception {
        this.mockMvc.perform(post("/api/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "en-US")
                        .content("{\"a\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("Bad request"));
    }

    @Test
    void problemJsonNegotiationStillAppliesTo400() throws Exception {
        this.mockMvc.perform(post("/api/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON)
                        .content("{\"a\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST));
    }

    private GlobalExceptionHandler handlerWithMessages() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton(org.springframework.context.MessageSource.class.getName(), this.messageSource);
        return new GlobalExceptionHandler(new ErrorBodyFactory(factory.getBeanProvider(
                org.springframework.context.MessageSource.class)));
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @RestController
    static class EchoController {

        @PostMapping("/api/test/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }
    }
}
