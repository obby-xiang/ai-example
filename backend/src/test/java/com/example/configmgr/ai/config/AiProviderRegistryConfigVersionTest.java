package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.config.db.AiSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * <b>I2 多实例默认参数热生效</b>：{@code ai_setting} 的默认参数（temperature/topP/maxTokens）烙在
 * {@link ProviderRuntime#options()} 上，而运行态按 RuntimeKey 缓存。写路径的 {@code evictAll()} 只清
 * <b>写那个实例</b>的缓存 —— 其他实例的缓存条目会一直带着旧 options，直到进程重启或运维手动清缓存。
 *
 * <h2>修法（版本闸，不新增轮询）</h2>
 * 注册表在 {@code current/runtimeFor/forRequest/forFrozenRequest} 入口比较
 * {@code configService.current().version()} 与 {@code lastEvictedConfigVersion}：变化即清缓存。
 * 清理与构建插入<b>同受 buildLock 保护</b>，且构建结束前再比一次版本：
 * 构建期间配置又变了 ⇒ 本次实例<b>不入缓存</b>（不把旧 options 塞回刚清空的表里）。
 *
 * <h2>不中断在飞 run</h2>
 * 清缓存只影响"下一次取用"：正在流的请求手里已持有 {@link ProviderRuntime}（ChatModel/ChatClient），
 * 清表不改变它的引用与 options —— 见 {@link #evictedCacheDoesNotDisturbAnInFlightRuntime()}。
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>去掉入口版本比较（回到"只有写实例 evictAll"）⇒ 第一例红（另一实例仍是旧温度）；</li>
 * <li>构建结束后不再比较版本 ⇒ 第二例红（旧 options 被插入缓存，runtimeCount=1）；</li>
 * <li>evictAll 不进 buildLock ⇒ 第三例的并发收敛断言可能读到旧条目（红）。</li>
 * </ul>
 */
class AiProviderRegistryConfigVersionTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private AiProvider provider;

	@BeforeEach
	void setUp() {
		this.provider = this.fixture.addEnvProvider("provider-a", "http://placeholder");
		this.fixture.addModel(this.provider.getId(), "model-a", true);
		this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, "0.2");
	}

	/** 另一个"实例"（独立缓存）在 DB 版本 +1 后，下一轮取用即按新默认参数重建。 */
	@Test
	void otherInstancesEvictOldDefaultOptionsAfterTheConfigVersionBumps() {
		AiConfigService service = this.fixture.service();
		AiProviderRegistry writerInstance = this.fixture.registry(service);
		AiProviderRegistry otherInstance = this.fixture.registry(service);

		ProviderRuntime writerBefore = writerInstance.current();
		ProviderRuntime otherBefore = otherInstance.current();
		assertThat(writerBefore.options().getTemperature()).isEqualTo(0.2d);
		assertThat(otherBefore.options().getTemperature()).isEqualTo(0.2d);

		// "另一个实例"写了默认参数：数据行 + config.version+1（写实例的 evictAll 清不到本实例）
		this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, "0.7");
		this.fixture.settings.put(AiSetting.CONFIG_VERSION, "2");

		ProviderRuntime otherAfter = otherInstance.current();
		assertThat(otherAfter.options().getTemperature())
			.as("非写实例也必须按新默认参数重建（否则设置页显示已保存、实际仍是旧值）")
			.isEqualTo(0.7d);
		assertThat(otherAfter.chatModel()).as("确实重建了实例（不是复用了旧条目）")
			.isNotSameAs(otherBefore.chatModel());
		assertThat(otherInstance.runtimeCount()).isEqualTo(1);

		assertThat(writerInstance.current().options().getTemperature()).isEqualTo(0.7d);
	}

	/**
	 * 构建期间配置又变：本次构建出的实例<b>不得入缓存</b>（否则"刚清空又被旧 options 塞回去"）。
	 *
	 * <p>白盒缝：用 spy 在 {@code buildOptions} 第一次读 {@code setting(...)} 之后推进版本 —— 于是
	 * "进入构建时是 v1、构建结束前已是 v2"，正是 build 与 evict 的竞态形态。
	 */
	@Test
	void staleBuildIsNotPutBackIntoTheCacheAfterAnEviction() {
		AiConfigService service = spy(this.fixture.service());
		AtomicBoolean flipped = new AtomicBoolean();
		doAnswer(invocation -> {
			Optional<String> value = (Optional<String>) invocation.callRealMethod();
			if (!flipped.getAndSet(true)) {
				this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, "0.7");
				this.fixture.settings.put(AiSetting.CONFIG_VERSION, "2");
			}
			return value;
		}).when(service).setting(anyString());
		AiProviderRegistry registry = this.fixture.registry(service);
		AiConfigService.ResolvedProvider resolved = service.resolveChatTarget("model-a").orElseThrow().provider();

		ProviderRuntime built = registry.runtimeFor(resolved);

		assertThat(built.options().getTemperature()).as("本次构建用的是进入时的快照（v1）").isEqualTo(0.2d);
		assertThat(registry.runtimeCount()).as("构建期间版本变了 ⇒ 不把旧 options 塞回缓存").isZero();

		ProviderRuntime next = registry.current();
		assertThat(next.options().getTemperature()).isEqualTo(0.7d);
		assertThat(next.chatModel()).as("下一轮按新版本重建").isNotSameAs(built.chatModel());
		assertThat(registry.runtimeCount()).isEqualTo(1);
	}

	/** 并发：一边取用/构建，一边推进配置版本 —— 最终缓存必须收敛到最后一版默认参数。 */
	@Test
	void concurrentBuildsAndVersionChangesConvergeToTheLatestOptions() throws Exception {
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		ExecutorService pool = Executors.newFixedThreadPool(6);
		AtomicBoolean stop = new AtomicBoolean();
		List<Future<?>> readers = new ArrayList<>();
		try {
			for (int i = 0; i < 6; i++) {
				readers.add(pool.submit(() -> {
					while (!stop.get()) {
						try {
							registry.current();
						}
						catch (RuntimeException ignored) {
							// 配置切换窗口里的窄异常与本用例无关（断言看最终收敛）
						}
					}
					return null;
				}));
			}
			for (int round = 2; round <= 5; round++) {
				this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, String.valueOf(round / 10.0d));
				this.fixture.settings.put(AiSetting.CONFIG_VERSION, String.valueOf(round));
				Thread.sleep(20L);
			}
		}
		finally {
			stop.set(true);
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, "0.9");
		this.fixture.settings.put(AiSetting.CONFIG_VERSION, "99");

		assertThat(registry.current().options().getTemperature())
			.as("并发构建与淘汰交错后不得留下旧 options").isEqualTo(0.9d);
		assertThat(registry.runtimeCount()).isEqualTo(1);
	}

	/** 清缓存不打断在飞 run：已取到的运行态引用与 options 保持不变；下一轮用新实例。 */
	@Test
	void evictedCacheDoesNotDisturbAnInFlightRuntime() {
		AiConfigService service = this.fixture.service();
		AiProviderRegistry registry = this.fixture.registry(service);
		ProviderRuntime inFlight = registry.current();
		assertThat(inFlight.options().getTemperature()).isEqualTo(0.2d);

		this.fixture.settings.put(AiSetting.DEFAULT_TEMPERATURE, "0.7");
		this.fixture.settings.put(AiSetting.CONFIG_VERSION, "2");

		ProviderRuntime next = registry.current();

		assertThat(inFlight.options().getTemperature())
			.as("正在流的一轮持有自己的实例，清缓存不改它的引用/options").isEqualTo(0.2d);
		assertThat(next.options().getTemperature()).isEqualTo(0.7d);
		assertThat(next.chatModel()).isNotSameAs(inFlight.chatModel());
	}

}
