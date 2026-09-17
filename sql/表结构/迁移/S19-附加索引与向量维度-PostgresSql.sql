-- S19 附加索引与向量维度归一化（PostgreSQL / pgvector）。
--
-- 本文件解决两个问题：
--   1. 向量列原先声明为无维度的 VECTOR，既不校验写入维度，也无法建立 ANN 索引
--      （pgvector 对无维度列建 HNSW 会直接报 "column does not have dimensions"）。
--      查询是精确 KNN，B-tree 只能缩小候选集、不能避免扫描，数据量上去后退化为 O(n)。
--   2. 归一化到固定维度后建立 HNSW 索引，让检索从精确扫描变成近似最近邻。
--
-- 前置条件：两张向量表中已有的向量维度必须一致，且与应用配置 app.ai.embedding.dimensions 相同。
-- 本脚本在维度不一致时会主动报错并中止，不会静默改写数据。
-- 全新部署的执行顺序：create_database_postgres_sql.sql -> create_table_postgres_sql.sql -> 本文件。
-- 可重复执行。

-- 期望维度：必须与 application.yaml 的 app.ai.embedding.dimensions 保持一致。
-- 如需调整，请同时修改这里和配置，并重新执行本脚本。

DO $$
DECLARE
    expected_dim integer := 1024;
    observed_dims text;
BEGIN
    SELECT string_agg(DISTINCT vector_dims(embedding)::text, ',' ORDER BY vector_dims(embedding)::text)
      INTO observed_dims
      FROM public.smartledge_document_embedding;

    IF observed_dims IS NOT NULL AND observed_dims <> expected_dim::text THEN
        RAISE EXCEPTION 'smartledge_document_embedding 存在非 % 维向量（实际维度: %），已中止；请先清理或重建向量数据',
            expected_dim, observed_dims;
    END IF;

    ALTER TABLE public.smartledge_document_embedding
        ALTER COLUMN embedding TYPE vector(1024);

    SELECT string_agg(DISTINCT vector_dims(embedding)::text, ',' ORDER BY vector_dims(embedding)::text)
      INTO observed_dims
      FROM public.smartledge_raptor_embedding;

    IF observed_dims IS NOT NULL AND observed_dims <> expected_dim::text THEN
        RAISE EXCEPTION 'smartledge_raptor_embedding 存在非 % 维向量（实际维度: %），已中止；请先清理或重建向量数据',
            expected_dim, observed_dims;
    END IF;

    ALTER TABLE public.smartledge_raptor_embedding
        ALTER COLUMN embedding TYPE vector(1024);
END $$;

-- HNSW + 余弦距离。检索语句使用 `embedding <=> CAST(? AS vector)`，因此必须用 vector_cosine_ops。
CREATE INDEX IF NOT EXISTS idx_document_embedding_hnsw_cosine
    ON public.smartledge_document_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_raptor_embedding_hnsw_cosine
    ON public.smartledge_raptor_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
