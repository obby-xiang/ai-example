package com.example.ai.repository;

import com.example.ai.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByStatusNotOrderByUpdatedAtDesc(String status);
    List<Task> findAllByOrderByUpdatedAtDesc();
}
