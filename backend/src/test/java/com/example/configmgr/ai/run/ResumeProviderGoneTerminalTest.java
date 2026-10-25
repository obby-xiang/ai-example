package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>B1（run 40182796512）：续跑硬否定必须收敛成终态 + 机器可读终帧</b>。
 *
 * <h2>缺陷形态（实测）</h2>
 * 快照绑定的 provider 被判定不可用（{@code PROVIDER_GONE}）时，旧实现只构造 409 语义的 body：
 * 快照仍是 {@code SUSPENDED}、归档里没有任何终帧。客户端随后重挂到这条"已决但无驱动方"的轮上，
 * 服务端用 {@code NO_TRANSPORT_DEADLINE} 的 emitter 把连接挂住 —— 实测 120s 后由客户端自己超时。
 *
 * <p>
 * {@link ProviderGoneException} 的类注早已写明红线：确定不可用必须落成 <b>FAILED 终态 + 机器可读码</b>，
 * 而不是"只发一帧 error 就把快照留在非终态"（这里连帧都没有，更糟）。
 *
 * <h2>本文件锁什么</h2>
 * <ul>
 * <li>确定不可用 ⇒ 快照 {@code FAILED} + {@code error} 带 {@code PROVIDER_GONE} 前缀 +
 *     归档末帧是 {@code type=error, code=AI_PROVIDER_GONE}（重挂回放它之后连接收流）；</li>
 * <li><b>不可知</b>（复核 Critical-1）：配置刷新失败（{@code ReloadResult.ok=false} / reload 抛错）或
 *     "行在但 key 此刻解不开" ⇒ {@code STATE_UNKNOWN}（503 语义），快照保持 {@code SUSPENDED}、
 *     <b>不写终态、不出业务帧</b>；</li>
 * <li>写权限只读（丢租/不可确认）⇒ <b>不</b>写终态、<b>不</b>出终帧（"不知道"不是"确定没了"，
 *     不能把这一轮判死，也不能发业务帧冒充权威）；</li>
 * <li>仍是终态幂等：已经是终态的轮不重复写、不重复出帧。</li>
 * </ul>
 *
 * <h2>证红方式</h2>
 * 去掉 {@code providerGone(...)} 里的收敛调用 ⇒ 断言"快照 FAILED / 末帧 error"的用例红；
 * 把 {@code resolveForResume} 退回"刷新失败也当空解析"（B2 第一版）⇒ 三条 {@code STATE_UNKNOWN} 用例红
 * （会把一次读库抖动写成永久 {@code FAILED}）。
 */
class ResumeProviderGoneTerminalTest {

	private static final String RUN_ID = "run-provider-gone-terminal";

	private static final String SESSION_ID = "s-provider-gone-terminal";

	private static final Long PROVIDER_ID = 7L;

	private static final String MODEL_ID = "deepseek-flash";

	private final AuthoritySwitchingStore store = new AuthoritySwitchingStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiProperties properties = new AiProperties();

	private ResumeService service;

