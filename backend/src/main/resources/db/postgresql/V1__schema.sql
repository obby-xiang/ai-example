-- V1__schema.sql（PostgreSQL 方言）—— 应用当前结构的唯一空库基线。
-- 与 H2 基线的表、列、约束、索引和外键一致，仅 CLOB 映射为 TEXT。
-- 包含身份化 AI 反馈、完整模型配置、任务创建幂等、作业 cancel_version 及全部子表外键。

-- 地区主数据
CREATE TABLE regions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(32) NOT NULL UNIQUE,
    name       VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 项目主数据
CREATE TABLE projects (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(32) NOT NULL UNIQUE,
    name        VARCHAR(64) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 配置定义
CREATE TABLE config_definitions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    level       VARCHAR(16)  NOT NULL, -- GLOBAL/REGION/PROJECT
    description VARCHAR(512),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 配置字段
CREATE TABLE config_fields (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    def_code       VARCHAR(64)  NOT NULL,
    code           VARCHAR(64)  NOT NULL,
    label          VARCHAR(128) NOT NULL,
    field_type     VARCHAR(16)  NOT NULL, -- STRING/NUMBER/DATE/ENUM/BOOLEAN/REFERENCE
    required       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_key         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order     INT          NOT NULL DEFAULT 0,
    options_json   TEXT,        -- JSON array of {value, label} for ENUM
    ref_def_code   VARCHAR(64), -- for REFERENCE type
    ref_field_code VARCHAR(64), -- referenced field code
    CONSTRAINT uq_field_def_code UNIQUE (def_code, code)
);

-- 配置定义依赖关系
CREATE TABLE config_dependencies (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_def_code VARCHAR(64) NOT NULL,
    to_def_code   VARCHAR(64) NOT NULL,
    CONSTRAINT uq_dep UNIQUE (from_def_code, to_def_code)
);

-- 已发布配置行
CREATE TABLE config_data_rows (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    def_code    VARCHAR(64)  NOT NULL,
    scope_type  VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL',
    scope_key   VARCHAR(64),
    row_key     VARCHAR(256) NOT NULL,
    data_json   TEXT         NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_data_row UNIQUE (def_code, scope_type, scope_key, row_key)
);
CREATE INDEX idx_data_def_scope ON config_data_rows(def_code, scope_type, scope_key);

-- 暂存配置行（导入前的中间数据）
CREATE TABLE config_staging_rows (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    def_code     VARCHAR(64)  NOT NULL,
    op_type      VARCHAR(16)  NOT NULL DEFAULT 'UPSERT',
    row_key      VARCHAR(256) NOT NULL,
    scope_type   VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL',
    scope_key    VARCHAR(64),
    data_json    TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'STAGED',
    base_version BIGINT,      -- 导入时刻的已发布行版本（发布时检测并发冲突）
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_staging_task ON config_staging_rows(task_id, def_code);

-- 任务
CREATE TABLE tasks (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          VARCHAR(32)  NOT NULL, -- EXPORT/IMPORT
    title         VARCHAR(256) NOT NULL,
    current_step  VARCHAR(64)  NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    settings_json TEXT,
    ai_create_run VARCHAR(255), -- settings_json.aiCreateRun 的可索引镜像（幂等唯一约束）
    ai_create_key VARCHAR(64),  -- settings_json.aiCreateKey 的可索引镜像（无 runId 时的幂等键）
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tasks_ai_create_run UNIQUE (ai_create_run),
    CONSTRAINT uq_tasks_ai_create_key UNIQUE (ai_create_key)
);

-- 任务条目（每个任务选择的配置项）
CREATE TABLE task_items (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        BIGINT      NOT NULL,
    def_code       VARCHAR(64) NOT NULL,
    sort_order     INT         NOT NULL DEFAULT 0,
    condition_json TEXT,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT uq_task_item UNIQUE (task_id, def_code)
);

-- 任务文件
CREATE TABLE task_files (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       BIGINT       NOT NULL,
    def_code      VARCHAR(64)  NOT NULL,
    file_type     VARCHAR(32)  NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    original_path VARCHAR(512),
    file_name     VARCHAR(256),
    row_count     INT,
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_task_file UNIQUE (task_id, def_code, file_type)
);

-- 异步作业
CREATE TABLE jobs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        BIGINT       NOT NULL,
    job_type       VARCHAR(32)  NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    progress       INT          NOT NULL DEFAULT 0,
    total          INT          NOT NULL DEFAULT 0,
    error_count    INT          NOT NULL DEFAULT 0,
    warning_count  INT          NOT NULL DEFAULT 0,
    result_json    TEXT,
    cancel_version BIGINT       NOT NULL DEFAULT 0, -- 取消/终态围栏（每次取消或终态 +1）
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP
);

-- 作业条目进度
CREATE TABLE job_items (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id    BIGINT      NOT NULL,
    def_code  VARCHAR(64) NOT NULL,
    status    VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed INT         NOT NULL DEFAULT 0,
    total     INT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_job_item UNIQUE (job_id, def_code)
);

-- 校验问题
CREATE TABLE validation_issues (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id     BIGINT        NOT NULL,
    def_code   VARCHAR(64)   NOT NULL,
    row_key    VARCHAR(256),
    field_code VARCHAR(64),
    severity   VARCHAR(16)   NOT NULL,
    message    VARCHAR(1024) NOT NULL,
    row_index  INT
);
CREATE INDEX idx_issue_job_def ON validation_issues(job_id, def_code);

-- AI 回复赞踩反馈：身份内幂等（tenant_id + user_id + message_id）。
-- tenant_id/user_id 必须由写入方给出，身份字段不允许缺省。
CREATE TABLE ai_feedback (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    message_id      VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    rating          INT          NOT NULL,
    reason          VARCHAR(1024),
    message_excerpt VARCHAR(400),
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_feedback_message UNIQUE (tenant_id, user_id, message_id)
);
CREATE INDEX idx_ai_feedback_scope ON ai_feedback (tenant_id, user_id, updated_at);

-- AI provider 主档：一行 = 一个 OpenAI 兼容端点。
CREATE TABLE ai_provider (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(128) NOT NULL,
    protocol         VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPAT',
    base_url         VARCHAR(512) NOT NULL,
    completions_path VARCHAR(128) NOT NULL DEFAULT '/chat/completions',
    models_path      VARCHAR(128) NOT NULL DEFAULT '/models',
    key_cipher       VARCHAR(2048),
    key_last4        VARCHAR(8),
    key_fingerprint  VARCHAR(64),
    key_ref          VARCHAR(8)   NOT NULL DEFAULT 'NONE',
    source           VARCHAR(16)  NOT NULL DEFAULT 'DB',
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_provider_code UNIQUE (code)
);

-- 模型 allowlist：能力位 + 上下文窗口（上下文分母的唯一来源）。
CREATE TABLE ai_model (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_id           BIGINT       NOT NULL,
    model_id              VARCHAR(128) NOT NULL,
    display_name          VARCHAR(128),
    max_context           INT,
    max_output            INT,
    supports_tool_calling BOOLEAN      NOT NULL DEFAULT TRUE,
    supports_vision       BOOLEAN      DEFAULT FALSE,
    supports_reasoning    BOOLEAN      DEFAULT FALSE,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default            BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order            INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_model_provider_model UNIQUE (provider_id, model_id)
);

-- 全局默认参数（单行键值）。config.version 是快照版本号（DB 真源）。
CREATE TABLE ai_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    value_json  VARCHAR(2048),
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_setting (setting_key, value_json) VALUES ('config.version', '1');

-- 配置变更审计。
CREATE TABLE ai_config_audit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       VARCHAR(64),
    action      VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32)  NOT NULL,
    target_id   VARCHAR(64),
    diff_json   VARCHAR(4096),
    at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 种子单飞的行锁表：行由本基线预置，应用只 SELECT ... FOR UPDATE。
CREATE TABLE app_lock (
    lock_name   VARCHAR(64) PRIMARY KEY,
    holder      VARCHAR(128),
    acquired_at TIMESTAMP
);

INSERT INTO app_lock (lock_name) VALUES ('seed');

-- 子表外键（ON DELETE CASCADE），包含 jobs.task_id。
ALTER TABLE task_items ADD CONSTRAINT fk_task_items_task
    FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE task_files ADD CONSTRAINT fk_task_files_task
    FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE config_staging_rows ADD CONSTRAINT fk_config_staging_rows_task
    FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE jobs ADD CONSTRAINT fk_jobs_task
    FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE;
ALTER TABLE job_items ADD CONSTRAINT fk_job_items_job
    FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE;
ALTER TABLE validation_issues ADD CONSTRAINT fk_validation_issues_job
    FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE;
