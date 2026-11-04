package com.example.configmgr.perf.redis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
	void pipelineWaitIsCappedAtEightHundredMillis() {
		assertEquals(800L, CountingRedis.pipelineWaitMillis(Duration.ofSeconds(5)));
		assertEquals(800L, CountingRedis.pipelineWaitMillis(Duration.ofSeconds(30)));
		assertEquals(250L, CountingRedis.pipelineWaitMillis(Duration.ofMillis(250)));
		assertTrue(CountingRedis.pipelineWaitMillis(Duration.ofSeconds(60)) < 1_000L);
		assertThrows(IllegalArgumentException.class, () -> CountingRedis.pipelineWaitMillis(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> CountingRedis.pipelineWaitMillis(null));
	}

	@Test
	void dispatchesAllCommandsThroughNativeAsyncApiWithoutWaiting() {
		RecordingAsyncCommands commands = new RecordingAsyncCommands();
		List<RedisFuture<?>> futures = new ArrayList<>();
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "EXISTS", KEY }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "SMEMBERS", KEY }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "GET", KEY }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "DEL", KEY }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "SREM", KEY, "run-1", "run-2" }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "ZREM", KEY, "run-1" }));
		futures.add(CountingRedis.dispatch(commands.commands(), new String[] { "SET", KEY, "bg", "EX", "600" }));

		assertEquals(List.of("exists", "smembers", "get", "del", "srem", "zrem", "set"), commands.names());
		assertEquals(7, futures.size());
		for (RedisFuture<?> future : futures) {
			assertNotNull(future);
			assertTrue(!future.isDone(), "dispatch 只能发出命令，不能在批次内等待");
		}
		assertArrayEquals(utf8(KEY), (byte[]) commands.argsOf("get")[0]);

		Object[] existsArgs = commands.argsOf("exists");
		assertEquals(1, ((byte[][]) existsArgs[0]).length);
		assertArrayEquals(utf8(KEY), ((byte[][]) existsArgs[0])[0]);

		Object[] sremArgs = commands.argsOf("srem");
		assertArrayEquals(new byte[][] { utf8("run-1"), utf8("run-2") }, (byte[][]) sremArgs[1]);

		Object[] setArgs = commands.argsOf("set");
		assertInstanceOf(SetArgs.class, setArgs[2]);
	}

	@Test
	void decodesCompletedBatchInCommandOrder() {
		CountingRedis redis = new CountingRedis(new StringRedisTemplate(), Duration.ofSeconds(5));
		List<RedisFuture<?>> futures = List.of(
				completed(1L),
				completed("payload".getBytes(StandardCharsets.UTF_8)),
				completed(new LinkedHashSet<>(List.of("run-1".getBytes(StandardCharsets.UTF_8)))),
				completed(null));

		List<Object> results = redis.awaitPipeline(futures, 100L);

		assertEquals(1L, results.get(0));
		assertEquals("payload", results.get(1));
		assertEquals(Set.of("run-1"), results.get(2));
		assertEquals(null, results.get(3));
	}

	@Test
	void cancelsUnfinishedFuturesWhenBatchTimesOut() {
		CountingRedis redis = new CountingRedis(new StringRedisTemplate(), Duration.ofSeconds(5));
		AtomicBoolean cancelled = new AtomicBoolean();
		RedisFuture<?> pending = pending(cancelled);

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> redis.awaitPipeline(List.of(pending), 20L));

		assertTrue(cancelled.get(), "超时后必须取消未完成的 future");
		assertTrue(error.getMessage().contains("20ms"), error.getMessage());
		assertTrue(error.getMessage().contains("已取消 1 条"), error.getMessage());
	}

	private static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static RedisFuture<?> completed(Object value) {
		return fakeFuture(value, true, new AtomicBoolean());
	}

	private static RedisFuture<?> pending(AtomicBoolean cancelled) {
		return fakeFuture(null, false, cancelled);
	}

	@SuppressWarnings("unchecked")
	private static RedisFuture<?> fakeFuture(Object value, boolean done, AtomicBoolean cancelled) {
		return (RedisFuture<?>) Proxy.newProxyInstance(RedisFuture.class.getClassLoader(),
				new Class<?>[] { RedisFuture.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "get" -> {
							if (!done) {
								throw new TimeoutException("fake future not done");
							}
							return value;
						}
						case "isDone" -> {
							return done;
						}
						case "isCancelled" -> {
							return cancelled.get();
						}
						case "cancel" -> {
							cancelled.set(true);
							return true;
						}
						case "getError" -> {
							return null;
						}
						case "toString" -> {
							return "fakeFuture";
						}
						case "hashCode" -> {
							return System.identityHashCode(proxy);
						}
						case "equals" -> {
							return proxy == args[0];
						}
						default -> {
							Class<?> type = method.getReturnType();
							if (type == boolean.class) {
								return false;
							}
							if (type == long.class) {
								return 0L;
							}
							if (type == int.class) {
								return 0;
							}
							return null;
						}
					}
				});
	}

	/** 记录原生 async 调用；返回永不完成的 future，确保 dispatch 不会在批次内等待。 */
	private static final class RecordingAsyncCommands implements InvocationHandler {

		private final List<String> names = new ArrayList<>();

		private final List<Object[]> arguments = new ArrayList<>();

		@SuppressWarnings("unchecked")
		private final RedisClusterAsyncCommands<byte[], byte[]> commands = (RedisClusterAsyncCommands<byte[], byte[]>) Proxy
				.newProxyInstance(RedisClusterAsyncCommands.class.getClassLoader(),
						new Class<?>[] { RedisClusterAsyncCommands.class }, this);

		RedisClusterAsyncCommands<byte[], byte[]> commands() {
			return this.commands;
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
				return "RecordingAsyncCommands";
			}
			if ("hashCode".equals(name)) {
				return System.identityHashCode(proxy);
			}
			if ("equals".equals(name)) {
				return proxy == args[0];
			}
			this.names.add(name);
			this.arguments.add(args == null ? new Object[0] : args);
			return pending(new AtomicBoolean());
		}
	}

}
