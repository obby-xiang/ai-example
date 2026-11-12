package com.example.configmgr.ai.tools;

import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiToolsControlFlowTest {

    private final DefinitionService definitionService = mock(DefinitionService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final AiTools tools = new AiTools(definitionService, taskService, mock(JobService.class));

    @Test
    void emptyCatalogTaskAndWorkspaceBranchesKeepTheirText() {
        when(definitionService.findAll()).thenReturn(List.of());
        when(taskService.findAll()).thenReturn(List.of());

        assertThat(tools.listConfigDefs(null)).isEqualTo(AiTools.LIST_CONFIG_DEFS_EMPTY_TEXT);
        assertThat(tools.listTasks()).isEqualTo(AiTools.LIST_TASKS_EMPTY_TEXT);
        assertThat(tools.getWorkspaceState(null)).isEqualTo("当前未打开任何任务，请先创建或选择一个任务");

        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("DOC");
        definition.setName("文档");
        definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        definition.setDescription("既有说明");
        definition.setFields(null);
        when(definitionService.findByCode("DOC")).thenReturn(definition);
        assertThat(tools.getConfigDef("DOC")).contains("说明: 既有说明");
    }
}
