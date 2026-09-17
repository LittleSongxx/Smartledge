/*
  O2-O8 RAG 完整测试知识路由初始化脚本

  当前脚本适用于 KnowledgeBase 硬边界改造后的新模型。

  脚本固定使用预留 ID 段 9000000000000000000，不需要设置会话参数。

  使用顺序：
  1. 直接执行时匹配以下 3 个知识库：
     - 解析回归知识库
     - 运营制度知识库
     - GraphRAG图谱评测知识库
  2. 在管理端创建这 3 个测试知识库。
  3. 按 document/O2-O8-RAG能力完整测试验收方案.md 第 1 步上传清单上传 15 份必传样例。
  4. 等每份文档完成解析、策略确认和索引构建，确认 parse_status=3、strategy_status=3、index_status=3。
  5. 在同一 MySQL 会话执行本脚本。每个知识库名和 (knowledge_base_id, original_file_name)
     必须唯一匹配，脚本不会按“最新”记录猜测。

  本脚本会创建或更新：
  - smartledge_knowledge_scope_node
  - smartledge_knowledge_topic_node
  - smartledge_topic_document_relation
  - smartledge_document_profile

  本脚本不会：
  - 创建知识库。
  - 修改文档所属知识库。
  - 修改文档解析、策略方案、索引状态、chunk、向量库、ES/BM25、KG 或 RAPTOR 数据。
  - 写入旧字段：知识域编码、知识域名称、业务分类、文档标签。

  重复执行可幂等重放；KB/文档匹配缺失或歧义、以及目标 ID 段归属冲突会在永久写入前停止。
*/

USE smartledge;

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

/* =========================================================
   0. 固定配置与断言临时表
   ========================================================= */

SET @base_id = 9000000000000000000;

SET @kb_parse_name = '解析回归知识库';
SET @kb_operation_name = '运营制度知识库';
SET @kb_graph_name = 'GraphRAG图谱评测知识库';

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_assert_fail;
CREATE TEMPORARY TABLE tmp_o2_o8_assert_fail (
    id INT NOT NULL PRIMARY KEY,
    reason VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
VALUES (1, 'sentinel');

SET @file_o2_provider_pdf = 'O2-provider-artifact验收样例.pdf';
SET @file_o2_ocr_pdf = 'O2-扫描OCR验收样例-图片型PDF.pdf';
SET @file_o2_ocr_png = 'O2-扫描OCR验收样例-文字截图.png';

SET @file_xinglian = '星联智服全渠道客服平台上线与运营管理手册.md';
SET @file_release = '生产环境发布与回滚操作规范.md';
SET @file_incident = '核心业务系统故障应急响应预案.md';
SET @file_data_policy = '客户数据分级与访问控制管理制度.md';
SET @file_travel = '差旅与费用报销管理办法.md';
SET @file_onboarding = '澄星智能新员工入职培训手册.md';

SET @file_audit_evidence = 'O6跨文档图谱-审计证据规范A.md';
SET @file_audit_alias = 'O6跨文档图谱-审计系统别名说明B.md';
SET @file_release_graph_spec = 'O6多社区排序-生产发布回滚规范A.md';
SET @file_release_graph_alias = 'O6多社区排序-生产发布回滚别名B.md';
SET @file_data_graph_spec = 'O6多社区排序-客户数据访问控制规范A.md';
SET @file_data_graph_alias = 'O6多社区排序-客户数据访问控制别名B.md';

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_required_kb;
CREATE TEMPORARY TABLE tmp_o2_o8_required_kb (
    kb_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL PRIMARY KEY,
    expected_doc_count INT NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_o2_o8_required_kb (kb_name, expected_doc_count)
VALUES
    (@kb_parse_name, 3),
    (@kb_operation_name, 6),
    (@kb_graph_name, 6);

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_expected_document;
CREATE TEMPORARY TABLE tmp_o2_o8_expected_document (
    batch_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    kb_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    original_file_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    PRIMARY KEY (kb_name, original_file_name)
) ENGINE=InnoDB;

INSERT INTO tmp_o2_o8_expected_document (batch_code, kb_name, original_file_name)
VALUES
    ('A-O2', @kb_parse_name, @file_o2_provider_pdf),
    ('A-O2', @kb_parse_name, @file_o2_ocr_pdf),
    ('A-O2', @kb_parse_name, @file_o2_ocr_png),

    ('B-业务', @kb_operation_name, @file_xinglian),
    ('B-业务', @kb_operation_name, @file_release),
    ('B-业务', @kb_operation_name, @file_incident),
    ('B-业务', @kb_operation_name, @file_data_policy),
    ('B-业务', @kb_operation_name, @file_travel),
    ('B-业务', @kb_operation_name, @file_onboarding),

    ('C-O6', @kb_graph_name, @file_audit_evidence),
    ('C-O6', @kb_graph_name, @file_audit_alias),
    ('C-O6', @kb_graph_name, @file_release_graph_spec),
    ('C-O6', @kb_graph_name, @file_release_graph_alias),
    ('C-O6', @kb_graph_name, @file_data_graph_spec),
    ('C-O6', @kb_graph_name, @file_data_graph_alias);

/* =========================================================
   1. 唯一解析知识库和文档 ID
   ========================================================= */

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_kb;
CREATE TEMPORARY TABLE tmp_o2_o8_kb AS
SELECT
    required.kb_name,
    required.expected_doc_count,
    COUNT(kb.id) AS match_count,
    IF(COUNT(kb.id) = 1, MAX(kb.id), NULL) AS knowledge_base_id
FROM tmp_o2_o8_required_kb required
LEFT JOIN smartledge_knowledge_base kb
  ON kb.status = 1
 AND kb.base_name = required.kb_name
GROUP BY required.kb_name, required.expected_doc_count;

SET @missing_kbs = (
    SELECT GROUP_CONCAT(kb_name ORDER BY kb_name SEPARATOR ', ')
    FROM tmp_o2_o8_kb
    WHERE match_count = 0
);

SET @ambiguous_kbs = (
    SELECT GROUP_CONCAT(CONCAT(kb_name, '(count=', match_count, ')') ORDER BY kb_name SEPARATOR ', ')
    FROM tmp_o2_o8_kb
    WHERE match_count > 1
);

SELECT
    CASE
        WHEN @missing_kbs IS NULL OR @missing_kbs = '' THEN 'OK: 3 个测试知识库均已找到'
        ELSE CONCAT('ERROR: 以下知识库不存在或未启用，请先创建后再执行脚本: ', @missing_kbs)
        END AS knowledge_base_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'missing enabled knowledge base'
    WHERE @missing_kbs IS NOT NULL AND @missing_kbs <> '';

SELECT
    CASE
        WHEN @ambiguous_kbs IS NULL OR @ambiguous_kbs = '' THEN 'OK: 3 个测试知识库名称均唯一'
        ELSE CONCAT('ERROR: 以下知识库名称存在多个启用记录: ', @ambiguous_kbs)
        END AS knowledge_base_uniqueness_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'ambiguous enabled knowledge base'
WHERE @ambiguous_kbs IS NOT NULL AND @ambiguous_kbs <> '';

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_latest_document;
CREATE TEMPORARY TABLE tmp_o2_o8_latest_document AS
SELECT
    expected.batch_code,
    expected.kb_name,
    kb.knowledge_base_id,
    expected.original_file_name,
    COUNT(d.id) AS match_count,
    IF(COUNT(d.id) = 1, MAX(d.id), NULL) AS document_id
FROM tmp_o2_o8_expected_document expected
JOIN tmp_o2_o8_kb kb ON kb.kb_name = expected.kb_name
LEFT JOIN smartledge_document d
  ON d.status = 1
 AND d.knowledge_base_id = kb.knowledge_base_id
 AND d.original_file_name = expected.original_file_name
GROUP BY expected.batch_code, expected.kb_name, kb.knowledge_base_id, expected.original_file_name;

SET @missing_docs = (
    SELECT GROUP_CONCAT(CONCAT(kb_name, '/', original_file_name) ORDER BY kb_name, original_file_name SEPARATOR '; ')
    FROM tmp_o2_o8_latest_document
    WHERE match_count = 0
);

SET @ambiguous_docs = (
    SELECT GROUP_CONCAT(
        CONCAT(kb_name, '/', original_file_name, '(count=', match_count, ')')
        ORDER BY kb_name, original_file_name SEPARATOR '; '
    )
    FROM tmp_o2_o8_latest_document
    WHERE match_count > 1
);

SELECT
    CASE
        WHEN @missing_docs IS NULL OR @missing_docs = '' THEN 'OK: 已按知识库和文件名找到 15 份有效文档'
        ELSE CONCAT('ERROR: 以下文档未在预期知识库下找到 status=1 记录: ', @missing_docs)
        END AS document_id_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'missing expected document'
    WHERE @missing_docs IS NOT NULL AND @missing_docs <> '';

SELECT
    CASE
        WHEN @ambiguous_docs IS NULL OR @ambiguous_docs = '' THEN 'OK: 15 个预期文件名在各自知识库内均唯一'
        ELSE CONCAT('ERROR: 以下预期文件存在多个有效记录，禁止按最新记录猜测: ', @ambiguous_docs)
        END AS document_uniqueness_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'ambiguous expected document'
WHERE @ambiguous_docs IS NOT NULL AND @ambiguous_docs <> '';

SET @not_ready_docs = (
    SELECT GROUP_CONCAT(CONCAT(
        latest.kb_name, '/',
        d.original_file_name,
        '(parse=', IFNULL(CAST(d.parse_status AS CHAR), 'NULL'),
        ', strategy=', IFNULL(CAST(d.strategy_status AS CHAR), 'NULL'),
        ', index=', IFNULL(CAST(d.index_status AS CHAR), 'NULL'),
        ', task=', IFNULL(CAST(d.last_index_task_id AS CHAR), 'NULL'),
        ')'
    ) ORDER BY latest.kb_name, d.original_file_name SEPARATOR '; ')
    FROM tmp_o2_o8_latest_document latest
    JOIN smartledge_document d ON d.id = latest.document_id
    WHERE IFNULL(d.parse_status, -1) <> 3
       OR IFNULL(d.strategy_status, -1) <> 3
       OR IFNULL(d.index_status, -1) <> 3
       OR d.last_index_task_id IS NULL
);

SELECT
    CASE
        WHEN @not_ready_docs IS NULL OR @not_ready_docs = '' THEN 'OK: 15 份文档均已完成解析、策略确认和索引构建'
        ELSE CONCAT('ERROR: 以下文档未完成 parse=3、strategy=3、index=3 或缺 last_index_task_id: ', @not_ready_docs)
        END AS document_status_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'document not ready'
    WHERE @not_ready_docs IS NOT NULL AND @not_ready_docs <> '';

SET @kb_parse_id = (SELECT knowledge_base_id FROM tmp_o2_o8_kb WHERE kb_name = @kb_parse_name);
SET @kb_operation_id = (SELECT knowledge_base_id FROM tmp_o2_o8_kb WHERE kb_name = @kb_operation_name);
SET @kb_graph_id = (SELECT knowledge_base_id FROM tmp_o2_o8_kb WHERE kb_name = @kb_graph_name);

SET @doc_o2_provider_pdf_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_o2_provider_pdf);
SET @doc_o2_ocr_pdf_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_o2_ocr_pdf);
SET @doc_o2_ocr_png_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_o2_ocr_png);

