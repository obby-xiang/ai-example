package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 复审 M1：{@code app.ai.limit.*} 的<b>启动期正数守卫</b>（仿 {@code app.ai.sse.write-timeout} 的 fail-fast 姿态）。
 *
 * <h2>为什么必须 fail-fast 而不是"静默降级"</h2>
 * <ul>
 * <li>{@code session-per-minute / ip-per-minute ≤ 0} ⇒ <b>每一条</b> {@code /chat} 都被 429：
 * 不是"关掉限流"，而是"关掉对话"。它没有性能降级的解释空间，只会让配置面与运行面说两套话；</li>
 * <li>{@code window ≤ 0} ⇒ Lua 里 {@code PEXPIRE 0} 会立刻删键，计数永远归零 ⇒ <b>限流静默失效</b>
 * （比报错更危险：运维以为有闸，实际没有）。</li>
 * </ul>
 * 故两者都在启动期直接失败，且错误信息点名具体键与当前值（排障一眼可见）。
 *
 * <p>读的是<b>绑定后</b>的值（绑定先于 {@code @PostConstruct}），与
 * {@link AiProperties#verifySseWriteTimeout()} 同一姿态；方法包级可见，测试直调。
 */
class AiPropertiesLimitGuardTest {

	@Test
	void defaultLimitsPassTheGuard() {
		assertThatCode(() -> new AiProperties().verifyRateLimit()).doesNotThrowAnyException();
	}

	@Test
	void nonPositiveSessionLimitFailsFastAndNamesTheKey() {
		AiProperties props = new AiProperties();
		props.getLimit().setSessionPerMinute(0);

		assertThatThrownBy(props::verifyRateLimit)
				.as("会话额度 0 ⇒ 每条 /chat 都 429（等于关掉对话），必须启动失败")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.ai.limit.session-per-minute");
	}

	@Test
	void negativeIpLimitFailsFastAndNamesTheKey() {
		AiProperties props = new AiProperties();
		props.getLimit().setIpPerMinute(-1);

		assertThatThrownBy(props::verifyRateLimit)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.ai.limit.ip-per-minute");
	}

	@Test
	void nonPositiveWindowFailsFastAndNamesTheKey() {
		AiProperties zero = new AiProperties();
		zero.getLimit().setWindow(Duration.ZERO);
		assertThatThrownBy(zero::verifyRateLimit)
				.as("窗口 0 ⇒ PEXPIRE 0 立刻删键，限流静默失效")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.ai.limit.window");

		AiProperties negative = new AiProperties();
		negative.getLimit().setWindow(Duration.ofSeconds(-30));
		assertThatThrownBy(negative::verifyRateLimit)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.ai.limit.window");
	}

}
