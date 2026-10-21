package com.example.configmgr.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 上游连通性探活／模型拉取（模型配置专项 §3.5 的 {@code /test} 与 {@code /models/fetch}）。
 *
 * <h2>为什么用 JDK {@link HttpClient}</h2>
 * 零新依赖（本仓"依赖引入需显式裁决"），且这是一个<b>一次性的 GET</b>：不需要 Spring 的
 * WebClient/连接池，也不需要把 chat 那套 WebClient 配置（重试/观测/网关）带进来。
 *
 * <h2>四档错误面（设计稿 §3.5 的稳定 code，禁止自由文本）</h2>
 * <table>
 * <tr><th>上游事实</th><th>code</th></tr>
 * <tr><td>200 且 {@code {data:[{id}]}}（或顶层数组）</td><td>{@code null} + ok=true</td></tr>
 * <tr><td>401 / 403</td><td>{@code AUTH_FAILED}</td></tr>
 * <tr><td>404 / 405 / 其它非 2xx（含 5xx）</td><td>{@code MODELS_UNSUPPORTED}（该端点没实现 /models，可手填）</td></tr>
 * <tr><td>200 但不是数组/没有 data</td><td>{@code MODELS_INVALID}</td></tr>
 * <tr><td>超时 / 连接失败 / IO</td><td>{@code UNREACHABLE}</td></tr>
 * <tr><td>私网且开关未开（或 seed 例外未命中）</td><td>{@code BLOCKED_PRIVATE_ENDPOINT}（<b>不发请求</b>）</td></tr>
 * </table>
 *
 * <p>
 * 5xx 归 {@code MODELS_UNSUPPORTED} 而非单列：从"能不能用它拉模型"这个产品问题看，5xx 与 404
 * 是同一个结论（这个端点现在不可用，改手填）；单列一个 {@code UPSTREAM_ERROR} 只会让前端多一条
 * 文案而行动不变。
 */
@Slf4j
@Component
public class AiConnectionProbe {

    public static final String CODE_MODELS_UNSUPPORTED = "MODELS_UNSUPPORTED";

    public static final String CODE_AUTH_FAILED = "AUTH_FAILED";

    public static final String CODE_UNREACHABLE = "UNREACHABLE";

    public static final String CODE_MODELS_INVALID = "MODELS_INVALID";

    /** 与设计稿 §3.5 的文案一致：后端 message 只是中文兜底，前端按 code 查文案。 */
    public static final String MESSAGE_MODELS_UNSUPPORTED = "该端点未实现 /models，可手填模型 ID";

    public static final String MESSAGE_AUTH_FAILED = "鉴权失败，请检查 key";

    public static final String MESSAGE_UNREACHABLE = "网络不可达/超时";

    public static final String MESSAGE_MODELS_INVALID = "上游 /models 返回的不是模型数组";

    private final AiProperties properties;

