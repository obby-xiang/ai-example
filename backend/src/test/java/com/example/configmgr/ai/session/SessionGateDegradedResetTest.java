package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>降级标记必须跟着"锁已确认"回来</b>（RT-34 的反向）。
 *
 * <h2>缺陷形态</h2>
 * Redis 不可用时 {@code acquire} 按 §13.1 降级为进程内单值门，把 holder 置
 * {@code degraded = true}，并在之后的 409 里如实上报。但同轮重入（park 后
 * {@code ResumeService} 用同一 runId 重新取门）<b>脚本成功确认了锁</b>，旧实现只更新
 * token / 锁值，<b>不清 degraded</b>：
 * <ul>
 * <li>跨实例互斥其实已经恢复，对外却一直声称"降级"；</li>
 * <li>这个会话整段生命周期（直到释放）都带着假的降级事实，健康面与 409 的
 *     {@code degraded} 字段因此失真 —— 告警要么一直响，要么被训练成忽略。</li>
 * </ul>
 *
 * <p>本用例把"降级 → 重入确认"压成一条确定链路：先在脚本失败时取门（降级可见），
 * 再让脚本恢复并同 run 重入，断言降级标记被清掉。
 */
class SessionGateDegradedResetTest {

	private static final String SESSION = "s-degraded-reset";

	private static final String RUN_ID = "run-degraded-reset";

	private final SessionGateLeaseTokenTest.FakeSessionRedis fake = new SessionGateLeaseTokenTest.FakeSessionRedis();

	private final SessionGate gate = new SessionGate(this.fake.template, new AiProperties());

	@Test
	void aConfirmedReacquireClearsTheDegradedFlag() {
		this.fake.failScripts = true;
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-degraded"))
			.as("Redis 不可用 ⇒ 降级为进程内门（本轮照常开跑）")
			.isNull();
		assertThat(this.gate.degradedSessions()).as("降级事实必须可见").isEqualTo(1);

		this.fake.failScripts = false;
		assertThat(this.gate.acquire(SESSION, RUN_ID, "token-confirmed")).as("同 run 重入且锁已确认").isNull();
		assertThat(this.gate.degradedSessions()).as("锁已由脚本确认 ⇒ degraded 必须清掉（跨实例互斥已恢复）").isZero();

		assertThat(this.gate.release(SESSION, RUN_ID, "token-confirmed")).isTrue();
	}

}
