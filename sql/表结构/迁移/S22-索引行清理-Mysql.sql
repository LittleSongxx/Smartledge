-- S22 批次 3：索引派生行的过期集识别与清理（MySQL 侧）
--
-- 背景：一次成功的构建只为**一个** task_id 写派生行；失败或重复的构建各自留下整套行，
-- 同一文档因此会累积多套派生数据。实测文档 2522855131016618028 有 5 个任务的
-- 622 行 chunk（MySQL）与 622 行向量（pgvector）。
--
-- 口径（唯一权威）：`smartledge_document.last_index_task_id` 就是"当前有效集"。
--   检索也是按它收窄的（RetrievalPlanAssembler → taskIds → 向量 SQL 的 task_id IN (...)，
--   ES 关键词侧同理由 resolvedTaskIds() 过滤），所以过期行既不会被召回，也不影响回答。
--   本脚本的性质是**存储与观测治理**，不是修召回缺陷（见 records/S22.md §2.5）。
--
-- 不在口径内的表：`smartledge_document_structure_node` 按 `parse_task_id` 归属，且每次解析
--   先按 document_id 全删再插入，因此不存在按索引任务累积的问题。
--
-- 本文件分两段：
--   第 1 段：只读扫描（随时可跑，用来核对"哪些行是过期的"）；
--   第 2 段：清理（**会删数据、不可逆**，必须由用户明确授权后执行；已包在事务里，可先看计数再决定）。
--
-- 与 Java 侧同一口径：`StaleDerivedIndexRowPolicy`（含单元测试）。两处若有分歧，以本注释的口径为准并同步修改。

-- ============================================================
-- 第 1 段：只读扫描（不修改任何数据）
-- ============================================================

-- 1.1 每份文档的派生行按任务分布，并标出哪一套是当前有效集
SELECT d.id AS document_id,
       d.document_name,
       d.tenant_id,
       d.last_index_task_id,
       t.task_id,
       CASE WHEN t.task_id = d.last_index_task_id THEN 'CURRENT' ELSE 'STALE' END AS row_kind,
       t.chunk_rows,
       t.parent_block_rows,
       t.kg_entity_rows,
       t.raptor_node_rows
FROM smartledge_document d
JOIN (
    SELECT document_id, task_id,
           SUM(chunk_rows) AS chunk_rows,
           SUM(parent_block_rows) AS parent_block_rows,
           SUM(kg_entity_rows) AS kg_entity_rows,
           SUM(raptor_node_rows) AS raptor_node_rows
    FROM (
        SELECT document_id, task_id, COUNT(*) AS chunk_rows, 0 AS parent_block_rows, 0 AS kg_entity_rows, 0 AS raptor_node_rows
        FROM smartledge_document_chunk GROUP BY document_id, task_id
        UNION ALL
        SELECT document_id, task_id, 0, COUNT(*), 0, 0
        FROM smartledge_document_parent_block GROUP BY document_id, task_id
        UNION ALL
        SELECT document_id, task_id, 0, 0, COUNT(*), 0
        FROM smartledge_kg_entity GROUP BY document_id, task_id
        UNION ALL
        SELECT document_id, task_id, 0, 0, 0, COUNT(*)
        FROM smartledge_raptor_node GROUP BY document_id, task_id
    ) parts
    GROUP BY document_id, task_id
) t ON t.document_id = d.id
ORDER BY d.id, row_kind, t.task_id;

-- 1.2 汇总：过期行总量（按表）
SELECT 'document_chunk' AS table_name, COUNT(*) AS stale_rows
FROM smartledge_document_chunk c
JOIN smartledge_document d ON d.id = c.document_id
WHERE d.last_index_task_id IS NOT NULL AND c.task_id <> d.last_index_task_id
UNION ALL
SELECT 'document_parent_block', COUNT(*)
FROM smartledge_document_parent_block p
JOIN smartledge_document d ON d.id = p.document_id
WHERE d.last_index_task_id IS NOT NULL AND p.task_id <> d.last_index_task_id
UNION ALL
SELECT 'kg_entity', COUNT(*)
FROM smartledge_kg_entity e
JOIN smartledge_document d ON d.id = e.document_id
WHERE d.last_index_task_id IS NOT NULL AND e.task_id <> d.last_index_task_id
UNION ALL
SELECT 'kg_relation', COUNT(*)
FROM smartledge_kg_relation r
JOIN smartledge_document d ON d.id = r.document_id
WHERE d.last_index_task_id IS NOT NULL AND r.task_id <> d.last_index_task_id
UNION ALL
SELECT 'kg_evidence', COUNT(*)
FROM smartledge_kg_evidence v
JOIN smartledge_document d ON d.id = v.document_id
WHERE d.last_index_task_id IS NOT NULL AND v.task_id <> d.last_index_task_id
UNION ALL
SELECT 'kg_community', COUNT(*)
FROM smartledge_kg_community m
JOIN smartledge_document d ON d.id = m.document_id
WHERE d.last_index_task_id IS NOT NULL AND m.task_id <> d.last_index_task_id
UNION ALL
SELECT 'raptor_node', COUNT(*)
FROM smartledge_raptor_node n
JOIN smartledge_document d ON d.id = n.document_id
WHERE d.last_index_task_id IS NOT NULL AND n.task_id <> d.last_index_task_id;

