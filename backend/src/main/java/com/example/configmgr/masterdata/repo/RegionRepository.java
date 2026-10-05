package com.example.configmgr.masterdata.repo;

import com.example.configmgr.masterdata.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RegionRepository extends JpaRepository<Region, Long> {
    Optional<Region> findByCode(String code);
    boolean existsByCode(String code);
}
