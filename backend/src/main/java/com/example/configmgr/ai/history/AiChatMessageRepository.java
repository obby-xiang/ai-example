package com.example.configmgr.ai.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 完整对话历史仓储（{@code ai_chat_message}）。
 *
 * <p>
 * 查询一律以 {@code (session_id, seq)} 为轴：唯一约束同时提供该会话内的有序索引，
 * 游标分页不需要 offset。删除是物理 DELETE，不做软删。
 */
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    /** 当前会话已分配的最大 seq（空会话 = 0）。追加侧据此从 1 开始分配。 */
    @Query("select coalesce(max(m.seq), 0) from AiChatMessage m where m.sessionId = :sessionId")
    long maxSeq(@Param("sessionId") String sessionId);

    /** 最新一页（倒序，不设上界）。 */
    List<AiChatMessage> findBySessionIdOrderBySeqDesc(String sessionId, Pageable pageable);

    /** 向更早翻页：seq < beforeSeq，倒序。 */
    List<AiChatMessage> findBySessionIdAndSeqLessThanOrderBySeqDesc(String sessionId, long beforeSeq,
            Pageable pageable);

    /** 最早一页（正序，不设下界）。 */
    List<AiChatMessage> findBySessionIdOrderBySeqAsc(String sessionId, Pageable pageable);

    /** 向更新翻页：seq > afterSeq，正序。 */
    List<AiChatMessage> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(String sessionId, long afterSeq,
            Pageable pageable);

    long countBySessionId(String sessionId);

    /** 同一落定点的幂等命中：`(session, run, messageId)` 唯一。 */
    Optional<AiChatMessage> findFirstBySessionIdAndRunIdAndMessageId(String sessionId, String runId,
            String messageId);

    /** 该 run 已落库的消息（诊断/对账；按 seq 正序）。 */
    List<AiChatMessage> findBySessionIdAndRunIdOrderBySeqAsc(String sessionId, String runId);

    /** 删除会话的<b>全部</b>历史行（物理删除，幂等：不存在时返回 0）。 */
    @Modifying
    @Query("delete from AiChatMessage m where m.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
