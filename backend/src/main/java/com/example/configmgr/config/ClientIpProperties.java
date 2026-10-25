package com.example.configmgr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code app.web.*}：HTTP 层的信任边界配置（目前只有可信代理网段）。
 *
 * <p>为什么单独成类而不是塞进 {@code AppProperties}：{@code AppProperties} 是基座业务配置
 * （job/file/task），本组是**入站流量的信任边界**，改它会改变"谁能代表客户端"的安全语义；
 * 分开后 {@code rg app.web} 就能把它的全部消费点收在一处。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.web")
public class ClientIpProperties {

	/**
	 * 可信代理网段（CIDR 或单个 IP，IPv4/IPv6 均可；例 {@code 10.0.0.0/8,192.168.1.7}）。
	 *
	 * <p><b>空列表 = 不信任任何 {@code X-Forwarded-For}</b>（默认，与改造前逐字一致）：
	 * 只有直连客户端时，{@code RemoteAddr} 就是真实来源。
	 *
	 * <p>非空时，**仅当 {@code RemoteAddr} 落在这些网段内**才解析 {@code X-Forwarded-For}
	 * （见 {@link ClientIpResolver}）。这个区分是必须的：{@code X-Forwarded-For} 是客户端可自填的
	 * 请求头，无条件信任它等于让调用方自选限流桶 / 自选审计来源。
	 *
	 * <p>填错（非 CIDR/IP 字面量）在启动期 fail-fast —— 静默忽略会让"以为配了信任网段、
	 * 实际全站仍按 LB 单 IP 计数"的故障无声存在。
	 */
	private List<String> trustedProxies = new ArrayList<>();

}
