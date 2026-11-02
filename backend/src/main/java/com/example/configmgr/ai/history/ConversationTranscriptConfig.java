package com.example.configmgr.ai.history;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 用户展示历史（transcript）的装配（B2）。
 *
 * <p>与记忆窗口共用 {@code app.ai.session-ttl}；不新增数据库表。append 使用同一 hash tag 内的
 * 4 键（data / {@code :bytes} / {@code :ready} / {@code :truncated}）单槽脚本，无跨槽操作。
 */
@Configuration
public class ConversationTranscriptConfig {

	@Bean
	public ConversationTranscriptStore conversationTranscriptStore(StringRedisTemplate redisTemplate,
			ObjectMapper objectMapper, AiProperties properties) {
		return new ConversationTranscriptStore(redisTemplate, objectMapper, properties.getSessionTtl());
	}

}
