package com.example.configmgr.ai.session;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话销毁用例的进程内 Redis 替身（只建模产品代码实际发出的命令）。
 *
 * <p>为什么不直接用 {@code mock(StringRedisTemplate.class)}：销毁编排要跨
 * SET / HASH / LIST / DEL / SCAN 多类命令维护<b>跨命令的状态</b>（先枚举、再删除），
 * 逐调用打桩会把用例写成"我的假设"而不是"存储语义"。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
final class PurgeFakeRedis {

	final Map<String, String> strings = new ConcurrentHashMap<>();

	final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();

	final Map<String, List<String>> lists = new ConcurrentHashMap<>();

	final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

	final Map<String, Duration> ttls = new ConcurrentHashMap<>();

	/** 被删除过的键（按调用顺序；用于断言"只删了该删的"）。 */
	final List<String> deleted = new ArrayList<>();

	volatile boolean failDelete;

	/**
	 * 只让<b>包含该子串</b>的键 DEL 失败（{@code null} = 关闭）。
	 *
	 * <p>为什么不只用 {@link #failDelete}：整面失败会让"按 run 删 13 键"先抛错，
	 * 后续的观测键删除根本走不到 —— 那样就测不到"某个具体键删不掉"这条可重试语义。
	 */
	volatile String failDeleteContaining;

	volatile boolean failScan;

	final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	final RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

	final RedisConnection standaloneConnection = mock(RedisConnection.class);

