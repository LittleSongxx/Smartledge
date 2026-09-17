-- S28：文档系统列。未授权前不要在真实库执行。重复执行会因列已存在失败。

ALTER TABLE `smartledge_document`
    ADD COLUMN `content_hash` varchar(128) DEFAULT NULL COMMENT '文件内容SHA-256' AFTER `metadata_json`,
    ADD COLUMN `source_uri` varchar(1024) DEFAULT NULL COMMENT '来源URI，默认 MinIO object url' AFTER `content_hash`,
    ADD COLUMN `language` varchar(16) DEFAULT NULL COMMENT '文档语言' AFTER `source_uri`,
    ADD COLUMN `effective_from` datetime DEFAULT NULL COMMENT '生效起点' AFTER `language`,
    ADD COLUMN `expires_at` datetime DEFAULT NULL COMMENT '过期时间，空表示不过期' AFTER `effective_from`;

ALTER TABLE `smartledge_document`
    ADD KEY `idx_kb_content_hash` (`knowledge_base_id`, `content_hash`),
    ADD KEY `idx_expires_at` (`expires_at`);
