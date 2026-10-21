package com.example.configmgr.ai.context;

import com.example.configmgr.ai.run.UsageDetails;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * usage 计量器（上下文统计与压缩专项批①；设计稿 §3.1 的 R1–R6）。
 *
 * <p>
 * <b>为什么不能直读末帧</b>（现状的两个失真面，类注已记在 {@code ResilientChatService.Accumulator}）：
 * <ol>
 * <li>Spring AI 的流式聚合<b>对每个分片都挂一个 usage 对象</b>（常见 0/0/0，也有网关把同一份真值
 * 每个 chunk 重复一遍）——"直读末帧"遇到"末帧 0/0/0"就把真值读成 0，遇到"每块重复"若按累加实现
 * 就会放大 N 倍（业界踩过的 1.7M tokens 事故）；</li>
 * <li>带工具的一轮里客户端可见的 usage 可能来自<b>前面的工具轮</b>（累计值），于是"有没有 usage"
 * 不能当终帧判据（SM-02c 实测）——本类因此只管"取哪个数"，不参与断流判定。</li>
 * </ol>
 *
 * <table>
 * <caption>六条规则与 kill</caption>
 * <tr><th>规则</th><th>内容</th><th>kill（改哪一行会让用例红）</th></tr>
 * <tr><td>R1 采集</td><td>三维全 ≤0 = 空帧，不参与</td><td>去掉空帧判定 ⇒ 末帧 0/0/0 被当真值</td></tr>
 * <tr><td>R2 去重</td><td>与已采纳值三维全等 = 只计一次</td><td>删去重并改成累加 ⇒ 值 ×N</td></tr>
 * <tr><td>R3 取最后</td><td>采纳最后一个<b>非空</b>候选</td><td>改成取第一帧 ⇒ 值不等</td></tr>
 * <tr><td>R4 单调</td><td>新候选 {@code prompt} 小于已采纳值 ⇒ 判脏帧、保留旧值、{@code dirty++}，不抛错</td>
 * <td>删 R4 ⇒ 脏帧把值拉小</td></tr>
 * <tr><td>R5 回退</td><td>全轮无有效候选 ⇒ {@code est=true}、{@code source=estimate}，值取估算器</td>
 * <td>{@code est} 恒 false ⇒ 前端 {@code ≈} 断言红</td></tr>
 * <tr><td>R6 作用域</td><td>每次 attempt 新建本类实例（调用方保证）⇒ 重试天然不串值</td>
 * <td>跨 attempt 复用同一实例 ⇒ 混值</td></tr>
 * </table>
 *
 * <p>
 * 输出契约（{@code done.usage} 的增量键，既有三维不动）：{@code est} · {@code source} ·
 * {@code frames} · {@code dirty} + 既有 {@code promptTokens/completionTokens/totalTokens}
 * （+ {@code cachedTokens/reasoningTokens} 可选，仍走 {@link UsageDetails#enrich}）。
 *
 * <p>
 * <b>模型名另有一套口径</b>：{@code model} 取<b>首个非空 metadata</b>（不是末帧 —— 流式聚合的后续
 * 分片经常不带 {@code model}，按末帧取会在多数轮次里丢成 null）。这与 usage 的"取最后"不矛盾：
 * 两者的取值对象不同（模型的元数据 vs 用量计数）。
 *
 * <p>
 * 本类只在运行线程里被调用（{@code consume()} 的单线程回调），故不做同步；跨线程复用是误用。
 */
public final class ContextMeter {

	/** 真值来源标记：上游给了 usage。 */
	public static final String SOURCE_STREAM = "stream";

	/** 真值来源标记：回退到估算器（前端据此显示 {@code ≈}）。 */
	public static final String SOURCE_ESTIMATE = "estimate";

	private int promptTokens;

	private int completionTokens;

	private int totalTokens;

	private boolean measured;

	private int frames;

	private int dirty;

	private Object nativeUsage;

	private String model;

	/**
	 * 采集一个上游响应（R1–R4）。
	 *
	 * @param response 流式聚合产出的单个响应；{@code null} 直接忽略（防御性，不影响语义）
	 */
	public void accept(ChatResponse response) {
		if (response == null) {
			return;
		}
		if (this.model == null && response.getMetadata() != null) {
			this.model = response.getMetadata().getModel();
		}
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		if (usage == null) {
			return;
		}
		Integer prompt = usage.getPromptTokens();
		Integer completion = usage.getCompletionTokens();
		Integer total = usage.getTotalTokens();
		if (prompt == null && completion == null && total == null) {
			return;
		}
		int pv = prompt == null ? 0 : prompt;
		int cv = completion == null ? 0 : completion;
		int tv = total == null ? 0 : total;
		// R1：空帧（0/0/0）不参与 —— 这是"末帧 0/0/0"失真的第一道闸
		if (pv <= 0 && cv <= 0 && tv <= 0) {
			return;
		}
		this.frames++;
		// R2：与已采纳值三维全等 ⇒ 重复帧，不重复计（治"每个 chunk 都挂同一份 usage"）
		if (this.measured && pv == this.promptTokens && cv == this.completionTokens && tv == this.totalTokens) {
			return;
		}
		// R4：prompt 回退 ⇒ 脏帧。保留已采纳的较大值、计数，绝不抛错（计量不能打断轮次）
		if (this.measured && pv < this.promptTokens) {
			this.dirty++;
			return;
		}
		// R3：采纳"最后一个非空"候选
		this.promptTokens = pv;
		this.completionTokens = cv;
		this.totalTokens = tv;
		this.measured = true;
		this.nativeUsage = usage.getNativeUsage();
	}

	public boolean measured() {
		return this.measured;
	}

	public int promptTokens() {
		return this.measured ? this.promptTokens : 0;
	}

	public int completionTokens() {
		return this.measured ? this.completionTokens : 0;
	}

	/** 采集到的非空 usage 帧数（诊断面：{@code done.usage.frames}）。 */
	public int frames() {
		return this.frames;
	}

	/** 被判脏的帧数（{@code prompt} 回退）。 */
	public int dirty() {
		return this.dirty;
	}

	/** 上游给出的模型名（取<b>首个非空 metadata</b> 的 model：流式聚合的后续帧常不带它，末帧取会丢）。 */
	public String model() {
		return this.model;
	}

	/**
	 * {@code done.usage} 的载荷（三维 + R1–R5 的增量键）。
	 *
	 * @param fallbackEstimate 全轮无有效 usage 时的回退估算值（由调用方按<b>本轮请求</b>估算：
	 *                         system + tools + 历史 + 本轮输入）；有真值时该参数不起作用
	 */
	public Map<String, Object> usage(int fallbackEstimate) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (this.measured) {
			out.put("promptTokens", this.promptTokens);
			out.put("completionTokens", this.completionTokens);
			out.put("totalTokens", this.totalTokens);
			out.put("est", false);
			out.put("source", SOURCE_STREAM);
			// T8：可选维度（cachedTokens / reasoningTokens）—— 取不到即省略
			UsageDetails.enrich(out, this.nativeUsage);
		}
		else {
			int estimate = Math.max(0, fallbackEstimate);
			out.put("promptTokens", estimate);
			out.put("completionTokens", 0);
			out.put("totalTokens", estimate);
			out.put("est", true);
			out.put("source", SOURCE_ESTIMATE);
		}
		out.put("frames", this.frames);
		out.put("dirty", this.dirty);
		return out;
	}

}
