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
 * <h2>strict seed</h2>
 * 表非空 ⇒ env <b>完全不参与</b>（不覆盖、不追加、不做 env 兜底）；需要重灌时显式
 * {@code app.ai.config.seed-force=true}，且只刷新 {@code source=ENV_SEED} 的行。
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

    public AiConfigSeeder(AiProviderRepository providerRepository, AiProviderModelRepository modelRepository,
            AiConfigService configService, AiProperties properties, PlatformTransactionManager transactionManager) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.configService = configService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        }
        catch (Exception ex) {
            // 种子失败绝不能让应用起不来：表空 + 无种子 = /health 503（与今天无 key 等价）
            log.warn("AI 配置种子未完成（不影响启动，AI 端点按可用性判定降级）：{}", ex.getMessage(), ex);
        }
    }

    /**
     * 执行一次种子判定。
     *
     * @return true = 本次写入了种子行
     */
    public boolean seed() {
        String envKey = this.configService.envApiKey();
        boolean force = this.properties.getConfig().isSeedForce();
        long existing = this.providerRepository.count();
        if (existing > 0 && !force) {
            log.debug("AI 配置种子跳过：ai_provider 已有 {} 行（strict seed：env 不参与）", existing);
            return false;
        }
        if (!StringUtils.hasText(envKey)) {
            if (existing == 0) {
                log.info("AI 配置种子跳过：ai_provider 为空且未配置 AI_API_KEY/DEEPSEEK_API_KEY"
                        + "（/health 将按 503 AI_UNAVAILABLE 降级）");
            }
            else {
                log.info("AI 配置种子跳过：未配置 env key（seed-force 也只重灌已有 ENV_SEED 行）");
            }
            return false;
        }
        try {
            this.transactionTemplate.executeWithoutResult(status -> writeSeed(force));
            this.configService.reload();
            log.info("AI 配置种子已写入：provider={} key_ref=ENV（env key 不落库）", AiConfigService.ENV_PROVIDER_CODE);
            return true;
        }
        catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException ex) {
            // 唯一约束兜底：另一实例在同一瞬间种好了。重载快照让对方写的那行生效。
            log.info("AI 配置种子并发冲突（另一实例已写入，本次不写）：{}", ex.getClass().getSimpleName());
            this.configService.reload();
            return false;
        }
    }

    private void writeSeed(boolean force) {
        Optional<AiProvider> existing = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE);
        AiProvider provider;
        if (existing.isPresent()) {
            provider = existing.get();
            if (!AiProvider.SOURCE_ENV_SEED.equals(provider.getSource())) {
                // 用户在 UI 改过这一行 ⇒ 不碰（seed-force 也只重灌"来源=ENV_SEED"的行）
                log.info("AI 配置种子跳过重灌：provider={} 的来源是 {}（非 ENV_SEED）",
                        provider.getCode(), provider.getSource());
                return;
            }
            provider.setDisplayName(AiConfigService.ENV_PROVIDER_DISPLAY_NAME);
            provider.setBaseUrl(this.configService.resolveEnvBaseUrl());
            provider.setCompletionsPath(this.configService.resolveEnvCompletionsPath());
            provider.setKeyLast4(AiKeyCipher.last4(this.configService.envApiKey()));
            provider = this.providerRepository.saveAndFlush(provider);
            log.info("AI 配置种子按 seed-force 重灌 provider={}", provider.getCode());
        }
        else {
            provider = this.providerRepository.saveAndFlush(this.configService.envSeedProvider());
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
        this.modelRepository.saveAndFlush(model);
        // 重灌时换过模型名：旧行必须让出 is_default，否则"两个默认"会让解析落到旧模型
        for (AiProviderModel other : this.modelRepository.findByProviderId(providerId)) {
            if (other.isDefault() && !other.getId().equals(model.getId())) {
                other.setDefault(false);
                this.modelRepository.save(other);
            }
        }
    }
}
