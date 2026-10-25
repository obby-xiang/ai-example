-- R-163 T5：AI 回复赞踩反馈（net-new 持久化面，与 parts[] 持久化无关）。
-- message_id 唯一 ⇒ 幂等 upsert（rating=0 = 取消，删行）。
-- 单用户本地应用（无登录体系），created_by 预留。

CREATE TABLE ai_feedback (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    rating          INT          NOT NULL, -- 1=赞 / -1=踩（0=取消不落库，落库前删行）
    reason          VARCHAR(1024),
    message_excerpt VARCHAR(400),          -- R-163 收口修 F2：被评价消息正文前 200 字符快照（人工复盘可读摘要）
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_feedback_message UNIQUE (message_id)
);
