package com.example.configmgr.ai.feedback;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * R-163 T5：AI 回复赞踩反馈条目（ai_feedback，net-new 表——parts[] 持久化不动的并列面）。
 * message_id 全局唯一 ⇒ 同一消息反复上报 = 幂等 upsert；rating=0（取消）在端点层翻译为删行。
 */
@Data
@Entity
@Table(name = "ai_feedback", uniqueConstraints = @UniqueConstraint(columnNames = "message_id"))
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 1=赞 / -1=踩（0=取消不落库）。 */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "reason", length = 1024)
    private String reason;

    /** R-163 收口修 F2：被评价消息正文前 200 字符快照（人工复盘的可读摘要；缺省 null）。 */
    @Column(name = "message_excerpt", length = 400)
    private String messageExcerpt;

    /** 单用户本地应用暂无登录体系，预留隔离位（未来多用户直接生效）。 */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
