package com.example.configmgr.file;

import java.time.Duration;

/**
 * 文件存储键迁移的<b>单飞锁</b>（跨实例）。
 *
 * <p>迁移会为同一行分配新 UUID 并复制文件；两个实例同时跑会产出两份复制件、后写者覆盖前写者的
 * 行引用，留下孤儿。故迁移入口必须先拿锁，拿不到直接放弃本次迁移（幂等，下一轮再试）。
 *
 * <p>生产实现见 {@link RedisFileKeyMigrationLock}（{@code SET NX PX} + compare-and-delete）；
 * 测试可用进程内实现验证单飞语义。
 */
public interface FileKeyMigrationLock {

    /**
     * 抢占迁移权。
     *
     * @param token 本次运行的持有者令牌（释放/续期时校验，避免删掉别人的锁）
     * @param ttl   锁租期（到期自动让位，防崩溃实例永久锁死）
     * @return true = 抢到
     */
    boolean tryAcquire(String token, Duration ttl);

    /**
     * 续期（仅当锁仍属于本 token 时延长租期）。
     *
     * @return false = 锁已失效/被接管，调用方应立即停止写入
     */
    boolean renew(String token, Duration ttl);

    /** 释放（仅当锁仍属于本 token 时删除）。 */
    void release(String token);
}
