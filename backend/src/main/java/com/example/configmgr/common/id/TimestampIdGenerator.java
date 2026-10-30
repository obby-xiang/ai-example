package com.example.configmgr.common.id;

import org.springframework.stereotype.Component;

/**
 * 毫秒时间戳发号：{@code nextId() == System.currentTimeMillis()}。
 *
 * <p>这是当前阶段的通用实现：只保证号值随时间前进，不额外做实例位/序列/时钟回拨处理。
 * 同一毫秒内（或跨实例同一毫秒）可能取到相同号；会话登记侧以 {@code SET NX} 原子创建，
 * 撞号时保留旧会话并由入口返回 503。需要更强唯一性时替换 {@link IdGenerator} 实现。
 */
@Component
public class TimestampIdGenerator implements IdGenerator {

	@Override
	public long nextId() {
		return System.currentTimeMillis();
	}

}
