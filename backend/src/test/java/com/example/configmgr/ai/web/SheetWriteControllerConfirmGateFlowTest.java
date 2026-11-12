package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.SheetWriteGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SheetWriteControllerConfirmGateFlowTest {

    private static final String RUN_ID = "run-sheet-result";

    private static final String CALL_ID = "call-sheet-result";

    private final InMemoryRunStore store = new InMemoryRunStore(true);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ResumeService resumeService = mock(ResumeService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResumeService> resumeProvider = mock(ObjectProvider.class);

    private RequestContextUtils.SafeCloseable identity;

    @BeforeEach
    void establishIdentity() {
        this.identity = RequestContextUtils.useContext(1L, 101L);
    }

    @AfterEach
    void closeIdentity() {
        this.identity.close();
    }

    @Test
    void cancelledSheetWritePostsStructuredResultThroughGuardAndRunStore() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("cancelled", "用户取消了本次修改", 0, 0, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true));

        PendingToolCall settled = this.store.pending(RUN_ID, CALL_ID);
        assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
        assertThat(settled.getOutcome()).isEqualTo("cancelled");
        assertThat(settled.getResultText()).isEqualTo(result);
        assertThat(this.mapper.readTree(settled.getResultText()).path("kind").asText()).isEqualTo("cancelled");
        assertThat(this.mapper.readTree(settled.getResultText()).path("plan").path("cells").asInt()).isEqualTo(2);
    }

    @Test
    void failedKindWithOkOutcomeIsRejectedBeforeConsuming() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("failed", "写入中途失败", 1, 1, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, false, "ok")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHEET_RESULT_REJECTED"));

        assertPendingNotConsumed();
    }

    @Test
    void appliedKindWithFailedOutcomeIsRejectedBeforeConsuming() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("applied", "已应用", 2, 2, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, false, "failed")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHEET_RESULT_REJECTED"));

        assertPendingNotConsumed();
    }

    @Test
    void cancelledKindWithoutCancellationFlagIsRejected() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("cancelled", "用户取消", 0, 0, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, false, "cancelled")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHEET_RESULT_REJECTED"));

        assertPendingNotConsumed();
    }

    @Test
    void cancelledResultWithNonzeroActualCountIsRejected() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("cancelled", "用户取消", 1, 1, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHEET_RESULT_REJECTED"));

        assertPendingNotConsumed();
    }

    @Test
    void failedResultAbovePlanIsRejected() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("failed", "写入中途失败", 3, 2, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, false, "failed")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHEET_RESULT_REJECTED"));

        assertPendingNotConsumed();
    }

    @Test
    void failedKindWithUnknownOutcomeIsAcceptedAsExplicitlyUnknown() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("failed", "恢复时无法确认执行事实", 0, 0, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, false, "unknown")))
                .andExpect(status().isOk());

        PendingToolCall settled = this.store.pending(RUN_ID, CALL_ID);
        assertThat(settled.getOutcome()).isEqualTo("unknown");
        assertThat(settled.getResultText()).isEqualTo(result);
    }

    @Test
    void oversizedCancelledSheetWriteJsonIsRejectedWithoutTruncation() throws Exception {
        seedPending("replace_values", replaceArgs());
        String result = receipt("cancelled", "x".repeat(9000), 0, 0, 2, 2);

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(result, true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FRONTEND_RESULT_TOO_LARGE"))
                .andExpect(jsonPath("$.truncated").value(false));

        assertPendingNotConsumed();
    }

    @Test
    void otherFrontendCancellationKeepsTheExistingFixedText() throws Exception {
        seedPending("generative_form", "{}");

        mockMvc().perform(post("/api/ai/frontend-tool-result").contentType(MediaType.APPLICATION_JSON)
                .content(body(null, true, null)))
                .andExpect(status().isOk());

        PendingToolCall settled = this.store.pending(RUN_ID, CALL_ID);
        assertThat(settled.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
        assertThat(settled.getResultText()).startsWith("FRONTEND_CANCELLED：");
    }

    private void assertPendingNotConsumed() {
        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(pending.getResultText()).isNull();
    }

    private void seedPending(String toolName, String arguments) {
        RunSnapshot snapshot = this.store.seedRun(RUN_ID, "session-sheet-result");
        snapshot.setStatus(RunSnapshot.RUNNING);
        this.store.save(snapshot);
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(CALL_ID);
        pending.setName(toolName);
        pending.setType("function");
        pending.setArguments(arguments);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setStatus(PendingToolCall.PENDING);
        this.store.seedPending(RUN_ID, pending);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.resumeProvider.getIfAvailable()).thenReturn(this.resumeService);
        when(this.resumeService.resume(RUN_ID)).thenReturn(new ResumeService.ResumeResult(
                ResumeService.Outcome.NOT_FOUND, Map.of("runId", RUN_ID)));
    }

    private MockMvc mockMvc() {
        ConfirmGate gate = new ConfirmGate(this.store, this.registry, new AiProperties(), this.cancellations,
                mock(ToolActivityBeacon.class), List.of(new SheetWriteGuard()), this.resumeProvider, Runnable::run);
        AiController controller = new AiController(mock(AiAvailability.class), provider(), provider(), provider(),
                mock(ChatMemory.class), mock(ChatMemoryRepository.class), mock(SessionGate.class), gate,
                this.store, this.registry, this.cancellations, mock(RedisAvailability.class),
                mock(ThreadPoolTaskExecutor.class), this.mapper, new AiProperties(), mock(Environment.class),
                mock(com.example.configmgr.job.service.JobWatchdog.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String body(String result, boolean cancelled, String outcome) throws Exception {
        return this.mapper.writeValueAsString(new FrontendToolResultRequest(
                RUN_ID, CALL_ID, result, null, cancelled, outcome, null, null));
    }

    private static String replaceArgs() {
        return "{\"defCode\":\"CURRENCY\",\"scope\":\"sheet\",\"columnCodes\":[\"status\"],"
                + "\"search\":\"停用\",\"replace\":\"禁用\"}";
    }

    private static String receipt(String kind, String text, int cells, int rows, int planCells, int planRows) {
        return "{\"kind\":\"" + kind + "\",\"text\":\"" + text + "\",\"cells\":" + cells
                + ",\"rows\":" + rows + ",\"plan\":{\"cells\":" + planCells + ",\"rows\":" + planRows + "}}";
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

}
