package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.SlotDefinition.SlotField;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigDefinition.ConfigLevel;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class CreateTaskReferenceResolver {

    public static final String UNRESOLVED_CODE = "CREATE_TASK_REFERENCES_UNRESOLVED";

    public static final String FAILED_CODE = "CREATE_TASK_REFERENCE_RESOLUTION_FAILED";

    private final DefinitionCatalog catalog;

    private final ObjectMapper mapper;

    private final DefinitionService definitionService;

    private final ConfigDataRowRepository dataRowRepository;

    public CreateTaskReferenceResolver(DefinitionCatalog catalog, ObjectMapper mapper) {
        this(catalog, mapper, null, null);
    }

    @Autowired
    public CreateTaskReferenceResolver(DefinitionCatalog catalog, ObjectMapper mapper,
                                       DefinitionService definitionService, ConfigDataRowRepository dataRowRepository) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.definitionService = definitionService;
        this.dataRowRepository = dataRowRepository;
    }

    public Resolution resolve(String argumentsJson) {
        try {
            JsonNode parsed = this.mapper.readTree(argumentsJson);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalArgumentException("create_task arguments must be a JSON object");
            }
            List<SlotDefinition> catalogEntries = this.catalog.list();
            if (catalogEntries == null) {
                throw new IllegalStateException("definition catalog returned null");
            }
            List<UnresolvedReference> unresolved = new ArrayList<>();
            List<SlotDefinition> definitions = resolveDefinitions(root, catalogEntries, unresolved);
            if (unresolved.isEmpty()) {
                resolveFields(root, definitions, unresolved);
            }
            if (!unresolved.isEmpty()) {
                return new Resolution(Status.UNRESOLVED, argumentsJson, unresolved, UNRESOLVED_CODE);
            }
            return new Resolution(Status.RESOLVED, this.mapper.writeValueAsString(root), List.of(), null);
        }
        catch (Exception ex) {
            log.warn("could not resolve structured create_task references; creation remains blocked", ex);
            return new Resolution(Status.FAILED, argumentsJson, List.of(), FAILED_CODE);
        }
    }

    private List<SlotDefinition> resolveDefinitions(ObjectNode root, List<SlotDefinition> catalogEntries,
                                                     List<UnresolvedReference> unresolved) {
        JsonNode references = root.get("defCodes");
        if (references == null || references.isNull()) {
            throw new IllegalArgumentException("defCodes is required and must be an array");
        }
        if (!(references instanceof ArrayNode array)) {
            throw new IllegalArgumentException("defCodes must be an array");
        }
        List<SlotDefinition> resolved = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode referenceNode = array.get(index);
            if (referenceNode.isNull()) {
                unresolved.add(unresolved(Kind.DEFINITION, index, -1, null, List.of(), Reason.BLANK));
                continue;
            }
            if (!referenceNode.isTextual()) {
                throw new IllegalArgumentException("defCodes entries must be strings");
            }
            String reference = referenceNode.asText();
            List<SlotDefinition> matches = StringUtils.isBlank(reference) ? List.of()
                    : catalogEntries.stream().filter(entry -> isDefinitionMatch(reference, entry)).toList();
            if (matches.size() == 1) {
                SlotDefinition match = matches.getFirst();
                array.set(index, this.mapper.getNodeFactory().textNode(match.code()));
                resolved.add(match);
            }
            else {
                unresolved.add(unresolved(Kind.DEFINITION, index, -1, reference, List.of(),
                        matchReason(reference, matches.size())));
            }
        }
        return resolved;
    }

    private static boolean isDefinitionMatch(String reference, SlotDefinition definition) {
        return definition != null && (StringUtils.equalsIgnoreCase(reference, definition.code())
                || StringUtils.equals(StringUtils.trim(reference), StringUtils.trim(definition.name())));
    }

    private void resolveFields(ObjectNode root, List<SlotDefinition> definitions,
                               List<UnresolvedReference> unresolved) {
        JsonNode conditions = root.get("conditions");
        if (conditions == null || conditions.isNull()) {
            return;
        }
        if (!conditions.isObject()) {
            throw new IllegalArgumentException("conditions must be an object");
        }
        JsonNode fields = conditions.get("fields");
        if (fields == null || fields.isNull()) {
            return;
        }
        if (!(fields instanceof ArrayNode fieldArray)) {
            throw new IllegalArgumentException("conditions.fields must be an array");
        }
        if (fieldArray.isEmpty()) {
            return;
        }
        if (definitions.isEmpty()) {
            unresolved.add(unresolved(Kind.DEFINITION, 0, -1, null, List.of(), Reason.NO_UNIQUE_OWNER));
            addFieldsWithoutUniqueDefinition(fieldArray, unresolved);
            return;
        }
        if (definitions.size() > 1) {
            throw new IllegalArgumentException("non-empty conditions require exactly one valid defCode");
        }
        SlotDefinition definition = definitions.getFirst();
        for (int index = 0; index < fieldArray.size(); index++) {
            if (!(fieldArray.get(index) instanceof ObjectNode field)) {
                throw new IllegalArgumentException("conditions.fields entries must be objects");
            }
            resolveField(field, definition, (ObjectNode) conditions, index, unresolved);
        }
    }

    private static void addFieldsWithoutUniqueDefinition(ArrayNode fields, List<UnresolvedReference> unresolved) {
        for (int index = 0; index < fields.size(); index++) {
            if (!(fields.get(index) instanceof ObjectNode field)) {
                throw new IllegalArgumentException("conditions.fields entries must be objects");
            }
            JsonNode referenceNode = field.get("fieldCode");
            if (referenceNode == null || !referenceNode.isNull() && !referenceNode.isTextual()) {
                throw new IllegalArgumentException("fieldCode must be a string or null");
            }
            String reference = referenceNode.isNull() ? null : referenceNode.asText();
            unresolved.add(unresolved(Kind.FIELD, 0, index, reference, List.of(), Reason.NO_UNIQUE_OWNER));
        }
    }

    private void resolveField(ObjectNode field, SlotDefinition definition, ObjectNode conditions, int fieldIndex,
                              List<UnresolvedReference> unresolved) {
        JsonNode referenceNode = field.get("fieldCode");
        if (referenceNode != null && referenceNode.isNull()) {
            Optional<SlotField> key = definition.keyField();
            if (key.isPresent()) {
                field.put("fieldCode", key.get().code());
                resolveValue(field, definition, key.get(), conditions, fieldIndex, unresolved);
            }
            else {
                unresolved.add(unresolvedField(fieldIndex, null, Reason.NO_UNIQUE_KEY));
            }
            return;
        }
        if (referenceNode == null || !referenceNode.isTextual()) {
            throw new IllegalArgumentException("fieldCode must be a string or null");
        }
        String reference = referenceNode.asText();
        List<SlotField> matches = StringUtils.isBlank(reference) ? List.of()
                : definition.fields().stream().filter(candidate -> isFieldMatch(reference, candidate)).toList();
        if (matches.size() != 1) {
            unresolved.add(unresolvedField(fieldIndex, reference, matchReason(reference, matches.size())));
            return;
        }
        SlotField match = matches.getFirst();
        field.put("fieldCode", match.code());
        resolveValue(field, definition, match, conditions, fieldIndex, unresolved);
    }

    private void resolveValue(ObjectNode field, SlotDefinition owner, SlotField fieldDefinition,
                              ObjectNode conditions, int fieldIndex, List<UnresolvedReference> unresolved) {
        if (!fieldDefinition.optionValues().isEmpty()) {
            resolveEnumValue(field, fieldDefinition, fieldIndex, unresolved);
            return;
        }
        if (fieldDefinition.isKey()) {
            resolvePrimaryKeyValue(field, owner, conditions);
        }
    }

    private void resolvePrimaryKeyValue(ObjectNode field, SlotDefinition definition, ObjectNode conditions) {
        if (!canResolvePrimaryKeyValue(field, definition)) {
            return;
        }
        ConfigDefinition authoritative = this.definitionService.findByCode(definition.code());
        if (authoritative == null || authoritative.getLevel() == null) {
            return;
        }
        ConfigLevel level = authoritative.getLevel();
        String scopeKey = level == ConfigLevel.GLOBAL ? null : uniqueScopeKey(conditions);
        if (level != ConfigLevel.GLOBAL && scopeKey == null) {
            return;
        }
        String original = field.path("value").asText();
        Optional<ConfigDataRow> exact = this.dataRowRepository.findRow(definition.code(), level.name(), scopeKey,
                original);
        if (exact.isPresent()) {
            field.put("value", authoritativeRowKey(exact.get()));
            return;
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(original.toUpperCase(Locale.ROOT));
        candidates.add(original.toLowerCase(Locale.ROOT));
        candidates.remove(original);
        LinkedHashSet<String> matchedRowKeys = new LinkedHashSet<>();
        for (String candidate : candidates) {
            Optional<ConfigDataRow> row = this.dataRowRepository.findRow(definition.code(), level.name(), scopeKey,
                    candidate);
            if (row.isPresent()) {
                matchedRowKeys.add(authoritativeRowKey(row.get()));
            }
        }
        if (matchedRowKeys.size() == 1) {
            field.put("value", matchedRowKeys.getFirst());
        }
    }

    private boolean canResolvePrimaryKeyValue(ObjectNode field, SlotDefinition definition) {
        JsonNode value = field.get("value");
        return value != null && value.isTextual() && "EQ".equals(field.path("operator").asText())
                && definition.keyFields().size() == 1 && this.definitionService != null && this.dataRowRepository != null;
    }

    private static String uniqueScopeKey(ObjectNode conditions) {
        JsonNode scopeKeys = conditions.get("scopeKeys");
        if (scopeKeys == null || !scopeKeys.isArray() || scopeKeys.size() != 1
                || !scopeKeys.get(0).isTextual() || StringUtils.isBlank(scopeKeys.get(0).asText())) {
            return null;
        }
        return scopeKeys.get(0).asText();
    }

    private static String authoritativeRowKey(ConfigDataRow row) {
        if (row.getRowKey() == null) {
            throw new IllegalStateException("primary key row has no authoritative rowKey");
        }
        return row.getRowKey();
    }

    private static boolean isFieldMatch(String reference, SlotField field) {
        return field != null && (StringUtils.equalsIgnoreCase(reference, field.code())
                || StringUtils.equals(StringUtils.trim(reference), StringUtils.trim(field.label())));
    }

    private void resolveEnumValue(ObjectNode field, SlotField definition, int fieldIndex,
                                  List<UnresolvedReference> unresolved) {
        if (definition.optionValues().isEmpty()) {
            return;
        }
        JsonNode value = field.get("value");
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray() && "IN".equals(field.path("operator").asText())) {
            resolveEnumArray(field, (ArrayNode) value, definition, fieldIndex, unresolved);
            return;
        }
        resolveScalarEnum(field, value.asText(), definition, fieldIndex, unresolved);
    }

    private void resolveScalarEnum(ObjectNode field, String reference, SlotField definition, int fieldIndex,
                                   List<UnresolvedReference> unresolved) {
        List<String> matches = optionMatches(reference, definition.optionValues());
        if (matches.size() == 1) {
            field.put("value", matches.getFirst());
            return;
        }
        unresolved.add(unresolved(Kind.ENUM_VALUE, 0, fieldIndex, reference, definition.optionValues(),
                matchReason(reference, matches.size())));
    }

    private void resolveEnumArray(ObjectNode field, ArrayNode values, SlotField definition, int fieldIndex,
                                  List<UnresolvedReference> unresolved) {
        String original = values.toString();
        List<String> resolvedValues = new ArrayList<>();
        Reason reason = null;
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isTextual()) {
                throw new IllegalArgumentException("IN enum values must be strings");
            }
            List<String> matches = optionMatches(value.asText(), definition.optionValues());
            if (matches.size() == 1) {
                values.set(index, this.mapper.getNodeFactory().textNode(matches.getFirst()));
                resolvedValues.add(matches.getFirst());
            }
            else if (reason == null) {
                reason = matchReason(value.asText(), matches.size());
            }
        }
        if (reason == null) {
            return;
        }
        unresolved.add(new UnresolvedReference(Kind.ENUM_VALUE, 0, fieldIndex, original,
                definition.optionValues(), reason, true, resolvedValues.stream().distinct().toList()));
    }

    private static List<String> optionMatches(String reference, List<String> options) {
        List<String> exact = options.stream().filter(reference::equals).toList();
        return exact.isEmpty() ? options.stream().filter(option -> StringUtils.equalsIgnoreCase(reference, option)).toList()
                : exact;
    }

    private static Reason matchReason(String reference, int matchCount) {
        if (StringUtils.isBlank(reference)) {
            return Reason.BLANK;
        }
        return matchCount > 1 ? Reason.AMBIGUOUS : Reason.NOT_FOUND;
    }

    private static UnresolvedReference unresolvedField(int fieldIndex, String reference, Reason reason) {
        return unresolved(Kind.FIELD, 0, fieldIndex, reference, List.of(), reason);
    }

    private static UnresolvedReference unresolved(Kind kind, int definitionIndex, int fieldIndex, String reference,
                                                  List<String> options, Reason reason) {
        return new UnresolvedReference(kind, definitionIndex, fieldIndex, reference, options, reason, false, List.of());
    }

    public enum Status {
        RESOLVED,
        UNRESOLVED,
        FAILED
    }

    public enum Kind {
        DEFINITION,
        FIELD,
        ENUM_VALUE
    }

    public record Resolution(Status status, String canonicalArguments, List<UnresolvedReference> unresolved,
                             String failureCode) {
        public Resolution {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }
    }

    public enum Reason {
        NOT_FOUND,
        AMBIGUOUS,
        BLANK,
        NO_UNIQUE_OWNER,
        NO_UNIQUE_KEY
    }

    public record UnresolvedReference(Kind kind, int definitionIndex, int fieldIndex, String reference,
                                      List<String> options, Reason reason, boolean isArray,
                                      List<String> resolvedValues) {
        public UnresolvedReference(Kind kind, int definitionIndex, int fieldIndex, String reference,
                                   List<String> options) {
            this(kind, definitionIndex, fieldIndex, reference, options, Reason.NOT_FOUND, false, List.of());
        }

        public UnresolvedReference {
            options = options == null ? List.of() : List.copyOf(options);
            resolvedValues = resolvedValues == null ? List.of() : List.copyOf(resolvedValues);
        }
    }
}
