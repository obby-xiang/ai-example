package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.apache.commons.lang3.ObjectUtils;

/**
 * 触发判定（上下文统计与压缩专项批①；设计稿 §3.2）。
 *
 * <h2>两套口径分开（本设计的核心）</h2>
 * <ul>
 * <li><b>展示用真值</b>：{@code usedNow = 最后一次成功请求的 usage.promptTokens}；</li>
 * <li><b>触发用预估</b>：{@code nextEst = usedNow + lastCompletion + estimate(本轮新输入)
 * + Σ(上次真值之后新增的工具结果)}</b> —— 判的是"下一次请求会有多大"，而不是"上一次有多大"。
 * 若拿上一次的 {@code used} 判，轮内工具回环会一路撞到上游 400 才发现（晚一轮）。</li>
 * </ul>
 * 两者的偏差被"<b>触发看估算、展示看真值</b>"隔离；滞回只看真值，故不会因估算抖动反复触发。
 *
 * <h2>阈值</h2>
 * 对 {@code limit − reserve} 取比（先扣预留缓冲再取比）：L1 ≥70% / L2 ≥80% / L3 ≥95%。
 * 自洽性检查（写进 NUMSPEC 依据列）：128K 下 {@code limit − reserve = 115K}，L3@95% 在
 * 13K 预留之外仍余 {@code 0.05 × 115K = 5.75K}；预留本身覆盖 {@code max-tokens 8192}。
 *
 * <p><b>预留按实际窗口收口（C13）</b>：上面的自洽性只在"预留远小于窗口"时成立。小窗口模型
 * （如 15000）上 13000 的绝对预留会把分母压成 2000 —— 任何请求都判成三倍越线，比例阈值失效。
 * 故分母一律用 {@link #effectiveReserve}：预留最多占窗口一半。</p>
 *
 * <p><b>真实输出预留（B1）</b>：本轮请求声明了大于配置预留的 {@code max_tokens} 时，输出空间
 * 就是那个数 —— 预留取两者较大值再按窗口一半收口。轮首触发、滞回解锁、轮内预算三处同一公式
 * （{@link #effectiveReserve(int, int, int)}）。</p>
 *
 * <h2>分母由调用方注入</h2>
 * 本类<b>不读</b>任何配置：分母以参数传入，调用方（{@link ContextCompactor}）从
 * {@link ContextLimitResolver} 取 —— 与 {@code /context}、{@code /health}、压缩日志同一个取数点。
 * 本类只保留阈值百分比、预留缓冲这两个"比例参数"；分母唯一来源是 {@code ai_model.max_context}。
 *
 * <p>
 * <b>每次最多升一级</b>由调用方（{@link ContextCompactor}）执行：本类只回答"按占比该到哪一级"，
 * 从当前级往上最多推一格 —— 一次跳到 L2/L3 会把整个前缀改写、缓存整段重建（调研 Q3）。
 */
public final class ContextTrigger {

	/** 压缩级别。 */
	public enum Level {

		/** 不触发。 */
		NONE,

		/** L1：工具结果清除（最轻、ROI 最高，原文在台账）。 */
		L1,

		/** L2：按 Turn 滑窗 + 递归摘要（批③）。 */
		L2,

		/** L3：硬截断兜底（批③）。 */
		L3;

		/** 级别名（帧与台账的线上值：{@code none}/{@code L1}/{@code L2}/{@code L3}）。 */
		public String wire() {
			return this == NONE ? "none" : name();
		}

		public static Level fromWire(String raw) {
			if (raw == null) {
				return NONE;
			}
			for (Level level : values()) {
				if (level.wire().equalsIgnoreCase(raw.trim())) {
					return level;
				}
			}
			return NONE;
		}

		/** 本次最多升一级：{@code this} 与 {@code next} 之间只前进一格。 */
		public Level advanceTo(Level next) {
			if (next == null || next.ordinal() <= ordinal()) {
				return this;
			}
			return values()[ordinal() + 1];
		}
	}

	/**
	 * 一次判定结果。
	 *
	 * @param level      该占比对应的<b>目标</b>级别（调用方按"最多升一级"收敛）
	 * @param ratio      {@code nextEst / (limit − reserve)}；{@code limit <= 0} 时恒 0（降级态无分母）
	 * @param nextEst    触发用预估（下次请求的 prompt 估算）
	 * @param limitTokens 分母上限（{@code <=0} = 未知）
	 * @param reserveTokens 预留缓冲
	 */
	public record Decision(Level level, double ratio, int nextEst, int limitTokens, int reserveTokens) {

