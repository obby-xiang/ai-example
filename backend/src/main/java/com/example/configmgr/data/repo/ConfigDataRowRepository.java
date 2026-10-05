package com.example.configmgr.data.repo;

import com.example.configmgr.data.entity.ConfigDataRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConfigDataRowRepository extends JpaRepository<ConfigDataRow, Long> {
    Optional<ConfigDataRow> findByDefCodeAndScopeTypeAndScopeKeyAndRowKey(
            String defCode, String scopeType, String scopeKey, String rowKey);

    List<ConfigDataRow> findByDefCodeAndScopeTypeAndScopeKeyOrderByRowKey(
            String defCode, String scopeType, String scopeKey);

    Page<ConfigDataRow> findByDefCodeAndScopeTypeAndScopeKey(
            String defCode, String scopeType, String scopeKey, Pageable pageable);

    @Query("SELECT COUNT(r) FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType AND (:scopeKey IS NULL OR r.scopeKey = :scopeKey)")
    long countByScope(@Param("defCode") String defCode,
                      @Param("scopeType") String scopeType,
                      @Param("scopeKey") String scopeKey);

    void deleteByDefCodeAndScopeTypeAndScopeKey(String defCode, String scopeType, String scopeKey);

    List<ConfigDataRow> findByDefCodeOrderByRowKey(String defCode);
}
