package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider 级实例注册表（模型配置专项 §3.4）：<b>热生效</b>的唯一落点。
 *
 * <h2>RuntimeKey 与重建口径</h2>
 * <ul>
 * <li>{@code RuntimeKey = providerId | keyFingerprint | baseUrl | completionsPath}：任一项变了
 * ⇒ 下一次取用重建实例（在飞请求手里的旧引用继续跑完）——<b>不需要重启</b>；</li>
 * <li>模型名/参数<b>不在</b> key 里：本批（批①）还没有逐请求 options，模型名只能烙在实例的默认
 * 选项上，因此"同 provider 换模型"走<b>轻量重绑</b>（复用同一个 {@link OpenAiApi}，只重建
 * model+client，缓存条目数与 key 都不变）；批③ 引入逐请求 options 后这一步会被替掉；</li>
 * <li>改种子/换 KEK 需重启（种子只在启动跑、P1 单 kid）—— 刻意如此。</li>
 * </ul>
 *
 * <h2>复用的两件包装</h2>
 * {@link SpToolCallingManager}（bean 单例、无状态：确认门/前端挂起/可见性钩子都在那里，换 provider
 * 绝不能丢）与记忆 advisor（与今天同一个 bean，记忆仍按 {@code CONVERSATION_ID} 隔离）。
 *
 * <h2>streamUsage 不许丢</h2>
 * {@code done} 终帧的 usage 来源就是请求里的 {@code stream_options.include_usage}（由
 * {@code spring.ai.openai.chat.options.stream-usage=true} 驱动）。重建实例时以
 * {@link OpenAiChatProperties#getOptions()} 为基底，缺省再兜一次 true —— 上下文统计专项的
 * 计量源不能因为换 provider 而断供。
 */
@Slf4j
@Component
public class AiProviderRegistry {

    private final AiConfigService configService;

    private final SpToolCallingManager toolCallingManager;

    private final MessageChatMemoryAdvisor memoryAdvisor;

    private final ObjectProvider<RetryTemplate> retryTemplate;

    private final ObjectProvider<ObservationRegistry> observationRegistry;

    private final OpenAiChatProperties chatProperties;

    /** RuntimeKey → 实例（批① 只保留"当前默认目标"这一个 key，旧 key 在重建时淘汰）。 */
    private final Map<String, ProviderRuntime> runtimes = new ConcurrentHashMap<>();

    private final Object buildLock = new Object();

    public AiProviderRegistry(AiConfigService configService, SpToolCallingManager toolCallingManager,
            MessageChatMemoryAdvisor memoryAdvisor, ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ObservationRegistry> observationRegistry, OpenAiChatProperties chatProperties) {
        this.configService = configService;
        this.toolCallingManager = toolCallingManager;
        this.memoryAdvisor = memoryAdvisor;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;
        this.chatProperties = chatProperties;
    }

    /**
     * 当前默认运行态；没有可用 provider 时抛 {@link AiUnavailableException}（控制器已按
     * availability 前置放行，这里是并发改配置的窄窗口兜底）。
     */
    public ProviderRuntime current() {
        return tryCurrent().orElseThrow(() -> {
            AiConfigService.Availability availability = this.configService.availability();
            return new AiUnavailableException(availability.code(), availability.reason());
        });
    }

    public Optional<ProviderRuntime> tryCurrent() {
        Optional<AiConfigService.ResolvedProvider> resolved = this.configService.resolveDefault();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runtimeFor(resolved.get()));
    }

    /** 按解析结果取（或重建）实例。 */
    public ProviderRuntime runtimeFor(AiConfigService.ResolvedProvider resolved) {
        String key = resolved.runtimeKey();
        ProviderRuntime cached = this.runtimes.get(key);
        if (cached != null && cached.modelId().equals(resolved.modelId())) {
            return cached;
        }
        synchronized (this.buildLock) {
            cached = this.runtimes.get(key);
            if (cached != null && cached.modelId().equals(resolved.modelId())) {
                return cached;
            }
            // 同 key 换模型 = 轻量重绑：复用已有的 OpenAiApi（不重建 WebClient）
            OpenAiApi api = cached != null ? cached.api() : newApi(resolved);
            ProviderRuntime rebuilt = newRuntime(resolved, key, api);
            this.runtimes.keySet().removeIf(existing -> !existing.equals(key));
            this.runtimes.put(key, rebuilt);
            log.info("AI provider 运行态已{} provider={} model={} runtimeKey={}", cached == null ? "装配" : "重绑",
                    resolved.providerCode(), resolved.modelId(), key);
            return rebuilt;
        }
    }

    /** 缓存条目数（取证/热生效用例：换 base-url 后仍是 1 条；条目身份变了才说明真的重建）。 */
    public int runtimeCount() {
        return this.runtimes.size();
    }

    public Set<String> runtimeKeys() {
        return new LinkedHashSet<>(this.runtimes.keySet());
    }

    /** 清空缓存（KEK/配置重启级变更后由运维或测试调用；生产无自动调用点）。 */
    public void evictAll() {
        this.runtimes.clear();
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private OpenAiApi newApi(AiConfigService.ResolvedProvider resolved) {
        return OpenAiApi.builder()
            .baseUrl(resolved.baseUrl())
            .apiKey(resolved.apiKey())
            .completionsPath(resolved.completionsPath())
            .build();
    }

    private ProviderRuntime newRuntime(AiConfigService.ResolvedProvider resolved, String key, OpenAiApi api) {
        OpenAiChatOptions options = buildOptions(resolved.modelId());
        ChatModel model = new ReasoningAwareOpenAiChatModel(api, options, this.toolCallingManager,
                this.retryTemplate.getIfAvailable(RetryTemplate::new),
                this.observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
        ChatClient client = ChatClient.builder(model).defaultAdvisors(this.memoryAdvisor).build();
        return new ProviderRuntime(resolved.providerId(), resolved.providerCode(), resolved.modelId(),
                resolved.baseUrl(), resolved.completionsPath(), key, options, api, model, client);
    }

    /**
     * 默认选项 = 今天 {@code spring.ai.openai.chat.options} 的逐值副本 + 解析出的模型名。
     *
     * <p>
     * 基底刻意取 {@link OpenAiChatProperties#getOptions()}（而不是自己写一套默认值）：
     * "空表 + env" 下与今天<b>逐值等价</b>（temperature 0.2 / max-tokens 4096 / stream-usage true）
     * 就是批① 的验收基线。{@code ai_setting} 里的全局默认（批② 写入）在这里覆盖。
     *
     * <p>
     * <b>脏值容错（M3）</b>：{@code ai_setting} 是人工可写入的键值表（设置页/直接改库/未来导入），
     * 一个 {@code default.temperature="hot"} 不该让整条 AI 链路 500 —— 解析失败/非有限值/
     * 非正的 maxTokens 一律 WARN + <b>忽略该项</b>（沿用 {@code spring.ai.openai.*} 的缺省），
     * 其余项照常生效。
     */
    OpenAiChatOptions buildOptions(String modelId) {
        OpenAiChatOptions base = this.chatProperties.getOptions();
        OpenAiChatOptions options = base != null ? OpenAiChatOptions.fromOptions(base)
                : OpenAiChatOptions.builder().build();
        if (options.getStreamUsage() == null) {
            options.setStreamUsage(Boolean.TRUE);
        }
        options.setModel(modelId);
        this.configService.setting(AiSetting.DEFAULT_TEMPERATURE)
            .ifPresent(value -> applyDouble(AiSetting.DEFAULT_TEMPERATURE, value, options::setTemperature));
        this.configService.setting(AiSetting.DEFAULT_TOP_P)
            .ifPresent(value -> applyDouble(AiSetting.DEFAULT_TOP_P, value, options::setTopP));
        this.configService.setting(AiSetting.DEFAULT_MAX_TOKENS)
            .ifPresent(value -> applyMaxTokens(AiSetting.DEFAULT_MAX_TOKENS, value, options::setMaxTokens));
        return options;
    }

    private void applyDouble(String settingKey, String raw, java.util.function.Consumer<Double> setter) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                throw new NumberFormatException("非有限值");
            }
            setter.accept(value);
        }
        catch (RuntimeException ex) {
            log.warn("ai_setting[{}] 的值 \"{}\" 不可用（{}）：忽略该项，沿用 spring.ai.openai.* 的缺省",
                    settingKey, abbreviate(raw), ex.getMessage());
        }
    }

    private void applyMaxTokens(String settingKey, String raw, java.util.function.Consumer<Integer> setter) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException("必须为正整数");
            }
            setter.accept(value);
        }
        catch (RuntimeException ex) {
            log.warn("ai_setting[{}] 的值 \"{}\" 不可用（{}）：忽略该项，沿用 spring.ai.openai.* 的缺省",
                    settingKey, abbreviate(raw), ex.getMessage());
        }
    }

    private static String abbreviate(String raw) {
        String text = raw == null ? "" : raw.trim();
        return text.length() <= 32 ? text : text.substring(0, 32) + "…";
    }
}
