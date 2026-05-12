CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE child_chunk_embeddings (
    child_chunk_id UUID PRIMARY KEY REFERENCES child_chunks(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    embedding VECTOR(384) NOT NULL,
    provider TEXT NOT NULL,
    model_name TEXT NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension = 384),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_child_chunk_embeddings_document_id
    ON child_chunk_embeddings (document_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_am WHERE amname = 'hnsw') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_child_chunk_embeddings_embedding_hnsw
                 ON child_chunk_embeddings
                 USING hnsw (embedding vector_cosine_ops)';
    END IF;
END $$;
