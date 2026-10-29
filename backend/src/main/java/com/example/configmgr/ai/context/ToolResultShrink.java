package com.example.configmgr.ai.context;

import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 越窗时的最后一刀：把<b>最新、未受保护</b>的工具结果<b>收窄</b>（C13 第 3 项）。
 *
 * <h2>为什么需要它（与 L1 的分工）</h2>
 * L1（{@link ToolResultClearing}）只清 {@code keep-tool-pairs} 之外的<b>旧</b>结果；最近的几对一律不碰
 * （模型当下最可能要复用的就是它们）。小窗口模型上，恰恰是这几对把窗口吃满：本批工具结果落定之后
 * {@code nextEst >= limit}，而 L1 一个都清不掉 ⇒ 这一轮只能以 {@code CONTEXT_OVERFLOW} 收场。
 *
 * <p>收窄是介于"留着全文"与"清成占位符"之间的第三档：<b>保留头部分片 + 截断标记</b>，让模型知道
 * "这份结果不是全部、完整结果见本轮工具调用记录"，而不是让它以为拿到的是全文或者什么都没有。
 *
 * <h2>四条不可动的边界</h2>
 * <ol>
 * <li><b>不删消息、不改 id、不改名字</b>：只换 {@code responseData} 的内容 ⇒ 配对骨架不变；</li>
 * <li><b>未决 HITL 条目永不动</b>（调用方传 {@code protectedToolCallIds}；传 {@code null} 等于
 * "读不到保护集"⇒ 整个方案不生效，宁可不省 token 也不动用户正在复核的那一条）；</li>
 * <li><b>已清除的占位符不重复处理</b>（幂等）；</li>
 * <li><b>截断字段仍是同一套</b>（{@link ToolResultLimiter.Truncation}）—— 与分档裁剪、帧侧
 * {@code TruncationContract} 同源，模型侧 {@code truncated=true} 不会被抹掉。</li>
 * </ol>
 *
 * <h2>纯函数</h2>
 * 不写存储、不发帧：只返回新的消息列表与"收窄了哪几条"。归档/台账由调用方决定（本轮不新写存储：
 * 工具结果的全文本来就在 {@code ai:ledger:<runId>} 里，收窄只改"模型看到的那一份"）。
 */
public final class ToolResultShrink {

	/** 收窄事件原因（trace/日志同一字符串）。 */
	public static final String REASON_REMAINING_WINDOW = "turn_remaining_window_shrink";

	/**
	 * 单条结果收窄后至少保留的 token 估算。
	 *
	 * <p>再小就只剩标记：模型既看不懂是什么，也不知道要不要重查。收窄只是"让这一跳发得出去"的
	 * 最后手段，真正的信息保底仍是台账全文。
	 */
	public static final int MIN_KEEP_TOKENS = 200;

	/**
	 * 一条被收窄的结果。
	 *
	 * @param toolCallId 调用 id（回取台账全文的键）
	 * @param name       工具名
	 * @param beforeTokens 收窄前估算
	 * @param afterTokens  收窄后估算
	 */
	public record Shrunk(String toolCallId, String name, int beforeTokens, int afterTokens) {
	}

	/**
	 * 收窄方案。
	 *
	 * @param messages    收窄后的消息列表（{@link #changed()} 为 false 时与原列表同引用）
	 * @param shrunk      被收窄的条目（按"最新优先"）
	 * @param savedTokens 省下的估算 token
	 */
	public record Plan(List<Message> messages, List<Shrunk> shrunk, int savedTokens) {

		public boolean changed() {
			return !this.shrunk.isEmpty();
		}

		public List<String> shrunkIds() {
			return this.shrunk.stream().map(Shrunk::toolCallId).toList();
		}

	}

	private ToolResultShrink() {
	}

