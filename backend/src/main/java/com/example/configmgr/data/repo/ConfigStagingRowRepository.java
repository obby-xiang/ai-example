package com.example.configmgr.data.repo;

import com.example.configmgr.data.entity.ConfigStagingRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfigStagingRowRepository extends JpaRepository<ConfigStagingRow, Long> {
    List<ConfigStagingRow> findByTaskIdAndDefCode(Long taskId, String defCode);
    List<ConfigStagingRow> findByTaskId(Long taskId);
    long countByTaskIdAndDefCode(Long taskId, String defCode);

    @Modifying
    @Query("DELETE FROM ConfigStagingRow r WHERE r.taskId = :taskId AND r.defCode = :defCode")
    void deleteByTaskIdAndDefCode(@Param("taskId") Long taskId, @Param("defCode") String defCode);

    @Modifying
    @Query("DELETE FROM ConfigStagingRow r WHERE r.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
