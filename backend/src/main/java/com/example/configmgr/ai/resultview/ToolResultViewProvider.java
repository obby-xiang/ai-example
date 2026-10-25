package com.example.configmgr.ai.resultview;

/**
 * 工具结果的<b>视图投影</b>扩展点（设计稿 §2.1 Vercel AI SDK 的 {@code toModelOutput} 姿态）。
 *
 * <h2>为什么按工具名自认领</h2>
 * 与 {@code FrontendToolGuard} / {@code ToolPreflightService} 同一体例：挂起/执行机制对
 * "哪个工具有视图"零知识，新增一种视图只需再加一个实现 Bean。
 * <b>未声明投影的工具一行不改</b>——帧上不多 {@code resultView} 键，前端走旧卡面。
 *
 * <h2>两个通道的关系（§5.3）</h2>
 * 结果是同一份真值的两个投影：
 * <ul>
 * <li><b>模型通道</b>：工具返回的 {@code resultText}（本接口<b>不参与</b>模型侧裁剪）；</li>
 * <li><b>视图通道</b>：本接口产出的 {@link ResultView}，只挂 {@code tool_result} 帧。</li>
 * </ul>
 * 实时帧必须从<b>结构化数据源</b>构造视图，不得把 {@code resultText} 反解回来 ——
 * 那会把"借文本当协议"的旧债原样搬进新契约（§1.2 缺口 A）。
 *
 * <p><b>历史归档是例外，且方向相反</b>（Task 6 / BZ-33）：归档时已经没有"当时的结构化数据源"了，
 * 再查一次业务库读到的是<b>今天</b>的数据，等于用当前库改写历史（刷新后卡面与同轮 assistant 文本
 * 自相矛盾）。因此归档通道必须从<b>当时那次调用的结果原文</b>取真值 —— 见
 * {@link #build(String, String, String, boolean)}。
 */
public interface ToolResultViewProvider {

	/** 是否由本实现为该工具出视图。 */
	boolean supports(String toolName);

	/**
	 * 构造实时帧的视图（确定性：同输入 ⇒ 同载荷）。
	 *
	 * @param toolCallId 本次调用的稳定 id（视图 {@code id} 取 {@code rv-<toolCallId>}）
	 * @param toolName   工具名（{@code supports} 已命中）
	 * @param resultText 工具结果原文（仅供"是否为空/是否陈述了截断"一类事实判断，<b>不做反解</b>）
	 * @return 视图；返回 {@code null} = 本次不出视图（旧卡面），异常也不得穿出执行链
	 */
	ResultView build(String toolCallId, String toolName, String resultText);

	/**
	 * 带通道标记的构造：{@code historical=true} 表示这是历史归档的<b>重放</b>，不是实时帧。
	 *
	 * <p>缺省实现忽略该标记并回落 {@link #build} —— 未声明归档语义的投影器行为逐字不变。
	 * 声明了归档语义的实现（如 {@code ListTasksResultViewProvider}）必须改用持久化的结果原文，
	 * <b>不得</b>再查实时业务库（那会用今天的数据改写当时的结论）。
	 *
	 * @param historical {@code true} = 历史归档重放
	 */
	default ResultView build(String toolCallId, String toolName, String resultText, boolean historical) {
		return build(toolCallId, toolName, resultText);
	}

}
