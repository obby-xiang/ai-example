package com.example.configmgr.health;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停机时 {@code /health/ready} 的 <b>draining 窗口</b>必须真实存在（多实例验收用例
 * {@code readiness-removal} 的产品侧根因）。
 *
 * <h2>缺陷（三实例 run 40173316364 实证）</h2>
 * SIGTERM 后 {@code ReadinessService} 确实写了 "实例进入 draining" 日志，但紧接着
 * （<b>没有任何等待</b>）Spring Boot 就走完 lifecycle stop → {@code TomcatWebServer#stop}
 * → {@code GracefulShutdown#close} = {@code connector.pause() + closeServerSocketGraceful()}：
 * 探针的观测序列是 {@code +2ms 200(ready=true)} → {@code +53ms socket hang up} →
 * 之后全部 ECONNREFUSED，一次 503 都没读到。也就是说"先摘流、后停服"只存在于日志里，
 * LB 没有任何探测窗口。
 *
 * <h2>本用例的口径</h2>
 * 用真实 Spring 事件总线装配 {@link ReadinessService}，并用一个
 * {@link Ordered#LOWEST_PRECEDENCE} 的监听器充当"连接器停止接受连接"的那一刻。
 * 这比真实链路<b>更严格</b>：真实关闭发生在事件扇出之后的 lifecycle stop，而这里的替身在我们的
 * 监听器之后立刻执行 —— 若在这种最紧的排布下窗口仍成立，真实链路只会更宽松。
 * 断言：替身执行时必须已经能读到 503/draining，且距离 draining 起始至少有一段可配置窗口
 * （{@code app.health.readiness.drain-window}）；关闭之后不得再回到 available。
 *
 * <p>边界：本用例不绑真实端口（连接器用同序监听器代表），"Tomcat 真实 socket 关闭时刻"由
 * 三实例 Action 覆盖。
 */
class ReadinessDrainWindowTest {

    @Test
    void connectorStopIsPrecededByAnObservableDrainingWindow() throws Exception {
        ReadinessProperties properties = new ReadinessProperties();
        properties.setDrainWindow(Duration.ofMillis(700));

        AtomicInteger probeCalls = new AtomicInteger();
        ReadinessProbe upProbe = new ReadinessProbe() {
            @Override
            public String name() {
                return "db";
            }

            @Override
            public ProbeResult check() {
                probeCalls.incrementAndGet();
                return ProbeResult.up("SELECT 1 正常");
            }
        };
        ReadinessProbeRunner runner = ReadinessProbeRunner.defaults();
        ReadinessService readiness = new ReadinessService(List.of(upProbe), runner);
        ConnectorCloser closer = new ConnectorCloser(readiness);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReadinessService.class, () -> readiness);
            context.registerBean(ReadinessProperties.class, () -> properties);
            context.registerBean(ConnectorCloser.class, () -> closer);
            context.registerBean("shutdownDrainGate", ShutdownDrainGate.class);
            context.refresh();

            assertThat(new HealthController(readiness).ready().getStatusCode())
                    .as("关闭前：硬依赖 UP ⇒ 200").isEqualTo(HttpStatus.OK);

            // 并发观测者：在窗口期内持续打真实端点，证明 503 是"窗口一开始就可见"，而不是只在结束时出现
            AtomicReference<Instant> firstObserved503At = new AtomicReference<>();
            Thread observer = new Thread(() -> {
                while (true) {
                    ResponseEntity<Map<String, Object>> live = new HealthController(readiness).ready();
                    if (live.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE
                            && Boolean.TRUE.equals(live.getBody().get("draining"))) {
                        firstObserved503At.compareAndSet(null, Instant.now());
                        return;
                    }
                    try {
                        Thread.sleep(10);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "drain-window-observer");
            observer.setDaemon(true);
            observer.start();

            Thread shutdownHook = new Thread(context::close, "test-shutdown-hook");
            shutdownHook.start();
            shutdownHook.join(10_000);
            assertThat(shutdownHook.isAlive()).as("关闭必须在有界时间内完成").isFalse();
            observer.join(5_000);

            // ① 连接器停止接受连接的那一刻，探针必须已经能读到 503/draining（不是事后日志）
            assertThat(closer.readyAtClose).as("充当连接器关闭的监听器必须真的执行过").isNotNull();
            assertThat(closer.readyAtClose.getStatusCode())
                    .as("连接器关闭前最后一次 /health/ready 必须是 503")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(closer.readyAtClose.getBody())
                    .containsEntry("draining", true)
                    .containsEntry("ready", false);
            assertThat(closer.readyAtClose.getBody().get("checks"))
                    .as("draining 走快路径：不再探测依赖").isEqualTo(Map.of());

            // ② 503 必须真实可见一段窗口；否则 LB 的探测周期（CI 为 2s，产品缺省 1–5s）抓不到
            Instant since = Instant.parse((String) closer.readyAtClose.getBody().get("drainingSince"));
            Duration visible = Duration.between(since, closer.closedAt);
            assertThat(visible)
                    .as("从 draining 可见到连接器关闭必须 ≥ drain-window 的 80%%（实得 %sms，配置 %sms）",
                            visible.toMillis(), properties.getDrainWindow().toMillis())
                    .isGreaterThanOrEqualTo(properties.getDrainWindow().multipliedBy(8).dividedBy(10));

            // ③ 503 必须在窗口"一开始"就可见（而不是等到窗口结束/连接器关闭那一刻）
            Instant firstSeen = firstObserved503At.get();
            assertThat(firstSeen).as("窗口期内必须能在线观测到 503/draining").isNotNull();
            assertThat(Duration.between(since, firstSeen))
                    .as("503 必须出现在窗口前段（实得 %sms，窗口 %sms）",
                            Duration.between(since, firstSeen).toMillis(), properties.getDrainWindow().toMillis())
                    .isLessThan(properties.getDrainWindow().dividedBy(2));
            assertThat(firstSeen).as("503 必须在连接器停接之前被观测到").isBefore(closer.closedAt);

            // ④ 窗口期内依赖仍被探测过（说明这确实是"服务还在、只是宣布未就绪"，不是已经停了）
            assertThat(probeCalls.get()).as("关闭前至少探测过一次").isPositive();

            // ⑤ 关闭之后不得"恢复 available"（draining 是终态）
            assertThat(readiness.isDraining()).isTrue();
            ResponseEntity<Map<String, Object>> after = new HealthController(readiness).ready();
            assertThat(after.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(after.getBody()).containsEntry("draining", true).containsEntry("ready", false);
        }
        finally {
            runner.close();
        }
    }

    /**
     * 充当"连接器停止接受连接"的时刻（真实链路里是事件扇出之后的 lifecycle stop；
     * 这里用最紧的排布 —— {@link Ordered#LOWEST_PRECEDENCE} 监听器，紧跟我们的 drain 监听器执行）。
     * 此刻读到的 readiness 就是 LB 在摘流前能看到的最后状态。
     */
    static final class ConnectorCloser implements ApplicationListener<ContextClosedEvent>, Ordered {

        private final ReadinessService readiness;

        volatile ResponseEntity<Map<String, Object>> readyAtClose;

        volatile Instant closedAt;

        ConnectorCloser(ReadinessService readiness) {
            this.readiness = readiness;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            this.readyAtClose = new HealthController(this.readiness).ready();
            this.closedAt = Instant.now();
        }
    }

}
