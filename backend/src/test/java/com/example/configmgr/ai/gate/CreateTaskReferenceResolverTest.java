package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Kind;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Reason;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Status;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.SlotDefinition.SlotField;
import com.example.configmgr.ai.tools.AiTools;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigDefinition.ConfigLevel;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CreateTaskReferenceResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesNamesAndInfersSinglePrimaryKey() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));

        CreateTaskReferenceResolver.Resolution byName = resolver.resolve("""
                {"type":"EXPORT","defCodes":[" 货币字典 "],"conditions":{"fields":[{"fieldCode":null,"operator":"EQ","value":"CNY"}]}}
                """);
        assertThat(byName.status()).isEqualTo(Status.RESOLVED);
        JsonNode canonical = read(byName.canonicalArguments());
        assertThat(canonical.path("defCodes")).containsExactly(json("CURRENCY"));
        assertThat(canonical.path("conditions").path("fields").get(0).path("fieldCode").asText()).isEqualTo("code");
        assertThat(byName.unresolved()).isEmpty();
        assertThat(byName.failureCode()).isNull();

        CreateTaskReferenceResolver.Resolution byCode = resolver.resolve("""
                {"type":"EXPORT","defCodes":["currency"],"conditions":{}}
                """);
        assertThat(byCode.status()).isEqualTo(Status.RESOLVED);
        assertThat(read(byCode.canonicalArguments()).path("defCodes").get(0).asText()).isEqualTo("CURRENCY");
    }

    @Test
    void preservesNonEnumValueAndCanonicalizesEnumOnlyOnUniqueMatch() {
        SlotDefinition definition = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("code", "货币代码", true, List.of("CNY", "USD")),
                new SlotField("note", "备注", false, List.of())));
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(definition));

        CreateTaskReferenceResolver.Resolution resolved = resolver.resolve("""
                {"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"fields":[{"fieldCode":" 货币代码 ","operator":"EQ","value":"cny"},{"fieldCode":"note","operator":"EQ","value":"  cNy  "}]}}
                """);
        assertThat(resolved.status()).isEqualTo(Status.RESOLVED);
        JsonNode fields = read(resolved.canonicalArguments()).path("conditions").path("fields");
        assertThat(fields.get(0).path("fieldCode").asText()).isEqualTo("code");
        assertThat(fields.get(0).path("value").asText()).isEqualTo("CNY");
        assertThat(fields.get(1).path("value").asText()).isEqualTo("  cNy  ");

        SlotDefinition ambiguousOptions = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("code", "货币代码", true, List.of("CNY", "cny"))));
        CreateTaskReferenceResolver.Resolution unresolved = resolver(() -> List.of(ambiguousOptions)).resolve("""
                {"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"CnY"}]}}
                """);
        assertThat(unresolved.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(unresolved.unresolved()).singleElement().satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(Kind.ENUM_VALUE);
            assertThat(reference.definitionIndex()).isZero();
            assertThat(reference.fieldIndex()).isZero();
            assertThat(reference.reference()).isEqualTo("CnY");
            assertThat(reference.options()).containsExactly("CNY", "cny");
            assertThat(reference.reason()).isEqualTo(Reason.AMBIGUOUS);
        });
    }

    @Test
    void resolvesMixedCaseInArrayElements() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));

        CreateTaskReferenceResolver.Resolution resolved = resolver.resolve("""
                {"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"fields":[{"fieldCode":"code","operator":"IN","value":["cny","Usd"]}]}}
                """);

        assertThat(resolved.status()).isEqualTo(Status.RESOLVED);
        JsonNode value = read(resolved.canonicalArguments()).path("conditions").path("fields").get(0).path("value");
        assertThat(value.isArray()).isTrue();
        assertThat(value).containsExactly(json("CNY"), json("USD"));
    }

    @Test
    void nonInArrayIsNotResolvedAsEnumValues() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":[\"cny\"]}]}}";

        CreateTaskReferenceResolver.Resolution unresolved = resolver.resolve(arguments);

        assertThat(unresolved.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(unresolved.canonicalArguments()).isEqualTo(arguments);
        assertThat(unresolved.unresolved()).singleElement()
                .satisfies(reference -> assertThat(reference.kind()).isEqualTo(Kind.ENUM_VALUE));
    }

    @Test
    void zeroMatchHasNotFoundReason() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));

        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"不存在\"],\"conditions\":{}}",
                Kind.DEFINITION, "不存在", Reason.NOT_FOUND);
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"缺失\",\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, "缺失", Reason.NOT_FOUND);
    }

    @Test
    void multipleMatchesHaveAmbiguousReason() {
        SlotDefinition sharedOne = new SlotDefinition("ONE", "共享", List.of(
                new SlotField("code", "代码", true, List.of())));
        SlotDefinition sharedTwo = new SlotDefinition("TWO", "共享", List.of(
                new SlotField("code", "代码", true, List.of())));
        SlotDefinition fields = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("first", "重复", true, List.of()),
                new SlotField("second", "重复", true, List.of())));
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(sharedOne, sharedTwo, fields));

        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"共享\"],\"conditions\":{}}",
                Kind.DEFINITION, "共享", Reason.AMBIGUOUS);
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"重复\",\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, "重复", Reason.AMBIGUOUS);
    }

    @Test
    void blankReferenceHasBlankReason() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));

        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"  \"],\"conditions\":{}}",
                Kind.DEFINITION, "  ", Reason.BLANK);
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[null],\"conditions\":{}}",
                Kind.DEFINITION, null, Reason.BLANK);
    }

    @Test
    void nullFieldWithoutUniqueKeyHasNoUniqueKeyReason() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("first", "第一", true, List.of()), new SlotField("second", "第二", true, List.of())))));

        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":null,\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, null, Reason.NO_UNIQUE_KEY);
    }

    @Test
    void canonicalizesUniquePrimaryKeyFromBoundedCaseCandidates() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.GLOBAL));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cNy")).thenReturn(Optional.empty());
        when(rows.findRow("CURRENCY", "GLOBAL", null, "CNY"))
                .thenReturn(Optional.of(row(ConfigLevel.GLOBAL, null, "CNY")));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cny")).thenReturn(Optional.empty());

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve("""
                {"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"cNy"}]}}
                """);

        assertThat(result.status()).isEqualTo(Status.RESOLVED);
        JsonNode field = read(result.canonicalArguments()).path("conditions").path("fields").get(0);
        assertThat(field.path("fieldCode").asText()).isEqualTo("code");
        assertThat(field.path("value").asText()).isEqualTo("CNY");
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "cNy");
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "CNY");
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "cny");
        verifyNoMoreInteractions(rows);
    }

    @Test
    void zeroPrimaryKeyRowsPreserveOriginalValue() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.GLOBAL));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cny")).thenReturn(Optional.empty());
        when(rows.findRow("CURRENCY", "GLOBAL", null, "CNY")).thenReturn(Optional.empty());
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "cny");
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "CNY");
        verifyNoMoreInteractions(rows);
    }

    @Test
    void multiplePrimaryKeyRowsPreserveOriginalValueWithoutChoosingCase() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.GLOBAL));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cNy")).thenReturn(Optional.empty());
        when(rows.findRow("CURRENCY", "GLOBAL", null, "CNY"))
                .thenReturn(Optional.of(row(ConfigLevel.GLOBAL, null, "CNY")));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cny"))
                .thenReturn(Optional.of(row(ConfigLevel.GLOBAL, null, "cny")));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cNy\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
    }

    @Test
    void compositePrimaryKeyPreservesOriginalValueWithoutQueries() {
        SlotDefinition composite = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("code", "货币代码", true, List.of()),
                new SlotField("region", "地区", true, List.of())));
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(composite, definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void numericPrimaryKeyPreservesOriginalValueWithoutQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":1}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void nonGlobalPrimaryKeyWithoutScopePreservesOriginalValue() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.REGION));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verify(definitions).findByCode("CURRENCY");
        verifyNoInteractions(rows);
    }

    @Test
    void nonGlobalPrimaryKeyUsesSingleProvidedScopeKey() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.REGION));
        when(rows.findRow("CURRENCY", "REGION", "R1", "cny")).thenReturn(Optional.empty());
        when(rows.findRow("CURRENCY", "REGION", "R1", "CNY"))
                .thenReturn(Optional.of(row(ConfigLevel.REGION, "R1", "CNY")));

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve("""
                {"type":"EXPORT","defCodes":["CURRENCY"],"conditions":{"scopeKeys":["R1"],"fields":[{"fieldCode":"code","operator":"EQ","value":"cny"}]}}
                """);

        assertThat(result.status()).isEqualTo(Status.RESOLVED);
        JsonNode condition = read(result.canonicalArguments()).path("conditions");
        assertThat(condition.path("scopeKeys")).containsExactly(json("R1"));
        assertThat(condition.path("fields").get(0).path("value").asText()).isEqualTo("CNY");
        verify(rows).findRow("CURRENCY", "REGION", "R1", "cny");
        verify(rows).findRow("CURRENCY", "REGION", "R1", "CNY");
        verifyNoMoreInteractions(rows);
    }

    @Test
    void nonEqPrimaryKeyPreservesOriginalValueWithoutQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"NE\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void nonEnumInArrayPreservesOriginalValuesWithoutQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"IN\",\"value\":[\"cny\",\"Usd\"]}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void explicitNullPrimaryKeyValuePreservesOriginalWithoutQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":null}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void booleanPrimaryKeyPreservesOriginalValueWithoutQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":true}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verifyNoInteractions(definitions, rows);
    }

    @Test
    void multipleNonGlobalScopesPreserveOriginalValueWithoutRowQueries() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.REGION));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"scopeKeys\":[\"R1\",\"R2\"],\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verify(definitions).findByCode("CURRENCY");
        verifyNoInteractions(rows);
    }

    @Test
    void exactOriginalPrimaryKeyRowSkipsCaseCandidates() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.GLOBAL));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cny"))
                .thenReturn(Optional.of(row(ConfigLevel.GLOBAL, null, "cny")));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "cny");
        verifyNoMoreInteractions(rows);
    }

    @Test
    void nullFieldPlaceholderKeepsKeyInferenceWithoutVerificationDependencies() {
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":null,\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = resolver(() -> List.of(currency())).resolve(arguments);

        assertThat(result.status()).isEqualTo(Status.RESOLVED);
        JsonNode field = read(result.canonicalArguments()).path("conditions").path("fields").get(0);
        assertThat(field.path("fieldCode").asText()).isEqualTo("code");
        assertThat(field.path("value").asText()).isEqualTo("cny");
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void primaryKeyRepositoryExceptionFailsClosed() {
        DefinitionService definitions = mock(DefinitionService.class);
        ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);
        when(definitions.findByCode("CURRENCY")).thenReturn(definition(ConfigLevel.GLOBAL));
        when(rows.findRow("CURRENCY", "GLOBAL", null, "cny")).thenThrow(new IllegalStateException("row store unavailable"));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = verifiedResolver(currency(), definitions, rows).resolve(arguments);

        assertFailedUnchanged(result, arguments);
        verify(rows).findRow("CURRENCY", "GLOBAL", null, "cny");
        verifyNoMoreInteractions(rows);
    }

    @Test
    void missingRowVerificationDependenciesPreserveOriginalValue() {
        SlotDefinition definition = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("code", "货币代码", true, List.of())));
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";

        CreateTaskReferenceResolver.Resolution result = resolver(() -> List.of(definition)).resolve(arguments);

        assertResolvedUnchanged(result, arguments);
    }

    @Test
    void catalogFailureStaysFailed() {
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}";

        CreateTaskReferenceResolver.Resolution failed = resolver(() -> {
            throw new IllegalStateException("catalog unavailable");
        }).resolve(original);

        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo("CREATE_TASK_REFERENCE_RESOLUTION_FAILED");
        assertThat(failed.canonicalArguments()).isEqualTo(original);
        assertThat(failed.unresolved()).isEmpty();
    }

    @Test
    void preservesMultiConfigEmptyConditionsAndScopeKeys() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD"),
                new SlotDefinition("FX_RATE", "汇率", List.of())));
        String multi = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\",\"fx_rate\"],\"conditions\":{}}";

        CreateTaskReferenceResolver.Resolution resolvedMulti = resolver.resolve(multi);
        assertThat(resolvedMulti.status()).isEqualTo(Status.RESOLVED);
        JsonNode multiJson = read(resolvedMulti.canonicalArguments());
        assertThat(multiJson.path("defCodes")).containsExactly(json("CURRENCY"), json("FX_RATE"));
        assertThat(multiJson.path("conditions").isObject()).isTrue();
        assertThat(multiJson.path("conditions").isEmpty()).isTrue();

        String scoped = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"scopeKeys\":[\" REGION_A \"],\"fields\":[]}}";
        CreateTaskReferenceResolver.Resolution resolvedScoped = resolver.resolve(scoped);
        assertThat(resolvedScoped.status()).isEqualTo(Status.RESOLVED);
        assertThat(resolvedScoped.canonicalArguments()).isEqualTo(scoped);
        assertThat(read(resolvedScoped.canonicalArguments()).path("conditions").path("scopeKeys").get(0).asText())
                .isEqualTo(" REGION_A ");
    }

    @Test
    void ownerlessFieldsCollectInsertableDefinitionAndField() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD")));
        String withoutDefinition = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";

        CreateTaskReferenceResolver.Resolution missingOwner = resolver.resolve(withoutDefinition);

        assertThat(missingOwner.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(missingOwner.canonicalArguments()).isEqualTo(withoutDefinition);
        assertThat(missingOwner.unresolved()).satisfiesExactly(definition -> {
            assertThat(definition.kind()).isEqualTo(Kind.DEFINITION);
            assertThat(definition.definitionIndex()).isZero();
            assertThat(definition.fieldIndex()).isEqualTo(-1);
            assertThat(definition.reference()).isNull();
            assertThat(definition.reason()).isEqualTo(Reason.NO_UNIQUE_OWNER);
        }, field -> {
            assertThat(field.kind()).isEqualTo(Kind.FIELD);
            assertThat(field.definitionIndex()).isZero();
            assertThat(field.fieldIndex()).isZero();
            assertThat(field.reference()).isEqualTo("货币代码");
            assertThat(field.reason()).isEqualTo(Reason.NO_UNIQUE_OWNER);
        });
    }

    @Test
    void multipleDefinitionsWithNonEmptyFieldsFailImmediately() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD"),
                new SlotDefinition("FX_RATE", "汇率", List.of())));
        String multipleDefinitions = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\",\"FX_RATE\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";

        CreateTaskReferenceResolver.Resolution ambiguousOwner = resolver.resolve(multipleDefinitions);

        assertThat(ambiguousOwner.status()).isEqualTo(Status.FAILED);
        assertThat(ambiguousOwner.failureCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(ambiguousOwner.canonicalArguments()).isEqualTo(multipleDefinitions);
        assertThat(ambiguousOwner.unresolved()).isEmpty();
    }

    @Test
    void nonObjectConditionsFailResolution() {
        CreateTaskReferenceResolver resolver = resolver(List::of);
        String array = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":[]}";
        String scalar = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":\"invalid\"}";

        CreateTaskReferenceResolver.Resolution arrayResult = resolver.resolve(array);
        assertThat(arrayResult.status()).isEqualTo(Status.FAILED);
        assertThat(arrayResult.failureCode()).isEqualTo("CREATE_TASK_REFERENCE_RESOLUTION_FAILED");
        assertThat(arrayResult.canonicalArguments()).isEqualTo(array);

        CreateTaskReferenceResolver.Resolution scalarResult = resolver.resolve(scalar);
        assertThat(scalarResult.status()).isEqualTo(Status.FAILED);
        assertThat(scalarResult.failureCode()).isEqualTo("CREATE_TASK_REFERENCE_RESOLUTION_FAILED");
        assertThat(scalarResult.canonicalArguments()).isEqualTo(scalar);
    }

    @Test
    void defCodesMissingOrNullFailButExplicitEmptyArrayResolves() {
        CreateTaskReferenceResolver resolver = resolver(List::of);
        String missing = "{\"type\":\"EXPORT\",\"conditions\":{}}";
        String explicitNull = "{\"type\":\"EXPORT\",\"defCodes\":null,\"conditions\":{}}";
        String explicitEmpty = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}";

        CreateTaskReferenceResolver.Resolution missingResult = resolver.resolve(missing);
        assertThat(missingResult.status()).isEqualTo(Status.FAILED);
        assertThat(missingResult.failureCode()).isEqualTo("CREATE_TASK_REFERENCE_RESOLUTION_FAILED");
        assertThat(missingResult.canonicalArguments()).isEqualTo(missing);

        CreateTaskReferenceResolver.Resolution nullResult = resolver.resolve(explicitNull);
        assertThat(nullResult.status()).isEqualTo(Status.FAILED);
        assertThat(nullResult.failureCode()).isEqualTo("CREATE_TASK_REFERENCE_RESOLUTION_FAILED");
        assertThat(nullResult.canonicalArguments()).isEqualTo(explicitNull);

        CreateTaskReferenceResolver.Resolution emptyResult = resolver.resolve(explicitEmpty);
        assertThat(emptyResult.status()).isEqualTo(Status.RESOLVED);
        assertThat(emptyResult.canonicalArguments()).isEqualTo(explicitEmpty);
        assertThat(emptyResult.unresolved()).isEmpty();
    }

    @Test
    void createTaskRequiresDefCodesArrayInToolContract() throws NoSuchMethodException {
        var method = AiTools.class.getDeclaredMethod("createTask", String.class, String.class, String[].class,
                AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class, ToolContext.class);
        ToolParam defCodes = method.getParameters()[2].getAnnotation(ToolParam.class);
        Tool tool = method.getAnnotation(Tool.class);

        assertThat(defCodes.required()).isTrue();
        assertThat(defCodes.description()).contains("必传").doesNotContain("可选");
        assertThat(tool.description()).contains("必传 defCodes 数组").doesNotContain("可选 defCodes");
    }

    private CreateTaskReferenceResolver resolver(DefinitionCatalog catalog) {
        return new CreateTaskReferenceResolver(catalog, this.mapper);
    }

    private CreateTaskReferenceResolver verifiedResolver(SlotDefinition definition, DefinitionService definitions,
            ConfigDataRowRepository rows) {
        return new CreateTaskReferenceResolver(() -> List.of(definition), this.mapper, definitions, rows);
    }

    private void assertResolvedUnchanged(CreateTaskReferenceResolver.Resolution result, String arguments) {
        assertThat(result.status()).isEqualTo(Status.RESOLVED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.canonicalArguments()).isEqualTo(arguments);
        assertThat(result.unresolved()).isEmpty();
    }

    private void assertFailedUnchanged(CreateTaskReferenceResolver.Resolution result, String arguments) {
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(result.canonicalArguments()).isEqualTo(arguments);
        assertThat(result.unresolved()).isEmpty();
    }

    private static ConfigDefinition definition(ConfigLevel level) {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("CURRENCY");
        definition.setName("货币字典");
        definition.setLevel(level);
        return definition;
    }

    private static ConfigDataRow row(ConfigLevel level, String scopeKey, String rowKey) {
        ConfigDataRow row = new ConfigDataRow();
        row.setDefCode("CURRENCY");
        row.setScopeType(level.name());
        row.setScopeKey(scopeKey);
        row.setRowKey(rowKey);
        row.setDataJson("{\"code\":\"" + rowKey + "\"}");
        return row;
    }

    private void assertUnresolved(CreateTaskReferenceResolver resolver, String arguments, Kind kind, String reference,
            Reason reason) {
        CreateTaskReferenceResolver.Resolution resolution = resolver.resolve(arguments);
        assertThat(resolution.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(resolution.failureCode()).isEqualTo("CREATE_TASK_REFERENCES_UNRESOLVED");
        assertThat(resolution.canonicalArguments()).isEqualTo(arguments);
        assertThat(resolution.unresolved()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo(kind);
            assertThat(item.reference()).isEqualTo(reference);
            assertThat(item.reason()).isEqualTo(reason);
            if (kind == Kind.DEFINITION) {
                assertThat(item.definitionIndex()).isZero();
                assertThat(item.fieldIndex()).isEqualTo(-1);
            }
            else {
                assertThat(item.definitionIndex()).isZero();
                assertThat(item.fieldIndex()).isZero();
            }
        });
    }

    private static SlotDefinition currency(String... options) {
        return new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("code", "货币代码", true, List.of(options))));
    }

    private JsonNode read(String json) {
        try {
            return this.mapper.readTree(json);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode json(String value) {
        return this.mapper.getNodeFactory().textNode(value);
    }
}
