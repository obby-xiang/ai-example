package com.example.configmgr.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端来源 IP 的解析（安全防护专项批① 闸门 3 的"IP 桶"取数点）。
 *
 * <h2>问题</h2>
 * {@code RemoteAddr} 在**直连**时就是客户端地址；但一旦前面有 LB/网关，它变成 LB 的地址 ⇒
 * 全站所有用户共用同一个 {@code ai:rate:ip:<LB-IP>} 桶（60/分），一个用户刷满即全员被拒。
 * 而反过来无条件读 {@code X-Forwarded-For}（XFF）又不行：那是**客户端可自填**的头，
 * 攻击者每次换一个假 IP 就绕过限流、还能污染审计来源。
 *
 * <h2>口径：只信任"可信代理网段"发来的 XFF</h2>
 * <ol>
 * <li>{@code app.web.trusted-proxies} 为空（默认）⇒ 只回 {@code RemoteAddr}，一个头都不看；</li>
 * <li>非空且 {@code RemoteAddr} **不在**网段内 ⇒ 只回 {@code RemoteAddr}（直连客户端自填的 XFF 一律忽略）；</li>
 * <li>非空且 {@code RemoteAddr} 在网段内 ⇒ 从 XFF **右往左**跳过可信代理，取第一个不可信地址
 * （标准代理链解析：最右侧是离我们最近一跳，左侧才可能是真实客户端）；</li>
 * <li>XFF 里出现非法字面量（域名、乱码）⇒ 整条头作废，回落到 {@code RemoteAddr}
 * ——宁可把这一批请求记到 LB 的桶上，也不按客户端自述的字符串建桶。</li>
 * </ol>
 *
 * <h2>为什么不解析 {@code Forwarded}</h2>
 * RFC 7239 的 {@code Forwarded} 头在本仓部署面没有使用者（nginx/常见 LB 默认发的都是 XFF）。
 * 上代理时**在代理层覆写** XFF（{@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for}）
 * 是标准做法；本类只负责"信任谁发来的头"这一半。
 */
@Slf4j
@Component
public class ClientIpResolver {

	/** 标准代理链头名。 */
	public static final String X_FORWARDED_FOR = "X-Forwarded-For";

	/** 单条链最多看几跳（超出部分忽略：异常长链是攻击面，不是拓扑）。 */
	private static final int MAX_CHAIN_LENGTH = 16;

	/** RemoteAddr 缺失时的占位（与改造前 AiController 的口径逐字一致）。 */
	public static final String UNKNOWN = "unknown";

	private final List<Cidr> trusted;

