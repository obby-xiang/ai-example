package com.example.configmgr.job.service;

import com.example.configmgr.definition.entity.ConfigField;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class JobRunnerRowKeyTest {

    @Test
    void importRunnerJoinsCompositeRowKeyWithSeparator() {
        ImportJobRunner runner = mock(ImportJobRunner.class, CALLS_REAL_METHODS);

        String rowKey = ReflectionTestUtils.invokeMethod(runner, "buildRowKey", row(), keyFields());

        assertThat(rowKey).isEqualTo("HE|100");
    }

    @Test
    void precheckRunnerJoinsCompositeRowKeyWithSeparator() {
        PrecheckJobRunner runner = mock(PrecheckJobRunner.class, CALLS_REAL_METHODS);

        String rowKey = ReflectionTestUtils.invokeMethod(runner, "buildRowKey", row(), keyFields());

        assertThat(rowKey).isEqualTo("HE|100");
    }

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("regionCode", "HE");
        row.put("bandwidthMbps", "100");
        return row;
    }

    private static List<ConfigField> keyFields() {
        ConfigField region = new ConfigField();
        region.setCode("regionCode");
        ConfigField bandwidth = new ConfigField();
        bandwidth.setCode("bandwidthMbps");
        return List.of(region, bandwidth);
    }
}