		public boolean triggered() {
			return this.level != Level.NONE;
		}

		/** 含 {@code limit <= 0} 的降级态（调用方据此不发百分比、不做比例判定）。 */
		public boolean degraded() {
			return this.limitTokens <= 0;
		}
	}

	private final AiProperties.Context config;

	public ContextTrigger(AiProperties.Context config) {
		this.config = ObjectUtils.getIfNull(config, AiProperties.Context::new);
	}

	/**
	 * 预留额度按<b>实际模型窗口</b>收口（C13）。
	 *
	 * <p>「预留」的语义是"给输出与一轮工具回环留的绝对额度"（默认 13000，依据见
	 * {@code AiProperties.Context#reserveTokens}）。但绝对额度在<b>小窗口模型</b>上会把分母压成
	 * {@code limit − reserve ≈ 2000}（15000 窗口）：任何一条稍长的请求都被判成"三倍越线"，
	 * 压缩/收尾在轮首都触发，比例阈值彻底失去意义（退化分母）。
	 *
	 * <p>收口规则：预留最多占窗口的一半，剩下的才是比例判定的分母。
	 * 128K 窗口下 {@code min(13000, 64000) = 13000} —— 与既有口径逐字相同（大窗口零变化）。
	 *
	 * @param limitTokens  实际窗口（{@code <=0} = 未知：原样返回，调用方不做比例判定）
	 * @param reserveTokens 配置的预留（{@code <=0} = 无预留）
	 */
	public static int effectiveReserve(int limitTokens, int reserveTokens) {
		return effectiveReserve(limitTokens, reserveTokens, 0);
	}

	/**
	 * 预留额度按<b>实际窗口 + 真实输出预留</b>收口（B1：轮首/轮内同一个公式）。
	 *
	 * <p>
	 * 配置的 {@code reserve-tokens} 是一个静态缓冲（默认 13000），但当本轮请求真的声明了
	 * {@code max_tokens}（例如 32000）时，上游实际需要的输出空间就是 32000 —— 仍按 13000
	 * 扣预留会把触发线整体推高，压缩在该压的时候判不出来。故取 {两者较大值}，再按窗口一半收口
	 * （小窗口上绝对预留仍不得把分母压成个位数）。
	 *
	 * @param maxOutputTokens 本轮请求的生效 {@code max_tokens}（{@code <=0} = 未声明，沿用配置预留）
	 */
	public static int effectiveReserve(int limitTokens, int reserveTokens, int maxOutputTokens) {
		int reserve = Math.max(Math.max(0, reserveTokens), Math.max(0, maxOutputTokens));
		if (limitTokens <= 0) {
			return reserve;
		}
		return Math.min(reserve, Math.max(1, limitTokens / 2));
	}

	/**
	 * 谱系升级是否被允许（滞回只锁<b>同级重复</b>，不阻止升级）。
	 *
	 * <p>B1 修复的缺陷现场：{@code armed=false} 时 {@link #decide} 直接把 decision 压成 NONE，
	 * 连"原始占比已经要求更高一级"也一起挡掉。升级的判据只能看<b>原始目标级</b>与谱系位置。
	 */
	public static boolean isUpgradeAllowed(Level raw, Level lineage) {
		Level target = ObjectUtils.defaultIfNull(raw, Level.NONE);
		Level current = ObjectUtils.defaultIfNull(lineage, Level.NONE);
		return target.ordinal() > current.ordinal();
	}

	/**
	 * 硬越窗（下一发 prompt 估算已经吃满整个窗口，连输出空间都没有）。
	 *
	 * <p>这一档必须能逃逸滞回：否则一次"压完刚好落在 60% 以上"的会话会被永久锁死，
	 * 下一次请求直接撞上游 400，而本地还在报 {@code hysteresis_locked}。
	 */
	public static boolean isHardOverflow(int nextEst, int limitTokens) {
		return limitTokens > 0 && nextEst >= limitTokens;
	}

	public static boolean upgradeAllowed(Level raw, Level lineage) {
		return isUpgradeAllowed(raw, lineage);
	}

