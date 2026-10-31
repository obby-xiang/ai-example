package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>会话任务草案的写入必须是"值 + TTL 同一条命令"</b>。
 *
 * <p>旧实现是 {@code HSET} 再 {@code EXPIRE}：两条命令之间进程被杀 / 连接断掉，Redis 里就留下
 * 一个<b>没有 TTL</b> 的草案 HASH —— 会话销毁的精确 {@code DEL} 只覆盖"已知会话"，这类键只能等
 * 人工清理或永不消失（草案里是用户的筛选条件候选）。
 *
 * <p>本类锁三条（都只看<b>命令面</b>，不依赖真实 Redis）：
 * <ol>
 * <li>一次写入 = <b>单键一条命令</b>，且这条命令同时带值与 TTL：不得出现"先写值、后设 TTL"的第二步；</li>
 * <li>这条命令失败（进程中断/连接失效）⇒ 不留下任何"有值无 TTL"的中间态，且异常照旧上抛；
 * 键仍是同一个会话键（Cluster 单键、身份段与 sessionId 都在 tag 内），也不新增扫描；</li>
 * <li>合并语义不变：空项取旧值、字段仍是 {@code taskId}、内容仍是可解析的 {@code Draft}。</li>
 * </ol>
 */
@SuppressWarnings("unchecked")
class CreateTaskDraftStoreAtomicTtlTest {

	private static final String RUN_ID = "run-1";

	private static final String SESSION_ID = "sess-atomic";

	private static final long TASK_ID = 42L;

	private static final Duration TTL = Duration.ofMinutes(30);

	private final ObjectMapper mapper = new ObjectMapper();

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

	private final RunStore runStore = mock(RunStore.class);

	private final String key = CreateTaskDraftStore.sessionKey(SESSION_ID);

	private CreateTaskDraftStore store;

	@BeforeEach
	void setUp() {
		ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
		when(redisProvider.getIfAvailable()).thenReturn(this.redis);
		ObjectProvider<RunStore> runStoreProvider = mock(ObjectProvider.class);
		when(runStoreProvider.getIfAvailable()).thenReturn(this.runStore);
		when(this.runStore.snapshotOwner(RUN_ID)).thenReturn(new RunStore.SnapshotOwner(
				RunStore.SnapshotOwner.State.ATTRIBUTED, SESSION_ID));
		when(this.redis.opsForHash()).thenReturn(this.hashOps);
		when(this.hashOps.get(anyString(), any())).thenReturn(null);
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
		AiProperties properties = new AiProperties();
		properties.setSessionTtl(TTL);
		this.store = new CreateTaskDraftStore(redisProvider, this.mapper, properties, runStoreProvider);
	}

	// ── ① 值 + TTL 同一条命令（单键），没有第二步 ────────────────────────────

	@Test
	void saveWritesValueAndTtlInOneSingleKeyCommand() {
		Map<String, Object> conditions = conditions("CNY");

		this.store.save(RUN_ID, TASK_ID, List.of("CURRENCY"), conditions);

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
		verify(this.redis).execute(any(RedisScript.class), keys.capture(), args.capture());

		assertThat(keys.getValue())
			.as("仍是同一个会话键（身份段 + sessionId 全在 tag 内），单键命令 ⇒ Cluster 无 CROSSSLOT")
			.containsExactly(this.key);
		assertThat(args.getValue()).as("字段 = taskId").hasSize(3);
		assertThat(args.getValue()[0]).isEqualTo(Long.toString(TASK_ID));
		assertThat(args.getValue()[2]).as("TTL 与那条写值命令同批送达（毫秒，与会话同寿）")
			.isEqualTo(Long.toString(TTL.toMillis()));
		CreateTaskDraftStore.Draft written = parseDraft(String.valueOf(args.getValue()[1]));
		assertThat(written.defCodes()).containsExactly("CURRENCY");
		assertThat(written.conditions()).isEqualTo(conditions);

		verify(this.hashOps, never()).put(anyString(), any(), any());
		verify(this.redis, never()).expire(anyString(), any(Duration.class));
		verify(this.redis, never()).expire(anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
		verify(this.redis, never()).keys(anyString());
	}

	// ── ② 写入失败/进程中断：不得留下"有值无 TTL"的键 ────────────────────────

	@Test
	void interruptedWriteNeverLeavesAValueWithoutTtl() {
		// 值命令与 TTL 是同一条命令 ⇒ 它失败 = 整笔不成立（不存在"值已落、TTL 还没设"的窗口）
		when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new IllegalStateException("连接已断（模拟进程中断）"));

		assertThatThrownBy(() -> this.store.save(RUN_ID, TASK_ID, List.of("CURRENCY"), conditions("CNY")))
			.as("写失败照旧上抛（调用方按内部失败处理）")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("写入会话任务草案失败");

		verify(this.hashOps, never()).put(anyString(), any(), any());
		verify(this.redis, never()).expire(anyString(), any(Duration.class));
		verify(this.redis, never()).expire(anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
		verify(this.redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	// ── ③ 合并语义不变（空项取旧值），仍是同一条单键原子命令 ─────────────────

	@Test
	void mergedDraftKeepsItsSemanticsInTheSameSingleCommand() {
		when(this.hashOps.get(this.key, Long.toString(TASK_ID))).thenReturn(
				writeJson(new CreateTaskDraftStore.Draft(List.of("CURRENCY"), conditions("CNY"), 1L)));

		// 本次只给了新条件（defCodes 为空 ⇒ 取旧值；conditions 非空 ⇒ 覆盖）
		this.store.save(RUN_ID, TASK_ID, List.of(), conditions("USD"));

		ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
		verify(this.redis).execute(any(RedisScript.class), anyList(), args.capture());
		CreateTaskDraftStore.Draft merged = parseDraft(String.valueOf(args.getValue()[1]));
		assertThat(merged.defCodes()).as("空 defCodes 不覆盖已有明确候选").containsExactly("CURRENCY");
		assertThat(merged.conditions()).isEqualTo(conditions("USD"));
		assertThat(args.getValue()[2]).isEqualTo(Long.toString(TTL.toMillis()));

		verify(this.hashOps, never()).put(anyString(), any(), any());
		verify(this.redis, never()).expire(anyString(), any(Duration.class));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private CreateTaskDraftStore.Draft parseDraft(String json) {
		try {
			return this.mapper.readValue(json, CreateTaskDraftStore.Draft.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException("写入值不是可解析的 Draft：" + json, ex);
		}
	}

	private String writeJson(Object value) {
		try {
			return this.mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, Object> conditions(String value) {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("fieldCode", "code");
		field.put("operator", "EQ");
		field.put("value", value);
		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(field));
		return conditions;
	}

}
