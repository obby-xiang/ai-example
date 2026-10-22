package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code app.ai.sheet.*} 的启动期校验（AI 表格工具治理批；与 {@link AiPropertiesMemoryGuardTest} 同一姿态）。
 *
 * <p>
 * 两个上界键的坏值都不是"性能降级"，而是<b>闸门被静默救活</b>：0/负值会被 {@code capSheetResult}
 * 的 {@code Math.max(1, …)} 与投影逻辑救成 1 —— 配置面写"0 = 不采样"，运行面仍回 1 行，
 * 两个口径对不上。故启动即失败并点名键与当前值。
 *
 * <p>与之对照：<b>大于硬顶不是坏值</b>（本批口径是"只能收紧、不能放大"，值 &gt; 20/5 时按硬顶计，
 * 见 {@code SheetSwitchesTest}）—— 拒绝它只会让一次笔误把服务挡在门外。
 */
class AiPropertiesSheetGuardTest {

	private static AiProperties props() {
		return new AiProperties();
	}

	/** 默认配置必须能过闸（防"加了校验把默认值判死"）。 */
	@Test
	void defaultsPass() {
		AiProperties properties = props();
		assertThatCode(properties::verifySheet).doesNotThrowAnyException();
	}

	/** 四键默认值 = 读开 / 写关 / 20 / 5（2026-10-22 裁决行）。 */
	@Test
	void defaultsAreReadOpenWriteClosedAndCappedAtHardTops() {
		AiProperties.Sheet sheet = props().getSheet();

		assertThat(sheet.isReadEnabled()).as("读侧总闸默认开").isTrue();
		assertThat(sheet.isWriteEnabled()).as("写侧总闸默认关（改状态的通道默认关）").isFalse();
		assertThat(sheet.getSampleMaxRows()).as("上界键缺省即硬顶").isEqualTo(20);
		assertThat(sheet.getProjectionMaxColumns()).as("上界键缺省即硬顶").isEqualTo(5);
	}

	/** 采样行数为 0 = 被 capSheetResult 救成 1 行（配置面与运行面两套口径）⇒ 启动失败。 */
	@Test
	void nonPositiveSampleMaxRowsIsRejected() {
		AiProperties properties = props();
		properties.getSheet().setSampleMaxRows(0);

		assertThatThrownBy(properties::verifySheet).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.sheet.sample-max-rows");
	}

	@Test
	void negativeSampleMaxRowsIsRejected() {
		AiProperties properties = props();
		properties.getSheet().setSampleMaxRows(-1);

		assertThatThrownBy(properties::verifySheet).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.sheet.sample-max-rows")
			.hasMessageContaining("-1");
	}

	/** 投影列数为 0 = 被救成 1 列，与"缺省投影 = 主键列 + 前 4 列"的承诺不符 ⇒ 启动失败。 */
	@Test
	void nonPositiveProjectionMaxColumnsIsRejected() {
		AiProperties properties = props();
		properties.getSheet().setProjectionMaxColumns(0);

		assertThatThrownBy(properties::verifySheet).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.sheet.projection-max-columns");
	}

	/** 大于硬顶是<b>合法</b>配置（按硬顶收敛，见 SheetSwitches），不得在启动期拒绝。 */
	@Test
	void valuesAboveHardTopAreAcceptedAndClampedLater() {
		AiProperties properties = props();
		properties.getSheet().setSampleMaxRows(200);
		properties.getSheet().setProjectionMaxColumns(9);

		assertThatCode(properties::verifySheet).doesNotThrowAnyException();
	}

	/**
	 * S-06：{@code application.yml} 中**不出现**四键的值（只有注释）。
	 *
	 * <p>7 键显化纪律的机器判据：写出来就等于给配置面加了第二份事实源，改键要改两处，
	 * 而两处迟早不一致（"配置面与运行面说两套话"的经典成因）。
	 */
	@Test
	void ymlDoesNotCarryValuesForTheFourSheetKeys() throws IOException {
		String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
		List<String> keys = List.of("read-enabled", "write-enabled", "sample-max-rows",
				"projection-max-columns");

		for (String line : yml.split("\r?\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#") || trimmed.isEmpty()) {
				continue;
			}
			for (String key : keys) {
				// 只允许出现在注释里；出现 `key:` 且非注释即为"写了值"
				assertThat(trimmed.startsWith(key + ":"))
					.as("app.ai.sheet.%s 只应写注释、不写值：%s", key, trimmed)
					.isFalse();
			}
		}
		assertThat(Arrays.stream(yml.split("\r?\n")).anyMatch(line -> line.contains("app.ai.sheet.read-enabled")))
			.as("四键的显化注释必须存在（否则运维 grep 不到）")
			.isTrue();
	}

	/** 收紧方向的边界（1 行 / 1 列）必须合法：本批允许管理员把闸门收得最窄。 */
	@Test
	void tightestLegalValuesAreAccepted() {
		AiProperties properties = props();
		properties.getSheet().setSampleMaxRows(1);
		properties.getSheet().setProjectionMaxColumns(1);

		assertThatCode(properties::verifySheet).doesNotThrowAnyException();
	}

}
