package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 苞 A（R-100）项 1：{@code DELETE /api/ai/history/{sessionId}} 的 HTTP 契约。
 *
 * <p>被删的正是 {@code GET /api/ai/history/{sessionId}} 返回的那份记忆窗口原文 ——
 * 同资源、异动词。六条契约（施工卡 §6.1 的四条 + 幂等重复 + 会话门报告）：
 * <ol>
 * <li>有记忆 → 200 + {@code deleted=true} + {@code existed=true}，且 {@code chatMemory.clear} 被调用；</li>
 * <li>无记忆 / TTL 已过期 → <b>200</b>（<b>不得 404</b>）+ {@code existed=false}（幂等）；</li>
 * <li>Redis 不可用 → <b>503</b> + {@code AI_REDIS_UNAVAILABLE}，且不得触碰记忆；</li>
 * <li>无 AI key（{@code AiAvailability=false}）→ 仍 200（历史的读写不依赖 key）；</li>
 * <li>重复删除 → 两次都 200，第二次 {@code existed=false}；</li>
 * <li>会话仍有在跑轮次 → 仍 200，只报告 {@code runActive}/{@code activeRunId}
 * （<b>明确否掉 409</b>：新建对话不能变成可失败动作）。</li>
 * </ol>
 *
 * <p>复核 F4 追加两条（判存口径）：{@code removedFromIndex} 由 {@code SISMEMBER} 单次判定得出 ——
 * 真 {@code RedisChatMemoryRepository} 装配下不得 {@code SMEMBERS}、也不得逐 id {@code EXISTS}
 * （既有六条用例走 {@code ChatMemoryRepository} 桩，落到 SPI 通用契约的回落分支）。
 *
 * <p>收口修（红队 MINOR-3）追加一条负断言：删除端点只碰记忆面 —— <b>台账面</b>
 * （{@code ai:session:*} / {@code ai:run:*}）的写方法一概不得被触达：{@link RunStore} 的全部写口
 * 与 {@link SessionGate} 的 {@code acquire}/{@code release}（后者只读 {@code currentRunId} 用于报告
 * {@code runActive}）。锁定「不删 ai:session:* / ai:run:*」不变量 —— 这两类键各有自己的 TTL。
 *
 * <p>构造方式照抄 {@link AiControllerRunsCurrentTest}：直接 {@code new AiController(...)} + Mockito 桩，
 * <b>不动构造器签名</b>（全仓 ≥5 处测试按固定参数表 new 本控制器）。
 */
class AiControllerHistoryDeleteTest {

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	/** AI 可用性桩：本端点**不看它**（有 key 无 key 都必须 200），默认桩值即"无 key"形态。 */
	private final AiAvailability availability = mock(AiAvailability.class);

