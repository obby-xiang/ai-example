package com.example.configmgr.ai.gate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用级限流已从产品移除（按当前生产契约，不再恢复 AiRateLimiter）。
 *
 * <p>原 {@code AiRateLimiterTest} 验证的是已删除的会话/IP 限流窗口；这里改为对"不存在"的
 * 显式断言，防止有人把限流组件悄悄加回控制器或配置面。
 */
class AiRateLimiterRemovedTest {

	@Test
	void rateLimiterClassIsNotOnTheClasspath() {
		assertThatThrownBy(() -> Class.forName("com.example.configmgr.ai.gate.AiRateLimiter"))
				.as("应用级限流类已移除")
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void chatControllerHasNoRateLimiterField() {
		assertThat(Arrays.stream(com.example.configmgr.ai.web.AiController.class.getDeclaredFields())
				.map(Field::getName))
				.as("AiController 不得再依赖应用级限流组件")
				.doesNotContain("aiRateLimiter");
	}
}
