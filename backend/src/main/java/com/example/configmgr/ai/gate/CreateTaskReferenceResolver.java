package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.SlotDefinition.SlotField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class CreateTaskReferenceResolver {

    public static final String UNRESOLVED_CODE = "CREATE_TASK_REFERENCES_UNRESOLVED";

    public static final String FAILED_CODE = "CREATE_TASK_REFERENCE_RESOLUTION_FAILED";

    private final DefinitionCatalog catalog;

    private final ObjectMapper mapper;

    public CreateTaskReferenceResolver(DefinitionCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
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
                unresolved.add(new UnresolvedReference(Kind.DEFINITION, index, -1, null, List.of()));
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
                unresolved.add(new UnresolvedReference(Kind.DEFINITION, index, -1, reference, List.of()));
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
        if (definitions.size() != 1) {
            addFieldsWithoutUniqueDefinition(fieldArray, unresolved);
            return;
        }
        SlotDefinition definition = definitions.getFirst();
        for (int index = 0; index < fieldArray.size(); index++) {
            if (!(fieldArray.get(index) instanceof ObjectNode field)) {
                throw new IllegalArgumentException("conditions.fields entries must be objects");
            }
            resolveField(field, definition, index, unresolved);
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
            unresolved.add(new UnresolvedReference(Kind.FIELD, -1, index, reference, List.of()));
        }
    }

    private void resolveField(ObjectNode field, SlotDefinition definition, int fieldIndex,
                              List<UnresolvedReference> unresolved) {
        JsonNode referenceNode = field.get("fieldCode");
        if (referenceNode != null && referenceNode.isNull()) {
            Optional<SlotField> key = definition.keyField();
            if (key.isPresent()) {
                field.put("fieldCode", key.get().code());
                resolveEnumValue(field, key.get(), fieldIndex, unresolved);
            }
            else {
                unresolved.add(unresolvedField(fieldIndex, null));
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
            unresolved.add(unresolvedField(fieldIndex, reference));
            return;
        }
        SlotField match = matches.getFirst();
        field.put("fieldCode", match.code());
        resolveEnumValue(field, match, fieldIndex, unresolved);
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
        String reference = value.asText();
        List<String> exact = definition.optionValues().stream().filter(reference::equals).toList();
        List<String> matches = exact.isEmpty()
                ? definition.optionValues().stream().filter(option -> StringUtils.equalsIgnoreCase(reference, option)).toList()
                : exact;
        if (matches.size() == 1) {
            field.put("value", matches.getFirst());
            return;
        }
        unresolved.add(new UnresolvedReference(Kind.ENUM_VALUE, 0, fieldIndex, reference,
                definition.optionValues()));
    }

    private static UnresolvedReference unresolvedField(int fieldIndex, String reference) {
        return new UnresolvedReference(Kind.FIELD, 0, fieldIndex, reference, List.of());
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

    public record UnresolvedReference(Kind kind, int definitionIndex, int fieldIndex, String reference,
                                      List<String> options) {
        public UnresolvedReference {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
