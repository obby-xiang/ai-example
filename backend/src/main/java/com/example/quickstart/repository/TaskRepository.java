package com.example.quickstart.repository;

import com.example.quickstart.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByOrderByCreatedAtDesc();

    long countByTaskNoStartingWith(String prefix);
}
