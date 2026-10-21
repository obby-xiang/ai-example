package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 生产 HTTP 知识源（追加 2）：从 {@code base-url} 拉 {@code manifest.json}，再按需拉各文档正文。
 *
 * <h2>四条硬口径</h2>
 * <ol>
 * <li><b>超时可配</b>：连接 / 读超时来自 {@code app.ai.kb.http.*}（默认 5s / 10s）；超时按源不可用处理
 * （fail-open，保留旧索引）；</li>
 * <li><b>增量</b>：manifest 请求带 {@code If-None-Match}（上次的 ETag），304 ⇒ 整份未变，不重建；
 * 各篇是否重拉由 manifest 的 {@code sha} 决定（KbService 判）；</li>
 * <li><b>鉴权头只从环境变量读</b>（{@code auth-env}，默认 {@code KB_HTTP_AUTH}）：值整段作为
 * {@code Authorization} 头，不写配置、不落库、不进日志/错误消息（错误消息只含 HTTP 状态码与目标名）；</li>
 * <li><b>失败不清空</b>：本类只抛 {@link KbSourceException}，由 {@code KbService} 保留旧索引并 WARN。</li>
 * </ol>
 */
@Slf4j
public class HttpKbSource implements KbSource {

    private final String baseUrl;

    private final Duration connectTimeout;

    private final Duration readTimeout;

    private final String authEnv;

    private final ObjectMapper mapper;

    private final HttpClient client;

    /** 鉴权头取值（生产 = 环境变量；用例可注入固定值验证"头会带、错误不泄漏"）。 */
    private final java.util.function.Supplier<String> authSupplier;

    public HttpKbSource(String baseUrl, Duration connectTimeout, Duration readTimeout, String authEnv,
            ObjectMapper mapper) {
        this(baseUrl, connectTimeout, readTimeout, authEnv,
                () -> readEnv(authEnv), mapper);
    }

    HttpKbSource(String baseUrl, Duration connectTimeout, Duration readTimeout, String authEnv,
            java.util.function.Supplier<String> authSupplier, ObjectMapper mapper) {
        this.baseUrl = normalizeBase(baseUrl);
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.authEnv = authEnv;
        this.authSupplier = authSupplier;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String readEnv(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return System.getenv(name);
        }
        catch (SecurityException e) {
            return null;
        }
    }

    @Override
    public String kind() {
        return "http";
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
        HttpRequest.Builder builder = request(manifestUri());
        if (previousEtag != null && !previousEtag.isBlank()) {
            builder.header("If-None-Match", previousEtag);
        }
        HttpResponse<byte[]> response = send(builder.build(), "manifest.json");
        if (response.statusCode() == 304) {
            // 304 的 ETag 可缺省：复用上一次的即可（服务端未变）
            String etag = response.headers().firstValue("ETag").orElse(previousEtag);
            return KbSource.ManifestFetch.notModified(etag);
        }
        requireSuccess(response, "manifest.json");
        KbManifest manifest = KbManifest.parse(response.body(), this.mapper);
        manifest.validate();
        String etag = response.headers().firstValue("ETag")
                .orElseGet(() -> KbHashing.sha256Hex(response.body()));
        return KbSource.ManifestFetch.modified(manifest, etag);
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        HttpRequest.Builder builder = request(documentUri(meta));
        HttpResponse<byte[]> response = send(builder.build(), "文档 " + meta.id());
        requireSuccess(response, "文档 " + meta.id());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- internals

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(this.readTimeout)
                .GET()
                .header("Accept", "application/json, text/markdown, text/plain;q=0.9, */*;q=0.5");
        String auth = authHeaderValue();
        if (auth != null && !auth.isBlank()) {
            builder.header("Authorization", auth);
        }
        return builder;
    }

    /**
     * 鉴权头的值：<b>每次请求现读</b>环境变量（轮换不必重启），缺失即不带。
     * 本方法的结果只允许进请求头 —— 不得出现在异常消息、日志、状态响应里。
     */
    private String authHeaderValue() {
        try {
            return this.authSupplier == null ? null : this.authSupplier.get();
        }
        catch (RuntimeException e) {
            log.debug("读取鉴权环境变量失败（{}），本次不带鉴权头", this.authEnv);
            return null;
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, String target) throws KbSourceException {
        try {
            return this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (HttpTimeoutException e) {
            throw new KbSourceException("请求超时（读超时 " + this.readTimeout.toMillis() + "ms）：" + target, e);
        }
        catch (IOException e) {
            // 只报"连不上"的类别与目标名：不拼 URL（避免把带查询串/凭据的地址带进日志面）
            throw new KbSourceException("无法连接知识服务（" + target + "）：" + e.getClass().getSimpleName(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbSourceException("请求被中断：" + target, e);
        }
    }

    private static void requireSuccess(HttpResponse<byte[]> response, String target) throws KbSourceException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        // 不包含响应体（可能回显请求头/内部地址）；只给状态码 + 目标名，模型侧可行动（稍后重试/联系管理员）
        throw new KbSourceException("知识服务返回 HTTP " + status + "（" + target + "）");
    }

    private URI manifestUri() throws KbSourceException {
        return uri(this.baseUrl + "/manifest.json");
    }

    private URI documentUri(KbDocMeta meta) throws KbSourceException {
        String path = meta.effectiveFile();
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new KbSourceException("文档路径不合法：" + meta.id());
            }
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return uri(this.baseUrl + "/" + encoded);
    }

    private static URI uri(String value) throws KbSourceException {
        try {
            return URI.create(value);
        }
        catch (IllegalArgumentException e) {
            throw new KbSourceException("知识服务地址不合法（检查 app.ai.kb.http.base-url）");
        }
    }

    private static String normalizeBase(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 连接超时（诊断面用；不涉及鉴权）。 */
    public Duration connectTimeout() {
        return this.connectTimeout;
    }

    /** 读超时（诊断面用；不涉及鉴权）。 */
    public Duration readTimeout() {
        return this.readTimeout;
    }
}
