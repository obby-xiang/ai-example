package com.example.spike.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 轮次执行器：<b>禁用虚拟线程（DC-12）</b>——挂起由专用有界平台线程池承载，
 * 池容量即挂起并发上限，超限**快速失败**（不无限排队占用连接）。
 *
 * <p>本类只影响"谁来承载阻塞"，与 SP-02 的续跑协议无关（挂起态在 Redis，不在承载线程里）。
 */
@Configuration
public class ExecutorConfig {

	@Bean(name = "sp02Executor", destroyMethod = "shutdown")
	public ExecutorService sp02Executor(SpikeProperties properties) {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(0, properties.getSuspendPoolSize(), 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), new java.util.concurrent.ThreadFactory() {
					private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();

					@Override
					public Thread newThread(Runnable runnable) {
						Thread thread = new Thread(runnable, "sp02-run-" + this.seq.incrementAndGet());
						thread.setDaemon(true);
						return thread;
					}
				}, new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

}
