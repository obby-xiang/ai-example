package com.example.ai.repository;

import com.example.ai.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    @Query("SELECT m FROM AiChatMessage m WHERE (m.taskId = :taskId OR (m.taskId IS NULL AND :taskId IS NOT NULL)) " +
            "AND (m.sessionId = :sessionId OR m.sessionId IS NULL) ORDER BY m.createdAt ASC, m.id ASC")
    List<AiChatMessage> findByTaskOrGlobal(@Param("taskId") Long taskId, @Param("sessionId") String sessionId);

    List<AiChatMessage> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);

    List<AiChatMessage> findByTaskIdIsNullAndSessionIdOrderByCreatedAtAscIdAsc(String sessionId);

    @Modifying
    @Query("DELETE FROM AiChatMessage m WHERE m.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);
}
