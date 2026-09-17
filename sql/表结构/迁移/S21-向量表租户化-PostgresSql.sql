-- S21 向量表租户化（PostgreSQL / pgvector）。
--
-- 本文件只做两件事：给两张向量表补 tenant_id，并定义 RLS 策略。
-- **策略默认不启用**，启用步骤见文件末尾 —— 原因见下。
--
-- 为什么不在这里直接 ENABLE + FORCE RLS：
--   pgvector 上的 RLS 必须三件事一起做才成立，缺一件就会出问题：
--     1) 应用必须用**非超级用户**连接。超级用户绕过 RLS，即使加了 FORCE ROW LEVEL SECURITY
--        也照样能看全表；用 postgres 账号连库时 RLS 等于不存在。
--     2) 租户上下文必须**按事务**设置（set_config(..., true)）。会话级设置会在 Hikari
--        连接复用时间串租户，是已知的跨租户泄漏来源。
--     3) 向量检索必须改成 over-fetch + 租户前置条件：ANN 索引遍历不参与 RLS 过滤，
--        它先在全球索引里取邻居再按策略丢弃，大规模下延迟会显著恶化。
--   只启用策略而没做 1) 会得到"看似有 RLS 实则无效"的假象；只做 1)+2) 而没做 3)
--   会让召回质量下降。因此本阶段先落列与策略定义，启用作为 B2 的独立收尾步骤。

-- 存量行归入默认租户
ALTER TABLE public.smartledge_document_embedding
    ADD COLUMN IF NOT EXISTS tenant_id bigint NOT NULL DEFAULT 1;
ALTER TABLE public.smartledge_raptor_embedding
    ADD COLUMN IF NOT EXISTS tenant_id bigint NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_document_embedding_tenant
    ON public.smartledge_document_embedding (tenant_id);
CREATE INDEX IF NOT EXISTS idx_raptor_embedding_tenant
    ON public.smartledge_raptor_embedding (tenant_id);

-- 租户 + 文档的复合前置条件索引：为将来的 over-fetch 检索与分区演进预留
CREATE INDEX IF NOT EXISTS idx_document_embedding_tenant_document
    ON public.smartledge_document_embedding (tenant_id, document_id, task_id);
CREATE INDEX IF NOT EXISTS idx_raptor_embedding_tenant_scope
    ON public.smartledge_raptor_embedding (tenant_id, scope_type, scope_key);

-- RLS 策略定义。
-- current_setting('app.tenant_id', true) 在未设置时返回 NULL，
-- 使 tenant_id = NULL 恒为假 —— 即 fail closed：拿不到租户上下文就不返回任何行。
DROP POLICY IF EXISTS tenant_isolation ON public.smartledge_document_embedding;
CREATE POLICY tenant_isolation ON public.smartledge_document_embedding
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);

DROP POLICY IF EXISTS tenant_isolation ON public.smartledge_raptor_embedding;
CREATE POLICY tenant_isolation ON public.smartledge_raptor_embedding
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);

-- ============================================================================
-- 启用 RLS（B3）：三件事必须一起做，缺一件就会得到"看似有 RLS 实则无效"的假象
-- ============================================================================
--
-- 1) 应用专用非超级用户角色。超级用户绕过 RLS，用 postgres 账号连库时策略等于不存在。
--    （口令与其它本地开发口令一致；生产必须换成强口令并只从注入的环境变量读取。）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartledge_app') THEN
        CREATE ROLE smartledge_app LOGIN PASSWORD 'smartledge';
    END IF;
END$$;

GRANT CONNECT ON DATABASE smartledge_pgvector TO smartledge_app;
GRANT USAGE ON SCHEMA public TO smartledge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.smartledge_document_embedding TO smartledge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.smartledge_raptor_embedding TO smartledge_app;

-- 2) 启用并强制策略。FORCE 才能约束表属主，否则属主身份（postgres）仍然绕过策略。
ALTER TABLE public.smartledge_document_embedding ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.smartledge_document_embedding FORCE ROW LEVEL SECURITY;
ALTER TABLE public.smartledge_raptor_embedding ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.smartledge_raptor_embedding FORCE ROW LEVEL SECURITY;

-- 3) 应用侧（代码与配置，不在本文件里）：
--    - application.yaml 的 app.manage.pgvector.username 改为 smartledge_app；
--    - 所有向量语句走 PgVectorTenantOperations：每条语句在同一个事务里先执行
--      SELECT set_config('app.tenant_id', ?, true)（第三个参数 true = 事务级，连接还池即失效）；
--    - 向量检索使用 over-fetch：ANN 索引遍历不参与策略过滤，只取 topK 会因策略丢弃而召回不足；
--      同时 WHERE 里带 tenant_id 前置条件，为 (tenant_id, embedding) 复合索引演进预留。
--
-- 核对方式（只读）：
--   SET ROLE smartledge_app;  -- 或直接用 smartledge_app 连接
--   SELECT count(*) FROM public.smartledge_document_embedding;                        -- 期望 0 行（未设置租户变量）
--   BEGIN; SELECT set_config('app.tenant_id','1',true);
--          SELECT count(*) FROM public.smartledge_document_embedding; COMMIT;         -- 期望租户 1 的行数

