package com.example.configmgr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryCustomizer;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 监听面收窄（I-1）：后端<b>默认只监听回环</b>，网段内的其它主机连不上。
 *
 * <h2>为什么是行为测试而不是"读一行 yml"</h2>
 * I-1 的漏洞不是"配置写错了"，而是"配置没写" —— 嵌入式 Tomcat 在 {@code server.address}
 * 缺省时绑 {@code 0.0.0.0}（全部网卡），于是同网段的任意客户端可 {@code PUT /api/ai/providers/{id}}
 * 把 {@code baseUrl} 指到自控主机、再 {@code POST /providers/{id}/test}，把既有的 Bearer key 送出去。
 * 所以这里做两件事：
 * <ol>
 * <li><b>绑定锁</b>：{@code application.yml} 经 Spring 的 Binder 真绑到 {@link ServerProperties} 上，
 *     {@code server.address} 必须是回环字面值 —— 键缺失时它是 {@code null}（红），
 *     而 {@code null} 的含义正是"绑全部网卡"；</li>
 * <li><b>行为锁</b>：把这组属性交给 Spring Boot 自己的
 *     {@link ServletWebServerFactoryCustomizer} 去定制 Tomcat，起一个真容器，然后<b>真连</b>：
 *     回环地址可达、本机非回环 IPv4 不可达。后者就是"同网段其它主机不可达"的同构证明
 *     （TCP 层在路由之前就拒了，与本机有没有 {@code /api/**} 映射无关）。</li>
 * </ol>
 *
 * <p>测试自己一律用 {@code server.port=0}（随机空闲端口）：常开服务的 8081 严禁被本测试占用/顶替。
 * yml 里 {@code server.port} 仍断言为 8081 —— 本批不动端口，只收窄地址。
 */
class ServerBindAddressConfigTest {

	/** 缺省监听地址：只回环。对外暴露**必须显式覆盖**（{@code --server.address=0.0.0.0} 或网关反代）。 */
	private static final String LOOPBACK = "127.0.0.1";

	@Test
	void yamlBindsTheListenerToLoopbackOnly() throws IOException {
		ServerProperties properties = serverPropertiesFromApplicationYaml();

		assertThat(properties.getAddress())
				.as("I-1：server.address 缺省 ⇒ Tomcat 绑 0.0.0.0（全部网卡）")
				.isEqualTo(InetAddress.getByName(LOOPBACK));
		assertThat(properties.getAddress().isLoopbackAddress())
				.as("地址必须落在回环段（127.0.0.0/8、::1）")
				.isTrue();
		assertThat(properties.getPort())
				.as("本批只收窄地址，端口仍是 8081（README 端口表与 check-docs 的对齐基线）")
				.isEqualTo(8081);
	}

	/**
	 * 真容器行为锁：yml 的地址经 Spring Boot 的定制器落到 Tomcat 后，
	 * <b>回环可达、本机非回环地址不可达</b>。后者与"同网段客户端不可达"同构 —— 绑定发生在路由之前。
	 */
	@Test
	void embeddedContainerAcceptsLoopbackAndRefusesOtherLocalAddresses() throws Exception {
		ServerProperties properties = serverPropertiesFromApplicationYaml();

		TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
		new ServletWebServerFactoryCustomizer(properties).customize(factory);
		// 定制器会把 yml 的 8081 写回 factory：测试里再强制一次随机端口（绝不碰常开服务）。
		factory.setPort(0);

		WebServer server = factory.getWebServer();
		server.start();
		try {
			int port = server.getPort();
			assertThat(port).as("随机端口已就绪").isPositive();

			assertThat(reachable(InetAddress.getLoopbackAddress(), port))
					.as("回环可达（本机 dev / E2E harness / 浏览器手测都是这一形态）")
					.isTrue();

			InetAddress otherLocal = firstNonLoopbackIpv4();
			if (otherLocal == null) {
				// 单网卡/受限容器里构造不出"非回环本机地址"，负向断言无从谈起：
				// 正向断言已锁住回环可用，绑定语义由 yml 锁保证。
				return;
			}
			assertThat(reachable(otherLocal, port))
					.as("绑在回环上的端口不应接受本机其它地址（%s）——同网段客户端同理", otherLocal.getHostAddress())
					.isFalse();
		}
		finally {
			server.stop();
		}
	}

	// ───────────────────────── 辅助 ─────────────────────────

	/**
	 * 把 {@code src/main/resources/application.yml} 走一遍 Spring 自己的加载/绑定链。
	 *
	 * <p>用 {@link ClassPathResource} 而不是裸读文件：这样"键名写错 / 类型不对 / 文件没进 classpath"
	 * 都会以绑定失败或属性缺失的形式暴露出来，而不是被字符串断言放过。
	 */
	private static ServerProperties serverPropertiesFromApplicationYaml() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		for (PropertySource<?> source : new YamlPropertySourceLoader()
			.load("application", new ClassPathResource("application.yml"))) {
			environment.getPropertySources().addFirst(source);
		}
		return Binder.get(environment)
			.bind("server", Bindable.of(ServerProperties.class))
			.orElseGet(ServerProperties::new);
	}

	private static boolean reachable(InetAddress address, int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 2000);
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	/** 本机第一个非回环 IPv4（取不到返回 {@code null}：容器里常见的单网卡形态）。 */
	private static InetAddress firstNonLoopbackIpv4() throws IOException {
		Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
		while (interfaces != null && interfaces.hasMoreElements()) {
			NetworkInterface candidate = interfaces.nextElement();
			if (!candidate.isUp() || candidate.isLoopback()) {
				continue;
			}
			Enumeration<InetAddress> addresses = candidate.getInetAddresses();
			while (addresses.hasMoreElements()) {
				InetAddress address = addresses.nextElement();
				if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
					return address;
				}
			}
		}
		return null;
	}

}
