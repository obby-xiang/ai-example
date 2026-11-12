package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class RunStoreJobFactTest {

    private final FakeLeaseRedis fake = new FakeLeaseRedis();
    private final RunStore store = new RunStore(this.fake.redis, new ObjectMapper(), new AiProperties());

    @Test
    void executionLedgerRoundTripPreservesCreatedJobIdAsAString() {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId("call-job");
        pending.setName("start_publish");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setArguments("{\"taskId\":7}");
        pending.setOutcome(ToolOutcome.OK.wire());
        pending.setExecuted(true);
        assertThat(PendingToolCall.class.getDeclaredFields())
                .as("PendingToolCall 必须先有持久化 createdJobId 字段")
                .anyMatch(field -> "createdJobId".equals(field.getName()));
        try {
            Field field = PendingToolCall.class.getDeclaredField("createdJobId");
            field.setAccessible(true);
            field.set(pending, 9007199254740991L);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }

        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 7L)) {
            this.store.appendLedger("run-job-ledger", pending, "发布作业已启动（作业 #1）");

            assertThat(this.store.ledger("run-job-ledger")).singleElement().satisfies(record ->
                    assertThat(record).containsEntry("createdJobId", "9007199254740991"));
        }
    }
}
