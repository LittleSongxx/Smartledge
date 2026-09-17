-- S21-B3 认证与授权（MySQL 业务库）。
--
-- 设计依据见 document/RAG系统设计与重构/stages/S21-多租户RBAC与用户端分离.md §2，
-- 以及同目录 records/S21.md 的 B3 小节。本文件只做 B3 需要的数据增量：
--   1. 补两个权限编码（管理台访问、文档写）
--   2. 把账号口令从系统参数里摘掉（明文口令不再存在于配置表）
--   3. 会话归属（user_id），用于"只有会话所有者能读写自己的会话与记忆摘要"
--
-- 可重复执行：DDL 先判断存在性，DML 用 INSERT IGNORE / 幂等 UPDATE。

USE smartledge;

-- ============================================================================
-- 1. 权限字典补充
-- ============================================================================
--
-- console:access —— 管理台访问。两个登录入口共用同一套凭据校验，靠这个权限区分
--   "能不能进管理端"。不用"拥有任意管理权限"来推导，因为普通用户的 document:read
--   也属于文档类权限，会让普通用户被误判成管理账号。
-- document:write —— 编辑文档与派生产物（策略确认、索引构建、文档画像重算）。
--   上传用 document:upload、删除用 document:delete，三者互不蕴含。

INSERT IGNORE INTO smartledge_permission
    (id, permission_code, permission_name, permission_group, description, create_time, edit_time, status)
VALUES
    (14, 'console:access', '管理台访问', 'system',   '登录管理台并访问管理接口的前提权限', NOW(), NOW(), 1),
    (15, 'document:write', '编辑文档',   'document', '确认策略、触发索引构建、重算文档画像', NOW(), NOW(), 1);

-- ADMIN 重新对齐租户内权限（含本次新增），但不含 tenant:manage。
INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 1000 + r.id * 100 + p.id, r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r CROSS JOIN smartledge_permission p
 WHERE r.role_code = 'ADMIN'
   AND p.permission_code <> 'tenant:manage';

-- CURATOR：文档接入与解析链路的全部权限 + 管理台访问，但不含用户/租户管理。
INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 2000 + r.id * 100 + p.id, r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r JOIN smartledge_permission p
    ON p.permission_code IN ('kb:read','kb:write','document:read','document:read-all','document:upload','document:write',
                             'document:delete','document:acl:manage','chat:use','observe:read',
                             'config:read','console:access')
 WHERE r.role_code = 'CURATOR';

-- USER 角色刻意不补：普通用户没有管理台访问权限，也进不了 /manage/**。

-- ============================================================================
-- 1.1 存量文档的 CURATOR 授权升级为 WRITE
-- ============================================================================
--
-- B2 的种子把存量文档同时授权给了 ADMIN（MANAGE）与 CURATOR（READ）。B3 起文档写路径
-- （策略确认、索引构建、删除）按 ACL 等级判定，只读授权会让"知识库管理员"这个角色
-- 无法对文档做任何动作，与角色定义（文档接入、解析、索引）不符。
-- 权限等级单调（MANAGE ⇒ WRITE ⇒ READ），因此这里把 CURATOR 的 READ 提升为 WRITE；
-- USER 角色的授权保持 READ 不变（普通用户不该获得写权限）。

UPDATE smartledge_document_acl acl
   JOIN smartledge_role r
     ON r.id = acl.principal_id
    AND r.tenant_id = acl.tenant_id
    AND r.role_code = 'CURATOR'
    SET acl.permission = 'WRITE',
        acl.edit_time = NOW()
 WHERE acl.principal_type = 'ROLE'
   AND acl.permission = 'READ'
   AND acl.status = 1;

-- ============================================================================
-- 2. 结束系统参数里的明文口令
-- ============================================================================
--
-- B3 起凭据只有一处权威：smartledge_user.password_hash（BCrypt）。
-- 系统参数里的 adminAuth.username / adminAuth.password 已无人读取，但值还留在配置 JSON 里
-- （明文口令）。这里把两个键从配置 JSON 中移除；配置快照按注册表读键，缺失的键直接从 JSON 消失，
-- 不影响其它参数。历史表当前为空，无需清理。

UPDATE smartledge_system_config
   SET config_json = JSON_REMOVE(config_json, '$.values."adminAuth.username"', '$.values."adminAuth.password"'),
       edit_time = NOW()
 WHERE JSON_CONTAINS_PATH(config_json, 'one', '$.values."adminAuth.password"');

-- ============================================================================
-- 3. 会话归属
-- ============================================================================
--
-- 会话与记忆摘要是"用户自己的历史"，必须能被所有者以外的人读到的只有租户边界是不够的：
-- 同一租户内任何用户拿到 conversationId 就能读别人的会话与摘要。
-- user_id = 0 表示无归属（B3 之前的历史会话），按 fail closed 处理：无归属会话不对任何用户开放。

DROP PROCEDURE IF EXISTS smartledge_add_dialogue_user_id;
DELIMITER $$
CREATE PROCEDURE smartledge_add_dialogue_user_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS c
                    WHERE c.TABLE_SCHEMA = 'smartledge'
                      AND c.TABLE_NAME = 'smartledge_chat_dialogue'
                      AND c.COLUMN_NAME = 'user_id') THEN
        ALTER TABLE smartledge_chat_dialogue
            ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 COMMENT '归属用户id；0 表示无归属历史会话' AFTER tenant_id;
        ALTER TABLE smartledge_chat_dialogue
            ADD INDEX idx_chat_dialogue_tenant_user (tenant_id, user_id);
    END IF;
END$$
DELIMITER ;

CALL smartledge_add_dialogue_user_id();
DROP PROCEDURE smartledge_add_dialogue_user_id;
