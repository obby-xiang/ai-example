package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要压缩熔断（上下文专项批③；设计稿 §3.5 / 用例 C-27..C-30）。
 *
 * <p>
 * 熔断只被"<b>摘要尝试</b>"驱动（L2/L3），L1 不受影响 —— 可用性优先：摘要坏了就只清工具结果，
 * 对话照常。计数成功清零、达阈值打开、到点半开一次、失败重新开窗（不升指数，避免长尾锁死）。
 */
class CompactionFuseTest {

	private static final long T0 = 1_700_000_000_000L;

	private static final long COOLDOWN_MS = Duration.ofMinutes(10).toMillis();

	private final AiProperties properties = new AiProperties();

	private final CompactionFuse fuse = new CompactionFuse(this.properties.getContext());

	/** C-27：连续 3 次失败 ⇒ 第 3 次即 open，冷却窗 = now + 10min；窗口内不允许摘要。 */
	@Test
	void opensAfterThreeConsecutiveFailures() {
		ContextState state = ContextState.EMPTY;
		for (int i = 1; i <= 2; i++) {
			state = this.fuse.onFailure(state, T0);
			assertThat(state.circuit()).isEqualTo(CompactionFuse.CLOSED);
			assertThat(state.fuseFails()).isEqualTo(i);
			assertThat(this.fuse.allowsSummary(state, T0)).isTrue();
		}

		ContextState opened = this.fuse.onFailure(state, T0);

		assertThat(opened.circuit()).isEqualTo(CompactionFuse.OPEN);
		assertThat(opened.fuseFails()).isEqualTo(3);
		assertThat(opened.openUntilMs()).isEqualTo(T0 + COOLDOWN_MS);
		assertThat(this.fuse.allowsSummary(opened, T0 + 1)).isFalse();
	}

	/** C-28：失败 2 → 成功 → 失败 2 仍不熔断（成功必须清零）。 */
	@Test
	void successResetsTheFailureCount() {
		ContextState state = this.fuse.onFailure(this.fuse.onFailure(ContextState.EMPTY, T0), T0);
		assertThat(state.fuseFails()).isEqualTo(2);

		ContextState closed = this.fuse.onSuccess(state, T0);
		assertThat(closed.fuseFails()).isZero();
		assertThat(closed.circuit()).isEqualTo(CompactionFuse.CLOSED);
		assertThat(closed.openUntilMs()).isZero();

		ContextState after = this.fuse.onFailure(this.fuse.onFailure(closed, T0), T0);
		assertThat(after.circuit()).isEqualTo(CompactionFuse.CLOSED);
		assertThat(after.fuseFails()).isEqualTo(2);
		assertThat(this.fuse.allowsSummary(after, T0)).isTrue();
	}

	/** C-29：冷却到点放一次（半开）；再失败 ⇒ 重新开一个新冷却窗。 */
	@Test
	void halfOpensOnceAfterCooldownThenReopensOnFailure() {
		ContextState opened = ContextState.EMPTY;
		for (int i = 0; i < 3; i++) {
			opened = this.fuse.onFailure(opened, T0);
		}
		assertThat(opened.circuit()).isEqualTo(CompactionFuse.OPEN);

		long afterCooldown = T0 + COOLDOWN_MS;
		assertThat(this.fuse.allowsSummary(opened, afterCooldown)).as("到点半开：放一次").isTrue();

		ContextState halfOpen = this.fuse.halfOpen(opened, afterCooldown);
		assertThat(halfOpen.circuit()).isEqualTo(CompactionFuse.HALF_OPEN);

		ContextState reopened = this.fuse.onFailure(halfOpen, afterCooldown);
		assertThat(reopened.circuit()).isEqualTo(CompactionFuse.OPEN);
		assertThat(reopened.openUntilMs()).isEqualTo(afterCooldown + COOLDOWN_MS);
		assertThat(this.fuse.allowsSummary(reopened, afterCooldown + 1)).isFalse();
	}

	/** 半开成功 ⇒ 闭合清零（与闭合态成功同一效果）。 */
	@Test
	void halfOpenSuccessClosesTheCircuit() {
		ContextState open = ContextState.EMPTY.withFuse(CompactionFuse.HALF_OPEN, 3, 0L);

		ContextState closed = this.fuse.onSuccess(open, T0);

		assertThat(closed.circuit()).isEqualTo(CompactionFuse.CLOSED);
		assertThat(closed.fuseFails()).isZero();
	}

	/**
	 * 双实例可达（DC-11 边界，<b>本用例单 JVM 可跑</b>，不依赖服务/harness）：两个实例各自读到同一份
	 * "冷却到点"的快照，会各放一次半开尝试。
	 *
	 * <p>
	 * 读 → 判 → 写回不是原子操作（状态在 {@code ai:ctx:} 里，未加锁）。后果 = 多重试一次摘要请求
	 * （不丢历史、不越窗、不会双写快照 —— 压缩只在本轮请求线程里改内存列表，落库由调用方串行做）。
	 * 本用例把这个"已知且可接受"的偏差钉住：将来若有人想改成"严格一次"，得先解决跨实例租约，
	 * 而不是悄悄改判据。
	 */
	@Test
	void twoInstancesBothSeeTheHalfOpenWindow() {
		ContextState opened = ContextState.EMPTY;
		for (int i = 0; i < 3; i++) {
			opened = this.fuse.onFailure(opened, T0);
		}
		long afterCooldown = opened.openUntilMs();
		CompactionFuse instanceA = new CompactionFuse(this.properties.getContext());
		CompactionFuse instanceB = new CompactionFuse(this.properties.getContext());

		assertThat(instanceA.allowsSummary(opened, afterCooldown)).isTrue();
		assertThat(instanceB.allowsSummary(opened, afterCooldown)).as("双实例各读一次同一份快照").isTrue();

		ContextState halfOpenA = instanceA.halfOpen(opened, afterCooldown);
		ContextState reopened = instanceA.onFailure(halfOpenA, afterCooldown);
		assertThat(reopened.circuit()).isEqualTo(CompactionFuse.OPEN);
		assertThat(reopened.openUntilMs()).as("失败重新开一个新冷却窗（不升指数）")
			.isEqualTo(afterCooldown + COOLDOWN_MS);
	}

}
