-- V6__task_child_fk.sql（PostgreSQL 方言版）
--
-- 对应 H2 形态的 Java 迁移 db/migration/../java/db/migration/V6__Task_child_fk.java。
-- 为什么 PG 用纯 SQL：H2 那份之所以写 Java，是因为 H2/MySQL 的"约束是否已存在 / DROP CONSTRAINT"
-- 语法不同、且 DDL 非事务，失败会留下"约束已建、版本未记"的半完成态。PG 的 DDL **是事务性的**
-- （本文件在一个 Flyway 事务里执行，任一步失败整体回滚），所以只要把"可重入"表达成
-- "存在即跳过"，纯 SQL + PL/pgSQL 就够，不需要编译期 Java 迁移。
--
-- 前置检查（只报错，不删改任何数据）：任一子表存在孤儿行即抛异常、迁移失败，交人工裁决。
-- 上线前核查 SQL（各环境先跑，确认均为 0）：
--   SELECT 'task_items' AS table_name, COUNT(*) AS orphans FROM task_items i
--     WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
--   UNION ALL SELECT 'task_files', COUNT(*) FROM task_files i
--     WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
--   UNION ALL SELECT 'config_staging_rows', COUNT(*) FROM config_staging_rows i
--     WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = i.task_id)
--   UNION ALL SELECT 'job_items', COUNT(*) FROM job_items i
--     WHERE NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = i.job_id)
--   UNION ALL SELECT 'validation_issues', COUNT(*) FROM validation_issues i
--     WHERE NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = i.job_id);
--
-- 不对 jobs.task_id 加 FK：历史库有 1 条 task_id=99999 的 FAILED 孤儿作业，
-- 按"不得清理任何现存数据"的约束不先删它；API 建作业/删除的互斥由任务行锁负责。

DO $$
DECLARE
    r        record;
    orphans  bigint;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('task_items',          'tasks', 'task_id'),
            ('task_files',          'tasks', 'task_id'),
            ('config_staging_rows', 'tasks', 'task_id'),
            ('job_items',           'jobs',  'job_id'),
            ('validation_issues',   'jobs',  'job_id')
        ) AS v(child_table, parent_table, child_column)
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I c WHERE NOT EXISTS (SELECT 1 FROM %I p WHERE p.id = c.%I)',
            r.child_table, r.parent_table, r.child_column)
        INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'V6 前置检查失败：%.% 存在 % 行孤儿。迁移不改任何数据，请人工裁决清理或归属后重跑。',
                r.child_table, r.child_column, orphans;
        END IF;
    END LOOP;
END
$$;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('fk_task_items_task',          'task_items',          'task_id', 'tasks'),
            ('fk_task_files_task',          'task_files',          'task_id', 'tasks'),
            ('fk_config_staging_rows_task', 'config_staging_rows', 'task_id', 'tasks'),
            ('fk_job_items_job',            'job_items',           'job_id',  'jobs'),
            ('fk_validation_issues_job',    'validation_issues',   'job_id',  'jobs')
        ) AS v(fk_name, child_table, child_column, parent_table)
    LOOP
        -- 存在性判断必须按 (schema, 表, 约束名) 限定：约束名在 PG 里只保证同表唯一，
        -- 只查 conname 会被别的 schema/表上的同名约束命中，导致本表 FK 静默漏建
        -- （迁移记成功、结构缺失）。conrelid 把范围收到 current_schema() 下的目标子表。
        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
             WHERE c.conname = r.fk_name
               AND c.conrelid = format('%I.%I', current_schema(), r.child_table)::regclass
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I (id) ON DELETE CASCADE',
                r.child_table, r.fk_name, r.child_column, r.parent_table);
        END IF;
    END LOOP;
END
$$;
