package com.example.configmgr.definition.service;

import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.repo.ConfigDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DependencyResolverTest {

    private final ConfigDefinitionRepository repository = mock(ConfigDefinitionRepository.class);
    private final DependencyResolver resolver = new DependencyResolver(repository);

    @Test
    void sortsDependenciesAndRejectsCyclesWithoutChangingEarlyReturns() {
        assertThat(resolver.sort(List.of())).isEmpty();
        verifyNoInteractions(repository);

        ConfigDefinition first = definition("FIRST", "SECOND");
        ConfigDefinition second = definition("SECOND", null);
        when(repository.findAllOrdered()).thenReturn(List.of(first, second));

        assertThat(resolver.sort(List.of("FIRST", "SECOND"))).containsExactly("SECOND", "FIRST");
        assertThat(resolver.sort(List.of("MISSING", "SECOND"))).containsExactly("MISSING", "SECOND");

        first.getFields().getFirst().setRefDefCode("SECOND");
        second.setFields(List.of(field("SECOND", "FIRST")));
        assertThatThrownBy(() -> resolver.sort(List.of("FIRST", "SECOND")))
                .isInstanceOf(CodedIllegalStateException.class)
                .hasMessageContaining("Circular dependency");
    }

    private static ConfigDefinition definition(String code, String refDefCode) {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode(code);
        definition.setFields(refDefCode == null ? List.of() : List.of(field(code, refDefCode)));
        return definition;
    }

    private static ConfigField field(String defCode, String refDefCode) {
        ConfigField field = new ConfigField();
        field.setDefCode(defCode);
        field.setCode("value");
        field.setRefDefCode(refDefCode);
        return field;
    }
}
