package com.example.quickstart.repository;

import com.example.quickstart.entity.StagingConfigData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StagingConfigDataRepository extends JpaRepository<StagingConfigData, Long> {

    List<StagingConfigData> findByTaskIdOrderByDefCodeAscRowNoAsc(Long taskId);

    List<StagingConfigData> findByTaskIdAndDefCodeOrderByRowNoAsc(Long taskId, String defCode);

    void deleteByTaskIdAndDefCode(Long taskId, String defCode);

    void deleteByTaskId(Long taskId);
}
