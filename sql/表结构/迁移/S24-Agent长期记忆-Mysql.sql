-- S24 Agent 长期记忆（MySQL 业务库）。
--
-- 可重复执行。不在本仓库自动化里连接真实验收库。
-- 事实按 (tenant_id, dialogue_code, entity_key) 寻址；新值 SUPERSEDE 旧 ACTIVE，禁止原地覆盖。

USE smartledge;

CREATE TABLE IF NOT EXISTS smartledge_long_term_memory (
    id                      BIGINT       NOT NULL COMMENT '主键id',
    tenant_id               BIGINT       NOT NULL DEFAULT '1' COMMENT '所属租户id',
    dialogue_code           VARCHAR(64)  NOT NULL COMMENT '所属业务会话编号',
    user_id                 BIGINT       DEFAULT NULL COMMENT '写入时的用户id，可空',
    entity_key              VARCHAR(80)  NOT NULL COMMENT '事实实体键',
    fact_text               VARCHAR(512) NOT NULL COMMENT '事实文本',
    source_kind             VARCHAR(32)  NOT NULL COMMENT 'USER_EXPLICIT / MODEL_CANDIDATE',
    lifecycle               VARCHAR(32)  NOT NULL COMMENT 'ACTIVE / SUPERSEDED / REJECTED',
    provenance_exchange_id  BIGINT       NOT NULL DEFAULT '0' COMMENT '写入来源轮次',
    version                 INT          NOT NULL DEFAULT '1' COMMENT '同键版本，从 1 递增',
    create_time             DATETIME     DEFAULT NULL COMMENT '创建时间',
    edit_time               DATETIME     DEFAULT NULL COMMENT '编辑时间',
    status                  TINYINT(1)   NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
    PRIMARY KEY (id),
    KEY idx_ltm_conversation_lifecycle (dialogue_code, lifecycle, status),
    KEY idx_ltm_conversation_entity (dialogue_code, entity_key, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话级可寻址长期事实';
