-- S22 批次 2 验收夹具：租户 B（demo-b）的管理员账号
--
-- 为什么要这个脚本：批次 2（派生数据 tenant_id 归属）的验收必须在**租户 2** 有一份真实文档——
-- 租户 1 的派生行取列默认值 1 恰好等于正确值，因此租户 1 无论怎么构建都证明不了修复。
--
-- 而租户 2 当前只有 `bob`（普通用户，无管理权限），上传入口在产品里只存在于管理端
-- （`/manage/document/upload`，需 `document:upload` + 管理端用途 token），所以租户 2 需要一个管理员账号。
-- 租户 2 的 `ADMIN` 角色**已经存在**（role id 见下方子查询），本脚本只是把用户与角色绑上。
--
-- 口令复用 alice 的 BCrypt 哈希 → 口令为 `user123456`（种子口令，见 records/S21.md §11.5）。
-- 不新造口令策略、不写入明文。
--
-- 幂等：两处 INSERT 都带 NOT EXISTS 守卫，重复执行不产生第二行。
-- 固定 ID 段：8900000000000000001 / 8900000000000000002（专用段，和既有数据不冲突）。
--
-- 执行者：按 AGENTS.md §8，写入真实验收库的初始化脚本由用户执行。
--
-- **必须用 utf8mb4 连接执行**，否则脚本里的中文会被双重编码（见 S22-修复CLI写入的中文乱码.sql）：
--   docker exec -i smartledge-mysql mysql -uroot -psmartledge --default-character-set=utf8mb4 smartledge < 本文件

INSERT INTO smartledge_user
    (id, tenant_id, username, password_hash, display_name, failed_attempts, create_time, edit_time, status)
SELECT 8900000000000000001, 2, 'tenantb_admin',
       (SELECT password_hash FROM smartledge_user WHERE tenant_id = 1 AND username = 'alice'),
       '租户B 管理员', 0, NOW(), NOW(), 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM smartledge_user WHERE tenant_id = 2 AND username = 'tenantb_admin'
);

INSERT INTO smartledge_user_role
    (id, tenant_id, user_id, role_id, create_time, edit_time, status)
SELECT 8900000000000000002, 2,
       (SELECT id FROM smartledge_user WHERE tenant_id = 2 AND username = 'tenantb_admin'),
       (SELECT id FROM smartledge_role WHERE tenant_id = 2 AND role_code = 'ADMIN'),
       NOW(), NOW(), 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM smartledge_user_role
    WHERE tenant_id = 2
      AND user_id = (SELECT id FROM smartledge_user WHERE tenant_id = 2 AND username = 'tenantb_admin')
);

-- 复核：应返回 1 行，角色为 ADMIN
SELECT u.id, u.tenant_id, u.username, u.display_name, u.status, r.role_code
FROM smartledge_user u
JOIN smartledge_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
JOIN smartledge_role r ON r.id = ur.role_id AND r.tenant_id = u.tenant_id
WHERE u.tenant_id = 2 AND u.username = 'tenantb_admin';
