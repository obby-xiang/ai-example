package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>I-3</b>（批③ 首项修复）：设置页改不动 env 种子行的问题。
 *
 * <h2>缺陷现场</h2>
 * 设置页一次保存会带上<b>整张卡片</b>（displayName / baseUrl / 路径），于是即使只改了显示名，
 * {@code baseUrl}（env 种子的本地桩 {@code http://127.0.0.1:18399}）也会被重新过一遍 URL 闸 ——
 * 闸默认禁私网/回环 ⇒ 400 {@code BLOCKED_PRIVATE_ENDPOINT} ⇒ 整行"改不动"。
 *
 * <h2>修复口径（两条一起做）</h2>
 * <ol>
 * <li>后端：{@code baseUrl} 与库内现值<b>相同</b>（或未提供）⇒ 不重跑闸 —— 闸拦的是"新引入的地址"，
 *     不是复审用户没动过的既有地址；</li>
 * <li>前端：提交体只带<b>变化字段</b>（{@code ai-settings-view.spec.ts} 的另一半）。</li>
 * </ol>
 *
 * <h2>证红方式</h2>
 * 把 {@code updateProvider} 里 {@code requestedBaseUrl.equals(existing.getBaseUrl())} 这个短路删掉 ⇒
 * {@code unchangedPrivateBaseUrlSkipsTheGuard} 立刻 400 红。反例 {@code changedPrivateBaseUrlIsStillBlocked}
 * 保证"闸没有被整体关掉"。
 */
class AiConfigWriterGuardTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> redisOps = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private AiConfigWriter writer() {
		when(this.redis.opsForValue()).thenReturn(this.redisOps);
		when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		this.fixture.properties.getConfig().setAllowPrivateEndpoints(false);
		return new AiConfigWriter(this.fixture.providerRepository, this.fixture.modelRepository,
				this.fixture.settingRepository, this.fixture.auditRepository, new AiKeyCipher(AiConfigTestFixture.KEK_A),
				new AiEndpointGuard(this.fixture.properties), this.redis, this.transactionManager,
				this.fixture.service(), new ObjectMapper());
	}

	@Test
	void unchangedPrivateBaseUrlSkipsTheGuard() {
		AiProvider seeded = this.fixture.addEnvProvider("deepseek", "http://127.0.0.1:18399");
		AiConfigWriter writer = writer();

		// 只改显示名：baseUrl 原样带回（设置页的现实行为）
		AiProvider updated = writer.updateProvider(seeded.getId(), new AiConfigWriter.ProviderCommand(null,
				"我的本地桩", null, "http://127.0.0.1:18399", null, null, null, null, null, null));

		assertThat(updated.getDisplayName()).isEqualTo("我的本地桩");
		assertThat(updated.getBaseUrl()).as("地址没变，闸不该介入").isEqualTo("http://127.0.0.1:18399");
		assertThat(updated.getSource()).as("用户一改即脱离种子（I-1）").isEqualTo(AiProvider.SOURCE_DB);
	}

	@Test
	void omittedBaseUrlAlsoSkipsTheGuard() {
		AiProvider seeded = this.fixture.addEnvProvider("deepseek", "http://127.0.0.1:18399");

		AiProvider updated = writer().updateProvider(seeded.getId(),
				new AiConfigWriter.ProviderCommand(null, "改名", null, null, null, null, null, null, null, null));

		assertThat(updated.getDisplayName()).isEqualTo("改名");
		assertThat(updated.getBaseUrl()).isEqualTo("http://127.0.0.1:18399");
	}

	@Test
	void changedPrivateBaseUrlIsStillBlocked() {
		AiProvider seeded = this.fixture.addEnvProvider("deepseek", "http://127.0.0.1:18399");

		// 改了地址 ⇒ 必须过闸；即便新旧都是私网，也不能"因为原来就是私网"而放行新的私网目标
		assertThatThrownBy(() -> writer().updateProvider(seeded.getId(),
				new AiConfigWriter.ProviderCommand(null, null, null, "http://192.168.1.10:8080", null, null, null,
						null, null, null)))
			.isInstanceOf(AiConfigException.class)
			.hasFieldOrPropertyWithValue("code", "BLOCKED_PRIVATE_ENDPOINT");
	}

}
