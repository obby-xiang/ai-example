package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiProviderModel;
import com.example.configmgr.ai.config.db.AiProviderModelRepository;
import com.example.configmgr.ai.config.db.AiProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * env 首次种子（模型配置专项 §3.3 / §10-5）：<b>仅当 {@code ai_provider} 为空且 env key 非空</b>。
 *
 * <h2>为什么是 ApplicationRunner 而不是 Condition</h2>
 * 条件求值早于 Flyway —— 首次启动时表还不存在，"DB 有没有 provider"这种判据只能在容器就绪后问。
 * 另外 ApplicationRunner 先于 {@code ApplicationReadyEvent}（{@link com.example.configmgr.ai.run.StartupResumeRunner}
 * 的触发点）执行，于是启动续跑看不到"种子还没写"的中间态。
 *
 * <h2>写什么</h2>
 * 1 行 provider（{@code code=deepseek}、{@code source=ENV_SEED}、{@code key_ref=ENV}、
 * {@code key_cipher=null}、{@code key_last4} 取 env key 末 4 位）+ 1 行模型
 * （{@code model_id=env AI_MODEL}、{@code is_default=true}）。
 * <b>env key 不落库</b>：运维手里的 env 不进 DB（泄露面最小），运行时按 {@code key_ref=ENV} 现读。
 *
 * <h2>写种子 = 写数据行 + 推进配置版本（与人工保存同一口径）</h2>
 * 数据行与 {@code ai_setting['config.version']}+1 在<b>同一事务</b>里提交，提交后重载本实例快照
 * （{@link AiConfigVersionPublisher}）；其它实例按 DB 版本复核。
 * 只有<b>真的写了数据</b>才推进版本（no-op / 并发冲突里输掉的那一方不制造版本抖动）。
 *
 * <h2>strict seed</h2>
 * 表非空 ⇒ env 不覆盖既有配置；仅后增列仍为 {@code NULL} 的旧 ENV_SEED 模型允许 extra-only 回填，
 * 非 NULL（含空对象）一律视为用户配置。回填不改 provider、enabled/default 或其他模型字段。
 * 完整重灌只在显式 {@code app.ai.config.seed-force=true} 时进行，且只刷新 {@code source=ENV_SEED} 的行。
 *
 * <h2>并发双启动</h2>
 * {@code count==0} 判据 + {@code code} 唯一约束兜底：两个实例同时启动时，后提交的一方拿到
 * {@link DataIntegrityViolationException}，按"别人已经种好了"记 INFO —— 不向外抛（启动不能因此失败）。
 */
@Slf4j
@Component
public class AiConfigSeeder implements ApplicationRunner {

    private final AiProviderRepository providerRepository;

    private final AiProviderModelRepository modelRepository;

    private final AiConfigService configService;

    private final AiProperties properties;

    private final TransactionTemplate transactionTemplate;

    /** 版本发布（写路径与种子共用的唯一实现）：种子写了数据就必须让别的实例看得见。 */
    private final AiConfigVersionPublisher versions;

