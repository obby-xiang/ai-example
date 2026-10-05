-- V1__schema.sql
-- AI辅助快速实施系统 数据库 Schema

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
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    def_code      VARCHAR(64)  NOT NULL,
    code          VARCHAR(64)  NOT NULL,
    label         VARCHAR(128) NOT NULL,
    field_type    VARCHAR(16)  NOT NULL, -- STRING/NUMBER/DATE/ENUM/BOOLEAN/REFERENCE
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_key        BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order    INT          NOT NULL DEFAULT 0,
    options_json  CLOB,        -- JSON array of {value, label} for ENUM
    ref_def_code  VARCHAR(64), -- for REFERENCE type
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
    scope_type  VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL', -- GLOBAL/REGION/PROJECT
    scope_key   VARCHAR(64),  -- region_code or project_code
    row_key     VARCHAR(256) NOT NULL, -- composite key from key fields
    data_json   CLOB         NOT NULL, -- {"field_code": value, ...}
    version     BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_data_row UNIQUE (def_code, scope_type, scope_key, row_key)
);
CREATE INDEX idx_data_def_scope ON config_data_rows(def_code, scope_type, scope_key);

-- 暂存配置行（导入前的中间数据）
CREATE TABLE config_staging_rows (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     BIGINT       NOT NULL,
    def_code    VARCHAR(64)  NOT NULL,
    op_type     VARCHAR(16)  NOT NULL DEFAULT 'UPSERT', -- UPSERT/DELETE
    row_key     VARCHAR(256) NOT NULL,
    scope_type  VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL',
    scope_key   VARCHAR(64),
    data_json   CLOB         NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'STAGED', -- STAGED/PUBLISHED/FAILED
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_staging_task ON config_staging_rows(task_id, def_code);

-- 任务
CREATE TABLE tasks (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          VARCHAR(32)  NOT NULL, -- EXPORT/IMPORT
    title         VARCHAR(256) NOT NULL,
    current_step  VARCHAR(64)  NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/COMPLETED/CANCELLED/FAILED
    settings_json CLOB,         -- {"importMode":"MERGE"/"REPLACE", ...}
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 任务条目（每个任务选择的配置项）
CREATE TABLE task_items (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id        BIGINT      NOT NULL,
    def_code       VARCHAR(64) NOT NULL,
    sort_order     INT         NOT NULL DEFAULT 0,
    condition_json CLOB,       -- query conditions for EXPORT
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING/READY/CHECKING/CHECKED/IMPORTING/IMPORTED/PUBLISHING/PUBLISHED/FAILED
    CONSTRAINT uq_task_item UNIQUE (task_id, def_code)
);

-- 任务文件
CREATE TABLE task_files (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    def_code     VARCHAR(64)  NOT NULL,
    file_type    VARCHAR(32)  NOT NULL, -- TEMPLATE/UPLOAD/EXPORT
    storage_path VARCHAR(512) NOT NULL,
    original_path VARCHAR(512),
    file_name    VARCHAR(256),
    row_count    INT,
    version      BIGINT       NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_task_file UNIQUE (task_id, def_code, file_type)
);

-- 异步作业
CREATE TABLE jobs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     BIGINT       NOT NULL,
    job_type    VARCHAR(32)  NOT NULL, -- EXPORT/PRECHECK/IMPORT/PUBLISH
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
    progress    INT          NOT NULL DEFAULT 0,
    total       INT          NOT NULL DEFAULT 0,
    error_count INT          NOT NULL DEFAULT 0,
    warning_count INT        NOT NULL DEFAULT 0,
    result_json CLOB,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
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
    job_id     BIGINT       NOT NULL,
    def_code   VARCHAR(64)  NOT NULL,
    row_key    VARCHAR(256),
    field_code VARCHAR(64),
    severity   VARCHAR(16)  NOT NULL, -- ERROR/WARNING/INFO
    message    VARCHAR(1024) NOT NULL,
    row_index  INT
);
CREATE INDEX idx_issue_job_def ON validation_issues(job_id, def_code);
