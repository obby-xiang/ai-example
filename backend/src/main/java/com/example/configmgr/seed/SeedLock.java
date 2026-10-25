package com.example.configmgr.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 种子播种的**跨实例单飞**：在播种事务内锁住 {@code app_lock} 的种子行。
 *
 * <h2>为什么是行锁而不是"先查再写"</h2>
 * 原判据 {@code regionRepository.count() > 0} 是**先查后写**：三个实例同时启动时都会读到 0，
 * 然后各自插入同一批 {@code regions.code} ⇒ 唯一约束冲突，后两者启动失败（种子越全、冲突越多）。
 * 行锁把"检查 + 写入"收进同一个临界区：抢到锁的实例播种并提交，等待方随后**在锁内重新判重**，
 * 看到已提交的数据就直接跳过（PG 的 READ COMMITTED / H2 的 READ COMMITTED 都是语句级快照，
 * 等待结束后重新执行的 SELECT 能看到前一个事务的提交）。
 *
 * <h2>锁的生命周期</h2>
 * 锁与**调用方事务**同界：提交/回滚即释放，不会漏到连接池的下一次借用里
 * （这是不用 {@code pg_advisory_lock} 的理由之一：它是连接级的，H2 也没有对应物）。
 * 因此本类**必须在事务内调用**，否则 autocommit 下 {@code FOR UPDATE} 的锁在下一条语句前就没了 ——
 * 那种"看起来加了锁"的误用比不加锁更危险，故这里直接 fail-fast。
 *
 * <h2>等待上界</h2>
 * 等待由数据库的锁等待参数决定，不由本类重试：PG 缺省无限等待（抢锁方崩了 ⇒ 事务回滚 ⇒ 锁释放），
 * H2 由连接串 {@code LOCK_TIMEOUT} 决定（application-h2tcp.yml 设 60s）—— 覆盖一整轮播种即可。
 *
 * <h2>前提</h2>
 * {@code app_lock} 表与行由迁移 {@code V7__app_lock} 预置（H2/PG 两份同字节）。行缺失说明迁移没跑，
 * 这里直接失败并点名迁移 —— 静默降级成"无锁播种"会重新引入唯一冲突。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedLock {

	/** 种子单飞的锁名（{@code app_lock.lock_name}）。 */
	public static final String SEED_LOCK = "seed";

	private static final String HOLDER = holder();

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 在调用方事务内抢 {@code app_lock.lock_name} 的行锁；抢到后清点持有者，返回即代表进入临界区。
	 *
	 * @param lockName 锁名（当前只有 {@link #SEED_LOCK}）
	 * @throws IllegalStateException 不在事务内，或锁行不存在（迁移未应用）
	 */
	public void lock(String lockName) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("SeedLock 必须在事务内调用（" + lockName + "）："
					+ "autocommit 下 FOR UPDATE 的锁在语句结束即释放，等于没加锁");
		}
		List<String> rows = this.jdbcTemplate.queryForList(
				"SELECT lock_name FROM app_lock WHERE lock_name = ? FOR UPDATE", String.class, lockName);
		if (rows.isEmpty()) {
			throw new IllegalStateException("app_lock 缺少行 '" + lockName + "'：迁移 V7__app_lock 未应用"
					+ "（H2 用 classpath:db/migration，PG 用 classpath:db/postgresql），种子无法单飞");
		}
		this.jdbcTemplate.update(
				"UPDATE app_lock SET holder = ?, acquired_at = CURRENT_TIMESTAMP WHERE lock_name = ?",
				HOLDER, lockName);
		log.info("已获得种子单飞锁 '{}'（holder={}）；等待方会在本事务结束后重新判重", lockName, HOLDER);
	}

	/** 持有者标识（pid@host）：只进 app_lock.holder 与日志，用于回答"这轮种子是哪台播的"。 */
	private static String holder() {
		try {
			String name = ManagementFactory.getRuntimeMXBean().getName();
			if (name != null && !name.isBlank()) {
				return name.length() > 128 ? name.substring(0, 128) : name;
			}
		}
		catch (RuntimeException ignored) {
			// 取不到就用固定占位（holder 只作观测，不参与判定）
		}
		return "unknown";
	}

}
