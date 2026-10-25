-- V7__app_lock.sql
--
-- 种子单飞的行锁表（多实例共享同一个库时，同一时刻只允许一个实例执行播种）。
--
-- 语义：
--   · 行由本迁移预置（'seed'），应用侧**不建行**，只 `SELECT ... FOR UPDATE` 抢锁；
--     抢到锁的事务提交/回滚即释放，等待方随后**重新判重**再决定是否播种（见 seed/SeedLock）。
--   · 为什么不用 advisory lock：PG 的 pg_advisory_lock 是连接级的、H2 没有对应物；
--     行锁是两库共同语义，且锁的生命周期与播种事务严格同界（不会泄漏到连接池的下一次借用）。
--   · holder / acquired_at 只作运维观测（哪台实例播的种），不参与判定。
--
-- 方言：只有 DDL 与一条幂等 INSERT，无方言差异 → db/migration（H2）与 db/postgresql（PG）
--       两份**同字节**；改动本文件必须两份一起改（MigrationSetGuardTest 守这条）。
-- 版本号在 H2 侧排在 Java 迁移 V6 之后；PG 侧排在 SQL 版 V6 之后，两库各自独立，互不影响。
--
-- IF NOT EXISTS 的口径：Flyway 保证迁移只成功执行一次，本句不是"日常可重跑"的许可，而是与 V6
-- （Java 迁移按元数据判存在）对齐的**半完成态可重入**：历史行丢失后重跑尾部时，
-- "表已建、版本未记"不该再炸一次。库表形状与下面 INSERT 的幂等性同源（同一把"存在即跳过"）。

CREATE TABLE IF NOT EXISTS app_lock (
    lock_name   VARCHAR(64) PRIMARY KEY,
    holder      VARCHAR(128),
    acquired_at TIMESTAMP
);

INSERT INTO app_lock (lock_name)
SELECT 'seed' WHERE NOT EXISTS (SELECT 1 FROM app_lock WHERE lock_name = 'seed');
