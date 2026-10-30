package com.example.configmgr.config;

import com.example.configmgr.ai.web.AiController;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>C-1（返修第 2 轮）</b>：reattach 的归档截断响应头必须对跨源前端<b>可读</b>。
 *
 * <h2>为什么需要这条用例</h2>
 * 浏览器只在 {@code Access-Control-Expose-Headers} 里列出的自定义响应头才能被 JS 读到；
 * 没列出时 {@code fetch} 拿到的 {@code headers.get('archive-trimmed')} 恒为 {@code null} ——
 * 后端"发出了"、前端"永远看不到"，表现为静默降级（用户看不到"不可完整恢复"的提示）。
 * 前端用 <b>fetch</b> 消费 SSE（不是 EventSource），因此这条 CORS 配置是契约的一部分。
 */
class CorsExposedHeadersTest {

	@Test
	void archiveTrimHeadersAreExposedToCrossOriginFrontends() {
		CorsConfiguration config = WebConfig.corsConfiguration();

		List<String> exposed = config.getExposedHeaders();
		assertThat(exposed).as("必须显式暴露三个归档截断头（否则前端读不到）")
			.contains(AiController.HEADER_ARCHIVE_TRIMMED, AiController.HEADER_ARCHIVE_FROM,
					AiController.HEADER_ARCHIVE_TO);
		assertThat(config.getAllowCredentials()).as("fetch 带凭据（身份头），暴露头与之同时生效").isTrue();
	}

}
