package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * 绝不能丢）与记忆 advisor（与今天同一个 bean，记忆仍按 {@code CONVERSATION_ID} 隔离；
 * 实际 bean 是身份绑定装饰器，接口按官方 {@link BaseChatMemoryAdvisor} 依赖）。
 *
 * <h2>streamUsage 不许丢</h2>
 * {@code done} 终帧的 usage 来源就是请求里的 {@code stream_options.include_usage}（由
 * {@code spring.ai.openai.chat.options.stream-usage=true} 驱动）。重建实例时以
 * {@link OpenAiChatProperties#getOptions()} 为基底，缺省再兜一次 true —— 上下文统计专项的
 * 计量源不能因为换 provider 而断供。
 *
 * <h2>I2：默认参数的多实例热生效（版本闸）</h2>
 * 默认参数（temperature/topP/maxTokens）烙在缓存实例的 options 上；写路径的 {@code evictAll()}
 * 只清<b>写那个实例</b>。本类在 {@code current/tryCurrent/runtimeFor/forRequest/forFrozenRequest}
 * 入口比较 {@code configService.current().version()} 与 {@link #lastEvictedConfigVersion}：
 * 变化即清缓存（与构建插入同受 {@link #buildLock} 保护，且构建结束前再比一次版本 ——
 * 构建期间配置又变则本次实例不入缓存）。在飞请求持有自己的实例，清缓存不打断它；
 * <b>下一轮/续跑</b>按新值重建。跨实例由同一个 DB 版本号 + 各实例的
 * {@code AiConfigService} 重载共同收敛，不新增轮询。
 *
 * <h2>I3：带 providerId 的精确解析</h2>
 * {@link #target(Long, String)} 把"这一轮属于哪家 provider"作为硬约束（启用/key/模型 allowlist），
 * 失败即空：续跑不跨 provider、不换模型。首轮/旧客户端仍走 {@link #target(String)} 的模型名兼容。
 */
@Slf4j
@Component
public class AiProviderRegistry {

    private final AiConfigService configService;

    private final SpToolCallingManager toolCallingManager;

    private final BaseChatMemoryAdvisor memoryAdvisor;

    private final ObjectProvider<RetryTemplate> retryTemplate;

    private final ObjectProvider<ObservationRegistry> observationRegistry;

    private final OpenAiChatProperties chatProperties;

    /** RuntimeKey → 实例（批① 只保留"当前默认目标"这一个 key，旧 key 在重建时淘汰）。 */
    private final Map<String, ProviderRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * RuntimeKey → <b>HTTP 客户端</b>（跨"配置版本淘汰"保留，只有 {@link #evictAll()} 会清）。
     *
     * <p>为什么单独存：RuntimeKey 已经包含 providerId | keyFingerprint | baseUrl | completionsPath ——
     * 任何影响端点的变更都会换 key（旧 api 自然不再被取用），而<b>默认参数/模型名变化</b>不影响端点，
     * 淘汰运行态时重建 WebClient 只是浪费。保留它才能维持"换模型不重建 HTTP 客户端"的既有口径
     * （{@code ProviderRegistryHotReloadTest}）。表内同样只留当前 key 一条。
     */
    private final Map<String, OpenAiApi> apis = new ConcurrentHashMap<>();

    private final Object buildLock = new Object();

    /**
     * 上一次因<b>配置版本变化</b>而淘汰运行态的 {@code config.version}（I2 多实例热生效的版本闸）。
     *
     * <p>为什么需要它：默认参数（temperature/topP/maxTokens）烙在已缓存实例的
     * {@link ProviderRuntime#options()} 上；写路径的 {@code evictAll()} 只清<b>写那个实例</b>的缓存，
     * 其他实例会一直用旧 options，直到重启。读侧不新增轮询：任何取用入口比一次
     * {@code configService.current().version()}（它自己按 Redis 版本号/DB 版本决定是否重载），
     * 与 {@code lastEvictedConfigVersion} 不等即清缓存。
     *
     * <p><b>初值 = {@link Long#MIN_VALUE}</b>：进程启动后第一次取用会"清一次空表"并把版本记为当时的值；
     * 之后的比较才有意义。
     */
    private final AtomicLong lastEvictedConfigVersion = new AtomicLong(Long.MIN_VALUE);

    /**
     * 上游流式连接的<b>读空闲</b>兜底（超时面治理批② 第 6 项；决策看板 {@code :16} = 300s）。
     *
     * <p>角色是"半开连接回收"：既不是首包/事件间判据（那两档 30s/90s 归看门狗），
     * 也不是段预算（300s 归看门狗，看板 {@code :14}）。三者到点行为的关系是
     * <b>watchdog 判据优先</b>：90s &lt; 300s ⇒ 静默类失败总由看门狗先判，
     * socket 层只在"连接已死但 TCP 未感知"（半开）时兜最后一道。
     *
     * <p>为什么不用 reactor-netty：本仓的编译依赖里<b>没有</b> {@code reactor-netty-http}
     * （{@code spring-webflux} 在，但 starter 未引入），{@code WebClient.builder()} 的缺省连接器
     * 实际落在 JDK {@code HttpClient}（Spring 6.2 的 {@code DefaultWebClientBuilder#initConnector}）。
     * 因此这里显式固定成 {@link JdkClientHttpConnector} 并设读超时 —— 依赖面零新增，
     * 语义面从"没有读超时"变成"有 300s 读空闲超时"。
     */
    static final Duration UPSTREAM_SOCKET_IDLE_TIMEOUT = Duration.ofSeconds(300);

    public AiProviderRegistry(AiConfigService configService, SpToolCallingManager toolCallingManager,
            BaseChatMemoryAdvisor memoryAdvisor, ObjectProvider<RetryTemplate> retryTemplate,
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
        ensureConfigVersion();
        Optional<AiConfigService.ResolvedProvider> resolved = this.configService.resolveDefault();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runtimeFor(resolved.get()));
    }

    /** 按解析结果取（或重建）实例。 */
    public ProviderRuntime runtimeFor(AiConfigService.ResolvedProvider resolved) {
        long version = ensureConfigVersion();
        String key = resolved.runtimeKey();
        ProviderRuntime cached = this.runtimes.get(key);
        if (cached != null && cached.modelId().equals(resolved.modelId())) {
            return cached;
        }
        synchronized (this.buildLock) {
            long lockedVersion = ensureConfigVersionLocked();
            cached = this.runtimes.get(key);
            if (cached != null && cached.modelId().equals(resolved.modelId())) {
                return cached;
            }
            // 同 key 换模型 = 轻量重绑：复用已有的 OpenAiApi（不重建 WebClient）。
            // api 缓存跨"版本淘汰"保留（见 {@link #apis}）—— 只要端点没变就不该换连接器。
            OpenAiApi api = cached != null ? cached.api() : this.apis.get(key);
            if (api == null) {
                api = newApi(resolved);
            }
            ProviderRuntime rebuilt = newRuntime(resolved, key, api);
            // I2：构建期间配置又变了（另一个实例写了默认参数）⇒ 本次实例的 options 可能已过期：
            // 清缓存且**不插入**，本次请求继续用它（在飞不中断），下一次取用按新配置重建。
            long afterBuild = this.configService.current().version();
            if (afterBuild != lockedVersion) {
                evictIfVersionChangedLocked(afterBuild);
                log.info("构建期间配置版本变化（{} → {}）：本次实例不入缓存 provider={} model={}",
                        lockedVersion, afterBuild, resolved.providerCode(), resolved.modelId());
                return rebuilt;
            }
            this.runtimes.keySet().removeIf(existing -> !existing.equals(key));
            this.runtimes.put(key, rebuilt);
            this.apis.keySet().removeIf(existing -> !existing.equals(key));
            this.apis.put(key, api);
            this.lastEvictedConfigVersion.set(lockedVersion);
            log.info("AI provider 运行态已{} provider={} model={} runtimeKey={} configVersion={}",
                    cached == null ? "装配" : "重绑", resolved.providerCode(), resolved.modelId(), key,
                    lockedVersion);
            return rebuilt;
        }
    }

    /**
     * I2 版本闸：配置版本变化 ⇒ 清空运行态缓存（下一次取用按新默认参数重建）。
     *
     * <p>清理与构建插入都在 {@link #buildLock} 内（互斥），因此不会出现"刚清空、旧构建又插回去"。
     * 在飞请求持有自己的 {@link ProviderRuntime} 引用，清表不改变它 —— 正在流的一轮不被打断。
     */
    private long ensureConfigVersion() {
        long version = this.configService.current().version();
        if (version == this.lastEvictedConfigVersion.get()) {
            return version;
        }
        synchronized (this.buildLock) {
            return ensureConfigVersionLocked();
        }
    }

    /** 已持 {@link #buildLock} 的版本闸（调用方保证互斥）。 */
    private long ensureConfigVersionLocked() {
        long version = this.configService.current().version();
        evictIfVersionChangedLocked(version);
        return version;
    }

    /**
     * 版本不同即清表并记下新版本（调用方必须持 {@link #buildLock}）。
     *
     * <p>只清 {@link #runtimes}（options/model/client），<b>保留</b> {@link #apis}：端点身份已在
     * RuntimeKey 里，任何端点级变更都会换 key；这里的版本变化只可能让"默认参数/模型名"过期。
     */
    private void evictIfVersionChangedLocked(long version) {
        long last = this.lastEvictedConfigVersion.get();
        if (version == last) {
            return;
        }
        int evicted = this.runtimes.size();
        this.runtimes.clear();
        this.lastEvictedConfigVersion.set(version);
        if (evicted > 0) {
            log.info("配置版本 {} → {}：淘汰 {} 个 provider 运行态（下一轮/续跑按新默认参数重建）", last,
                    version, evicted);
        }
    }

    // ── 请求级解析与逐请求 options（批③） ────────────────────────────────────

    /** 请求级覆盖（{@code AiChatRequest.Params} 在 config 层的样子；三值全空 = 没有覆盖）。 */
    public record Overrides(Double temperature, Double topP, Integer maxTokens) {

        public static final Overrides NONE = new Overrides(null, null, null);

        public boolean isEmpty() {
            return this.temperature == null && this.topP == null && this.maxTokens == null;
        }
    }

    /** 请求级解析（{@code modelId} 为空 ⇒ 默认链）；解析失败返回空（控制器已按 400 拦住，这里是窄窗口兜底）。 */
    public Optional<AiConfigService.ChatTarget> target(String requestedModelId) {
        return this.configService.resolveChatTarget(requestedModelId);
    }

    /**
     * <b>I3 精确绑定</b>：{@code providerId} 非空时只在这一家 provider 上解析（启用/key/模型 allowlist），
     * 失败即空 —— 不跨 provider、不换模型；{@code providerId == null} 时走旧的模型名兼容路径。
     *
     * <p>续跑（{@code ResumeService} 的身份校验与 {@code ResilientChatService#runtimeForSnapshot} 的
     * 运行态派生）必须用本重载：只按模型名解析在"同名模型挂两家"时会静默错配。
     */
    public Optional<AiConfigService.ChatTarget> target(Long providerId, String requestedModelId) {
        return this.configService.resolveChatTarget(providerId, requestedModelId);
    }

    /** 普通用户参数开关（{@code ai_setting['allow.userParams']}；缺省 = 允许）。 */
    public boolean allowUserParams() {
        return this.configService.allowUserParams();
    }

    /** 模型能力位（{@code supports_tool_calling}；无行可查时 true —— 与批③ 之前的行为等价）。 */
    public boolean supportsToolCalling(Long providerId, String modelId) {
        return this.configService.supportsToolCalling(providerId, modelId);
    }

    /**
     * 请求级运行态：按 {@code modelId} + 参数覆盖派生<b>一次性</b>实例。
     *
     * <h2>为什么不改缓存（批③ 的核心修复）</h2>
     * 批① 的 {@code runtimeFor} 用"同 key 换模型就轻量重绑"来表达默认模型变更；把它用到逐请求模型上
     * 会带来两个真实故障：
     * <ol>
     * <li><b>互相踩踏</b>：用户在两轮里交替选 A/B，缓存条目被反复重绑；</li>
     * <li><b>污染默认路径</b>：请求级模型覆盖了缓存条目的<b>默认选项</b>，下一轮"没选模型"的请求
     * 会带着上一轮的模型名。</li>
     * </ol>
     * 因此请求级模型/参数在<b>这里</b>派生：复用缓存条目里的 {@link OpenAiApi}（不重建 HTTP 客户端），
     * 只新建 model/client，<b>不读写也不淘汰</b>缓存 —— 缓存条目数恒等于 RuntimeKey 的个数。
     *
     * <p>
     * 与默认完全一致（模型相同、无覆盖）时直接返回缓存实例，避免每轮多建两个对象。
     */
    public ProviderRuntime forRequest(AiConfigService.ResolvedProvider resolved, String modelId,
            Overrides overrides) {
        ensureConfigVersion();
        ProviderRuntime base = runtimeFor(resolved);
        String effectiveModel = StringUtils.hasText(modelId) ? modelId.trim() : base.modelId();
        Overrides effectiveOverrides = overrides == null ? Overrides.NONE : overrides;
        if (effectiveModel.equals(base.modelId()) && effectiveOverrides.isEmpty()) {
            return base;
        }
        OpenAiChatOptions options = buildOptions(effectiveModel, effectiveOverrides);
        ChatModel model = newModel(base.api(), options, base.providerId());
        ChatClient client = ChatClient.builder(model).defaultAdvisors(this.memoryAdvisor).build();
        return new ProviderRuntime(base.providerId(), base.providerCode(), effectiveModel, base.baseUrl(),
                base.completionsPath(), base.runtimeKey(), options, base.api(), model, client);
    }

    /**
     * <b>L-26</b>：按快照<b>冻结的生效参数</b>派生一次性运行态（同一 run 的续跑专用）。
     *
     * <h2>与 {@link #forRequest} 的唯一差别：null 的语义</h2>
     * {@code forRequest} 的覆盖是"缺省即继承当前全局默认"；续跑要的恰恰相反 ——
     * 首轮当时 temperature 为 null（没有默认、用户也没传），续跑就<b>必须还是没有</b>：
     * 否则运维中途配了 {@code default.temperature} 就会把"同一轮"的后半段改到另一组参数上。
     * 因此这里先按当前默认装配 options，再用冻结值<b>逐项覆写（含清空）</b>：
     * 冻结 null ⇒ set 成 null，冻结值 ⇒ 照抄（不再做范围校验 —— 它已经是首轮生效过的值）。
     *
     * <p>其余项（{@code streamUsage} 等）仍取当前默认：本批冻结的是 L-26 裁决点名的五项
     * （provider/model/temperature/topP/maxTokens/paramsLocked），不是整份 options 快照。
     *
     * <p>与 {@code forRequest} 同一条纪律：<b>不读写缓存</b>（一次性运行态，复用 base 的 HTTP 客户端），
     * 因此续跑轮的模型/参数不会污染默认路径，也不会让缓存条目数随轮次增长。
     *
     * @param frozen 冻结的生效参数；null = 三值全空（等价于全 null 冻结，而不是"不冻结"）
     */
    public ProviderRuntime forFrozenRequest(AiConfigService.ResolvedProvider resolved, String modelId,
            Overrides frozen) {
        ensureConfigVersion();
        ProviderRuntime base = runtimeFor(resolved);
        String effectiveModel = StringUtils.hasText(modelId) ? modelId.trim() : base.modelId();
        OpenAiChatOptions options = buildOptions(effectiveModel, Overrides.NONE);
        options.setTemperature(frozen == null ? null : frozen.temperature());
        options.setTopP(frozen == null ? null : frozen.topP());
        options.setMaxTokens(frozen == null ? null : frozen.maxTokens());
        ChatModel model = newModel(base.api(), options, base.providerId());
        ChatClient client = ChatClient.builder(model).defaultAdvisors(this.memoryAdvisor).build();
        return new ProviderRuntime(base.providerId(), base.providerCode(), effectiveModel, base.baseUrl(),
                base.completionsPath(), base.runtimeKey(), options, base.api(), model, client);
    }


    /** 缓存条目数（取证/热生效用例：换 base-url 后仍是 1 条；条目身份变了才说明真的重建）。 */
    public int runtimeCount() {
        return this.runtimes.size();
    }

    public Set<String> runtimeKeys() {
        return new LinkedHashSet<>(this.runtimes.keySet());
    }

    /**
     * 清空缓存（KEK/配置重启级变更、写路径提交后的"本实例立即生效"、运维/测试手动清理）。
     *
     * <p><b>I2</b>：与构建插入同受 {@link #buildLock} 保护 —— 否则一次并发构建会在清空之后
     * 把<b>旧 options</b> 的实例插回去。同时把版本闸对齐到当前配置版本：之后其他实例靠版本比较
     * 自行清理（本方法只负责本实例，跨实例由版本闸收敛）。
     *
     * <p>在飞请求持有自己的 {@link ProviderRuntime} 引用，本方法不清、不改它 —— 正在流的一轮不中断。
     */
    public void evictAll() {
        long version;
        try {
            version = this.configService.current().version();
        }
        catch (RuntimeException ex) {
            // 读不到版本时对齐到 MIN_VALUE：下一次取用会再清一次（空表清空是幂等的），不会漏淘汰。
            version = Long.MIN_VALUE;
            log.warn("清空 provider 运行态时读不到配置版本（下一次取用会再对齐）：{}", ex.getMessage());
        }
        synchronized (this.buildLock) {
            int evicted = this.runtimes.size();
            this.runtimes.clear();
            // 手动/写路径的"全清"连 HTTP 客户端一起清（KEK 轮换等安全面操作）。
            this.apis.clear();
            this.lastEvictedConfigVersion.set(version);
            if (evicted > 0) {
                log.info("provider 运行态缓存已清空（{} 个，configVersion={}）：下一轮按最新配置重建", evicted,
                        version);
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private OpenAiApi newApi(AiConfigService.ResolvedProvider resolved) {
        return OpenAiApi.builder()
            .baseUrl(resolved.baseUrl())
            .apiKey(resolved.apiKey())
            .completionsPath(resolved.completionsPath())
            // 流式通道专用连接器（见 UPSTREAM_SOCKET_IDLE_TIMEOUT）：非流式走 RestClient，不受影响
            .webClientBuilder(upstreamWebClientBuilder())
            .build();
    }

    /**
     * 上游流式 {@link WebClient} 的构造点（唯一）：读空闲 300s 兜底。
     *
     * <p>只改"读操作的最长等待"（per-read idle），不是整条响应的总时长 —— 持续吐字的长轮
     * 不会被它掐断（每个分片都重置计时），只有真正死掉的半开连接才会被回收。
     */
    WebClient.Builder upstreamWebClientBuilder() {
        JdkClientHttpConnector connector = new JdkClientHttpConnector();
        connector.setReadTimeout(UPSTREAM_SOCKET_IDLE_TIMEOUT);
        return WebClient.builder().clientConnector(connector);
    }

    private ProviderRuntime newRuntime(AiConfigService.ResolvedProvider resolved, String key, OpenAiApi api) {
        OpenAiChatOptions options = buildOptions(resolved.modelId());
        ChatModel model = newModel(api, options, resolved.providerId());
        ChatClient client = ChatClient.builder(model).defaultAdvisors(this.memoryAdvisor).build();
        return new ProviderRuntime(resolved.providerId(), resolved.providerCode(), resolved.modelId(),
                resolved.baseUrl(), resolved.completionsPath(), key, options, api, model, client);
    }

    /**
     * 模型实例的唯一构造点。
     *
     * <p>{@code providerId} 传给补丁模型，用于<b>跨供应方推理元数据隔离</b>（请求层只回放与当前
     * 供应方同源的 {@code reasoningContent}）；它不是密钥、也不进请求体。
     */
    private ChatModel newModel(OpenAiApi api, OpenAiChatOptions options, Long providerId) {
        return new ReasoningAwareOpenAiChatModel(api, options, this.toolCallingManager,
                // 超时面治理批① 第 2 项：默认策略显式化为「仅瞬时故障 + 0.5s×2 上限 5s + 抖动」
                //（见 AiRetryDefaults；自有的 retryTemplate bean 由 AiRetryConfig 提供，
                // 这里的回落只为"非 Spring 装配/单测"与 bean 缺失两种场景兜底）
                this.retryTemplate.getIfAvailable(AiRetryDefaults::retryTemplate),
                this.observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                providerId);
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
        return buildOptions(modelId, Overrides.NONE);
    }

    /**
     * 逐请求 options：全局默认（{@code ai_setting}）之上叠<b>本轮请求的覆盖</b>。
     *
     * <p>
     * 覆盖值的容错与 {@code ai_setting} 同一条纪律（M3）：越界/非有限/非正一律 WARN + <b>忽略该项</b>，
     * 其余项照常生效 —— 控制器已按 400 {@code INVALID_PARAM} 拦过一遍，这里是"并发改配置/旁路调用"
     * 的窄窗口兜底，不让一条脏值把整轮打成 500。
     *
     * <p>
     * {@code streamUsage} 由基底保证为 true（全局默认段），覆盖只碰三值，<b>永远不动它</b> ——
     * {@code done.usage} 的来源不能因为用户调了 temperature 而消失。
     */
    OpenAiChatOptions buildOptions(String modelId, Overrides overrides) {
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
        if (overrides != null && !overrides.isEmpty()) {
            if (overrides.temperature() != null) {
                applyRange("request.temperature", overrides.temperature(), 0d, 2d, options::setTemperature);
            }
            if (overrides.topP() != null) {
                applyRange("request.topP", overrides.topP(), 0d, 1d, options::setTopP);
            }
            if (overrides.maxTokens() != null) {
                applyPositiveInt("request.maxTokens", overrides.maxTokens(), options::setMaxTokens);
            }
        }
        return options;
    }

    private void applyRange(String label, double value, double min, double max,
            java.util.function.Consumer<Double> setter) {
        if (!Double.isFinite(value) || value < min || value > max) {
            log.warn("{} 的值 {} 越界（[{},{}]）：忽略该项，沿用全局默认", label, value, min, max);
            return;
        }
        setter.accept(value);
    }

    private void applyPositiveInt(String label, int value, java.util.function.Consumer<Integer> setter) {
        if (value <= 0) {
            log.warn("{} 的值 {} 必须为正整数：忽略该项，沿用全局默认", label, value);
            return;
        }
        setter.accept(value);
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
