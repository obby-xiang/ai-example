package com.example.configmgr.masterdata.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.masterdata.entity.Project;
import com.example.configmgr.masterdata.entity.Region;
import com.example.configmgr.masterdata.repo.ProjectRepository;
import com.example.configmgr.masterdata.repo.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/master")
@RequiredArgsConstructor
public class MasterDataController {

    private final RegionRepository regionRepository;
    private final ProjectRepository projectRepository;

    @GetMapping("/regions")
    public ApiResponse<List<Region>> regions() {
        return ApiResponse.ok(regionRepository.findAll());
    }

    @GetMapping("/projects")
    public ApiResponse<List<Project>> projects(@RequestParam(required = false) String regionCode) {
        List<Project> list = StringUtils.hasText(regionCode)
                ? projectRepository.findByRegionCode(regionCode)
                : projectRepository.findAll();
        return ApiResponse.ok(list);
    }
}
