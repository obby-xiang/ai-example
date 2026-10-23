package com.example.configmgr.config;

import com.example.configmgr.ai.kb.AiKbController;
import com.example.configmgr.ai.web.AiController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-10 面的暴露口径（L-30 {@code GET /history/{sessionId}} / L-59 {@code POST /api/ai/kb/reload}）。
 *
 * <h2>本批的裁决口径</h2>
 * 「生产网关收口」是终局方向，本批不引 token 体系；落地的是<b>收窄监听面</b>：
 * 后端默认只绑回环（见 {@link ServerBindAddressConfigTest}），于是这两个面在本机 / CI 形态下
 * 不可能被网段直达 —— 收口由绑定层完成，而不是在 10 个端点上各写一遍鉴权。
 *
 * <h2>本类锁什么</h2>
 * <ol>
 * <li><b>没有第二个监听面</b>：yml 不含 {@code management.server.*}，actuator 不在 classpath
 *     ⇒ 不存在"主端口回环、管理端口仍对外"的绕过面；</li>
 * <li><b>H2 控制台不额外放行其它主机</b>：它挂在同一个连接器上、零认证且可执行 SQL，
 *     {@code web-allow-others} 必须是 false（缺省即 false），否则"把 0.0.0.0 显式打开"这条
 *     对外暴露路径会把库内控制台一起送出去；</li>
 * <li><b>两个 DC-10 端点仍挂在默认 {@code /api/**} 面</b>：它们不是管理端口上的独立映射，
 *     因此第 1 条的"唯一监听面"结论对它们成立。</li>
 * </ol>
 *
 * <p>残余面（如实登记，不在本批处理）：<b>同机多用户</b> —— 回环绑定只区分"哪台机器"，
 * 不区分"本机上的哪个用户/进程"；同机的其它用户仍可读写这两个面。
 * 彻底收口要做本机鉴权（token / unix socket / 反向代理前置鉴权），属网关收口终局方向。
 */
class Dc10ExposureSurfaceTest {

	@Test
	void noSecondManagementListenerIsConfigured() throws IOException {
		assertThat(applicationYamlPropertyNames())
				.as("management.server.* 一旦出现就是第二个监听面（主端口回环也挡不住它）")
				.noneMatch(name -> name.startsWith("management.server."));

		assertThat(ClassUtils.isPresent("org.springframework.boot.actuate.health.Health",
				Dc10ExposureSurfaceTest.class.getClassLoader()))
						.as("pom 无 spring-boot-starter-actuator：不存在 /actuator/** 暴露面")
						.isFalse();
	}

	@Test
	void h2ConsoleRefusesRequestsFromOtherHosts() throws IOException {
		StandardEnvironment environment = applicationYaml();

		assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class, false))
				.as("前置：控制台是开着的（README 的库内自查口）—— 本断言只为让下一条的前提可见")
				.isTrue();
		assertThat(environment.getProperty("spring.h2.console.settings.web-allow-others", Boolean.class, false))
				.as("H2 控制台零认证且可执行 SQL：不得额外放行其它主机（缺省 false，显式写了才生效）")
				.isFalse();
	}

	@Test
	void dc10EndpointsStayOnTheDefaultApiSurface() throws Exception {
		Method history = AiController.class.getMethod("history", String.class, Integer.class, Integer.class);
		assertThat(requestPaths(AiController.class, history))
				.as("L-30：读历史仍在默认 /api 面（与所有写端点同一个连接器）")
				.contains("/api/ai/history/{sessionId}");

		Method reload = AiKbController.class.getMethod("reload");
		assertThat(requestPaths(AiKbController.class, reload))
				.as("L-59：强制重建索引仍在默认 /api 面")
				.contains("/api/ai/kb/reload");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private static StandardEnvironment applicationYaml() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		for (PropertySource<?> source : new YamlPropertySourceLoader()
			.load("application", new ClassPathResource("application.yml"))) {
			environment.getPropertySources().addFirst(source);
		}
		return environment;
	}

	private static List<String> applicationYamlPropertyNames() throws IOException {
		List<String> names = new ArrayList<>();
		for (PropertySource<?> source : applicationYaml().getPropertySources()) {
			if (source instanceof EnumerablePropertySource<?> enumerable) {
				names.addAll(Arrays.asList(enumerable.getPropertyNames()));
			}
		}
		return names;
	}

	/** 类级前缀 + 方法级路径（Spring 自己的合并语义，含 {@code @GetMapping} 这类组合注解）。 */
	private static List<String> requestPaths(Class<?> type, Method method) {
		RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
		RequestMapping own = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
		assertThat(base).as("%s 缺类级 @RequestMapping", type.getSimpleName()).isNotNull();
		assertThat(own).as("%s 缺映射注解", method.getName()).isNotNull();

		List<String> paths = new ArrayList<>();
		String prefix = firstPath(base);
		for (String path : paths(own)) {
			paths.add(prefix + path);
		}
		return paths;
	}

	private static String firstPath(RequestMapping mapping) {
		List<String> paths = paths(mapping);
		return paths.isEmpty() ? "" : paths.get(0);
	}

	private static List<String> paths(RequestMapping mapping) {
		return mapping.path().length > 0 ? List.of(mapping.path()) : List.of(mapping.value());
	}

}
