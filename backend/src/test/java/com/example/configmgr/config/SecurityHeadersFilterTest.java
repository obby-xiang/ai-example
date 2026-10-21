package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全响应头（防护批② B2-T16）：CSP 只发 <b>Report-Only</b>，并带 nosniff / no-referrer。
 *
 * <p>kill：把 {@code Content-Security-Policy-Report-Only} 写成强制 {@code Content-Security-Policy}
 * ⇒ 第一条断言变红（强制模式会破 Vite 页面的运行时样式注入与 HMR，用户裁决第 4 条明确不做）。
 */
class SecurityHeadersFilterTest {

	@Test
	void emitsReportOnlyCspAndHardeningHeaders() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

		assertThat(response.getHeader("Content-Security-Policy-Report-Only"))
				.isEqualTo(SecurityHeadersFilter.CSP_REPORT_ONLY);
		assertThat(response.getHeader("Content-Security-Policy"))
				.as("强制模式本批不做（挂账）").isNull();
		assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
	}

	@Test
	void cspKeepsTheDirectivesTheCurrentFrontendNeeds() {
		String csp = SecurityHeadersFilter.CSP_REPORT_ONLY;

		assertThat(csp).contains("default-src 'self'");
		assertThat(csp).contains("img-src 'self' data:");
		assertThat(csp).contains("style-src 'self' 'unsafe-inline'");
		assertThat(csp).contains("connect-src 'self' ws:");
		assertThat(csp).contains("object-src 'none'");
	}

}
