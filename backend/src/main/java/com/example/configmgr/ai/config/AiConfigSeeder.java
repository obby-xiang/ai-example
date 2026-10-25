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
 * 数据行与 {@code ai_setting['config.version']}+1 在<b>同一事务</b>里提交，提交之后才发布跨实例
 * 失效信号（{@code INCR ai:cfg:ver}）并重载本实例快照（{@link AiConfigVersionPublisher}）。
 *
 * <p>
 * 这不是可选步骤：读侧（{@link AiConfigService#current()}）只按版本比对决定要不要重载，种子若不推进
 * 版本，<b>其它实例</b>的 provider 列表就永远是空的（对外表现为 env 合成行），续跑的精确绑定判据随即
 * 误判 {@code PROVIDER_GONE} —— run 40182796512 的三实例失败正是这一条。
 * 只有<b>真的写了数据</b>才推进版本（no-op / 并发冲突里输掉的那一方不制造版本抖动）。
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
            boolean[] wrote = new boolean[1];
            this.transactionTemplate.executeWithoutResult(status -> wrote[0] = writeSeed(force));
            if (!wrote[0]) {
                // 没写数据就不发版本：no-op 种子（表非空 / seed-force 只撞到用户改过的行）不得让版本抖动，
                // 否则每个实例都会为一次"什么都没改"白重载一次全表。
                return false;
            }
            // 数据行与 config.version 已在同一事务里落库；提交之后才发布跨实例信号 + 本实例重载
            // （与写路径 AiConfigWriter 同一口径：种子/人工保存必须让别的实例看得见）。
            this.versions.publishAfterCommit();
            log.info("AI 配置种子已写入：provider={} key_ref=ENV（env key 不落库）", AiConfigService.ENV_PROVIDER_CODE);
            return true;
        }
        catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException ex) {
            // 唯一约束兜底：另一实例在同一瞬间种好了。重载快照让对方写的那行生效
            // （对方的版本发布（DB +1 / INCR ai:cfg:ver）在它提交之后才发生，本实例的 1s 版本比对会补上）。
            log.info("AI 配置种子并发冲突（另一实例已写入，本次不写）：{}", ex.getClass().getSimpleName());
            this.configService.reload();
            return false;
        }
    }

    /**
     * 写种子行（与 {@code config.version}+1 同一事务）。
     *
     * <h2>并发交错下也必须"恰好一次"</h2>
     * {@link #seed()} 的 {@code count()==0} 判据在事务<b>外</b>：实例 C 可能读到 0，随后实例 A 完整种好并提交，
     * 然后 C 才进事务并<b>看得见</b> A 写的行（READ_COMMITTED）。旧实现这时会把这行原样"重灌"一遍并再
     * bump 一次版本 ⇒ 同一份种子数据、同一次冷启动，版本与失效信号各推进两次（三个实例白 reload 一轮）。
     *
     * <p>现在的口径：行已存在且<b>本次不是 seed-force</b> ⇒ 先判"这一版种子是否已经完整落地"
     * （provider 的四个 env 派生字段 + 目标模型行 enabled/is_default + 没有第二个默认行）。
     * 完全一致 = 真 no-op：不写、不 bump、不 INCR；只要有一项不一致（env 变了 / 模型行缺失或停用 /
     * 存在第二个默认行）就照常修好并 bump <b>一次</b>。
     *
     * @return true = 本次真的写了数据行（调用方据此决定要不要发布版本）
     */
    private boolean writeSeed(boolean force) {
        Optional<AiProvider> existing = this.providerRepository.findByCode(AiConfigService.ENV_PROVIDER_CODE);
        AiProvider provider;
        if (existing.isPresent()) {
            provider = existing.get();
            if (!AiProvider.SOURCE_ENV_SEED.equals(provider.getSource())) {
                // 用户在 UI 改过这一行 ⇒ 不碰（seed-force 也只重灌"来源=ENV_SEED"的行）
                log.info("AI 配置种子跳过重灌：provider={} 的来源是 {}（非 ENV_SEED）",
                        provider.getCode(), provider.getSource());
                return false;
            }
            if (!force && seedAlreadyLanded(provider)) {
                // 并发交错："count 检查之后别人才种好"。值完全一致 ⇒ 真 no-op，不制造版本抖动。
                log.debug("AI 配置种子跳过：provider={} 与 env 一致且模型行齐备（另一实例已种好）",
                        provider.getCode());
                return false;
            }
            provider.setDisplayName(AiConfigService.ENV_PROVIDER_DISPLAY_NAME);
            provider.setBaseUrl(this.configService.resolveEnvBaseUrl());
            provider.setCompletionsPath(this.configService.resolveEnvCompletionsPath());
            provider.setKeyLast4(AiKeyCipher.last4(this.configService.envApiKey()));
            provider = this.providerRepository.saveAndFlush(provider);
            log.info("AI 配置种子重灌 provider={}（force={}：env 与库不一致时才需要这一步）",
                    provider.getCode(), force);
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
        // 版本 +1 必须在<b>本事务内</b>：改了数据却没动版本，别的实例的快照永远看不到这行。
        this.versions.bumpInTransaction();
        return true;
    }

    /**
     * 这一版种子是否<b>已经完整落地</b>（no-op 判据）：只比"种子会写的那些字段"，不看用户可改的其它列。
     *
     * <p>
     * 用"将要写的值 vs 行里的值"比较，而不是"行存在 ⇒ 跳过"：模型行可以因为半途失败/人工删除而缺失，
     * 那种情况必须补齐（否则实例永远拿不到可用模型）。
     */
    private boolean seedAlreadyLanded(AiProvider provider) {
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
        if (model == null || !model.isEnabled() || !model.isDefault()) {
            // 目标模型行缺失/停用/非默认 ⇒ 数据不齐，仍要补（并 bump）
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
