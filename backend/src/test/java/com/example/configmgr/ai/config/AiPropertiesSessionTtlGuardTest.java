package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code app.ai.session-ttl} 的启动期校验。
 *
 * <p>transcript 用这把 TTL 给展示历史设置过期时间；无效值下 store 会拒写（避免永久键），
 * 若不 fail-fast，表现就是"历史静默不再落盘"。与 {@link AiPropertiesMemoryGuardTest} 同一姿态：
 * 这类配置错没有降级解释空间。
 */
class AiPropertiesSessionTtlGuardTest {

	/** 默认 24h 必须能过闸（防"加了校验把默认值判死"）。 */
	@Test
	void defaultsPass() {
		assertThatCode(new AiProperties()::verifySessionTtl).doesNotThrowAnyException();
	}

	@Test
	void zeroIsRejected() {
		AiProperties properties = new AiProperties();
		properties.setSessionTtl(Duration.ZERO);

		assertThatThrownBy(properties::verifySessionTtl).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("session-ttl");
	}

	@Test
	void negativeIsRejected() {
		AiProperties properties = new AiProperties();
		properties.setSessionTtl(Duration.ofMinutes(-1));

		assertThatThrownBy(properties::verifySessionTtl).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("session-ttl");
	}

}