SET @doc_xinglian_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_xinglian);
SET @doc_release_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_release);
SET @doc_incident_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_incident);
SET @doc_data_policy_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_data_policy);
SET @doc_travel_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_travel);
SET @doc_onboarding_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_onboarding);

SET @doc_audit_evidence_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_audit_evidence);
SET @doc_audit_alias_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_audit_alias);
SET @doc_release_graph_spec_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_release_graph_spec);
SET @doc_release_graph_alias_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_release_graph_alias);
SET @doc_data_graph_spec_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_data_graph_spec);
SET @doc_data_graph_alias_id = (SELECT document_id FROM tmp_o2_o8_latest_document WHERE original_file_name = @file_data_graph_alias);

SELECT
    latest.batch_code,
    latest.kb_name,
    latest.knowledge_base_id,
    d.id AS document_id,
    d.document_name,
    d.original_file_name,
    d.parse_status,
    d.strategy_status,
    d.index_status,
    d.last_index_task_id
FROM tmp_o2_o8_latest_document latest
         JOIN smartledge_document d ON d.id = latest.document_id
ORDER BY latest.kb_name, latest.original_file_name;

/* =========================================================
   2. 固定 ID
   ========================================================= */

SET @scope_parse_id = @base_id + 1;
SET @scope_operation_id = @base_id + 2;
SET @scope_graph_id = @base_id + 3;

SET @topic_o2_docmind_id = @base_id + 101;
SET @topic_o2_ocr_id = @base_id + 102;
SET @topic_o2_table_bbox_id = @base_id + 103;

SET @topic_operation_xinglian_id = @base_id + 201;
SET @topic_operation_release_id = @base_id + 202;
SET @topic_operation_incident_id = @base_id + 203;
SET @topic_operation_data_id = @base_id + 204;
SET @topic_operation_travel_id = @base_id + 205;
SET @topic_operation_onboarding_id = @base_id + 206;
SET @topic_operation_raptor_id = @base_id + 207;

SET @topic_graph_audit_id = @base_id + 301;
SET @topic_graph_release_id = @base_id + 302;
SET @topic_graph_data_id = @base_id + 303;
SET @topic_graph_boundary_id = @base_id + 304;

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_expected_ownership;
CREATE TEMPORARY TABLE tmp_o2_o8_expected_ownership (
    entity_type VARCHAR(16) NOT NULL,
    entity_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NULL,
    parent_id BIGINT NULL,
    document_id BIGINT NULL,
    business_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    PRIMARY KEY (entity_type, entity_id)
) ENGINE=InnoDB;

INSERT INTO tmp_o2_o8_expected_ownership
    (entity_type, entity_id, knowledge_base_id, parent_id, document_id, business_name)
