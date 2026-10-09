package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 单帧上限的<b>字段级</b>截断器（T3-10 裁定，{@code app.ai.frame.max-bytes}）。
 *
 * <h2>为什么严禁字节级截断（施工注记 R5）</h2>
 * 对帧 JSON 的字节流做"砍到 N 字节"会产出<b>坏 JSON</b>：回放读路径
 * （{@code RunStore#events}）对单帧坏损是<b>静默忽略</b>的，于是表现为"回放少一帧" ——
 * 而帧带单调 {@code seq}，少一帧就是 seq 洞，差量补发（{@code lastSeq}）与前端重挂会
 * 永久丢内容。故本类只做两件事：① 判定 = <b>整帧 JSON 的 UTF-8 字节数</b>；
 * ② 超限时对 {@code text} / {@code result} 的<b>值</b>按预算做字符级截断，并挂独立标记字段。
 *
 * <h2>标记字段（A21，口径变更）</h2>
 * {@code truncated: true} + {@code truncatedReason}（含原始字节数/上限/被截字段）。
 * <b>T3-10 的上限优先于 T2"给前端看的帧全文不裁剪"的承诺</b> —— 前端帧从"永不裁剪"
 * 变为"受单帧上限约束的字段级截断 + 标记"。
 *
 * <h2>预算与复测（A19）</h2>
 * 预算 = 上限 −（其余字段的 JSON 开销）；按各字段原始字节大小为权重分配；
 * 截后<b>复测</b>一次，仍超则按超出量迭代收缩（最多 {@value #MAX_ITERATIONS} 轮），
 * 保证落地帧 ≤ 上限。若无 {@code text}/{@code result} 可截（超限来自其它字段），
 * 则只挂标记、不改写任何字段 —— 仍然不破坏 JSON。
 */
@Slf4j
public final class FrameSizeLimiter {

	/** 可截断的字段（帧里承载"模型正文"与"工具结果全文"的两个值）。 */
	static final List<String> TRUNCATABLE_FIELDS = List.of("text", "result");

	/** 复测后仍超时的迭代收缩上限（防病态输入下的死循环）。 */
	private static final int MAX_ITERATIONS = 8;

	/** 预算里的安全余量：键名/冒号/逗号等 JSON 结构字节（非值部分）。 */
	private static final int STRUCTURE_MARGIN_BYTES = 64;

	/** 截断标记字段名（前端只读，不认识也不影响渲染）。 */
	public static final String TRUNCATED_FIELD = "truncated";

	public static final String TRUNCATED_REASON_FIELD = "truncatedReason";

	private FrameSizeLimiter() {
	}

	/**
	 * 就地把超限帧截断到上限内（原地改写 {@code frame}），并返回<b>该帧的最终负载字节</b>
	 * （出帧路径据此免去第二次序列化）。
	 *
	 * @param frame    帧（含 {@code type}/业务字段与 {@code seq}）
	 * @param mapper   与本轮出帧同一个序列化器（口径同源，避免"算的是 A、发的是 B"）
	 * @param maxBytes 单帧上限（UTF-8 字节；{@code <= 0} 表示不限制）
	 * @return 帧的 UTF-8 负载字节；序列化失败返回 {@code null}（调用方记日志并跳过本帧）
	 */
	public static byte[] apply(Map<String, Object> frame, ObjectMapper mapper, int maxBytes) {
		if (frame == null || mapper == null) {
			return new byte[0];
		}
		byte[] full = serialize(frame, mapper);
		if (full == null) {
			return null;
		}
		if (maxBytes <= 0 || full.length <= maxBytes) {
			return full;   // 未超限：一字不改，直接复用本次序列化结果
		}

		List<String> fields = new ArrayList<>();
		for (String field : TRUNCATABLE_FIELDS) {
			if (frame.get(field) instanceof String) {
				fields.add(field);
			}
		}
		String reason = "frame-bytes-exceeded: original=" + full.length + " limit=" + maxBytes
				+ " truncatedFields=" + String.join(",", fields);

		int budget = maxBytes - overhead(frame, mapper, fields, reason) - STRUCTURE_MARGIN_BYTES;
		if (budget < 0) {
			budget = 0;
		}
		for (int i = 0; i <= MAX_ITERATIONS; i++) {
			distribute(frame, mapper, fields, budget);
			frame.put(TRUNCATED_FIELD, true);
			frame.put(TRUNCATED_REASON_FIELD, reason);
			byte[] retry = serialize(frame, mapper);
			if (retry == null) {
				return null;
			}
			if (retry.length <= maxBytes) {
				log.warn("SSE 单帧超限已按字段级截断 type={} 原 {} 字节 → {} 字节（上限 {}，字段 {}）",
						frame.get("type"), full.length, retry.length, maxBytes, fields);
				return retry;
			}
			// 复测仍超：按超出量收缩预算再算一轮
			budget = Math.max(0, budget - (retry.length - maxBytes) - STRUCTURE_MARGIN_BYTES);
		}
		// 迭代到上限仍不满足（超限来自不可截字段）：只挂标记，保持 JSON 合法（绝不字节截断）
		frame.put(TRUNCATED_FIELD, true);
		frame.put(TRUNCATED_REASON_FIELD, reason + " (unreachable-limit: 其余字段本身已超限)");
		log.warn("SSE 单帧超限且不可截字段占主导 type={} 原 {} 字节（上限 {}）—— 已挂标记但未达上限",
				frame.get("type"), full.length, maxBytes);
		return serialize(frame, mapper);
	}

	/** 其余字段（去掉可截字段 + 已挂标记）的 JSON 开销。 */
	private static int overhead(Map<String, Object> frame, ObjectMapper mapper, List<String> fields, String reason) {
		Map<String, Object> probe = new LinkedHashMap<>(frame);
		for (String field : fields) {
			probe.remove(field);
		}
		probe.put(TRUNCATED_FIELD, true);
		probe.put(TRUNCATED_REASON_FIELD, reason);
		byte[] bytes = serialize(probe, mapper);
		return bytes == null ? 0 : bytes.length;
	}

	/** 按各字段原始字节大小为权重分配预算，并把每个字段截到自己的预算内（末字段拿余量）。 */
	private static void distribute(Map<String, Object> frame, ObjectMapper mapper, List<String> fields, int budget) {
		if (fields.isEmpty()) {
			return;
		}
		if (budget <= 0) {
			for (String field : fields) {
				frame.put(field, "");
			}
			return;
		}
		int count = fields.size();
		int[] shares = new int[count];
		if (count == 1) {
			shares[0] = budget;
		}
		else {
			long total = 0L;
			for (String field : fields) {
				total += escapedLength((String) frame.get(field), mapper);
			}
			int assigned = 0;
			for (int i = 0; i < count; i++) {
				if (i == count - 1) {
					shares[i] = Math.max(0, budget - assigned);
					break;
				}
				shares[i] = total <= 0 ? budget / count
						: (int) ((long) budget * escapedLength((String) frame.get(fields.get(i)), mapper) / total);
				assigned += shares[i];
			}
		}
		for (int i = 0; i < count; i++) {
			frame.put(fields.get(i), takeChars((String) frame.get(fields.get(i)), shares[i], mapper));
		}
	}

	/** 值在 JSON 里的转义后字节数（不含两侧引号）：`"` → `\"`、非 ASCII → 多字节等都被算准。 */
	static int escapedLength(String value, ObjectMapper mapper) {
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			return mapper.writeValueAsBytes(value).length - 2;
		}
		catch (Exception ex) {
			return value.length();
		}
	}

	/** 按<b>字符</b>截断到不超过 byteBudget 的转义字节数（二分 + 代理对保护）。 */
	static String takeChars(String value, int byteBudget, ObjectMapper mapper) {
		if (value == null || value.isEmpty() || byteBudget <= 0) {
			return "";
		}
		int lo = 0;
		int hi = value.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (escapedLength(value.substring(0, mid), mapper) <= byteBudget) {
				lo = mid;
			}
			else {
				hi = mid - 1;
			}
		}
		if (lo > 0 && lo < value.length() && Character.isHighSurrogate(value.charAt(lo - 1))) {
			lo--;   // 不切断代理对（半个代理项会被编码成替换字符）
		}
		return value.substring(0, lo);
	}

	private static byte[] serialize(Map<String, Object> frame, ObjectMapper mapper) {
		try {
			return mapper.writeValueAsBytes(frame);
		}
		catch (Exception ex) {
			return null;
		}
	}
}
