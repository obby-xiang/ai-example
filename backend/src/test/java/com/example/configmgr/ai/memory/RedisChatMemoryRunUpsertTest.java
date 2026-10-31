package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆仓储的<b>按轮 UPSERT</b>（修复"挂起轮 A + 新轮 B 整窗覆盖互删"的存储面）。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>每条写回只替换/追加<b>本 run 的块</b>，别的 run 的块原样保留；块间按 {@link MemoryTurn#turnStartedAtMs()}
 *     有序（同毫秒按 runId）；</li>
 * <li>读-改-写必须在<b>会话级 CAS</b> 里完成：两个实例/两个写者交错时后者重读再合并，
 *     <b>不得</b>用"拒绝旧写"代替合并（拒绝旧写仍会丢掉一个成功轮）；</li>
 * <li>合并后按 {@code maxMessages} 裁剪且不拆 tool 配对；空历史只清本 run 的块；</li>
 * <li>key 仍是 {@code chat:mem:<tenantId>:<userId>:{<escapedId>}}（版本键同 hash tag，Cluster 同槽）。</li>
 * </ul>
 *
 * <p>断言对象一律是<b>最终窗口内容</b>（{@code findByConversationId} 的正文与 metadata），不是调用次数。
 */
class RedisChatMemoryRunUpsertTest {

	private static final String CID = "s-run-upsert";

	private static final String VERSION_KEY = "chat:mem:1:101:{" + CID + "}:ver";

	private static final String GENERATION_KEY = "chat:mem:1:101:{" + CID + "}:gen";

	private static final MemoryTurn A = new MemoryTurn("run-a", 1_000L);

	private static final MemoryTurn B = new MemoryTurn("run-b", 2_000L);

	private static final MemoryTurn C = new MemoryTurn("run-c", 3_000L);

	private static final int WINDOW = 40;

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(6));

	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void tearDown() {
		this.identity.close();
	}

	@Test
	void earlierRunConvergingAfterTheLaterOneKeepsBothRounds() {
		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		assertThat(texts(this.repository.findByConversationId(CID)))
			.as("A 后收敛不得删掉 B 的成功轮，顺序按本轮起点")
			.containsExactly("A问", "A答", "B问", "B答");
	}

	@Test
	void laterRunConvergingLastKeepsTheEarlierRound() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		assertThat(texts(this.repository.findByConversationId(CID))).containsExactly("A问", "A答", "B问", "B答");
	}

	@Test
	void cancelledRoundAndSuccessfulRoundBothSurvive() {
		List<Message> cancelled = List.of(new UserMessage("A问"),
				AssistantMessage.builder().content("A 的半截").properties(java.util.Map.of("stopped", true)).build());
		this.repository.upsertRun(CID, A, cancelled, WINDOW, true);

		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		List<Message> window = this.repository.findByConversationId(CID);
		assertThat(texts(window)).containsExactly("A问", "A 的半截", "B问", "B答");
		assertThat(window.get(1).getMetadata()).as("取消轮的展示语义（stopped）不变").containsEntry("stopped", true);
		assertThat(RunMemoryMerge.runIdOf(window.get(1))).isEqualTo(A.runId());
		assertThat(RunMemoryMerge.runIdOf(window.get(2))).isEqualTo(B.runId());
	}

	@Test
	void advisorAppendsAreScopedToTheirOwnRun() {
		this.repository.upsertRun(CID, A, List.of(new UserMessage("A问")), WINDOW, true);
		this.repository.upsertRun(CID, A, List.of(AssistantMessage.builder().content("A答").build()), WINDOW, false);

		this.repository.upsertRun(CID, B, List.of(new UserMessage("B问")), WINDOW, false);

		assertThat(texts(this.repository.findByConversationId(CID))).containsExactly("A问", "A答", "B问");
	}

	/**
	 * 两个写者交错（另一实例在"读之后、CAS 之前"写下它的块）：本实例的 CAS 必须落空并<b>重读再合并</b>，
	 * 两个成功轮都要在窗口里。只加"版本号拒绝旧写"会让后到的写直接丢弃 ⇒ 一个成功轮丢失。
	 */
	@Test
	void concurrentWritersAreMergedInsteadOfOneOverwritingTheOther() {
		AtomicBoolean injected = new AtomicBoolean();
		this.fake.beforeScript = () -> {
			if (!injected.compareAndSet(false, true)) {
				return;
			}
			// 另一实例（同一 Redis、同一身份）在本次 CAS 之前写下 B 的整轮。
			new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(6))
				.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);
		};

		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		assertThat(texts(this.repository.findByConversationId(CID)))
			.as("CAS 落空后必须重读再合并：冲突不得变成覆盖或丢弃")
			.containsExactly("A问", "A答", "B问", "B答");
		assertThat(this.fake.casAttempts.get()).as("确实发生了一次 CAS 落空后的重试").isGreaterThanOrEqualTo(2);
		assertThat(this.fake.values).as("会话级 CAS 版本确实被推进").containsKey(VERSION_KEY);
	}

	/**
	 * <b>清空与在飞写回的交错（清空发生在"读完版本/窗口之后、CAS 之前"）</b>。
	 *
	 * <p>写者手里是清空前那份带归属的整段历史；用户在这一刻清空历史。若清空把版本键<b>删掉</b>
	 * （缺失 = 0），写者读到的旧版本恰好又是 0 ⇒ CAS 仍会通过，历史被写回来。正确口径：清空推进
	 * <b>代次 tombstone</b>，本写者这一次 CAS 直接作废，<b>并且不得靠重试绕过</b>（重试重读到空窗后
	 * 拿自己的整段历史再写一次，同样是复活）。
	 */
	@Test
	void writerThatReadBeforeAClearCannotRestoreTheClearedHistory() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		// 本轮收敛会带上本轮的新消息（真的会写）；窗口没变时的"等值早退"不走 CAS，测不到交错
		List<Message> inFlightHistory = new ArrayList<>(this.repository.findByConversationId(CID));
		inFlightHistory.add(new UserMessage("A续问"));
		inFlightHistory.add(AssistantMessage.builder().content("A续答").build());
		int casBefore = this.fake.casAttempts.get();
		AtomicBoolean cleared = new AtomicBoolean();
		this.fake.beforeScript = () -> {
			if (!cleared.compareAndSet(false, true)) {
				return;
			}
			this.repository.deleteByConversationId(CID);
		};

		boolean written = this.repository.upsertRun(CID, A, inFlightHistory, WINDOW, true);

		assertThat(written).as("清空发生在本次写回的 CAS 之前 ⇒ 这一笔必须放弃").isFalse();
		assertThat(this.repository.findByConversationId(CID)).as("被清空的历史不得复活").isEmpty();
		assertThat(this.fake.casAttempts.get()).as("清空是终局：不得用重试把历史写回来").isEqualTo(casBefore + 1);
	}

	/**
	 * <b>清空发生在两次 CAS 之间</b>（第一次被并发写顶掉、重试期间用户清空）。
	 *
	 * <p>重试不能再拿"新版本 + 空窗"当作可以继续写：否则重试本身就把清空前那份历史写回来了。
	 */
	@Test
	void clearDuringCasRetryAlsoAbortsInsteadOfRestoringHistory() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		List<Message> inFlightHistory = new ArrayList<>(this.repository.findByConversationId(CID));
		inFlightHistory.add(new UserMessage("A续问"));
		inFlightHistory.add(AssistantMessage.builder().content("A续答").build());
		AtomicInteger attempts = new AtomicInteger();
		AtomicBoolean inHook = new AtomicBoolean();
		this.fake.beforeScript = () -> {
			if (!inHook.compareAndSet(false, true)) {
				return;
			}
			try {
				int attempt = attempts.incrementAndGet();
				if (attempt == 1) {
					// 另一实例先落地 ⇒ 本次 CAS 落空，进入重试
					new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(6)).upsertRun(CID, C,
							history("C问", "C答"), WINDOW, true);
				}
				else if (attempt == 2) {
					// 重试期间用户清空历史
					this.repository.deleteByConversationId(CID);
				}
			}
			finally {
				inHook.set(false);
			}
		};

		boolean written = this.repository.upsertRun(CID, A, inFlightHistory, WINDOW, true);

		assertThat(attempts.get()).as("前置：第一次 CAS 确实被并发写顶掉、走到了重试").isGreaterThanOrEqualTo(2);
		assertThat(written).as("清空之后的任何一次重试都不得落地").isFalse();
		assertThat(this.repository.findByConversationId(CID)).as("被清空的历史不得被重试写回来").isEmpty();
	}

	/**
	 * <b>整窗写回（压缩）与并发写者的交错</b>：压缩算完之后、落盘之前，别的 run 往窗口里写了一条新消息。
	 *
	 * <p>压缩的输入是<b>旧窗口</b>，因此"整窗覆盖 + CAS 重试"会在重试里把新旧内容一起盖掉 —— 新消息丢失。
	 * 正确口径：版本对不上就<b>放弃这一次压缩写</b>（不重试、不重算），新消息原样保留。
	 */
	@Test
	void replaceWindowAbandonsWhenAnotherWriterLandsAfterTheBaseline() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		MemoryWindowSnapshot baseline = this.repository.readWindow(CID);
		// 压缩期间：另一条 run 写下自己的成功轮
		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		boolean applied = this.repository.replaceWindow(CID, baseline,
				List.of(new UserMessage("压缩算出的旧内容"), new AssistantMessage("压缩算出的旧回答")));

		assertThat(applied).as("基线已被改写 ⇒ 放弃本次整窗写回（不重试、不覆盖）").isFalse();
		assertThat(texts(this.repository.findByConversationId(CID)))
			.as("并发写者的新消息不得被压缩的旧内容覆盖").containsExactly("A问", "A答", "B问", "B答");
	}

	/** 基线仍成立时整窗写回原样落地（L1 占位/L2 摘要/L3 裁轮都依赖它）。 */
	@Test
	void replaceWindowLandsTheWholeWindowWhenTheBaselineIsStillCurrent() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		MemoryWindowSnapshot baseline = this.repository.readWindow(CID);
		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);
		MemoryWindowSnapshot current = this.repository.readWindow(CID);

		boolean applied = this.repository.replaceWindow(CID, current,
				List.of(new UserMessage("[压缩摘要]"), new UserMessage("保留的问题")));

		assertThat(applied).isTrue();
		assertThat(texts(this.repository.findByConversationId(CID)))
			.as("整窗内容必须原样落盘（跨 run 改写不被按 run 合并跳过）")
			.containsExactly("[压缩摘要]", "保留的问题");
		assertThat(current.version()).isEqualTo(baseline.version() + 1);
	}

	/**
	 * <b>无归属旧消息不得被另一个 run 的收敛整段删掉</b>（迁移期/旧版本写下的消息没有 runId）。
	 *
	 * <p>替换写只带来本轮历史：窗口里那些不在本轮历史里的无归属消息若被当成"已经在历史里"丢弃，
	 * 旧会话内容就整段丢失。它们必须原样留在窗口最前，且不得被误归本轮。
	 */
	@Test
	void legacyMessagesWithoutRunIdSurviveAnotherRunsConvergence() {
		this.fake.lists.put(this.repository.keyFor(CID), new CopyOnWriteArrayList<>(this.codec
			.serializeAll(List.of(new UserMessage("旧会话问题"), new AssistantMessage("旧会话回答")))));

		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		List<Message> window = this.repository.findByConversationId(CID);
		assertThat(texts(window)).as("无归属旧消息必须保留").containsExactly("旧会话问题", "旧会话回答", "B问", "B答");
		assertThat(RunMemoryMerge.runIdOf(window.get(0))).as("保留的旧消息不得被误归本轮").isNull();
		assertThat(RunMemoryMerge.runIdOf(window.get(1))).isNull();
		assertThat(RunMemoryMerge.runIdOf(window.get(2))).isEqualTo(B.runId());
	}

	/** 本轮历史里带着同一批旧消息 ⇒ 窗口里只留一份（去重后由本轮块重新盖章）。 */
	@Test
	void legacyMessagesCarriedInTheRunHistoryAreNotDuplicated() {
		this.fake.lists.put(this.repository.keyFor(CID), new CopyOnWriteArrayList<>(this.codec
			.serializeAll(List.of(new UserMessage("旧会话问题"), new AssistantMessage("旧会话回答")))));
		List<Message> incoming = new ArrayList<>(this.repository.findByConversationId(CID));
		incoming.addAll(history("B问", "B答"));

		this.repository.upsertRun(CID, B, incoming, WINDOW, true);

		assertThat(texts(this.repository.findByConversationId(CID))).as("旧消息只能出现一次")
			.containsExactly("旧会话问题", "旧会话回答", "B问", "B答");
	}

	@Test
	void concurrentRunsMigrateTheSameLegacyWindowOnlyOnce() {
		this.fake.lists.put(this.repository.keyFor(CID), new CopyOnWriteArrayList<>(this.codec
			.serializeAll(List.of(new UserMessage("旧会话问题"), new AssistantMessage("旧会话回答")))));
		List<Message> aIncoming = new ArrayList<>(this.repository.findByConversationId(CID));
		aIncoming.addAll(history("A问", "A答"));
		List<Message> bIncoming = new ArrayList<>(this.repository.findByConversationId(CID));
		bIncoming.addAll(history("B问", "B答"));

		this.repository.upsertRun(CID, A, aIncoming, WINDOW, true);
		this.repository.upsertRun(CID, B, bIncoming, WINDOW, true);

		List<Message> window = this.repository.findByConversationId(CID);
		assertThat(texts(window)).as("stale incoming 不得把已被其他 run 收养的旧消息再盖一次")
			.containsExactly("旧会话问题", "旧会话回答", "A问", "A答", "B问", "B答");
		assertThat(RunMemoryMerge.runIdOf(window.get(0))).isEqualTo(A.runId());
		assertThat(RunMemoryMerge.runIdOf(window.get(1))).isEqualTo(A.runId());
		assertThat(RunMemoryMerge.runIdOf(window.get(4))).as("本轮新消息仍归属本轮").isEqualTo(B.runId());
		assertThat(RunMemoryMerge.runIdOf(window.get(5))).isEqualTo(B.runId());
	}

	/** 清空是"清历史"不是"封会话"：之后的新轮必须能重新写（tombstone 只让清空前的写者失效）。 */
	@Test
	void aNewRunCanWriteAgainAfterTheHistoryWasCleared() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		this.repository.deleteByConversationId(CID);

		assertThat(this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true)).isTrue();
		assertThat(texts(this.repository.findByConversationId(CID))).containsExactly("B问", "B答");
	}

	@Test
	void emptyRunConvergeRemovesOnlyItsOwnBlock() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		this.repository.upsertRun(CID, A, List.of(), WINDOW, true);

		assertThat(texts(this.repository.findByConversationId(CID))).as("空窗收敛不得清掉 B 的成功轮")
			.containsExactly("B问", "B答");
		assertThat(this.repository.hasLiveMemory(CID)).isTrue();
		assertThat(this.repository.isConversationIndexed(CID)).as("会话仍在索引里").isTrue();
	}

	@Test
	void emptyRunConvergeWithNoOtherBlocksDeletesTheSessionWindow() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		this.repository.upsertRun(CID, A, List.of(), WINDOW, true);

		assertThat(this.repository.hasLiveMemory(CID)).isFalse();
		assertThat(this.repository.isConversationIndexed(CID)).isFalse();
	}

	@Test
	void mergedWindowIsTrimmedToMaxMessagesWithoutSplittingToolPairs() {
		List<Message> aHistory = List.of(new UserMessage("A问"), toolCall("A1", "ca1"), toolResult("ca1", "R1"),
				toolCall("A2", "ca2"), toolResult("ca2", "R2"), AssistantMessage.builder().content("A答").build());
		List<Message> bHistory = List.of(new UserMessage("B问"), AssistantMessage.builder().content("B答").build());

		this.repository.upsertRun(CID, A, aHistory, 5, true);
		this.repository.upsertRun(CID, B, bHistory, 5, true);

		List<Message> window = this.repository.findByConversationId(CID);
		assertThat(window.size()).isLessThanOrEqualTo(5);
		assertThat(window.get(0).getMessageType()).as("窗口首条不得是孤立 TOOL").isNotEqualTo(MessageType.TOOL);
		assertThat(texts(window)).as("最近的 B 轮必须保留，A 轮允许被裁").endsWith("B问", "B答");
	}

	/**
	 * 多实例身份键：两个仓储实例（模拟两个进程）写同一身份同一会话必须落<b>同一个键</b>，
	 * 版本键与之同 hash tag（Cluster 同槽，脚本不跨槽）；另一个身份写的是另一份窗口，互不可见。
	 */
	@Test
	void instancesShareTheSameIdentityScopedWindowAndVersionKey() {
		this.fake.enableClusterMode();
		RedisChatMemoryRepository otherInstance = new RedisChatMemoryRepository(this.fake.redis, this.codec,
				Duration.ofHours(6));

		otherInstance.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		this.repository.upsertRun(CID, B, history("B问", "B答"), WINDOW, true);

		assertThat(this.repository.keyFor(CID)).isEqualTo("chat:mem:1:101:{" + CID + "}");
		assertThat(this.fake.lists).containsKey(this.repository.keyFor(CID));
		assertThat(this.fake.values).containsKey(VERSION_KEY);
		assertThat(FakeMemoryRedis.slot(VERSION_KEY))
			.as("版本键与数据键同 hash tag（Lua 不跨槽）")
			.isEqualTo(FakeMemoryRedis.slot(this.repository.keyFor(CID)));
		assertThat(FakeMemoryRedis.slot(GENERATION_KEY))
			.as("清空代次键同样与数据键同槽（MERGE_CAS 四键同槽）")
			.isEqualTo(FakeMemoryRedis.slot(this.repository.keyFor(CID)));
		assertThat(texts(this.repository.findByConversationId(CID))).containsExactly("A问", "A答", "B问", "B答");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 202L)) {
			new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(6)).upsertRun(CID, A,
					history("别人的问", "别人的答"), WINDOW, true);
		}
		assertThat(texts(this.repository.findByConversationId(CID))).as("另一身份写的是另一份窗口")
			.containsExactly("A问", "A答", "B问", "B答");
		assertThat(this.fake.sets.get(RedisChatMemoryRepository.CONVERSATION_IDS_KEY))
			.contains("1/101/" + CID, "2/202/" + CID);
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private static List<Message> history(String question, String answer) {
		return List.of(new UserMessage(question), AssistantMessage.builder().content(answer).build());
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

	private static Message toolCall(String text, String callId) {
		return AssistantMessage.builder()
			.content(text)
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "start_export", "{}")))
			.build();
	}

	private static Message toolResult(String callId, String text) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(callId, "start_export", text)))
			.build();
	}

}
