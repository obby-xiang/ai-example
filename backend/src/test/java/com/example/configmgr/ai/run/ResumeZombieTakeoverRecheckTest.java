package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>僵尸接管前的复核</b>（2026-10-29）：抢到租约 <b>不等于</b> 原驱动已死。
 *
 * <h2>被钉住的缺陷</h2>
 * 僵尸接管只做了一次判活（调用方 {@code StartupResumeRunner} 那一拍），随后
 * {@code resumeInternal} 看到 {@code forceTakeover=true + status=RUNNING} 就<b>无条件</b>接管：
 * <ul>
 * <li><b>同实例</b>：租约对同一 owner 是可重入的，首轮驱动线程持有的租约被"抢到"（
 *     {@code created=false}），接管照样发生 ⇒ 一轮两个驱动方；</li>
 * <li><b>跨实例</b>：判活与接管之间原驱动又出了业务帧（长思考后的第一段正文、慢工具的回执），
 *     接管照样发生 ⇒ 同一轮被跑第二遍。</li>
 * </ul>
 *
 * <h2>本批口径</h2>
 * 接管前必须<b>再复核一次</b>"驱动已失活"，两条证据任一为真即<b>不接管</b>（让给原驱动）：
 * <ol>
 * <li>本进程仍登记着这一轮的驱动方（{@code ResilientChatService#isDriving}）；</li>
 * <li>业务活动锚点此刻仍在僵尸窗口内（接管判定之后业务又前进了）。</li>
 * </ol>
 * 真僵尸（无驱动登记 + 活动仍超龄）照旧接管 —— 复核不得把接管能力整体关掉。
 */
class ResumeZombieTakeoverRecheckTest {

	private static final String RUN_ID = "run-zombie-recheck";

	private static final String SESSION_ID = "s-zombie-recheck";

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			mock(SpToolCallingManager.class), this.executor, mock(StringRedisTemplate.class), this.properties,
			this.cancellations, mock(ToolResultLimiter.class));

	@BeforeEach
	void setUp() {
		when(this.cancellations.isCancelled(any())).thenReturn(false);
		when(this.cancellations.cancelState(any())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		// 续跑驱动同步跑完（断言的是"有没有接管"，不是线程时序）
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));
	}

	/**
	 * 同实例：首轮 {@code chat()} 仍在驱动（进程内有登记）⇒ 即使 {@code forceTakeover=true}、
	 * 即使抢租成功（可重入），也不得接管。
	 */
	@Test
	void zombieTakeoverIsRefusedWhileAnInProcessDriverIsStillRegistered() {
		seedStaleRunning();
		when(this.chatService.isDriving(RUN_ID)).thenReturn(true);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, true);

		assertThat(result.outcome()).as("驱动还活着 ⇒ 这一轮是 ALREADY_RUNNING，不是可接管的僵尸")
			.isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
		assertThat(result.body()).containsEntry("resumed", false)
			.containsEntry("reason", "DRIVER_ALIVE_IN_PROCESS");
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any());
		verify(this.chatService, never()).driveResumed(any(), any(), any(), any(), any(), any(), any());
		verify(this.executor, never()).execute(any(Runnable.class));
		assertThat(this.store.get(RUN_ID).getStatus()).as("不接管 ⇒ 不得改状态").isEqualTo(RunSnapshot.RUNNING);
	}

	/**
	 * 跨实例：接管判定之后业务活动又前进（原驱动还在出帧）⇒ 复核驳回接管。
	 *
	 * <p>判据是"业务活动锚点"，不是心跳：心跳只能说明进程活着，卡死的轮也会心跳。
	 */
	@Test
	void zombieTakeoverIsRefusedWhenBusinessActivityMovedAfterTheDecision() {
		seedStaleRunning();
		when(this.chatService.isDriving(RUN_ID)).thenReturn(false);
		// 判活之后、接管之前，原驱动又出了一条业务帧
		this.store.touchBusinessActivity(RUN_ID, System.currentTimeMillis());

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, true);

		assertThat(result.outcome()).as("业务仍在前进 ⇒ 不是僵尸，拒绝接管").isEqualTo(ResumeService.Outcome.ALREADY_RUNNING);
		assertThat(result.body()).containsEntry("reason", "ACTIVITY_ADVANCED");
		verify(this.executor, never()).execute(any(Runnable.class));
	}

	/** 真僵尸（无驱动登记 + 业务活动仍超龄）：复核不得把接管能力整体关掉。 */
	@Test
	void aTrulyStaleRoundWithNoLiveDriverIsStillTakenOver() {
		seedStaleRunning();
		when(this.chatService.isDriving(RUN_ID)).thenReturn(false);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID, true);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertThat(result.body()).containsEntry("zombieTakeover", true);
		verify(this.executor).execute(any(Runnable.class));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/** RUNNING 且业务活动已超龄（= 判活方那一拍看到的现场）。 */
	private void seedStaleRunning() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("问题"))));
		this.store.save(snapshot);
		// save 会把 updatedAtMs 刷成"现在"：超龄现场只能在落库之后铺（本替身返回同一对象引用）
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - Duration.ofMinutes(10).toMillis());
	}

}
