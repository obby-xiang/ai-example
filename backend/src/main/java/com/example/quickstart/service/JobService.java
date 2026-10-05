package com.example.quickstart.service;

import com.example.quickstart.dto.ExportJobRequest;
import com.example.quickstart.dto.ExportResultDto;
import com.example.quickstart.dto.JobItemDto;
import com.example.quickstart.dto.JobRunDto;
import com.example.quickstart.dto.RowDto;
import com.example.quickstart.dto.RowError;
import com.example.quickstart.dto.RowsJobRequest;
import com.example.quickstart.dto.StagingGroupDto;
import com.example.quickstart.entity.ExportResult;
import com.example.quickstart.entity.JobItem;
import com.example.quickstart.entity.JobRun;
import com.example.quickstart.entity.StagingConfigData;
import com.example.quickstart.repository.ExportResultRepository;
import com.example.quickstart.repository.JobItemRepository;
import com.example.quickstart.repository.JobRunRepository;
import com.example.quickstart.repository.StagingConfigDataRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 作业生命周期：创建（幂等）/查询/取消/导出结果/暂存区 */
@Service
@RequiredArgsConstructor
public class JobService {

    private static final TypeReference<List<RowError>> ROW_ERROR_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<>() {
    };

    private final JobRunRepository jobRunRepository;
    private final JobItemRepository jobItemRepository;
    private final ExportResultRepository exportResultRepository;
    private final StagingConfigDataRepository stagingRepository;
    private final DependencyService dependencyService;
    private final ConfigDefService configDefService;
    private final TaskService taskService;
    private final JobExecutor jobExecutor;
    private final ObjectMapper om;

    // ================================ 创建（幂等） ================================

    /** 导出作业。幂等：同任务同 kind 有 PENDING/RUNNING 作业时直接返回该作业 */
    @Transactional
    public synchronized JobRunDto createExportJob(Long taskId, ExportJobRequest req) {
        taskService.getOrThrow(taskId);
        var existing = jobRunRepository.findFirstByTaskIdAndKindAndStatusIn(
                taskId, "EXPORT", List.of("PENDING", "RUNNING"));
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        List<ExportJobRequest.ExportItem> items = req.items();
        List<String> defCodes = items.stream().map(ExportJobRequest.ExportItem::defCode).toList();
        List<String> sorted = dependencyService.topoSort(defCodes);
        Map<String, ExportJobRequest.ExportItem> byCode = new LinkedHashMap<>();
        for (ExportJobRequest.ExportItem item : items) {
            byCode.put(item.defCode(), item);
        }
        List<ItemSpec> specs = sorted.stream()
                .map(code -> new ItemSpec(code,
                        writeJson(Map.of("conditions",
                                byCode.get(code).conditions() == null ? List.of() : byCode.get(code).conditions()))))
                .toList();
        return createJob(taskId, "EXPORT", specs);
    }

    /** 检查/导入作业（请求结构相同） */
    @Transactional
    public synchronized JobRunDto createRowsJob(Long taskId, String kind, RowsJobRequest req) {
        taskService.getOrThrow(taskId);
        var existing = jobRunRepository.findFirstByTaskIdAndKindAndStatusIn(
                taskId, kind, List.of("PENDING", "RUNNING"));
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        List<RowsJobRequest.RowsItem> items = req.items();
        List<String> defCodes = items.stream().map(RowsJobRequest.RowsItem::defCode).toList();
        List<String> sorted = dependencyService.topoSort(defCodes);
        Map<String, RowsJobRequest.RowsItem> byCode = new LinkedHashMap<>();
        for (RowsJobRequest.RowsItem item : items) {
            byCode.put(item.defCode(), item);
        }
        List<ItemSpec> specs = sorted.stream()
                .map(code -> new ItemSpec(code,
                        writeJson(Map.of("rows",
                                byCode.get(code).rows() == null ? List.of() : byCode.get(code).rows()))))
                .toList();
        return createJob(taskId, kind, specs);
    }

    /** 发布作业：数据来自该任务暂存区 */
    @Transactional
    public synchronized JobRunDto createPublishJob(Long taskId) {
        taskService.getOrThrow(taskId);
        var existing = jobRunRepository.findFirstByTaskIdAndKindAndStatusIn(
                taskId, "PUBLISH", List.of("PENDING", "RUNNING"));
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        List<String> defCodes = stagingRepository.findByTaskIdOrderByDefCodeAscRowNoAsc(taskId).stream()
                .map(StagingConfigData::getDefCode).distinct().toList();
        if (defCodes.isEmpty()) {
            throw new IllegalArgumentException("暂存区无数据，无法发布");
        }
        List<String> sorted = dependencyService.topoSort(defCodes);
        List<ItemSpec> specs = sorted.stream().map(code -> new ItemSpec(code, null)).toList();
        return createJob(taskId, "PUBLISH", specs);
    }

