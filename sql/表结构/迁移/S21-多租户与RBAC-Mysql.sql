-- S21 多租户与 RBAC（MySQL 业务库）。
--
-- 设计依据见 document/RAG系统设计与重构/stages/S21-多租户RBAC与用户端分离.md §2。
-- 关键约束（已否证的做法不得采用）：
--   1. MySQL 没有行级安全（RLS 是 Postgres 特性），因此业务库的隔离由应用层 SQL 重写保证，
--      使用 MyBatis-Plus TenantLineInnerInterceptor —— 它在 SQL 重写层工作，覆盖
--      SELECT/INSERT/UPDATE/DELETE。这一点刻意不同于 Hibernate 的 @TenantId：
--      @TenantId 只保证 SELECT 带租户条件，默认的 UPDATE/DELETE 不带。
--   2. 派生内容（chunk / 向量 / 索引元数据）不写权限：权限在查询时从父文档解析，
--      这样改 ACL 立即生效且不需要重建索引。任何"索引期固化权限"的做法都会造成权限漂移。
--   3. tenant_id 一律 NOT NULL DEFAULT 1，使 ALTER 对存量数据安全（自动落入默认租户）。
--
-- 可重复执行：所有 DDL 先判断存在性。

USE smartledge;

-- ============================================================================
-- 1. 租户 / 用户 / 角色 / 权限
-- ============================================================================

