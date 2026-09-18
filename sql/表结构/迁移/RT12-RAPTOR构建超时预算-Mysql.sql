-- RT12 RAPTOR 构建读超时预算上调（2026-09-19）
--
-- 背景：ragTools.raptorBuildReadTimeoutMs 默认 600000ms（10 分钟）。RAPTOR 开启
-- LLM 摘要（raptorLlmSummaryEnabled）时，300+ chunk 的文档仅聚类摘要就要 10–20 分钟，
-- Java 侧读超时先到 → 任务失败（线上 HiPNUC_HI15 在 362 批 GraphRAG 全部完成后卡死于此）。
--
-- 生效条件：应用重启后读取托管配置快照。已有环境若曾按旧值手工初始化，执行本文件对齐。
-- 幂等：JSON_SET 直接覆盖为 3600000，可重复执行。

UPDATE smartledge.smartledge_system_config
SET config_json = JSON_SET(config_json, '$.values."ragTools.raptorBuildReadTimeoutMs"', CAST(3600000 AS JSON)),
    config_version = config_version + 1,
    edit_time = NOW()
WHERE id = 1
  AND CAST(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.values."ragTools.raptorBuildReadTimeoutMs"')) AS UNSIGNED) < 3600000;
