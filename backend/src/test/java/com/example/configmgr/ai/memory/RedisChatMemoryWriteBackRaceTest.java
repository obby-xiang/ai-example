package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;

import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * <b>销毁与记忆写回的交错窗口：入口守卫通过后落地的 RENAME 不得让会话复活</b>。
 *
 * <h2>缺陷形态</h2>
 * {@code saveAll} 在入口问一次销毁守卫，然后在 staging 上写整窗并 {@code RENAME} 到 final。
 * 销毁流程在"入口检查通过"与"RENAME 落地"之间装守卫并删过记忆键时，这一笔 RENAME 会把
 * <b>用户刚销毁的会话历史整窗写回来</b>：销毁侧已报 {@code complete=true}，数据却在几毫秒后复活，
 * 而且索引的 SREM 也已经做完（{@code isConversationIndexed} 看不见这份残留）。
 *
 * <h2>本批口径</h2>
 * RENAME 成功后<b>复检</b>守卫：命中即撤销本次写回（删掉刚写出的键、不写索引），
 * 复检读失败按"已装守卫"处理（fail-closed：宁可本次写回作废，也不能在守卫状态不可知时复活数据）。
 * 入口检查保留 —— 它在写之前就挡掉绝大部分写回，复检只兜住交叉窗口。
 */
class RedisChatMemoryWriteBackRaceTest {

	private static final String CID = "s-race";

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RacingGuard guard = new RacingGuard();

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(6), this.guard);

	@Test
	void renameThatLandsAfterThePurgeArmedTheGuardIsUndone() {
		// 销毁流程在"入口检查通过"之后、RENAME 之前装守卫并删过一次记忆键。
		this.fake.afterStageWrite = () -> this.guard.armed = true;

		this.repository.saveAll(CID, List.of(new UserMessage("用户刚销毁的会话")));

		assertFalse(this.fake.hasKey(this.repository.keyFor(CID)),
				"RENAME 落地后必须复检守卫并撤销写回（销毁后不得复活）");
		assertTrue(this.fake.set(RedisChatMemoryRepository.CONVERSATION_IDS_KEY).isEmpty(),
				"被撤销的写回不得把自己加回索引（否则索引指向一份已删除的历史）");
	}

	@Test
	void guardReadFailureAfterRenameIsFailClosed() {
		// 复检拿不到守卫状态（Redis 抖动）：不得把"不知道"当"没装守卫"。
		this.fake.afterStageWrite = () -> this.guard.throwOnRead = true;

		this.repository.saveAll(CID, List.of(new UserMessage("守卫不可知")));

		assertFalse(this.fake.hasKey(this.repository.keyFor(CID)), "守卫状态不可知 ⇒ 撤销本次写回（fail-closed）");
	}

	/**
	 * <b>I2（review）：RENAME 服务端已生效但回包丢失时，复检也必须执行</b>。
	 *
	 * <p>旧实现只在 {@code rename()} 正常返回后复检：Lettuce 超时/断连会让异常直接穿出 try，
	 * final 键其实已经落地，而复检被整个跳过 —— 销毁已 {@code complete=true}，历史却还在。
	 * 修复后复检在 {@code finally}：异常照旧向上抛（不吞错误），但已落地的键被撤销。
	 */
	@Test
	void lostRenameReplyStillTriggersTheRecheckAndUndo() {
		this.fake.afterStageWrite = () -> this.guard.armed = true;
		this.fake.loseRenameReply = true;

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> this.repository.saveAll(CID, List.of(new UserMessage("回包丢失的写回"))));

		assertTrue(thrown.getMessage().contains("回包丢失"), "错误必须照旧抛给调用方（不吞）");
		assertFalse(this.fake.hasKey(this.repository.keyFor(CID)),
				"服务端已生效的落地必须被 finally 里的复检撤销（销毁后不得复活）");
		assertTrue(this.fake.set(RedisChatMemoryRepository.CONVERSATION_IDS_KEY).isEmpty(),
				"被撤销的写回不得进索引");
	}

	/**
	 * <b>fix4 前提（确定性证据，不依赖 sleep/线程运气）：RENAME 已生效、写者在 finally 复检完成前
	 * crash ⇒ 整窗历史留在最终键上，且没有任何"写侧"补偿</b>。
	 *
	 * <p>用 {@link OutOfMemoryError} 从复检点逃出（{@code saveAll} 的 {@code else} 分支只 catch
	 * {@code RuntimeException}）精确复刻"finally 没能完成复检"的窗口：JVM 死/线程被 Error 打断时，
	 * 进程里不会再有第二段代码去撤销这一笔 —— 这正是 fix4 要用"complete 后的一次有界记忆复核"
	 * 补偿的时序边界（写侧本身无法闭合）。
	 */
	@Test
	void crashBeforeTheFinallyRecheckLeavesResidueThatNoWriterWillUndo() {
		// 入口检查必须通过（写者是在销毁装守卫之前进来的）；崩溃只发生在"staging 之后、复检之前"，
		// 所以在 afterStageWrite 这一拍同时把守卫置为 armed 并让复检点直接"进程死"。
		this.fake.afterStageWrite = () -> {
			this.guard.armed = true;
			this.guard.crashOnRead = true;
		};

		assertThrows(OutOfMemoryError.class,
				() -> this.repository.saveAll(CID, List.of(new UserMessage("崩溃前写回"))));

		assertTrue(this.fake.hasKey(this.repository.keyFor(CID)),
				"RENAME 已生效、复检未完成 ⇒ 残留存在（fix4 的有界复核要清的正是它）");
		assertTrue(this.fake.set(RedisChatMemoryRepository.CONVERSATION_IDS_KEY).isEmpty(),
				"崩溃发生在写索引之前：索引里没有这条残留");
	}

	@Test
	void guardArmedBeforeTheWriteStillBlocksAtTheEntryCheck() {
		this.guard.armed = true;

		this.repository.saveAll(CID, List.of(new UserMessage("销毁后写回")));

		assertFalse(this.fake.hasKey(this.repository.keyFor(CID)), "入口检查照旧拒绝");
		assertEquals(0, this.fake.stageWrites.get(), "拒绝发生在任何 Redis 写之前");
	}

	@Test
	void normalWriteBackWithoutAPurgeIsUnaffected() {
		this.repository.saveAll(CID, List.of(new UserMessage("正常收敛")));

		assertFalse(this.fake.list(this.repository.keyFor(CID)).isEmpty(), "没有守卫时写回照常");
		assertTrue(this.fake.set(RedisChatMemoryRepository.CONVERSATION_IDS_KEY).contains(CID), "索引照常更新");
	}

	/** 可编程的销毁守卫：只替换 {@code isArmed}（Redis 侧不参与本用例）。 */
	private static final class RacingGuard extends SessionPurgeGuard {

		volatile boolean armed;

		volatile boolean throwOnRead;

		/** 置 true = 复检时"进程死了"（Error 逃出 finally，模拟 JVM 崩溃/线程被打断）。 */
		volatile boolean crashOnRead;

		RacingGuard() {
			super(mock(StringRedisTemplate.class));
		}

		@Override
		public boolean isArmed(String sessionId) {
			if (this.crashOnRead) {
				throw new OutOfMemoryError("模拟 JVM 崩溃（复检前进程死）");
			}
			if (this.throwOnRead) {
				throw new com.example.configmgr.common.AiStateUnavailableException("守卫读失败（模拟）");
			}
			return this.armed;
		}

	}

}
