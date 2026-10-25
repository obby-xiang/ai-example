package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>C2：出厂配置必须真正启用 park</b>（{@code app.ai.park.enabled=true}），并保留派发重试配置。
 *
 * <p>为什么单列一条：pick 的代码缺省是 false（单测/非 Spring 装配保持阻塞语义），
 * 生产行为<b>只由 {@code application.yml} 决定</b>。少写这个键（或写错缩进）时，
 * 生产会静默退回"驻留线程等待"——配置面与用户裁决相反，且没有任何运行期信号。
 */
@SuppressWarnings("unchecked")
class AiParkConfigTest {

	@Test
	void applicationYmlActuallyEnablesParkAndKeepsDispatchRetryKeys() throws Exception {
		Map<String, Object> park;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
			assertThat(in).as("application.yml 必须在测试类路径上").isNotNull();
			Map<String, Object> root = new Yaml().load(in);
			Map<String, Object> ai = (Map<String, Object>) ((Map<String, Object>) root.get("app")).get("ai");
			park = (Map<String, Object>) ai.get("park");
		}

		assertThat(park).as("application.yml 缺 app.ai.park 段").isNotNull();
		assertThat(park.get("enabled")).as("C2：生产必须实际启用 park（代码缺省 false 只服务单测）")
			.isEqualTo(Boolean.TRUE);

		AiProperties.Park codeDefaults = new AiProperties().getPark();
		assertThat(codeDefaults.getMaxDispatchRetries())
			.as("派发重试窗口必须覆盖 total-budget 300s：15 次 = 2+4+8+16+30×11 = 360s")
			.isEqualTo(15);
		assertThat(Long.parseLong(String.valueOf(park.get("max-dispatch-retries"))))
			.as("出厂配置与代码缺省同为 15（少写/写错会让生产退回 210s 窗口）")
			.isEqualTo(15);
		assertThat(Long.parseLong(String.valueOf(park.get("max-dispatch-retries"))))
			.isEqualTo(codeDefaults.getMaxDispatchRetries());
		assertThat(DurationStyle.detectAndParse(String.valueOf(park.get("dispatch-retry-delay"))))
			.isEqualTo(codeDefaults.getDispatchRetryDelay());
		assertThat(DurationStyle.detectAndParse(String.valueOf(park.get("dispatch-retry-max-delay"))))
			.isEqualTo(codeDefaults.getDispatchRetryMaxDelay());
	}

}
