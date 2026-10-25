package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code app.ai.stream.*} 的启动期守卫（Redis 容量专项 A；与 {@code app.ai.limit.*} 同一 fail-fast 姿态）。
 *
 * <p>两个键的坏值都不是"性能降级"：
 * <ul>
 * <li>{@code byte-budget < 0}：负预算没有"关掉治理"的解释空间（关掉用 {@code 0}），
 * 只会让"预算/每条上界"变成负数、窗口静默落到下限 —— 配置面说"按字节"，运行面永远不按字节；</li>
 * <li>{@code index-prune-interval < 0}：负间隔让"距上次清理 ≥ 间隔"恒真 ⇒ 每次 {@code create}
 * 都全表扫索引（{@code SMEMBERS} + 逐成员 {@code EXISTS}），热路径变成 O(索引大小)。</li>
 * </ul>
 *
 * <p>读的是<b>绑定后</b>的值；方法包级可见，测试直调（与 {@link AiPropertiesLimitGuardTest} 同形）。
 */
class AiPropertiesStreamGuardTest {

	@Test
	void defaultsPassTheGuard() {
		AiProperties props = new AiProperties();

		assertThatCode(props::verifyStream).doesNotThrowAnyException();
		assertThat(props.getStream().getByteBudget()).as("默认 64MiB/轮（16GiB 部署下 ~20 并发的 8%）")
			.isEqualTo(64L * 1024 * 1024);
		assertThat(props.getStream().getIndexPruneInterval()).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void negativeByteBudgetFailsFastAndNamesTheKey() {
		AiProperties props = new AiProperties();
		props.getStream().setByteBudget(-1L);

		assertThatThrownBy(props::verifyStream)
			.as("0 才是'关闭字节治理'；负值只会让窗口静默落到下限")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.stream.byte-budget");
	}

	@Test
	void negativePruneIntervalFailsFastAndNamesTheKey() {
		AiProperties props = new AiProperties();
		props.getStream().setIndexPruneInterval(Duration.ofSeconds(-1));

		assertThatThrownBy(props::verifyStream)
			.as("负间隔 ⇒ 每次 create 都全表扫索引")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.stream.index-prune-interval");
	}

	/**
	 * 出厂 yml 与代码缺省必须是<b>同一个事实</b>（"只改一半"是这类容量键最典型的漂移形态：
	 * 配置面写 64MiB、运行面按字段初值跑）。
	 */
	@SuppressWarnings("unchecked")
	@Test
	void applicationYmlMatchesTheCodeDefaults() throws Exception {
		Map<String, Object> stream;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
			assertThat(in).as("application.yml 必须在测试类路径上").isNotNull();
			Map<String, Object> root = new Yaml().load(in);
			stream = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) root.get("app"))
				.get("ai")).get("stream");
		}

		AiProperties props = new AiProperties();
		assertThat(stream).as("application.yml 缺 app.ai.stream 段").isNotNull();
		assertThat(Long.parseLong(String.valueOf(stream.get("byte-budget"))))
			.as("byte-budget 是 long 字节数（不是 DataSize）")
			.isEqualTo(props.getStream().getByteBudget());
		assertThat(DurationStyle.detectAndParse(String.valueOf(stream.get("index-prune-interval"))))
			.isEqualTo(props.getStream().getIndexPruneInterval());
	}

}
