-- S22 附：修复通过 mysql CLI 写入时产生的中文乱码（cp1252/latin1 双重编码）
--
-- 现象：管理端「文档接入」把租户 2 的夹具文档名显示成 `Bç§Ÿæˆ·æ–‡æ¡£`，租户名显示成 `ç¤ºä¾‹ç§Ÿæˆ·B`。
--
-- 结论（字节级验证）：**不是加密，是乱码**。存储的字节 = 原始 UTF-8 被当作 cp1252/latin1 解码后
-- 又按 UTF-8 存了一遍。以 `示例租户B` 为例：
--     正确 UTF-8     : E7A4BA E4BE8B E7A79F E688B7 42
--     实际存储的字节 : C3A7C2A4C2BA C3A4C2BEE280B9 C3A7C2A7C5B8 C3A6CB86C2B7 42
--   （逐字节等于 cp1252 双重编码，不是 latin1 变体——差别在 0x96/0x87/0x9F/0x8B 这几个字节，
--     cp1252 把它们映射成 – ‡ Ÿ ‹，因此会留下 E28093 / E280A1 这样的痕迹。）
--
-- 根因：这些行是**经 mysql 命令行客户端**写入的（S21 的种子数据、以及本阶段的租户 B 管理员脚本），
-- 而该客户端的连接字符集不是 utf8mb4，于是把脚本里的 UTF-8 字节当成 latin1 再转存了一次。
-- **应用自身不受影响**：chunk / kg_entity / raptor_node 等由 JDBC（characterEncoding=UTF-8）写入的内容
-- 全部干净（已全表扫描：chunk_text 660 行、kg_entity.name 255 行均 0 命中）。
--
-- 一个必须记下来的陷阱：用**同一个 latin1 连接**把这些值读回来时，往返会把字节还原成原始 UTF-8，
-- 于是终端里看起来完全正常——本阶段第一次核对时就被这一点骗过。判定乱码必须看 HEX()，不能看终端输出。
--
-- 影响范围（全库字节级扫描，仅显示字段）：tenant_name 2 行、role_name 5 行、user.display_name 4 行、
-- document.document_name 1 行。
--
-- 修复原理：`CONVERT(BINARY(CONVERT(col USING latin1)) USING utf8mb4)`
--   ——字符 → latin1(cp1252 超集) 字节 → 按 UTF-8 解释。已在真实数据上逐行验证：
--      `默认租户` E9BB98E8AEA4E7A79FE688B7 ✓  `示例租户B` E7A4BAE4BE8BE7A79FE688B742 ✓  `B租户文档` 42E7A79FE688B7E69687E6A1A3 ✓
--
-- 幂等且带守卫：WHERE 里的字节模式只匹配"仍然是乱码"的行；已修复的行不再匹配 → 重复执行是空操作。
-- 可逆：反向变换即 `CONVERT(BINARY(CONVERT(col USING utf8mb4)) USING latin1)` 再转回 utf8mb4…… 若需回退请先备份。
--
-- **必须用 utf8mb4 连接执行**（否则修好的值会被再编码一次）：
--   docker exec -i smartledge-mysql mysql -uroot -psmartledge --default-character-set=utf8mb4 smartledge < 本文件

-- 1) 租户名
UPDATE smartledge.smartledge_tenant
SET tenant_name = CONVERT(BINARY(CONVERT(tenant_name USING latin1)) USING utf8mb4)
WHERE HEX(tenant_name) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6';

-- 2) 角色名
UPDATE smartledge.smartledge_role
SET role_name = CONVERT(BINARY(CONVERT(role_name USING latin1)) USING utf8mb4)
WHERE HEX(role_name) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6';

-- 3) 用户显示名
UPDATE smartledge.smartledge_user
SET display_name = CONVERT(BINARY(CONVERT(display_name USING latin1)) USING utf8mb4)
WHERE HEX(COALESCE(display_name, '')) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6';

-- 4) 文档名（租户 2 的夹具行）
UPDATE smartledge.smartledge_document
SET document_name = CONVERT(BINARY(CONVERT(document_name USING latin1)) USING utf8mb4)
WHERE HEX(COALESCE(document_name, '')) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6';

-- 5) 复核：应全部为正确中文，且不再有乱码字节模式
SELECT 'tenant' AS t, tenant_code AS k, tenant_name AS v, HEX(tenant_name) AS h FROM smartledge.smartledge_tenant
UNION ALL
SELECT 'role', role_code, role_name, HEX(role_name) FROM smartledge.smartledge_role
UNION ALL
SELECT 'user', username, display_name, HEX(COALESCE(display_name,'')) FROM smartledge.smartledge_user
UNION ALL
SELECT 'document', CAST(id AS CHAR), document_name, HEX(COALESCE(document_name,'')) FROM smartledge.smartledge_document;

SELECT '剩余乱码行数' AS check_name,
       (SELECT COUNT(*) FROM smartledge.smartledge_tenant WHERE HEX(tenant_name) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6')
     + (SELECT COUNT(*) FROM smartledge.smartledge_role WHERE HEX(role_name) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6')
     + (SELECT COUNT(*) FROM smartledge.smartledge_user WHERE HEX(COALESCE(display_name,'')) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6')
     + (SELECT COUNT(*) FROM smartledge.smartledge_document WHERE HEX(COALESCE(document_name,'')) REGEXP 'C3A7|C5B8|E28093|E280A1|C3A6')
       AS remaining;
