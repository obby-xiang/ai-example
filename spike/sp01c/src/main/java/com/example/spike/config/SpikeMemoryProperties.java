package com.example.spike.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SP-01c 实验参数。TTL 运行期可通过 /api/dev/ttl 覆盖，用于 V-c2 短 TTL 到期实验。
 */
@ConfigurationProperties(prefix = "spike.chat-memory")
public class SpikeMemoryProperties {

	/** 会话键 TTL（滑动过期：每次写入刷新）。生产建议见决策卡"生产化注意事项"。 */
	private Duration ttl = Duration.ofHours(6);

	/** MessageWindowChatMemory 窗口大小，由官方实现负责裁剪。 */
	private int maxMessages = 40;

	public Duration getTtl() {
		return ttl;
	}

	public void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	public int getMaxMessages() {
		return maxMessages;
	}

	public void setMaxMessages(int maxMessages) {
		this.maxMessages = maxMessages;
	}

}
