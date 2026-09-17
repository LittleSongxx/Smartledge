-- S28：向量行时效列。未授权前不要在真实库执行。
ALTER TABLE public.smartledge_document_embedding
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NULL;

COMMENT ON COLUMN public.smartledge_document_embedding.expires_at IS '过期时间，空表示不过期；检索必须排除已过期行';

CREATE INDEX IF NOT EXISTS idx_smartledge_document_embedding_expires_at
    ON public.smartledge_document_embedding (expires_at);
