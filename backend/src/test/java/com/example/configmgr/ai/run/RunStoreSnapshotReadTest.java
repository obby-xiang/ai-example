package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快照读取的<b>异常分类</b>（P1-1）：基础设施读失败 = typed 503，值损坏 = 数据错误（{@link IllegalStateException}）。
 *
 * <h2>为什么这条必须单独锁</h2>
 * {@code RunStore#get} 的 Redis {@code GET} 原先在 {@code try} 之外：连接失败时抛的是 Spring 的
 * {@link RedisConnectionFailureException}，落到全局兜底 ⇒ <b>500</b>；而 {@code /events/{runId}}
 * 与 {@code /runs/current} 的第一跳都是它，于是"Redis 抖一下"在客户端表现为服务端错误。
 * 正确的分类是：<b>依赖不可用</b>走 {@link AiStateUnavailableException}（{@code AI_REDIS_UNAVAILABLE} ⇒ 503，
 * 前端可重试），<b>值损坏</b>仍是 {@link IllegalStateException}（那是数据问题，不该报成"稍后重试"）。
 *
 * <p>本类同时覆盖两条产生路径：① 模板调用直接抛（真实 {@code RunStore} 代码路径，只是把 Redis 客户端
 * 换成会抛的桩）；② {@link FakeLeaseRedis#goDown()} 让<b>整条命令面</b>抛（不逐方法打桩，等价于"Redis 真的不可用"）。
 * 注意它<b>不是</b>把 {@code RunStore#get} 本身 mock 成抛异常 —— 被测的就是 get 的实现。
 */
class RunStoreSnapshotReadTest {

	private static final String RUN_ID = "run-snapshot-read";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	/** ① 连接失败（真实 Lettuce 异常类型）⇒ typed 503，且原因保留。 */
	@Test
	void infraReadFailureBecomesTypedUnavailable() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("boom"));
		RunStore store = new RunStore(redis, this.mapper, this.properties);

		assertThatThrownBy(() -> store.get(RUN_ID))
			.as("依赖不可用必须转成 AiStateUnavailableException（否则端点 500）")
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining(RUN_ID)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	/** ② Redis 整体不可用（替身全命令抛）—— 同样走 get 的实现，不 mock get 本身。 */
	@Test
	void redisDownThroughTheRealCallPathIsAlsoTyped() {
		this.fake.goDown();
		RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

		assertThatThrownBy(() -> store.get(RUN_ID))
			.isInstanceOf(AiStateUnavailableException.class)
			.hasCauseInstanceOf(IllegalStateException.class);
	}

	/** ③ 值损坏是数据问题：仍是 IllegalStateException，<b>不得</b>被报成 503。 */
	@Test
	void corruptedJsonStaysIllegalStateAndIsNotUnavailable() {
		RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);
		this.fake.strings.put(store.runKey(RUN_ID), "{not-json");

		assertThatThrownBy(() -> store.get(RUN_ID))
			.isInstanceOf(IllegalStateException.class)
			.isNotInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining(RUN_ID);
	}

	/** ④ 键不存在仍是 null（分类修复不得把"没有"变成"不可用"）。 */
	@Test
	void missingSnapshotIsStillNull() {
		RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

		assertThat(store.get(RUN_ID)).isNull();
	}

}
