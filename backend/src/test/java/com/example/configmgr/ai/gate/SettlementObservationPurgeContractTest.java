package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>结算观测位的销毁契约</b>（复审 C-1；<b>Task 3 可直接复用本类</b>）。
 *
 * <p>绑定要求：会话销毁必须删除<b>全部</b> AI 归属数据 —— {@code ai:observed:<runId>} 不能只等 TTL。
 * 本类把「销毁方该怎么调用 + 失败怎么处理」写成可执行契约，Task 3 在
 * {@code AiSessionPurgeService#deleteObservabilityKeys}（与 {@code ai:beat:}/{@code ai:activity:}/
 * {@code ai:cancel:} 同列）接线时照 {@link #purgeAll} 的三行实现即可：
 * <ol>
 * <li>{@code store.observedKey(runId)} 取键（与 {@code claim} 写入的是同一个键）；</li>
 * <li>{@code store.purge(runId)} 一次清两个面（Redis 键 + 进程内表），幂等；</li>
 * <li>返回 {@code false} ⇒ 记进既有 {@code errors}（走 {@code SessionPurgeRetryRunner} 的
 * 重试语义），不要静默。</li>
 * </ol>
 */
class SettlementObservationPurgeContractTest {

	private static final String RUN_ID = "run-observed-purge";

	private static final String OTHER_RUN = "run-observed-purge-other";

	private static final String CALL_ID = "call-observed-purge";

	private final AiProperties properties = new AiProperties();

	/** 销毁后：Redis 键（不是等 TTL）+ 进程内面都清；同 id 重新可抢（新的一轮语义）。 */
	@Test
	void purgeDeletesTheRedisKeyAndTheInProcessFace() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		store.claim(RUN_ID, CALL_ID);
		store.claim(OTHER_RUN, CALL_ID);

		assertThat(purgeAll(store, List.of(RUN_ID))).as("销毁成功不产生错误项").isEmpty();

		assertThat(fake.hasKey(store.observedKey(RUN_ID))).as("Redis 键已删（不是等 TTL）").isFalse();
		assertThat(fake.hasKey(store.observedKey(OTHER_RUN))).as("只删本 run，不误伤别的轮").isTrue();
		assertThat(store.claim(RUN_ID, CALL_ID)).as("销毁后重新可抢").isTrue();
	}

	/** 无 Redis（轻装配/单测）时，清的就是进程内那唯一一份面。 */
	@Test
	void purgeAlsoClearsTheInProcessFace() {
		SettlementObservationStore memoryOnly = new SettlementObservationStore();
		assertThat(memoryOnly.claim(RUN_ID, CALL_ID)).isTrue();

		assertThat(purgeAll(memoryOnly, List.of(RUN_ID))).isEmpty();

		assertThat(memoryOnly.claim(RUN_ID, CALL_ID)).as("进程内面已清").isTrue();
	}

	/** DEL 失败必须如实上报（调用方记 errors 走既有重试），不能静默成功。 */
	@Test
	void purgeReportsRedisFailureSoTheCallerCanRetry() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		store.claim(RUN_ID, CALL_ID);
		fake.failDelete = true;

		assertThat(purgeAll(store, List.of(RUN_ID))).as("失败必须进错误集").hasSize(1)
			.allSatisfy(error -> assertThat(error).contains(RUN_ID).contains("ai:observed"));
	}

	/** 销毁不存在的轮次不算错（幂等；重复销毁/空会话都安全）。 */
	@Test
	void purgingAnUnknownRunIsIdempotent() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);

		assertThat(purgeAll(store, List.of(RUN_ID, RUN_ID))).isEmpty();
	}

	/**
	 * <b>R-I-2 联合契约</b>：Task 3 的销毁面按<b>字面量</b> {@code "ai:observed:<runId>"} 单键 DEL
	 * （它刻意不引用本类常量，以免 Task 2 未并入时编译失败）。本用例用同一个字面量删，
	 * 验证 {@code claim} 写的确实是那把键 ⇒ 任一侧改名都会在这里失配，而不是在生产上静默少删。
	 */
	@Test
	void destroySideLiteralDeleteTargetsTheKeyClaimWrote() {
		FakeObservationRedis fake = new FakeObservationRedis();
		SettlementObservationStore store = new SettlementObservationStore(fake.template, this.properties);
		store.claim(RUN_ID, CALL_ID);
		store.claim(OTHER_RUN, CALL_ID);

		assertThat(Boolean.TRUE.equals(fake.template.delete("ai:observed:" + RUN_ID)))
			.as("按销毁方字面量删到了 claim 写的那把键").isTrue();
		assertThat(fake.hasKey("ai:observed:" + RUN_ID)).isFalse();
		assertThat(fake.hasKey("ai:observed:" + OTHER_RUN)).as("只删本 run，不误伤别的轮").isTrue();
		assertThat(store.claim(RUN_ID, CALL_ID)).as("删后重新可抢（键名一致 ⇒ 仲裁位真的没了）").isTrue();
	}

	/**
	 * <b>Task 3 的接线形状</b>（照抄即可）：逐 run {@code purge}，失败收集为错误项 ——
	 * 与 {@code AiSessionPurgeService} 既有的 {@code errors} + 重试语义同形，不需要新机制。
	 */
	private static List<String> purgeAll(SettlementObservationStore store, List<String> runIds) {
		List<String> errors = new ArrayList<>();
		for (String runId : runIds) {
			if (!store.purge(runId)) {
				errors.add("run[" + runId + "]: ai:observed 删除失败（可重试）");
			}
		}
		return errors;
	}

}
