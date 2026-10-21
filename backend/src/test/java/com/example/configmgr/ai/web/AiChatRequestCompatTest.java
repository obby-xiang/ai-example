package com.example.configmgr.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批③ 请求体的<b>双向兼容</b>契约（设计稿 §3.6）。
 *
 * <p>
 * 后端 {@code ObjectMapper} 是裸 {@code new ObjectMapper()}（{@code JacksonConfig}），
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 默认 true ⇒ <b>新前端 + 旧后端 = 400</b>。
 * 因此本类钉死三件事：
 * <ol>
 * <li>旧前端体（三个键）在<b>新后端</b>上照旧反序列化（新字段为 null，走默认解析链）；</li>
 * <li>新体两个可选块逐键落地；</li>
 * <li>{@code params} 的键集<b>恰为</b>三个（record 组件名即 wire 键，改名 = 旧请求 400）。</li>
 * </ol>
 *
 * <p>用裸 {@code ObjectMapper} 而不是 Spring 上下文：要验的正是"同一个 mapper 配置下的行为"。
 */
class AiChatRequestCompatTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void legacyBodyWithoutModelFieldsStillDeserializes() throws Exception {
		AiChatRequest request = this.mapper.readValue("""
				{"sessionId":"s-1","message":"你好","context":{"page":"tasks"}}
				""", AiChatRequest.class);

		assertThat(request.sessionId()).isEqualTo("s-1");
		assertThat(request.message()).isEqualTo("你好");
		assertThat(request.context().page()).isEqualTo("tasks");
		assertThat(request.modelId()).as("旧前端不传 ⇒ null（默认解析链）").isNull();
		assertThat(request.params()).isNull();
	}

	@Test
	void modelIdAndParamsLandOnExactKeys() throws Exception {
		AiChatRequest request = this.mapper.readValue("""
				{"sessionId":"s-1","message":"hi","modelId":"deepseek-pro",
				 "params":{"temperature":0.3,"topP":0.8,"maxTokens":1024}}
				""", AiChatRequest.class);

		assertThat(request.modelId()).isEqualTo("deepseek-pro");
		assertThat(request.params()).isNotNull();
		assertThat(request.params().temperature()).isEqualTo(0.3d);
		assertThat(request.params().topP()).isEqualTo(0.8d);
		assertThat(request.params().maxTokens()).isEqualTo(1024);
	}

	@Test
	void paramsKeySetIsFrozen() {
		List<String> components = Arrays.stream(AiChatRequest.Params.class.getRecordComponents())
			.map(component -> component.getName())
			.toList();

		assertThat(components).as("wire 键 = record 组件名；改名会让旧后端 400")
			.containsExactly("temperature", "topP", "maxTokens");
	}

	@Test
	void threeArgConstructorStaysAvailable() {
		AiChatRequest request = new AiChatRequest("s-2", "hi", null);

		assertThat(request.modelId()).isNull();
		assertThat(request.params()).isNull();
	}

	@Test
	void unknownTopLevelKeyIsStillRejectedWhichIsWhyBackendMustShipFirst() {
		// 口径钉死（不是"希望如此"）：未知键 400。这条同时是部署顺序的书面依据 ——
		// 新前端先上（旧后端）会 400；后端先上则旧前端（不含新键）零影响。
		assertThatThrownByUnknownKey();
	}

	private void assertThatThrownByUnknownKey() {
		try {
			this.mapper.readValue("{\"sessionId\":\"s\",\"message\":\"m\",\"surprise\":1}", AiChatRequest.class);
			org.assertj.core.api.Assertions.fail("未知键必须被判 400（FAIL_ON_UNKNOWN_PROPERTIES 默认 true）");
		}
		catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException ex) {
			assertThat(ex.getPropertyName()).isEqualTo("surprise");
		}
		catch (java.io.IOException ex) {
			throw new AssertionError("期望 UnrecognizedPropertyException", ex);
		}
	}

}