	private final RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);

	final RedisClusterConnection clusterConnection = mock(RedisClusterConnection.class);

	/** cluster 模式 = factory 返回 RedisClusterConnection；默认 standalone。 */
	volatile boolean clusterMode;

	/** cluster masters（顺序即扫描顺序）。 */
	final List<RedisClusterNode> clusterMasters = new CopyOnWriteArrayList<>();

	/** 每个 master 的键（模拟 Cluster 分片；未分配的键不会被该节点扫到）。 */
	final Map<RedisClusterNode, Set<String>> clusterNodeKeys = new java.util.IdentityHashMap<>();

	/** 观测：连接/cursor 是否被关闭。 */
	final AtomicInteger standaloneConnectionCloses = new AtomicInteger();

	final AtomicInteger clusterConnectionCloses = new AtomicInteger();

	final List<RedisClusterNode> closedNodeCursors = new CopyOnWriteArrayList<>();

	volatile int standaloneCursorCloses;

	/** 指定该 master 扫描抛错（模拟单节点失败）。 */
	volatile RedisClusterNode failNode;

	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private final ListOperations<String, String> listOps = mock(ListOperations.class);

	PurgeFakeRedis() {
		when(this.redis.opsForValue()).thenReturn(this.values);
		when(this.redis.opsForHash()).thenReturn(this.hashOps);
		when(this.redis.opsForSet()).thenReturn(this.setOps);
		when(this.redis.opsForList()).thenReturn(this.listOps);

		when(this.values.get(anyString())).thenAnswer(invocation -> this.strings.get(invocation.getArgument(0)));
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			Duration ttl = invocation.getArgument(2);
			if (ttl != null) {
				this.ttls.put(invocation.getArgument(0), ttl);
			}
			return null;
		}).when(this.values).set(anyString(), anyString(), any(Duration.class));
		when(this.values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			if (this.strings.containsKey(key)) {
				return false;
			}
			this.strings.put(key, invocation.getArgument(1));
			return true;
		});
		doAnswer(invocation -> {
			this.strings.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(this.values).set(anyString(), anyString());

		when(this.hashOps.entries(anyString())).thenAnswer(invocation -> {
			Map<String, String> table = this.hashes.get(invocation.getArgument(0));
			return table == null ? Map.of() : new LinkedHashMap<Object, Object>(table);
		});
		when(this.hashOps.get(anyString(), any())).thenAnswer(invocation -> {
			Map<String, String> table = this.hashes.get(invocation.getArgument(0));
			return table == null ? null : table.get(String.valueOf((Object) invocation.getArgument(1)));
		});
		doAnswer(invocation -> {
			this.hashes.computeIfAbsent(invocation.getArgument(0), ignored -> new ConcurrentHashMap<>())
				.put(String.valueOf((Object) invocation.getArgument(1)), String.valueOf((Object) invocation.getArgument(2)));
			return null;
		}).when(this.hashOps).put(anyString(), any(), any());
		when(this.hashOps.putIfAbsent(anyString(), any(), any())).thenAnswer(invocation -> {
			Map<String, String> table = this.hashes.computeIfAbsent(invocation.getArgument(0),
					ignored -> new ConcurrentHashMap<>());
			return table.putIfAbsent(String.valueOf((Object) invocation.getArgument(1)),
					String.valueOf((Object) invocation.getArgument(2))) == null;
		});

		when(this.listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			if (list == null || list.isEmpty()) {
				return List.of();
			}
			long start = invocation.getArgument(1);
			long stop = invocation.getArgument(2);
			synchronized (list) {
				int size = list.size();
				int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
				int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
				if (from > to || from >= size) {
					return List.of();
				}
				return new ArrayList<>(list.subList(from, to + 1));
			}
		});
		when(this.listOps.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
			List<String> list = this.lists.computeIfAbsent(invocation.getArgument(0),
					ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
			list.add(invocation.getArgument(1));
			return (long) list.size();
		});
		when(this.listOps.leftPush(anyString(), anyString())).thenAnswer(invocation -> {
			List<String> list = this.lists.computeIfAbsent(invocation.getArgument(0),
					ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
			list.add(0, invocation.getArgument(1));
			return (long) list.size();
		});
		when(this.listOps.remove(anyString(), anyLong(), any())).thenAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			if (list == null) {
				return 0L;
			}
			long count = invocation.getArgument(1);
			String value = String.valueOf((Object) invocation.getArgument(2));
			synchronized (list) {
				long removed = 0L;
				if (count == 0L) {
					while (list.remove(value)) {
						removed++;
					}
				}
				else if (count > 0L) {
					Iterator<String> iterator = list.iterator();
					while (iterator.hasNext() && removed < count) {
						if (value.equals(iterator.next())) {
							iterator.remove();
							removed++;
						}
					}
				}
				else {
					java.util.Collections.reverse(list);
					Iterator<String> iterator = list.iterator();
					while (iterator.hasNext() && removed < -count) {
						if (value.equals(iterator.next())) {
							iterator.remove();
							removed++;
						}
					}
					java.util.Collections.reverse(list);
				}
				return removed;
			}
		});
		doAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			if (list == null) {
				return null;
			}
			long start = invocation.getArgument(1);
			long stop = invocation.getArgument(2);
			synchronized (list) {
				int size = list.size();
				int from = (int) (start < 0 ? Math.max(0, size + start) : Math.min(start, size));
				int to = (int) (stop < 0 ? size + stop : Math.min(stop, size - 1));
				List<String> kept = from > to || from >= size ? new ArrayList<>()
						: new ArrayList<>(list.subList(from, to + 1));
				list.clear();
				list.addAll(kept);
			}
			return null;
		}).when(this.listOps).trim(anyString(), anyLong(), anyLong());
		when(this.listOps.size(anyString())).thenAnswer(invocation -> {
			List<String> list = this.lists.get(invocation.getArgument(0));
			return list == null ? 0L : (long) list.size();
		});

		when(this.setOps.members(anyString())).thenAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			return members == null ? Set.of() : new LinkedHashSet<>(members);
		});
		when(this.setOps.isMember(anyString(), (Object) any())).thenAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			return members != null && members.contains(String.valueOf((Object) invocation.getArgument(1)));
		});
		doAnswer(invocation -> {
			Set<String> members = this.sets.computeIfAbsent(invocation.getArgument(0),
					ignored -> ConcurrentHashMap.newKeySet());
			long added = 0L;
			for (Object value : tail(invocation.getArguments(), 1)) {
				if (members.add(String.valueOf(value))) {
					added++;
				}
			}
			return added;
		}).when(this.setOps).add(anyString(), any(String[].class));
		doAnswer(invocation -> {
			Set<String> members = this.sets.get(invocation.getArgument(0));
			if (members == null) {
				return 0L;
			}
			long removed = 0L;
			for (Object value : tail(invocation.getArguments(), 1)) {
				if (members.remove(String.valueOf(value))) {
					removed++;
				}
			}
			if (members.isEmpty()) {
				this.sets.remove(invocation.getArgument(0));
			}
			return removed;
		}).when(this.setOps).remove(anyString(), any(Object[].class));

		when(this.redis.hasKey(anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			return this.strings.containsKey(key) || this.hashes.containsKey(key) || this.lists.containsKey(key)
					|| this.sets.containsKey(key);
		});
		when(this.redis.type(anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			if (this.strings.containsKey(key)) {
				return DataType.STRING;
			}
			if (this.hashes.containsKey(key)) {
				return DataType.HASH;
			}
			if (this.lists.containsKey(key)) {
				return DataType.LIST;
			}
			if (this.sets.containsKey(key)) {
				return DataType.SET;
			}
			return DataType.NONE;
		});
		when(this.redis.expire(anyString(), any(Duration.class))).thenAnswer(invocation -> {
			this.ttls.put(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		});
		when(this.redis.delete(anyString())).thenAnswer(invocation -> {
			if (this.failDelete) {
				throw new IllegalStateException("模拟 DEL 失败");
			}
			return remove(invocation.getArgument(0));
		});
		when(this.redis.delete(anyCollection())).thenAnswer(invocation -> {
			if (this.failDelete) {
				throw new IllegalStateException("模拟 DEL 失败");
			}
			long removed = 0L;
			for (Object key : (Collection<?>) invocation.getArgument(0)) {
				if (Boolean.TRUE.equals(remove(String.valueOf(key)))) {
					removed++;
				}
			}
			return removed;
		});
		// Cluster-safe scanner 的真实调用路径：StringRedisTemplate -> ConnectionFactory -> Connection。
		// 不再为 template.scan 打桩（生产已禁止该调用；替身也不应替它自证）。
		when(this.redis.getConnectionFactory()).thenReturn(this.connectionFactory);
		when(this.connectionFactory.getConnection()).thenAnswer(invocation -> this.clusterMode
				? this.clusterConnection : this.standaloneConnection);
		doAnswer(invocation -> {
			this.standaloneConnectionCloses.incrementAndGet();
			return null;
		}).when(this.standaloneConnection).close();
		doAnswer(invocation -> {
			this.clusterConnectionCloses.incrementAndGet();
			return null;
		}).when(this.clusterConnection).close();
		when(this.standaloneConnection.keyCommands()).thenReturn(this.keyCommands);
		when(this.keyCommands.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
			if (this.failScan) {
				throw new IllegalStateException("模拟 SCAN 失败");
			}
			ScanOptions options = invocation.getArgument(0);
			return cursorMatching(options == null ? null : options.getPattern(), allKeys(), () -> {
				this.standaloneCursorCloses++;
			});
		});
		when(this.clusterConnection.clusterGetNodes()).thenReturn(this.clusterMasters);
		when(this.clusterConnection.scan(any(RedisClusterNode.class), any(ScanOptions.class))).thenAnswer(invocation -> {
			RedisClusterNode node = invocation.getArgument(0);
			if (this.failScan || node == this.failNode) {
				throw new IllegalStateException("模拟 master SCAN 失败");
			}
			ScanOptions options = invocation.getArgument(1);
			Set<String> source = this.clusterNodeKeys.getOrDefault(node, Set.of());
			return cursorMatching(options == null ? null : options.getPattern(), source, () -> {
				this.closedNodeCursors.add(node);
			});
		});
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
			RedisScript<?> script = invocation.getArgument(0);
			List<?> keys = invocation.getArgument(1);
			Object[] raw = invocation.getArguments();
			Object[] args = new Object[Math.max(0, raw.length - 2)];
			for (int i = 2; i < raw.length; i++) {
				args[i - 2] = raw[i];
			}
			String body = script.getScriptAsString();
			String key = String.valueOf(keys.get(0));
			if (body.contains("PURGE_PENDING_REGISTER")) {
				String current = this.strings.get(key);
				if (current != null) {
					return current;
				}
				this.strings.put(key, String.valueOf(args[0]));
				return String.valueOf(args[0]);
			}
			if (body.contains("PURGE_PENDING_CAS_SET")) {
				String current = this.strings.get(key);
				if (current == null || !current.contains(String.valueOf(args[0]))) {
					return 0L;
				}
				this.strings.put(key, String.valueOf(args[1]));
				return 1L;
			}
			if (body.contains("PURGE_PENDING_CAS_DEL")) {
				String current = this.strings.get(key);
				if (current == null || !current.contains(String.valueOf(args[0]))) {
					return 0L;
				}
				return Boolean.TRUE.equals(remove(key)) ? 1L : 0L;
			}
			if (body.contains("PURGE_GUARD_RETAIN")) {
				String current = this.strings.get(key);
				if (current != null && !current.startsWith(String.valueOf(args[0]))) {
					return 0L;
				}
				this.strings.put(key, String.valueOf(args[1]));
				Duration ttl = Duration.ofMillis(Long.parseLong(String.valueOf(args[2])));
				this.ttls.put(key, ttl);
				return 1L;
			}
			if (body.contains("PURGE_GUARD_DISARM")) {
				String current = this.strings.get(key);
				if (current != null && current.startsWith(String.valueOf(args[0]))) {
					return Boolean.TRUE.equals(remove(key)) ? 1L : 0L;
				}
				return 0L;
			}
			if (body.contains("MEMORY_CLEAR")) {
				// 清空记忆：DEL 数据键 + 推进代次 + 推进版本 + 续 TTL（三个键同 hash tag）。
				long ttlMillis = Long.parseLong(String.valueOf(args[0]));
				String dataKey = String.valueOf(keys.get(0));
				String versionKey = String.valueOf(keys.get(1));
				String generationKey = String.valueOf(keys.get(2));
				remove(dataKey);
				this.strings.put(generationKey,
						Long.toString(Long.parseLong(this.strings.getOrDefault(generationKey, "0")) + 1L));
				this.strings.put(versionKey,
						Long.toString(Long.parseLong(this.strings.getOrDefault(versionKey, "0")) + 1L));
				if (ttlMillis > 0L) {
					this.ttls.put(versionKey, Duration.ofMillis(ttlMillis));
					this.ttls.put(generationKey, Duration.ofMillis(ttlMillis));
				}
				return 1L;
			}
			throw new IllegalStateException("未建模的脚本：" + body);
		});
	}

	private Boolean remove(String key) {
		if (this.failDeleteContaining != null && key.contains(this.failDeleteContaining)) {
			throw new IllegalStateException("模拟 DEL 失败：" + key);
		}
		this.deleted.add(key);
		boolean existed = this.strings.remove(key) != null;
		existed |= this.hashes.remove(key) != null;
		existed |= this.lists.remove(key) != null;
		existed |= this.sets.remove(key) != null;
		this.ttls.remove(key);
		return existed;
	}

	private static Object[] tail(Object[] args, int from) {
		List<Object> values = new ArrayList<>();
		for (int i = from; i < args.length; i++) {
			if (args[i] instanceof Object[] nested) {
				values.addAll(List.of(nested));
			}
			else {
				values.add(args[i]);
			}
		}
		return values.toArray();
	}

	private Cursor<byte[]> cursorMatching(String glob, Collection<String> source, Runnable onClose) {
		List<byte[]> keys = new ArrayList<>();
		for (String key : source) {
			if (glob == null || globMatches(glob, key)) {
				keys.add(key.getBytes(StandardCharsets.UTF_8));
			}
		}
		return new BytesCursor(keys, onClose);
	}

	/** 增加一个 cluster master（默认无键；测试显式分配）。 */
	RedisClusterNode addClusterMaster(String host, int port) {
		RedisClusterNode node = mock(RedisClusterNode.class);
		when(node.isMaster()).thenReturn(true);
		when(node.getHost()).thenReturn(host);
		when(node.getPort()).thenReturn(port);
		this.clusterMasters.add(node);
		this.clusterNodeKeys.put(node, ConcurrentHashMap.newKeySet());
		return node;
	}

	/** 把一个键分配给指定 master（模拟 Cluster 分片）。 */
	void assignToMaster(RedisClusterNode node, String... keys) {
		this.clusterNodeKeys.computeIfAbsent(node, ignored -> ConcurrentHashMap.newKeySet())
			.addAll(List.of(keys));
	}

	private Set<String> allKeys() {
		Set<String> keys = new LinkedHashSet<>();
		keys.addAll(this.strings.keySet());
		keys.addAll(this.hashes.keySet());
		keys.addAll(this.lists.keySet());
		keys.addAll(this.sets.keySet());
		return keys;
	}

	/**
	 * Redis {@code stringmatchlen} 的忠实复刻（本替身只建模产品代码真正用到的 glob 子集）。
	 *
	 * <h2>为什么不能再用正则翻译</h2>
	 * 旧实现把 {@code [} / {@code ]} 当<b>字面字符</b>转义，比真 Redis 宽松得多：
	 * 真 Redis 里 {@code [} 开启字符类（{@code [^…]} 取反、{@code a-c} 区间、无 {@code ]} 时吃到模式末尾），
	 * {@code \} 转义下一字符。用它验证"sessionId 含 {@code [} 时销毁是否漏删自己的 staging 键"
	 * 会得到<b>假绿</b>——正是 review I1 指出的"替身掩盖缺陷"。因此这里直接按字符逐位匹配。
	 */
	static boolean globMatches(String pattern, String key) {
		int p = 0;
		int k = 0;
		int starP = -1;
		int starK = -1;
		while (k < key.length()) {
			char pc = p < pattern.length() ? pattern.charAt(p) : 0;
			if (pc == '*') {
				starP = ++p;
				starK = k;
				continue;
			}
			boolean matched = false;
			int next = 0;
			if (p < pattern.length()) {
				next = singleMatches(pattern, p, key.charAt(k));
				matched = next > 0;
			}
			if (matched) {
				p = next;
				k++;
				continue;
			}
			if (starP >= 0) {
				// 回溯到上一个 '*'：让它多吃一个字符
				p = starP;
				k = ++starK;
				continue;
			}
			return false;
		}
		while (p < pattern.length() && pattern.charAt(p) == '*') {
			p++;
		}
		return p == pattern.length();
	}

	/**
	 * 单个模式单元对单个字符的匹配。
	 *
	 * @return 匹配时返回<b>下一个模式位置</b>（&gt;0）；不匹配返回 0
	 */
	private static int singleMatches(String pattern, int p, char c) {
		char pc = pattern.charAt(p);
		switch (pc) {
			case '\\':
				if (p + 1 >= pattern.length()) {
					return c == '\\' ? p + 1 : 0;
				}
				return pattern.charAt(p + 1) == c ? p + 2 : 0;
			case '[': {
				int i = p + 1;
				boolean negate = i < pattern.length() && pattern.charAt(i) == '^';
				if (negate) {
					i++;
				}
				boolean matched = false;
				boolean closed = false;
				while (i < pattern.length()) {
					char current = pattern.charAt(i);
					if (current == '\\' && i + 1 < pattern.length()) {
						if (pattern.charAt(i + 1) == c) {
							matched = true;
						}
						i += 2;
						continue;
					}
					if (current == ']') {
						closed = true;
						i++;
						break;
					}
					if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-' && pattern.charAt(i + 2) != ']') {
						char from = current;
						char to = pattern.charAt(i + 2);
						if (from > to) {
							char swap = from;
							from = to;
							to = swap;
						}
						if (c >= from && c <= to) {
							matched = true;
						}
						i += 3;
						continue;
					}
					if (current == c) {
						matched = true;
					}
					i++;
				}
				if (negate) {
					matched = !matched;
				}
				if (!matched) {
					return 0;
				}
				// 未闭合的字符类吃到模式末尾：真 Redis 里它仍然只匹配一个字符，之后就要求键结束。
				return closed ? i : pattern.length();
			}
			case '?':
				return p + 1;
			default:
				return pc == c ? p + 1 : 0;
		}
	}

	private static final class BytesCursor implements Cursor<byte[]> {

		private final Iterator<byte[]> iterator;

		private final Runnable onClose;

		private boolean closed;

		BytesCursor(List<byte[]> keys, Runnable onClose) {
			this.iterator = keys.iterator();
			this.onClose = onClose;
		}

		@Override
		public boolean hasNext() {
			return !this.closed && this.iterator.hasNext();
		}

		@Override
		public byte[] next() {
			return this.iterator.next();
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				this.onClose.run();
			}
		}

		@Override
		public boolean isClosed() {
			return this.closed;
		}

		@Override
		public long getCursorId() {
			return 0;
		}

		@Override
		public Cursor.CursorId getId() {
			return Cursor.CursorId.of(0);
		}

		@Override
		public long getPosition() {
			return 0;
		}

	}
}
