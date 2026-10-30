package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.slot.TaskIntent;
import com.example.configmgr.ai.tool.AiContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>宿主确定性的任务类型表单</b>：用户只说"创建/新建任务"、类型又说不出时，由宿主先挂起一张
 * 单选 {@code EXPORT/IMPORT}（{@code purpose=TASK_TYPE}），<b>不再把这一问留给模型</b>。
 *
 * <h2>被替换掉的自由裁量</h2>
 * 同一句话下模型可能直接写成文字反问（"请问要创建导出还是导入任务？"）。那是把结构化缺口退回
 * 开放式文本：用户得打字、模型还得再解析一次，且两边都可能跑偏。用户口径是确定性的：
 * <b>新建意图 + 类型未知 ⇒ 宿主直接发单选</b>。
 *
 * <h2>触发判据（全部成立才挂起）</h2>
 * <ol>
 * <li><b>主动新建意图</b>：用户原话命中 {@link TaskIntent#isNewTaskIntent}（问规则/否定一律不算）；</li>
 * <li><b>本轮原话没说类型</b>：{@link TaskIntent#explicitTaskType} 为 {@code null}（导出/导入要<b>明说</b>）
 * —— 明说时直接 {@code create_task}，不弹表。<b>只看本轮用户原话</b>：
 * {@code context.getTaskType()}（旧任务页面的类型）与 {@code extra[taskSlots].taskType} 都属于<b>当前
 * 打开的那条旧任务</b>，不得当作新任务的类型 —— 也就是说，在旧 EXPORT 任务的向导页里说"创建任务"
 * 仍必须弹表；说"创建导入任务"才不弹（导入是新任务自己的类型，不是旧页面的）。</li>
 * <li><b>本轮还没建成任务</b>：台账里没有成功的 {@code create_task}（建过就不缺类型了）；</li>
 * <li><b>同一轮只问一次</b>：{@code toolCallId} 由 {@code runId} 决定，待决条目里已有同号即视为已挂过
 * （刷新恢复、重挂都不会再来一张）。</li>
 * </ol>
 *
 * <p><b>与放行闸门的分工</b>：{@link GenerativeFormGuard} 仍按槽位拒绝<b>模型自发</b>的类型表单
 * （槽位已定 = 模型不该再问）；宿主只负责"这一次由谁来问"，所以它的判据是"本轮原话"而不是旧任务
 * 的权威值。两边判断对象不同，不存在两套互相矛盾的口径。
 *
 * <h2>接入点与"不新增交互形态"</h2>
 * 挂在首轮 {@code store.create} 之后、驱动模型之前：类型未知时这一轮本来就<b>没有</b>可执行的动作
 * （{@code create_task} 缺必填的 type），先问一句是这一轮最短的路径。挂起面完全复用
 * {@link HostForm}（{@code saveSuspended} → {@code suspended} 帧 → 请求帧 → 收流），
 * 用户的选择经既有 {@code /api/ai/frontend-tool-result} 回灌，下一拍模型拿到的就是一条
 * {@code {"type":"EXPORT"}} 的工具结果 —— 确认卡（{@code create_task}）与 HITL 一个字都没改。
 */
@Slf4j
@Component
public class TaskTypeForm extends HostForm {

	/** 表单唯一字段的 key（回灌值形如 {@code {"type":"EXPORT"}}）。 */
	public static final String FIELD_KEY = "type";

	/** 表单标题（与提示词同一语言）。 */
	public static final String TITLE = "选择任务类型";

	/** 字段标签。 */
	public static final String FIELD_LABEL = "任务类型";

	/** 英文标题（由 {@link ReplyLanguage} 选择，不新增翻译体系）。 */
	public static final String TITLE_EN = "Select task type";

	/** 英文字段标签。 */
	public static final String FIELD_LABEL_EN = "Task type";

	/** 宿主类型表单的 {@code toolCallId} 前缀（后接 runId：同一轮只会问一次）。 */
	public static final String TOOL_CALL_ID_PREFIX = "host-type-form-";

	@Autowired
	public TaskTypeForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
			AiProperties properties) {
		super(store, registry, gate, codec, properties);
	}

	/** 宿主为某一轮生成的类型表单 {@code toolCallId}（确定性 = 天然去重）。 */
	public static String toolCallId(String runId) {
		return TOOL_CALL_ID_PREFIX + runId;
	}

	/**
	 * 判据成立时挂起类型单选。
	 *
	 * @param runId       本轮 id
	 * @param context     本轮上下文（含槽位 {@code extra[taskSlots]}）
	 * @param userMessage 本轮用户原文
	 * @param history     交棒历史（已含本轮用户消息）
	 * @return {@code true} = 已挂起：调用方本拍<b>不得</b>再驱动模型
	 */
	public boolean parkIfDue(String runId, AiContext context, String userMessage, List<Message> history) {
		if (runId == null || context == null || history == null) {
			return false;
		}
		if (!parkEnabled()) {
			// 阻塞式等待不承接这条流程（见 HostForm 类注：不新增中断路径）
			return false;
		}
		try {
			Optional<String> arguments = argumentsIfDue(runId, context, userMessage);
			if (arguments.isEmpty()) {
				return false;
			}
			return park(runId, history, frontendForm(toolCallId(runId), arguments.get()));
		}
		catch (RuntimeException ex) {
			// fail-safe：这是"让交互确定"的增强，不是这一轮的判定面
			log.warn("宿主任务类型表单未能挂起（按既有路径继续）runId={}：{}", runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 判据 + 表单正文（不含任何外置写）。
	 *
	 * <p>{@code context} 刻意<b>不</b>参与类型判定：它带着的是"当前打开的那条旧任务"的权威值
	 * （{@code taskType} 与 {@code extra[taskSlots]}），拿它判<b>新任务</b>的类型就是让旧任务参数
	 * 决定新任务。保留这个参数只为与另外两张宿主表单同形（调用点一处、判据一处）。
	 */
	Optional<String> argumentsIfDue(String runId, AiContext context, String userMessage) {
		if (!TaskIntent.isNewTaskIntent(userMessage)) {
			return Optional.empty();
		}
		if (TaskIntent.explicitTaskType(userMessage) != null) {
			// 本句已明说导出/导入 ⇒ 直接 create_task：类型不是缺口。
			// 只看**本轮用户原文**：旧任务页面的 taskType 与槽位都属于那条旧任务，不得带入新任务
			// （在旧 EXPORT 页说"创建任务"仍必须弹表，否则新任务会被旧任务的类型决定）。
			return Optional.empty();
		}
		if (createdTaskInRun(runId) != null) {
			// 本轮已经建成任务：类型不是缺口
			return Optional.empty();
		}
		if (alreadyIssued(runId, toolCallId(runId))) {
			return Optional.empty();
		}
		return formArguments(buildForm(ReplyLanguage.current()));
	}

	/** 单个 enum 单选 {@code EXPORT/IMPORT}（形状与 {@link GenerativeFormRules#PURPOSE_TASK_TYPE} 一致）。 */
	private static Map<String, Object> buildForm(ReplyLanguage language) {
		boolean english = language != null && language.english();
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("key", FIELD_KEY);
		field.put("label", english ? FIELD_LABEL_EN : FIELD_LABEL);
		field.put("type", "enum");
		field.put("required", true);
		field.put("options", english
				? List.of(option("EXPORT", "Export"), option("IMPORT", "Import"))
				: List.of(option("EXPORT", "导出"), option("IMPORT", "导入")));

		Map<String, Object> form = new LinkedHashMap<>();
		form.put("scenario", "CLARIFY");
		form.put("purpose", GenerativeFormRules.PURPOSE_TASK_TYPE);
		form.put("title", english ? TITLE_EN : TITLE);
		form.put("fields", List.of(field));
		return form;
	}

	private static Map<String, Object> option(String value, String label) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("value", value);
		option.put("label", label);
		return option;
	}

}
