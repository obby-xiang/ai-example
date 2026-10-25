package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.TruncationContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * {@code truncated / strategy / originalChars / returnedChars / noticeCode / notice}；
 * {@code truncatedReason} 保留为 {@code notice} 的兼容别名（旧客户端仍读它）；
 * 新客户端按 {@code noticeCode} + 数字本地化，{@code notice} 只作旧客户端英文降级。
 * <b>语义边界</b>：本键是"单帧字节上限"，超限时产出的仍是一份<b>完整合法的帧</b>
 * （字段级降采样 + 标记）。它表达"这一帧没给全"，因此只用于<b>无法跨帧续接</b>的字段
 * （{@code tool_result.result} 等）。
 *
 * <h2>跨帧分片（2026-10-25 截断专项）</h2>
 * 可续接的流式正文（{@code delta.text}，含 {@code kind=reasoning}）<b>不走</b>字段级截断：
 * 超限时由 {@link #splitStringValue} 按"整帧 JSON ≤ 上限"切成若干片段，写出器把它们发成
 * <b>同一 {@code messageId} 的多条既有 {@code delta}</b>（不新增帧型）——
 * 于是实时拼接、归档回放、完成后历史三处都不丢字，也无需任何截断标记。
 * 判据是本类既有的转义后字节口径（{@link #escapedLength}），CJK / emoji / 代理对不会被切半。
 * 上限小到无法安全分片（骨架/单码点放不下）时<b>抛 {@link IllegalStateException}</b>，
 * 由轮次的既有失败面收尾 —— 绝不回落字段截断或空串（那等于静默丢字）。
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

	/**
	 * 单帧上限的<b>运行期下限</b>（字节）。
	 *
	 * <p>
	 * 低于该值时，一个 {@code delta} 帧的骨架（type/atMs/messageId/runId/seq）加上一个最坏码点
	 * （emoji 在 JSON 里转义为 12 字节）就放不下，<b>无法安全分片</b>；若允许这样的配置，
	 * 唯一出路只能是字段截断（正文永久少字）或空串，两者都被本专项明确禁止。
	 * 因此：装配期（{@link SseChatEmitter} 构造器）直接失败，配置面（{@code AiProperties}）
	 * 的启动下限由主会话按同一数值接线。
	 */
	public static final int MIN_FRAME_BYTES = 1024;

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
		// 仅用于估算 notice 的 JSON 开销，不写进帧；真实用户文案由 markTruncated 产出。
		String noticeProbe = legacyNotice(TruncationContract.NOTICE_CODE_FRAME_TRUNCATED);

		int budget = maxBytes - overhead(frame, mapper, fields, noticeProbe) - STRUCTURE_MARGIN_BYTES;
		if (budget < 0) {
			budget = 0;
		}
		for (int i = 0; i <= MAX_ITERATIONS; i++) {
			distribute(frame, mapper, fields, budget);
			int[] chars = markTruncated(frame, fields, originalChars,
					TruncationContract.NOTICE_CODE_FRAME_TRUNCATED);
			byte[] retry = serialize(frame, mapper);
			if (retry == null) {
				return null;
			}
			if (retry.length <= maxBytes) {
				log.warn("SSE 单帧超限已按字段级降采样 type={} original={} actual={} limit={} fields={} originalChars={} returnedChars={}",
						frame.get("type"), full.length, retry.length, maxBytes, String.join(",", fields), chars[0], chars[1]);
				return retry;
			}
			// 复测仍超：按超出量收缩预算再算一轮
			budget = Math.max(0, budget - (retry.length - maxBytes) - STRUCTURE_MARGIN_BYTES);
		}
		// 迭代到上限仍不满足（超限来自不可截字段）：只挂标记，保持 JSON 合法（绝不字节截断）
		markTruncated(frame, fields, originalChars, TruncationContract.NOTICE_CODE_FRAME_LIMIT_UNREACHED);
		log.warn("SSE 单帧超限且不可截字段占主导 type={} original={} limit={} fields={} —— 已挂标记但未达上限",
				frame.get("type"), full.length, maxBytes, String.join(",", fields));
		return serialize(frame, mapper);
	}

	/**
	 * 把帧里的一个字符串字段切成若干片段，使<b>每一次</b>"该帧换成这一片"的整帧 JSON 都不超过
	 * {@code maxBytes}；片段按序拼接 === 原值（保原文、保顺序、不切代理对）。
	 *
	 * <p>
	 * 只给<b>可跨帧续接</b>的流式字段用（{@code delta.text}）：调用方把每个片段发成一条同
	 * {@code messageId} 的既有 {@code delta}。{@code result} 这类"一帧一份"的字段仍走
	 * {@link #apply} 的字段级降采样（截断标记语义不同：那是"内容没给全"）。
	 *
	 * <p>
	 * <b>为什么能保证上限</b>：JSON 对象里字段值的字节数 = 值转义后字节数，因此以
	 * "同骨架 + 空值"的序列化长度为基准，按 {@link #takeChars} 的转义字节口径切分即可。
	 * 调用方负责把<b>尚未写入的字段</b>也放进骨架（{@code seq} 用上界 {@link Long#MAX_VALUE}、
	 * {@code runId} 用真值）—— 实际帧只会更短，不会更长。
	 *
	 * <p>
	 * <b>失败语义（复审 C1/I1）</b>：上限小到放不下帧骨架、放不下一个码点、或复测收缩仍超限时，
	 * <b>抛 {@link IllegalStateException}</b>（消息含 {@code type/key/limit/originalChars}）——
	 * 绝不回落字段截断、绝不返回空串或超限片。理由是流式正文一旦被截断就<b>永久少字</b>
	 * （实时、归档、完成后历史三处同时缺失，重挂差量也补不回来）；宁可让本轮走既有失败面
	 * （error 帧 + 可重试/可观测），也不能静默交出一份"看起来成功但内容缺半"的回答。
	 * 生产默认 64KB 与运行期下限 {@value #MIN_FRAME_BYTES} 字节保证这条分支不可达。
	 *
	 * @param frame    帧骨架（含该字段的完整值；方法内部会用空值重算骨架开销）
	 * @param field    字段名（流式正文 = {@code text}）
	 * @param mapper   与出帧同一个序列化器
	 * @param maxBytes 单帧上限（UTF-8 字节；必须 {@code > 0}）
	 * @return 非空片段列表（原值为空串时返回单元素列表）
	 * @throws IllegalStateException    骨架/单码点放不下，或复测收缩仍超限
	 * @throws IllegalArgumentException {@code maxBytes <= 0} 或字段不是 String
	 */
	static List<String> splitStringValue(Map<String, Object> frame, String field, ObjectMapper mapper, int maxBytes) {
		Objects.requireNonNull(frame, "frame");
		Objects.requireNonNull(field, "field");
		Objects.requireNonNull(mapper, "mapper");
		Object raw = frame.get(field);
		if (!(raw instanceof String value)) {
			throw new IllegalArgumentException("字段必须是 String：field=" + field + "，当前 = "
					+ (raw == null ? "null" : raw.getClass().getName()));
		}
		if (maxBytes <= 0) {
			// 拆分器的契约是"必须给上限"；"<= 0 = 不限制"只存在于 apply 的兼容路径，
			// 流式正文不允许走那条路（那等于关掉单帧上限）。
			throw new IllegalArgumentException("maxBytes 必须 > 0（当前 = " + maxBytes + "）：流式正文不允许无上限");
		}
		Map<String, Object> probe = new LinkedHashMap<>(frame);
		probe.put(field, "");
		byte[] empty = serialize(probe, mapper);
		if (empty == null) {
			throw new IllegalStateException("帧骨架序列化失败：type=" + frame.get("type") + " key=" + field
					+ " limit=" + maxBytes + " originalChars=" + value.length());
		}
		int budget = maxBytes - empty.length;
		if (budget <= 0) {
			throw tooSmall(frame, field, maxBytes, empty.length, value.length(), null, "帧骨架已占满单帧上限");
		}
		if (value.isEmpty()) {
			return List.of(value);
		}
		// 整帧字节 = 空值骨架 + 该片段的转义后字节（两侧引号已在骨架里），故预算即 budget。
		List<String> chunks = new ArrayList<>();
		int offset = 0;
		while (offset < value.length()) {
			String rest = value.substring(offset);
			chunks.add(fitChunk(rest, frame, field, probe, mapper, maxBytes, budget, empty.length, value.length()));
			offset += chunks.get(chunks.size() - 1).length();
		}
		return chunks;
	}

	/**
	 * 取不超过 {@code budget} 转义字节的片段，并按整帧复测收缩（最多 4 轮）。
	 *
	 * <p>
	 * 任何一步无法产出<b>既非空又不超限</b>的片段都直接抛 {@link IllegalStateException}：
	 * 空片段会让调用方陷入死循环或交出"丢了这段正文"的空串，超限片段会被下游
	 * {@link #apply} 截断 —— 两者都是本专项明确禁止的静默丢字。
	 */
	private static String fitChunk(String rest, Map<String, Object> frame, String field,
			Map<String, Object> probe, ObjectMapper mapper, int maxBytes, int budget, int skeletonBytes,
			int originalChars) {
		String chunk = takeChars(rest, budget, mapper);
		for (int i = 0; i <= 4; i++) {
			if (chunk.isEmpty()) {
				int firstCodePoint = rest.codePointAt(0);
				int needed = escapedLength(new String(Character.toChars(firstCodePoint)), mapper);
				throw tooSmall(frame, field, maxBytes, skeletonBytes, originalChars, needed,
						"预算放不下一个码点（codePoint=U+" + Integer.toHexString(firstCodePoint).toUpperCase()
								+ "）");
			}
			Map<String, Object> candidate = new LinkedHashMap<>(probe);
			candidate.put(field, chunk);
			byte[] bytes = serialize(candidate, mapper);
			if (bytes == null) {
				throw new IllegalStateException("分片骨架序列化失败：type=" + frame.get("type") + " key=" + field
						+ " limit=" + maxBytes + " originalChars=" + originalChars);
			}
			int over = bytes.length - maxBytes;
			if (over <= 0) {
				return chunk;
			}
			chunk = takeChars(chunk, escapedLength(chunk, mapper) - over, mapper);
		}
		throw tooSmall(frame, field, maxBytes, skeletonBytes, originalChars, null, "复测收缩 5 轮后仍超限");
	}

	/** 统一的"配置/输入无法安全分片"失败：带 type/key/limit/originalChars 等可定位信息。 */
	private static IllegalStateException tooSmall(Map<String, Object> frame, String field,
			int maxBytes, int skeletonBytes, int originalChars, Integer firstCodePointBytes, String reason) {
		StringBuilder message = new StringBuilder("单帧上限过小，流式正文拒绝截断/空串兜底：reason=")
				.append(reason)
				.append(" type=").append(frame.get("type"))
				.append(" key=").append(field)
				.append(" limit=").append(maxBytes)
				.append(" originalChars=").append(originalChars)
				.append(" skeletonBytes=").append(skeletonBytes);
		if (firstCodePointBytes != null) {
			message.append(" firstCodePointBytes=").append(firstCodePointBytes);
		}
		message.append("（请提高 app.ai.result-budget.ui.max-frame-bytes，运行期下限 ")
				.append(MIN_FRAME_BYTES).append("）");
		return new IllegalStateException(message.toString());
	}

	/**
	 * 挂统一截断字段（notice 只写语言中立的旧客户端短句；数字/字段名进结构化字段与 warn 日志）。
	 *
	 * @return {@code [原始字符数, 返回字符数]}，供调用方写诊断日志
	 */
	private static int[] markTruncated(Map<String, Object> frame, List<String> fields,
			Map<String, Integer> originalChars, String noticeCode) {
		int original = 0;
		int returned = 0;
		for (String field : fields) {
			original += originalChars.getOrDefault(field, 0);
			String value = frame.get(field) instanceof String text ? text : "";
			returned += value.length();
		}
		String notice = legacyNotice(noticeCode);
		frame.put(TruncationContract.TRUNCATED_FIELD, true);
		frame.put(TruncationContract.NOTICE_CODE_FIELD, noticeCode);
		if (fields.isEmpty()) {
			frame.remove(TruncationContract.STRATEGY_FIELD);   // 无可压缩字段 ⇒ 可选键缺省，而非写 null
		}
		else {
			frame.put(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
		}
		frame.put(TruncationContract.ORIGINAL_CHARS_FIELD, original);
		frame.put(TruncationContract.RETURNED_CHARS_FIELD, returned);
		frame.put(TruncationContract.NOTICE_FIELD, notice);
		frame.put(TruncationContract.LEGACY_REASON_FIELD, notice);
		return new int[] { original, returned };
	}

	/**
	 * 旧客户端兼容的 {@code notice/truncatedReason}：语言中立（英文）、不含数字/字段名/机器前缀。
	 * 新客户端一律按 {@code noticeCode} + 本地模板展示。
	 */
	static String legacyNotice(String noticeCode) {
		return switch (noticeCode) {
			case TruncationContract.NOTICE_CODE_FRAME_LIMIT_UNREACHED ->
					"Result exceeds the frame size limit and could not be compressed further.";
			case TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED ->
					"Result truncated for the model; this frame shows the full result.";
			default -> "Result truncated because it exceeded the frame size limit.";
		};
	}

	/** 其余字段（去掉可截字段 + 统一截断字段）的 JSON 开销。 */
	private static int overhead(Map<String, Object> frame, ObjectMapper mapper, List<String> fields, String reason) {
		Map<String, Object> probe = new LinkedHashMap<>(frame);
		for (String field : fields) {
			probe.remove(field);
		}
		probe.put(TruncationContract.TRUNCATED_FIELD, true);
		probe.put(TruncationContract.NOTICE_CODE_FIELD, TruncationContract.NOTICE_CODE_FRAME_TRUNCATED);
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
		// 该 marker 会原样进入帧 result（用户可见）：语言中立英文、不承诺不可达的“台账/记录”路径。
		String notice = "Structured result exceeded the frame size limit; marked as a JSON envelope with a header preview.";
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
	/**
	 * 按<b>字符</b>截断到不超过 byteBudget 的转义字节数（二分 + 代理对保护）。
	 *
	 * <p>
	 * 上界直接收到 {@code min(value.length(), byteBudget)}：JSON 里每个 code unit 至少占 1 字节，
	 * 因此任何超过 {@code byteBudget} 个 code unit 的前缀必然超预算。长文本（大输入）里这避免了
	 * 每片都对<b>整段余串</b>做二分探测（旧上界 = 余串长度）；结果与旧实现逐字相同。
	 */
	static String takeChars(String value, int byteBudget, ObjectMapper mapper) {
		if (value == null || value.isEmpty() || byteBudget <= 0) {
			return "";
		}
		int lo = 0;
		int hi = Math.min(value.length(), byteBudget);
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