	/**
	 * 规划一次收窄（纯函数）。
	 *
	 * <p><b>目标 = {@code limit − 1}（不是 {@code limit − reserve}）</b>：越窗判据是
	 * {@code nextEst >= limit}，收到"恰好等于窗口"仍算越窗 —— 旧目标在 {@code reserve = 0} 时
	 * 正好削到 {@code limit}，白削一刀还照样失败。留 1 token 余量之后，这一跳真的落在窗口内；
	 * 随后按既有 95% 规则 WRAP_UP / arm（收尾指令本身也占 token，这是有意的安全后果）。
	 *
	 * @param messages              当前历史（原消息对象不被修改）
	 * @param currentTokens         当前判据值（{@code nextEst}）
	 * @param limitTokens           实际窗口
	 * @param protectedToolCallIds  未决 HITL 条目（{@code null} = 读不到保护集 ⇒ 不生效）
	 * @param limiter               裁剪器（截断判定与模型侧写入口同一份）
	 */
	public static Plan plan(List<Message> messages, int currentTokens, int limitTokens,
			Set<String> protectedToolCallIds, ToolResultLimiter limiter) {
		if (messages == null || messages.isEmpty() || limiter == null || protectedToolCallIds == null) {
			return new Plan(messages, List.of(), 0);
		}
		int target = Math.max(0, limitTokens - 1);
		int needToSave = currentTokens - target;
		if (needToSave <= 0) {
			return new Plan(messages, List.of(), 0);
		}
		List<Slot> candidates = candidates(messages, protectedToolCallIds);
		if (candidates.isEmpty()) {
			return new Plan(messages, List.of(), 0);
		}
		List<Shrunk> shrunk = new ArrayList<>();
		Map<Integer, Map<Integer, String>> replacements = new HashMap<>();
		int savedTokens = 0;
		int remainingNeed = needToSave;
		for (int index = 0; index < candidates.size(); index++) {
			if (remainingNeed <= 0) {
				break;
			}
			Slot slot = candidates.get(index);
			// 最新优先"按需"收窄：先让最新的一条吐出缺口（到 MIN_KEEP 为止），仍不够才动下一条 ——
			// 均匀摊薄会把"还能读全的最新结果"也一起削掉，而模型接下来最可能用的就是它。
			// 同一条最多重试 3 次：字符额度 → token 的换算是保守近似（标记本身也占字符），
			// 一次削下来可能差几十 token；差多少再补多少，直到真的落在窗口内。
			int dataTokens = slot.tokens();
			int before = dataTokens;
			String data = slot.data();
			for (int attempt = 0; attempt < 3 && remainingNeed > 0; attempt++) {
				// 防御：这一条是我们自己拼出来的（必然合法），但收窄是"最后一道救场"，
				// 解析异常也不能把整轮恢复弄崩 —— 跳过它、让后面的条目/硬截断接手。
				ToolResultEnvelope envelope;
				try {
					envelope = ToolResultEnvelope.fromJson(data);
				}
				catch (RuntimeException ex) {
					break;
				}
				if (envelope == null) {
					break;
				}
				// 额度算在 message 上（信封外壳/字段不参与"还能留多少正文"），否则第二轮会算出
				// "刚好等于当前长度"的额度而原地打转。
				int messageTokens = ContextEstimator.estimate(envelope.message());
				int take = Math.min(remainingNeed, Math.max(0, messageTokens - MIN_KEEP_TOKENS));
				if (take <= 0) {
					break;
				}
				int capChars = ToolResultLimiter.charsForRemainingTokens(messageTokens - take);
				if (capChars <= 0 || envelope.message().length() <= capChars) {
					break;
				}
				ToolResultLimiter.Truncation truncation = limiter.forModel(slot.name(), envelope.message(), capChars);
				if (!truncation.truncated()) {
					break;
				}
				// I6：再收缩不得把<b>首次</b>的 originalChars 改写成"这次看到的长度"。
				// 已被裁过的结果（如 originalChars=20000 / message≈5100）再收一刀时，forModel 只看得见
				// 现在这份文本；沿用更大的那个才是"原文有多长"的事实（取两值最大，不新造字段）。
				truncation = keepLargerOriginalChars(truncation, envelope.originalChars());
				String rewritten = envelope.withTruncation(truncation).toModelJsonWith(truncation);
				int after = ContextEstimator.estimate(rewritten);
				if (after >= dataTokens) {
					break;
				}
				data = rewritten;
				remainingNeed = Math.max(0, remainingNeed - (dataTokens - after));
				dataTokens = after;
			}
			if (dataTokens >= before) {
				continue;
			}
			replacements.computeIfAbsent(slot.messageIndex(), key -> new HashMap<>()).put(slot.responseIndex(), data);
			shrunk.add(new Shrunk(slot.toolCallId(), slot.name(), before, dataTokens));
			savedTokens += before - dataTokens;
		}
		if (shrunk.isEmpty()) {
			return new Plan(messages, List.of(), 0);
		}
		List<Message> out = new ArrayList<>(messages.size());
		for (int i = 0; i < messages.size(); i++) {
			Map<Integer, String> patch = replacements.get(i);
			out.add(patch == null ? messages.get(i) : rebuild((ToolResponseMessage) messages.get(i), patch));
		}
		return new Plan(out, List.copyOf(shrunk), savedTokens);
	}

