CREATE TABLE IF NOT EXISTS ingestion.documents (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    uploaded_by       UUID        NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    s3_key            VARCHAR(1000) NOT NULL,
    content_type      VARCHAR(100),
    file_size_bytes   BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    job_id            UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_documents_tenant_id  ON ingestion.documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_documents_job_id     ON ingestion.documents(job_id);
CREATE INDEX IF NOT EXISTS idx_documents_status     ON ingestion.documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_status ON ingestion.documents(tenant_id, status);
