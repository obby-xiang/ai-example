package com.example.configmgr.ai.config;

import com.example.configmgr.common.ErrorCodes;

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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置读路径（模型配置专项 §3.4）：<b>只读快照</b> + 版本号。
 *
 * <h2>为什么是快照</h2>
 * AI 热路径每轮都要问"现在用哪个 provider / 哪个模型 / key 是什么"。读路径<b>只读内存快照</b>
 * （{@link ConfigSnapshot}），不得退化成"每轮查两张表"（设计稿 §9 的最后一条风险）。
 * 版本号的取法：
 * <ul>
 * <li>唯一真源 = {@code ai_setting['config.version']}，每个检查周期都必须核对；</li>
 * <li>检查频率 = 每实例 {@code app.ai.config.reload-interval}（缺省 1s）至多一次；刷新 single-flight，
 * 已有读者在在途刷新期间使用 last-good 快照，不在实例锁内等待 DB I/O。</li>
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
 * {@code AI_KEY_UNREADABLE}，<b>绝不</b>回落到 env key（否则"为什么突然用错 key"无从排查）；</li>
 * <li>{@code key_ref=NONE} ⇒ 缺失。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiConfigService {

    private static final long REFRESH_WAIT_SECONDS = 5L;

    /** 种子/合成行的稳定机读名。 */
    public static final String ENV_PROVIDER_CODE = "deepseek";

    public static final String ENV_PROVIDER_DISPLAY_NAME = "DeepSeek";

    /** 种子行的上下文窗口占位值（UI 可改；上下文统计专项的分母初值）。 */
    public static final int ENV_SEED_MAX_CONTEXT = 128000;

    /** 无 provider 时的 503 码。 */
    public static final String CODE_UNAVAILABLE = ErrorCodes.AI_UNAVAILABLE;

    /** 无 provider 时的 503 文案（R-12：指向设置页；码仍是 {@code AI_UNAVAILABLE}）。 */
    public static final String UNAVAILABLE_REASON =
            "AI 能力未启用：请在「设置 → AI」配置供应商与 API Key";

    public static final String CODE_KEY_MISSING = ErrorCodes.AI_KEY_MISSING;

    public static final String CODE_KEY_UNREADABLE = ErrorCodes.AI_KEY_UNREADABLE;

    public static final String KEY_MISSING_REASON =
            "AI 能力未启用：请到「设置 → AI」为供应商录入 API Key";

    public static final String KEY_UNREADABLE_REASON =
            "AI 能力未启用：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置";

    /** 有 provider 有 key、但默认目标解析不出自己的模型时的 503 码。 */
    public static final String CODE_NO_MODEL_CONFIGURED = ErrorCodes.NO_MODEL_CONFIGURED;

    public static final String NO_MODEL_REASON =
            "AI 默认模型未配置：请在「设置 → AI」为供应商添加模型并选择默认模型";

    /** 默认目标硬绑定：绑定的供应商停用 ⇒ 整条 AI 不可用（不静默换别家）。 */
    public static final String BOUND_PROVIDER_DISABLED_REASON =
            "AI 默认供应商已停用：请在「设置 → AI」启用该供应商，或改选默认模型";

    /** 默认目标硬绑定：绑定的供应商行已不存在（硬删/脏配置）⇒ 整条 AI 不可用。 */
    public static final String BOUND_PROVIDER_MISSING_REASON =
            "AI 默认供应商不存在：请在「设置 → AI」重新选择默认模型";

    private static final String DEFAULT_MODEL_FALLBACK = "deepseek-flash";

    private final AiProviderRepository providerRepository;

    private final AiProviderModelRepository modelRepository;

    private final AiSettingRepository settingRepository;

    private final Environment environment;

    private final AiKeyCipher cipher;

    private final AiProperties properties;

    private final OpenAiConnectionProperties connectionProperties;

    private final OpenAiChatProperties chatProperties;

    private final AtomicReference<CompletableFuture<RefreshOutcome>> refreshInFlight = new AtomicReference<>();

    /** 按 provider code 记"已告警过解不开"，避免可用性探活逐秒刷 WARN。 */
    private final Map<String, Boolean> unreadableWarned = new ConcurrentHashMap<>();

    private volatile ConfigSnapshot snapshot;

    private volatile long lastCheckAtMs;

    /** 上一次加载是否失败（M2）：失败 ⇒ 下一个 tick 无条件重试，不依赖版本号变化。 */
    private volatile boolean lastLoadFailed;

    public AiConfigService(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiSettingRepository settingRepository, Environment environment, AiKeyCipher cipher,
            AiProperties properties, OpenAiConnectionProperties connectionProperties,
            OpenAiChatProperties chatProperties) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingRepository = settingRepository;
        this.environment = environment;
        this.cipher = cipher;
        this.properties = properties;
        this.connectionProperties = connectionProperties;
        this.chatProperties = chatProperties;
    }

    // ── 快照 ────────────────────────────────────────────────────────────────

    /** 当前快照（必要时按版本号重载）。已有读者在刷新期间始终使用 last-good。 */
    public ConfigSnapshot current() {
        ConfigSnapshot snap = this.snapshot;
        long now = System.currentTimeMillis();
        if (snap == null || now - this.lastCheckAtMs >= reloadIntervalMs()) {
            CompletableFuture<RefreshOutcome> flight = startRefresh(false);
            if (snap == null) {
                awaitRefresh(flight, false);
            }
        }
        ConfigSnapshot current = this.snapshot;
        if (current == null) {
            throw new IllegalStateException("initial AI config snapshot is unavailable");
        }
        return current;
    }

    /** 强制重载（种子或配置写提交后）。 */
    public ConfigSnapshot reload() {
        return reloadWithOutcome().snapshot();
    }

    /**
     * 一次强制重载的结局。
     *
     * @param snapshot 成功快照或 last-good；从未加载成功时为 {@code null}
     * @param ok 本次是否取得一致且完整的新快照
     */
    public record ReloadResult(ConfigSnapshot snapshot, boolean ok) {
    }

    /** 强制重载并如实报告结局；等待超时不取消在途加载。 */
    public ReloadResult reloadWithOutcome() {
        while (true) {
            RefreshOutcome outcome = awaitRefresh(startRefresh(true), true);
            if (outcome.forced()) {
                return outcome.result();
            }
        }
    }

    private RefreshOutcome awaitRefresh(CompletableFuture<RefreshOutcome> flight, boolean forced) {
        try {
            return flight.get(REFRESH_WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (Exception ex) {
            log.debug("config refresh wait ended without a completed result error={}", ex.getMessage());
        }
        return new RefreshOutcome(new ReloadResult(this.snapshot, false), forced);
    }

    private record RefreshOutcome(ReloadResult result, boolean forced) {
    }

    private CompletableFuture<RefreshOutcome> startRefresh(boolean force) {
        while (true) {
            CompletableFuture<RefreshOutcome> existing = this.refreshInFlight.get();
            if (existing != null) {
                return existing;
            }
            CompletableFuture<RefreshOutcome> candidate = new CompletableFuture<>();
            if (!this.refreshInFlight.compareAndSet(null, candidate)) {
                continue;
            }
            try {
                candidate.complete(performRefresh(force));
            }
            catch (RuntimeException ex) {
                applyLoad(new LoadOutcome(this.snapshot, false));
                this.lastCheckAtMs = System.currentTimeMillis();
                log.warn("config refresh failed (keeping last good snapshot) error={}", ex.getMessage());
                candidate.complete(new RefreshOutcome(new ReloadResult(this.snapshot, false), force));
            }
            finally {
                this.refreshInFlight.compareAndSet(candidate, null);
            }
            return candidate;
        }
    }

    private RefreshOutcome performRefresh(boolean force) {
        ConfigSnapshot snap = this.snapshot;
        if (!force) {
            if (!checkReload(snap)) {
                this.lastCheckAtMs = System.currentTimeMillis();
                return new RefreshOutcome(new ReloadResult(snap, true), false);
            }
            LoadOutcome outcome = load();
            applyLoad(outcome);
            this.lastCheckAtMs = System.currentTimeMillis();
            return new RefreshOutcome(new ReloadResult(this.snapshot, outcome.ok()), false);
        }
        LoadOutcome outcome = load();
        applyLoad(outcome);
        this.lastCheckAtMs = System.currentTimeMillis();
        return new RefreshOutcome(new ReloadResult(this.snapshot, outcome.ok()), true);
    }

    private void applyLoad(LoadOutcome outcome) {
        this.snapshot = outcome.snapshot();
        this.lastLoadFailed = !outcome.ok();
    }

    private long reloadIntervalMs() {
        var interval = this.properties.getConfig().getReloadInterval();
        return interval == null ? 0L : Math.max(0L, interval.toMillis());
    }

    private record LoadOutcome(ConfigSnapshot snapshot, boolean ok) {
    }

    private boolean checkReload(ConfigSnapshot snap) {
        if (snap == null || this.lastLoadFailed) {
            return true;
        }
        Long dbVersion = readDbVersion();
        return dbVersion != null && dbVersion != snap.version();
    }

    private Long readDbVersion() {
        try {
            return this.settingRepository.findBySettingKey(AiSetting.CONFIG_VERSION)
                .map(AiSetting::getValueJson)
                .map(AiConfigService::parseVersion)
                .orElse(0L);
        }
        catch (Exception ex) {
            log.debug("reading config.version failed (keeping last good snapshot) error={}", ex.getMessage());
            return null;
        }
    }

    private LoadOutcome load() {
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                Long before = readDbVersion();
                if (before == null) {
                    throw new IllegalStateException("config.version is unreadable before snapshot load");
                }
                ConfigSnapshot loaded = loadOnce();
                Long after = readDbVersion();
                if (after != null && before.equals(after) && loaded.version() == after) {
                    return new LoadOutcome(loaded, true);
                }
                log.debug("config snapshot changed during load, retrying attempt={} before={} after={}", attempt, before, after);
            }
            throw new IllegalStateException("config snapshot did not stabilize after 3 loads");
        }
        catch (Exception ex) {
            // 读库失败或版本持续变化时保留 last-good，绝不发布分批读取拼出的混合快照。
            if (this.lastLoadFailed) {
                log.debug("config snapshot reload failed again (keeping last good snapshot) error={}", ex.getMessage());
            }
            else {
                log.warn("config snapshot load failed (keeping last good snapshot; retried on next version check) error={}",
                        ex.getMessage());
            }
            return new LoadOutcome(this.snapshot, false);
        }
    }

    private ConfigSnapshot loadOnce() {
        List<AiProvider> providers = this.providerRepository.findAllByOrderBySortOrderAscIdAsc();
        List<Long> ids = providers.stream().map(AiProvider::getId).filter(java.util.Objects::nonNull).toList();
        List<AiProviderModel> models = ids.isEmpty() ? List.of()
                : this.modelRepository.findByProviderIdInOrderBySortOrderAscIdAsc(ids);
        Map<String, String> settings = new LinkedHashMap<>();
        this.settingRepository.findAll().forEach(setting -> settings.put(setting.getSettingKey(), setting.getValueJson()));
        return new ConfigSnapshot(parseVersion(settings.get(AiSetting.CONFIG_VERSION)), providers, models, settings);
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

    /** 按 id 找库里的 provider 行（写端点与 {@code /test} 用；合成行没有 id，不在此列）。 */
    public Optional<AiProvider> provider(Long providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        return current().providers().stream().filter(provider -> providerId.equals(provider.getId())).findFirst();
    }

    /**
     * 一行的 key 状态标签（设置页每个 provider 卡片显示"已配置/未配置/不可解"）：
     * 停用 ⇒ {@code DISABLED}；否则取运行时解密结论。
     */
    public String keyStatus(AiProvider provider) {
        if (provider == null) {
            return KeyStatus.MISSING.name();
        }
        if (!provider.isEnabled()) {
            return "DISABLED";
        }
        return resolveKey(provider).status().name();
    }

    /**
     * 全局默认参数（{@code ai_setting}）的视图（{@code GET/PUT /api/ai/settings/defaults}）。
     *
     * <p>
     * <b>读时容错</b>（批① M3 的口径）：脏值（{@code "hot"} / {@code NaN} / 负数）不抛，按"未设置"
     * 返回（{@code null}）—— 一个手填的设置项不能关掉整页；写路径（批②）负责让脏值进不来。
     *
     * @param version {@code config.version}（诊断用：改完能确认版本真的动了）
     * @param providerId {@code default.providerId}（与 {@code modelId} 成对；两者同为 {@code null} = 未设置默认目标）
     */
    public record Defaults(String modelId, Double temperature, Double topP, Integer maxTokens,
            Boolean allowUserParams, long version, Long providerId) {
    }

    /** 当前全局默认参数。 */
    public Defaults defaults() {
        ConfigSnapshot snap = current();
        Map<String, String> settings = snap.settings();
        return new Defaults(text(settings.get(AiSetting.DEFAULT_MODEL_ID)),
                parseDouble(settings.get(AiSetting.DEFAULT_TEMPERATURE)),
                parseDouble(settings.get(AiSetting.DEFAULT_TOP_P)), parseInteger(settings.get(AiSetting.DEFAULT_MAX_TOKENS)),
                parseBoolean(settings.get(AiSetting.ALLOW_USER_PARAMS)), snap.version(),
                parseLong(settings.get(AiSetting.DEFAULT_PROVIDER_ID)));
    }

    private static Long parseLong(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException ex) {
            log.debug("ignoring invalid ai_setting integer value={}", raw);
            return null;
        }
    }

    private static String text(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    private static Double parseDouble(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                log.debug("ignoring invalid ai_setting decimal value={}", raw);
                return null;
            }
            return value;
        }
        catch (NumberFormatException ex) {
            log.debug("ignoring invalid ai_setting decimal value={}", raw);
            return null;
        }
    }

    private static Integer parseInteger(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) {
                log.debug("ignoring invalid ai_setting integer value={}", raw);
                return null;
            }
            return value;
        }
        catch (NumberFormatException ex) {
            log.debug("ignoring invalid ai_setting integer value={}", raw);
            return null;
        }
    }

    private static Boolean parseBoolean(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        log.debug("ignoring invalid ai_setting boolean value={}", raw);
        return null;
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
        // L-91：env 种子模型按"思考模型"登记 —— 该列此前没人写（DB 缺省 FALSE），
        // 于是设置页/模型选择器恒显示"不支持推理"，与本机实跑事实相反（deepseek-flash
        // 直打 /chat/completions 确实返回 reasoning_content）。它是能力描述位，不参与运行闸门：
        // 后端是否发思考帧只看上游本片有没有 reasoning_content（有则发，无则不发明）。
        model.setSupportsReasoning(true);
        model.setEnabled(true);
        model.setDefault(true);
        return model;
    }

    // ── 解析：默认 provider / 模型 ────────────────────────────────────────────

    /**
     * 默认运行目标：只认 {@code ai_setting} 里<b>显式绑定</b>的 {@code (providerId, modelId)}。
     *
     * <h2>为什么必须成对解析</h2>
     * 只读 {@code default.modelId} 并把它交给"排序第一的 enabled+key provider"会让设置页里选的
     * B 模型在运行时发给 A（同名模型时静默错配；不同名时上游 404）。现在的口径：模型名只在
     * <b>它自己的 provider</b> 的 allowlist 里才生效，任何 provider 都不会借用别家的模型名；
     * 解析不出 ⇒ 空（{@link #availability()} 据此报 {@code NO_MODEL_CONFIGURED}）。
     *
     * <h2>显式绑定（{@code default.providerId} 存在）</h2>
     * 绑定的 provider 停用 / key 缺失 / key 解不开 / provider 行已不存在 / 绑定的模型停用或删除
     * ⇒ <b>直接空</b>，绝不静默换到其他 provider，也不在同一 provider 内换默认模型。
     * 只有 {@code default.modelId} 为空时，才允许使用该 provider 自己的默认模型
     * （{@code ai_model.is_default} → 首个 enabled）。
     *
     * <h2>未设置默认目标（两个键都为空）</h2>
     * 按 provider 库序取第一个能给出<b>自己</b>默认模型的 provider。这里不按模型名跨 provider
     * 猜归属：写路径要求两者成对提交，因此"有 modelId 没有 providerId"的库内状态不可达。
     */
    public Optional<ResolvedProvider> resolveDefault() {
        return resolveDefault(current());
    }

    private Optional<ResolvedProvider> resolveDefault(ConfigSnapshot snap) {
        List<AiProvider> providers = effectiveProviders(snap);
        Long configuredProviderId = parseLong(snap.settings().get(AiSetting.DEFAULT_PROVIDER_ID));
        if (configuredProviderId != null) {
            String configuredModel = text(snap.settings().get(AiSetting.DEFAULT_MODEL_ID));
            AiProvider bound = providers.stream()
                .filter(provider -> configuredProviderId.equals(provider.getId()))
                .findFirst()
                .orElse(null);
            // 硬绑定：不用 firstOwnTarget，任何不可用都明确为空（availability 给出对应档）
            return bound == null ? Optional.empty() : targetFor(snap, bound, configuredModel);
        }
        return firstOwnTarget(snap, providers);
    }

    /** 库序里第一个"key 可解析且能给出自己默认模型"的目标。 */
    private Optional<ResolvedProvider> firstOwnTarget(ConfigSnapshot snap, List<AiProvider> providers) {
        for (AiProvider provider : providers) {
            Optional<ResolvedProvider> target = targetFor(snap, provider, null);
            if (target.isPresent()) {
                return target;
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedProvider> targetFor(ConfigSnapshot snap, AiProvider provider, String preferredModel) {
        if (!provider.isEnabled()) {
            return Optional.empty();
        }
        KeyResolution key = resolveKey(provider);
        if (key.status() != KeyStatus.OK) {
            return Optional.empty();
        }
        String modelId = ownModelId(snap, provider, preferredModel);
        if (modelId == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProvider(provider.getId(), provider.getCode(), provider.getBaseUrl(),
                provider.getCompletionsPath(), modelId, key.apiKey(), key.fingerprint(), provider.getId() == null));
    }

    private boolean isUsable(AiProvider provider) {
        return provider != null && provider.isEnabled() && resolveKey(provider).status() == KeyStatus.OK;
    }

    /** 指定 provider 的运行目标（批③ 的"按请求选 provider"会复用；批① 供 /health 逐条状态用）。 */
    public Optional<ResolvedProvider> resolveProvider(AiProvider provider) {
        if (provider == null) {
            return Optional.empty();
        }
        ConfigSnapshot snap = current();
        return targetFor(snap, provider, text(snap.settings().get(AiSetting.DEFAULT_MODEL_ID)));
    }

    /**
     * 一次 {@code /chat} 的运行目标（批③）：运行目标 + <b>能力位</b>。
     *
     * <p>
     * 为什么单列一个 record 而不是给 {@link ResolvedProvider} 加字段：后者是批① 的对外形状
     * （{@code /health}、注册表、测试都在用），加字段会牵动 4 处测试构造与两个解析方法；
     * 能力位是"本轮要不要下发工具"的判据，只属于请求级解析。
     *
     * @param modelId 本轮模型名（= 请求显式值或解析链结果）
     * @param supportsToolCalling {@code false} ⇒ 本轮不下发 toolCallbacks（纯问答降级）
     */
    public record ChatTarget(ResolvedProvider provider, String modelId, boolean supportsToolCalling) {
    }

    /**
     * 面板可选的模型（{@code GET /api/ai/models} 的一行；web 层原样转成
     * {@code AvailableModelView} —— config 层不依赖 web 包）。
     */
    public record AvailableModel(Long providerId, String providerCode, String providerName, String modelId,
            String displayName, boolean supportsToolCalling, boolean supportsVision, boolean supportsReasoning,
            Integer maxContext, Integer maxOutput, boolean isDefault) {
    }

    /**
     * 无显式模型的请求走当前默认目标。只有模型名而没有 providerId 时不具备唯一归属，
     * 必须返回空；调用方应改用 {@link #resolveChatTarget(Long, String)} 传递显式二元组。
     */
    public Optional<ChatTarget> resolveChatTarget(String requestedModelId) {
        if (!StringUtils.hasText(requestedModelId)) {
            return resolveDefault().map(resolved -> new ChatTarget(resolved, resolved.modelId(),
                    supportsToolCalling(resolved.providerId(), resolved.modelId())));
        }
        return Optional.empty();
    }

    /**
     * <b>I3 精确绑定</b>：{@code (providerId, modelId)} 必须是<b>同一家</b> provider 上的真实可用目标。
     *
     * <h2>为什么只按模型名解析不够</h2>
     * 只有 {@code modelId} 时，解析只能"按库序取第一家能给出该模型的 provider"。同名模型在 A/B
     * 两家都存在时，续跑就会被判到<b>别家</b>（历史、{@code done.model}、推理元数据的来源全部说谎）；
     * 反过来，快照那一家缺了该模型时，按名字也会被别家的同名模型"救活"。
     *
     * <h2>判据（三条同时成立，且只在这一家上判）</h2>
     * <ol>
     * <li>provider 存在且 {@code enabled}；</li>
     * <li>key 可解析（{@link KeyStatus#OK}）；</li>
     * <li>模型在该 provider 的 allowlist 里且 {@code enabled}（env 合成行的种子模型等价于一行 enabled）。
     * 显式模型不满足 ⇒ <b>空</b>，不回落该 provider 的默认模型，也不去别家找同名模型。</li>
     * </ol>
     *
     * <p>带 provider 身份的解析<b>不做</b>模型名回退；{@code providerId=null} 只匹配当前生效的
     * env 合成行（数据库无 provider 且 env key 存在），不匹配数据库里的任何 provider。</p>
     *
     * @param providerId provider 主键；{@code null} = 仅限当前 env 合成行
     * @param requestedModelId 本轮模型名；<b>空白</b> = 该 provider 自己的默认模型
     *        （不借用全局 {@code default.modelId}：那个名字属于另一家时会把"绑定"变成"按名字猜"）
     */
    public Optional<ChatTarget> resolveChatTarget(Long providerId, String requestedModelId) {
        ConfigSnapshot snap = current();
        AiProvider bound = effectiveProviders(snap)
            .stream()
            .filter(provider -> providerId == null ? provider.getId() == null : providerId.equals(provider.getId()))
            .findFirst()
            .orElse(null);
        if (bound == null) {
            return Optional.empty();
        }
        String preferred = StringUtils.hasText(requestedModelId) ? requestedModelId.trim() : null;
        return targetFor(snap, bound, preferred)
            .map(resolved -> new ChatTarget(resolved, resolved.modelId(),
                    supportsToolCalling(resolved.providerId(), resolved.modelId())));
    }

    /**
     * 续跑精确绑定的<b>行级可用性</b>（B2 三态判据）：解析为空时用它区分"确定不可用"与"此刻不可知"。
     *
     * <p>
     * 为什么不直接用 {@link #resolveChatTarget(Long, String)} 的空与否：那个判据把三类事实压在一起 ——
     * 行没了、没声明 key、key 解不开。前两者是**确定**的配置状态（删了就是删了），第三类是
     * **环境**问题（KEK 缺失/轮换/密文损坏），KEK 修好这一轮就能续。判死只允许对前两者做。
     */
    public enum BindingState {

        /** 行、模型、key 三者都就绪（正常情况下 {@code resolveChatTarget} 不会为空）。 */
        RESOLVED,

        /**
         * <b>确定不可用</b>：provider/模型不存在、被停用、模型不在 allowlist，或该 provider
         * <b>从未声明 key</b>（{@code key_ref=NONE} / env key 为空）—— 都是配置层面可判定的状态。
         */
        ABSENT,

        /**
         * <b>此刻不可知</b>：行与模型都在、key 也声明了，但本进程此刻解不开
         * （KEK 缺失/轮换/密文损坏）。修好 KEK 就能续 ⇒ 不得判死。
         */
        KEY_UNREADABLE

    }

    /**
     * 续跑精确绑定的行级判据（见 {@link BindingState}）。
     *
     * <p>
     * 只看<b>快照</b>，不查库 —— 快照此刻可不可信由调用方用 {@link #reloadWithOutcome()} 的结局判定
     * （读库失败时"没有这一行"不代表库里没有）。
     *
     * @param providerId provider 主键；{@code null} = 仅限当前 env 合成行，其他场景确定不可用
     * @param modelId 快照里的模型名；空白 = 该 provider 自己的默认模型
     */
    public BindingState bindingState(Long providerId, String modelId) {
        ConfigSnapshot snap = current();
        AiProvider bound = effectiveProviders(snap)
            .stream()
            .filter(provider -> providerId == null ? provider.getId() == null : providerId.equals(provider.getId()))
            .findFirst()
            .orElse(null);
        if (bound == null || !bound.isEnabled()) {
            return BindingState.ABSENT;
        }
        String preferred = StringUtils.hasText(modelId) ? modelId.trim() : null;
        if (ownModelId(snap, bound, preferred) == null) {
            return BindingState.ABSENT;
        }
        KeyResolution key = resolveKey(bound);
        if (key.status() == KeyStatus.OK) {
            return BindingState.RESOLVED;
        }
        // 只有"声明了密文却解不开"才是不可知；"从来没声明 key"是确定的配置状态（与既有口径一致：
        // ResumeService.Outcome#PROVIDER_GONE 的注释把"密钥不可用"列为确定不可用）。
        return key.status() == KeyStatus.UNREADABLE ? BindingState.KEY_UNREADABLE : BindingState.ABSENT;
    }

    /**
     * 该模型在指定 provider 上是否可选（可选则返回那一行；env 合成行返回种子模型）。
     *
     * <p>
     * 合成行（{@code id=null}）走 {@link #envSeedModel()}：它不是库里的行，但它是"当前真的在跑"
     * 的模型 —— CI/E2E 全靠它，把它判成不可选等于让主路径 400。
     */
    private AiProviderModel selectableModel(ConfigSnapshot snap, AiProvider provider, String modelId) {
        if (provider.getId() == null) {
            AiProviderModel seed = envSeedModel();
            return seed.getModelId().equals(modelId) ? seed : null;
        }
        return snap.models()
            .stream()
            .filter(model -> provider.getId().equals(model.getProviderId()))
            .filter(AiProviderModel::isEnabled)
            .filter(model -> modelId.equals(model.getModelId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 模型的能力位（{@code supports_tool_calling}）。
     *
     * <p>
     * 找不到模型行（{@code ai_setting} / env 回落的模型名，库里没登记）时判 <b>true</b>：批③ 之前
     * 的行为就是"所有模型都下发工具"，无行可查时保持等价；只有显式登记为 {@code false} 的行才降级。
     */
    public boolean supportsToolCalling(Long providerId, String modelId) {
        if (modelId == null) {
            return true;
        }
        ConfigSnapshot snap = current();
        if (providerId == null) {
            return true;
        }
        return snap.models()
            .stream()
            .filter(model -> providerId.equals(model.getProviderId()))
            .filter(model -> modelId.equals(model.getModelId()))
            .map(AiProviderModel::isSupportsToolCalling)
            .findFirst()
            .orElse(true);
    }

    /**
     * 普通用户是否允许调整参数（{@code ai_setting['allow.userParams']}）。
     *
     * <p>
     * <b>缺省 = 允许</b>：该项未设置时谈"锁定"没有依据（设置页的 {@code allowUserParams !== false}
     * 是同一口径），只有显式 {@code false} 才锁。
     */
    public boolean allowUserParams() {
        return !Boolean.FALSE.equals(defaults().allowUserParams());
    }

    /**
     * 面板可选的模型（{@code GET /api/ai/models}；设计稿 §3.5）。
     *
     * <h2>为什么是独立端点而不是复用 {@code /providers}</h2>
     * 面板只要"能选什么"，{@code /providers} 还带着 baseUrl / keyRef / 模型路径这些配置面字段，
     * 且要前端自己再过滤一遍（过滤规则一旦与后端漂移，就会出现"列出来了点不通"）。
     * 本方法把三条可用性判据（provider enabled / key 可解析 / 模型 enabled）落在<b>后端唯一口径</b>，
     * 面板拿到即真值；payload 也更小（一次挂载就取，不考虑缓存）。
     *
     * <p>顺序 = provider 库序 → 模型库序：面板下拉的次序因此稳定（不随 HashMap 抖动）。
     */
    public List<AvailableModel> availableModels() {
        ConfigSnapshot snap = current();
        List<AvailableModel> out = new ArrayList<>();
        for (AiProvider provider : effectiveProviders(snap)) {
            if (!provider.isEnabled() || resolveKey(provider).status() != KeyStatus.OK) {
                continue;
            }
            List<AiProviderModel> rows;
            if (provider.getId() == null) {
                rows = List.of(envSeedModel());
            }
            else {
                rows = snap.models()
                    .stream()
                    .filter(model -> provider.getId().equals(model.getProviderId()))
                    .filter(AiProviderModel::isEnabled)
                    .toList();
            }
            for (AiProviderModel row : rows) {
                out.add(new AvailableModel(provider.getId(), provider.getCode(), provider.getDisplayName(),
                        row.getModelId(), row.getDisplayName() == null ? row.getModelId() : row.getDisplayName(),
                        row.isSupportsToolCalling(), Boolean.TRUE.equals(row.getSupportsVision()),
                        Boolean.TRUE.equals(row.getSupportsReasoning()),
                        row.getMaxContext(), row.getMaxOutput(), row.isDefault()));
            }
        }
        return out;
    }

    /**
     * 该 provider 能跑的模型。
     *
     * <p>唯一口径：<b>只从它自己的 allowlist 里取</b>。{@code default.modelId} 只有在这个 provider
     * 名下登记过才生效，且是<b>精确匹配</b>：模型被停用/删除 ⇒ 返回 {@code null}（不换同 provider
     * 的其他模型）——只有调用方明确没给模型名（{@code preferredModelId} 为空）时才走
     * {@code ai_model.is_default} → 首个 enabled。env 模型只用于 env 合成行（{@code id=null}），
     * 库内 provider 在没有任何可用模型时返回 {@code null}（不借 env / 别家的模型名）。
     */
    public String resolveModelId(ConfigSnapshot snap, AiProvider provider) {
        return ownModelId(snap, provider, text(snap.settings().get(AiSetting.DEFAULT_MODEL_ID)));
    }

    private String ownModelId(ConfigSnapshot snap, AiProvider provider, String preferredModelId) {
        if (StringUtils.hasText(preferredModelId)) {
            String wanted = preferredModelId.trim();
            return selectableModel(snap, provider, wanted) != null ? wanted : null;
        }
        List<AiProviderModel> models;
        String envFallback;
        if (provider.getId() == null) {
            models = List.of(envSeedModel());
            envFallback = resolveEnvModelId();
        }
        else {
            models = snap.models()
                .stream()
                .filter(model -> provider.getId().equals(model.getProviderId()))
                .toList();
            // 缺模型就是缺模型：env AI_MODEL 不是这家 provider 的 allowlist，不许借用
            envFallback = null;
        }
        return AiModelResolver.resolve(null, models, null, envFallback);
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
                log.debug("key fingerprint computation failed provider={} error={}", provider.getCode(), ex.getMessage());
            }
        }
        // 无 KEK 时，env key 只能重启才变 ⇒ 用固定标记作缓存键成分（不含任何 key 片段）
        return AiProvider.KEY_REF_ENV.equals(provider.getKeyRef()) ? "env" : null;
    }

    private void warnOnceUnreadable(AiProvider provider, AiKeyCipher.KeyCipherException ex) {
        String key = String.valueOf(provider.getCode());
        if (this.unreadableWarned.putIfAbsent(key, Boolean.TRUE) == null) {
            log.warn("provider={} key cannot be decrypted ({}): reporting {}, not falling back to env key; fix = restore AI_SECRET_KEK"
                    + " or re-enter the key", key, ex.getCode(), CODE_KEY_UNREADABLE);
        }
    }

    // ── 可用性 ──────────────────────────────────────────────────────────────

    public record Availability(boolean available, String code, String reason) {
    }

    /**
     * 动态可用性判据（设计稿 §3.4）。
     *
     * <h2>四档出口</h2>
     * <ul>
     * <li>{@code AI_UNAVAILABLE}：无 provider；或默认目标<b>硬绑定</b>的 provider 已停用/已不存在；</li>
     * <li>{@code AI_KEY_MISSING} / {@code AI_KEY_UNREADABLE}：绑定 provider 的 key 缺失/解不开；
     * 未绑定时沿用"任一 enabled provider 的 key 可用即算可用"；</li>
     * <li>{@code NO_MODEL_CONFIGURED}：provider/key 都在，但默认目标解析不出<b>它自己的</b>模型；</li>
     * <li>{@code AI_AVAILABLE}：默认目标解析成功。</li>
     * </ul>
     *
     * <p>绑定存在时<b>不</b>看其他 provider 是否可用：硬绑定的意义就是"配置了谁就只跑谁"，
     * 静默换到别家会让设置页与运行时再次分叉。
     */
    public Availability availability() {
        ConfigSnapshot snap = current();
        List<AiProvider> providers = effectiveProviders(snap);
        Long boundProviderId = parseLong(snap.settings().get(AiSetting.DEFAULT_PROVIDER_ID));
        if (boundProviderId != null) {
            return boundAvailability(snap, providers, boundProviderId);
        }
        List<AiProvider> enabled = providers.stream().filter(AiProvider::isEnabled).toList();
        if (enabled.isEmpty()) {
            // 空表 + 无 env：AI_UNAVAILABLE，并指向设置页
            return new Availability(false, CODE_UNAVAILABLE, UNAVAILABLE_REASON);
        }
        boolean keyOk = false;
        int unreadable = 0;
        int missing = 0;
        for (AiProvider provider : enabled) {
            switch (resolveKey(provider).status()) {
                case OK -> keyOk = true;
                case UNREADABLE -> unreadable++;
                case MISSING -> missing++;
            }
        }
        if (keyOk) {
            // "有 provider 有 key"不等于"能跑"：默认目标还必须解析得出<b>它自己的</b>模型。
            // 解析不出 ⇒ 明确 NO_MODEL_CONFIGURED（而不是借排序里别家的模型名继续跑）。
            return resolveDefault(snap).isPresent() ? new Availability(true, "AI_AVAILABLE", null)
                    : new Availability(false, CODE_NO_MODEL_CONFIGURED, NO_MODEL_REASON);
        }
        if (unreadable > 0) {
            return new Availability(false, CODE_KEY_UNREADABLE, KEY_UNREADABLE_REASON);
        }
        return new Availability(false, CODE_KEY_MISSING, KEY_MISSING_REASON);
    }

    /** 硬绑定的可用性：只看绑定行，任何异常都明确失败，不回退其他 provider。 */
    private Availability boundAvailability(ConfigSnapshot snap, List<AiProvider> providers, Long boundProviderId) {
        AiProvider bound = providers.stream()
            .filter(provider -> boundProviderId.equals(provider.getId()))
            .findFirst()
            .orElse(null);
        if (bound == null) {
            return new Availability(false, CODE_UNAVAILABLE, BOUND_PROVIDER_MISSING_REASON);
        }
        if (!bound.isEnabled()) {
            return new Availability(false, CODE_UNAVAILABLE, BOUND_PROVIDER_DISABLED_REASON);
        }
        KeyResolution key = resolveKey(bound);
        if (key.status() == KeyStatus.UNREADABLE) {
            return new Availability(false, CODE_KEY_UNREADABLE, KEY_UNREADABLE_REASON);
        }
        if (key.status() == KeyStatus.MISSING) {
            return new Availability(false, CODE_KEY_MISSING, KEY_MISSING_REASON);
        }
        return resolveDefault(snap).isPresent() ? new Availability(true, "AI_AVAILABLE", null)
                : new Availability(false, CODE_NO_MODEL_CONFIGURED, NO_MODEL_REASON);
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

    /**
     * 诊断视图（不含 key）：provider 行 + key 状态。
     *
     * <h2>合成行（批① 复审登记 M5 的消费面，批② 收口）</h2>
     * 表为空且 env key 非空时，这里会多出一行"<b>还没落库的 env 兜底行</b>"：{@code id=null}、
     * {@code source=ENV_SEED}、{@code synthetic=true}。设置页据此提示"当前配置来自环境变量兜底，
     * 尚未写入配置库"——否则用户会看到"库是空的"却仍然能用 AI，无从判断自己在改哪一层。
     *
     * <p>
     * {@code id} 可能因全局 Jackson {@code default-property-inclusion=non_null} 被省略，
     * 故<b>不要</b>用"{@code id} 键是否存在"判断合成行：认 {@code synthetic}（布尔，恒出现）。
     */
    public List<Map<String, Object>> describeProviders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiProvider provider : effectiveProviders()) {
            KeyResolution key = resolveKey(provider);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", provider.getId());
            row.put("code", provider.getCode());
            row.put("name", provider.getDisplayName());
            row.put("baseUrl", provider.getBaseUrl());
            row.put("enabled", provider.isEnabled());
            row.put("keyConfigured", provider.isKeyDeclared());
            row.put("keyRef", provider.getKeyRef());
            row.put("source", provider.getSource());
            row.put("synthetic", provider.getId() == null);
            row.put("status", provider.isEnabled() ? key.status().name() : "DISABLED");
            out.add(row);
        }
        return out;
    }
}
