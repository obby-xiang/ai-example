package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.id.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 后端会话发号入口（前端不再生成 sessionId）。
 *
 * <p>
 * {@code POST /api/ai/session} 返回
 * {@code {"success":true,"data":{"sessionId":<long>,"sessionIdText":"<十进制字符串>"}}}，
 * 同时以当前请求身份（{@code X-Tenant-Id}/{@code X-User-Id} 建立的上下文）登记会话归属。
 * 切换租户/用户后由前端调用本接口取新号；旧号留在原身份下，不删除、不复用。
 *
 * <h2>两个字段的分工（I1）</h2>
 * 号值可能超过 JS {@code Number.MAX_SAFE_INTEGER}；JSON number 在多数语言里会被
 * 解析成另一个数（double 在该量级间隔 256），且**不报错**。因此：
 * <ul>
 * <li>{@code sessionIdText}：<b>权威值</b>，十进制字符串，消费方（前端/脚本/E2E）必须用它；</li>
 * <li>{@code sessionId}：同值的 long 数字，<b>只为存量兼容</b>；禁止把它当 JSON number 直读。</li>
 * </ul>
 *
 * <h2>发号与撞号（有界重取）</h2>
 * 号由 {@link IdGenerator} 取（当前实现 {@code TimestampIdGenerator} 返回毫秒时间戳）。
 * 同一身份同一毫秒取两次号是<b>正常并发</b>，撞号只说明"这一毫秒的号已被用掉"：入口因此
 * <b>有界重取</b>（最多 {@link #SESSION_ID_ATTEMPTS} 次），每次仍只问 {@link IdGenerator#nextId()}，
 * <b>不在入口自研顺序号/自增后缀</b>（号值语义由发号器负责）。重取前让出一次<b>有界随机抖动</b>
 * （{@code [1, }{@link #RENUMBER_JITTER_MAX_MILLIS}{@code ]} 毫秒，每次重取重新抽）：毫秒发号器不推进
 * 时钟就换不了号，而固定步长会让同频重试者同一毫秒集体醒来、再次对撞。
 *
 * <p>两条终点都是 <b>503</b>（{@link #SESSION_ID_UNAVAILABLE}，可重试），绝不 200：
 * <ul>
 * <li>重取到上界仍撞号（旧记录全程未被覆盖 —— {@code SET NX} 只续期，不合并会话）；</li>
 * <li>会话<b>登记失败</b>（Redis 不可用/异常）：那个号并没有登记，回 200 等于让前端拿着一个
 * 本实例之外不存在的会话号继续对话（会话归属、记忆窗口、会话门全部落在空处）。</li>
 * </ul>
 *
 * <p>
 * <b>为什么必须由后端发号</b>：前端生成的 id 无法证明归属；归属仍以
 * {@code (tenantId,userId,sessionId)} 为准（唯一性不等于隔离）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiSessionController {

	/** 发号撞号/暂不可用的机器可读码（503，可重试）。 */
	public static final String SESSION_ID_UNAVAILABLE = "SESSION_ID_UNAVAILABLE";

	/** 同身份撞号时最多取几次号（含首次）：有界重取，连续撞号即回 503。 */
	public static final int SESSION_ID_ATTEMPTS = 3;

	/**
	 * 重取号前让出的<b>抖动上界</b>（毫秒）：实际让出 {@code [1, 本值]} 内的一次随机抽取。
	 *
	 * <p>为什么必须是随机而不是固定步长：{@link IdGenerator} 当前是毫秒时间戳，同一身份的 N 个
	 * 并发请求在同一毫秒取到同一个号 ⇒ N-1 个进入重试；固定步长会让它们在<b>同一毫秒</b>集体醒来、
	 * 再次全部对撞，3 次上界实际只覆盖 3 个毫秒，503 成批出现。抖动把它们摊开。
	 *
	 * <p>为什么下界是 1ms：毫秒发号器必须靠时钟前进才可能换号，0ms 的让出等于没让。
	 */
	public static final int RENUMBER_JITTER_MAX_MILLIS = 4;

	private final IdGenerator idGenerator;

	private final AiSessionService sessionService;

	/**
	 * 生产装配入口（<b>必须显式 {@code @Autowired}</b>）：本类有多个构造器时，Spring 找不到唯一候选
	 * 也没有无参构造器，会走 {@code instantiateBean} 并抛 {@code No default constructor found} ——
	 * 整个应用上下文刷新失败（不只是发号接口不可用）。仓库既有约定同此（{@code SessionGate} 等）。
	 */
	@Autowired
	public AiSessionController(IdGenerator idGenerator, AiSessionService sessionService) {
		this.idGenerator = idGenerator;
		this.sessionService = sessionService;
	}

	@PostMapping("/session")
	public ResponseEntity<ApiResponse<Map<String, Object>>> create() {
		// 身份缺失即失败（不落默认身份）；HTTP 边界已校验，这里再取一次保证"无上下文不登记"。
		long tenantId = RequestContextUtils.getTenantId();
		long userId = RequestContextUtils.getUserId();
		for (int attempt = 0; attempt < SESSION_ID_ATTEMPTS; attempt++) {
			if (attempt > 0) {
				pauseBeforeRenumbering();
			}
			long sessionId = this.idGenerator.nextId();
			// 登记是原子"不存在才创建"：结局必须三选一，不得把"没登记"读成"已创建"。
			AiSessionService.Registration registration = this.sessionService
				.register(Long.toString(sessionId), tenantId, userId);
			switch (registration) {
				case CREATED -> {
					return ok(sessionId);
				}
				case UNAVAILABLE -> {
					// 记录没有写成：200 会让前端拿着一个本实例之外不存在的会话号继续对话
					return unavailable("会话登记暂不可用（会话号 " + sessionId + " 未能登记），请稍后重试");
				}
				case EXISTED -> {
					// 同身份毫秒撞号：换一个号再试（SET NX 只续期，旧会话未被覆盖）
				}
			}
		}
		return unavailable("连续 " + SESSION_ID_ATTEMPTS + " 次取的会话号都已存在，旧会话未被覆盖，请稍后重试");
	}

	/**
	 * 重取号前让出一次<b>有界随机抖动</b>（{@code [1, RENUMBER_JITTER_MAX_MILLIS]} 毫秒）。
	 *
	 * <p>固定步长会让同频重试者在同一毫秒集体醒来、再次对撞（见
	 * {@link #RENUMBER_JITTER_MAX_MILLIS}）；每次重取重新抽一次才能把它们摊开。
	 *
	 * <p>抖动源在<b>使用点</b>取（{@link #nextRenumberJitterMillis()} 里的
	 * {@code ThreadLocalRandom.current()}）：它有线程局部状态，存进字段就等于让别的线程用
	 * 创建者的生成器。
	 */
	private void pauseBeforeRenumbering() {
		long millis = nextRenumberJitterMillis();
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 抖动抽取点：每次重取在 {@code [1, RENUMBER_JITTER_MAX_MILLIS]} 内重新抽一次。
	 *
	 * <p>包内可覆写：单测用它精确钉住"每次重取都重新抽、且落在界内"，而不必把随机源塞进字段。
	 */
	long nextRenumberJitterMillis() {
		return ThreadLocalRandom.current().nextLong(1L, RENUMBER_JITTER_MAX_MILLIS + 1L);
	}

	private static ResponseEntity<ApiResponse<Map<String, Object>>> ok(long sessionId) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sessionId", sessionId);
		// 权威值：十进制字符串。JSON number 超过 2^53 后不可直读（见类注）。
		data.put("sessionIdText", Long.toString(sessionId));
		return ResponseEntity.ok(ApiResponse.ok(data));
	}

	private static ResponseEntity<ApiResponse<Map<String, Object>>> unavailable(String message) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(ApiResponse.error(SESSION_ID_UNAVAILABLE, message));
	}

}
