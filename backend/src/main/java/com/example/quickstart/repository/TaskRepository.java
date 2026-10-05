package com.example.quickstart.repository;

import com.example.quickstart.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, String> {

    List<Task> findTop50ByOrderByCreatedAtDesc();

    List<Task> findByTypeOrderByCreatedAtDesc(String type);

    List<Task> findByStatusOrderByCreatedAtDesc(String status);

    List<Task> findByTypeAndStatusOrderByCreatedAtDesc(String type, String status);
}
