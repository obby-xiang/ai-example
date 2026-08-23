package com.example.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 透明 SSE 代理 —— ai-ui-vercel「前端 AI runtime」范式的后端唯一职责。
 *
 * 设计原则（响应经验 100029435）：
 *   1. 后端不做 agent loop、不解析 tool_calls、不持久化任务 —— 这些全部前移到浏览器
 *      （由 Vercel AI SDK streamText + tools[].execute 承担）。
 *   2. 后端只做一件事：把前端发来的 OpenAI 兼容 chat/completions 请求体原样转发给
 *      DeepSeek，注入真实 api-key（前端只持占位 key），并把 DeepSeek 返回的 SSE
 *      字节流逐块透传回前端，实现流式打字机效果。
 *   3. api-key 永不离开后端：忽略前端传入的 Authorization 头，统一用
 *      ${app.ai.api-key} 注入。
 *
 * 路径约定：前端 createOpenAI({ baseURL: '/api/ai/proxy' }) 会让 SDK POST 到
 *   /api/ai/proxy/chat/completions（部分版本带 /v1 前缀）。本控制器用 /** 通配并
 *   归一化子路径，再拼到 app.ai.base-url 上（DeepSeek 实际端点不带 /v1）。
 *
 * 流式实现：JDK 21 java.net.http.HttpClient（零新依赖，pom 无 webflux，避免引入
 *   reactive server 与现有 servlet 栈冲突）+ StreamingResponseBody（Spring 异步
 *   线程写出，不阻塞 Servlet 容器请求线程）。
 */
@RestController
@RequestMapping("/api/ai/proxy")
@RequiredArgsConstructor
@Slf4j
public class AiProxyController {

    @Value("${app.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    /** 复用单个 ObjectMapper(线程安全),用于请求体归一化。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 通配子路径，兼容 SDK 是否带 /v1 前缀。
     * 用 @PostMapping("/**") 匹配 /api/ai/proxy/chat/completions 等任意子路径。
     */
    @PostMapping("/**")
    public StreamingResponseBody proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        // 1) 提取子路径并归一化（剥离 /v1 前缀，DeepSeek 端点为 /chat/completions）
        String fullUri = req.getRequestURI(); // /api/ai/proxy/chat/completions 或 .../v1/chat/completions
        String prefix = "/api/ai/proxy";
        String sub = fullUri.length() > prefix.length() ? fullUri.substring(prefix.length()) : "/chat/completions";
        if (sub.startsWith("/v1")) sub = sub.substring(3); // -> /chat/completions
        if (sub.isEmpty()) sub = "/chat/completions";
        String target = baseUrl + sub;

        // 2) 读取前端发来的请求体（OpenAI 兼容 JSON，含 messages/tools/stream 等）
        byte[] raw = req.getInputStream().readAllBytes();
        // 2a) 归一化请求体：注入 thinking={"type":"disabled"} 关闭 DeepSeek 思考链。
        //     根因（项目经验沉淀）：deepseek-v4-flash 思考模式默认开启，带 tools 参数时
        //     中间轮 assistant 消息必须回传 reasoning_content，否则返回 400。前端 AI runtime
        //     用 Vercel AI SDK 管 multi-step loop，不会保留 reasoning_content，故在代理层
        //     统一关闭思考（配置管理场景无需思维链，省 token 加速）。若前端已显式设置则尊重。
        byte[] body = normalizeBody(raw);

        // 3) 响应类型固定为 SSE，提前声明，避免被缓冲后才下发
        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        resp.setCharacterEncoding("UTF-8");

        log.debug("[proxy] -> {} ({} bytes)", target, body.length);

        // 4) 构造转发请求：注入真实 api-key，忽略前端传入的占位 Authorization
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest upstream = HttpRequest.newBuilder(URI.create(target))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        // 5) 同步发送（headers 到达即返回，body 以 InputStream 形式流式读取）
        HttpResponse<InputStream> upstreamResp = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
        // 把上游状态码透传给前端（如 401/400/429），便于前端 SDK 抛出准确错误
        resp.setStatus(upstreamResp.statusCode());

        InputStream is = upstreamResp.body();
        return out -> {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush(); // 逐块刷出，前端立即收到 SSE 分片
            }
            is.close();
        };
    }

    /**
     * 请求体归一化：注入 thinking={"type":"disabled"}（若未显式设置）。
     * 解析失败则原样返回（不阻断请求，交由上游决定）。
     */
    private byte[] normalizeBody(byte[] raw) {
        if (raw == null || raw.length == 0) return raw;
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root != null && root.isObject() && !root.has("thinking")) {
                ObjectNode obj = (ObjectNode) root;
                obj.putPOJO("thinking", MAPPER.createObjectNode().put("type", "disabled"));
                return MAPPER.writeValueAsBytes(obj);
            }
            return raw;
        } catch (Exception e) {
            log.debug("[proxy] 请求体非 JSON 或解析失败,原样转发: {}", e.getMessage());
            return raw;
        }
    }
}
