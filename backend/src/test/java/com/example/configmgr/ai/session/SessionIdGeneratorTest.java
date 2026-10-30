package com.example.configmgr.ai.session;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后端会话发号（{@link SessionIdGenerator}）的唯一性单点。
 *
 * <p>
 * 口径：{@code 时间戳(ms) × 10^6 + 实例位(0..999) × 10^3 + 实例内序列(0..999)}。
 * 两条必须成立：
 * <ul>
 * <li><b>同毫秒不重复</b>：同一实例在同一毫秒内连续取号，序列位递增；</li>
 * <li><b>多实例不重复</b>：两个实例位在同一毫秒各取号也必须不同 —— 仅靠时间戳不够。</li>
 * </ul>
 * 因此前端不再生成 sessionId，取号只能走后端。
 */
class SessionIdGeneratorTest {

	@Test
	void sameMillisecondIdsDoNotRepeatWithinOneInstance() {
		AtomicLong clock = new AtomicLong(1_700_000_000_000L);
		SessionIdGenerator generator = new SessionIdGenerator(clock::get, 7);

		Set<Long> ids = new HashSet<>();
		long first = generator.next();
		long second = generator.next();
		long third = generator.next();

		assertThat(first).as("sessionId 必须是正 long").isPositive();
		assertThat(ids.add(first)).isTrue();
		assertThat(ids.add(second)).as("同一毫秒内第二次取号不得重复").isTrue();
		assertThat(ids.add(third)).as("同一毫秒内第三次取号不得重复").isTrue();
	}

	@Test
	void differentInstancesInTheSameMillisecondDoNotRepeat() {
		long fixedNow = 1_700_000_000_123L;
		SessionIdGenerator instanceA = new SessionIdGenerator(() -> fixedNow, 7);
		SessionIdGenerator instanceB = new SessionIdGenerator(() -> fixedNow, 8);

		assertThat(instanceA.next()).as("两个实例位在同一毫秒的首号必须不同")
			.isNotEqualTo(instanceB.next());
		assertThat(instanceA.next()).isNotEqualTo(instanceB.next());
	}

	@Test
	void aLargeClockRollbackFailsFastWithAnUnavailableSignal() {
		AtomicLong clock = new AtomicLong(1_700_000_000_000L);
		SessionIdGenerator generator = new SessionIdGenerator(clock::get, 7);
		generator.next();

		clock.addAndGet(-5_000L);

		assertThatThrownBy(generator::next)
			.as("大步回拨（>1s）不得占锁自旋，必须以可映射 503 的异常失败")
			.isInstanceOf(SessionIdGenerator.UnavailableException.class)
			.hasMessageContaining("时钟回拨");
	}

	@Test
	void explicitMultiInstanceFlagWithoutSlotFailsClosed() {
		MockEnvironment multiInstance = new MockEnvironment();
		multiInstance.setProperty("app.file.multi-instance", "true");

		assertThatThrownBy(() -> new SessionIdGenerator(multiInstance))
			.as("显式多实例开关（没有多实例 profile）也必须要求实例位")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_INSTANCE_SLOT")
			.hasMessageContaining("app.file.multi-instance=true");

		multiInstance.setProperty("app.instance-slot", "5");
		assertThat(new SessionIdGenerator(multiInstance).instanceSlot()).isEqualTo(5L);
	}

	@Test
	void sharedDatasourceWithoutProfileAlsoRequiresAnExplicitInstanceSlot() {
		MockEnvironment h2tcp = datasource("jdbc:h2:tcp://127.0.0.1:9092/./config_mgr");
		assertThatThrownBy(() -> new SessionIdGenerator(h2tcp))
			.as("H2 TCP 共享库本身就是多实例，未配置实例位必须启动失败")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_INSTANCE_SLOT")
			.hasMessageContaining("jdbc:h2:tcp:");

		MockEnvironment h2ssl = datasource("jdbc:h2:ssl://127.0.0.1:9092/./config_mgr");
		assertThatThrownBy(() -> new SessionIdGenerator(h2ssl)).as("H2 SSL 远程库同样算多实例")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jdbc:h2:ssl:");

		MockEnvironment postgresql = datasource("jdbc:postgresql://db:5432/config_mgr");
		assertThatThrownBy(() -> new SessionIdGenerator(postgresql)).as("PostgreSQL 共享库同样算多实例")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jdbc:postgresql:");

		MockEnvironment localFile = datasource("jdbc:h2:file:./data/config_mgr_db");
		assertThat(new SessionIdGenerator(localFile).instanceSlot()).as("本地文件库仍是单实例，默认实例位 0").isZero();

		MockEnvironment configured = datasource("jdbc:h2:tcp://127.0.0.1:9092/./config_mgr");
		configured.setProperty("app.instance-slot", "9");
		assertThat(new SessionIdGenerator(configured).instanceSlot()).isEqualTo(9L);
	}

	@Test
	void clusterDeploymentRequiresAnExplicitAuditableInstanceSlot() {
		MockEnvironment cluster = new MockEnvironment();
		cluster.setActiveProfiles("cluster");

		assertThatThrownBy(() -> new SessionIdGenerator(cluster))
			.as("集群/多实例未配置实例位必须启动失败，不得用随机 InstanceId 散列兜底")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_INSTANCE_SLOT");

		MockEnvironment singleInstance = new MockEnvironment();
		assertThat(new SessionIdGenerator(singleInstance).instanceSlot()).as("单实例默认实例位 0").isZero();

		MockEnvironment configured = new MockEnvironment();
		configured.setActiveProfiles("cluster");
		configured.setProperty("app.instance-slot", "3");
		assertThat(new SessionIdGenerator(configured).instanceSlot()).isEqualTo(3L);
	}

	private static MockEnvironment datasource(String url) {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("spring.datasource.url", url);
		return environment;
	}

}
