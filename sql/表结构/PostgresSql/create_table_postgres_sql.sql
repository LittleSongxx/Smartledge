-- 建议先手动创建 PostgreSQL 数据库：
-- CREATE DATABASE smartledge_pgvector ENCODING 'UTF8';
-- 然后连接到 smartledge_pgvector 库后执行本文件。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS public.smartledge_document_embedding (
                                                                     id BIGINT NOT NULL,
                                                                     document_id BIGINT NOT NULL,
                                                                     task_id BIGINT NOT NULL,
                                                                     plan_id BIGINT,
                                                                     parent_block_id BIGINT NOT NULL,
                                                                     chunk_no INTEGER NOT NULL,
                                                                     source_type SMALLINT DEFAULT 1,
                                                                     section_path VARCHAR(1000),
    structure_node_id BIGINT,
    structure_node_type SMALLINT,
    canonical_path VARCHAR(1000),
    item_index INTEGER,
    chunk_text TEXT NOT NULL,
    content_with_weight TEXT,
    chunk_type VARCHAR(32),
    title VARCHAR(1000),
    keywords TEXT,
    questions TEXT,
    char_count INTEGER DEFAULT 0,
    token_count INTEGER DEFAULT 0,
    page_no INTEGER,
    page_range VARCHAR(64),
    bbox_json TEXT,
    source_block_ids TEXT,
    embedding_model VARCHAR(128),
    metadata_json JSONB DEFAULT '{}'::jsonb,
    embedding VECTOR NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    edit_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status SMALLINT DEFAULT 1,
    PRIMARY KEY (id)
    );

