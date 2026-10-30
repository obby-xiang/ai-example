package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 轮次事件日志的抽象（B23L1 批① / 设计稿 §2.1）。
 *
 * <p>
 * 三个职责、五个方法：<b>取号</b>（{@link #nextSeq}）、<b>落档</b>（{@link #append} /
 * {@link #appendAndGetSeq}）、<b>按号回读</b>（{@link #readFrom}）与<b>末号</b>（{@link #lastSeq}）。
 * 生产装配只有一个实现
 * ——{@link RedisFrameStore}（Redis Stream + {@code INCR ai:seq:<runId>}），
 * <b>单实例与多实例完全同一实现</b>（不引内存环，消灭"单机走内存、集群走 Redis"的分支）。
 *
 * <h2>为什么取号与落档分开</h2>
 * 帧的单帧字节上限（T3-10 / A19）判定口径是<b>整帧 JSON 含 {@code seq}</b> 的 UTF-8 字节数，
 * 而截断必须发生在<b>落档之前</b>（否则事件日志里躺着未截断帧，回放与实时流分叉）。
 * 于是出帧路径固定为「取号 → 注入 {@code seq} → 截断/序列化 → 落档」，
 * 取号与落档因此必须是两个可分别调用的步骤（{@code append(runId, frame) → seq} 的单方法形态
 * 会把落档提前到截断之前）。
 *
 * <p>
 * <b>但落档可能改号</b>：事件日志的 entry ID 是显式 {@code <seq>-0}，Redis 要求新 ID 严格大于表尾；
 * 跨实例并发时表尾可能已被更高的号越过，生产实现会重分配号后落档。因此出帧路径必须用
 * {@link #appendAndGetSeq} 的返回值（最终落档号 + 归档字节）做实时投递与唤醒，
 * 否则同一帧在实时流与回放里是两个号（{@link SseChatEmitter} 已按此口径接线）。
 *
 * <h2>心跳不入日志</h2>
 * {@code heartbeat} 帧不取业务号、不落档（T7 口径原样保留）：{@link #append} 收到心跳帧是 no-op，
 * 调用方（{@link SseChatEmitter}）也不为它取号。
 *
 * <h2>序号语义</h2>
 * 业务帧 {@code seq} 自 1 起、单调、跨实例不重（Redis {@code INCR} 保证）；
 * 心跳有独立号空间（{@code heartbeatSeq}），与事件日志无关。
 */
public interface FrameStore {

	/** 唤醒批量上限（每次 wake 最多补多少帧；正常远小于事件日志窗口）。 */
	int WAKE_BATCH_LIMIT = 6000;

	/**
	 * 取下一个业务序号（并把它交给调用方注入帧体）。
	 *
	 * <p>生产实现 = {@code INCR ai:seq:<runId>}（Redis 原子，跨实例不重不回退）；失败时降级为
	 * 进程内单调计数（仅 WARN，不中断出帧 —— 与既有 S2-4"韧性优先"同口径；降级期间与已发放号
	 * 空间可能重叠，登记为 Redis 整体不可用下的观察项，不是续号锚点）。
	 */
	long nextSeq(String runId);

	/**
	 * 落档一帧（帧体已带 {@code seq}）。心跳帧 no-op。写失败只记日志，不抛出 ——
	 * 外置是"更耐久"，不是"更正确"的前提（与 B1 起 {@code RunStore#appendEvent} 同口径）。
	 *
	 * <p><b>落档号可能与帧体里的号不同</b>（见 {@link #appendAndGetSeq}）：出帧路径必须用后者，
	 * 本方法是"不在意最终号"的调用方（兼容写口/单测）的入口。
	 */
	void append(String runId, Map<String, Object> frame);

	/**
	 * 落档一帧并返回<b>实际落档的序号</b>（默认实现 = {@link #append} + 帧体里的 {@code seq}）。
	 *
	 * <p>为什么必须有这个口：事件日志的 entry ID 是显式 {@code <seq>-0}，而 Redis 要求新 ID
	 * <b>严格大于表尾</b> —— 跨实例并发写同一轮（confirm / frontend-tool-result 与驱动同时出帧）时，
	 * 本帧的号可能已被别人落档的更高号越过。生产实现此时<b>重分配号</b>后落档
	 * （{@link RedisFrameStore#appendAndGetSeq}），于是"帧体里的 seq"不再是最终落档号。
	 *
	 * <p><b>出帧路径必须用返回的号</b>：否则同一帧在实时流里一个号、在回放/唤醒里另一个号，
	 * 前端 {@code lastSeq} 与按号去重都会错位（同一帧被当两帧渲染，或被同号合并丢掉一帧）。
	 * 返回的 {@code payload} 是<b>落档的那一份帧体原文</b>，实时投递据此与归档逐字节一致
	 * （含截断结果）；实现不回传时调用方沿用自己那份。
	 *
	 * @return {@code seq <= 0} = 未落档（心跳 / 无号 / 写失败）；{@code payload == null} = 未回传字节
	 */
	default AppendResult appendAndGetSeq(String runId, Map<String, Object> frame) {
		append(runId, frame);
		Object seq = frame.get("seq");
		return new AppendResult(seq instanceof Number number ? number.longValue() : 0L, null);
	}

	/**
	 * 落档并返回实际落档号 + 归档字节，<b>负载在最终落档号确定之后才生成</b>。
	 *
	 * <p>为什么必须先定号再产负载：{@code <seq>-0} 被表尾越过时落档会<b>重分配号</b>。
	 * 若截断/序列化先按取号号做完再改号，帧就会多出"号宽"字节 —— 贴着单帧上限的帧必然越界；
	 * 反过来（先产负载再改号）也无法保证归档字节与实时字节同源。本口把工厂交给落档实现：
	 * 每一次尝试（含重分配后的新号）都用工厂在<b>那一次的最终号</b>下产出负载，
	 * 成功落档的那一份即归档字节，调用方原样投递 —— 同号、同字节、绝不越界。
	 *
	 * <p>默认实现（兼容实现用）：号不会被重分配，因此<b>先跑工厂</b>（它就地把截断结果写回帧体）
	 * 再落档 —— 归档里的帧体与实时负载因此是同一份截断结果（与"取号 → 截断 → 落档"的旧口径一致）。
	 * 工厂返回 {@code null}（无法序列化）时不落档，返回 {@code skipped()}。
	 * 生产实现（{@link RedisFrameStore}）在最终号下产负载并直接写该字节，故返回的字节即归档字节。
	 */
	default AppendResult appendAndGetSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
		if (payloadFactory == null) {
			return appendAndGetSeq(runId, frame);
		}
		byte[] payload = payloadFactory.payload(frame);
		if (payload == null) {
			return AppendResult.skipped();
		}
		AppendResult archived = appendAndGetSeq(runId, frame);
		if (archived.payload() != null) {
			// 实现自己回传了落档字节：以它为准（可能已重分配号）。
			return archived;
		}
		return new AppendResult(archived.seq(), payload);
	}

	/**
	 * 落档负载工厂：为一帧产出<b>最终负载字节</b>（{@link #appendAndGetSeq(String, Map, PayloadFactory)}
	 * 的回调，在最终落档号确定之后调用）。
	 */
	@FunctionalInterface
	interface PayloadFactory {

		/**
		 * @param frame 已把<b>最终号</b>写入 {@code seq} 的帧（实现可就地截断它，如
		 *              {@link FrameSizeLimiter#apply(Map, com.fasterxml.jackson.databind.ObjectMapper, int)}）
		 * @return 负载字节；{@code null} = 无法生成（该帧不落档）
		 */
		byte[] payload(Map<String, Object> frame);
	}

	/** 一次落档的结果：实际落档号 + 落档的帧体原文（实时投递据此与归档同一份字节）。 */
	record AppendResult(long seq, byte[] payload) {

		/** 未落档（心跳 / 无号 / 写失败）。 */
		public static AppendResult skipped() {
			return new AppendResult(0L, null);
		}
	}

	/**
	 * 按序号回读：返回 {@code seq > afterSeq} 的帧（升序）。
	 *
	 * <p>兼容期双读：先读 LIST 尾巴（{@code ai:events:<runId>}，只读），再读 Stream，
	 * 按 {@code seq} 拼接去重（同号以 Stream 为准）；<b>没有 {@code seq} 的老帧一律保留</b>
	 * （无法判定孤儿，宁可不发不可丢帧，与 {@code isOrphan} 同口径）。
	 *
	 * <p><b>空结果 ≠ 证明"没有帧"</b>：读失败（Redis 抖动、目标 5.0 拒绝非法 stream ID 等）
	 * <b>不会以异常表达，但结果可能为空、也可能缺失失败侧的那部分帧</b>（双侧读是独立进行的：
	 * Stream 读失败时已读到的兼容期 LIST 帧仍会照常返回，反之亦然）。因此调用方既不得把"空"
	 * 也不得把"帧少了几条"当成"该 run 确实没有新帧"的证明。实现侧以 WARN 留痕：带
	 * {@code runId}，其中 Stream 的 {@code XRANGE} 读取失败另带 {@code afterSeq} 与 {@code from}
	 * （见 {@link RedisFrameStore#readFrom}）；读失败同时计入
	 * {@link com.example.configmgr.ai.observe.RedisDegradation} 的 A3/A4 计数
	 * （{@code /api/ai/health} 的 {@code redis.degradations}），"空流"不计数 —— 两者因此可区分。
	 *
	 * <p><b>不抛异常只承诺生产实现</b>：{@link RedisFrameStore} 吞掉读失败不抛（回放与唤醒
	 * 补帧不能被一次读抖动打断）；接口另有兼容实现 {@code RunStoreFrameStore}（生产装配不可达，
	 * 仅兼容构造器使用），其读路径未做同款吞异常处理，<b>不在本承诺内</b>。
	 *
	 * @param afterSeq 前端已知的最大序号（0 = 全量）
	 * @param limit    最多返回多少帧；{@code <= 0} = 不限（回放批用）
	 */
	List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit);

	/**
	 * 带降级标记的回读（I-1）：{@code degraded=true} = 本次读有失败/有窗口被跳过，
	 * 结果<b>不是</b>"该 run 确实没有新帧"的证明。
	 *
	 * <p>默认实现把 {@link #readFrom} 视为未降级（兼容测试替身与兼容期 LIST 实现）；
	 * 生产实现 {@link RedisFrameStore} 覆盖它，把 A3/A4 的失败信号真正传到调用方。
	 */
	default ReadResult readFromWithStatus(String runId, long afterSeq, int limit) {
		return new ReadResult(readFrom(runId, afterSeq, limit), false);
	}

	/** 事件日志里<b>已落档</b>的最大序号；读失败返回 {@link OptionalLong#empty()}，不得折成 0。 */
	default OptionalLong lastSeqIfKnown(String runId) {
		return OptionalLong.of(lastSeq(runId));
	}

	/** 一次回读的结果 + 是否降级（I-1：API 层可区分"空流"与"读失败"）。 */
	record ReadResult(List<Map<String, Object>> frames, boolean degraded) {
	}

	/** 事件日志里<b>已落档</b>的最大序号（无帧 = 0；读失败的生产实现返回 -1，见 {@link #lastSeqIfKnown}）。 */
	long lastSeq(String runId);

	/**
	 * <b>终态释放</b>本轮的进程内簿记（帧序号/资源修复批；默认 no-op）。
	 *
	 * <p>只清"本进程为这一轮记的东西"（降级号、观察到的最大载荷、每轮一次的告警位），
	 * <b>不动事件日志与序号键</b>：它们按 session TTL 自然到期，回收之后的重挂/续跑仍按归档回放。
	 * 由终态出口（{@code RunRegistry#close}）调用 —— 谁结束谁释放，长跑实例上才不会被
	 * "每轮一条、只增不减"的进程内 Map 拖住。
	 */
	default void forget(String runId) {
	}

}
