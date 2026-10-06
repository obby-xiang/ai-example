-- V2__staging_base_version.sql
-- 导入暂存行记录导入时刻的已发布行版本号，用于发布时检测并发冲突

ALTER TABLE config_staging_rows ADD COLUMN base_version BIGINT;
