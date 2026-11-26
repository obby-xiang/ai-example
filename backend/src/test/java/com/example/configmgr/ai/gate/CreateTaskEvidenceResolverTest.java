package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateTaskEvidenceResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final DefinitionService definitions = mock(DefinitionService.class);

    private final ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);

    @Test
    void uniqueDefinitionAndRowKeyAreCompleted() throws Exception {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
        ConfigDataRow cny = row("CNY");
        when(this.rows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("GLOBAL"), org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cny), Pageable.ofSize(51), 1));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isPresent();
        JsonNode canonical = this.mapper.readTree(result.get());
        assertThat(canonical.path("defCodes")).containsExactly(this.mapper.readTree("\"CURRENCY\""));
        JsonNode field = canonical.path("conditions").path("fields").get(0);
        assertThat(field.path("fieldCode").asText()).isEqualTo("code");
        assertThat(field.path("operator").asText()).isEqualTo("EQ");
        assertThat(field.path("value").asText()).isEqualTo("CNY");
    }

    @Test
    void explicitEmptyDefinitionsDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
        when(this.rows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("GLOBAL"), org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row("CNY")), Pageable.ofSize(51), 1));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.definitions, this.rows);
    }

    @Test
    void multipleDefinitionsDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolverWithOther("货币");

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"未知配置\"],\"conditions\":{}}", "CURRENCY FX_RATE");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.definitions, this.rows);
    }

    @Test
    void multipleRowKeysDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
        when(this.rows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("GLOBAL"), org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row("CNY"), row("USD")), Pageable.ofSize(51), 2));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{}}", "cny usd 货币字典");

        assertThat(result).isEmpty();
    }

    @Test
    void nonPrimaryKeyDoesNotGuess() {
        SlotDefinition noKey = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotDefinition.SlotField("label", "标签", false, List.of())));
        CreateTaskEvidenceResolver resolver = resolver(noKey, definition(ConfigDefinition.ConfigLevel.GLOBAL));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void singleRegionScopeIsCompleted() throws Exception {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.REGION));
        ConfigDataRow cny = row("CNY");
        cny.setScopeType("REGION");
        cny.setScopeKey("R1");
        when(this.rows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("REGION"), org.mockito.ArgumentMatchers.eq("R1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cny), Pageable.ofSize(51), 1));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"scopeKeys\":[\"R1\"]}}",
                "cny货币字典");

        assertThat(result).isPresent();
        JsonNode canonical = this.mapper.readTree(result.get());
        assertThat(canonical.path("conditions").path("scopeKeys")).containsExactly(this.mapper.readTree("\"R1\""));
        assertThat(canonical.path("conditions").path("fields").get(0).path("value").asText()).isEqualTo("CNY");
    }

    @Test
    void missingScopeDoesNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.REGION));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{\"scopeKeys\":[]}}", "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void multipleScopesDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.REGION));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"scopeKeys\":[\"R1\",\"R2\"]}}",
                "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void blankReferencesDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"  \"],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void nullReferencesDoNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[null],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void nonEmptyConditionsAreNotOverwritten() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"USD\"}]}}",
                "cny货币字典");

        assertThat(result).isEmpty();
        verifyNoInteractions(this.rows);
    }

    @Test
    void pageOverflowDoesNotGuess() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
        when(this.rows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("GLOBAL"), org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row("CNY")), Pageable.ofSize(51), 51));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
    }

    @Test
    void repositoryFailureReturnsEmpty() {
        CreateTaskEvidenceResolver resolver = resolver(currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
        when(this.rows.findRowsInScopePaged(anyString(), anyString(), any(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        Optional<String> result = resolver.resolve(
                "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{}}", "cny货币字典");

        assertThat(result).isEmpty();
    }

    private CreateTaskEvidenceResolver resolver(SlotDefinition slot, ConfigDefinition authoritative) {
        return resolverWithOther("其他", slot, authoritative);
    }

    private CreateTaskEvidenceResolver resolverWithOther(String otherName) {
        return resolverWithOther(otherName, currency(), definition(ConfigDefinition.ConfigLevel.GLOBAL));
    }

    private CreateTaskEvidenceResolver resolverWithOther(String otherName, SlotDefinition slot,
                                                         ConfigDefinition authoritative) {
        DefinitionCatalog catalog = () -> List.of(slot, new SlotDefinition("FX_RATE", otherName, List.of(
                new SlotDefinition.SlotField("rate", "汇率", true, List.of()))));
        when(this.definitions.findByCode("CURRENCY")).thenReturn(authoritative);
        return new CreateTaskEvidenceResolver(catalog, this.mapper, this.definitions, this.rows, 50);
    }

    private static SlotDefinition currency() {
        return new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotDefinition.SlotField("code", "货币代码", true, List.of())));
    }

    private static ConfigDefinition definition(ConfigDefinition.ConfigLevel level) {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("CURRENCY");
        definition.setName("货币字典");
        definition.setLevel(level);
        return definition;
    }

    private static ConfigDataRow row(String rowKey) {
        ConfigDataRow row = new ConfigDataRow();
        row.setDefCode("CURRENCY");
        row.setScopeType("GLOBAL");
        row.setRowKey(rowKey);
        row.setDataJson("{\"code\":\"" + rowKey + "\"}");
        return row;
    }
}
