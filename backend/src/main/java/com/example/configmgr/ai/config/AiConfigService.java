package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.config.db.AiSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置读路径（模型配置专项 §3.4）：<b>只读快照</b> + 版本号。
 *
 * <h2>为什么是快照</h2>
 * AI 热路径每轮都要问"现在用哪个 provider / 哪个模型 / key 是什么"。读路径<b>只读内存快照</b>
 * （{@link ConfigSnapshot}），不得退化成"每轮查两张表"（设计稿 §9 的最后一条风险）。
 * 版本号的取法：
 * <ul>
 * <li>DB 真源 = {@code ai_setting['config.version']}（V4 预置 1）；</li>
 * <li>跨实例失效信号 = Redis {@code ai:cfg:ver}（键族与 B23L1 的 {@code ai:lease:}/
 * {@code ai:lock:} 对齐）；写路径（批②）改完 DB 版本号再 {@code INCR} 本键；</li>
 * <li>比较频率 = 每实例 {@code app.ai.config.reload-interval}（缺省 1s）至多一次；Redis 不可用
 * 时退化为本地版本比对（单实例正确；多实例"改配置只有本实例生效"登记为知悉风险，由 B23L1 批③
 * 的 Redis 设施收口）。</li>
 * </ul>
 *
 * <h2>env 兜底与"种子等价"</h2>
 * 表为空且 env key 非空时，本类会<b>临时合成</b>一行与 {@link AiConfigSeeder} 将写入内容逐值相同的
 * provider（{@code code=deepseek}、base-url/completions-path/model 全取今天
 * {@code spring.ai.openai.*} 的同一套回落规则）。这样"启动种子还没跑完就来了一个请求"也不会出现
 * 一个可用性抖动窗口；种子落库后这段兜底自然不再参与（表非空 ⇒ 不用合成行）。
 *
 * <h2>key 解析（fail-closed）</h2>
 * <ul>
 * <li>{@code key_ref=ENV} ⇒ 运行时从 {@code spring.ai.openai.api-key} 读（env key <b>从不</b>落库）；</li>
 * <li>{@code key_ref=DB} ⇒ 用 {@link AiKeyCipher} 解密 {@code key_cipher}；KEK 缺失/不符 ⇒
 * {@code KEY_UNREADABLE}，<b>绝不</b>回落到 env key（否则"为什么突然用错 key"无从排查）；</li>
 * <li>{@code key_ref=NONE} ⇒ 缺失。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiConfigService {

    /** 跨实例失效信号键（设计稿 §3.4 / §4 Redis 键族）。 */
    public static final String REDIS_VERSION_KEY = "ai:cfg:ver";

    /** 种子/合成行的稳定机读名。 */
    public static final String ENV_PROVIDER_CODE = "deepseek";

    public static final String ENV_PROVIDER_DISPLAY_NAME = "DeepSeek";

    /** 种子行的上下文窗口占位值（UI 可改；上下文统计专项的分母初值）。 */
    public static final int ENV_SEED_MAX_CONTEXT = 128000;

    /** 无 provider 时的 503 码与文案 —— 与批①之前<b>逐字相同</b>（前端与 E2E 的兼容基线）。 */
    public static final String CODE_UNAVAILABLE = "AI_UNAVAILABLE";

    public static final String LEGACY_UNAVAILABLE_REASON =
            "AI 能力未启用：未配置 AI_API_KEY（或 DEEPSEEK_API_KEY）环境变量，AI 端点已惰性降级";

    public static final String CODE_KEY_MISSING = "AI_KEY_MISSING";

    public static final String CODE_KEY_UNREADABLE = AiKeyCipher.CODE_KEY_UNREADABLE;

    private static final String DEFAULT_MODEL_FALLBACK = "deepseek-flash";

    private final AiProviderRepository providerRepository;

    private final AiProviderModelRepository modelRepository;

    private final AiSettingRepository settingRepository;

    private final Environment environment;

    private final AiKeyCipher cipher;

    private final AiProperties properties;

    private final OpenAiConnectionProperties connectionProperties;

    private final OpenAiChatProperties chatProperties;

    /** 可为 null（单测/无 Redis 装配）：此时版本比对退化为 DB 版本。 */
    private final StringRedisTemplate redis;

    private final Object refreshLock = new Object();

    /** 按 provider code 记"已告警过解不开"，避免可用性探活逐秒刷 WARN。 */
    private final Map<String, Boolean> unreadableWarned = new ConcurrentHashMap<>();

    private volatile ConfigSnapshot snapshot;

    private volatile long lastCheckAtMs;

    private volatile String lastRedisVersion;

    /** 上一次加载是否失败（M2）：失败 ⇒ 下一个 tick 无条件重试，不依赖版本号变化。 */
    private volatile boolean lastLoadFailed;

    public AiConfigService(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiSettingRepository settingRepository, Environment environment, AiKeyCipher cipher,
            AiProperties properties, OpenAiConnectionProperties connectionProperties,
            OpenAiChatProperties chatProperties, StringRedisTemplate redis) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingRepository = settingRepository;
        this.environment = environment;
        this.cipher = cipher;
        this.properties = properties;
        this.connectionProperties = connectionProperties;
        this.chatProperties = chatProperties;
        this.redis = redis;
    }

    // ── 快照 ────────────────────────────────────────────────────────────────

    /** 当前快照（必要时按版本号重载）。读路径的唯一入口。 */
    public ConfigSnapshot current() {
        long now = System.currentTimeMillis();
        ConfigSnapshot snap = this.snapshot;
        if (snap == null || now - this.lastCheckAtMs >= reloadIntervalMs()) {
            synchronized (this.refreshLock) {
                now = System.currentTimeMillis();
                snap = this.snapshot;
                if (snap == null || now - this.lastCheckAtMs >= reloadIntervalMs()) {
                    this.lastCheckAtMs = now;
                    ReloadCheck check = checkReload(snap);
                    if (check.reload()) {
                        applyLoad(load(), check.observedRedisVersion());
                    }
                }
            }
        }
        return this.snapshot;
    }

    /** 强制重载（种子写完、批② 写路径、测试用）。 */
    public ConfigSnapshot reload() {
        synchronized (this.refreshLock) {
            applyLoad(load(), readRedisVersion());
            this.lastCheckAtMs = System.currentTimeMillis();
            return this.snapshot;
        }
    }

    /**
     * 应用一次加载结果：<b>只在加载成功之后</b>推进 {@link #lastRedisVersion}（M2）。
     *
     * <h2>为什么"先推进版本、后加载"是个洞</h2>
     * Redis 的 {@code ai:cfg:ver} 是"本轮看到的版本"。旧实现一看到新版本就把它记进
     * {@code lastRedisVersion}，然后才去读库；若这一次读库失败（Hikari 抖动 / 迁移中 / 表锁），
     * 快照仍是旧的那份，而下一个 tick 会看到"版本没再变"直接跳过 —— <b>坏配置会被永久冻住</b>，
     * 直到下一个版本号（下一次人工改配置）才恢复。
     *
     * <p>现在的口径：版本只在 {@code load} 成功后成为 {@code lastRedisVersion}；
     * 失败的加载置 {@link #lastLoadFailed}，下一个 tick 无条件重试（与版本号是否变化无关）。
     */
    private void applyLoad(LoadOutcome outcome, String observedRedisVersion) {
        this.snapshot = outcome.snapshot();
        this.lastLoadFailed = !outcome.ok();
        if (outcome.ok() && observedRedisVersion != null) {
            this.lastRedisVersion = observedRedisVersion;
        }
        else if (outcome.ok()) {
            this.lastRedisVersion = null;
        }
    }

    private long reloadIntervalMs() {
        var interval = this.properties.getConfig().getReloadInterval();
        return interval == null ? 0L : Math.max(0L, interval.toMillis());
    }

    /** 一次版本比对的结果；{@code observedRedisVersion} 只用于"加载成功后"推进，不在比对时落地。 */
    private record ReloadCheck(boolean reload, String observedRedisVersion) {
    }

    private record LoadOutcome(ConfigSnapshot snapshot, boolean ok) {
    }

    private ReloadCheck checkReload(ConfigSnapshot snap) {
        if (snap == null || this.lastLoadFailed) {
            // 从未加载成功 / 上次加载失败 ⇒ 必须重试（重试与版本号是否变化无关）
            return new ReloadCheck(true, readRedisVersion());
        }
        String redisVersion = readRedisVersion();
        if (redisVersion != null) {
            return new ReloadCheck(!redisVersion.equals(this.lastRedisVersion), redisVersion);
        }
        // Redis 不可用 / 没有失效信号键 ⇒ 退化为本地版本比对（一次主键查询）
        return new ReloadCheck(readDbVersion(snap.version()) != snap.version(), null);
    }

    private String readRedisVersion() {
        if (this.redis == null) {
            return null;
        }
        try {
            return this.redis.opsForValue().get(REDIS_VERSION_KEY);
        }
        catch (Exception ex) {
            log.debug("读取 {} 失败（Redis 不可用？退化本地版本比对）：{}", REDIS_VERSION_KEY, ex.getMessage());
            return null;
        }
    }

    private long readDbVersion(long fallback) {
        try {
            return this.settingRepository.findBySettingKey(AiSetting.CONFIG_VERSION)
                .map(AiSetting::getValueJson)
                .map(AiConfigService::parseVersion)
                .orElse(fallback);
        }
        catch (Exception ex) {
            log.debug("读取 config.version 失败：{}", ex.getMessage());
            return fallback;
        }
    }

    private LoadOutcome load() {
        try {
            List<AiProvider> providers = this.providerRepository.findAllByOrderBySortOrderAscIdAsc();
            List<Long> ids = providers.stream().map(AiProvider::getId).filter(java.util.Objects::nonNull).toList();
            List<AiProviderModel> models = ids.isEmpty() ? List.of()
                    : this.modelRepository.findByProviderIdInOrderBySortOrderAscIdAsc(ids);
            Map<String, String> settings = new LinkedHashMap<>();
            this.settingRepository.findAll().forEach(setting -> settings.put(setting.getSettingKey(),
                    setting.getValueJson()));
            long version = parseVersion(settings.get(AiSetting.CONFIG_VERSION));
            return new LoadOutcome(new ConfigSnapshot(version, providers, models, settings), true);
        }
        catch (Exception ex) {
            // 读库失败不能让 /health 或 /chat 抛 500：保留上一份好快照，没有就回空快照（= 不可用）。
            // ok=false 会被 applyLoad 记进 lastLoadFailed ⇒ 下一个 tick 无条件重试（M2）。
            log.warn("配置快照加载失败（保留上一份好快照 / 退化为空快照，下次版本比对时重试）：{}", ex.getMessage());
            ConfigSnapshot previous = this.snapshot;
            return new LoadOutcome(previous != null ? previous : ConfigSnapshot.empty(), false);
        }
    }

    private static long parseVersion(String raw) {
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException ex) {
            return 0L;
        }
    }

    // ── provider / model 视图 ────────────────────────────────────────────────

    /** 库里的 provider 行（不含合成行；设置页与 {@code GET /api/ai/providers} 用）。 */
    public List<AiProvider> listProviders() {
        return current().providers();
    }

    public List<AiProviderModel> listModels() {
        return current().models();
    }

    public List<AiProviderModel> modelsOf(Long providerId) {
        if (providerId == null) {
            return List.of();
        }
        return current().models().stream().filter(model -> providerId.equals(model.getProviderId())).toList();
    }

    public Optional<AiProviderModel> model(Long providerId, String modelId) {
        return modelsOf(providerId).stream().filter(model -> model.getModelId().equals(modelId)).findFirst();
    }

    /**
     * 参与运行时的 provider 序列：库里的行；<b>仅当</b>库为空且 env key 非空时追加一行临时合成行。
     */
    public List<AiProvider> effectiveProviders() {
        return effectiveProviders(current());
    }

    private List<AiProvider> effectiveProviders(ConfigSnapshot snap) {
        if (!snap.providers().isEmpty()) {
            return snap.providers();
        }
        return StringUtils.hasText(envApiKey()) ? List.of(envSeedProvider()) : List.of();
    }

    /** 合成的 env 种子行 —— 内容与 {@link AiConfigSeeder} 将写入的行逐值一致（不是第二套真相）。 */
    public AiProvider envSeedProvider() {
        AiProvider provider = new AiProvider();
        provider.setCode(ENV_PROVIDER_CODE);
        provider.setDisplayName(ENV_PROVIDER_DISPLAY_NAME);
        provider.setBaseUrl(resolveEnvBaseUrl());
        provider.setCompletionsPath(resolveEnvCompletionsPath());
        provider.setModelsPath("/models");
        provider.setKeyRef(AiProvider.KEY_REF_ENV);
        provider.setKeyLast4(AiKeyCipher.last4(envApiKey()));
        provider.setSource(AiProvider.SOURCE_ENV_SEED);
        provider.setEnabled(true);
        return provider;
    }

    /** 种子模型（与合成行配套；{@code is_default=true}、{@code max_context} 取占位值）。 */
    public AiProviderModel envSeedModel() {
        AiProviderModel model = new AiProviderModel();
        model.setModelId(resolveEnvModelId());
        model.setDisplayName(resolveEnvModelId());
        model.setMaxContext(ENV_SEED_MAX_CONTEXT);
        model.setSupportsToolCalling(true);
        model.setEnabled(true);
        model.setDefault(true);
        return model;
    }

    // ── 解析：默认 provider / 模型 ────────────────────────────────────────────

    /**
     * 默认运行目标（批① 的唯一解析口径）：第一个"enabled 且 key 可解析"的 provider + 它的默认模型。
     *
     * <p>
     * 模型优先级（设计稿 §3.4）：{@code ai_model.is_default} → 该 provider 首个 enabled 模型 →
     * {@code ai_setting['default.modelId']} → env {@code AI_MODEL}。请求级 modelId 是批③ 的事。
     */
    public Optional<ResolvedProvider> resolveDefault() {
        ConfigSnapshot snap = current();
        for (AiProvider provider : effectiveProviders(snap)) {
            if (!provider.isEnabled()) {
                continue;
            }
            KeyResolution key = resolveKey(provider);
            if (key.status() != KeyStatus.OK) {
                continue;
            }
            return Optional.of(new ResolvedProvider(provider.getId(), provider.getCode(), provider.getBaseUrl(),
                    provider.getCompletionsPath(), resolveModelId(snap, provider), key.apiKey(), key.fingerprint(),
                    provider.getId() == null));
        }
        return Optional.empty();
    }

    /** 指定 provider 的运行目标（批③ 的"按请求选 provider"会复用；批① 供 /health 逐条状态用）。 */
    public Optional<ResolvedProvider> resolveProvider(AiProvider provider) {
        if (provider == null || !provider.isEnabled()) {
            return Optional.empty();
        }
        KeyResolution key = resolveKey(provider);
        if (key.status() != KeyStatus.OK) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProvider(provider.getId(), provider.getCode(), provider.getBaseUrl(),
                provider.getCompletionsPath(), resolveModelId(current(), provider), key.apiKey(), key.fingerprint(),
                provider.getId() == null));
    }

    /** 模型解析（见 {@link #resolveDefault()} 的优先级）。 */
    public String resolveModelId(ConfigSnapshot snap, AiProvider provider) {
        List<AiProviderModel> models = snap.models()
            .stream()
            .filter(model -> provider.getId() == null || provider.getId().equals(model.getProviderId()))
            .filter(AiProviderModel::isEnabled)
            .toList();
        Optional<AiProviderModel> flagged = models.stream().filter(AiProviderModel::isDefault).findFirst();
        if (flagged.isPresent()) {
            return flagged.get().getModelId();
        }
        if (!models.isEmpty()) {
            return models.get(0).getModelId();
        }
        String configured = snap.settings().get(AiSetting.DEFAULT_MODEL_ID);
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        return resolveEnvModelId();
    }

    // ── key 解析 ────────────────────────────────────────────────────────────

    public enum KeyStatus {

        /** 可解析（env 有值或密文可解）。 */
        OK,

        /** 没有声明的 key（{@code key_ref=NONE} 或 env 为空）。 */
        MISSING,

        /** 声明了 DB 托管 key，但本进程解不开（KEK 缺失/不符/密文损坏）。 */
        UNREADABLE

    }

    public record KeyResolution(KeyStatus status, String apiKey, String fingerprint) {

        static KeyResolution ok(String apiKey, String fingerprint) {
            return new KeyResolution(KeyStatus.OK, apiKey, fingerprint);
        }

        static KeyResolution missing() {
            return new KeyResolution(KeyStatus.MISSING, null, null);
        }

        static KeyResolution unreadable() {
            return new KeyResolution(KeyStatus.UNREADABLE, null, null);
        }
    }

    /** 解析一行的 key（fail-closed；DB 解不开<b>不</b>回落 env）。 */
    public KeyResolution resolveKey(AiProvider provider) {
        if (provider == null || !StringUtils.hasText(provider.getKeyRef())) {
            return KeyResolution.missing();
        }
        if (AiProvider.KEY_REF_ENV.equals(provider.getKeyRef())) {
            String envKey = envApiKey();
            if (!StringUtils.hasText(envKey)) {
                return KeyResolution.missing();
            }
            return KeyResolution.ok(envKey, fingerprintOf(provider, envKey));
        }
        if (AiProvider.KEY_REF_DB.equals(provider.getKeyRef())) {
            if (!StringUtils.hasText(provider.getKeyCipher())) {
                return KeyResolution.missing();
            }
            try {
                String plain = this.cipher.decrypt(provider.getKeyCipher(), provider.getCode(), provider.getKeyRef());
                if (!StringUtils.hasText(plain)) {
                    return KeyResolution.missing();
                }
                return KeyResolution.ok(plain, fingerprintOf(provider, plain));
            }
            catch (AiKeyCipher.KeyCipherException ex) {
                warnOnceUnreadable(provider, ex);
                return KeyResolution.unreadable();
            }
        }
        return KeyResolution.missing();
    }

    private String fingerprintOf(AiProvider provider, String apiKey) {
        if (StringUtils.hasText(provider.getKeyFingerprint())) {
            return provider.getKeyFingerprint();
        }
        if (this.cipher.isConfigured()) {
            try {
                return this.cipher.fingerprint(apiKey);
            }
            catch (AiKeyCipher.KeyCipherException ex) {
                log.debug("计算 key 指纹失败 provider={}：{}", provider.getCode(), ex.getMessage());
            }
        }
        // 无 KEK 时，env key 只能重启才变 ⇒ 用固定标记作缓存键成分（不含任何 key 片段）
        return AiProvider.KEY_REF_ENV.equals(provider.getKeyRef()) ? "env" : null;
    }

    private void warnOnceUnreadable(AiProvider provider, AiKeyCipher.KeyCipherException ex) {
        String key = String.valueOf(provider.getCode());
        if (this.unreadableWarned.putIfAbsent(key, Boolean.TRUE) == null) {
            log.warn("provider={} 的 key 无法解密（{}）：判 {}，不回落 env key。恢复路径 = 恢复 AI_SECRET_KEK"
                    + " 或重新录入 key", key, ex.getCode(), CODE_KEY_UNREADABLE);
        }
    }

    // ── 可用性 ──────────────────────────────────────────────────────────────

    public record Availability(boolean available, String code, String reason) {
    }

    /** 动态可用性判据（设计稿 §3.4）：存在 ≥1 个 enabled 且 key 可解析的 provider。 */
    public Availability availability() {
        ConfigSnapshot snap = current();
        List<AiProvider> enabled = effectiveProviders(snap).stream().filter(AiProvider::isEnabled).toList();
        if (enabled.isEmpty()) {
            // 空表 + 无 env：与批①之前逐字相同的 503（前端与 E2E 的兼容基线）
            return new Availability(false, CODE_UNAVAILABLE, LEGACY_UNAVAILABLE_REASON);
        }
        int missing = 0;
        int unreadable = 0;
        for (AiProvider provider : enabled) {
            switch (resolveKey(provider).status()) {
                case OK -> {
                    return new Availability(true, "AI_AVAILABLE", null);
                }
                case UNREADABLE -> unreadable++;
                case MISSING -> missing++;
            }
        }
        if (unreadable > 0) {
            return new Availability(false, CODE_KEY_UNREADABLE,
                    "AI 能力未启用：provider 的 key 无法解密（KEK 缺失/变更或密文损坏），请恢复 AI_SECRET_KEK 或重新录入 key");
        }
        return new Availability(false, CODE_KEY_MISSING,
                "AI 能力未启用：已配置的 provider 均无可用 key（key_ref=ENV 需设置 AI_API_KEY/DEEPSEEK_API_KEY，"
                            + "或到设置页录入 key）");
    }

    public boolean hasUsableProvider() {
        return availability().available();
    }

    // ── env（今天 spring.ai.openai.* 的同一套回落规则） ────────────────────────

    /** {@code spring.ai.openai.api-key}（即 AI_API_KEY，DEEPSEEK_API_KEY 兜底）。 */
    public String envApiKey() {
        String chatKey = this.chatProperties.getApiKey();
        if (StringUtils.hasText(chatKey)) {
            return chatKey;
        }
        return this.connectionProperties.getApiKey();
    }

    public String resolveEnvBaseUrl() {
        String chatBaseUrl = this.chatProperties.getBaseUrl();
        return StringUtils.hasText(chatBaseUrl) ? chatBaseUrl : this.connectionProperties.getBaseUrl();
    }

    public String resolveEnvCompletionsPath() {
        String path = this.chatProperties.getCompletionsPath();
        return StringUtils.hasText(path) ? path : "/chat/completions";
    }

    public String resolveEnvModelId() {
        if (this.chatProperties.getOptions() != null
                && StringUtils.hasText(this.chatProperties.getOptions().getModel())) {
            return this.chatProperties.getOptions().getModel();
        }
        return DEFAULT_MODEL_FALLBACK;
    }

    /** 全局默认参数（{@code ai_setting}）里的一项；缺省返回空。 */
    public Optional<String> setting(String key) {
        return Optional.ofNullable(current().settings().get(key)).filter(StringUtils::hasText);
    }

    // ── 快照与解析结果 ────────────────────────────────────────────────────────

    public record ConfigSnapshot(long version, List<AiProvider> providers, List<AiProviderModel> models,
            Map<String, String> settings) {

        public static ConfigSnapshot empty() {
            return new ConfigSnapshot(0L, List.of(), List.of(), Map.of());
        }

        public ConfigSnapshot {
            providers = List.copyOf(providers);
            models = List.copyOf(models);
            settings = Map.copyOf(settings);
        }

        public List<AiProviderModel> modelsOf(Long providerId) {
            return this.models.stream().filter(model -> model.getProviderId().equals(providerId)).toList();
        }
    }

    /**
     * 一次解析出的运行目标。
     *
     * @param providerId 库内 id；{@code null} = env 合成行（还没被种子写入）
     * @param apiKey 明文 key（只活在本对象里，不落日志、不出接口）
     * @param envBacked 是否来自 env 合成行 / {@code key_ref=ENV}
     */
    public record ResolvedProvider(Long providerId, String providerCode, String baseUrl, String completionsPath,
            String modelId, String apiKey, String fingerprint, boolean envBacked) {

        /** 运行时缓存键的成分之一（不含明文 key）。 */
        public String runtimeKey() {
            return runtimeKeyOf(this);
        }
    }

    /** RuntimeKey = providerId | keyFingerprint | baseUrl | completionsPath（模型刻意不在内）。 */
    public static String runtimeKeyOf(ResolvedProvider resolved) {
        return String.valueOf(resolved.providerId() == null ? "env" : resolved.providerId()) + "|"
                + String.valueOf(resolved.fingerprint()) + "|" + String.valueOf(resolved.baseUrl()) + "|"
                + String.valueOf(resolved.completionsPath());
    }

    /** 诊断视图（不含 key）：provider 行 + key 状态。 */
    public List<Map<String, Object>> describeProviders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProvider provider : effectiveProviders()) {
            KeyResolution key = resolveKey(provider);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", provider.getId());
            row.put("code", provider.getCode());
            row.put("name", provider.getDisplayName());
            row.put("enabled", provider.isEnabled());
            row.put("keyConfigured", provider.isKeyDeclared());
            row.put("keyRef", provider.getKeyRef());
            row.put("source", provider.getSource());
            row.put("status", provider.isEnabled() ? key.status().name() : "DISABLED");
            out.add(row);
        }
        return out;
    }
}
