package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SseChatEmitterResponsePathTest {

    private static final String RUN_ID = "run-response-path";

    private final RunStore store = mock(RunStore.class);

    private final FrameStore frames = mock(FrameStore.class);

    private final List<Map<String, Object>> archived = new ArrayList<>();

    private SseChatEmitter emitter;

    private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identityScope;

    @BeforeEach
    void setUp() {
        AtomicLong seq = new AtomicLong();
        when(this.frames.appendAndAllocateSeq(anyString(), anyMap(), any(), anyLong())).thenAnswer(invocation -> {
            Map<String, Object> frame = invocation.getArgument(1);
            long next = seq.incrementAndGet();
            frame.put("seq", next);
            FrameStore.PayloadFactory payloadFactory = invocation.getArgument(2);
            byte[] payload = payloadFactory.payload(frame);
            this.archived.add(new LinkedHashMap<>(frame));
            return new FrameStore.AppendResult(next, payload);
        });
        this.identityScope = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
        this.emitter = new SseChatEmitter(RUN_ID, this.store, this.frames, new ObjectMapper(), Runnable::run,
                16, 64 * 1024, null, null, null, null);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        this.identityScope.close();
    }

    @Test
    void suspensionRequestsOmitCallbackAndKeepBusinessFields() {
        this.emitter.confirmRequest(pending("call-confirm"));
        this.emitter.frontendToolRequest(pending("call-frontend"), 30);

        assertThat(this.archived).hasSize(2);
        assertThat(this.archived.get(0)).containsKeys("type", "seq", "atMs", "preflight")
                .containsEntry("type", "confirm_request")
                .containsEntry("toolCallId", "call-confirm")
                .containsEntry("name", "start_import")
                .containsEntry("args", "{\"taskId\":7}")
                .containsEntry("summary", "{\"taskId\":7}")
                .containsEntry("irreversible", false)
                .doesNotContainKey("callback");
        assertThat(this.archived.get(1)).containsKeys("type", "seq", "atMs", "expiresAt")
                .containsEntry("type", "frontend_tool_request")
                .containsEntry("toolCallId", "call-frontend")
                .containsEntry("name", "start_import")
                .containsEntry("args", "{\"taskId\":7}")
                .containsEntry("timeoutSeconds", 30)
                .doesNotContainKey("callback");
    }

    private static PendingToolCall pending(String toolCallId) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName("start_import");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setArguments("{\"taskId\":7}");
        return pending;
    }
}
