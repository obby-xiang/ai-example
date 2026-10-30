package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 表格工具披露开关（AI 表格工具治理方案 2026-10-22 §2；唯一数据源 = {@link AiProperties.Sheet}）。
 *
 * <h2>为什么是一个独立组件，而不是在工具方法体里判断</h2>
 * 开关的唯一语义是"<b>此刻是否披露</b>"（{@code ToolRegistry#forContext} 的裁剪），
 * 不是"调用了再拒"。若把判断写进 {@code AiTools} 的哨兵桩体，模型会看到一个"永远失败的工具"，
 * 白烧一轮 token，且用户看到的是"工具坏了"而不是"功能被关了"（方案 §2.3 明文禁止）。
 *
 * <p>
 * 因此本类只做一件事：{@code allows(name)} —— 并且 {@code ToolRegistry} 的披露谓词与执行兜底
 * <b>共用它</b>，从结构上排除"披露面与执行面两套口径"。
 *
 * <h2>四类工具与三档判定</h2>
 * <ul>
 * <li>{@link #READ_TOOLS}：随 {@code read-enabled}；</li>
 * <li>{@link #WRITE_TOOLS}：随 {@code write-enabled}（撤销随写闸走：没有写就没有可撤）；</li>
 * <li>{@link #DUAL_TOOLS}（当前仅 {@code normalize_column}）：<b>两闸同时开</b>才披露 ——
 * 它的 {@code commit=false} 与 {@code commit=true} 同名同形参，按读闸开放会让写闸形同虚设；</li>
 * <li>其它工具名：恒 true（本组件只治理表格工具，不碰任何别的工具）。</li>
 * </ul>
 *
 * <h2>有效上界</h2>
 * {@code sample-max-rows} / {@code projection-max-columns} 只能收紧、不能放大：
 * 有效值 = {@code min(配置值, 硬顶)}。硬顶是前端 R3 的物理边界（20 行 / 5 列），不随配置漂移。
 */
@Component
public final class SheetSwitches {

	/** 采样行数硬顶（与前端 {@code SHEET_TOOL_MAX_SAMPLE_ROWS} 同值，R3 物理边界）。 */
	public static final int HARD_MAX_SAMPLE_ROWS = 20;

	/** 投影列数硬顶（与前端 {@code SHEET_TOOL_MAX_COLUMNS} 同值）。 */
	public static final int HARD_MAX_PROJECTION_COLUMNS = 5;

	/** 纯读工具（5 个；随读闸）。工具名唯一注册定义在 {@link AiToolNames}。 */
	public static final Set<String> READ_TOOLS = Set.of(AiToolNames.READ_SHEET_SUMMARY, AiToolNames.AGGREGATE_SHEET,
			AiToolNames.INSPECT_COLUMNS, AiToolNames.REVIEW_CELLS, AiToolNames.FIND_ROWS);

	/** 纯写工具（3 个；随写闸。撤销随写闸：没有写就没有可撤）。 */
	public static final Set<String> WRITE_TOOLS = Set.of(AiToolNames.REPLACE_VALUES, AiToolNames.UPDATE_ROWS,
			AiToolNames.UNDO_LAST_SHEET_EDIT);

	/** 双档工具（1 个；`read AND write` 才披露）。 */
	public static final Set<String> DUAL_TOOLS = Set.of(AiToolNames.NORMALIZE_COLUMN);

	/** 全部受治理的表格工具（9 个；S-02 的矩阵基数）。 */
	public static final Set<String> SHEET_TOOLS = union();

	private final AiProperties.Sheet sheet;

	@Autowired
	public SheetSwitches(AiProperties properties) {
		this(properties == null ? null : properties.getSheet());
	}

	public SheetSwitches(AiProperties.Sheet sheet) {
		// 段缺失时按"缺省四键"装配而不是抛错：本类是决策组件，段缺失的启动失败由
		// AiProperties#verifySheet 负责（单一失败点，错误信息带键名与当前值）。
		this.sheet = sheet == null ? new AiProperties.Sheet() : sheet;
	}

	/** 缺省装配（读开 / 写关 / 20 / 5）—— 仅供单元测试与"未注入"的轻装配面。 */
	public static SheetSwitches defaults() {
		return new SheetSwitches(new AiProperties.Sheet());
	}

	private static Set<String> union() {
		Set<String> all = new java.util.HashSet<>(READ_TOOLS);
		all.addAll(WRITE_TOOLS);
		all.addAll(DUAL_TOOLS);
		return Set.copyOf(all);
	}

	public boolean readEnabled() {
		return this.sheet.isReadEnabled();
	}

	public boolean writeEnabled() {
		return this.sheet.isWriteEnabled();
	}

	/** 有效采样行数上界 = {@code min(配置值, 20)}。 */
	public int effectiveSampleMaxRows() {
		return Math.min(Math.max(1, this.sheet.getSampleMaxRows()), HARD_MAX_SAMPLE_ROWS);
	}

	/** 有效投影列数上界 = {@code min(配置值, 5)}。 */
	public int effectiveProjectionMaxColumns() {
		return Math.min(Math.max(1, this.sheet.getProjectionMaxColumns()), HARD_MAX_PROJECTION_COLUMNS);
	}

	/** 该工具此刻是否可以进披露面（唯一判定入口）。 */
	public boolean allows(String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return true;
		}
		if (READ_TOOLS.contains(toolName)) {
			return readEnabled();
		}
		if (DUAL_TOOLS.contains(toolName)) {
			return readEnabled() && writeEnabled();
		}
		if (WRITE_TOOLS.contains(toolName)) {
			return writeEnabled();
		}
		return true;
	}

	/**
	 * 被关掉的闸门键名（`read-enabled` / `write-enabled`）；未关或非受治理工具返回 {@code null}。
	 *
	 * <p>为什么需要"哪一侧"：{@link #allows} 只回答"能不能进披露面"，回执文案还要告诉管理员
	 * 该去看哪个键。双档工具（`normalize_column`）是"两闸同时开才披露"，<b>关哪侧就报哪侧</b> ——
	 * 按"是不是读工具"二分会把「读关写开」误报成 write-enabled（管理员去查会发现它本来就是 true）。
	 */
	public String disabledGate(String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return null;
		}
		if (READ_TOOLS.contains(toolName)) {
			return readEnabled() ? null : "read-enabled";
		}
		if (WRITE_TOOLS.contains(toolName)) {
			return writeEnabled() ? null : "write-enabled";
		}
		if (DUAL_TOOLS.contains(toolName)) {
			if (!readEnabled()) {
				return "read-enabled";
			}
			return writeEnabled() ? null : "write-enabled";
		}
		return null;
	}

	/** {@code /health.sheet} 的配置面（键名与设计稿 §2.5 逐字一致）。 */
	public Map<String, Object> health() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("readEnabled", readEnabled());
		out.put("writeEnabled", writeEnabled());
		out.put("sampleMaxRows", effectiveSampleMaxRows());
		out.put("projectionMaxColumns", effectiveProjectionMaxColumns());
		out.put("effectiveFrom", "config");
		return out;
	}

	/** 读侧闸关掉时的人话（回执与模型提示共用一份措辞）。 */
	public static String readDisabledText() {
		return "表格读取功能已被管理员关闭，本次未读任何数据。";
	}

	/** 写侧闸关掉时的人话（与既有写侧失败文案同句式「…，本次未做任何修改。」）。 */
	public static String writeDisabledText() {
		return "表格写入功能已被管理员关闭，本次未做任何修改。";
	}

	/** 双闸工具在"写闸关"时的措辞（它两档同名，故按写侧口径说明）。 */
	public static String dualDisabledText() {
		return writeDisabledText();
	}

}
