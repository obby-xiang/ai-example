package com.example.configmgr.ai.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B18（{@code /test} 四档错误面）＋ B19 的出站侧（探活前同样过私网闸）。
 *
 * <p>
 * 用 JDK 自带的 {@link HttpServer} 起一个真的上游（不 mock HTTP 客户端）：四档错误必须来自
 * <b>真实的状态码/真实的超时</b>，而不是"我方 if 分支自己回了个码"。
 *
 * <h2>四档映射（设计稿 §3.5）</h2>
 * <ul>
 * <li>404 / 405 ⇒ {@code MODELS_UNSUPPORTED}（该端点没实现 /models，可手填模型 ID）；</li>
 * <li>401 / 403 ⇒ {@code AUTH_FAILED}（鉴权失败，请检查 key）；</li>
 * <li>超时 / 连不上 ⇒ {@code UNREACHABLE}；</li>
 * <li>私网且开关关闭 ⇒ {@code BLOCKED_PRIVATE_ENDPOINT}（<b>不发请求</b>）。</li>
 * </ul>
 *
 * <p>
 * <b>证红</b>：把四档合并成一个 500（或全部回 {@code UNREACHABLE}）⇒ 本类逐档变红；去掉
 * {@code AiEndpointGuard} 调用 ⇒ {@link #privateEndpointIsBlockedWithoutTouchingTheNetwork()} 红
 * （命中计数会 +1）。
 */
class AiConnectionProbeTest {

	private HttpServer server;

	private final AtomicInteger hits = new AtomicInteger();

	private String baseUrl;

	@BeforeEach
	void startUpstream() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::handle);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@AfterEach
	void stopUpstream() {
		this.server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		this.hits.incrementAndGet();
		String path = exchange.getRequestURI().getPath();
		// ?models=401 的故障注入口径与 scripts/ci/stub-upstream.mjs 的扩展同形（E2E 批复用同一约定）
		if (path.contains("slow")) {
			try {
				Thread.sleep(2000L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 200, "{\"data\":[]}");
			return;
		}
		if (path.contains("401")) {
			respond(exchange, 401, "{\"error\":\"unauthorized\"}");
			return;
		}
		if (path.contains("403")) {
			respond(exchange, 403, "{\"error\":\"forbidden\"}");
			return;
		}
		if (path.contains("404")) {
			respond(exchange, 404, "{\"error\":\"not found\"}");
			return;
		}
		if (path.contains("405")) {
			respond(exchange, 405, "{\"error\":\"method not allowed\"}");
			return;
		}
		if (path.contains("500")) {
			respond(exchange, 500, "{\"error\":\"boom\"}");
			return;
		}
		if (path.contains("notarray")) {
			respond(exchange, 200, "{\"data\":\"nope\"}");
			return;
		}
		if (path.contains("empty")) {
			respond(exchange, 200, "{\"data\":[]}");
			return;
		}
		respond(exchange, 200, "{\"data\":[{\"id\":\"m-1\"},{\"id\":\"m-2\"}]}");
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("content-type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private AiConnectionProbe probe(boolean allowPrivate, Duration timeout) {
		AiProperties properties = new AiProperties();
		properties.getConfig().setAllowPrivateEndpoints(allowPrivate);
		properties.getConfig().setProbeTimeout(timeout);
		return new AiConnectionProbe(properties);
	}

	@Test
	void okPathReturnsTheModelList() {
		AiConnectionProbe.ProbeResult result = probe(true, Duration.ofSeconds(2)).fetchModels(this.baseUrl,
				"/models-ok/models", "probe-key", false);
		assertThat(result.ok()).isTrue();
		assertThat(result.status()).isEqualTo(200);
		assertThat(result.code()).isNull();
		assertThat(result.modelCount()).isEqualTo(2);
		assertThat(result.models()).containsExactly("m-1", "m-2");
		assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);
	}

	@Test
	void notImplementedEndpointMapsToModelsUnsupported() {
		AiConnectionProbe probe = probe(true, Duration.ofSeconds(2));
		for (String path : new String[] { "/models-404/models", "/models-405/models", "/models-500/models" }) {
			AiConnectionProbe.ProbeResult result = probe.fetchModels(this.baseUrl, path, "probe-key", false);
			assertThat(result.ok()).as(path).isFalse();
			assertThat(result.code()).as(path).isEqualTo(AiConnectionProbe.CODE_MODELS_UNSUPPORTED);
			assertThat(result.status()).as(path).isNotNull();
		}
	}

	@Test
	void unauthorizedMapsToAuthFailed() {
		AiConnectionProbe probe = probe(true, Duration.ofSeconds(2));
		for (String path : new String[] { "/models-401/models", "/models-403/models" }) {
			AiConnectionProbe.ProbeResult result = probe.fetchModels(this.baseUrl, path, "probe-key", false);
			assertThat(result.ok()).as(path).isFalse();
			assertThat(result.code()).as(path).isEqualTo(AiConnectionProbe.CODE_AUTH_FAILED);
			assertThat(result.status()).as(path).isEqualTo(path.contains("401") ? 401 : 403);
		}
	}

	@Test
	void timeoutMapsToUnreachable() {
		AiConnectionProbe.ProbeResult result = probe(true, Duration.ofMillis(150)).fetchModels(this.baseUrl,
				"/models-slow/models", "probe-key", false);
		assertThat(result.ok()).isFalse();
		assertThat(result.code()).isEqualTo(AiConnectionProbe.CODE_UNREACHABLE);
		assertThat(result.status()).isNull();
	}

	@Test
	void connectionRefusedMapsToUnreachable() {
		// 取一个刚关闭的端口：本机连不上 ⇒ UNREACHABLE（不是 INVALID_BASE_URL）
		int deadPort = this.server.getAddress().getPort();
		this.server.stop(0);
		AiConnectionProbe.ProbeResult result = probe(true, Duration.ofMillis(500)).fetchModels(
				"http://127.0.0.1:" + deadPort, "/models", "probe-key", false);
		assertThat(result.ok()).isFalse();
		assertThat(result.code()).isEqualTo(AiConnectionProbe.CODE_UNREACHABLE);
	}

	@Test
	void twoHundredButNotAnArrayMapsToModelsInvalid() {
		AiConnectionProbe.ProbeResult result = probe(true, Duration.ofSeconds(2)).fetchModels(this.baseUrl,
				"/models-notarray/models", "probe-key", false);
		assertThat(result.ok()).isFalse();
		assertThat(result.code()).isEqualTo(AiConnectionProbe.CODE_MODELS_INVALID);
	}

	@Test
	void emptyModelListIsStillOkWithZeroCount() {
		AiConnectionProbe.ProbeResult result = probe(true, Duration.ofSeconds(2)).fetchModels(this.baseUrl,
				"/models-empty/models", "probe-key", false);
		assertThat(result.ok()).isTrue();
		assertThat(result.modelCount()).isZero();
		assertThat(result.models()).isEmpty();
	}

	@Test
	void privateEndpointIsBlockedWithoutTouchingTheNetwork() {
		this.hits.set(0);
		AiConnectionProbe.ProbeResult result = probe(false, Duration.ofSeconds(2)).fetchModels(this.baseUrl,
				"/models-ok/models", "probe-key", false);
		assertThat(result.ok()).isFalse();
		assertThat(result.code()).isEqualTo(AiEndpointGuard.CODE_BLOCKED_PRIVATE_ENDPOINT);
		assertThat(this.hits.get()).as("私网闸必须在发请求之前拦下").isZero();
	}

	@Test
	void trustedSeededEndpointIsProbedEvenWhenPrivate() {
		AiConnectionProbe.ProbeResult result = probe(false, Duration.ofSeconds(2)).fetchModels(this.baseUrl,
				"/models-ok/models", "probe-key", true);
		assertThat(result.ok()).isTrue();
		assertThat(result.modelCount()).isEqualTo(2);
	}

}