	/** F4 用例专用：真仓储背后的 Redis 桩（只关心"判存几次往返"这一事实）。 */
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(this.availability)).build();

	@Test
	void existingMemoryIsDeletedAndReported() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.runStore.instanceId()).thenReturn("inst-1");
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));
		// 删除后索引里已没有该会话（其余会话照旧）
		when(this.chatMemoryRepository.findConversationIds()).thenReturn(List.of("s-2"));

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value("s-1"))
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(true))
			.andExpect(jsonPath("$.memoryKey").value("chat:mem:s-1"))
			.andExpect(jsonPath("$.removedFromIndex").value(true))
			.andExpect(jsonPath("$.runActive").value(false))
			.andExpect(jsonPath("$.activeRunId").doesNotExist())
			.andExpect(jsonPath("$.storedBy").value("inst-1"));

		verify(this.chatMemory).clear("s-1");
	}

	@Test
	void missingMemoryIsIdempotentOkNotNotFound() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of());

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(false));

		verify(this.chatMemory).clear("s-1");
	}

	@Test
	void repeatedDeleteSecondCallReportsNotExisted() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		// 第一次有记忆、第二次（clear 之后）已空
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")), List.of());

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.existed").value(true));
		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(false));
	}

	@Test
	void redisUnavailableFailsFastWithoutTouchingMemory() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(false);

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_REDIS_UNAVAILABLE"));

		verify(this.chatMemory, never()).clear(anyString());
	}

	@Test
	void noAiKeyStillDeletes() throws Exception {
		// 无 key：只有模型侧 bean 缺失；记忆的读写删都不依赖 key（类注口径）
		AiAvailability noKeyAvailability = mock(AiAvailability.class);
		assertFalse(noKeyAvailability.isAvailable());
		MockMvc noKey = MockMvcBuilders.standaloneSetup(controller(noKeyAvailability)).build();
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));

		noKey.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.existed").value(true));

		verify(this.chatMemory).clear("s-1");
	}

	@Test
	void aiKeyPresentAlsoDeletes() throws Exception {
		// 有 key 形态：与无 key 形态行为必须一致（本端点与 key 无关）
		AiAvailability withKey = mock(AiAvailability.class);
		when(withKey.isAvailable()).thenReturn(true);
		MockMvc available = MockMvcBuilders.standaloneSetup(controller(withKey)).build();
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of());

		available.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.existed").value(false));

		verify(this.chatMemory).clear("s-1");
	}

	@Test
	void activeRunIsReportedNotRejected() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));
		when(this.sessionGate.currentRunId("s-1")).thenReturn("r-1");
		// 收尾的记忆收敛可能把窗口写回：索引里又出现了该会话 —— 端点如实报告"删得不彻底"
		when(this.chatMemoryRepository.findConversationIds()).thenReturn(List.of("s-1"));

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runActive").value(true))
			.andExpect(jsonPath("$.activeRunId").value("r-1"))
			.andExpect(jsonPath("$.removedFromIndex").value(false));

		verify(this.chatMemory).clear("s-1");
	}

	@Test
	void blankSessionIdIsBadRequest() throws Exception {
		// 走 HTTP 到达不了这一分支：MockMvc 会把路径里的 %20 双编码成字面量 "%20"
		// （实测 sessionId="%20"，非空白），而真正空白段在 Spring 里不匹配本 mapping（404）。
		// 故直接调用端点方法验证守卫本身。
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		ResponseEntity<Map<String, Object>> response = controller(this.availability).deleteHistory(" ");

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("BAD_REQUEST", response.getBody().get("code"));
		verify(this.chatMemory, never()).clear(anyString());
	}

	// ── F4（复核收口）：判存只问"这一个 id"，不拉全量索引 ──────────────────────

	/**
	 * F4：生产装配（{@link RedisChatMemoryRepository}）下 {@code removedFromIndex} 走**单次**
	 * {@code SISMEMBER} 判定（键 = {@code chat:mem:__ids__}）—— 不得 {@code SMEMBERS}（拉全量索引），
	 * 也不得对每个存活 id 各来一次 {@code EXISTS}。索引里仍有该 id（在跑轮次收盘写回）→ 如实报 false。
	 */
	@Test
	void indexedCheckIsSingleSismemberNotFullIndexScan() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));
		SetOperations<String, String> setOps = setOperations();
		when(setOps.isMember("chat:mem:__ids__", "s-1")).thenReturn(Boolean.TRUE);

		MockMvc redisBacked = MockMvcBuilders
			.standaloneSetup(controller(this.availability, redisRepository(setOps)))
			.build();
		redisBacked.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.removedFromIndex").value(false));

		verify(setOps).isMember("chat:mem:__ids__", "s-1");
		verify(setOps, never()).members(anyString());
		verify(this.redisTemplate, never()).hasKey(anyString());
	}

	/** F4：成员不在索引里（{@code SISMEMBER} 回 null —— Redis 无该成员或未回话）同样判为"已移出"。 */
	@Test
	void absentMemberReportsRemovedFromIndexTrueWithSingleRoundTrip() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));
		SetOperations<String, String> setOps = setOperations();

		MockMvc redisBacked = MockMvcBuilders
			.standaloneSetup(controller(this.availability, redisRepository(setOps)))
			.build();
		redisBacked.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.removedFromIndex").value(true));

		verify(setOps).isMember("chat:mem:__ids__", "s-1");
		verify(setOps, never()).members(anyString());
		verify(this.redisTemplate, never()).hasKey(anyString());
	}

	// ── 收口修（红队 MINOR-3）：删除端点不触碰台账面 ─────────────────────────────

	/**
	 * MINOR-3 负断言：本端点只删<b>记忆面</b>（{@code chat:mem:<sessionId>} 与它的会话 id 索引），
	 * <b>不删</b> {@code ai:session:*} 与 {@code ai:run:*}（各自有 TTL，删了会打断在跑轮次与
	 * 挂起重建）。故 {@link RunStore} 的任何写方法都不允许被本端点调用 —— 包括最像"删除"的
	 * {@code deleteRun}（它会连 {@code ai:run:*} / {@code ai:pending:*} / {@code ai:ledger:*} /
	 * {@code ai:events:*} 一锅端）与 session→current 索引三写口；{@link SessionGate} 同样只读
	 * （{@code currentRunId} 仅用于报告 {@code runActive}），不得 {@code acquire}（占门会让该会话
	 * 后续请求 409）或 {@code release}（会释放他轮持有的锁）。
	 */
	@Test
	void deleteHistoryNeverWritesRunStoreOrSessionGate() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.chatMemory.get("s-1")).thenReturn(List.of(new UserMessage("你好")));
		// 最危险形态：该会话仍有在跑轮次（runActive=true 的报告路径）
		when(this.sessionGate.currentRunId("s-1")).thenReturn("r-1");

		mockMvc.perform(delete("/api/ai/history/s-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(true))
			.andExpect(jsonPath("$.runActive").value(true))
			.andExpect(jsonPath("$.activeRunId").value("r-1"));

		verify(this.chatMemory).clear("s-1");
		// 会话门：只读不写
		verify(this.sessionGate, never()).acquire(any(), any());
		verify(this.sessionGate, never()).release(any(), any());
		// 台账面（ai:run:* / ai:session:current:*）：一个写口都不许调
		verify(this.runStore, never()).create(any(), any(), any(), any(), any());
		verify(this.runStore, never()).save(any());
		verify(this.runStore, never()).deleteRun(any());
		verify(this.runStore, never()).saveSuspended(any(), any(), any(), any(), any());
		verify(this.runStore, never()).putPending(any(), any());
		verify(this.runStore, never()).claimExecution(any(), any(), any());
		verify(this.runStore, never()).appendLedger(any(), any(), any());
		verify(this.runStore, never()).cancelPendings(any());
		verify(this.runStore, never()).appendEvent(any(), any());
		verify(this.runStore, never()).recordIssuedSeq(any(), anyLong());
		verify(this.runStore, never()).touchActivity(any());
		verify(this.runStore, never()).markSessionCurrent(any(), any());
		verify(this.runStore, never()).renewSessionCurrent(any(), any());
		verify(this.runStore, never()).clearSessionCurrent(any(), any());
	}

	/** 只装配被测端点用得到的依赖（其余为桩：本用例不发模型请求）。 */
	private AiController controller(AiAvailability availability) {
		return controller(availability, this.chatMemoryRepository);
	}

	/** 同上，但换掉仓储实现（F4 两例用真 {@link RedisChatMemoryRepository}）。 */
	private AiController controller(AiAvailability availability, ChatMemoryRepository repository) {
		return new AiController(availability, provider(), provider(), provider(), this.chatMemory,
				repository, this.sessionGate, mock(ConfirmGate.class), this.runStore,
				mock(RunRegistry.class), mock(CancellationRegistry.class), this.redisAvailability,
				mock(ThreadPoolTaskExecutor.class), new ObjectMapper(), new AiProperties(), mock(Environment.class));
	}

	/** 真仓储（生产装配类型）+ 桩 Redis：只用到判存那一次往返。 */
	private RedisChatMemoryRepository redisRepository(SetOperations<String, String> setOps) {
		when(this.redisTemplate.opsForSet()).thenReturn(setOps);
		return new RedisChatMemoryRepository(this.redisTemplate, new MessageJsonCodec(new ObjectMapper()),
				Duration.ofHours(6));
	}

	@SuppressWarnings("unchecked")
	private static SetOperations<String, String> setOperations() {
		return mock(SetOperations.class);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
