-- S19 附加性索引（MySQL）。
--
-- 本文件只新增索引，不修改任何既有列、约束或排序规则。
-- 全新部署的执行顺序：create_database_mysql.sql -> create_table_mysql.sql -> init_system_config.sql -> 本文件。
-- 可重复执行：所有语句都先判断索引是否存在。
--
-- 两类索引的用途：
--   1. 消息对账任务的热查询条件（document_task 的状态+时间组合、document 的状态+时间组合）。
--      对账任务在 S19 引入，若没有这些索引会退化为每轮全表扫描。
--   2. S19 审计发现的「无索引的热外键列」和 O9 跨阶段身份列。

USE smartledge;

-- ---------- 1. 对账任务热查询 ----------

-- 补投查询：status + task_status + create_time。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_document_task ADD INDEX idx_document_task_status_status_create (status, task_status, create_time)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_document_task'
      AND INDEX_NAME = 'idx_document_task_status_status_create');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 失联判定查询：status + task_status + start_time。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_document_task ADD INDEX idx_document_task_status_status_start (status, task_status, start_time)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_document_task'
      AND INDEX_NAME = 'idx_document_task_status_status_start');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 文档状态释放查询：status + index_status + edit_time。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_document ADD INDEX idx_document_status_index_edit (status, index_status, edit_time)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_document'
      AND INDEX_NAME = 'idx_document_status_index_edit');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 文档解析状态释放查询：status + parse_status + edit_time。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_document ADD INDEX idx_document_status_parse_edit (status, parse_status, edit_time)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_document'
      AND INDEX_NAME = 'idx_document_status_parse_edit');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. O9 与观测链路的无索引热列 ----------

-- O9 跨阶段候选主键；建表时只索引了 citation_identity_hash，没有索引 candidate_id。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_chat_retrieval_result ADD INDEX idx_retrieval_result_candidate_id (candidate_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_chat_retrieval_result'
      AND INDEX_NAME = 'idx_retrieval_result_candidate_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_chat_retrieval_result ADD INDEX idx_retrieval_result_parent_block (parent_block_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_chat_retrieval_result'
      AND INDEX_NAME = 'idx_retrieval_result_parent_block');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_chat_exchange_trace_stage ADD INDEX idx_trace_stage_parent (parent_stage_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_chat_exchange_trace_stage'
      AND INDEX_NAME = 'idx_trace_stage_parent');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_checkpoint ADD INDEX idx_checkpoint_exchange (exchange_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_checkpoint'
      AND INDEX_NAME = 'idx_checkpoint_exchange');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_chat_dialogue ADD INDEX idx_dialogue_selected_document (selected_document_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_chat_dialogue'
      AND INDEX_NAME = 'idx_dialogue_selected_document');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 按文档查切块（重建、快照、父块提升都会用到）。
SET @sql := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE smartledge_document_chunk ADD INDEX idx_document_chunk_document_no (document_id, chunk_no)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'smartledge' AND TABLE_NAME = 'smartledge_document_chunk'
      AND INDEX_NAME = 'idx_document_chunk_document_no');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
