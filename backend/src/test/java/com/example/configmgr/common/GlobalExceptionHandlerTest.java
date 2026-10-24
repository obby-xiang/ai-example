package com.example.configmgr.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M3-2 / M3-3 / D5 / D7：错误响应形态与码。
 *
 * <p>
 * 证红证据（施工前）：{@code IllegalArgumentException} 走 {@code ApiResponse.error(message)} ⇒
 * 响应体 {@code code == null}；{@code Accept: application/problem+json} 仍是信封 ⇒ 本文件的
 * problem+json 与 en 断言都会红。
 */
class GlobalExceptionHandlerTest {

    private final ResourceBundleMessageSource messageSource = messageSource();

    private final GlobalExceptionHandler handler = handlerWithMessages();

    private GlobalExceptionHandler handlerWithMessages() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton(org.springframework.context.MessageSource.class.getName(), messageSource);
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

    private final Locale originalDefaultLocale = Locale.getDefault();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
        Locale.setDefault(originalDefaultLocale);
    }

    /**
     * CI 回归：Ubuntu 的 JVM 默认 locale 是 en-US，本机是 zh-CN（见 run 39613094682 的 5 红）。
     * 无 `Accept-Language` 头 = 默认 zh（S6 口径），不得因 JVM 默认 locale 漂成英文。
     */
    @Test
    void jvmDefaultLocaleDoesNotLeakIntoErrorMessages() {
        Locale.setDefault(Locale.ENGLISH);
        LocaleContextHolder.resetLocaleContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        assertNull(request.getHeader("Accept-Language"), "本用例的前提：无 Accept-Language 头");

        ResponseEntity<?> response = handlerWithMessages().handleIllegalArg(
                new IllegalArgumentException("字段编码不能为空"), request);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals("字段编码不能为空", body.getMessage(),
                "无 Accept-Language 时默认 zh，不得随 JVM 默认 locale 取 en");

        ResponseEntity<?> conflict = handlerWithMessages().handleConflict(
                new ConflictException(ErrorCodes.TASK_FINALIZED, "任务 #7 已终态"), request);
        ApiResponse<?> conflictBody = assertInstanceOf(ApiResponse.class, conflict.getBody());
        assertEquals("任务 #7 已终态", conflictBody.getMessage());
    }

    @Test
    void plainIllegalArgumentStillCarriesStableCode() {
        ResponseEntity<?> response = handler.handleIllegalArg(new IllegalArgumentException("字段编码不能为空"), null);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(400, response.getStatusCode().value());
        assertEquals(ErrorCodes.ILLEGAL_ARGUMENT, body.getCode(), "无码异常必须落到类型缺省码");
        assertEquals("字段编码不能为空", body.getMessage(), "zh 文案必须逐字不变");
    }

    @Test
    void plainIllegalStateCarriesStableCode() {
        ResponseEntity<?> response = handler.handleIllegalState(new IllegalStateException("状态不允许"), new MockHttpServletRequest());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.ILLEGAL_STATE, body.getCode());
        assertEquals("状态不允许", body.getMessage());
    }

    @Test
    void codedExceptionKeepsItsOwnCodeAndType() {
        IllegalArgumentException error = new CodedIllegalArgumentException(
                ErrorCodes.STEP_NOT_IN_SEQUENCE, "目标步骤不在该任务类型的步骤序列内：PUBLISH");
        ResponseEntity<?> response = handler.handleIllegalArg(error, null);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.STEP_NOT_IN_SEQUENCE, body.getCode());
        assertEquals("目标步骤不在该任务类型的步骤序列内：PUBLISH", body.getMessage());
        // is-a 关系不变：既有按 IllegalArgumentException 分支的调用方零改动
        assertInstanceOf(IllegalArgumentException.class, error);
    }

    @Test
    void traceIdAndArgsAreAbsentUnlessProvided() {
        ResponseEntity<?> response = handler.handleIllegalArg(new IllegalArgumentException("普通错误"), null);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertNull(body.getTraceId(), "没有 traceId 时不得塞空键（NON_NULL 口径）");
        assertNull(body.getArgs(), "没有参数时不得塞空键");
    }

    @Test
    void problemJsonNegotiationOnlyWhenAccepted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/definitions");
        request.addHeader("Accept", MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        request.setAttribute("traceId", "trace-1");

        ResponseEntity<?> response = handlerWithMessages().handleConflict(
                new ConflictException(ErrorCodes.TASK_FINALIZED, "任务 #7 已终态"), request);
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ProblemDetail problem = assertInstanceOf(ProblemDetail.class, response.getBody());
        assertEquals(409, problem.getStatus());
        assertEquals("urn:cfg:error:TASK_FINALIZED", problem.getType().toString());
        assertEquals("任务 #7 已终态", problem.getDetail());
        assertEquals(ErrorCodes.TASK_FINALIZED, problem.getProperties().get("code"));
        assertEquals("trace-1", problem.getProperties().get("traceId"));
    }

    @Test
    void defaultShapeStaysEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/definitions");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        ResponseEntity<?> response = handlerWithMessages().handleConflict(
                new ConflictException(ErrorCodes.TASK_FINALIZED, "任务 #7 已终态"), request);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.TASK_FINALIZED, body.getCode());
        assertEquals("任务 #7 已终态", body.getMessage());
    }

    @Test
    void englishLocaleTakesMessagesEn() {
        LocaleContextHolder.setLocale(Locale.US);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        ResponseEntity<?> response = handlerWithMessages().handleIllegalArg(
                new IllegalArgumentException("字段编码不能为空"), request);
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.ILLEGAL_ARGUMENT, body.getCode());
        assertEquals("Invalid argument", body.getMessage(), "en 下取 messages_en，绝不回显裸 key");
    }

    @Test
    void chineseLocaleKeepsOriginalTextEvenWhenMessagesHasKey() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ResponseEntity<?> response = handlerWithMessages().handleIllegalArg(
                new IllegalArgumentException("字段编码不能为空"), new MockHttpServletRequest("GET", "/api/x"));
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals("字段编码不能为空", body.getMessage(), "zh 一律用调用方原文（E2E 逐字不变）");
    }

    @Test
    void localeResolverDefaultsToChinese() {
        LocaleResolver resolver = new WebI18nConfig().localeResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(request));

        MockHttpServletRequest english = new MockHttpServletRequest();
        english.addHeader("Accept-Language", "en-US");
        assertEquals(Locale.US, resolver.resolveLocale(english));

        MockHttpServletRequest traditional = new MockHttpServletRequest();
        traditional.addHeader("Accept-Language", "zh-TW");
        Locale resolved = resolver.resolveLocale(traditional);
        assertEquals("zh", resolved.getLanguage(), "zh-TW 应回落 messages_zh 链");
    }

    @Test
    void foreignKeyViolationMapsTo409BusinessConflict() {
        DataIntegrityViolationException error = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("Referential integrity constraint violation", "23506", 23506));

        ResponseEntity<?> response = handlerWithMessages().handleDataIntegrity(
                error, new MockHttpServletRequest("POST", "/api/tasks/1/files/upload"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.ILLEGAL_STATE, body.getCode());
        assertNotNull(body.getMessage());
    }

    @Test
    void nonForeignKeyIntegrityViolationStays500() {
        DataIntegrityViolationException error = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("Unique index or primary key violation", "23505", 23505));

        ResponseEntity<?> response = handlerWithMessages().handleDataIntegrity(
                error, new MockHttpServletRequest("POST", "/api/tasks/1/files/upload"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.INTERNAL_ERROR, body.getCode());
    }

    @Test
    void unknownCodeFallsBackToCallerMessage() {
        LocaleContextHolder.setLocale(Locale.US);
        ResponseEntity<?> response = handlerWithMessages().handleConflict(
                new ConflictException("NOT_REGISTERED_CODE", "自定义冲突文案"), new MockHttpServletRequest("GET", "/api/x"));
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals("自定义冲突文案", body.getMessage());
        assertNotNull(body.getCode());
        assertTrueNoBareKey(body.getMessage());
    }

    private static void assertTrueNoBareKey(String message) {
        if (message != null && message.startsWith("error.")) {
            throw new AssertionError("不得回显裸 key：" + message);
        }
    }

    /** 头畸形 / 空白 / 不支持的语言，一律 zh-CN——不得经 `request.getLocale()` 退到 JVM 默认 locale。 */
    @Test
    void localeResolverFallsBackToChineseWhenHeaderUnusable() {
        LocaleResolver resolver = new WebI18nConfig().localeResolver();

        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.addHeader("Accept-Language", "en_US;;q=x");
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(malformed), "畸形头回落 zh");

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addHeader("Accept-Language", " ");
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(blank), "空白头回落 zh");

        MockHttpServletRequest unsupported = new MockHttpServletRequest();
        unsupported.addHeader("Accept-Language", "de-DE");
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveLocale(unsupported), "不支持的语言回落 zh");
    }

    @Test
    void allCodesHaveChineseAndEnglishText() {
        for (String code : ErrorCodes.ALL) {
            String zh = messageSource.getMessage(ErrorBodyFactory.KEY_PREFIX + code, null, Locale.SIMPLIFIED_CHINESE);
            String en = messageSource.getMessage(ErrorBodyFactory.KEY_PREFIX + code, null, Locale.US);
            assertNotNull(zh, "缺 zh 文案：" + code);
            assertNotNull(en, "缺 en 文案：" + code);
            if (List.of(zh, en).isEmpty()) {
                throw new AssertionError("空文案：" + code);
            }
        }
    }
}
