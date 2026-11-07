package com.example.configmgr.ai.config;

import com.example.configmgr.common.ErrorCodes;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 * <li><b>提交后</b> {@link AiConfigService#reload()}：本实例立刻用新配置（写的那个人不该等 1s 节流窗）；</li>
 * <li><b>提交后</b> {@link AiProviderRegistry#evictAll()}：默认参数（temperature/topP/maxTokens）烙在
 * 已缓存的运行态 options 上，不淘汰缓存就等于"保存了但下一轮还用旧值"。</li>
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

    public static final String CODE_PROVIDER_NOT_FOUND = ErrorCodes.PROVIDER_NOT_FOUND;

    public static final String CODE_PROVIDER_IN_USE = ErrorCodes.PROVIDER_IN_USE;

    /** 本批新增：{@code code} 唯一（设计稿表里没有，但重名必须有可判定的码，否则只能落 500）。 */
    public static final String CODE_PROVIDER_EXISTS = ErrorCodes.PROVIDER_EXISTS;

    public static final String CODE_MODEL_NOT_FOUND = ErrorCodes.MODEL_NOT_FOUND;

    /** 本批新增：{@code (provider_id, model_id)} 唯一。 */
    public static final String CODE_MODEL_EXISTS = ErrorCodes.MODEL_EXISTS;

    /** 本批新增：{@code code} 是密文 AAD 的一段，不可原地改（改了旧密文就解不开）。 */
    public static final String CODE_CODE_IMMUTABLE = ErrorCodes.CODE_IMMUTABLE;

    /** 本批新增：数值/长度/格式类校验（temperature 越界、key 过短等统一落这里）。 */
    public static final String CODE_INVALID_PARAM = ErrorCodes.INVALID_PARAM;

    /**
     * 改主机名却保留旧 key（复核 I-2）：<b>专用码</b>，前端有"重新录入 key"的专属文案。
     *
     * <p>与 {@link #CODE_INVALID_PARAM} 分开的理由：那一条是"参数不在允许范围"的通用校验，
     * 用户看到它只会去改数值/重选供应商，永远学不到"改主机要重录 key"。语义边界见
     * {@code ErrorCodes#KEY_BOUND_TO_HOST}。
     */
    public static final String CODE_KEY_BOUND_TO_HOST = ErrorCodes.KEY_BOUND_TO_HOST;

    /** 本批新增：模型名不合法（空白/超长/含空白符）。 */
    public static final String CODE_INVALID_MODEL = ErrorCodes.INVALID_MODEL;

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

    private final TransactionTemplate transactionTemplate;

    private final AiConfigService configService;

    private final ObjectMapper objectMapper;

    /** 数据行与 {@code config.version} 同一事务，提交后 reload 并淘汰运行态缓存。 */
    private final AiConfigVersionPublisher versions;

    public AiConfigWriter(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiSettingRepository settingRepository, AiConfigAuditRepository auditRepository, AiKeyCipher cipher,
            AiEndpointGuard guard, PlatformTransactionManager transactionManager,
            AiConfigService configService, ObjectMapper objectMapper, AiProviderRegistry runtimeRegistry) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingRepository = settingRepository;
        this.auditRepository = auditRepository;
        this.cipher = cipher;
        this.guard = guard;
        // N-6（复审）：事务边界是写路径的正确性前提，装配期就 fail-fast —— 不要在运行时才 NPE
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "AiConfigWriter requires PlatformTransactionManager: "
                        + "data rows, the config version, and audit rows must share one commit point"));
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.versions = new AiConfigVersionPublisher(settingRepository, configService, runtimeRegistry);
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
     * null = 清掉该项（回到"未设置"，读侧再按该 provider 自己的模型缺省回落）。范围：temperature [0,2]、
     * top_p [0,1]、max_tokens [1,200000] 且不超过所选模型的 {@code maxOutput}。
     *
     * @param providerId 默认目标所属 provider（与 {@code modelId} 成对；给出 {@code modelId} 时必填，
     * 该 provider 必须已启用且模型在其 enabled allowlist 中；两者同为 {@code null} = 清空默认目标）
     */
    public record DefaultsCommand(String modelId, Double temperature, Double topP, Integer maxTokens,
            Boolean allowUserParams, Long providerId) {
    }

    // ── provider ────────────────────────────────────────────────────────────

    public AiProvider createProvider(ProviderCommand command) {
        if (command == null) {
            throw invalidParam("Request body is required");
        }
        String code = requireCode(command.code());
        String baseUrl = checkBaseUrl(command.baseUrl());
        String displayName = requireText(command.displayName(), "displayName", 128);
        String protocol = requireProtocol(command.protocol());
        String completionsPath = requirePath(command.completionsPath(), "/chat/completions", "completionsPath");
        String modelsPath = requirePath(command.modelsPath(), "/models", "modelsPath");
        String apiKey = trimToNull(command.apiKey());
        KeyMaterial key = apiKey == null ? KeyMaterial.none() : sealKey(apiKey, code);

        AiProvider created = executeInTransaction(() -> {
            if (this.providerRepository.findByCode(code).isPresent()) {
                throw new AiConfigException(CODE_PROVIDER_EXISTS, "Provider code already exists: " + code);
            }
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
            audit(ACTION_PROVIDER_CREATE, "provider", String.valueOf(provider.getId()),
                    diffOf("code", code, "baseUrl", baseUrl, "key", key.action(), "last4", key.last4()));
            return provider;
        });
        afterCommit();
        return created;
    }

    public AiProvider updateProvider(Long providerId, ProviderCommand command) {
        if (command == null) {
            throw invalidParam("Request body is required");
        }
        AiProvider updated = executeInTransaction(() -> {
            AiProvider existing = requireProvider(providerId);
            if (StringUtils.hasText(command.code()) && !command.code().trim().equals(existing.getCode())) {
                throw new AiConfigException(CODE_CODE_IMMUTABLE,
                        "Provider code is immutable because it is part of the key ciphertext AAD; changing it would make the existing ciphertext undecryptable");
            }
            String requestedBaseUrl = trimToNull(command.baseUrl());
            String baseUrl = requestedBaseUrl == null || requestedBaseUrl.equals(existing.getBaseUrl())
                    ? existing.getBaseUrl() : checkBaseUrl(requestedBaseUrl);
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
                KeyMaterial sealed = sealKey(trimToNull(command.apiKey()), existing.getCode());
                key = new KeyMaterial(sealed.cipher(), sealed.last4(), sealed.fingerprint(), sealed.keyRef(), "updated");
            }
            else {
                key = null;
            }
            if (key == null && existing.isKeyDeclared()
                    && !originOf(existing.getBaseUrl()).equals(originOf(baseUrl))) {
                throw new AiConfigException(CODE_KEY_BOUND_TO_HOST,
                        "Changing the origin requires re-entering or clearing the key; the existing key is sent only to its original host (old "
                                + originOf(existing.getBaseUrl()) + " to " + originOf(baseUrl) + ")");
            }

            existing.setDisplayName(displayName);
            existing.setProtocol(protocol);
            existing.setBaseUrl(baseUrl);
            existing.setCompletionsPath(completionsPath);
            existing.setModelsPath(modelsPath);
            existing.setEnabled(command.enabled() == null ? existing.isEnabled() : command.enabled());
            existing.setSortOrder(command.sortOrder() == null ? existing.getSortOrder() : command.sortOrder());
            if (!AiProvider.SOURCE_DB.equals(existing.getSource())) {
                existing.setSource(AiProvider.SOURCE_DB);
            }
            if (key != null) {
                existing.setKeyCipher(key.cipher());
                existing.setKeyLast4(key.last4());
                existing.setKeyFingerprint(key.fingerprint());
                existing.setKeyRef(key.keyRef());
            }
            this.providerRepository.saveAndFlush(existing);
            Map<String, Object> diff = diffOf("code", existing.getCode(), "baseUrl", baseUrl);
            if (key != null) {
                diff.put("key", key.action());
                if (key.last4() != null) {
                    diff.put("last4", key.last4());
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
        AiProvider result = executeInTransaction(() -> {
            AiProvider existing = requireProvider(providerId);
            long modelCount = this.modelRepository.findByProviderId(providerId).size();
            if (hard) {
                if (modelCount > 0) {
                    throw new AiConfigException(CODE_PROVIDER_IN_USE,
                            "Provider still has " + modelCount + " model references and cannot be hard-deleted; delete the models first or use soft delete");
                }
                this.providerRepository.deleteById(providerId);
            }
            else {
                existing.setEnabled(false);
                this.providerRepository.saveAndFlush(existing);
            }
            audit(ACTION_PROVIDER_DELETE, "provider", String.valueOf(providerId),
                    diffOf("code", existing.getCode(), "hard", hard));
            return existing;
        });
        afterCommit();
        return result;
    }

    // ── 模型 allowlist ──────────────────────────────────────────────────────

    public AiProviderModel addModel(Long providerId, ModelCommand command) {
        if (command == null) {
            throw invalidParam("Request body is required");
        }
        String modelId = requireModelId(command.modelId());
        int maxContext = validateMaxContext(command.maxContext());
        int maxOutput = validateMaxOutput(command.maxOutput(), command.maxContext());

        AiProviderModel created = executeInTransaction(() -> {
            AiProvider provider = requireProvider(providerId);
            if (this.modelRepository.findByProviderIdAndModelId(providerId, modelId).isPresent()) {
                throw new AiConfigException(CODE_MODEL_EXISTS, "Model already exists in the allowlist: " + modelId);
            }
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
            audit(ACTION_MODEL_ADD, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId));
            return model;
        });
        afterCommit();
        return created;
    }

    public AiProviderModel updateModel(Long providerId, String modelId, ModelCommand command) {
        if (command == null) {
            throw invalidParam("Request body is required");
        }
        AiProviderModel updated = executeInTransaction(() -> {
            requireProvider(providerId);
            AiProviderModel existing = this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
                .orElseThrow(() -> new AiConfigException(CODE_MODEL_NOT_FOUND,
                        "Model not found: " + providerId + "/" + modelId));
            if (StringUtils.hasText(command.modelId()) && !command.modelId().trim().equals(modelId)) {
                throw new AiConfigException(CODE_INVALID_MODEL,
                        "modelId is immutable because provider_id + model_id is the unique key; delete it and create a new entry");
            }
            Integer nextMaxContext = command.maxContext() == null ? existing.getMaxContext() : command.maxContext();
            Integer nextMaxOutput = command.maxOutput() == null ? existing.getMaxOutput() : command.maxOutput();
            validateMaxContext(nextMaxContext);
            validateMaxOutput(nextMaxOutput, nextMaxContext);

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
            audit(ACTION_MODEL_UPDATE, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId, "enabled", existing.isEnabled(),
                            "supportsToolCalling", existing.isSupportsToolCalling()));
            return existing;
        });
        afterCommit();
        return updated;
    }

    public AiProviderModel deleteModel(Long providerId, String modelId) {
        AiProviderModel result = executeInTransaction(() -> {
            requireProvider(providerId);
            AiProviderModel existing = this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
                .orElseThrow(() -> new AiConfigException(CODE_MODEL_NOT_FOUND,
                        "Model not found: " + providerId + "/" + modelId));
            this.modelRepository.delete(existing);
            audit(ACTION_MODEL_DELETE, "model", providerId + ":" + modelId,
                    diffOf("providerId", providerId, "modelId", modelId));
            return existing;
        });
        afterCommit();
        return result;
    }

    // ── 全局默认参数 ────────────────────────────────────────────────────────

    public AiConfigService.Defaults updateDefaults(DefaultsCommand command) {
        if (command == null) {
            throw invalidParam("Request body is required");
        }
        if (command.temperature() != null && !inRange(command.temperature(), MIN_TEMPERATURE, MAX_TEMPERATURE)) {
            throw invalidParam("temperature must be in [" + MIN_TEMPERATURE + "," + MAX_TEMPERATURE + "] (current = "
                    + command.temperature() + ")");
        }
        if (command.topP() != null && !inRange(command.topP(), MIN_TOP_P, MAX_TOP_P)) {
            throw invalidParam("top_p must be in [" + MIN_TOP_P + "," + MAX_TOP_P + "] (current = " + command.topP()
                    + ")");
        }
        if (command.maxTokens() != null
                && (command.maxTokens() < MIN_TOKENS || command.maxTokens() > MAX_TOKENS)) {
            throw invalidParam("max_tokens must be in [" + MIN_TOKENS + "," + MAX_TOKENS + "] (current = "
                    + command.maxTokens() + ")");
        }
        String modelId = trimToNull(command.modelId());
        executeInTransaction(() -> {
            Long boundProviderId = resolveDefaultProvider(command.providerId(), modelId);
            if (modelId != null) {
                Integer ceiling = maxOutputOf(boundProviderId, modelId);
                if (ceiling != null && command.maxTokens() != null && command.maxTokens() > ceiling) {
                    throw invalidParam("max_tokens must not exceed the selected model max_output (" + ceiling + ")");
                }
            }
            putSetting(AiSetting.DEFAULT_PROVIDER_ID,
                    boundProviderId == null ? null : String.valueOf(boundProviderId));
            putSetting(AiSetting.DEFAULT_MODEL_ID, modelId);
            putSetting(AiSetting.DEFAULT_TEMPERATURE, command.temperature() == null ? null
                    : String.valueOf(command.temperature()));
            putSetting(AiSetting.DEFAULT_TOP_P, command.topP() == null ? null : String.valueOf(command.topP()));
            putSetting(AiSetting.DEFAULT_MAX_TOKENS,
                    command.maxTokens() == null ? null : String.valueOf(command.maxTokens()));
            putSetting(AiSetting.ALLOW_USER_PARAMS,
                    command.allowUserParams() == null ? null : String.valueOf(command.allowUserParams()));
            audit(ACTION_DEFAULTS_UPDATE, "setting", "defaults",
                    diffOf("providerId", boundProviderId, "modelId", modelId, "temperature", command.temperature(),
                            "topP", command.topP(), "maxTokens", command.maxTokens(), "allowUserParams",
                            command.allowUserParams()));
            return null;
        });
        afterCommit();
        return this.configService.defaults();
    }

    /**
     * 把"默认目标"归一成 {@code (providerId, modelId)}。
     *
     * <h2>为什么写路径也要绑 provider</h2>
     * 只存 {@code modelId} 时，读路径只能猜"排序第一的可用 provider"——设置页里选的是 B 的模型，
     * 运行时却发给 A（同名模型时静默错配）。因此当前协议要求默认目标<b>成对提交</b>：
     * <ul>
     * <li>给出 {@code modelId} ⇒ {@code providerId} 必填，provider 必须<b>已启用</b>，且该模型必须在
     * 它的 enabled allowlist 里（fail-closed：停用端点/停用模型不许成为默认目标）；</li>
     * <li>只给 {@code providerId} ⇒ 绑定该 provider，模型取它自己的默认模型；</li>
     * <li>两者都为空 ⇒ 清空绑定（读侧回到"库序里第一个能给出自己默认模型的 provider"）。</li>
     * </ul>
     *
     * <p>没有"按模型名推断 provider"的分支：同名模型跨 provider 时推断只会静默选边，读写同一份
     * 显式二元组才是唯一协议。</p>
     */
    private Long resolveDefaultProvider(Long requestedProviderId, String modelId) {
        if (requestedProviderId == null) {
            if (modelId != null) {
                throw invalidParam("modelId must be submitted together with providerId; select the provider that owns the model "
                        + "in the default-model dropdown and save");
            }
            return null;
        }
        AiProvider provider = requireProvider(requestedProviderId);
        if (!provider.isEnabled()) {
            throw invalidParam("The default provider is disabled; enable it in Settings > AI or choose another default model");
        }
        if (modelId != null && !hasEnabledModel(requestedProviderId, modelId)) {
            throw new AiConfigException(CODE_MODEL_NOT_FOUND,
                    "Model is not enabled in this provider allowlist: " + requestedProviderId + "/" + modelId);
        }
        return requestedProviderId;
    }

    private boolean hasEnabledModel(Long providerId, String modelId) {
        return this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
            .filter(AiProviderModel::isEnabled)
            .isPresent();
    }

    /**
     * 所选模型的 max_output 上限；没有设置/找不到时返回 null（不设上限）。
     *
     * <p>只按显式 {@code (providerId, modelId)} 精确取行：同名模型在别家的 {@code max_output} 不属于
     * 这个默认目标，不能参与取值。</p>
     */
    private Integer maxOutputOf(Long providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return null;
        }
        return this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
            .map(AiProviderModel::getMaxOutput)
            .orElse(null);
    }

    // ── 内部：事务/版本/审计 ──────────────────────────────────────────────────

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        return this.transactionTemplate.execute(status -> {
            // 先锁主键版本行并递增，再写数据与审计；并发事务不会提交同号配置快照。
            this.versions.bumpInTransaction();
            return action.get();
        });
    }

    /** 提交之后：跨实例失效信号 + 本实例快照重载 + 运行态缓存淘汰（{@link AiConfigVersionPublisher}）。 */
    private void afterCommit() {
        this.versions.publishAfterCommit();
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
            log.warn("audit diff serialization failed (action already applied)", ex);
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
            throw new AiConfigException(CODE_PROVIDER_NOT_FOUND, "provider id is required");
        }
        return this.providerRepository.findById(providerId)
            .orElseThrow(() -> new AiConfigException(CODE_PROVIDER_NOT_FOUND, "Provider not found: " + providerId));
    }

    private static String requireCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw invalidParam("code is required; use a stable machine-readable identifier such as custom-1");
        }
        String trimmed = code.trim();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw invalidParam("code may contain only lowercase letters, digits, underscores, and hyphens, with length 1-64 (current = " + trimmed + ")");
        }
        return trimmed;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw invalidParam(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalidParam(field + " exceeds the maximum length of " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String requireProtocol(String protocol) {
        if (!StringUtils.hasText(protocol)) {
            return "OPENAI_COMPAT";
        }
        String trimmed = protocol.trim();
        if (!"OPENAI_COMPAT".equals(trimmed)) {
            throw invalidParam("protocol supports only OPENAI_COMPAT (current = " + trimmed + ")");
        }
        return trimmed;
    }

    private static String requirePath(String path, String fallback, String field) {
        if (!StringUtils.hasText(path)) {
            return fallback;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            throw invalidParam(field + " must start with / (current = " + trimmed + ")");
        }
        if (trimmed.length() > 128) {
            throw invalidParam(field + " exceeds the maximum length of 128 characters");
        }
        return trimmed;
    }

    private static String requireModelId(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId is required");
        }
        String trimmed = modelId.trim();
        if (trimmed.length() > 128) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId exceeds the maximum length of 128 characters");
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new AiConfigException(CODE_INVALID_MODEL, "modelId must not contain whitespace (current = " + trimmed + ")");
        }
        return trimmed;
    }

    private static int validateMaxContext(Integer maxContext) {
        if (maxContext == null) {
            return 0;
        }
        if (maxContext < 1 || maxContext > MAX_CONTEXT_CEILING) {
            throw invalidParam("maxContext must be in [1," + MAX_CONTEXT_CEILING + "] (current = " + maxContext + ")");
        }
        return maxContext;
    }

    private static int validateMaxOutput(Integer maxOutput, Integer maxContext) {
        if (maxOutput == null) {
            return 0;
        }
        if (maxOutput < MIN_TOKENS || maxOutput > MAX_TOKENS) {
            throw invalidParam("maxOutput must be in [" + MIN_TOKENS + "," + MAX_TOKENS + "] (current = " + maxOutput + ")");
        }
        if (maxContext != null && maxOutput > maxContext) {
            throw invalidParam("maxOutput must not exceed maxContext (" + maxOutput + " > " + maxContext + ")");
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

    /**
     * {@code baseUrl} 的 <b>origin</b>（{@code scheme://host:port}，默认端口归一化），用于判定
     * "key 还能不能用在这台主机上"。
     *
     * <p>
     * 为什么是 origin 而不是 host：https→http 或换端口同样会把 key 发到另一个服务上
     * （同一台机器上监听 8080 的任意进程都能收到明文 key）。解析失败时退化为原串（会与任何
     * 合法 origin 不等 ⇒ 走"必须重录 key"的保守分支，不会静默放行）。
     */
    private static String originOf(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(baseUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort() == -1 ? defaultPort(scheme) : uri.getPort();
            return scheme + "://" + host + ":" + port;
        }
        catch (java.net.URISyntaxException ex) {
            return baseUrl.trim();
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : ("http".equals(scheme) ? 80 : -1);
    }

    /** 加密 + 指纹 + last4（一次算好，事务里只落结果；失败在事务外 ⇒ 表里不留半行）。 */
    private KeyMaterial sealKey(String apiKey, String providerCode) {
        if (apiKey.length() < MIN_KEY_LENGTH) {
            throw invalidParam("apiKey is too short; at least " + MIN_KEY_LENGTH + " characters are required so the last-four mask"
                    + " does not expose the entire key");
        }
        if (apiKey.length() > MAX_KEY_LENGTH) {
            throw invalidParam("apiKey exceeds the maximum length of " + MAX_KEY_LENGTH + " characters");
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
