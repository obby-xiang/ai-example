package com.example.configmgr.ai.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** R-163 T5：赞踩反馈仓储——message_id 唯一支撑幂等 upsert。 */
public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    AiFeedback findByMessageId(String messageId);

    /** 设置页「AI 反馈」查看列表：按更新时间倒序（仅当前用户——单用户应用即全表）。 */
    List<AiFeedback> findTop100ByOrderByUpdatedAtDesc();
}
