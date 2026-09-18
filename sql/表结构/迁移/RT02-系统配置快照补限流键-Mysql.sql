-- RT02 系统配置快照补限流键（存量库升级必做）。
--
-- 背景：SystemConfigSnapshotCodec.deserialize 按注册表严格校验存量快照的 values，
-- 缺任何一个已注册键即拒绝启动（fail-closed）。RT 轨道新增 chat.rateLimit.* 两个键、
-- 退休 graphRag.communityReport.enabled 一个键；旧快照不打本补丁，新 jar 启动即失败：
--   SuperAgentFrameException: 系统配置快照缺少配置项: chat.rateLimit.perMinutePerUser
--
-- 值与 SystemConfigSnapshot.ChatRateLimitOptions 默认值一致（10 次/分钟、5 并发），
-- 上线后可在管理台「系统配置」调整。多余键 tolerated，删除 communityReport 仅做清理。
-- 可重复执行（JSON_SET 幂等；JSON_REMOVE 对不存在键 no-op）。执行前建议整表备份。
-- 线上已执行记录：2026-09-18 smartledge.cn（备份表 smartledge_system_config_backup_rt09）。

USE smartledge;

UPDATE smartledge_system_config
SET config_json = JSON_REMOVE(
        JSON_SET(config_json,
                 '$.values."chat.rateLimit.perMinutePerUser"', 10,
                 '$.values."chat.rateLimit.concurrentPerTenant"', 5),
        '$.values."graphRag.communityReport.enabled"'),
    config_version = config_version + 1,
    edit_time = NOW()
WHERE id = 1;

SELECT JSON_EXTRACT(config_json, '$.values."chat.rateLimit.perMinutePerUser"') AS perMinutePerUser,
       JSON_EXTRACT(config_json, '$.values."chat.rateLimit.concurrentPerTenant"') AS concurrentPerTenant,
       JSON_EXTRACT(config_json, '$.values."graphRag.communityReport.enabled"') AS retiredCommunityReport,
       config_version
FROM smartledge_system_config;