COMMENT ON TABLE public.smartledge_document_embedding IS '文档切块向量表';
COMMENT ON COLUMN public.smartledge_document_embedding.id IS '主键id，直接复用 MySQL chunk 主键';
COMMENT ON COLUMN public.smartledge_document_embedding.document_id IS '文档id';
COMMENT ON COLUMN public.smartledge_document_embedding.task_id IS '索引任务id';
COMMENT ON COLUMN public.smartledge_document_embedding.plan_id IS '策略方案id';
COMMENT ON COLUMN public.smartledge_document_embedding.parent_block_id IS '所属父块id';
COMMENT ON COLUMN public.smartledge_document_embedding.chunk_no IS '切块序号';
COMMENT ON COLUMN public.smartledge_document_embedding.source_type IS '内容来源 1:原文切块 2:后处理补全文本';
COMMENT ON COLUMN public.smartledge_document_embedding.section_path IS '章节路径';
COMMENT ON COLUMN public.smartledge_document_embedding.structure_node_id IS '关联的结构节点id';
COMMENT ON COLUMN public.smartledge_document_embedding.structure_node_type IS '关联的结构节点类型';
COMMENT ON COLUMN public.smartledge_document_embedding.canonical_path IS '结构节点稳定路径';
COMMENT ON COLUMN public.smartledge_document_embedding.item_index IS '列表项/步骤项序号';
COMMENT ON COLUMN public.smartledge_document_embedding.chunk_text IS '切块文本内容';
COMMENT ON COLUMN public.smartledge_document_embedding.content_with_weight IS '带标题/章节/关键词/问题等权重信息的检索文本';
COMMENT ON COLUMN public.smartledge_document_embedding.chunk_type IS 'chunk类型 TEXT/TITLE/TABLE/IMAGE/FIGURE/CODE/FORMULA/MIXED';
COMMENT ON COLUMN public.smartledge_document_embedding.title IS 'chunk所属标题或章节标题';
COMMENT ON COLUMN public.smartledge_document_embedding.keywords IS 'chunk关键词 JSON 数组';
COMMENT ON COLUMN public.smartledge_document_embedding.questions IS 'chunk典型问题 JSON 数组';
COMMENT ON COLUMN public.smartledge_document_embedding.char_count IS '字符数';
COMMENT ON COLUMN public.smartledge_document_embedding.token_count IS 'token数';
COMMENT ON COLUMN public.smartledge_document_embedding.page_no IS '命中块所在页码';
COMMENT ON COLUMN public.smartledge_document_embedding.page_range IS '命中块覆盖页码范围';
COMMENT ON COLUMN public.smartledge_document_embedding.bbox_json IS '命中块版面坐标JSON';
COMMENT ON COLUMN public.smartledge_document_embedding.source_block_ids IS 'chunk来源document_block id列表JSON';
COMMENT ON COLUMN public.smartledge_document_embedding.embedding_model IS 'embedding 模型名称';
COMMENT ON COLUMN public.smartledge_document_embedding.metadata_json IS '向量检索附带元数据';
COMMENT ON COLUMN public.smartledge_document_embedding.embedding IS '向量值';
COMMENT ON COLUMN public.smartledge_document_embedding.create_time IS '创建时间';
COMMENT ON COLUMN public.smartledge_document_embedding.edit_time IS '编辑时间';
COMMENT ON COLUMN public.smartledge_document_embedding.status IS '1:正常 0:删除';

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_document_id
    ON public.smartledge_document_embedding (document_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_task_id
    ON public.smartledge_document_embedding (task_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_plan_id
    ON public.smartledge_document_embedding (plan_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_parent_block_id
    ON public.smartledge_document_embedding (parent_block_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_chunk_type
    ON public.smartledge_document_embedding (chunk_type);

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_status
    ON public.smartledge_document_embedding (status);

-- 当前第一期为了兼容不同 embedding 模型的维度变化，embedding 字段使用未固定维度的 VECTOR 类型。
-- 如果后续固定模型与维度，例如 1024 或 1536，
-- 可以把字段改成 VECTOR(1024/1536) 并补充 HNSW 或 IVF_FLAT 向量索引。

CREATE TABLE IF NOT EXISTS public.smartledge_raptor_embedding (
                                                                   id BIGINT NOT NULL,
                                                                   document_id BIGINT NOT NULL,
                                                                   task_id BIGINT NOT NULL,
                                                                   scope_type VARCHAR(32) NOT NULL DEFAULT 'DOCUMENT',
    scope_key VARCHAR(255) NOT NULL DEFAULT '',
    node_level INTEGER NOT NULL,
    node_no INTEGER NOT NULL,
    parent_node_id BIGINT,
    title VARCHAR(500),
    summary TEXT NOT NULL,
    summary_with_weight TEXT,
    source_chunk_ids_json TEXT,
    source_parent_block_ids_json TEXT,
    source_document_ids_json TEXT,
    source_task_ids_json TEXT,
    section_path VARCHAR(1000),
    page_range VARCHAR(64),
    keywords TEXT,
    questions TEXT,
    embedding_model VARCHAR(128),
    metadata_json JSONB DEFAULT '{}'::jsonb,
    embedding VECTOR NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    edit_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status SMALLINT DEFAULT 1,
    PRIMARY KEY (id)
    );

COMMENT ON TABLE public.smartledge_raptor_embedding IS 'RAPTOR层级摘要向量表';
COMMENT ON COLUMN public.smartledge_raptor_embedding.id IS '主键id，复用 MySQL raptor_node 主键';
COMMENT ON COLUMN public.smartledge_raptor_embedding.document_id IS '文档id';
COMMENT ON COLUMN public.smartledge_raptor_embedding.task_id IS '索引任务id';
COMMENT ON COLUMN public.smartledge_raptor_embedding.scope_type IS 'RAPTOR摘要范围类型：DOCUMENT/DATASET';
COMMENT ON COLUMN public.smartledge_raptor_embedding.scope_key IS 'RAPTOR摘要范围键，例如 document:{id} / knowledge:{code} / global';
COMMENT ON COLUMN public.smartledge_raptor_embedding.node_level IS '摘要树层级，1为最贴近原文的摘要';
COMMENT ON COLUMN public.smartledge_raptor_embedding.node_no IS '同层节点序号';
COMMENT ON COLUMN public.smartledge_raptor_embedding.parent_node_id IS '父摘要节点id';
COMMENT ON COLUMN public.smartledge_raptor_embedding.title IS '摘要节点标题';
COMMENT ON COLUMN public.smartledge_raptor_embedding.summary IS '摘要文本';
COMMENT ON COLUMN public.smartledge_raptor_embedding.summary_with_weight IS '带标题/章节/关键词/问题的加权检索文本';
COMMENT ON COLUMN public.smartledge_raptor_embedding.source_chunk_ids_json IS '可下钻的原文chunk id JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.source_parent_block_ids_json IS '可下钻的父块id JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.source_document_ids_json IS '跨文档摘要覆盖的文档id JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.source_task_ids_json IS '跨文档摘要覆盖的索引任务id JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.section_path IS '摘要覆盖章节路径';
COMMENT ON COLUMN public.smartledge_raptor_embedding.page_range IS '摘要覆盖页码范围';
COMMENT ON COLUMN public.smartledge_raptor_embedding.keywords IS '摘要关键词 JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.questions IS '摘要典型问题 JSON数组';
COMMENT ON COLUMN public.smartledge_raptor_embedding.embedding_model IS 'embedding 模型名称';
COMMENT ON COLUMN public.smartledge_raptor_embedding.metadata_json IS '摘要向量检索附带元数据';
COMMENT ON COLUMN public.smartledge_raptor_embedding.embedding IS '向量值';
COMMENT ON COLUMN public.smartledge_raptor_embedding.status IS '1:正常 0:删除';

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_document_id
    ON public.smartledge_raptor_embedding (document_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_task_id
    ON public.smartledge_raptor_embedding (task_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_scope
    ON public.smartledge_raptor_embedding (scope_type, scope_key);

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_level
    ON public.smartledge_raptor_embedding (document_id, node_level);

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_parent
    ON public.smartledge_raptor_embedding (parent_node_id);

CREATE INDEX IF NOT EXISTS idx_smartledge_raptor_embedding_status
    ON public.smartledge_raptor_embedding (status);
