package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>启动续跑两入口的身份边界</b>（GET 报告 / POST 扫描）。
 *
 * <h2>被钉住的缺陷</h2>
 * 这两个入口原先整体排除在 {@code TenantIdentityFilter} 之外：任何一个不带身份头的 HTTP 调用都能
 * 触发一次<b>全租户</b>扫描 —— 续跑别人的轮次、并在响应里拿到别人的 runId。GET 同理：它回的是
 * 上一次系统扫描的全局事实，A 能看到 B 的轮次。
 *
 * <h2>本组口径（不含 ops token、不扫描租户列表）</h2>
 * <ul>
 * <li>POST 只扫<b>当前身份</b>的轮次：别人的候选既不被续跑，也不出现在响应里（不泄漏 runId）；</li>
 * <li>GET 只报告<b>当前身份</b>的轮次：系统面（无身份线程）那次全局扫描的结果按调用身份切片，
 *     其他人的条目既不回显、也不计入 candidates；</li>
 * <li>系统面自身不受影响：应用启动时的自动扫描仍在无身份线程上按每轮记录身份推进（本组不改）。</li>
 * </ul>
 */
class StartupResumeEndpointIdentityTest {

	private static final long OWN_TENANT = 1L;

	private static final long OWN_USER = 101L;

	private static final long FOREIGN_TENANT = 2L;

	private static final long FOREIGN_USER = 202L;

	private static final String OWN_RUN = "run-own-identity";

	private static final String FOREIGN_RUN = "run-foreign-identity";

	private final ResumeService resumeService = mock(ResumeService.class);

	private final RunStore store = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final AiProperties properties = new AiProperties();

	private StartupResumeRunner runner;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.resumeService.listRuns()).thenReturn(List.of(
				candidate(OWN_RUN, OWN_TENANT, OWN_USER),
				candidate(FOREIGN_RUN, FOREIGN_TENANT, FOREIGN_USER)));
		when(this.resumeService.resume(anyString(), anyBoolean())).thenReturn(new ResumeService.ResumeResult(
				ResumeService.Outcome.ACCEPTED, Map.of("resumed", true)));
		this.runner = new StartupResumeRunner(this.resumeService, this.store, this.properties, this.availability);
		AiController controller = new AiController(this.availability, providerOf((ResilientChatService) null),
				providerOf(this.resumeService),
				providerOf(this.runner), mock(ChatMemory.class), mock(ChatMemoryRepository.class),
				mock(SessionGate.class), mock(ConfirmGate.class), this.store, mock(RunRegistry.class),
				mock(CancellationRegistry.class), mock(RedisAvailability.class), mock(ThreadPoolTaskExecutor.class),
				new ObjectMapper(), this.properties, mock(Environment.class), mock(JobWatchdog.class));
		this.mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void scanTriggeredByOneIdentityNeitherResumesNorReportsAnotherIdentitiesRun() throws Exception {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OWN_TENANT, OWN_USER)) {
			this.mvc.perform(post("/api/ai/startup-resume/scan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.resumed", hasSize(1)))
				.andExpect(jsonPath("$.data.resumed[0].runId").value(OWN_RUN))
				.andExpect(content().string(not(containsString(FOREIGN_RUN))));
		}

		verify(this.resumeService).resume(eq(OWN_RUN), anyBoolean());
		verify(this.resumeService, never()).resume(eq(FOREIGN_RUN), anyBoolean());
	}

	@Test
	void reportOnlyContainsTheCallingIdentitiesRuns() throws Exception {
		// 系统面（无身份线程）：应用启动扫描能看到所有身份的轮次 —— 这一拍不在本组改动范围内
		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			Map<String, Object> systemReport = this.runner.scan(true);
			org.assertj.core.api.Assertions.assertThat((List<?>) systemReport.get("resumed"))
				.as("系统面扫描仍按每轮记录身份推进")
				.hasSize(2);
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(OWN_TENANT, OWN_USER)) {
			this.mvc.perform(get("/api/ai/startup-resume"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.resumed", hasSize(1)))
				.andExpect(jsonPath("$.data.resumed[0].runId").value(OWN_RUN))
				.andExpect(jsonPath("$.data.candidates").value(1))
				.andExpect(content().string(not(containsString(FOREIGN_RUN))));
		}
	}

	private static Map<String, Object> candidate(String runId, long tenantId, long userId) {
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("runId", runId);
		run.put("tenantId", tenantId);
		run.put("userId", userId);
		run.put("status", "SUSPENDED");
		run.put("cancelled", false);
		run.put("unresolvedExternal", List.of());
		run.put("zombieRunning", false);
		return run;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
