ALTER TABLE documents
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN owner_id TEXT,
    ADD COLUMN visibility TEXT;

UPDATE documents
SET tenant_id = 'default',
    owner_id = 'anonymous',
    visibility = 'TENANT'
WHERE tenant_id IS NULL;

ALTER TABLE documents
    ALTER COLUMN tenant_id SET NOT NULL,
    ALTER COLUMN owner_id SET NOT NULL,
    ALTER COLUMN visibility SET NOT NULL,
    ADD CONSTRAINT chk_documents_visibility CHECK (visibility IN ('PRIVATE', 'TENANT'));

CREATE INDEX idx_documents_tenant_created_at
    ON documents (tenant_id, created_at DESC);

CREATE INDEX idx_documents_tenant_status
    ON documents (tenant_id, status);

CREATE INDEX idx_documents_tenant_owner
    ON documents (tenant_id, owner_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    document_id UUID,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_events_tenant_created_at
    ON audit_events (tenant_id, created_at DESC);

CREATE INDEX idx_audit_events_document_id
    ON audit_events (document_id, created_at DESC);

CREATE INDEX idx_audit_events_trace_id
    ON audit_events (trace_id);

CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL,
    job_type TEXT NOT NULL CHECK (job_type IN ('CHUNK', 'EMBED', 'FORCE_RECHUNK', 'REEMBED')),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ingestion_jobs_document_created_at
    ON ingestion_jobs (document_id, created_at DESC);

CREATE INDEX idx_ingestion_jobs_tenant_created_at
    ON ingestion_jobs (tenant_id, created_at DESC);