    public AiConfigSeeder(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiConfigService configService, AiProperties properties, PlatformTransactionManager transactionManager,
            AiConfigVersionPublisher versions) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.configService = configService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.versions = versions;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        }
        catch (Exception ex) {
            // 种子失败绝不能让应用起不来：表空 + 无种子 = /health 503（与今天无 key 等价）
            log.warn("AI config seeding incomplete (startup unaffected, AI endpoints degrade by availability)", ex);
        }
    }

    /**
     * 执行一次种子判定。
     *
     * @return true = 本次写入了种子行
     */
    public boolean seed() {
        return isSeedApplied();
    }

    public boolean isSeedApplied() {
        String envKey = this.configService.envApiKey();
        boolean force = this.properties.getConfig().isSeedForce();
        long existing = this.providerRepository.count();
        if (existing > 0 && !force && !isSeedRequestExtraBodyBackfillDue()) {
            if (log.isDebugEnabled()) {
                log.debug("AI config seeding skipped: ai_provider already has {} rows (strict seed: env not applied)", existing);
            }
            return false;
        }
        if (!StringUtils.hasText(envKey)) {
            if (existing == 0) {
                log.info("AI config seeding skipped: ai_provider empty and no AI_API_KEY/DEEPSEEK_API_KEY configured"
                        + " (/health degrades to 503 AI_UNAVAILABLE)");
            }
            else {
                log.info("AI config seeding skipped: no env key configured (seed-force only re-seeds existing ENV_SEED rows)");
            }
            return false;
        }
        try {
            this.configService.requestExtraBody(null, this.configService.resolveEnvModelId());
            boolean extraOnlyAttempt = existing > 0 && !force;
            boolean[] wrote = new boolean[1];
            this.transactionTemplate.executeWithoutResult(status -> wrote[0] = isSeedWritten(force, extraOnlyAttempt));
            if (!wrote[0]) {
                // 没写数据就不发版本：no-op 种子（表非空 / seed-force 只撞到用户改过的行）不得让版本抖动，
                // 否则每个实例都会为一次"什么都没改"白重载一次全表。
                return false;
            }
            // 数据行与 config.version 已在同一事务里落库；提交之后才发布跨实例信号 + 本实例重载
            // （与写路径 AiConfigWriter 同一口径：种子/人工保存必须让别的实例看得见）。
            this.versions.publishAfterCommit();
            log.info("AI config seeded: provider={} key_ref=ENV (env key not persisted)", AiConfigService.ENV_PROVIDER_CODE);
            return true;
        }
        catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException ex) {
            // 唯一约束兜底：另一实例在同一瞬间种好了。重载快照让对方写的那行生效
            // （对方的 DB 版本递增在提交后可见，本实例的周期版本比对也会补上）。
            log.info("AI config seeding raced with another instance (already written, nothing to do)", ex);
            this.configService.reload();
            return false;
        }
    }

    /**
     * 写种子行（与 {@code config.version}+1 同一事务）。
     *
     * <h2>并发交错下也必须"恰好一次"</h2>
     * {@link #isSeedApplied()} 的 {@code count()==0} 判据在事务<b>外</b>：实例 C 可能读到 0，随后实例 A 完整种好并提交，
     * 然后 C 才进事务并<b>看得见</b> A 写的行（READ_COMMITTED）。此时不得重灌数据并重复推进版本。
     *
     * <p>行已存在且<b>本次不是 seed-force</b> ⇒ 先判"这一版种子是否已经完整落地"
     * （provider 的四个 env 派生字段 + 目标模型行 enabled/is_default + 没有第二个默认行）。
     * 完全一致 = 真 no-op：不写、不递增版本；只要有一项不一致就修好并递增 <b>一次</b>。
     *
     * @param extraOnlyAttempt 事务外确定的 extra-only 尝试；锁内失去回填条件时只能 no-op，不得完整重灌
     * @return true = 本次真的写了数据行（调用方据此决定要不要发布版本）
     */
    private boolean isSeedWritten(boolean force, boolean extraOnlyAttempt) {
        // 与人工写路径同一锁顺序；no-op 种子只锁定、不递增版本。
        this.versions.lockInTransaction();
        if (extraOnlyAttempt) {
            if (!backfillSeedRequestExtraBody()) {
                return false;
            }
            this.versions.bumpInTransaction();
            return true;
        }
        AiProvider provider = saveSeedProvider(force);
        if (provider == null) {
            return false;
        }
        String modelId = this.configService.resolveEnvModelId();
        Long providerId = provider.getId();
        AiProviderModel model = this.modelRepository.findByProviderIdAndModelId(providerId, modelId)
            .orElseGet(() -> {
                AiProviderModel fresh = this.configService.envSeedModel();
                fresh.setProviderId(providerId);
                return fresh;
            });
        model.setDefault(true);
        model.setEnabled(true);
        model.setRequestExtraBody(this.configService.envSeedModel().getRequestExtraBody());
        this.modelRepository.saveAndFlush(model);
        // 重灌时换过模型名：旧行必须让出 is_default，否则"两个默认"会让解析落到旧模型
        for (AiProviderModel other : this.modelRepository.findByProviderId(providerId)) {
            if (other.isDefault() && !other.getId().equals(model.getId())) {
                other.setDefault(false);
                this.modelRepository.save(other);
            }
        }
        this.versions.bumpInTransaction();
        return true;
    }

    private AiProvider saveSeedProvider(boolean force) {
        Optional<AiProvider> existing = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE);
        if (existing.isEmpty()) {
            return this.providerRepository.saveAndFlush(this.configService.envSeedProvider());
        }
        AiProvider provider = existing.get();
        if (!AiProvider.SOURCE_ENV_SEED.equals(provider.getSource())) {
            // 用户在 UI 改过这一行 ⇒ 不碰（seed-force 也只重灌"来源=ENV_SEED"的行）
            log.info("AI config re-seed skipped: provider={} source={} (not ENV_SEED)",
                    provider.getCode(), provider.getSource());
            return null;
        }
        if (!force && isSeedAlreadyLanded(provider)) {
            // 并发交错："count 检查之后别人才种好"。值完全一致 ⇒ 真 no-op，不制造版本抖动。
            if (log.isDebugEnabled()) {
                log.debug("AI config seeding skipped: provider={} matches env and model rows complete (seeded by another instance)",
                        provider.getCode());
            }
            return null;
        }
        provider.setDisplayName(AiConfigService.ENV_PROVIDER_DISPLAY_NAME);
        provider.setBaseUrl(this.configService.resolveEnvBaseUrl());
        provider.setCompletionsPath(this.configService.resolveEnvCompletionsPath());
        provider.setKeyLast4(AiKeyCipher.last4(this.configService.envApiKey()));
        provider = this.providerRepository.saveAndFlush(provider);
        log.info("AI config re-seeded provider={} (force={}; needed only when env and DB diverge)",
                provider.getCode(), force);
        return provider;
    }

    /**
     * strict seed 只允许后增列仍为 NULL 的旧 ENV_SEED 模型进入 extra-only 回填。
     */
    private boolean isSeedRequestExtraBodyBackfillDue() {
        AiProvider provider = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE).orElse(null);
        if (provider == null || !AiProvider.SOURCE_ENV_SEED.equals(provider.getSource())) {
            return false;
        }
        AiProviderModel model = this.modelRepository.findByProviderIdAndModelId(provider.getId(),
                this.configService.resolveEnvModelId()).orElse(null);
        return model != null && model.getRequestExtraBody() == null
                && StringUtils.hasText(this.configService.envSeedModel().getRequestExtraBody());
    }

    /**
     * extra-only 回填：不得调用 saveSeedProvider，也不得改 enabled/default 或其他模型字段。
     */
    private boolean backfillSeedRequestExtraBody() {
        AiProvider provider = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE).orElse(null);
        if (provider == null || !AiProvider.SOURCE_ENV_SEED.equals(provider.getSource())) {
            return false;
        }
        AiProviderModel model = this.modelRepository.findByProviderIdAndModelId(provider.getId(),
                this.configService.resolveEnvModelId()).orElse(null);
        if (model == null || model.getRequestExtraBody() != null) {
            return false;
        }
        model.setRequestExtraBody(this.configService.envSeedModel().getRequestExtraBody());
        this.modelRepository.saveAndFlush(model);
        return true;
    }

    /**
     * 这一版种子是否<b>已经完整落地</b>（no-op 判据）：只比"种子会写的那些字段"，不看用户可改的其它列。
     *
     * <p>
     * 用"将要写的值 vs 行里的值"比较，而不是"行存在 ⇒ 跳过"：模型行可以因为半途失败/人工删除而缺失，
     * 那种情况必须补齐（否则实例永远拿不到可用模型）。
     */
    private boolean isSeedAlreadyLanded(AiProvider provider) {
        if (!java.util.Objects.equals(provider.getDisplayName(), AiConfigService.ENV_PROVIDER_DISPLAY_NAME)
                || !java.util.Objects.equals(provider.getBaseUrl(), this.configService.resolveEnvBaseUrl())
                || !java.util.Objects.equals(provider.getCompletionsPath(),
                        this.configService.resolveEnvCompletionsPath())
                || !java.util.Objects.equals(provider.getKeyLast4(),
                        AiKeyCipher.last4(this.configService.envApiKey()))) {
            return false;
        }
        Long providerId = provider.getId();
        String modelId = this.configService.resolveEnvModelId();
        AiProviderModel model = this.modelRepository.findByProviderIdAndModelId(providerId, modelId).orElse(null);
        if (model == null || !model.isEnabled() || !model.isDefault()
                || !java.util.Objects.equals(model.getRequestExtraBody(),
                        this.configService.envSeedModel().getRequestExtraBody())) {
            // 目标模型行缺失/停用/非默认/扩展不一致 ⇒ 数据不齐，仍要补（并 bump）
            return false;
        }
        for (AiProviderModel other : this.modelRepository.findByProviderId(providerId)) {
            if (other.isDefault() && !other.getId().equals(model.getId())) {
                // 还有第二个默认行 ⇒ 仍需清理
                return false;
            }
        }
        return true;
    }
}
