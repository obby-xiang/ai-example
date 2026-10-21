package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源类结局码与重试表（B23L1 批③a / 裁决 4、5）。
 *
 * <h2>两个新码的语义</h2>
 * <ul>
 * <li>{@link ToolOutcome#CODE_RESOURCE_LOCKED}（{@code RESOURCE_LOCKED}，结局 {@code blocked}）：
 * L1 资源互斥抢不到锁 —— "现在不能执行"，不是"执行出错"。<b>可重试</b>：锁的寿命由 TTL/续租界定，
 * 等对方收尾后再来是正确策略；</li>
 * <li>{@link ToolOutcome#CODE_TARGET_INVALID}（{@code TARGET_INVALID}，结局 {@code blocked}）：
 * 目标资源不成立（如 taskId 不存在）—— 参数本身就错，重试同一个调用只会得到同一个结论，不可重试。</li>
 * </ul>
 *
 * <h2>为什么码必须与结局绑定（而不是"码一出现就可重试"）</h2>
 * {@code retryable} 是 {@code outcome + code} 的二元函数：同一个码挂在不同结局下可以有不同的答案
 * （例如 {@code RESOURCE_LOCKED} 挂在 {@code failed} 下就不是"等锁"）。本类把这条"按结局分派"
 * 钉住 —— 否则实现退化成"码表全局可重试"，越 scope 等 blocked 码会被误判成可重试。
 *
 * <p><b>实例面维度（M8 口径）</b>：本类只断言纯函数（结局 × 码 → 布尔），与实例/多实例无关；
 * 信封面的三个消费者（模型侧 JSON、SSE 帧、台账）读的是同一个 {@code retryable} 值
 * （见 {@link ToolResultEnvelopeTest} 与本类的信封断言）。
 */
class ToolOutcomeResourceCodesTest {

	@Test
	void theTwoResourceCodesHaveTheirDocumentedWireValues() {
		assertThat(ToolOutcome.CODE_RESOURCE_LOCKED).isEqualTo("RESOURCE_LOCKED");
		assertThat(ToolOutcome.CODE_TARGET_INVALID).isEqualTo("TARGET_INVALID");
		assertThat(Arrays.asList(ToolOutcome.values())).as("结局枚举本身不新增成员（两个新码挂在既有结局上）")
			.hasSize(8);
		assertThat(ToolOutcome.fromWire("RESOURCE_LOCKED"))
			.as("两个新码是 code，不是 outcome（不能混进结局字面量）").isEqualTo(ToolOutcome.UNKNOWN);
	}

	@Test
	void blockedIsRetryableOnlyForResourceLocked() {
		assertThat(ToolOutcome.BLOCKED.retryable(ToolOutcome.CODE_RESOURCE_LOCKED))
			.as("裁决 4：等锁 ⇒ 可重试").isTrue();
		assertThat(ToolOutcome.BLOCKED.retryable(ToolOutcome.CODE_SCOPE_NOT_DISCLOSED))
			.as("越 scope 仍是 blocked 且不可重试（不放宽）").isFalse();
		assertThat(ToolOutcome.BLOCKED.retryable(null)).isFalse();
		assertThat(ToolOutcome.BLOCKED.retryable("SOMETHING_NEW")).isFalse();
	}

	@Test
	void blockedTargetInvalidIsNotRetryable() {
		assertThat(ToolOutcome.BLOCKED.retryable(ToolOutcome.CODE_TARGET_INVALID))
			.as("裁决 5：目标不成立 ⇒ 重试无意义（与 RESOURCE_LOCKED 同结局、不同结论）").isFalse();
		assertThat(ToolOutcome.FAILED.retryable(ToolOutcome.CODE_TARGET_INVALID))
			.as("码与结局绑定：挂 failed 也不会变成可重试").isFalse();
		assertThat(ToolOutcome.FAILED.retryable(ToolOutcome.CODE_PRECHECK_FAILED))
			.as("既有可重试码不变").isTrue();
	}

	@Test
	void theCodeAloneDoesNotMakeAnythingRetryable() {
		// 同一个码挂在不同结局下：只有 BLOCKED 认 RESOURCE_LOCKED、只有 FAILED 认预检类码
		assertThat(ToolOutcome.FAILED.retryable(ToolOutcome.CODE_RESOURCE_LOCKED)).isFalse();
		assertThat(ToolOutcome.OK.retryable(ToolOutcome.CODE_RESOURCE_LOCKED)).isFalse();
		assertThat(ToolOutcome.CANCELLED.retryable(ToolOutcome.CODE_RESOURCE_LOCKED)).isFalse();
		assertThat(ToolOutcome.BLOCKED.retryable(ToolOutcome.CODE_PRECHECK_FAILED))
			.as("预检码挂着 blocked 也不是'等锁'").isFalse();
	}

	@Test
	void theOtherOutcomesKeepTheirBatchTwoSemantics() {
		for (ToolOutcome outcome : ToolOutcome.values()) {
			if (outcome == ToolOutcome.BLOCKED) {
				continue;
			}
			String[] codes = { ToolOutcome.CODE_RESOURCE_LOCKED, ToolOutcome.CODE_TARGET_INVALID, null };
			for (String code : codes) {
				boolean expected = outcome == ToolOutcome.FAILED && code != null
						&& (ToolOutcome.CODE_PRECHECK_FAILED.equals(code)
								|| ToolOutcome.CODE_PRECHECK_UNAVAILABLE.equals(code)
								|| "PRECHECK_NOT_PASSED".equals(code) || "PRECHECK_STALE".equals(code));
				assertThat(outcome.retryable(code)).as("结局 %s + 码 %s 的重试结论不变", outcome, code)
					.isEqualTo(expected);
			}
		}
	}

	@Test
	void envelopesCarryTheCodeThroughToTheRetryableFlag() {
		ToolResultEnvelope locked = ToolResultEnvelope.blocked(ToolOutcome.CODE_RESOURCE_LOCKED, "任务上的导入作业正在进行",
				"{\"taskId\":7}");

		assertThat(locked.ok()).isFalse();
		assertThat(locked.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
		assertThat(locked.code()).isEqualTo(ToolOutcome.CODE_RESOURCE_LOCKED);
		assertThat(locked.retryable()).as("信封面：blocked + RESOURCE_LOCKED ⇒ retryable=true").isTrue();
		assertThat(locked.target()).containsEntry("taskId", 7);
		assertThat(locked.toJson()).contains("\"retryable\":true");

		ToolResultEnvelope invalid = ToolResultEnvelope.blocked(ToolOutcome.CODE_TARGET_INVALID, "任务 #7 不存在",
				"{\"taskId\":7}");
		assertThat(invalid.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
		assertThat(invalid.retryable()).as("信封面：blocked + TARGET_INVALID ⇒ retryable=false").isFalse();
	}

}
