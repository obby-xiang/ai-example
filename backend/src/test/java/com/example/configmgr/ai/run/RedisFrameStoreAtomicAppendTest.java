package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2（2026-10-30 关键稳定性修复）· 帧序号的<b>原子分配 + 归档</b>。
 *
 * <h2>被取消的口径</h2>
 * 旧实现是两步：{@code INCR} 先拿号（可能拿到 5），落档时显式 ID {@code 5-0} 已被别的实例的
 * {@code 6-0} 越过，于是<b>改号</b>成 7 重写 —— "先取低号、落档改高号"。这条窗口有两个后果：
 * ① 拿到的低号 5 永远不落档（跳号，本身允许）；② 更危险的是<b>落档号 ≠ 交付号</b>的中间态，
 * 以及"低号后到"的帧只能靠重分配挤到表尾，读者游标一旦越过原号，那一帧就再无归档可能。
 *
 * <p>本批把"最终号判定 + Stream 归档 + TTL + 幂等标记"收进<b>一次 Redis 脚本执行</b>：
 * Redis 串行执行脚本 ⇒ 分配序就是归档序，{@code AppendResult.seq} 就是 Stream entry ID 的毫秒段，
 * 帧体里的 {@code seq} 也是同一个数。跳号仍可能出现（降级/遗留墙钟 ID/失败重试），
 * 但<b>绝不会</b>出现"未来落档一个更低号"的新帧。
 *
 * <p>用例口径：两个 {@link RedisFrameStore} 实例共用一个 Redis 替身（= 两个应用实例共用 Redis），
 * 同时为同一轮出帧；判据是"归档序 == 分配序 == 交付序"与"回传字节 == 归档字节"。
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisFrameStoreAtomicAppendTest {
	private static final String RUN_ID = "run-atomic";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	/**
	 * <b>两个写出器竞争</b>：两个实例各出 12 帧，交错执行。
	 *
	 * <p>判据（缺一条即红）：
	 * <ol>
	 * <li>归档里没有重号、没有丢帧：号空间恰好 1..24（分配与归档同一拍 ⇒ 不会出现"取了号却落不了档"的洞）；</li>
	 * <li>物理顺序 == 序号顺序（{@code ID 毫秒段 == seq}），且严格递增；</li>
	 * <li>每次调用回传的 {@code (seq, payload)} 与归档里 {@code <seq>-0} 那条记录的 {@code frame} 字段
	 *     <b>逐字节相同</b>（交付序号 == 归档序号，交付字节 == 归档字节）；</li>
	 * <li>读者按号回读拿到的是同一条序列（未重、未整窗）。</li>
	 * </ol>
	 *
	 * <p>kill：退回"先 INCR 取号、再 XADD 落档（被越过就改号）"⇒ 号空间出现洞、或回传号与归档 ID 分叉；
	 * 回传的 payload 不是脚本里那份字节 ⇒ 第 3 条红。
	 */
	@Test
	void competingWritersArchiveInAllocationOrderWithIdsEqualToSeqs() throws Exception {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore left = store(redis);
		RedisFrameStore right = store(redis);

		int perWriter = 12;
		Map<Long, byte[]> handed = new ConcurrentHashMap<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			for (RedisFrameStore writer : List.of(left, right)) {
				pool.execute(() -> {
					try {
						start.await(5, TimeUnit.SECONDS);
						for (int i = 0; i < perWriter; i++) {
							FrameStore.AppendResult result = writer.appendAndAllocateSeq(RUN_ID,
									frame("delta", "w" + writer.streamKey(RUN_ID).hashCode() + "-" + i),
									payloadFactory());
							if (result.seq() <= 0L || result.payload() == null) {
								failures.add(new IllegalStateException("原子落档返回未归档：" + result.seq()));
								return;
							}
							byte[] previous = handed.put(result.seq(), result.payload());
							if (previous != null && !java.util.Arrays.equals(previous, result.payload())) {
								failures.add(new IllegalStateException("同一 seq 回传了两份不同字节：" + result.seq()));
							}
						}
					}
					catch (Throwable ex) {
						failures.add(ex);
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures).isEmpty();
		assertThat(handed.keySet()).as("每个回传号都不同（两实例共用一个号源）").hasSize(2 * perWriter);
		assertThat(handed.keySet()).as("分配与归档同一拍 ⇒ 号空间恰好 1..%d，无'取了号却落不了档'的洞",
				2 * perWriter).containsExactlyInAnyOrderElementsOf(
						java.util.stream.LongStream.rangeClosed(1, 2 * perWriter).boxed().toList());

		List<String> ids = redis.streamIds(left.streamKey(RUN_ID));
		assertThat(ids).as("归档条数 == 回传条数（一帧一条，不重不丢）").hasSize(2 * perWriter);
		for (int i = 0; i < ids.size(); i++) {
			Map<String, byte[]> fields = FakeFrameRedis.asMap(redis.stream(left.streamKey(RUN_ID)).get(i));
			long seq = Long.parseLong(new String(fields.get(RedisFrameStore.FIELD_SEQ), StandardCharsets.UTF_8));
			assertThat(ids.get(i)).as("物理序 == 序号序：第 %d 条记录的 ID 毫秒段必须等于它的 seq", i)
				.isEqualTo(seq + "-0");
			byte[] body = fields.get(RedisFrameStore.FIELD_FRAME);
			assertThat(new String(body, StandardCharsets.UTF_8)).as("帧体里的 seq 与记录 ID 同号")
				.contains("\"seq\":" + seq);
			assertThat(body).as("回传字节 == 归档字节（seq=%d）", seq).isEqualTo(handed.get(seq));
		}
		assertThat(seqFields(redis, left.streamKey(RUN_ID))).as("归档序号严格递增").isSorted()
			.doesNotHaveDuplicates();

		assertThat(seqs(left.readFrom(RUN_ID, 0L, 0))).as("读者按号回读：同一条序列，未重未整窗")
			.hasSize(2 * perWriter)
			.doesNotHaveDuplicates()
			.isSorted();
		assertThat(left.lastSeq(RUN_ID)).isEqualTo(2 * perWriter);
	}

	/**
	 * <b>低号尚未归档 + 高号先归档</b>（"先取低号、被越过后改高号"的根竞态）。
	 *
	 * <p>形态：一个写出器先用 {@code nextSeq} 拿到 1 号（<b>还没归档</b>），另一个实例随后归档了更高的 2 号。
	 * 旧实现这时会把 1 号<b>改</b>成 3 号再落档 —— 帧没丢，但：
	 * <ol>
	 * <li>交付号（1）与归档号（3）分叉（如果它已经把 1 号发给了订阅者）；</li>
	 * <li>更根本的是"归档序必须严格递增"失效：任何<b>低号</b>帧都能在<b>后被</b>塞进归档，
	 *     而订阅者游标一旦越过该号（唤醒补帧按游标取差量），它就被永久跳过。</li>
	 * </ol>
	 *
	 * <p>本批口径：号被表尾越过 ⇒ 该帧<b>不落档</b>（WARN 可观测），归档里<b>不会</b>出现更低号的新帧。
	 * 丢的只是"这一帧跨实例不可见"，而不是整个读者序列的一致性。
	 *
	 * <p>kill：退回"重分配号后重试"⇒ 归档里出现 3-0（且回传号非 0），两条断言同时红。
	 */
	@Test
	void staleLowSeqIsNeverArchivedAfterAHigherSeqWasArchived(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore lagging = store(redis);
		RedisFrameStore leading = store(redis);

		long tentative = lagging.nextSeq(RUN_ID);
		assertThat(tentative).as("只取号、尚未归档（低号）").isEqualTo(1L);
		FrameStore.AppendResult leadingResult = leading.appendAndAllocateSeq(RUN_ID,
				frame("tool_result", "高号先归档"), payloadFactory());
		assertThat(leadingResult.seq()).as("另一实例的帧先归档（原子落档给出更高号）").isEqualTo(2L);

		Map<String, Object> late = frame("delta", "低号后到");
		late.put("seq", tentative);
		FrameStore.AppendResult lateResult = lagging.appendAndGetSeq(RUN_ID, late, payloadFactory());

		assertThat(lateResult.seq()).as("号被表尾越过 ⇒ 不落档（不改号、不自动 ID）").isZero();
		assertThat(lateResult.payload()).isNull();
		assertThat(redis.streamIds(lagging.streamKey(RUN_ID)))
			.as("归档里只有先落档的高号：不允许未来出现更低号的新归档帧").containsExactly("2-0");
		assertThat(seqs(lagging.readFrom(RUN_ID, 0L, 0))).as("读者序列单调：只有 2").containsExactly(2L);
		assertThat(lagging.lastSeq(RUN_ID)).as("末号不回退").isEqualTo(2L);
		assertThat(output.getAll()).as("丢帧必须可观测（不是静默）").contains("事件日志显式号落档被表尾越过");
	}

	/**
	 * <b>XADD 成功但回包丢失</b>（Task 2 第 3 条）：脚本已经写入，客户端只看到异常。
	 *
	 * <p>重试<b>必须</b>按<b>精确 ID {@code <seq>-0} + 本次帧体</b>认出"这一帧已经归档"：
	 * ① 不得写出第二条（否则同一逻辑帧两个号，前端按号去重也救不回）；
	 * ② 不得改号（交付号必须等于归档号）；③ 不得靠"查表尾"—— 表尾这时可能已经是别人的帧。
	 *
	 * <p>判据的第二半：幂等是<b>按这次出帧</b>（token）而不是按内容 —— 内容相同的两帧是两条帧，
	 * 必须拿到两个不同的号（不能因为"内容一样"就吞掉一条）。
	 *
	 * <p>kill：去掉脚本里的 token 幂等判定（只按表尾/不做判定）⇒ 重试拍再 {@code INCR} 一次，
	 * 归档出现两条 {@code 1-0/2-0} 而回传号是 2，"只写了一条"与"回传号 == 1"同时红。
	 */
	@Test
	void unknownReplyAfterTheAppendIsResolvedByIdempotentRetryOnTheSameExactId() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.failAppendReplyTimes = 1;

		FrameStore.AppendResult result = store.appendAndAllocateSeq(RUN_ID, frame("delta", "回包丢了"), payloadFactory());

		assertThat(result.seq()).as("重试拿到的是已经落档的那个号（不是新号）").isEqualTo(1L);
		assertThat(redis.streamIds(store.streamKey(RUN_ID))).as("同一次出帧只归档一条（幂等）").containsExactly("1-0");
		Map<String, byte[]> fields = FakeFrameRedis.asMap(redis.stream(store.streamKey(RUN_ID)).get(0));
		assertThat(fields.get(RedisFrameStore.FIELD_FRAME)).as("交付字节 == 归档字节")
			.isEqualTo(result.payload());
		assertThat(new String(result.payload(), StandardCharsets.UTF_8)).contains("\"seq\":1");

		// 幂等的范围是"这一次出帧"：内容相同的新帧是一条新帧，必须拿到新号（跳号正常）。
		FrameStore.AppendResult second = store.appendAndAllocateSeq(RUN_ID, frame("delta", "回包丢了"),
				payloadFactory());
		assertThat(second.seq()).as("内容相同 ≠ 同一帧：新的一次出帧要拿新号").isEqualTo(2L);
		assertThat(redis.streamIds(store.streamKey(RUN_ID))).containsExactly("1-0", "2-0");
	}

	/**
	 * <b>I-1</b>：幂等标记键必须<b>只含一次 token</b>，且四键同槽。
	 *
	 * <p>缺陷形态：脚本用 {@code KEYS[4] .. token} 拼键，而 Java 侧传进来的 {@code KEYS[4]} 已经是
	 * {@code ai:xack:<token>:<tag>} —— 于是真实键变成 {@code ai:xack:<token>:<tag><token>}：
	 * ① 键名里 token 出现两次（同一次出帧的标记与"按 token 复用"的语义漂移）；
	 * ② 键随 tag 之后又拼了一段，不再是"同槽 run 级键"的规范形（Cluster 下算出来的槽可能变，
	 * 多键脚本会被拒）；③ 任何按 {@code ai:xack:<token>...} 前缀排障/清理的脚本都对不上。
	 *
	 * <p>判据：脚本收到的第 4 个 KEY 恰好是 {@code ai:xack:<token>:{...run:<runId>}}，token 只出现一次，
	 * 且它与 seq / stream / legacy 三键同槽；幂等账本里也只有这一个键。
	 *
	 * <p>kill：把 Java 侧退回"只传前缀"（Lua 再拼 token）或把 Lua 的拼接留着 ⇒ 本条即红。
	 */
	@Test
	void ackKeyCarriesTheTokenExactlyOnceAndSharesTheRunSlot() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		FrameStore.AppendResult result = store.appendAndAllocateSeq(RUN_ID, frame("delta", "ack 键"), payloadFactory());
		assertThat(result.seq()).isEqualTo(1L);

		List<String> keys = redis.lastAppendScriptKeys;
		assertThat(keys).as("原子落档脚本的键：seq / stream / legacy / ack").hasSize(4);
		String ackKey = keys.get(3);
		assertThat(ackKey).as("键形必须是 ai:xack:<token>:{run:<runId>}").matches(
				java.util.regex.Pattern.quote(RedisFrameStore.ACK_PREFIX) + "[0-9a-f\\-]{36}:"
						+ java.util.regex.Pattern.quote("{run:" + RUN_ID + "}"));
		int tokenStart = ackKey.indexOf(RedisFrameStore.ACK_PREFIX) + RedisFrameStore.ACK_PREFIX.length();
		String token = ackKey.substring(tokenStart, ackKey.indexOf(':', tokenStart));
		assertThat(ackKey.indexOf(token)).as("token 只能出现一次（旧口径键名里出现两次）")
			.isEqualTo(ackKey.lastIndexOf(token));

		assertThat(FakeLeaseRedis.slot(ackKey)).as("ack 键必须与其余 run 级键同槽（多键脚本才合法）")
			.isEqualTo(FakeLeaseRedis.slot(store.streamKey(RUN_ID)));
		assertThat(redis.acks.keySet()).as("幂等账本里只有这一个键（没有'拼两次'的幽灵键）")
			.containsExactly(ackKey);
	}

	/**
	 * <b>I-6</b>：号占位必须是<b>每次出帧唯一且安全</b>的，且<b>任何生产帧都保持"原子分配 + 归档"</b>。
	 *
	 * <h2>缺陷形态（旧口径）</h2>
	 * 占位是常量 {@code Long.MAX_VALUE}，切分靠"在负载里找唯一一处 {@code "seq":9223372036854775807}"。
	 * 帧体里只要再出现一次同样的串（嵌套 map 恰好也有 {@code seq} 字段且取极大值、或工具参数投影带了它），
	 * 唯一性判定失败 —— 旧实现于是<b>退化成"先取号、再按精确号落档"两步式</b>：
	 * 重新打开"取了号却落不了档"的窗口（跨实例并发的低号帧会被表尾越过而丢弃），
	 * 而那正是本批要消灭的形态。
	 *
	 * <p><b>本批口径</b>：占位是<b>每帧随机生成的 18 位数字</b>（恒定宽度 ≥ 真实号，截断预算不变；
	 * 只在本次负载里找一次，碰撞概率 ~1e-18 且每帧不同）。找不到/不唯一时<b>明确失败</b>
	 * （ERROR + 该帧不落档），<b>绝不</b>退化到非原子路径。
	 *
	 * <p>判据：嵌套 {@code seq=Long.MAX_VALUE} 的帧照样一次脚本归档、ID == seq、顶层 seq 是真号、
	 * 嵌套字段原样保留；失败时帧体不残留占位号。
	 *
	 * <p>kill：把占位退回常量 {@code Long.MAX_VALUE} ⇒ 唯一性判定失败 ⇒ 走退化路径
	 * （本替身不建模两步式，`atomicAppendCalls` 仍为 1 —— 但"帧体不残留占位"与"归档号 == 1"
	 * 会因退化路径的号源不同而变红）；把占位改成"找不到就跳过"以外的任何非原子兜底 ⇒ 同上。
	 */
	@Test
	void nestedSeqFieldsNeverForceANonAtomicFallbackAndNeverLeaveAPlaceholderBehind() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);

		Map<String, Object> frame = frame("tool_result", "嵌套 seq 字段");
		frame.put("usage", Map.of("seq", Long.MAX_VALUE, "promptTokens", 1));
		FrameStore.AppendResult archived = store.appendAndAllocateSeq(RUN_ID, frame, payloadFactory());

		assertThat(archived.seq()).as("任何生产帧都走原子落档并拿到号").isEqualTo(1L);
		assertThat(redis.atomicAppendCalls).as("没有退化路径：原子落档脚本被调用").isEqualTo(1);
		assertThat(redis.streamIds(store.streamKey(RUN_ID))).containsExactly("1-0");
		String body = new String(archived.payload(), StandardCharsets.UTF_8);
		assertThat(body).as("顶层 seq 已替换为真号").contains("\"seq\":1");
		assertThat(body).as("嵌套的 seq 字段原样保留（占位只替换顶层那一处）")
			.contains("\"seq\":" + Long.MAX_VALUE);

		redis.failXadd = true;
		Map<String, Object> failing = frame("delta", "落档失败");
		FrameStore.AppendResult skipped = store.appendAndAllocateSeq(RUN_ID, failing, payloadFactory());
		assertThat(skipped.seq()).as("落不了档 ⇒ 不交付").isZero();
		assertThat(failing).as("失败的帧体不得残留占位号（否则调用方会拿着一个假号继续走）")
			.doesNotContainKey("seq");
	}

	/**
	 * <b>I-2</b>：读取窗口必须按<b>条目</b>（entry 数）与<b>精确 afterSeq</b> 判定，
	 * 不得在 seq 空间做 {@code tail.seq − 窗口} 的减法 —— 跳号（降级重试 / 遗留墙钟 ID）
	 * 会让那个下限越过<b>仍然存在</b>的帧。
	 *
	 * <h2>缺陷形态</h2>
	 * 窗口 = 2 条、归档 = 1, 2, 3, 1e12, 1e12+1（后两条是遗留墙钟量级的号，跳号正常）。
	 * 读者带 {@code lastSeq=2} 重挂：旧实现把下限算成 {@code tail − 2 = 1e12 − 1}，
	 * 于是<b>3 号这一帧明明还在，却被跳过</b>（重挂者永久缺一帧）；同时"是否截断"也是用
	 * 这个错误的 seq 下限推出来的。
	 *
	 * <p><b>本批口径</b>：按 afterSeq 精确取"最近的窗口条数"（升序），是否截断只看
	 * ① 读取是否被条数窗口截住（还有更晚的帧没给完 ⇒ 仅"分批"，不算丢）或
	 * ② 游标是否早于<b>幸存窗口的第一条</b>（那一段确实不可回放 ⇒ 截断）。
	 *
	 * <p>kill：退回 {@code from = max(afterSeq, tail − window)} ⇒ 首条断言变成
	 * {@code [1e12, 1e12+1]}（3 号被吞）即红。
	 */
	@Test
	void windowReadUsesEntriesAndExactAfterSeqInsteadOfSeqArithmetic() {
		FakeFrameRedis redis = new FakeFrameRedis();
		// MAXLEN ~ 近似裁剪：保留"窗口 + 冗余"条 —— 这正是"跳号 + 裁剪"同时出现的现场。
		redis.retainExtra = 2;
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 2);
		long[] seqs = { 1L, 2L, 3L, 1_000_000_000_000L, 1_000_000_000_001L, 1_000_000_000_002L };
		for (long seq : seqs) {
			appendAt(store, seq, "帧" + seq);
		}
		// 6 条写入、limit = 2 + 2 = 4 ⇒ 幸存 3, 1e12, 1e12+1, 1e12+2（首条从 1 被推到 3）。
		assertThat(redis.counters.get(store.trimKey(RUN_ID))).isEqualTo(3L);

		FrameStore.ReadResult delta = store.readFromWithStatus(RUN_ID, 2L, 0);
		assertThat(seqs(delta.frames())).as("按精确 afterSeq 取最近窗口：3 号仍存在，不得被 seq 减法跳过")
			.containsExactly(3L, 1_000_000_000_000L);
		assertThat(delta.truncated()).as("读者下一号正好是幸存首条 ⇒ 没缺").isFalse();
		assertThat(delta.degraded()).isFalse();

		FrameStore.ReadResult full = store.readFromWithStatus(RUN_ID, 0L, 0);
		assertThat(seqs(full.frames())).as("全量回放给最新窗口（M′ 不变）")
			.containsExactly(1_000_000_000_001L, 1_000_000_000_002L);
		assertThat(full.truncated()).as("全量回放确实跳过了更早的条目").isTrue();
		// 返修第 3 条（四轮）：缺口取"窗口外那段"与"更低水位段"的<b>并集</b> ——
		// 水位 3 说明"从 1 起就不可恢复"，窗口外那段到 1e12（窗口头 1e12+1 之前那条）⇒ 缺口 [1, 1e12]。
		assertThat(full.trimmedFromSeq()).as("缺口起点不得低估（取更低的水位段）").isEqualTo(1L);
		assertThat(full.trimmedToSeq()).as("缺口终点取更远者（窗口头之前那条）").isEqualTo(1_000_000_000_000L);
	}

	/**
	 * <b>Minor</b>：幂等命中的早退路径<b>也要</b>续 stream / seq / ack 三处 TTL。
	 *
	 * <p>幂等命中意味着"这一帧已经在归档里"，它仍是活轮的帧：若早退不续期，滑动 TTL 会在重试窗口
	 * 里倒退，归档可能先于会话过期（重挂时读到的是"整轮没了"）。
	 *
	 * <p>kill：只在成功分支续期（早退只续 ack）⇒ 清空 TTL 后重试 ⇒ stream/seq 的 expiry 为 null，红。
	 */
	@Test
	void anIdempotentHitRenewsTheStreamAndSeqTtlToo() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.failAppendReplyTimes = 1;
		redis.clearExpiriesOnLostReply = true;

		FrameStore.AppendResult result = store.appendAndAllocateSeq(RUN_ID, frame("delta", "回包丢了"), payloadFactory());

		assertThat(result.seq()).as("幂等命中：回传的是已归档的号").isEqualTo(1L);
		assertThat(redis.expiry(store.streamKey(RUN_ID))).as("事件日志 TTL 必须在幂等命中时续上")
			.isEqualTo(java.time.Duration.ofHours(24));
		assertThat(redis.expiry(store.seqKey(RUN_ID))).as("序号键 TTL 同理").isEqualTo(java.time.Duration.ofHours(24));
	}

	/**
	 * <b>Minor</b>：脚本"拒绝交出刚重建的低号"这条早退路径，也必须给刚 {@code INCR} 出来的
	 * seq 键补 TTL —— 否则计数器键会<b>永久驻留</b>（INCR 新建的键默认无 TTL），
	 * 而它本应与归档同寿。
	 *
	 * <p>构造：归档已有 1..3、计数器被删（刚重建）、末号持续读不出 ⇒ 脚本拒绝交号；
	 * 后续尝试虽被拒/被表尾挡下，但<b>第一次</b>拒绝就已经让计数器存在了。
	 *
	 * <p>kill：去掉拒绝分支前的 {@code EXPIRE} ⇒ 该键 expiry 为 null，红。
	 */
	@Test
	void aRefusedAllocationStillLeavesATtlOnTheRecreatedCounter() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		appendAt(store, 1L, "一");
		appendAt(store, 2L, "二");
		appendAt(store, 3L, "三");
		redis.dropCounter(store.seqKey(RUN_ID));
		// 键真的过期：值与会话 TTL 一起消失（只删计数器会留下旧的 expiry，测不出"早退不补 TTL"）。
		redis.expiries.remove(store.seqKey(RUN_ID));
		redis.failTailRead = true;

		FrameStore.AppendResult result = store.appendAndAllocateSeq(RUN_ID, frame("delta", "拒绝"), payloadFactory());

		assertThat(result.seq()).as("无法证明唯一的号不交出去（该帧不落档）").isZero();
		assertThat(redis.expiry(store.seqKey(RUN_ID))).as("拒绝路径也必须给刚 INCR 出来的键补 TTL（否则永久驻留）")
			.isNotNull();
	}

	/**
	 * <b>返修第 2 条</b>：截断只能来自<b>真实发生过的归档裁剪</b>，由原子落档脚本记录低水位。
	 *
	 * <h2>为什么不能用"首条 &gt; afterSeq+1"猜</h2>
	 * 生产允许跳号（降级重试、遗留墙钟 ID、落档失败后重试）—— 号不连续 ≠ 帧被裁。
	 * 旧口径拿"幸存首条与游标的差"推断，会把跳号误报成"不可完整恢复"（前端据此提示用户"少了内容"）。
	 *
	 * <h2>本批口径</h2>
	 * 原子落档脚本在 XADD 前后各读一次<b>幸存首条</b>：只有首条被裁剪推高时，才把新的首条号
	 * 记进 {@code ai:xtrim}（同槽、随会话 TTL、只升不降）。读侧判据 = {@code afterSeq + 1 < 低水位}，
	 * 并且只在<b>有低水位</b>时下结论（老流没有水位 ⇒ 不臆测）。
	 *
	 * <p>判据：窗口 2（精确裁剪）追加 1..5 后，低水位推进到 4；读者 1 → 截断且缺口区间是
	 * {@code [2, 3]}；读者 3 → 不截断（下一号正好是幸存首条）。
	 *
	 * <p>kill：去掉脚本里的"首条变化才记水位"⇒ 低水位缺失、读者 1 的 truncated 为 false（红）；
	 * 用"首条 &gt; afterSeq+1"猜 ⇒ 见相邻用例（跳号会误报）。
	 */
	@Test
	void realTrimIsRecordedAsAWatermarkAndDrivesTheTruncationGap() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 2);
		for (int i = 1; i <= 5; i++) {
			// 用生产写路径（原子落档）：只有这条路径会在 XADD 前后观察幸存首条。
			FrameStore.AppendResult archived = store.appendAndAllocateSeq(RUN_ID, frame("delta", "第" + i + "帧"),
					payloadFactory());
			assertThat(archived.seq()).isEqualTo(i);
		}

		assertThat(redis.counters.get(store.trimKey(RUN_ID))).as("真实裁剪必须留下低水位（幸存首条号）")
			.isEqualTo(4L);

		FrameStore.ReadResult behind = store.readFromWithStatus(RUN_ID, 1L, 0);
		assertThat(behind.truncated()).as("游标 1 的下一号 2 早于低水位 4 ⇒ 确实被裁过").isTrue();
		assertThat(behind.trimmedFromSeq()).as("缺口起点 = afterSeq + 1").isEqualTo(2L);
		assertThat(behind.trimmedToSeq()).as("缺口终点 = 低水位 - 1（含端点）").isEqualTo(3L);

		FrameStore.ReadResult atHead = store.readFromWithStatus(RUN_ID, 3L, 0);
		assertThat(atHead.truncated()).as("游标 3 的下一号正好是幸存首条 ⇒ 没缺").isFalse();
		assertThat(atHead.trimmedFromSeq()).isZero();
		assertThat(atHead.trimmedToSeq()).isZero();
	}

	/**
	 * <b>返修第 2 条（反证）</b>：<b>跳号</b>与<b>遗留墙钟 ID</b>都不得被误报成截断。
	 *
	 * <p>两条语料都<b>没有</b>触发过 MAXLEN 裁剪（窗口足够大），因此没有低水位：
	 * 号从 1 跳到 5 只是"3/4 被取走但没落档"，自动 ID 遗留只是"那一轮迁移过"——
	 * 读者既没有被裁掉的内容，也不该看到"不可完整恢复"。
	 */
	@Test
	void skippedSeqsAndLegacyWallClockIdsNeverReportTruncation() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		appendAt(store, 1L, "一");
		appendAt(store, 2L, "二");
		appendAt(store, 5L, "跳号后的第五帧");

		assertThat(store.readFromWithStatus(RUN_ID, 2L, 0).truncated()).as("纯跳号不是截断").isFalse();
		assertThat(redis.counters).as("没有裁剪就没有水位").doesNotContainKey(store.trimKey(RUN_ID));

		// 遗留墙钟自动 ID（升级期记录）：它不是"被裁掉的帧"，同样不得触发截断。
		seedAutoIdEntry(redis, store.streamKey(RUN_ID), 9L, "{\"type\":\"delta\",\"seq\":9}");
		FrameStore.ReadResult legacy = store.readFromWithStatus(RUN_ID, 5L, 0);
		assertThat(legacy.truncated()).as("遗留墙钟 ID 不是截断（无水位 ⇒ 不臆测）").isFalse();
		assertThat(legacy.trimmedFromSeq()).isZero();
	}

	/**
	 * <b>返修第 3 条</b>：补齐/唤醒要能拿到<b>归档原文</b>，而不是"读成 Map 再重新序列化"。
	 *
	 * <p>为什么值得一个专口：一次补齐最多要投几十帧，逐帧 {@code readValue + writeValueAsBytes}
	 * 既多一遍 CPU，也容易让人误以为"实时字节 = 归档字节"。{@code readRawFrom} 直接返回
	 * Stream 记录里的 {@code frame} 字段原文（生产实现逐字节一致），语义边界写进接口注释。
	 *
	 * <p>kill：让 {@code readRawFrom} 走 {@code readFrom} 再反序列化/重排 ⇒ 键序或数字格式可能变，
	 * "逐字节相同"断言红。
	 */
	@Test
	void rawReadReturnsTheArchivedFrameTextVerbatim() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "tool_result");
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("result", "{\"nested\":[1,2,3],\"t\":\"中文 \"}");
		frame.put("usage", Map.of("seq", 9223372036854775807L));
		FrameStore.AppendResult archived = store.appendAndAllocateSeq(RUN_ID, frame, payloadFactory());

		List<FrameStore.RawFrame> raw = store.readRawFrom(RUN_ID, 0L, 10);

		assertThat(raw).hasSize(1);
		assertThat(raw.get(0).seq()).as("原文自带业务号").isEqualTo(archived.seq());
		assertThat(raw.get(0).json()).as("返回的就是 Stream 里那一条 frame 字段原文（不重排、不转义变化）")
			.isEqualTo(new String(archived.payload(), StandardCharsets.UTF_8));
	}

	/**
	 * <b>返修第 6 条</b>：锁内"回包未知"的重试<b>最多一次</b>。
	 *
	 * <p>为什么收窄：每次重试都是<b>持出帧锁</b>跑一次脚本（脚本里有 INCR + XADD）。
	 * 回包持续丢失时（连接半死），三次重试把出帧锁占住的时长翻倍，同一轮其它线程的帧全被拖住 ——
	 * 而重试的收益是"可能认出已经归档"，第二次之后边际收益为零（同 token 幂等判定是确定性的）。
	 * 因此：<b>原样一次 + 最多一次重试</b>；仍不收敛就按"未归档"处理（不交付，计数可见）。
	 *
	 * <p>判据：连续 5 次回包丢失 ⇒ 脚本<b>只被调用 2 次</b>（而不是 3 次），结果 skipped。
	 *
	 * <p>kill：退回三次循环 ⇒ {@code atomicAppendCalls == 3}，断言红。
	 */
	@Test
	void unknownReplyRetriedAtMostOnceInsideTheLock() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = store(redis);
		redis.failAppendReplyTimes = 5;

		FrameStore.AppendResult result = store.appendAndAllocateSeq(RUN_ID, frame("delta", "回包一直丢"), payloadFactory());

		assertThat(result.seq()).as("第二次用同一 token 认出已归档 ⇒ 正常交付那一个号").isEqualTo(1L);
		assertThat(redis.atomicAppendCalls).as("原样一次 + 最多一次重试（不得持锁重试三次）").isEqualTo(2);
		assertThat(redis.streamIds(store.streamKey(RUN_ID))).as("幂等判定不会写出第二条").containsExactly("1-0");
	}

	/**
	 * <b>返修第 1 条（三轮）</b>：全量回放给"最新窗口"时，若<b>流里的条目数 &gt; 返回条数</b>，
	 * 必须如实说 truncated，缺口按<b>最早未返回条目</b>计算。
	 *
	 * <h2>缺陷形态</h2>
	 * {@code MAXLEN ~} 是<b>近似</b>裁剪：流里可以比窗口多留最多 ~99 条。此时"最新窗口"读出的条数
	 * 小于 {@code XLEN}，最老的那 1..N 条<b>既不在返回里、也从未被物理裁掉</b> —— 只看"裁剪低水位"
	 * （只有真的裁剪才写）就说 truncated=false，前端会以为"全量回放完整"，那 1..N 条就静默缺席。
	 *
	 * <p><b>本批口径</b>：全量回放额外用 {@code XLEN} 与返回条数比：{@code XLEN > 返回条数} ⇒
	 * truncated=true，缺口 = [{@code 最早未返回条目}, {@code 窗口头之前那条}]（两次 O(1) 读：
	 * 首条 + 窗口头的前一条；<b>不用 seq 猜</b>）。
	 *
	 * <p>kill：只用"裁剪低水位"判 ⇒ 首次用例（MAXLEN~ 尚未物理裁剪）truncated 为 false，红。
	 */
	@Test
	void fullReplayReportsTruncationWhenTheStreamHoldsMoreEntriesThanTheReturnedWindow() {
		FakeFrameRedis redis = new FakeFrameRedis();
		// 窗口 4 条、但 MAXLEN ~ 允许多留 10 条：写 6 条时"物理上一条都没裁"。
		redis.retainExtra = 10;
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 4);
		for (int i = 1; i <= 6; i++) {
			appendAt(store, i, "第" + i + "帧");
		}
		assertThat(redis.counters).as("前提：没有发生物理裁剪（没有低水位）")
			.doesNotContainKey(store.trimKey(RUN_ID));
		assertThat(redis.stream(store.streamKey(RUN_ID))).as("前提：流里确实有 6 条").hasSize(6);

		FrameStore.ReadResult full = store.readFromWithStatus(RUN_ID, 0L, 0);

		assertThat(seqs(full.frames())).as("给最新窗口（含末帧）").containsExactly(3L, 4L, 5L, 6L);
		assertThat(full.truncated()).as("流长 > 返回条数 ⇒ 更早的条目没返回，必须如实说截断").isTrue();
		assertThat(full.trimmedFromSeq()).as("缺口起点 = 最早未返回条目").isEqualTo(1L);
		assertThat(full.trimmedToSeq()).as("缺口终点 = 窗口头之前那条（含端点）").isEqualTo(2L);

		// 控制组：窗口足够大时不得误报。
		FakeFrameRedis large = new FakeFrameRedis();
		RedisFrameStore roomy = new RedisFrameStore(large.redis, MAPPER, this.properties, 64);
		for (int i = 1; i <= 6; i++) {
			appendAt(roomy, i, "第" + i + "帧");
		}
		assertThat(roomy.readFromWithStatus(RUN_ID, 0L, 0).truncated()).as("全量都给到 ⇒ 不是截断").isFalse();
	}

	/**
	 * <b>返修第 4 条（三轮）</b>：裁剪低水位的"幸存首条"只认<b>业务 {@code seq} 字段</b>；
	 * 该字段缺失（升级期异常记录）时<b>不得</b>拿 ID 毫秒段顶替 —— 那会把墙钟量级写进水位，
	 * 让所有读者都被判成"被裁过"。无法确认就按"没有水位"处理（不报截断）。
	 *
	 * <p>kill：把 headSeq 回退到 {@code ID 毫秒段} ⇒ 本用例的红（水位被写成墙钟量级）。
	 */
	@Test
	void aSurvivingHeadWithoutSeqFieldNeverProducesAWatermark() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 2);
		// 铺一条只有 frame 字段、没有 seq 的异常记录（升级期坏数据形态）作为幸存首条。
		java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
		redis.connection.execute("XADD", store.streamKey(RUN_ID).getBytes(utf8), "1-0".getBytes(utf8),
				RedisFrameStore.FIELD_FRAME.getBytes(utf8), "{\"type\":\"delta\"}".getBytes(utf8));
		// 只追加两条：两次落档时"幸存首条"都还是那条没有 seq 的异常记录（窗口 2 条）。
		for (int i = 2; i <= 3; i++) {
			store.appendAndAllocateSeq(RUN_ID, frame("delta", "第" + i + "帧"), payloadFactory());
		}

		assertThat(redis.counters).as("首条没有业务 seq ⇒ 无法确认水位，不得用 ID 毫秒段顶替")
			.doesNotContainKey(store.trimKey(RUN_ID));
		assertThat(store.readFromWithStatus(RUN_ID, 0L, 0).truncated()).as("没有水位就不臆测截断").isFalse();
	}

	/**
	 * <b>返修第 3 条（四轮）· 假截断</b>：单条帧体坏损（无法解析）时，XLEN 仍会把"条目数"算进去，
	 * 而"可解析帧数"会少一条 —— 若拿<b>可解析帧数</b>去比 XLEN，就会把"一条坏帧"误报成
	 * "归档被裁、有缺口"（前端据此提示"不可完整恢复"，是错的）。
	 *
	 * <p>口径：与 XLEN 比的是<b>读到的记录条数</b>（含坏损条目），不是"成功解析的帧数"。
	 *
	 * <p>kill：用 {@code frames.size()} 比 XLEN ⇒ 本条 truncated 变 true，红。
	 */
	@Test
	void anUnparseableFrameNeverFakesATruncation() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 4);
		appendAt(store, 1L, "一");
		// 坏损记录（frame 不是 JSON）：占一个条目、解析不出帧。
		java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
		redis.connection.execute("XADD", store.streamKey(RUN_ID).getBytes(utf8), "2-0".getBytes(utf8),
				RedisFrameStore.FIELD_SEQ.getBytes(utf8), "2".getBytes(utf8),
				RedisFrameStore.FIELD_FRAME.getBytes(utf8), "{not-json".getBytes(utf8));
		appendAt(store, 3L, "三");
		appendAt(store, 4L, "四");
		assertThat(redis.connection.xLen(store.streamKey(RUN_ID).getBytes(utf8))).as("前提：4 条条目").isEqualTo(4);

		FrameStore.ReadResult full = store.readFromWithStatus(RUN_ID, 0L, 0);

		assertThat(seqs(full.frames())).as("坏损条目被跳过（只有 3 帧可解析）").containsExactly(1L, 3L, 4L);
		assertThat(full.truncated()).as("条目数 == 返回条数 ⇒ 没有缺口，坏帧不得算成截断").isFalse();
		assertThat(full.trimmedFromSeq()).isZero();
	}

	/**
	 * <b>返修第 3 条（四轮）· 缺口合并</b>：全量回放的缺口必须<b>覆盖更低水位</b>，
	 * 不能只报"窗口外那一段"。
	 *
	 * <p>现场：水位 = 2（1 号已被物理裁掉），之后更早的中间号从未落档（跳号），
	 * 幸存条目从 5 开始且多于窗口 ⇒ 窗口外还有 5..N-窗口 没返回。
	 * 只看 windowGap 会报 {@code archive-from = 5} —— 把"从 1 起就不可恢复"低估成"从 5 起"；
	 * 正确做法是两个信号取<b>并集</b>：from = min(游标+1, 窗口外首条)，to = max(水位-1, 窗口头之前那条)。
	 *
	 * <p>kill：只用水位或只用 windowGap ⇒ 首条断言（from=1）或末条（to=8）红。
	 */
	@Test
	void fullReplayGapCoversTheLowerWatermarkAsWellAsTheWindowOverflow() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore store = new RedisFrameStore(redis.redis, MAPPER, this.properties, 2);
		// 造水位 2：窗口 2、精确裁剪，写 1..3 ⇒ 首条被推到 2（水位=2），1 号物理消失。
		for (int i = 1; i <= 3; i++) {
			appendAt(store, i, "第" + i + "帧");
		}
		assertThat(redis.counters.get(store.trimKey(RUN_ID))).as("前提：水位 = 2").isEqualTo(2L);
		// 再写 5..9（跳号：3/4 不存在），每次都会推高首条 ⇒ 水位随之涨到 5..；为保持"水位更低"，
		// 这里改用**不触发水位更新**的路径：直接铺原始记录（模拟升级期/异常写入）。
		java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
		for (long seq = 5L; seq <= 9L; seq++) {
			String frameJson = "{\"type\":\"delta\",\"seq\":" + seq + ",\"atMs\":1700000000000,\"text\":\"x\"}";
			redis.connection.execute("XADD", store.streamKey(RUN_ID).getBytes(utf8), (seq + "-0").getBytes(utf8),
					RedisFrameStore.FIELD_SEQ.getBytes(utf8), String.valueOf(seq).getBytes(utf8),
					RedisFrameStore.FIELD_FRAME.getBytes(utf8), frameJson.getBytes(utf8));
		}
		assertThat(redis.counters.get(store.trimKey(RUN_ID))).as("前提：水位仍是 2（更低）").isEqualTo(2L);

		FrameStore.ReadResult full = store.readFromWithStatus(RUN_ID, 0L, 0);

		assertThat(seqs(full.frames())).as("给最新窗口（2 条）").containsExactly(8L, 9L);
		assertThat(full.truncated()).isTrue();
		assertThat(full.trimmedFromSeq()).as("缺口起点不得低估：水位说'从 1 起不可恢复'").isEqualTo(1L);
		assertThat(full.trimmedToSeq()).as("缺口终点取更远者（窗口头 8 之前那条 = 7）").isEqualTo(7L);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────
	private RedisFrameStore store(FakeFrameRedis redis) {
		return new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
	}

	/**
	 * 落档负载工厂：与生产同口径（{@code FrameSizeLimiter.apply}）。它在<b>最终号确定之后</b>才跑，
	 * 帧里的 {@code seq} 由实现注入 —— 本类只负责序列化。
	 */
	private static FrameStore.PayloadFactory payloadFactory() {
		return frame -> {
			try {
				return MAPPER.writeValueAsBytes(frame);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	private static Map<String, Object> frame(String type, String marker) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("text", marker);
		return frame;
	}

	/** 用原始 {@code XADD ... *} 铺一条旧实现的自动 ID 记录（升级期遗留形态）。 */
	private static void seedAutoIdEntry(FakeFrameRedis redis, String key, long seq, String frameJson) {
		java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
		redis.connection.execute("XADD", key.getBytes(utf8), "*".getBytes(utf8),
				RedisFrameStore.FIELD_SEQ.getBytes(utf8), String.valueOf(seq).getBytes(utf8),
				RedisFrameStore.FIELD_FRAME.getBytes(utf8), frameJson.getBytes(utf8));
	}

	/** 按给定号归档一帧（不经取号：模拟"号已由 Redis 定下"的形态，用来构造跳号语料）。 */
	private static void appendAt(RedisFrameStore store, long seq, String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("text", text);
		frame.put("seq", seq);
		FrameStore.AppendResult result = store.appendAndGetSeq(RUN_ID, frame, payloadFactory());
		assertThat(result.seq()).as("预置归档必须真的落档（seq=%d）", seq).isEqualTo(seq);
	}

	private static List<Long> seqFields(FakeFrameRedis redis, String streamKey) {
		List<Long> out = new ArrayList<>();
		for (Map<byte[], byte[]> record : redis.stream(streamKey)) {
			Map<String, byte[]> fields = FakeFrameRedis.asMap(record);
			out.add(Long.parseLong(new String(fields.get(RedisFrameStore.FIELD_SEQ), StandardCharsets.UTF_8)));
		}
		return out;
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		List<Long> out = new ArrayList<>(frames.size());
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

}
