package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>种子写路径必须发布配置版本</b>（run 40182796512 的根因 A）。
 *
 * <h2>缺陷形态（实测）</h2>
 * {@link AiConfigSeeder} 直接写 repository 并<b>只 reload 本实例</b>：DB 的
 * {@code ai_setting['config.version']} 停在种子常量 1、Redis {@code ai:cfg:ver} 也不存在。
 * 读侧（{@link AiConfigService#checkReload}）只按版本比对决定要不要重载，于是<b>别的实例</b>
 * 的配置快照永久停在"空 provider 列表"（对外表现为 env 合成行），续跑随后的精确绑定判据
 * （{@code resolveChatTarget(providerId, modelId)}）必然落空 ⇒ 误判 {@code PROVIDER_GONE}。
 *
 * <h2>本文件锁什么</h2>
 * 两个<b>互相独立</b>的 {@link AiConfigService} 快照（= 两个实例）共享同一份"库"（{@link AiConfigTestFixture}
 * 的内存表）：
 * <ul>
 * <li>一个有 Redis（走 {@code ai:cfg:ver} 比对）、一个没有（退化为 DB 版本比对）—— 两条收敛通道都要通；</li>
 * <li>两者都<b>先</b>加载了空快照（与实测的 B/C 同形态），种子之后必须在一个 reload 周期内看到种子行。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * 去掉种子事务里的版本 +1（或提交后不发布 {@code ai:cfg:ver}）⇒ 对应用例的
 * {@code resolveChatTarget} 为空 ⇒ 红。no-op 种子也 bump ⇒ 版本抖动断言红。
 */
class AiConfigSeederConvergenceTest {

	private static final String SEEDED_MODEL = "deepseek-flash";

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final AtomicReference<String> redisVersion = new AtomicReference<>();

	private final AtomicInteger redisIncrements = new AtomicInteger();

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	@Test
	void seedPublishesTheConfigVersionSoAnotherInstanceConvergesToTheSeededProvider() {
		StringRedisTemplate redis = redis();
		// 实例 A（种子写入方）；实例 B 只比 DB 版本（ai:cfg:ver 为空）；实例 C 比 ai:cfg:ver
		AiConfigService seederSide = this.fixture.service();
		AiConfigService dbVersionReader = this.fixture.service();
		AiConfigService redisVersionReader = this.fixture.serviceWithRedis(redis);
		// 冷启动竞态：B/C 在种子提交之前就加载了快照 —— 此时快照里没有库行（对外是 env 合成行）
		assertThat(dbVersionReader.current().providers()).as("冷启动先加载：库里还没有 provider").isEmpty();
		assertThat(redisVersionReader.current().providers()).isEmpty();
		assertThat(this.fixture.version()).isEqualTo(1);
		assertThat(this.redisVersion.get()).as("种子前 ai:cfg:ver 不存在").isNull();

		assertThat(seeder(seederSide, redis).seed()).as("空表 + env key ⇒ 写入种子").isTrue();

		assertThat(this.fixture.version()).as("种子写与 config.version +1 必须同一事务（DB 是真源）")
			.isEqualTo(2);
		assertThat(this.redisVersion.get()).as("提交后必须发布 ai:cfg:ver（跨实例失效信号）").isEqualTo("1");

		Long seededProviderId = this.fixture.providers.get(0).getId();
		assertThat(seededProviderId).isNotNull();
		assertThat(dbVersionReader.resolveChatTarget(seededProviderId, SEEDED_MODEL))
			.as("另一个实例（只比 DB 版本）必须在一个 reload 周期内收敛到种子行")
			.isPresent();
		assertThat(redisVersionReader.resolveChatTarget(seededProviderId, SEEDED_MODEL))
			.as("另一个实例（比 ai:cfg:ver）必须在一个 reload 周期内收敛到种子行")
			.isPresent();
		assertThat(dbVersionReader.describeProviders())
			.as("收敛后的诊断视图必须出现库行（synthetic=false），不能只剩 env 合成行")
			.anySatisfy(row -> {
				assertThat(row.get("id")).isEqualTo(seededProviderId);
				assertThat(row.get("synthetic")).isEqualTo(false);
			});
	}

	@Test
	void noOpSeedDoesNotChurnTheVersion() {
		StringRedisTemplate redis = redis();
		AiConfigService seederSide = this.fixture.service();
		AiConfigSeeder seeder = seeder(seederSide, redis);
		assertThat(seeder.seed()).isTrue();
		int versionAfterSeed = this.fixture.version();
		int incrementsAfterSeed = this.redisIncrements.get();

		assertThat(seeder.seed()).as("表非空 ⇒ strict seed 不写").isFalse();
		assertThat(seeder.seed()).isFalse();

		assertThat(this.fixture.version()).as("no-op 不得让版本抖动（读侧会白重载一次全表）")
			.isEqualTo(versionAfterSeed);
		assertThat(this.redisIncrements.get()).as("no-op 不得发布失效信号").isEqualTo(incrementsAfterSeed);
	}

	@Test
	void seedForceRewritesAndBumpsTheVersionOnce() {
		StringRedisTemplate redis = redis();
		AiConfigService seederSide = this.fixture.service();
		this.fixture.properties.getConfig().setSeedForce(true);
		AiConfigSeeder seeder = seeder(seederSide, redis);
		assertThat(seeder.seed()).isTrue();
		int afterFirst = this.fixture.version();

		// seed-force 每次都重灌 ENV_SEED 行 ⇒ 数据确实变了 ⇒ 版本必须跟着动（否则读侧看不到）
		assertThat(seeder.seed()).isTrue();
		assertThat(this.fixture.version()).isEqualTo(afterFirst + 1);
		assertThat(this.redisIncrements.get()).isEqualTo(2);
		assertThat(this.fixture.providers).as("重灌不得产生重复行").hasSize(1);
	}

	// ── 复核 Important-1：count() 之后另一实例提交的交错 ─────────────────────────

	/**
	 * 复核的确定性交错：C 在 A 提交<b>之前</b>读到 {@code count()==0}，随后 A 完整种好并提交，
	 * C 才进事务（READ_COMMITTED 看得见 A 的行）。
	 *
	 * <p>旧实现会把同一份数据再"重灌"一遍并再 bump 一次版本（版本 +2 / INCR 两次 = 三实例白 reload 一轮）。
	 * 现在的口径：行已存在、`!force`、且这一版种子已完整落地 ⇒ 真 no-op。
	 */
	@Test
	void seedDoesNotPublishTwiceForNoOpOrConcurrentSeed() {
		StringRedisTemplate redis = redis();
		AiConfigService seederSide = this.fixture.service();
		AiConfigSeeder seeder = seeder(seederSide, redis);
		assertThat(seeder.seed()).as("A 先种好").isTrue();
		int versionAfterA = this.fixture.version();
		int incrementsAfterA = this.redisIncrements.get();
		assertThat(versionAfterA).as("A 种完：迁移常量 1 → 2（恰好一次）").isEqualTo(2);
		assertThat(incrementsAfterA).as("A 种完：失效信号恰好一次").isEqualTo(1);

		// C 的事务外判据在 A 提交之前就读到了 0
		when(this.fixture.providerRepository.count()).thenReturn(0L);

		boolean secondSeedWrote = seeder.seed();

		// 复核 M-3：先观测"版本/失效信号/行数"，再断言语义返回值 ——
		// 旧码在这里会直接打出 2 → 3、INCR 1 → 2（"重复 bump"被真正看见），而不是只看到 isFalse 短路。
		assertThat(this.fixture.version()).as("同一份数据不得再 bump 一次（旧码实测 2→3）")
			.isEqualTo(versionAfterA);
		assertThat(this.redisIncrements.get()).as("不得再发布一次失效信号（旧码实测 1→2）")
			.isEqualTo(incrementsAfterA);
		assertThat(this.fixture.providers).as("不得产生重复行").hasSize(1);
		assertThat(this.fixture.models).as("不得产生重复模型行").hasSize(1);
		assertThat(secondSeedWrote).as("另一实例已种好且与 env 逐值一致 ⇒ 真 no-op").isFalse();
	}

	/** 但"不跳过缺失的模型"：交错命中时若模型行不在（半途失败/人工删），必须补齐并 bump 一次。 */
	@Test
	void missingModelRowIsStillRepairedByTheInterleavedSeedRun() {
		StringRedisTemplate redis = redis();
		AiConfigService seederSide = this.fixture.service();
		AiConfigSeeder seeder = seeder(seederSide, redis);
		assertThat(seeder.seed()).isTrue();
		int versionAfterA = this.fixture.version();
		int incrementsAfterA = this.redisIncrements.get();
		this.fixture.models.clear();
		when(this.fixture.providerRepository.count()).thenReturn(0L);

		assertThat(seeder.seed()).as("模型行缺失 ⇒ 仍要补（不得因为 provider 行在就整体跳过）").isTrue();
		assertThat(this.fixture.models).as("模型行必须补回来").hasSize(1);
		assertThat(this.fixture.version()).isEqualTo(versionAfterA + 1);
		assertThat(this.redisIncrements.get()).isEqualTo(incrementsAfterA + 1);
	}

	/** 另一侧：env 真的变了（baseUrl 不同）⇒ 必须写进去并 bump 恰好一次。 */
	@Test
	void changedEnvIsAppliedOnceEvenWhenTheRowAppearedConcurrently() {
		StringRedisTemplate redis = redis();
		AiConfigService seederSide = this.fixture.service();
		AiConfigSeeder seeder = seeder(seederSide, redis);
		assertThat(seeder.seed()).isTrue();
		int versionAfterA = this.fixture.version();
		int incrementsAfterA = this.redisIncrements.get();

		this.fixture.connectionProperties.setBaseUrl("http://rotated-env:4321");
		when(this.fixture.providerRepository.count()).thenReturn(0L);

		assertThat(seeder.seed()).as("env 变了 ⇒ 不是 no-op").isTrue();
		assertThat(this.fixture.providers.get(0).getBaseUrl()).isEqualTo("http://rotated-env:4321");
		assertThat(this.fixture.version()).as("只 bump 一次").isEqualTo(versionAfterA + 1);
		assertThat(this.redisIncrements.get()).isEqualTo(incrementsAfterA + 1);
		assertThat(this.fixture.providers).hasSize(1);
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private AiConfigSeeder seeder(AiConfigService service, StringRedisTemplate redis) {
		when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		AiConfigVersionPublisher versions = new AiConfigVersionPublisher(this.fixture.settingRepository, redis,
				service, null);
		return new AiConfigSeeder(this.fixture.providerRepository, this.fixture.modelRepository, service,
				this.fixture.properties, this.transactionManager, versions);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private StringRedisTemplate redis() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		ValueOperations ops = mock(ValueOperations.class);
		when(template.opsForValue()).thenReturn(ops);
		when(ops.get(anyString())).thenAnswer(invocation -> this.redisVersion.get());
		when(ops.increment(anyString())).thenAnswer(invocation -> {
			this.redisIncrements.incrementAndGet();
			long next = this.redisVersion.get() == null ? 1L : Long.parseLong(this.redisVersion.get()) + 1L;
			this.redisVersion.set(String.valueOf(next));
			return next;
		});
		return template;
	}

}
