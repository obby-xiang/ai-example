package com.example.configmgr.ai.form;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class CreateTaskDetailsForm extends HostForm {

    public static final String TITLE = "补充创建信息";

    public static final String TITLE_EN = "Complete task details";

    private static final String TOOL_CALL_ID_PREFIX = "host-create-details-";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    public CreateTaskDetailsForm(RunStore store, RunRegistry registry, ConfirmGate gate, MessageJsonCodec codec,
            AiProperties properties) {
        super(store, registry, gate, codec, properties);
    }

    public static String toolCallId(String createTaskToolCallId) {
        return TOOL_CALL_ID_PREFIX + createTaskToolCallId;
    }

    public static String nextToolCallId(String createTaskToolCallId, String currentFormToolCallId, String arguments) {
        String generation = StringUtils.defaultString(currentFormToolCallId) + "\n" + StringUtils.defaultString(arguments);
        return toolCallId(createTaskToolCallId) + "-"
                + UUID.nameUUIDFromBytes(generation.getBytes(StandardCharsets.UTF_8));
    }

    public boolean parkIfUnresolved(String runId, String createTaskToolCallId, String createTaskArguments,
            CreateTaskReferenceResolver.Resolution resolution, List<Message> history) {
        return parkIfUnresolved(runId, createTaskToolCallId, createTaskArguments, resolution, history, null, null);
    }

    public boolean parkIfUnresolved(String runId, String createTaskToolCallId, String createTaskArguments,
            CreateTaskReferenceResolver.Resolution resolution, List<Message> history, String assistantContent,
            Map<String, Object> assistantMetadata) {
        return parkUnresolved(runId, createTaskToolCallId, createTaskArguments, resolution, history, assistantContent,
                assistantMetadata, toolCallId(createTaskToolCallId));
    }

    public boolean parkNextUnresolved(String runId, String currentFormToolCallId, String createTaskToolCallId,
            String createTaskArguments, CreateTaskReferenceResolver.Resolution resolution, List<Message> history) {
        return parkUnresolved(runId, createTaskToolCallId, createTaskArguments, resolution, history, null, null,
                nextToolCallId(createTaskToolCallId, currentFormToolCallId, createTaskArguments));
    }

    private boolean parkUnresolved(String runId, String createTaskToolCallId, String createTaskArguments,
            CreateTaskReferenceResolver.Resolution resolution, List<Message> history, String assistantContent,
            Map<String, Object> assistantMetadata, String formToolCallId) {
        if (StringUtils.isAnyBlank(runId, createTaskToolCallId, createTaskArguments) || history == null
                || resolution == null || resolution.status() != CreateTaskReferenceResolver.Status.UNRESOLVED
                || resolution.unresolved().isEmpty() || !isParkEnabled() || hasIssued(runId, formToolCallId)) {
            return false;
        }
        Optional<String> arguments = formArguments(buildForm(resolution.unresolved(), ReplyLanguage.current()));
        if (arguments.isEmpty()) {
            return false;
        }
        PendingToolCall pending = frontendForm(formToolCallId, arguments.get());
        pending.setCreateTaskToolCallId(createTaskToolCallId);
        pending.setCreateTaskArguments(createTaskArguments);
        log.info("create_task has unresolved structured references; host parks details form runId={} unresolvedCount={}",
                runId, resolution.unresolved().size());
        return shouldPark(runId, history, pending, assistantContent, assistantMetadata);
    }

    private static Map<String, Object> buildForm(List<CreateTaskReferenceResolver.UnresolvedReference> unresolved,
            ReplyLanguage language) {
        boolean english = language != null && language.english();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CreateTaskReferenceResolver.UnresolvedReference reference : unresolved) {
            fields.add(buildField(reference, english));
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("scenario", "CLARIFY");
        form.put("purpose", FormPurposes.CREATE_TASK_DETAILS);
        form.put("title", english ? TITLE_EN : TITLE);
        form.put("fields", fields);
        return form;
    }

    private static Map<String, Object> buildField(CreateTaskReferenceResolver.UnresolvedReference reference,
            boolean english) {
        boolean enumValue = reference.kind() == CreateTaskReferenceResolver.Kind.ENUM_VALUE;
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", fieldKey(reference));
        field.put("label", fieldLabel(reference, english));
        field.put("type", enumValue ? "enum" : "text");
        field.put("required", true);
        if (enumValue) {
            field.put("options", reference.options().stream().map(CreateTaskDetailsForm::option).toList());
        }
        return field;
    }

    private static String fieldLabel(CreateTaskReferenceResolver.UnresolvedReference reference, boolean english) {
        int position = Math.max(0, reference.fieldIndex()) + 1;
        return switch (reference.kind()) {
            case DEFINITION -> (english ? "Configuration " : "配置项 ") + (reference.definitionIndex() + 1);
            case FIELD -> (english ? "Field " : "字段 ") + position;
            case ENUM_VALUE -> (english ? "Value " : "选项值 ") + position;
        };
    }

    public static String fieldKey(CreateTaskReferenceResolver.UnresolvedReference reference) {
        String definition = reference.definitionIndex() < 0 ? "unknown" : Integer.toString(reference.definitionIndex());
        return switch (reference.kind()) {
            case DEFINITION -> "definition_" + reference.definitionIndex();
            case FIELD -> "field_" + definition + "_" + reference.fieldIndex();
            case ENUM_VALUE -> "enum_value_" + definition + "_" + reference.fieldIndex();
        };
    }

    private static Map<String, Object> option(String value) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", value);
        return option;
    }

    public boolean parkCreateTaskConfirmation(String runId, PendingToolCall createTask, List<Message> history) {
        try {
            RunSnapshot snapshot = this.store.get(runId);
            String content = snapshot == null ? null : snapshot.getAssistantContent();
            Map<String, Object> metadata = snapshot == null ? null : snapshot.getAssistantMetadata();
            this.store.saveSuspended(runId, content, this.registry.of(runId).transcriptFullText(), List.of(createTask),
                    history, this.codec, metadata);
        }
        catch (RuntimeException ex) {
            convergeAfterParkFailure(runId, createTask, ex);
            return false;
        }
        return publishCreateTaskConfirmation(runId, createTask);
    }

    private boolean publishCreateTaskConfirmation(String runId, PendingToolCall createTask) {
        SseChatEmitter out;
        try {
            out = this.registry.of(runId);
            out.suspended(List.of(createTask), this.store.runKey(runId), this.store.pendingKey(runId),
                    this.store.instanceId());
            out.toolStart(createTask);
        }
        catch (RuntimeException ex) {
            log.warn("create task confirmation parked but initial frame publication failed runId={} toolCallId={}",
                    runId, createTask.getToolCallId(), ex);
            return true;
        }
        try {
            this.gate.publishRequestsForParkedBatch(runId, List.of(createTask));
        }
        catch (RuntimeException ex) {
            log.warn("create task confirmation parked but request publication failed runId={} toolCallId={}",
                    runId, createTask.getToolCallId(), ex);
            return true;
        }
        try {
            out.completeSubscriptions();
        }
        catch (RuntimeException ex) {
            log.warn("create task confirmation stream close failed; park remains intact runId={} toolCallId={}",
                    runId, createTask.getToolCallId(), ex);
        }
        return true;
    }

    public static boolean isCreateTaskDetails(PendingToolCall pending) {
        if (pending == null || !GenerativeFormRules.TOOL_NAME.equals(pending.getName())) {
            return false;
        }
        JsonNode arguments = parseJson(pending.getArguments());
        return arguments != null && FormPurposes.CREATE_TASK_DETAILS.equals(FormPurposes.resolve(arguments.path("form")));
    }

    public static String mergeSubmission(PendingToolCall pending) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(pending.getCreateTaskArguments());
            JsonNode values = JSON.readTree(pending.getResultText());
            JsonNode fields = JSON.readTree(pending.getArguments()).path("form").path("fields");
            if (root == null || values == null || !values.isObject() || !fields.isArray()) {
                throw new IllegalArgumentException("Invalid create task details receipt");
            }
            for (JsonNode field : fields) {
                mergeField(root, field.path("key").asText(), values);
            }
            return JSON.writeValueAsString(root);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("Cannot merge create task details receipt", ex);
        }
    }

    private static void mergeField(ObjectNode root, String key, JsonNode values) {
        JsonNode value = values.get(key);
        if (value == null || !value.isTextual() || StringUtils.isBlank(value.asText())) {
            throw new IllegalArgumentException("Missing textual create task detail");
        }
        if (key.startsWith("definition_")) {
            ArrayNode definitions = (ArrayNode) root.path("defCodes");
            int index = index(key.substring("definition_".length()));
            if (index >= definitions.size()) {
                throw new IllegalArgumentException("Definition index out of bounds");
            }
            definitions.set(index, value);
            return;
        }
        ConditionField target = conditionField(root, key);
        target.field().put(target.property(), value.asText());
    }

    private static ConditionField conditionField(ObjectNode root, String key) {
        boolean enumValue = key.startsWith("enum_value_");
        String prefix = enumValue ? "enum_value_" : "field_";
        if (!key.startsWith(prefix)) {
            throw new IllegalArgumentException("Unknown create task detail key");
        }
        String coordinates = key.substring(prefix.length());
        int separator = coordinates.lastIndexOf('_');
        if (separator <= 0) {
            throw new IllegalArgumentException("Invalid create task detail coordinates");
        }
        definitionIndex(coordinates.substring(0, separator));
        int fieldIndex = index(coordinates.substring(separator + 1));
        JsonNode fields = root.path("conditions").path("fields");
        if (!fields.isArray() || fieldIndex >= fields.size() || !(fields.get(fieldIndex) instanceof ObjectNode field)) {
            throw new IllegalArgumentException("Field index out of bounds");
        }
        return new ConditionField(field, enumValue ? "value" : "fieldCode");
    }

    private static int definitionIndex(String value) {
        return "unknown".equals(value) ? -1 : index(value);
    }

    private static int index(String value) {
        try {
            int index = Integer.parseInt(value);
            if (index < 0) {
                throw new IllegalArgumentException("Negative create task detail index");
            }
            return index;
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid create task detail index", ex);
        }
    }

    private record ConditionField(ObjectNode field, String property) {
    }

}
