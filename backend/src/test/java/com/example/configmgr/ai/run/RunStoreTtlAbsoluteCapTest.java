package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3-8 用例：<b>会话 TTL 的绝对上限</b>（锚点 = 该轮的 {@code createdAtMs}）。
 *
 * <h2>实测结论（本棒）</h2>
 * 现有滑动 TTL 的续期语义<b>会突破</b>"创建时刻 + session-ttl"：7 个续期点原本都把 TTL 整额重置，
 * 于是"每 2s 心跳一次的挂起轮"可以无限续命（Redis 层的等比复现见施工报告 §T3-8）。
 * 故按 A15 落地"剩余 TTL = min(sessionTtl, createdAt + sessionTtl − now)"。
 *
 * <h2>本用例锁定三层</h2>
 * <ol>
 * <li>纯函数 {@code remainingTtl} 的四个边界（正常 / 恰好耗尽 / 已过 / 无锚点）；</li>
 * <li>公开路径 {@code touchActivity}（心跳）真的按锚点收缩 TTL（不是只改了纯函数）；</li>
 * <li>续期不会把已过上限的键"续活"（回落 {@link RunStore#EXPIRED_RENEWAL}）。</li>
 * </ol>
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
class RunStoreTtlAbsoluteCapTest {

	private final Map<String, String> strings = new HashMap<>();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = new RunStore(this.redis, this.mapper, this.properties);

	RunStoreTtlAbsoluteCapTest() {
		when(this.redis.opsForValue()).thenReturn(this.valueOps);
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));
		when(this.valueOps.get(anyString())).thenAnswer(invocation -> this.strings.get(invocation.getArgument(0)));
	}

	// ── ① 纯函数边界 ──

	@Test
	void remainingTtlTakesTheSmallerOfSlidingTtlAndAbsoluteCap() {
		Duration sixHours = Duration.ofHours(6);
		long now = 1_000_000_000L;

		// 刚创建：整额（滑动 TTL 未触顶）
		assertThat(RunStore.remainingTtl(now, now, sixHours)).isEqualTo(sixHours);
		// 已过 1 小时：剩余 5 小时 < 6 小时 → 取剩余
		assertThat(RunStore.remainingTtl(now, now + Duration.ofHours(1).toMillis(), sixHours))
				.isEqualTo(Duration.ofHours(5));
		// 剩余恰 1 秒
		assertThat(RunStore.remainingTtl(now, now + sixHours.toMillis() - 1000, sixHours))
				.isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void remainingTtlAtOrPastTheCapBecomesNoRenewal() {
		Duration sixHours = Duration.ofHours(6);
		long created = 2_000_000_000L;

		// 恰好到上限（剩余 = 0）
		assertThat(RunStore.remainingTtl(created, created + sixHours.toMillis(), sixHours))
				.as("剩余 0 ⇒ 不再续期")
				.isEqualTo(RunStore.EXPIRED_RENEWAL);
		// 已过上限
		assertThat(RunStore.remainingTtl(created, created + sixHours.toMillis() + 1, sixHours))
				.isEqualTo(RunStore.EXPIRED_RENEWAL);
		assertThat(RunStore.EXPIRED_RENEWAL).as("Redis 不接受非正 TTL ⇒ 回落最小正 TTL").isPositive();
	}

	@Test
	void remainingTtlFallsBackWhenAnchorOrTtlIsMissing() {
		Duration sixHours = Duration.ofHours(6);
		long now = 3_000_000_000L;

		assertThat(RunStore.remainingTtl(0L, now, sixHours)).as("无创建时刻（旧数据）→ 不制造立刻过期")
				.isEqualTo(sixHours);
		assertThat(RunStore.remainingTtl(-1L, now, sixHours)).isEqualTo(sixHours);
		assertThat(RunStore.remainingTtl(now, now + Duration.ofDays(1).toMillis(), null)).isNull();
		assertThat(RunStore.remainingTtl(now, now + Duration.ofDays(1).toMillis(), Duration.ZERO)).isEqualTo(Duration.ZERO);
	}

	// ── ② 公开路径（心跳 touchActivity）按锚点收缩 TTL ──

	@Test
	void touchActivityRenewsWithTheRemainingAmountNotTheFullSlidingTtl() {
		String runId = "run-ttl-age";
		long created = System.currentTimeMillis() - Duration.ofHours(5).toMillis() - Duration.ofMinutes(30).toMillis();
		seedSnapshot(runId, created);

		this.store.touchActivity(runId);

		Duration renewed = capturedTtl(this.store.beatKey(runId));
		assertThat(renewed)
				.as("心跳把 TTL 收缩到 创建+6h 的剩余量（约 30 分钟），而不是整额 6 小时")
				.isLessThanOrEqualTo(Duration.ofMinutes(30).plusSeconds(2))
				.isGreaterThan(Duration.ofMinutes(29));
	}

	@Test
	void renewalPastTheCapDoesNotResurrectTheRun() {
		String runId = "run-ttl-expired";
		seedSnapshot(runId, System.currentTimeMillis() - Duration.ofHours(7).toMillis());

		this.store.touchActivity(runId);

		assertThat(capturedTtl(this.store.beatKey(runId)))
				.as("已过绝对上限 ⇒ 只给最小正 TTL（键随即过期），绝不续 6 小时")
				.isEqualTo(RunStore.EXPIRED_RENEWAL);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private void seedSnapshot(String runId, long createdAtMs) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId("s-" + runId);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setCreatedAtMs(createdAtMs);
		try {
			this.strings.put(this.store.runKey(runId), this.mapper.writeValueAsString(snapshot));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private Duration capturedTtl(String key) {
		ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
		org.mockito.Mockito.verify(this.valueOps).set(org.mockito.ArgumentMatchers.eq(key), anyString(), captor.capture());
		return captor.getValue();
	}
}
