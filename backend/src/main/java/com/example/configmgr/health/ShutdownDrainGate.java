package com.example.configmgr.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 关闭流程的<b>draining 窗口</b>：先让 {@code /health/ready} 变 503，并在这段窗口内<b>保持
 * web 连接器可接受连接</b>，窗口结束后才把停机交给 Spring Boot 的 graceful shutdown。
 *
 * <h2>为什么需要它（三实例 run 40173316364 实证）</h2>
 * {@code server.shutdown=graceful} 只保证"停止接受新请求 + 等在途请求收尾"。Spring Boot 3.5 的
 * 关闭链是：{@code AbstractApplicationContext#doClose} 先扇出 {@link ContextClosedEvent}
 * （本类的时机），<b>之后</b>才 {@code lifecycleProcessor.onClose()} → {@code TomcatWebServer#stop}
 * → {@code GracefulShutdown#close(Connector)} = {@code connector.pause()} +
 * {@code ProtocolHandler#closeServerSocketGraceful()}（字节码核对 spring-boot-3.5.14）。
 * 中间没有任何等待 ⇒ 实测探针序列是 {@code +2ms 200(ready=true)} → {@code +53ms socket hang up}
 * → 之后全部 {@code ECONNREFUSED}，一次 503 都没读到：摘流链只剩一条事后日志。
 * 只要在<b>事件扇出期间</b>等待，连接器关闭就被推迟（事件严格早于 lifecycle stop）。
 *
 * <h2>机制</h2>
 * 本监听器以 {@link Ordered#HIGHEST_PRECEDENCE} 先于 {@code GracefulShutdown} 执行：
 * <ol>
 * <li>{@link ReadinessService#markDraining()}（幂等，终态）⇒ {@code /health/ready} 立即 503；</li>
 * <li>在调用线程上<b>有界等待</b> {@code app.health.readiness.drain-window}：这期间连接器还没被
 *     pause，LB 的探测（以及任何探针）都能读到 503，从而真正完成摘流；</li>
 * <li>返回后事件继续扇出，{@code GracefulShutdown} 才停接新请求、等在途请求收尾。</li>
 * </ol>
 *
 * <p>等价于 K8s 的 {@code preStop: sleep N}，只是放在进程内，避免依赖容器编排清单。
 * 放在事件阶段而不是 wrapper 脚本里，还有一个好处：{@code /health/ready} 的 503 与"仍在接连接"
 * 是同一个进程里的同一段状态，不存在脚本与 JVM 的交接空窗。
 * 等待是<b>可中断</b>的（第二次 Ctrl-C 立即返回），且窗口有上界
 * （{@link ReadinessProperties#getDrainWindow()} 校验 ≤ 60s），与
 * {@code timeout-per-shutdown-phase} 相加即停机总时长。
 *
 * <p>{@code drain-window=0}（单实例/无 LB 形态的缺省）⇒ 本类只置位不等待，行为与改造前一致。
 */
@Slf4j
@Component
class ShutdownDrainGate {

	/** 等待切片：让 Ctrl-C/编排器信号能及时打断，不用一个长 sleep 卡死。 */
	private static final long SLICE_MS = 100L;

	private final ReadinessService readiness;

	private final Duration drainWindow;

	ShutdownDrainGate(ReadinessService readiness, ReadinessProperties properties) {
		this.readiness = readiness;
		this.drainWindow = properties.getDrainWindow();
	}

	@EventListener(ContextClosedEvent.class)
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public void drainBeforeConnectorStops() {
		this.readiness.markDraining();
		if (this.drainWindow == null || this.drainWindow.isZero() || this.drainWindow.isNegative()) {
			return;
		}
		log.info("draining 窗口开始：{}（/health/ready=503，web 连接器仍接受连接，等 LB 摘流）",
				this.drainWindow);
		long deadline = System.nanoTime() + this.drainWindow.toNanos();
		while (true) {
			long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0) {
				break;
			}
			try {
				Thread.sleep(Math.min(SLICE_MS, Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
			}
			catch (InterruptedException interrupted) {
				// 停机被外部打断（例如第二次信号）：立即交给后续的 graceful shutdown，不吞中断标记
				Thread.currentThread().interrupt();
				log.warn("draining 窗口被中断，立即进入停机（已经可见的 503 不会被撤销）");
				return;
			}
		}
		log.info("draining 窗口结束：交给优雅停机（停止接受新请求，等在途请求收尾）");
	}

}
