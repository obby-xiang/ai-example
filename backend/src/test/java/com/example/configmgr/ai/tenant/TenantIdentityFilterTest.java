package com.example.configmgr.ai.tenant;

import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP 身份边界（{@link TenantIdentityFilter}）的单点契约。
 *
 * <p>
 * 三件事必须同时成立：<b>缺身份直接拒绝</b>（不落任何默认识别）、<b>组合要校验</b>
 * （目录外的拼装不放行）、<b>合法身份只在请求期间存在</b>（请求结束必须清理，不留在线程上）。
 * 模型配置与业务接口不纳入本边界。
 */
class TenantIdentityFilterTest {

	private static final String TENANT_HEADER = "X-Tenant-Id";

	private static final String USER_HEADER = "X-User-Id";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void missingIdentityHeadersFailClosedBeforeTheChain() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		FilterChain chain = (req, res) -> chainCalled.set(true);

		new TenantIdentityFilter(this.mapper).doFilter(request, response, chain);

		assertThat(chainCalled).as("缺身份不得进入业务链（fail-closed）").isFalse();
		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode body = this.mapper.readTree(response.getContentAsByteArray());
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("code").asText()).isEqualTo(ErrorCodes.TENANT_IDENTITY_REQUIRED);
		assertThatThrownBy(RequestContextUtils::getTenantId).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void systemUserAndNonPositiveTenantAreRejectedOverHttp() throws Exception {
		new TenantIdentityFilter(this.mapper)
			.doFilter(request("POST", "/api/ai/chat", "1", "-1"), new MockHttpServletResponse(), failIfCalled());

		MockHttpServletResponse response = new MockHttpServletResponse();
		new TenantIdentityFilter(this.mapper)
			.doFilter(request("POST", "/api/ai/chat", "0", "101"), response, failIfCalled());

		assertThat(response.getStatus()).as("tenantId 必须是正数").isEqualTo(400);
	}

	@Test
	void unknownTenantUserCombinationIsRejected() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		FilterChain chain = (req, res) -> chainCalled.set(true);

		new TenantIdentityFilter(this.mapper).doFilter(request("GET", "/api/ai/runs", "1", "201"), response, chain);

		assertThat(chainCalled).isFalse();
		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode body = this.mapper.readTree(response.getContentAsByteArray());
		assertThat(body.path("code").asText()).isEqualTo(ErrorCodes.TENANT_IDENTITY_UNKNOWN);
	}

	@Test
	void validIdentityIsBoundForTheChainAndClearedAfterwards() throws Exception {
		MockHttpServletRequest request = request("GET", "/api/ai/runs", "2", "202");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<Long> tenantSeenByChain = new AtomicReference<>();
		AtomicReference<Long> userSeenByChain = new AtomicReference<>();
		FilterChain chain = (req, res) -> {
			tenantSeenByChain.set(RequestContextUtils.getTenantId());
			userSeenByChain.set(RequestContextUtils.getUserId());
		};

		new TenantIdentityFilter(this.mapper).doFilter(request, response, chain);

		assertThat(tenantSeenByChain.get()).isEqualTo(2L);
		assertThat(userSeenByChain.get()).isEqualTo(202L);
		assertThatThrownBy(RequestContextUtils::getTenantId).as("请求结束必须清理身份").isInstanceOf(IllegalStateException.class);
	}

	@Test
	void modelConfigEndpointsAreOutsideTheTenantBoundary() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/providers");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean chainCalled = new AtomicBoolean(false);
		FilterChain chain = (req, res) -> chainCalled.set(true);

		new TenantIdentityFilter(this.mapper).doFilter(request, response, chain);

		assertThat(chainCalled).as("模型配置不纳入租户边界").isTrue();
		assertThat(RequestContextUtils.currentOrNull()).isNull();
	}

	@Test
	void onlyConfigSubtreesAndExactNonDataPathsEscapeTheBoundary() throws Exception {
		MockHttpServletResponse toolsSubpath = new MockHttpServletResponse();
		AtomicBoolean toolsSubpathChain = new AtomicBoolean(false);
		new TenantIdentityFilter(this.mapper).doFilter(new MockHttpServletRequest("GET", "/api/ai/tools/secret"),
				toolsSubpath, (req, res) -> toolsSubpathChain.set(true));

		assertThat(toolsSubpathChain).as("排除项只认精确路径：新增 /api/ai/tools/... 不得静默逃逸").isFalse();
		assertThat(toolsSubpath.getStatus()).isEqualTo(400);

		MockHttpServletResponse root = new MockHttpServletResponse();
		new TenantIdentityFilter(this.mapper).doFilter(new MockHttpServletRequest("GET", "/api/ai"), root,
				failIfCalled());

		assertThat(root.getStatus()).as("/api/ai 本身也明确纳入边界（缺头 = 拒绝，不是静默跳过）").isEqualTo(400);

		AtomicBoolean configSubtreeChain = new AtomicBoolean(false);
		new TenantIdentityFilter(this.mapper).doFilter(new MockHttpServletRequest("GET", "/api/ai/providers/1/models"),
				new MockHttpServletResponse(), (req, res) -> configSubtreeChain.set(true));

		assertThat(configSubtreeChain).as("模型配置子树（providers/models/settings）仍不纳入").isTrue();
	}

	private static MockHttpServletRequest request(String method, String path, String tenantId, String userId) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.addHeader(TENANT_HEADER, tenantId);
		request.addHeader(USER_HEADER, userId);
		return request;
	}

	private static FilterChain failIfCalled() {
		return (req, res) -> {
			throw new AssertionError("非法身份不得进入业务链");
		};
	}

}
