CREATE TABLE parent_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    text TEXT NOT NULL,
    char_start INTEGER NOT NULL CHECK (char_start >= 0),
    char_end INTEGER NOT NULL CHECK (char_end >= char_start),
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_id, chunk_index)
);

CREATE TABLE child_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    parent_chunk_id UUID NOT NULL REFERENCES parent_chunks(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    text TEXT NOT NULL,
    char_start INTEGER NOT NULL CHECK (char_start >= 0),
    char_end INTEGER NOT NULL CHECK (char_end >= char_start),
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_parent_chunks_document_id ON parent_chunks (document_id, chunk_index);
CREATE INDEX idx_child_chunks_document_id ON child_chunks (document_id, chunk_index);
CREATE INDEX idx_child_chunks_parent_chunk_id ON child_chunks (parent_chunk_id, chunk_index);
