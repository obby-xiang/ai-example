package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B19（SSRF 闸）：URL 白名单口径 —— 仅 http/https、禁内嵌凭据、默认禁私网/回环/云元数据；
 * {@code app.ai.config.allow-private-endpoints=true} 才放行；<b>种子路径不受闸</b>。
 *
 * <p>
 * <b>证红</b>：去掉 {@link AiEndpointGuard} 里的私网判定（或把它改成"总是放行"）⇒
 * {@link #privateTargetsAreBlockedByDefault()} 全组变红；把 {@code trusted} 判定反过来 ⇒
 * {@link #seededBaseUrlBypassesOnlyThePrivateNetCheck()} 红。
 */
class AiEndpointGuardTest {

	private static AiProperties properties(boolean allowPrivate) {
		AiProperties properties = new AiProperties();
		properties.getConfig().setAllowPrivateEndpoints(allowPrivate);
		return properties;
	}

	private static AiEndpointGuard guard(boolean allowPrivate) {
		return new AiEndpointGuard(properties(allowPrivate));
	}

	@Test
	void publicEndpointsPassAndAreNormalised() {
		AiEndpointGuard guard = guard(false);
		assertThat(guard.checkForWrite(" https://api.deepseek.com/ ")).isEqualTo("https://api.deepseek.com");
		assertThat(guard.checkForWrite("https://gw.example.com/v1")).isEqualTo("https://gw.example.com/v1");
		assertThat(guard.checkForWrite("http://api.example.com:8080")).isEqualTo("http://api.example.com:8080");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://127.0.0.1:18399",
			"http://127.5.6.7",
			"http://localhost:8080",
			"http://10.1.2.3/v1",
			"http://172.16.0.9",
			"http://192.168.1.5:1234",
			"http://169.254.169.254/latest/meta-data",
			"http://0.0.0.0:80",
			"http://[::1]:8080",
			"http://100.64.1.1",
			"http://metadata.google.internal/computeMetadata/v1" })
	void privateTargetsAreBlockedByDefault(String url) {
		assertThatThrownBy(() -> guard(false).checkForWrite(url))
			.isInstanceOf(AiEndpointGuard.GuardException.class)
			.extracting(ex -> ((AiEndpointGuard.GuardException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT);
	}

	@Test
	void switchAllowsPrivateEndpoints() {
		assertThat(guard(true).checkForWrite("http://127.0.0.1:18399")).isEqualTo("http://127.0.0.1:18399");
		assertThat(guard(true).checkForWrite("http://192.168.1.5:1234/v1")).isEqualTo("http://192.168.1.5:1234/v1");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   ",
			"api.deepseek.com",
			"//api.deepseek.com",
			"ftp://api.deepseek.com",
			"file:///etc/passwd",
			"https://user:pass@api.example.com",
			"https://api.example.com:notaport",
			"http:///" })
	void malformedOrCredentialedUrlsAreRejectedEvenWhenPrivateIsAllowed(String url) {
		assertThatThrownBy(() -> guard(true).checkForWrite(url))
			.isInstanceOf(AiEndpointGuard.GuardException.class)
			.extracting(ex -> ((AiEndpointGuard.GuardException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_INVALID_BASE_URL);
	}

	@Test
	void seededBaseUrlBypassesOnlyThePrivateNetCheck() {
		// 种子行（env 是受信来源）⇒ 私网放行：CI 的桩就在 127.0.0.1:18399
		assertThat(guard(false).checkForOutbound("http://127.0.0.1:18399", true))
			.isEqualTo("http://127.0.0.1:18399");
		// 但 scheme/凭据两条仍然要过（受信不等于可以 file:// 或带凭据）
		assertThatThrownBy(() -> guard(false).checkForOutbound("file:///etc/passwd", true))
			.isInstanceOf(AiEndpointGuard.GuardException.class)
			.extracting(ex -> ((AiEndpointGuard.GuardException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_INVALID_BASE_URL);
		assertThatThrownBy(() -> guard(false).checkForOutbound("https://user:pass@api.example.com", true))
			.isInstanceOf(AiEndpointGuard.GuardException.class);
		// 非受信来源仍走全闸
		assertThatThrownBy(() -> guard(false).checkForOutbound("http://10.0.0.1", false))
			.isInstanceOf(AiEndpointGuard.GuardException.class)
			.extracting(ex -> ((AiEndpointGuard.GuardException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT);
	}

	@Test
	void urlLongerThanTheColumnIsRejected() {
		String longUrl = "https://api.example.com/" + "a".repeat(600);
		assertThatThrownBy(() -> guard(false).checkForWrite(longUrl))
			.isInstanceOf(AiEndpointGuard.GuardException.class)
			.extracting(ex -> ((AiEndpointGuard.GuardException) ex).getCode())
			.isEqualTo(AiEndpointGuard.CODE_INVALID_BASE_URL);
	}

}
