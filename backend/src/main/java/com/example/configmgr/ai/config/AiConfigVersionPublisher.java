package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiSetting;
import com.example.configmgr.ai.config.db.AiSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 配置版本发布：<b>数据行改了，版本必须跟着动</b>。
 *
 * <h2>为什么必须是唯一实现</h2>
 * 读侧（{@link AiConfigService#current()}）只按版本比对决定要不要重载：{@code ai_setting['config.version']}
 * 是 DB 真源，Redis {@code ai:cfg:ver} 是跨实例失效信号。任何一条写数据却不推进版本的路径，
 * 都会让<b>其它实例</b>的快照永久停在旧内容上 —— 配置看起来写成功了，实际只有写自己的那个实例生效。
 *
 * <p>
 * 这不是假设：{@code AiConfigSeeder} 曾直接写 provider/model 行并只 reload 本实例，于是 DB 版本停在
 * 迁移常量 1、{@code ai:cfg:ver} 不存在，其余实例的 provider 列表永远是空的（对外表现为 env 合成行），
 * 跨实例续跑随后被精确绑定判据误判成 {@code PROVIDER_GONE}（run 40182796512）。
 *
 * <p>
 * 因此写路径（{@link AiConfigWriter}）与 env 种子（{@link AiConfigSeeder}）共用本类：
 * <ol>
 * <li>{@link #bumpInTransaction()}：在调用方的<b>同一个事务</b>里 {@code +1}
 *     （"改了数据但版本没动"因此不可能提交）；</li>
 * <li>{@link #publishAfterCommit()}：提交之后 {@code INCR ai:cfg:ver}（Redis 不可用只 WARN，DB 版本仍是真源）
 *     + 本实例 {@link AiConfigService#reload()} + 运行态缓存淘汰。三者失败都不回滚 —— 数据已经落库了。</li>
 * </ol>
 */
@Slf4j
@Component
public class AiConfigVersionPublisher {

    private final AiSettingRepository settingRepository;

    /** 可为 null（单测）：无 Redis ⇒ 跳过跨实例失效信号（DB 版本号仍推进）。 */
    private final StringRedisTemplate redis;

    private final AiConfigService configService;

    /**
     * 运行态缓存（热生效的落点）：默认参数（temperature/topP/maxTokens）烙在已缓存的实例 options 上。
     * 可为 null（单测/未装配）⇒ 跳过淘汰。
     */
    private final AiProviderRegistry runtimeRegistry;

    public AiConfigVersionPublisher(AiSettingRepository settingRepository, StringRedisTemplate redis,
            AiConfigService configService, AiProviderRegistry runtimeRegistry) {
        this.settingRepository = settingRepository;
        this.redis = redis;
        this.configService = configService;
        this.runtimeRegistry = runtimeRegistry;
    }

    /**
     * {@code ai_setting['config.version']} +1（DB 真源）。
     *
     * <p>
     * <b>必须与数据行同一事务</b>：读侧靠比较本值决定要不要重载，改了数据却没动版本 = 读侧永远看不到
     * （比报错更糟）。
     *
     * <h2>为什么是"读-改-写"而不是原子 UPDATE</h2>
     * 配置写路径是<b>低频人工操作</b>，不存在并发抢版本号的场景；JPA 下走 {@code find → set → save}
     * 能复用实体与 {@code @UpdateTimestamp}。真要并发写同一键，两个事务都会读到旧值 ⇒ 版本号只 +1
     * 一次 —— 但<b>配置数据本身</b>也会有一个事务被覆盖，版本号少跳一格并不改变"读侧最终会看到
     * 最新值"这个结论（DB 版本单调不减、Redis {@code INCR} 每次都动）。
     *
     * @return 推进后的版本号
     */
    public long bumpInTransaction() {
        Optional<AiSetting> current = this.settingRepository.findBySettingKey(AiSetting.CONFIG_VERSION);
        long next = current.map(AiSetting::getValueJson).map(AiConfigVersionPublisher::parseVersion).orElse(0L)
                + 1L;
        AiSetting setting = current.orElseGet(() -> {
            AiSetting fresh = new AiSetting();
            fresh.setSettingKey(AiSetting.CONFIG_VERSION);
            return fresh;
        });
        setting.setValueJson(String.valueOf(next));
        this.settingRepository.save(setting);
        return next;
    }

    /** 提交之后：跨实例失效信号 + 本实例快照重载 + 运行态缓存淘汰。三者失败都不回滚。 */
    public void publishAfterCommit() {
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
