package com.example.configmgr.ai.kb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M19–M23：HTTP 源等价性、ETag 短路、sha 增量、5xx/超时 fail-open、鉴权头不泄漏。
 */
class KbHttpSourceTest {

    private TestServer server;

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
    }

    private KbService service(TestServer srv, Duration readTimeout, String token, Path indexDir) {
        HttpKbSource source = new HttpKbSource(srv.baseUrl(), Duration.ofSeconds(2), readTimeout,
                "KB_HTTP_AUTH", () -> token, KbTestSupport.mapper());
        return new KbService(KbTestSupport.props(this.server.root(), indexDir), KbTestSupport.mapper(), source);
    }

    @Test
    void localAndHttpSourcesIndexTheSameCorpus() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        KbService http = service(this.server, Duration.ofSeconds(5), null, KbTestSupport.tempDir("kb-http-"));
        KbService local = KbTestSupport.samplesService();

        assertThat(http.refreshNow()).isTrue();

        assertThat(http.status().docCount()).isEqualTo(local.status().docCount());
        assertThat(http.status().chunkCount()).isEqualTo(local.status().chunkCount());
        assertThat(http.status().source()).isEqualTo("http");
        assertThat(http.search("区域费率怎么计算", 5).hits().get(0).docId())
                .isEqualTo(local.search("区域费率怎么计算", 5).hits().get(0).docId());
    }

    @Test
    void unchangedEtagSkipsRebuild() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        KbService service = service(this.server, Duration.ofSeconds(5), null, KbTestSupport.tempDir("kb-http-"));
        assertThat(service.refreshNow()).isTrue();
        var builtAt = service.status().builtAt();

        assertThat(service.refreshNow()).isFalse();

        assertThat(service.status().builtAt()).isEqualTo(builtAt);
        assertThat(this.server.manifestHits.get()).isEqualTo(2);
        assertThat(this.server.conditionalHits.get()).isEqualTo(1);
    }

    @Test
    void changedShaFetchesOnlyThatDocument() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        KbService service = service(this.server, Duration.ofSeconds(5), null, KbTestSupport.tempDir("kb-http-"));
        assertThat(service.refreshNow()).isTrue();
        int docHitsBefore = this.server.docHits.getOrDefault("14-currency-scope.md", new AtomicInteger()).get();
        int otherHitsBefore = this.server.docHits.getOrDefault("01-region-tariff.md", new AtomicInteger()).get();

        this.server.mutateManifestSha("currency-scope");
        assertThat(service.refreshNow()).isTrue();

        assertThat(this.server.docHits.get("14-currency-scope.md").get())
                .isEqualTo(docHitsBefore + 1);
        assertThat(this.server.docHits.get("01-region-tariff.md").get())
                .isEqualTo(otherHitsBefore);
    }

    @Test
    void serverErrorKeepsOldIndexAndReports() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        KbService service = service(this.server, Duration.ofSeconds(5), null, KbTestSupport.tempDir("kb-http-"));
        service.refreshNow();
        int docs = service.status().docCount();

        this.server.failManifest = true;
        assertThat(service.refreshNow()).isFalse();

        assertThat(service.status().docCount()).isEqualTo(docs);
        assertThat(service.status().lastError()).contains("500");
        assertThat(service.search("费率", 3).hits()).isNotEmpty();
    }

    @Test
    void readTimeoutFailsOpenWithoutClearingIndex() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        KbService service = service(this.server, Duration.ofSeconds(5), null, KbTestSupport.tempDir("kb-http-"));
        service.refreshNow();

        this.server.delayMillis = 600;
        KbService shortTimeout = service(this.server, Duration.ofMillis(150), null, KbTestSupport.tempDir("kb-http2-"));
        assertThat(shortTimeout.refreshNow()).isFalse();
        assertThat(shortTimeout.status().ready()).isFalse();
        assertThat(shortTimeout.status().lastError()).contains("超时");
    }

    @Test
    void authHeaderIsSentButNeverLeaksInErrors() throws Exception {
        this.server = new TestServer(KbTestSupport.copySamples());
        this.server.requiredAuth = "Bearer sample-token-123";
        KbService ok = service(this.server, Duration.ofSeconds(5), "Bearer sample-token-123",
                KbTestSupport.tempDir("kb-http-"));
        assertThat(ok.refreshNow()).isTrue();
        assertThat(this.server.lastAuthHeader).isEqualTo("Bearer sample-token-123");

        this.server.requiredAuth = "Bearer other-token-456";
        KbService denied = service(this.server, Duration.ofSeconds(5), "Bearer sample-token-123",
                KbTestSupport.tempDir("kb-http2-"));
        assertThat(denied.refreshNow()).isFalse();
        assertThat(denied.status().lastError()).contains("401")
                .doesNotContain("sample-token-123");
    }

    /** 极简静态知识服务：manifest + 文档 + ETag + 可选鉴权/故障注入。 */
    static final class TestServer implements AutoCloseable {

        private final HttpServer server;

        private final Path root;

        Path root() {
            return this.root;
        }

        private final Map<String, AtomicInteger> docHits = new ConcurrentHashMap<>();

        private final AtomicInteger manifestHits = new AtomicInteger();

        private final AtomicInteger conditionalHits = new AtomicInteger();

        private volatile boolean failManifest;

        private volatile long delayMillis;

        private volatile String requiredAuth;

        private volatile String lastAuthHeader;

        private volatile String manifestEtag;

        TestServer(Path root) throws IOException {
            this.root = root;
            this.manifestEtag = KbHashing.sha256Hex(Files.readAllBytes(root.resolve("manifest.json")));
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort();
        }

        void mutateManifestSha(String docId) throws IOException {
            var mapper = KbTestSupport.mapper();
            var node = mapper.readTree(Files.readAllBytes(this.root.resolve("manifest.json")));
            for (var doc : node.get("docs")) {
                if (docId.equals(doc.get("id").asText())) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("sha", "tampered-" + docId);
                }
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
            Files.write(this.root.resolve("manifest.json"), bytes);
            this.manifestEtag = KbHashing.sha256Hex(bytes);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (this.delayMillis > 0) {
                    Thread.sleep(this.delayMillis);
                }
                String path = exchange.getRequestURI().getPath();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                this.lastAuthHeader = auth;
                if (this.requiredAuth != null && !this.requiredAuth.equals(auth)) {
                    respond(exchange, 401, new byte[0], null);
                    return;
                }
                if ("/manifest.json".equals(path)) {
                    this.manifestHits.incrementAndGet();
                    if (this.failManifest) {
                        respond(exchange, 500, "boom".getBytes(StandardCharsets.UTF_8), null);
                        return;
                    }
                    String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (ifNoneMatch != null) {
                        this.conditionalHits.incrementAndGet();
                        if (ifNoneMatch.equals(this.manifestEtag)) {
                            respond(exchange, 304, new byte[0], this.manifestEtag);
                            return;
                        }
                    }
                    byte[] body = Files.readAllBytes(this.root.resolve("manifest.json"));
                    respond(exchange, 200, body, this.manifestEtag);
                    return;
                }
                String relative = path.startsWith("/") ? path.substring(1) : path;
                Path file = this.root.resolve(relative).normalize();
                if (!file.startsWith(this.root) || !Files.isRegularFile(file)) {
                    respond(exchange, 404, new byte[0], null);
                    return;
                }
                this.docHits.computeIfAbsent(relative, key -> new AtomicInteger()).incrementAndGet();
                respond(exchange, 200, Files.readAllBytes(file), null);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, new byte[0], null);
            }
            finally {
                exchange.close();
            }
        }

        private static void respond(HttpExchange exchange, int status, byte[] body, String etag)
                throws IOException {
            if (etag != null) {
                exchange.getResponseHeaders().add("ETag", etag);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            if (status == 304) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}
