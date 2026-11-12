package com.example.configmgr.ai.tools;

import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobStartStructuredFactTest {

    @Test
    void startExportWritesRealJobIdIntoToolContext() {
        assertJobFact("start_export", Job.JobType.EXPORT, 101L);
    }

    @Test
    void startPrecheckWritesRealJobIdIntoToolContext() {
        assertJobFact("start_precheck", Job.JobType.PRECHECK, 102L);
    }

    @Test
    void startImportWritesRealJobIdIntoToolContext() {
        assertJobFact("start_import", Job.JobType.IMPORT, 103L);
    }

    @Test
    void startPublishWritesRealJobIdIntoToolContext() {
        assertJobFact("start_publish", Job.JobType.PUBLISH, 104L);
    }

    private static void assertJobFact(String toolName, Job.JobType type, long jobId) {
        Job job = new Job();
        job.setId(jobId);
        job.setTaskId(7L);
        job.setJobType(type);
        JobService jobService = mock(JobService.class);
        when(jobService.createAndStart(7L, type)).thenReturn(job);
        AiTools tools = new AiTools(mock(DefinitionService.class), mock(TaskService.class), jobService);
        ToolCallback callback = java.util.Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                .getToolCallbacks())
                .filter(candidate -> toolName.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        AtomicReference<Long> createdJobId = new AtomicReference<>();
        Map<String, Object> context = new HashMap<>();
        context.put("createdJobId", createdJobId);

        String receipt = callback.call("{\"taskId\":7}", new ToolContext(context));

        assertThat(createdJobId.get()).as("真实 Job.id 必须进入结构化执行事实，而不是只留在回执文本").isEqualTo(jobId);
        assertThat(receipt).contains("作业 #" + jobId);
    }
}
