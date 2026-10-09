package com.example.configmgr.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.run.RunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-5 定值回灌（2026-10-09）在 {@link AiProperties} 上的两条锚定：
 * <ol>
 * <li><b>默认值即定值</b>：投递池 8（原 4）、实时队列容量 256（维持）；</li>
 * <li><b>启动期容量联动校验确实被 Spring 生命周期调用</b>（{@code @PostConstruct}），
 * 且不满足联动式时<b>只 WARN、不 fail-fast</b>（上下文照常 refresh 成功）。</li>
 * </ol>
 */
class AiPropertiesT35DefaultsTest {

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		this.appender = new ListAppender<>();
		this.appender.start();
		((Logger) LoggerFactory.getLogger(AiPoolCapacityBudget.class)).addAppender(this.appender);
	}

	@AfterEach
	void detachAppender() {
		((Logger) LoggerFactory.getLogger(AiPoolCapacityBudget.class)).detachAppender(this.appender);
	}

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

	@Test
	void startupCheckIsWiredAndOnlyWarnsWhenTheBudgetIsExceeded() {
		// 只装配置属性绑定的最小上下文：挂起池抬到 1000 ⇒ 2000 > 10 × CPU 恒不成立
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t35-budget-test",
				Map.of("app.ai.suspend.pool-size", "1000")));
		context.register(PropertiesOnlyConfig.class);

		context.refresh();
		try {
			assertThat(context.getBean(AiProperties.class).getSuspend().getPoolSize())
					.as("绑定值落进 bean（校验读到的是实测绑定值，不是缺省值）")
					.isEqualTo(1000);
			assertThat(warnings())
					.as("@PostConstruct 在绑定后被调用：不满足联动式 ⇒ 恰一条 WARN")
					.hasSize(1);
			assertThat(warnings().get(0).getFormattedMessage()).contains("要求值");
		}
		finally {
			context.close();
		}
	}

	private List<ILoggingEvent> warnings() {
		return this.appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
	}

	/** 最小上下文：只做 {@code app.ai} 的属性绑定（校验的落点就是属性 bean 自身）。 */
	@Configuration
	@EnableConfigurationProperties(AiProperties.class)
	static class PropertiesOnlyConfig {

	}

}
