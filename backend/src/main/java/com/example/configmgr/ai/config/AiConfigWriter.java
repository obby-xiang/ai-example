package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiConfigAudit;
import com.example.configmgr.ai.config.db.AiConfigAuditRepository;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.config.db.AiSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 配置<b>写路径</b>（模型配置专项 §3.4 / §3.5 的批②）：新建/更新/删除 provider、模型 allowlist、
 * 全局默认参数。
 *
 * <h2>一次写 = 四件事，顺序固定</h2>
 * <ol>
 * <li><b>先校验、先加密</b>（都在事务外）：baseUrl 过 {@link AiEndpointGuard}、数值过范围、key 用
 * {@link AiKeyCipher} 加密。KEK 缺失时<b>表里不留半行</b>（B5）；</li>
 * <li><b>一个事务</b>：数据行 + {@code ai_setting['config.version']}+1 + 审计行（同一提交点，
 * 不存在"改了数据但版本没动"或"有审计没改动"的中间态）；</li>
 * <li><b>提交后</b> {@code INCR ai:cfg:ver}（跨实例失效信号；Redis 不可用只 WARN，DB 版本号仍是真源）；</li>
 * <li><b>提交后</b> {@link AiConfigService#reload()}：本实例立刻用新配置（写的那个人不该等 1s 节流窗）。</li>
 * </ol>
 *
 * <h2>key 的三种语义（PUT）</h2>
 * <ul>
 * <li>{@code apiKey} 非空 ⇒ 加密落库，{@code keyRef=DB}；</li>
 * <li>{@code apiKey} 省略/空白 ⇒ <b>不修改</b>（设置页的"留空 = 不改"）；</li>
 * <li>{@code clearKey=true} ⇒ 清空（{@code keyRef=NONE}），优先级高于 {@code apiKey}。</li>
 * </ul>
 *
 * <h2>审计的泄密纪律</h2>
 * {@code diff_json} 对 key 只写 {@code {"key":"set|updated|cleared|none","last4":"ab12"}} ——
 * 永不写明文、永不写密文/指纹（设计稿 §3.2 泄漏面第 3 条）。
 *
 * <h2>新增的错误码（设计稿 §3.5 的封闭表之外，五条）</h2>
 * {@code PROVIDER_EXISTS} / {@code MODEL_EXISTS} / {@code CODE_IMMUTABLE} / {@code INVALID_PARAM} /
 * {@code INVALID_MODEL}：分别对应"唯一键冲突"（两个）、"code 是密文 AAD 的一段故不可改"、
 * "数值/长度/格式校验"、"模型名不合法"。设计稿的封闭表是为<b>已命名的错误面</b>定的，CRUD 的
 * 唯一键冲突与入参校验必须有可判定的码（否则只能落 500 或自由文本），故显式新增并在此登记。
 */
@Slf4j
@Service
public class AiConfigWriter {

    // ── 稳定错误码（设计稿 §3.5 的封闭表 + 本批新增的五条，见类注末段） ──────────────

    public static final String CODE_PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND";

    public static final String CODE_PROVIDER_IN_USE = "PROVIDER_IN_USE";

    /** 本批新增：{@code code} 唯一（设计稿表里没有，但重名必须有可判定的码，否则只能落 500）。 */
    public static final String CODE_PROVIDER_EXISTS = "PROVIDER_EXISTS";

    public static final String CODE_MODEL_NOT_FOUND = "MODEL_NOT_FOUND";

    /** 本批新增：{@code (provider_id, model_id)} 唯一。 */
    public static final String CODE_MODEL_EXISTS = "MODEL_EXISTS";

    /** 本批新增：{@code code} 是密文 AAD 的一段，不可原地改（改了旧密文就解不开）。 */
    public static final String CODE_CODE_IMMUTABLE = "CODE_IMMUTABLE";

    /** 本批新增：数值/长度/格式类校验（temperature 越界、key 过短等统一落这里）。 */
    public static final String CODE_INVALID_PARAM = "INVALID_PARAM";

    /** 本批新增：模型名不合法（空白/超长/含空白符）。 */
    public static final String CODE_INVALID_MODEL = "INVALID_MODEL";

    // ── 审计动作 ────────────────────────────────────────────────────────────

    public static final String ACTION_PROVIDER_CREATE = "PROVIDER_CREATE";

    public static final String ACTION_PROVIDER_UPDATE = "PROVIDER_UPDATE";

    /** 软删（停用）与硬删共用：{@code diff.hard} 区分。 */
    public static final String ACTION_PROVIDER_DELETE = "PROVIDER_DELETE";

    public static final String ACTION_MODEL_ADD = "MODEL_ADD";

    public static final String ACTION_MODEL_UPDATE = "MODEL_UPDATE";

    public static final String ACTION_MODEL_DELETE = "MODEL_DELETE";

    public static final String ACTION_DEFAULTS_UPDATE = "DEFAULTS_UPDATE";

    // ── 校验常量 ────────────────────────────────────────────────────────────

    /** provider 机读名：小写字母/数字开头，允许 {@code _-}（与 {@code deepseek}、{@code custom-1} 同形）。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private static final int MAX_KEY_LENGTH = 512;

    /**
     * key 的最短长度（N1：批① 的 {@code last4} 对长度 ≤4 的串会返回<b>整串</b>）。
     *
     * <p>
     * 写路径引入用户输入后这是真实泄露面：4 位以内的 key 会在设置页原样展示。取 8 是下限而非
     * 强度要求 —— 真实 key 都是 20+ 位；8 只是"末 4 位一定只是片段"的最小保证。
     */
    private static final int MIN_KEY_LENGTH = 8;

    private static final double MIN_TEMPERATURE = 0d;

    private static final double MAX_TEMPERATURE = 2d;

    private static final double MIN_TOP_P = 0d;

    private static final double MAX_TOP_P = 1d;

    private static final int MIN_TOKENS = 1;

    private static final int MAX_TOKENS = 200_000;

    private static final int MAX_CONTEXT_CEILING = 10_000_000;

    private final AiProviderRepository providerRepository;

    private final AiProviderModelRepository modelRepository;

    private final AiSettingRepository settingRepository;

    private final AiConfigAuditRepository auditRepository;

    private final AiKeyCipher cipher;

    private final AiEndpointGuard guard;

    /** 可为 null（单测）：无 Redis ⇒ 跳过跨实例失效信号（DB 版本号仍推进）。 */
    private final StringRedisTemplate redis;

    private final TransactionTemplate transactionTemplate;

    private final AiConfigService configService;

    private final ObjectMapper objectMapper;

    public AiConfigWriter(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiSettingRepository settingRepository, AiConfigAuditRepository auditRepository, AiKeyCipher cipher,
            AiEndpointGuard guard, StringRedisTemplate redis, PlatformTransactionManager transactionManager,
            AiConfigService configService, ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingRepository = settingRepository;
        this.auditRepository = auditRepository;
        this.cipher = cipher;
        this.guard = guard;
        this.redis = redis;
        // N-6（复审）：事务边界是写路径的正确性前提，装配期就 fail-fast —— 不要在运行时才 NPE
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "AiConfigWriter 需要 PlatformTransactionManager："
                        + "写路径的“数据行 + 版本号 + 审计行”必须同一提交点"));
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    // ── 请求命令（controller 的 @RequestBody 直接反序列化成这三条 record） ──────────

    /**
     * provider 新建/更新命令。
     *
     * <p>
     * 更新时除 {@code code} 外全部"传了才改"（null = 不动）。{@code code} 传了就必须与原值相同
     * （它是密文 AAD 的一段，见 {@link #CODE_CODE_IMMUTABLE}）。
     *
     * @param apiKey 明文 key；省略/空白 = 不修改（新建时 = 不配置 key）
     * @param clearKey true = 清空 key（优先于 apiKey）
     */
    public record ProviderCommand(String code, String displayName, String protocol, String baseUrl,
            String completionsPath, String modelsPath, String apiKey, Boolean clearKey, Boolean enabled,
            Integer sortOrder) {
    }

    /**
     * 模型行命令（新建必须给 {@code modelId}；更新时 {@code modelId} 不可改，传了必须等于路径值）。
     *
     * <p>
     * 数值：{@code maxContext} 1..1e7（上限只是防手滑写 <i>12亿</i> 这种值撑爆分母）；
     * {@code maxOutput} 1..maxContext。
     */
    public record ModelCommand(String modelId, String displayName, Integer maxContext, Integer maxOutput,
            Boolean supportsToolCalling, Boolean supportsVision, Boolean supportsReasoning, Boolean enabled,
            Boolean isDefault, Integer sortOrder) {
    }

    /**
     * 全局默认参数命令：<b>整体替换语义</b>（设置页第三段是一个表单，提交即整段）。
     *
     * <p>
     * null = 清掉该项（回到"未设置"，读侧再按模型/环境缺省回落）。范围：temperature [0,2]、
     * top_p [0,1]、max_tokens [1,200000] 且不超过所选模型的 {@code maxOutput}。
     */
    public record DefaultsCommand(String modelId, Double temperature, Double topP, Integer maxTokens,
            Boolean allowUserParams) {
    }

    // ── provider ────────────────────────────────────────────────────────────

    public AiProvider createProvider(ProviderCommand command) {
        if (command == null) {
            throw invalidParam("请求体不能为空");
        }
        String code = requireCode(command.code());
        if (this.providerRepository.findByCode(code).isPresent()) {
            throw new AiConfigException(CODE_PROVIDER_EXISTS, "provider code 已存在：" + code);
        }
        String baseUrl = checkBaseUrl(command.baseUrl());
        String displayName = requireText(command.displayName(), "displayName", 128);
        String protocol = requireProtocol(command.protocol());
        String completionsPath = requirePath(command.completionsPath(), "/chat/completions", "completionsPath");
        String modelsPath = requirePath(command.modelsPath(), "/models", "modelsPath");

        String apiKey = trimToNull(command.apiKey());
        KeyMaterial key = apiKey == null ? KeyMaterial.none() : sealKey(apiKey, code);

        AiProvider created = executeInTransaction(() -> {
            AiProvider provider = new AiProvider();
            provider.setCode(code);
            provider.setDisplayName(displayName);
            provider.setProtocol(protocol);
            provider.setBaseUrl(baseUrl);
            provider.setCompletionsPath(completionsPath);
            provider.setModelsPath(modelsPath);
            provider.setKeyCipher(key.cipher());
            provider.setKeyLast4(key.last4());
            provider.setKeyFingerprint(key.fingerprint());
            provider.setKeyRef(key.keyRef());
            provider.setSource(AiProvider.SOURCE_DB);
            provider.setEnabled(command.enabled() == null || command.enabled());
            provider.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
            this.providerRepository.saveAndFlush(provider);
            bumpVersion();
            audit(ACTION_PROVIDER_CREATE, "provider", String.valueOf(provider.getId()),
                    diffOf("code", code, "baseUrl", baseUrl, "key", key.action(), "last4", key.last4()));
            return provider;
        });
        afterCommit();
        return created;
    }

    public AiProvider updateProvider(Long providerId, ProviderCommand command) {
        if (command == null) {
            throw invalidParam("请求体不能为空");
        }
        AiProvider existing = requireProvider(providerId);
        if (StringUtils.hasText(command.code()) && !command.code().trim().equals(existing.getCode())) {
            throw new AiConfigException(CODE_CODE_IMMUTABLE,
                    "provider code 不可修改（它是 key 密文 AAD 的一段，改了旧密文将无法解密）");
        }
        String baseUrl = StringUtils.hasText(command.baseUrl())
                ? checkBaseUrl(command.baseUrl()) : existing.getBaseUrl();
        String displayName = StringUtils.hasText(command.displayName())
                ? requireText(command.displayName(), "displayName", 128) : existing.getDisplayName();
        String protocol = StringUtils.hasText(command.protocol()) ? requireProtocol(command.protocol())
                : existing.getProtocol();
        String completionsPath = StringUtils.hasText(command.completionsPath())
                ? requirePath(command.completionsPath(), "/chat/completions", "completionsPath")
                : existing.getCompletionsPath();
        String modelsPath = StringUtils.hasText(command.modelsPath())
                ? requirePath(command.modelsPath(), "/models", "modelsPath") : existing.getModelsPath();

        KeyMaterial key;
        if (Boolean.TRUE.equals(command.clearKey())) {
            key = KeyMaterial.cleared();
        }
        else if (trimToNull(command.apiKey()) != null) {
            key = sealKey(trimToNull(command.apiKey()), existing.getCode());
            key = new KeyMaterial(key.cipher(), key.last4(), key.fingerprint(), key.keyRef(), "updated");
        }
        else {
            key = null;
        }

        KeyMaterial finalKey = key;
        AiProvider updated = executeInTransaction(() -> {
            existing.setDisplayName(displayName);
            existing.setProtocol(protocol);
            existing.setBaseUrl(baseUrl);
            existing.setCompletionsPath(completionsPath);
            existing.setModelsPath(modelsPath);
            existing.setEnabled(command.enabled() == null ? existing.isEnabled() : command.enabled());
            existing.setSortOrder(command.sortOrder() == null ? existing.getSortOrder() : command.sortOrder());
            // I-1（复审必修）：用户一改即脱离种子 —— 否则 seed-force 重启会把用户改过的 base-url/key_last4
            // 又按 env 覆盖回去（AiConfigSeeder#writeSeed 只重灌 source=ENV_SEED 的行）。
            // 连锁面：keyLast4 的 seed-force 赋值同样在 ENV_SEED 分支里 ⇒ source 翻转后自然跳过。
            if (!AiProvider.SOURCE_DB.equals(existing.getSource())) {
                existing.setSource(AiProvider.SOURCE_DB);
            }
            if (finalKey != null) {
                existing.setKeyCipher(finalKey.cipher());
                existing.setKeyLast4(finalKey.last4());
                existing.setKeyFingerprint(finalKey.fingerprint());
                existing.setKeyRef(finalKey.keyRef());
            }
            this.providerRepository.saveAndFlush(existing);
            bumpVersion();
            Map<String, Object> diff = diffOf("code", existing.getCode(), "baseUrl", baseUrl);
            if (finalKey != null) {
                diff.put("key", finalKey.action());
                if (finalKey.last4() != null) {
                    diff.put("last4", finalKey.last4());
                }
            }
            audit(ACTION_PROVIDER_UPDATE, "provider", String.valueOf(existing.getId()), diff);
            return existing;
        });
        afterCommit();
        return updated;
    }

    /**
     * 删除 provider：默认<b>软删</b>（{@code enabled=false}，模型行留着，随时可恢复）。
     *
     * @param hard true = 真删；仅当无模型引用时允许（{@code PROVIDER_IN_USE} 409）
     */
    public AiProvider deleteProvider(Long providerId, boolean hard) {
        AiProvider existing = requireProvider(providerId);
        AiProvider result = executeInTransaction(() -> {
            long modelCount = this.modelRepository.findByProviderId(providerId).size();
            if (hard) {
                if (modelCount > 0) {
                    throw new AiConfigException(CODE_PROVIDER_IN_USE,
                            "该 provider 仍有 " + modelCount + " 个模型引用，不能硬删（先删模型或改软删）");
                }
                this.providerRepository.deleteById(providerId);
            }
            else {
                existing.setEnabled(false);
                this.providerRepository.saveAndFlush(existing);
            }
            bumpVersion();
            audit(ACTION_PROVIDER_DELETE, "provider", String.valueOf(providerId),
                    diffOf("code", existing.getCode(), "hard", hard));
            return existing;
        });
        afterCommit();
        return result;
    }

    // ── 模型 allowlist ──────────────────────────────────────────────────────

    public AiProviderModel addModel(Long providerId, ModelCommand command) {
        AiProvider provider = requireProvider(providerId);
        if (command == null) {
            throw invalidParam("请求体不能为空");
        }
        String modelId = requireModelId(command.modelId());
        if (this.modelRepository.findByProviderIdAndModelId(providerId, modelId).isPresent()) {
            throw new AiConfigException(CODE_MODEL_EXISTS, "该模型已在 allowlist 中：" + modelId);
        }
        int maxContext = validateMaxContext(command.maxContext());
        int maxOutput = validateMaxOutput(command.maxOutput(), command.maxContext());

        AiProviderModel created = executeInTransaction(() -> {
            AiProviderModel model = new AiProviderModel();
            model.setProviderId(provider.getId());
            model.setModelId(modelId);
            model.setDisplayName(StringUtils.hasText(command.displayName()) ? command.displayName().trim() : modelId);
            model.setMaxContext(command.maxContext() == null ? null : maxContext);
            model.setMaxOutput(command.maxOutput() == null ? null : maxOutput);
            model.setSupportsToolCalling(command.supportsToolCalling() == null || command.supportsToolCalling());
            model.setSupportsVision(Boolean.TRUE.equals(command.supportsVision()));
            model.setSupportsReasoning(Boolean.TRUE.equals(command.supportsReasoning()));
            model.setEnabled(command.enabled() == null || command.enabled());
            model.setDefault(Boolean.TRUE.equals(command.isDefault()));
            model.setSortOrder(command.sortOrder() == null ? 0 : command.sortOrder());
            if (model.isDefault()) {
                clearDefaultFlag(providerId, null);
            }
            this.modelRepository.saveAndFlush(model);
            bumpVersion();
            audit(ACTION_MODEL_ADD, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId));
            return model;
        });
        afterCommit();
        return created;
    }

    public AiProviderModel updateModel(Long providerId, String modelId, ModelCommand command) {
        requireProvider(providerId);
        if (command == null) {
            throw invalidParam("请求体不能为空");
        }
        AiProviderModel existing = this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
            .orElseThrow(() -> new AiConfigException(CODE_MODEL_NOT_FOUND,
                    "模型不存在：" + providerId + "/" + modelId));
        if (StringUtils.hasText(command.modelId()) && !command.modelId().trim().equals(modelId)) {
            throw new AiConfigException(CODE_INVALID_MODEL,
                    "modelId 不可原地修改（唯一键是 provider_id + model_id）：请删除后另建一行");
        }
        Integer nextMaxContext = command.maxContext() == null ? existing.getMaxContext() : command.maxContext();
        Integer nextMaxOutput = command.maxOutput() == null ? existing.getMaxOutput() : command.maxOutput();
        validateMaxContext(nextMaxContext);
        validateMaxOutput(nextMaxOutput, nextMaxContext);

        AiProviderModel updated = executeInTransaction(() -> {
            if (command.displayName() != null) {
                existing.setDisplayName(StringUtils.hasText(command.displayName()) ? command.displayName().trim()
                        : existing.getModelId());
            }
            if (command.maxContext() != null) {
                existing.setMaxContext(command.maxContext());
            }
            if (command.maxOutput() != null) {
                existing.setMaxOutput(command.maxOutput());
            }
            if (command.supportsToolCalling() != null) {
                existing.setSupportsToolCalling(command.supportsToolCalling());
            }
            if (command.supportsVision() != null) {
                existing.setSupportsVision(command.supportsVision());
            }
            if (command.supportsReasoning() != null) {
                existing.setSupportsReasoning(command.supportsReasoning());
            }
            if (command.enabled() != null) {
                existing.setEnabled(command.enabled());
            }
            if (command.sortOrder() != null) {
                existing.setSortOrder(command.sortOrder());
            }
            if (command.isDefault() != null) {
                existing.setDefault(command.isDefault());
                if (command.isDefault()) {
                    clearDefaultFlag(providerId, existing.getId());
                }
            }
            this.modelRepository.saveAndFlush(existing);
            bumpVersion();
            audit(ACTION_MODEL_UPDATE, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId, "enabled", existing.isEnabled(),
                            "supportsToolCalling", existing.isSupportsToolCalling()));
            return existing;
        });
        afterCommit();
        return updated;
    }

    public AiProviderModel deleteModel(Long providerId, String modelId) {
        requireProvider(providerId);
        AiProviderModel existing = this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
            .orElseThrow(() -> new AiConfigException(CODE_MODEL_NOT_FOUND,
                    "模型不存在：" + providerId + "/" + modelId));
        executeInTransaction(() -> {
            this.modelRepository.delete(existing);
            bumpVersion();
            audit(ACTION_MODEL_DELETE, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId));
            return null;
        });
        afterCommit();
        return existing;
    }

    // ── 全局默认参数 ────────────────────────────────────────────────────────

    public AiConfigService.Defaults updateDefaults(DefaultsCommand command) {
        if (command == null) {
            throw invalidParam("请求体不能为空");
        }
        if (command.temperature() != null && !inRange(command.temperature(), MIN_TEMPERATURE, MAX_TEMPERATURE)) {
            throw invalidParam("temperature 必须在 [" + MIN_TEMPERATURE + "," + MAX_TEMPERATURE + "] 之间（当前 = "
                    + command.temperature() + "）");
        }
        if (command.topP() != null && !inRange(command.topP(), MIN_TOP_P, MAX_TOP_P)) {
            throw invalidParam("top_p 必须在 [" + MIN_TOP_P + "," + MAX_TOP_P + "] 之间（当前 = " + command.topP()
                    + "）");
        }
        if (command.maxTokens() != null
                && (command.maxTokens() < MIN_TOKENS || command.maxTokens() > MAX_TOKENS)) {
            throw invalidParam("max_tokens 必须在 [" + MIN_TOKENS + "," + MAX_TOKENS + "] 之间（当前 = "
                    + command.maxTokens() + "）");
        }
        String modelId = trimToNull(command.modelId());
        if (modelId != null) {
            requireKnownModel(modelId);
            Integer ceiling = this.configService.current()
                .models()
                .stream()
                .filter(model -> modelId.equals(model.getModelId()))
                .map(AiProviderModel::getMaxOutput)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
            if (ceiling != null && command.maxTokens() != null && command.maxTokens() > ceiling) {
                throw invalidParam("max_tokens 不能超过所选模型的 max_output（" + ceiling + "）");
            }
        }

        executeInTransaction(() -> {
            putSetting(AiSetting.DEFAULT_MODEL_ID, modelId);
            putSetting(AiSetting.DEFAULT_TEMPERATURE, command.temperature() == null ? null
                    : String.valueOf(command.temperature()));
            putSetting(AiSetting.DEFAULT_TOP_P, command.topP() == null ? null : String.valueOf(command.topP()));
            putSetting(AiSetting.DEFAULT_MAX_TOKENS,
                    command.maxTokens() == null ? null : String.valueOf(command.maxTokens()));
            putSetting(AiSetting.ALLOW_USER_PARAMS,
                    command.allowUserParams() == null ? null : String.valueOf(command.allowUserParams()));
            bumpVersion();
            audit(ACTION_DEFAULTS_UPDATE, "setting", "defaults",
                    diffOf("modelId", modelId, "temperature", command.temperature(), "topP", command.topP(),
                            "maxTokens", command.maxTokens(), "allowUserParams", command.allowUserParams()));
            return null;
        });
        afterCommit();
        return this.configService.defaults();
    }

    // ── 内部：事务/版本/审计 ──────────────────────────────────────────────────

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        T result = this.transactionTemplate.execute(status -> action.get());
        return result;
    }

    /**
     * {@code ai_setting['config.version']} +1（DB 真源）。
     *
     * <p>
     * 读侧（{@link AiConfigService#current()}）在 Redis 不可用时就是靠比较本值决定要不要重载，
     * 所以它必须与数据行<b>同一个事务</b>：改了数据却没动版本 = 读侧永远看不到（比报错更糟）。
     *
     * <h2>为什么是"读-改-写"而不是原子 UPDATE（复审 M-3）</h2>
     * 配置写路径是<b>低频人工操作</b>（点一次保存），不存在并发抢版本号的场景；JPA 下走
     * {@code find → set → save} 能复用实体与 {@code @UpdateTimestamp}，可读性也最好。
     * 真要并发写同一键，两个事务都会读到旧值 ⇒ 版本号只 +1 一次 —— 但<b>配置数据本身</b>也会
     * 有一个事务被覆盖，版本号少跳一格并不改变"读侧最终会看到最新值"这个结论（DB 版本单调不减、
     * Redis {@code INCR} 每次都动）。故不做 {@code UPDATE ... SET value=value+1} 的原生 SQL：
     * 那是给高并发计数器的写法，在这里只会换来一个绕过实体层的特例。
     *
     * <p>
     * N-5（复审）：只查一次 {@code findBySettingKey}（旧实现查两次：一次算值、一次取实体）。
     */
    private void bumpVersion() {
        Optional<AiSetting> current = this.settingRepository.findBySettingKey(AiSetting.CONFIG_VERSION);
        long next = current.map(AiSetting::getValueJson).map(AiConfigWriter::parseVersion).orElse(0L) + 1L;
        AiSetting setting = current.orElseGet(() -> {
            AiSetting fresh = new AiSetting();
            fresh.setSettingKey(AiSetting.CONFIG_VERSION);
            return fresh;
        });
        setting.setValueJson(String.valueOf(next));
        this.settingRepository.save(setting);
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

    /** 提交之后：跨实例失效信号 + 本实例快照重载。两者失败都不回滚（数据已经落库了）。 */
    private void afterCommit() {
        if (this.redis != null) {
            try {
                this.redis.opsForValue().increment(AiConfigService.REDIS_VERSION_KEY);
            }
            catch (Exception ex) {
                log.warn("INCR {} 失败（Redis 不可用）：本实例已 reload，其他实例要等 DB 版本比对兜底 —— {}",
                        AiConfigService.REDIS_VERSION_KEY, ex.getMessage());
            }
        }
        this.configService.reload();
    }

    private void audit(String action, String targetType, String targetId, Map<String, Object> diff) {
        AiConfigAudit audit = new AiConfigAudit();
        audit.setActor("local-user");
        audit.setAction(action);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setDiffJson(toJson(diff));
        this.auditRepository.save(audit);
    }

    private Map<String, Object> diffOf(Object... pairs) {
        Map<String, Object> diff = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                diff.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        }
        return diff;
    }

    private String toJson(Map<String, Object> diff) {
        try {
            return this.objectMapper.writeValueAsString(diff);
        }
        catch (Exception ex) {
            // 审计不能因为序列化失败而阻断写路径；但也不能静默：落一个可辨认的空壳
            log.warn("审计 diff 序列化失败（动作仍已执行）：{}", ex.getMessage());
            return "{}";
        }
    }

    private void putSetting(String key, String value) {
        if (value == null) {
            this.settingRepository.findBySettingKey(key).ifPresent(this.settingRepository::delete);
            return;
        }
        AiSetting setting = this.settingRepository.findBySettingKey(key).orElseGet(() -> {
            AiSetting fresh = new AiSetting();
            fresh.setSettingKey(key);
            return fresh;
        });
        setting.setValueJson(value);
        this.settingRepository.save(setting);
    }

    private void clearDefaultFlag(Long providerId, Long exceptId) {
        this.modelRepository.findByProviderId(providerId)
            .stream()
            .filter(AiProviderModel::isDefault)
            .filter(model -> exceptId == null || !exceptId.equals(model.getId()))
            .forEach(model -> {
                model.setDefault(false);
                this.modelRepository.saveAndFlush(model);
            });
    }

    // ── 内部：校验与 key 封装 ────────────────────────────────────────────────

    private AiProvider requireProvider(Long providerId) {
        if (providerId == null) {
            throw new AiConfigException(CODE_PROVIDER_NOT_FOUND, "provider id 必填");
        }
        return this.providerRepository.findById(providerId)
            .orElseThrow(() -> new AiConfigException(CODE_PROVIDER_NOT_FOUND, "provider 不存在：" + providerId));
    }

    private void requireKnownModel(String modelId) {
        boolean exists = this.configService.current()
            .models()
            .stream()
            .anyMatch(model -> modelId.equals(model.getModelId()));
        if (!exists) {
            throw new AiConfigException(CODE_MODEL_NOT_FOUND,
                    "默认模型不在任何 provider 的 allowlist 中：" + modelId + "（先把模型加进来）");
        }
    }

    private static String requireCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw invalidParam("code 必填（稳定机读名，如 custom-1）");
        }
        String trimmed = code.trim();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw invalidParam("code 只允许小写字母/数字/下划线/连字符，1-64 字符（当前 = " + trimmed + "）");
        }
        return trimmed;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw invalidParam(field + " 必填");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalidParam(field + " 过长（>" + maxLength + " 字符）");
        }
        return trimmed;
    }

    private static String requireProtocol(String protocol) {
        if (!StringUtils.hasText(protocol)) {
            return "OPENAI_COMPAT";
        }
        String trimmed = protocol.trim();
        if (!"OPENAI_COMPAT".equals(trimmed)) {
            throw invalidParam("protocol 本期只支持 OPENAI_COMPAT（当前 = " + trimmed + "）");
        }
        return trimmed;
    }

    private static String requirePath(String path, String fallback, String field) {
        if (!StringUtils.hasText(path)) {
            return fallback;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            throw invalidParam(field + " 必须以 / 开头（当前 = " + trimmed + "）");
        }
        if (trimmed.length() > 128) {
            throw invalidParam(field + " 过长（>128 字符）");
        }
        return trimmed;
    }

    private static String requireModelId(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId 必填");
        }
        String trimmed = modelId.trim();
        if (trimmed.length() > 128) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId 过长（>128 字符）");
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId 不允许包含空白字符（当前 = " + trimmed + "）");
        }
        return trimmed;
    }

    private static int validateMaxContext(Integer maxContext) {
        if (maxContext == null) {
            return 0;
        }
        if (maxContext < 1 || maxContext > MAX_CONTEXT_CEILING) {
            throw invalidParam("maxContext 必须在 [1," + MAX_CONTEXT_CEILING + "] 之间（当前 = " + maxContext + "）");
        }
        return maxContext;
    }

    private static int validateMaxOutput(Integer maxOutput, Integer maxContext) {
        if (maxOutput == null) {
            return 0;
        }
        if (maxOutput < MIN_TOKENS || maxOutput > MAX_TOKENS) {
            throw invalidParam("maxOutput 必须在 [" + MIN_TOKENS + "," + MAX_TOKENS + "] 之间（当前 = " + maxOutput + "）");
        }
        if (maxContext != null && maxOutput > maxContext) {
            throw invalidParam("maxOutput 不能大于 maxContext（" + maxOutput + " > " + maxContext + "）");
        }
        return maxOutput;
    }

    private static boolean inRange(double value, double min, double max) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= min && value <= max;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AiConfigException invalidParam(String message) {
        return new AiConfigException(CODE_INVALID_PARAM, message);
    }

    /** URL 闸 → 写路径的异常族（控制器只认 {@link AiConfigException}）。 */
    private String checkBaseUrl(String baseUrl) {
        try {
            return this.guard.checkForWrite(baseUrl);
        }
        catch (AiEndpointGuard.GuardException ex) {
            throw new AiConfigException(ex.getCode(), ex.getMessage());
        }
    }

    /** 加密 + 指纹 + last4（一次算好，事务里只落结果；失败在事务外 ⇒ 表里不留半行）。 */
    private KeyMaterial sealKey(String apiKey, String providerCode) {
        if (apiKey.length() < MIN_KEY_LENGTH) {
            throw invalidParam("apiKey 过短（至少 " + MIN_KEY_LENGTH + " 个字符）—— 太短的 key 会让末 4 位掩码"
                    + "失去意义，等同于明文展示");
        }
        if (apiKey.length() > MAX_KEY_LENGTH) {
            throw invalidParam("apiKey 过长（>" + MAX_KEY_LENGTH + " 字符）");
        }
        try {
            String envelope = this.cipher.encrypt(apiKey, providerCode, AiProvider.KEY_REF_DB);
            String fingerprint = this.cipher.fingerprint(apiKey);
            return new KeyMaterial(envelope, AiKeyCipher.last4(apiKey), fingerprint, AiProvider.KEY_REF_DB, "set");
        }
        catch (AiKeyCipher.KeyCipherException ex) {
            // 稳定 code 原样上抛（AI_SECRET_KEK_MISSING / KEY_EMPTY），控制器按 400 落
            throw new AiConfigException(ex.getCode(), ex.getMessage());
        }
    }

    /**
     * key 的落库材料。
     *
     * @param action 审计里的动作词：set / updated / cleared / none
     */
    private record KeyMaterial(String cipher, String last4, String fingerprint, String keyRef, String action) {

        static KeyMaterial none() {
            return new KeyMaterial(null, null, null, AiProvider.KEY_REF_NONE, "none");
        }

        static KeyMaterial cleared() {
            return new KeyMaterial(null, null, null, AiProvider.KEY_REF_NONE, "cleared");
        }
    }

}
