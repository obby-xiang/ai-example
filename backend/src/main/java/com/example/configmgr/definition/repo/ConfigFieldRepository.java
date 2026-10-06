package com.example.configmgr.definition.repo;

import com.example.configmgr.definition.entity.ConfigField;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConfigFieldRepository extends JpaRepository<ConfigField, Long> {
    List<ConfigField> findByDefCodeOrderBySortOrder(String defCode);
    void deleteByDefCode(String defCode);
}
