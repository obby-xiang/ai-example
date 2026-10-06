package com.example.spike.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Spring Data Redis 普通数据结构实现的官方 {@link ChatMemoryRepository} SPI 扩展（SP-01c）。
 *
 * <h2>为什么实现这个 SPI，而不是用官方 Redis 实现</h2>
 * <ol>
 * <li><b>1.1.x 线没有官方实现</b>：Spring AI 1.1.8 的 BOM 虽然列出了构件
 * {@code spring-ai-starter-model-chat-memory-repository-redis}，但该构件在 Maven Central 的
 * 1.1.x 全线从未发布（实测 maven-metadata：官方 Redis ChatMemoryRepository 最早版本为
 * {@code 2.0.0-M1}）。本地 {@code ~/.m2} 解析该坐标只留下空目录，无任何 jar。1.1.8 用不了官方实现。</li>
 * <li><b>官方 2.0 实现本机跑不起来、且给生产加约束</b>：官方 2.0.x 实现依赖 RedisJSON
 * （{@code jsonSet}）与 RediSearch（{@code ftCreate}/{@code ftSearch}）两个 Redis 模块；
 * {@code <MEMURAI_HOME>} 实测 {@code module list} 仅有 {@code vectorset}，无 JSON/Search 模块，
 * 官方实现路线在本机不可运行，且要求生产 Redis 额外加载模块。</li>
 * </ol>
 * <p>
 * 因此按 DC-05「Spring AI 已有能力禁止自研；官方能力不存在时实现官方 SPI 扩展点」实现本类。
 * <b>对话循环与记忆挂载仍全部走官方</b>：{@code MessageWindowChatMemory}（窗口裁剪）+
 * {@code MessageChatMemoryAdvisor}（记忆读写时机）——本类只提供存储层。
 *
 * <h2>存储结构</h2>
 * <ul>
 * <li>{@code chat:mem:<conversationId>} — Redis LIST，每个元素是一条 JSON 消息
 * （格式见 {@link MessageJsonCodec}）。</li>
 * <li>{@code chat:mem:__ids__} — Redis SET，会话 id 索引，供 {@link #findConversationIds()} 使用；
 * 读取时惰性剔除已过期的会话 id（见 {@link #findConversationIds()}）。</li>
 * </ul>
 *
 * <h2>语义</h2>
 * <ul>
 * <li>{@link #saveAll} = {@code DEL} + 批量 {@code RPUSH} + {@code EXPIRE}，
 * 与官方 2.0 实现 {@code saveAll}（内部 clear + add）的"整窗口覆盖写"语义一致；
 * 每次写入刷新 TTL，即滑动过期。</li>
 * <li>{@link #findByConversationId} = {@code LRANGE 0 -1} + 反序列化。</li>
 * <li>{@link #deleteByConversationId} = {@code DEL} + 索引 {@code SREM}。</li>
 * </ul>
 *
 * <h2>已知未覆盖（见决策卡）</h2>
 * 无跨实例互斥：同一 conversationId 的并发写是"最后写入者胜"，未做乐观锁 / 分布式锁。
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

	public static final String KEY_PREFIX = "chat:mem:";

	static final String CONVERSATION_IDS_KEY = "chat:mem:__ids__";

	private final StringRedisTemplate redis;

	private final MessageJsonCodec codec;

	private volatile Duration ttl;

	public RedisChatMemoryRepository(StringRedisTemplate redis, MessageJsonCodec codec, Duration ttl) {
		this.redis = redis;
		this.codec = codec;
		this.ttl = ttl;
	}

	/** 运行期覆盖默认 TTL（V-c2 短 TTL 实验使用）。 */
	public void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	public Duration getTtl() {
		return this.ttl;
	}

	/** 暴露内部键名，供实验脚本核对键结构。 */
	public String keyFor(String conversationId) {
		return key(conversationId);
	}

	private String key(String conversationId) {
		return KEY_PREFIX + conversationId;
	}

	@Override
	public List<String> findConversationIds() {
		Set<String> stored = this.redis.opsForSet().members(CONVERSATION_IDS_KEY);
		if (stored == null || stored.isEmpty()) {
			return List.of();
		}
		List<String> live = new ArrayList<>(stored.size());
		List<String> expired = new ArrayList<>();
		for (String conversationId : stored) {
			if (Boolean.TRUE.equals(this.redis.hasKey(key(conversationId)))) {
				live.add(conversationId);
			}
			else {
				expired.add(conversationId);
			}
		}
		if (!expired.isEmpty()) {
			this.redis.opsForSet().remove(CONVERSATION_IDS_KEY, expired.toArray());
		}
		return live;
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		List<String> rawMessages = this.redis.opsForList().range(key(conversationId), 0, -1);
		if (rawMessages == null || rawMessages.isEmpty()) {
			return List.of();
		}
		return this.codec.deserializeAll(rawMessages);
	}

	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		String redisKey = key(conversationId);
		this.redis.delete(redisKey);
		if (messages == null || messages.isEmpty()) {
			this.redis.opsForSet().remove(CONVERSATION_IDS_KEY, conversationId);
			return;
		}
		this.redis.opsForList().rightPushAll(redisKey, this.codec.serializeAll(messages));
		applyTtl(redisKey);
		this.redis.opsForSet().add(CONVERSATION_IDS_KEY, conversationId);
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		this.redis.delete(key(conversationId));
		this.redis.opsForSet().remove(CONVERSATION_IDS_KEY, conversationId);
	}

	private void applyTtl(String redisKey) {
		Duration current = this.ttl;
		if (current != null && !current.isZero() && !current.isNegative()) {
			this.redis.expire(redisKey, current);
		}
	}

}
