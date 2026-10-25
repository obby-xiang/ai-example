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
import java.util.regex.Pattern;

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

	volatile boolean failScan;

	final StringRedisTemplate redis = mock(StringRedisTemplate.class);

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
		when(this.redis.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
			if (this.failScan) {
				throw new IllegalStateException("模拟 SCAN 失败");
			}
			ScanOptions options = invocation.getArgument(0);
			return cursorMatching(options == null ? null : options.getPattern());
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
			throw new IllegalStateException("未建模的脚本：" + body);
		});
	}

	private Boolean remove(String key) {
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

	private Cursor<String> cursorMatching(String glob) {
		List<String> keys = new ArrayList<>();
		Pattern pattern = glob == null ? null : globToRegex(glob);
		for (String key : allKeys()) {
			if (pattern == null || pattern.matcher(key).matches()) {
				keys.add(key);
			}
		}
		return new ListCursor(keys);
	}

	private Set<String> allKeys() {
		Set<String> keys = new LinkedHashSet<>();
		keys.addAll(this.strings.keySet());
		keys.addAll(this.hashes.keySet());
		keys.addAll(this.lists.keySet());
		keys.addAll(this.sets.keySet());
		return keys;
	}

	private static Pattern globToRegex(String glob) {
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < glob.length(); i++) {
			char current = glob.charAt(i);
			if (current == '\\' && i + 1 < glob.length()) {
				regex.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
			}
			else if (current == '*') {
				regex.append(".*");
			}
			else if (current == '?') {
				regex.append('.');
			}
			else if ("[](){}.+^$|".indexOf(current) >= 0) {
				regex.append('\\').append(current);
			}
			else {
				regex.append(current);
			}
		}
		return Pattern.compile(regex.append('$').toString());
	}

	private static final class ListCursor implements Cursor<String> {

		private final Iterator<String> iterator;

		private boolean closed;

		ListCursor(List<String> keys) {
			this.iterator = keys.iterator();
		}

		@Override
		public boolean hasNext() {
			return !this.closed && this.iterator.hasNext();
		}

		@Override
		public String next() {
			return this.iterator.next();
		}

		@Override
		public void close() {
			this.closed = true;
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
