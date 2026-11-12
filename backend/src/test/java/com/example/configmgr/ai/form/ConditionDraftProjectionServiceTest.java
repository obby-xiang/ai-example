package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionDraftProjectionServiceTest {

    private static final String SESSION = "session-1";

    private static final long TASK_ID = 7L;

    private final RunStore runStore = mock(RunStore.class);

    private final TaskService taskService = mock(TaskService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private ConditionDraftProjectionService service;

    @BeforeEach
    void setUp() {
        this.service = new ConditionDraftProjectionService(this.runStore, this.taskService);
        task(TASK_ID, "A", "B");
    }

    @Test
    void selectsLatestBatchPerDefAcrossRunsAndIgnoresOtherOwnership() {
        PendingToolCall first = pending("call-a", "run-a", TASK_ID, 100L,
                args(item("A", condition("A-1")), item("B", condition("B-1"))));
        PendingToolCall latestA = pending("call-b", "run-b", TASK_ID, 200L,
                args(item("A", condition("A-2"))));
        PendingToolCall earlierB = pending("call-b-older", "run-a", TASK_ID, 50L,
                args(item("B", condition("B-OLD"))));
        PendingToolCall otherTask = pending("call-other-task", "run-c", 99L, 300L,
                args(item("A", condition("OTHER-TASK"))));
        PendingToolCall wrongRun = pending("call-wrong-run", "not-run-d", TASK_ID, 400L,
                args(item("B", condition("WRONG-RUN"))));
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(
                new LinkedHashSet<>(List.of("run-a", "run-b", "run-c", "run-d")),
                new LinkedHashSet<>(List.of("run-d", "run-c", "run-b", "run-a")));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(first, earlierB), List.of(earlierB, first));
        when(this.runStore.pendings("run-b")).thenReturn(List.of(latestA));
        when(this.runStore.pendings("run-c")).thenReturn(List.of(otherTask));
        when(this.runStore.pendings("run-d")).thenReturn(List.of(wrongRun));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isTrue();
        assertThat(projection.items()).hasSize(2);
        assertThat(projection.items().get(0).defCode()).isEqualTo("A");
        assertThat(projection.items().get(0).conditions().get("value").asText()).isEqualTo("A-2");
        assertThat(projection.items().get(1).defCode()).isEqualTo("B");
        assertThat(projection.items().get(1).conditions().get("value").asText()).isEqualTo("B-1");
        assertThat(projection.fingerprint()).hasSize(64);
        assertThat(projection.toMap()).containsOnlyKeys("complete", "items", "fingerprint");
    }

    @Test
    void otherTaskCandidatesDoNotBlockOrLeakIntoCurrentTask() {
        PendingToolCall otherTask = pending("call-other", "run-a", 99L, 100L,
                args(item("A", condition("OTHER"))));
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(otherTask));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isTrue();
        assertThat(projection.items()).isEmpty();
    }

    @Test
    void sameTimestampWithDifferentToolCallIdsIsIncomplete() {
        PendingToolCall left = pending("call-left", "run-a", TASK_ID, 200L,
                args(item("A", condition("A-1"))));
        PendingToolCall right = pending("call-right", "run-b", TASK_ID, 200L,
                args(item("A", condition("A-2"))));
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a", "run-b"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(left));
        when(this.runStore.pendings("run-b")).thenReturn(List.of(right));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isFalse();
        assertThat(projection.items()).isEmpty();
    }

    @Test
    void latestNonOkBatchIsIncomplete() {
        PendingToolCall older = pending("call-old", "run-a", TASK_ID, 100L,
                args(item("A", condition("A-1"))));
        PendingToolCall latest = pending("call-new", "run-b", TASK_ID, 200L,
                args(item("A", condition("A-2"))));
        latest.setStatus(PendingToolCall.FRONTEND_RESULT);
        latest.setOutcome(ToolOutcome.FAILED.wire());
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a", "run-b"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(older));
        when(this.runStore.pendings("run-b")).thenReturn(List.of(latest));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isFalse();
        assertThat(projection.items()).isEmpty();
    }

    @Test
    void olderMalformedBatchDoesNotOverrideLatestValidSuccess() {
        PendingToolCall malformed = pending("call-malformed", "run-a", TASK_ID, 100L,
                "{\"items\":[{\"defCode\":\"A\",\"conditions\":\"{}\"}]}");
        PendingToolCall latest = pending("call-valid", "run-b", TASK_ID, 200L,
                args(item("A", condition("A-2"))));
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a", "run-b"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(malformed));
        when(this.runStore.pendings("run-b")).thenReturn(List.of(latest));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isTrue();
        assertThat(projection.items()).hasSize(1);
        assertThat(projection.items().getFirst().conditions().get("value").asText()).isEqualTo("A-2");
    }

    @Test
    void malformedLatestBatchIsIncomplete() {
        PendingToolCall older = pending("call-old", "run-a", TASK_ID, 100L,
                args(item("A", condition("A-1"))));
        PendingToolCall latest = pending("call-new", "run-b", TASK_ID, 200L,
                "{\"items\":[{\"defCode\":\"A\",\"conditions\":\"{}\"}]}");
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a", "run-b"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(older));
        when(this.runStore.pendings("run-b")).thenReturn(List.of(latest));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isFalse();
        assertThat(projection.items()).isEmpty();
    }

    @Test
    void changedSecondReadIsIncomplete() {
        PendingToolCall first = pending("call-a", "run-a", TASK_ID, 100L,
                args(item("A", condition("A-1"))));
        PendingToolCall changed = pending("call-a", "run-a", TASK_ID, 100L,
                args(item("A", condition("A-2"))));
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of("run-a"));
        when(this.runStore.pendings("run-a")).thenReturn(List.of(first), List.of(changed));

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isFalse();
        assertThat(projection.items()).isEmpty();
    }

    @Test
    void noCandidateIsCompleteWithEmptyItems() {
        when(this.runStore.sessionRunIds(eq(SESSION), anyInt())).thenReturn(Set.of());

        ConditionDraftProjectionService.Projection projection = this.service.project(SESSION, TASK_ID);

        assertThat(projection.complete()).isTrue();
        assertThat(projection.items()).isEmpty();
        assertThat(projection.fingerprint()).hasSize(64);
    }

    private void task(long taskId, String... defCodes) {
        Task task = mock(Task.class);
        List<TaskItem> items = new ArrayList<>();
        for (String defCode : defCodes) {
            TaskItem item = mock(TaskItem.class);
            when(item.getDefCode()).thenReturn(defCode);
            items.add(item);
        }
        when(task.getItems()).thenReturn(items);
        when(this.taskService.findById(taskId)).thenReturn(task);
    }

    private PendingToolCall pending(String toolCallId, String runId, long taskId, long requestedAtMs, String arguments) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName(AiToolNames.SET_CONDITIONS);
        pending.setKind(PendingToolCall.KIND_FRONTEND);
        pending.setDraftRunId(runId);
        pending.setDraftTaskId(taskId);
        pending.setRequestedAtMs(requestedAtMs);
        pending.setStatus(PendingToolCall.FRONTEND_RESULT);
        pending.setOutcome(ToolOutcome.OK.wire());
        pending.setArguments(arguments);
        return pending;
    }

    private String args(Map<String, Object>... items) {
        try {
            return this.mapper.writeValueAsString(Map.of("items", items));
        }
        catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static Map<String, Object> item(String defCode, Map<String, Object> conditions) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("defCode", defCode);
        item.put("conditions", conditions);
        return item;
    }

    private static Map<String, Object> condition(String value) {
        return new LinkedHashMap<>(Map.of("value", value));
    }
}
