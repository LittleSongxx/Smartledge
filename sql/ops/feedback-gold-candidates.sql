-- 反馈驱动的检索金标候选导出（质量回路的线上数据入口）。
--
-- 背景：rag-gold.v1.json 目前只有 15 条合成金标，没有线上数据来源。
-- 用户端每轮回答可点「有帮助 / 没帮助」（smartledge_chat_exchange_feedback），
-- 其中 DOWN（没帮助）反馈是检索质量问题的最直接信号。
--
-- 用法（只读，人工确认后再并入金标）：
--   1. 运维在 MySQL 执行本查询，导出 JSON（DBeaver/mysql -e + jq 均可）；
--   2. 人工逐条确认：问题是否该命中当前知识库、期望应命中的文档/章节；
--   3. 按 rag-gold.v1.json 的 case 结构补写 question 与期望 identity 集合，
--      并入 resources/eval/rag-gold.v1.json（人工确认前绝不直接机灌）。
--  说明：identity 用合成引用键（与现有金标一致），不是索引行 id。
--
-- 排序：DOWN 优先，其次反馈时间倒序；包含 UP 对照组便于排查"答得好但用户仍不满"的误报。

SELECT
    f.exchange_id                                             AS exchangeId,
    f.dialogue_code                                           AS conversationId,
    CASE f.rating WHEN 1 THEN 'UP' ELSE 'DOWN' END            AS rating,
    f.comment                                                 AS feedbackComment,
    f.edit_time                                               AS feedbackTime,
    e.user_prompt                                             AS question,
    LEFT(e.reply_content, 2000)                               AS answerPreview,
    e.exchange_state                                          AS turnStatus,
    e.source_snapshot_list                                    AS referencesJson,
    d.selected_document_id                                    AS selectedDocumentId,
    d.selected_document_name                                  AS selectedDocumentName,
    d.chat_mode                                               AS chatMode
FROM smartledge_chat_exchange_feedback f
JOIN smartledge_chat_exchange e
  ON e.id = f.exchange_id AND e.dialogue_code = f.dialogue_code
JOIN smartledge_chat_dialogue d
  ON d.dialogue_code = f.dialogue_code
WHERE f.status = 1
  AND e.status = 1
ORDER BY CASE f.rating WHEN -1 THEN 0 ELSE 1 END, f.edit_time DESC
LIMIT 200;
