-- 作品集对外试用账号（幂等）。
--
-- 只创建 guest / reviewer，不改 admin、curator、alice。
-- 口令只落 BCrypt；明文只出现在登录页与 /guide，不要写进本文件注释以外的仓库位置。
-- reviewer 额外授予页面入口权限（含若干写权限编码），但写操作仍由
-- PortfolioDemoInterceptor / 前端只读守卫拦截。
-- 刻意不含：document:read-all、tenant:manage。
-- 文档 ACL 只授给当前已索引成功的文档，新上传的文档不会自动可见。

USE smartledge;

INSERT IGNORE INTO smartledge_permission
    (id, permission_code, permission_name, permission_group, description, create_time, edit_time, status)
VALUES
    (17, 'portfolio:demo', '作品集试用约束', 'system', '对外展示账号：只读 + 观测仅自己 + 提问限额', NOW(), NOW(), 1);

INSERT INTO smartledge_role
    (id, tenant_id, role_code, role_name, description, built_in, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5('portfolio-role-guest-t1'), 1, 15), 16, 10),
       1, 'PORTFOLIO_GUEST', '作品集会话试用', '对外会话端试用，只能检索已授权文档', 1, NOW(), NOW(), 1
 WHERE NOT EXISTS (
        SELECT 1 FROM smartledge_role WHERE tenant_id = 1 AND role_code = 'PORTFOLIO_GUEST'
       );

INSERT INTO smartledge_role
    (id, tenant_id, role_code, role_name, description, built_in, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5('portfolio-role-reviewer-t1'), 1, 15), 16, 10),
       1, 'PORTFOLIO_REVIEWER', '作品集管理试用', '对外管理端试用，只读已授权文档和自己的观测', 1, NOW(), NOW(), 1
 WHERE NOT EXISTS (
        SELECT 1 FROM smartledge_role WHERE tenant_id = 1 AND role_code = 'PORTFOLIO_REVIEWER'
       );

INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-rp-', r.id, '-', p.id)), 1, 15), 16, 10),
       r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r
  JOIN smartledge_permission p
    ON p.permission_code IN ('document:read', 'chat:use', 'portfolio:demo')
 WHERE r.tenant_id = 1
   AND r.role_code = 'PORTFOLIO_GUEST'
   AND r.status = 1
   AND p.status = 1;

INSERT IGNORE INTO smartledge_role_permission (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-rp-', r.id, '-', p.id)), 1, 15), 16, 10),
       r.tenant_id, r.id, p.id, NOW(), NOW(), 1
  FROM smartledge_role r
  JOIN smartledge_permission p
    ON p.permission_code IN (
        'console:access', 'document:read', 'kb:read', 'observe:read', 'chat:use', 'portfolio:demo',
        'document:acl:manage', 'document:upload', 'document:write', 'document:delete',
        'kb:write', 'kb:delete', 'user:manage', 'config:read', 'config:write'
    )
 WHERE r.tenant_id = 1
   AND r.role_code = 'PORTFOLIO_REVIEWER'
   AND r.status = 1
   AND p.status = 1;

INSERT INTO smartledge_user
    (id, tenant_id, username, password_hash, display_name, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5('portfolio-user-guest'), 1, 15), 16, 10),
       1,
       'guest',
       '$2b$10$eylwWOpDHY/zY.VHJ9o6I.CKUNfCfryBSF9VM4wZ5Z/GXToKJSJnq',
       '作品集会话试用',
       NOW(),
       NOW(),
       1
 WHERE NOT EXISTS (
        SELECT 1 FROM smartledge_user WHERE tenant_id = 1 AND username = 'guest'
       );

INSERT INTO smartledge_user
    (id, tenant_id, username, password_hash, display_name, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5('portfolio-user-reviewer'), 1, 15), 16, 10),
       1,
       'reviewer',
       '$2b$10$M0JWMo7/zMCuXf15irSiJ.JcGyplJnpiadtzf.4eL0h3/8nuWNJrW',
       '作品集管理试用',
       NOW(),
       NOW(),
       1
 WHERE NOT EXISTS (
        SELECT 1 FROM smartledge_user WHERE tenant_id = 1 AND username = 'reviewer'
       );

UPDATE smartledge_user
   SET password_hash = '$2b$10$eylwWOpDHY/zY.VHJ9o6I.CKUNfCfryBSF9VM4wZ5Z/GXToKJSJnq',
       display_name = '作品集会话试用',
       status = 1,
       failed_attempts = 0,
       locked_until = NULL,
       edit_time = NOW()
 WHERE tenant_id = 1 AND username = 'guest';

