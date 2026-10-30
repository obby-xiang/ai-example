package com.example.configmgr.common.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 毫秒时间戳发号的单点契约：{@code nextId()} 直接返回 {@code System.currentTimeMillis()}。
 */
class TimestampIdGeneratorTest {

	@Test
	void nextIdIsTheCurrentEpochMillisecond() {
		TimestampIdGenerator generator = new TimestampIdGenerator();

		long before = System.currentTimeMillis();
		long id = generator.nextId();
		long after = System.currentTimeMillis();

		assertThat(id).as("nextId 必须直接取 System.currentTimeMillis()").isBetween(before, after);
	}

}
