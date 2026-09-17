-- S22 批次 3：索引派生行的过期集清理（pgvector 侧）
--
-- 与 S22-索引行清理-Mysql.sql 同一口径：`smartledge_document.last_index_task_id`（在 MySQL 库）是
-- "当前有效集"的唯一权威，向量表里除它以外的 task_id 都是过期行。
--
-- 向量表带 RLS（tenant_id）+ 应用侧事务级 set_config：本脚本用超级用户执行，RLS 不生效，
-- 因此语句里必须**显式**带 tenant_id 条件（不要依赖 RLS 兜底）。
--
-- 顺序：先在 MySQL 侧跑完 2.1/2.2，再在这里跑 1/2；两侧的 @document_id / @last_index_task_id 必须一致。

-- ============================================================
-- 1. 只读扫描：该文档的向量行按任务分布
-- ============================================================
-- \set document_id 2522855131016618028
SELECT task_id, tenant_id, COUNT(*) AS vector_rows
FROM smartledge_document_embedding
WHERE document_id = 2522855131016618028
GROUP BY task_id, tenant_id
ORDER BY task_id;

-- 1.1 汇总：全库过期向量行（需要 MySQL 侧的 last_index_task_id 清单作为入参）
--     MySQL 侧执行：
--       SELECT id, last_index_task_id FROM smartledge_document WHERE last_index_task_id IS NOT NULL;
--     把结果填进下面的 VALUES 列表后执行。
-- SELECT e.document_id, e.task_id, COUNT(*)
-- FROM smartledge_document_embedding e
-- JOIN (VALUES (2522855131016618028::bigint, 2523494840625586176::bigint) /*, (doc, last_task), ... */)
--        AS active(document_id, last_index_task_id)
--   ON active.document_id = e.document_id
-- WHERE e.task_id <> active.last_index_task_id
-- GROUP BY e.document_id, e.task_id
-- ORDER BY e.document_id, e.task_id;

-- ============================================================
-- 2. 清理（不可逆，需授权；默认注释）
-- ============================================================
-- 2.1 先看计数（把两个常量换成实际值）
-- SELECT task_id, tenant_id, COUNT(*) FROM smartledge_document_embedding
-- WHERE document_id = 2522855131016618028 AND task_id <> 2523494840625586176
-- GROUP BY task_id, tenant_id;

-- 2.2 删除过期向量（tenant_id 显式书写，不依赖 RLS）
-- BEGIN;
-- DELETE FROM smartledge_document_embedding
-- WHERE document_id = 2522855131016618028
--   AND tenant_id = 1
--   AND task_id <> 2523494840625586176;
-- -- 复核：应只剩当前有效任务
-- SELECT task_id, tenant_id, COUNT(*) FROM smartledge_document_embedding
-- WHERE document_id = 2522855131016618028 GROUP BY task_id, tenant_id;
-- COMMIT;
-- -- 若计数不符预期：ROLLBACK;
