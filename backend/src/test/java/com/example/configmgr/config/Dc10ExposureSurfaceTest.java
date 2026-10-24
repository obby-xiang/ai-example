package com.example.configmgr.config;

import com.example.configmgr.ai.kb.AiKbController;
import com.example.configmgr.ai.web.AiController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-10 面的映射口径（L-30 {@code GET /api/ai/history/{sessionId}} / L-59 {@code POST /api/ai/kb/reload}）。
 *
 * <p>本类只锁<b>端点路径</b>：这两个面无认证，不能被悄悄挪到另一个端口 / 另一条映射前缀上
 * （例如挪到管理端口就成了另一个暴露面）。绑定地址与 H2 控制台口径不在本类范围内 ——
 * 监听面恢复默认（全网卡）后，暴露面管理不属于本轮裁决。
 */
class Dc10ExposureSurfaceTest {

	@Test
	void dc10EndpointsStayOnTheDefaultApiSurface() throws Exception {
		Method history = AiController.class.getMethod("history", String.class, Integer.class, Integer.class);
		assertThat(requestPaths(AiController.class, history))
				.as("L-30：读历史仍在默认 /api 面")
				.contains("/api/ai/history/{sessionId}");

		Method reload = AiKbController.class.getMethod("reload");
		assertThat(requestPaths(AiKbController.class, reload))
				.as("L-59：强制重建索引仍在默认 /api 面")
				.contains("/api/ai/kb/reload");
	}

	// ───────────────────────── 辅助 ─────────────────────────

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
