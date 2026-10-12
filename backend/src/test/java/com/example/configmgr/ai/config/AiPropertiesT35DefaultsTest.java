package com.example.configmgr.ai.config;

import com.example.configmgr.ai.run.RunStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-5 定值回灌（2026-10-09）在 {@link AiProperties} 上的锚定，另含裁决③拆键（2026-10-10）的两条：
 * <ol>
 * <li><b>默认值即定值</b>：投递池 8（原 4）、实时队列容量 256（维持）；</li>
 * <li><b>app.ai.hitl 拆键后的默认值与上限关系</b>（确认门 240s / 前端工具 120s；
 * 确认门上限须严格小于 {@code total-budget}，见 {@link #hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget}）。</li>
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
	 * 裁决③（2026-10-10 数字规格清点）拆键后的两个锚：① 两个挂起键的单键默认值；② 确认门可达性的
	 * <b>上限关系不变量</b> —— 确认门上限必须严格小于 {@code total-budget}，否则"等 600s"在
	 * 300s 就被看门狗判 {@code TOTAL_BUDGET} 收口（等待治理 R-99③ 的登记项 N-2）。
	 *
	 * <p>本用例是那条约束的机器化落点：日后若有人把 {@code confirm-timeout} 抬到 600s 而不同批抬高
	 * {@code total-budget}，本断言先红 —— 而不是让配置里留一句"实际可达 300s"的谎报。
	 */
	@Test
	void hitlKeysAreSplitAndConfirmTimeoutStaysWithinTotalBudget() {
		AiProperties defaults = new AiProperties();

		assertThat(defaults.getHitl().getConfirmTimeout())
				.as("确认门（等人）默认 240s —— 过渡取值；600s 待 total-budget 三语义拆分同批")
				.isEqualTo(Duration.ofSeconds(240));
		assertThat(defaults.getHitl().getFrontendToolTimeout())
				.as("前端工具（等机器）默认暂维 120s —— 降到 60s 的前置 = 前端折算面专项（P-1 / R-99②）")
				.isEqualTo(Duration.ofSeconds(120));
		assertThat(defaults.getHitl().getConfirmTimeout())
				.as("上限关系（N-2）：confirm-timeout 必须严格小于 total-budget，否则确认门等不满自己的上限")
				.isLessThan(defaults.getResilience().getTotalBudget());
	}

}
