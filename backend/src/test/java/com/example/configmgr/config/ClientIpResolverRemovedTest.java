package com.example.configmgr.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 客户端 IP 解析器已从产品移除（随应用级限流一起清退）。
 *
 * <p>原 {@code ClientIpResolverTest} 校验的是已删除的 X-Forwarded-For 解析与 CIDR 白名单；
 * 这里改为对"不存在"的显式断言，保留移除契约而不恢复旧功能。
 */
class ClientIpResolverRemovedTest {

	@Test
	void clientIpResolverClassIsNotOnTheClasspath() {
		assertThatThrownBy(() -> Class.forName("com.example.configmgr.config.ClientIpResolver"))
				.as("客户端 IP 解析器已移除")
				.isInstanceOf(ClassNotFoundException.class);
	}
}
