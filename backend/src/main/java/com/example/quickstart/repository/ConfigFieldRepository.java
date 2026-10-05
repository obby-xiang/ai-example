package com.example.quickstart.repository;

import com.example.quickstart.entity.ConfigField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfigFieldRepository extends JpaRepository<ConfigField, Long> {

    List<ConfigField> findByDefIdOrderBySortNoAscIdAsc(Long defId);

    void deleteByDefId(Long defId);
}
