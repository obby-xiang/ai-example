package com.example.configmgr.masterdata.repo;

import com.example.configmgr.masterdata.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByCode(String code);
    List<Project> findByRegionCode(String regionCode);
    boolean existsByCode(String code);
}
