package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.ToolPreflightService;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSteps;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ExportStartForm extends HostForm {

    public static final String TOOL_CALL_ID_PREFIX = "host-start-export-";

    private final TaskService taskService;

    private final JobService jobService;

    private final ToolPreflightService preflight;

    private final ConditionsForm conditionsForm;

    @Autowired
    public ExportStartForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
                           AiProperties properties, TaskService taskService, JobService jobService,
                           ToolPreflightService preflight, ConditionsForm conditionsForm) {
        super(store, registry, gate, codec, properties);
        this.taskService = taskService;
        this.jobService = jobService;
        this.preflight = preflight;
        this.conditionsForm = conditionsForm;
    }

    public enum ParkResult {
        NOT_DUE, PARKED, PARK_FAILED
    }

    public static String toolCallId(String runId, long taskId) {
        return TOOL_CALL_ID_PREFIX + (runId == null ? "" : runId + "-") + taskId;
    }

    public boolean parkIfDueAtExport(String runId, AiContext context, List<Message> history) {
        return parkIfDueAtExportResult(runId, context, history) == ParkResult.PARKED;
    }

    public ParkResult parkIfDueAtExportResult(String runId, AiContext context, List<Message> history) {
        if (runId == null || context == null || history == null || !isParkEnabled()) {
            return ParkResult.NOT_DUE;
        }
        try {
            Long taskId = context.getTaskId();
            if (taskId == null || this.taskService == null || this.conditionsForm == null || this.preflight == null) {
                return ParkResult.NOT_DUE;
            }
            Task task = this.taskService.findById(taskId);
            if (!isExportReady(task) || !this.conditionsForm.canStartExport(task)) {
                return ParkResult.NOT_DUE;
            }
            List<PendingToolCall> pendings = this.store.pendings(runId);
            if (!hasContinuationIntent(pendings, taskId)) {
                return ParkResult.NOT_DUE;
            }
            String toolCallId = toolCallId(runId, taskId);
            if (hasStartExport(pendings, taskId, toolCallId) || hasIssued(runId, toolCallId)
                    || !this.store.ledgerFor(runId, toolCallId).isEmpty() || hasJobConflict(taskId)) {
                return ParkResult.NOT_DUE;
            }
            String arguments = "{\"taskId\":" + taskId + "}";
            ToolPreflightService.PreflightReport report = this.preflight.inspect(AiToolNames.START_EXPORT, arguments, context);
            if (!report.pass()) {
                log.info("host start_export preflight failed; no confirmation parked runId={} taskId={} codes={}", runId, taskId,
                        report.failures().stream().map(ToolPreflightService.PreflightReport.Check::code).toList());
                return ParkResult.NOT_DUE;
            }
            return park(runId, history, confirm(toolCallId, arguments, report));
        } catch (RuntimeException ex) {
            log.warn("host start_export confirmation was not parked (continue on existing path) runId={}", runId, ex);
            return ParkResult.NOT_DUE;
        }
    }

    private ParkResult park(String runId, List<Message> history, PendingToolCall pending) {
        try {
            return shouldPark(runId, history, pending) ? ParkResult.PARKED : ParkResult.PARK_FAILED;
        } catch (RuntimeException ex) {
            log.warn("host start_export park threw; resume must not hand off runId={} toolCallId={}", runId,
                    pending.getToolCallId(), ex);
            return ParkResult.PARK_FAILED;
        }
    }

    private static boolean isExportReady(Task task) {
        return task != null && task.getType() == Task.TaskType.EXPORT && TaskSteps.isExportExecutionStep(task.getCurrentStep());
    }

    public static boolean hasContinuationIntent(List<PendingToolCall> pendings, Long taskId) {
        PendingToolCall created = DefinitionSelectionForm.settledCreateTask(pendings);
        return created != null && taskId != null && taskId.equals(created.getCreatedTaskId())
                && RunContextRefresher.openWizard(created.getArguments());
    }

    private static boolean hasStartExport(List<PendingToolCall> pendings, Long taskId, String toolCallId) {
        for (PendingToolCall pending : ObjectUtils.defaultIfNull(pendings, List.<PendingToolCall>of())) {
            if (pending == null || !AiToolNames.START_EXPORT.equals(pending.getName())) {
                continue;
            }
            JsonNode arguments = parseJson(pending.getArguments());
            Long requestedTaskId = DefinitionSelectionForm.longOf(arguments == null ? null : arguments.get("taskId"));
            if (toolCallId.equals(pending.getToolCallId()) || requestedTaskId == null || taskId.equals(requestedTaskId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasJobConflict(Long taskId) {
        return this.jobService == null || this.jobService.finalizedConflict(taskId, Job.JobType.EXPORT) != null
                || this.jobService.inFlightConflict(taskId, Job.JobType.EXPORT) != null;
    }

    private static PendingToolCall confirm(String toolCallId, String arguments,
                                           ToolPreflightService.PreflightReport report) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName(AiToolNames.START_EXPORT);
        pending.setType("function");
        pending.setArguments(arguments);
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        pending.setIrreversible(false);
        pending.setPreflight(report.asMap());
        return pending;
    }
}
