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
 * 因此实现必须从<b>结构化数据源</b>构造视图，不得把 {@code resultText} 反解回来 ——
 * 那会把"借文本当协议"的旧债原样搬进新契约（§1.2 缺口 A）。
 */
public interface ToolResultViewProvider {

	/** 是否由本实现为该工具出视图。 */
	boolean supports(String toolName);

	/**
	 * 构造视图（确定性：同输入 ⇒ 同载荷）。
	 *
	 * @param toolCallId 本次调用的稳定 id（视图 {@code id} 取 {@code rv-<toolCallId>}）
	 * @param toolName   工具名（{@code supports} 已命中）
	 * @param resultText 工具结果原文（仅供"是否为空/是否陈述了截断"一类事实判断，<b>不做反解</b>）
	 * @return 视图；返回 {@code null} = 本次不出视图（旧卡面），异常也不得穿出执行链
	 */
	ResultView build(String toolCallId, String toolName, String resultText);

}
