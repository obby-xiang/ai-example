package com.example.configmgr.ai.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ModelConcurrencyLimiter {

    static final int MAX_CONCURRENT_CALLS = 10;

    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_CALLS, true);

    <T> T call(Callable<T> call) throws Exception {
        acquire();
        try {
            return call.call();
        }
        finally {
            this.permits.release();
        }
    }

    <T> Flux<T> stream(Supplier<Flux<T>> call) {
        return Flux.defer(() -> {
            acquireForStream();
            Flux<T> stream;
            try {
                stream = call.get();
            }
            catch (RuntimeException | Error ex) {
                this.permits.release();
                throw ex;
            }
            if (stream == null) {
                this.permits.release();
                return Flux.error(new IllegalStateException("model stream is null"));
            }
            return stream.doFinally(ignored -> this.permits.release());
        });
    }

    private void acquire() throws InterruptedException {
        if (!this.permits.tryAcquire(ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("model concurrency acquisition timed out");
        }
    }

    private void acquireForStream() {
        try {
            acquire();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model concurrency acquisition interrupted", ex);
        }
    }
}
