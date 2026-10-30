package com.example.configmgr.definition.repo;

import com.example.configmgr.definition.entity.ConfigDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConfigDefinitionRepository extends JpaRepository<ConfigDefinition, Long> {
    Optional<ConfigDefinition> findByCode(String code);
    boolean existsByCode(String code);

    @Query("SELECT d FROM ConfigDefinition d ORDER BY d.sortOrder, d.code")
    List<ConfigDefinition> findAllOrdered();

    @Query("SELECT d FROM ConfigDefinition d WHERE d.level = :level ORDER BY d.sortOrder, d.code")
    List<ConfigDefinition> findByLevelOrdered(@Param("level") ConfigDefinition.ConfigLevel level);

    List<ConfigDefinition> findByLevel(ConfigDefinition.ConfigLevel level);
}
