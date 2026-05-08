-- document_chunks stores each text chunk and its vector embedding.
-- embedding vector(384) matches all-MiniLM-L6-v2 output dimensions.
-- The HNSW index enables sub-second cosine similarity search at 10K+ doc scale.
CREATE TABLE IF NOT EXISTS ingestion.document_chunks (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID    NOT NULL,
    tenant_id   UUID    NOT NULL,
    chunk_index INTEGER NOT NULL,
    content     TEXT    NOT NULL,
    embedding   vector(384),
    page_number INTEGER,
    char_start  INTEGER,
    char_end    INTEGER,
    token_count INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(document_id, chunk_index)
);

-- HNSW index for cosine similarity (works on empty table, no minimum data needed)
CREATE INDEX IF NOT EXISTS idx_chunks_embedding
    ON ingestion.document_chunks USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_chunks_tenant_id   ON ingestion.document_chunks(tenant_id);
CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON ingestion.document_chunks(document_id);
