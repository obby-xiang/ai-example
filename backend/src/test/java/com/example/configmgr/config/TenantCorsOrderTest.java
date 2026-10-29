package com.example.configmgr.config;

import com.example.configmgr.ai.tenant.TenantIdentityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 与身份过滤器的<b>顺序契约</b>（I5）。
 *
 * <p>
 * 身份过滤器会对缺头/非法身份的请求直接 400。如果 CORS 过滤器排在它之后
 * （裸 {@code Filter} Bean 的默认注册顺序是 {@code LOWEST_PRECEDENCE}），那个 400
 * <b>没有 {@code Access-Control-Allow-Origin}</b>：浏览器按 CORS 失败处理，前端只能看到
 * network error，读不到 {@code TENANT_IDENTITY_REQUIRED}。
 *
 * <p>
 * 本单点同时锁两件事：CORS 注册顺序早于身份过滤器；按该顺序执行时，身份拒绝的响应仍带 CORS 头。
 */
class TenantCorsOrderTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void corsFilterRunsBeforeIdentityFilterAndRejectionsCarryCorsHeaders() throws Exception {
		WebConfig web = new WebConfig();
		FilterRegistrationBean<CorsFilter> cors = web.corsFilter();
		FilterRegistrationBean<TenantIdentityFilter> identity = web.tenantIdentityFilter(this.mapper);

		assertThat(cors.getOrder()).as("CORS 必须早于身份过滤器").isLessThan(identity.getOrder());

		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");
		request.addHeader("Origin", "http://localhost:5173");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);

		cors.getFilter()
			.doFilter(request, response, (req, res) -> identity.getFilter()
				.doFilter(req, res, (req2, res2) -> chainCalled.set(true)));

		assertThat(chainCalled).as("身份拒绝不得进入业务链").isFalse();
		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader("Access-Control-Allow-Origin"))
			.as("身份拒绝的 400 必须带 CORS 头，浏览器才能读到错误码")
			.isEqualTo("http://localhost:5173");
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
	}

}
