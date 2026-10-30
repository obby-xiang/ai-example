package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * park 之后 {@code GET /api/ai/events/{runId}} 的<b>收流判据</b>（E2E 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * park 分支曾对<b>所有</b> SUSPENDED 轮在回放后无条件 {@code completeSubscriptions()}。E2E 的标准
 * 时序是「POST /confirm 或 /frontend-tool-result 得到 200 → 立刻 GET /events?lastSeq=N 收终帧」：
 * 此刻决策已落库、续跑已派发，但快照<b>仍是 SUSPENDED</b>（ResumeService 还没把它翻成 RUNNING），
 * 于是连接被当场收掉，续跑几十毫秒后产出的 {@code done} 永远到不了这条连接 ——
 * GF4 / GF5 / W-01..W-22 / H-10 / S-11 / T-13 / TC23⑥ 的"done 缺失"由此而来。
 *
 * <h2>本批口径</h2>
 * <ul>
 * <li>还有 {@code PENDING} 条目 ⇒ 仍在等人：回放完即收流（park 的本意，不悬挂连接）；</li>
 * <li>条目已全部结算 ⇒ 续跑在即：<b>保持连接</b>，让终帧到达（客户端无需再猜"要不要重挂"），
 *     并做一次"已决但无驱动方"的自愈对账（{@code triggerResumeForParked}，与死卡兜底同一判定、幂等）。</li>
 * </ul>
 *
 * <p>证红：把 {@code events} 的收流判据改回"只看 status==SUSPENDED" ⇒ 第一个用例红（subscriberCount=0）；
 * 反过来把收流整段删掉 ⇒ 第二个用例红（等人期间仍占着连接）。第三个用例钉的是自愈对账的<b>错误路径</b>：
 * 对账（{@code RunStore.get}）抛 {@code AiStateUnavailableException} 时，订阅已经由 {@code replayAndAttach}
 * 挂上、emitter 却还没交给容器 —— 异常上抛即成 500 且清理回调永不触发，订阅留到进程结束。
 * 去掉 {@code events} 里的就地降级 ⇒ 该用例红（异常从 {@code events} 传出）。
 */
class AiControllerEventsParkCloseTest {

	private static final String RUN_ID = "run-park-close";

	private static final String SESSION_ID = "s-park-close";

	private static final String CALL_ID = "call-park-close-1";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ConfirmGate confirmGate = mock(ConfirmGate.class);

	private AiController controller;

	private RequestContextUtils.SafeCloseable identityScope;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		// 重挂是 HTTP 数据面入口：请求线程必须有身份（无上下文时归属复核 fail-closed）
		this.identityScope = RequestContextUtils.useContext(1L, 101L);
		this.controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				this.confirmGate, this.store, this.registry, mock(CancellationRegistry.class),
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, this.properties,
				mock(Environment.class), mock(JobWatchdog.class));
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		// 替身的 seedRun 不读线程身份：显式钉上记录身份，与"HTTP 面必然带身份"同形
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		if (this.identityScope != null) {
			this.identityScope.close();
		}
	}

	@Test
	void settledParkedRunKeepsTheReattachedStreamOpenForTheResumedTerminalFrames() {
		// 决策/回灌已经落库（resumeOutcome=SCHEDULED），快照此刻仍是 SUSPENDED
		this.store.putPending(RUN_ID, pending(PendingToolCall.FRONTEND_RESULT));
		SseEmitter attached = new SseEmitter(-1L);
		this.registry.of(RUN_ID).attach(attached);

		this.controller.events(RUN_ID, null);

		assertThat(this.registry.of(RUN_ID).subscriberCount())
			.as("条目已结算 ⇒ 续跑在即：调用方连接 + 控制器新建连接都必须保留（否则续跑的 done 丢失）")
			.isEqualTo(2);
		verify(this.confirmGate).triggerResumeForParked(RUN_ID);
	}

	@Test
	void stillPendingParkedRunClosesTheReattachedStreamAfterReplay() {
		this.store.putPending(RUN_ID, pending(PendingToolCall.PENDING));
		SseEmitter attached = new SseEmitter(-1L);
		this.registry.of(RUN_ID).attach(attached);

		this.controller.events(RUN_ID, null);

		assertThat(this.registry.of(RUN_ID).subscriberCount())
			.as("仍在等人 ⇒ 回放后收流，不悬挂连接（park 的本意）")
			.isZero();
		verify(this.confirmGate, never()).triggerResumeForParked(anyString());
	}

	@Test
	void selfHealFailureAfterAttachClosesTheStreamInsteadOfLeakingTheSubscriber() {
		// 条目已结算 ⇒ 走"已决但无驱动方"的自愈对账；对账要读快照，
		// RunStore.get 在 Redis 故障时抛 AiStateUnavailableException（此处由 ConfirmGate 复现）。
		this.store.putPending(RUN_ID, pending(PendingToolCall.FRONTEND_RESULT));
		SseEmitter attached = new SseEmitter(-1L);
		this.registry.of(RUN_ID).attach(attached);
		when(this.confirmGate.triggerResumeForParked(RUN_ID))
			.thenThrow(new AiStateUnavailableException("Redis 不可用：快照读失败"));

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value())
			.as("对账异常时订阅已挂上（replayAndAttach 在前）：抛出即成 500，emitter 不交给容器、清理回调永不触发")
			.isEqualTo(200);
		assertThat(response.getBody()).isInstanceOf(SseEmitter.class);
		verify(this.confirmGate).triggerResumeForParked(RUN_ID);
		assertThat(this.registry.of(RUN_ID).subscriberCount())
			.as("对账失败 ⇒ 就地收流：订阅不得留在表里（否则 hasLiveSubscriber 常真、recycleIfIdle 永不放行，"
					+ "唤醒/回放继续往死订阅排队）")
			.isZero();
	}

	private static PendingToolCall pending(String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("navigate_to");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setStatus(status);
		return pending;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