    private final AiEndpointGuard guard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 复用一个 JDK HttpClient：每次 {@code /test} 新建一个会让"点十次测试连接"留下十个 selector 线程
     * 等着被 GC。连接超时取自配置（本键是启动期静态配置，故单例安全）；单次请求的超时另由
     * {@code HttpRequest#timeout} 给。
     */
    private volatile HttpClient client;

    public AiConnectionProbe(AiProperties properties) {
        this.properties = properties;
        this.guard = new AiEndpointGuard(properties);
    }

    private HttpClient client(Duration timeout) {
        HttpClient existing = this.client;
        if (existing == null) {
            synchronized (this) {
                existing = this.client;
                if (existing == null) {
                    existing = HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
                    this.client = existing;
                }
            }
        }
        return existing;
    }

    /**
     * 拉一次 {@code GET {baseUrl}{modelsPath}}。
     *
     * @param apiKey 明文 key；空 ⇒ 不发 Authorization（让上游自己回 401，交给 AUTH_FAILED 档）
     * @param trusted 种子行（{@code source=ENV_SEED}）为 true ⇒ 跳过私网判定
     */
    public ProbeResult fetchModels(String baseUrl, String modelsPath, String apiKey, boolean trusted) {
        String base;
        try {
            base = this.guard.checkForOutbound(baseUrl, trusted);
        }
        catch (AiEndpointGuard.GuardException ex) {
            return new ProbeResult(false, null, 0L, ex.getCode(), ex.getMessage(), 0, List.of());
        }
        String path = normalisePath(modelsPath);
        URI uri;
        try {
            uri = URI.create(base + path);
        }
        catch (IllegalArgumentException ex) {
            return new ProbeResult(false, null, 0L, AiEndpointGuard.CODE_INVALID_BASE_URL,
                    "拼接后的 /models 地址不合法：" + ex.getClass().getSimpleName(), 0, List.of());
        }

        Duration timeout = this.properties.getConfig().getProbeTimeout();
        long startedAt = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpClient client = client(timeout);
            // 同步等一个 future：HttpClient 的 send() 在超时上跨越"连接"与"读取"两段，
            // 但部分平台（代理/慢 DNS）仍会把 connectTimeout 拉长 ⇒ 再用 get(timeout) 兜一道硬闸。
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            return interpret(response.statusCode(), response.body(), latencyMs);
        }
        catch (TimeoutException ex) {
            log.debug("探活超时 uri={}（超时即 UNREACHABLE，不再重试）", uri);
            return new ProbeResult(false, null, elapsedMs(startedAt), CODE_UNREACHABLE, MESSAGE_UNREACHABLE, 0,
                    List.of());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, null, elapsedMs(startedAt), CODE_UNREACHABLE, MESSAGE_UNREACHABLE, 0,
                    List.of());
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.debug("探活失败 uri={}：{}", uri, cause.getClass().getSimpleName());
            return new ProbeResult(false, null, elapsedMs(startedAt), CODE_UNREACHABLE, MESSAGE_UNREACHABLE, 0,
                    List.of());
        }
        catch (RuntimeException ex) {
            // URI/客户端装配期的 IllegalArgument 等：仍然只回稳定 code（绝不把上游异常文本漏给前端）
            log.debug("探活请求装配失败 uri={}：{}", uri, ex.getClass().getSimpleName());
            return new ProbeResult(false, null, elapsedMs(startedAt), CODE_UNREACHABLE, MESSAGE_UNREACHABLE, 0,
                    List.of());
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String normalisePath(String modelsPath) {
        String path = StringUtils.hasText(modelsPath) ? modelsPath.trim() : "/models";
        return path.startsWith("/") ? path : "/" + path;
    }

    /** 状态码 + 体 → 结果（把"上游事实"翻译成稳定 code）。 */
    ProbeResult interpret(int status, String body, long latencyMs) {
        if (status == 401 || status == 403) {
            return new ProbeResult(false, status, latencyMs, CODE_AUTH_FAILED, MESSAGE_AUTH_FAILED, 0, List.of());
        }
        if (status < 200 || status >= 300) {
            return new ProbeResult(false, status, latencyMs, CODE_MODELS_UNSUPPORTED, MESSAGE_MODELS_UNSUPPORTED, 0,
                    List.of());
        }
        List<String> ids = parseModelIds(body);
        if (ids == null) {
            return new ProbeResult(false, status, latencyMs, CODE_MODELS_INVALID, MESSAGE_MODELS_INVALID, 0,
                    List.of());
        }
        return new ProbeResult(true, status, latencyMs, null, null, ids.size(), ids);
    }

    /**
     * 从 {@code {data:[{id:"..."}]}} 抽 id；也容 {@code {data:["..."]}} 与顶层数组。
     *
     * @return {@code null} = 200 但不是模型数组（MODELS_INVALID）
     */
    private List<String> parseModelIds(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = this.objectMapper.readTree(body);
            JsonNode array = root.isArray() ? root : root.path("data");
            if (!array.isArray()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : array) {
                String id = item.isTextual() ? item.asText() : item.path("id").asText(null);
                if (StringUtils.hasText(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        catch (Exception ex) {
            return null;
        }
    }

    /**
     * 一次探活的结果。
     *
     * @param ok 是否拿到合法的模型列表
     * @param status 上游 HTTP 状态（网络层失败为 null）
     * @param latencyMs 端到端耗时
     * @param code 稳定错误码（ok=true 时为 null）
     * @param modelCount 模型数量（ok=false 时为 0）
     * @param models 模型 id 列表（ok=false 时为空）
     */
    public record ProbeResult(boolean ok, Integer status, long latencyMs, String code, String message,
            int modelCount, List<String> models) {

        public static ProbeResult ok(int status, long latencyMs, List<String> models) {
            return new ProbeResult(true, status, latencyMs, null, null, models.size(), models);
        }

        public static ProbeResult failed(String code, Integer status, long latencyMs, String message) {
            return new ProbeResult(false, status, latencyMs, code, message, 0, List.of());
        }
    }
}
