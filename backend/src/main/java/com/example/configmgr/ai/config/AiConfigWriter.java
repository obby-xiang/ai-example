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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 配置<b>写路径</b>（模型配置专项 §3.4 / §3.5 的批②）：新建/更新/删除 provider、模型 allowlist、
 * 全局默认参数。
 *
 * <h2>一次写 = 五件事，顺序固定</h2>
 * <ol>
 * <li><b>先校验、先加密</b>（都在事务外）：baseUrl 过 {@link AiEndpointGuard}、数值过范围、key 用
 * {@link AiKeyCipher} 加密。KEK 缺失时<b>表里不留半行</b>（B5）；</li>
 * <li><b>一个事务</b>：数据行 + {@code ai_setting['config.version']}+1 + 审计行（同一提交点，
 * 不存在"改了数据但版本没动"或"有审计没改动"的中间态）；</li>
 * <li><b>提交后</b> {@code INCR ai:cfg:ver}（跨实例失效信号；Redis 不可用只 WARN，DB 版本号仍是真源）；</li>
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

    /**
     * 版本发布（与 env 种子共用的唯一实现）：数据行 + {@code config.version} 同一事务，
     * 提交后 {@code INCR ai:cfg:ver} + reload + 淘汰运行态缓存。
     */
    private final AiConfigVersionPublisher versions;

    public AiConfigWriter(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiSettingRepository settingRepository, AiConfigAuditRepository auditRepository, AiKeyCipher cipher,
            AiEndpointGuard guard, StringRedisTemplate redis, PlatformTransactionManager transactionManager,
            AiConfigService configService, ObjectMapper objectMapper, AiProviderRegistry runtimeRegistry) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.settingRepository = settingRepository;
        this.auditRepository = auditRepository;
        this.cipher = cipher;
        this.guard = guard;
        // N-6（复审）：事务边界是写路径的正确性前提，装配期就 fail-fast —— 不要在运行时才 NPE
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "AiConfigWriter 需要 PlatformTransactionManager："
                        + "写路径的“数据行 + 版本号 + 审计行”必须同一提交点"));
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.versions = new AiConfigVersionPublisher(settingRepository, redis, configService, runtimeRegistry);
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
     * @param providerId 默认目标所属 provider（与 {@code modelId} 成对；显式给出时该 provider 必须
     * 已启用且模型在其 enabled allowlist 中。省略时按模型唯一归属推断，<b>推断只认已启用的供应方</b>，
     * 旧客户端只提交 {@code modelId} 仍可写；同名歧义只允许"原样回写旧配置"）
     */
    public record DefaultsCommand(String modelId, Double temperature, Double topP, Integer maxTokens,
            Boolean allowUserParams, Long providerId) {

        /** 兼容旧调用（不带 provider 绑定）。 */
        public DefaultsCommand(String modelId, Double temperature, Double topP, Integer maxTokens,
                Boolean allowUserParams) {
            this(modelId, temperature, topP, maxTokens, allowUserParams, null);
        }
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
        // I-3（批③ 首项修复）：baseUrl <b>没变</b>就不重跑 URL 闸。
        //
        // 为什么必须这样：env 种子的 baseUrl 是 CI/E2E 的本地桩（127.0.0.1），而 URL 闸默认禁私网/回环
        // ⇒ 旧实现下"改个显示名/换把 key"都会被 BLOCKED_PRIVATE_ENDPOINT 打回，种子行在设置页上
        // 表现为"改不动"。闸的本意是拦住<b>新引入的</b>地址，不是复审一个用户没动过的既有地址
        // （初始地址由受信来源 env 提供，见设计稿 §3.5"种子路径不受闸限制"）。
        // 反例仍被拦住：改了 baseUrl ⇒ 走闸（哪怕改成另一个私网地址）。
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
            key = sealKey(trimToNull(command.apiKey()), existing.getCode());
            key = new KeyMaterial(key.cipher(), key.last4(), key.fingerprint(), key.keyRef(), "updated");
        }
        else {
            key = null;
        }

        // ── key 的主机边界（复核 ID-03 / S1）────────────────────────────────────────
        // key 属于"某一台主机"：{@code /test} 与 {@code /models/fetch} 直接把库里的 (baseUrl, key) 对
        // 发出去。若允许"换主机 + 保留旧 key"，攻击者只要 改 baseUrl → 点一次「测试连接」就能把库里的
        // key 外带到自己的主机（ID-03 实测路径：4 个请求即外带，默认任意外部主机都放行）。
        // 因此边界放在<b>配对产生处</b>：换 origin（scheme+host+port）就必须重新录入 key 或显式清空。
        // 私网判定是另一条独立判据（上一行 checkBaseUrl 先跑），两者互不掩盖。
        if (key == null && existing.isKeyDeclared()
                && !originOf(existing.getBaseUrl()).equals(originOf(baseUrl))) {
            throw new AiConfigException(CODE_KEY_BOUND_TO_HOST,
                    "改主机名（origin）后必须重新录入 key 或先清空 key：已有 key 只会发往它被录入时的那台主机（旧 "
                            + originOf(existing.getBaseUrl()) + " → 新 " + originOf(baseUrl) + "）");
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
        Long providerId = resolveDefaultProvider(command.providerId(), modelId);
        if (modelId != null) {
            Integer ceiling = maxOutputOf(providerId, modelId);
            if (ceiling != null && command.maxTokens() != null && command.maxTokens() > ceiling) {
                throw invalidParam("max_tokens 不能超过所选模型的 max_output（" + ceiling + "）");
            }
        }

        Long boundProviderId = providerId;
        executeInTransaction(() -> {
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
            bumpVersion();
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
     * 运行时却发给 A（同名模型时静默错配）。所以这里落库时就把归属定下来：
     * <ul>
     * <li>显式 {@code providerId} ⇒ provider 必须<b>已启用</b>，且给的模型必须在它的 enabled
     * allowlist 里（fail-closed：停用端点/停用模型不许成为默认目标）；</li>
     * <li>旧客户端只提交 {@code modelId} ⇒ 唯一所有者才自动绑定（同名模型跨 provider ⇒
     * {@code INVALID_PARAM}，文案给出可操作路径，不替用户选边）；<b>自动归属只认已启用的供应方</b>
     * —— 模型行的 {@code enabled} 与供应方的 {@code enabled} 是两个维度，只按模型判断会把默认目标
     * 硬绑到已停用的供应方（此后整条 AI 变 503）；</li>
     * <li>两者都为空 ⇒ 清空绑定（读侧回到"该 provider 自己的默认模型"）。</li>
     * </ul>
     *
     * <p>停用供应方的模型<b>不参与</b>所有者推断。因此"旧配置原样回写"（见
     * {@link #isUnchangedLegacyDefault}）落在"无启用所有者"分支时仍保持未绑定 —— 读侧按既有回退
     * 用可用供应方自己的模型，既不改写用户的默认模型名，也不替用户改选供应方；非原样回写则按
     * {@code MODEL_NOT_FOUND} 拒绝（不静默落到别家模型）。
     */
    private Long resolveDefaultProvider(Long requestedProviderId, String modelId) {
        if (requestedProviderId != null) {
            AiProvider provider = requireProvider(requestedProviderId);
            if (!provider.isEnabled()) {
                throw invalidParam("默认供应商已停用：请先在「设置 → AI」启用它，或改选其他默认模型");
            }
            if (modelId != null && !hasEnabledModel(requestedProviderId, modelId)) {
                throw new AiConfigException(CODE_MODEL_NOT_FOUND,
                        "模型不在该供应商的 allowlist 中或已停用：" + requestedProviderId + "/" + modelId);
            }
            return requestedProviderId;
        }
        if (modelId == null) {
            return null;
        }
        AiConfigService.ConfigSnapshot snapshot = this.configService.current();
        // 自动归属的供应方启用闸：与显式 providerId 分支同一把尺（停用供应方不许成为默认目标）。
        List<Long> enabledProviders = snapshot.providers()
            .stream()
            .filter(AiProvider::isEnabled)
            .map(AiProvider::getId)
            .filter(Objects::nonNull)
            .toList();
        List<Long> owners = snapshot.models()
            .stream()
            .filter(model -> modelId.equals(model.getModelId()))
            .filter(AiProviderModel::isEnabled)
            .map(AiProviderModel::getProviderId)
            .filter(Objects::nonNull)
            .filter(enabledProviders::contains)
            .distinct()
            .toList();
        if (owners.isEmpty()) {
            if (isUnchangedLegacyDefault(modelId)) {
                // 旧配置指向一个已删除/未登记的模型，或该模型的供应方已全部停用：只改温度等参数时
                // 保留原值（仍不绑定），读侧按既有回退用可用供应方自己的模型；不阻断保存、不改绑
                return null;
            }
            throw new AiConfigException(CODE_MODEL_NOT_FOUND,
                    "默认模型不在任何供应商的 allowlist 中：" + modelId + "（先把模型加进来）");
        }
        if (owners.size() > 1) {
            if (isUnchangedLegacyDefault(modelId)) {
                // 旧配置本身就是"同名模型未绑定"：原样保留（读侧按所有者顺序解析），
                // 用户只改温度时不该被迫先做一次 provider 选择。
                return null;
            }
            throw invalidParam("模型 " + modelId + " 属于多个供应商，请指定 providerId（设置页：在"
                    + "「默认模型」下拉中重新选择该模型所属的供应商后保存）");
        }
        return owners.get(0);
    }

    /** 提交的 (null, modelId) 是否就是库里已有的旧配置（未绑定 provider）——原样回写不算歧义。 */
    private boolean isUnchangedLegacyDefault(String modelId) {
        java.util.Map<String, String> settings = this.configService.current().settings();
        return modelId.equals(trimToNull(settings.get(AiSetting.DEFAULT_MODEL_ID)))
                && trimToNull(settings.get(AiSetting.DEFAULT_PROVIDER_ID)) == null;
    }

    private boolean hasEnabledModel(Long providerId, String modelId) {
        return this.configService.current()
            .models()
            .stream()
            .filter(model -> providerId.equals(model.getProviderId()))
            .filter(AiProviderModel::isEnabled)
            .anyMatch(model -> modelId.equals(model.getModelId()));
    }

    /**
     * 所选模型的 max_output 上限；没有设置/找不到时返回 null（不设上限）。
     *
     * <p>{@code providerId} 为空 = 旧配置未绑定（同名模型可能属于多家）⇒ 取所有同名模型
     * {@code max_output} 的<b>最小值</b>：宁可保守拒绝，也不因为"没绑定"放宽校验。
     */
    private Integer maxOutputOf(Long providerId, String modelId) {
        if (modelId == null) {
            return null;
        }
        return this.configService.current()
            .models()
            .stream()
            .filter(model -> providerId == null || providerId.equals(model.getProviderId()))
            .filter(model -> modelId.equals(model.getModelId()))
            .map(AiProviderModel::getMaxOutput)
            .filter(Objects::nonNull)
            .min(Integer::compare)
            .orElse(null);
    }

    // ── 内部：事务/版本/审计 ──────────────────────────────────────────────────

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        T result = this.transactionTemplate.execute(status -> action.get());
        return result;
    }

    /**
     * {@code ai_setting['config.version']} +1（DB 真源），在<b>当前事务</b>内。
     *
     * <p>
     * 实现只有一份 —— {@link AiConfigVersionPublisher#bumpInTransaction()}：读侧靠比较本值决定要不要
     * 重载，所以它必须与数据行同一提交点；env 种子（{@link AiConfigSeeder}）走同一实现，
     * 于是"某条写路径改了数据却没动版本"在结构上不可能再出现。
     */
    private void bumpVersion() {
        this.versions.bumpInTransaction();
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