-- ============================================================
-- 第 2 段：清理（不可逆，需授权；默认注释掉，逐条确认后执行）
-- ============================================================
-- 用法：把 @document_id 改成目标文档，先跑 2.1 看计数，确认无误再跑 2.2。
-- 幂等：重复执行不会删错——已删过的任务集合为空，第二次执行为空操作。

-- SET @document_id = 2522855131016618028;

-- 2.1 过期任务集合与待删计数（只读；@last_index_task_id 为空时集合自然为空 → 一条都不删）
-- SET @last_index_task_id = (SELECT last_index_task_id FROM smartledge_document WHERE id = @document_id);
-- DROP TEMPORARY TABLE IF EXISTS tmp_stale_index_task;
-- CREATE TEMPORARY TABLE tmp_stale_index_task (task_id BIGINT PRIMARY KEY) AS
-- SELECT DISTINCT task_id FROM (
--     SELECT task_id FROM smartledge_document_chunk WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_document_parent_block WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_kg_entity WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_kg_relation WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_kg_evidence WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_kg_community WHERE document_id = @document_id
--     UNION SELECT task_id FROM smartledge_raptor_node WHERE document_id = @document_id
-- ) all_tasks
-- WHERE @last_index_task_id IS NOT NULL AND task_id <> @last_index_task_id;
-- SELECT * FROM tmp_stale_index_task;

-- 2.2 删除（逐表；保留 last_index_task_id 那一套）
-- START TRANSACTION;
-- DELETE c FROM smartledge_document_chunk c
--   JOIN tmp_stale_index_task s ON s.task_id = c.task_id WHERE c.document_id = @document_id;
-- DELETE p FROM smartledge_document_parent_block p
--   JOIN tmp_stale_index_task s ON s.task_id = p.task_id WHERE p.document_id = @document_id;
-- DELETE e FROM smartledge_kg_entity e
--   JOIN tmp_stale_index_task s ON s.task_id = e.task_id WHERE e.document_id = @document_id;
-- DELETE r FROM smartledge_kg_relation r
--   JOIN tmp_stale_index_task s ON s.task_id = r.task_id WHERE r.document_id = @document_id;
-- DELETE v FROM smartledge_kg_evidence v
--   JOIN tmp_stale_index_task s ON s.task_id = v.task_id WHERE v.document_id = @document_id;
-- DELETE m FROM smartledge_kg_community m
--   JOIN tmp_stale_index_task s ON s.task_id = m.task_id WHERE m.document_id = @document_id;
-- DELETE n FROM smartledge_raptor_node n
--   JOIN tmp_stale_index_task s ON s.task_id = n.task_id WHERE n.document_id = @document_id;
-- -- 复核：下面两条应只剩当前有效任务
-- SELECT task_id, COUNT(*) FROM smartledge_document_chunk WHERE document_id = @document_id GROUP BY task_id;
-- SELECT task_id, COUNT(*) FROM smartledge_kg_entity WHERE document_id = @document_id GROUP BY task_id;
-- COMMIT;
-- -- 若计数不符预期：ROLLBACK;

-- 2.3 pgvector 侧（另一半，见 S22-索引行清理-PostgresSql.sql）
--     向量行同样按 task_id 累积，必须一起清理，否则 RLS 下的 over-fetch 仍会白读过期向量。
--
-- 2.4 ES 关键词侧：过期文档在 ES 里仍然存在但**不可召回**（检索按 resolvedTaskIds() 过滤），
--     因此不阻塞本次清理；如需回收存储，用 delete_by_query 按 task_id 删除即可（可后置）。
