package com.example.quickstart.repository;

import com.example.quickstart.entity.ConfigData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConfigDataRepository extends JpaRepository<ConfigData, Long> {

    List<ConfigData> findByDefCodeOrderByRowNoAsc(String defCode);

    long countByDefCode(String defCode);

    void deleteByDefCode(String defCode);
}
