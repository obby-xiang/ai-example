package com.example.quickstart.repository;

import com.example.quickstart.entity.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Optional<JobRun> findFirstByTaskIdAndKindAndStatusIn(Long taskId, String kind, Collection<String> statuses);

    List<JobRun> findByTaskIdOrderByIdDesc(Long taskId);

    List<JobRun> findTop3ByTaskIdOrderByIdDesc(Long taskId);

    List<JobRun> findByStatusIn(Collection<String> statuses);

    void deleteByTaskId(Long taskId);
}
