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

    /**
     * 注意：scope_key 在 GLOBAL 级为 NULL，派生查询的 "=" 无法匹配 NULL，
     * 因此所有带 scopeKey 的查询都用下面的 null-safe JPQL 实现。
     */
    @Query("SELECT r FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType "
            + "AND ((:scopeKey IS NULL AND r.scopeKey IS NULL) OR r.scopeKey = :scopeKey) "
            + "AND r.rowKey = :rowKey")
    Optional<ConfigDataRow> findRow(@Param("defCode") String defCode,
                                    @Param("scopeType") String scopeType,
                                    @Param("scopeKey") String scopeKey,
                                    @Param("rowKey") String rowKey);

    @Query("SELECT r FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType "
            + "AND ((:scopeKey IS NULL AND r.scopeKey IS NULL) OR r.scopeKey = :scopeKey) "
            + "ORDER BY r.rowKey")
    List<ConfigDataRow> findRowsInScope(@Param("defCode") String defCode,
                                        @Param("scopeType") String scopeType,
                                        @Param("scopeKey") String scopeKey);

    @Query(value = "SELECT r FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType "
            + "AND ((:scopeKey IS NULL AND r.scopeKey IS NULL) OR r.scopeKey = :scopeKey) "
            + "ORDER BY r.rowKey",
            countQuery = "SELECT COUNT(r) FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType "
                    + "AND ((:scopeKey IS NULL AND r.scopeKey IS NULL) OR r.scopeKey = :scopeKey)")
    Page<ConfigDataRow> findRowsInScopePaged(@Param("defCode") String defCode,
                                             @Param("scopeType") String scopeType,
                                             @Param("scopeKey") String scopeKey,
                                             Pageable pageable);

    @Query("SELECT COUNT(r) FROM ConfigDataRow r WHERE r.defCode = :defCode AND r.scopeType = :scopeType "
            + "AND (:scopeKey IS NULL OR r.scopeKey = :scopeKey)")
    long countByScope(@Param("defCode") String defCode,
                      @Param("scopeType") String scopeType,
                      @Param("scopeKey") String scopeKey);

    List<ConfigDataRow> findByDefCodeOrderByRowKey(String defCode);

    /** 已发布行数（定义删除的"有数据禁止删除"检查 / 导出作业分母预估）。 */
    long countByDefCode(String defCode);
}
