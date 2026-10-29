package com.example.configmgr.ai.session;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

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

}
