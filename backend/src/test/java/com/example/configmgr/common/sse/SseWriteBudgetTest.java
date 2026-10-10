package com.example.configmgr.common.sse;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 包②：SSE 写侧超时预算的机制单测（{@link SseWriteBudget}）。
 *
 * <p>
 * 覆盖五件事：
 * <ul>
 * <li><b>预算真的约束调用线程</b>：写出被卡住时，调用线程按预算返回 {@code TIMED_OUT}
 * （而不是像裸 {@code SseEmitter.send} 那样一直等到容器的 120s 写超时）；</li>
 * <li><b>直执模式与修复前等价</b>：{@link SseWriteBudget#direct()} 在调用线程内写出，
 * 异常原样交回（既有单测路径的确定性不受影响）；</li>
 * <li><b>失败原因原样回传</b>：{@code ExecutionException} 不得改写异常类型 —— 调用方的
 * 日志与判定口径（"客户端断开"）依赖它；</li>
 * <li><b>两条通道取值同源</b>：AI 通道（{@code app.ai.sse.write-timeout}）与任务通道
 * （{@code app.task.sse.write-timeout}）的缺省必须都来自
 * {@link SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}（"取值与 a 对齐"的机器可查锚点）。</li>
 * <li><b>配置守卫在启动期生效</b>：{@code app.task.sse.emitter-timeout} 必须 &gt; 0，
 * 配成 0 时<b>绑定后</b>的启动期校验直接失败（S4-⑨ 收口）；两条通道的
 * {@code app.ai.sse.write-timeout} / {@code app.task.sse.write-timeout} 同款校验对称
 * （0/负值静默回落缺省 15s 的形态予以 fail-fast，S3-1 收口）。</li>
 * </ul>
 */
class SseWriteBudgetTest {

    private static SseWriteBudget.SendTask blocking(CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
        };
    }

    // ── 预算模式：卡住的写出不再拖住调用线程 ─────────────────────────────────

    @Test
    void timedOutWriteReturnsToCallerWithinTheBudget() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "budget-test-writer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            SseWriteBudget budget = SseWriteBudget.on(writer, 200L);
            long before = SseWriteBudget.writeTimeouts();

            long startedAt = System.nanoTime();
            SseWriteBudget.Result result = budget.write(blocking(started, release));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(started.await(1, TimeUnit.SECONDS)).as("写出已开始（卡在写出线程里）").isTrue();
            assertThat(result.outcome()).as("超预算 ⇒ TIMED_OUT").isEqualTo(SseWriteBudget.Outcome.TIMED_OUT);
            assertThat(result.failure()).as("超时不携带写出异常（写出还没结束）").isNull();
            assertThat(elapsedMillis).as("调用线程按预算脱身（不随写出一起无限期挂住）").isLessThan(3_000L);
            assertThat(SseWriteBudget.writeTimeouts()).as("写超时计数 +1（可观测面）").isEqualTo(before + 1);

            release.countDown();
        }
        finally {
            writer.shutdownNow();
        }
    }

    @Test
    void successfulWriteReturnsSent() {
        ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "budget-test-sent");
            thread.setDaemon(true);
            return thread;
        });
        SseWriteBudget budget = SseWriteBudget.on(writer, 1_000L);
        try {
            AtomicReference<String> thread = new AtomicReference<>();
            SseWriteBudget.Result result = budget.write(() -> thread.set(Thread.currentThread().getName()));

            assertThat(result.outcome()).isEqualTo(SseWriteBudget.Outcome.SENT);
            assertThat(thread.get()).as("写出发生在写出池线程（投递线程不被占用）")
                    .isEqualTo("budget-test-sent");
        }
        finally {
            writer.shutdownNow();
        }
    }

    @Test
    void failedWriteCarriesTheOriginalExceptionBack() {
        ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "budget-test-failed");
            thread.setDaemon(true);
            return thread;
        });
        SseWriteBudget budget = SseWriteBudget.on(writer, 1_000L);
        try {
            IOException disconnected = new IOException("客户端断开");
            SseWriteBudget.Result result = budget.write(() -> {
                throw disconnected;
            });

            assertThat(result.outcome()).isEqualTo(SseWriteBudget.Outcome.FAILED);
            assertThat(result.failure()).as("异常实例原样回传（调用方按它记日志/判处置）").isSameAs(disconnected);
        }
        finally {
            writer.shutdownNow();
        }
    }

    // ── 直执模式：与修复前逐字等价（单测/非 Spring 路径） ────────────────────

    @Test
    void directBudgetWritesInlineAndReportsFailureWithoutWrapping() {
        SseWriteBudget budget = SseWriteBudget.direct();
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicBoolean threw = new AtomicBoolean();

        SseWriteBudget.Result sent = budget.write(() -> thread.set(Thread.currentThread().getName()));
        SseWriteBudget.Result failed = budget.write(() -> {
            threw.set(true);
            throw new IllegalStateException("ResponseBodyEmitter has already completed");
        });

        assertThat(budget.budgeted()).as("直执模式不受预算约束").isFalse();
        assertThat(sent.outcome()).isEqualTo(SseWriteBudget.Outcome.SENT);
        assertThat(thread.get()).as("直执 = 调用线程内写出（帧序用例的确定性依赖它）")
                .isEqualTo(Thread.currentThread().getName());
        assertThat(threw).isTrue();
        assertThat(failed.outcome()).isEqualTo(SseWriteBudget.Outcome.FAILED);
        assertThat(failed.failure()).isInstanceOf(IllegalStateException.class);
    }

    // ── 取值对齐：两条通道同源（机器可查的锚点） ─────────────────────────────

    @Test
    void bothChannelsTakeTheSameDefaultWriteBudget() {
        assertThat(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS).as("预算必须为正（0 就不是预算了）").isPositive();
        assertThat(new AiProperties().getSse().getWriteTimeout().toMillis())
                .as("AI 通道缺省写预算 = 单一事实源")
                .isEqualTo(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
        assertThat(new AppProperties().getTask().getSse().getWriteTimeout().toMillis())
                .as("任务通道缺省写预算 = 单一事实源（取值与 AI 通道对齐）")
                .isEqualTo(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
        assertThat(new AppProperties().getTask().getSse().getEmitterTimeout().toMillis())
                .as("任务通道 emitter 生命周期超时必须为正（0 = 该 emitter 无生命周期超时，"
                        + "且不等于连接无释放手段：120s 容器写超时仍在）")
                .isPositive()
                .isGreaterThan(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void sharedElasticWriterPoolIsIdempotentAndDaemon() {
        ExecutorService first = SseWriteBudget.sharedElasticWriterPool();
        ExecutorService second = SseWriteBudget.sharedElasticWriterPool();

        assertThat(second).as("进程级写出池幂等装配").isSameAs(first);
    }

    // ── 配置守卫：emitter 生命周期超时必须为正（2026-10-10 复核 S4-⑨ 收口） ──────

    /**
     * {@code app.task.sse.emitter-timeout} 配成 0s 必须被<b>绑定后</b>的启动期校验拦下
     * （0 = 该订阅者没有生命周期超时，卡住的写在 15s 预算后只剩 120s 容器写超时兜底）。
     * 守卫读的是绑定值而不是字段缺省，故用最小 Spring 上下文实测（装置口径同
     * {@code AiPropertiesT35DefaultsTest}）。
     */
    @Test
    void zeroTaskEmitterTimeoutFailsStartupWhileTheDefaultPasses() {
        // 先证"缺省（5m）照常起"：守卫不得过严
        AnnotationConfigApplicationContext healthy = new AnnotationConfigApplicationContext();
        healthy.register(PropertiesOnlyConfig.class);
        healthy.refresh();
        try {
            assertThat(healthy.getBean(AppProperties.class).getTask().getSse().getEmitterTimeout()).isPositive();
        }
        finally {
            healthy.close();
        }

        // 再把绑定值改成 0s：refresh 必须失败，根因即该守卫（fail-fast，不是告警）
        AnnotationConfigApplicationContext zeroed = new AnnotationConfigApplicationContext();
        zeroed.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pkg2-emitter-timeout",
                Map.of("app.task.sse.emitter-timeout", "0s")));
        zeroed.register(PropertiesOnlyConfig.class);
        try {
            assertThatThrownBy(zeroed::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.task.sse.emitter-timeout");
        }
        finally {
            zeroed.close();
        }
    }

    // ── 配置守卫：写预算必须为正（2026-10-10 复核 S3-1 收口：守卫对称化） ────────

    /**
     * {@code app.ai.sse.write-timeout} 与 {@code app.task.sse.write-timeout} 配成 0s 必须被
     * <b>绑定后</b>的启动期校验拦下 —— 0/负值会被 {@code SseWriteBudget.on} <b>静默回落</b>成缺省
     * 15s，配置文件与运行行为不符；两条通道对称 fail-fast。缺省（15s）照常起（守卫不得过严）。
     *
     * <p>
     * 与 {@link #zeroTaskEmitterTimeoutFailsStartupWhileTheDefaultPasses()} 同装置：守卫读绑定值
     * 而非字段缺省，故必须过最小 Spring 上下文实测，不能只 new 一个属性对象断言。
     */
    @Test
    void zeroWriteTimeoutFailsStartupOnBothChannelsWhileTheDefaultPasses() {
        // 先证"缺省照常起"：两条通道的属性 bean 都能绑定，且值都为正
        AnnotationConfigApplicationContext defaults = new AnnotationConfigApplicationContext();
        defaults.register(PropertiesOnlyConfig.class);
        defaults.register(AiPropertiesOnlyConfig.class);
        defaults.refresh();
        try {
            assertThat(defaults.getBean(AppProperties.class).getTask().getSse().getWriteTimeout())
                    .as("任务通道缺省写预算照常通过守卫").isPositive();
            assertThat(defaults.getBean(AiProperties.class).getSse().getWriteTimeout())
                    .as("AI 通道缺省写预算照常通过守卫").isPositive();
        }
        finally {
            defaults.close();
        }

        // 再把绑定值改成 0s：refresh 必须失败，根因消息点名该键（不是告警、更不是静默回落 15s）
        assertZeroWriteTimeoutFailsStartup(PropertiesOnlyConfig.class, "app.task.sse.write-timeout");
        assertZeroWriteTimeoutFailsStartup(AiPropertiesOnlyConfig.class, "app.ai.sse.write-timeout");
    }

    /** 最小上下文里把 {@code key} 配成 0s：refresh 必须失败，且根因点名该键。 */
    private static void assertZeroWriteTimeoutFailsStartup(Class<?> config, String key) {
        AnnotationConfigApplicationContext zeroed = new AnnotationConfigApplicationContext();
        zeroed.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pkg2-write-timeout",
                Map.of(key, "0s")));
        zeroed.register(config);
        try {
            assertThatThrownBy(zeroed::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key);
        }
        finally {
            zeroed.close();
        }
    }

    /** 最小上下文：只做 {@code app.*} 的属性绑定（守卫的落点就是属性 bean 自身）。 */
    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesOnlyConfig {

    }

    /** 最小上下文：只做 {@code app.ai.*} 的属性绑定（AI 通道写预算守卫的落点同是属性 bean 自身）。 */
    @Configuration
    @EnableConfigurationProperties(AiProperties.class)
    static class AiPropertiesOnlyConfig {

    }

    /** 取预算内部的写出执行器（白盒：本类无对外可观测面，且它正是"是否走池"的判据）。 */
    private static Object executorOf(SseWriteBudget budget) {
        try {
            Field field = SseWriteBudget.class.getDeclaredField("executor");
            field.setAccessible(true);
            return field.get(budget);
        }
        catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("读不出 SseWriteBudget#executor", ex);
        }
    }
}
