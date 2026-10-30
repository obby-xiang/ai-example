package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FakeLeaseRedis;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>每跳披露重算的身份读取顺序</b>（真实 {@link RunStore} + 真实键形）。
 *
 * <h2>被钉住的形态</h2>
 * 官方模型循环在<b>每一发请求</b>前调 {@code resolveToolDefinitions} 重算披露面，而它跑在 Reactor 上游
 * 线程（{@code boundedElastic}）上：那里没有请求身份，池化线程上还可能残留上一位调用者的身份。
 * 旧实现直接 {@code store.get(runId)}（键由<b>当前线程</b>身份拼）：
 * <ul>
 * <li>无身份线程只读 legacy 键 ⇒ 拿不到本轮快照 ⇒ 回落冻结集合 ——
 *     {@code create_task} 之后的 {@code navigate_to}、导航之后的向导工具在<b>下一跳永不出现</b>
 *     （动态披露静默降级，模型"看得见的工具"与"执行面"分叉）；</li>
 * <li>残留他人身份时读到<b>别人的</b>同名 runId 快照 ⇒ 披露面被换成别人的上下文（串身份）。</li>
 * </ul>
 *
 * <p>修法：与执行点同一口径 —— 先解析本轮可信执行信封，按<b>记录身份</b>开作用域再读快照。
 */
class SpToolCallingManagerDisclosureIdentityTest {

	private static final String RUN_ID = "run-disclosure-identity";

	private static final String SESSION_ID = "s-disclosure-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private static final TenantUser RESIDUAL_IDENTITY = new TenantUser(9002L, 8L);

	/** 请求边界那一份上下文：不是向导页，只有 {@code *} 级工具。 */
	private static final AiContext BOUNDARY_CONTEXT = AiContext.of("definitions", null, null, null, null);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private AnnotationConfigApplicationContext toolContext;

	private ToolRegistry toolRegistry;

	private SpToolCallingManager manager;

	@BeforeEach
	void setUp() {
		this.toolContext = new AnnotationConfigApplicationContext();
		this.toolContext.registerBean(DisclosureTools.class);
		this.toolContext.registerBean(ToolRegistry.class);
		this.toolContext.refresh();
		this.toolRegistry = this.toolContext.getBean(ToolRegistry.class);
		this.manager = new SpToolCallingManager(this.store, this.registry, mock(ConfirmGate.class), this.toolRegistry,
				this.codec, new ToolActivityBeacon(), mock(CancellationRegistry.class),
				new ToolResultLimiter(this.properties), List.of());
	}

	@AfterEach
	void tearDown() {
		if (this.toolContext != null) {
			this.toolContext.close();
		}
	}

	@Test
	void nextHopDisclosureRecomputesFromTheRecordIdentitySnapshot() {
		RunSnapshot record = seedRun(RECORD_IDENTITY, AiContext.of("export", "EXPORT", "EXPORT", 7L, null));
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromSnapshot(record, 0L);
		// 残留身份 B：同名 runId 的另一轮，披露面只有 * 级工具 —— 若按线程身份读就会命中它。
		seedRun(RESIDUAL_IDENTITY, BOUNDARY_CONTEXT);
		ToolCallingChatOptions options = options(envelope);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：官方上游线程没有请求身份").isNull();
		assertThat(definitionNames(this.manager.resolveToolDefinitions(options)))
			.as("第二跳披露必须按记录身份快照重算，否则 navigate_to 之后新披露的工具永远不出现")
			.contains("start_export");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				RESIDUAL_IDENTITY.tenantId(), RESIDUAL_IDENTITY.userId())) {
			assertThat(definitionNames(this.manager.resolveToolDefinitions(options)))
				.as("残留身份不得决定本轮披露面")
				.contains("start_export");
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private RunSnapshot seedRun(TenantUser identity, AiContext context) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(identity.tenantId(),
				identity.userId())) {
			return this.store.create(RUN_ID, SESSION_ID, context, List.of(new UserMessage("看下一批工具")),
					this.codec);
		}
	}

	private ToolCallingChatOptions options(ToolExecutionEnvelope envelope) {
		Map<String, Object> toolContext = new LinkedHashMap<>();
		toolContext.put("runId", RUN_ID);
		toolContext.put(ToolExecutionEnvelope.TOOL_CONTEXT_KEY, envelope);
		return ToolCallingChatOptions.builder()
			.toolCallbacks(this.toolRegistry.forContext(BOUNDARY_CONTEXT))
			.toolContext(toolContext)
			.build();
	}

	private static List<String> definitionNames(List<ToolDefinition> definitions) {
		return definitions.stream().map(ToolDefinition::name).toList();
	}

	/** 披露面随上下文变化的最小题材：{@code *} 级导航 + 只在 EXPORT 步骤披露的导出。 */
	static class DisclosureTools {

		@Tool(name = "navigate_to", description = "驱动前端页面导航")
		@ToolScope("*")
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String navigateTo(@ToolParam(description = "目标页面") String page) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "start_export", description = "启动导出作业")
		@ToolScope({ "task:EXPORT/EXPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.J)
		@ToolChannel(ToolMeta.Channel.BACKEND)
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			return "哨兵桩（永不执行）";
		}

	}

}