	/**
	 * 保留更大的 {@code originalChars}（I6）：二次/多次收缩时，"早先那份原文有多长"不能被覆盖成
	 * "这次看到的长度" —— 否则信封字段与台账全文自相矛盾。
	 */
	private static ToolResultLimiter.Truncation keepLargerOriginalChars(ToolResultLimiter.Truncation truncation,
			int previousOriginalChars) {
		int original = Math.max(truncation.originalChars(), previousOriginalChars);
		if (original == truncation.originalChars()) {
			return truncation;
		}
		return new ToolResultLimiter.Truncation(truncation.truncated(), truncation.strategy(), original,
				truncation.returnedChars(), truncation.notice(), truncation.text());
	}

	/**
	 * 可收窄的条目（<b>最新优先</b>）：跳过受保护的、已清除的、以及不像信封（解析失败）的结果。
	 *
	 * <p>解析失败的那类通常来自更早的实现或非工具通道（裸文本），改写成信封会换掉形态 ——
	 * 宁可少省一点，也不要给模型一份结构变了的"历史"。
	 */
	private static List<Slot> candidates(List<Message> messages, Set<String> protectedIds) {
		List<Slot> candidates = new ArrayList<>();
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (!(messages.get(i) instanceof ToolResponseMessage toolResponse)) {
				continue;
			}
			List<ToolResponseMessage.ToolResponse> responses = toolResponse.getResponses();
			for (int j = responses.size() - 1; j >= 0; j--) {
				ToolResponseMessage.ToolResponse response = responses.get(j);
				String data = response.responseData();
				if (data == null || data.isEmpty() || ToolResultClearing.isAlreadyCleared(data)) {
					continue;
				}
				if (response.id() != null && protectedIds.contains(response.id())) {
					continue;
				}
				ToolResultEnvelope envelope;
				try {
					envelope = ToolResultEnvelope.fromJson(data);
				}
				catch (RuntimeException ex) {
					continue;
				}
				if (envelope == null || envelope.message() == null || envelope.message().isEmpty()) {
					continue;
				}
				candidates.add(new Slot(i, j, response.id(), response.name(), ContextEstimator.estimate(data), data));
			}
		}
		return candidates;
	}

	private static ToolResponseMessage rebuild(ToolResponseMessage original, Map<Integer, String> patch) {
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(original.getResponses());
		for (Map.Entry<Integer, String> entry : patch.entrySet()) {
			ToolResponseMessage.ToolResponse existing = responses.get(entry.getKey());
			responses.set(entry.getKey(),
					new ToolResponseMessage.ToolResponse(existing.id(), existing.name(), entry.getValue()));
		}
		ToolResponseMessage.Builder builder = ToolResponseMessage.builder().responses(responses);
		if (original.getMetadata() != null && !original.getMetadata().isEmpty()) {
			builder.metadata(original.getMetadata());
		}
		return builder.build();
	}

	private record Slot(int messageIndex, int responseIndex, String toolCallId, String name, int tokens,
			String data) {
	}

}
