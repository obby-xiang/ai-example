package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>宿主确定性的定义选择表单</b>：新建任务 → 导航到配置选择步骤后，若配置未知，由宿主自己挂起一张
 * 平铺多选表单（{@code purpose=SELECT_DEFINITIONS}），不再把这一交互留给模型自由决定。
 *
 * <h2>被替换掉的自由裁量</h2>
 * 同一现场下模型的输出不可控：它可能写成文字反问（"请告诉我导出哪个配置"），也可能自己拼一份
 * 带层级 / 级联的 options。用户口径是确定性的：<b>新任务 + 导航成功 + 配置未知 ⇒ 宿主直接发
 * 平铺多选</b>，选项来自 {@link DefinitionCatalog}（{@code value=defCode}、{@code label=定义名称}），
 * 不需要 {@code dependsOn}、不需要模型先 {@code list_config_defs}。
 *
 * <h2>触发判据（全部成立才挂起）</h2>
 * <ol>
 * <li><b>新任务链</b>：本轮台账里有 {@code create_task} 且回执带真实任务号 —— 这是"用户主动新建"
 * 的<b>唯一</b>证据。用户只是浏览/打开既有页面（没有 create_task）时一律不弹，旧任务配置也不会
 * 成为"新任务的已知配置"（判据只看本轮台账与<b>新任务</b>自己的权威值）；</li>
 * <li><b>刚导航到那个任务</b>：本批已结算条目里有一条 {@code navigate_to}，{@code outcome=OK}
 * 且入参 {@code taskId} 等于上面那个任务号 —— 判据挂在"导航成功"这条边上，而不是挂在一段时间
 * 或某个任务状态上，因此同一轮里后续的批（表单回灌、选择配置项）不会再触发；</li>
 * <li><b>停在选择步骤</b>：上下文页是导出向导、权威步骤 {@code SELECT_DEFS}；</li>
 * <li><b>配置未知</b>：任务槽位里没有 {@code defCodes}（与 {@link GenerativeFormGuard} 的放行判据
 * 同一份 {@link TaskSlots}）—— 已知配置时宿主不弹，让模型直接走 {@code select_definitions}；</li>
 * <li><b>目录可枚举</b>：目录非空、可读，且条数不超过平铺上限
 * （{@link GenerativeFormRules#MAX_OPTIONS}）。空目录/读取失败/超上限一律<b>不生成残缺表单</b> ——
 * 交回既有失败恢复与模型侧路径，绝不下发一张"选了也没用"的空表；</li>
 * <li><b>本批没有自己处理</b>：条目里已经有 {@code generative_form} / {@code select_definitions}
 * 时不重复；同一任务也只挂一次（{@link #toolCallId(long)}）。</li>
 * </ol>
 *
 * <h2>复用的是既有机制，不新增中断路径</h2>
 * 挂起面与模型自己下发的 {@code generative_form} <b>完全一致</b>：
 * {@link RunStore#saveSuspended} → {@code suspended} 帧 → {@link ConfirmGate#publishRequestsForParkedBatch}
 * （置 {@code req} 并发 {@code frontend_tool_request}）→ 本轮收流。之后用户的<b>提交</b>与<b>取消</b>
 * 走既有 {@code POST /api/ai/frontend-tool-result}（{@link ConfirmGate#submitFrontendResult}）与既有续跑，
 * 值经既有回灌闸门（{@link GenerativeFormGuard#inspectResult}）复核后作为工具结果回填模型；
 * 选择本身仍由既有 {@code select_definitions} 完成 —— HITL 确认门、步骤守卫、任务状态机一字不动。
 *
 * <p><b>只在 park 装配下生效</b>（{@code app.ai.park.enabled}，生产 yml = true）：阻塞式等待
 * （park=false）意味着"等人在同一个工作线程里"，把它用在这条<b>新增</b>的流程上就是新的中断路径，
 * 因此这里直接跳过（模型侧既有行为不变）。
 *
 * <h2>唯一的接入点：{@code ResumeService} 的批间交棒点</h2>
 * {@code navigate_to} 是前端工具 —— 生产 {@code park=true} 时它在等待处就结束了首轮执行
 * （{@code RunParkedException}），结算、批间上下文刷新与"下一跳"全部发生在
 * {@code ResumeService#resume}。于是"导航成功之后、模型再被问之前"的确定点只有一个：
 * 续跑侧<b>整批结算 + 权威同步 + 工作区段重写之后、记忆窗口/压缩与交棒之前</b>（那里的
 * {@code nextHopContext} 才是"下一跳那份上下文"，槽位也已按新任务的权威值重算过）。
 * 首轮执行循环的尾部因此<b>不</b>另挂一处：park 下那条路只在"前端抢在 park 判定之前回灌"的
 * 竞态里才会走到，在那里再挂一处就要用另一份（可能未重算的）上下文做判据 —— 同一件事两套判据
 * 是更差的选择；竞态里本次不挂起时，用户得到的仍是改造前的既有行为。
 */
@Slf4j
@Component
public class DefinitionSelectionForm {

	/** 表单唯一字段的 key（回灌值形如 {@code {"defCodes":["CURRENCY",...]}}）。 */
	public static final String FIELD_KEY = "defCodes";

	/** 表单标题（与提示词同一语言；模型自发的表单也用同一句式）。 */
	public static final String TITLE = "选择要导出的配置项";

	/** 字段标签。 */
	public static final String FIELD_LABEL = "配置项";

	/** 宿主表单的 {@code toolCallId} 前缀（后接任务号：同一任务只会挂起一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-def-form-";

	/** 配置选择步骤（与 {@code TaskService} 的 EXPORT 首步、{@code @ToolScope} 同一字面量）。 */
	static final String SELECT_DEFS_STEP = "SELECT_DEFS";

	/** 导出向导页（{@link ContextBuilder#SELECTION_PAGE_IDS} 的成员）。 */
	static final String EXPORT_PAGE = "export";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final RunStore store;

	private final RunRegistry registry;

	private final ConfirmGate gate;

	private final MessageJsonCodec codec;

	private final AiProperties properties;

	private final DefinitionCatalog catalog;

	@Autowired
	public DefinitionSelectionForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties, DefinitionCatalog catalog) {
		this.store = store;
		this.registry = registry;
		this.gate = gate;
		this.codec = codec;
		this.properties = properties;
		this.catalog = catalog;
	}

	/** 宿主为某个任务生成的表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(long taskId) {
		return TOOL_CALL_ID_PREFIX + taskId;
	}

	/**
	 * 判据成立时挂起平铺多选表单。
	 *
	 * @param runId        本轮 id
	 * @param context      <b>交棒那一份</b>上下文（批间刷新 + 任务权威同步之后）
	 * @param settledBatch 本批已结算条目（结算后回读的真值）
	 * @param history      交棒历史（本批工具结果已在内）
	 * @return {@code true} = 已挂起表单：调用方本拍<b>不得</b>再驱动模型（这一轮在等人填表）
	 */
	public boolean parkIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		if (runId == null || context == null || history == null) {
			return false;
		}
		if (this.properties == null || !this.properties.getPark().isEnabled()) {
			// 阻塞式等待不承接这条新增流程（见类注：不新增中断路径）
			return false;
		}
		try {
			Optional<String> arguments = argumentsIfDue(runId, context, settledBatch);
			if (arguments.isEmpty()) {
				return false;
			}
			return park(runId, history, pending(context.getTaskId(), arguments.get()));
		}
		catch (RuntimeException ex) {
			// fail-safe：这是"让交互确定"的增强，不是这一轮的判定面 —— 任何读库/写库/发帧异常
			// 都只 WARN，交回既有路径（模型照常决定），绝不把一轮正常续跑变成失败。
			log.warn("宿主定义选择表单未能挂起（按既有路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 判据 + 表单正文（不含任何外置写）。
	 *
	 * @return {@code empty} = 不该由宿主发这张表单
	 */
	Optional<String> argumentsIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch) {
		Long createdTaskId = createdTaskInRun(runId);
		if (createdTaskId == null) {
			// 普通浏览/打开既有任务：不是新任务链
			return Optional.empty();
		}
		if (context.getTaskId() == null || !createdTaskId.equals(context.getTaskId())) {
			// 还在旧任务上（或根本没到向导）：旧任务的配置不能被当成新任务的已知信息
			return Optional.empty();
		}
		if (handledInBatch(settledBatch)) {
			return Optional.empty();
		}
		if (!EXPORT_PAGE.equals(context.getPage()) || !SELECT_DEFS_STEP.equals(context.getStep())) {
			return Optional.empty();
		}
		if (!navigatedTo(settledBatch, createdTaskId)) {
			return Optional.empty();
		}
		if (TaskSlots.fromExtra(context.getExtra()).hasDefCodes()) {
			// 配置已知 ⇒ 直接走 select_definitions，不弹表单（与槽位放行同一判据）
			return Optional.empty();
		}
		if (alreadyIssued(runId, createdTaskId)) {
			return Optional.empty();
		}
		return formArguments(createdTaskId);
	}

	/** 本轮是否已创建任务（AR-03 的同一份台账口径）；回执里没有任务号 = 不算"新任务链"。 */
	private Long createdTaskInRun(String runId) {
		List<Map<String, Object>> ledger;
		try {
			ledger = this.store.ledger(runId);
		}
		catch (RuntimeException ex) {
			log.warn("读取本轮台账失败，宿主不生成定义选择表单 runId={}：{}", runId, ex.getMessage());
			return null;
		}
		if (ledger == null || ledger.isEmpty()) {
			return null;
		}
		for (Map<String, Object> record : ledger) {
			if (record == null || !RunContextRefresher.TOOL_CREATE_TASK.equals(record.get("name"))) {
				continue;
			}
			if (!ToolOutcome.OK.wire().equals(String.valueOf(record.get("outcome")))) {
				continue;
			}
			Long taskId = RunContextRefresher.taskIdFromText(String.valueOf(record.get("resultText")));
			if (taskId != null) {
				return taskId;
			}
		}
		return null;
	}

	/** 本批是否有一次<b>成功</b>的导航，且目标正是本轮新建的那个任务。 */
	private static boolean navigatedTo(List<PendingToolCall> settledBatch, Long createdTaskId) {
		if (settledBatch == null || settledBatch.isEmpty()) {
			return false;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled == null || !RunContextRefresher.TOOL_NAVIGATE.equals(settled.getName())) {
				continue;
			}
			if (!settled.resolved() || settled.effectiveOutcome() != ToolOutcome.OK) {
				continue;
			}
			JsonNode args = parse(settled.getArguments());
			Long target = longOf(args == null ? null : args.get("taskId"));
			if (createdTaskId.equals(target)) {
				return true;
			}
		}
		return false;
	}

	/** 本批已经有模型自己的选择表单/勾选调用：宿主不再重复（同一缺口只问一次）。 */
	private static boolean handledInBatch(List<PendingToolCall> settledBatch) {
		if (settledBatch == null) {
			return false;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled == null) {
				continue;
			}
			if (GenerativeFormRules.TOOL_NAME.equals(settled.getName())
					|| "select_definitions".equals(settled.getName())) {
				return true;
			}
		}
		return false;
	}

	/** 同一任务只挂一次：{@code toolCallId} 是确定性的，待决条目里已有同号即视为已挂过。 */
	private boolean alreadyIssued(String runId, Long createdTaskId) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			// 读不出来就不冒"重复弹表"的险：宁可不发，交回既有路径
			log.warn("读取待决条目失败，宿主不生成定义选择表单 runId={}：{}", runId, ex.getMessage());
			return true;
		}
		String toolCallId = toolCallId(createdTaskId);
		for (PendingToolCall pending : pendings == null ? List.<PendingToolCall>of() : pendings) {
			if (pending != null && toolCallId.equals(pending.getToolCallId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 平铺多选的表单正文（<b>纯函数</b>）：选项就是 {@link DefinitionCatalog} 的配置项。
	 *
	 * <p>不生成 {@code defaultValue}（旧任务/历史选择不得预填）与 {@code optionsFrom}
	 * （不需要 {@code dependsOn} 层级：选项平铺）。
	 *
	 * @return {@code empty} = 目录为空 / 不可枚举 / 超出平铺上限（<b>不得</b>下发空表或残表）
	 */
	private Optional<String> formArguments(Long createdTaskId) {
		List<SlotDefinition> definitions;
		try {
			definitions = this.catalog == null ? List.of() : this.catalog.list();
		}
		catch (RuntimeException ex) {
			log.warn("配置定义目录读取失败，宿主不生成定义选择表单 taskId={}：{}", createdTaskId, ex.getMessage());
			return Optional.empty();
		}
		if (definitions == null || definitions.isEmpty()) {
			log.warn("配置定义目录为空，宿主不生成空表单 taskId={}（交回既有失败恢复与模型侧路径）", createdTaskId);
			return Optional.empty();
		}
		if (definitions.size() > GenerativeFormRules.MAX_OPTIONS) {
			log.warn("配置定义数量 {} 超出平铺上限 {}，宿主不生成残缺表单 taskId={}（交回模型侧路径）",
					definitions.size(), GenerativeFormRules.MAX_OPTIONS, createdTaskId);
			return Optional.empty();
		}
		List<Map<String, Object>> options = new ArrayList<>(definitions.size());
		Set<String> seen = new LinkedHashSet<>();
		for (SlotDefinition definition : definitions) {
			String value = definition == null ? null : definition.code();
			if (value == null || value.isBlank() || value.length() > GenerativeFormRules.MAX_OPTION_TEXT_CHARS
					|| !seen.add(value)) {
				// 编码缺失/过长/重复：这不是"少一项"，而是"这张表选不全" —— 交给模型侧路径
				log.warn("配置定义编码不可平铺（缺失/过长/重复），宿主不生成残缺表单 taskId={} code={}",
						createdTaskId, value);
				return Optional.empty();
			}
			Map<String, Object> option = new LinkedHashMap<>();
			option.put("value", value);
			option.put("label", label(definition));
			options.add(option);
		}
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("key", FIELD_KEY);
		field.put("label", FIELD_LABEL);
		field.put("type", "multi_select");
		field.put("required", true);
		field.put("options", options);

		Map<String, Object> form = new LinkedHashMap<>();
		form.put("scenario", "CLARIFY");
		form.put("purpose", GenerativeFormRules.PURPOSE_SELECT_DEFINITIONS);
		form.put("title", TITLE);
		form.put("fields", List.of(field));

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("form", form);
		String json;
		try {
			json = JSON.writeValueAsString(arguments);
		}
		catch (Exception ex) {
			log.warn("定义选择表单序列化失败，宿主不生成表单 taskId={}：{}", createdTaskId, ex.getMessage());
			return Optional.empty();
		}
		if (json.length() > GenerativeFormRules.MAX_FORM_CHARS) {
			log.warn("定义选择表单长度 {} 超出上限 {}，宿主不生成残缺表单 taskId={}", json.length(),
					GenerativeFormRules.MAX_FORM_CHARS, createdTaskId);
			return Optional.empty();
		}
		return Optional.of(json);
	}

	/** 选项文案：定义名称（只作展示，回灌值是编码）；超长按选项文本上限截断。 */
	private static String label(SlotDefinition definition) {
		String name = definition.name();
		if (name == null || name.isBlank()) {
			return definition.code();
		}
		String trimmed = name.trim();
		return trimmed.length() > GenerativeFormRules.MAX_OPTION_TEXT_CHARS
				? trimmed.substring(0, GenerativeFormRules.MAX_OPTION_TEXT_CHARS) : trimmed;
	}

	private PendingToolCall pending(Long taskId, String arguments) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId(taskId));
		pending.setName(GenerativeFormRules.TOOL_NAME);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		// 表单是"收集值"，不是不可逆写：不要不确定性的复选框语义
		pending.setIrreversible(false);
		return pending;
	}

	/**
	 * 既有挂起面（与模型自己下发的 {@code generative_form} 同一条路）：外置挂起态 → {@code suspended}
	 * → 请求帧 → 本轮收流。顺序是硬要求（{@code saveSuspended} 先于任何帧）。
	 */
	private boolean park(String runId, List<Message> history, PendingToolCall pending) {
		try {
			this.store.saveSuspended(runId, "", List.of(pending), history, this.codec);
		}
		catch (RuntimeException ex) {
			// 写挂起态失败：<b>不得留下一条看不见的待决条目</b>（saveSuspended 先写条目再写快照，
			// 快照那一步失败时条目会留在 Redis 里；前端没有请求帧、用户看不到卡，而后续续跑会被
			// 既有 PENDING_UNRESOLVED 前置挡住 —— 一轮就此卡死）。这里尽力把它收敛成终态
			// （不执行、不再等待），失败只告警：最坏情形与"没收敛"相同。
			convergeAfterParkFailure(runId, pending, ex);
			return false;
		}
		SseChatEmitter out = this.registry.of(runId);
		out.suspended(List.of(pending), this.store.runKey(runId), this.store.pendingKey(runId),
				this.store.instanceId());
		this.gate.publishRequestsForParkedBatch(runId, List.of(pending));
		// "挂起即断"（B1）：请求帧送达后本轮订阅收流，用户提交/取消后再带 lastSeq 重连
		out.completeSubscriptions();
		log.info("宿主已挂起定义选择表单 runId={} toolCallId={}（平铺多选，等用户勾选）", runId,
				pending.getToolCallId());
		return true;
	}

	/** 挂起失败后的尽力收敛：把条目落成"未执行"的终态，避免它永远以 PENDING 挡住续跑。 */
	private void convergeAfterParkFailure(String runId, PendingToolCall pending, RuntimeException cause) {
		log.warn("宿主表单挂起写失败（本拍不挂起，按既有路径继续）runId={} toolCallId={}：{}", runId,
				pending.getToolCallId(), cause.getMessage());
		try {
			pending.setStatus(PendingToolCall.BLOCKED);
			pending.setOutcome(ToolOutcome.FAILED.wire());
			pending.setCode(ToolOutcome.CODE_TOOL_EXECUTION_FAILED);
			pending.setReason("宿主表单挂起失败，未下发前端");
			pending.setExecuted(false);
			pending.setResolvedAtMs(System.currentTimeMillis());
			this.store.putPending(runId, pending);
		}
		catch (RuntimeException nested) {
			log.warn("宿主表单挂起失败后的条目收敛也失败（该条目留在 PENDING）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), nested.getMessage());
		}
	}

	private static JsonNode parse(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			return JSON.readTree(argumentsJson);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static Long longOf(JsonNode node) {
		if (node == null || node.isNull() || node.isFloatingPointNumber() || node.isBoolean()) {
			return null;
		}
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		if (node.isTextual()) {
			try {
				return Long.parseLong(node.asText().trim());
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

}
