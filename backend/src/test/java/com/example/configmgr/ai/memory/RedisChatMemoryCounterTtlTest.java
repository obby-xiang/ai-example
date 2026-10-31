package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话级 CAS 计数器的 <b>TTL 必须跟着数据键滑动</b>（`:ver` / `:gen`）。
 *
 * <h2>被钉住的缺口</h2>
 * 清空与合并写会给两个计数器设 TTL，但 <b>{@code saveAll}</b>、<b>合并写的等值早退</b>、以及
 * <b>读快照</b>这几条路径不碰计数器 —— 计数器就可能先于数据键过期：`:gen` 归 0 后，一个"写回期间
 * 并没有被清空"的写者会拿到 {@code -2}，被<b>误判成清空</b>而放弃写回（历史静默不落盘）。
 *
 * <p>口径：<b>凡是数据键被续期/续写的地方，两个计数器同 TTL 一起续</b>（EXPIRE 不存在的键是空操作，
 * 不会凭空造键）。计数器允许在会话销毁后随 TTL 残留 —— 但不得影响"删净"判定（{@code hasLiveMemory}
 * / 索引复核只看数据键与索引）。
 */
class RedisChatMemoryCounterTtlTest {

	private static final String CID = "s-counter-ttl";

	private static final String VERSION_KEY = "chat:mem:1:101:{" + CID + "}:ver";

	private static final String GENERATION_KEY = "chat:mem:1:101:{" + CID + "}:gen";

	private static final Duration TTL = Duration.ofHours(6);

	private static final int WINDOW = 40;

	private static final MemoryTurn A = new MemoryTurn("run-a", 1_000L);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			TTL);

	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void tearDown() {
		this.identity.close();
	}

	/** 整窗写（saveAll）也要把两个计数器续到同一 TTL。 */
	@Test
	void saveAllSlidesBothCasCountersWithTheDataKey() {
		this.repository.deleteByConversationId(CID); // 清空造出计数器（带 TTL）
		this.fake.expiries.clear(); // 模拟"计数器 TTL 已到、数据键还要继续用"的前一刻

		this.repository.saveAll(CID, history("A问", "A答"));

		assertThat(this.fake.expiry(VERSION_KEY)).as("版本键必须随数据键一起续 TTL").isEqualTo(TTL);
		assertThat(this.fake.expiry(GENERATION_KEY)).as("代次键必须随数据键一起续 TTL").isEqualTo(TTL);
	}

	/** 合并写的等值早退（窗口没变、不落盘）同样不能把计数器留在"先过期"的状态。 */
	@Test
	void aNoOpMergeWriteAlsoSlidesTheCounters() {
		List<Message> same = history("A问", "A答");
		this.repository.upsertRun(CID, A, same, WINDOW, true);
		this.fake.expiries.clear();
		List<Message> unchanged = this.repository.findByConversationId(CID);
		int casBefore = this.fake.casAttempts.get();

		this.repository.upsertRun(CID, A, unchanged, WINDOW, true); // 窗口没变 ⇒ 等值早退，不写、不推进版本

		assertThat(this.fake.casAttempts.get()).as("前置：确实是等值早退（没有发 CAS）").isEqualTo(casBefore);
		assertThat(this.fake.expiry(VERSION_KEY)).isEqualTo(TTL);
		assertThat(this.fake.expiry(GENERATION_KEY)).isEqualTo(TTL);
	}

	/** 读快照（压缩写回的 CAS 基线）也要续：基线读出来之后计数器不能中途过期。 */
	@Test
	void readingTheWindowSnapshotAlsoSlidesTheCounters() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);
		this.fake.expiries.clear();

		this.repository.readWindow(CID);

		assertThat(this.fake.expiry(VERSION_KEY)).isEqualTo(TTL);
		assertThat(this.fake.expiry(GENERATION_KEY)).isEqualTo(TTL);
	}

	/**
	 * 读快照拿到的基线在"另一写者做了等值早退"之后仍然成立：不得因为计数器 TTL 问题被误判成
	 * "写回期间被清空"（{@code -2} ⇒ 压缩写回静默放弃）。
	 */
	@Test
	void aBaselineCapturedBeforeANoOpWriteIsNotMistakenForAClear() {
		List<Message> same = history("A问", "A答");
		this.repository.upsertRun(CID, A, same, WINDOW, true);
		MemoryWindowSnapshot baseline = this.repository.readWindow(CID);
		int casBefore = this.fake.casAttempts.get();
		// 另一写者写的是"同一份窗口"⇒ 等值早退：版本与代次都不动，基线仍成立
		this.repository.upsertRun(CID, A, this.repository.findByConversationId(CID), WINDOW, true);
		assertThat(this.fake.casAttempts.get()).as("前置：另一写者确实是等值早退").isEqualTo(casBefore);
		this.fake.expiries.clear();

		boolean applied = this.repository.replaceWindow(CID, baseline,
				List.of(new UserMessage("压缩后的窗口"), new AssistantMessage("压缩后的回答")));

		assertThat(applied).as("代次没变 ⇒ 不得误判成清空").isTrue();
		assertThat(this.repository.findByConversationId(CID)).extracting(Message::getText)
			.containsExactly("压缩后的窗口", "压缩后的回答");
	}

	/** 销毁：计数器允许随 TTL 残留，但"删净"判定与索引复核不受影响。 */
	@Test
	void clearKeepsTheCountersWithTtlWithoutBreakingTheDeletedVerdict() {
		this.repository.upsertRun(CID, A, history("A问", "A答"), WINDOW, true);

		this.repository.deleteByConversationId(CID);

		assertThat(this.fake.expiry(VERSION_KEY)).isEqualTo(TTL);
		assertThat(this.fake.expiry(GENERATION_KEY)).isEqualTo(TTL);
		assertThat(this.repository.findByConversationId(CID)).as("历史已删净").isEmpty();
		assertThat(this.repository.hasLiveMemory(CID)).isFalse();
		assertThat(this.repository.isConversationIndexed(CID)).isFalse();
	}

	private static List<Message> history(String question, String answer) {
		return List.of(new UserMessage(question), AssistantMessage.builder().content(answer).build());
	}

}
