-- S24 安全隔离补丁（MySQL 业务库）。
--
-- 幂等、可重复执行。不要在双写窗口盲跑：先让新代码写入 tenant_id / token_version，
-- 再在人工窗口执行本脚本。本文件不执行物理删除，也不连接真实验收库。
--
-- 覆盖：
--   1. smartledge_user.token_version
--   2. 子表 tenant_id（route_trace / strategy_plan|step / parse_artifact / task_log）
--   3. (tenant_id, dialogue_code) 唯一
--   4. document:read-all
--   5. 从租户 ADMIN 收回 tenant:manage
--   6. 从系统参数 JSON 移除可写 JWT 密钥

USE smartledge;

-- ============================================================================
-- 1. token_version
-- ============================================================================
DROP PROCEDURE IF EXISTS smartledge_s24_add_token_version;
DELIMITER $$
CREATE PROCEDURE smartledge_s24_add_token_version()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS c
                    WHERE c.TABLE_SCHEMA = 'smartledge'
                      AND c.TABLE_NAME = 'smartledge_user'
                      AND c.COLUMN_NAME = 'token_version') THEN
        ALTER TABLE smartledge_user
            ADD COLUMN token_version BIGINT NOT NULL DEFAULT 1
            COMMENT 'JWT 版本；停用/改角色/登出后递增' AFTER last_login_at;
    END IF;
END$$
DELIMITER ;
CALL smartledge_s24_add_token_version();
DROP PROCEDURE smartledge_s24_add_token_version;

-- ============================================================================
-- 2. 子表 tenant_id
-- ============================================================================
DROP PROCEDURE IF EXISTS smartledge_s24_add_child_tenant;
DELIMITER $$
CREATE PROCEDURE smartledge_s24_add_child_tenant()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE tbl VARCHAR(64);
    DECLARE cur CURSOR FOR
        SELECT t.table_name FROM (
            SELECT 'smartledge_knowledge_route_trace' AS table_name UNION ALL
            SELECT 'smartledge_document_strategy_plan' UNION ALL
            SELECT 'smartledge_document_strategy_step' UNION ALL
            SELECT 'smartledge_document_parse_artifact' UNION ALL
            SELECT 'smartledge_document_task_log'
        ) t
        WHERE EXISTS (SELECT 1 FROM information_schema.TABLES it
                       WHERE it.TABLE_SCHEMA = 'smartledge' AND it.TABLE_NAME = t.table_name);
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO tbl;
        IF done = 1 THEN LEAVE read_loop; END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS c
                        WHERE c.TABLE_SCHEMA = 'smartledge' AND c.TABLE_NAME = tbl
                          AND c.COLUMN_NAME = 'tenant_id') THEN
            SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 ',
                              'COMMENT ''所属租户id'' AFTER id');
            PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET @idx = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX idx_', tbl, '_tenant (tenant_id)');
            PREPARE stmt2 FROM @idx; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
        END IF;
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;
CALL smartledge_s24_add_child_tenant();
DROP PROCEDURE smartledge_s24_add_child_tenant;

-- 列默认值是 1。非租户 1 的历史行必须从父文档/会话回填，否则拦截器会把它们藏掉。
UPDATE smartledge_document_strategy_plan p
  JOIN smartledge_document d ON d.id = p.document_id
   SET p.tenant_id = d.tenant_id
 WHERE d.tenant_id IS NOT NULL AND p.tenant_id <> d.tenant_id;

UPDATE smartledge_document_strategy_step s
  JOIN smartledge_document d ON d.id = s.document_id
   SET s.tenant_id = d.tenant_id
 WHERE d.tenant_id IS NOT NULL AND s.tenant_id <> d.tenant_id;

UPDATE smartledge_document_parse_artifact a
  JOIN smartledge_document d ON d.id = a.document_id
   SET a.tenant_id = d.tenant_id
 WHERE d.tenant_id IS NOT NULL AND a.tenant_id <> d.tenant_id;

UPDATE smartledge_document_task_log l
  JOIN smartledge_document d ON d.id = l.document_id
   SET l.tenant_id = d.tenant_id
 WHERE d.tenant_id IS NOT NULL AND l.tenant_id <> d.tenant_id;

UPDATE smartledge_knowledge_route_trace t
  JOIN smartledge_chat_dialogue d
    ON d.dialogue_code COLLATE utf8mb4_unicode_ci = t.conversation_id COLLATE utf8mb4_unicode_ci
   SET t.tenant_id = d.tenant_id
 WHERE d.tenant_id IS NOT NULL AND t.tenant_id <> d.tenant_id;

-- ============================================================================
-- 3. (tenant_id, dialogue_code) 唯一
-- ============================================================================
-- 若同租户已有重复 dialogue_code，本段会失败。执行前先检查：
--   SELECT tenant_id, dialogue_code, COUNT(*) FROM smartledge_chat_dialogue
--    GROUP BY tenant_id, dialogue_code HAVING COUNT(*) > 1;
DROP PROCEDURE IF EXISTS smartledge_s24_unique_dialogue;
DELIMITER $$
CREATE PROCEDURE smartledge_s24_unique_dialogue()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS c
                WHERE c.TABLE_SCHEMA = 'smartledge'
                  AND c.TABLE_NAME = 'smartledge_chat_dialogue'
                  AND c.COLUMN_NAME = 'tenant_id')
       AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS s
                        WHERE s.TABLE_SCHEMA = 'smartledge'
                          AND s.TABLE_NAME = 'smartledge_chat_dialogue'
                          AND s.INDEX_NAME = 'uk_tenant_dialogue_code') THEN
        ALTER TABLE smartledge_chat_dialogue
            ADD UNIQUE KEY uk_tenant_dialogue_code (tenant_id, dialogue_code);
    END IF;
END$$
DELIMITER ;
CALL smartledge_s24_unique_dialogue();
DROP PROCEDURE smartledge_s24_unique_dialogue;

-- ============================================================================
-- 4. document:read-all
-- ============================================================================
INSERT IGNORE INTO smartledge_permission
    (id, permission_code, permission_name, permission_group, description, create_time, edit_time, status)
VALUES
    (16, 'document:read-all', '查看租户全部文档', 'document', '查看租户资产全集，不按文档 ACL 收窄', NOW(), NOW(), 1);

-- 角色 id 可能是雪花，禁止 `role_id * 100`（会溢出 BIGINT）。主键由角色+权限派生。
INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('s24-read-all-', r.id, '-', p.id)), 1, 15), 16, 10),
       r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r JOIN smartledge_permission p
    ON p.permission_code = 'document:read-all'
 WHERE r.role_code IN ('ADMIN', 'CURATOR')
   AND r.status = 1;

-- ============================================================================
-- 5. 从租户 ADMIN 收回 tenant:manage
-- ============================================================================
UPDATE smartledge_role_permission rp
  JOIN smartledge_role r
    ON r.id = rp.role_id
   AND r.tenant_id = rp.tenant_id
   AND r.role_code = 'ADMIN'
  JOIN smartledge_permission p
    ON p.id = rp.permission_id
   AND p.permission_code = 'tenant:manage'
   SET rp.status = 0,
       rp.edit_time = NOW()
 WHERE rp.status = 1;

-- ============================================================================
-- 6. 系统参数不再承载 JWT 密钥
-- ============================================================================
UPDATE smartledge_system_config
   SET config_json = JSON_REMOVE(config_json, '$.values."adminAuth.tokenSecret"'),
       edit_time = NOW()
 WHERE JSON_CONTAINS_PATH(config_json, 'one', '$.values."adminAuth.tokenSecret"');
