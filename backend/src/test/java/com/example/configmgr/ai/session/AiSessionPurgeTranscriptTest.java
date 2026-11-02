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
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2：会话销毁对 transcript 的<b>精确键 + 类型校验</b>删除。
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
			String bytes = ConversationTranscriptStore.bytesKey("s-1");
			String ready = ConversationTranscriptStore.readyKey("s-1");
			String truncated = ConversationTranscriptStore.truncatedKey("s-1");
			when(this.redis.type(data)).thenReturn(DataType.LIST);
			when(this.redis.type(bytes)).thenReturn(DataType.STRING);
			when(this.redis.type(ready)).thenReturn(DataType.STRING);
			when(this.redis.type(truncated)).thenReturn(DataType.STRING);
			when(this.redis.delete(anyString())).thenReturn(true);
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();

			AiSessionPurgeService.TranscriptView view = this.service.deleteTranscript("s-1", true, 7L, errors,
					warnings);

			assertThat(view).isEqualTo(new AiSessionPurgeService.TranscriptView(true, 7L, true));
			verify(this.redis).delete(data);
			verify(this.redis).delete(bytes);
			verify(this.redis).delete(ready);
			verify(this.redis).delete(truncated);
			assertThat(errors).isEmpty();
			assertThat(warnings).isEmpty();
		}
	}

	@Test
	void typeMismatchIsSkippedAsPrefixCollision() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			String data = ConversationTranscriptStore.dataKey("s-1");
			when(this.redis.type(data)).thenReturn(DataType.HASH);
			when(this.redis.type(ConversationTranscriptStore.bytesKey("s-1"))).thenReturn(DataType.NONE);
			when(this.redis.type(ConversationTranscriptStore.readyKey("s-1"))).thenReturn(DataType.NONE);
			when(this.redis.type(ConversationTranscriptStore.truncatedKey("s-1"))).thenReturn(DataType.NONE);
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();

			AiSessionPurgeService.TranscriptView view = this.service.deleteTranscript("s-1", true, 1L, errors,
					warnings);

			assertThat(view.deleted()).isFalse();
			verify(this.redis, never()).delete(data);
			assertThat(warnings).singleElement().asString().contains("前缀碰撞");
			assertThat(errors).isEmpty();
		}
	}

}
