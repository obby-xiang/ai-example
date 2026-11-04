package com.example.configmgr.ai.feedback;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * R-163 T5：AI 回复赞踩反馈条目（ai_feedback，net-new 表——parts[] 持久化不动的并列面）。
 * message_id 在<b>身份内</b>唯一 ⇒ 同一消息反复上报 = 幂等 upsert；rating=0（取消）在端点层翻译为删行。
 *
 * <p><b>身份隔离（当前协议）</b>：反馈是<b>会话数据</b>，必须按
 * {@code tenant_id + user_id} 隔离 —— 两个身份可以用同一个 sessionId / messageId 而互不覆盖、
 * 互不可见、互不误删。
 * <ul>
 * <li>{@code tenant_id/user_id}：写入时固化当前身份，两者均为非空；不存在无归属的 {@code (0,0)}
 * 默认命名空间，读/写/删都按显式身份精确匹配；</li>
 * <li>{@code created_by}：写入时记录当前 {@code userId}；</li>
 * <li>唯一键 {@code (tenant_id, user_id, message_id)}：幂等 upsert 的粒度是"身份内"。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "ai_feedback",
		uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "user_id", "message_id" }))
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属租户（显式身份；写入时固化，非空）。 */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 归属用户（显式身份；写入时固化，非空）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

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

    /** 写入时的当前用户（{@code userId} 的字符串形）。 */
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