    private JobRunDto createJob(Long taskId, String kind, List<ItemSpec> specs) {
        JobRun job = new JobRun();
        job.setTaskId(taskId);
        job.setKind(kind);
        job.setStatus("PENDING");
        job.setTotal(specs.size());
        job.setProcessed(0);
        job.setCreatedAt(LocalDateTime.now());
        job = jobRunRepository.save(job);

        int seq = 1;
        for (ItemSpec spec : specs) {
            JobItem item = new JobItem();
            item.setJobId(job.getId());
            item.setDefCode(spec.defCode());
            item.setSeq(seq++);
            item.setStatus("PENDING");
            item.setRequestJson(spec.requestJson());
            jobItemRepository.save(item);
        }
        // 事务提交后再触发异步执行，否则执行线程读不到未提交的作业行
        Long jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobExecutor.submit(jobId);
            }
        });
        return toDto(job);
    }

    private record ItemSpec(String defCode, String requestJson) {
    }

    // ================================ 查询 / 取消 ================================

    public JobRun getJobOrThrow(Long jobId) {
        return jobRunRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在: " + jobId));
    }

    public JobRunDto getJob(Long jobId) {
        return toDto(getJobOrThrow(jobId));
    }

    /** 取消：轮次边界生效（执行器在处理下一个配置项前检查取消标志） */
    public JobRunDto cancelJob(Long jobId) {
        JobRun job = getJobOrThrow(jobId);
        if ("PENDING".equals(job.getStatus()) || "RUNNING".equals(job.getStatus())) {
            jobExecutor.requestCancel(jobId);
        }
        return toDto(job);
    }

    /** 取消任务时级联取消所有进行中的作业 */
    public void cancelRunningJobsForTask(Long taskId) {
        for (JobRun job : jobRunRepository.findByTaskIdOrderByIdDesc(taskId)) {
            if ("PENDING".equals(job.getStatus()) || "RUNNING".equals(job.getStatus())) {
                jobExecutor.requestCancel(job.getId());
            }
        }
    }

    public List<ExportResultDto> exportResults(Long jobId) {
        getJobOrThrow(jobId);
        List<ExportResultDto> result = new ArrayList<>();
        for (ExportResult er : exportResultRepository.findByJobIdOrderByIdAsc(jobId)) {
            var def = configDefService.getDef(er.getDefCode());
            List<Map<String, Object>> rows;
            try {
                rows = om.readValue(er.getRowsJson(), ROWS_TYPE);
            } catch (Exception e) {
                throw new IllegalStateException("导出结果解析失败: " + er.getDefCode(), e);
            }
            result.add(new ExportResultDto(er.getDefCode(), er.getDefCode() + ".xlsx",
                    rows.size(), def.fields(), rows));
        }
        return result;
    }

    public List<StagingGroupDto> staging(Long taskId) {
        taskService.getOrThrow(taskId);
        Map<String, List<RowDto>> grouped = new LinkedHashMap<>();
        for (StagingConfigData row : stagingRepository.findByTaskIdOrderByDefCodeAscRowNoAsc(taskId)) {
            grouped.computeIfAbsent(row.getDefCode(), k -> new ArrayList<>())
                    .add(new RowDto(row.getRowNo(), configDefService.parseData(row.getDataJson())));
        }
        return grouped.entrySet().stream()
                .map(e -> new StagingGroupDto(e.getKey(), e.getValue().size(), e.getValue()))
                .toList();
    }

    // ================================ DTO ================================

    public JobRunDto toDto(JobRun job) {
        List<JobItemDto> items = jobItemRepository.findByJobIdOrderBySeqAsc(job.getId()).stream()
                .map(this::toItemDto).toList();
        return new JobRunDto(job.getId(), job.getTaskId(), job.getKind(), job.getStatus(),
                job.getTotal(), job.getProcessed(), job.getCurrentItem(), job.getResult(),
                job.getError(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(), items);
    }

    private JobItemDto toItemDto(JobItem item) {
        List<RowError> detail = null;
        if (item.getDetail() != null && !item.getDetail().isBlank()) {
            try {
                detail = om.readValue(item.getDetail(), ROW_ERROR_LIST_TYPE);
            } catch (Exception e) {
                detail = List.of();
            }
        }
        return new JobItemDto(item.getDefCode(), item.getSeq(), item.getStatus(),
                item.getTotalRows(), item.getOkRows(), item.getErrorRows(),
                item.getMessage(), detail);
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
