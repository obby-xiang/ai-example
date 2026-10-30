package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.session.AiSessionService.Registration;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>重取号之间的让出必须是"有界随机抖动"，且抖动源不能缓存进字段</b>（I-3 / M-2）。
 *
 * <h2>为什么要抖动</h2>
 * 同一身份的 N 个并发发号请求在同一毫秒取到同一个号（毫秒时间戳发号器必然如此），于是 N-1 个进入
 * 重试；固定步长会让它们<b>在同一毫秒集体醒来</b>、再次全部取到同一个号，第三次仍如此 ⇒ 503 成批
 * 出现。"3 次上界"实际只覆盖 3 个互不相同的毫秒。
 *
 * <h2>为什么不能把 {@code ThreadLocalRandom.current()} 存进字段</h2>
 * 它有线程局部状态（"methods of this object should be called only by the current thread"）。
 * 构造器在启动/请求线程上执行一次就把那个实例塞进共享字段，之后所有线程都去用创建者的生成器 ——
 * 语义上依赖 JDK 内部按线程号取种子的实现细节。正确写法是在<b>使用点</b>取。
 *
 * <p>本类两条用例各钉一条：每次重取重新抽一次并落在 {@code [1, RENUMBER_JITTER_MAX_MILLIS]}；
 * 类里不得出现缓存随机源字段。
 */
class AiSessionControllerRenumberJitterTest {

	private static final long TENANT = 1L;

	private static final long USER = 101L;

	@Test
	void everyRenumberingPauseDrawsABoundedJitterAtTheCallSite() {
		IdGenerator generator = mock(IdGenerator.class);
		when(generator.nextId()).thenReturn(1_700_000_000_123L, 1_700_000_000_124L, 1_700_000_000_125L);
		AiSessionService sessions = mock(AiSessionService.class);
		when(sessions.register("1700000000123", TENANT, USER)).thenReturn(Registration.EXISTED);
		when(sessions.register("1700000000124", TENANT, USER)).thenReturn(Registration.EXISTED);
		when(sessions.register("1700000000125", TENANT, USER)).thenReturn(Registration.CREATED);
		RecordingController controller = new RecordingController(generator, sessions, 1L, 3L);

		ResponseEntity<ApiResponse<Map<String, Object>>> response;
		long startedAt = System.nanoTime();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			response = controller.create();
		}
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).containsEntry("sessionIdText", "1700000000125");
		assertThat(controller.drawn()).as("每次重取都重新抽一次（不是固定步长）").containsExactly(1L, 3L);
		assertThat(controller.drawn())
			.allSatisfy(millis -> assertThat(millis).isBetween(1L, (long) AiSessionController.RENUMBER_JITTER_MAX_MILLIS));
		assertThat(elapsedMillis).as("必须按抽到的抖动真的让出（1ms + 3ms）").isGreaterThanOrEqualTo(4L);
	}

	@Test
	void theControllerDoesNotCacheARandomSourceInAField() {
		assertThat(AiSessionController.class.getDeclaredFields())
			.as("ThreadLocalRandom 必须在使用点取（缓存字段会让别的线程用创建者的生成器）")
			.noneMatch(field -> RandomGenerator.class.isAssignableFrom(field.getType()));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 记录每次抽取并回答给定序列的控制器替身（只管"抽了几次、抽到多少"，不放断言）。 */
	private static final class RecordingController extends AiSessionController {

		private final long[] values;

		private final List<Long> drawn = new ArrayList<>();

		private int index;

		RecordingController(IdGenerator generator, AiSessionService sessions, long... values) {
			super(generator, sessions);
			this.values = values;
		}

		@Override
		long nextRenumberJitterMillis() {
			long value = this.values[Math.min(this.index++, this.values.length - 1)];
			this.drawn.add(value);
			return value;
		}

		List<Long> drawn() {
			return List.copyOf(this.drawn);
		}

	}

}
