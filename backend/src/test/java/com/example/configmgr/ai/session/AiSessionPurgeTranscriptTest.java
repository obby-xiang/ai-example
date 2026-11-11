package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.ai.form.FormAttachmentStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2：会话销毁对 transcript 的<b>精确键 + 类型校验</b>删除。
 *
 * <p>当前写口是一条 pipeline 里的 {@code PURGE_TYPE_DEL} 脚本（EXISTS → TYPE 比对 → DEL），
 * 回包按批顺序给出：{@code 1} = 已删、{@code 0} = 不存在、{@code -1} = 键类型不符。
 * transcript 的类型不符按<b>可重试错误</b>处理（历史未删净），不再有"前缀碰撞静默跳过"。
 */
class AiSessionPurgeTranscriptTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiSessionPurgeService service = new AiSessionPurgeService(this.redis, mock(RunStore.class),
			mock(SessionGate.class), mock(CancellationRegistry.class), mock(ChatMemoryRepository.class),
			mock(FormAttachmentStore.class), mock(FileStorageService.class), mock(SessionPurgeDatabase.class),
			mock(SessionPurgeGuard.class), mock(SessionPurgePendingStore.class), new AiProperties(), Duration.ZERO);

	@Test
	void deletesTranscriptDataAndMarkersByExactKeyAndType() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			String data = ConversationTranscriptStore.dataKey("s-1");
			String order = ConversationTranscriptStore.orderKey("s-1");
			String meta = ConversationTranscriptStore.metaKey("s-1");
			String truncated = ConversationTranscriptStore.truncatedKey("s-1");
			// 四个键都存在且类型正确 ⇒ 脚本逐个 DEL（回包 1）。
			when(this.redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(1L, 1L, 1L, 1L));
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();

			AiSessionPurgeService.TranscriptView view = this.service.deleteTranscript("s-1", true, 7L, errors,
					warnings);

			assertThat(view).isEqualTo(new AiSessionPurgeService.TranscriptView(true, 7L, true));
			// 精确键：删后复核逐个 hasKey（替身默认 false = 无残留）。
			verify(this.redis).hasKey(data);
			verify(this.redis).hasKey(order);
			verify(this.redis).hasKey(meta);
			verify(this.redis).hasKey(truncated);
			assertThat(errors).isEmpty();
			assertThat(warnings).isEmpty();
		}
	}

	@Test
	void typeMismatchIsReportedAsRetryableInsteadOfDeleted() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			String data = ConversationTranscriptStore.dataKey("s-1");
			// 首个键类型不符（-1），其后不存在（0）：历史未删净 ⇒ 可重试错误，不得报成功。
			when(this.redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(-1L, 0L, 0L, 0L));
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();

			AiSessionPurgeService.TranscriptView view = this.service.deleteTranscript("s-1", true, 1L, errors,
					warnings);

			assertThat(view.deleted()).as("一个键都没删成 ⇒ 不得报已删除").isFalse();
			assertThat(errors).singleElement().asString().contains("键类型不符").contains("transcript");
			assertThat(warnings).as("transcript 的类型不符是可重试错误，不是可静默跳过的前缀碰撞").isEmpty();
			verify(this.redis).hasKey(data);
		}
	}

}
