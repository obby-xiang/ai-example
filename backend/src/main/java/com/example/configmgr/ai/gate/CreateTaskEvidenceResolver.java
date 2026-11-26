package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
public class CreateTaskEvidenceResolver {

    private static final int DEFAULT_MAX_SCAN_ROWS = 50;

    private static final int MAX_SCAN_ROWS_LIMIT = 200;

    private final DefinitionCatalog catalog;

    private final ObjectMapper mapper;

    private final DefinitionService definitionService;

    private final ConfigDataRowRepository dataRowRepository;

    private final int maxScanRows;

    @Autowired
    public CreateTaskEvidenceResolver(DefinitionCatalog catalog, ObjectMapper mapper,
                                      DefinitionService definitionService, ConfigDataRowRepository dataRowRepository) {
        this(catalog, mapper, definitionService, dataRowRepository, DEFAULT_MAX_SCAN_ROWS);
    }

    CreateTaskEvidenceResolver(DefinitionCatalog catalog, ObjectMapper mapper, DefinitionService definitionService,
                               ConfigDataRowRepository dataRowRepository, int maxScanRows) {
        if (maxScanRows < 1 || maxScanRows > MAX_SCAN_ROWS_LIMIT) {
            throw new IllegalArgumentException("maxScanRows must be between 1 and " + MAX_SCAN_ROWS_LIMIT);
        }
        this.catalog = catalog;
        this.mapper = mapper;
        this.definitionService = definitionService;
        this.dataRowRepository = dataRowRepository;
        this.maxScanRows = maxScanRows;
    }

    public Optional<String> resolve(String argumentsJson, String userText) {
        if (StringUtils.isBlank(argumentsJson) || StringUtils.isBlank(userText)) {
            return Optional.empty();
        }
        try {
            if (!(this.mapper.readTree(argumentsJson) instanceof ObjectNode root) || !canComplete(root)) {
                return Optional.empty();
            }
            Optional<SlotDefinition> definition = uniqueDefinition(root, userText);
            if (definition.isEmpty()) {
                return Optional.empty();
            }
            return complete(root, definition.get(), userText);
        }
        catch (Exception ex) {
            log.warn("could not resolve create_task evidence; keeping the existing correction path", ex);
            return Optional.empty();
        }
    }

    private boolean canComplete(ObjectNode root) {
        JsonNode conditions = root.get("conditions");
        if (conditions != null && !conditions.isNull() && (!conditions.isObject() || !conditions.isEmpty())) {
            return false;
        }
        JsonNode references = root.get("defCodes");
        if (references == null || references.isNull()) {
            return true;
        }
        if (!(references instanceof ArrayNode array) || array.size() > 1) {
            return false;
        }
        for (JsonNode reference : array) {
            if (!reference.isTextual() || StringUtils.isBlank(reference.asText())) {
                return false;
            }
        }
        return true;
    }

    private Optional<SlotDefinition> uniqueDefinition(ObjectNode root, String userText) {
        List<SlotDefinition> entries = this.catalog.list();
        if (entries == null) {
            return Optional.empty();
        }
        JsonNode references = root.get("defCodes");
        if (references instanceof ArrayNode array && array.size() == 1) {
            String reference = array.get(0).asText();
            List<SlotDefinition> exact = entries.stream()
                    .filter(entry -> isDefinitionMatch(reference, entry)).toList();
            if (exact.size() == 1) {
                return Optional.of(exact.getFirst());
            }
        }
        List<SlotDefinition> contained = entries.stream()
                .filter(entry -> containsIgnoreCase(userText, entry.code()) || containsIgnoreCase(userText, entry.name()))
                .toList();
        return contained.size() == 1 ? Optional.of(contained.getFirst()) : Optional.empty();
    }

    private Optional<String> complete(ObjectNode root, SlotDefinition definition, String userText) throws JsonProcessingException {
        ConfigDefinition authoritative = this.definitionService.findByCode(definition.code());
        if (authoritative == null || authoritative.getLevel() == null || definition.keyFields().size() != 1) {
            return Optional.empty();
        }
        String scopeKey = authoritative.getLevel() == ConfigDefinition.ConfigLevel.GLOBAL ? null : uniqueScopeKey(root);
        if (authoritative.getLevel() != ConfigDefinition.ConfigLevel.GLOBAL && scopeKey == null) {
            return Optional.empty();
        }
        Page<ConfigDataRow> page = this.dataRowRepository.findRowsInScopePaged(definition.code(),
                authoritative.getLevel().name(), scopeKey, PageRequest.of(0, this.maxScanRows + 1));
        if (page == null || page.getTotalElements() > this.maxScanRows || page.getContent() == null) {
            return Optional.empty();
        }
        List<String> matches = page.getContent().stream().filter(row -> row != null && containsToken(userText, row.getRowKey()))
                .map(ConfigDataRow::getRowKey).distinct().toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }
        root.putArray("defCodes").add(definition.code());
        ArrayNode fields = root.with("conditions").putArray("fields");
        SlotDefinition.SlotField key = definition.keyFields().getFirst();
        fields.addObject().put("fieldCode", key.code()).put("operator", "EQ").put("value", matches.getFirst());
        return Optional.of(this.mapper.writeValueAsString(root));
    }

    private static String uniqueScopeKey(ObjectNode root) {
        JsonNode scopeKeys = root.path("conditions").path("scopeKeys");
        return scopeKeys.isArray() && scopeKeys.size() == 1 && scopeKeys.get(0).isTextual()
                && StringUtils.isNotBlank(scopeKeys.get(0).asText()) ? scopeKeys.get(0).asText() : null;
    }

    private static boolean isDefinitionMatch(String reference, SlotDefinition definition) {
        return definition != null && (StringUtils.equalsIgnoreCase(reference, definition.code())
                || StringUtils.equals(StringUtils.trim(reference), StringUtils.trim(definition.name())));
    }

    private static boolean containsIgnoreCase(String text, String candidate) {
        return StringUtils.isNotBlank(candidate) && text.toLowerCase(Locale.ROOT)
                .contains(candidate.toLowerCase(Locale.ROOT));
    }

    private static boolean containsToken(String text, String candidate) {
        if (StringUtils.isBlank(candidate)) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = candidate.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < haystack.length()) {
            int match = haystack.indexOf(needle, from);
            if (match < 0) {
                return false;
            }
            if (isTokenBoundary(haystack, match - 1) && isTokenBoundary(haystack, match + needle.length())) {
                return true;
            }
            from = match + needle.length();
        }
        return false;
    }

    private static boolean isTokenBoundary(String text, int index) {
        return index < 0 || index >= text.length() || !isAsciiTokenCharacter(text.charAt(index));
    }

    private static boolean isAsciiTokenCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '_';
    }
}
