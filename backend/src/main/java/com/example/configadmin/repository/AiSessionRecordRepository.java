package com.example.configadmin.repository;

import com.example.configadmin.entity.AiSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface AiSessionRecordRepository extends JpaRepository<AiSessionRecord, String> {

    @Transactional
    void deleteByLastAccessBefore(LocalDateTime threshold);
}
