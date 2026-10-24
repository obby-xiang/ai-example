package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 基于 Spring Data Redis 普通数据结构实现的官方 {@link ChatMemoryRepository} SPI 扩展（SP-01c）。
 *
 * <h2>为什么实现这个 SPI，而不是用官方 Redis 实现</h2>
 * <ol>
 * <li><b>1.1.x 线没有官方实现</b>：Spring AI 1.1.7 的 BOM 虽然列出了构件
 * {@code spring-ai-starter-model-chat-memory-repository-redis}，但该构件在 Maven Central 的
 * 1.1.x 全线从未发布（实测 maven-metadata：官方 Redis ChatMemoryRepository 最早版本为
 * {@code 2.0.0-M1}）。本地 {@code ~/.m2} 解析该坐标只留下空目录，无任何 jar。1.1.7 用不了官方实现。</li>
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
 * <h2>存储结构（Cluster 安全）</h2>
 * <ul>
 * <li>{@code chat:mem:{<escapedId>}} — Redis LIST，每个元素是一条 JSON 消息
 * （格式见 {@link MessageJsonCodec}）。花括号内是 Redis hash tag；id 中的 {@code %}/{@code {}/
 * {@code }} 先转义（{@code %}→{@code %25}、{@code {}→{@code %7B}、{@code }}→{@code %7D}），
 * 保证任意非空 id 的 tag 非空且无碰撞。</li>
 * <li>{@code chat:mem:staging:{<escapedId>}:<uuid>} — 非空 {@link #saveAll} 的临时 LIST，
 * 与 final 共享同一个 hash tag，因此 RENAME 同槽。</li>
 * <li>{@code chat:mem:<id>} — <b>迁移期 legacy 键</b>（无 tag，旧版本写入）。读侧 tagged 缺失时回落；
 * 保存成功 RENAME 后删除它，完成自然迁移。</li>
 * <li>{@code chat:mem:__ids__} — Redis SET，会话 id 索引，<b>刻意不加 hash tag</b>：给全局索引
 * 打 tag 会把所有会话压进同一个 slot（自造热点）。索引与数据键天然分属不同槽，因此不做跨键 Lua，
 * 只用单键命令序列。</li>
 * </ul>
 *
 * <h2>语义与失败安全</h2>
 * <ul>
 * <li><b>非空 {@link #saveAll} = staging 原子写入（RPUSH + PEXPIRE）+ {@code RENAME}</b>：
 * staging 写入走<b>单键</b> Lua，数据与 TTL 同时生效（finally 的 DEL 再失败也不会留下无 TTL 的孤儿）；
 * 随后 {@code RENAME staging final} 原子覆盖。RENAME 成功前旧历史一直可读；任一步失败只清理
 * staging，<b>绝不先删旧历史</b>。TTL 随 RENAME 继承到 final，仍为滑动过期。</li>
 * <li><b>索引后置</b>：RENAME 成功后才 {@code SADD chat:mem:__ids__}。索引更新失败只 WARN
 * （带 conversationId / 数据键，便于对账），<b>不回退已提交的新历史</b>；下一次成功保存会自愈。</li>
 * <li><b>删除 = tagged 单键 DEL + legacy 单键 DEL + 索引单键 SREM</b>：三条单键命令，任何一条都不跨槽；
 * SREM 让索引在删除时立即收敛，避免 {@code chat:mem:__ids__} 随会话数无界增长。SREM 失败只 WARN，
 * 数据删除结果不受影响。</li>
 * <li><b>空 / null 列表</b>等价于"清空该会话记忆"，委托 {@link #deleteByConversationId}。</li>
 * <li><b>空白 conversationId 拒绝</b>：空串 / 纯空白 / null 在触碰 Redis 前抛
 * {@link IllegalArgumentException}（空 id 会产生空 hash tag，Cluster 下 staging/final 不同槽）。</li>
 * </ul>
 *
 * <h2>全局索引的最终一致性</h2>
 * <ul>
 * <li><b>stale 成员</b>（SREM 失败、或数据 TTL 先到期）：{@link #findConversationIds()} 会把
 * tagged / legacy 都不存在的成员移除；{@link #isConversationIndexed(String)} 同时要求索引成员与
 * 任一数据键存活，因此删除后立即判为"不在索引"。</li>
 * <li><b>缺失成员</b>（RENAME 成功但 SADD 失败）：索引不会自动发现它；历史按 id 仍可读，
 * 下一次成功 {@link #saveAll} 会重新 SADD。日志中的 conversationId / 数据键即为对账线索。</li>
 * <li>调用方<strong>不需要额外 {@code clear()}</strong>：删除语义已含数据键清理与索引 SREM；索引
 * 异常由上述惰性路径/下次保存收敛。</li>
 * </ul>
 *
 * <h2>迁移注意</h2>
 * <p>新写入使用 tagged 键；旧 {@code chat:mem:<id>} 在迁移期仍可读。上线后首次成功保存会删除该会话的
 * legacy 键。若长期不保存，legacy 键仍随原 TTL 过期。本类不做批量迁移：旧索引成员在
 * {@link #findConversationIds()} 中只要 legacy 数据键还在就不会被当 stale 清掉。</p>
 *
 * <h2>已知未覆盖</h2>
 * <ul>
 * <li>无跨实例互斥：同一 conversationId 的并发写是"最后写入者胜"（staging 带 UUID，互不覆盖）；
 * 并发读只会看到旧窗或某个完整新窗。</li>
 * <li>客户端超时但服务端 RENAME 已成功：调用方看到失败且跳过 SADD，数据已提交、索引缺失，
 * 由下次保存自愈。</li>
 * <li>删除的 SREM 与并发保存的 SADD 仍有窄窗口：SREM 可能抹掉刚 SADD 的活成员，由下次保存自愈；
 * 不引入跨槽 Lua 换取索引的强一致。</li>
 * </ul>
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

	private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

	public static final String KEY_PREFIX = "chat:mem:";

	static final String CONVERSATION_IDS_KEY = "chat:mem:__ids__";

	/** staging 键前缀；与 final 键共享 {@code {<escapedId>}} hash tag。 */
	static final String STAGING_PREFIX = KEY_PREFIX + "staging:";

	/** staging 脚本标记（测试替身据此分派；不要改动与脚本正文不一致）。 */
	static final String STAGE_WRITE_MARK = "STAGE_WRITE";

	/**
	 * staging 原子写入：{@code RPUSH ... + PEXPIRE} 在同一条<b>单键</b>脚本里完成。
	 *
	 * <p>为什么不是 {@code RPUSH} 后再发 {@code EXPIRE}：两条命令之间进程崩溃、或 EXPIRE 失败后
	 * finally 的 DEL 也失败时，会留下一个无 TTL 的孤儿 staging 键。单键脚本不跨槽（Cluster 安全），
	 * 且数据与 TTL 同生同死。ARGV[1] = TTL 毫秒（0 表示不设 TTL），其余为消息 JSON。
	 */
	private static final RedisScript<Long> STAGE_WRITE = new DefaultRedisScript<>(
			"-- STAGE_WRITE\n" + "local ttl = tonumber(ARGV[1])\n"
					+ "for i = 2, #ARGV do redis.call('RPUSH', KEYS[1], ARGV[i]) end\n"
					+ "if ttl and ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end\n" + "return #ARGV - 1",
			Long.class);

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

	/**
	 * 暴露内部数据键名，供实验脚本核对键结构。
	 *
	 * <p>注意键形含 hash tag：{@code chat:mem:{<escapedId>}}（不是 {@code KEY_PREFIX + id}）。
	 */
	public String keyFor(String conversationId) {
		return key(conversationId);
	}

	/**
	 * <b>实际可读</b>的数据键：tagged 存活优先，其次迁移期 legacy，都不存在时回落 tagged（规范写入口）。
	 *
	 * <p>只用于"报告/诊断真实键"（如 {@code DELETE /history} 的 {@code memoryKey}）：迁移期旧会话可能
	 * 只有 legacy {@code chat:mem:<raw id>}，此时报 tagged 会指向一个不存在的键。两次单键 {@code EXISTS}，
	 * 不跨槽、不发 Lua。
	 */
	public String readableKeyFor(String conversationId) {
		String tagged = key(conversationId);
		if (Boolean.TRUE.equals(this.redis.hasKey(tagged))) {
			return tagged;
		}
		String legacy = legacyKey(conversationId);
		if (Boolean.TRUE.equals(this.redis.hasKey(legacy))) {
			return legacy;
		}
		return tagged;
	}

	private String key(String conversationId) {
		return KEY_PREFIX + "{" + escapedId(conversationId) + "}";
	}

	/**
	 * staging 键：{@code chat:mem:staging:{<escapedId>}:<uuid>}。
	 *
	 * <p>hash tag 必须放在 staging 前缀之后、uuid 之前 —— 这样它与 final 键
	 * {@code chat:mem:{<escapedId>}} 的 tag 逐字相同，{@code RENAME} 在 Cluster 下同槽。
	 */
	static String stagingKey(String conversationId, String token) {
		return STAGING_PREFIX + "{" + escapedId(conversationId) + "}:" + token;
	}

	/** 迁移期旧键形（无 hash tag，id 原样，不转义）。 */
	private static String legacyKey(String conversationId) {
		return KEY_PREFIX + requireId(conversationId);
	}

	private static String requireId(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			throw new IllegalArgumentException("conversationId 不得为空白");
		}
		return conversationId;
	}

	/**
	 * 无碰撞转义：先 {@code %}，再 {@code {} / {@code }}。
	 *
	 * <p>顺序不能反：先把 {@code %} 变成 {@code %25}，后续替换产生的 {@code %} 才不会被二次转义。
	 * 转义后 id 不含花括号，作为 hash tag 内容时非空且与 staging/final 一致。
	 */
	private static String escapedId(String conversationId) {
		return requireId(conversationId).replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
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
			// 旧版本可能写入过空白索引成员（新写入已拒绝空白 id）；不能让一个毒成员阻断整次枚举。
			if (conversationId == null || conversationId.isBlank()) {
				expired.add(conversationId);
				continue;
			}
			if (hasLiveMemory(conversationId)) {
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

	/**
	 * 该会话是否"仍在索引且仍有存活数据"——{@code SISMEMBER} + 数据键 {@code EXISTS}。
	 *
	 * <p>存在的理由：{@link #findConversationIds()} 是 SPI 契约「列出**全部**存活 id」，
	 * 实现是 {@code SMEMBERS} + 逐 id 一次 {@code EXISTS} 的惰性清理；调用方若只想判定**某一个** id
	 * （如 {@code DELETE /api/ai/history/{sessionId}} 的 {@code removedFromIndex}），
	 * 用它会带来 N 倍 Redis 往返。本方法是那种"单点判定"的正解，不触发任何清理副作用。
	 *
	 * <p><b>语义</b>：索引成员命中且 tagged 或 legacy 数据键存活才为 true。正常删除会同时
	 * {@code DEL + SREM}，所以删除后立即为 false；SREM 失败或数据 TTL 先到期时，靠这里的
	 * {@code EXISTS} 兜住"索引成员还在但数据已没了"的 stale 事实。这比单纯 {@code SISMEMBER}
	 * 更符合调用方字段 {@code removedFromIndex} 的语义（它要的是"是否仍可列/仍存活"）。
	 */
	public boolean isConversationIndexed(String conversationId) {
		if (!Boolean.TRUE.equals(this.redis.opsForSet().isMember(CONVERSATION_IDS_KEY, conversationId))) {
			return false;
		}
		return hasLiveMemory(conversationId);
	}

	private boolean hasLiveMemory(String conversationId) {
		return Boolean.TRUE.equals(this.redis.hasKey(key(conversationId)))
				|| Boolean.TRUE.equals(this.redis.hasKey(legacyKey(conversationId)));
	}

	/**
	 * 读会话历史：tagged 优先；tagged 不存在时回落迁移期 legacy 键。
	 *
	 * <p>不回写、不迁移 —— 迁移发生在下一次成功 {@link #saveAll} 之后（RENAME 成功后删除 legacy）。
	 */
	@Override
	public List<Message> findByConversationId(String conversationId) {
		String taggedKey = key(conversationId);
		List<String> rawMessages = this.redis.opsForList().range(taggedKey, 0, -1);
		if (rawMessages == null || rawMessages.isEmpty()) {
			rawMessages = this.redis.opsForList().range(legacyKey(conversationId), 0, -1);
		}
		if (rawMessages == null || rawMessages.isEmpty()) {
			return List.of();
		}
		return this.codec.deserializeAll(rawMessages);
	}

	/**
	 * 整窗口覆盖写。
	 *
	 * <p>非空路径先在<b>单键脚本</b>里把新窗口写入与 final 同槽的 staging 并设置 TTL，再
	 * {@code RENAME} 原子覆盖 final —— 任一步失败都只清理 staging，旧历史保持可读。
	 * RENAME 成功后才删除 legacy（自然迁移）并更新全局索引；索引失败只 WARN，不回退已提交的新历史。
	 *
	 * <p>空（或 null，防御）列表等价于"清空该会话记忆"：委托 {@link #deleteByConversationId}
	 * （tagged/legacy 各一条单键 DEL，再单键 SREM）。
	 */
	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		String finalKey = key(conversationId); // 先校验 id：空白 id 在序列化/Redis 之前失败
		String legacyKey = legacyKey(conversationId);
		if (messages == null || messages.isEmpty()) {
			deleteByConversationId(conversationId);
			return;
		}
		// 先序列化：序列化失败没有任何 Redis 副作用
		List<String> payload = this.codec.serializeAll(messages);
		String stagingKey = stagingKey(conversationId, UUID.randomUUID().toString());
		boolean renamed = false;
		try {
			Object[] args = new Object[payload.size() + 1];
			args[0] = Long.toString(stagingTtlMillis());
			for (int i = 0; i < payload.size(); i++) {
				args[i + 1] = payload.get(i);
			}
			this.redis.execute(STAGE_WRITE, List.of(stagingKey), args);
			this.redis.rename(stagingKey, finalKey);
			renamed = true;
		}
		finally {
			if (!renamed) {
				deleteStagingQuietly(stagingKey);
			}
		}
		deleteLegacyQuietly(legacyKey, conversationId);
		addToIndex(conversationId, finalKey);
	}

	/**
	 * 删除会话历史：tagged 单键 DEL、legacy 单键 DEL、索引单键 SREM。
	 *
	 * <p>不再把数据键与全局索引合成 Lua：两者不同槽，Cluster 下整条脚本 {@code CROSSSLOT}。
	 * SREM 让索引立即收敛；SREM 失败只 WARN（数据已删，索引 stale 由
	 * {@link #findConversationIds()} / {@link #isConversationIndexed(String)} 兜住）。
	 */
	@Override
	public void deleteByConversationId(String conversationId) {
		this.redis.delete(key(conversationId));
		this.redis.delete(legacyKey(conversationId));
		removeFromIndexQuietly(conversationId);
	}

	/**
	 * 索引更新：辅助面，失败不影响已提交的历史。
	 *
	 * <p>失败时打一条带 conversationId 与数据键的 WARN（可观测 / 可对账）；下一次成功
	 * {@link #saveAll} 会重新 {@code SADD}。不抛异常，避免调用方误以为历史写失败而重试/回滚。
	 */
	private void addToIndex(String conversationId, String finalKey) {
		try {
			this.redis.opsForSet().add(CONVERSATION_IDS_KEY, conversationId);
		}
		catch (RuntimeException ex) {
			log.warn("会话记忆已写入但全局索引更新失败（历史按 id 仍可读；findConversationIds 暂不可见，"
					+ "下次成功保存自愈）conversationId={} key={}", conversationId, finalKey, ex);
		}
	}

	/** SREM 失败不改变"数据已删除"的事实，只留下可由惰性路径/人工对账的 stale 成员。 */
	private void removeFromIndexQuietly(String conversationId) {
		try {
			this.redis.opsForSet().remove(CONVERSATION_IDS_KEY, conversationId);
		}
		catch (RuntimeException ex) {
			log.warn("会话历史已删除但索引 SREM 失败（stale 成员待惰性清理/对账）conversationId={} index={}",
					conversationId, CONVERSATION_IDS_KEY, ex);
		}
	}

	/** legacy 是迁移期残留；清理失败不影响新历史，留 WARN 供对账。 */
	private void deleteLegacyQuietly(String legacyKey, String conversationId) {
		try {
			this.redis.delete(legacyKey);
		}
		catch (RuntimeException ex) {
			log.warn("新历史已提交但 legacy 键清理失败（旧键待 TTL/人工清理）conversationId={} legacyKey={}",
					conversationId, legacyKey, ex);
		}
	}

	private void deleteStagingQuietly(String stagingKey) {
		try {
			this.redis.delete(stagingKey);
		}
		catch (RuntimeException ex) {
			// TTL 已在单键脚本里与数据同时设置；这里再兜一层，避免失败路径留下无主 staging
			log.warn("清理 staging 键失败（staging 仍带 TTL，可按此键对账/删除）stagingKey={}", stagingKey, ex);
		}
	}

	private long stagingTtlMillis() {
		Duration current = this.ttl;
		if (current == null || current.isZero() || current.isNegative()) {
			return 0L;
		}
		return Math.max(1L, current.toMillis());
	}

}
