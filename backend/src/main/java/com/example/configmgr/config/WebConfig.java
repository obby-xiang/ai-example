package com.example.configmgr.config;

import com.example.configmgr.ai.tenant.TenantIdentityFilter;
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

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
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
