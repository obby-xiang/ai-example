package com.example.configmgr.seed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三实例同时播种（多实例专项 · 种子幂等）：同一库、三个**独立事务**并发跑
 * {@link DataSeedRunner} + {@link GlmSeedService}，要求
 * <b>不重复、不报唯一冲突、恰好一个实例真正写入</b>。
 *
 * <p>为什么这样能代表"三个实例"：实例间唯一的共享面就是数据库与 {@code app_lock} 行；
 * 三个线程各自经 Spring 代理进入 {@code @Transactional} 方法 ⇒ 三个互不共享的事务/连接，
 * 与三个 JVM 在同一库上的并发形态等价（H2/TCP 三 JVM 的真实例冒烟属环境级验证，见
 * application-h2tcp.yml，不在单测里起进程）。
 *
 * <p>用例先清空种子表制造"全新库"：Spring 上下文启动时已经播过一轮，不清空的话第二轮
 * 只会全部跳过，测不到竞态。
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:seed-race;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class SeedConcurrencyTest {

	/** 播种数据总量：基座 1230（5+5+4+16+1200）+ glm 370（40+30+120+8+12+20+112+28）。 */
	private static final int EXPECTED_DATA_ROWS = 1600;

	private static final int INSTANCES = 3;

	@Autowired
	private DataSeedRunner dataSeedRunner;

	@Autowired
	private GlmSeedService glmSeedService;

	@Autowired
	private SeedLock seedLock;

	@Autowired
	private SeedStatus seedStatus;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void restoreSeededState() throws Exception {
		// 每个用例后把库恢复成"已播种"（同类的其它用例不依赖，但保持上下文缓存内的一致状态）
		if (count("regions") == 0) {
			this.dataSeedRunner.run(null);
			this.glmSeedService.seedIfAbsent();
		}
	}

	@Test
	void threeInstancesSeedExactlyOnceWithoutUniqueViolations() throws Exception {
		clearSeedTables();
		assertThat(count("regions")).isZero();

		List<Boolean> glmWrites = runConcurrent();

		assertThat(glmWrites).as("恰好一个实例真正写入 glm 种子").containsExactlyInAnyOrder(true, false, false);
		assertThat(count("regions")).isEqualTo(4);
		assertThat(count("projects")).isEqualTo(7);
		assertThat(count("config_definitions")).isEqualTo(15);
		assertThat(count("config_data_rows")).isEqualTo(EXPECTED_DATA_ROWS);
		assertThat(duplicateDataRows()).as("按业务键不得有重复行").isZero();
		assertThat(duplicateCodes()).as("定义/地区/项目编码不得重复").isZero();

		assertThat(this.seedStatus.stateOf(SeedStatus.Seed.BASE))
				.isIn(SeedStatus.State.DONE, SeedStatus.State.SKIPPED);
		assertThat(this.seedStatus.stateOf(SeedStatus.Seed.GLM)).isEqualTo(SeedStatus.State.DONE);
		assertThat(this.seedStatus.isReady()).isTrue();
	}

	@Test
	void secondConcurrentWaveSkipsEverything() throws Exception {
		clearSeedTables();
		runConcurrent();
		int rowsAfterFirstWave = count("config_data_rows");

		List<Boolean> secondWave = runConcurrent();

		assertThat(secondWave).as("第二轮全部判重跳过").containsExactly(false, false, false);
		assertThat(count("config_data_rows")).isEqualTo(rowsAfterFirstWave);
		assertThat(count("config_definitions")).isEqualTo(15);
		assertThat(duplicateDataRows()).isZero();
	}

	@Test
	void seedLockRefusesToRunOutsideTransaction() {
		assertThatThrownBy(() -> this.seedLock.lock(SeedLock.SEED_LOCK))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("必须在事务内");
	}

	// ---------- helpers ----------

	/** 三个线程在同一屏障后同时开跑；返回每个线程的 glm 写入结果。 */
	private List<Boolean> runConcurrent() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
		CyclicBarrier barrier = new CyclicBarrier(INSTANCES);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int i = 0; i < INSTANCES; i++) {
				Callable<Boolean> task = () -> {
					barrier.await(30, TimeUnit.SECONDS);
					this.dataSeedRunner.run(null);
					return this.glmSeedService.seedIfAbsent();
				};
				futures.add(pool.submit(task));
			}
			List<Boolean> results = new ArrayList<>();
			for (Future<Boolean> future : futures) {
				// 有任何唯一冲突/锁超时都会在这里以 ExecutionException 浮出，失败信息带原始异常
				results.add(future.get(180, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private void clearSeedTables() {
		this.jdbcTemplate.update("DELETE FROM config_data_rows");
		this.jdbcTemplate.update("DELETE FROM config_fields");
		this.jdbcTemplate.update("DELETE FROM config_definitions");
		this.jdbcTemplate.update("DELETE FROM projects");
		this.jdbcTemplate.update("DELETE FROM regions");
	}

	private int count(String table) {
		Integer value = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		return value == null ? 0 : value;
	}

	/** 同一业务键（def_code + 范围 + row_key）出现多行的组数。 */
	private int duplicateDataRows() {
		Integer value = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM (SELECT def_code, scope_type, scope_key, row_key "
						+ "FROM config_data_rows GROUP BY def_code, scope_type, scope_key, row_key "
						+ "HAVING COUNT(*) > 1) duplicated",
				Integer.class);
		return value == null ? 0 : value;
	}

	/** 各表编码重复的总数（regions/projects/config_definitions 的唯一约束在并发下最容易被打穿）。 */
	private int duplicateCodes() {
		Integer value = this.jdbcTemplate.queryForObject(
				"SELECT (SELECT COUNT(*) FROM (SELECT code FROM regions GROUP BY code HAVING COUNT(*) > 1) r) "
						+ "+ (SELECT COUNT(*) FROM (SELECT code FROM projects GROUP BY code HAVING COUNT(*) > 1) p) "
						+ "+ (SELECT COUNT(*) FROM (SELECT code FROM config_definitions GROUP BY code HAVING COUNT(*) > 1) d)",
				Integer.class);
		return value == null ? 0 : value;
	}

}
