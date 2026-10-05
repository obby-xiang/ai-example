package com.example.quickstart.controller;

import com.example.quickstart.dto.ExportJobRequest;
import com.example.quickstart.dto.ExportResultDto;
import com.example.quickstart.dto.JobRunDto;
import com.example.quickstart.dto.RowsJobRequest;
import com.example.quickstart.dto.StagingGroupDto;
import com.example.quickstart.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping("/tasks/{id}/jobs/export")
    public JobRunDto export(@PathVariable Long id, @Valid @RequestBody ExportJobRequest req) {
        return jobService.createExportJob(id, req);
    }

    @PostMapping("/tasks/{id}/jobs/check")
    public JobRunDto check(@PathVariable Long id, @Valid @RequestBody RowsJobRequest req) {
        return jobService.createRowsJob(id, "CHECK", req);
    }

    @PostMapping("/tasks/{id}/jobs/import")
    public JobRunDto importJob(@PathVariable Long id, @Valid @RequestBody RowsJobRequest req) {
        return jobService.createRowsJob(id, "IMPORT", req);
    }

    @PostMapping("/tasks/{id}/jobs/publish")
    public JobRunDto publish(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return jobService.createPublishJob(id);
    }

    @GetMapping("/jobs/{jobId}")
    public JobRunDto getJob(@PathVariable Long jobId) {
        return jobService.getJob(jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public JobRunDto cancelJob(@PathVariable Long jobId) {
        return jobService.cancelJob(jobId);
    }

    @GetMapping("/jobs/{jobId}/export-results")
    public List<ExportResultDto> exportResults(@PathVariable Long jobId) {
        return jobService.exportResults(jobId);
    }

    @GetMapping("/tasks/{id}/staging")
    public List<StagingGroupDto> staging(@PathVariable Long id) {
        return jobService.staging(id);
    }
}
