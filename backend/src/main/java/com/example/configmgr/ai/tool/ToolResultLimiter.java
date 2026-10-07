package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具结果回填模型的裁剪器（DC-14 T2 / R10-1，{@code app.ai.tool-result.max-rows|max-chars}）。
 *
 * <h2>两条通道分离（T2 的形态要求）</h2>
 * <ul>
 * <li><b>给模型看的</b>：{@code role:tool} 消息的 {@code responseData} —— 经本类裁剪；
 * 调用点只有两处：{@code gate/SpToolCallingManager}（本轮循环内回填）与
 * {@code run/ResumeService}（续跑重建历史时按台账 {@code resultText} 重建），
 * 两处同源，不会出现"续跑后的历史比首轮长/短"的口径分叉；</li>
 * <li><b>给前端看的</b>：SSE {@code tool_result} 帧与 Redis 台账的 {@code resultText} ——
 * <b>全文不裁剪</b>（前端要展示完整结果、账本是"结果的事实源"；模型侧上限是上下文成本问题，
 * 不是数据完备性问题）。</li>
 * </ul>
 *
 * <h2>截断必须可被模型看见</h2>
 * 截断处追加<b>截断标记</b>（含原始行数/字符数与生效上限），否则模型会把"前 N 行"
 * 当成全部 —— 这类"静默截断"会让模型基于缺数据自信地下结论（比不截断更危险）。
 */
@Component
@RequiredArgsConstructor
public class ToolResultLimiter {

	/** 截断标记的固定前缀（测试与排障据此识别，勿改）。 */
	public static final String TRUNCATION_MARKER = "\n…[结果已截断：";

	private final AiProperties properties;

	/**
	 * 按配置裁剪一份<b>给模型看</b>的工具结果文本。
	 *
	 * <ol>
	 * <li>行数超 {@code maxRows} → 只留前 {@code maxRows} 行 + 截断标记；</li>
	 * <li>行数未超但字符数超 {@code maxChars} → 截断到 {@code maxChars} 字符 + 截断标记；</li>
	 * <li>两者都未超（或上限取 0/负数表示不限制）→ 原样返回。</li>
	 * </ol>
	 */
	public String forModel(String text) {
		if (text == null || text.isEmpty()) {
			return text == null ? "" : text;
		}
		int maxChars = this.properties.getToolResult().getMaxChars();
		int maxRows = this.properties.getToolResult().getMaxRows();
		int totalChars = text.length();
		int totalRows = countRows(text);

		if (maxRows > 0 && totalRows > maxRows) {
			String head = firstRows(text, maxRows);
			return head + marker(totalRows, totalChars, "仅前 " + maxRows + " 行");
		}
		if (maxChars > 0 && totalChars > maxChars) {
			return text.substring(0, maxChars) + marker(totalRows, totalChars, "仅前 " + maxChars + " 字符");
		}
		return text;
	}

	/** 是否被裁剪过（供诊断/帧字段标注；判据只认标记，避免"恰好等长"的误判）。 */
	public boolean truncated(String text) {
		return text != null && text.contains(TRUNCATION_MARKER);
	}

	private static String marker(int rows, int chars, String kept) {
		return TRUNCATION_MARKER + "原 " + rows + " 行 / " + chars + " 字符，" + kept
				+ "；完整结果见本轮工具调用记录（前端帧与台账不截断）。如需更多细节，请缩小查询范围或分页获取。]";
	}

	/**
	 * 行数 = 换行符数 + 1。
	 *
	 * <p>
	 * <b>两种换行形态都要数</b>：后端工具（当前端）的结果文本经官方
	 * {@code DefaultToolCallResultConverter} 序列化，换行在字符串里成了**字面两字符** {@code \n}
	 * （实测：{@code list_config_defs} 的 15 行结果在回填消息里是
	 * {@code "- CURRENCY…\\n- DOC_TYPE…"}，直接数 {@code '\n'} 会得到"1 行"，
	 * 行数上限形同虚设）。既然"行"的语义就是"分隔的行"，两种形态一并计。
	 */
	private static int countRows(String text) {
		int rows = 1 + countChar(text, '\n') + countEscapedNewlines(text);
		return rows;
	}

	/** 前 rows 行（在"\n"字符或字面 "\n" 两字符处切开）。 */
	private static String firstRows(String text, int rows) {
		int seen = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				seen++;
			}
			else if (text.charAt(i) == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'n') {
				seen++;
				i++;
			}
			if (seen == rows) {
				return text.substring(0, i + 1);
			}
		}
		return text;
	}

	private static int countChar(String text, char target) {
		int count = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == target) {
				count++;
			}
		}
		return count;
	}

	private static int countEscapedNewlines(String text) {
		int count = 0;
		for (int i = 0; i + 1 < text.length(); i++) {
			if (text.charAt(i) == '\\' && text.charAt(i + 1) == 'n') {
				count++;
				i++;
			}
		}
		return count;
	}

}
