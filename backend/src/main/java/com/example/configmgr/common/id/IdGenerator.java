package com.example.configmgr.common.id;

/**
 * 通用 ID 发号器。
 *
 * <p>调用方只依赖本接口；当前实现是 {@link TimestampIdGenerator}（毫秒时间戳）。
 * 分布式 ID（如雪花号）后续需要时替换实现即可，不改调用方。
 */
public interface IdGenerator {

	/** 取一个新号。 */
	long nextId();

}
