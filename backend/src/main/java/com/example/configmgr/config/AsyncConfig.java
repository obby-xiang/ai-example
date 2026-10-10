package com.example.configmgr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务 {@code @Async}（作业执行）线程模型。
 *
 * <h2>N3 裁决执行（DC-12：禁用虚拟线程是<b>全局</b>的）</h2>
 * 基座原为 {@code spring.threads.virtual.enabled=true} + 本类返回 {@code null}
 * （= 用 Spring Boot 自动建的虚拟线程执行器）：
 * <ul>
 * <li>虚拟线程与 {@code synchronized}/ThreadLocal 的 pinning、线程数无界，
 * 与"专用有界平台线程池承担长挂起"的线程模型冲突（ADR-2 重审 N1 的容量联动无从谈起）；</li>
 * <li>配置已翻为 {@code false}，此时 Spring Boot 的自动兜底是 {@code SimpleAsyncTaskExecutor}
 * —— 每任务一线程、无上限、无队列，作业并发一旦上来就是资源失控。</li>
 * </ul>
 * 因此这里显式给出<b>有界平台线程池</b>作为 {@code @Async} 执行器：
 * 容量与队列都有界、饱和即拒绝（{@code AbortPolicy}，拒绝发生在"事务提交后回调"里，
 * 由 {@code JobService} 的调用链如实冒泡，不静默丢作业）。
 *
 * <h2>为什么池大小是这几个数</h2>
 * 作业是"批量写库 + 逐行进度"的长任务（{@code app.job.batch-size}/{@code demo-batch-delay-ms}），
 * 并发量远小于 AI 挂起；4 条核心线程 / 16 条上限 + 200 队列足以覆盖
 * "一个任务中心点几下一键导出"的真实并发，同时把"无限线程"的可能性彻底关掉。
 * 该取值与 AI 侧 {@code app.ai.suspend.pool-size} 相互独立（两套负载不互相抢占）。
 *
 * <p><b>取值依据补充（2026-10-10 数字规格清点裁决④：值不动、补依据）</b>：业界依据 §7
 * 「数据库连接池（HikariCP）」明确官方反对"按并发数放大池子"（原文："too many connections have
 * a clear and demonstrable negative impact on performance"），内网小用户量单实例下默认值即可用；
 * 同节 §12「任务看门狗 / 僵尸任务判定超时」的横向对照（Quartz 60s / Hangfire 30min / Celery 1h /
 * K8s {@code periodSeconds} 10s × {@code failureThreshold} 3 = 约 30s）说明本项目这类内部批处理的
 * 并发量级远低于需要放大池子的规模。故本类四个数（core 4 / max 16 / queue 200 / 关停等待 30s）
 * <b>维持现值</b>：<b>本值不是新值</b>，本苞只补依据注释，未改任何数值。
 *
 * <p><b>改本值须连带看哪里</b>：{@code corePoolSize} / {@code maxPoolSize} / {@code queueCapacity}
 * 是一套（放大量级会同时抬高对数据库连接的需求，见下条"已知张力"）；{@code awaitTerminationSeconds}
 * 与 {@code waitForTasksToCompleteOnShutdown} 配套，改它等于改"关停时最长阻塞时间"（影响滚动发布与
 * 停机窗口）；另须与 AI 侧 {@code app.ai.suspend.pool-size} 一并复核（两套池共享机器但不共享线程）。
 *
 * <p><b>已知张力（如实登记，不在本批改动面）</b>：本池上限 16 <b>大于</b> Hikari 的连接上限 10
 * （见 {@code application.yml} 的 {@code spring.datasource.hikari} 注释）—— 16 条作业线程中最多
 * 10 条能同时持有连接，其余在 Hikari {@code connectionTimeout} 内排队。内网小并发下接受；若作业
 * 并发实测逼近上限，这两个数须一并重审（等待治理排查已登记为 R-68 同面）。
 *
 * <p>
 * {@code @EnableAsync} 由主类 {@code ConfigMgrApplication} 开启，这里只提供执行器。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** 业务作业执行器 bean 名（{@code @Async} 默认按类型找 {@link Executor}）。 */
    @Bean(name = "applicationTaskExecutor", destroyMethod = "shutdown")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("job-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
