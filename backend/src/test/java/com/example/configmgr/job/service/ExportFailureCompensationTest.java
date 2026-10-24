package com.example.configmgr.job.service;

import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * I-4 端到端回归：导出成功路径先写条目 COMPLETED，随后写盘失败。
 *
 * <p>旧行为（ITEM_OPEN 门禁直接挡 catch 的 FAILED 补偿）会留下
 * “作业 FAILED、job_item COMPLETED、无导出行”的自相矛盾；本用例在真实 H2 上驱动
 * {@link ExportJobRunner} 并让 {@link ExcelWriter} 抛 IO 异常，证明：
 * 条目被补偿降级为 FAILED、任务项 FAILED、且没有任何 EXPORT 产物行落库。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:i4-export-failure;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/i4-export-failure-files"
})
class ExportFailureCompensationTest {

    private static final String SYS_PARAM = "SYS_PARAM";

    @MockitoBean
    private ExcelWriter excelWriter;

    @Autowired
    private ExportJobRunner exportJobRunner;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;

    @Test
    void failedExcelWriteDowngradesCompletedItemAndLeavesNoArtifact() throws Exception {
        Task task = taskService.create(Task.TaskType.EXPORT, "I4-导出写盘失败");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));

        doThrow(new IOException("disk full in test"))
                .when(this.excelWriter).write(any(), anyList(), anyString());

        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        exportJobRunner.run(job);

        Job storedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(storedJob.getStatus()).as("写盘失败 → 作业 FAILED")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(storedJob.getErrorCount()).isPositive();

        JobItem item = jobItemRepository.findByJobIdAndDefCode(job.getId(), SYS_PARAM).orElseThrow();
        assertThat(item.getStatus())
                .as("成功路径先写 COMPLETED，catch 必须补偿降级为 FAILED")
                .isEqualTo("FAILED");
        assertThat(item.getProcessed()).isPositive();
        assertThat(item.getTotal()).isPositive();

        TaskItem taskItem = taskItemRepository.findByTaskIdAndDefCode(task.getId(), SYS_PARAM).orElseThrow();
        assertThat(taskItem.getStatus()).isEqualTo("FAILED");

        assertThat(taskFileRepository.findByTaskIdAndDefCodeAndFileType(task.getId(), SYS_PARAM, "EXPORT"))
                .as("写盘失败不得留下 EXPORT 产物行（状态与产物一致）").isEmpty();
    }
}
