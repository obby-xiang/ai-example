package com.example.configadmin.repository;

import com.example.configadmin.entity.ConfigRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfigRowRepository extends JpaRepository<ConfigRow, Long> {

    List<ConfigRow> findByDefCodeAndPublished(String defCode, boolean published);

    List<ConfigRow> findByDefCodeAndBatchId(String defCode, String batchId);

    long countByDefCodeAndPublished(String defCode, boolean published);

    long countByDefCode(String defCode);

    void deleteByDefCodeAndBatchId(String defCode, String batchId);

    void deleteByDefCodeAndPublished(String defCode, boolean published);
}
