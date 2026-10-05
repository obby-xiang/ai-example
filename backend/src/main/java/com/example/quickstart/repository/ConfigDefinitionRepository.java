package com.example.quickstart.repository;

import com.example.quickstart.entity.ConfigDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigDefinitionRepository extends JpaRepository<ConfigDefinition, Long> {

    Optional<ConfigDefinition> findByCode(String code);
}
