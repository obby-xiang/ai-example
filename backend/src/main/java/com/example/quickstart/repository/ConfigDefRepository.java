package com.example.quickstart.repository;

import com.example.quickstart.entity.ConfigDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigDefRepository extends JpaRepository<ConfigDef, Long> {

    Optional<ConfigDef> findByCode(String code);

    List<ConfigDef> findByOrderByLevelAscCodeAsc();

    boolean existsByCode(String code);
}
