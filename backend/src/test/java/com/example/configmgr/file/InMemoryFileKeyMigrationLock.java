package com.example.configmgr.file;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内单飞锁替身：{@code compareAndSet} 语义等价 Redis {@code SET NX}，供并发用例共享。
 *
 * <p>只用于测试：真实跨实例语义由 {@link RedisFileKeyMigrationLock} 的 {@code SET NX PX} 保证。
 */
final class InMemoryFileKeyMigrationLock implements FileKeyMigrationLock {

    private final AtomicReference<String> holder = new AtomicReference<>();

    private final AtomicInteger acquireCalls = new AtomicInteger();

    private final AtomicInteger renewCalls = new AtomicInteger();

    private final AtomicInteger releaseCalls = new AtomicInteger();

    private volatile boolean renewalsAllowed = true;

    @Override
    public boolean tryAcquire(String token, Duration ttl) {
        this.acquireCalls.incrementAndGet();
        return this.holder.compareAndSet(null, token);
    }

    @Override
    public boolean renew(String token, Duration ttl) {
        this.renewCalls.incrementAndGet();
        return this.renewalsAllowed && token.equals(this.holder.get());
    }

    @Override
    public void release(String token) {
        this.releaseCalls.incrementAndGet();
        this.holder.compareAndSet(token, null);
    }

    void preHold(String token) {
        this.holder.set(token);
    }

    void failRenewals() {
        this.renewalsAllowed = false;
    }

    boolean isFree() {
        return this.holder.get() == null;
    }

    int acquireCalls() {
        return this.acquireCalls.get();
    }

    int renewCalls() {
        return this.renewCalls.get();
    }

    int releaseCalls() {
        return this.releaseCalls.get();
    }
}
