package com.example.configmgr.ai.config;

import com.example.configmgr.common.ErrorCodes;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * URL 闸（模型配置专项 §3.5；调研 §3.1d）—— SSRF 的唯一落点。
 *
 * <h2>判据（按顺序，先便宜后昂贵）</h2>
 * <ol>
 * <li><b>绝对地址</b>：必须能解析出 scheme + host；相对路径/裸域名一律 {@code INVALID_BASE_URL}；</li>
 * <li><b>只有 http/https</b>：{@code file:}/{@code ftp:}/{@code jar:} 等一律拒（本仓的客户端只会说
 * OpenAI 兼容协议，别的 scheme 只会是"想读本地文件"）；</li>
 * <li><b>禁内嵌凭据</b>：{@code https://user:pass@host} 拒 —— 凭据会被原样写进日志/错误消息，
 * 而本项目已有独立的 key 通道（{@code key_ref}），URL 里带凭据是绕过 key 托管的旁路；</li>
 * <li><b>私网/回环/元数据</b>：解析 host 的<b>全部</b>地址，任一落在 loopback / any-local /
 * link-local（含 {@code 169.254.169.254} 云元数据）/ site-local / CGNAT / IPv6 ULA / multicast
 * ⇒ {@code BLOCKED_PRIVATE_ENDPOINT}。开关 {@code app.ai.config.allow-private-endpoints=true} 才放行。</li>
 * </ol>
 *
 * <h2>种子路径的例外</h2>
 * {@link #checkForOutbound(String, boolean)} 的 {@code trusted=true} 只跳过第 4 条（env 是受信来源；
 * CI 的桩就在 {@code 127.0.0.1:18399}），第 1–3 条仍然生效 —— "受信"不等于"可以 file:// 或带凭据"。
 *
 * <h2>已知限制（登记为知悉风险，不在本批根治）</h2>
 * 本闸在<b>写入时</b>解析域名。攻击者可用 DNS 重绑定（首次解析到公网、连接时解析到
 * 127.0.0.1）绕过。根治要接管 HTTP 客户端的解析（自定义 {@code DnsResolver} + 连接期复查），
 * 超出本批范围；本项目是单用户本地应用、配置面本身已是"管理员权限"，风险面有限。
 * 解析失败（离线/域名尚未生效）<b>不</b>判脏值 —— 解析不出来就连接不上，真连不上由探活报
 * {@code UNREACHABLE}，把一个"写不进库的离线环境"钉死在这里没有收益。
 */
@Slf4j
@Component
public class AiEndpointGuard {

    /** URL 不合法（scheme/host/凭据/长度/端口）。 */
    public static final String CODE_INVALID_BASE_URL = ErrorCodes.INVALID_BASE_URL;

    /** 目标落在私网/回环/元数据地址且开关未开。 */
    public static final String CODE_BLOCKED_PRIVATE_ENDPOINT = ErrorCodes.BLOCKED_PRIVATE_ENDPOINT;

    /** 与 {@code ai_provider.base_url} 的列宽一致（512）。 */
    private static final int MAX_URL_LENGTH = 512;

    /** 云元数据主机名（IP 形态由 link-local 判定覆盖；这里兜住"名字解析到元数据"的写法）。 */
    private static final String[] METADATA_HOSTS = { "metadata.google.internal", "metadata", "instance-data",
            "metadata.azure.com" };

    private final AiProperties properties;

    public AiEndpointGuard(AiProperties properties) {
        this.properties = properties;
    }

    /** 写路径（新建/更新 provider 的 baseUrl）：<b>全闸</b>，私网判定总是生效（除非开关打开）。 */
    public String checkForWrite(String baseUrl) {
        return check(baseUrl, false);
    }

    /**
     * 出站前（{@code /test}、{@code /models/fetch}）：受信来源跳过私网判定。
     *
     * @param trusted {@code source=ENV_SEED}（env 种子行）为 true
     */
    public String checkForOutbound(String baseUrl, boolean trusted) {
        return check(baseUrl, trusted);
    }

    private String check(String baseUrl, boolean trusted) {
        String url = normalise(baseUrl);
        URI uri = parse(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalid("baseUrl 只接受 http/https 绝对地址（当前 scheme = " + uri.getScheme() + "）");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("baseUrl 不允许内嵌凭据（user:pass@）—— 密钥请走 key 字段");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw invalid("baseUrl 缺少主机名");
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            throw invalid("baseUrl 端口越界（" + uri.getPort() + "）");
        }
        boolean metadataHost = isMetadataHost(host);
        if (this.properties.getConfig().isAllowPrivateEndpoints() || trusted) {
            if (metadataHost) {
                // 元数据主机名连"放行私网"都不该放：它不是用户的网关，只可能是 SSRF 的靶子
                throw blocked("baseUrl 指向云元数据主机（" + host + "）：任何配置下都不放行");
            }
            return url;
        }
        if (metadataHost) {
            throw blocked("baseUrl 指向云元数据主机（" + host + "）：任何配置下都不放行");
        }
        InetAddress[] addresses = resolve(host);
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw blocked("baseUrl 解析到私网/回环/保留地址（" + host + " → " + address.getHostAddress()
                        + "）：默认禁止（如果这是内网自建网关，请显式设 app.ai.config.allow-private-endpoints=true）");
            }
        }
        return url;
    }

    /**
     * 归一化：去空白 + 去尾部斜杠。
     *
     * <p>
     * 尾部斜杠不归一化会让 {@code baseUrl + modelsPath} 变成 {@code https://h//models}
     * （部分网关按双斜杠判 404）。
     */
    private static String normalise(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw invalid("baseUrl 必填");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw invalid("baseUrl 过长（>" + MAX_URL_LENGTH + " 字符）");
        }
        return url;
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        }
        catch (Exception ex) {
            throw invalid("baseUrl 不是合法 URL：" + ex.getClass().getSimpleName());
        }
        if (uri.getScheme() == null) {
            throw invalid("baseUrl 缺少 scheme（需要 http:// 或 https://）");
        }
        return uri;
    }

    private static boolean isMetadataHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String candidate : METADATA_HOSTS) {
            if (lower.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress[] resolve(String host) {
        String literal = host;
        // URI#getHost 对 IPv6 字面量保留方括号（"[::1]"），InetAddress 不认
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        try {
            return InetAddress.getAllByName(literal);
        }
        catch (UnknownHostException ex) {
            // 解析不出来 ⇒ 连接不上 ⇒ 交给探活报 UNREACHABLE（见类注"已知限制"）
            log.debug("URL 闸：主机名当前无法解析（不判脏值）host={}", host);
            return new InetAddress[0];
        }
    }

    /** 私网/回环/保留地址判定（IPv4 + IPv6）。 */
    static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10（CGNAT，云厂商内网常用）
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 192.0.0.0/24（IETF 协议保留）
            return first == 192 && second == 0 && (bytes[2] & 0xFF) == 0;
        }
        if (bytes.length == 16) {
            // fc00::/7（IPv6 ULA，JDK 没有现成判定）
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    private static GuardException invalid(String message) {
        return new GuardException(CODE_INVALID_BASE_URL, message);
    }

    private static GuardException blocked(String message) {
        return new GuardException(CODE_BLOCKED_PRIVATE_ENDPOINT, message);
    }

    /** 闸的拒绝面：只带稳定 code（控制器按它映射 HTTP 状态与前端文案）。 */
    public static class GuardException extends RuntimeException {

        private final String code;

        public GuardException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return this.code;
        }
    }
}