CREATE TABLE IF NOT EXISTS smartledge_tenant (
    id            BIGINT       NOT NULL COMMENT '主键id',
    tenant_code   VARCHAR(64)  NOT NULL COMMENT '租户编码',
    tenant_name   VARCHAR(128) NOT NULL COMMENT '租户名称',
    description   VARCHAR(512) DEFAULT NULL COMMENT '描述',
    create_time   DATETIME     DEFAULT NULL COMMENT '创建时间',
    edit_time     DATETIME     DEFAULT NULL COMMENT '编辑时间',
    status        TINYINT(1)   NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

CREATE TABLE IF NOT EXISTS smartledge_user (
    id             BIGINT       NOT NULL COMMENT '主键id',
    tenant_id      BIGINT       NOT NULL DEFAULT '1' COMMENT '所属租户id',
    username       VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash  VARCHAR(128) NOT NULL COMMENT 'BCrypt 口令哈希',
    display_name   VARCHAR(128) DEFAULT NULL COMMENT '显示名',
    email          VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    -- 锁定与审计字段：登录失败连续累计，成功即清零；用于防暴力破解
    failed_attempts INT         NOT NULL DEFAULT '0' COMMENT '连续登录失败次数',
    locked_until   DATETIME     DEFAULT NULL COMMENT '锁定截止时间',
    last_login_at  DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    create_time    DATETIME     DEFAULT NULL COMMENT '创建时间',
    edit_time      DATETIME     DEFAULT NULL COMMENT '编辑时间',
    status         TINYINT(1)   NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    -- 登录名在租户内唯一；跨租户可同名
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS smartledge_role (
    id          BIGINT       NOT NULL COMMENT '主键id',
    tenant_id   BIGINT       NOT NULL DEFAULT '1' COMMENT '所属租户id',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码',
    role_name   VARCHAR(128) NOT NULL COMMENT '角色名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '描述',
    built_in    TINYINT(1)   NOT NULL DEFAULT '0' COMMENT '1:内置角色，不可删除',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    edit_time   DATETIME     DEFAULT NULL COMMENT '编辑时间',
    status      TINYINT(1)   NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_code (tenant_id, role_code),
    KEY idx_role_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS smartledge_permission (
    id              BIGINT       NOT NULL COMMENT '主键id',
    permission_code VARCHAR(96)  NOT NULL COMMENT '权限编码 resource:action',
    permission_name VARCHAR(128) NOT NULL COMMENT '权限名称',
    permission_group VARCHAR(64) NOT NULL COMMENT '权限分组，用于前端归类',
    description     VARCHAR(512) DEFAULT NULL COMMENT '描述',
    create_time     DATETIME     DEFAULT NULL COMMENT '创建时间',
    edit_time       DATETIME     DEFAULT NULL COMMENT '编辑时间',
    status          TINYINT(1)   NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表（全局字典，不分租户）';

CREATE TABLE IF NOT EXISTS smartledge_user_role (
    id          BIGINT     NOT NULL COMMENT '主键id',
    tenant_id   BIGINT     NOT NULL DEFAULT '1' COMMENT '所属租户id',
    user_id     BIGINT     NOT NULL COMMENT '用户id',
    role_id     BIGINT     NOT NULL COMMENT '角色id',
    create_time DATETIME   DEFAULT NULL COMMENT '创建时间',
    edit_time   DATETIME   DEFAULT NULL COMMENT '编辑时间',
    status      TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_role_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS smartledge_role_permission (
    id            BIGINT     NOT NULL COMMENT '主键id',
    tenant_id     BIGINT     NOT NULL DEFAULT '1' COMMENT '所属租户id',
    role_id       BIGINT     NOT NULL COMMENT '角色id',
    permission_id BIGINT     NOT NULL COMMENT '权限id',
    create_time   DATETIME   DEFAULT NULL COMMENT '创建时间',
    edit_time     DATETIME   DEFAULT NULL COMMENT '编辑时间',
    status        TINYINT(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_role_permission_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联';

-- ============================================================================
-- 2. 文档 ACL
-- ============================================================================
--
-- 为什么是独立表而不是把 ACL 塞进 smartledge_document.metadata_json：
--   a) ACL 会变，且需要按主体（用户/角色）反查"我能看哪些文档"，JSON 无法建索引；
--   b) metadata_json 承载的是业务属性（部门、密级等），与访问控制混在一起会让
--      "元数据过滤"和"权限过滤"两个不同语义互相污染。
-- principal_type 支持 USER / ROLE 两类主体，便于按角色批量授权。

CREATE TABLE IF NOT EXISTS smartledge_document_acl (
    id             BIGINT      NOT NULL COMMENT '主键id',
    tenant_id      BIGINT      NOT NULL DEFAULT '1' COMMENT '所属租户id',
    document_id    BIGINT      NOT NULL COMMENT '文档id',
    principal_type VARCHAR(16) NOT NULL COMMENT '主体类型 USER/ROLE',
    principal_id   BIGINT      NOT NULL COMMENT '主体id（用户id或角色id）',
    permission     VARCHAR(16) NOT NULL DEFAULT 'READ' COMMENT '权限 READ/WRITE/MANAGE',
    granted_by     BIGINT      DEFAULT NULL COMMENT '授权人用户id',
    create_time    DATETIME    DEFAULT NULL COMMENT '创建时间',
    edit_time      DATETIME    DEFAULT NULL COMMENT '编辑时间',
    status         TINYINT(1)  NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_acl (document_id, principal_type, principal_id),
    -- 反查用：给定主体列出其可见文档
    KEY idx_acl_principal (tenant_id, principal_type, principal_id),
    KEY idx_acl_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档访问控制表';

-- ============================================================================
-- 3. 业务表补 tenant_id
-- ============================================================================
--
-- 覆盖范围：内容与状态直接归属租户的根与直接查询的子表。
-- 未纳入本批的表（system_config / document_strategy_plan|step / knowledge_route_trace /
-- document_task_log / document_parse_artifact 等）仍在单一默认租户语义下工作，
-- 原因是它们只经由已隔离的父实体被访问；后续批次按需补齐。

-- 用存储过程批量补列，幂等。
DROP PROCEDURE IF EXISTS smartledge_add_tenant_column;
DELIMITER $$
CREATE PROCEDURE smartledge_add_tenant_column()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE tbl VARCHAR(64);
    -- 需要补 tenant_id 的表清单
    DECLARE cur CURSOR FOR
        SELECT t.table_name FROM (
            SELECT 'smartledge_knowledge_base' AS table_name UNION ALL
            SELECT 'smartledge_knowledge_scope_node' UNION ALL
            SELECT 'smartledge_knowledge_topic_node' UNION ALL
            SELECT 'smartledge_topic_document_relation' UNION ALL
            SELECT 'smartledge_document' UNION ALL
            SELECT 'smartledge_document_profile' UNION ALL
            SELECT 'smartledge_document_task' UNION ALL
            SELECT 'smartledge_document_parent_block' UNION ALL
            SELECT 'smartledge_document_chunk' UNION ALL
            SELECT 'smartledge_document_structure_node' UNION ALL
            SELECT 'smartledge_document_block' UNION ALL
            SELECT 'smartledge_kg_entity' UNION ALL
            SELECT 'smartledge_kg_relation' UNION ALL
            SELECT 'smartledge_kg_evidence' UNION ALL
            SELECT 'smartledge_kg_community' UNION ALL
            SELECT 'smartledge_raptor_node' UNION ALL
            SELECT 'smartledge_chat_dialogue' UNION ALL
            SELECT 'smartledge_chat_exchange' UNION ALL
            SELECT 'smartledge_chat_retrieval_result' UNION ALL
            SELECT 'smartledge_chat_channel_execution' UNION ALL
            SELECT 'smartledge_chat_memory_summary'
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
            -- NOT NULL DEFAULT 1：存量行自动归入默认租户，不需要单独回填
            SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 '
                              'COMMENT ''所属租户id'' AFTER id');
            PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET @idx = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX idx_', tbl, '_tenant (tenant_id)');
            PREPARE stmt2 FROM @idx; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
        END IF;
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;

CALL smartledge_add_tenant_column();
DROP PROCEDURE smartledge_add_tenant_column;

-- ============================================================================
-- 4. 种子数据
-- ============================================================================
-- 口令哈希由 BCrypt(rounds=10) 生成，种子口令仅用于本地初始化，生产必须强制改密。

INSERT IGNORE INTO smartledge_tenant (id, tenant_code, tenant_name, description, create_time, edit_time, status)
VALUES (1, 'default', '默认租户', 'S21 之前的存量数据全部归入该租户', NOW(), NOW(), 1),
       (2, 'demo-b', '示例租户B', '用于验证跨租户隔离', NOW(), NOW(), 1);

INSERT IGNORE INTO smartledge_permission (id, permission_code, permission_name, permission_group, description, create_time, edit_time, status)
VALUES
    (1,  'kb:read',            '查看知识库',      'knowledge',   '列出与查看知识库及其配置', NOW(), NOW(), 1),
    (2,  'kb:write',           '编辑知识库',      'knowledge',   '创建与修改知识库', NOW(), NOW(), 1),
    (3,  'kb:delete',          '删除知识库',      'knowledge',   '删除知识库', NOW(), NOW(), 1),
    (4,  'document:read',      '查看文档',        'document',    '查看可见范围内的文档', NOW(), NOW(), 1),
    (5,  'document:upload',    '上传文档',        'document',    '上传并触发解析', NOW(), NOW(), 1),
    (6,  'document:delete',    '删除文档',        'document',    '删除文档及其派生产物', NOW(), NOW(), 1),
    (7,  'document:acl:manage','管理文档权限',    'document',    '授予或回收文档访问权限', NOW(), NOW(), 1),
    (8,  'chat:use',           '使用对话',        'chat',        '发起知识库问答', NOW(), NOW(), 1),
    (9,  'observe:read',       '查看观测',        'observe',     '查看检索观测、路由追踪与阶段基准', NOW(), NOW(), 1),
    (10, 'config:read',        '查看参数配置',    'system',      '查看系统参数与历史', NOW(), NOW(), 1),
    (11, 'config:write',       '修改参数配置',    'system',      '修改并恢复系统参数', NOW(), NOW(), 1),
    (12, 'user:manage',        '管理用户与角色',  'system',      '管理租户内的用户与角色', NOW(), NOW(), 1),
    (13, 'tenant:manage',      '管理租户',        'system',      '跨租户管理能力', NOW(), NOW(), 1);

INSERT IGNORE INTO smartledge_role (id, tenant_id, role_code, role_name, description, built_in, create_time, edit_time, status)
VALUES
    (1, 1, 'ADMIN',   '租户管理员', '租户内全部权限', 1, NOW(), NOW(), 1),
    (2, 1, 'CURATOR', '知识库管理员', '文档接入、解析、索引与权限管理，不含用户管理', 1, NOW(), NOW(), 1),
    (3, 1, 'USER',    '普通用户',   '仅可提问与查看自己有权限的文档', 1, NOW(), NOW(), 1),
    (4, 2, 'ADMIN',   '租户管理员', '租户内全部权限', 1, NOW(), NOW(), 1),
    (5, 2, 'USER',    '普通用户',   '仅可提问与查看自己有权限的文档', 1, NOW(), NOW(), 1);

-- ADMIN 角色拿全部权限；CURATOR 拿除用户/租户管理外的全部；USER 只拿对话与文档查看
INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 1000 + r.id * 100 + p.id, r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r CROSS JOIN smartledge_permission p
 WHERE r.role_code = 'ADMIN';

INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 2000 + r.id * 100 + p.id, r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r JOIN smartledge_permission p
    ON p.permission_code IN ('kb:read','kb:write','document:read','document:upload','document:delete',
                             'document:acl:manage','chat:use','observe:read','config:read')
 WHERE r.role_code = 'CURATOR';

INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 3000 + r.id * 100 + p.id, r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r JOIN smartledge_permission p
    ON p.permission_code IN ('document:read','chat:use')
 WHERE r.role_code = 'USER';

-- 用户：admin@t1、curator@t1、alice@t1、bob@t2
-- 口令分别为 admin123456 / curator123456 / user123456 / user123456（仅本地初始化用）
INSERT IGNORE INTO smartledge_user (id, tenant_id, username, password_hash, display_name, create_time, edit_time, status)
VALUES
    (1, 1, 'admin',   '$2b$10$RqLlzaX4iHAXt/XLheXbhusra3ta5.QbxcglxTbv/Ei5Mpm6Ly5v2', '租户管理员', NOW(), NOW(), 1),
    (2, 1, 'curator', '$2b$10$wKfGRuiP3y8MoO8ds9.7R.svae5Cpj3q.cLtju0oQgmpP6YtlRIqG', '知识库管理员', NOW(), NOW(), 1),
    (3, 1, 'alice',   '$2b$10$I4mwXI8OW5xGIyIwufLfUeJcb0CWLSeOh.VE0vcZkhL6vqnn4Tnx6', '普通用户 Alice', NOW(), NOW(), 1),
    (4, 2, 'bob',     '$2b$10$I4mwXI8OW5xGIyIwufLfUeJcb0CWLSeOh.VE0vcZkhL6vqnn4Tnx6', '租户B 用户 Bob', NOW(), NOW(), 1);

INSERT IGNORE INTO smartledge_user_role (id, tenant_id, user_id, role_id, create_time, edit_time, status)
VALUES (1, 1, 1, 1, NOW(), NOW(), 1),
       (2, 1, 2, 2, NOW(), NOW(), 1),
       (3, 1, 3, 3, NOW(), NOW(), 1),
       (4, 2, 4, 5, NOW(), NOW(), 1);

-- 存量文档授权给默认租户的 ADMIN 与 CURATOR 角色；
-- alice（USER）故意不授权，用于验证"同一租户内未授权即不可见"。
INSERT IGNORE INTO smartledge_document_acl (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT 9000000000000000001, d.tenant_id, d.id, 'ROLE', 1, 'MANAGE', NOW(), NOW(), 1
  FROM smartledge_document d WHERE d.status = 1;

INSERT IGNORE INTO smartledge_document_acl (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT 9000000000000000002, d.tenant_id, d.id, 'ROLE', 2, 'READ', NOW(), NOW(), 1
  FROM smartledge_document d WHERE d.status = 1;