	public static boolean hardOverflow(int nextEst, int limitTokens) {
		return isHardOverflow(nextEst, limitTokens);
	}

	public boolean hysteresisUnlocked(int usedNow, int fixedPrefixTokens, int limitTokens, boolean everCompacted) {
		return isHysteresisUnlocked(usedNow, fixedPrefixTokens, limitTokens, everCompacted);
	}

	public boolean hysteresisUnlocked(int usedNow, int fixedPrefixTokens, int limitTokens, boolean everCompacted,
			int maxOutputTokens) {
		return isHysteresisUnlocked(usedNow, fixedPrefixTokens, limitTokens, everCompacted, maxOutputTokens);
	}

	/**
	 * 触发判定。
	 *
	 * @param limitTokens             分母上限（来自 {@link ContextLimitResolver}；{@code <=0} = 未知 ⇒ 降级态）
	 * @param usedPrompt              上次成功请求的 {@code promptTokens}（无真值传 0）
	 * @param lastCompletion          上次成功请求的 {@code completionTokens}
	 * @param newInputTokens          本轮新输入的估算
	 * @param toolResultsSinceMeasure 上次真值之后新增的工具结果估算（轮起点通常为 0：
	 *                                窗口里的工具结果已经计在上次真值里；续跑重建的工具响应才是真增量）
	 * @param thresholdsArmed         是否允许按阈值触发（滞回锁，见 {@link #hysteresisUnlocked}）
	 */
	public Decision decide(int limitTokens, int usedPrompt, int lastCompletion, int newInputTokens,
			int toolResultsSinceMeasure, boolean thresholdsArmed) {
		return decide(limitTokens, usedPrompt, lastCompletion, newInputTokens, toolResultsSinceMeasure, 0,
				thresholdsArmed);
	}

	/**
	 * 触发判定（带<b>整包估算</b>；C13）。
	 *
	 * <h2>为什么需要整包估算</h2>
	 * 真值口径 {@code used + completion + input + toolResults} 只在"有过一次成功的上游请求"时成立 ——
	 * 那时 {@code used} 已经把 system 人设/工作区快照与工具 schema 都算进去了。轮首（新会话、新进程、
	 * {@code ai:ctx:} 过期）与压缩后的第一条请求都<b>没有真值</b>：旧口径这时只算"本轮新输入"，
	 * 于是一份 3000 token 的 system + 4000 token 的工具集在小窗口模型上<b>一个字都没进判据</b>，
	 * 一直到上游 400 才发现。
	 *
	 * <p>因此无真值时改用调用方给的整包估算（system + tools + 历史 + 本轮输入）。
	 * 有真值时仍走真值口径 —— 真值是账单，估算只负责兜底（"真值优先"不变）。
	 *
	 * @param wholeRequestEstimate 整包估算（{@code <=0} = 调用方没有这份估算 ⇒ 退回旧口径）
	 */
	public Decision decide(int limitTokens, int usedPrompt, int lastCompletion, int newInputTokens,
			int toolResultsSinceMeasure, int wholeRequestEstimate, boolean thresholdsArmed) {
		return decide(limitTokens, usedPrompt, lastCompletion, newInputTokens, toolResultsSinceMeasure,
				wholeRequestEstimate, thresholdsArmed, 0);
	}

