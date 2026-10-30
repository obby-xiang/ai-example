package com.example.configmgr.config;

import com.example.configmgr.ai.tenant.TenantIdentityFilter;
import com.example.configmgr.ai.web.AiController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * CORS（I5）：必须<b>显式注册且排在身份过滤器之前</b>。
     *
     * <p>
     * 裸 {@code Filter} Bean 的注册顺序是 {@code LOWEST_PRECEDENCE}，排在身份过滤器之后 ——
     * 身份拒绝的 400 就不带 {@code Access-Control-Allow-Origin}，浏览器只报 network error。
     * 这里改用 {@link FilterRegistrationBean} 固定顺序（{@code HIGHEST_PRECEDENCE + 5}），
     * 让 CORS 头在业务链之前写好，拒绝响应也能被前端读到错误码。
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = corsConfiguration();

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.setName("corsFilter");
        return registration;
    }

    /**
     * CORS 配置本体（包内可见：用例要断言"自定义响应头可被前端读到"）。
     *
     * <p><b>exposedHeaders（C-1）</b>：浏览器只把 {@code Access-Control-Expose-Headers} 里列出的
     * 自定义响应头交给 JS。reattach 用 {@code archive-trimmed/archive-from/archive-to} 告知
     * "归档缺口"，不加这一项时后端发了、跨源前端 {@code headers.get(...)} 恒为 null
     * （静默降级成"永远没有裁剪"）。前端走 fetch 消费 SSE，因此这里是契约的一部分。
     */
    static CorsConfiguration corsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of(AiController.HEADER_ARCHIVE_TRIMMED, AiController.HEADER_ARCHIVE_FROM,
                AiController.HEADER_ARCHIVE_TO));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    /**
     * 安全响应头（防护批③ B2-9）：{@code /api/*} 上发 CSP <b>Report-Only</b> + nosniff + no-referrer。
     * 页面本体由 Vite 承载、本过滤器覆盖不到 —— 实际生效面与"不做强制模式"的理由见
     * {@link SecurityHeadersFilter} 类注。
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("securityHeadersFilter");
        return registration;
    }

    /**
     * 租户/用户身份边界（2026-10-30）：{@code /api/ai/*} 下的对话数据面读取
     * {@code X-Tenant-Id}/{@code X-User-Id} 并建立上下文；模型配置/健康/工具/身份目录除外
     * （见 {@code TenantIdentityFilter#shouldNotFilter}）。排在安全响应头之后。
     */
    @Bean
    public FilterRegistrationBean<TenantIdentityFilter> tenantIdentityFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<TenantIdentityFilter> registration =
                new FilterRegistrationBean<>(new TenantIdentityFilter(objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("tenantIdentityFilter");
        return registration;
    }
}
