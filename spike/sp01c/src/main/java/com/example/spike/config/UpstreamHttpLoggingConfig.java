package com.example.spike.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

/**
 * 仅当 {@code spike.evidence-logging=true} 时生效：把发往 OpenAI 兼容端点的每次 HTTP 调用
 * 及其状态码、请求体、响应体打进日志。
 *
 * <p>
 * 目的：V-c5 需要"每轮上游 HTTP 状态 + 模型响应"的原始证据，而 Spring AI 1.1.8 的
 * {@code org.springframework.ai.openai=DEBUG} 实测不输出请求体/响应码（实测：开 DEBUG 后日志中
 * 无任何 DEBUG 行）。本类通过 Spring Boot 的 {@link RestClientCustomizer} 挂在 Spring AI 自动配置
 * 所使用的 {@code RestClient.Builder} 上，不侵入业务代码。
 *
 * <p>
 * 只观测不修改请求；响应体读一次后以缓冲区回放给调用方，保证 Spring AI 仍能正常解析。
 */
@Configuration
@ConditionalOnProperty(name = "spike.evidence-logging", havingValue = "true")
public class UpstreamHttpLoggingConfig {

	private static final Logger logger = LoggerFactory.getLogger("spike.upstream-http");

	private static final int MAX_BODY_CHARS = 4000;

	@Bean
	public RestClientCustomizer upstreamHttpEvidenceCustomizer() {
		return builder -> builder.requestInterceptor(new UpstreamHttpEvidenceInterceptor());
	}

	static class UpstreamHttpEvidenceInterceptor implements ClientHttpRequestInterceptor {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			long started = System.currentTimeMillis();
			ClientHttpResponse response = execution.execute(request, body);
			byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
			long elapsed = System.currentTimeMillis() - started;

			logger.info("UPSTREAM {} {} -> HTTP {} ({} ms, requestBytes={})", request.getMethod(),
					request.getURI().getPath(), response.getStatusCode().value(), elapsed, body.length);
			logger.info("UPSTREAM_REQUEST_BODY {}", truncate(new String(body, StandardCharsets.UTF_8)));
			logger.info("UPSTREAM_RESPONSE_BODY {}",
					truncate(new String(responseBody, StandardCharsets.UTF_8)));

			return new ReplayableClientHttpResponse(response, responseBody);
		}

		private static String truncate(String text) {
			return text.length() <= MAX_BODY_CHARS ? text : text.substring(0, MAX_BODY_CHARS) + "...[truncated]";
		}

	}

	/** 把已读过的响应体回放给调用方。 */
	static final class ReplayableClientHttpResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;

		private final byte[] body;

		ReplayableClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
			this.delegate = delegate;
			this.body = body;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return this.delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return this.delegate.getStatusText();
		}

		@Override
		public org.springframework.http.HttpHeaders getHeaders() {
			return this.delegate.getHeaders();
		}

		@Override
		public InputStream getBody() {
			return new ByteArrayInputStream(this.body);
		}

		@Override
		public void close() {
			this.delegate.close();
		}

	}

}
