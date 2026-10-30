package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.GlobalExceptionHandler;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>C-1（返修第 2 轮）</b>：reattach 的归档截断响应头必须与前端<b>已落地</b>的契约同名同语义。
 *
 * <h2>契约（前端 {@code frontend/src/api/sse.ts} 的 {@code ARCHIVE_TRIM_HEADERS}）</h2>
 * <ul>
 * <li>{@code archive-trimmed: true} —— 只在"确实被归档窗口裁过"时出现（缺失 = 没裁/不可知）；</li>
 * <li>{@code archive-from} / {@code archive-to} —— <b>缺口区间</b>（含端点），
 *     即 {@code afterSeq + 1} 到 {@code 裁剪低水位 - 1}，<b>不是</b>本次回放的区间。</li>
 * </ul>
 *
 * <p>为什么是"缺口"而不是"回放区间"：前端拿它给用户解释"哪一段不可完整恢复"。
 * 回放区间（例如 6..8）表达不了缺口（3..5 才是真正丢掉的那段），旧的
 * {@code archiveTrimmed/archiveFrom/archiveTo} 命名又与前端对不上 —— 前端读不到，等于没暴露。
 *
 * <p>kill：改回大小写/驼峰命名 ⇒ 首条 {@code header} 断言红（前端 {@code headers.get('archive-trimmed')}
 * 只做大小写不敏感匹配，不做驼峰展开）；把 from/to 写成回放区间 ⇒ 数值断言红；
 * 未截断时也发头 ⇒ 第二条红（前端只认"出现即裁剪"）。
 */
class AiControllerEventsReattachTrimHeaderTest {

	private static final String RUN_ID = "run-reattach-trim";

	private static final String SESSION_ID = "s-reattach-trim";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final FrameStore frameStore = mock(FrameStore.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private MockMvc mvc;

	private RequestContextUtils.SafeCloseable identityScope;

	@BeforeEach
	void setUp() {
		AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
				mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class),
				mock(ConfirmGate.class), this.store, this.registry, this.cancellations,
				mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class), this.mapper, new AiProperties(),
				mock(Environment.class), mock(JobWatchdog.class));
		ReflectionTestUtils.setField(controller, "frameStore", this.frameStore);
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		this.mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler(new ErrorBodyFactory(provider())))
			.build();
		this.identityScope = RequestContextUtils.useContext(TENANT_ID, USER_ID);
	}

	@AfterEach
	void tearDown() {
		if (this.identityScope != null) {
			this.identityScope.close();
		}
	}

	/** 截断时：三个头都在，且 from/to 是<b>缺口</b>区间（回放的是 6..8，缺的是 3..5）。 */
	@Test
	void reattachPublishesTheGapIntervalUnderTheFrontendHeaderNames() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot());
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(8L));
		when(this.emitter.replayAndAttachWithStatus(any(), any(), anyBoolean()))
			.thenReturn(new SseChatEmitter.ReplayResult(3, false, true, true, 3L, 5L));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isOk())
			.andExpect(header().string("archive-trimmed", "true"))
			.andExpect(header().string("archive-from", "3"))
			.andExpect(header().string("archive-to", "5"));
	}

	/** 未截断时：三个头都<b>不得</b>出现（前端"出现即裁剪"，缺失 = 没裁）。 */
	@Test
	void reattachWithoutTrimmingPublishesNoTrimHeadersAtAll() throws Exception {
		when(this.store.get(RUN_ID)).thenReturn(snapshot());
		when(this.frameStore.lastSeqIfKnown(RUN_ID)).thenReturn(OptionalLong.of(8L));
		when(this.emitter.replayAndAttachWithStatus(any(), any(), anyBoolean()))
			.thenReturn(new SseChatEmitter.ReplayResult(2, false, false, false, 0L, 0L));

		this.mvc.perform(get("/api/ai/events/{runId}", RUN_ID).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("archive-trimmed"))
			.andExpect(header().doesNotExist("archive-from"))
			.andExpect(header().doesNotExist("archive-to"));
	}

	/**
	 * <b>跨端同名断言</b>：后端写的三个头名必须逐字等于<b>前端已落地</b>的
	 * {@code ARCHIVE_TRIM_HEADERS}（{@code frontend/src/api/sse.ts}）。
	 *
	 * <p>为什么要把前端源码拉进后端用例：响应头是跨端契约，改名只需一端就能"编译通过"，
	 * 但线上表现为"前端永远看不到裁剪信号"（静默降级）。这条用例把两端的字面量钉在一起 ——
	 * 任一端改名都会让这条变红，而不是等真机验收才发现。
	 *
	 * <p>语义断言：前端把 {@code from/to} 解释为"缺口区间（含端点）"，与后端 {@code trimmedFromSeq/ToSeq}
	 * 同名同义（后端在上一条用例里已用"回放 6..8 但缺口 3..5"钉住）。
	 */
	@Test
	void archiveTrimHeaderNamesMatchTheFrontendContractExactly() throws Exception {
		Path sseTs = frontendSseTs();
		String source = Files.readString(sseTs);

		assertThat(extractHeader(source, "trimmed")).as("前端读取的 trimmed 头名")
			.isEqualTo(AiController.HEADER_ARCHIVE_TRIMMED);
		assertThat(extractHeader(source, "from")).as("前端读取的 from 头名")
			.isEqualTo(AiController.HEADER_ARCHIVE_FROM);
		assertThat(extractHeader(source, "to")).as("前端读取的 to 头名")
			.isEqualTo(AiController.HEADER_ARCHIVE_TO);
		assertThat(source).as("前端把 from/to 解释为缺口区间（含端点）")
			.contains("区间含端点");
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 前端 sse.ts 的路径（maven 的 cwd 是 backend/；也兼容从仓库根运行）。 */
	private static Path frontendSseTs() {
		for (String candidate : new String[] { "../frontend/src/api/sse.ts", "frontend/src/api/sse.ts" }) {
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path)) {
				return path;
			}
		}
		throw new IllegalStateException("找不到 frontend/src/api/sse.ts（跨端契约断言需要它）");
	}

	/** 从 {@code ARCHIVE_TRIM_HEADERS = { trimmed: '...', from: '...', to: '...' }} 里取一个值。 */
	private static String extractHeader(String source, String key) {
		Matcher matcher = Pattern.compile(key + ":\\s*'([^']+)'").matcher(source);
		assertThat(matcher.find()).as("前端源码里应能找到 %s 头名", key).isTrue();
		return matcher.group(1);
	}

	private static RunSnapshot snapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider() {
		return mock(ObjectProvider.class);
	}

}
