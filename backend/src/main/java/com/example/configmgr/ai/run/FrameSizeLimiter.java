package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.TruncationContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 单帧上限的<b>字段级</b>降采样器（T3-10 裁定；G5 起上限来自
 * {@code app.ai.result-budget.ui.max-frame-bytes}）。
 *
 * <h2>为什么严禁字节级截断（施工注记 R5）</h2>
 * 对帧 JSON 的字节流做"砍到 N 字节"会产出<b>坏 JSON</b>：回放读路径
 * （{@code RunStore#events}）对单帧坏损是<b>静默忽略</b>的，于是表现为"回放少一帧" ——
 * 而帧带单调 {@code seq}，少一帧就是 seq 洞，差量补发（{@code lastSeq}）与前端重挂会
 * 永久丢内容。故本类只做两件事：① 判定 = <b>整帧 JSON 的 UTF-8 字节数</b>；
 * ② 超限时对 {@code text} / {@code result} 的<b>值</b>按预算做降采样，并挂统一截断字段。
 *
 * <h2>截断字段（G5 统一契约，{@link TruncationContract}）</h2>
 * {@code truncated / strategy / originalChars / returnedChars / notice}；
 * {@code truncatedReason} 保留为 {@code notice} 的兼容别名（前端角标仍读它）。
 * <b>语义边界</b>：本键是"单帧字节上限"，超限时产出的仍是一份<b>完整合法的帧</b>
 * （字段级降采样 + 标记），<b>不做跨帧分片</b>；分片/续帧协议与超大结果 spill 一并挂账。
 *
 * <h2>结构化结果不做字符截断</h2>
 * {@code result} 是 JSON 字符串时（后端工具结果常见形态），按字符切会得到非法 JSON。
 * 这类值改为<b>整体标记</b>（{@link TruncationContract#jsonMarker}，含 {@code preview}），
 * 仍在帧字节预算内，但绝不破坏可解析性。
 *
 * <h2>预算与复测（A19）</h2>
 * 预算 = 上限 −（其余字段的 JSON 开销，含截断字段本身）；按各字段原始字节大小为权重分配；
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

	/** 截断字段名（统一契约的单一事实源在 {@link TruncationContract}）。 */
	public static final String TRUNCATED_FIELD = TruncationContract.TRUNCATED_FIELD;

	/** 兼容别名（= {@link TruncationContract#NOTICE_FIELD}）。 */
	public static final String TRUNCATED_REASON_FIELD = TruncationContract.LEGACY_REASON_FIELD;

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
		Map<String, Integer> originalChars = new LinkedHashMap<>();
		for (String field : TRUNCATABLE_FIELDS) {
			if (frame.get(field) instanceof String value) {
				fields.add(field);
				originalChars.put(field, value.length());
			}
		}
		boolean modelViewTruncated = Boolean.TRUE.equals(frame.get(TruncationContract.TRUNCATED_FIELD));
		String reason = "frame-bytes-exceeded: original=" + full.length + " limit=" + maxBytes
				+ " truncatedFields=" + String.join(",", fields)
				+ (modelViewTruncated ? " (model-side-view-already-truncated)" : "");

		int budget = maxBytes - overhead(frame, mapper, fields, reason) - STRUCTURE_MARGIN_BYTES;
		if (budget < 0) {
			budget = 0;
		}
		for (int i = 0; i <= MAX_ITERATIONS; i++) {
			distribute(frame, mapper, fields, budget);
			markTruncated(frame, fields, originalChars, full.length, maxBytes, modelViewTruncated);
			byte[] retry = serialize(frame, mapper);
			if (retry == null) {
				return null;
			}
			if (retry.length <= maxBytes) {
				log.warn("SSE 单帧超限已按字段级降采样 type={} 原 {} 字节 → {} 字节（上限 {}，字段 {}）",
						frame.get("type"), full.length, retry.length, maxBytes, fields);
				return retry;
			}
			// 复测仍超：按超出量收缩预算再算一轮
			budget = Math.max(0, budget - (retry.length - maxBytes) - STRUCTURE_MARGIN_BYTES);
		}
		// 迭代到上限仍不满足（超限来自不可截字段）：只挂标记，保持 JSON 合法（绝不字节截断）
		markTruncated(frame, fields, originalChars, full.length, maxBytes, modelViewTruncated);
		frame.put(TruncationContract.NOTICE_FIELD,
				String.valueOf(frame.get(TruncationContract.NOTICE_FIELD)) + "（unreachable-limit: 其余字段本身已超限）");
		frame.put(TruncationContract.LEGACY_REASON_FIELD, frame.get(TruncationContract.NOTICE_FIELD));
		log.warn("SSE 单帧超限且不可截字段占主导 type={} 原 {} 字节（上限 {}）—— 已挂标记但未达上限",
				frame.get("type"), full.length, maxBytes);
		return serialize(frame, mapper);
	}

	/** 挂统一截断字段（notice 说明原始字节/上限/被截字段）。 */
	private static void markTruncated(Map<String, Object> frame, List<String> fields,
			Map<String, Integer> originalChars, int frameBytes, int maxBytes, boolean modelViewTruncated) {
		int original = 0;
		int returned = 0;
		for (String field : fields) {
			original += originalChars.getOrDefault(field, 0);
			String value = frame.get(field) instanceof String text ? text : "";
			returned += value.length();
		}
		String notice = "frame-bytes-exceeded: original=" + frameBytes + " limit=" + maxBytes + " truncatedFields="
				+ String.join(",", fields) + "；单帧字节超限，已对上述字段做头部降采样（返回 " + returned + " 字符，原 "
				+ original + " 字符），完整结果见执行台账。";
		if (modelViewTruncated) {
			notice = notice + "模型侧视图此前亦已截断。";
		}
		frame.put(TruncationContract.TRUNCATED_FIELD, true);
		frame.put(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		frame.put(TruncationContract.ORIGINAL_CHARS_FIELD, original);
		frame.put(TruncationContract.RETURNED_CHARS_FIELD, returned);
		frame.put(TruncationContract.NOTICE_FIELD, notice);
		frame.put(TruncationContract.LEGACY_REASON_FIELD, notice);
	}

	/** 其余字段（去掉可截字段 + 统一截断字段）的 JSON 开销。 */
	private static int overhead(Map<String, Object> frame, ObjectMapper mapper, List<String> fields, String reason) {
		Map<String, Object> probe = new LinkedHashMap<>(frame);
		for (String field : fields) {
			probe.remove(field);
		}
		probe.put(TruncationContract.TRUNCATED_FIELD, true);
		probe.put(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		probe.put(TruncationContract.ORIGINAL_CHARS_FIELD, 0);
		probe.put(TruncationContract.RETURNED_CHARS_FIELD, 0);
		probe.put(TruncationContract.NOTICE_FIELD, reason);
		probe.put(TruncationContract.LEGACY_REASON_FIELD, reason);
		byte[] bytes = serialize(probe, mapper);
		return bytes == null ? 0 : bytes.length;
	}

	/** 按各字段原始字节大小为权重分配预算，并把每个字段降采样到自己的预算内（末字段拿余量）。 */
	private static void distribute(Map<String, Object> frame, ObjectMapper mapper, List<String> fields, int budget) {
		if (fields.isEmpty()) {
			return;
		}
		if (budget <= 0) {
			for (String field : fields) {
				frame.put(field, takeValue((String) frame.get(field), 0, mapper));
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
			frame.put(fields.get(i), takeValue((String) frame.get(fields.get(i)), shares[i], mapper));
		}
	}

	/**
	 * 单个字段值的降采样：普通文本按字符截断；JSON 整体标记（保留头部分片）。
	 *
	 * <p>两条路径都以"该值在 JSON 里的转义后字节数"为预算，口径与整帧判定一致。
	 */
	static String takeValue(String value, int byteBudget, ObjectMapper mapper) {
		if (value == null || value.isEmpty() || byteBudget <= 0) {
			return "";
		}
		if (!TruncationContract.looksStructured(value)) {
			return takeChars(value, byteBudget, mapper);
		}
		String notice = "结构化结果（JSON）超限，未做字符截断（截断会产生非法 JSON）；已整体标记并保留头部预览，"
				+ "完整结果见执行台账。";
		String emptyMarker = TruncationContract.jsonMarker("", value.length(), notice);
		int remaining = byteBudget - escapedLength(emptyMarker, mapper);
		String preview = remaining > 0 ? takeChars(value, remaining, mapper) : "";
		for (int i = 0; i < 4; i++) {
			String marker = TruncationContract.jsonMarker(preview, value.length(), notice);
			int used = escapedLength(marker, mapper);
			if (used <= byteBudget) {
				return marker;
			}
			int over = used - byteBudget;
			preview = takeChars(preview, Math.max(0, escapedLength(preview, mapper) - over), mapper);
			if (preview.isEmpty()) {
				return TruncationContract.jsonMarker("", value.length(), notice);
			}
		}
		return TruncationContract.jsonMarker(preview, value.length(), notice);
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
