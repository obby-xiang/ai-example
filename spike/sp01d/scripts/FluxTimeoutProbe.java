// 微实验：确认 Reactor Flux.timeout(Duration) 的语义边界
// 运行：
//   java -cp <REACTOR_CORE_JAR>;<REACTIVE_STREAMS_JAR> scripts/FluxTimeoutProbe.java
// 结论用于 SP-01d 决策卡 V-d1 的"卡点定位"。
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class FluxTimeoutProbe {

    public static void main(String[] args) {
        System.out.println("reactor-core version = " + Flux.class.getPackage().getImplementationVersion());
        probeA_firstItemOnly();
        probeB_midStreamSilence();
        probeC_firstAndInterTimeout();
        probeD_retryAfterMidStreamError();
        probeE_retryAfterMidStreamTimeout();
    }

    /** A：首包静默 —— 单值 timeout 应当触发。 */
    private static void probeA_firstItemOnly() {
        long t0 = System.currentTimeMillis();
        String outcome;
        try {
            Flux.never().timeout(Duration.ofMillis(300)).blockLast();
            outcome = "completed";
        } catch (Throwable e) {
            outcome = root(e);
        }
        System.out.printf("A 首包静默 | flux=Flux.never() timeout=300ms -> %s @%dms%n",
                outcome, System.currentTimeMillis() - t0);
    }

    /** B：已产出首元素后的上游静默 —— 单值 timeout 是否触发？（本次实验的关键判别点） */
    private static void probeB_midStreamSilence() {
        long t0 = System.currentTimeMillis();
        String outcome;
        AtomicInteger emitted = new AtomicInteger();
        try {
            Flux.concat(Flux.just("first"), Flux.<String>never())
                    .timeout(Duration.ofMillis(300))
                    .doOnNext(x -> emitted.incrementAndGet())
                    .blockLast(Duration.ofSeconds(3));
            outcome = "completed-within-3s-guard";
        } catch (Throwable e) {
            outcome = root(e);
        }
        System.out.printf("B 事件间静默 | flux=concat(just(first),never) timeout=300ms -> %s @%dms emitted=%d%n",
                outcome, System.currentTimeMillis() - t0, emitted.get());
    }

    /** C：timeout(firstTimeoutPublisher, nextTimeoutFactory) —— 事件间静默应当触发。 */
    private static void probeC_firstAndInterTimeout() {
        long t0 = System.currentTimeMillis();
        String outcome;
        AtomicInteger emitted = new AtomicInteger();
        try {
            Flux.concat(Flux.just("first"), Flux.<String>never())
                    .timeout(Mono.delay(Duration.ofMillis(300)), item -> Mono.delay(Duration.ofMillis(300)))
                    .doOnNext(x -> emitted.incrementAndGet())
                    .blockLast(Duration.ofSeconds(3));
            outcome = "completed-within-3s-guard";
        } catch (Throwable e) {
            outcome = root(e);
        }
        System.out.printf("C 事件间静默+双超时 | 同上但用 (firstPublisher,nextFactory) -> %s @%dms emitted=%d%n",
                outcome, System.currentTimeMillis() - t0, emitted.get());
    }

    /** D：参照实现的 .timeout(D).retry(1).doOnNext(..) 在"已产出后出错"时会怎样。 */
    private static void probeD_retryAfterMidStreamError() {
        long t0 = System.currentTimeMillis();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger emitted = new AtomicInteger();
        StringBuilder seen = new StringBuilder();
        String outcome;
        try {
            Flux.concat(Flux.just("A", "B"), Flux.error(new RuntimeException("socket reset")))
                    .doOnSubscribe(s -> subscriptions.incrementAndGet())
                    .timeout(Duration.ofMillis(500))
                    .retry(1)
                    .doOnNext(x -> {
                        emitted.incrementAndGet();
                        seen.append(x);
                    })
                    .blockLast(Duration.ofSeconds(3));
            outcome = "completed";
        } catch (Throwable e) {
            outcome = root(e);
        }
        System.out.printf("D retry 重订阅 | concat(A,B,error) .timeout(500ms).retry(1).doOnNext -> %s @%dms"
                        + " 订阅次数=%d 下游收到元素=%d 内容=%s%n",
                outcome, System.currentTimeMillis() - t0, subscriptions.get(), emitted.get(), seen);
    }

    /** E：参照实现在"已产出内容后上游静默超时"下的行为（最接近真实断流）。 */
    private static void probeE_retryAfterMidStreamTimeout() {
        long t0 = System.currentTimeMillis();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger emitted = new AtomicInteger();
        StringBuilder seen = new StringBuilder();
        String outcome;
        try {
            Flux.concat(Flux.just("A", "B"), Flux.<String>never())
                    .doOnSubscribe(s -> subscriptions.incrementAndGet())
                    .timeout(Duration.ofMillis(400))
                    .retry(1)
                    .doOnNext(x -> {
                        emitted.incrementAndGet();
                        seen.append(x);
                    })
                    .blockLast(Duration.ofSeconds(5));
            outcome = "completed";
        } catch (Throwable e) {
            outcome = root(e);
        }
        System.out.printf("E retry 超时重订阅 | concat(A,B,never) .timeout(400ms).retry(1).doOnNext -> %s @%dms"
                        + " 订阅次数=%d 下游收到元素=%d 内容=%s%n",
                outcome, System.currentTimeMillis() - t0, subscriptions.get(), emitted.get(), seen);
    }

    private static String root(Throwable e) {
        if (e instanceof TimeoutException) {
            return "TimeoutException";
        }
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + (r instanceof TimeoutException ? "" : ": " + r.getMessage());
    }
}
