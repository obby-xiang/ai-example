package com.example.ai.repository;

import com.example.ai.entity.AgentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentStateRepository extends JpaRepository<AgentState, String> {

    /** 查找某 sessionId 下处于指定状态的所有 state（可能多条，用于清理） */
    List<AgentState> findAllBySessionIdAndStatus(String sessionId, String status);

    /** 清理过期未恢复的 state（标记为 EXPIRED） */
    @Modifying
    @Query("UPDATE AgentState s SET s.status = 'EXPIRED' " +
            "WHERE s.status = 'WAITING_TOOL' AND s.expiresAt < CURRENT_TIMESTAMP")
    int markExpired();

    /** 清理某 session 的所有 state */
    @Modifying
    @Query("DELETE FROM AgentState s WHERE s.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /** 列出某 session 的所有 state（用于诊断） */
    List<AgentState> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
