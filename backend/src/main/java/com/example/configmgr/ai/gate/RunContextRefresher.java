package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具批后的<b>运行上下文刷新</b>（多步工作流批）。
 *
 * <h2>它解决什么</h2>
 * 请求侧的披露面在<b>请求边界</b>按当时的 {@code AiContext} 算一次（{@code ToolRegistry#forContext}），
 * 而 {@code SpToolCallingManager} 执行路径复查 scope 用的是本轮快照里冻结的那一份。于是
 * "导航成功 → 向导工具可见"在过去根本走不通：上下文只在<b>新的一轮对话</b>才会变，同一轮里
 * 模型永远只看到任务中心/起始页的披露集。
 *
 * <p>本类只做一件事：把<b>已结算且成功</b>的页面动作翻译成新的 {@link AiContext}：
 * <ul>
 * <li>{@code navigate_to}：按入参更新 {@code page}/{@code taskId}；</li>
 * <li>{@code create_task}：从回执里取<b>真实任务号</b>（{@code #123}）；</li>
 * <li>{@code confirm_step}：<b>只按 {@link TaskService} 的权威 {@code currentStep} 更新步骤</b> ——
 * 绝不照抄入参里的 {@code step}（模型可能自报"已跳到 EXPORT"，而域层根本没前进：
 * 步骤决定 {@code start_export} 等工具的披露面，猜错就是把假入口给模型）；</li>
 * <li>以上动作一旦拿到有效 {@code taskId}（{@code confirm_step} 入参没有则沿用当前上下文的），就按
 * {@link TaskService} 的<b>权威值</b>读 {@code type/currentStep/status}（任务状态是披露面"终态收窄"
 * 的判据之一，不能靠猜）；</li>
 * <li><b>无 taskId 的导航</b>清掉旧的 {@code taskType/step/taskStatus}（否则新页面仍带着旧任务的
 * 披露面）；<b>换页</b>删掉 {@code extra.uiRegionCount}（旧页的区域数不代表新页）；</li>
 * <li>无变化返回 {@link Optional#empty()} —— 调用方据此跳过写回。</li>
 * </ul>
 *
 * <h2>失败口径</h2>
 * 任务读不到/读取异常一律<b>抛出</b>（{@code IllegalStateException}），由调用方保留旧上下文并 WARN：
 * 刷新是"让下一步更准"的增强，不是授权面，<b>绝不能因为一次读库失败让整轮工具执行失败</b>；
 * 也绝不拿猜出来的 type/step 去替换权威值。
 *
 * <p>本类不改 {@link TaskService}、不新增持久面、不新增帧类型：它只产出 {@link AiContext}。
 */
@Slf4j
@Component
public class RunContextRefresher {

	/** 页面导航工具名（前端通道；成功回执代表"页面真的到了"）。 */
	public static final String TOOL_NAVIGATE = "navigate_to";

	/** 任务创建工具名（后端确认门；回执里带真实任务号）。 */
	public static final String TOOL_CREATE_TASK = "create_task";

	/** 步骤推进工具名（前端通道；成功回执代表前端已把任务推进并落库）。 */
	public static final String TOOL_CONFIRM_STEP = "confirm_step";

	/** 页面里已注册 AI 区域的数量（{@code ui:on} 标签的判据）。键名与 {@code ToolRegistry} 同源。 */
	public static final String EXTRA_UI_REGION_COUNT = "uiRegionCount";

	private static final ObjectMapper JSON = new ObjectMapper();

	private final TaskService taskService;

	public RunContextRefresher(TaskService taskService) {
		this.taskService = taskService;
	}

	/** 该工具是否会推进运行上下文（调用方据此跳过无谓的 Redis 读）。 */
	public static boolean supports(String toolName) {
		return TOOL_NAVIGATE.equals(toolName) || TOOL_CREATE_TASK.equals(toolName)
				|| TOOL_CONFIRM_STEP.equals(toolName);
	}

	/**
	 * 按一条<b>已结算</b>工具结果算下一份运行上下文。
	 *
	 * @param current 当前上下文（null 按空上下文处理）
	 * @param settled 已结算的待决条目：只认 {@code outcome=OK} 的
	 *                {@code navigate_to}/{@code create_task}/{@code confirm_step}
	 * @return 新上下文；{@code empty} = 这个结果不改变上下文
	 * @throws IllegalStateException 权威任务读不到/读取失败（调用方必须保留旧上下文）
	 */
	public Optional<AiContext> next(AiContext current, PendingToolCall settled) {
		if (settled == null || !supports(settled.getName())) {
			return Optional.empty();
		}
		// 已结算 + 成功，二者缺一不可：只有"前端真的做了/后端真的建了"才是页面事实
		if (!settled.resolved() || settled.effectiveOutcome() != ToolOutcome.OK) {
			return Optional.empty();
		}
		AiContext before = current == null ? AiContext.empty() : current;
		AiContext base = copyOf(before);
		if (TOOL_NAVIGATE.equals(settled.getName())) {
			applyNavigate(base, settled);
		}
		else if (TOOL_CREATE_TASK.equals(settled.getName())) {
			applyCreateTask(base, settled);
		}
		else {
			applyConfirmStep(base, settled);
		}
		return base.equals(before) ? Optional.empty() : Optional.of(base);
	}

	/**
	 * 续跑交棒前的<b>任务权威同步</b>：只按 {@code current.taskId} 读任务，覆盖
	 * {@code taskType / step / taskStatus}（页面与 extra 不动）。
	 *
	 * <p><b>为什么必须有</b>：挂起期内用户可以在向导里手动改步骤/状态；而续跑批里可能没有任何
	 * {@code navigate_to / create_task / confirm_step} 结算（{@link #supports} 全不命中）
	 * ⇒ 批间刷新什么都不做，下一跳永远带挂起那一刻的旧步骤。调用方（{@code ResumeService}）
	 * 只在<b>交棒前</b>用它，批内判定用的上下文仍冻结在批首。
	 *
	 * @param current 当前上下文（无 taskId = 无事可同步）
	 * @return 新上下文；无 taskId 或值未变化 ⇒ {@code empty}
	 * @throws IllegalStateException 任务读不到/读取失败（调用方必须 WARN 并保留旧值）
	 */
	public Optional<AiContext> syncTask(AiContext current) {
		if (current == null || current.getTaskId() == null || current.getTaskId() <= 0L) {
			return Optional.empty();
		}
		AiContext base = copyOf(current);
		applyTask(base, requireTask(current.getTaskId()));
		return base.equals(current) ? Optional.empty() : Optional.of(base);
	}

	/** 导航：page/taskId 来自入参；有 taskId 时 type/step/status 来自任务权威值。 */
	private void applyNavigate(AiContext base, PendingToolCall settled) {
		JsonNode args = parse(settled.getArguments());
		String page = text(args == null ? null : args.get("page"));
		Long taskId = longOf(args == null ? null : args.get("taskId"));
		if (taskId == null) {
			// 无任务导航：旧任务的类型/步骤/状态必须清掉，否则新页面仍在旧任务的披露面里
			base.setTaskId(null);
			base.setTaskType(null);
			base.setStep(null);
			base.setTaskStatus(null);
		}
		else {
			Task task = requireTask(taskId);
			applyTask(base, task);
			if (page == null) {
				page = pageOf(task.getType());
			}
		}
		applyPage(base, page);
	}

	/** 创建：任务号只能从回执里取（真实任务号），字段仍以任务权威值为准。 */
	private void applyCreateTask(AiContext base, PendingToolCall settled) {
		Long taskId = taskIdFromText(settled.getResultText());
		if (taskId == null) {
			// 回执里没有任务号：不编造 ID，也不动上下文
			log.warn("create_task 回执里没有任务号，运行上下文保持不变 toolCallId={}",
					settled.getToolCallId());
			return;
		}
		Task task = requireTask(taskId);
		applyTask(base, task);
		if (base.getPage() == null || base.getPage().isBlank()) {
			applyPage(base, pageOf(task.getType()));
		}
	}

	/**
	 * 步骤推进：<b>步骤只认 {@link TaskService} 的权威 {@code currentStep}</b>。
	 *
	 * <p>为什么绝不按入参 {@code step} 猜：{@code confirm_step} 的 {@code step} 只是"希望推进到哪一步"，
	 * 前端校验不过时根本不会前进；而步骤是 {@code start_export} 等工具的披露判据 —— 照抄入参会把
	 * "模型以为到了 EXPORT"变成"确实给了 start_export 假入口"。因此 {@code args.step} 一律忽略，
	 * 只读权威值。
	 *
	 * <p>任务定位：条目入参带 {@code taskId} 就用它，否则沿用<b>当前上下文</b>的 taskId；两者都没有 ⇒
	 * 不动上下文（没有任务就没有可刷新的步骤事实，WARN 一条即可）。
	 */
	private void applyConfirmStep(AiContext base, PendingToolCall settled) {
		JsonNode args = parse(settled.getArguments());
		Long taskId = longOf(args == null ? null : args.get("taskId"));
		if (taskId == null) {
			taskId = base.getTaskId();
		}
		if (taskId == null || taskId <= 0L) {
			log.warn("confirm_step 缺少可用的 taskId（入参与上下文都没有），运行上下文保持不变 toolCallId={}",
					settled.getToolCallId());
			return;
		}
		Task task = requireTask(taskId);
		applyTask(base, task);
		if (base.getPage() == null || base.getPage().isBlank()) {
			applyPage(base, pageOf(task.getType()));
		}
	}

	private static void applyTask(AiContext base, Task task) {
		base.setTaskId(task.getId());
		base.setTaskType(task.getType() == null ? null : task.getType().name());
		base.setStep(task.getCurrentStep());
		base.setTaskStatus(task.getStatus() == null ? null : task.getStatus().name());
	}

	/**
	 * 页面事实：只在真的换页时改写，并清掉旧页的 {@code uiRegionCount}。
	 *
	 * <p>为什么是"删键"而不是写 0：{@code ToolRegistry#uiRegionsPresent} 把<b>缺失</b>当"存在"
	 * （fail-open，旧客户端/桩不带该键时工具不得静默消失），写 0 会变成确定的"本页没有 AI 区域"，
	 * 那是拿旧页的数字冒充新页的事实。
	 */
	private static void applyPage(AiContext base, String page) {
		if (page == null || page.isBlank() || page.equals(base.getPage())) {
			return;
		}
		base.setPage(page);
		Map<String, Object> extra = base.getExtra();
		if (extra != null) {
			extra.remove(EXTRA_UI_REGION_COUNT);
		}
	}

	/**
	 * 任务权威值（{@code type/currentStep/status}）。
	 *
	 * <p>用 {@link TaskService#findByIdRaw} 而不是 {@code findById}：披露只需要这三个标量，
	 * 不该为一次刷新把整份任务条目集合（含文件/暂存行）都水合出来。
	 */
	private Task requireTask(long taskId) {
		Optional<Task> found;
		try {
			found = this.taskService.findByIdRaw(taskId);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("读取任务 #" + taskId + " 失败，运行上下文保持不变：" + ex.getMessage(), ex);
		}
		if (found == null || found.isEmpty()) {
			throw new IllegalStateException("任务 #" + taskId + " 不存在（或已删除），运行上下文保持不变");
		}
		return found.get();
	}

	/** 任务类型 → 向导页（{@code navigate_to} 的 page 取值同源）。 */
	private static String pageOf(Task.TaskType type) {
		if (type == null) {
			return null;
		}
		return switch (type) {
			case EXPORT -> "export";
			case IMPORT -> "import";
		};
	}

	private static AiContext copyOf(AiContext source) {
		Map<String, Object> extra = source.getExtra() == null ? new LinkedHashMap<>()
				: new LinkedHashMap<>(source.getExtra());
		return AiContext.of(source.getPage(), source.getTaskType(), source.getStep(), source.getTaskId(),
				source.getTaskStatus(), extra);
	}

	/**
	 * 从创建回执里取 {@code #123}（取不到返回 null：调用方不得编造 ID）。
	 *
	 * <p>与 {@code SpToolCallingManager} 的"本轮是否已创建任务"同一解析口径（同一份回执、同一个号），
	 * 因此放在这里作为包内共享的唯一实现。
	 */
	static Long taskIdFromText(String text) {
		if (text == null) {
			return null;
		}
		int hash = text.indexOf('#');
		if (hash < 0) {
			return null;
		}
		int index = hash + 1;
		long value = 0L;
		boolean any = false;
		while (index < text.length() && Character.isDigit(text.charAt(index))) {
			value = value * 10 + (text.charAt(index) - '0');
			any = true;
			index++;
		}
		return any ? value : null;
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

	private static String text(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		String value = node.asText();
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** JSON 取值 → long（只认整数与数字文本；小数/布尔/对象/数组/空一律 null，口径同 ToolTargetResolver）。 */
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
