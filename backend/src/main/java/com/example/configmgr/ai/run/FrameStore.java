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
 * <b>2026-10-30（Task 2）起：号与归档必须同一拍</b>——两步式"先取号、落档被越过就改高号"的窗口被取消：
 * 生产实现 {@link RedisFrameStore#appendAndAllocateSeq} 用<b>一次 Redis 脚本</b>完成
 * 「最终号判定 → 追加 {@code <seq>-0} → TTL → 幂等标记」，于是
 * <b>分配序 == 归档序 == 交付序</b>，且 {@code AppendResult.seq} 恒等于 Stream entry ID 的毫秒段。
 * <b>不允许</b>再出现"未来落档一个更低号的新帧"：低号帧若已被表尾越过（{@link #append} 的显式号路径），
 * 它就不落档（只 WARN），而不是改号挤到表尾。
 *
 * <p><b>跳号是正常的</b>：{@code seq} 保证每轮唯一、单调、严格递增，但<b>不保证 +1 连续</b>
 * （降级重试、遗留墙钟 ID、失败后重试都会跳）。单实例无竞争时看起来是连续的，
 * <b>那只是单实例形态，不是跨实例保证</b>（conformance 里的连续性断言只覆盖兼容/单实例路径）。
 *
 * <p>出帧路径<b>必须</b>用 {@link #appendAndAllocateSeq} 的返回值（归档号 + 归档字节）做实时投递与唤醒：
 * 归档没成功（{@code seq <= 0}）就<b>不交付</b>、不推进游标、不发唤醒 —— 否则订阅者会看到一个
 * 永不进入归档的号，重挂/唤醒按号去重时会永久漏掉后续帧。
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
	 * 取下一个业务序号（<b>只取号、不归档</b>）。
	 *
	 * <p>生产实现 = {@code INCR ai:seq:<runId>}（Redis 原子，跨实例不重不回退）；失败时降级为
	 * 进程内单调计数（仅 WARN，不中断出帧 —— 与既有 S2-4"韧性优先"同口径）。
	 *
	 * <p><b>本方法的结果不能当交付号</b>（2026-10-30 Task 2）：它只回答"下一个可用号是几"，
	 * 并不占住归档位次 —— 同一瞬间另一个实例可能已经用更高的号归档，本号便再也落不了档。
	 * 出帧路径一律走 {@link #appendAndAllocateSeq}（号与归档同一拍，成功才交付）；
	 * 本方法只服务兼容写口与诊断（{@link RunStoreFrameStore} / 单测 / 非出帧调用方）。
	 */
	long nextSeq(String runId);

	/**
	 * 落档一帧（帧体已带 {@code seq}）。心跳帧 no-op。写失败只记日志，不抛出 ——
	 * 外置是"更耐久"，不是"更正确"的前提（与 B1 起 {@code RunStore#appendEvent} 同口径）。
	 *
	 * <p><b>不会改号</b>（2026-10-30 Task 2）：帧体里的 {@code seq} 就是它被归档的号。若该号已被
	 * 表尾越过（另一个实例已经用更高的号归档），本帧<b>不落档</b>（WARN + {@code skipped}）——
	 * 改号会让"交付号 ≠ 归档号"，而挤到表尾会让归档序与分配序分叉。出帧路径必须改用
	 * {@link #appendAndAllocateSeq}（它先定号再归档，因此永不被越过）。
	 */
	void append(String runId, Map<String, Object> frame);

	/**
	 * 落档一帧并返回<b>实际落档的序号</b>（默认实现 = {@link #append} + 帧体里的 {@code seq}）。
	 *
	 * <p>为什么必须有这个口：返回的 {@code payload} 是<b>落档的那一份帧体原文</b>，实时投递据此与归档
	 * 逐字节一致（含截断结果）；实现不回传时调用方沿用自己那份。
	 *
	 * <p><b>不会改号</b>（2026-10-30 Task 2）：号就是帧体里的 {@code seq}；被表尾越过 ⇒ 不落档
	 * （{@code seq <= 0}），绝不"改高号挤到表尾"。出帧路径用
	 * {@link #appendAndAllocateSeq}（定号 + 归档同一拍）。
	 *
	 * @return {@code seq <= 0} = 未落档（心跳 / 无号 / 写失败 / 号已被越过）；{@code payload == null} = 未回传字节
	 */
	default AppendResult appendAndGetSeq(String runId, Map<String, Object> frame) {
		append(runId, frame);
		Object seq = frame.get("seq");
		return new AppendResult(seq instanceof Number number ? number.longValue() : 0L, null);
	}

	/**
	 * 落档并返回实际落档号 + 归档字节，<b>负载在最终落档号确定之后才生成</b>。
	 *
	 * <p>为什么必须先定号再产负载：单帧上限的判定口径是<b>整帧 JSON 含 {@code seq}</b> 的字节数，
	 * 而归档的 entry ID 必须等于号 —— 先把号定下来，截断/序列化才与"最终号"同源（贴上限的帧不会
	 * 因为号变宽越界）。工厂每次尝试都在<b>那一次的最终号</b>下产出负载，成功落档的那份即归档字节，
	 * 调用方原样投递 —— 同号、同字节。
	 *
	 * <p>默认实现（兼容实现用）：先跑工厂（它就地把截断结果写回帧体）再落档，归档里的帧体与实时负载
	 * 因此是同一份截断结果。工厂返回 {@code null}（无法序列化）时不落档，返回 {@code skipped()}。
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
			// 实现自己回传了落档字节：以它为准（归档字节 == 交付字节）。
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
	 * <b>原子落档</b>（2026-10-30 Task 2 的核心）：<b>最终序号分配与归档在同一次 Redis 脚本里完成</b>，
	 * 于是"分配序 == 归档序 == 交付序"，不存在"先取低号、被越过后改高号"的窗口。
	 *
	 * <h2>为什么必须原子（旧口径的两个缺陷）</h2>
	 * <ol>
	 * <li><b>交付号 ≠ 归档号</b>：两步式下 {@code INCR} 交出的号可能已被另一个实例的更高号越过，
	 * 落档只能改号 —— 实时流用旧号、回放用新号，前端 {@code lastSeq} 与按号去重同时错位；</li>
	 * <li><b>低号后到 = 永久缺帧</b>：读者游标越过某号之后，那一号若还能"后补"落档，所有已越过它的
	 * 订阅者都收不到（唤醒补帧按游标取差量）；本口让"落档"严格按号递增发生，低号帧根本不会迟到。</li>
	 * </ol>
	 *
	 * <p><b>号源只能是 Redis</b>：脚本内 {@code INCR}/{@code INCRBY} 是唯一发号口，
	 * 不用 {@code IdGenerator}、不用数据库、不用实例本地计数（跨实例顺序不能由单进程自证）。
	 *
	 * <p><b>失败语义</b>：{@code seq <= 0} = 本次<b>没有归档</b>（Redis 不可用 / 末号不可读且无法证明唯一 /
	 * 帧体无法安全嵌入号）。调用方<b>不得交付、不得推进游标、不得发唤醒</b>：
	 * 没有归档号就没有可交付的顺序事实，退回"先发出去再说"会造出永不入档的号。
	 * 降级是<b>可观测</b>的（WARN + {@link com.example.configmgr.ai.observe.RedisDegradation}），
	 * 且<b>不得</b>在降级期间宣称多实例顺序成立。
	 *
	 * <p><b>负载与最终号同源</b>：实现在"号占位"下调用工厂（截断/序列化按最宽号算 ⇒ 最终字节只会更短），
	 * 再把号替换进负载后归档；返回的 {@code payload} 即归档字节，实时投递原样使用。
	 *
	 * <p>默认实现（兼容/单实例路径）：{@link #nextSeq} 取号 → 写入帧体 → {@link #appendAndGetSeq}。
	 * 单实例无竞争时号即归档号；<b>不</b>具备跨实例保证（生产装配恒为 {@link RedisFrameStore}）。
	 */
	default AppendResult appendAndAllocateSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
		long seq = nextSeq(runId);
		if (seq <= 0L) {
			return AppendResult.skipped();
		}
		frame.put("seq", seq);
		AppendResult archived = appendAndGetSeq(runId, frame, payloadFactory);
		if (archived.seq() > 0L) {
			return archived;
		}
		return AppendResult.skipped();
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

	/**
	 * 归档原文的一条（{@code json} = 落档时写进 Stream 的那段 {@code frame} 字段）。
	 *
	 * <p>为什么要"原文"口：交付前的<b>补齐</b>与<b>唤醒补帧</b>一次可能投几十帧，
	 * 若都走 {@code readFrom}（反序列化成 {@code Map} 再序列化）就多两遍 CPU，而且
	 * "实时字节 == 归档字节"这句话在兼容实现上并不成立。本口把语义说清楚：
	 * <b>生产实现（{@link RedisFrameStore}）返回归档原文（逐字节一致）</b>；
	 * 兼容实现可以不复写本口（默认空列表 ⇒ 调用方回落到 {@code readFrom} + 序列化，
	 * 此时只是<b>语义等价</b>，不承诺逐字节一致）。
	 */
	record RawFrame(long seq, String json) {
	}

	/**
	 * 按序号读取<b>归档原文</b>（{@code seq > afterSeq}，升序，最多 {@code limit} 条）。
	 *
	 * <p>默认实现返回空列表 = <b>不支持原文读取</b>；调用方必须回落到 {@link #readFrom}
	 * （兼容实现/替身），并按"语义等价"处理（不承诺与归档逐字节相同）。
	 * 读失败同样返回空列表（调用方按"补不到"处理，不阻塞投递链）。
	 */
	default List<RawFrame> readRawFrom(String runId, long afterSeq, int limit) {
		return List.of();
	}

	/**
	 * 一次回读的结果：帧、是否降级（读失败）、是否<b>明确截断</b>。
	 *
	 * <p><b>{@code truncated}</b>（2026-10-30 Task 2 返修，经 reattach 响应头
	 * {@code archive-trimmed} / {@code archive-from} / {@code archive-to} 暴露给前端）：
	 * 本次回放<b>确实跳过了不可回放的区间</b> —— 判据是<b>真实裁剪低水位</b>
	 * （{@code ai:xtrim}，由原子落档脚本在观察到"幸存首条被推高"时记录），
	 * 即 {@code afterSeq + 1 < 低水位}；<b>不</b>看"号是否连续"（生产上跳号是正常形态：
	 * 降级重试、遗留墙钟 ID、失败重试），也<b>不</b>用"首条 &gt; afterSeq+1"猜。
	 * 老流没有低水位 ⇒ 不下结论（{@code truncated=false}）。
	 * 与 {@code degraded} 的分工：{@code degraded} = "这次读失败，可信度未知"（应重试）；
	 * {@code truncated} = "读成功了，但归档窗口已经翻过去了"（提示不可完整恢复，不必重试）。
	 *
	 * <p>{@code trimmedFromSeq}/{@code trimmedToSeq} = 缺口区间的<b>含端点</b>边界
	 * （全量回放：{@code min(游标+1, 窗口外首条)} 到 {@code max(低水位-1, 窗口头之前那条)}；
	 * 差量读：{@code afterSeq+1} 到 {@code 低水位-1}；未截断时为 {@code 0/0}），
	 * 对外即 reattach 响应头 {@code archive-from}/{@code archive-to}。
	 *
	 * <p><b>它们是缺口区间的保守边界，不是"逐号清单"</b>：生产允许跳号（降级重试、遗留墙钟 ID、
	 * 失败后重试都会跳），因此区间内的号<b>不保证每个都真实存在过</b> —— 边界只能保证
	 * "此区间之外的帧不受本次裁剪影响"，消费方据此提示"不可完整恢复 [from, to]"即可，
	 * 不得把区间长度当成"丢了多少帧"，也不得逐个号去对账。
	 */
	record ReadResult(List<Map<String, Object>> frames, boolean degraded, boolean truncated, long trimmedFromSeq,
			long trimmedToSeq) {

		/** 兼容构造（未截断）：兼容实现/测试替身不需要区分这一位。 */
		public ReadResult(List<Map<String, Object>> frames, boolean degraded) {
			this(frames, degraded, false, 0L, 0L);
		}

		/** 兼容构造（只有截断位、无缺口区间）。 */
		public ReadResult(List<Map<String, Object>> frames, boolean degraded, boolean truncated) {
			this(frames, degraded, truncated, 0L, 0L);
		}
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
