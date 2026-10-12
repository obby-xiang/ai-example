package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-152（取消/迟到终态写竞态）服务层直测：任务 CANCELLED 后执行器迟到提交 COMPLETED，
 * {@link JobTerminalWriter#complete} 经 {@link JobProgressService#finish} 的先到者胜守卫识别
 * 「作业已收终态」并丢弃，**不得**把任务行翻回 COMPLETED —— 否则终态守卫会落入放行分支
 * （CI E2E MAJOR-2 四连红唯一失败面）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r152-late-completion;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r152-late-completion-files"
})
class JobTerminalWriterLateCompletionTest {

    @Autowired
    private JobTerminalWriter terminalWriter;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;

    @Test
    void lateCompletionAfterCancelDoesNotFlipTaskBackToCompleted() {
        long taskId = cancelledExportTask();

        // 执行器迟到提交 COMPLETED：finish 识别作业已 CANCELLED → 丢弃，任务态不得被翻回 COMPLETED
        Job cancelledJob = jobRepository.findByTaskId(taskId).get(0);
        terminalWriter.complete(cancelledJob, Job.JobStatus.COMPLETED, 0, 0, 0, 0, Map.of());

        Task after = taskRepository.findById(taskId).orElseThrow();
        assertThat(after.getStatus()).as("迟到 COMPLETED 不得把已 CANCELLED 任务翻回")
                .isEqualTo(Task.TaskStatus.CANCELLED);
    }

    private long cancelledExportTask() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R152-迟到终态");
        task.setStatus(Task.TaskStatus.CANCELLED);
        taskRepository.save(task);

        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.CANCELLED);
        jobRepository.save(job);
        return task.getId();
    }
}
