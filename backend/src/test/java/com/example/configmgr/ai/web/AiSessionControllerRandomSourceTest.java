package com.example.configmgr.ai.web;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>抖动源不得缓存进字段</b>（M-2）。
 *
 * <p>{@code ThreadLocalRandom.current()} 返回<b>线程局部</b>实例（JDK 文档："methods of this object
 * should be called only by the current thread"）。把它存进字段，等于让构造它的那条线程的生成器被
 * 所有请求线程共用 —— 正确性依赖 JDK"按线程号取种子"的实现细节，而不是接口语义。
 *
 * <p>本用例用反射钉住结构：控制器里不得出现 {@link RandomGenerator}（含
 * {@code ThreadLocalRandom}）类型的实例字段；抖动必须在使用点取。
 */
class AiSessionControllerRandomSourceTest {

	@Test
	void theControllerDoesNotCacheARandomSourceInAField() {
		assertThat(AiSessionController.class.getDeclaredFields())
			.as("ThreadLocalRandom 必须在使用点取（缓存字段会让别的线程用创建者的生成器）")
			.noneMatch(field -> RandomGenerator.class.isAssignableFrom(field.getType()));
	}

}
