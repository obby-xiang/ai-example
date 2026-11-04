package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.config.db.AiSettingRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 配置版本发布：<b>数据行改了，版本必须跟着动</b>。
 *
 * <h2>为什么必须是唯一实现</h2>
 * 读侧（{@link AiConfigService#current()}）只按版本比对决定要不要重载：{@code ai_setting['config.version']}
 * 是唯一真源。写路径（{@link AiConfigWriter}）与 env 种子（{@link AiConfigSeeder}）共用本类：
 * 数据行与版本在同一事务提交，提交后刷新本实例并淘汰运行态缓存。
 */
@Component
public class AiConfigVersionPublisher {

    private final AiSettingRepository settingRepository;

    private final AiConfigService configService;

    /** 可为 null（未装配运行态缓存）⇒ 跳过淘汰。 */
    private final AiProviderRegistry runtimeRegistry;

    public AiConfigVersionPublisher(AiSettingRepository settingRepository, AiConfigService configService,
            AiProviderRegistry runtimeRegistry) {
        this.settingRepository = settingRepository;
        this.configService = configService;
        this.runtimeRegistry = runtimeRegistry;
    }

    /** 在当前事务内锁定主键版本行；供需要先判定 no-op 的写路径使用。 */
    public void lockInTransaction() {
        this.settingRepository.findBySettingKeyForUpdate(AiSetting.CONFIG_VERSION);
    }

    /**
     * {@code ai_setting['config.version']} +1（DB 真源）。
     *
     * <p>必须与数据行同一事务。通过主键 {@code SELECT ... FOR UPDATE} 锁定版本行，再递增并 flush；
     * 并发配置写因此获得不同且单调的版本号。
     *
     * @return 推进后的版本号
     */
    public long bumpInTransaction() {
        Optional<AiSetting> current = this.settingRepository.findBySettingKeyForUpdate(AiSetting.CONFIG_VERSION);
        long next = current.map(AiSetting::getValueJson).map(AiConfigVersionPublisher::parseVersion).orElse(0L)
                + 1L;
        AiSetting setting = current.orElseGet(() -> {
            AiSetting fresh = new AiSetting();
            fresh.setSettingKey(AiSetting.CONFIG_VERSION);
            return fresh;
        });
        setting.setValueJson(String.valueOf(next));
        this.settingRepository.saveAndFlush(setting);
        return next;
    }

    /** 提交之后刷新本实例并淘汰运行态缓存；其它实例按 DB 版本复核。 */
    public void publishAfterCommit() {
        this.configService.reload();
        // 热生效：默认参数烙在已缓存的运行态 options 上，不淘汰 ⇒ 下一轮仍用旧值。
        if (this.runtimeRegistry != null) {
            this.runtimeRegistry.evictAll();
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

}
