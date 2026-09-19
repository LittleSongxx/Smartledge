-- RT14 最终证据最低置信度配置键（2026-09-19）
--
-- 背景：语料外问题全量返回 top-k（emptyScopeCorrectness=0）。新增
-- ragRuntime.minEvidenceConfidence：rerank SUCCESS 时低于该置信度的候选在最终证据
-- 阶段被过滤（FILTERED_BELOW_CONFIDENCE），全部低于则按无证据处理走 noEvidenceReply。
-- 生产默认 0.30（2026-09-19 探针扫参：垃圾候选 rerank 分 ≤0.24，正确答案 >0.35，0.30 全切语料外且 in-scope 零损失）。
--
-- 生效条件：NEW_CONVERSATION（新会话生效）；应用启动时快照 fail-closed 校验需要该键存在。
-- 幂等：JSON_SET 直接写入，可重复执行。

UPDATE smartledge.smartledge_system_config
SET config_json = JSON_SET(config_json, '$.values."ragRuntime.minEvidenceConfidence"', CAST(0.30 AS JSON)),
    config_version = config_version + 1,
    edit_time = NOW()
WHERE id = 1;
