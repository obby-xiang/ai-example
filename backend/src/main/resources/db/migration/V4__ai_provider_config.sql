-- 模型配置专项批①（设计稿 docs/research/设计-模型配置功能专项-2026-10-21.md §3.1）：
-- ai_provider / ai_model / ai_setting / ai_config_audit 四表。
-- 只建表，不改任何既有表（ai_feedback 与本专项无关）。
-- 批量写路径（新建/更新 provider、加密 key）在批②；批① 只有"表空才写"的 env 种子。

-- provider 主档：一行 = 一个 OpenAI 兼容端点。
-- key_cipher 存信封串 v1.<kid>.<iv>.<ct+tag>（AAD = code|keyRef）；env 种子行刻意留空 ——
-- env key 不落库（设计稿 §3.3 / §10-5），key_ref=ENV 表示运行时从进程环境读。
CREATE TABLE ai_provider (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL,     -- 稳定机读名（deepseek / custom-<n>）；种子写 deepseek
    display_name     VARCHAR(128) NOT NULL,
    protocol         VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPAT', -- 预留列，本期只此一值
    base_url         VARCHAR(512) NOT NULL,
    completions_path VARCHAR(128) NOT NULL DEFAULT '/chat/completions',
    models_path      VARCHAR(128) NOT NULL DEFAULT '/models',
    key_cipher       VARCHAR(2048),             -- 信封串；ENV 种子行为空
    key_last4        VARCHAR(8),                -- 只展示末 4 位
    key_fingerprint  VARCHAR(64),               -- HMAC-SHA256(KEK, key) 前 16 hex（运行时缓存键的一部分）
    key_ref          VARCHAR(8)   NOT NULL DEFAULT 'NONE', -- DB / ENV / NONE
    source           VARCHAR(16)  NOT NULL DEFAULT 'DB',   -- ENV_SEED / DB
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_provider_code UNIQUE (code)
);

-- 模型 allowlist：能力位 + 上下文窗口（上下文统计专项的分母来源）。
-- 不加 provider 外键约束：删除走软删（enabled=false），避免级联阻塞。
CREATE TABLE ai_model (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id          BIGINT       NOT NULL,
    model_id             VARCHAR(128) NOT NULL, -- 请求体里的 model 原值
    display_name         VARCHAR(128),
    max_context          INT,
    max_output           INT,
    supports_tool_calling BOOLEAN     NOT NULL DEFAULT TRUE, -- false ⇒ 不下发 tools
    supports_vision      BOOLEAN      DEFAULT FALSE,
    supports_reasoning   BOOLEAN      DEFAULT FALSE,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default           BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_model_provider_model UNIQUE (provider_id, model_id)
);

-- 全局默认参数（单行键值）。config.version 是快照版本号（DB 真源；跨实例失效信号走 Redis ai:cfg:ver）。
CREATE TABLE ai_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    value_json  VARCHAR(2048),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_setting (setting_key, value_json) VALUES ('config.version', '1');

-- 配置变更审计（P2；批① 只建结构，不写数据）。
-- diff_json 对 key 只写 {"key":"updated","last4":"ab12"} —— 永不写密文或明文。
CREATE TABLE ai_config_audit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       VARCHAR(64),
    action      VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32)  NOT NULL,
    target_id   VARCHAR(64),
    diff_json   VARCHAR(4096),
    at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
