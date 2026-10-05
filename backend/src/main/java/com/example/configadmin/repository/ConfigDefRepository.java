package com.example.configadmin.repository;

import com.example.configadmin.entity.ConfigDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigDefRepository extends JpaRepository<ConfigDef, Long> {

    Optional<ConfigDef> findByCode(String code);

    boolean existsByCode(String code);

    List<ConfigDef> findAllByOrderBySortOrderAscIdAsc();

    List<ConfigDef> findByEnabledTrueOrderBySortOrderAscIdAsc();

    List<ConfigDef> findByCodeIn(List<String> codes);
}
