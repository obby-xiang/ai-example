package com.example.quickstart.repository;

import com.example.quickstart.entity.JobItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface JobItemRepository extends JpaRepository<JobItem, Long> {

    List<JobItem> findByJobIdOrderBySeqAsc(Long jobId);

    void deleteByJobIdIn(Collection<Long> jobIds);
}
