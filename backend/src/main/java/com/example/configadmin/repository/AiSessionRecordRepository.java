package com.example.configadmin.repository;

import com.example.configadmin.entity.AiSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface AiSessionRecordRepository extends JpaRepository<AiSessionRecord, String> {

    void deleteByLastAccessBefore(LocalDateTime threshold);
}
