package com.example.ai.repository;

import com.example.ai.entity.ConfigDataRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigDataRowRepository extends JpaRepository<ConfigDataRow, Long> {

    long countByConfigDefId(Long configDefId);

    Page<ConfigDataRow> findByConfigDefId(Long configDefId, Pageable pageable);

    List<ConfigDataRow> findByConfigDefId(Long configDefId);

    List<ConfigDataRow> findByConfigDefIdOrderByIdAsc(Long configDefId);

    @Modifying
    @Query("DELETE FROM ConfigDataRow r WHERE r.configDefId = :defId")
    void deleteByConfigDefId(@Param("defId") Long defId);

    @Query(value = "SELECT COUNT(*) FROM config_data_rows r WHERE r.config_def_id = :defId " +
            "AND CAST(r.row_data AS VARCHAR) LIKE :keyword", nativeQuery = true)
    long countByConfigDefIdAndKeyword(@Param("defId") Long defId, @Param("keyword") String keyword);
}
