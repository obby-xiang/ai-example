package com.example.ai.repository;

import com.example.ai.entity.ConfigDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigDefinitionRepository extends JpaRepository<ConfigDefinition, Long> {
    Optional<ConfigDefinition> findByCode(String code);
    List<ConfigDefinition> findByEnabledTrueOrderByIdAsc();
}
