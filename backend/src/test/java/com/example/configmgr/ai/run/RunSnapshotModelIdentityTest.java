package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批① 的"模型身份 additive 字段"：{@link RunSnapshot#getProviderId()}/{@link RunSnapshot#getModelId()}
 * 由 {@code RunStore.create(...)} 从第一笔写就带上，并通过 {@code asMap()} 透出（取证口径与 runEpoch 一致）。
 *
 * <p>
 * <b>证红</b>：把 {@code create} 里的 {@code setProviderId/setModelId} 去掉（或从 asMap 移除两键）⇒ 红。
 * 旧快照（没有这两个字段的 JSON）反序列化后为 {@code null}，不影响续跑判定 —— 批③ 才改成"按快照取模型"。
 */
class RunSnapshotModelIdentityTest {

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	@Test
	void createPersistsTheModelIdentityOfTheTurn() {
		RunSnapshot snapshot = this.store.create("run-identity", "s-identity", AiContext.empty(),
				List.of(new UserMessage("你好")), this.codec, 42L, "deepseek-flash");

		RunSnapshot stored = this.store.get("run-identity");
		assertThat(stored.getProviderId()).isEqualTo(42L);
		assertThat(stored.getModelId()).isEqualTo("deepseek-flash");
		assertThat(snapshot.getModelId()).isEqualTo("deepseek-flash");
	}

	@Test
	void asMapExposesTheIdentityForEvidence() {
		RunSnapshot snapshot = this.store.create("run-identity-2", "s-identity", AiContext.empty(),
				List.of(new UserMessage("你好")), this.codec, 7L, "deepseek-pro");

		Map<String, Object> view = snapshot.asMap();
		assertThat(view).containsEntry("providerId", 7L).containsEntry("modelId", "deepseek-pro");
	}

	@Test
	void oldCreateSignatureStillWorksAndLeavesTheIdentityNull() {
		RunSnapshot snapshot = this.store.create("run-legacy", "s-legacy", AiContext.empty(),
				List.of(new UserMessage("你好")), this.codec);

		assertThat(snapshot.getProviderId()).isNull();
		assertThat(snapshot.getModelId()).isNull();
		assertThat(snapshot.asMap()).containsKey("providerId").containsKey("modelId");
	}

}
