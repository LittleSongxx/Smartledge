-- S23 批次 2 夹具：租户 B（demo-b）的 `CURATOR` 角色（与租户 1 对称）
--
-- 为什么要这个脚本：S23 的目标形态是"租户内的知识资产维护者（CURATOR）能自己上传并维护文档"。
-- 这个角色在**租户 1 已经存在**（`smartledge_role` tenant 1 / CURATOR / 11 个权限），
-- 但租户 2 只有 `ADMIN` 与 `USER`（`records/S22.md` §17.4、`records/S23.md` §2.2 S23-E）。
-- 租户 2 因此没有"能上传、能维护、但不管理成员"的那一档，无法完整复现目标形态。
--
-- 权限集合**从租户 1 的 CURATOR 复制**，不在这里重写一份清单：手工维护第二份清单一定会漂移，
-- 而漂移的后果是"两个租户的同名角色能力不同"。复制源缺失时脚本不插入任何权限行（fail closed）。
--
-- 幂等：角色与权限两处插入都带 NOT EXISTS 守卫，重复执行不产生第二行。
-- 固定 ID 段：8900000000000000010 起（专用段；S22 已用 8900000000000000001/2，互不冲突）。
--
-- 执行者：按 AGENTS.md §8，写入真实验收库的初始化脚本由用户执行。
--
-- **必须用 utf8mb4 连接执行**，否则脚本里的中文会被双重编码（见 S22-修复CLI写入的中文乱码.sql）：
--   docker exec -i smartledge-mysql mysql -uroot -psmartledge --default-character-set=utf8mb4 smartledge < 本文件

-- 1) 租户 2 的 CURATOR 角色
INSERT INTO smartledge_role
    (id, tenant_id, role_code, role_name, description, built_in, create_time, edit_time, status)
SELECT 8900000000000000010, 2, 'CURATOR', '知识库管理员',
       '文档接入、解析、索引与权限管理，不含用户管理', 1, NOW(), NOW(), 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM smartledge_role WHERE tenant_id = 2 AND role_code = 'CURATOR'
);

-- 2) 权限集合：从租户 1 的 CURATOR 复制（缺一行就补一行）
INSERT INTO smartledge_role_permission
    (id, tenant_id, role_id, permission_id, create_time, edit_time, status)
SELECT 8900000000000000100 + ROW_NUMBER() OVER (ORDER BY source.permission_id),
       2,
       target.id,
       source.permission_id,
       NOW(), NOW(), 1
FROM smartledge_role target
JOIN smartledge_role owner ON owner.tenant_id = 1 AND owner.role_code = 'CURATOR'
JOIN smartledge_role_permission source ON source.role_id = owner.id AND source.status = 1
WHERE target.tenant_id = 2
  AND target.role_code = 'CURATOR'
  AND NOT EXISTS (
      SELECT 1 FROM smartledge_role_permission existing
       WHERE existing.role_id = target.id
         AND existing.permission_id = source.permission_id
  );

-- 3) 只读复核：两侧 CURATOR 的角色 id 与权限数
SELECT r.tenant_id,
       r.id AS role_id,
       r.role_code,
       COUNT(rp.permission_id) AS permission_count
  FROM smartledge_role r
  LEFT JOIN smartledge_role_permission rp ON rp.role_id = r.id AND rp.status = 1
 WHERE r.role_code = 'CURATOR'
 GROUP BY r.tenant_id, r.id, r.role_code
 ORDER BY r.tenant_id;
