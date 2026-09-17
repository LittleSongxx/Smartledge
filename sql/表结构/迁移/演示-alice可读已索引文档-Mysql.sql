-- 演示：给 alice（USER）可读「已索引」文档。
--
-- 不授予 document:read-all，不改角色权限，不写生产库，除非操作人明确执行。
-- 幂等：按 (document_id, principal_type, principal_id) 唯一键 INSERT IGNORE。
-- 只覆盖 index_status = 3 的文档；未构建文档仍然不可检索。

USE smartledge;

INSERT IGNORE INTO smartledge_document_acl
    (id, tenant_id, document_id, principal_type, principal_id, permission, create_time, edit_time, status)
SELECT CONV(SUBSTRING(MD5(CONCAT('demo-acl-', d.id, '-alice')), 1, 15), 16, 10),
       d.tenant_id,
       d.id,
       'USER',
       u.id,
       'READ',
       NOW(),
       NOW(),
       1
  FROM smartledge_document d
  JOIN smartledge_user u
    ON u.tenant_id = d.tenant_id
   AND u.username = 'alice'
   AND u.status = 1
 WHERE d.status = 1
   AND d.index_status = 3;