VALUES
    ('SCOPE', @scope_parse_id, @kb_parse_id, NULL, NULL, 'O2 解析固定回归'),
    ('SCOPE', @scope_operation_id, @kb_operation_id, NULL, NULL, '运营制度与RAG问答评测'),
    ('SCOPE', @scope_graph_id, @kb_graph_id, NULL, NULL, 'GraphRAG跨文档图谱评测'),

    ('TOPIC', @topic_o2_docmind_id, @kb_parse_id, @scope_parse_id, NULL, 'Document Mind 与版面解析'),
    ('TOPIC', @topic_o2_ocr_id, @kb_parse_id, @scope_parse_id, NULL, 'OCR 与图片文本解析'),
    ('TOPIC', @topic_o2_table_bbox_id, @kb_parse_id, @scope_parse_id, NULL, '表格和页面定位产物'),
    ('TOPIC', @topic_operation_xinglian_id, @kb_operation_id, @scope_operation_id, NULL, '星联智服上线运营'),
    ('TOPIC', @topic_operation_release_id, @kb_operation_id, @scope_operation_id, NULL, '生产发布与回滚'),
    ('TOPIC', @topic_operation_incident_id, @kb_operation_id, @scope_operation_id, NULL, 'NovaRAG 故障应急'),
    ('TOPIC', @topic_operation_data_id, @kb_operation_id, @scope_operation_id, NULL, '客户数据访问控制'),
    ('TOPIC', @topic_operation_travel_id, @kb_operation_id, @scope_operation_id, NULL, '差旅费用报销'),
    ('TOPIC', @topic_operation_onboarding_id, @kb_operation_id, @scope_operation_id, NULL, '入职培训与 30/60/90'),
    ('TOPIC', @topic_operation_raptor_id, @kb_operation_id, @scope_operation_id, NULL, '运营制度跨文档总结'),
    ('TOPIC', @topic_graph_audit_id, @kb_graph_id, @scope_graph_id, NULL, '审计系统权限图谱'),
    ('TOPIC', @topic_graph_release_id, @kb_graph_id, @scope_graph_id, NULL, 'ReleaseControl 生产发布回滚图谱'),
    ('TOPIC', @topic_graph_data_id, @kb_graph_id, @scope_graph_id, NULL, 'DataAccessGuard 客户数据访问控制图谱'),
    ('TOPIC', @topic_graph_boundary_id, @kb_graph_id, @scope_graph_id, NULL, 'GraphRAG 多社区边界'),

    ('PROFILE', @base_id + 401, NULL, NULL, @doc_o2_provider_pdf_id, NULL),
    ('PROFILE', @base_id + 402, NULL, NULL, @doc_o2_ocr_pdf_id, NULL),
    ('PROFILE', @base_id + 403, NULL, NULL, @doc_o2_ocr_png_id, NULL),
    ('PROFILE', @base_id + 404, NULL, NULL, @doc_xinglian_id, NULL),
    ('PROFILE', @base_id + 405, NULL, NULL, @doc_release_id, NULL),
    ('PROFILE', @base_id + 406, NULL, NULL, @doc_incident_id, NULL),
    ('PROFILE', @base_id + 407, NULL, NULL, @doc_data_policy_id, NULL),
    ('PROFILE', @base_id + 408, NULL, NULL, @doc_travel_id, NULL),
    ('PROFILE', @base_id + 409, NULL, NULL, @doc_onboarding_id, NULL),
    ('PROFILE', @base_id + 410, NULL, NULL, @doc_audit_evidence_id, NULL),
    ('PROFILE', @base_id + 411, NULL, NULL, @doc_audit_alias_id, NULL),
    ('PROFILE', @base_id + 412, NULL, NULL, @doc_release_graph_spec_id, NULL),
    ('PROFILE', @base_id + 413, NULL, NULL, @doc_release_graph_alias_id, NULL),
    ('PROFILE', @base_id + 414, NULL, NULL, @doc_data_graph_spec_id, NULL),
    ('PROFILE', @base_id + 415, NULL, NULL, @doc_data_graph_alias_id, NULL),

    ('RELATION', @base_id + 501, @kb_parse_id, @topic_o2_docmind_id, @doc_o2_provider_pdf_id, NULL),
    ('RELATION', @base_id + 502, @kb_parse_id, @topic_o2_ocr_id, @doc_o2_ocr_pdf_id, NULL),
    ('RELATION', @base_id + 503, @kb_parse_id, @topic_o2_ocr_id, @doc_o2_ocr_png_id, NULL),
    ('RELATION', @base_id + 504, @kb_parse_id, @topic_o2_table_bbox_id, @doc_o2_provider_pdf_id, NULL),
    ('RELATION', @base_id + 505, @kb_parse_id, @topic_o2_table_bbox_id, @doc_o2_ocr_pdf_id, NULL),
    ('RELATION', @base_id + 506, @kb_operation_id, @topic_operation_xinglian_id, @doc_xinglian_id, NULL),
    ('RELATION', @base_id + 507, @kb_operation_id, @topic_operation_release_id, @doc_release_id, NULL),
    ('RELATION', @base_id + 508, @kb_operation_id, @topic_operation_release_id, @doc_xinglian_id, NULL),
    ('RELATION', @base_id + 509, @kb_operation_id, @topic_operation_incident_id, @doc_incident_id, NULL),
    ('RELATION', @base_id + 510, @kb_operation_id, @topic_operation_incident_id, @doc_xinglian_id, NULL),
    ('RELATION', @base_id + 511, @kb_operation_id, @topic_operation_data_id, @doc_data_policy_id, NULL),
    ('RELATION', @base_id + 512, @kb_operation_id, @topic_operation_travel_id, @doc_travel_id, NULL),
    ('RELATION', @base_id + 513, @kb_operation_id, @topic_operation_onboarding_id, @doc_onboarding_id, NULL),
    ('RELATION', @base_id + 514, @kb_operation_id, @topic_operation_raptor_id, @doc_xinglian_id, NULL),
    ('RELATION', @base_id + 515, @kb_operation_id, @topic_operation_raptor_id, @doc_release_id, NULL),
    ('RELATION', @base_id + 516, @kb_operation_id, @topic_operation_raptor_id, @doc_incident_id, NULL),
    ('RELATION', @base_id + 517, @kb_graph_id, @topic_graph_audit_id, @doc_audit_evidence_id, NULL),
    ('RELATION', @base_id + 518, @kb_graph_id, @topic_graph_audit_id, @doc_audit_alias_id, NULL),
    ('RELATION', @base_id + 519, @kb_graph_id, @topic_graph_release_id, @doc_release_graph_spec_id, NULL),
    ('RELATION', @base_id + 520, @kb_graph_id, @topic_graph_release_id, @doc_release_graph_alias_id, NULL),
    ('RELATION', @base_id + 521, @kb_graph_id, @topic_graph_data_id, @doc_data_graph_spec_id, NULL),
    ('RELATION', @base_id + 522, @kb_graph_id, @topic_graph_data_id, @doc_data_graph_alias_id, NULL),
    ('RELATION', @base_id + 523, @kb_graph_id, @topic_graph_boundary_id, @doc_release_graph_spec_id, NULL),
    ('RELATION', @base_id + 524, @kb_graph_id, @topic_graph_boundary_id, @doc_release_graph_alias_id, NULL),
    ('RELATION', @base_id + 525, @kb_graph_id, @topic_graph_boundary_id, @doc_data_graph_spec_id, NULL),
    ('RELATION', @base_id + 526, @kb_graph_id, @topic_graph_boundary_id, @doc_data_graph_alias_id, NULL);

