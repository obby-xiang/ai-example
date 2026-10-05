package com.example.configmgr.job.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final ConfigStagingRowRepository stagingRowRepository;

    @PostMapping("/tasks/{taskId}/jobs")
    public ResponseEntity<ApiResponse<Job>> createJob(@PathVariable Long taskId,
                                                       @RequestBody Map<String, String> body) {
        Job.JobType jobType = Job.JobType.valueOf(body.get("jobType"));
        Job job = jobService.createAndStart(taskId, jobType);
        return ResponseEntity.status(201).body(ApiResponse.ok(job));
    }

    @GetMapping("/tasks/{taskId}/jobs")
    public ApiResponse<List<Job>> listJobs(@PathVariable Long taskId) {
        return ApiResponse.ok(jobService.findByTaskId(taskId));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<Job> getJob(@PathVariable Long jobId) {
        return ApiResponse.ok(jobService.findById(jobId));
    }

    @DeleteMapping("/jobs/{jobId}")
    public ApiResponse<?> cancelJob(@PathVariable Long jobId) {
        jobService.cancel(jobId);
        return ApiResponse.ok();
    }

    @GetMapping("/jobs/{jobId}/issues")
    public ApiResponse<Page<ValidationIssue>> getIssues(@PathVariable Long jobId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(jobService.findIssues(jobId, page, size));
    }

    @GetMapping("/jobs/{jobId}/diff")
    public ApiResponse<List<ConfigStagingRow>> getDiff(@PathVariable Long jobId) {
        Job job = jobService.findById(jobId);
        List<ConfigStagingRow> staging = stagingRowRepository.findByTaskId(job.getTaskId());
        return ApiResponse.ok(staging);
    }
}
