-- S23 附：补修 S22 漏掉的 CLI 双重编码列（cp1252/latin1 乱码）
--
-- 背景：S22 已经修过一轮（见 S22-修复CLI写入的中文乱码.sql），但那份脚本的守卫只覆盖了
-- `tenant_name` / `role_name` / `user.display_name` / `document.document_name` 四列。
-- S23 开工时做全库字节级扫描（information_schema 生成 249 条探测语句），发现还有 **8 个列** 仍是双重编码：
--
--   smartledge_permission.permission_name          15 行
--   smartledge_permission.description              15 行
--   smartledge_role.description                     5 行
--   smartledge_tenant.description                   2 行
--   smartledge_system_config_group.group_label      4 行
--   smartledge_system_config_group.group_description 6 行
--   smartledge_system_config_category.category_label 20 行
--   smartledge_system_config_category.category_description 22 行
--
-- 证据（字节级）：`console:access` 的 permission_name 十六进制 = C3A7C2AEC2A1...（逐字节等于 cp1252 双重编码），
-- 而同一行的 permission_code 是干净的 ASCII；对照已修好的 role_name = E79FA5E8AF86E5BA93E7AEA1E79086E59198。
--
-- 影响面：**只是显示**。授权判定用的 `permission_code` 与角色编码全部是 ASCII、未受影响；
-- 前端界面（参数配置的分组/分类标签、任何展示权限中文名的地方）才会看到乱码。
--
-- 修复原理（与 S22 同一变换）：`CONVERT(BINARY(CONVERT(col USING latin1)) USING utf8mb4)`
--   ——字符 → latin1(cp1252 超集) 字节 → 按 UTF-8 解释。
--
-- 幂等且带守卫。**守卫判据在第一版里是错的**（沿用 S22 的字节模式白名单 C3A7|C5B8|E28093|E280A1|C3A6）：
-- 那个白名单只覆盖 S22 那些行恰好出现过的字节组合，对 `å®‰å…¨ä¸Žè®¿é—®`（安全与访问）这类行完全失配，
-- 实测漏掉 3 行（group_label 1 行 + category_label 2 行），并且脚本自带的复核会给出"剩余 0"的假阴性。
-- 现改用**语义判据**：非 ASCII 且 `latin1` 转换不产生任何不可映射字符（'?'）。
--   · 真正的双重编码值 → 每个字符都在 latin1/cp1252 里 → 转换无 '?' → 命中；
--   · 干净中文 → 汉字在 latin1 里不可映射 → 转换产生 '?' → 不命中；
--   · 纯 ASCII → 被"非 ASCII"条件排除。
-- 已知误报类：合法的 Latin-1 补充区字符（如 `°C`、`2×rs485` 的 ° 与 ×）也会命中，因此每列都先复核再修：
-- 本脚本只作用于「配置分组/分类标签与描述」这些**中文标签列**（内容表不在范围内，其命中项已逐条复核为合法字符）。
-- 可逆：反向变换即 `CONVERT(BINARY(CONVERT(col USING utf8mb4)) USING latin1)` 再转回 utf8mb4；若需回退请先备份。
--
-- **必须用 utf8mb4 连接执行**（否则修好的值会被再编码一次）：
--   docker exec -i smartledge-mysql mysql -uroot -psmartledge --default-character-set=utf8mb4 smartledge < 本文件

-- 1) 权限字典：显示名与描述
UPDATE smartledge.smartledge_permission
SET permission_name = CONVERT(BINARY(CONVERT(permission_name USING latin1)) USING utf8mb4)
WHERE `permission_name` <> CONVERT(`permission_name` USING ascii) AND HEX(CONVERT(`permission_name` USING latin1)) NOT LIKE '%3F%';

UPDATE smartledge.smartledge_permission
SET description = CONVERT(BINARY(CONVERT(description USING latin1)) USING utf8mb4)
WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%';

-- 2) 角色描述
UPDATE smartledge.smartledge_role
SET description = CONVERT(BINARY(CONVERT(description USING latin1)) USING utf8mb4)
WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%';

-- 3) 租户描述
UPDATE smartledge.smartledge_tenant
SET description = CONVERT(BINARY(CONVERT(description USING latin1)) USING utf8mb4)
WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%';

-- 4) 参数分组标签与描述
UPDATE smartledge.smartledge_system_config_group
SET group_label = CONVERT(BINARY(CONVERT(group_label USING latin1)) USING utf8mb4)
WHERE `group_label` <> CONVERT(`group_label` USING ascii) AND HEX(CONVERT(`group_label` USING latin1)) NOT LIKE '%3F%';

UPDATE smartledge.smartledge_system_config_group
SET group_description = CONVERT(BINARY(CONVERT(group_description USING latin1)) USING utf8mb4)
WHERE `group_description` <> CONVERT(`group_description` USING ascii) AND HEX(CONVERT(`group_description` USING latin1)) NOT LIKE '%3F%';

-- 5) 参数分类标签与描述
UPDATE smartledge.smartledge_system_config_category
SET category_label = CONVERT(BINARY(CONVERT(category_label USING latin1)) USING utf8mb4)
WHERE `category_label` <> CONVERT(`category_label` USING ascii) AND HEX(CONVERT(`category_label` USING latin1)) NOT LIKE '%3F%';

UPDATE smartledge.smartledge_system_config_category
SET category_description = CONVERT(BINARY(CONVERT(category_description USING latin1)) USING utf8mb4)
WHERE `category_description` <> CONVERT(`category_description` USING ascii) AND HEX(CONVERT(`category_description` USING latin1)) NOT LIKE '%3F%';

-- 6) 复核：逐列剩余的乱码行数应为 0，且修好的值应是合法 UTF-8 中文
SELECT 'permission_name' AS column_name, COUNT(*) AS remaining FROM smartledge.smartledge_permission
 WHERE `permission_name` <> CONVERT(`permission_name` USING ascii) AND HEX(CONVERT(`permission_name` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'permission.description', COUNT(*) FROM smartledge.smartledge_permission
 WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'role.description', COUNT(*) FROM smartledge.smartledge_role
 WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'tenant.description', COUNT(*) FROM smartledge.smartledge_tenant
 WHERE `description` <> CONVERT(`description` USING ascii) AND HEX(CONVERT(`description` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'group.group_label', COUNT(*) FROM smartledge.smartledge_system_config_group
 WHERE `group_label` <> CONVERT(`group_label` USING ascii) AND HEX(CONVERT(`group_label` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'group.group_description', COUNT(*) FROM smartledge.smartledge_system_config_group
 WHERE `group_description` <> CONVERT(`group_description` USING ascii) AND HEX(CONVERT(`group_description` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'category.category_label', COUNT(*) FROM smartledge.smartledge_system_config_category
 WHERE `category_label` <> CONVERT(`category_label` USING ascii) AND HEX(CONVERT(`category_label` USING latin1)) NOT LIKE '%3F%'
UNION ALL
SELECT 'category.category_description', COUNT(*) FROM smartledge.smartledge_system_config_category
 WHERE `category_description` <> CONVERT(`category_description` USING ascii) AND HEX(CONVERT(`category_description` USING latin1)) NOT LIKE '%3F%';

SELECT permission_code, permission_name, HEX(permission_name) AS hex_name FROM smartledge.smartledge_permission ORDER BY id;