DROP TEMPORARY TABLE IF EXISTS tmp_o2_o8_ownership_conflict;
CREATE TEMPORARY TABLE tmp_o2_o8_ownership_conflict (
    conflict_key VARCHAR(128) NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('SCOPE:', s.id)
FROM smartledge_knowledge_scope_node s
LEFT JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'SCOPE' AND expected.entity_id = s.id
WHERE s.id BETWEEN @base_id + 1 AND @base_id + 3
  AND (expected.entity_id IS NULL
    OR NOT (s.knowledge_base_id <=> expected.knowledge_base_id)
    OR NOT (s.scope_name <=> expected.business_name));

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('SCOPE-NATURAL:', s.id)
FROM smartledge_knowledge_scope_node s
JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'SCOPE'
 AND expected.knowledge_base_id = s.knowledge_base_id
 AND expected.business_name = s.scope_name
 AND expected.entity_id <> s.id;

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('TOPIC:', topic.id)
FROM smartledge_knowledge_topic_node topic
LEFT JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'TOPIC' AND expected.entity_id = topic.id
WHERE topic.id BETWEEN @base_id + 101 AND @base_id + 304
  AND (expected.entity_id IS NULL
    OR NOT (topic.knowledge_base_id <=> expected.knowledge_base_id)
    OR NOT (topic.scope_id <=> expected.parent_id)
    OR NOT (topic.topic_name <=> expected.business_name));

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('TOPIC-NATURAL:', topic.id)
FROM smartledge_knowledge_topic_node topic
JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'TOPIC'
 AND expected.knowledge_base_id = topic.knowledge_base_id
 AND expected.business_name = topic.topic_name
 AND expected.entity_id <> topic.id;

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('PROFILE:', profile.id)
FROM smartledge_document_profile profile
LEFT JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'PROFILE' AND expected.entity_id = profile.id
WHERE profile.id BETWEEN @base_id + 401 AND @base_id + 415
  AND (expected.entity_id IS NULL
    OR NOT (profile.document_id <=> expected.document_id));

INSERT INTO tmp_o2_o8_ownership_conflict (conflict_key)
SELECT CONCAT('RELATION:', relation_item.id)
FROM smartledge_topic_document_relation relation_item
LEFT JOIN tmp_o2_o8_expected_ownership expected
  ON expected.entity_type = 'RELATION' AND expected.entity_id = relation_item.id
WHERE relation_item.id BETWEEN @base_id + 501 AND @base_id + 526
  AND (expected.entity_id IS NULL
    OR NOT (relation_item.knowledge_base_id <=> expected.knowledge_base_id)
    OR NOT (relation_item.topic_id <=> expected.parent_id)
    OR NOT (relation_item.document_id <=> expected.document_id));

SET @id_ownership_conflicts = (
    SELECT GROUP_CONCAT(conflict_key ORDER BY conflict_key SEPARATOR ', ')
    FROM tmp_o2_o8_ownership_conflict
);

SELECT CASE
           WHEN @id_ownership_conflicts IS NULL OR @id_ownership_conflicts = ''
               THEN 'OK: 目标 ID 段未占用或归属于同一 run'
           ELSE CONCAT('ERROR: 目标 ID 段存在归属冲突: ', @id_ownership_conflicts)
       END AS id_ownership_check;

INSERT INTO tmp_o2_o8_assert_fail (id, reason)
SELECT 1, 'target id ownership conflict'
WHERE @id_ownership_conflicts IS NOT NULL AND @id_ownership_conflicts <> '';

/* =========================================================
   3. 知识范围配置
   ========================================================= */

INSERT INTO smartledge_knowledge_scope_node (
    id, knowledge_base_id, scope_name, parent_scope_id, description, aliases, examples, sort_order,
    create_time, edit_time, status
)
VALUES
    (
        @scope_parse_id,
        @kb_parse_id,
        'O2 解析固定回归',
        NULL,
        '用于承接 O2 固定解析回归样例，只验证 OCR、layout、reading order、表格、bbox、artifact 和 O9 文档侧观测。',
        'O2解析,OCR回归,Document Mind回归,解析固定样例,文档侧观测',
        '["O2 扫描 OCR 样例里有没有识别到关键短语","O2 固定样例中的表格有几行几列","这份 PDF 样例是否包含图示或图片区域"]',
        10,
        NOW(), NOW(), 1
    ),
    (
        @scope_operation_id,
        @kb_operation_id,
        '运营制度与RAG问答评测',
        NULL,
        '用于承接运营制度、客服平台上线、生产发布、故障应急、数据访问、差旅报销和入职培训类问答，重点验收 O3/O4/O7/O8。',
        '运营制度,RAG问答评测,客服平台,生产发布,故障应急,客户数据,差旅报销,入职培训',
        '["检索命中率突然下降的可能原因都有哪些","NovaRAG 检索服务降级时按什么顺序处理","L4 高敏感信息的审批要求是什么"]',
        20,
        NOW(), NOW(), 1
    ),
    (
        @scope_graph_id,
        @kb_graph_id,
        'GraphRAG跨文档图谱评测',
        NULL,
        '用于承接 O6 GraphRAG 跨文档别名、canonical、实体关系、community、多社区排序和负边界测试。',
        'O6图谱,GraphRAG,跨文档图谱,AuditTrail,ReleaseControl,DataAccessGuard,多社区排序',
        '["审计系统有哪些权限相关要求","ReleaseControl 和 CAB 是什么关系","DataAccessGuard 和信息安全部是什么关系"]',
        30,
        NOW(), NOW(), 1
    )
    ON DUPLICATE KEY UPDATE
                         knowledge_base_id = VALUES(knowledge_base_id),
                         scope_name = VALUES(scope_name),
                         parent_scope_id = VALUES(parent_scope_id),
                         description = VALUES(description),
                         aliases = VALUES(aliases),
                         examples = VALUES(examples),
                         sort_order = VALUES(sort_order),
                         edit_time = NOW(),
                         status = 1;

/* =========================================================
   4. 知识主题配置
   ========================================================= */

INSERT INTO smartledge_knowledge_topic_node (
    id, knowledge_base_id, topic_name, scope_id, description, aliases, examples,
    answer_shape, execution_preference, sort_order,
    create_time, edit_time, status
)
VALUES
    (@topic_o2_docmind_id, @kb_parse_id, 'Document Mind 与版面解析', @scope_parse_id, '验证普通 PDF 的 layout、表格、FIGURE block、artifact、bbox 和 RAG 产物联动。', 'Document Mind,layout,artifact,bbox,FIGURE,表格解析', '["O2 固定样例中的表格能否被识别成结构化表格","这份 PDF 样例是否包含图示或图片区域"]', 'structure', 'retrieval', 10, NOW(), NOW(), 1),
    (@topic_o2_ocr_id, @kb_parse_id, 'OCR 与图片文本解析', @scope_parse_id, '验证图片型 PDF 和 PNG 图片文件的 OCR 文本、页面图片、表格图片和关键业务短语。', '图片型PDF,OCR,PNG OCR,PAGE_IMAGE,TABLE_IMAGE,蓝桥订单,RAG-O2-20260630,支付回调延迟', '["O2 扫描 OCR 样例里有没有识别到蓝桥订单 7391","图片型 PDF OCR 是否识别到了编号条款和业务关键词"]', 'explain', 'retrieval', 20, NOW(), NOW(), 1),
    (@topic_o2_table_bbox_id, @kb_parse_id, '表格和页面定位产物', @scope_parse_id, '验证固定样例表格结构、table bbox、PAGE_IMAGE、TABLE_IMAGE 和页面 overlay。', '表格结构,table bbox,PAGE_IMAGE,TABLE_IMAGE,overlay,页面定位', '["O2 固定样例中的表格有几行几列","表格和页面定位产物是否可见"]', 'structure', 'retrieval', 30, NOW(), NOW(), 1),

    (@topic_operation_xinglian_id, @kb_operation_id, '星联智服上线运营', @scope_operation_id, '回答星联智服客服平台上线、知识治理、机器人策略设计、灰度验证、上线观察、故障处理和质量评估问题。', '星联智服,客服平台,上线运营,知识治理,机器人策略,观察时长,检索命中率,人工转接率', '["检索命中率突然下降的可能原因都有哪些","人工转接率异常升高检查顺序是什么","上线观察与值班规则中观察时长有哪些"]', 'steps', 'retrieval', 10, NOW(), NOW(), 1),
    (@topic_operation_release_id, @kb_operation_id, '生产发布与回滚', @scope_operation_id, '回答生产发布、灰度节奏、发布暂停、强制回滚、NovaRAG 召回成功率和发布风险控制问题。', '生产发布,回滚,灰度节奏,发布暂停,强制回滚,NovaRAG,召回成功率', '["生产发布默认灰度节奏分几个阶段","强制回滚条件有哪些","哪些情况下默认动作是暂停发布"]', 'steps', 'retrieval', 20, NOW(), NOW(), 1),
    (@topic_operation_incident_id, @kb_operation_id, 'NovaRAG 故障应急', @scope_operation_id, '回答核心业务系统故障分级、NovaRAG 检索服务降级、应急处理顺序和升级边界。', '故障应急,NovaRAG降级,检索服务降级,P1,P2,人工转接激增', '["NovaRAG 检索服务降级时按什么顺序处理","连续 15 分钟无法返回检索结果故障等级怎么判断"]', 'steps', 'retrieval', 30, NOW(), NOW(), 1),
    (@topic_operation_data_id, @kb_operation_id, '客户数据访问控制', @scope_operation_id, '回答客户数据分级、L4 高敏感数据访问、审批、导出限制、日志保存和审计要求。', '客户数据,L4高敏感,访问控制,审批,DataCleanRoom,日志保存,表格问答', '["L4 高敏感信息的审批要求和默认有效期是什么","L3 和 L4 数据的日志保存期限分别是多少"]', 'list', 'retrieval', 40, NOW(), NOW(), 1),
    (@topic_operation_travel_id, @kb_operation_id, '差旅费用报销', @scope_operation_id, '回答差旅住宿标准、报销金额阈值、审批流程和费用合规问题。', '差旅,费用报销,住宿标准,审批阈值,财务BP', '["北京出差酒店住宿上限是多少","10000 元以上报销需要哪些审批"]', 'list', 'retrieval', 50, NOW(), NOW(), 1),
    (@topic_operation_onboarding_id, @kb_operation_id, '入职培训与 30/60/90', @scope_operation_id, '回答入职培训日程、首周安排、30/60/90 天关注重点和培训模块。', '入职培训,首周日程,30天,60天,90天,培训模块', '["入职当天 09:30-10:30 的培训模块是什么","第 30 天、第 60 天、第 90 天分别关注什么"]', 'list', 'retrieval', 60, NOW(), NOW(), 1),
    (@topic_operation_raptor_id, @kb_operation_id, '运营制度跨文档总结', @scope_operation_id, '用于 O7 RAPTOR 单文档和跨文档总结测试，聚合客服平台上线、生产发布和故障应急主线。', 'RAPTOR,跨文档总结,上线风险控制,灰度验证,回滚评估,质量复盘', '["请总结星联智服平台从灰度上线到生产发布再到质量复盘的完整治理流程","这两份规范中和上线风险控制相关的要求有哪些"]', 'compare', 'retrieval', 70, NOW(), NOW(), 1),

    (@topic_graph_audit_id, @kb_graph_id, '审计系统权限图谱', @scope_graph_id, '验证审计系统和 AuditTrail 的跨文档 canonical、别名、权限记录关系和负边界。', 'AuditTrail,审计系统,权限记录,异常权限扩散,信息安全部,系统管理员', '["审计系统有哪些权限相关要求","审计系统本身是否审批权限或直接回收权限"]', 'list', 'graph_then_evidence', 10, NOW(), NOW(), 1),
    (@topic_graph_release_id, @kb_graph_id, 'ReleaseControl 生产发布回滚图谱', @scope_graph_id, '验证 ReleaseControl、CAB、值班 SRE、发布申请、灰度观察和回滚演练的跨文档关系。', 'ReleaseControl,生产发布控制台,CAB,变更评审委员会,值班SRE,回滚演练', '["ReleaseControl 和变更评审委员会、值班 SRE 分别是什么关系","生产发布回滚相关的跨文档图谱社区总结是什么"]', 'list', 'graph_then_evidence', 20, NOW(), NOW(), 1),
    (@topic_graph_data_id, @kb_graph_id, 'DataAccessGuard 客户数据访问控制图谱', @scope_graph_id, '验证 DataAccessGuard、数据治理负责人、信息安全部、客户数据 Owner 和访问台账的跨文档关系。', 'DataAccessGuard,客户数据访问控制平台,数据治理负责人,信息安全部,客户数据Owner,安全复核组', '["DataAccessGuard 和数据治理负责人、信息安全部分别是什么关系","客户数据访问控制相关的跨文档图谱社区总结是什么"]', 'list', 'graph_then_evidence', 30, NOW(), NOW(), 1),
    (@topic_graph_boundary_id, @kb_graph_id, 'GraphRAG 多社区边界', @scope_graph_id, '验证生产发布 community 和客户数据访问 community 的排序、边界和负样例。', '多社区排序,community边界,负样例,职责边界,弱关系外推', '["ReleaseControl 是否负责 L4 高敏感客户数据访问范围确认","DataAccessGuard 是否负责回滚演练和发布窗口管控"]', 'explain', 'graph_assist', 40, NOW(), NOW(), 1)
    ON DUPLICATE KEY UPDATE
                         knowledge_base_id = VALUES(knowledge_base_id),
                         topic_name = VALUES(topic_name),
                         scope_id = VALUES(scope_id),
                         description = VALUES(description),
                         aliases = VALUES(aliases),
                         examples = VALUES(examples),
                         answer_shape = VALUES(answer_shape),
                         execution_preference = VALUES(execution_preference),
                         sort_order = VALUES(sort_order),
                         edit_time = NOW(),
                         status = 1;

/* =========================================================
   5. 文档画像配置
   ========================================================= */

INSERT INTO smartledge_document_profile (
    id, document_id, profile_version, document_summary, document_type, core_topics, example_questions,
    graph_friendly, supports_graph_outline, supports_item_lookup, supports_graph_assist,
    profile_source, profile_status, error_msg,
    create_time, edit_time, status
)
VALUES
    (@base_id + 401, @doc_o2_provider_pdf_id, 1, 'O2 固定解析样例，用于验证 Document Mind 解析、layout、表格、FIGURE block、bbox、artifact 和后续 RAG 产物联动。', 'spec', '["Document Mind解析","layout","表格解析","FIGURE block","bbox","artifact"]', '["O2 固定样例中的表格能否被识别成结构化表格","这份 PDF 样例是否包含图示或图片区域"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 402, @doc_o2_ocr_pdf_id, 1, 'O2 图片型 PDF OCR 样例，用于验证扫描 PDF 的 OCR 文本、页面图片、表格图片、bbox overlay 和解析观测。', 'spec', '["图片型PDF OCR","PAGE_IMAGE","TABLE_IMAGE","bbox overlay","解析观测"]', '["图片型 PDF OCR 是否识别到了编号条款和业务关键词"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 403, @doc_o2_ocr_png_id, 1, 'O2 PNG 图片 OCR 样例，用于验证图片文件进入解析主链路并识别蓝桥订单、RAG-O2-20260630 和支付回调延迟等关键短语。', 'spec', '["PNG OCR","图片文本","蓝桥订单","RAG-O2-20260630","支付回调延迟"]', '["O2 扫描 OCR 样例里有没有识别到蓝桥订单 7391"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 404, @doc_xinglian_id, 1, '星联智服客服平台上线运营手册，覆盖需求澄清、知识治理、机器人策略设计、灰度验证、生产发布、上线观察、故障处置和运营质量评估。', 'manual', '["客服平台上线","知识治理","机器人策略","灰度验证","上线观察","故障处理","运营质量评估"]', '["检索命中率突然下降的可能原因都有哪些","人工转接率异常升高检查顺序是什么","上线观察与值班规则中观察时长有哪些"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 405, @doc_release_id, 1, '生产环境发布与回滚操作规范，覆盖发布暂停原则、默认灰度节奏、强制回滚条件、NovaRAG 召回成功率和发布风险控制。', 'rule', '["生产发布","灰度节奏","发布暂停","强制回滚","NovaRAG召回成功率","风险控制"]', '["生产发布默认灰度节奏分几个阶段","强制回滚条件有哪些","哪些情况下默认动作是暂停发布"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 406, @doc_incident_id, 1, '核心业务系统故障应急响应预案，覆盖故障分级、NovaRAG 检索服务降级、应急处置顺序和升级边界。', 'troubleshooting', '["故障应急","故障分级","NovaRAG降级","检索服务降级","人工转接激增"]', '["NovaRAG 检索服务降级时按什么顺序处理","连续 15 分钟无法返回检索结果故障等级怎么判断"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 407, @doc_data_policy_id, 1, '客户数据分级与访问控制管理制度，覆盖数据等级、L4 高敏感数据访问、审批层级、导出限制、日志保存和审计要求。', 'rule', '["客户数据分级","L4高敏感","访问审批","DataCleanRoom","日志保存","表格问答"]', '["L4 高敏感信息的审批要求和默认有效期是什么","L3 和 L4 数据的日志保存期限分别是多少"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 408, @doc_travel_id, 1, '差旅与费用报销管理办法，覆盖住宿标准、报销金额阈值、审批流程和费用合规。', 'rule', '["差旅","费用报销","住宿标准","审批金额阈值","财务BP"]', '["北京出差酒店住宿上限是多少","10000 元以上报销需要哪些审批"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 409, @doc_onboarding_id, 1, '澄星智能新员工入职培训手册，覆盖首周培训日程、培训模块、地点和 30/60/90 天关注重点。', 'manual', '["入职培训","首周日程","培训模块","30天","60天","90天"]', '["入职当天 09:30-10:30 的培训模块是什么","第 30 天、第 60 天、第 90 天分别关注什么"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 410, @doc_audit_evidence_id, 1, 'O6 审计证据规范 A，用于验证 AuditTrail 权限记录、审批记录、回收记录和异常权限扩散证据。', 'rule', '["AuditTrail","审计系统","权限记录","异常权限扩散","信息安全部"]', '["审计系统有哪些权限相关要求","异常权限扩散时 AuditTrail 要保留哪些信息"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 411, @doc_audit_alias_id, 1, 'O6 审计系统别名说明 B，用于验证审计系统与 AuditTrail 的 canonical/alias，以及不审批、不直接回收权限的负边界。', 'rule', '["AuditTrail别名","审计系统","canonical","负边界"]', '["审计系统本身是否审批权限或直接回收权限"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 412, @doc_release_graph_spec_id, 1, 'O6 生产发布回滚规范 A，用于验证 ReleaseControl、CAB、值班 SRE、发布申请、灰度观察和回滚演练 community。', 'rule', '["ReleaseControl","CAB","值班SRE","发布申请","灰度观察","回滚演练"]', '["ReleaseControl 和变更评审委员会、值班 SRE 分别是什么关系"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 413, @doc_release_graph_alias_id, 1, 'O6 生产发布回滚别名 B，用于验证 ReleaseControl、生产发布控制台、变更评审委员会和 CAB 的别名归一。', 'rule', '["ReleaseControl别名","生产发布控制台","变更评审委员会","CAB"]', '["生产发布回滚相关的跨文档图谱社区总结是什么"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 414, @doc_data_graph_spec_id, 1, 'O6 客户数据访问控制规范 A，用于验证 DataAccessGuard、数据治理负责人、信息安全部和访问台账 community。', 'rule', '["DataAccessGuard","数据治理负责人","信息安全部","访问台账"]', '["DataAccessGuard 和数据治理负责人、信息安全部分别是什么关系"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
    (@base_id + 415, @doc_data_graph_alias_id, 1, 'O6 客户数据访问控制别名 B，用于验证 DataAccessGuard、客户数据访问控制平台、客户数据 Owner 和安全复核组的别名归一与负边界。', 'rule', '["DataAccessGuard别名","客户数据Owner","安全复核组","负边界"]', '["客户数据访问控制相关的跨文档图谱社区总结是什么","DataAccessGuard 是否负责回滚演练和发布窗口管控"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1)
    ON DUPLICATE KEY UPDATE
                         profile_version = VALUES(profile_version),
                         document_summary = VALUES(document_summary),
                         document_type = VALUES(document_type),
                         core_topics = VALUES(core_topics),
                         example_questions = VALUES(example_questions),
                         graph_friendly = VALUES(graph_friendly),
                         supports_graph_outline = VALUES(supports_graph_outline),
                         supports_item_lookup = VALUES(supports_item_lookup),
                         supports_graph_assist = VALUES(supports_graph_assist),
                         profile_source = VALUES(profile_source),
                         profile_status = VALUES(profile_status),
                         error_msg = VALUES(error_msg),
                         edit_time = NOW(),
                         status = 1;

/* =========================================================
   6. 主题文档关联配置
   ========================================================= */

UPDATE smartledge_topic_document_relation
SET status = 0, edit_time = NOW()
WHERE knowledge_base_id IN (@kb_parse_id, @kb_operation_id, @kb_graph_id)
  AND topic_id IN (
                   @topic_o2_docmind_id,
                   @topic_o2_ocr_id,
                   @topic_o2_table_bbox_id,
                   @topic_operation_xinglian_id,
                   @topic_operation_release_id,
                   @topic_operation_incident_id,
                   @topic_operation_data_id,
                   @topic_operation_travel_id,
                   @topic_operation_onboarding_id,
                   @topic_operation_raptor_id,
                   @topic_graph_audit_id,
                   @topic_graph_release_id,
                   @topic_graph_data_id,
                   @topic_graph_boundary_id
    );

INSERT INTO smartledge_topic_document_relation (
    id, knowledge_base_id, topic_id, document_id, relation_score, relation_source, reason,
    create_time, edit_time, status
)
VALUES
    (@base_id + 501, @kb_parse_id, @topic_o2_docmind_id, @doc_o2_provider_pdf_id, 0.9800, 'manual', '该样例用于验证普通 PDF 的 Document Mind、layout、表格、FIGURE、bbox 和 artifact。', NOW(), NOW(), 1),
    (@base_id + 502, @kb_parse_id, @topic_o2_ocr_id, @doc_o2_ocr_pdf_id, 0.9800, 'manual', '该样例用于验证图片型 PDF OCR、PAGE_IMAGE、TABLE_IMAGE 和 bbox overlay。', NOW(), NOW(), 1),
    (@base_id + 503, @kb_parse_id, @topic_o2_ocr_id, @doc_o2_ocr_png_id, 0.9800, 'manual', '该样例用于验证 PNG 图片 OCR 和关键短语识别。', NOW(), NOW(), 1),
    (@base_id + 504, @kb_parse_id, @topic_o2_table_bbox_id, @doc_o2_provider_pdf_id, 0.9600, 'manual', '该样例用于验证结构化表格、table bbox 和页面定位产物。', NOW(), NOW(), 1),
    (@base_id + 505, @kb_parse_id, @topic_o2_table_bbox_id, @doc_o2_ocr_pdf_id, 0.9400, 'manual', '该样例用于验证 OCR 表格图片和 TABLE_IMAGE 产物。', NOW(), NOW(), 1),

    (@base_id + 506, @kb_operation_id, @topic_operation_xinglian_id, @doc_xinglian_id, 0.9900, 'manual', '该手册是星联智服客服平台上线运营和 O8 主基线的核心文档。', NOW(), NOW(), 1),
    (@base_id + 507, @kb_operation_id, @topic_operation_release_id, @doc_release_id, 0.9900, 'manual', '该规范集中描述生产发布、灰度节奏、发布暂停和强制回滚条件。', NOW(), NOW(), 1),
    (@base_id + 508, @kb_operation_id, @topic_operation_release_id, @doc_xinglian_id, 0.6200, 'manual', '星联智服手册包含上线观察和回滚评估相关内容，可作为发布回滚跨文档对照。', NOW(), NOW(), 1),
    (@base_id + 509, @kb_operation_id, @topic_operation_incident_id, @doc_incident_id, 0.9900, 'manual', '该预案集中描述 NovaRAG 检索服务降级和核心故障应急处理。', NOW(), NOW(), 1),
    (@base_id + 510, @kb_operation_id, @topic_operation_incident_id, @doc_xinglian_id, 0.6500, 'manual', '星联智服手册包含检索命中率下降、回答口径不完整和人工转接率异常等故障处理章节。', NOW(), NOW(), 1),
    (@base_id + 511, @kb_operation_id, @topic_operation_data_id, @doc_data_policy_id, 0.9900, 'manual', '该制度集中描述客户数据分级、L4 数据访问审批、导出限制和日志保存。', NOW(), NOW(), 1),
    (@base_id + 512, @kb_operation_id, @topic_operation_travel_id, @doc_travel_id, 0.9900, 'manual', '该办法集中描述差旅住宿标准和报销审批金额阈值。', NOW(), NOW(), 1),
    (@base_id + 513, @kb_operation_id, @topic_operation_onboarding_id, @doc_onboarding_id, 0.9900, 'manual', '该手册集中描述新员工入职培训日程和 30/60/90 天关注重点。', NOW(), NOW(), 1),
    (@base_id + 514, @kb_operation_id, @topic_operation_raptor_id, @doc_xinglian_id, 0.9600, 'manual', '该手册提供客服平台上线、运营监控和质量复盘主线，适合 RAPTOR 总结。', NOW(), NOW(), 1),
    (@base_id + 515, @kb_operation_id, @topic_operation_raptor_id, @doc_release_id, 0.9400, 'manual', '该规范提供生产发布、灰度验证和回滚评估主线，适合跨文档 RAPTOR 总结。', NOW(), NOW(), 1),
    (@base_id + 516, @kb_operation_id, @topic_operation_raptor_id, @doc_incident_id, 0.7200, 'manual', '该预案提供故障应急和降级处理干扰样例，用于验证跨文档总结边界。', NOW(), NOW(), 1),

    (@base_id + 517, @kb_graph_id, @topic_graph_audit_id, @doc_audit_evidence_id, 0.9900, 'manual', '该文档提供 AuditTrail 权限记录和异常权限扩散的关系证据。', NOW(), NOW(), 1),
    (@base_id + 518, @kb_graph_id, @topic_graph_audit_id, @doc_audit_alias_id, 0.9700, 'manual', '该文档提供审计系统与 AuditTrail 的别名、职责和负边界。', NOW(), NOW(), 1),
    (@base_id + 519, @kb_graph_id, @topic_graph_release_id, @doc_release_graph_spec_id, 0.9900, 'manual', '该文档提供 ReleaseControl、CAB、值班 SRE 和回滚演练关系证据。', NOW(), NOW(), 1),
    (@base_id + 520, @kb_graph_id, @topic_graph_release_id, @doc_release_graph_alias_id, 0.9700, 'manual', '该文档提供 ReleaseControl、生产发布控制台、变更评审委员会和 CAB 的别名归一证据。', NOW(), NOW(), 1),
    (@base_id + 521, @kb_graph_id, @topic_graph_data_id, @doc_data_graph_spec_id, 0.9900, 'manual', '该文档提供 DataAccessGuard、数据治理负责人、信息安全部和访问台账关系证据。', NOW(), NOW(), 1),
    (@base_id + 522, @kb_graph_id, @topic_graph_data_id, @doc_data_graph_alias_id, 0.9700, 'manual', '该文档提供 DataAccessGuard、客户数据访问控制平台、客户数据 Owner 和安全复核组的别名归一证据。', NOW(), NOW(), 1),
    (@base_id + 523, @kb_graph_id, @topic_graph_boundary_id, @doc_release_graph_spec_id, 0.9000, 'manual', '用于验证生产发布回滚 community 在多社区排序中的边界。', NOW(), NOW(), 1),
    (@base_id + 524, @kb_graph_id, @topic_graph_boundary_id, @doc_release_graph_alias_id, 0.8800, 'manual', '用于验证生产发布别名文档不会被客户数据访问问题错误选中。', NOW(), NOW(), 1),
    (@base_id + 525, @kb_graph_id, @topic_graph_boundary_id, @doc_data_graph_spec_id, 0.9000, 'manual', '用于验证客户数据访问 community 在多社区排序中的边界。', NOW(), NOW(), 1),
    (@base_id + 526, @kb_graph_id, @topic_graph_boundary_id, @doc_data_graph_alias_id, 0.8800, 'manual', '用于验证客户数据访问别名文档不会被生产发布问题错误选中。', NOW(), NOW(), 1)
    ON DUPLICATE KEY UPDATE
                         relation_score = VALUES(relation_score),
                         relation_source = VALUES(relation_source),
                         reason = VALUES(reason),
                         edit_time = NOW(),
                         status = 1;

/* =========================================================
   7. 执行后检查
   ========================================================= */

SELECT
    kb.kb_name,
    COUNT(d.id) AS doc_count
FROM tmp_o2_o8_latest_document latest
         JOIN smartledge_document d ON d.id = latest.document_id
         JOIN tmp_o2_o8_kb kb ON kb.knowledge_base_id = d.knowledge_base_id
GROUP BY kb.kb_name
ORDER BY kb.kb_name;

SELECT
    s.knowledge_base_id,
    kb.base_name AS knowledge_base_name,
    s.id AS scope_id,
    s.scope_name,
    s.aliases,
    s.sort_order,
    s.status
FROM smartledge_knowledge_scope_node s
         JOIN smartledge_knowledge_base kb ON kb.id = s.knowledge_base_id
WHERE s.id IN (@scope_parse_id, @scope_operation_id, @scope_graph_id)
ORDER BY kb.base_name, s.sort_order;

SELECT
    t.knowledge_base_id,
    kb.base_name AS knowledge_base_name,
    t.id AS topic_id,
    t.topic_name,
    t.scope_id,
    s.scope_name,
    t.answer_shape,
    t.execution_preference,
    t.sort_order,
    t.status
FROM smartledge_knowledge_topic_node t
         JOIN smartledge_knowledge_base kb ON kb.id = t.knowledge_base_id
         JOIN smartledge_knowledge_scope_node s ON s.id = t.scope_id
WHERE t.id IN (
               @topic_o2_docmind_id,
               @topic_o2_ocr_id,
               @topic_o2_table_bbox_id,
               @topic_operation_xinglian_id,
               @topic_operation_release_id,
               @topic_operation_incident_id,
               @topic_operation_data_id,
               @topic_operation_travel_id,
               @topic_operation_onboarding_id,
               @topic_operation_raptor_id,
               @topic_graph_audit_id,
               @topic_graph_release_id,
               @topic_graph_data_id,
               @topic_graph_boundary_id
    )
ORDER BY kb.base_name, s.sort_order, t.sort_order;

SELECT
    p.document_id,
    d.knowledge_base_name,
    d.original_file_name,
    p.document_type,
    p.profile_status,
    p.graph_friendly,
    p.supports_graph_outline,
    p.supports_item_lookup,
    p.supports_graph_assist,
    p.status
FROM smartledge_document_profile p
         JOIN smartledge_document d ON d.id = p.document_id
WHERE p.document_id IN (
                        @doc_o2_provider_pdf_id,
                        @doc_o2_ocr_pdf_id,
                        @doc_o2_ocr_png_id,
                        @doc_xinglian_id,
                        @doc_release_id,
                        @doc_incident_id,
                        @doc_data_policy_id,
                        @doc_travel_id,
                        @doc_onboarding_id,
                        @doc_audit_evidence_id,
                        @doc_audit_alias_id,
                        @doc_release_graph_spec_id,
                        @doc_release_graph_alias_id,
                        @doc_data_graph_spec_id,
                        @doc_data_graph_alias_id
    )
ORDER BY d.knowledge_base_name, d.original_file_name;

SELECT
    kb.base_name AS knowledge_base_name,
    s.scope_name,
    t.topic_name,
    d.original_file_name,
    r.relation_score,
    r.relation_source,
    r.reason,
    r.status
FROM smartledge_topic_document_relation r
         JOIN smartledge_knowledge_base kb ON kb.id = r.knowledge_base_id
         JOIN smartledge_knowledge_topic_node t ON t.id = r.topic_id
         JOIN smartledge_knowledge_scope_node s ON s.id = t.scope_id
         JOIN smartledge_document d ON d.id = r.document_id
WHERE r.knowledge_base_id IN (@kb_parse_id, @kb_operation_id, @kb_graph_id)
  AND r.topic_id IN (
                     @topic_o2_docmind_id,
                     @topic_o2_ocr_id,
                     @topic_o2_table_bbox_id,
                     @topic_operation_xinglian_id,
                     @topic_operation_release_id,
                     @topic_operation_incident_id,
                     @topic_operation_data_id,
                     @topic_operation_travel_id,
                     @topic_operation_onboarding_id,
                     @topic_operation_raptor_id,
                     @topic_graph_audit_id,
                     @topic_graph_release_id,
                     @topic_graph_data_id,
                     @topic_graph_boundary_id
    )
  AND r.status = 1
ORDER BY kb.base_name, s.sort_order, t.sort_order, r.relation_score DESC, d.original_file_name;

SELECT
    r.id,
    r.knowledge_base_id AS relation_kb,
    t.knowledge_base_id AS topic_kb,
    d.knowledge_base_id AS doc_kb,
    d.original_file_name
FROM smartledge_topic_document_relation r
         JOIN smartledge_knowledge_topic_node t ON t.id = r.topic_id
         JOIN smartledge_document d ON d.id = r.document_id
WHERE r.status = 1
  AND r.topic_id IN (
                     @topic_o2_docmind_id,
                     @topic_o2_ocr_id,
                     @topic_o2_table_bbox_id,
                     @topic_operation_xinglian_id,
                     @topic_operation_release_id,
                     @topic_operation_incident_id,
                     @topic_operation_data_id,
                     @topic_operation_travel_id,
                     @topic_operation_onboarding_id,
                     @topic_operation_raptor_id,
                     @topic_graph_audit_id,
                     @topic_graph_release_id,
                     @topic_graph_data_id,
                     @topic_graph_boundary_id
    )
  AND (r.knowledge_base_id <> t.knowledge_base_id OR r.knowledge_base_id <> d.knowledge_base_id);

COMMIT;
