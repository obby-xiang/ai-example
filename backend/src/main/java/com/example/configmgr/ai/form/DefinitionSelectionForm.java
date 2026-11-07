package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlots;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.task.entity.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
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
 * <b>宿主确定性的定义选择表单</b>：新建任务后若配置未知，由宿主自己在选择步骤挂起一张平铺多选表单
 * （{@code purpose=SELECT_DEFINITIONS}），不再把这一交互留给模型自由决定。
 *
 * <h2>被替换掉的自由裁量</h2>
 * 同一现场下模型的输出不可控：它可能写成文字反问（"请告诉我导出哪个配置"），也可能自己拼一份
 * 带层级 / 级联的 options。用户口径是确定性的：<b>新任务 + 停在选择步骤 + 配置未知 ⇒ 宿主直接发
 * 平铺多选</b>，选项来自 {@link DefinitionCatalog}（{@code value=defCode}、{@code label=定义名称}），
 * 不需要 {@code dependsOn}、不需要模型先 {@code list_config_defs}。
 *
 * <h2>触发判据（全部成立才挂起）</h2>
 * <ol>
 * <li><b>新任务链</b>：本轮台账里有 {@code create_task} 且回执带真实任务号 —— 这是"用户主动新建"
 * 的<b>唯一</b>证据。用户只是浏览/打开既有页面（没有 create_task）时一律不弹，旧任务配置也不会
 * 成为"新任务的已知配置"（判据只看本轮台账与<b>新任务</b>自己的权威值）；</li>
 * <li><b>页面归属证据</b>：{@code page}/{@code taskId}/{@code step} 的判定（见下一条）来自权威
 * 上下文；"当前页属于新任务"可由本 run 成功导航到该任务的 {@code navigate_to} 回执证明，
 * 也可由回灌时持久化的 {@code pageId + selectionSynced} 当前页面事实证明（手工切任务没有导航条目）。
 * 两者都没有 ⇒ 交回模型路径，不猜页面；去重仍靠"同一任务只挂一次"的确定性
 * {@code toolCallId}（最后一条）；</li>
 * <li><b>停在选择步骤</b>：上下文页是导出向导、任务号就是本轮新建的那个、权威步骤 {@code SELECT_DEFS}；</li>
 * <li><b>配置未知</b>：任务槽位里没有 {@code defCodes}（与 {@link GenerativeFormGuard} 的放行判据
 * 同一份 {@link TaskSlots}）—— 已知配置时宿主不弹表，改由
 * {@link #parkSelectDefinitionsIfDue} 经既有 {@code select_definitions} 通道直接代选；</li>
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
 * <h2>两条挂起期的硬约束</h2>
 * <ol>
 * <li><b>沿用当前快照的 {@code assistantContent}/{@code assistantMetadata}</b>：{@code saveSuspended}
 * 会把这两项一起覆盖写，传 {@code ""}/{@code null} 等于抹掉模型刚产出的那一轮（正文可能非空，
 * 推理元数据还带着 {@code reasoningContent}/{@code encrypted_content} 等厂商私有键）。挂起只换
 * "谁在等"，故先读快照再原样带上（6 参重载）；</li>
 * <li><b>快照一旦挂起成功，本拍就不再交回模型</b>：随后的发帧/发布/收流只是观测面，任一失败
 * 都只 WARN 并<b>返回 true</b>（这一轮在等人，等恢复）。交回模型会形成
 * "SUSPENDED + 待决条目 + 模型在跑"的双驱动 —— 同一轮的帧与历史会分叉。
 * {@code saveSuspended} 本身的失败仍按原口径收敛成终态并返回 {@code false}。</li>
 * </ol>
 *
 * <p><b>只在 park 装配下生效</b>（{@code app.ai.park.enabled}，生产 yml = true）：阻塞式等待
 * （park=false）意味着"等人在同一个工作线程里"，把它用在这条<b>新增</b>的流程上就是新的中断路径，
 * 因此这里直接跳过（模型侧既有行为不变）。
 *
 * <h2>唯一的接入点：{@code ResumeService} 的批间交棒点</h2>
 * {@code navigate_to} 是前端工具 —— 生产 {@code park=true} 时它在等待处就结束了首轮执行
 * （{@code RunParkedException}），结算、批间上下文刷新与"下一跳"全部发生在
 * {@code ResumeService#resume}。于是"新任务到达选择步骤之后、模型再被问之前"的确定点只有一个：
 * 续跑侧<b>整批结算 + 权威同步 + 工作区段重写之后、记忆窗口/压缩与交棒之前</b>（那里的
 * {@code nextHopContext} 才是"下一跳那份上下文"，槽位也已按新任务的权威值重算过）。
 * 首轮执行循环的尾部因此<b>不</b>另挂一处：park 下那条路只在"前端抢在 park 判定之前回灌"的
 * 竞态里才会走到，在那里再挂一处就要用另一份（可能未重算的）上下文做判据 —— 同一件事两套判据
 * 是更差的选择；竞态里本次不挂起时，用户得到的仍是改造前的既有行为。
 */
@Slf4j
@Component
public class DefinitionSelectionForm extends HostForm {

	/** 表单唯一字段的 key（回灌值形如 {@code {"defCodes":["CONFIG_CODE",...]}}）。 */
	public static final String FIELD_KEY = "defCodes";

	/** 表单标题（与提示词同一语言；模型自发的表单也用同一句式）。 */
	public static final String TITLE = "选择要导出的配置项";

	/** 字段标签。 */
	public static final String FIELD_LABEL = "配置项";

	/** 宿主表单的 {@code toolCallId} 前缀（后接任务号：同一任务只会挂起一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-def-form-";

	/** 配置选择步骤（唯一注册定义见 {@code TaskSteps}；此处只是既有调用点的别名）。 */
	static final String SELECT_DEFS_STEP = com.example.configmgr.task.service.TaskSteps.SELECT_DEFS;

	/** 条件收集步骤（宿主推进的显式目标：不交给前端按页面位置推断）。 */
	static final String QUERY_COND_STEP = com.example.configmgr.task.service.TaskSteps.QUERY_COND;

	/** 导出向导页（{@link ContextBuilder#SELECTION_PAGE_IDS} 的成员）。 */
	static final String EXPORT_PAGE = "export";

	/** 导入向导页（{@link ContextBuilder#SELECTION_PAGE_IDS} 的成员）。 */
	static final String IMPORT_PAGE = "import";

	/** 宿主导航的 {@code toolCallId} 前缀（后接任务号：同一任务只导航一次）。 */
	public static final String NAVIGATE_TOOL_CALL_ID_PREFIX = "host-nav-";

	/** 首次宿主导航非 OK 终结后的<b>唯一一次</b>重试后缀（{@code host-nav-<taskId>-r2}）。 */
	public static final String NAVIGATE_RETRY_SUFFIX = "-r2";

	/** 宿主代选的 {@code toolCallId} 前缀（后接任务号：同一任务只代选一次）。 */
	public static final String SELECT_TOOL_CALL_ID_PREFIX = "host-def-select-";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final DefinitionCatalog catalog;

	@Autowired
	public DefinitionSelectionForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties, DefinitionCatalog catalog) {
		super(store, registry, gate, codec, properties);
		this.catalog = catalog;
	}

	/** 宿主为某个任务生成的表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(long taskId) {
		return TOOL_CALL_ID_PREFIX + taskId;
	}

	/**
	 * 由宿主经既有前端 {@code navigate_to} 通道把新建任务送进对应向导页（导出 / 导入）。
	 *
	 * <p><b>两个触发点（都只看当前 {@code settledBatch}）</b>：
	 * <ol>
	 * <li><b>本批成功结算 {@code create_task}</b>（{@code outcome=OK} 且回执带真实任务号）—— 首发
	 *     {@code host-nav-<taskId>}。台账里"曾经建过任务"不算，否则无在飞工具的旧 run 恢复时会仅凭
	 *     历史台账把用户拉回向导页；页面相等也不作为"新任务已打开"的证据（旧任务 A 的 {@code export}
	 *     页可能正开着）。本批已有成功或仍未决的导航时不重复；模型同批导航已失败时，创建现已成功，
	 *     宿主用同一个确定性 id 接管一次，避免"批准前导航不存在任务"的失败永久截断后续宿主链；</li>
	 * <li><b>本批里第一次宿主导航 {@code host-nav-<taskId>} 刚以非 OK 终态结算</b> —— 只重发一次
	 *     {@code host-nav-<taskId>-r2}。这是"首次导航失败后没有 OK 证据、确定性 id 又永久去重"
	 *     的唯一有界恢复：{@code PENDING} 不重发、模型自发导航或其它导航失败不触发、{@code -r2}
	 *     再失败不再重发（第二次失败交回模型/UI），旧 run 恢复（本批没有这条失败条目）同样不触发。</li>
	 * </ol>
	 *
	 * <p>{@code create_task(openWizard=false)} 表示只创建，禁止首发自动导航；以后用户明确打开或配置任务时，
	 * 既有导航回执与配置链仍可正常使用。
	 *
	 * <p>两个触发点都要求 {@code createdTaskInRun} 与当前 {@code context.taskId} 绑定、任务类型
	 * 可判定（首发优先取 {@code create_task} 入参 type，重试取上下文权威类型）；首发要求本批没有
	 * 成功或仍未决的其它 {@code navigate_to}，重试要求本批除那条失败的首发外没有别的 {@code navigate_to}。
	 *
	 * @return {@code true} = 已挂起导航：调用方本拍<b>不得</b>再驱动模型（导航结果会经续跑回来）
	 */
	public boolean parkNavigateIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		if (runId == null || context == null || history == null || settledBatch == null || !isParkEnabled()) {
			return false;
		}
		try {
			Long createdTaskId = createdTaskInRun(runId);
			if (createdTaskId == null || !createdTaskId.equals(context.getTaskId())) {
				// 不是本轮新建并已绑定的任务：不改既有页面（旧 run 恢复同口径）
				return false;
			}
			NavigationDecision decision = resolveNavigation(context, settledBatch, createdTaskId);
			if (decision == null) {
				return false;
			}
			String toolCallId = NAVIGATE_TOOL_CALL_ID_PREFIX + createdTaskId
					+ (decision.isRetry() ? NAVIGATE_RETRY_SUFFIX : "");
			if (hasIssued(runId, toolCallId)) {
				// 首发已发过；重试 id 只允许一次（-r2 再失败不会再发）
				return false;
			}
			Map<String, Object> arguments = new LinkedHashMap<>();
			arguments.put("page", decision.page());
			arguments.put("taskId", createdTaskId);
			Optional<String> json = toJson(arguments);
			if (json.isEmpty()) {
				return false;
			}
			log.info("{} task #{}, host sends navigate_to(page={}, id={}) runId={}", decision.isRetry() ? "retry after non-OK navigation" : "created in this batch", createdTaskId, decision.page(), toolCallId, runId);
			return shouldPark(runId, history,
					withToolTimeout(frontendTool(toolCallId, AiToolNames.NAVIGATE_TO, json.get())));
		}
		catch (RuntimeException ex) {
			log.warn("host navigate_to was not sent (continue on existing path) runId={}", runId, ex);
			return false;
		}
	}

	private NavigationDecision resolveNavigation(AiContext context, List<PendingToolCall> settledBatch, long createdTaskId) {
		PendingToolCall created = settledCreateTask(settledBatch);
		if (created != null) {
			if (!RunContextRefresher.openWizard(created.getArguments())) {
				return null;
			}
			Long createdInBatch = created.getCreatedTaskId();
			if (createdInBatch == null || createdTaskId != createdInBatch) {
				return null;
			}
			String type = taskTypeOf(created.getArguments());
			if (type == null) {
				// 上下文已绑定该新任务（上面的判据），类型可用其权威值
				type = context.getTaskType();
			}
			String page = wizardPage(type);
			if (page == null) {
				return null;
			}
			if (settledBatch.stream()
				.filter(settled -> settled != null && AiToolNames.NAVIGATE_TO.equals(settled.getName()))
				.anyMatch(settled -> !settled.resolved() || settled.effectiveOutcome() == ToolOutcome.OK)) {
				// 成功或仍在等待的导航不重复；非 OK 终态由宿主在创建成功后接管一次
				return null;
			}
			return new NavigationDecision(failedFirstHostNavigation(settledBatch, createdTaskId) != null, page);
		}
		PendingToolCall failed = failedFirstHostNavigation(settledBatch, createdTaskId);
		if (failed == null) {
			// 本批没有"刚失败的第一次宿主导航"：不因历史台账/旧失败把用户拉回页面
			return null;
		}
		String page = wizardPage(context.getTaskType());
		if (page == null || hasOtherNavigateInBatch(settledBatch, failed.getToolCallId())) {
			// 本批还有模型自发/其它导航：不抢发第二次
			return null;
		}
		return new NavigationDecision(true, page);
	}

	private record NavigationDecision(boolean isRetry, String page) {
	}

	/**
	 * 已到 {@code EXPORT/SELECT_DEFS}、配置已知、且页面归属已由本 run 导航回执或持久化页面事实证明时，
	 * 由宿主经既有前端 {@code select_definitions} 通道代为选择（{@code mode=REPLACE}，只形成前端草案，
	 * 业务库仍由前端下一步提交）。
	 *
	 * <p>页面证据必须属于当前新建任务（旧任务 A 的页面不得冒充新任务 B）；没有证据就交回模型，不猜。
	 * 配置未知时<b>不</b>走这里：沿用 {@link #parkIfDue} 的平铺多选表单。
	 * 本批已有 {@code select_definitions}（成功或失败）不重复；同一任务用确定性 id 去重。
	 * 选择成功后的推进仍由 {@link #parkAdvanceIfDue} 在下一跳完成。
	 *
	 * @return {@code true} = 已挂起代选：调用方本拍<b>不得</b>再驱动模型
	 */
	public boolean parkSelectDefinitionsIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		if (runId == null || context == null || history == null || !isParkEnabled()) {
			return false;
		}
		try {
			Long createdTaskId = createdTaskInRun(runId);
			if (createdTaskId == null || !createdTaskId.equals(context.getTaskId())) {
				return false;
			}
			if (!EXPORT_PAGE.equals(context.getPage()) || !SELECT_DEFS_STEP.equals(context.getStep())) {
				return false;
			}
			if (!hasNavigatedToTaskInRun(runId, createdTaskId) && !hasPersistedPageFact(context)) {
				// 没有导航回执或已同步页面事实：页面归属不确定，交回模型
				return false;
			}
			List<String> codes = TaskSlots.fromExtra(context.getExtra()).defCodes();
			if (codes.isEmpty()) {
				// 配置未知：交给平铺多选表单
				return false;
			}
			if (isHandledInBatch(settledBatch, AiToolNames.SELECT_DEFINITIONS)) {
				// 本批已处理（含失败）：不重复下发，让既有路径继续
				return false;
			}
			String toolCallId = SELECT_TOOL_CALL_ID_PREFIX + createdTaskId;
			if (hasIssued(runId, toolCallId)) {
				return false;
			}
			Map<String, Object> arguments = new LinkedHashMap<>();
			arguments.put("codes", codes);
			arguments.put("mode", "REPLACE");
			Optional<String> json = toJson(arguments);
			if (json.isEmpty()) {
				return false;
			}
			log.info("definitions already known; host sends select_definitions runId={} taskId={} codes={}", runId, createdTaskId,
					codes);
			return shouldPark(runId, history,
					withToolTimeout(frontendTool(toolCallId, AiToolNames.SELECT_DEFINITIONS, json.get())));
		}
		catch (RuntimeException ex) {
			log.warn("host select_definitions was not sent (continue on existing path) runId={}", runId, ex);
			return false;
		}
	}

	/** 任务类型 → 向导页（{@code navigate_to} 的同源取值）；未知类型 = {@code null}。 */
	private static String wizardPage(String taskType) {
		if (StringUtils.isBlank(taskType)) {
			return null;
		}
		if (Task.TaskType.EXPORT.name().equalsIgnoreCase(taskType.trim())) {
			return EXPORT_PAGE;
		}
		if (Task.TaskType.IMPORT.name().equalsIgnoreCase(taskType.trim())) {
			return IMPORT_PAGE;
		}
		return null;
	}

	/** 前端工具入参序列化；失败 = {@code empty}（不下发半截参数）。 */
	private static Optional<String> toJson(Map<String, Object> arguments) {
		try {
			return Optional.of(JSON.writeValueAsString(arguments));
		}
		catch (Exception ex) {
			log.warn("could not serialize host frontend tool arguments; not sent this time", ex);
			return Optional.empty();
		}
	}

	/** 本批里成功创建任务的那条 {@code create_task}（回执带真实任务号）；没有 = {@code null}。 */
	private static PendingToolCall settledCreateTask(List<PendingToolCall> settledBatch) {
		if (settledBatch == null) {
			return null;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled == null || !AiToolNames.CREATE_TASK.equals(settled.getName())
					|| !settled.resolved() || settled.effectiveOutcome() != ToolOutcome.OK) {
				continue;
			}
			if (settled.getCreatedTaskId() != null) {
				return settled;
			}
		}
		return null;
	}

	/** {@code create_task} 入参里的 {@code type}（缺键/非文本/空白 = {@code null}）。 */
	private static String taskTypeOf(String argumentsJson) {
		JsonNode arguments = parseJson(argumentsJson);
		JsonNode type = arguments == null ? null : arguments.get("type");
		if (type == null || !type.isTextual()) {
			return null;
		}
		String value = type.asText("").trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * 本 run 是否有一条<b>成功结算</b>的 {@code navigate_to}，且目标正是该任务：待决条目里
	 * {@code name=navigate_to}、前端通道、已结算、{@code outcome=OK}、{@code arguments.taskId} 匹配。
	 *
	 * <p>这是页面归属的一种结构证据；手工切换则由 {@link #hasPersistedPageFact} 补充证明。
	 * 读失败按"没有导航证据"处理（交回模型，不猜）。
	 */
	private boolean hasNavigatedToTaskInRun(String runId, long taskId) {
		List<PendingToolCall> pendings;
		try {
			pendings = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.warn("could not read navigation evidence; not treated as navigated runId={}", runId, ex);
			return false;
		}
		for (PendingToolCall pending : ObjectUtils.defaultIfNull(pendings, List.<PendingToolCall>of())) {
			if (pending == null || !AiToolNames.NAVIGATE_TO.equals(pending.getName())
					|| !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
					|| !pending.resolved() || pending.effectiveOutcome() != ToolOutcome.OK) {
				continue;
			}
			JsonNode arguments = parseJson(pending.getArguments());
			JsonNode raw = arguments == null ? null : arguments.get("taskId");
			Long navigated = longOf(raw);
			if (navigated != null && navigated == taskId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 回灌时持久化的当前页面事实：pageId 与任务页一致，且任务详情已应用。
	 * 手工切任务没有 navigate_to 条目；这个标记与当前 run 创建回执、当前 taskId 一起证明页面归属。
	 * 仅 create_task 推算出的上下文没有 selectionSynced，不得冒充页面已打开。
	 */
	private static boolean hasPersistedPageFact(AiContext context) {
		if (context == null || context.getExtra() == null || context.getPage() == null) {
			return false;
		}
		Object pageId = context.getExtra().get("pageId");
		return context.getPage().equals(String.valueOf(pageId))
				&& Boolean.TRUE.equals(context.getExtra().get(ContextBuilder.SELECTION_SYNCED_KEY));
	}

	/**
	 * 本批里"第一次宿主导航"（{@code host-nav-<taskId>}）以<b>非 OK 终态</b>结算的那一条；没有 = {@code null}。
	 *
	 * <p>只认确定性的首发 id（后缀必须是纯数字）：{@code PENDING} 不算（{@code resolved()} 为假），
	 * {@code -r2} 重试条目不算，模型自发的 {@code navigate_to}（别的 toolCallId）也不算 ——
	 * 因此第二次失败不会再触发，模型/其它导航失败也不会被宿主误判为"该重试"。
	 */
	private static PendingToolCall failedFirstHostNavigation(List<PendingToolCall> settledBatch, long taskId) {
		if (settledBatch == null) {
			return null;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled == null || !AiToolNames.NAVIGATE_TO.equals(settled.getName())
					|| !PendingToolCall.KIND_FRONTEND.equals(settled.getKind())
					|| !settled.resolved() || settled.effectiveOutcome() == ToolOutcome.OK) {
				continue;
			}
			Long idTask = firstHostNavigationTaskId(settled.getToolCallId());
			if (idTask == null || idTask != taskId) {
				continue;
			}
			JsonNode arguments = parseJson(settled.getArguments());
			Long argTask = longOf(arguments == null ? null : arguments.get("taskId"));
			if (argTask == null || argTask != taskId) {
				continue;
			}
			return settled;
		}
		return null;
	}

	/** {@code host-nav-<taskId>}（不含 {@code -r2}）里的任务号；非该形态 = {@code null}。 */
	private static Long firstHostNavigationTaskId(String toolCallId) {
		if (toolCallId == null || !toolCallId.startsWith(NAVIGATE_TOOL_CALL_ID_PREFIX)) {
			return null;
		}
		String suffix = toolCallId.substring(NAVIGATE_TOOL_CALL_ID_PREFIX.length());
		if (suffix.isEmpty()) {
			return null;
		}
		for (int i = 0; i < suffix.length(); i++) {
			if (!Character.isDigit(suffix.charAt(i))) {
				// -r2 或任何非纯数字后缀都不是"第一次宿主导航"
				return null;
			}
		}
		try {
			return Long.valueOf(suffix);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** 本批除给定条目外是否还有别的 {@code navigate_to}（模型自发或其它导航）；有则不抢发重试。 */
	private static boolean hasOtherNavigateInBatch(List<PendingToolCall> settledBatch, String excludedToolCallId) {
		if (settledBatch == null) {
			return false;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled != null && AiToolNames.NAVIGATE_TO.equals(settled.getName())
					&& (excludedToolCallId == null || !excludedToolCallId.equals(settled.getToolCallId()))) {
				return true;
			}
		}
		return false;
	}

	/** JSON 整数/数字文本 → long；其它形状（小数/布尔/对象/数组/空）= {@code null}。 */
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
		if (!isParkEnabled()) {
			// 阻塞式等待不承接这条新增流程（见类注：不新增中断路径）
			return false;
		}
		try {
			Optional<String> arguments = argumentsIfDue(runId, context, settledBatch);
			if (arguments.isEmpty()) {
				return false;
			}
			return shouldPark(runId, history, frontendForm(toolCallId(context.getTaskId()), arguments.get()));
		}
		catch (RuntimeException ex) {
			// fail-safe：这是"让交互确定"的增强，不是这一轮的判定面 —— 任何读库/写库/发帧异常
			// 都只 WARN，交回既有路径（模型照常决定），绝不把一轮正常续跑变成失败。
			log.warn("host definition selection form was not parked (continue on existing path) runId={}", runId, ex);
			return false;
		}
	}

	/**
	 * 配置选择成功后，由宿主经既有前端 {@code confirm_step} 通道推进到条件步骤。
	 * 本批已有推进调用、选择失败或页面已离开选择步骤时不重复下发。
	 *
	 * <p>判据是既有的通用推进范围：任何任务停在 {@code EXPORT/SELECT_DEFS}、配置已知、本批有成功的
	 * {@code select_definitions} 且本批还没推进过 ⇒ 宿主推进；不要求"任务由本 run 新建"，也不要求
	 * 本 run 有导航回执 —— 后续 run / 旧任务里用户手动改选后的正常推进同样适用。
	 *
	 * <p>目标步骤<b>显式</b>写 {@code step=QUERY_COND}（而不是留空交给前端按"页面当前位置 +1"）：
	 * 空参数时前端的目标来自 {@code stepIndex+1}，页面状态与任务权威步骤不一致就会二次推进
	 * （QUERY_COND→EXPORT，跳过条件收集）或直接以"缺少 step"失败。确定性目标也让回执可对账。
	 */
	public boolean parkAdvanceIfDue(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		return shouldParkAdvance(runId, context, settledBatch, history);
	}

	public boolean shouldParkAdvance(String runId, AiContext context, List<PendingToolCall> settledBatch,
			List<Message> history) {
		if (runId == null || context == null || history == null || !isParkEnabled()
				|| context.getTaskId() == null || !EXPORT_PAGE.equals(context.getPage())
				|| !SELECT_DEFS_STEP.equals(context.getStep())
				|| !TaskSlots.fromExtra(context.getExtra()).hasDefCodes()
				|| isHandledInBatch(settledBatch, AiToolNames.CONFIRM_STEP)
				|| !hasSuccessfulSelection(settledBatch)) {
			return false;
		}
		String toolCallId = "host-def-next-" + context.getTaskId();
		if (hasIssued(runId, toolCallId)) {
			return false;
		}
		PendingToolCall advance = withToolTimeout(frontendTool(toolCallId, AiToolNames.CONFIRM_STEP,
				"{\"step\":\"" + QUERY_COND_STEP + "\"}"));
		log.info("definitions selected; host sends confirm_step to advance to the condition step runId={} taskId={}", runId, context.getTaskId());
		return shouldPark(runId, history, advance);
	}

	private static boolean hasSuccessfulSelection(List<PendingToolCall> settledBatch) {
		if (settledBatch == null) {
			return false;
		}
		for (PendingToolCall settled : settledBatch) {
			if (settled != null && AiToolNames.SELECT_DEFINITIONS.equals(settled.getName())
					&& com.example.configmgr.ai.tool.ToolOutcome.OK == settled.effectiveOutcome()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 判据 + 表单正文（不含任何外置写）。
	 *
	 * <p>判据<b>按任务状态驱动</b>：本轮 {@code create_task} 成功、上下文任务号就是它、
	 * 页面归属有本 run 导航回执或已同步页面事实、页/步骤是导出向导的 {@code SELECT_DEFS}、槽位尚无
	 * {@code defCodes}、本批未被处理、同一任务未挂过。旧任务的页面不得冒充新任务。
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
		if (!hasNavigatedToTaskInRun(runId, createdTaskId) && !hasPersistedPageFact(context)) {
			// 没有导航回执或已同步页面事实：交回模型
			return Optional.empty();
		}
		if (isHandledInBatch(settledBatch, GenerativeFormRules.TOOL_NAME, AiToolNames.SELECT_DEFINITIONS)) {
			return Optional.empty();
		}
		if (!EXPORT_PAGE.equals(context.getPage()) || !SELECT_DEFS_STEP.equals(context.getStep())) {
			return Optional.empty();
		}
		if (TaskSlots.fromExtra(context.getExtra()).hasDefCodes()) {
			// 配置已知 ⇒ 直接走 select_definitions，不弹表单（与槽位放行同一判据）
			return Optional.empty();
		}
		if (hasIssued(runId, toolCallId(createdTaskId))) {
			return Optional.empty();
		}
		return buildArguments(createdTaskId);
	}

	/**
	 * 平铺多选的表单正文（<b>纯函数</b>）：选项就是 {@link DefinitionCatalog} 的配置项。
	 *
	 * <p>不生成 {@code defaultValue}（旧任务/历史选择不得预填）与 {@code optionsFrom}
	 * （不需要 {@code dependsOn} 层级：选项平铺）。
	 *
	 * @return {@code empty} = 目录为空 / 不可枚举 / 超出平铺上限（<b>不得</b>下发空表或残表）
	 */
	private Optional<String> buildArguments(Long createdTaskId) {
		List<SlotDefinition> definitions;
		try {
			definitions = this.catalog == null ? List.of() : this.catalog.list();
		}
		catch (RuntimeException ex) {
			log.warn("could not read definition catalog; host skips the definition selection form taskId={}", createdTaskId, ex);
			return Optional.empty();
		}
		if (CollectionUtils.isEmpty(definitions)) {
			log.info("definition catalog is empty; host skips the empty form taskId={} (fall back to failure recovery and the model path)", createdTaskId);
			return Optional.empty();
		}
		if (definitions.size() > GenerativeFormRules.MAX_OPTIONS) {
			log.warn("definition count {} exceeds flat limit {}; host skips the truncated form taskId={} (fall back to model path)",
					definitions.size(), GenerativeFormRules.MAX_OPTIONS, createdTaskId);
			return Optional.empty();
		}
		List<Map<String, Object>> options = new ArrayList<>(definitions.size());
		Set<String> seen = new LinkedHashSet<>();
		for (SlotDefinition definition : definitions) {
			String value = definition == null ? null : definition.code();
			if (StringUtils.isBlank(value) || value.length() > GenerativeFormRules.MAX_OPTION_TEXT_CHARS
					|| !seen.add(value)) {
				// 编码缺失/过长/重复：这不是"少一项"，而是"这张表选不全" —— 交给模型侧路径
				log.warn("definition code cannot be flattened (missing/too long/duplicate); host skips the truncated form taskId={} code={}",
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
		return formArguments(form);
	}

	/** 选项文案：定义名称（只作展示，回灌值是编码）；超长按选项文本上限截断。 */
	private static String label(SlotDefinition definition) {
		String name = definition.name();
		if (StringUtils.isBlank(name)) {
			return definition.code();
		}
		String trimmed = name.trim();
		return trimmed.length() > GenerativeFormRules.MAX_OPTION_TEXT_CHARS
				? trimmed.substring(0, GenerativeFormRules.MAX_OPTION_TEXT_CHARS) : trimmed;
	}

}
