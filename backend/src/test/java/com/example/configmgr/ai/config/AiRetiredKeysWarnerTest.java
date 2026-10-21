package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退役键的启动告警（超时面治理批① 第 7 项）：命中即 WARN 并点名新键，未命中零噪音。
 */
@ExtendWith(OutputCaptureExtension.class)
class AiRetiredKeysWarnerTest {

	@Test
	void everyRetiredKeyIsNamedTogetherWithItsReplacement(CapturedOutput output) {
		MockEnvironment env = new MockEnvironment()
			.withProperty("app.ai.hitl.confirm-timeout", "5m")
			.withProperty("app.ai.session-ttl-minutes", "360")
			.withProperty("app.ai.max-iterations", "12")
			.withProperty("app.ai.thinking-disabled", "true");

		new AiRetiredKeysWarner(env).warnOnRetiredKeys();

		assertThat(output).contains("app.ai.hitl.confirm-timeout 已退役")
			.contains("app.ai.hitl.frontend-tool-timeout")
			.contains("app.ai.session-ttl-minutes 已退役")
			.contains("改用 app.ai.session-ttl")
			.contains("app.ai.max-iterations 已退役")
			.contains("app.ai.thinking-disabled 已退役")
			.contains("启动期发现 4 个已退役的 app.ai 配置键");
	}

	@Test
	void singleHitStillNamesTheNewKey(CapturedOutput output) {
		MockEnvironment env = new MockEnvironment().withProperty("app.ai.session-ttl-minutes", "60");

		new AiRetiredKeysWarner(env).warnOnRetiredKeys();

		assertThat(output).contains("app.ai.session-ttl-minutes 已退役").contains("当前配置值 = 60")
			.contains("启动期发现 1 个已退役");
	}

	@Test
	void cleanConfigurationProducesNoWarn(CapturedOutput output) {
		MockEnvironment env = new MockEnvironment().withProperty("app.ai.session-ttl", "24h");

		new AiRetiredKeysWarner(env).warnOnRetiredKeys();

		assertThat(output).doesNotContain("已退役");
	}

}