	/**
	 * 触发判定（带<b>真实输出预留</b>；B1）。
	 *
	 * <p>分母与滞回、轮内预算统一用 {@link #effectiveReserve(int, int, int)}：本轮请求声明的
	 * {@code max_tokens} 大于配置预留时，触发线必须按真实输出空间收口，否则"这一发连输出都放不下"
	 * 会被判成未达线。
	 *
	 * @param maxOutputTokens 本轮请求的生效 {@code max_tokens}（{@code <=0} = 未声明）
	 */
	public Decision decide(int limitTokens, int usedPrompt, int lastCompletion, int newInputTokens,
			int toolResultsSinceMeasure, int wholeRequestEstimate, boolean thresholdsArmed, int maxOutputTokens) {
		long nextEst;
		if (usedPrompt > 0) {
			nextEst = Math.max(0, usedPrompt) + Math.max(0, lastCompletion) + Math.max(0, newInputTokens)
					+ Math.max(0, toolResultsSinceMeasure);
		}
		else if (wholeRequestEstimate > 0) {
			// 无真值：整包估算；completion 单独取 max 兜住"上一跳输出还没进历史"的形态（不重复计）
			nextEst = Math.max(wholeRequestEstimate,
					Math.max(0, lastCompletion) + Math.max(0, newInputTokens) + Math.max(0, toolResultsSinceMeasure));
		}
		else {
			nextEst = Math.max(0, lastCompletion) + Math.max(0, newInputTokens) + Math.max(0, toolResultsSinceMeasure);
		}
		int reserve = effectiveReserve(limitTokens, this.config.getReserveTokens(), maxOutputTokens);
		if (limitTokens <= 0) {
			return new Decision(Level.NONE, 0d, (int) nextEst, 0, reserve);
		}
		long denominator = Math.max(1L, (long) limitTokens - reserve);
		double ratio = (double) nextEst / denominator;
		if (!thresholdsArmed) {
			return new Decision(Level.NONE, ratio, (int) nextEst, limitTokens, reserve);
		}
		Level level = Level.NONE;
		if (ratio * 100d >= this.config.getL3Percent()) {
			level = Level.L3;
		}
		else if (ratio * 100d >= this.config.getL2Percent()) {
			level = Level.L2;
		}
		else if (ratio * 100d >= this.config.getL1Percent()) {
			level = Level.L1;
		}
		return new Decision(level, ratio, (int) nextEst, limitTokens, reserve);
	}

	/**
	 * 滞回解锁：压缩完成后<b>可压缩历史</b>占触发同一分母的比例低于滞回线才允许再次触发。
	 *
	 * <p>
	 * 判据用<b>真值</b>（不是 nextEst）：估算抖动不该产生"压一次不够、立刻再压"的抖动链。
	 * 从未压缩过 ⇒ 直接解锁。上限未知 ⇒ 解锁（降级态由 L1/L3 的绝对阈值兜底）。
	 *
	 * <p><b>C13 两处口径修正</b>（否则大固定前缀会把 L2/L3 永久锁死）：
	 * <ol>
	 * <li><b>扣掉不可压缩的前缀</b>：{@code usedNow} 自 C13 起含 system + tools（与上游
	 * {@code promptTokens} 同口径）；滞回要问的是"<b>历史</b>还大不大"。一份 8000 token 的人设+
	 * 工具集在 15000 窗口上恒 ≥ 60%，若不扣，L2/L3（只有轮首能做）永远不触发，会话只能靠轮内
	 * L1/收窄苦撑；</li>
	 * <li><b>分母与触发一致</b>：用 {@code limit − effectiveReserve}（同 {@link #decide}），
	 * 否则"触发看一个分母、解锁看另一个"会在小窗口上互相打架。</li>
	 * </ol>
	 *
	 * @param usedNow            压缩后的基线（含 system + tools）
	 * @param fixedPrefixTokens  本轮 system + tools 的估算（不可压缩，按 {@code > 0} 才扣）
	 * @param limitTokens        实际窗口（{@code <=0} = 未知 ⇒ 解锁）
	 * @param everCompacted      本会话是否压缩过
	 */
	public boolean isHysteresisUnlocked(int usedNow, int fixedPrefixTokens, int limitTokens, boolean everCompacted) {
		return isHysteresisUnlocked(usedNow, fixedPrefixTokens, limitTokens, everCompacted, 0);
	}

	/**
	 * 滞回解锁（带真实输出预留；B1）。
	 *
	 * <p>分母必须与 {@link #decide} 完全一致（{@code limit − effectiveReserve(limit, reserve, maxOutput)}）：
	 * 触发看一个分母、解锁看另一个分母，会在小窗口/大 max_tokens 的模型上互相打架。
	 */
	public boolean isHysteresisUnlocked(int usedNow, int fixedPrefixTokens, int limitTokens, boolean everCompacted,
			int maxOutputTokens) {
		if (!everCompacted) {
			return true;
		}
		if (limitTokens <= 0) {
			return true;
		}
		int compressible = Math.max(0, Math.max(0, usedNow) - Math.max(0, fixedPrefixTokens));
		long denominator = Math.max(1L,
				(long) limitTokens - effectiveReserve(limitTokens, this.config.getReserveTokens(), maxOutputTokens));
		return (double) compressible * 100d / denominator < this.config.getHysteresisPercent();
	}

}
