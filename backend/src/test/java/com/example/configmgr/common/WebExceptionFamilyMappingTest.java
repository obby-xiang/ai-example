package com.example.configmgr.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>L-111</b>：Spring 内建异常族按<b>异常族</b>一次性收口（此前全部落兜底 {@code Exception} handler ⇒ 500）。
 *
 * <p>
 * 缺陷形态（2026-10-23，L-96 施工时观测）：{@code @ExceptionHandler(Exception.class)} 是唯一出口，
 * Spring MVC 自己抛的客户端错误异常（参数缺失 / 类型不匹配 / 方法不支持 / 媒体类型不支持 / 显式状态异常）
 * 都被兜成 {@code 500 INTERNAL_ERROR} —— 「状态码被吃掉」，客户端把"我发错了"读成"服务端炸了"。
 * 修法 = {@link GlobalExceptionHandler} 内的<b>映射表</b>（异常族 → 状态码 + 稳定码 + 文案），不按异常逐个补。
 *
 * <p>
 * <b>证红</b>（修前）：{@code missingRequestParameter} / {@code typeMismatch} / {@code methodNotAllowed}
 * / {@code unsupportedMediaType} / {@code responseStatusException} / {@code noHandlerFound} 全红
 * （实际 500 / {@code INTERNAL_ERROR}）。
 *
 * <p>
 * 码值口径：<b>不新增码</b>（新增码要三方对账，会牵动 {@code frontend/src/types/api.ts} 镜像；本批纯后端）。
 * 故 4xx 族复用 {@link ErrorCodes#BAD_REQUEST}，404 族复用 {@link ErrorCodes#RESOURCE_NOT_FOUND}，
 * en 文案走既有 {@code messages_en} 的 {@code error.<CODE>} 兜底；状态码本身仍按 HTTP 语义给足
 * （400 / 404 / 405 / 406 / 415）。
 */
class WebExceptionFamilyMappingTest {

    private final ResourceBundleMessageSource messageSource = messageSource();

    private final GlobalExceptionHandler handler = handlerWithMessages();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(this.handler)
                .setLocaleResolver(localeResolver)
                .build();
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    // ── 400 族：参数缺失 / 类型不匹配 ─────────────────────────────────────

    @Test
    void missingRequestParameterIs400Not500() throws Exception {
        this.mockMvc.perform(get("/api/test/need-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("缺少必需的请求参数"));
    }

    @Test
    void argumentTypeMismatchIs400Not500() throws Exception {
        this.mockMvc.perform(get("/api/test/need-int").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("请求参数类型不匹配"));
    }

    // ── 405 / 415 / 406 族 ───────────────────────────────────────────────

    @Test
    void methodNotAllowedIs405WithAllowHeader() throws Exception {
        this.mockMvc.perform(post("/api/test/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("该接口不支持此请求方法"));
    }

    @Test
    void unsupportedMediaTypeIs415Not500() throws Exception {
        this.mockMvc.perform(post("/api/test/json-only")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("纯文本"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("请求内容类型不受支持"));
    }

    @Test
    void notAcceptableIs406WithoutBody() throws Exception {
        // 406 不能回 JSON 体：客户端已声明不接受该形态，再写体会二次触发协商失败
        this.mockMvc.perform(get("/api/test/only-text").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
    }

    // ── 404 族 ───────────────────────────────────────────────────────────

    @Test
    void noHandlerFoundIs404() {
        ResponseEntity<?> response = this.handler.handleWebFamily(
                new NoHandlerFoundException("GET", "/api/nope", new HttpHeaders()), request(), null);

        assertEquals(404, response.getStatusCode().value());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, body.getCode());
        assertEquals("请求的资源不存在", body.getMessage());
    }

    @Test
    void noResourceFoundIs404() {
        ResponseEntity<?> response = this.handler.handleWebFamily(
                new NoResourceFoundException(HttpMethod.GET, "missing.txt"), request(), null);

        assertEquals(404, response.getStatusCode().value());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, body.getCode());
    }

    // ── ResponseStatusException：状态与 reason 透出 ───────────────────────

    @Test
    void responseStatusExceptionKeepsItsStatusAndReason() throws Exception {
        this.mockMvc.perform(get("/api/test/conflict-status"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("已经存在"));
    }

    @Test
    void responseStatusExceptionWithoutReasonFallsBackToStatusText() {
        ResponseEntity<?> response = this.handler.handleWebFamily(
                new ResponseStatusException(HttpStatus.NOT_FOUND), request(), null);

        assertEquals(404, response.getStatusCode().value());
        ApiResponse<?> body = assertInstanceOf(ApiResponse.class, response.getBody());
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, body.getCode());
        assertEquals("请求的资源不存在", body.getMessage());
    }

    // ── locale / 形态 ────────────────────────────────────────────────────

    @Test
    void englishLocaleFallsBackToMessagesEn() throws Exception {
        this.mockMvc.perform(get("/api/test/need-param").header("Accept-Language", "en-US"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST))
                .andExpect(jsonPath("$.message").value("Bad request"));
    }

    @Test
    void problemJsonNegotiationAppliesToTheFamily() throws Exception {
        this.mockMvc.perform(get("/api/test/need-int").param("page", "abc")
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BAD_REQUEST));

        ResponseEntity<?> response = this.handler.handleWebFamily(
                new NoHandlerFoundException("GET", "/api/nope", new HttpHeaders()), problemRequest(), null);
        ProblemDetail problem = assertInstanceOf(ProblemDetail.class, response.getBody());
        assertEquals("urn:cfg:error:" + ErrorCodes.RESOURCE_NOT_FOUND, problem.getType().toString());
    }

    /** 非表内异常仍走兜底（500）——映射表不得把未知异常吞成 4xx。 */
    @Test
    void unknownExceptionStillToGeneralFallback() {
        ResponseEntity<?> response = this.handler.handleGeneral(new IllegalStateException("数据库炸了"), request(),
                null);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(ErrorCodes.INTERNAL_ERROR, assertInstanceOf(ApiResponse.class, response.getBody()).getCode());
    }

    /** 表内父族的服务端子类必须显式覆盖回 500（父族 4xx 不得吃掉服务端缺陷）。 */
    @Test
    void serverSideSubclassesOfClientFamiliesStay500() throws Exception {
        var method = ProbeController.class.getDeclaredMethod("needParam", String.class);
        ResponseEntity<?> missingPathVariable = this.handler.handleWebFamily(
                new org.springframework.web.bind.MissingPathVariableException("token",
                        new org.springframework.core.MethodParameter(method, 0)),
                request(), null);
        assertEquals(500, missingPathVariable.getStatusCode().value());
        assertEquals(ErrorCodes.INTERNAL_ERROR,
                assertInstanceOf(ApiResponse.class, missingPathVariable.getBody()).getCode());

        ResponseEntity<?> conversion = this.handler.handleWebFamily(
                new org.springframework.beans.ConversionNotSupportedException("abc", Integer.class, null),
                request(), null);
        assertEquals(500, conversion.getStatusCode().value());
        assertEquals(ErrorCodes.INTERNAL_ERROR,
                assertInstanceOf(ApiResponse.class, conversion.getBody()).getCode());
    }

    @Test
    void streamingResponseIsNotRewrittenByTheFamily() {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<?> handled = this.handler.handleWebFamily(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "已开流"), request(), response);

        assertEquals(500, handled.getStatusCode().value());
        assertNull(handled.getBody());
    }

    // ── 装配 ────────────────────────────────────────────────────────────

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

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/test/need-param");
    }

    private static MockHttpServletRequest problemRequest() {
        MockHttpServletRequest request = request();
        request.addHeader("Accept", MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        return request;
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/test/need-param")
        String needParam(@RequestParam String token) {
            return token;
        }

        @GetMapping("/api/test/need-int")
        int needInt(@RequestParam int page) {
            return page;
        }

        @GetMapping("/api/test/get-only")
        String getOnly() {
            return "ok";
        }

        @PostMapping(value = "/api/test/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> jsonOnly(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping(value = "/api/test/only-text", produces = MediaType.TEXT_PLAIN_VALUE)
        String onlyText() {
            return "ok";
        }

        @GetMapping("/api/test/conflict-status")
        String conflictStatus() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已经存在");
        }
    }
}