	@BeforeEach
	void setUp() {
		ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
		org.mockito.Mockito.doAnswer(invocation -> {
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				mock(SpToolCallingManager.class), executor, mock(StringRedisTemplate.class), this.properties,
				mock(CancellationRegistry.class), mock(ToolResultLimiter.class));
		ReflectionTestUtils.setField(this.service, "aiConfigService", this.configService);
		// 硬否定：库里真的没有这一行（强制重载<b>成功</b>之后仍为空，且行级声明也没有）
		when(this.configService.resolveChatTarget(any(Long.class), anyString())).thenReturn(Optional.empty());
		when(this.configService.reloadWithOutcome())
			.thenReturn(new AiConfigService.ReloadResult(AiConfigService.ConfigSnapshot.empty(), true));
		when(this.configService.bindingState(any(Long.class), anyString()))
			.thenReturn(AiConfigService.BindingState.ABSENT);
	}

	@Test
	void missingAfterSuccessfulReloadConvergesTerminal() {
		seedSuspendedSnapshot();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		// 前置（判死的前提）：刷新**成功**了，且刷新后行级判据是 ABSENT（不是"读库没读到"）
		verify(this.configService).reloadWithOutcome();
		verify(this.configService).bindingState(PROVIDER_ID, MODEL_ID);
		assertThat(result.outcome()).as("409 语义不变（调用方仍能拿到明确原因）")
			.isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("确定不可用必须落 FAILED，不能停在 SUSPENDED 等超时")
			.isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).as("快照里的机器可读前缀").startsWith("PROVIDER_GONE");
		assertThat(snapshot.getInFlightToolCalls()).as("不再有挂起中的工具").isEmpty();

		Map<String, Object> last = lastArchivedFrame();
		assertThat(last).as("必须写终帧：否则重挂只会等一条不会再有帧的连接").isNotNull();
		assertThat(last.get("type")).as("终帧类型").isEqualTo("error");
		assertThat(last.get("code")).as("机器可读终态码").isEqualTo(ErrorCodes.AI_PROVIDER_GONE);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(), any());
	}

	// ── 复核 Critical-1：刷新失败 / key 不可用 = 不可知，不得判死 ────────────────

	/**
	 * {@code reload()} 在读库故障时<b>不抛</b>（{@code load()} 自己吞了，返回 {@code ok=false}）。
	 * 这种"刷新没刷新到"绝不能被当成"端点没了"。
	 */
	@Test
	void reloadFailureCannotBecomeProviderGone() {
		seedSuspendedSnapshot();
		when(this.configService.reloadWithOutcome())
			.thenReturn(new AiConfigService.ReloadResult(AiConfigService.ConfigSnapshot.empty(), false));

		assertStateUnknownWithoutConvergence();
	}

	/** 极端情形（reload 真抛）：同样是不可知，不能判死。 */
	@Test
	void throwingReloadIsStateUnknownNotProviderGone() {
		seedSuspendedSnapshot();
		when(this.configService.reloadWithOutcome()).thenThrow(new IllegalStateException("Hikari 获取连接超时"));

		assertStateUnknownWithoutConvergence();
	}

	/**
	 * 行在、模型在，只是 key 此刻解不开（KEK 缺失/轮换/密文损坏）：{@link AiConfigService#resolveChatTarget}
	 * 同样返回空，但这不是"端点被删"。KEK 修好这一轮就能续 —— 不得判死。
	 */
	@Test
	void keyFailureCannotBecomeProviderGone() {
		seedSuspendedSnapshot();
		when(this.configService.bindingState(PROVIDER_ID, MODEL_ID))
			.thenReturn(AiConfigService.BindingState.KEY_UNREADABLE);

		assertStateUnknownWithoutConvergence();
	}

	private void assertStateUnknownWithoutConvergence() {
		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("不可知 ⇒ 503 语义（STATE_UNKNOWN），不是 409 PROVIDER_GONE")
			.isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		assertThat(result.body()).containsEntry("reason", "STATE_UNKNOWN");
		assertThat(this.store.get(RUN_ID).getStatus()).as("快照必须保持 SUSPENDED（可重试）")
			.isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(frames()).as("不可知 ⇒ 不得写任何业务帧（终帧就是判死）").isEmpty();
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any(), any(), any());
	}

	@Test
	void convergenceIsSkippedWhenTheLeaseAuthorityIsUnreadable() {
		// "不知道"不是"确定没了"：丢租/设施不可确认时既不能判死，也不能发业务帧冒充权威。
		seedSuspendedSnapshot();
		this.store.refuseWrites(LeaseService.LeaseAuthority.UNAVAILABLE);

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.PROVIDER_GONE);
		assertThat(this.store.get(RUN_ID).getStatus())
			.as("写权限不可确认 ⇒ 不得把这一轮判死（留给真正的持有者）").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(frames()).as("写权限不可确认 ⇒ 不得写任何业务帧").isEmpty();
	}

	@Test
	void alreadyTerminalRunIsNotRewrittenOnASecondAttempt() {
		seedSuspendedSnapshot();
		RunSnapshot done = this.store.get(RUN_ID);
		done.setStatus(RunSnapshot.FAILED);
		done.setError("PROVIDER_GONE：先前的失败");
		this.store.save(done);

		this.service.resume(RUN_ID);

		RunSnapshot after = this.store.get(RUN_ID);
		assertThat(after.getError()).as("已是终态 ⇒ 不覆盖既有 error").isEqualTo("PROVIDER_GONE：先前的失败");
		assertThat(frames()).as("已是终态 ⇒ 不重复出帧").isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private void seedSuspendedSnapshot() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setProviderId(PROVIDER_ID);
		snapshot.setModelId(MODEL_ID);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("你好"))));
		this.store.save(snapshot);
	}

	private List<Map<String, Object>> frames() {
		return this.store.events(RUN_ID);
	}

	private Map<String, Object> lastArchivedFrame() {
		List<Map<String, Object>> archived = frames();
		return archived.isEmpty() ? null : archived.get(archived.size() - 1);
	}

	/**
	 * 可切换写权限的进程内替身：铺数据时按默认（{@code NEVER_HELD}）放行，
	 * 需要模拟"租约判定不可读"时切成 {@code UNAVAILABLE}（真实 {@link RunStore#save} 同口径拒写）。
	 */
	private static final class AuthoritySwitchingStore extends InMemoryRunStore {

		private volatile LeaseService.LeaseAuthority forced;

		void refuseWrites(LeaseService.LeaseAuthority authority) {
			this.forced = authority;
		}

		@Override
		public LeaseService.LeaseAuthority runLeaseAuthority(String runId) {
			LeaseService.LeaseAuthority forcedAuthority = this.forced;
			return forcedAuthority == null ? super.runLeaseAuthority(runId) : forcedAuthority;
		}

	}

}
