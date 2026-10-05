package com.example.quickstart.repository;

import com.example.quickstart.entity.ScopeDict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScopeDictRepository extends JpaRepository<ScopeDict, Long> {

    List<ScopeDict> findByScopeTypeOrderByCodeAsc(String scopeType);
}
