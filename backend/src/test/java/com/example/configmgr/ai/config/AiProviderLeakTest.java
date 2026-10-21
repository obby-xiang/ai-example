package com.example.configmgr.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.db.AiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3（泄密面）：实体 {@code toString()} 与运行日志里<b>不得</b>出现 key 明文或密文。
 *
 * <p>
 * <b>证红</b>：去掉 {@link AiProvider#getKeyCipher()} 上的 {@code @ToString.Exclude} ⇒
 * 第一段（{@code @Data} 生成的 {@code toString}）立刻把 {@code v1.k1...} 打出来 ⇒ 红。
 * 第二段锁的是"解析失败时的 WARN 也只许出现 provider code 与稳定 code"。
 */
class AiProviderLeakTest {

	private static final String SECRET = "leak-probe-value-fedcba9876543210";

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final AiKeyCipher cipher = new AiKeyCipher(AiConfigTestFixture.KEK_A);

	private ListAppender<ILoggingEvent> appender;

	private Logger serviceLogger;

	@BeforeEach
	void attachAppender() {
		this.serviceLogger = (Logger) LoggerFactory.getLogger(AiConfigService.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.serviceLogger.addAppender(this.appender);
	}

	@AfterEach
	void detachAppender() {
		this.serviceLogger.detachAppender(this.appender);
	}

	@Test
	void providerToStringNeverCarriesTheCipherOrThePlaintext() {
		String envelope = this.cipher.encrypt(SECRET, "deepseek", AiProvider.KEY_REF_DB);
		AiProvider provider = this.fixture.addDbProvider("deepseek", "http://x", envelope);
		provider.setKeyFingerprint(this.cipher.fingerprint(SECRET));

		String rendered = provider.toString();
		assertThat(rendered).contains("deepseek").doesNotContain(envelope).doesNotContain(SECRET)
			.doesNotContain("v1.k1.");
		// M1（复审）：指纹同样是 key 派生材料（可用来离线比对两个库里的 key 是否同一个），
		// 也不许进 toString —— 只允许出现在 key_fingerprint 列与运行时缓存键里。
		assertThat(rendered).doesNotContain(provider.getKeyFingerprint());
		assertThat(provider.getKeyCipher()).isEqualTo(envelope);
		assertThat(provider.getKeyFingerprint()).hasSize(16);
	}

	@Test
	void unreadableKeyWarnCarriesOnlyTheStableCode() {
		// 用 KEK_B 加密，服务侧持有 KEK_A ⇒ 解不开 ⇒ 走 KEY_UNREADABLE 的 WARN（节流一次）
		String envelope = new AiKeyCipher(AiConfigTestFixture.KEK_B).encrypt(SECRET, "deepseek",
				AiProvider.KEY_REF_DB);
		this.fixture.addDbProvider("deepseek", "http://x", envelope);
		AiConfigService service = this.fixture.service(AiConfigTestFixture.KEK_A);

		AiConfigService.Availability availability = service.availability();

		assertThat(availability.available()).isFalse();
		assertThat(availability.code()).isEqualTo(AiKeyCipher.CODE_KEY_UNREADABLE);
		List<String> messages = this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).isNotEmpty();
		assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain(envelope)
			.doesNotContain(SECRET)
			.doesNotContain("v1.k1."));
		assertThat(this.appender.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
	}

	@Test
	void diagnosticViewAndAvailabilityReasonNeverCarryKeyMaterial() {
		String envelope = this.cipher.encrypt(SECRET, "deepseek", AiProvider.KEY_REF_DB);
		this.fixture.addDbProvider("deepseek", "http://x", envelope);
		AiConfigService service = this.fixture.service();

		String rendered = String.valueOf(service.describeProviders()) + service.availability().reason();
		assertThat(rendered).doesNotContain(SECRET).doesNotContain(envelope).doesNotContain("v1.k1.");
	}

}
