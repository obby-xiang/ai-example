package com.example.configmgr.ai.form;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前会话、当前任务的未提交条件草案只读投影。 */
@Component
public class ConditionDraftProjectionService {

    private static final int MAX_SESSION_RUNS = 256;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunStore runStore;

    private final TaskService taskService;

    public ConditionDraftProjectionService(RunStore runStore, TaskService taskService) {
        this.runStore = runStore;
        this.taskService = taskService;
    }

    public record Item(String defCode, JsonNode conditions) {

        Map<String, Object> toMap() {
            return Map.of("defCode", this.defCode, "conditions", this.conditions);
        }
    }

    public record Projection(boolean complete, List<Item> items, String fingerprint) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("complete", this.complete);
            out.put("items", this.items.stream().map(Item::toMap).toList());
            out.put("fingerprint", this.fingerprint);
            return out;
        }
    }

    private record Candidate(String runId, PendingToolCall pending, List<ConditionsForm.BatchItem> items,
                             String signature, boolean valid) {

        boolean successful() {
            return PendingToolCall.FRONTEND_RESULT.equals(this.pending.getStatus())
                    && this.pending.effectiveOutcome() == ToolOutcome.OK;
        }
    }

    private record Snapshot(boolean valid, List<String> defCodes, List<Candidate> candidates, String fingerprint) {
    }

    public Projection project(String sessionId, long taskId) {
        Snapshot first = capture(sessionId, taskId);
        Snapshot second = capture(sessionId, taskId);
        if (!first.fingerprint.equals(second.fingerprint)) {
            return incomplete(first.fingerprint);
        }
        return projectStable(first);
    }

    private Projection projectStable(Snapshot snapshot) {
        if (!snapshot.valid) {
            return incomplete(snapshot.fingerprint);
        }
        List<Item> items = new ArrayList<>();
        for (String defCode : snapshot.defCodes) {
            Candidate latest = latestForDef(snapshot.candidates, defCode);
            if (latest == null) {
                continue;
            }
            if (!latest.valid || !latest.successful() || isAmbiguous(snapshot.candidates, defCode, latest)) {
                return incomplete(snapshot.fingerprint);
            }
            ConditionsForm.BatchItem selected = latest.items.stream()
                    .filter(item -> defCode.equals(item.defCode())).findFirst().orElse(null);
            if (selected == null) {
                return incomplete(snapshot.fingerprint);
            }
            items.add(new Item(defCode, selected.conditions().deepCopy()));
        }
        return new Projection(true, List.copyOf(items), snapshot.fingerprint);
    }

    private static Candidate latestForDef(List<Candidate> candidates, String defCode) {
        Candidate latest = null;
        for (Candidate candidate : candidates) {
            if (candidate.items.stream().noneMatch(item -> defCode.equals(item.defCode()))) {
                continue;
            }
            if (latest == null || candidate.pending.getRequestedAtMs() > latest.pending.getRequestedAtMs()) {
                latest = candidate;
            }
        }
        return latest;
    }

    private static boolean isAmbiguous(List<Candidate> candidates, String defCode, Candidate latest) {
        for (Candidate candidate : candidates) {
            if (candidate == latest || candidate.pending.getRequestedAtMs() != latest.pending.getRequestedAtMs()) {
                continue;
            }
            if (!candidate.pending.getToolCallId().equals(latest.pending.getToolCallId())
                    && candidate.items.stream().anyMatch(item -> defCode.equals(item.defCode()))) {
                return true;
            }
        }
        return false;
    }

    private Snapshot capture(String sessionId, long taskId) {
        Task task = this.taskService.findById(taskId);
        if (task == null) {
            return new Snapshot(false, List.of(), List.of(), fingerprint(sessionId, taskId, false, List.of(), List.of()));
        }
        List<String> defCodes = taskDefCodes(task);
        List<String> runIds = new ArrayList<>(this.runStore.sessionRunIds(sessionId, MAX_SESSION_RUNS));
        runIds.sort(null);
        List<Candidate> candidates = new ArrayList<>();
        for (String runId : runIds) {
            for (PendingToolCall pending : this.runStore.pendings(runId)) {
                Candidate candidate = candidate(runId, taskId, pending);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return new Snapshot(true, defCodes, List.copyOf(candidates),
                fingerprint(sessionId, taskId, true, defCodes, candidates));
    }

    private static Candidate candidate(String runId, long taskId, PendingToolCall pending) {
        if (!AiToolNames.SET_CONDITIONS.equals(pending.getName())
                || !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
                || !runId.equals(pending.getDraftRunId()) || !Long.valueOf(taskId).equals(pending.getDraftTaskId())) {
            return null;
        }
        List<ConditionsForm.BatchItem> strictItems = ConditionsForm.batchItemsOf(pending.getArguments());
        List<ConditionsForm.BatchItem> items = strictItems == null ? candidateOutlines(pending.getArguments()) : strictItems;
        String signature = ConditionsForm.setConditionSignature(pending.getArguments());
        boolean valid = StringUtils.isNotBlank(pending.getToolCallId()) && pending.getRequestedAtMs() > 0L
                && strictItems != null && signature != null;
        return new Candidate(runId, pending, items, signature, valid);
    }

    private static List<ConditionsForm.BatchItem> candidateOutlines(String arguments) {
        List<ConditionsForm.BatchItem> items = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(arguments);
            if (root == null || !root.path("items").isArray()) {
                return List.of();
            }
            for (JsonNode item : root.path("items")) {
                if (item.isObject() && item.path("defCode").isTextual()
                        && StringUtils.isNotBlank(item.path("defCode").asText().trim())) {
                    items.add(new ConditionsForm.BatchItem(item.path("defCode").asText().trim(), item.path("conditions")));
                }
            }
        } catch (Exception ex) {
            return List.of();
        }
        return items;
    }

    private static List<String> taskDefCodes(Task task) {
        if (task.getItems() == null) {
            return List.of();
        }
        return task.getItems().stream().filter(item -> item != null && StringUtils.isNotBlank(item.getDefCode()))
                .map(TaskItem::getDefCode).toList();
    }

    private static String fingerprint(String sessionId, long taskId, boolean valid, List<String> defCodes,
                                      List<Candidate> candidates) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sessionId", sessionId);
        root.put("taskId", taskId);
        root.put("valid", valid);
        root.put("defCodes", defCodes);
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing((Candidate candidate) -> candidate.runId)
                .thenComparing(candidate -> candidate.pending.getToolCallId(), Comparator.nullsLast(String::compareTo)));
        root.put("candidates", ordered.stream().map(ConditionDraftProjectionService::fingerprintCandidate).toList());
        try {
            return DigestUtils.sha256Hex(JSON.writeValueAsString(root));
        }
        catch (Exception ex) {
            throw new AiStateUnavailableException("Condition draft projection is unavailable", ex);
        }
    }

    private static Map<String, Object> fingerprintCandidate(Candidate candidate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", candidate.runId);
        out.put("toolCallId", candidate.pending.getToolCallId());
        out.put("draftRunId", candidate.pending.getDraftRunId());
        out.put("draftTaskId", candidate.pending.getDraftTaskId());
        out.put("requestedAtMs", candidate.pending.getRequestedAtMs());
        out.put("status", candidate.pending.getStatus());
        out.put("outcome", candidate.pending.effectiveOutcome().wire());
        out.put("signature", candidate.signature);
        out.put("valid", candidate.valid);
        return out;
    }

    private static Projection incomplete(String fingerprint) {
        return new Projection(false, List.of(), fingerprint);
    }
}
