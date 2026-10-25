package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可信 XFF 解析（多实例专项）：
 *
 * <ul>
 * <li>默认（无信任网段）**不读** XFF —— 与改造前逐字一致，客户端伪造头不生效；</li>
 * <li>只有 RemoteAddr 落在可信网段内才解析链，且从右往左取第一个不可信地址（左端可被客户端伪造）；</li>
 * <li>链内出现非 IP 字面量 ⇒ 整条头作废（回 RemoteAddr），不按自述字符串建限流桶；</li>
 * <li>IPv4 / IPv6 / IPv4-mapped / {@code ip:port} 形态都要能落到同一个可比较的地址文本。</li>
 * </ul>
 */
class ClientIpResolverTest {

	private static final String LB = "10.0.0.5";

	@Test
	void noTrustedProxiesMeansRemoteAddrOnly() {
		ClientIpResolver resolver = resolver(List.of());
		MockHttpServletRequest request = request(LB, "203.0.113.7");

		assertThat(resolver.resolve(request)).isEqualTo(LB);
		assertThat(resolver.trustedProxyCount()).isZero();
	}

	@Test
	void trustedProxyUsesForwardedFor() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
		MockHttpServletRequest request = request(LB, "203.0.113.7");

		assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
	}

	@Test
	void untrustedRemoteCannotSpoofForwardedFor() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
		MockHttpServletRequest request = request("198.51.100.9", "203.0.113.7");

		assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
	}

	@Test
	void walksChainRightToLeftSkippingTrustedHops() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

		// 客户端自填了最左的 1.1.1.1；LB 追加了真实来源 203.0.113.7 → 必须取后者
		assertThat(resolver.resolve(request(LB, "1.1.1.1, 203.0.113.7"))).isEqualTo("203.0.113.7");
		// 两级可信代理（10.x 内网 LB）→ 继续左跳取第一个不可信
		assertThat(resolver.resolve(request(LB, "203.0.113.7, 10.9.9.9"))).isEqualTo("203.0.113.7");
	}

	@Test
	void allTrustedChainFallsBackToLeftmost() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8", "192.168.0.0/16"));

		assertThat(resolver.resolve(request(LB, "10.1.1.1, 192.168.1.1"))).isEqualTo("10.1.1.1");
	}

	@Test
	void invalidTokenInvalidatesWholeHeader() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

		assertThat(resolver.resolve(request(LB, "evil.example.com"))).isEqualTo(LB);
		assertThat(resolver.resolve(request(LB, "203.0.113.7, not-an-ip"))).isEqualTo(LB);
	}

	@Test
	void malformedIpv6TokenNeverTriggersNameResolution() throws Exception {
		try (org.mockito.MockedStatic<java.net.InetAddress> mocked =
				org.mockito.Mockito.mockStatic(java.net.InetAddress.class)) {
			mocked.when(() -> java.net.InetAddress.getByName(org.mockito.ArgumentMatchers.anyString()))
					.thenThrow(new java.net.UnknownHostException("不应触发名字解析"));
			assertThat(ClientIpResolver.ipBytes("2001:db8::zz")).isNull();
			mocked.verify(() -> java.net.InetAddress.getByName("2001:db8::zz"),
					org.mockito.Mockito.never());
		}
	}

	@Test
	void missingHeaderFallsBackToRemote() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

		assertThat(resolver.resolve(request(LB, null))).isEqualTo(LB);
	}

	@Test
	void ipv4WithPortInHeaderIsNormalized() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

		assertThat(resolver.resolve(request(LB, "203.0.113.7:4431"))).isEqualTo("203.0.113.7");
	}

	@Test
	void ipv4MappedRemoteMatchesIpv4Cidr() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

		assertThat(resolver.resolve(request("::ffff:10.0.0.5", "203.0.113.7"))).isEqualTo("203.0.113.7");
	}

	@Test
	void ipv6CidrIsSupported() {
		ClientIpResolver resolver = resolver(List.of("fd00::/8"));

		assertThat(resolver.resolve(request("fd00::5", "2001:db8::1"))).isEqualTo("2001:db8::1");
	}

	@Test
	void singleAddressIsTreatedAsHostRoute() {
		ClientIpResolver resolver = resolver(List.of("10.0.0.5"));

		assertThat(resolver.resolve(request(LB, "203.0.113.7"))).isEqualTo("203.0.113.7");
		assertThat(resolver.resolve(request("10.0.0.6", "203.0.113.7"))).isEqualTo("10.0.0.6");
	}

	@Test
	void malformedTrustedProxyFailsFast() {
		assertThatThrownBy(() -> resolver(List.of("lb.internal")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("trusted-proxies");
		assertThatThrownBy(() -> resolver(List.of("10.0.0.0/33")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void blankOrNullRequestIsUnknown() {
		ClientIpResolver resolver = resolver(List.of());

		assertThat(resolver.resolve(null)).isEqualTo(ClientIpResolver.UNKNOWN);
		assertThat(resolver.resolve(request("  ", null))).isEqualTo(ClientIpResolver.UNKNOWN);
	}

	private static ClientIpResolver resolver(List<String> trusted) {
		ClientIpProperties properties = new ClientIpProperties();
		properties.setTrustedProxies(trusted);
		return new ClientIpResolver(properties);
	}

	private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		if (forwardedFor != null) {
			request.addHeader(ClientIpResolver.X_FORWARDED_FOR, forwardedFor);
		}
		return request;
	}

}