	public ClientIpResolver(ClientIpProperties properties) {
		List<Cidr> parsed = new ArrayList<>();
		for (String raw : properties.getTrustedProxies()) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			Cidr cidr = Cidr.parse(raw.trim());
			if (cidr == null) {
				throw new IllegalStateException("app.web.trusted-proxies 含非法网段/地址：'" + raw
						+ "'（只接受 IPv4/IPv6 字面量或 CIDR，例 10.0.0.0/8、192.168.1.7、fd00::/8）");
			}
			parsed.add(cidr);
		}
		this.trusted = List.copyOf(parsed);
	}

	/** 便于测试/日志的信任网段数量。 */
	public int trustedProxyCount() {
		return this.trusted.size();
	}

	/**
	 * 解析请求来源 IP。
	 *
	 * @return 可信链上的客户端地址；取不到时 {@link #UNKNOWN}。永不返回 {@code null}。
	 */
	public String resolve(HttpServletRequest request) {
		if (request == null) {
			return UNKNOWN;
		}
		String remote = normalize(request.getRemoteAddr());
		if (remote.isEmpty()) {
			remote = UNKNOWN;
		}
		if (this.trusted.isEmpty() || !isTrusted(remote)) {
			return remote;
		}
		List<String> chain = parseChain(request.getHeader(X_FORWARDED_FOR));
		if (chain.isEmpty()) {
			return remote;
		}
		String candidate = remote;
		for (int i = chain.size() - 1; i >= 0; i--) {
			if (!isTrusted(candidate)) {
				break;
			}
			candidate = chain.get(i);
		}
		return candidate.isEmpty() ? remote : candidate;
	}

	/** XFF → 地址列表；任一 token 非 IP 字面量即整条作废（返回空）。 */
	private static List<String> parseChain(String header) {
		if (header == null || header.isBlank()) {
			return List.of();
		}
		String[] tokens = header.split(",");
		if (tokens.length > MAX_CHAIN_LENGTH) {
			log.debug("X-Forwarded-For 链过长（{} 跳），按不可信处理", tokens.length);
			return List.of();
		}
		List<String> chain = new ArrayList<>(tokens.length);
		for (String token : tokens) {
			String address = normalize(token);
			if (!isIpLiteral(address)) {
				log.debug("X-Forwarded-For 含非 IP 字面量，整条头作废");
				return List.of();
			}
			chain.add(address);
		}
		return chain;
	}

	private boolean isTrusted(String address) {
		byte[] bytes = ipBytes(address);
		if (bytes == null) {
			return false;
		}
		for (Cidr cidr : this.trusted) {
			if (cidr.matches(bytes)) {
				return true;
			}
		}
		return false;
	}

	/** 去空白、去 {@code [IPv6]} 方括号、去 {@code ip:port} 的端口后缀，并把 IPv4-mapped IPv6 折回 IPv4 文本。 */
	private static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String value = raw.trim();
		if (value.isEmpty()) {
			return "";
		}
		if (value.startsWith("[")) {
			int end = value.indexOf(']');
			if (end > 0) {
				value = value.substring(1, end);
			}
		}
		else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
			// 恰好一个冒号 ⇒ IPv4:port 形态；IPv6 字面量至少两个冒号，不会被误切
			String port = value.substring(value.indexOf(':') + 1);
			if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
				value = value.substring(0, value.indexOf(':'));
			}
		}
		byte[] bytes = ipBytes(value);
		if (bytes == null) {
			return value;
		}
		byte[] v4 = toIpv4(bytes);
		return v4 == null ? value : formatIpv4(v4);
	}

	private static boolean isIpLiteral(String value) {
		return ipBytes(value) != null;
	}

	/** IP 字面量 → 字节（IPv4 4 字节 / IPv6 16 字节）；**不是字面量就返回 null**（绝不触发 DNS）。 */
	static byte[] ipBytes(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		byte[] v4 = parseIpv4(value);
		if (v4 != null) {
			return v4;
		}
		if (value.indexOf(':') < 0 || !value.matches("[0-9A-Fa-f:.]+")) {
			return null;
		}
		try {
			byte[] bytes = InetAddress.getByName(value).getAddress();
			if (bytes.length == 4) {
				return bytes;
			}
			if (bytes.length == 16) {
				return bytes;
			}
			return null;
		}
		catch (UnknownHostException ex) {
			return null;
		}
	}

	/** 严格的 IPv4 字面量解析（每段 0–255，不允许多余字符/前导 {@code +}）。 */
	private static byte[] parseIpv4(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) {
			return null;
		}
		byte[] bytes = new byte[4];
		for (int i = 0; i < 4; i++) {
			String part = parts[i];
			if (part.isEmpty() || part.length() > 3) {
				return null;
			}
			int octet = 0;
			for (int c = 0; c < part.length(); c++) {
				char ch = part.charAt(c);
				if (ch < '0' || ch > '9') {
					return null;
				}
				octet = octet * 10 + (ch - '0');
			}
			if (octet > 255) {
				return null;
			}
			bytes[i] = (byte) octet;
		}
		return bytes;
	}

	/** {@code ::ffff:a.b.c.d}（IPv4-mapped）折成 4 字节，其余 IPv6 返回 null。 */
	private static byte[] toIpv4(byte[] bytes) {
		if (bytes.length != 16) {
			return bytes.length == 4 ? bytes : null;
		}
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0) {
				return null;
			}
		}
		if ((bytes[10] & 0xff) != 0xff || (bytes[11] & 0xff) != 0xff) {
			return null;
		}
		return new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
	}

	private static String formatIpv4(byte[] bytes) {
		return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
	}

	/** 一个网段（{@code 10.0.0.0/8} / 单 IP / IPv6 CIDR）。 */
	record Cidr(byte[] network, int prefixBits) {

		static Cidr parse(String value) {
			String address = value;
			int prefix = -1;
			int slash = value.indexOf('/');
			if (slash >= 0) {
				address = value.substring(0, slash);
				String bits = value.substring(slash + 1);
				if (bits.isEmpty() || bits.length() > 3) {
					return null;
				}
				int parsed = 0;
				for (int i = 0; i < bits.length(); i++) {
					char ch = bits.charAt(i);
					if (ch < '0' || ch > '9') {
						return null;
					}
					parsed = parsed * 10 + (ch - '0');
				}
				prefix = parsed;
			}
			byte[] bytes = ipBytes(address);
			if (bytes == null) {
				return null;
			}
			int maxBits = bytes.length * 8;
			if (prefix < 0) {
				prefix = maxBits;
			}
			if (prefix > maxBits) {
				return null;
			}
			return new Cidr(bytes, prefix);
		}

		boolean matches(byte[] candidate) {
			byte[] normalized = toIpv4(candidate);
			byte[] effective = normalized == null ? candidate : normalized;
			if (effective.length != this.network.length) {
				return false;
			}
			int fullBytes = this.prefixBits / 8;
			int remainder = this.prefixBits % 8;
			for (int i = 0; i < fullBytes; i++) {
				if (effective[i] != this.network[i]) {
					return false;
				}
			}
			if (remainder == 0) {
				return true;
			}
			int mask = 0xff << (8 - remainder);
			return (effective[fullBytes] & mask) == (this.network[fullBytes] & mask);
		}

	}

}
