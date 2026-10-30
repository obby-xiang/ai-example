package com.example.configmgr.common.id;

import org.springframework.stereotype.Component;

/**
 * 毫秒时间戳发号：{@code nextId() == System.currentTimeMillis()}。
 *
 * <p>这是当前阶段的通用实现：只保证号值随时间前进，不额外做实例位/序列/时钟回拨处理。
 * 同一毫秒内（或跨实例同一毫秒）可能取到相同号；会话登记侧以 {@code SET NX} 原子创建，
 * 撞号时保留旧会话、旧记录只续期，发号入口（{@code POST /api/ai/session}）据此
 * <b>有界重取号</b>（同毫秒并发是正常情况）；重取到上界仍撞号才回 503。
 * 需要更强唯一性时替换 {@link IdGenerator} 实现（入口不自行拼号/加序号）。
 */
@Component
public class TimestampIdGenerator implements IdGenerator {

	@Override
	public long nextId() {
		return System.currentTimeMillis();
	}

}
