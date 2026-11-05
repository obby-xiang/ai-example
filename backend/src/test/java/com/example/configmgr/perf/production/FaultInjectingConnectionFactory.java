package com.example.configmgr.perf.production;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** A transport proxy that fails selected real commands once; production classes and scripts stay unchanged. */
final class FaultInjectingConnectionFactory extends LettuceConnectionFactory {

	private record Rule(String operation, String keyPart) {
		boolean matches(String method, String commandText) {
			String operation = this.operation.toLowerCase(Locale.ROOT);
			return (method.contains(operation) || commandText.toLowerCase(Locale.ROOT).contains(operation))
					&& (this.keyPart.isBlank() || commandText.contains(this.keyPart));
		}
	}

	private final RedisConnectionFactory delegate;

	private final Queue<Rule> rules = new ConcurrentLinkedQueue<>();

	FaultInjectingConnectionFactory(RedisConnectionFactory delegate) {
		this.delegate = delegate;
	}

	void failNext(String operation, String keyPart) {
		this.rules.add(new Rule(operation.toLowerCase(Locale.ROOT), keyPart == null ? "" : keyPart));
	}

	void clear() {
		this.rules.clear();
	}

	@Override
	public RedisConnection getConnection() {
		return proxy(RedisConnection.class, this.delegate.getConnection());
	}

	@Override
	public RedisClusterConnection getClusterConnection() {
		return proxy(RedisClusterConnection.class, this.delegate.getClusterConnection());
	}

	@Override
	public RedisSentinelConnection getSentinelConnection() {
		return this.delegate.getSentinelConnection();
	}

	@Override
	public boolean getConvertPipelineAndTxResults() {
		return this.delegate.getConvertPipelineAndTxResults();
	}

	@Override
	public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
		return this.delegate.translateExceptionIfPossible(ex);
	}

	private <T> T proxy(Class<T> type, T connection) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				(proxy, method, args) -> invoke(connection, method, args)));
	}

	private Object invoke(Object connection, Method method, Object[] args) throws Throwable {
		String methodName = method.getName().toLowerCase(Locale.ROOT);
		String commandText = commandText(args);
		for (Rule rule : this.rules) {
			if (rule.matches(methodName, commandText) && this.rules.remove(rule)) {
				throw new IllegalStateException("PERF_INJECTED " + rule.operation + " " + rule.keyPart);
			}
		}
		try {
			return method.invoke(connection, args);
		}
		catch (InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private static String commandText(Object[] args) {
		if (args == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (Object arg : args) {
			append(out, arg);
		}
		return out.toString();
	}

	private static void append(StringBuilder out, Object value) {
		if (value == null) {
			return;
		}
		if (value instanceof byte[] bytes) {
			out.append(' ').append(new String(bytes, StandardCharsets.UTF_8));
			return;
		}
		if (value instanceof byte[][] many) {
			for (byte[] item : many) {
				append(out, item);
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			for (Object item : collection) {
				append(out, item);
			}
			return;
		}
		if (value.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(value);
			for (int i = 0; i < length; i++) {
				append(out, java.lang.reflect.Array.get(value, i));
			}
			return;
		}
		out.append(' ').append(value);
	}

}
