package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.observe.RedisDegradation;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.sse.SseWriteBudget;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2（2026-10-30）· SSE 交付侧的<b>顺序不变量</b>。
 *
 * <h2>两条硬不变量</h2>
 * <ol>
 * <li><b>只有实际归档成功才交付</b>：没有归档号就没有可交付的顺序事实。旧实现在落档失败时
 *     仍用"取号时那个号"实时投递 —— 订阅者看到一个永不入档的号，游标被推过它之后，
 *     重挂/唤醒的差量读再也不会把这一帧补回来（永久缺帧）；同时唤醒还会按这个号广播，
 *     把别的实例也带进同一个洞；</li>
 * <li><b>归档序号 == 交付序号</b>：实时负载就是归档字节（同号、同内容），
 *     否则同一帧在实时流与回放里是两个号（前端 {@code lastSeq} 与按号去重同时错位）。</li>
 * </ol>
 *
 * <p>另附两条读取面口径：<b>重挂差量</b>按号精确（跳号不丢帧），以及后端给出的
 * <b>明确截断位</b>（{@code ReplayResult#truncated}）—— 前端据此提示"不可完整恢复"，
 * 而不是用"首帧号 &gt; lastSeq + 1"去猜（跳号是正常形态，猜出来的缺口会把跳号误报成丢帧）。
 */
@ExtendWith(OutputCaptureExtension.class)
class SseChatEmitterDeliveryInvariantTest {

	private static final String RUN_ID = "run-delivery-invariant";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	/**
	 * <b>归档失败 ⇒ 不交付、不发唤醒</b>（Task 2 第 2 条）。
	 *
	 * <p>第一帧的原子落档返回"未归档"（Redis 抖动）；第二帧正常归档（号 1）。
	 * 判据：订阅者只收到第二帧、它带归档号、唤醒只发一次且是归档号、归档里只有它。
	 *
	 * <p>kill：退回"落档失败但用取号号照发"⇒ 订阅者收到两帧（第一帧的号 1 与第二帧的号 2 都不在归档里），
	 * "只收到一帧 / 归档只有一帧 / 唤醒是归档号"三条同时红。
	 */
	@Test
	void framesThatWereNotArchivedAreNeitherDeliveredNorPublished() {
		FailsOnceFrameStore frames = new FailsOnceFrameStore();
		FakeWakeBus bus = new FakeWakeBus();
		SseChatEmitter out = emitter(frames, bus, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("这一帧落不了档");
		out.delta("这一帧正常归档");

		List<Map<String, Object>> delivered = FrameWire.normalize(wire.frames());
		assertThat(delivered).as("未归档的帧不得交付").hasSize(1);
		assertThat(delivered.get(0)).containsEntry("type", "delta").containsEntry("text", "这一帧正常归档");
		long deliveredSeq = ((Number) delivered.get(0).get("seq")).longValue();
		assertThat(deliveredSeq).as("交付号 = 归档号").isEqualTo(1L);

		List<Map<String, Object>> archived = FrameWire.normalize(frames.readFrom(RUN_ID, 0L, 0));
		assertThat(archived).as("归档里只有真正落档的那一帧").hasSize(1);
		assertThat(((Number) archived.get(0).get("seq")).longValue()).isEqualTo(deliveredSeq);
		assertThat(bus.published).as("唤醒上界必须是归档号（未归档的帧不广播，否则别的实例会去补一个不存在的号）")
			.containsExactly(RUN_ID + "@" + deliveredSeq);
	}

	/**
	 * <b>两个写出器（两个实例）竞争</b>：各自为同一轮出帧，全部走原子落档。
	 *
	 * <p>判据（每个订阅者独立看）：
	 * <ol>
	 * <li>收到的每一帧的 {@code seq} 都在归档里，且帧体与归档逐字节一致（交付号 == 归档号）；</li>
	 * <li>收到的号<b>严格递增</b>（顺序语义：不允许"高号先交付、低号后交付"）；</li>
	 * <li>两边的帧合起来覆盖归档的全部号 —— 谁也没丢，谁也没重（跳号可以有，重复不行）。</li>
	 * </ol>
	 *
	 * <p>kill：退回两步式（先取号后落档、被越过就改号）⇒ 交付号与归档号分叉（第 1 条红）；
	 * 落档前就扇出 ⇒ 号可能逆序（第 2 条红）。
	 */
	@Test
	void competingWritersDeliverOnlyArchivedSeqsInIncreasingOrder() throws Exception {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore driverFrames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RedisFrameStore confirmFrames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		SseChatEmitter driver = emitter(driverFrames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		SseChatEmitter confirm = emitter(confirmFrames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire driverWire = FrameWire.attachTo(driver);
		FrameWire confirmWire = FrameWire.attachTo(confirm);

		int perWriter = 8;
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			pool.execute(() -> emitMany(driver, "driver", perWriter, start, failures));
			pool.execute(() -> emitMany(confirm, "confirm", perWriter, start, failures));
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(failures).isEmpty();

		List<Map<String, Object>> archived = FrameWire.normalize(driverFrames.readFrom(RUN_ID, 0L, 0));
		assertThat(archived).as("两实例共 16 帧全部归档").hasSize(2 * perWriter);
		Map<Long, String> bodyBySeq = new LinkedHashMap<>();
		for (Map<String, Object> frame : archived) {
			long seq = ((Number) frame.get("seq")).longValue();
			bodyBySeq.put(seq, String.valueOf(frame.get("text")));
		}
		assertThat(bodyBySeq.keySet()).as("归档号唯一").hasSize(2 * perWriter);

		// 收尾：把"最后一个唤醒"补给两个实例（本用例不接 pub/sub 总线，唤醒由这里显式注入）——
		// 于是"每个订阅者都应看到整轮"成为可判定的终态，而不是"谁最后写谁少几帧"。
		long tail = driverFrames.lastSeq(RUN_ID);
		driver.onWake(RUN_ID, tail);
		confirm.onWake(RUN_ID, tail);

		List<Long> deliveredByDriver = seqs(driverWire.frames());
		List<Long> deliveredByConfirm = seqs(confirmWire.frames());
		for (List<Long> delivered : List.of(deliveredByDriver, deliveredByConfirm)) {
			assertThat(delivered).as("每个订阅者收到的号严格递增（高号先交付再补低号 = 违规）").isSorted()
				.doesNotHaveDuplicates();
			assertThat(delivered).as("交付号必须都出现在归档里").allSatisfy(seq -> assertThat(bodyBySeq).containsKey(seq));
			// 同一轮的全部帧对<b>每个</b>订阅者都可见（不只"自己实例写的那一半"）：
			// 交付前按游标从归档补齐（Task 2 返修第 1 条），否则另一个实例写的号会被游标跳过。
			assertThat(delivered).as("该订阅者拿到了整轮归档（跨实例写的帧也在内）")
				.containsExactlyInAnyOrderElementsOf(bodyBySeq.keySet());
		}

		// 帧体逐字节一致：交付负载就是归档字节（同号同内容）
		for (Map<String, Object> frame : driverWire.frames()) {
			Map<String, Object> normalized = FrameWire.normalize(frame);
			long seq = ((Number) normalized.get("seq")).longValue();
			assertThat(normalized.get("text")).as("seq=%d 的交付内容必须等于归档内容", seq)
				.isEqualTo(bodyBySeq.get(seq));
		}
	}

	/**
	 * <b>重挂差量</b>：{@code ?lastSeq=2} 且归档是 1、2、5（3/4 是别的实例取走但没落档的号 = 正常跳号）。
	 *
	 * <p>判据：只回放 5（且只有一帧）、{@code degraded=false}、{@code truncated=false}。
	 * 跳号<b>不是</b>缺口：差量读按号 {@code > lastSeq} 取，所以"号不连续"绝不会让读者少帧，
	 * 也不会让读者多收（重复）。
	 *
	 * <p>kill：把差量读改成"按条数取尾"或"按 {@code lastSeq+1} 起读"⇒ 5 号被漏（前者）或 3/4 的空洞
	 * 被当成"没有新帧"（后者），两条断言红。
	 */
	@Test
	void reattachDeltaReturnsTheArchivedFramesAboveLastSeqEvenWithSkippedSeqs() throws Exception {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		appendAt(frames, 1L, "第一帧");
		appendAt(frames, 2L, "第二帧");
		appendAt(frames, 5L, "跳号后的第五帧");

		SseChatEmitter out = emitter(frames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wire = new FrameWire();
		SseChatEmitter.ReplayResult plan = out.replayAndAttachWithStatus(wire.emitter(), 2L, false);

		assertThat(plan.degraded()).as("读成功就不是降级").isFalse();
		assertThat(plan.truncated()).as("号只是跳了，没有帧被窗口裁掉").isFalse();
		List<Map<String, Object>> replayed = FrameWire.normalize(wire.frames());
		assertThat(seqs(replayed)).as("只补 > lastSeq 的帧（跳号不丢帧）").containsExactly(5L);
		assertThat(replayed.get(0)).containsEntry("text", "跳号后的第五帧");
		assertThat(frames.lastSeq(RUN_ID)).as("末号 = 归档末号").isEqualTo(5L);
	}

	/**
	 * <b>明确截断位</b>（Task 2 给 Task 3 的接口）：游标早于幸存窗口 ⇒ {@code truncated=true}。
	 *
	 * <p>窗口 4、归档 1..8：重挂 {@code lastSeq=1} 时读取下限被夹到窗口头 4，
	 * 于是 2..4 已经不可回放 —— 这是"确实少了一段"，必须由后端明确告知，
	 * 而不是让前端用"首帧号 &gt; lastSeq + 1"去猜（跳号时猜错）。
	 *
	 * <p>kill：不传这一位（默认 false 或从 degraded 推导）⇒ 首条断言红；
	 * 把每次回放都标成 truncated ⇒ 第二条（在窗口内时 false）红。
	 */
	@Test
	void replayBeforeTheSurvivingWindowReportsTheExplicitTruncationFlag() throws Exception {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 4);
		for (int i = 1; i <= 8; i++) {
			appendAt(frames, i, "第" + i + "帧");
		}
		SseChatEmitter out = emitter(frames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);

		FrameWire behind = new FrameWire();
		SseChatEmitter.ReplayResult truncated = out.replayAndAttachWithStatus(behind.emitter(), 1L, false);
		assertThat(truncated.truncated()).as("游标早于幸存窗口 ⇒ 后端必须明确说'截断'").isTrue();
		assertThat(truncated.degraded()).as("读没失败（只是窗口翻过去了）").isFalse();
		assertThat(seqs(behind.frames())).as("给的是最新窗口（含末帧）").containsExactly(5L, 6L, 7L, 8L);

		FrameWire inside = new FrameWire();
		SseChatEmitter.ReplayResult intact = out.replayAndAttachWithStatus(inside.emitter(), 6L, false);
		assertThat(intact.truncated()).as("在窗口内 ⇒ 不是截断").isFalse();
		assertThat(seqs(inside.frames())).containsExactly(7L, 8L);
	}

	/**
	 * <b>交付前必须补齐更低号</b>（Task 2 返修第 1 条）：原子分配只保证"归档序 == 分配序"，
	 * <b>不</b>保证"本实例的唤醒先到"。
	 *
	 * <h2>缺陷形态（真缺帧）</h2>
	 * 实例 A 归档 seq=1（它的 {@code PUBLISH} 还在路上），实例 B 归档 seq=2 并立刻本地扇出 ——
	 * B 的订阅者于是先收到 2、游标推到 2；随后 A 的唤醒（上界 1）到达 B，
	 * {@code onWake} 按"上界 ≤ 最小游标 ⇒ 无差量"短路，<b>1 号再也不会被投递</b>（永久缺帧）。
	 *
	 * <p><b>本批口径</b>：本地扇出<b>不得</b>只投自己的高号。投递一帧之前，先按<b>每个订阅者的游标</b>
	 * 从归档补出 {@code (游标, 本帧号)} 之间的全部帧（升序），再投本帧 —— 于是
	 * "谁先醒来"不再影响"订阅者看到的顺序与完整性"。补不完整（批上限）时本帧<b>不投</b>，
	 * 游标只推进到已交付处，下一次唤醒/扇出继续（绝不跳过中间号）。
	 *
	 * <p>kill：退回"只 fanout 自己的号"⇒ 首条断言只剩 {@code [2]}（1 被跳过）即红；
	 * 补齐后再重复投递（不做游标去重）⇒ 迟到唤醒那一步出现 {@code [1,2,1,2]} 或重复号。
	 */
	@Test
	void aHigherSeqIsNeverDeliveredBeforeAnArchivedLowerSeq() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore framesA = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RedisFrameStore framesB = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		SseChatEmitter instanceA = emitter(framesA, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		SseChatEmitter instanceB = emitter(framesB, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wireB = FrameWire.attachTo(instanceB);

		instanceA.delta("A 的第一帧");
		instanceB.delta("B 的第二帧");

		assertThat(seqs(wireB.frames())).as("B 的订阅者必须先收到归档里的 1，再收到 2（顺序与完整性都不由'谁先醒'决定）")
			.containsExactly(1L, 2L);
		assertThat(seqs(framesA.readFrom(RUN_ID, 0L, 0))).as("两帧都已归档").containsExactly(1L, 2L);

		// 迟到的唤醒：1 号已经交付过，不得重复；也不得再补任何帧。
		instanceB.onWake(RUN_ID, 1L);
		assertThat(seqs(wireB.frames())).as("迟到唤醒不得重复投递（游标去重）").containsExactly(1L, 2L);
	}

	/**
	 * <b>I-5</b>：终帧归档失败时，在线端<b>不得</b>静默等到超时。
	 *
	 * <h2>缺陷形态</h2>
	 * {@code done}/{@code error} 是"这一轮结束"的唯一信号。归档失败时若不投递也不收流，
	 * 连接会一直挂着等一个<b>永远不会再发</b>的终帧 —— 前端只能靠自己的看门狗超时才发现，
	 * 用户看到的是"转圈到超时"。而投递一个<b>未归档</b>的终帧更糟：重挂回放里没有它，
	 * 这一轮在归档视角永远"没结束"，`terminalFrameArchived` 也会一直说没有终帧。
	 *
	 * <p><b>本批口径</b>：不投未归档的终帧；改为走<b>既有恢复失败链</b>
	 * （N-2 / AR-05 的 typed error：{@code AI_REDIS_UNAVAILABLE + retryable + degraded}）并主动收流 ——
	 * 客户端拿到明确失败 + 连接关闭，重挂/看门狗按既有路径收敛。
	 *
	 * <p>kill：退回"未归档就 return"（不投也不收流）⇒ 首条断言（error 帧）与会话收尾断言同时红；
	 * 退回"照投 done"⇒ 末条断言（不得出现 done）红。
	 */
	@Test
	void anUnarchivedTerminalFrameIsNeverDeliveredAndClosesTheSubscribersThroughTheRecoveryChain() {
		TerminalFailingFrameStore frames = new TerminalFailingFrameStore();
		SseChatEmitter out = emitter(frames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("正文");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> received = FrameWire.normalize(wire.frames());
		assertThat(received).extracting(frame -> String.valueOf(frame.get("type")))
			.as("未归档的终帧不得投递；改为既有恢复失败链的 typed error")
			.containsExactly("delta", "error");
		assertThat(received.get(1)).as("恢复失败链的载荷（前端据此提示'暂不可用、可重试'）")
			.containsEntry("code", ErrorCodes.AI_REDIS_UNAVAILABLE)
			.containsEntry("retryable", true)
			.containsEntry("degraded", true);
		assertThat(received).as("不得出现未归档的 done").noneMatch(frame -> "done".equals(frame.get("type")));
		org.mockito.Mockito.verify(wire.emitter()).complete();
	}

	/**
	 * <b>I-4</b>：原子落档失败必须进统一降级计数（{@code /api/ai/health} 可读），日志每轮只吼一次。
	 *
	 * <p>为什么不能只有日志：Redis 抖动期"帧没落档"会连续发生，逐帧 WARN 会被轮询日志刷掉，
	 * 而"整轮帧都没进事件日志"是必须能被聚合观测的事实。
	 *
	 * <p>kill：只 WARN 不计数 ⇒ 计数断言红；每帧都 WARN ⇒ 日志出现次数断言红（>1）。
	 */
	@Test
	void aFailedAtomicAppendIsCountedOncePerFrameAndWarnedOncePerRun(CapturedOutput output) {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		redis.failXadd = true;
		long before = RedisDegradation.count(RedisDegradation.A7_FRAME_APPEND);

		Map<String, Object> first = new LinkedHashMap<>();
		first.put("type", "delta");
		first.put("atMs", 1_700_000_000_000L);
		first.put("text", "第一帧（落不了档）");
		Map<String, Object> second = new LinkedHashMap<>(first);
		second.put("text", "第二帧（落不了档）");
		FrameStore.AppendResult one = frames.appendAndAllocateSeq(RUN_ID, first, payloadFactory());
		FrameStore.AppendResult two = frames.appendAndAllocateSeq(RUN_ID, second, payloadFactory());

		assertThat(one.seq()).isZero();
		assertThat(two.seq()).isZero();
		assertThat(RedisDegradation.count(RedisDegradation.A7_FRAME_APPEND)).as("每帧未落档计一次（事件粒度 = 帧）")
			.isEqualTo(before + 2);
		assertThat(occurrences(output.getAll(), "事件日志原子落档失败")).as("日志每轮只吼一次（计数负责量级）")
			.isEqualTo(1);
	}

	/**
	 * <b>返修第 3 条</b>：交付前补齐<b>不得绕过实时容量闸</b>。
	 *
	 * <h2>缺陷形态（返修前）</h2>
	 * 补齐走 {@code sub.queue.add} 直接塞进无界队列：一个落后很远的订阅者（或一次长 backlog）
	 * 能把队列灌到几千帧 —— 队列无界、内存无界，而实时闸只数"实时帧"，这份 backlog 完全不受控；
	 * 同时一次性读 {@code WAKE_BATCH_LIMIT}(6000) 条并逐帧重新序列化，代价与延迟都不可预期。
	 *
	 * <p><b>本批口径</b>：补齐统一走 {@link #enqueueLive}（与实时扇出同一个容量闸），
	 * 单批上限取 {@code min(队列容量, WAKE_BATCH_LIMIT)}；闸满即<b>摘除订阅者</b>
	 * （前端带 lastSeq 重挂，走既有恢复链），绝不静默灌爆队列。
	 *
	 * <p>判据：容量 2、归档已有 5 帧、订阅者游标 0 ⇒ 一帧都不该"硬塞"进队列；
	 * 连接被主动摘除（complete），已投递条数 ≤ 容量。
	 *
	 * <p>kill：退回 {@code sub.queue.add} 直塞 ⇒ 队列里出现 5 帧、连接不被摘除（两条断言同时红）。
	 */
	@Test
	void catchUpUsesTheLiveCapacityGateAndEvictsInsteadOfFloodingTheQueue() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore framesA = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		RedisFrameStore framesB = new RedisFrameStore(redis.redis, MAPPER, this.properties, 6000);
		SseChatEmitter instanceA = emitter(framesA, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		int queueCapacity = 2;
		// 投递执行器<b>只排队不执行</b>：让"队列里已有实时帧"成为确定状态（否则同步写出会立刻排空）。
		List<Runnable> deferred = new CopyOnWriteArrayList<>();
		SseChatEmitter instanceB = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), framesB, MAPPER,
				deferred::add, queueCapacity, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), null, null);
		FrameWire wireB = FrameWire.attachTo(instanceB);

		instanceB.delta("B-1");                 // seq 1：实时入队 1 条（liveQueued = 1，游标 = 1）
		for (int i = 0; i < 3; i++) {
			instanceA.delta("A-" + i);          // seq 2..4：另一实例写，B 还没看到
		}
		instanceB.delta("B-2");                 // seq 5：扇出前必须补齐 2..4，但队列只剩 1 格

		assertThat(instanceB.subscriberCount()).as("补齐撞上容量闸 ⇒ 摘除订阅者（前端带 lastSeq 重挂补帧）")
			.isZero();
		assertThat(wireB.size()).as("摘除时清队列：不静默灌爆、也不把 backlog 当实时流硬塞").isZero();
		org.mockito.Mockito.verify(wireB.emitter()).complete();
		assertThat(deferred).as("闸满发生在入队阶段（drain 尚未被提交）").isNotEmpty();
	}

	/**
	 * <b>返修第 4 条</b>：投递线程发现"身份不可确认且没有可校验提示"时，<b>不得静默把队列停住</b>。
	 *
	 * <h2>缺陷形态（返修前）</h2>
	 * {@code submitDrain} 里遇到这种身份就直接 {@code return}（帧留在队列）—— 队列再没有被排空过：
	 * 若此刻没有新的出帧把它重新 kick，连接就挂着一个"永远不动"的队列（用户看到转圈），
	 * 而重试也没有任何节流/退出条件，容易形成"kick → 跳过 → 再 kick"的忙环。
	 *
	 * <p><b>本批口径</b>：主动<b>摘除订阅者</b>（{@code evict}）—— 前端带 lastSeq 重挂，
	 * 重挂会重新解析身份并回放归档（既有恢复链），既不会停队列，也不会有忙环。
	 *
	 * <p>构造：策略分支要求"身份解析因<b>读异常</b>失败且没有可校验提示"，它在生产上极难自然命中
	 * （有提示时读异常会退回已校验提示），因此这里用子类把解析结果固定成"不可确认"，
	 * 只验证 drain 的<b>处置</b>（摘除 vs 静默停摆）。回放已把帧暂存进队列 ⇒ 投递线程必然被触发。
	 *
	 * <p>kill：退回"直接 return"⇒ 订阅者仍在（{@code subscriberCount()} 非 0）、连接永不 complete，两条红。
	 */
	@Test
	void drainWithAnUnconfirmableIdentityEvictsInsteadOfStallingTheQueue() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		frames.seed(RUN_ID, frameOf("delta", 1L, "一"), frameOf("delta", 2L, "二"));
		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), frames, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), null, null) {
			@Override
			IdentityOutcome deliveryIdentityOutcome() {
				return new IdentityOutcome(null, true);
			}
		};
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult plan = out.replayAndAttachWithStatus(wire.emitter(), null, false);

		assertThat(plan.staged()).as("回放确实暂存了帧（否则测不到投递线程的身份分支）").isEqualTo(2);
		assertThat(out.subscriberCount()).as("身份不可确认 ⇒ 主动摘除（重挂补帧），不得把队列停住").isZero();
		assertThat(wire.size()).as("不得带着 null 身份（legacy 键形）继续投递").isZero();
		org.mockito.Mockito.verify(wire.emitter()).complete();
	}

	/**
	 * <b>返修第 4 条（三轮）</b>：合成终帧收口后必须补一次<b>终态回收判定</b> ——
	 * 否则"已终态 + 无订阅者"的写出器会一直留在注册表里（持有订阅表/队列/唤醒目标引用）。
	 *
	 * <p>为什么不能指望别的出口：常规收口是"最后一个订阅者离开时由 {@code closeSubscription} 触发回收"，
	 * 而合成终帧是在 {@code completeOne} 里关闭连接的 —— 那条路径已经走过一次回收判定；
	 * 但现在 {@code terminating} 是**之后**才置的，判定条件当时还不成立，只能在这里补一次。
	 *
	 * <p>kill：去掉这行 ⇒ 钩子不被调用，红。
	 */
	@Test
	void theSyntheticTerminalStillRecyclesTheEmitter() {
		TerminalFailingFrameStore frames = new TerminalFailingFrameStore();
		SseChatEmitter out = emitter(frames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wire = FrameWire.attachTo(out);
		AtomicInteger recycled = new AtomicInteger();
		out.setRecycleHook(recycled::incrementAndGet);

		out.delta("正文");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		assertThat(out.isTerminating()).isTrue();
		assertThat(recycled.get()).as("合成终帧收口后必须触发一次终态回收（释放注册表条目）").isEqualTo(1);
	}

	/**
	 * <b>返修第 5 条</b>：补发 synthetic 终帧（未归档终帧的恢复失败链）之后必须收口 ——
	 * 任何心跳/业务帧都不得再发。
	 *
	 * <p>为什么：合成终帧表达"这一轮在这个连接上已经结束"。若之后还能再发帧，
	 * 前端会在一个"已终结"的流上收到迟到内容（比如挂起期心跳），把终态又拉回进行中。
	 *
	 * <p>kill：不在合成终帧后置 {@code terminating} ⇒ 之后的心跳/业务帧仍会被扇出（末条断言红）。
	 */
	@Test
	void afterTheSyntheticTerminalErrorNothingElseIsDelivered() {
		TerminalFailingFrameStore frames = new TerminalFailingFrameStore();
		SseChatEmitter out = emitter(frames, null, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("正文");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		int deliveredBefore = wire.size();
		out.heartbeat(Map.of("phase", "suspended"));
		out.delta("终帧之后不应再出现的内容");

		assertThat(out.isTerminating()).as("合成终帧 = 这一轮在这个连接上的终点 ⇒ 必须置终态（不再收新帧）")
			.isTrue();
		assertThat(wire.size()).as("合成终帧之后不得再发任何帧（含心跳）").isEqualTo(deliveredBefore);
		assertThat(FrameWire.normalize(wire.frames())).noneMatch(frame -> "delta".equals(frame.get("type"))
				&& "终帧之后不应再出现的内容".equals(frame.get("text")));
	}

	private static Map<String, Object> frameOf(String type, long seq, String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("text", text);
		frame.put("seq", seq);
		return frame;
	}

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

	private static int occurrences(String text, String needle) {
		int count = 0;
		int index = text.indexOf(needle);
		while (index >= 0) {
			count++;
			index = text.indexOf(needle, index + needle.length());
		}
		return count;
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter(FrameStore frames, FrameWakeBus bus, int maxFrameBytes) {
		return new SseChatEmitter(RUN_ID, new InMemoryRunStore(), frames, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, maxFrameBytes, SseWriteBudget.direct(), bus, null);
	}

	private static void emitMany(SseChatEmitter out, String marker, int count, CountDownLatch start,
			List<Throwable> failures) {
		try {
			start.await(5, TimeUnit.SECONDS);
			for (int i = 0; i < count; i++) {
				out.delta(marker + "-" + i);
			}
		}
		catch (Throwable ex) {
			failures.add(ex);
		}
	}

	/** 按给定号归档一帧（不经取号：模拟"号已经由 Redis 定下"的形态）。 */
	private static void appendAt(RedisFrameStore frames, long seq, String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("atMs", 1_700_000_000_000L);
		frame.put("text", text);
		frame.put("seq", seq);
		FrameStore.AppendResult result = frames.appendAndGetSeq(RUN_ID, frame, f -> {
			try {
				return MAPPER.writeValueAsBytes(f);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		assertThat(result.seq()).as("预置归档必须真的落档（seq=%d）", seq).isEqualTo(seq);
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

	/** 只让<b>终帧</b>（done/error）落档失败：模拟"正文都归档了、终帧写不进 Redis"。 */
	private static final class TerminalFailingFrameStore implements FrameStore {

		private final InMemoryFrameStore delegate = new InMemoryFrameStore();

		@Override
		public long nextSeq(String runId) {
			return this.delegate.nextSeq(runId);
		}

		@Override
		public void append(String runId, Map<String, Object> frame) {
			this.delegate.append(runId, frame);
		}

		@Override
		public AppendResult appendAndAllocateSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
			String type = String.valueOf(frame.get("type"));
			if ("done".equals(type) || "error".equals(type)) {
				return AppendResult.skipped();
			}
			return FrameStore.super.appendAndAllocateSeq(runId, frame, payloadFactory);
		}

		@Override
		public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
			return this.delegate.readFrom(runId, afterSeq, limit);
		}

		@Override
		public long lastSeq(String runId) {
			return this.delegate.lastSeq(runId);
		}

	}

	/**
	 * 第一次原子落档返回"未归档"（模拟 Redis 抖动期），其余委托内存替身。
	 *
	 * <p>刻意<b>不</b>在失败时退回取号：本用例要证的就是"没有归档号 ⇒ 不交付"。
	 */
	private static final class FailsOnceFrameStore implements FrameStore {

		private final InMemoryFrameStore delegate = new InMemoryFrameStore();

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public long nextSeq(String runId) {
			return this.delegate.nextSeq(runId);
		}

		@Override
		public void append(String runId, Map<String, Object> frame) {
			this.delegate.append(runId, frame);
		}

		@Override
		public AppendResult appendAndAllocateSeq(String runId, Map<String, Object> frame, PayloadFactory payloadFactory) {
			if (this.calls.incrementAndGet() == 1) {
				return AppendResult.skipped();
			}
			return FrameStore.super.appendAndAllocateSeq(runId, frame, payloadFactory);
		}

		@Override
		public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
			return this.delegate.readFrom(runId, afterSeq, limit);
		}

		@Override
		public long lastSeq(String runId) {
			return this.delegate.lastSeq(runId);
		}

	}

}
