package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工具结果回填模型的裁剪器（DC-14 T2 / R10-1；G5 起预算收敛到
 * {@code app.ai.result-budget.model.*}）。
 *
 * <h2>两条通道分离，但截断判定同源</h2>
 * <ul>
 * <li><b>给模型看的</b>：{@code role:tool} 消息的 {@code responseData} —— 经本类裁剪；
 * 调用点只有两处：{@code gate/SpToolCallingManager}（本轮循环内回填）与
 * {@code run/ResumeService}（续跑重建历史时按台账 {@code resultText} 重建），
 * 两处同源，不会出现"续跑后的历史比首轮长/短"的口径分叉；</li>
 * <li><b>给前端看的</b>：SSE {@code tool_result} 帧与 Redis 台账的 {@code resultText} ——
 * 帧里的 {@code result} 仍是<b>全文</b>；但帧必须能回答"模型看到的是不是全文"，
 * 故 {@code SseChatEmitter} 用<b>同一个本类实例/同一份判定</b>在帧上挂同一套截断字段
 * （{@link TruncationContract}）—— 模型侧 {@code truncated=true} 时帧侧绝不为 false。</li>
 * </ul>
 *
 * <h2>预算分档（{@code app.ai.result-budget.model.*}）</h2>
 * 具名工具走 {@code default-max-chars}（20000）；{@code per-tool} 逐工具覆盖；
 * <b>拿不到工具名/未知工具</b>走 {@code fallback-max-chars}（8000，旧口径兜底）。
 * 行数上限 200 仍然先行施加（JSON 转义形态的 {@code \n} 也计行）。
 *
 * <h2>截断必须可被模型看见，且不破坏结构</h2>
 * <ul>
 * <li>普通文本：头部保留 + <b>截断标记</b>（{@link #TRUNCATION_MARKER}），
 * <b>标记长度计入预算</b>（返回总长 ≤ 上限；上限小到装不下正文时只给标记，绝不静默丢弃）；</li>
 * <li>结构化结果（JSON）：<b>绝不按字符切</b>（切完就是非法 JSON，下游整段丢失），
 * 改为整体标记成一份合法 JSON（{@link TruncationContract#jsonMarker}，含 {@code preview} 头部分片）。</li>
 * </ul>
 *
 * <h2>挂账（本批不做）</h2>
 * 超大结果的 <b>spill</b>（模型只拿 handle + 预览，正文落对象存储/本地文件，模型按需分段取回）
 * 与 <b>SSE 帧分片/续帧协议</b>不在本期范围：前者要新存储面与工具协议扩展，后者要前后端同时改
 * 帧消费逻辑，工作量均超出"双通道对齐"本身。本类与 {@code FrameSizeLimiter} 已把"截断"表达成
 * 可替换的决策（{@link Truncation}）与统一字段，后续接 spill 时只需把 {@code text} 换成 handle 引用。
 */
@Component
public class ToolResultLimiter {

	/** 截断标记的固定前缀（测试与排障据此识别，勿改）。 */
	public static final String TRUNCATION_MARKER = "\n…[结果已截断：";

	/** 人读说明的固定前缀（{@code notice} 与标记正文同一句话）。 */
	private static final String NOTICE_PREFIX = "结果已截断：";

	private final AiProperties properties;

	/**
	 * 已注册工具目录（判"未知工具"用；null = 无注册表，按"具名即已知"处理）。
	 *
	 * <p>为什么需要它：{@code per-tool} 是显式清单，未列出的工具默认按 20000 处理；
	 * 但"具名却从未注册"（历史重放里已被删除的工具）应当收回兜底档 8000 —— 这类结果
	 * 通常不是当前工具集的产物，放大它只增加上下文风险。
	 *
	 * <p><b>为什么延迟取（不是构造器直注入）</b>：{@link ToolRegistry} 的 {@code @PostConstruct}
	 * 会逐个 {@code getBean} 扫描工具 bean，而工具 bean（如 {@code KbTools}）依赖本类 ——
	 * 构造期直注入就把这条链闭成环（Registry → KbTools → Limiter → Registry）。环上任何
	 * 创建顺序抖动都会以"bean 创建失败"被注册表的扫描兜底吞掉，表现为工具静默消失
	 * （2026-10-23 实测：KB 三工具 37→34）。延迟到判档时再取，环在结构上不存在。
	 */
	private final java.util.function.Supplier<ToolRegistry> toolRegistrySupplier;

	/** 无注册表装配（单测/非 Spring）：具名工具一律按默认档。 */
	public ToolResultLimiter(AiProperties properties) {
		this(properties, (ToolRegistry) null);
	}

	public ToolResultLimiter(AiProperties properties, ToolRegistry toolRegistry) {
		this.properties = properties;
		this.toolRegistrySupplier = () -> toolRegistry;
	}

	@Autowired
	public ToolResultLimiter(AiProperties properties, org.springframework.beans.factory.ObjectProvider<ToolRegistry> toolRegistry) {
		this.properties = properties;
		this.toolRegistrySupplier = toolRegistry::getIfAvailable;
	}

	/**
	 * 一次截断判定的完整结果（统一字段契约；帧侧同名复用）。
	 *
	 * @param truncated     是否截断
	 * @param strategy      {@link TruncationContract#STRATEGY_HEAD} 等（未截断为 null）
	 * @param originalChars 截断前字符数
	 * @param returnedChars 截断后返回的字符数
	 * @param notice        人读说明（未截断为 null）
	 * @param text          实际返回给模型的文本
	 */
	public record Truncation(boolean truncated, String strategy, int originalChars, int returnedChars, String notice,
			String text) {

		/** 未截断的判定（字段面与截断判定同一套，值为客观事实）。 */
		public static Truncation untouched(String text) {
			String value = text == null ? "" : text;
			return new Truncation(false, null, value.length(), value.length(), null, value);
		}
	}

	/**
	 * 模型侧的预算快照（行/字符）—— <b>单一事实源</b>仍是 {@code app.ai.result-budget.model.*}，
	 * 这里只是把 {@link #forModel} 实际会用到的两个数暴露出来，供"分页类工具"按同一口径
	 * 派生页大小（L-87：{@code kb_read_doc} 的一页必须整页可见，否则截断会丢结构尾巴）。
	 *
	 * @param maxRows  行数上限（0 = 不限）
	 * @param maxChars 字符上限（0 = 不限；已按工具名解析 per-tool/default/fallback 三档）
	 */
	public record ModelBudget(int maxRows, int maxChars) {
	}

	/** 按工具名取模型侧预算（判定口径与 {@link #forModel} 逐字一致）。 */
	public ModelBudget modelBudget(String toolName) {
		AiProperties.ResultBudget.Model model = this.properties.getResultBudget().getModel();
		return new ModelBudget(model.getMaxRows(), maxCharsFor(toolName, model));
	}

	/**
	 * 行数口径（与裁剪判定同一实现：真换行与字面 {@code \n} 都算）。
	 *
	 * <p>公开它是为了让分页类工具能在<b>回填前</b>用同一把尺子复核"这一页塞不塞得下"，
	 * 而不是等本类去切。
	 */
	public static int rowsOf(String text) {
		return text == null ? 0 : countRows(text);
	}

	/**
	 * 按配置裁剪一份<b>给模型看</b>的工具结果文本。
	 *
	 * @param toolName 工具名（决定分档；null/空白/未注册 ⇒ 兜底档 8000）
	 * @param text     原始结果文本
	 */
	public Truncation forModel(String toolName, String text) {
		if (text == null || text.isEmpty()) {
			return Truncation.untouched(text);
		}
		AiProperties.ResultBudget.Model model = this.properties.getResultBudget().getModel();
		int maxRows = model.getMaxRows();
		int maxChars = maxCharsFor(toolName, model);
		int originalChars = text.length();

		if (TruncationContract.looksStructured(text)) {
			// 结构化结果：行数语义不适用（一行 JSON），只按字符档判
			if (maxChars <= 0 || originalChars <= maxChars) {
				return Truncation.untouched(text);
			}
			return structured(text, maxChars);
		}

		int totalRows = countRows(text);
		boolean rowsCut = maxRows > 0 && totalRows > maxRows;
		String kept = rowsCut ? firstRows(text, maxRows) : text;
		boolean charsCut = maxChars > 0 && kept.length() > maxChars;
		if (!rowsCut && !charsCut) {
			return Truncation.untouched(text);
		}

		String notice = notice(originalChars, totalRows, kept.length(), maxRows, maxChars, rowsCut);
		if (maxChars > 0) {
			// 标记长度计入预算：正文只拿"上限 − 标记"的额度
			int budget = maxChars - marker(notice).length();
			kept = cutChars(kept, Math.max(0, budget));
			notice = notice(originalChars, totalRows, kept.length(), maxRows, maxChars, rowsCut);
			int second = maxChars - marker(notice).length();
			if (kept.length() > second) {
				kept = cutChars(kept, Math.max(0, second));
				notice = notice(originalChars, totalRows, kept.length(), maxRows, maxChars, rowsCut);
			}
		}
		String visited = kept + marker(notice);
		return new Truncation(true, TruncationContract.STRATEGY_HEAD, originalChars, visited.length(), notice,
				visited);
	}

	/** 兼容口（无工具名 ⇒ 兜底档）。 */
	public String forModel(String text) {
		return forModel(null, text).text();
	}

	/** 是否被裁剪过（供诊断/帧字段标注；判据只认标记，避免"恰好等长"的误判）。 */
	public boolean truncated(String text) {
		return text != null && text.contains(TRUNCATION_MARKER);
	}

	private int maxCharsFor(String toolName, AiProperties.ResultBudget.Model model) {
		if (toolName == null || toolName.isBlank()) {
			return model.getFallbackMaxChars();
		}
		Integer explicit = model.getPerTool() == null ? null : model.getPerTool().get(toolName);
		if (explicit != null) {
			return explicit;
		}
		ToolRegistry registry = this.toolRegistrySupplier.get();
		if (registry != null && registry.getMeta(toolName) == null) {
			return model.getFallbackMaxChars();
		}
		return model.getDefaultMaxChars();
	}

	/**
	 * 结构化结果的整体标记（<b>不</b>按字符切 JSON）。
	 *
	 * <p>标记本身是合法 JSON；{@code preview} 是原文的头部分片（普通字符串字段，转义由
	 * Jackson 负责），既让模型能判读"这大概是什么"，又不产生半截 JSON。
	 */
	private static Truncation structured(String text, int maxChars) {
		int originalChars = text.length();
		String notice = "结构化结果（JSON）超限：原 " + originalChars + " 字符 > 上限 " + maxChars
				+ " 字符，未做字符截断（截断会产生非法 JSON）；已整体标记并保留头部预览，"
				+ "完整结果见本轮工具调用记录。如需更多细节，请缩小查询范围或分页获取。";
		String marker = TruncationContract.jsonMarker("", originalChars, notice);
		int previewBudget = maxChars - marker.length();
		String preview = previewBudget > 0 ? cutChars(text, previewBudget) : "";
		for (int i = 0; i < 4; i++) {
			marker = TruncationContract.jsonMarker(preview, originalChars, notice);
			if (marker.length() <= maxChars) {
				break;
			}
			int over = marker.length() - maxChars;
			preview = cutChars(preview, Math.max(0, preview.length() - over));
		}
		marker = TruncationContract.jsonMarker(preview, originalChars, notice);
		return new Truncation(true, TruncationContract.STRATEGY_HEAD, originalChars, marker.length(), notice,
				marker);
	}

	private static String marker(String notice) {
		String body = notice.startsWith(NOTICE_PREFIX) ? notice.substring(NOTICE_PREFIX.length()) : notice;
		return TRUNCATION_MARKER + body + "]";
	}

	private static String notice(int originalChars, int totalRows, int returnedChars, int maxRows, int maxChars,
			boolean rowsCut) {
		String keptDescription = rowsCut ? "仅前 " + maxRows + " 行" : "共 " + totalRows + " 行";
		return NOTICE_PREFIX + "原 " + totalRows + " 行 / " + originalChars + " 字符，" + keptDescription + "，返回 " + returnedChars
				+ " 字符（上限：" + (maxRows > 0 ? maxRows + " 行" : "行数不限") + " / "
				+ (maxChars > 0 ? maxChars + " 字符" : "字符不限") + "；strategy="
				+ TruncationContract.STRATEGY_HEAD + "）。完整结果见本轮工具调用记录（台账为全文）；"
				+ "如需更多细节，请缩小查询范围或分页获取。";
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

	/** 按字符截断到 budget（不切断代理对）。 */
	private static String cutChars(String text, int budget) {
		if (text == null || budget <= 0) {
			return "";
		}
		if (text.length() <= budget) {
			return text;
		}
		int end = budget;
		if (Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end);
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
