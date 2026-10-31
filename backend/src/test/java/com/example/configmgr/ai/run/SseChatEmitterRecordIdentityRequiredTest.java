package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写出器在<b>没有身份提示</b>（{@code requestIdentityHint == null}）时的处置：生产装配下不得
 * "永久缓存无身份"并照旧写 legacy 键族。
 *
 * <h2>被钉住的形态</h2>
 * 身份解析的唯一入口是 {@link SseChatEmitter#deliveryIdentityOutcome()}：它按"构造线程的提示身份"
 * 拼读记录键。提示为 {@code null} 时旧实现直接
 * {@code deliveryIdentity = null; identityResolved = true} —— 于是整条写出器被<b>永久</b>钉在
 * "无身份"上，此后每一帧都落进 legacy 键族（{@code ai:xevents:{run:<id>}}）。
 *
 * <p>而本轮真正的帧写在身份键族（{@code ai:xevents:{<t>:<u>:run:<id>}}）。两套键族是两个归档：
 * 重挂回放与跨实例唤醒读的是身份键族 ⇒ 这一轮的帧"发出去了但读不到"，正是本批要消灭的无身份分叉。
 *
 * <h2>本批口径（只作用于生产装配）</h2>
 * <ul>
 * <li><b>不可确认</b>：提示缺失 ⇒ {@code IdentityOutcome(null, readFailure=true)}，由
 *     {@code emit}/{@code drain}/{@code publishWake}/{@code ensureWakeSubscribed} 走既有的
 *     "身份不可确认 ⇒ 本拍明确失败、绝不按 legacy 键写"分支；</li>
 * <li><b>不缓存</b>：{@code identityResolved} 保持 {@code false}，下一拍仍会重新解析（不是"永久无身份"）；</li>
 * <li><b>兼容面不变</b>：非生产装配（轻装配/单测的三参、六参 {@code RunRegistry}）仍走 legacy 命名空间
 *     —— 那里的 legacy 是<b>唯一</b>命名空间，不存在两套归档分叉。</li>
 * </ul>
 */
class SseChatEmitterRecordIdentityRequiredTest {

	private static final String RUN_ID = "run-record-identity-required";

	private static final String SESSION_ID = "s-record-identity-required";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	/**
	 * 生产装配（{@code RunRegistry} 的七参构造 = Spring 唯一入口）+ 构造线程没有身份 ⇒
	 * 身份<b>不可确认</b>：不落 legacy、不缓存、不交付。
	 */
	@Test
	void productionAssemblyWithoutAnIdentityHintFailsClosedAndNeverWritesLegacyKeys() {
		seedRun();
		// 生产装配路径（@Autowired 七参构造），但本拍线程没有任何身份上下文
		assertThat(RequestContextUtils.currentOrNull()).isNull();
		SseChatEmitter out = productionRegistry().of(RUN_ID);

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(out.deliveryIdentityOutcome().readFailure())
			.as("提示缺失必须报成'身份不可确认'（而不是'确认这一轮没有身份'）").isTrue();
		softly.assertThat(out.deliveryIdentityOutcome().identity()).isNull();

		out.delta("身份不可确认的一帧");

		softly.assertThat(readFrames(null)).as("身份不可确认时不得写 legacy 事件键（那是第二套归档）").isEmpty();
		softly.assertThat(issuedSeq(null)).as("legacy 序号键不得被写").isZero();
		softly.assertThat(out.lastIssuedSeq()).as("没有归档就没有交付：这一拍不得占用业务号").isZero();
		softly.assertThat(readFrames(RECORD_IDENTITY)).as("身份不可确认时也不得猜着写进记录身份键族").isEmpty();
		softly.assertAll();
	}

	/** 失败不得被缓存成"永久无身份"：下一拍仍是"不可确认"，不会退化成确认态。 */
	@Test
	void theUnconfirmedOutcomeIsNotCachedAsAConfirmedAbsence() {
		seedRun();
		SseChatEmitter out = productionRegistry().of(RUN_ID);

		out.delta("第一拍");
		SseChatEmitter.IdentityOutcome second = out.deliveryIdentityOutcome();

		assertThat(second.readFailure())
			.as("第一拍没有把 'null' 落定为已解析结果（identityResolved 不得置位）")
			.isTrue();
		assertThat(readFrames(null)).as("两拍都不得写 legacy 事件键").isEmpty();
	}

	/** 兼容面零变化：非生产装配（六参）仍以 legacy 命名空间为唯一命名空间。 */
	@Test
	void lightweightAssemblyKeepsTheDocumentedLegacyNamespace() {
		seedRun();
		SseChatEmitter out = new RunRegistry(this.store, this.mapper, null, RunStoreFrameStore.of(this.store), null,
				null).of(RUN_ID);

		out.delta("轻装配的一帧");

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(out.deliveryIdentityOutcome().readFailure())
			.as("轻装配不是'身份不可确认'：legacy 就是它唯一的命名空间").isFalse();
		softly.assertThat(readFrames(null)).as("轻装配仍照既有口径写 legacy 事件键")
			.extracting(frame -> String.valueOf(frame.get("type")))
			.containsExactly("delta");
		softly.assertAll();
	}

	/** 生产装配注册表：Spring 里唯一的那条七参构造（{@code properties}/{@code frames} 皆为显式注入）。 */
	private RunRegistry productionRegistry() {
		return new RunRegistry(this.store, this.mapper, null, RunStoreFrameStore.of(this.store), null, null, null);
	}

	/** 按给定身份读事件日志（{@code null} = 显式无身份 ⇒ legacy 键）。 */
	private List<Map<String, Object>> readFrames(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return RunStoreFrameStore.of(this.store).readFrom(RUN_ID, 0L, 0);
		}
	}

	private long issuedSeq(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.lastIssuedSeq(RUN_ID);
		}
	}

	private void seedRun() {
		MessageJsonCodec codec = new MessageJsonCodec(this.mapper);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), codec);
		}
	}

}
