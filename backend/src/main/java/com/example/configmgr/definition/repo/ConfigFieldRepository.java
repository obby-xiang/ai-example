package com.example.configmgr.definition.repo;

import com.example.configmgr.definition.entity.ConfigField;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConfigFieldRepository extends JpaRepository<ConfigField, Long> {
    List<ConfigField> findByDefCodeOrderBySortOrder(String defCode);
    void deleteByDefCode(String defCode);

    /** 反向引用查询：哪些字段（属哪个定义）以 REFERENCE 指向给定定义（删除定义的被引用检查）。 */
    List<ConfigField> findByRefDefCode(String refDefCode);
}
