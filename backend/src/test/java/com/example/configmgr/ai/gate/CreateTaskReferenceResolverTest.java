package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Kind;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Status;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.SlotDefinition.SlotField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        });
    }

    @Test
    void distinguishesMissingAmbiguousBlankAndCatalogFailure() {
        SlotDefinition sharedOne = new SlotDefinition("ONE", "共享", List.of(
                new SlotField("code", "代码", true, List.of())));
        SlotDefinition sharedTwo = new SlotDefinition("TWO", "共享", List.of(
                new SlotField("code", "代码", true, List.of())));
        SlotDefinition fields = new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotField("first", "重复", true, List.of()),
                new SlotField("second", "重复", true, List.of())));
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(sharedOne, sharedTwo, fields));

        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"不存在\"],\"conditions\":{}}",
                Kind.DEFINITION, "不存在");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"共享\"],\"conditions\":{}}",
                Kind.DEFINITION, "共享");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"  \"],\"conditions\":{}}",
                Kind.DEFINITION, "  ");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[null],\"conditions\":{}}",
                Kind.DEFINITION, null);
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"导出CNY货币字典\"],\"conditions\":{}}",
                Kind.DEFINITION, "导出CNY货币字典");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"缺失\",\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, "缺失");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"重复\",\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, "重复");
        assertUnresolved(resolver, "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":null,\"operator\":\"EQ\",\"value\":\"x\"}]}}",
                Kind.FIELD, null);

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
    void nonEmptyFieldsRequireUniqueOwningDefinition() {
        CreateTaskReferenceResolver resolver = resolver(() -> List.of(currency("CNY", "USD"),
                new SlotDefinition("FX_RATE", "汇率", List.of())));
        String withoutDefinition = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{\"fields\":[{\"fieldCode\":null,\"operator\":\"EQ\",\"value\":\"CNY\"},{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"USD\"}]}}";

        CreateTaskReferenceResolver.Resolution missingOwner = resolver.resolve(withoutDefinition);
        assertThat(missingOwner.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(missingOwner.canonicalArguments()).isEqualTo(withoutDefinition);
        assertThat(missingOwner.unresolved()).satisfiesExactly(first -> {
            assertThat(first.kind()).isEqualTo(Kind.FIELD);
            assertThat(first.definitionIndex()).isEqualTo(-1);
            assertThat(first.fieldIndex()).isZero();
            assertThat(first.reference()).isNull();
        }, second -> {
            assertThat(second.kind()).isEqualTo(Kind.FIELD);
            assertThat(second.definitionIndex()).isEqualTo(-1);
            assertThat(second.fieldIndex()).isEqualTo(1);
            assertThat(second.reference()).isEqualTo("货币代码");
        });

        String multipleDefinitions = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\",\"FX_RATE\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        CreateTaskReferenceResolver.Resolution ambiguousOwner = resolver.resolve(multipleDefinitions);
        assertThat(ambiguousOwner.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(ambiguousOwner.canonicalArguments()).isEqualTo(multipleDefinitions);
        assertThat(ambiguousOwner.unresolved()).singleElement().satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(Kind.FIELD);
            assertThat(reference.definitionIndex()).isEqualTo(-1);
            assertThat(reference.fieldIndex()).isZero();
            assertThat(reference.reference()).isEqualTo("货币代码");
        });
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

    private CreateTaskReferenceResolver resolver(DefinitionCatalog catalog) {
        return new CreateTaskReferenceResolver(catalog, this.mapper);
    }

    private void assertUnresolved(CreateTaskReferenceResolver resolver, String arguments, Kind kind, String reference) {
        CreateTaskReferenceResolver.Resolution resolution = resolver.resolve(arguments);
        assertThat(resolution.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(resolution.failureCode()).isEqualTo("CREATE_TASK_REFERENCES_UNRESOLVED");
        assertThat(resolution.canonicalArguments()).isEqualTo(arguments);
        assertThat(resolution.unresolved()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo(kind);
            assertThat(item.reference()).isEqualTo(reference);
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
