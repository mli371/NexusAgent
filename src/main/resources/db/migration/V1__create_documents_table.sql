CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    original_filename TEXT NOT NULL,
    content_type TEXT,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 TEXT NOT NULL,
    minio_bucket TEXT NOT NULL,
    minio_object_key TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('STORED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_documents_created_at ON documents (created_at DESC);
CREATE INDEX idx_documents_status ON documents (status);
