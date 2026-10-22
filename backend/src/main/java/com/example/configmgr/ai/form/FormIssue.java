package com.example.configmgr.ai.form;

/**
 * 生成式表单的<b>结构化错误</b>：一条问题 = 归属字段 + 机器可读码 + 人读说明。
 *
 * <h2>为什么把字符串原因升级成三件套</h2>
 * 旧口径只回一段中文（{@code List<String>}）：模型能读懂，但<b>前端没法据此点亮具体字段</b>，
 * 只能把整段文字塞进卡级错误条；模型也没法用程序化判断决定"要不要改 schema 重发"。
 * 本记录把同一批事实拆成三个面：
 * <ul>
 * <li>{@link #key}：字段键（表单级 / 参数级问题为空串；无法确定字段时用 {@code fields[i]} 定位）；</li>
 * <li>{@link #code}：机器可读码（见 {@link FormIssueCodes}，前端据此决定字段红框与是否可重试）；</li>
 * <li>{@link #message}：人读说明（与旧 {@code schemaReasons/resultReasons} 的文案逐条同源，既有断言不降）。</li>
 * </ul>
 *
 * <p><b>不变量</b>：{@code message} 永远是给人/模型看的那一句；{@code code} 一旦发布就不再改名
 * （前端按码分支），新增语义只能新增码。
 *
 * @param key     归属字段键（无归属时为空串）
 * @param code    机器可读码（{@link FormIssueCodes} 的白名单之一）
 * @param message 人读说明
 */
public record FormIssue(String key, String code, String message) {

	public FormIssue {
		key = key == null ? "" : key;
		message = message == null ? "" : message;
	}

	/** 表单级（无字段归属）问题。 */
	public static FormIssue form(String code, String message) {
		return new FormIssue("", code, message);
	}

	/** 字段级问题。 */
	public static FormIssue field(String key, String code, String message) {
		return new FormIssue(key, code, message);
	}

}
