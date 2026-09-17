-- S24 文档 ACL 补全（供人后跑，本仓库自动化不执行）。
--
-- 幂等：按 (document_id, principal_type, principal_id) 唯一键 INSERT IGNORE。
-- 主键由 document_id + 本租户角色码派生，不使用常量 id，也不把租户 1 的角色授给其他租户文档。
-- 不要在双写窗口盲跑；不要对本脚本做真实验收库写入，除非操作人明确授权。

USE smartledge;

INSERT IGNORE INTO smartledge_document_acl
    (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('s21-acl-', d.id, '-ADMIN')), 1, 15), 16, 10),
       d.tenant_id, d.id, 'ROLE', admin_role.id, 'MANAGE', NOW(), NOW(), 1
  FROM smartledge_document d
  JOIN (
        SELECT tenant_id, MIN(id) AS id
          FROM smartledge_role
         WHERE role_code = 'ADMIN' AND status = 1
         GROUP BY tenant_id
  ) admin_role ON admin_role.tenant_id = d.tenant_id
 WHERE d.status = 1;

INSERT IGNORE INTO smartledge_document_acl
    (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('s21-acl-', d.id, '-CURATOR')), 1, 15), 16, 10),
       d.tenant_id, d.id, 'ROLE', curator_role.id, 'WRITE', NOW(), NOW(), 1
  FROM smartledge_document d
  JOIN (
        SELECT tenant_id, MIN(id) AS id
          FROM smartledge_role
         WHERE role_code = 'CURATOR' AND status = 1
         GROUP BY tenant_id
  ) curator_role ON curator_role.tenant_id = d.tenant_id
 WHERE d.status = 1;
