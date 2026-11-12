package com.example.configmgr.ai.form;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefinitionFieldOptionSourceTest {

	private final DefinitionService definitions = mock(DefinitionService.class);

	private final FormOptionSource source = new FormOptionSource.DefinitionFieldOptionSource(this.definitions);

	@Test
	void knownDefinitionReturnsFieldsInDeclaredOrder() {
		ConfigDefinition definition = new ConfigDefinition();
		definition.setFields(List.of(field("currency_code", "币种编码"), field("status", null)));
		when(this.definitions.findByCode("CURRENCY")).thenReturn(definition);

		assertThat(this.source.options("CURRENCY")).containsExactly(
				Map.of("value", "currency_code", "label", "币种编码"),
				Map.of("value", "status", "label", "status"));
	}

	@Test
	void blankParentValueReturnsRealEmptyWithoutReading() {
		assertThat(this.source.options("  ")).isEmpty();
		verifyNoInteractions(this.definitions);
	}

	@Test
	void unknownDefinitionReturnsRealEmptyWithoutWarning() {
		when(this.definitions.findByCode("UNKNOWN")).thenThrow(
				com.example.configmgr.common.ResourceNotFoundException.of("Configuration definition", "UNKNOWN"));
		Logger logger = (Logger) LoggerFactory.getLogger(FormOptionSource.DefinitionFieldOptionSource.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			assertThat(this.source.options("UNKNOWN")).isEmpty();
			assertThat(logs.list).isEmpty();
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}
	}

	@Test
	void readFailureIsUnavailableAndLogsOnlySafeMetadata() {
		String parent = "PRIVATE_DEFINITION";
		IllegalStateException failure = new IllegalStateException("database secret");
		when(this.definitions.findByCode(parent)).thenThrow(failure);
		Logger logger = (Logger) LoggerFactory.getLogger(FormOptionSource.DefinitionFieldOptionSource.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			assertThatThrownBy(() -> this.source.options(parent))
				.isInstanceOf(CodedIllegalStateException.class)
				.hasFieldOrPropertyWithValue("code", ErrorCodes.FORM_OPTION_SOURCE_UNAVAILABLE)
				.hasCause(failure);
			assertThat(logs.list).hasSize(1);
			ILoggingEvent event = logs.list.getFirst();
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage())
				.contains("sourceId=definition_fields", "parentValueLength=" + parent.length())
				.doesNotContain(parent);
			assertThat(event.getThrowableProxy()).isNotNull();
		}
		finally {
			logger.detachAppender(logs);
			logs.stop();
		}
	}

	private static ConfigField field(String code, String label) {
		ConfigField field = new ConfigField();
		field.setCode(code);
		field.setLabel(label);
		return field;
	}
}
