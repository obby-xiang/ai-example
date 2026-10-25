package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.ProviderRuntime;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 测试用的「本轮运行态」桩。
 *
 * <p>摘要统一读取本轮的 provider/model/options；用例只关心这三项与（可选的）{@link ChatModel}，
 * 其余 provider 字段填占位值即可 —— 真实的 HTTP/端点解析不在本组契约里。
 */
public final class TestProviderRuntime {

	public static final String MODEL = "turn-model";

	private TestProviderRuntime() {
	}

	public static ProviderRuntime runtime() {
		return runtime(MODEL, null);
	}

	public static ProviderRuntime runtime(String model, ChatModel chatModel) {
		OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).temperature(0.2).build();
		return new ProviderRuntime(7L, "turn-provider", model, "http://localhost:0",
				"/v1/chat/completions", "turn|key", options, null, chatModel, null);
	}

}
