package com.example.configmgr.perf.redis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountingRedisPipelineTest {

	private static final String KEY = "ai:session:{1:101:session-1}";

	@Test
	void acceptsEveryCommandTheHarnessQueues() {
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "EXISTS", KEY }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "SMEMBERS", KEY }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "GET", KEY }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "DEL", KEY }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "SET", KEY, "bg", "EX", "600" }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "SREM", KEY, "run-1" }));
		assertDoesNotThrow(() -> CountingRedis.requirePipelineCommand(new String[] { "ZREM", KEY, "run-1", "run-2" }));
	}

	@Test
	void commandNamesAreNormalized() {
		assertEquals("SMEMBERS", CountingRedis.pipelineCommandName(new String[] { "smembers", KEY }));
		assertEquals("ZREM", CountingRedis.pipelineCommandName(new String[] { "zRem", KEY, "run-1" }));
	}

	@Test
	void rejectsUnsupportedCommandsInsteadOfExecutingThemSynchronously() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "INCR", KEY }));
		assertTrue(error.getMessage().contains("INCR"));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "EVAL", "return 1", "0" }));
	}

	@Test
	void rejectsWrongArityOrUnsupportedSetForm() {
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "EXISTS" }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "GET", KEY, KEY }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "SREM", KEY }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "SET", KEY, "bg" }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "SET", KEY, "bg", "PX", "600" }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "SET", KEY, "bg", "EX", "abc" }));
		assertThrows(IllegalArgumentException.class,
				() -> CountingRedis.requirePipelineCommand(new String[] { "SET", KEY, "bg", "EX", "0" }));
	}

	@Test
	void queuesEachCommandThroughItsTypedConnectionApi() throws Exception {
		RecordingConnection connection = new RecordingConnection();
		queue(connection.connection(), new String[] { "EXISTS", KEY });
		queue(connection.connection(), new String[] { "SMEMBERS", KEY });
		queue(connection.connection(), new String[] { "GET", KEY });
		queue(connection.connection(), new String[] { "DEL", KEY });
		queue(connection.connection(), new String[] { "SREM", KEY, "run-1", "run-2" });
		queue(connection.connection(), new String[] { "ZREM", KEY, "run-1" });
		queue(connection.connection(), new String[] { "SET", KEY, "bg", "EX", "600" });

		assertEquals(List.of("exists", "sMembers", "get", "del", "sRem", "zRem", "set"), connection.names());
		assertArrayEquals(utf8(KEY), (byte[]) connection.argsOf("get")[0]);

		Object[] existsArgs = connection.argsOf("exists");
		assertEquals(1, ((byte[][]) existsArgs[0]).length, "EXISTS 必须是单键，才能走普通 async 命令");
		assertArrayEquals(utf8(KEY), ((byte[][]) existsArgs[0])[0]);

		Object[] sremArgs = connection.argsOf("sRem");
		assertEquals(2, ((byte[][]) sremArgs[1]).length);
		assertArrayEquals(new byte[][] { utf8("run-1"), utf8("run-2") }, (byte[][]) sremArgs[1]);

		Object[] setArgs = connection.argsOf("set");
		Expiration expiration = assertInstanceOf(Expiration.class, setArgs[2]);
		assertEquals(600L, expiration.getExpirationTimeInSeconds());
		assertFalse(expiration.isPersistent());
		assertEquals(RedisStringCommands.SetOption.UPSERT, setArgs[3]);
	}

	@Test
	void queuesCommandsInCallerOrder() throws Exception {
		RecordingConnection connection = new RecordingConnection();
		queue(connection.connection(), new String[] { "GET", KEY });
		queue(connection.connection(), new String[] { "EXISTS", KEY });
		queue(connection.connection(), new String[] { "DEL", KEY });
		assertEquals(List.of("get", "exists", "del"), connection.names());
	}

	private static void queue(RedisConnection connection, String[] command) throws Exception {
		Method queue = CountingRedis.class.getDeclaredMethod("queuePipelined", RedisConnection.class,
				String[].class);
		queue.setAccessible(true);
		try {
			queue.invoke(null, connection, command);
		}
		catch (java.lang.reflect.InvocationTargetException ex) {
			throw (Exception) ex.getCause();
		}
	}

	private static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	/** 只实现 queuePipelined 触达的方法，记录类型化调用；任何 execute 调用都会以名字暴露出来。 */
	private static final class RecordingConnection implements InvocationHandler {

		private final List<String> names = new ArrayList<>();

		private final List<Object[]> arguments = new ArrayList<>();

		private final RedisConnection connection = (RedisConnection) proxy(RedisConnection.class);

		RedisConnection connection() {
			return this.connection;
		}

		List<String> names() {
			return this.names;
		}

		Object[] argsOf(String name) {
			int index = this.names.indexOf(name);
			assertTrue(index >= 0, "未调用 " + name);
			return this.arguments.get(index);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			String name = method.getName();
			if ("toString".equals(name)) {
				return "RecordingConnection";
			}
			if ("hashCode".equals(name)) {
				return System.identityHashCode(proxy);
			}
			if ("equals".equals(name)) {
				return proxy == args[0];
			}
			Class<?> returnType = method.getReturnType();
			if (returnType.isInterface()
					&& returnType.getName().startsWith("org.springframework.data.redis.connection.")) {
				return proxy(returnType);
			}
			this.names.add(name);
			this.arguments.add(args == null ? new Object[0] : args);
			if (returnType == boolean.class) {
				return false;
			}
			if (returnType == Boolean.class) {
				return Boolean.FALSE;
			}
			if (returnType == long.class || returnType == Long.class) {
				return 0L;
			}
			if (returnType == int.class || returnType == Integer.class) {
				return 0;
			}
			if (returnType == byte[].class) {
				return new byte[0];
			}
			if (Set.class.isAssignableFrom(returnType)) {
				return Set.of();
			}
			return null;
		}

		private Object proxy(Class<?> type) {
			return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, this);
		}
	}

}
