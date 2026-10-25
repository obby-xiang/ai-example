package com.example.configmgr.health;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 就绪探测执行器的装配（{@code app.health.readiness.*}）。
 *
 * <p>执行器作为独立 bean（而不是在 {@link ReadinessService} 里 new）有两个好处：
 * 生命周期由 Spring 管（{@code destroyMethod=close} 关闭线程池），且超时/容量能单测
 * （{@link ReadinessProbeRunner} 是普通 final 类，不依赖容器）。
 */
@Configuration
class ReadinessRunnerConfig {

	@Bean(destroyMethod = "close")
	ReadinessProbeRunner readinessProbeRunner(ReadinessProperties properties) {
		return new ReadinessProbeRunner(properties.getProbeThreads(), properties.getProbeQueueCapacity(),
				properties.getProbeTimeout());
	}

}
