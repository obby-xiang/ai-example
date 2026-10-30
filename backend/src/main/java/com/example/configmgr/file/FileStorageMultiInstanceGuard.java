package com.example.configmgr.file;

import com.example.configmgr.config.AppProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多实例部署的<b>启动期闸门</b>：{@code app.file.storage-path} 必须是绝对路径。
 *
 * <h2>为什么必须绝对</h2>
 * 相对路径按<b>各实例的 JVM 工作目录</b>解析：同一份共享存储挂载在两个实例上时，A 的工作目录是
 * {@code /opt/app}、B 是 {@code /srv/app}，同一个存储键（{@code uuid.xlsx}）在两边会解析成两个不同目录
 * —— 「共享文件存储」名存实亡，表现为 A 上传、B 报文件不存在。故多实例部署下要求绝对路径，配错即
 * <b>启动失败</b>（与 {@code RedisClusterTopologyGuard} 同姿态：配置错误必须让实例起不来，
 * 而不是带病上线）。
 *
 * <h2>什么算多实例</h2>
 * 任一条件成立即按多实例校验：
 * <ul>
 * <li>显式开关 {@code app.file.multi-instance=true}（没有专属 profile 的部署用它）；</li>
 * <li>激活了多实例 profile：{@code cluster}（Redis Cluster / 多实例）、{@code multi-instance}、
 * {@code h2tcp}、{@code postgresql}；</li>
 * <li><b>数据源本身就是共享库</b>：{@code spring.datasource.url} 以 {@code jdbc:h2:tcp:} /
 * {@code jdbc:h2:ssl:}（H2 远程 Server —— 库已跨进程共享，文件不能再各写各的盘）或
 * {@code jdbc:postgresql:} 开头。
 * 这条不依赖 profile：三实例验证网就是直接给 {@code --spring.datasource.url=jdbc:h2:tcp://...}；
 * 只认 profile 会漏掉"仅 h2tcp"的部署，A 写 B 读不到。</li>
 * </ul>
 *
 * <p><b>默认单实例开发不受影响</b>：无上述开关/profile、数据源仍是 {@code jdbc:h2:file:} /
 * {@code jdbc:h2:mem:} 时本类直接放行，相对路径 {@code ./data/files} 仍是合法缺省。
 */
@Component
public class FileStorageMultiInstanceGuard {

    /** 既有 Redis Cluster profile（多实例数据面）。 */
    public static final String CLUSTER_PROFILE = "cluster";

    /** 显式多实例 profile（无专属 YAML，仅作激活标记）。 */
    public static final String MULTI_INSTANCE_PROFILE = "multi-instance";

    /** H2 TCP Server 模式：数据库已跨进程共享，文件也必须共享。 */
    public static final String H2TCP_PROFILE = "h2tcp";

    /** PostgreSQL 共享库 profile（M2 多实例切换）。 */
    public static final String POSTGRESQL_PROFILE = "postgresql";

    /** H2 TCP 数据源前缀（库已跨进程共享；无需额外 profile 也必须共享文件）。 */
    public static final String H2TCP_DATASOURCE_PREFIX = "jdbc:h2:tcp:";

    /** H2 SSL（远程 Server 模式）数据源前缀。 */
    public static final String H2SSL_DATASOURCE_PREFIX = "jdbc:h2:ssl:";

    /** PostgreSQL 数据源前缀（共享库）。 */
    public static final String POSTGRESQL_DATASOURCE_PREFIX = "jdbc:postgresql:";

    /** 视为多实例部署的 profile 集合。 */
    public static final Set<String> MULTI_INSTANCE_PROFILES =
            Set.of(CLUSTER_PROFILE, MULTI_INSTANCE_PROFILE, H2TCP_PROFILE, POSTGRESQL_PROFILE);

    /** 显式多实例开关属性名（无专属 profile 的部署用）。 */
    public static final String MULTI_INSTANCE_PROPERTY = "app.file.multi-instance";

    /** 属性名（与 {@code app.file.storage-path} 同键）。 */
    public static final String STORAGE_PATH_PROPERTY = "app.file.storage-path";

    /** 标准环境变量名（Spring relaxed binding：APP_FILE_STORAGE_PATH → app.file.storage-path）。 */
    public static final String STORAGE_PATH_ENV = "APP_FILE_STORAGE_PATH";

    public FileStorageMultiInstanceGuard(AppProperties appProperties, Environment environment) {
        List<String> reasons = multiInstanceReasons(appProperties, environment);
        if (reasons.isEmpty()) {
            return;
        }
        validateAbsolute(appProperties, reasons);
    }

    /** 多实例判据（返回触发原因，空 = 单实例，不校验）。 */
    static List<String> multiInstanceReasons(AppProperties appProperties, Environment environment) {
        return multiInstanceReasons(appProperties.getFile().isMultiInstance(), environment);
    }

    /**
     * 多实例判据（<b>只读 {@link Environment}</b>，供其他启动期组件复用同一口径）。
     *
     * <p>与 {@link #multiInstanceReasons(AppProperties, Environment)} 的唯一差别是显式开关
     * {@code app.file.multi-instance} 直接从环境读取（调用方没有绑定好的 {@link AppProperties}）。
     */
    public static List<String> multiInstanceReasons(Environment environment) {
        boolean explicit = environment != null
                && Boolean.parseBoolean(environment.getProperty(MULTI_INSTANCE_PROPERTY));
        return multiInstanceReasons(explicit, environment);
    }

    private static List<String> multiInstanceReasons(boolean explicitSwitch, Environment environment) {
        List<String> reasons = new ArrayList<>();
        if (explicitSwitch) {
            reasons.add(MULTI_INSTANCE_PROPERTY + "=true");
        }
        if (environment != null) {
            for (String profile : environment.getActiveProfiles()) {
                if (MULTI_INSTANCE_PROFILES.contains(profile)) {
                    reasons.add("profile=" + profile);
                }
            }
            String datasourceUrl = environment.getProperty("spring.datasource.url");
            if (datasourceUrl != null) {
                String normalized = datasourceUrl.trim().toLowerCase(Locale.ROOT);
                if (normalized.startsWith(H2TCP_DATASOURCE_PREFIX)) {
                    reasons.add("datasource=" + H2TCP_DATASOURCE_PREFIX);
                }
                else if (normalized.startsWith(H2SSL_DATASOURCE_PREFIX)) {
                    reasons.add("datasource=" + H2SSL_DATASOURCE_PREFIX);
                }
                else if (normalized.startsWith(POSTGRESQL_DATASOURCE_PREFIX)) {
                    reasons.add("datasource=" + POSTGRESQL_DATASOURCE_PREFIX);
                }
            }
        }
        return reasons;
    }

    private static void validateAbsolute(AppProperties appProperties, List<String> reasons) {
        String reason = String.join(" / ", reasons);
        String value = appProperties.getFile().getStoragePath();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("多实例部署缺少 " + STORAGE_PATH_PROPERTY
                    + "（判据：" + reason + "）：请配置共享存储的绝对路径（或环境变量 " + STORAGE_PATH_ENV + "）");
        }
        if (value.contains("${")) {
            throw new IllegalStateException(STORAGE_PATH_PROPERTY + " 未解析（当前 = " + value
                    + "，判据：" + reason + "）：多实例部署须显式提供绝对路径（环境变量 " + STORAGE_PATH_ENV + "）");
        }
        Path path;
        try {
            path = Path.of(value);
        }
        catch (InvalidPathException e) {
            throw new IllegalStateException(STORAGE_PATH_PROPERTY + " 不是合法路径（当前 = " + value
                    + "，判据：" + reason + "）", e);
        }
        if (!path.isAbsolute()) {
            throw new IllegalStateException("多实例部署要求 " + STORAGE_PATH_PROPERTY + " 为绝对路径（当前 = "
                    + value + "，判据：" + reason + "）：相对路径按各实例的 JVM 工作目录解析，"
                    + "同一存储键在 A/B 实例会指向不同目录（A 写、B 读不到）。"
                    + "请配置绝对路径（或环境变量 " + STORAGE_PATH_ENV + "）；单实例本地开发可保持默认 profile。");
        }
    }
}
