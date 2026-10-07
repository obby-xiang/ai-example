package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 一条"帧线"：某个订阅者实际收到的帧，按到达顺序录下来。
 *
 * <p>
 * 帧序一致性的<b>断言对象是这条线</b>，而不是"某方法被调用过几次"—— 方法调用能证明帧被请求发出，
 * 证明不了帧之间的顺序与序号；协议的一致性恰恰活在顺序里。这里录的是
 * {@link SseChatEmitter} 真正写进 {@link SseEmitter} 的 JSON 原文（写出前才序列化），
 * 因此断言的是终端契约本身。
 *
 * <p>
 * 挂了线上的订阅者是一个 {@code SseEmitter} 桩：任何 {@code send} 都只做记录。
 * 帧线线程安全（挂起期心跳与决策帧可能来自不同线程）。
 */
public final class FrameWire {

	private final ObjectMapper mapper = new ObjectMapper();

	private final List<String> payloads = new CopyOnWriteArrayList<>();

	private final SseEmitter emitter;

	public FrameWire() {
		this(payload -> {
		});
	}

	/**
	 * 带"到达前钩子"的帧线：钩子在<b>记录之前</b>跑，因此可以让某条帧的写出在钩子里阻塞，
	 * 把"两个线程同时出帧"的到达顺序确定化（否则这类用例只能靠运气复现）。
	 *
	 * @param beforeRecord 收到帧原文后、记录之前调用（可阻塞）
	 */
	public FrameWire(java.util.function.Consumer<String> beforeRecord) {
		this.emitter = mock(SseEmitter.class);
		try {
			doAnswer(invocation -> {
				// 注意：不能写成 String.valueOf(invocation.getArgument(0)) —— 泛型 getArgument 与
				// String.valueOf 的重载会让编译器推出 String.valueOf(char[])，运行时 CCE。
				Object argument = invocation.getArgument(0);
				String payload = String.valueOf(argument);
				beforeRecord.accept(payload);
				this.payloads.add(payload);
				return null;
			}).when(this.emitter).send(anyString());
		}
		catch (IOException ex) {
			// SseEmitter#send 声明了 IOException；桩件不会抛（上面只做记录）
			throw new IllegalStateException("无法桩定 SseEmitter#send", ex);
		}
	}

	/** 建一条帧线并挂到写出器上（先挂后发，避免漏帧）。 */
	public static FrameWire attachTo(SseChatEmitter out) {
		FrameWire wire = new FrameWire();
		out.attach(wire.emitter());
		return wire;
	}

	public SseEmitter emitter() {
		return this.emitter;
	}

	public int size() {
		return this.payloads.size();
	}

	/** 帧原文（JSON 字符串，按到达顺序）。 */
	public List<String> payloads() {
		return List.copyOf(this.payloads);
	}

	/** 解码后的帧（按到达顺序）。整型统一归一成 {@code Long}，用例断言只需一套数字口径。 */
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> frames() {
		List<Map<String, Object>> out = new ArrayList<>(this.payloads.size());
		for (String payload : this.payloads) {
			try {
				out.add(normalize(this.mapper.readValue(payload, Map.class)));
			}
			catch (Exception ex) {
				throw new IllegalStateException("帧原文不是 JSON 对象：" + payload, ex);
			}
		}
		return out;
	}

	/**
	 * 帧的数字归一：JSON 解出来的小整数是 {@code Integer}，而帧在线上的原值来自 {@code int}/{@code long}
	 * 两种来源（{@code seq} 是 long、{@code chars}/{@code attempts} 是 int）。不归一就会出现
	 * "5 不等于 5L"这种与协议无关的断言噪音。
	 */
	public static Map<String, Object> normalize(Map<String, Object> frame) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		frame.forEach((key, value) -> out.put(key, value instanceof Integer number ? (Object) number.longValue()
				: value));
		return out;
	}

	/** 整表归一（归档视角的帧也可以用同一套数字口径断言）。 */
	public static List<Map<String, Object>> normalize(List<Map<String, Object>> frames) {
		List<Map<String, Object>> out = new ArrayList<>(frames.size());
		for (Map<String, Object> frame : frames) {
			out.add(normalize(frame));
		}
		return out;
	}

	public List<String> types() {
		return FrameContract.types(frames());
	}

	/**
	 * 阻塞等某一类帧出现。并发出帧的用例（确认门决策来自 HTTP 线程、挂起公告来自运行线程）
	 * 靠它把"决策线程"对齐到"挂起已公告"之后，从而让帧序断言确定化。
	 */
	public Map<String, Object> awaitType(String type, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			for (Map<String, Object> frame : frames()) {
				if (type.equals(frame.get("type"))) {
					return frame;
				}
			}
			try {
				TimeUnit.MILLISECONDS.sleep(20L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new IllegalStateException("等待帧超时：" + type + "（已收到 " + types() + "）");
	}

}
