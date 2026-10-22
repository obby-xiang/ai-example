package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格开关的判定矩阵（AI 表格工具治理方案 2026-10-22 §2.7 的 S-01 / S-02 / S-04）。
 *
 * <h2>为什么矩阵要逐格断言</h2>
 * 开关的价值全在"披露面 = 执行面"这一条上（{@link ToolRegistry} 的 {@code forContext} 与
 * {@code matchesContext} 共用 {@link SheetSwitches#allows}）。一旦某格判错，症状不是报错而是
 * "工具忽然不见了"或"该关的没关"——只有把 9 工具 × 4 开关组合全部钉住，才谈得上防回潮。
 *
 * <p>矩阵基数是 <b>9 个表格工具</b>（不是设计稿写 8 个）：实际入库的是读 5 + 写 3 + 撤销 1
 * （{@code find_rows} 是读侧第 5 个），逐格 = 9 × 4 = 36 例。
 */
class SheetSwitchesTest {

	private static SheetSwitches switches(boolean read, boolean write) {
		AiProperties properties = new AiProperties();
		properties.getSheet().setReadEnabled(read);
		properties.getSheet().setWriteEnabled(write);
		return new SheetSwitches(properties);
	}

	/** S-01：缺省读开 / 写关。 */
	@Test
	void defaultsAreReadOpenAndWriteClosed() {
		SheetSwitches defaults = new SheetSwitches(new AiProperties());

		assertThat(defaults.readEnabled()).isTrue();
		assertThat(defaults.writeEnabled()).isFalse();
		for (String read : SheetSwitches.READ_TOOLS) {
			assertThat(defaults.allows(read)).as("缺省披露读工具 %s", read).isTrue();
		}
		for (String write : SheetSwitches.WRITE_TOOLS) {
			assertThat(defaults.allows(write)).as("缺省不披露写工具 %s", write).isFalse();
		}
		assertThat(defaults.allows("normalize_column")).as("双档工具缺省不披露（写闸关）").isFalse();
	}

	/**
	 * S-02：9 工具 × 4 开关组合逐格对账。
	 *
	 * <p>四档期望：读工具 = read；写 3 工具 + 撤销 = write；{@code normalize_column} = read AND write。
	 */
	@ParameterizedTest(name = "read={0} write={1} ⇒ {2} 只读 / {3} 写 / {4} 双档")
	@CsvSource({
			"true,  true,  true,  true,  true",
			"true,  false, true,  false, false",
			"false, true,  false, true,  false",
			"false, false, false, false, false" })
	void matrixMatchesPerToolClass(boolean read, boolean write, boolean expectRead, boolean expectWrite,
			boolean expectDual) {
		SheetSwitches switches = switches(read, write);

		for (String name : SheetSwitches.READ_TOOLS) {
			assertThat(switches.allows(name)).as("读工具 %s", name).isEqualTo(expectRead);
		}
		for (String name : SheetSwitches.WRITE_TOOLS) {
			assertThat(switches.allows(name)).as("写工具 %s", name).isEqualTo(expectWrite);
		}
		for (String name : SheetSwitches.DUAL_TOOLS) {
			assertThat(switches.allows(name)).as("双档工具 %s（读 AND 写）", name).isEqualTo(expectDual);
		}
		assertThat(SheetSwitches.READ_TOOLS).hasSize(5);
		assertThat(SheetSwitches.WRITE_TOOLS).hasSize(3);
		assertThat(SheetSwitches.DUAL_TOOLS).hasSize(1);
		assertThat(SheetSwitches.SHEET_TOOLS).hasSize(9);
	}

	/** 双档工具的半开状态必须被排除：写闸关时连只读建议档一起消失（不取"建议档随读闸"）。 */
	@Test
	void dualToolIsHiddenWhenOnlyTheReadGateIsOpen() {
		SheetSwitches readOnly = switches(true, false);

		assertThat(readOnly.allows("normalize_column")).isFalse();
		assertThat(readOnly.allows("read_sheet_summary")).isTrue();
		assertThat(switches(true, true).allows("normalize_column")).isTrue();
	}

	/** 撤销随写闸走：没有写就没有可撤（否则模型会去撤销一个它做不了的改动的历史）。 */
	@Test
	void undoFollowsTheWriteGate() {
		assertThat(switches(true, false).allows("undo_last_sheet_edit")).isFalse();
		assertThat(switches(false, true).allows("undo_last_sheet_edit")).isTrue();
	}

	/** 本组件只治理表格工具：其它工具名恒放行（含 null/空名，未登记元数据不是"越权"的证据）。 */
	@Test
	void nonSheetToolsAreNeverTouched() {
		SheetSwitches allOff = switches(false, false);

		for (String name : List.of("list_config_defs", "start_export", "ui_snapshot", "generative_form")) {
			assertThat(allOff.allows(name)).as("%s 不受表格开关治理", name).isTrue();
		}
		assertThat(allOff.allows(null)).isTrue();
		assertThat(allOff.allows("")).isTrue();
	}

	/** S-04：值 &gt; 硬顶时有效值 = 硬顶（"配置放大采样"在结构上不可能）。 */
	@Test
	void effectiveValuesNeverExceedHardTops() {
		AiProperties properties = new AiProperties();
		properties.getSheet().setSampleMaxRows(200);
		properties.getSheet().setProjectionMaxColumns(9);
		SheetSwitches switches = new SheetSwitches(properties);

		assertThat(switches.effectiveSampleMaxRows()).isEqualTo(SheetSwitches.HARD_MAX_SAMPLE_ROWS).isEqualTo(20);
		assertThat(switches.effectiveProjectionMaxColumns())
			.isEqualTo(SheetSwitches.HARD_MAX_PROJECTION_COLUMNS).isEqualTo(5);
	}

	/** 收紧方向如实生效（管理员配 10 行 / 3 列 ⇒ 有效值就是 10 / 3）。 */
	@Test
	void tighterValuesAreHonoured() {
		AiProperties properties = new AiProperties();
		properties.getSheet().setSampleMaxRows(10);
		properties.getSheet().setProjectionMaxColumns(3);
		SheetSwitches switches = new SheetSwitches(properties);

		assertThat(switches.effectiveSampleMaxRows()).isEqualTo(10);
		assertThat(switches.effectiveProjectionMaxColumns()).isEqualTo(3);
	}

	/** {@code /health.sheet} 的配置面：键名与设计稿 §2.5 逐字一致，且回显<b>有效值</b>。 */
	@Test
	void healthEchoesEffectiveValues() {
		AiProperties properties = new AiProperties();
		properties.getSheet().setSampleMaxRows(200);
		properties.getSheet().setWriteEnabled(true);
		Map<String, Object> health = new SheetSwitches(properties).health();

		assertThat(health).containsEntry("readEnabled", true)
			.containsEntry("writeEnabled", true)
			// 关键：报的是生效值（20），不是配置原值（200）—— 否则前端会以为还能取 200 行
			.containsEntry("sampleMaxRows", 20)
			.containsEntry("projectionMaxColumns", 5)
			.containsEntry("effectiveFrom", "config");
	}

	/** 段缺失（绑定为 null）按缺省四键装配，不抛错（失败点单列在 AiProperties#verifySheet）。 */
	@Test
	void nullSectionFallsBackToDefaults() {
		SheetSwitches switches = new SheetSwitches((AiProperties.Sheet) null);

		assertThat(switches.readEnabled()).isTrue();
		assertThat(switches.writeEnabled()).isFalse();
		assertThat(switches.effectiveSampleMaxRows()).isEqualTo(20);
	}

	/** 两份人话文案：读闸关说"未读任何数据"，写闸关说"未做任何修改"（同句式）。 */
	@Test
	void disabledTextsAreHonestAboutWhatDidNotHappen() {
		assertThat(SheetSwitches.readDisabledText()).contains("已被管理员关闭").contains("未读任何数据");
		assertThat(SheetSwitches.writeDisabledText()).contains("已被管理员关闭").contains("未做任何修改");
	}

	/**
	 * 关闸归因（{@link SheetSwitches#disabledGate(String)}）：回执文案点名**实际关掉的那一侧**。
	 *
	 * <p>双档工具是重点：`normalize_column` 要两闸同开，所以「读关写开」与「读开写关」都必须
	 * 报出真正关着的那扇门 —— 按"是不是读工具"二分会把前者误报成 write-enabled。
	 */
	@Test
	void disabledGateNamesTheSideThatIsActuallyClosed() {
		AiProperties.Sheet readClosed = new AiProperties.Sheet();
		readClosed.setReadEnabled(false);
		readClosed.setWriteEnabled(true);
		SheetSwitches readGateClosed = new SheetSwitches(readClosed);
		assertThat(readGateClosed.disabledGate("read_sheet_summary")).isEqualTo("read-enabled");
		assertThat(readGateClosed.disabledGate("normalize_column")).as("读关写开：双档按读侧报")
				.isEqualTo("read-enabled");
		assertThat(readGateClosed.disabledGate("update_rows")).as("写闸开着 ⇒ 写工具不被拦").isNull();

		AiProperties.Sheet writeClosed = new AiProperties.Sheet();
		writeClosed.setReadEnabled(true);
		writeClosed.setWriteEnabled(false);
		SheetSwitches writeGateClosed = new SheetSwitches(writeClosed);
		assertThat(writeGateClosed.disabledGate("update_rows")).isEqualTo("write-enabled");
		assertThat(writeGateClosed.disabledGate("normalize_column")).isEqualTo("write-enabled");
		assertThat(writeGateClosed.disabledGate("find_rows")).as("读闸开着 ⇒ 读工具不被拦").isNull();

		assertThat(writeGateClosed.disabledGate("list_config_defs")).as("非表格工具不归本组件管").isNull();
		assertThat(writeGateClosed.disabledGate(null)).isNull();
	}

}
