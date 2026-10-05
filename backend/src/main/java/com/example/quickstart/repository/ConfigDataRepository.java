package com.example.quickstart.repository;

import com.example.quickstart.entity.ConfigData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigDataRepository extends JpaRepository<ConfigData, Long> {

    List<ConfigData> findByDefIdAndStatusOrderByIdAsc(Long defId, String status);

    Optional<ConfigData> findByDefIdAndStatusAndTaskIdAndScopeValue(Long defId, String status, String taskId, String scopeValue);

    List<ConfigData> findByDefIdAndStatusAndScopeValueOrderByIdAsc(Long defId, String status, String scopeValue);

    void deleteByDefIdAndStatusAndTaskId(Long defId, String status, String taskId);

    void deleteByDefIdAndStatusAndScopeValue(Long defId, String status, String scopeValue);

    void deleteByTaskId(String taskId);

    long countByDefIdAndStatus(Long defId, String status);
}
