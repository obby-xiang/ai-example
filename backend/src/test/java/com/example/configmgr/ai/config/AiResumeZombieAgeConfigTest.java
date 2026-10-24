package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-09：{@code zombie-age} 必须大于两类<b>配置允许的合法静默</b>。
 *
 * <p>
 * 判死阈值比"工具执行上限"（{@code app.ai.tool.exec-timeout}）或"上游事件间静默上限"
 * （{@code app.ai.resilience.inter-event-timeout}）还短时，任何正在跑长工具 / 慢吐字的活轮
 * 都会被跨实例判成僵尸并接管 —— 同一轮被执行两次。故：
 * <ul>
 * <li>代码缺省与出厂 yml <b>两处都要改</b>（只改一处 = 配置面与运行面两套事实）；</li>
 * <li>阈值要留出合理余量（这里锁 ≥ 两类静默的最大值 + 30s）。</li>
 * </ul>
 */
class AiResumeZombieAgeConfigTest {

	@Test
	void codeDefaultLeavesRoomForToolAndInterEventSilence() {
		AiProperties properties = new AiProperties();

		assertThat(properties.getResume().getZombieAge())
			.as("代码缺省 zombie-age 必须 >= max(exec-timeout, inter-event-timeout) + zombie-margin")
			.isGreaterThanOrEqualTo(legalSilenceFloor(properties)
				.plus(properties.getResume().getZombieMargin()));
	}

	@Test
	void shippedYamlMatchesTheCodeDefaultAndLeavesRoomForLegalSilence() throws Exception {
		Map<String, Object> ai = aiSection();

		Duration yamlZombie = duration(map(ai, "resume"), "zombie-age");
		Duration yamlMargin = duration(map(ai, "resume"), "zombie-margin");
		Duration yamlExec = duration(map(ai, "tool"), "exec-timeout");
		Duration yamlInter = duration(map(ai, "resilience"), "inter-event-timeout");

		assertThat(yamlZombie)
			.as("出厂 yml 的 zombie-age 必须 >= 两类合法静默的最大值 + zombie-margin")
			.isGreaterThanOrEqualTo(max(yamlExec, yamlInter).plus(yamlMargin));
		assertThat(yamlZombie)
			.as("代码缺省与出厂 yml 必须是同一个事实（不能只改一半）")
			.isEqualTo(new AiProperties().getResume().getZombieAge());
		assertThat(yamlMargin)
			.as("余量本身也必须是代码缺省与出厂 yml 同一事实")
			.isEqualTo(new AiProperties().getResume().getZombieMargin());
	}

	private static Duration legalSilenceFloor(AiProperties properties) {
		return max(properties.getTool().getExecTimeout(), properties.getResilience().getInterEventTimeout());
	}

	private static Duration max(Duration left, Duration right) {
		return left.compareTo(right) >= 0 ? left : right;
	}

	private static Duration duration(Map<String, Object> section, String key) {
		return DurationStyle.detectAndParse(String.valueOf(section.get(key)));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> aiSection() throws Exception {
		try (InputStream in = AiResumeZombieAgeConfigTest.class.getClassLoader()
			.getResourceAsStream("application.yml")) {
			assertThat(in).as("application.yml 必须在测试类路径上").isNotNull();
			Map<String, Object> root = new Yaml().load(in);
			return map(map(root, "app"), "ai");
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> parent, String key) {
		Object value = parent.get(key);
		assertThat(value).as("application.yml 缺少 %s 段", key).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

}
