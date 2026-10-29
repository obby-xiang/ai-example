package com.example.configmgr.ai.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * R-163 T5：赞踩反馈仓储——{@code (tenant_id, user_id, message_id)} 唯一支撑<b>身份内</b>幂等 upsert。
 *
 * <p>所有查询都必须带 {@code tenantId/userId}（租户任务 2 收尾组 / V11）：反馈是会话数据，
 * 只按 {@code messageId} 查会把别人的行读出来并覆盖（跨租户同 messageId 是合法的）。
 * 刻意<b>不</b>提供无身份的重载 —— 那等于给"跨身份读写"留一条最短路径。
 */
public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    AiFeedback findByMessageIdAndTenantIdAndUserId(String messageId, long tenantId, long userId);

    /** 设置页「AI 反馈」查看列表：当前身份的条目，按更新时间倒序（上限 100）。 */
    List<AiFeedback> findTop100ByTenantIdAndUserIdOrderByUpdatedAtDesc(long tenantId, long userId);
}
