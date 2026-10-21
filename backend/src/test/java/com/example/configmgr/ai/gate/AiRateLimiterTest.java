package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiRateLimiter} 的窗口语义（批① 闸门 3）。断言打的是<b>脚本语义</b>：
 * {@link FakeRateRedis} 在 Java 侧逐行复刻 {@code -- RATE_SLIDING_WINDOW}，时间由
 * {@link FakeRateRedis#nowMs} 给出 ⇒ "窗口滑过即恢复"是确定性用例，不 sleep。
 *
 * <table>
 * <caption>矩阵</caption>
 * <tr><td>会话维度</td><td>窗口内 20 过、21 拒</td></tr>
 * <tr><td>滑动恢复</td><td>时间推进 &gt; 窗口 ⇒ 额度回来（不是"等到下一个整分钟"）</td></tr>
 * <tr><td>IP 维度</td><td>60 过、61 拒；换 sessionId 也绕不过</td></tr>
 * <tr><td>降级</td><td>Redis 宕机 ⇒ 放行 + degraded</td></tr>
 * <tr><td>键族</td><td>{@code ai:rate:session:*} / {@code ai:rate:ip:*}，带窗口 TTL</td></tr>
 * </table>
 */
class AiRateLimiterTest {

	private static final String IP = "10.1.2.3";

	private final AiProperties properties = new AiProperties();

	private final FakeRateRedis redis = new FakeRateRedis();

	private final AiRateLimiter limiter = new AiRateLimiter(this.redis.redis, this.properties);

	@Test
	void sessionAllowsTwentyPerWindowAndRejectsTheTwentyFirst() {
		for (int i = 1; i <= 20; i++) {
			assertThat(this.limiter.check("s-1", IP).allowed()).as("第 %d 次在额度内", i).isTrue();
		}

		AiRateLimiter.Decision rejected = this.limiter.check("s-1", IP);

		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.scope()).isEqualTo(AiRateLimiter.SCOPE_SESSION);
		assertThat(rejected.limit()).isEqualTo(20);
		assertThat(rejected.used()).as("窗口内计数停在额度上").isEqualTo(20);
		assertThat(rejected.retryAfterMs()).as("距最早一条滑出的毫秒数").isBetween(1L, 60_000L);
		assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);
		assertThat(this.redis.count(AiRateLimiter.SESSION_KEY_PREFIX + "s-1")).isEqualTo(20);
	}

	@Test
	void anotherSessionHasItsOwnBudget() {
		for (int i = 1; i <= 20; i++) {
			this.limiter.check("s-1", IP);
		}

		assertThat(this.limiter.check("s-1", IP).allowed()).isFalse();
		assertThat(this.limiter.check("s-2", IP).allowed()).as("会话维度按 sessionId 分开计").isTrue();
	}

	@Test
	void quotaRecoversWhenTheWindowSlides() {
		for (int i = 1; i <= 20; i++) {
			this.limiter.check("s-slide", IP);
		}
		assertThat(this.limiter.check("s-slide", IP).allowed()).isFalse();

		// 时间推进 30s：最早那批仍在窗口内
		this.redis.nowMs.addAndGet(Duration.ofSeconds(30).toMillis());
		assertThat(this.limiter.check("s-slide", IP).allowed()).as("窗口未滑过 ⇒ 仍拒").isFalse();

		// 再推进 31s（累计 61s > 60s 窗口）：全部旧成员出窗，额度回来
		this.redis.nowMs.addAndGet(Duration.ofSeconds(31).toMillis());
		assertThat(this.limiter.check("s-slide", IP).allowed()).as("窗口滑过 ⇒ 恢复（滑动窗口，不是整分钟翻转）")
				.isTrue();
		assertThat(this.redis.count(AiRateLimiter.SESSION_KEY_PREFIX + "s-slide")).as("旧成员已被回收").isEqualTo(1);
	}

	@Test
	void ipDimensionIsSixtyPerWindowAcrossSessions() {
		for (int i = 1; i <= 60; i++) {
			// 每次换一个 sessionId：证明"换会话绕不过 IP 额度"
			assertThat(this.limiter.check("s-ip-" + i, IP).allowed()).as("第 %d 次在 IP 额度内", i).isTrue();
		}

		AiRateLimiter.Decision rejected = this.limiter.check("s-ip-61", IP);

		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.scope()).as("被拒的是 IP 维度").isEqualTo(AiRateLimiter.SCOPE_IP);
		assertThat(rejected.limit()).isEqualTo(60);
	}

	@Test
	void degradesToAllowWhenRedisIsDown() {
		this.redis.down = true;

		AiRateLimiter.Decision decision = this.limiter.check("s-down", IP);

		assertThat(decision.allowed()).as("设施不可用 ⇒ 放行（降级不阻断业务）").isTrue();
		assertThat(decision.degraded()).isTrue();
	}

	@Test
	void usesTheAiRateKeyFamilyWithWindowTtl() {
		this.limiter.check("s-key", IP);

		assertThat(this.redis.zsets).containsKeys(AiRateLimiter.SESSION_KEY_PREFIX + "s-key",
				AiRateLimiter.IP_KEY_PREFIX + IP);
		assertThat(AiRateLimiter.SESSION_KEY_PREFIX).startsWith(AiRateLimiter.KEY_PREFIX);
		assertThat(AiRateLimiter.IP_KEY_PREFIX).startsWith(AiRateLimiter.KEY_PREFIX);
		assertThat(this.redis.expiries.get(AiRateLimiter.SESSION_KEY_PREFIX + "s-key"))
				.as("窗口键必须带 TTL（否则键面无限增长）").isEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void limitsAreConfigurable() {
		this.properties.getLimit().setSessionPerMinute(2);
		this.properties.getLimit().setIpPerMinute(5);

		assertThat(this.limiter.check("s-cfg", IP).allowed()).isTrue();
		assertThat(this.limiter.check("s-cfg", IP).allowed()).isTrue();
		AiRateLimiter.Decision rejected = this.limiter.check("s-cfg", IP);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.limit()).isEqualTo(2);
	}

}
