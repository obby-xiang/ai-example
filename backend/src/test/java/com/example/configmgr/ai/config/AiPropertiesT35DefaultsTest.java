package com.example.configmgr.ai.config;

import com.example.configmgr.ai.run.RunStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-5 定值回灌（2026-10-09）在 {@link AiProperties} 上的锚定，另含 2026-10-20 用户裁决后的两条：
 * <ol>
 * <li><b>默认值即定值</b>：投递池 8（原 4）、实时队列容量 256（维持）；</li>
 * <li><b>会话 TTL 24h</b>（裁决第 4 条：6h → 24h、滑动续期、无绝对上限）；</li>
 * <li><b>app.ai.hitl 只剩一个键</b>：{@code frontend-tool-timeout} 120s（即时执行类前端工具的回执兜底）；
 *     确认门键已退役（等人无上限，见 {@link #confirmGateHasNoTimeoutKeyAndFrontendToolTimeoutStays}）。</li>
 * </ol>
 *
 * <p>R-148（B-3）连带删：原「启动期容量联动校验」随本批删除，其 WARN 用例与 appender 一并移除
 * —— 该校验即本条过度设计本体（109 行独立类只为承载一条启动期告警）。
 */
class AiPropertiesT35DefaultsTest {

	@Test
	void defaultsAreTheT35Values() {
		AiProperties defaults = new AiProperties();

		assertThat(defaults.getSse().getDeliveryPoolSize())
				.as("T3-5 定值 2026-10-09：投递池 8 —— 不被饿死 ⟺ 慢连接数 < 池容量")
				.isEqualTo(8);
		assertThat(defaults.getSse().getDeliveryQueueCapacity())
				.as("T3-5 定值 2026-10-09：实时队列容量维持 256")
				.isEqualTo(256)
				.isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);
		assertThat(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY)
				.as("R1 不变量：实时队列容量 < 归档窗口")
				.isLessThan(RunStore.EVENT_WINDOW);
		assertThat(defaults.getSuspend().getPoolSize()).as("挂起专用池维持 20").isEqualTo(20);
	}

	/**
	 * 2026-10-20 用户裁决第 1/2/4 条的机器化落点：
	 * <ul>
	 * <li>会话 TTL = 24h（6h → 24h，滑动续期、无绝对上限）；</li>
	 * <li>确认门没有等待上限 —— 键退役后 {@link AiProperties.Hitl} 只剩前端工具键；</li>
	 * <li>前端工具键仍为 120s（即时执行类前端工具的后端回执兜底；{@code generative_form} 豁免，
	 *     口径在 {@code ConfirmGate#suspendWaitSeconds}）。</li>
	 * </ul>
	 */
	@Test
	void confirmGateHasNoTimeoutKeyAndFrontendToolTimeoutStays() {
		AiProperties defaults = new AiProperties();

		assertThat(defaults.getSessionTtl())
				.as("会话 TTL 24h（裁决第 4 条：滑动续期、整个会话维度、无绝对上限）")
				.isEqualTo(Duration.ofHours(24));
		assertThat(defaults.getHitl().getFrontendToolTimeout())
				.as("前端工具（等机器）默认维持 120s —— 降到 60s 的前置 = 前端折算面专项（P-1 / R-99②）")
				.isEqualTo(Duration.ofSeconds(120));
		assertThat(java.util.Arrays.stream(AiProperties.Hitl.class.getDeclaredFields())
				.map(java.lang.reflect.Field::getName)
				.toList())
				.as("确认门键已退役：hitl 下不得再有 confirmTimeout（等人无上限，裁决第 1 条）")
				.doesNotContain("confirmTimeout");
	}

}