UPDATE smartledge_user
   SET password_hash = '$2b$10$M0JWMo7/zMCuXf15irSiJ.JcGyplJnpiadtzf.4eL0h3/8nuWNJrW',
       display_name = '作品集管理试用',
       status = 1,
       failed_attempts = 0,
       locked_until = NULL,
       edit_time = NOW()
 WHERE tenant_id = 1 AND username = 'reviewer';

INSERT IGNORE INTO smartledge_user_role (id, tenant_id, user_id, role_id, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-ur-', u.id, '-', r.id)), 1, 15), 16, 10),
       1, u.id, r.id, NOW(), NOW(), 1
  FROM smartledge_user u
  JOIN smartledge_role r
    ON r.tenant_id = 1 AND r.role_code = 'PORTFOLIO_GUEST' AND r.status = 1
 WHERE u.tenant_id = 1 AND u.username = 'guest' AND u.status = 1;

INSERT IGNORE INTO smartledge_user_role (id, tenant_id, user_id, role_id, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-ur-', u.id, '-', r.id)), 1, 15), 16, 10),
       1, u.id, r.id, NOW(), NOW(), 1
  FROM smartledge_user u
  JOIN smartledge_role r
    ON r.tenant_id = 1 AND r.role_code = 'PORTFOLIO_REVIEWER' AND r.status = 1
 WHERE u.tenant_id = 1 AND u.username = 'reviewer' AND u.status = 1;

INSERT INTO smartledge_document_acl
    (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-acl-role-', d.id, '-', r.id)), 1, 15), 16, 10),
       1, d.id, 'ROLE', r.id, 'READ', NOW(), NOW(), 1
  FROM smartledge_document d
  JOIN smartledge_role r
    ON r.tenant_id = 1
   AND r.role_code IN ('PORTFOLIO_GUEST', 'PORTFOLIO_REVIEWER')
   AND r.status = 1
 WHERE d.tenant_id = 1
   AND d.status = 1
   AND d.index_status = 3
   AND NOT EXISTS (
        SELECT 1
          FROM smartledge_document_acl a
         WHERE a.document_id = d.id
           AND a.principal_type = 'ROLE'
           AND a.principal_id = r.id
       );

INSERT INTO smartledge_document_acl
    (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('portfolio-acl-user-', d.id, '-', u.id)), 1, 15), 16, 10),
       1, d.id, 'USER', u.id, 'READ', NOW(), NOW(), 1
  FROM smartledge_document d
  JOIN smartledge_user u
    ON u.tenant_id = 1
   AND u.username IN ('guest', 'reviewer')
   AND u.status = 1
 WHERE d.tenant_id = 1
   AND d.status = 1
   AND d.index_status = 3
   AND NOT EXISTS (
        SELECT 1
          FROM smartledge_document_acl a
         WHERE a.document_id = d.id
           AND a.principal_type = 'USER'
           AND a.principal_id = u.id
       );

UPDATE smartledge_document_acl a
  JOIN smartledge_role r
    ON r.id = a.principal_id
   AND r.role_code IN ('PORTFOLIO_GUEST', 'PORTFOLIO_REVIEWER')
   SET a.status = 1,
       a.permission = 'READ',
       a.edit_time = NOW()
 WHERE a.principal_type = 'ROLE';

UPDATE smartledge_document_acl a
  JOIN smartledge_user u
    ON u.id = a.principal_id
   AND u.username IN ('guest', 'reviewer')
   SET a.status = 1,
       a.permission = 'READ',
       a.edit_time = NOW()
 WHERE a.principal_type = 'USER';

SELECT 'portfolio_users' AS k, u.username, r.role_code, u.status
  FROM smartledge_user u
  JOIN smartledge_user_role ur ON ur.user_id = u.id AND ur.status = 1
  JOIN smartledge_role r ON r.id = ur.role_id
 WHERE u.username IN ('guest', 'reviewer');

SELECT 'portfolio_acl' AS k, a.principal_type, d.id AS document_id, a.permission, a.status
  FROM smartledge_document_acl a
  JOIN smartledge_document d ON d.id = a.document_id
 WHERE a.principal_type = 'ROLE'
   AND a.principal_id IN (
        SELECT id FROM smartledge_role WHERE role_code IN ('PORTFOLIO_GUEST', 'PORTFOLIO_REVIEWER')
       );
